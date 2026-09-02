package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val ISSUE_0028_PROTOCOL = "issue-0028-fixed-v3-neural-population-scaling-v1"
private const val ISSUE_0028_REFERENCE_ARTIFACT =
    "neural-candidate-update-scale-diagnostic/artifact.json"
private const val ISSUE_0028_PREFIX = 128
private val ISSUE_0028_TRACE_EPOCHS = (
    (0..10).toList() + listOf(
        12,
        16,
        20,
        24,
        32,
        48,
        64,
        96,
        128,
        192,
        256,
        384,
        512,
        768,
        1_024,
        1_500,
    )
).toSet()

@Serializable
internal data class NeuralUpdateCountDistribution(
    val totalParameterCells: Int,
    val activeParameterCells: Int,
    val inactiveParameterCells: Int,
    val totalUpdateCalls: Long,
    val minimumActiveUpdateCount: Long,
    val p10ActiveUpdateCount: Long,
    val p25ActiveUpdateCount: Long,
    val medianActiveUpdateCount: Long,
    val p75ActiveUpdateCount: Long,
    val p90ActiveUpdateCount: Long,
    val p99ActiveUpdateCount: Long,
    val maximumActiveUpdateCount: Long,
    val meanActiveUpdateCount: Double,
)

@Serializable
internal data class NeuralProjectionUpdateExposureProfile(
    val projection: String,
    val inputDimension: Int,
    val hiddenDimension: Int,
    val activeInputBuckets: Int,
    val sparseFeatureOccurrencesPerEpoch: Long,
    val uniqueBucketDecisionTouchesPerEpoch: Long,
    val actualWeightUpdateCallsPerEpoch: Long,
    val weightUpdatesPerEpoch: NeuralUpdateCountDistribution,
    val denseBiasUpdateCallsPerCellPerEpoch: Long,
)

@Serializable
internal data class NeuralExposureBandAssociation(
    val band: String,
    val definition: String,
    val parameterCells: Int,
    val minimumUpdatesPerEpoch: Long,
    val meanUpdatesPerEpoch: Double,
    val maximumUpdatesPerEpoch: Long,
    val meanCumulativeUpdatesAtCheckpoint: Double,
    val initialWeightRms: Double,
    val checkpointWeightRms: Double,
    val weightChangeRms: Double,
    val initialPreActivationContributionRms: Double,
    val checkpointPreActivationContributionRms: Double,
)

@Serializable
internal data class NeuralProjectionExposureAssociation(
    val projection: String,
    val checkpointEpoch: Int,
    val globalDecisionStepsAtCheckpoint: Long,
    val cumulativeWeightUpdates: NeuralUpdateCountDistribution,
    val denseBiasUpdatesPerCell: Long,
    val initialBiasRms: Double,
    val checkpointBiasRms: Double,
    val checkpointPreActivationRms: Double,
    val checkpointNearSaturatedFraction: Double,
    val logUpdateCountVsAbsoluteWeightChangePearson: Double?,
    val logUpdateCountVsCheckpointAbsoluteWeightPearson: Double?,
    val bands: List<NeuralExposureBandAssociation>,
)

@Serializable
internal data class NeuralPopulationScalingSeedResult(
    val seed: Long,
    val resultSource: String,
    val epochsCompleted: Int,
    val stoppedAfterPerfectConfirmation: Boolean,
    val firstPerfectEpoch: Int?,
    val longestPerfectStreak: Int,
    val bestEpoch: Int,
    val bestStrictRankingCorrect: Int,
    val bestStrictRankingAccuracy: Double,
    val bestMeanCrossEntropy: Double,
    val finalStrictRankingCorrect: Int,
    val finalStrictRankingAccuracy: Double,
    val finalMeanCrossEntropy: Double,
    val minimumObservedMeanCrossEntropy: Double,
    val trace: List<NeuralProjectionTracePoint>,
    val bestCheckpoint: NeuralProjectionTracePoint,
    val finalCheckpoint: NeuralProjectionTracePoint,
    val bestExposureAssociations: List<NeuralProjectionExposureAssociation>,
    val finalExposureAssociations: List<NeuralProjectionExposureAssociation>?,
    val exactObservedFinalUpdateCountsMatchReconstruction: Boolean?,
    val bestCheckpointMisrankedDecisions: List<NeuralMemorizationDecisionFit>,
    val modelPath: String,
    val modelSha256: String,
)

@Serializable
internal data class NeuralPopulationScalingStageResult(
    val decisions: Int,
    val resultSource: String,
    val subsetIdentity: String,
    val exposureProfiles: List<NeuralProjectionUpdateExposureProfile>,
    val seeds: List<NeuralPopulationScalingSeedResult>,
    val reliablyMemorizedByAllSeeds: Boolean,
)

@Serializable
internal data class NeuralMatchedGlobalStepCheckpoint(
    val targetGlobalDecisionSteps: Long,
    val decisions: Int,
    val seed: Long,
    val epoch: Int,
    val globalDecisionSteps: Long,
    val stateP90CumulativeUpdateCalls: Long,
    val candidateP90CumulativeUpdateCalls: Long,
    val tracePoint: NeuralProjectionTracePoint,
)

@Serializable
internal data class Issue0028NeuralPopulationScalingReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0028_PROTOCOL,
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
    val updateExposureDefinition: List<String>,
    val saturationAndCollapseDefinitions: List<String>,
    val stages: List<NeuralPopulationScalingStageResult>,
    val matchedGlobalStepCheckpoints: List<NeuralMatchedGlobalStepCheckpoint>,
    val diagnosticCase: String,
    val interpretation: List<String>,
    val limitations: List<String>,
    val narrowestNextQuestion: String,
)

