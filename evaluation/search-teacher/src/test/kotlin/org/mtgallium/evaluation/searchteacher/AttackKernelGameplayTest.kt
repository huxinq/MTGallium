package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag

@Tag("public-source")
class AttackKernelGameplayTest {
    @Test fun `game cost ceiling counts total search instead of faster individual decisions`() {
        val costs = listOf(ContinuationPolicyCost("control", 16, 100, 1000.0),
            ContinuationPolicyCost("candidate", 16, 200, 1200.0))
        assertTrue(costs[1].meanSearchedDecisionMillis!! < costs[0].meanSearchedDecisionMillis!!)
        assertEquals(1.2, attackGameplayCostRatio(costs))
        assertNull(attackGameplayCostRatio(listOf(ContinuationPolicyCost("control", 0, 0, 0.0),
            ContinuationPolicyCost("candidate", 0, 0, 0.0))))
    }

    @Test fun `fresh parity process retains first crossing and batch overshoot without new capital`() {
        val rule = attackGameplayRule()
        val scores = mutableListOf<PairedSequentialScore>()
        while (true) {
            val range = continuationChunk(rule, scores, 0, 8) ?: break
            scores += range.map { PairedSequentialScore(it, 1.0) }
        }
        val result = pairedSequentialTest(rule, scores, 0)
        assertEquals(PairedSequentialDisposition.ABOVE_NULL, result.disposition)
        assertTrue(pairedMeanConfidenceSequence(rule, scores.take(result.validScoredPairs).map { it.pointRate!! }).lower > .5)
        assertEquals(scores.size - result.validScoredPairs, result.operationalOvershootPairs)
        assertNull(continuationChunk(rule, scores, 0, 8))
        val invalid = listOf(PairedSequentialScore(0, null, listOf("stopped")))
        assertEquals(PairedSequentialDisposition.INVALID_PAIR, pairedSequentialTest(rule, invalid, 0).disposition)
        assertNull(continuationChunk(rule, invalid, 0, 8))
    }
}
