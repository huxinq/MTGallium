package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable @SerialName("org.mtgallium.agent.infoset.core.LeafStateSource")
enum class LeafEvaluationMethod {
    CURRENT_INFORMATION_STATE,
    BOUNDED_ROLLOUT;
}

@Serializable @SerialName("org.mtgallium.agent.infoset.core.RolloutCutoff")
enum class RolloutCutoff {
    EVALUATE,
    QUIESCENCE,
    POLICY_QUIESCENCE,
}

/** Which singleton priority passes quiescence advances without making them search decisions. */
@Serializable @SerialName("org.mtgallium.agent.infoset.core.QuiescencePassRule")
enum class QuiescencePassRule {
    /** Only a pass that is the complete legal menu. */
    RULES_FORCED_V1,
    /**
     * Also a pass that is the complete action-space-profile menu, while the root player's position
     * is volatile. A profile that omits standalone mana abilities otherwise leaves stack and combat
     * resolution depending on untapped mana sources. Quiet positions are evaluated where they stand.
     */
    PROFILE_FORCED_WHILE_VOLATILE_V1;
}

@Serializable @SerialName("org.mtgallium.agent.infoset.core.LeafEvaluationConfig")
data class LeafEvaluationConfig(
    val stateSource: LeafEvaluationMethod,
    val cutoff: RolloutCutoff = RolloutCutoff.EVALUATE,
    /** Absent in earlier configurations, which keep the rules-forced rule and their identity. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val quiescencePasses: QuiescencePassRule = QuiescencePassRule.RULES_FORCED_V1,
) {
    init {
        require(cutoff == RolloutCutoff.EVALUATE || stateSource == LeafEvaluationMethod.BOUNDED_ROLLOUT) {
            "A non-direct cutoff requires a bounded rollout"
        }
        require(quiescencePasses == QuiescencePassRule.RULES_FORCED_V1 ||
            stateSource != LeafEvaluationMethod.BOUNDED_ROLLOUT || cutoff != RolloutCutoff.EVALUATE) {
            "A quiescence pass rule requires a leaf that settles through quiescence"
        }
    }
}

/** Recorded leaf settings keep historical wire values without making retired routes selectable. */
@Serializable @SerialName("org.mtgallium.agent.infoset.core.LeafEvaluationDiagnostic")
data class LeafEvaluationDiagnostic(
    val stateSource: String,
    val cutoff: String = "EVALUATE",
    val unresolved: String? = "EVALUATE",
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val quiescencePasses: String = "RULES_FORCED_V1",
)

internal fun LeafEvaluationConfig.diagnostic() = LeafEvaluationDiagnostic(
    stateSource = stateSource.name,
    cutoff = cutoff.name,
    quiescencePasses = quiescencePasses.name,
)

@Serializable @SerialName("org.mtgallium.agent.infoset.core.RolloutTurnHorizon")
data class RolloutTurnHorizon(
    val completedTurns: Int,
    /** Safety limit, not a substitute evaluation horizon. Exhaustion stops the search. */
    val maxPolicyDecisions: Int = 512,
) {
    init {
        require(completedTurns > 0)
        require(maxPolicyDecisions > 0)
    }
}

enum class RolloutTurnHorizonFailure { DECISION_LIMIT, MISSING_DECISION }

/** A requested turn boundary was not reached; no heuristic or terminal value is supplied. */
class RolloutTurnHorizonException(
    val failure: RolloutTurnHorizonFailure,
    val targetTurnNumber: Int,
    val policyDecisions: Int,
) : IllegalStateException(
    "Rollout turn horizon $targetTurnNumber not reached: $failure after $policyDecisions decisions"
)

@Serializable @SerialName("org.mtgallium.agent.infoset.core.InformationSetSearchConfig")
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
    /** Evaluate at the first player decision after N complete turns, anchored to the search root. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rolloutTurnHorizon: RolloutTurnHorizon? = null,
) {
    init {
        require(simulations > 0)
        require(explorationConstant >= 0.0 && explorationConstant.isFinite())
        require(maxPolicyDecisions > 0)
        require(initialExpansionLimit > 0)
        require(maxQuiescenceDecisions > 0)
        require(maxQuiescenceForcedPasses > 0)
        require(rolloutTurnHorizon == null || leaf.stateSource == LeafEvaluationMethod.BOUNDED_ROLLOUT) {
            "Completed-turn horizons require bounded rollout"
        }
        require(rolloutTurnHorizon == null || leaf.cutoff == RolloutCutoff.EVALUATE
        ) { "A completed-turn horizon evaluates its boundary directly, without later quiescence" }
        require(wideningThresholds.size == wideningLimits.size)
        require(wideningThresholds.zipWithNext().all { (a, b) -> a < b })
        require(wideningLimits.zipWithNext().all { (a, b) -> a < b })
        require(wideningLimits.all { it > initialExpansionLimit }) {
            "Every widening limit must be greater than the initial expansion limit"
        }
    }
}

@Serializable @SerialName("org.mtgallium.agent.infoset.core.SearchCandidateStatistics")
data class RootActionStatistics(
    val choice: SemanticChoice,
    /** Successful backed settlements for this branch in the current search. */
    val visits: Int,
    /** Root-perspective mean of those settlements; zero is a placeholder when [visits] is zero. */
    val meanValue: Double,
    @kotlinx.serialization.SerialName("policyProbability") val visitFraction: Double,
)

