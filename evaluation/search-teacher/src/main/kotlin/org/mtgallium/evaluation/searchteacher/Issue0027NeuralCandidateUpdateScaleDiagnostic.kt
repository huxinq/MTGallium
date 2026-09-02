package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance

internal const val ISSUE_0027_PROTOCOL = "issue-0027-fixed-v3-candidate-update-scale-v1"
private const val ISSUE_0027_SUBSET_PROTOCOL = "sha256-nested-training-decisions-v1"
private const val ISSUE_0027_TRAINING_DECISIONS = 389
private const val ISSUE_0027_EXPECTED_ORDER_SHA256 =
    "d5cf812bf30a94db545e44b875d5a0a36b1c2ed6ffe55023ede8b720419ab06e"
private const val ISSUE_0027_STATE_INPUT_SCALE = 1.0 / 32.0
private const val ISSUE_0027_CANDIDATE_UPDATE_SCALE = 1.0 / 8.0
private const val ISSUE_0027_MAXIMUM_EPOCHS = 1_500
private const val ISSUE_0027_CONFIRMATION_EPOCHS = 20
private const val ISSUE_0027_WITNESS_GAME = "teacher-corpus-000001"
private const val ISSUE_0027_WITNESS_DECISION = 265
private val ISSUE_0027_SEEDS = listOf(1729L, 3253L, 6997L)
private val ISSUE_0027_TRACE_EPOCHS = (
    (0..10).toList() + listOf(12, 16, 20, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1_024, 1_500)
).toSet()

@Serializable
internal data class NeuralCandidateScaleReproduction(
    val referenceProtocol: String,
    val expectedEpochsCompleted: Int,
    val expectedFirstPerfectEpoch: Int?,
    val expectedBestEpoch: Int,
    val expectedBestStrictRankingCorrect: Int,
    val expectedFinalStrictRankingCorrect: Int,
    val expectedFinalMeanCrossEntropy: Double,
    val expectedModelSha256: String,
    val exactFitMetricsMatch: Boolean,
    val exactRecordedTrajectoryMatch: Boolean,
    val exactBestAndFinalGeometryMatch: Boolean,
    val exactModelSha256Match: Boolean,
)

@Serializable
internal data class NeuralWitnessCheckpoint(
    val epoch: Int,
    val teacherMargin: Double,
    val meanCrossEntropyContribution: Double,
    val strictRankingCorrect: Boolean,
    val predictedCandidateIndex: Int,
    val predictedIntent: String,
)

@Serializable
internal data class NeuralWitnessTrajectory(
    val decision: NeuralDecisionReference,
    val teacherCandidateIndex: Int,
    val teacherIntent: String,
    val issue0026Seed3253BestMargin: Double? = null,
    val firstStrictlyPositiveEpoch: Int?,
    val firstTwentyConsecutivePositiveEpoch: Int?,
    val longestStrictlyPositiveStreak: Int,
    val maximumMarginCheckpoint: NeuralWitnessCheckpoint,
    val bestModelCheckpoint: NeuralWitnessCheckpoint,
    val finalCheckpoint: NeuralWitnessCheckpoint,
    val selectedCheckpoints: List<NeuralWitnessCheckpoint>,
)

@Serializable
internal data class NeuralCandidateScaleSeedResult(
    val seed: Long,
    val epochsCompleted: Int,
    val stoppedAfterPerfectConfirmation: Boolean,
    val firstPerfectEpoch: Int?,
    val longestPerfectStreak: Int,
    val bestEpoch: Int,
    val bestStrictRankingCorrect: Int,
    val bestStrictRankingAccuracy: Double,
    val bestMeanCrossEntropy: Double,
    val bestMinimumTeacherMargin: Double,
    val finalStrictRankingCorrect: Int,
    val finalStrictRankingAccuracy: Double,
    val finalMeanCrossEntropy: Double,
    val minimumObservedMeanCrossEntropy: Double,
    val trace: List<NeuralProjectionTracePoint>,
    val bestCheckpoint: NeuralProjectionTracePoint,
    val finalCheckpoint: NeuralProjectionTracePoint,
    val witness: NeuralWitnessTrajectory?,
    val bestCheckpointMisrankedDecisions: List<NeuralMemorizationDecisionFit>,
    val modelPath: String,
    val modelSha256: String,
    val issue0026Reproduction: NeuralCandidateScaleReproduction?,
)

@Serializable
internal data class NeuralCandidateScaleStageResult(
    val decisions: Int,
    val maximumEpochs: Int,
    val subsetIdentity: String,
    val seeds: List<NeuralCandidateScaleSeedResult>,
    val reliablyMemorizedByAllSeeds: Boolean,
)

