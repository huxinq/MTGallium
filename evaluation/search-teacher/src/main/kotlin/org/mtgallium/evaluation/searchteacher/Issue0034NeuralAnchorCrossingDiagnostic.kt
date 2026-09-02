package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val ISSUE_0034_PROTOCOL = "issue-0034-fixed-v3-neural-anchor-first-crossing-v1"
private const val ISSUE_0034_ANCHOR_DECISIONS = 323
private const val ISSUE_0034_REFERENCE_ARTIFACT =
    "neural-cohort-continuation-diagnostic/artifact.json"
private const val ISSUE_0034_EXPECTED_REFERENCE_SHA256 =
    "6e7d31c9da235e5487c8c12dff4f5e3e66d70ac9b562c539674361a00e5b70ea"
private val ISSUE_0034_GROUPS = listOf("STATE_AND_QUERY", "CANDIDATE_PROJECTION", "ALL_PARAMETERS")

@Serializable
internal data class Issue0034CandidateIdentity(
    val candidateIndex: Int,
    val signature: String,
    val operationFamily: String,
    val actionIntent: String,
    val sourceCardName: String?,
    val displayLabel: String,
    val displayTargets: List<String>,
)

@Serializable
internal data class Issue0034CrossedMargin(
    val vulnerableDecision: NeuralDecisionReference,
    val teacherCandidate: Issue0034CandidateIdentity,
    val preUpdateBestCompetitor: Issue0034CandidateIdentity,
    val postUpdateBestCompetitor: Issue0034CandidateIdentity,
    val preUpdateTeacherMargin: Double,
    val postUpdateTeacherMargin: Double,
    val preUpdateMarginAgainstPostCompetitor: Double,
    val postUpdateMarginAgainstPostCompetitor: Double,
    val exactActualChangeAgainstPostCompetitor: Double,
    val actualDeltaFirstOrderEffect: Double,
    val rawGradientDescentFirstOrderEffect: Double,
    val recipeScaledRawGradientDescentFirstOrderEffect: Double,
    val resetAdamDeltaFirstOrderEffect: Double,
    val resetAdamPostMarginAgainstPostCompetitor: Double,
    val resetAdamCrossesThisConstraint: Boolean,
)

@Serializable
internal data class Issue0034UpdateGeometry(
    val parameterGroup: String,
    val rawGradientNorm: Double,
    val recipeScaledRawDescentNorm: Double,
    val actualParameterDeltaNorm: Double,
    val resetAdamParameterDeltaNorm: Double,
    val actualDeltaCosineWithRawDescent: Double?,
    val actualDeltaCosineWithRecipeScaledRawDescent: Double?,
    val resetAdamDeltaCosineWithRecipeScaledRawDescent: Double?,
    val actualDeltaCosineWithResetAdamDelta: Double?,
)

@Serializable
internal data class Issue0034ParameterVector(
    val stateWeights: DoubleArray,
    val stateBias: DoubleArray,
    val candidateWeights: DoubleArray,
    val candidateBias: DoubleArray,
    val globalQuery: DoubleArray,
)

@Serializable
internal data class Issue0034MechanismVectors(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0034_PROTOCOL,
    val seed: Long,
    val postForkDecisionStep: Long,
    val triggeringDecision: NeuralDecisionReference,
    val rawCurrentDecisionGradient: Issue0034ParameterVector,
    val recipeScaledRawDescentDirection: Issue0034ParameterVector,
    val actualSparseAdamParameterDelta: Issue0034ParameterVector,
    val resetSparseAdamParameterDelta: Issue0034ParameterVector,
)

@Serializable
internal data class Issue0034CrossingStateCheckpoint(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0034_PROTOCOL,
    val seed: Long,
    val state: String,
    val postForkDecisionStepsCompleted: Long,
    val absoluteEpoch: Int,
    val withinEpochUpdatesCompleted: Int,
    val model: NeuralBcModelArtifact,
    val optimizer: SparseAdamState,
)

@Serializable
internal data class Issue0034SeedResult(
    val seed: Long,
    val sourceForkPath: String,
    val sourceForkSha256: String,
    val forkAnchorEpoch: Int,
    val forkOptimizerDecisionSteps: Int,
    val forkMinimumTeacherMargin: Double,
    val issue0031FirstObservedFailureUpperBound: Long,
    val firstCrossingPostForkDecisionStep: Long,
    val absoluteEpoch: Int,
    val withinEpochPosition: Int,
    val triggeringTrainingDecision: NeuralDecisionReference,
    val triggeringDecisionTeacherCandidate: Issue0034CandidateIdentity,
    val triggeringDecisionPreUpdateMargin: Double,
    val preUpdateMinimumAnchorMargin: Double,
    val postUpdateMinimumAnchorMargin: Double,
    val crossedMargins: List<Issue0034CrossedMargin>,
    val updateGeometry: List<Issue0034UpdateGeometry>,
    val preCrossingCheckpointPath: String,
    val preCrossingCheckpointSha256: String,
    val postCrossingCheckpointPath: String,
    val postCrossingCheckpointSha256: String,
    val mechanismVectorsPath: String,
    val mechanismVectorsSha256: String,
    val exactReconstructionMatchesLocalizedAfterState: Boolean,
    val localMechanismCase: String,
)

