package org.mtgallium.agent.neural

import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.*
import kotlin.test.*

class FactualPolicyTensorsTest {
    private val encoder = FactualPolicyEncoder()

    @Test fun `unavailable deck knowledge differs from known empty without requiring a sampler`() {
        val unknown = NeuralFixtures.state()
        val empty = NeuralFixtures.state(knowledge = unknown.knowledge.copy(deckCardCounts = mapOf("p1" to emptyMap())))
        val a = encoder.decision(NeuralFixtures.site(unknown))
        val b = encoder.decision(NeuralFixtures.site(empty))
        assertNotEquals(a.view, b.view)
        assertContains(text(a.view), "\"other-1\":null")
        assertContains(text(b.view), "\"other-1\":{}")
    }

    @Test fun `identity bookkeeping and presentation never enter factual tokens`() {
        val first = NeuralFixtures.state()
        val altered = EpistemicState.capture(first.observation.copy(observationDigest = "unrelated-digest",
            players = first.observation.players.map { it.copy(name = "seed-and-source-game-label") }),
            first.history, first.historyCommitment, first.knowledge.copy(knowledgeDigest = "different-knowledge-hash"), false)
        val site = NeuralFixtures.site(first)
        val other = DecisionSite.create(altered, "p0", site.expansion.copy(proposalVersion = "another-proposer", proposalSeed = 87123,
            candidates = site.expansion.candidates.map { it.copy(display = SemanticChoiceDisplay("oracle answer")) }))
        assertEquals(encoder.decision(site), encoder.decision(other))
        assertFalse(text(encoder.decision(other).view).contains("seed-and-source-game-label"))
        val roundtrip = PolicyJson.format.decodeFromJsonElement<InformationStateRepresentation>(
            PolicyJson.format.encodeToJsonElement(site.information()))
        assertEquals(encoder.decision(site), encoder.decision(DecisionSite.create(EpistemicState.capture(roundtrip), requireNotNull(roundtrip.actingPlayerId), site.expansion)))
    }

