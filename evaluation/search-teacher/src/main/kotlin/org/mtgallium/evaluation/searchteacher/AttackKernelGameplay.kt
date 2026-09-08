package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal data class AttackKernelGameplayPlan(
    val build: ResearchBuildReference,
    val study: CloningComparisonInput,
    val studyManifestSha256: String,
    val absoluteDeadlineEpochSeconds: Double,
    val baseSeed: Long = 2026090809153,
    val workers: Int = 8,
) {
    init {
        require(workers == 8 && baseSeed == 2026090809153L)
        require(absoluteDeadlineEpochSeconds.isFinite())
        require(studyManifestSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

internal fun attackGameplayRule() = PairedSequentialRule(nullPointRate = .5, targetPointRate = .5,
    falsePositiveRate = .025, falseNegativeRate = .025, maximumPairs = 256)

/** Accumulated player search time per executed game, including dispatched overshoot. */
internal fun attackGameplayCostRatio(costs: List<ContinuationPolicyCost>): Double? {
    require(costs.size == 2 && costs[0].policyId != costs[1].policyId)
    return costs[0].searchMillisPerGame?.takeIf { it > 0 }?.let { costs[1].searchMillisPerGame?.div(it) }
}

@Serializable
internal data class AttackKernelGameplayReport(
    val identity: String, val plan: AttackKernelGameplayPlan, val source: ResearchRunProvenance,
    val calibration: SearchTeacherCalibrationPlan, val chunks: List<ContinuationChunkBinding>,
    val scores: List<PairedSequentialScore>, val result: PairedSequentialResult,
    val operationalValid: Boolean, val treatmentIssues: List<String>,
    val costs: List<ContinuationPolicyCost>, val searchedTimePerGameRatio: Double?,
    val attackSelections: Long, val disposition: String, val strengthAndCostGatePassed: Boolean,
    val interpretation: String = "Fresh complete seat-swapped pairs test one frozen attack fit against its incumbent. The first sequential stopping prefix determines strength; all executed games, including overshoot, determine accumulated player search-time-per-game cost. Invalid or stopped work is never a game outcome. Per-test confidence assumes independent-seed common conditional pair means; no automatic promotion or campaign-wide multiplicity claim follows."
)

internal fun attackGameplayBindings(plan: AttackKernelGameplayPlan, source: ResearchRunProvenance,
    calibration: SearchTeacherCalibrationPlan): ResearchRunBindings =
    ResearchRunBindings(protocol = "attack-kernel-fresh-gameplay-v1", material = mapOf(
        "plan" to sha256(evidenceJson.encodeToString(AttackKernelGameplayPlan.serializer(), plan)),
        "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
        "calibration" to sha256(evidenceJson.encodeToString(SearchTeacherCalibrationPlan.serializer(), calibration))))

internal class AttackKernelGameplayRunner(private val root: Path) {
    fun run(plan: AttackKernelGameplayPlan, output: Path, deckPath: Path): AttackKernelGameplayReport {
        val source = ResearchRunProvenance.capture(root).also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        verifyResearchBuild(plan.build, source)
        val studyPath = Path.of(plan.study.directory)
        require(researchSha256File(studyPath.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == plan.studyManifestSha256)
        ResearchRunArtifacts.loadAndVerify(studyPath, plan.study.researchRunIdentity)
        val study = readEvidenceJson(studyPath.resolve("report.json"), AttackKernelLearningReport.serializer())
        require(study.researchRunIdentity == plan.study.researchRunIdentity)
        require(study.disposition == "VALIDATION_PASSED_GAMEPLAY_REQUIRED")
        val validation = requireNotNull(study.validationResult)
        require(validation == attackValidationResult(validation.rows) && validation.passed)
        require(study.source.checkedOutArgentumCommit == source.checkedOutArgentumCommit)
        val fit = requireNotNull(study.fit)
        fit.loadFrozenModel()
        val control = study.plan.control
        val candidate = control.copy(id = "fast16-attack-kernel-cdfce236", attackRootKernelRolloutFit = fit)
        require(candidate.copy(id = control.id, attackRootKernelRolloutFit = null) == control)
        val calibration = SearchTeacherCalibrationPlan(phase = SearchTeacherCalibrationPhase.CONFIRMATION,
            baseSeed = plan.baseSeed, pairOffset = 0, pairCount = 256, control = control, candidates = listOf(candidate))
        val manifest = loadDeckManifest(deckPath)
        val bank = loadVerifiedRealGamePositionBank(Path.of(study.plan.bank.directory), study.plan.bank.researchRunIdentity)
        requireAttackAllocation(bank)
        val oldGroups = bank.games.map { it.seedGroupId }.toSet()
        val newSeeds = (0 until 256).map(calibration::pairSeed)
        require(newSeeds.distinct().size == 256 && newSeeds.none { seed ->
            realGamePositionSeedGroup(manifest.deckHash(), manifest.cardPoolHash(), seed) in oldGroups })
        require(bank.sources.all { it.deckHash == manifest.deckHash() && it.cardPoolHash == manifest.cardPoolHash() })
        val directory = EvidenceStore(root).requireDiagnosticOutput(output, "attack kernel fresh gameplay")
        require(!Files.exists(directory)) { "Inspect retained gameplay; never restart this test" }
        Files.createDirectories(directory)
        val bindings = attackGameplayBindings(plan, source, calibration)
        writeJsonAtomically(directory.resolve("plan.json"), plan)
        writeJsonAtomically(directory.resolve("bindings.json"), bindings)
        val deadline = minOf(plan.absoluteDeadlineEpochSeconds, System.currentTimeMillis() / 1000.0 + 7200)
        val rule = attackGameplayRule()
        val scores = mutableListOf<PairedSequentialScore>()
        val chunks = mutableListOf<ContinuationChunkBinding>()
        val lengthRows = mutableListOf<GameplayLengthObservation>()
        val costs = listOf(control, candidate).map { ContinuationPolicyCost(it.id, 0, 0, 0.0) }.toMutableList()
        val issues = mutableListOf<String>()
        var valid = true
        var attackSelections = 0L
        var stop: String? = null
        val runner = SearchTeacherCalibrationRunner(root, buildRegistry(), manifest)
        while (true) {
            val range = continuationChunk(rule, scores, 0, plan.workers) ?: break
            if (System.currentTimeMillis() / 1000.0 >= deadline) { stop = "TIME_LIMIT"; break }
            val relative = "chunks/pair-${range.first}"
            val path = directory.resolve(relative)
            val chunkPlan = calibration.copy(pairOffset = range.first, pairCount = range.count())
            val report = runner.run(chunkPlan, path, plan.workers, publishProgress = false)
            verifyCompletedCalibration(path, report.runIdentity)
            require(report.plan == chunkPlan && report.workerThreads == plan.workers)
            require(report.sourceProvenance == source.sourceProvenance)
            val pairs = report.comparisons.single().pairs
            lengthRows += gameplayLengthObservations(pairs, candidate.id)
            require(report.comparisons.single() == calibrationComparison(chunkPlan, candidate, pairs))
            pairs.flatMap { it.games }.forEach { game -> game.seatDiagnostics.values.forEach { seat ->
                val expected = report.policies.single { it.descriptor.id == seat.policyId }
                seat.searchDecisionsDetail.forEach { decision ->
                    val d = decision.searchDiagnostics
                    val label = "${game.gameId}:${decision.decisionIndex}"
                    if (d.freshSimulations != 56 || d.reusedSimulations != 0 || d.rootSelectionGuidance != null)
                        issues += "$label: budget/reuse/guidance mismatch"
                    if (d.rootRolloutPolicyId != requireNotNull(expected.rootRolloutPolicy).declaredId ||
                        d.opponentRolloutPolicyId != requireNotNull(expected.opponentRolloutPolicy).declaredId)
                        issues += "$label: continuation policy mismatch"
                    try { requireValidScreenSearch(d) } catch (e: IllegalArgumentException) { issues += "$label: ${e.message}" }
                    if (seat.policyId == candidate.id) attackSelections +=
                        d.rootRolloutPolicyDecisions.selectedComponents["attack-kernel-complete-small-menu-v1"] ?: 0
                }
            } }
            valid = valid && report.valid
            scores += pairs.map { PairedSequentialScore(it.pairIndex,
                if (it.valid) requireNotNull(it.treatmentPoints) / 2.0 else null, it.invalidationReasons) }
            costs.indices.forEach { i ->
                val games = pairs.flatMap { it.games }
                val ds = games.flatMap { it.seatDiagnostics.values }.filter { it.policyId == costs[i].policyId }
                    .flatMap { it.searchDecisionsDetail }
                costs[i] = costs[i].copy(games = costs[i].games + games.size,
                    searchedDecisions = costs[i].searchedDecisions + ds.size,
                    searchMillis = costs[i].searchMillis + ds.sumOf { it.latencyMillis })
            }
            chunks += ContinuationChunkBinding(relative, report.runIdentity,
                researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)), range.first, pairs.size)
            val ratio = attackGameplayCostRatio(costs)
            writeJsonAtomically(directory.resolve("progress.json"), pairedSequentialTest(rule, scores, 0))
            publishDurableRunProgress(System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of), scores.size, 256,
                "attack gameplay", "${pairedSequentialTest(rule, scores, 0).disposition}; search/game ratio=$ratio")
            stop = when {
                !valid -> "INVALID_GAMEPLAY"
                issues.isNotEmpty() -> "TREATMENT_FAILURE"
                System.currentTimeMillis() / 1000.0 >= deadline -> "TIME_LIMIT"
                ratio == null || ratio > 1.10 -> "COST_LIMIT"
                else -> null
            }
            if (stop != null) break
        }
        val raw = pairedSequentialTest(rule, scores, 0)
        val result = raw.copy(confidenceSequence = pairedMeanConfidenceSequence(rule,
            scores.take(raw.validScoredPairs).map { requireNotNull(it.pointRate) }))
        val ratio = attackGameplayCostRatio(costs)
        val passed = stop == null && valid && issues.isEmpty() && attackSelections > 0 &&
            result.disposition == PairedSequentialDisposition.ABOVE_NULL &&
            requireNotNull(result.confidenceSequence).lower > .5 && ratio != null && ratio <= 1.10
        val report = AttackKernelGameplayReport(bindings.identity, plan, source, calibration, chunks, scores, result,
            valid, issues, costs, ratio, attackSelections, stop ?: result.disposition.name, passed)
        writeJsonAtomically(directory.resolve("report.json"), report)
        writeTextAtomically(directory.resolve("report.md"), "# Attack gameplay: ${report.disposition}\n" +
            "Run `${report.identity}`; source `${source.outerCommit}`; Argentum `${source.checkedOutArgentumCommit}`.\n" +
            "Strength/cost gate=${report.strengthAndCostGatePassed}; search-time-per-game ratio=${report.searchedTimePerGameRatio}.\n" +
            renderGameplayLengths(lengthRows, result.inspectedPairs, calibration.pairOffset))
        finalizeResearchWorkflowArtifacts(directory, bindings.identity)
        return report
    }
}