@Serializable
internal data class Issue0034NeuralAnchorCrossingReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0034_PROTOCOL,
    val generatedAtUtc: String,
    val implementationSourceProvenance: PolicySourceProvenance,
    val historicalSourceProvenance: PolicySourceProvenance,
    val historicalCandidateSchemaVersion: Int,
    val featureSchema: String,
    val corpusManifestPath: String,
    val corpusManifestSha256: String,
    val splitPath: String,
    val splitSha256: String,
    val subsetSelectionProtocol: String,
    val subsetSelectionOrderSha256: String,
    val anchorDecisions: Int,
    val anchorIdentity: String,
    val issue0031ReferenceArtifactPath: String,
    val issue0031ReferenceArtifactSha256: String,
    val modelConfig: NeuralBcModelConfig,
    val trainingConfig: NeuralBcTrainingConfig,
    val initializationSeeds: List<Long>,
    val replayDefinition: List<String>,
    val mechanismDefinition: List<String>,
    val seeds: List<Issue0034SeedResult>,
    val diagnosticCase: String,
    val interpretation: List<String>,
    val limitations: List<String>,
    val narrowestNextQuestion: String,
)

@Serializable
internal data class Issue0034PreflightSeed(
    val seed: Long,
    val sourceForkSha256: String,
    val forkEpoch: Int,
    val forkOptimizerDecisionSteps: Int,
    val forkStrictRankingCorrect: Int,
    val forkMinimumTeacherMargin: Double,
    val deterministicNextDecision: NeuralDecisionReference,
    val restoredNextUpdateMatchesExactly: Boolean,
)

@Serializable
internal data class Issue0034PreflightReport(
    val protocol: String = ISSUE_0034_PROTOCOL,
    val corpusManifestSha256: String,
    val splitSha256: String,
    val orderSha256: String,
    val anchorIdentity: String,
    val issue0031ReferenceSha256: String,
    val seeds: List<Issue0034PreflightSeed>,
    val researchTrainingUpdatesRetainedByPreflight: Int = 0,
    val researchArtifactsEmittedByPreflight: Int = 0,
)

private data class Issue0034Prepared(
    val inputs: CorrectedNeuralDiagnosticInputs,
    val anchor: List<EncodedBcDecision>,
    val anchorIdentity: String,
    val referencePath: Path,
    val referenceSha: String,
    val reference: Issue0031NeuralCohortContinuationReport,
    val forks: Map<Long, LoadedIssue0034Fork>,
)

private data class LoadedIssue0034Fork(
    val path: Path,
    val sha256: String,
    val checkpoint: Issue0031TrainingForkCheckpoint,
    val reference: Issue0031SeedResult,
)

private data class Issue0034RankingSnapshot(
    val decision: EncodedBcDecision,
    val scores: DoubleArray,
    val teacherIndex: Int,
    val bestAlternativeIndex: Int,
) {
    val margin: Double get() = scores[teacherIndex] - scores[bestAlternativeIndex]
}

private data class Issue0034LocalizedCrossing(
    val postForkStep: Long,
    val absoluteEpoch: Int,
    val withinEpochPosition: Int,
    val order: List<EncodedBcDecision>,
    val triggeringDecision: EncodedBcDecision,
    val preRankings: List<Issue0034RankingSnapshot>,
    val postRankings: List<Issue0034RankingSnapshot>,
    val crossingIndices: List<Int>,
    val epochStartModel: NeuralBcModelArtifact,
    val epochStartOptimizer: SparseAdamState,
    val localizedAfterModel: NeuralBcModelArtifact,
    val localizedAfterOptimizer: SparseAdamState,
)

private data class Issue0034ExactCrossingState(
    val preModel: NeuralBcModelArtifact,
    val preOptimizer: SparseAdamState,
    val postModel: NeuralBcModelArtifact,
    val postOptimizer: SparseAdamState,
)

