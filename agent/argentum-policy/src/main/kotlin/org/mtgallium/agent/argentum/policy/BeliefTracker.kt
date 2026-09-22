package org.mtgallium.agent.argentum.policy

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.BeliefSnapshot
import org.mtgallium.agent.infoset.core.BeliefSnapshotSource
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.argentum.ArgentumParticleBeliefSnapshot

import org.mtgallium.agent.infoset.argentum.ArgentumBeliefSupport
import org.mtgallium.agent.infoset.argentum.ArgentumBeliefProposalAuditSink
import org.mtgallium.agent.infoset.argentum.ArgentumConditionalRejuvenator
import org.mtgallium.agent.infoset.argentum.ArgentumHybridBeliefWorldSource
import org.mtgallium.agent.infoset.argentum.ArgentumKnownDeckBeliefWorldSource
import org.mtgallium.agent.infoset.argentum.ArgentumParticleDiagnostics
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.ActionSelector
import org.mtgallium.agent.infoset.core.ActionDistributionModel
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.ParticleBelief
import org.mtgallium.agent.infoset.core.ParticleDepletionException
import org.mtgallium.agent.infoset.core.ParticleRejuvenator
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.Weighted

/** Immutable, read-only counters for one production belief lifecycle. */
data class BeliefUpdateDiagnostics(
    val initialConstructionAttempts: Int,
    val initialConstructionCompletions: Int,
    val initialConstructionRefusals: Int,
    val rebuildAttempts: Int,
    val rebuildCompletions: Int,
    val rebuildRefusals: Int,
    val conditioningAttempts: Int,
    val conditioningCompletions: Int,
    val conditioningDepletions: Int,
    val conditioningSupportRefusals: Int,
    val conditioningOtherRefusals: Int,
    val sequentialUpdateAttempts: Int,
    val sequentialUpdateCompletions: Int,
    val sequentialUpdateDepletions: Int,
    val sequentialUpdateSupportRefusals: Int,
    val sequentialUpdateOtherRefusals: Int,
) {
    init {
        require(
            initialConstructionAttempts ==
                initialConstructionCompletions + initialConstructionRefusals
        )
        require(rebuildAttempts == rebuildCompletions + rebuildRefusals)
        require(
            conditioningAttempts == conditioningCompletions + conditioningDepletions +
                conditioningSupportRefusals + conditioningOtherRefusals
        )
        require(
            sequentialUpdateAttempts == sequentialUpdateCompletions + sequentialUpdateDepletions +
                sequentialUpdateSupportRefusals + sequentialUpdateOtherRefusals
        )
    }
}

