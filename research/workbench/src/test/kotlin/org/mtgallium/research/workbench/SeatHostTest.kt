package org.mtgallium.research.workbench

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class SeatHostTest {
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

    /** Plays the first listed action that isn't a mana ability; keeps every hand. */
    private class FirstActionAgent : SeatAgent {
        var last: JsonObject? = null
        var received = 0
        override fun receive(message: String) {
            received++
            val m = researchJson.parseToJsonElement(message).jsonObject
            if (m["type"]?.jsonPrimitive?.content != "error") last = m
        }
        override fun act(): String {
            val m = requireNotNull(last)
            return when (m.getValue("type").jsonPrimitive.content) {
                "mulliganDecision" -> """{"type":"KeepHand","playerId":"${seat()}"}"""
                "chooseBottomCards" -> buildJsonObject {
                    put("type", "BottomCards"); put("playerId", seat())
                    put("cardIds", JsonArray(m.getValue("hand").jsonArray.take(m.getValue("cardsToPutOnBottom").jsonPrimitive.int)))
                }.toString()
                else -> {
                    val pending = m["pendingDecision"]
                    if (pending is JsonObject) {
                        check(pending.getValue("type").jsonPrimitive.content == "SelectCardsDecision") { "Unexpected decision: $pending" }
                        val count = pending.getValue("minSelections").jsonPrimitive.int
                        return buildJsonObject {
                            put("type", "SubmitDecision"); put("playerId", seat())
                            putJsonObject("response") {
                                put("type", "CardsSelectedResponse"); put("decisionId", pending.getValue("id"))
                                put("selectedCards", JsonArray(pending.getValue("options").jsonArray.take(count)))
                            }
                        }.toString()
                    }
                    val actions = m.getValue("legalActions").jsonArray.map { it.jsonObject }
                    val chosen = actions.firstOrNull { it["isManaAbility"]?.jsonPrimitive?.boolean != true &&
                        it["requiresTargets"]?.jsonPrimitive?.boolean != true && it.getValue("action").jsonObject["type"]?.jsonPrimitive?.content == "PlayLand" }
                        ?: actions.first { it.getValue("action").jsonObject["type"]?.jsonPrimitive?.content in setOf("PassPriority", "DeclareAttackers", "DeclareBlockers") }
                    chosen.getValue("action").toString()
                }
            }
        }
        var seatId: String? = null
        private fun seat(): String = requireNotNull(seatId)
    }

    @Test fun `a seat agent plays a whole game through its seat`() {
        val deck = monoRed()
        val plan = ResearchGameConfig(decks = listOf(deck, deck), policies = listOf("heuristic", "heuristic"), seed = 12, threads = 1)
        val registry = buildRegistry()
        val game = PythonGame.create(plan, registry, "seat-agent")
        val agent = FirstActionAgent().also { it.seatId = game.world.trueState().turnOrder[1].value }
        val players = game.players.toMutableMap()
        players["p1"] = seatPlayer(agent)
        val host = SeatHost(registry, game.world.trueState(), mapOf("p1" to agent), verify = true)
        val result = playGame(game.world, players, 12, maximumDecisions = 3000, seats = host)
        assertEquals(GameStatus.TERMINAL, result.status)
        assertEquals(0, host.fallbacks)
        assertTrue(agent.received > 50)
    }
}
