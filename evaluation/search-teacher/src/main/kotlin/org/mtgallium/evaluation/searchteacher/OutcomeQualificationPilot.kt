package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.searchteacher.SEARCH_TEACHER_UNPROFILED_RUNTIME_ID
import org.mtgallium.agent.searchteacher.SearchTeacherRuntimeConfig
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints
import org.mtgallium.research.run.ResearchRunFiles

internal const val OUTCOME_QUALIFICATION_PILOT_PROTOCOL =
    "outcome-qualification-current-search-teacher-v1"
internal const val OUTCOME_QUALIFICATION_TREATMENT_ID = SEARCH_TEACHER_UNPROFILED_RUNTIME_ID
internal const val OUTCOME_QUALIFICATION_COMPARATOR_ID =
    "no-search-heuristic-profile-coupled-mono-red-fast-mana-pruned-v1"

/** The two exact policy specs for this prospective, single-matchup pilot. */
internal object OutcomeQualificationPilotRoster {
    val runtime: SearchTeacherRuntimeConfig = SearchTeacherRuntimeConfig()

    fun policies(): Pair<ArenaPolicySpec, ArenaPolicySpec> {
        val parameters = runtime.policyParameters()
        val treatment = ArenaPolicySpec(
            id = OUTCOME_QUALIFICATION_TREATMENT_ID,
            kind = ArenaPolicyKind.SEARCH,
            parameters = parameters,
        )
        val comparator = ArenaPolicySpec(
            id = OUTCOME_QUALIFICATION_COMPARATOR_ID,
            kind = ArenaPolicyKind.SEARCH,
            parameters = parameters,
            searchPlanner = SearchPlannerKind.NO_SEARCH_HEURISTIC,
        )
        return treatment to comparator
    }
}

@Serializable
internal data class OutcomeQualificationPair(
    val pairIndex: Int,
    val seed: Long,
    val games: List<GameRunResult>,
    val valid: Boolean,
    val invalidationReasons: List<String>,
    /** Search Teacher points across both legs; null if either leg is scientifically invalid. */
    val treatmentPoints: Double? = null,
)

@Serializable
internal data class OutcomeQualificationSeatSummary(
    val seat: String,
    val games: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val pointRate: Double?,
)

@Serializable
internal data class OutcomeQualificationOperationalSummary(
    val policyId: String,
    val games: Int,
    val validGames: Int,
    val invalidGames: Int,
    val searchDecisions: Int,
    val searchLatencyMillisTotal: Double,
    val searchLatencyMillisMean: Double?,
    val searchLatencyMillisP95: Double?,
    /** Whole game elapsed time is shared by both arms and is intentionally not assigned as per-arm cost. */
    val wholeGameElapsedMillis: Double,
)

@Serializable
internal data class OutcomeQualificationPilotReport(
    val schemaVersion: Int = 1,
    val protocol: String = OUTCOME_QUALIFICATION_PILOT_PROTOCOL,
    val runIdentity: String,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceProvenance: PolicySourceProvenance,
    val deckHash: String,
    val cardPoolHash: String,
    val baseSeed: Long,
    val assignedPairs: Int,
    val assignedGames: Int,
    val workerThreads: Int,
    val policies: List<TournamentPolicyDescription>,
    val policyEvidenceIdentities: Map<String, String>,
    val pairs: List<OutcomeQualificationPair>,
    val validPairs: Int,
    val validGames: Int,
    val incompletePairs: Int,
    val invalidPairs: Int,
    val treatmentBySeat: List<OutcomeQualificationSeatSummary>,
    val treatmentPointRate: Double?,
    val pairedBootstrap95Lower: Double?,
    val pairedBootstrap95Upper: Double?,
    val visibleSeatPointRateDifference: Double?,
    val operationalByPolicy: List<OutcomeQualificationOperationalSummary>,
    val inferenceScope: String,
    val limitation: String,
    val valid: Boolean,
    val failureReasons: List<String>,
)

internal class OutcomeQualificationPilotRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val baseSeed: Long,
) {
    private val evidence = EvidenceStore(root)

    fun run(pairCount: Int, workerThreads: Int, output: Path? = null): OutcomeQualificationPilotReport {
        require(pairCount > 0)
        require(workerThreads > 0)
        val (treatment, comparator) = OutcomeQualificationPilotRoster.policies()
        val sourceRun = RunProvenance.capture(root)
        sourceRun.requireReady()
        val sourceProvenance = requireNotNull(sourceRun.sourceProvenance)
        val arena = SearchTeacherArena(registry, manifest, SearchTeacherArena.smokeProfile(), baseSeed)
        val policyEvidenceIdentities = listOf(treatment, comparator).associate { policy ->
            policy.id to arena.evidenceBinding(policy, null, sourceProvenance).identity
        }.toSortedMap()
        val identity = ResearchRunBindings(
            protocol = OUTCOME_QUALIFICATION_PILOT_PROTOCOL,
            material = mapOf(
                "source-provenance" to sha256(evidenceJson.encodeToString(sourceProvenance)),
                "policy-evidence" to sha256(evidenceJson.encodeToString(policyEvidenceIdentities)),
                "deck" to manifest.deckHash(), "card-pool" to manifest.cardPoolHash(),
                "base-seed" to baseSeed.toString(), "pair-count" to pairCount.toString(),
            ),
        ).identity
        val directory = output ?: evidence.diagnostic(
            "outcome-qualification-pilot/${outcomeQualificationDirectoryKey(identity)}",
            "the outcome-qualification pilot output",
        )
        evidence.requireDiagnosticOutput(directory, "the outcome-qualification pilot output")
        val reportPath = directory.resolve("report.json")
        if (Files.isRegularFile(reportPath)) {
            val currentManifest = directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)
            if (Files.isRegularFile(currentManifest)) ResearchRunArtifacts.loadAndVerify(directory, identity)
            else ResearchRunArtifacts.verifyLegacyChecksums(directory)
            return evidenceJson.decodeFromString(Files.readString(reportPath))
        }
        val completedPairs = AtomicInteger(0)
        val progress = OutcomeQualificationDurableProgress(pairCount)
        progress.update(0, "preparing paired games")
        val pairs = parallelMapOrdered(pairCount, workerThreads) { pairIndex ->
            val seed = ComponentSeeds.derive(baseSeed, pairIndex, "outcome-qualification-library-orders")
            val checkpoint = directory.resolve("pairs/pair-$pairIndex.json")
            val games = loadPair(checkpoint, identity, pairIndex, seed).toMutableList()
            while (games.size < 2) {
                val leg = games.size
                val descriptor = tournamentDescriptor(treatment, comparator, pairIndex, leg)
                games += arena.playWithPolicies(
                    descriptor.gameId,
                    seed,
                    if (leg == 0) treatment else comparator,
                    if (leg == 0) comparator else treatment,
                    replay = replayOptions(identity, descriptor),
                )
                persistPair(checkpoint, identity, pairIndex, seed, games)
            }
            outcomeQualificationPair(pairIndex, seed, games, treatment.id).also {
                progress.update(completedPairs.incrementAndGet(), "completed pair $pairIndex")
            }
        }.sortedBy(OutcomeQualificationPair::pairIndex)
        val validPairs = pairs.filter(OutcomeQualificationPair::valid)
        val pairScores = validPairs.map { TournamentPairIndexScore(it.pairIndex, requireNotNull(it.treatmentPoints) / 2.0) }
        val interval = pairScores.takeIf { it.isNotEmpty() }?.let {
            pairIndexBootstrapInterval(it, ComponentSeeds.derive(baseSeed, "outcome-qualification-bootstrap"))
        }
        val allGames = pairs.flatMap(OutcomeQualificationPair::games)
        val report = OutcomeQualificationPilotReport(
            runIdentity = identity,
            generatedAtUtc = Instant.now().toString(),
            outerCommit = sourceRun.outerCommit,
            argentumCommit = sourceRun.checkedOutArgentumCommit,
            sourceProvenance = sourceProvenance,
            deckHash = manifest.deckHash(),
            cardPoolHash = manifest.cardPoolHash(),
            baseSeed = baseSeed,
            assignedPairs = pairCount,
            assignedGames = pairCount * 2,
            workerThreads = workerThreads,
            policies = listOf(treatment, comparator).map(::describeTournamentPolicy),
            policyEvidenceIdentities = policyEvidenceIdentities,
            pairs = pairs,
            validPairs = validPairs.size,
            validGames = validPairs.size * 2,
            incompletePairs = pairs.count { it.games.size != 2 },
            invalidPairs = pairs.count { !it.valid },
            treatmentBySeat = listOf("p0", "p1").map { outcomeQualificationSeat(it, validPairs, treatment.id) },
            treatmentPointRate = pairScores.takeIf { it.isNotEmpty() }?.map(TournamentPairIndexScore::value)?.average(),
            pairedBootstrap95Lower = interval?.first,
            pairedBootstrap95Upper = interval?.second,
            visibleSeatPointRateDifference = outcomeQualificationSeatDifference(validPairs, treatment.id),
            operationalByPolicy = listOf(
                outcomeQualificationOperational(treatment.id, allGames),
                outcomeQualificationOperational(comparator.id, allGames),
            ),
            inferenceScope = "Prospective 50-pair seat-swapped common-seed Mono-Red mirror comparison of the identified current Search Teacher composition with direct profile-coupled heuristic selection under mono-red-fast-mana-pruned-v1.",
            limitation = "NO_SEARCH_HEURISTIC removes belief construction, rollout-policy execution, and leaf evaluation with tree traversal. This comparison tests the complete current search composition against direct heuristic selection; it does not attribute any difference specifically to tree traversal.",
            valid = pairs.size == pairCount && pairs.all { it.games.size == 2 },
            failureReasons = buildList {
                if (pairs.size != pairCount) add("assigned pair materialization incomplete: ${pairs.size}/$pairCount")
                if (pairs.any { it.games.size != 2 }) add("one or more assigned pairs are incomplete")
            },
        )
        writeJsonAtomically(reportPath, report)
        writeTextAtomically(directory.resolve("report.md"), renderOutcomeQualificationPilot(report))
        ResearchRunArtifacts(directory, identity).also {
            it.register("report.json")
            it.register("report.md")
            it.finalize()
        }
        return report
    }

    private fun loadPair(path: Path, identity: String, pairIndex: Int, seed: Long): List<GameRunResult> =
        runCatching {
            ResearchRunCheckpoints.load(path).also {
                require(it.researchRunIdentity == identity && it.payloadSchema == OUTCOME_QUALIFICATION_CHECKPOINT_SCHEMA)
            }.payload().decodeToString().let { evidenceJson.decodeFromString<OutcomeQualificationCheckpoint>(it) }
        }
            .getOrNull()?.takeIf {
                it.pairIndex == pairIndex && it.seed == seed && it.games.size in 1..2
            }?.games ?: emptyList()

    private fun persistPair(path: Path, identity: String, pairIndex: Int, seed: Long, games: List<GameRunResult>) {
        ResearchRunCheckpoints.persist(
            path, identity, OUTCOME_QUALIFICATION_CHECKPOINT_SCHEMA, games.size.toLong(),
            evidenceJson.encodeToString(OutcomeQualificationCheckpoint(pairIndex, seed, games)).encodeToByteArray(),
        )
    }

    private fun replayOptions(identity: String, descriptor: TournamentGameDescriptor): GameReplayOptions {
        val path = evidence.diagnostic(
            "outcome-qualification-pilot/${outcomeQualificationDirectoryKey(identity)}/replays/${descriptor.gameId}.privileged.replay.jsonl.gz",
            "the outcome-qualification canonical replay",
        )
        return GameReplayOptions(path, root.relativize(path).toString(), identity)
    }
}

