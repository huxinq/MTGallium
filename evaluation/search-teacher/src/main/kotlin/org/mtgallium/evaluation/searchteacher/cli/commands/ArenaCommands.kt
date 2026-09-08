package org.mtgallium.evaluation.searchteacher.cli.commands

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val arenaCommands = listOf(
    SearchTeacherCommand(
        "smoke", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runSmoke,
    ),
    SearchTeacherCommand(
        "inspection", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runInspection,
    ),
    SearchTeacherCommand(
        "arena", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runArena,
    ),
    SearchTeacherCommand(
        "arena-shard", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runArenaShard,
    ),
    SearchTeacherCommand(
        "arena-merge", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runArenaMerge,
    ),
    SearchTeacherCommand(
        "tactical", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runTactical,
    ),
    SearchTeacherCommand(
        "tactical-authoring", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runTacticalAuthoring,
    ),
    SearchTeacherCommand(
        "tactical-horizon-authoring", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runTacticalHorizonAuthoring,
    ),
    SearchTeacherCommand(
        "latency-preflight", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runLatencyPreflight,
    ),
    SearchTeacherCommand(
        "corpus", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runCorpus,
    ),
    SearchTeacherCommand(
        "ablations", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runAblations,
    ),
    SearchTeacherCommand(
        "belief", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runBelief,
    ),
    SearchTeacherCommand(
        "opponent-models", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runOpponentModels,
    ),
    SearchTeacherCommand(
        "population", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runPopulation,
    ),
    SearchTeacherCommand(
        "review", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runReview,
    ),
    SearchTeacherCommand(
        "replay", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runReplay,
    ),
    SearchTeacherCommand(
        "throughput", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runThroughput,
    ),
    SearchTeacherCommand(
        "baseline-hardening", CommandPreparation.LEGACY_ARENA,
        SearchTeacherCommandContext::runBaselineHardening,
    ),
)

private fun SearchTeacherCommandContext.runSmoke() {
    val heuristic = arena.play("smoke-heuristic", options.seed, ArenaPolicyKind.HEURISTIC, ArenaPolicyKind.HEURISTIC)
    val search = arena.play("smoke-search", options.seed + 1, ArenaPolicyKind.SEARCH, ArenaPolicyKind.HEURISTIC)
    val report = SmokeReport(
        generatedAtUtc = Instant.now().toString(),
        deckId = manifest.id,
        deckHash = manifest.deckHash(),
        gridConfigurations = loadSearchGrid().particles.size * loadSearchGrid().simulations.size *
            loadSearchGrid().leafConfigurations.size * loadSearchGrid().actionSpaceProfiles.size,
        heuristicGame = heuristic,
        searchGame = search,
        passed = listOf(heuristic, search).all {
            it.terminal && it.exception == null && it.illegalResponses == 0 && !it.stepLimit
        },
    )
    val path = diagnosticOutput("smoke/report.json")
    writeJsonAtomically(path, report)
    println(
        "Both smoke games reached an engine-reported game end without an exception, illegal response, " +
            "or step-limit stop: ${report.passed}. This checks two executions, not strategic quality. " +
            "Report: $path"
    )
    check(report.passed) { "Search-teacher smoke failed: $report" }
}

private fun SearchTeacherCommandContext.runInspection() {
    val gameId = UUID.randomUUID().toString()
    val directory = diagnosticOutput("inspection/$gameId")
    val publicPath = directory.resolve("${options.perspective}.inspection.json")
    val privilegedPath = directory.resolve(
        "privileged/${options.perspective}.privileged-inspection.json"
    )
    val game = arena.play(
        gameId = gameId,
        gameSeed = options.seed,
        p0Policy = if (options.perspective == "p0") ArenaPolicyKind.SEARCH else ArenaPolicyKind.HEURISTIC,
        p1Policy = if (options.perspective == "p1") ArenaPolicyKind.SEARCH else ArenaPolicyKind.HEURISTIC,
        evidence = GameEvidenceOptions(
            inspection = publicPath,
            privilegedInspection = privilegedPath,
            inspectionPerspective = options.perspective,
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            profileHash = sha256(evidenceJson.encodeToString(profile)),
        ),
    )
    val attemptReportPath = directory.resolve("attempt-report.json")
    writeJsonAtomically(attemptReportPath, game.evidenceRunAttemptSummary())
    check(
        game.disposition == GameRunDisposition.GAME_ENDED &&
            game.terminal && game.evidenceStop == null && game.exception == null &&
            game.informationLedgerComplete
    ) {
        "Inspection game did not complete with a conformant ledger; work-only attempt accounting: " +
            "$attemptReportPath"
    }
    println("Inspection replay: $publicPath")
    println("Privileged unlock: $privilegedPath")
}

