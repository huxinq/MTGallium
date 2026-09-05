package org.mtgallium.agent.infoset.core

import kotlinx.serialization.Serializable

@Serializable
enum class LeafStateSource { CURRENT_INFORMATION_STATE, CURRENT_SAMPLED_WORLD, BOUNDED_ROLLOUT }

@Serializable
enum class LeafEvaluator(val evaluatorId: String) {
    MTGALLIUM_VISIBLE_V2("mono-red-visible-board-v2"),
    MTGALLIUM_TACTICAL_V3("mono-red-tactical-value-v3"),
    MTGALLIUM_LEARNED_OUTCOME_V1("mono-red-learned-outcome-value-v1"),
    ARGENTUM_BOARD_V1("argentum-board-v1"),
    ;
}

@Serializable
data class LeafEvaluationConfig(
    val stateSource: LeafStateSource,
    val evaluator: LeafEvaluator,
)

@Serializable
data class InformationSetSearchConfig(
    val simulations: Int,
    val explorationConstant: Double = 1.4,
    val maxPolicyDecisions: Int = 256,
    val leaf: LeafEvaluationConfig,
    val initialExpansionLimit: Int = 64,
    val wideningThresholds: List<Int> = listOf(64, 256, 1024),
    val wideningLimits: List<Int> = listOf(128, 256, 512),
    val maxQuiescenceDecisions: Int = 32,
    val maxQuiescenceForcedPasses: Int = 256,
    val compressPolicySingletonPasses: Boolean = false,
    /** Exact semantic-prefix memoization. This is behavior-preserving and can be disabled for A/B validation. */
    val cacheSimulationTransitions: Boolean = true,
    /** Optional deployment-style budget. Null preserves exact fixed-simulation behavior. */
    val wallClockBudgetMillis: Long? = null,
    val minimumSimulations: Int = 1,
) {
    init {
        require(simulations > 0)
        require(explorationConstant >= 0.0 && explorationConstant.isFinite())
        require(maxPolicyDecisions > 0)
        require(initialExpansionLimit > 0)
        require(maxQuiescenceDecisions > 0)
        require(maxQuiescenceForcedPasses > 0)
        require(wallClockBudgetMillis == null || wallClockBudgetMillis > 0)
        require(minimumSimulations in 1..simulations)
        require(wideningThresholds.size == wideningLimits.size)
        require(wideningThresholds.zipWithNext().all { (a, b) -> a < b })
        require(wideningLimits.zipWithNext().all { (a, b) -> a < b })
        require(wideningLimits.all { it > initialExpansionLimit }) {
            "Every widening limit must be greater than the initial expansion limit"
        }
    }
}

@Serializable
data class InformationSetSearchReuseConfig(
    val enabled: Boolean = false,
    val minimumFreshSimulations: Int = 16,
    val maximumReuseFraction: Double = 0.75,
) {
    init {
        require(minimumFreshSimulations > 0)
        require(maximumReuseFraction.isFinite() && maximumReuseFraction in 0.0..1.0)
    }

    companion object {
        val DISABLED = InformationSetSearchReuseConfig()
    }
}

@Serializable
data class SearchCandidateStatistics(
    val choice: SemanticChoice,
    val visits: Int,
    val meanValue: Double,
    val policyProbability: Double,
)

/** Why one simulated continuation supplied the scalar that search backed up. */
@Serializable
enum class SearchSettlementOrigin {
    /** The sampled search continuation itself reached an engine terminal payoff. */
    TERMINAL_PAYOFF,
    /** Search settled a bounded continuation with the configured leaf heuristic. */
    HEURISTIC_SETTLEMENT,
    /** Search settled a nonterminal information state with a learned outcome estimate. */
    LEARNED_OUTCOME_ESTIMATE,
    /** Search explicitly backed up neutral because a bounded continuation remained unresolved. */
    NEUTRAL_UNRESOLVED_SETTLEMENT,
}

