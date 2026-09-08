package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import kotlin.math.ceil
import org.mtgallium.research.run.*

/** Read-only admission continuation: original child identities and producers remain authoritative. */
internal data class FactualResidualContinuation(
    val parent: FactualResidualStudyReport,
    val allocation: FactualResidualAllocation,
    val entries: Map<Int, FactualResidualCorpusEntry>,
)

/** No replay, label projection, fit, or artifact write occurs here; callers supply authenticated source inputs. */
internal fun loadFactualResidualContinuation(parent: FactualResidualInput, plan: FactualResidualStudyPlan,
    allocation: FactualResidualAllocation, deck: DeckManifest,
    inputs: LoadedFactualResidualInputs): FactualResidualContinuation {
    require(plan.admissionParent == parent)
    val directory = Path.of(parent.directory).toAbsolutePath().normalize()
    require(researchSha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == parent.manifestSha256)
    val artifacts = ResearchRunArtifacts.loadAndVerify(directory, parent.identity).artifacts.associateBy { it.relativePath }
    require(artifacts.keys.containsAll(setOf("report.json", "plan.json", "bindings.json",
        "allocation/report.json", "allocation/${ResearchRunArtifacts.MANIFEST_FILE}")))
    require(artifacts.keys.none { it == "corpus/report.json" || it == "corpus/${ResearchRunArtifacts.MANIFEST_FILE}" ||
        it.startsWith("training/") || it.startsWith("readout/") }) { "Admission continuation cannot reuse a post-corpus or fitted study" }
    val report = readEvidenceJson(directory.resolve("report.json"), FactualResidualStudyReport.serializer())
    require(report.bindings.identity == parent.identity &&
        report.bindings == factualResidualStudyBindings(report.plan, report.producer, report.runtime, deck))
    require(readEvidenceJson(directory.resolve("plan.json"), FactualResidualStudyPlan.serializer()) == report.plan)
    require(readEvidenceJson(directory.resolve("bindings.json"), ResearchRunBindings.serializer()) == report.bindings)
    require(report.disposition == FactualResidualStudyDisposition.STUDY_REFUSED && report.corpus == null &&
        report.training == null && report.predictions == null && report.searches.isEmpty() && report.gate == null)
    require(report.plan.admissionParent == null) { "Chained admission continuation is not supported" }
    require(report.producer.checkedOutEngineCommit == FACTUAL_INCUMBENT_ARGENTUM_REVISION)
    verifyResearchBuild(report.plan.build, report.producer, report.runtime)
    require(plan.copy(build = report.plan.build, workers = report.plan.workers,
        admissionWorkers = report.plan.admissionWorkers, maximumSeconds = report.plan.maximumSeconds,
        admissionParent = null) == report.plan) { "Admission continuation changes the scientific plan" }
    require(plan.maximumSeconds.toDouble() + ceil(report.elapsedSeconds) <= report.plan.maximumSeconds) {
        "Admission continuation exceeds the original native study time budget"
    }
    val allocationReference = requireNotNull(report.allocation)
    require(Path.of(allocationReference.directory).toAbsolutePath().normalize() == directory.resolve("allocation"))
    require(allocationReference.manifestSha256 == artifacts.getValue("allocation/${ResearchRunArtifacts.MANIFEST_FILE}").sha256)
    verifyFactualResidualInput(allocationReference)
    val priorAllocation = readEvidenceJson(directory.resolve("allocation/report.json"), FactualResidualAllocation.serializer())
    require(priorAllocation.bindings.identity == allocationReference.identity && priorAllocation.studyIdentity == parent.identity)
    require(priorAllocation.inventory == plan.inventory && priorAllocation.inventory == allocation.inventory &&
        priorAllocation.incumbent == plan.incumbent && priorAllocation.incumbent == allocation.incumbent &&
        priorAllocation.games == allocation.games && priorAllocation.frameRule == allocation.frameRule &&
        priorAllocation.weighting == allocation.weighting && priorAllocation.rootOrder == allocation.rootOrder) {
        "Admission continuation changes the frozen allocation"
    }

    val childPath = Regex("^corpus/trajectories/game-(0|[1-9][0-9]*)/(report\\.json|research-run-manifest\\.json)$")
    val registered = artifacts.keys.filter { it.startsWith("corpus/trajectories/") }.groupBy { relative ->
        val match = requireNotNull(childPath.matchEntire(relative)) { "Unexpected registered trajectory artifact: $relative" }
        match.groupValues[1].toInt().also { require(it in allocation.games.indices) }
    }.toSortedMap()
    require(registered.size in 1 until allocation.games.size) { "Continuation requires both reusable and missing admission coordinates" }
    registered.forEach { (index, paths) ->
        val prefix = "corpus/trajectories/game-$index/"
        require(paths.toSet() == setOf(prefix + "report.json", prefix + ResearchRunArtifacts.MANIFEST_FILE)) {
            "Registered trajectory $index does not have a complete report/manifest pair"
        }
    }
    // The existing source manifest authority is checked sequentially once per source, not once per child.
    val sourceArtifacts = inputs.sources.mapValues { (_, source) ->
        require(researchSha256File(Path.of(source.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)) == source.manifestSha256)
        ResearchRunArtifacts.loadAndVerify(Path.of(source.directory), source.identity).artifacts.associateBy { it.relativePath }
    }
    val coordinates = registered.keys.toList()
    val entries = parallelMapOrdered(coordinates.size, minOf(2, plan.effectiveAdmissionWorkers)) { offset ->
        val index = coordinates[offset]
        val childDirectory = directory.resolve("corpus/trajectories/game-$index")
        val childManifest = readEvidenceJson(childDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE), ResearchRunArtifactManifest.serializer())
        require(childManifest.artifacts.size == 1 && childManifest.artifacts.single() ==
            artifacts.getValue("corpus/trajectories/game-$index/report.json").copy(relativePath = "report.json"))
        val reference = FactualResidualInput(childDirectory.toString(), childManifest.researchRunIdentity,
            artifacts.getValue("corpus/trajectories/game-$index/${ResearchRunArtifacts.MANIFEST_FILE}").sha256)
        index to loadReusableFactualResidualTrajectory(reference, allocation.games[index], report, parent.identity,
            deck, inputs, sourceArtifacts)
    }.toMap()
    return FactualResidualContinuation(report, priorAllocation, entries)
}

