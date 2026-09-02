package org.mtgallium.agent.infoset.core

import java.io.BufferedWriter
import java.io.Closeable
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import org.mtgallium.research.run.ResearchSourceProvenance
import org.mtgallium.research.run.ResearchSourceTreeState

const val TRAJECTORY_SCHEMA_V1: Int = 1
const val TRAJECTORY_SCHEMA_V2: Int = 2
const val TRAJECTORY_SCHEMA_V3: Int = 3
const val TRAJECTORY_SCHEMA_V4: Int = 4
const val TRAJECTORY_SCHEMA_V5: Int = 5
const val TRAJECTORY_SCHEMA_V6: Int = 6
const val TRAJECTORY_SCHEMA_V7: Int = 7
const val TRAJECTORY_SCHEMA_V8: Int = 8
const val TRAJECTORY_SCHEMA_V9: Int = 9
const val TRAJECTORY_SCHEMA_V10: Int = 10
const val TRAJECTORY_SCHEMA_V11: Int = 11
const val TRAJECTORY_SCHEMA_V12: Int = 12
const val TRAJECTORY_SCHEMA_CURRENT: Int = TRAJECTORY_SCHEMA_V12

const val POLICY_BEHAVIOR_BINDING_SCHEMA_V1: Int = 1
const val POLICY_BEHAVIOR_BINDING_PREFIX: String = "policy-evidence-v1-sha256"

/**
 * Historical policy-facing names for the one generic source-provenance authority.
 * They retain the JSON field shape needed by existing evidence; new non-policy code uses ResearchSourceProvenance.
 */
typealias PolicySourceTreeState = ResearchSourceTreeState
typealias PolicySourceProvenance = ResearchSourceProvenance

/**
 * Full detached-record binding for one policy behavior and the exact source state that supplied it.
 * A dirty-tree fingerprint distinguishes diagnostics, but does not make their uncommitted source
 * retrievable; evidence release separately requires clean, published revisions.
 */
@Serializable
data class PolicyBehaviorBinding(
    val schemaVersion: Int = POLICY_BEHAVIOR_BINDING_SCHEMA_V1,
    val behaviorIdentity: String,
    val behaviorSpecificationSha256: String,
    val sourceProvenance: PolicySourceProvenance,
    val identity: String,
) {
    init {
        require(schemaVersion == POLICY_BEHAVIOR_BINDING_SCHEMA_V1)
        require(behaviorIdentity.isNotBlank())
        require(behaviorSpecificationSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Behavior specification fingerprint must be a lowercase SHA-256 value"
        }
        require(identity == computeIdentity(behaviorIdentity, behaviorSpecificationSha256, sourceProvenance)) {
            "Policy evidence identity does not match behavior and source provenance"
        }
    }

    companion object {
        fun create(
            behaviorIdentity: String,
            behaviorSpecification: JsonObject,
            sourceProvenance: PolicySourceProvenance,
        ): PolicyBehaviorBinding {
            val specificationSha256 = PolicyJson.digest(behaviorSpecification)
            return PolicyBehaviorBinding(
                behaviorIdentity = behaviorIdentity,
                behaviorSpecificationSha256 = specificationSha256,
                sourceProvenance = sourceProvenance,
                identity = computeIdentity(behaviorIdentity, specificationSha256, sourceProvenance),
            )
        }

        private fun computeIdentity(
            behaviorIdentity: String,
            behaviorSpecificationSha256: String,
            sourceProvenance: PolicySourceProvenance,
        ): String {
            val material = buildJsonObject {
                put("schemaVersion", POLICY_BEHAVIOR_BINDING_SCHEMA_V1)
                put("behaviorIdentity", behaviorIdentity)
                put("behaviorSpecificationSha256", behaviorSpecificationSha256)
                put(
                    "sourceProvenance",
                    PolicyJson.format.encodeToJsonElement(
                        ResearchSourceProvenance.serializer(),
                        sourceProvenance,
                    ),
                )
            }
            return "$POLICY_BEHAVIOR_BINDING_PREFIX:${PolicyJson.digest(material)}"
        }
    }
}

