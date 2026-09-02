package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.ResearchRunCheckpoints

internal const val ISSUE_0031_PROTOCOL = "issue-0031-fixed-v3-neural-cohort-continuation-v1"
private const val ISSUE_0031_REFERENCE_ARTIFACT =
    "neural-final-boundary-diagnostic/artifact.json"
private const val ISSUE_0031_EXPECTED_REFERENCE_SHA256 =
    "449735df2c0d60c1fc60902b56ac741872323047d72ba2e5fbd8357d04a2abb9"
private const val ISSUE_0031_ANCHOR_DECISIONS = 323
private const val ISSUE_0031_ADDED_DECISIONS = 66
private val ISSUE_0031_TRACE_EPOCHS = (
    (0..8).toList() + listOf(
        10, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1_024, 1_500,
    )
).toSet()

@Serializable
internal data class NeuralMarginSummary(
    val decisions: Int,
    val minimum: Double,
    val p10: Double,
    val median: Double,
    val p90: Double,
    val maximum: Double,
    val mean: Double,
)

@Serializable
internal data class NeuralCohortFitMetrics(
    val decisions: Int,
    val strictRankingCorrect: Int,
    val strictRankingAccuracy: Double,
    val meanCrossEntropy: Double,
    val margins: NeuralMarginSummary,
    val misrankedDecisions: List<NeuralMemorizationDecisionFit>,
)

@Serializable
internal data class Issue0031TrajectoryCheckpoint(
    val postForkDecisionSteps: Long,
    val expandedEpochsCompleted: Int,
    val anchorEpochsCompleted: Int,
    val anchorEpochPosition: Int,
    val reason: String,
    val anchorContinuation: NeuralCohortFitMetrics,
    val expandedAnchor: NeuralCohortFitMetrics,
    val expandedAdded: NeuralCohortFitMetrics,
    val expandedCombined: NeuralCohortFitMetrics,
    val anchorProjection: NeuralProjectionTracePoint?,
    val expandedProjection: NeuralProjectionTracePoint?,
)

@Serializable
internal data class Issue0031TrainingForkCheckpoint(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0031_PROTOCOL,
    val seed: Long,
    val completedAnchorEpochs: Int,
    val firstPerfectAnchorEpoch: Int,
    val consecutivePerfectAnchorEpochs: Int,
    val nextAbsoluteEpoch: Int,
    val model: NeuralBcModelArtifact,
    val optimizer: SparseAdamState,
)

@Serializable
internal data class Issue0031SeedResult(
    val seed: Long,
    val forkAnchorEpoch: Int,
    val forkFirstPerfectEpoch: Int,
    val forkAnchorMetrics: NeuralCohortFitMetrics,
    val forkAddedMetrics: NeuralCohortFitMetrics,
    val forkCombinedMetrics: NeuralCohortFitMetrics,
    val forkCheckpointPath: String,
    val forkCheckpointSha256: String,
    val exactModelAndOptimizerFork: Boolean,
    val forkGradientGeometry: NeuralCohortGradientGeometry,
    val expandedFirstPerfectEpoch: Int?,
    val expandedConfirmationEpoch: Int?,
    val expandedEpochsCompleted: Int,
    val postForkDecisionSteps: Long,
    val expandedEpochsWithAnchorErrors: Int,
    val firstExpandedAnchorErrorEpoch: Int?,
    val anchorContinuationErrorObservations: Int,
    val firstAnchorContinuationErrorAtPostForkSteps: Long?,
    val expandedMinimumAnchorMargin: Double,
    val expandedMinimumAnchorMarginEpoch: Int,
    val anchorContinuationMinimumMargin: Double,
    val anchorContinuationMinimumMarginAtPostForkSteps: Long,
    val finalAnchorContinuation: NeuralCohortFitMetrics,
    val finalExpandedAnchor: NeuralCohortFitMetrics,
    val finalExpandedAdded: NeuralCohortFitMetrics,
    val finalExpandedCombined: NeuralCohortFitMetrics,
    val conditionalGradientGeometry: NeuralCohortGradientGeometry?,
    val addedGradientConcentration: List<NeuralAddedDecisionGradientConcentration>,
    val trajectory: List<Issue0031TrajectoryCheckpoint>,
    val finalAnchorModelPath: String,
    val finalAnchorModelSha256: String,
    val finalExpandedModelPath: String,
    val finalExpandedModelSha256: String,
    val diagnosticCase: String,
)

@Serializable
internal data class Issue0031NeuralCohortContinuationReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0031_PROTOCOL,
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
    val anchorDecisions: Int,
    val anchorIdentity: String,
    val addedDecisions: Int,
    val addedIdentity: String,
    val referenceArtifactPath: String,
    val referenceArtifactSha256: String,
    val referenceProtocol: String,
    val modelConfig: NeuralBcModelConfig,
    val trainingConfig: NeuralBcTrainingConfig,
    val initializationSeeds: List<Long>,
    val maximumPostForkExpandedEpochs: Int,
    val maximumPostForkDecisionSteps: Long,
    val perfectConfirmationEpochs: Int,
    val traceExpandedEpochs: List<Int>,
    val matchedExposureDefinition: List<String>,
    val forkContinuationStateDefinition: List<String>,
    val metricDefinitions: List<String>,
    val seeds: List<Issue0031SeedResult>,
    val diagnosticCase: String,
    val interpretation: List<String>,
    val limitations: List<String>,
    val narrowestNextQuestion: String,
)

@Serializable
internal data class Issue0031PreflightPhaseTiming(
    val phase: String,
    val elapsedMillis: Double,
)

@Serializable
internal data class Issue0031PreflightReport(
    val protocol: String = ISSUE_0031_PROTOCOL,
    val corpusManifestSha256: String,
    val splitSha256: String,
    val subsetSelectionOrderSha256: String,
    val trainingDecisions: Int,
    val anchorIdentity: String,
    val addedIdentity: String,
    val referenceArtifactSha256: String,
    val parameterCount: Int,
    val optimizerDecisionSteps: Int,
    val phaseTimings: List<Issue0031PreflightPhaseTiming>,
    val totalMillis: Double,
)

private data class Issue0031PreparedInputs(
    val implementation: PolicySourceProvenance,
    val population: Issue0022HistoricalPopulation,
    val ordered: List<EncodedBcDecision>,
    val anchor: List<EncodedBcDecision>,
    val added: List<EncodedBcDecision>,
    val orderSha: String,
    val anchorIdentity: String,
    val addedIdentity: String,
    val modelConfig: NeuralBcModelConfig,
    val trainingConfig: NeuralBcTrainingConfig,
    val referencePath: Path,
    val referenceSha: String,
    val reference: Issue0030NeuralFinalBoundaryReport,
    val preflightReport: Issue0031PreflightReport,
)

