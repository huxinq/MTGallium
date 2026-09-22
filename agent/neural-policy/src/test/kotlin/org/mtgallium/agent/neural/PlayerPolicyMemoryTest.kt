package org.mtgallium.agent.neural

import org.mtgallium.agent.infoset.core.*
import kotlin.test.*

class PlayerPolicyMemoryTest {
    @Test fun `interleaved games repeat scoring reset and fork match separate references`() {
        val model = CountingModel()
        val a = PlayerPolicySession(model, PolicyMemoryOwner("game-a", "p0"))
        val b = PlayerPolicySession(model, PolicyMemoryOwner("game-b", "p1"))
        val ah = listOf(NeuralFixtures.event(0, 1), NeuralFixtures.event(1, 2))
        val bh = listOf(NeuralFixtures.event(0, -1), NeuralFixtures.event(1, -2))
        a.observe(NeuralFixtures.state(ah.take(1)))
        b.observe(NeuralFixtures.state(bh.take(1), "p1"))
        val a1 = NeuralFixtures.site(NeuralFixtures.state(ah.take(1)))
        val before = a.snapshot()
        assertEquals(a.score(a1), a.score(a1))
        assertEquals(before, a.snapshot())
        assertEquals(0, a.observe(a1.epistemic).newlyDeliveredEvents)
        a.observe(NeuralFixtures.state(ah))
        b.observe(NeuralFixtures.state(bh, "p1"))
        for ((session, state) in listOf(a to NeuralFixtures.state(ah), b to NeuralFixtures.state(bh, "p1"))) {
            val isolated = PlayerPolicySession(model, session.owner)
            isolated.observe(state)
            assertEquals(isolated.snapshot(), session.snapshot())
            assertEquals(isolated.score(NeuralFixtures.site(state)), session.score(NeuralFixtures.site(state)))
        }
        val fork = a.fork("continuation-1")
        val branchState = NeuralFixtures.state(ah + NeuralFixtures.event(2, 5))
        fork.observe(branchState)
        assertEquals(2, a.snapshot().history.cursor)
        assertEquals(3, fork.snapshot().history.cursor)
        val separate = PlayerPolicySession(model, fork.owner)
        separate.observe(branchState)
        assertEquals(separate.snapshot(), fork.snapshot())
        val bSaved = b.snapshot()
        a.reset()
        assertEquals(0, a.snapshot().history.cursor)
        assertEquals(bSaved, b.snapshot())
        a.observe(NeuralFixtures.state(ah))
        assertEquals(2, a.snapshot().history.cursor)
        a.close()
        assertFailsWith<IllegalStateException> { a.score(a1) }
        assertEquals(0, model.closed)
        assertEquals(bSaved, b.snapshot())
    }

    @Test fun `restoration binds owner checkpoint schema and exact event prefix`() {
        val model = CountingModel()
        val session = PlayerPolicySession(model, PolicyMemoryOwner("game", "p0"))
        val state = NeuralFixtures.state(listOf(NeuralFixtures.event(0)))
        session.observe(state)
        val saved = session.snapshot()
        session.reset()
        session.restore(saved, state)
        assertEquals(saved, session.snapshot())
        for (bad in listOf(saved.copy(owner = saved.owner.copy(game = "other")),
            saved.copy(owner = saved.owner.copy(player = "p1")), saved.copy(modelIdentity = "other-model"),
            saved.copy(inputSchemaIdentity = "other-schema"), saved.copy(history = PolicyHistoryCommitment.empty()),
            saved.copy(memory = NeuralMemoryTensors(emptyList())))) {
            assertFailsWith<IllegalArgumentException> { session.restore(bad, state) }
            assertEquals(saved, session.snapshot())
        }
        assertFailsWith<IllegalArgumentException> { session.observe(NeuralFixtures.state()) }
        assertFailsWith<IllegalArgumentException> {
            session.observe(NeuralFixtures.state(listOf(NeuralFixtures.event(0, 99), NeuralFixtures.event(1))))
        }
        assertFailsWith<IllegalArgumentException> { session.observe(NeuralFixtures.state(player = "p1")) }
        assertFailsWith<UnsupportedOperationException> { (saved.memory.values as MutableList).clear() }
    }

    @Test fun `unconsumed observations and failing batches never become accepted memory`() {
        val model = CountingModel()
        val session = PlayerPolicySession(model, PolicyMemoryOwner("game", "p0"))
        val events = listOf(NeuralFixtures.event(0), NeuralFixtures.event(1))
        val future = NeuralFixtures.state(events)
        assertFailsWith<IllegalArgumentException> { session.score(NeuralFixtures.site(future)) }
        val before = session.snapshot()
        model.failUpdate = 2
        assertFailsWith<IllegalStateException> { session.observe(future) }
        assertEquals(before, session.snapshot())
        model.failUpdate = null
        session.observe(future)
        val reference = PlayerPolicySession(model, session.owner)
        reference.observe(future)
        assertEquals(reference.snapshot(), session.snapshot())
        val observed = session.snapshot()
        model.failScore = true
        assertFailsWith<IllegalStateException> { session.score(NeuralFixtures.site(future)) }
        assertEquals(observed, session.snapshot())
        model.failScore = false
        model.nonfiniteScore = true
        assertFailsWith<IllegalArgumentException> { session.score(NeuralFixtures.site(future)) }
        assertEquals(observed, session.snapshot())
    }

    @Test fun `unsupported delivered transitions never consume a memory cursor`() {
        val model = CountingModel()
        val session = PlayerPolicySession(model, PolicyMemoryOwner("game", "p0"))
        val before = session.snapshot()
        val unsupported = NeuralFixtures.event(0).copy(kind = PolicyHistoryEventKind.UNSUPPORTED_VISIBLE_TRANSITION,
            detail = PerspectiveEventDetail.UnsupportedVisibleTransition(engineEventType = "Unknown", reason = "test"))
        assertFailsWith<FactualEncodingException> { session.observe(NeuralFixtures.state(listOf(unsupported))) }
        assertEquals(before, session.snapshot())
        assertEquals(0, model.updates)
    }

    private class CountingModel : SequencePolicyModel {
        override val identity = "test-frozen-weights-v1"
        override val schema = FactualTensorSchema()
        override val architecture = NeuralArchitecture.GRU
        var updates = 0
        var failUpdate: Int? = null
        var failScore = false
        var nonfiniteScore = false
        var closed = 0
        override fun initialMemory() = NeuralMemoryTensors(listOf(0f))
        override fun validateMemory(memory: NeuralMemoryTensors) { require(memory.values.size == 1 && memory.historyMask.isEmpty()) }
        override fun advance(event: List<Int>, memory: NeuralMemoryTensors): NeuralMemoryTensors {
            updates++
            check(updates != failUpdate) { "injected update failure" }
            return NeuralMemoryTensors(listOf(memory.values.single() + event.sum().toFloat()))
        }
        override fun score(input: FactualDecisionTensors, memory: NeuralMemoryTensors): List<Float> {
            check(!failScore) { "injected inference failure" }
            return input.actions.indices.map { if (nonfiniteScore) Float.NaN else memory.values.single() + it }
        }
        override fun close() { closed++ }
    }
}
