package org.mtgallium.agent.infoset.argentum

import org.mtgallium.agent.infoset.core.DecisionAdmission
import org.mtgallium.agent.infoset.core.DecisionView
import org.mtgallium.agent.infoset.core.DecisionSiteRequest
import org.mtgallium.agent.infoset.core.EpistemicState

import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.ai.engine.AiProfile
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameEvent
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
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.ExactlyOneSubmissionResult
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
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyAnnotatedSearchWorld
import org.mtgallium.agent.infoset.core.ProgressiveSearchWorld
import org.mtgallium.agent.infoset.core.DerivedCacheTransferSearchWorld
import org.mtgallium.agent.infoset.core.SearchStepResult
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import kotlinx.serialization.encodeToString


@Serializable
enum class ArgentumHeuristicProfile {
    PRODUCTION,
    PRODUCTION_EXPIRING;

    internal fun aiProfile(): AiProfile = when (this) {
        PRODUCTION -> AiProfile.PRODUCTION
        PRODUCTION_EXPIRING -> AiProfile.PRODUCTION_EXPIRING
    }
}

/** Trusted, state-owning implementation of the narrow [SearchWorld] facade. */
class ArgentumSearchWorld private constructor(
    private val environment: GameEnvironment,
    private val gameId: String,
    private val seedBase: Long,
    private val effectiveSetupSeed: Long,
    private val aliases: Map<EntityId, String>,
    private val history: PerspectiveHistory,
    private var decisionIndex: Int,
    private val expander: UnifiedSemanticExpander,
    private val heuristicAnnotator: ArgentumHeuristicAnnotator?,
    private val knownDecks: Map<String, Map<String, Int>>,
    private val heuristicResolutionSink: (ArgentumHeuristicResolution) -> Unit,
) : ProgressiveSearchWorld, PolicyAnnotatedSearchWorld, DerivedCacheTransferSearchWorld {
    /** The engine environment owns the registry used for transition, projection, and materialization. */
    private val cardRegistry: CardRegistry = environment.cardRegistry
    private val projector = SafeObservationProjector()
    private val exactObservedActionExpander = UnifiedSemanticExpander()
    private var cachedExpansion: CachedExpansion? = null
    private var auditedState: GameState? = null
    private val cachedProjections = mutableMapOf<EntityId, StateCache<PreparedSemanticExpansionInput>>()
    private val cachedSafeProjections = mutableMapOf<EntityId, StateCache<SafeObservationProjection>>()
    private val cachedInformationStates = mutableMapOf<String, StateCache<InformationStateRepresentation>>()
    private val cachedEpistemicStates = mutableMapOf<String, StateCache<EpistemicState>>()
    private val cachedDecisionContexts = mutableMapOf<DecisionView, StateCache<DecisionSiteRequest>>()
    private var capturedRevision: StateCache<ArgentumSearchWorld>? = null
    private var nativeEpistemicBuilds = 0
    private var nativeDecisionCaptures = 0
    private var nativeRevisionCaptures = 0
    // At most one predecessor; derived worlds do not inherit this link.
    private var lastExactObservation: Pair<ArgentumSearchWorld, String>? = null
    // Only native-observation origin, not the factual optionality value or a predecessor world.
    // Null denotes ordinary search/replay (or a derived world with no accepted transition).
    private data class ObservedChoiceOrigin(val decisionIndex: Int, val actor: String, val signature: String)
    private var lastObservedChoiceOrigin: ObservedChoiceOrigin? = null

    /** Actual capture-only environment/history forks made by this world, excluding ordinary forks. */
    internal fun decisionRevisionCaptureCount(): Int = nativeRevisionCaptures

    /** Counts only derived projection work, not hidden-state data or scientific observations. */
    fun semanticProjectionWork(): Pair<Int, Int> = nativeEpistemicBuilds to nativeDecisionCaptures

    internal fun observedProposalExpansionAttempts(): Int = exactObservedActionExpander.expansionAttempts

    override fun epistemicState(viewer: String): EpistemicState {
        cachedEpistemicStates[viewer]?.takeIf { it.state === environment.state && it.decisionIndex == decisionIndex }
            ?.let { return it.value }
        capturedRevision?.takeIf { it.state === environment.state && it.decisionIndex == decisionIndex }?.let { captured ->
            return captured.value.epistemicState(viewer).also { cachedEpistemicStates[viewer] = StateCache(environment.state, decisionIndex, it) }
        }
        val viewerId = rawPlayer(viewer)
        val projection = project(viewerId)
        val state = EpistemicState.capture(projection.observation, history.forViewer(viewerId),
            history.commitmentForViewer(viewerId), history.knowledgeForViewer(viewerId, projection.observation, knownDecks),
            environment.isTerminal, environment.winnerId?.let(aliases::getValue))
        nativeEpistemicBuilds++
        cachedEpistemicStates[viewer] = StateCache(environment.state, decisionIndex, state)
        return state
    }

    override fun decisionContext(view: DecisionView): DecisionSiteRequest {
        val effective = view.copy(limit = view.limit ?: cachedExpansion?.limit ?: DEFAULT_EXPANSION_LIMIT)
        cachedDecisionContexts[effective]?.takeIf { it.state === environment.state && it.decisionIndex == decisionIndex }
            ?.let { return it.value }
        val actor = requireNotNull(actorToAct()) { "A decision context requires a current actor" }
        val expanded = expansionResult(requireNotNull(effective.limit),
            includePolicyAdmission = effective.admission == DecisionAdmission.PRODUCTION,
            includePolicyAnnotations = effective.annotations).policy
        // One private, never-advanced revision owns lazy projections for every requested view.
        // Capture-only wrappers do not inherit requests or captures of earlier views.
        val captured = capturedRevision?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }?.value ?: derivedWorld(environment.fork(), history.fork()).also {
            nativeRevisionCaptures++
            it.cachedEpistemicStates.putAll(cachedEpistemicStates.filterValues { cache ->
                cache.state === environment.state && cache.decisionIndex == decisionIndex
            })
            it.cachedProjections.putAll(cachedProjections.filterValues { cache -> cache.state === environment.state })
            it.cachedSafeProjections.putAll(cachedSafeProjections.filterValues { cache -> cache.state === environment.state })
            capturedRevision = StateCache(environment.state, decisionIndex, it)
        }
        val result = DecisionSiteRequest.capture(actor, expanded, { captured.epistemicState(actor) }, effective,
            PolicyJson.digest(PolicyJson.format.encodeToJsonElement(UnifiedSemanticExpansionSpecification.serializer(), semanticExpansionSpecification())),
            referenceGroups = { captured.project(captured.rawPlayer(actor)).references.visibleSemanticGroups() })
        nativeDecisionCaptures++
        cachedDecisionContexts[effective] = StateCache(environment.state, decisionIndex, result)
        return result
    }

    /** Capture the admitted decision and safe action-reference relations at the same revision. */
    fun policyDecisionProjection(view: DecisionView = DecisionView()): ArgentumPolicyDecisionProjection {
        val request = decisionContext(view)
        val captured = requireNotNull(capturedRevision).value
        val references = captured.project(captured.rawPlayer(request.actor)).references.visibleSemanticGroups()
        return ArgentumPolicyDecisionProjection(request.site(), references)
    }

    private var cachedAuthoritativeFingerprint: StateCache<String>? = null

    /** Existing accepted-choice coordinate; factual forks inherit it. Host observation only. */
    val acceptedDecisionCountForHost: Int get() = decisionIndex

    override fun actorToAct(): String? = policyActor(environment)?.let(aliases::getValue)

    /** Current safe-projection work counts; no referee state enters this diagnostic. */
    internal fun observationDescriptorReuse(viewer: String): Int = project(rawPlayer(viewer)).references.reusedCardDescriptors

    internal fun observationFragmentReuse(viewer: String): Pair<Int, Int> = project(rawPlayer(viewer)).canonicalFragments.let {
        it.reusedCards to it.encodedCards
    }

    /** Runtime history representation; forks inherit it through the history ledger. */
    val historyEventOrder: PerspectiveHistoryEventOrder get() = history.eventOrder
    val historyObjectReference: PerspectiveHistoryObjectReference get() = history.objectReference

    /** Declared candidate-generation behavior used by this world and all of its ordinary forks. */
    fun semanticExpansionSpecification(): UnifiedSemanticExpansionSpecification =
        expander.behaviorSpecification

    override fun informationState(viewer: String): InformationStateRepresentation {
        cachedInformationStates[viewer]?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }?.let { return it.value }
        return buildInformationState(viewer).also { information ->
            cachedInformationStates[viewer] = StateCache(environment.state, decisionIndex, information)
        }
    }

    private fun buildInformationState(
        viewer: String,
        capturedBase: PolicyExpansion? = null,
    ): InformationStateRepresentation {
        val viewerId = rawPlayer(viewer)
        val projection = project(viewerId)
        val knowledge = history.knowledgeForViewer(viewerId, projection.observation, knownDecks)
        val actorId = policyActor(environment)
        val expansion = capturedBase ?: if (actorId == viewerId && !environment.isTerminal) {
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
        return InformationStateRepresentationFactory.build(
            projection = projection,
            history = history.forViewer(viewerId),
            historyCommitment = history.commitmentForViewer(viewerId),
            expansion = expansion,
            actingPlayerId = actorId?.let(aliases::getValue),
            terminated = environment.isTerminal,
            winnerId = environment.winnerId?.let(aliases::getValue),
            knowledge = knowledge,
        )
    }

    /** Trusted conformance probe; returns counts/codes only and never exposes hidden identities. */
    fun hiddenTruthConformanceProbe(viewer: String): HiddenTruthConformanceProbe {
        val viewerId = rawPlayer(viewer)
        val opponent = environment.playerIds.firstOrNull { it != viewerId }
            ?: return HiddenTruthConformanceProbe(false, false, false, "NO_OPPONENT_HIDDEN_ZONE")
        val hidden = environment.state.getHand(opponent) + environment.state.getLibrary(opponent)
        if (hidden.size < 2) return HiddenTruthConformanceProbe(false, false, false, "INSUFFICIENT_HIDDEN_OBJECTS")
        val first = hidden.first()
        val firstCard = environment.state.getEntity(first)?.get<CardComponent>()
            ?: return HiddenTruthConformanceProbe(false, false, false, "MISSING_HIDDEN_CARD")
        // Swapping identical cards would make a zero-effect check vacuous.
        val last = hidden.lastOrNull { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name?.let { it != firstCard.name } == true
        } ?: return HiddenTruthConformanceProbe(false, false, false, "NO_DISTINCT_HIDDEN_CARD_IDENTITIES")
        val lastCard = environment.state.getEntity(last)?.get<CardComponent>()
            ?: return HiddenTruthConformanceProbe(false, false, false, "MISSING_HIDDEN_CARD")
        val permutedState = environment.state
            .updateEntity(first) { it.with(lastCard) }
            .updateEntity(last) { it.with(firstCard) }
        val permutedEnvironment = environment.fork().also {
            it.restore(permutedState, environment.playerIds, environment.stepCount)
        }
        val permuted = derivedWorld(permutedEnvironment, history.fork())
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

    /** Trusted diagnostic only: alter unknown identities without advancing game chance or history. */
    fun permuteHiddenTruthForHost(viewer: String, seed: Long): HiddenTruthPermutation {
        val expected = try { informationState(viewer) } catch (_: UnsupportedInformationStateException) {
            return HiddenTruthPermutation(null, "UNSUPPORTED_SOURCE_INFORMATION", 0)
        }
        if (knowledgeConsistencyFailure(viewer, expected) != null) {
            return HiddenTruthPermutation(null, "SOURCE_KNOWLEDGE_INCONSISTENT", 0)
        }
        val viewerId = rawPlayer(viewer)
        val remembered = rememberedKnowledgeObjectIds(viewer, expected)
        val state = environment.state
        val assignments = linkedMapOf<EntityId, com.wingedsheep.sdk.model.CardDefinition>()
        val visibility = com.wingedsheep.engine.view.Visibility(cardRegistry())
        val random = kotlin.random.Random(seed)
        var changed = state
        var changedObjects = 0
        // Separate owners preserve each known deck; known library positions stay fixed.
        for (owner in environment.playerIds) {
            val slots = listOf(Zone.HAND, Zone.LIBRARY).flatMap { zone ->
                val ids = if (zone == Zone.HAND) state.getHand(owner) else state.getLibrary(owner)
                ids.filter { id -> id !in remembered &&
                    !visibility.isCardIdentityVisibleTo(state, ZoneKey(owner, zone), id, viewerId) &&
                    state.getEntity(id)?.get<CardComponent>() != null }
            }
            val cards = slots.map { state.getEntity(it)!!.get<CardComponent>()!! }
            val shuffled = cards.shuffled(random)
            slots.forEachIndexed { index, id ->
                if (cards[index].name != shuffled[index].name) changedObjects++
                assignments[id] = requireNotNull(cardRegistry().getCard(shuffled[index].name))
            }
            val libraryKey = ZoneKey(owner, Zone.LIBRARY)
            val library = state.getLibrary(owner)
            val unknown = slots.toSet()
            val shuffledIds = library.filter { it in unknown }.shuffled(random).iterator()
            changed = changed.copy(zones = changed.zones + (libraryKey to library.map {
                if (it in unknown) shuffledIds.next() else it
            }))
        }
        if (changedObjects == 0) return HiddenTruthPermutation(null, "NO_CHANGED_IDENTITIES", 0)
        // Rebuild all printed-definition components, not just CardComponent: otherwise cards
        // with activated/triggered abilities would become incoherent false-positive worlds.
        val coherent = when (val result = com.wingedsheep.engine.hidden.HiddenWorldMaterializer(cardRegistry()).materialize(
            changed, com.wingedsheep.engine.hidden.HiddenWorldMaterializationRequest(assignments, state.rng))) {
            is com.wingedsheep.engine.hidden.HiddenWorldMaterializationResult.Materialized -> result.state
            is com.wingedsheep.engine.hidden.HiddenWorldMaterializationResult.Unsupported ->
                return HiddenTruthPermutation(null, "ENGINE_${result.reason.kind.name}", changedObjects)
        }
        val childEnvironment = environment.fork().also {
            it.restore(coherent, environment.playerIds, environment.stepCount)
        }
        val child = derivedWorld(childEnvironment, history.fork())
        check(child.authoritativeState().rng == state.rng)
        val actual = child.informationState(viewer)
        val reason = when {
            actual.informationStateDigest != expected.informationStateDigest -> "INFORMATION_DIFFERS"
            actual.knowledge.knowledgeDigest != expected.knowledge.knowledgeDigest -> "KNOWLEDGE_DIFFERS"
            child.knowledgeConsistencyFailure(viewer, expected) != null -> "KNOWLEDGE_INCONSISTENT"
            actorToAct() == viewer && (expandChoices() != child.expandChoices() ||
                expandChoicesForPolicyAdmission() != child.expandChoicesForPolicyAdmission()) -> "MENU_DIFFERS"
            else -> null
        }
        return HiddenTruthPermutation(child.takeIf { reason == null }, reason, changedObjects)
    }

    fun persistentHistoryForkSharesPrefix(viewer: String): Boolean =
        history.sharesLedgerPrefixWith(history.fork(), rawPlayer(viewer))

    /**
     * Return a redacted reason when this complete sampled world contradicts facts remembered in
     * [expected]. A null result establishes only agreement with that perspective's current safe
     * observation, knowledge projection, visible history, known objects/zones, and remembered
     * library prefix.
     */
    fun knowledgeConsistencyFailure(viewer: String, expected: InformationStateRepresentation): String? {
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

    override fun expandChoicesForPolicyAdmission(): PolicyExpansion =
        expansionResult(includePolicyAdmission = true).policy

    override fun expandChoicesForPolicyAdmission(limit: Int): PolicyExpansion =
        expansionResult(limit, includePolicyAdmission = true).policy

    override fun expandChoicesWithPolicyAnnotations(): PolicyExpansion =
        expansionResult(includePolicyAdmission = true, includePolicyAnnotations = true).policy

    override fun expandChoicesWithPolicyAnnotations(limit: Int): PolicyExpansion =
        expansionResult(limit, includePolicyAdmission = true, includePolicyAnnotations = true).policy

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
        require(action.playerId == actor) { "Observed action belongs to a different actor" }
        val rebound = if (action is SubmitDecision) {
            val pendingId = environment.pendingDecision?.id
                ?: error("Observed a decision response while the shadow world has no pending decision")
            action.copy(response = action.response.withDecisionId(pendingId))
        } else action
        val native = if (rebound is SubmitDecision) ArgentumEngineChoice.Decision(rebound.response)
            else ArgentumEngineChoice.Action(rebound)
        val prepared = preparedProjection(actor)
        val semantic = exactObservedActionExpander.encodePreparedChoice(native, prepared)
        return ArgentumObservedStep(
            semantic,
            submitNativeChoice(semantic, native),
        )
    }

    /** No proposal work: a singleton concrete pass, or a second engine-accepted declaration.
     * Null means unqualified cardinality, never a claim that one search group is one native move.
     * The supplied action itself is certified only by the shared owner's successful submission.
     */
    private fun observedChoiceOptionality(action: GameAction, prepared: PreparedSemanticExpansionInput): Boolean? {
        if (action is com.wingedsheep.engine.core.PassPriority && environment.pendingDecision == null &&
            prepared.legalActions.singleOrNull()?.action == action) return false
        if (action is com.wingedsheep.engine.core.PassPriority) {
            // Concrete native templates may establish a second choice, but their failure or
            // the finite probe limit cannot establish a singleton.
            return prepared.legalActions.asSequence().map { it.action }.filter { it != action }.take(2)
                .any { environment.fork().stepExactlyOne(it) is ExactlyOneSubmissionResult.Applied }
                .takeIf { it }
        }
        val alternative = when (action) {
            is DeclareBlockers -> action.copy(blockers = emptyMap()).takeIf { it != action }
            is DeclareAttackers -> action.copy(attackers = emptyMap(), bands = emptyList()).takeIf { it != action }
            is SubmitDecision, is KeepHand, is TakeMulligan, is BottomCards -> null
            else -> prepared.legalActions.map { it.action }.filterIsInstance<com.wingedsheep.engine.core.PassPriority>()
                .singleOrNull()?.takeIf { it != action }
        } ?: return null
        return if (environment.fork().stepExactlyOne(alternative) is ExactlyOneSubmissionResult.Applied) true else null
    }

    /** Trusted capture at one immutable revision. Does not assert acceptance or positive policy mass. */
    fun captureObservedActionForHost(
        observer: String,
        action: GameAction,
        view: DecisionView = DecisionView(),
    ): ArgentumObservedActionCapture {
        val frozen = fork() as ArgentumSearchWorld
        val actor = requireNotNull(policyActor(frozen.environment))
        require(action.playerId == actor) { "Observed action belongs to a different actor" }
        val native = if (action is SubmitDecision) ArgentumEngineChoice.Decision(action.response.withDecisionId(
            requireNotNull(frozen.environment.pendingDecision).id)) else ArgentumEngineChoice.Action(action)
        val semantic = frozen.exactObservedActionExpander.encodePreparedChoice(native, frozen.preparedProjection(actor))
        val ids = requireNotNull(action.completeEntityReferencesOrNull()) { "Incomplete native references" } - aliases.keys
        val effectiveView = view.copy(limit = view.limit ?: frozen.cachedExpansion?.limit ?: DEFAULT_EXPANSION_LIMIT)
        return ArgentumObservedActionCapture(frozen.decisionContext(effectiveView), frozen.epistemicState(observer),
            action, semantic, frozen.observedBindings(observer, ids), observedActionBehaviorId(),
            decisionIndex, historyEventOrder, historyObjectReference, effectiveView, aliases.toMap())
    }

    fun observedActionBehaviorId(): String =
        "${ArgentumActionCorrespondence.BEHAVIOR_ID}:${historyEventOrder.name}:${historyObjectReference.name}"

    /**
     * Trusted propagation of the latest accepted native observation by its search signature.
     * Keeps representative selection and likelihood historical, but records the representative
     * through the same native-optionality owner as the factual observation. This is not exact
     * object correspondence. No factual optionality or hidden native declaration is transported.
     * Search/replay and derived worlds return null, preserving ordinary [step] semantics.
     */
    fun observedChoicePropagationForHost(
        actor: String,
        choice: SemanticChoice,
    ): ((SearchWorld, SemanticChoice) -> SearchStepResult)? {
        val origin = lastObservedChoiceOrigin ?: return null
        require(origin.decisionIndex + 1 == decisionIndex && origin.actor == actor && origin.signature == choice.signature) {
            "Observed-choice propagation requires the latest accepted declaration"
        }
        val eventOrder = historyEventOrder
        val objectReference = historyObjectReference
        return { child, representative ->
            require(child is ArgentumSearchWorld)
            require(child.decisionIndex == origin.decisionIndex && child.actorToAct() == origin.actor &&
                representative.signature == origin.signature && child.historyEventOrder == eventOrder &&
                child.historyObjectReference == objectReference) {
                "Observed-choice propagation requires the corresponding predecessor boundary"
            }
            child.applyChoice(representative, observedSubmission = true)
        }
    }

    private fun observedBindings(observer: String, ids: Set<EntityId>): List<ArgentumObservedObjectBinding> {
        val actor = requireNotNull(policyActor(environment))
        val viewer = rawPlayer(observer)
        val actorRefs = project(actor).references
        val observerRefs = project(viewer).references
        val actorHandles = history.knowledgeObjectBindingsForViewer(actor).entries.associate { it.value to it.key }
        val observerHandles = history.knowledgeObjectBindingsForViewer(viewer).entries.associate { it.value to it.key }
        val qualified = history.qualifiedBattlefieldBindings(viewer, environment.state)
        return ids.map { id -> ArgentumObservedObjectBinding(id, environment.state.objectRef(id),
            actorRefs.referenceOrNull(id), observerRefs.referenceOrNull(id), actorHandles[id], observerHandles[id],
            id in qualified && observerRefs.referenceOrNull(id) != null) }
    }

    /** Resolve only through qualified observer-local handles, then let native legality decide. */
    fun correspondObservedActionForHost(
        capture: ArgentumObservedActionCapture,
        conditioningView: DecisionView = capture.view,
    ): ArgentumActionCorrespondence {
        fun refuse(reason: ArgentumCorrespondenceRefusal) = ArgentumActionCorrespondence.Unsupported(reason)
        if (decisionIndex != capture.decisionIndex || actorToAct() != capture.actingSite.actor ||
            historyEventOrder != capture.eventOrder || historyObjectReference != capture.objectReference)
            return refuse(ArgentumCorrespondenceRefusal.DIFFERENT_BOUNDARY)
        val observer = capture.observerInformation.perspectivePlayerId
        val current = epistemicState(observer)
        if (current.historyCommitment != capture.observerInformation.historyCommitment ||
            current.knowledge != capture.observerInformation.knowledge ||
            current.observation != capture.observerInformation.observation)
            return refuse(ArgentumCorrespondenceRefusal.DIFFERENT_OBSERVER_INFORMATION)
        val sourceAction = capture.action
        if (sourceAction.transportObservedObjects { it } == null)
            return refuse(ArgentumCorrespondenceRefusal.UNSUPPORTED_ACTION_FAMILY)
        val targetIds = history.knowledgeObjectBindingsForViewer(rawPlayer(observer)).values.toSet()
        val (objects, reason) = QualifiedObservedObjectCorrespondence.bind(capture.bindings, observedBindings(observer, targetIds))
        if (reason != null) return refuse(reason)
        val players = capture.players.mapValues { (_, alias) -> rawPlayer(alias) }
        val mapped = requireNotNull(sourceAction.transportObservedObjects { id ->
            players[id] ?: requireNotNull(objects).getValue(id)
        })
        val context = decisionContext(conditioningView)
        val group = context.expansion.candidates.singleOrNull { it.signature == capture.searchGroup.signature }
            ?: return refuse(ArgentumCorrespondenceRefusal.UNAVAILABLE_GROUP)
        val encoded = exactObservedActionExpander.encodePreparedChoice(ArgentumEngineChoice.Action(mapped),
            preparedProjection(requireNotNull(policyActor(environment))))
        if (group.canonicalPayload != encoded.canonicalPayload || group.canonicalPayload != capture.searchGroup.canonicalPayload)
            return refuse(ArgentumCorrespondenceRefusal.UNAVAILABLE_GROUP)
        if (environment.fork().stepExactlyOne(mapped) is ExactlyOneSubmissionResult.Rejected)
            return refuse(ArgentumCorrespondenceRefusal.NATIVE_REJECTED)
        val representative = resolveChoice(group) as? ArgentumResolvedChoice.Action
        return ArgentumActionCorrespondence.Matched(mapped, group,
            if (representative?.value == mapped) 1.0 else 0.0)
    }

    /** Latest accepted transition only, available in the explicitly corrected representation mode. */
    fun lastObservedActionCaptureForHost(observer: String): ArgentumObservedActionCapture? =
        lastExactObservation?.let { (before, action) -> before.captureObservedActionForHost(observer,
            PolicyJson.format.decodeFromString(GameAction.serializer(), action)) }

    /** Family classification only; no proposal expansion or hidden identity leaves the host. */
    fun lastObservedActionHasQualifiedTransportForHost(): Boolean? = lastExactObservation?.let { (_, bytes) ->
        PolicyJson.format.decodeFromString(GameAction.serializer(), bytes).transportObservedObjects { it } != null
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

    internal fun exactRevision(): ArgentumWorldRevision =
        ArgentumWorldRevision(when {
            historyObjectReference != PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1 ->
                PolicyJson.sha256("${authoritativeFingerprint()}:${historyEventOrder.name}:${historyObjectReference.name}:${history.trustedReferenceStateDigest()}")
            historyEventOrder != PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1 ->
                PolicyJson.sha256("${authoritativeFingerprint()}:${historyEventOrder.name}")
            else -> authoritativeFingerprint()
        })

    override fun copyDerivedCachesFrom(source: SearchWorld): Boolean {
        val other = source as? ArgentumSearchWorld ?: return false
        // Separately constructed owners may produce the same caches. Compare their behavior
        // and the inputs retained by lazy requests, not the allocation identity of the owners.
        if (expander.behaviorSpecification != other.expander.behaviorSpecification ||
            heuristicAnnotator?.profile != other.heuristicAnnotator?.profile) return false
        if (gameId != other.gameId || seedBase != other.seedBase ||
            aliases != other.aliases || knownDecks != other.knownDecks) return false
        if (cardRegistry !== other.cardRegistry || historyEventOrder != other.historyEventOrder ||
            historyObjectReference != other.historyObjectReference) return false
        if (environment.state !== other.environment.state || decisionIndex != other.decisionIndex) return false
        if (aliases.keys.any { viewer -> !history.sharesLedgerPrefixWith(other.history, viewer) }) return false
        if (historyObjectReference != PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1 &&
            history.trustedReferenceStateDigest() != other.history.trustedReferenceStateDigest()) return false
        if (other === this) return true
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
        cachedEpistemicStates.clear()
        cachedDecisionContexts.clear()
        capturedRevision = other.capturedRevision?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }
        cachedEpistemicStates.putAll(other.cachedEpistemicStates.filterValues {
            it.state === environment.state && it.decisionIndex == decisionIndex
        })
        cachedDecisionContexts.putAll(other.cachedDecisionContexts.filterValues {
            it.state === environment.state && it.decisionIndex == decisionIndex
        })
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

    /** Host-only chance pilot: rebuild printed identities in fixed zone slots, preserving RNG. */
    fun luckPlayerIdsForHost(): Map<String, EntityId> = aliases.entries.associate { it.value to it.key }

    fun forkPermutingChanceForHost(player: String, order: List<EntityId>, includeHand: Boolean = false): ArgentumSearchWorld {
        val owner = rawPlayer(player)
        val state = environment.state
        val hand = if (includeHand) state.getHand(owner) else emptyList()
        val original = hand + state.getLibrary(owner)
        require(order.size == original.size && order.toSet() == original.toSet())
        val assignments = original.zip(order).associate { (slot, source) ->
            slot to requireNotNull(cardRegistry.getCard(requireNotNull(state.getEntity(source)?.get<CardComponent>()).name))
        }
        val coherent = when (val result = com.wingedsheep.engine.hidden.HiddenWorldMaterializer(cardRegistry).materialize(
            state, com.wingedsheep.engine.hidden.HiddenWorldMaterializationRequest(assignments, state.rng))) {
            is com.wingedsheep.engine.hidden.HiddenWorldMaterializationResult.Materialized -> result.state
            is com.wingedsheep.engine.hidden.HiddenWorldMaterializationResult.Unsupported ->
                error("CHANCE_MATERIALIZATION_${result.reason.kind.name}")
        }
        check(coherent.rng == state.rng && coherent.zones == state.zones)
        val child = environment.fork().also {
            it.restore(coherent, environment.playerIds, environment.stepCount)
        }
        return derivedWorld(child, history.fork())
    }

    /** Snapshot-only value input: no remembered history or library order enters the pilot V. */
    fun luckValueInformationForHost(viewer: String): InformationStateRepresentation {
        val snapshot = derivedWorld(environment.fork(), PerspectiveHistory(environment.playerIds,
            eventOrder = history.eventOrder, objectReference = history.objectReference))
        return snapshot.buildInformationState(viewer, PolicyExpansion(emptyList(), true, 0,
            "luck-snapshot-v1", 0))
    }

    /** Diagnostic derivative of this history, never a new admitted origin or belief proposal. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun forkSwappingNativeIdsForHost(first: EntityId, second: EntityId): ArgentumSearchWorld {
        require(first in environment.state.getBattlefield() && second in environment.state.getBattlefield())
        require(first !in aliases && second !in aliases)
        val swap = ArgentumNativeIdSwap(first, second)
        val state = swap.apply(GameState.serializer(), environment.state)
        val renamedEnvironment = environment.fork().also {
            it.restore(state, environment.playerIds, environment.stepCount)
        }
        require(renamedEnvironment.legalActions().map(swap::legal) == environment.legalActions()) {
            "NATIVE_ID_SWAP_LEGAL_CONTRACT_CHANGED"
        }
        val renamedHistory = history.swapNativeIds(swap)
        check(renamedHistory.swapNativeIds(swap).trustedReferenceStateDigest() == history.trustedReferenceStateDigest())
        return derivedWorld(renamedEnvironment, renamedHistory)
    }

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
        val transitions = mutableListOf<ArgentumRawTransition>()
        val result = applyChoice(choice, rawTraceSink = transitions)
        return ArgentumReplayStep(result, transitions)
    }

    private fun applyChoice(
        choice: SemanticChoice,
        rawTraceSink: MutableList<ArgentumRawTransition>? = null,
        observedSubmission: Boolean = false,
    ): SearchStepResult {
        check(!environment.isTerminal) { "Cannot step a terminal search world" }
        var expansion = cachedExpansion?.annotated?.expansion?.takeIf { annotated ->
                annotated.policy.candidates.any { it.signature == choice.signature }
            }
            ?: expansionResult(cachedExpansion?.limit ?: DEFAULT_EXPANSION_LIMIT)
        if (expansion.policy.candidates.none { it.signature == choice.signature } &&
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
        return submitNativeChoice(canonical, engineChoice,
            legacySearchOptionality = if (observedSubmission) null
                else expansion.policy.candidates.size > 1 || !expansion.policy.isExhaustive,
            rawTraceSink = rawTraceSink)
    }

    /** Sole submission/history owner. Native admission is independent of representative lookup. */
    private fun submitNativeChoice(
        canonical: SemanticChoice,
        engineChoice: ArgentumEngineChoice,
        legacySearchOptionality: Boolean? = null,
        rawTraceSink: MutableList<ArgentumRawTransition>? = null,
    ): SearchStepResult {
        check(!environment.isTerminal) { "Cannot step a terminal search world" }
        val actor = requireNotNull(policyActor(environment))
        val beforeState = environment.state
        // Keep the already-built Gym input as well as the safe projection. A pure priority
        // transfer changes only three visible priority fields, so the next inputs can be derived
        // exactly without rebuilding every zone/card view and StateDigest.
        val beforePrepared = aliases.keys.associateWith { preparedProjection(it) }
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
        val submittedAction = when (engineChoice) {
            is ArgentumEngineChoice.Action -> engineChoice.value
            is ArgentumEngineChoice.Decision -> SubmitDecision(actor, engineChoice.value)
        }
        // V2 histories use the same bounded native witness on both submission routes.
        // Unknown stays unknown even if search enumerated multiple representative groups.
        // Older modes retain search's historical Boolean, but direct observation now uses
        // native witnesses too; observedActionBehaviorId explicitly identifies that change.
        val strategicallyOptional = if (
            historyObjectReference == PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2 ||
            legacySearchOptionality == null
        ) observedChoiceOptionality(submittedAction, beforePrepared.getValue(actor))
        else legacySearchOptionality
        val exactBefore = if (historyObjectReference == PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2)
            fork() as ArgentumSearchWorld else null
        val submission = environment.stepExactlyOne(submittedAction)
        val rejection = (submission as? ExactlyOneSubmissionResult.Rejected)?.reason
        rawTraceSink?.add(
            ArgentumRawTransition(
                action = submittedAction,
                beforeState = beforeState,
                afterState = environment.state,
                events = environment.lastStepEvents,
                rejectionReason = rejection,
            )
        )
        if (rejection != null) {
            return SearchStepResult(false, rejection)
        }
        lastObservedChoiceOrigin = if (legacySearchOptionality == null)
            ObservedChoiceOrigin(decisionIndex, aliases.getValue(actor), canonical.signature) else null
        lastExactObservation = exactBefore?.let {
            it to PolicyJson.format.encodeToString(GameAction.serializer(), submittedAction)
        }
        history.recordChoice(
            actor,
            canonical,
            privateChoice,
            historyKind,
            strategicallyOptional = strategicallyOptional,
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
            aliases.keys.associateWith { viewer -> project(viewer, before.getValue(viewer)) }
        }
        val semanticEvents = history.recordEngineEvents(
            engineEvents = environment.lastStepEvents,
            actorViewer = actor,
            beforeState = beforeState,
            afterState = environment.state,
            before = before,
            after = after,
        )
        // Event projection can acquire new handles. Publish the resulting snapshot from that
        // ledger, not a cached pre-acquisition reference map. Historical modes keep their bytes.
        val visibleAfter = if (historyObjectReference == PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2 &&
            !isPurePriorityTransfer) {
            invalidateStateDerivedCaches()
            aliases.keys.associateWith { viewer -> project(viewer) }
        } else after
        // PriorityChangedEvent already records every field changed by the engine's pure transfer.
        // Adding a catch-all observation transition here merely repeats that fact, including an
        // always-empty zone delta, in both ledgers and their hash chains.
        val visibleTransitions = if (isPurePriorityTransfer) {
            emptyList()
        } else {
            history.recordVisibleTransition(
                before.mapValues { it.value.observation },
                visibleAfter.mapValues { it.value.observation },
                returnViewer = actor,
            )
        }
        val forced = semanticEvents + visibleTransitions
        return SearchStepResult(true, forcedTransitions = forced, privateToActor = privateChoice)
    }

    override fun fork(): SearchWorld = derivedWorld(environment.fork(), history.fork()).also { fork ->
        // GameEnvironment forks preserve the exact immutable state and entity identities. The
        // validated engine choices in this expansion therefore remain valid until either world
        // takes a step. Reusing them avoids rebuilding large combat candidate families once per
        // simulation; worlds reconstructed from a different sampled state use withSampledState()
        // and deliberately do not inherit this cache.
        fork.cachedExpansion = cachedExpansion
        fork.capturedRevision = capturedRevision?.takeIf {
            it.state === fork.environment.state && it.decisionIndex == fork.decisionIndex
        }
        fork.cachedEpistemicStates.putAll(cachedEpistemicStates.filterValues {
            it.state === fork.environment.state && it.decisionIndex == fork.decisionIndex
        })
        // Requests already own captured revisions; sharing them cannot mutate either world.
        fork.cachedDecisionContexts.putAll(cachedDecisionContexts.filterValues {
            it.state === fork.environment.state && it.decisionIndex == fork.decisionIndex
        })
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

    /**
     * Reannotates only a fork used inside simulated search. It preserves represented state,
     * history, and candidates, while deliberately discarding annotation-dependent caches.
     */
    fun forkWithHeuristicProfile(profile: ArgentumHeuristicProfile): ArgentumSearchWorld = derivedWorld(
        environment.fork(), history.fork(),
        heuristicAnnotator = ArgentumHeuristicAnnotator(cardRegistry, knownDecks, profile),
    )

    /** Rebinds the root action-space policy after an exact scenario has been constructed. */
    fun withActionSpaceProfile(profile: SearchActionSpaceProfile): ArgentumSearchWorld = derivedWorld(
        environment.fork(), history.fork(),
        expander = UnifiedSemanticExpander(actionSpaceProfile = profile),
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
        includePolicyAdmission: Boolean = false,
        includePolicyAnnotations: Boolean = false,
    ): UnifiedExpansionResult {
        require(!includePolicyAnnotations || includePolicyAdmission)
        val actor = requireNotNull(policyActor(environment)) { "No actor in non-terminal world" }
        val seed = proposalSeed()
        val key = "${aliases.getValue(actor)}:$decisionIndex:$seed"
        // Candidate-family conformance is scoped to a requested limit. Returning a previously
        // widened family for a later 64-choice request would silently change the tree contract.
        val cached = cachedExpansion?.takeIf { it.key == key && it.limit == limit }
        val base = cached?.base ?: expander.expandPrepared(
                environment,
                cardRegistry,
                seed,
                limit,
                cachedExpansion?.takeIf { it.key == key && it.limit < limit }?.base,
                preparedInput = preparedProjection(actor),
            )
                .also { expansion ->
                    val priorAnchor = cachedExpansion
                        ?.takeIf { it.key == key }
                        ?.admitted
                        ?.diagnosis
                        ?.takeIf {
                            it.resolution == ArgentumHeuristicResolution.VALIDATED_ATTACK_ANCHOR ||
                                it.resolution == ArgentumHeuristicResolution.VALIDATED_BLOCK_ANCHOR
                        }
                        ?.choice
                        ?.signature
                    cachedExpansion = CachedExpansion(key, limit, expansion, priorAnchorSignature = priorAnchor)
                }
        if (!includePolicyAdmission || heuristicAnnotator == null) return base
        val current = requireNotNull(cachedExpansion).takeIf { it.key == key && it.limit == limit }
            ?: CachedExpansion(key, limit, base).also { cachedExpansion = it }
        val admission = current.admit(heuristicAnnotator, actor, seed)
        if (!includePolicyAnnotations) return admission.expansion
        return current.annotate(heuristicAnnotator, admission).expansion
    }

    private fun CachedExpansion.admit(
        annotator: ArgentumHeuristicAnnotator,
        actor: EntityId,
        seed: Long,
    ): ArgentumHeuristicAdmission = admitted ?: annotator.admit(
        environment,
        aliases,
        base,
        seed,
        rememberedObjectIds = rememberedKnowledgeObjectIds(
            aliases.getValue(actor),
            informationState(aliases.getValue(actor)),
        ),
        encodeSemanticChoice = { choice -> expander.encodePreparedChoice(choice, preparedProjection(actor)) },
        priorAnchorSignature = priorAnchorSignature,
    ).also { admitted = it }

    private fun CachedExpansion.annotate(
        annotator: ArgentumHeuristicAnnotator,
        admission: ArgentumHeuristicAdmission,
    ): ArgentumHeuristicAnnotation = annotated ?: annotator.annotate(admission).also { resolved ->
        annotated = resolved
        resolved.diagnosis.resolution?.let(heuristicResolutionSink)
    }

    private fun expansionContaining(choice: SemanticChoice): UnifiedExpansionResult {
        cachedExpansion?.annotated?.expansion?.takeIf { annotated ->
            annotated.policy.candidates.any { it.signature == choice.signature }
        }?.let { return it }
        cachedExpansion?.admitted?.expansion?.takeIf { admitted ->
            admitted.policy.candidates.any { it.signature == choice.signature }
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

    private fun project(viewer: EntityId, previous: SafeObservationProjection? = null): SafeObservationProjection {
        cachedSafeProjections[viewer]?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }?.let { return it.value }
        return preparedProjection(viewer, previous).projection
    }

    private fun preparedProjection(viewer: EntityId, previous: SafeObservationProjection? = null): PreparedSemanticExpansionInput {
        requireSupportedInformationState()
        cachedProjections[viewer]?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }?.let { return it.value }
        val legal = if (viewer == policyActor(environment)) environment.legalActions() else emptyList()
        val observation = ObservationBuilder(cardRegistry).build(environment.state, viewer, legal)
            .observation as TrainingObservation
        val projection = cachedSafeProjections[viewer]?.takeIf {
            it.state === environment.state && it.decisionIndex == decisionIndex
        }?.value ?: projector.project(
            observation,
            aliases,
            ArgentumPolicyRuntimeProjector.project(environment.state, viewer, cardRegistry, observation),
            pendingDecision = environment.pendingDecision,
            previous = previous,
            qualifiedBattlefieldHandles = if (historyObjectReference == PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2)
                history.qualifiedBattlefieldBindings(viewer, environment.state) else emptyMap(),
            canonicalCombatRows = historyObjectReference == PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2,
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
        cachedEpistemicStates.clear()
        cachedDecisionContexts.clear()
        capturedRevision = null
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
    /** Root-owned card authority for this trusted world and every world derived from it. */
    internal fun cardRegistry(): CardRegistry = cardRegistry
    internal fun authoritativeState(): GameState = environment.state
    internal fun rememberedKnowledgeObjectIds(
        viewerAlias: String,
        expected: InformationStateRepresentation,
    ): Set<EntityId> {
        val bindings = history.knowledgeObjectBindingsForViewer(rawPlayer(viewerAlias))
        return expected.knowledge.knownObjects.map { knownObject ->
            bindings[knownObject.knowledgeObjectKey]
                ?: error("A represented remembered object is missing its trusted binding")
        }.toSet()
    }

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
        val admission = current.admit(annotator, actor, seed)
        return current.annotate(annotator, admission).diagnosis
    }

    /** Privileged evidence only; no authoritative state reference escapes this DTO. */
    fun privilegedDebugSnapshot(): ArgentumPrivilegedDebugSnapshot {
        fun names(ids: List<EntityId>): List<String> = ids.map { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name ?: "<missing:$id>"
        }
        val semanticPerspective = policyActor(environment) ?: environment.playerIds.first()
        val semanticObservation = ObservationBuilder(cardRegistry).build(
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
                "initial-seed:$effectiveSetupSeed",
                "search-base-seed:$seedBase",
            ),
        )
    }

    internal fun withSampledState(
        state: GameState,
        futureChanceStreamIdentity: Long,
    ): ArgentumSearchWorld = withHypotheticalState(state, futureChanceStreamIdentity)

    /**
     * Fork this world's complete position while replacing its future game-chance stream: the
     * authoritative position for a privileged offline search, or a hypothetical particle copied
     * during belief resampling. The identity must come from declared experiment/search randomness;
     * this boundary deliberately never derives it from [GameState.rng] or the current state.
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
        return derivedWorld(sampledEnvironment, history.fork())
    }

    /** Adapter-internal audit seam for exercising construction from a reconstructed safe ledger. */
    internal fun withRememberedHistoryForVerification(reconstructedHistory: PerspectiveHistory): ArgentumSearchWorld =
        derivedWorld(environment.fork(), reconstructedHistory.fork())

    /** Share session configuration; callers explicitly choose state/history and cache inheritance. */
    private fun derivedWorld(
        environment: GameEnvironment,
        history: PerspectiveHistory,
        expander: UnifiedSemanticExpander = this.expander,
        heuristicAnnotator: ArgentumHeuristicAnnotator? = this.heuristicAnnotator,
    ): ArgentumSearchWorld = ArgentumSearchWorld(
        environment = environment,
        gameId = gameId,
        seedBase = seedBase,
        effectiveSetupSeed = effectiveSetupSeed,
        aliases = aliases,
        history = history,
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
        var admitted: ArgentumHeuristicAdmission? = null,
        var annotated: ArgentumHeuristicAnnotation? = null,
    )
    private data class StateCache<T>(val state: GameState, val decisionIndex: Int, val value: T)

    companion object {
        const val ARGENTUM_BOARD_EVALUATOR_V1 = "argentum-board-v1"
        const val DEFAULT_EXPANSION_LIMIT = 64
        private const val FUTURE_CHANCE_SEED_DOMAIN = "argentum-hypothetical-future-chance-v1"
        private val STEP_EXPANSION_LIMITS = listOf(128, 256, 512, 1_024, 2_048)
        fun create(
            environment: GameEnvironment,
            gameId: String,
            seedBase: Long,
            effectiveSetupSeed: Long,
            expander: UnifiedSemanticExpander = UnifiedSemanticExpander(),
            knownDecks: Map<String, Map<String, Int>>? = null,
            projectionAuditSink: PerspectiveProjectionAuditSink = PerspectiveProjectionAuditSink.NONE,
            heuristicResolutionSink: (ArgentumHeuristicResolution) -> Unit = {},
            historyEventOrder: PerspectiveHistoryEventOrder = PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1,
            historyObjectReference: PerspectiveHistoryObjectReference = PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1,
        ): ArgentumSearchWorld {
            require(environment.playerIds.isNotEmpty()) { "Environment must be reset before wrapping" }
            val aliases = environment.playerIds.mapIndexed { index, id -> id to "p$index" }.toMap()
            return ArgentumSearchWorld(
                environment = environment,
                gameId = gameId,
                seedBase = seedBase,
                effectiveSetupSeed = effectiveSetupSeed,
                aliases = aliases,
                history = PerspectiveHistory(environment.playerIds, projectionAuditSink, historyEventOrder, historyObjectReference),
                decisionIndex = 0,
                expander = expander,
                heuristicAnnotator = knownDecks?.let { ArgentumHeuristicAnnotator(environment.cardRegistry, it, ArgentumHeuristicProfile.PRODUCTION) },
                knownDecks = knownDecks.orEmpty(),
                heuristicResolutionSink = heuristicResolutionSink,
            )
        }
    }
}

/** Checks the complete represented player input before a hidden world can enter a belief batch. */
internal object ArgentumInformationSupport {
    fun failure(sampled: InformationStateRepresentation, expected: InformationStateRepresentation): String? {
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

private data class ArgentumHeuristicAdmission(
    val expansion: UnifiedExpansionResult,
    val diagnosis: ArgentumHeuristicChoiceDiagnosis,
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
    val rawTransitions: List<ArgentumRawTransition>,
)

/** One MTGallium-submitted exact transition retained for private canonical replay construction. */
data class ArgentumRawTransition(
    val action: GameAction,
    val beforeState: GameState,
    val afterState: GameState,
    val events: List<GameEvent>,
    val rejectionReason: String?,
) {
    val accepted: Boolean get() = rejectionReason == null
}

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
    val profile: ArgentumHeuristicProfile,
) {
    private val materializer = KnownDeckWorldMaterializer(cardRegistry)
    // Share engine services for this annotator; every selection still gets fresh AI memory.
    private val playerFactory by lazy { AIPlayer.Factory(cardRegistry) }

    fun admit(
        environment: GameEnvironment,
        aliases: Map<EntityId, String>,
        expansion: UnifiedExpansionResult,
        seed: Long,
        rememberedObjectIds: Set<EntityId>,
        encodeSemanticChoice: (ArgentumEngineChoice) -> SemanticChoice,
        priorAnchorSignature: String? = null,
    ): ArgentumHeuristicAdmission {
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
        val hypothesisSeed = ComponentSeeds.derive(seed, aliases.getValue(actor), "argentum-heuristic")
        val sampledState = when (val sampled = materializer.materialize(
            state = pins.samplingState,
            viewerId = actor,
            decklists = rawDecks,
            beliefRng = GameRng.seeded(hypothesisSeed),
            futureRng = GameRng.seeded(ComponentSeeds.derive(hypothesisSeed, "known-deck-future")),
        )) {
            is KnownDeckWorldMaterializationResult.Materialized -> pins.restore(sampled.state)
            is KnownDeckWorldMaterializationResult.Unsupported -> return unavailable(
                expansion,
                ArgentumHeuristicUnavailableReason.DETERMINIZATION_UNSUPPORTED,
                sampled.reasons.map(KnownDeckWorldFailure::redactedCode).distinct().sorted(),
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
    ): ArgentumHeuristicAdmission {
        val ordinary = expansion.policy.candidates.filterNot { it.signature == selected.signature }
        val candidates = when {
            anchorEngineChoice != null -> listOf(selected) + ordinary.take(
                (expansion.policy.candidates.size - 1).coerceAtLeast(0)
            )
            promoteFirst -> listOf(selected) + ordinary
            else -> expansion.policy.candidates
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
        val admitted = UnifiedExpansionResult(
            policy = expansion.policy.copy(candidates = candidates),
            engineChoices = engineChoices,
            attemptedCandidates = expansion.attemptedCandidates,
            rejectedCandidates = expansion.rejectedCandidates,
            rejectedSignatures = expansion.rejectedSignatures,
        )
        return ArgentumHeuristicAdmission(
            expansion = admitted,
            diagnosis = ArgentumHeuristicChoiceDiagnosis(
                choice = selected,
                resolution = resolution,
                selectedAcceptedBySampledState = selectedAcceptedBySampledState,
                selectedAcceptedByAuthoritativeState = selectedAcceptedByAuthoritativeState,
                selectedSemanticSignature = selected.signature,
                semanticEquivalentCandidateSignatures = listOf(selected.signature)
                    .takeIf { resolution == ArgentumHeuristicResolution.SEMANTIC_EQUIVALENT }
                    .orEmpty(),
            ),
        )
    }

    fun annotate(admission: ArgentumHeuristicAdmission): ArgentumHeuristicAnnotation {
        val selected = admission.diagnosis.choice ?: return ArgentumHeuristicAnnotation(
            admission.expansion,
            admission.diagnosis,
        )
        val tagged = selected.copy(
            display = selected.display.copy(
                policyTags = selected.display.policyTags + ARGENTUM_HEURISTIC_CHOICE_TAG_V1
            )
        )
        val candidates = admission.expansion.policy.candidates.map { choice ->
            if (choice.signature == selected.signature) tagged else choice
        }
        return ArgentumHeuristicAnnotation(
            expansion = UnifiedExpansionResult(
                policy = admission.expansion.policy.copy(candidates = candidates),
                engineChoices = admission.expansion.engineChoices,
                attemptedCandidates = admission.expansion.attemptedCandidates,
                rejectedCandidates = admission.expansion.rejectedCandidates,
                rejectedSignatures = admission.expansion.rejectedSignatures,
            ),
            diagnosis = admission.diagnosis.copy(choice = tagged),
        )
    }

    private fun select(
        state: GameState,
        actor: EntityId,
        expansion: UnifiedExpansionResult,
    ): ArgentumEngineChoice = when {
            state.pendingDecision?.playerId == actor -> ArgentumEngineChoice.Decision(
                playerFactory.create(actor, profile.aiProfile())
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
                playerFactory.create(actor, profile.aiProfile()).chooseAction(state)
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
    ): ArgentumHeuristicAdmission = ArgentumHeuristicAdmission(
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
