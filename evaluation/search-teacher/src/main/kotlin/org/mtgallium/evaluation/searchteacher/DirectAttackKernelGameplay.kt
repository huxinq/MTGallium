package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal enum class DirectAttackGameplayComparator { PLANNER, DIRECT_HEURISTIC }

/** One prospectively frozen comparison. The two comparator roles remain separate experiments. */
@Serializable
internal data class DirectAttackKernelGameplayPlan(
    val build: ResearchBuildReference,
    val screen: CloningComparisonInput,
    val screenManifestSha256: String,
    val comparator: DirectAttackGameplayComparator,
    val baseSeed: Long,
    val rule: PairedSequentialRule,
    val maximumWallSeconds: Int,
    val absoluteDeadlineEpochSeconds: Double,
    val workers: Int = 8,
) {
    init {
        require(screenManifestSha256.matches(Regex("[0-9a-f]{64}")))
        require(workers in 1..8 && maximumWallSeconds in 1..7200)
        require(absoluteDeadlineEpochSeconds.isFinite())
        require(rule.nullPointRate == .5 && rule.targetPointRate == .5 &&
            rule.practicalAcceptance == null && !rule.stopForFutility) {
            "Direct attack gameplay requires a prospective superiority test at parity"
        }
        require(rule.maximumPairs <= 256)
    }
}

internal fun directAttackGameplayCalibration(
    screen: DirectAttackKernelScreenPlan, plan: DirectAttackKernelGameplayPlan,
): SearchTeacherCalibrationPlan {
    val control = when (plan.comparator) {
        DirectAttackGameplayComparator.PLANNER -> screen.control.copy(id = "direct-attack-gameplay-planner")
        DirectAttackGameplayComparator.DIRECT_HEURISTIC -> screen.control.copy(
            id = "direct-attack-gameplay-heuristic", directAttackHeuristic = true)
    }
    val candidate = screen.control.copy(id = "direct-attack-gameplay-learned", directAttackKernelFit = screen.fit)
    return SearchTeacherCalibrationPlan(phase = SearchTeacherCalibrationPhase.CONFIRMATION,
        baseSeed = ComponentSeeds.derive(plan.baseSeed, "direct-attack-gameplay-v1", plan.comparator.name),
        pairOffset = 0, pairCount = plan.rule.maximumPairs, control = control, candidates = listOf(candidate))
}

@Serializable
internal enum class DirectAttackGameplayStop {
    TIME_LIMIT, INVALID_GAMEPLAY, TREATMENT_FAILURE, COST_DATA_FAILURE, COST_LIMIT,
}

/** A finalized failed or incomplete development screen never authorizes gameplay. */
internal fun requireDirectAttackGameplayScreen(screen: DirectAttackKernelScreenReport) {
    require(screen.disposition == "DEVELOPMENT_GATE_PASSED_GAMEPLAY_REQUIRED") {
        "Direct attack gameplay requires a passed development screen; retained disposition is ${screen.disposition}"
    }
    require(screen.rows.map { it.rootId }.sorted() == screen.plan.rootIds) {
        "Screen gate population differs from its frozen root IDs"
    }
    val gate = directAttackScreenGate(screen.rows)
    require(screen.gate == gate && gate.passed) { "Screen gate does not reproduce a development pass" }
}

/** Reserve the complete bounded opposite-comparator domain even when this plan has a smaller cap. */
internal fun directAttackGameplaySeedSchedule(
    plan: DirectAttackKernelGameplayPlan, calibration: SearchTeacherCalibrationPlan,
    excludedSeedGroups: Set<String>, seedGroup: (Long) -> String,
): List<Long> {
    val seeds = (0 until plan.rule.maximumPairs).map(calibration::pairSeed)
    require(seeds.distinct().size == seeds.size && seeds.none { seedGroup(it) in excludedSeedGroups }) {
        "Gameplay must use seed groups disjoint from the entire screen bank and fitting population"
    }
    val other = DirectAttackGameplayComparator.entries.single { it != plan.comparator }
    val otherBase = ComponentSeeds.derive(plan.baseSeed, "direct-attack-gameplay-v1", other.name)
    val otherSeeds = (0 until 256).map { calibrationPairSeed(otherBase, it) }.toSet()
    require(seeds.none { it in otherSeeds }) { "The declared comparator seed domains overlap" }
    return seeds
}

@Serializable
internal data class DirectAttackGameplayExposure(
    val learnedDirectSelections: Long,
    val controlDirectSelections: Long,
    val inspectedLearnedDirectSelections: Long,
    val inspectedControlDirectSelections: Long,
)