/** The scalar and its contemporaneous search-settlement origin; never infer this after search. */
data class SearchSettlement(
    val backedValue: Double,
    val origin: SearchSettlementOrigin,
) {
    init { require(backedValue.isFinite()) { "Search settlement must be finite" } }
}

/** Actual terminal continuation reached only by the production rollout-policy pair. */
data class TerminalPolicyContinuation(
    val payoff: Double,
    val policyDecisions: Int,
    val rootPolicyDecisions: OpponentPolicyDecisionSummary,
    val opponentPolicyDecisions: OpponentPolicyDecisionSummary,
) {
    init {
        require(payoff.isFinite() && payoff in -1.0..1.0)
        require(policyDecisions >= 0)
        require(rootPolicyDecisions.decisions + opponentPolicyDecisions.decisions == policyDecisions)
        require(rootPolicyDecisions.evidenceInvalidatingReplacements == 0)
        require(opponentPolicyDecisions.evidenceInvalidatingReplacements == 0)
    }
}

/** Exact partition of successful backups for one candidate edge. */
@Serializable
data class SearchSettlementCounts(
    val terminalPayoffBackups: Int = 0,
    val heuristicSettlementBackups: Int = 0,
    /** Absent in historical evidence, which therefore remains a zero-count unknown for this origin. */
    val learnedOutcomeEstimateBackups: Int = 0,
    val neutralUnresolvedSettlementBackups: Int = 0,
) {
    init {
        require(terminalPayoffBackups >= 0)
        require(heuristicSettlementBackups >= 0)
        require(learnedOutcomeEstimateBackups >= 0)
        require(neutralUnresolvedSettlementBackups >= 0)
    }

    val successfulBackups: Int
        get() = terminalPayoffBackups + heuristicSettlementBackups +
            learnedOutcomeEstimateBackups + neutralUnresolvedSettlementBackups

    fun plus(other: SearchSettlementCounts): SearchSettlementCounts = SearchSettlementCounts(
        terminalPayoffBackups + other.terminalPayoffBackups,
        heuristicSettlementBackups + other.heuristicSettlementBackups,
        learnedOutcomeEstimateBackups + other.learnedOutcomeEstimateBackups,
        neutralUnresolvedSettlementBackups + other.neutralUnresolvedSettlementBackups,
    )

    companion object {
        fun one(origin: SearchSettlementOrigin): SearchSettlementCounts = when (origin) {
            SearchSettlementOrigin.TERMINAL_PAYOFF -> SearchSettlementCounts(terminalPayoffBackups = 1)
            SearchSettlementOrigin.HEURISTIC_SETTLEMENT -> SearchSettlementCounts(heuristicSettlementBackups = 1)
            SearchSettlementOrigin.LEARNED_OUTCOME_ESTIMATE ->
                SearchSettlementCounts(learnedOutcomeEstimateBackups = 1)
            SearchSettlementOrigin.NEUTRAL_UNRESOLVED_SETTLEMENT ->
                SearchSettlementCounts(neutralUnresolvedSettlementBackups = 1)
        }
    }
}

/**
 * The production Search Teacher's deterministic root-choice ordering.
 *
 * Keep evidence admission on this shared function: a serialized candidate table is not sufficient
 * teacher authority unless its recorded choice is the winner under the production ordering.
 */
fun List<SearchCandidateStatistics>.selectedSearchWinnerOrNull(): SearchCandidateStatistics? =
    maxWithOrNull(
        compareBy<SearchCandidateStatistics> { it.visits }
            .thenBy { it.meanValue }
            .thenByDescending { it.choice.signature }
    )

