package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.state.GameState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.infoset.core.InformationSetSearch
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SearchSettlementOrigin
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueEvaluator
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatureCompiler
import org.mtgallium.agent.searchteacher.MonoRedInformationEvaluator
import org.mtgallium.agent.searchteacher.PolicyCompressionConfig
import org.mtgallium.agent.searchteacher.SearchReuseConfig
import org.mtgallium.agent.searchteacher.SEARCH_TEACHER_UNPROFILED_RUNTIME_ID
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunFiles
import org.mtgallium.research.run.researchSha256

/**
 * A source-prepared diagnostic, not an arena, corpus producer, or search implementation.
 *
 * Its primary observation is a direct sibling table: every source-root candidate is applied once
 * for every frozen schedule coordinate. Both treatments are settled through the authoritative
 * production first-unvisited-edge seam; search-tree visit allocation is intentionally outside
 * this protocol.
 */
internal const val LEARNED_LEAF_FIXED_ROOT_PROTOCOL = "learned-leaf-fixed-root-cross-evaluation-v2"
internal const val LEARNED_LEAF_FIXED_ROOT_SOURCE_COMMIT = "5e5af940bd8e885e78d3d7d708130e8d85ace7be"
internal const val LEARNED_LEAF_FIXED_ROOT_ANALYSIS =
    "learned-leaf-fixed-root-cross-evaluation-v2:direct-sibling-first-unvisited-edge-v2-raw-preclip"
internal const val LEARNED_LEAF_FIXED_ROOT_STUB_SHA256 =
    "7525ee4b3ad4eeb1e29835ba8523ce44a106d2038ba6948b9cbfc58bd3bcf929"
internal const val LEARNED_LEAF_FIRST_DIVERGENCE_MULLIGAN_STUB_SHA256 =
    "103280a84657c97a6ac838a1654a32c3d5b9dace39e6c279a34a2d2492c8dff3"
private val learnedLeafFixedRootStubSha256Allowlist = setOf(
    LEARNED_LEAF_FIXED_ROOT_STUB_SHA256,
    LEARNED_LEAF_FIRST_DIVERGENCE_MULLIGAN_STUB_SHA256,
)

@Serializable
internal data class LearnedLeafFixedRootSelectionStub(
    val stubSchemaVersion: Int,
    val state: String,
    val selectionWasResultBlind: Boolean,
    val pilot: LearnedLeafFixedRootStubPilot,
    val missingReconstructionOwnedFields: List<String>,
    val selectionRule: String,
    val roots: List<LearnedLeafFixedRootStubRoot>,
) { init { require(stubSchemaVersion == 1 && state == "INCOMPLETE_RECONSTRUCTION_STUB" && selectionWasResultBlind) } }
@Serializable internal data class LearnedLeafFixedRootStubPilot(
    val runIdentity: String, val mtgalliumSourceCommit: String, val argentumCommit: String,
    val manifestSha256: String, val reportSha256: String, val checkpointPayloadSha256: String,
    val trainingRunIdentity: String, val corpusIdentity: String, val replayBaseSeed: Long, val policySearchBaseSeed: Long,
)
@Serializable internal data class LearnedLeafFixedRootStubRoot(
    val id: String, val pairIndex: Int, val leg: String, val sourceGameId: String, val decisionIndex: Int,
    val rootActor: String, val sourcePolicyId: String, val sourceDecisionFamily: String, val sourcePhase: String,
    val sourceStep: String, val turnNumber: Int,
    val sourceSeat: String, val representedKnowledgeCategory: String? = null, val selectionReason: String,
    val marginBand: String, val marginMetadata: String, val rootInformationStateDigest: String? = null,
    val semanticPrefixDigest: String, val retainedPreStateDigest: String? = null,
    val replayRelativePath: String, val replaySha256: String,
    val candidateSignatures: List<String>, val candidateFamilyDigest: String,
    val schedule: LearnedLeafFixedRootSchedule? = null, val replayGameSeed: Long,
)

internal data class LoadedLearnedLeafFixedRootSelectionStub(
    val stub: LearnedLeafFixedRootSelectionStub,
    val sha256: String,
)

/** Reads only an exact Director-frozen stub; any byte substitution is refused before decoding. */
internal fun readLearnedLeafFixedRootStub(path: Path): LoadedLearnedLeafFixedRootSelectionStub {
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
        "Fixed-root selection stub must be a regular non-symlink file"
    }
    val bytes = Files.readAllBytes(path)
    val sha256 = researchSha256(bytes)
    require(sha256 in learnedLeafFixedRootStubSha256Allowlist) {
        "Fixed-root selection stub hash mismatch"
    }
    val stub = evidenceJson.decodeFromString<LearnedLeafFixedRootSelectionStub>(bytes.decodeToString()).also {
        require(it.missingReconstructionOwnedFields == listOf(
            "roots[].representedKnowledgeCategory", "roots[].rootInformationStateDigest", "roots[].schedule",
        ))
        require(it.roots.all { root -> root.representedKnowledgeCategory == null && root.rootInformationStateDigest == null && root.schedule == null })
    }
    return LoadedLearnedLeafFixedRootSelectionStub(stub, sha256)
}

@Serializable
internal data class LearnedLeafFixedRootManifest(
    val schemaVersion: Int = 3,
    val protocol: String = LEARNED_LEAF_FIXED_ROOT_PROTOCOL,
    val manifestId: String,
    val sourceStubSha256: String,
    val sourceStubSchemaVersion: Int,
    val selectionWasResultBlind: Boolean,
    val selectionRule: String,
    /** Historical learned-treatment implementation; never the diagnostic source checkout. */
    val mtgalliumSourceCommit: String,
    val pilot: LearnedLeafFixedRootPilotBinding,
    /** Entries are supplied by a separately frozen selector; this code never mines a pilot. */
    val roots: List<LearnedLeafFixedRootSelection>,
) {
    init {
        require(schemaVersion == 3 && protocol == LEARNED_LEAF_FIXED_ROOT_PROTOCOL)
        require(sourceStubSha256 in learnedLeafFixedRootStubSha256Allowlist)
        require(sourceStubSchemaVersion == 1 && selectionWasResultBlind && selectionRule.isNotBlank())
        require(mtgalliumSourceCommit == LEARNED_LEAF_FIXED_ROOT_SOURCE_COMMIT)
        require(roots.map(LearnedLeafFixedRootSelection::id).distinct().size == roots.size)
    }

    fun requireComplete(): LearnedLeafFixedRootManifest {
        require(roots.isNotEmpty()) { "The fixed-root panel has no frozen entries" }
        roots.forEach(LearnedLeafFixedRootSelection::requireComplete)
        require(manifestId == learnedLeafFixedRootManifestId(this)) {
            "Fixed-root manifest id is not bound to its complete content"
        }
        return this
    }
}

internal fun learnedLeafFixedRootManifestId(manifest: LearnedLeafFixedRootManifest): String {
    val content = manifest.copy(manifestId = "CONTENT_ID_OMITTED")
    return "learned-leaf-fixed-root-manifest-v3-sha256:" +
        PolicyJson.sha256(evidenceJson.encodeToString(LearnedLeafFixedRootManifest.serializer(), content))
}

/**
 * Loads a Director-supplied private panel. Public source contains no retained-pilot selection;
 * callers must provide a concrete non-symlink file and [requireComplete] before execution.
 */
internal fun loadLearnedLeafFixedRootManifest(path: Path): LearnedLeafFixedRootManifest {
    return readLearnedLeafFixedRootManifest(path).manifest
}

/** The exact bytes are part of diagnostic provenance; a parsed-equivalent replacement is refused. */
internal data class LoadedLearnedLeafFixedRootManifest(
    val manifest: LearnedLeafFixedRootManifest,
    val sha256: String,
)

internal fun readLearnedLeafFixedRootManifest(path: Path): LoadedLearnedLeafFixedRootManifest {
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
        "Fixed-root panel must be a regular non-symlink file: $path"
    }
    val bytes = Files.readAllBytes(path)
    return LoadedLearnedLeafFixedRootManifest(
        evidenceJson.decodeFromString(bytes.decodeToString()),
        researchSha256(bytes),
    )
}

@Serializable
internal data class LearnedLeafFixedRootPilotBinding(
    val runIdentity: String,
    val argentumCommit: String,
    val checkpointPayloadSha256: String,
    val corpusIdentity: String,
    val trainingRunIdentity: String,
    val validationRunIdentity: String,
    /** Authenticated only as retained gate identity; this diagnostic never opens TEST frames. */
    val testRunIdentity: String,
    val learnedModelConfigurationId: String,
    val control: LearnedLeafFixedRootPolicyBinding,
    val learned: LearnedLeafFixedRootPolicyBinding,
) {
    init {
        require(runIdentity.startsWith("research-run-v1-sha256:"))
        require(argentumCommit.length == 40 && checkpointPayloadSha256.isSha256())
        require(corpusIdentity.isNotBlank() && trainingRunIdentity.isNotBlank() &&
            validationRunIdentity.isNotBlank() && testRunIdentity.isNotBlank())
        require(learnedModelConfigurationId.isNotBlank())
        require(control.id != learned.id)
    }
}

internal fun learnedLeafFixedRootPolicyBinding(
    report: LearnedLeafPilotReport,
    id: String,
): LearnedLeafFixedRootPolicyBinding {
    val description = report.policies.single { it.id == id }
    require(description.kind == ArenaPolicyKind.SEARCH)
    return LearnedLeafFixedRootPolicyBinding(
        id = id,
        evidenceIdentity = report.policyEvidenceIdentities.getValue(id),
        leaf = requireNotNull(description.leaf),
        composition = LearnedLeafFixedRootPolicyComposition(
            profileId = SEARCH_TEACHER_UNPROFILED_RUNTIME_ID,
            particles = requireNotNull(description.particles),
            simulations = requireNotNull(description.simulations),
            maxPolicyDecisions = requireNotNull(description.maxPolicyDecisions),
            explorationConstant = requireNotNull(description.explorationConstant),
            actionSpaceProfile = requireNotNull(description.actionSpaceProfile),
            beliefMode = requireNotNull(description.beliefMode),
            beliefArchitecture = requireNotNull(description.beliefArchitecture),
            planner = requireNotNull(description.searchPlanner),
            opponentPolicyId = requireNotNull(description.opponentPolicyId),
            policyCompression = requireNotNull(description.policyCompression),
            searchReuse = requireNotNull(description.searchReuse),
        ),
    )
}

internal fun learnedLeafFixedRootPilotBinding(
    report: LearnedLeafPilotReport,
    historical: HistoricalOutcomeValueDiagnosticCheckpoint,
): LearnedLeafFixedRootPilotBinding {
    val controlId = report.policies.single { it.leaf?.stateSource == LeafStateSource.BOUNDED_ROLLOUT }.id
    val learnedId = report.policies.single { it.leaf?.evaluator == LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1 }.id
    return LearnedLeafFixedRootPilotBinding(
        runIdentity = report.runIdentity,
        argentumCommit = report.argentumCommit,
        checkpointPayloadSha256 = report.trainingEnvelopePayloadSha256,
        corpusIdentity = report.corpusIdentity,
        trainingRunIdentity = report.trainingRunIdentity,
        validationRunIdentity = historical.validationRunIdentity,
        testRunIdentity = historical.testRunIdentity,
        learnedModelConfigurationId = historical.diagnosticEvaluator().configurationId,
        control = learnedLeafFixedRootPolicyBinding(report, controlId),
        learned = learnedLeafFixedRootPolicyBinding(report, learnedId),
    )
}

