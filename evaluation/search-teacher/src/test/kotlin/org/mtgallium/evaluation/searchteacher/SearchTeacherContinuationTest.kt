package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag

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
}
