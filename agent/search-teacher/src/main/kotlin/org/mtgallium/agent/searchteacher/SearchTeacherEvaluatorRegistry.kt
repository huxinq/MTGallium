package org.mtgallium.agent.searchteacher

import org.mtgallium.agent.infoset.core.InformationStateEvaluator
import org.mtgallium.agent.infoset.core.LeafEvaluationStrategy
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.infoset.core.UnresolvedLeafHandling

object SearchTeacherEvaluatorRegistry {
    fun strategy(
        evaluator: LeafEvaluator,
        informationEvaluator: InformationStateEvaluator? = null,
    ): LeafEvaluationStrategy = when (evaluator) {
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
