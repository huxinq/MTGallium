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
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherRuntimeConfig
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints
import org.mtgallium.research.run.ResearchRunFiles

internal const val SEARCH_BUDGET_FRONTIER_PROTOCOL = "search-budget-frontier-current-teacher-v1"
internal const val SEARCH_BUDGET_FRONTIER_CONTROL_ID =
    "$SEARCH_TEACHER_UNPROFILED_RUNTIME_ID-8x64-control"
internal const val SEARCH_BUDGET_FRONTIER_TREATMENT_ID =
    "$SEARCH_TEACHER_UNPROFILED_RUNTIME_ID-8x32-treatment"
internal const val SEARCH_BUDGET_FRONTIER_REQUIRED_PAIRS = 50
internal const val SEARCH_BUDGET_FRONTIER_MATERIAL_LATENCY_REDUCTION = 0.30

/** Exact current-search budget treatment. All behavior other than finite simulation count is bound equal. */
internal object SearchBudgetFrontierRoster {
    val runtime: SearchTeacherRuntimeConfig = SearchTeacherRuntimeConfig()

    fun policies(): Pair<ArenaPolicySpec, ArenaPolicySpec> {
        val controlParameters = runtime.policyParameters()
        val treatmentParameters = controlParameters.copy(simulations = 32)
        require(controlParameters.simulations == 64)
        require(treatmentParameters.simulations == 32)
        require(treatmentParameters.copy(simulations = controlParameters.simulations) == controlParameters) {
            "Search-budget frontier policies must differ only in simulation count"
        }
        val control = ArenaPolicySpec(
            id = SEARCH_BUDGET_FRONTIER_CONTROL_ID,
            kind = ArenaPolicyKind.SEARCH,
            parameters = controlParameters,
        )
        val treatment = ArenaPolicySpec(
            id = SEARCH_BUDGET_FRONTIER_TREATMENT_ID,
            kind = ArenaPolicyKind.SEARCH,
            parameters = treatmentParameters,
        )
        return control to treatment
    }
}

@Serializable
internal enum class SearchBudgetFrontierDecision {
    BUDGET_SLACK_SUPPORTED,
    STRENGTH_COST_TRADEOFF_SUPPORTED,
    STRENGTH_RESULT_AMBIGUOUS,
    COST_HYPOTHESIS_NOT_SUPPORTED,
    PILOT_INVALID,
}

@Serializable
internal data class SearchBudgetFrontierPair(
    val pairIndex: Int,
    val seed: Long,
    val games: List<GameRunResult>,
    val valid: Boolean,
    val invalidationReasons: List<String>,
    /** Treatment points across both seat-swapped legs; null for an invalid pair. */
    val treatmentPoints: Double? = null,
)

@Serializable
internal data class SearchBudgetFrontierSeatSummary(
    val seat: String,
    val games: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val pointRate: Double?,
)

/** Work counters are per searched decision unless explicitly named as totals. */
@Serializable
internal data class SearchBudgetFrontierOperationalSummary(
    val policyId: String,
    val configuredSimulations: Int,
    val games: Int,
    val validGames: Int,
    val invalidGames: Int,
    val searchedDecisions: Int,
    val latencyMeanMillis: Double?,
    val latencyP50Millis: Double?,
    val latencyP95Millis: Double?,
    val actualSimulationsTotal: Int,
    val simulatedWorldStepsTotal: Int,
    val rolloutDecisionsTotal: Int,
    val policyAnnotatedExpansionsTotal: Int,
    val transitionCacheHitsTotal: Int,
    val transitionCacheMissesTotal: Int,
    val evaluatorCallsTotal: Int,
    val wholeGameElapsedMillis: Double,
)

@Serializable
internal data class SearchBudgetFrontierReport(
    val schemaVersion: Int = 1,
    val protocol: String = SEARCH_BUDGET_FRONTIER_PROTOCOL,
    val runIdentity: String,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceProvenance: PolicySourceProvenance,
    val deckHash: String,
    val cardPoolHash: String,
    val baseSeed: Long,
    /** Operational metadata only; it is intentionally absent from [runIdentity]. */
    val workerThreads: Int,
    val assignedPairs: Int,
    val assignedGames: Int,
    val policies: List<TournamentPolicyDescription>,
    val policyEvidenceIdentities: Map<String, String>,
    val pairs: List<SearchBudgetFrontierPair>,
    val validPairs: Int,
    val validGames: Int,
    val invalidPairs: Int,
    val incompletePairs: Int,
    val treatmentBySeat: List<SearchBudgetFrontierSeatSummary>,
    val treatmentPointRate: Double?,
    val pairedBootstrap95Lower: Double?,
    val pairedBootstrap95Upper: Double?,
    val visibleSeatPointRateDifference: Double?,
    val operationalByPolicy: List<SearchBudgetFrontierOperationalSummary>,
    /** `(64-simulation mean - 32-simulation mean) / 64-simulation mean`. */
    val treatmentLatencyReduction: Double?,
    val decision: SearchBudgetFrontierDecision,
    val inference: String,
    val limitations: List<String>,
    val valid: Boolean,
    val failureReasons: List<String>,
)

