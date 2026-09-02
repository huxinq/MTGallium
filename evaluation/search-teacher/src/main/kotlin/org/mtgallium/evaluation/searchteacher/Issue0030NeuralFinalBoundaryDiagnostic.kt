package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val ISSUE_0030_PROTOCOL = "issue-0030-fixed-v3-neural-n323-boundary-v1"
private const val ISSUE_0030_REFERENCE_ARTIFACT =
    "neural-stability-boundary-diagnostic/artifact.json"
private const val ISSUE_0030_EXPECTED_REFERENCE_SHA256 =
    "75226241fa7013f3edcb586389a5d5949874f66a04d384597a7b1fb52d44f495"
private const val ISSUE_0030_PREFIX = 323

@Serializable
internal data class Issue0030NeuralFinalBoundaryReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0030_PROTOCOL,
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
    val addedDecisionCohort: String,
    val interpretation: List<String>,
    val limitations: List<String>,
    val narrowestNextQuestion: String,
)

internal class Issue0030NeuralFinalBoundaryDiagnostic(
    private val root: Path,
    private val outputDirectory: Path,
) {
    fun run(
        historicalManifestPath: Path,
        progress: (String) -> Unit = {},
    ): Issue0030NeuralFinalBoundaryReport {
        Files.createDirectories(outputDirectory)
        val prepared = CorrectedNeuralDiagnosticPreparation(root).prepare(historicalManifestPath)
        val implementation = prepared.implementationSourceProvenance
        val population = prepared.population
        val ordered = prepared.rawOrderedDecisions
        val orderSha = prepared.subsetSelectionOrderSha256
        val modelConfig = prepared.modelConfig
        progress("Recovered the exact repaired issue-0022 training population and fixed nested order")

        val referencePath = EvidenceStore(root).latest(ISSUE_0030_REFERENCE_ARTIFACT)
        val referenceSha = sha256File(referencePath)
        require(referenceSha == ISSUE_0030_EXPECTED_REFERENCE_SHA256) {
            "Issue-0029 reference artifact changed: $referenceSha"
        }
        val reference = evidenceJson.decodeFromString<Issue0029NeuralStabilityBoundaryReport>(
            Files.readString(referencePath)
        )
        require(reference.protocol == ISSUE_0029_PROTOCOL)
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
        require(reference.fixedPrefixDecisions == 256)
        require(reference.stages.single { it.decisions == 256 }.reliablyMemorizedByAllSeeds)
        require(!reference.stages.single { it.decisions == 389 }.reliablyMemorizedByAllSeeds)
        progress("Bound n=256 and n=389 to retained issue-0029 evidence $referenceSha")

        val stage323 = Issue0028NeuralPopulationScalingDiagnostic(root, outputDirectory)
            .trainFixedCorrectedPrefix(
                ordered = ordered,
                modelConfig = modelConfig,
                decisions = ISSUE_0030_PREFIX,
                progress = progress,
            )
        val fixedPrefixIdentity = prepared.rawPrefixIdentity(ISSUE_0030_PREFIX)
        require(stage323.subsetIdentity == fixedPrefixIdentity)
        val stages = listOf(
            retainedStage(reference, 256),
            stage323,
            retainedStage(reference, 389),
        )
        val reliable = stage323.reliablyMemorizedByAllSeeds
        val allReachedPerfect = stage323.seeds.all { it.firstPerfectEpoch != null }
        val diagnosticCase = when {
            reliable -> "N323_RELIABLY_MEMORIZED_BY_ALL_SEEDS"
            allReachedPerfect -> "N323_PERFECT_FIT_REACHED_BY_ALL_SEEDS_BUT_NOT_RELIABLE"
            else -> "N323_NOT_REACHED_BY_ALL_SEEDS_AND_NOT_RELIABLE"
        }
        val stabilityInterval = if (reliable) "(323, 389]" else "(256, 323]"
        val cohort = if (reliable) "324-389" else "257-323"
        val nextQuestion =
            "Which state features, candidate sets, gradient contributions, and teacher-margin interactions introduced " +
                "by decisions $cohort distinguish the smaller population from its reliable lower anchor?"

        return Issue0030NeuralFinalBoundaryReport(
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
            fixedPrefixDecisions = ISSUE_0030_PREFIX,
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
                "expensive phase launched through the committed durable-run helper under systemd --user; exact helper run identity is external operational provenance recorded in issue 0030",
            updateExposureDefinition = reference.updateExposureDefinition,
            saturationAndCollapseDefinitions = reference.saturationAndCollapseDefinitions,
            stages = stages,
            diagnosticCase = diagnosticCase,
            allSeedStabilityInterval = stabilityInterval,
            addedDecisionCohort = cohort,
            interpretation = issue0030Interpretation(stages, stage323),
            limitations = listOf(
                "This is fixed-training-population optimization evidence, not held-out, generalization, architecture-selection, or playing-strength evidence.",
                "Only n=323 was newly trained. Every n=256/n=389 measurement is retained from the SHA-bound issue-0029 artifact.",
                "Update-call exposure remains descriptive; it omits gradient magnitude and direction, feature value, candidate multiplicity, and sparse-touch timing under the global Adam clock.",
                "Exact projection equality detects severe finite-precision collapse but does not measure merely close hidden vectors.",
                "No chart is added: three fixed nested sizes and three fixed seeds are more exactly auditable in tables, and a connected curve would imply unmeasured behavior between anchors.",
                "No scale, architecture, feature, data, objective, label, candidate semantics, update order, seed, or training budget was tuned against n=323.",
                "The resulting interval is final for this task; no additional prefix is run before direct inspection of the identified added-decision cohort.",
            ),
            narrowestNextQuestion = nextQuestion,
        )
    }

    private fun retainedStage(
        reference: Issue0029NeuralStabilityBoundaryReport,
        decisions: Int,
    ): NeuralPopulationScalingStageResult {
        val stage = reference.stages.single { it.decisions == decisions }
        return stage.copy(
            resultSource = "RETAINED_ISSUE_0029(${stage.resultSource})",
            seeds = stage.seeds.map { seed ->
                seed.copy(resultSource = "RETAINED_ISSUE_0029(${seed.resultSource})")
            },
        )
    }
}