/** Public, information-safe trajectory record. Privileged state belongs in a separate schema. */
@Serializable
sealed interface PolicyTrajectoryRecord {
    val schemaVersion: Int
    val gameId: String
}

@Serializable
@SerialName("header")
data class PolicyTrajectoryHeader(
    override val schemaVersion: Int = TRAJECTORY_SCHEMA_CURRENT,
    override val gameId: String,
    val createdAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val deckManifestHash: String,
    val cardPoolHash: String,
    val perspectivePlayerId: String,
    val observationSchemaVersion: Int = POLICY_SCHEMA_CURRENT,
    val boundedInputSchemaVersion: Int = BOUNDED_POLICY_INPUT_SCHEMA_CURRENT,
    val historyCommitmentAlgorithm: String = POLICY_HISTORY_COMMITMENT_ALGORITHM,
    val candidateSchemaVersion: Int = CANDIDATE_SCHEMA_CURRENT,
    val profileManifestHash: String,
    val behaviorBinding: PolicyBehaviorBinding,
    val policyVersion: String,
    val evaluatorVersion: String,
    val leaf: LeafEvaluationConfig,
    val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
    val beliefVersion: String,
    val opponentModelVersion: String,
) : PolicyTrajectoryRecord {
    init {
        requireCurrent(schemaVersion)
        require(policyVersion == behaviorBinding.identity) {
            "Trajectory policy version must be the full behavior-and-source identity"
        }
        require(outerCommit == behaviorBinding.sourceProvenance.outer.revision)
        require(argentumCommit == behaviorBinding.sourceProvenance.argentum.revision)
    }
}

@Serializable
@SerialName("decision")
data class PolicyTrajectoryDecision(
    override val schemaVersion: Int = TRAJECTORY_SCHEMA_CURRENT,
    override val gameId: String,
    val decisionIndex: Int,
    val actingPlayerId: String,
    val policyVersion: String,
    val evaluatorVersion: String,
    val leaf: LeafEvaluationConfig,
    val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
    val beliefVersion: String,
    val opponentModelVersion: String,
    /** Bounded semantic input; the full safe prefix is reconstructed from ledger append records. */
    val policyInput: BoundedPolicyInput,
    val expansion: PolicyExpansion,
    val candidates: List<SearchCandidateStatistics>,
    val chosen: SemanticChoice,
    val heuristicChoice: SemanticChoice,
    val rootValue: Double,
    val beliefDiagnostics: BeliefDiagnostics,
    val searchDiagnostics: InformationSetSearchDiagnostics,
) : PolicyTrajectoryRecord {
    init {
        requireCurrent(schemaVersion)
        require(decisionIndex >= 0) { "Decision index must be non-negative" }
        require(chosen.signature in candidates.map { it.choice.signature }) {
            "Chosen action must occur in the serialized search statistics"
        }
        require(policyInput.actingPlayerId == actingPlayerId) {
            "Trajectory actor does not match the bounded policy input"
        }
        policyInput.requireValidDigest()
        require(rootValue.isFinite()) { "Root value must be finite" }
        require(searchDiagnostics.leaf == leaf) { "Trajectory leaf configuration disagrees with search diagnostics" }
        require(evaluatorVersion == leaf.evaluator.evaluatorId) {
            "Trajectory evaluator version disagrees with its leaf configuration"
        }
    }

    val informationStateDigest: String get() = policyInput.informationStateDigest
    val historyDigest: String get() = policyInput.historyCommitment.digest
    val historyCursor: Int get() = policyInput.historyCursor

    fun informationState(ledger: List<PolicyHistoryEvent>): PolicyInformationState =
        policyInput.toInformationState(ledger)
}