internal fun searchBudgetFrontierDecision(
    valid: Boolean,
    treatmentLatencyReduction: Double?,
    treatmentPointRate: Double?,
    pairedBootstrap95Upper: Double?,
): SearchBudgetFrontierDecision = when {
    !valid -> SearchBudgetFrontierDecision.PILOT_INVALID
    treatmentLatencyReduction == null || treatmentLatencyReduction < SEARCH_BUDGET_FRONTIER_MATERIAL_LATENCY_REDUCTION ->
        SearchBudgetFrontierDecision.COST_HYPOTHESIS_NOT_SUPPORTED
    treatmentPointRate == null || pairedBootstrap95Upper == null ->
        SearchBudgetFrontierDecision.PILOT_INVALID
    pairedBootstrap95Upper < 0.5 -> SearchBudgetFrontierDecision.STRENGTH_COST_TRADEOFF_SUPPORTED
    treatmentPointRate >= 0.5 -> SearchBudgetFrontierDecision.BUDGET_SLACK_SUPPORTED
    else -> SearchBudgetFrontierDecision.STRENGTH_RESULT_AMBIGUOUS
}

internal class SearchBudgetFrontierRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val baseSeed: Long,
) {
    private val evidence = EvidenceStore(root)

    fun run(pairCount: Int, workerThreads: Int, output: Path? = null): SearchBudgetFrontierReport {
        require(pairCount > 0)
        require(workerThreads > 0)
        val (control, treatment) = SearchBudgetFrontierRoster.policies()
        val controlParameters = control.effectiveParameters(baseSeed)
        val treatmentParameters = treatment.effectiveParameters(baseSeed)
        require(treatmentParameters.copy(simulations = controlParameters.simulations) == controlParameters)
        val sourceRun = RunProvenance.capture(root)
        sourceRun.requireReady()
        val sourceProvenance = requireNotNull(sourceRun.sourceProvenance)
        val arena = SearchTeacherArena(registry, manifest, SearchTeacherArena.smokeProfile(), baseSeed)
        val policyEvidenceIdentities = listOf(control, treatment).associate { policy ->
            policy.id to arena.evidenceBinding(policy, null, sourceProvenance).identity
        }.toSortedMap()
        val identity = searchBudgetFrontierBindings(
            sourceProvenance = sourceProvenance,
            policyEvidenceIdentities = policyEvidenceIdentities,
            deckHash = manifest.deckHash(),
            cardPoolHash = manifest.cardPoolHash(),
            baseSeed = baseSeed,
            pairCount = pairCount,
        ).identity
        val directory = output ?: evidence.diagnostic(
            "search-budget-frontier/${searchBudgetFrontierDirectoryKey(identity)}",
            "the paired Search Teacher search-budget frontier output",
        )
        evidence.requireDiagnosticOutput(directory, "the paired Search Teacher search-budget frontier output")
        val reportPath = directory.resolve("report.json")
        if (Files.isRegularFile(reportPath)) {
            ResearchRunArtifacts.loadAndVerify(directory, identity)
            return evidenceJson.decodeFromString(Files.readString(reportPath))
        }
        val completedPairs = AtomicInteger(0)
        val progress = SearchBudgetFrontierDurableProgress(pairCount)
        progress.update(0, "preparing 8x64 versus 8x32 seat-swapped pairs")
        val pairs = parallelMapOrdered(pairCount, workerThreads) { pairIndex ->
            val seed = ComponentSeeds.derive(baseSeed, pairIndex, "search-budget-frontier-library-orders")
            val checkpoint = directory.resolve("pairs/pair-$pairIndex.json")
            val games = loadPair(checkpoint, identity, pairIndex, seed).toMutableList()
            while (games.size < 2) {
                val leg = games.size
                val descriptor = tournamentDescriptor(control, treatment, pairIndex, leg)
                games += arena.playWithPolicies(
                    descriptor.gameId,
                    seed,
                    if (leg == 0) control else treatment,
                    if (leg == 0) treatment else control,
                    replay = replayOptions(identity, descriptor),
                )
                persistPair(checkpoint, identity, pairIndex, seed, games)
            }
            searchBudgetFrontierPair(pairIndex, seed, games, treatment.id).also {
                progress.update(completedPairs.incrementAndGet(), "completed pair $pairIndex")
            }
        }.sortedBy(SearchBudgetFrontierPair::pairIndex)
        val validPairs = pairs.filter(SearchBudgetFrontierPair::valid)
        val pairScores = validPairs.map {
            TournamentPairIndexScore(it.pairIndex, requireNotNull(it.treatmentPoints) / 2.0)
        }
        val interval = pairScores.takeIf { it.isNotEmpty() }?.let {
            pairIndexBootstrapInterval(it, ComponentSeeds.derive(baseSeed, "search-budget-frontier-bootstrap"))
        }
        val allGames = pairs.flatMap(SearchBudgetFrontierPair::games)
        val operations = listOf(
            searchBudgetFrontierOperational(control.id, controlParameters, allGames),
            searchBudgetFrontierOperational(treatment.id, treatmentParameters, allGames),
        )
        val treatmentLatencyReduction = operations.first().latencyMeanMillis?.let { controlLatency ->
            operations.last().latencyMeanMillis?.let { treatmentLatency ->
                (controlLatency - treatmentLatency) / controlLatency
            }
        }
        val valid = pairs.size == pairCount && pairs.all(SearchBudgetFrontierPair::valid)
        val treatmentPointRate = pairScores.takeIf { it.isNotEmpty() }?.map(TournamentPairIndexScore::value)?.average()
        val decision = searchBudgetFrontierDecision(
            valid = valid,
            treatmentLatencyReduction = treatmentLatencyReduction,
            treatmentPointRate = treatmentPointRate,
            pairedBootstrap95Upper = interval?.second,
        )
        val report = SearchBudgetFrontierReport(
            runIdentity = identity,
            generatedAtUtc = Instant.now().toString(),
            outerCommit = sourceRun.outerCommit,
            argentumCommit = sourceRun.checkedOutArgentumCommit,
            sourceProvenance = sourceProvenance,
            deckHash = manifest.deckHash(),
            cardPoolHash = manifest.cardPoolHash(),
            baseSeed = baseSeed,
            workerThreads = workerThreads,
            assignedPairs = pairCount,
            assignedGames = pairCount * 2,
            policies = listOf(control, treatment).map(::describeTournamentPolicy),
            policyEvidenceIdentities = policyEvidenceIdentities,
            pairs = pairs,
            validPairs = validPairs.size,
            validGames = validPairs.size * 2,
            invalidPairs = pairs.count { !it.valid },
            incompletePairs = pairs.count { it.games.size != 2 },
            treatmentBySeat = listOf("p0", "p1").map {
                searchBudgetFrontierSeat(it, validPairs, treatment.id)
            },
            treatmentPointRate = treatmentPointRate,
            pairedBootstrap95Lower = interval?.first,
            pairedBootstrap95Upper = interval?.second,
            visibleSeatPointRateDifference = searchBudgetFrontierSeatDifference(validPairs, treatment.id),
            operationalByPolicy = operations,
            treatmentLatencyReduction = treatmentLatencyReduction,
            decision = decision,
            inference = searchBudgetFrontierInference(decision),
            limitations = listOf(
                "This is a simulation-budget experiment, not a learning experiment.",
                "Both arms preserve current Search Teacher semantics apart from finite simulation budget.",
                "A strength result whose interval crosses parity is not proof of equivalence.",
                "A positive 8x32 result does not determine the budget for any future teacher corpus; offline generation and inference budgets remain separate decisions.",
            ),
            valid = valid,
            failureReasons = buildList {
                if (pairs.size != pairCount) add("assigned pair materialization incomplete: ${pairs.size}/$pairCount")
                pairs.filterNot(SearchBudgetFrontierPair::valid).forEach { pair ->
                    add("pair ${pair.pairIndex} invalid: ${pair.invalidationReasons.joinToString("; ")}")
                }
            },
        )
        writeJsonAtomically(reportPath, report)
        writeTextAtomically(directory.resolve("report.md"), renderSearchBudgetFrontier(report))
        ResearchRunArtifacts(directory, identity).also {
            it.register("report.json")
            it.register("report.md")
            it.finalize()
        }
        progress.complete(decision.name)
        return report
    }

    private fun loadPair(path: Path, identity: String, pairIndex: Int, seed: Long): List<GameRunResult> =
        runCatching {
            ResearchRunCheckpoints.load(path).also {
                require(it.researchRunIdentity == identity && it.payloadSchema == SEARCH_BUDGET_FRONTIER_CHECKPOINT_SCHEMA)
            }.payload().decodeToString().let { evidenceJson.decodeFromString<SearchBudgetFrontierCheckpoint>(it) }
        }.getOrNull()?.takeIf { it.pairIndex == pairIndex && it.seed == seed && it.games.size in 1..2 }?.games
            ?: emptyList()

    private fun persistPair(path: Path, identity: String, pairIndex: Int, seed: Long, games: List<GameRunResult>) {
        ResearchRunCheckpoints.persist(
            path, identity, SEARCH_BUDGET_FRONTIER_CHECKPOINT_SCHEMA, games.size.toLong(),
            evidenceJson.encodeToString(SearchBudgetFrontierCheckpoint(pairIndex, seed, games)).encodeToByteArray(),
        )
    }

    private fun replayOptions(identity: String, descriptor: TournamentGameDescriptor): GameReplayOptions {
        val path = evidence.diagnostic(
            "search-budget-frontier/${searchBudgetFrontierDirectoryKey(identity)}/replays/${descriptor.gameId}.privileged.replay.jsonl.gz",
            "the search-budget frontier canonical replay",
        )
        return GameReplayOptions(path, root.relativize(path).toString(), identity)
    }
}

