package org.mtgallium.evaluation.searchteacher

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlinx.serialization.Serializable

@Serializable
internal enum class PairedPracticalObjective { NON_INFERIOR, EQUIVALENT }

/** Margin is an absolute game-score fraction, e.g. .02 means two percentage points. */
@Serializable
internal data class PairedPracticalAcceptance(
    val margin: Double,
    val objective: PairedPracticalObjective,
) {
    init { require(margin > 0 && margin < 0.5) }
}

@Serializable
internal data class PairedMeanConfidenceSequence(
    val method: String = "fixed-betting-mixture-inversion-v1",
    val simultaneousCoverageAtLeast: Double,
    val validScoredPairs: Int,
    val lower: Double,
    val upper: Double,
) {
    /** A bound on distance from parity, not an estimate of the actual strength difference. */
    val maximumCompatibleDistanceFromParity: Double get() = max(0.5 - lower, upper - 0.5)
}

/**
 * Invert the same fixed betting mixtures used by pairedSequentialTest at every hypothetical mean m.
 * Upper wealth is decreasing in m; lower wealth is increasing. For the true common conditional
 * mean, Ville's inequality bounds ANY upper crossing by alpha and ANY lower crossing by beta.
 * Their union therefore gives simultaneous coverage >= 1-alpha-beta over all inspection times.
 * There is no union penalty over m: coverage failure tests the one true m. Policies, pair order,
 * fractions and error allocations must be frozen before this population's outcomes are inspected.
 * See Waudby-Smith and Ramdas, https://arxiv.org/abs/2010.09686.
 *
 * Bisection retains the outer endpoints, including exact 0/1 when not excluded. The interval uses
 * only the supplied valid prefix; invalid execution remains a separate result, never a score.
 */
internal fun pairedMeanConfidenceSequence(
    rule: PairedSequentialRule,
    pointRates: List<Double>,
): PairedMeanConfidenceSequence {
    require(pointRates.size <= rule.maximumPairs && pointRates.all { it in 0.0..1.0 })
    require(rule.falsePositiveRate + rule.falseNegativeRate < 1)
    fun logWealth(mean: Double, upper: Boolean): Double {
        val capitals = rule.betFractions.map { fraction ->
            pointRates.sumOf { x ->
                if (upper) ln1p(fraction / mean * (x - mean))
                else ln1p(-fraction / (1 - mean) * (x - mean))
            }
        }
        val largest = capitals.max()
        return largest + ln(capitals.sumOf { exp(it - largest) } / capitals.size)
    }
    var lowerOutside = 0.0
    var lowerInside = 1.0
    var upperInside = 0.0
    var upperOutside = 1.0
    repeat(52) {
        val mLower = (lowerOutside + lowerInside) / 2
        if (logWealth(mLower, true) >= -ln(rule.falsePositiveRate)) lowerOutside = mLower
        else lowerInside = mLower
        val mUpper = (upperInside + upperOutside) / 2
        if (logWealth(mUpper, false) >= -ln(rule.falseNegativeRate)) upperOutside = mUpper
        else upperInside = mUpper
    }
    return PairedMeanConfidenceSequence(
        simultaneousCoverageAtLeast = 1 - rule.falsePositiveRate - rule.falseNegativeRate,
        validScoredPairs = pointRates.size, lower = lowerOutside, upper = upperOutside,
    )
}
