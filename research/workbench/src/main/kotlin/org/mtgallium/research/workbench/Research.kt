package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.Deck
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.argentum.policy.*
import org.mtgallium.agent.monored.LinearWeights

/** Game setup and native search settings shared by the CLI and Python session. */
@Serializable
data class GamesPlan(
    val decks: List<Map<String, Int>>,
    val policies: List<String> = listOf("heuristic", "heuristic"),
    val seed: Long = 1,
    val games: Int = 1,
    val threads: Int = 1,
    val startingLife: Int = 20,
    val startingHandSize: Int = 7,
    val startingPlayerIndex: Int = 0,
    val skipMulligans: Boolean = false,
    val useHandSmoother: Boolean = false,
    val actionProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
    val particles: Int = 8,
    val simulations: Int = 64,
    val searchDepth: Int = 32,
    val explorationConstant: Double = 1.4,
    val leaf: LeafEvaluationConfig = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT),
    val valueWeights: LinearWeights? = null,
    val rolloutTurnHorizon: RolloutTurnHorizon? = null,
    val opponentModel: String = "mixture",
    val maximumDecisions: Int? = 2048,
    val maximumSeconds: Double? = null,
    val recordDecisions: Boolean = false,
    val recordReplay: Boolean = false,
)

/** Read one privileged engine-state snapshot from a replay. */
@Serializable
data class ReplayFrame(val index: Int, val state: GameState, val action: GameAction? = null, val accepted: Boolean? = null)

@Serializable
data class PlayedGame(val index: Int, val seed: Long, val result: GameResult)

fun runGames(plan: GamesPlan, output: Path): List<PlayedGame> {
    require(plan.decks.size == 2 && plan.policies.size == 2) { "This CLI convenience runs two-player games" }
    require(plan.games > 0 && plan.threads > 0)
    val registry = buildRegistry()
    Files.createDirectories(output.toAbsolutePath().parent)
    Files.createDirectory(output)
    writeJson(output.resolve("plan.json"), plan)
    writeJson(output.resolve("context.json"), executionContext())
    val results = parallelMap((0 until plan.games).toList(), plan.threads) { index ->
        val destination = output.resolve("games/$index")
        Files.createDirectories(destination)
        val seed = Math.addExact(plan.seed, index.toLong())
        try {
            val game = PythonGame.create(plan.copy(seed = seed), registry, "game-$index")
            val world = game.world
            val players = game.players
            val decisions = if (plan.recordDecisions) openJsonLines(destination.resolve("decisions.jsonl.gz")) else null
            val result = decisions.use { decisionLog ->
                (if (plan.recordReplay) openJsonLines(destination.resolve("replay.jsonl.gz")) else null).use { replayLog ->
                    var frame = 0
                    replayLog?.writeRecord(ReplayFrame(frame, world.authoritativeStateForHost()))
                    playGame(world, players, seed, plan.maximumDecisions, plan.maximumSeconds,
                        record = decisionLog?.let { writer -> { decision -> writer.writeRecord(decision) } },
                        rawTrace = replayLog?.let { writer -> { step ->
                            writer.writeRecord(ReplayFrame(++frame, step.afterState, step.action, step.accepted))
                        } })
                }
            }
            PlayedGame(index, seed, result).also { writeJson(destination.resolve("result.json"), it) }
        } catch (failure: Exception) {
            writeJson(destination.resolve("failure.json"), buildJsonObject {
                put("error", failure.javaClass.name)
                put("message", failure.message?.let(::JsonPrimitive) ?: JsonNull)
            })
            throw failure
        }
    }
    writeJson(output.resolve("results.json"), results)
    return results
}

fun main(args: Array<String>) {
    fun arity(size: Int, usage: String) = require(args.size == size) { usage }
    fun path(index: Int): Path = Path.of(args[index])
    when (args.firstOrNull()) {
        null, "help", "--help", "-h" -> println("""
            games PLAN.json OUTPUT_DIRECTORY
            fit ROOTS.json MODEL.json [RIDGE]
            predict MODEL.json MENUS.json OUTPUT.json
            encode DECISIONS.jsonl[.gz] FEATURES.json
            replay-state REPLAY.jsonl[.gz] FRAME_INDEX STATE.json

            These functions use ordinary files. Use your own main for other experiments.
        """.trimIndent())
        "games" -> {
            arity(3, "games PLAN.json OUTPUT_DIRECTORY")
            println(researchJson.encodeToString(runGames(readJson(path(1)), path(2))))
        }
        "fit" -> {
            require(args.size in 3..4) { "fit ROOTS.json MODEL.json [RIDGE]" }
            val roots = readJson<List<RootActionKernelTrainingRoot>>(path(1))
            val model = fitRootActionKernel(roots, args.getOrNull(3)?.toDouble() ?: 0.001)
            writeJson(path(2), model)
            println("Fitted ${roots.size} roots; wrote ${path(2)}")
        }
        "predict" -> {
            arity(4, "predict MODEL.json MENUS.json OUTPUT.json")
            val model = readJson<RootActionKernelModel>(path(1))
            val rows = readJson<JsonArray>(path(2)).map { item ->
                val row = item.jsonObject
                val features = researchJson.decodeFromJsonElement<List<RootActionKernelFeatures>>(row.getValue("features"))
                val scores = model.scores(features)
                JsonObject(row + mapOf("scores" to researchJson.encodeToJsonElement(scores),
                    "predictedIndex" to (scores.indices.maxByOrNull { scores[it] }?.let(::JsonPrimitive) ?: JsonNull)))
            }
            writeJson(path(3), JsonArray(rows))
            println("Scored ${rows.size} menus; wrote ${path(3)}")
        }
        "encode" -> {
            arity(3, "encode DECISIONS.jsonl[.gz] FEATURES.json")
            val rows = useJsonLines(path(1)) { records -> records.map { value ->
                val decision = researchJson.decodeFromJsonElement<GameDecision>(value)
                val input = decision.information
                val features = rootActionKernelFeatures(input)
                buildJsonObject {
                    put("index", decision.index)
                    put("selectedIndex", decision.selectedIndex)
                    put("accepted", decision.accepted)
                    put("features", researchJson.encodeToJsonElement(features))
                    put("rulesExhaustive", decision.rulesExhaustive)
                    put("profileExhaustive", decision.profileExhaustive)
                }
            }.toList() }
            writeJson(path(2), JsonArray(rows))
            println("Encoded ${rows.size} decisions without inventing value targets")
        }
        "replay-state" -> {
            arity(4, "replay-state REPLAY.jsonl[.gz] FRAME_INDEX STATE.json")
            val index = args[2].toInt()
            val frame = useJsonLines(path(1)) { records ->
                records.firstOrNull { it.jsonObject.getValue("index").jsonPrimitive.int == index }
            } ?: error("No replay frame $index")
            writeJson(path(3), frame.jsonObject.getValue("state"))
        }
        else -> error("Unknown research command '${args.first()}'; use help or your own main")
    }
}