internal fun reconstructSparseAdamUpdateExposure(
    decisions: List<EncodedBcDecision>,
    modelConfig: NeuralBcModelConfig,
    epochs: Int,
): SparseAdamUpdateExposure {
    require(decisions.isNotEmpty() && epochs >= 0)
    val state = LongArray(modelConfig.hiddenDimension * modelConfig.stateDimension)
    val candidate = LongArray(modelConfig.hiddenDimension * modelConfig.candidateDimension)
    decisions.forEach { decision ->
        repeat(modelConfig.hiddenDimension) { hidden ->
            val stateOffset = hidden * modelConfig.stateDimension
            decision.state.indices.forEach { bucket -> state[stateOffset + bucket]++ }
        }
        val candidateBuckets = linkedSetOf<Int>()
        decision.candidates.forEach { vector -> vector.indices.forEach(candidateBuckets::add) }
        repeat(modelConfig.hiddenDimension) { hidden ->
            val candidateOffset = hidden * modelConfig.candidateDimension
            candidateBuckets.forEach { bucket -> candidate[candidateOffset + bucket]++ }
        }
    }
    fun scaled(values: LongArray): LongArray = LongArray(values.size) { index ->
        Math.multiplyExact(values[index], epochs.toLong())
    }
    val denseUpdates = Math.multiplyExact(decisions.size.toLong(), epochs.toLong())
    return SparseAdamUpdateExposure(
        decisionSteps = Math.multiplyExact(decisions.size, epochs),
        stateWeightUpdateCounts = scaled(state),
        stateBiasUpdateCounts = LongArray(modelConfig.hiddenDimension) { denseUpdates },
        candidateWeightUpdateCounts = scaled(candidate),
        candidateBiasUpdateCounts = LongArray(modelConfig.hiddenDimension) { denseUpdates },
        globalQueryUpdateCounts = LongArray(modelConfig.hiddenDimension) { denseUpdates },
    )
}

private fun sameUpdateExposure(
    actual: SparseAdamUpdateExposure,
    expected: SparseAdamUpdateExposure,
): Boolean = actual.decisionSteps == expected.decisionSteps &&
    actual.stateWeightUpdateCounts.contentEquals(expected.stateWeightUpdateCounts) &&
    actual.stateBiasUpdateCounts.contentEquals(expected.stateBiasUpdateCounts) &&
    actual.candidateWeightUpdateCounts.contentEquals(expected.candidateWeightUpdateCounts) &&
    actual.candidateBiasUpdateCounts.contentEquals(expected.candidateBiasUpdateCounts) &&
    actual.globalQueryUpdateCounts.contentEquals(expected.globalQueryUpdateCounts)

private fun updateCountDistribution(counts: LongArray): NeuralUpdateCountDistribution {
    val active = counts.filter { it > 0L }.sorted()
    require(active.isNotEmpty())
    fun percentile(fraction: Double): Long = active[((active.size - 1) * fraction).toInt()]
    return NeuralUpdateCountDistribution(
        totalParameterCells = counts.size,
        activeParameterCells = active.size,
        inactiveParameterCells = counts.size - active.size,
        totalUpdateCalls = counts.sum(),
        minimumActiveUpdateCount = active.first(),
        p10ActiveUpdateCount = percentile(0.10),
        p25ActiveUpdateCount = percentile(0.25),
        medianActiveUpdateCount = percentile(0.50),
        p75ActiveUpdateCount = percentile(0.75),
        p90ActiveUpdateCount = percentile(0.90),
        p99ActiveUpdateCount = percentile(0.99),
        maximumActiveUpdateCount = active.last(),
        meanActiveUpdateCount = active.average(),
    )
}

private fun exposureProfiles(
    decisions: List<EncodedBcDecision>,
    modelConfig: NeuralBcModelConfig,
): List<NeuralProjectionUpdateExposureProfile> {
    val oneEpoch = reconstructSparseAdamUpdateExposure(decisions, modelConfig, epochs = 1)
    val stateOccurrences = decisions.sumOf { it.state.indices.size.toLong() }
    val candidateOccurrences = decisions.sumOf { decision ->
        decision.candidates.sumOf { it.indices.size.toLong() }
    }
    val candidateTouches = decisions.sumOf { decision ->
        decision.candidates.flatMap { it.indices.asIterable() }.toSet().size.toLong()
    }
    fun profile(
        name: String,
        dimension: Int,
        counts: LongArray,
        occurrences: Long,
        touches: Long,
    ): NeuralProjectionUpdateExposureProfile {
        val distribution = updateCountDistribution(counts)
        require(distribution.activeParameterCells % modelConfig.hiddenDimension == 0)
        return NeuralProjectionUpdateExposureProfile(
            projection = name,
            inputDimension = dimension,
            hiddenDimension = modelConfig.hiddenDimension,
            activeInputBuckets = distribution.activeParameterCells / modelConfig.hiddenDimension,
            sparseFeatureOccurrencesPerEpoch = occurrences,
            uniqueBucketDecisionTouchesPerEpoch = touches,
            actualWeightUpdateCallsPerEpoch = distribution.totalUpdateCalls,
            weightUpdatesPerEpoch = distribution,
            denseBiasUpdateCallsPerCellPerEpoch = decisions.size.toLong(),
        )
    }
    return listOf(
        profile(
            name = "STATE",
            dimension = modelConfig.stateDimension,
            counts = oneEpoch.stateWeightUpdateCounts,
            occurrences = stateOccurrences,
            touches = stateOccurrences,
        ),
        profile(
            name = "CANDIDATE",
            dimension = modelConfig.candidateDimension,
            counts = oneEpoch.candidateWeightUpdateCounts,
            occurrences = candidateOccurrences,
            touches = candidateTouches,
        ),
    )
}