internal class Issue0034NeuralAnchorCrossingDiagnostic(
    private val root: Path,
    private val outputDirectory: Path,
) {
    fun preflight(historicalManifestPath: Path): Issue0034PreflightReport {
        val prepared = prepare(historicalManifestPath)
        val seeds = CORRECTED_NEURAL_SEEDS.map { seed ->
            val loaded = prepared.forks.getValue(seed)
            val fork = loaded.checkpoint
            val forkModel = copyNeuralBcModelArtifact(fork.model)
            val forkAdam = SparseAdam(forkModel, prepared.inputs.trainingConfig, fork.optimizer)
            val restoredModel = copyNeuralBcModelArtifact(fork.model)
            val restoredAdam = SparseAdam(restoredModel, prepared.inputs.trainingConfig, fork.optimizer)
            val firstDecision = issue0034EpochOrder(prepared.anchor, seed, fork.completedAnchorEpochs).first()
            val forkMetrics = cohortMetrics(
                CandidateConditionedNeuralPolicy.fromArtifact(fork.model),
                prepared.anchor,
            )
            require(forkMetrics.strictRankingCorrect == ISSUE_0034_ANCHOR_DECISIONS)
            val beforeFits = issue0034Rankings(fork.model, prepared.anchor)
            require(beforeFits.all { it.margin > 0.0 })
            forkAdam.step(firstDecision)
            restoredAdam.step(firstDecision)
            requireIssue0034ModelEquals(forkModel, restoredModel)
            require(sameSparseAdamState(forkAdam.snapshotState(), restoredAdam.snapshotState()))
            val afterFits = issue0034Rankings(forkModel, prepared.anchor)
            require(afterFits.size == beforeFits.size && afterFits.all { it.margin.isFinite() })
            Issue0034PreflightSeed(
                seed = seed,
                sourceForkSha256 = loaded.sha256,
                forkEpoch = fork.completedAnchorEpochs,
                forkOptimizerDecisionSteps = fork.optimizer.decisionSteps,
                forkStrictRankingCorrect = forkMetrics.strictRankingCorrect,
                forkMinimumTeacherMargin = forkMetrics.margins.minimum,
                deterministicNextDecision = firstDecision.neuralDecisionReference(),
                restoredNextUpdateMatchesExactly = true,
            )
        }
        return Issue0034PreflightReport(
            corpusManifestSha256 = prepared.inputs.population.manifestSha256,
            splitSha256 = prepared.inputs.population.splitSha256,
            orderSha256 = prepared.inputs.subsetSelectionOrderSha256,
            anchorIdentity = prepared.anchorIdentity,
            issue0031ReferenceSha256 = prepared.referenceSha,
            seeds = seeds,
        )
    }

    fun run(
        historicalManifestPath: Path,
        progress: (String) -> Unit = {},
    ): Issue0034NeuralAnchorCrossingReport {
        Files.createDirectories(outputDirectory)
        val prepared = prepare(historicalManifestPath)
        val durableProgress = Issue0034DurableProgress()
        durableProgress.publish(0, "input-validation", "verified retained forks and fixed anchor")
        val results = CORRECTED_NEURAL_SEEDS.mapIndexed { index, seed ->
            progress("Seed $seed: localizing the first individual anchor-only crossing")
            durableProgress.publish(index, "step-localization", "seed $seed")
            locateAndAnalyze(prepared, prepared.forks.getValue(seed), progress).also { result ->
                durableProgress.publish(
                    index + 1,
                    "seed-complete",
                    "seed $seed crossed at post-fork update ${result.firstCrossingPostForkDecisionStep}",
                )
            }
        }
        val diagnosticCase = issue0034DiagnosticCase(results)
        durableProgress.publish(3, "complete", diagnosticCase)
        return Issue0034NeuralAnchorCrossingReport(
            generatedAtUtc = Instant.now().toString(),
            implementationSourceProvenance = prepared.inputs.implementationSourceProvenance,
            historicalSourceProvenance = prepared.inputs.population.manifest.sourceProvenance,
            historicalCandidateSchemaVersion = CANDIDATE_SCHEMA_V3,
            featureSchema = NEURAL_BC_FEATURE_SCHEMA,
            corpusManifestPath = root.relativize(prepared.inputs.population.manifestPath).toString(),
            corpusManifestSha256 = prepared.inputs.population.manifestSha256,
            splitPath = root.relativize(prepared.inputs.population.splitPath).toString(),
            splitSha256 = prepared.inputs.population.splitSha256,
            subsetSelectionProtocol = CORRECTED_NEURAL_SUBSET_PROTOCOL,
            subsetSelectionOrderSha256 = prepared.inputs.subsetSelectionOrderSha256,
            anchorDecisions = prepared.anchor.size,
            anchorIdentity = prepared.anchorIdentity,
            issue0031ReferenceArtifactPath = root.relativize(prepared.referencePath).toString(),
            issue0031ReferenceArtifactSha256 = prepared.referenceSha,
            modelConfig = prepared.inputs.modelConfig,
            trainingConfig = prepared.inputs.trainingConfig,
            initializationSeeds = CORRECTED_NEURAL_SEEDS,
            replayDefinition = listOf(
                "Each seed begins from the retained issue-0031 fork checkpoint; n=323 is not retrained.",
                "Epoch order is anchor.shuffled(Random(seed xor absoluteEpoch)); absoluteEpoch begins at the checkpoint's nextAbsoluteEpoch.",
                "One observation follows every ordinary SparseAdam.step update and evaluates strict teacher margin over all 323 anchor decisions.",
                "The first crossing is the first update for which at least one previously positive teacher-vs-best-alternative margin becomes non-positive.",
            ),
            mechanismDefinition = listOf(
                "The raw gradient is the analytical gradient of the triggering decision's exact softmax cross-entropy at the reconstructed pre-update model.",
                "The actual parameter delta is reconstructed by restoring the retained Adam state and applying exactly the triggering update.",
                "Margin directional effects use the analytical teacher-score-minus-post-competitor gradient at the pre-update model; exact finite update effects are reported separately.",
                "The reset-Adam comparison applies the same triggering decision at the same model with zero moments/counts/clock and the unchanged learning rate plus 1/8 candidate-update scale. It is a one-step local counterfactual only.",
            ),
            seeds = results,
            diagnosticCase = diagnosticCase,
            interpretation = issue0034Interpretation(results, diagnosticCase),
            limitations = listOf(
                "This localizes strict-fit loss under the fixed n=323 continuation; it is not held-out, playing-strength, or general-Magic evidence.",
                "A one-step reset-Adam counterfactual isolates dependence on accumulated optimizer state only locally; it does not evaluate a reset-optimizer training intervention.",
                "First-order margin effects linearize one teacher-versus-post-competitor constraint. Exact before/after margins remain authoritative when nonlinear movement or competitor switching matters.",
                "The result does not establish that SparseAdam is globally unsuitable, that example order or reliability criteria should change, or that any vulnerable decision is bad data.",
                "It does not establish that the same mechanism explains n=389 from-initialization instability, and it does not justify architecture, optimizer, scale, data, or learning-paradigm changes.",
            ),
            narrowestNextQuestion = issue0034NextQuestion(results),
        )
    }

    private fun prepare(historicalManifestPath: Path): Issue0034Prepared {
        val inputs = CorrectedNeuralDiagnosticPreparation(root).prepare(historicalManifestPath)
        val cohorts = inputs.splitAt(ISSUE_0034_ANCHOR_DECISIONS)
        val referencePath = EvidenceStore(root).latest(ISSUE_0034_REFERENCE_ARTIFACT)
        val referenceSha = sha256File(referencePath)
        require(referenceSha == ISSUE_0034_EXPECTED_REFERENCE_SHA256) {
            "Issue-0031 reference artifact changed: $referenceSha"
        }
        val reference = evidenceJson.decodeFromString<Issue0031NeuralCohortContinuationReport>(
            Files.readString(referencePath)
        )
        require(reference.protocol == ISSUE_0031_PROTOCOL)
        require(reference.corpusManifestSha256 == inputs.population.manifestSha256)
        require(reference.splitSha256 == inputs.population.splitSha256)
        require(reference.subsetSelectionOrderSha256 == inputs.subsetSelectionOrderSha256)
        require(reference.anchorIdentity == cohorts.anchorIdentity)
        require(reference.modelConfig == inputs.modelConfig)
        require(reference.trainingConfig == inputs.trainingConfig)
        require(reference.initializationSeeds == CORRECTED_NEURAL_SEEDS)
        val forks = reference.seeds.associate { seedReference ->
            require(seedReference.seed in CORRECTED_NEURAL_SEEDS)
            val path = root.resolve(seedReference.forkCheckpointPath).normalize()
            require(Files.isRegularFile(path)) { "Missing retained issue-0031 fork $path" }
            val sha = sha256File(path)
            require(sha == seedReference.forkCheckpointSha256) {
                "Issue-0031 fork ${seedReference.seed} changed: $sha"
            }
            val checkpoint = evidenceJson.decodeFromString<Issue0031TrainingForkCheckpoint>(
                Files.readString(path)
            )
            require(checkpoint.protocol == ISSUE_0031_PROTOCOL)
            require(checkpoint.seed == seedReference.seed)
            require(checkpoint.completedAnchorEpochs == seedReference.forkAnchorEpoch)
            require(checkpoint.nextAbsoluteEpoch == checkpoint.completedAnchorEpochs + 1)
            require(checkpoint.optimizer.decisionSteps == checkpoint.completedAnchorEpochs * cohorts.anchor.size)
            require(checkpoint.model.config == inputs.modelConfig)
            require(checkpoint.model.trainingSeed == checkpoint.seed)
            checkpoint.seed to LoadedIssue0034Fork(path, sha, checkpoint, seedReference)
        }
        require(forks.keys == CORRECTED_NEURAL_SEEDS.toSet())
        return Issue0034Prepared(
            inputs = inputs,
            anchor = cohorts.anchor,
            anchorIdentity = cohorts.anchorIdentity,
            referencePath = referencePath,
            referenceSha = referenceSha,
            reference = reference,
            forks = forks,
        )
    }

    private fun locateAndAnalyze(
        prepared: Issue0034Prepared,
        loaded: LoadedIssue0034Fork,
        progress: (String) -> Unit,
    ): Issue0034SeedResult {
        val fork = loaded.checkpoint
        val upperBound = requireNotNull(loaded.reference.firstAnchorContinuationErrorAtPostForkSteps) {
            "Issue-0031 seed ${fork.seed} did not retain an anchor-only failure observation"
        }
        val localized = locateCrossing(
            anchor = prepared.anchor,
            fork = fork,
            trainingConfig = prepared.inputs.trainingConfig,
            maximumPostForkSteps = upperBound,
        )
        val exact = reconstructExactCrossing(prepared.anchor, fork, prepared.inputs.trainingConfig, localized)
        val seed = fork.seed
        val beforePath = outputDirectory.resolve("pre-crossing-seed-$seed.json")
        val afterPath = outputDirectory.resolve("post-crossing-seed-$seed.json")
        writeJsonAtomically(
            beforePath,
            Issue0034CrossingStateCheckpoint(
                seed = seed,
                state = "IMMEDIATELY_BEFORE_TRIGGERING_UPDATE",
                postForkDecisionStepsCompleted = localized.postForkStep - 1,
                absoluteEpoch = localized.absoluteEpoch,
                withinEpochUpdatesCompleted = localized.withinEpochPosition - 1,
                model = exact.preModel,
                optimizer = exact.preOptimizer,
            ),
        )
        writeJsonAtomically(
            afterPath,
            Issue0034CrossingStateCheckpoint(
                seed = seed,
                state = "IMMEDIATELY_AFTER_TRIGGERING_UPDATE",
                postForkDecisionStepsCompleted = localized.postForkStep,
                absoluteEpoch = localized.absoluteEpoch,
                withinEpochUpdatesCompleted = localized.withinEpochPosition,
                model = exact.postModel,
                optimizer = exact.postOptimizer,
            ),
        )
        val rawGradient = neuralBcObjectiveGradient(exact.preModel, listOf(localized.triggeringDecision))
        val rawDescent = rawGradient.scaled(-1.0)
        val recipeScaledRawDescent = issue0034RecipeScaledRawDescent(
            rawGradient,
            prepared.inputs.trainingConfig.candidateProjectionUpdateScale,
        )
        val actualDelta = issue0034ModelDelta(exact.preModel, exact.postModel)
        val resetModel = copyNeuralBcModelArtifact(exact.preModel)
        val resetAdam = SparseAdam(resetModel, prepared.inputs.trainingConfig)
        resetAdam.step(localized.triggeringDecision)
        val resetDelta = issue0034ModelDelta(exact.preModel, resetModel)
        val mechanismPath = outputDirectory.resolve("mechanism-vectors-seed-$seed.json")
        writeJsonAtomically(
            mechanismPath,
            Issue0034MechanismVectors(
                seed = seed,
                postForkDecisionStep = localized.postForkStep,
                triggeringDecision = localized.triggeringDecision.neuralDecisionReference(),
                rawCurrentDecisionGradient = rawGradient.issue0034Vector(),
                recipeScaledRawDescentDirection = recipeScaledRawDescent.issue0034Vector(),
                actualSparseAdamParameterDelta = actualDelta.issue0034Vector(),
                resetSparseAdamParameterDelta = resetDelta.issue0034Vector(),
            ),
        )
        val resetRankings = issue0034Rankings(resetModel, prepared.anchor)
        val crossed = localized.crossingIndices.map { index ->
            val decision = prepared.anchor[index]
            val pre = localized.preRankings[index]
            val post = localized.postRankings[index]
            val reset = resetRankings[index]
            val competitor = post.bestAlternativeIndex
            val marginGradient = neuralBcScoreDifferenceGradient(
                exact.preModel,
                decision,
                decision.labelIndex,
                competitor,
            )
            val preAgainstPost = pre.scores[decision.labelIndex] - pre.scores[competitor]
            val postAgainstPost = post.scores[decision.labelIndex] - post.scores[competitor]
            val resetAgainstPost = reset.scores[decision.labelIndex] - reset.scores[competitor]
            Issue0034CrossedMargin(
                vulnerableDecision = decision.neuralDecisionReference(),
                teacherCandidate = candidateIdentity(prepared, decision, decision.labelIndex),
                preUpdateBestCompetitor = candidateIdentity(prepared, decision, pre.bestAlternativeIndex),
                postUpdateBestCompetitor = candidateIdentity(prepared, decision, competitor),
                preUpdateTeacherMargin = pre.margin,
                postUpdateTeacherMargin = post.margin,
                preUpdateMarginAgainstPostCompetitor = preAgainstPost,
                postUpdateMarginAgainstPostCompetitor = postAgainstPost,
                exactActualChangeAgainstPostCompetitor = postAgainstPost - preAgainstPost,
                actualDeltaFirstOrderEffect = gradientDot(marginGradient, actualDelta, "ALL_PARAMETERS"),
                rawGradientDescentFirstOrderEffect = gradientDot(marginGradient, rawDescent, "ALL_PARAMETERS"),
                recipeScaledRawGradientDescentFirstOrderEffect =
                    gradientDot(marginGradient, recipeScaledRawDescent, "ALL_PARAMETERS"),
                resetAdamDeltaFirstOrderEffect = gradientDot(marginGradient, resetDelta, "ALL_PARAMETERS"),
                resetAdamPostMarginAgainstPostCompetitor = resetAgainstPost,
                resetAdamCrossesThisConstraint = resetAgainstPost <= 0.0,
            )
        }
        val geometry = ISSUE_0034_GROUPS.map { group ->
            Issue0034UpdateGeometry(
                parameterGroup = group,
                rawGradientNorm = gradientNorm(rawGradient, group),
                recipeScaledRawDescentNorm = gradientNorm(recipeScaledRawDescent, group),
                actualParameterDeltaNorm = gradientNorm(actualDelta, group),
                resetAdamParameterDeltaNorm = gradientNorm(resetDelta, group),
                actualDeltaCosineWithRawDescent = gradientCosine(actualDelta, rawDescent, group),
                actualDeltaCosineWithRecipeScaledRawDescent =
                    gradientCosine(actualDelta, recipeScaledRawDescent, group),
                resetAdamDeltaCosineWithRecipeScaledRawDescent =
                    gradientCosine(resetDelta, recipeScaledRawDescent, group),
                actualDeltaCosineWithResetAdamDelta = gradientCosine(actualDelta, resetDelta, group),
            )
        }
        val triggerPre = localized.preRankings.single {
            it.decision.gameId == localized.triggeringDecision.gameId &&
                it.decision.decisionIndex == localized.triggeringDecision.decisionIndex
        }
        val localCase = issue0034LocalMechanismCase(crossed)
        progress(
            "Seed $seed: first crossing at ${localized.postForkStep}, " +
                "${crossed.size} vulnerable margin(s), $localCase"
        )
        return Issue0034SeedResult(
            seed = seed,
            sourceForkPath = root.relativize(loaded.path).toString(),
            sourceForkSha256 = loaded.sha256,
            forkAnchorEpoch = fork.completedAnchorEpochs,
            forkOptimizerDecisionSteps = fork.optimizer.decisionSteps,
            forkMinimumTeacherMargin = loaded.reference.forkAnchorMetrics.margins.minimum,
            issue0031FirstObservedFailureUpperBound = upperBound,
            firstCrossingPostForkDecisionStep = localized.postForkStep,
            absoluteEpoch = localized.absoluteEpoch,
            withinEpochPosition = localized.withinEpochPosition,
            triggeringTrainingDecision = localized.triggeringDecision.neuralDecisionReference(),
            triggeringDecisionTeacherCandidate = candidateIdentity(
                prepared,
                localized.triggeringDecision,
                localized.triggeringDecision.labelIndex,
            ),
            triggeringDecisionPreUpdateMargin = triggerPre.margin,
            preUpdateMinimumAnchorMargin = localized.preRankings.minOf(Issue0034RankingSnapshot::margin),
            postUpdateMinimumAnchorMargin = localized.postRankings.minOf(Issue0034RankingSnapshot::margin),
            crossedMargins = crossed,
            updateGeometry = geometry,
            preCrossingCheckpointPath = root.relativize(beforePath).toString(),
            preCrossingCheckpointSha256 = sha256File(beforePath),
            postCrossingCheckpointPath = root.relativize(afterPath).toString(),
            postCrossingCheckpointSha256 = sha256File(afterPath),
            mechanismVectorsPath = root.relativize(mechanismPath).toString(),
            mechanismVectorsSha256 = sha256File(mechanismPath),
            exactReconstructionMatchesLocalizedAfterState = true,
            localMechanismCase = localCase,
        )
    }

    private fun locateCrossing(
        anchor: List<EncodedBcDecision>,
        fork: Issue0031TrainingForkCheckpoint,
        trainingConfig: NeuralBcTrainingConfig,
        maximumPostForkSteps: Long,
    ): Issue0034LocalizedCrossing {
        val model = copyNeuralBcModelArtifact(fork.model)
        val adam = SparseAdam(model, trainingConfig, fork.optimizer)
        var preRankings = issue0034Rankings(model, anchor)
        require(preRankings.all { it.margin > 0.0 })
        var postForkStep = 0L
        var relativeEpoch = 0
        while (postForkStep < maximumPostForkSteps) {
            val absoluteEpoch = fork.completedAnchorEpochs + relativeEpoch + 1
            val order = issue0034EpochOrder(anchor, fork.seed, fork.completedAnchorEpochs + relativeEpoch)
            val epochStartModel = copyNeuralBcModelArtifact(model)
            val epochStartOptimizer = adam.snapshotState()
            order.forEachIndexed { position, decision ->
                if (postForkStep >= maximumPostForkSteps) return@forEachIndexed
                adam.step(decision)
                postForkStep++
                val postRankings = issue0034Rankings(model, anchor)
                val crossingIndices = preRankings.indices.filter { index ->
                    preRankings[index].margin > 0.0 && postRankings[index].margin <= 0.0
                }
                if (crossingIndices.isNotEmpty()) {
                    return Issue0034LocalizedCrossing(
                        postForkStep = postForkStep,
                        absoluteEpoch = absoluteEpoch,
                        withinEpochPosition = position + 1,
                        order = order,
                        triggeringDecision = decision,
                        preRankings = preRankings,
                        postRankings = postRankings,
                        crossingIndices = crossingIndices,
                        epochStartModel = epochStartModel,
                        epochStartOptimizer = epochStartOptimizer,
                        localizedAfterModel = copyNeuralBcModelArtifact(model),
                        localizedAfterOptimizer = adam.snapshotState(),
                    )
                }
                preRankings = postRankings
            }
            relativeEpoch++
        }
        error(
            "Seed ${fork.seed} did not reproduce its issue-0031 anchor instability by " +
                "post-fork step $maximumPostForkSteps"
        )
    }

    private fun reconstructExactCrossing(
        anchor: List<EncodedBcDecision>,
        fork: Issue0031TrainingForkCheckpoint,
        trainingConfig: NeuralBcTrainingConfig,
        localized: Issue0034LocalizedCrossing,
    ): Issue0034ExactCrossingState {
        val preModel = copyNeuralBcModelArtifact(localized.epochStartModel)
        val adam = SparseAdam(preModel, trainingConfig, localized.epochStartOptimizer)
        localized.order.take(localized.withinEpochPosition - 1).forEach(adam::step)
        val preOptimizer = adam.snapshotState()
        require(
            preOptimizer.decisionSteps.toLong() ==
                fork.optimizer.decisionSteps.toLong() + localized.postForkStep - 1
        )
        val reconstructedPre = issue0034Rankings(preModel, anchor)
        require(reconstructedPre.indices.all { index ->
            reconstructedPre[index].scores.contentEquals(localized.preRankings[index].scores)
        })
        val retainedPreModel = copyNeuralBcModelArtifact(preModel)
        adam.step(localized.triggeringDecision)
        val postOptimizer = adam.snapshotState()
        requireIssue0034ModelEquals(preModel, localized.localizedAfterModel)
        require(sameSparseAdamState(postOptimizer, localized.localizedAfterOptimizer))
        return Issue0034ExactCrossingState(
            preModel = retainedPreModel,
            preOptimizer = preOptimizer,
            postModel = copyNeuralBcModelArtifact(preModel),
            postOptimizer = postOptimizer,
        )
    }

    private fun candidateIdentity(
        prepared: Issue0034Prepared,
        decision: EncodedBcDecision,
        candidateIndex: Int,
    ): Issue0034CandidateIdentity {
        val example = prepared.inputs.population.examples.single {
            it.gameId == decision.gameId && it.decisionIndex == decision.decisionIndex
        }
        val candidate = example.input.candidates[candidateIndex]
        return Issue0034CandidateIdentity(
            candidateIndex = candidateIndex,
            signature = candidate.signature,
            operationFamily = candidate.operationFamily.name,
            actionIntent = candidate.actionIntent.kind.name,
            sourceCardName = candidate.actionIntent.sourceCardName,
            displayLabel = candidate.display.label,
            displayTargets = candidate.display.targetNames,
        )
    }
}

