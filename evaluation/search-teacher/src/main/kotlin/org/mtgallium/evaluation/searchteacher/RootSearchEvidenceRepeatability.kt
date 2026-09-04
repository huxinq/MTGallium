package org.mtgallium.evaluation.searchteacher

import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayTransition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.InformationSetSearchDiagnostics
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyExpansionOmissionReason
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.searchteacher.SearchTeacherAutomaticSelection
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherRuntimeConfig
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

internal const val ROOT_SEARCH_EVIDENCE_PROTOCOL = "root-search-evidence-repeatability-v1"
internal const val ROOT_SEARCH_EVIDENCE_STANDARD_BUDGET = 64
internal const val ROOT_SEARCH_EVIDENCE_ADJACENT_BUDGET = 256
internal const val ROOT_SEARCH_EVIDENCE_ADJACENT_ROOTS = 6
private const val MAXIMUM_ROOT_EXPANSION = 2_048

@Serializable
internal enum class RootEvidenceDecisionFamily {
    MULLIGAN,
    DECISION_RESPONSE,
    DECLARE_ATTACKERS,
    DECLARE_BLOCKERS,
    STACK_RESPONSE,
    ORDINARY_ACTION,
}

@Serializable
internal data class RootEvidenceStatisticSemantics(
    val statistic: String,
    val sourceMeaning: String,
    val doesNotEstablish: String,
)

@Serializable
internal data class RootEvidenceRoot(
    val rootId: String,
    val panelIndex: Int,
    val sourceGameId: String,
    val decisionIndex: Int,
    val actor: String,
    val regime: FrozenRootRegime,
    val decisionFamily: RootEvidenceDecisionFamily,
    val tactical: Boolean,
    val turnNumber: Int,
    val phase: String,
    val step: String,
    val visibleStackDepth: Int,
    val opponentHandSize: Int,
    val opponentLibrarySize: Int,
    val initialCandidateCount: Int,
    val fullProfileCandidateCount: Int,
    val initialUnexpandedProfileCandidates: Int,
    val initialProfileExhaustive: Boolean,
    val fullProfileExhaustive: Boolean,
    val initialRulesExhaustive: Boolean,
    val initialOmissionReasons: Set<PolicyExpansionOmissionReason>,
    val initialOperationFamilies: Map<SemanticOperationFamily, Int>,
    val informationStateDigest: String,
    val semanticPrefixDigest: String,
    val replayPath: String,
    val replaySha256: String,
    val gameSeed: Long,
    val sourceSearchBaseSeed: Long,
) {
    init {
        require(initialCandidateCount > 0)
        require(fullProfileCandidateCount >= initialCandidateCount)
        require(initialUnexpandedProfileCandidates == fullProfileCandidateCount - initialCandidateCount)
        require(fullProfileExhaustive)
    }
}

@Serializable
internal data class RootEvidencePopulation(
    val schemaVersion: Int = 1,
    val protocol: String = ROOT_SEARCH_EVIDENCE_PROTOCOL,
    val panelVersion: String,
    val panelDigest: String,
    val sourceRunIdentity: String,
    val sourceOuterCommit: String,
    val sourceArgentumCommit: String,
    val currentOuterCommit: String,
    val currentArgentumCommit: String,
    val sourceProvenance: PolicySourceProvenance,
    val deckId: String,
    val deckHash: String,
    val cardPoolHash: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val selectionAlgorithm: String,
    val replayRefusals: List<FrozenRootReplayRefusal>,
    val roots: List<RootEvidenceRoot>,
    val adjacentBudgetRootIds: List<String>,
    val populationIdentity: String,
) {
    init {
        require(schemaVersion == 1 && protocol == ROOT_SEARCH_EVIDENCE_PROTOCOL)
        require(roots.isNotEmpty())
        require(roots.map { it.rootId }.distinct().size == roots.size)
        require(adjacentBudgetRootIds.all { id -> roots.any { it.rootId == id } })
    }
}

@Serializable
internal data class RootEvidenceSearchConfiguration(
    val budget: Int,
    val particles: Int,
    val maxPolicyDecisions: Int,
    val explorationConstant: Double,
    val leaf: LeafEvaluationConfig,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val beliefMode: BeliefMode,
    val beliefArchitecture: BeliefArchitecture,
    val opponentPolicyId: String,
    val rootRolloutPolicyId: String,
    val opponentRolloutPolicyId: String,
    val policyCompressionEnabled: Boolean,
    val treeReuseEnabled: Boolean,
    val wallClockBudgetMillis: Long?,
    val policyIdentity: String,
)

@Serializable
internal data class RootEvidenceCandidateOutcome(
    val signature: String,
    val label: String,
    val operationFamily: SemanticOperationFamily,
    val visits: Int,
    val meanValue: Double,
    /** The current source field; exactly visits divided by total completed root-edge visits. */
    val policyProbability: Double,
)

@Serializable
internal data class RootEvidenceWorkDiagnostics(
    val simulations: Int,
    val particles: Int,
    val nodes: Int,
    val maximumDepth: Int,
    val exhaustiveNodes: Int,
    val nonExhaustiveNodes: Int,
    val wideningEvents: Int,
    val searchWorldSteps: Int,
    val rejectedTransitions: Int,
    val evaluatorCalls: Int,
    val quiescenceOverflows: Int,
    val quiescenceFallbacks: Int,
    val rootRolloutDecisions: Int,
    val opponentRolloutDecisions: Int,
) {
    companion object {
        fun from(value: InformationSetSearchDiagnostics) = RootEvidenceWorkDiagnostics(
            simulations = value.simulations,
            particles = value.particles,
            nodes = value.nodes,
            maximumDepth = value.maximumDepth,
            exhaustiveNodes = value.exhaustiveNodes,
            nonExhaustiveNodes = value.nonExhaustiveNodes,
            wideningEvents = value.wideningEvents,
            searchWorldSteps = value.searchWorldSteps,
            rejectedTransitions = value.rejectedTransitions,
            evaluatorCalls = value.evaluatorCalls,
            quiescenceOverflows = value.quiescenceOverflows,
            quiescenceFallbacks = value.quiescenceFallbacks,
            rootRolloutDecisions = value.rootRolloutDecisions,
            opponentRolloutDecisions = value.opponentRolloutDecisions,
        )
    }
}

@Serializable
internal data class RootEvidenceTrial(
    val rootId: String,
    val panelIndex: Int,
    val budget: Int,
    val repetition: Int,
    val searchIdentity: String,
    val searchSeed: Long,
    val chosenSignature: String,
    val chosenLabel: String,
    val rootValue: Double,
    val candidates: List<RootEvidenceCandidateOutcome>,
    val searchMillis: Double,
    val beliefAcceptedParticles: Int,
    val beliefRejectedParticles: Int,
    val beliefEffectiveSampleSize: Double,
    val beliefEntropy: Double,
    val beliefFailures: Map<String, Int>,
    val work: RootEvidenceWorkDiagnostics,
)

