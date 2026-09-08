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

        val alternative = SemanticChoice.create(kind = SemanticChoiceKind.ACTION, operationFamily = SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay("other"), canonicalPayload = buildJsonObject { put("test", "other") })
        val alternativeSamples = samples.map { it.copy(payoff = -it.payoff) }
        val twoActions = row.copy(terminalRootActions = row.terminalRootActions +
            TerminalRootActionSamples(alternative, 4, alternativeSamples, TerminalRootActionDisposition.COMPLETE))
        val rootIds = listOf("r1", "r2", "r3")
        val repeatedRows = rootIds.flatMap { rootId ->
            listOf(twoActions.copy(rootId = rootId), twoActions.copy(rootId = rootId, repetition = 1))
        }
        val gridReport = report.copy(plan = plan.copy(rootLimit = 3, rootIds = rootIds, repetitions = 2),
            selectedRootIds = rootIds, eligibleRoots = 3, rows = repeatedRows)
        val reference = SavedRootPolicyInput("/tmp/targets", "synthetic", "target")
        val targets = linkedMapOf("baseline" to TerminalTargetStage(reference, gridReport),
            "variant" to TerminalTargetStage(reference, gridReport))
        val frozen = rootIds.map { rootId ->
            // The larger group favors one fixed candidate; the smaller group favors the other.
            FrozenTerminalSensitivityRoot(rootId, if (rootId == "r3") "g2" else "g1",
                if (rootId == "r3") action.signature else alternative.signature,
                if (rootId == "r3") alternative.signature else action.signature,
                action.signature, mapOf(action.signature to 0.0, alternative.signature to 0.0))
        }
        val cells = terminalSensitivityGrid(targets, listOf(2, 4), frozen)
        assertEquals(listOf("baseline", "baseline", "variant", "variant"), cells.map { it.variantId })
        assertEquals(listOf(2, 4, 2, 4), cells.map { it.samplesPerAction })
        assertEquals(listOf(0.0, 0.0), cells.first().candidateVersusOld.equalGroupMeanByTargetRepetition)
        assertEquals(0.0, cells.first().candidateVersusOld.equalGroupMeanDifference)
        assertEquals(listOf(-2.0, -2.0), cells.first().candidateVersusOld.rows.first().candidateMinusBaselineByReferenceRepetition)
        assertEquals(listOf(0.0, 0.0), cells[1].candidateVersusOld.rows.first().candidateMinusBaselineByReferenceRepetition)
        assertEquals(cells.first().candidateVersusOld, cells[2].candidateVersusOld)
        assertTrue(cells.all { cell -> cell.rankings.all { it.baselineBestActions.size == 2 } })
        assertFails { terminalSensitivityGrid(mapOf("duplicate" to TerminalTargetStage(reference,
            gridReport.copy(rows = repeatedRows + repeatedRows.first()))), listOf(2), frozen) }
    }
}
