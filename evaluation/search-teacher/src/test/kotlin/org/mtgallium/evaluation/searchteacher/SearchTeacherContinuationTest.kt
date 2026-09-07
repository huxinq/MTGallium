package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.research.run.ResearchBuildReference
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings

@Tag("public-source")
class SearchTeacherContinuationTest {
    private val original = PairedSequentialRule(nullPointRate = .5, targetPointRate = .5,
        falsePositiveRate = .025, falseNegativeRate = .025, maximumPairs = 16,
        betFractions = listOf(.2, .5, .8), stopForFutility = true)
    private val offset = 7
    private val supplied = List(16) { PairedSequentialScore(offset + it, if (it % 2 == 0) 1.0 else 0.0) }
    private val stopped = pairedSequentialTest(original, supplied, offset)
    private val prefix = supplied.take(stopped.inspectedPairs)
    private val parent = pairedSequentialTest(original, prefix, offset)

    @Test fun `optional continuation preserves every capital and authenticates original stopped result`() {
        assertEquals(PairedSequentialDisposition.FUTILITY, parent.disposition)
        val larger = continuationRule(original, parent, prefix, offset, 64)
        val resumed = pairedSequentialTest(larger, prefix, offset)
        assertEquals(parent.upperLogCapitals, resumed.upperLogCapitals)
        assertEquals(parent.lowerLogCapitals, resumed.lowerLogCapitals)
        assertEquals(parent.orderedPrefixSha256, resumed.orderedPrefixSha256)
        assertEquals(PairedSequentialDisposition.CONTINUE, resumed.disposition)
        assertEquals(original, parent.rule)
        assertFails { continuationRule(original, parent.copy(logUpperMixture = 1.0), prefix, offset, 64) }
        assertFails { continuationRule(original, parent, prefix.drop(1), offset, 64) }
        assertFails { continuationRule(original, parent, prefix, offset, 16) }
        assertFails { continuationRule(original, parent.copy(operationalOvershootPairs = 1), prefix, offset, 64) }
        assertFails { continuationRule(original, parent.copy(disposition = PairedSequentialDisposition.ABOVE_NULL), prefix, offset, 64) }
    }

    @Test fun `dispatcher starts at next unseen index and stops cumulative process including batch overshoot`() {
        val rule = continuationRule(original, parent, prefix, offset, 64)
        val scores = prefix.toMutableList()
        assertEquals(offset + prefix.size, continuationChunk(rule, scores, offset, 4)!!.first)
        var batches = 0
        while (true) {
            val range = continuationChunk(rule, scores, offset, 4) ?: break
            scores += range.map { PairedSequentialScore(it, 1.0) }
            batches++
        }
        val result = pairedSequentialTest(rule, scores, offset)
        assertTrue(batches > 0)
        assertEquals(PairedSequentialDisposition.ABOVE_NULL, result.disposition)
        assertEquals(scores.size - result.inspectedPairs, result.operationalOvershootPairs)
        assertTrue(result.operationalOvershootPairs in 0..3)
        assertNull(continuationChunk(rule, scores, offset, 4))
        assertNotEquals(result.upperLogCapitals, pairedSequentialTest(rule, scores.drop(prefix.size), offset + prefix.size).upperLogCapitals)
    }

    @Test fun `invalid child pair stops without assigning an outcome and cap bounds final batch`() {
        val rule = continuationRule(original, parent, prefix, offset, 64)
        val scores = prefix + PairedSequentialScore(offset + prefix.size, null, listOf("non-game failure"))
        val result = pairedSequentialTest(rule, scores, offset)
        assertEquals(PairedSequentialDisposition.INVALID_PAIR, result.disposition)
        assertEquals(prefix.size, result.validScoredPairs)
        assertNull(continuationChunk(rule, scores, offset, 4))
        val noFutility = rule.copy(maximumPairs = prefix.size + 2, stopForFutility = false)
        assertEquals(2, continuationChunk(noFutility, prefix, offset, 4)!!.count())
        assertFails { continuationChunk(rule, prefix + prefix.last(), offset, 4) }
    }

