package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.PolicyCompressionConfig
import org.mtgallium.agent.searchteacher.SearchReuseConfig
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance

internal const val BASELINE_FACTORIAL_TOURNAMENT_VERSION = "baseline-factorial-v1"
internal const val BASELINE_FACTORIAL_BASE_SEED = 1063712001183646703L
internal const val BASELINE_FACTORIAL_PAIRS = 25
internal const val BASELINE_FACTORIAL_WORKERS = 4
internal const val BASELINE_FACTORIAL_GAMES = 250
internal const val BASELINE_FACTORIAL_SMOKE_GAMES = 6
internal const val BASELINE_FACTORIAL_SMOKE_WORKERS = 4
private const val BASELINE_FACTORIAL_SCHEDULE_VERSION = "interleaved-pair-jobs-opaque-uuid-v2"
private const val BASELINE_FACTORIAL_SCORED_DOMAIN = "baseline-factorial-library-orders"
private const val BASELINE_FACTORIAL_SMOKE_DOMAIN = "baseline-factorial-smoke-library-orders"
private const val BASELINE_FACTORIAL_SEED_ALGORITHM = "ComponentSeeds.derive-v1"
private const val BASELINE_FACTORIAL_ANALYTICS_ID = "pair-index-bootstrap-percentile-v1"
private const val BASELINE_FACTORIAL_BOOTSTRAP_RESAMPLES = 10_000

@Serializable
internal data class BaselineFactorialRunManifest(
    val schemaVersion: Int = 2, val runIdentity: String, val recordKind: String,
    val outerCommit: String, val argentumCommit: String, val sourceProvenance: PolicySourceProvenance,
    val policyEvidenceIdentities: Map<String, String>, val deckHash: String, val cardPoolHash: String,
    val baseSeed: Long, val seedDerivationDomain: String, val pairsPerMatchup: Int, val workerThreads: Int,
    val scheduleVersion: String, val seedDerivationAlgorithm: String, val analyticsId: String, val bootstrapResamples: Int,
    val operatingSystem: String, val architecture: String, val availableProcessors: Int, val jvmIdentity: String, val maximumHeapBytes: Long,
    val maximumGameDecisions: Int, val scheduledGames: List<BaselineScheduledGame>, val outputSemantics: String,
    val prerequisiteSmokeIdentity: String? = null, val prerequisiteSmokeManifestSha256: String? = null,
    val prerequisiteSmokeReportSha256: String? = null,
)

@Serializable
internal data class BaselineScheduledGame(
    val gameId: String, val firstPolicyId: String, val secondPolicyId: String, val pairIndex: Int, val leg: String,
    val p0PolicyId: String, val p1PolicyId: String, val seed: Long,
)

@Serializable
internal data class BaselineFactorialSmokeReport(
    val schemaVersion: Int = 1, val recordKind: String = "WORK_ONLY_BASELINE_FACTORIAL_SMOKE",
    val smokeIdentity: String, val generatedAtUtc: String, val manifestSha256: String,
    val games: List<TournamentGameSummary>, val passed: Boolean, val failureReasons: List<String>,
    val maximumGameElapsedMillis: Double?, val configuredMaximumHeapBytes: Long, val heapUsedAfterSmokeBytes: Long,
    val heapHeadroomFractionAfterSmoke: Double,
    val neverPooledWithScoredTournament: Boolean = true,
)

/** Fixed, work-only roster for the captured baseline proposal; it is deliberately not a publisher. */
internal object BaselineFactorialRoster {
    const val ROLLOUT_V2_ID = "search-bounded_rollout-mtgallium_visible_v2"
    const val CURRENT_V2_ID = "search-current_information_state-mtgallium_visible_v2"
    const val ROLLOUT_V3_ID = "search-bounded_rollout-mtgallium_tactical_v3"
    const val CURRENT_V3_ID = "search-current_information_state-mtgallium_tactical_v3"
    const val HEURISTIC_ID = "argentum-production-heuristic"

