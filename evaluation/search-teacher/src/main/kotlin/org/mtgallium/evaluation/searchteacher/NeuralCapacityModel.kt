package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal const val NEURAL_BC_INTERACTION_MODEL_SCHEMA = 1

/**
 * A deliberately small nonlinear extension of the issue-0022 scorer.
 *
 * State and candidate retain separate 32-dimensional projections. A 64-dimensional hidden layer
 * receives state, candidate, and their elementwise product before producing one candidate score.
 * This adds nonlinear state/candidate interaction while increasing parameters only modestly.
 */
@Serializable
internal data class NeuralBcInteractionModelConfig(
    val schemaVersion: Int = NEURAL_BC_INTERACTION_MODEL_SCHEMA,
    val featureSchema: String = NEURAL_BC_FEATURE_SCHEMA,
    val stateDimension: Int = 1_024,
    val candidateDimension: Int = 512,
    val projectionDimension: Int = 32,
    val interactionDimension: Int = 64,
    val parameterCount: Int =
        projectionDimension * stateDimension + projectionDimension +
            projectionDimension * candidateDimension + projectionDimension +
            interactionDimension * projectionDimension * 3 + interactionDimension +
            interactionDimension + 1,
) {
    init {
        require(schemaVersion == NEURAL_BC_INTERACTION_MODEL_SCHEMA)
        require(stateDimension > 0 && candidateDimension > 0)
        require(projectionDimension > 0 && interactionDimension > 0)
    }
}

@Serializable
internal data class NeuralBcInteractionModelArtifact(
    val schemaVersion: Int = NEURAL_BC_INTERACTION_MODEL_SCHEMA,
    val protocol: String = "candidate-conditioned-interaction-mlp-v1",
    val config: NeuralBcInteractionModelConfig,
    val trainingSeed: Long,
    val bestEpoch: Int,
    val stateWeights: DoubleArray,
    val stateBias: DoubleArray,
    val candidateWeights: DoubleArray,
    val candidateBias: DoubleArray,
    val interactionWeights: DoubleArray,
    val interactionBias: DoubleArray,
    val outputWeights: DoubleArray,
    val outputBias: DoubleArray,
)

internal class CandidateConditionedInteractionPolicy private constructor(
    val artifact: NeuralBcInteractionModelArtifact,
) : NeuralBcScoringPolicy {
    private val config = artifact.config

    override fun scores(decision: EncodedBcDecision): DoubleArray {
        val state = project(
            decision.state,
            artifact.stateWeights,
            artifact.stateBias,
            config.stateDimension,
            config.projectionDimension,
        )
        val scale = 1.0 / sqrt(config.interactionDimension.toDouble())
        return DoubleArray(decision.candidateCount) { candidateIndex ->
            val candidate = project(
                decision.candidates[candidateIndex],
                artifact.candidateWeights,
                artifact.candidateBias,
                config.candidateDimension,
                config.projectionDimension,
            )
            val joint = jointInput(state, candidate)
            val hidden = denseTanh(
                joint,
                artifact.interactionWeights,
                artifact.interactionBias,
                config.projectionDimension * 3,
                config.interactionDimension,
            )
            dot(artifact.outputWeights, hidden) * scale + artifact.outputBias.single()
        }
    }

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        writeTextAtomically(path, evidenceJson.encodeToString(artifact) + "\n")
    }

    companion object {
        fun initialize(
            config: NeuralBcInteractionModelConfig,
            seed: Long,
        ): CandidateConditionedInteractionPolicy {
            val random = Random(seed)
            fun weights(rows: Int, columns: Int): DoubleArray {
                val scale = sqrt(2.0 / (rows + columns).toDouble())
                return DoubleArray(rows * columns) { gaussian(random) * scale }
            }
            return CandidateConditionedInteractionPolicy(
                NeuralBcInteractionModelArtifact(
                    config = config,
                    trainingSeed = seed,
                    bestEpoch = 0,
                    stateWeights = weights(config.projectionDimension, config.stateDimension),
                    stateBias = DoubleArray(config.projectionDimension),
                    candidateWeights = weights(config.projectionDimension, config.candidateDimension),
                    candidateBias = DoubleArray(config.projectionDimension),
                    interactionWeights = weights(
                        config.interactionDimension,
                        config.projectionDimension * 3,
                    ),
                    interactionBias = DoubleArray(config.interactionDimension),
                    outputWeights = weights(1, config.interactionDimension),
                    outputBias = DoubleArray(1),
                )
            )
        }

        fun fromArtifact(artifact: NeuralBcInteractionModelArtifact): CandidateConditionedInteractionPolicy =
            CandidateConditionedInteractionPolicy(artifact)

        fun load(path: Path): CandidateConditionedInteractionPolicy = fromArtifact(
            evidenceJson.decodeFromString(Files.readString(path))
        )

        private fun gaussian(random: Random): Double {
            val first = random.nextDouble().coerceAtLeast(1e-12)
            return sqrt(-2.0 * ln(first)) * cos(2.0 * PI * random.nextDouble())
        }
    }
}