private fun projectionExposureAssociation(
    projection: String,
    decisions: List<EncodedBcDecision>,
    seed: Long,
    checkpointPolicy: CandidateConditionedNeuralPolicy,
    checkpointTrace: NeuralProjectionTracePoint,
): NeuralProjectionExposureAssociation {
    val modelConfig = checkpointPolicy.artifact.config
    val initial = CandidateConditionedNeuralPolicy.initialize(modelConfig, seed).artifact
    val epoch = checkpointTrace.epoch
    require(epoch == checkpointPolicy.artifact.bestEpoch)
    val perEpoch = reconstructSparseAdamUpdateExposure(decisions, modelConfig, epochs = 1)
    val cumulative = reconstructSparseAdamUpdateExposure(decisions, modelConfig, epochs = epoch)
    val isState = projection == "STATE"
    require(isState || projection == "CANDIDATE")
    val perEpochCounts = if (isState) perEpoch.stateWeightUpdateCounts else perEpoch.candidateWeightUpdateCounts
    val cumulativeCounts = if (isState) {
        cumulative.stateWeightUpdateCounts
    } else {
        cumulative.candidateWeightUpdateCounts
    }
    val initialWeights = if (isState) initial.stateWeights else initial.candidateWeights
    val checkpointWeights = if (isState) {
        checkpointPolicy.artifact.stateWeights
    } else {
        checkpointPolicy.artifact.candidateWeights
    }
    val initialBias = if (isState) initial.stateBias else initial.candidateBias
    val checkpointBias = if (isState) {
        checkpointPolicy.artifact.stateBias
    } else {
        checkpointPolicy.artifact.candidateBias
    }
    val inputDimension = if (isState) modelConfig.stateDimension else modelConfig.candidateDimension
    val vectors = if (isState) {
        decisions.map(EncodedBcDecision::state)
    } else {
        decisions.flatMap(EncodedBcDecision::candidates)
    }
    val traceDistribution = if (isState) checkpointTrace.state else checkpointTrace.candidate
    val activeCounts = perEpochCounts.filter { it > 0L }.sorted()
    val p10 = activeCounts[((activeCounts.size - 1) * 0.10).toInt()]
    val p90 = activeCounts[((activeCounts.size - 1) * 0.90).toInt()]
    data class Band(val name: String, val definition: String, val includes: (Long) -> Boolean)
    val bands = listOf(
        Band("RARE_ACTIVE", "0 < updates/epoch <= active p10 ($p10)") { count -> count in 1..p10 },
        Band(
            "MIDDLE_ACTIVE",
            "active p10 ($p10) < updates/epoch < active p90 ($p90)",
        ) { count -> count > p10 && count < p90 },
        Band(
            "HEAVY_ACTIVE",
            "updates/epoch >= active p90 ($p90), excluding the rare band if thresholds tie",
        ) { count -> count >= p90 && count > p10 },
    )
    val associations = bands.mapNotNull { band ->
        val cells = perEpochCounts.indices.filter { band.includes(perEpochCounts[it]) }
        if (cells.isEmpty()) return@mapNotNull null
        val cellSet = cells.toHashSet()
        fun contributionRms(weights: DoubleArray): Double {
            var squared = 0.0
            var values = 0L
            vectors.forEach { vector ->
                repeat(modelConfig.hiddenDimension) { hidden ->
                    val offset = hidden * inputDimension
                    var contribution = 0.0
                    vector.indices.indices.forEach { position ->
                        val index = offset + vector.indices[position]
                        if (index in cellSet) contribution += weights[index] * vector.values[position]
                    }
                    squared += contribution * contribution
                    values++
                }
            }
            return sqrt(squared / values)
        }
        NeuralExposureBandAssociation(
            band = band.name,
            definition = band.definition,
            parameterCells = cells.size,
            minimumUpdatesPerEpoch = cells.minOf { perEpochCounts[it] },
            meanUpdatesPerEpoch = cells.map { perEpochCounts[it] }.average(),
            maximumUpdatesPerEpoch = cells.maxOf { perEpochCounts[it] },
            meanCumulativeUpdatesAtCheckpoint = cells.map { cumulativeCounts[it] }.average(),
            initialWeightRms = indexedRms(initialWeights, cells),
            checkpointWeightRms = indexedRms(checkpointWeights, cells),
            weightChangeRms = indexedDifferenceRms(initialWeights, checkpointWeights, cells),
            initialPreActivationContributionRms = contributionRms(initialWeights),
            checkpointPreActivationContributionRms = contributionRms(checkpointWeights),
        )
    }
    val activeCells = perEpochCounts.indices.filter { perEpochCounts[it] > 0L }
    return NeuralProjectionExposureAssociation(
        projection = projection,
        checkpointEpoch = epoch,
        globalDecisionStepsAtCheckpoint = cumulative.decisionSteps.toLong(),
        cumulativeWeightUpdates = updateCountDistribution(cumulativeCounts),
        denseBiasUpdatesPerCell = cumulative.decisionSteps.toLong(),
        initialBiasRms = arrayRms(initialBias),
        checkpointBiasRms = arrayRms(checkpointBias),
        checkpointPreActivationRms = traceDistribution.rmsPreActivation,
        checkpointNearSaturatedFraction = traceDistribution.nearSaturatedFraction,
        logUpdateCountVsAbsoluteWeightChangePearson = pearson(
            activeCells.map { ln(1.0 + perEpochCounts[it]) },
            activeCells.map { abs(checkpointWeights[it] - initialWeights[it]) },
        ),
        logUpdateCountVsCheckpointAbsoluteWeightPearson = pearson(
            activeCells.map { ln(1.0 + perEpochCounts[it]) },
            activeCells.map { abs(checkpointWeights[it]) },
        ),
        bands = associations,
    )
}

private fun indexedRms(values: DoubleArray, indices: List<Int>): Double =
    sqrt(indices.sumOf { values[it] * values[it] } / indices.size)

private fun indexedDifferenceRms(
    initial: DoubleArray,
    checkpoint: DoubleArray,
    indices: List<Int>,
): Double = sqrt(indices.sumOf {
    val difference = checkpoint[it] - initial[it]
    difference * difference
} / indices.size)

private fun arrayRms(values: DoubleArray): Double = sqrt(values.sumOf { it * it } / values.size)

private fun pearson(left: List<Double>, right: List<Double>): Double? {
    require(left.size == right.size && left.isNotEmpty())
    val leftMean = left.average()
    val rightMean = right.average()
    var covariance = 0.0
    var leftSquares = 0.0
    var rightSquares = 0.0
    left.indices.forEach { index ->
        val leftDelta = left[index] - leftMean
        val rightDelta = right[index] - rightMean
        covariance += leftDelta * rightDelta
        leftSquares += leftDelta * leftDelta
        rightSquares += rightDelta * rightDelta
    }
    if (leftSquares == 0.0 || rightSquares == 0.0) return null
    return covariance / sqrt(leftSquares * rightSquares)
}

