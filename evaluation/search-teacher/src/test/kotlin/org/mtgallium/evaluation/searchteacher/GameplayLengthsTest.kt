package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Tag
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings

@Tag("public-source")
class GameplayLengthsTest {
    private fun game(id: String, decisions: Int, winner: String? = "p1", terminal: Boolean = true) =
        GameRunResult(gameId = id, seed = 42, p0Policy = ArenaPolicyKind.SEARCH, p1Policy = ArenaPolicyKind.SEARCH,
            winner = if (terminal) winner else null, terminal = terminal,
            disposition = if (terminal) GameRunDisposition.GAME_ENDED else GameRunDisposition.STOPPED_LIMIT,
            decisions = decisions, searchSeat = null, searchScore = null, illegalResponses = 0, fallbacks = 0,
            stepLimit = !terminal, replayVerified = terminal, p0PolicyId = "control", p1PolicyId = "candidate")

    @Test fun `invalid pair excludes its terminal partner and stopped length while draws remain games`() {
        val good = searchBudgetFrontierPair(10, 42, listOf(game("win", 100), game("draw", 200, null)), "candidate")
        val bad = searchBudgetFrontierPair(11, 42, listOf(game("terminal-partner", 1), game("stopped", 0, terminal = false)), "candidate")
        val rows = gameplayLengthObservations(listOf(good, bad), "candidate")
        assertEquals(listOf(true, true, false, false), rows.map { it.eligible })
        assertEquals(listOf("candidate win", "draw"), rows.filter { it.eligible }.map { it.candidateOutcome })
        assertEquals(150.0, gameplayLengthDistribution(rows.filter { it.eligible }.map { it.decisions.toDouble() }).mean)
        val text = renderGameplayLengths(rows, 2, 10)
        assertContains(text, "2 attempted games excluded")
        assertContains(text, "| All executed valid | 2 | 150.00 / 150.00 / 200.00 |")
        assertContains(text, "unavailable (0/2)")
    }

    @Test fun `overshoot remains separate from outcome splits and duplicate game rows are refused`() {
        val prefix = searchBudgetFrontierPair(7, 42, listOf(game("p0", 100), game("p1", 200, "p0")), "candidate")
        val later = searchBudgetFrontierPair(8, 42, listOf(game("o0", 900), game("o1", 900)), "candidate")
        val rows = gameplayLengthObservations(listOf(prefix, later), "candidate")
        val text = renderGameplayLengths(rows, 1, 7)
        assertContains(text, "| Inspected valid | 2 | 150.00")
        assertContains(text, "| Operational overshoot valid | 2 | 900.00")
        assertContains(text, "| Inspected: candidate win | 1 | 100.00")
        assertContains(text, "| Inspected: control win | 1 | 200.00")
        assertFails { renderGameplayLengths(rows + rows.first(), 1, 7) }
        assertFails { gameplayLengthObservations(listOf(prefix, prefix), "candidate") }
    }