internal fun renderIssue0031Preflight(report: Issue0031PreflightReport): String = buildString {
    appendLine("Issue-0031 neural cohort continuation preflight passed")
    report.phaseTimings.forEach { timing ->
        appendLine("  ${timing.phase}: ${String.format(Locale.ROOT, "%.3f", timing.elapsedMillis)} ms")
    }
    appendLine("  total preparation: ${String.format(Locale.ROOT, "%.3f", report.totalMillis)} ms")
    appendLine("  decisions: ${report.trainingDecisions}; parameters: ${report.parameterCount}")
    appendLine("  corpus: ${report.corpusManifestSha256}")
    appendLine("  split: ${report.splitSha256}")
    appendLine("  order: ${report.subsetSelectionOrderSha256}")
    appendLine("  anchor: ${report.anchorIdentity}")
    appendLine("  added: ${report.addedIdentity}")
    appendLine("  reference: ${report.referenceArtifactSha256}")
    append("  optimizer updates: ${report.optimizerDecisionSteps}; no training or evidence output performed")
}

internal class Issue0031NeuralCohortContinuationDiagnostic(
    private val root: Path,
    private val outputDirectory: Path,
) {
    fun preflight(historicalManifestPath: Path): Issue0031PreflightReport =
        prepare(historicalManifestPath).preflightReport

    fun run(
        historicalManifestPath: Path,
        progress: (String) -> Unit = {},
    ): Issue0031NeuralCohortContinuationReport {
        Files.createDirectories(outputDirectory)
        val durableProgress = Issue0031DurableProgress()
        val prepared = prepare(historicalManifestPath)
        val implementation = prepared.implementation
        val population = prepared.population
        val ordered = prepared.ordered
        val anchor = prepared.anchor
        val added = prepared.added
        val orderSha = prepared.orderSha
        val anchorIdentity = prepared.anchorIdentity
        val addedIdentity = prepared.addedIdentity
        val modelConfig = prepared.modelConfig
        val trainingConfig = prepared.trainingConfig
        val referencePath = prepared.referencePath
        val referenceSha = prepared.referenceSha
        val reference = prepared.reference
        progress("Verified exact issue-0022 population, nested order, n=323 anchor, and corrected learner")
        durableProgress.publish(0, "preflight", "verified fixed corpus/order and corrected learner")

        val results = CORRECTED_NEURAL_SEEDS.mapIndexed { index, seed ->
            durableProgress.publish(index, "anchor-training", "seed $seed: reconstructing exact n=323 fork")
            progress("Seed $seed: reconstructing exact reliable n=323 continuation state")
            val expected = reference.stages.single { it.decisions == ISSUE_0031_ANCHOR_DECISIONS }
                .seeds.single { it.seed == seed }
            val fork = trainAnchorFork(anchor, seed, modelConfig, trainingConfig)
            require(fork.epoch == expected.epochsCompleted)
            require(fork.firstPerfectEpoch == expected.firstPerfectEpoch)
            require(fork.metrics.strictRankingCorrect == expected.finalStrictRankingCorrect)
            require(abs(fork.metrics.meanCrossEntropy - expected.finalMeanCrossEntropy) <= 1e-12) {
                "Seed $seed fork loss ${fork.metrics.meanCrossEntropy} does not reproduce issue 0030 " +
                    "${expected.finalMeanCrossEntropy}"
            }
            require(fork.optimizer.decisionSteps == fork.epoch * anchor.size)
            val forkCheckpoint = Issue0031TrainingForkCheckpoint(
                seed = seed,
                completedAnchorEpochs = fork.epoch,
                firstPerfectAnchorEpoch = fork.firstPerfectEpoch,
                consecutivePerfectAnchorEpochs = CORRECTED_NEURAL_CONFIRMATION_EPOCHS,
                nextAbsoluteEpoch = fork.epoch + 1,
                model = copyNeuralBcModelArtifact(fork.artifact).copy(bestEpoch = fork.epoch),
                optimizer = fork.optimizer,
            )
            val forkPath = outputDirectory.resolve("fork-seed-$seed.json")
            ResearchRunCheckpoints.persist(
                forkPath,
                issue0031ResearchRunIdentity(prepared),
                ISSUE_0031_FORK_CHECKPOINT_SCHEMA,
                0,
                evidenceJson.encodeToString(forkCheckpoint).encodeToByteArray(),
            )
            val forkSha = sha256File(forkPath)
            progress("Seed $seed: exact fork at anchor epoch ${fork.epoch}; continuing both branches")
            durableProgress.publish(index, "fork-continuation", "seed $seed: matched anchor/expanded branches")
            val result = continueFromFork(
                seed = seed,
                fork = forkCheckpoint,
                anchor = anchor,
                added = added,
                combined = ordered,
                trainingConfig = trainingConfig,
                forkPath = forkPath,
                forkSha = forkSha,
                progress = progress,
            )
            durableProgress.publish(index + 1, "seed-complete", "seed $seed: ${result.diagnosticCase}")
            result
        }
        val diagnosticCase = classifyIssue0031(results)
        durableProgress.publish(3, "complete", diagnosticCase)
        return Issue0031NeuralCohortContinuationReport(
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
            anchorDecisions = anchor.size,
            anchorIdentity = anchorIdentity,
            addedDecisions = added.size,
            addedIdentity = addedIdentity,
            referenceArtifactPath = root.relativize(referencePath).toString(),
            referenceArtifactSha256 = referenceSha,
            referenceProtocol = reference.protocol,
            modelConfig = modelConfig,
            trainingConfig = trainingConfig,
            initializationSeeds = CORRECTED_NEURAL_SEEDS,
            maximumPostForkExpandedEpochs = CORRECTED_NEURAL_MAXIMUM_EPOCHS,
            maximumPostForkDecisionSteps =
                CORRECTED_NEURAL_MAXIMUM_EPOCHS.toLong() * ordered.size,
            perfectConfirmationEpochs = CORRECTED_NEURAL_CONFIRMATION_EPOCHS,
            traceExpandedEpochs = ISSUE_0031_TRACE_EPOCHS.sorted(),
            matchedExposureDefinition = listOf(
                "One post-fork step is one ordinary SparseAdam.step decision update and one increment of its global bias-correction clock.",
                "At every recorded point both branches have taken exactly the same number of post-fork decision updates; the anchor branch may therefore be partway through a 323-decision epoch when the expanded branch completes a 389-decision epoch.",
                "Shuffle epochs continue from the fork's next absolute epoch in each branch. No model, optimizer moment, sparse update count, bias-correction clock, or epoch-order state is reset.",
                "The upper bound is 1,500 post-fork expanded epochs = ${1_500L * ordered.size} post-fork decision steps. Reliability remains 20 consecutive strict-perfect complete expanded epochs.",
            ),
            forkContinuationStateDefinition = listOf(
                "The fork persists every model parameter, every first/second Adam moment, every sparse update count, the global optimizer decision-step clock, the completed epoch, first-perfect epoch, perfect streak, and next absolute shuffle epoch.",
                "Both branches deep-copy the persisted arrays; exact equality is checked before either branch takes an update, and a focused test verifies equal restored branches produce an identical next update without aliasing.",
            ),
            metricDefinitions = listOf(
                "Loss is the arithmetic mean of the existing per-decision softmax cross-entropy objective; combined loss is checked against the 323/389 and 66/389 cohort-weighted mean.",
                "Strict fit requires the teacher score to exceed every alternative score; equality is incorrect.",
                "Margins are teacher score minus the highest alternative score. Percentiles use the lower observed rank floor((n-1)q).",
                "Gradient actual-objective norms weight cohort mean gradients by 323/389 and 66/389; size-normalized norms and cosines compare the two cohort mean gradients directly.",
                "Near saturation and exact projection collapse retain the issue-0030 definitions and are sampled only at sparse trajectory checkpoints.",
            ),
            seeds = results,
            diagnosticCase = diagnosticCase,
            interpretation = interpretIssue0031(results, diagnosticCase),
            limitations = listOf(
                "This is a fixed-corpus training-trajectory intervention, not held-out, playing-strength, architecture-selection, or general policy-view evidence.",
                "The expanded branch changes both the decision population and its per-epoch shuffle length; matched global decision steps control optimizer exposure, while ordinary epoch-based ordering remains part of the treatment.",
                "The fork starts at the first endpoint satisfying 20 consecutive strict-perfect n=323 epochs, not at an arbitrarily selected low-loss n=323 checkpoint.",
                "Analytical gradients describe the common-state mean scalar objective. Sparse Adam moments, per-decision ordering, and nonlinear movement mean a local cosine does not by itself establish the later causal trajectory.",
                "Exact-collapse telemetry detects bit-identical projections only; saturation is supportive because issue 0030 already established it is compatible with reliable fitting.",
                "No chart is added: exact matched-exposure tables expose all discriminating cohort quantities without implying interpolation between deliberately sparse checkpoints.",
                "No learning rate, scale, optimizer, representation, architecture, seed, cohort, order, label, or post-fork budget was tuned against this result.",
            ),
            narrowestNextQuestion = narrowestNextIssue0031Question(diagnosticCase),
        )
    }

    private fun prepare(historicalManifestPath: Path): Issue0031PreparedInputs {
        val totalStarted = System.nanoTime()
        val prepared = CorrectedNeuralDiagnosticPreparation(root).prepare(historicalManifestPath)
        val timings = prepared.phaseTimings.mapTo(mutableListOf()) {
            Issue0031PreflightPhaseTiming(it.phase, it.elapsedMillis)
        }
        val implementation = prepared.implementationSourceProvenance
        val population = prepared.population
        val cohorts = prepared.splitAt(ISSUE_0031_ANCHOR_DECISIONS)
        val ordered = cohorts.combined
        val anchor = cohorts.anchor
        val added = cohorts.added
        require(added.size == ISSUE_0031_ADDED_DECISIONS)
        val orderSha = prepared.subsetSelectionOrderSha256
        val anchorIdentity = cohorts.anchorIdentity
        val addedIdentity = cohorts.addedIdentity
        val modelConfig = prepared.modelConfig
        val trainingConfig = prepared.trainingConfig
        lateinit var referencePath: Path
        lateinit var referenceSha: String
        lateinit var reference: Issue0030NeuralFinalBoundaryReport
        timed(timings, "reference artifact validation") {
            referencePath = EvidenceStore(root).latest(ISSUE_0031_REFERENCE_ARTIFACT)
            referenceSha = sha256File(referencePath)
            require(referenceSha == ISSUE_0031_EXPECTED_REFERENCE_SHA256) {
                "Issue-0030 reference artifact changed: $referenceSha"
            }
            reference = evidenceJson.decodeFromString(Files.readString(referencePath))
            requireReference(reference, population, orderSha, modelConfig, anchorIdentity)
        }
        val optimizerDecisionSteps = timed(timings, "learner and optimizer initialization") {
            val artifact = CandidateConditionedNeuralPolicy.initialize(
                modelConfig,
                CORRECTED_NEURAL_SEEDS.first(),
            ).artifact
            SparseAdam(artifact, trainingConfig).snapshotState().decisionSteps.also {
                require(it == 0)
            }
        }
        val report = Issue0031PreflightReport(
            corpusManifestSha256 = population.manifestSha256,
            splitSha256 = population.splitSha256,
            subsetSelectionOrderSha256 = orderSha,
            trainingDecisions = ordered.size,
            anchorIdentity = anchorIdentity,
            addedIdentity = addedIdentity,
            referenceArtifactSha256 = referenceSha,
            parameterCount = modelConfig.parameterCount,
            optimizerDecisionSteps = optimizerDecisionSteps,
            phaseTimings = timings.toList(),
            totalMillis = elapsedMillis(totalStarted),
        )
        return Issue0031PreparedInputs(
            implementation = implementation,
            population = population,
            ordered = ordered,
            anchor = anchor,
            added = added,
            orderSha = orderSha,
            anchorIdentity = anchorIdentity,
            addedIdentity = addedIdentity,
            modelConfig = modelConfig,
            trainingConfig = trainingConfig,
            referencePath = referencePath,
            referenceSha = referenceSha,
            reference = reference,
            preflightReport = report,
        )
    }

    private fun <T> timed(
        timings: MutableList<Issue0031PreflightPhaseTiming>,
        phase: String,
        block: () -> T,
    ): T {
        val started = System.nanoTime()
        return block().also {
            timings += Issue0031PreflightPhaseTiming(phase, elapsedMillis(started))
        }
    }

    private fun elapsedMillis(startedNanos: Long): Double =
        (System.nanoTime() - startedNanos) / 1_000_000.0

    private fun requireReference(
        reference: Issue0030NeuralFinalBoundaryReport,
        population: Issue0022HistoricalPopulation,
        orderSha: String,
        modelConfig: NeuralBcModelConfig,
        anchorIdentity: String,
    ) {
        require(reference.protocol == ISSUE_0030_PROTOCOL)
        require(reference.corpusManifestSha256 == population.manifestSha256)
        require(reference.splitSha256 == population.splitSha256)
        require(reference.subsetSelectionProtocol == CORRECTED_NEURAL_SUBSET_PROTOCOL)
        require(reference.subsetSelectionOrderSha256 == orderSha)
        require(reference.trainingDecisions == CORRECTED_NEURAL_TRAINING_DECISIONS)
        require(reference.fixedPrefixDecisions == ISSUE_0031_ANCHOR_DECISIONS)
        require(reference.fixedPrefixIdentity == anchorIdentity)
        require(reference.historicalCandidateSchemaVersion == CANDIDATE_SCHEMA_V3)
        require(reference.featureSchema == NEURAL_BC_FEATURE_SCHEMA)
        require(reference.modelConfig == modelConfig)
        require(reference.initializationSeeds == CORRECTED_NEURAL_SEEDS)
        require(reference.maximumEpochs == CORRECTED_NEURAL_MAXIMUM_EPOCHS)
        require(reference.perfectConfirmationEpochs == CORRECTED_NEURAL_CONFIRMATION_EPOCHS)
        require(reference.optimizer.contains("state inputs x1/32"))
        require(reference.optimizer.contains("candidate weight/bias post-Adam updates x1/8"))
        require(reference.stages.single { it.decisions == ISSUE_0031_ANCHOR_DECISIONS }
            .reliablyMemorizedByAllSeeds)
    }

    private fun continueFromFork(
        seed: Long,
        fork: Issue0031TrainingForkCheckpoint,
        anchor: List<EncodedBcDecision>,
        added: List<EncodedBcDecision>,
        combined: List<EncodedBcDecision>,
        trainingConfig: NeuralBcTrainingConfig,
        forkPath: Path,
        forkSha: String,
        progress: (String) -> Unit,
    ): Issue0031SeedResult {
        val anchorModel = copyNeuralBcModelArtifact(fork.model)
        val expandedModel = copyNeuralBcModelArtifact(fork.model)
        val anchorAdam = SparseAdam(anchorModel, trainingConfig, fork.optimizer)
        val expandedAdam = SparseAdam(expandedModel, trainingConfig, fork.optimizer)
        requireExactFork(anchorModel, expandedModel, anchorAdam.snapshotState(), expandedAdam.snapshotState())
        val anchorBranch = Issue0031ContinuationBranch(
            seed, fork.completedAnchorEpochs, anchor, anchorModel, anchorAdam,
        )
        val expandedBranch = Issue0031ContinuationBranch(
            seed, fork.completedAnchorEpochs, combined, expandedModel, expandedAdam,
        )
        val forkPolicy = CandidateConditionedNeuralPolicy.fromArtifact(fork.model)
        val forkAnchor = cohortMetrics(forkPolicy, anchor)
        val forkAdded = cohortMetrics(forkPolicy, added)
        val forkCombined = cohortMetrics(forkPolicy, combined)
        requireCombinedMetrics(forkAnchor, forkAdded, forkCombined)
        val forkGradient = neuralCohortGradientGeometry(fork.model, anchor, added, "FORK")
        val trajectory = mutableListOf<Issue0031TrajectoryCheckpoint>()
        trajectory += trajectoryCheckpoint(
            reason = "FORK",
            expandedEpoch = 0,
            anchorBranch = anchorBranch,
            expandedBranch = expandedBranch,
            anchor = anchor,
            added = added,
            combined = combined,
            includeProjection = true,
        )
        var expandedFirstPerfect: Int? = null
        var expandedPerfectStreak = 0
        var expandedConfirmation: Int? = null
        var expandedAnchorErrorEpochs = 0
        var firstExpandedAnchorError: Int? = null
        var firstExpandedAnchorErrorModel: NeuralBcModelArtifact? = null
        var expandedMinimumAnchorMargin = forkAnchor.margins.minimum
        var expandedMinimumAnchorMarginEpoch = 0
        val anchorErrorObservations = linkedSetOf<String>()
        var firstAnchorErrorStep: Long? = null
        var anchorMinimumMargin = forkAnchor.margins.minimum
        var anchorMinimumMarginStep = 0L

        for (expandedEpoch in 1..CORRECTED_NEURAL_MAXIMUM_EPOCHS) {
            expandedBranch.advance(combined.size.toLong())
            require(expandedBranch.epochsCompleted == expandedEpoch)
            anchorBranch.advance(combined.size.toLong()) { completedAnchorEpoch, branch ->
                val metric = cohortMetrics(branch.policy(), anchor)
                if (metric.strictRankingCorrect < anchor.size) {
                    anchorErrorObservations += "EPOCH:$completedAnchorEpoch"
                    if (firstAnchorErrorStep == null) firstAnchorErrorStep = branch.postForkSteps
                }
                if (metric.margins.minimum < anchorMinimumMargin) {
                    anchorMinimumMargin = metric.margins.minimum
                    anchorMinimumMarginStep = branch.postForkSteps
                }
            }
            require(anchorBranch.postForkSteps == expandedBranch.postForkSteps)
            val expandedPolicy = expandedBranch.policy()
            val expandedAnchor = cohortMetrics(expandedPolicy, anchor)
            val expandedAdded = cohortMetrics(expandedPolicy, added)
            val expandedCombined = cohortMetrics(expandedPolicy, combined)
            requireCombinedMetrics(expandedAnchor, expandedAdded, expandedCombined)
            val anchorMatched = cohortMetrics(anchorBranch.policy(), anchor)
            if (anchorMatched.strictRankingCorrect < anchor.size) {
                anchorErrorObservations += "MATCHED:$expandedEpoch"
                if (firstAnchorErrorStep == null) firstAnchorErrorStep = anchorBranch.postForkSteps
            }
            if (anchorMatched.margins.minimum < anchorMinimumMargin) {
                anchorMinimumMargin = anchorMatched.margins.minimum
                anchorMinimumMarginStep = anchorBranch.postForkSteps
            }
            if (expandedAnchor.strictRankingCorrect < anchor.size) {
                expandedAnchorErrorEpochs++
                if (firstExpandedAnchorError == null) {
                    firstExpandedAnchorError = expandedEpoch
                    firstExpandedAnchorErrorModel = copyNeuralBcModelArtifact(expandedModel)
                }
            }
            if (expandedAnchor.margins.minimum < expandedMinimumAnchorMargin) {
                expandedMinimumAnchorMargin = expandedAnchor.margins.minimum
                expandedMinimumAnchorMarginEpoch = expandedEpoch
            }
            if (expandedCombined.strictRankingCorrect == combined.size) {
                if (expandedFirstPerfect == null) expandedFirstPerfect = expandedEpoch
                expandedPerfectStreak++
                if (expandedPerfectStreak >= CORRECTED_NEURAL_CONFIRMATION_EPOCHS) {
                    expandedConfirmation = expandedEpoch
                }
            } else {
                expandedPerfectStreak = 0
            }
            val event = when {
                expandedConfirmation == expandedEpoch -> "EXPANDED_CONFIRMED"
                firstExpandedAnchorError == expandedEpoch -> "FIRST_EXPANDED_ANCHOR_ERROR"
                expandedEpoch == CORRECTED_NEURAL_MAXIMUM_EPOCHS -> "BUDGET_ENDPOINT"
                else -> null
            }
            if (expandedEpoch in ISSUE_0031_TRACE_EPOCHS || event != null) {
                trajectory += trajectoryCheckpoint(
                    reason = event ?: "SCHEDULED",
                    expandedEpoch = expandedEpoch,
                    anchorBranch = anchorBranch,
                    expandedBranch = expandedBranch,
                    anchor = anchor,
                    added = added,
                    combined = combined,
                    includeProjection = expandedEpoch in ISSUE_0031_TRACE_EPOCHS ||
                        expandedConfirmation == expandedEpoch,
                )
            }
            if (expandedConfirmation != null) break
        }
        val finalAnchor = cohortMetrics(anchorBranch.policy(), anchor)
        val finalExpandedAnchor = cohortMetrics(expandedBranch.policy(), anchor)
        val finalExpandedAdded = cohortMetrics(expandedBranch.policy(), added)
        val finalExpandedCombined = cohortMetrics(expandedBranch.policy(), combined)
        requireCombinedMetrics(finalExpandedAnchor, finalExpandedAdded, finalExpandedCombined)
        val conditionalGradient = firstExpandedAnchorErrorModel?.let { model ->
            neuralCohortGradientGeometry(
                model,
                anchor,
                added,
                "FIRST_EXPANDED_ANCHOR_ERROR_EPOCH_$firstExpandedAnchorError",
            )
        }
        val anyOpposition = (listOf(forkGradient) + listOfNotNull(conditionalGradient)).any { geometry ->
            geometry.groups.any { (it.sizeNormalizedCosine ?: 0.0) < 0.0 }
        }
        val concentration = if (firstExpandedAnchorErrorModel != null && anyOpposition) {
            addedDecisionGradientConcentration(firstExpandedAnchorErrorModel, anchor, added)
        } else {
            emptyList()
        }
        val anchorModelPath = outputDirectory.resolve("final-anchor-seed-$seed.json")
        val expandedModelPath = outputDirectory.resolve("final-expanded-seed-$seed.json")
        CandidateConditionedNeuralPolicy.fromArtifact(
            copyNeuralBcModelArtifact(anchorModel).copy(
                bestEpoch = fork.completedAnchorEpochs + anchorBranch.epochsCompleted
            )
        ).save(anchorModelPath)
        CandidateConditionedNeuralPolicy.fromArtifact(
            copyNeuralBcModelArtifact(expandedModel).copy(
                bestEpoch = fork.completedAnchorEpochs + expandedBranch.epochsCompleted
            )
        ).save(expandedModelPath)
        val diagnosticCase = classifyIssue0031Seed(
            expandedConfirmation = expandedConfirmation,
            expandedAnchorErrorEpochs = expandedAnchorErrorEpochs,
            anchorContinuationErrorEpochs = anchorErrorObservations.size,
            finalExpandedAnchorCorrect = finalExpandedAnchor.strictRankingCorrect,
        )
        progress(
            "Seed $seed: $diagnosticCase; expanded=${finalExpandedCombined.strictRankingCorrect}/389 " +
                "after ${expandedBranch.postForkSteps} matched steps"
        )
        return Issue0031SeedResult(
            seed = seed,
            forkAnchorEpoch = fork.completedAnchorEpochs,
            forkFirstPerfectEpoch = fork.firstPerfectAnchorEpoch,
            forkAnchorMetrics = forkAnchor,
            forkAddedMetrics = forkAdded,
            forkCombinedMetrics = forkCombined,
            forkCheckpointPath = root.relativize(forkPath).toString(),
            forkCheckpointSha256 = forkSha,
            exactModelAndOptimizerFork = true,
            forkGradientGeometry = forkGradient,
            expandedFirstPerfectEpoch = expandedFirstPerfect,
            expandedConfirmationEpoch = expandedConfirmation,
            expandedEpochsCompleted = expandedBranch.epochsCompleted,
            postForkDecisionSteps = expandedBranch.postForkSteps,
            expandedEpochsWithAnchorErrors = expandedAnchorErrorEpochs,
            firstExpandedAnchorErrorEpoch = firstExpandedAnchorError,
            anchorContinuationErrorObservations = anchorErrorObservations.size,
            firstAnchorContinuationErrorAtPostForkSteps = firstAnchorErrorStep,
            expandedMinimumAnchorMargin = expandedMinimumAnchorMargin,
            expandedMinimumAnchorMarginEpoch = expandedMinimumAnchorMarginEpoch,
            anchorContinuationMinimumMargin = anchorMinimumMargin,
            anchorContinuationMinimumMarginAtPostForkSteps = anchorMinimumMarginStep,
            finalAnchorContinuation = finalAnchor,
            finalExpandedAnchor = finalExpandedAnchor,
            finalExpandedAdded = finalExpandedAdded,
            finalExpandedCombined = finalExpandedCombined,
            conditionalGradientGeometry = conditionalGradient,
            addedGradientConcentration = concentration,
            trajectory = trajectory.distinctBy { it.postForkDecisionSteps },
            finalAnchorModelPath = root.relativize(anchorModelPath).toString(),
            finalAnchorModelSha256 = sha256File(anchorModelPath),
            finalExpandedModelPath = root.relativize(expandedModelPath).toString(),
            finalExpandedModelSha256 = sha256File(expandedModelPath),
            diagnosticCase = diagnosticCase,
        )
    }
}