@Serializable
internal data class RootEvidenceTrialFailure(
    val rootId: String,
    val panelIndex: Int,
    val budget: Int,
    val repetition: Int,
    val code: String,
    val message: String,
)

@Serializable
internal data class RootEvidenceSupportCounts(
    val unvisited: Int,
    val oneToThreeVisits: Int,
    val fourToSevenVisits: Int,
    val eightToFifteenVisits: Int,
    val sixteenOrMoreVisits: Int,
) {
    val total: Int
        get() = unvisited + oneToThreeVisits + fourToSevenVisits + eightToFifteenVisits +
            sixteenOrMoreVisits
}

@Serializable
internal data class RootEvidenceRankStability(
    val minimumVisitsInBothRepetitions: Int,
    val comparableRepetitionPairs: Int,
    val meanKendallTauB: Double?,
    val meanAbsoluteCandidateValueDifference: Double?,
)

@Serializable
internal data class RootEvidenceCandidateStability(
    val signature: String,
    val label: String,
    val operationFamily: SemanticOperationFamily,
    val selectedRepetitions: Int,
    val minimumVisits: Int,
    val visitsMean: Double,
    val visitsStandardDeviation: Double,
    val allocationShareMean: Double,
    val allocationShareStandardDeviation: Double,
    val meanValueMean: Double,
    val meanValueStandardDeviation: Double,
    val meanValueRange: Double,
)

@Serializable
internal data class RootEvidenceRootBudgetSummary(
    val rootId: String,
    val panelIndex: Int,
    val budget: Int,
    val completedRepetitions: Int,
    val distinctSelectedActions: Int,
    val modalSignature: String?,
    val modalLabel: String?,
    val modalFraction: Double?,
    val selectionEntropyBits: Double?,
    val pairwiseSelectedActionAgreement: Double?,
    val allocationPairwiseTotalVariationMean: Double?,
    val allocationPairwiseTotalVariationP95: Double?,
    val rootValueMean: Double?,
    val rootValueStandardDeviation: Double?,
    val rootValueRange: Double?,
    val supportCounts: RootEvidenceSupportCounts,
    val rankStability: List<RootEvidenceRankStability>,
    val candidates: List<RootEvidenceCandidateStability>,
)

@Serializable
internal data class RootEvidenceValueStabilityBySupport(
    val minimumVisitsEveryRepetition: Int,
    val candidates: Int,
    val medianCandidateMeanValueStandardDeviation: Double?,
    val p95CandidateMeanValueStandardDeviation: Double?,
)

@Serializable
internal data class RootEvidenceAggregateRankStability(
    val minimumVisitsInBothRepetitions: Int,
    val rootSummaries: Int,
    val comparableRepetitionPairs: Int,
    val meanRootKendallTauB: Double?,
    val meanRootAbsoluteCandidateValueDifference: Double?,
)

@Serializable
internal data class RootEvidenceBudgetSummary(
    val budget: Int,
    val roots: Int,
    val completedTrials: Int,
    val unanimousRoots: Int,
    val meanModalFraction: Double?,
    val meanPairwiseSelectedActionAgreement: Double?,
    val meanAllocationPairwiseTotalVariation: Double?,
    val medianRootValueStandardDeviation: Double?,
    val supportCounts: RootEvidenceSupportCounts,
    val rankStability: List<RootEvidenceAggregateRankStability>,
    val candidateValueStabilityBySupport: List<RootEvidenceValueStabilityBySupport>,
)

@Serializable
internal data class RootEvidenceBudgetSensitivity(
    val standardBudget: Int,
    val adjacentBudget: Int,
    val roots: Int,
    val pairedTrials: Int,
    val sameSelectedActionPairs: Int,
    val selectedActionAgreement: Double?,
    val meanAllocationTotalVariation: Double?,
    val meanAbsoluteRootValueChange: Double?,
    val meanSignedRootValueChange: Double?,
    val rankStability: List<RootEvidenceRankStability>,
)

@Serializable
internal data class RootSearchEvidenceRepeatabilityReport(
    val schemaVersion: Int = 1,
    val protocol: String = ROOT_SEARCH_EVIDENCE_PROTOCOL,
    val generatedAtUtc: String,
    val sourceProvenance: PolicySourceProvenance,
    val population: RootEvidencePopulation,
    val configurations: List<RootEvidenceSearchConfiguration>,
    val standardRepetitions: Int,
    val adjacentBudgetRepetitions: Int,
    val workerThreads: Int,
    val statisticSemantics: List<RootEvidenceStatisticSemantics>,
    val trials: List<RootEvidenceTrial>,
    val failures: List<RootEvidenceTrialFailure>,
    val typedFailureCounts: Map<String, Int>,
    val rootSummaries: List<RootEvidenceRootBudgetSummary>,
    val budgetSummaries: List<RootEvidenceBudgetSummary>,
    val budgetSensitivity: RootEvidenceBudgetSensitivity,
    val completed: Boolean,
    val artifactIdentity: String,
    val limitations: List<String>,
)

