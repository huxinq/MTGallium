package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.player.MulliganStateComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.ExactlyOneSubmissionResult
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.trainer.defaults.ExactStructuredDecisionExpander
import com.wingedsheep.gym.trainer.spi.StructuredDecisionExpansion
import com.wingedsheep.sdk.model.EntityId
import java.math.BigInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.BestFirstStructuredActionProposer
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyExpansionOmissionReason
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticActionIntent
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticActionTargetRelation
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

sealed interface ArgentumEngineChoice {
    data class Action(
        val value: GameAction,
        /** Copied from the enumerated [com.wingedsheep.engine.legalactions.LegalAction]. */
        val isManaAbility: Boolean = false,
        /** True only when [value] is the engine-enumerated action itself, without parameter changes. */
        val copiedFromLegalAction: Boolean = false,
    ) : ArgentumEngineChoice
    data class Decision(val value: DecisionResponse) : ArgentumEngineChoice
}

/** Inputs that determine candidate generation before a search-specific response limit is applied. */
@Serializable
data class UnifiedSemanticExpansionSpecification(
    val schemaVersion: Int = 1,
    val defaultResponseLimit: Int,
    val maximumAttempts: Int,
    val maximumGeneratedScans: Int,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val proposalAlgorithmVersion: String,
    val proposalVersion: String,
) {
    init {
        require(schemaVersion == 1)
        require(defaultResponseLimit > 0)
        require(maximumAttempts >= defaultResponseLimit)
        require(maximumGeneratedScans >= maximumAttempts)
        require(proposalAlgorithmVersion.isNotBlank())
        require(proposalVersion == "$proposalAlgorithmVersion:${actionSpaceProfile.profileId}")
    }
}

class UnifiedExpansionResult internal constructor(
    val policy: PolicyExpansion,
    val engineChoices: Map<String, ArgentumEngineChoice>,
    val attemptedCandidates: Int,
    val rejectedCandidates: Int,
    internal val rejectedSignatures: Set<String> = emptySet(),
)

internal data class PreparedSemanticExpansionInput(
    val actor: EntityId,
    val legalActions: List<com.wingedsheep.engine.legalactions.LegalAction>,
    val observation: TrainingObservation,
    val projection: SafeObservationProjection,
)

/**
 * Complete action/decision expansion for information-set search.
 *
 * Every candidate is stepped on an immutable environment fork before it is admitted. Candidate
 * generation and proposal ordering are pure functions of the visible contract and [proposalSeed];
 * neither reads nor advances game randomness.
 */
