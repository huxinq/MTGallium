package org.mtgallium.agent.searchteacher

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyJson

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
}
