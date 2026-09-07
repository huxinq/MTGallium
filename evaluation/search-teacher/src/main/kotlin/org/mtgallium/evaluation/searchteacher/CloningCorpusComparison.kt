package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

internal const val CLONING_CORPUS_COMPARISON_PROTOCOL = "cloning-corpus-comparison-v1"

@Serializable
internal data class CloningComparisonInput(val directory: String, val researchRunIdentity: String) {
    init { require(Path.of(directory).isAbsolute && researchRunIdentity.isNotBlank()) }
}

@Serializable
internal data class CloningCorpusComparisonPlan(
    val originalCorpus: CloningComparisonInput,
    val baselineFit: CloningComparisonInput,
    val replayCorpus: CloningComparisonInput,
    val training: NeuralBcTrainingConfig,
    val seed: Long,
)

@Serializable
internal data class CloningCorpusComparisonReport(
    val researchRunIdentity: String,
    val generatedAtUtc: String,
    val source: ResearchRunProvenance,
    val plan: CloningCorpusComparisonPlan,
    val preflight: Boolean,
    val fitMillis: Double,
    val bestEpoch: Int,
    val maximumTrainingAccuracy: Double,
    val bestValidationSelectionLoss: Double,
    val selectedCheckpointTrainingSelectionLoss: Double,
    val epochTrace: List<NeuralBcInteractionEpochMetrics>,
    val groupPartitions: Map<String, String>,
    val excludedSingletons: Int,
    val baseline: Map<String, CloningGeneralizationMetrics>,
    val largerCorpus: Map<String, CloningGeneralizationMetrics>,
)

