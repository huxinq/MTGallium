package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.TreeSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.infoset.core.InformationSetSearch
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SearchSettlementOrigin
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.searchteacher.LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatureCompiler
import org.mtgallium.agent.searchteacher.MonoRedInformationEvaluator
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints
import org.mtgallium.research.run.ResearchRunFiles
import org.mtgallium.research.run.ResearchRunProvenance
import org.mtgallium.research.run.researchSha256

internal const val DECISION_LOCAL_ROOT_PROTOCOL = "decision-local-sibling-root-population-v1"
internal const val DECISION_LOCAL_EXPERIMENT_PROTOCOL = "decision-local-sibling-terminal-outcome-v1"
internal const val DECISION_LOCAL_SIGNAL_PROTOCOL = "decision-local-sibling-terminal-signal-v1"
internal const val DECISION_LOCAL_SOURCE_PILOT_RUN =
    "research-run-v1-sha256:f79abc1d2dfcccedf65287fb661cf7b2b5b25ca6a9f223f2285c4652d0f48f9d"
internal const val DECISION_LOCAL_SELECTION_RULE =
    "one-control-policy-root-per-pair-target-family-phase-seat-and-candidate-band-minimum-penalty-sha256-tie-v1"
internal const val DECISION_LOCAL_SPLIT_RULE =
    "whole-pair-sha256-rank-34-train-6-validation-10-test-v1"
internal const val DECISION_LOCAL_CONTINUATION_SEED_RULE =
    "common-across-siblings-root-split-replicate-derived-future-chance-v1"
internal const val DECISION_LOCAL_FEATURE_SCHEDULE =
    "all-64-production-root-particle-coordinates-current-information-leaf-boundary-v1"
internal const val DECISION_LOCAL_MODEL_OBJECTIVE =
    "equal-root-equal-sibling-root-centered-terminal-outcome-ridge-v1"
internal const val DECISION_LOCAL_SOLVER = "jacobi-pcg-sparse-root-centered-normal-equation-v1"
internal const val DECISION_LOCAL_ROOT_CHECKPOINT_SCHEMA = "decision-local-root-evidence-v1"
internal const val DECISION_LOCAL_DEFAULT_RIDGE = 0.01
private const val DECISION_LOCAL_TOLERANCE = 1e-7
private const val DECISION_LOCAL_MAX_CONTINUATION_DECISIONS = 4096
private const val DECISION_LOCAL_FEATURE_WORLDS = 64
private const val DECISION_LOCAL_PRIMARY_REPLICATES = 8
private const val DECISION_LOCAL_TEST_REPLICATES = 16

@Serializable
internal enum class DecisionLocalSplit { TRAIN, VALIDATION, TEST, CHALLENGE }

@Serializable
internal data class DecisionLocalGateSpecification(
    val schemaVersion: Int = 1,
    val minimumPairwiseAccuracyAdvantage: Double = 0.05,
    val minimumMeanIndependentRegretAdvantage: Double = 0.05,
    val minimumCompositeAdvantage: Double = 0.10,
    val maximumOptimismP90Disadvantage: Double = 0.05,
    val minimumSignalSpreadFraction: Double = 0.20,
    val minimumDistinguishableFraction: Double = 0.10,
    val maximumMeanCandidateStandardError: Double = 0.25,
    val meaningfulSpread: Double = 0.25,
    val ambiguityAgainstCheapHeuristicIsFailure: Boolean = true,
) {
    init { require(schemaVersion == 1 && ambiguityAgainstCheapHeuristicIsFailure) }
}

internal val decisionLocalGate = DecisionLocalGateSpecification()