internal class RootEvidencePopulationBuilder(
    private val root: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val manifest: DeckManifest,
    private val panel: FreshWorldFrozenRootPanel,
    private val sourceProvenance: PolicySourceProvenance,
) {
    fun build(rootLimit: Int): RootEvidencePopulation {
        require(rootLimit in 1..panel.roots.size)
        val described = panel.roots.take(rootLimit).map(::describe)
        val adjacent = adjacentSubset(described, minOf(ROOT_SEARCH_EVIDENCE_ADJACENT_ROOTS, described.size))
        val identity = PolicyJson.sha256(buildString {
            append(ROOT_SEARCH_EVIDENCE_PROTOCOL).append(':').append(panel.panelDigest).append('\n')
            described.forEach { candidate ->
                append(candidate.rootId).append(':')
                    .append(candidate.informationStateDigest).append(':')
                    .append(candidate.initialCandidateCount).append(':')
                    .append(candidate.fullProfileCandidateCount).append('\n')
            }
            append("adjacent:").append(adjacent.joinToString(",", transform = RootEvidenceRoot::rootId))
        })
        return RootEvidencePopulation(
            panelVersion = panel.panelVersion,
            panelDigest = panel.panelDigest,
            sourceRunIdentity = panel.sourceRunIdentity,
            sourceOuterCommit = panel.sourceOuterCommit,
            sourceArgentumCommit = panel.sourceArgentumCommit,
            currentOuterCommit = panel.currentOuterCommit,
            currentArgentumCommit = panel.currentArgentumCommit,
            sourceProvenance = sourceProvenance,
            deckId = panel.deckId,
            deckHash = panel.deckHash,
            cardPoolHash = panel.cardPoolHash,
            actionSpaceProfile = panel.actionSpaceProfile,
            selectionAlgorithm = panel.selectionAlgorithm +
                "; the repeatability population retains the panel order and takes its first $rootLimit roots; " +
                "the adjacent-budget subset takes the largest-branching unused root from each decision family, " +
                "then fills by descending initial candidate count with root-id tie-break",
            replayRefusals = panel.replayRefusals,
            roots = described,
            adjacentBudgetRootIds = adjacent.map(RootEvidenceRoot::rootId),
            populationIdentity = identity,
        )
    }

    private fun describe(frozen: FreshWorldFrozenRoot): RootEvidenceRoot {
        val prefix = semanticPrefix(frozen)
        val world = reconstruct(frozen, prefix)
        val actor = requireNotNull(world.actorToAct())
        require(actor == frozen.perspectivePlayerId)
        val information = world.informationState(actor)
        require(information.informationStateDigest == frozen.informationStateDigest)
        val initial = world.expandChoices()
        require(SearchTeacherAutomaticSelection.classify(initial) == null)
        val full = if (initial.isProfileExhaustive) initial else world.expandChoices(MAXIMUM_ROOT_EXPANSION)
        require(full.isProfileExhaustive) {
            "Root ${frozen.id} is not profile-exhaustive at $MAXIMUM_ROOT_EXPANSION candidates"
        }
        val observation = information.observation
        val family = decisionFamily(initial.candidates, observation.stack.isNotEmpty())
        return RootEvidenceRoot(
            rootId = frozen.id,
            panelIndex = frozen.panelIndex,
            sourceGameId = frozen.gameId,
            decisionIndex = frozen.decisionIndex,
            actor = actor,
            regime = frozen.regime,
            decisionFamily = family,
            tactical = family in setOf(
                RootEvidenceDecisionFamily.DECISION_RESPONSE,
                RootEvidenceDecisionFamily.DECLARE_ATTACKERS,
                RootEvidenceDecisionFamily.DECLARE_BLOCKERS,
                RootEvidenceDecisionFamily.STACK_RESPONSE,
            ),
            turnNumber = frozen.turnNumber,
            phase = frozen.phase,
            step = frozen.step,
            visibleStackDepth = observation.stack.size,
            opponentHandSize = frozen.opponentHandSize,
            opponentLibrarySize = frozen.opponentLibrarySize,
            initialCandidateCount = initial.candidates.size,
            fullProfileCandidateCount = full.candidates.size,
            initialUnexpandedProfileCandidates = full.candidates.size - initial.candidates.size,
            initialProfileExhaustive = initial.isProfileExhaustive,
            fullProfileExhaustive = full.isProfileExhaustive,
            initialRulesExhaustive = initial.isExhaustive,
            initialOmissionReasons = initial.omissionReasons,
            initialOperationFamilies = initial.candidates.groupingBy { it.operationFamily }.eachCount(),
            informationStateDigest = information.informationStateDigest,
            semanticPrefixDigest = frozen.semanticPrefixDigest,
            replayPath = frozen.replayPath,
            replaySha256 = frozen.replaySha256,
            gameSeed = frozen.gameSeed,
            sourceSearchBaseSeed = frozen.searchBaseSeed,
        )
    }

    private fun adjacentSubset(candidates: List<RootEvidenceRoot>, count: Int): List<RootEvidenceRoot> {
        val selected = mutableListOf<RootEvidenceRoot>()
        RootEvidenceDecisionFamily.entries.forEach { family ->
            candidates.asSequence().filter { it.decisionFamily == family && it !in selected }
                .sortedWith(compareByDescending<RootEvidenceRoot> { it.initialCandidateCount }.thenBy { it.rootId })
                .firstOrNull()?.let(selected::add)
        }
        candidates.asSequence().filter { it !in selected }
            .sortedWith(compareByDescending<RootEvidenceRoot> { it.initialCandidateCount }.thenBy { it.rootId })
            .take(count - selected.size.coerceAtMost(count))
            .forEach(selected::add)
        return selected.take(count)
    }

    private fun reconstruct(frozen: FreshWorldFrozenRoot, prefix: List<SemanticChoice>): ArgentumSearchWorld =
        reconstructReplayReviewWorld(
            registry = registry,
            manifest = manifest,
            gameId = frozen.gameId,
            gameSeed = frozen.gameSeed,
            searchBaseSeed = frozen.searchBaseSeed,
            startingPlayerIndex = 0,
            profile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            semanticPrefix = prefix,
        )

    private fun semanticPrefix(frozen: FreshWorldFrozenRoot): List<SemanticChoice> =
        readFrozenSemanticPrefix(root, frozen)
}

