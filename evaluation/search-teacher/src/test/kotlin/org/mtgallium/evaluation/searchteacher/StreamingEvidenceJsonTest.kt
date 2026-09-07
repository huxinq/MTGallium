package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Tag

@Tag("public-source")
class StreamingEvidenceJsonTest {
    @Serializable data class Payload(val text: String, val values: List<Double?>, val nested: List<List<Int>>)

    @Test fun `streamed JSON preserves historical bytes defaults Unicode and strict decoding`() {
        val path = createTempDirectory("streamed-evidence").resolve("report.json")
        val payload = Payload("quoted \"value\" and Unicode 雪 🧪\n", listOf(null, -0.0, .1, 1e-12), List(500) { listOf(it, -it) })
        writeEvidenceJsonStream(path, payload, Payload.serializer())
        assertEquals(evidenceJson.encodeToString(payload) + "\n", Files.readString(path))
        assertEquals(payload, readEvidenceJson(path, Payload.serializer()))
        Files.writeString(path, "{\"unknown\":true}")
        assertFails { readEvidenceJson(path, Payload.serializer()) }
    }
}
