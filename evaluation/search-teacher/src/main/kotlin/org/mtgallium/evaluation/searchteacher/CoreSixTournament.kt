package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Random
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.PolicyCompressionConfig
import org.mtgallium.agent.searchteacher.SearchReuseConfig
import org.mtgallium.agent.searchteacher.SearchTeacherLeafConfigurations
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints

internal const val CORE_SIX_TOURNAMENT_VERSION = "core-six-pilot-v8-replay-routing-four-worker-search-perf"

internal data class ArenaPolicySpec(
    val id: String,
    val kind: ArenaPolicyKind,
    val profile: FrozenSearchProfile? = null,
    /**
     * Current production policy composition, when it is intentionally not one of the
     * historical frozen arena profiles.  This keeps evaluation provenance from relabeling it.
     */
    val parameters: SearchTeacherPolicyParameters? = null,
    val beliefMode: BeliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
    val beliefArchitecture: BeliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
    val searchPlanner: SearchPlannerKind = SearchPlannerKind.SHARED_TREE,
    val policyCompression: PolicyCompressionConfig = PolicyCompressionConfig(),
    val searchReuse: SearchReuseConfig = SearchReuseConfig(),
) {
    init {
        require(id.isNotBlank())
        require((kind == ArenaPolicyKind.SEARCH) == (profile != null || parameters != null))
        require(profile == null || parameters == null)
    }

    fun effectiveParameters(arenaBaseSeed: Long): SearchTeacherPolicyParameters =
        parameters ?: requireNotNull(profile).policyParameters(
            baseSeed = arenaBaseSeed,
            beliefMode = beliefMode,
            beliefArchitecture = beliefArchitecture,
            policyCompression = policyCompression,
            searchReuse = searchReuse,
        )
}

@Serializable
internal data class TournamentPolicyDescription(
    val id: String,
    val kind: ArenaPolicyKind,
    val leaf: LeafEvaluationConfig? = null,
    val particles: Int? = null,
    val simulations: Int? = null,
    val maxPolicyDecisions: Int? = null,
    val explorationConstant: Double? = null,
    val actionSpaceProfile: SearchActionSpaceProfile? = null,
    val beliefMode: BeliefMode? = null,
    val beliefArchitecture: BeliefArchitecture? = null,
    val searchPlanner: SearchPlannerKind? = null,
    val opponentPolicyId: String? = null,
    val policyCompression: PolicyCompressionConfig? = null,
    val searchReuse: SearchReuseConfig? = null,
)

@Serializable
internal data class TournamentMatchupReport(
    val firstPolicyId: String,
    val secondPolicyId: String,
    val pairCount: Int,
    val winsFirst: Int,
    val draws: Int,
    val winsSecond: Int,
    val firstPointRate: Double,
    val games: List<GameRunResult>,
    /** Shared library-order block assigned to each consecutive seat-swapped pair. */
    val pairIndices: List<Int> = (0 until pairCount).toList(),
)

@Serializable
internal data class TournamentStanding(
    val rank: Int,
    val policyId: String,
    val rating: Double,
    val confidenceLower: Double,
    val confidenceUpper: Double,
    val gamePointRate: Double,
    val wins: Int,
    val draws: Int,
    val losses: Int,
)

@Serializable
internal data class CoreSixTournamentReport(
    val schemaVersion: Int = 8,
    val tournamentVersion: String = CORE_SIX_TOURNAMENT_VERSION,
    val runIdentity: String = "",
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceProvenance: PolicySourceProvenance? = null,
    val policyEvidenceIdentities: Map<String, String> = emptyMap(),
    val deckHash: String,
    val benchmarkPath: String,
    val benchmarkSha256: String,
    val benchmarkOuterCommit: String,
    val benchmarkArgentumCommit: String,
    /** Legacy launch-review fields retained only so historical reports still decode. */
    val reviewManifestSha256: String = "",
    val reviewerName: String = "",
    val reviewedAtUtc: String = "",
    val reviewReference: String = "",
    val baseSeed: Long,
    val pairsPerMatchup: Int,
    val workerThreads: Int,
    val runtimeWarmupWorkers: Int,
    val policies: List<TournamentPolicyDescription>,
    val matchups: List<TournamentMatchupReport>,
    val standings: List<TournamentStanding>,
    val startingPlayerRating: Double,
    val completePairs: Int,
    val gameCount: Int,
    val cleanupDiscardEvents: Int,
    val gamesWithCleanupDiscard: Int,
    val cleanupDiscardGameRate: Double,
    val policyQualityWarnings: List<String>,
    val valid: Boolean,
    val failureReasons: List<String>,
)