internal fun searchBudgetFrontierBindings(
    sourceProvenance: PolicySourceProvenance,
    policyEvidenceIdentities: Map<String, String>,
    deckHash: String,
    cardPoolHash: String,
    baseSeed: Long,
    pairCount: Int,
): ResearchRunBindings = ResearchRunBindings(
    protocol = SEARCH_BUDGET_FRONTIER_PROTOCOL,
    material = mapOf(
        "source-provenance" to sha256(evidenceJson.encodeToString(sourceProvenance)),
        "policy-evidence" to sha256(evidenceJson.encodeToString<Map<String, String>>(
            policyEvidenceIdentities.toSortedMap(),
        )),
        "deck" to deckHash,
        "card-pool" to cardPoolHash,
        "base-seed" to baseSeed.toString(),
        "pair-count" to pairCount.toString(),
    ),
)

@Serializable
private data class SearchBudgetFrontierCheckpoint(
    val pairIndex: Int,
    val seed: Long,
    val games: List<GameRunResult>,
)

private const val SEARCH_BUDGET_FRONTIER_CHECKPOINT_SCHEMA = "search-budget-frontier-pair-v1"

internal class SearchBudgetFrontierDurableProgress(private val total: Int) {
    private val path = System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of)

    fun update(completed: Int, detail: String) = publish(completed, "search budget frontier", detail)

    fun complete(detail: String) = publish(total, "complete", detail)

    private fun publish(completed: Int, phase: String, detail: String) {
        publishDurableRunProgress(path, completed, total, phase, detail)
    }
}

