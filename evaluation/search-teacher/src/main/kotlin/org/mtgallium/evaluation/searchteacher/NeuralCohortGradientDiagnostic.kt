package org.mtgallium.evaluation.searchteacher

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.serialization.Serializable

@Serializable
internal data class NeuralCohortGradientGroupGeometry(
    val parameterGroup: String,
    val actualCombinedObjectiveAnchorContributionNorm: Double,
    val actualCombinedObjectiveAddedContributionNorm: Double,
    val actualCombinedObjectiveGradientNorm: Double,
    val sizeNormalizedAnchorGradientNorm: Double,
    val sizeNormalizedAddedGradientNorm: Double,
    val sizeNormalizedCosine: Double?,
)

@Serializable
internal data class NeuralCohortGradientGeometry(
    val evaluatedAt: String,
    val anchorDecisions: Int,
    val addedDecisions: Int,
    val combinedDecisions: Int,
    val objective: String,
    val groups: List<NeuralCohortGradientGroupGeometry>,
)

@Serializable
internal data class NeuralAddedDecisionGradientConcentration(
    val decision: NeuralDecisionReference,
    val teacherMargin: Double,
    val crossEntropy: Double,
    val gradientNorm: Double,
    val cosineWithAnchorOpposingDirection: Double?,
)

internal data class DenseNeuralBcGradient(
    val stateWeights: DoubleArray,
    val stateBias: DoubleArray,
    val candidateWeights: DoubleArray,
    val candidateBias: DoubleArray,
    val globalQuery: DoubleArray,
) {
    fun scaled(scale: Double): DenseNeuralBcGradient = DenseNeuralBcGradient(
        stateWeights = DoubleArray(stateWeights.size) { stateWeights[it] * scale },
        stateBias = DoubleArray(stateBias.size) { stateBias[it] * scale },
        candidateWeights = DoubleArray(candidateWeights.size) { candidateWeights[it] * scale },
        candidateBias = DoubleArray(candidateBias.size) { candidateBias[it] * scale },
        globalQuery = DoubleArray(globalQuery.size) { globalQuery[it] * scale },
    )

    fun plus(other: DenseNeuralBcGradient): DenseNeuralBcGradient = DenseNeuralBcGradient(
        stateWeights = add(stateWeights, other.stateWeights),
        stateBias = add(stateBias, other.stateBias),
        candidateWeights = add(candidateWeights, other.candidateWeights),
        candidateBias = add(candidateBias, other.candidateBias),
        globalQuery = add(globalQuery, other.globalQuery),
    )

    private fun add(left: DoubleArray, right: DoubleArray): DoubleArray {
        require(left.size == right.size)
        return DoubleArray(left.size) { left[it] + right[it] }
    }
}

/** Dense analytical gradient of the exact mean candidate cross-entropy used by the learner. */
internal fun neuralBcObjectiveGradient(
    artifact: NeuralBcModelArtifact,
    decisions: List<EncodedBcDecision>,
): DenseNeuralBcGradient {
    require(decisions.isNotEmpty())
    val gradient = zeroGradient(artifact)
    decisions.forEach { decision -> accumulateDecisionGradient(artifact, decision, gradient) }
    return gradient.scaled(1.0 / decisions.size)
}

/** Analytical gradient of score[winner] - score[loser] at the exact supplied model state. */
internal fun neuralBcScoreDifferenceGradient(
    artifact: NeuralBcModelArtifact,
    decision: EncodedBcDecision,
    winnerCandidateIndex: Int,
    loserCandidateIndex: Int,
): DenseNeuralBcGradient {
    require(winnerCandidateIndex in decision.candidates.indices)
    require(loserCandidateIndex in decision.candidates.indices)
    require(winnerCandidateIndex != loserCandidateIndex)
    val scoreGradients = DoubleArray(decision.candidateCount)
    scoreGradients[winnerCandidateIndex] = 1.0
    scoreGradients[loserCandidateIndex] = -1.0
    return zeroGradient(artifact).also { gradient ->
        accumulateScoreGradient(artifact, decision, scoreGradients, gradient)
    }
}