internal class RootSearchEvidenceRepeatabilityExperiment(
    private val root: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val manifest: DeckManifest,
    private val panel: FreshWorldFrozenRootPanel,
    private val population: RootEvidencePopulation,
) {
    private val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)
    private val opponentPolicy = defaultMonoRedOpponentPolicy()

    fun run(
        repetitions: Int,
        workerThreads: Int,
        generatedAtUtc: String,
        progress: (String) -> Unit = {},
    ): RootSearchEvidenceRepeatabilityReport {
        require(repetitions > 1 && workerThreads > 0)
        val adjacentRepetitions = minOf(4, repetitions)
        val adjacentIds = population.adjacentBudgetRootIds.toSet()
        val specifications = buildList {
            population.roots.forEach { root ->
                repeat(repetitions) { repetition ->
                    add(TrialSpecification(root, ROOT_SEARCH_EVIDENCE_STANDARD_BUDGET, repetition))
                }
            }
            population.roots.filter { it.rootId in adjacentIds }.forEach { root ->
                repeat(adjacentRepetitions) { repetition ->
                    add(TrialSpecification(root, ROOT_SEARCH_EVIDENCE_ADJACENT_BUDGET, repetition))
                }
            }
        }
        val trials = mutableListOf<RootEvidenceTrial>()
        val failures = mutableListOf<RootEvidenceTrialFailure>()
        var finished = 0
        specifications.chunked(workerThreads).forEach { batch ->
            val results = parallelMapOrdered(batch.size, minOf(workerThreads, batch.size)) { index ->
                val spec = batch[index]
                runCatching { runTrial(spec) }.fold(
                    onSuccess = { TrialResult.Success(it) },
                    onFailure = { failure ->
                        TrialResult.Failure(
                            RootEvidenceTrialFailure(
                                rootId = spec.root.rootId,
                                panelIndex = spec.root.panelIndex,
                                budget = spec.budget,
                                repetition = spec.repetition,
                                code = failure::class.simpleName ?: "Throwable",
                                message = failure.message?.take(500) ?: "no message",
                            )
                        )
                    },
                )
            }
            results.forEach { result ->
                finished++
                when (result) {
                    is TrialResult.Success -> trials += result.trial
                    is TrialResult.Failure -> failures += result.failure
                }
                progress("root-evidence trial $finished/${specifications.size}")
            }
        }
        return summarize(
            repetitions = repetitions,
            adjacentRepetitions = adjacentRepetitions,
            workerThreads = workerThreads,
            generatedAtUtc = generatedAtUtc,
            scheduledTrials = specifications.size,
            trials = trials.sortedWith(
                compareBy<RootEvidenceTrial> { it.panelIndex }.thenBy { it.budget }.thenBy { it.repetition }
            ),
            failures = failures.sortedWith(
                compareBy<RootEvidenceTrialFailure> { it.panelIndex }
                    .thenBy { it.budget }.thenBy { it.repetition }
            ),
        )
    }

    private fun runTrial(specification: TrialSpecification): RootEvidenceTrial {
        val rootDescriptor = specification.root
        val frozen = panel.roots.single { it.id == rootDescriptor.rootId }
        val prefix = readFrozenSemanticPrefix(root, frozen)
        val actual = reconstructReplayReviewWorld(
            registry = registry,
            manifest = manifest,
            gameId = frozen.gameId,
            gameSeed = frozen.gameSeed,
            searchBaseSeed = frozen.searchBaseSeed,
            startingPlayerIndex = 0,
            profile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            semanticPrefix = emptyList(),
        )
        val parameters = parameters(specification.budget)
        val searchIdentity = "$ROOT_SEARCH_EVIDENCE_PROTOCOL:${rootDescriptor.rootId}:${specification.repetition}"
        val session = SearchTeacherPolicySession(
            root = actual,
            viewer = rootDescriptor.actor,
            registry = registry,
            knownDecks = knownDecks,
            parameters = parameters,
            opponentPolicy = opponentPolicy,
            gameId = searchIdentity,
        )
        prefix.forEachIndexed { decisionIndex, historical ->
            val actor = requireNotNull(actual.actorToAct())
            val exact = actual.expandChoices().candidates.singleOrNull { it.signature == historical.signature }
                ?: error("Root ${rootDescriptor.rootId} prefix choice $decisionIndex is not currently legal")
            val step = actual.step(exact)
            require(step.accepted) { "Root ${rootDescriptor.rootId} prefix choice $decisionIndex was rejected" }
            session.observeAccepted(actual, actor, exact, decisionIndex, step.privateToActor)
        }
        val actor = requireNotNull(actual.actorToAct())
        require(actor == rootDescriptor.actor)
        require(actual.informationState(actor).informationStateDigest == rootDescriptor.informationStateDigest)
        val initial = actual.expandChoices()
        require(SearchTeacherAutomaticSelection.classify(initial) == null)
        val searchSeed = ComponentSeeds.derive(
            ROOT_SEARCH_EVIDENCE_PROTOCOL,
            rootDescriptor.rootId,
            specification.repetition,
            "paired-search",
        )
        val started = System.nanoTime()
        val selection = session.select(actual, actor, searchSeed)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000.0
        val search = requireNotNull(selection.search) { "Repeatability root unexpectedly became automatic" }
        require(search.diagnostics.simulations == specification.budget)
        require(search.diagnostics.rejectedTransitions == 0)
        require(search.candidates.sumOf { it.visits } == specification.budget)
        require(search.candidates.map { it.choice.signature }.toSet() ==
            initial.candidates.map { it.signature }.toSet())
        val belief = session.latestBeliefDiagnostics
        return RootEvidenceTrial(
            rootId = rootDescriptor.rootId,
            panelIndex = rootDescriptor.panelIndex,
            budget = specification.budget,
            repetition = specification.repetition,
            searchIdentity = searchIdentity,
            searchSeed = searchSeed,
            chosenSignature = search.chosen.signature,
            chosenLabel = search.chosen.display.label,
            rootValue = search.rootValue,
            candidates = search.candidates.map { candidate ->
                RootEvidenceCandidateOutcome(
                    signature = candidate.choice.signature,
                    label = candidate.choice.display.label,
                    operationFamily = candidate.choice.operationFamily,
                    visits = candidate.visits,
                    meanValue = candidate.meanValue,
                    policyProbability = candidate.policyProbability,
                )
            },
            searchMillis = elapsedMillis,
            beliefAcceptedParticles = belief.acceptedParticles,
            beliefRejectedParticles = belief.rejectedParticles,
            beliefEffectiveSampleSize = belief.effectiveSampleSizeAfter,
            beliefEntropy = belief.entropy,
            beliefFailures = belief.failures,
            work = RootEvidenceWorkDiagnostics.from(search.diagnostics),
        )
    }

    private fun parameters(budget: Int): SearchTeacherPolicyParameters = SearchTeacherRuntimeConfig(
        particles = 8,
        simulations = budget,
        maxPolicyDecisions = 32,
        explorationConstant = 1.4,
        leaf = LeafEvaluationConfig(
            LeafStateSource.BOUNDED_ROLLOUT,
            LeafEvaluator.MTGALLIUM_VISIBLE_V2,
        ),
        actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
        beliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
        beliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
        baseSeed = 20260825L,
    ).policyParameters()

    private fun configuration(budget: Int): RootEvidenceSearchConfiguration {
        val parameters = parameters(budget)
        return RootEvidenceSearchConfiguration(
            budget = budget,
            particles = parameters.particles,
            maxPolicyDecisions = parameters.maxPolicyDecisions,
            explorationConstant = parameters.explorationConstant,
            leaf = parameters.leaf,
            actionSpaceProfile = parameters.actionSpaceProfile,
            beliefMode = parameters.beliefMode,
            beliefArchitecture = parameters.beliefArchitecture,
            opponentPolicyId = opponentPolicy.id,
            rootRolloutPolicyId = SearchTeacherSearchFactory.rootRolloutPolicy().id,
            opponentRolloutPolicyId = SearchTeacherSearchFactory.opponentRolloutPolicy().id,
            policyCompressionEnabled = parameters.policyCompression.enabled,
            treeReuseEnabled = parameters.searchReuse.enabled,
            wallClockBudgetMillis = parameters.wallClockBudgetMillis,
            policyIdentity = parameters.policyIdentity(knownDecks, opponentPolicy),
        )
    }

    private fun summarize(
        repetitions: Int,
        adjacentRepetitions: Int,
        workerThreads: Int,
        generatedAtUtc: String,
        scheduledTrials: Int,
        trials: List<RootEvidenceTrial>,
        failures: List<RootEvidenceTrialFailure>,
    ): RootSearchEvidenceRepeatabilityReport {
        val rootSummaries = buildList {
            population.roots.forEach { root ->
                listOf(ROOT_SEARCH_EVIDENCE_STANDARD_BUDGET, ROOT_SEARCH_EVIDENCE_ADJACENT_BUDGET).forEach { budget ->
                    val matching = trials.filter { it.rootId == root.rootId && it.budget == budget }
                    if (matching.isNotEmpty()) add(summarizeRoot(root, budget, matching))
                }
            }
        }
        val budgetSummaries = listOf(
            summarizeBudget(ROOT_SEARCH_EVIDENCE_STANDARD_BUDGET, rootSummaries),
            summarizeBudget(ROOT_SEARCH_EVIDENCE_ADJACENT_BUDGET, rootSummaries),
        )
        val sensitivity = summarizeBudgetSensitivity(trials)
        val identity = PolicyJson.sha256(buildString {
            append(ROOT_SEARCH_EVIDENCE_PROTOCOL).append(':').append(population.populationIdentity).append('\n')
            trials.forEach { trial ->
                append(trial.rootId).append(':').append(trial.budget).append(':').append(trial.repetition)
                    .append(':').append(trial.chosenSignature).append(':').append(trial.rootValue).append('\n')
                trial.candidates.forEach { candidate ->
                    append(candidate.signature).append(':').append(candidate.visits)
                        .append(':').append(candidate.meanValue).append('\n')
                }
            }
            failures.forEach { failure ->
                append("failure:").append(failure.rootId).append(':').append(failure.budget)
                    .append(':').append(failure.repetition).append(':').append(failure.code).append('\n')
            }
        })
        return RootSearchEvidenceRepeatabilityReport(
            generatedAtUtc = generatedAtUtc,
            sourceProvenance = population.sourceProvenance,
            population = population,
            configurations = listOf(
                configuration(ROOT_SEARCH_EVIDENCE_STANDARD_BUDGET),
                configuration(ROOT_SEARCH_EVIDENCE_ADJACENT_BUDGET),
            ),
            standardRepetitions = repetitions,
            adjacentBudgetRepetitions = adjacentRepetitions,
            workerThreads = workerThreads,
            statisticSemantics = rootStatisticSemantics(),
            trials = trials,
            failures = failures,
            typedFailureCounts = failures.groupingBy { it.code }.eachCount().toSortedMap(),
            rootSummaries = rootSummaries,
            budgetSummaries = budgetSummaries,
            budgetSensitivity = sensitivity,
            completed = trials.size + failures.size == scheduledTrials && failures.isEmpty(),
            artifactIdentity = identity,
            limitations = listOf(
                "Repeatability measures Search Teacher behavior, not strategic correctness.",
                "The source-bound panel contains at most 16 initially admitted candidates per root, so it does not measure high-branch progressive-widening behavior.",
                "The production profile deliberately suppresses standalone mana actions; those actions are outside every recorded candidate table.",
                "Mean-value rankings are derived only for candidates meeting the stated visit floor in both repetitions; the implementation records no native rank target.",
                "The adjacent 256-simulation regime is a sensitivity check, not an oracle or ground truth.",
                "Search identities independently vary sequential belief construction, belief updates, hidden-world draws, future chance, opponent samples, and tree-policy tie-breaking together, as production identities do.",
            ),
        )
    }

    private data class TrialSpecification(
        val root: RootEvidenceRoot,
        val budget: Int,
        val repetition: Int,
    )

    private sealed interface TrialResult {
        data class Success(val trial: RootEvidenceTrial) : TrialResult
        data class Failure(val failure: RootEvidenceTrialFailure) : TrialResult
    }
}

