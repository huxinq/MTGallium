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
import org.mtgallium.agent.infoset.planning.LeafEvaluationConfig
import org.mtgallium.agent.infoset.planning.LeafEvaluationMethod
import org.mtgallium.agent.infoset.planning.RolloutCutoff
import org.mtgallium.agent.infoset.planning.RolloutTurnHorizon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bounded, public-fixture search records. Absolute goldens await the first build on linuxbox. */
class SearchCharacterizationTest {
    @Test
    fun `seeded public positions give stable choices visits diagnostics and work digests`() {
        val deck = publicDeck()
        val scenarios = listOf(
            "default" to ResearchGameConfig(decks = listOf(deck, deck), seed = 811L,
                startingHandSize = 7, skipMulligans = true, particles = 1, simulations = 2,
                searchDepth = 2),
            "current-information" to ResearchGameConfig(decks = listOf(deck, deck), seed = 811L,
                startingHandSize = 7, skipMulligans = true, particles = 1, simulations = 2,
                searchDepth = 2, leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE)),
            "quiescence" to ResearchGameConfig(decks = listOf(deck, deck), seed = 811L,
                startingHandSize = 7, skipMulligans = true, particles = 1, simulations = 2,
                searchDepth = 2, leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT,
                    RolloutCutoff.QUIESCENCE)),
            "turn-horizon" to ResearchGameConfig(decks = listOf(deck, deck), seed = 811L,
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
        compareOrCaptureGolden("search-characterization.jsonl", actual)
    }

    private fun characterize(name: String, plan: ResearchGameConfig): JsonObject {
        val game = PythonGame.create(plan, buildRegistry(), "characterization-$name")
        var foundChoice = false
        var steps = 0
        while (steps < 64) {
            val menu = game.world.decisionContext().menu.candidates
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
}

/** Compares with a committed golden; MTG_CAPTURE_GOLDENS=1 writes a candidate under build/ instead. */
internal fun compareOrCaptureGolden(name: String, actual: String) {
    if (System.getenv("MTG_CAPTURE_GOLDENS") == "1") {
        val output = Path.of("build", "golden-capture", name)
        Files.createDirectories(output.parent)
        Files.writeString(output, actual)
        error("Captured $output; review it before replacing the golden")
    }
    val expected = requireNotNull(object {}.javaClass.getResourceAsStream("/goldens/$name")) {
        "Missing golden $name"
    }.bufferedReader().use { it.readText() }
    assertEquals(expected, actual, name)
}
