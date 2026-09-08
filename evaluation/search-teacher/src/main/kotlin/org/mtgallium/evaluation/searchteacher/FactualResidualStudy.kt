package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.mtgallium.agent.searchteacher.*
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal data class FactualResidualCorpusEntry(
    val allocation: FactualResidualGameAllocation,
    val trajectory: FactualResidualInput?,
    val disposition: FactualIncumbentTrajectoryDisposition?,
    val rows: Int,
    val failure: String?,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reusedFromStudyIdentity: String? = null,
) {
    init {
        require(reusedFromStudyIdentity == null || (disposition == FactualIncumbentTrajectoryDisposition.ADMITTED && trajectory != null))
        require(rows >= 0)
        if (disposition == FactualIncumbentTrajectoryDisposition.ADMITTED) {
            require(trajectory != null && rows == allocation.semanticDecisions && failure == null)
        } else require(rows == 0 && failure != null)
    }
}

@Serializable
internal data class FactualResidualCorpusReport(
    val bindings: ResearchRunBindings,
    val allocation: FactualResidualInput,
    val entries: List<FactualResidualCorpusEntry>,
) {
    init { requireFactualResidualAllocation(entries.map { it.allocation }) }
    val complete: Boolean get() = entries.all { it.disposition == FactualIncumbentTrajectoryDisposition.ADMITTED }
}

@Serializable
internal data class FactualResidualTrainingReport(
    val bindings: ResearchRunBindings,
    val corpus: FactualResidualInput,
    val allocation: FactualResidualInput,
    val environmentBindings: ResearchRunBindings,
    val checkpointIdentity: FactualOutcomeResidualCheckpointIdentity,
    val fittingRows: Int,
    val vocabulary: Int,
    val iterations: Int,
    val maxGradientResidual: Double,
)

@Serializable
internal data class FactualResidualPredictionReadout(
    val seedGroups: Int,
    val games: Int,
    val frames: Int,
    val anchorMeanSquaredError: Double,
    val residualMeanSquaredError: Double,
    val weightedClippingFraction: Double,
    val interpretation: String = "Descriptive SCREEN fit-holdout prediction on all factual frames, equally weighted by seed group, leg and frame; not a selection gate or deployed strength result.",
)

@Serializable
internal enum class FactualResidualStudyDisposition { ELIGIBLE_FOR_FACTUAL_TARGETS, READOUT_GATE_FAILED, STUDY_REFUSED }

@Serializable
internal data class FactualResidualStudyReport(
    val bindings: ResearchRunBindings,
    val plan: FactualResidualStudyPlan,
    val producer: ResearchRunProvenance,
    val runtime: Map<String, String>,
    val disposition: FactualResidualStudyDisposition,
    val elapsedSeconds: Double,
    val allocation: FactualResidualInput?,
    val corpus: FactualResidualInput?,
    val training: FactualResidualInput?,
    val predictions: FactualResidualPredictionReadout?,
    val searches: List<FactualResidualSearchRow>,
    val gate: FactualResidualReadoutGate?,
    val failure: String?,
    val interpretation: String = "One factual incumbent outcome residual fit and fresh matched root decisions. Eligibility permits only the separate factual terminal-target gate; it does not establish policy improvement or recursive learnability.",
) {
    init {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0)
        if (disposition == FactualResidualStudyDisposition.STUDY_REFUSED) require(failure != null)
        else {
            require(failure == null && allocation != null && corpus != null && training != null && predictions != null && gate != null)
            require(searches.size == 128 && (disposition == FactualResidualStudyDisposition.ELIGIBLE_FOR_FACTUAL_TARGETS) == gate.eligibleForFactualTargets)
        }
    }
}

internal fun factualResidualStudyBindings(plan: FactualResidualStudyPlan, producer: ResearchRunProvenance,
    runtime: Map<String, String>, deck: DeckManifest) = ResearchRunBindings(protocol = "factual-residual-study-v1", material = mapOf(
        "plan" to sha256(evidenceJson.encodeToString(plan)), "producer" to sha256(evidenceJson.encodeToString(producer)),
        "runtime" to preflightRuntimeHash(runtime), "deck" to deck.deckHash(), "card-pool" to deck.cardPoolHash(),
        "target" to FACTUAL_OUTCOME_RESIDUAL_TARGET, "objective" to FACTUAL_OUTCOME_RESIDUAL_OBJECTIVE,
    ))

