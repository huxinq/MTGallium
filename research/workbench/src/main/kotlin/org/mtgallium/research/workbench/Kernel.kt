package org.mtgallium.research.workbench

import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.CholeskyDecomposition
import org.mtgallium.agent.infoset.core.DecisionSite
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.SemanticChoice

@Serializable
data class RootActionKernelVector(val indices: List<Int>, val values: List<Double>) {
    init {
        require(indices.size == values.size && indices.all { it >= 0 })
        require(indices.zipWithNext().all { (a, b) -> a < b } && values.all(Double::isFinite))
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
data class RootActionKernelFeatures(val state: RootActionKernelVector, val centeredCandidate: RootActionKernelVector)

/** IDs are joins and weighting groups; neither is a predictive feature. Targets are caller-supplied quantities. */
@Serializable
data class RootActionKernelTrainingRoot(
    val rootId: String,
    val seedGroupId: String,
    val features: List<RootActionKernelFeatures>,
    val actionMeans: List<Double>,
) {
    init {
        require(rootId.isNotBlank() && seedGroupId.isNotBlank())
        require(features.isNotEmpty() && features.size == actionMeans.size && actionMeans.all(Double::isFinite))
    }
}

@Serializable
data class RootActionKernelModel(
    val ridge: Double,
    val centers: List<RootActionKernelFeatures>,
    val coefficients: List<Double>,
) {
    init {
        require(ridge.isFinite() && ridge > 0)
        require(centers.isNotEmpty() && centers.size == coefficients.size && coefficients.all(Double::isFinite))
    }
    fun score(features: RootActionKernelFeatures): Double = centers.indices.sumOf {
        coefficients[it] * rootActionKernel(centers[it], features)
    }.also { require(it.isFinite()) { "Kernel score overflow" } }
    fun scores(menu: List<RootActionKernelFeatures>): List<Double> = menu.map(::score)
}

fun rootActionKernel(a: RootActionKernelFeatures, b: RootActionKernelFeatures): Double =
    (1 + a.state.dot(b.state)) * a.centeredCandidate.dot(b.centeredCandidate)

/** Current semantic features; a captured live site and a saved player record use the same computation. */
fun rootActionKernelFeatures(site: DecisionSite, stateDimension: Int = 1024, candidateDimension: Int = 512): List<RootActionKernelFeatures> =
    rootActionKernelFeatures(site.information(), site.expansion.candidates, stateDimension, candidateDimension)

fun rootActionKernelFeatures(
    information: InformationStateRepresentation,
    candidates: List<SemanticChoice> = information.candidates,
    stateDimension: Int = 1024,
    candidateDimension: Int = 512,
): List<RootActionKernelFeatures> {
    fun normalized(vector: RootActionKernelVector): RootActionKernelVector {
        val norm = sqrt(vector.values.sumOf { it * it })
        require(norm > 0 && norm.isFinite())
        return RootActionKernelVector(vector.indices, vector.values.map { it / norm })
    }
    require(candidates.isNotEmpty() && candidates.all { it.schemaVersion == information.candidateSchemaVersion })
    val encoder = SemanticFeatures(information, stateDimension, candidateDimension)
    val state = normalized(encoder.state())
    val actions = candidates.map { normalized(encoder.candidate(it)) }
    val mean = sortedMapOf<Int, Double>()
    actions.forEach { action -> action.indices.forEachIndexed { i, index ->
        mean[index] = mean.getOrDefault(index, 0.0) + action.values[i] / actions.size
    } }
    return actions.map { action ->
        val centered = mean.mapValues { -it.value }.toSortedMap()
        action.indices.forEachIndexed { i, index -> centered[index] = centered.getValue(index) + action.values[i] }
        val nonzero = centered.filterValues { it != 0.0 }
        RootActionKernelFeatures(state, RootActionKernelVector(nonzero.keys.toList(), nonzero.values.toList()))
    }
}

/**
 * Minimize sum_i w_i (f_i - (y_i - mean_root(y)))² + ridge ||f||²_K.
 * Default mass is equal per group, then root, then action. Explicit positive masses are
 * used as supplied, not silently renormalized. Raw scores are never clipped.
 */
fun fitRootActionKernel(
    roots: List<RootActionKernelTrainingRoot>,
    ridge: Double = 0.001,
    actionWeights: Map<String, List<Double>>? = null,
): RootActionKernelModel {
    require(roots.isNotEmpty() && roots.map { it.rootId }.distinct().size == roots.size)
    require(ridge.isFinite() && ridge > 0)
    val groups = roots.groupingBy { it.seedGroupId }.eachCount()
    val weights = actionWeights ?: roots.associate { root -> root.rootId to List(root.features.size) {
        1.0 / groups.size / groups.getValue(root.seedGroupId) / root.features.size
    } }
    require(weights.keys == roots.map { it.rootId }.toSet()) { "Weights must cover the fitted roots exactly" }
    roots.forEach { root -> require(weights.getValue(root.rootId).let { values ->
        values.size == root.features.size && values.all { it.isFinite() && it > 0 }
    }) }
    val centers = roots.flatMap { it.features }
    val scale = roots.flatMap { root -> weights.getValue(root.rootId).map(::sqrt) }
    val centered = roots.flatMap { root ->
        val mean = root.actionMeans.average()
        root.actionMeans.map { it - mean }
    }
    val gram = Array(centers.size) { i -> DoubleArray(centers.size) { j ->
        scale[i] * rootActionKernel(centers[i], centers[j]) * scale[j] + if (i == j) ridge else 0.0
    } }
    require(gram.all { row -> row.all(Double::isFinite) } && centered.all(Double::isFinite))
    val target = DoubleArray(centers.size) { scale[it] * centered[it] }
    val solution = CholeskyDecomposition(Array2DRowRealMatrix(gram, false), 1e-12, 0.0).solver
        .solve(ArrayRealVector(target, false)).toArray()
    return RootActionKernelModel(ridge, centers, solution.indices.map { solution[it] * scale[it] })
}
