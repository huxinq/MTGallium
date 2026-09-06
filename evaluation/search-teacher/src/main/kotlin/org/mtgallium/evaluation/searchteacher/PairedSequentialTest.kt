package org.mtgallium.evaluation.searchteacher

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlinx.serialization.Serializable

/**
 * A future independent-seed game population, not inference about a finite committed seed list.
 * Frozen policies/deck and independent pair seeds are assumed to give a common conditional mean.
 * Each X is one COMPLETE seat-swapped pair's point rate, including game draws. Equal boundaries
 * define opposing directional tests around one common reference point; they do not create a gap
 * or an automatic indifference conclusion.
 */
@Serializable
internal data class PairedSequentialRule(
    val schemaVersion: Int = 1,
    val populationModel: String = "independent-seed-pair-mean-v1",
    val nullPointRate: Double,
    val targetPointRate: Double,
    val falsePositiveRate: Double,
    val falseNegativeRate: Double,
    val maximumPairs: Int,
    val betFractions: List<Double> = listOf(0.05, 0.1, 0.2, 0.4, 0.8),
) {
    init {
        require(schemaVersion == 1 && populationModel == "independent-seed-pair-mean-v1")
        require(nullPointRate > 0 && nullPointRate <= targetPointRate && targetPointRate < 1)
        require(falsePositiveRate > 0 && falsePositiveRate < 1)
        require(falseNegativeRate > 0 && falseNegativeRate < 1)
        require(maximumPairs > 0)
        require(betFractions.isNotEmpty() && betFractions.distinct().size == betFractions.size)
        require(betFractions.all { it.isFinite() && it > 0 && it < 1 })
        require(betFractions.all { (it / nullPointRate).isFinite() && (it / (1 - targetPointRate)).isFinite() }) {
            "Sequential betting factors must remain finite at the declared boundaries"
        }
    }
}

@Serializable
internal data class PairedSequentialScore(
    val pairIndex: Int,
    val pointRate: Double?,
    val invalidReasons: List<String> = emptyList(),
) {
    init {
        require(pairIndex >= 0)
        require((pointRate == null) == invalidReasons.isNotEmpty())
        require(pointRate == null || pointRate in setOf(0.0, 0.25, 0.5, 0.75, 1.0))
    }
}

@Serializable
internal enum class PairedSequentialDisposition {
    CONTINUE, ABOVE_NULL, BELOW_TARGET, BOTH_BOUNDARIES_CROSSED, BUDGET_EXHAUSTED, INVALID_PAIR,
}

@Serializable
internal data class PairedSequentialResult(
    val rule: PairedSequentialRule,
    val disposition: PairedSequentialDisposition,
    /** Includes an invalid pair when that pair stopped the test; it is never assigned a score. */
    val inspectedPairs: Int,
    val validScoredPairs: Int,
    val stoppingPairIndex: Int?,
    val orderedPrefixSha256: String,
    val logUpperMixture: Double,
    val logLowerMixture: Double,
    val upperLogCapitals: List<Double>,
    val lowerLogCapitals: List<Double>,
    /** Work already completed after the first stopping prefix, never used to alter its decision. */
    val operationalOvershootPairs: Int,
    val invalidReasons: List<String>,
    val assumptions: List<String> = listOf(
        "This is a bounded-mean betting test under the declared independent-seed common conditional-mean model, not a finite-schedule confidence interval.",
        "Pair order, policies, populations, bet fractions and boundaries must be fixed before outcomes are inspected.",
        "ABOVE_NULL rejects mean <= nullPointRate; BELOW_TARGET rejects mean >= targetPointRate. Equal boundaries are opposing directional tests around their common reference. Neither is itself a game result or automatic policy promotion.",
        "Error guarantees are per test; exploratory multiple-candidate selection needs separate independent confirmation or explicit error allocation.",
        "BUDGET_EXHAUSTED is inconclusive. This rule does not guarantee earlier stopping or quantify a cost improvement.",
    ),
)

/**
 * Fixed mixtures of nonnegative betting supermartingales; see Waudby-Smith and Ramdas,
 * https://arxiv.org/abs/2010.09686. For f in (0,1), each upper factor is
 * 1 + f/p0 * (X-p0), whose conditional expectation is <=1 under E[X|past]<=p0.
 * Lower factors are 1 - f/(1-p1) * (X-p1), valid under E[X|past]>=p1.
 * The equally weighted mixture starts at ONE; Ville boundaries are 1/alpha and 1/beta.
 * Log arithmetic avoids overflow. No normal approximation or fitted variance is used.
 */
internal fun pairedSequentialTest(
    rule: PairedSequentialRule,
    scores: List<PairedSequentialScore>,
    firstPairIndex: Int,
): PairedSequentialResult {
    require(firstPairIndex >= 0 && scores.size <= rule.maximumPairs)
    require(scores.map { it.pairIndex.toLong() } == scores.indices.map { firstPairIndex.toLong() + it }) {
        "Sequential evidence must be a contiguous prefix in the prospectively fixed pair order"
    }
    val upper = DoubleArray(rule.betFractions.size)
    val lower = DoubleArray(rule.betFractions.size)
    var inspected = 0
    var valid = 0
    var disposition = PairedSequentialDisposition.CONTINUE
    var invalid = emptyList<String>()
    for (score in scores) {
        inspected++
        val x = score.pointRate
        if (x == null) {
            disposition = PairedSequentialDisposition.INVALID_PAIR
            invalid = score.invalidReasons
            break
        }
        valid++
        rule.betFractions.forEachIndexed { index, fraction ->
            upper[index] += ln1p(fraction / rule.nullPointRate * (x - rule.nullPointRate))
            lower[index] += ln1p(-fraction / (1 - rule.targetPointRate) * (x - rule.targetPointRate))
        }
        val above = logMeanExp(upper) >= -ln(rule.falsePositiveRate)
        val below = logMeanExp(lower) >= -ln(rule.falseNegativeRate)
        disposition = when {
            above && below -> PairedSequentialDisposition.BOTH_BOUNDARIES_CROSSED
            above -> PairedSequentialDisposition.ABOVE_NULL
            below -> PairedSequentialDisposition.BELOW_TARGET
            inspected == rule.maximumPairs -> PairedSequentialDisposition.BUDGET_EXHAUSTED
            else -> PairedSequentialDisposition.CONTINUE
        }
        if (disposition != PairedSequentialDisposition.CONTINUE) break
    }
    return PairedSequentialResult(rule, disposition, inspected, valid,
        scores.take(inspected).lastOrNull()?.pairIndex,
        sha256(evidenceJson.encodeToString(scores.take(inspected))),
        logMeanExp(upper), logMeanExp(lower), upper.toList(), lower.toList(),
        scores.size - inspected, invalid)
}

private fun logMeanExp(values: DoubleArray): Double {
    val maximum = values.max()
    return maximum + ln(values.sumOf { exp(it - maximum) } / values.size)
}
