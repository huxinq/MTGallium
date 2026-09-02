package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.ai.engine.hidden.Determinizer
import com.wingedsheep.ai.engine.hidden.KnownDeckSampleResult
import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.ai.engine.AiProfile
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.PriorityChangedEvent
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.ai.engine.SimulationTraceStep
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import kotlin.math.tanh
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyAnnotatedSearchWorld
import org.mtgallium.agent.infoset.core.ProgressiveSearchWorld
import org.mtgallium.agent.infoset.core.ReusableSearchWorld
import org.mtgallium.agent.infoset.core.DerivedCacheTransferSearchWorld
import org.mtgallium.agent.infoset.core.SearchStepResult
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SearchWorldReuseKey
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import kotlinx.serialization.encodeToString

/** Trusted, state-owning implementation of the narrow [SearchWorld] facade. */
class ArgentumSearchWorld private constructor(
    private val environment: GameEnvironment,
    private val gameId: String,
    private val seedBase: Long,
    private val aliases: Map<EntityId, String>,
    private val history: PerspectiveHistory,
    private var decisionIndex: Int,
    private val expander: UnifiedSemanticExpander,
    private val heuristicAnnotator: ArgentumHeuristicAnnotator?,
    private val knownDecks: Map<String, Map<String, Int>>,
    private val heuristicResolutionSink: (ArgentumHeuristicResolution) -> Unit,
) : ProgressiveSearchWorld, ReusableSearchWorld, PolicyAnnotatedSearchWorld, DerivedCacheTransferSearchWorld {
    private val projector = SafeObservationProjector()
    private val exactObservedActionExpander = UnifiedSemanticExpander()
    private var cachedExpansion: CachedExpansion? = null
    private var auditedState: GameState? = null
    private val cachedProjections = mutableMapOf<EntityId, StateCache<PreparedSemanticExpansionInput>>()
    private val cachedSafeProjections = mutableMapOf<EntityId, StateCache<SafeObservationProjection>>()
    private val cachedInformationStates = mutableMapOf<String, StateCache<PolicyInformationState>>()
    private var cachedAuthoritativeFingerprint: StateCache<String>? = null

    override fun actorToAct(): String? = policyActor(environment)?.let(aliases::getValue)

    /** Declared candidate-generation behavior used by this world and all of its ordinary forks. */
    fun semanticExpansionSpecification(): UnifiedSemanticExpansionSpecification =
        expander.behaviorSpecification

    override fun informationState(viewer: String): PolicyInformationState {
        cachedInformationStates[viewer]?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }?.let { return it.value }
        val viewerId = rawPlayer(viewer)
        val projection = project(viewerId)
        val knowledge = history.knowledgeForViewer(viewerId, projection.observation, knownDecks)
        val actorId = policyActor(environment)
        val expansion = if (actorId == viewerId && !environment.isTerminal) {
            expansionResult().policy
        } else {
            PolicyExpansion(
                candidates = emptyList(),
                isExhaustive = true,
                estimatedCandidateCount = 0,
                proposalVersion = "not-acting-v1",
                proposalSeed = proposalSeed(),
            )
        }
        return PolicyInformationStateFactory.build(
            projection = projection,
            history = history.forViewer(viewerId),
            historyCommitment = history.commitmentForViewer(viewerId),
            expansion = expansion,
            actingPlayerId = actorId?.let(aliases::getValue),
            terminated = environment.isTerminal,
            winnerId = environment.winnerId?.let(aliases::getValue),
            knowledge = knowledge,
        ).also { information ->
            cachedInformationStates[viewer] = StateCache(environment.state, decisionIndex, information)
        }
    }

    /** Trusted conformance probe; returns counts/codes only and never exposes hidden identities. */
    fun hiddenTruthConformanceProbe(viewer: String): HiddenTruthConformanceProbe {
        val viewerId = rawPlayer(viewer)
        val opponent = environment.playerIds.firstOrNull { it != viewerId }
            ?: return HiddenTruthConformanceProbe(false, false, false, "NO_OPPONENT_HIDDEN_ZONE")
        val hidden = environment.state.getHand(opponent) + environment.state.getLibrary(opponent)
        if (hidden.size < 2) return HiddenTruthConformanceProbe(false, false, false, "INSUFFICIENT_HIDDEN_OBJECTS")
        val first = hidden.first()
        val last = hidden.last()
        val firstCard = environment.state.getEntity(first)?.get<CardComponent>()
            ?: return HiddenTruthConformanceProbe(false, false, false, "MISSING_HIDDEN_CARD")
        val lastCard = environment.state.getEntity(last)?.get<CardComponent>()
            ?: return HiddenTruthConformanceProbe(false, false, false, "MISSING_HIDDEN_CARD")
        val permutedState = environment.state
            .updateEntity(first) { it.with(lastCard) }
            .updateEntity(last) { it.with(firstCard) }
        val permutedEnvironment = environment.fork().also {
            it.restore(permutedState, environment.playerIds, environment.stepCount)
        }
        val permuted = ArgentumSearchWorld(
            environment = permutedEnvironment,
            gameId = gameId,
            seedBase = seedBase,
            aliases = aliases,
            history = history.fork(),
            decisionIndex = decisionIndex,
            expander = expander,
            heuristicAnnotator = heuristicAnnotator,
            knownDecks = knownDecks,
            heuristicResolutionSink = heuristicResolutionSink,
        )
        val leftInformation = informationState(viewer)
        val rightInformation = permuted.informationState(viewer)
        val informationEqual = leftInformation == rightInformation
        val inputEqual = runCatching {
            PolicyJson.format.encodeToString(BoundedPolicyInputCompiler.compile(leftInformation)) ==
                PolicyJson.format.encodeToString(BoundedPolicyInputCompiler.compile(rightInformation))
        }.getOrDefault(false)
        val expansionEqual = if (actorToAct() == viewer) {
            val left = expandChoices()
            val right = permuted.expandChoices()
            left.copy(proposalSeed = 0L) == right.copy(proposalSeed = 0L) &&
                left.proposalSeed == right.proposalSeed
        } else true
        return HiddenTruthConformanceProbe(informationEqual, inputEqual, expansionEqual, null)
    }

    fun verifyKnowledgeSupport(
        viewer: String,
        cardRegistry: CardRegistry,
        seed: Long,
        particleCount: Int = 8,
    ): KnowledgeSupportVerification = runCatching {
        val information = informationState(viewer)
        val batch = ArgentumKnownDeckBeliefWorldSource(this, cardRegistry)
            .sample(information, knownDecks, seed, particleCount)
        val codes = batch.particles.mapNotNull { particle ->
            val sampledWorld = particle.value as? ArgentumSearchWorld ?: return@mapNotNull "WORLD_TYPE"
            sampledWorld.knowledgeSupportFailure(viewer, information)
        }
        KnowledgeSupportVerification(batch.particles.size, codes.size, codes.firstOrNull())
    }.getOrElse { KnowledgeSupportVerification(0, 1, it::class.simpleName ?: "SUPPORT_FAILURE") }

    fun persistentHistoryForkSharesPrefix(viewer: String): Boolean =
        history.sharesLedgerPrefixWith(history.fork(), rawPlayer(viewer))

    /**
     * Return a redacted reason when this complete sampled world contradicts facts remembered in
     * [expected]. A null result establishes only agreement with that perspective's current safe
     * observation, knowledge projection, visible history, known objects/zones, and remembered
     * library prefix.
     */
    fun knowledgeSupportFailure(viewer: String, expected: PolicyInformationState): String? {
        val sampled = informationState(viewer)
        ArgentumInformationSupport.failure(sampled, expected)?.let { return it }
        return ArgentumRememberedFactSupport.failure(
            state = environment.state,
            playersByAlias = aliases.entries.associate { (raw, alias) -> alias to raw },
            objectBindings = history.knowledgeObjectBindingsForViewer(rawPlayer(viewer)),
            knowledge = expected.knowledge,
        )
    }

    override fun expandChoices(): PolicyExpansion = expansionResult().policy

    override fun expandChoices(limit: Int): PolicyExpansion = expansionResult(limit).policy

    override fun expandChoicesWithPolicyAnnotations(): PolicyExpansion =
        expansionResult(includePolicyAnnotations = true).policy

    override fun expandChoicesWithPolicyAnnotations(limit: Int): PolicyExpansion =
        expansionResult(limit, includePolicyAnnotations = true).policy

    /**
     * Resolve a semantic choice against the current authoritative expansion without advancing this
     * world. Live hosts use this to submit the teacher's choice to their own authoritative engine;
     * the action is observed back through [applyObservedAction] only after that engine accepts it.
     */
    fun resolveChoice(choice: SemanticChoice): ArgentumResolvedChoice {
        val expansion = expansionContaining(choice)
        val canonical = expansion.policy.candidates.singleOrNull { it.signature == choice.signature }
            ?: error("Choice ${choice.signature} is absent from the current semantic expansion")
        require(canonical.canonicalPayload == choice.canonicalPayload) {
            "Choice payload does not match its current signature"
        }
        return when (val engineChoice = expansion.engineChoices.getValue(choice.signature)) {
            is ArgentumEngineChoice.Action -> ArgentumResolvedChoice.Action(engineChoice.value)
            is ArgentumEngineChoice.Decision -> ArgentumResolvedChoice.Decision(engineChoice.value)
        }
    }

    /**
     * Apply one action accepted by an external authoritative engine through the same semantic step
     * used by arena games. Decision ids are rebound to this shadow world's current pending decision
     * because those ids are intentionally not deterministic across reconstructions.
     */
    fun applyObservedAction(action: GameAction): ArgentumObservedStep {
        val actor = requireNotNull(policyActor(environment)) { "No actor in non-terminal world" }
        val rebound = if (action is SubmitDecision) {
            val pendingId = environment.pendingDecision?.id
                ?: error("Observed a decision response while the shadow world has no pending decision")
            action.copy(response = action.response.withDecisionId(pendingId))
        } else action
        // Observed host actions are always decoded against the unpruned rules profile. A local
        // search abstraction may suppress mana actions for proposal purposes, but it must still
        // accept and record one taken by a human or another policy.
        val expansion = exactObservedActionExpander.expand(
            environment,
            proposalSeed(),
            MAX_OBSERVED_EXPANSION,
        )
        val match = expansion.engineChoices.entries.singleOrNull { (_, choice) ->
            when (choice) {
                is ArgentumEngineChoice.Action -> choice.value == rebound
                is ArgentumEngineChoice.Decision ->
                    rebound is SubmitDecision &&
                        rebound.playerId == actor &&
                        choice.value == rebound.response
            }
        } ?: error(
            "Observed ${action::class.simpleName} is absent or ambiguous in the current semantic expansion " +
                "(${expansion.policy.candidates.size}/${expansion.policy.estimatedCandidateCount})"
        )
        val semantic = expansion.policy.candidates.single { it.signature == match.key }
        return ArgentumObservedStep(
            semantic,
            applyChoice(semantic, suppliedExpansion = expansion),
        )
    }

    /** Stable full-truth fingerprint used only to prove a trusted shadow matches its live host. */
    fun authoritativeFingerprint(): String {
        cachedAuthoritativeFingerprint?.takeIf { it.state === environment.state }?.let { return it.value }
        return ArgentumStateFingerprint.of(environment.state).also { fingerprint ->
            cachedAuthoritativeFingerprint = StateCache(environment.state, decisionIndex, fingerprint)
        }
    }

    /** Uncached trusted check used to prove planning did not mutate the live engine state. */
    fun freshAuthoritativeFingerprintForHost(): String = ArgentumStateFingerprint.of(environment.state)

    /** Privileged replay diagnostic only; maps authoritative state components to opaque digests. */
    fun authoritativeComponentDigestsForHost(): Map<String, String> =
        ArgentumStateFingerprint.componentDigests(environment.state)

    fun authoritativeStateEvidenceForHost(): ArgentumAuthoritativeStateEvidence =
        ArgentumStateFingerprint.evidence(environment.state).also { evidence ->
            cachedAuthoritativeFingerprint = StateCache(environment.state, decisionIndex, evidence.fingerprint)
        }

    override fun privateSearchReuseKey(): SearchWorldReuseKey =
        SearchWorldReuseKey.fromTrustedDigest(authoritativeFingerprint())

    override fun copyDerivedCachesFrom(source: SearchWorld): Boolean {
        val other = source as? ArgentumSearchWorld ?: return false
        if (environment.state !== other.environment.state || decisionIndex != other.decisionIndex) return false
        if (aliases.keys.any { viewer -> !history.sharesLedgerPrefixWith(other.history, viewer) }) return false
        cachedExpansion = other.cachedExpansion
        cachedProjections.clear()
        cachedProjections.putAll(other.cachedProjections.filterValues {
            it.state === environment.state && it.decisionIndex == decisionIndex
        })
        cachedSafeProjections.clear()
        cachedSafeProjections.putAll(other.cachedSafeProjections.filterValues {
            it.state === environment.state && it.decisionIndex == decisionIndex
        })
        cachedInformationStates.clear()
        cachedInformationStates.putAll(other.cachedInformationStates.filterValues {
            it.state === environment.state && it.decisionIndex == decisionIndex
        })
        cachedAuthoritativeFingerprint = other.cachedAuthoritativeFingerprint?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }
        auditedState = other.auditedState?.takeIf { it === environment.state }
        return true
    }

    /** Trusted host bridge; never expose this value through a perspective-safe policy API. */
    fun authoritativeStateForHost(): GameState = environment.state

    /** Initializer events for privileged replay headers; call only before the first step. */
    fun initializationEventsForHost(): List<com.wingedsheep.engine.core.GameEvent> {
        check(decisionIndex == 0) { "Initialization events are only available before the first decision" }
        return environment.events
    }

    /**
     * Apply one player choice and stop before any other player is asked to pass or respond.
     * Search must expose those later choices as separate branches.
     */
    override fun step(choice: SemanticChoice): SearchStepResult = applyChoice(choice)

    /**
     * Apply exactly one semantic engine action without the AI simulator's quiet-state passes.
     *
     * Retained as an explicit call site for tactical proof code. Ordinary search now has the same
     * one-choice boundary through [step].
     */
    fun stepRaw(choice: SemanticChoice): SearchStepResult = applyChoice(choice)

    /** Authoritative arena step recording exactly the submitted player action for replay. */
    fun stepWithReplayTrace(choice: SemanticChoice): ArgentumReplayStep {
        val result = applyChoice(choice, traceRawAction = true)
        return ArgentumReplayStep(result, environment.lastStepTrace)
    }

    private fun applyChoice(
        choice: SemanticChoice,
        suppliedExpansion: UnifiedExpansionResult? = null,
        traceRawAction: Boolean = false,
    ): SearchStepResult {
        check(!environment.isTerminal) { "Cannot step a terminal search world" }
        val actor = requireNotNull(policyActor(environment))
        var expansion = suppliedExpansion
            ?: cachedExpansion?.annotated?.expansion?.takeIf { annotated ->
                annotated.policy.candidates.any { it.signature == choice.signature }
            }
            ?: expansionResult(cachedExpansion?.limit ?: DEFAULT_EXPANSION_LIMIT)
        if (suppliedExpansion == null &&
            expansion.policy.candidates.none { it.signature == choice.signature } &&
            !expansion.policy.isExhaustive
        ) {
            for (limit in STEP_EXPANSION_LIMITS) {
                if (limit <= (cachedExpansion?.limit ?: 0)) continue
                expansion = expansionResult(limit)
                if (expansion.policy.candidates.any { it.signature == choice.signature } || expansion.policy.isExhaustive) break
            }
        }
        val canonical = expansion.policy.candidates.singleOrNull { it.signature == choice.signature }
            ?: return SearchStepResult(
                false,
                "Choice ${choice.signature} (${choice.display.label}) is absent from the current semantic expansion " +
                    "of ${expansion.policy.candidates.size}/${expansion.policy.estimatedCandidateCount}; " +
                    "available=${expansion.policy.candidates.joinToString(limit = 12) { it.signature + ":" + it.display.label }}",
            )
        if (canonical.canonicalPayload != choice.canonicalPayload) {
            return SearchStepResult(false, "Choice payload does not match its current signature")
        }
        val engineChoice = expansion.engineChoices.getValue(choice.signature)
        val beforeState = environment.state
        // Keep the already-built Gym input as well as the safe projection. A pure priority
        // transfer changes only three visible priority fields, so the next inputs can be derived
        // exactly without rebuilding every zone/card view and StateDigest.
        val beforePrepared = aliases.keys.associateWith(::preparedProjection)
        val before = beforePrepared.mapValues { it.value.projection }
        val privateChoice = engineChoice is ArgentumEngineChoice.Decision &&
            environment.pendingDecision.isPrivateToChooser() ||
            (engineChoice as? ArgentumEngineChoice.Action)?.value is BottomCards
        val historyKind = when (canonical.operationFamily) {
            SemanticOperationFamily.MULLIGAN -> PolicyHistoryEventKind.MULLIGAN
            SemanticOperationFamily.DECLARE_ATTACKERS,
            SemanticOperationFamily.DECLARE_BLOCKERS -> PolicyHistoryEventKind.COMBAT_DECLARATION
            SemanticOperationFamily.PASS_PRIORITY -> PolicyHistoryEventKind.PRIORITY_PASS
            SemanticOperationFamily.DECISION_RESPONSE -> PolicyHistoryEventKind.DECISION
            else -> PolicyHistoryEventKind.ACTION
        }
        when (engineChoice) {
            is ArgentumEngineChoice.Action -> if (traceRawAction) {
                environment.stepRawTraced(engineChoice.value)
            } else {
                environment.stepRaw(engineChoice.value)
            }
            is ArgentumEngineChoice.Decision -> if (traceRawAction) {
                environment.stepRawTraced(SubmitDecision(actor, engineChoice.value))
            } else {
                environment.stepRaw(SubmitDecision(actor, engineChoice.value))
            }
        }
        if (environment.lastRejection != null) {
            return SearchStepResult(false, environment.lastRejection)
        }
        history.recordChoice(
            actor,
            canonical,
            privateChoice,
            historyKind,
            strategicallyOptional = expansion.policy.candidates.size > 1 || !expansion.policy.isExhaustive,
            libraryBottomObjects = ((engineChoice as? ArgentumEngineChoice.Action)?.value as? BottomCards)
                ?.cardIds
                .orEmpty()
                .map { objectId ->
                    LibraryBottomKnowledge(
                        objectId = objectId,
                        cardName = requireNotNull(environment.state.getEntity(objectId)?.get<CardComponent>()?.name) {
                            "Accepted mulligan-bottom object is missing its card identity"
                        },
                    )
                },
        )
        decisionIndex++
        cachedExpansion = null
        invalidateStateDerivedCaches()
        val purePriorityTransfer = environment.lastStepEvents.singleOrNull() as? PriorityChangedEvent
        val isPurePriorityTransfer = canonical.operationFamily == SemanticOperationFamily.PASS_PRIORITY &&
            purePriorityTransfer != null
        val after = if (isPurePriorityTransfer) {
            val nextActor = purePriorityTransfer.playerId
            val priorityAlias = aliases.getValue(purePriorityTransfer.playerId)
            before.mapValues { (viewer, projection) ->
                projection.withPriority(priorityAlias).also { updated ->
                    cachedSafeProjections[viewer] = StateCache(environment.state, decisionIndex, updated)
                    val legalActions = if (viewer == nextActor) environment.legalActions() else emptyList()
                    val priorObservation = beforePrepared.getValue(viewer).observation
                    val updatedObservation = priorObservation.copy(
                        agentToAct = nextActor,
                        priorityPlayerId = nextActor,
                        players = priorObservation.players.map { player ->
                            player.copy(hasPriority = player.id == nextActor)
                        },
                        // The expander consumes the authoritative LegalAction list above. Its
                        // presentation DTOs and Gym stateDigest are derived fields, deliberately
                        // excluded from semantic identity and unnecessary on this internal path.
                        legalActions = emptyList(),
                        stateDigest = "",
                    )
                    cachedProjections[viewer] = StateCache(
                        environment.state,
                        decisionIndex,
                        PreparedSemanticExpansionInput(viewer, legalActions, updatedObservation, updated),
                    )
                }
            }
        } else {
            aliases.keys.associateWith(::project)
        }
        val semanticEvents = history.recordEngineEvents(
            engineEvents = environment.lastStepEvents,
            actorViewer = actor,
            beforeState = beforeState,
            afterState = environment.state,
            before = before,
            after = after,
        )
        // PriorityChangedEvent already records every field changed by the engine's pure transfer.
        // Adding a catch-all observation transition here merely repeats that fact, including an
        // always-empty zone delta, in both ledgers and their hash chains.
        val visibleTransitions = if (isPurePriorityTransfer) {
            emptyList()
        } else {
            history.recordVisibleTransition(
                before.mapValues { it.value.observation },
                after.mapValues { it.value.observation },
                returnViewer = actor,
            )
        }
        val forced = semanticEvents + visibleTransitions
        return SearchStepResult(true, forcedTransitions = forced, privateToActor = privateChoice)
    }

    override fun fork(): SearchWorld = ArgentumSearchWorld(
        environment = environment.fork(),
        gameId = gameId,
        seedBase = seedBase,
        aliases = aliases,
        history = history.fork(),
        decisionIndex = decisionIndex,
        expander = expander,
        heuristicAnnotator = heuristicAnnotator,
        knownDecks = knownDecks,
        heuristicResolutionSink = heuristicResolutionSink,
    ).also { fork ->
        // GameEnvironment forks preserve the exact immutable state and entity identities. The
        // validated engine choices in this expansion therefore remain valid until either world
        // takes a step. Reusing them avoids rebuilding large combat candidate families once per
        // simulation; worlds reconstructed from a different sampled state use withSampledState()
        // and deliberately do not inherit this cache.
        fork.cachedExpansion = cachedExpansion
        fork.cachedProjections.putAll(cachedProjections.filterValues { it.state === fork.environment.state })
        fork.cachedSafeProjections.putAll(
            cachedSafeProjections.filterValues { it.state === fork.environment.state }
        )
        fork.cachedInformationStates.putAll(
            cachedInformationStates.filterValues {
                it.state === fork.environment.state && it.decisionIndex == fork.decisionIndex
            }
        )
        fork.cachedAuthoritativeFingerprint = cachedAuthoritativeFingerprint?.takeIf {
            it.state === fork.environment.state
        }
        fork.auditedState = auditedState?.takeIf { it === fork.environment.state }
    }

    /** Rebinds the root action-space policy after an exact scenario has been constructed. */
    fun withActionSpaceProfile(profile: SearchActionSpaceProfile): ArgentumSearchWorld =
        ArgentumSearchWorld(
            environment = environment.fork(),
            gameId = gameId,
            seedBase = seedBase,
            aliases = aliases,
            history = history.fork(),
            decisionIndex = decisionIndex,
            expander = UnifiedSemanticExpander(actionSpaceProfile = profile),
            heuristicAnnotator = heuristicAnnotator,
            knownDecks = knownDecks,
            heuristicResolutionSink = heuristicResolutionSink,
        )

    override fun terminalPayoff(rootPlayer: String): Double? {
        if (!environment.isTerminal) return null
        val raw = rawPlayer(rootPlayer)
        return when (environment.winnerId) {
            raw -> 1.0
            null -> 0.0
            else -> -1.0
        }
    }

    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double {
        require(evaluatorId == ARGENTUM_BOARD_EVALUATOR_V1) {
            "Evaluator '$evaluatorId' is not permitted at the sampled-world boundary"
        }
        terminalPayoff(rootPlayer)?.let { return it }
        return tanh(environment.evaluate(rawPlayer(rootPlayer)) / 20.0)
    }

    private fun expansionResult(
        limit: Int = cachedExpansion?.limit ?: DEFAULT_EXPANSION_LIMIT,
        includePolicyAnnotations: Boolean = false,
    ): UnifiedExpansionResult {
        val actor = requireNotNull(policyActor(environment)) { "No actor in non-terminal world" }
        val seed = proposalSeed()
        val key = "${aliases.getValue(actor)}:$decisionIndex:$seed"
        // Candidate-family conformance is scoped to a requested limit. Returning a previously
        // widened family for a later 64-choice request would silently change the tree contract.
        val cached = cachedExpansion?.takeIf { it.key == key && it.limit == limit }
        val base = cached?.base ?: expander.expandPrepared(
                environment,
                seed,
                limit,
                cachedExpansion?.takeIf { it.key == key && it.limit < limit }?.base,
                preparedInput = preparedProjection(actor),
            )
                .also { expansion ->
                    val priorAnchor = cachedExpansion
                        ?.takeIf { it.key == key }
                        ?.annotated
                        ?.diagnosis
                        ?.takeIf {
                            it.resolution == ArgentumHeuristicResolution.VALIDATED_ATTACK_ANCHOR ||
                                it.resolution == ArgentumHeuristicResolution.VALIDATED_BLOCK_ANCHOR
                        }
                        ?.choice
                        ?.signature
                    cachedExpansion = CachedExpansion(key, limit, expansion, priorAnchorSignature = priorAnchor)
                }
        if (!includePolicyAnnotations || heuristicAnnotator == null) return base
        val current = requireNotNull(cachedExpansion).takeIf { it.key == key && it.limit == limit }
            ?: CachedExpansion(key, limit, base).also { cachedExpansion = it }
        val annotation = current.annotated ?: heuristicAnnotator.annotate(
            environment,
            aliases,
            base,
            seed,
            rememberedObjectIds = rememberedKnowledgeObjectIds(
                aliases.getValue(actor),
                informationState(aliases.getValue(actor)),
            ),
            encodeSemanticChoice = { choice -> expander.encodePreparedChoice(choice, preparedProjection(actor)) },
            priorAnchorSignature = current.priorAnchorSignature,
        ).also { resolved ->
            current.annotated = resolved
            resolved.diagnosis.resolution?.let(heuristicResolutionSink)
        }
        return annotation.expansion
    }

    private fun expansionContaining(choice: SemanticChoice): UnifiedExpansionResult {
        cachedExpansion?.annotated?.expansion?.takeIf { annotated ->
            annotated.policy.candidates.any { it.signature == choice.signature }
        }?.let { return it }
        var expansion = expansionResult(cachedExpansion?.limit ?: DEFAULT_EXPANSION_LIMIT)
        if (expansion.policy.candidates.any { it.signature == choice.signature } || expansion.policy.isExhaustive) {
            return expansion
        }
        for (limit in STEP_EXPANSION_LIMITS) {
            if (limit <= (cachedExpansion?.limit ?: 0)) continue
            expansion = expansionResult(limit)
            if (expansion.policy.candidates.any { it.signature == choice.signature } || expansion.policy.isExhaustive) {
                break
            }
        }
        return expansion
    }

    private fun project(viewer: EntityId): SafeObservationProjection {
        cachedSafeProjections[viewer]?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }?.let { return it.value }
        return preparedProjection(viewer).projection
    }

    private fun preparedProjection(viewer: EntityId): PreparedSemanticExpansionInput {
        requireSupportedInformationState()
        cachedProjections[viewer]?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }?.let { return it.value }
        val legal = if (viewer == policyActor(environment)) environment.legalActions() else emptyList()
        val observation = ObservationBuilder(environment.cardRegistry).build(environment.state, viewer, legal)
            .observation as TrainingObservation
        val projection = cachedSafeProjections[viewer]?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }?.value ?: projector.project(
            observation,
            aliases,
            ArgentumPolicyRuntimeProjector.project(environment.state, viewer, environment.cardRegistry, observation),
        )
        return PreparedSemanticExpansionInput(
            actor = viewer,
            legalActions = legal,
            observation = observation,
            projection = projection,
        ).also { prepared ->
            cachedProjections[viewer] = StateCache(environment.state, decisionIndex, prepared)
            cachedSafeProjections[viewer] = StateCache(environment.state, decisionIndex, projection)
        }
    }

    private fun invalidateStateDerivedCaches() {
        cachedProjections.clear()
        cachedSafeProjections.clear()
        cachedInformationStates.clear()
        cachedAuthoritativeFingerprint = null
    }

    private fun requireSupportedInformationState() {
        val state = environment.state
        if (auditedState === state) return
        val reasons = buildSet {
            if (state.entities.values.any { it.has<FaceDownComponent>() }) add("FACE_DOWN_OBJECT")
            if (state.stack.any { state.getEntity(it)?.get<SpellOnStackComponent>()?.castFaceDown == true }) {
                add("FACE_DOWN_STACK_SPELL")
            }
            if (state.entities.values.any { it.get<RevealedToComponent>() != null }) add("SELECTIVE_REVEAL_STATE")
        }.sorted()
        if (reasons.isNotEmpty()) throw UnsupportedInformationStateException(reasons)
        auditedState = state
    }

    private fun proposalSeed(): Long = ComponentSeeds.derive(gameId, decisionIndex, seedBase, "proposal")

    private fun rawPlayer(alias: String): EntityId = aliases.entries.singleOrNull { it.value == alias }?.key
        ?: error("Unknown safe player id $alias")

    internal fun rawPlayerIds(): Map<String, EntityId> = aliases.entries.associate { (raw, safe) -> safe to raw }
    internal fun authoritativeState(): GameState = environment.state
    internal fun rememberedKnowledgeObjectIds(
        viewerAlias: String,
        expected: PolicyInformationState,
    ): Set<EntityId> {
        val bindings = history.knowledgeObjectBindingsForViewer(rawPlayer(viewerAlias))
        return expected.knowledge.knownObjects.map { knownObject ->
            bindings[knownObject.knowledgeObjectKey]
                ?: error("A represented remembered object is missing its trusted binding")
        }.toSet()
    }
    internal fun authoritativeRng(): GameRng = environment.state.rng
    internal fun environmentFork(): GameEnvironment = environment.fork()
    internal fun historyFork(): PerspectiveHistory = history.fork()
    internal fun decisionIndex(): Int = decisionIndex
    internal fun gameId(): String = gameId
    internal fun seedBase(): Long = seedBase
    internal fun expander(): UnifiedSemanticExpander = expander

    /** Determinized production-heuristic choice projected back to the semantic contract. */
    fun determinizedHeuristicChoice(maxCandidates: Int = 2_048): SemanticChoice {
        return determinizedHeuristicChoiceOrNull(maxCandidates)
            ?: error("No information-safe Argentum heuristic annotation at decision $decisionIndex")
    }

    /** Returns null when strict re-determinization cannot map the heuristic into the safe contract. */
    fun determinizedHeuristicChoiceOrNull(maxCandidates: Int = 2_048): SemanticChoice? =
        determinizedHeuristicChoiceDiagnosis(maxCandidates).choice

    /** Privileged operational diagnosis; never place the selected engine payload in public policy data. */
    fun determinizedHeuristicChoiceDiagnosis(maxCandidates: Int = 2_048): ArgentumHeuristicChoiceDiagnosis {
        val annotator = heuristicAnnotator ?: return ArgentumHeuristicChoiceDiagnosis(
            unavailableReason = ArgentumHeuristicUnavailableReason.ANNOTATOR_UNAVAILABLE,
        )
        val base = expansionResult(maxCandidates)
        val actor = requireNotNull(policyActor(environment)) { "No actor in non-terminal world" }
        val seed = proposalSeed()
        val key = "${aliases.getValue(actor)}:$decisionIndex:$seed"
        val current = requireNotNull(cachedExpansion).takeIf { it.key == key && it.limit == maxCandidates }
            ?: CachedExpansion(key, maxCandidates, base).also { cachedExpansion = it }
        val annotation = current.annotated ?: annotator.annotate(
            environment,
            aliases,
            base,
            seed,
            rememberedObjectIds = rememberedKnowledgeObjectIds(
                aliases.getValue(actor),
                informationState(aliases.getValue(actor)),
            ),
            encodeSemanticChoice = { choice -> expander.encodePreparedChoice(choice, preparedProjection(actor)) },
            priorAnchorSignature = current.priorAnchorSignature,
        ).also { resolved ->
            current.annotated = resolved
            resolved.diagnosis.resolution?.let(heuristicResolutionSink)
        }
        return annotation.diagnosis
    }

    /** Privileged evidence only; no authoritative state reference escapes this DTO. */
    fun privilegedDebugSnapshot(): ArgentumPrivilegedDebugSnapshot {
        fun names(ids: List<EntityId>): List<String> = ids.map { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name ?: "<missing:$id>"
        }
        val semanticPerspective = policyActor(environment) ?: environment.playerIds.first()
        val semanticObservation = ObservationBuilder(environment.cardRegistry).build(
            environment.state,
            semanticPerspective,
            if (environment.isTerminal) emptyList() else environment.legalActions(),
            revealAll = true,
        ).observation as TrainingObservation
        return ArgentumPrivilegedDebugSnapshot(
            decisionIndex = decisionIndex,
            authoritativeSemanticDigest = semanticObservation.stateDigest,
            hiddenHands = aliases.entries.associate { (raw, safe) -> safe to names(environment.state.getHand(raw)) },
            libraries = aliases.entries.associate { (raw, safe) -> safe to names(environment.state.getLibrary(raw)) },
            chanceTrace = listOf(
                "initial-seed:${environment.state.initialSeed}",
                "search-base-seed:$seedBase",
            ),
        )
    }

    internal fun withSampledState(
        state: GameState,
        futureChanceStreamIdentity: Long,
    ): ArgentumSearchWorld = withHypotheticalState(state, futureChanceStreamIdentity)

    /**
     * Fork the complete authoritative position for a privileged offline search while replacing its
     * future game-chance stream. The identity must come from declared experiment/search randomness;
     * this boundary deliberately never derives it from [GameState.rng] or [GameState.initialSeed].
     */
    fun forkForHypotheticalSearch(futureChanceStreamIdentity: Long): ArgentumSearchWorld =
        withHypotheticalState(environment.state, futureChanceStreamIdentity)

    private fun withHypotheticalState(
        state: GameState,
        futureChanceStreamIdentity: Long,
    ): ArgentumSearchWorld {
        val sampledEnvironment = environment.fork()
        sampledEnvironment.restore(
            state.copy(
                rng = GameRng.seeded(
                    ComponentSeeds.derive(futureChanceStreamIdentity, FUTURE_CHANCE_SEED_DOMAIN)
                )
            ),
            environment.playerIds,
            environment.stepCount,
        )
        return ArgentumSearchWorld(
            environment = sampledEnvironment,
            gameId = gameId,
            seedBase = seedBase,
            aliases = aliases,
            history = history.fork(),
            decisionIndex = decisionIndex,
            expander = expander,
            heuristicAnnotator = heuristicAnnotator,
            knownDecks = knownDecks,
            heuristicResolutionSink = heuristicResolutionSink,
        )
    }

    /** Adapter-internal audit seam for exercising construction from a reconstructed safe ledger. */
    internal fun withRememberedHistoryForVerification(reconstructedHistory: PerspectiveHistory): ArgentumSearchWorld =
        ArgentumSearchWorld(
            environment = environment.fork(),
            gameId = gameId,
            seedBase = seedBase,
            aliases = aliases,
            history = reconstructedHistory.fork(),
            decisionIndex = decisionIndex,
            expander = expander,
            heuristicAnnotator = heuristicAnnotator,
            knownDecks = knownDecks,
            heuristicResolutionSink = heuristicResolutionSink,
        )

    private data class CachedExpansion(
        val key: String,
        val limit: Int,
        val base: UnifiedExpansionResult,
        val priorAnchorSignature: String? = null,
        var annotated: ArgentumHeuristicAnnotation? = null,
    )
    private data class StateCache<T>(val state: GameState, val decisionIndex: Int, val value: T)

    companion object {
        const val ARGENTUM_BOARD_EVALUATOR_V1 = "argentum-board-v1"
        const val DEFAULT_EXPANSION_LIMIT = 64
        private const val FUTURE_CHANCE_SEED_DOMAIN = "argentum-hypothetical-future-chance-v1"
        private val STEP_EXPANSION_LIMITS = listOf(128, 256, 512, 1_024, 2_048)
        private const val MAX_OBSERVED_EXPANSION = 2_048
        fun create(
            environment: GameEnvironment,
            gameId: String,
            seedBase: Long,
            expander: UnifiedSemanticExpander = UnifiedSemanticExpander(),
            cardRegistry: CardRegistry? = null,
            knownDecks: Map<String, Map<String, Int>>? = null,
            projectionAuditSink: PerspectiveProjectionAuditSink = PerspectiveProjectionAuditSink.NONE,
            heuristicResolutionSink: (ArgentumHeuristicResolution) -> Unit = {},
        ): ArgentumSearchWorld {
            require(environment.playerIds.isNotEmpty()) { "Environment must be reset before wrapping" }
            require((cardRegistry == null) == (knownDecks == null)) {
                "Card registry and known decks must be supplied together for heuristic annotation"
            }
            val aliases = environment.playerIds.mapIndexed { index, id -> id to "p$index" }.toMap()
            return ArgentumSearchWorld(
                environment = environment,
                gameId = gameId,
                seedBase = seedBase,
                aliases = aliases,
                history = PerspectiveHistory(environment.playerIds, projectionAuditSink),
                decisionIndex = 0,
                expander = expander,
                heuristicAnnotator = cardRegistry?.let {
                    ArgentumHeuristicAnnotator(it, requireNotNull(knownDecks))
                },
                knownDecks = knownDecks.orEmpty(),
                heuristicResolutionSink = heuristicResolutionSink,
            )
        }
    }
}

