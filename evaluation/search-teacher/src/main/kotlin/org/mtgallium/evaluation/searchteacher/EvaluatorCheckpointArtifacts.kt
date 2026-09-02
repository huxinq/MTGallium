package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice

/**
 * Schemas for the completed Issue 0012 evaluator checkpoint.
 *
 * The experiment runner and its former approval/audit interface are retired, but
 * these DTOs remain so the preserved prepared population, progress, per-position
 * checkpoints, and report can still be decoded and cross-checked.
 */
internal const val EVALUATOR_CHECKPOINT_PROTOCOL_VERSION = "issue-0012-evaluator-checkpoint-v1"
internal const val EVALUATOR_CHECKPOINT_SOURCE_IDENTITY =
    "baseline-factorial-v1-sha256:9432649f71ad5f8ac7406e9f0c2ccc5fb7ffb4f3ae8e3e04fe9bb3f5cba55b09"
internal const val EVALUATOR_CHECKPOINT_SOURCE_MANIFEST_SHA256 =
    "11853af10c489f897ba1b1724ecac1901881ded40610b2a3761b9312c6fec7af"
internal const val EVALUATOR_CHECKPOINT_SOURCE_REPORT_SHA256 =
    "19abd007478202e576e2043becd2d49a6c653a5906bd28f9149069869d5e7feb"

@Serializable
internal data class EvaluatorCheckpointSourceReportProjection(
    val schemaVersion: Int,
    val recordKind: String,
    val runIdentity: String,
    val outerCommit: String,
    val argentumCommit: String,
    val safeReplayArtifacts: List<TournamentArtifactDigest>,
    val completePairs: Int,
    val gameCount: Int,
    val valid: Boolean,
)

@Serializable
internal enum class EvaluatorCheckpointDecisionFamily {
    PASS_WITH_PLAYABLE_LAND_OR_SPELL,
    CAST_BURN_NOW,
    DEPLOY_CREATURE,
    ATTACK,
    BLOCK,
    CLEANUP_DISCARD,
    MULLIGAN,
    BOTTOM_CARD,
}

@Serializable
internal enum class EvaluatorCheckpointExclusionReason {
    NOT_A_RECORDED_SEARCH,
    NON_EXHAUSTIVE_ACTION_PROFILE,
    SEARCH_AND_RECORDED_HEURISTIC_AGREE,
    OUTSIDE_DECLARED_DECISION_FAMILIES,
    DUPLICATE_INFORMATION_STATE,
    FAMILY_QUOTA_FILLED,
}

@Serializable
internal data class EvaluatorCheckpointPopulationDisposition(
    val gameId: String,
    val decisionIndex: Int,
    val informationStateDigest: String,
    val included: Boolean,
    val family: EvaluatorCheckpointDecisionFamily? = null,
    val exclusion: EvaluatorCheckpointExclusionReason? = null,
) {
    init {
        require(included == (family != null))
        require(included == (exclusion == null))
    }
}

@Serializable
internal data class EvaluatorCheckpointSourceBinding(
    val runIdentity: String,
    val manifestSha256: String,
    val reportSha256: String,
    val outerCommit: String,
    val argentumCommit: String,
    val deckHash: String,
    val cardPoolHash: String,
    val policyEvidenceIdentities: Map<String, String>,
)

/** Player-view content only; sampled worlds, seeds, and replay paths are absent. */
@Serializable
internal data class EvaluatorCheckpointSafePosition(
    val positionId: String,
    val family: EvaluatorCheckpointDecisionFamily,
    val sourceGameId: String,
    val sourceDecisionIndex: Int,
    val sourcePolicyId: String,
    val sourcePolicyBehaviorIdentity: String,
    val informationState: PolicyInformationState,
    val legalActionsInDeclaredOrder: List<SemanticChoice>,
    val recordedSearchChoice: SemanticChoice,
    val recordedHeuristicChoice: SemanticChoice,
)