/** The only intervention is added p1 TRAIN data; selection still uses the original p0 validation. */
internal fun runCloningCorpusComparison(
    repository: Path,
    plan: CloningCorpusComparisonPlan,
    output: Path,
    preflight: Boolean = false,
): CloningCorpusComparisonReport {
    val source = ResearchRunProvenance.capture(repository, "third_party/argentum-engine").also { it.requireReady() }
    require(!source.outerDirty && !source.engineDirty) { "Commit comparison source before fitting" }
    val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "cloning corpus comparison")
    require(!Files.exists(destination))
    fun verified(ref: CloningComparisonInput): Map<String, Path> {
        val root = Path.of(ref.directory)
        return ResearchRunArtifacts.loadAndVerify(root, ref.researchRunIdentity).artifacts.associate {
            it.relativePath to ResearchRunFiles.resolveBelow(root, it.relativePath).also { path ->
                require(researchSha256File(path) == it.sha256)
            }
        }
    }
    val original = verified(plan.originalCorpus)
    val baselineFiles = verified(plan.baselineFit)
    val replayFiles = verified(plan.replayCorpus)
    fun text(files: Map<String, Path>, name: String) = Files.readString(files.getValue(name))
    fun lines(path: Path): List<String> = GZIPInputStream(Files.newInputStream(path)).bufferedReader().use { it.readLines() }
    val manifest = evidenceJson.decodeFromString<CorpusManifest>(text(original, "corpus-manifest.json"))
    val lineage = evidenceJson.decodeFromString<CalibrationCloningLineage>(text(original, "lineage.json"))
    require(manifest.passed && lineage.datasetIdentity == manifest.datasetIdentity)
    val oldExamples = lines(original.getValue("examples.jsonl.gz")).map { evidenceJson.decodeFromString<BehavioralCloningExample>(it) }
    val originalGames = manifest.entries.map { it.gameId }.toSet()
    require(oldExamples.isNotEmpty() && oldExamples.map { it.gameId }.toSet() == originalGames)
    require(oldExamples.all { it.evidence.datasetIdentity == manifest.datasetIdentity &&
        it.evidence.policyEvidenceIdentity == lineage.teacherPolicyEvidenceIdentity &&
        it.evidence.sourceProvenance == manifest.sourceProvenance && it.actingPlayerId == "p0" })
    val oldByKey = oldExamples.associateBy { it.gameId to it.decisionIndex }
    require(oldByKey.size == oldExamples.size)
    val split = evidenceJson.decodeFromString<Map<String, String>>(text(baselineFiles, "split.json"))
    require(split.keys == originalGames)
    val originalPlan = evidenceJson.parseToJsonElement(text(original, "retained-plan.json")).jsonObject
    val sourceBaseSeed = originalPlan.getValue("baseSeed").jsonPrimitive.long
    val seedByGroup = originalGames.map { lineage.wholePairGroupByGame.getValue(it) }.distinct().associateWith { group ->
        require(group.startsWith("${lineage.parentResearchRunIdentity}:pair-"))
        calibrationPairSeed(sourceBaseSeed, group.substringAfterLast(":pair-").toInt())
    }
    val partitionByGroup = cloningGroupPartitions(split, lineage.wholePairGroupByGame, seedByGroup)
    val baselineBindings = evidenceJson.decodeFromString<ResearchRunBindings>(text(baselineFiles, "bindings.json"))
    require(baselineBindings.identity == plan.baselineFit.researchRunIdentity &&
        baselineBindings.protocol == "calibration-reference-cloning-fit-v1")
    require(baselineBindings.material["parent"] == plan.originalCorpus.researchRunIdentity &&
        baselineBindings.material["parent-manifest"] == researchSha256File(Path.of(plan.originalCorpus.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)))
    require(baselineBindings.material["split"] == sha256(JsonObject(split.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()))
    require(baselineBindings.material["examples"] == researchSha256File(original.getValue("examples.jsonl.gz")))
    require(baselineBindings.material["training-config"] == sha256(evidenceJson.encodeToString(plan.training)))
    require(baselineBindings.material["seed"] == plan.seed.toString() && plan.training.initializationSeeds == listOf(plan.seed))
    val baselineArtifact = evidenceJson.decodeFromString<NeuralBcInteractionModelArtifact>(text(baselineFiles, "model.json"))
    require(baselineArtifact.trainingSeed == plan.seed &&
        baselineBindings.material["model-config"] == sha256(evidenceJson.encodeToString(baselineArtifact.config)))
    // Reuse the established artifact/shape/finite-weight admission used by live root inference.
    CloningRootRolloutPolicy.fromVerifiedFit(Path.of(plan.baselineFit.directory), plan.baselineFit.researchRunIdentity)
    val baseline = CandidateConditionedInteractionPolicy.fromArtifact(baselineArtifact)
    val replayReport = evidenceJson.decodeFromString<ReplayReferenceCloningReport>(text(replayFiles, "report.json"))
    val replayBindings = evidenceJson.decodeFromString<ResearchRunBindings>(text(replayFiles, "bindings.json"))
    require(replayBindings.identity == plan.replayCorpus.researchRunIdentity && replayBindings.protocol == REPLAY_REFERENCE_CLONING_PROTOCOL)
    require(replayReport.researchRunIdentity == plan.replayCorpus.researchRunIdentity && replayReport.protocol == REPLAY_REFERENCE_CLONING_PROTOCOL)
    require(replayReport.parentRunIdentity == lineage.parentResearchRunIdentity &&
        replayReport.parentManifestSha256 == lineage.parentManifestSha256 && replayReport.historicalSource == manifest.sourceProvenance)
    require(replayReport.teacher.binding.identity == lineage.teacherPolicyEvidenceIdentity)
    require(replayBindings == replayReferenceCloningBindings(replayReport.projectionProvenance,
        replayReport.parentRunIdentity, replayReport.parentManifestSha256, replayReport.teacher.binding.identity,
        replayReport.selectedPairIndices, replayReport.teacher.descriptor.parameters(sourceBaseSeed).actionSpaceProfile))

    require(replayReport.games.map { it.pairGroup }.toSet() == partitionByGroup.keys)
    val replayGames = replayReport.games.associateBy { it.gameId }
    require(replayGames.size == replayReport.games.size)
    val projected = lines(replayFiles.getValue("examples.jsonl.gz")).map { evidenceJson.decodeFromString<ReplayReferenceCloningExample>(it) }
    require(projected.all { it.actor in setOf("p0", "p1") })
    require(projected.size == replayReport.exampleCount && projected.map { it.gameId to it.decisionIndex }.distinct().size == projected.size)
    projected.forEach { row ->
        val game = replayGames.getValue(row.gameId)
        require(!game.excludedForCurrentGenerationLimit && row.actor == game.teacherSeat && row.pairGroup == game.pairGroup)
    }
    replayGames.values.forEach { game -> require(projected.count { it.gameId == game.gameId } == game.retainedExamples) }
    val encoder = NeuralBehavioralCloningFeatureEncoder(baselineArtifact.config.stateDimension, baselineArtifact.config.candidateDimension)
    projected.filter { it.actor == "p0" }.forEach { row ->
        val old = oldByKey.getValue(row.gameId to row.decisionIndex)
        require(old.teacherAction == row.teacherAction)
        requireReplayCloningEncodingParity(encoder.encode(old), encoder.encode(row.policyInput,
            row.policyInput.candidates.indexOf(row.teacherAction), row.gameId, row.decisionIndex))
    }
    require(projected.filter { it.actor == "p0" }.map { it.gameId to it.decisionIndex }.toSet() == oldByKey.keys)
    val groupByGame = lineage.wholePairGroupByGame.filterKeys { it in originalGames }.toMutableMap()
    val additions = projected.filter { it.actor == "p1" }.map { row ->
        require(row.gameId !in originalGames)
        val previous = groupByGame.put(row.gameId, row.pairGroup)
        require(previous == null || previous == row.pairGroup)
        encoder.encode(row.policyInput, row.policyInput.candidates.indexOf(row.teacherAction), row.gameId, row.decisionIndex)
    }
    val originals = oldExamples.map(encoder::encode)
    val all = originals + additions
    require(all.map { it.gameId to it.decisionIndex }.distinct().size == all.size)
    fun partition(row: EncodedBcDecision) = partitionByGroup.getValue(groupByGame.getValue(row.gameId))
    val originalTrain = originals.filter { it.candidateCount >= 2 && partition(it) == "TRAIN" }
    val selectionValidation = originals.filter { it.candidateCount >= 2 && partition(it) == "VALIDATION" }
    val addedTrain = additions.filter { it.candidateCount >= 2 && partition(it) == "TRAIN" }
    val addedValidation = additions.filter { it.candidateCount >= 2 && partition(it) == "VALIDATION" }
    val populations = linkedMapOf("originalTrain" to originalTrain, "addedTrain" to addedTrain,
        "combinedTrain" to originalTrain + addedTrain, "selectionValidation" to selectionValidation,
        "addedValidation" to addedValidation, "combinedValidation" to selectionValidation + addedValidation)
    require(populations.values.all { it.isNotEmpty() })
    val training = if (preflight) plan.training.copy(maximumEpochs = 2) else plan.training
    val material = sortedMapOf("source" to sha256(evidenceJson.encodeToString(source)),
        "plan" to sha256(evidenceJson.encodeToString(plan)), "model-config" to sha256(evidenceJson.encodeToString(baselineArtifact.config)),
        "training-config" to sha256(evidenceJson.encodeToString(training)), "seed" to plan.seed.toString(),
        "selection-rule" to "original-p0-validation-loss-improvement-over-1e-7-v1")
    listOf("original" to plan.originalCorpus, "baseline" to plan.baselineFit, "replay" to plan.replayCorpus).forEach { (name, ref) ->
        material["$name-run"] = ref.researchRunIdentity
        material["$name-manifest"] = researchSha256File(Path.of(ref.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE))
    }
    val bindings = ResearchRunBindings(protocol = if (preflight) "$CLONING_CORPUS_COMPARISON_PROTOCOL-preflight" else CLONING_CORPUS_COMPARISON_PROTOCOL,
        material = material)
    // Evaluate the frozen baseline before fitting so malformed inputs fail before substantial compute.
    fun evaluate(policy: NeuralBcScoringPolicy): Map<String, CloningGeneralizationMetrics> {
        val scored = evaluateCloningGeneralization(policy, all.filter { it.candidateCount >= 2 }, groupByGame).rows
            .associateBy { it.gameId to it.decisionIndex }
        return populations.mapValues { (_, rows) -> summarizeCloningGeneralization(rows.map { scored.getValue(it.gameId to it.decisionIndex) }) }
    }
    val baselineMetrics = evaluate(baseline)
    val started = System.nanoTime()
    val trained = NeuralBcInteractionTrainer(baselineArtifact.config, training).train(originalTrain + addedTrain, selectionValidation, plan.seed)
    val fitMillis = (System.nanoTime() - started) / 1_000_000.0
    val largerMetrics = evaluate(trained.policy)
    val serialized = evidenceJson.encodeToString(trained.policy.artifact)
    val reloaded = CandidateConditionedInteractionPolicy.fromArtifact(evidenceJson.decodeFromString(serialized))
    all.forEach { row ->
        val before = trained.policy.scores(row)
        val after = reloaded.scores(row)
        require(before.size == row.candidateCount && before.all(Double::isFinite) && before.contentEquals(after))
    }
    val result = CloningCorpusComparisonReport(bindings.identity, Instant.now().toString(), source, plan, preflight,
        fitMillis, trained.bestEpoch, trained.maximumTrainingAccuracy, trained.bestValidationLoss,
        trained.selectedCheckpointTrainingLoss, trained.epochTrace, partitionByGroup, all.count { it.candidateCount < 2 },
        baselineMetrics, largerMetrics)
    Files.createDirectories(destination)
    ResearchRunFiles.atomicWrite(destination.resolve("model.json"), serialized)
    ResearchRunFiles.atomicWrite(destination.resolve("plan.json"), evidenceJson.encodeToString(plan))
    ResearchRunFiles.atomicWrite(destination.resolve("bindings.json"), evidenceJson.encodeToString(bindings))
    ResearchRunFiles.atomicWrite(destination.resolve("report.json"), evidenceJson.encodeToString(result))
    ResearchRunArtifacts(destination, bindings.identity).also { artifacts ->
        listOf("model.json", "plan.json", "bindings.json", "report.json").forEach(artifacts::register)
        artifacts.finalize()
    }
    return result
}