    fun policies(): List<ArenaPolicySpec> {
        val base = SearchTeacherArena.smokeProfile().copy(
            particles = 8,
            simulations = 64,
            maxPolicyDecisions = 32,
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
        )
        fun search(id: String, stateSource: LeafStateSource, evaluator: LeafEvaluator) = ArenaPolicySpec(
            id = id,
            kind = ArenaPolicyKind.SEARCH,
            profile = base.copy(leaf = LeafEvaluationConfig(stateSource, evaluator)),
            // The captured proposal predates the repaired player-choice boundary: compression is not runnable.
            policyCompression = PolicyCompressionConfig(enabled = false),
            searchReuse = SearchReuseConfig(enabled = false),
        )
        return listOf(
            search(ROLLOUT_V2_ID, LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
            search(CURRENT_V2_ID, LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
            search(ROLLOUT_V3_ID, LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_TACTICAL_V3),
            search(CURRENT_V3_ID, LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_TACTICAL_V3),
            ArenaPolicySpec(HEURISTIC_ID, ArenaPolicyKind.HEURISTIC),
        )
    }

    fun matchups(policies: List<ArenaPolicySpec> = policies()): List<Pair<ArenaPolicySpec, ArenaPolicySpec>> {
        val byId = policies.associateBy(ArenaPolicySpec::id)
        fun policy(id: String) = requireNotNull(byId[id])
        return listOf(
            policy(ROLLOUT_V2_ID) to policy(HEURISTIC_ID),
            policy(ROLLOUT_V2_ID) to policy(CURRENT_V2_ID),
            policy(ROLLOUT_V3_ID) to policy(CURRENT_V3_ID),
            policy(ROLLOUT_V2_ID) to policy(ROLLOUT_V3_ID),
            policy(CURRENT_V2_ID) to policy(CURRENT_V3_ID),
        )
    }

    fun smokeMatchups(policies: List<ArenaPolicySpec> = policies()): List<Pair<ArenaPolicySpec, ArenaPolicySpec>> {
        val byId = policies.associateBy(ArenaPolicySpec::id)
        fun policy(id: String) = requireNotNull(byId[id])
        return listOf(
            policy(ROLLOUT_V3_ID) to policy(CURRENT_V3_ID),
            policy(ROLLOUT_V2_ID) to policy(ROLLOUT_V3_ID),
            policy(CURRENT_V2_ID) to policy(CURRENT_V3_ID),
        )
    }
}

@Serializable
internal data class BaselineFactorialTournamentReport(
    val schemaVersion: Int = 1,
    val recordKind: String = "WORK_ONLY_BASELINE_FACTORIAL",
    val tournamentVersion: String = BASELINE_FACTORIAL_TOURNAMENT_VERSION,
    val runIdentity: String,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceProvenance: PolicySourceProvenance,
    val policyEvidenceIdentities: Map<String, String>,
    val smokeIdentity: String,
    val smokeManifestSha256: String,
    val smokeReportSha256: String,
    val deckHash: String,
    val cardPoolHash: String,
    val baseSeed: Long,
    val pairsPerMatchup: Int,
    val workerThreads: Int,
    val policies: List<TournamentPolicyDescription>,
    val matchups: List<TournamentMatchupReport>,
    val safeReplayArtifacts: List<TournamentArtifactDigest>,
    /** Null means an incomplete/stopped pair withheld the numerical estimate. */
    val matchupRates: List<TournamentRateEstimate?>,
    val standings: List<TournamentStanding>?,
    val startingPlayerRating: Double?,
    val completePairs: Int,
    val gameCount: Int,
    val valid: Boolean,
    val failureReasons: List<String>,
    val publicationProhibited: Boolean = true,
)

internal class BaselineFactorialTournamentRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val deckManifest: DeckManifest,
    private val baseSeed: Long = BASELINE_FACTORIAL_BASE_SEED,
) {
    private val evidence = EvidenceStore(root)

    fun run(pairsPerMatchup: Int, workerThreads: Int): BaselineFactorialTournamentReport {
        require(baseSeed == BASELINE_FACTORIAL_BASE_SEED) { "Baseline requires the accepted fixed base seed" }
        require(pairsPerMatchup == BASELINE_FACTORIAL_PAIRS) { "Baseline requires exactly 25 pairs per matchup" }
        require(workerThreads == BASELINE_FACTORIAL_WORKERS) { "Baseline requires exactly four workers" }
        val source = RunProvenance.capture(root)
        source.requireReady()
        val provenance = requireNotNull(source.sourceProvenance)
        val policies = BaselineFactorialRoster.policies()
        val matchups = BaselineFactorialRoster.matchups(policies)
        val arena = SearchTeacherArena(registry, deckManifest, SearchTeacherArena.smokeProfile(), baseSeed)
        val bindings = policies.associate { policy ->
            policy.id to arena.evidenceBinding(policy, null, provenance).identity
        }.toSortedMap()
        val smoke = requirePassedSmoke(bindings, source, provenance)
        val runIdentity = "baseline-factorial-v1-sha256:" + sha256(listOf(
            BASELINE_FACTORIAL_TOURNAMENT_VERSION, BASELINE_FACTORIAL_SCHEDULE_VERSION, encodeBaselineBindings(bindings),
            source.outerCommit, source.checkedOutArgentumCommit, evidenceJson.encodeToString(provenance),
            deckManifest.deckHash(), deckManifest.cardPoolHash(), baseSeed.toString(),
            pairsPerMatchup.toString(), workerThreads.toString(),
            smoke.report.smokeIdentity, smoke.report.manifestSha256, smoke.reportSha256,
        ).joinToString(":"))
        val runDirectory = evidence.diagnostic(
            "baseline-factorial-v1/${baselineArtifactDirectoryKey(runIdentity)}",
            "the work-only baseline checkpoints",
        )
        val runManifest = baselineManifest(runIdentity, "WORK_ONLY_BASELINE_FACTORIAL", source, provenance, bindings, deckManifest,
            pairsPerMatchup, workerThreads, BASELINE_FACTORIAL_SCORED_DOMAIN, smoke)
        val manifestPath = runDirectory.resolve("manifest.json")
        writeOrMatchManifest(manifestPath, runManifest)
        val reportPath = runDirectory.resolve("report.json")
        if (Files.isRegularFile(reportPath)) {
            val prior = evidenceJson.decodeFromString<BaselineFactorialTournamentReport>(Files.readString(reportPath))
            require(prior.runIdentity == runIdentity && prior.outerCommit == source.outerCommit &&
                prior.argentumCommit == source.checkedOutArgentumCommit && prior.sourceProvenance == provenance &&
                prior.smokeIdentity == smoke.report.smokeIdentity &&
                prior.smokeManifestSha256 == smoke.report.manifestSha256 && prior.smokeReportSha256 == smoke.reportSha256
            ) { "Baseline report identity or provenance does not match the declared manifest" }
            requireSourceUnchanged(source)
            return prior
        }
        val tracker = TournamentProgressTracker(
            progressPath = runDirectory.resolve("progress.json"), tournamentVersion = BASELINE_FACTORIAL_TOURNAMENT_VERSION,
            runIdentity = runIdentity, outerCommit = source.outerCommit, argentumCommit = source.checkedOutArgentumCommit,
            baseSeed = baseSeed, pairsPerMatchup = pairsPerMatchup, workerThreads = workerThreads,
            matchupCount = matchups.size, recordKind = "WORK_ONLY_BASELINE_FACTORIAL", policyIds = policies.map { it.id },
        )
        data class Completed(val first: String, val second: String, val index: Int, val games: List<GameRunResult>)
        return try {
            val jobs = interleavedTournamentPairJobs(matchups.size, pairsPerMatchup)
            val completed = parallelMapOrdered(jobs.size, workerThreads) { jobIndex ->
                val job = jobs[jobIndex]
                val (first, second) = matchups[job.matchupIndex]
                val index = job.pairIndex
                val checkpoint = runDirectory.resolve("${first.id}--${second.id}/pair-$index.json")
                val games = (load(checkpoint, runIdentity, first, second, index) ?: emptyList()).toMutableList()
                games.forEachIndexed { leg, game -> tracker.recordCheckpointReuse(baselineDescriptor(runIdentity, first, second, index, leg), game) }
                fun persist() = writeJsonAtomically(checkpoint, TournamentPairCheckpoint(runIdentity = runIdentity, firstPolicyId = first.id, secondPolicyId = second.id, pairIndex = index, games = games))
                while (games.size < 2) {
                    val leg = games.size
                    val descriptor = baselineDescriptor(runIdentity, first, second, index, leg)
                    tracker.prepareGame(descriptor)
                    games += try {
                        arena.playWithPolicies(descriptor.gameId, ComponentSeeds.derive(baseSeed, index, BASELINE_FACTORIAL_SCORED_DOMAIN),
                            if (leg == 0) first else second, if (leg == 0) second else first,
                            evidence = safeEvidence(runDirectory, descriptor, first, bindings, source, provenance),
                            replay = replay(runIdentity, descriptor, source), progressObserver = tracker)
                    } catch (error: Throwable) { tracker.abandonGame(descriptor.gameId, error); throw error }
                    persist()
                }
                Completed(first.id, second.id, index, games)
            }
            val summaries = matchups.map { (first, second) ->
                val pairs = completed.filter { it.first == first.id && it.second == second.id }.sortedBy { it.index }
                baselineSummary(first.id, second.id, pairsPerMatchup, pairs.flatMap { it.games }, pairs.map { it.index })
            }
            val games = summaries.flatMap { it.games }
            val safeArtifacts = games.map { game -> safeArtifact(root, runDirectory, game.gameId) }
            val completePairs = summaries.sumOf { it.games.chunked(2).count { pair -> pair.size == 2 && pair.all(::operationallyValidGame) } }
            val failures = buildList {
                if (games.size != BASELINE_FACTORIAL_GAMES) add("expected $BASELINE_FACTORIAL_GAMES scheduled games, found ${games.size}")
                if (completePairs != BASELINE_FACTORIAL_PAIRS * matchups.size) add("expected ${BASELINE_FACTORIAL_PAIRS * matchups.size} complete pairs, found $completePairs")
                if (games.map { it.gameId }.distinct().size != games.size) add("duplicate game ids")
                addAll(baselineScheduleFailures(runIdentity, matchups, summaries, baseSeed))
                if (safeArtifacts.any { it == null }) add("one or more player-visible replay artifacts are missing or unhashed")
                games.filterNot(::operationallyValidGame).forEach { add("invalid game ${it.gameId}: ${it.exception ?: "operational invariant"}") }
            }
            requireSourceUnchanged(source)
            val rating = if (failures.isEmpty()) BradleyTerry.fit(policies.map { it.id }, summaries, baseSeed) else null
            val report = BaselineFactorialTournamentReport(runIdentity = runIdentity, generatedAtUtc = Instant.now().toString(),
                outerCommit = source.outerCommit, argentumCommit = source.checkedOutArgentumCommit, sourceProvenance = provenance,
                policyEvidenceIdentities = bindings,
                smokeIdentity = smoke.report.smokeIdentity, smokeManifestSha256 = smoke.report.manifestSha256,
                smokeReportSha256 = smoke.reportSha256,
                deckHash = deckManifest.deckHash(), cardPoolHash = deckManifest.cardPoolHash(), baseSeed = baseSeed,
                pairsPerMatchup = pairsPerMatchup, workerThreads = workerThreads, policies = policies.map(::describeTournamentPolicy),
                matchups = summaries, safeReplayArtifacts = safeArtifacts.filterNotNull(), matchupRates = summaries.map { matchup ->
                    if (matchup.games.chunked(2).all { pair -> pair.size == 2 && pair.all(::operationallyValidGame) }) {
                        baselineRate(matchup, baseSeed)
                    } else null
                }, standings = rating?.standings, startingPlayerRating = rating?.startingPlayerRating,
                completePairs = completePairs, gameCount = games.size, valid = failures.isEmpty(), failureReasons = failures)
            writeJsonAtomically(reportPath, report)
            writeTextAtomically(runDirectory.resolve("report.md"), renderBaselineFactorialTournament(report))
            tracker.finish(if (report.valid) TournamentRunProgressState.COMPLETED else TournamentRunProgressState.FAILED)
            report
        } catch (error: Throwable) { tracker.finish(TournamentRunProgressState.FAILED); throw error }
    }

    private fun replay(identity: String, descriptor: TournamentGameDescriptor, source: RunProvenance): GameReplayOptions {
        val path = evidence.diagnostic(
            "baseline-factorial-v1/${baselineArtifactDirectoryKey(identity)}/replays/${descriptor.gameId}.privileged.replay.jsonl.gz",
            "the work-only baseline replay checkpoint",
        )
        return GameReplayOptions(
            path, root.relativize(path).toString(), identity,
            outerCommit = source.outerCommit, argentumCommit = source.checkedOutArgentumCommit,
        )
    }

    private fun safeEvidence(
        runDirectory: Path, descriptor: TournamentGameDescriptor, first: ArenaPolicySpec,
        bindings: Map<String, String>, source: RunProvenance, provenance: PolicySourceProvenance,
    ): GameEvidenceOptions {
        val firstPolicySeat = baselineFirstPolicySeat(descriptor)
        return GameEvidenceOptions(
            inspection = runDirectory.resolve("safe/${descriptor.gameId}.inspection.json"), inspectionPerspective = firstPolicySeat,
            outerCommit = source.outerCommit, argentumCommit = source.checkedOutArgentumCommit,
            profileHash = requireNotNull(bindings[first.id]), sourceProvenance = provenance,
        )
    }

    private fun load(
        path: Path,
        identity: String,
        first: ArenaPolicySpec,
        second: ArenaPolicySpec,
        index: Int,
    ): List<GameRunResult>? {
        if (!Files.exists(path)) return null
        require(Files.isRegularFile(path)) { "Baseline checkpoint is not a regular file: $path" }
        val checkpoint = evidenceJson.decodeFromString<TournamentPairCheckpoint>(Files.readString(path))
        require(
            checkpoint.schemaVersion == 3 && checkpoint.runIdentity == identity &&
                checkpoint.firstPolicyId == first.id && checkpoint.secondPolicyId == second.id &&
                checkpoint.pairIndex == index && checkpoint.games.size in 1..2
        ) { "Baseline checkpoint identity or shape changed; assigned games will not be rerun: $path" }
        checkpoint.games.forEachIndexed { leg, game ->
            val descriptor = baselineDescriptor(identity, first, second, index, leg)
            require(
                game.gameId == descriptor.gameId && game.p0PolicyId == descriptor.p0PolicyId &&
                    game.p1PolicyId == descriptor.p1PolicyId &&
                    game.seed == ComponentSeeds.derive(baseSeed, index, BASELINE_FACTORIAL_SCORED_DOMAIN)
            ) { "Baseline checkpoint schedule changed; assigned games will not be rerun: $path" }
        }
        return checkpoint.games
    }

    private fun requirePassedSmoke(
        bindings: Map<String, String>,
        source: RunProvenance,
        provenance: PolicySourceProvenance,
    ): VerifiedBaselineSmoke {
        val identity = baselineSmokeIdentity(bindings, source, deckManifest, BASELINE_FACTORIAL_SMOKE_WORKERS)
        val directory = evidence.diagnostic(
            "baseline-factorial-v1/smoke/${baselineArtifactDirectoryKey(identity)}",
            "the work-only baseline launch smoke",
        )
        val manifestPath = directory.resolve("manifest.json")
        val reportPath = directory.resolve("report.json")
        require(Files.isRegularFile(manifestPath) && Files.isRegularFile(reportPath)) {
            "Run the exact six-game baseline-factorial-smoke before the scored baseline"
        }
        val manifest = evidenceJson.decodeFromString<BaselineFactorialRunManifest>(Files.readString(manifestPath))
        val report = evidenceJson.decodeFromString<BaselineFactorialSmokeReport>(Files.readString(reportPath))
        val expectedManifest = baselineManifest(
            identity, "WORK_ONLY_BASELINE_FACTORIAL_SMOKE", source, provenance, bindings, deckManifest,
            pairs = 1, workers = BASELINE_FACTORIAL_SMOKE_WORKERS, domain = BASELINE_FACTORIAL_SMOKE_DOMAIN, smoke = true,
        )
        require(manifest == expectedManifest && report.smokeIdentity == identity &&
            report.manifestSha256 == sha256File(manifestPath) && report.passed && report.games.size == BASELINE_FACTORIAL_SMOKE_GAMES &&
            report.games.all(::operationallyValidTournamentSummary) && baselineSmokeMetricsPassed(
                report.games, report.configuredMaximumHeapBytes, report.heapUsedAfterSmokeBytes,
            ) && report.heapHeadroomFractionAfterSmoke >= 0.10
        ) { "Baseline launch smoke is missing, mismatched, or did not pass" }
        return VerifiedBaselineSmoke(report, sha256File(reportPath))
    }

    private fun requireSourceUnchanged(expected: RunProvenance) {
        val current = RunProvenance.capture(root)
        current.requireReady()
        require(current == expected) { "Source provenance changed during the baseline; final reporting is refused" }
    }
}

private data class VerifiedBaselineSmoke(
    val report: BaselineFactorialSmokeReport,
    val reportSha256: String,
)

internal fun baselineFirstPolicySeat(descriptor: TournamentGameDescriptor): String =
    if (descriptor.p0PolicyId == descriptor.firstPolicyId) "p0" else "p1"

/** Manifest identities retain their scheme separator; evidence directories remain portable repository paths. */
internal fun baselineArtifactDirectoryKey(identity: String): String {
    require(identity.isNotBlank() && '/' !in identity && '\\' !in identity)
    return identity.replace(':', '-')
}

/** Inspection bundles require opaque UUID game IDs; schedule fields retain the human-readable identity. */
internal fun baselineDescriptor(
    runIdentity: String, first: ArenaPolicySpec, second: ArenaPolicySpec, pairIndex: Int, legIndex: Int,
): TournamentGameDescriptor {
    require(legIndex in 0..1)
    val leg = if (legIndex == 0) "a" else "b"
    val opaqueId = UUID.nameUUIDFromBytes(
        "$BASELINE_FACTORIAL_SCHEDULE_VERSION\u0000$runIdentity\u0000${first.id}\u0000${second.id}\u0000$pairIndex\u0000$leg".toByteArray(StandardCharsets.UTF_8)
    ).toString()
    return TournamentGameDescriptor(
        gameId = opaqueId, firstPolicyId = first.id, secondPolicyId = second.id, pairIndex = pairIndex, leg = leg,
        p0PolicyId = if (legIndex == 0) first.id else second.id, p1PolicyId = if (legIndex == 0) second.id else first.id,
    )
}

private fun safeArtifact(root: Path, directory: Path, gameId: String): TournamentArtifactDigest? {
    val path = directory.resolve("safe/$gameId.inspection.json")
    return path.takeIf(Files::isRegularFile)?.let { TournamentArtifactDigest(root.relativize(it).toString(), sha256File(it), Files.size(it)) }
}

private fun baselineScheduleFailures(
    runIdentity: String, matchups: List<Pair<ArenaPolicySpec, ArenaPolicySpec>>, summaries: List<TournamentMatchupReport>, baseSeed: Long,
): List<String> = buildList {
    if (summaries.size != matchups.size) add("baseline matchup count differs from the declared five-matchup schedule")
    matchups.zip(summaries).forEach { (matchup, summary) ->
        val (first, second) = matchup
        if (summary.firstPolicyId != first.id || summary.secondPolicyId != second.id || summary.pairIndices != (0 until BASELINE_FACTORIAL_PAIRS).toList()) {
            add("baseline pair indices or matchup identity differs from the declared schedule for ${first.id}/${second.id}")
        }
        val expected = (0 until BASELINE_FACTORIAL_PAIRS).flatMap { index -> listOf(
            baselineDescriptor(runIdentity, first, second, index, 0), baselineDescriptor(runIdentity, first, second, index, 1),
        ) }
        if (summary.games.size != expected.size || summary.games.zip(expected).any { (game, descriptor) ->
            game.gameId != descriptor.gameId || game.p0PolicyId != descriptor.p0PolicyId || game.p1PolicyId != descriptor.p1PolicyId ||
                game.seed != ComponentSeeds.derive(baseSeed, descriptor.pairIndex, BASELINE_FACTORIAL_SCORED_DOMAIN)
        }) add("baseline game ids, seats, legs, or derived seeds differ from the declared schedule for ${first.id}/${second.id}")
    }
}

internal class BaselineFactorialSmokeRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val deckManifest: DeckManifest,
    private val baseSeed: Long = BASELINE_FACTORIAL_BASE_SEED,
) {
    private val evidence = EvidenceStore(root)

    fun run(workerThreads: Int): BaselineFactorialSmokeReport {
        require(baseSeed == BASELINE_FACTORIAL_BASE_SEED) { "Baseline smoke requires the accepted fixed base seed" }
        require(workerThreads == BASELINE_FACTORIAL_SMOKE_WORKERS) {
            "Baseline smoke requires exactly four workers"
        }
        val source = RunProvenance.capture(root)
        source.requireReady()
        val provenance = requireNotNull(source.sourceProvenance)
        val policies = BaselineFactorialRoster.policies()
        val arena = SearchTeacherArena(registry, deckManifest, SearchTeacherArena.smokeProfile(), baseSeed)
        val bindings = policies.associate { it.id to arena.evidenceBinding(it, null, provenance).identity }.toSortedMap()
        val identity = baselineSmokeIdentity(bindings, source, deckManifest, workerThreads)
        val directory = evidence.diagnostic(
            "baseline-factorial-v1/smoke/${baselineArtifactDirectoryKey(identity)}",
            "the work-only baseline launch smoke",
        )
        val manifestPath = directory.resolve("manifest.json")
        writeOrMatchManifest(manifestPath, baselineManifest(identity, "WORK_ONLY_BASELINE_FACTORIAL_SMOKE", source, provenance, bindings, deckManifest,
            pairs = 1, workers = workerThreads, domain = BASELINE_FACTORIAL_SMOKE_DOMAIN, smoke = true))
        val reportPath = directory.resolve("report.json")
        if (Files.isRegularFile(reportPath)) {
            val prior = evidenceJson.decodeFromString<BaselineFactorialSmokeReport>(Files.readString(reportPath))
            require(prior.smokeIdentity == identity && prior.manifestSha256 == sha256File(manifestPath)) {
                "Baseline smoke report identity does not match its manifest"
            }
            requireSourceUnchanged(source)
            return prior
        }
        val matchups = BaselineFactorialRoster.smokeMatchups(policies)
        val games = parallelMapOrdered(BASELINE_FACTORIAL_SMOKE_GAMES, workerThreads) { job ->
                val leg = job / matchups.size
                val matchupIndex = (job % matchups.size + leg) % matchups.size
                val (first, second) = matchups[matchupIndex]
                val descriptor = baselineDescriptor(identity, first, second, 0, leg)
                val replayPath = directory.resolve("replays/${descriptor.gameId}.privileged.replay.jsonl.gz")
                arena.playWithPolicies(
                    descriptor.gameId, ComponentSeeds.derive(baseSeed, 0, BASELINE_FACTORIAL_SMOKE_DOMAIN),
                    if (leg == 0) first else second, if (leg == 0) second else first,
                    replay = GameReplayOptions(
                        replayPath, root.relativize(replayPath).toString(), identity,
                        outerCommit = source.outerCommit, argentumCommit = source.checkedOutArgentumCommit,
                    ),
                )
        }
        val failures = games.filterNot(::operationallyValidGame).map {
            "invalid smoke game ${it.gameId}: ${it.exception ?: "operational invariant"}"
        }.toMutableList()
        val runtime = Runtime.getRuntime()
        val maximumHeap = runtime.maxMemory()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val headroom = (maximumHeap - usedHeap).toDouble() / maximumHeap.coerceAtLeast(1L)
        if (!baselineSmokeMetricsPassed(games.map(GameRunResult::compact), maximumHeap, usedHeap)) {
            failures += "smoke elapsed-time or JVM heap-headroom preflight did not pass"
        }
        requireSourceUnchanged(source)
        val report = BaselineFactorialSmokeReport(
            smokeIdentity = identity, generatedAtUtc = Instant.now().toString(), manifestSha256 = sha256File(manifestPath),
            games = games.map(GameRunResult::compact), passed = failures.isEmpty(), failureReasons = failures,
            maximumGameElapsedMillis = games.mapNotNull { it.elapsedMillis }.maxOrNull(), configuredMaximumHeapBytes = maximumHeap,
            heapUsedAfterSmokeBytes = usedHeap, heapHeadroomFractionAfterSmoke = headroom,
        )
        writeJsonAtomically(reportPath, report)
        return report
    }

    private fun requireSourceUnchanged(expected: RunProvenance) {
        val current = RunProvenance.capture(root)
        current.requireReady()
        require(current == expected) { "Source provenance changed during the baseline smoke; reporting is refused" }
    }
}

