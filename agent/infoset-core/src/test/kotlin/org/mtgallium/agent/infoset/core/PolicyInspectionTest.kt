package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PolicyInspectionTest {
    @Test
    fun `current inspection schema carries canonically ordered display-only art`() {
        val presentation = PolicyInspectionPresentation(
            cardImages = listOf(
                PolicyInspectionCardImage(
                    key = "name:Shock",
                    cardName = "Shock",
                    imageUri = "https://cards.scryfall.io/normal/front/a/b/shock.jpg",
                ),
            ),
        )
        val bundle = bundle().copy(presentation = presentation)
        val encoded = PolicyJson.format.encodeToString(PolicyInspectionBundle.serializer(), bundle)
        val decoded = PolicyJson.format.decodeFromString<PolicyInspectionBundle>(encoded)
        val informationJson = PolicyJson.format.encodeToString(
            PolicyInformationState.serializer(),
            bundle.informationState(0),
        )

        assertEquals(INSPECTION_SCHEMA_CURRENT, bundle.schemaVersion)
        assertEquals(bundle, decoded)
        assertFalse("imageUri" in informationJson)
        assertFalse("presentation" in informationJson)
    }

    @Test
    fun `legacy v1 is rejected by current-policy reconstruction`() {
        assertFailsWith<IllegalArgumentException> {
            bundle().copy(schemaVersion = INSPECTION_SCHEMA_V1)
        }
    }

    @Test
    fun `affected current-schema inspection header remains decodable`() {
        val affected = bundle().copy(evaluatorVersion = LeafEvaluator.MTGALLIUM_VISIBLE_V2.evaluatorId)
        val encoded = PolicyJson.format.encodeToString(PolicyInspectionBundle.serializer(), affected)

        val decoded = PolicyJson.format.decodeFromString<PolicyInspectionBundle>(encoded)

        assertEquals(INSPECTION_SCHEMA_CURRENT, decoded.schemaVersion)
        assertEquals(LeafEvaluator.MTGALLIUM_VISIBLE_V2.evaluatorId, decoded.evaluatorVersion)
    }

    @Test
    fun `v5 rejects seed-derived inspection identifiers`() {
        assertFailsWith<IllegalArgumentException> {
            bundle().copy(gameId = "inspection-117-p0")
        }
    }

    @Test
    fun `inspection art rejects non-Scryfall hosts and unstable catalog ordering`() {
        assertFailsWith<IllegalArgumentException> {
            PolicyInspectionCardImage("name:Shock", "Shock", "https://example.com/shock.jpg")
        }
        val first = PolicyInspectionCardImage(
            "name:Shock", "Shock", "https://cards.scryfall.io/normal/front/a/b/shock.jpg",
        )
        val second = PolicyInspectionCardImage(
            "name:Mountain", "Mountain", "https://cards.scryfall.io/normal/front/c/d/mountain.jpg",
        )
        assertFailsWith<IllegalArgumentException> {
            PolicyInspectionPresentation(cardImages = listOf(first, second))
        }
    }

    private fun bundle(): PolicyInspectionBundle {
        val observation = PolicyObservation(
            perspectivePlayerId = "p0",
            turnNumber = 1,
            phase = "BEGINNING",
            step = "UPKEEP",
            activePlayerId = "p0",
            priorityPlayerId = null,
            players = emptyList(),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            observationDigest = "observation",
        )
        val frame = PolicyInspectionFrame(
            frameIndex = 0,
            afterDecisionIndex = null,
            actingPlayerId = null,
            observation = observation,
            knowledge = PolicyKnowledgeState.empty("p0"),
            candidates = emptyList(),
            historyLength = 0,
            historyCommitment = PolicyHistoryCommitment.empty(),
            informationStateDigest = "information",
            terminated = true,
            winnerId = null,
        )
        return PolicyInspectionBundle(
            gameId = "00000000-0000-4000-8000-000000000001",
            createdAtUtc = "2026-08-24T00:00:00Z",
            outerCommit = "outer",
            argentumCommit = "fork",
            deckManifestHash = "deck",
            cardPoolHash = "pool",
            profileManifestHash = "profile",
            perspectivePlayerId = "p0",
            policyVersion = "policy",
            evaluatorVersion = "evaluator",
            beliefVersion = "belief",
            opponentModelVersion = "opponent",
            ledger = emptyList(),
            frames = listOf(frame),
            outcome = PolicyInspectionOutcome(
                decisions = 0,
                terminated = true,
                truncated = false,
                winnerId = null,
                resultByPlayer = mapOf("p0" to 0.0, "p1" to 0.0),
            ),
        )
    }
}
