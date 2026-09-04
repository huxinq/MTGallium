package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.AbilityCounteredEvent
import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.LifeChangeReason
import com.wingedsheep.engine.core.LifeChangedEvent
import com.wingedsheep.engine.core.LookedAtCardsEvent
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DiscardRequiredEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.contract.StackItemKind
import com.wingedsheep.gym.contract.StackItemView
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind

class SafeObservationProjectorTest {
    private val cardRegistry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    @Test
    fun `incremental pure-priority projection is byte-identical to full projection`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val next = env.playerIds[1]
        val aliases = env.playerIds.mapIndexed { index, id -> id to "p$index" }.toMap()
        val before = ObservationBuilder(cardRegistry).build(env.state, viewer, env.legalActions())
            .observation as TrainingObservation
        val after = before.copy(
            priorityPlayerId = next,
            players = before.players.map { player ->
                player.copy(hasPriority = player.id == next)
            },
        )
        val projector = SafeObservationProjector()
        val incremental = projector.project(before, aliases).withPriority("p1").observation
        val full = projector.project(after, aliases).observation

        assertEquals(full, incremental)
        assertEquals(
            PolicyJson.format.encodeToString(PolicyObservation.serializer(), full),
            PolicyJson.format.encodeToString(PolicyObservation.serializer(), incremental),
        )
    }

    @Test
    fun `cleanup discard requirement has a reviewed public causal projection`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val discardingPlayer = env.playerIds[1]
        val gym = ObservationBuilder(cardRegistry).build(env.state, viewer, env.legalActions())
            .observation as TrainingObservation
        val projection = SafeObservationProjector().project(gym)

        val projected = PerspectiveEventProjector.project(
            eventId = 0,
            event = DiscardRequiredEvent(discardingPlayer, 2),
            viewer = viewer,
            aliases = env.playerIds.mapIndexed { index, id -> id to "p$index" }.toMap(),
            beforeState = env.state,
            afterState = env.state,
            beforeRefs = projection.references,
            afterRefs = projection.references,
            knowledgeObjectKey = { "known-test" },
            knownLibraryDrawObject = { false },
        )

        val detail = assertIs<PerspectiveEventDetail.Causal>(assertNotNull(projected).detail)
        assertEquals(PolicyHistoryEventKind.CAUSAL, projected.kind)
        assertEquals("CLEANUP_DISCARD_REQUIRED", detail.eventType)
        assertEquals("p1", detail.actorId)
        assertEquals(2, detail.numericValue)
        assertTrue(detail.targetNames.isEmpty())
        assertTrue(detail.targetObjectRefs.isEmpty())
    }

    @Test
    fun `public speed and combat cross through safe references`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val opponent = env.playerIds[1]
        val original = ObservationBuilder(cardRegistry).build(env.state, viewer, env.legalActions())
            .observation as TrainingObservation
        val visibleCards = original.zones.flatMap { it.cards }
        val attacker = visibleCards[0].entityId
        val blocker = visibleCards[1].entityId
        val runtime = ArgentumPolicyRuntimeProjection(
            players = mapOf(viewer to ArgentumPolicyPlayerRuntime(speed = 2)),
            combat = ArgentumPolicyCombatRuntime(
                attackingPlayerId = viewer,
                attackers = listOf(ArgentumPolicyAttackerRuntime(attacker, opponent, listOf(blocker))),
                blockers = listOf(ArgentumPolicyBlockerRuntime(blocker, listOf(attacker))),
            ),
        )

        val safe = SafeObservationProjector().project(original, null, runtime).observation

        assertEquals(2, safe.players.single { it.playerId == "p0" }.speed)
        val combat = assertNotNull(safe.combat)
        assertEquals("p0", combat.attackingPlayerId)
        assertEquals("p1", combat.attackers.single().defenderObjectRef)
        assertFalse(combat.attackers.single().attackerObjectRef in setOf(attacker.value, blocker.value))
        assertEquals(combat.attackers.single().attackerObjectRef, combat.blockers.single().blockedAttackerObjectRefs.single())
    }

    @Test
    fun `a public stack target that is no longer visible receives only a structural reference`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val original = ObservationBuilder(cardRegistry).build(env.state, viewer, env.legalActions())
            .observation as TrainingObservation
        val departedTarget = EntityId.generate()
        val stackObject = EntityId.generate()
        val observation = original.copy(
            stack = listOf(
                StackItemView(
                    entityId = stackObject,
                    controllerId = viewer,
                    name = "Historical target test",
                    kind = StackItemKind.TRIGGERED_ABILITY,
                    targets = listOf(departedTarget, departedTarget),
                )
            ),
        )

        val projected = SafeObservationProjector().project(observation).observation
        val safeTargets = projected.stack.single().targets
        val serialized = PolicyJson.format.encodeToString(PolicyObservation.serializer(), projected)

        assertEquals(listOf("stack-target:0:0", "stack-target:0:0"), safeTargets)
        assertFalse(departedTarget.value in serialized)
        assertFalse(stackObject.value in serialized)
    }

    @Test
    fun `countered abilities are retained as public causal history`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val gym = ObservationBuilder(cardRegistry).build(env.state, viewer, env.legalActions())
            .observation as TrainingObservation
        val projection = SafeObservationProjector().project(gym)
        val sourceId = env.state.getHand(viewer).first()
        val abilityId = EntityId.generate()

        val projected = PerspectiveEventProjector.project(
            eventId = 0,
            event = AbilityCounteredEvent(
                abilityEntityId = abilityId,
                description = "Countered by the game rules",
                sourceId = sourceId,
                sourceName = "Hexing Squelcher",
                controllerId = viewer,
            ),
            viewer = viewer,
            aliases = env.playerIds.mapIndexed { index, id -> id to "p$index" }.toMap(),
            beforeState = env.state,
            afterState = env.state,
            beforeRefs = projection.references,
            afterRefs = projection.references,
            knowledgeObjectKey = { "known-test" },
            knownLibraryDrawObject = { false },
        )

        val detail = assertIs<PerspectiveEventDetail.Causal>(assertNotNull(projected).detail)
        assertEquals(PolicyHistoryEventKind.CAUSAL, projected.kind)
        assertEquals("ABILITY_COUNTERED", detail.eventType)
        assertEquals("p0", detail.actorId)
        assertEquals("Hexing Squelcher", detail.sourceName)
        assertNotNull(detail.sourceObjectRef)
        assertEquals("Countered by the game rules", detail.result)
    }

    @Test
    fun `private engine events cannot create gaps in another viewers event numbers`() {
        val env = environment()
        val actor = env.playerIds[0]
        val opponent = env.playerIds[1]
        val projections = env.playerIds.associateWith { viewer ->
            val observation = ObservationBuilder(cardRegistry).build(env.state, viewer, emptyList())
                .observation as TrainingObservation
            SafeObservationProjector().project(observation)
        }
        val history = PerspectiveHistory(env.playerIds)
        val lookedAt = env.state.getLibrary(actor).take(2)

        history.recordEngineEvents(
            engineEvents = listOf(
                LookedAtCardsEvent(actor, lookedAt, "Private look"),
                LifeChangedEvent(opponent, 20, 19, LifeChangeReason.LIFE_LOSS),
            ),
            actorViewer = actor,
            beforeState = env.state,
            afterState = env.state,
            before = projections,
            after = projections,
        )

        assertEquals(listOf(0L, 1L), history.forViewer(actor).map { it.eventId })
        assertEquals(listOf(0L), history.forViewer(opponent).map { it.eventId })
    }

    @Test
    fun `a proposed position must preserve the identity and zone of a remembered hand object`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val opponent = env.playerIds[1]
        val revealed = env.state.getHand(opponent).first()
        val revealedCard = env.state.getEntity(revealed)!!
            .get<com.wingedsheep.engine.state.components.identity.CardComponent>()!!
        val replacement = (env.state.getHand(opponent) + env.state.getLibrary(opponent)).first { id ->
            env.state.getEntity(id)
                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                ?.name != revealedCard.name
        }
        val replacementCard = env.state.getEntity(replacement)!!
            .get<com.wingedsheep.engine.state.components.identity.CardComponent>()!!
        val projections = env.playerIds.associateWith { perspective ->
            SafeObservationProjector().project(
                ObservationBuilder(cardRegistry).build(env.state, perspective, emptyList()).observation as TrainingObservation,
            )
        }
        val history = PerspectiveHistory(env.playerIds)
        history.recordEngineEvents(
            engineEvents = listOf(HandLookedAtEvent(viewer, opponent, listOf(revealed))),
            actorViewer = viewer,
            beforeState = env.state,
            afterState = env.state,
            before = projections,
            after = projections,
        )
        val knownDecks = mapOf(
            "p0" to mapOf("Mountain" to 17, "Raging Goblin" to 3),
            "p1" to mapOf("Mountain" to 17, "Raging Goblin" to 3),
        )
        val knowledge = history.knowledgeForViewer(
            viewer,
            projections.getValue(viewer).observation,
            knownDecks,
        )
        val playersByAlias = env.playerIds.mapIndexed { index, id -> "p$index" to id }.toMap()
        val objectBindings = history.knowledgeObjectBindingsForViewer(viewer)

        assertNull(
            ArgentumRememberedFactSupport.failure(
                env.state,
                playersByAlias,
                objectBindings,
                knowledge,
            )
        )

        val contradictory = env.state
            .updateEntity(revealed) { it.with(replacementCard) }
            .updateEntity(replacement) { it.with(revealedCard) }
        assertEquals(
            "KNOWN_OBJECT_CARD_MISMATCH",
            ArgentumRememberedFactSupport.failure(
                contradictory,
                playersByAlias,
                objectBindings,
                knowledge,
            ),
        )
    }

    @Test
    fun `a stale known object alone cannot disclose an opponents hidden draw`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val opponent = env.playerIds[1]
        val hiddenCard = env.state.getLibrary(opponent).first()
        val projection = SafeObservationProjector().project(
            ObservationBuilder(cardRegistry).build(env.state, viewer, emptyList()).observation as TrainingObservation,
        )

        val projected = PerspectiveEventProjector.project(
            eventId = 0,
            event = CardsDrawnEvent(opponent, 1, listOf(hiddenCard)),
            viewer = viewer,
            aliases = env.playerIds.mapIndexed { index, id -> id to "p$index" }.toMap(),
            beforeState = env.state,
            afterState = env.state,
            beforeRefs = projection.references,
            afterRefs = projection.references,
            knowledgeObjectKey = { "stale-known-object" },
            knownLibraryDrawObject = { false },
        )

        val draw = assertIs<PerspectiveEventDetail.Draw>(assertNotNull(projected).detail)
        assertTrue(draw.knownCardNames.isEmpty())
        assertTrue(draw.knowledgeObjectKeys.isEmpty())
    }

    @Test
    fun `intervening shuffle invalidates a batch-start reveal before an opponent draw`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val opponent = env.playerIds[1]
        val hiddenCard = env.state.getLibrary(opponent).first()
        val revealedState = env.state.updateEntity(hiddenCard) { entity ->
            entity.with(
                com.wingedsheep.engine.state.components.identity.RevealedToComponent.to(viewer),
            )
        }
        val projection = SafeObservationProjector().project(
            ObservationBuilder(cardRegistry).build(revealedState, viewer, emptyList()).observation as TrainingObservation,
        )

        val projected = PerspectiveEventProjector.project(
            eventId = 0,
            event = CardsDrawnEvent(opponent, 1, listOf(hiddenCard)),
            viewer = viewer,
            aliases = env.playerIds.mapIndexed { index, id -> id to "p$index" }.toMap(),
            beforeState = revealedState,
            afterState = env.state,
            beforeRefs = projection.references,
            afterRefs = projection.references,
            knowledgeObjectKey = { "knowledge-object" },
            knownLibraryDrawObject = { false },
            revealIdentityInvalidated = { true },
        )

        val draw = assertIs<PerspectiveEventDetail.Draw>(assertNotNull(projected).detail)
        assertTrue(draw.knownCardNames.isEmpty())
    }

    @Test
    fun `shuffle invalidates raw known-library continuity before the next draw`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val card = env.state.getLibrary(viewer).first()
        val projections = env.playerIds.associateWith { player ->
            SafeObservationProjector().project(
                ObservationBuilder(cardRegistry).build(env.state, player, emptyList()).observation as TrainingObservation,
            )
        }
        val history = PerspectiveHistory(env.playerIds)

        history.recordEngineEvents(
            listOf(LookedAtCardsEvent(viewer, listOf(card), "Known top")),
            viewer,
            env.state,
            env.state,
            projections,
            projections,
        )
        val oldKey = assertIs<PerspectiveEventDetail.Look>(history.forViewer(viewer).last().detail)
            .knowledgeObjectKeys.single()
        history.recordEngineEvents(
            listOf(LibraryShuffledEvent(viewer)),
            viewer,
            env.state,
            env.state,
            projections,
            projections,
        )
        val shuffle = assertIs<PerspectiveEventDetail.Shuffle>(history.forViewer(viewer).last().detail)
        assertEquals(listOf(oldKey), shuffle.invalidatedKnowledgeObjectKeys)
        assertTrue(shuffle.invalidatedKnowledgeObjectKeys.none { card.value in it })
        history.recordEngineEvents(
            listOf(CardsDrawnEvent(viewer, 1, listOf(card))),
            viewer,
            env.state,
            env.state,
            projections,
            projections,
        )

        val newKey = assertIs<PerspectiveEventDetail.Draw>(history.forViewer(viewer).last().detail)
            .knowledgeObjectKeys.single()
        assertNotEquals(oldKey, newKey)
    }

    @Test
    fun `a known hand object entering the library after a shuffle retains its continuity`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val card = env.state.getHand(viewer).first()
        val cardName = assertNotNull(
            env.state.getEntity(card)
                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
        ).name
        fun projected(state: com.wingedsheep.engine.state.GameState) = env.playerIds.associateWith { player ->
            SafeObservationProjector().project(
                ObservationBuilder(cardRegistry).build(state, player, emptyList()).observation as TrainingObservation,
            )
        }
        val beforeProjection = projected(env.state)
        val history = PerspectiveHistory(env.playerIds)
        history.recordEngineEvents(
            listOf(HandLookedAtEvent(viewer, viewer, listOf(card))),
            viewer,
            env.state,
            env.state,
            beforeProjection,
            beforeProjection,
        )
        val oldKey = assertIs<PerspectiveEventDetail.Look>(history.forViewer(viewer).single().detail)
            .knowledgeObjectKeys.single()
        val afterState = env.state.copy(
            zones = env.state.zones +
                (ZoneKey(viewer, Zone.HAND) to env.state.getHand(viewer).filterNot { it == card }) +
                (ZoneKey(viewer, Zone.LIBRARY) to (env.state.getLibrary(viewer) + card)),
        )

        history.recordEngineEvents(
            listOf(
                LibraryShuffledEvent(viewer),
                ZoneChangeEvent(card, cardName, Zone.HAND, Zone.LIBRARY, viewer),
            ),
            viewer,
            env.state,
            afterState,
            beforeProjection,
            projected(afterState),
        )

        val shuffle = assertIs<PerspectiveEventDetail.Shuffle>(history.forViewer(viewer)[1].detail)
        val zoneChange = assertIs<PerspectiveEventDetail.ZoneChange>(history.forViewer(viewer)[2].detail)
        val knownDecks = mapOf(
            "p0" to mapOf("Mountain" to 17, "Raging Goblin" to 3),
            "p1" to mapOf("Mountain" to 17, "Raging Goblin" to 3),
        )
        val knowledge = history.knowledgeForViewer(
            viewer,
            projected(afterState).getValue(viewer).observation,
            knownDecks,
        )

        assertFalse(oldKey in shuffle.invalidatedKnowledgeObjectKeys)
        assertEquals(oldKey, zoneChange.knowledgeObjectKey)
        assertEquals(card, history.knowledgeObjectBindingsForViewer(viewer).getValue(oldKey))
        assertTrue(knowledge.knownObjects.any { it.knowledgeObjectKey == oldKey && it.zone == "LIBRARY" })
    }

    @Test
    fun `a visible token-like zone change remains history without a current object or binding`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val owner = env.playerIds[1]
        val objectId = env.state.getHand(owner).first()
        val objectName = assertNotNull(
            env.state.getEntity(objectId)
                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
        ).name
        val beforeState = env.state.copy(
            zones = env.state.zones +
                (ZoneKey(owner, Zone.HAND) to env.state.getHand(owner).filterNot { it == objectId }) +
                (ZoneKey(owner, Zone.BATTLEFIELD) to (env.state.getBattlefield(owner) + objectId)),
        )
        val afterState = beforeState.removeEntity(objectId)
        fun projected(state: com.wingedsheep.engine.state.GameState) = env.playerIds.associateWith { player ->
            SafeObservationProjector().project(
                ObservationBuilder(cardRegistry).build(state, player, emptyList()).observation as TrainingObservation,
            )
        }
        val history = PerspectiveHistory(env.playerIds)

        history.recordEngineEvents(
            engineEvents = listOf(
                ZoneChangeEvent(
                    entityId = objectId,
                    entityName = objectName,
                    fromZone = Zone.BATTLEFIELD,
                    toZone = Zone.GRAVEYARD,
                    ownerId = owner,
                )
            ),
            actorViewer = viewer,
            beforeState = beforeState,
            afterState = afterState,
            before = projected(beforeState),
            after = projected(afterState),
        )

        val detail = assertIs<PerspectiveEventDetail.ZoneChange>(history.forViewer(viewer).single().detail)
        val historicalKey = assertNotNull(detail.knowledgeObjectKey)
        val knownDecks = mapOf(
            "p0" to mapOf("Mountain" to 17, "Raging Goblin" to 3),
            "p1" to mapOf("Mountain" to 17, "Raging Goblin" to 3),
        )
        val afterProjection = projected(afterState).getValue(viewer)
        val knowledge = history.knowledgeForViewer(viewer, afterProjection.observation, knownDecks)

        assertFalse(detail.continuesAsCurrentObject)
        assertFalse(objectId.value in historicalKey)
        assertTrue(knowledge.knownObjects.none { it.knowledgeObjectKey == historicalKey })
        assertFalse(history.knowledgeObjectBindingsForViewer(viewer).containsKey(historicalKey))
        assertNull(
            ArgentumRememberedFactSupport.failure(
                afterState,
                env.playerIds.mapIndexed { index, id -> "p$index" to id }.toMap(),
                history.knowledgeObjectBindingsForViewer(viewer),
                knowledge,
            )
        )
    }

    private fun environment(): GameEnvironment {
        return GameEnvironment.create(cardRegistry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of("Mountain" to 17, "Raging Goblin" to 3)),
                        PlayerConfig("Bob", Deck.of("Mountain" to 17, "Raging Goblin" to 3)),
                    ),
                    seed = 77L,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                )
            )
        }
    }

    @Test
    fun `safe projection is byte-identical when unauthorized hidden zones are permuted`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val opponent = env.playerIds[1]
        val original = ObservationBuilder(cardRegistry).build(env.state, viewer, env.legalActions())
            .observation as TrainingObservation
        val permutedState = env.state.copy(
            zones = env.state.zones + mapOf(
                ZoneKey(opponent, Zone.HAND) to env.state.getHand(opponent).reversed(),
                ZoneKey(opponent, Zone.LIBRARY) to env.state.getLibrary(opponent).reversed(),
                ZoneKey(viewer, Zone.LIBRARY) to env.state.getLibrary(viewer).reversed(),
            )
        )
        val permuted = ObservationBuilder(cardRegistry).build(permutedState, viewer, env.legalActions())
            .observation as TrainingObservation

        val projector = SafeObservationProjector()
        val left = projector.project(original).observation
        val right = projector.project(permuted).observation

        assertEquals(
            PolicyJson.format.encodeToString(PolicyObservation.serializer(), left),
            PolicyJson.format.encodeToString(PolicyObservation.serializer(), right),
        )
    }

    @Test
    fun `visible unordered-zone storage order does not rename policy objects`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val original = ObservationBuilder(cardRegistry).build(env.state, viewer, env.legalActions())
            .observation as TrainingObservation
        val reorderedState = env.state.copy(
            zones = env.state.zones + (
                ZoneKey(viewer, Zone.HAND) to env.state.getHand(viewer).reversed()
            ),
        )
        val reordered = ObservationBuilder(cardRegistry).build(reorderedState, viewer, env.legalActions())
            .observation as TrainingObservation

        val projector = SafeObservationProjector()

        assertEquals(projector.project(original).observation, projector.project(reordered).observation)
    }

    @Test
    fun `private search and reorder contracts only cross for their chooser`() {
        val env = environment()
        val observer = env.playerIds[0]
        val chooser = env.playerIds[1]
        val ids = env.state.getLibrary(chooser).take(2)
        val metadata = ids.associateWith { id ->
            val card = env.state.getEntity(id)!!
                .get<com.wingedsheep.engine.state.components.identity.CardComponent>()!!
            SearchCardInfo(card.name, card.manaCost.toString(), card.typeLine.toString())
        }
        val context = DecisionContext(sourceName = "Private library operation")
        val decisions = listOf(
            SearchLibraryDecision("search", chooser, "Search", context, ids, 0, 1, metadata, "a card"),
            ReorderLibraryDecision("reorder", chooser, "Reorder", context, ids, metadata),
        )

        for (decision in decisions) {
            val state = env.state.copy(pendingDecision = decision)
            val unauthorized = ObservationBuilder(cardRegistry).build(state, observer, emptyList())
                .observation as TrainingObservation
            val authorized = ObservationBuilder(cardRegistry).build(state, chooser, emptyList())
                .observation as TrainingObservation

            val hidden = SafeObservationProjector().project(
                unauthorized,
                playerAliases = null,
                runtime = ArgentumPolicyRuntimeProjection.EMPTY,
                pendingDecision = decision,
            ).observation.pendingDecision
            val visible = SafeObservationProjector().project(
                authorized,
                playerAliases = null,
                runtime = ArgentumPolicyRuntimeProjection.EMPTY,
                pendingDecision = decision,
            ).observation.pendingDecision

            assertFalse(assertNotNull(hidden).canRespond)
            assertNull(hidden.choiceSpec)
            assertNotNull(assertNotNull(visible).choiceSpec)
        }
    }

    @Test
    fun `only hidden-zone decision responses are private to their chooser`() {
        val env = environment()
        val chooser = env.playerIds[0]
        val ids = env.state.getLibrary(chooser).take(2)
        val metadata = ids.associateWith { SearchCardInfo("Hidden", "", "Card") }
        val context = DecisionContext(sourceName = "Visibility contract")

        assertTrue(SearchLibraryDecision("s", chooser, "Search", context, ids, 0, 1, metadata, "card").isPrivateToChooser())
        assertTrue(ReorderLibraryDecision("r", chooser, "Order", context, ids, metadata).isPrivateToChooser())
        assertTrue(SelectCardsDecision("h", chooser, "Hidden cards", context, ids, 1, 1, cardInfo = metadata).isPrivateToChooser())
        assertFalse(SelectCardsDecision("v", chooser, "Visible cards", context, ids, 1, 1).isPrivateToChooser())
        assertFalse(
            ChooseTargetsDecision(
                "t", chooser, "Targets", context,
                listOf(TargetRequirementInfo(0, "target")),
                mapOf(0 to ids),
            ).isPrivateToChooser()
        )
    }

    @Test
    fun `raw runtime ids do not occur as visible object references`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val gym = ObservationBuilder(cardRegistry).build(env.state, viewer, env.legalActions())
            .observation as TrainingObservation
        val safe = SafeObservationProjector().project(gym).observation
        val rawVisible = gym.zones.flatMap { it.cards }.map { it.entityId.value }.toSet()

        assertFalse(safe.zones.flatMap { it.cards }.any { it.objectRef in rawVisible })
    }

    @Test
    fun `private decision payload is replaced by an occurrence marker for the opponent`() {
        val env = environment()
        val actor = env.playerIds[0]
        val opponent = env.playerIds[1]
        val history = PerspectiveHistory(env.playerIds)
        val choice = SemanticChoice.create(
            kind = SemanticChoiceKind.DECISION,
            operationFamily = org.mtgallium.agent.infoset.core.SemanticOperationFamily.MULLIGAN,
            display = SemanticChoiceDisplay("Mulligan bottom", sourceName = "Shock"),
            canonicalPayload = buildJsonObject {
                put("choice", JsonPrimitive("bottom-shock"))
                put("privateCard", JsonPrimitive("zone:p0:HAND:0"))
            },
        )

        history.recordChoice(
            actor,
            choice,
            privateToActor = true,
            kind = PolicyHistoryEventKind.MULLIGAN,
            strategicallyOptional = false,
        )

        val own = history.forViewer(actor).single()
        val hidden = history.forViewer(opponent).single()
        assertEquals(PolicyHistoryEventKind.MULLIGAN, own.kind)
        assertEquals(PolicyHistoryEventKind.PRIVATE_DECISION_OCCURRED, hidden.kind)
        assertFalse("privateCard" in hidden.payload)
        assertFalse("Mulligan bottom" in hidden.payload.toString())
        assertFalse("Shock" in hidden.payload.toString())
        assertEquals("DECISION", hidden.payload["choiceKind"]?.jsonPrimitive?.content)
        assertEquals(false, (own.detail as PerspectiveEventDetail.Choice).strategicallyOptional)
        assertEquals(null, (hidden.detail as PerspectiveEventDetail.Choice).strategicallyOptional)
        assertEquals(null, (hidden.detail as PerspectiveEventDetail.Choice).operationFamily)
    }

    @Test
    fun `public action history exposes its family but optionality only to the actor`() {
        val env = environment()
        val actor = env.playerIds[0]
        val opponent = env.playerIds[1]
        val history = PerspectiveHistory(env.playerIds)
        val choice = SemanticChoice.create(
            kind = SemanticChoiceKind.ACTION,
            operationFamily = org.mtgallium.agent.infoset.core.SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay("Pass priority"),
            canonicalPayload = buildJsonObject { put("type", JsonPrimitive("PassPriority")) },
        )

        history.recordChoice(
            actor,
            choice,
            privateToActor = false,
            kind = PolicyHistoryEventKind.PRIORITY_PASS,
            strategicallyOptional = false,
        )

        val own = history.forViewer(actor).single().detail as PerspectiveEventDetail.Choice
        val observed = history.forViewer(opponent).single().detail as PerspectiveEventDetail.Choice
        assertEquals(false, own.strategicallyOptional)
        assertEquals(null, observed.strategicallyOptional)
        assertEquals(choice.operationFamily, observed.operationFamily)
    }
}
