package org.mtgallium.agent.argentum.policy

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.Deck
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.neural.*
import kotlin.test.*

class ArgentumNeuralPolicyHostTest {
    @Test fun `nine score-requiring hosts batch like scalar hosts at model capacity sixteen`() {
        val model = CapacitySixteenModel()
        val scalar = (0 until 9).map { host(model, "scalar-$it") }
        val batched = (0 until 9).map { host(model, "batched-$it") }
        try {
            val expected = scalar.map { it.step() }
            val actual = ArgentumNeuralPolicyHost.stepBatch(batched).lanes.map {
                (it as NeuralHostLaneResult.Transition).value
            }
            assertEquals(listOf(9), model.scoredBatchSizes)
            expected.zip(actual).forEachIndexed { index, (single, batch) ->
                assertFalse(single.decision.rulesForced, "scalar lane $index must require a score")
                assertFalse(batch.decision.rulesForced, "batch lane $index must require a score")
                assertEquals(single.decision.selectedIndex, batch.decision.selectedIndex)
                assertEquals(single.decision.site.expansion.candidates[single.decision.selectedIndex].signature,
                    batch.decision.site.expansion.candidates[batch.decision.selectedIndex].signature)
                assertEquals(single.decision.scores, batch.decision.scores)
                assertEquals(single.result, batch.result)
                assertTrue(batch.result.accepted)
                for (player in listOf("p0", "p1")) {
                    val scalarMemory = scalar[index].memory(player)
                    val batchMemory = batched[index].memory(player)
                    assertEquals(scalarMemory.history.cursor, batchMemory.history.cursor)
                    assertEquals(scalarMemory.memory, batchMemory.memory)
                }
            }
        } finally {
            scalar.forEach { it.close() }
            batched.forEach { it.close() }
        }
    }

    private fun host(model: SequencePolicyModel, game: String): ArgentumNeuralPolicyHost {
        val registry = CardRegistry().apply {
            register(CardDefinition(name = "Batch Test Bear", manaCost = ManaCost.parse("{0}"),
                typeLine = TypeLine.parse("Creature — Bear"), creatureStats = CreatureStats(1, 1)))
        }
        val deck = Deck.of("Batch Test Bear" to 40)
        val environment = GameEnvironment.create(registry).also {
            it.reset(GameConfig(players = listOf(PlayerConfig("Alice", deck), PlayerConfig("Bob", deck)),
                seed = 42613L, startingPlayerIndex = 0, skipMulligans = true, useHandSmoother = false))
        }
        val world = ArgentumSearchWorld.create(environment, game, 42613L, 42613L,
            knownDecks = mapOf("p0" to mapOf("Batch Test Bear" to 40), "p1" to mapOf("Batch Test Bear" to 40)))
        repeat(32) {
            if (world.authoritativeStateForHost().step == Step.PRECOMBAT_MAIN) {
                return ArgentumNeuralPolicyHost(world, game, listOf("p0", "p1"), model)
            }
            assertTrue(world.applyObservedAction(PassPriority(requireNotNull(world.authoritativeStateForHost().priorityPlayerId))).result.accepted)
        }
        error("Authored game did not reach a score-requiring first main phase")
    }

    private class CapacitySixteenModel : SequencePolicyModel {
        override val identity = "capacity-sixteen-test-model"
        override val schema = FactualTensorSchema()
        override val architecture = NeuralArchitecture.GRU
        override val maximumBatchSize = 16
        val scoredBatchSizes = mutableListOf<Int>()
        override fun initialMemory() = NeuralMemoryTensors(listOf(0f))
        override fun validateMemory(memory: NeuralMemoryTensors) = require(memory.values.size == 1)
        override fun advance(event: List<Int>, memory: NeuralMemoryTensors) =
            NeuralMemoryTensors(listOf(memory.values.single() + event.sum()))
        override fun score(input: FactualDecisionTensors, memory: NeuralMemoryTensors) =
            input.actions.indices.map { memory.values.single() + it }
        override fun scoreBatch(inputs: List<FactualDecisionTensors>, memories: List<NeuralMemoryTensors>): List<List<Float>> {
            scoredBatchSizes += inputs.size
            return super.scoreBatch(inputs, memories)
        }
        override fun close() = Unit
    }
}