class UnifiedSemanticExpander(
    private val maxResponses: Int = DEFAULT_RESPONSE_LIMIT,
    private val maxAttempts: Int = DEFAULT_MAXIMUM_ATTEMPTS,
    private val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
    private val proposalAlgorithmVersion: String = DEFAULT_PROPOSAL_ALGORITHM_VERSION,
) {
    private val projector = SafeObservationProjector()
    private val proposalVersion = "$proposalAlgorithmVersion:${actionSpaceProfile.profileId}"
    val behaviorSpecification: UnifiedSemanticExpansionSpecification =
        UnifiedSemanticExpansionSpecification(
            defaultResponseLimit = maxResponses,
            maximumAttempts = maxAttempts,
            maximumGeneratedScans = MAX_GENERATED_SCANS,
            actionSpaceProfile = actionSpaceProfile,
            proposalAlgorithmVersion = proposalAlgorithmVersion,
            proposalVersion = proposalVersion,
        )

    init {
        require(maxResponses > 0)
        require(maxAttempts >= maxResponses)
    }

    fun expand(
        environment: GameEnvironment,
        cardRegistry: CardRegistry,
        proposalSeed: Long,
    ): UnifiedExpansionResult = expand(environment, cardRegistry, proposalSeed, maxResponses)

    fun expand(
        environment: GameEnvironment,
        cardRegistry: CardRegistry,
        proposalSeed: Long,
        responseLimit: Int,
        validatedPrefix: UnifiedExpansionResult? = null,
    ): UnifiedExpansionResult = expandPrepared(
        environment,
        cardRegistry,
        proposalSeed,
        responseLimit,
        validatedPrefix,
        preparedInput = null,
    )

    /** Privileged adapter diagnostic: encode one engine choice under the current safe projection. */
    internal fun encodePreparedChoice(
        choice: ArgentumEngineChoice,
        prepared: PreparedSemanticExpansionInput,
    ): SemanticChoice = encodeChoice(
        choice,
        prepared.observation,
        prepared.projection.references,
        prepared.legalActions,
    )

    internal fun expandPrepared(
        environment: GameEnvironment,
        cardRegistry: CardRegistry,
        proposalSeed: Long,
        responseLimit: Int,
        validatedPrefix: UnifiedExpansionResult? = null,
        preparedInput: PreparedSemanticExpansionInput?,
    ): UnifiedExpansionResult {
        require(responseLimit > 0 && responseLimit <= maxAttempts)
        if (environment.isTerminal) {
            return UnifiedExpansionResult(
                PolicyExpansion(emptyList(), true, 0, proposalVersion, proposalSeed),
                emptyMap(),
                0,
                0,
            )
        }
        val actor = requireNotNull(policyActor(environment)) { "Non-terminal environment has no actor" }
        val prepared = preparedInput ?: environment.legalActions().let { legalActions ->
            val observation = ObservationBuilder(cardRegistry).build(
                environment.state,
                actor,
                legalActions,
            ).observation as TrainingObservation
            PreparedSemanticExpansionInput(
                actor,
                legalActions,
                observation,
                projector.project(
                    observation,
                    playerAliases = null,
                    runtime = ArgentumPolicyRuntimeProjector.project(
                        environment.state,
                        actor,
                        cardRegistry,
                        observation,
                    ),
                    pendingDecision = environment.pendingDecision,
                ),
            )
        }
        require(prepared.actor == actor) { "Prepared semantic expansion belongs to another actor" }
        val gymObservation = prepared.observation
        val projection = prepared.projection
        val refs = projection.references

        val generated = if (environment.pendingDecision != null) {
            val decision = environment.pendingDecision!!
            val expansion = when (val exact = ExactStructuredDecisionExpander.Default.expand(environment.state, decision)) {
                is StructuredDecisionExpansion.Complete -> StructuredResponseProposal(
                    responses = exact.responses,
                    isExhaustive = true,
                    estimatedResponseCount = exact.responses.size.toLong(),
                )
                StructuredDecisionExpansion.Unsupported ->
                    BoundedDecisionResponseProposer(responseLimit, maxAttempts)
                        .propose(environment.state, decision)
            }
            GeneratedChoices(
                choices = expansion.responses.asSequence().map { ArgentumEngineChoice.Decision(it) },
                estimatedCount = expansion.estimatedResponseCount,
                sourceExhaustive = expansion.isExhaustive,
                profileSourceExhaustive = expansion.isExhaustive,
            )
        } else {
            generateActions(environment, refs, gymObservation, prepared.legalActions)
        }

        val valid = linkedMapOf<String, Pair<SemanticChoice, ArgentumEngineChoice>>()
        val seenSemantic = mutableSetOf<String>()
        val reusable = validatedPrefix?.takeIf {
            generated.preserveOrder &&
                it.policy.proposalSeed == proposalSeed &&
                it.policy.proposalVersion == proposalVersion &&
                it.policy.candidates.size <= responseLimit
        }
        val reusableAccepted = reusable?.engineChoices.orEmpty()
        val reusableRejected = reusable?.rejectedSignatures.orEmpty()
        val rejectedSignatures = mutableSetOf<String>()
        var attempts = 0
        var rejected = 0
        var scanned = 0
        var exhausted = false
        val iterator = generated.choices.iterator()
        while (
            attempts < maxAttempts &&
            scanned < MAX_GENERATED_SCANS &&
            (!generated.preserveOrder || valid.size < responseLimit) &&
            iterator.hasNext()
        ) {
            val engineChoice = iterator.next()
            scanned++
            val admittedReferences = when (engineChoice) {
                is ArgentumEngineChoice.Action -> engineChoice.value.completeEntityReferencesOrNull()
                is ArgentumEngineChoice.Decision -> engineChoice.value.completeEntityReferencesOrNull()
            }
            if (admittedReferences == null) {
                attempts++
                rejected++
                continue
            }
            if (admittedReferences.any { !refs.admits(it) }) {
                attempts++
                rejected++
                continue
            }
            val semantic = encodeChoice(engineChoice, gymObservation, refs, prepared.legalActions)
            if (!seenSemantic.add(semantic.signature)) continue
            attempts++
            reusableAccepted[semantic.signature]?.let { accepted ->
                valid[semantic.signature] = semantic to accepted
                continue
            }
            if (semantic.signature in reusableRejected) {
                rejected++
                rejectedSignatures += semantic.signature
                continue
            }
            // An action copied verbatim from the engine's current legal-action list is already
            // validated by that enumerator. Forking and executing the identical value here merely
            // duplicates engine work. Synthesized/parameterized actions and decisions retain the
            // fail-closed acceptance probe.
            val accepted = engineChoice is ArgentumEngineChoice.Action &&
                engineChoice.copiedFromLegalAction || isAccepted(environment, engineChoice)
            if (!accepted) {
                rejected++
                rejectedSignatures += semantic.signature
                continue
            }
            valid[semantic.signature] = semantic to engineChoice
        }
        exhausted = !iterator.hasNext()
        check(valid.isNotEmpty()) {
            "No valid semantic expansion at turn ${environment.turnNumber} after $attempts attempts"
        }

        val allValid = valid.values.toList()
        val selected = if (generated.preserveOrder) {
            allValid.take(responseLimit)
        } else {
            val anchors = allValid.take(2)
            val stratified = allValid.drop(anchors.size).sortedBy { (choice, _) ->
                PolicyJson.sha256("$proposalSeed:${choice.signature}")
            }
            (anchors + stratified).take(responseLimit)
        }
        val exhaustive = generated.sourceExhaustive && exhausted && allValid.size <= responseLimit
        val profileExhaustive = generated.profileSourceExhaustive && exhausted && allValid.size <= responseLimit
        val omissionReasons = buildSet {
            addAll(generated.omissionReasons)
            if (!exhausted) {
                when {
                    attempts >= maxAttempts -> add(PolicyExpansionOmissionReason.ATTEMPT_LIMIT)
                    scanned >= MAX_GENERATED_SCANS -> add(PolicyExpansionOmissionReason.GENERATED_SCAN_LIMIT)
                    else -> add(PolicyExpansionOmissionReason.RESPONSE_LIMIT)
                }
            }
            if (allValid.size > responseLimit) add(PolicyExpansionOmissionReason.RESPONSE_LIMIT)
        }
        val estimate = when {
            generated.sourceExhaustive && exhausted -> allValid.size.toLong()
            else -> null
        }
        return UnifiedExpansionResult(
            policy = PolicyExpansion(
                candidates = selected.map { it.first },
                isExhaustive = exhaustive,
                estimatedCandidateCount = estimate?.coerceAtLeast(selected.size.toLong()),
                proposalVersion = proposalVersion,
                proposalSeed = proposalSeed,
                isProfileExhaustive = profileExhaustive,
                omissionReasons = omissionReasons,
            ),
            engineChoices = selected.associate { (semantic, engine) -> semantic.signature to engine },
            attemptedCandidates = attempts,
            rejectedCandidates = rejected,
            rejectedSignatures = rejectedSignatures,
        )
    }

    private fun generateActions(
        environment: GameEnvironment,
        refs: SafeReferenceMap,
        observation: TrainingObservation,
        legalActions: List<com.wingedsheep.engine.legalactions.LegalAction>,
    ): GeneratedChoices {
        mulliganActions(environment)?.let { return it }
        // Argentum's enumerator intentionally returns unaffordable presentation rows as well as
        // executable actions. Every built-in selector filters those rows before choosing, and the
        // old fork-and-step admission probe rejected them only after expensive encoding. They are
        // not members of the executable semantic action space.
        val affordable = legalActions.filter { it.affordable }
        val included = if (actionSpaceProfile.suppressesStandaloneManaAbilities) {
            affordable.filterNot { it.isManaAbility }
        } else {
            affordable
        }
        check(included.isNotEmpty()) {
            "${actionSpaceProfile.profileId} suppressed every legal action"
        }
        val suppressedLegalActions = included.size != affordable.size
        val sources = included.map { legal ->
            legal to concreteActions(environment, legal, refs, observation)
        }
        val estimate = sources.fold(0L) { total, (_, source) ->
            saturatingAdd(total, source.estimatedCount)
        }
        return GeneratedChoices(
            choices = sources.asSequence().flatMap { (legal, source) ->
                source.actions.map { action ->
                    ArgentumEngineChoice.Action(
                        value = action,
                        isManaAbility = legal.isManaAbility,
                        copiedFromLegalAction = legal.affordable && action == legal.action,
                    )
                }
            },
            estimatedCount = estimate,
            sourceExhaustive = !suppressedLegalActions && sources.all { (_, source) -> source.exhaustive },
            profileSourceExhaustive = sources.all { (_, source) -> source.exhaustive },
            omissionReasons = buildSet {
                if (suppressedLegalActions) {
                    add(PolicyExpansionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA)
                }
                if (sources.any { (_, source) -> !source.exhaustive }) {
                    add(PolicyExpansionOmissionReason.SOURCE_NON_EXHAUSTIVE)
                }
            },
            preserveOrder = sources.size == 1 && sources.single().second.preserveOrder,
        )
    }

    private fun mulliganActions(environment: GameEnvironment): GeneratedChoices? {
        val actor = mulliganActor(environment) ?: return null
        val mulligan = environment.state.getEntity(actor)?.get<MulliganStateComponent>() ?: return null
        if (!mulligan.hasKept) {
            val actions = buildList<GameAction> {
                add(KeepHand(actor))
                if (mulligan.canMulligan) add(TakeMulligan(actor))
            }
            return GeneratedChoices(
                choices = actions.asSequence().map { ArgentumEngineChoice.Action(it) },
                estimatedCount = actions.size.toLong(),
                sourceExhaustive = true,
            )
        }

        val count = mulligan.cardsToBottom
        if (count <= 0) return null
        val hand = environment.state.getHand(actor)
        val orders = orderedSelections(hand, count)
        return GeneratedChoices(
            choices = orders.map { ArgentumEngineChoice.Action(BottomCards(actor, it)) },
            estimatedCount = permutationCount(hand.size, count),
            sourceExhaustive = true,
        )
    }

    private fun concreteActions(
        environment: GameEnvironment,
        legal: com.wingedsheep.engine.legalactions.LegalAction,
        refs: SafeReferenceMap,
        observation: TrainingObservation,
    ): ActionCandidates = when (val template = legal.action) {
        is DeclareAttackers -> attackCandidates(template, legal)
        is DeclareBlockers -> blockCandidates(environment, template, legal, refs, observation)
        is CastSpell -> parameterizedCandidates(environment, legal, template)
        is ActivateAbility -> parameterizedCandidates(environment, legal, template)
        else -> ActionCandidates(sequenceOf(template), 1L, true)
    }

    private fun attackCandidates(
        template: DeclareAttackers,
        legal: com.wingedsheep.engine.legalactions.LegalAction,
    ): ActionCandidates {
        val attackers = legal.validAttackers.orEmpty()
        val targets = legal.validAttackTargets.orEmpty()
        val mandatory = legal.mandatoryAttackers.orEmpty().toSet()
        val choices = attackers.map { attacker ->
            buildList<Pair<EntityId, EntityId?>> {
                if (attacker !in mandatory) add(attacker to null)
                targets.forEach { add(attacker to it) }
            }
        }
        val estimate = choices.fold(1L) { count, options -> saturatingMultiply(count, options.size.toLong()) }
        val actions = cartesian(choices).map { selections ->
            template.copy(attackers = selections.mapNotNull { (attacker, target) ->
                target?.let { attacker to it }
            }.toMap())
        }
        return ActionCandidates(actions, estimate, true)
    }

    private fun blockCandidates(
        environment: GameEnvironment,
        template: DeclareBlockers,
        legal: com.wingedsheep.engine.legalactions.LegalAction,
        refs: SafeReferenceMap,
        observation: TrainingObservation,
    ): ActionCandidates {
        val attackers = environment.state.findEntitiesWith<AttackingComponent>()
            .map { it.first }
        val estimate = legal.validBlockers.orEmpty().fold(1L) { count, blocker ->
            val cap = (legal.blockerMaxBlockCounts?.get(blocker) ?: 1)
                .coerceAtLeast(0)
                .coerceAtMost(attackers.size)
            val required = legal.mandatoryBlockerAssignments?.get(blocker).orEmpty().distinct()
            val options = if (required.any { it !in attackers } || required.size > cap) {
                0L
            } else {
                combinationRangeCount(attackers.size - required.size, 0, cap - required.size)
            }
            saturatingMultiply(count, options)
        }
        val space = BlockStructuredActionSpace(template, legal, attackers, refs, observation)
        val actions = BestFirstStructuredActionProposer(space).proposals()
        return ActionCandidates(actions, estimate, exhaustive = true, preserveOrder = true)
    }

    private fun parameterizedCandidates(
        environment: GameEnvironment,
        legal: com.wingedsheep.engine.legalactions.LegalAction,
        template: GameAction,
    ): ActionCandidates {
        val xValues: List<Int?> = if (legal.hasXCost) {
            val maximum = legal.maxAffordableX ?: legal.minX
            boundaryFirst(legal.minX..maximum).map { it as Int? }.toList()
        } else {
            listOf(null)
        }
        val targets = targetSelections(legal)
        val maxRepeats = legal.maxRepeatableActivations
        val repeatCounts = if (template is ActivateAbility && maxRepeats != null) {
            boundaryFirst(1..maxRepeats).toList()
        } else {
            listOf(1)
        }
        val estimate = saturatingMultiply(
            saturatingMultiply(xValues.size.toLong(), targets.estimatedCount),
            repeatCounts.size.toLong(),
        )
        val actions = sequence {
            for (x in xValues) {
                for (targetIds in targets.values) {
                    val chosenTargets = targetIds.map { resolveTarget(it, environment) }
                    for (repeat in repeatCounts) {
                        val base = when (template) {
                            is CastSpell -> template.copy(targets = chosenTargets, xValue = x ?: template.xValue)
                            is ActivateAbility -> template.copy(
                                targets = chosenTargets,
                                xValue = x ?: template.xValue,
                                repeatCount = repeat,
                            )
                            else -> template
                        }
                        val distributions = damageDistributions(legal, targetIds)
                        for (distribution in distributions.values) {
                            yield(when (base) {
                                is CastSpell -> base.copy(damageDistribution = distribution)
                                is ActivateAbility -> base.copy(damageDistribution = distribution)
                                else -> base
                            })
                        }
                    }
                }
            }
        }
        val withDistribution = saturatingMultiply(estimate, damageDistributions(legal, legal.validTargets.orEmpty()).estimatedCount)
        return ActionCandidates(actions, withDistribution, targets.exhaustive)
    }

    private fun targetSelections(
        legal: com.wingedsheep.engine.legalactions.LegalAction,
    ): SelectionCandidates<EntityId> {
        val requirements = legal.targetRequirements
        if (!requirements.isNullOrEmpty()) {
            val perRequirement = requirements.sortedBy { it.index }.map { requirement ->
                selectionLists(requirement.validTargets, requirement.minTargets, requirement.maxTargets).toList()
            }
            val estimate = perRequirement.fold(1L) { count, values -> saturatingMultiply(count, values.size.toLong()) }
            return SelectionCandidates(cartesian(perRequirement).map { it.flatten() }, estimate, true)
        }
        val values = legal.validTargets.orEmpty()
        val minimum = if (values.isEmpty()) 0 else legal.minTargets.coerceAtMost(values.size)
        val maximum = if (values.isEmpty()) 0 else legal.targetCount.coerceAtMost(values.size)
        val estimate = combinationRangeCount(values.size, minimum, maximum)
        return SelectionCandidates(selectionLists(values, minimum, maximum), estimate, true)
    }

    private fun damageDistributions(
        legal: com.wingedsheep.engine.legalactions.LegalAction,
        targets: List<EntityId>,
    ): DistributionCandidates {
        if (!legal.requiresDamageDistribution || targets.isEmpty()) {
            return DistributionCandidates(sequenceOf(null), 1L)
        }
        val total = legal.totalDamageToDistribute ?: return DistributionCandidates(sequenceOf(null), 1L)
        val minimum = legal.minDamagePerTarget ?: 0
        val remainder = total - minimum * targets.size
        if (remainder < 0) return DistributionCandidates(emptySequence(), 0L)
        val vectors = integerVectors(targets.size, remainder).map { vector ->
            targets.indices.associate { index -> targets[index] to vector[index] + minimum }
        }
        return DistributionCandidates(vectors, combination(total - minimum * targets.size + targets.size - 1, targets.size - 1))
    }

    private fun resolveTarget(id: EntityId, environment: GameEnvironment): ChosenTarget {
        val state = environment.state
        return when {
            id in state.turnOrder -> ChosenTarget.Player(id)
            id in state.stack -> ChosenTarget.Spell(id)
            id in state.getBattlefield() -> ChosenTarget.Permanent(id)
            else -> {
                val zone = state.zones.entries.firstOrNull { (_, ids) -> id in ids }?.key
                    ?: error("Target $id is in no current zone")
                ChosenTarget.Card(id, zone.ownerId, zone.zoneType)
            }
        }
    }

    private fun isAccepted(environment: GameEnvironment, choice: ArgentumEngineChoice): Boolean {
        val child = environment.fork()
        val result = when (choice) {
            is ArgentumEngineChoice.Action -> child.stepExactlyOne(choice.value)
            is ArgentumEngineChoice.Decision -> child.stepExactlyOne(
                com.wingedsheep.engine.core.SubmitDecision(choice.value.playerId(environment), choice.value)
            )
        }
        return result is ExactlyOneSubmissionResult.Applied
    }

    private fun DecisionResponse.playerId(environment: GameEnvironment): EntityId =
        environment.pendingDecision?.playerId
            ?: error("Decision response has no current decision player")

    private fun encodeChoice(
        choice: ArgentumEngineChoice,
        observation: TrainingObservation,
        refs: SafeReferenceMap,
        legalActions: List<com.wingedsheep.engine.legalactions.LegalAction>,
    ): SemanticChoice {
        val (type, rawBody, label, sourceId) = when (choice) {
            is ArgentumEngineChoice.Action -> listOf(
                "ACTION",
                engineJson.encodeToJsonElement(GameAction.serializer(), choice.value),
                actionDisplayLabel(choice.value, observation, legalActions),
                choice.value.policySourceEntityIdOrNull(),
            )
            is ArgentumEngineChoice.Decision -> listOf(
                "DECISION",
                engineJson.encodeToJsonElement(DecisionResponse.serializer(), choice.value)
                    .replaceTopLevelStringField(
                        "decisionId",
                        environmentDecisionId(observation),
                        DECISION_PLACEHOLDER,
                    ),
                observation.pendingDecision?.prompt ?: "Decision",
                observation.pendingDecision?.sourceEntityId,
            )
        }
        @Suppress("UNCHECKED_CAST")
        val body = if (choice is ArgentumEngineChoice.Action && choice.value is DeclareBlockers &&
            choice.value.blockers.values.all { it.size <= 1 }
        ) {
            semanticDeclareBlockers(choice.value, refs)
        } else {
            when (choice) {
                is ArgentumEngineChoice.Action ->
                    refs.semanticActionJson(choice.value, rawBody as JsonObject)
                is ArgentumEngineChoice.Decision ->
                    refs.semanticDecisionResponseJson(choice.value, rawBody as JsonObject)
            }
        }
        val payload = buildJsonObject {
            put("engineChoiceType", JsonPrimitive(type as String))
            put("body", body)
        }
        val operationFamily = operationFamily(choice)
        val sourceName = (sourceId as EntityId?)?.let { id ->
            observation.zones.asSequence().flatMap { it.cards.asSequence() }
                .firstOrNull { it.entityId == id }?.name
                ?: observation.stack.firstOrNull { it.entityId == id }?.name
                ?: observation.pendingDecision?.sourceName
        }
        val targetNames = when (choice) {
            is ArgentumEngineChoice.Action -> actionTargetIds(choice.value).mapNotNull { id ->
                observation.players.firstOrNull { it.id == id }?.name
                    ?: observation.zones.asSequence().flatMap { it.cards.asSequence() }
                        .firstOrNull { it.entityId == id }?.name
                    ?: observation.stack.firstOrNull { it.entityId == id }?.name
            }
            is ArgentumEngineChoice.Decision -> emptyList()
        }.distinct()
        val actionIntent = actionIntent(choice, observation, sourceName)
        return SemanticChoice(
            signature = SemanticChoice.computeSignature(operationFamily, actionIntent, payload),
            kind = if (choice is ArgentumEngineChoice.Action) SemanticChoiceKind.ACTION else SemanticChoiceKind.DECISION,
            operationFamily = operationFamily,
            actionIntent = actionIntent,
            display = SemanticChoiceDisplay(label as String, sourceName = sourceName, targetNames = targetNames),
            canonicalPayload = payload,
        )
    }

    private fun actionIntent(
        choice: ArgentumEngineChoice,
        observation: TrainingObservation,
        sourceName: String?,
    ): SemanticActionIntent {
        val action = (choice as? ArgentumEngineChoice.Action)?.value
        val targetRelations = action?.let(::actionTargetIds).orEmpty().mapNotNullTo(linkedSetOf()) { targetId ->
            when {
                targetId == observation.perspectivePlayerId -> SemanticActionTargetRelation.SELF_PLAYER
                observation.players.any { it.id == targetId } -> SemanticActionTargetRelation.OPPONENT_PLAYER
                else -> observation.zones.asSequence().flatMap { it.cards.asSequence() }
                    .firstOrNull { it.entityId == targetId }?.let { target ->
                        when (target.controllerId ?: target.ownerId) {
                            observation.perspectivePlayerId -> SemanticActionTargetRelation.SELF_CONTROLLED_OBJECT
                            null -> SemanticActionTargetRelation.OTHER_VISIBLE_OBJECT
                            else -> SemanticActionTargetRelation.OPPONENT_CONTROLLED_OBJECT
                        }
                    } ?: observation.stack.firstOrNull { it.entityId == targetId }?.let { target ->
                        when (target.controllerId) {
                            observation.perspectivePlayerId -> SemanticActionTargetRelation.SELF_CONTROLLED_OBJECT
                            null -> SemanticActionTargetRelation.OTHER_VISIBLE_OBJECT
                            else -> SemanticActionTargetRelation.OPPONENT_CONTROLLED_OBJECT
                        }
                    }
            }
        }
        return SemanticActionIntent(
            kind = when (choice) {
                is ArgentumEngineChoice.Decision -> SemanticActionIntentKind.RESPOND_TO_DECISION
                is ArgentumEngineChoice.Action -> when {
                    choice.value is PassPriority -> SemanticActionIntentKind.PASS_PRIORITY
                    choice.isManaAbility -> SemanticActionIntentKind.PRODUCE_MANA
                    choice.value is CastSpell -> SemanticActionIntentKind.CAST_SPELL
                    choice.value is PlayLand -> SemanticActionIntentKind.PLAY_LAND
                    choice.value is ActivateAbility -> SemanticActionIntentKind.ACTIVATE_ABILITY
                    choice.value is DeclareAttackers && choice.value.attackers.isEmpty() ->
                        SemanticActionIntentKind.DECLINE_ATTACK
                    choice.value is DeclareAttackers -> SemanticActionIntentKind.DECLARE_ATTACKERS
                    choice.value is DeclareBlockers && choice.value.blockers.values.all(List<EntityId>::isEmpty) ->
                        SemanticActionIntentKind.DECLINE_BLOCK
                    choice.value is DeclareBlockers -> SemanticActionIntentKind.DECLARE_BLOCKERS
                    choice.value is KeepHand -> SemanticActionIntentKind.KEEP_HAND
                    choice.value is TakeMulligan -> SemanticActionIntentKind.TAKE_MULLIGAN
                    choice.value is BottomCards -> SemanticActionIntentKind.BOTTOM_CARDS
                    else -> SemanticActionIntentKind.OTHER
                }
            },
            sourceCardName = sourceName,
            targetRelations = targetRelations,
        )
    }

    private fun operationFamily(choice: ArgentumEngineChoice): SemanticOperationFamily = when (choice) {
        is ArgentumEngineChoice.Decision -> SemanticOperationFamily.DECISION_RESPONSE
        is ArgentumEngineChoice.Action -> when {
            choice.value is PassPriority -> SemanticOperationFamily.PASS_PRIORITY
            choice.isManaAbility -> SemanticOperationFamily.MANA_ABILITY
            choice.value is CastSpell -> SemanticOperationFamily.CAST_SPELL
            choice.value is PlayLand -> SemanticOperationFamily.PLAY_LAND
            choice.value is ActivateAbility -> SemanticOperationFamily.ACTIVATE_ABILITY
            choice.value is DeclareAttackers -> SemanticOperationFamily.DECLARE_ATTACKERS
            choice.value is DeclareBlockers -> SemanticOperationFamily.DECLARE_BLOCKERS
            choice.value is KeepHand || choice.value is TakeMulligan || choice.value is BottomCards ->
                SemanticOperationFamily.MULLIGAN
            else -> SemanticOperationFamily.OTHER
        }
    }

    private fun actionTargetIds(action: GameAction): List<EntityId> = when (action) {
        is CastSpell -> action.targets.map(::chosenTargetId) + action.damageDistribution.orEmpty().keys
        is ActivateAbility -> action.targets.map(::chosenTargetId) + action.damageDistribution.orEmpty().keys
        is DeclareAttackers -> action.attackers.values.toList()
        is DeclareBlockers -> action.blockers.values.flatten()
        else -> emptyList()
    }

    /**
     * Canonicalize ordinary one-attacker-per-blocker assignments as a colored incidence topology.
     * Identical creatures remain interchangeable, but sharing one attacker and splitting across
     * two attackers produce different semantic payloads.
     */
    private fun semanticDeclareBlockers(
        action: DeclareBlockers,
        refs: SafeReferenceMap,
    ): JsonObject {
        data class AttackerBundle(
            val attackerClass: String,
            val blockerCounts: Map<String, Int>,
        )

        val byAttacker = linkedMapOf<EntityId, MutableMap<String, Int>>()
        action.blockers.forEach { (blocker, attackers) ->
            attackers.forEach { attacker ->
                val counts = byAttacker.getOrPut(attacker) { linkedMapOf() }
                val blockerClass = refs.semanticReference(blocker)
                counts[blockerClass] = counts.getOrDefault(blockerClass, 0) + 1
            }
        }
        val bundles = byAttacker.map { (attacker, counts) ->
            AttackerBundle(refs.semanticReference(attacker), counts.toSortedMap())
        }.groupBy(AttackerBundle::attackerClass).toSortedMap()
        val blockerOrdinals = mutableMapOf<String, Int>()
        val canonicalAssignments = sortedMapOf<String, JsonElement>()
        bundles.forEach { (attackerClass, equivalentAttackers) ->
            equivalentAttackers.sortedBy { bundle ->
                bundle.blockerCounts.entries.joinToString("\u001f") { (blockerClass, count) ->
                    "$blockerClass=$count"
                }
            }.forEachIndexed { attackerOrdinal, bundle ->
                val attackerRef = "$attackerClass#$attackerOrdinal"
                bundle.blockerCounts.forEach { (blockerClass, count) ->
                    repeat(count) {
                        val blockerOrdinal = blockerOrdinals.getOrDefault(blockerClass, 0)
                        blockerOrdinals[blockerClass] = blockerOrdinal + 1
                        canonicalAssignments["$blockerClass#$blockerOrdinal"] = JsonArray(
                            listOf(JsonPrimitive(attackerRef))
                        )
                    }
                }
            }
        }
        return buildJsonObject {
            put("blockers", JsonObject(canonicalAssignments))
            put("playerId", JsonPrimitive(refs.semanticReference(action.playerId)))
            put("type", JsonPrimitive("DeclareBlockers"))
        }
    }

    private fun chosenTargetId(target: ChosenTarget): EntityId = when (target) {
        is ChosenTarget.Player -> target.playerId
        is ChosenTarget.Permanent -> target.entityId
        is ChosenTarget.Spell -> target.spellEntityId
        is ChosenTarget.Card -> target.cardId
    }

    private fun actionDisplayLabel(
        action: GameAction,
        observation: TrainingObservation,
        legalActions: List<com.wingedsheep.engine.legalactions.LegalAction>,
    ): String {
        if (action is DeclareAttackers) return attackDisplayLabel(action, observation)
        val source = action.policySourceEntityIdOrNull()
        val matching = legalActions.filter { legal ->
            legal.action.policySourceEntityIdOrNull() == source && legal.actionType == action::class.simpleName
        }
        val exact = matching.firstOrNull { legal ->
            val legalAction = legal.action
            when {
                action is CastSpell && legalAction is CastSpell ->
                    legalAction.cardId == action.cardId &&
                        legalAction.useAlternativeCost == action.useAlternativeCost &&
                        legalAction.alternativeCostType == action.alternativeCostType &&
                        legalAction.declaredCostSlot == action.declaredCostSlot
                action is ActivateAbility && legalAction is ActivateAbility ->
                    legalAction.sourceId == action.sourceId &&
                        legalAction.abilityId == action.abilityId &&
                        legalAction.manaColorChoice == action.manaColorChoice
                else -> legalAction == action
            }
        }
        val base = exact?.description ?: matching.firstOrNull()?.description
            ?: action::class.simpleName.orEmpty()
        val alternativeCostType = (action as? CastSpell)?.alternativeCostType
        return if (action is CastSpell && action.useAlternativeCost && alternativeCostType != null) {
            "$base (${alternativeCostType.name.lowercase().replaceFirstChar(Char::uppercase)})"
        } else {
            base
        }
    }

    private fun attackDisplayLabel(action: DeclareAttackers, observation: TrainingObservation): String {
        if (action.attackers.isEmpty()) return "No attacks"
        val byDefender = action.attackers.entries.groupBy({ it.value }, { it.key })
        if (byDefender.size == 1) {
            return "Attack with ${countedNames(byDefender.values.single(), observation)}"
        }
        return byDefender.entries
            .sortedBy { (defender) -> observableName(defender, observation) ?: "defender" }
            .joinToString(prefix = "Attack ", separator = "; ") { (defender, attackers) ->
                val defenderName = observableName(defender, observation) ?: "defender"
                "$defenderName with ${countedNames(attackers, observation)}"
            }
    }

    private fun countedNames(ids: List<EntityId>, observation: TrainingObservation): String = ids
        .map { observableName(it, observation) ?: "unknown attacker" }
        .groupingBy { it }
        .eachCount()
        .toSortedMap()
        .entries
        .joinToString(" and ") { (name, count) -> if (count == 1) name else "$name x$count" }

    private fun observableName(id: EntityId, observation: TrainingObservation): String? =
        observation.players.firstOrNull { it.id == id }?.name
            ?: observation.zones.asSequence().flatMap { it.cards.asSequence() }
                .firstOrNull { it.entityId == id }?.name
            ?: observation.stack.firstOrNull { it.entityId == id }?.name

    private fun environmentDecisionId(observation: TrainingObservation): String? =
        observation.pendingDecision?.decisionId

    private data class GeneratedChoices(
        val choices: Sequence<ArgentumEngineChoice>,
        val estimatedCount: Long?,
        val sourceExhaustive: Boolean,
        val profileSourceExhaustive: Boolean = sourceExhaustive,
        val omissionReasons: Set<PolicyExpansionOmissionReason> = if (sourceExhaustive) {
            emptySet()
        } else {
            setOf(PolicyExpansionOmissionReason.SOURCE_NON_EXHAUSTIVE)
        },
        val preserveOrder: Boolean = false,
    )

    private data class ActionCandidates(
        val actions: Sequence<GameAction>,
        val estimatedCount: Long,
        val exhaustive: Boolean,
        val preserveOrder: Boolean = false,
    )

    private data class SelectionCandidates<T>(
        val values: Sequence<List<T>>,
        val estimatedCount: Long,
        val exhaustive: Boolean,
    )

    private data class DistributionCandidates(
        val values: Sequence<Map<EntityId, Int>?>,
        val estimatedCount: Long,
    )

    companion object {
        const val DEFAULT_RESPONSE_LIMIT = 64
        const val DEFAULT_MAXIMUM_ATTEMPTS = 2_048
        const val DEFAULT_PROPOSAL_ALGORITHM_VERSION = "semantic-structured-actions-v4"
        const val MAX_GENERATED_SCANS = 1_000_000
        private const val DECISION_PLACEHOLDER = "\$CURRENT_DECISION_ID"

        fun defaultBehaviorSpecification(
            actionSpaceProfile: SearchActionSpaceProfile,
        ): UnifiedSemanticExpansionSpecification = UnifiedSemanticExpander(
            actionSpaceProfile = actionSpaceProfile,
        ).behaviorSpecification

        private val engineJson = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            classDiscriminator = "type"
        }
    }
}

