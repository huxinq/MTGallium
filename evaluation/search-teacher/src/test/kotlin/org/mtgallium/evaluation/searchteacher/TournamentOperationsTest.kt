package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

class TournamentOperationsTest {
    @Test
    fun `completed v1 legacy benchmark remains readable after evaluator refactor`() {
        val root = Path.of("").toAbsolutePath().normalize().let { current ->
            if (Files.isDirectory(current.resolve("agent"))) current else current.resolve("../..").normalize()
        }
        val encoded = Files.readString(
            EvidenceStore(root).latest("tactical/legacy-leaf-benchmark.json")
        )

        assertTrue("\"MTGALLIUM_VISIBLE_V1\"" in encoded)
        val report = decodeLegacyTacticalLeafBenchmarkReport(encoded)

        assertTrue(report.completed)
        assertEquals(48, report.cases.size)
        assertEquals(5, report.leafResults.size)
        assertTrue(report.leafResults.any {
            it.leaf.evaluator == org.mtgallium.agent.infoset.core.LeafEvaluator.MTGALLIUM_VISIBLE_V2
        })
    }

    @Test
    fun `progress snapshots remain coherent across eight workers`() {
        val root = createTempDirectory("mtgallium-tournament-concurrency")
        val tracker = TournamentProgressTracker(
            progressPath = root.resolve("progress.json"),
            tournamentVersion = "test-v1",
            runIdentity = "parallel-run",
            outerCommit = "outer",
            argentumCommit = "argentum",
            baseSeed = 17L,
            pairsPerMatchup = 1,
            workerThreads = 8,
            heartbeatMillis = 60_000L,
            output = {},
            installShutdownHook = false,
        )
        val descriptors = (0 until 8).map { worker ->
            descriptor("parallel-$worker", "a", "alpha", "beta").copy(pairIndex = worker)
        }
        descriptors.forEach { tracker.prepareGame(it) }
        val executor = Executors.newFixedThreadPool(8)
        descriptors.forEach { descriptor ->
            executor.submit {
                repeat(100) { decision ->
                    val progress = ArenaDecisionProgress(
                        gameId = descriptor.gameId,
                        decisionIndex = decision,
                        turnNumber = decision / 10 + 1,
                        phase = "MAIN",
                        step = "PRECOMBAT_MAIN",
                        actor = if (decision % 2 == 0) "p0" else "p1",
                        lifeTotals = mapOf("p0" to 20 - decision / 20, "p1" to 20 - decision / 25),
                    )
                    tracker.decisionStarted(progress)
                    tracker.decisionCompleted(progress, 1.0)
                }
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        tracker.flushNow()
        val report = evidenceJson.decodeFromString<TournamentProgressReport>(
            Files.readString(root.resolve("progress.json"))
        )
        assertEquals(8, report.activeGames)
        assertEquals(8, report.games.map { it.gameId }.distinct().size)
        assertTrue(report.games.all { it.decisionsCompleted == 100 && it.decisionIndex == 99 })
        tracker.finish(TournamentRunProgressState.INTERRUPTED)

        val restarted = TournamentProgressTracker(
            progressPath = root.resolve("progress.json"),
            tournamentVersion = "test-v1",
            runIdentity = "parallel-run",
            outerCommit = "outer",
            argentumCommit = "argentum",
            baseSeed = 17L,
            pairsPerMatchup = 1,
            workerThreads = 8,
            heartbeatMillis = 60_000L,
            output = {},
            installShutdownHook = false,
        )
        restarted.prepareGame(descriptors.first())
        assertTrue(restarted.snapshot().games.single().restartedFromBeginning)
        assertEquals(1, restarted.snapshot().restartedFromBeginningGames)
        restarted.finish(TournamentRunProgressState.INTERRUPTED)
    }

    @Test
    fun `progress heartbeat exposes turn decisions life and elapsed time atomically`() {
        val root = createTempDirectory("mtgallium-tournament-progress")
        val progressPath = root.resolve("progress.json")
        val ticks = AtomicLong(0L)
        val base = Instant.parse("2026-08-25T00:00:00Z")
        val messages = mutableListOf<String>()
        val tracker = TournamentProgressTracker(
            progressPath = progressPath,
            tournamentVersion = "test-v1",
            runIdentity = "run-1",
            outerCommit = "outer",
            argentumCommit = "argentum",
            baseSeed = 17L,
            pairsPerMatchup = 1,
            workerThreads = 2,
            heartbeatMillis = 60_000L,
            output = messages::add,
            now = { base.plusNanos(ticks.get()) },
            nanoTime = ticks::get,
            installShutdownHook = false,
        )
        val first = descriptor("game-a", "a", "alpha", "beta")
        tracker.prepareGame(first)
        tracker.gameStarted(first.gameId, first.p0PolicyId, first.p1PolicyId)
        tracker.decisionStarted(
            ArenaDecisionProgress(
                gameId = first.gameId,
                decisionIndex = 7,
                turnNumber = 4,
                phase = "COMBAT",
                step = "DECLARE_ATTACKERS",
                actor = "p0",
                lifeTotals = mapOf("p0" to 13, "p1" to 8),
            )
        )
        ticks.set(12_500_000_000L)

        val heartbeat = tracker.snapshot()
        assertEquals(1, heartbeat.activeGames)
        assertEquals(29, heartbeat.queuedGames)
        assertEquals(4, heartbeat.games.single().turnNumber)
        assertEquals(7, heartbeat.games.single().decisionIndex)
        assertEquals(mapOf("p0" to 13, "p1" to 8), heartbeat.games.single().lifeTotals)
        assertEquals(12_500L, heartbeat.games.single().currentDecisionElapsedMillis)

        tracker.flushNow(printConsole = true)
        val decoded = evidenceJson.decodeFromString<TournamentProgressReport>(Files.readString(progressPath))
        assertEquals(heartbeat.runIdentity, decoded.runIdentity)
        assertEquals(heartbeat.games.single().lifeTotals, decoded.games.single().lifeTotals)
        assertTrue(messages.any { "turn=4" in it && "life=13/8" in it })

        tracker.decisionCompleted(heartbeat.games.single().let {
            ArenaDecisionProgress(
                gameId = it.gameId,
                decisionIndex = requireNotNull(it.decisionIndex),
                turnNumber = requireNotNull(it.turnNumber),
                phase = requireNotNull(it.phase),
                step = requireNotNull(it.step),
                actor = requireNotNull(it.actor),
                lifeTotals = it.lifeTotals,
            )
        }, 12_500.0)
        tracker.gameFinished(validGame(first.gameId, first.p0PolicyId, first.p1PolicyId))
        val second = descriptor("game-b", "b", "beta", "alpha")
        tracker.recordCheckpointReuse(second, validGame(second.gameId, second.p0PolicyId, second.p1PolicyId))

        val finished = tracker.snapshot()
        assertEquals(2, finished.finishedGames)
        assertEquals(1, finished.checkpointReusedGames)
        assertEquals(1, finished.finishedPairs)
        assertEquals(0, finished.failedGames)
        tracker.finish(TournamentRunProgressState.COMPLETED)
        assertEquals(
            TournamentRunProgressState.COMPLETED,
            evidenceJson.decodeFromString<TournamentProgressReport>(Files.readString(progressPath)).state,
        )
    }

    @Test
    fun `progress keeps O-04 stops distinct from engine-ended draws`() {
        val root = createTempDirectory("mtgallium-tournament-o04-progress")
        val messages = mutableListOf<String>()
        val tracker = TournamentProgressTracker(
            progressPath = root.resolve("progress.json"),
            tournamentVersion = "test-v1",
            runIdentity = "o04-progress",
            outerCommit = "outer",
            argentumCommit = "argentum",
            baseSeed = 17L,
            pairsPerMatchup = 1,
            workerThreads = 1,
            heartbeatMillis = 60_000L,
            output = messages::add,
            installShutdownHook = false,
        )
        val stoppedDescriptor = descriptor("stopped-a", "a", "alpha", "beta")
        val drawDescriptor = descriptor("draw-b", "b", "beta", "alpha")
        tracker.prepareGame(stoppedDescriptor)
        tracker.prepareGame(drawDescriptor)
        tracker.gameFinished(
            GameRunResult(
                gameId = stoppedDescriptor.gameId,
                seed = 1L,
                p0Policy = ArenaPolicyKind.SEARCH,
                p1Policy = ArenaPolicyKind.SEARCH,
                winner = null,
                terminal = false,
                disposition = GameRunDisposition.STOPPED_REPRESENTATION,
                evidenceStop = EvidenceStopMetadata(
                    triggerCodes = listOf("TEST_UNREPRESENTED"),
                    affectedViewers = listOf("p0"),
                    firstDetectedBeforeDecision = 3,
                    detectionPoint = EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION,
                    triggeringDecisionIndex = 2,
                    refusedPolicyDecisionIndex = 3,
                ),
                decisions = 2,
                searchSeat = null,
                searchScore = null,
                illegalResponses = 0,
                fallbacks = 0,
                stepLimit = false,
                exception = "UNSUPPORTED_INFORMATION_STATE:TEST_UNREPRESENTED",
                p0PolicyId = stoppedDescriptor.p0PolicyId,
                p1PolicyId = stoppedDescriptor.p1PolicyId,
            )
        )
        tracker.gameFinished(validGame(drawDescriptor.gameId, drawDescriptor.p0PolicyId, drawDescriptor.p1PolicyId).copy(winner = null))

        val report = tracker.snapshot()
        assertEquals(1, report.stoppedRepresentationGames)
        assertEquals(0, report.stoppedSoftwareGames)
        assertEquals(EvidenceFailureAccounting(), report.evidenceStopAccounting)
        assertEquals(GameRunDisposition.STOPPED_REPRESENTATION, report.lastEvidenceStop?.disposition)
        assertEquals(3, report.lastEvidenceStop?.refusedPolicyDecisionIndex)
        assertEquals(1, report.failedGames)
        assertTrue(report.lastFailure.orEmpty().contains("STOPPED_REPRESENTATION"))
        assertTrue(messages.any { "stopped=STOPPED_REPRESENTATION" in it })
        assertTrue(messages.any { "winner=draw" in it })
        assertFalse(messages.any { "stopped=STOPPED_REPRESENTATION" in it && "winner=draw" in it })
        tracker.flushNow()
        val persisted = evidenceJson.decodeFromString<TournamentProgressReport>(
            Files.readString(root.resolve("progress.json"))
        )
        assertEquals(report.evidenceStopAccounting, persisted.evidenceStopAccounting)
        assertEquals(report.lastEvidenceStop, persisted.lastEvidenceStop)
        tracker.finish(TournamentRunProgressState.FAILED)
    }

    @Test
    fun `tree reuse validation states the probability limit and production restriction`() {
        val report = passingReviewReport("2026-08-25T00:00:00Z")

        val markdown = renderTreeReuseValidation(report)

        assertTrue("does not make the number of retained paths proportional" in markdown)
        assertTrue("production reuse remains disabled" in markdown)
    }

    @Test
    fun `cleanup discard is a policy warning rather than an operational failure`() {
        val game = validGame("cleanup", "alpha", "beta").copy(
            replayPath = "replays/cleanup.jsonl.gz",
            replaySha256 = "abc123",
            replayVerified = true,
            cleanupDiscardEvents = 1,
        )

        assertTrue(operationallyValidGame(game))
        val warnings = cleanupPolicyQualityWarnings(listOf(game))
        assertEquals(1, warnings.size)
        assertTrue("1/1 games" in warnings.single())
    }

    private fun descriptor(gameId: String, leg: String, p0: String, p1: String) = TournamentGameDescriptor(
        gameId = gameId,
        firstPolicyId = "alpha",
        secondPolicyId = "beta",
        pairIndex = 0,
        leg = leg,
        p0PolicyId = p0,
        p1PolicyId = p1,
    )

    private fun validGame(gameId: String, p0: String, p1: String) = GameRunResult(
        gameId = gameId,
        seed = 1L,
        p0Policy = ArenaPolicyKind.SEARCH,
        p1Policy = ArenaPolicyKind.SEARCH,
        winner = "p0",
        terminal = true,
        disposition = GameRunDisposition.GAME_ENDED,
        decisions = 8,
        searchSeat = null,
        searchScore = null,
        illegalResponses = 0,
        fallbacks = 0,
        stepLimit = false,
        p0PolicyId = p0,
        p1PolicyId = p1,
    )

    private fun passingReviewReport(generated: String) = TreeReuseValidationReport(
        generatedAtUtc = generated,
        outerCommit = "outer",
        argentumCommit = "argentum",
        outerDirty = false,
        argentumDirty = false,
        baseSeed = 17L,
        simulationsPerDecision = 64,
        maxPolicyDecisions = 8,
        singleton = PolicySingletonValidation(
            rulesCandidateFamilies = emptyList(),
            profileCandidateFamilies = emptyList(),
            rulesExhaustive = true,
            profileExhaustive = false,
            omissionReasons = emptySet(),
            exactForced = false,
            selectionKind = null,
            simulationsAvoided = 64,
            semanticChoicePreserved = true,
            passed = true,
        ),
        strategicLandHold = StrategicLandHoldValidation(
            candidateFamilies = listOf(
                org.mtgallium.agent.infoset.core.SemanticOperationFamily.PASS_PRIORITY,
                org.mtgallium.agent.infoset.core.SemanticOperationFamily.PLAY_LAND,
            ),
            automaticSelectionKind = null,
            firstLandNetValueAfterLeavingHand = 0.65,
            sixthLandNetValueAfterLeavingHand = -0.20,
            passRemainsSearchable = true,
            diminishingResourceValue = true,
            passed = true,
        ),
        factorial = emptyList(),
        gates = TreeReuseValidationGates(
            singletonSemanticEquivalence = true,
            deterministicReplay = true,
            factorialSemanticEquivalence = true,
            reuseWorkRatio = 0.5,
            reuseWorkPassed = true,
            latencyRatioUpper95 = 0.9,
            latencyPassed = true,
            maximumRegret = 0.0,
            regretPassed = true,
            memoryPassed = true,
        ),
        passed = true,
        limitations = emptyList(),
    )
}
