package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance

internal const val ISSUE_0026_PROTOCOL = "issue-0026-fixed-v3-neural-saturation-intervention-v1"
private const val ISSUE_0026_SUBSET_PROTOCOL = "sha256-nested-training-decisions-v1"
private const val ISSUE_0026_TRAINING_DECISIONS = 389
private const val ISSUE_0026_EXPECTED_ORDER_SHA256 =
    "d5cf812bf30a94db545e44b875d5a0a36b1c2ed6ffe55023ede8b720419ab06e"
private val ISSUE_0026_SEEDS = listOf(1729L, 3253L, 6997L)
private val ISSUE_0026_TRACE_EPOCHS = (
    (0..10).toList() + listOf(12, 16, 20, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1_024, 1_500)
).toSet()

@Serializable
internal data class NeuralSparseInputScaleSummary(
    val vectors: Int,
    val scalarValues: Int,
    val medianNonzeroValues: Double,
    val p90NonzeroValues: Double,
    val maximumNonzeroValues: Int,
    val medianL1Norm: Double,
    val p90L1Norm: Double,
    val maximumL1Norm: Double,
    val medianL2Norm: Double,
    val p90L2Norm: Double,
    val maximumL2Norm: Double,
    val medianMaximumAbsoluteValue: Double,
    val p90MaximumAbsoluteValue: Double,
    val maximumAbsoluteValue: Double,
)

@Serializable
internal data class NeuralFeatureFamilyProjectionContribution(
    val family: String,
    val emittedTerms: Int,
    val rmsPreActivationContributionAcrossSeeds: Double,
    val shareOfSummedFamilyRms: Double,
)

@Serializable
internal data class NeuralInputScaleAudit(
    val subsetDecisions: Int,
    val state: NeuralSparseInputScaleSummary,
    val candidate: NeuralSparseInputScaleSummary,
    val initializationStateFamilyContributions: List<NeuralFeatureFamilyProjectionContribution>,
    val initializationCandidateFamilyContributions: List<NeuralFeatureFamilyProjectionContribution>,
)

@Serializable
internal data class NeuralIssue0025Reproduction(
    val expectedBestEpoch: Int,
    val expectedBestStrictRankingCorrect: Int,
    val expectedFinalStrictRankingAccuracy: Double,
    val expectedFinalMeanCrossEntropy: Double,
    val expectedModelSha256: String,
    val exactMetricsMatch: Boolean,
    val exactEpochHistoryMatch: Boolean,
    val exactModelSha256Match: Boolean,
)

@Serializable
internal data class NeuralTrajectorySeedResult(
    val seed: Long,
    val epochsCompleted: Int,
    val stoppedAfterPerfectConfirmation: Boolean,
    val firstPerfectEpoch: Int?,
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
    val bestCheckpointMisrankedDecisions: List<NeuralMemorizationDecisionFit>,
    val modelPath: String,
    val modelSha256: String,
    val issue0025Reproduction: NeuralIssue0025Reproduction?,
)

@Serializable
internal data class NeuralTrajectoryStageResult(
    val decisions: Int,
    val maximumEpochs: Int,
    val subsetIdentity: String,
    val seeds: List<NeuralTrajectorySeedResult>,
    val reliablyMemorizedByAllSeeds: Boolean,
)

@Serializable
internal data class NeuralTrajectoryConditionResult(
    val condition: String,
    val learningRate: Double,
    val stateInputScale: Double,
    val candidateInputScale: Double,
    val stages: List<NeuralTrajectoryStageResult>,
)

@Serializable
internal data class Issue0026NeuralSaturationTrajectoryReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0026_PROTOCOL,
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
    val modelConfig: NeuralBcModelConfig,
    val optimizer: String,
    val initializationSeeds: List<Long>,
    val traceEpochs: List<Int>,
    val saturationDefinitions: List<String>,
    val inputScaleAudit: NeuralInputScaleAudit,
    val baseline: NeuralTrajectoryConditionResult,
    val interventionRationale: List<String>,
    val intervention: NeuralTrajectoryConditionResult,
    val baselineFailureClassification: String,
    val diagnosticCase: String,
    val interpretation: List<String>,
    val limitations: List<String>,
)