@Serializable
internal data class LearnedLeafFixedRootPolicyBinding(
    val id: String,
    val evidenceIdentity: String,
    val leaf: LeafEvaluationConfig,
    val composition: LearnedLeafFixedRootPolicyComposition,
) {
    init { require(id.isNotBlank() && evidenceIdentity.isNotBlank()) }
}

/** Complete retained policy composition relevant to root belief/search semantics. */
@Serializable
internal data class LearnedLeafFixedRootPolicyComposition(
    val profileId: String,
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val explorationConstant: Double,
    val actionSpaceProfile: org.mtgallium.agent.infoset.core.SearchActionSpaceProfile,
    val beliefMode: BeliefMode,
    val beliefArchitecture: BeliefArchitecture,
    val planner: SearchPlannerKind,
    val opponentPolicyId: String,
    val policyCompression: PolicyCompressionConfig,
    val searchReuse: SearchReuseConfig,
) {
    init {
        require(profileId == SEARCH_TEACHER_UNPROFILED_RUNTIME_ID)
        require(particles == 8 && simulations == 64 && maxPolicyDecisions == 32)
        require(explorationConstant == 1.4)
        require(actionSpaceProfile == org.mtgallium.agent.infoset.core.SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1)
        require(beliefMode == BeliefMode.CONSISTENCY_ONLY_V1 && beliefArchitecture == BeliefArchitecture.SEQUENTIAL_B_V1)
        require(planner == SearchPlannerKind.SHARED_TREE)
        require(opponentPolicyId == "mono-red-mixture-70-10-10-10-v2")
        require(!policyCompression.enabled && !searchReuse.enabled)
    }

    fun parameters(baseSeed: Long, leaf: LeafEvaluationConfig): SearchTeacherPolicyParameters =
        SearchTeacherPolicyParameters(
            particles, simulations, maxPolicyDecisions, explorationConstant, leaf, actionSpaceProfile,
            beliefMode, beliefArchitecture, baseSeed, profileId, policyCompression, searchReuse,
        )
}

/** A root is explicit selected metadata, never a category produced by the evaluator. */
@Serializable
internal data class LearnedLeafFixedRootSelection(
    val id: String,
    val sourceGameId: String,
    val pairIndex: Int,
    val leg: String,
    val decisionIndex: Int,
    val sourcePolicyId: String,
    val sourceDecisionFamily: String,
    val sourcePhase: String,
    val sourceStep: String,
    val turnNumber: Int,
    /** Acting player id used for perspective-safe evaluator calls. */
    val rootActor: String,
    /** Selector's play/draw (or other) category; it is not the actor identifier. */
    val sourceSeat: String,
    val representedKnowledgeCategory: String,
    /** E.g. FIRST_COMPARABLE_DIVERGENCE; interpretation is frozen by the selector. */
    val selectionReason: String,
    /** E.g. HIGH or LOW. This diagnostic deliberately has no threshold or quantile code. */
    val marginBand: String,
    val marginMetadata: String,
    val rootInformationStateDigest: String,
    val semanticPrefixDigest: String,
    val retainedPreStateDigest: String? = null,
    val replayRelativePath: String,
    val replaySha256: String,
    val candidateSignatures: List<String>,
    val candidateFamilyDigest: String,
    val schedule: LearnedLeafFixedRootSchedule,
) {
    init {
        require(id.isNotBlank() && sourceGameId.isNotBlank())
        require(pairIndex >= 0 && leg in setOf("a", "b") && decisionIndex >= 0)
        require(sourcePolicyId.isNotBlank() && sourceDecisionFamily.isNotBlank() && sourcePhase.isNotBlank())
        require(sourceStep.isNotBlank() && turnNumber > 0)
        require(rootActor.isNotBlank() && sourceSeat.isNotBlank() && representedKnowledgeCategory.isNotBlank())
        require(selectionReason.isNotBlank() && marginBand.isNotBlank() && marginMetadata.isNotBlank())
        require(rootInformationStateDigest.isSha256() && semanticPrefixDigest.isSha256())
        require(retainedPreStateDigest == null || retainedPreStateDigest.isRetainedPreStateIdentity())
        require(replayRelativePath.isNotBlank() && replaySha256.isSha256())
        require(candidateSignatures.size >= 2 && candidateSignatures == candidateSignatures.sorted())
        require(candidateSignatures.distinct().size == candidateSignatures.size)
        require(candidateFamilyDigest == learnedLeafCandidateFamilyDigest(candidateSignatures))
    }

    fun requireComplete(): LearnedLeafFixedRootSelection {
        schedule.requireComplete()
        require(schedule.originalGameId == sourceGameId && schedule.decisionIndex == decisionIndex)
        return this
    }
}

/**
 * Runtime worlds are private and never appear here. These source-bound reconstruction coordinates
 * identify the replay/belief lifecycle and production simulation particle. The materializer must
 * retain the scheduled world's future RNG; this schema deliberately contains no replacement RNG.
 */
@Serializable
internal data class LearnedLeafFixedRootSchedule(
    val originalGameId: String,
    val replayGameSeed: Long,
    val replayBaseSeed: Long,
    val policySearchBaseSeed: Long,
    val decisionIndex: Int,
    val beliefLifecycleVersion: String,
    val beliefDerivationVersion: String,
    val coordinates: List<LearnedLeafFixedRootScheduleCoordinate>,
    val scheduleDigest: String,
) {
    init {
        require(originalGameId.isNotBlank() && decisionIndex >= 0)
        // These two independent historical seed roles are deliberately frozen.  The replay world
        // remains on the original arena base seed; live-search particle selection uses the
        // historical policy base seed and must never be substituted by diagnostic randomness.
        require(replayBaseSeed == 20260823L && policySearchBaseSeed == 20260825L)
        require(beliefLifecycleVersion.isNotBlank() && beliefDerivationVersion.isNotBlank())
        require(coordinates.isNotEmpty())
        require(coordinates.map(LearnedLeafFixedRootScheduleCoordinate::productionSimulationIndex) == coordinates.indices.toList())
        require(scheduleDigest == learnedLeafFixedRootScheduleDigest(
            originalGameId, replayGameSeed, replayBaseSeed, policySearchBaseSeed, decisionIndex,
            beliefLifecycleVersion, beliefDerivationVersion, coordinates,
        ))
    }

    fun requireComplete(): LearnedLeafFixedRootSchedule {
        require(coordinates.isNotEmpty())
        return this
    }
}

internal fun learnedLeafCandidateFamilyDigest(signatures: List<String>): String =
    PolicyJson.sha256(signatures.joinToString("\n"))

internal fun learnedLeafFixedRootScheduleDigest(
    originalGameId: String,
    replayGameSeed: Long,
    replayBaseSeed: Long,
    policySearchBaseSeed: Long,
    decisionIndex: Int,
    beliefLifecycleVersion: String,
    beliefDerivationVersion: String,
    coordinates: List<LearnedLeafFixedRootScheduleCoordinate>,
): String = PolicyJson.sha256(buildString {
    append("learned-leaf-fixed-root-schedule-v2\n")
    append(originalGameId).append(':').append(replayGameSeed).append(':').append(replayBaseSeed).append(':')
        .append(policySearchBaseSeed).append(':').append(decisionIndex).append('\n')
    append(beliefLifecycleVersion).append(':').append(beliefDerivationVersion).append('\n')
    coordinates.forEach { coordinate ->
        append(coordinate.productionSimulationIndex).append(':').append(coordinate.rootParticleIndex).append('\n')
    }
})

@Serializable
internal data class LearnedLeafFixedRootScheduleCoordinate(
    val productionSimulationIndex: Int,
    val rootParticleIndex: Int,
) {
    init { require(productionSimulationIndex >= 0 && rootParticleIndex >= 0) }
}

/**
 * The selector may establish this witness while replaying one registered pilot game. It is not a
 * panel-entry factory: a Director must freeze a selected witness into the manifest above.
 */
internal data class LearnedLeafFirstComparableDivergence(
    val sourceGameId: String,
    val decisionIndex: Int,
    val learnedTopSignature: String,
    val boundedRolloutTopSignature: String,
) {
    init {
        require(sourceGameId.isNotBlank() && decisionIndex >= 0)
        require(learnedTopSignature != boundedRolloutTopSignature)
    }
}

internal fun firstComparableDivergence(
    chronological: List<LearnedLeafComparableRoot>,
): LearnedLeafFirstComparableDivergence? = chronological
    .sortedBy(LearnedLeafComparableRoot::decisionIndex)
    .firstOrNull { it.learnedTopSignature != it.boundedRolloutTopSignature }
    ?.let {
        LearnedLeafFirstComparableDivergence(
            it.sourceGameId, it.decisionIndex, it.learnedTopSignature, it.boundedRolloutTopSignature,
        )
    }

internal data class LearnedLeafComparableRoot(
    val sourceGameId: String,
    val decisionIndex: Int,
    val learnedTopSignature: String,
    val boundedRolloutTopSignature: String,
)

/** A refusal is a result; it never becomes a score, neutral leaf, or omitted denominator. */
@Serializable
internal enum class LearnedLeafFixedRootFailureCode {
    PANEL_INCOMPLETE,
    SOURCE_IDENTITY_MISMATCH,
    REPLAY_MISSING_OR_HASH_MISMATCH,
    REPLAY_ROOT_MISMATCH,
    CANDIDATE_FAMILY_MISMATCH,
    SCHEDULE_MISMATCH,
    BELIEF_MISMATCH,
    TRANSITION_MISMATCH,
    SETTLEMENT_MISMATCH,
    MISSING_SIBLING_LEAF,
    DUPLICATE_SIBLING_LEAF,
    MATERIALIZATION_REJECTED,
    NONFINITE_VALUE,
}

@Serializable
internal data class LearnedLeafFixedRootFailure(
    val code: LearnedLeafFixedRootFailureCode,
    val rootId: String? = null,
    val candidateSignature: String? = null,
    val scheduleIndex: Int? = null,
    val detail: String,
)

/**
 * Trusted adapter boundary. Implementations reconstruct one safe root, enumerate its exact
 * candidate family once, apply each candidate to every frozen scheduled world, and invoke
 * [org.mtgallium.agent.infoset.core.InformationSetSearch.settleFirstUnvisitedEdge] separately
 * with the production learned and bounded-control configurations. They must retain each scheduled
 * world's future RNG and translate any exception/rejection to a typed refusal.
 */
internal interface LearnedLeafDirectSiblingMaterializer {
    fun verifyBinding(root: LearnedLeafFixedRootSelection): LearnedLeafFixedRootBindingVerification
    fun materialize(root: LearnedLeafFixedRootSelection): LearnedLeafDirectSiblingMaterialization
}

