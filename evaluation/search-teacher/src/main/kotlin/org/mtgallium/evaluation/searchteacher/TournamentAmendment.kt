package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

private const val TOURNAMENT_AMENDMENT_VERSION = "tournament-fallback-amendment-v1"

@Serializable
internal data class TournamentArtifactDigest(
    val path: String,
    val sha256: String,
    val bytes: Long,
)

@Serializable
internal data class TournamentSourceInventory(
    val sourceRunIdentity: String,
    val sourceReport: TournamentArtifactDigest,
    val runArtifacts: List<TournamentArtifactDigest>,
    val aggregateSha256: String,
)

internal data class TournamentGameReplacement(
    val gameId: String,
    val sourceReplayPath: String,
    val sourceReplaySha256: String,
    val replacementReplayPath: String,
    val replacementReplaySha256: String,
    val sourceFallbacks: Int,
    val resolutionCounts: Map<String, Int>,
    val replacement: GameRunResult,
)

@Serializable
internal data class TournamentGameReplacementSummary(
    val gameId: String,
    val sourceReplayPath: String,
    val sourceReplaySha256: String,
    val replacementReplayPath: String,
    val replacementReplaySha256: String,
    val sourceFallbacks: Int,
    val resolutionCounts: Map<String, Int>,
    val replacement: TournamentGameSummary,
)

@Serializable
internal data class TournamentAmendmentReport(
    val schemaVersion: Int = 2,
    val version: String = TOURNAMENT_AMENDMENT_VERSION,
    val amendmentIdentity: String,
    val generatedAtUtc: String,
    val sourceRunIdentity: String,
    val sourceTournamentVersion: String,
    val sourceOuterCommit: String,
    val sourceArgentumCommit: String,
    val repairOuterCommit: String,
    val repairArgentumCommit: String,
    val repairImplementationSha256: String,
    val sourceInventory: TournamentSourceInventory,
    val selectedGameCount: Int,
    val replacements: List<TournamentGameReplacementSummary>,
    val amendedTournament: CoreSixTournamentCompactReport? = null,
    val sourceArtifactsUnchanged: Boolean,
    val passed: Boolean,
    val failureReasons: List<String>,
)

@Serializable
internal data class TournamentAmendmentProgress(
    val schemaVersion: Int = 1,
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
    val queuedGames: Int = 0,
    val activeGames: Int = 0,
    val finishedGames: Int,
    val checkpointReusedGames: Int,
    val restartedFromBeginningGames: Int,
    val failedGames: Int = 0,
    val finishedPairs: Int,
    val elapsedMillis: Long,
    val games: List<TournamentActiveGameProgress> = emptyList(),
    val lastFailure: String? = null,
    val recordKind: String = "AMENDED",
    val sourceRunIdentity: String,
    val replacementCount: Int,
)

