package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.BOUNDED_POLICY_INPUT_SCHEMA_V5
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.POLICY_SCHEMA_V6
import org.mtgallium.agent.infoset.core.PolicyBeliefSummary
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.SemanticActionIntent
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance

internal const val ISSUE_0024_PROTOCOL = "issue-0024-fixed-v3-neural-capacity-diagnostic-v1"
private const val ISSUE_0022_DATASET_IDENTITY =
    "corpus-dataset-v5-sha256:56aaef70d1a5579fd7c6a92da6cbd80cad0dd4b3a5a1b8b0c7b3dac2f5ca09a0"
private const val ISSUE_0022_SPLIT_IDENTITY =
    "neural-bc-split-v1-sha256:1e2c71e72fe4453001e0b28e794088d4da39cbdb889c30ae52c394f1249ecf81"
private const val ISSUE_0022_MANIFEST_SHA256 =
    "070eb011b92c6b95000425cf8ba20ac6bb499afff61cd5d788435621a3230cd2"
private const val ISSUE_0022_SPLIT_SHA256 =
    "acf7357dcbde8830a845ac8c412c4e5ef8f05a177a4c40f79f20cc674487e7be"
private const val ISSUE_0022_SOURCE_REVISION = "3fc092e73ff04c3896973ebb016f27d4459350d5"
private const val ISSUE_0022_ARGENTUM_REVISION = "3eda577fdd10d08e0e62d66b4727ab53f1b41ff5"
private const val ISSUE_0022_NONTRIVIAL_DECISIONS = 494
private const val ISSUE_0022_ADMITTED_DECISIONS = 1_562
private const val ISSUE_0022_ORIGINAL_MAX_TRAIN_MEAN =
    (0.6683804627249358 + 0.6863753213367609 + 0.6760925449871465) / 3.0
private val ISSUE_0022_ORIGINAL_MAX_TRAIN_BY_SEED = mapOf(
    1729L to 0.6683804627249358,
    3253L to 0.6863753213367609,
    6997L to 0.6760925449871465,
)
private const val HISTORICAL_TRAJECTORY_SCHEMA = 12

/** V3 choice reader with the exact serialized fields and no current-schema promotion. */
@Serializable
internal data class HistoricalSemanticChoiceV3(
    val schemaVersion: Int,
    val signature: String,
    val kind: SemanticChoiceKind,
    val operationFamily: SemanticOperationFamily,
    val actionIntent: SemanticActionIntent,
    val display: SemanticChoiceDisplay,
    val canonicalPayload: JsonObject,
) {
    init {
        require(schemaVersion == CANDIDATE_SCHEMA_V3)
        require(signature == SemanticChoice.computeSignature(operationFamily, actionIntent, canonicalPayload))
    }

    fun featureView(): NeuralBcFeatureCandidate = NeuralBcFeatureCandidate(
        kind = kind,
        operationFamily = operationFamily,
        actionIntent = actionIntent,
        canonicalPayload = canonicalPayload,
    )
}

/** Exact issue-0022 `BoundedPolicyInput` V5/candidate-V3 persisted contract. */
@Serializable
internal data class HistoricalBoundedPolicyInputV5CandidateV3(
    val schemaVersion: Int,
    val actingPlayerId: String?,
    val observation: PolicyObservation,
    val knowledge: PolicyKnowledgeState,
    val belief: PolicyBeliefSummary,
    val recentEvents: List<PolicyHistoryEvent>,
    val recentEventStartCursor: Int,
    val historyCommitment: PolicyHistoryCommitment,
    val informationStateDigest: String,
    val candidates: List<HistoricalSemanticChoiceV3>,
    val candidateSchemaVersion: Int,
    val terminated: Boolean,
    val winnerId: String?,
    val inputDigest: String,
) {
    init {
        require(schemaVersion == BOUNDED_POLICY_INPUT_SCHEMA_V5)
        require(candidateSchemaVersion == CANDIDATE_SCHEMA_V3)
        require(observation.currentTurnStateComplete)
        require(candidates.isNotEmpty() && candidates.map { it.signature }.distinct().size == candidates.size)
        require(belief.knowledgeDigest == knowledge.knowledgeDigest)
    }

    fun requireValidDigest() {
        require(inputDigest == canonicalDigest()) { "Historical bounded-input digest mismatch" }
    }

    fun featureView(): NeuralBcFeatureInput = NeuralBcFeatureInput(
        actingPlayerId = actingPlayerId,
        observation = observation,
        knowledge = knowledge,
        recentEvents = recentEvents,
        candidates = candidates.map(HistoricalSemanticChoiceV3::featureView),
        candidateSchemaVersion = candidateSchemaVersion,
    )

    private fun canonicalDigest(): String = PolicyJson.digest(
        PolicyJson.format.encodeToJsonElement(
            serializer(),
            copy(inputDigest = ""),
        )
    )
}

internal data class Issue0022HistoricalExample(
    val gameId: String,
    val decisionIndex: Int,
    val input: HistoricalBoundedPolicyInputV5CandidateV3,
    val teacherAction: HistoricalSemanticChoiceV3,
    val labelIndex: Int,
) {
    fun encode(encoder: NeuralBehavioralCloningFeatureEncoder): EncodedBcDecision = encoder.encode(
        input = input.featureView(),
        labelIndex = labelIndex,
        gameId = gameId,
        decisionIndex = decisionIndex,
    )
}

internal data class Issue0022HistoricalPopulation(
    val manifest: CorpusManifest,
    val manifestPath: Path,
    val manifestSha256: String,
    val split: NeuralBcGameSplit,
    val splitPath: Path,
    val splitSha256: String,
    val examples: List<Issue0022HistoricalExample>,
)

/**
 * Narrow source-bound reader for the one retained issue-0022 population. Current public-corpus
 * admission remains V4-only. This reader requires the exact historical manifest, split, source,
 * schema tuple, per-file sizes/hashes, and every original bounded-input digest.
 */