private fun issue0034EpochOrder(
    anchor: List<EncodedBcDecision>,
    seed: Long,
    completedAbsoluteEpoch: Int,
): List<EncodedBcDecision> =
    anchor.shuffled(Random(seed xor (completedAbsoluteEpoch + 1).toLong()))

private fun issue0034Rankings(
    artifact: NeuralBcModelArtifact,
    decisions: List<EncodedBcDecision>,
): List<Issue0034RankingSnapshot> {
    val policy = CandidateConditionedNeuralPolicy.fromArtifact(artifact)
    return decisions.map { decision ->
        val scores = policy.scores(decision)
        require(scores.all(Double::isFinite))
        val alternative = scores.indices.filter { it != decision.labelIndex }.maxBy { scores[it] }
        Issue0034RankingSnapshot(
            decision = decision,
            scores = scores,
            teacherIndex = decision.labelIndex,
            bestAlternativeIndex = alternative,
        )
    }
}

private fun issue0034ModelDelta(
    before: NeuralBcModelArtifact,
    after: NeuralBcModelArtifact,
): DenseNeuralBcGradient {
    require(before.config == after.config)
    fun subtract(left: DoubleArray, right: DoubleArray): DoubleArray {
        require(left.size == right.size)
        return DoubleArray(left.size) { right[it] - left[it] }
    }
    return DenseNeuralBcGradient(
        stateWeights = subtract(before.stateWeights, after.stateWeights),
        stateBias = subtract(before.stateBias, after.stateBias),
        candidateWeights = subtract(before.candidateWeights, after.candidateWeights),
        candidateBias = subtract(before.candidateBias, after.candidateBias),
        globalQuery = subtract(before.globalQuery, after.globalQuery),
    )
}

