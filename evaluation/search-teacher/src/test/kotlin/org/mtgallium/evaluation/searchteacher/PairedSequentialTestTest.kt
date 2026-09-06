package org.mtgallium.evaluation.searchteacher

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

@Tag("public-source")
class PairedSequentialTestTest {
    private val rule = PairedSequentialRule(nullPointRate = 0.5, targetPointRate = 0.6,
        falsePositiveRate = 0.05, falseNegativeRate = 0.05, maximumPairs = 100)

    @Test
    fun `normalized capital starts at one and split pairs are scored atomically`() {
        val empty = pairedSequentialTest(rule, emptyList(), 70)
        assertEquals(0.0, empty.logUpperMixture)
        assertEquals(0.0, empty.logLowerMixture)
        assertEquals(PairedSequentialDisposition.CONTINUE, empty.disposition)
        val split = pairedSequentialTest(rule, List(100) { PairedSequentialScore(70 + it, 0.5) }, 70)
        assertEquals(0.0, split.logUpperMixture)
        assertEquals(PairedSequentialDisposition.BELOW_TARGET, split.disposition)
        assertTrue(split.inspectedPairs < 100)
        assertEquals(100 - split.inspectedPairs, split.operationalOvershootPairs)
    }

    @Test
    fun `first crossing survives parallel overshoot including later invalid games`() {
        val wins = List(20) { PairedSequentialScore(it, 1.0) }
        val prefix = pairedSequentialTest(rule, wins, 0)
        assertEquals(PairedSequentialDisposition.ABOVE_NULL, prefix.disposition)
        val later = pairedSequentialTest(rule, wins + PairedSequentialScore(20, null, listOf("stopped")), 0)
        assertEquals(prefix.disposition, later.disposition)
        assertEquals(prefix.orderedPrefixSha256, later.orderedPrefixSha256)
        assertEquals(prefix.logUpperMixture, later.logUpperMixture)
        assertEquals(prefix.operationalOvershootPairs + 1, later.operationalOvershootPairs)
        assertEquals(emptyList(), later.invalidReasons)
    }

    @Test
    fun `invalid pair before a boundary stops without strategic score or skipped index`() {
        val result = pairedSequentialTest(rule, listOf(PairedSequentialScore(4, 0.75),
            PairedSequentialScore(5, null, listOf("unsupported")), PairedSequentialScore(6, 1.0)), 4)
        assertEquals(PairedSequentialDisposition.INVALID_PAIR, result.disposition)
        assertEquals(2, result.inspectedPairs)
        assertEquals(1, result.validScoredPairs)
        assertEquals(listOf("unsupported"), result.invalidReasons)
        assertFailsWith<IllegalArgumentException> {
            pairedSequentialTest(rule, listOf(PairedSequentialScore(4, 1.0), PairedSequentialScore(6, 1.0)), 4)
        }
    }

    @Test
    fun `exhaustive binary path probabilities respect both optional-stopping error bounds`() {
        val horizon = 12
        val bounded = rule.copy(maximumPairs = horizon, falsePositiveRate = 0.2, falseNegativeRate = 0.2)
        var falseAbove = 0.0
        var falseBelow = 0.0
        for (bits in 0 until (1 shl horizon)) {
            val scores = List(horizon) { index -> PairedSequentialScore(index, ((bits shr index) and 1).toDouble()) }
            val result = pairedSequentialTest(bounded, scores, 0)
            val wins = Integer.bitCount(bits)
            if (result.disposition in setOf(PairedSequentialDisposition.ABOVE_NULL,
                    PairedSequentialDisposition.BOTH_BOUNDARIES_CROSSED)) falseAbove += 0.5.pow(horizon)
            if (result.disposition in setOf(PairedSequentialDisposition.BELOW_TARGET,
                    PairedSequentialDisposition.BOTH_BOUNDARIES_CROSSED)) {
                falseBelow += 0.6.pow(wins) * 0.4.pow(horizon - wins)
            }
        }
        assertTrue(falseAbove <= bounded.falsePositiveRate + 1e-12)
        assertTrue(falseBelow <= bounded.falseNegativeRate + 1e-12)
        assertTrue(falseAbove > 0 && falseBelow > 0)
    }