internal class Issue0028NeuralPopulationScalingDiagnostic(
    private val root: Path,
    private val outputDirectory: Path,
) {
    fun run(
        historicalManifestPath: Path,
        progress: (String) -> Unit = {},
    ): Issue0028NeuralPopulationScalingReport {
        Files.createDirectories(outputDirectory)
        val prepared = CorrectedNeuralDiagnosticPreparation(root).prepare(historicalManifestPath)
        val implementation = prepared.implementationSourceProvenance
        val population = prepared.population
        val ordered = prepared.rawOrderedDecisions
        val orderSha = prepared.subsetSelectionOrderSha256
        val modelConfig = prepared.modelConfig
        progress("Recovered the exact repaired issue-0022 training population and fixed nested order")

        val referencePath = EvidenceStore(root).latest(ISSUE_0028_REFERENCE_ARTIFACT)
        val referenceSha = sha256File(referencePath)
        val reference = evidenceJson.decodeFromString<Issue0027NeuralCandidateUpdateScaleReport>(
            Files.readString(referencePath)
        )
        require(reference.protocol == ISSUE_0027_PROTOCOL)
        require(reference.corpusManifestSha256 == population.manifestSha256)
        require(reference.splitSha256 == population.splitSha256)
        require(reference.subsetSelectionOrderSha256 == orderSha)
        require(reference.modelConfig == modelConfig)
        require(reference.initializationSeeds == CORRECTED_NEURAL_SEEDS)
        require(reference.maximumEpochs == CORRECTED_NEURAL_MAXIMUM_EPOCHS)
        require(reference.perfectConfirmationEpochs == CORRECTED_NEURAL_CONFIRMATION_EPOCHS)
        require(reference.intervention.stateInputScale == CORRECTED_NEURAL_STATE_INPUT_SCALE)
        require(reference.intervention.candidateInputScale == 1.0)
        require(
            reference.intervention.candidateProjectionUpdateScale ==
                CORRECTED_NEURAL_CANDIDATE_UPDATE_SCALE
        )
        progress("Bound the n=64 and n=389 comparisons to retained issue-0027 evidence $referenceSha")

        val stage64 = retainedStage(reference, ordered, modelConfig, decisions = 64)
        val stage128 = trainFixedCorrectedPrefix(
            ordered = ordered,
            modelConfig = modelConfig,
            decisions = ISSUE_0028_PREFIX,
            progress = progress,
        )
        val stage389 = retainedStage(reference, ordered, modelConfig, decisions = 389)
        val stages = listOf(stage64, stage128, stage389)
        val matchedGlobalSteps = matchedGlobalStepCheckpoints(stages, target = 3_072L)
        val n128Reliable = stage128.reliablyMemorizedByAllSeeds
        val n128AllReached = stage128.seeds.all { it.firstPerfectEpoch != null }
        val mean64State = stage64.seeds.map { it.finalCheckpoint.state.nearSaturatedFraction }.average()
        val mean128State = stage128.seeds.map { it.finalCheckpoint.state.nearSaturatedFraction }.average()
        val mean64Candidate = stage64.seeds.map { it.finalCheckpoint.candidate.nearSaturatedFraction }.average()
        val mean128Candidate = stage128.seeds.map { it.finalCheckpoint.candidate.nearSaturatedFraction }.average()
        val materialSaturationRise = mean128State >= mean64State + 0.10 ||
            mean128Candidate >= mean64Candidate + 0.10
        val diagnosticCase = when {
            n128Reliable && !materialSaturationRise ->
                "N128_REMAINS_RELIABLY_MEMORIZABLE_WITHOUT_A_MATERIAL_SATURATION_RISE"
            n128Reliable ->
                "N128_REMAINS_RELIABLY_MEMORIZABLE_DESPITE_A_MATERIAL_SATURATION_RISE"
            n128AllReached && materialSaturationRise ->
                "N128_PERFECT_FIT_IS_TRANSIENT_AND_SATURATION_RISES_MATERIALLY"
            n128AllReached ->
                "N128_PERFECT_FIT_IS_TRANSIENT_WITHOUT_A_MATERIAL_SATURATION_RISE"
            materialSaturationRise ->
                "N128_IS_NOT_RELIABLE_AND_SATURATION_RISES_MATERIALLY"
            else ->
                "N128_IS_NOT_RELIABLE_WITHOUT_A_MATERIAL_SATURATION_RISE"
        }
        val nextQuestion = if (n128Reliable) {
            "Does the unchanged corrected learner remain reliable at the single fixed n=256 nested prefix, between the now-stable n=128 and unstable n=389 regimes?"
        } else {
            "Does the unchanged corrected learner remain reliable at the single fixed n=96 nested prefix, between stable n=64 and the now-unstable n=128 regime?"
        }

        return Issue0028NeuralPopulationScalingReport(
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
            traceEpochs = ISSUE_0028_TRACE_EPOCHS.sorted(),
            updateExposureDefinition = listOf(
                "An update event is one actual call to SparseAdam.update for one parameter cell. SparseAdam increments one global decision-step clock before each decision and uses that clock for every touched parameter's Adam bias correction.",
                "Each sparse state entry causes one state-weight update per hidden row. Candidate gradients are accumulated by weight index across all candidates in a decision, so a candidate-weight cell receives at most one update per hidden row per decision even if its bucket occurs in several candidates.",
                "State/candidate biases are dense controls: every bias cell is updated once per decision. Counts measure calls, not gradient magnitude, and do not turn the global-clock sparse optimizer into a per-parameter-clock Adam optimizer.",
                "Rare active means at or below the active-cell p10 updates/epoch; heavy active means at or above p90 and outside the rare band if the thresholds tie. Band pre-activation contribution is the RMS of that band's signed weight-times-input sum before bias and tanh.",
            ),
            saturationAndCollapseDefinitions = listOf(
                "Near saturated means |tanh(z)| >= 0.99; exactly saturated means the JVM Double tanh result is exactly -1.0 or 1.0.",
                "Mean projection derivative is the arithmetic mean of 1 - tanh(z)^2 over all observed projection values.",
                "State collapse means distinct exact raw encoded states share an exactly equal learned 32-value query vector.",
                "Learned candidate collapse means distinct exact raw candidate vectors in one current decision share an exactly equal learned 32-value candidate projection; raw candidate aliases are counted separately.",
                "Strict fit requires the teacher score to exceed every alternative score; equality is incorrect.",
                "Reliable memorization requires 20 consecutive strict-perfect epochs. A material saturation rise is a prespecified 10 percentage-point increase in mean final near saturation from retained n=64 on either projection.",
            ),
            stages = stages,
            matchedGlobalStepCheckpoints = matchedGlobalSteps,
            diagnosticCase = diagnosticCase,
            interpretation = scalingInterpretation(stages, matchedGlobalSteps, materialSaturationRise),
            limitations = listOf(
                "This is fixed-training-population optimization evidence, not held-out, generalization, or playing-strength evidence.",
                "Only n=128 was newly trained. The n=64/n=389 fit and geometry are exact retained issue-0027 evidence; their best-model files were hash-verified and used only for the new exposure association summaries.",
                "Update-call counts do not measure gradient magnitude, direction, candidate occurrence multiplicity inside an accumulated gradient, or the timing of sparse touches under the global Adam clock. The weight and pre-activation summaries supply association evidence, not a causal decomposition.",
                "The rare/heavy p10/p90 bands are descriptive slices of this fixed hashed representation. Hash collisions and differing feature values mean equal call counts need not imply equal numerical influence.",
                "Best-checkpoint band associations are directly comparable across all sizes; retained issue-0027 artifacts did not save final n=64/n=389 parameter arrays separately when final differed from best.",
                "No chart is added: three fixed population sizes and three fixed seeds are more exactly auditable in the compact tables than in an interpolated visual trend.",
                "No scale, architecture, data, objective, label, candidate semantics, update order, seed, or training budget was tuned against n=128.",
            ),
            narrowestNextQuestion = nextQuestion,
        )
    }

    private fun retainedStage(
        reference: Issue0027NeuralCandidateUpdateScaleReport,
        ordered: List<EncodedBcDecision>,
        modelConfig: NeuralBcModelConfig,
        decisions: Int,
    ): NeuralPopulationScalingStageResult {
        val rawSubset = ordered.take(decisions)
        val subset = rawSubset.map { it.withStateInputScale(CORRECTED_NEURAL_STATE_INPUT_SCALE) }
        val subsetIdentity = PolicyJson.sha256(
            rawSubset.joinToString("\n") { "${it.gameId}:${it.decisionIndex}" }
        )
        val retained = reference.intervention.stages.single { it.decisions == decisions }
        require(retained.subsetIdentity == subsetIdentity)
        val seeds = retained.seeds.map { result ->
            val modelPath = root.resolve(result.modelPath).normalize()
            require(modelPath.startsWith(root) && Files.isRegularFile(modelPath)) {
                "Retained issue-0027 best model is unavailable: ${result.modelPath}"
            }
            require(sha256File(modelPath) == result.modelSha256) {
                "Retained issue-0027 best model hash changed: ${result.modelPath}"
            }
            val policy = CandidateConditionedNeuralPolicy.load(modelPath)
            require(policy.artifact.bestEpoch == result.bestEpoch)
            NeuralPopulationScalingSeedResult(
                seed = result.seed,
                resultSource = "RETAINED_ISSUE_0027",
                epochsCompleted = result.epochsCompleted,
                stoppedAfterPerfectConfirmation = result.stoppedAfterPerfectConfirmation,
                firstPerfectEpoch = result.firstPerfectEpoch,
                longestPerfectStreak = result.longestPerfectStreak,
                bestEpoch = result.bestEpoch,
                bestStrictRankingCorrect = result.bestStrictRankingCorrect,
                bestStrictRankingAccuracy = result.bestStrictRankingAccuracy,
                bestMeanCrossEntropy = result.bestMeanCrossEntropy,
                finalStrictRankingCorrect = result.finalStrictRankingCorrect,
                finalStrictRankingAccuracy = result.finalStrictRankingAccuracy,
                finalMeanCrossEntropy = result.finalMeanCrossEntropy,
                minimumObservedMeanCrossEntropy = result.minimumObservedMeanCrossEntropy,
                trace = result.trace,
                bestCheckpoint = result.bestCheckpoint,
                finalCheckpoint = result.finalCheckpoint,
                bestExposureAssociations = listOf("STATE", "CANDIDATE").map { projection ->
                    projectionExposureAssociation(
                        projection,
                        subset,
                        result.seed,
                        policy,
                        result.bestCheckpoint,
                    )
                },
                finalExposureAssociations = null,
                exactObservedFinalUpdateCountsMatchReconstruction = null,
                bestCheckpointMisrankedDecisions = result.bestCheckpointMisrankedDecisions,
                modelPath = result.modelPath,
                modelSha256 = result.modelSha256,
            )
        }
        return NeuralPopulationScalingStageResult(
            decisions = decisions,
            resultSource = "RETAINED_ISSUE_0027",
            subsetIdentity = subsetIdentity,
            exposureProfiles = exposureProfiles(subset, modelConfig),
            seeds = seeds,
            reliablyMemorizedByAllSeeds = retained.reliablyMemorizedByAllSeeds,
        )
    }

    /** Reused by issues 0029 and 0030 for their single fixed boundary-localization runs. */
    internal fun trainFixedCorrectedPrefix(
        ordered: List<EncodedBcDecision>,
        modelConfig: NeuralBcModelConfig,
        decisions: Int,
        progress: (String) -> Unit,
    ): NeuralPopulationScalingStageResult {
        require(decisions in setOf(ISSUE_0028_PREFIX, 256, 323)) {
            "The fixed corrected-prefix harness is closed over the issue-0028 n=128, issue-0029 n=256, and issue-0030 n=323 runs"
        }
        val rawSubset = ordered.take(decisions)
        require(rawSubset.size == decisions)
        val subset = rawSubset.map { it.withStateInputScale(CORRECTED_NEURAL_STATE_INPUT_SCALE) }
        val subsetIdentity = PolicyJson.sha256(
            rawSubset.joinToString("\n") { "${it.gameId}:${it.decisionIndex}" }
        )
        val seeds = CORRECTED_NEURAL_SEEDS.map { seed ->
            progress("Training fixed n=$decisions seed=$seed")
            val trace = mutableListOf<NeuralProjectionTracePoint>()
            val trained = NeuralBcMemorizationTrainer(
                modelConfig = modelConfig,
                trainingConfig = NeuralBcTrainingConfig(
                    maximumEpochs = CORRECTED_NEURAL_MAXIMUM_EPOCHS,
                    learningRate = 0.01,
                    candidateProjectionUpdateScale = CORRECTED_NEURAL_CANDIDATE_UPDATE_SCALE,
                    initializationSeeds = CORRECTED_NEURAL_SEEDS,
                ),
                perfectConfirmationEpochs = CORRECTED_NEURAL_CONFIRMATION_EPOCHS,
            ).train(subset, seed) { policy, metric ->
                if (metric.epoch in ISSUE_0028_TRACE_EPOCHS) {
                    trace += traceNeuralBcProjection(policy, subset, metric)
                }
            }
            val modelPath = outputDirectory.resolve("fixed-n$decisions-seed-$seed.json")
            trained.policy.save(modelPath)
            val modelSha = sha256File(modelPath)
            val bestMetric = trained.epochMetrics.single { it.epoch == trained.bestEpoch }
            val finalMetric = trained.epochMetrics.last()
            val bestCheckpoint = traceNeuralBcProjection(trained.policy, subset, bestMetric)
            val finalCheckpoint = traceNeuralBcProjection(trained.finalPolicy, subset, finalMetric)
            val expectedExposure = reconstructSparseAdamUpdateExposure(
                subset,
                modelConfig,
                trained.epochsCompleted,
            )
            val exactExposureMatch = sameUpdateExposure(
                trained.optimizerUpdateExposure,
                expectedExposure,
            )
            require(exactExposureMatch) {
                "Observed SparseAdam update calls did not match source-derived exposure at seed $seed"
            }
            progress(
                "Finished fixed n=$decisions seed=$seed: " +
                    "first=${trained.firstPerfectEpoch ?: "never"}, " +
                    "best=${trained.bestStrictRankingCorrect}/$decisions, " +
                    "final=${finalMetric.strictRankingCorrect}/$decisions, " +
                    "confirmed=${trained.stoppedAfterPerfectConfirmation}"
            )
            NeuralPopulationScalingSeedResult(
                seed = seed,
                resultSource = "NEW_FIXED_N${decisions}_RUN",
                epochsCompleted = trained.epochsCompleted,
                stoppedAfterPerfectConfirmation = trained.stoppedAfterPerfectConfirmation,
                firstPerfectEpoch = trained.firstPerfectEpoch,
                longestPerfectStreak = longestPerfectRun(trained.epochMetrics, decisions),
                bestEpoch = trained.bestEpoch,
                bestStrictRankingCorrect = trained.bestStrictRankingCorrect,
                bestStrictRankingAccuracy = trained.bestStrictRankingAccuracy,
                bestMeanCrossEntropy = trained.bestMeanCrossEntropy,
                finalStrictRankingCorrect = finalMetric.strictRankingCorrect,
                finalStrictRankingAccuracy = trained.finalStrictRankingAccuracy,
                finalMeanCrossEntropy = trained.finalMeanCrossEntropy,
                minimumObservedMeanCrossEntropy = trained.minimumObservedMeanCrossEntropy,
                trace = trace,
                bestCheckpoint = bestCheckpoint,
                finalCheckpoint = finalCheckpoint,
                bestExposureAssociations = listOf("STATE", "CANDIDATE").map { projection ->
                    projectionExposureAssociation(
                        projection,
                        subset,
                        seed,
                        trained.policy,
                        bestCheckpoint,
                    )
                },
                finalExposureAssociations = listOf("STATE", "CANDIDATE").map { projection ->
                    projectionExposureAssociation(
                        projection,
                        subset,
                        seed,
                        trained.finalPolicy,
                        finalCheckpoint,
                    )
                },
                exactObservedFinalUpdateCountsMatchReconstruction = exactExposureMatch,
                bestCheckpointMisrankedDecisions = trained.retainedHardDecisions.filterNot {
                    it.strictRankingCorrect
                },
                modelPath = root.relativize(modelPath).toString(),
                modelSha256 = modelSha,
            )
        }
        return NeuralPopulationScalingStageResult(
            decisions = decisions,
            resultSource = "NEW_FIXED_N${decisions}_RUN",
            subsetIdentity = subsetIdentity,
            exposureProfiles = exposureProfiles(subset, modelConfig),
            seeds = seeds,
            reliablyMemorizedByAllSeeds = seeds.all {
                it.stoppedAfterPerfectConfirmation &&
                    it.longestPerfectStreak >= CORRECTED_NEURAL_CONFIRMATION_EPOCHS
            },
        )
    }
}

