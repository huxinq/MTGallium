package org.mtgallium.research.workbench

import kotlinx.serialization.json.*
import kotlin.test.*

class ComparisonChoiceSeedTest {
    private fun run(seed: Long?): Pair<JsonElement, JsonElement> {
        val connection = PythonResearchConnection()
        val created = connection.request(buildJsonObject {
            put("command", "create")
            put("plan", buildJsonObject {
                put("seed", 31); put("startingHandSize", 3)
                put("policies", buildJsonArray { add("random"); add("random") })
                put("decks", buildJsonArray { repeat(2) {
                    add(buildJsonObject { put("Mountain", 8); put("Lightning Bolt", 4) })
                } })
            })
        }).jsonObject
        fun observation() = connection.request(buildJsonObject {
            put("command", "information"); put("game", created.getValue("game")); put("player", "p0")
        }).jsonObject.getValue("observation")
        val before = observation()
        val result = connection.request(buildJsonObject {
            put("command", "compare"); put("game", created.getValue("game"))
            put("candidateSeat", "p0"); put("incumbent", "random"); put("maximumDecisions", 1)
            if (seed != null) put("choiceSeed", seed)
        }).jsonObject
        if (seed != null) assertEquals(seed, result.getValue("choiceSeed").jsonPrimitive.long)
        return before to observation()
    }

    @Test fun `choice streams reproduce independently without changing the deal`() {
        val results = (0L..15L).map { run(it) }
        assertEquals(1, results.map { it.first }.distinct().size)
        assertTrue(results.map { it.second }.distinct().size > 1)
        assertEquals(run(9), run(9))
        assertEquals(run(null), run(31))
    }
}
