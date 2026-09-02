package org.mtgallium.evaluation.searchteacher

import java.io.Closeable
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumHeuristicChoiceDiagnosis
import org.mtgallium.agent.infoset.core.PolicyObservation

internal interface ArenaProgressObserver {
    fun gameStarted(gameId: String, p0PolicyId: String, p1PolicyId: String) = Unit
    fun decisionStarted(progress: ArenaDecisionProgress) = Unit
    fun decisionCompleted(progress: ArenaDecisionProgress, elapsedMillis: Double) = Unit
    fun policyFallback(progress: ArenaPolicyFallbackProgress) = Unit
    fun gameFinished(result: GameRunResult) = Unit

    companion object {
        val NONE: ArenaProgressObserver = object : ArenaProgressObserver {}
    }
}

internal data class ArenaPolicyFallbackProgress(
    val gameId: String,
    val decisionIndex: Int,
    val actor: String,
    val diagnosis: ArgentumHeuristicChoiceDiagnosis,
)

internal data class ArenaDecisionProgress(
    val gameId: String,
    val decisionIndex: Int,
    val turnNumber: Int,
    val phase: String,
    val step: String,
    val actor: String,
    val lifeTotals: Map<String, Int>,
) {
    companion object {
        fun from(gameId: String, decisionIndex: Int, actor: String, observation: PolicyObservation) =
            ArenaDecisionProgress(
                gameId = gameId,
                decisionIndex = decisionIndex,
                turnNumber = observation.turnNumber,
                phase = observation.phase,
                step = observation.step,
                actor = actor,
                lifeTotals = observation.players.associate { it.playerId to it.life }.toSortedMap(),
            )
    }
}

@Serializable
internal enum class TournamentRunProgressState {
    RUNNING,
    COMPLETED,
    FAILED,
    INTERRUPTED,
}

@Serializable
internal data class TournamentActiveGameProgress(
    val gameId: String,
    val firstPolicyId: String,
    val secondPolicyId: String,
    val pairIndex: Int,
    val leg: String,
    val p0PolicyId: String,
    val p1PolicyId: String,
    val restartedFromBeginning: Boolean = false,
    val startedAtUtc: String,
    val elapsedMillis: Long,
    val decisionsCompleted: Int,
    val decisionIndex: Int? = null,
    val turnNumber: Int? = null,
    val phase: String? = null,
    val step: String? = null,
    val actor: String? = null,
    val lifeTotals: Map<String, Int> = emptyMap(),
    val currentDecisionElapsedMillis: Long? = null,
    val lastDecisionMillis: Double? = null,
)

@Serializable
internal data class TournamentEvidenceStopProgress(
    val gameId: String,
    val disposition: GameRunDisposition,
    val detectionPoint: EvidenceStopDetectionPoint,
    val triggeringDecisionIndex: Int? = null,
    val refusedPolicyDecisionIndex: Int,
    val triggerCodes: List<String>,
    val affectedViewers: List<String>,
    val accounting: EvidenceFailureAccounting,
)

@Serializable
internal data class TournamentProgressReport(
    val schemaVersion: Int = 2,
    val tournamentVersion: String,
    val runIdentity: String,
    val generatedAtUtc: String,
    val state: TournamentRunProgressState,
    val outerCommit: String,
    val argentumCommit: String,
    val baseSeed: Long,
    val pairsPerMatchup: Int,
    val workerThreads: Int,
    val totalPairs: Int,
    val totalGames: Int,
    val queuedGames: Int,
    val activeGames: Int,
    val finishedGames: Int,
    val checkpointReusedGames: Int,
    val restartedFromBeginningGames: Int = 0,
    val failedGames: Int,
    val finishedPairs: Int,
    val elapsedMillis: Long,
    val games: List<TournamentActiveGameProgress>,
    val lastFailure: String? = null,
    /** O-04(a) work-only failure denominators; stopped games are never draws. */
    val stoppedRepresentationGames: Int = 0,
    val stoppedSoftwareGames: Int = 0,
    val evidenceStopAccounting: EvidenceFailureAccounting = EvidenceFailureAccounting(0, 0, 0),
    val lastEvidenceStop: TournamentEvidenceStopProgress? = null,
    val recordKind: String = "ORIGINAL",
    val policyIds: List<String> = emptyList(),
    val baselineAmendmentIdentity: String? = null,
    val baselinePairsPath: String? = null,
    val baselinePairsSha256: String? = null,
    val baselinePairs: Int = 0,
    val baselineGames: Int = 0,
    val extensionPairs: Int? = null,
    val extensionGames: Int? = null,
)

