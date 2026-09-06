package org.mtgallium.agent.searchteacher

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.RolloutHorizonSettlementOverride
import org.mtgallium.agent.infoset.core.UnresolvedLeafHandling

class SearchTeacherLeafConfigurationsTest {
    @Test
    fun `supported calibration cells preserve their frozen order`() {
        assertEquals(
            listOf(
                LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
                LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
                LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.ARGENTUM_BOARD_V1),
                LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
                LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.ARGENTUM_BOARD_V1),
            ),
            SearchTeacherLeafConfigurations.supported,
        )
    }

    @Test
    fun `experimental cells preserve one tactical evaluator cell per state source`() {
        assertEquals(
            listOf(
                LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_TACTICAL_V3),
                LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.MTGALLIUM_TACTICAL_V3),
                LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_TACTICAL_V3),
            ),
            SearchTeacherLeafConfigurations.experimental,
        )
    }

    @Test
    fun `persisted leaf selection schema remains byte compatible`() {
        assertEquals(
            listOf(
                "\"MTGALLIUM_VISIBLE_V2\"",
                "\"MTGALLIUM_TACTICAL_V3\"",
                "\"MTGALLIUM_LEARNED_OUTCOME_V1\"",
                "\"ARGENTUM_BOARD_V1\"",
            ),
            LeafEvaluator.entries.map { PolicyJson.format.encodeToString(it) },
        )
        assertEquals(
            """{"stateSource":"CURRENT_INFORMATION_STATE","evaluator":"MTGALLIUM_VISIBLE_V2"}""",
            PolicyJson.format.encodeToString(
                LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                )
            ),
        )
    }

    @Test
    fun `bounded tactical direct evaluation is explicit while default preserves quiescence neutral settlement`() {
        val default = LeafEvaluationConfig(
            LeafStateSource.BOUNDED_ROLLOUT,
            LeafEvaluator.MTGALLIUM_TACTICAL_V3,
        )
        val direct = default.copy(
            rolloutHorizonSettlementOverride = RolloutHorizonSettlementOverride.DIRECT_EVALUATION,
        )
        val quiescenceEvaluation = default.copy(
            rolloutHorizonSettlementOverride =
                RolloutHorizonSettlementOverride.QUIESCENCE_WITH_EVALUATION_FALLBACK,
        )

        val defaultStrategy = SearchTeacherEvaluatorRegistry.strategy(default)
        val directStrategy = SearchTeacherEvaluatorRegistry.strategy(direct)
        val quiescenceEvaluationStrategy = SearchTeacherEvaluatorRegistry.strategy(quiescenceEvaluation)
        assertEquals(true, defaultStrategy.settleAtRolloutHorizon)
        assertEquals(UnresolvedLeafHandling.BACK_UP_NEUTRAL, defaultStrategy.unresolvedLeafHandling)
        assertEquals(false, directStrategy.settleAtRolloutHorizon)
        assertEquals(UnresolvedLeafHandling.EVALUATE, directStrategy.unresolvedLeafHandling)
        assertEquals(true, quiescenceEvaluationStrategy.settleAtRolloutHorizon)
        assertEquals(UnresolvedLeafHandling.EVALUATE, quiescenceEvaluationStrategy.unresolvedLeafHandling)
        assertEquals(
            """{"stateSource":"BOUNDED_ROLLOUT","evaluator":"MTGALLIUM_TACTICAL_V3","rolloutHorizonSettlementOverride":"DIRECT_EVALUATION"}""",
            PolicyJson.format.encodeToString(direct),
        )
        assertEquals(
            """{"stateSource":"BOUNDED_ROLLOUT","evaluator":"MTGALLIUM_TACTICAL_V3","rolloutHorizonSettlementOverride":"QUIESCENCE_WITH_EVALUATION_FALLBACK"}""",
            PolicyJson.format.encodeToString(quiescenceEvaluation),
        )
    }

    @Test
    fun `registry rejects rollout horizon settlement overrides outside bounded tactical v3`() {
        listOf(
            LeafEvaluator.MTGALLIUM_VISIBLE_V2,
            LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1,
            LeafEvaluator.ARGENTUM_BOARD_V1,
        ).forEach { evaluator ->
            RolloutHorizonSettlementOverride.entries.forEach { override ->
                val leaf = LeafEvaluationConfig(
                    LeafStateSource.BOUNDED_ROLLOUT,
                    evaluator,
                    override,
                )
                assertFailsWith<IllegalArgumentException>("$evaluator / $override") {
                    SearchTeacherEvaluatorRegistry.strategy(leaf)
                }
            }
        }
    }
}
