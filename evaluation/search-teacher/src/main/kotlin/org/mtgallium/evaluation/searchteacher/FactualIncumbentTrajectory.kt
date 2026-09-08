package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PublicArtifactPrivacy
import org.mtgallium.agent.searchteacher.MonoRedInformationEvaluator
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

internal const val FACTUAL_INCUMBENT_ARGENTUM_REVISION = "3757f6bd064e2f057401fac2a4226c5afa5cd011"
internal const val FACTUAL_INCUMBENT_TRAJECTORY_PROTOCOL = "factual-incumbent-trajectory-v1"
internal const val FACTUAL_INCUMBENT_REPLAY_EQUIVALENCE =
    "argentum-3757-scoped-routing-correspondence-without-historical-typeline-v1"

/** One explicitly selected factual game. No population selection, split, fit, or sampled world. */
@Serializable
internal data class FactualIncumbentTrajectoryRequest(
    val sourceDirectory: String,
    val sourceRunIdentity: String,
    val sourceManifestSha256: String,
    val sourceProvenance: ResearchSourceProvenance,
    val calibrationPlan: SearchTeacherCalibrationPlan,
    val gameId: String,
    val viewer: String,
    val seedGroupId: String,
    val build: ResearchBuildReference,
) {
    init {
        require(Path.of(sourceDirectory).isAbsolute)
        require(sourceRunIdentity.isNotBlank() && sourceManifestSha256.matches(Regex("[0-9a-f]{64}")))
        require(gameId.isNotBlank() && viewer in setOf("p0", "p1") && seedGroupId.isNotBlank())
    }
}

@Serializable
internal enum class FactualIncumbentTrajectoryDisposition {
    ADMITTED, BUILD_REFUSED, SOURCE_REFUSED, GAME_REFUSED, REPLAY_REFUSED,
}

@Serializable
internal data class FactualIncumbentTrajectoryRow(
    val decisionIndex: Int,
    val viewer: String,
    val information: PolicyInformationState,
    val v2Value: Double,
    val actualTerminalPayoff: Double,
) {
    init {
        require(decisionIndex >= 0 && viewer in setOf("p0", "p1"))
        require(!information.terminated && information.winnerId == null && information.actingPlayerId in setOf("p0", "p1"))
        require(information.observation.perspectivePlayerId == viewer && information.knowledge.perspectivePlayerId == viewer)
        require(information.knowledge.epistemicallyComplete && information.knowledge.unsupportedReasons.isEmpty())
        require(v2Value.isFinite() && v2Value == MonoRedInformationEvaluator.evaluate(information, viewer))
        require(actualTerminalPayoff in setOf(-1.0, 0.0, 1.0))
    }
}

@Serializable
internal data class FactualIncumbentTrajectorySource(
    val reportSha256: String,
    val checkpointReference: String,
    val checkpointSha256: String,
    val checkpointPayloadSha256: String,
    val replayReference: String,
    val replaySha256: String,
    val replayTerminalRecordDigest: String,
    val pairIndex: Int,
    val leg: Int,
    val gameSeed: Long,
    val p0Policy: SearchTeacherCalibrationPolicyReport,
    val p1Policy: SearchTeacherCalibrationPolicyReport,
    val deckHash: String,
    val cardPoolHash: String,
    val semanticDecisions: Int,
    val rawTransitions: Int,
)

/** A refusal never contains even a partial label population. The artifact itself stays private. */
@Serializable
internal data class FactualIncumbentTrajectoryReport(
    val schemaVersion: Int = 1,
    val bindings: ResearchRunBindings,
    val request: FactualIncumbentTrajectoryRequest,
    val producer: ResearchRunProvenance,
    val runtime: Map<String, String>,
    val disposition: FactualIncumbentTrajectoryDisposition,
    val refusal: String? = null,
    val source: FactualIncumbentTrajectorySource? = null,
    val replayEquivalence: String = FACTUAL_INCUMBENT_REPLAY_EQUIVALENCE,
    val replayAudit: OutcomeStateReplayCompatibilityAudit? = null,
    val rows: List<FactualIncumbentTrajectoryRow> = emptyList(),
) {
    init {
        require(schemaVersion == 1 && bindings.protocol == FACTUAL_INCUMBENT_TRAJECTORY_PROTOCOL)
        require(replayEquivalence == FACTUAL_INCUMBENT_REPLAY_EQUIVALENCE)
        if (disposition == FactualIncumbentTrajectoryDisposition.ADMITTED) {
            require(refusal == null && source != null && replayAudit != null && rows.isNotEmpty())
            require(!producer.outerDirty && !producer.engineDirty && producer.consistent &&
                producer.checkedOutEngineCommit == FACTUAL_INCUMBENT_ARGENTUM_REVISION)
            require(source.checkpointReference == "checkpoints/${request.gameId}.json" &&
                source.replayReference == "replays/${request.gameId}.privileged.replay.jsonl.gz")
            require(source.leg in 0..1 && source.gameSeed == request.calibrationPlan.pairSeed(source.pairIndex))
            require(request.seedGroupId == realGamePositionSeedGroup(source.deckHash, source.cardPoolHash, source.gameSeed))
            val descriptors = listOf(request.calibrationPlan.control) + request.calibrationPlan.candidates
            require(source.p0Policy.descriptor in descriptors && source.p1Policy.descriptor in descriptors)
            require(source.p0Policy.binding.sourceProvenance == request.sourceProvenance &&
                source.p1Policy.binding.sourceProvenance == request.sourceProvenance)
            require(rows.map { it.decisionIndex } == (0 until source.semanticDecisions).toList())
            require(rows.all { it.viewer == request.viewer } && rows.map { it.actualTerminalPayoff }.distinct().size == 1)
            require(replayAudit.legacyTimeLordTypeLineNormalizationCount == 0)
            replayAudit.requireForRawTransitionCount(source.rawTransitions)
        } else require(refusal != null && source == null && replayAudit == null && rows.isEmpty())
    }
}