@Serializable
private data class OutcomeQualificationCheckpoint(
    val pairIndex: Int,
    val seed: Long,
    val games: List<GameRunResult>,
)

private const val OUTCOME_QUALIFICATION_CHECKPOINT_SCHEMA = "outcome-qualification-pair-v1"

private class OutcomeQualificationDurableProgress(private val total: Int) {
    private val path = System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of)

    fun update(completed: Int, detail: String) {
        publishDurableRunProgress(path, completed, total, "outcome qualification", detail)
    }
}

private fun outcomeQualificationPair(
    pairIndex: Int,
    seed: Long,
    games: List<GameRunResult>,
    treatmentId: String,
): OutcomeQualificationPair {
    val reasons = games.flatMap(::outcomeQualificationInvalidationReasons).distinct().sorted()
    val valid = games.size == 2 && reasons.isEmpty()
    return OutcomeQualificationPair(
        pairIndex, seed, games, valid,
        if (games.size == 2) reasons else reasons + "incomplete seat-swapped pair",
        treatmentPoints = if (valid) games.sumOf { outcomeQualificationScore(it, treatmentId) } else null,
    )
}

private fun outcomeQualificationInvalidationReasons(game: GameRunResult): List<String> = buildList {
    if (game.disposition != GameRunDisposition.GAME_ENDED) add("${game.gameId}: disposition=${game.disposition}")
    if (!game.terminal) add("${game.gameId}: non-terminal")
    if (game.evidenceStop != null) add("${game.gameId}: evidence-stop=${game.evidenceStop.triggerCodes.joinToString(",")}")
    if (game.stepLimit) add("${game.gameId}: step-limit")
    if (game.exception != null) add("${game.gameId}: exception=${game.exception}")
    if (game.illegalResponses != 0) add("${game.gameId}: illegal-responses=${game.illegalResponses}")
    if (game.fallbacks != 0) add("${game.gameId}: evidence-invalidating-fallbacks=${game.fallbacks}")
    if (!game.informationLedgerComplete) add("${game.gameId}: incomplete-information-ledger")
    if (!game.replayVerified) add("${game.gameId}: replay-not-verified")
}

