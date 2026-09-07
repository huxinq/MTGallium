package org.mtgallium.evaluation.searchteacher

import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig

@Serializable
internal data class VisibleV2ActionFitRoot(
    val rootId: String, val seedGroupId: String,
    val traces: List<VisibleV2ActionTrace>, val referenceMeans: Map<String, Double>,
) {
    init {
        require(rootId.isNotBlank() && seedGroupId.isNotBlank() && traces.size >= 2)
        require(traces.map { it.action.signature }.distinct().size == traces.size)
        require(traces.map { it.action.signature }.toSet() == referenceMeans.keys)
        require(referenceMeans.values.all { it.isFinite() && it in -1.0..1.0 })
    }
}

@Serializable
internal data class VisibleV2ActionFitStep(
    val iteration: Int, val centeredActionMeanSquaredError: Double,
    val fixedTraceReferenceRegret: Double, val penalizedObjective: Double,
)

@Serializable
internal data class VisibleV2ActionFitResult(
    val fitted: MonoRedVisibleEvaluatorConfig, val selectedIteration: Int,
    val hand: VisibleV2ActionFitStep, val selected: VisibleV2ActionFitStep,
    val trace: List<VisibleV2ActionFitStep>,
    val interpretation: String = "Gradients minimize within-root centered action-value residuals plus the declared hand prior. Development fixed-continuation reference regret selects the checkpoint, then penalized loss breaks exact regret ties. This surrogate holds the captured continuation fixed; fresh-search evaluation is required before any decision-quality claim.",
)

/** Unlike root-value regression, any root-wide value offset cancels from this action comparison. */
internal fun centeredActionResiduals(predicted: List<Double>, reference: List<Double>): List<Double> {
    require(predicted.size == reference.size && predicted.size >= 2)
    require((predicted + reference).all { it.isFinite() })
    val residuals = predicted.zip(reference) { p, r -> p - r }
    val offset = residuals.average()
    return residuals.map { it - offset }
}

internal fun fitVisibleV2ActionOrdering(
    roots: List<VisibleV2ActionFitRoot>, hand: MonoRedVisibleEvaluatorConfig, fit: VisibleV2FitConfig,
): VisibleV2ActionFitResult {
    require(roots.isNotEmpty() && roots.map { it.rootId }.distinct().size == roots.size)
    val groupCounts = roots.groupingBy { it.seedGroupId }.eachCount()
    val weights = roots.map { 1.0 / groupCounts.size / groupCounts.getValue(it.seedGroupId) }
    val prior = visibleV2Coefficients(hand); val x = prior.copyOf()
    val first = DoubleArray(x.size); val second = DoubleArray(x.size)
    val basis = roots.map { root -> root.traces.map { action -> action.features.map(::visibleV2CoefficientFeatures) } }
    fun assess(iteration: Int, config: MonoRedVisibleEvaluatorConfig, gradient: DoubleArray?): VisibleV2ActionFitStep {
        var mse = 0.0; var regret = 0.0
        roots.forEachIndexed { rootIndex, root ->
            val values = root.traces.map { it.meanValue(config) }
            val reference = root.traces.map { root.referenceMeans.getValue(it.action.signature) }
            val residuals = centeredActionResiduals(values, reference)
            mse += weights[rootIndex] * residuals.map { it * it }.average()
            // First semantic-menu action wins an exact predicted tie, matching the declared surrogate rule.
            val chosen = values.indices.maxBy { values[it] }
            regret += weights[rootIndex] * (reference.max() - reference[chosen])
            if (gradient != null) root.traces.forEachIndexed { actionIndex, action ->
                val factor = 2 * weights[rootIndex] * residuals[actionIndex] / root.traces.size / action.visits / hand.tanhScale
                action.features.forEachIndexed { featureIndex, features ->
                    val value = features.evaluate(config)
                    val derivative = factor * (1 - value * value)
                    gradient.indices.forEach { j -> gradient[j] += derivative * basis[rootIndex][actionIndex][featureIndex][j] }
                }
            }
        }
        val coefficients = visibleV2Coefficients(config)
        val penalty = coefficients.indices.sumOf { (coefficients[it] - prior[it]).pow(2) } * fit.priorPenalty / coefficients.size
        return VisibleV2ActionFitStep(iteration, mse, regret, mse + penalty)
    }
    val initial = assess(0, hand, null)
    var best = initial; var bestConfig = hand
    val trace = mutableListOf(initial)
    for (iteration in 1..fit.iterations) {
        val gradient = DoubleArray(x.size) { j -> 2 * fit.priorPenalty * (x[j] - prior[j]) / x.size }
        assess(iteration - 1, visibleV2Configured(hand, x), gradient)
        for (j in x.indices) {
            require(gradient[j].isFinite())
            first[j] = .9 * first[j] + .1 * gradient[j]
            second[j] = .999 * second[j] + .001 * gradient[j] * gradient[j]
            x[j] -= fit.learningRate * (first[j] / (1 - .9.pow(iteration))) /
                (sqrt(second[j] / (1 - .999.pow(iteration))) + 1e-8)
        }
        val configured = visibleV2Configured(hand, x)
        val current = assess(iteration, configured, null)
        require(current.penalizedObjective.isFinite())
        if (current.fixedTraceReferenceRegret < best.fixedTraceReferenceRegret ||
            (current.fixedTraceReferenceRegret == best.fixedTraceReferenceRegret && current.penalizedObjective < best.penalizedObjective)) {
            best = current; bestConfig = configured
        }
        if (iteration % 50 == 0 || iteration == fit.iterations) trace += current
    }
    return VisibleV2ActionFitResult(bestConfig, best.iteration, initial, best, trace)
}