@Serializable
internal data class DecisionLocalRootAssignment(
    val root: LearnedLeafFixedRootSelection,
    val split: DecisionLocalSplit,
    val splitRankSha256: String,
) {
    init {
        require(split != DecisionLocalSplit.CHALLENGE)
        require(splitRankSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

@Serializable
internal data class DecisionLocalRootManifest(
    val schemaVersion: Int = 1,
    val protocol: String = DECISION_LOCAL_ROOT_PROTOCOL,
    val manifestId: String,
    val selectionWasResultBlind: Boolean,
    val selectionRule: String,
    val splitRule: String,
    val sourcePilotRunIdentity: String,
    val historicalRootSourceCommit: String,
    val argentumCommit: String,
    val pilot: LearnedLeafFixedRootPilotBinding,
    val gate: DecisionLocalGateSpecification,
    val assignments: List<DecisionLocalRootAssignment>,
) {
    init {
        require(schemaVersion == 1 && protocol == DECISION_LOCAL_ROOT_PROTOCOL)
        require(selectionWasResultBlind)
        require(selectionRule == DECISION_LOCAL_SELECTION_RULE)
        require(splitRule == DECISION_LOCAL_SPLIT_RULE)
        require(sourcePilotRunIdentity == DECISION_LOCAL_SOURCE_PILOT_RUN)
        require(assignments.size == 50)
        require(assignments.map { it.root.pairIndex }.distinct().size == assignments.size)
        require(assignments.count { it.split == DecisionLocalSplit.TRAIN } == 34)
        require(assignments.count { it.split == DecisionLocalSplit.VALIDATION } == 6)
        require(assignments.count { it.split == DecisionLocalSplit.TEST } == 10)
        require(assignments.all { it.root.sourcePolicyId == pilot.control.id })
        require(manifestId == "CONTENT_ID_OMITTED" || manifestId == decisionLocalRootManifestId(this))
    }
}

private fun decisionLocalRootManifestId(manifest: DecisionLocalRootManifest): String =
    "decision-local-root-manifest-v1-sha256:" + researchSha256(
        evidenceJson.encodeToString(manifest.copy(manifestId = "CONTENT_ID_OMITTED"))
    )

/** Selects and binds roots without reading a winner, payoff, learned score, visit, or mean value. */
internal class DecisionLocalRootFreezer(
    private val repositoryRoot: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val deckManifest: DeckManifest,
) {
    fun freeze(
        pilotDirectory: Path,
        corpusDirectory: Path,
        gateDirectory: Path,
        output: Path,
    ): DecisionLocalRootManifest {
        require(!Files.exists(output)) { "Root-freeze output must be fresh: $output" }
        val provenance = ResearchRunProvenance.capture(repositoryRoot)
        provenance.requireReady()
        require(!provenance.outerDirty && !provenance.engineDirty) {
            "Root freezing requires committed clean treatment source"
        }
        val historical = HistoricalOutcomeValueDiagnosticCheckpoint.load(corpusDirectory, gateDirectory)
        val reportPath = pilotDirectory.resolve("report.json")
        val artifactPath = pilotDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)
        ResearchRunArtifacts.loadAndVerify(pilotDirectory, DECISION_LOCAL_SOURCE_PILOT_RUN)
        val pilot = evidenceJson.decodeFromString<LearnedLeafPilotReport>(Files.readString(reportPath))
        require(pilot.valid && pilot.runIdentity == DECISION_LOCAL_SOURCE_PILOT_RUN)
        require(pilot.argentumCommit == provenance.checkedOutArgentumCommit)
        val control = pilot.policies.single { it.leaf?.stateSource == LeafStateSource.BOUNDED_ROLLOUT }
        val learned = pilot.policies.single { it.leaf?.evaluator == LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1 }
        val stubPilot = LearnedLeafFixedRootStubPilot(
            runIdentity = pilot.runIdentity,
            mtgalliumSourceCommit = pilot.outerCommit,
            argentumCommit = pilot.argentumCommit,
            manifestSha256 = sha256File(artifactPath),
            reportSha256 = sha256File(reportPath),
            checkpointPayloadSha256 = pilot.trainingEnvelopePayloadSha256,
            trainingRunIdentity = pilot.trainingRunIdentity,
            corpusIdentity = pilot.corpusIdentity,
            replayBaseSeed = pilot.baseSeed,
            policySearchBaseSeed = 20260825L,
        )
        val drafts = pilot.pairs.sortedBy { it.pairIndex }.map { pair ->
            selectRootDraft(pilotDirectory, pair, control.id)
        }
        val stub = LearnedLeafFixedRootSelectionStub(
            stubSchemaVersion = 1,
            state = "INCOMPLETE_RECONSTRUCTION_STUB",
            selectionWasResultBlind = true,
            pilot = stubPilot,
            missingReconstructionOwnedFields = listOf(
                "roots[].representedKnowledgeCategory", "roots[].rootInformationStateDigest", "roots[].schedule",
            ),
            selectionRule = DECISION_LOCAL_SELECTION_RULE,
            roots = drafts,
        )
        val binder = LearnedLeafFixedRootProductionBinder(pilotDirectory, registry, deckManifest, historical)
        val verifiedPilot = binder.loadAndVerifyPilot(stub)
        binder.requireStubPilot(stub, verifiedPilot)
        val pilotBinding = learnedLeafFixedRootPilotBinding(pilot, historical)
        require(pilotBinding.control.id == control.id && pilotBinding.learned.id == learned.id)
        val roots = drafts.map { binder.bindRoot(stub, pilot, pilotBinding, it) }
        val ranked = roots.associateWith {
            researchSha256("decision-local-whole-pair-split-v1:${pilot.runIdentity}:${it.pairIndex}")
        }
        val orderedRanks = ranked.entries.sortedBy { it.value }
        val splitByPair = buildMap {
            orderedRanks.forEachIndexed { index, entry ->
                put(entry.key.pairIndex, when {
                    index < 34 -> DecisionLocalSplit.TRAIN
                    index < 40 -> DecisionLocalSplit.VALIDATION
                    else -> DecisionLocalSplit.TEST
                })
            }
        }
        val provisional = DecisionLocalRootManifest(
            manifestId = "CONTENT_ID_OMITTED",
            selectionWasResultBlind = true,
            selectionRule = DECISION_LOCAL_SELECTION_RULE,
            splitRule = DECISION_LOCAL_SPLIT_RULE,
            sourcePilotRunIdentity = pilot.runIdentity,
            historicalRootSourceCommit = pilot.outerCommit,
            argentumCommit = pilot.argentumCommit,
            pilot = pilotBinding,
            gate = decisionLocalGate,
            assignments = roots.sortedBy { it.pairIndex }.map {
                DecisionLocalRootAssignment(it, splitByPair.getValue(it.pairIndex), ranked.getValue(it))
            },
        )
        val manifest = provisional.copy(manifestId = decisionLocalRootManifestId(provisional))
        Files.createDirectories(output)
        ResearchRunFiles.atomicWrite(output.resolve("root-manifest.json"), evidenceJson.encodeToString(manifest) + "\n")
        val bindings = ResearchRunBindings(
            protocol = DECISION_LOCAL_ROOT_PROTOCOL,
            material = mapOf(
                "treatment-source" to provenance.outerCommit,
                "source-pilot" to pilot.runIdentity,
                "historical-root-source" to pilot.outerCommit,
                "argentum" to pilot.argentumCommit,
                "selection-rule" to DECISION_LOCAL_SELECTION_RULE,
                "split-rule" to DECISION_LOCAL_SPLIT_RULE,
                "root-manifest" to manifest.manifestId,
            ),
        )
        ResearchRunFiles.atomicWrite(output.resolve("run-identity.txt"), bindings.identity + "\n")
        ResearchRunArtifacts(output, bindings.identity).also {
            it.register("root-manifest.json")
            it.register("run-identity.txt")
            it.finalize()
        }
        return manifest
    }

    internal fun selectRootDraft(
        pilotDirectory: Path,
        pair: LearnedLeafPilotPair,
        controlPolicyId: String,
    ): LearnedLeafFixedRootStubRoot {
        val targetSeat = if (pair.pairIndex % 2 == 0) "p0" else "p1"
        val game = pair.games.single { game ->
            (if (targetSeat == "p0") game.p0PolicyId else game.p1PolicyId) == controlPolicyId
        }
        require(game.terminal && game.replayVerified && game.exception == null)
        val details = requireNotNull(game.seatDiagnostics[targetSeat]).searchDecisionsDetail.filter {
            it.candidateStatistics.size in 2..8 && it.chosen != null
        }
        require(details.isNotEmpty())
        val targetFamilies = listOf(
            SemanticOperationFamily.MULLIGAN,
            SemanticOperationFamily.CAST_SPELL,
            SemanticOperationFamily.DECLARE_ATTACKERS,
            SemanticOperationFamily.DECLARE_BLOCKERS,
            SemanticOperationFamily.PLAY_LAND,
            SemanticOperationFamily.ACTIVATE_ABILITY,
            SemanticOperationFamily.PASS_PRIORITY,
            SemanticOperationFamily.DECISION_RESPONSE,
        )
        val desiredFamily = targetFamilies[pair.pairIndex % targetFamilies.size]
        val desiredPhase = listOf("EARLY", "MIDDLE", "LATE")[(pair.pairIndex / targetFamilies.size) % 3]
        val desiredBand = if ((pair.pairIndex / 3) % 2 == 0) "SMALL" else "MODERATE"
        fun phase(turn: Int) = when {
            turn <= 2 -> "EARLY"
            turn <= 5 -> "MIDDLE"
            else -> "LATE"
        }
        fun band(count: Int) = if (count <= 3) "SMALL" else "MODERATE"
        val selected = details.minWith(
            compareBy<ArenaSearchDecisionDiagnostic> {
                (if (it.chosen!!.operationFamily == desiredFamily) 0 else 100) +
                    (if (phase(it.turnNumber) == desiredPhase) 0 else 20) +
                    (if (band(it.candidateStatistics.size) == desiredBand) 0 else 5)
            }.thenBy {
                researchSha256("decision-local-root-tie-v1:${pair.pairIndex}:${game.gameId}:${it.decisionIndex}")
            }
        )
        val replayRelative = "replays/${Path.of(requireNotNull(game.replayPath)).fileName}"
        val replay = readVerifiedCanonicalSemanticReplay(
            fixedRootReplayPath(pilotDirectory, replayRelative, requireNotNull(game.replaySha256))
        )
        val prefix = replay.decisions.take(selected.decisionIndex).map(CanonicalSemanticDecision::choice)
        val signatures = selected.candidateStatistics.map { it.choice.signature }.sorted()
        return LearnedLeafFixedRootStubRoot(
            id = "primary-pair-${pair.pairIndex}-${targetSeat}-decision-${selected.decisionIndex}",
            pairIndex = pair.pairIndex,
            leg = if (targetSeat == "p0") "a" else "b",
            sourceGameId = game.gameId,
            decisionIndex = selected.decisionIndex,
            rootActor = targetSeat,
            sourcePolicyId = controlPolicyId,
            sourceDecisionFamily = selected.chosen!!.operationFamily.name,
            sourcePhase = selected.phase,
            sourceStep = selected.step,
            turnNumber = selected.turnNumber,
            sourceSeat = if (targetSeat == "p0") "PLAY" else "DRAW",
            selectionReason = "target-family=${desiredFamily.name};target-phase=$desiredPhase;target-band=$desiredBand",
            marginBand = "NOT_SELECTED_BY_RESULT_OR_SCORE",
            marginMetadata = "result-blind-metadata-only-v1",
            semanticPrefixDigest = PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }),
            replayRelativePath = replayRelative,
            replaySha256 = requireNotNull(game.replaySha256),
            candidateSignatures = signatures,
            candidateFamilyDigest = learnedLeafCandidateFamilyDigest(signatures),
            replayGameSeed = game.seed,
        )
    }
}

internal fun loadDecisionLocalRootManifest(path: Path): DecisionLocalRootManifest {
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path))
    val manifest = evidenceJson.decodeFromString<DecisionLocalRootManifest>(Files.readString(path))
    require(manifest.manifestId == decisionLocalRootManifestId(manifest))
    return manifest
}

@Serializable
internal data class DecisionLocalCandidateEvidence(
    val signature: String,
    val featureWorlds: Int,
    val nonterminalFeatureWorlds: Int,
    val terminalFeatureWorlds: Int,
    val terminalFeatureOffset: Double,
    val featureMeans: Map<String, Double>,
    val featureScheduleDigest: String,
    val cheapHeuristicScore: Double,
    val failedGlobalModelScore: Double,
    val primaryTerminalPayoffs: List<Double>,
    val independentTerminalPayoffs: List<Double>,
    val continuationPolicyDecisions: Int,
    val continuationRuntimeMillis: Double,
) {
    init {
        require(featureWorlds == nonterminalFeatureWorlds + terminalFeatureWorlds)
        require(primaryTerminalPayoffs.size >= DECISION_LOCAL_PRIMARY_REPLICATES)
        require((primaryTerminalPayoffs + independentTerminalPayoffs).all { it in -1.0..1.0 })
        require(featureMeans.values.all(Double::isFinite))
    }
    val primaryMean: Double get() = primaryTerminalPayoffs.average()
    val independentMean: Double? get() = independentTerminalPayoffs.takeIf { it.isNotEmpty() }?.average()
}

@Serializable
internal data class DecisionLocalRootEvidence(
    val schemaVersion: Int = 1,
    val rootId: String,
    val split: DecisionLocalSplit,
    val pairIndex: Int,
    val decisionFamily: String,
    val phase: String,
    val turnNumber: Int,
    val rootActor: String,
    val representedKnowledgeCategory: String,
    val candidateFamilyDigest: String,
    val productionScheduleDigest: String,
    val primaryReplicates: Int,
    val independentReplicates: Int,
    val candidates: List<DecisionLocalCandidateEvidence>,
    val failures: List<String> = emptyList(),
) {
    init {
        require(schemaVersion == 1)
        require(candidates.map { it.signature }.sorted() == candidates.map { it.signature })
        require(candidates.size in 2..8)
        require(failures.isEmpty())
    }
}

