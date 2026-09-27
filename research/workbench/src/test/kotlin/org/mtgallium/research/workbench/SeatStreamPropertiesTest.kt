package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.*
import com.wingedsheep.engine.state.components.identity.*
import com.wingedsheep.engine.state.components.battlefield.NotedCreatureTypesComponent
import com.wingedsheep.engine.handlers.effects.FaceDownTurnUp
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.FaceDownMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import com.wingedsheep.engine.view.ClientEventTransformer

/** Audience properties shared by face-down, secret-choice and resolution scenarios. */
class SeatStreamPropertiesTest {
    private val registry = CardRegistry().apply { register(PortalSet.cards); register(PortalSet.basicLands) }
    private val env = GameEnvironment.create(registry).apply {
        reset(GameConfig(players = listOf(
            PlayerConfig("A", Deck.of("Mountain" to 10, "Raging Goblin" to 10)),
            PlayerConfig("B", Deck.of("Mountain" to 10, "Raging Goblin" to 10))),
            seed = 77L, skipMulligans = true, startingPlayerIndex = 0))
    }
    private val owner = env.playerIds[0]
    private val viewer = env.playerIds[1]
    private val source = env.state.getHand(owner).first()
    private fun message(state: GameState, seat: EntityId): ServerMessage.StateUpdate {
        val session = GameSession(cardRegistry = registry)
        session.injectStateForTesting(state, emptyMap())
        return (session.createStateUpdate(seat, emptyList(), true) as ServerMessage.StateUpdate)
            .copy(interactionEpoch = null, stateVersion = 0)
    }

    /** Opt-in desired contract; fails at the pinned engine revision and retains the counterexample. */
    @Test fun hiddenLibraryOrderMustNotChangeStream() {
        assumeTrue(System.getenv("SEAT_STREAM_REQUIRE_PRIVATE") == "1")
        val state = env.state
        val key = ZoneKey(owner, Zone.LIBRARY)
        val changed = state.copy(zones = state.zones + (key to state.getLibrary(owner).reversed()))
        assertNotEquals(state, changed)
        assertEquals(message(state, viewer), message(changed, viewer))
    }

    @Test fun hiddenDrawSlotMustNotChangeOpponentEvent() {
        assumeTrue(System.getenv("SEAT_STREAM_REQUIRE_PRIVATE") == "1")
        val slots = env.state.getLibrary(owner).take(2)
        val a = listOf(CardsDrawnEvent(owner, 1, listOf(slots[0]), listOf("Mountain")))
        val b = listOf(CardsDrawnEvent(owner, 1, listOf(slots[1]), listOf("Mountain")))
        assertEquals(ClientEventTransformer.transform(a, viewer), ClientEventTransformer.transform(b, viewer))
    }

    @Test fun revealEventInformsTheCardOwner() {
        val hand = env.state.getHand(owner)
        val events = listOf(HandRevealedEvent(owner, hand))
        assertTrue(ClientEventTransformer.transform(events, owner).isNotEmpty())
        assertTrue(ClientEventTransformer.transform(events, viewer).isNotEmpty())
    }

    @Test fun hiddenDrawIdentityNamesAreMasked() {
        val a = listOf(CardsDrawnEvent(owner, 1, listOf(source), listOf("Mountain")))
        val b = listOf(CardsDrawnEvent(owner, 1, listOf(source), listOf("Raging Goblin")))
        assertEquals(ClientEventTransformer.transform(a, viewer), ClientEventTransformer.transform(b, viewer))
        assertNotEquals(ClientEventTransformer.transform(a, owner), ClientEventTransformer.transform(b, owner))
    }

    @Test fun allocationOrderCanIdentifyUnrevealedLibraryCards() {
        // A second initialization uses the public ordered deck input, but a different shuffle seed.
        val known = GameEnvironment.create(registry).apply {
            reset(GameConfig(players = listOf(
                PlayerConfig("A", Deck.of("Mountain" to 10, "Raging Goblin" to 10)),
                PlayerConfig("B", Deck.of("Mountain" to 10, "Raging Goblin" to 10))),
                seed = 88L, skipMulligans = true, startingPlayerIndex = 0))
        }
        val inferred = known.state.entities.mapNotNull { (id, components) ->
            components.get<CardComponent>()?.let { id to it.name }
        }.toMap()
        val delivered = message(env.state, viewer).state
        val ids = delivered.zones.single { it.zoneId == ZoneKey(owner, Zone.LIBRARY) }.cardIds
        assertTrue(ids.isNotEmpty())
        for (id in ids) {
            assertTrue(id !in delivered.cards, "Identity should be masked")
            assertEquals(env.state.getEntity(id)!!.get<CardComponent>()!!.name, inferred[id])
        }
        println("SEAT_STREAM_ID_RECOVERY\t${ids.size}\t${ids.size}")
    }

    @Test fun manifestedIdentityIsPrivateAfterRebuildingComponents() {
        val state = env.state.moveToZone(source, ZoneKey(owner, Zone.HAND), ZoneKey(owner, Zone.BATTLEFIELD))
        fun replace(name: String): GameState {
            val definition = registry.requireCard(name)
            var container = CardEntityFactory.create(definition, owner)
                .with(FaceDownComponent).with(FaceDownModeComponent(FaceDownMode.MANIFEST))
            FaceDownTurnUp.dataFor(definition, name, FaceDownMode.MANIFEST)?.let { container = container.with(it) }
            return state.updateEntity(source) { container }
        }
        val a = replace("Mountain")
        val b = replace("Raging Goblin")
        assertNotEquals(message(a, owner), message(b, owner), "Owner sensitivity control")
        assertEquals(message(a, viewer), message(b, viewer), "Opponent cannot distinguish manifested identities")
    }

    @Test fun notedChoiceIsPrivateToItsChooser() {
        val state = env.state.moveToZone(source, ZoneKey(owner, Zone.HAND), ZoneKey(owner, Zone.BATTLEFIELD))
        val a = state.updateEntity(source) { it.with(NotedCreatureTypesComponent(setOf("Elf"), owner)) }
        val b = state.updateEntity(source) { it.with(NotedCreatureTypesComponent(setOf("Goblin"), owner)) }
        assertNotEquals(message(a, owner), message(b, owner))
        assertEquals(message(a, viewer), message(b, viewer))
    }

    @Test fun secretBidIsPrivateButDecisionContextIsVisible() {
        fun paused(number: Int, name: String = "Public source"): GameState = env.state.suspendForDecision(
            question = { id -> ChooseNumberDecision(id, viewer, "Choose a number",
                DecisionContext(sourceId = source, sourceName = name), 0, 20) },
            answer = SecretBidContinuation(source, "Public source", owner, viewer, emptyList(),
                mapOf(owner to number), null, null, null)).state
        assertEquals(message(paused(1), viewer), message(paused(9), viewer))
        assertNotEquals(message(paused(1), viewer), message(paused(1, "Other public source"), viewer))
    }
}