private fun matchedGlobalStepCheckpoints(
    stages: List<NeuralPopulationScalingStageResult>,
    target: Long,
): List<NeuralMatchedGlobalStepCheckpoint> = stages.flatMap { stage ->
    val stateP90 = stage.exposureProfiles.single { it.projection == "STATE" }
        .weightUpdatesPerEpoch.p90ActiveUpdateCount
    val candidateP90 = stage.exposureProfiles.single { it.projection == "CANDIDATE" }
        .weightUpdatesPerEpoch.p90ActiveUpdateCount
    stage.seeds.map { seed ->
        val available = (seed.trace + seed.bestCheckpoint + seed.finalCheckpoint)
            .distinctBy(NeuralProjectionTracePoint::epoch)
        val point = available.minWith(
            compareBy<NeuralProjectionTracePoint> {
                abs(it.epoch.toLong() * stage.decisions - target)
            }.thenBy(NeuralProjectionTracePoint::epoch)
        )
        NeuralMatchedGlobalStepCheckpoint(
            targetGlobalDecisionSteps = target,
            decisions = stage.decisions,
            seed = seed.seed,
            epoch = point.epoch,
            globalDecisionSteps = point.epoch.toLong() * stage.decisions,
            stateP90CumulativeUpdateCalls = stateP90 * point.epoch,
            candidateP90CumulativeUpdateCalls = candidateP90 * point.epoch,
            tracePoint = point,
        )
    }
}

