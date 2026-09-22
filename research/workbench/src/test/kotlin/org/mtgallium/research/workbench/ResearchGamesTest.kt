package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.sdk.model.Deck
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*

class ResearchGamesTest {
    companion object { private val registry by lazy(::buildRegistry) }
    private fun world(cards: Int = 1, hand: Int = 0): ArgentumSearchWorld {
        val deck = mapOf("Mountain" to cards)
        return createWorld(GameConfig(players = listOf(
            PlayerConfig("A", Deck.of("Mountain" to cards)), PlayerConfig("B", Deck.of("Mountain" to cards))),
            startingHandSize = hand, skipMulligans = true, startingPlayerIndex = 0, seed = 11),
            mapOf("p0" to deck, "p1" to deck), registry)
    }
    private fun pass() = Player { context, _ ->
        context.expansion.candidates.firstOrNull { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
            ?: context.expansion.candidates.first()
    }
    private fun players() = mapOf("p0" to pass(), "p1" to pass())

    @Test fun `zero decisions is an unfinished game not a draw`() {
        val result = playGame(world(), players(), maximumDecisions = 0)
        assertEquals(GameStatus.DECISION_LIMIT, result.status)
        assertEquals(0, result.decisions)
        assertNull(result.payoffs)
    }

    @Test fun `a public small-deck game reaches an actual engine outcome`() {
        val decisions = mutableListOf<GameDecision>()
        val result = playGame(world(), players(), maximumDecisions = 400, record = decisions::add)
        assertEquals(GameStatus.TERMINAL, result.status)
        assertTrue(result.decisions > 0)
        assertEquals(result.decisions, decisions.size)
        assertEquals(setOf(-1.0, 1.0), result.payoffs!!.values.toSet())
        for (row in decisions) {
            assertTrue(row.accepted)
            assertTrue(row.selectedIndex in row.information.candidates.indices)
            assertEquals(row.information.actingPlayerId, row.information.observation.perspectivePlayerId)
            assertTrue(row.profileExhaustive || !row.rulesExhaustive)
        }
    }

    @Test fun `recorded decision information uses the player requested menu and digest`() {
        val game = world(cards = 8, hand = 2)
        for (ignored in 0 until 32) {
            if (game.actorToAct() == "p0" && game.decisionContext().expansion.candidates.size > 1) break
            val pass = game.expandChoices().candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
            check(game.step(pass).accepted)
        }
        val unrestricted = game.decisionContext()
        assertEquals("p0", game.actorToAct())
        assertTrue(unrestricted.expansion.candidates.size > 1)
        val contexts = mutableListOf<DecisionSiteRequest>()
        val player = Player(view = DecisionView(limit = 1)) { context, _ ->
            contexts += context
            context.expansion.candidates.single()
        }
        val rows = mutableListOf<GameDecision>()
        playGame(game, mapOf("p0" to player, "p1" to pass()), maximumDecisions = 1, record = rows::add)
        val context = contexts.single()
        val row = rows.single()
        val information = row.information
        assertEquals(1, information.candidates.size)
        assertEquals(context.expansion.candidates, information.candidates)
        assertEquals(InformationStateRepresentationDigest.compute(
            information.observation.observationDigest,
            information.historyCommitment,
            information.knowledge.knowledgeDigest,
            context.actor,
            context.expansion.candidates.map { it.signature },
            context.expansion.proposalVersion,
        ), information.informationStateDigest)
    }

    @Test fun `policy and observer failures propagate rather than produce outcomes`() {
        val broken = Player { _, _ -> error("policy failure") }
        assertEquals("policy failure", assertFailsWith<IllegalStateException> {
            playGame(world(), mapOf("p0" to broken, "p1" to pass()), maximumDecisions = 1)
        }.message)
        val badObserver = Player(observe = { _, _, _, _ -> error("observer failure") }, choose = pass().choose)
        assertEquals("observer failure", assertFailsWith<IllegalStateException> {
            playGame(world(), mapOf("p0" to badObserver, "p1" to pass()), maximumDecisions = 1)
        }.message)
    }

    @Test fun `a factual branch can be played without changing its parent`() {
        val parent = world()
        val before = parent.authoritativeFingerprint()
        val branch = parent.fork() as ArgentumSearchWorld
        val result = playGame(branch, players(), maximumDecisions = 1)
        assertEquals(1, result.decisions)
        assertEquals(before, parent.authoritativeFingerprint())
        assertNotEquals(before, branch.authoritativeFingerprint())
    }

    @Test fun `semantic encoding remains normalized centered and independent of fresh native ids`() {
        val first = rootActionKernelFeatures(world(8, 2).decisionContext().site())
        val second = rootActionKernelFeatures(world(8, 2).decisionContext().site())
        assertEquals(first, second)
        assertEquals(1.0, first.first().state.values.sumOf { it * it }, 1e-12)
        val sums = mutableMapOf<Int, Double>()
        first.forEach { row -> row.centeredCandidate.indices.forEachIndexed { i, index ->
            sums[index] = sums.getOrDefault(index, 0.0) + row.centeredCandidate.values[i]
        } }
        assertTrue(sums.values.all { kotlin.math.abs(it) < 1e-12 })
    }

    @Test fun `ordered candidate payloads remain distinguishable without changing the state features`() {
        val information = world(8, 2).decisionContext().site().information()
        val original = information.candidates.first()
        fun choice(values: List<String>) = SemanticChoice.create(
            kind = original.kind, operationFamily = original.operationFamily,
            actionIntent = original.actionIntent, display = original.display,
            canonicalPayload = buildJsonObject {
                put("ordered", buildJsonArray { values.forEach { add(it) } })
            })
        val menu = listOf(choice(listOf("first", "second")), choice(listOf("second", "first")))
        val encoded = rootActionKernelFeatures(information, menu)
        assertNotEquals(encoded[0].centeredCandidate, encoded[1].centeredCandidate)
        assertEquals(encoded[0].state, encoded[1].state)
        assertEquals(rootActionKernelFeatures(information).first().state, encoded[0].state)
        assertTrue(encoded[0].centeredCandidate.values.any { it != 0.0 })
    }

    @Test fun `CLI records decisions and privileged playback as ordinary files`() {
        val directory = Files.createTempDirectory("research-games-")
        try {
            val plan = GamesPlan(decks = List(2) { mapOf("Mountain" to 1) }, policies = listOf("random", "random"),
                startingHandSize = 0, skipMulligans = true, maximumDecisions = 2,
                recordDecisions = true, recordReplay = true)
            val output = directory.resolve("run")
            val result = runGames(plan, output).single()
            assertEquals(GameStatus.DECISION_LIMIT, result.result.status)
            assertNull(result.result.payoffs)
            val decisions = useJsonLines(output.resolve("games/0/decisions.jsonl.gz")) { records ->
                records.map { researchJson.decodeFromJsonElement<GameDecision>(it) }.toList()
            }
            assertEquals(2, decisions.size)
            val frames = useJsonLines(output.resolve("games/0/replay.jsonl.gz")) { records ->
                records.map { researchJson.decodeFromJsonElement<ReplayFrame>(it) }.toList()
            }
            assertEquals(0, frames.first().index)
            assertNull(frames.first().action)
            assertTrue(frames.drop(1).all { it.action != null && it.accepted == true })
            assertEquals(result, readJson<PlayedGame>(output.resolve("games/0/result.json")))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `parallel mapping preserves result order and does not hide a failed task`() {
        assertEquals(listOf(4, 1, 9), parallelMap(listOf(2, 1, 3), 8) { it * it })
        assertFails { parallelMap(listOf(1, 2), 2) { check(it != 2); it } }
    }
}