internal sealed interface LearnedLeafFixedRootBindingVerification {
    data object Verified : LearnedLeafFixedRootBindingVerification
    data class Refused(val failures: List<LearnedLeafFixedRootFailure>) : LearnedLeafFixedRootBindingVerification
}

internal sealed interface LearnedLeafDirectSiblingMaterialization {
    data class Complete(val leaves: List<LearnedLeafDirectSiblingLeaf>) : LearnedLeafDirectSiblingMaterialization
    data class Refused(val failures: List<LearnedLeafFixedRootFailure>) : LearnedLeafDirectSiblingMaterialization
}

/** Runtime-only result for a candidate × frozen schedule coordinate. */
internal sealed interface LearnedLeafDirectSiblingLeaf {
    val rootId: String
    val candidateSignature: String
    val scheduleIndex: Int
    val scheduleDigest: String

    data class Terminal(
        override val rootId: String,
        override val candidateSignature: String,
        override val scheduleIndex: Int,
        override val scheduleDigest: String,
        val payoff: Double,
    ) : LearnedLeafDirectSiblingLeaf

    data class Nonterminal(
        override val rootId: String,
        override val candidateSignature: String,
        override val scheduleIndex: Int,
        override val scheduleDigest: String,
        /** State immediately after the selected root action; it is not necessarily the deployed leaf. */
        val postActionInformation: PolicyInformationState,
        /** Present only when production actually invoked the learned evaluator. */
        val deployedLearnedLeaf: LearnedLeafRecordedEvaluation?,
        /** Treatment-faithful production first-unvisited-edge settlement. */
        val learnedTreatment: LearnedLeafTreatmentOutcome,
        /** Bounded-rollout control settlement; it is a qualified control, not ground truth. */
        val boundedRolloutControl: LearnedLeafTreatmentOutcome,
    ) : LearnedLeafDirectSiblingLeaf
}

/** Exact perspective-safe state and value supplied to the deployed learned evaluator. */
internal data class LearnedLeafRecordedEvaluation(
    val information: PolicyInformationState,
    val rootPlayer: String,
    /** Exact value returned to search after the model-owned output transform. */
    val learnedValue: Double,
    val visibleHeuristicValue: Double,
    /** Linear checkpoint score before the model-owned clipping transform. */
    val rawLearnedScore: Double = learnedValue,
)

/** Either treatment's typed first-unvisited-edge result. A refusal has no strategic value. */
internal sealed interface LearnedLeafTreatmentOutcome {
    data class Settled(
        val settlement: org.mtgallium.agent.infoset.core.SearchSettlement,
    ) : LearnedLeafTreatmentOutcome

    data class Refused(val failure: LearnedLeafFixedRootFailure) : LearnedLeafTreatmentOutcome
}

/** Delegates exactly to the historical evaluator while recording only actual production calls. */
internal class RecordedLearnedEvaluator(
    delegate: LearnedOutcomeValueEvaluator,
) {
    private val captures = mutableListOf<LearnedLeafRecordedEvaluation>()
    val evaluator: ConfiguredInformationStateEvaluator = delegate.observedEvaluationBy { information, rootPlayer, evaluation ->
            captures += LearnedLeafRecordedEvaluation(
                information = information,
                rootPlayer = rootPlayer,
                learnedValue = evaluation.deployedValue,
                visibleHeuristicValue = MonoRedInformationEvaluator.evaluate(information, rootPlayer),
                rawLearnedScore = evaluation.rawScore,
            )
        }

    fun captureFor(outcome: LearnedLeafTreatmentOutcome): LearnedLeafRecordedEvaluation? {
        val requiresCapture = (outcome as? LearnedLeafTreatmentOutcome.Settled)
            ?.settlement?.origin == SearchSettlementOrigin.LEARNED_OUTCOME_ESTIMATE
        require(captures.size == if (requiresCapture) 1 else 0) {
            "Learned evaluator capture count ${captures.size} does not match settlement origin"
        }
        return captures.singleOrNull()
    }
}

@Serializable
internal enum class LearnedLeafFixedRootArm {
    BOUNDED_ROLLOUT_CONTROL,
    LEARNED_TREATMENT,
    DIRECT_SIBLING_RAW_LEARNED_SCORE,
    DIRECT_SIBLING_VISIBLE_HEURISTIC,
    DIRECT_SIBLING_CONSTANT,
    DIRECT_SIBLING_SIGN_INVERTED,
    DIRECT_SIBLING_MONOTONIC_SCALE_HALF,
    DIRECT_SIBLING_MONOTONIC_SCALE_DOUBLE,
    DIRECT_SIBLING_WITHIN_FAMILY_SHUFFLED,
}

@Serializable
internal data class LearnedLeafFeatureAudit(
    val trainReferenceDigest: String,
    val nonterminalLeaves: Int,
    /** RMS diagonal standardized distance over positive-variance TRAIN coordinates only. */
    val meanTrainStandardizedDistance: Double?,
    val positiveVarianceTrainCoordinates: Int,
    /** A nonzero departure in a TRAIN coordinate without a usable diagonal scale. */
    val zeroVarianceFeatureOccurrences: Int,
    val zeroVarianceFeatureKeys: Int,
    /** Sparse feature occurrences whose key does not occur in TRAIN. */
    val unseenFeatureOccurrences: Int,
    val unseenFeatureKeys: Int,
    /** Whole-root means and spreads across every exact deployed evaluator input. */
    val overallFeatureMoments: List<LearnedLeafDeployedFeatureMoment>,
    val perCandidate: List<LearnedLeafCandidateFeatureAudit>,
)

@Serializable
internal data class LearnedLeafCandidateFeatureAudit(
    val candidateSignature: String,
    val deployedLearnedLeaves: Int,
    val meanTrainStandardizedDistance: Double?,
    val zeroVarianceFeatureOccurrences: Int,
    val unseenFeatureOccurrences: Int,
    /** Perspective-safe production-evaluator coordinates; absent sparse keys count as zero. */
    val featureMoments: List<LearnedLeafDeployedFeatureMoment>,
)

/** Bounded sufficient statistics for one existing learned-feature coordinate. */
@Serializable
internal data class LearnedLeafDeployedFeatureMoment(
    val key: String,
    val observations: Int,
    val mean: Double,
    val minimum: Double,
    val maximum: Double,
    val populationVariance: Double,
    val trainMean: Double?,
    val trainPopulationVariance: Double?,
) {
    init {
        require(LearnedOutcomeValueFeatureCompiler.isAllowedFeatureKey(key))
        require(observations > 0)
        require(listOf(mean, minimum, maximum, populationVariance).all(Double::isFinite))
        require(minimum <= mean && mean <= maximum && populationVariance >= 0.0)
        require((trainMean == null) == (trainPopulationVariance == null))
    }
}

@Serializable
internal data class LearnedLeafDirectSiblingValue(
    val candidateSignature: String,
    val scheduleIndex: Int,
    val terminal: Boolean,
    val value: Double,
    val settlementOrigin: SearchSettlementOrigin,
)

@Serializable
internal data class LearnedLeafDirectSiblingArmResult(
    val arm: LearnedLeafFixedRootArm,
    val values: List<LearnedLeafDirectSiblingValue>,
    val candidateMeans: Map<String, Double>,
    val topSignature: String,
)

@Serializable
internal data class LearnedLeafDirectSiblingComparison(
    val arm: LearnedLeafFixedRootArm,
    val sameTopSignatureAsControl: Boolean,
    val kendallTau: Double?,
    val controlRegret: Double,
    val optimisticError: Double,
    val pessimisticError: Double,
    val meanAbsoluteError: Double,
    /** All unordered candidate pairs, including ties; ties agree only when both arms tie. */
    val pairwiseCompared: Int,
    val pairwiseAgreed: Int,
    val pairwiseAccuracy: Double?,
    val learnedValuedRegretOfControlSelectedAction: Double,
)

/** Panel aggregation is intentionally separate from per-root direct sibling scoring. */
@Serializable
internal data class LearnedLeafDirectSiblingErrorFrequency(
    val comparedRoots: Int,
    val optimisticErrorRoots: Int,
    val pessimisticErrorRoots: Int,
    val optimisticErrorRate: Double?,
    val pessimisticErrorRate: Double?,
)

internal fun aggregateLearnedLeafErrorFrequency(
    comparisons: List<LearnedLeafDirectSiblingComparison>,
): LearnedLeafDirectSiblingErrorFrequency {
    val roots = comparisons.size
    val optimistic = comparisons.count { it.optimisticError > 0.0 }
    val pessimistic = comparisons.count { it.pessimisticError > 0.0 }
    return LearnedLeafDirectSiblingErrorFrequency(
        roots, optimistic, pessimistic,
        if (roots == 0) null else optimistic.toDouble() / roots,
        if (roots == 0) null else pessimistic.toDouble() / roots,
    )
}

@Serializable
internal data class LearnedLeafDirectSiblingReport(
    val protocol: String = LEARNED_LEAF_FIXED_ROOT_PROTOCOL,
    val rootId: String,
    val scheduleDigest: String,
    val candidateFamilyDigest: String,
    val featureAudit: LearnedLeafFeatureAudit,
    val directSiblingArms: List<LearnedLeafDirectSiblingArmResult>,
    val comparisonsToControl: List<LearnedLeafDirectSiblingComparison>,
    val counterfactualScope: String =
        "DIRECT_SIBLING arms transform settled scalar observations only; they do not measure full-tree search sensitivity.",
    val rawImmediateLearnedScores: List<LearnedLeafRawImmediateLearnedScore> = emptyList(),
    /** This protocol does not execute a search; any sensitivity report is separately labelled. */
    val actualSearchSensitivity: LearnedLeafActualSearchSensitivity? = null,
)

@Serializable
internal data class LearnedLeafRawImmediateLearnedScore(
    val candidateSignature: String,
    val scheduleIndex: Int,
    /** Kept as `value` for compatibility with the previously empty report field. */
    val value: Double,
    val deployedClippedValue: Double,
) {
    init {
        require(candidateSignature.isNotBlank() && scheduleIndex >= 0)
        require(value.isFinite())
        require(deployedClippedValue.isFinite() && deployedClippedValue in -1.0..1.0)
        require(deployedClippedValue == value.coerceIn(-1.0, 1.0))
    }
}

@Serializable
internal data class LearnedLeafActualSearchSensitivity(
    val protocol: String,
    val description: String,
) {
    init { require(protocol.isNotBlank() && description.isNotBlank()) }
}

@Serializable
internal sealed interface LearnedLeafDirectSiblingOutcome {
    @Serializable
    data class Complete(val report: LearnedLeafDirectSiblingReport) : LearnedLeafDirectSiblingOutcome
    @Serializable
    data class Refused(val failures: List<LearnedLeafFixedRootFailure>) : LearnedLeafDirectSiblingOutcome
}

/**
 * Executes no game mechanics itself. The trusted materializer owns replay reconstruction and
 * common-random-number rollout; this comparator verifies its complete grid, calls production
 * learned evaluation through the materializer's exact production continuation path, records the
 * actual evaluator input, and consumes bounded-rollout settlement with contemporaneous provenance.
 */