/** Checks the complete represented player input before a hidden world can enter a belief batch. */
internal object ArgentumInformationSupport {
    fun failure(sampled: PolicyInformationState, expected: PolicyInformationState): String? {
        if (sampled.observation != expected.observation) return "SAFE_OBSERVATION_MISMATCH"
        if (sampled.knowledge != expected.knowledge) return "SAFE_KNOWLEDGE_MISMATCH"
        if (sampled.historyCommitment != expected.historyCommitment || sampled.history != expected.history) {
            return "SAFE_HISTORY_MISMATCH"
        }
        return null
    }
}

/** Checks represented remembered card facts against one proposed full engine position. */
internal object ArgentumRememberedFactSupport {
    fun failure(
        state: GameState,
        playersByAlias: Map<String, EntityId>,
        objectBindings: Map<String, EntityId>,
        knowledge: PolicyKnowledgeState,
    ): String? {
        fun zoneIds(ownerAlias: String, zone: String): List<EntityId>? {
            val owner = playersByAlias[ownerAlias] ?: return null
            val zoneType = runCatching { Zone.valueOf(zone) }.getOrNull() ?: return null
            return if (zoneType == Zone.STACK) {
                state.stack.filter { id ->
                    state.getEntity(id)?.get<CardComponent>()?.ownerId == owner
                }
            } else {
                state.getZone(ZoneKey(owner, zoneType))
            }
        }

        fun cardName(id: EntityId): String? = state.getEntity(id)?.get<CardComponent>()?.name
        fun names(ids: List<EntityId>): List<String> = ids.mapNotNull(::cardName)

        for (knownObject in knowledge.knownObjects) {
            val id = objectBindings[knownObject.knowledgeObjectKey]
                ?: return "KNOWN_OBJECT_BINDING_MISSING"
            if (cardName(id) != knownObject.cardName) return "KNOWN_OBJECT_CARD_MISMATCH"
            val zone = zoneIds(knownObject.ownerId, knownObject.zone)
                ?: return "KNOWN_OBJECT_ZONE_UNSUPPORTED"
            if (id !in zone) return "KNOWN_OBJECT_ZONE_MISMATCH"
        }
        for (zone in knowledge.zones) {
            val actualIds = zoneIds(zone.ownerId, zone.zone) ?: return "KNOWN_ZONE_UNSUPPORTED"
            if (actualIds.size != zone.size) return "ZONE_SIZE_MISMATCH"
            val actualCounts = names(actualIds).groupingBy { it }.eachCount()
            if (zone.knownCardCounts.any { (name, count) -> (actualCounts[name] ?: 0) < count }) {
                return "KNOWN_ZONE_CARD_MISMATCH"
            }
        }
        for (order in knowledge.knownLibraryOrders) {
            val actual = names(zoneIds(order.playerId, "LIBRARY") ?: return "LIBRARY_OWNER_MISSING")
            if (order.top.size > actual.size) return "LIBRARY_ORDER_LENGTH_MISMATCH"
            if (order.top.indices.any { index -> order.top[index]?.let { it != actual[index] } == true }) {
                return "LIBRARY_ORDER_MISMATCH"
            }
            if (order.bottom.size > actual.size) return "LIBRARY_BOTTOM_ORDER_LENGTH_MISMATCH"
            val bottomOffset = actual.size - order.bottom.size
            if (order.bottom.indices.any { index ->
                    order.bottom[index]?.let { it != actual[bottomOffset + index] } == true
                }
            ) {
                return "LIBRARY_BOTTOM_ORDER_MISMATCH"
            }
        }
        return null
    }
}

