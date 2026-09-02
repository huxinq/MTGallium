package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Random
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.searchteacher.SearchReuseConfig
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceLocation
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance

internal const val TOURNAMENT_V3_CALIBRATED_VERSION = "v3-calibrated-current-policies-v1"
internal const val TOURNAMENT_V3_POLICY_ID =
    "search-current_information_state-mtgallium_tactical_v3"
internal const val TOURNAMENT_V3_CALIBRATED_MATCHUPS = 3
internal const val TOURNAMENT_V3_CALIBRATED_PAIRS = 75
internal const val TOURNAMENT_V3_CALIBRATED_GAMES = 150

internal val TOURNAMENT_V3_CALIBRATED_OPPONENT_IDS = listOf(
    "search-current_information_state-mtgallium_visible_v2",
    "search-bounded_rollout-mtgallium_visible_v2",
    "argentum-production-heuristic",
)

@Serializable
internal data class TournamentGameProvenance(
    val gameId: String,
    val firstPolicyId: String,
    val secondPolicyId: String,
    val pairIndex: Int,
    val leg: String,
    val origin: String,
    val evidenceIdentity: String,
    val replayPath: String,
    val replaySha256: String,
)

@Serializable
internal data class TournamentPolicyOperationalSummary(
    val policyId: String,
    val games: Int,
    val validGames: Int,
    val replayVerifiedGames: Int,
    val searchDecisions: Int,
    val actualSimulations: Long,
    val freshSimulations: Long,
    val reusedSimulations: Long,
    val searchWorldSteps: Long,
    val meanSearchLatencyMillis: Double?,
    val p95SearchLatencyMillis: Double?,
    val totalParticipantGameElapsedMillis: Double,
)

@Serializable
internal data class TournamentRateEstimate(
    val policyId: String,
    val opponentPolicyId: String? = null,
    val pairs: Int,
    val games: Int,
    val points: Double,
    val pointRate: Double,
    val confidenceLower: Double,
    val confidenceUpper: Double,
)

@Serializable
internal data class TournamentV3CalibratedReport(
    val schemaVersion: Int = 2,
    val recordKind: String = "CALIBRATED",
    val tournamentVersion: String = TOURNAMENT_V3_CALIBRATED_VERSION,
    val runIdentity: String,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceProvenance: PolicySourceProvenance? = null,
    val policyEvidenceIdentities: Map<String, String> = emptyMap(),
    /** Legacy launch-review fields retained only so historical reports still decode. */
    val reviewManifestSha256: String = "",
    val reviewerName: String = "",
    val reviewedAtUtc: String = "",
    val reviewReference: String = "",
    val deckHash: String,
    val baseSeed: Long,
    val pairsPerMatchup: Int,
    val workerThreads: Int,
    val runtimeWarmupWorkers: Int,
    val policies: List<TournamentPolicyDescription>,
    val opponentPolicyIds: List<String>,
    val matchups: List<TournamentMatchupSummary>,
    val standings: List<TournamentStanding>,
    val startingPlayerRating: Double,
    val v3FieldPointRate: TournamentRateEstimate,
    val v3HeadToHead: List<TournamentRateEstimate>,
    val operationalByPolicy: List<TournamentPolicyOperationalSummary>,
    val gameProvenance: List<TournamentGameProvenance>,
    val analyticsPath: String =
        EvidenceLocation.WORK.relativePath("tournament/$runIdentity/analytics.json"),
    val completePairs: Int,
    val gameCount: Int,
    val fixedWork: Boolean = true,
    val partialRoundRobin: Boolean = true,
    val independentFromHistoricalTournament: Boolean = true,
    val fieldRateScope: String =
        "The v3 aggregate field rate covers only the declared direct-v2, prior-winner, and production-heuristic anchors under the recorded implementations.",
    val inferenceScope: String =
        "Strength of the complete v3 search policy in this standalone calibrated gauntlet at 8 particles, 64 simulations, and depth 32; not a leaf-only causal estimate, a full round robin, or a general-strength claim.",
    val continuityStatement: String =
        "No games or ratings from the earlier six-player tournament are pooled into this record because the heuristic-adapter repair materially changed policy behavior.",
    val reuseAsymmetry: String =
        "Every policy uses its recorded search-reuse configuration; production tree reuse remains disabled.",
    val valid: Boolean,
    val failureReasons: List<String>,
)