internal data class TrainedInteractionBcModel(
    val policy: CandidateConditionedInteractionPolicy,
    val bestEpoch: Int,
    val bestValidationLoss: Double,
    val selectedCheckpointTrainingLoss: Double,
    val maximumTrainingAccuracy: Double,
)

internal class NeuralBcInteractionTrainer(
    private val modelConfig: NeuralBcInteractionModelConfig,
    private val trainingConfig: NeuralBcTrainingConfig,
) {
    fun train(
        train: List<EncodedBcDecision>,
        validation: List<EncodedBcDecision>,
        seed: Long,
    ): TrainedInteractionBcModel {
        val effectiveTrain = train.filter { it.candidateCount >= PRIMARY_MIN_CANDIDATES }
        val effectiveValidation = validation.filter { it.candidateCount >= PRIMARY_MIN_CANDIDATES }
        require(effectiveTrain.isNotEmpty() && effectiveValidation.isNotEmpty())
        var policy = CandidateConditionedInteractionPolicy.initialize(modelConfig, seed)
        val adam = InteractionAdam(policy.artifact, trainingConfig)
        var bestArtifact = copyInteractionArtifact(policy.artifact)
        var bestEpoch = 0
        var bestValidationLoss = Double.POSITIVE_INFINITY
        var maximumTrainingAccuracy = 0.0
        for (epoch in 1..trainingConfig.maximumEpochs) {
            effectiveTrain.shuffled(Random(seed xor epoch.toLong())).forEach(adam::step)
            maximumTrainingAccuracy = maxOf(
                maximumTrainingAccuracy,
                neuralBcAccuracy(policy, effectiveTrain),
            )
            val validationLoss = interactionMeanLoss(policy, effectiveValidation)
            if (validationLoss < bestValidationLoss - 1e-7) {
                bestValidationLoss = validationLoss
                bestEpoch = epoch
                bestArtifact = copyInteractionArtifact(policy.artifact).copy(bestEpoch = epoch)
            }
        }
        policy = CandidateConditionedInteractionPolicy.fromArtifact(bestArtifact)
        return TrainedInteractionBcModel(
            policy = policy,
            bestEpoch = bestEpoch,
            bestValidationLoss = bestValidationLoss,
            selectedCheckpointTrainingLoss = interactionMeanLoss(policy, effectiveTrain),
            maximumTrainingAccuracy = maximumTrainingAccuracy,
        )
    }
}