internal class FactualResidualStudy(private val repository: Path, private val registry: CardRegistry,
    private val manifest: DeckManifest) {
    fun run(plan: FactualResidualStudyPlan, output: Path): FactualResidualStudyReport {
        val deadline = FactualResidualDeadline(plan.maximumSeconds)
        val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "factual residual study")
        require(!Files.exists(destination)) { "Study output already exists" }
        val producer = ResearchRunProvenance.capture(repository)
        val runtime = researchPreflightRuntime()
        producer.requireReady()
        verifyResearchBuild(plan.build, producer, runtime)
        require(producer.checkedOutEngineCommit == FACTUAL_INCUMBENT_ARGENTUM_REVISION)
        val bindings = factualResidualStudyBindings(plan, producer, runtime, manifest)
        val inputs = loadFactualResidualInputs(plan, manifest)
        val inputPaths = inputs.sources.values.map { Path.of(it.directory) } + listOf(Path.of(plan.inventory.directory), Path.of(plan.build.directory)) +
            listOfNotNull(plan.admissionParent?.let { Path.of(it.directory) })
        require(inputPaths.none { destination.startsWith(it) || it.startsWith(destination) })
        Files.createDirectories(destination)
        writeJsonAtomically(destination.resolve("plan.json"), plan)
        writeJsonAtomically(destination.resolve("bindings.json"), bindings)
        var allocationRef: FactualResidualInput? = null
        var corpusRef: FactualResidualInput? = null
        var trainingRef: FactualResidualInput? = null
        var predictions: FactualResidualPredictionReadout? = null
        var searches = emptyList<FactualResidualSearchRow>()
        var gate: FactualResidualReadoutGate? = null
        var failure: String? = null
        try {
            deadline.requireRemaining()
            val allocation = allocateFactualResidualStudy(plan, inputs, bindings.identity)
            val continuation = plan.admissionParent?.let {
                loadFactualResidualContinuation(it, plan, allocation, manifest, inputs)
            }
            deadline.requireRemaining()
            val allocationPath = destination.resolve("allocation")
            writeJsonAtomically(allocationPath.resolve("report.json"), allocation)
            finalizeResearchWorkflowArtifacts(allocationPath, allocation.bindings.identity)
            allocationRef = factualResidualReference(allocationPath, allocation.bindings.identity)
            verifyFactualResidualInput(allocationRef)
            require(readEvidenceJson(allocationPath.resolve("report.json"), FactualResidualAllocation.serializer()) == allocation)
            // Allocation is immutable before any new trajectory labels are produced or fitting begins.
            val corpusPath = destination.resolve("corpus")
            val entries = parallelMapOrdered(allocation.games.size, plan.effectiveAdmissionWorkers) { index ->
                val game = allocation.games[index]
                continuation?.entries?.get(index)?.let { return@parallelMapOrdered it }
                try {
                    deadline.requireRemaining()
                    val parent = inputs.parents.getValue(game.sourceRunIdentity)
                    val source = inputs.sources.getValue(game.sourceRunIdentity)
                    val request = FactualIncumbentTrajectoryRequest(source.directory, source.identity, source.manifestSha256,
                        parent.sourceProvenance, parent.plan, game.gameId, game.viewer, game.seedGroupId, plan.build)
                    val childPath = corpusPath.resolve("trajectories/game-$index")
                    val admitted = FactualIncumbentTrajectoryAdmission(repository, registry, manifest).admit(request, childPath)
                    require(admitted.source == null || admitted.source.semanticDecisions == game.semanticDecisions)
                    FactualResidualCorpusEntry(game, factualResidualReference(childPath, admitted.bindings.identity),
                        admitted.disposition, admitted.rows.size, admitted.refusal)
                } catch (error: Exception) {
                    FactualResidualCorpusEntry(game, null, null, 0, "${error.javaClass.simpleName}: ${error.message}")
                }
            }
            val corpusBindings = ResearchRunBindings(protocol = "factual-residual-corpus-v1", material = mapOf(
                "study" to bindings.identity, "allocation" to allocationRef.identity,
                "allocation-manifest" to allocationRef.manifestSha256, "entries" to sha256(evidenceJson.encodeToString(entries))))
            val corpus = FactualResidualCorpusReport(corpusBindings, allocationRef, entries)
            writeJsonAtomically(corpusPath.resolve("report.json"), corpus)
            finalizeResearchWorkflowArtifacts(corpusPath, corpusBindings.identity)
            corpusRef = factualResidualReference(corpusPath, corpusBindings.identity)
            require(corpus.complete) { "The preallocated factual corpus is incomplete; no filtered fit is permitted" }
            deadline.requireRemaining()
            val trainingPath = destination.resolve("training")
            trainingRef = trainFactualResidual(plan, allocation, allocationRef, corpus, corpusRef, producer, trainingPath, deadline)
            val learned = loadFactualResidualTraining(trainingRef)
            // No screen prediction or new root search occurs until the sole fitted checkpoint is sealed and reloaded.
            predictions = factualResidualPredictions(corpus, learned, deadline)
            searches = readoutFactualResidualRoots(plan, allocation, inputs,
                entries.associate { (it.allocation.sourceRunIdentity to it.allocation.gameId) to requireNotNull(it.trajectory) },
                registry, manifest, learned, destination.resolve("readout"), deadline)
            gate = factualResidualReadoutGate(allocation, searches)
            deadline.requireRemaining()
        } catch (error: Exception) {
            failure = "${error.javaClass.simpleName}: ${error.message}"
        }
        val disposition = if (failure != null) FactualResidualStudyDisposition.STUDY_REFUSED
            else if (gate!!.eligibleForFactualTargets) FactualResidualStudyDisposition.ELIGIBLE_FOR_FACTUAL_TARGETS
            else FactualResidualStudyDisposition.READOUT_GATE_FAILED
        val report = FactualResidualStudyReport(bindings, plan, producer, runtime, disposition, deadline.elapsedSeconds,
            allocationRef, corpusRef, trainingRef, predictions, searches, gate, failure)
        writeJsonAtomically(destination.resolve("report.json"), report)
        finalizeResearchWorkflowArtifacts(destination, bindings.identity)
        return report
    }
}