internal fun factualIncumbentTrajectoryBindings(request: FactualIncumbentTrajectoryRequest,
    producer: ResearchRunProvenance, runtime: Map<String, String>) = ResearchRunBindings(
    protocol = FACTUAL_INCUMBENT_TRAJECTORY_PROTOCOL,
    material = mapOf(
        "request" to sha256(evidenceJson.encodeToString(request)),
        "producer" to sha256(evidenceJson.encodeToString(producer)),
        "runtime" to preflightRuntimeHash(runtime),
        "state-grain" to "actual-pre-decision-information-for-explicit-viewer",
        "label" to "same-viewer-actual-completed-terminal-payoff",
        "evaluator" to MonoRedInformationEvaluator.id,
        "replay-equivalence" to FACTUAL_INCUMBENT_REPLAY_EQUIVALENCE,
    ),
)

/** Source-owned one-game producer. Construction never runs a policy or generates a new game. */
internal class FactualIncumbentTrajectoryAdmission(
    private val repository: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
) {
    fun admit(request: FactualIncumbentTrajectoryRequest, output: Path): FactualIncumbentTrajectoryReport {
        val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "factual incumbent trajectory")
        require(!Files.exists(destination)) { "Factual trajectory output already exists" }
        val inputs = listOf(Path.of(request.sourceDirectory), Path.of(request.build.directory))
        require(inputs.none { destination.startsWith(it) || it.startsWith(destination) })
        val producer = ResearchRunProvenance.capture(repository)
        val runtime = researchPreflightRuntime()
        val bindings = factualIncumbentTrajectoryBindings(request, producer, runtime)
        var stage = FactualIncumbentTrajectoryDisposition.BUILD_REFUSED
        val result = try {
            producer.requireReady()
            verifyResearchBuild(request.build, producer, runtime)
            require(producer.checkedOutEngineCommit == FACTUAL_INCUMBENT_ARGENTUM_REVISION)
            stage = FactualIncumbentTrajectoryDisposition.SOURCE_REFUSED
            val directory = Path.of(request.sourceDirectory)
            val report = loadFactualIncumbentCalibration(request)
            requireFactualIncumbentSource(request, report, producer.sourceProvenance, manifest.deckHash(), manifest.cardPoolHash())
            val entries = ResearchRunArtifacts.loadAndVerify(directory, request.sourceRunIdentity).artifacts.associateBy { it.relativePath }
            stage = FactualIncumbentTrajectoryDisposition.GAME_REFUSED
            val selected = report.comparisons.flatMap { comparison -> comparison.pairs.flatMap { pair ->
                pair.games.mapIndexed { leg, game -> Triple(pair, leg, game) }
            } }.single { it.third.gameId == request.gameId }
            val (pair, leg, game) = selected
            requireFactualIncumbentGame(request, pair, game, report.deckHash, report.cardPoolHash)
            val p0 = report.policies.single { it.descriptor.id == game.p0PolicyId }
            val p1 = report.policies.single { it.descriptor.id == game.p1PolicyId }
            val arena = SearchTeacherArena(registry, manifest, calibrationPresentationProfile(report.sourceProvenance), report.plan.baseSeed)
            listOf(p0, p1).forEach { policy ->
                val spec = policy.descriptor.policy(report.plan.baseSeed)
                require(arena.evidenceBinding(spec, null, report.sourceProvenance) == policy.binding)
                require(describeTournamentPolicy(spec) == policy.policy && spec.effectiveParameters(report.plan.baseSeed).searchConfig() == policy.search)
                require(spec.effectiveRootRolloutPolicy().behaviorSpecification == policy.rootRolloutPolicy &&
                    spec.effectiveOpponentRolloutPolicy().behaviorSpecification == policy.opponentRolloutPolicy)
            }
            val profile = p0.descriptor.parameters(report.plan.baseSeed).actionSpaceProfile
            require(profile == p1.descriptor.parameters(report.plan.baseSeed).actionSpaceProfile)
            val checkpointReference = "checkpoints/${game.gameId}.json"
            val checkpoint = ResearchRunCheckpoints.load(ResearchRunFiles.resolveBelow(directory, checkpointReference))
            val replayReference = "replays/${game.gameId}.privileged.replay.jsonl.gz"
            require(entries.getValue(replayReference).sha256 == game.replaySha256)
            stage = FactualIncumbentTrajectoryDisposition.REPLAY_REFUSED
            val replay = readVerifiedCanonicalSemanticReplay(ResearchRunFiles.resolveBelow(directory, replayReference))
            requireFactualIncumbentReplay(request, report, game, replay)
            val projected = projectFactualIncumbentTrajectory(replay, request.viewer,
                ArgentumSemanticReplayWorldFactory(registry, manifest).create(SemanticReplaySetup(
                    game.gameId, game.seed, report.plan.baseSeed, 0, profile,
                )))
            FactualIncumbentTrajectoryReport(bindings = bindings, request = request, producer = producer, runtime = runtime,
                disposition = FactualIncumbentTrajectoryDisposition.ADMITTED,
                source = FactualIncumbentTrajectorySource(entries.getValue("report.json").sha256,
                    checkpointReference, entries.getValue(checkpointReference).sha256, checkpoint.payloadSha256,
                    replayReference, requireNotNull(game.replaySha256), replay.terminal.recordDigest,
                    pair.pairIndex, leg, game.seed, p0, p1, report.deckHash, report.cardPoolHash,
                    replay.decisions.size, replay.decisions.sumOf { it.transitions.size }),
                replayAudit = projected.second, rows = projected.first)
        } catch (failure: Exception) {
            FactualIncumbentTrajectoryReport(bindings = bindings, request = request, producer = producer, runtime = runtime,
                disposition = stage, refusal = "${failure.javaClass.simpleName}: ${failure.message}")
        }
        Files.createDirectories(destination)
        writeJsonAtomically(destination.resolve("report.json"), result)
        ResearchRunArtifacts(destination, bindings.identity).also { it.register("report.json"); it.finalize() }
        return result
    }
}