internal fun summarizeRootEvidenceTrials(
    root: RootEvidenceRoot,
    budget: Int,
    trials: List<RootEvidenceTrial>,
): RootEvidenceRootBudgetSummary = summarizeRoot(root, budget, trials)

private fun summarizeRoot(
    root: RootEvidenceRoot,
    budget: Int,
    trials: List<RootEvidenceTrial>,
): RootEvidenceRootBudgetSummary {
    require(trials.all { it.rootId == root.rootId && it.budget == budget })
    val counts = trials.groupingBy { it.chosenSignature }.eachCount()
    val modal = counts.entries.maxWithOrNull(
        compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }
    )
    val pairs = unorderedPairs(trials)
    val allocationDistances = pairs.map { (first, second) -> allocationTotalVariation(first, second) }
    val values = trials.map { it.rootValue }
    val candidateStability = candidateStability(trials)
    return RootEvidenceRootBudgetSummary(
        rootId = root.rootId,
        panelIndex = root.panelIndex,
        budget = budget,
        completedRepetitions = trials.size,
        distinctSelectedActions = counts.size,
        modalSignature = modal?.key,
        modalLabel = modal?.key?.let { signature ->
            trials.asSequence().flatMap { it.candidates.asSequence() }
                .firstOrNull { it.signature == signature }?.label
        },
        modalFraction = modal?.value?.toDouble()?.div(trials.size),
        selectionEntropyBits = entropyBits(counts.values, trials.size),
        pairwiseSelectedActionAgreement = if (pairs.isEmpty()) null else {
            pairs.count { (first, second) -> first.chosenSignature == second.chosenSignature }.toDouble() / pairs.size
        },
        allocationPairwiseTotalVariationMean = allocationDistances.averageOrNull(),
        allocationPairwiseTotalVariationP95 = allocationDistances.p95OrNull(),
        rootValueMean = values.averageOrNull(),
        rootValueStandardDeviation = values.sampleStandardDeviation(),
        rootValueRange = values.rangeOrNull(),
        supportCounts = supportCounts(trials.flatMap { it.candidates }),
        rankStability = listOf(1, 4, 8).map { threshold -> rankStability(pairs, threshold) },
        candidates = candidateStability,
    )
}