@Serializable
internal data class EvaluatorCheckpointSafePacket(
    val schemaVersion: Int = 1,
    val documentKind: String = "evaluator-checkpoint-safe-population-v1",
    val protocolVersion: String = EVALUATOR_CHECKPOINT_PROTOCOL_VERSION,
    val source: EvaluatorCheckpointSourceBinding,
    val selectionProcedure: String,
    val dispositions: List<EvaluatorCheckpointPopulationDisposition>,
    val positions: List<EvaluatorCheckpointSafePosition>,
    val limitation: String,
)

/** Private reconstruction data needed to interpret the preserved experiment. */
@Serializable
internal data class EvaluatorCheckpointPrivatePosition(
    val positionId: String,
    val sourceGameId: String,
    val sourceDecisionIndex: Int,
    val actor: String,
    val gameSeed: Long,
    val searchBaseSeed: Long,
    val diagnosticSearchSeed: Long,
    val startingPlayerIndex: Int,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val semanticPrefix: List<SemanticChoice>,
    val canonicalReplayPath: String,
    val canonicalReplaySha256: String,
    val safeInspectionPath: String,
    val safeInspectionSha256: String,
    val informationStateDigest: String,
    val legalActionSignaturesInOrder: List<String>,
    val sampledWorldFingerprints: List<String>,
    val beliefDiagnostics: BeliefDiagnostics,
)

@Serializable
internal data class EvaluatorCheckpointPreparedManifest(
    val schemaVersion: Int = 1,
    val documentKind: String = "evaluator-checkpoint-private-manifest-v1",
    val protocolVersion: String = EVALUATOR_CHECKPOINT_PROTOCOL_VERSION,
    val preparedAtUtc: String,
    val source: EvaluatorCheckpointSourceBinding,
    val preparedSourceProvenance: PolicySourceProvenance,
    val safePacketPath: String,
    val safePacketSha256: String,
    val visibleV2ImplementationSha256: String,
    val evaluatorRegistrySha256: String,
    val tacticalV3ImplementationSha256: String,
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val explorationConstant: Double,
    val leafStateSource: LeafStateSource,
    val evaluatorKey: LeafEvaluator,
    val variants: List<EvaluatorCheckpointVariant>,
    val positions: List<EvaluatorCheckpointPrivatePosition>,
    val identitySha256: String,
)

@Serializable
internal enum class EvaluatorCheckpointVariant {
    UNCHANGED,
    NO_RETAINED_HAND_RESOURCE_REWARD,
    NO_USABLE_MANA_OR_MANA_FIT_REWARD,
    NEITHER_RESOURCE_REWARD,
}

@Serializable
internal enum class EvaluatorCheckpointFailureType {
    REPRESENTATION_REFUSAL,
    BELIEF_CONSTRUCTION_REFUSAL,
    SEARCH_TRANSITION_FAILURE,
    SEARCH_SOFTWARE_FAILURE,
}

@Serializable
internal data class EvaluatorCheckpointFailure(
    val type: EvaluatorCheckpointFailureType,
    val exceptionClass: String,
    val message: String,
)

@Serializable
internal data class EvaluatorCheckpointActionResult(
    val choice: SemanticChoice,
    val simulations: Int,
    val score: Double,
    val zeroVisits: Boolean,
) {
    init {
        require(simulations >= 0)
        require(score.isFinite())
        require(zeroVisits == (simulations == 0))
    }
}

@Serializable
internal data class EvaluatorCheckpointVariantResult(
    val variant: EvaluatorCheckpointVariant,
    val evaluatorConfigurationId: String,
    val selectedAction: SemanticChoice? = null,
    val rootScore: Double? = null,
    val legalActions: List<EvaluatorCheckpointActionResult> = emptyList(),
    val elapsedMillis: Double? = null,
    val failure: EvaluatorCheckpointFailure? = null,
) {
    init {
        require((failure == null) == (selectedAction != null))
        require(rootScore == null || rootScore.isFinite())
        require(elapsedMillis == null || elapsedMillis.isFinite() && elapsedMillis >= 0.0)
        if (failure == null) {
            require(rootScore != null && elapsedMillis != null)
            require(legalActions.any { it.choice.signature == selectedAction!!.signature })
        } else {
            require(rootScore == null && elapsedMillis == null && legalActions.isEmpty())
        }
    }
}

