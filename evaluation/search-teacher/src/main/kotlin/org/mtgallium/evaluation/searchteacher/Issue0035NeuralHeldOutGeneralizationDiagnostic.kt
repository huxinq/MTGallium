package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val ISSUE_0035_PROTOCOL = "issue-0035-fixed-v3-held-out-generalization-v1"
private const val ISSUE_0035_REFERENCE_ARTIFACT =
    "neural-cohort-continuation-diagnostic/artifact.json"
private const val ISSUE_0035_EXPECTED_REFERENCE_SHA256 =
    "6e7d31c9da235e5487c8c12dff4f5e3e66d70ac9b562c539674361a00e5b70ea"
private const val ISSUE_0035_ORIGINAL_ARTIFACT = "neural-behavioral-cloning/artifact.json"
private const val ISSUE_0035_EXPECTED_ORIGINAL_SHA256 =
    "89f325282ed67d59309dc753d205336ef515c2b732a74e8a7854259c7a944b7c"
private const val ISSUE_0035_TRAIN_DECISIONS = 389
private const val ISSUE_0035_VALIDATION_DECISIONS = 36
private const val ISSUE_0035_TEST_DECISIONS = 69

@Serializable
internal data class Issue0035ExpectedAccuracy(
    val decisions: Int,
    val expectedCorrect: Double,
    val accuracy: Double,
)

@Serializable
internal data class Issue0035SeedCohortMetrics(
    val seed: Long,
    val decisions: Int,
    val exactTeacherSelections: Int,
    val exactTeacherActionAccuracy: Double,
    val strictPositiveMargins: Int,
    val tiedBestScores: Int,
    val meanCrossEntropy: Double,
    val meanTeacherProbability: Double,
    val margins: NeuralMarginSummary,
)

@Serializable
internal data class Issue0035CohortMetrics(
    val cohort: String,
    val decisions: Int,
    val uniform: Issue0035ExpectedAccuracy,
    val stateIgnorantEmpirical: Issue0035ExpectedAccuracy,
    val neuralBySeed: List<Issue0035SeedCohortMetrics>,
    val neuralMeanAccuracy: Double,
    val neuralMinimumAccuracy: Double,
    val neuralMaximumAccuracy: Double,
    val neuralPooledCorrect: Int,
    val neuralPooledDecisions: Int,
    val neuralPooledAccuracy: Double,
)

@Serializable
internal data class Issue0035RetainedModel(
    val seed: Long,
    val expandedConfirmationEpoch: Int,
    val artifactBestEpoch: Int,
    val modelPath: String,
    val modelSha256: String,
    val trainingStrictRankingCorrect: Int,
    val trainingDecisions: Int,
    val trainingMeanCrossEntropy: Double,
    val trainingMinimumMargin: Double,
    val heldOutSmokeScores: Int,
)

@Serializable
internal data class Issue0035HeldOutFailure(
    val seed: Long,
    val gameId: String,
    val decisionIndex: Int,
    val decisionFamily: String,
    val candidateCount: Int,
    val teacherCandidateIndex: Int,
    val teacherSignature: String,
    val teacherDisplay: String,
    val predictedCandidateIndex: Int,
    val predictedSignature: String,
    val predictedDisplay: String,
    val teacherMargin: Double,
    val teacherProbability: Double,
    val crossEntropy: Double,
)

@Serializable
internal data class Issue0035ErrorOverlap(
    val decisions: Int,
    val unanimouslyCorrect: Int,
    val unanimouslyIncorrect: Int,
    val mixedCorrectness: Int,
    val unanimousIncorrectByDecisionFamily: Map<String, Int>,
    val unanimousIncorrectByCandidateCount: Map<Int, Int>,
    val unanimousIncorrectDecisions: List<NeuralDecisionReference>,
    val unanimousIncorrectWithSamePredictedCandidate: Int,
)

@Serializable
internal data class Issue0035PreflightReport(
    val protocol: String = ISSUE_0035_PROTOCOL,
    val corpusManifestSha256: String,
    val splitSha256: String,
    val splitIdentity: String,
    val trainGames: Int,
    val validationGames: Int,
    val testGames: List<String>,
    val nontrivialDecisionsBySplit: Map<String, Int>,
    val featureSchema: String,
    val stateInputScale: Double,
    val referenceArtifactSha256: String,
    val originalArtifactSha256: String,
    val retainedModels: List<Issue0035RetainedModel>,
    val heldOutSmokeDecision: NeuralDecisionReference,
    val noHeldOutTrainingOrStoppingInfluence: Boolean,
    val researchTrainingUpdates: Int,
    val researchArtifactsEmitted: Int,
)