internal class Issue0022HistoricalCorpusReader(private val root: Path) {
    fun read(manifestPath: Path): Issue0022HistoricalPopulation {
        val normalizedManifest = requireRegularFile(manifestPath)
        require(sha256File(normalizedManifest) == ISSUE_0022_MANIFEST_SHA256) {
            "Issue-0022 diagnostic requires the exact retained admitted manifest"
        }
        val manifest = evidenceJson.decodeFromString<CorpusManifest>(Files.readString(normalizedManifest))
        require(manifest.datasetIdentity == ISSUE_0022_DATASET_IDENTITY)
        require(manifest.outerCommit == ISSUE_0022_SOURCE_REVISION)
        require(manifest.argentumCommit == ISSUE_0022_ARGENTUM_REVISION)
        require(manifest.entries.size == 19 && manifest.entries.none { it.gameId == "teacher-corpus-000016" })
        require(manifest.requestedGames == 19 && manifest.terminalGames == 19 && manifest.replayVerifiedGames == 19)
        require(manifest.passed)

        val splitPath = requireRegularFile(normalizedManifest.parent.resolve("split.json"))
        require(sha256File(splitPath) == ISSUE_0022_SPLIT_SHA256) {
            "Issue-0022 diagnostic requires the exact retained whole-game split"
        }
        val split = evidenceJson.decodeFromString<NeuralBcGameSplit>(Files.readString(splitPath))
        require(split.datasetIdentity == ISSUE_0022_DATASET_IDENTITY)
        require(split.splitIdentity == ISSUE_0022_SPLIT_IDENTITY)
        require(split.trainGames.size == 15 && split.validationGames.size == 2 && split.testGames.size == 2)

        val examples = manifest.entries.sortedBy { it.gameId }.flatMap { entry ->
            val trajectory = requireRegularFile(root.resolve(entry.publicTrajectory))
            require(Files.size(trajectory) == entry.publicSizeBytes)
            require(sha256File(trajectory) == entry.publicSha256)
            readTrajectory(entry, trajectory, manifest)
        }
        require(examples.size == ISSUE_0022_ADMITTED_DECISIONS)
        require(examples.map { it.gameId }.toSet() ==
            (split.trainGames + split.validationGames + split.testGames).toSet())
        require(examples.count { it.input.candidates.size >= PRIMARY_MIN_CANDIDATES } ==
            ISSUE_0022_NONTRIVIAL_DECISIONS)
        require(examples.distinctBy { it.gameId to it.decisionIndex }.size == examples.size)
        return Issue0022HistoricalPopulation(
            manifest = manifest,
            manifestPath = normalizedManifest,
            manifestSha256 = sha256File(normalizedManifest),
            split = split,
            splitPath = splitPath,
            splitSha256 = sha256File(splitPath),
            examples = examples,
        )
    }

    private fun readTrajectory(
        entry: CorpusEntry,
        path: Path,
        manifest: CorpusManifest,
    ): List<Issue0022HistoricalExample> {
        val examples = mutableListOf<Issue0022HistoricalExample>()
        var lineNumber = 0
        var headerSeen = false
        GZIPInputStream(Files.newInputStream(path)).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                lineNumber++
                val record = PolicyJson.format.parseToJsonElement(line).jsonObject
                val type = record.getValue("type").jsonPrimitive.content
                val schema = record.getValue("schemaVersion").jsonPrimitive.int
                require(schema == HISTORICAL_TRAJECTORY_SCHEMA) {
                    "Unexpected historical trajectory schema in ${entry.gameId}:$lineNumber"
                }
                when (type) {
                    "header" -> {
                        require(lineNumber == 1 && !headerSeen)
                        require(record.getValue("candidateSchemaVersion").jsonPrimitive.int == CANDIDATE_SCHEMA_V3)
                        require(record.getValue("boundedInputSchemaVersion").jsonPrimitive.int ==
                            BOUNDED_POLICY_INPUT_SCHEMA_V5)
                        require(record.getValue("observationSchemaVersion").jsonPrimitive.int == POLICY_SCHEMA_V6)
                        require(record.getValue("outerCommit").jsonPrimitive.content == manifest.outerCommit)
                        require(record.getValue("argentumCommit").jsonPrimitive.content == manifest.argentumCommit)
                        require(record.getValue("profileManifestHash").jsonPrimitive.content == manifest.profileHash)
                        require(record.getValue("perspectivePlayerId").jsonPrimitive.content == entry.game.searchSeat)
                        headerSeen = true
                    }
                    "decision" -> {
                        require(headerSeen)
                        val input = PolicyJson.format.decodeFromJsonElement(
                            HistoricalBoundedPolicyInputV5CandidateV3.serializer(),
                            record.getValue("policyInput"),
                        )
                        val chosen = PolicyJson.format.decodeFromJsonElement(
                            HistoricalSemanticChoiceV3.serializer(),
                            record.getValue("chosen"),
                        )
                        val gameId = record.getValue("gameId").jsonPrimitive.content
                        val decisionIndex = record.getValue("decisionIndex").jsonPrimitive.int
                        val actingPlayer = record.getValue("actingPlayerId").jsonPrimitive.content
                        require(gameId == entry.gameId && actingPlayer == entry.game.searchSeat)
                        input.requireValidDigest()
                        require(input.actingPlayerId == actingPlayer && !input.terminated && input.winnerId == null)
                        val label = input.candidates.indexOf(chosen)
                        require(label >= 0) {
                            "Historical teacher action is not an exact current candidate at $gameId:$decisionIndex"
                        }
                        examples += Issue0022HistoricalExample(
                            gameId = gameId,
                            decisionIndex = decisionIndex,
                            input = input,
                            teacherAction = chosen,
                            labelIndex = label,
                        )
                    }
                }
            }
        }
        require(headerSeen && examples.isNotEmpty())
        return examples
    }

    private fun requireRegularFile(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(root.toAbsolutePath().normalize())) {
            "Historical evidence path escapes the repository: $normalized"
        }
        require(Files.isRegularFile(normalized) && !Files.isSymbolicLink(normalized)) {
            "Historical evidence is not a regular non-symlink file: $normalized"
        }
        return normalized
    }
}