internal fun baselineSmokeMetricsPassed(games: List<TournamentGameSummary>, maximumHeap: Long, usedHeap: Long): Boolean =
    games.size == BASELINE_FACTORIAL_SMOKE_GAMES && games.mapNotNull { it.elapsedMillis }.let { elapsed ->
        elapsed.size == BASELINE_FACTORIAL_SMOKE_GAMES && elapsed.all { it.isFinite() && it > 0.0 }
    } && maximumHeap > 0 && (maximumHeap - usedHeap).toDouble() / maximumHeap >= 0.10

private fun baselineSmokeIdentity(
    bindings: Map<String, String>, source: RunProvenance, manifest: DeckManifest, workerThreads: Int,
): String =
    "baseline-factorial-smoke-v1-sha256:" + sha256(listOf(
        BASELINE_FACTORIAL_TOURNAMENT_VERSION, BASELINE_FACTORIAL_SCHEDULE_VERSION, encodeBaselineBindings(bindings), source.outerCommit,
        source.checkedOutArgentumCommit, evidenceJson.encodeToString(source.sourceProvenance),
        manifest.deckHash(), manifest.cardPoolHash(), BASELINE_FACTORIAL_BASE_SEED.toString(),
        System.getProperty("os.name") ?: "unknown", System.getProperty("os.arch") ?: "unknown",
        Runtime.getRuntime().availableProcessors().toString(), baselineJvmIdentity(), Runtime.getRuntime().maxMemory().toString(),
        SearchTeacherArena.MAX_GAME_DECISIONS.toString(), workerThreads.toString(),
    ).joinToString(":"))