internal object V3CalibratedRoster {
    fun policies(): List<ArenaPolicySpec> {
        val historical = CoreSixRoster.policies()
        val byId = historical.associateBy(ArenaPolicySpec::id)
        val opponents = TOURNAMENT_V3_CALIBRATED_OPPONENT_IDS.map { id ->
            requireNotNull(byId[id]) { "Calibrated v3 opponent is absent from the core-six roster: $id" }
        }
        val base = requireNotNull(historical.first().profile)
        val v3 = ArenaPolicySpec(
            id = TOURNAMENT_V3_POLICY_ID,
            kind = ArenaPolicyKind.SEARCH,
            profile = base.copy(
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_TACTICAL_V3,
                ),
            ),
            beliefMode = historical.first().beliefMode,
            beliefArchitecture = historical.first().beliefArchitecture,
            searchPlanner = historical.first().searchPlanner,
            policyCompression = historical.first().policyCompression,
            searchReuse = SearchReuseConfig(enabled = false),
        )
        return opponents + v3
    }

    fun descriptions(): List<TournamentPolicyDescription> = policies().map(::describeTournamentPolicy)

    fun opponents(policies: List<ArenaPolicySpec> = policies()): List<ArenaPolicySpec> {
        require(policies.size == 4 && policies.last().id == TOURNAMENT_V3_POLICY_ID)
        require(policies.dropLast(1).map(ArenaPolicySpec::id) == TOURNAMENT_V3_CALIBRATED_OPPONENT_IDS)
        return policies.dropLast(1)
    }

    fun rosterSha256(): String = sha256(evidenceJson.encodeToString(descriptions()))
}