internal class Issue0026NeuralSaturationTrajectoryDiagnostic(
    private val root: Path,
    private val outputDirectory: Path,
) {
    fun run(
        historicalManifestPath: Path,
        progress: (String) -> Unit = {},
    ): Issue0026NeuralSaturationTrajectoryReport {
        Files.createDirectories(outputDirectory)
        val implementation = requireNotNull(RunProvenance.capture(root).sourceProvenance)
        val population = Issue0022HistoricalCorpusReader(root).read(historicalManifestPath)
        val encoder = NeuralBehavioralCloningFeatureEncoder()
        val trainGames = population.split.trainGames.toSet()
        val trainExamples = population.examples.filter {
            it.gameId in trainGames && it.input.candidates.size >= PRIMARY_MIN_CANDIDATES
        }
        val train = trainExamples.map { it.encode(encoder) }
        require(train.size == ISSUE_0026_TRAINING_DECISIONS)
        val ordered = deterministicNeuralMemorizationOrder(
            train,
            population.manifest.datasetIdentity,
            ISSUE_0026_SUBSET_PROTOCOL,
        )
        val selectionMaterial = ordered.joinToString("\n") { "${it.gameId}:${it.decisionIndex}" }
        val selectionSha = PolicyJson.sha256(selectionMaterial)
        require(selectionSha == ISSUE_0026_EXPECTED_ORDER_SHA256)
        progress("Read the exact fixed issue-0022 population and recovered the issue-0025 nested order")

        val modelConfig = NeuralBcModelConfig(
            stateDimension = encoder.stateDimension,
            candidateDimension = encoder.candidateDimension,
        )
        val inputScale = auditInputScale(
            encoder = encoder,
            ordered = ordered.take(64),
            examples = trainExamples,
            modelConfig = modelConfig,
        )
        val issue0025 = readIssue0025Reference(population, selectionSha)
        val baseline = trainCondition(
            condition = "UNCHANGED_ISSUE_0025_BASELINE",
            learningRate = 0.01,
            stateInputScale = 1.0,
            ordered = ordered,
            modelConfig = modelConfig,
            issue0025 = issue0025,
            stageConfigs = listOf(16 to 1_500, 64 to 1_500),
            progress = progress,
        )
        require(baseline.stages.flatMap { it.seeds }.all {
            it.issue0025Reproduction?.exactMetricsMatch == true &&
                it.issue0025Reproduction.exactEpochHistoryMatch &&
                it.issue0025Reproduction.exactModelSha256Match
        }) { "Read-only trajectory instrumentation changed an issue-0025 baseline result" }
        val intervention16And64 = trainCondition(
            condition = "STATE_INPUT_SCALE_ONE_OVER_32",
            learningRate = 0.01,
            stateInputScale = 1.0 / 32.0,
            ordered = ordered,
            modelConfig = modelConfig,
            issue0025 = null,
            stageConfigs = listOf(16 to 1_500, 64 to 1_500),
            progress = progress,
        )
        val intervention = if (
            intervention16And64.stages.single { it.decisions == 64 }.reliablyMemorizedByAllSeeds
        ) {
            val full = trainCondition(
                condition = "STATE_INPUT_SCALE_ONE_OVER_32",
                learningRate = 0.01,
                stateInputScale = 1.0 / 32.0,
                ordered = ordered,
                modelConfig = modelConfig,
                issue0025 = null,
                stageConfigs = listOf(389 to 600),
                progress = progress,
            )
            intervention16And64.copy(stages = intervention16And64.stages + full.stages)
        } else {
            intervention16And64
        }
        val intervention64 = intervention.stages.single { it.decisions == 64 }
        val baseline64 = baseline.stages.single { it.decisions == 64 }
        val baselineEpochOneStateSaturation = baseline64.seeds.map { seed ->
            seed.trace.single { it.epoch == 1 }.state.nearSaturatedFraction
        }.average()
        val interventionEpochOneStateSaturation = intervention64.seeds.map { seed ->
            seed.trace.single { it.epoch == 1 }.state.nearSaturatedFraction
        }.average()
        val materiallyReduced = interventionEpochOneStateSaturation <
            baselineEpochOneStateSaturation - 0.10
        val diagnosticCase = when {
            intervention64.reliablyMemorizedByAllSeeds && materiallyReduced ->
                "STATE_INPUT_SCALE_PREVENTS_COLLAPSE_AND_RESTORES_RELIABLE_64_MEMORIZATION"
            intervention64.reliablyMemorizedByAllSeeds ->
                "STATE_INPUT_SCALE_RESTORES_64_MEMORIZATION_WITHOUT_PREVENTING_MEASURED_COLLAPSE"
            materiallyReduced ->
                "STATE_INPUT_SCALE_REDUCES_COLLAPSE_BUT_DOES_NOT_RESTORE_RELIABLE_64_MEMORIZATION"
            else -> "STATE_INPUT_SCALE_DOES_NOT_REDUCE_COLLAPSE_OR_RESTORE_RELIABLE_64_MEMORIZATION"
        }

        return Issue0026NeuralSaturationTrajectoryReport(
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
            trainingDecisions = train.size,
            subsetSelectionProtocol = ISSUE_0026_SUBSET_PROTOCOL,
            subsetSelectionOrderSha256 = selectionSha,
            modelConfig = modelConfig,
            optimizer = "unchanged issue-0022 SparseAdam; per-decision exact-label cross-entropy",
            initializationSeeds = ISSUE_0026_SEEDS,
            traceEpochs = ISSUE_0026_TRACE_EPOCHS.sorted(),
            saturationDefinitions = listOf(
                "Near-saturated means |tanh(z)| >= 0.99, equivalently |z| >= atanh(0.99) approximately 2.647.",
                "Exactly saturated means the JVM Double result of tanh(z) is exactly -1.0 or 1.0.",
                "Weak derivative means 1 - tanh(z)^2 <= 0.01.",
                "Projection collapse means distinct exact raw encoded state vectors yield an exactly equal 32-value learned query vector; harmful collapse additionally creates a cycle in the exact hidden scorer-input ranking graph.",
            ),
            inputScaleAudit = inputScale,
            baseline = baseline,
            interventionRationale = listOf(
                "Initialization is asymmetric: state projections begin materially near saturation while candidate projections begin mostly linear.",
                "The first unchanged optimizer epoch drives state pre-activations roughly 18–20 times above their initialization median and creates harmful exact aliases at n=64.",
                "The original state vectors have much larger norms than candidate vectors, dominated by repeated recent-event and visible-card bags.",
                "A state-only 1/32 multiplier is the smallest power-of-two attenuation exceeding the observed epoch-one median pre-activation expansion; it is fixed before intervention training and preserves every encoded distinction.",
            ),
            intervention = intervention,
            baselineFailureClassification =
                "MIXED_INITIAL_STATE_SCALE_STRESS_WITH_TRAINING_DRIVEN_SEVERE_SATURATION",
            diagnosticCase = diagnosticCase,
            interpretation = trajectoryInterpretation(baseline, intervention),
            limitations = listOf(
                "This is fixed-training-population optimization evidence, not held-out or playing-strength evidence.",
                "Percentiles use the lower integer index floor((n - 1) * q) over sorted absolute pre-activations.",
                "The intervention changes one global state-projection input scale only; it is a mechanism test, not a proposed production preprocessing rule.",
                "The 1/32 input multiplier changes both initialization pre-activations and the effective contribution of state-weight updates, so this run cannot uniquely separate input scale from state-update step scale.",
                "The 1/32 factor was selected from the baseline activation expansion before intervention labels were fit; no second scale was tried.",
            ),
        )
    }

    private fun readIssue0025Reference(
        population: Issue0022HistoricalPopulation,
        selectionSha: String,
    ): Issue0025NeuralMemorizationReport {
        val path = EvidenceStore(root).latest("neural-memorization-diagnostic/artifact.json")
        val report = evidenceJson.decodeFromString<Issue0025NeuralMemorizationReport>(Files.readString(path))
        require(report.protocol == ISSUE_0025_PROTOCOL)
        require(report.corpusManifestSha256 == population.manifestSha256)
        require(report.splitSha256 == population.splitSha256)
        require(report.subsetSelectionOrderSha256 == selectionSha)
        return report
    }

    private fun trainCondition(
        condition: String,
        learningRate: Double,
        stateInputScale: Double,
        ordered: List<EncodedBcDecision>,
        modelConfig: NeuralBcModelConfig,
        issue0025: Issue0025NeuralMemorizationReport?,
        stageConfigs: List<Pair<Int, Int>>,
        progress: (String) -> Unit,
    ): NeuralTrajectoryConditionResult {
        require(stateInputScale > 0.0 && stateInputScale.isFinite())
        val stages = stageConfigs.map { (count, maximumEpochs) ->
            val rawSubset = ordered.take(count)
            val subset = rawSubset.map { it.withStateInputScale(stateInputScale) }
            val subsetIdentity = PolicyJson.sha256(
                rawSubset.joinToString("\n") { "${it.gameId}:${it.decisionIndex}" }
            )
            val referenceStage = issue0025?.stages?.single { it.config.decisions == count }
            val seeds = ISSUE_0026_SEEDS.map { seed ->
                progress("Tracing $condition n=$count seed=$seed")
                val trace = mutableListOf<NeuralProjectionTracePoint>()
                val trained = NeuralBcMemorizationTrainer(
                    modelConfig = modelConfig,
                    trainingConfig = NeuralBcTrainingConfig(
                        maximumEpochs = maximumEpochs,
                        learningRate = learningRate,
                        initializationSeeds = ISSUE_0026_SEEDS,
                    ),
                    perfectConfirmationEpochs = 20,
                ).train(subset, seed) { policy, metric ->
                    if (metric.epoch in ISSUE_0026_TRACE_EPOCHS) {
                        trace += traceNeuralBcProjection(policy, subset, metric)
                    }
                }
                val modelPath = outputDirectory.resolve(
                    "${condition.lowercase()}-n$count-seed-$seed.json"
                )
                trained.policy.save(modelPath)
                val modelSha = sha256File(modelPath)
                val bestMetric = trained.epochMetrics.single { it.epoch == trained.bestEpoch }
                val finalMetric = trained.epochMetrics.last()
                val expected = referenceStage?.seedResults?.single { it.seed == seed }
                val reproduction = expected?.let {
                    NeuralIssue0025Reproduction(
                        expectedBestEpoch = it.bestEpoch,
                        expectedBestStrictRankingCorrect = it.bestStrictRankingCorrect,
                        expectedFinalStrictRankingAccuracy = it.finalStrictRankingAccuracy,
                        expectedFinalMeanCrossEntropy = it.finalMeanCrossEntropy,
                        expectedModelSha256 = it.modelSha256,
                        exactMetricsMatch = trained.bestEpoch == it.bestEpoch &&
                            trained.bestStrictRankingCorrect == it.bestStrictRankingCorrect &&
                            trained.finalStrictRankingAccuracy == it.finalStrictRankingAccuracy &&
                            trained.finalMeanCrossEntropy == it.finalMeanCrossEntropy,
                        exactEpochHistoryMatch = trained.epochMetrics == it.epochMetrics,
                        exactModelSha256Match = modelSha == it.modelSha256,
                    )
                }
                progress(
                    "Finished $condition n=$count seed=$seed: best=${trained.bestStrictRankingCorrect}/$count, " +
                        "final=${finalMetric.strictRankingCorrect}/$count"
                )
                NeuralTrajectorySeedResult(
                    seed = seed,
                    epochsCompleted = trained.epochsCompleted,
                    stoppedAfterPerfectConfirmation = trained.stoppedAfterPerfectConfirmation,
                    firstPerfectEpoch = trained.firstPerfectEpoch,
                    bestEpoch = trained.bestEpoch,
                    bestStrictRankingCorrect = trained.bestStrictRankingCorrect,
                    bestStrictRankingAccuracy = trained.bestStrictRankingAccuracy,
                    bestMeanCrossEntropy = trained.bestMeanCrossEntropy,
                    finalStrictRankingCorrect = finalMetric.strictRankingCorrect,
                    finalStrictRankingAccuracy = trained.finalStrictRankingAccuracy,
                    finalMeanCrossEntropy = trained.finalMeanCrossEntropy,
                    minimumObservedMeanCrossEntropy = trained.minimumObservedMeanCrossEntropy,
                    trace = trace,
                    bestCheckpoint = traceNeuralBcProjection(trained.policy, subset, bestMetric),
                    finalCheckpoint = traceNeuralBcProjection(trained.finalPolicy, subset, finalMetric),
                    bestCheckpointMisrankedDecisions = trained.retainedHardDecisions.filterNot {
                        it.strictRankingCorrect
                    },
                    modelPath = root.relativize(modelPath).toString(),
                    modelSha256 = modelSha,
                    issue0025Reproduction = reproduction,
                )
            }
            NeuralTrajectoryStageResult(
                decisions = count,
                maximumEpochs = maximumEpochs,
                subsetIdentity = subsetIdentity,
                seeds = seeds,
                reliablyMemorizedByAllSeeds = seeds.all { it.bestStrictRankingCorrect == count },
            )
        }
        return NeuralTrajectoryConditionResult(
            condition = condition,
            learningRate = learningRate,
            stateInputScale = stateInputScale,
            candidateInputScale = 1.0,
            stages = stages,
        )
    }

    private fun auditInputScale(
        encoder: NeuralBehavioralCloningFeatureEncoder,
        ordered: List<EncodedBcDecision>,
        examples: List<Issue0022HistoricalExample>,
        modelConfig: NeuralBcModelConfig,
    ): NeuralInputScaleAudit {
        val byDecision = examples.associateBy { it.gameId to it.decisionIndex }
        val audits = ordered.map { decision ->
            val example = byDecision.getValue(decision.gameId to decision.decisionIndex)
            encoder.auditedFeatureEmissions(example.input.featureView()).also { audit ->
                require(sameSparseFeatureVector(audit.state, decision.state))
                require(audit.candidates.size == decision.candidates.size)
                audit.candidates.indices.forEach { index ->
                    require(sameSparseFeatureVector(audit.candidates[index], decision.candidates[index]))
                }
            }
        }
        return NeuralInputScaleAudit(
            subsetDecisions = ordered.size,
            state = sparseInputScaleSummary(ordered.map(EncodedBcDecision::state)),
            candidate = sparseInputScaleSummary(ordered.flatMap(EncodedBcDecision::candidates)),
            initializationStateFamilyContributions = familyContributions(
                emissionVectors = audits.map(NeuralBcFeatureEmissionAudit::stateEmissions),
                modelConfig = modelConfig,
                state = true,
            ),
            initializationCandidateFamilyContributions = familyContributions(
                emissionVectors = audits.flatMap(NeuralBcFeatureEmissionAudit::candidateEmissions),
                modelConfig = modelConfig,
                state = false,
            ),
        )
    }
}