internal fun encodeBaselineBindings(bindings: Map<String, String>): String =
    evidenceJson.encodeToString<Map<String, String>>(bindings.toSortedMap())

internal fun baselineScheduledGames(identity: String, domain: String, pairs: Int, smoke: Boolean = false): List<BaselineScheduledGame> {
    val matchups = if (smoke) BaselineFactorialRoster.smokeMatchups() else BaselineFactorialRoster.matchups()
    if (smoke) return (0..1).flatMap { leg ->
        matchups.indices.map { slot ->
            val (first, second) = matchups[(slot + leg) % matchups.size]
            baselineDescriptor(identity, first, second, 0, leg).let { descriptor ->
                BaselineScheduledGame(descriptor.gameId, descriptor.firstPolicyId, descriptor.secondPolicyId, descriptor.pairIndex,
                    descriptor.leg, descriptor.p0PolicyId, descriptor.p1PolicyId, ComponentSeeds.derive(BASELINE_FACTORIAL_BASE_SEED, 0, domain))
            }
        }
    }
    return interleavedTournamentPairJobs(matchups.size, pairs).flatMap { job ->
        matchups[job.matchupIndex].let { (first, second) -> (0..1).map { leg ->
            baselineDescriptor(identity, first, second, job.pairIndex, leg).let { descriptor ->
            BaselineScheduledGame(descriptor.gameId, descriptor.firstPolicyId, descriptor.secondPolicyId, descriptor.pairIndex,
                descriptor.leg, descriptor.p0PolicyId, descriptor.p1PolicyId,
                ComponentSeeds.derive(BASELINE_FACTORIAL_BASE_SEED, job.pairIndex, domain))
            }
        } }
    }
}