@Serializable
internal data class Issue0035NeuralHeldOutGeneralizationReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0035_PROTOCOL,
    val generatedAtUtc: String,
    val implementationSourceProvenance: PolicySourceProvenance,
    val historicalSourceProvenance: PolicySourceProvenance,
    val historicalCandidateSchemaVersion: Int,
    val featureSchema: String,
    val stateInputScale: Double,
    val corpusManifestPath: String,
    val corpusManifestSha256: String,
    val corpusDatasetIdentity: String,
    val splitPath: String,
    val splitSha256: String,
    val splitIdentity: String,
    val splitUnit: String,
    val trainGames: List<String>,
    val validationGames: List<String>,
    val testGames: List<String>,
    val admittedDecisions: Int,
    val nontrivialDecisionsBySplit: Map<String, Int>,
    val primaryHeldOutDefinition: List<String>,
    val trainingIsolation: List<String>,
    val referenceArtifactPath: String,
    val referenceArtifactSha256: String,
    val originalArtifactPath: String,
    val originalArtifactSha256: String,
    val modelConfig: NeuralBcModelConfig,
    val trainingConfig: NeuralBcTrainingConfig,
    val retainedModels: List<Issue0035RetainedModel>,
    val primaryTest: Issue0035CohortMetrics,
    val byDecisionFamily: List<Issue0035CohortMetrics>,
    val byCandidateCountRange: List<Issue0035CohortMetrics>,
    val byExactCandidateCount: List<Issue0035CohortMetrics>,
    val attacksByExactCandidateCount: List<Issue0035CohortMetrics>,
    val errorOverlap: Issue0035ErrorOverlap,
    val representativeFailures: List<Issue0035HeldOutFailure>,
    val historicalOriginalPrimary: BcCohortMetrics,
    val historicalOriginalByDecisionFamily: List<BcCohortMetrics>,
    val historicalOriginalByCandidateCountRange: List<BcCohortMetrics>,
    val baselineRecomputationMatchesIssue0022: Boolean,
    val metricDefinitions: List<String>,
    val diagnosticCase: String,
    val interpretation: List<String>,
    val limitations: List<String>,
    val narrowestNextQuestion: String,
)

private data class Issue0035Prepared(
    val implementation: PolicySourceProvenance,
    val population: Issue0022HistoricalPopulation,
    val trainAll: List<EncodedBcDecision>,
    val train: List<EncodedBcDecision>,
    val test: List<EncodedBcDecision>,
    val testExamples: Map<Pair<String, Int>, Issue0022HistoricalExample>,
    val empirical: EmpiricalIntentBaseline,
    val models: Map<Long, CandidateConditionedNeuralPolicy>,
    val retainedModels: List<Issue0035RetainedModel>,
    val referencePath: Path,
    val referenceSha256: String,
    val reference: Issue0031NeuralCohortContinuationReport,
    val originalPath: Path,
    val originalSha256: String,
    val original: NeuralBcExperimentReport,
    val smoke: NeuralDecisionReference,
)