private fun SearchTeacherCommandContext.runArena() {
    val report = pairedArena(
        arena = arena,
        profileId = profile.id,
        opponent = options.opponent,
        pairCount = options.pairs,
        baseSeed = options.seed,
        workerThreads = options.threads,
        checkpointRoot = diagnosticOutput("arena"),
    )
    val path = diagnosticOutput("arena/${options.opponent.name.lowercase()}.json")
    writeJsonAtomically(path, report)
    println(
        "Arena ${report.completeGames}/${report.gameCount}, improvement " +
            "${"%.3f".format(report.pointImprovement)}, " +
        "CI [${"%.3f".format(report.confidenceLower)}, ${"%.3f".format(report.confidenceUpper)}]: $path"
    )
}

private fun SearchTeacherCommandContext.runArenaShard() {
    val shard = pairedArenaShard(
        arena = arena,
        profileId = profile.id,
        opponent = options.opponent,
        pairOffset = options.pairOffset,
        pairCount = options.pairs,
        baseSeed = options.seed,
        workerThreads = options.threads,
        checkpointRoot = diagnosticOutput("arena"),
    )
    val name = "${options.opponent.name.lowercase()}-${options.pairOffset}-${options.pairs}.json"
    val path = diagnosticOutput("arena/shards/$name")
    writeJsonAtomically(path, shard)
    println("Arena shard ${shard.pairOffset} until ${shard.pairOffset + shard.pairCount}: $path")
}

private fun SearchTeacherCommandContext.runArenaMerge() {
    val shardDirectory = options.shardDirectory
        ?: store.work("arena/shards")
    val shards = loadArenaShards(shardDirectory).filter {
        it.profileId == profile.id &&
            it.runIdentity == arena.runIdentity &&
            it.opponent == options.opponent &&
            it.baseSeed == options.seed
    }
    val report = aggregatePairedArenaShards(
        profileId = profile.id,
        runIdentity = arena.runIdentity,
        opponent = options.opponent,
        expectedPairCount = options.pairs,
        baseSeed = options.seed,
        shards = shards,
    )
    val path = diagnosticOutput("arena/${options.opponent.name.lowercase()}.json")
    writeJsonAtomically(path, report)
    println("Merged ${shards.size} shards into ${report.completePairs}/${report.pairCount} pairs: $path")
}

private fun SearchTeacherCommandContext.runTactical() {
    val report = TacticalBenchmarkRunner(registry, manifest, profile).run(includeStrategicReference = true)
    val path = diagnosticOutput("tactical/report.json")
    writeJsonAtomically(path, report)
    println(
        "Tactical check: ${report.mechanicallyForcedSolved}/${report.mechanicallyForcedTotal} " +
            "hand-authored mechanically forced cases solved, " +
            "${report.strategicSeparatedCases} separated strategic references, " +
            "${report.proposalStressFailures} proposal and ${report.hiddenStateFailures} hidden-state failures " +
            "and every declared condition satisfied=${report.passed}. This does not establish general " +
            "strategy or complete action coverage. Report: $path"
    )
}

private fun SearchTeacherCommandContext.runTacticalAuthoring() {
    val (packet, path) = TacticalAuthoringPacketGenerator(root, registry, manifest)
        .generate(options.caseLimit)
    println("Tactical authoring packet ${packet.scenarios.size} scenarios: $path")
}

