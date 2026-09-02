package org.mtgallium.agent.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.infoset.core.UnresolvedLeafHandling

class SearchTeacherCompositionTest {
    @Test
    fun `persisted evaluator identities resolve through the production registry`() {
        LeafEvaluator.entries.forEach { evaluator ->
            assertEquals(
                evaluator.evaluatorId,
                SearchTeacherEvaluatorRegistry.strategy(evaluator).source.invokedEvaluatorId,
                "Registry identity drifted for $evaluator",
            )
        }
    }

    @Test
    fun `registry owns evaluator routing and safety behavior`() {
        val visible = SearchTeacherEvaluatorRegistry.strategy(LeafEvaluator.MTGALLIUM_VISIBLE_V2)
        val tactical = SearchTeacherEvaluatorRegistry.strategy(LeafEvaluator.MTGALLIUM_TACTICAL_V3)
        val sampled = SearchTeacherEvaluatorRegistry.strategy(LeafEvaluator.ARGENTUM_BOARD_V1)

        assertIs<LeafValueSource.Information>(visible.source)
        assertTrue(visible.supportsTraceReuse)
        assertFalse(visible.settleAtRolloutHorizon)
        assertEquals(UnresolvedLeafHandling.EVALUATE, visible.unresolvedLeafHandling)

        assertIs<LeafValueSource.Information>(tactical.source)
        assertFalse(tactical.supportsTraceReuse)
        assertTrue(tactical.settleAtRolloutHorizon)
        assertEquals(UnresolvedLeafHandling.BACK_UP_NEUTRAL, tactical.unresolvedLeafHandling)

        assertIs<LeafValueSource.SampledWorld>(sampled.source)
        assertTrue(sampled.supportsTraceReuse)
    }

    @Test
    fun `registry and factory reject incompatible evaluator wiring`() {
        assertFailsWith<IllegalArgumentException> {
            SearchTeacherEvaluatorRegistry.strategy(
                LeafEvaluator.MTGALLIUM_TACTICAL_V3,
                MonoRedInformationEvaluator,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SearchTeacherSearchFactory.create(
                InformationSetSearchConfig(
                    simulations = 1,
                    leaf = LeafEvaluationConfig(
                        LeafStateSource.CURRENT_INFORMATION_STATE,
                        LeafEvaluator.ARGENTUM_BOARD_V1,
                    ),
                )
            )
        }
    }

    @Test
    fun `production opponent and rollout defaults keep their recorded identities`() {
        assertEquals("mono-red-mixture-70-10-10-10-v2", defaultMonoRedOpponentPolicy().id)
        assertEquals("root-argentum-production-rollout-v2", SearchTeacherSearchFactory.rootRolloutPolicy().id)
        assertEquals(
            "opponent-argentum-production-rollout-v2",
            SearchTeacherSearchFactory.opponentRolloutPolicy().id,
        )
    }
}