    @Test fun `visible reference relabeling preserves relationships without merging distinct creatures`() {
        fun inputs(prefix: String): FactualDecisionTensors {
            val a = "$prefix:0"; val b = "$prefix:1"
            val state = NeuralFixtures.state(cards = listOf(NeuralFixtures.card(a), NeuralFixtures.card(b)))
            val actions = listOf(a, b).map { target -> SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
                operationFamily = SemanticOperationFamily.DECLARE_BLOCKERS, display = SemanticChoiceDisplay("same display"),
                canonicalPayload = buildJsonObject { put("target", target) }) }
            return encoder.decision(NeuralFixtures.site(state, actions))
        }
        val a = inputs("zone:p0:BATTLEFIELD:aaaaaaaaaaaaaaaa")
        val b = inputs("zone:p0:BATTLEFIELD:bbbbbbbbbbbbbbbb")
        assertEquals(a, b)
        assertNotEquals(a.actions[0], a.actions[1])
        assertFalse(text(a.view).contains("aaaaaaaa"))
        val state = NeuralFixtures.state(cards = listOf(NeuralFixtures.card("zone:p0:BATTLEFIELD:abc:0")))
        val ref = "object:p0:BATTLEFIELD:" + "c".repeat(64)
        val action = SemanticChoice.create(kind = SemanticChoiceKind.ACTION, operationFamily = SemanticOperationFamily.DECLARE_BLOCKERS,
            display = SemanticChoiceDisplay("block"), canonicalPayload = buildJsonObject {
                put("assignments", buildJsonObject { put(ref, JsonArray(listOf(JsonPrimitive(ref)))) })
            })
        val site = NeuralFixtures.site(state, listOf(action))
        assertFailsWith<FactualEncodingException> { encoder.decision(site) }
        val bound = encoder.decision(site, mapOf(ref to listOf("zone:p0:BATTLEFIELD:abc:0")))
        assertContains(text(bound.actions.single()), "visibleGroup")
        assertFalse(text(bound.actions.single()).contains("c".repeat(32)))
    }

    @Test fun `candidate order and completeness remain distinct from padding`() {
        val site = NeuralFixtures.site()
        val first = encoder.decision(site)
        val reversed = encoder.decision(DecisionSite.create(site.epistemic, site.actor,
            site.expansion.copy(candidates = site.expansion.candidates.reversed())))
        assertEquals(first.view, reversed.view)
        assertEquals(first.actions.reversed(), reversed.actions)
        val bounded = encoder.decision(DecisionSite.create(site.epistemic, site.actor,
            site.expansion.copy(isExhaustive = false, isProfileExhaustive = false,
                omissionReasons = setOf(PolicyExpansionOmissionReason.RESPONSE_LIMIT))))
        assertEquals(first.actions, bounded.actions)
        assertFalse(bounded.rulesExhaustive)
        assertFalse(bounded.profileExhaustive)
        assertTrue(first.view.all { it != 0 } && first.actions.flatten().all { it != 0 })
    }

    @Test fun `event ordinal is not a feature and private entitlement is checked`() {
        val event = NeuralFixtures.event(0, 1)
        assertEquals(encoder.event(event, "p0", listOf("p0", "p1")),
            encoder.event(event.copy(eventId = 981), "p0", listOf("p0", "p1")))
        assertNotEquals(encoder.event(event, "p0", listOf("p0", "p1")),
            encoder.event(NeuralFixtures.event(0, -1), "p0", listOf("p0", "p1")))
        assertFailsWith<IllegalArgumentException> {
            encoder.event(event.copy(audience = PolicyAudience(PolicyAudienceScope.ENTITLED_PLAYERS, setOf("p1"))),
                "p0", listOf("p0", "p1"))
        }
        assertFailsWith<FactualEncodingException> { encoder.event(event.copy(detail = null), "p0", listOf("p0", "p1")) }
    }

    @Test fun `input limits refuse instead of truncating and captured bytes are detached`() {
        assertFailsWith<FactualEncodingException> {
            FactualPolicyEncoder(FactualTensorSchema(maximumViewBytes = 256)).decision(NeuralFixtures.site())
        }
        val tokens = encoder.decision(NeuralFixtures.site())
        assertFailsWith<UnsupportedOperationException> { (tokens.view as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (tokens.actions[0] as MutableList).clear() }
        for (name in listOf("org.mtgallium.agent.infoset.core.SearchWorld",
            "org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld")) {
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        }
    }

    @Test fun `qualified remembered occurrences survive across events and align with current view`() {
        fun history(a: String, b: String) = listOf(a, b).mapIndexed { i, key ->
            PolicyHistoryEvent(i.toLong(), PolicyAudience(PolicyAudienceScope.PUBLIC), "p0", PolicyHistoryEventKind.COUNTER_CHANGE,
                buildJsonObject { }, PerspectiveEventDetail.CounterChange(objectRef = "history-object:v1:$key",
                    objectName = "Raging Goblin", counterType = "+1/+1", delta = 1))
        }
        val same = history("knowledge-object-1", "knowledge-object-1")
        val distinct = history("knowledge-object-1", "knowledge-object-2")
        val renamed = history("knowledge-object-99", "knowledge-object-17")
        val a = encoder.events(same, "p0", listOf("p0", "p1"))
        val b = encoder.events(distinct, "p0", listOf("p0", "p1"))
        assertNotEquals(a, b)
        assertEquals(b, encoder.events(renamed, "p0", listOf("p0", "p1")))
        assertEquals(b.drop(1), encoder.events(distinct, "p0", listOf("p0", "p1"), 1))
        assertContains(text(b[0]), "remembered-0")
        assertContains(text(b[1]), "remembered-1")
        val state = NeuralFixtures.state(distinct, cards = listOf(NeuralFixtures.card("history-object:v1:knowledge-object-2")))
        assertContains(text(encoder.decision(NeuralFixtures.site(state)).view), "remembered-1")
        assertFalse(text(b[1]).contains("knowledge-object"))
    }

    @Test fun `shuffle allocator names are not features and unsupported transitions refuse`() {
        fun shuffle(key: String) = PolicyHistoryEvent(0, PolicyAudience(PolicyAudienceScope.PUBLIC), "p0",
            PolicyHistoryEventKind.SHUFFLE, buildJsonObject { }, PerspectiveEventDetail.Shuffle(playerId = "p0",
                cause = "shuffle", invalidatedKnowledgeObjectKeys = listOf(key)))
        val first = encoder.event(shuffle("knowledge-object-1"), "p0", listOf("p0", "p1"))
        assertEquals(first, encoder.event(shuffle("knowledge-object-99"), "p0", listOf("p0", "p1")))
        assertFalse(text(first).contains("knowledge-object"))
        assertFailsWith<FactualEncodingException> {
            encoder.event(shuffle("x").copy(kind = PolicyHistoryEventKind.UNSUPPORTED_VISIBLE_TRANSITION,
                detail = PerspectiveEventDetail.UnsupportedVisibleTransition(engineEventType = "Unrepresented", reason = "test")),
                "p0", listOf("p0", "p1"))
        }
    }

    @Test fun `legacy visible deltas admit only recorder facts and never observation hashes`() {
        fun delta(before: String, after: String) = PolicyHistoryEvent(1,
            PolicyAudience(PolicyAudienceScope.ENTITLED_PLAYERS, setOf("p0")), null,
            PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION, buildJsonObject {
                put("fromObservation", before); put("toObservation", after)
                put("zoneDelta", buildJsonArray { add(buildJsonObject {
                    put("key", "p0:HAND:Mountain"); put("before", 1); put("after", 0)
                }) })
                put("priorityFrom", "p0"); put("priorityTo", "p1")
            })
        val first = delta("a".repeat(64), "b".repeat(64))
        val tokens = encoder.event(first, "p0", listOf("p0", "p1"))
        assertEquals(tokens, encoder.event(delta("c".repeat(64), "d".repeat(64)), "p0", listOf("p0", "p1")))
        assertContains(text(tokens), "Mountain")
        assertContains(text(tokens), "\"priorityTo\":\"other-1\"")
        assertFalse("Observation" in text(tokens))
        assertFailsWith<FactualEncodingException> {
            encoder.event(first.copy(payload = JsonObject(first.payload + ("unrecognized" to JsonPrimitive(9)))),
                "p0", listOf("p0", "p1"))
        }
        assertFailsWith<FactualEncodingException> {
            encoder.event(first.copy(kind = PolicyHistoryEventKind.ACTION), "p0", listOf("p0", "p1"))
        }
    }

    private fun text(tokens: List<Int>) = tokens.map { (it - 1).toByte() }.toByteArray().toString(Charsets.UTF_8)
}