private fun issue0030Interpretation(
    stages: List<NeuralPopulationScalingStageResult>,
    n323: NeuralPopulationScalingStageResult,
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
    val n256 = stages.single { it.decisions == 256 }
    val n389 = stages.single { it.decisions == 389 }
    val confirmed = n323.seeds.count(NeuralPopulationScalingSeedResult::stoppedAfterPerfectConfirmation)
    val reached = n323.seeds.count { it.firstPerfectEpoch != null }
    val exactProblemPoints = n323.seeds.flatMap { seed ->
        (seed.trace + seed.bestCheckpoint + seed.finalCheckpoint).distinctBy { it.epoch }
            .filter { point ->
                point.collapse.repeatedProjectedStateGroups > 0 ||
                    point.candidateCollapse.learnedCandidateCollapseGroups > 0 ||
                    point.collapse.contradictoryRankingComponents > 0
            }.map { point -> seed.seed to point }
    }
    val exposure = stages.joinToString("; ") { stage ->
        val state = stage.exposureProfiles.single { it.projection == "STATE" }
        val candidate = stage.exposureProfiles.single { it.projection == "CANDIDATE" }
        "n=${stage.decisions}: state median/p90 ${state.weightUpdatesPerEpoch.medianActiveUpdateCount}/" +
            "${state.weightUpdatesPerEpoch.p90ActiveUpdateCount}, candidate " +
            "${candidate.weightUpdatesPerEpoch.medianActiveUpdateCount}/" +
            "${candidate.weightUpdatesPerEpoch.p90ActiveUpdateCount}"
    }
    return listOf(
        "The unchanged learner's n=323 best strict fits are ${fit(n323, final = false)} and final fits are " +
            "${fit(n323, final = true)}; $reached/3 seeds reached 323/323 and $confirmed/3 satisfied the " +
            "20-consecutive-perfect reliability criterion.",
        "Final state near-saturation ranges are n=256 ${projectionRange(n256, state = true)}, " +
            "n=323 ${projectionRange(n323, state = true)}, and n=389 ${projectionRange(n389, state = true)}. " +
            "Candidate ranges are respectively ${projectionRange(n256, state = false)}, " +
            "${projectionRange(n323, state = false)}, and ${projectionRange(n389, state = false)}.",
        if (exactProblemPoints.isEmpty()) {
            "No n=323 initialization, traced, best, or final checkpoint contains an exact repeated learned state, " +
                "an exact learned-candidate repetition, or a hidden ranking contradiction."
        } else {
            "The only n=323 exact-projection event is " + exactProblemPoints.joinToString { (seed, point) ->
                "seed $seed epoch ${point.epoch}: ${point.collapse.repeatedProjectedStateGroups} repeated-state " +
                    "groups, ${point.candidateCollapse.learnedCandidateCollapseGroups} learned-candidate groups " +
                    "containing ${point.candidateCollapse.teacherLabelsInLearnedCandidateCollapseGroups} teacher " +
                    "labels, and ${point.collapse.contradictoryRankingComponents} hidden contradictions"
            } + ". All n=323 best and final checkpoints are clear."
        },
        "Actual sparse weight-update exposure per epoch progresses as follows: $exposure. This is a direct counter " +
            "comparison, not a claim that count alone causes saturation or fit instability.",
        if (n323.reliablyMemorizedByAllSeeds) {
            "By the prespecified behavioral criterion, n=323 remains reliably memorized like n=256 rather than " +
                "seed-dependent n=389; the all-seed stability interval becomes (323, 389]."
        } else {
            "By the prespecified behavioral criterion, n=323 has entered the unreliable regime before n=389; the " +
                "all-seed stability interval becomes (256, 323]. Per-seed first-perfect and streak results " +
                "distinguish failure to reach perfect fit from transient fit."
        },
    )
}