/** The same completed-calibration/checkpoint authority used by position-bank admission. */
internal fun loadFactualIncumbentCalibration(request: FactualIncumbentTrajectoryRequest): SearchTeacherCalibrationReport {
    val directory = Path.of(request.sourceDirectory)
    require(sha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == request.sourceManifestSha256)
    return loadCompletedCalibration(directory, request.sourceRunIdentity)
}

internal fun requireFactualIncumbentSource(request: FactualIncumbentTrajectoryRequest,
    report: SearchTeacherCalibrationReport, producer: ResearchSourceProvenance, deckHash: String, cardPoolHash: String) {
    require(report.runIdentity == request.sourceRunIdentity && report.sourceProvenance == request.sourceProvenance)
    require(report.plan == request.calibrationPlan)
    require(report.sourceProvenance.gitlinkMatchesCheckout && producer.gitlinkMatchesCheckout)
    require(report.sourceProvenance.argentum.revision == FACTUAL_INCUMBENT_ARGENTUM_REVISION &&
        producer.argentum.revision == FACTUAL_INCUMBENT_ARGENTUM_REVISION)
    require(report.deckHash == deckHash && report.cardPoolHash == cardPoolHash)
}

internal fun requireFactualIncumbentGame(request: FactualIncumbentTrajectoryRequest,
    pair: SearchBudgetFrontierPair, game: GameRunResult, deckHash: String, cardPoolHash: String) {
    require(pair.valid && pair.invalidationReasons.isEmpty() && game in pair.games)
    require(game.gameId == request.gameId && game.seed == pair.seed && game.decisions > 0)
    require(game.p0Policy == ArenaPolicyKind.SEARCH && game.p1Policy == ArenaPolicyKind.SEARCH)
    val policyIds = (listOf(request.calibrationPlan.control) + request.calibrationPlan.candidates).map { it.id }
    require(game.p0PolicyId in policyIds && game.p1PolicyId in policyIds)
    require(game.disposition == GameRunDisposition.GAME_ENDED && game.terminal && game.replayVerified &&
        game.replayVerificationDiagnostic == null && game.exception == null && !game.stepLimit && game.evidenceStop == null)
    require(searchBudgetFrontierInvalidationReasons(game).isEmpty())
    require(game.informationLedgerComplete && game.unsupportedInformationEvents.isEmpty())
    require(realGamePositionSeedGroup(deckHash, cardPoolHash, game.seed) == request.seedGroupId)
}

