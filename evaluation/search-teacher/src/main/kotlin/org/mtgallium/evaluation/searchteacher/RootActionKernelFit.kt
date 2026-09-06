package org.mtgallium.evaluation.searchteacher

import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.SemanticChoice

/** Normalized numeric encoder output; no IDs, targets, values or sampled worlds enter this vector. */
@Serializable
internal data class RootActionKernelVector(val indices: List<Int>, val values: List<Double>) {
    init {
        require(indices.size == values.size && indices.all { it >= 0 } && indices.zipWithNext().all { (a, b) -> a < b })
        require(values.all(Double::isFinite))
    }
    fun dot(other: RootActionKernelVector): Double {
        var i = 0; var j = 0; var sum = 0.0
        while (i < indices.size && j < other.indices.size) {
            when {
                indices[i] < other.indices[j] -> i++
                indices[i] > other.indices[j] -> j++
                else -> { sum += values[i] * other.values[j]; i++; j++ }
            }
        }
        return sum
    }
}

@Serializable
internal data class RootActionKernelFeatures(val state: RootActionKernelVector, val centeredCandidate: RootActionKernelVector)

@Serializable
internal data class RootActionKernelTrainingRoot(val rootId: String, val seedGroupId: String,
    val features: List<RootActionKernelFeatures>, val actionMeans: List<Double>) {
    init {
        require(rootId.isNotBlank() && seedGroupId.isNotBlank() && features.size >= 2 && features.size == actionMeans.size)
        require(actionMeans.all { it.isFinite() && it in -1.0..1.0 })
    }
}

@Serializable
internal data class RootActionKernelModel(val schemaVersion: Int = 1,
    val featureSchema: String = NEURAL_BC_FEATURE_SCHEMA,
    val stateDimension: Int = 1024, val candidateDimension: Int = 512,
    val ridge: Double, val centers: List<RootActionKernelFeatures>, val coefficients: List<Double>) {
    init {
        require(schemaVersion == 1 && featureSchema == NEURAL_BC_FEATURE_SCHEMA && stateDimension == 1024 && candidateDimension == 512)
        require(ridge.isFinite() && ridge > 0 && centers.isNotEmpty() && centers.size == coefficients.size && coefficients.all(Double::isFinite))
        require(centers.all { f -> f.state.indices.all { it < stateDimension } && f.centeredCandidate.indices.all { it < candidateDimension } })
    }
    fun score(features: RootActionKernelFeatures): Double = centers.indices.sumOf { coefficients[it] * rootActionKernel(centers[it], features) }
}

/** Explicit feature map c + (s tensor c); shared root state alone cancels from action ordering. */
internal fun rootActionKernel(a: RootActionKernelFeatures, b: RootActionKernelFeatures): Double =
    (1 + a.state.dot(b.state)) * a.centeredCandidate.dot(b.centeredCandidate)

internal fun rootActionKernelFeatures(information: PolicyInformationState, candidates: List<SemanticChoice>): List<RootActionKernelFeatures> {
    val encoded = NeuralBehavioralCloningFeatureEncoder(1024, 512).encodeLivePolicyMenuForInference(information, candidates)
    fun normalized(f: SparseFeatureVector): RootActionKernelVector {
        val norm = sqrt(f.values.sumOf { it * it })
        require(norm > 0 && norm.isFinite())
        return RootActionKernelVector(f.indices.toList(), f.values.map { it / norm })
    }
    val state = normalized(encoded.state)
    val action = encoded.candidates.map(::normalized)
    val average = sortedMapOf<Int, Double>()
    action.forEach { f -> f.indices.forEachIndexed { i, index -> average[index] = average.getOrDefault(index, 0.0) + f.values[i] / action.size } }
    return action.map { f ->
        val centered = average.mapValues { -it.value }.toSortedMap()
        f.indices.forEachIndexed { i, index -> centered[index] = centered.getValue(index) + f.values[i] }
        val nonzero = centered.filterValues { it != 0.0 }
        RootActionKernelFeatures(state, RootActionKernelVector(nonzero.keys.toList(), nonzero.values.toList()))
    }
}

/** Cholesky solves a strictly positive-definite regularized kernel system; no inverse or optimizer sweep. */
internal fun solveRootActionKernel(matrix: Array<DoubleArray>, target: DoubleArray): DoubleArray {
    val n = target.size
    require(n > 0 && matrix.size == n && matrix.all { it.size == n && it.all(Double::isFinite) } && target.all(Double::isFinite))
    val lower = Array(n) { DoubleArray(n) }
    for (i in 0 until n) for (j in 0..i) {
        require(kotlin.math.abs(matrix[i][j] - matrix[j][i]) < 1e-10)
        var residual = matrix[i][j]
        for (k in 0 until j) residual -= lower[i][k] * lower[j][k]
        lower[i][j] = if (i == j) { require(residual > 0 && residual.isFinite()); sqrt(residual) } else residual / lower[j][j]
    }
    val y = DoubleArray(n)
    for (i in 0 until n) { var value = target[i]; for (j in 0 until i) value -= lower[i][j] * y[j]; y[i] = value / lower[i][i] }
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) { var value = y[i]; for (j in i + 1 until n) value -= lower[j][i] * x[j]; x[i] = value / lower[i][i] }
    require(x.all(Double::isFinite))
    return x
}

/** Equal seed-group, root and action weighting; root-wide reference offsets are removed before fitting. */
internal fun fitRootActionKernel(roots: List<RootActionKernelTrainingRoot>, ridge: Double): RootActionKernelModel {
    require(roots.isNotEmpty() && roots.map { it.rootId }.distinct().size == roots.size && ridge.isFinite() && ridge > 0)
    val groups = roots.groupingBy { it.seedGroupId }.eachCount()
    val centers = roots.flatMap { it.features }
    val weights = roots.flatMap { root -> List(root.features.size) { sqrt(1.0 / groups.size / groups.getValue(root.seedGroupId) / root.features.size) } }
    val values = roots.flatMap { root -> val mean = root.actionMeans.average(); root.actionMeans.map { it - mean } }
    val matrix = Array(centers.size) { i -> DoubleArray(centers.size) { j ->
        weights[i] * weights[j] * rootActionKernel(centers[i], centers[j]) + if (i == j) ridge else 0.0
    } }
    val alpha = solveRootActionKernel(matrix, DoubleArray(values.size) { weights[it] * values[it] })
    return RootActionKernelModel(ridge = ridge, centers = centers, coefficients = alpha.indices.map { alpha[it] * weights[it] })
}