@Serializable
internal data class NeuralBcCollisionBreakdown(
    val decisionFamily: String,
    val decisionsWithCandidateFeatureCollisions: Int,
    val labelsSharingFeaturesWithAnotherCurrentCandidate: Int,
)

@Serializable
internal data class NeuralBcCollisionExample(
    val gameId: String,
    val decisionIndex: Int,
    val decisionFamily: String,
    val leftCandidateIndex: Int,
    val rightCandidateIndex: Int,
    val teacherLabelIndex: Int,
    val leftCanonicalPayload: String,
    val rightCanonicalPayload: String,
    val cause: String,
)

@Serializable
internal data class NeuralBcDiagnosticRepresentation(
    val featureSchema: String,
    val diagnostics: NeuralBcRepresentationDiagnostics,
    val byDecisionFamily: List<NeuralBcCollisionBreakdown>,
    val collisionCauses: List<String>,
    val collisionExamples: List<NeuralBcCollisionExample>,
)

@Serializable
internal data class NeuralBcCapacityModelSummary(
    val modelId: String,
    val structure: String,
    val parameterCount: Int,
    val featureSchema: String,
    val trainingConfig: NeuralBcTrainingConfig,
    val seedResults: List<NeuralBcSeedResult>,
    val primaryTest: BcCohortMetrics,
    val allHeldOutCoverage: BcCohortMetrics,
    val byDecisionFamily: List<BcCohortMetrics>,
    val technicalExecution: NeuralBcTechnicalExecution,
    val persistenceRoundTripPassed: Boolean,
)

@Serializable
internal data class Issue0024NeuralCapacityReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0024_PROTOCOL,
    val generatedAtUtc: String,
    val implementationSourceProvenance: PolicySourceProvenance,
    val historicalSourceProvenance: PolicySourceProvenance,
    val historicalCandidateSchemaVersion: Int,
    val currentCandidateSchemaVersion: Int,
    val corpusManifestPath: String,
    val corpusManifestSha256: String,
    val corpusDatasetIdentity: String,
    val splitPath: String,
    val splitSha256: String,
    val splitIdentity: String,
    val admittedGames: Int,
    val attemptedGames: Int,
    val excludedGame: String,
    val exclusionReason: String,
    val admittedDecisions: Int,
    val nontrivialDecisions: Int,
    val nontrivialDecisionsBySplit: Map<String, Int>,
    val originalRepresentation: NeuralBcDiagnosticRepresentation,
    val repairedRepresentation: NeuralBcDiagnosticRepresentation,
    val featureRepairs: List<String>,
    val originalModelBeforeRepairMaximumTrainingAccuracyMean: Double,
    val originalModelBeforeRepairMaximumTrainingAccuracyBySeed: Map<Long, Double>,
    val originalModelBeforeRepairHeldOutNontrivialAccuracy: Double,
    val originalModelBeforeRepairOrdinaryActionAccuracy: Double,
    val originalModelBeforeRepairAttackDeclarationAccuracy: Double,
    val repairedOriginalModel: NeuralBcCapacityModelSummary,
    val strongerModelConfig: NeuralBcInteractionModelConfig,
    val strongerModel: NeuralBcCapacityModelSummary,
    val aliasesMateriallyImprovedOriginalFit: Boolean,
    val diagnosticCase: String,
    val interpretation: List<String>,
    val limitations: List<String>,
)

