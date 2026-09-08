package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.ComponentSeeds

@Tag("public-source")
class PairIndexBootstrapTest {
    @Test
    fun `fractional pair scores receive a deterministic bounded block-bootstrap interval`() {
        val scores = listOf(0.25, 0.5, 0.5, 0.75).mapIndexed { pairIndex, value ->
            TournamentPairIndexScore(pairIndex, value)
        }
        val interval = pairIndexBootstrapInterval(scores, seed = 17L, samples = 2_000)

        assertEquals(interval, pairIndexBootstrapInterval(scores, seed = 17L, samples = 2_000))
        assertTrue(interval.first in 0.0..0.5)
        assertTrue(interval.second in 0.5..1.0)
        assertTrue(interval.first < 0.5 && interval.second > 0.5)
    }

    @Test
    fun `block-bootstrap interval has reasonable exact coverage for bounded pair outcomes`() {
        val blockCount = 8
        var coveredDatasets = 0
        repeat(1 shl blockCount) { mask ->
            val scores = List(blockCount) { pairIndex ->
                TournamentPairIndexScore(pairIndex, ((mask shr pairIndex) and 1).toDouble())
            }
            val interval = pairIndexBootstrapInterval(
                scores,
                seed = ComponentSeeds.derive("coverage", mask),
                samples = 1_000,
            )
            if (interval.first <= 0.5 && interval.second >= 0.5) coveredDatasets++
        }
        val exactCoverage = coveredDatasets.toDouble() / (1 shl blockCount)

        assertTrue(exactCoverage in 0.85..0.99, "exact coverage=$exactCoverage")
    }

    @Test
    fun `shared seed correlation changes uncertainty with identical score marginals`() {
        val aligned = listOf(
            TournamentPairIndexScore(0, 1.0), TournamentPairIndexScore(1, 0.0),
            TournamentPairIndexScore(0, 1.0), TournamentPairIndexScore(1, 0.0),
        )
        val offset = listOf(
            TournamentPairIndexScore(0, 1.0), TournamentPairIndexScore(1, 0.0),
            TournamentPairIndexScore(0, 0.0), TournamentPairIndexScore(1, 1.0),
        )
        val alignedInterval = pairIndexBootstrapInterval(aligned, seed = 19L)
        val offsetInterval = pairIndexBootstrapInterval(offset, seed = 19L)

        assertEquals(aligned.map { it.value }.sorted(), offset.map { it.value }.sorted())
        assertTrue(alignedInterval.first < offsetInterval.first)
        assertTrue(alignedInterval.second > offsetInterval.second)
        assertEquals(0.5 to 0.5, offsetInterval)
    }

}