@Serializable
internal data class NeuralCandidateScaleConditionResult(
    val condition: String,
    val learningRate: Double,
    val stateInputScale: Double,
    val candidateInputScale: Double,
    val candidateProjectionUpdateScale: Double,
    val stages: List<NeuralCandidateScaleStageResult>,
)

@Serializable
internal data class Issue0027NeuralCandidateUpdateScaleReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0027_PROTOCOL,
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
    val maximumEpochs: Int,
    val perfectConfirmationEpochs: Int,
    val traceEpochs: List<Int>,
    val saturationAndCollapseDefinitions: List<String>,
    val interventionRationale: List<String>,
    val baseline: NeuralCandidateScaleConditionResult,
    val intervention: NeuralCandidateScaleConditionResult,
    val conditionalFullSetRunPerformed: Boolean,
    val diagnosticCase: String,
    val interpretation: List<String>,
    val limitations: List<String>,
    val narrowestNextQuestion: String,
)

private data class ObservedWitness(
    val epoch: Int,
    val fit: NeuralMemorizationDecisionFit,
)

internal class Issue0027NeuralCandidateUpdateScaleDiagnostic(
    private val root: Path,
    private val outputDirectory: Path,
) {
    fun run(
        historicalManifestPath: Path,
        progress: (String) -> Unit = {},
    ): Issue0027NeuralCandidateUpdateScaleReport {
        Files.createDirectories(outputDirectory)
        val implementation = requireNotNull(RunProvenance.capture(root).sourceProvenance)
        val population = Issue0022HistoricalCorpusReader(root).read(historicalManifestPath)
        val encoder = NeuralBehavioralCloningFeatureEncoder()
        val trainGames = population.split.trainGames.toSet()
        val train = population.examples.filter {
            it.gameId in trainGames && it.input.candidates.size >= PRIMARY_MIN_CANDIDATES
        }.map { it.encode(encoder) }
        require(train.size == ISSUE_0027_TRAINING_DECISIONS)
        val ordered = deterministicNeuralMemorizationOrder(
            train,
            population.manifest.datasetIdentity,
            ISSUE_0027_SUBSET_PROTOCOL,
        )
        val orderSha = PolicyJson.sha256(
            ordered.joinToString("\n") { "${it.gameId}:${it.decisionIndex}" }
        )
        require(orderSha == ISSUE_0027_EXPECTED_ORDER_SHA256)
        require(ordered.indexOfFirst {
            it.gameId == ISSUE_0027_WITNESS_GAME && it.decisionIndex == ISSUE_0027_WITNESS_DECISION
        } in 0 until 64) { "The fixed witness is no longer in the n=64 prefix" }
        progress("Read the exact issue-0022 training population and recovered the issue-0025/0026 nested order")

        val modelConfig = NeuralBcModelConfig(
            stateDimension = encoder.stateDimension,
            candidateDimension = encoder.candidateDimension,
        )
        val issue0026 = readIssue0026Reference(population, orderSha)
        val baseline = trainCondition(
            condition = "ISSUE_0026_STATE_SCALED_BASELINE",
            candidateProjectionUpdateScale = 1.0,
            ordered = ordered,
            modelConfig = modelConfig,
            stageCounts = listOf(16, 64),
            issue0026 = issue0026,
            progress = progress,
        )
        require(baseline.stages.flatMap(NeuralCandidateScaleStageResult::seeds).all { seed ->
            seed.issue0026Reproduction?.let {
                it.exactFitMetricsMatch && it.exactRecordedTrajectoryMatch &&
                    it.exactBestAndFinalGeometryMatch && it.exactModelSha256Match
            } == true
        }) { "The scale-control implementation or read-only instrumentation changed an issue-0026 result" }
        progress("Exactly reproduced all six fixed issue-0026 state-scaled baseline runs")

        val firstIntervention = trainCondition(
            condition = "CANDIDATE_PROJECTION_UPDATE_SCALE_ONE_OVER_8",
            candidateProjectionUpdateScale = ISSUE_0027_CANDIDATE_UPDATE_SCALE,
            ordered = ordered,
            modelConfig = modelConfig,
            stageCounts = listOf(16, 64),
            issue0026 = null,
            progress = progress,
        )
        val gatePassed = firstIntervention.stages.single { it.decisions == 64 }
            .reliablyMemorizedByAllSeeds
        val intervention = if (gatePassed) {
            progress("All three n=64 seeds met the 20-epoch reliability gate; running the bounded n=389 check")
            val full = trainCondition(
                condition = "CANDIDATE_PROJECTION_UPDATE_SCALE_ONE_OVER_8",
                candidateProjectionUpdateScale = ISSUE_0027_CANDIDATE_UPDATE_SCALE,
                ordered = ordered,
                modelConfig = modelConfig,
                stageCounts = listOf(389),
                issue0026 = null,
                progress = progress,
            )
            firstIntervention.copy(stages = firstIntervention.stages + full.stages)
        } else {
            progress("The n=64 reliability gate failed; stopping without a full-set run")
            firstIntervention
        }

        val baseline64 = baseline.stages.single { it.decisions == 64 }
        val intervention64 = intervention.stages.single { it.decisions == 64 }
        val baselineFinalSaturation = baseline64.seeds.map { it.finalCheckpoint.candidate.nearSaturatedFraction }.average()
        val interventionFinalSaturation = intervention64.seeds.map {
            it.finalCheckpoint.candidate.nearSaturatedFraction
        }.average()
        val materiallyReduced = interventionFinalSaturation <= baselineFinalSaturation - 0.10
        val diagnosticCase = when {
            intervention64.reliablyMemorizedByAllSeeds && materiallyReduced ->
                "CANDIDATE_UPDATE_SCALE_REDUCES_SATURATION_AND_RESTORES_RELIABLE_64_MEMORIZATION"
            intervention64.reliablyMemorizedByAllSeeds ->
                "CANDIDATE_UPDATE_SCALE_RESTORES_RELIABLE_64_MEMORIZATION_WITHOUT_MATERIAL_FINAL_SATURATION_REDUCTION"
            materiallyReduced ->
                "CANDIDATE_UPDATE_SCALE_REDUCES_SATURATION_BUT_DOES_NOT_RESTORE_RELIABLE_64_MEMORIZATION"
            else ->
                "CANDIDATE_UPDATE_SCALE_DOES_NOT_MATERIALLY_REDUCE_FINAL_SATURATION_OR_RESTORE_RELIABLE_64_MEMORIZATION"
        }
        val nextQuestion = when {
            intervention64.reliablyMemorizedByAllSeeds -> {
                val full = intervention.stages.single { it.decisions == 389 }
                if (full.reliablyMemorizedByAllSeeds) {
                    "Does the now-memorizable fixed mapping generalize under the untouched issue-0022 whole-game split?"
                } else {
                    "At what fixed nested size above 64 does reliable memorization fail under this unchanged intervention, and what projection geometry appears there?"
                }
            }
            materiallyReduced ->
                "Which remaining optimization or bilinear-interaction mechanism prevents stable strict ranking when candidate saturation is reduced?"
            else ->
                "Does candidate activation scale, rather than candidate parameter-update scale alone, control the remaining saturation and ranking instability?"
        }

        return Issue0027NeuralCandidateUpdateScaleReport(
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
            subsetSelectionProtocol = ISSUE_0027_SUBSET_PROTOCOL,
            subsetSelectionOrderSha256 = orderSha,
            modelConfig = modelConfig,
            optimizer = "issue-0022 SparseAdam at 0.01; intervention multiplies only post-Adam candidate weight/bias updates",
            initializationSeeds = ISSUE_0027_SEEDS,
            maximumEpochs = ISSUE_0027_MAXIMUM_EPOCHS,
            perfectConfirmationEpochs = ISSUE_0027_CONFIRMATION_EPOCHS,
            traceEpochs = ISSUE_0027_TRACE_EPOCHS.sorted(),
            saturationAndCollapseDefinitions = listOf(
                "Near saturated means |tanh(z)| >= 0.99; exactly saturated means the JVM Double tanh result is exactly -1.0 or 1.0.",
                "Mean projection derivative is the arithmetic mean of 1 - tanh(z)^2 over all observed projection values.",
                "State collapse means distinct exact raw encoded states share an exactly equal learned 32-value query vector.",
                "Learned candidate collapse means distinct exact raw candidate vectors in one current decision share an exactly equal learned 32-value candidate projection; raw candidate aliases are counted separately.",
                "Strict fit requires the teacher score to exceed every alternative score; equality is incorrect.",
                "Reliable memorization requires 20 consecutive strict-perfect epochs, not merely one 64/64 epoch.",
            ),
            interventionRationale = listOf(
                "With state input scale fixed at 1/32, issue 0026 candidate projections begin healthy (0.08% to 0.76% near saturation) but training drives final near saturation to 95.16% to 96.26%.",
                "Candidate weight RMS grows from about 0.060 to 0.69 to 0.73 and candidate bias RMS from zero to 1.18 to 1.50, directly supporting an update-driven candidate-scale test.",
                "A 1/8 candidate-only post-Adam update multiplier is the smallest power-of-two reduction whose nominal 1,500-epoch candidate-update horizon (187.5 baseline-equivalent epochs) remains beyond the latest prior first-perfect epoch (125) but before the first recorded checkpoint (192) where every seed exceeded 75% candidate near saturation.",
                "The factor was fixed from issue-0026 trajectories before this run; no alternative factor was trained.",
            ),
            baseline = baseline,
            intervention = intervention,
            conditionalFullSetRunPerformed = gatePassed,
            diagnosticCase = diagnosticCase,
            interpretation = candidateScaleInterpretation(baseline, intervention, materiallyReduced),
            limitations = listOf(
                "This is fixed-training-population optimization evidence, not held-out or playing-strength evidence.",
                "The 1/8 factor is an evidence-selected mechanism probe, not a sweep result or a production hyperparameter recommendation.",
                "Adam moments are unchanged; the multiplier applies only to the normalized candidate weight and bias parameter step. State weights, state bias, global query, data, objective, shuffle order, and epoch budget are unchanged.",
                "The nominal baseline-equivalent epoch argument is only a selection heuristic because adaptive gradients and the coupled state/query trajectory prevent literal time rescaling.",
                "Exact projection equality detects severe finite-precision collapse but does not quantify merely close hidden vectors.",
                "No chart is added: the fixed-seed checkpoint tables preserve the exact audit values and make the small controlled comparison more directly reviewable.",
            ),
            narrowestNextQuestion = nextQuestion,
        )
    }

    private fun readIssue0026Reference(
        population: Issue0022HistoricalPopulation,
        orderSha: String,
    ): Issue0026NeuralSaturationTrajectoryReport {
        val path = EvidenceStore(root).latest("neural-saturation-trajectory-diagnostic/artifact.json")
        val report = evidenceJson.decodeFromString<Issue0026NeuralSaturationTrajectoryReport>(Files.readString(path))
        require(report.protocol == ISSUE_0026_PROTOCOL)
        require(report.corpusManifestSha256 == population.manifestSha256)
        require(report.splitSha256 == population.splitSha256)
        require(report.subsetSelectionOrderSha256 == orderSha)
        require(report.intervention.stateInputScale == ISSUE_0027_STATE_INPUT_SCALE)
        require(report.intervention.candidateInputScale == 1.0)
        return report
    }

    private fun trainCondition(
        condition: String,
        candidateProjectionUpdateScale: Double,
        ordered: List<EncodedBcDecision>,
        modelConfig: NeuralBcModelConfig,
        stageCounts: List<Int>,
        issue0026: Issue0026NeuralSaturationTrajectoryReport?,
        progress: (String) -> Unit,
    ): NeuralCandidateScaleConditionResult {
        val stages = stageCounts.map { count ->
            val rawSubset = ordered.take(count)
            val subset = rawSubset.map { it.withStateInputScale(ISSUE_0027_STATE_INPUT_SCALE) }
            val subsetIdentity = PolicyJson.sha256(
                rawSubset.joinToString("\n") { "${it.gameId}:${it.decisionIndex}" }
            )
            val referenceStage = issue0026?.intervention?.stages?.single { it.decisions == count }
            val witness = subset.singleOrNull {
                it.gameId == ISSUE_0027_WITNESS_GAME && it.decisionIndex == ISSUE_0027_WITNESS_DECISION
            }
            val seeds = ISSUE_0027_SEEDS.map { seed ->
                progress("Training $condition n=$count seed=$seed")
                val trace = mutableListOf<NeuralProjectionTracePoint>()
                val witnessObservations = mutableListOf<ObservedWitness>()
                val trained = NeuralBcMemorizationTrainer(
                    modelConfig = modelConfig,
                    trainingConfig = NeuralBcTrainingConfig(
                        maximumEpochs = ISSUE_0027_MAXIMUM_EPOCHS,
                        learningRate = 0.01,
                        candidateProjectionUpdateScale = candidateProjectionUpdateScale,
                        initializationSeeds = ISSUE_0027_SEEDS,
                    ),
                    perfectConfirmationEpochs = ISSUE_0027_CONFIRMATION_EPOCHS,
                ).train(subset, seed) { policy, metric ->
                    if (metric.epoch in ISSUE_0027_TRACE_EPOCHS) {
                        trace += traceNeuralBcProjection(policy, subset, metric)
                    }
                    witness?.let { decision ->
                        witnessObservations += ObservedWitness(
                            metric.epoch,
                            neuralMemorizationDecisionFit(policy, decision),
                        )
                    }
                }
                val modelPath = outputDirectory.resolve(
                    "${condition.lowercase()}-n$count-seed-$seed.json"
                )
                trained.policy.save(modelPath)
                val modelSha = sha256File(modelPath)
                val bestMetric = trained.epochMetrics.single { it.epoch == trained.bestEpoch }
                val finalMetric = trained.epochMetrics.last()
                val bestCheckpoint = traceNeuralBcProjection(trained.policy, subset, bestMetric)
                val finalCheckpoint = traceNeuralBcProjection(trained.finalPolicy, subset, finalMetric)
                val reference = referenceStage?.seeds?.single { it.seed == seed }
                val reproduction = reference?.let {
                    NeuralCandidateScaleReproduction(
                        referenceProtocol = ISSUE_0026_PROTOCOL,
                        expectedEpochsCompleted = it.epochsCompleted,
                        expectedFirstPerfectEpoch = it.firstPerfectEpoch,
                        expectedBestEpoch = it.bestEpoch,
                        expectedBestStrictRankingCorrect = it.bestStrictRankingCorrect,
                        expectedFinalStrictRankingCorrect = it.finalStrictRankingCorrect,
                        expectedFinalMeanCrossEntropy = it.finalMeanCrossEntropy,
                        expectedModelSha256 = it.modelSha256,
                        exactFitMetricsMatch = trained.epochsCompleted == it.epochsCompleted &&
                            trained.stoppedAfterPerfectConfirmation == it.stoppedAfterPerfectConfirmation &&
                            trained.firstPerfectEpoch == it.firstPerfectEpoch &&
                            trained.bestEpoch == it.bestEpoch &&
                            trained.bestStrictRankingCorrect == it.bestStrictRankingCorrect &&
                            finalMetric.strictRankingCorrect == it.finalStrictRankingCorrect &&
                            trained.finalMeanCrossEntropy == it.finalMeanCrossEntropy,
                        exactRecordedTrajectoryMatch = sameTraceIgnoringCandidateCollapse(trace, it.trace),
                        exactBestAndFinalGeometryMatch =
                            sameTracePointIgnoringCandidateCollapse(bestCheckpoint, it.bestCheckpoint) &&
                                sameTracePointIgnoringCandidateCollapse(finalCheckpoint, it.finalCheckpoint),
                        exactModelSha256Match = modelSha == it.modelSha256,
                    )
                }
                val witnessResult = witness?.let { decision ->
                    summarizeWitness(
                        decision = decision,
                        observations = witnessObservations,
                        bestEpoch = trained.bestEpoch,
                        issue0026Seed3253BestMargin = if (seed == 3253L && count == 64) {
                            reference?.bestCheckpointMisrankedDecisions?.single {
                                it.decision.gameId == ISSUE_0027_WITNESS_GAME &&
                                    it.decision.decisionIndex == ISSUE_0027_WITNESS_DECISION
                            }?.teacherMargin
                        } else {
                            null
                        },
                    )
                }
                progress(
                    "Finished $condition n=$count seed=$seed: first=${trained.firstPerfectEpoch ?: "never"}, " +
                        "best=${trained.bestStrictRankingCorrect}/$count, final=${finalMetric.strictRankingCorrect}/$count, " +
                        "confirmed=${trained.stoppedAfterPerfectConfirmation}"
                )
                NeuralCandidateScaleSeedResult(
                    seed = seed,
                    epochsCompleted = trained.epochsCompleted,
                    stoppedAfterPerfectConfirmation = trained.stoppedAfterPerfectConfirmation,
                    firstPerfectEpoch = trained.firstPerfectEpoch,
                    longestPerfectStreak = longestPerfectStreak(trained.epochMetrics, count),
                    bestEpoch = trained.bestEpoch,
                    bestStrictRankingCorrect = trained.bestStrictRankingCorrect,
                    bestStrictRankingAccuracy = trained.bestStrictRankingAccuracy,
                    bestMeanCrossEntropy = trained.bestMeanCrossEntropy,
                    bestMinimumTeacherMargin = trained.bestMinimumTeacherMargin,
                    finalStrictRankingCorrect = finalMetric.strictRankingCorrect,
                    finalStrictRankingAccuracy = trained.finalStrictRankingAccuracy,
                    finalMeanCrossEntropy = trained.finalMeanCrossEntropy,
                    minimumObservedMeanCrossEntropy = trained.minimumObservedMeanCrossEntropy,
                    trace = trace,
                    bestCheckpoint = bestCheckpoint,
                    finalCheckpoint = finalCheckpoint,
                    witness = witnessResult,
                    bestCheckpointMisrankedDecisions = trained.retainedHardDecisions.filterNot {
                        it.strictRankingCorrect
                    },
                    modelPath = root.relativize(modelPath).toString(),
                    modelSha256 = modelSha,
                    issue0026Reproduction = reproduction,
                )
            }
            NeuralCandidateScaleStageResult(
                decisions = count,
                maximumEpochs = ISSUE_0027_MAXIMUM_EPOCHS,
                subsetIdentity = subsetIdentity,
                seeds = seeds,
                reliablyMemorizedByAllSeeds = seeds.all {
                    it.stoppedAfterPerfectConfirmation && it.longestPerfectStreak >= ISSUE_0027_CONFIRMATION_EPOCHS
                },
            )
        }
        return NeuralCandidateScaleConditionResult(
            condition = condition,
            learningRate = 0.01,
            stateInputScale = ISSUE_0027_STATE_INPUT_SCALE,
            candidateInputScale = 1.0,
            candidateProjectionUpdateScale = candidateProjectionUpdateScale,
            stages = stages,
        )
    }
}