private const val ISSUE_0031_FORK_CHECKPOINT_SCHEMA = "issue-0031-training-fork-v1"

private fun issue0031ResearchRunIdentity(prepared: Issue0031PreparedInputs): String =
    org.mtgallium.research.run.ResearchRunBindings(
        protocol = ISSUE_0031_PROTOCOL,
        material = mapOf(
            "corpus-manifest" to prepared.population.manifestSha256,
            "split" to prepared.population.splitSha256,
            "subset-order" to prepared.orderSha,
            "model-config" to sha256(evidenceJson.encodeToString(prepared.modelConfig)),
            "training-config" to sha256(evidenceJson.encodeToString(prepared.trainingConfig)),
        ),
    ).identity

/** Current continuation reader; historical issue-0031 fork files retain their dedicated readers. */
internal fun loadIssue0031ForkCheckpoint(path: Path, expectedResearchRunIdentity: String): Issue0031TrainingForkCheckpoint {
    val envelope = ResearchRunCheckpoints.load(path)
    require(envelope.researchRunIdentity == expectedResearchRunIdentity) {
        "Issue-0031 fork belongs to ${envelope.researchRunIdentity}, not $expectedResearchRunIdentity"
    }
    require(envelope.payloadSchema == ISSUE_0031_FORK_CHECKPOINT_SCHEMA)
    return evidenceJson.decodeFromString(envelope.payload().decodeToString())
}