internal fun directAttackGameplayExposure(
    pairs: List<SearchBudgetFrontierPair>, inspectedPairs: Int, controlId: String, candidateId: String,
): DirectAttackGameplayExposure {
    require(inspectedPairs in 0..pairs.size && controlId != candidateId)
    fun count(population: List<SearchBudgetFrontierPair>, policyId: String) = population.sumOf { pair ->
        pair.games.sumOf { game -> game.seatDiagnostics.values.filter { it.policyId == policyId }.sumOf {
            (it.selectionCounts[SearchTeacherSelectionKind.DIRECT_POLICY_ACTION] ?: 0).toLong()
        } }
    }
    val inspected = pairs.take(inspectedPairs)
    return DirectAttackGameplayExposure(count(pairs, candidateId), count(pairs, controlId),
        count(inspected, candidateId), count(inspected, controlId))
}

internal fun directAttackGameplayResult(rule: PairedSequentialRule, scores: List<PairedSequentialScore>): PairedSequentialResult {
    val result = pairedSequentialTest(rule, scores, 0)
    return result.copy(confidenceSequence = pairedMeanConfidenceSequence(rule,
        scores.take(result.validScoredPairs).map { requireNotNull(it.pointRate) }))
}

internal fun directAttackGameplayStop(
    operationalValid: Boolean, treatmentIssues: List<String>, cost: DirectGameplayDecisionCost,
    deadlineReached: Boolean,
): DirectAttackGameplayStop? = when {
    !operationalValid -> DirectAttackGameplayStop.INVALID_GAMEPLAY
    treatmentIssues.isNotEmpty() -> DirectAttackGameplayStop.TREATMENT_FAILURE
    cost.issues.isNotEmpty() || cost.candidateControlRatio == null -> DirectAttackGameplayStop.COST_DATA_FAILURE
    !cost.costGatePassed -> DirectAttackGameplayStop.COST_LIMIT
    deadlineReached -> DirectAttackGameplayStop.TIME_LIMIT
    else -> null
}

internal fun directAttackGameplayPassed(
    result: PairedSequentialResult, stop: DirectAttackGameplayStop?, operationalValid: Boolean,
    treatmentIssues: List<String>, costGatePassed: Boolean, comparator: DirectAttackGameplayComparator,
    exposure: DirectAttackGameplayExposure,
): Boolean = stop == null && operationalValid && treatmentIssues.isEmpty() && costGatePassed &&
    exposure.inspectedLearnedDirectSelections > 0 &&
    (if (comparator == DirectAttackGameplayComparator.DIRECT_HEURISTIC) exposure.inspectedControlDirectSelections > 0
        else exposure.controlDirectSelections == 0L) &&
    result.disposition == PairedSequentialDisposition.ABOVE_NULL &&
    result.rule.nullPointRate == .5 && result.rule.targetPointRate == .5 &&
    result.confidenceSequence?.lower?.let { it > .5 } == true

@Serializable
internal data class DirectAttackKernelGameplayReport(
    val identity: String, val plan: DirectAttackKernelGameplayPlan, val source: ResearchRunProvenance,
    val screenSource: ResearchRunProvenance, val calibration: SearchTeacherCalibrationPlan,
    val chunks: List<ContinuationChunkBinding>, val scores: List<PairedSequentialScore>,
    val result: PairedSequentialResult, val population: SearchTeacherSequentialPopulation,
    val operationalValid: Boolean, val treatmentIssues: List<String>,
    val decisionCost: DirectGameplayDecisionCost, val directExposure: DirectAttackGameplayExposure,
    val stop: DirectAttackGameplayStop?, val comparisonStrengthAndCostGatePassed: Boolean,
    val interpretation: String = "One frozen direct attack fit versus the declared comparator, with unchanged search fallback and root/opponent rollouts. Strength uses the first inspected complete-pair prefix; accumulated decision computation per game uses all executed games, including invalid work and dispatched overshoot. The cost ceiling is 1.10 and missing measurements cannot pass. Learned direct selection must occur in the inspected strength prefix, as must the direct heuristic in its comparator test; overshoot-only exposure cannot qualify. A pass concerns this comparator only: attribution requires separate planner and same-role heuristic comparisons, and recursive learnability additionally requires a further learned-incumbent improvement. Per-test confidence assumes independent-seed common conditional pair means; no campaign-wide multiplicity or automatic promotion claim follows.",
)

internal fun directAttackGameplayBindings(
    plan: DirectAttackKernelGameplayPlan, source: ResearchRunProvenance, calibration: SearchTeacherCalibrationPlan,
) = ResearchRunBindings(protocol = "direct-attack-kernel-fresh-gameplay-v1", material = mapOf(
    "plan" to sha256(evidenceJson.encodeToString(DirectAttackKernelGameplayPlan.serializer(), plan)),
    "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
    "calibration" to sha256(evidenceJson.encodeToString(SearchTeacherCalibrationPlan.serializer(), calibration)),
))