private class CapturingLeafEvaluator : ConfiguredInformationStateEvaluator {
    override val id: String = MonoRedInformationEvaluator.id
    override val configurationId: String = "decision-local-capturing-visible-boundary-v1"
    var captured: PolicyInformationState? = null
        private set
    override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double {
        check(captured == null) { "Leaf boundary invoked the capture evaluator more than once" }
        captured = information
        return MonoRedInformationEvaluator.evaluate(information, rootPlayer)
    }
}

internal data class DecisionLocalReconstructedRoot(
    val worlds: List<ArgentumSearchWorld>,
    val liveSearchSeed: Long,
)

/** Shared historical reconstruction authority for the original experiment and its precision follow-up. */
internal fun reconstructDecisionLocalRoot(
    pilotDirectory: Path,
    registry: com.wingedsheep.engine.registry.CardRegistry,
    deckManifest: DeckManifest,
    pilot: LearnedLeafFixedRootPilotBinding,
    root: LearnedLeafFixedRootSelection,
    learnedEvaluator: ConfiguredInformationStateEvaluator? = null,
): DecisionLocalReconstructedRoot {
    val knownDecks = mapOf("p0" to deckManifest.mainDeck, "p1" to deckManifest.mainDeck)
    val opponent = defaultMonoRedOpponentPolicy()
    val replay = readVerifiedCanonicalSemanticReplay(
        fixedRootReplayPath(pilotDirectory, root.replayRelativePath, root.replaySha256)
    )
    val actual = createSemanticReplayWorld(
        registry, deckManifest, root.sourceGameId, root.schedule.replayGameSeed,
        root.schedule.replayBaseSeed, 0,
        org.mtgallium.agent.infoset.core.SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
    )
    val sourceBinding = listOf(pilot.control, pilot.learned).single { it.id == root.sourcePolicyId }
    val sourceParameters = sourceBinding.composition.parameters(
        root.schedule.policySearchBaseSeed, sourceBinding.leaf,
    )
    require(sourceBinding.id != pilot.learned.id || learnedEvaluator != null)
    val session = SearchTeacherPolicySession(
        root = actual,
        viewer = root.rootActor,
        knownDecks = knownDecks,
        parameters = sourceParameters,
        opponentPolicy = opponent,
        gameId = root.sourceGameId,
        informationEvaluator = if (sourceBinding.id == pilot.learned.id) learnedEvaluator else null,
    )
    replayFixedRootPrefix(root.decisionIndex, replay, actual, session)
    require(actual.actorToAct() == root.rootActor)
    require(actual.informationState(root.rootActor).informationStateDigest == root.rootInformationStateDigest)
    val candidates = actual.expandChoices().candidates
    require(candidates.map { it.signature }.sorted() == root.candidateSignatures)
    val belief = session.beliefBatch(actual)
    val liveSearchSeed = ComponentSeeds.derive(
        root.schedule.originalGameId, root.schedule.decisionIndex,
        root.schedule.policySearchBaseSeed, "live-search",
    )
    val indices = InformationSetSearch.productionRootParticleIndices(
        belief.particles.map { it.weight }, liveSearchSeed, 64,
    )
    require(indices == root.schedule.coordinates.map { it.rootParticleIndex })
    return DecisionLocalReconstructedRoot(
        belief.particles.map { it.value as ArgentumSearchWorld }, liveSearchSeed,
    )
}

@Serializable
internal data class DecisionLocalTerminalSample(
    val replicate: Int,
    val particleIndex: Int,
    val futureSeed: Long,
    val continuationSeed: Long,
    val payoff: Double,
    val policyDecisions: Int,
    val elapsedMillis: Double,
) { init { require(payoff in -1.0..1.0) } }

/** The original seed domains and production terminal continuation are shared verbatim. */
internal fun continueDecisionLocalCandidate(
    reconstructed: DecisionLocalReconstructedRoot,
    root: LearnedLeafFixedRootSelection,
    split: DecisionLocalSplit,
    signature: String,
    replicate: Int,
    search: InformationSetSearch,
): DecisionLocalTerminalSample {
    val coordinate = root.schedule.coordinates[replicate % root.schedule.coordinates.size]
    val futureSeed = ComponentSeeds.derive(DECISION_LOCAL_CONTINUATION_SEED_RULE, root.id, split.name, replicate)
    val continuationSeed = ComponentSeeds.derive(reconstructed.liveSearchSeed, split.name, replicate, "terminal-continuation")
    val child = reconstructed.worlds[coordinate.rootParticleIndex].forkForHypotheticalSearch(futureSeed)
    val choice = child.expandChoices().candidates.single { it.signature == signature }
    require(child.stepWithReplayTrace(choice).result.accepted)
    val started = System.nanoTime()
    var decisions = 0
    val payoff = child.terminalPayoff(root.rootActor) ?: search.continueFirstUnvisitedEdgeToTerminal(
        child, root.rootActor, continuationSeed, coordinate.productionSimulationIndex,
        childDepth = 1, maximumContinuationPolicyDecisions = DECISION_LOCAL_MAX_CONTINUATION_DECISIONS,
    ).also { decisions = it.policyDecisions }.payoff
    return DecisionLocalTerminalSample(
        replicate, coordinate.rootParticleIndex, futureSeed, continuationSeed, payoff, decisions,
        (System.nanoTime() - started) / 1_000_000.0,
    )
}

internal class DecisionLocalEvidenceMaterializer(
    private val pilotDirectory: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val deckManifest: DeckManifest,
    private val pilot: LearnedLeafFixedRootPilotBinding,
    private val historical: HistoricalOutcomeValueDiagnosticCheckpoint,
    private val sampleObserver: (DecisionLocalTerminalSample) -> Unit = {},
) {
    constructor(pilotDirectory: Path, registry: com.wingedsheep.engine.registry.CardRegistry,
        deckManifest: DeckManifest, manifest: DecisionLocalRootManifest,
        historical: HistoricalOutcomeValueDiagnosticCheckpoint) :
        this(pilotDirectory, registry, deckManifest, manifest.pilot, historical)
    private val knownDecks = mapOf("p0" to deckManifest.mainDeck, "p1" to deckManifest.mainDeck)
    private val opponent = defaultMonoRedOpponentPolicy()
    private val failedGlobal = historical.diagnosticEvaluator()
    private val controlParameters = pilot.control.composition.parameters(
        20260825L, pilot.control.leaf,
    )
    private val terminalSearch = SearchTeacherSearchFactory.create(controlParameters.searchConfig(), opponent)
    private val featureParameters = controlParameters.copy(
        leaf = LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_VISIBLE_V2)
    )

    fun materialize(
        assignment: DecisionLocalRootAssignment,
        primaryReplicates: Int,
        independentReplicates: Int,
    ): DecisionLocalRootEvidence = materializeRoot(
        assignment.root, assignment.split, primaryReplicates, independentReplicates,
    )

    fun materializeChallenge(
        root: LearnedLeafFixedRootSelection,
        primaryReplicates: Int,
    ): DecisionLocalRootEvidence = materializeRoot(root, DecisionLocalSplit.CHALLENGE, primaryReplicates, 0)

    private fun materializeRoot(
        root: LearnedLeafFixedRootSelection,
        split: DecisionLocalSplit,
        primaryReplicates: Int,
        independentReplicates: Int,
    ): DecisionLocalRootEvidence {
        require(primaryReplicates >= DECISION_LOCAL_PRIMARY_REPLICATES)
        val reconstructed = reconstructDecisionLocalRoot(
            pilotDirectory, registry, deckManifest, pilot, root, failedGlobal,
        )
        val liveSearchSeed = reconstructed.liveSearchSeed
        val candidateEvidence = root.candidateSignatures.map { signature ->
            val featureAccumulator = linkedMapOf<String, Double>()
            var terminalOffset = 0.0
            var terminalWorlds = 0
            var cheap = 0.0
            var global = 0.0
            val featureDigests = mutableListOf<String>()
            root.schedule.coordinates.forEachIndexed { scheduleIndex, coordinate ->
                val base = reconstructed.worlds[coordinate.rootParticleIndex]
                val child = base.fork() as ArgentumSearchWorld
                val choice = child.expandChoices().candidates.single { it.signature == signature }
                val step = child.stepWithReplayTrace(choice)
                require(step.result.accepted)
                val immediate = child.terminalPayoff(root.rootActor)
                if (immediate != null) {
                    terminalWorlds++
                    terminalOffset += immediate / DECISION_LOCAL_FEATURE_WORLDS
                    cheap += immediate / DECISION_LOCAL_FEATURE_WORLDS
                    global += immediate / DECISION_LOCAL_FEATURE_WORLDS
                    featureDigests += "terminal:$immediate"
                } else {
                    val capture = CapturingLeafEvaluator()
                    val search = SearchTeacherSearchFactory.create(
                        featureParameters.searchConfig(), opponent, informationEvaluator = capture,
                    )
                    val settlement = search.settleFirstUnvisitedEdge(
                        child, root.rootActor, liveSearchSeed,
                        coordinate.productionSimulationIndex, childDepth = 1,
                    )
                    val information = capture.captured
                    if (information == null) {
                        require(settlement.origin == SearchSettlementOrigin.TERMINAL_PAYOFF)
                        terminalWorlds++
                        terminalOffset += settlement.backedValue / DECISION_LOCAL_FEATURE_WORLDS
                        cheap += settlement.backedValue / DECISION_LOCAL_FEATURE_WORLDS
                        global += settlement.backedValue / DECISION_LOCAL_FEATURE_WORLDS
                        featureDigests += "boundary-terminal:${settlement.backedValue}"
                    } else {
                        val features = LearnedOutcomeValueFeatureCompiler.compile(information, root.rootActor)
                        features.values.forEach { (key, value) ->
                            featureAccumulator[key] = featureAccumulator.getOrDefault(key, 0.0) +
                                value / DECISION_LOCAL_FEATURE_WORLDS
                        }
                        cheap += MonoRedInformationEvaluator.evaluate(information, root.rootActor) /
                            DECISION_LOCAL_FEATURE_WORLDS
                        global += failedGlobal.evaluate(information, root.rootActor) /
                            DECISION_LOCAL_FEATURE_WORLDS
                        featureDigests += PolicyJson.sha256(
                            features.values.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
                        )
                    }
                }
                require(scheduleIndex == coordinate.productionSimulationIndex)
            }
            val primary = mutableListOf<Double>()
            val independent = mutableListOf<Double>()
            var policyDecisions = 0
            var runtimeNanos = 0L
            repeat(primaryReplicates + independentReplicates) { replicate ->
                val sample = continueDecisionLocalCandidate(reconstructed, root, split, signature, replicate, terminalSearch)
                sampleObserver(sample)
                policyDecisions += sample.policyDecisions
                runtimeNanos += (sample.elapsedMillis * 1_000_000.0).toLong()
                if (replicate < primaryReplicates) primary += sample.payoff else independent += sample.payoff
            }
            DecisionLocalCandidateEvidence(
                signature = signature,
                featureWorlds = DECISION_LOCAL_FEATURE_WORLDS,
                nonterminalFeatureWorlds = DECISION_LOCAL_FEATURE_WORLDS - terminalWorlds,
                terminalFeatureWorlds = terminalWorlds,
                terminalFeatureOffset = terminalOffset,
                featureMeans = featureAccumulator.filterValues { it != 0.0 }.toSortedMap(),
                featureScheduleDigest = researchSha256(featureDigests.joinToString("\n")),
                cheapHeuristicScore = cheap,
                failedGlobalModelScore = global,
                primaryTerminalPayoffs = primary,
                independentTerminalPayoffs = independent,
                continuationPolicyDecisions = policyDecisions,
                continuationRuntimeMillis = runtimeNanos / 1_000_000.0,
            )
        }
        return DecisionLocalRootEvidence(
            rootId = root.id,
            split = split,
            pairIndex = root.pairIndex,
            decisionFamily = root.sourceDecisionFamily,
            phase = root.sourcePhase,
            turnNumber = root.turnNumber,
            rootActor = root.rootActor,
            representedKnowledgeCategory = root.representedKnowledgeCategory,
            candidateFamilyDigest = root.candidateFamilyDigest,
            productionScheduleDigest = root.schedule.scheduleDigest,
            primaryReplicates = primaryReplicates,
            independentReplicates = independentReplicates,
            candidates = candidateEvidence,
        )
    }
}