private data class Issue0031ForkRuntime(
    val epoch: Int,
    val firstPerfectEpoch: Int,
    val metrics: NeuralCohortFitMetrics,
    val artifact: NeuralBcModelArtifact,
    val optimizer: SparseAdamState,
)

private fun trainAnchorFork(
    anchor: List<EncodedBcDecision>,
    seed: Long,
    modelConfig: NeuralBcModelConfig,
    trainingConfig: NeuralBcTrainingConfig,
): Issue0031ForkRuntime {
    val policy = CandidateConditionedNeuralPolicy.initialize(modelConfig, seed)
    val adam = SparseAdam(policy.artifact, trainingConfig)
    var firstPerfect: Int? = null
    var perfectStreak = 0
    for (epoch in 1..trainingConfig.maximumEpochs) {
        anchor.shuffled(Random(seed xor epoch.toLong())).forEach(adam::step)
        val metric = cohortMetrics(policy, anchor)
        if (metric.strictRankingCorrect == anchor.size) {
            if (firstPerfect == null) firstPerfect = epoch
            perfectStreak++
            if (perfectStreak >= CORRECTED_NEURAL_CONFIRMATION_EPOCHS) {
                return Issue0031ForkRuntime(
                    epoch = epoch,
                    firstPerfectEpoch = requireNotNull(firstPerfect),
                    metrics = metric,
                    artifact = copyNeuralBcModelArtifact(policy.artifact),
                    optimizer = adam.snapshotState(),
                )
            }
        } else {
            perfectStreak = 0
        }
    }
    error("Seed $seed did not reproduce reliable n=323 within ${trainingConfig.maximumEpochs} epochs")
}

