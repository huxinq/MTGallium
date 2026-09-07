package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig

@Tag("public-source")
class TerminalRootContinuationsTest {
    private fun action(key: String) = SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay(key),
        canonicalPayload = JsonObject(mapOf("key" to JsonPrimitive(key))))
    private fun sample(index: Int, value: Double) = TerminalRootSample(index, index % 2, index.toLong(), index.toLong(), value, 0,
        OpponentPolicyDecisionSummary(), OpponentPolicyDecisionSummary(), 0.0)

    @Test fun `selected action union preserves menu order and rejects duplicate or foreign actions`() {
        val menu = listOf(action("a"), action("b"), action("c"))
        assertEquals(listOf(menu[0], menu[2]), selectTerminalActionSubset(menu,
            listOf(menu[2].signature, menu[0].signature).sorted()))
        assertFails { selectTerminalActionSubset(menu, listOf("foreign")) }
        assertFails { selectTerminalActionSubset(menu, listOf(menu[0].signature, menu[0].signature)) }
        assertFails { selectTerminalActionSubset(menu, emptyList()) }
        assertEquals(4096, terminalRootWorkload(TerminalRootContinuationConfig(16,
            maximumTotalContinuations = 4096), List(32) { 8 }, 1, 1))
    }

    @Test fun `terminal targets retain samples and pair coordinates without inventing visits or means`() {
        val menu = listOf(action("a"), action("b"))
        val result = collectTerminalRootActions(menu, 2) { a, i -> sample(i, if (a == menu[0]) 1.0 else -1.0) }
        assertEquals(listOf(1.0, -1.0), result.map { it.meanTerminalPayoff })
        assertTrue(result.all { it.disposition == TerminalRootActionDisposition.COMPLETE })
        assertEquals(result[0].samples.map { it.particleIndex to it.futureSeed }, result[1].samples.map { it.particleIndex to it.futureSeed })
        result.forEach { assertEquals(it, evidenceJson.decodeFromString<TerminalRootActionSamples>(evidenceJson.encodeToString(it))) }
        assertFails { result.first().copy(meanTerminalPayoff = 0.0) }
    }

    @Test fun `failure retains prior terminal samples and leaves incomplete actions without targets`() {
        val menu = listOf(action("a"), action("b"), action("c"))
        var calls = 0
        val result = collectTerminalRootActions(menu, 2) { a, i ->
            calls++
            if (a == menu[1] && i == 1) error("continuation exhausted")
            sample(i, -1.0)
        }
        assertEquals(4, calls)
        assertEquals(listOf(TerminalRootActionDisposition.COMPLETE, TerminalRootActionDisposition.NON_GAME_FAILURE,
            TerminalRootActionDisposition.NOT_EXECUTED), result.map { it.disposition })
        assertEquals(listOf(2, 1, 0), result.map { it.samples.size })
        assertEquals(listOf(-1.0, null, null), result.map { it.meanTerminalPayoff })
        assertTrue(result[1].diagnostic!!.contains("continuation exhausted"))
        assertFails { result[1].copy(meanTerminalPayoff = -1.0) }
        assertFails { result[2].copy(disposition = TerminalRootActionDisposition.COMPLETE) }
    }

    @Test fun `nonfinite values sample reordering and duplicate actions are refused`() {
        assertFails { sample(0, Double.NaN) }
        assertFails { sample(0, Double.POSITIVE_INFINITY) }
        assertFails { collectTerminalRootActions(listOf(action("a"), action("a")), 1) { _, i -> sample(i, 0.0) } }
        val result = collectTerminalRootActions(listOf(action("a")), 2) { _, i -> sample(i + 1, 0.0) }
        assertEquals(TerminalRootActionDisposition.NON_GAME_FAILURE, result.single().disposition)
        assertNull(result.single().meanTerminalPayoff)
        assertTrue(result.single().samples.isEmpty())
    }

    @Test fun `terminal mode has a separate explicit budget and preserves historical plan bytes`() {
        val policy = PositionBankScreenPolicy(SearchTeacherCalibrationPolicy("reference", 8, 64, 32, 1.4, true, 1.0), MonoRedVisibleEvaluatorConfig())
        val plain = PositionBankScreenPlan(bankDirectory = "/tmp/bank", expectedBankIdentity = "bank", partition = PositionBankScreenPartition.DEVELOPMENT,
            mode = PositionBankScreenMode.SEARCH, rootLimit = 2, repetitions = 2, policies = listOf(policy))
        assertFalse("terminalContinuation" in evidenceJson.encodeToString(plain))
        val config = TerminalRootContinuationConfig(8, maximumTotalContinuations = 128)
        assertEquals(128, terminalRootWorkload(config, listOf(3, 5), 2, 1))
        assertFails { terminalRootWorkload(config, listOf(3, 6), 2, 1) }
        assertFails { terminalRootWorkload(config, listOf(Int.MAX_VALUE), 2, 1) }
        assertFails { plain.copy(terminalContinuation = config) }
        assertFails { plain.copy(mode = PositionBankScreenMode.TERMINAL_CONTINUATIONS) }
        val terminal = plain.copy(mode = PositionBankScreenMode.TERMINAL_CONTINUATIONS, terminalContinuation = config)
        assertEquals(terminal, evidenceJson.decodeFromString<PositionBankScreenPlan>(evidenceJson.encodeToString(terminal)))
        assertFails { config.copy(seedRule = "unknown") }
    }
}