private fun SearchTeacherCommandContext.runTacticalHorizonAuthoring() {
    val limit = options.caseLimit.coerceAtMost(TacticalHorizonCatalog.cases.size)
    val (packet, path) = TacticalAuthoringPacketGenerator(root, registry, manifest)
        .generateHorizonSuite(limit)
    println("Tactical horizon authoring packet ${packet.scenarios.size} scenarios: $path")
}

private fun SearchTeacherCommandContext.runLatencyPreflight() {
    val report = LatencyPreflightRunner(root, registry, manifest, options.caseLimit).run()
    val path = diagnosticOutput("latency-preflight/report.json")
    writeJsonAtomically(path, report)
    report.candidates.forEach { candidate ->
        println(
            "Preflight ${candidate.profile.leaf}: " +
                "p95=${"%.1f".format(candidate.measuredPoint.p95Millis)} ms, " +
                "score=${"%.3f".format(candidate.measuredPoint.tacticalScore)}, " +
                "JFR samples=${candidate.executionSamples}"
        )
    }
    println(
        "Every declared finite-case agreement, repeated-choice, p95 latency, and profiling-sample " +
            "condition was satisfied: ${report.passed}. This work-only result does not authorize profile " +
            "selection. Report: $path"
    )
    check(report.passed) { "Latency preflight failed: ${report.failureReasons}" }
}

private fun SearchTeacherCommandContext.runCorpus() {
    val corpus = SearchTeacherCorpus(root, registry, manifest, profile, options.seed)
        .generate(options.games, options.threads)
    require(corpus.passed) {
        "Corpus generation retained stopped or ineligible games in work-only quarantine; no labels were " +
            "admitted. Attempt accounting: " +
            store.work("corpus-quarantine/${corpus.profileId}-${options.seed}/attempt-report.json")
    }
    val path = diagnosticOutput("corpus/v5/manifest.json")
    writePublicJsonAtomically(path, corpus)
    println(
        "${corpus.replayVerifiedGames}/${corpus.requestedGames} requested corpus games replayed with the " +
            "recorded choices and outcomes; every corpus condition was satisfied=${corpus.passed}. Replay " +
            "agreement does not establish strategic label quality. Manifest: $path"
    )
}

private fun SearchTeacherCommandContext.runAblations() {
    val report = SearchMethodAblations(
        registry, manifest, profile, options.seed,
        diagnosticOutput("arena"),
    )
        .run(options.pairs, options.threads)
    val path = diagnosticOutput("ablations/search-methods.json")
    writeJsonAtomically(path, report)
    println("Search-method ablations ${report.methods.size} methods x ${options.pairs} pairs: $path")
}

private fun SearchTeacherCommandContext.runBelief() {
    val report = BeliefComparisonEvaluation(
        registry, manifest, profile, options.seed,
        diagnosticOutput("arena"),
    )
        .run(options.pairs, options.heldOutPairs, options.threads)
    val path = diagnosticOutput("belief/comparison.json")
    writeJsonAtomically(path, report)
    println(
        "The declared comparison selected policy-conditioned unseen-card weighting for later teacher " +
            "experiments: ${report.conditionedModeSelected}. This selection is relative to the recorded opponents, games, " +
            "and threshold and does not establish that the probabilities match human beliefs. Report: $path"
    )
}

private fun SearchTeacherCommandContext.runOpponentModels() {
    val report = OpponentModelEvaluation(
        registry, manifest, profile, options.seed,
        diagnosticOutput("arena"),
    )
        .run(options.pairs, options.threads)
    val path = diagnosticOutput("ablations/opponent-models.json")
    writeJsonAtomically(path, report)
    println("Opponent-model ablations ${report.models.size} models x ${options.pairs} pairs: $path")
}