@Serializable
internal data class TournamentPairCheckpoint(
    val schemaVersion: Int = 3,
    val runIdentity: String,
    val firstPolicyId: String,
    val secondPolicyId: String,
    val pairIndex: Int,
    val games: List<GameRunResult>,
)

/** Current Core Six payload; the run relation now belongs to the generic checkpoint envelope. */
@Serializable
private data class CoreSixPairPayload(
    val firstPolicyId: String,
    val secondPolicyId: String,
    val pairIndex: Int,
    val games: List<GameRunResult>,
)

private const val CORE_SIX_PAIR_CHECKPOINT_SCHEMA = "core-six-pair-v1"

internal object CoreSixRoster {
    fun policies(): List<ArenaPolicySpec> {
        val base = SearchTeacherArena.smokeProfile().copy(
            maxPolicyDecisions = 32,
            particles = 8,
            simulations = 64,
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
        )
        return SearchTeacherLeafConfigurations.supported.map { leaf ->
            ArenaPolicySpec(
                id = "search-${leaf.stateSource.name.lowercase()}-${leaf.evaluator.name.lowercase()}",
                kind = ArenaPolicyKind.SEARCH,
                profile = base.copy(leaf = leaf),
                policyCompression = PolicyCompressionConfig(enabled = false),
                searchReuse = SearchReuseConfig(enabled = false),
            )
        } + ArenaPolicySpec("argentum-production-heuristic", ArenaPolicyKind.HEURISTIC)
    }
}

