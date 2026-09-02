package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance

internal const val ISSUE_0025_PROTOCOL = "issue-0025-fixed-v3-neural-memorization-v1"
private const val ISSUE_0025_SUBSET_PROTOCOL = "sha256-nested-training-decisions-v1"
private const val ISSUE_0025_TRAINING_DECISIONS = 389

@Serializable
internal data class NeuralMemorizationSeedResult(
    val seed: Long,
    val epochsCompleted: Int,
    val stoppedAfterPerfectConfirmation: Boolean,
    val firstPerfectEpoch: Int?,
    val bestEpoch: Int,
    val bestStrictRankingCorrect: Int,
    val bestStrictRankingAccuracy: Double,
    val bestProductionTieBreakAccuracy: Double,
    val bestMeanCrossEntropy: Double,
    val bestMinimumTeacherMargin: Double,
    val finalStrictRankingAccuracy: Double,
    val finalMeanCrossEntropy: Double,
    val minimumObservedMeanCrossEntropy: Double,
    val firstEpochAtBestAccuracy: Int,
    val lossAtFirstBestAccuracy: Double,
    val minimumLossAfterFirstBestAccuracy: Double,
    val lossImprovementAfterAccuracyStalled: Double,
    val lossContinuedImprovingAfterAccuracyStalled: Boolean,
    val epochMetrics: List<NeuralMemorizationEpochMetric>,
    val retainedHardDecisions: List<NeuralMemorizationDecisionFit>,
    val modelPath: String,
    val modelSha256: String,
)

@Serializable
internal data class NeuralPersistentFailure(
    val decision: NeuralDecisionReference,
    val misrankedSeeds: List<Long>,
    val meanTeacherLossWhenMisranked: Double,
    val worstTeacherMarginAcrossSeeds: Double,
)

@Serializable
internal data class NeuralMemorizationStageResult(
    val config: NeuralMemorizationStageConfig,
    val subsetIdentity: String,
    val selectedDecisions: List<NeuralDecisionReference>,
    val seedResults: List<NeuralMemorizationSeedResult>,
    val reliablyMemorizedByAllSeeds: Boolean,
    val seedsReachingPerfectStrictRanking: Int,
    val decisionsMisrankedByAnySeed: Int,
    val decisionsMisrankedByAllSeeds: Int,
    val persistentFailures: List<NeuralPersistentFailure>,
)

@Serializable
internal data class NeuralActivationSaturationComparison(
    val diagnosticQuestion: String,
    val successfulSubsetDecisions: Int,
    val failingSubsetDecisions: Int,
    val successfulSubsetAudits: List<NeuralActivationSaturationSeedAudit>,
    val failingSubsetAudits: List<NeuralActivationSaturationSeedAudit>,
)

@Serializable
internal data class Issue0025NeuralMemorizationReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0025_PROTOCOL,
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
    val subsetSelectionOrder: List<NeuralDecisionReference>,
    val modelConfig: NeuralBcModelConfig,
    val optimizer: String,
    val learningRate: Double,
    val initializationSeeds: List<Long>,
    val perfectConfirmationEpochs: Int,
    val realizability: NeuralFullInputRealizability,
    val stages: List<NeuralMemorizationStageResult>,
    val largestReliablyMemorizedSubset: Int,
    val firstFailingSubset: Int?,
    val diagnosticCase: String,
    val activationSaturationDiagnostic: NeuralActivationSaturationComparison?,
    val narrowDiagnostic: List<String>,
    val interpretation: List<String>,
    val limitations: List<String>,
)