private fun summarizeBudget(
    budget: Int,
    summaries: List<RootEvidenceRootBudgetSummary>,
): RootEvidenceBudgetSummary {
    val roots = summaries.filter { it.budget == budget }
    val candidateStability = roots.flatMap { it.candidates }
    val support = roots.map { it.supportCounts }.fold(emptySupportCounts(), ::plus)
    return RootEvidenceBudgetSummary(
        budget = budget,
        roots = roots.size,
        completedTrials = roots.sumOf { it.completedRepetitions },
        unanimousRoots = roots.count { it.distinctSelectedActions == 1 },
        meanModalFraction = roots.mapNotNull { it.modalFraction }.averageOrNull(),
        meanPairwiseSelectedActionAgreement = roots.mapNotNull { it.pairwiseSelectedActionAgreement }.averageOrNull(),
        meanAllocationPairwiseTotalVariation = roots.mapNotNull {
            it.allocationPairwiseTotalVariationMean
        }.averageOrNull(),
        medianRootValueStandardDeviation = roots.mapNotNull { it.rootValueStandardDeviation }.medianOrNull(),
        supportCounts = support,
        rankStability = listOf(1, 4, 8).map { threshold ->
            val comparable = roots.mapNotNull { root ->
                root.rankStability.single { it.minimumVisitsInBothRepetitions == threshold }
                    .takeIf { it.comparableRepetitionPairs > 0 }
            }
            RootEvidenceAggregateRankStability(
                minimumVisitsInBothRepetitions = threshold,
                rootSummaries = comparable.size,
                comparableRepetitionPairs = comparable.sumOf { it.comparableRepetitionPairs },
                meanRootKendallTauB = comparable.mapNotNull { it.meanKendallTauB }.averageOrNull(),
                meanRootAbsoluteCandidateValueDifference = comparable
                    .mapNotNull { it.meanAbsoluteCandidateValueDifference }.averageOrNull(),
            )
        },
        candidateValueStabilityBySupport = listOf(1, 4, 8).map { threshold ->
            val eligible = candidateStability.filter { it.minimumVisits >= threshold }
                .map { it.meanValueStandardDeviation }
            RootEvidenceValueStabilityBySupport(
                minimumVisitsEveryRepetition = threshold,
                candidates = eligible.size,
                medianCandidateMeanValueStandardDeviation = eligible.medianOrNull(),
                p95CandidateMeanValueStandardDeviation = eligible.p95OrNull(),
            )
        },
    )
}

private fun summarizeBudgetSensitivity(trials: List<RootEvidenceTrial>): RootEvidenceBudgetSensitivity {
    val byKey = trials.associateBy { Triple(it.rootId, it.repetition, it.budget) }
    val pairs = byKey.keys.asSequence()
        .filter { it.third == ROOT_SEARCH_EVIDENCE_STANDARD_BUDGET }
        .mapNotNull { key ->
            val standard = byKey[key] ?: return@mapNotNull null
            val adjacent = byKey[Triple(key.first, key.second, ROOT_SEARCH_EVIDENCE_ADJACENT_BUDGET)]
                ?: return@mapNotNull null
            standard to adjacent
        }.toList()
    return RootEvidenceBudgetSensitivity(
        standardBudget = ROOT_SEARCH_EVIDENCE_STANDARD_BUDGET,
        adjacentBudget = ROOT_SEARCH_EVIDENCE_ADJACENT_BUDGET,
        roots = pairs.map { it.first.rootId }.distinct().size,
        pairedTrials = pairs.size,
        sameSelectedActionPairs = pairs.count { (standard, adjacent) ->
            standard.chosenSignature == adjacent.chosenSignature
        },
        selectedActionAgreement = if (pairs.isEmpty()) null else {
            pairs.count { (standard, adjacent) -> standard.chosenSignature == adjacent.chosenSignature }
                .toDouble() / pairs.size
        },
        meanAllocationTotalVariation = pairs.map { allocationTotalVariation(it.first, it.second) }.averageOrNull(),
        meanAbsoluteRootValueChange = pairs.map { kotlin.math.abs(it.second.rootValue - it.first.rootValue) }
            .averageOrNull(),
        meanSignedRootValueChange = pairs.map { it.second.rootValue - it.first.rootValue }.averageOrNull(),
        rankStability = listOf(1, 4, 8).map { threshold -> rankStability(pairs, threshold) },
    )
}

private fun candidateStability(trials: List<RootEvidenceTrial>): List<RootEvidenceCandidateStability> {
    val candidates = trials.flatMap { it.candidates }.associateBy { it.signature }
    return candidates.values.sortedBy { it.signature }.map { candidate ->
        val outcomes = trials.map { trial ->
            trial.candidates.singleOrNull { it.signature == candidate.signature }
                ?: RootEvidenceCandidateOutcome(
                    signature = candidate.signature,
                    label = candidate.label,
                    operationFamily = candidate.operationFamily,
                    visits = 0,
                    meanValue = 0.0,
                    policyProbability = 0.0,
                )
        }
        val visits = outcomes.map { it.visits.toDouble() }
        val allocations = outcomes.map { it.policyProbability }
        val values = outcomes.filter { it.visits > 0 }.map { it.meanValue }
        RootEvidenceCandidateStability(
            signature = candidate.signature,
            label = candidate.label,
            operationFamily = candidate.operationFamily,
            selectedRepetitions = trials.count { it.chosenSignature == candidate.signature },
            minimumVisits = outcomes.minOf { it.visits },
            visitsMean = visits.average(),
            visitsStandardDeviation = visits.sampleStandardDeviation() ?: 0.0,
            allocationShareMean = allocations.average(),
            allocationShareStandardDeviation = allocations.sampleStandardDeviation() ?: 0.0,
            meanValueMean = values.averageOrNull() ?: 0.0,
            meanValueStandardDeviation = values.sampleStandardDeviation() ?: 0.0,
            meanValueRange = values.rangeOrNull() ?: 0.0,
        )
    }
}

private fun rankStability(
    pairs: List<Pair<RootEvidenceTrial, RootEvidenceTrial>>,
    minimumVisits: Int,
): RootEvidenceRankStability {
    val comparisons = pairs.mapNotNull { (first, second) ->
        val firstBySignature = first.candidates.associateBy { it.signature }
        val comparable = second.candidates.mapNotNull { candidate ->
            val other = firstBySignature[candidate.signature] ?: return@mapNotNull null
            if (candidate.visits < minimumVisits || other.visits < minimumVisits) return@mapNotNull null
            Triple(candidate.signature, other.meanValue, candidate.meanValue)
        }
        if (comparable.size < 2) return@mapNotNull null
        val tau = kendallTauB(comparable.map { it.second }, comparable.map { it.third })
        val mae = comparable.map { kotlin.math.abs(it.second - it.third) }.average()
        tau to mae
    }
    return RootEvidenceRankStability(
        minimumVisitsInBothRepetitions = minimumVisits,
        comparableRepetitionPairs = comparisons.size,
        meanKendallTauB = comparisons.mapNotNull { it.first }.averageOrNull(),
        meanAbsoluteCandidateValueDifference = comparisons.map { it.second }.averageOrNull(),
    )
}