internal class LearnedLeafDirectSiblingComparator(
    private val trainReference: OutcomeValueTrainFeatureReference,
) {
    fun compare(
        panel: LearnedLeafFixedRootManifest,
        root: LearnedLeafFixedRootSelection,
        materializer: LearnedLeafDirectSiblingMaterializer,
    ): LearnedLeafDirectSiblingOutcome {
        if (runCatching { panel.requireComplete(); root.requireComplete() }.isFailure ||
            root !in panel.roots || root.sourcePolicyId !in setOf(panel.pilot.control.id, panel.pilot.learned.id)
        ) {
            return refused(LearnedLeafFixedRootFailureCode.PANEL_INCOMPLETE, root, "Unfilled root selection")
        }
        when (val verification = materializer.verifyBinding(root)) {
            LearnedLeafFixedRootBindingVerification.Verified -> Unit
            is LearnedLeafFixedRootBindingVerification.Refused -> return LearnedLeafDirectSiblingOutcome.Refused(verification.failures)
        }
        return when (val materialized = materializer.materialize(root)) {
            is LearnedLeafDirectSiblingMaterialization.Refused -> LearnedLeafDirectSiblingOutcome.Refused(materialized.failures)
            is LearnedLeafDirectSiblingMaterialization.Complete -> compareMaterialized(root, materialized.leaves)
        }
    }

    internal fun compareMaterialized(
        root: LearnedLeafFixedRootSelection,
        leaves: List<LearnedLeafDirectSiblingLeaf>,
    ): LearnedLeafDirectSiblingOutcome {
        validateGrid(root, leaves)?.let { return LearnedLeafDirectSiblingOutcome.Refused(it) }
        leaves.filterIsInstance<LearnedLeafDirectSiblingLeaf.Nonterminal>().flatMap { leaf ->
            listOf(leaf.learnedTreatment, leaf.boundedRolloutControl).mapNotNull {
                (it as? LearnedLeafTreatmentOutcome.Refused)?.failure
            }
        }.takeIf { it.isNotEmpty() }?.let { return LearnedLeafDirectSiblingOutcome.Refused(it) }
        val ordered = leaves.sortedWith(compareBy<LearnedLeafDirectSiblingLeaf> { it.candidateSignature }.thenBy { it.scheduleIndex })
        val raw = ordered.map { leaf ->
            when (leaf) {
                is LearnedLeafDirectSiblingLeaf.Terminal -> RawSiblingValue(leaf, leaf.payoff, leaf.payoff)
                is LearnedLeafDirectSiblingLeaf.Nonterminal -> {
                    val learnedSettlement = requireNotNull(leaf.learnedTreatment as? LearnedLeafTreatmentOutcome.Settled).settlement
                    val controlSettlement = requireNotNull(leaf.boundedRolloutControl as? LearnedLeafTreatmentOutcome.Settled).settlement
                    RawSiblingValue(
                        leaf, learnedSettlement.backedValue, controlSettlement.backedValue,
                        controlSettlement.origin, learnedSettlement.origin, leaf.deployedLearnedLeaf,
                    )
                }
            }
        }
        if (raw.any { !it.learned.isFinite() || !it.control.isFinite() }) {
            return refused(LearnedLeafFixedRootFailureCode.NONFINITE_VALUE, root, "Evaluator returned a non-finite value")
        }
        val audit = featureAudit(ordered, root.rootActor, trainReference)
        val arms = scalarArms(raw, trainReference.constantBaseline).map { (arm, values) ->
            armResult(arm, values, root.candidateSignatures)
        }
        val control = arms.single { it.arm == LearnedLeafFixedRootArm.BOUNDED_ROLLOUT_CONTROL }
        return LearnedLeafDirectSiblingOutcome.Complete(
            LearnedLeafDirectSiblingReport(
                rootId = root.id,
                scheduleDigest = root.schedule.scheduleDigest,
                candidateFamilyDigest = root.candidateFamilyDigest,
                featureAudit = audit,
                directSiblingArms = arms,
                comparisonsToControl = arms.filter { it.arm != control.arm }.map { arm ->
                    compareToControl(control, arm, arms.single { it.arm == LearnedLeafFixedRootArm.LEARNED_TREATMENT })
                },
                rawImmediateLearnedScores = raw.mapNotNull { value ->
                    value.deployedLearnedLeaf?.let { evaluation ->
                        LearnedLeafRawImmediateLearnedScore(
                            candidateSignature = value.leaf.candidateSignature,
                            scheduleIndex = value.leaf.scheduleIndex,
                            value = evaluation.rawLearnedScore,
                            deployedClippedValue = evaluation.learnedValue,
                        )
                    }
                },
            )
        )
    }

    private fun validateGrid(
        root: LearnedLeafFixedRootSelection,
        leaves: List<LearnedLeafDirectSiblingLeaf>,
    ): List<LearnedLeafFixedRootFailure>? {
        val expected = root.candidateSignatures.flatMap { signature ->
            root.schedule.coordinates.indices.map { index -> signature to index }
        }.toSet()
        val failures = mutableListOf<LearnedLeafFixedRootFailure>()
        leaves.forEach { leaf ->
            if (leaf.rootId != root.id || leaf.scheduleDigest != root.schedule.scheduleDigest) {
                failures += failure(LearnedLeafFixedRootFailureCode.SCHEDULE_MISMATCH, root, leaf, "Root or schedule identity differs")
            }
            if ((leaf.candidateSignature to leaf.scheduleIndex) !in expected) {
                failures += failure(LearnedLeafFixedRootFailureCode.CANDIDATE_FAMILY_MISMATCH, root, leaf, "Unexpected candidate or schedule coordinate")
            }
            if (leaf is LearnedLeafDirectSiblingLeaf.Terminal && !leaf.payoff.isFinite()) {
                failures += failure(LearnedLeafFixedRootFailureCode.NONFINITE_VALUE, root, leaf, "Terminal payoff is non-finite")
            }
        }
        leaves.groupBy { it.candidateSignature to it.scheduleIndex }.filterValues { it.size != 1 }
            .values.flatten().forEach { leaf ->
                failures += failure(LearnedLeafFixedRootFailureCode.DUPLICATE_SIBLING_LEAF, root, leaf, "Coordinate is duplicated")
            }
        val present = leaves.map { it.candidateSignature to it.scheduleIndex }.toSet()
        (expected - present).forEach { (signature, index) ->
            failures += LearnedLeafFixedRootFailure(LearnedLeafFixedRootFailureCode.MISSING_SIBLING_LEAF, root.id, signature, index, "Coordinate was not materialized")
        }
        return failures.takeIf { it.isNotEmpty() }
    }
}

private data class RawSiblingValue(
    val leaf: LearnedLeafDirectSiblingLeaf,
    val learned: Double,
    val control: Double,
    val controlOrigin: SearchSettlementOrigin = SearchSettlementOrigin.TERMINAL_PAYOFF,
    val learnedOrigin: SearchSettlementOrigin = SearchSettlementOrigin.TERMINAL_PAYOFF,
    val deployedLearnedLeaf: LearnedLeafRecordedEvaluation? = null,
)

private fun scalarArms(
    raw: List<RawSiblingValue>,
    constantBaseline: Double,
): List<Pair<LearnedLeafFixedRootArm, List<Pair<RawSiblingValue, Double>>>> {
    val shuffledByCoordinate = raw.filter { it.leaf is LearnedLeafDirectSiblingLeaf.Nonterminal }
        .groupBy { it.leaf.scheduleIndex }
        .flatMap { (_, scheduleLeaves) ->
            val ordered = scheduleLeaves.sortedBy { it.leaf.candidateSignature }
            val rotated = ordered.map(RawSiblingValue::learned).let { values ->
                if (values.isEmpty()) emptyList() else values.drop(1) + values.first()
            }
            ordered.zip(rotated).map { (value, shuffledValue) ->
                (value.leaf.candidateSignature to value.leaf.scheduleIndex) to shuffledValue
            }
        }.toMap()
    fun values(transform: (RawSiblingValue) -> Double): List<Pair<RawSiblingValue, Double>> = raw.map { it to transform(it) }
    /**
     * These counterfactual arms are meaningful only for actual learned-model estimates.  In
     * particular, a child may become terminal (or unresolved) after a nonterminal parent step;
     * preserving that settlement's origin and value prevents a post-hoc scalar arm from silently
     * relabelling engine payoff or a failure-neutral settlement as learned evidence.
     */
    fun learnedEstimateOnly(value: RawSiblingValue, transform: (Double) -> Double): Double =
        if (value.learnedOrigin != SearchSettlementOrigin.LEARNED_OUTCOME_ESTIMATE) value.learned
        else transform(value.learned).coerceIn(-1.0, 1.0)
    return listOf(
        LearnedLeafFixedRootArm.BOUNDED_ROLLOUT_CONTROL to values { it.control },
        LearnedLeafFixedRootArm.LEARNED_TREATMENT to values { it.learned },
        LearnedLeafFixedRootArm.DIRECT_SIBLING_RAW_LEARNED_SCORE to values { value ->
            if (value.learnedOrigin != SearchSettlementOrigin.LEARNED_OUTCOME_ESTIMATE) value.learned
            else requireNotNull(value.deployedLearnedLeaf).rawLearnedScore
        },
        LearnedLeafFixedRootArm.DIRECT_SIBLING_VISIBLE_HEURISTIC to values { value ->
            learnedEstimateOnly(value) { requireNotNull(value.deployedLearnedLeaf).visibleHeuristicValue }
        },
        LearnedLeafFixedRootArm.DIRECT_SIBLING_CONSTANT to values { learnedEstimateOnly(it) { constantBaseline } },
        LearnedLeafFixedRootArm.DIRECT_SIBLING_SIGN_INVERTED to values { learnedEstimateOnly(it) { prediction -> -prediction } },
        LearnedLeafFixedRootArm.DIRECT_SIBLING_MONOTONIC_SCALE_HALF to values { learnedEstimateOnly(it) { prediction -> prediction * 0.5 } },
        LearnedLeafFixedRootArm.DIRECT_SIBLING_MONOTONIC_SCALE_DOUBLE to values { learnedEstimateOnly(it) { prediction -> prediction * 2.0 } },
        LearnedLeafFixedRootArm.DIRECT_SIBLING_WITHIN_FAMILY_SHUFFLED to values { value ->
            if (value.learnedOrigin != SearchSettlementOrigin.LEARNED_OUTCOME_ESTIMATE) value.learned else
                requireNotNull(shuffledByCoordinate[value.leaf.candidateSignature to value.leaf.scheduleIndex])
        },
    )
}