private fun issue0034RecipeScaledRawDescent(
    gradient: DenseNeuralBcGradient,
    candidateScale: Double,
): DenseNeuralBcGradient = DenseNeuralBcGradient(
    stateWeights = DoubleArray(gradient.stateWeights.size) { -gradient.stateWeights[it] },
    stateBias = DoubleArray(gradient.stateBias.size) { -gradient.stateBias[it] },
    candidateWeights = DoubleArray(gradient.candidateWeights.size) {
        -candidateScale * gradient.candidateWeights[it]
    },
    candidateBias = DoubleArray(gradient.candidateBias.size) {
        -candidateScale * gradient.candidateBias[it]
    },
    globalQuery = DoubleArray(gradient.globalQuery.size) { -gradient.globalQuery[it] },
)

private fun DenseNeuralBcGradient.issue0034Vector(): Issue0034ParameterVector =
    Issue0034ParameterVector(
        stateWeights = stateWeights.copyOf(),
        stateBias = stateBias.copyOf(),
        candidateWeights = candidateWeights.copyOf(),
        candidateBias = candidateBias.copyOf(),
        globalQuery = globalQuery.copyOf(),
    )

private fun requireIssue0034ModelEquals(
    first: NeuralBcModelArtifact,
    second: NeuralBcModelArtifact,
) {
    require(first.config == second.config)
    require(first.trainingSeed == second.trainingSeed)
    require(first.bestEpoch == second.bestEpoch)
    require(first.stateWeights.contentEquals(second.stateWeights))
    require(first.stateBias.contentEquals(second.stateBias))
    require(first.candidateWeights.contentEquals(second.candidateWeights))
    require(first.candidateBias.contentEquals(second.candidateBias))
    require(first.globalQuery.contentEquals(second.globalQuery))
}