@Serializable
enum class ArgentumHeuristicResolution {
    RAW_EXACT,
    SEMANTIC_EQUIVALENT,
    VALIDATED_ATTACK_ANCHOR,
    VALIDATED_BLOCK_ANCHOR,
}

enum class ArgentumHeuristicUnavailableReason {
    ANNOTATOR_UNAVAILABLE,
    NO_POLICY_ACTOR,
    DECK_ALIAS_MISMATCH,
    DETERMINIZATION_UNSUPPORTED,
    ENGINE_CHOICE_UNMAPPED,
}

/**
 * Privileged explanation of the fail-closed production-heuristic adapter boundary. The engine
 * payload descriptions can contain authoritative entity ids and must not enter public policy data.
 */
data class ArgentumHeuristicChoiceDiagnosis(
    val choice: SemanticChoice? = null,
    val resolution: ArgentumHeuristicResolution? = null,
    val unavailableReason: ArgentumHeuristicUnavailableReason? = null,
    val reasonCodes: List<String> = emptyList(),
    val selectedEngineChoiceClass: String? = null,
    val selectedEngineChoiceDescription: String? = null,
    val candidateEngineChoiceClasses: List<String> = emptyList(),
    val selectedAcceptedBySampledState: Boolean? = null,
    val selectedAcceptedByAuthoritativeState: Boolean? = null,
    val closestCandidateEngineChoices: List<String> = emptyList(),
    val selectedSemanticSignature: String? = null,
    val semanticEquivalentCandidateSignatures: List<String> = emptyList(),
)