private fun armResult(
    arm: LearnedLeafFixedRootArm,
    values: List<Pair<RawSiblingValue, Double>>,
    signatures: List<String>,
): LearnedLeafDirectSiblingArmResult {
    val flattened = values.map { (raw, value) ->
        LearnedLeafDirectSiblingValue(
            raw.leaf.candidateSignature, raw.leaf.scheduleIndex,
            raw.leaf is LearnedLeafDirectSiblingLeaf.Terminal, value,
            when {
                raw.leaf is LearnedLeafDirectSiblingLeaf.Terminal -> SearchSettlementOrigin.TERMINAL_PAYOFF
                arm == LearnedLeafFixedRootArm.BOUNDED_ROLLOUT_CONTROL -> raw.controlOrigin
                arm == LearnedLeafFixedRootArm.LEARNED_TREATMENT -> raw.learnedOrigin
                else -> raw.learnedOrigin
            },
        )
    }
    val means = signatures.associateWith { signature ->
        flattened.filter { it.candidateSignature == signature }.map(LearnedLeafDirectSiblingValue::value).average()
    }
    val top = means.maxWith(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).key
    return LearnedLeafDirectSiblingArmResult(arm, flattened, means, top)
}

private fun compareToControl(
    control: LearnedLeafDirectSiblingArmResult,
    arm: LearnedLeafDirectSiblingArmResult,
    learned: LearnedLeafDirectSiblingArmResult,
): LearnedLeafDirectSiblingComparison {
    require(control.candidateMeans.keys == arm.candidateMeans.keys)
    val errors = control.candidateMeans.keys.map { arm.candidateMeans.getValue(it) - control.candidateMeans.getValue(it) }
    val controlBest = control.candidateMeans.maxOf { it.value }
    val keys = control.candidateMeans.keys.sorted()
    val pairs = keys.indices.flatMap { left -> ((left + 1) until keys.size).map { right -> left to right } }
    val agreed = pairs.count { (left, right) ->
        val controlOrder = (control.candidateMeans.getValue(keys[left]) - control.candidateMeans.getValue(keys[right])).compareTo(0.0)
        val armOrder = (arm.candidateMeans.getValue(keys[left]) - arm.candidateMeans.getValue(keys[right])).compareTo(0.0)
        controlOrder == armOrder
    }
    return LearnedLeafDirectSiblingComparison(
        arm = arm.arm,
        sameTopSignatureAsControl = arm.topSignature == control.topSignature,
        kendallTau(control.candidateMeans, arm.candidateMeans),
        controlBest - control.candidateMeans.getValue(arm.topSignature),
        maxOf(0.0, errors.maxOrNull() ?: 0.0),
        maxOf(0.0, -(errors.minOrNull() ?: 0.0)),
        errors.map(::abs).average(),
        pairs.size,
        agreed,
        if (pairs.isEmpty()) null else agreed.toDouble() / pairs.size,
        learned.candidateMeans.maxOf { it.value } - learned.candidateMeans.getValue(control.topSignature),
    )
}

private fun featureAudit(
    leaves: List<LearnedLeafDirectSiblingLeaf>,
    rootPlayer: String,
    reference: OutcomeValueTrainFeatureReference,
): LearnedLeafFeatureAudit {
    val moments = reference.moments.associateBy(OutcomeValueTrainFeatureMoment::key)
    var distanceSum = 0.0
    var nonterminal = 0
    var zeroVarianceOccurrences = 0
    val zeroVarianceKeys = mutableSetOf<String>()
    var unseenOccurrences = 0
    val unseenKeys = mutableSetOf<String>()
    val perCandidate = mutableMapOf<String, MutableList<Map<String, Double>>>()
    val candidateZeroVariance = mutableMapOf<String, Int>()
    val candidateUnseen = mutableMapOf<String, Int>()
    val allCompiled = mutableListOf<Map<String, Double>>()
    leaves.filterIsInstance<LearnedLeafDirectSiblingLeaf.Nonterminal>().forEach { leaf ->
        val captured = leaf.deployedLearnedLeaf ?: return@forEach
        require(captured.rootPlayer == rootPlayer)
        val values = LearnedOutcomeValueFeatureCompiler.compile(captured.information, rootPlayer).values
        allCompiled += values
        perCandidate.getOrPut(leaf.candidateSignature, ::mutableListOf) += values
        val positiveVariance = moments.values.filter { it.populationVariance > 0.0 }
        moments.values.filter { it.populationVariance == 0.0 }.forEach { moment ->
            if ((values[moment.key] ?: 0.0) != moment.mean) {
                zeroVarianceOccurrences++
                candidateZeroVariance[leaf.candidateSignature] = candidateZeroVariance.getOrDefault(leaf.candidateSignature, 0) + 1
                zeroVarianceKeys += moment.key
            }
        }
        val squared = positiveVariance.sumOf { moment ->
                val delta = (values[moment.key] ?: 0.0) - moment.mean
                delta * delta / moment.populationVariance
        }
        distanceSum += sqrt(squared / positiveVariance.size.coerceAtLeast(1))
        nonterminal++
        values.keys.filter { it !in moments }.forEach { key ->
            unseenOccurrences++
            candidateUnseen[leaf.candidateSignature] = candidateUnseen.getOrDefault(leaf.candidateSignature, 0) + 1
            unseenKeys += key
        }
    }
    return LearnedLeafFeatureAudit(
        reference.referenceDigest, nonterminal,
        if (nonterminal == 0) null else distanceSum / nonterminal,
        moments.values.count { it.populationVariance > 0.0 }, zeroVarianceOccurrences, zeroVarianceKeys.size,
        unseenOccurrences, unseenKeys.size,
        summarizeFeatureMoments(allCompiled, moments),
        perCandidate.toSortedMap().map { (candidate, compiled) ->
            val distances = compiled.map { values ->
                val positive = moments.values.filter { it.populationVariance > 0.0 }
                sqrt(positive.sumOf { moment ->
                    val delta = (values[moment.key] ?: 0.0) - moment.mean
                    delta * delta / moment.populationVariance
                } / positive.size.coerceAtLeast(1))
            }
            LearnedLeafCandidateFeatureAudit(
                candidate, compiled.size, distances.average(),
                candidateZeroVariance.getOrDefault(candidate, 0), candidateUnseen.getOrDefault(candidate, 0),
                summarizeFeatureMoments(compiled, moments),
            )
        },
    )
}

internal fun summarizeFeatureMoments(
    compiled: List<Map<String, Double>>,
    train: Map<String, OutcomeValueTrainFeatureMoment>,
): List<LearnedLeafDeployedFeatureMoment> {
    if (compiled.isEmpty()) return emptyList()
    return (train.keys + compiled.flatMap { it.keys }).toSortedSet().map { key ->
        val observed = compiled.map { it[key] ?: 0.0 }
        val minimum = observed.min()
        val maximum = observed.max()
        // `average()` accumulates from zero, so repeating one negative Double can round its mean
        // one ULP below that identical value. The mathematical mean is inside [minimum, maximum];
        // update from the first observation and clamp only the final rounding residue so the
        // retained sufficient statistics preserve that invariant exactly.
        var mean = observed.first()
        observed.drop(1).forEachIndexed { index, value ->
            mean += (value - mean) / (index + 2)
        }
        mean = mean.coerceIn(minimum, maximum)
        val reference = train[key]
        LearnedLeafDeployedFeatureMoment(
            key = key,
            observations = observed.size,
            mean = mean,
            minimum = minimum,
            maximum = maximum,
            populationVariance = observed.sumOf { value ->
                val delta = value - mean
                delta * delta
            } / observed.size,
            trainMean = reference?.mean,
            trainPopulationVariance = reference?.populationVariance,
        )
    }
}

private fun kendallTau(first: Map<String, Double>, second: Map<String, Double>): Double? {
    var concordant = 0
    var discordant = 0
    val keys = first.keys.sorted()
    keys.indices.forEach { left -> ((left + 1) until keys.size).forEach { right ->
        val a = (first.getValue(keys[left]) - first.getValue(keys[right])).compareTo(0.0)
        val b = (second.getValue(keys[left]) - second.getValue(keys[right])).compareTo(0.0)
        if (a != 0 && b != 0) if (a == b) concordant++ else discordant++
    } }
    val compared = concordant + discordant
    return if (compared == 0) null else (concordant - discordant).toDouble() / compared
}

private fun refused(code: LearnedLeafFixedRootFailureCode, root: LearnedLeafFixedRootSelection, detail: String) =
    LearnedLeafDirectSiblingOutcome.Refused(listOf(LearnedLeafFixedRootFailure(code, root.id, detail = detail)))

private fun failure(
    code: LearnedLeafFixedRootFailureCode,
    root: LearnedLeafFixedRootSelection,
    leaf: LearnedLeafDirectSiblingLeaf,
    detail: String,
): LearnedLeafFixedRootFailure =
    LearnedLeafFixedRootFailure(code, root.id, leaf.candidateSignature, leaf.scheduleIndex, detail)

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))

private fun String.isRetainedPreStateIdentity(): Boolean =
    isSha256() || matches(Regex("[a-z][a-z0-9-]*-sha256:[0-9a-f]{64}"))

internal fun fixedRootReplayPath(
    pilotDirectory: Path,
    replayRelativePath: String,
    replaySha256: String,
): Path {
    val base = pilotDirectory.toAbsolutePath().normalize()
    val path = base.resolve(replayRelativePath).normalize()
    require(path.startsWith(base)) { "Replay path escapes pilot directory" }
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path))
    require(sha256File(path) == replaySha256)
    return path
}

internal fun fixedRootSourceGame(
    pilot: LearnedLeafPilotReport,
    pairIndex: Int,
    sourceGameId: String,
    leg: String,
    rootActor: String,
    sourcePolicyId: String,
): GameRunResult {
    val pair = pilot.pairs.singleOrNull { it.pairIndex == pairIndex }
        ?: error("Retained pair is absent")
    val game = pair.games.singleOrNull { it.gameId == sourceGameId }
        ?: error("Retained game is absent")
    require(pair.games.indexOf(game).let { index -> if (index == 0) "a" else "b" } == leg)
    require(game.p0PolicyId.let { id -> if (rootActor == "p0") id else game.p1PolicyId } == sourcePolicyId)
    return game
}

internal fun fixedRootRetainedCandidateSignatures(
    game: GameRunResult,
    decisionIndex: Int,
    rootActor: String,
    binding: LearnedLeafFixedRootPolicyBinding,
): List<String> {
    val detail = game.seatDiagnostics[rootActor]
        ?.searchDecisionsDetail
        ?.singleOrNull { it.decisionIndex == decisionIndex }
        ?: error("Retained candidateStatistics are absent for root")
    val diagnostics = detail.searchDiagnostics
    require(diagnostics.particles == binding.composition.particles)
    require(diagnostics.simulations == binding.composition.simulations)
    require(diagnostics.freshSimulations == binding.composition.simulations && diagnostics.reusedSimulations == 0)
    require(diagnostics.leaf == binding.leaf && diagnostics.rejectedTransitions == 0)
    return detail.candidateStatistics.map { it.choice.signature }.sorted().also { require(it.size >= 2) }
}