internal fun neuralCohortGradientGeometry(
    artifact: NeuralBcModelArtifact,
    anchor: List<EncodedBcDecision>,
    added: List<EncodedBcDecision>,
    evaluatedAt: String,
): NeuralCohortGradientGeometry {
    require(anchor.isNotEmpty() && added.isNotEmpty())
    val anchorMean = neuralBcObjectiveGradient(artifact, anchor)
    val addedMean = neuralBcObjectiveGradient(artifact, added)
    val combinedSize = anchor.size + added.size
    val anchorActual = anchorMean.scaled(anchor.size.toDouble() / combinedSize)
    val addedActual = addedMean.scaled(added.size.toDouble() / combinedSize)
    val combined = anchorActual.plus(addedActual)
    val groups = listOf("STATE_AND_QUERY", "CANDIDATE_PROJECTION", "ALL_PARAMETERS").map { group ->
        NeuralCohortGradientGroupGeometry(
            parameterGroup = group,
            actualCombinedObjectiveAnchorContributionNorm = gradientNorm(anchorActual, group),
            actualCombinedObjectiveAddedContributionNorm = gradientNorm(addedActual, group),
            actualCombinedObjectiveGradientNorm = gradientNorm(combined, group),
            sizeNormalizedAnchorGradientNorm = gradientNorm(anchorMean, group),
            sizeNormalizedAddedGradientNorm = gradientNorm(addedMean, group),
            sizeNormalizedCosine = gradientCosine(anchorMean, addedMean, group),
        )
    }
    return NeuralCohortGradientGeometry(
        evaluatedAt = evaluatedAt,
        anchorDecisions = anchor.size,
        addedDecisions = added.size,
        combinedDecisions = combinedSize,
        objective =
            "mean per-decision softmax cross-entropy; actual combined contribution weights are " +
                "${anchor.size}/$combinedSize and ${added.size}/$combinedSize",
        groups = groups,
    )
}

internal fun addedDecisionGradientConcentration(
    artifact: NeuralBcModelArtifact,
    anchor: List<EncodedBcDecision>,
    added: List<EncodedBcDecision>,
    limit: Int = 10,
): List<NeuralAddedDecisionGradientConcentration> {
    val anchorMean = neuralBcObjectiveGradient(artifact, anchor)
    val policy = CandidateConditionedNeuralPolicy.fromArtifact(artifact)
    return added.map { decision ->
        val local = neuralBcObjectiveGradient(artifact, listOf(decision))
        val fit = neuralMemorizationDecisionFit(policy, decision)
        NeuralAddedDecisionGradientConcentration(
            decision = decision.neuralDecisionReference(),
            teacherMargin = fit.teacherMargin,
            crossEntropy = fit.meanCrossEntropyContribution,
            gradientNorm = gradientNorm(local, "ALL_PARAMETERS"),
            cosineWithAnchorOpposingDirection = gradientCosine(anchorMean.scaled(-1.0), local, "ALL_PARAMETERS"),
        )
    }.sortedWith(
        compareByDescending<NeuralAddedDecisionGradientConcentration> {
            it.cosineWithAnchorOpposingDirection ?: Double.NEGATIVE_INFINITY
        }.thenByDescending(NeuralAddedDecisionGradientConcentration::gradientNorm)
    ).take(limit)
}

private fun zeroGradient(artifact: NeuralBcModelArtifact): DenseNeuralBcGradient = DenseNeuralBcGradient(
    stateWeights = DoubleArray(artifact.stateWeights.size),
    stateBias = DoubleArray(artifact.stateBias.size),
    candidateWeights = DoubleArray(artifact.candidateWeights.size),
    candidateBias = DoubleArray(artifact.candidateBias.size),
    globalQuery = DoubleArray(artifact.globalQuery.size),
)

private fun accumulateDecisionGradient(
    artifact: NeuralBcModelArtifact,
    decision: EncodedBcDecision,
    gradient: DenseNeuralBcGradient,
) {
    val policy = CandidateConditionedNeuralPolicy.fromArtifact(artifact)
    val probabilities = cohortSoftmax(policy.scores(decision))
    val scoreGradients = DoubleArray(decision.candidateCount) { candidateIndex ->
        probabilities[candidateIndex] - if (candidateIndex == decision.labelIndex) 1.0 else 0.0
    }
    accumulateScoreGradient(artifact, decision, scoreGradients, gradient)
}

