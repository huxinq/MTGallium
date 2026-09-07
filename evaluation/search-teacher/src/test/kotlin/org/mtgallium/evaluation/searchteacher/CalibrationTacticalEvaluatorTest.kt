package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluator
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluatorSettings
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluatorSchema2

@Tag("public-source")
class CalibrationTacticalEvaluatorTest {
    private val compact = Json(evidenceJson) { prettyPrint = false }
    private val prefix = """{"id":"test","particles":8,"simulations":64,"maxPolicyDecisions":32,"explorationConstant":1.4,"singletonSelection":true,"rolloutHeuristicProbability":1.0"""

    private fun roundTrip(wire: String): SearchTeacherCalibrationPolicy {
        val policy = compact.decodeFromString<SearchTeacherCalibrationPolicy>(wire)
        assertEquals(wire, compact.encodeToString(policy))
        // The production pretty-printed representation must retain the same member order too.
        val expected = evidenceJson.encodeToString(JsonElement.serializer(), compact.parseToJsonElement(wire))
        assertEquals(expected, evidenceJson.encodeToString(policy))
        return policy
    }

    @Test
    fun `schema one settings preserve original bytes and ten coefficient identity`() {
        val settings = """{"schemaVersion":1,"outputTemperature":2.0,"startingLife":20,"annotationVersion":"mono-red-tactical-annotations-v1","enabledFamilies":["NONLINEAR_LIFE","COMBAT_READINESS","ROOT_KNOWN_REACH","SAFE_OPPONENT_PRIOR","MANA_FIT","KNOWN_CARD_VALUE","INITIATIVE"],"weights":{"life":1.2,"lethal":2.4,"body":0.9,"attack":1.0,"block":0.35,"reach":0.85,"hand":0.55,"mana":0.65,"landConversion":0.2,"initiative":0.25}}"""
        val policy = roundTrip(prefix + ""","rolloutTurnHorizon":{"completedTurns":2,"maxPolicyDecisions":512},"tacticalEvaluator":$settings}""")
        val evaluator = assertIs<MonoRedTacticalEvaluator>(policy.informationEvaluator())
        assertEquals(MonoRedTacticalEvaluatorSettings().configurationId, evaluator.configurationId)
        assertEquals(10, tacticalV3Coefficients(evaluator.settings.weights).size)
        assertEquals(policy, compact.decodeFromString(compact.encodeToString(policy)))
    }

    @Test
    fun `screening strings preserve original bytes and schema two identities`() {
        for (selector in SearchTeacherCalibrationTacticalEvaluator.entries) {
            val policy = roundTrip(prefix + ""","tacticalEvaluator":"${selector.name}","searchHeuristicProfile":"PRODUCTION_EXPIRING","rolloutTurnHorizon":{"completedTurns":2,"maxPolicyDecisions":512}}""")
            val evaluator = assertIs<MonoRedTacticalEvaluatorSchema2>(policy.informationEvaluator())
            val attack = if (selector == SearchTeacherCalibrationTacticalEvaluator.V3_DEFAULT) "1" else "0"
            val initiative = if (selector == SearchTeacherCalibrationTacticalEvaluator.V3_DEFAULT) "0.25" else "0"
            assertEquals("mono-red-tactical-value-v3:schema-2:temperature-2:life-20:mono-red-tactical-annotations-v2:" +
                "COMBAT_READINESS+DURABLE_MANA+HAND_VALUE+INITIATIVE+NONLINEAR_LIFE+REACH:" +
                "weights-1.2,2.4,0.9,$attack,0.35,0.85,0.55,0.65,$initiative", evaluator.configurationId)
        }
        roundTrip(prefix + ""","searchHeuristicProfile":"PRODUCTION_EXPIRING","rolloutTurnHorizon":{"completedTurns":2,"maxPolicyDecisions":512}}""")
        assertEquals(null, roundTrip("$prefix}").tacticalEvaluator)
    }

    @Test
    fun `screening policy omission stays omitted and conflicting evaluator claims fail`() {
        val wire = """{"search":$prefix,"tacticalEvaluator":"V3_DEFAULT"}}"""
        val policy = compact.decodeFromString<PositionBankScreenPolicy>(wire)
        assertEquals(wire, compact.encodeToString(policy))
        assertIs<MonoRedTacticalEvaluatorSchema2>(policy.informationEvaluator())
        assertFailsWith<IllegalArgumentException> {
            policy.copy(evaluator = org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig())
        }
        assertFails { compact.decodeFromString<SearchTeacherCalibrationPolicy>(prefix + ""","tacticalEvaluator":"V3_UNKNOWN"}""") }
        assertFails { compact.decodeFromString<SearchTeacherCalibrationPolicy>(prefix + ""","tacticalEvaluator":{"schemaVersion":2}}""") }
    }

    @Test
    fun `attack influence refuses policy quiescence that its observer cannot record`() {
        val policy = SearchTeacherCalibrationPolicy("test", 8, 64, 32, 1.4, true, 1.0,
            tacticalEvaluator = SearchTeacherCalibrationTacticalEvaluator.V3_DEFAULT)
        val plan = PositionBankScreenPlan(bankDirectory = "/tmp/bank", expectedBankIdentity = "bank",
            partition = PositionBankScreenPartition.DEVELOPMENT, mode = PositionBankScreenMode.SEARCH,
            rootLimit = 1, repetitions = 1, policies = listOf(PositionBankScreenPolicy(policy)),
            attackInfluenceFit = RootKernelFitReference("/tmp/fit", "research-run-v1-sha256:" + "b".repeat(64), "a".repeat(64)))
        assertFailsWith<IllegalArgumentException> {
            plan.copy(policies = listOf(PositionBankScreenPolicy(policy.copy(
                rolloutHorizonSettlementOverride = org.mtgallium.agent.infoset.core.RolloutHorizonSettlementOverride
                    .POLICY_QUIESCENCE_WITH_EVALUATION_FALLBACK))))
        }
    }
}