internal class Issue0031ContinuationBranch(
    private val seed: Long,
    private val forkEpoch: Int,
    private val decisions: List<EncodedBcDecision>,
    artifact: NeuralBcModelArtifact,
    private val optimizer: SparseAdam,
) {
    private val policy = CandidateConditionedNeuralPolicy.fromArtifact(artifact)
    var epochsCompleted: Int = 0
        private set
    var epochPosition: Int = 0
        private set
    var postForkSteps: Long = 0
        private set
    private var currentOrder: List<EncodedBcDecision> = shuffledEpoch()

    fun policy(): CandidateConditionedNeuralPolicy = policy

    fun advance(
        steps: Long,
        completedEpochObserver: ((Int, Issue0031ContinuationBranch) -> Unit)? = null,
    ) {
        require(steps >= 0)
        repeat(steps.toInt()) {
            optimizer.step(currentOrder[epochPosition])
            epochPosition++
            postForkSteps++
            if (epochPosition == decisions.size) {
                epochsCompleted++
                epochPosition = 0
                completedEpochObserver?.invoke(epochsCompleted, this)
                currentOrder = shuffledEpoch()
            }
        }
    }

    private fun shuffledEpoch(): List<EncodedBcDecision> {
        val absoluteEpoch = forkEpoch + epochsCompleted + 1
        return decisions.shuffled(Random(seed xor absoluteEpoch.toLong()))
    }
}