internal fun replayFixedRootPrefix(
    decisionIndex: Int,
    replay: VerifiedCanonicalSemanticReplay,
    actual: ArgentumSearchWorld,
    session: SearchTeacherPolicySession,
) {
    val stateEquivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
    requireFixedRootReplayStateMatch(
        difference = stateEquivalence.initialDifference(
            replay.states.first(), actual.authoritativeStateForHost(),
        ),
        gameId = replay.header.gameId,
        semanticDecisionIndex = 0,
        rawOrdinal = -1,
    )
    replay.decisions.take(decisionIndex).forEach { decision ->
        val actor = requireNotNull(actual.actorToAct())
        val exact = actual.expandChoices().candidates.singleOrNull { it.signature == decision.choice.signature }
            ?: error("Canonical semantic choice ${decision.decisionIndex} is no longer legal")
        require(exact == decision.choice) { "Canonical semantic choice changed meaning" }
        val applied = actual.stepWithReplayTrace(exact)
        require(applied.result.accepted && applied.rawTransitions.size == decision.transitions.size)
        applied.rawTransitions.zip(decision.transitions).forEach { (raw, expected) ->
            require(recordedReplayActionEquals(requireNotNull(expected.action), raw.action))
            val eventComparison = recordedReplayEventComparison(
                expected.events, raw.events, requireNotNull(expected.action), raw.action,
                replay.states[expected.ordinal], raw.beforeState,
                replay.states[expected.ordinal + 1], raw.afterState,
            )
            require(eventComparison.difference == null)
            requireFixedRootReplayStateMatch(
                difference = fixedRootReplayTransitionDifference(
                    stateEquivalence = stateEquivalence,
                    expectedAction = requireNotNull(expected.action),
                    actualAction = raw.action,
                    expectedEvents = expected.events,
                    actualEvents = raw.events,
                    expectedBefore = replay.states[expected.ordinal],
                    actualBefore = raw.beforeState,
                    expectedAfter = replay.states[expected.ordinal + 1],
                    actualAfter = raw.afterState,
                    expectedAccepted = expected.accepted,
                    actualAccepted = raw.accepted,
                    rawOrdinal = expected.ordinal,
                    eventComparison = eventComparison,
                ),
                gameId = replay.header.gameId,
                semanticDecisionIndex = decision.decisionIndex,
                rawOrdinal = expected.ordinal,
            )
        }
        session.observeAccepted(actual, actor, exact, decision.decisionIndex, applied.result.privateToActor)
    }
}

internal fun fixedRootReplayTransitionDifference(
    stateEquivalence: RecordedReplayStateEquivalence,
    expectedAction: GameAction,
    actualAction: GameAction,
    expectedEvents: List<GameEvent>,
    actualEvents: List<GameEvent>,
    expectedBefore: GameState,
    actualBefore: GameState,
    expectedAfter: GameState,
    actualAfter: GameState,
    expectedAccepted: Boolean,
    actualAccepted: Boolean,
    rawOrdinal: Int,
    eventComparison: RecordedReplayEventComparison,
): RecordedReplayStateDifference? = stateEquivalence.transitionDifference(
    expectedAction = expectedAction,
    actualAction = actualAction,
    expectedEvents = expectedEvents,
    actualEvents = actualEvents,
    expectedBefore = expectedBefore,
    actualBefore = actualBefore,
    expectedAfter = expectedAfter,
    actualAfter = actualAfter,
    expectedAccepted = expectedAccepted,
    actualAccepted = actualAccepted,
    rawOrdinal = rawOrdinal,
    legacyTimeLordTypeLineNormalizations = eventComparison.legacyTimeLordTypeLineNormalizations,
)

private fun requireFixedRootReplayStateMatch(
    difference: RecordedReplayStateDifference?,
    gameId: String,
    semanticDecisionIndex: Int,
    rawOrdinal: Int,
) {
    require(difference == null) {
        val mismatch = requireNotNull(difference)
        "Canonical replay state mismatch while binding fixed root: gameId=$gameId, " +
            "semanticDecision=$semanticDecisionIndex, rawOrdinal=$rawOrdinal, " +
            "boundary=${mismatch.boundary}, path=${mismatch.path}, reason=${mismatch.reason}, " +
            "expected=${mismatch.expected ?: "<missing>"}, actual=${mismatch.actual ?: "<missing>"}"
    }
}

internal fun representedKnowledgeCategory(information: PolicyInformationState): String = when {
    !information.knowledge.epistemicallyComplete -> "INCOMPLETE_UNSUPPORTED"
    information.knowledge.knownObjects.isNotEmpty() ||
        information.knowledge.knownLibraryOrders.isNotEmpty() -> "COMPLETE_WITH_REMEMBERED_FACTS"
    else -> "COMPLETE_DECK_AND_VISIBLE_FACTS"
}

/**
 * One-shot authority that completes only reconstruction-owned fields in the Director-frozen
 * result-blind stub. It authenticates inputs and reconstructs beliefs, but has no settlement API.
 */
internal class LearnedLeafFixedRootProductionBinder(
    private val pilotDirectory: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val deckManifest: DeckManifest,
    private val historical: HistoricalOutcomeValueDiagnosticCheckpoint,
) {
    private val knownDecks = mapOf("p0" to deckManifest.mainDeck, "p1" to deckManifest.mainDeck)
    private val opponent = defaultMonoRedOpponentPolicy()

    fun bind(stubPath: Path, outputPath: Path): LearnedLeafFixedRootManifest {
        val loadedStub = readLearnedLeafFixedRootStub(stubPath)
        val stub = loadedStub.stub
        require(!Files.exists(outputPath)) { "Bound fixed-root manifest output must be fresh: $outputPath" }
        val pilot = loadAndVerifyPilot(stub)
        requireStubPilot(stub, pilot)
        val pilotBinding = learnedLeafFixedRootPilotBinding(pilot, historical)
        val roots = stub.roots.map { root -> bindRoot(stub, pilot, pilotBinding, root) }
        val provisional = LearnedLeafFixedRootManifest(
            manifestId = "CONTENT_ID_PENDING",
            sourceStubSha256 = loadedStub.sha256,
            sourceStubSchemaVersion = stub.stubSchemaVersion,
            selectionWasResultBlind = stub.selectionWasResultBlind,
            selectionRule = stub.selectionRule,
            mtgalliumSourceCommit = stub.pilot.mtgalliumSourceCommit,
            pilot = pilotBinding,
            roots = roots,
        )
        val manifest = provisional.copy(manifestId = learnedLeafFixedRootManifestId(provisional))
            .requireComplete()
        ResearchRunFiles.atomicWrite(outputPath, evidenceJson.encodeToString(manifest) + "\n")
        require(readLearnedLeafFixedRootManifest(outputPath).manifest == manifest)
        return manifest
    }

    internal fun loadAndVerifyPilot(stub: LearnedLeafFixedRootSelectionStub): LearnedLeafPilotReport {
        require(Files.isDirectory(pilotDirectory) && !Files.isSymbolicLink(pilotDirectory))
        ResearchRunArtifacts.loadAndVerify(pilotDirectory, stub.pilot.runIdentity)
        val manifestPath = pilotDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)
        val reportPath = pilotDirectory.resolve("report.json")
        require(sha256File(manifestPath) == stub.pilot.manifestSha256)
        require(sha256File(reportPath) == stub.pilot.reportSha256)
        return evidenceJson.decodeFromString(Files.readString(reportPath))
    }

    internal fun requireStubPilot(stub: LearnedLeafFixedRootSelectionStub, pilot: LearnedLeafPilotReport) {
        require(stub.pilot.mtgalliumSourceCommit == LEARNED_LEAF_FIXED_ROOT_SOURCE_COMMIT)
        require(pilot.valid && pilot.runIdentity == stub.pilot.runIdentity)
        require(pilot.outerCommit == stub.pilot.mtgalliumSourceCommit)
        require(pilot.argentumCommit == stub.pilot.argentumCommit)
        require(pilot.trainingEnvelopePayloadSha256 == stub.pilot.checkpointPayloadSha256)
        require(pilot.trainingRunIdentity == stub.pilot.trainingRunIdentity)
        require(pilot.corpusIdentity == stub.pilot.corpusIdentity)
        require(pilot.baseSeed == stub.pilot.replayBaseSeed)
        require(historical.trainingRunIdentity == stub.pilot.trainingRunIdentity)
        require(historical.corpusIdentity == stub.pilot.corpusIdentity)
        require(historical.checkpointPayloadSha256 == stub.pilot.checkpointPayloadSha256)
        require(historical.diagnosticEvaluator().checkpointIdentity.payloadSha256 == stub.pilot.checkpointPayloadSha256)
        require(pilot.deckHash == deckManifest.deckHash() && pilot.cardPoolHash == deckManifest.cardPoolHash())
    }

    internal fun bindRoot(
        stub: LearnedLeafFixedRootSelectionStub,
        pilot: LearnedLeafPilotReport,
        pilotBinding: LearnedLeafFixedRootPilotBinding,
        root: LearnedLeafFixedRootStubRoot,
    ): LearnedLeafFixedRootSelection {
        val game = fixedRootSourceGame(
            pilot, root.pairIndex, root.sourceGameId, root.leg, root.rootActor, root.sourcePolicyId,
        )
        require(game.seed == root.replayGameSeed && game.replayVerified && game.replaySha256 == root.replaySha256)
        val replay = readVerifiedCanonicalSemanticReplay(
            fixedRootReplayPath(pilotDirectory, root.replayRelativePath, root.replaySha256),
        )
        require(replay.header.gameId == root.sourceGameId)
        require(replay.header.requireExtensionString("mtgallium.runIdentity") == stub.pilot.runIdentity)
        require(replay.header.requireExtensionString("mtgallium.outerCommit") == stub.pilot.mtgalliumSourceCommit)
        require(replay.header.requireExtensionString("mtgallium.argentumCommit") == stub.pilot.argentumCommit)
        require(replay.header.requireExtensionLong("mtgallium.gameSeed") == root.replayGameSeed)
        require(replay.header.requireExtensionLong("mtgallium.baseSeed") == stub.pilot.replayBaseSeed)
        require(root.decisionIndex in replay.decisions.indices)
        val prefix = replay.decisions.take(root.decisionIndex).map(CanonicalSemanticDecision::choice)
        require(PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }) == root.semanticPrefixDigest)
        require(replay.decisions[root.decisionIndex].choice.operationFamily.name == root.sourceDecisionFamily)

        val actual = createSemanticReplayWorld(
            registry = registry,
            manifest = deckManifest,
            gameId = root.sourceGameId,
            gameSeed = root.replayGameSeed,
            searchBaseSeed = stub.pilot.replayBaseSeed,
            startingPlayerIndex = 0,
            profile = org.mtgallium.agent.infoset.core.SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
        )
        val sourceBinding = listOf(pilotBinding.control, pilotBinding.learned).single { it.id == root.sourcePolicyId }
        require(fixedRootRetainedCandidateSignatures(
            game, root.decisionIndex, root.rootActor, sourceBinding,
        ) == root.candidateSignatures)
        val parameters = sourceBinding.composition.parameters(stub.pilot.policySearchBaseSeed, sourceBinding.leaf)
        val session = SearchTeacherPolicySession(
            root = actual,
            viewer = root.rootActor,
            knownDecks = knownDecks,
            parameters = parameters,
            opponentPolicy = opponent,
            gameId = root.sourceGameId,
            informationEvaluator = if (sourceBinding.id == pilotBinding.learned.id) historical.diagnosticEvaluator() else null,
        )
        replayFixedRootPrefix(root.decisionIndex, replay, actual, session)
        require(actual.actorToAct() == root.rootActor)
        val information = actual.informationState(root.rootActor)
        require(information.observation.phase == root.sourcePhase)
        require(information.observation.step == root.sourceStep)
        require(information.observation.turnNumber == root.turnNumber)
        val candidates = actual.expandChoices().candidates.map { it.signature }.sorted()
        require(candidates == root.candidateSignatures)
        require(learnedLeafCandidateFamilyDigest(candidates) == root.candidateFamilyDigest)

        val belief = session.beliefBatch(actual)
        require(belief.particles.size == sourceBinding.composition.particles)
        val liveSearchSeed = ComponentSeeds.derive(
            root.sourceGameId, root.decisionIndex, stub.pilot.policySearchBaseSeed, "live-search",
        )
        val indices = InformationSetSearch.productionRootParticleIndices(
            belief.particles.map { it.weight }, liveSearchSeed, sourceBinding.composition.simulations,
        )
        val coordinates = indices.mapIndexed { productionIndex, particleIndex ->
            LearnedLeafFixedRootScheduleCoordinate(productionIndex, particleIndex)
        }
        val schedule = LearnedLeafFixedRootSchedule(
            originalGameId = root.sourceGameId,
            replayGameSeed = root.replayGameSeed,
            replayBaseSeed = stub.pilot.replayBaseSeed,
            policySearchBaseSeed = stub.pilot.policySearchBaseSeed,
            decisionIndex = root.decisionIndex,
            beliefLifecycleVersion = "sequential-b-v1",
            beliefDerivationVersion = "production-root-particle-indices-v1",
            coordinates = coordinates,
            scheduleDigest = learnedLeafFixedRootScheduleDigest(
                root.sourceGameId, root.replayGameSeed, stub.pilot.replayBaseSeed,
                stub.pilot.policySearchBaseSeed, root.decisionIndex, "sequential-b-v1",
                "production-root-particle-indices-v1", coordinates,
            ),
        )
        return LearnedLeafFixedRootSelection(
            id = root.id,
            sourceGameId = root.sourceGameId,
            pairIndex = root.pairIndex,
            leg = root.leg,
            decisionIndex = root.decisionIndex,
            sourcePolicyId = root.sourcePolicyId,
            sourceDecisionFamily = root.sourceDecisionFamily,
            sourcePhase = root.sourcePhase,
            sourceStep = root.sourceStep,
            turnNumber = root.turnNumber,
            rootActor = root.rootActor,
            sourceSeat = root.sourceSeat,
            representedKnowledgeCategory = representedKnowledgeCategory(information),
            selectionReason = root.selectionReason,
            marginBand = root.marginBand,
            marginMetadata = root.marginMetadata,
            rootInformationStateDigest = information.informationStateDigest,
            semanticPrefixDigest = root.semanticPrefixDigest,
            retainedPreStateDigest = root.retainedPreStateDigest,
            replayRelativePath = root.replayRelativePath,
            replaySha256 = root.replaySha256,
            candidateSignatures = root.candidateSignatures,
            candidateFamilyDigest = root.candidateFamilyDigest,
            schedule = schedule,
        )
    }
}