private data class ArgentumHeuristicAnnotation(
    val expansion: UnifiedExpansionResult,
    val diagnosis: ArgentumHeuristicChoiceDiagnosis,
)

sealed interface ArgentumResolvedChoice {
    data class Action(val value: GameAction) : ArgentumResolvedChoice
    data class Decision(val value: DecisionResponse) : ArgentumResolvedChoice
}

data class ArgentumObservedStep(
    val choice: SemanticChoice,
    val result: SearchStepResult,
)

data class ArgentumReplayStep(
    val result: SearchStepResult,
    val rawTransitions: List<SimulationTraceStep>,
)

/** Fail-closed boundary for authoritative states outside the frozen pool's visibility contract. */
class UnsupportedInformationStateException(
    val reasonCodes: List<String>,
) : IllegalStateException("UNSUPPORTED_INFORMATION_STATE:${reasonCodes.joinToString(",")}")

data class HiddenTruthConformanceProbe(
    val informationStateEqual: Boolean,
    val boundedInputEqual: Boolean,
    val expansionEqual: Boolean,
    val unavailableReason: String?,
) {
    val passed: Boolean get() = unavailableReason == null && informationStateEqual && boundedInputEqual && expansionEqual
}

data class KnowledgeSupportVerification(
    val particlesChecked: Int,
    val failures: Int,
    val failureCode: String?,
) {
    val passed: Boolean get() = particlesChecked > 0 && failures == 0
}