internal class Issue0035NeuralHeldOutGeneralizationDiagnostic(
    private val root: Path,
    private val outputDirectory: Path,
) {
    fun preflight(historicalManifestPath: Path): Issue0035PreflightReport {
        val prepared = prepare(historicalManifestPath)
        return Issue0035PreflightReport(
            corpusManifestSha256 = prepared.population.manifestSha256,
            splitSha256 = prepared.population.splitSha256,
            splitIdentity = prepared.population.split.splitIdentity,
            trainGames = prepared.population.split.trainGames.size,
            validationGames = prepared.population.split.validationGames.size,
            testGames = prepared.population.split.testGames,
            nontrivialDecisionsBySplit = nontrivialCounts(prepared.population),
            featureSchema = NEURAL_BC_FEATURE_SCHEMA,
            stateInputScale = CORRECTED_NEURAL_STATE_INPUT_SCALE,
            referenceArtifactSha256 = prepared.referenceSha256,
            originalArtifactSha256 = prepared.originalSha256,
            retainedModels = prepared.retainedModels,
            heldOutSmokeDecision = prepared.smoke,
            noHeldOutTrainingOrStoppingInfluence = true,
            researchTrainingUpdates = 0,
            researchArtifactsEmitted = 0,
        )
    }

    fun run(historicalManifestPath: Path): Issue0035NeuralHeldOutGeneralizationReport {
        val prepared = prepare(historicalManifestPath)
        Files.createDirectories(outputDirectory)
        val primary = issue0035Cohort("test-nontrivial-primary", prepared.test, prepared.empirical, prepared.models)
        val byFamily = prepared.test.groupBy(EncodedBcDecision::decisionFamily).toSortedMap().map { (name, rows) ->
            issue0035Cohort(name, rows, prepared.empirical, prepared.models)
        }
        val byRange = listOf("2", "3-4", "5+").map { range ->
            issue0035Cohort(
                range,
                prepared.test.filter { issue0035CandidateRange(it.candidateCount) == range },
                prepared.empirical,
                prepared.models,
            )
        }
        val byExact = prepared.test.groupBy(EncodedBcDecision::candidateCount).toSortedMap().map { (count, rows) ->
            issue0035Cohort(count.toString(), rows, prepared.empirical, prepared.models)
        }
        val attacksByExact = prepared.test.filter { it.decisionFamily == "DECLARE_ATTACKERS" }
            .groupBy(EncodedBcDecision::candidateCount).toSortedMap().map { (count, rows) ->
                issue0035Cohort(count.toString(), rows, prepared.empirical, prepared.models)
            }
        val errorOverlap = issue0035ErrorOverlap(prepared.test, prepared.models)
        val failures = prepared.models.flatMap { (seed, model) ->
            issue0035Failures(seed, model, prepared.test, prepared.testExamples)
        }
        val baselinesMatch = baselinesMatch(primary, prepared.original.primaryTest)
        require(baselinesMatch) { "Recomputed historical baselines do not reproduce issue 0022" }
        val diagnosticCase = classifyIssue0035(primary, prepared.original.primaryTest, byFamily, prepared.original)
        return Issue0035NeuralHeldOutGeneralizationReport(
            generatedAtUtc = Instant.now().toString(),
            implementationSourceProvenance = prepared.implementation,
            historicalSourceProvenance = prepared.population.manifest.sourceProvenance,
            historicalCandidateSchemaVersion = CANDIDATE_SCHEMA_V3,
            featureSchema = NEURAL_BC_FEATURE_SCHEMA,
            stateInputScale = CORRECTED_NEURAL_STATE_INPUT_SCALE,
            corpusManifestPath = root.relativize(prepared.population.manifestPath).toString(),
            corpusManifestSha256 = prepared.population.manifestSha256,
            corpusDatasetIdentity = prepared.population.manifest.datasetIdentity,
            splitPath = root.relativize(prepared.population.splitPath).toString(),
            splitSha256 = prepared.population.splitSha256,
            splitIdentity = prepared.population.split.splitIdentity,
            splitUnit = "complete games",
            trainGames = prepared.population.split.trainGames,
            validationGames = prepared.population.split.validationGames,
            testGames = prepared.population.split.testGames,
            admittedDecisions = prepared.population.examples.size,
            nontrivialDecisionsBySplit = nontrivialCounts(prepared.population),
            primaryHeldOutDefinition = listOf(
                "The primary population is every issue-0022 test-game decision with at least two current semantic candidates: exactly 69 decisions from two complete held-out games.",
                "The label is exact equality with the persisted accepted candidate-V3 Search Teacher action; neural correctness uses the current scorer's deterministic highest-score candidate index.",
                "Uniform expected correctness is the mean of 1/current-candidate-count. The state-ignorant empirical baseline uses training-game teacher intent frequencies within decision family and splits credit uniformly across tied current candidates.",
            ),
            trainingIsolation = listOf(
                "Issue 0031 reconstructed features only for nontrivial decisions whose game id is in the 15-game training split; its fixed ordered identity contains 389 decisions.",
                "The expanded stopping condition inspected only strict ranking over those 389 training decisions and stopped after 20 consecutive perfect complete epochs.",
                "Validation and test labels, outcomes, losses, and accuracies were not inputs to training, checkpoint choice, or stopping. This evaluation loads the retained final expanded model and performs zero optimizer updates.",
            ),
            referenceArtifactPath = root.relativize(prepared.referencePath).toString(),
            referenceArtifactSha256 = prepared.referenceSha256,
            originalArtifactPath = root.relativize(prepared.originalPath).toString(),
            originalArtifactSha256 = prepared.originalSha256,
            modelConfig = prepared.reference.modelConfig,
            trainingConfig = prepared.reference.trainingConfig,
            retainedModels = prepared.retainedModels,
            primaryTest = primary,
            byDecisionFamily = byFamily,
            byCandidateCountRange = byRange,
            byExactCandidateCount = byExact,
            attacksByExactCandidateCount = attacksByExact,
            errorOverlap = errorOverlap,
            representativeFailures = failures,
            historicalOriginalPrimary = prepared.original.primaryTest,
            historicalOriginalByDecisionFamily = prepared.original.byDecisionFamily,
            historicalOriginalByCandidateCountRange = prepared.original.byCandidateCountRange,
            baselineRecomputationMatchesIssue0022 = baselinesMatch,
            metricDefinitions = listOf(
                "Exact teacher-action accuracy counts the persisted teacher candidate only when it is the deterministic max-score selection; ties follow the existing first-index scorer behavior.",
                "Teacher margin is teacher score minus the highest alternative score; strict positive margin is reported separately from deterministic exact selection.",
                "Cross-entropy is the existing per-decision softmax objective and teacher probability is exp(-cross-entropy).",
                "Aggregate neural accuracy is both the arithmetic mean of the three equal-sized seed accuracies and pooled exact hits over 207 seed-decisions; seed values and range remain explicit.",
                "The descriptive case calls a change of at least five percentage points material and at most three points near the original; these are interpretation labels, not significance tests or continuation gates.",
            ),
            diagnosticCase = diagnosticCase,
            interpretation = interpretIssue0035(
                primary,
                prepared.original.primaryTest,
                byFamily,
                prepared.original,
                errorOverlap,
            ),
            limitations = listOf(
                "This is imitation of one realized Search Teacher candidate per decision, not strategic correctness, value accuracy, game outcome, or playing-strength evidence.",
                "The held-out population is only two complete games and 69 nontrivial decisions in a fixed-deck Mono-Red mirror; game-level separation is real, breadth is small.",
                "Attack declarations and candidate count remain confounded: the 16 attacks have candidate counts 2, 4, and 8 from subset enumeration, so a family difference does not isolate attack semantics.",
                "The comparison changes the feature repair, state/input scaling, candidate update scaling, and staged training trajectory together. It does not causally isolate any one correction.",
                "The three retained seeds all fit training perfectly but are not independent corpus samples. Their spread describes initialization/trajectory variation on this fixed evidence only.",
                "No new teacher data, retraining arm, hyperparameter choice, architecture search, optimizer intervention, or downstream learning paradigm was evaluated.",
            ),
            narrowestNextQuestion = narrowestIssue0035Question(diagnosticCase),
        )
    }

    private fun prepare(historicalManifestPath: Path): Issue0035Prepared {
        val corrected = CorrectedNeuralDiagnosticPreparation(root).prepare(historicalManifestPath)
        val population = corrected.population
        val encoder = NeuralBehavioralCloningFeatureEncoder()
        val encoded = population.examples.map { it.encode(encoder).withStateInputScale(CORRECTED_NEURAL_STATE_INPUT_SCALE) }
        val trainAll = encoded.filter { it.gameId in population.split.trainGames }
        val validation = encoded.filter { it.gameId in population.split.validationGames && it.candidateCount >= 2 }
        val test = encoded.filter { it.gameId in population.split.testGames && it.candidateCount >= 2 }
        require(corrected.orderedDecisions.size == ISSUE_0035_TRAIN_DECISIONS)
        require(validation.size == ISSUE_0035_VALIDATION_DECISIONS)
        require(test.size == ISSUE_0035_TEST_DECISIONS)
        require((population.split.trainGames + population.split.validationGames + population.split.testGames).distinct().size == 19)

        val store = EvidenceStore(root)
        val referencePath = store.latest(ISSUE_0035_REFERENCE_ARTIFACT)
        val referenceSha = sha256File(referencePath)
        require(referenceSha == ISSUE_0035_EXPECTED_REFERENCE_SHA256)
        val reference = evidenceJson.decodeFromString<Issue0031NeuralCohortContinuationReport>(
            Files.readString(referencePath)
        )
        require(reference.protocol == ISSUE_0031_PROTOCOL)
        require(reference.corpusManifestSha256 == population.manifestSha256)
        require(reference.splitSha256 == population.splitSha256)
        require(reference.splitIdentity == population.split.splitIdentity)
        require(reference.featureSchema == NEURAL_BC_FEATURE_SCHEMA)
        require(reference.trainingDecisions == ISSUE_0035_TRAIN_DECISIONS)
        require(reference.subsetSelectionOrderSha256 == corrected.subsetSelectionOrderSha256)
        require(reference.initializationSeeds == CORRECTED_NEURAL_SEEDS)
        require(reference.modelConfig == corrected.modelConfig)
        require(reference.trainingConfig.candidateProjectionUpdateScale == CORRECTED_NEURAL_CANDIDATE_UPDATE_SCALE)

        val models = linkedMapOf<Long, CandidateConditionedNeuralPolicy>()
        val retained = reference.seeds.map { result ->
            require(result.expandedConfirmationEpoch != null)
            require(result.expandedEpochsCompleted == result.expandedConfirmationEpoch)
            require(result.finalExpandedCombined.strictRankingCorrect == ISSUE_0035_TRAIN_DECISIONS)
            val modelPath = root.resolve(result.finalExpandedModelPath).normalize()
            require(modelPath.startsWith(root) && Files.isRegularFile(modelPath) && !Files.isSymbolicLink(modelPath))
            require(sha256File(modelPath) == result.finalExpandedModelSha256)
            val model = CandidateConditionedNeuralPolicy.load(modelPath)
            require(model.artifact.trainingSeed == result.seed)
            require(model.artifact.config == reference.modelConfig)
            require(model.artifact.bestEpoch == result.forkAnchorEpoch + result.expandedEpochsCompleted)
            val fit = cohortMetrics(model, corrected.orderedDecisions)
            require(fit.strictRankingCorrect == ISSUE_0035_TRAIN_DECISIONS)
            require(abs(fit.meanCrossEntropy - result.finalExpandedCombined.meanCrossEntropy) <= 1e-12)
            models[result.seed] = model
            Issue0035RetainedModel(
                seed = result.seed,
                expandedConfirmationEpoch = result.expandedConfirmationEpoch,
                artifactBestEpoch = model.artifact.bestEpoch,
                modelPath = result.finalExpandedModelPath,
                modelSha256 = result.finalExpandedModelSha256,
                trainingStrictRankingCorrect = fit.strictRankingCorrect,
                trainingDecisions = fit.decisions,
                trainingMeanCrossEntropy = fit.meanCrossEntropy,
                trainingMinimumMargin = fit.margins.minimum,
                heldOutSmokeScores = model.scores(test.first()).also { scores ->
                    require(scores.size == test.first().candidateCount && scores.all(Double::isFinite))
                }.size,
            )
        }
        require(models.keys.toList() == CORRECTED_NEURAL_SEEDS)

        val originalPath = store.latest(ISSUE_0035_ORIGINAL_ARTIFACT)
        val originalSha = sha256File(originalPath)
        require(originalSha == ISSUE_0035_EXPECTED_ORIGINAL_SHA256)
        val original = evidenceJson.decodeFromString<NeuralBcExperimentReport>(Files.readString(originalPath))
        require(original.corpusDatasetIdentity == population.manifest.datasetIdentity)
        require(original.split.splitIdentity == population.split.splitIdentity)
        require(original.primaryTest.decisions == ISSUE_0035_TEST_DECISIONS)
        val smoke = test.first().neuralDecisionReference()
        return Issue0035Prepared(
            implementation = corrected.implementationSourceProvenance,
            population = population,
            trainAll = trainAll,
            train = corrected.orderedDecisions,
            test = test,
            testExamples = population.examples.filter { it.gameId in population.split.testGames }
                .associateBy { it.gameId to it.decisionIndex },
            empirical = EmpiricalIntentBaseline(trainAll),
            models = models,
            retainedModels = retained,
            referencePath = referencePath,
            referenceSha256 = referenceSha,
            reference = reference,
            originalPath = originalPath,
            originalSha256 = originalSha,
            original = original,
            smoke = smoke,
        )
    }
}