internal class TournamentAmendmentRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
) {
    private val evidence = EvidenceStore(root)

    fun run(sourceRunIdentity: String, workerThreads: Int): Pair<TournamentAmendmentReport, Path> {
        require(sourceRunIdentity.matches(Regex("[a-f0-9]{64}"))) { "Invalid --source-run identity" }
        val startedNanos = System.nanoTime()
        val sourceDirectory = evidence.work("tournament/$sourceRunIdentity")
        require(Files.isDirectory(sourceDirectory)) { "Missing source tournament $sourceDirectory" }
        val sourceProgressPath = sourceDirectory.resolve("progress.json")
        val sourceProgress = evidenceJson.decodeFromString<TournamentProgressReport>(
            Files.readString(sourceProgressPath)
        )
        require(sourceProgress.runIdentity == sourceRunIdentity)
        require(sourceProgress.finishedGames == sourceProgress.totalGames && sourceProgress.activeGames == 0) {
            "Source tournament has not finished"
        }
        require(workerThreads == sourceProgress.workerThreads) {
            "Amendment must use the source run's ${sourceProgress.workerThreads} workers"
        }

        val rawSourceReportPath = sourceDirectory.resolve("report.json")
        val legacySourceReportPath = evidence.latest("tournament/${sourceProgress.tournamentVersion}.json")
        val sourceReportPath = rawSourceReportPath.takeIf(Files::isRegularFile) ?: legacySourceReportPath
        require(Files.isRegularFile(sourceReportPath)) { "Missing source report $sourceReportPath" }
        val sourceReport = evidenceJson.decodeFromString<CoreSixTournamentReport>(Files.readString(sourceReportPath))
        require(sourceReport.tournamentVersion == sourceProgress.tournamentVersion)
        require(sourceReport.outerCommit == sourceProgress.outerCommit)
        require(sourceReport.argentumCommit == sourceProgress.argentumCommit)
        require(sourceReport.baseSeed == sourceProgress.baseSeed)
        require(sourceReport.pairsPerMatchup == sourceProgress.pairsPerMatchup)
        require(sourceReport.workerThreads == sourceProgress.workerThreads)

        val sourceCheckpoints = loadCheckpoints(sourceDirectory, sourceRunIdentity)
        require(sourceCheckpoints.size == sourceProgress.totalPairs) {
            "Expected ${sourceProgress.totalPairs} source checkpoints, found ${sourceCheckpoints.size}"
        }
        val sourceGames = sourceCheckpoints.flatMap { it.second.games }
        require(sourceGames.size == sourceProgress.totalGames)
        require(sourceGames.map(GameRunResult::gameId).distinct().size == sourceGames.size)
        val reportGames = sourceReport.matchups.flatMap(TournamentMatchupReport::games)
        require(reportGames.associate { it.gameId to it.replaySha256 } ==
            sourceGames.associate { it.gameId to it.replaySha256 }) {
            "Source report and checkpoint game inventories differ"
        }

        val invalidGames = sourceGames.filterNot(::operationallyValidGame)
        require(invalidGames.size == sourceProgress.failedGames) {
            "Progress reports ${sourceProgress.failedGames} failures, checkpoints contain ${invalidGames.size}"
        }
        val ineligible = invalidGames.associateWith(::nonFallbackFailureReasons)
            .filterValues { it.isNotEmpty() }
        require(ineligible.isEmpty()) {
            "Automatic amendment is limited to fallback-only games: " +
                ineligible.entries.joinToString { (game, reasons) -> "${game.gameId}=${reasons.joinToString(",")}" }
        }
        require(invalidGames.isNotEmpty()) { "Source tournament has no fallback-only failed games" }

        val inventoryBefore = inventory(sourceDirectory, sourceReportPath, sourceRunIdentity)
        val repairImplementationSha256 = implementationSha256()
        val amendmentIdentity = sha256(
            listOf(
                TOURNAMENT_AMENDMENT_VERSION,
                sourceRunIdentity,
                inventoryBefore.aggregateSha256,
                currentOuterCommit(),
                currentArgentumCommit(),
                repairImplementationSha256,
                invalidGames.sortedBy(GameRunResult::gameId)
                    .joinToString("|") { "${it.gameId}:${it.replaySha256}" },
            ).joinToString(":"),
        )
        val outputDirectory = evidence.diagnostic(
            "tournament-amendment/$amendmentIdentity",
            "the tournament amendment checkpoints",
        )
        val existingReportPath = outputDirectory.resolve("report.json")
        if (Files.isRegularFile(existingReportPath)) {
            val existing = evidenceJson.decodeFromString<TournamentAmendmentReport>(Files.readString(existingReportPath))
            require(existing.amendmentIdentity == amendmentIdentity && existing.passed) {
                "Existing amendment identity is incomplete or failed: $existingReportPath"
            }
            return existing to existingReportPath
        }
        Files.createDirectories(outputDirectory.resolve("replays"))

        val replacements = parallelMapOrdered(invalidGames.size, workerThreads) { index ->
            rerunGame(
                original = invalidGames.sortedBy(GameRunResult::gameId)[index],
                sourceRunIdentity = sourceRunIdentity,
                amendmentIdentity = amendmentIdentity,
                outputDirectory = outputDirectory,
            )
        }
        val replacementFailures = replacements.flatMap(::replacementFailureReasons)
        val inventoryAfter = inventory(sourceDirectory, sourceReportPath, sourceRunIdentity)
        val sourceArtifactsUnchanged = inventoryAfter == inventoryBefore
        val failures = buildList {
            addAll(replacementFailures)
            if (!sourceArtifactsUnchanged) add("Source tournament artifacts changed during amendment")
        }

        val amendedTournament = if (failures.isEmpty()) {
            buildAmendedTournament(sourceReport, sourceCheckpoints, replacements)
        } else {
            null
        }
        val replacementSummaries = replacements.map(TournamentGameReplacement::summary)
        val report = TournamentAmendmentReport(
            amendmentIdentity = amendmentIdentity,
            generatedAtUtc = Instant.now().toString(),
            sourceRunIdentity = sourceRunIdentity,
            sourceTournamentVersion = sourceProgress.tournamentVersion,
            sourceOuterCommit = sourceProgress.outerCommit,
            sourceArgentumCommit = sourceProgress.argentumCommit,
            repairOuterCommit = currentOuterCommit(),
            repairArgentumCommit = currentArgentumCommit(),
            repairImplementationSha256 = repairImplementationSha256,
            sourceInventory = inventoryBefore,
            selectedGameCount = invalidGames.size,
            replacements = replacementSummaries,
            amendedTournament = amendedTournament?.compact(),
            sourceArtifactsUnchanged = sourceArtifactsUnchanged,
            passed = failures.isEmpty() && amendedTournament?.valid == true,
            failureReasons = failures + amendedTournament?.failureReasons.orEmpty(),
        )
        writeJsonAtomically(existingReportPath, report)
        if (!report.passed) return report to existingReportPath

        writeOverlayCheckpoints(outputDirectory, sourceCheckpoints, replacements)
        val markdownPath = outputDirectory.resolve("report.md")
        writeTextAtomically(markdownPath, renderTournamentAmendment(report))
        writeJsonAtomically(
            outputDirectory.resolve("progress.json"),
            TournamentAmendmentProgress(
                tournamentVersion = "${sourceProgress.tournamentVersion}-amended",
                runIdentity = amendmentIdentity,
                generatedAtUtc = report.generatedAtUtc,
                state = TournamentRunProgressState.COMPLETED,
                outerCommit = currentOuterCommit(),
                argentumCommit = currentArgentumCommit(),
                baseSeed = sourceProgress.baseSeed,
                pairsPerMatchup = sourceProgress.pairsPerMatchup,
                workerThreads = workerThreads,
                totalPairs = sourceProgress.totalPairs,
                totalGames = sourceProgress.totalGames,
                finishedGames = sourceProgress.totalGames,
                checkpointReusedGames = sourceProgress.totalGames - replacements.size,
                restartedFromBeginningGames = replacements.size,
                finishedPairs = sourceProgress.totalPairs,
                elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000,
                sourceRunIdentity = sourceRunIdentity,
                replacementCount = replacements.size,
            ),
        )
        return report to existingReportPath
    }

    private fun rerunGame(
        original: GameRunResult,
        sourceRunIdentity: String,
        amendmentIdentity: String,
        outputDirectory: Path,
    ): TournamentGameReplacement {
        val sourceReplay = resolveReplay(original)
        require(sha256File(sourceReplay) == original.replaySha256) {
            "Source replay hash mismatch for ${original.gameId}"
        }
        val header = requireNotNull(readPrivilegedReplay(sourceReplay).first().header)
        require(header.gameId == original.gameId)
        require(header.runIdentity == sourceRunIdentity)
        require(header.gameSeed == original.seed)
        require(header.deckHash == manifest.deckHash())
        val p0 = header.p0Policy.toArenaPolicySpec()
        val p1 = header.p1Policy.toArenaPolicySpec()
        require(p0.id == original.p0PolicyId && p1.id == original.p1PolicyId)
        val replayPath = outputDirectory.resolve("replays/${original.gameId}.privileged.replay.jsonl.gz")
        println("Tournament amendment started: ${original.gameId} (${p0.id} vs ${p1.id})")
        val replacement = SearchTeacherArena(
            registry,
            manifest,
            p0.profile ?: p1.profile ?: SearchTeacherArena.smokeProfile(),
            header.baseSeed,
        ).playWithPolicies(
            gameId = header.gameId,
            gameSeed = header.gameSeed,
            p0Policy = p0,
            p1Policy = p1,
            replay = GameReplayOptions(
                finalPath = replayPath,
                referencePath = root.relativize(replayPath).toString(),
                runIdentity = amendmentIdentity,
                outerCommit = currentOuterCommit(),
                argentumCommit = currentArgentumCommit(),
            ),
            progressObserver = AmendmentProgressObserver(),
        )
        println(
            "Tournament amendment finished: ${original.gameId}; decisions=${replacement.decisions}; " +
                "fallbacks=${replacement.fallbacks}; resolutions=${replacement.heuristicResolutionCounts}"
        )
        return TournamentGameReplacement(
            gameId = original.gameId,
            sourceReplayPath = root.relativize(sourceReplay).toString(),
            sourceReplaySha256 = requireNotNull(original.replaySha256),
            replacementReplayPath = requireNotNull(replacement.replayPath),
            replacementReplaySha256 = requireNotNull(replacement.replaySha256),
            sourceFallbacks = original.fallbacks,
            resolutionCounts = replacement.heuristicResolutionCounts,
            replacement = replacement,
        )
    }

    private fun replacementFailureReasons(replacement: TournamentGameReplacement): List<String> = buildList {
        val game = replacement.replacement
        if (!operationallyValidGame(game)) add("${game.gameId}: replacement is not operationally valid")
        if (!game.replayVerified) add("${game.gameId}: replacement replay did not verify")
        val repairActivations = game.heuristicResolutionCounts.getOrDefault("SEMANTIC_EQUIVALENT", 0) +
            game.heuristicResolutionCounts.getOrDefault("VALIDATED_ATTACK_ANCHOR", 0) +
            game.heuristicResolutionCounts.getOrDefault("VALIDATED_BLOCK_ANCHOR", 0)
        if (repairActivations == 0) {
            add("${game.gameId}: replacement did not activate semantic remapping or combat-declaration anchoring")
        }
        if (game.gameId != replacement.gameId) add("${replacement.gameId}: replacement game id changed")
    }

    private fun nonFallbackFailureReasons(game: GameRunResult): List<String> = buildList {
        if (game.fallbacks <= 0) add("NO_FALLBACK")
        if (!game.terminal) add("NON_TERMINAL")
        if (game.stepLimit) add("STEP_LIMIT")
        if (game.exception != null) add("EXCEPTION")
        if (game.illegalResponses != 0) add("ILLEGAL_RESPONSE")
        if (!game.informationLedgerComplete) add("LEDGER_INCOMPLETE")
        if (!game.replayVerified || game.replayPath == null || game.replaySha256 == null) add("REPLAY_INVALID")
        if (game.seatDiagnostics.values.any { seat ->
                seat.searchDecisionsDetail.any { it.searchDiagnostics.rejectedTransitions != 0 }
            }
        ) add("REJECTED_SEARCH_TRANSITION")
    }

    private fun buildAmendedTournament(
        source: CoreSixTournamentReport,
        sourceCheckpoints: List<Pair<Path, TournamentPairCheckpoint>>,
        replacements: List<TournamentGameReplacement>,
    ): CoreSixTournamentReport {
        val replacementByGame = replacements.associate { it.gameId to it.replacement }
        val amendedCheckpoints = sourceCheckpoints.map { (_, checkpoint) ->
            checkpoint.copy(games = checkpoint.games.map { replacementByGame[it.gameId] ?: it })
        }
        val allGames = amendedCheckpoints.flatMap(TournamentPairCheckpoint::games)
        require(allGames.size == source.gameCount && allGames.map(GameRunResult::gameId).distinct().size == allGames.size)
        require(replacementByGame.keys == replacements.map(TournamentGameReplacement::gameId).toSet())
        val matchups = source.matchups.map { sourceMatchup ->
            val matchupPairs = amendedCheckpoints
                .filter { it.firstPolicyId == sourceMatchup.firstPolicyId && it.secondPolicyId == sourceMatchup.secondPolicyId }
                .sortedBy(TournamentPairCheckpoint::pairIndex)
            summarizeMatchup(
                sourceMatchup.firstPolicyId,
                sourceMatchup.secondPolicyId,
                sourceMatchup.pairCount,
                matchupPairs.flatMap(TournamentPairCheckpoint::games),
                matchupPairs.map(TournamentPairCheckpoint::pairIndex),
            )
        }
        val failures = allGames.filterNot(::operationallyValidGame).map {
            "invalid game ${it.gameId}: ${it.exception ?: "operational invariant"}"
        }
        val rating = BradleyTerry.fit(source.policies.map(TournamentPolicyDescription::id), matchups, source.baseSeed)
        val cleanupEvents = allGames.sumOf(GameRunResult::cleanupDiscardEvents)
        val cleanupGames = allGames.count { it.cleanupDiscardEvents > 0 }
        val currentPolicies = CoreSixRoster.policies().map(::describeTournamentPolicy)
        require(currentPolicies.map(TournamentPolicyDescription::id) ==
            source.policies.map(TournamentPolicyDescription::id)) {
            "Current core-six policy roster differs from the source tournament"
        }
        return source.copy(
            runIdentity = sourceCheckpoints.first().second.runIdentity,
            generatedAtUtc = Instant.now().toString(),
            policies = currentPolicies,
            matchups = matchups,
            standings = rating.standings,
            startingPlayerRating = rating.startingPlayerRating,
            completePairs = matchups.sumOf { matchup ->
                matchup.games.chunked(2).count { pair -> pair.size == 2 && pair.all(::operationallyValidGame) }
            },
            gameCount = allGames.size,
            cleanupDiscardEvents = cleanupEvents,
            gamesWithCleanupDiscard = cleanupGames,
            cleanupDiscardGameRate = cleanupGames.toDouble() / allGames.size.coerceAtLeast(1),
            policyQualityWarnings = cleanupPolicyQualityWarnings(allGames),
            valid = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    private fun writeOverlayCheckpoints(
        outputDirectory: Path,
        sourceCheckpoints: List<Pair<Path, TournamentPairCheckpoint>>,
        replacements: List<TournamentGameReplacement>,
    ) {
        val replacementByGame = replacements.associate { it.gameId to it.replacement }
        sourceCheckpoints.forEach { (_, source) ->
            val path = outputDirectory.resolve(
                "${source.firstPolicyId}--${source.secondPolicyId}/pair-${source.pairIndex}.json"
            )
            writeJsonAtomically(
                path,
                source.copy(
                    runIdentity = outputDirectory.fileName.toString(),
                    games = source.games.map { replacementByGame[it.gameId] ?: it },
                ),
            )
        }
    }

    private fun loadCheckpoints(
        sourceDirectory: Path,
        sourceRunIdentity: String,
    ): List<Pair<Path, TournamentPairCheckpoint>> = Files.walk(sourceDirectory).use { paths ->
        paths.filter { path ->
            Files.isRegularFile(path) && path.fileName.toString().matches(Regex("pair-\\d+\\.json"))
        }.map { path ->
            path to evidenceJson.decodeFromString<TournamentPairCheckpoint>(Files.readString(path))
        }.filter { (_, checkpoint) ->
            checkpoint.schemaVersion == 3 && checkpoint.runIdentity == sourceRunIdentity && checkpoint.games.size == 2
        }.sorted(Comparator.comparing { (path, _) -> path.toString() }).toList()
    }

    private fun resolveReplay(game: GameRunResult): Path {
        val reference = requireNotNull(game.replayPath) { "${game.gameId} has no replay" }
        val path = Path.of(reference).let { if (it.isAbsolute) it else root.resolve(it) }
        require(Files.isRegularFile(path)) { "Missing replay for ${game.gameId}: $path" }
        return path
    }

    private fun inventory(
        sourceDirectory: Path,
        sourceReportPath: Path,
        sourceRunIdentity: String,
    ): TournamentSourceInventory {
        val artifacts = Files.walk(sourceDirectory).use { paths ->
            paths.filter(Files::isRegularFile)
                .map(::artifactDigest)
                .sorted(Comparator.comparing(TournamentArtifactDigest::path))
                .toList()
        }
        val report = artifactDigest(sourceReportPath)
        val aggregate = sha256(
            (listOf(report) + artifacts).joinToString("|") { "${it.path}:${it.bytes}:${it.sha256}" }
        )
        return TournamentSourceInventory(sourceRunIdentity, report, artifacts, aggregate)
    }

    private fun artifactDigest(path: Path): TournamentArtifactDigest = TournamentArtifactDigest(
        path = root.relativize(path).toString(),
        sha256 = sha256File(path),
        bytes = Files.size(path),
    )

    private fun implementationSha256(): String {
        val files = listOf(
            "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumSearchWorld.kt",
            "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/UnifiedSemanticExpander.kt",
            "evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/SearchTeacherArena.kt",
            "evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/TournamentAmendment.kt",
        ).map(root::resolve)
        return sha256(files.joinToString("|") { "${root.relativize(it)}:${sha256File(it)}" })
    }

    private fun ReplayPolicyConfiguration.toArenaPolicySpec(): ArenaPolicySpec = ArenaPolicySpec(
        id = id,
        kind = kind,
        profile = profile,
        beliefMode = beliefMode,
        beliefArchitecture = beliefArchitecture,
        searchPlanner = searchPlanner,
        policyCompression = policyCompression,
        searchReuse = searchReuse,
    )
}

private fun TournamentGameReplacement.summary(): TournamentGameReplacementSummary =
    TournamentGameReplacementSummary(
        gameId = gameId,
        sourceReplayPath = sourceReplayPath,
        sourceReplaySha256 = sourceReplaySha256,
        replacementReplayPath = replacementReplayPath,
        replacementReplaySha256 = replacementReplaySha256,
        sourceFallbacks = sourceFallbacks,
        resolutionCounts = resolutionCounts,
        replacement = replacement.compact(),
    )

private class AmendmentProgressObserver : ArenaProgressObserver {
    override fun decisionCompleted(progress: ArenaDecisionProgress, elapsedMillis: Double) {
        if ((progress.decisionIndex + 1) % 25 == 0) {
            println(
                "Tournament amendment progress: ${progress.gameId}; decision=${progress.decisionIndex + 1}; " +
                    "turn=${progress.turnNumber}; ${progress.phase}/${progress.step}; " +
                    "last=${"%.1f".format(elapsedMillis)} ms"
            )
        }
    }
}

internal fun renderTournamentAmendment(report: TournamentAmendmentReport): String = buildString {
    appendLine("# Replayed games that previously used substituted behavior")
    appendLine()
    appendLine("## What changed")
    appendLine()
    appendLine(
        "This procedure replayed ${report.replacements.size}/${report.selectedGameCount} selected games " +
            "with the repair revision while preserving the source-run files. The resulting aggregate still " +
            "combines ${report.amendedTournament?.gameCount?.minus(report.replacements.size) ?: "unknown"} " +
            "source-revision games with ${report.replacements.size} repair-revision games."
    )
    appendLine()
    appendLine(
        "For example, replacing a game that had used a substitute action can show how the repaired path " +
            "behaves without rewriting the original replay. The mixed-revision aggregate remains historical " +
            "diagnosis; it is not a current-policy population and must not supply training labels or current " +
            "playing-strength estimates."
    )
    appendLine()
    val amendmentConditionSummary =
        if (report.passed) {
            "every listed amendment condition was satisfied"
        } else {
            "${report.failureReasons.size} amendment condition(s) prevented use"
        }
    appendLine(
        "The source inventory ${if (report.sourceArtifactsUnchanged) "retained the same recorded bytes" else "changed while the amendment ran"}, " +
            "and $amendmentConditionSummary. This statement is relative " +
            "to the recorded hashes; it does not show that an external copy of the source files is unchanged."
    )
    appendLine()
    appendLine("## Implementation trace")
    appendLine()
    appendLine("- Amendment: `${report.amendmentIdentity}`")
    appendLine("- Source run: `${report.sourceRunIdentity}`")
    report.amendedTournament?.let { tournament ->
        appendLine()
        append(renderCoreSixTournament(tournament))
    }
    if (report.failureReasons.isNotEmpty()) {
        appendLine()
        appendLine("## Recorded conditions that prevented amendment use")
        report.failureReasons.forEach { appendLine("- $it") }
    }
}