internal fun EncodedBcDecision.withStateInputScale(scale: Double): EncodedBcDecision =
    if (scale == 1.0) this else copy(
        state = SparseFeatureVector(
            indices = state.indices.copyOf(),
            values = DoubleArray(state.values.size) { state.values[it] * scale },
        )
    )

private fun trajectoryInterpretation(
    baseline: NeuralTrajectoryConditionResult,
    intervention: NeuralTrajectoryConditionResult,
): List<String> {
    fun pctRange(values: List<Double>): String =
        "%.2f–%.2f%%".format(values.minOrNull()!! * 100.0, values.maxOrNull()!! * 100.0)
    fun decimalRange(values: List<Double>): String =
        "%.3f–%.3f".format(values.minOrNull()!!, values.maxOrNull()!!)
    fun scientificRange(values: List<Double>): String =
        "%.3e–%.3e".format(values.minOrNull()!!, values.maxOrNull()!!)
    fun points(condition: NeuralTrajectoryConditionResult, decisions: Int, epoch: Int) =
        condition.stages.single { it.decisions == decisions }.seeds.map { seed ->
            seed.trace.single { it.epoch == epoch }
        }
    val baselineZero = points(baseline, 64, 0)
    val baselineOne = points(baseline, 64, 1)
    val interventionZero = points(intervention, 64, 0)
    val interventionOne = points(intervention, 64, 1)
    val intervention16 = intervention.stages.single { it.decisions == 16 }
    val intervention64 = intervention.stages.single { it.decisions == 64 }
    val baselineBest = baseline.stages.single { it.decisions == 64 }.seeds.map { it.bestCheckpoint }
    val interventionBest = intervention64.seeds.map { it.bestCheckpoint }
    return buildList {
        add(
            "Read-only tracing exactly reproduces all six retained issue-0025 n=16/n=64 epoch histories, " +
                "best checkpoint hashes, and fit metrics."
        )
        add(
            "At unchanged n=64 initialization, state median |z| is " +
                "${decimalRange(baselineZero.map { it.state.medianAbsolutePreActivation })}, " +
                "${pctRange(baselineZero.map { it.state.nearSaturatedFraction })} of state outputs are near " +
                "saturation, none are exactly saturated, and mean state derivative is " +
                "${decimalRange(baselineZero.map { it.state.meanTanhDerivative })}; candidate near saturation " +
                "is only ${pctRange(baselineZero.map { it.candidate.nearSaturatedFraction })}."
        )
        add(
            "After one unchanged optimizer epoch, state median |z| reaches " +
                "${decimalRange(baselineOne.map { it.state.medianAbsolutePreActivation })}, near saturation " +
                "reaches ${pctRange(baselineOne.map { it.state.nearSaturatedFraction })}, and exact saturation " +
                "reaches ${pctRange(baselineOne.map { it.state.exactlySaturatedFraction })}; mean derivative " +
                "falls to ${scientificRange(baselineOne.map { it.state.meanTanhDerivative })}, and every seed " +
                "has at least one repeated learned-state group and a contradictory hidden ranking component."
        )
        add(
            "Over that first epoch, state-weight RMS grows only from " +
                "${decimalRange(baselineZero.map { it.parameters.stateWeightRms })} to " +
                "${decimalRange(baselineOne.map { it.parameters.stateWeightRms })}, while state median |z| " +
                "grows 18–20 times. The large repeated-feature inputs and coherent update alignment, not raw " +
                "weight norm growth alone, therefore explain the immediate pre-activation expansion."
        )
        add(
            "The 1/32 state-only input scale changes n=64 initialization state median |z| to " +
                "${decimalRange(interventionZero.map { it.state.medianAbsolutePreActivation })} and epoch-one " +
                "near saturation to ${pctRange(interventionOne.map { it.state.nearSaturatedFraction })}; " +
                "candidate inputs and their initialization are unchanged."
        )
        add(
            "At intervention best checkpoints, state near saturation is " +
                "${pctRange(interventionBest.map { it.state.nearSaturatedFraction })} with exact saturation " +
                "${pctRange(interventionBest.map { it.state.exactlySaturatedFraction })}, versus " +
                "${pctRange(baselineBest.map { it.state.nearSaturatedFraction })} near and " +
                "${pctRange(baselineBest.map { it.state.exactlySaturatedFraction })} exact in the baseline; " +
                "the intervention best checkpoints have no repeated learned-state groups or hidden ranking " +
                "contradictions."
        )
        add(
            "Under the intervention, n=16 best strict fits are " +
                intervention16.seeds.joinToString(" / ") { "${it.bestStrictRankingCorrect}/16" } +
                "; n=64 best strict fits are " +
                intervention64.seeds.joinToString(" / ") { "${it.bestStrictRankingCorrect}/64" } + "."
        )
        if (intervention64.reliablyMemorizedByAllSeeds) {
            add("The single intervention restores reliable 64/64 memorization across all fixed seeds.")
        } else {
            add(
                "The single intervention does not restore reliable 64/64 memorization across all fixed seeds; " +
                    "the experiment therefore stops without a full-set run."
            )
            val remaining = intervention64.seeds.flatMap { seed ->
                seed.bestCheckpointMisrankedDecisions.map { seed.seed to it }
            }
            if (remaining.isNotEmpty()) {
                add(
                    "Remaining intervention best-checkpoint misrankings: " +
                        remaining.joinToString { (seed, fit) ->
                            "seed $seed `${fit.decision.gameId}:${fit.decision.decisionIndex}` " +
                                "margin ${"%.6f".format(fit.teacherMargin)}"
                        } + "."
                )
            }
        }
        intervention.stages.singleOrNull { it.decisions == 389 }?.let { full ->
            add(
                "The conditional fixed 389-decision check reaches best strict fits " +
                    full.seeds.joinToString(" / ") { "${it.bestStrictRankingCorrect}/389" } + "."
            )
        }
    }
}

