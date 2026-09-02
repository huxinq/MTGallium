package org.mtgallium.agent.infoset.core

import kotlinx.serialization.Serializable

const val INSPECTION_SCHEMA_V1: Int = 1
const val INSPECTION_SCHEMA_V2: Int = 2
const val INSPECTION_SCHEMA_V3: Int = 3
const val INSPECTION_SCHEMA_V4: Int = 4
const val INSPECTION_SCHEMA_V5: Int = 5
const val INSPECTION_SCHEMA_V6: Int = 6
const val INSPECTION_SCHEMA_V7: Int = 7
const val INSPECTION_SCHEMA_V8: Int = 8
const val INSPECTION_SCHEMA_V9: Int = 9
const val INSPECTION_SCHEMA_V10: Int = 10
const val INSPECTION_SCHEMA_CURRENT: Int = INSPECTION_SCHEMA_V10

const val INSPECTION_EXECUTION_BINDING_SCHEMA_V1: Int = 1

/**
 * Runtime-derived identity for one policy seat in a generated inspection replay.
 * The display-oriented policy id is kept separate from the behavior specification digest.
 */
@Serializable
data class PolicyInspectionPolicyExecutionIdentity(
    val policyId: String,
    val behaviorIdentity: String,
    val behaviorSpecificationSha256: String,
) {
    init {
        require(policyId.isNotBlank())
        require(behaviorIdentity.isNotBlank())
        require(behaviorSpecificationSha256.isLowerSha256())
    }
}

/**
 * A predeclared protocol commitment completed with facts derived by the game runner.
 * Raw game seeds remain outside the perspective-safe replay; [scheduledExecutionSha256]
 * commits to them and the governed protocol recomputes that digest from its manifest.
 */
@Serializable
data class PolicyInspectionExecutionBinding(
    val schemaVersion: Int = INSPECTION_EXECUTION_BINDING_SCHEMA_V1,
    val protocolId: String,
    val manifestSha256: String,
    val scheduledExecutionSha256: String,
    val declaredBehaviorSha256: String,
    val declaredPopulationSha256: String,
    val declaredLimitsSha256: String,
    val actualPolicyByPlayer: Map<String, PolicyInspectionPolicyExecutionIdentity>,
    val actualDeckSha256ByPlayer: Map<String, String>,
    val actualGameConfigurationSha256: String,
    val actualRuntimeIdentitySha256: String,
    val actualExecutionLimits: Map<String, Long>,
    val liveOpponentPolicyDecisions: OpponentPolicyDecisionSummary,
    val searchOpponentPolicyDecisions: OpponentPolicyDecisionSummary,
    val heuristicComparatorDecisions: OpponentPolicyDecisionSummary,
) {
    init {
        require(schemaVersion == INSPECTION_EXECUTION_BINDING_SCHEMA_V1)
        require(protocolId.isNotBlank())
        listOf(
            manifestSha256,
            scheduledExecutionSha256,
            declaredBehaviorSha256,
            declaredPopulationSha256,
            declaredLimitsSha256,
        ).forEach { require(it.isLowerSha256()) }
        require(actualPolicyByPlayer.isNotEmpty())
        require(actualPolicyByPlayer.keys.toList() == actualPolicyByPlayer.keys.sorted()) {
            "Inspection execution policy seats must be canonically ordered"
        }
        require(actualDeckSha256ByPlayer.keys == actualPolicyByPlayer.keys)
        require(actualDeckSha256ByPlayer.keys.toList() == actualDeckSha256ByPlayer.keys.sorted())
        require(actualDeckSha256ByPlayer.values.all { it.isLowerSha256() })
        require(actualGameConfigurationSha256.isLowerSha256())
        require(actualRuntimeIdentitySha256.isLowerSha256())
        require(actualExecutionLimits.isNotEmpty())
        require(actualExecutionLimits.keys.all(String::isNotBlank))
        require(actualExecutionLimits.values.all { it > 0 })
        require(actualExecutionLimits.keys.toList() == actualExecutionLimits.keys.sorted()) {
            "Inspection execution limits must be canonically ordered"
        }
    }

    val evidenceInvalidatingReplacements: Int
        get() = liveOpponentPolicyDecisions.evidenceInvalidatingReplacements +
            searchOpponentPolicyDecisions.evidenceInvalidatingReplacements +
            heuristicComparatorDecisions.evidenceInvalidatingReplacements
}