@Serializable
internal data class DecisionLocalPreflightReport(
    val schemaVersion: Int = 1,
    val protocol: String = "decision-local-throughput-preflight-v1",
    val rootManifestId: String,
    val rootsAttempted: Int,
    val candidateFamiliesCompleted: Int,
    val candidatesCompleted: Int,
    val terminalContinuationsCompleted: Int,
    val failures: List<String>,
    val elapsedMillis: Double,
    val meanContinuationMillis: Double?,
    val estimatedPrimaryHoursAtEightReplicates: Double?,
    val outcomeValuesInspectedOrSerialized: Boolean = false,
)

@Serializable
internal data class DecisionLocalModelCheckpoint(
    val schemaVersion: Int = 1,
    val modelId: String,
    val featureSchema: String,
    val objective: String,
    val regularization: Double,
    val solver: String,
    val tolerance: Double,
    val iterationLimit: Int,
    val iterations: Int,
    val stoppingReason: String,
    val maximumKktResidual: Double,
    val featureCount: Int,
    val weights: Map<String, Double>,
) {
    init {
        require(schemaVersion == 1 && featureSchema == LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1)
        require(modelId == "CONTENT_ID_OMITTED" || modelId == decisionLocalModelId(this))
    }
    fun score(candidate: DecisionLocalCandidateEvidence): Double = candidate.terminalFeatureOffset +
        candidate.featureMeans.entries.sumOf { (key, value) -> value * weights.getOrDefault(key, 0.0) }
}

private fun decisionLocalModelId(model: DecisionLocalModelCheckpoint): String =
    "decision-local-linear-v1-sha256:" + researchSha256(
        evidenceJson.encodeToString(model.copy(modelId = "CONTENT_ID_OMITTED"))
    )

private data class CenteredDecisionRow(
    val rootId: String,
    val values: Map<String, Double>,
    val label: Double,
    val weight: Double,
)

/** Keep the historical default; diagnostic penalties are recorded in the model's content identity. */
@JvmOverloads
internal fun fitDecisionLocalModel(
    roots: List<DecisionLocalRootEvidence>,
    ridge: Double = DECISION_LOCAL_DEFAULT_RIDGE,
): DecisionLocalModelCheckpoint {
    require(ridge.isFinite() && ridge > 0.0) { "Ridge penalty must be finite and positive" }
    require(roots.isNotEmpty() && roots.all { it.split == DecisionLocalSplit.TRAIN })
    val featureKeys = TreeSet(utf8BytewiseStringComparator).apply {
        roots.flatMap { it.candidates }.forEach { addAll(it.featureMeans.keys) }
    }.toList()
    val featureIndex = featureKeys.withIndex().associate { it.value to it.index }
    val rows = roots.flatMap { root ->
        val candidates = root.candidates
        val rootMeanFeatures = linkedMapOf<String, Double>()
        candidates.forEach { candidate ->
            candidate.featureMeans.forEach { (key, value) ->
                rootMeanFeatures[key] = rootMeanFeatures.getOrDefault(key, 0.0) + value / candidates.size
            }
        }
        val rootMeanTarget = candidates.map { it.primaryMean }.average()
        val rootMeanOffset = candidates.map { it.terminalFeatureOffset }.average()
        candidates.map { candidate ->
            val centered = (candidate.featureMeans.keys + rootMeanFeatures.keys).associateWith { key ->
                candidate.featureMeans.getOrDefault(key, 0.0) - rootMeanFeatures.getOrDefault(key, 0.0)
            }.filterValues { it != 0.0 }
            CenteredDecisionRow(
                root.rootId,
                centered,
                (candidate.primaryMean - rootMeanTarget) -
                    (candidate.terminalFeatureOffset - rootMeanOffset),
                1.0 / roots.size / candidates.size,
            )
        }
    }
    val sparse = rows.map { row ->
        row.values.entries.sortedBy { featureIndex.getValue(it.key) }.map {
            featureIndex.getValue(it.key) to it.value
        }
    }
    val rhs = DoubleArray(featureKeys.size)
    val diagonal = DoubleArray(featureKeys.size) { ridge }
    rows.indices.forEach { rowIndex ->
        val row = rows[rowIndex]
        sparse[rowIndex].forEach { (index, value) ->
            rhs[index] += row.weight * value * row.label
            diagonal[index] += row.weight * value * value
        }
    }
    fun multiply(vector: DoubleArray): DoubleArray {
        val result = DoubleArray(vector.size) { ridge * vector[it] }
        rows.indices.forEach { rowIndex ->
            val dot = sparse[rowIndex].sumOf { (index, value) -> vector[index] * value }
            sparse[rowIndex].forEach { (index, value) -> result[index] += rows[rowIndex].weight * value * dot }
        }
        return result
    }
    fun trueResidual(weights: DoubleArray): DoubleArray {
        val product = multiply(weights)
        return DoubleArray(rhs.size) { rhs[it] - product[it] }
    }
    fun maxAbs(values: DoubleArray): Double = values.maxOfOrNull(::abs) ?: 0.0
    val weights = DoubleArray(featureKeys.size)
    var residual = rhs.copyOf()
    var z = DoubleArray(residual.size) { residual[it] / diagonal[it] }
    var direction = z.copyOf()
    var rz = residual.indices.sumOf { residual[it] * z[it] }
    val cap = max(1, featureKeys.size * 2)
    var iterations = 0
    var stopping = "certified-zero"
    if (maxAbs(residual) > DECISION_LOCAL_TOLERANCE) {
        stopping = "iteration-cap"
        for (iteration in 1..cap) {
            iterations = iteration
            val hd = multiply(direction)
            val curvature = direction.indices.sumOf { direction[it] * hd[it] }
            require(curvature.isFinite() && curvature > 0.0)
            val alpha = rz / curvature
            weights.indices.forEach { index ->
                weights[index] += alpha * direction[index]
                residual[index] -= alpha * hd[index]
            }
            if (iteration == featureKeys.size) residual = trueResidual(weights)
            if (maxAbs(residual) <= DECISION_LOCAL_TOLERANCE) {
                stopping = "kkt-tolerance"
                break
            }
            z = DoubleArray(residual.size) { residual[it] / diagonal[it] }
            val nextRz = residual.indices.sumOf { residual[it] * z[it] }
            val beta = nextRz / rz
            direction.indices.forEach { index -> direction[index] = z[index] + beta * direction[index] }
            rz = nextRz
        }
    }
    val kkt = maxAbs(trueResidual(weights))
    require(kkt <= DECISION_LOCAL_TOLERANCE) {
        "Decision-local PCG did not reach KKT tolerance: $kkt"
    }
    val provisional = DecisionLocalModelCheckpoint(
        modelId = "CONTENT_ID_OMITTED",
        featureSchema = LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1,
        objective = DECISION_LOCAL_MODEL_OBJECTIVE,
        regularization = ridge,
        solver = DECISION_LOCAL_SOLVER,
        tolerance = DECISION_LOCAL_TOLERANCE,
        iterationLimit = cap,
        iterations = iterations,
        stoppingReason = stopping,
        maximumKktResidual = kkt,
        featureCount = featureKeys.size,
        weights = featureKeys.indices.associate { featureKeys[it] to weights[it] }.filterValues { it != 0.0 },
    )
    return provisional.copy(modelId = decisionLocalModelId(provisional))
}