internal class CoreSixTournament(
    private val root: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val manifest: DeckManifest,
    private val baseSeed: Long,
    private val benchmarkPath: Path,
) {
    private val evidence = EvidenceStore(root)

    fun run(pairsPerMatchup: Int, workerThreads: Int): CoreSixTournamentReport {
        require(pairsPerMatchup > 0)
        require(workerThreads > 0)
        val benchmark = decodeLegacyTacticalLeafBenchmarkReport(Files.readString(benchmarkPath))
        require(benchmark.completed) { "Legacy benchmark did not complete: ${benchmark.failureReasons}" }
        require(benchmark.cases.size == 48 && benchmark.leafResults.size == 5)
        require(benchmark.particles == 8 && benchmark.simulations == 64 && benchmark.maxPolicyDecisions == 32)
        val benchmarkHash = sha256File(benchmarkPath)
        val policies = CoreSixRoster.policies()
        require(policies.map { it.id }.distinct().size == policies.size)
        val sourceRun = RunProvenance.capture(root)
        sourceRun.requireReady()
        val sourceProvenance = requireNotNull(sourceRun.sourceProvenance)
        val arena = SearchTeacherArena(registry, manifest, SearchTeacherArena.smokeProfile(), baseSeed)
        val policyEvidenceIdentities = policies.associate { policy ->
            policy.id to arena.evidenceBinding(
                policy = policy,
                maxSearchDecisions = null,
                sourceProvenance = sourceProvenance,
            ).identity
        }.toSortedMap()
        val runIdentity = ResearchRunBindings(
            protocol = CORE_SIX_TOURNAMENT_VERSION,
            material = mapOf(
                "source-provenance" to sha256(evidenceJson.encodeToString(sourceProvenance)),
                "policy-evidence" to sha256(evidenceJson.encodeToString(policyEvidenceIdentities)),
                "deck" to manifest.deckHash(), "card-pool" to manifest.cardPoolHash(),
                "benchmark" to benchmarkHash, "base-seed" to baseSeed.toString(),
                "pairs-per-matchup" to pairsPerMatchup.toString(),
            ),
        ).identity
        val progressPath = evidence.diagnostic(
            "tournament/$runIdentity/progress.json",
            "the tournament progress checkpoint",
        )
        val tracker = TournamentProgressTracker(
            progressPath = progressPath,
            tournamentVersion = CORE_SIX_TOURNAMENT_VERSION,
            runIdentity = runIdentity,
            outerCommit = sourceRun.outerCommit,
            argentumCommit = sourceRun.checkedOutArgentumCommit,
            baseSeed = baseSeed,
            pairsPerMatchup = pairsPerMatchup,
            workerThreads = workerThreads,
        )
        println("Tournament progress JSON: $progressPath")
        val runtimeWarmupWorkers = minOf(workerThreads, RECOMMENDED_TOURNAMENT_WORKERS)
        val matchupSpecs = policies.indices.flatMap { first ->
            (first + 1 until policies.size).map { second -> policies[first] to policies[second] }
        }
        data class CompletedPair(
            val firstPolicyId: String,
            val secondPolicyId: String,
            val pairIndex: Int,
            val games: List<GameRunResult>,
        )
        return try {
            warmRuntime(arena, policies, runtimeWarmupWorkers)
            val jobs = interleavedTournamentPairJobs(matchupSpecs.size, pairsPerMatchup)
            val completedPairs = parallelMapOrdered(jobs.size, workerThreads) { jobIndex ->
                val job = jobs[jobIndex]
                val pairIndex = job.pairIndex
                val (first, second) = matchupSpecs[job.matchupIndex]
                val checkpoint = checkpointPath(runIdentity, first.id, second.id, pairIndex)
                val seed = ComponentSeeds.derive(baseSeed, pairIndex, "core-six-library-orders")
                val games = (loadCheckpoint(checkpoint, runIdentity, first.id, second.id, pairIndex) ?: emptyList())
                    .toMutableList()
                games.forEachIndexed { index, game ->
                    tracker.recordCheckpointReuse(
                        gameDescriptor(first, second, pairIndex, index),
                        game,
                    )
                }
                fun persist() = ResearchRunCheckpoints.persist(
                    checkpoint, runIdentity, CORE_SIX_PAIR_CHECKPOINT_SCHEMA, games.size.toLong(),
                    evidenceJson.encodeToString(CoreSixPairPayload(
                        firstPolicyId = first.id,
                        secondPolicyId = second.id,
                        pairIndex = pairIndex,
                        games = games,
                    )).encodeToByteArray(),
                )
                if (games.isEmpty()) {
                    val descriptor = gameDescriptor(first, second, pairIndex, 0)
                    tracker.prepareGame(descriptor)
                    games += try {
                        arena.playWithPolicies(
                            descriptor.gameId,
                            seed,
                            first,
                            second,
                            replay = replayOptions(runIdentity, descriptor),
                            progressObserver = tracker,
                        )
                    } catch (error: Throwable) {
                        tracker.abandonGame(descriptor.gameId, error)
                        throw error
                    }
                    persist()
                }
                if (games.size == 1) {
                    val descriptor = gameDescriptor(first, second, pairIndex, 1)
                    tracker.prepareGame(descriptor)
                    games += try {
                        arena.playWithPolicies(
                            descriptor.gameId,
                            seed,
                            second,
                            first,
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
            val pairsByMatchup = completedPairs.groupBy { it.firstPolicyId to it.secondPolicyId }
            val matchups = matchupSpecs.map { (first, second) ->
                val matchupPairs = requireNotNull(pairsByMatchup[first.id to second.id])
                    .sortedBy(CompletedPair::pairIndex)
                summarizeMatchup(
                    first.id,
                    second.id,
                    pairsPerMatchup,
                    matchupPairs.flatMap(CompletedPair::games),
                    matchupPairs.map(CompletedPair::pairIndex),
                )
            }
            val allGames = matchups.flatMap { it.games }
            val cleanupDiscardEvents = allGames.sumOf(GameRunResult::cleanupDiscardEvents)
            val gamesWithCleanupDiscard = allGames.count { it.cleanupDiscardEvents > 0 }
            val policyQualityWarnings = cleanupPolicyQualityWarnings(allGames)
            val failures = buildList {
                if (matchups.size != 15) add("expected 15 matchups, found ${matchups.size}")
                if (allGames.size != 30 * pairsPerMatchup) add("expected ${30 * pairsPerMatchup} games, found ${allGames.size}")
                allGames.filterNot(::operationallyValidGame).forEach {
                    add("invalid game ${it.gameId}: ${it.exception ?: "operational invariant"}")
                }
            }
            val rating = BradleyTerry.fit(policies.map { it.id }, matchups, baseSeed)
            val report = CoreSixTournamentReport(
                runIdentity = runIdentity,
                generatedAtUtc = Instant.now().toString(),
                outerCommit = sourceRun.outerCommit,
                argentumCommit = sourceRun.checkedOutArgentumCommit,
                sourceProvenance = sourceProvenance,
                policyEvidenceIdentities = policyEvidenceIdentities,
                deckHash = manifest.deckHash(),
                benchmarkPath = root.relativize(benchmarkPath).toString(),
                benchmarkSha256 = benchmarkHash,
                benchmarkOuterCommit = benchmark.outerCommit,
                benchmarkArgentumCommit = benchmark.argentumCommit,
                baseSeed = baseSeed,
                pairsPerMatchup = pairsPerMatchup,
                workerThreads = workerThreads,
                runtimeWarmupWorkers = runtimeWarmupWorkers,
                policies = policies.map(::describeTournamentPolicy),
                matchups = matchups,
                standings = rating.standings,
                startingPlayerRating = rating.startingPlayerRating,
                completePairs = matchups.sumOf {
                    matchup -> matchup.games.chunked(2).count { pair -> pair.all(::operationallyValidGame) }
                },
                gameCount = allGames.size,
                cleanupDiscardEvents = cleanupDiscardEvents,
                gamesWithCleanupDiscard = gamesWithCleanupDiscard,
                cleanupDiscardGameRate = gamesWithCleanupDiscard.toDouble() / allGames.size.coerceAtLeast(1),
                policyQualityWarnings = policyQualityWarnings,
                valid = failures.isEmpty(),
                failureReasons = failures,
            )
            tracker.finish(
                if (report.valid) TournamentRunProgressState.COMPLETED else TournamentRunProgressState.FAILED
            )
            report
        } catch (error: Throwable) {
            tracker.finish(TournamentRunProgressState.FAILED)
            throw error
        }
    }

    private fun warmRuntime(
        arena: SearchTeacherArena,
        policies: List<ArenaPolicySpec>,
        workers: Int,
    ) {
        val search = policies.first { it.kind == ArenaPolicyKind.SEARCH }
        val heuristic = policies.single { it.kind == ArenaPolicyKind.HEURISTIC }
        println("Warming tournament search runtime with $workers representative workers")
        val warmups = parallelMapOrdered(workers, workers) { index ->
            arena.playWithPolicies(
                gameId = "runtime-warmup-$index",
                gameSeed = ComponentSeeds.derive(baseSeed, index, "core-six-runtime-warmup"),
                p0Policy = search,
                p1Policy = heuristic,
                maxSearchDecisions = 1,
            )
        }
        warmups.forEach { result ->
            require(result.exception == null && result.informationLedgerComplete) {
                "Tournament runtime warmup failed for ${result.gameId}: ${result.exception}"
            }
        }
    }

    private companion object {
        const val RECOMMENDED_TOURNAMENT_WORKERS = 4
    }

    private fun gameDescriptor(
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

    private fun checkpointPath(runIdentity: String, first: String, second: String, pairIndex: Int): Path =
        evidence.diagnostic(
            "tournament/$runIdentity/$first--$second/pair-$pairIndex.json",
            "the tournament pair checkpoint",
        )

    private fun replayOptions(runIdentity: String, descriptor: TournamentGameDescriptor): GameReplayOptions {
        val path = evidence.diagnostic(
            "tournament/$runIdentity/replays/${descriptor.gameId}.privileged.replay.jsonl.gz",
            "the tournament replay checkpoint",
        )
        return GameReplayOptions(
            finalPath = path,
            referencePath = root.relativize(path).toString(),
            runIdentity = runIdentity,
        )
    }

    private fun loadCheckpoint(path: Path, identity: String, first: String, second: String, pairIndex: Int): List<GameRunResult>? =
        runCatching {
            val envelope = ResearchRunCheckpoints.load(path)
            require(envelope.researchRunIdentity == identity && envelope.payloadSchema == CORE_SIX_PAIR_CHECKPOINT_SCHEMA)
            evidenceJson.decodeFromString<CoreSixPairPayload>(envelope.payload().decodeToString()).takeIf {
                it.firstPolicyId == first && it.secondPolicyId == second && it.pairIndex == pairIndex &&
                    it.games.size in 1..2 && it.games.all(::replayArtifactIsIntact)
            }?.games
        }.getOrElse {
            // Only historical Core Six checkpoints use the retired direct payload envelope.
            runCatching {
                evidenceJson.decodeFromString<TournamentPairCheckpoint>(Files.readString(path)).takeIf {
                    it.schemaVersion == 3 && it.runIdentity == identity && it.firstPolicyId == first &&
                        it.secondPolicyId == second && it.pairIndex == pairIndex && it.games.size in 1..2 &&
                        it.games.all(::replayArtifactIsIntact)
                }?.games
            }.getOrNull()
        }

    private fun replayArtifactIsIntact(game: GameRunResult): Boolean {
        if (!game.replayVerified) return false
        val reference = game.replayPath ?: return false
        val expectedHash = game.replaySha256 ?: return false
        val raw = Path.of(reference)
        val path = if (raw.isAbsolute) raw else root.resolve(raw)
        return Files.isRegularFile(path) && sha256File(path) == expectedHash &&
            TournamentReplayVerifier(registry, manifest).verify(path).verified
    }
}

internal fun describeTournamentPolicy(policy: ArenaPolicySpec): TournamentPolicyDescription =
    TournamentPolicyDescription(
        id = policy.id,
        kind = policy.kind,
        leaf = policy.parameters?.leaf ?: policy.profile?.leaf,
        particles = policy.parameters?.particles ?: policy.profile?.particles,
        simulations = policy.parameters?.simulations ?: policy.profile?.simulations,
        maxPolicyDecisions = policy.parameters?.maxPolicyDecisions ?: policy.profile?.maxPolicyDecisions,
        explorationConstant = policy.parameters?.explorationConstant ?: policy.profile?.explorationConstant,
        actionSpaceProfile = policy.parameters?.actionSpaceProfile ?: policy.profile?.actionSpaceProfile,
        beliefMode = policy.beliefMode.takeIf { policy.kind == ArenaPolicyKind.SEARCH },
        beliefArchitecture = policy.beliefArchitecture.takeIf { policy.kind == ArenaPolicyKind.SEARCH },
        searchPlanner = policy.searchPlanner.takeIf { policy.kind == ArenaPolicyKind.SEARCH },
        opponentPolicyId = "mono-red-mixture-70-10-10-10-v2".takeIf {
            policy.kind == ArenaPolicyKind.SEARCH
        },
        policyCompression = policy.policyCompression.takeIf { policy.kind == ArenaPolicyKind.SEARCH },
        searchReuse = policy.searchReuse.takeIf { policy.kind == ArenaPolicyKind.SEARCH },
    )

internal fun operationallyValidGame(game: GameRunResult): Boolean =
    game.disposition == GameRunDisposition.GAME_ENDED && game.evidenceStop == null &&
    game.terminal && !game.stepLimit && game.exception == null &&
    game.illegalResponses == 0 && game.fallbacks == 0 && game.informationLedgerComplete &&
    game.replayVerified && game.replayPath != null && game.replaySha256 != null &&
    game.seatDiagnostics.values.all { seat ->
        seat.searchDecisionsDetail.all { it.searchDiagnostics.rejectedTransitions == 0 }
    }

internal fun cleanupPolicyQualityWarnings(games: List<GameRunResult>): List<String> = buildList {
    val affected = games.count { it.cleanupDiscardEvents > 0 }
    if (affected > 0) {
        val events = games.sumOf(GameRunResult::cleanupDiscardEvents)
        add(
            "$events cleanup discard event(s) occurred in $affected/${games.size} games; " +
                "review opening-land counts and preceding searched land holds before interpreting policy quality"
        )
    }
}

internal fun summarizeMatchup(
    first: String,
    second: String,
    pairs: Int,
    games: List<GameRunResult>,
    pairIndices: List<Int> = (0 until pairs).toList(),
): TournamentMatchupReport {
    require(games.all { it.disposition == GameRunDisposition.GAME_ENDED && it.terminal && it.evidenceStop == null }) {
        "Tournament scoring requires an engine-ended game; stopped or legacy nonterminal inputs are refused"
    }
    fun score(game: GameRunResult): Double = when (game.winner) {
        null -> 0.5
        "p0" -> if (game.p0PolicyId == first) 1.0 else 0.0
        else -> if (game.p1PolicyId == first) 1.0 else 0.0
    }
    val scores = games.map(::score)
    return TournamentMatchupReport(
        firstPolicyId = first,
        secondPolicyId = second,
        pairCount = pairs,
        winsFirst = scores.count { it == 1.0 },
        draws = scores.count { it == 0.5 },
        winsSecond = scores.count { it == 0.0 },
        firstPointRate = scores.average(),
        games = games,
        pairIndices = pairIndices,
    )
}

internal data class RatingResult(val standings: List<TournamentStanding>, val startingPlayerRating: Double)

internal data class TournamentPairIndexBlock(
    val pairIndex: Int,
    val games: List<GameRunResult>,
)

/** Keeps all matchups assigned the same library-order block together for resampling. */
internal fun tournamentPairIndexBlocks(
    matchups: List<TournamentMatchupReport>,
): List<TournamentPairIndexBlock> {
    val indexedPairs = matchups.flatMap { matchup ->
        val pairs = matchup.games.chunked(2)
        require(pairs.all { it.size == 2 }) {
            "Every tournament resampling pair must contain both seat-swapped games"
        }
        require(matchup.pairIndices.size == pairs.size) {
            "Matchup ${matchup.firstPolicyId}/${matchup.secondPolicyId} has " +
                "${matchup.pairIndices.size} pair indices for ${pairs.size} pairs"
        }
        require(matchup.pairIndices.distinct().size == matchup.pairIndices.size) {
            "A matchup cannot repeat a pair-index seed block"
        }
        matchup.pairIndices.zip(pairs)
    }
    return indexedPairs.groupBy({ it.first }, { it.second })
        .toSortedMap()
        .map { (pairIndex, pairs) -> TournamentPairIndexBlock(pairIndex, pairs.flatten()) }
}

internal object BradleyTerry {
    private const val SCALE = 400.0 / 2.302585092994046
    private const val RIDGE = 1e-6
    private const val GRADIENT_TOLERANCE = 1e-8
    private const val STEP_TOLERANCE = 1e-10
    private const val MAX_ITERATIONS = 200
    private const val MAX_LINE_SEARCH_STEPS = 60

    fun fit(
        policyIds: List<String>,
        matchups: List<TournamentMatchupReport>,
        seed: Long,
        bootstrapSamples: Int = 10_000,
    ): RatingResult {
        require(bootstrapSamples >= 100)
        require(matchups.flatMap { it.games }.all {
            it.disposition == GameRunDisposition.GAME_ENDED && it.terminal && it.evidenceStop == null
        }) {
            "Bradley-Terry requires engine-ended games; a null winner is otherwise not a draw"
        }
        val heuristic = policyIds.indexOf("argentum-production-heuristic")
        require(heuristic >= 0)
        val observations = matchups.flatMap { it.games }.map { game ->
            Observation(policyIds.indexOf(game.p0PolicyId), policyIds.indexOf(game.p1PolicyId), when (game.winner) {
                "p0" -> 1.0
                "p1" -> 0.0
                else -> 0.5
            })
        }
        val coefficients = solve(policyIds.size, heuristic, observations)
        val strengths = strengths(policyIds.size, heuristic, coefficients)
        val samples = Array(policyIds.size) { DoubleArray(bootstrapSamples) }
        val random = Random(ComponentSeeds.derive(seed, "bradley-terry-bootstrap"))
        val pairIndexBlocks = tournamentPairIndexBlocks(matchups)
        require(pairIndexBlocks.isNotEmpty()) { "Bradley-Terry bootstrap requires a complete pair" }
        repeat(bootstrapSamples) { sampleIndex ->
            val sampled = List(pairIndexBlocks.size) {
                pairIndexBlocks[random.nextInt(pairIndexBlocks.size)].games
            }.flatten().map { game -> Observation(policyIds.indexOf(game.p0PolicyId), policyIds.indexOf(game.p1PolicyId), when (game.winner) {
                "p0" -> 1.0
                "p1" -> 0.0
                else -> 0.5
            }) }
            val fitted = strengths(policyIds.size, heuristic, solve(policyIds.size, heuristic, sampled))
            fitted.forEachIndexed { index, value -> samples[index][sampleIndex] = value * SCALE }
        }
        val raw = policyIds.mapIndexed { index, id ->
            val games = observations.filter { it.p0 == index || it.p1 == index }
            val points = games.sumOf { if (it.p0 == index) it.outcome else 1.0 - it.outcome }
            val wins = games.count { (it.p0 == index && it.outcome == 1.0) || (it.p1 == index && it.outcome == 0.0) }
            val draws = games.count { it.outcome == 0.5 }
            val sorted = samples[index].sortedArray()
            TournamentStanding(
                rank = 0,
                policyId = id,
                rating = strengths[index] * SCALE,
                confidenceLower = sorted[(bootstrapSamples * 0.025).toInt()],
                confidenceUpper = sorted[(bootstrapSamples * 0.975).toInt().coerceAtMost(bootstrapSamples - 1)],
                gamePointRate = points / games.size,
                wins = wins,
                draws = draws,
                losses = games.size - wins - draws,
            )
        }.sortedWith(compareByDescending<TournamentStanding> { it.rating }.thenByDescending { it.gamePointRate }.thenBy { it.policyId })
        return RatingResult(raw.mapIndexed { index, standing -> standing.copy(rank = index + 1) }, coefficients.last() * SCALE)
    }

    private data class Observation(val p0: Int, val p1: Int, val outcome: Double)

    private fun strengths(count: Int, anchor: Int, coefficients: DoubleArray): DoubleArray {
        val result = DoubleArray(count)
        var position = 0
        repeat(count) { index -> if (index != anchor) result[index] = coefficients[position++] }
        return result
    }

    private fun solve(count: Int, anchor: Int, observations: List<Observation>): DoubleArray {
        val dimension = count
        val beta = DoubleArray(dimension)
        fun feature(policy: Int): Int = if (policy == anchor) -1 else if (policy < anchor) policy else policy - 1
        fun features(observation: Observation): DoubleArray = DoubleArray(dimension).also { x ->
            feature(observation.p0).takeIf { it >= 0 }?.let { x[it] += 1.0 }
            feature(observation.p1).takeIf { it >= 0 }?.let { x[it] -= 1.0 }
            x[dimension - 1] = 1.0
        }
        val design = observations.map { it to features(it) }
        fun logistic(value: Double): Double = if (value >= 0.0) {
            1.0 / (1.0 + kotlin.math.exp(-value))
        } else {
            val exponential = kotlin.math.exp(value)
            exponential / (1.0 + exponential)
        }
        fun softplus(value: Double): Double =
            kotlin.math.max(value, 0.0) + kotlin.math.ln1p(kotlin.math.exp(-kotlin.math.abs(value)))
        fun objective(coefficients: DoubleArray): Double =
            design.sumOf { (observation, x) ->
                val linear = x.indices.sumOf { x[it] * coefficients[it] }
                observation.outcome * linear - softplus(linear)
            } - RIDGE * coefficients.sumOf { it * it } / 2.0

        repeat(MAX_ITERATIONS) {
            val gradient = DoubleArray(dimension) { -RIDGE * beta[it] }
            val information = Array(dimension) { row -> DoubleArray(dimension) { column -> if (row == column) RIDGE else 0.0 } }
            design.forEach { (observation, x) ->
                val linear = x.indices.sumOf { x[it] * beta[it] }
                val probability = logistic(linear)
                val weight = probability * (1.0 - probability)
                x.indices.forEach { row ->
                    gradient[row] += (observation.outcome - probability) * x[row]
                    x.indices.forEach { column -> information[row][column] += weight * x[row] * x[column] }
                }
            }
            if (gradient.maxOf { kotlin.math.abs(it) } < GRADIENT_TOLERANCE) return beta
            val delta = gaussianSolve(information, gradient)
            val currentObjective = objective(beta)
            val directionalDerivative = gradient.indices.sumOf { gradient[it] * delta[it] }
            require(directionalDerivative > 0.0 && directionalDerivative.isFinite()) {
                "Bradley-Terry Newton direction is not an ascent direction"
            }
            var scale = 1.0
            var candidate: DoubleArray? = null
            var lineSearchSteps = 0
            while (candidate == null && lineSearchSteps < MAX_LINE_SEARCH_STEPS) {
                val proposed = DoubleArray(dimension) { beta[it] + scale * delta[it] }
                if (objective(proposed) >= currentObjective + 1e-4 * scale * directionalDerivative) {
                    candidate = proposed
                } else {
                    scale /= 2.0
                }
                lineSearchSteps++
            }
            val accepted = requireNotNull(candidate) { "Bradley-Terry line search did not converge" }
            val maximumStep = accepted.indices.maxOf { kotlin.math.abs(accepted[it] - beta[it]) }
            accepted.copyInto(beta)
            if (maximumStep < STEP_TOLERANCE) return beta
        }
        error("Bradley-Terry fit did not converge")
    }

    private fun gaussianSolve(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
        val n = vector.size
        val augmented = Array(n) { row -> DoubleArray(n + 1) { column -> if (column == n) vector[row] else matrix[row][column] } }
        repeat(n) { column ->
            val pivot = (column until n).maxBy { kotlin.math.abs(augmented[it][column]) }
            val swap = augmented[column]; augmented[column] = augmented[pivot]; augmented[pivot] = swap
            require(kotlin.math.abs(augmented[column][column]) > 1e-14) { "Singular Bradley-Terry system" }
            for (row in column + 1 until n) {
                val factor = augmented[row][column] / augmented[column][column]
                for (entry in column..n) augmented[row][entry] -= factor * augmented[column][entry]
            }
        }
        val result = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var remainder = augmented[row][n]
            for (column in row + 1 until n) remainder -= augmented[row][column] * result[column]
            result[row] = remainder / augmented[row][row]
        }
        return result
    }
}

internal fun renderCoreSixTournament(report: CoreSixTournamentReport): String = buildString {
    appendLine("# Recorded seat-swapped games comparing six declared policies")
    appendLine()
    appendTournamentExecutionSummary(
        gameCount = report.gameCount,
        completePairs = report.completePairs,
        conditionsSatisfied = report.valid,
        failureCount = report.failureReasons.size,
        gamesWithCleanupDiscard = report.gamesWithCleanupDiscard,
    )
    appendLine()
    appendLine("## Experiment trace")
    appendLine()
    appendLine("- Procedure identifier: core-six pilot tournament")
    appendLine("- Benchmark: `${report.benchmarkPath}` (`${report.benchmarkSha256}`)")
    appendLine(
        "- Candidate construction deliberately omits selected redundant mana activations. The standings " +
            "therefore apply only to that reduced action set and do not describe play with every represented " +
            "standalone mana action available " +
            "(`${SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1.profileId}`)."
    )
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
    if (report.failureReasons.isNotEmpty()) {
        appendLine()
        appendLine("## Recorded conditions that prevented use")
        report.failureReasons.forEach { appendLine("- $it") }
    }
}