private class InteractionAdam(
    private val artifact: NeuralBcInteractionModelArtifact,
    private val config: NeuralBcTrainingConfig,
) {
    private val stateAdam = AdamArray(artifact.stateWeights)
    private val stateBiasAdam = AdamArray(artifact.stateBias)
    private val candidateAdam = AdamArray(artifact.candidateWeights)
    private val candidateBiasAdam = AdamArray(artifact.candidateBias)
    private val interactionAdam = AdamArray(artifact.interactionWeights)
    private val interactionBiasAdam = AdamArray(artifact.interactionBias)
    private val outputAdam = AdamArray(artifact.outputWeights)
    private val outputBiasAdam = AdamArray(artifact.outputBias)
    private val interactionGradient = DoubleArray(artifact.interactionWeights.size)
    private val interactionBiasGradient = DoubleArray(artifact.interactionBias.size)
    private val outputGradient = DoubleArray(artifact.outputWeights.size)
    private var time = 0

    fun step(decision: EncodedBcDecision) {
        time++
        interactionGradient.fill(0.0)
        interactionBiasGradient.fill(0.0)
        outputGradient.fill(0.0)

        val model = artifact.config
        val projection = model.projectionDimension
        val interaction = model.interactionDimension
        val jointSize = projection * 3
        val state = project(
            decision.state,
            artifact.stateWeights,
            artifact.stateBias,
            model.stateDimension,
            projection,
        )
        val candidates = decision.candidates.map { vector ->
            project(
                vector,
                artifact.candidateWeights,
                artifact.candidateBias,
                model.candidateDimension,
                projection,
            )
        }
        val joints = candidates.map { candidate -> jointInput(state, candidate) }
        val hidden = joints.map { joint ->
            denseTanh(
                joint,
                artifact.interactionWeights,
                artifact.interactionBias,
                jointSize,
                interaction,
            )
        }
        val scale = 1.0 / sqrt(interaction.toDouble())
        val scores = DoubleArray(hidden.size) { index ->
            dot(artifact.outputWeights, hidden[index]) * scale + artifact.outputBias.single()
        }
        val probabilities = interactionSoftmax(scores)
        val stateProjectionGradient = DoubleArray(projection)
        val candidateBiasGradient = DoubleArray(projection)
        val candidateWeightGradients = linkedMapOf<Int, Double>()
        var outputBiasGradient = 0.0

        candidates.indices.forEach { candidateIndex ->
            val scoreGradient = probabilities[candidateIndex] -
                if (candidateIndex == decision.labelIndex) 1.0 else 0.0
            outputBiasGradient += scoreGradient
            val hiddenPreGradient = DoubleArray(interaction)
            (0 until interaction).forEach { h ->
                outputGradient[h] += scoreGradient * hidden[candidateIndex][h] * scale
                hiddenPreGradient[h] = scoreGradient * artifact.outputWeights[h] * scale *
                    (1.0 - hidden[candidateIndex][h] * hidden[candidateIndex][h])
                interactionBiasGradient[h] += hiddenPreGradient[h]
                val offset = h * jointSize
                (0 until jointSize).forEach { j ->
                    interactionGradient[offset + j] += hiddenPreGradient[h] * joints[candidateIndex][j]
                }
            }
            val jointGradient = DoubleArray(jointSize)
            (0 until interaction).forEach { h ->
                val offset = h * jointSize
                (0 until jointSize).forEach { j ->
                    jointGradient[j] += artifact.interactionWeights[offset + j] * hiddenPreGradient[h]
                }
            }
            val candidateProjectionGradient = DoubleArray(projection)
            (0 until projection).forEach { h ->
                stateProjectionGradient[h] += jointGradient[h] +
                    jointGradient[projection * 2 + h] * candidates[candidateIndex][h]
                candidateProjectionGradient[h] = jointGradient[projection + h] +
                    jointGradient[projection * 2 + h] * state[h]
            }
            (0 until projection).forEach { h ->
                val preGradient = candidateProjectionGradient[h] *
                    (1.0 - candidates[candidateIndex][h] * candidates[candidateIndex][h])
                candidateBiasGradient[h] += preGradient
                val offset = h * model.candidateDimension
                decision.candidates[candidateIndex].indices.indices.forEach { position ->
                    val weightIndex = offset + decision.candidates[candidateIndex].indices[position]
                    val gradient = preGradient * decision.candidates[candidateIndex].values[position]
                    candidateWeightGradients[weightIndex] =
                        candidateWeightGradients.getOrDefault(weightIndex, 0.0) + gradient
                }
            }
        }

        (0 until projection).forEach { h ->
            val preGradient = stateProjectionGradient[h] * (1.0 - state[h] * state[h])
            val offset = h * model.stateDimension
            decision.state.indices.indices.forEach { position ->
                val weightIndex = offset + decision.state.indices[position]
                stateAdam.update(
                    weightIndex,
                    preGradient * decision.state.values[position],
                    time,
                    config,
                )
            }
            stateBiasAdam.update(h, preGradient, time, config)
            candidateBiasAdam.update(h, candidateBiasGradient[h], time, config)
        }
        candidateWeightGradients.forEach { (index, gradient) ->
            candidateAdam.update(index, gradient, time, config)
        }
        interactionGradient.indices.forEach { index ->
            interactionAdam.update(index, interactionGradient[index], time, config)
        }
        interactionBiasGradient.indices.forEach { index ->
            interactionBiasAdam.update(index, interactionBiasGradient[index], time, config)
        }
        outputGradient.indices.forEach { index ->
            outputAdam.update(index, outputGradient[index], time, config)
        }
        outputBiasAdam.update(0, outputBiasGradient, time, config)
    }
}