private fun sparseInputScaleSummary(vectors: List<SparseFeatureVector>): NeuralSparseInputScaleSummary {
    require(vectors.isNotEmpty())
    val nonzero = vectors.map { vector -> vector.values.count { it != 0.0 }.toDouble() }.sorted()
    val l1 = vectors.map { vector -> vector.values.sumOf(::abs) }.sorted()
    val l2 = vectors.map { vector -> sqrt(vector.values.sumOf { it * it }) }.sorted()
    val maximum = vectors.map { vector -> vector.values.maxOf(::abs) }.sorted()
    fun percentile(values: List<Double>, q: Double): Double = values[((values.size - 1) * q).toInt()]
    return NeuralSparseInputScaleSummary(
        vectors = vectors.size,
        scalarValues = vectors.sumOf { vector -> vector.values.count { it != 0.0 } },
        medianNonzeroValues = percentile(nonzero, 0.50),
        p90NonzeroValues = percentile(nonzero, 0.90),
        maximumNonzeroValues = nonzero.last().toInt(),
        medianL1Norm = percentile(l1, 0.50),
        p90L1Norm = percentile(l1, 0.90),
        maximumL1Norm = l1.last(),
        medianL2Norm = percentile(l2, 0.50),
        p90L2Norm = percentile(l2, 0.90),
        maximumL2Norm = l2.last(),
        medianMaximumAbsoluteValue = percentile(maximum, 0.50),
        p90MaximumAbsoluteValue = percentile(maximum, 0.90),
        maximumAbsoluteValue = maximum.last(),
    )
}

