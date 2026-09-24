package org.mtgallium.research.workbench

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

/** Built-in policies choose as they did before they became a provider. */
class NativePolicyReplayTest {
    private fun deck(): JsonObject {
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null) {
            val file = directory.resolve("fixtures/decks/mono-red-standard-2026-07-30.json")
            if (Files.exists(file)) return researchJson.parseToJsonElement(Files.readString(file))
                .jsonObject.getValue("mainDeck").jsonObject
            directory = directory.parent
        }
        error("Mono-Red deck fixture not found")
    }

    private fun summary(selection: JsonObject): String {
        val selected = selection.getValue("choice").jsonObject
        val intent = selected["actionIntent"]?.jsonObject
        val choice = selected.getValue("signature").jsonPrimitive.content + " " + selected["operationFamily"] + " " +
            intent?.get("sourceCardName") + " " + intent?.get("targetRelations")
        val search = selection["search"]?.takeUnless { it is JsonNull }?.jsonObject ?: return choice
        val candidates = search.getValue("candidates").jsonArray.map { it.jsonObject }.joinToString(",") {
            "${it.getValue("choice").jsonObject.getValue("signature").jsonPrimitive.content}:" +
                "${it.getValue("visits")}:${it.getValue("meanValue")}"
        }
        return "$choice [$candidates]"
    }

    /** Recorded from the Python protocol before the built-in policies moved behind [NativePolicyProvider]. */
    @Test fun `built-in policies replay their recorded same-seed games`() {
        val deck = deck()
        val connection = PythonResearchConnection()
        fun call(command: String, body: JsonObjectBuilder.() -> Unit = {}) =
            connection.request(buildJsonObject { put("command", command); body() })
        val probe = call("create") { putJsonObject("plan") {
            put("decks", JsonArray(listOf(deck, deck)))
            put("policies", JsonArray(listOf(JsonPrimitive("random"), JsonPrimitive("random"))))
        } }.jsonObject.getValue("game")
        val random = Random(11)
        val names = call("value-features") { put("game", probe); put("player", "p0") }.jsonObject.keys.sorted()
        val weights = buildJsonObject {
            put("bias", 0.05)
            putJsonObject("weights") { names.forEach { put(it, random.nextGaussian() * 0.05) } }
        }
        val small = mapOf("particles" to JsonPrimitive(2), "simulations" to JsonPrimitive(8))
        val games = listOf(
            listOf("search", "heuristic") to 105L to small + ("searchDepth" to JsonPrimitive(8)),
            listOf("random", "search") to 106L to small + mapOf("searchDepth" to JsonPrimitive(4),
                "valueWeights" to weights, "valueLink" to JsonPrimitive("tanh"), "opponentModel" to JsonPrimitive("heuristic")),
            listOf("production", "heuristic") to 104L to emptyMap<String, JsonElement>(),
            listOf("random", "production") to 107L to emptyMap<String, JsonElement>(),
        )
        val lines = mutableListOf<String>()
        for ((setup, settings) in games) {
            val (policies, seed) = setup
            val game = call("create") { putJsonObject("plan") {
                put("decks", JsonArray(listOf(deck, deck)))
                put("policies", JsonArray(policies.map(::JsonPrimitive)))
                put("seed", seed)
                settings.forEach { (key, value) -> put(key, value) }
            } }.jsonObject.getValue("game")
            repeat(600) {
                val status = call("status") { put("game", game) }.jsonObject
                if (status.getValue("terminal").jsonPrimitive.boolean) return@repeat
                val actor = status.getValue("actor").jsonPrimitive.content
                val policy = policies[actor.removePrefix("p").toInt()]
                val selection = call("select") { put("game", game); put("policy", policy) }.jsonObject
                lines += "$seed ${selection.getValue("index")} $actor $policy ${summary(selection)}"
                call("step") {
                    put("game", game); put("index", selection.getValue("index")); put("view", selection.getValue("view"))
                    put("choice", selection.getValue("choice")); put("record", false)
                }
            }
            lines += "$seed status ${call("status") { put("game", game) }}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n", postfix = "\n").toByteArray())
        assertEquals("7f9ca7e2b5f0c25c8e380b88b8c7669732a6beb62ded4106c87293b2f921053f",
            digest.joinToString("") { "%02x".format(it) })
    }
}