    @Test
    fun `fractional scores stay valid and budget exhaustion is inconclusive`() {
        val result = pairedSequentialTest(rule.copy(maximumPairs = 4),
            listOf(0.25, 0.75, 0.5, 0.5).mapIndexed { i, score -> PairedSequentialScore(i, score) }, 0)
        assertEquals(PairedSequentialDisposition.BUDGET_EXHAUSTED, result.disposition)
        assertEquals(4, result.validScoredPairs)
        assertFailsWith<IllegalArgumentException> { rule.copy(betFractions = listOf(1.0)) }
        assertFailsWith<IllegalArgumentException> { rule.copy(nullPointRate = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { rule.copy(nullPointRate = Double.MIN_VALUE) }
        assertFailsWith<IllegalArgumentException> { rule.copy(nullPointRate = .6, targetPointRate = .5) }
        assertFailsWith<IllegalArgumentException> { PairedSequentialScore(0, null) }
    }

    @Test
    fun `equal boundaries stop obvious directions by complete pairs and retain one worker-pair overshoot`() {
        val symmetric = PairedSequentialRule(
            nullPointRate = .5,
            targetPointRate = .5,
            falsePositiveRate = .025,
            falseNegativeRate = .025,
            maximumPairs = 12,
            betFractions = listOf(.8),
        )
        listOf(1.0 to PairedSequentialDisposition.ABOVE_NULL, 0.0 to PairedSequentialDisposition.BELOW_TARGET)
            .forEach { (score, expected) ->
                val calls = java.util.Collections.synchronizedList(mutableListOf<Int>())
                val execution = executePairedSequentialSchedule(symmetric, 0, 2) { index ->
                    calls += index
                    pair(index, score)
                }
                assertEquals((0..7).toList(), calls.sorted())
                assertEquals(expected, execution.result.disposition)
                assertEquals(7, execution.result.inspectedPairs)
                assertEquals(1, execution.result.operationalOvershootPairs)
                assertEquals(SearchTeacherSequentialPopulation(12, 8, 7, 4, 1, 0, 0), execution.population)
            }
    }

    @Test
    fun `equal boundaries are inconclusive for split pairs and preserve invalid pair semantics`() {
        val symmetric = PairedSequentialRule(
            nullPointRate = .5,
            targetPointRate = .5,
            falsePositiveRate = .025,
            falseNegativeRate = .025,
            maximumPairs = 12,
            betFractions = listOf(.8),
        )
        val split = pairedSequentialTest(symmetric,
            List(12) { index -> PairedSequentialScore(index, if (index % 2 == 0) 1.0 else 0.0) }, 0)
        val invalid = pairedSequentialTest(symmetric, listOf(
            PairedSequentialScore(0, 1.0), PairedSequentialScore(1, null, listOf("stopped"))), 0)

        assertEquals(PairedSequentialDisposition.BUDGET_EXHAUSTED, split.disposition)
        assertEquals(PairedSequentialDisposition.INVALID_PAIR, invalid.disposition)
        assertEquals(2, invalid.inspectedPairs)
        assertEquals(1, invalid.validScoredPairs)
    }

    @Test
    fun `equal boundary directional errors respect each rate and their combined bound`() {
        val horizon = 12
        val symmetric = PairedSequentialRule(
            nullPointRate = .5,
            targetPointRate = .5,
            falsePositiveRate = .025,
            falseNegativeRate = .025,
            maximumPairs = horizon,
            betFractions = listOf(.8),
        )
        var above = 0.0
        var below = 0.0
        var either = 0.0
        for (bits in 0 until (1 shl horizon)) {
            val result = pairedSequentialTest(symmetric,
                List(horizon) { index -> PairedSequentialScore(index, ((bits shr index) and 1).toDouble()) }, 0)
            val probability = .5.pow(horizon)
            val crossesAbove = result.disposition in setOf(
                PairedSequentialDisposition.ABOVE_NULL,
                PairedSequentialDisposition.BOTH_BOUNDARIES_CROSSED,
            )
            val crossesBelow = result.disposition in setOf(
                PairedSequentialDisposition.BELOW_TARGET,
                PairedSequentialDisposition.BOTH_BOUNDARIES_CROSSED,
            )
            if (crossesAbove) above += probability
            if (crossesBelow) below += probability
            if (crossesAbove || crossesBelow) either += probability
        }
        assertTrue(above <= symmetric.falsePositiveRate + 1e-12)
        assertTrue(below <= symmetric.falseNegativeRate + 1e-12)
        assertTrue(either <= symmetric.falsePositiveRate + symmetric.falseNegativeRate + 1e-12)
    }
    @Test
    fun `worker chunks stop after first crossed prefix and retain bounded overshoot`() {
        val stopping = rule.copy(falsePositiveRate = .5, falseNegativeRate = .001, betFractions = listOf(.8))
        val calls = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val execution = executePairedSequentialSchedule(stopping, 40, 4) { index ->
            calls += index
            pair(index, 1.0)
        }
        assertEquals(listOf(40, 41, 42, 43), calls.sorted())
        assertEquals(PairedSequentialDisposition.ABOVE_NULL, execution.result.disposition)
        assertEquals(2, execution.result.inspectedPairs)
        assertEquals(2, execution.result.operationalOvershootPairs)
        assertEquals(SearchTeacherSequentialPopulation(100, 4, 2, 96, 2, 0, 0), execution.population)
        assertTrue(execution.valid)
    }

    @Test
    fun `invalid prefix stops while invalid overshoot has separate operational validity`() {
        val execution = executePairedSequentialSchedule(rule, 10, 4) { index -> pair(index, if (index == 11) null else .5) }
        assertEquals(PairedSequentialDisposition.INVALID_PAIR, execution.result.disposition)
        assertEquals(4, execution.pairs.size)
        assertFalse(execution.valid)
        val stopping = rule.copy(falsePositiveRate = .5, falseNegativeRate = .001, betFractions = listOf(.8))
        val overshoot = executePairedSequentialSchedule(stopping, 10, 4) { index -> pair(index, if (index == 12) null else 1.0) }
        assertEquals(PairedSequentialDisposition.ABOVE_NULL, overshoot.result.disposition)
        assertEquals(2, overshoot.result.inspectedPairs)
        assertEquals(4, overshoot.pairs.size)
        assertTrue(overshoot.valid, "Post-stop work cannot invalidate the already frozen inferential prefix")
        assertFalse(overshoot.operationalValid)
        assertEquals(SearchTeacherSequentialPopulation(100, 4, 2, 96, 2, 0, 1), overshoot.population)
        assertTrue(overshoot.pairs.drop(overshoot.result.inspectedPairs).any { !it.valid })
    }

    @Test
    fun `last worker chunk honors prospective cap and ordered pair coordinates`() {
        val execution = executePairedSequentialSchedule(rule.copy(maximumPairs = 5), 17, 3) { pair(it, .5) }
        assertEquals((17..21).toList(), execution.pairs.map { it.pairIndex })
        assertEquals(PairedSequentialDisposition.BUDGET_EXHAUSTED, execution.result.disposition)
        assertEquals(0, execution.result.operationalOvershootPairs)
        assertFailsWith<IllegalArgumentException> {
            executePairedSequentialSchedule(rule, 17, 1) { pair(it + 1, .5) }
        }
    }

    /** Synthetic score-provider witness only; no game/replay evidence is produced by these scheduling tests. */
    private fun pair(index: Int, pointRate: Double?) = SearchBudgetFrontierPair(index, index.toLong(), emptyList(),
        pointRate != null, if (pointRate == null) listOf("synthetic-invalid-pair") else emptyList(), pointRate?.times(2))

}