    @Test fun `completed arena records the canonical terminal player turn`() {
        val root = Files.createTempDirectory("game-length-arena-")
        val replay = root.resolve("game.privileged.replay.jsonl.gz")
        val deck = org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest(
            "game-length-fixture", "Public game length fixture", "synthetic", "2026-09-08",
            "public synthetic fixture", mapOf("Mountain" to 24, "Hired Claw" to 36), emptyMap())
        val parameters = org.mtgallium.agent.searchteacher.SearchTeacherRuntimeConfig().policyParameters()
        val profile = FrozenSearchProfile(id = "fast-arena-v1", generatedAtUtc = "synthetic",
            outerCommit = "synthetic", argentumCommit = "synthetic", host = "synthetic", particles = 8,
            simulations = 64, leaf = parameters.leaf, actionSpaceProfile = parameters.actionSpaceProfile,
            maxPolicyDecisions = 16, measuredP95Millis = 0.0, tacticalScore = 0.0,
            standardError = 0.0, calibrationReportHash = "synthetic")
        val game = SearchTeacherArena(buildRegistry(), deck, profile, 19L).play(
            gameId = "game-length-terminal-turn", gameSeed = 19L,
            p0Policy = ArenaPolicyKind.HEURISTIC, p1Policy = ArenaPolicyKind.HEURISTIC,
            replay = GameReplayOptions(finalPath = replay, referencePath = "game.privileged.replay.jsonl.gz",
                runIdentity = "game-length-public-fixture", outerCommit = "synthetic", argentumCommit = "synthetic"))
        assertEquals(GameRunDisposition.GAME_ENDED, game.disposition, game.exception)
        assertTrue(game.replayVerified, game.replayVerificationDiagnostic)
        val reconstructed = reconstructCanonicalTournamentReplay(replay)
        val terminal = reconstructed.stateAt(reconstructed.states.lastIndex)
        assertTrue(terminal.gameOver)
        assertTrue(terminal.turnNumber > 1)
        assertEquals(terminal.turnNumber, game.terminalTurnNumber)
    }

    @Test fun `unknown historical turn stays omitted and zero is an explicitly recorded pregame terminal`() {
        val legacy = game("legacy", 20)
        val bytes = evidenceJson.encodeToString(legacy)
        assertFalse("terminalTurnNumber" in bytes)
        val decoded = evidenceJson.decodeFromString<GameRunResult>(bytes)
        assertNull(decoded.terminalTurnNumber)
        assertEquals(bytes, evidenceJson.encodeToString(decoded))
        val known = legacy.copy(terminalTurnNumber = 0)
        assertEquals(0, evidenceJson.decodeFromString<GameRunResult>(evidenceJson.encodeToString(known)).terminalTurnNumber)
        assertFails { legacy.copy(terminalTurnNumber = -1) }
        assertFails { game("stopped", 0, terminal = false).copy(terminalTurnNumber = 1) }
    }

    @Test fun `distributions distinguish availability and use explicit nearest rank p90`() {
        assertEquals(GameplayLengthDistribution(0, null, null, null), gameplayLengthDistribution(emptyList()))
        assertEquals(GameplayLengthDistribution(4, 25.0, 25.0, 40.0), gameplayLengthDistribution(listOf(40.0, 10.0, 30.0, 20.0)))
        assertFails { gameplayLengthDistribution(listOf(Double.NaN)) }
        assertFails { gameplayLengthDistribution(listOf(-1.0)) }
    }

    @Test fun `inspection requires finalized registered evidence and never starts or writes a run`() {
        val directory = Files.createTempDirectory("game-length-summary-")
        val plan = directory.resolve("plan.json")
        Files.writeString(plan, "{}")
        assertFailsWith<IllegalArgumentException> { loadRetainedGameplayLengths(directory) }
        assertEquals(listOf("plan.json"), Files.list(directory).use { it.map { path -> path.fileName.toString() }.sorted().toList() })
        val identity = ResearchRunBindings(protocol = "synthetic-game-length-v1", material = mapOf("purpose" to "hash-refusal")).identity
        ResearchRunArtifacts(directory, identity).also { it.register("plan.json"); it.finalize() }
        Files.writeString(plan, "changed")
        assertFails { loadRetainedGameplayLengths(directory) }
    }

    @Test fun `cli accepts one or two directories including spaces and refuses output or an unbounded list`() {
        fun parse(vararg args: String) = SearchTeacherCli.parse(arrayOf("--suite", "gameplay-summary", *args))
        assertEquals(listOf(Path.of("/tmp/first run"), Path.of("/tmp/second")),
            parse("--run-directory", "/tmp/first run", "--run-directory", "/tmp/second").runDirectories)
        assertFails { parse() }
        assertFails { parse("--run-directory", "/tmp/one", "--output", "/tmp/out") }
        assertFails { parse("--run-directory", "/tmp/one", "--run-directory", "/tmp/two", "--run-directory", "/tmp/three") }
    }
}
