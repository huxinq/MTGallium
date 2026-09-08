package org.mtgallium.evaluation.searchteacher

import kotlin.math.abs
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.*

@Tag("public-source")
class FactualOutcomeResidualFitTest {
    @Test
    fun `weighted rank one solution penalizes intercept and permits residual magnitude above one`() {
        val features = features(20)
        val rows = listOf(FactualOutcomeResidualFitRow(features, 2.0, 0.75), FactualOutcomeResidualFitRow(features, -2.0, 0.25))
        val fit = fitFactualOutcomeResidual(rows)
        val denominator = 1.0 + features.values.values.sumOf { it * it } + FACTUAL_OUTCOME_RESIDUAL_RIDGE
        assertEquals(1.0 / denominator, fit.bias, 1e-10)
        features.values.forEach { (key, value) -> assertEquals(value / denominator, fit.weights.getValue(key), 1e-10) }
        assertTrue(abs(fit.bias - 1.0) > 0.5) // An unpenalized intercept would instead absorb the weighted mean.
        assertTrue(fit.maxGradientResidual <= 1e-9 && fit.iterations > 0)
        assertEquals(fit, fitFactualOutcomeResidual(rows))
    }

    @Test
    fun `independent weighted gradient certifies every coefficient and intercept`() {
        val rows = listOf(FactualOutcomeResidualFitRow(features(7), -1.7, 0.2),
            FactualOutcomeResidualFitRow(features(20), 0.5, 0.3), FactualOutcomeResidualFitRow(features(35), 1.8, 0.5))
        val fit = fitFactualOutcomeResidual(rows)
        val errors = rows.map { row -> fit.bias + row.features.values.entries.sumOf { (key, value) -> fit.weights.getValue(key) * value } - row.residualTarget }
        val gradients = fit.weights.map { (key, value) -> 2.0 * (rows.indices.sumOf { index ->
            rows[index].weight * errors[index] * (rows[index].features.values[key] ?: 0.0)
        } + FACTUAL_OUTCOME_RESIDUAL_RIDGE * value) } +
            (2.0 * (rows.indices.sumOf { rows[it].weight * errors[it] } + FACTUAL_OUTCOME_RESIDUAL_RIDGE * fit.bias))
        assertTrue(gradients.maxOf { abs(it) } <= 1e-9)
        assertEquals(rows.flatMap { it.features.values.keys }.distinct().sortedWith(factualOutcomeResidualFeatureComparator), fit.weights.keys.toList())
        val wrongWeights = rows.map { it.copy(weight = 1.0 / rows.size) }
        assertNotEquals(fit.bias, fitFactualOutcomeResidual(wrongWeights).bias)
    }

    @Test
    fun `zero targets yield exact zero coefficients without historical target coercion`() {
        val fit = fitFactualOutcomeResidual(listOf(FactualOutcomeResidualFitRow(features(20), 0.0, 1.0)))
        assertEquals(0.0, fit.bias)
        assertTrue(fit.weights.values.all { it == 0.0 })
        assertEquals(0, fit.iterations)
        assertEquals(0.0, fit.maxGradientResidual)
        assertFailsWith<IllegalArgumentException> { FactualOutcomeResidualFitRow(features(20), 2.1, 1.0) }
        assertFailsWith<IllegalArgumentException> { FactualOutcomeResidualFitRow(features(20), Double.NaN, 1.0) }
        assertFailsWith<IllegalArgumentException> { FactualOutcomeResidualFitRow(features(20), 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { fitFactualOutcomeResidual(listOf(FactualOutcomeResidualFitRow(features(20), 0.0, 0.9))) }
        assertFailsWith<IllegalArgumentException> { fitFactualOutcomeResidual(emptyList()) }
    }

    @Test
    fun `feature order is unsigned UTF8 byte order`() {
        assertEquals(listOf("a", "\uE000", "\uD800\uDC00"), listOf("\uD800\uDC00", "a", "\uE000").sortedWith(factualOutcomeResidualFeatureComparator))
    }

    private fun features(life: Int): LearnedOutcomeValueFeatures = LearnedOutcomeValueFeatureCompiler.compile(
        PolicyInformationState(actingPlayerId = "p0", observation = PolicyObservation(perspectivePlayerId = "p0", turnNumber = 1,
            phase = "PRECOMBAT_MAIN", step = "PRECOMBAT_MAIN", activePlayerId = "p0", priorityPlayerId = "p0",
            players = listOf(PolicyPlayerView("p0", "First", life, 1, 40, 0, 0, PolicyManaPool(), true, true, false),
                PolicyPlayerView("p1", "Second", 10, 1, 40, 0, 0, PolicyManaPool(), false, false, false)),
            zones = emptyList(), stack = emptyList(), currentTurnStateComplete = true, pendingDecision = null,
            observationDigest = PolicyJson.sha256("observation:$life")), informationStateDigest = PolicyJson.sha256("information:$life"),
            history = emptyList(), historyCommitment = PolicyHistoryCommitment.replay(emptyList()),
            knowledge = PolicyKnowledgeState(perspectivePlayerId = "p0", knowledgeDigest = PolicyJson.sha256("knowledge")),
            candidates = emptyList(), terminated = false, winnerId = null), "p0")
}