/**
 * Private, source-bound materialization of one frozen root.  It intentionally has no selector,
 * resource lookup, corpus producer, or training entry point: the Director supplies a manifest
 * already frozen from authenticated pilot evidence.  The only corpus access happens outside this
 * type through the caller-provided TRAIN-only feature reference.
 */
internal class LearnedLeafFixedRootProductionMaterializer(
    private val pilotDirectory: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val deckManifest: DeckManifest,
    private val panel: LearnedLeafFixedRootManifest,
    private val learnedEvaluator: LearnedOutcomeValueEvaluator,
) : LearnedLeafDirectSiblingMaterializer {
    private val knownDecks = mapOf("p0" to deckManifest.mainDeck, "p1" to deckManifest.mainDeck)
    private val opponent = defaultMonoRedOpponentPolicy()

    override fun verifyBinding(root: LearnedLeafFixedRootSelection): LearnedLeafFixedRootBindingVerification =
        runCatching {
            panel.requireComplete()
            require(root in panel.roots)
            val pilot = loadPilot()
            require(pilot.valid && pilot.runIdentity == panel.pilot.runIdentity)
            require(pilot.outerCommit == panel.mtgalliumSourceCommit)
            require(pilot.argentumCommit == panel.pilot.argentumCommit)
            require(pilot.trainingEnvelopePayloadSha256 == panel.pilot.checkpointPayloadSha256)
            require(pilot.corpusIdentity == panel.pilot.corpusIdentity)
            require(pilot.trainingRunIdentity == panel.pilot.trainingRunIdentity)
            require(pilot.deckHash == deckManifest.deckHash() && pilot.cardPoolHash == deckManifest.cardPoolHash())
            require(learnedEvaluator.checkpointIdentity.payloadSha256 == panel.pilot.checkpointPayloadSha256)
            require(learnedEvaluator.configurationId == panel.pilot.learnedModelConfigurationId)
            requirePolicyBindings(pilot)
            val game = sourceGame(pilot, root)
            require(game.seed == root.schedule.replayGameSeed)
            require(game.replayVerified && game.replaySha256 == root.replaySha256)
            val replay = verifiedReplay(root)
            require(replay.header.gameId == root.sourceGameId)
            require(replay.header.requireExtensionString("mtgallium.runIdentity") == panel.pilot.runIdentity)
            require(replay.header.requireExtensionString("mtgallium.outerCommit") == panel.mtgalliumSourceCommit)
            require(replay.header.requireExtensionString("mtgallium.argentumCommit") == panel.pilot.argentumCommit)
            require(replay.header.requireExtensionLong("mtgallium.gameSeed") == root.schedule.replayGameSeed)
            require(replay.header.requireExtensionLong("mtgallium.baseSeed") == root.schedule.replayBaseSeed)
            require(replay.header.requireExtensionString("mtgallium.deckHash") == deckManifest.deckHash())
            require(replay.header.requireExtensionString("mtgallium.cardPoolHash") == deckManifest.cardPoolHash())
            require(root.schedule.replayBaseSeed == 20260823L && root.schedule.policySearchBaseSeed == 20260825L)
            require(root.decisionIndex in replay.decisions.indices)
            val prefix = replay.decisions.take(root.decisionIndex).map(CanonicalSemanticDecision::choice)
            require(PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }) == root.semanticPrefixDigest)
            require(retainedCandidateSignatures(game, root) == root.candidateSignatures)
        }.fold(
            onSuccess = { LearnedLeafFixedRootBindingVerification.Verified },
            onFailure = { failure ->
                LearnedLeafFixedRootBindingVerification.Refused(
                    listOf(bindingFailure(root, failure))
                )
            },
        )

    override fun materialize(root: LearnedLeafFixedRootSelection): LearnedLeafDirectSiblingMaterialization {
        val verified = verifyBinding(root)
        if (verified is LearnedLeafFixedRootBindingVerification.Refused) {
            return LearnedLeafDirectSiblingMaterialization.Refused(verified.failures)
        }
        return runCatching {
            val replay = verifiedReplay(root)
            val actual = createSemanticReplayWorld(
                registry = registry,
                manifest = deckManifest,
                gameId = root.sourceGameId,
                gameSeed = root.schedule.replayGameSeed,
                searchBaseSeed = root.schedule.replayBaseSeed,
                startingPlayerIndex = 0,
                profile = org.mtgallium.agent.infoset.core.SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            )
            val controlParameters = panel.pilot.control.composition.parameters(
                root.schedule.policySearchBaseSeed, panel.pilot.control.leaf,
            )
            val learnedParameters = panel.pilot.learned.composition.parameters(
                root.schedule.policySearchBaseSeed, panel.pilot.learned.leaf,
            )
            val session = SearchTeacherPolicySession(
                root = actual,
                viewer = root.rootActor,
                knownDecks = knownDecks,
                parameters = if (root.sourcePolicyId == panel.pilot.learned.id) learnedParameters else controlParameters,
                opponentPolicy = opponent,
                gameId = root.sourceGameId,
                informationEvaluator = if (root.sourcePolicyId == panel.pilot.learned.id) learnedEvaluator else null,
            )
            requireReplayPrefix(root, replay, actual, session)
            require(actual.actorToAct() == root.rootActor)
            require(actual.informationState(root.rootActor).informationStateDigest == root.rootInformationStateDigest)
            val candidates = actual.expandChoices().candidates
            require(candidates.map { it.signature }.sorted() == root.candidateSignatures)
            val belief = session.beliefBatch(actual)
            require(belief.particles.size == controlParameters.particles)
            val liveSearchSeed = ComponentSeeds.derive(
                root.schedule.originalGameId,
                root.schedule.decisionIndex,
                root.schedule.policySearchBaseSeed,
                "live-search",
            )
            val indices = InformationSetSearch.productionRootParticleIndices(
                belief.particles.map { it.weight }, liveSearchSeed, root.schedule.coordinates.size,
            )
            require(indices == root.schedule.coordinates.map(LearnedLeafFixedRootScheduleCoordinate::rootParticleIndex))

            val controlSearch = SearchTeacherSearchFactory.create(controlParameters.searchConfig(), opponent)
            val leaves = buildList {
                root.candidateSignatures.forEach { signature ->
                    root.schedule.coordinates.forEachIndexed { scheduleIndex, coordinate ->
                        val base = belief.particles[coordinate.rootParticleIndex].value as? ArgentumSearchWorld
                            ?: error("Belief particle is not an Argentum world")
                        val child = base.fork() as ArgentumSearchWorld
                        val exact = child.expandChoices().candidates.singleOrNull { it.signature == signature }
                            ?: error("Scheduled particle no longer exposes $signature")
                        val stepped = child.stepWithReplayTrace(exact)
                        require(stepped.result.accepted) { "Candidate $signature was rejected" }
                        child.terminalPayoff(root.rootActor)?.let { payoff ->
                            add(LearnedLeafDirectSiblingLeaf.Terminal(root.id, signature, scheduleIndex, root.schedule.scheduleDigest, payoff))
                        } ?: run {
                            val recorder = RecordedLearnedEvaluator(learnedEvaluator)
                            val learnedSearch = SearchTeacherSearchFactory.create(
                                learnedParameters.searchConfig(), opponent, informationEvaluator = recorder.evaluator,
                            )
                            val learned = settle(
                                root, signature, scheduleIndex, "learned",
                            ) {
                                learnedSearch.settleFirstUnvisitedEdge(
                                    child.fork(), root.rootActor, liveSearchSeed,
                                    coordinate.productionSimulationIndex, childDepth = 1,
                                )
                            }
                            val deployedLeaf = recorder.captureFor(learned)
                            val control = settle(
                                root, signature, scheduleIndex, "bounded-control",
                            ) {
                                controlSearch.settleFirstUnvisitedEdge(
                                    child.fork(), root.rootActor, liveSearchSeed,
                                    coordinate.productionSimulationIndex, childDepth = 1,
                                )
                            }
                            add(LearnedLeafDirectSiblingLeaf.Nonterminal(
                                root.id, signature, scheduleIndex, root.schedule.scheduleDigest,
                                child.informationState(root.rootActor), deployedLeaf, learned, control,
                            ))
                        }
                    }
                }
            }
            LearnedLeafDirectSiblingMaterialization.Complete(leaves)
        }.getOrElse { failure ->
            LearnedLeafDirectSiblingMaterialization.Refused(listOf(materializationFailure(root, failure)))
        }
    }

    private fun loadPilot(): LearnedLeafPilotReport {
        require(Files.isDirectory(pilotDirectory) && !Files.isSymbolicLink(pilotDirectory))
        ResearchRunArtifacts.loadAndVerify(pilotDirectory, panel.pilot.runIdentity)
        val report = pilotDirectory.resolve("report.json")
        require(Files.isRegularFile(report) && !Files.isSymbolicLink(report))
        return evidenceJson.decodeFromString(Files.readString(report))
    }

    private fun requirePolicyBindings(pilot: LearnedLeafPilotReport) {
        val bindings = listOf(panel.pilot.control, panel.pilot.learned)
        bindings.forEach { binding ->
            require(pilot.policyEvidenceIdentities[binding.id] == binding.evidenceIdentity)
            val description = pilot.policies.single { it.id == binding.id }
            require(description.leaf == binding.leaf)
            require(description.kind == ArenaPolicyKind.SEARCH)
            require(description.particles == binding.composition.particles)
            require(description.simulations == binding.composition.simulations)
            require(description.maxPolicyDecisions == binding.composition.maxPolicyDecisions)
            require(description.explorationConstant == binding.composition.explorationConstant)
            require(description.actionSpaceProfile == binding.composition.actionSpaceProfile)
            require(description.beliefMode == binding.composition.beliefMode)
            require(description.beliefArchitecture == binding.composition.beliefArchitecture)
            require(description.searchPlanner == binding.composition.planner)
            require(description.opponentPolicyId == binding.composition.opponentPolicyId)
            require(description.policyCompression == binding.composition.policyCompression)
            require(description.searchReuse == binding.composition.searchReuse)
        }
    }

    private fun sourceGame(pilot: LearnedLeafPilotReport, root: LearnedLeafFixedRootSelection): GameRunResult {
        return fixedRootSourceGame(
            pilot, root.pairIndex, root.sourceGameId, root.leg, root.rootActor, root.sourcePolicyId,
        )
    }

    private fun retainedCandidateSignatures(game: GameRunResult, root: LearnedLeafFixedRootSelection): List<String> {
        val binding = listOf(panel.pilot.control, panel.pilot.learned).single { it.id == root.sourcePolicyId }
        return fixedRootRetainedCandidateSignatures(game, root.decisionIndex, root.rootActor, binding)
    }

    private fun replayPath(root: LearnedLeafFixedRootSelection): Path {
        return fixedRootReplayPath(pilotDirectory, root.replayRelativePath, root.replaySha256)
    }

    private fun verifiedReplay(root: LearnedLeafFixedRootSelection): VerifiedCanonicalSemanticReplay =
        readVerifiedCanonicalSemanticReplay(replayPath(root))

    private fun requireReplayPrefix(
        root: LearnedLeafFixedRootSelection,
        replay: VerifiedCanonicalSemanticReplay,
        actual: ArgentumSearchWorld,
        session: SearchTeacherPolicySession,
    ) = replayFixedRootPrefix(root.decisionIndex, replay, actual, session)

    private fun settle(
        root: LearnedLeafFixedRootSelection,
        signature: String,
        scheduleIndex: Int,
        treatment: String,
        action: () -> org.mtgallium.agent.infoset.core.SearchSettlement,
    ): LearnedLeafTreatmentOutcome = runCatching(action).fold(
        onSuccess = { settlement ->
            if (settlement.backedValue.isFinite()) LearnedLeafTreatmentOutcome.Settled(settlement)
            else LearnedLeafTreatmentOutcome.Refused(LearnedLeafFixedRootFailure(
                LearnedLeafFixedRootFailureCode.SETTLEMENT_MISMATCH, root.id, signature, scheduleIndex,
                "$treatment settlement is non-finite",
            ))
        },
        onFailure = { failure -> LearnedLeafTreatmentOutcome.Refused(LearnedLeafFixedRootFailure(
            LearnedLeafFixedRootFailureCode.SETTLEMENT_MISMATCH, root.id, signature, scheduleIndex,
            "$treatment settlement refused: ${failure.message}",
        )) },
    )

    private fun bindingFailure(root: LearnedLeafFixedRootSelection, failure: Throwable): LearnedLeafFixedRootFailure {
        val message = failure.message.orEmpty()
        val code = when {
            "Replay" in message || "replay" in message || "Canonical" in message ->
                LearnedLeafFixedRootFailureCode.REPLAY_MISSING_OR_HASH_MISMATCH
            "candidate" in message || "Candidate" in message ->
                LearnedLeafFixedRootFailureCode.CANDIDATE_FAMILY_MISMATCH
            else -> LearnedLeafFixedRootFailureCode.SOURCE_IDENTITY_MISMATCH
        }
        return LearnedLeafFixedRootFailure(code, root.id, detail = message.ifBlank { failure::class.simpleName.orEmpty() })
    }

    private fun materializationFailure(root: LearnedLeafFixedRootSelection, failure: Throwable): LearnedLeafFixedRootFailure {
        val message = failure.message.orEmpty()
        val code = when {
            "Canonical" in message || "replay" in message || "Replay" in message ->
                LearnedLeafFixedRootFailureCode.REPLAY_ROOT_MISMATCH
            "Belief" in message || "belief" in message || "particle" in message ->
                LearnedLeafFixedRootFailureCode.BELIEF_MISMATCH
            "Candidate" in message || "candidate" in message ->
                LearnedLeafFixedRootFailureCode.CANDIDATE_FAMILY_MISMATCH
            "semantic" in message || "transition" in message || "rejected" in message ->
                LearnedLeafFixedRootFailureCode.TRANSITION_MISMATCH
            else -> LearnedLeafFixedRootFailureCode.MATERIALIZATION_REJECTED
        }
        return LearnedLeafFixedRootFailure(code, root.id, detail = message.ifBlank { failure::class.simpleName.orEmpty() })
    }
}

