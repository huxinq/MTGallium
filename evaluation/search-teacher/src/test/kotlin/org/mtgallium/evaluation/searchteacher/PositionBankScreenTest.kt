package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.RolloutHorizonSettlementOverride
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig

@Tag("public-source")
class PositionBankScreenTest {
    @Test
    fun `screen rejects contradictory evaluator declarations`() {
        val evaluator = MonoRedVisibleEvaluatorConfig()
        val search = SearchTeacherCalibrationPolicy("candidate", 8, 64, 32, 0.35, true, 1.0)
        val outerOnly = PositionBankScreenPolicy(search, evaluator)
        val explicit = PositionBankScreenPolicy(search.copy(evaluator = evaluator), evaluator)
        assertEquals(requireNotNull(outerOnly.evaluator).configurationId,
            explicit.search.policy(7).informationEvaluator?.configurationId)
        assertFailsWith<IllegalArgumentException> {
            PositionBankScreenPolicy(search.copy(evaluator = evaluator.copy(life = 0.24)), evaluator)
        }
    }

    @Test
    fun `feature screen rejects tactical settlement treatment while search keeps tactical metadata`() {
        val tactical = SearchTeacherCalibrationPolicy(
            "tactical-direct", 8, 64, 32, 0.35, true, 1.0,
            tacticalEvaluator = SearchTeacherCalibrationTacticalEvaluator.V3_DEFAULT,
            rolloutHorizonSettlementOverride = RolloutHorizonSettlementOverride.DIRECT_EVALUATION,
        )
        val policy = PositionBankScreenPolicy(tactical)
        assertEquals("mono-red-tactical-value-v3", policy.informationEvaluator().id)
        assertFailsWith<IllegalArgumentException> {
            PositionBankScreenPlan(
                bankDirectory = "bank", expectedBankIdentity = "identity",
                partition = PositionBankScreenPartition.DEVELOPMENT,
                mode = PositionBankScreenMode.FEATURES, rootLimit = 1, repetitions = 1,
                policies = listOf(policy),
            )
        }
    }
}