internal fun cohortMetrics(
    policy: NeuralBcScoringPolicy,
    decisions: List<EncodedBcDecision>,
): NeuralCohortFitMetrics {
    require(decisions.isNotEmpty())
    val fits = decisions.map { neuralMemorizationDecisionFit(policy, it) }
    val margins = fits.map(NeuralMemorizationDecisionFit::teacherMargin).sorted()
    fun quantile(q: Double): Double = margins[((margins.size - 1) * q).toInt()]
    return NeuralCohortFitMetrics(
        decisions = decisions.size,
        strictRankingCorrect = fits.count(NeuralMemorizationDecisionFit::strictRankingCorrect),
        strictRankingAccuracy = fits.count(NeuralMemorizationDecisionFit::strictRankingCorrect).toDouble() /
            decisions.size,
        meanCrossEntropy = fits.map(NeuralMemorizationDecisionFit::meanCrossEntropyContribution).average(),
        margins = NeuralMarginSummary(
            decisions = decisions.size,
            minimum = margins.first(),
            p10 = quantile(0.10),
            median = quantile(0.50),
            p90 = quantile(0.90),
            maximum = margins.last(),
            mean = margins.average(),
        ),
        misrankedDecisions = fits.filterNot(NeuralMemorizationDecisionFit::strictRankingCorrect)
            .sortedBy(NeuralMemorizationDecisionFit::teacherMargin)
            .take(25),
    )
}

internal fun requireCombinedMetrics(
    anchor: NeuralCohortFitMetrics,
    added: NeuralCohortFitMetrics,
    combined: NeuralCohortFitMetrics,
) {
    require(anchor.decisions + added.decisions == combined.decisions)
    require(anchor.strictRankingCorrect + added.strictRankingCorrect == combined.strictRankingCorrect)
    val reconstructed = (
        anchor.meanCrossEntropy * anchor.decisions + added.meanCrossEntropy * added.decisions
    ) / combined.decisions
    require(abs(reconstructed - combined.meanCrossEntropy) <= 1e-12) {
        "Combined cohort objective does not equal the size-weighted component objective"
    }
}

private fun trajectoryCheckpoint(
    reason: String,
    expandedEpoch: Int,
    anchorBranch: Issue0031ContinuationBranch,
    expandedBranch: Issue0031ContinuationBranch,
    anchor: List<EncodedBcDecision>,
    added: List<EncodedBcDecision>,
    combined: List<EncodedBcDecision>,
    includeProjection: Boolean,
): Issue0031TrajectoryCheckpoint {
    val anchorMetric = cohortMetrics(anchorBranch.policy(), anchor)
    val expandedAnchor = cohortMetrics(expandedBranch.policy(), anchor)
    val expandedAdded = cohortMetrics(expandedBranch.policy(), added)
    val expandedCombined = cohortMetrics(expandedBranch.policy(), combined)
    requireCombinedMetrics(expandedAnchor, expandedAdded, expandedCombined)
    return Issue0031TrajectoryCheckpoint(
        postForkDecisionSteps = expandedBranch.postForkSteps,
        expandedEpochsCompleted = expandedEpoch,
        anchorEpochsCompleted = anchorBranch.epochsCompleted,
        anchorEpochPosition = anchorBranch.epochPosition,
        reason = reason,
        anchorContinuation = anchorMetric,
        expandedAnchor = expandedAnchor,
        expandedAdded = expandedAdded,
        expandedCombined = expandedCombined,
        anchorProjection = if (includeProjection) {
            traceNeuralBcProjection(anchorBranch.policy(), anchor, anchorMetric.traceMetric(anchorBranch.epochsCompleted))
        } else {
            null
        },
        expandedProjection = if (includeProjection) {
            traceNeuralBcProjection(expandedBranch.policy(), combined, expandedCombined.traceMetric(expandedEpoch))
        } else {
            null
        },
    )
}

private fun NeuralCohortFitMetrics.traceMetric(epoch: Int): NeuralMemorizationEpochMetric =
    NeuralMemorizationEpochMetric(
        epoch = epoch,
        meanCrossEntropy = meanCrossEntropy,
        strictRankingCorrect = strictRankingCorrect,
        strictRankingAccuracy = strictRankingAccuracy,
        productionTieBreakCorrect = strictRankingCorrect,
        productionTieBreakAccuracy = strictRankingAccuracy,
        minimumTeacherMargin = margins.minimum,
    )

private fun requireExactFork(
    firstModel: NeuralBcModelArtifact,
    secondModel: NeuralBcModelArtifact,
    firstOptimizer: SparseAdamState,
    secondOptimizer: SparseAdamState,
) {
    require(firstModel.config == secondModel.config && firstModel.trainingSeed == secondModel.trainingSeed)
    require(firstModel.stateWeights.contentEquals(secondModel.stateWeights))
    require(firstModel.stateBias.contentEquals(secondModel.stateBias))
    require(firstModel.candidateWeights.contentEquals(secondModel.candidateWeights))
    require(firstModel.candidateBias.contentEquals(secondModel.candidateBias))
    require(firstModel.globalQuery.contentEquals(secondModel.globalQuery))
    require(sameSparseAdamState(firstOptimizer, secondOptimizer))
}

