package org.mtgallium.evaluation.searchteacher

import java.util.Random
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.serialization.Serializable
import org.mtgallium.research.run.researchSha256

private const val NONLINEAR_SCHEMA = "decision-local-phase-tanh-v1"
private const val CONTEXT_PREFIX = "root-context/"

/** These are pre-choice public state fields. The source-selected action family is not an input. */
internal fun decisionLocalContext(root: DecisionLocalRootEvidence): String =
    "${root.phase}/${if (root.turnNumber == 0) "opening" else "in-game"}"

/** Offline projection only: source vectors/schedules are unchanged and identify their parent input. */
internal fun conditionDecisionLocalFeatures(root: DecisionLocalRootEvidence): DecisionLocalRootEvidence {
    val context = decisionLocalContext(root)
    return root.copy(candidates = root.candidates.map { candidate ->
        require(candidate.featureMeans.keys.none { it.startsWith(CONTEXT_PREFIX) })
        candidate.copy(featureMeans = buildMap {
            putAll(candidate.featureMeans)
            candidate.featureMeans.forEach { (key, value) -> put("${CONTEXT_PREFIX}$context/$key", value) }
        })
    })
}

/** The projection belongs to the model artifact and scorer, not an optional caller convention. */
@Serializable
internal data class DecisionLocalPhaseModel(
    val schema: String = "decision-local-phase-linear-v1",
    val projectedRidge: DecisionLocalModelCheckpoint,
) {
    init { require(schema == "decision-local-phase-linear-v1") }
    val modelId: String get() = "decision-local-phase-linear-v1-sha256:" +
        researchSha256(evidenceJson.encodeToString(serializer(), this))
    fun scores(root: DecisionLocalRootEvidence): List<Double> =
        conditionDecisionLocalFeatures(root).candidates.map(projectedRidge::score)
}

internal fun fitDecisionLocalPhaseModel(roots: List<DecisionLocalRootEvidence>): DecisionLocalPhaseModel {
    require(roots.isNotEmpty() && roots.all { it.split == DecisionLocalSplit.TRAIN })
    require(roots.map { it.pairIndex }.distinct().size == roots.size)
    return DecisionLocalPhaseModel(projectedRidge = fitLearnabilityModel(roots.map(::conditionDecisionLocalFeatures)))
}

@Serializable
internal data class DecisionLocalNonlinearConfig(
    val hiddenUnits: Int = 16,
    val epochs: Int = 400,
    val learningRate: Double = 0.01,
    /** L2 on hidden/output weights, not biases, in the root-equal mean-squared objective. */
    val regularization: Double = 0.001,
    val seed: Long = 2026090601L,
) {
    init {
        require(hiddenUnits > 0 && epochs > 0)
        require(learningRate.isFinite() && learningRate > 0)
        require(regularization.isFinite() && regularization >= 0)
    }
}

@Serializable
internal data class DecisionLocalNonlinearModel(
    val schema: String = NONLINEAR_SCHEMA,
    val config: DecisionLocalNonlinearConfig,
    val featureKeys: List<String>,
    /** TRAIN-only root-equal RMS, floored at one; absent/unseen coordinates contribute zero. */
    val scales: List<Double>,
    val hiddenWeights: List<Double>,
    val hiddenBiases: List<Double>,
    val outputWeights: List<Double>,
    val outputBias: Double,
    val trainingRootIds: List<String>,
    val trainingDataSha256: String,
    val initialObjective: Double,
    val finalObjective: Double,
) {
    init {
        require(schema == NONLINEAR_SCHEMA)
        require(featureKeys == featureKeys.distinct().sorted())
        require(scales.size == featureKeys.size && scales.all { it.isFinite() && it >= 1.0 })
        require(hiddenWeights.size == featureKeys.size * config.hiddenUnits)
        require(hiddenBiases.size == config.hiddenUnits && outputWeights.size == config.hiddenUnits)
        require((hiddenWeights + hiddenBiases + outputWeights + outputBias).all(Double::isFinite))
        require(trainingRootIds.isNotEmpty() && trainingRootIds == trainingRootIds.distinct().sorted())
        require(trainingDataSha256.matches(Regex("[0-9a-f]{64}")))
        require(initialObjective.isFinite() && finalObjective.isFinite())
    }

    val modelId: String get() = "decision-local-phase-tanh-v1-sha256:" +
        researchSha256(evidenceJson.encodeToString(serializer(), this))

    fun scores(root: DecisionLocalRootEvidence): List<Double> {
        val index = featureKeys.withIndex().associate { it.value to it.index }
        return root.candidates.map { candidate ->
            val input = nonlinearInput(root, candidate).entries.mapNotNull { (key, value) ->
                index[key]?.let { it to value / scales[it] }
            }
            var score = outputBias
            for (h in 0 until config.hiddenUnits) {
                var activation = hiddenBiases[h]
                for ((i, value) in input) activation += hiddenWeights[h * featureKeys.size + i] * value
                score += outputWeights[h] * tanh(activation)
            }
            // An entirely terminal feature schedule has an exact offset, never a learned residual.
            candidate.terminalFeatureOffset + nonterminalMass(candidate) * score
        }.also { require(it.all(Double::isFinite)) }
    }
}