@Serializable
internal data class DecisionLocalSignalSummary(
    val roots: Int,
    val meanSiblingSpread: Double,
    val medianSiblingSpread: Double,
    val meanCandidateStandardError: Double,
    val meanContinuationVariance: Double,
    val meaningfulSpreadRoots: Int,
    val distinguishableBestFromRunnerUpRoots: Int,
    val byDecisionFamily: Map<String, Double>,
    val byGamePhase: Map<String, Double>,
    val sufficientToTrain: Boolean,
)

internal fun decisionLocalSignal(roots: List<DecisionLocalRootEvidence>): DecisionLocalSignalSummary {
    require(roots.isNotEmpty())
    val spreads = roots.map { root ->
        root.candidates.maxOf { it.primaryMean } - root.candidates.minOf { it.primaryMean }
    }
    val standardErrors = roots.flatMap { root -> root.candidates.map { standardError(it.primaryTerminalPayoffs) } }
    val variances = roots.flatMap { root -> root.candidates.map { sampleVariance(it.primaryTerminalPayoffs) } }
    val distinguishable = roots.count { root ->
        val ordered = root.candidates.sortedByDescending { it.primaryMean }
        if (ordered[0].primaryMean == ordered[1].primaryMean) false else {
            val differences = ordered[0].primaryTerminalPayoffs.zip(ordered[1].primaryTerminalPayoffs) { a, b -> a - b }
            differences.average() - 1.96 * standardError(differences) > 0.0
        }
    }
    fun phase(root: DecisionLocalRootEvidence) = when {
        root.turnNumber <= 2 -> "EARLY"
        root.turnNumber <= 5 -> "MIDDLE"
        else -> "LATE"
    }
    val meaningful = spreads.count { it >= decisionLocalGate.meaningfulSpread }
    val meanSe = standardErrors.average()
    val sufficient = meaningful.toDouble() / roots.size >= decisionLocalGate.minimumSignalSpreadFraction &&
        distinguishable.toDouble() / roots.size >= decisionLocalGate.minimumDistinguishableFraction &&
        meanSe <= decisionLocalGate.maximumMeanCandidateStandardError
    return DecisionLocalSignalSummary(
        roots = roots.size,
        meanSiblingSpread = spreads.average(),
        medianSiblingSpread = percentile(spreads, 0.5),
        meanCandidateStandardError = meanSe,
        meanContinuationVariance = variances.average(),
        meaningfulSpreadRoots = meaningful,
        distinguishableBestFromRunnerUpRoots = distinguishable,
        byDecisionFamily = roots.groupBy { it.decisionFamily }.mapValues { (_, group) ->
            group.map { it.candidates.maxOf(DecisionLocalCandidateEvidence::primaryMean) -
                it.candidates.minOf(DecisionLocalCandidateEvidence::primaryMean) }.average()
        }.toSortedMap(),
        byGamePhase = roots.groupBy(::phase).mapValues { (_, group) ->
            group.map { it.candidates.maxOf(DecisionLocalCandidateEvidence::primaryMean) -
                it.candidates.minOf(DecisionLocalCandidateEvidence::primaryMean) }.average()
        }.toSortedMap(),
        sufficientToTrain = sufficient,
    )
}

@Serializable
internal data class DecisionLocalMethodMetrics(
    val method: String,
    val roots: Int,
    val pairwiseOrderingAccuracy: Double,
    val withinRootRankCorrelation: Double,
    val correctBestActionRate: Double,
    val primarySelectedActionRegret: Double,
    val independentSelectedActionRegret: Double,
    val worstRootIndependentRegret: Double,
    val meanPositiveSelectedOptimism: Double,
    val p90PositiveSelectedOptimism: Double,
    val meanPredictedMargin: Double,
    val meanActualIndependentMargin: Double,
    val composite: Double,
    val earlyGameIndependentRegret: Double?,
    val mulliganIndependentRegret: Double?,
    val regretByCandidateCount: Map<Int, Double>,
)

private data class ScoredRoot(
    val root: DecisionLocalRootEvidence,
    val scores: Map<String, Double>,
    val uniform: Boolean = false,
)

private fun decisionLocalKendallTau(first: Map<String, Double>, second: Map<String, Double>): Double {
    require(first.keys == second.keys)
    val keys = first.keys.sortedWith(utf8BytewiseStringComparator)
    var concordance = 0.0
    var comparisons = 0
    for (left in keys.indices) for (right in left + 1 until keys.size) {
        val firstSign = (first.getValue(keys[left]) - first.getValue(keys[right])).compareTo(0.0)
        val secondSign = (second.getValue(keys[left]) - second.getValue(keys[right])).compareTo(0.0)
        if (firstSign != 0) {
            comparisons += 1
            concordance += when {
                secondSign == firstSign -> 1.0
                secondSign == 0 -> 0.0
                else -> -1.0
            }
        }
    }
    return if (comparisons == 0) 0.0 else concordance / comparisons
}

private fun metrics(method: String, scored: List<ScoredRoot>): DecisionLocalMethodMetrics {
    val pairResults = mutableListOf<Double>()
    val rank = mutableListOf<Double>()
    val correctBest = mutableListOf<Double>()
    val primaryRegrets = mutableListOf<Double>()
    val auditRegrets = mutableListOf<Double>()
    val optimism = mutableListOf<Double>()
    val predictedMargins = mutableListOf<Double>()
    val actualMargins = mutableListOf<Double>()
    val perRootAudit = mutableListOf<Pair<DecisionLocalRootEvidence, Double>>()
    scored.forEach { item ->
        val candidates = item.root.candidates
        val actual = candidates.associate { it.signature to it.primaryMean }
        val audit = candidates.associate { it.signature to requireNotNull(it.independentMean) }
        val keys = candidates.map { it.signature }
        for (left in keys.indices) for (right in left + 1 until keys.size) {
            val actualSign = (actual.getValue(keys[left]) - actual.getValue(keys[right])).compareTo(0.0)
            if (actualSign != 0) {
                val predictedSign = (item.scores.getValue(keys[left]) - item.scores.getValue(keys[right])).compareTo(0.0)
                pairResults += when { predictedSign == actualSign -> 1.0; predictedSign == 0 -> 0.5; else -> 0.0 }
            }
        }
        rank += decisionLocalKendallTau(actual, item.scores)
        val actualBest = actual.maxOf { it.value }
        val auditBest = audit.maxOf { it.value }
        val selections = if (item.uniform) keys else listOf(keys.maxWith(
            compareBy<String> { item.scores.getValue(it) }.thenByDescending { it }
        ))
        val primaryRegret = selections.map { actualBest - actual.getValue(it) }.average()
        val auditRegret = selections.map { auditBest - audit.getValue(it) }.average()
        val bestSet = actual.filterValues { it == actualBest }.keys
        correctBest += selections.count { it in bestSet }.toDouble() / selections.size
        primaryRegrets += primaryRegret
        auditRegrets += auditRegret
        perRootAudit += item.root to auditRegret
        val centeredScores = item.scores.mapValues { it.value - item.scores.values.average() }
        val centeredAudit = audit.mapValues { it.value - audit.values.average() }
        val selectedOptimism = selections.map {
            max(0.0, centeredScores.getValue(it) - centeredAudit.getValue(it))
        }.average()
        optimism += selectedOptimism
        val predictedMargin = selections.map { selected ->
            centeredScores.getValue(selected) - centeredScores.filterKeys { it != selected }.maxOf { it.value }
        }.average()
        val actualMargin = selections.map { selected ->
            centeredAudit.getValue(selected) - centeredAudit.filterKeys { it != selected }.maxOf { it.value }
        }.average()
        predictedMargins += predictedMargin
        actualMargins += actualMargin
    }
    val pairwise = pairResults.average()
    val regret = auditRegrets.average()
    val p90 = percentile(optimism, 0.9)
    return DecisionLocalMethodMetrics(
        method, scored.size, pairwise, rank.average(), correctBest.average(), primaryRegrets.average(), regret,
        auditRegrets.max(), optimism.average(), p90, predictedMargins.average(), actualMargins.average(),
        pairwise - regret - p90,
        perRootAudit.filter { it.first.turnNumber <= 2 }.map { it.second }.takeIf { it.isNotEmpty() }?.average(),
        perRootAudit.filter { it.first.decisionFamily == SemanticOperationFamily.MULLIGAN.name }
            .map { it.second }.takeIf { it.isNotEmpty() }?.average(),
        perRootAudit.groupBy { it.first.candidates.size }.mapValues { (_, values) -> values.map { it.second }.average() }.toSortedMap(),
    )
}

