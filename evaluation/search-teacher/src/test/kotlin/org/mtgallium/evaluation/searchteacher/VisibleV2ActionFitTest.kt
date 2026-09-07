package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.*

@Tag("public-source")
class VisibleV2ActionFitTest {
    private val hand = MonoRedVisibleEvaluatorConfig()
    private val empty = MonoRedVisibleFeatures(20, 20, 0, 0, 0, 0, emptyList(), emptyList())
    private fun action(key: String) = SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay(key),
        canonicalPayload = JsonObject(mapOf("key" to JsonPrimitive(key))))
    private fun trace(key: String, features: MonoRedVisibleFeatures) = VisibleV2ActionTrace(action(key), 1,
        listOf(features), 0.0, features.evaluate(hand), SearchSettlementCounts(heuristicSettlementBackups = 1))

    @Test fun `root value offsets cancel while wrong action ordering remains visible`() {
        centeredActionResiduals(listOf(.2, .4), listOf(.1, .3)).zip(
            centeredActionResiduals(listOf(.3, .5), listOf(.2, .4))).forEach { (a, b) -> assertEquals(a, b, 1e-12) }
        val residuals = centeredActionResiduals(listOf(.4, .2), listOf(.1, .3))
        assertEquals(.2, residuals[0], 1e-12)
        assertEquals(-.2, residuals[1], 1e-12)
    }
    @Test fun `fixed nonheuristic contribution is unchanged by coefficient rescoring`() {
        val t = VisibleV2ActionTrace(action("a"), 2, listOf(empty.copy(rootLife = 24)), 1.0,
            (1 + empty.copy(rootLife = 24).evaluate(hand)) / 2,
            SearchSettlementCounts(terminalPayoffBackups = 1, heuristicSettlementBackups = 1))
        val modified = hand.copy(life = .8)
        assertEquals((1 + t.features.single().evaluate(modified)) / 2, t.meanValue(modified))
        assertFailsWith<IllegalArgumentException> { t.copy(features = emptyList()) }
        assertFailsWith<IllegalArgumentException> { t.copy(fixedBackupValueSum = 2.0) }
    }
    @Test fun `action gap fitting corrects a synthetic ordering and selects only development regret`() {
        val a = trace("life", empty.copy(rootLife = 25))
        val b = trace("hand", empty.copy(rootHandSize = 3))
        val root = VisibleV2ActionFitRoot("r", "group", listOf(a, b), mapOf(a.action.signature to .6, b.action.signature to .1))
        val result = fitVisibleV2ActionOrdering(listOf(root), hand, VisibleV2FitConfig(iterations = 300))
        assertTrue(result.hand.fixedTraceReferenceRegret > 0)
        assertEquals(0.0, result.selected.fixedTraceReferenceRegret)
        assertTrue(a.meanValue(result.fitted) > b.meanValue(result.fitted))
        assertEquals(hand.tanhScale, result.fitted.tanhScale)
        assertFailsWith<IllegalArgumentException> { root.copy(referenceMeans = mapOf(a.action.signature to .6)) }
    }
}