private fun issue0034LocalMechanismCase(crossed: List<Issue0034CrossedMargin>): String = when {
    crossed.all(Issue0034CrossedMargin::resetAdamCrossesThisConstraint) ->
        "CURRENT_EXAMPLE_CROSSES_UNDER_BOTH_RETAINED_AND_RESET_ADAM_STATE"
    crossed.none(Issue0034CrossedMargin::resetAdamCrossesThisConstraint) ->
        "CROSSING_DEPENDS_ON_RETAINED_ADAM_STATE_IN_LOCAL_RESET_COMPARISON"
    else -> "MIXED_RESET_ADAM_SUFFICIENCY_ACROSS_SIMULTANEOUS_CROSSINGS"
}

private fun issue0034DiagnosticCase(results: List<Issue0034SeedResult>): String {
    val triggers = results.map(Issue0034SeedResult::triggeringTrainingDecision).toSet()
    val vulnerableSets = results.map { result -> result.crossedMargins.map { it.vulnerableDecision }.toSet() }
    val commonVulnerable = vulnerableSets.reduce { first, second -> first intersect second }
    return when {
        triggers.size == 1 && commonVulnerable.isNotEmpty() -> "CONCENTRATED_RECURRING_LOCAL_CROSSING"
        triggers.size == 1 || commonVulnerable.isNotEmpty() -> "PARTIALLY_RECURRING_PATH_DEPENDENT_CROSSING"
        else -> "DIFFUSE_SEED_SPECIFIC_PATH_DEPENDENT_CROSSINGS"
    }
}