internal fun renderIssue0030NeuralFinalBoundary(
    report: Issue0030NeuralFinalBoundaryReport,
): String = buildString {
    fun pct(value: Double): String = "%.2f%%".format(value * 100.0)
    fun number(value: Double): String = "%.6f".format(value)
    appendLine("# Neural n=323 final stability-boundary diagnostic")
    appendLine()
    appendLine("## Technical summary")
    appendLine()
    appendLine("`${report.diagnosticCase}`")
    appendLine()
    appendLine("All-seed stability interval: `${report.allSeedStabilityInterval}`")
    appendLine()
    appendLine("Added-decision cohort for direct inspection: `${report.addedDecisionCohort}`")
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
    appendLine("- Fixed n=323 identity: `${report.fixedPrefixIdentity}`")
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
    appendLine("Only n=323 is newly trained. The source-bound historical reader reconstructs the exact repaired 389-decision issue-0022 training population, verifies the deterministic order digest, selects its first 323 decisions, and runs the issue-0028 corrected-prefix harness unchanged. n=256 and n=389 come directly from the SHA-bound issue-0029 artifact.")
    appendLine()
    appendLine("## Memorization result")
    appendLine()
    appendLine("| n | Source | Seed | First perfect | Longest perfect / confirmed | Best epoch / strict / loss | Final epoch / strict / loss | Minimum loss | Counter audit |")
    appendLine("| ---: | --- | ---: | ---: | --- | --- | --- | ---: | --- |")
    report.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            appendLine(
                "| ${stage.decisions} | ${stage.resultSource} | ${seed.seed} | ${seed.firstPerfectEpoch ?: "—"} | " +
                    "${seed.longestPerfectStreak} / ${seed.stoppedAfterPerfectConfirmation} | " +
                    "${seed.bestEpoch} / ${seed.bestStrictRankingCorrect}/${stage.decisions} / ${number(seed.bestMeanCrossEntropy)} | " +
                    "${seed.epochsCompleted} / ${seed.finalStrictRankingCorrect}/${stage.decisions} / ${number(seed.finalMeanCrossEntropy)} | " +
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
    val exactEvents = report.stages.single { it.decisions == report.fixedPrefixDecisions }.seeds.flatMap { seed ->
        (seed.trace + seed.bestCheckpoint + seed.finalCheckpoint).distinctBy { it.epoch }
            .filter { point ->
                point.collapse.repeatedProjectedStateGroups > 0 ||
                    point.candidateCollapse.learnedCandidateCollapseGroups > 0 ||
                    point.collapse.contradictoryRankingComponents > 0
            }.map { point -> seed.seed to point }
    }
    appendLine("## Exact projection events")
    appendLine()
    if (exactEvents.isEmpty()) {
        appendLine("No exact learned-state repetition, learned-candidate repetition, or hidden contradiction occurs at any n=323 initialization, traced, best, or final checkpoint.")
    } else {
        appendLine("| Seed | Epoch | State groups / largest | Candidate groups / teacher labels | Hidden contradictions / affected |")
        appendLine("| ---: | ---: | --- | --- | --- |")
        exactEvents.forEach { (seed, point) ->
            appendLine(
                "| $seed | ${point.epoch} | ${point.collapse.repeatedProjectedStateGroups} / " +
                    "${point.collapse.largestRepeatedProjectedStateGroup} | " +
                    "${point.candidateCollapse.learnedCandidateCollapseGroups} / " +
                    "${point.candidateCollapse.teacherLabelsInLearnedCandidateCollapseGroups} | " +
                    "${point.collapse.contradictoryRankingComponents} / " +
                    "${point.collapse.decisionsAffectedByContradictions} |"
            )
        }
        appendLine()
        appendLine("The event is transient when it is absent from the following traced checkpoint and from the seed's best and final checkpoints. A learned-candidate group containing no teacher label does not itself impose a contradictory teacher ranking.")
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
    appendLine("## Next direct cohort comparison")
    appendLine()
    appendLine(report.narrowestNextQuestion)
}