private fun String.isLowerSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))

/**
 * Display-only material carried by an inspection artifact. It is deliberately outside
 * [PolicyInformationState]: changing card art must never change a policy input or digest.
 */
@Serializable
data class PolicyInspectionPresentation(
    val cardImages: List<PolicyInspectionCardImage> = emptyList(),
    val unresolvedCardNames: List<String> = emptyList(),
) {
    init {
        require(cardImages.map { it.key }.distinct().size == cardImages.size) {
            "Inspection card-image keys must be unique"
        }
        require(cardImages == cardImages.sortedBy { it.key }) {
            "Inspection card images must be canonically ordered"
        }
        require(unresolvedCardNames == unresolvedCardNames.distinct().sorted()) {
            "Unresolved card names must be unique and sorted"
        }
    }
}

@Serializable
data class PolicyInspectionCardImage(
    /** `definition:<safe definition id>` for exact printings, or `name:<safe card name>`. */
    val key: String,
    val cardName: String,
    val imageUri: String,
    val rotationDegrees: Int = 0,
    val source: String = "SCRYFALL",
) {
    init {
        require(key.startsWith("definition:") || key.startsWith("name:"))
        require(cardName.isNotBlank())
        require(imageUri.startsWith("https://cards.scryfall.io/")) {
            "Inspection art must use the Scryfall image CDN"
        }
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(source == "SCRYFALL")
    }
}

/**
 * A normalized, perspective-safe playback artifact. The append-only event ledger is stored once;
 * each frame names the prefix that was available at that point in the real game.
 */
@Serializable
data class PolicyInspectionBundle(
    val schemaVersion: Int = INSPECTION_SCHEMA_CURRENT,
    val gameId: String,
    val createdAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val deckManifestHash: String,
    val cardPoolHash: String,
    val profileManifestHash: String,
    val perspectivePlayerId: String,
    val policyVersion: String,
    val evaluatorVersion: String,
    val beliefVersion: String,
    val opponentModelVersion: String,
    /** Present only when a governed producer binds the replay before game generation. */
    val executionBinding: PolicyInspectionExecutionBinding? = null,
    val presentation: PolicyInspectionPresentation = PolicyInspectionPresentation(),
    val ledger: List<PolicyHistoryEvent>,
    val frames: List<PolicyInspectionFrame>,
    val outcome: PolicyInspectionOutcome,
) {
    init {
        require(schemaVersion == INSPECTION_SCHEMA_CURRENT) {
            "Unknown inspection schema $schemaVersion"
        }
        require(schemaVersion != INSPECTION_SCHEMA_V1 || presentation == PolicyInspectionPresentation()) {
            "Inspection schema v1 cannot carry presentation metadata"
        }
        require(gameId.isNotBlank())
        if (schemaVersion >= INSPECTION_SCHEMA_V4) {
            require(runCatching { java.util.UUID.fromString(gameId) }.isSuccess) {
                "Inspection schema v4 requires an opaque UUID game id"
            }
        }
        require(frames.isNotEmpty()) { "An inspection bundle requires an initial frame" }
        require(frames.map { it.frameIndex } == frames.indices.toList()) { "Inspection frames must be contiguous" }
        require(frames.first().afterDecisionIndex == null) { "Frame zero must precede every decision" }
        require(frames.drop(1).map { it.afterDecisionIndex } == frames.drop(1).indices.map { it }) {
            "Each later frame must follow exactly one wrapper decision"
        }
        require(frames.zipWithNext().all { (a, b) -> a.historyLength <= b.historyLength }) {
            "Inspection history cursors cannot move backward"
        }
        require(frames.all { it.historyLength in 0..ledger.size })
        require(frames.last().historyLength == ledger.size) { "Final frame must expose the complete safe ledger" }
        require(outcome.decisions == frames.size - 1)
        require(outcome.terminated xor outcome.truncated) {
            "Inspection must end in either an engine terminal state or an explicit decision cap"
        }
        require(frames.last().terminated == outcome.terminated)
        require(frames.last().winnerId == outcome.winnerId)
        frames.forEach { frame ->
            require(frame.observation.perspectivePlayerId == perspectivePlayerId)
            require(frame.knowledge.perspectivePlayerId == perspectivePlayerId)
        }
    }

    fun informationState(frameIndex: Int): PolicyInformationState {
        val frame = frames[frameIndex]
        return PolicyInformationState(
            actingPlayerId = frame.actingPlayerId,
            observation = frame.observation,
            informationStateDigest = frame.informationStateDigest,
            historyCommitment = frame.historyCommitment,
            history = ledger.take(frame.historyLength),
            knowledge = frame.knowledge,
            candidates = frame.candidates,
            candidateSchemaVersion = frame.candidateSchemaVersion,
            terminated = frame.terminated,
            winnerId = frame.winnerId,
        )
    }
}