internal fun searchBudgetFrontierPair(
    pairIndex: Int,
    seed: Long,
    games: List<GameRunResult>,
    treatmentId: String,
): SearchBudgetFrontierPair {
    val reasons = games.flatMap(::searchBudgetFrontierInvalidationReasons).distinct().sorted()
    val valid = games.size == 2 && reasons.isEmpty()
    return SearchBudgetFrontierPair(
        pairIndex = pairIndex,
        seed = seed,
        games = games,
        valid = valid,
        invalidationReasons = if (games.size == 2) reasons else reasons + "incomplete seat-swapped pair",
        treatmentPoints = if (valid) games.sumOf { searchBudgetFrontierScore(it, treatmentId) } else null,
    )
}

internal fun searchBudgetFrontierInvalidationReasons(game: GameRunResult): List<String> = buildList {
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

internal fun searchBudgetFrontierScore(game: GameRunResult, policyId: String): Double {
    val p0 = when (game.winner) { "p0" -> 1.0; "p1" -> 0.0; else -> 0.5 }
    return if (game.p0PolicyId == policyId) p0 else 1.0 - p0
}

internal fun searchBudgetFrontierSeat(
    seat: String,
    pairs: List<SearchBudgetFrontierPair>,
    treatmentId: String,
): SearchBudgetFrontierSeatSummary {
    val games = pairs.flatMap(SearchBudgetFrontierPair::games).filter {
        (seat == "p0" && it.p0PolicyId == treatmentId) || (seat == "p1" && it.p1PolicyId == treatmentId)
    }
    val scores = games.map { searchBudgetFrontierScore(it, treatmentId) }
    return SearchBudgetFrontierSeatSummary(
        seat = seat,
        games = games.size,
        wins = scores.count { it == 1.0 },
        draws = scores.count { it == 0.5 },
        losses = scores.count { it == 0.0 },
        pointRate = scores.takeIf { it.isNotEmpty() }?.average(),
    )
}

internal fun searchBudgetFrontierSeatDifference(
    pairs: List<SearchBudgetFrontierPair>,
    treatmentId: String,
): Double? {
    val p0 = searchBudgetFrontierSeat("p0", pairs, treatmentId).pointRate
    val p1 = searchBudgetFrontierSeat("p1", pairs, treatmentId).pointRate
    return if (p0 == null || p1 == null) null else p0 - p1
}

internal fun searchBudgetFrontierOperational(
    policyId: String,
    parameters: SearchTeacherPolicyParameters,
    games: List<GameRunResult>,
): SearchBudgetFrontierOperationalSummary {
    val armGames = games.filter { it.p0PolicyId == policyId || it.p1PolicyId == policyId }
    val seats = armGames.mapNotNull { game ->
        game.seatDiagnostics.entries.singleOrNull { it.value.policyId == policyId }?.value
    }
    val details = seats.flatMap(ArenaSeatDiagnostics::searchDecisionsDetail)
    val latencies = details.map(ArenaSearchDecisionDiagnostic::latencyMillis)
    return SearchBudgetFrontierOperationalSummary(
        policyId = policyId,
        configuredSimulations = parameters.simulations,
        games = armGames.size,
        validGames = armGames.count { searchBudgetFrontierInvalidationReasons(it).isEmpty() },
        invalidGames = armGames.count { searchBudgetFrontierInvalidationReasons(it).isNotEmpty() },
        searchedDecisions = details.size,
        latencyMeanMillis = latencies.takeIf { it.isNotEmpty() }?.average(),
        latencyP50Millis = latencies.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) },
        latencyP95Millis = latencies.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
        actualSimulationsTotal = details.sumOf { it.searchDiagnostics.simulations },
        simulatedWorldStepsTotal = details.sumOf { it.searchDiagnostics.searchWorldSteps },
        rolloutDecisionsTotal = details.sumOf {
            it.searchDiagnostics.rootRolloutDecisions + it.searchDiagnostics.opponentRolloutDecisions
        },
        policyAnnotatedExpansionsTotal = details.sumOf { it.searchDiagnostics.policyAnnotatedExpansions },
        transitionCacheHitsTotal = details.sumOf { it.searchDiagnostics.transitionCacheHits },
        transitionCacheMissesTotal = details.sumOf { it.searchDiagnostics.transitionCacheMisses },
        evaluatorCallsTotal = details.sumOf { it.searchDiagnostics.evaluatorCalls },
        wholeGameElapsedMillis = armGames.sumOf { it.elapsedMillis ?: 0.0 },
    )
}

