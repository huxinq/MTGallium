package org.mtgallium.agent.infoset.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class NativeSemanticValueTest {
    @Test fun `native epistemic state has no retained candidate or representation`() {
        val information = information()
        val state = EpistemicState.capture(information)
        assertTrue(EpistemicState::class.java.declaredFields.none {
            it.type == InformationStateRepresentation::class.java || it.name.contains("candidate", ignoreCase = true)
        })
        assertSame(state.observation, state.observation)
        assertSame(state.knowledge, state.knowledge)
        assertSame(state.history, state.history)
        val expansion = expansion(information.candidates)
        val site = DecisionSite.create(EpistemicState.capture(information), "p0", expansion)
        assertSame(site.expansion, site.expansion)
        assertSame(site.information(), site.information())
        assertEquals(information.copy(informationStateDigest = site.informationStateDigest), site.information())
        assertEquals(EpistemicState.capture(information.copy(candidates = emptyList())).epistemicDigest, state.epistemicDigest)
    }

    @Test fun `one immutable history owns an incremental commitment and is shared on repeated capture`() {
        val payload = mutableMapOf<String, JsonElement>("choice" to JsonPrimitive("pass"))
        val detailNames = mutableListOf("known-card")
        val event = PolicyHistoryEvent(0, PolicyAudience(PolicyAudienceScope.PUBLIC), "p0", PolicyHistoryEventKind.REVEAL,
            JsonObject(payload), PerspectiveEventDetail.Reveal(ownerId = "p0", zone = "HAND", cardNames = detailNames))
        val expected = PolicyHistoryCommitment.empty().append(event)
        val first = PolicyHistorySnapshot.empty().append(event)
        payload.clear(); detailNames.clear()
        assertEquals(expected, first.commitment)
        assertEquals(PolicyHistoryCommitment.replay(first), first.commitment)
        assertEquals(listOf("known-card"), (first[0].detail as PerspectiveEventDetail.Reveal).cardNames)
        val second = first.append(first[0].copy(eventId = 1))
        assertEquals(1, first.size); assertEquals(2, second.size)
        assertSame(first, PolicyHistorySnapshot.capture(first, first.commitment))
        assertSame(first[0], second[0])
        val base = information().copy(history = first, historyCommitment = first.commitment)
        assertSame(first, EpistemicState.capture(base).history)
        assertFailsWith<IllegalArgumentException> { PolicyHistorySnapshot.capture(second, first.commitment) }
        assertFailsWith<IllegalArgumentException> { first.append(first[0]) }
    }

    @Test fun `nested pending choice metadata and card collections cannot mutate captured knowledge`() {
        val targetNames = mutableListOf("target")
        val metadata = mutableMapOf<String, JsonElement>("nested" to JsonArray(targetNames.map(::JsonPrimitive)))
        val information = information().let { it.copy(observation = it.observation.copy(pendingDecision = PolicyPendingDecisionView(
            decisionKind = "Options", playerId = "p0", prompt = "Choose", phase = "MAIN", canRespond = true,
            choiceSpec = PolicyDecisionChoiceSpec.Options(targetNames, canCancel = false, metadata = JsonArray(listOf(JsonObject(metadata))))))) }
        val state = EpistemicState.capture(information)
        val encoded = PolicyJson.format.encodeToString(state.observation)
        metadata.clear(); targetNames.clear()
        assertEquals(encoded, PolicyJson.format.encodeToString(state.observation))
        val spec = state.observation.pendingDecision!!.choiceSpec as PolicyDecisionChoiceSpec.Options
        assertFailsWith<UnsupportedOperationException> { (spec.options as MutableList<String>).clear() }
        assertEquals(listOf("target"), spec.options)
    }

    private fun information(): InformationStateRepresentation {
        val observation = PlayerObservationSnapshot("p0", 1, "MAIN", "PRECOMBAT_MAIN", "p0", "p0", emptyList(), emptyList(), emptyList(),
            currentTurnStateComplete = true, pendingDecision = null, observationDigest = "authored-observation")
        val choice = SemanticChoice.create(SemanticChoiceKind.ACTION, SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay("Pass"), canonicalPayload = buildJsonObject { put("action", "pass") })
        return InformationStateRepresentation(actingPlayerId = "p0", observation = observation, informationStateDigest = "historical-digest",
            historyCommitment = PolicyHistoryCommitment.empty(), history = emptyList(), candidates = listOf(choice), terminated = false)
    }
    private fun expansion(choices: List<SemanticChoice>) = PolicyExpansion(choices, true, choices.size.toLong(), "authored-expansion")
}