private fun issue0034Interpretation(
    results: List<Issue0034SeedResult>,
    diagnosticCase: String,
): List<String> {
    val resetCounts = results.sumOf { result ->
        result.crossedMargins.count(Issue0034CrossedMargin::resetAdamCrossesThisConstraint)
    }
    val totalCrossings = results.sumOf { it.crossedMargins.size }
    return listOf(
        "All three retained forks reproduce a positive-to-non-positive strict-margin crossing no later than the coarse issue-0031 observation, so the earlier instability was not a restoration mismatch.",
        when (diagnosticCase) {
            "CONCENTRATED_RECURRING_LOCAL_CROSSING" ->
                "The same triggering update and at least one common vulnerable decision recur across seeds, supporting a concentrated local antagonistic witness."
            "PARTIALLY_RECURRING_PATH_DEPENDENT_CROSSING" ->
                "Only part of the trigger/vulnerability identity recurs across seeds, supporting a repeated local ingredient embedded in path-dependent states."
            else ->
                "Triggering and vulnerable decisions differ across seeds, supporting diffuse path-dependent instability rather than one universal bad example."
        },
        "$resetCounts of $totalCrossings vulnerable constraints also cross under the one-step reset-Adam comparison; retained-state dependence is interpreted per seed rather than generalized to optimizer suitability.",
        "Exact finite margin changes and analytical first-order effects are both retained. Any disagreement is evidence of finite nonlinear movement or competitor switching, not permission to replace the observed crossing with the linear approximation.",
    )
}

private fun issue0034NextQuestion(results: List<Issue0034SeedResult>): String {
    val allReset = results.flatMap(Issue0034SeedResult::crossedMargins)
        .all(Issue0034CrossedMargin::resetAdamCrossesThisConstraint)
    return if (allReset) {
        "Do the recurring trigger/vulnerability feature interactions explain why these already-small positive margins remain exposed under continued ordinary loss minimization?"
    } else {
        "Which retained Adam moment/count component is minimally necessary for the seed-specific damaging update to cross its vulnerable margin?"
    }
}

@Serializable
private data class Issue0034Progress(
    val schemaVersion: Int = 1,
    val updatedAt: String,
    val completed: Int,
    val total: Int = 3,
    val unit: String = "seeds",
    val phase: String,
    val detail: String,
)

private class Issue0034DurableProgress {
    private val path = System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of)

    fun publish(completed: Int, phase: String, detail: String) {
        path?.let { progressPath ->
            writeJsonAtomically(
                progressPath,
                Issue0034Progress(
                    updatedAt = Instant.now().toString(),
                    completed = completed,
                    phase = phase,
                    detail = detail,
                ),
            )
        }
    }
}