internal fun issue0035SeedMetrics(
    seed: Long,
    policy: NeuralBcScoringPolicy,
    decisions: List<EncodedBcDecision>,
): Issue0035SeedCohortMetrics {
    require(decisions.isNotEmpty())
    val fits = decisions.map { neuralMemorizationDecisionFit(policy, it) }
    val margins = fits.map(NeuralMemorizationDecisionFit::teacherMargin).sorted()
    fun quantile(q: Double): Double = margins[((margins.size - 1) * q).toInt()]
    return Issue0035SeedCohortMetrics(
        seed = seed,
        decisions = decisions.size,
        exactTeacherSelections = fits.count { it.teacherCandidateIndex == it.predictedCandidateIndex },
        exactTeacherActionAccuracy = fits.count { it.teacherCandidateIndex == it.predictedCandidateIndex }.toDouble() / decisions.size,
        strictPositiveMargins = fits.count(NeuralMemorizationDecisionFit::strictRankingCorrect),
        tiedBestScores = fits.count { !it.strictRankingCorrect && it.teacherCandidateIndex == it.predictedCandidateIndex },
        meanCrossEntropy = fits.map(NeuralMemorizationDecisionFit::meanCrossEntropyContribution).average(),
        meanTeacherProbability = fits.map { exp(-it.meanCrossEntropyContribution) }.average(),
        margins = NeuralMarginSummary(
            decisions = decisions.size,
            minimum = margins.first(),
            p10 = quantile(0.10),
            median = quantile(0.50),
            p90 = quantile(0.90),
            maximum = margins.last(),
            mean = margins.average(),
        ),
    )
}