internal fun policyActor(environment: GameEnvironment): EntityId? =
    mulliganActor(environment) ?: environment.agentToAct

private fun mulliganActor(environment: GameEnvironment): EntityId? {
    val state = environment.state
    val mulligans = state.turnOrder.mapNotNull { player ->
        state.getEntity(player)?.get<MulliganStateComponent>()?.let { player to it }
    }
    return mulligans.firstOrNull { (_, component) -> !component.hasKept }?.first
        ?: mulligans.firstOrNull { (_, component) -> component.hasKept && component.cardsToBottom > 0 }?.first
}

private fun <T> cartesian(choices: List<List<T>>): Sequence<List<T>> = sequence {
    if (choices.isEmpty()) {
        yield(emptyList())
        return@sequence
    }
    suspend fun SequenceScope<List<T>>.visit(index: Int, prefix: MutableList<T>) {
        if (index == choices.size) {
            yield(prefix.toList())
            return
        }
        for (value in choices[index]) {
            prefix += value
            visit(index + 1, prefix)
            prefix.removeAt(prefix.lastIndex)
        }
    }
    visit(0, mutableListOf())
}

private fun <T> selectionLists(values: List<T>, minimum: Int, maximum: Int): Sequence<List<T>> = sequence {
    for (size in minimum.coerceAtLeast(0)..maximum.coerceAtMost(values.size)) {
        yieldAll(combinations(values, size))
    }
}

