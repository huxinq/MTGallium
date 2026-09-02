package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val ISSUE_0029_PROTOCOL = "issue-0029-fixed-v3-neural-n256-boundary-v1"
private const val ISSUE_0029_REFERENCE_ARTIFACT =
    "neural-population-scaling-diagnostic/artifact.json"
private const val ISSUE_0029_EXPECTED_REFERENCE_SHA256 =
    "6b282c677ff226ca897dfdf9fdd899df722024001d875027797ca425e1e50081"
private const val ISSUE_0029_PREFIX = 256

@Serializable
internal data class Issue0029NeuralStabilityBoundaryReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0029_PROTOCOL,
    val generatedAtUtc: String,
    val implementationSourceProvenance: PolicySourceProvenance,
    val historicalSourceProvenance: PolicySourceProvenance,
    val historicalCandidateSchemaVersion: Int,
    val featureSchema: String,
    val corpusManifestPath: String,
    val corpusManifestSha256: String,
    val corpusDatasetIdentity: String,
    val splitPath: String,
    val splitSha256: String,
    val splitIdentity: String,
    val trainingDecisions: Int,
    val subsetSelectionProtocol: String,
    val subsetSelectionOrderSha256: String,
    val fixedPrefixDecisions: Int,
    val fixedPrefixIdentity: String,
    val referenceArtifactPath: String,
    val referenceArtifactSha256: String,
    val referenceProtocol: String,
    val referenceGeneratedAtUtc: String,
    val modelConfig: NeuralBcModelConfig,
    val optimizer: String,
    val initializationSeeds: List<Long>,
    val maximumEpochs: Int,
    val perfectConfirmationEpochs: Int,
    val traceEpochs: List<Int>,
    val executionMode: String,
    val updateExposureDefinition: List<String>,
    val saturationAndCollapseDefinitions: List<String>,
    val stages: List<NeuralPopulationScalingStageResult>,
    val diagnosticCase: String,
    val allSeedStabilityInterval: String,
    val interpretation: List<String>,
    val limitations: List<String>,
    val narrowestNextQuestion: String,
)

