package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.PLANNER_EVIDENCE_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.PlannerEvidenceSidecar
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints

internal const val SEARCH_BUDGET_FRONTIER_EXTENSION_PROTOCOL = "search-budget-frontier-extension-v1"
internal const val SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS = 100
internal const val SEARCH_BUDGET_FRONTIER_EXTENSION_START = 50
internal const val SEARCH_BUDGET_FRONTIER_EXTENSION_CHECKPOINT_SCHEMA = "search-budget-frontier-extension-pair-v1"
private const val SEARCH_BUDGET_FRONTIER_ORIGINAL_DIRECTORY =
    "search-budget-frontier/713a9feaaf2b83209a85a4e6"

@Serializable
internal enum class SearchBudgetFrontierExtensionDecision {
    CLEAR_STRENGTH_DEFICIT,
    BUDGET_SLACK_SUPPORTED,
    RESIDUAL_NEAR_PARITY_AMBIGUITY,
    EXTENSION_INVALID,
}

@Serializable
internal data class SearchBudgetFrontierPlannerArtifact(
    val gameId: String,
    val perspectivePlayerId: String,
    val publicTrajectoryReference: String,
    val plannerEvidenceReference: String,
    val plannerEvidenceSha256: String,
    val plannerEvidenceBytes: Long,
    val schemaVersion: Int = PLANNER_EVIDENCE_SCHEMA_CURRENT,
)

@Serializable
internal data class SearchBudgetFrontierExtensionPair(
    val pair: SearchBudgetFrontierPair,
    /** One p0-safe trajectory/sidecar per game; seat swapping balances policy coverage. */
    val plannerArtifacts: List<SearchBudgetFrontierPlannerArtifact>,
)

internal data class VerifiedSearchBudgetFrontierExtensionCheckpoint(
    val pair: SearchBudgetFrontierExtensionPair,
    val payloadSha256: String,
)

/**
 * Fail-closed reader for the retained extension's pair authority. Ordinary resume may elect to
 * treat a refusal as a cache miss; evidence derivations consume this strict form directly.
 */
internal fun readSearchBudgetFrontierExtensionCheckpoint(
    path: Path,
    expectedIdentity: String,
    expectedPairIndex: Int? = null,
    expectedSeed: Long? = null,
): VerifiedSearchBudgetFrontierExtensionCheckpoint {
    val envelope = ResearchRunCheckpoints.load(path)
    require(envelope.researchRunIdentity == expectedIdentity) {
        "Extension pair checkpoint belongs to ${envelope.researchRunIdentity}, not $expectedIdentity"
    }
    require(envelope.payloadSchema == SEARCH_BUDGET_FRONTIER_EXTENSION_CHECKPOINT_SCHEMA) {
        "Unexpected extension pair checkpoint schema ${envelope.payloadSchema}"
    }
    val pair = evidenceJson.decodeFromString<SearchBudgetFrontierExtensionPair>(
        envelope.payload().decodeToString(),
    )
    expectedPairIndex?.let { require(pair.pair.pairIndex == it) }
    expectedSeed?.let { require(pair.pair.seed == it) }
    require(pair.pair.games.size in 1..2)
    return VerifiedSearchBudgetFrontierExtensionCheckpoint(pair, envelope.payloadSha256)
}

@Serializable
internal data class SearchBudgetFrontierTrancheSummary(
    val assignedPairs: Int,
    val validPairs: Int,
    val validGames: Int,
    val invalidPairs: Int,
    val incompletePairs: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val treatmentPointRate: Double?,
    val pairedBootstrap95Lower: Double?,
    val pairedBootstrap95Upper: Double?,
    val treatmentBySeat: List<SearchBudgetFrontierSeatSummary>,
    val visibleSeatPointRateDifference: Double?,
)

@Serializable
internal data class SearchBudgetFrontierCumulativePair(
    val tranche: String,
    val pairIndex: Int,
    val valid: Boolean,
    val treatmentPoints: Double?,
    val invalidationReasons: List<String>,
)