private fun kendallTauB(first: List<Double>, second: List<Double>): Double? {
    require(first.size == second.size)
    var concordant = 0
    var discordant = 0
    var tiesFirst = 0
    var tiesSecond = 0
    for (left in first.indices) {
        for (right in left + 1 until first.size) {
            val firstSign = first[left].compareTo(first[right])
            val secondSign = second[left].compareTo(second[right])
            when {
                firstSign == 0 && secondSign == 0 -> Unit
                firstSign == 0 -> tiesFirst++
                secondSign == 0 -> tiesSecond++
                firstSign == secondSign -> concordant++
                else -> discordant++
            }
        }
    }
    val denominator = sqrt(
        (concordant + discordant + tiesFirst).toDouble() *
            (concordant + discordant + tiesSecond).toDouble()
    )
    return if (denominator == 0.0) null else (concordant - discordant) / denominator
}

private fun allocationTotalVariation(first: RootEvidenceTrial, second: RootEvidenceTrial): Double {
    val firstAllocations = first.candidates.associate { it.signature to it.policyProbability }
    val secondAllocations = second.candidates.associate { it.signature to it.policyProbability }
    return 0.5 * (firstAllocations.keys + secondAllocations.keys).sumOf { signature ->
        kotlin.math.abs(firstAllocations.getOrDefault(signature, 0.0) -
            secondAllocations.getOrDefault(signature, 0.0))
    }
}

private fun supportCounts(candidates: List<RootEvidenceCandidateOutcome>): RootEvidenceSupportCounts =
    RootEvidenceSupportCounts(
        unvisited = candidates.count { it.visits == 0 },
        oneToThreeVisits = candidates.count { it.visits in 1..3 },
        fourToSevenVisits = candidates.count { it.visits in 4..7 },
        eightToFifteenVisits = candidates.count { it.visits in 8..15 },
        sixteenOrMoreVisits = candidates.count { it.visits >= 16 },
    )

private fun emptySupportCounts() = RootEvidenceSupportCounts(0, 0, 0, 0, 0)

private fun plus(
    first: RootEvidenceSupportCounts,
    second: RootEvidenceSupportCounts,
) = RootEvidenceSupportCounts(
    unvisited = first.unvisited + second.unvisited,
    oneToThreeVisits = first.oneToThreeVisits + second.oneToThreeVisits,
    fourToSevenVisits = first.fourToSevenVisits + second.fourToSevenVisits,
    eightToFifteenVisits = first.eightToFifteenVisits + second.eightToFifteenVisits,
    sixteenOrMoreVisits = first.sixteenOrMoreVisits + second.sixteenOrMoreVisits,
)

private fun decisionFamily(
    candidates: List<SemanticChoice>,
    visibleStack: Boolean,
): RootEvidenceDecisionFamily = when {
    candidates.any { it.operationFamily == SemanticOperationFamily.MULLIGAN } ->
        RootEvidenceDecisionFamily.MULLIGAN
    candidates.any { it.operationFamily == SemanticOperationFamily.DECISION_RESPONSE } ->
        RootEvidenceDecisionFamily.DECISION_RESPONSE
    candidates.any { it.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS } ->
        RootEvidenceDecisionFamily.DECLARE_ATTACKERS
    candidates.any { it.operationFamily == SemanticOperationFamily.DECLARE_BLOCKERS } ->
        RootEvidenceDecisionFamily.DECLARE_BLOCKERS
    visibleStack -> RootEvidenceDecisionFamily.STACK_RESPONSE
    else -> RootEvidenceDecisionFamily.ORDINARY_ACTION
}

private fun readFrozenSemanticPrefix(
    root: Path,
    frozen: FreshWorldFrozenRoot,
): List<SemanticChoice> {
    val path = root.resolve(frozen.replayPath)
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path))
    require(sha256File(path) == frozen.replaySha256)
    val choices = readCanonicalReplay(path).filterIsInstance<CanonicalReplayTransition>().mapNotNull { transition ->
        val decision = (transition.extensions["mtgallium.decisionIndex"] as? JsonPrimitive)
            ?.content?.toInt() ?: return@mapNotNull null
        val encoded = transition.extensions["mtgallium.semanticChoice"] ?: return@mapNotNull null
        decision to PolicyJson.format.decodeFromJsonElement(SemanticChoice.serializer(), encoded)
    }
    require(choices.map { it.first } == choices.indices.toList())
    val prefix = choices.take(frozen.decisionIndex).map { it.second }
    require(PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }) == frozen.semanticPrefixDigest)
    return prefix
}

private fun rootStatisticSemantics(): List<RootEvidenceStatisticSemantics> = listOf(
    RootEvidenceStatisticSemantics(
        "candidate",
        "A root statistic is attached to one semantic-choice edge of the shared node keyed by the acting player's information-state digest and actor. Root conformance requires every sampled world to expose the same proposal identity and semantic signatures.",
        "It is not attached to a raw engine action id, display label, particular hidden world, or durable legality claim outside the reconstructed root.",
    ),
    RootEvidenceStatisticSemantics(
        "visits",
        "One visit is one completed simulation that traversed the root edge and backed one finite root-player value through it. With disabled reuse and fixed work, root visits sum to completed simulations.",
        "A visit is not a game, independent strategic trial, posterior sample, correctness vote, or observed terminal outcome.",
    ),
    RootEvidenceStatisticSemantics(
        "candidate meanValue",
        "The arithmetic mean of backed root-player values on that edge. Under the current bounded-rollout-visible-v2 regime a backed value may be terminal payoff or the clipped visible-v2 heuristic at the bounded rollout horizon.",
        "It is not an observed game result, authoritative hidden truth, calibrated win probability, or value independent of evaluator/settlement/search configuration.",
    ),
    RootEvidenceStatisticSemantics(
        "rootValue",
        "The visit-weighted mean of all visited root-edge mean values, equivalently the mean backed value across completed root simulations in the current no-reuse fixed-work regime.",
        "It is not the selected candidate's value, an observed outcome, or a larger-budget oracle.",
    ),
    RootEvidenceStatisticSemantics(
        "policyProbability",
        "Exactly candidate visits divided by total completed root-edge visits; it is the normalized allocation of this finite search algorithm's work.",
        "The field name does not make it a probability that the action is correct, optimal, or should be played. Treating it as a soft policy is an additional interpretation.",
    ),
    RootEvidenceStatisticSemantics(
        "chosen",
        "The deterministic maximum by visits, then meanValue, then reverse semantic signature tie-break. The live teacher submits that semantic choice and only accepted transitions become behavioral-cloning labels.",
        "The winner is not value-first and is not established as strategically optimal.",
    ),
    RootEvidenceStatisticSemantics(
        "unvisited candidate",
        "A recorded root edge with zero completed backed simulations; its serialized meanValue is the implementation sentinel 0.0 and is not an estimate. UCT visits every admitted edge before revisiting one.",
        "It is different from a profile-suppressed, expansion-rejected, or not-yet-expanded action, none of which appears as a candidate row.",
    ),
    RootEvidenceStatisticSemantics(
        "nonordinary paths",
        "Terminal transitions back up +1/0/-1. Bounded nonterminal horizons use the configured heuristic. A rejected simulated transition throws and prevents any search result; it is not backed up. A wall-clock configuration, when present, returns only after completed simulations and records the smaller completed count.",
        "Software rejection, stop, timeout, unsupported representation, or partial execution is never converted into a strategic value.",
    ),
)