internal class Issue0029NeuralStabilityBoundaryDiagnostic(
    private val root: Path,
    private val outputDirectory: Path,
) {
    fun run(
        historicalManifestPath: Path,
        progress: (String) -> Unit = {},
    ): Issue0029NeuralStabilityBoundaryReport {
        Files.createDirectories(outputDirectory)
        val prepared = CorrectedNeuralDiagnosticPreparation(root).prepare(historicalManifestPath)
        val implementation = prepared.implementationSourceProvenance
        val population = prepared.population
        val ordered = prepared.rawOrderedDecisions
        val orderSha = prepared.subsetSelectionOrderSha256
        val modelConfig = prepared.modelConfig
        progress("Recovered the exact repaired issue-0022 training population and fixed nested order")

        val referencePath = EvidenceStore(root).latest(ISSUE_0029_REFERENCE_ARTIFACT)
        val referenceSha = sha256File(referencePath)
        require(referenceSha == ISSUE_0029_EXPECTED_REFERENCE_SHA256) {
            "Issue-0028 reference artifact changed: $referenceSha"
        }
        val reference = evidenceJson.decodeFromString<Issue0028NeuralPopulationScalingReport>(
            Files.readString(referencePath)
        )
        require(reference.protocol == ISSUE_0028_PROTOCOL)
        require(reference.corpusManifestSha256 == population.manifestSha256)
        require(reference.splitSha256 == population.splitSha256)
        require(reference.subsetSelectionProtocol == CORRECTED_NEURAL_SUBSET_PROTOCOL)
        require(reference.subsetSelectionOrderSha256 == orderSha)
        require(reference.trainingDecisions == ordered.size)
        require(reference.historicalCandidateSchemaVersion == CANDIDATE_SCHEMA_V3)
        require(reference.featureSchema == NEURAL_BC_FEATURE_SCHEMA)
        require(reference.modelConfig == modelConfig)
        require(reference.initializationSeeds == CORRECTED_NEURAL_SEEDS)
        require(reference.maximumEpochs == CORRECTED_NEURAL_MAXIMUM_EPOCHS)
        require(reference.perfectConfirmationEpochs == CORRECTED_NEURAL_CONFIRMATION_EPOCHS)
        require(reference.optimizer.contains("state inputs x1/32"))
        require(reference.optimizer.contains("candidate weight/bias post-Adam updates x1/8"))
        require(reference.stages.single { it.decisions == 64 }.reliablyMemorizedByAllSeeds)
        require(reference.stages.single { it.decisions == 128 }.reliablyMemorizedByAllSeeds)
        require(!reference.stages.single { it.decisions == 389 }.reliablyMemorizedByAllSeeds)
        progress("Bound n=64, n=128, and n=389 to retained issue-0028 evidence $referenceSha")

        val stage256 = Issue0028NeuralPopulationScalingDiagnostic(root, outputDirectory)
            .trainFixedCorrectedPrefix(
                ordered = ordered,
                modelConfig = modelConfig,
                decisions = ISSUE_0029_PREFIX,
                progress = progress,
            )
        val fixedPrefixIdentity = prepared.rawPrefixIdentity(ISSUE_0029_PREFIX)
        require(stage256.subsetIdentity == fixedPrefixIdentity)
        val stages = listOf(64, 128).map { retainedStage(reference, it) } +
            stage256 + retainedStage(reference, 389)
        val reliable = stage256.reliablyMemorizedByAllSeeds
        val allReachedPerfect = stage256.seeds.all { it.firstPerfectEpoch != null }
        val diagnosticCase = when {
            reliable -> "N256_RELIABLY_MEMORIZED_BY_ALL_SEEDS"
            allReachedPerfect -> "N256_PERFECT_FIT_REACHED_BY_ALL_SEEDS_BUT_NOT_RELIABLE"
            else -> "N256_NOT_REACHED_BY_ALL_SEEDS_AND_NOT_RELIABLE"
        }
        val stabilityInterval = if (reliable) "(256, 389]" else "(128, 256]"
        val nextQuestion = when {
            reliable ->
                "Should the remaining (256, 389] interval be localized with one fixed midpoint, or is a direct n=256 versus n=389 trajectory comparison now the more discriminating use of compute?"
            allReachedPerfect ->
                "Which teacher margins repeatedly change sign at n=256, and does their instability precede the severe saturation seen at n=389?"
            else ->
                "Does the unchanged learner reliably memorize the single fixed n=192 midpoint, between reliable n=128 and unreliable n=256?"
        }

        return Issue0029NeuralStabilityBoundaryReport(
            generatedAtUtc = Instant.now().toString(),
            implementationSourceProvenance = implementation,
            historicalSourceProvenance = population.manifest.sourceProvenance,
            historicalCandidateSchemaVersion = CANDIDATE_SCHEMA_V3,
            featureSchema = NEURAL_BC_FEATURE_SCHEMA,
            corpusManifestPath = root.relativize(population.manifestPath).toString(),
            corpusManifestSha256 = population.manifestSha256,
            corpusDatasetIdentity = population.manifest.datasetIdentity,
            splitPath = root.relativize(population.splitPath).toString(),
            splitSha256 = population.splitSha256,
            splitIdentity = population.split.splitIdentity,
            trainingDecisions = ordered.size,
            subsetSelectionProtocol = CORRECTED_NEURAL_SUBSET_PROTOCOL,
            subsetSelectionOrderSha256 = orderSha,
            fixedPrefixDecisions = ISSUE_0029_PREFIX,
            fixedPrefixIdentity = fixedPrefixIdentity,
            referenceArtifactPath = root.relativize(referencePath).toString(),
            referenceArtifactSha256 = referenceSha,
            referenceProtocol = reference.protocol,
            referenceGeneratedAtUtc = reference.generatedAtUtc,
            modelConfig = modelConfig,
            optimizer =
                "issue-0022 SparseAdam at 0.01, state inputs x1/32, candidate weight/bias post-Adam updates x1/8",
            initializationSeeds = CORRECTED_NEURAL_SEEDS,
            maximumEpochs = CORRECTED_NEURAL_MAXIMUM_EPOCHS,
            perfectConfirmationEpochs = CORRECTED_NEURAL_CONFIRMATION_EPOCHS,
            traceEpochs = reference.traceEpochs,
            executionMode =
                "ordinary foreground Gradle workflow; the owner explicitly excluded the in-development durable-run helper",
            updateExposureDefinition = reference.updateExposureDefinition,
            saturationAndCollapseDefinitions = reference.saturationAndCollapseDefinitions,
            stages = stages,
            diagnosticCase = diagnosticCase,
            allSeedStabilityInterval = stabilityInterval,
            interpretation = issue0029Interpretation(stages, stage256),
            limitations = listOf(
                "This is fixed-training-population optimization evidence, not held-out, generalization, architecture-selection, or playing-strength evidence.",
                "Only n=256 was newly trained. Every n=64/n=128/n=389 measurement is retained from the SHA-bound issue-0028 artifact.",
                "Update-call exposure remains descriptive. Issue 0028 already showed that similar heavy-cell call exposure can accompany substantially different state saturation, so this result does not treat raw call count as a sufficient cause.",
                "Exact projection equality detects severe finite-precision collapse but does not measure merely close hidden vectors.",
                "No chart is added: four fixed nested sizes and three fixed seeds are more exactly auditable in tables, and a connected curve would imply unmeasured behavior between anchors.",
                "No scale, architecture, feature, data, objective, label, candidate semantics, update order, seed, or training budget was tuned against n=256.",
                "The in-development durable-run facility was not used at the owner's explicit direction; this changes operational supervision, not the training recipe or evidence semantics.",
            ),
            narrowestNextQuestion = nextQuestion,
        )
    }

    private fun retainedStage(
        reference: Issue0028NeuralPopulationScalingReport,
        decisions: Int,
    ): NeuralPopulationScalingStageResult {
        val stage = reference.stages.single { it.decisions == decisions }
        return stage.copy(
            resultSource = "RETAINED_ISSUE_0028(${stage.resultSource})",
            seeds = stage.seeds.map { seed ->
                seed.copy(resultSource = "RETAINED_ISSUE_0028(${seed.resultSource})")
            },
        )
    }
}