internal class Issue0024NeuralCapacityDiagnostic(
    private val root: Path,
    private val registry: CardRegistry,
    private val deck: DeckManifest,
    private val currentProfile: FrozenSearchProfile,
    private val baseSeed: Long,
    private val outputDirectory: Path,
    private val originalTrainingConfig: NeuralBcTrainingConfig = NeuralBcTrainingConfig(),
    private val strongerTrainingConfig: NeuralBcTrainingConfig = NeuralBcTrainingConfig(
        maximumEpochs = 180,
        learningRate = 0.005,
    ),
) {
    fun run(
        historicalManifestPath: Path,
        progress: (String) -> Unit = {},
    ): Issue0024NeuralCapacityReport {
        Files.createDirectories(outputDirectory)
        val implementation = requireNotNull(RunProvenance.capture(root).sourceProvenance)
        val population = Issue0022HistoricalCorpusReader(root).read(historicalManifestPath)
        progress("Read ${population.examples.size} exact candidate-V3 decisions from the fixed issue-0022 corpus")

        val originalEncoder = NeuralBehavioralCloningFeatureEncoder(
            projection = NeuralBcFeatureProjection.ISSUE_0022_ORIGINAL,
        )
        val repairedEncoder = NeuralBehavioralCloningFeatureEncoder()
        val originalEncoded = population.examples.map { it.encode(originalEncoder) }
        val repairedEncoded = population.examples.map { it.encode(repairedEncoder) }
        val split = population.split
        fun splitRows(rows: List<EncodedBcDecision>, games: List<String>): List<EncodedBcDecision> =
            rows.filter { it.gameId in games }
        val originalTrain = splitRows(originalEncoded, split.trainGames)
        val train = splitRows(repairedEncoded, split.trainGames)
        val validation = splitRows(repairedEncoded, split.validationGames)
        val test = splitRows(repairedEncoded, split.testGames)
        require((train + validation + test).size == repairedEncoded.size)
        require(train.count(::isDiagnosticNontrivial) == 389)
        require(validation.count(::isDiagnosticNontrivial) == 36)
        require(test.count(::isDiagnosticNontrivial) == 69)

        val originalRepresentation = representation(
            featureSchema = NEURAL_BC_ORIGINAL_FEATURE_SCHEMA,
            rows = originalTrain,
            examples = population.examples.filter { it.gameId in split.trainGames },
            encoder = originalEncoder,
        )
        require(originalRepresentation.diagnostics.decisionsWithCandidateFeatureCollisions == 28)
        require(originalRepresentation.diagnostics.labelsSharingFeaturesWithAnotherCurrentCandidate == 18)
        require(
            originalRepresentation.diagnostics.maximumTrainingAccuracyPermittedByEncodedFeatures ==
                376.0 / 389.0
        )
        val repairedRepresentation = representation(
            featureSchema = NEURAL_BC_FEATURE_SCHEMA,
            rows = train,
            examples = population.examples.filter { it.gameId in split.trainGames },
            encoder = repairedEncoder,
        )
        progress(
            "Candidate collisions ${originalRepresentation.diagnostics.decisionsWithCandidateFeatureCollisions} -> " +
                "${repairedRepresentation.diagnostics.decisionsWithCandidateFeatureCollisions}; " +
                "ceiling ${originalRepresentation.diagnostics.maximumTrainingAccuracyPermittedByEncodedFeatures} -> " +
                repairedRepresentation.diagnostics.maximumTrainingAccuracyPermittedByEncodedFeatures
        )
        repairedRepresentation.collisionExamples.forEach { collision ->
            progress(
                "Remaining collision ${collision.gameId}:${collision.decisionIndex} " +
                    "candidates ${collision.leftCandidateIndex}/${collision.rightCandidateIndex}: " +
                    "${collision.leftCanonicalPayload} versus ${collision.rightCanonicalPayload}"
            )
        }

        val empirical = EmpiricalIntentBaseline(train)
        val modelConfig = NeuralBcModelConfig(
            stateDimension = repairedEncoder.stateDimension,
            candidateDimension = repairedEncoder.candidateDimension,
        )
        val originalModels = linkedMapOf<Long, CandidateConditionedNeuralPolicy>()
        val originalSeeds = originalTrainingConfig.initializationSeeds.map { seed ->
            progress("Training repaired original bilinear seed $seed")
            val trained = NeuralBcTrainer(modelConfig, originalTrainingConfig).train(train, validation, seed)
            val path = outputDirectory.resolve("original-bilinear-seed-$seed.json")
            trained.policy.save(path)
            originalModels[seed] = trained.policy
            seedResult(
                seed = seed,
                bestEpoch = trained.bestEpoch,
                bestValidationLoss = trained.bestValidationLoss,
                selectedCheckpointTrainingLoss = trained.selectedCheckpointTrainingLoss,
                maximumTrainingAccuracy = trained.maximumTrainingAccuracy,
                policy = trained.policy,
                train = train,
                validation = validation,
                test = test,
                path = path,
            )
        }
        val originalRoundTrip = roundTripOriginal(originalModels, originalTrainingConfig, test)
        val repairedOriginalSummary = modelSummary(
            modelId = "issue-0022-bilinear-repaired-features",
            structure = "32-dimensional tanh state and candidate projections with a scaled bilinear dot score",
            parameterCount = modelConfig.parameterCount,
            trainingConfig = originalTrainingConfig,
            seedResults = originalSeeds,
            test = test,
            allTest = test,
            empirical = empirical,
            models = originalModels,
            technical = executeTechnicalSmoke(originalModels.getValue(originalTrainingConfig.initializationSeeds.first()), repairedEncoder),
            roundTrip = originalRoundTrip,
        )

        val strongerConfig = NeuralBcInteractionModelConfig(
            stateDimension = repairedEncoder.stateDimension,
            candidateDimension = repairedEncoder.candidateDimension,
        )
        val strongerModels = linkedMapOf<Long, CandidateConditionedInteractionPolicy>()
        val strongerSeeds = strongerTrainingConfig.initializationSeeds.map { seed ->
            progress("Training nonlinear interaction seed $seed")
            val trained = NeuralBcInteractionTrainer(strongerConfig, strongerTrainingConfig)
                .train(train, validation, seed)
            val path = outputDirectory.resolve("interaction-mlp-seed-$seed.json")
            trained.policy.save(path)
            strongerModels[seed] = trained.policy
            seedResult(
                seed = seed,
                bestEpoch = trained.bestEpoch,
                bestValidationLoss = trained.bestValidationLoss,
                selectedCheckpointTrainingLoss = trained.selectedCheckpointTrainingLoss,
                maximumTrainingAccuracy = trained.maximumTrainingAccuracy,
                policy = trained.policy,
                train = train,
                validation = validation,
                test = test,
                path = path,
            )
        }
        val strongerRoundTrip = roundTripStronger(strongerModels, strongerTrainingConfig, test)
        val strongerSummary = modelSummary(
            modelId = "candidate-conditioned-interaction-mlp-v1",
            structure = "32-dimensional tanh state/candidate projections; concatenate state, candidate, " +
                "and elementwise product; 64-dimensional tanh interaction layer; scalar candidate score",
            parameterCount = strongerConfig.parameterCount,
            trainingConfig = strongerTrainingConfig,
            seedResults = strongerSeeds,
            test = test,
            allTest = test,
            empirical = empirical,
            models = strongerModels,
            technical = executeTechnicalSmoke(strongerModels.getValue(strongerTrainingConfig.initializationSeeds.first()), repairedEncoder),
            roundTrip = strongerRoundTrip,
        )

        val originalMaxMean = originalSeeds.map(NeuralBcSeedResult::maximumTrainingAccuracy).average()
        val strongerMaxMean = strongerSeeds.map(NeuralBcSeedResult::maximumTrainingAccuracy).average()
        val ceiling = repairedRepresentation.diagnostics.maximumTrainingAccuracyPermittedByEncodedFeatures
        val aliasesMaterial = originalMaxMean - ISSUE_0022_ORIGINAL_MAX_TRAIN_MEAN >= 0.02
        val closeToCeiling = strongerMaxMean >= ceiling - 0.03
        val heldOutGain = requireNotNull(strongerSummary.primaryTest.neuralMeanAccuracy) - 0.5072463768115942
        val diagnosticCase = when {
            closeToCeiling && heldOutGain < 0.02 ->
                "STRONGER_MODEL_APPROACHES_REPAIRED_CEILING_WITHOUT_CLEAR_HELD_OUT_IMPROVEMENT"
            closeToCeiling -> "STRONGER_MODEL_APPROACHES_REPAIRED_REPRESENTATION_CEILING"
            else -> "STRONGER_MODEL_STILL_FITS_REPAIRED_REPRESENTATION_POORLY"
        }
        return Issue0024NeuralCapacityReport(
            generatedAtUtc = Instant.now().toString(),
            implementationSourceProvenance = implementation,
            historicalSourceProvenance = population.manifest.sourceProvenance,
            historicalCandidateSchemaVersion = CANDIDATE_SCHEMA_V3,
            currentCandidateSchemaVersion = org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V4,
            corpusManifestPath = root.relativize(population.manifestPath).toString(),
            corpusManifestSha256 = population.manifestSha256,
            corpusDatasetIdentity = population.manifest.datasetIdentity,
            splitPath = root.relativize(population.splitPath).toString(),
            splitSha256 = population.splitSha256,
            splitIdentity = split.splitIdentity,
            admittedGames = 19,
            attemptedGames = 20,
            excludedGame = "teacher-corpus-000016",
            exclusionReason = "teacher expansion exhausted a response or generation limit",
            admittedDecisions = repairedEncoded.size,
            nontrivialDecisions = repairedEncoded.count(::isDiagnosticNontrivial),
            nontrivialDecisionsBySplit = mapOf(
                "train" to train.count(::isDiagnosticNontrivial),
                "validation" to validation.count(::isDiagnosticNontrivial),
                "test" to test.count(::isDiagnosticNontrivial),
            ),
            originalRepresentation = originalRepresentation,
            repairedRepresentation = repairedRepresentation,
            featureRepairs = listOf(
                "Suffixed semantic entity references such as #0 and #1 resolve to the visible descriptor and retain the semantic ordinal as a feature.",
                "Candidate JSON arrays use position-indexed item paths, so A-then-B and B-then-A no longer become the same feature bag.",
                "V3 historical and V4 current semantic descriptors are reconstructed according to their declared candidate schema; no V3 candidate is promoted to V4.",
            ),
            originalModelBeforeRepairMaximumTrainingAccuracyMean = ISSUE_0022_ORIGINAL_MAX_TRAIN_MEAN,
            originalModelBeforeRepairMaximumTrainingAccuracyBySeed = ISSUE_0022_ORIGINAL_MAX_TRAIN_BY_SEED,
            originalModelBeforeRepairHeldOutNontrivialAccuracy = 0.5072463768115942,
            originalModelBeforeRepairOrdinaryActionAccuracy = 0.562962962962963,
            originalModelBeforeRepairAttackDeclarationAccuracy = 0.3541666666666667,
            repairedOriginalModel = repairedOriginalSummary,
            strongerModelConfig = strongerConfig,
            strongerModel = strongerSummary,
            aliasesMateriallyImprovedOriginalFit = aliasesMaterial,
            diagnosticCase = diagnosticCase,
            interpretation = buildList {
                add("Known-alias effect on the unchanged bilinear model is measured by the change in mean maximum training fit from $ISSUE_0022_ORIGINAL_MAX_TRAIN_MEAN to $originalMaxMean.")
                add("The stronger model's mean maximum training fit is $strongerMaxMean against a repaired encoded ceiling of $ceiling.")
                add("Held-out nontrivial accuracy changed from 0.5072463768115942 in issue 0022 to ${strongerSummary.primaryTest.neuralMeanAccuracy}; training fit is the primary discriminator.")
            },
            limitations = listOf(
                "This is exact-label behavioral cloning on the fixed issue-0022 candidate-V3 population, not playing-strength evidence.",
                "The historical reader is intentionally bound to one manifest, split, source pair, schema tuple, and per-trajectory content hashes; it is not a general obsolete-schema admission path.",
                "The stronger model changes both interaction structure and training budget, so it diagnoses readily learnable capacity rather than isolating one architectural coefficient.",
                "The corpus is a small fixed-deck Mono-Red mirror and does not establish general-Magic policy-view sufficiency.",
                "No outcome target, value target, DAgger, Expert Iteration, self-play, or neural-guided search was evaluated.",
            ),
        )
    }

    private fun representation(
        featureSchema: String,
        rows: List<EncodedBcDecision>,
        examples: List<Issue0022HistoricalExample>,
        encoder: NeuralBehavioralCloningFeatureEncoder,
    ): NeuralBcDiagnosticRepresentation {
        val selected = rows.filter(::isDiagnosticNontrivial)
        val exampleByKey = examples.associateBy { it.gameId to it.decisionIndex }
        val diagnostics = neuralBcRepresentationDiagnostics(rows)
        val breakdown = selected.groupBy(EncodedBcDecision::decisionFamily).toSortedMap().mapNotNull { (family, familyRows) ->
            val collisionRows = familyRows.filter(::hasCandidateFeatureCollision)
            val collidingLabels = familyRows.filter(::labelSharesCandidateFeatures)
            if (collisionRows.isEmpty() && collidingLabels.isEmpty()) null else {
                NeuralBcCollisionBreakdown(family, collisionRows.size, collidingLabels.size)
            }
        }
        val collisionExamples = selected.filter(::hasCandidateFeatureCollision).map { row ->
            val example = requireNotNull(exampleByKey[row.gameId to row.decisionIndex])
            collisionExample(row, example, encoder)
        }
        val causes = collisionExamples.groupingBy(NeuralBcCollisionExample::cause).eachCount()
            .toSortedMap().map { (cause, count) -> "$count training decisions: $cause" }
        return NeuralBcDiagnosticRepresentation(
            featureSchema = featureSchema,
            diagnostics = diagnostics,
            byDecisionFamily = breakdown,
            collisionCauses = causes,
            collisionExamples = collisionExamples,
        )
    }

    private fun collisionExample(
        row: EncodedBcDecision,
        example: Issue0022HistoricalExample,
        encoder: NeuralBehavioralCloningFeatureEncoder,
    ): NeuralBcCollisionExample {
        val pair = row.candidates.indices.firstNotNullOf { left ->
            (left + 1 until row.candidateCount).firstOrNull { right ->
                sameSparseFeatureVector(row.candidates[left], row.candidates[right])
            }?.let { right -> left to right }
        }
        val largeEncoder = NeuralBehavioralCloningFeatureEncoder(
            stateDimension = 1_048_583,
            candidateDimension = 1_048_583,
            projection = encoder.projection,
        )
        val large = example.encode(largeEncoder)
        val remainsBeforeHashing = sameSparseFeatureVector(
            large.candidates[pair.first],
            large.candidates[pair.second],
        )
        val cause = when {
            !remainsBeforeHashing -> "512-bucket candidate feature hashing collision"
            encoder.projection == NeuralBcFeatureProjection.ISSUE_0022_ORIGINAL &&
                row.decisionFamily == "MULLIGAN" ->
                "ordered candidate array was encoded as an unordered repeated-item bag"
            encoder.projection == NeuralBcFeatureProjection.ISSUE_0022_ORIGINAL ->
                "suffixed semantic entity assignment was not resolved and its keyed value was discarded"
            row.decisionFamily == "DECLARE_BLOCKERS" ->
                "entity-keyed blocker map retained participating descriptors but lost blocker-to-attacker pairing"
            else -> "distinct semantic candidates remain identical before practical feature hashing"
        }
        return NeuralBcCollisionExample(
            gameId = row.gameId,
            decisionIndex = row.decisionIndex,
            decisionFamily = row.decisionFamily,
            leftCandidateIndex = pair.first,
            rightCandidateIndex = pair.second,
            teacherLabelIndex = row.labelIndex,
            leftCanonicalPayload = PolicyJson.canonical(example.input.candidates[pair.first].canonicalPayload),
            rightCanonicalPayload = PolicyJson.canonical(example.input.candidates[pair.second].canonicalPayload),
            cause = cause,
        )
    }

    private fun seedResult(
        seed: Long,
        bestEpoch: Int,
        bestValidationLoss: Double,
        selectedCheckpointTrainingLoss: Double,
        maximumTrainingAccuracy: Double,
        policy: NeuralBcScoringPolicy,
        train: List<EncodedBcDecision>,
        validation: List<EncodedBcDecision>,
        test: List<EncodedBcDecision>,
        path: Path,
    ): NeuralBcSeedResult = NeuralBcSeedResult(
        seed = seed,
        bestEpoch = bestEpoch,
        bestValidationLoss = bestValidationLoss,
        selectedCheckpointTrainingLoss = selectedCheckpointTrainingLoss,
        maximumTrainingAccuracy = maximumTrainingAccuracy,
        selectedCheckpointTrainingAccuracy = neuralBcAccuracy(policy, train.filter(::isDiagnosticNontrivial)),
        validationAccuracy = neuralBcAccuracy(policy, validation.filter(::isDiagnosticNontrivial)),
        testAccuracy = neuralBcAccuracy(policy, test.filter(::isDiagnosticNontrivial)),
        modelPath = root.relativize(path).toString(),
        modelSha256 = sha256File(path),
    )

    private fun <T : NeuralBcScoringPolicy> modelSummary(
        modelId: String,
        structure: String,
        parameterCount: Int,
        trainingConfig: NeuralBcTrainingConfig,
        seedResults: List<NeuralBcSeedResult>,
        test: List<EncodedBcDecision>,
        allTest: List<EncodedBcDecision>,
        empirical: EmpiricalIntentBaseline,
        models: Map<Long, T>,
        technical: NeuralBcTechnicalExecution,
        roundTrip: Boolean,
    ): NeuralBcCapacityModelSummary {
        val primary = test.filter(::isDiagnosticNontrivial)
        return NeuralBcCapacityModelSummary(
            modelId = modelId,
            structure = structure,
            parameterCount = parameterCount,
            featureSchema = NEURAL_BC_FEATURE_SCHEMA,
            trainingConfig = trainingConfig,
            seedResults = seedResults,
            primaryTest = diagnosticCohort("test-nontrivial-primary", primary, empirical, models),
            allHeldOutCoverage = diagnosticCohort("test-all-technical-coverage", allTest, empirical, models),
            byDecisionFamily = primary.groupBy(EncodedBcDecision::decisionFamily).toSortedMap().map { (family, rows) ->
                diagnosticCohort(family, rows, empirical, models)
            },
            technicalExecution = technical,
            persistenceRoundTripPassed = roundTrip,
        )
    }

    private fun <T : NeuralBcScoringPolicy> diagnosticCohort(
        name: String,
        decisions: List<EncodedBcDecision>,
        empirical: EmpiricalIntentBaseline,
        models: Map<Long, T>,
    ): BcCohortMetrics {
        val uniformCorrect = decisions.sumOf { 1.0 / it.candidateCount }
        val empiricalCorrect = decisions.sumOf(empirical::expectedCorrect)
        val neural = models.mapValues { (_, model) -> neuralBcAccuracy(model, decisions) }
        val reportable = decisions.size >= 10
        fun accuracy(correct: Double): BcAccuracy = BcAccuracy(
            decisions = decisions.size,
            expectedCorrect = correct,
            accuracy = if (reportable) correct / decisions.size else null,
        )
        return BcCohortMetrics(
            cohort = name,
            decisions = decisions.size,
            uniform = accuracy(uniformCorrect),
            stateIgnorantEmpirical = accuracy(empiricalCorrect),
            neuralAccuracyBySeed = if (reportable) neural else emptyMap(),
            neuralMeanAccuracy = neural.values.takeIf { reportable }?.average(),
            neuralMinimumAccuracy = neural.values.takeIf { reportable }?.minOrNull(),
            neuralMaximumAccuracy = neural.values.takeIf { reportable }?.maxOrNull(),
        )
    }

    private fun roundTripOriginal(
        models: Map<Long, CandidateConditionedNeuralPolicy>,
        config: NeuralBcTrainingConfig,
        test: List<EncodedBcDecision>,
    ): Boolean {
        val seed = config.initializationSeeds.first()
        val loaded = CandidateConditionedNeuralPolicy.load(
            outputDirectory.resolve("original-bilinear-seed-$seed.json")
        )
        return models.getValue(seed).scores(test.first(::isDiagnosticNontrivial))
            .contentEquals(loaded.scores(test.first(::isDiagnosticNontrivial)))
    }

    private fun roundTripStronger(
        models: Map<Long, CandidateConditionedInteractionPolicy>,
        config: NeuralBcTrainingConfig,
        test: List<EncodedBcDecision>,
    ): Boolean {
        val seed = config.initializationSeeds.first()
        val loaded = CandidateConditionedInteractionPolicy.load(
            outputDirectory.resolve("interaction-mlp-seed-$seed.json")
        )
        return models.getValue(seed).scores(test.first(::isDiagnosticNontrivial))
            .contentEquals(loaded.scores(test.first(::isDiagnosticNontrivial)))
    }

    private fun executeTechnicalSmoke(
        policy: NeuralBcScoringPolicy,
        encoder: NeuralBehavioralCloningFeatureEncoder,
    ): NeuralBcTechnicalExecution {
        val gameId = "issue-0024-neural-technical-smoke"
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Player 0", deck.deck()),
                    PlayerConfig("Player 1", deck.deck()),
                ),
                skipMulligans = false,
                useHandSmoother = false,
                startingPlayerIndex = 0,
                seed = baseSeed xor 0x4930303234L,
            )
        )
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = gameId,
            seedBase = baseSeed xor 0x4e455552414cL,
            effectiveSetupSeed = baseSeed xor 0x4930303234L,
            expander = UnifiedSemanticExpander(actionSpaceProfile = currentProfile.actionSpaceProfile),
            cardRegistry = registry,
            knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck),
        )
        val candidateCounts = mutableListOf<Int>()
        val decisionFamilies = mutableListOf<String>()
        val selectedFamilies = mutableListOf<SemanticOperationFamily>()
        var accepted = 0
        var currentMembership = true
        var allAccepted = true
        repeat(12) {
            val actor = world.actorToAct() ?: return@repeat
            val information = world.informationState(actor)
            if (information.terminated) return@repeat
            val input = org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler.compile(information)
            val encoded = encoder.encodeForInference(input)
            val selectedIndex = policy.selectIndex(encoded)
            val selected = input.candidates[selectedIndex]
            candidateCounts += input.candidates.size
            decisionFamilies += encoded.decisionFamily
            selectedFamilies += selected.operationFamily
            currentMembership = currentMembership && selected in input.candidates
            val result = world.step(selected)
            allAccepted = allAccepted && result.accepted
            if (!result.accepted) return@repeat
            accepted++
        }
        val passed = accepted == candidateCounts.size && accepted >= 8 && currentMembership && allAccepted
        require(passed)
        return NeuralBcTechnicalExecution(
            gameId = gameId,
            attemptedDecisions = candidateCounts.size,
            acceptedDecisions = accepted,
            candidateCounts = candidateCounts,
            decisionFamilies = decisionFamilies,
            selectedFamilies = selectedFamilies,
            allSelectionsWereCurrentCandidates = currentMembership,
            allSemanticStepsAccepted = allAccepted,
            usedOnlyBoundedInputsForScoring = true,
            passed = passed,
        )
    }

    private fun isDiagnosticNontrivial(decision: EncodedBcDecision): Boolean =
        decision.candidateCount >= PRIMARY_MIN_CANDIDATES

    private fun labelSharesCandidateFeatures(decision: EncodedBcDecision): Boolean =
        decision.candidates.indices.any { index ->
            index != decision.labelIndex &&
                sameSparseFeatureVector(decision.candidates[index], decision.candidates[decision.labelIndex])
        }
}