private fun searchBudgetFrontierInference(decision: SearchBudgetFrontierDecision): String = when (decision) {
    SearchBudgetFrontierDecision.BUDGET_SLACK_SUPPORTED ->
        "The 8x32 treatment met the predeclared 30% latency reduction threshold and did not show a clear deficit at this pilot scale. Consider one further unchanged-budget halving before introducing learning."
    SearchBudgetFrontierDecision.STRENGTH_COST_TRADEOFF_SUPPORTED ->
        "The 8x32 treatment met the cost threshold but its paired interval lay wholly below parity. Learned perspective-safe outcome value is the leading next intervention because it could replace expensive rollout continuation while preserving tree correction."
    SearchBudgetFrontierDecision.STRENGTH_RESULT_AMBIGUOUS ->
        "The 8x32 treatment met the cost threshold, but this pilot cannot distinguish practical parity from a meaningful loss. Return the next precision-versus-learning choice to the owner; do not add games automatically."
    SearchBudgetFrontierDecision.COST_HYPOTHESIS_NOT_SUPPORTED ->
        "The 8x32 treatment did not meet the predeclared 30% latency reduction threshold. Re-evaluate the controllable cost mechanism before selecting a learner."
    SearchBudgetFrontierDecision.PILOT_INVALID ->
        "The paired strategic estimator is invalid. Repair only the evidence or experiment defect needed to rerun this same comparison."
}