@Serializable
data class PolicyInspectionFrame(
    val frameIndex: Int,
    /** Null only for frame zero; otherwise this frame is the state after the named decision. */
    val afterDecisionIndex: Int?,
    val actingPlayerId: String?,
    val observation: PolicyObservation,
    val knowledge: PolicyKnowledgeState,
    val candidates: List<SemanticChoice>,
    val candidateSchemaVersion: Int = CANDIDATE_SCHEMA_CURRENT,
    val historyLength: Int,
    val historyCommitment: PolicyHistoryCommitment,
    val informationStateDigest: String,
    val terminated: Boolean,
    val winnerId: String?,
    /** The transition that produced this frame; absent on frame zero. */
    val transition: PolicyInspectionTransition? = null,
    /** Present only when this perspective's search policy ran from this frame. */
    val search: PolicyInspectionSearch? = null,
) {
    init {
        require(frameIndex >= 0)
        require(afterDecisionIndex == frameIndex.takeIf { it > 0 }?.minus(1))
        require(historyLength >= 0)
        require(historyCommitment.cursor == historyLength)
        require(candidates.map { it.signature }.distinct().size == candidates.size)
        require((frameIndex == 0) == (transition == null))
        require(search == null || search.decisionIndex == frameIndex)
    }

    val historyDigest: String get() = historyCommitment.digest
}

@Serializable
data class PolicyInspectionTransition(
    val decisionIndex: Int,
    val actorId: String,
    /** Null when the perspective observed that a private response occurred but not its answer. */
    val observedChoice: SemanticChoice?,
    val privateResponse: Boolean,
    val eventStartInclusive: Int,
    val eventEndExclusive: Int,
) {
    init {
        require(decisionIndex >= 0)
        require(eventStartInclusive >= 0)
        require(eventEndExclusive > eventStartInclusive)
        require(!privateResponse || observedChoice == null)
    }
}

@Serializable
data class PolicyInspectionSearch(
    val decisionIndex: Int,
    val expansion: PolicyExpansion,
    val candidates: List<SearchCandidateStatistics>,
    val chosen: SemanticChoice,
    val heuristicChoice: SemanticChoice,
    val rootValue: Double,
    val beliefDiagnostics: BeliefDiagnostics,
    val searchDiagnostics: InformationSetSearchDiagnostics,
) {
    init {
        require(decisionIndex >= 0)
        require(rootValue.isFinite())
        require(chosen.signature in candidates.map { it.choice.signature })
        require(expansion.candidates.map { it.signature }.toSet()
            .all { it in candidates.map { statistic -> statistic.choice.signature }.toSet() }) {
            "Inspection search statistics must include every initially expanded candidate"
        }
    }
}

@Serializable
data class PolicyInspectionOutcome(
    val decisions: Int,
    val terminated: Boolean,
    val truncated: Boolean,
    val winnerId: String?,
    val resultByPlayer: Map<String, Double>,
) {
    init {
        require(decisions >= 0)
        require(terminated xor truncated)
        require(resultByPlayer.values.all { it.isFinite() && it in -1.0..1.0 })
    }
}
