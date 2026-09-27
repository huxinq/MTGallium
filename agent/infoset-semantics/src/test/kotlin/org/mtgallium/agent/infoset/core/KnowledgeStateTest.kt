package org.mtgallium.agent.infoset.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KnowledgeStateTest {
    private val decks = mapOf(
        "p0" to mapOf("Mountain" to 19, "Shock" to 2),
        "p1" to mapOf("Mountain" to 19, "Shock" to 2),
    )

    @Test
    fun `transform updates only an existing continuity handle and preserves legacy object-state bytes`() {
        val legacy = """{"type":"object_state","schemaVersion":1,"objectRef":"view-ref","objectName":"Mountain","change":"TRANSFORMED","value":null,"relatedObjectRefs":[]}"""
        val oldDetail = CanonicalJson.format.decodeFromString<ObservedEventDetail>(legacy)
        assertEquals(legacy, CanonicalJson.format.encodeToString(ObservedEventDetail.serializer(), oldDetail))
        val remembered = event(1, ObservedEventDetail.ZoneChange(
            ownerId = "p1", fromZone = "HAND", toZone = "GRAVEYARD",
            cardName = "Shock", knowledgeObjectKey = "remembered",
        ))
        fun reduce(detail: ObservedEventDetail) = KnowledgeReplay.reduce(
            "p0", decks, observation(), listOf(remembered, event(2, detail)),
        ).knownObjects.single()
        val transform = (oldDetail as ObservedEventDetail.ObjectState).copy(
            schemaVersion = PERSPECTIVE_EVENT_SCHEMA_V2, knowledgeObjectKey = "remembered",
        )
        assertEquals("Mountain", reduce(transform).cardName)
        assertEquals("p1", reduce(transform).ownerId)
        assertEquals("GRAVEYARD", reduce(transform).zone)
        assertEquals("Shock", reduce(oldDetail).cardName)
        assertEquals("Shock", reduce(transform.copy(knowledgeObjectKey = "unrelated")).cardName)
        assertEquals("Shock", reduce(transform.copy(objectName = null)).cardName)
        assertEquals("Shock", reduce(transform.copy(change = "CONTROLLER_CHANGED")).cardName)
    }

    @Test
    fun `current visible cards are exact and hidden remainder preserves deck conservation`() {
        val knowledge = KnowledgeReplay.reduce("p0", decks, observation(), emptyList())

        assertTrue(knowledge.isComplete)
        assertEquals(mapOf("Mountain" to 1, "Shock" to 1), knowledge.zone("p0", "HAND").knownCardCounts)
        assertEquals(18, knowledge.unlocatedCardCounts.getValue("p0").getValue("Mountain"))
        assertEquals(1, knowledge.unlocatedCardCounts.getValue("p0").getValue("Shock"))
        assertEquals(19, knowledge.unlocatedCardCounts.getValue("p1").getValue("Mountain"))
        assertEquals(2, knowledge.unlocatedCardCounts.getValue("p1").getValue("Shock"))
    }

    @Test
    fun `shuffle invalidates order knowledge but retains count knowledge`() {
        val looked = event(
            1,
            ObservedEventDetail.Look(
                ownerId = "p0",
                zone = "LIBRARY",
                cardNames = listOf("Shock", "Mountain"),
                ordered = true,
                fromTop = true,
            ),
        )
        val before = KnowledgeReplay.reduce("p0", decks, observation(), listOf(looked))
        val after = KnowledgeReplay.reduce(
            "p0",
            decks,
            observation(),
            listOf(
                looked,
                event(
                    2,
                    ObservedEventDetail.Shuffle(playerId = "p0", cause = "SPELL_OR_ABILITY"),
                ),
            ),
        )

        assertEquals(listOf("Shock", "Mountain"), before.order("p0").top)
        assertTrue(after.order("p0").top.isEmpty())
        assertEquals(1, after.order("p0").shuffleEpoch)
        assertEquals(before.unlocatedCardCounts, after.unlocatedCardCounts)
        assertNotEquals(before.knowledgeDigest, after.knowledgeDigest)
    }

    @Test
    fun `unsupported visible transition fails the completeness claim closed`() {
        val knowledge = KnowledgeReplay.reduce(
            "p0",
            decks,
            observation(),
            listOf(
                event(
                    1,
                    ObservedEventDetail.UnsupportedVisibleTransition(
                        engineEventType = "MysteryEvent",
                        reason = "no safe projector",
                    ),
                ),
            ),
        )

        assertFalse(knowledge.isComplete)
        assertEquals(listOf("MysteryEvent: no safe projector"), knowledge.unsupportedReasons)
    }

    @Test
    fun `a legitimately revealed opponent card remains exact across later unrelated events`() {
        val reveal = event(
            1,
            ObservedEventDetail.Reveal(
                ownerId = "p1",
                zone = "HAND",
                cardNames = listOf("Shock"),
                knowledgeObjectKeys = listOf("knowledge-object-0"),
            ),
        )
        val later = event(
            2,
            ObservedEventDetail.Causal(
                eventType = "SPELL_RESOLVED",
                actorId = "p0",
                sourceName = "Mountain",
                sourceObjectRef = null,
            ),
        )

        val knowledge = KnowledgeReplay.reduce("p0", decks, observation(), listOf(reveal, later))

        assertEquals(mapOf("Shock" to 1), knowledge.zone("p1", "HAND").knownCardCounts)
        assertEquals(1, knowledge.unlocatedCardCounts.getValue("p1").getValue("Shock"))
        assertEquals("HAND", knowledge.knownObjects.single().zone)
    }

    @Test
    fun `same visible snapshot may retain strategically different legitimate knowledge`() {
        val withoutReveal = KnowledgeReplay.reduce("p0", decks, observation(), emptyList())
        val withReveal = KnowledgeReplay.reduce(
            "p0",
            decks,
            observation(),
            listOf(
                event(
                    1,
                    ObservedEventDetail.Reveal(
                        ownerId = "p1",
                        zone = "HAND",
                        cardNames = listOf("Shock"),
                        knowledgeObjectKeys = listOf("knowledge-object-0"),
                    ),
                ),
            ),
        )

        assertNotEquals(withoutReveal.knowledgeDigest, withReveal.knowledgeDigest)
        assertEquals(emptyMap(), withoutReveal.zone("p1", "HAND").knownCardCounts)
        assertEquals(mapOf("Shock" to 1), withReveal.zone("p1", "HAND").knownCardCounts)
    }

    @Test
    fun `history differences with no epistemic effect reduce to the same knowledge state`() {
        val first = KnowledgeReplay.reduce(
            "p0",
            decks,
            observation(),
            listOf(
                event(
                    1,
                    ObservedEventDetail.Causal(
                        eventType = "ABILITY_RESOLVED",
                        actorId = "p0",
                        sourceName = "Hired Claw",
                        sourceObjectRef = null,
                    ),
                ),
            ),
        )
        val second = KnowledgeReplay.reduce(
            "p0",
            decks,
            observation(),
            listOf(
                event(
                    1,
                    ObservedEventDetail.TurnStructure(
                        turnNumber = 1,
                        phase = "BEGINNING",
                        step = "UPKEEP",
                        activePlayerId = "p0",
                        priorityPlayerId = "p0",
                    ),
                ),
            ),
        )

        assertEquals(first, second)
    }

    @Test
    fun `drawing a legitimately known top card moves that knowledge into hand`() {
        val knowledge = KnowledgeReplay.reduce(
            "p0",
            decks,
            observation(),
            listOf(
                event(
                    1,
                    ObservedEventDetail.Look(
                        ownerId = "p1",
                        zone = "LIBRARY",
                        cardNames = listOf("Shock", "Mountain"),
                        knowledgeObjectKeys = listOf("knowledge-object-0", "knowledge-object-1"),
                        ordered = true,
                        fromTop = true,
                    ),
                ),
                event(
                    2,
                    ObservedEventDetail.Draw(
                        playerId = "p1",
                        count = 1,
                        knownCardNames = listOf("Shock"),
                        knowledgeObjectKeys = listOf("knowledge-object-0"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("Mountain"), knowledge.order("p1").top)
        assertEquals(mapOf("Shock" to 1), knowledge.zone("p1", "HAND").knownCardCounts)
        assertEquals("HAND", knowledge.knownObjects.single { it.cardName == "Shock" }.zone)
    }

    @Test
    fun `incremental accumulator matches full replay after every event`() {
        val history = listOf(
            event(
                1,
                ObservedEventDetail.Look(
                    ownerId = "p1",
                    zone = "LIBRARY",
                    cardNames = listOf("Shock", "Mountain"),
                    knowledgeObjectKeys = listOf("knowledge-object-0", "knowledge-object-1"),
                    ordered = true,
                    fromTop = true,
                ),
            ),
            event(
                2,
                ObservedEventDetail.Draw(
                    playerId = "p1",
                    count = 1,
                    knownCardNames = listOf("Shock"),
                    knowledgeObjectKeys = listOf("knowledge-object-0"),
                ),
            ),
            event(
                3,
                ObservedEventDetail.Shuffle(playerId = "p1", cause = "SPELL_OR_ABILITY"),
            ),
        )
        val accumulator = KnowledgeTracker()

        history.forEachIndexed { index, next ->
            accumulator.append(next)
            val incremental = accumulator.snapshot("p0", decks, observation())
            val replayed = KnowledgeReplay.reduce("p0", decks, observation(), history.take(index + 1))
            assertEquals(replayed, incremental)
        }
        assertEquals(
            KnowledgeReplay.reduce("p0", decks, observation(), history),
            accumulator.fork().snapshot("p0", decks, observation()),
        )
    }

    @Test
    fun `ambiguous hidden movement fails exact reduction closed instead of tracking a raw object`() {
        val knowledge = KnowledgeReplay.reduce(
            "p0",
            decks,
            observation(),
            listOf(
                event(
                    1,
                    ObservedEventDetail.Reveal(
                        ownerId = "p1",
                        zone = "HAND",
                        cardNames = listOf("Shock"),
                        knowledgeObjectKeys = listOf("knowledge-object-0"),
                    ),
                ),
                event(
                    2,
                    ObservedEventDetail.ZoneChange(
                        ownerId = "p1",
                        fromZone = "HAND",
                        toZone = "LIBRARY",
                        cardName = null,
                    ),
                ),
            ),
        )

        assertFalse(knowledge.isComplete)
        assertTrue(knowledge.knownObjects.isEmpty())
        assertTrue(knowledge.unsupportedReasons.single().contains("requires a location constraint"))
    }

    @Test
    fun `shuffle invalidates object-specific library knowledge but retains deck conservation`() {
        val knowledge = KnowledgeReplay.reduce(
            "p0",
            decks,
            observation(),
            listOf(
                event(
                    1,
                    ObservedEventDetail.Look(
                        ownerId = "p1",
                        zone = "LIBRARY",
                        cardNames = listOf("Shock"),
                        knowledgeObjectKeys = listOf("knowledge-object-0"),
                        ordered = true,
                        fromTop = true,
                    ),
                ),
                event(2, ObservedEventDetail.Shuffle(playerId = "p1", cause = "EFFECT")),
            ),
        )

        assertTrue(knowledge.isComplete)
        assertTrue(knowledge.knownObjects.none { it.ownerId == "p1" && it.zone == "LIBRARY" })
        assertTrue(knowledge.order("p1").top.isEmpty())
        assertEquals(decks.getValue("p1").getValue("Shock"), knowledge.unlocatedCardCounts.getValue("p1")["Shock"])
    }

    @Test
    fun `shuffle invalidates only its supplied opaque current-object keys in replay and incrementally`() {
        val history = listOf(
            event(
                1,
                ObservedEventDetail.ZoneChange(
                    ownerId = "p1",
                    fromZone = "LIBRARY",
                    toZone = "HAND",
                    cardName = "Shock",
                    knowledgeObjectKey = "mulligan-hand-key",
                ),
            ),
            event(
                2,
                ObservedEventDetail.Reveal(
                    ownerId = "p1",
                    zone = "HAND",
                    cardNames = listOf("Mountain"),
                    knowledgeObjectKeys = listOf("unrelated-hand-key"),
                ),
            ),
            event(
                3,
                ObservedEventDetail.Shuffle(
                    playerId = "p1",
                    cause = "MULLIGAN",
                    invalidatedKnowledgeObjectKeys = listOf("mulligan-hand-key"),
                ),
            ),
        )
        val accumulator = KnowledgeTracker()

        history.forEachIndexed { index, next ->
            accumulator.append(next)
            assertEquals(
                KnowledgeReplay.reduce("p0", decks, observation(), history.take(index + 1)),
                accumulator.snapshot("p0", decks, observation()),
            )
        }

        val knowledge = KnowledgeReplay.reduce("p0", decks, observation(), history)
        assertEquals(listOf("unrelated-hand-key"), knowledge.knownObjects.map { it.knowledgeObjectKey })
    }

    @Test
    fun `non-continuing named zone change removes only that current object`() {
        val history = listOf(
            event(
                1,
                ObservedEventDetail.ZoneChange(
                    ownerId = "p1",
                    fromZone = "HAND",
                    toZone = "BATTLEFIELD",
                    cardName = "Shock",
                    knowledgeObjectKey = "ceased-token-key",
                ),
            ),
            event(
                2,
                ObservedEventDetail.ZoneChange(
                    ownerId = "p1",
                    fromZone = "HAND",
                    toZone = "BATTLEFIELD",
                    cardName = "Mountain",
                    knowledgeObjectKey = "surviving-object-key",
                ),
            ),
            event(
                3,
                ObservedEventDetail.ZoneChange(
                    ownerId = "p1",
                    fromZone = "BATTLEFIELD",
                    toZone = "GRAVEYARD",
                    cardName = "Shock",
                    knowledgeObjectKey = "ceased-token-key",
                    continuesAsCurrentObject = false,
                ),
            ),
        )
        val knowledge = KnowledgeReplay.reduce(
            "p0",
            decks,
            observation(),
            history,
        )
        val accumulator = KnowledgeTracker().also { it.append(history) }

        assertEquals(knowledge, accumulator.snapshot("p0", decks, observation()))
        assertEquals(listOf("surviving-object-key"), knowledge.knownObjects.map { it.knowledgeObjectKey })
        assertEquals("BATTLEFIELD", knowledge.knownObjects.single().zone)
    }

    @Test
    fun `private London mulligan choice records and shuffle invalidates known bottom order`() {
        val bottomChoice = event(
            1,
            ObservedEventDetail.Choice(
                semanticSignature = "bottom-order",
                choiceKind = SemanticChoiceKind.ACTION.name,
                operationFamily = SemanticOperationFamily.MULLIGAN,
                privateToActor = true,
                strategicallyOptional = true,
                libraryBottomCardNames = listOf("Shock", "Mountain"),
                libraryBottomKnowledgeObjectKeys = listOf("bottom-shock", "bottom-mountain"),
            ),
            actor = "p0",
        )
        val before = KnowledgeReplay.reduce("p0", decks, observation(), listOf(bottomChoice))
        val after = KnowledgeReplay.reduce(
            "p0",
            decks,
            observation(),
            listOf(
                bottomChoice,
                event(2, ObservedEventDetail.Shuffle(playerId = "p0", cause = "SPELL_OR_ABILITY")),
            ),
        )

        assertEquals(listOf("Shock", "Mountain"), before.order("p0").bottom)
        assertEquals(
            setOf("bottom-shock", "bottom-mountain"),
            before.knownObjects.map { it.knowledgeObjectKey }.toSet(),
        )
        assertTrue(after.order("p0").bottom.isEmpty())
        assertTrue(after.knownObjects.none { it.ownerId == "p0" && it.zone == "LIBRARY" })
    }

    private fun event(id: Long, detail: ObservedEventDetail, actor: String? = null) = ObservedEvent(
        eventId = id,
        audience = EventAudience(EventAudienceScope.PUBLIC),
        actor = actor,
        kind = ObservedEventKind.FORCED_TRANSITION,
        payload = buildJsonObject { },
        detail = detail,
    )

    private fun PlayerKnowledge.zone(player: String, zone: String) =
        zones.single { it.ownerId == player && it.zone == zone }

    private fun PlayerKnowledge.order(player: String) =
        knownLibraryOrders.single { it.playerId == player }

    private fun observation() = PlayerObservationSnapshot(
        viewerId = "p0",
        turnNumber = 1,
        phase = "BEGINNING",
        step = "UPKEEP",
        activePlayerId = "p0",
        priorityPlayerId = "p0",
        players = listOf(
            PlayerView("p0", "Root", 20, 2, 19, 0, 0, ManaPoolView(), true, true, false),
            PlayerView("p1", "Opponent", 20, 2, 19, 0, 0, ManaPoolView(), false, false, false),
        ),
        zones = listOf(
            ZoneView(
                "p0", "HAND", false, 2,
                listOf(card("safe:mountain", "Mountain"), card("safe:shock", "Shock")),
            ),
            ZoneView("p0", "LIBRARY", true, 19, emptyList()),
            ZoneView("p1", "HAND", true, 2, emptyList()),
            ZoneView("p1", "LIBRARY", true, 19, emptyList()),
        ),
        stack = emptyList(),
        pendingDecision = null,
        observationDigest = CanonicalJson.sha256("observation"),
    )

    private fun card(ref: String, name: String) = ObjectView(
        objectRef = ref,
        definitionId = name,
        name = name,
        zone = "HAND",
        ownerId = "p0",
        controllerId = null,
        types = if (name == "Mountain") setOf("LAND") else setOf("INSTANT"),
        subtypes = emptySet(),
        colors = if (name == "Shock") setOf("RED") else emptySet(),
        keywords = emptySet(),
        manaCost = if (name == "Shock") "{R}" else "",
        manaValue = if (name == "Shock") 1 else 0,
        oracleText = "",
        power = null,
        toughness = null,
        tapped = false,
        summoningSick = false,
        faceDown = false,
        damageMarked = 0,
        counters = emptyMap(),
        attachedTo = null,
        attachments = emptyList(),
    )
}