private fun <T> combinations(values: List<T>, size: Int): Sequence<List<T>> = sequence {
    if (size == 0) {
        yield(emptyList())
        return@sequence
    }
    suspend fun SequenceScope<List<T>>.visit(start: Int, remaining: Int, prefix: MutableList<T>) {
        if (remaining == 0) {
            yield(prefix.toList())
            return
        }
        for (index in start..values.size - remaining) {
            prefix += values[index]
            visit(index + 1, remaining - 1, prefix)
            prefix.removeAt(prefix.lastIndex)
        }
    }
    if (size <= values.size) visit(0, size, mutableListOf())
}

private fun <T> orderedSelections(values: List<T>, size: Int): Sequence<List<T>> = sequence {
    if (size == 0) {
        yield(emptyList())
        return@sequence
    }
    suspend fun SequenceScope<List<T>>.visit(prefix: MutableList<T>, remaining: MutableList<T>) {
        if (prefix.size == size) {
            yield(prefix.toList())
            return
        }
        for (index in remaining.indices.toList()) {
            val value = remaining.removeAt(index)
            prefix += value
            visit(prefix, remaining)
            prefix.removeAt(prefix.lastIndex)
            remaining.add(index, value)
        }
    }
    if (size <= values.size) visit(mutableListOf(), values.toMutableList())
}