internal fun renderIssue0024NeuralCapacityDiagnostic(report: Issue0024NeuralCapacityReport): String = buildString {
    fun pct(value: Double?): String = value?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"
    fun modelSection(title: String, model: NeuralBcCapacityModelSummary) {
        appendLine("## $title")
        appendLine()
        appendLine("${model.structure}. Parameters: ${model.parameterCount}. Feature schema: `${model.featureSchema}`.")
        appendLine("Training: Adam ${model.trainingConfig.learningRate}, ${model.trainingConfig.maximumEpochs} epochs, " +
            "validation-loss checkpoint selection, seeds ${model.trainingConfig.initializationSeeds}.")
        appendLine()
        appendLine("| Seed | Best epoch | Maximum train | Selected train | Validation | Test |")
        appendLine("| ---: | ---: | ---: | ---: | ---: | ---: |")
        model.seedResults.forEach { seed ->
            appendLine("| ${seed.seed} | ${seed.bestEpoch} | ${pct(seed.maximumTrainingAccuracy)} | " +
                "${pct(seed.selectedCheckpointTrainingAccuracy)} | ${pct(seed.validationAccuracy)} | " +
                "${pct(seed.testAccuracy)} |")
        }
        appendLine()
        appendLine("| Held-out population | n | Uniform | Empirical | Neural mean | Neural range |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
        val primary = model.primaryTest
        appendLine("| Nontrivial | ${primary.decisions} | ${pct(primary.uniform.accuracy)} | " +
            "${pct(primary.stateIgnorantEmpirical.accuracy)} | ${pct(primary.neuralMeanAccuracy)} | " +
            "${pct(primary.neuralMinimumAccuracy)}–${pct(primary.neuralMaximumAccuracy)} |")
        appendLine()
        appendLine("| Decision family | n | Uniform | Empirical | Neural mean |")
        appendLine("| --- | ---: | ---: | ---: | ---: |")
        model.byDecisionFamily.forEach { metric ->
            appendLine("| ${metric.cohort} | ${metric.decisions} | ${pct(metric.uniform.accuracy)} | " +
                "${pct(metric.stateIgnorantEmpirical.accuracy)} | ${pct(metric.neuralMeanAccuracy)} |")
        }
        appendLine()
        appendLine("Loaded-model score round trip: ${model.persistenceRoundTripPassed}; current-V4 semantic execution: " +
            "${model.technicalExecution.acceptedDecisions}/${model.technicalExecution.attemptedDecisions} accepted.")
        appendLine()
    }

    appendLine("# Neural candidate-capacity diagnostic on the fixed issue-0022 corpus")
    appendLine()
    appendLine("## Conclusion")
    appendLine()
    appendLine("`${report.diagnosticCase}`")
    appendLine()
    appendLine("This is a behavioral-cloning capacity/representation diagnostic, not playing-strength evidence.")
    appendLine()
    appendLine("## Fixed historical population")
    appendLine()
    appendLine("- Candidate-V${report.historicalCandidateSchemaVersion} corpus: " +
        "`${report.corpusDatasetIdentity}` (${report.admittedGames}/${report.attemptedGames} games)")
    appendLine("- Manifest: `${report.corpusManifestPath}` (`${report.corpusManifestSha256}`)")
    appendLine("- Whole-game split: `${report.splitIdentity}`; nontrivial train/validation/test = " +
        "${report.nontrivialDecisionsBySplit.getValue("train")}/" +
        "${report.nontrivialDecisionsBySplit.getValue("validation")}/" +
        "${report.nontrivialDecisionsBySplit.getValue("test")}")
    appendLine("- Excluded unchanged: `${report.excludedGame}` — ${report.exclusionReason}")
    appendLine("- Reader boundary: exact V3 source/hash/schema contract; current candidate-V${report.currentCandidateSchemaVersion} admission remains strict")
    appendLine()
    appendLine("## Feature repair and representational ceiling")
    appendLine()
    report.featureRepairs.forEach { appendLine("- $it") }
    appendLine()
    appendLine("| Projection | Collision decisions | Colliding labels | Fit ceiling |")
    appendLine("| --- | ---: | ---: | ---: |")
    appendLine("| Original | ${report.originalRepresentation.diagnostics.decisionsWithCandidateFeatureCollisions} | " +
        "${report.originalRepresentation.diagnostics.labelsSharingFeaturesWithAnotherCurrentCandidate} | " +
        "${pct(report.originalRepresentation.diagnostics.maximumTrainingAccuracyPermittedByEncodedFeatures)} |")
    appendLine("| Repaired | ${report.repairedRepresentation.diagnostics.decisionsWithCandidateFeatureCollisions} | " +
        "${report.repairedRepresentation.diagnostics.labelsSharingFeaturesWithAnotherCurrentCandidate} | " +
        "${pct(report.repairedRepresentation.diagnostics.maximumTrainingAccuracyPermittedByEncodedFeatures)} |")
    appendLine()
    if (report.repairedRepresentation.byDecisionFamily.isNotEmpty()) {
        val breakdown = report.repairedRepresentation.byDecisionFamily.joinToString("; ") { family ->
            "${family.decisionFamily}: ${family.decisionsWithCandidateFeatureCollisions} roots, " +
                "${family.labelsSharingFeaturesWithAnotherCurrentCandidate} labels"
        }
        appendLine("Remaining collision breakdown: $breakdown.")
        appendLine("Causes: ${report.repairedRepresentation.collisionCauses.joinToString("; ")}.")
        report.repairedRepresentation.collisionExamples.forEach { collision ->
            appendLine("- `${collision.gameId}:${collision.decisionIndex}` candidates " +
                "${collision.leftCandidateIndex}/${collision.rightCandidateIndex}, teacher=" +
                "${collision.teacherLabelIndex}: ${collision.cause}")
        }
        appendLine()
    } else {
        appendLine("No repaired training candidate-feature collisions remain.")
        appendLine()
    }
    appendLine("### Original-model before/after control")
    appendLine()
    appendLine("| Seed | Original maximum train | Repaired maximum train | Change |")
    appendLine("| ---: | ---: | ---: | ---: |")
    report.repairedOriginalModel.seedResults.forEach { seed ->
        val before = report.originalModelBeforeRepairMaximumTrainingAccuracyBySeed.getValue(seed.seed)
        appendLine("| ${seed.seed} | ${pct(before)} | ${pct(seed.maximumTrainingAccuracy)} | " +
            "${pct(seed.maximumTrainingAccuracy - before)} |")
    }
    appendLine()
    val repairedPrimary = requireNotNull(report.repairedOriginalModel.primaryTest.neuralMeanAccuracy)
    val repairedOrdinary = requireNotNull(report.repairedOriginalModel.byDecisionFamily
        .single { it.cohort == "ORDINARY_ACTION" }.neuralMeanAccuracy)
    val repairedAttacks = requireNotNull(report.repairedOriginalModel.byDecisionFamily
        .single { it.cohort == "DECLARE_ATTACKERS" }.neuralMeanAccuracy)
    appendLine("Held-out nontrivial changed from " +
        "${pct(report.originalModelBeforeRepairHeldOutNontrivialAccuracy)} to ${pct(repairedPrimary)}; " +
        "ordinary actions from ${pct(report.originalModelBeforeRepairOrdinaryActionAccuracy)} to " +
        "${pct(repairedOrdinary)}; attack declarations from " +
        "${pct(report.originalModelBeforeRepairAttackDeclarationAccuracy)} to ${pct(repairedAttacks)}.")
    appendLine()
    modelSection("Controlled original bilinear rerun", report.repairedOriginalModel)
    modelSection("Stronger nonlinear interaction diagnostic", report.strongerModel)
    appendLine("## Interpretation and limits")
    appendLine()
    report.interpretation.forEach { appendLine("- $it") }
    appendLine("- Known aliases cleared the runner's two-point mean-max diagnostic threshold: " +
        report.aliasesMateriallyImprovedOriginalFit)
    report.limitations.forEach { appendLine("- $it") }
}
