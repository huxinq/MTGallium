package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Tag("public-source")
class TerminalTargetSensitivityTest {
    @Test fun `target rank audit separates strict reversals ties and best-set changes`() {
        val base = linkedMapOf("a" to 1.0, "b" to 0.0, "c" to -1.0)
        val changed = linkedMapOf("a" to 0.0, "b" to 1.0, "c" to 0.0)
        val r = targetRankingChange("r", "g", base, changed)
        assertEquals(3, r.actionPairs)
        assertEquals(1, r.reversedStrictPairs)
        assertEquals(1, r.tiedAtEitherEndPairs)
        assertEquals(listOf("a"), r.baselineBestActions)
        assertEquals(listOf("b"), r.variantBestActions)
        assertFails { targetRankingChange("r", "g", base, changed - "c") }
    }

    @Test fun `nested prefixes retain actual sample order and cannot salvage a failed root`() {
        val action = SemanticChoice.create(kind = SemanticChoiceKind.ACTION, operationFamily = SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay("pass"), canonicalPayload = buildJsonObject { put("test", "pass") })
        val policy = SearchTeacherCalibrationPolicy("target", 8, 64, 32, 1.4, true, 1.0)
        val plan = PositionBankScreenPlan(bankDirectory = "/tmp/bank", expectedBankIdentity = "bank", partition = PositionBankScreenPartition.VALIDATION,
            mode = PositionBankScreenMode.TERMINAL_CONTINUATIONS, rootLimit = 1, repetitions = 1,
            policies = listOf(PositionBankScreenPolicy(policy, MonoRedVisibleEvaluatorConfig())), terminalContinuation = TerminalRootContinuationConfig(4, maximumTotalContinuations = 100))
        val samples = listOf(1.0, 1.0, -1.0, -1.0).mapIndexed { i, value -> TerminalRootSample(i, 0, 0, 0, value, 0, OpponentPolicyDecisionSummary(), OpponentPolicyDecisionSummary(), 0.0) }
        val row = PositionBankScreenRow("r", "target", "evaluator", 0, PositionBankScreenDisposition.TERMINAL_CONTINUATIONS, 0.0, 0.0,
            terminalRootActions = listOf(TerminalRootActionSamples(action, 4, samples, TerminalRootActionDisposition.COMPLETE)))
        val tree = PolicySourceTreeState("synthetic", "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val report = PositionBankScreenReport(researchRunIdentity = "synthetic", sourceProvenance = PolicySourceProvenance(expectedArgentumRevision = "synthetic", outer = tree, argentum = tree),
            generatedAtUtc = "synthetic", plan = plan, workerThreads = 1, eligibleRoots = 1, selectedRootIds = listOf("r"), rows = listOf(row), valid = true)
        assertEquals(1.0, terminalTargetPrefixValues(report, "r", 2).single().getValue(action.signature))
        assertEquals(0.0, terminalTargetPrefixValues(report, "r", 4).single().getValue(action.signature))
        assertFails { terminalTargetPrefixValues(report, "r", 5) }
        assertFails { terminalTargetPrefixValues(report.copy(rows = listOf(row.copy(disposition = PositionBankScreenDisposition.REFUSED))), "r", 2) }
    }
}
