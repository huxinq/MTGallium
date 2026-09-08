// Java continuation monitors compile against both current and retained treatment runtimes.
// Keep their existing JVM entry point while giving the shared arithmetic its own source owner.
@file:JvmName("TournamentV3CalibratedKt")

package org.mtgallium.evaluation.searchteacher

import java.util.Random

internal data class TournamentPairIndexScore(val pairIndex: Int, val value: Double) {
    init {
        require(pairIndex >= 0)
        require(value.isFinite() && value in 0.0..1.0)
    }
}

/** Percentile interval for bounded fractional pair scores using the shared seed block as the unit. */
internal fun pairIndexBootstrapInterval(
    scores: List<TournamentPairIndexScore>,
    seed: Long,
    samples: Int = 10_000,
): Pair<Double, Double> {
    require(scores.isNotEmpty())
    require(samples > 0)
    val blocks = scores.groupBy(TournamentPairIndexScore::pairIndex)
        .toSortedMap()
        .values
        .toList()
    val random = Random(seed)
    val means = List(samples) {
        var sum = 0.0
        var count = 0
        repeat(blocks.size) {
            val block = blocks[random.nextInt(blocks.size)]
            sum += block.sumOf(TournamentPairIndexScore::value)
            count += block.size
        }
        sum / count
    }
    return percentile(means, 0.025).coerceIn(0.0, 1.0) to
        percentile(means, 0.975).coerceIn(0.0, 1.0)
}