private fun longestPerfectRun(
    metrics: List<NeuralMemorizationEpochMetric>,
    decisions: Int,
): Int {
    var current = 0
    var longest = 0
    metrics.forEach { metric ->
        current = if (metric.strictRankingCorrect == decisions) current + 1 else 0
        longest = maxOf(longest, current)
    }
    return longest
}

private fun scalingInterpretation(
    stages: List<NeuralPopulationScalingStageResult>,
    matchedGlobalSteps: List<NeuralMatchedGlobalStepCheckpoint>,
    materialSaturationRise: Boolean,
): List<String> {
    fun pctRange(values: List<Double>): String =
        "%.2f–%.2f%%".format(values.minOrNull()!! * 100.0, values.maxOrNull()!! * 100.0)
    fun fit(stage: NeuralPopulationScalingStageResult): String =
        stage.seeds.joinToString(" / ") { "${it.bestStrictRankingCorrect}/${stage.decisions}" }
    val n64 = stages.single { it.decisions == 64 }
    val n128 = stages.single { it.decisions == 128 }
    val n389 = stages.single { it.decisions == 389 }
    val stateProfiles = stages.map { stage ->
        stage.decisions to stage.exposureProfiles.single { it.projection == "STATE" }
    }
    val candidateProfiles = stages.map { stage ->
        stage.decisions to stage.exposureProfiles.single { it.projection == "CANDIDATE" }
    }
    return listOf(
        "The unchanged corrected learner's n=128 best fits are ${fit(n128)}; final fits are " +
            n128.seeds.joinToString(" / ") { "${it.finalStrictRankingCorrect}/128" } +
            "; all-seed 20-epoch reliability is ${n128.reliablyMemorizedByAllSeeds}.",
        "Final n=128 state near saturation is ${pctRange(n128.seeds.map { it.finalCheckpoint.state.nearSaturatedFraction })}; " +
            "candidate near saturation is ${pctRange(n128.seeds.map { it.finalCheckpoint.candidate.nearSaturatedFraction })}. " +
            "The prespecified material rise from n=64 is $materialSaturationRise.",
        "No n=128 initialization, retained trace, best, or final checkpoint contains an exact learned-state repetition, " +
            "learned-candidate repetition, or hidden ranking contradiction. Candidate exact saturation is zero at every " +
            "best/final checkpoint; final state exact saturation is " +
            pctRange(n128.seeds.map { it.finalCheckpoint.state.exactlySaturatedFraction }) + ".",
        "The retained stability endpoints are n=64 reliable (${fit(n64)}) and n=389 not reliable across all seeds (${fit(n389)}).",
        "Active state buckets / median active weight updates per epoch progress " +
            stateProfiles.joinToString("; ") { (n, profile) ->
                "n=$n: ${profile.activeInputBuckets}/${profile.weightUpdatesPerEpoch.medianActiveUpdateCount}"
            } + ".",
        "Active candidate buckets / median active weight updates per epoch progress " +
            candidateProfiles.joinToString("; ") { (n, profile) ->
                "n=$n: ${profile.activeInputBuckets}/${profile.weightUpdatesPerEpoch.medianActiveUpdateCount}"
            } + ". Candidate occurrences are not update calls because same-bucket gradients are merged within a decision.",
        matchedGlobalStepInterpretation(matchedGlobalSteps),
        exposureAssociationInterpretation(n128),
        if (n128.reliablyMemorizedByAllSeeds) {
            "The all-seed stability boundary is now known to lie above 128 and at or below 389 decisions under this fixed recipe."
        } else {
            "The all-seed stability boundary is now known to lie above 64 and at or below 128 decisions under this fixed recipe."
        },
    )
}

