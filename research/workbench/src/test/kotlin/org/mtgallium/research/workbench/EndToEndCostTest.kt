package org.mtgallium.research.workbench

// seat-path begin
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.builtins.ListSerializer
// seat-path end
import org.mtgallium.agent.infoset.argentum.ArgentumRawTransition
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Opt-in: end-to-end cost of heuristic Mono-Red mirror games, as `runGames` plays them. With
 * E2E_SEAT_PATH=1 a GameSession also hosts each game the browser way: every accepted engine action
 * goes in through the actor's seat, in its own card names, and both seats get their update.
 * Set E2E_OUTPUT (a private directory); E2E_CELLS lists policy:seatPath[:games[:warmup]] cells
 * (search:0,search:1:16:2), where a search candidate plays the heuristic with alternating seats.
 * E2E_GAMES and E2E_WARMUP are the default sizes, E2E_THREADS the parallel games.
 */
class EndToEndCostTest {
    private val cpu = ManagementFactory.getThreadMXBean()

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

    private class Played(val row: String, val decisions: List<String>, val seatSteps: List<String>)

    @Test fun endToEndCost() {
        val outputName = System.getenv("E2E_OUTPUT")
        assumeTrue(outputName != null, "Set E2E_OUTPUT to a private directory")
        val output = Path.of(outputName!!)
        Files.createDirectories(output)
        val games = System.getenv("E2E_GAMES")?.toInt() ?: 32
        val threads = System.getenv("E2E_THREADS")?.toInt() ?: 8
        val warmup = System.getenv("E2E_WARMUP")?.toInt() ?: threads
        val cells = (System.getenv("E2E_CELLS") ?: "search:0").split(",").map { it.split(":") }
        val registry = buildRegistry()
        val deck = deck()

        fun play(policy: String, seatPath: Boolean, game: Int, seed: Long, verify: Boolean): Played {
            val candidateSeat = game % 2
            val policies = MutableList(2) { "heuristic" }.also { it[candidateSeat] = policy }
            val cpuStarted = cpu.currentThreadCpuTime
            val wallStarted = System.nanoTime()
            val played = PythonGame.create(ResearchGameConfig(decks = listOf(deck, deck), policies = policies, seed = seed, threads = 1),
                registry, "e2e-$game")
            val world = played.world
            val candidate = played.players.keys.elementAt(candidateSeat)
            val actors = HashMap<Int, String>()
            val decisions = ArrayList<String>()
            val digest = MessageDigest.getInstance("SHA-256")
            var rawTrace: ((ArgentumRawTransition) -> Unit)? = null
            // seat-path begin
            val host = if (seatPath) BrowserHost(registry, world.trueState(), verify) else null
            rawTrace = host?.let { it::accept }
            // seat-path end
            val result = playGame(world, played.players, seed, beforeChoice = { _, context, index -> actors[index] = context.actor },
                record = { decision ->
                val role = if (actors[decision.index] == candidate) "candidate" else "opponent"
                decisions += listOf(game, decision.index, role, decision.decisionNanos).joinToString("\t")
                digest.update("${decision.index}:${decision.selectedIndex};".toByteArray())
            }, rawTrace = rawTrace)
            val cpuNs = cpu.currentThreadCpuTime - cpuStarted
            val wallNs = System.nanoTime() - wallStarted
            digest.update("${result.status}:${result.payoffs}".toByteArray())
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
            var executeNs = 0L
            var updateNs = 0L
            var seatUpdates = 0
            val seatSteps = ArrayList<String>()
            // seat-path begin
            if (host != null) {
                executeNs = host.executeNs
                updateNs = host.updateNs
                seatUpdates = host.updates
                host.steps.forEachIndexed { i, (execute, update) -> seatSteps += "$game\t$i\t$execute\t$update" }
            }
            // seat-path end
            val row = listOf(policy, if (seatPath) 1 else 0, game, seed, candidateSeat, candidate, result.status,
                result.decisions, result.payoffs?.get(candidate) ?: "", cpuNs, wallNs, executeNs, updateNs, seatUpdates, fingerprint)
                .joinToString("\t")
            return Played(row, decisions, seatSteps)
        }

        for (cell in cells) {
            val policy = cell[0]
            val seatPath = cell.getOrNull(1) == "1"
            val games = cell.getOrNull(2)?.toInt() ?: games
            val warmup = cell.getOrNull(3)?.toInt() ?: warmup
            val name = "$policy-${if (seatPath) "seat" else "plain"}"
            // Warm-up games are not recorded; with the seat path they also check that the host
            // reproduces every engine state the world reaches.
            parallelMap((0 until warmup).toList(), threads) { play(policy, seatPath, it, 900L + it, verify = true) }
            val started = System.nanoTime()
            val played = parallelMap((0 until games).toList(), threads) { play(policy, seatPath, it, 700L + it, verify = false) }
            val cellWallNs = System.nanoTime() - started
            Files.write(output.resolve("$name-games.tsv"), listOf("policy\tseat_path\tgame\tseed\tcandidate_seat\tcandidate\tstatus\t" +
                "decisions\tcandidate_payoff\tcpu_ns\twall_ns\tseat_execute_ns\tseat_update_ns\tseat_updates\tfingerprint") + played.map { it.row })
            Files.write(output.resolve("$name-decisions.tsv"), listOf("game\tdecision\trole\tdecision_ns") + played.flatMap { it.decisions })
            if (seatPath) Files.write(output.resolve("$name-seat-steps.tsv"), listOf("game\tstep\texecute_ns\tupdate_ns") + played.flatMap { it.seatSteps })
            Files.writeString(output.resolve("$name-cell.txt"), "games\t$games\nthreads\t$threads\nwall_ns\t$cellWallNs\n")
            println("E2E_CELL\t$name\t$games games\t${cellWallNs / 1e9} s")
        }
    }
}

