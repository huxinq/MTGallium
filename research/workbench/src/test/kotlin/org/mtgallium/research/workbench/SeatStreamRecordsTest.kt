package org.mtgallium.research.workbench

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.engine.hidden.*
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.CardDefinition
import java.nio.file.Path
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.assertTrue
import com.wingedsheep.gym.GameEnvironment
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice

/** Private corpus paths are opt-in. Snapshot replay does not invent missing engine events. */
class SeatStreamRecordsTest {
    @Test fun recordedTransitions() {
        val input = System.getenv("SEAT_STREAM_GAME")
        assumeTrue(input != null, "Set SEAT_STREAM_GAME to one complete accepted-action tape")
        val output = Path.of(requireNotNull(System.getenv("SEAT_STREAM_OUTPUT")))
        Files.createDirectories(output)
        val registry = buildRegistry()
        val sessions = mutableMapOf<EntityId, GameSession>()
        val logs = mutableMapOf<EntityId, MutableList<ClientEvent>>()
        var decisions = 0
        var terminal = false
        output.resolve("transitions.tsv").toFile().bufferedWriter().use { out ->
            out.appendLine("decision\tseat\tevents\tbuild_ns\tjson_bytes\tgzip_bytes\tfull_json_bytes\tfull_gzip_bytes")
            useJsonLines(Path.of(input!!)) { rows ->
                for (element in rows) {
                    val row = element.jsonObject
                    if (row["type"]?.jsonPrimitive?.content == "end") {
                        terminal = researchJson.decodeFromJsonElement<GameState>(row.getValue("privilegedFinalState")).gameOver
                    }
                    val encoded = row["privilegedPreState"] ?: continue
                    val state = researchJson.decodeFromJsonElement<GameState>(encoded)
                    val env = GameEnvironment.create(registry).also { it.restore(state, state.turnOrder) }
                    val world = ArgentumSearchWorld.create(env, "stream-replay", 0L, 0L)
                    val choice = researchJson.decodeFromJsonElement<SemanticChoice>(row.getValue("chosenChoice"))
                    val replay = world.stepWithReplayTrace(choice)
                    assertTrue(replay.result.accepted, "Rejected decision ${row["decisionIndex"]}: ${replay.result}")
                    for (transition in replay.rawTransitions) {
                        assertTrue(transition.accepted)
                        for ((seat, player) in state.turnOrder.withIndex()) {
                            val session = sessions.getOrPut(player) { GameSession(cardRegistry = registry) }
                            session.injectStateForTesting(transition.afterState, emptyMap())
                            val start = System.nanoTime()
                            val message = requireNotNull(session.createStateUpdate(player, transition.events, true))
                            val elapsed = System.nanoTime() - start
                            val json = researchJson.encodeToString<ServerMessage>(message).toByteArray()
                            val bytes = ByteArrayOutputStream()
                            GZIPOutputStream(bytes).use { it.write(json) }
                            val events = when (message) {
                                is ServerMessage.StateUpdate -> message.events
                                is ServerMessage.StateDeltaUpdate -> message.events
                                else -> error("Unexpected update")
                            }
                            val log = logs.getOrPut(player) { mutableListOf() }
                            log += events.filter { it !is ClientEvent.PermanentTapped && it !is ClientEvent.PermanentUntapped && it !is ClientEvent.ManaAdded }
                            val full = when (message) {
                                is ServerMessage.StateUpdate -> message
                                is ServerMessage.StateDeltaUpdate -> ServerMessage.StateUpdate(
                                    state = requireNotNull(session.getClientState(player)).copy(gameLog = log.toList()),
                                    events = message.events, legalActions = message.legalActions,
                                    pendingDecision = message.pendingDecision, nextStopPoint = message.nextStopPoint,
                                    opponentDecisionStatus = message.opponentDecisionStatus,
                                    stopOverrides = message.stopOverrides, undoAvailable = message.undoAvailable,
                                    priorityMode = message.priorityMode, stateVersion = message.stateVersion,
                                    interactionEpoch = message.interactionEpoch)
                                else -> error("Unexpected update")
                            }
                            val fullJson = researchJson.encodeToString<ServerMessage>(full).toByteArray()
                            val fullBytes = ByteArrayOutputStream()
                            GZIPOutputStream(fullBytes).use { it.write(fullJson) }
                            out.appendLine(listOf(row["decisionIndex"], seat, transition.events.size, elapsed,
                                json.size, bytes.size(), fullJson.size, fullBytes.size()).joinToString("\t"))
                        }
                    }
                    decisions++
                }
            }
        }
        assertTrue(terminal && decisions >= 100, "Require a complete game with at least 100 decisions")
    }