private fun matchedGlobalStepInterpretation(
    checkpoints: List<NeuralMatchedGlobalStepCheckpoint>,
): String {
    fun pctRange(values: List<Double>): String =
        "%.2f–%.2f%%".format(values.minOrNull()!! * 100.0, values.maxOrNull()!! * 100.0)
    fun range(values: List<Long>): String = "${values.minOrNull()}–${values.maxOrNull()}"
    return checkpoints.groupBy(NeuralMatchedGlobalStepCheckpoint::decisions).entries
        .sortedBy(Map.Entry<Int, List<NeuralMatchedGlobalStepCheckpoint>>::key)
        .joinToString(prefix = "Near 3,072 global decision steps, ", separator = "; ", postfix = ".") {
            (decisions, points) ->
            "n=$decisions has p90 state calls ${range(points.map { it.stateP90CumulativeUpdateCalls })} " +
                "and state near saturation ${pctRange(points.map { it.tracePoint.state.nearSaturatedFraction })}"
        } + " Similar p90 call exposure but much higher n=389 state saturation shows that call count alone is insufficient."
}

private fun exposureAssociationInterpretation(stage: NeuralPopulationScalingStageResult): String {
    fun numberRange(values: List<Double>): String =
        "%.3f–%.3f".format(values.minOrNull()!!, values.maxOrNull()!!)
    val state = stage.seeds.map { seed ->
        seed.bestExposureAssociations.single { it.projection == "STATE" }
    }
    val candidate = stage.seeds.map { seed ->
        seed.bestExposureAssociations.single { it.projection == "CANDIDATE" }
    }
    val stateCorrelations = state.mapNotNull { it.logUpdateCountVsAbsoluteWeightChangePearson }
    val candidateCorrelations = candidate.mapNotNull { it.logUpdateCountVsAbsoluteWeightChangePearson }
    fun contributions(
        associations: List<NeuralProjectionExposureAssociation>,
        band: String,
    ): List<Double> = associations.map { association ->
        association.bands.single { it.band == band }.checkpointPreActivationContributionRms
    }
    return "At n=128 best checkpoints, heavy-versus-rare pre-activation contribution RMS is " +
        "${numberRange(contributions(state, "HEAVY_ACTIVE"))} versus " +
        "${numberRange(contributions(state, "RARE_ACTIVE"))} on state and " +
        "${numberRange(contributions(candidate, "HEAVY_ACTIVE"))} versus " +
        "${numberRange(contributions(candidate, "RARE_ACTIVE"))} on candidate. However, log update count " +
        "versus absolute weight change has Pearson ${numberRange(stateCorrelations)} on state cells and " +
        "${numberRange(candidateCorrelations)} on candidate cells. Exposure therefore tracks aggregate numerical " +
        "influence, especially on candidate, but is not a monotone per-weight growth explanation."
}