private fun baselineManifest(
    identity: String, kind: String, source: RunProvenance, provenance: PolicySourceProvenance, bindings: Map<String, String>, deck: DeckManifest,
    pairs: Int, workers: Int, domain: String, prerequisiteSmoke: VerifiedBaselineSmoke? = null, smoke: Boolean = false,
): BaselineFactorialRunManifest = BaselineFactorialRunManifest(
    runIdentity = identity, recordKind = kind, outerCommit = source.outerCommit, argentumCommit = source.checkedOutArgentumCommit,
    sourceProvenance = provenance, policyEvidenceIdentities = bindings, deckHash = deck.deckHash(), cardPoolHash = deck.cardPoolHash(),
    baseSeed = BASELINE_FACTORIAL_BASE_SEED, seedDerivationDomain = domain, pairsPerMatchup = pairs, workerThreads = workers,
    scheduleVersion = BASELINE_FACTORIAL_SCHEDULE_VERSION, seedDerivationAlgorithm = BASELINE_FACTORIAL_SEED_ALGORITHM,
    analyticsId = BASELINE_FACTORIAL_ANALYTICS_ID, bootstrapResamples = BASELINE_FACTORIAL_BOOTSTRAP_RESAMPLES,
    operatingSystem = System.getProperty("os.name") ?: "unknown", architecture = System.getProperty("os.arch") ?: "unknown",
    availableProcessors = Runtime.getRuntime().availableProcessors(), jvmIdentity = baselineJvmIdentity(),
    maximumHeapBytes = Runtime.getRuntime().maxMemory(), maximumGameDecisions = SearchTeacherArena.MAX_GAME_DECISIONS,
    scheduledGames = baselineScheduledGames(identity, domain, pairs, smoke),
    outputSemantics = "Work-only checkpoint and replay artifacts; never latest, frozen, review, or publication output; smoke games are never pooled with the scored tournament.",
    prerequisiteSmokeIdentity = prerequisiteSmoke?.report?.smokeIdentity,
    prerequisiteSmokeManifestSha256 = prerequisiteSmoke?.report?.manifestSha256,
    prerequisiteSmokeReportSha256 = prerequisiteSmoke?.reportSha256,
)

