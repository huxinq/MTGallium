package org.mtgallium.evaluation.searchteacher

import kotlin.math.abs
import org.mtgallium.agent.searchteacher.FACTUAL_OUTCOME_RESIDUAL_RIDGE
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatures
import org.mtgallium.agent.searchteacher.factualOutcomeResidualFeatureComparator

internal data class FactualOutcomeResidualFitRow(
    val features: LearnedOutcomeValueFeatures,
    val residualTarget: Double,
    val weight: Double,
) {
    init {
        require(residualTarget.isFinite() && residualTarget in -2.0..2.0)
        require(weight.isFinite() && weight > 0.0)
    }
}

internal data class FactualOutcomeResidualFit(
    val bias: Double,
    val weights: Map<String, Double>,
    val iterations: Int,
    /** Infinity norm of the full weighted-MSE plus ridge gradient, including the bias coordinate. */
    val maxGradientResidual: Double,
)

private const val RESIDUAL_GRADIENT_TOLERANCE = 1e-9

/**
 * Deterministic matrix-free Jacobi-preconditioned conjugate gradients for the fixed objective.
 * The intercept is coordinate zero of the same penalized vector, never centered out or unpenalized.
 * Row order is supplied by the caller's verified allocation; only supplied features enter vocabulary.
 */
internal fun fitFactualOutcomeResidual(rows: List<FactualOutcomeResidualFitRow>): FactualOutcomeResidualFit {
    require(rows.isNotEmpty())
    require(abs(residualSum(rows.map { it.weight }) - 1.0) <= 1e-12) { "Residual fit weights must sum to one" }
    val keys = rows.flatMap { it.features.values.keys }.distinct().sortedWith(factualOutcomeResidualFeatureComparator)
    val indices = keys.withIndex().associate { it.value to it.index + 1 }
    val sparse = rows.map { row ->
        val entries = row.features.values.entries.sortedWith { a, b -> factualOutcomeResidualFeatureComparator.compare(a.key, b.key) }
        ResidualSparseRow(intArrayOf(0) + entries.map { indices.getValue(it.key) }.toIntArray(),
            doubleArrayOf(1.0) + entries.map { it.value }.toDoubleArray(), row.residualTarget, row.weight)
    }
    val dimension = keys.size + 1
    val rhs = ResidualVectorSum(dimension)
    val diagonal = ResidualVectorSum(dimension)
    sparse.forEach { row -> row.indices.indices.forEach { index ->
        val coordinate = row.indices[index]
        val value = row.values[index]
        rhs.add(coordinate, row.weight * row.target * value)
        diagonal.add(coordinate, row.weight * value * value)
    } }
    val b = rhs.values()
    val preconditioner = diagonal.values().map { residualFinite(it + FACTUAL_OUTCOME_RESIDUAL_RIDGE) }.toDoubleArray()
    fun apply(vector: DoubleArray): DoubleArray {
        val result = ResidualVectorSum(dimension)
        sparse.forEach { row ->
            val prediction = residualDot(row, vector)
            row.indices.indices.forEach { index -> result.add(row.indices[index], row.weight * row.values[index] * prediction) }
        }
        return result.values().mapIndexed { index, value -> residualFinite(value + FACTUAL_OUTCOME_RESIDUAL_RIDGE * vector[index]) }.toDoubleArray()
    }
    fun trueResidual(vector: DoubleArray): DoubleArray = apply(vector).mapIndexed { index, value -> residualFinite(b[index] - value) }.toDoubleArray()
    val coefficients = DoubleArray(dimension)
    var residual = b.copyOf()
    var preconditioned = residual.mapIndexed { index, value -> residualFinite(value / preconditioner[index]) }.toDoubleArray()
    var direction = preconditioned.copyOf()
    var product = residualDot(residual, preconditioned)
    var iterations = 0
    val maximumIterations = Math.multiplyExact(2, dimension)
    while (2.0 * residual.maxOf { abs(it) } > RESIDUAL_GRADIENT_TOLERANCE && iterations < maximumIterations) {
        require(product > 0.0 && product.isFinite()) { "Residual CG lost positive residual norm" }
        val applied = apply(direction)
        val curvature = residualDot(direction, applied)
        require(curvature > 0.0 && curvature.isFinite()) { "Residual CG lost positive curvature" }
        val alpha = residualFinite(product / curvature)
        coefficients.indices.forEach { index ->
            coefficients[index] = residualFinite(coefficients[index] + alpha * direction[index])
            residual[index] = residualFinite(residual[index] - alpha * applied[index])
        }
        iterations++
        val restart = iterations % 32 == 0 || 2.0 * residual.maxOf { abs(it) } <= RESIDUAL_GRADIENT_TOLERANCE
        if (restart) residual = trueResidual(coefficients)
        preconditioned = residual.mapIndexed { index, value -> residualFinite(value / preconditioner[index]) }.toDoubleArray()
        val nextProduct = residualDot(residual, preconditioned)
        val beta = if (restart) 0.0 else residualFinite(nextProduct / product)
        direction.indices.forEach { index -> direction[index] = residualFinite(preconditioned[index] + beta * direction[index]) }
        product = nextProduct
    }
    // Reconstruct the objective's gradient from row prediction errors, independently of CG recurrence.
    val gradient = ResidualVectorSum(dimension)
    sparse.forEach { row ->
        val error = residualFinite(residualDot(row, coefficients) - row.target)
        row.indices.indices.forEach { index -> gradient.add(row.indices[index], 2.0 * row.weight * row.values[index] * error) }
    }
    val maxGradient = gradient.values().mapIndexed { index, value ->
        abs(residualFinite(value + 2.0 * FACTUAL_OUTCOME_RESIDUAL_RIDGE * coefficients[index]))
    }.max()
    check(maxGradient <= RESIDUAL_GRADIENT_TOLERANCE) {
        "Residual fit lacks a numerical certificate after $iterations iterations: max gradient $maxGradient"
    }
    return FactualOutcomeResidualFit(coefficients[0], keys.associateWith { coefficients[indices.getValue(it)] }, iterations, maxGradient)
}