@Serializable
internal data class EvaluatorCheckpointPositionCheckpoint(
    val schemaVersion: Int = 1,
    val documentKind: String = "evaluator-checkpoint-position-result-v1",
    val protocolIdentitySha256: String,
    val positionId: String,
    val completedAtUtc: String,
    val variants: List<EvaluatorCheckpointVariantResult>,
)

@Serializable
internal data class EvaluatorCheckpointProgress(
    val schemaVersion: Int = 1,
    val documentKind: String = "evaluator-checkpoint-progress-v1",
    val protocolIdentitySha256: String,
    val updatedAtUtc: String,
    val assignedPositions: Int,
    val completedPositions: Int,
    val typedFailures: Int,
    val runningPositionId: String? = null,
    val state: String,
)

@Serializable
internal data class EvaluatorCheckpointPositionReport(
    val positionId: String,
    val family: EvaluatorCheckpointDecisionFamily,
    val visibleSituation: String,
    val variants: List<EvaluatorCheckpointVariantResult>,
)

@Serializable
internal data class EvaluatorCheckpointReport(
    val schemaVersion: Int = 1,
    val documentKind: String = "evaluator-checkpoint-diagnostic-report-v1",
    val protocolVersion: String = EVALUATOR_CHECKPOINT_PROTOCOL_VERSION,
    val generatedAtUtc: String,
    val source: EvaluatorCheckpointSourceBinding,
    val protocolIdentitySha256: String,
    val population: String,
    val assignedPositions: Int,
    val includedByFamily: Map<EvaluatorCheckpointDecisionFamily, Int>,
    val exclusionCounts: Map<EvaluatorCheckpointExclusionReason, Int>,
    val typedFailureCounts: Map<EvaluatorCheckpointFailureType, Int>,
    val positionsWhereAnyVariantChangedTheSelectedAction: Int,
    val directObservations: List<String>,
    val positions: List<EvaluatorCheckpointPositionReport>,
    val permittedConclusion: String,
    val limits: List<String>,
)

internal data class EvaluatorCheckpointArtifactSet(
    val prepared: EvaluatorCheckpointPreparedManifest,
    val safe: EvaluatorCheckpointSafePacket,
    val progress: EvaluatorCheckpointProgress,
    val report: EvaluatorCheckpointReport,
)

internal object EvaluatorCheckpointArtifacts {
    private val json = Json(evidenceJson) { ignoreUnknownKeys = true }

    fun readPrepared(path: Path): EvaluatorCheckpointPreparedManifest = read(path)
    fun readSafe(path: Path): EvaluatorCheckpointSafePacket = read(path)
    fun readProgress(path: Path): EvaluatorCheckpointProgress = read(path)
    fun readReport(path: Path): EvaluatorCheckpointReport = read(path)
    fun readPosition(path: Path): EvaluatorCheckpointPositionCheckpoint = read(path)
    fun readSourceReport(path: Path): EvaluatorCheckpointSourceReportProjection = read(path)

    fun readDirectory(root: Path): EvaluatorCheckpointArtifactSet {
        val prepared = readPrepared(root.resolve("prepared-private.json"))
        val safe = readSafe(root.resolve("prepared-safe.json"))
        val progress = readProgress(root.resolve("progress.json"))
        val report = readReport(root.resolve("report.json"))

        require(prepared.protocolVersion == EVALUATOR_CHECKPOINT_PROTOCOL_VERSION)
        require(safe.protocolVersion == prepared.protocolVersion)
        require(report.protocolVersion == prepared.protocolVersion)
        require(safe.source == prepared.source && report.source == prepared.source)
        require(progress.protocolIdentitySha256 == prepared.identitySha256)
        require(report.protocolIdentitySha256 == prepared.identitySha256)
        require(progress.assignedPositions == prepared.positions.size)
        require(report.assignedPositions == prepared.positions.size)
        require(safe.positions.map { it.positionId } == prepared.positions.map { it.positionId })

        return EvaluatorCheckpointArtifactSet(prepared, safe, progress, report)
    }

    private inline fun <reified T> read(path: Path): T {
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
            "Evaluator checkpoint artifact is missing or unsafe: $path"
        }
        return json.decodeFromString(Files.readString(path))
    }
}
