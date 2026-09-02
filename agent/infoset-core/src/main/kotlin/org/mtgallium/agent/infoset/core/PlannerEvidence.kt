package org.mtgallium.agent.infoset.core

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.mtgallium.research.run.ResearchRunFiles

/**
 * Safe, planner-only evidence bound to a completed trajectory.  It deliberately references the
 * trajectory's decision view, candidate vector, and expansion record instead of serializing them.
 */
const val PLANNER_EVIDENCE_SCHEMA_CURRENT: Int = 1

@Serializable
data class PlannerEvidenceBinding(
    val gameId: String,
    val safeTrajectoryReference: String,
    val safeTrajectorySha256: String,
    val trajectorySchemaVersion: Int,
    val candidateSchemaVersion: Int,
    val behaviorBinding: PolicyBehaviorBinding,
    val actionSpaceProfile: SearchActionSpaceProfile,
    /** Present for governed corpus production; absent for an otherwise valid standalone smoke. */
    val researchRunIdentity: String? = null,
) {
    init {
        require(gameId.isNotBlank())
        require(safeTrajectoryReference.isNotBlank() && !safeTrajectoryReference.startsWith('/') && ':' !in safeTrajectoryReference)
        require(!safeTrajectoryReference.replace('\\', '/').contains("privileged", ignoreCase = true))
        require(safeTrajectorySha256.matches(Regex("[0-9a-f]{64}")))
        require(trajectorySchemaVersion == TRAJECTORY_SCHEMA_CURRENT)
        require(candidateSchemaVersion == CANDIDATE_SCHEMA_CURRENT)
        require(researchRunIdentity == null || researchRunIdentity.isNotBlank())
    }
}

@Serializable
data class PlannerEvidenceWork(
    val searchLatencyMillis: Double,
    val simulations: Int,
    val searchWorldSteps: Int,
    val rolloutDecisions: Int,
    val policyAnnotatedExpansions: Int,
    val rejectedTransitions: Int,
) {
    init {
        require(searchLatencyMillis >= 0.0 && searchLatencyMillis.isFinite())
        require(simulations >= 0 && searchWorldSteps >= 0 && rolloutDecisions >= 0)
        require(policyAnnotatedExpansions >= 0 && rejectedTransitions >= 0)
    }
}

@Serializable
data class PlannerEvidenceCandidate(
    /** Resolves only against the bound safe trajectory decision's candidate vector. */
    val candidateSignature: String,
    val rawVisits: Int,
    val backedMean: Double,
    val settlementCounts: SearchSettlementCounts,
) {
    init {
        require(candidateSignature.isNotBlank())
        require(rawVisits >= 0)
        require(backedMean.isFinite())
        require(settlementCounts.successfulBackups == rawVisits) {
            "Candidate settlement counts must partition its successful backed visits"
        }
    }
}

@Serializable
data class PlannerEvidenceDecision(
    val gameId: String,
    val decisionIndex: Int,
    val actingPlayerId: String,
    /** Existing safe decision identity; the sidecar cannot be used without that exact decision. */
    val informationStateDigest: String,
    val selectedCandidateSignature: String,
    val candidates: List<PlannerEvidenceCandidate>,
    val work: PlannerEvidenceWork,
) {
    init {
        require(gameId.isNotBlank() && decisionIndex >= 0 && actingPlayerId.isNotBlank())
        require(informationStateDigest.isNotBlank() && selectedCandidateSignature.isNotBlank())
        require(candidates.map(PlannerEvidenceCandidate::candidateSignature).distinct().size == candidates.size)
        require(selectedCandidateSignature in candidates.map(PlannerEvidenceCandidate::candidateSignature))
    }
}

@Serializable
data class PlannerEvidenceSidecar(
    val schemaVersion: Int = PLANNER_EVIDENCE_SCHEMA_CURRENT,
    val binding: PlannerEvidenceBinding,
    val decisions: List<PlannerEvidenceDecision>,
) {
    init {
        require(schemaVersion == PLANNER_EVIDENCE_SCHEMA_CURRENT) { "Unknown planner-evidence schema $schemaVersion" }
        require(decisions.all { it.gameId == binding.gameId })
        require(decisions.map(PlannerEvidenceDecision::decisionIndex).distinct().size == decisions.size)
    }

    fun writeCompressed(path: Path): Path {
        val encoded = PolicyJson.format.encodeToString(PlannerEvidenceSidecar.serializer(), this)
        PublicArtifactPrivacy.requireSafeJson(encoded, "planner evidence sidecar")
        val compressed = ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).bufferedWriter().use { it.write(encoded + "\n") }
            bytes.toByteArray()
        }
        return ResearchRunFiles.atomicWrite(path, compressed)
    }

    companion object {
        fun readCompressed(path: Path): PlannerEvidenceSidecar =
            GZIPInputStream(java.nio.file.Files.newInputStream(path)).bufferedReader().use { reader ->
                val encoded = reader.readText()
                PublicArtifactPrivacy.requireSafeJson(encoded, "planner evidence sidecar")
                PolicyJson.format.decodeFromString(PlannerEvidenceSidecar.serializer(), encoded)
            }
    }
}

fun InformationSetSearchResult.plannerEvidenceDecision(
    gameId: String,
    decisionIndex: Int,
    actingPlayerId: String,
    informationStateDigest: String,
    latencyMillis: Double,
): PlannerEvidenceDecision = PlannerEvidenceDecision(
    gameId = gameId,
    decisionIndex = decisionIndex,
    actingPlayerId = actingPlayerId,
    informationStateDigest = informationStateDigest,
    selectedCandidateSignature = chosen.signature,
    candidates = candidates.map { candidate ->
        PlannerEvidenceCandidate(
            candidateSignature = candidate.choice.signature,
            rawVisits = candidate.visits,
            backedMean = candidate.meanValue,
            settlementCounts = settlementCountsFor(candidate.choice),
        )
    },
    work = PlannerEvidenceWork(
        searchLatencyMillis = latencyMillis,
        simulations = diagnostics.simulations,
        searchWorldSteps = diagnostics.searchWorldSteps,
        rolloutDecisions = diagnostics.rootRolloutDecisions + diagnostics.opponentRolloutDecisions,
        policyAnnotatedExpansions = diagnostics.policyAnnotatedExpansions,
        rejectedTransitions = diagnostics.rejectedTransitions,
    ),
)
