package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.RolloutHorizonSettlementOverride
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig

@Tag("public-source")
class PositionBankScreenTest {
    @Test
    fun `explicit roots are exact sorted unique selections and historical default remains omitted`() {
        val policy = PositionBankScreenPolicy(SearchTeacherCalibrationPolicy("reference", 8, 64, 32, 1.4, true, 1.0), MonoRedVisibleEvaluatorConfig())
        val plan = PositionBankScreenPlan(bankDirectory = "/tmp/bank", expectedBankIdentity = "bank", partition = PositionBankScreenPartition.DEVELOPMENT,
            mode = PositionBankScreenMode.ACTION_CONDITIONAL, rootLimit = 2, repetitions = 2, policies = listOf(policy))
        assertFalse("rootIds" in evidenceJson.encodeToString(plan))
        val explicit = plan.copy(rootIds = listOf("a", "b"))
        assertEquals(explicit, evidenceJson.decodeFromString<PositionBankScreenPlan>(evidenceJson.encodeToString(explicit)))
        assertFailsWith<IllegalArgumentException> { plan.copy(rootIds = listOf("a")) }
        assertFailsWith<IllegalArgumentException> { plan.copy(rootIds = listOf("b", "a")) }
        assertFailsWith<IllegalArgumentException> { plan.copy(rootIds = listOf("a", "a")) }
        assertFailsWith<IllegalArgumentException> { selectPositionScreenRoots(explicit, emptyList()) }
    }

    @Test
    fun `independent reference seed domain is explicit while historical plan bytes omit the default`() {
        val policy = PositionBankScreenPolicy(SearchTeacherCalibrationPolicy("reference", 8, 64, 32, 1.4, true, 1.0),
            MonoRedVisibleEvaluatorConfig())
        val plan = PositionBankScreenPlan(bankDirectory = "/tmp/bank", expectedBankIdentity = "bank", partition = PositionBankScreenPartition.DEVELOPMENT,
            mode = PositionBankScreenMode.SEARCH, rootLimit = 2, repetitions = 2, policies = listOf(policy))
        assertFalse("searchSeedDomain" in evidenceJson.encodeToString(plan))
        val reference = plan.copy(mode = PositionBankScreenMode.ACTION_CONDITIONAL, searchSeedDomain = "independent-reference-v1")
        assertEquals(reference, evidenceJson.decodeFromString<PositionBankScreenPlan>(evidenceJson.encodeToString(reference)))
        assertFailsWith<IllegalArgumentException> { plan.copy(searchSeedDomain = "") }
    }

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