private fun nonterminalMass(candidate: DecisionLocalCandidateEvidence): Double {
    require(candidate.featureWorlds > 0 && candidate.nonterminalFeatureWorlds in 0..candidate.featureWorlds)
    require(candidate.terminalFeatureOffset.isFinite())
    return candidate.nonterminalFeatureWorlds.toDouble() / candidate.featureWorlds
}

private fun nonlinearInput(root: DecisionLocalRootEvidence, candidate: DecisionLocalCandidateEvidence): Map<String, Double> {
    require(candidate.featureMeans.keys.none { it.startsWith(CONTEXT_PREFIX) })
    return candidate.featureMeans + ("${CONTEXT_PREFIX}${decisionLocalContext(root)}" to 1.0)
}

private data class NonlinearRow(val indices: IntArray, val values: DoubleArray, val mass: Double, val target: Double)

/** A bounded offline action-outcome experiment, not a state-value or rollout-policy checkpoint. */
internal fun fitDecisionLocalNonlinearModel(
    roots: List<DecisionLocalRootEvidence>,
    config: DecisionLocalNonlinearConfig = DecisionLocalNonlinearConfig(),
): DecisionLocalNonlinearModel {
    require(roots.isNotEmpty() && roots.all { it.split == DecisionLocalSplit.TRAIN })
    require(roots.map { it.rootId }.distinct().size == roots.size)
    require(roots.map { it.pairIndex }.distinct().size == roots.size) { "Use one retained root per whole-game group" }
    val training = roots.sortedBy { it.rootId }
    training.forEach { root ->
        require(root.primaryReplicates == 32 && root.independentReplicates == 0 && root.failures.isEmpty())
        root.candidates.forEach { candidate ->
            require(candidate.primaryTerminalPayoffs.size == 32 && candidate.independentTerminalPayoffs.isEmpty())
            require(candidate.primaryTerminalPayoffs.all { it in setOf(-1.0, 0.0, 1.0) })
            nonterminalMass(candidate)
        }
    }
    val inputMaps = training.map { root -> root.candidates.map { nonlinearInput(root, it) } }
    val keys = inputMaps.flatten().flatMap { it.keys }.distinct().sorted()
    val index = keys.withIndex().associate { it.value to it.index }
    val scales = DoubleArray(keys.size)
    inputMaps.forEach { siblings -> siblings.forEach { row -> row.forEach { (key, value) ->
        scales[index.getValue(key)] += value * value / siblings.size / training.size
    } } }
    scales.indices.forEach { scales[it] = max(1.0, sqrt(scales[it])) }
    require(scales.all(Double::isFinite))
    val rows = training.zip(inputMaps) { root, siblings -> root.candidates.zip(siblings) { candidate, input ->
        val entries = input.entries.filter { it.value != 0.0 }.sortedBy { index.getValue(it.key) }
        NonlinearRow(entries.map { index.getValue(it.key) }.toIntArray(),
            entries.map { it.value / scales[index.getValue(it.key)] }.toDoubleArray(),
            nonterminalMass(candidate), candidate.primaryMean - candidate.terminalFeatureOffset)
    } }
    val dimension = keys.size
    val hidden = config.hiddenUnits
    val outputStart = hidden * dimension + hidden
    val biasIndex = outputStart + hidden
    val parameters = DoubleArray(biasIndex + 1)
    val random = Random(config.seed)
    val meanInputNorm = rows.sumOf { siblings -> siblings.sumOf { it.values.sumOf { v -> v * v } } / siblings.size } / rows.size
    for (i in 0 until hidden * dimension) parameters[i] = random.nextGaussian() / sqrt(max(1.0, meanInputNorm))
    for (i in outputStart until biasIndex) parameters[i] = random.nextGaussian() * 0.1 / sqrt(hidden.toDouble())

    fun objective(gradient: DoubleArray?): Double {
        var loss = 0.0
        for (siblings in rows) {
            val activations = siblings.map { row -> DoubleArray(hidden) { h ->
                var value = parameters[hidden * dimension + h]
                for (j in row.indices.indices) value += parameters[h * dimension + row.indices[j]] * row.values[j]
                tanh(value)
            } }
            val errors = DoubleArray(siblings.size) { i ->
                val raw = parameters[biasIndex] + (0 until hidden).sumOf { h -> parameters[outputStart + h] * activations[i][h] }
                siblings[i].mass * raw - siblings[i].target
            }
            val meanError = errors.average()
            for (i in errors.indices) errors[i] -= meanError
            loss += errors.sumOf { it * it } / siblings.size / rows.size
            if (gradient != null) {
                // Recenter again for floating-point residual: d(Pe)/de = P.
                val residual = errors.average()
                for (i in siblings.indices) {
                    val row = siblings[i]
                    val delta = 2.0 * (errors[i] - residual) * row.mass / siblings.size / rows.size
                    gradient[biasIndex] += delta
                    for (h in 0 until hidden) {
                        val a = activations[i][h]
                        gradient[outputStart + h] += delta * a
                        val hiddenDelta = delta * parameters[outputStart + h] * (1.0 - a * a)
                        gradient[hidden * dimension + h] += hiddenDelta
                        for (j in row.indices.indices) gradient[h * dimension + row.indices[j]] += hiddenDelta * row.values[j]
                    }
                }
            }
        }
        for (i in parameters.indices) {
            if (i in hidden * dimension until outputStart || i == biasIndex) continue
            loss += config.regularization * parameters[i] * parameters[i]
            if (gradient != null) gradient[i] += 2.0 * config.regularization * parameters[i]
        }
        require(loss.isFinite() && (gradient == null || gradient.all(Double::isFinite)))
        return loss
    }
    val initial = objective(null)
    val firstMoment = DoubleArray(parameters.size)
    val secondMoment = DoubleArray(parameters.size)
    repeat(config.epochs) { epoch ->
        val gradient = DoubleArray(parameters.size)
        objective(gradient)
        val firstCorrection = 1.0 - 0.9.pow(epoch + 1)
        val secondCorrection = 1.0 - 0.999.pow(epoch + 1)
        for (i in parameters.indices) {
            firstMoment[i] = 0.9 * firstMoment[i] + 0.1 * gradient[i]
            secondMoment[i] = 0.999 * secondMoment[i] + 0.001 * gradient[i] * gradient[i]
            parameters[i] -= config.learningRate * (firstMoment[i] / firstCorrection) /
                (sqrt(secondMoment[i] / secondCorrection) + 1e-8)
        }
    }
    return DecisionLocalNonlinearModel(config = config, featureKeys = keys, scales = scales.toList(),
        hiddenWeights = parameters.take(hidden * dimension),
        hiddenBiases = parameters.slice(hidden * dimension until outputStart),
        outputWeights = parameters.slice(outputStart until biasIndex), outputBias = parameters[biasIndex],
        trainingRootIds = training.map { it.rootId },
        trainingDataSha256 = researchSha256(training.joinToString("\n") { evidenceJson.encodeToString(DecisionLocalRootEvidence.serializer(), it) }),
        initialObjective = initial, finalObjective = objective(null))
}