private fun sameTraceIgnoringCandidateCollapse(
    actual: List<NeuralProjectionTracePoint>,
    expected: List<NeuralProjectionTracePoint>,
): Boolean = actual.size == expected.size && actual.indices.all { index ->
    sameTracePointIgnoringCandidateCollapse(actual[index], expected[index])
}

private fun sameTracePointIgnoringCandidateCollapse(
    actual: NeuralProjectionTracePoint,
    expected: NeuralProjectionTracePoint,
): Boolean = actual.copy(candidateCollapse = NeuralCandidateProjectionCollapseSummary()) ==
    expected.copy(candidateCollapse = NeuralCandidateProjectionCollapseSummary())

private fun longestPerfectStreak(
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

private fun summarizeWitness(
    decision: EncodedBcDecision,
    observations: List<ObservedWitness>,
    bestEpoch: Int,
    issue0026Seed3253BestMargin: Double?,
): NeuralWitnessTrajectory {
    require(observations.isNotEmpty())
    var streak = 0
    var longest = 0
    var firstConfirmed: Int? = null
    observations.forEach { observed ->
        streak = if (observed.fit.strictRankingCorrect) streak + 1 else 0
        longest = maxOf(longest, streak)
        if (streak == ISSUE_0027_CONFIRMATION_EPOCHS && firstConfirmed == null) {
            firstConfirmed = observed.epoch
        }
    }
    val maximum = observations.maxBy { it.fit.teacherMargin }
    val firstPositive = observations.firstOrNull { it.fit.strictRankingCorrect }?.epoch
    val importantEpochs = ISSUE_0027_TRACE_EPOCHS + setOfNotNull(
        firstPositive,
        firstConfirmed,
        maximum.epoch,
        bestEpoch,
        observations.last().epoch,
    )
    fun ObservedWitness.checkpoint() = NeuralWitnessCheckpoint(
        epoch = epoch,
        teacherMargin = fit.teacherMargin,
        meanCrossEntropyContribution = fit.meanCrossEntropyContribution,
        strictRankingCorrect = fit.strictRankingCorrect,
        predictedCandidateIndex = fit.predictedCandidateIndex,
        predictedIntent = fit.predictedIntent,
    )
    return NeuralWitnessTrajectory(
        decision = decision.neuralDecisionReference(),
        teacherCandidateIndex = decision.labelIndex,
        teacherIntent = decision.candidateIntents[decision.labelIndex].name,
        issue0026Seed3253BestMargin = issue0026Seed3253BestMargin,
        firstStrictlyPositiveEpoch = firstPositive,
        firstTwentyConsecutivePositiveEpoch = firstConfirmed,
        longestStrictlyPositiveStreak = longest,
        maximumMarginCheckpoint = maximum.checkpoint(),
        bestModelCheckpoint = observations.single { it.epoch == bestEpoch }.checkpoint(),
        finalCheckpoint = observations.last().checkpoint(),
        selectedCheckpoints = observations.filter { it.epoch in importantEpochs }.map {
            it.checkpoint()
        },
    )
}

private fun candidateScaleInterpretation(
    baseline: NeuralCandidateScaleConditionResult,
    intervention: NeuralCandidateScaleConditionResult,
    materiallyReduced: Boolean,
): List<String> {
    fun pctRange(values: List<Double>): String =
        "%.2f–%.2f%%".format(values.minOrNull()!! * 100.0, values.maxOrNull()!! * 100.0)
    val baseline64 = baseline.stages.single { it.decisions == 64 }
    val intervention16 = intervention.stages.single { it.decisions == 16 }
    val intervention64 = intervention.stages.single { it.decisions == 64 }
    val baselineFinal = baseline64.seeds.map { it.finalCheckpoint }
    val interventionFinal = intervention64.seeds.map { it.finalCheckpoint }
    return buildList {
        add(
            "Default candidate update scale 1.0 exactly reproduces all six issue-0026 state-scaled " +
                "n=16/n=64 fit results, recorded projection trajectories, best/final geometry, and selected model hashes."
        )
        add(
            "Candidate initialization is unchanged and healthy; the intervention acts only after gradients exist. " +
                "At final n=64 checkpoints, candidate near saturation changes from " +
                "${pctRange(baselineFinal.map { it.candidate.nearSaturatedFraction })} to " +
                "${pctRange(interventionFinal.map { it.candidate.nearSaturatedFraction })}."
        )
        add(
            "The n=16 control best fits are " +
                intervention16.seeds.joinToString(" / ") { "${it.bestStrictRankingCorrect}/16" } +
                " and all-seed 20-epoch confirmation is ${intervention16.reliablyMemorizedByAllSeeds}."
        )
        add(
            "The n=64 intervention best fits are " +
                intervention64.seeds.joinToString(" / ") { "${it.bestStrictRankingCorrect}/64" } +
                "; final fits are " +
                intervention64.seeds.joinToString(" / ") { "${it.finalStrictRankingCorrect}/64" } +
                "; all-seed 20-epoch confirmation is ${intervention64.reliablyMemorizedByAllSeeds}."
        )
        add(
            if (materiallyReduced) {
                "The candidate-only update correction materially reduces measured final saturation."
            } else {
                "The candidate-only update correction does not reduce mean final candidate near saturation by the prespecified 10 percentage-point materiality threshold."
            }
        )
        if (intervention64.reliablyMemorizedByAllSeeds) {
            val full = intervention.stages.single { it.decisions == 389 }
            add(
                "Because all three n=64 seeds met the reliability gate, the unchanged n=389 follow-up was run; " +
                    "best fits are ${full.seeds.joinToString(" / ") { "${it.bestStrictRankingCorrect}/389" }} and " +
                    "all-seed confirmation is ${full.reliablyMemorizedByAllSeeds}."
            )
        } else {
            add("The prespecified gate failed, so no n=389 run was performed and no further factor was tried.")
        }
    }
}

internal fun renderIssue0027NeuralCandidateUpdateScale(
    report: Issue0027NeuralCandidateUpdateScaleReport,
): String = buildString {
    fun pct(value: Double): String = "%.2f%%".format(value * 100.0)
    fun number(value: Double): String = "%.6f".format(value)
    appendLine("# Candidate-projection update-scale diagnostic")
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
    appendLine("- Epoch budget / strict-perfect confirmation: ${report.maximumEpochs} / ${report.perfectConfirmationEpochs}")
    appendLine()
    appendLine("## Definitions")
    appendLine()
    report.saturationAndCollapseDefinitions.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Why exactly one-eighth")
    appendLine()
    report.interventionRationale.forEach { appendLine("- $it") }
    appendLine()
    renderCandidateScaleCondition(report.baseline, this)
    renderCandidateScaleCondition(report.intervention, this)
    appendLine("## Witness: `$ISSUE_0027_WITNESS_GAME:$ISSUE_0027_WITNESS_DECISION`")
    appendLine()
    appendLine("| Condition | n | Seed | Prior issue-0026 best margin | First positive | First 20-positive endpoint | Longest positive streak | Max margin (epoch) | Best-model margin | Final margin |")
    appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: |")
    listOf(report.baseline, report.intervention).forEach { condition ->
        condition.stages.filter { it.decisions >= 64 }.forEach { stage ->
            stage.seeds.forEach { seed ->
                seed.witness?.let { witness ->
                    appendLine(
                        "| ${condition.condition} | ${stage.decisions} | ${seed.seed} | " +
                            "${witness.issue0026Seed3253BestMargin?.let(::number) ?: "—"} | " +
                            "${witness.firstStrictlyPositiveEpoch ?: "—"} | " +
                            "${witness.firstTwentyConsecutivePositiveEpoch ?: "—"} | " +
                            "${witness.longestStrictlyPositiveStreak} | " +
                            "${number(witness.maximumMarginCheckpoint.teacherMargin)} (${witness.maximumMarginCheckpoint.epoch}) | " +
                            "${number(witness.bestModelCheckpoint.teacherMargin)} | " +
                            "${number(witness.finalCheckpoint.teacherMargin)} |"
                    )
                }
            }
        }
    }
    appendLine()
    appendLine("## Interpretation boundary")
    appendLine()
    appendLine("Conditional n=389 run performed: `${report.conditionalFullSetRunPerformed}`.")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
    appendLine()
    appendLine("Narrowest next question: ${report.narrowestNextQuestion}")
}

private fun renderCandidateScaleCondition(
    condition: NeuralCandidateScaleConditionResult,
    output: StringBuilder,
) = with(output) {
    fun pct(value: Double): String = "%.2f%%".format(value * 100.0)
    fun number(value: Double): String = "%.6f".format(value)
    appendLine("## ${condition.condition}")
    appendLine()
    appendLine(
        "Learning rate ${condition.learningRate}; state/candidate input scales " +
            "${condition.stateInputScale}/${condition.candidateInputScale}; candidate update scale " +
            "${condition.candidateProjectionUpdateScale}."
    )
    appendLine()
    appendLine("| n | Seed | First perfect | Longest perfect streak / confirmed | Best epoch / strict / loss | Final strict / loss | Minimum loss | Exact issue-0026 reproduction |")
    appendLine("| ---: | ---: | ---: | --- | --- | --- | ---: | --- |")
    condition.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            val reproduced = seed.issue0026Reproduction?.let {
                it.exactFitMetricsMatch && it.exactRecordedTrajectoryMatch &&
                    it.exactBestAndFinalGeometryMatch && it.exactModelSha256Match
            }
            appendLine(
                "| ${stage.decisions} | ${seed.seed} | ${seed.firstPerfectEpoch ?: "—"} | " +
                    "${seed.longestPerfectStreak} / ${seed.stoppedAfterPerfectConfirmation} | " +
                    "${seed.bestEpoch} / ${seed.bestStrictRankingCorrect}/${stage.decisions} / ${number(seed.bestMeanCrossEntropy)} | " +
                    "${seed.finalStrictRankingCorrect}/${stage.decisions} / ${number(seed.finalMeanCrossEntropy)} | " +
                    "${number(seed.minimumObservedMeanCrossEntropy)} | ${reproduced ?: "n/a"} |"
            )
        }
    }
    appendLine()
    appendLine("All-seed 20-consecutive reliability: " + condition.stages.joinToString { stage ->
        "n=${stage.decisions}: ${stage.reliablyMemorizedByAllSeeds}"
    })
    appendLine()
    appendLine("### Initialization, early, best, and final geometry")
    appendLine()
    appendLine("| n | Seed | Checkpoint | Epoch | Strict / loss | Candidate near / exact / derivative | Candidate weight / bias RMS | State repeats / largest | Learned candidate collapses / teacher labels | Hidden contradictions / affected |")
    appendLine("| ---: | ---: | --- | ---: | --- | --- | --- | --- | --- | --- |")
    condition.stages.forEach { stage ->
        stage.seeds.forEach { seed ->
            val selected = buildList {
                listOf(0, 1, 8, 24, 96, 192).forEach { epoch ->
                    seed.trace.singleOrNull { it.epoch == epoch }?.let { add("trace" to it) }
                }
                add("best" to seed.bestCheckpoint)
                add("final" to seed.finalCheckpoint)
            }.distinctBy { (name, point) -> name to point.epoch }
            selected.forEach { (name, point) ->
                appendLine(
                    "| ${stage.decisions} | ${seed.seed} | $name | ${point.epoch} | " +
                        "${point.strictRankingCorrect}/${stage.decisions} / ${number(point.meanCrossEntropy)} | " +
                        "${pct(point.candidate.nearSaturatedFraction)} / ${pct(point.candidate.exactlySaturatedFraction)} / ${"%.3e".format(point.candidate.meanTanhDerivative)} | " +
                        "${number(point.parameters.candidateWeightRms)} / ${number(point.parameters.candidateBiasRms)} | " +
                        "${point.collapse.repeatedProjectedStateGroups} / ${point.collapse.largestRepeatedProjectedStateGroup} | " +
                        "${point.candidateCollapse.learnedCandidateCollapseGroups} / ${point.candidateCollapse.teacherLabelsInLearnedCandidateCollapseGroups} | " +
                        "${point.collapse.contradictoryRankingComponents} / ${point.collapse.decisionsAffectedByContradictions} |"
                )
            }
        }
    }
    appendLine()
    val misses = condition.stages.flatMap { stage ->
        stage.seeds.flatMap { seed ->
            seed.bestCheckpointMisrankedDecisions.map { Triple(stage.decisions, seed.seed, it) }
        }
    }
    if (misses.isNotEmpty()) {
        appendLine("Best-checkpoint strict misses:")
        misses.forEach { (decisions, seed, fit) ->
            appendLine(
                "- n=$decisions seed=$seed `${fit.decision.gameId}:${fit.decision.decisionIndex}`: " +
                    "teacher ${fit.teacherIntent}, predicted ${fit.predictedIntent}, loss " +
                    "${number(fit.meanCrossEntropyContribution)}, margin ${number(fit.teacherMargin)}"
            )
        }
        appendLine()
    }
}