internal data class TournamentGameDescriptor(
    val gameId: String,
    val firstPolicyId: String,
    val secondPolicyId: String,
    val pairIndex: Int,
    val leg: String,
    val p0PolicyId: String,
    val p1PolicyId: String,
) {
    val pairId: String = "$firstPolicyId--$secondPolicyId--$pairIndex"
}

private data class ActiveGameRuntime(
    val descriptor: TournamentGameDescriptor,
    val startedAt: Instant,
    val startedNanos: Long,
    val state: AtomicReference<ActiveDecisionRuntime> = AtomicReference(ActiveDecisionRuntime()),
)

private data class ActiveDecisionRuntime(
    val progress: ArenaDecisionProgress? = null,
    val decisionStartedNanos: Long? = null,
    val decisionsCompleted: Int = 0,
    val lastDecisionMillis: Double? = null,
)

internal class TournamentProgressTracker(
    private val progressPath: Path,
    private val tournamentVersion: String,
    private val runIdentity: String,
    private val outerCommit: String,
    private val argentumCommit: String,
    private val baseSeed: Long,
    private val pairsPerMatchup: Int,
    private val workerThreads: Int,
    private val matchupCount: Int = 15,
    private val baselinePairs: Int = 0,
    private val baselineGames: Int = 0,
    private val recordKind: String = "ORIGINAL",
    private val policyIds: List<String> = emptyList(),
    private val baselineAmendmentIdentity: String? = null,
    private val baselinePairsPath: String? = null,
    private val baselinePairsSha256: String? = null,
    private val heartbeatMillis: Long = 10_000L,
    private val output: (String) -> Unit = ::println,
    private val now: () -> Instant = Instant::now,
    private val nanoTime: () -> Long = System::nanoTime,
    installShutdownHook: Boolean = true,
) : ArenaProgressObserver, Closeable {
    private val scheduledPairs = matchupCount * pairsPerMatchup
    private val scheduledGames = scheduledPairs * 2
    private val totalPairs = baselinePairs + scheduledPairs
    private val totalGames = baselineGames + scheduledGames
    private val startedNanos = nanoTime()
    private val active = ConcurrentHashMap<String, ActiveGameRuntime>()
    private val descriptors = ConcurrentHashMap<String, TournamentGameDescriptor>()
    private val interruptedGameIds = loadInterruptedGameIds(progressPath, runIdentity)
    private val restartedGameIds = ConcurrentHashMap.newKeySet<String>()
    private val finishedGameIds = ConcurrentHashMap.newKeySet<String>()
    private val reusedGameIds = ConcurrentHashMap.newKeySet<String>()
    private val failedGameIds = ConcurrentHashMap.newKeySet<String>()
    private val evidenceStops = ConcurrentHashMap<String, TournamentEvidenceStopProgress>()
    private val lastEvidenceStop = AtomicReference<TournamentEvidenceStopProgress?>(null)
    private val finishedPairIds = ConcurrentHashMap.newKeySet<String>()
    private val lastFailure = AtomicReference<String?>(null)
    private val runState = AtomicReference(TournamentRunProgressState.RUNNING)
    private val closed = AtomicBoolean(false)
    private val flushLock = Any()
    private val reporter = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "core-six-progress-reporter").apply { isDaemon = true }
    }
    private val shutdownHook = Thread(
        { finish(TournamentRunProgressState.INTERRUPTED) },
        "core-six-progress-shutdown",
    )

    init {
        require(heartbeatMillis > 0)
        require(matchupCount > 0)
        require(baselinePairs >= 0 && baselineGames >= 0)
        require(baselineGames == baselinePairs * 2) { "Each baseline pair must contain two games" }
        require(recordKind.isNotBlank())
        require(policyIds.distinct().size == policyIds.size)
        if (installShutdownHook) Runtime.getRuntime().addShutdownHook(shutdownHook)
        reporter.scheduleAtFixedRate(
            { safeFlush(printConsole = true) },
            heartbeatMillis,
            heartbeatMillis,
            TimeUnit.MILLISECONDS,
        )
        requestFlush()
    }

    fun prepareGame(descriptor: TournamentGameDescriptor) {
        check(descriptors.putIfAbsent(descriptor.gameId, descriptor) == null) {
            "Tournament game already registered: ${descriptor.gameId}"
        }
        val runtime = ActiveGameRuntime(descriptor, now(), nanoTime())
        check(active.putIfAbsent(descriptor.gameId, runtime) == null) {
            "Tournament game already active: ${descriptor.gameId}"
        }
        if (descriptor.gameId in interruptedGameIds) restartedGameIds += descriptor.gameId
        output(
            "Tournament game started: ${descriptor.firstPolicyId} vs ${descriptor.secondPolicyId} " +
                "pair ${descriptor.pairIndex} leg ${descriptor.leg} (${descriptor.gameId})" +
                if (descriptor.gameId in restartedGameIds) " [restarted from seed]" else ""
        )
        requestFlush()
    }

    fun recordCheckpointReuse(descriptor: TournamentGameDescriptor, result: GameRunResult) {
        descriptors.putIfAbsent(descriptor.gameId, descriptor)
        reusedGameIds += descriptor.gameId
        finishGame(descriptor, result)
    }

    fun abandonGame(gameId: String, error: Throwable) {
        val descriptor = descriptors[gameId] ?: return
        active.remove(gameId)
        failedGameIds += gameId
        finishedGameIds += gameId
        lastFailure.set("$gameId: ${error::class.qualifiedName}: ${error.message}")
        updateFinishedPair(descriptor)
        output("Tournament game failed: $gameId: ${error::class.simpleName}: ${error.message}")
        requestFlush()
    }

    override fun gameStarted(gameId: String, p0PolicyId: String, p1PolicyId: String) {
        val descriptor = requireNotNull(descriptors[gameId]) { "Unregistered tournament game: $gameId" }
        check(descriptor.p0PolicyId == p0PolicyId && descriptor.p1PolicyId == p1PolicyId) {
            "Tournament policy mismatch for $gameId"
        }
    }

    override fun decisionStarted(progress: ArenaDecisionProgress) {
        val runtime = active[progress.gameId] ?: return
        runtime.state.updateAndGet { previous ->
            previous.copy(progress = progress, decisionStartedNanos = nanoTime())
        }
    }

    override fun decisionCompleted(progress: ArenaDecisionProgress, elapsedMillis: Double) {
        val runtime = active[progress.gameId] ?: return
        runtime.state.updateAndGet { previous ->
            previous.copy(
                progress = progress,
                decisionStartedNanos = null,
                decisionsCompleted = progress.decisionIndex + 1,
                lastDecisionMillis = elapsedMillis,
            )
        }
    }

    override fun gameFinished(result: GameRunResult) {
        val descriptor = descriptors[result.gameId] ?: return
        finishGame(descriptor, result)
    }

    fun snapshot(): TournamentProgressReport {
        val snapshotNanos = nanoTime()
        val activeGames = active.values.map { runtime ->
            val state = runtime.state.get()
            val progress = state.progress
            TournamentActiveGameProgress(
                gameId = runtime.descriptor.gameId,
                firstPolicyId = runtime.descriptor.firstPolicyId,
                secondPolicyId = runtime.descriptor.secondPolicyId,
                pairIndex = runtime.descriptor.pairIndex,
                leg = runtime.descriptor.leg,
                p0PolicyId = runtime.descriptor.p0PolicyId,
                p1PolicyId = runtime.descriptor.p1PolicyId,
                restartedFromBeginning = runtime.descriptor.gameId in restartedGameIds,
                startedAtUtc = runtime.startedAt.toString(),
                elapsedMillis = nanosToMillis(snapshotNanos - runtime.startedNanos),
                decisionsCompleted = state.decisionsCompleted,
                decisionIndex = progress?.decisionIndex,
                turnNumber = progress?.turnNumber,
                phase = progress?.phase,
                step = progress?.step,
                actor = progress?.actor,
                lifeTotals = progress?.lifeTotals ?: emptyMap(),
                currentDecisionElapsedMillis = state.decisionStartedNanos?.let {
                    nanosToMillis(snapshotNanos - it)
                },
                lastDecisionMillis = state.lastDecisionMillis,
            )
        }.sortedWith(
            compareBy<TournamentActiveGameProgress> { it.firstPolicyId }
                .thenBy { it.secondPolicyId }
                .thenBy { it.pairIndex }
                .thenBy { it.leg }
        )
        val finishedScheduledGames = finishedGameIds.size
        val finishedGames = baselineGames + finishedScheduledGames
        val stops = evidenceStops.values.toList()
        val stopAccounting = EvidenceFailureAccounting(
            reached = stops.sumOf { it.accounting.reached },
            refused = stops.sumOf { it.accounting.refused },
            degraded = stops.sumOf { it.accounting.degraded },
        )
        return TournamentProgressReport(
            tournamentVersion = tournamentVersion,
            runIdentity = runIdentity,
            generatedAtUtc = now().toString(),
            state = runState.get(),
            outerCommit = outerCommit,
            argentumCommit = argentumCommit,
            baseSeed = baseSeed,
            pairsPerMatchup = pairsPerMatchup,
            workerThreads = workerThreads,
            totalPairs = totalPairs,
            totalGames = totalGames,
            queuedGames = (totalGames - finishedGames - activeGames.size).coerceAtLeast(0),
            activeGames = activeGames.size,
            finishedGames = finishedGames,
            checkpointReusedGames = reusedGameIds.size,
            restartedFromBeginningGames = restartedGameIds.size,
            failedGames = failedGameIds.size,
            finishedPairs = baselinePairs + finishedPairIds.size,
            elapsedMillis = nanosToMillis(snapshotNanos - startedNanos),
            games = activeGames,
            lastFailure = lastFailure.get(),
            stoppedRepresentationGames = stops.count { it.disposition == GameRunDisposition.STOPPED_REPRESENTATION },
            stoppedSoftwareGames = stops.count { it.disposition == GameRunDisposition.STOPPED_SOFTWARE },
            evidenceStopAccounting = stopAccounting,
            lastEvidenceStop = lastEvidenceStop.get(),
            recordKind = recordKind,
            policyIds = policyIds,
            baselineAmendmentIdentity = baselineAmendmentIdentity,
            baselinePairsPath = baselinePairsPath,
            baselinePairsSha256 = baselinePairsSha256,
            baselinePairs = baselinePairs,
            baselineGames = baselineGames,
            extensionPairs = scheduledPairs.takeIf { recordKind == "EXTENSION" },
            extensionGames = scheduledGames.takeIf { recordKind == "EXTENSION" },
        )
    }

    internal fun flushNow(printConsole: Boolean = false) = safeFlush(printConsole)

    fun finish(state: TournamentRunProgressState) {
        if (!closed.compareAndSet(false, true)) return
        runState.set(state)
        reporter.shutdownNow()
        safeFlush(printConsole = true)
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    override fun close() = finish(
        if (failedGameIds.isEmpty()) TournamentRunProgressState.COMPLETED else TournamentRunProgressState.FAILED
    )

    private fun finishGame(descriptor: TournamentGameDescriptor, result: GameRunResult) {
        active.remove(descriptor.gameId)
        result.evidenceStop?.let { stop ->
            val recorded = TournamentEvidenceStopProgress(
                gameId = descriptor.gameId,
                disposition = result.disposition,
                detectionPoint = stop.detectionPoint,
                triggeringDecisionIndex = stop.triggeringDecisionIndex,
                refusedPolicyDecisionIndex = stop.refusedPolicyDecisionIndex,
                triggerCodes = stop.triggerCodes,
                affectedViewers = stop.affectedViewers,
                accounting = stop.accounting,
            )
            evidenceStops.putIfAbsent(
                descriptor.gameId,
                recorded,
            )
            lastEvidenceStop.set(recorded)
        }
        if (!validTournamentProgressGame(result)) {
            failedGameIds += descriptor.gameId
            lastFailure.set(
                "${descriptor.gameId}: ${result.disposition}; " +
                    (result.exception ?: result.evidenceStop?.triggerCodes?.joinToString(",") ?: "operational invariant")
            )
        }
        if (finishedGameIds.add(descriptor.gameId)) updateFinishedPair(descriptor)
        val resultLabel = if (
            result.disposition == GameRunDisposition.GAME_ENDED && result.terminal && result.evidenceStop == null
        ) {
            "winner=${result.winner ?: "draw"}"
        } else {
            val stop = result.evidenceStop
            "stopped=${result.disposition}" +
                stop?.let {
                    "; detection=${it.detectionPoint}; refusedDecision=${it.refusedPolicyDecisionIndex}; " +
                        "triggers=${it.triggerCodes.joinToString(",")}"
                }.orEmpty()
        }
        output(
            "Tournament game complete: ${descriptor.firstPolicyId} vs ${descriptor.secondPolicyId} " +
                "pair ${descriptor.pairIndex} leg ${descriptor.leg}; decisions=${result.decisions}; " +
                "$resultLabel${if (result.exception == null) "" else "; error=${result.exception}"}"
        )
        requestFlush()
    }

    private fun updateFinishedPair(descriptor: TournamentGameDescriptor) {
        val legs = descriptors.values.count { it.pairId == descriptor.pairId && it.gameId in finishedGameIds }
        if (legs == 2) finishedPairIds += descriptor.pairId
    }

    private fun requestFlush() {
        if (closed.get()) return
        runCatching { reporter.execute { safeFlush(printConsole = false) } }
    }

    private fun safeFlush(printConsole: Boolean) {
        synchronized(flushLock) {
            runCatching {
                val report = snapshot()
                writeJsonAtomically(progressPath, report)
                if (printConsole) printReport(report)
            }.onFailure { error ->
                lastFailure.set("progress reporter: ${error::class.qualifiedName}: ${error.message}")
                System.err.println("Tournament progress reporter failed: ${error.message}")
            }
        }
    }

    private fun printReport(report: TournamentProgressReport) {
        output(
            "Tournament progress: games=${report.finishedGames}/${report.totalGames} " +
                "pairs=${report.finishedPairs}/${report.totalPairs} active=${report.activeGames} " +
                "queued=${report.queuedGames} reused=${report.checkpointReusedGames} failed=${report.failedGames} " +
                "restarted=${report.restartedFromBeginningGames} " +
                "elapsed=${formatDuration(report.elapsedMillis)}"
        )
        report.games.forEach { game ->
            val life = listOf("p0", "p1").joinToString("/") { seat ->
                game.lifeTotals[seat]?.toString() ?: "?"
            }
            output(
                "  ${game.gameId}${if (game.restartedFromBeginning) " [restarted]" else ""}: " +
                    "turn=${game.turnNumber ?: "?"} decision=${game.decisionIndex ?: 0} " +
                    "completed=${game.decisionsCompleted} actor=${game.actor ?: "initializing"} life=$life " +
                    "phase=${game.phase ?: "?"}/${game.step ?: "?"} " +
                    "decisionElapsed=${game.currentDecisionElapsedMillis?.let(::formatDuration) ?: "between decisions"} " +
                    "gameElapsed=${formatDuration(game.elapsedMillis)}"
            )
        }
    }
}

