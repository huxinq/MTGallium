package org.mtgallium.evaluation.searchteacher.replay

import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CanonicalReplayTest {
    @Test
    fun `canonical JSON patches reconstruct object and array changes exactly`() {
        val before = JsonObject(
            mapOf(
                "kept" to JsonPrimitive(1),
                "removed" to JsonPrimitive(true),
                "array" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("d"))),
            )
        )
        val after = JsonObject(
            mapOf(
                "added" to JsonPrimitive("new"),
                "kept" to JsonPrimitive(2),
                "array" to JsonArray(
                    listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"), JsonPrimitive("d"))
                ),
            )
        )

        val patch = ReplayCanonicalJson.diff(before, after)

        assertEquals(ReplayCanonicalJson.canonicalize(after), ReplayCanonicalJson.apply(before, patch))
        assertEquals(1, patch.filterIsInstance<ReplayPatchSplice>().size)
    }

    @Test
    fun `recorded choices and states reconstruct without engine re-execution`() {
        val initial = GameState(turnNumber = 1)
        val recorder = CanonicalReplayRecorder(
            gameId = "game-1",
            createdAtUtc = "2026-08-26T00:00:00Z",
            engineVersion = "test",
            producer = "replay-test",
            players = listOf("p0", "p1"),
            initialState = initial,
        )
        val rejected = recorder.appendAction(
            origin = ReplayTransitionOrigin.PLAYER,
            action = PassPriority(EntityId("p0")),
            accepted = false,
            rejectionReason = "not your priority",
            resultingState = initial,
        )
        val terminalState = initial.copy(turnNumber = 2, gameOver = true, winnerId = EntityId("p0"))
        val accepted = recorder.appendAction(
            origin = ReplayTransitionOrigin.PLAYER,
            action = PassPriority(EntityId("p0")),
            accepted = true,
            resultingState = terminalState,
        )
        val terminal = recorder.finish(
            status = ReplayCompletionStatus.COMPLETE,
            finalState = terminalState,
            winnerId = "p0",
        )

        val replay = CanonicalReplayReconstructor.reconstruct(
            listOf(recorder.header, rejected, accepted, terminal)
        )

        assertEquals(initial, replay.stateAt(0))
        assertEquals(initial, replay.stateAt(1))
        assertEquals(terminalState, replay.stateAt(2))
        assertEquals(
            listOf(PassPriority(EntityId("p0")), PassPriority(EntityId("p0"))),
            replay.transitions.map { it.action },
        )
        assertEquals(0, assertIs<ReplayPatchedState>(rejected.state).operations.size)
    }

    @Test
    fun `tampered state patches fail their state digest`() {
        val initial = GameState()
        val recorder = CanonicalReplayRecorder(
            gameId = "tamper",
            createdAtUtc = "2026-08-26T00:00:00Z",
            engineVersion = "test",
            producer = "replay-test",
            players = listOf("p0"),
            initialState = initial,
        )
        val final = initial.copy(turnNumber = 1, gameOver = true)
        val transition = recorder.appendAction(
            origin = ReplayTransitionOrigin.PLAYER,
            action = PassPriority(EntityId("p0")),
            accepted = true,
            resultingState = final,
        )
        val terminal = recorder.finish(ReplayCompletionStatus.COMPLETE, final)
        val changedEncoding = ReplayPatchedState(listOf(ReplayPatchSet("/turnNumber", JsonPrimitive(99))))
        val unsignedTampered = transition.copy(state = changedEncoding, recordDigest = "")
        val tampered = unsignedTampered.copy(recordDigest = ReplayRecordDigests.of(unsignedTampered))
        val unsignedTerminal = terminal.copy(previousRecordDigest = tampered.recordDigest, recordDigest = "")
        val chainedTerminal = unsignedTerminal.copy(recordDigest = ReplayRecordDigests.of(unsignedTerminal))

        assertFailsWith<IllegalArgumentException> {
            CanonicalReplayReconstructor.reconstruct(listOf(recorder.header, tampered, chainedTerminal))
        }
    }

    @Test
    fun `validated prefixes resume and force a full checkpoint every 128 transitions`() {
        val initial = GameState()
        val recorder = CanonicalReplayRecorder(
            gameId = "resume",
            createdAtUtc = "2026-08-26T00:00:00Z",
            engineVersion = "test",
            producer = "replay-test",
            players = listOf("p0"),
            initialState = initial,
        )
        val transitions = (0..128).map { index ->
            recorder.appendAction(
                origin = ReplayTransitionOrigin.PLAYER,
                action = PassPriority(EntityId("p0")),
                accepted = true,
                resultingState = initial.copy(turnNumber = index + 1),
            )
        }
        val prefix = listOf(recorder.header) + transitions

        assertEquals(initial.copy(turnNumber = 129), CanonicalReplayReconstructor.reconstructPrefix(prefix).stateAt(129))
        assertIs<ReplayFullState>(transitions[128].state)
        assertEquals(
            129,
            CanonicalReplayRecorder.resume(prefix).appendAction(
                origin = ReplayTransitionOrigin.PLAYER,
                action = PassPriority(EntityId("p0")),
                accepted = true,
                resultingState = initial.copy(turnNumber = 130),
            ).ordinal,
        )
    }
}