@Serializable
internal data class SearchBudgetFrontierExtensionReport(
    val schemaVersion: Int = 1,
    val protocol: String = SEARCH_BUDGET_FRONTIER_EXTENSION_PROTOCOL,
    val extensionIdentity: String,
    val cumulativeIdentity: String,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceProvenance: PolicySourceProvenance,
    val originalPilotIdentity: String,
    val originalPilotReportReference: String,
    val originalPilotReportSha256: String,
    val extensionPairStart: Int,
    val extensionPairCount: Int,
    val workerThreads: Int,
    val policies: List<TournamentPolicyDescription>,
    val policyEvidenceIdentities: Map<String, String>,
    val original: SearchBudgetFrontierTrancheSummary,
    val extension: SearchBudgetFrontierTrancheSummary,
    val cumulative: SearchBudgetFrontierTrancheSummary,
    val extensionPairs: List<SearchBudgetFrontierExtensionPair>,
    val cumulativePairDistribution: List<SearchBudgetFrontierCumulativePair>,
    val extensionOperationalByPolicy: List<SearchBudgetFrontierOperationalSummary>,
    val cumulativeOperationalByPolicy: List<SearchBudgetFrontierOperationalSummary>,
    val extensionLatencyReduction: Double?,
    val cumulativeLatencyReduction: Double?,
    val plannerEvidenceCapability: String,
    val plannerArtifactsExpected: Int,
    val plannerArtifactsPresent: Int,
    val valid: Boolean,
    val failureReasons: List<String>,
    val decision: SearchBudgetFrontierExtensionDecision,
    val inference: String,
)

internal class SearchBudgetFrontierExtensionRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val baseSeed: Long,
) {
    private val evidence = EvidenceStore(root)

    fun run(pairStart: Int, pairCount: Int, workerThreads: Int, output: Path? = null): SearchBudgetFrontierExtensionReport {
        require(pairCount > 0 && workerThreads > 0)
        val originalDirectory = evidence.work(SEARCH_BUDGET_FRONTIER_ORIGINAL_DIRECTORY)
        val originalPath = originalDirectory.resolve("report.json")
        require(Files.isRegularFile(originalPath)) { "The original 50-pair budget-frontier report is required" }
        val original = evidenceJson.decodeFromString<SearchBudgetFrontierReport>(Files.readString(originalPath))
        ResearchRunArtifacts.loadAndVerify(originalDirectory, original.runIdentity)
        require(original.valid && original.assignedPairs == SEARCH_BUDGET_FRONTIER_REQUIRED_PAIRS) {
            "Original budget-frontier population is not the retained valid 50-pair pilot"
        }

        val (control, treatment) = SearchBudgetFrontierRoster.policies()
        val controlParameters = control.effectiveParameters(baseSeed)
        val treatmentParameters = treatment.effectiveParameters(baseSeed)
        require(treatmentParameters.copy(simulations = controlParameters.simulations) == controlParameters)
        require(original.policies == listOf(control, treatment).map(::describeTournamentPolicy)) {
            "Current extension treatment differs materially from the original pilot policy descriptions"
        }
        val originalSeeds = original.pairs.map(SearchBudgetFrontierPair::seed).toSet()
        val extensionIndices = (pairStart until pairStart + pairCount).toList()
        val extensionSeeds = extensionIndices.map { ComponentSeeds.derive(baseSeed, it, "search-budget-frontier-library-orders") }
        require(extensionSeeds.distinct().size == extensionSeeds.size && extensionSeeds.none(originalSeeds::contains)) {
            "Extension pair schedule overlaps the original 50-pair schedule"
        }

        val sourceRun = RunProvenance.capture(root).also { it.requireReady() }
        val sourceProvenance = requireNotNull(sourceRun.sourceProvenance)
        val arena = SearchTeacherArena(registry, manifest, SearchTeacherArena.smokeProfile(), baseSeed)
        val policyEvidenceIdentities = listOf(control, treatment).associate { policy ->
            policy.id to arena.evidenceBinding(policy, null, sourceProvenance).identity
        }.toSortedMap()
        val originalReportSha = sha256File(originalPath)
        val extensionIdentity = extensionBindings(
            sourceProvenance, policyEvidenceIdentities, manifest.deckHash(), manifest.cardPoolHash(), baseSeed,
            original.runIdentity, originalReportSha, pairStart, pairCount,
        ).identity
        val cumulativeIdentity = ResearchRunBindings(
            protocol = "search-budget-frontier-cumulative-v1",
            material = mapOf(
                "original-pilot" to original.runIdentity,
                "original-report" to originalReportSha,
                "extension" to extensionIdentity,
                "result-schema" to "search-budget-frontier-cumulative-v1",
            ),
        ).identity
        val directory = output ?: evidence.diagnostic(
            "search-budget-frontier-extension/${extensionDirectoryKey(extensionIdentity)}",
            "the final 8x64 versus 8x32 precision extension",
        )
        evidence.requireDiagnosticOutput(directory, "the final 8x64 versus 8x32 precision extension")
        val reportPath = directory.resolve("report.json")
        if (Files.isRegularFile(reportPath)) {
            ResearchRunArtifacts.loadAndVerify(directory, extensionIdentity)
            return evidenceJson.decodeFromString(Files.readString(reportPath))
        }

        val completed = AtomicInteger(0)
        val progress = SearchBudgetFrontierDurableProgress(pairCount)
        progress.update(0, "preparing final 100-pair precision extension")
        val extensionPairs = parallelMapOrdered(pairCount, workerThreads) { offset ->
            val pairIndex = pairStart + offset
            val seed = ComponentSeeds.derive(baseSeed, pairIndex, "search-budget-frontier-library-orders")
            val checkpoint = directory.resolve("pairs/pair-$pairIndex.json")
            val stored = loadExtensionPair(checkpoint, extensionIdentity, pairIndex, seed)
            val games = stored?.pair?.games?.toMutableList() ?: mutableListOf()
            while (games.size < 2) {
                val leg = games.size
                val descriptor = tournamentDescriptor(control, treatment, pairIndex, leg)
                games += arena.playWithPolicies(
                    descriptor.gameId, seed,
                    if (leg == 0) control else treatment,
                    if (leg == 0) treatment else control,
                    evidence = evidenceOptions(extensionIdentity, descriptor, sourceRun.outerCommit, sourceRun.checkedOutArgentumCommit, sourceProvenance),
                    replay = replayOptions(extensionIdentity, descriptor),
                )
                persistExtensionPair(checkpoint, extensionIdentity, pairIndex, seed, games, directory)
            }
            extensionPair(pairIndex, seed, games, directory).also {
                persistExtensionPair(checkpoint, extensionIdentity, pairIndex, seed, games, directory)
                progress.update(completed.incrementAndGet(), "completed pair $pairIndex")
            }
        }.sortedBy { it.pair.pairIndex }

        val originalSummary = originalSummary(original)
        val extensionPlain = extensionPairs.map(SearchBudgetFrontierExtensionPair::pair)
        val extensionSummary = trancheSummary(extensionPlain, null, baseSeed, "extension")
        val cumulativePairs = original.pairs + extensionPlain
        val cumulativeSummary = trancheSummary(cumulativePairs, null, baseSeed, "cumulative")
        val extensionOperations = operations(controlParameters, treatmentParameters, extensionPlain.flatMap(SearchBudgetFrontierPair::games))
        val cumulativeOperations = operations(controlParameters, treatmentParameters, cumulativePairs.flatMap(SearchBudgetFrontierPair::games))
        val extensionReduction = latencyReduction(extensionOperations)
        val cumulativeReduction = latencyReduction(cumulativeOperations)
        val plannerArtifactsExpected = extensionPairs.sumOf { it.pair.games.size }
        val plannerArtifactsPresent = extensionPairs.sumOf { it.plannerArtifacts.size }
        val valid = extensionPairs.size == pairCount && extensionPairs.all { it.pair.valid } &&
            plannerArtifactsPresent == plannerArtifactsExpected && cumulativePairs.size == original.pairs.size + pairCount
        val failures = buildList {
            if (extensionPairs.size != pairCount) add("extension materialized ${extensionPairs.size}/$pairCount pairs")
            extensionPairs.filterNot { it.pair.valid }.forEach { add("pair ${it.pair.pairIndex} invalid: ${it.pair.invalidationReasons.joinToString("; ")}") }
            if (plannerArtifactsPresent != plannerArtifactsExpected) add("planner sidecars $plannerArtifactsPresent/$plannerArtifactsExpected")
        }
        val decision = extensionDecision(valid, cumulativeSummary)
        val report = SearchBudgetFrontierExtensionReport(
            extensionIdentity = extensionIdentity,
            cumulativeIdentity = cumulativeIdentity,
            generatedAtUtc = Instant.now().toString(),
            outerCommit = sourceRun.outerCommit,
            argentumCommit = sourceRun.checkedOutArgentumCommit,
            sourceProvenance = sourceProvenance,
            originalPilotIdentity = original.runIdentity,
            originalPilotReportReference = root.relativize(originalPath).toString(),
            originalPilotReportSha256 = originalReportSha,
            extensionPairStart = pairStart,
            extensionPairCount = pairCount,
            workerThreads = workerThreads,
            policies = listOf(control, treatment).map(::describeTournamentPolicy),
            policyEvidenceIdentities = policyEvidenceIdentities,
            original = originalSummary,
            extension = extensionSummary,
            cumulative = cumulativeSummary,
            extensionPairs = extensionPairs,
            cumulativePairDistribution = original.pairs.map { cumulativePair("original", it) } + extensionPlain.map { cumulativePair("extension", it) },
            extensionOperationalByPolicy = extensionOperations,
            cumulativeOperationalByPolicy = cumulativeOperations,
            extensionLatencyReduction = extensionReduction,
            cumulativeLatencyReduction = cumulativeReduction,
            plannerEvidenceCapability = "Original 50-pair pilot has no planner sidecars; every extension game carries one p0-safe V1 sidecar. Seat swapping gives one p0 control and one p0 treatment game per pair.",
            plannerArtifactsExpected = plannerArtifactsExpected,
            plannerArtifactsPresent = plannerArtifactsPresent,
            valid = valid,
            failureReasons = failures,
            decision = decision,
            inference = extensionInference(decision),
        )
        writeJsonAtomically(reportPath, report)
        writeTextAtomically(directory.resolve("report.md"), renderExtension(report))
        ResearchRunArtifacts(directory, extensionIdentity).also { it.register("report.json"); it.register("report.md"); it.finalize() }
        progress.complete(decision.name)
        return report
    }

    private fun evidenceOptions(identity: String, descriptor: TournamentGameDescriptor, outer: String, argentum: String, source: PolicySourceProvenance): GameEvidenceOptions {
        val publicPath = evidence.diagnostic("search-budget-frontier-extension/${extensionDirectoryKey(identity)}/public/${descriptor.gameId}.p0.jsonl.gz", "the extension p0 safe trajectory")
        val plannerPath = evidence.diagnostic("search-budget-frontier-extension/${extensionDirectoryKey(identity)}/public/planner/${descriptor.gameId}.p0.planner.json.gz", "the extension p0 planner sidecar")
        return GameEvidenceOptions(publicTrajectory = publicPath, plannerEvidence = plannerPath, publicTrajectoryPerspective = "p0", publicTrajectoryReference = root.relativize(publicPath).toString(), researchRunIdentity = identity, outerCommit = outer, argentumCommit = argentum, profileHash = sha256("$SEARCH_BUDGET_FRONTIER_EXTENSION_PROTOCOL:${descriptor.firstPolicyId}:${descriptor.secondPolicyId}"), sourceProvenance = source)
    }

    private fun replayOptions(identity: String, descriptor: TournamentGameDescriptor): GameReplayOptions {
        val path = evidence.diagnostic("search-budget-frontier-extension/${extensionDirectoryKey(identity)}/replays/${descriptor.gameId}.privileged.replay.jsonl.gz", "the extension canonical replay")
        return GameReplayOptions(path, root.relativize(path).toString(), identity)
    }

    private fun extensionPair(pairIndex: Int, seed: Long, games: List<GameRunResult>, directory: Path): SearchBudgetFrontierExtensionPair {
        val artifacts = games.mapNotNull { plannerArtifact(it.gameId, directory) }
        val pair = searchBudgetFrontierPair(pairIndex, seed, games, SEARCH_BUDGET_FRONTIER_TREATMENT_ID)
        val sidecarReasons = buildList {
            games.forEach { game -> if (artifacts.none { it.gameId == game.gameId }) add("${game.gameId}: missing or invalid p0 planner sidecar") }
        }
        return SearchBudgetFrontierExtensionPair(pair.copy(valid = pair.valid && sidecarReasons.isEmpty(), invalidationReasons = (pair.invalidationReasons + sidecarReasons).distinct().sorted(), treatmentPoints = pair.treatmentPoints.takeIf { pair.valid && sidecarReasons.isEmpty() }), artifacts)
    }

    private fun plannerArtifact(gameId: String, directory: Path): SearchBudgetFrontierPlannerArtifact? = runCatching {
        val public = directory.resolve("public/$gameId.p0.jsonl.gz")
        val planner = directory.resolve("public/planner/$gameId.p0.planner.json.gz")
        val sidecar = PlannerEvidenceSidecar.readCompressed(planner)
        require(Files.isRegularFile(public) && sidecar.binding.gameId == gameId && sidecar.binding.safeTrajectorySha256 == sha256File(public))
        require(sidecar.binding.safeTrajectoryReference == root.relativize(public).toString() && sidecar.binding.actionSpaceProfile.profileId == "mono-red-fast-mana-pruned-v1")
        require(sidecar.decisions.all { it.actingPlayerId == "p0" })
        SearchBudgetFrontierPlannerArtifact(gameId, "p0", sidecar.binding.safeTrajectoryReference, root.relativize(planner).toString(), sha256File(planner), Files.size(planner))
    }.getOrNull()

    private fun loadExtensionPair(path: Path, identity: String, pairIndex: Int, seed: Long): SearchBudgetFrontierExtensionPair? = runCatching {
        readSearchBudgetFrontierExtensionCheckpoint(path, identity, pairIndex, seed).pair
    }.getOrNull()

    private fun persistExtensionPair(path: Path, identity: String, pairIndex: Int, seed: Long, games: List<GameRunResult>, directory: Path) {
        val pair = extensionPair(pairIndex, seed, games, directory)
        ResearchRunCheckpoints.persist(path, identity, SEARCH_BUDGET_FRONTIER_EXTENSION_CHECKPOINT_SCHEMA, games.size.toLong(), evidenceJson.encodeToString(pair).encodeToByteArray())
    }
}