private fun familyContributions(
    emissionVectors: List<List<NeuralBcFeatureEmission>>,
    modelConfig: NeuralBcModelConfig,
    state: Boolean,
): List<NeuralFeatureFamilyProjectionContribution> {
    require(emissionVectors.isNotEmpty())
    val dimension = if (state) modelConfig.stateDimension else modelConfig.candidateDimension
    val emittedTerms = emissionVectors.flatten().groupingBy { featureFamily(it.feature) }.eachCount()
    val squared = linkedMapOf<String, Double>()
    ISSUE_0026_SEEDS.forEach { seed ->
        val artifact = CandidateConditionedNeuralPolicy.initialize(modelConfig, seed).artifact
        val weights = if (state) artifact.stateWeights else artifact.candidateWeights
        emissionVectors.forEach { emissions ->
            val grouped = emissions.groupBy { featureFamily(it.feature) }
            grouped.forEach { (family, terms) ->
                repeat(modelConfig.hiddenDimension) { hidden ->
                    val offset = hidden * dimension
                    val contribution = terms.sumOf { weights[offset + it.bucket] * it.value }
                    squared[family] = squared.getOrDefault(family, 0.0) + contribution * contribution
                }
            }
        }
    }
    val denominator = ISSUE_0026_SEEDS.size.toDouble() * emissionVectors.size * modelConfig.hiddenDimension
    val rms = squared.mapValues { (_, value) -> sqrt(value / denominator) }
    val total = rms.values.sum()
    return rms.map { (family, value) ->
        NeuralFeatureFamilyProjectionContribution(
            family = family,
            emittedTerms = emittedTerms.getValue(family),
            rmsPreActivationContributionAcrossSeeds = value,
            shareOfSummedFamilyRms = value / total,
        )
    }.sortedByDescending(NeuralFeatureFamilyProjectionContribution::rmsPreActivationContributionAcrossSeeds)
}