internal fun renderIssue0034NeuralAnchorCrossing(
    report: Issue0034NeuralAnchorCrossingReport,
): String = buildString {
    appendLine("# Issue 0034: first individual n=323 anchor-margin crossing")
    appendLine()
    appendLine("Case: `${report.diagnosticCase}`")
    appendLine()
    report.interpretation.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Exact crossings")
    appendLine()
    appendLine("| Seed | First step | Absolute epoch / position | Trigger | Vulnerable margins | Pre min | Post min | Local mechanism |")
    appendLine("| ---: | ---: | ---: | --- | --- | ---: | ---: | --- |")
    report.seeds.forEach { seed ->
        val vulnerable = seed.crossedMargins.joinToString("<br>") { crossing ->
            "${crossing.vulnerableDecision.gameId}:${crossing.vulnerableDecision.decisionIndex} " +
                "${issue0034Number(crossing.preUpdateTeacherMargin)} → " +
                issue0034Number(crossing.postUpdateTeacherMargin)
        }
        appendLine(
            "| ${seed.seed} | ${seed.firstCrossingPostForkDecisionStep} | " +
                "${seed.absoluteEpoch} / ${seed.withinEpochPosition} | " +
                "${seed.triggeringTrainingDecision.gameId}:${seed.triggeringTrainingDecision.decisionIndex} " +
                "(${seed.triggeringTrainingDecision.decisionFamily}) | $vulnerable | " +
                "${issue0034Number(seed.preUpdateMinimumAnchorMargin)} | " +
                "${issue0034Number(seed.postUpdateMinimumAnchorMargin)} | `${seed.localMechanismCase}` |"
        )
    }
    appendLine()
    appendLine("## One-step mechanism")
    appendLine()
    report.seeds.forEach { seed ->
        appendLine("### Seed ${seed.seed}")
        appendLine()
        appendLine("Trigger: `${seed.triggeringTrainingDecision.gameId}:${seed.triggeringTrainingDecision.decisionIndex}` — ${seed.triggeringDecisionTeacherCandidate.displayLabel}")
        appendLine()
        appendLine("| Group | Raw gradient norm | Actual delta norm | Reset-Adam delta norm | Actual vs scaled raw cosine | Actual vs reset cosine |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
        seed.updateGeometry.forEach { group ->
            appendLine(
                "| ${group.parameterGroup} | ${issue0034Number(group.rawGradientNorm)} | " +
                    "${issue0034Number(group.actualParameterDeltaNorm)} | " +
                    "${issue0034Number(group.resetAdamParameterDeltaNorm)} | " +
                    "${issue0034Nullable(group.actualDeltaCosineWithRecipeScaledRawDescent)} | " +
                    "${issue0034Nullable(group.actualDeltaCosineWithResetAdamDelta)} |"
            )
        }
        appendLine()
        seed.crossedMargins.forEach { crossing ->
            appendLine(
                "- `${crossing.vulnerableDecision.gameId}:${crossing.vulnerableDecision.decisionIndex}`: " +
                    "teacher `${crossing.teacherCandidate.displayLabel}` versus post competitor " +
                    "`${crossing.postUpdateBestCompetitor.displayLabel}`; actual constraint change " +
                    "${issue0034Number(crossing.exactActualChangeAgainstPostCompetitor)}, " +
                    "first-order actual ${issue0034Number(crossing.actualDeltaFirstOrderEffect)}, " +
                    "scaled raw descent ${issue0034Number(crossing.recipeScaledRawGradientDescentFirstOrderEffect)}, " +
                    "reset-Adam post margin ${issue0034Number(crossing.resetAdamPostMarginAgainstPostCompetitor)}."
            )
        }
        appendLine()
    }
    appendLine("## Evidence identity")
    appendLine()
    report.seeds.forEach { seed ->
        appendLine("- Seed ${seed.seed}: pre `${seed.preCrossingCheckpointSha256}`, post `${seed.postCrossingCheckpointSha256}`, mechanism `${seed.mechanismVectorsSha256}`.")
    }
    appendLine()
    appendLine("## Limitations")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Narrowest next question")
    appendLine()
    appendLine(report.narrowestNextQuestion)
}

internal fun renderIssue0034Preflight(report: Issue0034PreflightReport): String = buildString {
    appendLine("Issue-0034 neural anchor-crossing preflight passed")
    appendLine("  corpus: ${report.corpusManifestSha256}")
    appendLine("  split: ${report.splitSha256}")
    appendLine("  order: ${report.orderSha256}")
    appendLine("  anchor: ${report.anchorIdentity}")
    appendLine("  issue-0031 reference: ${report.issue0031ReferenceSha256}")
    report.seeds.forEach { seed ->
        appendLine(
            "  seed ${seed.seed}: fork epoch ${seed.forkEpoch}, optimizer step " +
                "${seed.forkOptimizerDecisionSteps}, 323/323, min margin " +
                "${issue0034Number(seed.forkMinimumTeacherMargin)}, next " +
                "${seed.deterministicNextDecision.gameId}:${seed.deterministicNextDecision.decisionIndex}, " +
                "exact restored update=${seed.restoredNextUpdateMatchesExactly}"
        )
    }
    append(
        "  retained research updates: ${report.researchTrainingUpdatesRetainedByPreflight}; " +
            "research artifacts: ${report.researchArtifactsEmittedByPreflight}"
    )
}

private fun issue0034Number(value: Double): String = "%.12g".format(java.util.Locale.ROOT, value)
private fun issue0034Nullable(value: Double?): String = value?.let(::issue0034Number) ?: "n/a"