    @Test fun `new epoch cost gate exposes regression despite favorable historical timing`() {
        val parent = continuationCosts("parent", listOf(ContinuationPolicyCost("control", 100, 100, 1000.0),
            ContinuationPolicyCost("candidate", 100, 100, 500.0)))
        val newer = continuationCosts("new", listOf(ContinuationPolicyCost("control", 10, 10, 100.0),
            ContinuationPolicyCost("candidate", 10, 10, 110.0)))
        assertTrue(parent.costGatePassed)
        assertFalse(newer.costGatePassed)
        assertEquals(1.1, newer.meanSearchedDecisionCostRatio)
        assertFalse(continuationCosts("empty", listOf(ContinuationPolicyCost("control", 0, 0, 0.0),
            ContinuationPolicyCost("candidate", 0, 0, 0.0))).costGatePassed)
    }
    @Test fun `NI continuation preserves original confidence sequence and admits ordered parent overshoot once`() {
        val ni = original.copy(nullPointRate = .48, targetPointRate = .52, maximumPairs = 208,
            stopForFutility = false, practicalAcceptance = PairedPracticalAcceptance(.02, PairedPracticalObjective.NON_INFERIOR))
        val available = List(208) { PairedSequentialScore(offset + it, if (it % 5 < 3) 1.0 else 0.0) }
        val firstStop = pairedSequentialTest(ni, available, offset)
        assertEquals(PairedSequentialDisposition.NON_INFERIOR, firstStop.disposition)
        val executed = available.take(firstStop.inspectedPairs + 2)
        val stoppedNi = pairedSequentialTest(ni, executed, offset)
        assertEquals(2, stoppedNi.operationalOvershootPairs)
        val parity = superiorityContinuationRule(ni, stoppedNi, executed, offset, 1024)
        assertEquals(ni.betFractions, parity.betFractions)
        assertEquals(ni.falsePositiveRate, parity.falsePositiveRate)
        assertEquals(ni.falseNegativeRate, parity.falseNegativeRate)
        assertEquals(pairedMeanConfidenceSequence(ni, executed.map { it.pointRate!! }),
            pairedMeanConfidenceSequence(parity, executed.map { it.pointRate!! }))
        assertEquals(PairedSequentialDisposition.CONTINUE, pairedSequentialTest(parity, executed, offset).disposition)
        assertEquals(offset + executed.size, continuationChunk(parity, executed, offset, 8)!!.first)
        val combined = executed.toMutableList()
        while (true) {
            val range = continuationChunk(parity, combined, offset, 8) ?: break
            combined += range.map { PairedSequentialScore(it, 1.0) }
        }
        val superior = pairedSequentialTest(parity, combined, offset)
        assertEquals(PairedSequentialDisposition.ABOVE_NULL, superior.disposition)
        assertTrue(pairedMeanConfidenceSequence(parity, combined.take(superior.validScoredPairs).map { it.pointRate!! }).lower > .5)
        assertEquals(PairedSequentialDisposition.NON_INFERIOR, stoppedNi.disposition)
        assertEquals(ni, stoppedNi.rule)
        assertFails { continuationRule(ni, stoppedNi, executed, offset, 1024) }
        assertFails { superiorityContinuationRule(ni, stoppedNi, executed.dropLast(1), offset, 1024) }
        assertFails { superiorityContinuationRule(ni, stoppedNi, executed.reversed(), offset, 1024) }
        assertFails { superiorityContinuationRule(ni, stoppedNi.copy(logUpperMixture = 0.0), executed, offset, 1024) }
        assertFails { superiorityContinuationRule(ni, stoppedNi.copy(confidenceSequence = null), executed, offset, 1024) }
        assertFails { superiorityContinuationRule(ni, stoppedNi, executed + PairedSequentialScore(offset + executed.size, null, listOf("failure")), offset, 1024) }
        assertFails { superiorityContinuationRule(ni.copy(practicalAcceptance = null), stoppedNi, executed, offset, 1024) }
    }

    @Test fun `parity already crossed in inherited pairs finalizes a verified zero-child report`(@TempDir directory: Path) {
        val ni = original.copy(nullPointRate = .48, targetPointRate = .52, maximumPairs = 208,
            stopForFutility = false, practicalAcceptance = PairedPracticalAcceptance(.02, PairedPracticalObjective.NON_INFERIOR))
        val available = List(208) { PairedSequentialScore(offset + it, 1.0) }
        val niStop = pairedSequentialTest(ni, available, offset)
        val executed = available.take(niStop.inspectedPairs + 2)
        val parent = pairedSequentialTest(ni, executed, offset)
        assertEquals(2, parent.operationalOvershootPairs)
        val rule = superiorityContinuationRule(ni, parent, executed, offset, 1024)
        val parity = pairedSequentialTest(rule, executed, offset)
        assertEquals(PairedSequentialDisposition.ABOVE_NULL, parity.disposition)
        assertNull(continuationChunk(rule, executed, offset, 8))
        val tree = PolicySourceTreeState("synthetic", "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val source = PolicySourceProvenance(expectedArgentumRevision = "synthetic", outer = tree, argentum = tree)
        val plan = SearchTeacherContinuationPlan(directory.resolve("parent").toString(), "synthetic-parent",
            "a".repeat(64), parent.orderedPrefixSha256, "synthetic",
            ResearchBuildReference(directory.resolve("build").toString(), "synthetic-build", "b".repeat(64)),
            "Synthetic same-source fixture", 1024, 8, seekSuperiority = true)
        assertFalse(evidenceJson.encodeToString(SearchTeacherContinuationPlan.serializer(), plan.copy(seekSuperiority = false)).contains("seekSuperiority"))
        val cost = listOf(ContinuationPolicyCost("control", 2, 2, 4.0), ContinuationPolicyCost("candidate", 2, 2, 2.0))
        val epochs = listOf(continuationCosts("parent", cost), continuationCosts("new",
            cost.map { it.copy(games = 0, searchedDecisions = 0, searchMillis = 0.0) }), continuationCosts("combined", cost))
        val identity = ResearchRunBindings(protocol = "synthetic-continuation", material = mapOf("fixture" to "zero-child")).identity
        val report = continuationReport(plan, identity, source, source, parent, executed.size,
            executed, emptyList(), parity, true, emptyList(), epochs)
        assertEquals(0, report.newExecutedPairs)
        assertEquals(0, report.newInspectedPairs)
        assertEquals(executed.size - parity.inspectedPairs, report.result.operationalOvershootPairs)
        assertTrue(report.result.confidenceSequence!!.lower > .5)
        assertEquals(parent, report.parentResult)
        assertTrue(report.chunks.isEmpty())
        assertFalse(report.strengthAndCostGatePassed)
        writeJsonAtomically(directory.resolve("plan.json"), plan)
        retainContinuationReport(directory, report)
        val verified = ResearchRunArtifacts.loadAndVerify(directory, identity)
        assertEquals(setOf("plan.json", "report.json", "progress.json"), verified.artifacts.map { it.relativePath }.toSet())
        assertEquals(report.result, readEvidenceJson(directory.resolve("progress.json"), PairedSequentialResult.serializer()))
        assertEquals(report, readEvidenceJson(directory.resolve("report.json"), SearchTeacherContinuationReport.serializer()))
        retainContinuationReport(directory, report) // Finalized replay verifies; it never dispatches a child.
    }

}