// seat-path begin
/**
 * A GameSession hosting the world's game as a browser game: each accepted engine action is
 * submitted by its actor in that seat's card names, and each seat is sent its update. The host is
 * set to the world's state before each action, so it follows the world's game exactly.
 */
private class BrowserHost(registry: CardRegistry, initial: GameState, private val verify: Boolean) {
    private val cpu = ManagementFactory.getThreadMXBean()
    private val session = GameSession(cardRegistry = registry)
    private val seats = initial.turnOrder
    private var epoch = ""
    var executeNs = 0L
    var updateNs = 0L
    var updates = 0
    /** Per hosted action: CPU to take the action in, and to build both seats' updates. */
    val steps = ArrayList<Pair<Long, Long>>()

    init {
        session.injectStateForTesting(initial, emptyMap())
        sendAll(emptyList())
    }

    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private fun withoutUuids(state: GameState?): String = uuid.replace(state?.entities.toString(), "") +
        uuid.replace(state?.copy(entities = emptyMap()).toString(), "")

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = cpu.currentThreadCpuTime
        val value = block()
        return value to cpu.currentThreadCpuTime - started
    }

    private fun sendAll(events: List<GameEvent>): Long =
        timed { seats.forEach { send(it, events) } }.second.also { updateNs += it }

    private fun send(seat: EntityId, events: List<GameEvent>) {
        val message = session.createStateUpdate(seat, events, useEngineDecisionIds = false)
        epoch = when (message) {
            is ServerMessage.StateUpdate -> message.interactionEpoch
            is ServerMessage.StateDeltaUpdate -> message.interactionEpoch
            else -> error("No state update for $seat: $message")
        } ?: error("Update has no interaction epoch")
        updates++
    }

    fun accept(transition: ArgentumRawTransition) {
        if (!transition.accepted) return
        session.injectStateForTesting(transition.beforeState, emptyMap())
        val action = transition.action
        val actor = action.playerId
        val (events, taken) = timed<List<GameEvent>> {
            when (action) {
                is KeepHand -> emptyList<GameEvent>().also { check(session.keepHand(actor) !is GameSession.MulliganActionResult.Failure) }
                is TakeMulligan -> emptyList<GameEvent>().also { check(session.takeMulligan(actor) !is GameSession.MulliganActionResult.Failure) }
                is BottomCards -> emptyList<GameEvent>().also {
                    val names = session.toSeat(actor, action.cardIds, ListSerializer(EntityId.serializer()))
                    val result = session.chooseBottomCards(actor, names)
                    check(result !is GameSession.MulliganActionResult.Failure) { "Host refused $action: $result" }
                }
                else -> {
                    var seatAction = session.toSeat(actor, action, GameAction.serializer())
                    if (seatAction is SubmitDecision) seatAction = seatAction.copy(
                        response = seatAction.response.withDecisionId("$epoch:${seatAction.response.decisionId}"))
                    when (val result = session.executeClientAction(actor, seatAction, interactionEpoch = epoch)) {
                        is GameSession.ActionResult.Success -> result.events
                        is GameSession.ActionResult.PausedForDecision -> result.events
                        is GameSession.ActionResult.Failure -> error("Host refused $action: ${result.reason}")
                    }
                }
            }
        }
        executeNs += taken
        val hosted = if (verify) session.getStateForTesting() else null
        steps += taken to sendAll(events)
        // Resolution keys are random UUIDs, so they differ between any two runs of the same action.
        if (verify) check(hosted == transition.afterState || withoutUuids(hosted) == withoutUuids(transition.afterState)) {
            "Host diverged from the world after $action"
        }
    }
}
// seat-path end