private fun issue0035Cohort(
    name: String,
    decisions: List<EncodedBcDecision>,
    empirical: EmpiricalIntentBaseline,
    models: Map<Long, CandidateConditionedNeuralPolicy>,
): Issue0035CohortMetrics {
    require(decisions.isNotEmpty())
    val uniformCorrect = decisions.sumOf { 1.0 / it.candidateCount }
    val empiricalCorrect = decisions.sumOf(empirical::expectedCorrect)
    val neural = models.map { (seed, model) -> issue0035SeedMetrics(seed, model, decisions) }
    val pooledCorrect = neural.sumOf(Issue0035SeedCohortMetrics::exactTeacherSelections)
    val pooledDecisions = neural.sumOf(Issue0035SeedCohortMetrics::decisions)
    return Issue0035CohortMetrics(
        cohort = name,
        decisions = decisions.size,
        uniform = Issue0035ExpectedAccuracy(decisions.size, uniformCorrect, uniformCorrect / decisions.size),
        stateIgnorantEmpirical = Issue0035ExpectedAccuracy(decisions.size, empiricalCorrect, empiricalCorrect / decisions.size),
        neuralBySeed = neural,
        neuralMeanAccuracy = neural.map(Issue0035SeedCohortMetrics::exactTeacherActionAccuracy).average(),
        neuralMinimumAccuracy = neural.minOf(Issue0035SeedCohortMetrics::exactTeacherActionAccuracy),
        neuralMaximumAccuracy = neural.maxOf(Issue0035SeedCohortMetrics::exactTeacherActionAccuracy),
        neuralPooledCorrect = pooledCorrect,
        neuralPooledDecisions = pooledDecisions,
        neuralPooledAccuracy = pooledCorrect.toDouble() / pooledDecisions,
    )
}

private fun issue0035Failures(
    seed: Long,
    policy: CandidateConditionedNeuralPolicy,
    decisions: List<EncodedBcDecision>,
    examples: Map<Pair<String, Int>, Issue0022HistoricalExample>,
): List<Issue0035HeldOutFailure> = decisions.mapNotNull { decision ->
    val fit = neuralMemorizationDecisionFit(policy, decision)
    if (fit.teacherCandidateIndex == fit.predictedCandidateIndex) return@mapNotNull null
    val example = requireNotNull(examples[decision.gameId to decision.decisionIndex])
    val teacher = example.input.candidates[fit.teacherCandidateIndex]
    val predicted = example.input.candidates[fit.predictedCandidateIndex]
    Issue0035HeldOutFailure(
        seed = seed,
        gameId = decision.gameId,
        decisionIndex = decision.decisionIndex,
        decisionFamily = decision.decisionFamily,
        candidateCount = decision.candidateCount,
        teacherCandidateIndex = fit.teacherCandidateIndex,
        teacherSignature = teacher.signature,
        teacherDisplay = teacher.display.label,
        predictedCandidateIndex = fit.predictedCandidateIndex,
        predictedSignature = predicted.signature,
        predictedDisplay = predicted.display.label,
        teacherMargin = fit.teacherMargin,
        teacherProbability = exp(-fit.meanCrossEntropyContribution),
        crossEntropy = fit.meanCrossEntropyContribution,
    )
}.sortedBy(Issue0035HeldOutFailure::teacherMargin).take(5)