internal fun requireFactualIncumbentReplay(request: FactualIncumbentTrajectoryRequest,
    report: SearchTeacherCalibrationReport, game: GameRunResult, replay: VerifiedCanonicalSemanticReplay) {
    val header = replay.header
    require(header.gameId == game.gameId && header.players == listOf("p0", "p1"))
    require(header.engineVersion == FACTUAL_INCUMBENT_ARGENTUM_REVISION)
    val expected = mapOf("mtgallium.runIdentity" to report.runIdentity,
        "mtgallium.outerCommit" to report.sourceProvenance.outer.revision,
        "mtgallium.argentumCommit" to report.sourceProvenance.argentum.revision,
        "mtgallium.gameSeed" to game.seed.toString(), "mtgallium.baseSeed" to report.plan.baseSeed.toString(),
        "mtgallium.deckHash" to report.deckHash, "mtgallium.cardPoolHash" to report.cardPoolHash)
    expected.forEach { (key, value) -> require(header.extensions[key]?.jsonPrimitive?.content == value) { "Replay binding differs: $key" } }
    require(request.viewer in header.players && replay.decisions.size == game.decisions)
    require(replay.terminal.winnerId == game.winner)
}

/** Whole replay must verify before any captured information acquires a terminal label. */
internal fun projectFactualIncumbentTrajectory(replay: VerifiedCanonicalSemanticReplay, viewer: String,
    world: SemanticReplayWorld): Pair<List<FactualIncumbentTrajectoryRow>, OutcomeStateReplayCompatibilityAudit> {
    require(viewer in setOf("p0", "p1") && replay.header.players == listOf("p0", "p1"))
    require(replay.terminal.status == org.mtgallium.evaluation.searchteacher.replay.ReplayCompletionStatus.COMPLETE)
    val equivalence = RecordedReplayStateEquivalence.currentEngine(replay.header.engineVersion)
    val information = mutableListOf<PolicyInformationState>()
    replaySemanticPrefix(replay.decisions.size, replay, world, equivalence, beforeDecision = { index ->
        require(index == information.size)
        val state = world.informationState(viewer)
        require(!state.terminated && state.winnerId == null && state.actingPlayerId == world.actorToAct())
        require(state.observation.perspectivePlayerId == viewer && state.knowledge.perspectivePlayerId == viewer)
        require(state.knowledge.epistemicallyComplete && state.knowledge.unsupportedReasons.isEmpty())
        information += state
    })
    require(equivalence.finalDifference(replay.states.last(), world.authoritativeState(),
        replay.decisions.sumOf { it.transitions.size }) == null)
    val finalInformation = world.informationState(viewer)
    require(finalInformation.terminated && finalInformation.winnerId == replay.terminal.winnerId)
    require(finalInformation.observation.perspectivePlayerId == viewer && finalInformation.knowledge.perspectivePlayerId == viewer)
    require(finalInformation.knowledge.epistemicallyComplete && finalInformation.knowledge.unsupportedReasons.isEmpty())
    val payoff = requireNotNull(world.terminalPayoff(viewer))
    require(payoff == when (replay.terminal.winnerId) { viewer -> 1.0; null -> 0.0; else -> -1.0 })
    val rows = information.mapIndexed { index, state -> FactualIncumbentTrajectoryRow(index, viewer, state,
        MonoRedInformationEvaluator.evaluate(state, viewer), payoff) }
    val encoded = evidenceJson.encodeToString(rows)
    PublicArtifactPrivacy.requireSafeJson(encoded, "factual incumbent trajectory information")
    require(equivalence.safeDerivedArtifactDifference(evidenceJson.encodeToJsonElement(rows)) == null)
    return rows to equivalence.completedAudit()
}

internal fun loadVerifiedFactualIncumbentTrajectory(directory: Path, expectedIdentity: String): FactualIncumbentTrajectoryReport {
    val artifacts = ResearchRunArtifacts.loadAndVerify(directory, expectedIdentity)
    require(artifacts.artifacts.any { it.relativePath == "report.json" }) {
        "Factual trajectory manifest does not register report.json"
    }
    val report = readEvidenceJson(ResearchRunFiles.resolveBelow(directory, "report.json"), FactualIncumbentTrajectoryReport.serializer())
    require(report.bindings.identity == expectedIdentity &&
        report.bindings == factualIncumbentTrajectoryBindings(report.request, report.producer, report.runtime))
    return report
}