@Serializable
internal data class LearnedLeafFixedRootPanelReport(
    val protocol: String = LEARNED_LEAF_FIXED_ROOT_PROTOCOL,
    val manifestId: String,
    val pilotRunIdentity: String,
    val diagnosticRunIdentity: String,
    val inputBinding: LearnedLeafFixedRootInputBinding,
    val roots: List<LearnedLeafDirectSiblingOutcome>,
    val accounting: LearnedLeafFixedRootPanelAccounting,
    val limitation: String =
        "Each record is a deterministic counterfactual root reconstruction. Bounded rollout is a control, not truth; these values are not retained original per-simulation leaves. Post-hoc monotonic sibling transforms are not full-tree search sensitivity.",
)

@Serializable
internal data class LearnedLeafFixedRootPanelAccounting(
    val assignedRoots: Int,
    val completedRoots: Int,
    val refusedRoots: Int,
    val assignedCandidateScheduleCoordinates: Int,
    val completedCandidateScheduleCoordinates: Int,
    val failuresByCode: Map<LearnedLeafFixedRootFailureCode, Int>,
) {
    init {
        require(assignedRoots > 0 && completedRoots >= 0 && refusedRoots >= 0)
        require(completedRoots + refusedRoots == assignedRoots)
        require(assignedCandidateScheduleCoordinates > 0)
        require(completedCandidateScheduleCoordinates in 0..assignedCandidateScheduleCoordinates)
        require(failuresByCode.values.all { it > 0 })
        require((refusedRoots == 0) == failuresByCode.isEmpty())
    }
}

/** Immutable identities consumed by one report; included in the report before its file checksum. */
@Serializable
internal data class LearnedLeafFixedRootInputBinding(
    val manifestSha256: String,
    val pilotRunIdentity: String,
    val corpusIdentity: String,
    val trainingRunIdentity: String,
    val validationRunIdentity: String,
    val testRunIdentity: String,
    val checkpointPayloadSha256: String,
    val learnedModelConfigurationId: String,
    val historicalSourceCommit: String,
    val argentumCommit: String,
    val diagnosticSourceIdentity: String,
    val analysisIdentity: String = LEARNED_LEAF_FIXED_ROOT_ANALYSIS,
) {
    init {
        require(manifestSha256.isSha256() && checkpointPayloadSha256.isSha256())
        require(pilotRunIdentity.isNotBlank() && corpusIdentity.isNotBlank() && trainingRunIdentity.isNotBlank())
        require(validationRunIdentity.isNotBlank() && testRunIdentity.isNotBlank() && learnedModelConfigurationId.isNotBlank())
        require(historicalSourceCommit.length == 40 && argentumCommit.length == 40)
        require(diagnosticSourceIdentity.isNotBlank() && analysisIdentity == LEARNED_LEAF_FIXED_ROOT_ANALYSIS)
    }
}

/** Runs only an already frozen panel; the caller owns the authenticated diagnostic run artifact. */
internal fun runLearnedLeafFixedRootDiagnostic(
    panel: LearnedLeafFixedRootManifest,
    materializer: LearnedLeafDirectSiblingMaterializer,
    trainReference: OutcomeValueTrainFeatureReference,
    inputBinding: LearnedLeafFixedRootInputBinding,
    diagnosticRunIdentity: String,
): LearnedLeafFixedRootPanelReport {
    val roots = panel.roots.map { root ->
        LearnedLeafDirectSiblingComparator(trainReference).compare(panel, root, materializer)
    }
    val completed = roots.filterIsInstance<LearnedLeafDirectSiblingOutcome.Complete>()
    val refused = roots.filterIsInstance<LearnedLeafDirectSiblingOutcome.Refused>()
    return LearnedLeafFixedRootPanelReport(
        manifestId = panel.manifestId,
        pilotRunIdentity = panel.pilot.runIdentity,
        diagnosticRunIdentity = diagnosticRunIdentity,
        inputBinding = inputBinding,
        roots = roots,
        accounting = LearnedLeafFixedRootPanelAccounting(
            assignedRoots = panel.roots.size,
            completedRoots = completed.size,
            refusedRoots = refused.size,
            assignedCandidateScheduleCoordinates = panel.roots.sumOf { root ->
                root.candidateSignatures.size * root.schedule.coordinates.size
            },
            completedCandidateScheduleCoordinates = completed.sumOf { outcome ->
                outcome.report.directSiblingArms.single {
                    it.arm == LearnedLeafFixedRootArm.BOUNDED_ROLLOUT_CONTROL
                }.values.size
            },
            failuresByCode = refused.flatMap(LearnedLeafDirectSiblingOutcome.Refused::failures)
                .groupingBy(LearnedLeafFixedRootFailure::code).eachCount().toSortedMap(),
        ),
    )
}
