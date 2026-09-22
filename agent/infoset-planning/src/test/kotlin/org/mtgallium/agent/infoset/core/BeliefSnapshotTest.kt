package org.mtgallium.agent.infoset.core

import kotlin.test.*

class BeliefSnapshotTest {
    @Test fun `query consumers work with an analytic backend without materializing worlds`() {
        val backend = AnalyticPresence(.3)
        val queries: BeliefQueryView = backend.queries
        assertEquals(.3, queries.opponentHand.probabilityAtLeast("A"))
        assertEquals(.3, queries.opponentHand.probabilityAllOf(mapOf("A" to 1)))
        assertEquals(.3, queries.opponentHand.expectedCopies("A"))
        assertEquals(0, backend.materializations)
        val generated = backend.hypotheses.materialize()
        assertEquals(1, backend.materializations)
        queries.requireSameSnapshot(generated)
        assertSame(queries.binding, generated.binding)
        val mass = generated.batch.particles.filter { (it.value as PresenceWorld).present }.sumOf { it.weight }
        assertEquals(queries.opponentHand.probabilityAtLeast("A"), mass)
    }

    @Test fun `published query view retains only detached estimates and their binding`() {
        val snapshot = AnalyticPresence(.3)
        val view = snapshot.queries
        val values = view.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { field -> field.isAccessible = true; field.get(view) }
        assertEquals(2, values.size)
        assertTrue(values.any { it === view.binding })
        assertTrue(values.any { it === view.opponentHand })
        assertTrue(values.none { it is BeliefSnapshot })
        assertSame(view, snapshot.queries)
    }

    @Test fun `matching epistemic and model labels do not permit cross-snapshot hypothesis joins`() {
        val first = AnalyticPresence(.3)
        val second = AnalyticPresence(.3)
        assertEquals(first.queries.binding.epistemicDigest, second.queries.binding.epistemicDigest)
        assertEquals(first.queries.binding.inferenceModelIdentity, second.queries.binding.inferenceModelIdentity)
        assertFailsWith<IllegalArgumentException> { first.queries.requireSameSnapshot(second.hypotheses.materialize()) }
        first.queries.requireSameSnapshot(first.hypotheses.materialize())
        assertTrue(BeliefQueryView::class.java.methods.none { it.returnType == SearchWorld::class.java || it.returnType == GeneratedHypotheses::class.java })
    }

    @Test fun `wrong perspective empty or unnormalized generation fails at its capability boundary`() {
        val wrong = object : BeliefSnapshot("p1", "epistemic", "test-model") {
            override fun handQueries() = AnalyticPresence(.3).queries.opponentHand
            override fun generateHypotheses() = batch(emptyList())
        }
        assertFailsWith<IllegalArgumentException> { wrong.queries.opponentHand }
        assertFailsWith<IllegalArgumentException> { wrong.hypotheses.materialize() }
        val unnormalized = object : BeliefSnapshot("p0", "epistemic", "test-model") {
            override fun handQueries() = AnalyticPresence(.3).queries.opponentHand
            override fun generateHypotheses() = batch(listOf(Weighted<SearchWorld>(PresenceWorld(true), .5)))
        }
        assertFailsWith<IllegalArgumentException> { unnormalized.hypotheses.materialize() }
    }

    /** A deliberately small second backend, not a strategic model or a particle implementation. */
    private class AnalyticPresence(private val probability: Double) : BeliefSnapshot("p0", "same-epistemic-view", "analytic-presence-v1") {
        var materializations = 0
        override fun handQueries() = object : OpponentHandBeliefQueries {
            override val viewerAlias = "p0"
            override fun probabilityAtLeast(cardName: String, minimumCopies: Int): Double {
                require(cardName.isNotBlank() && minimumCopies >= 0)
                return when { minimumCopies == 0 -> 1.0; cardName == "A" && minimumCopies == 1 -> probability; else -> 0.0 }
            }
            override fun probabilityAllOf(minimumCopiesByCard: Map<String, Int>): Double {
                require(minimumCopiesByCard.all { it.key.isNotBlank() && it.value >= 0 })
                return if (minimumCopiesByCard.all { it.value == 0 }) 1.0
                else if (minimumCopiesByCard.all { it.value == 0 || it.key == "A" && it.value == 1 }) probability else 0.0
            }
            override fun probabilityAnyOf(cardNames: Set<String>): Double = if ("A" in cardNames) probability else 0.0
            override fun expectedCopies(cardName: String) = probabilityAtLeast(cardName)
            override fun presenceMarginals() = mapOf("A" to probability)
        }
        override fun generateHypotheses(): BeliefBatch<Weighted<SearchWorld>> {
            materializations++
            return batch(listOf(Weighted<SearchWorld>(PresenceWorld(true), probability), Weighted<SearchWorld>(PresenceWorld(false), 1 - probability)))
        }
    }

    private class PresenceWorld(val present: Boolean) : SearchWorld {
        override fun actorToAct(): String? = null
        override fun decisionContext(view: DecisionView): DecisionSiteRequest =
            error("Query/generation fixture does not execute gameplay")
        override fun expandChoices() = PolicyExpansion(emptyList(), true, 0, "terminal-fixture")
        override fun informationState(viewer: String): InformationStateRepresentation = error("Query/generation fixture does not execute gameplay")
        override fun step(choice: SemanticChoice): SearchStepResult = error("No decisions in fixture")
        override fun fork(): SearchWorld = PresenceWorld(present)
        override fun terminalPayoff(rootPlayer: String) = if (present) 1.0 else -1.0
        override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String) = error("Terminal fixture")
    }
    companion object {
        private fun batch(worlds: List<Weighted<SearchWorld>>) = BeliefBatch(worlds,
            BeliefDiagnostics(BeliefMode.CONSISTENCY_ONLY_V1, worlds.size, worlds.size, 0,
                worlds.size.toDouble(), worlds.size.toDouble(), 0.0, 0))
    }
}