private fun featureFamily(feature: String): String = when {
    feature.startsWith("state.event.") -> "recent-events"
    feature.startsWith("state.card.") -> "visible-cards"
    feature.startsWith("state.combat.") -> "combat"
    feature.startsWith("state.knowledge.") -> "knowledge"
    feature.startsWith("state.pending.") -> "pending-decision"
    feature.startsWith("state.player.") -> "players"
    feature.startsWith("state.stack") -> "stack"
    feature.startsWith("state.zone.") -> "zones"
    feature.startsWith("state.") -> "turn-phase-priority"
    feature.startsWith("candidate.payload.") -> "payload"
    feature.startsWith("candidate.source") -> "source"
    feature.startsWith("candidate.targetRelation") -> "target-relations"
    feature.startsWith("candidate.") -> "kind-family-intent"
    else -> "other"
}

internal fun renderIssue0026NeuralSaturationTrajectory(
    report: Issue0026NeuralSaturationTrajectoryReport,
): String = buildString {
    fun pct(value: Double): String = "%.2f%%".format(value * 100.0)
    fun number(value: Double): String = "%.6f".format(value)
    appendLine("# Neural saturation trajectory and targeted intervention")
    appendLine()
    appendLine("## Current diagnostic state")
    appendLine()
    appendLine("`${report.diagnosticCase}`")
    appendLine()
    appendLine("Baseline classification: `${report.baselineFailureClassification}`")
    appendLine()
    appendLine("This artifact is fixed-corpus training-system evidence, not playing-strength evidence.")
    appendLine()
    appendLine("## Fixed contract")
    appendLine()
    appendLine("- Dataset: `${report.corpusDatasetIdentity}`")
    appendLine("- Manifest SHA-256: `${report.corpusManifestSha256}`")
    appendLine("- Split: `${report.splitIdentity}` / `${report.splitSha256}`")
    appendLine("- Nested order: `${report.subsetSelectionOrderSha256}`")
    appendLine("- Model: ${report.modelConfig.parameterCount} parameters; seeds ${report.initializationSeeds}")
    appendLine()
    appendLine("## Definitions")
    appendLine()
    report.saturationDefinitions.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Encoded input scale at n=64")
    appendLine()
    appendLine("| Projection | Median / p90 / max nonzeros | Median / p90 / max L2 | Median / p90 / max |value| |")
    appendLine("| --- | --- | --- | --- |")
    listOf("State" to report.inputScaleAudit.state, "Candidate" to report.inputScaleAudit.candidate).forEach { (name, scale) ->
        appendLine("| $name | ${number(scale.medianNonzeroValues)} / ${number(scale.p90NonzeroValues)} / ${scale.maximumNonzeroValues} | " +
            "${number(scale.medianL2Norm)} / ${number(scale.p90L2Norm)} / ${number(scale.maximumL2Norm)} | " +
            "${number(scale.medianMaximumAbsoluteValue)} / ${number(scale.p90MaximumAbsoluteValue)} / ${number(scale.maximumAbsoluteValue)} |")
    }
    appendLine()
    appendLine("Initialization family contributions are RMS additive pre-activation contributions across all three seeds.")
    appendLine()
    appendLine("| Projection | Family | RMS contribution | Share of summed family RMS | Emitted terms |")
    appendLine("| --- | --- | ---: | ---: | ---: |")
    listOf(
        "State" to report.inputScaleAudit.initializationStateFamilyContributions,
        "Candidate" to report.inputScaleAudit.initializationCandidateFamilyContributions,
    ).forEach { (projection, families) ->
        families.forEach { family ->
            appendLine("| $projection | ${family.family} | ${number(family.rmsPreActivationContributionAcrossSeeds)} | " +
                "${pct(family.shareOfSummedFamilyRms)} | ${family.emittedTerms} |")
        }
    }
    appendLine()
    renderTrajectoryCondition(report.baseline, this)
    appendLine("## Intervention rationale")
    appendLine()
    report.interventionRationale.forEach { appendLine("- $it") }
    appendLine()
    renderTrajectoryCondition(report.intervention, this)
    appendLine("## Interpretation and limits")
    appendLine()
    report.interpretation.forEach { appendLine("- $it") }
    report.limitations.forEach { appendLine("- $it") }
}

