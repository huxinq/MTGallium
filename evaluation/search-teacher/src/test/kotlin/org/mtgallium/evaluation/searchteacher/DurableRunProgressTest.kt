package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DurableRunProgressTest {
    @Test
    fun `paired experiments write the durable-run v1 progress wire format`() {
        val path = Files.createTempDirectory("durable-progress-test-").resolve("progress.json")

        publishDurableRunProgress(path, completed = 7, total = 50, phase = "paired run", detail = "completed pair 6")

        val progress = evidenceJson.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals(
            setOf("schemaVersion", "updatedAt", "completed", "total", "unit", "phase", "detail"),
            progress.keys,
        )
        assertEquals("1", progress.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("7", progress.getValue("completed").jsonPrimitive.content)
        assertEquals("50", progress.getValue("total").jsonPrimitive.content)
        assertEquals("pairs", progress.getValue("unit").jsonPrimitive.content)
        assertEquals("paired run", progress.getValue("phase").jsonPrimitive.content)
        assertEquals("completed pair 6", progress.getValue("detail").jsonPrimitive.content)
    }
}