private fun issue0029Interpretation(
    stages: List<NeuralPopulationScalingStageResult>,
    n256: NeuralPopulationScalingStageResult,
): List<String> {
    fun fit(stage: NeuralPopulationScalingStageResult, final: Boolean): String =
        stage.seeds.joinToString(" / ") { seed ->
            val correct = if (final) seed.finalStrictRankingCorrect else seed.bestStrictRankingCorrect
            "$correct/${stage.decisions}"
        }
    fun pctRange(values: List<Double>): String =
        "%.2f–%.2f%%".format(values.minOrNull()!! * 100.0, values.maxOrNull()!! * 100.0)
    fun projectionRange(
        stage: NeuralPopulationScalingStageResult,
        state: Boolean,
    ): String = pctRange(stage.seeds.map { seed ->
        val distribution = if (state) seed.finalCheckpoint.state else seed.finalCheckpoint.candidate
        distribution.nearSaturatedFraction
    })
    fun exactProblems(seed: NeuralPopulationScalingSeedResult): Boolean =
        (seed.trace + seed.bestCheckpoint + seed.finalCheckpoint).any { point ->
            point.collapse.repeatedProjectedStateGroups > 0 ||
                point.candidateCollapse.learnedCandidateCollapseGroups > 0 ||
                point.collapse.contradictoryRankingComponents > 0
        }
    val n64 = stages.single { it.decisions == 64 }
    val n128 = stages.single { it.decisions == 128 }
    val n389 = stages.single { it.decisions == 389 }
    val confirmed = n256.seeds.count(NeuralPopulationScalingSeedResult::stoppedAfterPerfectConfirmation)
    val reached = n256.seeds.count { it.firstPerfectEpoch != null }
    val exactProblemSeeds = n256.seeds.filter(::exactProblems).map(NeuralPopulationScalingSeedResult::seed)
    val exposure = stages.joinToString("; ") { stage ->
        val state = stage.exposureProfiles.single { it.projection == "STATE" }
        val candidate = stage.exposureProfiles.single { it.projection == "CANDIDATE" }
        "n=${stage.decisions}: state median/p90 ${state.weightUpdatesPerEpoch.medianActiveUpdateCount}/" +
            "${state.weightUpdatesPerEpoch.p90ActiveUpdateCount}, candidate " +
            "${candidate.weightUpdatesPerEpoch.medianActiveUpdateCount}/" +
            "${candidate.weightUpdatesPerEpoch.p90ActiveUpdateCount}"
    }
    return listOf(
        "The unchanged learner's n=256 best strict fits are ${fit(n256, final = false)} and final fits are " +
            "${fit(n256, final = true)}; $reached/3 seeds reached 256/256 and $confirmed/3 satisfied the " +
            "20-consecutive-perfect reliability criterion.",
        "Final state near-saturation ranges are n=64 ${projectionRange(n64, state = true)}, " +
            "n=128 ${projectionRange(n128, state = true)}, n=256 ${projectionRange(n256, state = true)}, and " +
            "n=389 ${projectionRange(n389, state = true)}. Candidate ranges are respectively " +
            "${projectionRange(n64, state = false)}, ${projectionRange(n128, state = false)}, " +
            "${projectionRange(n256, state = false)}, and ${projectionRange(n389, state = false)}.",
        if (exactProblemSeeds.isEmpty()) {
            "No n=256 initialization, traced, best, or final checkpoint contains an exact repeated learned state, " +
                "an exact learned-candidate repetition, or a hidden ranking contradiction."
        } else {
            "At least one exact learned projection repetition or hidden ranking contradiction appears at n=256 for " +
                "seeds $exactProblemSeeds; the checkpoint table supplies the category and timing."
        },
        "Actual sparse weight-update exposure per epoch progresses as follows: $exposure. This is a direct counter " +
            "comparison, not a claim that count alone causes saturation or fit failure.",
        if (n256.reliablyMemorizedByAllSeeds) {
            "By the prespecified behavioral criterion, n=256 remains in the stable n=128 regime rather than the " +
                "seed-dependent n=389 regime; the all-seed boundary moves to (256, 389]."
        } else {
            "By the prespecified behavioral criterion, n=256 has entered the unreliable regime seen at n=389; the " +
                "all-seed boundary narrows to (128, 256]. The per-seed first-perfect and streak results distinguish " +
                "transient fit from failure to reach perfect fit."
        },
    )
}