internal class Issue0025NeuralMemorizationDiagnostic(
    private val root: Path,
    private val outputDirectory: Path,
    private val stageConfigs: List<NeuralMemorizationStageConfig> = listOf(
        NeuralMemorizationStageConfig(decisions = 1, maximumEpochs = 1_000),
        NeuralMemorizationStageConfig(decisions = 4, maximumEpochs = 1_000),
        NeuralMemorizationStageConfig(decisions = 16, maximumEpochs = 1_500),
        NeuralMemorizationStageConfig(decisions = 64, maximumEpochs = 1_500),
        NeuralMemorizationStageConfig(decisions = 389, maximumEpochs = 600),
    ),
    private val seeds: List<Long> = listOf(1729L, 3253L, 6997L),
    private val learningRate: Double = 0.01,
    private val perfectConfirmationEpochs: Int = 20,
) {
    init {
        require(stageConfigs.map { it.decisions } == stageConfigs.map { it.decisions }.sorted())
        require(stageConfigs.map { it.decisions }.distinct().size == stageConfigs.size)
        require(stageConfigs.last().decisions == ISSUE_0025_TRAINING_DECISIONS)
        require(seeds.isNotEmpty() && seeds.distinct().size == seeds.size)
    }

    fun run(
        historicalManifestPath: Path,
        progress: (String) -> Unit = {},
    ): Issue0025NeuralMemorizationReport {
        Files.createDirectories(outputDirectory)
        val implementation = requireNotNull(RunProvenance.capture(root).sourceProvenance)
        val population = Issue0022HistoricalCorpusReader(root).read(historicalManifestPath)
        val encoder = NeuralBehavioralCloningFeatureEncoder()
        val trainGames = population.split.trainGames.toSet()
        val train = population.examples.asSequence()
            .filter { it.gameId in trainGames }
            .map { it.encode(encoder) }
            .filter { it.candidateCount >= PRIMARY_MIN_CANDIDATES }
            .toList()
        require(train.size == ISSUE_0025_TRAINING_DECISIONS)
        progress("Read and repaired-encoded ${train.size} exact issue-0022 training decisions")

        val realizability = auditNeuralFullInputRealizability(train)
        progress(
            "Full-input ranking graph: ${realizability.distinctScorerInputs} exact scorer inputs, " +
                "${realizability.rankingConstraints} constraints, " +
                "${realizability.contradictoryRankingComponents.size} contradictory components"
        )
        require(
            !realizability.unrestrictedDeterministicScorerIsConsistent ||
                realizability.constructiveStrictRankingCorrect == train.size
        )

        val ordered = deterministicNeuralMemorizationOrder(
            decisions = train,
            datasetIdentity = population.manifest.datasetIdentity,
            protocol = ISSUE_0025_SUBSET_PROTOCOL,
        )
        val selectionMaterial = ordered.joinToString("\n") { "${it.gameId}:${it.decisionIndex}" }
        val modelConfig = NeuralBcModelConfig(
            stateDimension = encoder.stateDimension,
            candidateDimension = encoder.candidateDimension,
        )
        val stages = if (realizability.unrestrictedDeterministicScorerIsConsistent) {
            stageConfigs.map { stage ->
                val subset = ordered.take(stage.decisions)
                val subsetIdentity = PolicyJson.sha256(
                    subset.joinToString("\n") { "${it.gameId}:${it.decisionIndex}" }
                )
                progress(
                    "Memorization stage n=${stage.decisions}, maxEpochs=${stage.maximumEpochs}, " +
                        "subset=$subsetIdentity"
                )
                val seedResults = seeds.map { seed ->
                    progress("Training bilinear memorizer n=${stage.decisions} seed=$seed")
                    val trainingConfig = NeuralBcTrainingConfig(
                        maximumEpochs = stage.maximumEpochs,
                        learningRate = learningRate,
                        initializationSeeds = seeds,
                    )
                    val trained = NeuralBcMemorizationTrainer(
                        modelConfig = modelConfig,
                        trainingConfig = trainingConfig,
                        perfectConfirmationEpochs = perfectConfirmationEpochs,
                    ).train(subset, seed)
                    val modelPath = outputDirectory.resolve(
                        "bilinear-n${stage.decisions}-seed-$seed.json"
                    )
                    trained.policy.save(modelPath)
                    progress(
                        "Finished n=${stage.decisions} seed=$seed: " +
                            "best=${trained.bestStrictRankingCorrect}/${stage.decisions}, " +
                            "loss=${trained.bestMeanCrossEntropy}, epochs=${trained.epochsCompleted}"
                    )
                    trained.seedResult(seed, modelPath)
                }
                stageResult(stage, subsetIdentity, subset, seedResults)
            }
        } else {
            emptyList()
        }
        val largestMemorized = stages.filter(NeuralMemorizationStageResult::reliablyMemorizedByAllSeeds)
            .maxOfOrNull { it.config.decisions } ?: 0
        val firstFailing = stages.firstOrNull { !it.reliablyMemorizedByAllSeeds }?.config?.decisions
        val diagnosticCase = when {
            !realizability.unrestrictedDeterministicScorerIsConsistent ->
                "FULL_SCORER_INPUT_MAPPING_CONTAINS_CONTRADICTORY_RANKINGS"
            largestMemorized == ISSUE_0025_TRAINING_DECISIONS ->
                "FULL_TRAINING_MAPPING_MEMORIZED_WITH_EXISTING_BILINEAR_ARCHITECTURE"
            firstFailing == 1 -> "SINGLE_DECISION_MEMORIZATION_FAILED"
            else -> "MEMORIZATION_FAILS_AS_NESTED_SUBSET_GROWS"
        }
        val saturationDiagnostic = activationSaturationDiagnostic(
            ordered = ordered,
            stages = stages,
            largestMemorized = largestMemorized,
            firstFailing = firstFailing,
        )
        return Issue0025NeuralMemorizationReport(
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
            subsetSelectionProtocol = ISSUE_0025_SUBSET_PROTOCOL,
            subsetSelectionOrderSha256 = PolicyJson.sha256(selectionMaterial),
            subsetSelectionOrder = ordered.map(EncodedBcDecision::neuralDecisionReference),
            modelConfig = modelConfig,
            optimizer = "existing issue-0022 SparseAdam; per-decision cross-entropy updates; no validation selection",
            learningRate = learningRate,
            initializationSeeds = seeds,
            perfectConfirmationEpochs = perfectConfirmationEpochs,
            realizability = realizability,
            stages = stages,
            largestReliablyMemorizedSubset = largestMemorized,
            firstFailingSubset = firstFailing,
            diagnosticCase = diagnosticCase,
            activationSaturationDiagnostic = saturationDiagnostic,
            narrowDiagnostic = narrowDiagnostic(saturationDiagnostic),
            interpretation = interpretation(
                realizability,
                stages,
                largestMemorized,
                firstFailing,
                saturationDiagnostic,
            ),
            limitations = listOf(
                "This is a training-set realizability and memorization diagnostic on the exact fixed issue-0022 candidate-V3 training population, not held-out or playing-strength evidence.",
                "Static consistency proves existence only for an unrestricted deterministic function over exact encoded scorer inputs; it does not prove realizability by the 49,248-parameter bilinear network.",
                "Reliable memorization means all three fixed initialization seeds achieved strict teacher-over-every-alternative ranking on the nested subset.",
                "The ladder changes only subset size and maximum training duration; it retains repaired features, bilinear architecture, exact-label cross-entropy, SparseAdam at 0.01, and the candidate-conditioned scoring task.",
                "The activation audit observes saved best checkpoints, not the path from initialization; it does not yet distinguish input/initialization scale from optimizer-driven saturation.",
                "The 16-to-64 ladder gap localizes the first tested failure stage, not the exact first failing prefix length.",
            ),
        )
    }

    private fun activationSaturationDiagnostic(
        ordered: List<EncodedBcDecision>,
        stages: List<NeuralMemorizationStageResult>,
        largestMemorized: Int,
        firstFailing: Int?,
    ): NeuralActivationSaturationComparison? {
        if (largestMemorized <= 0 || firstFailing == null) return null
        val successful = stages.single { it.config.decisions == largestMemorized }
        val failing = stages.single { it.config.decisions == firstFailing }
        val hardDecisions = failing.persistentFailures.map {
            it.decision.gameId to it.decision.decisionIndex
        }.toSet()
        fun audit(stage: NeuralMemorizationStageResult): List<NeuralActivationSaturationSeedAudit> =
            stage.seedResults.map { seed ->
                auditNeuralBcActivationSaturation(
                    policy = CandidateConditionedNeuralPolicy.load(root.resolve(seed.modelPath)),
                    decisions = ordered.take(stage.config.decisions),
                    hardDecisions = hardDecisions,
                )
            }
        return NeuralActivationSaturationComparison(
            diagnosticQuestion =
                "Did tanh saturation collapse distinct learned scorer inputs at the first tested failure boundary?",
            successfulSubsetDecisions = successful.config.decisions,
            failingSubsetDecisions = failing.config.decisions,
            successfulSubsetAudits = audit(successful),
            failingSubsetAudits = audit(failing),
        )
    }

    private fun narrowDiagnostic(
        diagnostic: NeuralActivationSaturationComparison?,
    ): List<String> {
        if (diagnostic == null) return emptyList()
        fun range(values: List<Double>): String =
            "${"%.2f".format(values.minOrNull()!! * 100.0)}–" +
                "${"%.2f".format(values.maxOrNull()!! * 100.0)}%"
        val success = diagnostic.successfulSubsetAudits
        val failure = diagnostic.failingSubsetAudits
        return listOf(
            "The one post-ladder diagnostic compared exact tanh projections at the " +
                "${diagnostic.successfulSubsetDecisions}-decision successful stage and " +
                "${diagnostic.failingSubsetDecisions}-decision failing stage.",
            "Exactly saturated state activations were ${range(success.map { it.stateExactlySaturatedFraction })} " +
                "at ${diagnostic.successfulSubsetDecisions} decisions and " +
                "${range(failure.map { it.stateExactlySaturatedFraction })} at " +
                "${diagnostic.failingSubsetDecisions}; near-saturated (|tanh| >= 0.99) state activations " +
                "were ${range(success.map { it.stateNearSaturatedFraction })} and " +
                "${range(failure.map { it.stateNearSaturatedFraction })}, respectively.",
            "At the failing stage, learned hidden-input audits found " +
                failure.joinToString(" / ") { audit ->
                    val groups = audit.effectiveHiddenInputRealizability.repeatedEncodedStateGroups
                    "seed ${audit.seed}: $groups repeated-state " +
                        (if (groups == 1) "group" else "groups") + ", " +
                        "${audit.effectiveHiddenInputRealizability.contradictoryRankingComponents.size} " +
                        "contradictory components, " +
                        "${audit.hardDecisionsInRepeatedProjectedStateGroups} hard decisions in repeated-state groups"
                } + ".",
        )
    }

    private fun TrainedNeuralMemorizationModel.seedResult(
        seed: Long,
        modelPath: Path,
    ): NeuralMemorizationSeedResult = NeuralMemorizationSeedResult(
        seed = seed,
        epochsCompleted = epochsCompleted,
        stoppedAfterPerfectConfirmation = stoppedAfterPerfectConfirmation,
        firstPerfectEpoch = firstPerfectEpoch,
        bestEpoch = bestEpoch,
        bestStrictRankingCorrect = bestStrictRankingCorrect,
        bestStrictRankingAccuracy = bestStrictRankingAccuracy,
        bestProductionTieBreakAccuracy = bestProductionTieBreakAccuracy,
        bestMeanCrossEntropy = bestMeanCrossEntropy,
        bestMinimumTeacherMargin = bestMinimumTeacherMargin,
        finalStrictRankingAccuracy = finalStrictRankingAccuracy,
        finalMeanCrossEntropy = finalMeanCrossEntropy,
        minimumObservedMeanCrossEntropy = minimumObservedMeanCrossEntropy,
        firstEpochAtBestAccuracy = firstEpochAtBestAccuracy,
        lossAtFirstBestAccuracy = lossAtFirstBestAccuracy,
        minimumLossAfterFirstBestAccuracy = minimumLossAfterFirstBestAccuracy,
        lossImprovementAfterAccuracyStalled = lossImprovementAfterAccuracyStalled,
        lossContinuedImprovingAfterAccuracyStalled = lossContinuedImprovingAfterAccuracyStalled,
        epochMetrics = epochMetrics,
        retainedHardDecisions = retainedHardDecisions,
        modelPath = root.relativize(modelPath).toString(),
        modelSha256 = sha256File(modelPath),
    )

    private fun stageResult(
        config: NeuralMemorizationStageConfig,
        subsetIdentity: String,
        subset: List<EncodedBcDecision>,
        seeds: List<NeuralMemorizationSeedResult>,
    ): NeuralMemorizationStageResult {
        val fitsByDecision = seeds.flatMap { seed ->
            seed.retainedHardDecisions.map { fit -> seed.seed to fit }
        }.groupBy { (_, fit) -> fit.decision.gameId to fit.decision.decisionIndex }
        val failures = subset.map(EncodedBcDecision::neuralDecisionReference).mapNotNull { decision ->
            val fits = fitsByDecision[decision.gameId to decision.decisionIndex].orEmpty()
            val misranked = fits.filterNot { (_, fit) -> fit.strictRankingCorrect }
            if (misranked.isEmpty()) null else {
                NeuralPersistentFailure(
                    decision = decision,
                    misrankedSeeds = misranked.map { it.first }.sorted(),
                    meanTeacherLossWhenMisranked = misranked.map {
                        it.second.meanCrossEntropyContribution
                    }.average(),
                    worstTeacherMarginAcrossSeeds = misranked.minOf { it.second.teacherMargin },
                )
            }
        }.sortedWith(
            compareByDescending<NeuralPersistentFailure> { it.misrankedSeeds.size }
                .thenByDescending { it.meanTeacherLossWhenMisranked }
                .thenBy { it.decision.gameId }
                .thenBy { it.decision.decisionIndex }
        )
        return NeuralMemorizationStageResult(
            config = config,
            subsetIdentity = subsetIdentity,
            selectedDecisions = subset.map(EncodedBcDecision::neuralDecisionReference),
            seedResults = seeds,
            reliablyMemorizedByAllSeeds = seeds.all { it.bestStrictRankingCorrect == config.decisions },
            seedsReachingPerfectStrictRanking = seeds.count { it.bestStrictRankingCorrect == config.decisions },
            decisionsMisrankedByAnySeed = failures.size,
            decisionsMisrankedByAllSeeds = failures.count { it.misrankedSeeds.size == seeds.size },
            persistentFailures = failures,
        )
    }

    private fun interpretation(
        realizability: NeuralFullInputRealizability,
        stages: List<NeuralMemorizationStageResult>,
        largestMemorized: Int,
        firstFailing: Int?,
        saturationDiagnostic: NeuralActivationSaturationComparison?,
    ): List<String> = buildList {
        if (realizability.unrestrictedDeterministicScorerIsConsistent) {
            add(
                "The exact shared-scorer ranking graph is acyclic and its constructed lookup score " +
                    "strictly ranks ${realizability.constructiveStrictRankingCorrect}/" +
                    "${realizability.trainingDecisions} teacher candidates first."
            )
        } else {
            add(
                "The exact shared-scorer ranking graph contains " +
                    "${realizability.contradictoryRankingComponents.size} contradictory components " +
                    "affecting ${realizability.decisionsAffectedByContradictions} decisions."
            )
        }
        if (stages.isNotEmpty()) {
            add("The largest nested subset memorized by every seed is $largestMemorized decisions.")
            firstFailing?.let { add("The first nested subset not memorized by every seed is $it decisions.") }
        }
        saturationDiagnostic?.let { diagnostic ->
            val failing = diagnostic.failingSubsetAudits
            val learnedContradictions = failing.sumOf {
                it.effectiveHiddenInputRealizability.contradictoryRankingComponents.size
            }
            val hardAliases = failing.sumOf(NeuralActivationSaturationSeedAudit::hardDecisionsInRepeatedProjectedStateGroups)
            if (learnedContradictions > 0 || hardAliases > 0) {
                add(
                    "The targeted activation audit found learned projection aliases at the failing boundary; " +
                        "this implicates tanh saturation/model-input-training interaction rather than a contradiction " +
                        "in the repaired raw encoded inputs."
                )
            } else {
                add(
                    "The targeted activation audit did not find exact learned projection aliases involving the " +
                        "hard cohort; saturation, finite bilinear expressiveness, and optimization interference " +
                        "therefore remain unresolved."
                )
            }
        }
    }
}