private fun SearchTeacherCommandContext.runPopulation() {
    val report = PopulationEvaluation(
        registry, manifest, profile, options.seed,
        diagnosticOutput("arena"),
    )
        .run(options.pairs, options.threads)
    val path = diagnosticOutput("population/cross-play.json")
    writeJsonAtomically(path, report)
    println(
        "Every declared comparison against the recorded policy population met its report conditions: " +
            "${report.passed}. This does not establish performance against unlisted policies or people. " +
            "Report: $path"
    )
}

private fun SearchTeacherCommandContext.runReview() {
    val result = ReviewEvidenceGenerator(root, registry, manifest, options.seed).generate(
        corpusManifestPath = options.corpusManifest
            ?: store.work("corpus/v5/manifest.json").takeIf(Files::isRegularFile)
            ?: store.latest("corpus/v5/manifest.json"),
        reviewItems = options.reviewItems,
        surprisingCases = options.surprisingCases,
    )
    val packetPath = diagnosticOutput("review/expert-packet.json")
    writePublicJsonAtomically(packetPath, result.packet)
    val replayPath = diagnosticOutput("review/privileged/surprising-lines.privileged.json")
    writeJsonAtomically(replayPath, result.surprisingLines)
    println(
        "Review packet ${result.packet.itemCount} blinded decisions; " +
            "${result.surprisingLines.verifiedCases}/${result.surprisingLines.requestedCases} " +
            "surprising lines replayed: $packetPath; $replayPath"
    )
}

private fun SearchTeacherCommandContext.runReplay() {
    val manifestPath = options.corpusManifest
        ?: store.work("corpus/v5/manifest.json").takeIf(Files::isRegularFile)
        ?: store.latest("corpus/v5/manifest.json")
    val corpus = evidenceJson.decodeFromString<CorpusManifest>(Files.readString(manifestPath))
    val verifier = SearchTeacherCorpus(root, registry, manifest, profile, options.seed)
    val results = corpus.entries.map { entry ->
        val publicPath = root.resolve(entry.publicTrajectory)
        val canonicalPath = privilegedCanonicalReplayPath(publicPath)
        entry.gameId to verifier.verifyExisting(
            publicPath,
            canonicalPath.takeIf(Files::exists) ?: privilegedDebugPath(publicPath),
        )
    }
    results.forEach { (gameId, replay) -> println("$gameId: $replay") }
    check(results.all { it.second.verified }) { "One or more corpus replays diverged" }
}

private fun SearchTeacherCommandContext.runThroughput() {
    val report = ThroughputProfiler(root, registry, manifest, profile).run(options.caseLimit)
    val path = diagnosticOutput("throughput/${profile.id}.json")
    writeJsonAtomically(path, report)
    println(
        "Throughput recorded ${"%.2f".format(report.decisionsPerSecond)} decisions/s, " +
            "p95=${"%.1f".format(report.decisionP95Millis)} ms, " +
            "${report.executionSamples} JFR samples, and every declared timing/profiling condition " +
            "satisfied=${report.passed}. This host-specific measurement does not establish playing " +
            "strength or latency elsewhere. Report: $path"
    )
}

private fun SearchTeacherCommandContext.runBaselineHardening() {
    val corpusPath = options.corpusManifest
        ?: store.work("corpus/v5/manifest.json").takeIf(Files::isRegularFile)
        ?: store.latest("corpus/v5/manifest.json")
    val hardening = BaselineHardeningRunner(root, registry, manifest, profile, options.seed)
        .run(corpusPath, options.games, options.threads)
    println("Baseline hardening work bundle: $hardening")
}

@Serializable
@kotlinx.serialization.SerialName("org.mtgallium.evaluation.searchteacher.SmokeReport")
private data class SmokeReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val deckId: String,
    val deckHash: String,
    val gridConfigurations: Int,
    val heuristicGame: GameRunResult,
    val searchGame: GameRunResult,
    val passed: Boolean,
)

private fun loadArenaShards(directory: Path): List<PairedArenaShard> {
    require(Files.isDirectory(directory)) { "Arena shard directory does not exist: $directory" }
    return Files.list(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
            .sorted()
            .map { evidenceJson.decodeFromString<PairedArenaShard>(Files.readString(it)) }
            .toList()
    }
}
