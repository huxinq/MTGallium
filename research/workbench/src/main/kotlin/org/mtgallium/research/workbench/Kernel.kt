package org.mtgallium.research.workbench

import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.DecisionPoint
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.SemanticChoice

// Frozen casting-kernel features for horizon16; training is archived.
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