private fun trainFactualResidual(plan: FactualResidualStudyPlan, allocation: FactualResidualAllocation,
    allocationRef: FactualResidualInput, corpus: FactualResidualCorpusReport, corpusRef: FactualResidualInput,
    producer: ResearchRunProvenance, output: Path, deadline: FactualResidualDeadline): FactualResidualInput {
    verifyFactualResidualInput(allocationRef)
    verifyFactualResidualInput(corpusRef)
    require(corpus.complete && corpus.entries.map { it.allocation } == allocation.games)
    val rows = corpus.entries.filter { it.allocation.role == FactualResidualDataRole.TRAIN }.flatMap { entry ->
        deadline.requireRemaining()
        val trajectory = loadAllocatedFactualTrajectory(entry)
        entry.allocation.fittingFrames.map { index ->
            val row = trajectory.rows[index]
            FactualOutcomeResidualFitRow(LearnedOutcomeValueFeatureCompiler.compile(row.information, row.viewer),
                row.actualTerminalPayoff - row.v2Value, 1.0 / (16 * 2 * entry.allocation.fittingFrames.size))
        }
    }
    val environment = ResearchRunBindings(protocol = "factual-residual-environment-v1", material = mapOf(
        "incumbent" to sha256(evidenceJson.encodeToString(plan.incumbent)),
        "source" to sha256(evidenceJson.encodeToString(producer)), "deployment" to FACTUAL_OUTCOME_RESIDUAL_DEPLOYMENT,
        "anchor" to FACTUAL_OUTCOME_RESIDUAL_ANCHOR))
    val bindings = ResearchRunBindings(protocol = "factual-residual-training-v1", material = mapOf(
        "corpus" to corpusRef.identity, "corpus-manifest" to corpusRef.manifestSha256,
        "allocation" to allocationRef.identity, "allocation-manifest" to allocationRef.manifestSha256,
        "environment" to environment.identity, "target" to FACTUAL_OUTCOME_RESIDUAL_TARGET,
        "feature-schema" to LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1, "feature-scaling" to LEARNED_OUTCOME_VALUE_FEATURE_SCALING_V1,
        "objective" to FACTUAL_OUTCOME_RESIDUAL_OBJECTIVE, "weighting" to FACTUAL_RESIDUAL_WEIGHTING))
    deadline.requireRemaining()
    val fit = fitFactualOutcomeResidual(rows)
    deadline.requireRemaining()
    val evaluator = FactualOutcomeResidualEvaluator.fromCheckpoint(FactualOutcomeResidualCheckpointPayload(
        training = FactualOutcomeResidualTrainingBinding(corpusRef.identity, allocationRef.identity, bindings.identity, environment.identity),
        bias = fit.bias, weights = fit.weights))
    ResearchRunCheckpoints.persist(output.resolve("checkpoint.json"), bindings.identity,
        FACTUAL_OUTCOME_RESIDUAL_CHECKPOINT_PAYLOAD_SCHEMA, 0, evaluator.canonicalCheckpointBytes())
    writeJsonAtomically(output.resolve("report.json"), FactualResidualTrainingReport(bindings, corpusRef, allocationRef,
        environment, evaluator.checkpointIdentity, rows.size, fit.weights.size, fit.iterations, fit.maxGradientResidual))
    finalizeResearchWorkflowArtifacts(output, bindings.identity)
    return factualResidualReference(output, bindings.identity)
}