internal class TournamentV3CalibratedRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val deckManifest: DeckManifest,
    private val baseSeed: Long,
) {
    private val evidence = EvidenceStore(root)

    fun run(pairsPerMatchup: Int, workerThreads: Int): TournamentV3CalibratedReport {
        require(pairsPerMatchup == 25) { "The calibrated tournament requires exactly 25 pairs per matchup" }
        require(workerThreads == 4) { "The calibrated tournament uses exactly four workers" }
        val policies = V3CalibratedRoster.policies()
        val descriptions = policies.map(::describeTournamentPolicy)
        val opponents = V3CalibratedRoster.opponents(policies)
        val v3 = policies.last()
        val matchupSpecs = opponents.map { it to v3 }
        val sourceRun = RunProvenance.capture(root)
        sourceRun.requireReady()
        val sourceProvenance = requireNotNull(sourceRun.sourceProvenance)
        val arena = SearchTeacherArena(registry, deckManifest, SearchTeacherArena.smokeProfile(), baseSeed)
        val policyEvidenceIdentities = policies.associate { policy ->
            policy.id to arena.evidenceBinding(
                policy = policy,
                maxSearchDecisions = null,
                sourceProvenance = sourceProvenance,
            ).identity
        }.toSortedMap()
        val runIdentity = "tournament-evidence-v1-sha256:" + sha256(
            listOf(
                TOURNAMENT_V3_CALIBRATED_VERSION,
                evidenceJson.encodeToString(policyEvidenceIdentities),
                deckManifest.deckHash(), deckManifest.cardPoolHash(),
                baseSeed.toString(),
                pairsPerMatchup.toString(),
                workerThreads.toString(),
            ).joinToString(":"),
        )
        val runDirectory = evidence.diagnostic(
            "tournament/$runIdentity",
            "the calibrated-tournament checkpoints",
        )
        val existingReport = runDirectory.resolve("report.json")
        if (Files.isRegularFile(existingReport)) {
            val existing = evidenceJson.decodeFromString<TournamentV3CalibratedReport>(
                Files.readString(existingReport)
            )
            require(existing.runIdentity == runIdentity && existing.valid) {
                "Existing calibrated tournament record is incomplete"
            }
            return existing
        }
        val tracker = TournamentProgressTracker(
            progressPath = runDirectory.resolve("progress.json"),
            tournamentVersion = TOURNAMENT_V3_CALIBRATED_VERSION,
            runIdentity = runIdentity,
            outerCommit = sourceRun.outerCommit,
            argentumCommit = sourceRun.checkedOutArgentumCommit,
            baseSeed = baseSeed,
            pairsPerMatchup = pairsPerMatchup,
            workerThreads = workerThreads,
            matchupCount = matchupSpecs.size,
            recordKind = "CALIBRATED",
            policyIds = policies.map(ArenaPolicySpec::id),
        )
        println("V3 calibrated tournament progress JSON: ${runDirectory.resolve("progress.json")}")
        val runtimeWarmupWorkers = minOf(workerThreads, 4)
        data class CompletedPair(
            val firstPolicyId: String,
            val secondPolicyId: String,
            val pairIndex: Int,
            val games: List<GameRunResult>,
        )
        return try {
            warmRuntime(arena, v3, opponents.single { it.kind == ArenaPolicyKind.HEURISTIC }, runtimeWarmupWorkers)
            val jobs = interleavedTournamentPairJobs(matchupSpecs.size, pairsPerMatchup)
            val completed = parallelMapOrdered(jobs.size, workerThreads) { jobIndex ->
                val job = jobs[jobIndex]
                val (first, second) = matchupSpecs[job.matchupIndex]
                val pairIndex = job.pairIndex
                val checkpoint = runDirectory.resolve("${first.id}--${second.id}/pair-$pairIndex.json")
                val seed = ComponentSeeds.derive(baseSeed, pairIndex, "v3-calibrated-library-orders")
                val games = (loadCheckpoint(checkpoint, runIdentity, first.id, second.id, pairIndex) ?: emptyList())
                    .toMutableList()
                games.forEachIndexed { leg, game ->
                    tracker.recordCheckpointReuse(tournamentDescriptor(first, second, pairIndex, leg), game)
                }
                fun persist() = writeJsonAtomically(
                    checkpoint,
                    TournamentPairCheckpoint(
                        runIdentity = runIdentity,
                        firstPolicyId = first.id,
                        secondPolicyId = second.id,
                        pairIndex = pairIndex,
                        games = games,
                    ),
                )
                while (games.size < 2) {
                    val leg = games.size
                    val descriptor = tournamentDescriptor(first, second, pairIndex, leg)
                    tracker.prepareGame(descriptor)
                    games += try {
                        arena.playWithPolicies(
                            descriptor.gameId,
                            seed,
                            if (leg == 0) first else second,
                            if (leg == 0) second else first,
                            replay = replayOptions(runIdentity, descriptor),
                            progressObserver = tracker,
                        )
                    } catch (error: Throwable) {
                        tracker.abandonGame(descriptor.gameId, error)
                        throw error
                    }
                    persist()
                }
                CompletedPair(first.id, second.id, pairIndex, games)
            }
            val grouped = completed.groupBy { it.firstPolicyId to it.secondPolicyId }
            val matchupReports = matchupSpecs.map { (first, second) ->
                val matchupPairs = requireNotNull(grouped[first.id to second.id])
                    .sortedBy(CompletedPair::pairIndex)
                summarizeMatchup(
                    first.id,
                    second.id,
                    pairsPerMatchup,
                    matchupPairs.flatMap(CompletedPair::games),
                    matchupPairs.map(CompletedPair::pairIndex),
                )
            }
            val games = matchupReports.flatMap(TournamentMatchupReport::games)
            val matchups = matchupReports.map { matchup ->
                TournamentMatchupSummary(
                    matchup.firstPolicyId,
                    matchup.secondPolicyId,
                    matchup.pairCount,
                    matchup.winsFirst,
                    matchup.draws,
                    matchup.winsSecond,
                    matchup.firstPointRate,
                    matchup.games.map(GameRunResult::compact),
                    matchup.pairIndices,
                )
            }
            val operational = TournamentOperationalAccumulator(policies.map(ArenaPolicySpec::id))
            games.forEach(operational::record)
            val rating = fitCompactTournament(policies.map(ArenaPolicySpec::id), matchups, baseSeed)
            val provenance = buildCurrentProvenance(matchups, runIdentity)
            val completePairs = matchupReports.sumOf { matchup ->
                matchup.games.chunked(2).count { pair -> pair.size == 2 && pair.all(::operationallyValidGame) }
            }
            val failures = buildList {
                if (matchups.size != TOURNAMENT_V3_CALIBRATED_MATCHUPS) {
                    add("expected $TOURNAMENT_V3_CALIBRATED_MATCHUPS matchups, found ${matchups.size}")
                }
                if (games.size != TOURNAMENT_V3_CALIBRATED_GAMES) {
                    add("expected $TOURNAMENT_V3_CALIBRATED_GAMES games, found ${games.size}")
                }
                if (games.map(GameRunResult::gameId).distinct().size != games.size) add("duplicate game ids")
                if (provenance.size != games.size || provenance.map { it.gameId }.toSet() !=
                    games.map(GameRunResult::gameId).toSet()
                ) add("game provenance is incomplete")
                if (matchupReports.map(TournamentMatchupReport::firstPolicyId) !=
                    TOURNAMENT_V3_CALIBRATED_OPPONENT_IDS || matchupReports.any {
                        it.pairCount != pairsPerMatchup || it.games.size != pairsPerMatchup * 2
                    }
                ) add("calibrated matchup schedule is incomplete")
                if (completePairs != TOURNAMENT_V3_CALIBRATED_PAIRS) {
                    add("expected $TOURNAMENT_V3_CALIBRATED_PAIRS valid pairs, found $completePairs")
                }
                games.filterNot(::operationallyValidGame).forEach { game ->
                    add("invalid game ${game.gameId}: ${game.exception ?: "operational invariant"}")
                }
            }
            val report = TournamentV3CalibratedReport(
                runIdentity = runIdentity,
                generatedAtUtc = Instant.now().toString(),
                outerCommit = sourceRun.outerCommit,
                argentumCommit = sourceRun.checkedOutArgentumCommit,
                sourceProvenance = sourceProvenance,
                policyEvidenceIdentities = policyEvidenceIdentities,
                deckHash = deckManifest.deckHash(),
                baseSeed = baseSeed,
                pairsPerMatchup = pairsPerMatchup,
                workerThreads = workerThreads,
                runtimeWarmupWorkers = runtimeWarmupWorkers,
                policies = descriptions,
                opponentPolicyIds = opponents.map(ArenaPolicySpec::id),
                matchups = matchups,
                standings = rating.standings,
                startingPlayerRating = rating.startingPlayerRating,
                v3FieldPointRate = rateEstimate(TOURNAMENT_V3_POLICY_ID, null, matchups, baseSeed),
                v3HeadToHead = matchups.map { matchup ->
                    rateEstimate(
                        TOURNAMENT_V3_POLICY_ID,
                        matchup.firstPolicyId,
                        listOf(matchup),
                        baseSeed,
                    )
                },
                operationalByPolicy = operational.summaries(),
                gameProvenance = provenance,
                completePairs = completePairs,
                gameCount = games.size,
                valid = failures.isEmpty(),
                failureReasons = failures,
            )
            tracker.finish(if (report.valid) TournamentRunProgressState.COMPLETED else TournamentRunProgressState.FAILED)
            report
        } catch (error: Throwable) {
            tracker.finish(TournamentRunProgressState.FAILED)
            throw error
        }
    }

    private fun warmRuntime(
        arena: SearchTeacherArena,
        v3: ArenaPolicySpec,
        heuristic: ArenaPolicySpec,
        workers: Int,
    ) {
        parallelMapOrdered(workers, workers) { index ->
            arena.playWithPolicies(
                "v3-calibrated-runtime-warmup-$index",
                ComponentSeeds.derive(baseSeed, index, "v3-calibrated-runtime-warmup"),
                v3,
                heuristic,
                maxSearchDecisions = 1,
            )
        }.forEach { result ->
            require(result.exception == null && result.informationLedgerComplete) {
                "V3 calibrated runtime warmup failed: ${result.exception}"
            }
        }
    }

    private fun replayOptions(runIdentity: String, descriptor: TournamentGameDescriptor): GameReplayOptions {
        val path = evidence.diagnostic(
            "tournament/$runIdentity/replays/${descriptor.gameId}.privileged.replay.jsonl.gz",
            "the calibrated-tournament replay checkpoint",
        )
        return GameReplayOptions(path, root.relativize(path).toString(), runIdentity)
    }

    private fun loadCheckpoint(
        path: Path,
        identity: String,
        first: String,
        second: String,
        pairIndex: Int,
    ): List<GameRunResult>? = runCatching {
        evidenceJson.decodeFromString<TournamentPairCheckpoint>(Files.readString(path)).takeIf { checkpoint ->
            checkpoint.schemaVersion == 3 && checkpoint.runIdentity == identity &&
                checkpoint.firstPolicyId == first && checkpoint.secondPolicyId == second &&
                checkpoint.pairIndex == pairIndex && checkpoint.games.size in 1..2 &&
                checkpoint.games.all(::replayArtifactIsIntact)
        }?.games
    }.getOrNull()

    private fun replayArtifactIsIntact(game: GameRunResult): Boolean {
        if (!game.replayVerified) return false
        val reference = game.replayPath ?: return false
        val expected = game.replaySha256 ?: return false
        val raw = Path.of(reference)
        val path = if (raw.isAbsolute) raw else root.resolve(raw)
        return Files.isRegularFile(path) && sha256File(path) == expected &&
            TournamentReplayVerifier(registry, deckManifest).verify(path).verified
    }
}