private fun outcomeQualificationScore(game: GameRunResult, policyId: String): Double {
    val p0 = when (game.winner) { "p0" -> 1.0; "p1" -> 0.0; else -> 0.5 }
    return if (game.p0PolicyId == policyId) p0 else 1.0 - p0
}

private fun outcomeQualificationSeat(
    seat: String,
    pairs: List<OutcomeQualificationPair>,
    treatmentId: String,
): OutcomeQualificationSeatSummary {
    val games = pairs.flatMap(OutcomeQualificationPair::games).filter {
        (seat == "p0" && it.p0PolicyId == treatmentId) || (seat == "p1" && it.p1PolicyId == treatmentId)
    }
    val scores = games.map { outcomeQualificationScore(it, treatmentId) }
    return OutcomeQualificationSeatSummary(
        seat, games.size, scores.count { it == 1.0 }, scores.count { it == 0.5 }, scores.count { it == 0.0 },
        scores.takeIf { it.isNotEmpty() }?.average(),
    )
}

private fun outcomeQualificationSeatDifference(pairs: List<OutcomeQualificationPair>, treatmentId: String): Double? {
    val p0 = outcomeQualificationSeat("p0", pairs, treatmentId).pointRate
    val p1 = outcomeQualificationSeat("p1", pairs, treatmentId).pointRate
    return if (p0 == null || p1 == null) null else p0 - p1
}