private fun issue0035ErrorOverlap(
    decisions: List<EncodedBcDecision>,
    models: Map<Long, CandidateConditionedNeuralPolicy>,
): Issue0035ErrorOverlap {
    val rows = decisions.map { decision ->
        val predictions = models.values.map { it.selectIndex(decision) }
        Triple(decision, predictions, predictions.count { it == decision.labelIndex })
    }
    val unanimousIncorrect = rows.filter { it.third == 0 }
    return Issue0035ErrorOverlap(
        decisions = decisions.size,
        unanimouslyCorrect = rows.count { it.third == models.size },
        unanimouslyIncorrect = unanimousIncorrect.size,
        mixedCorrectness = rows.count { it.third in 1 until models.size },
        unanimousIncorrectByDecisionFamily = unanimousIncorrect.groupingBy { it.first.decisionFamily }
            .eachCount().toSortedMap(),
        unanimousIncorrectByCandidateCount = unanimousIncorrect.groupingBy { it.first.candidateCount }
            .eachCount().toSortedMap(),
        unanimousIncorrectDecisions = unanimousIncorrect.map { it.first.neuralDecisionReference() }
            .sortedWith(compareBy(NeuralDecisionReference::gameId).thenBy(NeuralDecisionReference::decisionIndex)),
        unanimousIncorrectWithSamePredictedCandidate = unanimousIncorrect.count { it.second.distinct().size == 1 },
    )
}

private fun nontrivialCounts(population: Issue0022HistoricalPopulation): Map<String, Int> = mapOf(
    "train" to population.examples.count { it.gameId in population.split.trainGames && it.input.candidates.size >= 2 },
    "validation" to population.examples.count { it.gameId in population.split.validationGames && it.input.candidates.size >= 2 },
    "test" to population.examples.count { it.gameId in population.split.testGames && it.input.candidates.size >= 2 },
).also {
    require(it == mapOf(
        "train" to ISSUE_0035_TRAIN_DECISIONS,
        "validation" to ISSUE_0035_VALIDATION_DECISIONS,
        "test" to ISSUE_0035_TEST_DECISIONS,
    ))
}

private fun baselinesMatch(current: Issue0035CohortMetrics, historical: BcCohortMetrics): Boolean =
    abs(current.uniform.expectedCorrect - historical.uniform.expectedCorrect) <= 1e-12 &&
        abs(current.stateIgnorantEmpirical.expectedCorrect - historical.stateIgnorantEmpirical.expectedCorrect) <= 1e-12

private fun classifyIssue0035(
    current: Issue0035CohortMetrics,
    historical: BcCohortMetrics,
    families: List<Issue0035CohortMetrics>,
    original: NeuralBcExperimentReport,
): String {
    val delta = current.neuralMeanAccuracy - requireNotNull(historical.neuralMeanAccuracy)
    val ordinary = families.single { it.cohort == "ORDINARY_ACTION" }
    val attacks = families.single { it.cohort == "DECLARE_ATTACKERS" }
    val oldOrdinary = original.byDecisionFamily.single { it.cohort == "ORDINARY_ACTION" }
    val oldAttacks = original.byDecisionFamily.single { it.cohort == "DECLARE_ATTACKERS" }
    val ordinaryDelta = ordinary.neuralMeanAccuracy - requireNotNull(oldOrdinary.neuralMeanAccuracy)
    val attackDelta = attacks.neuralMeanAccuracy - requireNotNull(oldAttacks.neuralMeanAccuracy)
    return when {
        current.neuralMaximumAccuracy - current.neuralMinimumAccuracy >= 0.10 ->
            "FULL_FIT_HELD_OUT_LARGE_SEED_VARIANCE"
        ordinaryDelta >= 0.05 && attackDelta <= 0.03 ->
            "FULL_FIT_ORDINARY_IMPROVES_ATTACKS_REMAIN_WEAK"
        delta >= 0.05 -> "FULL_FIT_MATERIALLY_IMPROVES_HELD_OUT"
        delta <= -0.05 -> "FULL_FIT_HELD_OUT_WORSE"
        abs(delta) <= 0.03 -> "FULL_FIT_HELD_OUT_REMAINS_NEAR_ORIGINAL"
        else -> "FULL_FIT_HELD_OUT_CHANGE_IS_MODEST_OR_MIXED"
    }
}

