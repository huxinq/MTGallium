package org.mtgallium.evaluation.searchteacher

/** Identifies the changed floating-point evaluation order, not a new learned model. */
internal const val COMPILED_ROOT_ACTION_KERNEL_ID = "bilinear-root-menu-scorer-v1"

/**
 * Compiles sum_i alpha_i (1 + s_i dot s) (c_i dot c) into b dot c + s^T W c.
 * The shared state projection is computed once per menu. Training, normalization,
 * and candidate centering stay unchanged. Scores are algebraically equivalent to
 * the dual model; floating-point order (and potentially near-tie choices) differs.
 * Arrays are private and read-only after construction, so menus can be scored concurrently.
 */
internal class CompiledRootActionKernel(model: RootActionKernelModel) {
    private val stateDimension = model.stateDimension
    private val candidateDimension = model.candidateDimension
    private val bias = DoubleArray(candidateDimension)
    private val interaction = Array(stateDimension) { DoubleArray(candidateDimension) }

    init {
        model.centers.forEachIndexed { i, center ->
            val alpha = model.coefficients[i]
            center.centeredCandidate.indices.forEachIndexed { j, candidateIndex ->
                val weightedCandidate = alpha * center.centeredCandidate.values[j]
                bias[candidateIndex] += weightedCandidate
                center.state.indices.forEachIndexed { k, stateIndex ->
                    interaction[stateIndex][candidateIndex] += center.state.values[k] * weightedCandidate
                }
            }
        }
        require(bias.all(Double::isFinite) && interaction.all { it.all(Double::isFinite) }) {
            "Non-finite compiled kernel coefficients"
        }
    }

    fun scores(features: List<RootActionKernelFeatures>): List<Double> {
        require(features.isNotEmpty())
        val state = features.first().state
        require(state.indices.all { it < stateDimension })
        require(features.all { it.state == state && it.centeredCandidate.indices.all { index -> index < candidateDimension } }) {
            "Compiled scoring requires one shared root state and in-range candidates"
        }
        val projected = bias.copyOf()
        state.indices.forEachIndexed { i, stateIndex ->
            val row = interaction[stateIndex]
            val value = state.values[i]
            for (j in projected.indices) projected[j] += value * row[j]
        }
        require(projected.all(Double::isFinite)) { "Non-finite compiled kernel projection" }
        return features.map { feature ->
            val candidate = feature.centeredCandidate
            candidate.indices.indices.sumOf { i -> projected[candidate.indices[i]] * candidate.values[i] }
                .also { require(it.isFinite()) { "Non-finite compiled kernel score" } }
        }
    }
}