@Serializable
internal data class DecisionLocalGateResult(
    val passed: Boolean,
    val reasons: List<String>,
)

internal fun evaluateGate(metrics: List<DecisionLocalMethodMetrics>): DecisionLocalGateResult {
    val learned = metrics.single { it.method == "decision-local-model" }
    val baselines = metrics.filter { it.method != learned.method }
    val reasons = buildList {
        baselines.forEach { baseline ->
            if (learned.pairwiseOrderingAccuracy < baseline.pairwiseOrderingAccuracy +
                decisionLocalGate.minimumPairwiseAccuracyAdvantage) {
                add("pairwise advantage over ${baseline.method} is below ${decisionLocalGate.minimumPairwiseAccuracyAdvantage}")
            }
            if (learned.independentSelectedActionRegret > baseline.independentSelectedActionRegret -
                decisionLocalGate.minimumMeanIndependentRegretAdvantage) {
                add("independent-regret advantage over ${baseline.method} is below ${decisionLocalGate.minimumMeanIndependentRegretAdvantage}")
            }
            if (learned.composite < baseline.composite + decisionLocalGate.minimumCompositeAdvantage) {
                add("deployment composite advantage over ${baseline.method} is below ${decisionLocalGate.minimumCompositeAdvantage}")
            }
            if (learned.p90PositiveSelectedOptimism > baseline.p90PositiveSelectedOptimism +
                decisionLocalGate.maximumOptimismP90Disadvantage) {
                add("p90 optimism is materially worse than ${baseline.method}")
            }
        }
    }
    return DecisionLocalGateResult(reasons.isEmpty(), reasons)
}

@Serializable
internal enum class DecisionLocalConclusion {
    A, B, C, D, E, STAGE_ONE_SIGNAL_SUFFICIENT, STAGE_ONE_SIGNAL_INSUFFICIENT, STAGE_ONE_INCOMPLETE,
}

internal fun decisionLocalStageOneConclusion(
    signal: DecisionLocalSignalSummary?,
    assignedRoots: Int,
): DecisionLocalConclusion = when {
    signal == null || signal.roots != assignedRoots -> DecisionLocalConclusion.STAGE_ONE_INCOMPLETE
    signal.sufficientToTrain -> DecisionLocalConclusion.STAGE_ONE_SIGNAL_SUFFICIENT
    else -> DecisionLocalConclusion.STAGE_ONE_SIGNAL_INSUFFICIENT
}

@Serializable
internal data class DecisionLocalExperimentReport(
    val schemaVersion: Int = 1,
    val protocol: String = DECISION_LOCAL_EXPERIMENT_PROTOCOL,
    val researchRunIdentity: String,
    val generatedAtUtc: String,
    val treatmentMtgalliumSha: String,
    val historicalRootSourceMtgalliumSha: String,
    val argentumSha: String,
    val rootManifestId: String,
    val scientificEvidenceIdentity: String,
    val targetSemantics: String,
    val actionProfile: String,
    val beliefConfiguration: String,
    val outerSearchConfiguration: String,
    val simulatedOpponentModelIdentity: String,
    val rootContinuationPolicyIdentity: String,
    val opponentContinuationPolicyIdentity: String,
    val featureSchema: String,
    val continuationSeedSchedule: String,
    val primaryRoots: Int,
    val splitCounts: Map<String, Int>,
    val admittedRoots: Int,
    val excludedRoots: Int,
    val exclusionReasons: Map<String, Int>,
    val terminalContinuations: Int,
    val computeMillis: Double,
    val signal: DecisionLocalSignalSummary?,
    val model: DecisionLocalModelCheckpoint?,
    val testMetrics: List<DecisionLocalMethodMetrics>,
    val optimisticSelectionGate: DecisionLocalGateResult?,
    val challengeMetrics: List<DecisionLocalMethodMetrics>,
    val conclusion: DecisionLocalConclusion,
    val conclusionText: String,
    val limitations: List<String>,
    val nextOwnerDecision: String,
    val sourceProvenance: ResearchRunProvenance? = null,
    val researchRunBindings: ResearchRunBindings? = null,
    val excludedRootFailures: List<String> = emptyList(),
)