private fun baselineJvmIdentity(): String = listOfNotNull(
    System.getProperty("java.vendor"), System.getProperty("java.vm.name"), System.getProperty("java.version"),
).joinToString(" | ")

private fun writeOrMatchManifest(path: Path, manifest: BaselineFactorialRunManifest) {
    val encoded = evidenceJson.encodeToString(manifest) + "\n"
    if (!Files.exists(path)) writeJsonAtomically(path, manifest)
    else require(Files.readString(path) == encoded) { "Baseline manifest changed; exact checkpoint resume is refused" }
}

internal fun renderBaselineFactorialTournament(report: BaselineFactorialTournamentReport): String = buildString {
    appendLine("# Work-only Search Teacher v2/v3 factorial baseline")
    appendLine()
    appendLine("Recorded ${report.gameCount}/$BASELINE_FACTORIAL_GAMES games and ${report.completePairs}/${BASELINE_FACTORIAL_PAIRS * 5} complete seat-swapped pairs.")
    report.matchups.zip(report.matchupRates).forEach { (matchup, rate) ->
        appendLine("- ${matchup.firstPolicyId} vs ${matchup.secondPolicyId}: raw W-D-L ${matchup.winsFirst}-${matchup.draws}-${matchup.winsSecond}; " +
            (rate?.let { "centered pair score ${"%.3f".format(it.pointRate - 0.5)} (pair-block 95% ${"%.1f".format(it.confidenceLower * 100)}–${"%.1f".format(it.confidenceUpper * 100)}%)" }
                ?: "numerical rate withheld because one or more assigned pairs was incomplete or invalid"))
    }
    appendLine()
    appendLine("This work-only output is not published evidence and cannot establish general strength, human-relative performance, or training-label quality.")
    if (report.failureReasons.isNotEmpty()) report.failureReasons.forEach { appendLine("- $it") }
}