private fun outcomeQualificationOperational(policyId: String, games: List<GameRunResult>): OutcomeQualificationOperationalSummary {
    val armGames = games.filter { it.p0PolicyId == policyId || it.p1PolicyId == policyId }
    val seatDiagnostics = armGames.mapNotNull { game ->
        game.seatDiagnostics.entries.singleOrNull { it.value.policyId == policyId }?.value
    }
    val latencies = seatDiagnostics.flatMap(ArenaSeatDiagnostics::searchLatenciesMillis)
    return OutcomeQualificationOperationalSummary(
        policyId, armGames.size, armGames.count(::operationallyValidGame),
        armGames.count { !operationallyValidGame(it) }, seatDiagnostics.sumOf(ArenaSeatDiagnostics::searchDecisions),
        latencies.sum(), latencies.takeIf { it.isNotEmpty() }?.average(), latencies.p95OrNull(),
        armGames.sumOf { it.elapsedMillis ?: 0.0 },
    )
}

private fun List<Double>.p95OrNull(): Double? = if (isEmpty()) null else percentile(this, 0.95)

internal fun renderOutcomeQualificationPilot(report: OutcomeQualificationPilotReport): String = buildString {
    appendLine("# Source-current Search Teacher outcome-qualification pilot")
    appendLine()
    appendLine("## Repository/source facts")
    appendLine()
    appendLine("- Outer source: `${report.outerCommit}`; Argentum: `${report.argentumCommit}`.")
    appendLine("- Deck hash: `${report.deckHash}`; card-pool hash: `${report.cardPoolHash}`.")
    appendLine("- Assigned population: ${report.assignedPairs} common-seed seat-swapped pairs (${report.assignedGames} games).")
    appendLine("- Treatment: `${OUTCOME_QUALIFICATION_TREATMENT_ID}`; comparator: `${OUTCOME_QUALIFICATION_COMPARATOR_ID}`.")
    appendLine()
    appendLine("## Measured pilot outcomes")
    appendLine()
    appendLine("- Scientifically valid pairs: ${report.validPairs}/${report.assignedPairs}; valid games: ${report.validGames}/${report.assignedGames}.")
    appendLine("- Invalid pairs: ${report.invalidPairs}; incomplete pairs: ${report.incompletePairs}.")
    val pointRate = report.treatmentPointRate?.let { "${"%.1f".format(it * 100)}%" } ?: "not estimable"
    val interval = if (report.pairedBootstrap95Lower != null) "${"%.1f".format(report.pairedBootstrap95Lower * 100)}–${"%.1f".format(report.pairedBootstrap95Upper!! * 100)}%" else "not estimable"
    appendLine("- Search Teacher paired game-point rate: $pointRate (paired bootstrap 95%: $interval).")
    report.treatmentBySeat.forEach { seat ->
        appendLine("- Search Teacher as ${seat.seat}: ${seat.wins}-${seat.draws}-${seat.losses}; point rate ${seat.pointRate?.let { "%.1f".format(it * 100) + "%" } ?: "not estimable"}.")
    }
    appendLine("- Visible p0 minus p1 point-rate difference: ${report.visibleSeatPointRateDifference?.let { "%.1f".format(it * 100) + " percentage points" } ?: "not estimable"}.")
    report.operationalByPolicy.forEach { arm ->
        appendLine("- `${arm.policyId}`: ${arm.searchDecisions} search decisions; mean/p95 search latency " +
            "${arm.searchLatencyMillisMean?.let { "%.1f".format(it) } ?: "n/a"}/${arm.searchLatencyMillisP95?.let { "%.1f".format(it) } ?: "n/a"} ms.")
    }
    appendLine()
    appendLine("## Inference and limitation")
    appendLine()
    appendLine(report.inferenceScope)
    appendLine()
    appendLine(report.limitation)
    if (report.invalidPairs > 0) {
        appendLine()
        appendLine("Invalid pairs are retained in `report.json` with each game and its exact invalidation reason; they are excluded from the paired strategic estimator rather than converted into outcomes.")
    }
}

private fun outcomeQualificationDirectoryKey(identity: String): String =
    identity.substringAfterLast(':').take(24)
