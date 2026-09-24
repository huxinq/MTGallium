package org.mtgallium.agent.argentum.policy

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.GameRng
import kotlin.test.*
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.SearchWorld

/** Conditioned resampling copies a hypothesis without also copying its future draws and shuffles. */
class ConditionedFreshChanceTest {
    private fun world(): ArgentumSearchWorld {
        val registry = CardRegistry().apply {
            register(CardDefinition(name = "Chance Test Bear", manaCost = ManaCost.parse("{0}"),
                typeLine = TypeLine.parse("Creature — Bear"), creatureStats = CreatureStats(1, 1)))
        }
        val environment = GameEnvironment.create(registry)
        val deck = Deck.of("Chance Test Bear" to 40)
        environment.reset(GameConfig(players = listOf(PlayerConfig("Alice", deck), PlayerConfig("Bob", deck)),
            seed = 5150L, startingPlayerIndex = 0, skipMulligans = true, useHandSmoother = false))
        return ArgentumSearchWorld.create(environment, "fresh-chance-test", 5150L, 5150L,
            knownDecks = mapOf("p0" to mapOf("Chance Test Bear" to 40), "p1" to mapOf("Chance Test Bear" to 40)))
    }

    private fun SearchWorld.state() = (this as ArgentumSearchWorld).authoritativeStateForHost()

    @Test fun `a resampled duplicate keeps its hidden state and information but draws a fresh chance stream`() {
        val source = world()
        val first = FRESH_CHANCE_COPY.rejuvenate(source, 1, 11L)
        val second = FRESH_CHANCE_COPY.rejuvenate(source, 2, 12L)
        val rngs = listOf(source.state().rng, first.state().rng, second.state().rng)
        assertEquals(rngs.size, rngs.distinct().size)
        assertEquals(first.state().rng, FRESH_CHANCE_COPY.rejuvenate(source, 1, 11L).state().rng)
        val unseeded = GameRng(0L)
        for (copy in listOf(first, second)) {
            assertEquals(source.state().copy(rng = unseeded), copy.state().copy(rng = unseeded))
            for (viewer in listOf("p0", "p1")) {
                assertEquals(source.informationState(viewer), copy.informationState(viewer))
            }
        }
        // Copying must not advance or reseed the source hypothesis.
        assertEquals(rngs.first(), source.state().rng)
    }

    @Test fun `conditioned maintenance is a new behavior identity`() {
        assertEquals(CONDITIONED_BELIEF_MAINTENANCE_V3, conditionedBeliefMaintenanceIdentity(BeliefMode.POLICY_CONDITIONED_V1))
        assertNotEquals(CONDITIONED_BELIEF_MAINTENANCE_V2, CONDITIONED_BELIEF_MAINTENANCE_V3)
        assertNull(conditionedBeliefMaintenanceIdentity(BeliefMode.CONSISTENCY_ONLY_V1))
    }
}
