package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val STOPPED_TOURNAMENT_RUN_ID =
    "7b689d268ba53eff76cb59c500c08fd671a12b800fedaae75110da0765100e0d"
internal const val STOPPED_TOURNAMENT_SEARCH_P50_MILLIS = 9_100.0
internal const val STOPPED_TOURNAMENT_SEARCH_P95_MILLIS = 20_375.6

@Serializable
internal data class TournamentPerformanceTrial(
    val gameId: String,
    val workerCount: Int,
    val gameSeed: Long,
    val gameElapsedMillis: Double,
    val search: ArenaSearchDecisionDiagnostic,
    val exception: String?,
    val informationLedgerComplete: Boolean,
)

@Serializable
internal data class TournamentPerformanceRegime(
    val workerCount: Int,
    val trials: List<TournamentPerformanceTrial>,
    val wallClockMillis: Double,
    val searchesPerSecond: Double,
    val p50SearchMillis: Double,
    val p95SearchMillis: Double,
    val meanSearchMillis: Double,
    val p50SpeedupFromStoppedRun: Double,
    val p95SpeedupFromStoppedRun: Double,
)

@Serializable
internal data class TournamentPerformanceReport(
    val schemaVersion: Int = 3,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val deckHash: String,
    val stoppedRunIdentity: String = STOPPED_TOURNAMENT_RUN_ID,
    val stoppedRunP50SearchMillis: Double = STOPPED_TOURNAMENT_SEARCH_P50_MILLIS,
    val stoppedRunP95SearchMillis: Double = STOPPED_TOURNAMENT_SEARCH_P95_MILLIS,
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val warmupTrials: Int,
    val recommendedWorkerCount: Int,
    val singleWorker: TournamentPerformanceRegime,
    val fourWorkers: TournamentPerformanceRegime,
    val eightWorkers: TournamentPerformanceRegime,
    /** Observational profiler: it carries no latency SLO for the scientific teacher. */
    val targetP50Millis: Double? = null,
    /** Observational profiler: it carries no latency SLO for the scientific teacher. */
    val targetP95Millis: Double? = null,
    val jfrPath: String,
    val executionSamples: Int,
    val topFlameStacks: List<FlameStackSample>,
    val parallelJfrPath: String,
    val parallelExecutionSamples: Int,
    val parallelTopFlameStacks: List<FlameStackSample>,
    val eightToFourThroughputRatio: Double,
    val passed: Boolean,
    val failureReasons: List<String>,
)

/**
 * Real full-deck, real-engine profiler. Each trial stops after its first MCTS decision; mulligans,
 * rules passes, belief construction, and all prior live transitions still execute normally.
 */
