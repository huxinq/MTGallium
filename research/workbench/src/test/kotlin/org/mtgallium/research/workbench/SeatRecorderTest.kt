package org.mtgallium.research.workbench

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class SeatRecorderTest {
    private fun monoRed(): Map<String, Int> {
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null) {
            val file = directory.resolve("fixtures/decks/mono-red-standard-2026-07-30.json")
            if (Files.exists(file)) return researchJson.parseToJsonElement(Files.readString(file))
                .jsonObject.getValue("mainDeck").jsonObject.mapValues { it.value.jsonPrimitive.int }
            directory = directory.parent
        }
        error("Mono-Red deck fixture not found")
    }

    @Test fun `each seat logs what it is sent and every action it takes, forced ones included`() {
        val directory = Files.createTempDirectory("seat-recorder-")
        try {
            val deck = monoRed()
            val plan = ResearchGameConfig(decks = listOf(deck, deck), policies = listOf("heuristic", "heuristic"),
                seed = 11, threads = 1, maximumDecisions = 400, recordReplay = true, recordSeats = true)
            val output = directory.resolve("run")
            runGames(plan, output)
            val game = output.resolve("games/0")
            val truth = useJsonLines(game.resolve("truth.jsonl.gz")) { it.map { r -> r.jsonObject }.toList() }
            val accepted = useJsonLines(game.resolve("replay.jsonl.gz")) { it.drop(1).count { r -> r.jsonObject["accepted"]?.jsonPrimitive?.boolean == true } }
            assertEquals(accepted, truth.size, "one truth record per accepted engine action")
            assertEquals((1..truth.size).toList(), truth.map { it.getValue("step").jsonPrimitive.int })
            for (seat in listOf("p0", "p1")) {
                val log = useJsonLines(game.resolve("seat-$seat.jsonl.gz")) { it.map { r -> r.jsonObject }.toList() }
                val sentSteps = log.filter { "sent" in it }.map { it.getValue("step").jsonPrimitive.int }
                val actorSteps = truth.filter { it.getValue("actor").jsonPrimitive.content == seat }.map { it.getValue("step").jsonPrimitive.int }
                assertEquals(actorSteps, sentSteps, "$seat sends exactly its own accepted actions")
                assertTrue(log.all { it.keys.all { key -> key in setOf("step", "decision", "received", "sent") } })
                assertTrue(log.first().containsKey("received"))
                // Every engine action is followed by an update to both seats.
                val updates = setOf("stateUpdate", "stateDeltaUpdate")
                val updated = log.filter { "received" in it && it.getValue("received").jsonObject["type"]?.jsonPrimitive?.content in updates }
                    .map { it.getValue("step").jsonPrimitive.int }.toSet()
                assertEquals((0..truth.size).toSet(), updated)
            }
            assertTrue(truth.any { it.getValue("candidates").jsonPrimitive.int == 1 }, "forced decisions are recorded")
        } finally { directory.toFile().deleteRecursively() }
    }
}
