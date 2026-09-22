package org.mtgallium.agent.infoset.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class DecisionSiteTest {
    @Test fun `epistemic identity ignores action enumeration while site and information identities distinguish it`() {
        val firstExpansion = expansion(listOf(choice("a"), choice("b")))
        val secondExpansion = expansion(listOf(choice("b"), choice("a")), "another-enumerator")
        val first = information(firstExpansion)
        val second = information(secondExpansion)
        assertNotEquals(first.informationStateDigest, second.informationStateDigest)
        val a = site(first, firstExpansion)
        val b = site(second, secondExpansion)
        assertEquals(a.epistemic.epistemicDigest, b.epistemic.epistemicDigest)
        assertNotEquals(a.decisionSiteDigest, b.decisionSiteDigest)
        assertEquals(InformationStateRepresentationDigest.compute(first.observation.observationDigest,
            first.historyCommitment, first.knowledge.knowledgeDigest, "p0",
            firstExpansion.candidates.map { it.signature }, firstExpansion.proposalVersion), a.informationStateDigest)
        assertEquals(InformationStateRepresentationDigest.compute(second.observation.observationDigest,
            second.historyCommitment, second.knowledge.knowledgeDigest, "p0",
            secondExpansion.candidates.map { it.signature }, secondExpansion.proposalVersion), b.informationStateDigest)
        assertEquals(PolicyJson.format.encodeToString(first), PolicyJson.format.encodeToString(a.information()))
        assertEquals(PolicyJson.format.encodeToString(firstExpansion), PolicyJson.format.encodeToString(a.expansion))
    }

    @Test fun `proposal completeness and omissions bind decision context but not represented knowledge`() {
        val base = expansion(listOf(choice("a"), choice("b")))
        val info = information(base)
        val root = site(info, base)
        for (variant in listOf(base.copy(proposalVersion = "different"),
            base.copy(isExhaustive = false, omissionReasons = setOf(PolicyExpansionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA)),
            base.copy(isExhaustive = false, isProfileExhaustive = false,
                omissionReasons = setOf(PolicyExpansionOmissionReason.RESPONSE_LIMIT)))) {
            val site = site(info, variant)
            assertEquals(root.epistemic.epistemicDigest, site.epistemic.epistemicDigest)
            assertNotEquals(root.decisionSiteDigest, site.decisionSiteDigest)
        }
        val seedChanged = site(info, base.copy(proposalSeed = 1234L))
        assertEquals(root.decisionSiteDigest, seedChanged.decisionSiteDigest)
        assertEquals(1234L, seedChanged.expansion.proposalSeed)
    }

    @Test fun `actual observation remembered knowledge history and terminal facts change epistemic identity`() {
        val info = information(expansion(listOf(choice("a"))))
        val expected = EpistemicState.capture(info).epistemicDigest
        val event = PolicyHistoryEvent(eventId = 0, audience = PolicyAudience(PolicyAudienceScope.PUBLIC), actor = "p0",
            kind = PolicyHistoryEventKind.ACTION, payload = buildJsonObject { put("observed", "pass") })
        val changes = listOf(info.copy(observation = info.observation.copy(step = "UPKEEP")),
            info.copy(knowledge = info.knowledge.copy(deckCardCounts = mapOf("p0" to mapOf("Mountain" to 20)))),
            info.copy(history = listOf(event), historyCommitment = PolicyHistoryCommitment.replay(listOf(event))),
            info.copy(terminated = true, winnerId = "p0"))
        changes.forEach { assertNotEquals(expected, EpistemicState.capture(it).epistemicDigest) }
        assertEquals(expected, EpistemicState.capture(info.copy(informationStateDigest = "legacy-label",
            observation = info.observation.copy(observationDigest = "self-reported-label"),
            knowledge = info.knowledge.copy(knowledgeDigest = "self-reported-label"))).epistemicDigest)
    }

    @Test fun `epistemic capture refuses mismatched perspective and history`() {
        val menu = expansion(listOf(choice("a"), choice("b")))
        val info = information(menu)
        assertFailsWith<IllegalArgumentException> { EpistemicState.capture(info.copy(knowledge = PolicyKnowledgeState.empty("p1"))) }
        assertFailsWith<IllegalArgumentException> { EpistemicState.capture(info.copy(
            historyCommitment = PolicyHistoryCommitment(cursor = 0, digest = "a".repeat(64)))) }
    }

    @Test fun `captured values cannot be mutated through original or returned information collections`() {
        val payload = mutableMapOf<String, JsonElement>("choice" to JsonPrimitive("a"))
        val choices = mutableListOf(SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
            operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay("a"), canonicalPayload = JsonObject(payload)))
        val menu = expansion(choices)
        val info = information(menu)
        val site = site(info, menu)
        val before = PolicyJson.format.encodeToString(site.information())
        val digest = site.decisionSiteDigest
        payload["choice"] = JsonPrimitive("tampered")
        choices.clear()
        assertFailsWith<UnsupportedOperationException> { (site.information().candidates as MutableList<SemanticChoice>).clear() }
        assertFailsWith<UnsupportedOperationException> { (site.expansion.candidates as MutableList<SemanticChoice>).clear() }
        assertEquals(before, PolicyJson.format.encodeToString(site.information()))
        assertEquals(digest, site.decisionSiteDigest)
        assertEquals(1, site.expansion.candidates.size)
    }

    @Test fun `lazy site acquisition projects information once`() {
        val menu = expansion(listOf(choice("a")))
        val info = information(menu)
        var acquisitions = 0
        val request = DecisionSiteRequest.capture("p0", menu, { acquisitions++; EpistemicState.capture(info) })
        assertEquals(menu, request.expansion)
        assertEquals(0, acquisitions)
        assertSame(request.site(), request.site())
        assertEquals(1, acquisitions)
    }

    private fun choice(label: String) = SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay(label),
        canonicalPayload = buildJsonObject { put("choice", label) })
    private fun expansion(choices: List<SemanticChoice>, version: String = "fixture-expansion") =
        PolicyExpansion(choices, true, choices.size.toLong(), version)
    private fun site(information: InformationStateRepresentation, expansion: PolicyExpansion): DecisionSite =
        DecisionSite.create(EpistemicState.capture(information), requireNotNull(information.actingPlayerId), expansion)
    private fun information(expansion: PolicyExpansion): InformationStateRepresentation {
        val observation = PlayerObservationSnapshot("p0", 1, "MAIN", "PRECOMBAT_MAIN", "p0", "p0",
            emptyList(), emptyList(), emptyList(), currentTurnStateComplete = true, pendingDecision = null,
            observationDigest = "fixture-observation")
        val history = PolicyHistoryCommitment.empty()
        val knowledge = PolicyKnowledgeState.empty("p0")
        return InformationStateRepresentation(actingPlayerId = "p0", observation = observation,
            informationStateDigest = InformationStateRepresentationDigest.compute(observation.observationDigest, history,
                knowledge.knowledgeDigest, "p0", expansion.candidates.map { it.signature }, expansion.proposalVersion),
            historyCommitment = history, history = emptyList(), knowledge = knowledge,
            candidates = expansion.candidates, terminated = false)
    }
}