internal class TournamentPerformanceProfiler(
    private val root: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val manifest: DeckManifest,
    private val baseSeed: Long,
) {
    private val evidence = EvidenceStore(root)
    fun run(singleWorkerTrials: Int = 3, parallelTrials: Int = 8): TournamentPerformanceReport {
        require(singleWorkerTrials > 0)
        require(parallelTrials >= 8)
        // This profiler is an operational measurement of the source-current qualified teacher,
        // not a Core Six leaf comparison. The Core Six roster's first search entry currently
        // uses a CURRENT_INFORMATION_STATE leaf, whereas qualification binds the complete
        // runtime composition below, including BOUNDED_ROLLOUT settlement.
        val (policy, opponent) = OutcomeQualificationPilotRoster.policies()
        val arena = SearchTeacherArena(
            registry,
            manifest,
            SearchTeacherArena.smokeProfile(),
            baseSeed,
        )

        // A single discarded search does not cover the rules/card surface reached by eight
        // distinct opening hands. Warm one full representative batch as well: JFR showed the old
        // "warm" parallel measurement compiling 17k methods (37.7 CPU-seconds of compiler work),
        // which measured JVM startup rather than tournament search throughput.
        runTrial(arena, policy, opponent, -1, 1)
        parallelMapOrdered(parallelTrials, 8) { index ->
            runTrial(arena, policy, opponent, -10_000 - index, 8)
        }
        val jfrPath = evidence.diagnostic(
            "tournament-performance/profile.jfr",
            "the single-worker tournament performance recording",
        )
        Files.createDirectories(jfrPath.parent)
        val recording = Recording(Configuration.getConfiguration("profile")).apply {
            name = "mtgallium-real-tournament-performance"
            enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5))
        }
        recording.use {
            it.start()
            try {
                runTrial(arena, policy, opponent, 0, 1)
            } finally {
                it.stop()
                it.dump(jfrPath)
            }
        }
        val singleStarted = System.nanoTime()
        val single = List(singleWorkerTrials) { index ->
            runTrial(arena, policy, opponent, index, 1)
        }
        val singleWallMillis = elapsedMillis(singleStarted)
        val fourStarted = System.nanoTime()
        val four = parallelMapOrdered(parallelTrials, 4) { index ->
            runTrial(arena, policy, opponent, 20_000 + index, 4)
        }
        val fourWallMillis = elapsedMillis(fourStarted)
        val parallelJfrPath = evidence.diagnostic(
            "tournament-performance/profile-eight-workers.jfr",
            "the multi-worker tournament performance recording",
        )
        val parallelRecording = Recording(Configuration.getConfiguration("profile")).apply {
            name = "mtgallium-real-tournament-performance-eight-workers"
            enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5))
        }
        val parallelStarted = System.nanoTime()
        val parallel = parallelRecording.use {
            it.start()
            try {
                parallelMapOrdered(parallelTrials, 8) { index ->
                    runTrial(arena, policy, opponent, 10_000 + index, 8)
                }
            } finally {
                it.stop()
                it.dump(parallelJfrPath)
            }
        }
        val parallelWallMillis = elapsedMillis(parallelStarted)
        val singleRegime = summarize(1, single, singleWallMillis)
        val fourRegime = summarize(4, four, fourWallMillis)
        val parallelRegime = summarize(8, parallel, parallelWallMillis)
        val flameSamples = readFlameSamples(jfrPath)
        val parallelFlameSamples = readFlameSamples(parallelJfrPath)
        val failures = buildList {
            (single + four + parallel).filter { it.exception != null }.forEach {
                add("${it.gameId}: ${it.exception}")
            }
            (single + four + parallel).filterNot { it.informationLedgerComplete }.forEach {
                add("${it.gameId}: incomplete information ledger")
            }
            (single + four + parallel).filter {
                it.search.searchDiagnostics.simulations != 64 ||
                    it.search.searchDiagnostics.particles != 8
            }.forEach { add("${it.gameId}: fixed 8x64x32 workload changed") }
            (single + four + parallel).filter {
                it.search.searchDiagnostics.leaf != OutcomeQualificationPilotRoster.runtime.leaf
            }.forEach { add("${it.gameId}: qualified leaf configuration changed") }
            (single + four + parallel).filter {
                it.search.searchDiagnostics.rejectedTransitions != 0
            }.forEach {
                add("${it.gameId}: ${it.search.searchDiagnostics.rejectedTransitions} rejected search transitions")
            }
        }
        return TournamentPerformanceReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            deckHash = manifest.deckHash(),
            particles = OutcomeQualificationPilotRoster.runtime.particles,
            simulations = OutcomeQualificationPilotRoster.runtime.simulations,
            maxPolicyDecisions = OutcomeQualificationPilotRoster.runtime.maxPolicyDecisions,
            warmupTrials = parallelTrials + 1,
            recommendedWorkerCount = 4,
            singleWorker = singleRegime,
            fourWorkers = fourRegime,
            eightWorkers = parallelRegime,
            jfrPath = root.relativize(jfrPath).toString(),
            executionSamples = flameSamples.sumOf(FlameStackSample::samples),
            topFlameStacks = flameSamples.take(50),
            parallelJfrPath = root.relativize(parallelJfrPath).toString(),
            parallelExecutionSamples = parallelFlameSamples.sumOf(FlameStackSample::samples),
            parallelTopFlameStacks = parallelFlameSamples.take(50),
            eightToFourThroughputRatio = parallelRegime.searchesPerSecond / fourRegime.searchesPerSecond,
            passed = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    private fun runTrial(
        arena: SearchTeacherArena,
        policy: ArenaPolicySpec,
        opponent: ArenaPolicySpec,
        index: Int,
        workers: Int,
    ): TournamentPerformanceTrial {
        val seed = ComponentSeeds.derive(baseSeed, index, workers, "real-tournament-performance")
        val id = "performance-$workers-${index.toString().padStart(5, '0')}"
        val game = arena.playWithPolicies(
            gameId = id,
            gameSeed = seed,
            p0Policy = policy,
            p1Policy = opponent,
            maxSearchDecisions = 1,
        )
        val search = requireNotNull(game.seatDiagnostics["p0"]?.searchDecisionsDetail?.singleOrNull()) {
            "Profiler trial $id did not reach exactly one p0 search decision: $game"
        }
        return TournamentPerformanceTrial(
            gameId = id,
            workerCount = workers,
            gameSeed = seed,
            gameElapsedMillis = requireNotNull(game.elapsedMillis),
            search = search,
            exception = game.exception,
            informationLedgerComplete = game.informationLedgerComplete,
        )
    }

    private fun summarize(
        workers: Int,
        trials: List<TournamentPerformanceTrial>,
        wallClockMillis: Double,
    ): TournamentPerformanceRegime {
        val latencies = trials.map { it.search.latencyMillis }
        return TournamentPerformanceRegime(
            workerCount = workers,
            trials = trials,
            wallClockMillis = wallClockMillis,
            searchesPerSecond = trials.size * 1_000.0 / wallClockMillis,
            p50SearchMillis = percentile(latencies, 0.50),
            p95SearchMillis = percentile(latencies, 0.95),
            meanSearchMillis = latencies.average(),
            p50SpeedupFromStoppedRun = STOPPED_TOURNAMENT_SEARCH_P50_MILLIS / percentile(latencies, 0.50),
            p95SpeedupFromStoppedRun = STOPPED_TOURNAMENT_SEARCH_P95_MILLIS / percentile(latencies, 0.95),
        )
    }

    private fun elapsedMillis(startedNanos: Long): Double =
        (System.nanoTime() - startedNanos) / 1_000_000.0
}