internal fun writeTournamentV3CalibratedArtifacts(
    root: Path,
    report: TournamentV3CalibratedReport,
): Path {
    val directory = EvidenceStore(root).diagnostic(
        "tournament/${report.runIdentity}",
        "the calibrated-tournament artifacts",
    )
    val reportPath = directory.resolve("report.json")
    val markdownPath = directory.resolve("report.md")
    val analyticsPath = directory.resolve("analytics.json")
    writeJsonAtomically(reportPath, report)
    writeTextAtomically(markdownPath, renderTournamentV3Calibrated(report))
    exportTournamentAnalytics(root, directory, analyticsPath)
    return reportPath
}

private fun exportTournamentAnalytics(root: Path, runDirectory: Path, outputPath: Path) {
    val process = ProcessBuilder(
        "node",
        root.resolve("tools/tournament-monitor/export-analytics.mjs").toString(),
        runDirectory.toString(),
        outputPath.toString(),
        "10000",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    require(process.waitFor() == 0) { "Tournament analytics export failed: $output" }
    require(Files.isRegularFile(outputPath)) { "Tournament analytics exporter did not write $outputPath" }
}

internal fun renderTournamentV3Calibrated(report: TournamentV3CalibratedReport): String = buildString {
    appendLine("# Recorded results against three declared policies without pooling earlier games")
    appendLine()
    appendLine("## What was observed")
    appendLine()
    appendLine(
        "The run recorded ${report.gameCount} games in ${report.completePairs} complete seat-swapped pairs. " +
            "The candidate earned ${"%.1f".format(report.v3FieldPointRate.pointRate * 100)}% of available points " +
            "against the three declared policies, with a shared-pair-index-block-resampled nominal 95% interval of " +
            "${"%.1f".format(report.v3FieldPointRate.confidenceLower * 100)}–" +
            "${"%.1f".format(report.v3FieldPointRate.confidenceUpper * 100)}%."
    )
    appendLine()
    appendLine(
        "For example, every required matchup, game, pair, replay, and provenance record must be present before " +
            "the tournament procedure accepts the run. The recorded conditions " +
            "${if (report.valid) "were all satisfied" else "were not all satisfied; ${report.failureReasons.size} condition(s) prevented use"}."
    )
    appendLine()
    appendLine(
        "No earlier tournament outcomes are pooled into these numbers. The result applies only to the declared " +
            "deck, policies, source revision, 8-position/64-simulation/depth-32 settings, and analysis. It does " +
            "not establish improvement caused by one component, performance against people or other decks, or " +
            "permission to train from the games."
    )
    appendLine()
    appendLine("## Experiment trace")
    appendLine()
    appendLine("- Run: `${report.runIdentity}`")
    appendLine("- Opponent policy identifiers: ${report.opponentPolicyIds.joinToString()}")
    appendLine("- Candidate field point rate: ${"%.1f".format(report.v3FieldPointRate.pointRate * 100)}% " +
        "(pair-level 95% ${"%.1f".format(report.v3FieldPointRate.confidenceLower * 100)}–" +
        "${"%.1f".format(report.v3FieldPointRate.confidenceUpper * 100)}%)")
    appendLine("- Earlier tournament outcomes pooled: no")
    appendLine()
    appendLine("## Standings calculated from the recorded games")
    appendLine()
    appendLine("| Rank | Policy | Rating | 95% interval | Points | W-D-L |")
    appendLine("| ---: | --- | ---: | ---: | ---: | ---: |")
    report.standings.forEach { standing ->
        appendLine(
            "| ${standing.rank} | ${standing.policyId} | ${"%.1f".format(standing.rating)} | " +
                "${"%.1f".format(standing.confidenceLower)} to ${"%.1f".format(standing.confidenceUpper)} | " +
                "${"%.1f".format(standing.gamePointRate * 100)}% | " +
                "${standing.wins}-${standing.draws}-${standing.losses} |"
        )
    }
    appendLine()
    appendLine("## Recorded scope fields")
    appendLine()
    appendLine(report.inferenceScope)
    appendLine(report.fieldRateScope)
    appendLine(report.continuityStatement)
    appendLine(report.reuseAsymmetry)
    if (report.failureReasons.isNotEmpty()) {
        appendLine()
        appendLine("## Recorded conditions that prevented use")
        report.failureReasons.forEach { appendLine("- $it") }
    }
}

internal fun tournamentDescriptor(
    first: ArenaPolicySpec,
    second: ArenaPolicySpec,
    pairIndex: Int,
    legIndex: Int,
): TournamentGameDescriptor {
    require(legIndex in 0..1)
    val leg = if (legIndex == 0) "a" else "b"
    return TournamentGameDescriptor(
        gameId = "tournament-${first.id}-${second.id}-$pairIndex-$leg",
        firstPolicyId = first.id,
        secondPolicyId = second.id,
        pairIndex = pairIndex,
        leg = leg,
        p0PolicyId = if (legIndex == 0) first.id else second.id,
        p1PolicyId = if (legIndex == 0) second.id else first.id,
    )
}

internal fun operationallyValidTournamentSummary(game: TournamentGameSummary): Boolean =
    game.disposition == GameRunDisposition.GAME_ENDED && game.evidenceStop == null &&
    game.terminal && !game.stepLimit && game.exception == null && game.illegalResponses == 0 &&
        game.fallbacks == 0 && game.informationLedgerComplete && game.replayVerified &&
        game.replayPath != null && game.replaySha256 != null

internal fun parseGameSuffix(gameId: String): Pair<Int, String> {
    val match = requireNotNull(Regex("-(\\d+)-([ab])$").find(gameId)) {
        "Invalid tournament game id: $gameId"
    }
    return match.groupValues[1].toInt() to match.groupValues[2]
}

internal fun fitCompactTournament(
    policyIds: List<String>,
    matchups: List<TournamentMatchupSummary>,
    seed: Long,
): RatingResult = BradleyTerry.fit(
    policyIds,
    matchups.map { matchup ->
        TournamentMatchupReport(
            matchup.firstPolicyId,
            matchup.secondPolicyId,
            matchup.pairCount,
            matchup.winsFirst,
            matchup.draws,
            matchup.winsSecond,
            matchup.firstPointRate,
            matchup.games.map { game ->
                GameRunResult(
                    gameId = game.gameId,
                    seed = game.seed,
                    p0Policy = ArenaPolicyKind.SEARCH,
                    p1Policy = ArenaPolicyKind.SEARCH,
                    winner = game.winner,
                    terminal = game.terminal,
                    disposition = game.disposition,
                    evidenceStop = game.evidenceStop,
                    decisions = game.decisions,
                    searchSeat = game.searchSeat,
                    searchScore = game.searchScore,
                    illegalResponses = game.illegalResponses,
                    fallbacks = game.fallbacks,
                    stepLimit = game.stepLimit,
                    exception = game.exception,
                    informationLedgerComplete = game.informationLedgerComplete,
                    p0PolicyId = game.p0PolicyId,
                    p1PolicyId = game.p1PolicyId,
                    replayPath = game.replayPath,
                    replaySha256 = game.replaySha256,
                    replayVerified = game.replayVerified,
                )
            },
            matchup.pairIndices,
        )
    },
    seed,
)

internal fun rateEstimate(
    policyId: String,
    opponentPolicyId: String?,
    matchups: List<TournamentMatchupSummary>,
    seed: Long,
): TournamentRateEstimate {
    require(matchups.flatMap { it.games }.all {
        it.disposition == GameRunDisposition.GAME_ENDED && it.terminal && it.evidenceStop == null
    }) {
        "Rate estimates require engine-ended games; a null winner is otherwise not a draw"
    }
    val pairScores = matchups.flatMap { matchup ->
        val pairs = matchup.games.chunked(2)
        require(matchup.pairIndices.size == pairs.size) {
            "Matchup ${matchup.firstPolicyId}/${matchup.secondPolicyId} has " +
                "${matchup.pairIndices.size} pair indices for ${pairs.size} pairs"
        }
        matchup.pairIndices.zip(pairs).map { (pairIndex, games) ->
            require(games.size == 2) { "Every rate-estimate pair must contain both seat-swapped games" }
            require(games.all { policyId == it.p0PolicyId || policyId == it.p1PolicyId }) {
                "Policy $policyId is absent from a requested rate-estimate pair"
            }
            val suffixes = games.map { parseGameSuffix(it.gameId) }
            require(suffixes.map { it.first }.toSet() == setOf(pairIndex)) {
                "Both seat-swapped games must use the same pair-index seed block"
            }
            require(suffixes.map(Pair<Int, String>::second).toSet() == setOf("a", "b")) {
                "A rate-estimate pair must contain legs a and b"
            }
            TournamentPairIndexScore(
                pairIndex = pairIndex,
                value = games.sumOf { policyScore(it, policyId) } / games.size,
            )
        }
    }
    val points = pairScores.sumOf(TournamentPairIndexScore::value)
    val interval = pairIndexBootstrapInterval(
        pairScores,
        ComponentSeeds.derive(seed, policyId, opponentPolicyId, "v3-rate-shared-pair-index-blocks"),
    )
    return TournamentRateEstimate(
        policyId,
        opponentPolicyId,
        pairScores.size,
        pairScores.size * 2,
        points,
        points / pairScores.size,
        interval.first,
        interval.second,
    )
}

private fun policyScore(game: TournamentGameSummary, policyId: String): Double {
    val p0 = when (game.winner) {
        "p0" -> 1.0
        "p1" -> 0.0
        else -> 0.5
    }
    return if (game.p0PolicyId == policyId) p0 else 1.0 - p0
}

internal data class TournamentPairIndexScore(val pairIndex: Int, val value: Double) {
    init {
        require(pairIndex >= 0)
        require(value.isFinite() && value in 0.0..1.0)
    }
}

/** Percentile interval for bounded fractional pair scores using the shared seed block as the unit. */
internal fun pairIndexBootstrapInterval(
    scores: List<TournamentPairIndexScore>,
    seed: Long,
    samples: Int = 10_000,
): Pair<Double, Double> {
    require(scores.isNotEmpty())
    require(samples > 0)
    val blocks = scores.groupBy(TournamentPairIndexScore::pairIndex)
        .toSortedMap()
        .values
        .toList()
    val random = Random(seed)
    val means = List(samples) {
        var sum = 0.0
        var count = 0
        repeat(blocks.size) {
            val block = blocks[random.nextInt(blocks.size)]
            sum += block.sumOf(TournamentPairIndexScore::value)
            count += block.size
        }
        sum / count
    }
    return percentile(means, 0.025).coerceIn(0.0, 1.0) to
        percentile(means, 0.975).coerceIn(0.0, 1.0)
}

private fun buildCurrentProvenance(
    matchups: List<TournamentMatchupSummary>,
    runIdentity: String,
): List<TournamentGameProvenance> = matchups.flatMap { matchup ->
    matchup.games.mapNotNull { game ->
        val replayPath = game.replayPath ?: return@mapNotNull null
        val replaySha256 = game.replaySha256 ?: return@mapNotNull null
        val (pairIndex, leg) = parseGameSuffix(game.gameId)
        TournamentGameProvenance(
            game.gameId,
            matchup.firstPolicyId,
            matchup.secondPolicyId,
            pairIndex,
            leg,
            "CURRENT_CALIBRATED_RUN",
            runIdentity,
            replayPath,
            replaySha256,
        )
    }
}

internal fun artifactInventorySha256(artifacts: List<TournamentArtifactDigest>): String = sha256(
    artifacts.joinToString("|") { "${it.path}:${it.bytes}:${it.sha256}" }
)

private data class MutableOperational(
    var games: Int = 0,
    var validGames: Int = 0,
    var replayVerifiedGames: Int = 0,
    var searchDecisions: Int = 0,
    var simulations: Long = 0,
    var freshSimulations: Long = 0,
    var reusedSimulations: Long = 0,
    var searchWorldSteps: Long = 0,
    val searchLatencies: MutableList<Double> = mutableListOf(),
    var elapsedMillis: Double = 0.0,
)

internal class TournamentOperationalAccumulator(policyIds: List<String>) {
    private val values = policyIds.associateWith { MutableOperational() }.toMutableMap()

    fun record(game: GameRunResult) {
        listOf("p0" to game.p0PolicyId, "p1" to game.p1PolicyId).forEach { (seat, policyId) ->
            val value = requireNotNull(values[policyId]) { "Unknown tournament policy $policyId" }
            value.games++
            if (operationallyValidGame(game)) value.validGames++
            if (game.replayVerified) value.replayVerifiedGames++
            value.elapsedMillis += game.elapsedMillis ?: 0.0
            val diagnostics = game.seatDiagnostics[seat]
            if (diagnostics != null) {
                value.searchDecisions += diagnostics.searchDecisions
                value.searchLatencies += diagnostics.searchLatenciesMillis
                diagnostics.searchDecisionsDetail.forEach { decision ->
                    value.simulations += decision.searchDiagnostics.simulations
                    value.freshSimulations += decision.searchDiagnostics.freshSimulations
                    value.reusedSimulations += decision.searchDiagnostics.reusedSimulations
                    value.searchWorldSteps += decision.searchDiagnostics.searchWorldSteps
                }
            }
        }
    }

    fun summaries(): List<TournamentPolicyOperationalSummary> = values.map { (policyId, value) ->
        TournamentPolicyOperationalSummary(
            policyId,
            value.games,
            value.validGames,
            value.replayVerifiedGames,
            value.searchDecisions,
            value.simulations,
            value.freshSimulations,
            value.reusedSimulations,
            value.searchWorldSteps,
            value.searchLatencies.takeIf { it.isNotEmpty() }?.average(),
            value.searchLatencies.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
            value.elapsedMillis,
        )
    }
}
