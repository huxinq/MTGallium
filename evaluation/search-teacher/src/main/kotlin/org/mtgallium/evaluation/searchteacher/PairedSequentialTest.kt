package org.mtgallium.evaluation.searchteacher

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * A future independent-seed game population, not inference about a finite committed seed list.
 * Frozen policies/deck and independent pair seeds are assumed to give a common conditional mean.
 * Each X is one COMPLETE seat-swapped pair's point rate, including game draws. Equal boundaries
 * define opposing directional tests around one common reference point; they do not create a gap
 * or an automatic indifference conclusion.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class PairedSequentialRule(
    val schemaVersion: Int = 1,
    val populationModel: String = "independent-seed-pair-mean-v1",
    val nullPointRate: Double,
    val targetPointRate: Double,
    val falsePositiveRate: Double,
    val falseNegativeRate: Double,
    val maximumPairs: Int,
    val betFractions: List<Double> = listOf(0.05, 0.1, 0.2, 0.4, 0.8),
    /** Prospectively opt in; omitted false preserves existing rule bytes and run bindings. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stopForFutility: Boolean = false,
    /** A new prospective objective; null preserves historical directional rules and their bytes. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val practicalAcceptance: PairedPracticalAcceptance? = null,
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
        practicalAcceptance?.let {
            require(nullPointRate == 0.5 - it.margin && targetPointRate == 0.5 + it.margin) {
                "Practical acceptance requires boundaries at parity minus/plus its declared margin"
            }
            require(falsePositiveRate + falseNegativeRate < 1)
            require(!stopForFutility) { "Directional futility is not a practical-acceptance stopping rule" }
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
    FUTILITY,
    NON_INFERIOR, PRACTICALLY_EQUIVALENT,
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
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
    ) + (if (rule.stopForFutility) listOf(
        "FUTILITY is inconclusive: after a complete valid pair, neither directional boundary can be reached within the remaining pair cap, even with its most favorable continuation. It does not establish parity or equivalence.",
    ) else emptyList()) + (if (rule.practicalAcceptance != null) listOf(
        "Practical acceptance uses a prospectively declared margin around 0.5. NON_INFERIOR rejects mean <= the lower boundary; PRACTICALLY_EQUIVALENT rejects both mean <= the lower and mean >= the upper boundary. Neither establishes exact equality, superiority, or a runtime improvement.",
        "The reported confidence sequence has simultaneous coverage at least 1 - falsePositiveRate - falseNegativeRate under the common conditional-mean model. These are directional error allocations, not a power guarantee. No game outcome or selected historical treatment may be substituted into this new protocol.",
    ) else emptyList()),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val confidenceSequence: PairedMeanConfidenceSequence? = null,
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
        disposition = if (rule.practicalAcceptance != null) when {
            // A lower-bound crossing alone is not equivalence. Keep the same current-prefix
            // processes for both bounds; do not combine independently selected favorable prefixes.
            above && rule.practicalAcceptance.objective == PairedPracticalObjective.NON_INFERIOR ->
                PairedSequentialDisposition.NON_INFERIOR
            above && below && rule.practicalAcceptance.objective == PairedPracticalObjective.EQUIVALENT ->
                PairedSequentialDisposition.PRACTICALLY_EQUIVALENT
            inspected == rule.maximumPairs -> PairedSequentialDisposition.BUDGET_EXHAUSTED
            else -> PairedSequentialDisposition.CONTINUE
        } else when {
            above && below -> PairedSequentialDisposition.BOTH_BOUNDARIES_CROSSED
            above -> PairedSequentialDisposition.ABOVE_NULL
            below -> PairedSequentialDisposition.BELOW_TARGET
            inspected == rule.maximumPairs -> PairedSequentialDisposition.BUDGET_EXHAUSTED
            rule.stopForFutility && boundariesUnreachable(rule, upper, lower, rule.maximumPairs - inspected) ->
                PairedSequentialDisposition.FUTILITY
            else -> PairedSequentialDisposition.CONTINUE
        }
        if (disposition != PairedSequentialDisposition.CONTINUE) break
    }
    return PairedSequentialResult(rule, disposition, inspected, valid,
        scores.take(inspected).lastOrNull()?.pairIndex,
        sha256(evidenceJson.encodeToString(scores.take(inspected))),
        logMeanExp(upper), logMeanExp(lower), upper.toList(), lower.toList(),
        scores.size - inspected, invalid,
        confidenceSequence = rule.practicalAcceptance?.let {
            pairedMeanConfidenceSequence(rule, scores.take(valid).map { requireNotNull(it.pointRate) })
        })
}

/**
 * Upper factors increase with X; lower factors decrease with X. Thus all remaining X=1
 * maximize the upper mixture and all X=0 maximize the lower mixture, at EVERY future prefix.
 * Both endpoint growth factors exceed one, so their cap-time maxima bound earlier crossings.
 * Bound repeated-addition rounding as well as the real-valued cap. Keep a small final
 * log-space margin: a numerically borderline bound must continue, never prune.
 */
private fun boundariesUnreachable(
    rule: PairedSequentialRule,
    upper: DoubleArray,
    lower: DoubleArray,
    remainingPairs: Int,
): Boolean {
    val maximumUpper = DoubleArray(upper.size) { index ->
        maximumFutureLogCapital(upper[index], remainingPairs,
            ln1p(rule.betFractions[index] / rule.nullPointRate * (1 - rule.nullPointRate)))
    }
    val maximumLower = DoubleArray(lower.size) { index ->
        maximumFutureLogCapital(lower[index], remainingPairs,
            ln1p(rule.betFractions[index] / (1 - rule.targetPointRate) * rule.targetPointRate))
    }
    return logMeanExp(maximumUpper) < -ln(rule.falsePositiveRate) - 1e-10 &&
        logMeanExp(maximumLower) < -ln(rule.falseNegativeRate) - 1e-10
}

/**
 * The test updates capital by repeated +=, not one multiplication. Its forward error is
 * bounded by gamma_n * (abs(capital) + n * abs(growth)), gamma_n = n*u / (1-n*u).
 * u=2^-53; the Int pair cap keeps n*u below one. Round the bound outwards so cancellation
 * in the linear estimate cannot suppress a boundary reachable by the actual update path.
 */
private fun maximumFutureLogCapital(capital: Double, remainingPairs: Int, growth: Double): Double {
    val n = remainingPairs.toDouble()
    val nu = Math.nextUp(n * (Math.ulp(1.0) / 2))
    val gamma = Math.nextUp(nu / Math.nextDown(1 - nu))
    val magnitude = Math.nextUp(abs(capital) + Math.nextUp(n * abs(growth)))
    val linearUpper = Math.nextUp(capital + Math.nextUp(n * growth))
    return Math.nextUp(linearUpper + Math.nextUp(gamma * magnitude))
}

private fun logMeanExp(values: DoubleArray): Double {
    val maximum = values.max()
    return maximum + ln(values.sumOf { exp(it - maximum) } / values.size)
}
