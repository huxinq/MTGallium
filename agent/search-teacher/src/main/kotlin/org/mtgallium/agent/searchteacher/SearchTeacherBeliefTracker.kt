package org.mtgallium.agent.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
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
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.ParticleBelief
import org.mtgallium.agent.infoset.core.ParticleDepletionException
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.Weighted

/** Immutable, read-only counters for one production belief lifecycle. */
data class SearchTeacherBeliefLifecycleDiagnostics(
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

/** Stateful belief lifecycle shared by live reconstruction and experiment arenas. */
internal class SearchTeacherBeliefTracker(
    root: ArgentumSearchWorld,
    private val viewer: String,
    private val registry: CardRegistry,
    private val knownDecks: Map<String, Map<String, Int>>,
    private val parameters: SearchTeacherPolicyParameters,
    private val opponentModel: OpponentPolicy,
    private val gameId: String,
    private val proposalAuditSink: ArgentumBeliefProposalAuditSink,
) {
    private val rejuvenator = ArgentumConditionalRejuvenator(
        registry,
        knownDecks,
        viewer,
        proposalAuditSink,
        "$viewer:conditional-rejuvenation",
    )
    private var belief: ParticleBelief
    var latestDiagnostics: BeliefDiagnostics
        private set
    val diagnosticsHistory = mutableListOf<BeliefDiagnostics>()
    var reconditionings: Int = 0
        private set
    val particleDepletions: Int
        get() = conditioningDepletions + sequentialUpdateDepletions
    var continuityEpoch: Long = 0L
        private set
    private var initialConstructionAttempts: Int = 0
    private var initialConstructionCompletions: Int = 0
    private var initialConstructionRefusals: Int = 0
    private var rebuildAttempts: Int = 0
    private var rebuildCompletions: Int = 0
    private var rebuildRefusals: Int = 0
    private var conditioningAttempts: Int = 0
    private var conditioningCompletions: Int = 0
    private var conditioningDepletions: Int = 0
    private var conditioningSupportRefusals: Int = 0
    private var conditioningOtherRefusals: Int = 0
    private var sequentialUpdateAttempts: Int = 0
    private var sequentialUpdateCompletions: Int = 0
    private var sequentialUpdateDepletions: Int = 0
    private var sequentialUpdateSupportRefusals: Int = 0
    private var sequentialUpdateOtherRefusals: Int = 0
    private var pendingDepletion: Boolean = false
    private var expectedInformation: PolicyInformationState = root.informationState(viewer)

    init {
        initialConstructionAttempts++
        try {
            val initial = sample(root, expectedInformation, "initial")
            belief = ParticleBelief.from(initial, parameters.beliefMode)
            latestDiagnostics = initial.diagnostics.copy(
                mode = parameters.beliefMode,
                marginalCardProbabilities = ArgentumParticleDiagnostics.opponentHandMarginals(belief, viewer),
            )
            initialConstructionCompletions++
        } catch (failure: Throwable) {
            initialConstructionRefusals++
            throw failure
        }
    }

    val lowEssUpdates: Int
        get() = diagnosticsHistory.count {
            it.effectiveSampleSizeBefore < parameters.particles / 10.0
        }

    val invalidWeights: Int
        get() = diagnosticsHistory.sumOf { it.failures["invalidWeights"] ?: 0 }

    val lifecycleDiagnostics: SearchTeacherBeliefLifecycleDiagnostics
        get() = SearchTeacherBeliefLifecycleDiagnostics(
            initialConstructionAttempts = initialConstructionAttempts,
            initialConstructionCompletions = initialConstructionCompletions,
            initialConstructionRefusals = initialConstructionRefusals,
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

    fun batch(): BeliefBatch<Weighted<SearchWorld>> {
        val particles = belief.weightedWorlds()
        ArgentumBeliefSupport.requireSupported(
            particles.map { it.value },
            viewer,
            expectedInformation,
            "Search belief read",
        )
        return BeliefBatch(particles = particles, diagnostics = latestDiagnostics)
    }

    fun synchronize(actual: ArgentumSearchWorld, decisionIndex: Int) {
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
            latestDiagnostics = latestDiagnostics.copy(knowledgeDigest = expected.knowledge.knowledgeDigest)
            expectedInformation = expected
            return
        }
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
    ) {
        val expected = actual.informationState(viewer)
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
        val privateOpponentChoice = actor != viewer && privateToActor &&
            parameters.beliefArchitecture != BeliefArchitecture.PRIVILEGED_O_V1
        sequentialUpdateAttempts++
        val update = try {
            when {
                privateOpponentChoice -> belief.advanceUnobserved(
                    actor = actor,
                    opponentPolicy = opponentModel,
                    updateSeed = seed,
                    rejuvenator = rejuvenator,
                )
                parameters.beliefMode == BeliefMode.POLICY_CONDITIONED_V1 && actor != viewer -> belief.advance(
                    actor = actor,
                    observedSignature = choice.signature,
                    conditioningPolicy = opponentModel,
                    updateSeed = seed,
                    rejuvenator = rejuvenator,
                )
                else -> belief.advance(
                    actor = actor,
                    observedSignature = choice.signature,
                    updateSeed = seed,
                    rejuvenator = rejuvenator,
                )
            }
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
        expected: PolicyInformationState,
        purpose: String,
        incrementContinuity: Boolean,
        failures: Map<String, Int> = emptyMap(),
    ) {
        if (incrementContinuity) continuityEpoch++
        reconditionings++
        rebuildAttempts++
        try {
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
        information: PolicyInformationState,
        purpose: String,
    ): BeliefBatch<Weighted<SearchWorld>> {
        val seed = ComponentSeeds.derive(gameId, viewer, purpose)
        val batch = when (parameters.beliefArchitecture) {
            BeliefArchitecture.SNAPSHOT_A_V1,
            BeliefArchitecture.SEQUENTIAL_B_V1 -> ArgentumKnownDeckBeliefWorldSource(
                actual,
                registry,
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
                registry,
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
