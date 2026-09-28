package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in: the cost of per-seat card names on real heuristic Mono-Red mirror games. For every
 * engine transition and seat it times the same update three ways: the in-process path (engine ids),
 * the browser path for a seat with no names, and the browser path for a seat that has a name (it was
 * shown one opponent library card that then went out of sight). That seat's messages are renamed
 * once the card is back in sight.
 * Set SEAT_RENAMING_OUTPUT (a private directory) and optionally SEAT_RENAMING_GAMES.
 */
class SeatRenamingCostTest {
    private fun deck(): Map<String, Int> {
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null) {
            val file = directory.resolve("fixtures/decks/mono-red-standard-2026-07-30.json")
            if (Files.exists(file)) return researchJson.parseToJsonElement(Files.readString(file))
                .jsonObject.getValue("mainDeck").jsonObject.mapValues { it.value.jsonPrimitive.content.toInt() }
            directory = directory.parent
        }
        error("Mono-Red deck fixture not found")
    }

    @Test fun seatRenamingCost() {
        val outputName = System.getenv("SEAT_RENAMING_OUTPUT")
        assumeTrue(outputName != null, "Set SEAT_RENAMING_OUTPUT to a private directory")
        val output = Path.of(outputName!!)
        Files.createDirectories(output)
        val games = System.getenv("SEAT_RENAMING_GAMES")?.toInt() ?: 8
        val registry = buildRegistry()
        val deck = deck()
        val ids = ListSerializer(EntityId.serializer())
        output.resolve("updates.tsv").toFile().bufferedWriter().use { updates ->
            output.resolve("inbound.tsv").toFile().bufferedWriter().use { inbound ->
                updates.appendLine("game\ttransition\tseat\tevents\tengine_ns\tplain_ns\tnamed_ns")
                inbound.appendLine("game\ttransition\tplain_ns\tnamed_ns")
                for (game in 0 until games) {
                    val seed = 700L + game
                    val played = PythonGame.create(
                        ResearchGameConfig(decks = listOf(deck, deck), seed = seed, threads = 1), registry, "cost-$game")
                    val world = played.world
                    val initial = world.trueState()
                    val seats = initial.turnOrder
                    val engine = GameSession(cardRegistry = registry)
                    val plain = GameSession(cardRegistry = registry)
                    val named = GameSession(cardRegistry = registry)
                    // Each named seat is shown the top of its opponent's library, which then goes out of sight.
                    val shown = seats.associateWith { seat -> initial.getLibrary(seats.first { it != seat }).first() }
                    for (seat in seats) {
                        val card = shown.getValue(seat)
                        named.injectStateForTesting(initial.updateEntity(card) { it.with(RevealedToComponent.to(seat)) }, emptyMap())
                        named.createStateUpdate(seat, emptyList())
                        named.injectStateForTesting(initial, emptyMap())
                        named.clearLastSentState(seat)
                        named.createStateUpdate(seat, emptyList())
                        assertEquals(null, named.fromSeat(seat, listOf(card), ids), "The named seat must have renamed its card")
                    }
                    var transition = 0
                    fun timed(session: GameSession, state: GameState, seat: EntityId, events: List<com.wingedsheep.engine.core.GameEvent>, engineIds: Boolean): Long {
                        session.injectStateForTesting(state, emptyMap())
                        val started = System.nanoTime()
                        session.createStateUpdate(seat, events, engineIds)
                        return System.nanoTime() - started
                    }
                    val result = playGame(world, played.players, seed, rawTrace = { step ->
                        transition++
                        for (seat in seats) {
                            val engineNs = timed(engine, step.afterState, seat, step.events, true)
                            val plainNs = timed(plain, step.afterState, seat, step.events, false)
                            val namedNs = timed(named, step.afterState, seat, step.events, false)
                            updates.appendLine(listOf(game, transition, seats.indexOf(seat), step.events.size,
                                engineNs, plainNs, namedNs).joinToString("\t"))
                        }
                        val actor = step.action.playerId
                        val plainStart = System.nanoTime()
                        plain.fromSeat(actor, step.action, GameAction.serializer())
                        val plainNs = System.nanoTime() - plainStart
                        val namedStart = System.nanoTime()
                        named.fromSeat(actor, step.action, GameAction.serializer())
                        val namedNs = System.nanoTime() - namedStart
                        inbound.appendLine(listOf(game, transition, plainNs, namedNs).joinToString("\t"))
                    })
                    println("SEAT_RENAMING_GAME\t$game\t${result.status}\t$transition")
                    updates.flush(); inbound.flush()
                }
            }
        }
        assertTrue(Files.size(output.resolve("updates.tsv")) > 0)
    }
}