private fun interpretIssue0035(
    current: Issue0035CohortMetrics,
    historical: BcCohortMetrics,
    families: List<Issue0035CohortMetrics>,
    original: NeuralBcExperimentReport,
    errorOverlap: Issue0035ErrorOverlap,
): List<String> {
    val old = requireNotNull(historical.neuralMeanAccuracy)
    val ordinary = families.single { it.cohort == "ORDINARY_ACTION" }
    val attacks = families.single { it.cohort == "DECLARE_ATTACKERS" }
    val oldOrdinary = original.byDecisionFamily.single { it.cohort == "ORDINARY_ACTION" }
    val oldAttacks = original.byDecisionFamily.single { it.cohort == "DECLARE_ATTACKERS" }
    return listOf(
        "Every retained model reloads with its issue-0031 hash and reproduces 389/389 strict positive-margin fit before held-out scoring; evaluation performs no training update.",
        "Mean exact held-out imitation changes from $old to ${current.neuralMeanAccuracy} (${current.neuralMeanAccuracy - old} absolute) on the identical 69-decision two-game population.",
        "Ordinary-action mean changes from ${oldOrdinary.neuralMeanAccuracy} to ${ordinary.neuralMeanAccuracy}; attack-declaration mean changes from ${oldAttacks.neuralMeanAccuracy} to ${attacks.neuralMeanAccuracy}. Candidate-count cohorts must be read alongside that family split.",
        "The recomputed uniform and state-ignorant empirical expected-correct masses exactly reproduce issue 0022, confirming the primary population and baseline semantics rather than copying their percentages.",
        "Seed accuracies are ${current.neuralBySeed.joinToString { "${it.seed}: ${it.exactTeacherSelections}/${it.decisions}" }}. Perfect training fit therefore does not imply identical held-out behavior.",
        "Across held-out decision identities, ${errorOverlap.unanimouslyCorrect} are correct for all seeds, ${errorOverlap.unanimouslyIncorrect} are wrong for all seeds, and ${errorOverlap.mixedCorrectness} have seed-dependent correctness. The unanimous errors are ${errorOverlap.unanimousIncorrectByDecisionFamily.entries.joinToString { "${it.key}: ${it.value}" }}.",
    )
}

private fun narrowestIssue0035Question(diagnosticCase: String): String = when (diagnosticCase) {
    "FULL_FIT_MATERIALLY_IMPROVES_HELD_OUT" ->
        "Which held-out decision families and candidate-count strata account for the remaining errors once training failure is removed?"
    "FULL_FIT_ORDINARY_IMPROVES_ATTACKS_REMAIN_WEAK" ->
        "Within the frozen two-game evidence, does attack error track candidate-set size or an attack-specific represented distinction after conditioning on the available counts?"
    "FULL_FIT_HELD_OUT_REMAINS_NEAR_ORIGINAL" ->
        "Which fixed held-out errors are shared across all three fully fitted seeds, and do they expose a common representation/inductive-bias ambiguity rather than optimization failure?"
    "FULL_FIT_HELD_OUT_WORSE" ->
        "Which fixed held-out errors were introduced consistently by the fully fitted solutions, without tuning a new model against this test set?"
    "FULL_FIT_HELD_OUT_LARGE_SEED_VARIANCE" ->
        "Which held-out constraints separate the three fully fitted seed solutions, and are those differences concentrated by family or candidate count?"
    else -> "Which fixed held-out errors are common versus seed-specific after complete training fit?"
}

private fun issue0035CandidateRange(count: Int): String = when (count) {
    2 -> "2"
    in 3..4 -> "3-4"
    else -> "5+"
}

internal fun renderIssue0035Preflight(report: Issue0035PreflightReport): String = buildString {
    appendLine("Issue-0035 held-out generalization preflight passed")
    appendLine("  split: ${report.splitIdentity}; games ${report.trainGames}/${report.validationGames}/${report.testGames.size}")
    appendLine("  nontrivial train/validation/test: ${report.nontrivialDecisionsBySplit.values.joinToString("/")}")
    appendLine("  test games: ${report.testGames.joinToString()}")
    appendLine("  issue-0031 reference: ${report.referenceArtifactSha256}")
    report.retainedModels.forEach { model ->
        appendLine("  seed ${model.seed}: ${model.trainingStrictRankingCorrect}/${model.trainingDecisions}, model ${model.modelSha256}")
    }
    append("  held-out smoke: ${report.heldOutSmokeDecision.gameId}:${report.heldOutSmokeDecision.decisionIndex}; no training or output")
}

