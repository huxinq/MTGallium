package org.mtgallium.agent.infoset.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class NativeSemanticValueTest {
    @Test fun `native epistemic state has no retained candidate or representation`() {
        val information = information()
        val state = InformationState.capture(information)
        assertTrue(InformationState::class.java.declaredFields.none {
            it.type == InformationStateRepresentation::class.java || it.name.contains("candidate", ignoreCase = true)
        })
        assertSame(state.observation, state.observation)
        assertSame(state.knowledge, state.knowledge)
        assertSame(state.history, state.history)
        val menu = expansion(information.candidates)
        val site = DecisionPoint.create(InformationState.capture(information), "p0", menu)
        assertSame(site.menu, site.menu)
        assertSame(site.information(), site.information())
        assertEquals(information.copy(informationStateDigest = site.informationStateDigest), site.information())
        assertEquals(InformationState.capture(information.copy(candidates = emptyList())).epistemicDigest, state.epistemicDigest)
    }

    @Test fun `one immutable history owns an incremental commitment and is shared on repeated capture`() {
        val payload = mutableMapOf<String, JsonElement>("choice" to JsonPrimitive("pass"))
        val detailNames = mutableListOf("known-card")
        val event = ObservedEvent(0, EventAudience(EventAudienceScope.PUBLIC), "p0", ObservedEventKind.REVEAL,
            JsonObject(payload), ObservedEventDetail.Reveal(ownerId = "p0", zone = "HAND", cardNames = detailNames))
        val expected = HistoryHashChain.empty().append(event)
        val first = ObservationHistory.empty().append(event)
        payload.clear(); detailNames.clear()
        assertEquals(expected, first.commitment)
        assertEquals(HistoryHashChain.replay(first), first.commitment)
        assertEquals(listOf("known-card"), (first[0].detail as ObservedEventDetail.Reveal).cardNames)
        val second = first.append(first[0].copy(eventId = 1))
        assertEquals(1, first.size); assertEquals(2, second.size)
        assertSame(first, ObservationHistory.capture(first, first.commitment))
        assertSame(first[0], second[0])
        val base = information().copy(history = first, historyCommitment = first.commitment)
        assertSame(first, InformationState.capture(base).history)
        assertFailsWith<IllegalArgumentException> { ObservationHistory.capture(second, first.commitment) }
        assertFailsWith<IllegalArgumentException> { first.append(first[0]) }
    }

    @Test fun `nested pending choice metadata and card collections cannot mutate captured knowledge`() {
        val targetNames = mutableListOf("target")
        val metadata = mutableMapOf<String, JsonElement>("nested" to JsonArray(targetNames.map(::JsonPrimitive)))
        val information = information().let { it.copy(observation = it.observation.copy(pendingDecision = PendingDecisionView(
            decisionKind = "Options", playerId = "p0", prompt = "Choose", phase = "MAIN", canRespond = true,
            choiceSpec = PendingDecisionOptions.Options(targetNames, canCancel = false, metadata = JsonArray(listOf(JsonObject(metadata))))))) }
        val state = InformationState.capture(information)
        val encoded = CanonicalJson.format.encodeToString(state.observation)
        metadata.clear(); targetNames.clear()
        assertEquals(encoded, CanonicalJson.format.encodeToString(state.observation))
        val spec = state.observation.pendingDecision!!.choiceSpec as PendingDecisionOptions.Options
        assertFailsWith<UnsupportedOperationException> { (spec.options as MutableList<String>).clear() }
        assertEquals(listOf("target"), spec.options)
    }

    private fun information(): InformationStateRepresentation {
        val observation = PlayerObservationSnapshot("p0", 1, "MAIN", "PRECOMBAT_MAIN", "p0", "p0", emptyList(), emptyList(), emptyList(),
            currentTurnStateComplete = true, pendingDecision = null, observationDigest = "authored-observation")
        val choice = SemanticChoice.create(SemanticChoiceKind.ACTION, SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay("Pass"), canonicalPayload = buildJsonObject { put("action", "pass") })
        return InformationStateRepresentation(actingPlayerId = "p0", observation = observation, informationStateDigest = "historical-digest",
            historyCommitment = HistoryHashChain.empty(), history = emptyList(), candidates = listOf(choice), terminated = false)
    }
    private fun expansion(choices: List<SemanticChoice>) = ActionMenu(choices, true, choices.size.toLong(), "authored-expansion")
}