private fun integerVectors(length: Int, total: Int): Sequence<List<Int>> = sequence {
    if (length == 0) {
        if (total == 0) yield(emptyList())
        return@sequence
    }
    suspend fun SequenceScope<List<Int>>.visit(index: Int, remaining: Int, prefix: MutableList<Int>) {
        if (index == length - 1) {
            prefix += remaining
            yield(prefix.toList())
            prefix.removeAt(prefix.lastIndex)
            return
        }
        for (amount in 0..remaining) {
            prefix += amount
            visit(index + 1, remaining - amount, prefix)
            prefix.removeAt(prefix.lastIndex)
        }
    }
    visit(0, total, mutableListOf())
}

private fun boundaryFirst(range: IntRange): Sequence<Int> = sequence {
    if (range.isEmpty()) return@sequence
    yield(range.first)
    if (range.last != range.first) yield(range.last)
    for (value in (range.first + 1) until range.last) yield(value)
}

private fun combinationRangeCount(n: Int, minimum: Int, maximum: Int): Long =
    (minimum..maximum).fold(0L) { total, size -> saturatingAdd(total, combination(n, size)) }

private fun combination(n: Int, k: Int): Long {
    if (k < 0 || k > n) return 0L
    val use = minOf(k, n - k)
    var value = BigInteger.ONE
    for (index in 1..use) {
        value = value.multiply(BigInteger.valueOf((n - use + index).toLong()))
            .divide(BigInteger.valueOf(index.toLong()))
        if (value > BigInteger.valueOf(Long.MAX_VALUE)) return Long.MAX_VALUE
    }
    return value.toLong()
}

private fun permutationCount(n: Int, k: Int): Long {
    if (k < 0 || k > n) return 0L
    var result = 1L
    for (value in (n - k + 1)..n) result = saturatingMultiply(result, value.toLong())
    return result
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

private fun saturatingMultiply(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}

private fun JsonElement.replaceTopLevelStringField(
    field: String,
    old: String?,
    new: String?,
): JsonElement {
    if (old == null || new == null) return this
    val objectValue = this as? JsonObject ?: return this
    val current = objectValue[field] as? JsonPrimitive ?: return this
    if (!current.isString || current.content != old) return this
    return JsonObject(objectValue.toMutableMap().apply { put(field, JsonPrimitive(new)) })
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content
