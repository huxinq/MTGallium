package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.LookedAtCardsEvent
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SpellCastEvent
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

class ArgentumBeliefLifecycleTest {
    private val deck = mapOf("Mountain" to 10, "Raging Goblin" to 10)
    private val knownDecks = mapOf("p0" to deck, "p1" to deck)

    @Test
    fun `a second accepted mulligan can rebuild a complete remembered-fact-consistent belief`() {
        val fixture = fixture(seed = 1_106L, skipMulligans = false)
        val world = fixture.world

        repeat(2) { mulliganIndex ->
            val takeMulligan = world.expandChoices().candidates.single { choice ->
                (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value is TakeMulligan
            }
            val step = world.step(takeMulligan)

            assertTrue(step.accepted, "mulligan $mulliganIndex: ${step.diagnostic}")
            val expected = world.informationState("p0")
            assertNull(
                world.knowledgeSupportFailure("p0", expected),
                "accepted mulligan $mulliganIndex left contradictory remembered facts",
            )
        }

        val expected = world.informationState("p0")
        val rebuilt = ArgentumKnownDeckBeliefWorldSource(world, fixture.registry).sample(
            expected,
            knownDecks,
            beliefSeed = 41_106L,
            count = 8,
        )

        assertEquals(8, rebuilt.particles.size)
        assertTrue(ArgentumBeliefSupport.completeFailures(rebuilt.particles.map { it.value }, "p0", expected).isEmpty())
    }

    @Test
    fun `belief construction refusal exposes only stable redacted support codes`() {
        val failure = ArgentumBeliefSupportException(
            viewerAlias = "p0",
            requestedParticles = 8,
            acceptedParticles = 0,
            attempts = 4_096,
            failureCounts = mapOf(
                "KnowledgeSupport:KNOWN_OBJECT_BINDING_MISSING" to 4_095,
                "RawUnsupportedReasonWithSecretName" to 1,
            ),
        )

        assertEquals(
            listOf("ENGINE_SAMPLER_REJECTED", "KNOWN_OBJECT_BINDING_MISSING"),
            failure.reasonCodes,
        )
        assertEquals(4_095, failure.reasonCounts.getValue("KNOWN_OBJECT_BINDING_MISSING"))
        assertFalse("RawUnsupportedReasonWithSecretName" in failure.message.orEmpty())
    }

    @Test
    fun `hand reveal survives rebuild and refresh across sampled hidden identities`() {
        val fixture = fixture(seed = 1_101L)
        val viewer = fixture.environment.playerIds[0]
        val opponent = fixture.environment.playerIds[1]
        val state = fixture.environment.state
        val revealed = state.getHand(opponent).first()
        val revealedName = cardName(state, revealed)
        val alternatives = (state.getHand(opponent) + state.getLibrary(opponent)).filter { id ->
            id != revealed && cardName(state, id) != revealedName
        }
        assertTrue(alternatives.size >= 2)

        val history = PerspectiveHistory(fixture.environment.playerIds)
        history.recordEngineEvents(
            engineEvents = listOf(HandLookedAtEvent(viewer, opponent, listOf(revealed))),
            actorViewer = viewer,
            beforeState = state,
            afterState = state,
            before = projections(state, fixture.environment.playerIds, fixture.registry),
            after = projections(state, fixture.environment.playerIds, fixture.registry),
        )
        val world = fixture.world.withRememberedHistoryForVerification(history)
        val expected = world.informationState("p0")
        assertTrue(expected.knowledge.knownObjects.any { it.cardName == revealedName && it.zone == "HAND" })

        alternatives.take(4).forEach { alternative ->
            val contradictory = world.withSampledState(
                swapCardIdentities(state, revealed, alternative),
                futureChanceStreamIdentity = 11_101L,
            )
            assertNotNull(contradictory.knowledgeSupportFailure("p0", expected))
        }
        verifyRebuildAndRefresh(world, fixture.registry, "p0", "hand reveal")
    }

    @Test
    fun `a completely remembered hidden hand is pinned during rebuild and rejuvenation`() {
        val uniqueDeck = PortalSet.cards.asSequence()
            .map { it.name }
            .distinct()
            .take(20)
            .associateWith { 1 }
        assertEquals(20, uniqueDeck.size)
        val registry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of(*uniqueDeck.entries.map { it.key to it.value }.toTypedArray())),
                        PlayerConfig("Bob", Deck.of(*uniqueDeck.entries.map { it.key to it.value }.toTypedArray())),
                    ),
                    seed = 1_107L,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                )
            )
        }
        val viewer = environment.playerIds[0]
        val opponent = environment.playerIds[1]
        val rememberedHand = environment.state.getHand(opponent)
        val history = PerspectiveHistory(environment.playerIds)
        history.recordEngineEvents(
            engineEvents = listOf(HandLookedAtEvent(viewer, opponent, rememberedHand)),
            actorViewer = viewer,
            beforeState = environment.state,
            afterState = environment.state,
            before = projections(environment.state, environment.playerIds, registry),
            after = projections(environment.state, environment.playerIds, registry),
        )
        val decks = mapOf("p0" to uniqueDeck, "p1" to uniqueDeck)
        val world = ArgentumSearchWorld.create(
            environment,
            "belief-complete-hand",
            1_107L,
            cardRegistry = registry,
            effectiveSetupSeed = 1_107L,
            knownDecks = decks,
        ).withRememberedHistoryForVerification(history)
        val expected = world.informationState("p0")
        val rememberedNames = rememberedHand.associateWith { cardName(environment.state, it) }

        val rebuilt = ArgentumKnownDeckBeliefWorldSource(world, registry).sample(
            expected,
            decks,
            beliefSeed = 41_107L,
            count = 4,
        )
        rebuilt.particles.forEach { weighted ->
            val sampled = assertIs<ArgentumSearchWorld>(weighted.value)
            rememberedNames.forEach { (objectId, cardName) ->
                assertEquals(cardName, this.cardName(sampled.authoritativeState(), objectId))
                assertEquals(
                    environment.state.getEntity(objectId)?.get<RevealedToComponent>(),
                    sampled.authoritativeState().getEntity(objectId)?.get<RevealedToComponent>(),
                )
            }
            assertNull(sampled.knowledgeSupportFailure("p0", expected))
        }

        val rejuvenated = assertIs<ArgentumSearchWorld>(
            ArgentumConditionalRejuvenator(registry, decks, "p0").rejuvenate(
                rebuilt.particles.first().value,
                duplicateIndex = 1,
                seed = 51_107L,
            )
        )
        rememberedNames.forEach { (objectId, cardName) ->
            assertEquals(cardName, this.cardName(rejuvenated.authoritativeState(), objectId))
            assertEquals(
                environment.state.getEntity(objectId)?.get<RevealedToComponent>(),
                rejuvenated.authoritativeState().getEntity(objectId)?.get<RevealedToComponent>(),
            )
        }
        assertNull(rejuvenated.knowledgeSupportFailure("p0", expected))
    }

    @Test
    fun `known library prefix survives rebuild and refresh across sampled orders`() {
        val fixture = fixture(seed = 1_102L)
        val viewer = fixture.environment.playerIds[0]
        val state = fixture.environment.state
        val library = state.getLibrary(viewer)
        val knownTop = library.take(3)
        val alternatives = library.drop(3).filter { id ->
            cardName(state, id) != cardName(state, knownTop.first())
        }
        assertTrue(alternatives.isNotEmpty())

        val history = PerspectiveHistory(fixture.environment.playerIds)
        history.recordEngineEvents(
            engineEvents = listOf(LookedAtCardsEvent(viewer, knownTop, "belief property known top")),
            actorViewer = viewer,
            beforeState = state,
            afterState = state,
            before = projections(state, fixture.environment.playerIds, fixture.registry),
            after = projections(state, fixture.environment.playerIds, fixture.registry),
        )
        val world = fixture.world.withRememberedHistoryForVerification(history)
        val expected = world.informationState("p0")
        assertEquals(knownTop.map { cardName(state, it) }, expected.knowledge.knownLibraryOrders
            .single { it.playerId == "p0" }.top)

        alternatives.take(4).forEach { alternative ->
            val reordered = library.toMutableList().also { ids ->
                val alternativeIndex = ids.indexOf(alternative)
                ids[alternativeIndex] = ids.first()
                ids[0] = alternative
            }
            val contradictoryState = state.copy(
                zones = state.zones + (ZoneKey(viewer, Zone.LIBRARY) to reordered),
            )
            val contradictory = world.withSampledState(
                contradictoryState,
                futureChanceStreamIdentity = 11_102L,
            )
            assertEquals("LIBRARY_ORDER_MISMATCH", contradictory.knowledgeSupportFailure("p0", expected))
        }
        verifyRebuildAndRefresh(world, fixture.registry, "p0", "library-order reveal")
    }

    @Test
    fun `revealed object continuity survives rebuild and refresh after a zone change`() {
        val fixture = fixture(seed = 1_103L)
        val viewer = fixture.environment.playerIds[0]
        val opponent = fixture.environment.playerIds[1]
        val beforeState = fixture.environment.state
        val revealed = beforeState.getHand(opponent).first()
        val revealedName = cardName(beforeState, revealed)
        val replacement = (beforeState.getHand(opponent) + beforeState.getLibrary(opponent)).first { id ->
            id != revealed && cardName(beforeState, id) == revealedName
        }
        val replacementZone = if (replacement in beforeState.getHand(opponent)) Zone.HAND else Zone.LIBRARY
        val afterState = beforeState.copy(
            zones = beforeState.zones +
                (ZoneKey(opponent, Zone.HAND) to beforeState.getHand(opponent).filterNot { it == revealed }) +
                (ZoneKey(opponent, Zone.GRAVEYARD) to (beforeState.getGraveyard(opponent) + revealed)),
        )

        val history = PerspectiveHistory(fixture.environment.playerIds)
        history.recordEngineEvents(
            engineEvents = listOf(HandLookedAtEvent(viewer, opponent, listOf(revealed))),
            actorViewer = viewer,
            beforeState = beforeState,
            afterState = beforeState,
            before = projections(beforeState, fixture.environment.playerIds, fixture.registry),
            after = projections(beforeState, fixture.environment.playerIds, fixture.registry),
        )
        history.recordEngineEvents(
            engineEvents = listOf(
                ZoneChangeEvent(
                    entityId = revealed,
                    entityName = revealedName,
                    fromZone = Zone.HAND,
                    toZone = Zone.GRAVEYARD,
                    ownerId = opponent,
                )
            ),
            actorViewer = viewer,
            beforeState = beforeState,
            afterState = afterState,
            before = projections(beforeState, fixture.environment.playerIds, fixture.registry),
            after = projections(afterState, fixture.environment.playerIds, fixture.registry),
        )
        fixture.environment.restore(afterState, fixture.environment.playerIds, fixture.environment.stepCount)
        val world = ArgentumSearchWorld.create(
            fixture.environment,
            "belief-lifecycle-object",
            77L,
            cardRegistry = fixture.registry,
            effectiveSetupSeed = 1_103L,
            knownDecks = knownDecks,
        ).withRememberedHistoryForVerification(history)
        val expected = world.informationState("p0")
        assertTrue(expected.knowledge.knownObjects.any {
            it.cardName == revealedName && it.zone == "GRAVEYARD"
        })

        val contradictoryZones = afterState.zones +
            (ZoneKey(opponent, Zone.GRAVEYARD) to afterState.getGraveyard(opponent).map {
                if (it == revealed) replacement else it
            }) +
            (ZoneKey(opponent, replacementZone) to zoneIds(afterState, opponent, replacementZone).map {
                if (it == replacement) revealed else it
            })
        val contradictory = world.withSampledState(
            afterState.copy(zones = contradictoryZones),
            futureChanceStreamIdentity = 11_103L,
        )
        assertEquals("KNOWN_OBJECT_ZONE_MISMATCH", contradictory.knowledgeSupportFailure("p0", expected))
        verifyRebuildAndRefresh(world, fixture.registry, "p0", "revealed-object continuity")
    }

    @Test
    fun `a cast event moves its known card onto the separately stored stack`() {
        val fixture = fixture(seed = 1_105L)
        val viewer = fixture.environment.playerIds[0]
        val beforeState = fixture.environment.state
        val spell = beforeState.getHand(viewer).first()
        val spellName = cardName(beforeState, spell)
        val afterState = beforeState.copy(
            zones = beforeState.zones +
                (ZoneKey(viewer, Zone.HAND) to beforeState.getHand(viewer).filterNot { it == spell }),
            stack = beforeState.stack + spell,
        )

        val history = PerspectiveHistory(fixture.environment.playerIds)
        history.recordEngineEvents(
            engineEvents = listOf(
                SpellCastEvent(
                    spellEntityId = spell,
                    cardName = spellName,
                    casterId = viewer,
                    castFromZone = Zone.HAND,
                )
            ),
            actorViewer = viewer,
            beforeState = beforeState,
            afterState = afterState,
            before = projections(beforeState, fixture.environment.playerIds, fixture.registry),
            after = projections(afterState, fixture.environment.playerIds, fixture.registry),
        )
        fixture.environment.restore(afterState, fixture.environment.playerIds, fixture.environment.stepCount)
        val world = ArgentumSearchWorld.create(
            fixture.environment,
            "belief-lifecycle-stack",
            78L,
            cardRegistry = fixture.registry,
            effectiveSetupSeed = 1_105L,
            knownDecks = knownDecks,
        ).withRememberedHistoryForVerification(history)
        val expected = world.informationState("p0")

        assertTrue(expected.knowledge.knownObjects.any {
            it.cardName == spellName && it.zone == "STACK"
        })
        assertNull(world.knowledgeSupportFailure("p0", expected))
        verifyRebuildAndRefresh(world, fixture.registry, "p0", "known stack object")
    }

    @Test
    fun `private choice survives entitled refresh without entering the opponents knowledge`() {
        val fixture = fixture(seed = 1_104L, skipMulligans = false)
        val actor = fixture.environment.playerIds[0]
        val firstHistory = PerspectiveHistory(fixture.environment.playerIds)
        val secondHistory = PerspectiveHistory(fixture.environment.playerIds)
        firstHistory.recordChoice(
            actor,
            privateChoice("bottom-mountain", "Mountain"),
            privateToActor = true,
            kind = org.mtgallium.agent.infoset.core.PolicyHistoryEventKind.MULLIGAN,
        )
        secondHistory.recordChoice(
            actor,
            privateChoice("bottom-goblin", "Raging Goblin"),
            privateToActor = true,
            kind = org.mtgallium.agent.infoset.core.PolicyHistoryEventKind.MULLIGAN,
        )
        val firstWorld = fixture.world.withRememberedHistoryForVerification(firstHistory)
        val secondWorld = fixture.world.withRememberedHistoryForVerification(secondHistory)

        assertEquals(
            "SAFE_HISTORY_MISMATCH",
            secondWorld.knowledgeSupportFailure("p0", firstWorld.informationState("p0")),
        )
        assertNull(secondWorld.knowledgeSupportFailure("p1", firstWorld.informationState("p1")))
        verifyRebuildAndRefresh(firstWorld, fixture.registry, "p0", "entitled private choice")
        verifyRebuildAndRefresh(firstWorld, fixture.registry, "p1", "redacted private choice")
    }

    private fun verifyRebuildAndRefresh(
        world: ArgentumSearchWorld,
        registry: CardRegistry,
        viewer: String,
        property: String,
    ) {
        val expected = world.informationState(viewer)
        val rejuvenator = ArgentumConditionalRejuvenator(registry, knownDecks, viewer)
        repeat(PROPERTY_TRIALS) { trial ->
            val rebuilt = ArgentumKnownDeckBeliefWorldSource(world, registry).sample(
                expected,
                knownDecks,
                beliefSeed = 20_000L + trial,
                count = PARTICLES,
            )
            rebuilt.particles.forEachIndexed { particleIndex, weighted ->
                val rebuiltWorld = assertIs<ArgentumSearchWorld>(weighted.value)
                assertTrue(
                    ArgentumBeliefSupport.completeFailures(
                        listOf(rebuiltWorld),
                        viewer,
                        expected,
                    ).isEmpty(),
                    "$property rebuild trial=$trial particle=$particleIndex",
                )
                val refreshed = assertIs<ArgentumSearchWorld>(
                    rejuvenator.rejuvenate(
                        rebuiltWorld,
                        duplicateIndex = 1,
                        seed = 30_000L + trial * PARTICLES + particleIndex,
                    )
                )
                assertTrue(
                    ArgentumBeliefSupport.completeFailures(
                        listOf(refreshed),
                        viewer,
                        expected,
                    ).isEmpty(),
                    "$property refresh trial=$trial particle=$particleIndex",
                )
            }
        }
    }

    private fun fixture(seed: Long, skipMulligans: Boolean = true): Fixture {
        val registry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                        PlayerConfig("Bob", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                    ),
                    seed = seed,
                    skipMulligans = skipMulligans,
                    startingPlayerIndex = 0,
                )
            )
        }
        return Fixture(
            registry,
            environment,
            ArgentumSearchWorld.create(
                environment,
                "belief-lifecycle-$seed",
                seed,
                cardRegistry = registry,
                effectiveSetupSeed = seed,
                knownDecks = knownDecks,
            ),
        )
    }

    private fun projections(
        state: GameState,
        players: List<EntityId>,
        cardRegistry: CardRegistry,
    ) = players.associateWith { viewer ->
        val observation = ObservationBuilder(cardRegistry).build(state, viewer, emptyList()).observation as TrainingObservation
        SafeObservationProjector().project(observation)
    }

    private fun swapCardIdentities(state: GameState, first: EntityId, second: EntityId): GameState {
        val firstCard = state.getEntity(first)!!.get<CardComponent>()!!
        val secondCard = state.getEntity(second)!!.get<CardComponent>()!!
        return state
            .updateEntity(first) { it.with(secondCard) }
            .updateEntity(second) { it.with(firstCard) }
    }

    private fun zoneIds(state: GameState, owner: EntityId, zone: Zone): List<EntityId> = when (zone) {
        Zone.HAND -> state.getHand(owner)
        Zone.LIBRARY -> state.getLibrary(owner)
        else -> error("Unexpected replacement zone $zone")
    }

    private fun cardName(state: GameState, id: EntityId): String =
        assertNotNull(state.getEntity(id)?.get<CardComponent>()).name

    private fun privateChoice(id: String, cardName: String): SemanticChoice = SemanticChoice.create(
        kind = SemanticChoiceKind.DECISION,
        operationFamily = SemanticOperationFamily.MULLIGAN,
        display = SemanticChoiceDisplay("Bottom $cardName", sourceName = cardName),
        canonicalPayload = buildJsonObject {
            put("choice", JsonPrimitive(id))
            put("privateCard", JsonPrimitive(cardName))
        },
    )

    private data class Fixture(
        val registry: CardRegistry,
        val environment: GameEnvironment,
        val world: ArgentumSearchWorld,
    )

    private companion object {
        const val PROPERTY_TRIALS = 3
        const val PARTICLES = 3
    }
}