private fun baselineSummary(first: String, second: String, pairs: Int, games: List<GameRunResult>, pairIndices: List<Int>): TournamentMatchupReport {
    val scored = games.filter(::operationallyValidGame)
    fun score(game: GameRunResult): Double = when (game.winner) {
        null -> 0.5
        "p0" -> if (game.p0PolicyId == first) 1.0 else 0.0
        else -> if (game.p1PolicyId == first) 1.0 else 0.0
    }
    val scores = scored.map(::score)
    return TournamentMatchupReport(first, second, pairs, scores.count { it == 1.0 }, scores.count { it == 0.5 }, scores.count { it == 0.0 },
        scores.average().takeIf { scores.isNotEmpty() } ?: 0.0, games, pairIndices)
}

private fun baselineRate(matchup: TournamentMatchupReport, seed: Long): TournamentRateEstimate {
    val scores = matchup.pairIndices.zip(matchup.games.chunked(2)).map { (index, pair) ->
        TournamentPairIndexScore(index, pair.sumOf { game ->
            val p0 = when (game.winner) { "p0" -> 1.0; "p1" -> 0.0; else -> 0.5 }
            if (game.p0PolicyId == matchup.firstPolicyId) p0 else 1.0 - p0
        } / 2.0)
    }
    val interval = pairIndexBootstrapInterval(scores, ComponentSeeds.derive(seed, "${matchup.firstPolicyId}/${matchup.secondPolicyId}", "baseline-factorial-pair-blocks"))
    return TournamentRateEstimate(matchup.firstPolicyId, matchup.secondPolicyId, scores.size, scores.size * 2,
        scores.sumOf { it.value }, scores.map { it.value }.average(), interval.first, interval.second)
}