/** Choices over identities visible only to the chooser stay private; announced game choices do not. */
internal fun com.wingedsheep.engine.core.PendingDecision?.isPrivateToChooser(): Boolean = when (this) {
    is SearchLibraryDecision, is ReorderLibraryDecision -> true
    is SelectCardsDecision -> cardInfo != null
    else -> false
}

@kotlinx.serialization.Serializable
data class ArgentumPrivilegedDebugSnapshot(
    val decisionIndex: Int,
    /** Reveal-all semantic digest; deliberately canonicalizes ephemeral routing nonces. */
    val authoritativeSemanticDigest: String,
    val hiddenHands: Map<String, List<String>>,
    val libraries: Map<String, List<String>>,
    val chanceTrace: List<String>,
)

/**
 * Marks the action selected by Argentum's production heuristic after a strict re-determinization
 * from the acting player's perspective. The tag is therefore a function of player information and
 * an external seed, not of hidden truth in the authoritative world.
 */
private class ArgentumHeuristicAnnotator(
    private val cardRegistry: CardRegistry,
    private val knownDecks: Map<String, Map<String, Int>>,
) {
    private val determinizer = Determinizer(cardRegistry)

    fun annotate(
        environment: GameEnvironment,
        aliases: Map<EntityId, String>,
        expansion: UnifiedExpansionResult,
        seed: Long,
        rememberedObjectIds: Set<EntityId>,
        encodeSemanticChoice: (ArgentumEngineChoice) -> SemanticChoice,
        priorAnchorSignature: String? = null,
    ): ArgentumHeuristicAnnotation {
        val actor = policyActor(environment) ?: return unavailable(
            expansion,
            ArgentumHeuristicUnavailableReason.NO_POLICY_ACTOR,
        )
        val rawDecks = knownDecks.mapKeys { (alias, _) ->
            aliases.entries.singleOrNull { it.value == alias }?.key
                ?: error("Known deck uses unknown safe player $alias")
        }
        if (rawDecks.keys != aliases.keys) return unavailable(
            expansion,
            ArgentumHeuristicUnavailableReason.DECK_ALIAS_MISMATCH,
        )
        val pins = pinRememberedObjects(environment.state, actor, rememberedObjectIds)
        val sampledState = when (val sampled = determinizer.sampleKnownDeckWorld(
            pins.samplingState,
            actor,
            rawDecks,
            GameRng.seeded(ComponentSeeds.derive(seed, aliases.getValue(actor), "argentum-heuristic")),
        )) {
            is KnownDeckSampleResult.Success -> pins.restore(sampled.state)
            is KnownDeckSampleResult.Unsupported -> return unavailable(
                expansion,
                ArgentumHeuristicUnavailableReason.DETERMINIZATION_UNSUPPORTED,
                sampled.reasons.map { it::class.simpleName ?: "Unknown" }.distinct().sorted(),
            )
        }
        val selectedEngineChoice = select(sampledState, actor, expansion)
        val selected = expansion.engineChoices.entries.singleOrNull { (_, candidate) ->
            choicesEqual(candidate, selectedEngineChoice)
        }?.key
        if (selected != null) {
            return resolved(
                expansion = expansion,
                selected = expansion.policy.candidates.single { it.signature == selected },
                resolution = ArgentumHeuristicResolution.RAW_EXACT,
            )
        }

        val selectedSemantic = encodeSemanticChoice(selectedEngineChoice)
        val semanticMatches = expansion.policy.candidates.filter { it.signature == selectedSemantic.signature }
        if (semanticMatches.size == 1) {
            return resolved(
                expansion = expansion,
                selected = semanticMatches.single(),
                resolution = ArgentumHeuristicResolution.SEMANTIC_EQUIVALENT,
                promoteFirst = priorAnchorSignature == selectedSemantic.signature,
            )
        }

        val acceptedBySampledState = accepts(environment, sampledState, selectedEngineChoice)
        val acceptedByAuthoritativeState = accepts(environment, environment.state, selectedEngineChoice)
        val selectedAction = (selectedEngineChoice as? ArgentumEngineChoice.Action)?.value
        val anchorResolution = when {
            selectedAction is DeclareAttackers &&
                selectedSemantic.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS ->
                ArgentumHeuristicResolution.VALIDATED_ATTACK_ANCHOR
            selectedAction is DeclareBlockers &&
                selectedSemantic.operationFamily == SemanticOperationFamily.DECLARE_BLOCKERS ->
                ArgentumHeuristicResolution.VALIDATED_BLOCK_ANCHOR
            else -> null
        }
        val anchorEligible = semanticMatches.isEmpty() &&
            !expansion.policy.isExhaustive &&
            anchorResolution != null &&
            acceptedBySampledState && acceptedByAuthoritativeState
        if (anchorEligible) {
            return resolved(
                expansion = expansion,
                selected = selectedSemantic,
                resolution = requireNotNull(anchorResolution),
                anchorEngineChoice = selectedEngineChoice,
                promoteFirst = true,
                selectedAcceptedBySampledState = true,
                selectedAcceptedByAuthoritativeState = true,
            )
        }

        return unavailable(
            expansion = expansion,
            reason = ArgentumHeuristicUnavailableReason.ENGINE_CHOICE_UNMAPPED,
            reasonCodes = buildList {
                if (semanticMatches.size > 1) add("AMBIGUOUS_SEMANTIC_ENGINE_CHOICE")
                if (semanticMatches.isEmpty()) add("NO_SEMANTIC_ENGINE_CHOICE")
                if (expansion.policy.isExhaustive) add("EXHAUSTIVE_EXPANSION_CONTRADICTION")
                if (anchorResolution == null) {
                    add("ANCHOR_NOT_COMBAT_DECLARATION")
                }
                if (!acceptedBySampledState) add("ANCHOR_REJECTED_BY_SAMPLED_STATE")
                if (!acceptedByAuthoritativeState) add("ANCHOR_REJECTED_BY_AUTHORITATIVE_STATE")
            },
            selectedEngineChoice = selectedEngineChoice,
            selectedAcceptedBySampledState = acceptedBySampledState,
            selectedAcceptedByAuthoritativeState = acceptedByAuthoritativeState,
            closestCandidateEngineChoices = closestCandidates(selectedEngineChoice, expansion),
            selectedSemanticSignature = selectedSemantic.signature,
            semanticEquivalentCandidateSignatures = semanticMatches.map(SemanticChoice::signature),
        )
    }

    private fun resolved(
        expansion: UnifiedExpansionResult,
        selected: SemanticChoice,
        resolution: ArgentumHeuristicResolution,
        anchorEngineChoice: ArgentumEngineChoice? = null,
        promoteFirst: Boolean = false,
        selectedAcceptedBySampledState: Boolean? = null,
        selectedAcceptedByAuthoritativeState: Boolean? = null,
    ): ArgentumHeuristicAnnotation {
        val tagged = selected.copy(
            display = selected.display.copy(
                policyTags = selected.display.policyTags +
                    ARGENTUM_HEURISTIC_CHOICE_TAG_V1
            )
        )
        val ordinary = expansion.policy.candidates.filterNot { it.signature == selected.signature }
        val candidates = when {
            anchorEngineChoice != null -> listOf(tagged) + ordinary.take(
                (expansion.policy.candidates.size - 1).coerceAtLeast(0)
            )
            promoteFirst -> listOf(tagged) + ordinary
            else -> expansion.policy.candidates.map { choice ->
                if (choice.signature == selected.signature) tagged else choice
            }
        }
        val retainedSignatures = candidates.mapTo(linkedSetOf(), SemanticChoice::signature)
        val engineChoices = linkedMapOf<String, ArgentumEngineChoice>()
        candidates.forEach { choice ->
            engineChoices[choice.signature] = if (choice.signature == selected.signature && anchorEngineChoice != null) {
                anchorEngineChoice
            } else {
                expansion.engineChoices.getValue(choice.signature)
            }
        }
        check(engineChoices.keys == retainedSignatures)
        val annotated = UnifiedExpansionResult(
            policy = expansion.policy.copy(candidates = candidates),
            engineChoices = engineChoices,
            attemptedCandidates = expansion.attemptedCandidates,
            rejectedCandidates = expansion.rejectedCandidates,
            rejectedSignatures = expansion.rejectedSignatures,
        )
        return ArgentumHeuristicAnnotation(
            expansion = annotated,
            diagnosis = ArgentumHeuristicChoiceDiagnosis(
                choice = tagged,
                resolution = resolution,
                selectedAcceptedBySampledState = selectedAcceptedBySampledState,
                selectedAcceptedByAuthoritativeState = selectedAcceptedByAuthoritativeState,
                selectedSemanticSignature = tagged.signature,
                semanticEquivalentCandidateSignatures = listOf(tagged.signature)
                    .takeIf { resolution == ArgentumHeuristicResolution.SEMANTIC_EQUIVALENT }
                    .orEmpty(),
            ),
        )
    }

    private fun select(
        state: GameState,
        actor: EntityId,
        expansion: UnifiedExpansionResult,
    ): ArgentumEngineChoice = when {
            state.pendingDecision?.playerId == actor -> ArgentumEngineChoice.Decision(
                AIPlayer.create(cardRegistry, actor, AiProfile.PRODUCTION)
                    .respondToDecision(state, state.pendingDecision!!)
            )
            expansion.engineChoices.values.any { it is ArgentumEngineChoice.Action && it.value is KeepHand } -> {
                val mulligans = state.getEntity(actor)?.get<com.wingedsheep.engine.state.components.player.MulliganStateComponent>()
                    ?.mulligansTaken ?: 0
                val lands = state.getHand(actor).count { id ->
                    state.getEntity(id)?.get<CardComponent>()?.name?.let(cardRegistry::requireCard)?.isLand == true
                }
                val keep = mulligans >= 2 || lands in 2..5
                expansion.engineChoices.values.first { candidate ->
                    candidate is ArgentumEngineChoice.Action &&
                        if (keep) candidate.value is KeepHand else candidate.value is TakeMulligan
                }
            }
            expansion.engineChoices.values.any { it is ArgentumEngineChoice.Action && it.value is BottomCards } -> {
                expansion.engineChoices.values.filterIsInstance<ArgentumEngineChoice.Action>()
                    .filter { it.value is BottomCards }
                    .maxBy { candidate -> bottomScore(state, candidate.value as BottomCards) }
            }
            else -> ArgentumEngineChoice.Action(
                AIPlayer.create(cardRegistry, actor, AiProfile.PRODUCTION).chooseAction(state)
            )
        }

    private fun choicesEqual(left: ArgentumEngineChoice, right: ArgentumEngineChoice): Boolean = when {
        left is ArgentumEngineChoice.Action && right is ArgentumEngineChoice.Action -> left.value == right.value
        left is ArgentumEngineChoice.Decision && right is ArgentumEngineChoice.Decision -> left.value == right.value
        else -> false
    }

    private fun unavailable(
        expansion: UnifiedExpansionResult,
        reason: ArgentumHeuristicUnavailableReason,
        reasonCodes: List<String> = emptyList(),
        selectedEngineChoice: ArgentumEngineChoice? = null,
        selectedAcceptedBySampledState: Boolean? = null,
        selectedAcceptedByAuthoritativeState: Boolean? = null,
        closestCandidateEngineChoices: List<String> = emptyList(),
        selectedSemanticSignature: String? = null,
        semanticEquivalentCandidateSignatures: List<String> = emptyList(),
    ): ArgentumHeuristicAnnotation = ArgentumHeuristicAnnotation(
        expansion = expansion,
        diagnosis = ArgentumHeuristicChoiceDiagnosis(
            unavailableReason = reason,
            reasonCodes = reasonCodes,
            selectedEngineChoiceClass = selectedEngineChoice?.choiceClass(),
            selectedEngineChoiceDescription = selectedEngineChoice?.toString(),
            candidateEngineChoiceClasses = expansion.engineChoices.values.map { it.choiceClass() }.distinct().sorted(),
            selectedAcceptedBySampledState = selectedAcceptedBySampledState,
            selectedAcceptedByAuthoritativeState = selectedAcceptedByAuthoritativeState,
            closestCandidateEngineChoices = closestCandidateEngineChoices,
            selectedSemanticSignature = selectedSemanticSignature,
            semanticEquivalentCandidateSignatures = semanticEquivalentCandidateSignatures,
        ),
    )

    private fun accepts(
        environment: GameEnvironment,
        state: GameState,
        choice: ArgentumEngineChoice,
    ): Boolean = runCatching {
        val child = environment.fork().also {
            it.restore(state, environment.playerIds, environment.stepCount)
        }
        when (choice) {
            is ArgentumEngineChoice.Action -> child.step(choice.value)
            is ArgentumEngineChoice.Decision -> child.step(
                SubmitDecision(requireNotNull(policyActor(child)), choice.value)
            )
        }
        child.lastRejection == null
    }.getOrDefault(false)

    private fun closestCandidates(
        selected: ArgentumEngineChoice,
        expansion: UnifiedExpansionResult,
    ): List<String> {
        val selectedBlocks = (selected as? ArgentumEngineChoice.Action)?.value as? DeclareBlockers
        val candidates = expansion.engineChoices.values.filter { it.choiceClass() == selected.choiceClass() }
        if (selectedBlocks == null) return candidates.take(8).map(ArgentumEngineChoice::toString)
        val selectedEdges = selectedBlocks.blockers.flatMap { (blocker, attackers) ->
            attackers.map { attacker -> blocker to attacker }
        }.toSet()
        return candidates.sortedBy { candidate ->
            val action = (candidate as ArgentumEngineChoice.Action).value as DeclareBlockers
            val edges = action.blockers.flatMap { (blocker, attackers) ->
                attackers.map { attacker -> blocker to attacker }
            }.toSet()
            (selectedEdges - edges).size + (edges - selectedEdges).size
        }.take(8).map(ArgentumEngineChoice::toString)
    }

    private fun ArgentumEngineChoice.choiceClass(): String = when (this) {
        is ArgentumEngineChoice.Action -> value::class.simpleName ?: "UnknownAction"
        is ArgentumEngineChoice.Decision -> value::class.simpleName ?: "UnknownDecision"
    }

    private fun bottomScore(state: GameState, action: BottomCards): Int {
        val cards = action.cardIds.map { id ->
            val name = state.getEntity(id)?.get<CardComponent>()?.name ?: return@map null
            cardRegistry.requireCard(name)
        }.filterNotNull()
        val lands = cards.count { it.isLand }
        return cards.sumOf { it.cmc } + lands * 2
    }
}
