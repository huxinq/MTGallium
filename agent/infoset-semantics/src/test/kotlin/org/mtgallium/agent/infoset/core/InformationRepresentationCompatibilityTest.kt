package org.mtgallium.agent.infoset.core

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Canonical representation JSON retains its exact field vocabulary across source renames. */
class InformationRepresentationCompatibilityTest {
    private val legacySnapshotJson = """
        {"perspectivePlayerId":"p0","turnNumber":1,"phase":"MAIN","step":"MAIN",
         "activePlayerId":"p0","priorityPlayerId":"p0","players":[],"zones":[],
         "stack":[],"combat":null,"currentTurnStateComplete":false,
         "pendingDecision":null,"observationDigest":"snapshot"}
    """.trimIndent()

    @Test
    fun `snapshot round trips canonical JSON bytes`() {
        val snapshot = PolicyJson.format.decodeFromString(
            PlayerObservationSnapshot.serializer(), legacySnapshotJson,
        )
        assertEquals(
            PolicyJson.canonical(PolicyJson.format.parseToJsonElement(legacySnapshotJson)),
            PolicyJson.canonical(PolicyJson.format.encodeToJsonElement(PlayerObservationSnapshot.serializer(), snapshot)),
        )
    }

    @Test
    fun `information representation retains exact wire fields`() {
        val information = InformationStateRepresentation(
            actingPlayerId = "p0",
            observation = PolicyJson.format.decodeFromString(PlayerObservationSnapshot.serializer(), legacySnapshotJson),
            informationStateDigest = "information",
            historyCommitment = PolicyHistoryCommitment.empty(),
            history = emptyList(), candidates = emptyList(), terminated = false,
        )
        val serializer = InformationStateRepresentation.serializer()
        val encoded = PolicyJson.format.encodeToJsonElement(serializer, information) as JsonObject
        assertEquals(
            setOf("schemaVersion", "actingPlayerId", "observation", "informationStateDigest",
                "historyCommitment", "history", "knowledge", "candidates", "candidateSchemaVersion",
                "terminated", "winnerId"),
            encoded.keys,
        )
        assertEquals(information, PolicyJson.format.decodeFromJsonElement(InformationStateRepresentation.serializer(), encoded))
    }
}
