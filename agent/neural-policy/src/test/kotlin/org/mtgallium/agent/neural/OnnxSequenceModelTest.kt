package org.mtgallium.agent.neural

import kotlin.test.*

class OnnxSequenceModelTest {
    private val graph = checkNotNull(javaClass.getResourceAsStream("/sum-actions.onnx")).use { it.readBytes() }
    private val input = FactualDecisionTensors(listOf(1), listOf(listOf(2, 3), listOf(7)), true, true)

    @Test fun `single lane model loads from graph bytes without training metadata`() {
        val descriptor = """{"config":{"architecture":"CURRENT_VIEW"},"graphs":{"scoreSingle":"sum"}}"""
        val model = OnnxSequenceModel.load(descriptor.toByteArray()) { name ->
            assertEquals("sum", name)
            graph
        }
        model.use {
            assertEquals(listOf(5f, 7f), it.score(input, it.initialMemory()))
            assertEquals(listOf(5f), it.score(input.copy(actions = input.actions.take(1)), it.initialMemory()))
        }
        assertFailsWith<IllegalStateException> { model.score(input, model.initialMemory()) }
    }

    @Test fun `one graph serves single and ragged batch roles`() {
        val descriptor = """{"config":{"architecture":"CURRENT_VIEW"},"maximumBatch":3,
            "graphs":{"scoreSingle":"sum","scoreBatch":"sum"}}"""
        var reads = 0
        OnnxSequenceModel.load(descriptor.toByteArray()) { reads++; graph }.use { model ->
            val inputs = listOf(input, input.copy(actions = listOf(listOf(11))), input)
            val memory = model.initialMemory()
            assertEquals(listOf(listOf(5f, 7f), listOf(11f), listOf(5f, 7f)),
                model.scoreBatch(inputs, List(3) { memory }))
            assertEquals(1, reads)
            assertFailsWith<IllegalArgumentException> {
                model.scoreBatch(inputs + input, List(4) { memory })
            }
        }
    }

    @Test fun `missing batch graph fails before loading native sessions`() {
        val descriptor = """{"config":{"architecture":"CURRENT_VIEW"},"maximumBatch":2,
            "graphs":{"scoreSingle":"sum"}}"""
        assertFailsWith<IllegalArgumentException> {
            OnnxSequenceModel.load(descriptor.toByteArray()) { error("No graph should be loaded") }
        }
    }

    @Test fun `scalar observation works with capacity 256`() = observeWithCapacity(256)

    @Test fun `scalar observation works with capacity 257`() = observeWithCapacity(257)

    private fun observeWithCapacity(capacity: Int) {
        val descriptor = """{"config":{"architecture":"CURRENT_VIEW"},"maximumBatch":$capacity,
            "graphs":{"scoreSingle":"sum","scoreBatch":"sum"}}"""
        OnnxSequenceModel.load(descriptor.toByteArray()) { graph }.use { model ->
            assertEquals(listOf(5f, 7f), model.score(input, model.initialMemory()))
            PlayerPolicySession(model, PolicyMemoryOwner("capacity-$capacity", "p0")).use { session ->
                val state = NeuralFixtures.state(listOf(NeuralFixtures.event(0)))
                assertEquals(0, session.snapshot().history.cursor)
                assertEquals(1, session.observe(state).newlyDeliveredEvents)
                assertEquals(state.historyCommitment, session.snapshot().history)
                assertEquals(1, session.snapshot().history.cursor)
            }
        }
    }
}
