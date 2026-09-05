package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeSet
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1
import org.mtgallium.agent.searchteacher.LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1
import org.mtgallium.agent.searchteacher.LEARNED_OUTCOME_VALUE_FEATURE_SCALING_V1
import org.mtgallium.agent.searchteacher.LEARNED_OUTCOME_VALUE_MODEL_V1
import org.mtgallium.agent.searchteacher.LEARNED_OUTCOME_VALUE_TARGET_V1
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueCheckpointPayload
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueEvaluator
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatureCompiler
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatures
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueTrainingBinding
import org.mtgallium.agent.searchteacher.MonoRedInformationEvaluator
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpointEnvelope
import org.mtgallium.research.run.ResearchRunCheckpoints
import org.mtgallium.research.run.ResearchRunFiles
import org.mtgallium.research.run.ResearchRunProvenance
import org.mtgallium.research.run.researchSha256

internal const val LEARNED_OUTCOME_VALUE_TRAINING_PROTOCOL = "learned-outcome-value-training-v1"
internal const val LEARNED_OUTCOME_VALUE_VALIDATION_PROTOCOL = "learned-outcome-value-validation-v1"
internal const val LEARNED_OUTCOME_VALUE_TEST_PROTOCOL = "learned-outcome-value-test-v1"
internal const val LEARNED_OUTCOME_VALUE_ORDERING = "pair-index-leg-a-then-b-frame-index-utf8-feature-keys-v1"
internal const val LEARNED_OUTCOME_VALUE_WEIGHTING = "equal-game-then-equal-frame-v1"
internal const val LEARNED_OUTCOME_VALUE_SOLVER = "weighted-centered-jacobi-pcg-ridge-v2"
internal const val LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE = "checkpoint.json"
internal const val LEARNED_OUTCOME_VALUE_TRAINING_REPORT_FILE = "training-report.json"
internal const val LEARNED_OUTCOME_VALUE_VALIDATION_REPORT_FILE = "validation-report.json"
internal const val LEARNED_OUTCOME_VALUE_TEST_REPORT_FILE = "test-report.json"

private const val RIDGE_LAMBDA = 0.01
private const val KKT_TOLERANCE = 1e-8
private const val SOLVER_ITERATION_CAP_MULTIPLIER = 2
private const val SOLVER_RESTART_ITERATION_MULTIPLIER = 1
private const val SOLVER_CAP_RULE = "two-times-feature-count-resource-guard-v1"
private const val SOLVER_RESTART_RULE = "one-restart-after-true-residual-at-feature-count-v1"
private const val SOLVER_PRECONDITIONER = "jacobi-centered-normal-diagonal-v1"
private const val SOLVER_START = "zero-coefficients-v1"
private const val SOLVER_RESIDUAL_RULE = "rebuild-true-h-minus-Hw-at-feature-count-v1"
private const val SOLVER_CERTIFICATION_RULE = "rebuild-raw-scores-and-audit-original-intercept-and-ridge-gradients-v1"
private const val VALIDATION_MSE_FACTOR = 0.99
private const val VALIDATION_MIN_PREDICTION_SD = 0.02
private const val VALIDATION_MIN_COEFFICIENT_L2 = 1e-8
private const val VALIDATION_MIN_DISTINCT_TERMINAL_PAYOFFS = 2

private val trainingJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

/** Every promotion threshold is one serialized, identity-bound validation-gate specification. */
@Serializable
internal data class OutcomeValueValidationGateSpecification(
    val schemaVersion: Int = 1,
    val maximumTrainingKktResidual: Double,
    val minimumCoefficientL2Norm: Double,
    val minimumEqualGamePredictionStandardDeviation: Double,
    val maximumValidationMseFactorOfTrainConstantBaseline: Double,
    val minimumDistinctTerminalPayoffsPerPartition: Int,
    val requireRootAndOpponentActorFrames: Boolean,
    val requireCanonicalReloadPredictionEquality: Boolean,
)

private val outcomeValueValidationGateSpecification = OutcomeValueValidationGateSpecification(
    maximumTrainingKktResidual = KKT_TOLERANCE,
    minimumCoefficientL2Norm = VALIDATION_MIN_COEFFICIENT_L2,
    minimumEqualGamePredictionStandardDeviation = VALIDATION_MIN_PREDICTION_SD,
    maximumValidationMseFactorOfTrainConstantBaseline = VALIDATION_MSE_FACTOR,
    minimumDistinctTerminalPayoffsPerPartition = VALIDATION_MIN_DISTINCT_TERMINAL_PAYOFFS,
    requireRootAndOpponentActorFrames = true,
    requireCanonicalReloadPredictionEquality = true,
)

internal enum class OutcomeValueActorRelation { ROOT, OPPONENT }

internal enum class OutcomeValueTrainingFailureKind {
    INVALID_DATA,
    NONFINITE_INPUT,
    NONFINITE_PARAMETER,
    NONFINITE_PREDICTION,
    NUMERICAL_BREAKDOWN,
    CONVERGENCE_LIMIT,
    CHECKPOINT_ENVELOPE_INVALID,
    VALIDATION_NOT_PASSED,
}