@Serializable
@SerialName("forced_transition")
data class PolicyTrajectoryForcedTransition(
    override val schemaVersion: Int = TRAJECTORY_SCHEMA_CURRENT,
    override val gameId: String,
    val afterDecisionIndex: Int,
    val events: List<PolicyHistoryEvent>,
) : PolicyTrajectoryRecord {
    init {
        requireCurrent(schemaVersion)
        require(afterDecisionIndex >= 0)
        require(events.isNotEmpty())
    }
}

@Serializable
enum class PolicyTrajectoryCompletion {
    /** Argentum ended the game and supplied the game result. */
    GAME_ENDED,
    /** The recorded run stopped while the game was still in progress; no game result is assigned. */
    STOPPED_BEFORE_GAME_END,
}

@Serializable
enum class PolicyTrajectoryStopReason {
    GAME_DECISION_LIMIT_REACHED,
    SEARCH_DECISION_LIMIT_REACHED,
    PER_DECISION_TIME_LIMIT_REACHED,
    WHOLE_GAME_TIME_LIMIT_REACHED,
    TURN_LIMIT_REACHED,
    TRANSITION_LIMIT_REACHED,
    /** A player-visible fact reached by the run is outside the represented policy record. */
    REPRESENTATION_FAILURE,
    /** The adapter or search transition failed rather than producing a Magic result. */
    SOFTWARE_TRANSITION_FAILURE,
    /** Backward-compatible catch-all for older technical stop producers. */
    TECHNICAL_FAILURE,
}

@Serializable
@SerialName("outcome")
data class PolicyTrajectoryOutcome(
    override val schemaVersion: Int = TRAJECTORY_SCHEMA_CURRENT,
    override val gameId: String,
    val decisions: Int,
    val completion: PolicyTrajectoryCompletion,
    val stopReason: PolicyTrajectoryStopReason? = null,
    val winnerId: String?,
    val resultByPlayer: Map<String, Double>?,
    /** Null marks a private opponent response that this trajectory's perspective may not inspect. */
    val semanticResponseSequence: List<SemanticChoice?>,
    val sequenceDigest: String = PolicyJson.sha256(
        semanticResponseSequence.joinToString("\u001f") { it?.signature ?: "<private>" }
    ),
) : PolicyTrajectoryRecord {
    init {
        requireCurrent(schemaVersion)
        require(decisions >= 0)
        require(semanticResponseSequence.size == decisions)
        when (completion) {
            PolicyTrajectoryCompletion.GAME_ENDED -> {
                require(stopReason == null) { "A game-ended trajectory cannot have a stop reason" }
                require(!resultByPlayer.isNullOrEmpty()) { "A game-ended trajectory must include game results" }
                require(resultByPlayer.values.all { it.isFinite() && it in -1.0..1.0 })
            }
            PolicyTrajectoryCompletion.STOPPED_BEFORE_GAME_END -> {
                require(stopReason != null) { "A stopped trajectory must state why recording stopped" }
                require(winnerId == null) { "A stopped trajectory cannot declare a winner" }
                require(resultByPlayer == null) { "A stopped trajectory cannot assign game results" }
            }
        }
    }
}