internal fun renderSearchBudgetFrontier(report: SearchBudgetFrontierReport): String = buildString {
    appendLine("# Current Search Teacher 8x64 versus 8x32 budget frontier")
    appendLine()
    appendLine("## Repository and treatment facts")
    appendLine()
    appendLine("- Outer source: `${report.outerCommit}`; Argentum: `${report.argentumCommit}`.")
    appendLine("- Deck hash: `${report.deckHash}`; card-pool hash: `${report.cardPoolHash}`.")
    appendLine("- Assigned population: ${report.assignedPairs} common-seed seat-swapped pairs (${report.assignedGames} games); workers: ${report.workerThreads} (operational metadata only).")
    report.policies.forEach { policy ->
        appendLine("- `${policy.id}`: ${policy.particles} particles × ${policy.simulations} simulations × depth ${policy.maxPolicyDecisions}; leaf `${policy.leaf}`; action profile `${policy.actionSpaceProfile?.profileId}`.")
    }
    appendLine("- The control/treatment binding is identical apart from simulations: 64 versus 32.")
    appendLine()
    appendLine("## Population and paired strength")
    appendLine()
    appendLine("- Valid pairs: ${report.validPairs}/${report.assignedPairs}; valid games: ${report.validGames}/${report.assignedGames}; invalid pairs: ${report.invalidPairs}; incomplete pairs: ${report.incompletePairs}.")
    val pointRate = report.treatmentPointRate?.let { "%.1f".format(it * 100) + "%" } ?: "not estimable"
    val interval = if (report.pairedBootstrap95Lower != null) {
        "${"%.1f".format(report.pairedBootstrap95Lower * 100)}–${"%.1f".format(report.pairedBootstrap95Upper!! * 100)}%"
    } else "not estimable"
    appendLine("- 8x32 result: paired game points $pointRate (paired bootstrap 95%: $interval).")
    report.treatmentBySeat.forEach { seat ->
        appendLine("- 8x32 as ${seat.seat}: ${seat.wins}-${seat.draws}-${seat.losses}; point rate ${seat.pointRate?.let { "%.1f".format(it * 100) + "%" } ?: "not estimable"}.")
    }
    appendLine("- Visible p0 minus p1 8x32 point-rate difference: ${report.visibleSeatPointRateDifference?.let { "%.1f".format(it * 100) + " percentage points" } ?: "not estimable"}.")
    appendLine("- Pair-level scores and every assigned game remain in `report.json`; invalid pairs are excluded from the paired estimator, never converted to outcomes.")
    appendLine()
    appendLine("## Measured search cost")
    appendLine()
    report.operationalByPolicy.forEach { arm ->
        appendLine("- `${arm.policyId}`: ${arm.searchedDecisions} searched decisions; latency mean/p50/p95 ${arm.latencyMeanMillis?.let { "%.1f".format(it) } ?: "n/a"}/${arm.latencyP50Millis?.let { "%.1f".format(it) } ?: "n/a"}/${arm.latencyP95Millis?.let { "%.1f".format(it) } ?: "n/a"} ms; actual simulations ${arm.actualSimulationsTotal}; steps ${arm.simulatedWorldStepsTotal}; rollout decisions ${arm.rolloutDecisionsTotal}; policy-annotated expansions ${arm.policyAnnotatedExpansionsTotal}; transition-cache hits/misses ${arm.transitionCacheHitsTotal}/${arm.transitionCacheMissesTotal}; evaluator calls ${arm.evaluatorCallsTotal}.")
    }
    appendLine("- 8x32 mean-latency reduction versus 8x64: ${report.treatmentLatencyReduction?.let { "%.1f".format(it * 100) + "%" } ?: "not estimable"}; predeclared material threshold: ${"%.0f".format(SEARCH_BUDGET_FRONTIER_MATERIAL_LATENCY_REDUCTION * 100)}%.")
    appendLine()
    appendLine("## Failures, inference, and limitations")
    appendLine()
    if (report.failureReasons.isEmpty()) appendLine("- No invalid pairs, failures, fallbacks, exclusions, or incomplete pairs.")
    else report.failureReasons.forEach { appendLine("- $it") }
    appendLine("- Decision: `${report.decision}`.")
    appendLine("- ${report.inference}")
    report.limitations.forEach { appendLine("- $it") }
}

private fun searchBudgetFrontierDirectoryKey(identity: String): String =
    identity.substringAfterLast(':').take(24)