internal class DecisionLocalExperimentRunner(
    private val repositoryRoot: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val deckManifest: DeckManifest,
) {
    fun preflight(
        pilotDirectory: Path,
        rootManifestPath: Path,
        corpusDirectory: Path,
        gateDirectory: Path,
        output: Path,
    ): DecisionLocalPreflightReport {
        require(!Files.exists(output))
        val manifest = loadDecisionLocalRootManifest(rootManifestPath)
        val historical = HistoricalOutcomeValueDiagnosticCheckpoint.load(corpusDirectory, gateDirectory)
        val materializer = DecisionLocalEvidenceMaterializer(pilotDirectory, registry, deckManifest, manifest, historical)
        // Throughput verification must not materialize held-out terminal outcomes.
        val developmentRoots = manifest.assignments.filter { it.split != DecisionLocalSplit.TEST }
        val assignments = listOfNotNull(
            developmentRoots.minByOrNull { it.root.candidateSignatures.size },
            developmentRoots.sortedBy { it.root.candidateSignatures.size }
                .getOrNull(developmentRoots.size / 2),
            developmentRoots.maxByOrNull { it.root.candidateSignatures.size },
        ).distinctBy { it.root.id }
        val started = System.nanoTime()
        var candidates = 0
        var continuations = 0
        val failures = mutableListOf<String>()
        var completedFamilies = 0
        assignments.forEach { assignment ->
            runCatching { materializer.materialize(assignment, DECISION_LOCAL_PRIMARY_REPLICATES, 0) }
                .onSuccess {
                    completedFamilies++
                    candidates += it.candidates.size
                    continuations += it.candidates.sumOf { candidate -> candidate.primaryTerminalPayoffs.size }
                }.onFailure { failures += "${assignment.root.id}:${it::class.simpleName}:${it.message}" }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        val mean = if (continuations > 0) elapsed / continuations else null
        val estimated = mean?.let { it * manifest.assignments.sumOf { a ->
            a.root.candidateSignatures.size * if (a.split == DecisionLocalSplit.TEST) 16 else 8
        } / 3_600_000.0 }
        val report = DecisionLocalPreflightReport(
            rootManifestId = manifest.manifestId,
            rootsAttempted = assignments.size,
            candidateFamiliesCompleted = completedFamilies,
            candidatesCompleted = candidates,
            terminalContinuationsCompleted = continuations,
            failures = failures,
            elapsedMillis = elapsed,
            meanContinuationMillis = mean,
            estimatedPrimaryHoursAtEightReplicates = estimated,
        )
        Files.createDirectories(output)
        ResearchRunFiles.atomicWrite(output.resolve("preflight.json"), evidenceJson.encodeToString(report) + "\n")
        val identity = ResearchRunBindings(
            protocol = report.protocol,
            material = mapOf("root-manifest" to manifest.manifestId, "treatment-source" to ResearchRunProvenance.capture(repositoryRoot).outerCommit),
        ).identity
        ResearchRunArtifacts(output, identity).also { it.register("preflight.json"); it.finalize() }
        return report
    }

    fun run(
        pilotDirectory: Path,
        rootManifestPath: Path,
        corpusDirectory: Path,
        gateDirectory: Path,
        challengeManifestPaths: List<Path>,
        output: Path,
        progressPath: Path? = null,
        signalOnly: Boolean = false,
    ): DecisionLocalExperimentReport {
        val started = System.nanoTime()
        val provenance = ResearchRunProvenance.capture(repositoryRoot)
        provenance.requireReady()
        require(!provenance.outerDirty && !provenance.engineDirty) { "Experiment requires committed clean source" }
        val manifest = loadDecisionLocalRootManifest(rootManifestPath)
        val historical = HistoricalOutcomeValueDiagnosticCheckpoint.load(corpusDirectory, gateDirectory)
        require(manifest.argentumCommit == provenance.checkedOutArgentumCommit)
        require(!signalOnly || challengeManifestPaths.isEmpty()) { "Stage one does not admit challenge panels" }
        require(!signalOnly || !Files.exists(output)) { "Stage one requires a fresh output directory" }
        val bindings = ResearchRunBindings(
            protocol = if (signalOnly) DECISION_LOCAL_SIGNAL_PROTOCOL else DECISION_LOCAL_EXPERIMENT_PROTOCOL,
            material = mapOf(
                "treatment-source" to provenance.outerCommit,
                "root-manifest" to manifest.manifestId,
                "historical-root-source" to manifest.historicalRootSourceCommit,
                "argentum" to manifest.argentumCommit,
                "action-profile" to manifest.pilot.control.composition.actionSpaceProfile.profileId,
                "belief" to "CONSISTENCY_ONLY_V1:SEQUENTIAL_B_V1:8",
                "outer-search" to "8x64:max-policy-decisions-32:exploration-1.4:reuse-off",
                "simulated-opponent" to "mono-red-mixture-70-10-10-10-v2",
                "root-continuation" to SearchTeacherSearchFactory.rootRolloutPolicy().behaviorSpecification.toString(),
                "opponent-continuation" to SearchTeacherSearchFactory.opponentRolloutPolicy().behaviorSpecification.toString(),
                "features" to LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1,
                "model" to "$DECISION_LOCAL_MODEL_OBJECTIVE:$DECISION_LOCAL_SOLVER:ridge=$DECISION_LOCAL_DEFAULT_RIDGE:tol=$DECISION_LOCAL_TOLERANCE",
                "split" to DECISION_LOCAL_SPLIT_RULE,
                "continuation-seeds" to DECISION_LOCAL_CONTINUATION_SEED_RULE,
                "feature-schedule" to DECISION_LOCAL_FEATURE_SCHEDULE,
                "gate" to researchSha256(evidenceJson.encodeToString(decisionLocalGate)),
            ),
        )
        Files.createDirectories(output)
        val materializer = DecisionLocalEvidenceMaterializer(pilotDirectory, registry, deckManifest, manifest, historical)
        val admitted = mutableListOf<DecisionLocalRootEvidence>()
        val exclusions = mutableListOf<String>()
        val totalPrimary = manifest.assignments.count { !signalOnly || it.split != DecisionLocalSplit.TEST }
        var completed = 0
        fun progress(phase: String, detail: String) {
            val path = progressPath ?: return
            val json = """{"schemaVersion":1,"updatedAt":"${Instant.now()}","completed":$completed,"total":$totalPrimary,"unit":"roots","phase":"$phase","detail":"$detail"}"""
            ResearchRunFiles.atomicWrite(path, json + "\n")
        }
        fun loadOrRun(assignment: DecisionLocalRootAssignment, primary: Int, independent: Int): DecisionLocalRootEvidence? {
            val relative = "roots/${assignment.split.name.lowercase()}-${assignment.root.id}.json"
            val path = output.resolve(relative)
            return runCatching {
                val evidence = if (Files.exists(path)) {
                    val envelope = ResearchRunCheckpoints.load(path)
                    require(envelope.researchRunIdentity == bindings.identity && envelope.payloadSchema == DECISION_LOCAL_ROOT_CHECKPOINT_SCHEMA)
                    evidenceJson.decodeFromString<DecisionLocalRootEvidence>(envelope.payload().decodeToString())
                } else {
                    materializer.materialize(assignment, primary, independent).also { rootEvidence ->
                        ResearchRunCheckpoints.persist(
                            path, bindings.identity, DECISION_LOCAL_ROOT_CHECKPOINT_SCHEMA,
                            assignment.root.pairIndex.toLong(), evidenceJson.encodeToString(rootEvidence).encodeToByteArray(),
                        )
                    }
                }
                require(evidence.rootId == assignment.root.id && evidence.split == assignment.split)
                evidence
            }.getOrElse { failure ->
                exclusions += "${assignment.root.id}:${failure::class.simpleName}:${failure.message}"
                null
            }.also {
                completed++
                progress("terminal sibling evidence", "completed ${assignment.root.id}")
            }
        }
        progress("terminal sibling evidence", "starting TRAIN and VALIDATION roots")
        manifest.assignments.filter { it.split != DecisionLocalSplit.TEST }.forEach { assignment ->
            loadOrRun(assignment, DECISION_LOCAL_PRIMARY_REPLICATES, 0)?.let(admitted::add)
        }
        val trainValidation = admitted.filter { it.split in setOf(DecisionLocalSplit.TRAIN, DecisionLocalSplit.VALIDATION) }
        val signal = trainValidation.takeIf { it.isNotEmpty() }?.let(::decisionLocalSignal)
        if (signalOnly) {
            val conclusion = decisionLocalStageOneConclusion(signal, totalPrimary)
            val text = when (conclusion) {
                DecisionLocalConclusion.STAGE_ONE_INCOMPLETE ->
                    "Stage one incomplete: reconstruction or continuation failures prevent a population-level signal conclusion."
                DecisionLocalConclusion.STAGE_ONE_SIGNAL_SUFFICIENT ->
                    "Stage one passes the predeclared eight-replicate label-signal screen; training has not run."
                else -> "Stage one does not pass the predeclared eight-replicate label-signal screen; training has not run."
            }
            val report = terminalReport(
                bindings, provenance, manifest, admitted, exclusions, signal, null, emptyList(), null,
                emptyList(), started, conclusion, text,
                listOf(
                    "Only the 34 TRAIN and six VALIDATION roots are assigned; all ten TEST roots remain unmaterialized.",
                    "Eight matched terminal continuations per candidate are conditional on the frozen finite belief and declared continuation policies.",
                    "The signal screen is descriptive: best/runner-up selection uses the same eight samples and is not a confirmatory confidence test.",
                    "No optimizer update, model checkpoint, TEST evaluation, challenge evaluation, or gameplay treatment was performed.",
                ),
                "Return the terminal-outcome signal and measured cost to the owner before any later stage.",
            ).copy(protocol = DECISION_LOCAL_SIGNAL_PROTOCOL, primaryRoots = totalPrimary)
            finalize(output, report)
            return report
        }
        requireNotNull(signal) { "No terminal root evidence was admitted" }
        if (!signal.sufficientToTrain) {
            val report = terminalReport(
                bindings, provenance, manifest, admitted, exclusions, signal, null, emptyList(), null,
                emptyList(), started, DecisionLocalConclusion.B,
                "B — terminal sibling labels are too noisy at the frozen eight-replicate scale to support the model gate.",
                listOf("The predeclared label-signal gate failed before any model fit or TEST-root materialization."),
                "Do not run a learned-leaf gameplay test; decide whether a separately mandated replication-cost experiment is worthwhile.",
            )
            finalize(output, report)
            return report
        }
        val model = fitDecisionLocalModel(admitted.filter { it.split == DecisionLocalSplit.TRAIN })
        ResearchRunFiles.atomicWrite(output.resolve("model.json"), evidenceJson.encodeToString(model) + "\n")
        progress("frozen model", "model ${model.modelId} written before TEST materialization")
        manifest.assignments.filter { it.split == DecisionLocalSplit.TEST }.forEach { assignment ->
            loadOrRun(assignment, DECISION_LOCAL_PRIMARY_REPLICATES, DECISION_LOCAL_TEST_REPLICATES - DECISION_LOCAL_PRIMARY_REPLICATES)
                ?.let(admitted::add)
        }
        val test = admitted.filter { it.split == DecisionLocalSplit.TEST }
        val testMetrics = if (test.size == 10) evaluateMethods(test, model) else emptyList()
        val gate = testMetrics.takeIf { it.isNotEmpty() }?.let(::evaluateGate)
        val challenges = if (testMetrics.isNotEmpty()) {
            challengeManifestPaths.flatMap { path ->
                val panel = readLearnedLeafFixedRootManifest(path).manifest.requireComplete()
                require(panel.pilot.runIdentity == manifest.pilot.runIdentity)
                panel.roots.mapNotNull { root ->
                    runCatching { materializer.materializeChallenge(root, DECISION_LOCAL_PRIMARY_REPLICATES) }
                        .onFailure { exclusions += "challenge:${root.id}:${it::class.simpleName}:${it.message}" }
                        .getOrNull()
                }
            }
        } else emptyList()
        val challengeMetrics = challenges.takeIf { it.isNotEmpty() }?.let { evaluateMethodsWithoutAudit(it, model) }.orEmpty()
        val learned = testMetrics.singleOrNull { it.method == "decision-local-model" }
        val heuristic = testMetrics.singleOrNull { it.method == "cheap-visible-heuristic" }
        val (conclusion, text, next) = when {
            test.size != 10 -> Triple(
                DecisionLocalConclusion.E,
                "E — remaining ambiguity: the frozen TEST population was not completely admitted.",
                "Repair only the recorded reconstruction or continuation validity defect before interpreting the gate.",
            )
            gate?.passed == true -> Triple(
                DecisionLocalConclusion.A,
                "A — decision-local model passes the predeclared offline deployment gate.",
                "The owner may specify one narrow future learned-leaf gameplay treatment; none was launched here.",
            )
            learned != null && heuristic != null &&
                heuristic.pairwiseOrderingAccuracy >= learned.pairwiseOrderingAccuracy &&
                heuristic.independentSelectedActionRegret <= learned.independentSelectedActionRegret -> Triple(
                DecisionLocalConclusion.D,
                "D — the existing cheap visible-information heuristic equals or beats the learned model on ranking and independent selected-action regret.",
                "Do not promote a learned leaf; retain the cheap heuristic unless a new owner-level question is posed.",
            )
            else -> Triple(
                DecisionLocalConclusion.C,
                "C — sibling terminal outcomes contain usable signal, but the predetermined simple decision-local model fails the deployment gate.",
                "Do not train a larger model automatically; decide whether feature/model-form localization merits a separate bounded diagnostic.",
            )
        }
        val report = terminalReport(
            bindings, provenance, manifest, admitted, exclusions, signal, model, testMetrics, gate,
            challengeMetrics, started, conclusion, text,
            listOf(
                "The primary population contains one root per retained paired-game lineage (50 roots total).",
                "Eight primary terminal continuations per candidate limit per-root precision; TEST uses eight additional independent continuations.",
                "The frozen Mono-Red deck, action profile, and pinned Argentum revision bound the scope.",
            ), next,
        )
        finalize(output, report)
        return report
    }

    private fun evaluateMethods(test: List<DecisionLocalRootEvidence>, model: DecisionLocalModelCheckpoint): List<DecisionLocalMethodMetrics> {
        val modelScores = test.map { root -> ScoredRoot(root, root.candidates.associate { it.signature to model.score(it) }) }
        val constant = test.map { root -> ScoredRoot(root, root.candidates.associate { it.signature to 0.0 }) }
        val uniform = test.map { root -> ScoredRoot(root, root.candidates.associate { it.signature to 0.0 }, uniform = true) }
        val cheap = test.map { root -> ScoredRoot(root, root.candidates.associate { it.signature to it.cheapHeuristicScore }) }
        val global = test.map { root -> ScoredRoot(root, root.candidates.associate { it.signature to it.failedGlobalModelScore }) }
        val shuffled = modelScores.map { scored ->
            val keys = scored.scores.keys.sorted()
            val shifted = keys.indices.associate { index -> keys[index] to scored.scores.getValue(keys[(index + 1) % keys.size]) }
            ScoredRoot(scored.root, shifted)
        }
        return listOf(
            metrics("decision-local-model", modelScores), metrics("constant", constant),
            metrics("within-root-shuffled", shuffled), metrics("uniform-action", uniform),
            metrics("cheap-visible-heuristic", cheap), metrics("failed-global-outcome-model", global),
        )
    }

    private fun evaluateMethodsWithoutAudit(
        roots: List<DecisionLocalRootEvidence>,
        model: DecisionLocalModelCheckpoint,
    ): List<DecisionLocalMethodMetrics> {
        val promoted = roots.map { root ->
            root.copy(candidates = root.candidates.map { candidate ->
                candidate.copy(independentTerminalPayoffs = candidate.primaryTerminalPayoffs)
            })
        }
        return evaluateMethods(promoted, model)
    }

    private fun terminalReport(
        bindings: ResearchRunBindings,
        provenance: ResearchRunProvenance,
        manifest: DecisionLocalRootManifest,
        admitted: List<DecisionLocalRootEvidence>,
        exclusions: List<String>,
        signal: DecisionLocalSignalSummary?,
        model: DecisionLocalModelCheckpoint?,
        metrics: List<DecisionLocalMethodMetrics>,
        gate: DecisionLocalGateResult?,
        challenge: List<DecisionLocalMethodMetrics>,
        started: Long,
        conclusion: DecisionLocalConclusion,
        conclusionText: String,
        limitations: List<String>,
        next: String,
    ): DecisionLocalExperimentReport {
        val continuations = admitted.sumOf { root -> root.candidates.sumOf {
            it.primaryTerminalPayoffs.size + it.independentTerminalPayoffs.size
        } }
        return DecisionLocalExperimentReport(
            researchRunIdentity = bindings.identity,
            generatedAtUtc = Instant.now().toString(),
            treatmentMtgalliumSha = provenance.outerCommit,
            historicalRootSourceMtgalliumSha = manifest.historicalRootSourceCommit,
            argentumSha = manifest.argentumCommit,
            rootManifestId = manifest.manifestId,
            scientificEvidenceIdentity = bindings.identity,
            targetSemantics = "actual terminal payoff from the original root player's perspective after one candidate action and subsequent play by the fixed production root/opponent rollout policies",
            actionProfile = manifest.pilot.control.composition.actionSpaceProfile.profileId,
            beliefConfiguration = "8 particles; CONSISTENCY_ONLY_V1; SEQUENTIAL_B_V1",
            outerSearchConfiguration = "canonical 8x64, maxPolicyDecisions=32, exploration=1.4, tree reuse disabled",
            simulatedOpponentModelIdentity = "mono-red-mixture-70-10-10-10-v2 (leaf-boundary quiescence only; not continuation-label policy)",
            rootContinuationPolicyIdentity = SearchTeacherSearchFactory.rootRolloutPolicy().behaviorSpecification.toString(),
            opponentContinuationPolicyIdentity = SearchTeacherSearchFactory.opponentRolloutPolicy().behaviorSpecification.toString(),
            featureSchema = LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1,
            continuationSeedSchedule = DECISION_LOCAL_CONTINUATION_SEED_RULE,
            primaryRoots = manifest.assignments.size,
            splitCounts = manifest.assignments.groupingBy { it.split.name }.eachCount().toSortedMap(),
            admittedRoots = admitted.count { it.split != DecisionLocalSplit.CHALLENGE },
            excludedRoots = exclusions.count { !it.startsWith("challenge:") },
            exclusionReasons = exclusions.groupingBy { it.substringBefore(':') }.eachCount().toSortedMap(),
            terminalContinuations = continuations,
            computeMillis = (System.nanoTime() - started) / 1_000_000.0,
            signal = signal,
            model = model,
            testMetrics = metrics,
            optimisticSelectionGate = gate,
            challengeMetrics = challenge,
            conclusion = conclusion,
            conclusionText = conclusionText,
            limitations = limitations,
            nextOwnerDecision = next,
            sourceProvenance = provenance,
            researchRunBindings = bindings,
            excludedRootFailures = exclusions.toList(),
        )
    }

    private fun finalize(output: Path, report: DecisionLocalExperimentReport) {
        ResearchRunFiles.atomicWrite(output.resolve("report.json"), evidenceJson.encodeToString(report) + "\n")
        val markdown = buildString {
            append("# Decision-local sibling terminal-outcome experiment\n\n")
            append("**Conclusion: ${report.conclusionText}**\n\n")
            append("- Evidence: `${report.scientificEvidenceIdentity}`\n")
            append("- Treatment SHA: `${report.treatmentMtgalliumSha}`\n")
            append("- Argentum: `${report.argentumSha}`\n")
            append("- Primary roots: ${report.admittedRoots}/${report.primaryRoots}; exclusions: ${report.excludedRoots}\n")
            append("- Terminal continuations: ${report.terminalContinuations}\n")
            append("- Label spread: mean ${report.signal?.meanSiblingSpread}, median ${report.signal?.medianSiblingSpread}\n")
            append("- Distinguishable best/runner-up roots: ${report.signal?.distinguishableBestFromRunnerUpRoots}/${report.signal?.roots}\n\n")
            if (report.testMetrics.isNotEmpty()) {
                append("| Method | Pairwise | Independent regret | Worst regret | P90 optimism | Composite |\n")
                append("|---|---:|---:|---:|---:|---:|\n")
                report.testMetrics.forEach {
                    append("| ${it.method} | ${it.pairwiseOrderingAccuracy} | ${it.independentSelectedActionRegret} | ${it.worstRootIndependentRegret} | ${it.p90PositiveSelectedOptimism} | ${it.composite} |\n")
                }
            }
            append("\nNext owner decision: ${report.nextOwnerDecision}\n")
        }
        ResearchRunFiles.atomicWrite(output.resolve("report.md"), markdown)
        val artifacts = ResearchRunArtifacts(output, report.researchRunIdentity)
        Files.createDirectories(output.resolve("roots"))
        Files.walk(output.resolve("roots")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { artifacts.register(output.relativize(it).toString()) }
        }
        if (Files.exists(output.resolve("model.json"))) artifacts.register("model.json")
        artifacts.register("report.json")
        artifacts.register("report.md")
        artifacts.finalize()
        ResearchRunArtifacts.loadAndVerify(output, report.researchRunIdentity)
    }
}

private fun sampleVariance(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    return values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
}

private fun standardError(values: List<Double>): Double = sqrt(sampleVariance(values) / values.size)