private fun extensionBindings(source: PolicySourceProvenance, identities: Map<String, String>, deck: String, cardPool: String, baseSeed: Long, original: String, originalSha: String, start: Int, count: Int) = ResearchRunBindings(
    protocol = SEARCH_BUDGET_FRONTIER_EXTENSION_PROTOCOL,
    material = mapOf("source-provenance" to sha256(evidenceJson.encodeToString(source)), "policy-evidence" to sha256(evidenceJson.encodeToString<Map<String, String>>(identities.toSortedMap())), "deck" to deck, "card-pool" to cardPool, "base-seed" to baseSeed.toString(), "original-pilot" to original, "original-report" to originalSha, "pair-start" to start.toString(), "pair-count" to count.toString(), "schedule" to "search-budget-frontier-library-orders-v1", "planner-evidence-schema" to PLANNER_EVIDENCE_SCHEMA_CURRENT.toString())
)

private fun trancheSummary(pairs: List<SearchBudgetFrontierPair>, originalSeats: List<SearchBudgetFrontierSeatSummary>?, baseSeed: Long, name: String): SearchBudgetFrontierTrancheSummary {
    val valid = pairs.filter(SearchBudgetFrontierPair::valid)
    val scores = valid.map { TournamentPairIndexScore(it.pairIndex, requireNotNull(it.treatmentPoints) / 2.0) }
    val interval = scores.takeIf { it.isNotEmpty() }?.let { pairIndexBootstrapInterval(it, ComponentSeeds.derive(baseSeed, "search-budget-frontier-$name-bootstrap")) }
    val games = valid.flatMap(SearchBudgetFrontierPair::games)
    val treatmentScores = games.map { searchBudgetFrontierScore(it, SEARCH_BUDGET_FRONTIER_TREATMENT_ID) }
    val seats = originalSeats ?: listOf("p0", "p1").map { searchBudgetFrontierSeat(it, valid, SEARCH_BUDGET_FRONTIER_TREATMENT_ID) }
    return SearchBudgetFrontierTrancheSummary(pairs.size, valid.size, valid.size * 2, pairs.count { !it.valid }, pairs.count { it.games.size != 2 }, treatmentScores.count { it == 1.0 }, treatmentScores.count { it == .5 }, treatmentScores.count { it == 0.0 }, scores.map(TournamentPairIndexScore::value).takeIf { it.isNotEmpty() }?.average(), interval?.first, interval?.second, seats, searchBudgetFrontierSeatDifference(valid, SEARCH_BUDGET_FRONTIER_TREATMENT_ID))
}

