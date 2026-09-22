package org.mtgallium.agent.neural

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.mtgallium.agent.infoset.core.*
import kotlin.test.*

class PlayerPolicyBatchTest {
    @Test fun `large empty observation requests use bounded stack space`() {
        val model = BatchModel()
        val sessions = List(10_000) { PlayerPolicySession(model, PolicyMemoryOwner("game-$it", "p0")) }
        val saved = sessions.map { it.snapshot() }
        val states = List(sessions.size) { NeuralFixtures.state() }
        val task = FutureTask { PlayerPolicySession.observeBatch(sessions, states) }
        val thread = Thread(null, task, "large-observation-batch", 256 * 1024L)
        try {
            thread.start()
            assertEquals(List(sessions.size) { PolicyObservationReceipt(0, 0, 0) }, task.get(10, TimeUnit.SECONDS))
            assertEquals(saved, sessions.map { it.snapshot() })
            assertTrue(model.batchSizes.isEmpty())
        } finally {
            thread.join(5_000)
            sessions.forEach { it.close() }
        }
    }

    @Test fun `large observation requests are chunked to model capacity`() {
        val model = BatchModel()
        val sessions = List(257) { PlayerPolicySession(model, PolicyMemoryOwner("game-$it", "p0")) }
        val state = NeuralFixtures.state(listOf(NeuralFixtures.event(0)))
        try {
            val receipts = PlayerPolicySession.observeBatch(sessions, List(sessions.size) { state })
            assertEquals(List(85) { 3 } + 2, model.batchSizes)
            assertTrue(receipts.all { it.newlyDeliveredEvents == 1 })
            PlayerPolicySession(model, PolicyMemoryOwner("reference", "p0")).use { reference ->
                reference.observe(state)
                sessions.forEach {
                    assertEquals(state.historyCommitment, it.snapshot().history)
                    assertEquals(reference.snapshot().memory, it.snapshot().memory)
                }
            }
        } finally {
            sessions.forEach { it.close() }
        }
    }

    @Test fun `ragged suffixes and independently advanced cursors retain exact player histories`() {
        val model = BatchModel()
        val sessions = (0..3).map { PlayerPolicySession(model, PolicyMemoryOwner("game-$it", "p0")) }
        val states = sessions.indices.map { i -> NeuralFixtures.state(List(i + 1) { NeuralFixtures.event(it, i + it) }) }
        sessions[2].observe(NeuralFixtures.state(states[2].history.take(2)))
        val beforeCalls = model.batchSizes.size
        val receipts = PlayerPolicySession.observeBatch(sessions, states)
        assertEquals(listOf(1, 2, 1, 4), receipts.map { it.newlyDeliveredEvents })
        assertEquals(listOf(3, 1, 2, 1, 1), model.batchSizes.drop(beforeCalls))
        sessions.indices.forEach { i ->
            PlayerPolicySession(model, sessions[i].owner).use { reference ->
                reference.observe(states[i])
                assertEquals(reference.snapshot(), sessions[i].snapshot())
                assertEquals(reference.score(NeuralFixtures.site(states[i])), sessions[i].score(NeuralFixtures.site(states[i])))
            }
        }
        val saved = sessions.map { it.snapshot() }
        val before = model.batchSizes.size
        assertTrue(PlayerPolicySession.observeBatch(sessions.reversed(), states.reversed()).all { it.newlyDeliveredEvents == 0 })
        assertEquals(before, model.batchSizes.size)
        assertEquals(saved, sessions.map { it.snapshot() })
        sessions.forEach { it.close() }
    }

