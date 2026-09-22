package org.mtgallium.research.workbench

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir

/** Inspecting one finished frame does not require accepting an unfinished later record. */
class ResearchStreamingTest {
    @TempDir lateinit var temporary: Path

    @Test fun `state extraction stops at the requested frame rather than parsing the entire recording`() {
        for (extension in listOf("jsonl", "jsonl.gz")) {
            val input = temporary.resolve("replay.$extension")
            val state = buildJsonObject { put("publicFixture", "frame already written") }
            openJsonLines(input).use { writer ->
                writer.writeRecord(buildJsonObject { put("index", 0); put("state", state) })
                writer.write("{\"index\":1,\"state\":") // interrupted subsequent JSON record
            }
            val output = temporary.resolve("state-$extension.json")
            main(arrayOf("replay-state", input.toString(), "0", output.toString()))
            assertEquals(state, readJson<JsonObject>(output))
            // An unavailable frame cannot be fabricated from the incomplete tail.
            assertFails { main(arrayOf("replay-state", input.toString(), "1",
                temporary.resolve("missing-$extension.json").toString())) }
            assertFalse(Files.exists(temporary.resolve("missing-$extension.json")))
        }
    }
}