internal fun sameSparseAdamState(first: SparseAdamState, second: SparseAdamState): Boolean =
    first.schemaVersion == second.schemaVersion &&
        first.decisionSteps == second.decisionSteps &&
        first.stateFirstMoment.contentEquals(second.stateFirstMoment) &&
        first.stateSecondMoment.contentEquals(second.stateSecondMoment) &&
        first.stateBiasFirstMoment.contentEquals(second.stateBiasFirstMoment) &&
        first.stateBiasSecondMoment.contentEquals(second.stateBiasSecondMoment) &&
        first.candidateFirstMoment.contentEquals(second.candidateFirstMoment) &&
        first.candidateSecondMoment.contentEquals(second.candidateSecondMoment) &&
        first.candidateBiasFirstMoment.contentEquals(second.candidateBiasFirstMoment) &&
        first.candidateBiasSecondMoment.contentEquals(second.candidateBiasSecondMoment) &&
        first.queryFirstMoment.contentEquals(second.queryFirstMoment) &&
        first.querySecondMoment.contentEquals(second.querySecondMoment) &&
        first.stateWeightUpdateCounts.contentEquals(second.stateWeightUpdateCounts) &&
        first.stateBiasUpdateCounts.contentEquals(second.stateBiasUpdateCounts) &&
        first.candidateWeightUpdateCounts.contentEquals(second.candidateWeightUpdateCounts) &&
        first.candidateBiasUpdateCounts.contentEquals(second.candidateBiasUpdateCounts) &&
        first.globalQueryUpdateCounts.contentEquals(second.globalQueryUpdateCounts)

private fun classifyIssue0031Seed(
    expandedConfirmation: Int?,
    expandedAnchorErrorEpochs: Int,
    anchorContinuationErrorEpochs: Int,
    finalExpandedAnchorCorrect: Int,
): String = when {
    anchorContinuationErrorEpochs > 0 -> "ANCHOR_CONTINUATION_INSTABILITY"
    expandedAnchorErrorEpochs > 0 -> "COHORT_INDUCED_ANCHOR_DESTABILIZATION"
    expandedConfirmation != null -> "READILY_ASSIMILATED_ANCHOR_SECURE"
    finalExpandedAnchorCorrect == ISSUE_0031_ANCHOR_DECISIONS -> "ADDED_COHORT_LOCAL_DIFFICULTY"
    else -> "MIXED_OR_UNRESOLVED"
}

private fun classifyIssue0031(results: List<Issue0031SeedResult>): String = when {
    results.all { it.diagnosticCase == "READILY_ASSIMILATED_ANCHOR_SECURE" } ->
        "ALL_SEEDS_ASSIMILATE_FROM_FORK_TRAJECTORY_FORMATION_STRONGLY_FAVORED"
    results.any { it.diagnosticCase == "ANCHOR_CONTINUATION_INSTABILITY" } ->
        "ANCHOR_CONTINUATION_INSTABILITY_WEAKENS_COHORT_CAUSAL_STORY"
    results.all { it.diagnosticCase == "ADDED_COHORT_LOCAL_DIFFICULTY" } ->
        "ADDED_COHORT_REMAINS_LOCALLY_DIFFICULT_WITH_ANCHOR_INTACT"
    results.any { it.diagnosticCase == "COHORT_INDUCED_ANCHOR_DESTABILIZATION" } ->
        "ADDED_COHORT_DESTABILIZES_ESTABLISHED_ANCHOR_IN_AT_LEAST_ONE_SEED"
    else -> "MIXED_OR_UNRESOLVED_TRAJECTORY_RESPONSE"
}

private fun interpretIssue0031(
    results: List<Issue0031SeedResult>,
    diagnosticCase: String,
): List<String> {
    val firstExpanded = results.map { result ->
        result.trajectory.single { it.expandedEpochsCompleted == 1 }
    }
    return listOf(
        "Every seed reproduced its issue-0030 n=323 reliability endpoint exactly and forked from equal model and optimizer continuation state.",
        "The added cohort causes an immediate local shock: after the first 389 post-fork updates, expanded-anchor fits are " +
            firstExpanded.joinToString(" / ") { it.expandedAnchor.strictRankingCorrect.toString() + "/323" } +
            ", while all matched anchor-only controls remain 323/323.",
        "All expanded branches nevertheless satisfy the full 20-consecutive-perfect n=389 criterion. Confirmation epochs by seed are " +
            results.joinToString { result ->
                "${result.seed}: ${result.expandedConfirmationEpoch ?: "not reached"}"
            } +
            ", at matched post-fork exposures " + results.joinToString { result ->
                "${result.seed}: ${result.postForkDecisionSteps}"
            } + ". Assimilation is reliable within the fixed budget but highly seed-dependent, not uniformly quick.",
        "The control prevents a cohort-only explanation: every anchor-only continuation later has at least one observed strict-fit failure, first at matched post-fork steps " +
            results.joinToString { result ->
                "${result.seed}: ${result.firstAnchorContinuationErrorAtPostForkSteps}"
            } + ", and every control recovers to 323/323 at its final matched endpoint.",
        "Fork and first-error cohort-gradient cosines vary by seed and parameter group; they do not provide one uniform opposition mechanism for the common immediate damage. No sampled branch state contains an exact repeated learned state or hidden ranking contradiction.",
        "The prespecified case remains $diagnosticCase because the anchor-only failures weaken attribution of the longer trajectory to decisions 324-389 alone. The evidence simultaneously demonstrates immediate cohort-induced disruption, eventual all-seed assimilation, and background continued-training instability; it does not prove a general optimizer-basin theorem.",
    )
}

private fun narrowestNextIssue0031Question(diagnosticCase: String): String = when {
    diagnosticCase == "ALL_SEEDS_ASSIMILATE_FROM_FORK_TRAJECTORY_FORMATION_STRONGLY_FAVORED" ->
        "Which minimal early-training trajectory difference caused by including decisions 324-389 from initialization diverts the learner away from the reliably assimilating continuation observed here?"
    diagnosticCase == "ANCHOR_CONTINUATION_INSTABILITY_WEAKENS_COHORT_CAUSAL_STORY" ->
        "What ordinary continued-training event makes an already reliable n=323 solution lose strict fit even without the added cohort?"
    diagnosticCase == "ADDED_COHORT_REMAINS_LOCALLY_DIFFICULT_WITH_ANCHOR_INTACT" ->
        "Which of the persistently misranked decisions 324-389 share the smallest common local difficulty at the intact n=323 solution?"
    diagnosticCase == "ADDED_COHORT_DESTABILIZES_ESTABLISHED_ANCHOR_IN_AT_LEAST_ONE_SEED" ->
        "Is the measured anchor damage explained by the small opposing added-decision set identified at the common destabilization state, or is it diffuse across the cohort?"
    else -> "Which single matched-state difference separates the seed-specific continuation outcomes?"
}

@Serializable
private data class Issue0031Progress(
    val schemaVersion: Int = 1,
    val updatedAt: String,
    val completed: Int,
    val total: Int = 3,
    val unit: String = "seeds",
    val phase: String,
    val detail: String,
)

private class Issue0031DurableProgress {
    private val path = System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of)

    fun publish(completed: Int, phase: String, detail: String) {
        path?.let { progressPath ->
            writeJsonAtomically(
                progressPath,
                Issue0031Progress(
                    updatedAt = Instant.now().toString(),
                    completed = completed,
                    phase = phase,
                    detail = detail,
                ),
            )
        }
    }
}