internal fun renderIssue0025NeuralMemorizationDiagnostic(
    report: Issue0025NeuralMemorizationReport,
): String = buildString {
    fun pct(value: Double): String = "%.2f%%".format(value * 100.0)
    fun decimal(value: Double): String = "%.6f".format(value)
    fun scientific(value: Double): String = "%.3e".format(value)

    appendLine("# Neural full-input realizability and memorization ladder")
    appendLine()
    appendLine("## Conclusion")
    appendLine()
    appendLine("`${report.diagnosticCase}`")
    appendLine()
    appendLine("This is fixed-corpus training-fit evidence, not held-out or playing-strength evidence.")
    appendLine()
    appendLine("## Exact population and protocol")
    appendLine()
    appendLine("- Candidate-V${report.historicalCandidateSchemaVersion} dataset: `${report.corpusDatasetIdentity}`")
    appendLine("- Manifest SHA-256: `${report.corpusManifestSha256}`")
    appendLine("- Whole-game split: `${report.splitIdentity}`; training decisions: ${report.trainingDecisions}")
    appendLine("- Repaired feature schema: `${report.featureSchema}`")
    appendLine("- Nested order: `${report.subsetSelectionProtocol}` / `${report.subsetSelectionOrderSha256}`")
    appendLine("- Model: ${report.modelConfig.parameterCount}-parameter issue-0022 bilinear scorer")
    appendLine("- Training: ${report.optimizer}; learning rate ${report.learningRate}; seeds ${report.initializationSeeds}")
    appendLine()
    appendLine("## Full scorer-input realizability")
    appendLine()
    val audit = report.realizability
    appendLine("| Check | Result |")
    appendLine("| --- | ---: |")
    appendLine("| Exact decision inputs | ${audit.distinctEncodedDecisionInputs}/${audit.trainingDecisions} distinct |")
    appendLine("| Duplicate full-decision groups | ${audit.exactDuplicateDecisionInputGroups} |")
    appendLine("| Encoded states | ${audit.distinctEncodedStates} distinct; ${audit.repeatedEncodedStateGroups} repeated groups |")
    appendLine("| Exact `(state, candidate)` scorer inputs | ${audit.distinctScorerInputs}/${audit.candidateOccurrences} distinct occurrences |")
    appendLine("| Duplicate scorer-input groups | ${audit.exactDuplicateScorerInputGroups} |")
    appendLine("| Strict ranking constraints | ${audit.rankingConstraints} total; ${audit.distinctRankingConstraints} distinct |")
    appendLine("| Contradictory components | ${audit.contradictoryRankingComponents.size} |")
    appendLine("| Decisions affected by contradictions | ${audit.decisionsAffectedByContradictions} |")
    appendLine("| Unrestricted deterministic scorer consistent | ${audit.unrestrictedDeterministicScorerIsConsistent} |")
    appendLine("| Constructed strict ranking | ${audit.constructiveStrictRankingCorrect}/${audit.trainingDecisions} (${pct(audit.constructiveStrictRankingAccuracy)}) |")
    appendLine()
    if (audit.repeatedEncodedStates.isNotEmpty()) {
        appendLine("Repeated encoded-state groups:")
        audit.repeatedEncodedStates.forEach { group ->
            appendLine("- `${group.encodedStateId}`: " + group.decisions.joinToString { "`${it.gameId}:${it.decisionIndex}`" })
        }
        appendLine()
    }
    if (audit.contradictoryRankingComponents.isNotEmpty()) {
        appendLine("Contradictory ranking components:")
        audit.contradictoryRankingComponents.forEach { component ->
            appendLine("- ${component.scorerInputIds.size} scorer inputs / ${component.constraints.size} constraints: " +
                component.affectedDecisions.joinToString { "`${it.gameId}:${it.decisionIndex}`" })
        }
        appendLine()
    }
    appendLine("## Nested memorization ladder")
    appendLine()
    appendLine("Reliable memorization requires strict teacher-over-every-alternative ranking for all three seeds.")
    appendLine()
    appendLine("| n | Max epochs | Perfect seeds | Reliable | Best strict fit by seed | First perfect epoch by seed |")
    appendLine("| ---: | ---: | ---: | --- | --- | --- |")
    report.stages.forEach { stage ->
        appendLine("| ${stage.config.decisions} | ${stage.config.maximumEpochs} | " +
            "${stage.seedsReachingPerfectStrictRanking}/${stage.seedResults.size} | " +
            "${stage.reliablyMemorizedByAllSeeds} | " +
            stage.seedResults.joinToString(" / ") { pct(it.bestStrictRankingAccuracy) } + " | " +
            stage.seedResults.joinToString(" / ") { it.firstPerfectEpoch?.toString() ?: "—" } + " |")
    }
    appendLine()
    report.stages.forEach { stage ->
        appendLine("### ${stage.config.decisions} decisions")
        appendLine()
        appendLine("| Seed | Epochs | Best epoch | Best strict | Best loss | Final strict | Final loss | Loss improvement after accuracy stall |")
        appendLine("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        stage.seedResults.forEach { seed ->
            appendLine("| ${seed.seed} | ${seed.epochsCompleted} | ${seed.bestEpoch} | " +
                "${pct(seed.bestStrictRankingAccuracy)} | ${decimal(seed.bestMeanCrossEntropy)} | " +
                "${pct(seed.finalStrictRankingAccuracy)} | ${decimal(seed.finalMeanCrossEntropy)} | " +
                "${decimal(seed.lossImprovementAfterAccuracyStalled)} |")
        }
        if (stage.persistentFailures.isNotEmpty()) {
            appendLine()
            appendLine("Persistent best-checkpoint misrankings:")
            stage.persistentFailures.forEach { failure ->
                appendLine("- `${failure.decision.gameId}:${failure.decision.decisionIndex}` " +
                    "(${failure.decision.decisionFamily}, ${failure.decision.candidateCount} candidates): " +
                    "seeds ${failure.misrankedSeeds}, mean loss when misranked " +
                    "${decimal(failure.meanTeacherLossWhenMisranked)}, " +
                    "worst margin ${decimal(failure.worstTeacherMarginAcrossSeeds)}")
            }
        }
        appendLine()
    }
    report.activationSaturationDiagnostic?.let { diagnostic ->
        appendLine("## Targeted activation-saturation diagnostic")
        appendLine()
        appendLine(diagnostic.diagnosticQuestion)
        appendLine()
        appendLine("| Stage | Seed | Exact state sat. | Near state sat. | Mean state derivative | " +
            "Exact candidate sat. | Near candidate sat. | Repeated projected states | Hidden contradictions | Affected decisions | Hard in repeated states |")
        appendLine("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        (diagnostic.successfulSubsetAudits + diagnostic.failingSubsetAudits).forEach { audit ->
            appendLine("| ${audit.subsetDecisions} | ${audit.seed} | " +
                "${pct(audit.stateExactlySaturatedFraction)} | ${pct(audit.stateNearSaturatedFraction)} | " +
                "${scientific(audit.meanStateTanhDerivative)} | " +
                "${pct(audit.candidateExactlySaturatedFraction)} | " +
                "${pct(audit.candidateNearSaturatedFraction)} | " +
                "${audit.effectiveHiddenInputRealizability.repeatedEncodedStateGroups} | " +
                "${audit.effectiveHiddenInputRealizability.contradictoryRankingComponents.size} | " +
                "${audit.effectiveHiddenInputRealizability.decisionsAffectedByContradictions} | " +
                "${audit.hardDecisionsInRepeatedProjectedStateGroups} |")
        }
        appendLine()
        val hardDecisionKeys = report.stages.single {
            it.config.decisions == diagnostic.failingSubsetDecisions
        }.persistentFailures.map { it.decision.gameId to it.decision.decisionIndex }.toSet()
        diagnostic.failingSubsetAudits.forEach { audit ->
            if (audit.repeatedProjectedStateGroupsContainingHardDecisions.isNotEmpty()) {
                appendLine("Seed ${audit.seed} repeated projected-state groups containing hard decisions:")
                audit.repeatedProjectedStateGroupsContainingHardDecisions.forEach { group ->
                    val hardMembers = group.decisions.filter {
                        it.gameId to it.decisionIndex in hardDecisionKeys
                    }
                    appendLine("- `${group.encodedStateId}` has ${group.decisions.size} decisions; hard members: " +
                        hardMembers.joinToString { "`${it.gameId}:${it.decisionIndex}`" })
                }
                appendLine()
            }
        }
    }
    appendLine("## Interpretation and limits")
    appendLine()
    report.interpretation.forEach { appendLine("- $it") }
    report.narrowDiagnostic.forEach { appendLine("- $it") }
    report.limitations.forEach { appendLine("- $it") }
}