internal object NeuralFixtures {
    fun event(index: Int, cue: Int = 1) = PolicyHistoryEvent(index.toLong(), PolicyAudience(PolicyAudienceScope.PUBLIC),
        "p1", PolicyHistoryEventKind.RESOURCE_CHANGE, buildJsonObject { },
        PerspectiveEventDetail.ResourceChange(playerId = "p0", resource = "authored-cue", delta = cue, reason = null))

    fun state(history: List<PolicyHistoryEvent> = emptyList(), player: String = "p0",
        knowledge: PolicyKnowledgeState = PolicyKnowledgeState.empty(player), cards: List<PolicyCardView> = emptyList()): EpistemicState {
        val observation = PlayerObservationSnapshot(player, 1, "TEST", "TEST", "p0", "p0",
            listOf("p0", "p1").map { id -> PolicyPlayerView(id, "display", 20, 0, 3, 0, 0, PolicyManaPool(), id == "p0", id == "p0", false) },
            listOf(PolicyZoneView("p0", "BATTLEFIELD", false, cards.size, cards), PolicyZoneView("p1", "HAND", true, 1, emptyList())),
            emptyList(), pendingDecision = null, observationDigest = "fixture")
        return EpistemicState.capture(observation, history, PolicyHistoryCommitment.replay(history), knowledge, false)
    }

    fun site(state: EpistemicState = state(), candidates: List<SemanticChoice> = choices()) =
        DecisionSite.create(state, state.perspectivePlayerId, PolicyExpansion(candidates, true, candidates.size.toLong(), "fixture-v1"))

    fun choices() = listOf(false, true).map { answer -> SemanticChoice.create(kind = SemanticChoiceKind.DECISION,
        operationFamily = SemanticOperationFamily.DECISION_RESPONSE, display = SemanticChoiceDisplay("Answer"),
        canonicalPayload = buildJsonObject { put("answer", answer) }) }

    fun card(ref: String) = PolicyCardView(objectRef = ref, definitionId = "registry-key", name = "Raging Goblin", zone = "BATTLEFIELD",
        ownerId = "p0", controllerId = "p0", types = setOf("CREATURE"), subtypes = setOf("Goblin"), colors = setOf("RED"),
        keywords = setOf("HASTE"), manaCost = "{R}", manaValue = 1, oracleText = "Haste", power = 1, toughness = 1,
        tapped = false, summoningSick = false, faceDown = false, damageMarked = 0, counters = emptyMap(), attachedTo = null, attachments = emptyList())
}