internal fun renderIssue0029NeuralStabilityBoundary(
    report: Issue0029NeuralStabilityBoundaryReport,
): String = buildString {
    fun pct(value: Double): String = "%.2f%%".format(value * 100.0)
    fun number(value: Double): String = "%.6f".format(value)
    appendLine("# Neural n=256 stability-boundary diagnostic")
    appendLine()
    appendLine("## Technical summary")
    appendLine()
    appendLine("`${report.diagnosticCase}`")
    appendLine()
    appendLine("All-seed stability interval: `${report.allSeedStabilityInterval}`")
    appendLine()
    report.interpretation.forEach { appendLine("- $it") }
    appendLine()
    appendLine("This is fixed-corpus training-system evidence, not held-out or playing-strength evidence.")
    appendLine()
    appendLine("## Scope, data, and definitions")
    appendLine()
    appendLine("- Dataset: `${report.corpusDatasetIdentity}`")
    appendLine("- Manifest SHA-256: `${report.corpusManifestSha256}`")
    appendLine("- Split: `${report.splitIdentity}` / `${report.splitSha256}`")
    appendLine("- Nested order: `${report.subsetSelectionOrderSha256}`")
    appendLine("- Fixed n=256 identity: `${report.fixedPrefixIdentity}`")
    appendLine("- Historical candidate schema: ${report.historicalCandidateSchemaVersion}; feature schema: `${report.featureSchema}`")
    appendLine("- Model: ${report.modelConfig.parameterCount} parameters; seeds ${report.initializationSeeds}")
    appendLine("- Optimizer: ${report.optimizer}")
    appendLine("- Epoch budget / strict-perfect confirmation: ${report.maximumEpochs} / ${report.perfectConfirmationEpochs}")
    appendLine("- Retained comparison: `${report.referenceProtocol}` / `${report.referenceArtifactSha256}`")
    appendLine("- Execution: ${report.executionMode}")
    appendLine()
    report.saturationAndCollapseDefinitions.forEach { appendLine("- $it") }
    report.updateExposureDefinition.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Methodology")
    appendLine()
    appendLine("Only n=256 is newly trained. The source-bound historical reader reconstructs the exact repaired 389-decision issue-0022 training population, verifies the deterministic order digest, selects its first 256 decisions, and runs the issue-0028 corrected-prefix harness unchanged. n=64, n=128, and n=389 come directly from the SHA-bound issue-0028 artifact.")
    appendLine()
    appendLine("## Memorization result")
    appendLine()
    appendLine("| n | Source | Seed | First perfect | Longest perfect / confirmed | Best epoch / strict / loss | Final strict / loss | Minimum loss | Counter audit |")
    appendLine("| ---: | --- | ---: | ---: | --- | --- | --- | ---: | --- |")
    report.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            appendLine(
                "| ${stage.decisions} | ${stage.resultSource} | ${seed.seed} | ${seed.firstPerfectEpoch ?: "—"} | " +
                    "${seed.longestPerfectStreak} / ${seed.stoppedAfterPerfectConfirmation} | " +
                    "${seed.bestEpoch} / ${seed.bestStrictRankingCorrect}/${stage.decisions} / ${number(seed.bestMeanCrossEntropy)} | " +
                    "${seed.finalStrictRankingCorrect}/${stage.decisions} / ${number(seed.finalMeanCrossEntropy)} | " +
                    "${number(seed.minimumObservedMeanCrossEntropy)} | " +
                    "${seed.exactObservedFinalUpdateCountsMatchReconstruction ?: "retained"} |"
            )
        }
    }
    appendLine()
    appendLine("All-seed 20-consecutive reliability: " + report.stages.joinToString { stage ->
        "n=${stage.decisions}: ${stage.reliablyMemorizedByAllSeeds}"
    })
    appendLine()
    appendLine("## Projection condition")
    appendLine()
    appendLine("| n | Seed | Checkpoint | Epoch | Strict / loss | State near / exact / derivative | Candidate near / exact / derivative | State repeats / largest | Candidate repeats / labels | Hidden contradictions / affected |")
    appendLine("| ---: | ---: | --- | ---: | --- | --- | --- | --- | --- | --- |")
    report.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            val selected = buildList {
                if (stage.decisions == report.fixedPrefixDecisions) {
                    listOf(0, 1, 8, 24, 64, 128, 256, 512, 1_024, 1_500).forEach { epoch ->
                        seed.trace.singleOrNull { it.epoch == epoch }?.let { add("trace" to it) }
                    }
                }
                add("best" to seed.bestCheckpoint)
                add("final" to seed.finalCheckpoint)
            }.distinctBy { (name, point) -> name to point.epoch }
            selected.forEach { (name, point) ->
                appendLine(
                    "| ${stage.decisions} | ${seed.seed} | $name | ${point.epoch} | " +
                        "${point.strictRankingCorrect}/${stage.decisions} / ${number(point.meanCrossEntropy)} | " +
                        "${pct(point.state.nearSaturatedFraction)} / ${pct(point.state.exactlySaturatedFraction)} / ${"%.3e".format(point.state.meanTanhDerivative)} | " +
                        "${pct(point.candidate.nearSaturatedFraction)} / ${pct(point.candidate.exactlySaturatedFraction)} / ${"%.3e".format(point.candidate.meanTanhDerivative)} | " +
                        "${point.collapse.repeatedProjectedStateGroups} / ${point.collapse.largestRepeatedProjectedStateGroup} | " +
                        "${point.candidateCollapse.learnedCandidateCollapseGroups} / ${point.candidateCollapse.teacherLabelsInLearnedCandidateCollapseGroups} | " +
                        "${point.collapse.contradictoryRankingComponents} / ${point.collapse.decisionsAffectedByContradictions} |"
                )
            }
        }
    }
    appendLine()
    appendLine("## Optimizer exposure comparison")
    appendLine()
    appendLine("| n | Projection | Active buckets | Feature occurrences | Unique bucket-decision touches | Actual weight calls | Active min / p10 / median / p90 / p99 / max | Dense bias calls/cell |")
    appendLine("| ---: | --- | ---: | ---: | ---: | ---: | --- | ---: |")
    report.stages.forEach { stage ->
        stage.exposureProfiles.forEach { profile ->
            val counts = profile.weightUpdatesPerEpoch
            appendLine(
                "| ${stage.decisions} | ${profile.projection} | ${profile.activeInputBuckets} | " +
                    "${profile.sparseFeatureOccurrencesPerEpoch} | ${profile.uniqueBucketDecisionTouchesPerEpoch} | " +
                    "${profile.actualWeightUpdateCallsPerEpoch} | ${counts.minimumActiveUpdateCount} / " +
                    "${counts.p10ActiveUpdateCount} / ${counts.medianActiveUpdateCount} / " +
                    "${counts.p90ActiveUpdateCount} / ${counts.p99ActiveUpdateCount} / " +
                    "${counts.maximumActiveUpdateCount} | ${profile.denseBiasUpdateCallsPerCellPerEpoch} |"
            )
        }
    }
    appendLine()
    val misses = report.stages.single { it.decisions == report.fixedPrefixDecisions }.seeds.flatMap { seed ->
        seed.bestCheckpointMisrankedDecisions.map { seed.seed to it }
    }
    if (misses.isNotEmpty()) {
        appendLine("## Best-checkpoint strict misses")
        appendLine()
        misses.forEach { (seed, fit) ->
            appendLine(
                "- seed=$seed `${fit.decision.gameId}:${fit.decision.decisionIndex}`: teacher " +
                    "${fit.teacherIntent}, predicted ${fit.predictedIntent}, " +
                    "loss ${number(fit.meanCrossEntropyContribution)}, margin ${number(fit.teacherMargin)}"
            )
        }
        appendLine()
    }
    appendLine("## Limitations and robustness")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Next step")
    appendLine()
    appendLine(report.narrowestNextQuestion)
}