internal fun loadFactualResidualTraining(reference: FactualResidualInput): FactualOutcomeResidualEvaluator {
    verifyFactualResidualInput(reference, setOf("report.json", "checkpoint.json"))
    val directory = Path.of(reference.directory)
    val report = readEvidenceJson(directory.resolve("report.json"), FactualResidualTrainingReport.serializer())
    require(report.bindings.identity == reference.identity)
    val envelope = ResearchRunCheckpoints.load(directory.resolve("checkpoint.json"))
    require(envelope.researchRunIdentity == reference.identity && envelope.payloadSchema == FACTUAL_OUTCOME_RESIDUAL_CHECKPOINT_PAYLOAD_SCHEMA)
    require(envelope.sequence == 0L && envelope.parentPayloadSha256 == null)
    val evaluator = FactualOutcomeResidualEvaluator.load(envelope.payload())
    require(evaluator.canonicalCheckpointBytes().contentEquals(envelope.payload()) && evaluator.checkpointIdentity == report.checkpointIdentity)
    require(evaluator.checkpointIdentity.training == FactualOutcomeResidualTrainingBinding(report.corpus.identity,
        report.allocation.identity, reference.identity, report.environmentBindings.identity))
    verifyFactualResidualInput(report.corpus)
    verifyFactualResidualInput(report.allocation)
    return evaluator
}

private fun loadAllocatedFactualTrajectory(entry: FactualResidualCorpusEntry): FactualIncumbentTrajectoryReport {
    val reference = requireNotNull(entry.trajectory)
    verifyFactualResidualInput(reference)
    val trajectory = loadVerifiedFactualIncumbentTrajectory(Path.of(reference.directory), reference.identity)
    require(trajectory.disposition == FactualIncumbentTrajectoryDisposition.ADMITTED)
    val game = entry.allocation
    require(trajectory.request.gameId == game.gameId && trajectory.request.sourceRunIdentity == game.sourceRunIdentity &&
        trajectory.request.viewer == game.viewer && trajectory.request.seedGroupId == game.seedGroupId &&
        trajectory.rows.size == game.semanticDecisions)
    require(trajectory.source!!.leg == game.leg)
    return trajectory
}

private fun factualResidualPredictions(corpus: FactualResidualCorpusReport, evaluator: FactualOutcomeResidualEvaluator,
    deadline: FactualResidualDeadline): FactualResidualPredictionReadout {
    val games = corpus.entries.filter { it.allocation.role == FactualResidualDataRole.SCREEN }
    require(games.size == 32 && games.map { it.allocation.seedGroupId }.distinct().size == 16)
    var count = 0
    var anchorError = 0.0
    var residualError = 0.0
    var clipped = 0.0
    games.forEach { entry ->
        deadline.requireRemaining()
        val trajectory = loadAllocatedFactualTrajectory(entry)
        val weight = 1.0 / (16 * 2 * trajectory.rows.size)
        trajectory.rows.forEach { row ->
            val predicted = evaluator.evaluateDetailed(row.information, row.viewer)
            val a = row.v2Value - row.actualTerminalPayoff
            val b = predicted.deployedValue - row.actualTerminalPayoff
            anchorError += weight * a * a
            residualError += weight * b * b
            if (predicted.rawScore != predicted.deployedValue) clipped += weight
            count++
        }
    }
    return FactualResidualPredictionReadout(16, 32, count, anchorError, residualError, clipped)
}