private fun accumulateScoreGradient(
    artifact: NeuralBcModelArtifact,
    decision: EncodedBcDecision,
    scoreGradients: DoubleArray,
    gradient: DenseNeuralBcGradient,
) {
    require(scoreGradients.size == decision.candidateCount)
    val config = artifact.config
    val hidden = config.hiddenDimension
    val statePre = projectPreActivation(
        decision.state,
        artifact.stateWeights,
        artifact.stateBias,
        config.stateDimension,
        hidden,
    )
    val state = DoubleArray(hidden) { tanh(statePre[it]) }
    val query = DoubleArray(hidden) { state[it] + artifact.globalQuery[it] }
    val candidatePre = decision.candidates.map { candidate ->
        projectPreActivation(
            candidate,
            artifact.candidateWeights,
            artifact.candidateBias,
            config.candidateDimension,
            hidden,
        )
    }
    val candidates = candidatePre.map { pre -> DoubleArray(hidden) { tanh(pre[it]) } }
    val scoreScale = 1.0 / sqrt(hidden.toDouble())
    val stateActivationGradient = DoubleArray(hidden)
    candidates.indices.forEach { candidateIndex ->
        val scoreGradient = scoreGradients[candidateIndex]
        val vector = decision.candidates[candidateIndex]
        (0 until hidden).forEach { h ->
            stateActivationGradient[h] += scoreGradient * candidates[candidateIndex][h] * scoreScale
            val candidatePreGradient = scoreGradient * query[h] * scoreScale *
                (1.0 - candidates[candidateIndex][h] * candidates[candidateIndex][h])
            gradient.candidateBias[h] += candidatePreGradient
            val offset = h * config.candidateDimension
            vector.indices.indices.forEach { position ->
                gradient.candidateWeights[offset + vector.indices[position]] +=
                    candidatePreGradient * vector.values[position]
            }
        }
    }
    (0 until hidden).forEach { h ->
        gradient.globalQuery[h] += stateActivationGradient[h]
        val statePreGradient = stateActivationGradient[h] * (1.0 - state[h] * state[h])
        gradient.stateBias[h] += statePreGradient
        val offset = h * config.stateDimension
        decision.state.indices.indices.forEach { position ->
            gradient.stateWeights[offset + decision.state.indices[position]] +=
                statePreGradient * decision.state.values[position]
        }
    }
}

private fun projectPreActivation(
    vector: SparseFeatureVector,
    weights: DoubleArray,
    bias: DoubleArray,
    dimension: Int,
    hidden: Int,
): DoubleArray = DoubleArray(hidden) { h ->
    var total = bias[h]
    val offset = h * dimension
    vector.indices.indices.forEach { position ->
        total += weights[offset + vector.indices[position]] * vector.values[position]
    }
    total
}

private fun cohortSoftmax(scores: DoubleArray): DoubleArray {
    val maximum = scores.maxOrNull() ?: error("Empty score vector")
    val exponentials = DoubleArray(scores.size) { exp(scores[it] - maximum) }
    val total = exponentials.sum()
    return DoubleArray(scores.size) { exponentials[it] / total }
}

internal fun gradientNorm(gradient: DenseNeuralBcGradient, group: String): Double =
    sqrt(gradientDot(gradient, gradient, group))

internal fun gradientCosine(
    left: DenseNeuralBcGradient,
    right: DenseNeuralBcGradient,
    group: String,
): Double? {
    val leftNorm = gradientNorm(left, group)
    val rightNorm = gradientNorm(right, group)
    if (leftNorm == 0.0 || rightNorm == 0.0) return null
    return (gradientDot(left, right, group) / (leftNorm * rightNorm)).coerceIn(-1.0, 1.0)
}

internal fun gradientDot(
    left: DenseNeuralBcGradient,
    right: DenseNeuralBcGradient,
    group: String,
): Double {
    fun arrayDot(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size)
        var result = 0.0
        a.indices.forEach { result += a[it] * b[it] }
        return result
    }
    val state = arrayDot(left.stateWeights, right.stateWeights) +
        arrayDot(left.stateBias, right.stateBias) +
        arrayDot(left.globalQuery, right.globalQuery)
    val candidate = arrayDot(left.candidateWeights, right.candidateWeights) +
        arrayDot(left.candidateBias, right.candidateBias)
    return when (group) {
        "STATE_AND_QUERY" -> state
        "CANDIDATE_PROJECTION" -> candidate
        "ALL_PARAMETERS" -> state + candidate
        else -> error("Unknown parameter group $group")
    }
}

private fun dot(left: DoubleArray, right: DoubleArray): Double {
    var result = 0.0
    left.indices.forEach { result += left[it] * right[it] }
    return result
}