internal fun renderRootSearchEvidenceRepeatability(report: RootSearchEvidenceRepeatabilityReport): String = buildString {
    val standard = report.budgetSummaries.single { it.budget == ROOT_SEARCH_EVIDENCE_STANDARD_BUDGET }
    val adjacent = report.budgetSummaries.single { it.budget == ROOT_SEARCH_EVIDENCE_ADJACENT_BUDGET }
    appendLine("# Search Teacher root-search evidence repeatability")
    appendLine()
    appendLine(
        "Completed ${report.trials.size} searches across ${report.population.roots.size} source-bound roots; " +
            "technical failures=${report.failures.size}. Artifact identity: `${report.artifactIdentity}`."
    )
    appendLine()
    appendLine("## Population and design")
    appendLine()
    appendLine("- Panel: `${report.population.panelDigest}` from `${report.population.sourceRunIdentity}`")
    appendLine("- Current outer/Argentum: `${report.population.currentOuterCommit}` / `${report.population.currentArgentumCommit}`")
    appendLine("- Standard: ${report.standardRepetitions} independent identities per root at 64 simulations, depth 32")
    appendLine("- Adjacent: ${report.adjacentBudgetRepetitions} paired identities on ${report.population.adjacentBudgetRootIds.size} roots at 256 simulations")
    appendLine("- Decision families: ${report.population.roots.groupingBy { it.decisionFamily }.eachCount()}")
    appendLine("- Regimes: ${report.population.roots.groupingBy { it.regime }.eachCount()}")
    appendLine("- Candidate-count range: ${report.population.roots.minOf { it.initialCandidateCount }}..${report.population.roots.maxOf { it.initialCandidateCount }}")
    appendLine()
    appendLine("## Repeatability")
    appendLine()
    appendLine("| Budget | Roots | Unanimous action | Mean modal mass | Mean action agreement | Mean allocation TV | Median root-value SD |")
    appendLine("| ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    listOf(standard, adjacent).forEach { summary ->
        appendLine(
            "| ${summary.budget} | ${summary.roots} | ${summary.unanimousRoots}/${summary.roots} | " +
                "${summary.meanModalFraction.f3()} | ${summary.meanPairwiseSelectedActionAgreement.f3()} | " +
                "${summary.meanAllocationPairwiseTotalVariation.f3()} | " +
                "${summary.medianRootValueStandardDeviation.f3()} |"
        )
    }
    appendLine()
    appendLine(
        "Standard-budget candidate observations: unvisited=${standard.supportCounts.unvisited}, " +
            "1–3=${standard.supportCounts.oneToThreeVisits}, 4–7=${standard.supportCounts.fourToSevenVisits}, " +
            "8–15=${standard.supportCounts.eightToFifteenVisits}, 16+=${standard.supportCounts.sixteenOrMoreVisits}."
    )
    appendLine()
    appendLine("Mean-value ordering is a derived comparison, not a native Search Teacher rank:")
    standard.rankStability.forEach { rank ->
        appendLine(
            "- minimum ${rank.minimumVisitsInBothRepetitions} visits in both repetitions: " +
                "${rank.rootSummaries} roots, ${rank.comparableRepetitionPairs} repetition pairs, " +
                "mean root Kendall tau-b ${rank.meanRootKendallTauB.f3()}, mean absolute value difference " +
                "${rank.meanRootAbsoluteCandidateValueDifference.f3()}."
        )
    }
    appendLine()
    appendLine("Candidate-value stability by support floor:")
    standard.candidateValueStabilityBySupport.forEach { value ->
        appendLine(
            "- at least ${value.minimumVisitsEveryRepetition} visits in every repetition: " +
                "${value.candidates} candidates, median/p95 candidate value SD " +
                "${value.medianCandidateMeanValueStandardDeviation.f3()}/${value.p95CandidateMeanValueStandardDeviation.f3()}."
        )
    }
    appendLine()
    appendLine("## Adjacent-budget sensitivity")
    appendLine()
    appendLine(
        "Across ${report.budgetSensitivity.pairedTrials} paired identities on " +
            "${report.budgetSensitivity.roots} roots, selected-action agreement was " +
            "${report.budgetSensitivity.sameSelectedActionPairs}/${report.budgetSensitivity.pairedTrials} " +
            "(${report.budgetSensitivity.selectedActionAgreement.f3()}); mean allocation TV was " +
            "${report.budgetSensitivity.meanAllocationTotalVariation.f3()}, and mean absolute root-value change was " +
            "${report.budgetSensitivity.meanAbsoluteRootValueChange.f3()}."
    )
    appendLine()
    appendLine("## Source semantics")
    appendLine()
    report.statisticSemantics.forEach { semantic ->
        appendLine("- **${semantic.statistic}:** ${semantic.sourceMeaning} ${semantic.doesNotEstablish}")
    }
    appendLine()
    appendLine("## Limitations")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
}

private fun entropyBits(counts: Collection<Int>, total: Int): Double? = if (total == 0) null else {
    counts.sumOf { count ->
        val probability = count.toDouble() / total
        -probability * ln(probability) / ln(2.0)
    }
}

private fun <T> unorderedPairs(values: List<T>): List<Pair<T, T>> = buildList {
    for (left in values.indices) for (right in left + 1 until values.size) add(values[left] to values[right])
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun List<Double>.sampleStandardDeviation(): Double? = when (size) {
    0 -> null
    1 -> 0.0
    else -> {
        val mean = average()
        sqrt(sumOf { (it - mean) * (it - mean) } / (size - 1))
    }
}

private fun List<Double>.rangeOrNull(): Double? = if (isEmpty()) null else max() - min()

private fun List<Double>.medianOrNull(): Double? = if (isEmpty()) null else sorted().let { values ->
    val middle = values.size / 2
    if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
}

private fun List<Double>.p95OrNull(): Double? = if (isEmpty()) null else percentile(this, 0.95)

private fun Double?.f3(): String = this?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "n/a"