private fun originalSummary(report: SearchBudgetFrontierReport) = SearchBudgetFrontierTrancheSummary(
    assignedPairs = report.assignedPairs,
    validPairs = report.validPairs,
    validGames = report.validGames,
    invalidPairs = report.invalidPairs,
    incompletePairs = report.incompletePairs,
    wins = report.treatmentBySeat.sumOf { it.wins },
    draws = report.treatmentBySeat.sumOf { it.draws },
    losses = report.treatmentBySeat.sumOf { it.losses },
    treatmentPointRate = report.treatmentPointRate,
    pairedBootstrap95Lower = report.pairedBootstrap95Lower,
    pairedBootstrap95Upper = report.pairedBootstrap95Upper,
    treatmentBySeat = report.treatmentBySeat,
    visibleSeatPointRateDifference = report.visibleSeatPointRateDifference,
)

private fun operations(control: SearchTeacherPolicyParameters, treatment: SearchTeacherPolicyParameters, games: List<GameRunResult>) = listOf(searchBudgetFrontierOperational(SEARCH_BUDGET_FRONTIER_CONTROL_ID, control, games), searchBudgetFrontierOperational(SEARCH_BUDGET_FRONTIER_TREATMENT_ID, treatment, games))
private fun latencyReduction(operations: List<SearchBudgetFrontierOperationalSummary>) = operations.first().latencyMeanMillis?.let { c -> operations.last().latencyMeanMillis?.let { (c - it) / c } }
private fun cumulativePair(tranche: String, pair: SearchBudgetFrontierPair) = SearchBudgetFrontierCumulativePair(tranche, pair.pairIndex, pair.valid, pair.treatmentPoints, pair.invalidationReasons)
internal fun extensionDecision(valid: Boolean, cumulative: SearchBudgetFrontierTrancheSummary): SearchBudgetFrontierExtensionDecision = when { !valid -> SearchBudgetFrontierExtensionDecision.EXTENSION_INVALID; cumulative.pairedBootstrap95Upper == null || cumulative.pairedBootstrap95Lower == null -> SearchBudgetFrontierExtensionDecision.EXTENSION_INVALID; cumulative.pairedBootstrap95Upper < .5 -> SearchBudgetFrontierExtensionDecision.CLEAR_STRENGTH_DEFICIT; cumulative.pairedBootstrap95Lower >= .5 -> SearchBudgetFrontierExtensionDecision.BUDGET_SLACK_SUPPORTED; else -> SearchBudgetFrontierExtensionDecision.RESIDUAL_NEAR_PARITY_AMBIGUITY }
private fun extensionInference(decision: SearchBudgetFrontierExtensionDecision) = when (decision) { SearchBudgetFrontierExtensionDecision.CLEAR_STRENGTH_DEFICIT -> "Cumulative paired evidence places 8x32 on the losing side of parity; stop unchanged-budget descent and return outcome-grounded learned leaf value to the Advisor/owner."; SearchBudgetFrontierExtensionDecision.BUDGET_SLACK_SUPPORTED -> "Cumulative evidence makes 8x32 credibly competitive at this decision scale without claiming formal equivalence; return a possible 8x16 budget point to the Advisor/owner."; SearchBudgetFrontierExtensionDecision.RESIDUAL_NEAR_PARITY_AMBIGUITY -> "8x32 is a demonstrated cheaper frontier point, but cumulative evidence remains compatible with modest loss and practical parity; do not add pairs automatically."; SearchBudgetFrontierExtensionDecision.EXTENSION_INVALID -> "The declared extension cannot support cumulative interpretation; repair only the evidence defect needed to reproduce it." }
internal fun renderExtension(report: SearchBudgetFrontierExtensionReport) = buildString {
    appendLine("# Final Search Teacher 8x64 versus 8x32 precision extension")
    appendLine(); appendLine("- Source `${report.outerCommit}`; Argentum `${report.argentumCommit}`.")
    appendLine("- Original identity `${report.originalPilotIdentity}`; extension `${report.extensionIdentity}`; cumulative `${report.cumulativeIdentity}`.")
    listOf("Original" to report.original, "Extension" to report.extension, "Cumulative" to report.cumulative).forEach { (name, s) -> appendLine("- $name: valid ${s.validPairs}/${s.assignedPairs} pairs; 8x32 ${s.wins}-${s.draws}-${s.losses}; points ${s.treatmentPointRate?.let { "%.1f".format(it * 100) } ?: "n/a"}%; bootstrap 95% ${s.pairedBootstrap95Lower?.let { "%.1f".format(it * 100) } ?: "n/a"}–${s.pairedBootstrap95Upper?.let { "%.1f".format(it * 100) } ?: "n/a"}%.") }
    appendLine("- Extension/cumulative 8x32 latency reduction: ${report.extensionLatencyReduction?.let { "%.1f".format(it * 100) } ?: "n/a"}%/${report.cumulativeLatencyReduction?.let { "%.1f".format(it * 100) } ?: "n/a"}%.")
    appendLine("- Planner evidence: ${report.plannerArtifactsPresent}/${report.plannerArtifactsExpected}. ${report.plannerEvidenceCapability}")
    appendLine("- Decision: `${report.decision}`. ${report.inference}")
    if (report.failureReasons.isNotEmpty()) report.failureReasons.forEach { appendLine("- $it") }
}
private fun extensionDirectoryKey(identity: String) = identity.substringAfterLast(':').take(24)