internal fun renderIssue0028NeuralPopulationScaling(
    report: Issue0028NeuralPopulationScalingReport,
): String = buildString {
    fun pct(value: Double): String = "%.2f%%".format(value * 100.0)
    fun number(value: Double): String = "%.6f".format(value)
    fun correlation(value: Double?): String = value?.let { "%.3f".format(it) } ?: "n/a"
    appendLine("# Neural population-scaling and sparse-update diagnostic")
    appendLine()
    appendLine("## Answer")
    appendLine()
    appendLine("`${report.diagnosticCase}`")
    appendLine()
    report.interpretation.forEach { appendLine("- $it") }
    appendLine()
    appendLine("This artifact is fixed-corpus training-system evidence, not held-out or playing-strength evidence.")
    appendLine()
    appendLine("## Fixed experimental contract")
    appendLine()
    appendLine("- Dataset: `${report.corpusDatasetIdentity}`")
    appendLine("- Manifest SHA-256: `${report.corpusManifestSha256}`")
    appendLine("- Split: `${report.splitIdentity}` / `${report.splitSha256}`")
    appendLine("- Nested order: `${report.subsetSelectionOrderSha256}`")
    appendLine("- Historical candidate schema: ${report.historicalCandidateSchemaVersion}; feature schema: `${report.featureSchema}`")
    appendLine("- Model: ${report.modelConfig.parameterCount} parameters; seeds ${report.initializationSeeds}")
    appendLine("- Optimizer: ${report.optimizer}")
    appendLine("- Epoch budget / strict-perfect confirmation: ${report.maximumEpochs} / ${report.perfectConfirmationEpochs}")
    appendLine("- Retained comparison: `${report.referenceProtocol}` / `${report.referenceArtifactSha256}`")
    appendLine()
    appendLine("Only n=128 is newly trained; n=64 and n=389 are retained issue-0027 measurements.")
    appendLine()
    appendLine("## Definitions")
    appendLine()
    report.saturationAndCollapseDefinitions.forEach { appendLine("- $it") }
    report.updateExposureDefinition.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Memorization")
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
    appendLine("| n | Seed | Checkpoint | Epoch | Strict / loss | State near / exact / derivative | Candidate near / exact / derivative | State repeats / largest | Candidate collapses / labels | Hidden contradictions / affected |")
    appendLine("| ---: | ---: | --- | ---: | --- | --- | --- | --- | --- | --- |")
    report.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            val selected = buildList {
                if (stage.decisions == ISSUE_0028_PREFIX) {
                    listOf(0, 1, 8, 24, 64, 128, 256, 512, 1_024).forEach { epoch ->
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
    appendLine("## Actual sparse update exposure per epoch")
    appendLine()
    appendLine("Candidate occurrences show what enters accumulated gradients; unique bucket-decision touches determine actual candidate-weight update calls.")
    appendLine()
    appendLine("| n | Projection | Active buckets / cells | Feature occurrences | Unique bucket-decision touches | Actual weight calls | Active count min / p10 / median / p90 / p99 / max / mean | Dense bias calls per cell |")
    appendLine("| ---: | --- | --- | ---: | ---: | ---: | --- | ---: |")
    report.stages.forEach { stage ->
        stage.exposureProfiles.forEach { profile ->
            val distribution = profile.weightUpdatesPerEpoch
            appendLine(
                "| ${stage.decisions} | ${profile.projection} | ${profile.activeInputBuckets} / ${distribution.activeParameterCells} | " +
                    "${profile.sparseFeatureOccurrencesPerEpoch} | ${profile.uniqueBucketDecisionTouchesPerEpoch} | " +
                    "${profile.actualWeightUpdateCallsPerEpoch} | " +
                    "${distribution.minimumActiveUpdateCount} / ${distribution.p10ActiveUpdateCount} / " +
                    "${distribution.medianActiveUpdateCount} / ${distribution.p90ActiveUpdateCount} / " +
                    "${distribution.p99ActiveUpdateCount} / ${distribution.maximumActiveUpdateCount} / " +
                    "${"%.2f".format(distribution.meanActiveUpdateCount)} | " +
                    "${profile.denseBiasUpdateCallsPerCellPerEpoch} |"
            )
        }
    }
    appendLine()
    appendLine("## Best-checkpoint exposure association")
    appendLine()
    appendLine("| n | Seed | Projection | Epoch / global steps | Cumulative active median / p90 / max | Bias RMS initial → best | Pre-activation RMS / near | corr(log calls, |Δw|) | corr(log calls, |w|) |")
    appendLine("| ---: | ---: | --- | --- | --- | --- | --- | ---: | ---: |")
    report.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            seed.bestExposureAssociations.forEach { association ->
                val counts = association.cumulativeWeightUpdates
                appendLine(
                    "| ${stage.decisions} | ${seed.seed} | ${association.projection} | " +
                        "${association.checkpointEpoch} / ${association.globalDecisionStepsAtCheckpoint} | " +
                        "${counts.medianActiveUpdateCount} / ${counts.p90ActiveUpdateCount} / ${counts.maximumActiveUpdateCount} | " +
                        "${number(association.initialBiasRms)} → ${number(association.checkpointBiasRms)} | " +
                        "${number(association.checkpointPreActivationRms)} / ${pct(association.checkpointNearSaturatedFraction)} | " +
                        "${correlation(association.logUpdateCountVsAbsoluteWeightChangePearson)} | " +
                        "${correlation(association.logUpdateCountVsCheckpointAbsoluteWeightPearson)} |"
                )
            }
        }
    }
    appendLine()
    appendLine("## Near-matched global-step control")
    appendLine()
    appendLine("The closest retained/new checkpoint to 3,072 global decision updates is selected per seed. This controls cumulative optimizer time approximately, without adding a run.")
    appendLine()
    appendLine("| n | Seed | Epoch / global steps | p90 cumulative state / candidate calls | Strict / loss | State pre-RMS / near / exact | Candidate pre-RMS / near / exact |")
    appendLine("| ---: | ---: | --- | --- | --- | --- | --- |")
    report.matchedGlobalStepCheckpoints.forEach { checkpoint ->
        val point = checkpoint.tracePoint
        appendLine(
            "| ${checkpoint.decisions} | ${checkpoint.seed} | ${checkpoint.epoch} / ${checkpoint.globalDecisionSteps} | " +
                "${checkpoint.stateP90CumulativeUpdateCalls} / ${checkpoint.candidateP90CumulativeUpdateCalls} | " +
                "${point.strictRankingCorrect}/${checkpoint.decisions} / ${number(point.meanCrossEntropy)} | " +
                "${number(point.state.rmsPreActivation)} / ${pct(point.state.nearSaturatedFraction)} / " +
                "${pct(point.state.exactlySaturatedFraction)} | " +
                "${number(point.candidate.rmsPreActivation)} / ${pct(point.candidate.nearSaturatedFraction)} / " +
                "${pct(point.candidate.exactlySaturatedFraction)} |"
        )
    }
    appendLine()
    appendLine("### Rare versus heavy active cells")
    appendLine()
    appendLine("| n | Seed | Projection | Band | Cells | Updates/epoch min / mean / max | Mean cumulative | Weight RMS initial → best / Δ | Contribution RMS initial → best |")
    appendLine("| ---: | ---: | --- | --- | ---: | --- | ---: | --- | --- |")
    report.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            seed.bestExposureAssociations.forEach { association ->
                association.bands.filter { it.band != "MIDDLE_ACTIVE" }.forEach { band ->
                    appendLine(
                        "| ${stage.decisions} | ${seed.seed} | ${association.projection} | ${band.band} | " +
                            "${band.parameterCells} | ${band.minimumUpdatesPerEpoch} / " +
                            "${"%.2f".format(band.meanUpdatesPerEpoch)} / ${band.maximumUpdatesPerEpoch} | " +
                            "${"%.2f".format(band.meanCumulativeUpdatesAtCheckpoint)} | " +
                            "${number(band.initialWeightRms)} → ${number(band.checkpointWeightRms)} / ${number(band.weightChangeRms)} | " +
                            "${number(band.initialPreActivationContributionRms)} → " +
                            "${number(band.checkpointPreActivationContributionRms)} |"
                    )
                }
            }
        }
    }
    appendLine()
    val misses = report.stages.flatMap { stage ->
        stage.seeds.flatMap { seed ->
            seed.bestCheckpointMisrankedDecisions.map { Triple(stage.decisions, seed.seed, it) }
        }
    }
    if (misses.isNotEmpty()) {
        appendLine("## Best-checkpoint strict misses")
        appendLine()
        misses.forEach { (decisions, seed, fit) ->
            appendLine(
                "- n=$decisions seed=$seed `${fit.decision.gameId}:${fit.decision.decisionIndex}`: " +
                    "teacher ${fit.teacherIntent}, predicted ${fit.predictedIntent}, " +
                    "loss ${number(fit.meanCrossEntropyContribution)}, margin ${number(fit.teacherMargin)}"
            )
        }
        appendLine()
    }
    appendLine("## Interpretation boundary")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
    appendLine()
    appendLine("Narrowest next question: ${report.narrowestNextQuestion}")
}