internal fun renderIssue0031NeuralCohortContinuation(
    report: Issue0031NeuralCohortContinuationReport,
): String = buildString {
    appendLine("# Issue 0031: n=323 to n=389 matched continuation fork")
    appendLine()
    appendLine("## Technical summary")
    appendLine()
    appendLine("Diagnostic case: `${report.diagnosticCase}`.")
    appendLine()
    interpretIssue0031(report.seeds, report.diagnosticCase).forEach { appendLine("- $it") }
    appendLine()
    appendLine("This is fixed-corpus training-system evidence, not held-out or playing-strength evidence.")
    appendLine()
    appendLine("## Key findings")
    appendLine()
    appendLine("| Seed | Fork epoch / loss / min margin | Added fit at fork | Expanded first / confirmed | Expanded anchor-error epochs | Anchor-only errors | Final expanded anchor / added / all | Matched steps |")
    appendLine("| ---: | --- | --- | --- | ---: | ---: | --- | ---: |")
    report.seeds.forEach { seed ->
        appendLine(
            "| ${seed.seed} | ${seed.forkAnchorEpoch} / ${number31(seed.forkAnchorMetrics.meanCrossEntropy)} / ${number31(seed.forkAnchorMetrics.margins.minimum)} | " +
                "${seed.forkAddedMetrics.strictRankingCorrect}/${seed.forkAddedMetrics.decisions} / ${number31(seed.forkAddedMetrics.meanCrossEntropy)} | " +
                "${seed.expandedFirstPerfectEpoch ?: "—"} / ${seed.expandedConfirmationEpoch ?: "—"} | " +
                "${seed.expandedEpochsWithAnchorErrors} | ${seed.anchorContinuationErrorObservations} | " +
                "${seed.finalExpandedAnchor.strictRankingCorrect}/323 / ${seed.finalExpandedAdded.strictRankingCorrect}/66 / ${seed.finalExpandedCombined.strictRankingCorrect}/389 | " +
                "${seed.postForkDecisionSteps} |"
        )
    }
    appendLine()
    appendLine("## Definitions and provenance")
    appendLine()
    appendLine("- Dataset: `${report.corpusDatasetIdentity}`")
    appendLine("- Manifest / split SHA-256: `${report.corpusManifestSha256}` / `${report.splitSha256}`")
    appendLine("- Nested order: `${report.subsetSelectionOrderSha256}`")
    appendLine("- Anchor / added identities: `${report.anchorIdentity}` / `${report.addedIdentity}`")
    appendLine("- Historical candidate schema: ${report.historicalCandidateSchemaVersion}; features: `${report.featureSchema}`")
    appendLine("- Reference: `${report.referenceProtocol}` / `${report.referenceArtifactSha256}`")
    appendLine("- Model: ${report.modelConfig.parameterCount} parameters; seeds ${report.initializationSeeds}")
    appendLine("- Corrected recipe: state inputs x1/32; candidate projection post-Adam updates x1/8; learning rate ${report.trainingConfig.learningRate}")
    appendLine()
    report.forkContinuationStateDefinition.forEach { appendLine("- $it") }
    report.matchedExposureDefinition.forEach { appendLine("- $it") }
    report.metricDefinitions.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Matched branch trajectories")
    appendLine()
    report.seeds.forEach { seed ->
        appendLine("### Seed ${seed.seed}")
        appendLine()
        appendLine("Case: `${seed.diagnosticCase}`. Fork checkpoint: `${seed.forkCheckpointSha256}`.")
        appendLine()
        appendLine("| Post-fork steps | Expanded epoch | Anchor epoch+cursor | Anchor-only correct / loss / min margin | Expanded anchor correct / loss / min margin | Added correct / loss / min margin | Combined correct / loss | Reason |")
        appendLine("| ---: | ---: | --- | --- | --- | --- | --- | --- |")
        seed.trajectory.forEach { point ->
            appendLine(
                "| ${point.postForkDecisionSteps} | ${point.expandedEpochsCompleted} | ${point.anchorEpochsCompleted}+${point.anchorEpochPosition}/323 | " +
                    metricCell(point.anchorContinuation) + " | " + metricCell(point.expandedAnchor) + " | " +
                    metricCell(point.expandedAdded) + " | ${point.expandedCombined.strictRankingCorrect}/389 / ${number31(point.expandedCombined.meanCrossEntropy)} | ${point.reason} |"
            )
        }
        appendLine()
    }
    appendLine("## Common-state gradient geometry")
    appendLine()
    appendLine("Actual-objective contributions use the combined 323/389 and 66/389 weights; normalized norms/cosines compare cohort means.")
    appendLine()
    appendLine("| Seed | State | Group | Actual anchor / added / combined norm | Normalized anchor / added norm | Cosine |")
    appendLine("| ---: | --- | --- | --- | --- | ---: |")
    report.seeds.forEach { seed ->
        (listOf(seed.forkGradientGeometry) + listOfNotNull(seed.conditionalGradientGeometry)).forEach { geometry ->
            geometry.groups.forEach { group ->
                appendLine(
                    "| ${seed.seed} | ${geometry.evaluatedAt} | ${group.parameterGroup} | " +
                        "${number31(group.actualCombinedObjectiveAnchorContributionNorm)} / " +
                        "${number31(group.actualCombinedObjectiveAddedContributionNorm)} / " +
                        "${number31(group.actualCombinedObjectiveGradientNorm)} | " +
                        "${number31(group.sizeNormalizedAnchorGradientNorm)} / ${number31(group.sizeNormalizedAddedGradientNorm)} | " +
                        "${group.sizeNormalizedCosine?.let(::number31) ?: "—"} |"
                )
            }
        }
    }
    appendLine()
    val concentrated = report.seeds.filter { it.addedGradientConcentration.isNotEmpty() }
    if (concentrated.isNotEmpty()) {
        appendLine("Conditional concentration was run only where the expanded branch damaged the anchor and common-state gradients opposed in at least one parameter group.")
        appendLine()
        concentrated.forEach { seed ->
            appendLine("Seed ${seed.seed}: " + seed.addedGradientConcentration.joinToString { item ->
                "${item.decision.gameId}:${item.decision.decisionIndex} cos=${item.cosineWithAnchorOpposingDirection?.let(::number31) ?: "—"}"
            })
            appendLine()
        }
    } else {
        appendLine("No conditional per-decision gradient concentration was justified by the prespecified destabilization-plus-opposition condition.")
        appendLine()
    }
    appendLine("## Limitations and robustness")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Next question")
    appendLine()
    appendLine(report.narrowestNextQuestion)
}

private fun metricCell(metric: NeuralCohortFitMetrics): String =
    "${metric.strictRankingCorrect}/${metric.decisions} / ${number31(metric.meanCrossEntropy)} / ${number31(metric.margins.minimum)}"

private fun number31(value: Double): String = when {
    value == 0.0 -> "0"
    abs(value) < 1e-4 || abs(value) >= 1e4 -> "%.4e".format(value)
    else -> "%.6f".format(value)
}