internal class DirectAttackKernelGameplayRunner(private val root: Path) {
    fun run(plan: DirectAttackKernelGameplayPlan, output: Path, deckPath: Path): DirectAttackKernelGameplayReport {
        val source = ResearchRunProvenance.capture(root).also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        verifyResearchBuild(plan.build, source)
        val screenPath = Path.of(plan.screen.directory)
        require(researchSha256File(screenPath.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == plan.screenManifestSha256)
        ResearchRunArtifacts.loadAndVerify(screenPath, plan.screen.researchRunIdentity)
        val screen = readEvidenceJson(screenPath.resolve("report.json"), DirectAttackKernelScreenReport.serializer())
        require(screen.identity == plan.screen.researchRunIdentity)
        require(readEvidenceJson(screenPath.resolve("plan.json"), DirectAttackKernelScreenPlan.serializer()) == screen.plan)
        screen.source.requireReady()
        require(!screen.source.outerDirty && !screen.source.engineDirty)
        require(screen.source.checkedOutArgentumCommit == source.checkedOutArgentumCommit)
        requireDirectAttackGameplayScreen(screen)
        val bankPath = Path.of(screen.plan.bank.directory)
        val screenBindings = ResearchRunBindings(protocol = "direct-attack-kernel-screen-v1", material = mapOf(
            "plan" to sha256(evidenceJson.encodeToString(DirectAttackKernelScreenPlan.serializer(), screen.plan)),
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), screen.source)),
            "bank-manifest" to researchSha256File(bankPath.resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        ))
        require(screenBindings.identity == screen.identity)
        require(readEvidenceJson(screenPath.resolve("bindings.json"), ResearchRunBindings.serializer()) == screenBindings)
        val bank = loadVerifiedRealGamePositionBank(bankPath, screen.plan.bank.researchRunIdentity)
        screen.plan.fit.loadFrozenModel()
        val fitPath = Path.of(screen.plan.fit.directory)
        require(ResearchRunArtifacts.loadAndVerify(fitPath, screen.plan.fit.researchRunIdentity).artifacts.any {
            it.relativePath == "development.json"
        })
        val training = readEvidenceJson(fitPath.resolve("development.json"),
            kotlinx.serialization.builtins.ListSerializer(RootActionKernelTrainingRoot.serializer()))
        val manifest = loadDeckManifest(deckPath)
        require(bank.sources.all { it.deckHash == manifest.deckHash() && it.cardPoolHash == manifest.cardPoolHash() })
        val calibration = directAttackGameplayCalibration(screen.plan, plan)
        val oldGroups = bank.games.map { it.seedGroupId }.toSet() + training.map { it.seedGroupId }
        directAttackGameplaySeedSchedule(plan, calibration, oldGroups) {
            realGamePositionSeedGroup(manifest.deckHash(), manifest.cardPoolHash(), it)
        }
        val directory = EvidenceStore(root).requireDiagnosticOutput(output, "direct attack kernel fresh gameplay")
        require(!Files.exists(directory)) { "Inspect retained direct gameplay; never restart this test" }
        Files.createDirectories(directory)
        val bindings = directAttackGameplayBindings(plan, source, calibration)
        writeJsonAtomically(directory.resolve("plan.json"), plan)
        writeJsonAtomically(directory.resolve("bindings.json"), bindings)
        val deadline = minOf(plan.absoluteDeadlineEpochSeconds,
            System.currentTimeMillis() / 1000.0 + plan.maximumWallSeconds)
        val control = calibration.control
        val candidate = calibration.candidates.single()
        val pairs = mutableListOf<SearchBudgetFrontierPair>()
        val chunks = mutableListOf<ContinuationChunkBinding>()
        val issues = mutableListOf<String>()
        var stop: DirectAttackGameplayStop? = null
        fun scores() = pairs.map { PairedSequentialScore(it.pairIndex,
            if (it.valid) requireNotNull(it.treatmentPoints) / 2.0 else null, it.invalidationReasons) }
        val runner = SearchTeacherCalibrationRunner(root, buildRegistry(), manifest)
        while (true) {
            val range = continuationChunk(plan.rule, scores(), 0, plan.workers) ?: break
            if (System.currentTimeMillis() / 1000.0 >= deadline) { stop = DirectAttackGameplayStop.TIME_LIMIT; break }
            val relative = "chunks/pair-" + range.first
            val path = directory.resolve(relative)
            val chunkPlan = calibration.copy(pairOffset = range.first, pairCount = range.count())
            val chunk = runner.run(chunkPlan, path, plan.workers, publishProgress = false)
            verifyCompletedCalibration(path, chunk.runIdentity)
            require(chunk.plan == chunkPlan && chunk.workerThreads == plan.workers &&
                chunk.sourceProvenance == source.sourceProvenance)
            val newPairs = chunk.comparisons.single().pairs
            require(chunk.comparisons.single() == calibrationComparison(chunkPlan, candidate, newPairs))
            newPairs.flatMap { it.games }.forEach { game -> game.seatDiagnostics.values.forEach { seat ->
                val expected = chunk.policies.single { it.descriptor.id == seat.policyId }
                seat.searchDecisionsDetail.forEach { decision ->
                    val d = decision.searchDiagnostics
                    val label = game.gameId + ":" + decision.decisionIndex
                    if (d.freshSimulations != expected.descriptor.simulations || d.reusedSimulations != 0 ||
                        d.rootSelectionGuidance != null) issues += label + ": budget/reuse/guidance mismatch"
                    if (d.rootRolloutPolicyId != requireNotNull(expected.rootRolloutPolicy).declaredId ||
                        d.opponentRolloutPolicyId != requireNotNull(expected.opponentRolloutPolicy).declaredId)
                        issues += label + ": continuation policy mismatch"
                    try { requireValidScreenSearch(d) }
                    catch (e: IllegalArgumentException) { issues += label + ": " + e.message }
                }
            } }
            pairs += newPairs
            if (plan.comparator == DirectAttackGameplayComparator.PLANNER &&
                directAttackGameplayExposure(pairs, 0, control.id, candidate.id).controlDirectSelections != 0L)
                issues += "Planner comparator made a direct selection"
            chunks += ContinuationChunkBinding(relative, chunk.runIdentity,
                researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)), range.first, newPairs.size)
            val cost = directGameplayDecisionCost(pairs, control.id, candidate.id)
            val result = pairedSequentialTest(plan.rule, scores(), 0)
            writeJsonAtomically(directory.resolve("progress.json"), result)
            writeJsonAtomically(directory.resolve("decision-cost-progress.json"), cost)
            publishDurableRunProgress(System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of), pairs.size,
                plan.rule.maximumPairs, "direct attack gameplay",
                result.disposition.name + "; decision computation/game ratio=" + cost.candidateControlRatio)
            stop = directAttackGameplayStop(pairs.all { it.valid }, issues, cost,
                System.currentTimeMillis() / 1000.0 >= deadline)
            if (stop != null) break
        }
        val scores = scores()
        val result = directAttackGameplayResult(plan.rule, scores)
        val cost = directGameplayDecisionCost(pairs, control.id, candidate.id)
        val operationalValid = pairs.all { it.valid }
        val population = SearchTeacherSequentialPopulation(plan.rule.maximumPairs, pairs.size, result.inspectedPairs,
            plan.rule.maximumPairs - pairs.size, pairs.size - result.inspectedPairs,
            pairs.take(result.inspectedPairs).count { !it.valid }, pairs.drop(result.inspectedPairs).count { !it.valid })
        val exposure = directAttackGameplayExposure(pairs, result.inspectedPairs, control.id, candidate.id)
        val passed = directAttackGameplayPassed(result, stop, operationalValid, issues, cost.costGatePassed,
            plan.comparator, exposure)
        val report = DirectAttackKernelGameplayReport(bindings.identity, plan, source, screen.source, calibration,
            chunks, scores, result, population, operationalValid, issues, cost, exposure, stop, passed)
        writeJsonAtomically(directory.resolve("report.json"), report)
        writeTextAtomically(directory.resolve("report.md"),
            "# Direct attack gameplay: " + (stop?.name ?: result.disposition.name) + "\n" +
            "Run " + report.identity + "; source " + source.outerCommit + "; Argentum " + source.checkedOutArgentumCommit + ".\n" +
            "Comparator=" + plan.comparator + "; comparison strength/cost gate=" + passed +
            "; all-decision-computation-per-game ratio=" + cost.candidateControlRatio + ".\n" +
            "Executed/inspected/overshoot pairs=" + population.executedPairs + "/" + population.inspectedPairs +
            "/" + population.overshootPairs + "; missing or invalid cost issues=" + cost.issues.size + ".\n" +
            report.interpretation + "\n" +
            renderGameplayLengths(gameplayLengthObservations(pairs, candidate.id), result.inspectedPairs, 0))
        finalizeResearchWorkflowArtifacts(directory, bindings.identity)
        return report
    }
}