    @Test fun `later update and malformed output refuse without installing any lane`() {
        val model = BatchModel()
        val sessions = (0..3).map { PlayerPolicySession(model, PolicyMemoryOwner("game-$it", "p0")) }
        val states = sessions.indices.map { NeuralFixtures.state(listOf(NeuralFixtures.event(0, it))) }
        val saved = sessions.map { it.snapshot() }
        model.failCall = 2
        assertFailsWith<IllegalStateException> { PlayerPolicySession.observeBatch(sessions, states) }
        assertEquals(saved, sessions.map { it.snapshot() })
        model.failCall = null
        model.dropLane = true
        assertFailsWith<IllegalArgumentException> { PlayerPolicySession.observeBatch(sessions, states) }
        assertEquals(saved, sessions.map { it.snapshot() })
        model.dropLane = false
        PlayerPolicySession.observeBatch(sessions, states)
        assertTrue(sessions.all { it.snapshot().history.cursor == 1 })
        assertFailsWith<IllegalArgumentException> { PlayerPolicySession.observeBatch(listOf(sessions[0], sessions[0]), states.take(2)) }
        sessions.forEach { it.close() }
    }

    @Test fun `reversed concurrent requests cannot deadlock or consume an event twice`() {
        val model = BatchModel()
        val sessions = (0..1).map { PlayerPolicySession(model, PolicyMemoryOwner("concurrent-$it", "p0")) }
        val states = listOf(NeuralFixtures.state(listOf(NeuralFixtures.event(0))), NeuralFixtures.state(listOf(NeuralFixtures.event(0, 2))))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { PlayerPolicySession.observeBatch(sessions, states) })
            val b = pool.submit(Callable { PlayerPolicySession.observeBatch(sessions.reversed(), states.reversed()) })
            assertEquals(2, (a.get(5, TimeUnit.SECONDS) + b.get(5, TimeUnit.SECONDS)).sumOf { it.newlyDeliveredEvents })
            assertEquals(listOf(2), model.batchSizes)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            sessions.forEach { it.close() }
        }
    }

    @Test fun `caller list replacement cannot redirect a captured checkpoint to another owner`() {
        val model = BatchModel()
        val original = PlayerPolicySession(model, PolicyMemoryOwner("original", "p0"))
        val replacement = PlayerPolicySession(model, PolicyMemoryOwner("replacement", "p1"))
        val requested = mutableListOf(original)
        val states = mutableListOf(NeuralFixtures.state(listOf(NeuralFixtures.event(0))))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        model.beforeBatch = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val updating = pool.submit(Callable { PlayerPolicySession.observeBatch(requested, states) })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            requested[0] = replacement
            states[0] = NeuralFixtures.state(player = "p1")
            release.countDown()
            assertEquals(1, updating.get(5, TimeUnit.SECONDS).single().newlyDeliveredEvents)
            assertEquals(1, original.snapshot().history.cursor)
            assertEquals(0, replacement.snapshot().history.cursor)
            assertEquals(replacement.owner, replacement.snapshot().owner)
        } finally {
            release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            original.close(); replacement.close()
        }
    }

    private class BatchModel : SequencePolicyModel {
        override val identity = "batch-test-model"
        override val schema = FactualTensorSchema()
        override val architecture = NeuralArchitecture.GRU
        override val maximumBatchSize = 3
        val batchSizes = mutableListOf<Int>()
        var failCall: Int? = null
        var dropLane = false
        var beforeBatch: (() -> Unit)? = null
        override fun initialMemory() = NeuralMemoryTensors(listOf(0f))
        override fun validateMemory(memory: NeuralMemoryTensors) { require(memory.values.size == 1 && memory.historyMask.isEmpty()) }
        override fun advance(event: List<Int>, memory: NeuralMemoryTensors) = NeuralMemoryTensors(listOf(memory.values.single() * 0.5f + event.sum()))
        override fun advanceBatch(events: List<List<Int>?>, memories: List<NeuralMemoryTensors>): List<NeuralMemoryTensors> {
            beforeBatch?.invoke()
            batchSizes += events.size
            check(batchSizes.size != failCall) { "injected later batch failure" }
            val result = super.advanceBatch(events, memories)
            return if (dropLane) result.dropLast(1) else result
        }
        override fun score(input: FactualDecisionTensors, memory: NeuralMemoryTensors) = input.actions.indices.map { it + memory.values.single() }
        override fun close() = Unit
    }
}