/** The scalar and its contemporaneous search-settlement origin; never infer this after search. */
data class SimulationReturn(
    val backedValue: Double,
    val origin: ReturnSource,
) {
    init { require(backedValue.isFinite()) { "Search settlement must be finite" } }
}

/** Exact partition of successful backups for one candidate edge. */
@Serializable @SerialName("org.mtgallium.agent.infoset.core.SearchSettlementCounts")
data class ReturnSourceCounts(
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

    fun plus(other: ReturnSourceCounts): ReturnSourceCounts = ReturnSourceCounts(
        terminalPayoffBackups + other.terminalPayoffBackups,
        heuristicSettlementBackups + other.heuristicSettlementBackups,
        learnedOutcomeEstimateBackups + other.learnedOutcomeEstimateBackups,
        neutralUnresolvedSettlementBackups + other.neutralUnresolvedSettlementBackups,
    )

    companion object {
        fun one(origin: ReturnSource): ReturnSourceCounts = when (origin) {
            ReturnSource.TERMINAL_PAYOFF -> ReturnSourceCounts(terminalPayoffBackups = 1)
            ReturnSource.HEURISTIC_SETTLEMENT -> ReturnSourceCounts(heuristicSettlementBackups = 1)
            ReturnSource.LEARNED_OUTCOME_ESTIMATE ->
                ReturnSourceCounts(learnedOutcomeEstimateBackups = 1)
            ReturnSource.NEUTRAL_UNRESOLVED_SETTLEMENT ->
                ReturnSourceCounts(neutralUnresolvedSettlementBackups = 1)
        }
    }
}

/**
 * The production Search Teacher's deterministic root-choice ordering.
 *
 * Keep evidence admission on this shared function: a serialized candidate table is not sufficient
 * teacher authority unless its recorded choice is the winner under the production ordering.
 */
fun List<RootActionStatistics>.mostVisitedActionOrNull(): RootActionStatistics? =
    maxWithOrNull(
        compareBy<RootActionStatistics> { it.visits }
            .thenBy { it.meanValue }
            .thenByDescending { it.choice.signature }
    )

@Serializable @SerialName("org.mtgallium.agent.infoset.core.InformationSetSearchDiagnostics")
data class InformationSetSearchDiagnostics(
    val simulations: Int,
    val particles: Int,
    val nodes: Int,
    val maximumDepth: Int,
    val exhaustiveNodes: Int,
    val nonExhaustiveNodes: Int,
    val wideningEvents: Int,
    val opponentModelId: String,
    val leaf: LeafEvaluationDiagnostic,
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
    /** The part of [quiescenceForcedPasses] forced only by the action-space profile. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val quiescenceProfileForcedPasses: Int = 0,
    val quiescenceStrategicDecisions: Int = 0,
    val quiescenceOverflows: Int = 0,
    val quiescenceFallbacks: Int = 0,
    val searchWorldSteps: Int = 0,
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
    val evaluatorId: String = "unknown",
    val evaluatorConfigurationId: String = evaluatorId,
    val evaluatorCalls: Int = 0,
    /** Evaluator calls at positions with pending stack, combat, damage, order, or lethal damage. */
    @kotlinx.serialization.SerialName("unsettledLeafEvaluations") val nonQuietLeafEvaluations: Int = 0,
    val evaluatorNanos: Long = 0,
    val evaluatorOutputChecksum: String = "0000000000000000",
    /** Unresolved horizon fallbacks backed up as neutral instead of evaluated. */
    val quiescenceUnresolvedBackups: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val rootSelectionGuidance: kotlinx.serialization.json.JsonObject? = null,
    val wallClockBudgetMillis: Long? = null,
    /** Exact within-search rollout-prefix hits; policy choices are still sampled afresh. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val rolloutTransitionCacheHits: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val rolloutTransitionCacheSnapshots: Int = 0,
    /** Prefixes whose new snapshot was refused by the runtime memory cap; execution continues. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val rolloutTransitionCacheBypasses: Int = 0,
)

@Serializable @SerialName("org.mtgallium.agent.infoset.core.InformationSetSearchResult")
data class InformationSetSearchResult(
    val chosen: SemanticChoice,
    val rootValue: Double,
    val candidates: List<RootActionStatistics>,
    /** Planner-only provenance keyed by the safe trajectory's existing candidate signatures. */
    val candidateSettlementCounts: Map<String, ReturnSourceCounts>,
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

    fun settlementCountsFor(choice: SemanticChoice): ReturnSourceCounts =
        requireNotNull(candidateSettlementCounts[choice.signature]) {
            "Search result has no settlement accounting for ${choice.signature}"
        }
}

class InformationSetConformanceException(message: String) : IllegalStateException(message)
