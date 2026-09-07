package org.mtgallium.evaluation.searchteacher

import kotlin.math.ln
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind

@Tag("public-source")
class CloningGeneralizationTest {
    @Test
    fun `uncapped loss preserves extreme wrong-label evidence and rejects nonfinite scores`() {
        assertEquals(ln(2.0), cloningUncappedCrossEntropy(doubleArrayOf(0.0, 0.0), 1), 1e-14)
        assertEquals(1000.0, cloningUncappedCrossEntropy(doubleArrayOf(1000.0, 0.0), 1), 1e-14)
        assertFails { cloningUncappedCrossEntropy(doubleArrayOf(Double.NaN, 0.0), 0) }
        assertFails { cloningUncappedCrossEntropy(doubleArrayOf(0.0, Double.POSITIVE_INFINITY), 0) }
    }

    @Test
    fun `counterpart games cannot split one pair across training and validation`() {
        val groups = mapOf("a0" to "pair-a", "a1" to "pair-a", "b0" to "pair-b")
        assertEquals(mapOf("pair-a" to "TRAIN", "pair-b" to "VALIDATION"),
            cloningGroupPartitions(mapOf("a0" to "TRAIN", "b0" to "VALIDATION"), groups, mapOf("pair-a" to 17L, "pair-b" to 19L)))
        assertFails { cloningGroupPartitions(mapOf("a0" to "TRAIN", "a1" to "VALIDATION", "b0" to "VALIDATION"), groups, mapOf("pair-a" to 17L, "pair-b" to 19L)) }
        assertFails { cloningGroupPartitions(mapOf("a0" to "TRAIN", "b0" to "VALIDATION"), groups, mapOf("pair-a" to 17L, "pair-b" to 17L)) }
    }

    @Test
    fun `evaluation counts matched decisions with first-menu tie breaking and distinct pair units`() {
        val empty = SparseFeatureVector(intArrayOf(), doubleArrayOf())
        fun decision(game: String, index: Int, label: Int) = EncodedBcDecision(game, index, "synthetic", empty,
            listOf(empty, empty), List(2) { SemanticOperationFamily.MULLIGAN },
            List(2) { SemanticActionIntentKind.KEEP_HAND }, label)
        val policy = object : NeuralBcScoringPolicy {
            override fun scores(decision: EncodedBcDecision) = doubleArrayOf(0.0, 0.0)
        }
        val rows = listOf(decision("a0", 0, 0), decision("a1", 1, 1), decision("b0", 0, 0))
        val groups = mapOf("a0" to "a", "a1" to "a", "b0" to "b")
        val metrics = evaluateCloningGeneralization(policy, rows, groups)
        assertEquals(3, metrics.decisions)
        assertEquals(3, metrics.games)
        assertEquals(2, metrics.pairGroups)
        assertEquals(2, metrics.exactActions)
        assertEquals(2.0 / 3, metrics.exactActionAgreement)
        assertEquals(ln(2.0), metrics.meanUncappedCrossEntropy, 1e-14)
        assertFails { evaluateCloningGeneralization(policy, rows + rows.first(), groups) }
    }
}
