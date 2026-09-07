package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

@Tag("public-source")
class PairedPracticalAcceptanceTest {
    private val rule = PairedSequentialRule(
        nullPointRate = .48, targetPointRate = .52,
        falsePositiveRate = .025, falseNegativeRate = .025, maximumPairs = 300,
        betFractions = listOf(.05, .1, .2, .4, .8),
        practicalAcceptance = PairedPracticalAcceptance(.02, PairedPracticalObjective.EQUIVALENT),
    )

    @Test
    fun `exactly split pairs can establish practical equivalence and preserve the first stopping prefix`() {
        val scores = List(300) { PairedSequentialScore(20 + it, .5) }
        val result = pairedSequentialTest(rule, scores, 20)
        assertEquals(PairedSequentialDisposition.PRACTICALLY_EQUIVALENT, result.disposition)
        assertTrue(result.inspectedPairs < 300)
        val interval = requireNotNull(result.confidenceSequence)
        assertTrue(interval.lower > .48 && interval.upper < .52)
        assertEquals(.95, interval.simultaneousCoverageAtLeast, 1e-14)
        assertEquals(result.validScoredPairs, interval.validScoredPairs)
        assertTrue(interval.maximumCompatibleDistanceFromParity < .02)
        val stopped = scores.take(result.inspectedPairs)
        val withInvalidOvershoot = pairedSequentialTest(rule,
            stopped + PairedSequentialScore(20 + stopped.size, null, listOf("unsupported")), 20)
        assertEquals(result.confidenceSequence, withInvalidOvershoot.confidenceSequence)
        assertEquals(result.orderedPrefixSha256, withInvalidOvershoot.orderedPrefixSha256)
        assertEquals(1, withInvalidOvershoot.operationalOvershootPairs)
        val nonInferior = pairedSequentialTest(rule.copy(practicalAcceptance =
            PairedPracticalAcceptance(.02, PairedPracticalObjective.NON_INFERIOR)), scores, 20)
        assertEquals(PairedSequentialDisposition.NON_INFERIOR, nonInferior.disposition)
        assertEquals(result.inspectedPairs, nonInferior.inspectedPairs)
    }

    @Test
    fun `one bound alone never establishes equivalence but the lower bound establishes non inferiority`() {
        for (value in listOf(0.0, 1.0)) {
            val scores = List(300) { PairedSequentialScore(it, value) }
            val equivalent = pairedSequentialTest(rule, scores, 0)
            assertEquals(PairedSequentialDisposition.BUDGET_EXHAUSTED, equivalent.disposition)
            val nonInferior = pairedSequentialTest(rule.copy(practicalAcceptance =
                PairedPracticalAcceptance(.02, PairedPracticalObjective.NON_INFERIOR)), scores, 0)
            assertEquals(if (value == 1.0) PairedSequentialDisposition.NON_INFERIOR
                else PairedSequentialDisposition.BUDGET_EXHAUSTED, nonInferior.disposition)
            if (value == 1.0) assertTrue(requireNotNull(nonInferior.confidenceSequence).lower > .48)
        }
    }

    @Test
    fun `missing and invalid pairs cannot acquire a score or establish acceptance`() {
        val empty = pairedSequentialTest(rule, emptyList(), 0)
        assertEquals(PairedSequentialDisposition.CONTINUE, empty.disposition)
        assertEquals(0.0, empty.confidenceSequence?.lower)
        assertEquals(1.0, empty.confidenceSequence?.upper)
        val invalid = pairedSequentialTest(rule, listOf(PairedSequentialScore(0, .5),
            PairedSequentialScore(1, null, listOf("stopped")), PairedSequentialScore(2, 1.0)), 0)
        assertEquals(PairedSequentialDisposition.INVALID_PAIR, invalid.disposition)
        assertEquals(1, invalid.confidenceSequence?.validScoredPairs)
        assertEquals(1, invalid.operationalOvershootPairs)
    }

    @Test
    fun `confidence sequence has joint optional stopping coverage over exhaustive binary prefixes`() {
        val bounded = rule.copy(falsePositiveRate = .2, falseNegativeRate = .2,
            maximumPairs = 9, betFractions = listOf(.2, .8))
        // Enumerate every possible first failure, weighting by its actual null probability.
        // Checking only the final interval would miss repeated-inspection inflation.
        for (mean in listOf(.25, .48, .5, .52, .75)) {
            fun failureMass(prefix: List<Double>, probability: Double): Double {
                val interval = pairedMeanConfidenceSequence(bounded, prefix)
                if (mean < interval.lower || mean > interval.upper) return probability
                if (prefix.size == bounded.maximumPairs) return 0.0
                return failureMass(prefix + 0.0, probability * (1 - mean)) +
                    failureMass(prefix + 1.0, probability * mean)
            }
            assertTrue(failureMass(emptyList(), 1.0) <= .4 + 1e-12)
        }
    }

    @Test
    fun `practical configuration is explicit and legacy rule and result bytes are unchanged`() {
        val legacyRule = rule.copy(practicalAcceptance = null)
        val encodedRule = evidenceJson.encodeToString(legacyRule)
        assertFalse("practicalAcceptance" in encodedRule)
        val legacyResult = pairedSequentialTest(legacyRule, emptyList(), 0)
        val encodedResult = evidenceJson.encodeToString(legacyResult)
        assertFalse("confidenceSequence" in encodedResult)
        assertEquals(legacyRule, evidenceJson.decodeFromString<PairedSequentialRule>(encodedRule))
        assertEquals(legacyResult, evidenceJson.decodeFromString<PairedSequentialResult>(encodedResult))
        assertEquals(rule, evidenceJson.decodeFromString<PairedSequentialRule>(evidenceJson.encodeToString(rule)))
        assertFailsWith<IllegalArgumentException> { rule.copy(nullPointRate = .5) }
        assertFailsWith<IllegalArgumentException> { rule.copy(stopForFutility = true) }
        assertFailsWith<IllegalArgumentException> { PairedPracticalAcceptance(0.0, PairedPracticalObjective.EQUIVALENT) }
        assertFailsWith<IllegalArgumentException> { PairedPracticalAcceptance(Double.NaN, PairedPracticalObjective.EQUIVALENT) }
    }
}
