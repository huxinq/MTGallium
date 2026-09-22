package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.sdk.model.Deck
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.mtgallium.agent.infoset.core.*

/** Meaningful composition cases, independent of the retired workflow machinery. */
class ResearchSemanticsRegressionTest {
    @TempDir lateinit var temporary: Path
    companion object { private val registry by lazy(::buildRegistry) }
    private fun world() = createWorld(GameConfig(players = List(2) { index ->
        PlayerConfig("Player $index", Deck.of("Mountain" to 60))
    }, startingHandSize = 0, skipMulligans = true, startingPlayerIndex = 0, seed = 11),
        mapOf("p0" to mapOf("Mountain" to 60), "p1" to mapOf("Mountain" to 60)), registry)
    private fun players() = List(2) { index -> "p$index" to Player { context, _ ->
        context.expansion.candidates.firstOrNull { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
            ?: context.expansion.candidates.first()
    } }.toMap()

    @Test fun `native help is owned here while unknown commands still fail`() {
        for (arguments in listOf(emptyArray(), arrayOf("help"), arrayOf("--help"))) main(arguments)
        assertFailsWith<IllegalStateException> { main(arrayOf("not-a-research-command")) }
    }

    @Test fun `prediction preserves the observed selection as distinct from model preference`() {
        val state = RootActionKernelVector(listOf(0), listOf(1.0))
        val negative = RootActionKernelFeatures(state, RootActionKernelVector(listOf(0), listOf(-1.0)))
        val positive = RootActionKernelFeatures(state, RootActionKernelVector(listOf(0), listOf(1.0)))
        writeJson(temporary.resolve("model.json"), RootActionKernelModel(.001, listOf(positive), listOf(1.0)))
        writeJson(temporary.resolve("menus.json"), buildJsonArray { add(buildJsonObject {
            put("features", researchJson.encodeToJsonElement(listOf(negative, positive)))
            put("selectedIndex", 0)
            put("accepted", true)
        }) })
        main(arrayOf("predict", temporary.resolve("model.json").toString(),
            temporary.resolve("menus.json").toString(), temporary.resolve("output.json").toString()))
        val row = readJson<JsonArray>(temporary.resolve("output.json")).single().jsonObject
        assertEquals(0, row.getValue("selectedIndex").jsonPrimitive.int)
        assertEquals(1, row.getValue("predictedIndex").jsonPrimitive.int)
        assertEquals(true, row.getValue("accepted").jsonPrimitive.boolean)
    }

    @Test fun `encoding a rejected diagnostic row retains its rejection rather than promoting it to acceptance`() {
        val context = world().decisionContext()
        val row = GameDecision(0, context.site().information(),
            context.expansion.isExhaustive, context.expansion.isProfileExhaustive,
            selectedIndex = 0, accepted = false, decisionNanos = 0)
        val input = temporary.resolve("decisions.jsonl")
        openJsonLines(input).use { it.writeRecord(row) }
        main(arrayOf("encode", input.toString(), temporary.resolve("features.json").toString()))
        val encoded = readJson<JsonArray>(temporary.resolve("features.json")).single().jsonObject
        assertEquals(false, encoded.getValue("accepted").jsonPrimitive.boolean)
        assertFalse("actionMeans" in encoded)
    }

    @Test fun `resuming a factual world uses the existing decision coordinate rather than restarting at zero`() {
        val world = world()
        val observations = mutableListOf<GameDecision>()
        val first = playGame(world, players(), maximumDecisions = 1, record = observations::add)
        val second = playGame(world, players(), maximumDecisions = 1, record = observations::add)
        assertEquals(listOf(1, 1), listOf(first.decisions, second.decisions))
        assertEquals(listOf(0, 1), observations.map { it.index })
        val child = world.fork() as org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
        val forkRows = mutableListOf<GameDecision>()
        playGame(child, players(), maximumDecisions = 1, record = forkRows::add)
        assertEquals(2, forkRows.single().index)
    }

    @Test fun `search convenience accepts positive budgets outside the former grid`() {
        val output = temporary.resolve("search")
        val plan = GamesPlan(decks = List(2) { mapOf("Mountain" to 60) },
            policies = listOf("search", "random"), particles = 1, simulations = 1, searchDepth = 1,
            startingHandSize = 0, skipMulligans = true, maximumDecisions = 2, recordDecisions = true)
        val result = runGames(plan, output).single()
        assertEquals(GameStatus.DECISION_LIMIT, result.result.status)
        assertNull(result.result.payoffs)
        assertEquals(2, useJsonLines(output.resolve("games/0/decisions.jsonl.gz")) { it.count() })
    }
}