@Serializable
data class InformationSetSearchDiagnostics(
    val simulations: Int,
    val particles: Int,
    val nodes: Int,
    val maximumDepth: Int,
    val exhaustiveNodes: Int,
    val nonExhaustiveNodes: Int,
    val wideningEvents: Int,
    val opponentModelId: String,
    val leaf: LeafEvaluationConfig,
    val rootRolloutPolicyId: String? = null,
    val opponentRolloutPolicyId: String? = null,
    val rootRolloutDecisions: Int = 0,
    val opponentRolloutDecisions: Int = 0,
    val rootRolloutFallbacks: Int = 0,
    val opponentRolloutFallbacks: Int = 0,
    /** One exact component attribution for every sampled outer-opponent action. */
    val opponentModelPolicyDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    /** One exact component attribution for every sampled root-seat rollout action. */
    val rootRolloutPolicyDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    /** One exact component attribution for every sampled opponent-seat rollout action. */
    val opponentRolloutPolicyDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    val quiescenceForcedPasses: Int = 0,
    val quiescenceStrategicDecisions: Int = 0,
    val quiescenceOverflows: Int = 0,
    val quiescenceFallbacks: Int = 0,
    val freshSimulations: Int = simulations,
    val reusedSimulations: Int = 0,
    val refreshedSimulations: Int = 0,
    val reuseDiscardReasons: Map<String, Int> = emptyMap(),
    val retainedTraceCount: Int = 0,
    val retainedSnapshotCount: Int = 0,
    val compressedPolicySingletonPasses: Int = 0,
    val searchWorldSteps: Int = 0,
    val reuseTopologyCandidates: Int = 0,
    val privateReuseKeyComputations: Int = 0,
    val policyAnnotatedExpansions: Int = 0,
    val transitionCacheHits: Int = 0,
    val transitionCacheMisses: Int = 0,
    val transitionCacheSnapshots: Int = 0,
    /** Child snapshots refreshed after deterministic projections/annotations were materialized. */
    val transitionCacheDerivedSnapshots: Int = 0,
    val policyAnnotationCacheHits: Int = 0,
    val policyAnnotationCacheMisses: Int = 0,
    val opponentDistributionCacheHits: Int = 0,
    val opponentDistributionCacheMisses: Int = 0,
    /** Any rejected simulated transition is a correctness defect, even if search can recover. */
    val rejectedTransitions: Int = 0,
    val configuredEvaluatorId: String = leaf.evaluator.evaluatorId,
    val invokedEvaluatorId: String = configuredEvaluatorId,
    val invokedEvaluatorConfigurationId: String = invokedEvaluatorId,
    val evaluatorCalls: Int = 0,
    val evaluatorNanos: Long = 0,
    val evaluatorOutputChecksum: String = "0000000000000000",
    /** V3 backs up neutral uncertainty instead of applying a quiet evaluator to unresolved tactics. */
    val quiescenceUnresolvedBackups: Int = 0,
    val wallClockBudgetMillis: Long? = null,
) {
    val evaluatorId: String get() = leaf.evaluator.evaluatorId
}

@Serializable
data class InformationSetSearchResult(
    val chosen: SemanticChoice,
    val rootValue: Double,
    val candidates: List<SearchCandidateStatistics>,
    /** Planner-only provenance keyed by the safe trajectory's existing candidate signatures. */
    val candidateSettlementCounts: Map<String, SearchSettlementCounts>,
    val diagnostics: InformationSetSearchDiagnostics,
) {
    init {
        val candidateVisits = candidates.associate { it.choice.signature to it.visits }
        require(candidateVisits.keys == candidateSettlementCounts.keys) {
            "Settlement accounting must cover exactly the returned candidate family"
        }
        candidateVisits.forEach { (signature, visits) ->
            require(candidateSettlementCounts.getValue(signature).successfulBackups == visits) {
                "Settlement accounting must partition successful backups for $signature"
            }
        }
    }

    fun settlementCountsFor(choice: SemanticChoice): SearchSettlementCounts =
        requireNotNull(candidateSettlementCounts[choice.signature]) {
            "Search result has no settlement accounting for ${choice.signature}"
        }
}

class InformationSetConformanceException(message: String) : IllegalStateException(message)
