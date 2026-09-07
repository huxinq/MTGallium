package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import kotlin.math.tanh
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.searchteacher.*
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.UnresolvedLeafHandling

@Tag("public-source")
class TacticalV3CalibrationTest {
    private val hand = MonoRedTacticalEvaluatorSettings()
    @Test fun `existing bounded formula and clipping derivative remain exact`() {
        val features = MonoRedTacticalLinearFeatures(listOf(.1, -.3, .4, .5, -.2, .8, -.1, .2, .3, -.4))
        val w = hand.weights
        val raw = w.life*.1 + w.lethal*-.3 + w.body*.4 + w.attack*.5 + w.block*-.2 + w.reach*.8 + w.hand*-.1 + w.mana*.2 + w.landConversion*.3 + w.initiative*-.4
        assertEquals(raw, features.rawScore(w))
        assertEquals(.95 * tanh(raw.coerceIn(-6.0, 6.0) / 2.0), features.evaluate(hand))
        val coefficients = tacticalV3Coefficients(w)
        for (j in coefficients.indices) {
            val plus = coefficients.copyOf().also { it[j] += 1e-5 }
            val minus = coefficients.copyOf().also { it[j] -= 1e-5 }
            val numeric = (features.evaluate(tacticalV3Configured(hand, plus)) - features.evaluate(tacticalV3Configured(hand, minus))) / 2e-5
            assertEquals(numeric, tacticalV3Derivative(features, hand) * features.values[j], 1e-9)
        }
        val saturated = MonoRedTacticalLinearFeatures(List(10) { 1.0 })
        assertTrue(saturated.rawScore(w) > 6)
        assertEquals(0.0, tacticalV3Derivative(saturated, hand))
        assertEquals(.95 * tanh(3.0), saturated.evaluate(hand))
        assertFailsWith<IllegalArgumentException> { MonoRedTacticalLinearFeatures(listOf(1.0)) }
    }
    @Test fun `fit recovers known ten weights without changing non-weight settings`() {
        val truth = tacticalV3Configured(hand, DoubleArray(10) { .1 + it * .05 })
        val targets = (0..9).flatMap { coordinate -> listOf(-.8, -.4, .4, .8).mapIndexed { index, value ->
            val features = MonoRedTacticalLinearFeatures(List(10) { if (it == coordinate) value else 0.0 })
            TacticalV3FitTarget("$coordinate-$index", "g$coordinate", features, features.evaluate(truth))
        } }
        val fit = fitTacticalV3Coefficients(targets, hand, VisibleV2FitConfig(iterations = 2000, priorPenalty = 0.0))
        assertTrue(fit.fittedMeanSquaredError < 1e-10)
        assertEquals(hand.copy(weights = fit.fitted.weights), fit.fitted)
        val unseen = MonoRedTacticalLinearFeatures(List(10) { if (it % 2 == 0) .3 else -.2 })
        assertEquals(unseen.evaluate(truth), unseen.evaluate(fit.fitted), 1e-5)
        val unchanged = fitTacticalV3Coefficients(targets.map { it.copy(target = it.features.evaluate(hand)) }, hand, VisibleV2FitConfig(iterations = 10))
        assertEquals(hand, unchanged.fitted); assertEquals(0, unchanged.selectedIteration)
    }
    @Test fun `group weights and invalid targets are guarded`() {
        val f = MonoRedTacticalLinearFeatures(List(10) { 0.0 })
        val rows = listOf(TacticalV3FitTarget("a", "g1", f, .1), TacticalV3FitTarget("b", "g1", f, .2), TacticalV3FitTarget("c", "g2", f, .3))
        assertEquals(listOf(.25, .25, .5), tacticalV3TargetWeights(rows))
        assertFailsWith<IllegalArgumentException> { tacticalV3TargetWeights(rows + rows.first()) }
        assertFailsWith<IllegalArgumentException> { tacticalV3TargetWeights(listOf(rows.first().copy(target = Double.NaN))) }
    }
    @Test fun `configured tactical policies use registered settlement while old serialized policies stay unchanged`() {
        val old = SearchTeacherCalibrationPolicy("test", 8, 64, 32, 1.4, true, 1.0)
        assertFalse(evidenceJson.encodeToString(SearchTeacherCalibrationPolicy.serializer(), old).contains("tacticalEvaluator"))
        assertEquals(LeafEvaluator.MTGALLIUM_VISIBLE_V2, old.parameters(1).leaf.evaluator)
        val tactical = old.copy(tacticalEvaluator = CalibrationTacticalEvaluator.Settings(hand))
        val parameters = tactical.parameters(1)
        assertEquals(LeafEvaluator.MTGALLIUM_TACTICAL_V3, parameters.leaf.evaluator)
        val strategy = SearchTeacherEvaluatorRegistry.strategy(parameters.leaf, MonoRedTacticalEvaluator(hand))
        assertTrue(strategy.settleAtRolloutHorizon)
        assertFalse(strategy.supportsTraceReuse)
        assertEquals(UnresolvedLeafHandling.BACK_UP_NEUTRAL, strategy.unresolvedLeafHandling)
        assertFailsWith<IllegalArgumentException> { tactical.copy(evaluator = MonoRedVisibleEvaluatorConfig()) }
        val screen = PositionBankScreenPolicy(tactical, MonoRedVisibleEvaluatorConfig())
        assertFailsWith<IllegalArgumentException> { PositionBankScreenPlan(bankDirectory = "bank", expectedBankIdentity = "id",
            partition = PositionBankScreenPartition.DEVELOPMENT, mode = PositionBankScreenMode.ACTION_CONDITIONAL_V2_TRACES,
            rootLimit = 1, repetitions = 1, policies = listOf(screen)) }
    }
}