private data class ResidualSparseRow(val indices: IntArray, val values: DoubleArray, val target: Double, val weight: Double)
private fun residualDot(row: ResidualSparseRow, vector: DoubleArray): Double {
    var sum = 0.0
    var correction = 0.0
    for (index in row.indices.indices) {
        val value = residualFinite(row.values[index] * vector[row.indices[index]])
        val next = residualFinite(sum + value)
        correction += if (abs(sum) >= abs(value)) (sum - next) + value else (value - next) + sum
        sum = next
    }
    return residualFinite(sum + correction)
}
private fun residualDot(a: DoubleArray, b: DoubleArray): Double {
    var sum = 0.0
    var correction = 0.0
    for (index in a.indices) {
        val value = residualFinite(a[index] * b[index])
        val next = residualFinite(sum + value)
        correction += if (abs(sum) >= abs(value)) (sum - next) + value else (value - next) + sum
        sum = next
    }
    return residualFinite(sum + correction)
}
private fun residualFinite(value: Double): Double = value.also { require(it.isFinite()) { "Non-finite residual fit arithmetic" } }

private fun residualSum(values: Iterable<Double>): Double {
    val sum = ResidualVectorSum(1)
    values.forEach { sum.add(0, it) }
    return sum.values()[0]
}

/** Compensated sums keep the normal operator and independent gradient certificate stable. */
private class ResidualVectorSum(size: Int) {
    private val sum = DoubleArray(size)
    private val correction = DoubleArray(size)
    fun add(index: Int, value: Double) {
        residualFinite(value)
        val previous = sum[index]
        val next = residualFinite(previous + value)
        correction[index] += if (abs(previous) >= abs(value)) (previous - next) + value else (value - next) + previous
        sum[index] = next
    }
    fun values(): DoubleArray = sum.indices.map { residualFinite(sum[it] + correction[it]) }.toDoubleArray()
}