/** Concrete particle maintenance backend, shared by live reconstruction and experiment arenas. */
internal class ArgentumParticleBeliefBackend private constructor(
    root: ArgentumSearchWorld,
    private val viewer: String,
    private val knownDecks: Map<String, Map<String, Int>>,
    private val parameters: BeliefConfig,
    private val opponentDistribution: ActionDistributionModel,
    private val privateChoiceSelector: ActionSelector,
    private val gameId: String,
    private val proposalAuditSink: ArgentumBeliefProposalAuditSink,
    source: ArgentumParticleBeliefBackend?,
) : BeliefSnapshotSource {
    constructor(root: ArgentumSearchWorld, viewer: String, knownDecks: Map<String, Map<String, Int>>,
        parameters: SearchPolicyConfig, opponentModel: OpponentPolicy, gameId: String,
        proposalAuditSink: ArgentumBeliefProposalAuditSink) :
        this(root, viewer, knownDecks, parameters.beliefConfiguration(), opponentModel, opponentModel, gameId, proposalAuditSink, null)

    constructor(root: ArgentumSearchWorld, viewer: String, knownDecks: Map<String, Map<String, Int>>,
        configuration: BeliefConfig, opponentModel: OpponentPolicy, gameId: String,
        proposalAuditSink: ArgentumBeliefProposalAuditSink) :
        this(root, viewer, knownDecks, configuration, opponentModel, opponentModel, gameId, proposalAuditSink, null)

    constructor(root: ArgentumSearchWorld, viewer: String, knownDecks: Map<String, Map<String, Int>>,
        configuration: BeliefConfig, opponentDistribution: ActionDistributionModel,
        privateChoiceSelector: ActionSelector, gameId: String, proposalAuditSink: ArgentumBeliefProposalAuditSink) :
        this(root, viewer, knownDecks, configuration, opponentDistribution, privateChoiceSelector,
            gameId, proposalAuditSink, null)

    private val rejuvenator: ParticleRejuvenator =
        if (parameters.beliefMode == BeliefMode.POLICY_CONDITIONED_V1) ParticleRejuvenator.FORK_ONLY
        else ArgentumConditionalRejuvenator(
        knownDecks,
        viewer,
        proposalAuditSink,
        "$viewer:conditional-rejuvenation",
    )
    private var belief: ParticleBelief
    var latestDiagnostics: BeliefDiagnostics
        private set
    val diagnosticsHistory: MutableList<BeliefDiagnostics> = source?.diagnosticsHistory?.toMutableList() ?: mutableListOf()
    var reconditionings: Int = source?.reconditionings ?: 0
        private set
    val particleDepletions: Int
        get() = conditioningDepletions + sequentialUpdateDepletions
    var continuityEpoch: Long = source?.continuityEpoch ?: 0L
        private set
    private var rebuildAttempts: Int = source?.rebuildAttempts ?: 0
    private var rebuildCompletions: Int = source?.rebuildCompletions ?: 0
    private var rebuildRefusals: Int = source?.rebuildRefusals ?: 0
    private var conditioningAttempts: Int = source?.conditioningAttempts ?: 0
    private var conditioningCompletions: Int = source?.conditioningCompletions ?: 0
    private var conditioningDepletions: Int = source?.conditioningDepletions ?: 0
    private var conditioningSupportRefusals: Int = source?.conditioningSupportRefusals ?: 0
    private var conditioningOtherRefusals: Int = source?.conditioningOtherRefusals ?: 0
    private var sequentialUpdateAttempts: Int = source?.sequentialUpdateAttempts ?: 0
    private var sequentialUpdateCompletions: Int = source?.sequentialUpdateCompletions ?: 0
    private var sequentialUpdateDepletions: Int = source?.sequentialUpdateDepletions ?: 0
    private var sequentialUpdateSupportRefusals: Int = source?.sequentialUpdateSupportRefusals ?: 0
    private var sequentialUpdateOtherRefusals: Int = source?.sequentialUpdateOtherRefusals ?: 0
    private var pendingDepletion: Boolean = source?.pendingDepletion ?: false
    private var expectedInformation: InformationStateRepresentation = source?.expectedInformation ?: root.informationState(viewer)
    private var maintenanceFailure: ConditionedBeliefReconstructionRequired? = source?.maintenanceFailure

    init {
        require(root.historyObjectReference !=
            org.mtgallium.agent.infoset.argentum.PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2 ||
            parameters.beliefArchitecture == BeliefArchitecture.SEQUENTIAL_B_V1) {
            "Qualified observed updates require SEQUENTIAL_B_V1"
        }
        if (parameters.beliefMode == BeliefMode.POLICY_CONDITIONED_V1 &&
            parameters.beliefArchitecture != BeliefArchitecture.SEQUENTIAL_B_V1) {
            throw ConditionedBeliefReconstructionRequired("UNSUPPORTED_ARCHITECTURE")
        }
        maintenanceFailure?.let { throw it }
        if (source != null) {
            belief = source.belief.fork()
            latestDiagnostics = source.latestDiagnostics
        } else {
            val initial = sample(root, expectedInformation, "initial")
            belief = ParticleBelief.from(initial, parameters.beliefMode)
            latestDiagnostics = initial.diagnostics.copy(
                mode = parameters.beliefMode,
                marginalCardProbabilities = ArgentumParticleDiagnostics.opponentHandMarginals(belief, viewer),
            )
        }
    }

    /** Continue the same posterior and update lifecycle on an independently forked factual world. */
    fun fork(actual: ArgentumSearchWorld): ArgentumParticleBeliefBackend {
        maintenanceFailure?.let { throw it }
        require(actual.informationState(viewer).informationStateDigest == expectedInformation.informationStateDigest) {
            "Factual continuation must preserve the session's current information state"
        }
        return ArgentumParticleBeliefBackend(
            actual,
            viewer,
            knownDecks,
            parameters,
            opponentDistribution,
            privateChoiceSelector,
            gameId,
            proposalAuditSink,
            this,
        )
    }

    val lowEssUpdates: Int
        get() = diagnosticsHistory.count {
            it.effectiveSampleSizeBefore < parameters.particles / 10.0
        }

    val invalidWeights: Int
        get() = diagnosticsHistory.sumOf { it.failures["invalidWeights"] ?: 0 }

    val lifecycleDiagnostics: BeliefUpdateDiagnostics
        get() = BeliefUpdateDiagnostics(
            // A tracker is observable only after construction succeeds; forks retain that origin.
            initialConstructionAttempts = 1,
            initialConstructionCompletions = 1,
            initialConstructionRefusals = 0,
            rebuildAttempts = rebuildAttempts,
            rebuildCompletions = rebuildCompletions,
            rebuildRefusals = rebuildRefusals,
            conditioningAttempts = conditioningAttempts,
            conditioningCompletions = conditioningCompletions,
            conditioningDepletions = conditioningDepletions,
            conditioningSupportRefusals = conditioningSupportRefusals,
            conditioningOtherRefusals = conditioningOtherRefusals,
            sequentialUpdateAttempts = sequentialUpdateAttempts,
            sequentialUpdateCompletions = sequentialUpdateCompletions,
            sequentialUpdateDepletions = sequentialUpdateDepletions,
            sequentialUpdateSupportRefusals = sequentialUpdateSupportRefusals,
            sequentialUpdateOtherRefusals = sequentialUpdateOtherRefusals,
        )

    private var capturedSnapshot: BeliefSnapshot? = null
    private val qualifiedObservedMode = when (parameters.observedConditioning) {
        ObservedBeliefConditioning.HISTORICAL_GROUP_SIGNATURE_V1 -> false
        ObservedBeliefConditioning.QUALIFIED_SUPPORTED_FAMILIES_V1 -> true
        null -> root.historyObjectReference ==
            org.mtgallium.agent.infoset.argentum.PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2
    }
    var lastObservedUpdate: ObservedBeliefUpdateEvidence? = source?.lastObservedUpdate
        private set
    private var exactObservedConditioningUsed: Boolean = source?.exactObservedConditioningUsed ?: false
    private val inferenceModelIdentity: String = "particle-inference-v1-sha256:" + PolicyJson.digest(buildJsonObject {
        put("configuration", PolicyJson.format.encodeToJsonElement(parameters))
        put("opponentDistribution", PolicyJson.format.encodeToJsonElement(opponentDistribution.behaviorSpecification))
        put("privateChoiceSelector", PolicyJson.format.encodeToJsonElement(privateChoiceSelector.behaviorSpecification))
        // Direct observed submission no longer derives optionality from proposal expansion,
        // including in historical representation/signature modes. Keep configuration bytes,
        // but never reuse the old inference identity for those changed histories.
        put("observedSubmission", root.observedActionBehaviorId())
        if (qualifiedObservedMode) {
            put("observedUpdate", QUALIFIED_OBSERVED_BELIEF_V1)
        }
        put("maintenance", if (parameters.beliefMode == BeliefMode.POLICY_CONDITIONED_V1)
            "descendant-information-knowledge-copying-resampling-v1" else "existing-consistency-rejuvenation-v1")
    })

    override fun snapshot(): BeliefSnapshot {
        maintenanceFailure?.let { throw it }
        return capturedSnapshot ?: ArgentumParticleBeliefSnapshot.capture(belief, expectedInformation,
            latestDiagnostics, inferenceModelIdentity + if (exactObservedConditioningUsed && !qualifiedObservedMode)
                ":qualified-exact-observed:retained-representative-only-v1" else "").also { capturedSnapshot = it }
    }

    /** Compatibility adapter for callers still consuming prepared weighted worlds. */
    fun batch(): BeliefBatch<Weighted<SearchWorld>> = snapshot().hypotheses.materialize().batch

    fun synchronize(actual: ArgentumSearchWorld, decisionIndex: Int) {
        maintenanceFailure?.let { throw it }
        val expected = actual.informationState(viewer)
        val particles = belief.weightedWorlds()
        val digestMismatches = particles.count { weighted ->
            weighted.value.informationState(viewer).informationStateDigest != expected.informationStateDigest
        }

        var supportFailures = ArgentumBeliefSupport.failures(
            particles.map { it.value },
            viewer,
            expected,
        )
        var unsupportedParticles = supportFailures.values.sum()
        val rootRefresh = parameters.beliefArchitecture in setOf(
            BeliefArchitecture.SNAPSHOT_A_V1,
            BeliefArchitecture.HYBRID_C_V1,
        )
        if (digestMismatches == 0 && supportFailures.isEmpty() && !rootRefresh && !pendingDepletion) {
            val knowledgeChanged = latestDiagnostics.knowledgeDigest != expected.knowledge.knowledgeDigest
            if (expectedInformation != expected || knowledgeChanged) capturedSnapshot = null
            if (knowledgeChanged) latestDiagnostics = latestDiagnostics.copy(knowledgeDigest = expected.knowledge.knowledgeDigest)
            expectedInformation = expected
            return
        }
        capturedSnapshot = null
        if (parameters.beliefArchitecture == BeliefArchitecture.SEQUENTIAL_B_V1 &&
            digestMismatches > 0 && supportFailures.isEmpty() && !pendingDepletion
        ) {
            conditioningAttempts++
            val conditioned = try {
                belief.conditionOnInformationState(
                    viewer = viewer,
                    expectedInformationStateDigest = expected.informationStateDigest,
                    updateSeed = ComponentSeeds.derive(gameId, decisionIndex, "safe-information-conditioning"),
                    rejuvenator = rejuvenator,
                    updatedKnowledgeDigest = expected.knowledge.knowledgeDigest,
                )
            } catch (_: ParticleDepletionException) {
                conditioningDepletions++
                null
            } catch (failure: Throwable) {
                conditioningOtherRefusals++
                throw failure
            }
            if (conditioned != null) {
                val conditionedFailures = try {
                    ArgentumBeliefSupport.completeFailures(
                        conditioned.belief.weightedWorlds().map { it.value },
                        viewer,
                        expected,
                    )
                } catch (failure: Throwable) {
                    conditioningOtherRefusals++
                    throw failure
                }
                if (conditionedFailures.isEmpty()) {
                    try {
                        belief = conditioned.belief
                        latestDiagnostics = conditioned.diagnostics.copy(
                            marginalCardProbabilities =
                                ArgentumParticleDiagnostics.opponentHandMarginals(belief, viewer),
                        )
                        diagnosticsHistory += latestDiagnostics
                        reconditionings++
                        conditioningCompletions++
                        expectedInformation = expected
                        return
                    } catch (failure: Throwable) {
                        conditioningOtherRefusals++
                        throw failure
                    }
                }
                conditioningSupportRefusals++
                supportFailures = conditionedFailures
                unsupportedParticles = ArgentumBeliefSupport.incompatibleWorldCount(
                    conditioned.belief.weightedWorlds().map { it.value },
                    viewer,
                    expected,
                )
            }
        }
        rebuildPopulation(
            actual = actual,
            expected = expected,
            purpose = "recondition:$decisionIndex",
            incrementContinuity = !pendingDepletion,
            failures = buildMap {
                if (digestMismatches > 0) put("informationMismatchParticles", digestMismatches)
                if (supportFailures.isNotEmpty()) {
                    put("rememberedFactContradictionParticles", unsupportedParticles)
                }
            },
        )
    }

    fun advance(
        actual: ArgentumSearchWorld,
        actor: String,
        choice: SemanticChoice,
        decisionIndex: Int,
        privateToActor: Boolean,
        exactObservedAction: org.mtgallium.agent.infoset.argentum.ArgentumObservedActionCapture? = null,
    ) {
        require(exactObservedAction == null ||
            parameters.observedConditioning != ObservedBeliefConditioning.HISTORICAL_GROUP_SIGNATURE_V1) {
            "An exact update conflicts with the declared historical group-conditioning mode"
        }
        capturedSnapshot = null
        maintenanceFailure?.let { throw it }
        val expected = actual.informationState(viewer)
        val exactCapture = exactObservedAction ?: if (qualifiedObservedMode && !privateToActor &&
            actual.lastObservedActionHasQualifiedTransportForHost() == true) {
            requireNotNull(actual.lastObservedActionCaptureForHost(viewer)) { "Missing accepted host predecessor" }
        } else null
        if (qualifiedObservedMode) requireNotNull(actual.lastObservedActionHasQualifiedTransportForHost()) {
            "Qualified updates require the actual accepted host transition"
        }
        val route = when {
            privateToActor && actor != viewer -> ObservedBeliefUpdateRoute.PRIVATE_UNOBSERVED_V1
            exactCapture != null -> ObservedBeliefUpdateRoute.QUALIFIED_EXACT_MEMBER_V1
            qualifiedObservedMode -> ObservedBeliefUpdateRoute.UNSUPPORTED_FAMILY_SIGNATURE_COMPATIBILITY_V1
            else -> ObservedBeliefUpdateRoute.HISTORICAL_SIGNATURE_V1
        }
        lastObservedUpdate = ObservedBeliefUpdateEvidence(route, choice.operationFamily,
            if (qualifiedObservedMode) QUALIFIED_OBSERVED_BELIEF_V1 else route.name)
        if (exactCapture != null) {
            require(exactCapture.observerInformation.perspectivePlayerId == viewer)
            require(exactCapture.actingSite.actor == actor && exactCapture.searchGroup == choice)
            require(!privateToActor) { "Exact observer conditioning cannot expose a private declaration" }
            require(parameters.beliefArchitecture == BeliefArchitecture.SEQUENTIAL_B_V1)
            exactObservedConditioningUsed = true
        }
        if (parameters.beliefArchitecture in setOf(
                BeliefArchitecture.SNAPSHOT_A_V1,
                BeliefArchitecture.HYBRID_C_V1,
            )
        ) {
            rebuildPopulation(
                actual = actual,
                expected = expected,
                purpose = "refresh:${decisionIndex + 1}",
                incrementContinuity = !pendingDepletion,
            )
            return
        }
        val seed = ComponentSeeds.derive(gameId, decisionIndex, "live-belief-update")
        // New information is evidence about descendants, not a reason to redraw all worlds.
        // Filter before the core's single resampling step; keep the legacy mode unchanged.
        val observation = if (parameters.beliefMode == BeliefMode.POLICY_CONDITIONED_V1) {
            org.mtgallium.agent.infoset.core.ParticleObservationCondition(expected.knowledge.knowledgeDigest) { world ->
                ArgentumBeliefSupport.completeFailures(listOf(world), viewer, expected).isEmpty()
            }
        } else null
        val privateOpponentChoice = actor != viewer && privateToActor &&
            parameters.beliefArchitecture != BeliefArchitecture.PRIVILEGED_O_V1
        val signatureStep = if (exactCapture == null && !privateOpponentChoice)
            actual.observedChoicePropagationForHost(actor, choice) else null
        sequentialUpdateAttempts++
        val update = try {
            when {
                privateOpponentChoice -> belief.advanceUnobserved(
                    actor = actor,
                    opponentPolicy = privateChoiceSelector,
                    updateSeed = seed,
                    rejuvenator = rejuvenator,
                    observation = observation,
                )
                parameters.beliefMode == BeliefMode.POLICY_CONDITIONED_V1 && actor != viewer -> belief.advance(
                    actor = actor,
                    observedSignature = choice.signature,
                    conditioningPolicy = opponentDistribution,
                    updateSeed = seed,
                    rejuvenator = rejuvenator,
                    observation = observation,
                    exactAction = exactCapture?.particleAction(),
                    signatureStep = signatureStep,
                )
                else -> belief.advance(
                    actor = actor,
                    observedSignature = choice.signature,
                    updateSeed = seed,
                    rejuvenator = rejuvenator,
                    observation = observation,
                    exactAction = exactCapture?.particleAction(),
                    signatureStep = signatureStep,
                )
            }
        } catch (failure: org.mtgallium.agent.infoset.core.ExactObservationDepletionException) {
            sequentialUpdateDepletions++
            val stop = ConditionedBeliefReconstructionRequired(failure.report.kind.name, failure.report, failure)
            maintenanceFailure = stop
            throw stop
        } catch (failure: org.mtgallium.agent.infoset.core.UnsupportedObservedActionException) {
            sequentialUpdateOtherRefusals++
            val stop = ConditionedBeliefReconstructionRequired("UNAVAILABLE_CORRESPONDENCE", failure.report, failure)
            maintenanceFailure = stop
            throw stop
        } catch (_: ParticleDepletionException) {
            sequentialUpdateDepletions++
            pendingDepletion = true
            continuityEpoch++
            rebuildPopulation(
                actual = actual,
                expected = expected,
                purpose = "depletion:${decisionIndex + 1}",
                incrementContinuity = false,
                failures = mapOf("particlePopulationExhausted" to 1),
            )
            return
        } catch (failure: Throwable) {
            sequentialUpdateOtherRefusals++
            if (exactCapture != null) maintenanceFailure = ConditionedBeliefReconstructionRequired(
                "EXACT_OBSERVED_ACTION_REFUSAL")
            throw failure
        }
        val updatedWorlds = try {
            update.belief.weightedWorlds().map { it.value }
        } catch (failure: Throwable) {
            sequentialUpdateOtherRefusals++
            throw failure
        }
        val supportFailures = try {
            ArgentumBeliefSupport.completeFailures(
                updatedWorlds,
                viewer,
                expected,
            )
        } catch (failure: Throwable) {
            sequentialUpdateOtherRefusals++
            throw failure
        }
        if (supportFailures.isNotEmpty()) {
            sequentialUpdateSupportRefusals++
            rebuildPopulation(
                actual = actual,
                expected = expected,
                purpose = "post-advance:${decisionIndex + 1}",
                incrementContinuity = !pendingDepletion,
                failures = mapOf(
                    "postAdvanceContradictionParticles" to ArgentumBeliefSupport.incompatibleWorldCount(
                        updatedWorlds,
                        viewer,
                        expected,
                    ),
                ),
            )
            return
        }
        try {
            belief = update.belief
            latestDiagnostics = update.diagnostics.copy(
                knowledgeDigest = expected.knowledge.knowledgeDigest,
                marginalCardProbabilities = ArgentumParticleDiagnostics.opponentHandMarginals(belief, viewer),
            )
            diagnosticsHistory += latestDiagnostics
            sequentialUpdateCompletions++
            expectedInformation = expected
        } catch (failure: Throwable) {
            sequentialUpdateOtherRefusals++
            throw failure
        }
    }

    private fun rebuildPopulation(
        actual: ArgentumSearchWorld,
        expected: InformationStateRepresentation,
        purpose: String,
        incrementContinuity: Boolean,
        failures: Map<String, Int> = emptyMap(),
    ) {
        if (incrementContinuity) continuityEpoch++
        reconditionings++
        rebuildAttempts++
        try {
            if (parameters.beliefMode == BeliefMode.POLICY_CONDITIONED_V1) {
                val failure = ConditionedBeliefReconstructionRequired("KNOWLEDGE_ONLY_REBUILD")
                maintenanceFailure = failure
                throw failure
            }
            val resamplingCount = latestDiagnostics.resamplingCount + 1
            val rebuilt = sample(actual, expected, purpose)
            val rebuiltDiagnostics = rebuilt.diagnostics.copy(
                mode = parameters.beliefMode,
                resamplingCount = resamplingCount,
                failures = buildMap {
                    putAll(rebuilt.diagnostics.failures)
                    failures.forEach { (code, count) ->
                        put(code, getOrDefault(code, 0) + count)
                    }
                },
            )
            belief = ParticleBelief.from(
                rebuilt.copy(diagnostics = rebuiltDiagnostics),
                parameters.beliefMode,
            )
            latestDiagnostics = rebuiltDiagnostics.copy(
                marginalCardProbabilities = ArgentumParticleDiagnostics.opponentHandMarginals(belief, viewer),
            )
            diagnosticsHistory += latestDiagnostics
            pendingDepletion = false
            expectedInformation = expected
            rebuildCompletions++
        } catch (failure: Throwable) {
            rebuildRefusals++
            throw failure
        }
    }

    private fun sample(
        actual: ArgentumSearchWorld,
        information: InformationStateRepresentation,
        purpose: String,
    ): BeliefBatch<Weighted<SearchWorld>> {
        val seed = ComponentSeeds.derive(gameId, viewer, purpose)
        val batch = when (parameters.beliefArchitecture) {
            BeliefArchitecture.SNAPSHOT_A_V1,
            BeliefArchitecture.SEQUENTIAL_B_V1 -> ArgentumKnownDeckBeliefWorldSource(
                actual,
                proposalAuditSink,
                "$viewer:$purpose",
            )
                .sample(information, knownDecks, seed, parameters.particles)
                .let { batch ->
                    batch.copy(
                        diagnostics = batch.diagnostics.copy(architecture = parameters.beliefArchitecture),
                    )
                }
            BeliefArchitecture.HYBRID_C_V1 -> ArgentumHybridBeliefWorldSource(
                actual,
                proposalAuditSink,
                "$viewer:$purpose",
            )
                .sample(information, knownDecks, seed, parameters.particles)
            BeliefArchitecture.PRIVILEGED_O_V1 -> BeliefBatch(
                particles = List(parameters.particles) { particleIndex ->
                    Weighted(
                        actual.forkForHypotheticalSearch(
                            ComponentSeeds.derive(seed, particleIndex, "privileged-particle")
                        ),
                        1.0 / parameters.particles,
                    )
                },
                diagnostics = BeliefDiagnostics(
                    mode = parameters.beliefMode,
                    requestedParticles = parameters.particles,
                    acceptedParticles = parameters.particles,
                    rejectedParticles = 0,
                    effectiveSampleSizeBefore = parameters.particles.toDouble(),
                    effectiveSampleSizeAfter = parameters.particles.toDouble(),
                    entropy = kotlin.math.ln(parameters.particles.toDouble()),
                    resamplingCount = 0,
                    architecture = parameters.beliefArchitecture,
                    knowledgeDigest = information.knowledge.knowledgeDigest,
                ),
            )
        }
        ArgentumBeliefSupport.requireSupported(
            batch.particles.map { it.value },
            viewer,
            information,
            "Search belief $purpose",
        )
        return batch
    }
}
