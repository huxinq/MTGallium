package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.*

/** One explicit extension of an unchanged stopped process, not a new confirmation test. */
@Serializable
internal data class SearchTeacherContinuationPlan(
    val parentDirectory: String,
    val parentIdentity: String,
    val parentManifestSha256: String,
    val parentPrefixSha256: String,
    val expectedSourceCommit: String,
    val build: ResearchBuildReference,
    val sourceCompatibilityStatement: String,
    val totalPairCap: Int,
    val workerThreads: Int,
) {
    init {
        require(parentDirectory.isNotBlank() && parentIdentity.isNotBlank() && expectedSourceCommit.isNotBlank())
        require(listOf(parentManifestSha256, parentPrefixSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(sourceCompatibilityStatement.isNotBlank() && totalPairCap > 0 && workerThreads > 0)
    }
}

internal fun continuationRule(original: PairedSequentialRule, parentResult: PairedSequentialResult,
    prefix: List<PairedSequentialScore>, firstPairIndex: Int, totalPairCap: Int): PairedSequentialRule {
    require(totalPairCap > original.maximumPairs)
    require(parentResult.disposition in setOf(PairedSequentialDisposition.FUTILITY, PairedSequentialDisposition.BUDGET_EXHAUSTED))
    require(prefix.isNotEmpty() && prefix.all { it.pointRate != null } && parentResult.operationalOvershootPairs == 0)
    require(pairedSequentialTest(original, prefix, firstPairIndex) == parentResult)
    val rule = original.copy(maximumPairs = totalPairCap)
    val continued = pairedSequentialTest(rule, prefix, firstPairIndex)
    require(continued.disposition == PairedSequentialDisposition.CONTINUE)
    require(continued.orderedPrefixSha256 == parentResult.orderedPrefixSha256 &&
        continued.upperLogCapitals == parentResult.upperLogCapitals && continued.lowerLogCapitals == parentResult.lowerLogCapitals)
    return rule
}

/** The first cumulative stopping prefix wins; finish its dispatched batch only as overshoot. */
internal fun continuationChunk(rule: PairedSequentialRule, scores: List<PairedSequentialScore>, first: Int,
    workers: Int): IntRange? {
    require(workers > 0 && first >= 0 && first.toLong() + rule.maximumPairs <= Int.MAX_VALUE)
    if (pairedSequentialTest(rule, scores, first).disposition != PairedSequentialDisposition.CONTINUE) return null
    return (first + scores.size) until (first + scores.size + minOf(workers, rule.maximumPairs - scores.size))
}

@Serializable
internal data class ContinuationPolicyCost(val policyId: String, val games: Int, val searchedDecisions: Int,
    val searchMillis: Double) {
    init { require(games >= 0 && searchedDecisions >= 0 && searchMillis.isFinite() && searchMillis >= 0) }
    val meanSearchedDecisionMillis: Double? get() = searchMillis.takeIf { searchedDecisions > 0 }?.div(searchedDecisions)
    val searchMillisPerGame: Double? get() = searchMillis.takeIf { games > 0 }?.div(games)
}

@Serializable
internal data class ContinuationEpochCost(val label: String, val policies: List<ContinuationPolicyCost>,
    val meanSearchedDecisionCostRatio: Double?, val searchedTimePerGameRatio: Double?, val costGatePassed: Boolean)

internal fun continuationCosts(label: String, costs: List<ContinuationPolicyCost>): ContinuationEpochCost {
    require(costs.size == 2 && costs.map { it.policyId }.distinct().size == 2)
    val control = costs[0]; val candidate = costs[1]
    val ratio = control.meanSearchedDecisionMillis?.takeIf { it > 0 }?.let { c -> candidate.meanSearchedDecisionMillis?.div(c) }
    val gameRatio = control.searchMillisPerGame?.takeIf { it > 0 }?.let { c -> candidate.searchMillisPerGame?.div(c) }
    return ContinuationEpochCost(label, costs, ratio, gameRatio, ratio != null && ratio <= 1.0)
}

@Serializable
internal data class ContinuationChunkBinding(val directory: String, val identity: String,
    val manifestSha256: String, val pairOffset: Int, val executedPairs: Int)

@Serializable
internal data class SearchTeacherContinuationReport(
    val protocol: String = "search-teacher-optional-continuation-v1",
    val identity: String, val plan: SearchTeacherContinuationPlan,
    val source: PolicySourceProvenance, val parentSource: PolicySourceProvenance,
    val parentResult: PairedSequentialResult, val parentPairs: Int,
    val chunks: List<ContinuationChunkBinding>, val scores: List<PairedSequentialScore>,
    val result: PairedSequentialResult, val newExecutedPairs: Int,
    val newInspectedPairs: Int, val plannedUnexecutedPairs: Int,
    val operationalValid: Boolean, val treatmentIssues: List<String>,
    val costs: List<ContinuationEpochCost>,
    val strengthAndCostGatePassed: Boolean,
    val interpretation: String = "The unchanged cumulative betting process includes the authenticated parent prefix exactly once. The original stopped parent remains immutable. Per-process anytime error control assumes the original conditional-mean null relative to all information used to continue; this is not fresh confirmation, conditional-on-prefix error control, campaign multiplicity control or automatic promotion. Costs retain parent/new/combined execution populations including batch overshoot; the new and combined mean searched-decision cost gates must both pass. Source compatibility is an explicit reviewed statement, not proved by equal configuration hashes.",
)

internal class SearchTeacherContinuationRunner(private val root: Path, private val registry: CardRegistry,
    private val manifest: DeckManifest) {
    fun run(plan: SearchTeacherContinuationPlan, output: Path, preflightOnly: Boolean = false): SearchTeacherContinuationReport? {
        val sourceRun = RunProvenance.capture(root).also { it.requireReady() }
        require(!sourceRun.outerDirty && !sourceRun.engineDirty && sourceRun.outerCommit == plan.expectedSourceCommit)
        val source = requireNotNull(sourceRun.sourceProvenance)
        verifyResearchBuild(plan.build, ResearchRunProvenance.capture(root))
        val parentDirectory = Path.of(plan.parentDirectory).toAbsolutePath().normalize()
        require(sha256File(parentDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == plan.parentManifestSha256)
        val parent = loadCompletedSequentialCalibration(parentDirectory, plan.parentIdentity)
        require(parent.sourceProvenance.argentum == source.argentum)
        require(parent.deckHash == manifest.deckHash() && parent.cardPoolHash == manifest.cardPoolHash())
        val parentPairs = completedSequentialBankPairs(parent)
        val scores = parentPairs.map(::continuationScore).toMutableList()
        val parentResult = requireNotNull(parent.sequentialResult)
        require(parentResult.orderedPrefixSha256 == plan.parentPrefixSha256)
        val first = parent.plan.pairOffset
        val rule = continuationRule(requireNotNull(parent.sequentialRule), parentResult, scores, first, plan.totalPairCap)
        require(first.toLong() + plan.totalPairCap <= Int.MAX_VALUE)
        // Loading the original descriptors authenticates frozen fits before any child gameplay.
        val arena = SearchTeacherArena(registry, manifest, calibrationPresentationProfile(source), parent.plan.baseSeed)
        parent.policies.forEach { old ->
            val policy = old.descriptor.policy(parent.plan.baseSeed)
            val binding = arena.evidenceBinding(policy, null, source)
            require(binding.behaviorIdentity == old.binding.behaviorIdentity &&
                binding.behaviorSpecificationSha256 == old.binding.behaviorSpecificationSha256)
            require(old.search == old.descriptor.parameters(parent.plan.baseSeed).searchConfig())
            require(old.rootRolloutPolicy == policy.effectiveRootRolloutPolicy().behaviorSpecification &&
                old.opponentRolloutPolicy == policy.effectiveOpponentRolloutPolicy().behaviorSpecification)
        }
        val directory = EvidenceStore(root).requireDiagnosticOutput(output, "Search Teacher optional continuation")
        require(!directory.startsWith(parentDirectory) && !parentDirectory.startsWith(directory))
        val identity = ResearchRunBindings(protocol = "search-teacher-optional-continuation-v1", material = mapOf(
            "plan" to sha256(evidenceJson.encodeToString(plan)), "source" to sha256(evidenceJson.encodeToString(source)),
            "parent-rule" to sha256(evidenceJson.encodeToString(parentResult.rule)),
            "continuation-rule" to sha256(evidenceJson.encodeToString(rule)))).identity
        val finalPath = directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)
        val finalized = Files.exists(finalPath)
        if (finalized) ResearchRunArtifacts.loadAndVerify(directory, identity)
        val planPath = directory.resolve("plan.json")
        if (Files.exists(planPath)) require(evidenceJson.decodeFromString<SearchTeacherContinuationPlan>(Files.readString(planPath)) == plan)
        else writeJsonAtomically(planPath, plan)
        if (preflightOnly) return null
        val costs = parent.plan.let { p -> listOf(p.control.id, p.candidates.single().id) }
        val parentCosts = costs.map { costFor(it, parentPairs) }
        val newCosts = costs.map { ContinuationPolicyCost(it, 0, 0, 0.0) }.toMutableList()
        val chunks = mutableListOf<ContinuationChunkBinding>()
        val issues = mutableListOf<String>()
        var valid = true
        publishDurableRunProgress(System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of),
            0, rule.maximumPairs - parentPairs.size, "optional continuation", "parent verified; preparing new pairs")
        while (true) {
            val range = continuationChunk(rule, scores, first, plan.workerThreads) ?: break
            val relative = "chunks/pair-${range.first}"
            val chunkDirectory = directory.resolve(relative)
            // A finalized continuation is verification-only, never permission to fill missing work.
            if (finalized) require(Files.exists(chunkDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
            val chunkPlan = parent.plan.copy(pairOffset = range.first, pairCount = range.count())
            val report = SearchTeacherCalibrationRunner(root, registry, manifest).run(chunkPlan, chunkDirectory, plan.workerThreads, publishProgress = false)
            verifyCompletedCalibration(chunkDirectory, report.runIdentity)
            require(report.plan == chunkPlan && report.sourceProvenance == source && report.workerThreads == plan.workerThreads)
            val pairs = report.comparisons.single().pairs
            require(report.comparisons.single() == calibrationComparison(chunkPlan, chunkPlan.candidates.single(), pairs))
            require(report.policies.map { it.binding.behaviorSpecificationSha256 } == parent.policies.map { it.binding.behaviorSpecificationSha256 })
            pairs.forEach { pair -> pair.games.forEach { game -> game.seatDiagnostics.values.forEach { seat ->
                val descriptor = (listOf(chunkPlan.control) + chunkPlan.candidates).single { it.id == seat.policyId }
                seat.searchDecisionsDetail.forEach { decision ->
                    val d = decision.searchDiagnostics
                    val label = "${game.gameId}:${decision.decisionIndex}"
                    if (d.freshSimulations != descriptor.simulations || d.reusedSimulations != 0 || d.rootSelectionGuidance != null)
                        issues += "$label: budget/reuse/guidance mismatch"
                    val expected = report.policies.single { it.descriptor.id == seat.policyId }
                    if (d.rootRolloutPolicyId != expected.rootRolloutPolicy.declaredId || d.opponentRolloutPolicyId != expected.opponentRolloutPolicy.declaredId)
                        issues += "$label: continuation policy mismatch"
                    try { requireValidScreenSearch(d) } catch (e: IllegalArgumentException) { issues += "$label: ${e.message}" }
                }
            } } }
            valid = valid && report.valid
            scores += pairs.map(::continuationScore)
            newCosts.indices.forEach { i -> newCosts[i] = addCost(newCosts[i], costFor(costs[i], pairs)) }
            chunks += ContinuationChunkBinding(relative, report.runIdentity,
                sha256File(chunkDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)), range.first, pairs.size)
            val result = pairedSequentialTest(rule, scores, first)
            if (!finalized) writeJsonAtomically(directory.resolve("progress.json"), result)
            publishDurableRunProgress(System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of),
                scores.size - parentPairs.size, rule.maximumPairs - parentPairs.size, "optional continuation",
                "${scores.size} cumulative pairs; ${result.disposition}; treatment issues=${issues.size}")
            if (issues.isNotEmpty()) break
        }
        val chunkRoot = directory.resolve("chunks")
        if (Files.exists(chunkRoot)) Files.list(chunkRoot).use { paths ->
            require(paths.map { directory.relativize(it).toString() }.toList().toSet() == chunks.map { it.directory }.toSet())
        }
        val result = pairedSequentialTest(rule, scores, first)
        val epochs = listOf(continuationCosts("parent", parentCosts), continuationCosts("new", newCosts),
            continuationCosts("combined", parentCosts.indices.map { addCost(parentCosts[it], newCosts[it]) }))
        val report = SearchTeacherContinuationReport(identity = identity, plan = plan, source = source,
            parentSource = parent.sourceProvenance, parentResult = parentResult, parentPairs = parentPairs.size,
            chunks = chunks, scores = scores, result = result, newExecutedPairs = scores.size - parentPairs.size,
            newInspectedPairs = result.inspectedPairs - parentPairs.size, plannedUnexecutedPairs = rule.maximumPairs - scores.size,
            operationalValid = valid, treatmentIssues = issues, costs = epochs,
            strengthAndCostGatePassed = valid && issues.isEmpty() && result.disposition == PairedSequentialDisposition.ABOVE_NULL &&
                epochs.drop(1).all { it.costGatePassed })
        if (finalized) {
            require(readEvidenceJson(directory.resolve("report.json"), SearchTeacherContinuationReport.serializer()) == report)
        } else {
            writeJsonAtomically(directory.resolve("report.json"), report)
            ResearchRunArtifacts(directory, identity).also { artifacts ->
                listOf("plan.json", "report.json", "progress.json").forEach(artifacts::register)
                chunks.forEach { artifacts.register("${it.directory}/${ResearchRunArtifacts.MANIFEST_FILE}") }
                artifacts.finalize()
            }
        }
        return report
    }
}

private fun continuationScore(pair: SearchBudgetFrontierPair) = PairedSequentialScore(pair.pairIndex,
    if (pair.valid) requireNotNull(pair.treatmentPoints) / 2.0 else null, pair.invalidationReasons)

private fun costFor(policy: String, pairs: List<SearchBudgetFrontierPair>): ContinuationPolicyCost {
    val games = pairs.flatMap { it.games }
    val decisions = games.flatMap { it.seatDiagnostics.values }.filter { it.policyId == policy }.flatMap { it.searchDecisionsDetail }
    return ContinuationPolicyCost(policy, games.size, decisions.size, decisions.sumOf { it.latencyMillis })
}

private fun addCost(a: ContinuationPolicyCost, b: ContinuationPolicyCost): ContinuationPolicyCost {
    require(a.policyId == b.policyId)
    return a.copy(games = a.games + b.games, searchedDecisions = a.searchedDecisions + b.searchedDecisions,
        searchMillis = a.searchMillis + b.searchMillis)
}