private fun loadInterruptedGameIds(progressPath: Path, runIdentity: String): Set<String> = runCatching {
    evidenceJson.decodeFromString<TournamentProgressReport>(java.nio.file.Files.readString(progressPath))
        .takeIf { it.runIdentity == runIdentity && it.state != TournamentRunProgressState.COMPLETED }
        ?.games
        ?.mapTo(linkedSetOf()) { it.gameId }
        ?: emptySet()
}.getOrDefault(emptySet())

private fun validTournamentProgressGame(game: GameRunResult): Boolean =
    game.disposition == GameRunDisposition.GAME_ENDED && game.evidenceStop == null &&
        game.terminal && !game.stepLimit && game.exception == null && game.illegalResponses == 0 &&
        game.fallbacks == 0 && game.informationLedgerComplete

private fun nanosToMillis(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanos.coerceAtLeast(0L))

private fun formatDuration(millis: Long): String {
    val duration = Duration.ofMillis(millis.coerceAtLeast(0L))
    val hours = duration.toHours()
    val minutes = duration.minusHours(hours).toMinutes()
    val seconds = duration.minusHours(hours).minusMinutes(minutes).seconds
    return if (hours > 0) "%dh%02dm%02ds".format(hours, minutes, seconds) else "%dm%02ds".format(minutes, seconds)
}
