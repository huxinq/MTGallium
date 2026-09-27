package org.mtgallium.research.workbench

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.RolloutCutoff
import org.mtgallium.agent.infoset.core.RolloutTurnHorizon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bounded, public-fixture search records. Absolute goldens await the first build on linuxbox. */
class SearchCharacterizationTest {
    @Test
    fun `seeded public positions give stable choices visits diagnostics and work digests`() {
        val deck = publicDeck()
        val scenarios = listOf(
            "default" to GamesPlan(decks = listOf(deck, deck), seed = 811L,
                startingHandSize = 7, skipMulligans = true, particles = 1, simulations = 2,
                searchDepth = 2),
            "current-information" to GamesPlan(decks = listOf(deck, deck), seed = 811L,
                startingHandSize = 7, skipMulligans = true, particles = 1, simulations = 2,
                searchDepth = 2, leaf = LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE)),
            "quiescence" to GamesPlan(decks = listOf(deck, deck), seed = 811L,
                startingHandSize = 7, skipMulligans = true, particles = 1, simulations = 2,
                searchDepth = 2, leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT,
                    RolloutCutoff.QUIESCENCE)),
            "turn-horizon" to GamesPlan(decks = listOf(deck, deck), seed = 811L,
                startingHandSize = 7, skipMulligans = true, particles = 1, simulations = 2,
                searchDepth = 2, rolloutTurnHorizon = RolloutTurnHorizon(1, 32)),
        )
        val lines = scenarios.map { (name, plan) ->
            val first = characterize(name, plan)
            assertEquals(first, characterize(name, plan), name)
            first.toString()
        }
        val output = Path.of("build", "characterization", "search.jsonl")
        Files.createDirectories(output.parent)
        val actual = lines.joinToString("\n", postfix = "\n")
        Files.writeString(output, actual)
        compareOrCaptureGolden(actual)
    }

    private fun characterize(name: String, plan: GamesPlan): JsonObject {
        val game = PythonGame.create(plan, buildRegistry(), "characterization-$name")
        var foundChoice = false
        var steps = 0
        while (steps < 64) {
            val menu = game.world.decisionContext().expansion.candidates
            if (menu.size > 1) {
                foundChoice = true
                break
            }
            check(game.world.step(menu.single()).accepted)
            steps++
        }
        check(foundChoice) { "No multi-action public-fixture decision within 64 steps" }
        val actor = requireNotNull(game.world.actorToAct())
        val information = game.world.informationState(actor)
        val selection = game.select("search", 901L)
        val search = selection.getValue("search").jsonObject
        val diagnostics = JsonObject(search.getValue("diagnostics").jsonObject - "evaluatorNanos")
        val visits = search.getValue("candidates").jsonArray.sumOf {
            it.jsonObject.getValue("visits").jsonPrimitive.content.toInt()
        }
        assertEquals(plan.simulations, visits, name)
        assertEquals(plan.simulations, diagnostics.getValue("simulations").jsonPrimitive.content.toInt(), name)
        assertTrue(search.getValue("candidates").jsonArray.isNotEmpty())
        val counters = diagnostics.filterKeys { it in setOf("searchWorldSteps", "evaluatorCalls",
            "transitionCacheHits", "transitionCacheMisses", "quiescenceForcedPasses",
            "rootRolloutDecisions", "opponentRolloutDecisions") }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(JsonObject(counters).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return buildJsonObject {
            put("scenario", name)
            put("choice", selection.getValue("choice"))
            put("candidates", search.getValue("candidates"))
            put("diagnostics", diagnostics)
            put("workDigest", digest)
            put("informationStateDigest", information.informationStateDigest)
            put("historyCommitment", researchJson.encodeToJsonElement(information.historyCommitment))
        }
    }

    private fun publicDeck(): Map<String, Int> {
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null) {
            val file = directory.resolve("fixtures/decks/mono-red-standard-2026-07-30.json")
            if (Files.exists(file)) return researchJson.parseToJsonElement(Files.readString(file))
                .jsonObject.getValue("mainDeck").jsonObject.mapValues { it.value.jsonPrimitive.content.toInt() }
            directory = directory.parent
        }
        error("Public Mono-Red fixture not found")
    }

    private fun compareOrCaptureGolden(actual: String) {
        val name = "search-characterization.jsonl"
        if (System.getenv("MTG_CAPTURE_GOLDENS") == "1") {
            val source = Path.of(requireNotNull(System.getenv("MTG_SOURCE_JSON")) {
                "Golden capture requires a tools/remote source snapshot"
            })
            val expectedSha = requireNotNull(System.getenv("MTG_GOLDEN_BASELINE_SHA")) {
                "Golden capture requires the recorded unretired baseline source SHA"
            }
            val provenance = researchJson.parseToJsonElement(Files.readString(source)).jsonObject
            check(provenance.getValue("commit").jsonPrimitive.content == expectedSha) {
                "Capture source SHA differs from the recorded unretired baseline"
            }
            check(provenance.getValue("diff").jsonPrimitive.content.isEmpty() &&
                provenance.getValue("status").jsonPrimitive.content.isEmpty()) {
                "Capture requires a clean committed source snapshot"
            }
            val capture = Path.of("build", "golden-capture", name)
            Files.createDirectories(capture.parent)
            Files.writeString(capture, actual)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(actual.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            Files.writeString(capture.resolveSibling("$name.source.json"), buildJsonObject {
                put("sourceSha", expectedSha)
                put("bytesSha256", digest)
            }.toString() + "\n")
            error("Captured $capture; review and pin it before comparison. Capture is not verification.")
        }
        val expected = requireNotNull(javaClass.getResourceAsStream("/goldens/$name")) {
            "Missing $name golden; capture and accept it on the recorded unretired baseline first"
        }.bufferedReader().use { it.readText() }
        val metadata = requireNotNull(javaClass.getResourceAsStream("/goldens/$name.source.json")) {
            "Missing $name.source.json; accept payload and provenance together"
        }.bufferedReader().use { researchJson.parseToJsonElement(it.readText()).jsonObject }
        val sourceSha = metadata.getValue("sourceSha").jsonPrimitive.content
        val bytesSha = metadata.getValue("bytesSha256").jsonPrimitive.content
        check(Regex("[0-9a-f]{40}").matches(sourceSha)) { "Invalid $name source SHA" }
        check(Regex("[0-9a-f]{64}").matches(bytesSha)) { "Invalid $name byte SHA-256" }
        val pinnedSha = MessageDigest.getInstance("SHA-256")
            .digest(expected.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(bytesSha, pinnedSha, "$name provenance does not match pinned bytes")
        assertEquals(expected, actual)
    }
}