/** A worker returns compact metadata, releasing its decoded rows before the next child is loaded. */
private fun loadReusableFactualResidualTrajectory(reference: FactualResidualInput, game: FactualResidualGameAllocation,
    parent: FactualResidualStudyReport, parentIdentity: String, deck: DeckManifest,
    inputs: LoadedFactualResidualInputs, sourceArtifacts: Map<String, Map<String, ResearchRunArtifact>>): FactualResidualCorpusEntry {
    val trajectory = loadVerifiedFactualIncumbentTrajectory(Path.of(reference.directory), reference.identity)
    require(trajectory.disposition == FactualIncumbentTrajectoryDisposition.ADMITTED) { "A registered parent trajectory is not admitted" }
    val sourceReference = inputs.sources.getValue(game.sourceRunIdentity)
    val calibration = inputs.parents.getValue(game.sourceRunIdentity)
    val expectedRequest = FactualIncumbentTrajectoryRequest(sourceReference.directory, sourceReference.identity,
        sourceReference.manifestSha256, calibration.sourceProvenance, calibration.plan, game.gameId,
        game.viewer, game.seedGroupId, parent.plan.build)
    require(trajectory.request == expectedRequest && trajectory.producer == parent.producer && trajectory.runtime == parent.runtime) {
        "Parent trajectory source, build, producer, or runtime differs from its admission study"
    }
    val source = requireNotNull(trajectory.source)
    val pair = calibration.comparisons.flatMap { it.pairs }.single { it.games.any { actual -> actual.gameId == game.gameId } }
    val actual = pair.games[game.leg]
    require(actual.gameId == game.gameId && source.pairIndex == pair.pairIndex && source.leg == game.leg &&
        source.gameSeed == actual.seed && source.semanticDecisions == game.semanticDecisions &&
        source.semanticDecisions == actual.decisions && trajectory.rows.size == game.semanticDecisions)
    require(source.deckHash == deck.deckHash() && source.cardPoolHash == deck.cardPoolHash())
    require(source.p0Policy == calibration.policies.single { it.descriptor.id == actual.p0PolicyId } &&
        source.p1Policy == calibration.policies.single { it.descriptor.id == actual.p1PolicyId })
    val artifacts = sourceArtifacts.getValue(game.sourceRunIdentity)
    require(source.reportSha256 == artifacts.getValue("report.json").sha256 &&
        source.replaySha256 == artifacts.getValue(source.replayReference).sha256 && source.replaySha256 == actual.replaySha256 &&
        source.checkpointSha256 == artifacts.getValue(source.checkpointReference).sha256)
    val checkpoint = ResearchRunCheckpoints.load(ResearchRunFiles.resolveBelow(Path.of(sourceReference.directory), source.checkpointReference))
    require(source.checkpointPayloadSha256 == checkpoint.payloadSha256)
    return FactualResidualCorpusEntry(game, reference, trajectory.disposition, trajectory.rows.size, null,
        reusedFromStudyIdentity = parentIdentity)
}
