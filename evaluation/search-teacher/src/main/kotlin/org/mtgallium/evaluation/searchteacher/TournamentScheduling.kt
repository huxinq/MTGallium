package org.mtgallium.evaluation.searchteacher

internal data class TournamentPairJob(val matchupIndex: Int, val pairIndex: Int)

/**
 * Interleaves matchup families at every pair index. Rotating the first matchup
 * prevents the same edge from always receiving the earliest worker slot.
 */
internal fun interleavedTournamentPairJobs(
    matchupCount: Int,
    pairsPerMatchup: Int,
): List<TournamentPairJob> {
    require(matchupCount > 0)
    require(pairsPerMatchup > 0)
    return (0 until pairsPerMatchup).flatMap { pairIndex ->
        (0 until matchupCount).map { slot ->
            TournamentPairJob(
                matchupIndex = (slot + pairIndex) % matchupCount,
                pairIndex = pairIndex,
            )
        }
    }
}
