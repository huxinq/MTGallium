package org.mtgallium.research.workbench

import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.CholeskyDecomposition
import org.mtgallium.agent.infoset.core.DecisionPoint
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.SemanticChoice

@Serializable @kotlinx.serialization.SerialName("org.mtgallium.research.workbench.RootActionKernelVector")
data class KernelFeatureVector(val indices: List<Int>, val values: List<Double>) {
    init {
        require(indices.size == values.size && indices.all { it >= 0 })
        require(indices.zipWithNext().all { (a, b) -> a < b } && values.all(Double::isFinite))
    }
    fun dot(other: KernelFeatureVector): Double {
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

@Serializable @kotlinx.serialization.SerialName("org.mtgallium.research.workbench.RootActionKernelFeatures")
data class KernelActionFeatures(val state: KernelFeatureVector, val centeredCandidate: KernelFeatureVector)

/** IDs are joins and weighting groups; neither is a predictive feature. Targets are caller-supplied quantities. */
@Serializable @kotlinx.serialization.SerialName("org.mtgallium.research.workbench.RootActionKernelTrainingRoot")
data class KernelTrainingRoot(
    val rootId: String,
    val seedGroupId: String,
    val features: List<KernelActionFeatures>,
    val actionMeans: List<Double>,
) {
    init {
        require(rootId.isNotBlank() && seedGroupId.isNotBlank())
        require(features.isNotEmpty() && features.size == actionMeans.size && actionMeans.all(Double::isFinite))
    }
}

@Serializable @kotlinx.serialization.SerialName("org.mtgallium.research.workbench.RootActionKernelModel")
data class KernelRidgeActionModel(
    val ridge: Double,
    val centers: List<KernelActionFeatures>,
    val coefficients: List<Double>,
) {
    init {
        require(ridge.isFinite() && ridge > 0)
        require(centers.isNotEmpty() && centers.size == coefficients.size && coefficients.all(Double::isFinite))
    }
    fun score(features: KernelActionFeatures): Double = centers.indices.sumOf {
        coefficients[it] * actionKernel(centers[it], features)
    }.also { require(it.isFinite()) { "Kernel score overflow" } }
    fun scores(menu: List<KernelActionFeatures>): List<Double> = menu.map(::score)
}

fun actionKernel(a: KernelActionFeatures, b: KernelActionFeatures): Double =
    (1 + a.state.dot(b.state)) * a.centeredCandidate.dot(b.centeredCandidate)

/** Current semantic features; a captured live site and a saved player record use the same computation. */
fun kernelActionFeatures(site: DecisionPoint, stateDimension: Int = 1024, candidateDimension: Int = 512): List<KernelActionFeatures> =
    kernelActionFeatures(site.information(), site.menu.candidates, stateDimension, candidateDimension)

fun kernelActionFeatures(
    information: InformationStateRepresentation,
    candidates: List<SemanticChoice> = information.candidates,
    stateDimension: Int = 1024,
    candidateDimension: Int = 512,
): List<KernelActionFeatures> {
    fun normalized(vector: KernelFeatureVector): KernelFeatureVector {
        val norm = sqrt(vector.values.sumOf { it * it })
        require(norm > 0 && norm.isFinite())
        return KernelFeatureVector(vector.indices, vector.values.map { it / norm })
    }
    require(candidates.isNotEmpty() && candidates.all { it.schemaVersion == information.candidateSchemaVersion })
    val encoder = HashedKernelFeatures(information, stateDimension, candidateDimension)
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
        KernelActionFeatures(state, KernelFeatureVector(nonzero.keys.toList(), nonzero.values.toList()))
    }
}

/**
 * Minimize sum_i w_i (f_i - (y_i - mean_root(y)))² + ridge ||f||²_K.
 * Default mass is equal per group, then root, then action. Explicit positive masses are
 * used as supplied, not silently renormalized. Raw scores are never clipped.
 */
fun fitKernelRidge(
    roots: List<KernelTrainingRoot>,
    ridge: Double = 0.001,
    actionWeights: Map<String, List<Double>>? = null,
): KernelRidgeActionModel {
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
        scale[i] * actionKernel(centers[i], centers[j]) * scale[j] + if (i == j) ridge else 0.0
    } }
    require(gram.all { row -> row.all(Double::isFinite) } && centered.all(Double::isFinite))
    val target = DoubleArray(centers.size) { scale[it] * centered[it] }
    val solution = CholeskyDecomposition(Array2DRowRealMatrix(gram, false), 1e-12, 0.0).solver
        .solve(ArrayRealVector(target, false)).toArray()
    return KernelRidgeActionModel(ridge, centers, solution.indices.map { solution[it] * scale[it] })
}