internal class OutcomeValueTrainingException(
    val kind: OutcomeValueTrainingFailureKind,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException("$kind: $message", cause)

/**
 * One compiler-produced, nonterminal frame. Its label is deliberately joined after feature
 * compilation from the completed game's artifact, rather than obtained from the bundle frame.
 */
internal data class OutcomeValueExample(
    val pairIndex: Int,
    val leg: String,
    val frameIndex: Int,
    val actorRelation: OutcomeValueActorRelation,
    val features: LearnedOutcomeValueFeatures,
    val actualTerminalPayoff: Double,
) {
    init {
        require(pairIndex >= 0 && leg in setOf("a", "b") && frameIndex >= 0)
        require(actualTerminalPayoff.isFinite() && actualTerminalPayoff in -1.0..1.0)
        require(features.schemaId == LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1)
    }

    val gameKey: OutcomeValueGameKey get() = OutcomeValueGameKey(pairIndex, leg)
}

/**
 * One retained nonterminal corpus frame, decoded and compiled by the same authority that creates
 * training rows.  The private evidence diagnostic needs the original information state to prove
 * the production inference path, but it must not reconstruct a second frame-to-row convention.
 */
internal data class OutcomeValueCorpusFrame(
    val example: OutcomeValueExample,
    /** Recorded corpus authority; do not infer root perspective from pair leg. */
    val rootPlayerId: String,
    val information: PolicyInformationState,
) {
    init { require(rootPlayerId in setOf("p0", "p1")) }
}

internal data class OutcomeValueGameKey(val pairIndex: Int, val leg: String) : Comparable<OutcomeValueGameKey> {
    init { require(pairIndex >= 0 && leg in setOf("a", "b")) }
    override fun compareTo(other: OutcomeValueGameKey): Int =
        pairIndex.compareTo(other.pairIndex).takeIf { it != 0 }
            ?: (if (leg == "a") 0 else 1).compareTo(if (other.leg == "a") 0 else 1)
}

/** Fixed data order is part of model identity: pair, a/b leg, then nonterminal frame index. */
internal fun canonicalOutcomeValueOrder(examples: Iterable<OutcomeValueExample>): List<OutcomeValueExample> =
    examples.sortedWith(compareBy<OutcomeValueExample>({ it.pairIndex }, { if (it.leg == "a") 0 else 1 }, { it.frameIndex }))
        .also { ordered ->
            require(ordered.map { Triple(it.pairIndex, it.leg, it.frameIndex) }.distinct().size == ordered.size) {
                "Outcome-value training frames must be unique per pair, leg, and frame"
            }
        }

/** UTF-8 byte lexicographic order, independent of platform locale or collation. */
internal val utf8BytewiseStringComparator: Comparator<String> = Comparator { left, right ->
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
        val comparison = (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
        if (comparison != 0) return@Comparator comparison
    }
    leftBytes.size.compareTo(rightBytes.size)
}

internal data class OutcomeValueWeightedExample(val example: OutcomeValueExample, val weight: Double)

/** Every game receives 1/G total mass; its eligible frames divide that mass exactly. */
internal fun equalGameOutcomeValueWeights(examples: Iterable<OutcomeValueExample>): List<OutcomeValueWeightedExample> {
    val ordered = canonicalOutcomeValueOrder(examples)
    val byGame = ordered.groupBy(OutcomeValueExample::gameKey)
    require(byGame.isNotEmpty()) { "Outcome-value training requires at least one eligible game" }
    val gameWeight = 1.0 / byGame.size.toDouble()
    return ordered.map { example ->
        val frames = requireNotNull(byGame[example.gameKey]).size
        OutcomeValueWeightedExample(example, gameWeight / frames.toDouble())
    }.also { weighted ->
        ensureFinite(weighted.map(OutcomeValueWeightedExample::weight), "frame weights")
    }
}

/** Equal-game mean of actual completed terminal payoffs; it is the frozen validation baseline. */
internal fun equalGameTerminalPayoffMean(examples: Iterable<OutcomeValueExample>): Double {
    val byGame = canonicalOutcomeValueOrder(examples).groupBy(OutcomeValueExample::gameKey)
    require(byGame.isNotEmpty()) { "Baseline requires at least one game" }
    return byGame.values.map { frames ->
        val labels = frames.map(OutcomeValueExample::actualTerminalPayoff).distinct()
        require(labels.size == 1) { "A completed game must carry exactly one terminal-payoff label" }
        labels.single()
    }.average().also { requireFinite(it, "equal-game terminal-payoff mean") }
}

/** TRAIN-only feature moments used by historical fixed-root diagnostics. */
@Serializable
internal data class OutcomeValueTrainFeatureMoment(
    val key: String,
    val mean: Double,
    val populationVariance: Double,
) {
    init {
        require(LearnedOutcomeValueFeatureCompiler.isAllowedFeatureKey(key))
        require(mean.isFinite() && populationVariance.isFinite() && populationVariance >= 0.0)
    }
}

/**
 * A read-only reference calculated from authenticated TRAIN rows. This type has no construction
 * route through production training and cannot materialize VALIDATION or TEST rows.
 */
@Serializable
internal data class OutcomeValueTrainFeatureReference(
    val training: LearnedOutcomeValueTrainingBinding,
    val rows: Int,
    val games: Int,
    val constantBaseline: Double,
    val moments: List<OutcomeValueTrainFeatureMoment>,
    val referenceDigest: String,
) {
    init {
        require(rows > 0 && games > 0 && constantBaseline.isFinite() && constantBaseline in -1.0..1.0)
        require(moments.isNotEmpty() && moments.map { it.key } == moments.map { it.key }.sortedWith(utf8BytewiseStringComparator))
        require(moments.map { it.key }.distinct().size == moments.size)
        require(referenceDigest == outcomeValueTrainFeatureReferenceDigest(training, rows, games, constantBaseline, moments))
    }
}

internal fun outcomeValueTrainFeatureReferenceDigest(
    training: LearnedOutcomeValueTrainingBinding,
    rows: Int,
    games: Int,
    constantBaseline: Double,
    moments: List<OutcomeValueTrainFeatureMoment>,
): String = researchSha256(buildString {
    append("outcome-value-historical-train-feature-reference-v1\n")
    append(trainingJson.encodeToString(training)).append('\n')
    append(rows).append(':').append(games).append(':').append(constantBaseline).append('\n')
    moments.forEach { append(it.key).append(':').append(it.mean).append(':').append(it.populationVariance).append('\n') }
})

private fun trainFeatureReference(
    training: LearnedOutcomeValueTrainingBinding,
    examples: List<OutcomeValueExample>,
): OutcomeValueTrainFeatureReference {
    val weighted = equalGameOutcomeValueWeights(examples)
    val keys = TreeSet(utf8BytewiseStringComparator).apply { weighted.forEach { addAll(it.example.features.values.keys) } }
    val moments = keys.map { key ->
        val mean = weighted.sumOf { it.weight * (it.example.features.values[key] ?: 0.0) }
        val variance = weighted.sumOf {
            val delta = (it.example.features.values[key] ?: 0.0) - mean
            it.weight * delta * delta
        }
        OutcomeValueTrainFeatureMoment(key, mean, variance)
    }
    val games = examples.map(OutcomeValueExample::gameKey).distinct().size
    val baseline = equalGameTerminalPayoffMean(examples)
    return OutcomeValueTrainFeatureReference(
        training, examples.size, games, baseline, moments,
        outcomeValueTrainFeatureReferenceDigest(training, examples.size, games, baseline, moments),
    )
}

internal data class OutcomeValueFit(
    val evaluator: LearnedOutcomeValueEvaluator,
    val trainFeatureKeys: Set<String>,
    val solverIterations: Int,
    val maxKktResidual: Double,
    val coefficientL2Norm: Double,
)

/** A fit whose binding and TRAIN rows came together from the verified loaded corpus authority. */
private class LoadedOutcomeValueFit(
    val training: LearnedOutcomeValueTrainingBinding,
    val fit: OutcomeValueFit,
    private val orderedTrainExamples: List<OutcomeValueExample>,
) {
    internal fun trainExamples(): List<OutcomeValueExample> = orderedTrainExamples
}

/**
 * Numerical test seam. Production enters this deterministic solver only through
 * [fitLoadedOutcomeValueCorpus], which obtains both rows and binding from the verified corpus.
 * It has no RNG, shuffling, validation input, model selection, or checkpoint selection.
 */
internal object LearnedOutcomeValueTrainer {
    fun fit(
        trainExamples: Iterable<OutcomeValueExample>,
        binding: LearnedOutcomeValueTrainingBinding,
    ): OutcomeValueFit {
        val rows = equalGameOutcomeValueWeights(trainExamples)
        validateRows(rows)
        val featureKeys = TreeSet(utf8BytewiseStringComparator).apply {
            rows.forEach { addAll(it.example.features.values.keys) }
        }.toList()
        require(featureKeys.isNotEmpty()) { "Outcome-value train universe cannot be empty" }

        val featureIndex = featureKeys.withIndex().associate { (index, key) -> key to index }
        val sparseRows = rows.map { row -> sparseRow(row.example.features, featureIndex) }
        val labels = DoubleArray(rows.size) { rows[it].example.actualTerminalPayoff }
        val rowWeights = DoubleArray(rows.size) { rows[it].weight }
        val sums = centeredNormalEquationSums(sparseRows, labels, rowWeights, featureKeys.size)
        val coefficients = DoubleArray(featureKeys.size)
        val certification = RidgeCertification(sparseRows, labels, rowWeights)

        fun certifiedFitOrNull(iterations: Int): OutcomeValueFit? {
            val bias = (sums.labelMean - dot(sums.featureMeans, coefficients, "intercept reconstruction")).also {
                requireFinite(it, "reconstructed intercept")
            }
            val certificate = certification.audit(bias, coefficients)
            if (certificate.maxKktResidual > KKT_TOLERANCE) return null
            val checkpoint = LearnedOutcomeValueCheckpointPayload(
                training = binding,
                bias = certificate.bias,
                weights = featureKeys.zip(coefficients.asIterable()).toMap(),
            )
            return OutcomeValueFit(
                evaluator = LearnedOutcomeValueEvaluator.fromCheckpoint(checkpoint),
                trainFeatureKeys = featureKeys.toSet(),
                solverIterations = iterations,
                maxKktResidual = certificate.maxKktResidual,
                coefficientL2Norm = coefficientL2Norm(coefficients),
            )
        }

        certifiedFitOrNull(iterations = 0)?.let { return it }

        val normalEquation = CenteredNormalEquation(sparseRows, rowWeights, sums.featureMeans, sums.diagonal)
        var residual = sums.rightHandSide.copyOf() // h - H * 0
        var preconditionedResidual = normalEquation.precondition(residual)
        var direction = preconditionedResidual.copyOf()
        var residualInnerProduct = dot(residual, preconditionedResidual, "initial preconditioned residual")
        requirePositiveFinite(residualInnerProduct, "initial preconditioned residual")
        val restartIteration = SOLVER_RESTART_ITERATION_MULTIPLIER * featureKeys.size
        val iterationCap = SOLVER_ITERATION_CAP_MULTIPLIER * featureKeys.size
        var finalKkt = Double.POSITIVE_INFINITY

        for (iteration in 1..iterationCap) {
            val normalDirection = normalEquation.multiply(direction)
            val curvature = dot(direction, normalDirection, "CG curvature")
            requirePositiveFinite(curvature, "CG curvature")
            val alpha = residualInnerProduct / curvature
            requireFinite(alpha, "CG step length")
            addScaled(coefficients, direction, alpha, "CG coefficients")
            addScaled(residual, normalDirection, -alpha, "CG residual")

            if (maxAbsolute(residual, "recurrent CG residual") <= KKT_TOLERANCE) {
                certifiedFitOrNull(iteration)?.let { return it }
            }

            if (iteration == restartIteration) {
                // This reconstruction is deliberately independent of the recurrent CG residual.
                residual = normalEquation.trueResidual(sums.rightHandSide, coefficients)
                certifiedFitOrNull(iteration)?.let { return it }
                preconditionedResidual = normalEquation.precondition(residual)
                direction = preconditionedResidual.copyOf()
                residualInnerProduct = dot(residual, preconditionedResidual, "restarted preconditioned residual")
                requirePositiveFinite(residualInnerProduct, "restarted preconditioned residual")
            } else if (iteration < iterationCap) {
                preconditionedResidual = normalEquation.precondition(residual)
                val nextInnerProduct = dot(residual, preconditionedResidual, "CG preconditioned residual")
                requirePositiveFinite(nextInnerProduct, "CG preconditioned residual")
                val beta = nextInnerProduct / residualInnerProduct
                requireFinite(beta, "CG direction scale")
                combineDirection(direction, preconditionedResidual, beta)
                residualInnerProduct = nextInnerProduct
            }
        }

        // The cap is only a resource guard. The emitted solution still needs this independent audit.
        val finalBias = (sums.labelMean - dot(sums.featureMeans, coefficients, "intercept reconstruction")).also {
            requireFinite(it, "reconstructed intercept")
        }
        finalKkt = certification.audit(finalBias, coefficients).maxKktResidual
        if (finalKkt <= KKT_TOLERANCE) {
            certifiedFitOrNull(iterationCap)?.let { return it }
        }
        throw OutcomeValueTrainingException(
            OutcomeValueTrainingFailureKind.CONVERGENCE_LIMIT,
            "Jacobi-preconditioned CG did not reach independently audited training KKT <= $KKT_TOLERANCE within $iterationCap iterations; residual=$finalKkt",
        )
    }
}

private data class SparseOutcomeValueRow(
    val featureIndices: IntArray,
    val featureValues: DoubleArray,
) {
    init { require(featureIndices.size == featureValues.size) }
}

private fun sparseRow(
    features: LearnedOutcomeValueFeatures,
    featureIndex: Map<String, Int>,
): SparseOutcomeValueRow {
    val keys = features.values.keys.sortedWith(utf8BytewiseStringComparator)
    val indices = ArrayList<Int>(keys.size)
    val values = ArrayList<Double>(keys.size)
    keys.forEach { key ->
        val value = requireNotNull(features.values[key])
        requireFinite(value, "feature value")
        if (value != 0.0) {
            indices += requireNotNull(featureIndex[key]) { "Feature key escaped the train universe" }
            values += value
        }
    }
    return SparseOutcomeValueRow(indices.toIntArray(), values.toDoubleArray())
}

private data class CenteredNormalEquationSums(
    val featureMeans: DoubleArray,
    val labelMean: Double,
    val rightHandSide: DoubleArray,
    val diagonal: DoubleArray,
)

private fun centeredNormalEquationSums(
    rows: List<SparseOutcomeValueRow>,
    labels: DoubleArray,
    rowWeights: DoubleArray,
    featureCount: Int,
): CenteredNormalEquationSums {
    val totalWeight = weightedSum(rows.indices) { rowWeights[it] }
    if (totalWeight <= 0.0) throw OutcomeValueTrainingException(OutcomeValueTrainingFailureKind.INVALID_DATA, "Training row weights must have positive total mass")
    val labelMean = weightedSum(rows.indices) { index -> rowWeights[index] * labels[index] } / totalWeight
    requireFinite(labelMean, "weighted label mean")
    val centeredLabelSum = weightedSum(rows.indices) { index -> rowWeights[index] * (labels[index] - labelMean) }
    val featureSums = Array(featureCount) { CompensatedSum("weighted feature sum") }
    val weightedSquares = Array(featureCount) { CompensatedSum("weighted feature square sum") }
    rows.indices.forEach { rowIndex ->
        val row = rows[rowIndex]
        val weight = rowWeights[rowIndex]
        row.featureIndices.indices.forEach { entryIndex ->
            val feature = row.featureIndices[entryIndex]
            val value = row.featureValues[entryIndex]
            featureSums[feature].add(weight * value)
            weightedSquares[feature].add(weight * value * value)
        }
    }
    val featureMeans = DoubleArray(featureCount) { index ->
        (featureSums[index].value() / totalWeight).also { requireFinite(it, "weighted feature mean") }
    }
    val rightHandSide = Array(featureCount) { CompensatedSum("centered normal-equation right hand side") }
    rows.indices.forEach { rowIndex ->
        val row = rows[rowIndex]
        val scaledLabel = rowWeights[rowIndex] * (labels[rowIndex] - labelMean)
        requireFinite(scaledLabel, "centered weighted label")
        row.featureIndices.indices.forEach { entryIndex ->
            val feature = row.featureIndices[entryIndex]
            rightHandSide[feature].add(row.featureValues[entryIndex] * scaledLabel)
        }
    }
    val correctedRightHandSide = DoubleArray(featureCount) { index ->
        (rightHandSide[index].value() - featureMeans[index] * centeredLabelSum).also {
            requireFinite(it, "centered normal-equation right hand side")
        }
    }
    val diagonal = DoubleArray(featureCount) { index ->
        (RIDGE_LAMBDA + weightedSquares[index].value() - totalWeight * featureMeans[index] * featureMeans[index]).also {
            requireFinite(it, "centered normal-equation diagonal")
            requirePositiveFinite(it, "centered normal-equation diagonal")
        }
    }
    return CenteredNormalEquationSums(featureMeans, labelMean, correctedRightHandSide, diagonal)
}

private class CenteredNormalEquation(
    private val rows: List<SparseOutcomeValueRow>,
    private val rowWeights: DoubleArray,
    private val featureMeans: DoubleArray,
    private val diagonal: DoubleArray,
) {
    private val totalWeight = weightedSum(rows.indices) { rowWeights[it] }

    fun multiply(vector: DoubleArray): DoubleArray {
        require(vector.size == featureMeans.size)
        val featureMeanDot = dot(featureMeans, vector, "feature-mean dot product")
        val result = DoubleArray(vector.size) { index ->
            (RIDGE_LAMBDA * vector[index] - totalWeight * featureMeans[index] * featureMeanDot).also {
                requireFinite(it, "centered normal-equation product")
            }
        }
        rows.indices.forEach { rowIndex ->
            val row = rows[rowIndex]
            var rowDot = 0.0
            row.featureIndices.indices.forEach { entryIndex ->
                rowDot = checkedSum(
                    rowDot,
                    row.featureValues[entryIndex] * vector[row.featureIndices[entryIndex]],
                    "sparse row dot product",
                )
            }
            val weightedRowDot = rowWeights[rowIndex] * rowDot
            requireFinite(weightedRowDot, "weighted sparse row dot product")
            row.featureIndices.indices.forEach { entryIndex ->
                val feature = row.featureIndices[entryIndex]
                result[feature] = checkedSum(
                    result[feature],
                    row.featureValues[entryIndex] * weightedRowDot,
                    "centered normal-equation product",
                )
            }
        }
        return result
    }

    fun precondition(residual: DoubleArray): DoubleArray = DoubleArray(residual.size) { index ->
        (residual[index] / diagonal[index]).also { requireFinite(it, "Jacobi-preconditioned residual") }
    }

    fun trueResidual(rightHandSide: DoubleArray, coefficients: DoubleArray): DoubleArray {
        val product = multiply(coefficients)
        return DoubleArray(rightHandSide.size) { index ->
            (rightHandSide[index] - product[index]).also { requireFinite(it, "reconstructed CG residual") }
        }
    }
}

private class RidgeCertification(
    private val rows: List<SparseOutcomeValueRow>,
    private val labels: DoubleArray,
    private val rowWeights: DoubleArray,
) {
    fun audit(bias: Double, coefficients: DoubleArray): RidgeCertificate {
        requireFinite(bias, "audited intercept")
        val featureGradients = Array(coefficients.size) { CompensatedSum("feature KKT") }
        val interceptGradient = CompensatedSum("intercept KKT")
        rows.indices.forEach { rowIndex ->
            val row = rows[rowIndex]
            val rawScore = CompensatedSum("reconstructed training raw score")
            rawScore.add(bias)
            row.featureIndices.indices.forEach { entryIndex ->
                rawScore.add(row.featureValues[entryIndex] * coefficients[row.featureIndices[entryIndex]])
            }
            val residual = rawScore.value() - labels[rowIndex]
            requireFinite(residual, "reconstructed training residual")
            val weightedResidual = rowWeights[rowIndex] * residual
            requireFinite(weightedResidual, "weighted reconstructed training residual")
            interceptGradient.add(weightedResidual)
            row.featureIndices.indices.forEach { entryIndex ->
                val feature = row.featureIndices[entryIndex]
                featureGradients[feature].add(row.featureValues[entryIndex] * weightedResidual)
            }
        }
        var maximum = absFinite(interceptGradient.value(), "intercept KKT")
        coefficients.indices.forEach { index ->
            featureGradients[index].add(RIDGE_LAMBDA * coefficients[index])
            maximum = max(maximum, absFinite(featureGradients[index].value(), "feature KKT"))
        }
        return RidgeCertificate(bias, maximum)
    }
}

private data class RidgeCertificate(val bias: Double, val maxKktResidual: Double)

private fun coefficientL2Norm(coefficients: DoubleArray): Double {
    val squaredSum = CompensatedSum("coefficient L2 norm")
    coefficients.forEach { coefficient -> squaredSum.add(coefficient * coefficient) }
    return sqrt(squaredSum.value()).also { requireFinite(it, "coefficient L2 norm") }
}

private class CompensatedSum(private val field: String) {
    private var sum = 0.0
    private var correction = 0.0

    fun add(term: Double) {
        requireFinite(term, field)
        val total = sum + term
        val adjustment = if (kotlin.math.abs(sum) >= kotlin.math.abs(term)) {
            (sum - total) + term
        } else {
            (term - total) + sum
        }
        sum = total
        correction = checkedSum(correction, adjustment, field)
        requireFinite(sum, field)
    }

    fun value(): Double = (sum + correction).also { requireFinite(it, field) }
}

private fun checkedSum(current: Double, term: Double, field: String): Double {
    requireFinite(term, field)
    return (current + term).also { requireFinite(it, field) }
}

private fun dot(left: DoubleArray, right: DoubleArray, field: String): Double {
    require(left.size == right.size)
    val sum = CompensatedSum(field)
    left.indices.forEach { index ->
        sum.add(left[index] * right[index])
    }
    return sum.value()
}

private fun addScaled(target: DoubleArray, direction: DoubleArray, scale: Double, field: String) {
    require(target.size == direction.size)
    requireFinite(scale, field)
    target.indices.forEach { index ->
        target[index] = checkedSum(target[index], scale * direction[index], field)
    }
}

private fun combineDirection(direction: DoubleArray, preconditionedResidual: DoubleArray, beta: Double) {
    require(direction.size == preconditionedResidual.size)
    requireFinite(beta, "CG direction scale")
    direction.indices.forEach { index ->
        direction[index] = checkedSum(preconditionedResidual[index], beta * direction[index], "CG direction")
    }
}

private fun maxAbsolute(values: DoubleArray, field: String): Double {
    var maximum = 0.0
    values.forEach { value -> maximum = max(maximum, absFinite(value, field)) }
    return maximum
}

private fun requirePositiveFinite(value: Double, field: String) {
    requireFinite(value, field)
    if (value <= 0.0) {
        throw OutcomeValueTrainingException(
            OutcomeValueTrainingFailureKind.NUMERICAL_BREAKDOWN,
            "$field must be positive for deterministic Jacobi-preconditioned CG",
        )
    }
}

/** Production fit owner: only the verified TRAIN partition is admissible to the optimizer. */
private fun fitLoadedOutcomeValueCorpus(
    corpus: LoadedOutcomeValueCorpus,
): LoadedOutcomeValueFit {
    val binding = corpus.trainingBinding()
    require(corpus.matchesTraining(binding))
    val train = canonicalOutcomeValueOrder(corpus.examples(OutcomeStateCorpusSplit.TRAIN))
    return LoadedOutcomeValueFit(binding, LearnedOutcomeValueTrainer.fit(train, binding), train)
}

private fun weightedSum(indices: IntRange, term: (Int) -> Double): Double {
    val sum = CompensatedSum("weighted training sum")
    for (index in indices) {
        sum.add(term(index))
    }
    return sum.value()
}

private fun absFinite(value: Double, field: String): Double = kotlin.math.abs(value).also { requireFinite(it, field) }

private fun requireFinite(value: Double, field: String) {
    if (!value.isFinite()) throw OutcomeValueTrainingException(OutcomeValueTrainingFailureKind.NONFINITE_PARAMETER, "$field is non-finite")
}

private fun ensureFinite(values: Iterable<Double>, field: String) = values.forEach { value -> requireFinite(value, field) }

private fun validateRows(rows: List<OutcomeValueWeightedExample>) {
    rows.forEach { row ->
        if (!row.example.actualTerminalPayoff.isFinite() || row.example.features.values.values.any { !it.isFinite() }) {
            throw OutcomeValueTrainingException(OutcomeValueTrainingFailureKind.NONFINITE_INPUT, "Training inputs must be finite")
        }
    }
    require(rows.map { it.example.gameKey }.distinct().isNotEmpty())
}

@Serializable
internal data class OutcomeValueMetricReport(
    val pairClusteredMse: Double,
    val rawScoreMinimum: Double,
    val rawScoreMaximum: Double,
    val clippedFraction: Double,
    val unseenFeatureOccurrences: Int,
    val unseenFeatureKeys: Int,
    val equalGamePredictionStandardDeviation: Double,
    val rootActorMse: Double?,
    val opponentActorMse: Double?,
)

internal fun outcomeValueMetrics(
    examples: Iterable<OutcomeValueExample>,
    evaluator: LearnedOutcomeValueEvaluator,
    trainUniverse: Set<String>,
): OutcomeValueMetricReport {
    val rows = canonicalOutcomeValueOrder(examples)
    require(rows.isNotEmpty()) { "Metric report requires examples" }
    val payload = checkpointPayload(evaluator)
    val raw = rows.map { example -> rawOutcomeValueScore(payload, example.features) }
    ensureFinite(raw, "evaluation raw predictions")
    val prediction = raw.map { it.coerceIn(-1.0, 1.0) }
    val frameLosses = rows.indices.associateWith { index ->
        val error = prediction[index] - rows[index].actualTerminalPayoff
        error * error
    }
    val gameMse = rows.indices.groupBy { rows[it].gameKey }.mapValues { (_, gameRows) ->
        gameRows.map(frameLosses::getValue).average()
    }
    val pairMse = gameMse.entries.groupBy { it.key.pairIndex }.values
        .map { pairGames -> pairGames.map { it.value }.average() }
        .average()
    requireFinite(pairMse, "pair-clustered MSE")
    fun relationMse(relation: OutcomeValueActorRelation): Double? = rows.indices.filter {
        rows[it].actorRelation == relation
    }.takeIf { it.isNotEmpty() }?.map(frameLosses::getValue)?.average()
    val gamePredictionMeans = rows.indices.groupBy { rows[it].gameKey }.values.map { gameRows ->
        gameRows.map { prediction[it] }.average()
    }
    val gamePredictionMean = gamePredictionMeans.average()
    val predictionSd = sqrt(gamePredictionMeans.map { (it - gamePredictionMean) * (it - gamePredictionMean) }.average())
    val unseen = rows.flatMap { example -> example.features.values.keys.filter { it !in trainUniverse } }
    return OutcomeValueMetricReport(
        pairClusteredMse = pairMse,
        rawScoreMinimum = raw.min(),
        rawScoreMaximum = raw.max(),
        clippedFraction = raw.count { it < -1.0 || it > 1.0 }.toDouble() / raw.size,
        unseenFeatureOccurrences = unseen.size,
        unseenFeatureKeys = unseen.toSet().size,
        equalGamePredictionStandardDeviation = predictionSd,
        rootActorMse = relationMse(OutcomeValueActorRelation.ROOT),
        opponentActorMse = relationMse(OutcomeValueActorRelation.OPPONENT),
    )
}

private fun checkpointPayload(evaluator: LearnedOutcomeValueEvaluator): LearnedOutcomeValueCheckpointPayload =
    trainingJson.decodeFromString(evaluator.canonicalCheckpointPayload)

private fun rawOutcomeValueScore(payload: LearnedOutcomeValueCheckpointPayload, features: LearnedOutcomeValueFeatures): Double {
    var raw = payload.bias
    features.values.forEach { (key, value) -> raw += (payload.weights[key] ?: 0.0) * value }
    requireFinite(raw, "raw outcome-value score")
    return raw
}

private data class OutcomeValueCheckpointTrainAudit(
    val rows: Int,
    val games: Int,
    val terminalPayoffMean: Double,
    val maxKktResidual: Double,
    val coefficientL2Norm: Double,
)

/** Recomputes the actual checkpoint's unclipped ridge facts from canonical TRAIN rows. */
private fun auditOutcomeValueCheckpointTrain(
    trainExamples: Iterable<OutcomeValueExample>,
    evaluator: LearnedOutcomeValueEvaluator,
): OutcomeValueCheckpointTrainAudit {
    val rows = equalGameOutcomeValueWeights(trainExamples)
    validateRows(rows)
    val payload = checkpointPayload(evaluator)
    val featureKeys = TreeSet(utf8BytewiseStringComparator).apply {
        rows.forEach { addAll(it.example.features.values.keys) }
        addAll(payload.weights.keys)
    }.toList()
    require(featureKeys.toSet() == payload.weights.keys) {
        "Checkpoint feature coordinates must exactly match canonical TRAIN coordinates"
    }
    val featureIndex = featureKeys.withIndex().associate { (index, key) -> key to index }
    val sparseRows = rows.map { sparseRow(it.example.features, featureIndex) }
    val labels = DoubleArray(rows.size) { rows[it].example.actualTerminalPayoff }
    val rowWeights = DoubleArray(rows.size) { rows[it].weight }
    val coefficients = DoubleArray(featureKeys.size) { index ->
        requireNotNull(payload.weights[featureKeys[index]]).also { requireFinite(it, "checkpoint coefficient") }
    }
    val certificate = RidgeCertification(sparseRows, labels, rowWeights).audit(payload.bias, coefficients)
    return OutcomeValueCheckpointTrainAudit(
        rows = rows.size,
        games = rows.map { it.example.gameKey }.distinct().size,
        terminalPayoffMean = equalGameTerminalPayoffMean(rows.map { it.example }),
        maxKktResidual = certificate.maxKktResidual,
        coefficientL2Norm = coefficientL2Norm(coefficients),
    )
}

@Serializable
internal data class OutcomeValueTrainingReport(
    val schemaVersion: Int = 2,
    val trainingRunIdentity: String,
    val checkpointPayloadSha256: String,
    val rows: Int,
    val games: Int,
    val trainTerminalPayoffMean: Double,
    val solverIterations: Int,
    val maxKktResidual: Double,
    val coefficientL2Norm: Double,
)

@Serializable
internal data class OutcomeValueValidationReport(
    val schemaVersion: Int = 1,
    val validationRunIdentity: String,
    val trainingRunIdentity: String,
    val immutableCheckpointPayloadSha256: String,
    val validationPairSplitIdentity: String,
    val frozenTrainConstantBaselineMse: Double,
    val metrics: OutcomeValueMetricReport,
    val passed: Boolean,
    val failures: List<String>,
) {
    init { require(passed == failures.isEmpty()) { "Validation pass status must exactly match recorded failures" } }
}

@Serializable
internal data class OutcomeValueTestReport(
    val schemaVersion: Int = 1,
    val testRunIdentity: String,
    val validationRunIdentity: String,
    val immutableCheckpointPayloadSha256: String,
    val metrics: OutcomeValueMetricReport,
)

internal data class LoadedOutcomeValueCheckpoint(
    val envelope: ResearchRunCheckpointEnvelope,
    val evaluator: LearnedOutcomeValueEvaluator,
)

/** Verified corpus authority used only inside the private host capability implementation. */
private class LoadedOutcomeValueCorpus private constructor(
    private val manifest: OutcomeStateCorpusManifest,
    private val developmentExamples: Map<OutcomeStateCorpusSplit, List<OutcomeValueExample>>,
    private val loadSealedTestExamples: (PromotedOutcomeValueCheckpoint) -> List<OutcomeValueExample>,
    private val loadDevelopmentSignalExamples: (OutcomeStateCorpusSplit) -> List<OutcomeValueSignalExample>,
    private val loadSealedTestSignalExamples: (PromotedOutcomeValueCheckpoint) -> List<OutcomeValueSignalExample>,
    private val loadHistoricallyAuditedTestSignalExamples: (HistoricalOutcomeValueDiagnosticCheckpoint) -> List<OutcomeValueSignalExample>,
) {
    val deckHash: String get() = manifest.deckHash
    val cardPoolHash: String get() = manifest.cardPoolHash
    val actionSpaceProfile: SearchActionSpaceProfile get() = manifest.actionSpaceProfile
    fun trainingBinding(): LearnedOutcomeValueTrainingBinding = learnedOutcomeValueTrainingBinding(manifest)
    fun trainingBindings(provenance: ResearchRunProvenance): ResearchRunBindings =
        learnedOutcomeValueTrainingBindings(manifest, provenance)
    fun matchesTraining(training: LearnedOutcomeValueTrainingBinding): Boolean =
        trainingBinding() == training
    fun requirePromotion(promotion: PromotedOutcomeValueCheckpoint) {
        require(promotion.corpusIdentity == manifest.researchRunIdentity)
        require(promotion.pairSplitIdentity == outcomeValueIdentity("outcome-state-split", manifest.splitBindingSha256))
    }
    /** TEST frames and labels are inaccessible through ordinary corpus access. */
    fun examples(split: OutcomeStateCorpusSplit): List<OutcomeValueExample> {
        require(split != OutcomeStateCorpusSplit.TEST) { "TEST examples require a promoted outcome-value checkpoint" }
        return developmentExamples.getValue(split)
    }

    /** The loader, rather than a host convention, owns sealed TEST decoding and feature compilation. */
    fun sealedTestExamples(promotion: PromotedOutcomeValueCheckpoint): List<OutcomeValueExample> = loadSealedTestExamples(promotion)

    /** Diagnostic metadata remains private to this verified corpus owner and never enters fitting. */
    fun signalExamples(split: OutcomeStateCorpusSplit): List<OutcomeValueSignalExample> {
        require(split != OutcomeStateCorpusSplit.TEST) { "TEST signal rows require a promoted outcome-value checkpoint" }
        return loadDevelopmentSignalExamples(split)
    }

    /** TEST diagnostic rows use the same promotion-bound sealed corpus path as descriptive TEST metrics. */
    fun sealedTestSignalExamples(promotion: PromotedOutcomeValueCheckpoint): List<OutcomeValueSignalExample> =
        loadSealedTestSignalExamples(promotion)

    /** The historical audit is diagnostic-only and is not interchangeable with production promotion. */
    fun historicallyAuditedTestSignalExamples(audit: HistoricalOutcomeValueDiagnosticCheckpoint): List<OutcomeValueSignalExample> =
        loadHistoricallyAuditedTestSignalExamples(audit)

    companion object {
        fun load(corpusDirectory: Path): LoadedOutcomeValueCorpus = Loader.load(corpusDirectory)
        fun selectRetainedTrainFrames(corpusDirectory: Path, expectedCorpusIdentity: String): List<OutcomeValueCorpusFrame> =
            Loader.selectRetainedTrainFrames(corpusDirectory, expectedCorpusIdentity)

        /** Nested so private construction is physically co-located with verified loading. */
        private object Loader {
            fun load(corpusDirectory: Path): LoadedOutcomeValueCorpus {
                val manifest = verifiedManifest(corpusDirectory)
                val development = OutcomeStateCorpusSplit.entries.filter { it != OutcomeStateCorpusSplit.TEST }
                    .associateWith { mutableListOf<OutcomeValueExample>() }
                manifest.games.filter { it.split != OutcomeStateCorpusSplit.TEST }
                    .sortedWith(compareBy({ it.pairIndex }, { if (it.leg == "a") 0 else 1 })).forEach { game ->
                    development.getValue(game.split) += loadFrames(corpusDirectory, game).map(OutcomeValueCorpusFrame::example)
                    }
                return LoadedOutcomeValueCorpus(
                    manifest,
                    development.mapValues { (_, examples) -> canonicalOutcomeValueOrder(examples) },
                    { promotion ->
                        require(promotion.corpusIdentity == manifest.researchRunIdentity)
                        require(promotion.pairSplitIdentity == outcomeValueIdentity("outcome-state-split", manifest.splitBindingSha256))
                        ResearchRunArtifacts.loadAndVerify(corpusDirectory, manifest.researchRunIdentity)
                        manifest.games.filter { it.split == OutcomeStateCorpusSplit.TEST }
                            .sortedWith(compareBy({ it.pairIndex }, { if (it.leg == "a") 0 else 1 }))
                            .flatMap { game -> loadFrames(corpusDirectory, game).map(OutcomeValueCorpusFrame::example) }
                            .let(::canonicalOutcomeValueOrder)
                    },
                    { split ->
                        require(split != OutcomeStateCorpusSplit.TEST)
                        manifest.games.filter { it.split == split }
                            .sortedWith(compareBy({ it.pairIndex }, { if (it.leg == "a") 0 else 1 }))
                            .flatMap { game -> loadSignalExamples(corpusDirectory, game) }
                            .sortedWith(compareBy({ it.example.pairIndex }, { if (it.example.leg == "a") 0 else 1 }, { it.example.frameIndex }))
                    },
                    { promotion ->
                        require(promotion.corpusIdentity == manifest.researchRunIdentity)
                        require(promotion.pairSplitIdentity == outcomeValueIdentity("outcome-state-split", manifest.splitBindingSha256))
                        ResearchRunArtifacts.loadAndVerify(corpusDirectory, manifest.researchRunIdentity)
                        manifest.games.filter { it.split == OutcomeStateCorpusSplit.TEST }
                            .sortedWith(compareBy({ it.pairIndex }, { if (it.leg == "a") 0 else 1 }))
                            .flatMap { game -> loadSignalExamples(corpusDirectory, game) }
                            .sortedWith(compareBy({ it.example.pairIndex }, { if (it.example.leg == "a") 0 else 1 }, { it.example.frameIndex }))
                    },
                    { audit ->
                        require(audit.corpusIdentity == manifest.researchRunIdentity)
                        require(audit.pairSplitIdentity == outcomeValueIdentity("outcome-state-split", manifest.splitBindingSha256))
                        ResearchRunArtifacts.loadAndVerify(corpusDirectory, manifest.researchRunIdentity)
                        manifest.games.filter { it.split == OutcomeStateCorpusSplit.TEST }
                            .sortedWith(compareBy({ it.pairIndex }, { if (it.leg == "a") 0 else 1 }))
                            .flatMap { game -> loadSignalExamples(corpusDirectory, game) }
                            .sortedWith(compareBy({ it.example.pairIndex }, { if (it.example.leg == "a") 0 else 1 }, { it.example.frameIndex }))
                    },
                )
            }

            /**
             * Retained parity needs four predeclared TRAIN strata, not corpus-scale feature work.
             * Verify the complete registered corpus first, then decode only the earliest eligible
             * frame for each recorded root player and actor/root relation.
             */
            fun selectRetainedTrainFrames(corpusDirectory: Path, expectedCorpusIdentity: String): List<OutcomeValueCorpusFrame> {
                val manifest = verifiedManifest(corpusDirectory, expectedCorpusIdentity)
                val required = listOf("p0", "p1").flatMap { root ->
                    listOf(OutcomeValueActorRelation.ROOT, OutcomeValueActorRelation.OPPONENT).map { root to it }
                }
                val selected = linkedMapOf<Pair<String, OutcomeValueActorRelation>, OutcomeValueCorpusFrame>()
                manifest.games.asSequence()
                    .filter { it.split == OutcomeStateCorpusSplit.TRAIN }
                    .sortedWith(compareBy<OutcomeStateGameArtifact>({ it.pairIndex }, { if (it.leg == "a") 0 else 1 }))
                    .forEach { game ->
                        if (required.none { key -> key.first == game.rootPlayerId && key !in selected }) return@forEach
                        loadFrames(corpusDirectory, game).forEach { frame ->
                            val key = frame.rootPlayerId to frame.example.actorRelation
                            if (key in required &&
                                (frame.example.actorRelation != OutcomeValueActorRelation.ROOT ||
                                    frame.information.candidates.isNotEmpty())
                            ) selected.putIfAbsent(key, frame)
                        }
                    }
                return required.map { key ->
                    requireNotNull(selected[key]) {
                        "Verified TRAIN corpus contains no ${key.second} frame for root ${key.first}"
                    }
                }
            }

            private fun verifiedManifest(corpusDirectory: Path, expectedCorpusIdentity: String? = null): OutcomeStateCorpusManifest {
                val manifest = trainingJson.decodeFromString<OutcomeStateCorpusManifest>(
                    Files.readString(ResearchRunFiles.resolveBelow(corpusDirectory, "corpus.json"))
                )
                require(expectedCorpusIdentity == null || manifest.researchRunIdentity == expectedCorpusIdentity) {
                    "Retained parity audit refuses a substituted corpus identity"
                }
                val expected = outcomeStateCorpusBindings(
                    historical = manifest.historical, producer = manifest.producer,
                    trainingProjection = manifest.trainingProjection,
                    inputInventorySha256 = manifest.inputInventorySha256, deckHash = manifest.deckHash,
                    cardPoolHash = manifest.cardPoolHash, splitBindingSha256 = manifest.splitBindingSha256,
                )
                require(manifest.researchRunIdentity == expected.identity) {
                    "Outcome-state corpus manifest identity disagrees with its bound corpus material"
                }
                ResearchRunArtifacts.loadAndVerify(corpusDirectory, expected.identity)
                return manifest
            }

            private fun loadFrames(corpusDirectory: Path, game: OutcomeStateGameArtifact): List<OutcomeValueCorpusFrame> {
                val bundlePath = ResearchRunFiles.resolveBelow(corpusDirectory, game.bundleReference)
                require(Files.isRegularFile(bundlePath) && researchSha256(Files.readAllBytes(bundlePath)) == game.bundleSha256)
                val bundle = readCompressedInspection(bundlePath)
                require(bundle.gameId == game.derivedBundleId && bundle.outcome.terminated && !bundle.outcome.truncated)
                require(bundle.outcome.resultByPlayer.getValue(game.rootPlayerId) == game.actualTerminalPayoff)
                return bundle.frames.filter { !it.terminated }.map { frame ->
                    outcomeValueCorpusFrame(game, frame.frameIndex, bundle.informationState(frame.frameIndex))
                }
            }

            private fun loadSignalExamples(
                corpusDirectory: Path,
                game: OutcomeStateGameArtifact,
            ): List<OutcomeValueSignalExample> {
                val bundlePath = ResearchRunFiles.resolveBelow(corpusDirectory, game.bundleReference)
                require(Files.isRegularFile(bundlePath) && researchSha256(Files.readAllBytes(bundlePath)) == game.bundleSha256)
                val bundle = readCompressedInspection(bundlePath)
                require(bundle.gameId == game.derivedBundleId && bundle.outcome.terminated && !bundle.outcome.truncated)
                require(bundle.outcome.resultByPlayer.getValue(game.rootPlayerId) == game.actualTerminalPayoff)
                return bundle.frames.filter { !it.terminated }.map { frame ->
                    val information = bundle.informationState(frame.frameIndex)
                    val actor = requireNotNull(information.actingPlayerId) { "Nonterminal frame must name an actor" }
                    OutcomeValueSignalExample(
                        example = OutcomeValueExample(
                            game.pairIndex, game.leg, frame.frameIndex,
                            if (actor == game.rootPlayerId) OutcomeValueActorRelation.ROOT else OutcomeValueActorRelation.OPPONENT,
                            LearnedOutcomeValueFeatureCompiler.compile(information, game.rootPlayerId), game.actualTerminalPayoff,
                        ),
                        phase = information.observation.phase,
                        rootSeat = game.rootPlayerId,
                        visibleHeuristicPrediction = MonoRedInformationEvaluator.evaluate(information, game.rootPlayerId),
                    )
                }
            }
        }
    }
}

/**
 * Read-only diagnostic authority for a historical checkpoint whose committed trainer source no
 * longer equals the current source. It is deliberately distinct from [PromotedOutcomeValueCheckpoint]:
 * it cannot enter a pilot, persist evidence, or be supplied to any production evaluator host.
 *
 * The sole factory verifies the retained corpus and every completed gate-stage artifact before it
 * decodes a payload. TEST bundles remain sealed until [globalSignalPartitions] is called on this
 * already-authenticated final capability.
 */
internal class HistoricalOutcomeValueDiagnosticCheckpoint private constructor(
    private val corpus: LoadedOutcomeValueCorpus,
    private val evaluator: LearnedOutcomeValueEvaluator,
    private val evidence: HistoricalOutcomeValueCheckpointEvidence,
) {
    val trainingRunIdentity: String get() = evidence.trainingRunIdentity
    val validationRunIdentity: String get() = evidence.validationRunIdentity
    val testRunIdentity: String get() = evidence.testRunIdentity
    val checkpointPayloadSha256: String get() = evidence.checkpointPayloadSha256
    val corpusIdentity: String get() = evidence.training.corpusIdentity
    val pairSplitIdentity: String get() = evidence.training.pairSplitIdentity
    internal val frozenTrainConstantPrediction: Double get() = evidence.frozenTrainConstantPrediction

    /** The exact authenticated historical payload, exposed only to diagnostic-only hosts. */
    internal fun diagnosticEvaluator(): LearnedOutcomeValueEvaluator = evaluator

    /**
     * Computes the fixed-root reference from TRAIN alone. Unlike [globalSignalPartitions], this
     * method has no path to validation/test signal rows or held-out frame materialization.
     */
    internal fun trainOnlyFeatureReference(): OutcomeValueTrainFeatureReference =
        trainFeatureReference(evidence.training, corpus.examples(OutcomeStateCorpusSplit.TRAIN))

    /** This is the only held-out opening in the historical diagnostic path. */
    internal fun globalSignalPartitions(): List<OutcomeValueGlobalSignalPartitionReport> = listOf(
        OutcomeValueGlobalSignalDiagnostic.evaluate(
            OutcomeStateCorpusSplit.TRAIN,
            corpus.signalExamples(OutcomeStateCorpusSplit.TRAIN),
            evaluator,
            evidence.frozenTrainConstantPrediction,
        ),
        OutcomeValueGlobalSignalDiagnostic.evaluate(
            OutcomeStateCorpusSplit.VALIDATION,
            corpus.signalExamples(OutcomeStateCorpusSplit.VALIDATION),
            evaluator,
            evidence.frozenTrainConstantPrediction,
        ),
        OutcomeValueGlobalSignalDiagnostic.evaluate(
            OutcomeStateCorpusSplit.TEST,
            corpus.historicallyAuditedTestSignalExamples(this),
            evaluator,
            evidence.frozenTrainConstantPrediction,
        ),
    )

    companion object {
        fun load(corpusDirectory: Path, gateDirectory: Path): HistoricalOutcomeValueDiagnosticCheckpoint {
            // This verified development load intentionally has no TEST bundle materialization path.
            val corpus = LoadedOutcomeValueCorpus.load(corpusDirectory)
            val evidence = verifyHistoricalOutcomeValueCheckpointEvidence(
                corpus.trainingBinding(),
                ResearchRunFiles.resolveBelow(gateDirectory, "training"),
                ResearchRunFiles.resolveBelow(gateDirectory, "validation"),
                ResearchRunFiles.resolveBelow(gateDirectory, "test"),
            )
            return HistoricalOutcomeValueDiagnosticCheckpoint(
                corpus,
                LearnedOutcomeValueEvaluator.fromCheckpoint(evidence.payload),
                evidence,
            )
        }
    }
}

/** Private historical evidence, intentionally unavailable as a production capability. */
private data class HistoricalOutcomeValueCheckpointEvidence(
    val trainingRunIdentity: String,
    val validationRunIdentity: String,
    val testRunIdentity: String,
    val checkpointPayloadSha256: String,
    val frozenTrainConstantPrediction: Double,
    val training: LearnedOutcomeValueTrainingBinding,
    val payload: LearnedOutcomeValueCheckpointPayload,
)

/**
 * Authenticates the retained gate records without recomputing current trainer/validator provenance.
 * Those old source identities are no longer reproducible from the current checkout; the audit
 * instead verifies their recorded completed artifacts, their recorded run identities, and every
 * immutable corpus/checkpoint/policy/projection/configuration cross-binding carried by the payload.
 */
private fun verifyHistoricalOutcomeValueCheckpointEvidence(
    expectedTraining: LearnedOutcomeValueTrainingBinding,
    trainingDirectory: Path,
    validationDirectory: Path,
    testDirectory: Path,
): HistoricalOutcomeValueCheckpointEvidence {
    val trainingManifest = ResearchRunArtifacts.loadAndVerify(trainingDirectory)
    val trainingReport = trainingJson.decodeFromString<OutcomeValueTrainingReport>(
        Files.readString(ResearchRunFiles.resolveBelow(trainingDirectory, LEARNED_OUTCOME_VALUE_TRAINING_REPORT_FILE)),
    )
    require(trainingReport.schemaVersion == 2)
    require(trainingManifest.researchRunIdentity == trainingReport.trainingRunIdentity)

    val envelope = ResearchRunCheckpoints.load(
        ResearchRunFiles.resolveBelow(trainingDirectory, LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE),
    )
    require(envelope.researchRunIdentity == trainingReport.trainingRunIdentity)
    require(envelope.payloadSchema == LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1)
    require(envelope.sequence == 0L && envelope.parentPayloadSha256 == null)
    require(researchSha256(envelope.payload()) == envelope.payloadSha256)
    require(trainingReport.checkpointPayloadSha256 == envelope.payloadSha256)
    require(trainingReport.trainTerminalPayoffMean.isFinite())
    val payload = trainingJson.decodeFromString<LearnedOutcomeValueCheckpointPayload>(envelope.payload().decodeToString())
    require(payload.training == expectedTraining) {
        "Historical checkpoint corpus/split/policy/environment/projection/configuration binding disagrees with verified corpus"
    }
    require(payload.modelAlgorithmId == LEARNED_OUTCOME_VALUE_MODEL_V1)
    require(payload.featureSchemaId == LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1)
    require(payload.featureScalingId == LEARNED_OUTCOME_VALUE_FEATURE_SCALING_V1)
    require(payload.targetId == LEARNED_OUTCOME_VALUE_TARGET_V1)

    val validationManifest = ResearchRunArtifacts.loadAndVerify(validationDirectory)
    val validationReport = trainingJson.decodeFromString<OutcomeValueValidationReport>(
        Files.readString(ResearchRunFiles.resolveBelow(validationDirectory, LEARNED_OUTCOME_VALUE_VALIDATION_REPORT_FILE)),
    )
    require(validationReport.schemaVersion == 1)
    require(validationManifest.researchRunIdentity == validationReport.validationRunIdentity)
    require(validationReport.trainingRunIdentity == trainingReport.trainingRunIdentity)
    require(validationReport.immutableCheckpointPayloadSha256 == envelope.payloadSha256)
    require(validationReport.validationPairSplitIdentity == expectedTraining.pairSplitIdentity)
    require(validationReport.passed && validationReport.failures.isEmpty()) {
        "Historical diagnostic requires the recorded validation PASS"
    }

    val testManifest = ResearchRunArtifacts.loadAndVerify(testDirectory)
    val testReport = trainingJson.decodeFromString<OutcomeValueTestReport>(
        Files.readString(ResearchRunFiles.resolveBelow(testDirectory, LEARNED_OUTCOME_VALUE_TEST_REPORT_FILE)),
    )
    val expectedTestRun = ResearchRunBindings(
        protocol = LEARNED_OUTCOME_VALUE_TEST_PROTOCOL,
        material = mapOf(
            "validation-run" to validationReport.validationRunIdentity,
            "checkpoint" to envelope.payloadSha256,
            "corpus" to expectedTraining.corpusIdentity,
            "test-pair-split" to expectedTraining.pairSplitIdentity,
        ),
    ).identity
    require(testManifest.researchRunIdentity == expectedTestRun)
    require(testReport.testRunIdentity == expectedTestRun)
    require(testReport.validationRunIdentity == validationReport.validationRunIdentity)
    require(testReport.immutableCheckpointPayloadSha256 == envelope.payloadSha256)
    return HistoricalOutcomeValueCheckpointEvidence(
        trainingRunIdentity = trainingReport.trainingRunIdentity,
        validationRunIdentity = validationReport.validationRunIdentity,
        testRunIdentity = testReport.testRunIdentity,
        checkpointPayloadSha256 = envelope.payloadSha256,
        frozenTrainConstantPrediction = trainingReport.trainTerminalPayoffMean,
        training = expectedTraining,
        payload = payload,
    )
}

/** Private evidence audit seam: verified TRAIN frames only, never TEST. */
internal fun loadVerifiedOutcomeValueCorpusRetainedTrainFrames(
    corpusDirectory: Path,
    expectedCorpusIdentity: String,
): List<OutcomeValueCorpusFrame> =
    LoadedOutcomeValueCorpus.selectRetainedTrainFrames(corpusDirectory, expectedCorpusIdentity)

/** The sole frame-to-training-row transformation, shared by corpus loading and retained audits. */
internal fun outcomeValueCorpusFrame(
    game: OutcomeStateGameArtifact,
    frameIndex: Int,
    information: PolicyInformationState,
): OutcomeValueCorpusFrame {
    require(!information.terminated) { "Outcome-value corpus rows require a nonterminal information state" }
    val actor = requireNotNull(information.actingPlayerId) { "Nonterminal frame must name an actor" }
    return OutcomeValueCorpusFrame(
        OutcomeValueExample(
            game.pairIndex,
            game.leg,
            frameIndex,
            if (actor == game.rootPlayerId) OutcomeValueActorRelation.ROOT else OutcomeValueActorRelation.OPPONENT,
            LearnedOutcomeValueFeatureCompiler.compile(information, game.rootPlayerId),
            game.actualTerminalPayoff,
        ),
        game.rootPlayerId,
        information,
    )
}

/** Final host authority. Its only origin captures provenance and verifies a corpus directory. */
internal class PreparedOutcomeValueTraining private constructor(
    private val corpus: LoadedOutcomeValueCorpus,
    private val training: LearnedOutcomeValueTrainingBinding,
    private val bindings: ResearchRunBindings,
    private val provenance: ResearchRunProvenance,
) {
    val trainingRunIdentity: String get() = bindings.identity

    private fun restore(directory: Path): Pair<LoadedOutcomeValueCheckpoint, OutcomeValueTrainingReport> {
        val checkpoint = OutcomeValueCheckpointHostLoader.load(
            ResearchRunFiles.resolveBelow(directory, LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE), bindings, training,
        )
        return checkpoint to loadOutcomeValueTrainingReport(directory, bindings, checkpoint)
    }

    private fun fitAndPersist(directory: Path): Pair<LoadedOutcomeValueCheckpoint, OutcomeValueTrainingReport> {
        val fit = fitLoadedOutcomeValueCorpus(corpus)
        persistFit(directory, fit)
        return restore(directory)
    }

    /** The prepared authority, not a caller-supplied binding, owns checkpoint persistence. */
    private fun persistFit(directory: Path, fit: LoadedOutcomeValueFit): OutcomeValueTrainingReport =
        persistOutcomeValueTraining(directory, bindings, fit)

    private fun validationBindings(checkpoint: LoadedOutcomeValueCheckpoint): ResearchRunBindings =
        learnedOutcomeValueValidationBindings(bindings, checkpoint, training, provenance)

    internal fun admissionMaterial(
        trainingDirectory: Path,
        validationDirectory: Path,
    ): AdmissionMaterial {
        val (checkpoint, trainingReport) = if (Files.exists(ResearchRunFiles.resolveBelow(trainingDirectory, ResearchRunArtifacts.MANIFEST_FILE))) {
            restore(trainingDirectory)
        } else {
            fitAndPersist(trainingDirectory)
        }
        val validationBindings = validationBindings(checkpoint)
        val canonicalValidationBindings = learnedOutcomeValueValidationBindings(bindings, checkpoint, training, provenance)
        require(validationBindings == canonicalValidationBindings) { "Validation must use the canonical protocol, gate specification, and committed validator source" }
        val report = if (Files.exists(ResearchRunFiles.resolveBelow(validationDirectory, ResearchRunArtifacts.MANIFEST_FILE))) {
            loadOutcomeValueValidationReport(validationDirectory, validationBindings, bindings, checkpoint)
        } else {
            validateLoadedOutcomeValueCorpus(
                validationBindings, bindings, checkpoint, training, corpus, trainingReport,
                committedOutcomeValueValidatorSource(provenance),
            ).also { persistOutcomeValueValidation(validationDirectory, it) }
        }
        return AdmissionMaterial(this, checkpoint, validationBindings, report)
    }

    internal fun evaluatePromotedTest(
        promotion: PromotedOutcomeValueCheckpoint,
        testBindings: ResearchRunBindings,
    ): OutcomeValueTestReport = evaluateLoadedOutcomeValueTest(testBindings, promotion, corpus)

    fun requirePilotCompatibility(
        deckHash: String,
        cardPoolHash: String,
        actionSpaceProfile: SearchActionSpaceProfile,
        argentumCommit: String,
    ) {
        require(corpus.deckHash == deckHash && corpus.cardPoolHash == cardPoolHash) {
            "Pilot deck/card-pool material differs from the promoted outcome-value corpus"
        }
        require(corpus.actionSpaceProfile == actionSpaceProfile) {
            "Pilot action-space profile differs from the promoted outcome-value corpus"
        }
        require(argentumCommit == OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT) {
            "Pilot Argentum revision differs from the promoted outcome-value corpus"
        }
        require(training.environmentProfileIdentity == outcomeValueIdentity(
            "outcome-state-environment", "$deckHash|$cardPoolHash|${actionSpaceProfile.profileId}",
        )) {
            "Pilot environment differs from the promoted outcome-value training binding"
        }
    }

    companion object {
        fun load(corpusDirectory: Path, repositoryRoot: Path): PreparedOutcomeValueTraining {
            val provenance = ResearchRunProvenance.capture(repositoryRoot)
            val corpus = LoadedOutcomeValueCorpus.load(corpusDirectory)
            val training = corpus.trainingBinding()
            require(corpus.matchesTraining(training))
            return PreparedOutcomeValueTraining(corpus, training, corpus.trainingBindings(provenance), provenance)
        }
    }
}

/** Private material produced only by a genuine prepared authority; no persistence or promotion API accepts it. */
internal class AdmissionMaterial internal constructor(
    internal val prepared: PreparedOutcomeValueTraining,
    internal val checkpoint: LoadedOutcomeValueCheckpoint,
    internal val bindings: ResearchRunBindings,
    internal val report: OutcomeValueValidationReport,
)

internal class OutcomeValueAdmission private constructor(
    private val material: AdmissionMaterial,
) {
    val validationRunIdentity: String get() = material.bindings.identity
    val passed: Boolean get() = material.report.passed

    fun promote(): PromotedOutcomeValueCheckpoint = PromotedOutcomeValueCheckpoint.fromAdmission(this)

    fun evaluateAndPersistTest(directory: Path): OutcomeValueTestReport {
        val promotion = promote()
        val testBindings = learnedOutcomeValueTestBindings(promotion)
        val result = material.prepared.evaluatePromotedTest(promotion, testBindings)
        persistOutcomeValueTest(directory, result)
        return result
    }

    fun loadTest(directory: Path): OutcomeValueTestReport {
        val promotion = promote()
        return loadOutcomeValueTestReport(directory, learnedOutcomeValueTestBindings(promotion), material.report, material.checkpoint)
    }

    /** Only promotion may retrieve the checked recorded material, and only after PASS. */
    internal fun promotionMaterial(): AdmissionMaterial {
        require(passed) { "Promotion requires a recorded validation PASS" }
        return material
    }

    companion object {
        fun fromPrepared(prepared: PreparedOutcomeValueTraining, trainingDirectory: Path, validationDirectory: Path): OutcomeValueAdmission =
            OutcomeValueAdmission(prepared.admissionMaterial(trainingDirectory, validationDirectory))
    }
}

internal class PromotedOutcomeValueCheckpoint private constructor(
    private val admission: OutcomeValueAdmission,
) {
    private val material: AdmissionMaterial get() = admission.promotionMaterial()
    fun evaluator(): LearnedOutcomeValueEvaluator = material.checkpoint.evaluator
    /** The verified immutable checkpoint envelope's training-run identity. */
    val trainingRunIdentity: String get() = material.checkpoint.envelope.researchRunIdentity
    val validationRunIdentity: String get() = material.report.validationRunIdentity
    val validationGateIdentity: String get() = material.bindings.material.getValue("gate-specification")
    val validatorSourceIdentity: String get() = material.bindings.material.getValue("validator-source")
    val trainingEnvelopePayloadSha256: String get() = material.checkpoint.envelope.payloadSha256
    val corpusIdentity: String get() = material.checkpoint.evaluator.checkpointIdentity.training.corpusIdentity
    val pairSplitIdentity: String get() = material.report.validationPairSplitIdentity
    fun requirePilotCompatibility(
        deckHash: String,
        cardPoolHash: String,
        actionSpaceProfile: SearchActionSpaceProfile,
        argentumCommit: String,
    ) = material.prepared.requirePilotCompatibility(deckHash, cardPoolHash, actionSpaceProfile, argentumCommit)
        fun requireTestBinding(testBindings: ResearchRunBindings) { require(testBindings == learnedOutcomeValueTestBindings(this)) }
        /** Safe evaluator metadata for metric arithmetic; it cannot create descriptive TEST evidence. */
        internal fun trainingFeatureUniverse(): Set<String> = material.checkpoint.evaluatorTrainUniverse()
    companion object {
        fun fromAdmission(admission: OutcomeValueAdmission): PromotedOutcomeValueCheckpoint {
            require(admission.passed) { "Promotion requires a recorded validation PASS" }
            return PromotedOutcomeValueCheckpoint(admission)
        }
    }
}


/** Host-side trust boundary: verify generic envelope and bound material before evaluator decoding. */
internal object OutcomeValueCheckpointHostLoader {
    fun load(
        path: Path,
        expectedTrainingRun: ResearchRunBindings,
        expectedTraining: LearnedOutcomeValueTrainingBinding,
    ): LoadedOutcomeValueCheckpoint {
        val envelope = try {
            ResearchRunCheckpoints.load(path)
        } catch (failure: Exception) {
            throw OutcomeValueTrainingException(OutcomeValueTrainingFailureKind.CHECKPOINT_ENVELOPE_INVALID, "Checkpoint envelope is malformed", failure)
        }
        if (envelope.researchRunIdentity != expectedTrainingRun.identity ||
            envelope.payloadSchema != LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1 || envelope.sequence != 0L ||
            researchSha256(envelope.payload()) != envelope.payloadSha256
        ) throw OutcomeValueTrainingException(OutcomeValueTrainingFailureKind.CHECKPOINT_ENVELOPE_INVALID, "Checkpoint envelope identity, schema, sequence, or hash disagrees")
        val payload = try {
            trainingJson.decodeFromString<LearnedOutcomeValueCheckpointPayload>(envelope.payload().decodeToString())
        } catch (failure: Exception) {
            throw OutcomeValueTrainingException(OutcomeValueTrainingFailureKind.CHECKPOINT_ENVELOPE_INVALID, "Checkpoint payload is malformed", failure)
        }
        if (payload.training != expectedTraining || payload.modelAlgorithmId != LEARNED_OUTCOME_VALUE_MODEL_V1 ||
            payload.featureSchemaId != LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1 ||
            payload.featureScalingId != LEARNED_OUTCOME_VALUE_FEATURE_SCALING_V1 || payload.targetId != LEARNED_OUTCOME_VALUE_TARGET_V1
        ) throw OutcomeValueTrainingException(OutcomeValueTrainingFailureKind.CHECKPOINT_ENVELOPE_INVALID, "Checkpoint material bindings disagree with the host expectation")
        verifyTrainingRunMaterials(expectedTrainingRun, expectedTraining)
        val evaluator = LearnedOutcomeValueEvaluator.load(envelope.payload())
        if (evaluator.checkpointIdentity.payloadSha256 != envelope.payloadSha256) {
            throw OutcomeValueTrainingException(OutcomeValueTrainingFailureKind.CHECKPOINT_ENVELOPE_INVALID, "Canonical evaluator payload hash disagrees with envelope")
        }
        return LoadedOutcomeValueCheckpoint(envelope, evaluator)
    }

    /**
     * Retained-evidence diagnostic entry point.  The registered artifact manifest authenticates
     * the completed training directory; these exact historical identifiers then bind its opaque
     * checkpoint without pretending that the current source generated the old training run.
     */
    fun loadRetained(
        trainingDirectory: Path,
        expectedTrainingRunIdentity: String,
        expectedCorpusIdentity: String,
        expectedPayloadSha256: String,
    ): LoadedOutcomeValueCheckpoint {
        ResearchRunArtifacts.loadAndVerify(trainingDirectory, expectedTrainingRunIdentity)
        val envelope = try {
            ResearchRunCheckpoints.load(
                ResearchRunFiles.resolveBelow(trainingDirectory, LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE),
            )
        } catch (failure: Exception) {
            throw OutcomeValueTrainingException(
                OutcomeValueTrainingFailureKind.CHECKPOINT_ENVELOPE_INVALID,
                "Retained checkpoint envelope is malformed",
                failure,
            )
        }
        if (envelope.researchRunIdentity != expectedTrainingRunIdentity ||
            envelope.payloadSchema != LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1 ||
            envelope.sequence != 0L || envelope.payloadSha256 != expectedPayloadSha256 ||
            researchSha256(envelope.payload()) != envelope.payloadSha256
        ) throw OutcomeValueTrainingException(
            OutcomeValueTrainingFailureKind.CHECKPOINT_ENVELOPE_INVALID,
            "Retained checkpoint identity, schema, sequence, or hash disagrees",
        )
        val evaluator = LearnedOutcomeValueEvaluator.load(envelope.payload())
        if (evaluator.checkpointIdentity.payloadSha256 != expectedPayloadSha256 ||
            evaluator.checkpointIdentity.training.corpusIdentity != expectedCorpusIdentity
        ) throw OutcomeValueTrainingException(
            OutcomeValueTrainingFailureKind.CHECKPOINT_ENVELOPE_INVALID,
            "Retained checkpoint payload does not bind the expected corpus",
        )
        return LoadedOutcomeValueCheckpoint(envelope, evaluator)
    }
}

private fun verifyTrainingRunMaterials(
    bindings: ResearchRunBindings,
    training: LearnedOutcomeValueTrainingBinding,
) {
    val material = bindings.material
    if (bindings.protocol != LEARNED_OUTCOME_VALUE_TRAINING_PROTOCOL ||
        material["corpus"] != training.corpusIdentity || material["pair-split"] != training.pairSplitIdentity ||
        material["projection"] != training.projectionIdentity ||
        material["feature-schema"] != LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1 ||
        material["feature-scaling"] != LEARNED_OUTCOME_VALUE_FEATURE_SCALING_V1 ||
        material["model"] != LEARNED_OUTCOME_VALUE_MODEL_V1 || material["target"] != LEARNED_OUTCOME_VALUE_TARGET_V1 ||
        material["root-behavior"] != training.rootBehaviorPolicyIdentity ||
        material["opponent-behavior"] != training.opponentBehaviorPolicyIdentity ||
        material["environment"] != training.environmentProfileIdentity || material["solver"] != LEARNED_OUTCOME_VALUE_SOLVER ||
        material["solver-cap"] != SOLVER_CAP_RULE || material["solver-restart"] != SOLVER_RESTART_RULE ||
        material["solver-preconditioner"] != SOLVER_PRECONDITIONER || material["solver-start"] != SOLVER_START ||
        material["solver-residual"] != SOLVER_RESIDUAL_RULE || material["solver-certification"] != SOLVER_CERTIFICATION_RULE ||
        material["lambda"] != RIDGE_LAMBDA.toString() || material["ordering"] != LEARNED_OUTCOME_VALUE_ORDERING ||
        material["weighting"] != LEARNED_OUTCOME_VALUE_WEIGHTING ||
        material["learner-configuration"] != training.learnerConfigurationIdentity ||
        material["argentum"] != OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT || material["deck"].isNullOrBlank() ||
        material["card-pool"].isNullOrBlank() || material["corpus-manifest"].isNullOrBlank() ||
        material["producer-source"].isNullOrBlank() || material["trainer-source"].isNullOrBlank()
    ) throw OutcomeValueTrainingException(OutcomeValueTrainingFailureKind.CHECKPOINT_ENVELOPE_INVALID, "Host training-run material does not bind this checkpoint contract")
}

/** Production entry point: a completed corpus becomes one opaque, corpus-derived training authority. */
internal fun prepareLoadedOutcomeValueTraining(
    corpusDirectory: Path,
    repositoryRoot: Path,
): PreparedOutcomeValueTraining = PreparedOutcomeValueTraining.load(corpusDirectory, repositoryRoot)

internal fun learnedOutcomeValueTrainingBindings(
    corpus: OutcomeStateCorpusManifest,
    trainerProvenance: ResearchRunProvenance,
): ResearchRunBindings {
    trainerProvenance.requireReady()
    require(!trainerProvenance.outerDirty && !trainerProvenance.engineDirty) {
        "Learned-outcome training evidence requires committed clean trainer and Argentum source"
    }
    val trainerSource = trainerProvenance.sourceProvenance
    require(trainerSource.expectedArgentumRevision == OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT)
    val training = learnedOutcomeValueTrainingBinding(corpus)
    return ResearchRunBindings(
        protocol = LEARNED_OUTCOME_VALUE_TRAINING_PROTOCOL,
        material = mapOf(
            "corpus" to corpus.researchRunIdentity,
            "corpus-manifest" to outcomeValueIdentity("corpus-manifest", researchSha256(trainingJson.encodeToString(corpus))),
            "pair-split" to training.pairSplitIdentity,
            "projection" to training.projectionIdentity,
            "learner-configuration" to training.learnerConfigurationIdentity,
            "producer-source" to outcomeValueIdentity("producer-source", researchSha256(trainingJson.encodeToString(corpus.producer.sourceProvenance))),
            "trainer-source" to outcomeValueIdentity("trainer-source", researchSha256(trainingJson.encodeToString(trainerSource))),
            "argentum" to OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
            "feature-schema" to LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1,
            "feature-scaling" to LEARNED_OUTCOME_VALUE_FEATURE_SCALING_V1,
            "model" to LEARNED_OUTCOME_VALUE_MODEL_V1,
            "target" to LEARNED_OUTCOME_VALUE_TARGET_V1,
            "root-behavior" to training.rootBehaviorPolicyIdentity,
            "opponent-behavior" to training.opponentBehaviorPolicyIdentity,
            "environment" to training.environmentProfileIdentity,
            "deck" to corpus.deckHash,
            "card-pool" to corpus.cardPoolHash,
            "solver" to LEARNED_OUTCOME_VALUE_SOLVER,
            "solver-cap" to SOLVER_CAP_RULE,
            "solver-restart" to SOLVER_RESTART_RULE,
            "solver-preconditioner" to SOLVER_PRECONDITIONER,
            "solver-start" to SOLVER_START,
            "solver-residual" to SOLVER_RESIDUAL_RULE,
            "solver-certification" to SOLVER_CERTIFICATION_RULE,
            "lambda" to RIDGE_LAMBDA.toString(),
            "ordering" to LEARNED_OUTCOME_VALUE_ORDERING,
            "weighting" to LEARNED_OUTCOME_VALUE_WEIGHTING,
        ),
    )
}

internal fun learnedOutcomeValueTrainingBinding(corpus: OutcomeStateCorpusManifest): LearnedOutcomeValueTrainingBinding =
    LearnedOutcomeValueTrainingBinding(
        corpusIdentity = corpus.researchRunIdentity,
        pairSplitIdentity = outcomeValueIdentity("outcome-state-split", corpus.splitBindingSha256),
        learnerConfigurationIdentity = outcomeValueIdentity(
            "learned-outcome-configuration",
            "$LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1|$LEARNED_OUTCOME_VALUE_FEATURE_SCALING_V1|$LEARNED_OUTCOME_VALUE_MODEL_V1|$LEARNED_OUTCOME_VALUE_TARGET_V1|$RIDGE_LAMBDA|$KKT_TOLERANCE|$LEARNED_OUTCOME_VALUE_SOLVER|$SOLVER_CAP_RULE|$SOLVER_RESTART_RULE|$SOLVER_PRECONDITIONER|$SOLVER_START|$SOLVER_RESIDUAL_RULE|$SOLVER_CERTIFICATION_RULE|$LEARNED_OUTCOME_VALUE_ORDERING|$LEARNED_OUTCOME_VALUE_WEIGHTING",
        ),
        projectionIdentity = corpus.trainingProjection.identity(),
        rootBehaviorPolicyIdentity = corpus.historical.controlPolicyEvidenceIdentity,
        opponentBehaviorPolicyIdentity = corpus.historical.treatmentPolicyEvidenceIdentity,
        environmentProfileIdentity = outcomeValueIdentity("outcome-state-environment", "${corpus.deckHash}|${corpus.cardPoolHash}|${corpus.actionSpaceProfile.profileId}"),
    )

private fun outcomeValueIdentity(prefix: String, material: String): String =
    "$prefix-sha256:${if (material.matches(Regex("[0-9a-f]{64}"))) material else researchSha256(material)}"

/** Low-level test seam; production persistence is owned by [PreparedOutcomeValueTraining]. */
private fun persistOutcomeValueTraining(
    directory: Path,
    bindings: ResearchRunBindings,
    loadedFit: LoadedOutcomeValueFit,
): OutcomeValueTrainingReport {
    val fit = loadedFit.fit
    val ordered = loadedFit.trainExamples()
    require(fit.evaluator.checkpointIdentity.training == loadedFit.training)
    verifyTrainingRunMaterials(bindings, fit.evaluator.checkpointIdentity.training)
    val payload = fit.evaluator.canonicalCheckpointBytes()
    val envelope = ResearchRunCheckpoints.persist(
        ResearchRunFiles.resolveBelow(directory, LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE),
        bindings.identity,
        LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1,
        0,
        payload,
    )
    val report = OutcomeValueTrainingReport(
        trainingRunIdentity = bindings.identity,
        checkpointPayloadSha256 = envelope.payloadSha256,
        rows = ordered.size,
        games = ordered.map(OutcomeValueExample::gameKey).distinct().size,
        trainTerminalPayoffMean = equalGameTerminalPayoffMean(ordered),
        solverIterations = fit.solverIterations,
        maxKktResidual = fit.maxKktResidual,
        coefficientL2Norm = fit.coefficientL2Norm,
    )
    require(report.coefficientL2Norm.isFinite())
    ResearchRunFiles.atomicWrite(ResearchRunFiles.resolveBelow(directory, LEARNED_OUTCOME_VALUE_TRAINING_REPORT_FILE), trainingJson.encodeToString(report) + "\n")
    ResearchRunArtifacts(directory, bindings.identity).also {
        it.register(LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE)
        it.register(LEARNED_OUTCOME_VALUE_TRAINING_REPORT_FILE)
        it.finalize()
    }
    return report
}

private fun loadOutcomeValueTrainingReport(
    directory: Path,
    expectedTrainingRun: ResearchRunBindings,
    checkpoint: LoadedOutcomeValueCheckpoint,
): OutcomeValueTrainingReport {
    ResearchRunArtifacts.loadAndVerify(directory, expectedTrainingRun.identity)
    val report = trainingJson.decodeFromString<OutcomeValueTrainingReport>(
        Files.readString(ResearchRunFiles.resolveBelow(directory, LEARNED_OUTCOME_VALUE_TRAINING_REPORT_FILE))
    )
    require(report.trainingRunIdentity == expectedTrainingRun.identity)
    require(report.checkpointPayloadSha256 == checkpoint.envelope.payloadSha256)
    require(report.schemaVersion == 2)
    val payload = trainingJson.decodeFromString<LearnedOutcomeValueCheckpointPayload>(checkpoint.evaluator.canonicalCheckpointPayload)
    val iterationCap = SOLVER_ITERATION_CAP_MULTIPLIER * payload.weights.size
    require(report.rows > 0 && report.games > 0 && report.solverIterations in 0..iterationCap)
    if (report.solverIterations == 0) {
        require(payload.weights.values.all { it == 0.0 } && report.maxKktResidual <= KKT_TOLERANCE) {
            "Zero solver iterations require the independently certified constant optimum"
        }
    }
    ensureFinite(listOf(report.trainTerminalPayoffMean, report.maxKktResidual, report.coefficientL2Norm), "training report")
    return report
}

/** Validation can only describe an immutable, host-verified training checkpoint. */
internal fun validateOutcomeValueCheckpoint(
    validationBindings: ResearchRunBindings,
    trainingBindings: ResearchRunBindings,
    checkpoint: LoadedOutcomeValueCheckpoint,
    expectedTraining: LearnedOutcomeValueTrainingBinding,
    trainExamples: Iterable<OutcomeValueExample>,
    validationExamples: Iterable<OutcomeValueExample>,
    trainReport: OutcomeValueTrainingReport,
    expectedValidatorSource: String,
): OutcomeValueValidationReport {
    require(validationBindings.protocol == LEARNED_OUTCOME_VALUE_VALIDATION_PROTOCOL)
    require(validationBindings.material["training-run"] == trainingBindings.identity)
    require(validationBindings.material["checkpoint"] == checkpoint.envelope.payloadSha256)
    require(validationBindings.material["validation-pair-split"] == expectedTraining.pairSplitIdentity)
    require(validationBindings.material["corpus"] == expectedTraining.corpusIdentity)
    require(validationBindings.material["model"] == LEARNED_OUTCOME_VALUE_MODEL_V1)
    require(validationBindings.material["target"] == LEARNED_OUTCOME_VALUE_TARGET_V1)
    require(validationBindings.material["gate-specification"] == outcomeValueIdentity(
        "outcome-value-validation-gate",
        trainingJson.encodeToString(outcomeValueValidationGateSpecification),
    ))
    require(validationBindings.material["validator-source"] == expectedValidatorSource)
    require(checkpoint.envelope.researchRunIdentity == trainingBindings.identity)
    require(checkpoint.evaluator.checkpointIdentity.training == expectedTraining)
    require(trainReport.trainingRunIdentity == trainingBindings.identity)
    require(trainReport.checkpointPayloadSha256 == checkpoint.envelope.payloadSha256)
    val train = canonicalOutcomeValueOrder(trainExamples)
    val validation = canonicalOutcomeValueOrder(validationExamples)
    val checkpointTrainAudit = auditOutcomeValueCheckpointTrain(train, checkpoint.evaluator)
    require(trainReport.rows == checkpointTrainAudit.rows && trainReport.games == checkpointTrainAudit.games)
    require(trainReport.trainTerminalPayoffMean == checkpointTrainAudit.terminalPayoffMean)
    require(trainReport.maxKktResidual >= 0.0 && trainReport.coefficientL2Norm >= 0.0)
    require(trainReport.maxKktResidual == checkpointTrainAudit.maxKktResidual)
    require(trainReport.coefficientL2Norm == checkpointTrainAudit.coefficientL2Norm)
    val baseline = equalGameConstantPairMse(validation, equalGameTerminalPayoffMean(train))
    val metrics = outcomeValueMetrics(validation, checkpoint.evaluator, checkpoint.evaluatorTrainUniverse())
    val failures = mutableListOf<String>()
    if (train.map(OutcomeValueExample::gameKey).intersect(validation.map(OutcomeValueExample::gameKey).toSet()).isNotEmpty()) failures += "train-validation game overlap"
    if (train.map(OutcomeValueExample::actualTerminalPayoff).distinct().size < outcomeValueValidationGateSpecification.minimumDistinctTerminalPayoffsPerPartition || validation.map(OutcomeValueExample::actualTerminalPayoff).distinct().size < outcomeValueValidationGateSpecification.minimumDistinctTerminalPayoffsPerPartition) failures += "train and validation each require at least ${outcomeValueValidationGateSpecification.minimumDistinctTerminalPayoffsPerPartition} terminal payoff values"
    if (outcomeValueValidationGateSpecification.requireRootAndOpponentActorFrames && listOf(train, validation).any { partition -> partition.none { it.actorRelation == OutcomeValueActorRelation.ROOT } || partition.none { it.actorRelation == OutcomeValueActorRelation.OPPONENT } }) failures += "root and opponent actor frames are required in train and validation"
    if (checkpointTrainAudit.maxKktResidual > outcomeValueValidationGateSpecification.maximumTrainingKktResidual) failures += "training KKT residual exceeds fixed threshold"
    if (checkpointTrainAudit.coefficientL2Norm <= outcomeValueValidationGateSpecification.minimumCoefficientL2Norm) failures += "coefficient L2 norm is effectively zero"
    if (metrics.equalGamePredictionStandardDeviation < outcomeValueValidationGateSpecification.minimumEqualGamePredictionStandardDeviation) failures += "validation equal-game prediction SD is below ${outcomeValueValidationGateSpecification.minimumEqualGamePredictionStandardDeviation}"
    if (metrics.pairClusteredMse > outcomeValueValidationGateSpecification.maximumValidationMseFactorOfTrainConstantBaseline * baseline) failures += "validation pair-clustered MSE does not beat frozen train-mean baseline"
    val reload = LearnedOutcomeValueEvaluator.load(checkpoint.evaluator.canonicalCheckpointBytes())
    val original = validation.map { checkpoint.evaluator.evaluate(it.features).toBits() }
    val reloaded = validation.map { reload.evaluate(it.features).toBits() }
    if (outcomeValueValidationGateSpecification.requireCanonicalReloadPredictionEquality && original != reloaded) failures += "canonical checkpoint reload changes validation predictions"
    return OutcomeValueValidationReport(
        validationRunIdentity = validationBindings.identity,
        trainingRunIdentity = trainingBindings.identity,
        immutableCheckpointPayloadSha256 = checkpoint.envelope.payloadSha256,
        validationPairSplitIdentity = expectedTraining.pairSplitIdentity,
        frozenTrainConstantBaselineMse = baseline,
        metrics = metrics,
        passed = failures.isEmpty(),
        failures = failures,
    )
}

/** Production validation owner: manifest membership/counts are checked before exactly its partitions are used. */
private fun validateLoadedOutcomeValueCorpus(
    validationBindings: ResearchRunBindings,
    trainingBindings: ResearchRunBindings,
    checkpoint: LoadedOutcomeValueCheckpoint,
    expectedTraining: LearnedOutcomeValueTrainingBinding,
    corpus: LoadedOutcomeValueCorpus,
    trainReport: OutcomeValueTrainingReport,
    expectedValidatorSource: String,
): OutcomeValueValidationReport {
    require(corpus.matchesTraining(expectedTraining))
    return validateOutcomeValueCheckpoint(
        validationBindings,
        trainingBindings,
        checkpoint,
        expectedTraining,
        corpus.examples(OutcomeStateCorpusSplit.TRAIN),
        corpus.examples(OutcomeStateCorpusSplit.VALIDATION),
        trainReport,
        expectedValidatorSource,
    )
}

internal fun learnedOutcomeValueValidationBindings(
    trainingRun: ResearchRunBindings,
    checkpoint: LoadedOutcomeValueCheckpoint,
    training: LearnedOutcomeValueTrainingBinding,
    validatorProvenance: ResearchRunProvenance,
): ResearchRunBindings = ResearchRunBindings(
    protocol = LEARNED_OUTCOME_VALUE_VALIDATION_PROTOCOL,
    material = mapOf(
        "training-run" to trainingRun.identity,
        "checkpoint" to checkpoint.envelope.payloadSha256,
        "corpus" to training.corpusIdentity,
        "validation-pair-split" to training.pairSplitIdentity,
        "model" to LEARNED_OUTCOME_VALUE_MODEL_V1,
        "target" to LEARNED_OUTCOME_VALUE_TARGET_V1,
        "gate-specification" to outcomeValueIdentity(
            "outcome-value-validation-gate",
            trainingJson.encodeToString(outcomeValueValidationGateSpecification),
        ),
        "validator-source" to committedOutcomeValueValidatorSource(validatorProvenance),
    ),
)

internal fun committedOutcomeValueValidatorSource(provenance: ResearchRunProvenance): String {
    provenance.requireReady()
    require(!provenance.outerDirty && !provenance.engineDirty) {
        "Learned-outcome validation evidence requires committed clean validator and Argentum source"
    }
    require(provenance.sourceProvenance.expectedArgentumRevision == OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT)
    return outcomeValueIdentity("validator-source", researchSha256(trainingJson.encodeToString(provenance.sourceProvenance)))
}

private fun LoadedOutcomeValueCheckpoint.evaluatorTrainUniverse(): Set<String> =
    checkpointPayload(evaluator).weights.keys

internal fun equalGameConstantPairMse(examples: Iterable<OutcomeValueExample>, constant: Double): Double {
    requireFinite(constant, "baseline constant")
    val rows = canonicalOutcomeValueOrder(examples)
    return rows.groupBy(OutcomeValueExample::gameKey).values.groupBy { it.first().pairIndex }.values.map { pairGames ->
        pairGames.map { game -> game.map { (it.actualTerminalPayoff - constant) * (it.actualTerminalPayoff - constant) }.average() }.average()
    }.average().also { requireFinite(it, "constant pair MSE") }
}

private fun persistOutcomeValueValidation(directory: Path, report: OutcomeValueValidationReport): Path {
    ResearchRunFiles.atomicWrite(ResearchRunFiles.resolveBelow(directory, LEARNED_OUTCOME_VALUE_VALIDATION_REPORT_FILE), trainingJson.encodeToString(report) + "\n")
    return ResearchRunArtifacts(directory, report.validationRunIdentity).also {
        it.register(LEARNED_OUTCOME_VALUE_VALIDATION_REPORT_FILE)
        it.finalize()
    }.let { ResearchRunFiles.resolveBelow(directory, ResearchRunArtifacts.MANIFEST_FILE) }
}

/** A completed validation stage is resumed only through its verified, bound artifact. */
private fun loadOutcomeValueValidationReport(
    directory: Path,
    expectedValidationRun: ResearchRunBindings,
    expectedTrainingRun: ResearchRunBindings,
    checkpoint: LoadedOutcomeValueCheckpoint,
): OutcomeValueValidationReport {
    ResearchRunArtifacts.loadAndVerify(directory, expectedValidationRun.identity)
    val report = trainingJson.decodeFromString<OutcomeValueValidationReport>(
        Files.readString(ResearchRunFiles.resolveBelow(directory, LEARNED_OUTCOME_VALUE_VALIDATION_REPORT_FILE))
    )
    require(report.validationRunIdentity == expectedValidationRun.identity)
    require(report.trainingRunIdentity == expectedTrainingRun.identity)
    require(report.immutableCheckpointPayloadSha256 == checkpoint.envelope.payloadSha256)
    require(report.validationPairSplitIdentity == checkpoint.evaluator.checkpointIdentity.training.pairSplitIdentity)
    return report
}

/** Production test owner: the TEST partition remains inaccessible until host promotion. */
private fun evaluateLoadedOutcomeValueTest(
    testBindings: ResearchRunBindings,
    promotion: PromotedOutcomeValueCheckpoint,
    corpus: LoadedOutcomeValueCorpus,
): OutcomeValueTestReport {
    corpus.requirePromotion(promotion)
    promotion.requireTestBinding(testBindings) // Authenticate bindings before opening TEST.
    return OutcomeValueTestReport(
        testRunIdentity = testBindings.identity,
        validationRunIdentity = promotion.validationRunIdentity,
        immutableCheckpointPayloadSha256 = promotion.trainingEnvelopePayloadSha256,
        metrics = outcomeValueMetrics(corpus.sealedTestExamples(promotion), promotion.evaluator(), promotion.trainingFeatureUniverse()),
    )
}

internal fun learnedOutcomeValueTestBindings(
    promotion: PromotedOutcomeValueCheckpoint,
): ResearchRunBindings = ResearchRunBindings(
    protocol = LEARNED_OUTCOME_VALUE_TEST_PROTOCOL,
    material = mapOf(
        "validation-run" to promotion.validationRunIdentity,
        "checkpoint" to promotion.trainingEnvelopePayloadSha256,
        "corpus" to promotion.corpusIdentity,
        "test-pair-split" to promotion.pairSplitIdentity,
    ),
)

private fun persistOutcomeValueTest(directory: Path, report: OutcomeValueTestReport): Path {
    ResearchRunFiles.atomicWrite(ResearchRunFiles.resolveBelow(directory, LEARNED_OUTCOME_VALUE_TEST_REPORT_FILE), trainingJson.encodeToString(report) + "\n")
    return ResearchRunArtifacts(directory, report.testRunIdentity).also {
        it.register(LEARNED_OUTCOME_VALUE_TEST_REPORT_FILE)
        it.finalize()
    }.let { ResearchRunFiles.resolveBelow(directory, ResearchRunArtifacts.MANIFEST_FILE) }
}

/** A completed descriptive TEST stage is resumed only through its verified artifact. */
private fun loadOutcomeValueTestReport(
    directory: Path,
    expectedTestRun: ResearchRunBindings,
    expectedValidation: OutcomeValueValidationReport,
    checkpoint: LoadedOutcomeValueCheckpoint,
): OutcomeValueTestReport {
    ResearchRunArtifacts.loadAndVerify(directory, expectedTestRun.identity)
    val report = trainingJson.decodeFromString<OutcomeValueTestReport>(
        Files.readString(ResearchRunFiles.resolveBelow(directory, LEARNED_OUTCOME_VALUE_TEST_REPORT_FILE))
    )
    require(report.testRunIdentity == expectedTestRun.identity)
    require(report.validationRunIdentity == expectedValidation.validationRunIdentity)
    require(report.immutableCheckpointPayloadSha256 == checkpoint.envelope.payloadSha256)
    return report
}