internal fun renderIssue0035NeuralHeldOutGeneralization(
    report: Issue0035NeuralHeldOutGeneralizationReport,
): String = buildString {
    fun pct(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f%%", value * 100.0)
    fun seedHits(metric: Issue0035CohortMetrics): String = metric.neuralBySeed.joinToString(" / ") {
        "${it.exactTeacherSelections}/${it.decisions} (${pct(it.exactTeacherActionAccuracy)})"
    }
    appendLine("# Issue 0035: fully fitted neural held-out generalization")
    appendLine()
    appendLine("Diagnostic case: `${report.diagnosticCase}`.")
    appendLine()
    report.interpretation.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Primary historical comparison")
    appendLine()
    appendLine("| Population | n | Uniform | Empirical | Seeds 1729 / 3253 / 6997 | Neural mean |")
    appendLine("| --- | ---: | ---: | ---: | --- | ---: |")
    appendLine("| Fully fitted issue-0031 models | ${report.primaryTest.decisions} | ${pct(report.primaryTest.uniform.accuracy)} | ${pct(report.primaryTest.stateIgnorantEmpirical.accuracy)} | ${seedHits(report.primaryTest)} | ${pct(report.primaryTest.neuralMeanAccuracy)} |")
    appendLine("| Original issue-0022 underfitting models | ${report.historicalOriginalPrimary.decisions} | ${pct(requireNotNull(report.historicalOriginalPrimary.uniform.accuracy))} | ${pct(requireNotNull(report.historicalOriginalPrimary.stateIgnorantEmpirical.accuracy))} | ${report.historicalOriginalPrimary.neuralAccuracyBySeed.entries.joinToString(" / ") { "${pct(it.value)}" }} | ${pct(requireNotNull(report.historicalOriginalPrimary.neuralMeanAccuracy))} |")
    appendLine()
    appendLine("## Decision families")
    appendLine()
    appendLine("| Family | n | Uniform | Empirical | Seeds | Mean |")
    appendLine("| --- | ---: | ---: | ---: | --- | ---: |")
    report.byDecisionFamily.forEach { metric ->
        appendLine("| ${metric.cohort} | ${metric.decisions} | ${pct(metric.uniform.accuracy)} | ${pct(metric.stateIgnorantEmpirical.accuracy)} | ${seedHits(metric)} | ${pct(metric.neuralMeanAccuracy)} |")
    }
    appendLine()
    appendLine("## Candidate-count ranges")
    appendLine()
    appendLine("| Candidates | n | Uniform | Empirical | Seeds | Mean |")
    appendLine("| --- | ---: | ---: | ---: | --- | ---: |")
    report.byCandidateCountRange.forEach { metric ->
        appendLine("| ${metric.cohort} | ${metric.decisions} | ${pct(metric.uniform.accuracy)} | ${pct(metric.stateIgnorantEmpirical.accuracy)} | ${seedHits(metric)} | ${pct(metric.neuralMeanAccuracy)} |")
    }
    appendLine()
    appendLine("## Attack declarations by exact candidate count")
    appendLine()
    appendLine("| Candidates | n | Uniform | Empirical | Seeds | Mean |")
    appendLine("| --- | ---: | ---: | ---: | --- | ---: |")
    report.attacksByExactCandidateCount.forEach { metric ->
        appendLine("| ${metric.cohort} | ${metric.decisions} | ${pct(metric.uniform.accuracy)} | ${pct(metric.stateIgnorantEmpirical.accuracy)} | ${seedHits(metric)} | ${pct(metric.neuralMeanAccuracy)} |")
    }
    appendLine()
    appendLine("## Cross-seed error overlap")
    appendLine()
    appendLine("- Unanimously correct: ${report.errorOverlap.unanimouslyCorrect}/${report.errorOverlap.decisions}.")
    appendLine("- Unanimously incorrect: ${report.errorOverlap.unanimouslyIncorrect}/${report.errorOverlap.decisions}; same predicted candidate in ${report.errorOverlap.unanimousIncorrectWithSamePredictedCandidate}.")
    appendLine("- Mixed correctness: ${report.errorOverlap.mixedCorrectness}/${report.errorOverlap.decisions}.")
    appendLine("- Unanimous errors by family: ${report.errorOverlap.unanimousIncorrectByDecisionFamily.entries.joinToString { "${it.key}=${it.value}" }}.")
    appendLine("- Unanimous errors by candidate count: ${report.errorOverlap.unanimousIncorrectByCandidateCount.entries.joinToString { "${it.key}=${it.value}" }}.")
    appendLine()
    appendLine("## Loss and margin diagnostics")
    appendLine()
    appendLine("| Seed | Exact | Cross-entropy | Teacher probability | Margin min / p10 / median / mean |")
    appendLine("| ---: | ---: | ---: | ---: | --- |")
    report.primaryTest.neuralBySeed.forEach { metric ->
        appendLine("| ${metric.seed} | ${metric.exactTeacherSelections}/${metric.decisions} | ${"%.6f".format(java.util.Locale.ROOT, metric.meanCrossEntropy)} | ${"%.6f".format(java.util.Locale.ROOT, metric.meanTeacherProbability)} | ${"%.6f / %.6f / %.6f / %.6f".format(java.util.Locale.ROOT, metric.margins.minimum, metric.margins.p10, metric.margins.median, metric.margins.mean)} |")
    }
    appendLine()
    appendLine("## Evidence boundary")
    appendLine()
    appendLine("- Split unit: ${report.splitUnit}; test games: ${report.testGames.joinToString()}.")
    appendLine("- Nontrivial train/validation/test: ${report.nontrivialDecisionsBySplit.values.joinToString("/")}.")
    appendLine("- Issue-0031 artifact: `${report.referenceArtifactSha256}`; original issue-0022 artifact: `${report.originalArtifactSha256}`.")
    appendLine("- Recomputed baseline identity: ${report.baselineRecomputationMatchesIssue0022}.")
    appendLine()
    appendLine("## Limitations")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Narrowest next question")
    appendLine()
    appendLine(report.narrowestNextQuestion)
}
