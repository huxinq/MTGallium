package org.mtgallium.agent.searchteacher

import org.mtgallium.agent.infoset.core.InformationStateEvaluator
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluationStrategy
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.infoset.core.UnresolvedLeafHandling

object SearchTeacherEvaluatorRegistry {
    fun strategy(
        leaf: LeafEvaluationConfig,
        informationEvaluator: InformationStateEvaluator? = null,
    ): LeafEvaluationStrategy = when (val evaluator = leaf.evaluator) {
        LeafEvaluator.MTGALLIUM_VISIBLE_V2 -> informationStrategy(
            evaluator,
            informationEvaluator ?: MonoRedInformationEvaluator,
        )
        LeafEvaluator.MTGALLIUM_TACTICAL_V3 -> informationStrategy(
            evaluator,
            informationEvaluator ?: MonoRedTacticalEvaluatorV3,
            supportsTraceReuse = false,
            settleAtRolloutHorizon = true,
            unresolvedLeafHandling = UnresolvedLeafHandling.BACK_UP_NEUTRAL,
        )
        LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1 -> {
            require(leaf.stateSource == LeafStateSource.CURRENT_INFORMATION_STATE) {
                evaluator.evaluatorId + " replaces bounded continuation only through " +
                    LeafStateSource.CURRENT_INFORMATION_STATE
            }
            require(informationEvaluator is CheckpointBackedLearnedOutcomeValueEvaluator) {
                evaluator.evaluatorId + " requires a checkpoint-backed LearnedOutcomeValueEvaluator"
            }
            informationStrategy(
                evaluator,
                informationEvaluator,
                supportsTraceReuse = false,
            )
        }
        LeafEvaluator.ARGENTUM_BOARD_V1 -> {
            require(informationEvaluator == null) {
                "${evaluator.evaluatorId} is evaluated only from an allowlisted sampled world"
            }
            LeafEvaluationStrategy(
                configuredEvaluatorId = evaluator.evaluatorId,
                source = LeafValueSource.SampledWorld(evaluator.evaluatorId),
            )
        }
    }

    private fun informationStrategy(
        evaluator: LeafEvaluator,
        implementation: InformationStateEvaluator,
        supportsTraceReuse: Boolean = true,
        settleAtRolloutHorizon: Boolean = false,
        unresolvedLeafHandling: UnresolvedLeafHandling = UnresolvedLeafHandling.EVALUATE,
    ): LeafEvaluationStrategy = LeafEvaluationStrategy(
        configuredEvaluatorId = evaluator.evaluatorId,
        source = LeafValueSource.Information(implementation),
        supportsTraceReuse = supportsTraceReuse,
        settleAtRolloutHorizon = settleAtRolloutHorizon,
        unresolvedLeafHandling = unresolvedLeafHandling,
    )
}