private fun renderTrajectoryCondition(
    condition: NeuralTrajectoryConditionResult,
    output: StringBuilder,
) = with(output) {
    fun pct(value: Double): String = "%.2f%%".format(value * 100.0)
    fun number(value: Double): String = "%.6f".format(value)
    appendLine("## ${condition.condition}")
    appendLine()
    appendLine("Learning rate: ${condition.learningRate}")
    appendLine("State / candidate input scale: ${condition.stateInputScale} / ${condition.candidateInputScale}")
    appendLine()
    appendLine("| n | Seed | First perfect / confirmed | Best strict | Final strict | Best / final loss | Exact issue-0025 reproduction |")
    appendLine("| ---: | ---: | --- | ---: | ---: | --- | --- |")
    condition.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            val reproduced = seed.issue0025Reproduction?.let {
                it.exactMetricsMatch && it.exactEpochHistoryMatch && it.exactModelSha256Match
            }
            appendLine("| ${stage.decisions} | ${seed.seed} | ${seed.firstPerfectEpoch ?: "—"} / ${seed.stoppedAfterPerfectConfirmation} | " +
                "${seed.bestStrictRankingCorrect}/${stage.decisions} (${pct(seed.bestStrictRankingAccuracy)}) | " +
                "${seed.finalStrictRankingCorrect}/${stage.decisions} (${pct(seed.finalStrictRankingAccuracy)}) | " +
                "${number(seed.bestMeanCrossEntropy)} / ${number(seed.finalMeanCrossEntropy)} | ${reproduced ?: "n/a"} |")
        }
    }
    appendLine()
    appendLine("### Best/final projection geometry")
    appendLine()
    appendLine("| n | Seed | Checkpoint | Epoch | State near / exact | State derivative | Candidate near / exact | Candidate derivative | Repeated states / largest | Hidden contradictions / affected |")
    appendLine("| ---: | ---: | --- | ---: | --- | ---: | --- | ---: | --- | --- |")
    condition.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            listOf("best" to seed.bestCheckpoint, "final" to seed.finalCheckpoint).forEach { (name, point) ->
                appendLine("| ${stage.decisions} | ${seed.seed} | $name | ${point.epoch} | " +
                    "${pct(point.state.nearSaturatedFraction)} / ${pct(point.state.exactlySaturatedFraction)} | " +
                    "${"%.3e".format(point.state.meanTanhDerivative)} | " +
                    "${pct(point.candidate.nearSaturatedFraction)} / ${pct(point.candidate.exactlySaturatedFraction)} | " +
                    "${"%.3e".format(point.candidate.meanTanhDerivative)} | " +
                    "${point.collapse.repeatedProjectedStateGroups} / ${point.collapse.largestRepeatedProjectedStateGroup} | " +
                    "${point.collapse.contradictoryRankingComponents} / ${point.collapse.decisionsAffectedByContradictions} |")
            }
        }
    }
    appendLine()
    val hard = condition.stages.flatMap { stage ->
        stage.seeds.flatMap { seed ->
            seed.bestCheckpointMisrankedDecisions.map { Triple(stage.decisions, seed.seed, it) }
        }
    }
    if (hard.isNotEmpty()) {
        appendLine("Best-checkpoint misrankings:")
        hard.forEach { (decisions, seed, fit) ->
            appendLine("- n=$decisions seed=$seed `${fit.decision.gameId}:${fit.decision.decisionIndex}`: " +
                "teacher ${fit.teacherIntent}, predicted ${fit.predictedIntent}, loss " +
                "${number(fit.meanCrossEntropyContribution)}, margin ${number(fit.teacherMargin)}")
        }
        appendLine()
    }
    appendLine("### Trajectory checkpoints")
    appendLine()
    appendLine("| n | Seed | Epoch | Strict | Loss | State |z| p50 / p90 | State near / exact | State mean derivative | State weight RMS | Candidate |z| p50 / p90 | Candidate near / exact | Candidate mean derivative | Candidate weight RMS | Repeated states / largest | Hidden contradictions / affected |")
    appendLine("| ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | ---: | --- | --- | ---: | ---: | --- | --- |")
    condition.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            seed.trace.forEach { point ->
                appendLine("| ${stage.decisions} | ${seed.seed} | ${point.epoch} | ${point.strictRankingCorrect}/${stage.decisions} | " +
                    "${number(point.meanCrossEntropy)} | ${number(point.state.medianAbsolutePreActivation)} / ${number(point.state.p90AbsolutePreActivation)} | " +
                    "${pct(point.state.nearSaturatedFraction)} / ${pct(point.state.exactlySaturatedFraction)} | ${"%.3e".format(point.state.meanTanhDerivative)} | " +
                    "${number(point.parameters.stateWeightRms)} | " +
                    "${number(point.candidate.medianAbsolutePreActivation)} / ${number(point.candidate.p90AbsolutePreActivation)} | " +
                    "${pct(point.candidate.nearSaturatedFraction)} / ${pct(point.candidate.exactlySaturatedFraction)} | ${"%.3e".format(point.candidate.meanTanhDerivative)} | " +
                    "${number(point.parameters.candidateWeightRms)} | " +
                    "${point.collapse.repeatedProjectedStateGroups} / ${point.collapse.largestRepeatedProjectedStateGroup} | " +
                    "${point.collapse.contradictoryRankingComponents} / ${point.collapse.decisionsAffectedByContradictions} |")
            }
        }
    }
    appendLine()
}
