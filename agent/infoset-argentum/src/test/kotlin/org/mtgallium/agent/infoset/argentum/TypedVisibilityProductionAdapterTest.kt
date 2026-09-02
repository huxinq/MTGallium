package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.contract.sourceEntityIdOrNull
import com.wingedsheep.mtg.sets.definitions.ons.cards.FutureSight
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypedVisibilityProductionAdapterTest {
    @Test
    fun `top-library source crosses observation projection and semantic expansion`() {
        val registry = registry()
        val environment = environment(
            registry,
            Deck.of("Future Sight" to 1, "Mountain" to 19),
        )
        advanceToPrecombatMain(environment)
        val actor = environment.playerIds[0]
        val futureSight = environment.state.entities.entries.single { (_, container) ->
            container.get<CardComponent>()?.name == "Future Sight"
        }.key
        val state = move(environment.state, futureSight, ZoneKey(actor, Zone.BATTLEFIELD))
        val top = state.getLibrary(actor).first()
        environment.restore(state, environment.playerIds)

        val legal = environment.legalActions()
        val observation = ObservationBuilder(registry).build(state, actor, legal).observation
            as TrainingObservation
        val safe = SafeObservationProjector().project(observation).observation
        val library = safe.zones.single { it.ownerId == "p0" && it.zone == Zone.LIBRARY.name }
        val expansion = UnifiedSemanticExpander().expand(environment, proposalSeed = 701L)

        assertTrue(library.hidden)
        assertEquals(1, library.cards.size)
        assertEquals(state.getEntity(top)?.get<CardComponent>()?.name, library.cards.single().name)
        assertNotEquals(top.value, library.cards.single().objectRef)
        assertTrue(expansion.engineChoices.values.any { choice ->
            (choice as? ArgentumEngineChoice.Action)?.value?.sourceEntityIdOrNull() == top
        })
    }

    @Test
    fun `command-zone source crosses observation projection and semantic expansion`() {
        val registry = registry()
        val environment = environment(registry)
        advanceToPrecombatMain(environment)
        val actor = environment.playerIds[0]
        val commander = findCard(environment.state, actor, "Raging Goblin")
        val mountain = findCard(environment.state, actor, "Mountain")
        var state = move(environment.state, commander, ZoneKey(actor, Zone.COMMAND))
            .updateEntity(commander) { it.with(CommanderComponent(actor)) }
        state = move(state, mountain, ZoneKey(actor, Zone.BATTLEFIELD))
            .copy(format = Format.Commander())
        environment.restore(state, environment.playerIds)

        val legal = environment.legalActions()
        val observation = ObservationBuilder(registry).build(state, actor, legal).observation
            as TrainingObservation
        val command = SafeObservationProjector().project(observation).observation.zones.single {
            it.ownerId == "p0" && it.zone == Zone.COMMAND.name
        }
        val expansion = UnifiedSemanticExpander().expand(environment, proposalSeed = 702L)

        assertFalse(command.hidden)
        assertTrue(command.cards.any { it.name == "Raging Goblin" })
        assertTrue(expansion.engineChoices.values.any { choice ->
            (choice as? ArgentumEngineChoice.Action)?.value?.sourceEntityIdOrNull() == commander
        })
    }

    @Test
    fun `face-down and selective reveal survive the production safe projection without identity leak`() {
        val registry = registry()
        val environment = environment(registry)
        val actor = environment.playerIds[0]
        val opponent = environment.playerIds[1]
        val faceDown = environment.state.getHand(opponent).first()
        val revealed = environment.state.getHand(opponent)[1]
        val revealedName = environment.state.getEntity(revealed)!!.get<CardComponent>()!!.name
        var state = move(environment.state, faceDown, ZoneKey(opponent, Zone.BATTLEFIELD))
            .updateEntity(faceDown) { it.with(FaceDownComponent) }
        state = state.updateEntity(revealed) { it.with(RevealedToComponent.to(actor)) }

        val observation = ObservationBuilder(registry).build(state, actor, emptyList()).observation
            as TrainingObservation
        val safe = SafeObservationProjector().project(observation).observation
        val masked = safe.zones.single {
            it.ownerId == "p1" && it.zone == Zone.BATTLEFIELD.name
        }.cards.single { it.faceDown }
        val hand = safe.zones.single { it.ownerId == "p1" && it.zone == Zone.HAND.name }

        assertEquals("Face-down creature", masked.name)
        assertNull(masked.definitionId)
        assertEquals("", masked.oracleText)
        assertNotEquals(faceDown.value, masked.objectRef)
        assertTrue(hand.hidden)
        assertEquals(listOf(revealedName), hand.cards.map { it.name })
        assertNotEquals(revealed.value, hand.cards.single().objectRef)
    }

    @Test
    fun `unadmitted action source is refused at both gym and semantic adapter boundaries`() {
        val registry = registry()
        val environment = environment(registry)
        val actor = environment.playerIds[0]
        val hidden = environment.state.getHand(environment.playerIds[1]).first()
        val malicious = LegalAction(
            action = PlayLand(actor, hidden),
            actionType = "PlayLand",
            description = "Play hidden source",
        )
        val legalWithMalicious = environment.legalActions() + malicious
        val built = ObservationBuilder(registry).build(environment.state, actor, legalWithMalicious)
        val observation = built.observation as TrainingObservation
        val projection = SafeObservationProjector().project(observation)
        val expansion = UnifiedSemanticExpander().expandPrepared(
            environment = environment,
            proposalSeed = 703L,
            responseLimit = 2_048,
            preparedInput = PreparedSemanticExpansionInput(
                actor = actor,
                legalActions = legalWithMalicious,
                observation = observation,
                projection = projection,
            ),
        )

        assertFalse(observation.legalActions.any { it.sourceEntityId == hidden })
        assertTrue(expansion.rejectedCandidates >= 1)
        assertFalse(expansion.engineChoices.values.any { choice ->
            (choice as? ArgentumEngineChoice.Action)?.value?.sourceEntityIdOrNull() == hidden
        })
    }

    private fun registry(): CardRegistry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(FutureSight)
    }

    private fun environment(
        registry: CardRegistry,
        firstDeck: Deck = Deck.of("Mountain" to 17, "Raging Goblin" to 3),
    ): GameEnvironment = GameEnvironment.create(registry).also { environment ->
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", firstDeck),
                    PlayerConfig("Bob", Deck.of("Mountain" to 17, "Raging Goblin" to 3)),
                ),
                seed = 613L,
                skipMulligans = true,
                startingPlayerIndex = 0,
            )
        )
    }

    private fun findCard(state: GameState, owner: EntityId, name: String): EntityId =
        (state.getHand(owner) + state.getLibrary(owner)).first { id ->
            state.getEntity(id)?.get<CardComponent>()?.name == name
        }

    private fun move(state: GameState, id: EntityId, destination: ZoneKey): GameState {
        val source = state.zones.entries.single { (_, ids) -> id in ids }.key
        return state.removeFromZone(source, id).addToZone(destination, id)
    }

    private fun advanceToPrecombatMain(environment: GameEnvironment) {
        repeat(32) {
            if (
                environment.state.step == Step.PRECOMBAT_MAIN &&
                environment.state.priorityPlayerId == environment.playerIds[0]
            ) return
            environment.stepRaw(environment.legalActions().first { it.action is PassPriority }.action)
        }
        error("Did not reach the starting player's precombat main phase")
    }
}