    @Test fun recordedSnapshots() {
        val records = System.getenv("SEAT_STREAM_RECORDS")
        assumeTrue(records != null, "Set SEAT_STREAM_RECORDS to a recorded-game directory")
        val output = Path.of(requireNotNull(System.getenv("SEAT_STREAM_OUTPUT")))
        Files.createDirectories(output)
        val registry = buildRegistry()
        val transformer = ClientStateTransformer(registry)
        val visibility = Visibility(registry)
        val materializer = HiddenWorldMaterializer(registry)
        fun payload(state: GameState, player: EntityId): ServerMessage.StateUpdate {
            val session = GameSession(cardRegistry = registry)
            session.injectStateForTesting(state, emptyMap())
            return (session.createStateUpdate(player, emptyList(), true) as ServerMessage.StateUpdate)
                .copy(interactionEpoch = null, stateVersion = 0)
        }
        val paths = Files.walk(Path.of(records!!)).use { files ->
            files.filter { it.fileName.toString().matches(Regex("game-.*\\.jsonl\\.gz")) }.sorted().toList()
        }
        assertTrue(paths.isNotEmpty())
        var positions = 0
        output.resolve("snapshots.tsv").toFile().bufferedWriter().use { out ->
            out.appendLine("game\tdecision\tseat\tbuild_ns\tfull_build_ns\tfull_json\tdelta_json\tfull_gzip\tdelta_gzip\tidentity_swap\torder_swap")
            for (path in paths) {
                val sessions = mutableMapOf<EntityId, GameSession>()
                val started = System.nanoTime()
                var frames = 0
                useJsonLines(path) { rows ->
                    for (element in rows) {
                        val row = element.jsonObject
                        val encoded = row["privilegedPreState"] ?: row["privilegedFinalState"] ?: continue
                        val state = researchJson.decodeFromJsonElement<GameState>(encoded)
                        for ((seat, player) in state.turnOrder.withIndex()) {
                            val session = sessions.getOrPut(player) { GameSession(cardRegistry = registry) }
                            session.injectStateForTesting(state, emptyMap())
                            val start = System.nanoTime()
                            val message = requireNotNull(session.createStateUpdate(player, emptyList(), true))
                            val elapsed = System.nanoTime() - start
                            val delta = researchJson.encodeToString<ServerMessage>(message)
                            // Fresh session requests the initial full message, excluding accumulated logs.
                            val fresh = GameSession(cardRegistry = registry)
                            fresh.injectStateForTesting(state, emptyMap())
                            val fullStart = System.nanoTime()
                            val fullMessage = requireNotNull(fresh.createStateUpdate(player, emptyList(), true))
                            val fullElapsed = System.nanoTime() - fullStart
                            val full = researchJson.encodeToString<ServerMessage>(fullMessage)
                            var identity = "NOT_SAMPLED"
                            var order = "NOT_SAMPLED"
                            if (positions < 256) {
                                val assignments = linkedMapOf<EntityId, CardDefinition>()
                                var reordered = state
                                for (owner in state.turnOrder) {
                                    val slots = listOf(Zone.HAND, Zone.LIBRARY).flatMap { zone ->
                                        state.getZone(ZoneKey(owner, zone)).filter { id ->
                                            !visibility.isCardIdentityVisibleTo(state, ZoneKey(owner, zone), id, player)
                                        }
                                    }
                                    val names = slots.map { state.getEntity(it)!!.get<CardComponent>()!!.name }.reversed()
                                    slots.zip(names).forEach { (id, name) -> assignments[id] = requireNotNull(registry.getCard(name)) }
                                    val hidden = slots.toSet()
                                    val library = state.getLibrary(owner)
                                    val reverse = library.filter { it in hidden }.reversed().iterator()
                                    reordered = reordered.copy(zones = reordered.zones + (ZoneKey(owner, Zone.LIBRARY) to library.map {
                                        if (it in hidden) reverse.next() else it
                                    }))
                                }
                                val before = transformer.transform(state, player)
                                val beforePayload = payload(state, player)
                                order = if (beforePayload == payload(reordered, player)) "SAME" else "CHANGED"
                                identity = when (val replacement = materializer.materialize(state, HiddenWorldMaterializationRequest(assignments, state.rng))) {
                                    is HiddenWorldMaterializationResult.Unsupported -> "UNSUPPORTED_${replacement.reason.kind}"
                                    is HiddenWorldMaterializationResult.Materialized -> if (beforePayload == payload(replacement.state, player)) "SAME" else "CHANGED"
                                }
                                if (order == "CHANGED" && !Files.exists(output.resolve("library-order-counterexample.json"))) {
                                    writeJson(output.resolve("library-order-counterexample.json"), buildJsonObject {
                                        put("source", path.fileName.toString()); put("decision", row["decisionIndex"] ?: JsonNull)
                                        put("seat", seat); put("before", researchJson.encodeToJsonElement(before))
                                        put("after", researchJson.encodeToJsonElement(transformer.transform(reordered, player)))
                                    })
                                }
                            }
                            fun compact(text: String): Int {
                                val bytes = ByteArrayOutputStream()
                                GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
                                return bytes.size()
                            }
                            out.appendLine(listOf(Path.of(records).relativize(path), row["decisionIndex"] ?: "final", seat, elapsed, fullElapsed,
                                full.toByteArray().size, delta.toByteArray().size, compact(full), compact(delta), identity, order).joinToString("\t"))
                            positions++
                        }
                        frames++
                    }
                }
                println("SEAT_STREAM_GAME\t${path.fileName}\t$frames\t${System.nanoTime() - started}")
                out.flush()
            }
        }
        assertTrue(positions >= 100, "Need at least 100 seated positions")
    }
}