/** One-record-per-line gzip writer. A header must be first and an outcome record last. */
class PolicyTrajectoryWriter private constructor(
    private val writer: BufferedWriter,
) : Closeable {
    private var started = false
    private var terminated = false
    private var ledgerSize = 0
    private var gameId: String? = null
    private var nextDecisionIndex = 0
    private var nextTransitionIndex = 0
    private var historyCommitment = PolicyHistoryCommitment.empty()
    private var actionSpaceProfile: SearchActionSpaceProfile? = null
    private var policyVersion: String? = null
    private var evaluatorVersion: String? = null
    private var leaf: LeafEvaluationConfig? = null
    private var beliefVersion: String? = null
    private var opponentModelVersion: String? = null

    /** Number of accepted choices whose perspective-safe transitions have been appended. */
    val completedDecisions: Int get() = nextTransitionIndex

    fun append(record: PolicyTrajectoryRecord) {
        check(!terminated) { "A trajectory cannot be extended after its outcome" }
        if (!started) {
            require(record is PolicyTrajectoryHeader) { "The first trajectory record must be a header" }
            gameId = record.gameId
            actionSpaceProfile = record.actionSpaceProfile
            policyVersion = record.policyVersion
            evaluatorVersion = record.evaluatorVersion
            leaf = record.leaf
            beliefVersion = record.beliefVersion
            opponentModelVersion = record.opponentModelVersion
            require(record.evaluatorVersion == record.leaf.evaluator.evaluatorId) {
                "Trajectory header evaluator version disagrees with its leaf configuration"
            }
            started = true
        } else {
            require(record !is PolicyTrajectoryHeader) { "A trajectory may contain only one header" }
        }
        require(record.gameId == gameId) { "Trajectory record game id does not match its header" }
        when (record) {
            is PolicyTrajectoryDecision -> {
                require(record.actionSpaceProfile == actionSpaceProfile) {
                    "Decision action-space profile does not match the trajectory header"
                }
                require(record.policyVersion == policyVersion) {
                    "Decision policy version does not match the trajectory header"
                }
                require(record.evaluatorVersion == evaluatorVersion && record.leaf == leaf) {
                    "Decision evaluator or leaf configuration does not match the trajectory header"
                }
                require(record.beliefVersion == beliefVersion) {
                    "Decision belief version does not match the trajectory header"
                }
                require(record.opponentModelVersion == opponentModelVersion) {
                    "Decision opponent model does not match the trajectory header"
                }
                require(
                    record.beliefVersion ==
                        "${record.beliefDiagnostics.architecture.name.lowercase()}:" +
                        record.beliefDiagnostics.mode.name.lowercase()
                ) {
                    "Decision belief version disagrees with its runtime diagnostics"
                }
                require(record.decisionIndex >= nextDecisionIndex) { "Decision indices must increase" }
                nextDecisionIndex = record.decisionIndex + 1
                require(record.policyInput.historyCommitment == historyCommitment) {
                    "Decision ${record.decisionIndex} history commitment does not match the streamed ledger"
                }
            }
            is PolicyTrajectoryForcedTransition -> {
                require(record.afterDecisionIndex == nextTransitionIndex++) {
                    "Forced-transition indices must be contiguous from zero"
                }
                require(record.events.map { it.eventId } ==
                    (ledgerSize until ledgerSize + record.events.size).map(Int::toLong)) {
                    "Ledger append after decision ${record.afterDecisionIndex} is not contiguous at $ledgerSize"
                }
                record.events.forEach { historyCommitment = historyCommitment.append(it) }
                ledgerSize = historyCommitment.cursor
            }
            is PolicyTrajectoryOutcome -> require(record.decisions == nextTransitionIndex) {
                "Outcome decision count ${record.decisions} does not match $nextTransitionIndex transitions"
            }
            else -> Unit
        }
        val encoded = PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), record)
        PublicArtifactPrivacy.requireSafeJson(encoded, "public trajectory record")
        writer.write(encoded)
        writer.newLine()
        if (record is PolicyTrajectoryOutcome) terminated = true
    }

    override fun close() {
        writer.close()
        check(started) { "Cannot close an empty trajectory" }
        check(terminated) { "Trajectory closed without an outcome record" }
    }

    companion object {
        fun compressed(path: Path): PolicyTrajectoryWriter {
            require(path.fileName.toString().endsWith(".jsonl.gz")) {
                "Compressed trajectories must use the .jsonl.gz suffix"
            }
            path.parent?.let(Files::createDirectories)
            return from(GZIPOutputStream(Files.newOutputStream(path)))
        }

        fun from(output: OutputStream): PolicyTrajectoryWriter = PolicyTrajectoryWriter(
            BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        )
    }
}

private fun requireCurrent(schemaVersion: Int) {
    require(schemaVersion == TRAJECTORY_SCHEMA_CURRENT) {
        "Unknown trajectory schema version $schemaVersion"
    }
}
