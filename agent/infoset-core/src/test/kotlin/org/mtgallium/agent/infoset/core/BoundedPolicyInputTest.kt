package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject

class BoundedPolicyInputTest {
    @Test
    fun `old exact evidence stays in knowledge while the neural event window remains bounded`() {
        val reveal = event(
            0,
            PerspectiveEventDetail.Reveal(
                ownerId = "p1",
                zone = "HAND",
                cardNames = listOf("Shock"),
                knowledgeObjectKeys = listOf("knowledge-object-0"),
            ),
        )
        val history = listOf(reveal) + (1L..100L).map { index ->
            event(
                index,
                PerspectiveEventDetail.TurnStructure(
                    turnNumber = index.toInt(),
                    phase = "MAIN",
                    step = "PRECOMBAT_MAIN",
                    activePlayerId = "p0",
                    priorityPlayerId = "p0",
                ),
            )
        }
        val information = information(history)

        val bounded = BoundedPolicyInputCompiler.compile(
            information,
            config = BoundedPolicyInputConfig(recentEventLimit = 8),
        )

        assertEquals(8, bounded.recentEvents.size)
        assertEquals(93, bounded.recentEventStartCursor)
        assertTrue(bounded.knowledge.knownObjects.any { it.cardName == "Shock" && it.zone == "HAND" })
        assertEquals(information, bounded.toInformationState(history))
    }

    @Test
    fun `candidate and byte limits fail closed`() {
        val information = information(listOf(event(0, null)))

        assertFailsWith<IllegalArgumentException> {
            BoundedPolicyInputCompiler.compile(
                information.copy(candidates = listOf(choice("a"), choice("b"))),
                config = BoundedPolicyInputConfig(candidateLimit = 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BoundedPolicyInputCompiler.compile(
                information,
                config = BoundedPolicyInputConfig(recentEventByteLimit = 1),
            )
        }
    }

    @Test
    fun `canonical digest rejects a modified bounded feature`() {
        val history = listOf(event(0, null))
        val bounded = BoundedPolicyInputCompiler.compile(information(history))

        bounded.requireValidDigest()
        assertFailsWith<IllegalArgumentException> {
            bounded.copy(winnerId = "p0").toInformationState(history)
        }
    }

    @Test
    fun `four thousand event ledger scans only the bounded suffix`() {
        val history = (0L until 4_096L).map { event(it, null) }
        val compiled = BoundedPolicyInputCompiler.compileWithMetrics(information(history))

        assertEquals(64, compiled.metrics.eventsExamined)
        assertEquals(64, compiled.metrics.recentEventCount)
        assertTrue(compiled.metrics.totalBytes <= 1024 * 1024)
        assertFailsWith<IllegalArgumentException> {
            BoundedPolicyInputCompiler.compile(information(history), config = BoundedPolicyInputConfig(totalByteLimit = 1))
        }
    }

    @Test
    fun `current bounded schema refuses a snapshot without authoritative turn state`() {
        val information = information(emptyList())

        assertFailsWith<IllegalArgumentException> {
            BoundedPolicyInputCompiler.compile(
                information.copy(
                    observation = information.observation.copy(currentTurnStateComplete = false),
                ),
            )
        }
    }

    @Test
    fun `serialized V4 input cannot masquerade as repaired current input`() {
        val current = BoundedPolicyInputCompiler.compile(information(emptyList()))
        val encoded = PolicyJson.format.encodeToString(BoundedPolicyInput.serializer(), current)
        val obsolete = encoded.replaceFirst(
            "\"schemaVersion\":$BOUNDED_POLICY_INPUT_SCHEMA_CURRENT",
            "\"schemaVersion\":$BOUNDED_POLICY_INPUT_SCHEMA_V4",
        )

        assertFailsWith<IllegalArgumentException> {
            PolicyJson.format.decodeFromString<BoundedPolicyInput>(obsolete)
        }
    }

    private fun information(history: List<PolicyHistoryEvent>): PolicyInformationState {
        val observation = observation()
        val decks = mapOf(
            "p0" to mapOf("Mountain" to 19, "Shock" to 2),
            "p1" to mapOf("Mountain" to 19, "Shock" to 2),
        )
        val knowledge = PolicyKnowledgeReducer.reduce("p0", decks, observation, history)
        val historyCommitment = PolicyHistoryCommitment.replay(history)
        return PolicyInformationState(
            actingPlayerId = "p0",
            observation = observation,
            informationStateDigest = PolicyJson.sha256("info:${historyCommitment.digest}"),
            historyCommitment = historyCommitment,
            history = history,
            knowledge = knowledge,
            candidates = listOf(choice("pass")),
            terminated = false,
        )
    }

    private fun observation() = PolicyObservation(
        perspectivePlayerId = "p0",
        turnNumber = 1,
        phase = "MAIN",
        step = "PRECOMBAT_MAIN",
        activePlayerId = "p0",
        priorityPlayerId = "p0",
        players = listOf(
            PolicyPlayerView("p0", "Root", 20, 0, 21, 0, 0, PolicyManaPool(), true, true, false),
            PolicyPlayerView("p1", "Opponent", 20, 1, 20, 0, 0, PolicyManaPool(), false, false, false),
        ),
        zones = listOf(
            PolicyZoneView("p0", "HAND", false, 0, emptyList()),
            PolicyZoneView("p0", "LIBRARY", true, 21, emptyList()),
            PolicyZoneView("p1", "HAND", true, 1, emptyList()),
            PolicyZoneView("p1", "LIBRARY", true, 20, emptyList()),
        ),
        stack = emptyList(),
        currentTurnStateComplete = true,
        pendingDecision = null,
        observationDigest = PolicyJson.sha256("bounded-observation"),
    )

    private fun event(id: Long, detail: PerspectiveEventDetail?) = PolicyHistoryEvent(
        eventId = id,
        audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
        actor = null,
        kind = PolicyHistoryEventKind.TURN_STRUCTURE,
        payload = buildJsonObject { },
        detail = detail,
    )

    private fun choice(id: String) = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.OTHER,
        display = SemanticChoiceDisplay(id),
        canonicalPayload = buildJsonObject { put("choice", kotlinx.serialization.json.JsonPrimitive(id)) },
    )
}
