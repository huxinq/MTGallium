package org.mtgallium.evaluation.searchteacher

import kotlin.math.abs
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.RootActionSearchEstimate
import org.mtgallium.agent.infoset.core.SearchSettlementCounts
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.searchteacher.ConfiguredMonoRedInformationEvaluator
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.agent.searchteacher.MonoRedVisibleFeatures

/**
 * A fixed-continuation surrogate. Changing coefficients here does not rerun tree selection or rollouts.
 * Features describe projected simulated leaf hypotheses, not observed historical states.
 * Only numeric v2 features are retained; sampled engine state is never retained.
 */
@Serializable
internal data class VisibleV2ActionTrace(
    val action: SemanticChoice,
    val visits: Int,
    val features: List<MonoRedVisibleFeatures>,
    /** Residual backed-value sum held constant when rescoring; not an observed game-outcome artifact. */
    val fixedBackupValueSum: Double,
    val handMeanValue: Double,
    val settlementCounts: SearchSettlementCounts,
) {
    init {
        require(visits > 0 && visits == settlementCounts.successfulBackups)
        require(features.size == settlementCounts.heuristicSettlementBackups)
        require(settlementCounts.learnedOutcomeEstimateBackups == 0 && settlementCounts.neutralUnresolvedSettlementBackups == 0)
        require(fixedBackupValueSum.isFinite() && abs(fixedBackupValueSum) <= settlementCounts.terminalPayoffBackups + 1e-8)
        require(handMeanValue.isFinite() && handMeanValue in -1.0..1.0)
    }
    fun meanValue(config: MonoRedVisibleEvaluatorConfig): Double =
        (fixedBackupValueSum + features.sumOf { it.evaluate(config) }) / visits
}

/** Observer with exactly the hand evaluator's output/configuration identity. One worker owns each instance. */
internal class RecordingVisibleV2Evaluator(config: MonoRedVisibleEvaluatorConfig) : ConfiguredInformationStateEvaluator {
    private val hand = ConfiguredMonoRedInformationEvaluator(config)
    private val captured = mutableListOf<MonoRedVisibleFeatures>()
    override val id: String get() = hand.id
    override val configurationId: String get() = hand.configurationId
    override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double {
        val features = MonoRedVisibleFeatures.extract(information, rootPlayer)
        captured += features
        return features.evaluate(hand.config)
    }
    fun reset() { captured.clear() }
    fun finish(estimate: RootActionSearchEstimate): VisibleV2ActionTrace =
        visibleV2ActionTrace(estimate, captured.toList(), hand.config)
}

internal fun visibleV2ActionTrace(
    estimate: RootActionSearchEstimate, features: List<MonoRedVisibleFeatures>, hand: MonoRedVisibleEvaluatorConfig,
): VisibleV2ActionTrace {
    requireValidScreenSearch(estimate.diagnostics)
    require(estimate.diagnostics.reusedSimulations == 0)
    require(estimate.diagnostics.evaluatorCalls == features.size)
    val sum = features.sumOf { it.evaluate(hand) }
    val fixed = estimate.meanBackedValue * estimate.visits - sum
    val trace = VisibleV2ActionTrace(estimate.action, estimate.visits, features, fixed,
        estimate.meanBackedValue, estimate.settlementCounts)
    require(abs(trace.meanValue(hand) - estimate.meanBackedValue) < 1e-12)
    return trace
}