private class AdamArray(private val parameters: DoubleArray) {
    private val firstMoment = DoubleArray(parameters.size)
    private val secondMoment = DoubleArray(parameters.size)

    fun update(index: Int, gradient: Double, time: Int, config: NeuralBcTrainingConfig) {
        val beta1 = config.adamBeta1
        val beta2 = config.adamBeta2
        firstMoment[index] = beta1 * firstMoment[index] + (1.0 - beta1) * gradient
        secondMoment[index] = beta2 * secondMoment[index] + (1.0 - beta2) * gradient * gradient
        val correctedFirst = firstMoment[index] / (1.0 - beta1.pow(time))
        val correctedSecond = secondMoment[index] / (1.0 - beta2.pow(time))
        parameters[index] -= config.learningRate * correctedFirst /
            (sqrt(correctedSecond) + config.adamEpsilon)
    }
}

private fun project(
    vector: SparseFeatureVector,
    weights: DoubleArray,
    bias: DoubleArray,
    inputDimension: Int,
    outputDimension: Int,
): DoubleArray = DoubleArray(outputDimension) { output ->
    var total = bias[output]
    val offset = output * inputDimension
    vector.indices.indices.forEach { position ->
        total += weights[offset + vector.indices[position]] * vector.values[position]
    }
    tanh(total)
}

private fun jointInput(state: DoubleArray, candidate: DoubleArray): DoubleArray {
    require(state.size == candidate.size)
    return DoubleArray(state.size * 3) { index ->
        when {
            index < state.size -> state[index]
            index < state.size * 2 -> candidate[index - state.size]
            else -> state[index - state.size * 2] * candidate[index - state.size * 2]
        }
    }
}

private fun denseTanh(
    input: DoubleArray,
    weights: DoubleArray,
    bias: DoubleArray,
    inputDimension: Int,
    outputDimension: Int,
): DoubleArray = DoubleArray(outputDimension) { output ->
    var total = bias[output]
    val offset = output * inputDimension
    (0 until inputDimension).forEach { index -> total += weights[offset + index] * input[index] }
    tanh(total)
}

private fun dot(left: DoubleArray, right: DoubleArray): Double {
    var result = 0.0
    left.indices.forEach { result += left[it] * right[it] }
    return result
}

private fun interactionSoftmax(scores: DoubleArray): DoubleArray {
    val maximum = scores.maxOrNull() ?: error("Empty candidate set")
    val exponentials = DoubleArray(scores.size) { exp(scores[it] - maximum) }
    val total = exponentials.sum()
    return DoubleArray(scores.size) { exponentials[it] / total }
}

private fun interactionCrossEntropy(scores: DoubleArray, label: Int): Double =
    -ln(interactionSoftmax(scores)[label].coerceAtLeast(1e-15))

private fun interactionMeanLoss(
    policy: NeuralBcScoringPolicy,
    examples: List<EncodedBcDecision>,
): Double = examples.sumOf { interactionCrossEntropy(policy.scores(it), it.labelIndex) } / examples.size

internal fun neuralBcAccuracy(
    policy: NeuralBcScoringPolicy,
    examples: List<EncodedBcDecision>,
): Double = if (examples.isEmpty()) Double.NaN else {
    examples.count { policy.selectIndex(it) == it.labelIndex }.toDouble() / examples.size
}

private fun copyInteractionArtifact(
    artifact: NeuralBcInteractionModelArtifact,
): NeuralBcInteractionModelArtifact = artifact.copy(
    stateWeights = artifact.stateWeights.copyOf(),
    stateBias = artifact.stateBias.copyOf(),
    candidateWeights = artifact.candidateWeights.copyOf(),
    candidateBias = artifact.candidateBias.copyOf(),
    interactionWeights = artifact.interactionWeights.copyOf(),
    interactionBias = artifact.interactionBias.copyOf(),
    outputWeights = artifact.outputWeights.copyOf(),
    outputBias = artifact.outputBias.copyOf(),
)
