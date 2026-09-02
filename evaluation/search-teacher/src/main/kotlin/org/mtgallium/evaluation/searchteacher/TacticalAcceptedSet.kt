package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.SemanticChoice

@Serializable
internal enum class TacticalAcceptedSetStatus { DRAFT, COMPLETE, INVALID_SCENARIO, UNCERTAIN }

@Serializable
internal data class TacticalReviewProvenance(
    val kind: String,
    val migratedFromSchemaVersion: Int? = null,
)

@Serializable
internal data class TacticalAcceptedSetLabel(
    val schemaVersion: Int,
    val documentKind: String,
    val sourcePacketSha256: String,
    val outerCommit: String,
    val argentumCommit: String,
    val scenarioId: String,
    val caseId: String,
    val category: TacticalCategory,
    val perspectivePlayerId: String,
    val informationStateDigest: String,
    val historyCommitment: PolicyHistoryCommitment,
    val proposalVersion: String,
    val candidateSetExhaustive: Boolean,
    val estimatedCandidateCount: Long?,
    val status: TacticalAcceptedSetStatus,
    val acceptedChoices: List<SemanticChoice>,
    val author: String,
    val reviewer: String,
    val provenance: TacticalReviewProvenance,
    val scenarioNotes: String,
    val createdAtUtc: String,
    val updatedAtUtc: String,
) {
    init {
        require(schemaVersion == 3 && documentKind == "tactical-accepted-set-label-v3")
        require(sourcePacketSha256.matches(Regex("[0-9a-f]{64}")))
        require(provenance.kind == "HUMAN_REVIEW")
        require(acceptedChoices.map { it.signature }.distinct().size == acceptedChoices.size)
    }
}

@Serializable
internal data class TacticalAcceptedSetLabelSet(
    val schemaVersion: Int,
    val documentKind: String,
    val sourcePacketSha256: String,
    val outerCommit: String,
    val argentumCommit: String,
    val exportedAtUtc: String,
    val labels: List<TacticalAcceptedSetLabel>,
) {
    init {
        require(schemaVersion == 3 && documentKind == "tactical-accepted-set-label-set-v3")
        require(sourcePacketSha256.matches(Regex("[0-9a-f]{64}")))
        require(labels.map { it.caseId }.distinct().size == labels.size)
        require(labels.all { it.sourcePacketSha256 == sourcePacketSha256 })
    }
}

internal data class ValidatedTacticalAcceptedSet(
    val packet: TacticalAuthoringPacket,
    val labels: TacticalAcceptedSetLabelSet,
    val conformance: TacticalHorizonConformanceReport,
    val acceptedByCase: Map<String, Set<String>>,
)

internal fun loadValidatedTacticalAcceptedSet(
    packetPath: Path,
    labelPath: Path,
    conformancePath: Path,
): ValidatedTacticalAcceptedSet {
    require(Files.isRegularFile(packetPath)) { "Tactical packet does not exist: $packetPath" }
    require(Files.isRegularFile(labelPath)) { "Tactical accepted-set review does not exist: $labelPath" }
    require(Files.isRegularFile(conformancePath)) { "Tactical conformance report does not exist: $conformancePath" }
    val packet = evidenceJson.decodeFromString<TacticalAuthoringPacket>(Files.readString(packetPath))
    val labels = evidenceJson.decodeFromString<TacticalAcceptedSetLabelSet>(Files.readString(labelPath))
    val conformance = evidenceJson.decodeFromString<TacticalHorizonConformanceReport>(
        Files.readString(conformancePath)
    )
    val packetSha = sha256File(packetPath)
    require(packet.suiteVersion == TACTICAL_HORIZON_SUITE_VERSION)
    require(labels.sourcePacketSha256 == packetSha) { "Review is bound to a different tactical packet" }
    require(labels.outerCommit == packet.outerCommit && labels.argentumCommit == packet.argentumCommit)
    require(conformance.suiteVersion == packet.suiteVersion)
    require(conformance.sourcePacketSha256 == packetSha) {
        "Conformance report is bound to a different tactical packet"
    }
    require(conformance.outerCommit == packet.outerCommit && conformance.argentumCommit == packet.argentumCommit)
    require(conformance.readyForBlindReview && conformance.failureReasons.isEmpty()) {
        "Tactical packet did not pass the blind-review conformance gate: ${conformance.failureReasons}"
    }
    require(labels.labels.map { it.caseId }.toSet() == packet.scenarios.map { it.caseId }.toSet()) {
        "Review must cover every horizon scenario"
    }
    require(conformance.cases.map { it.caseId }.toSet() == packet.scenarios.map { it.caseId }.toSet()) {
        "Conformance report must cover every horizon scenario"
    }
    val auditByCase = conformance.cases.associateBy(TacticalHorizonCaseAudit::caseId)
    packet.scenarios.forEach { scenario ->
        val audit = requireNotNull(auditByCase[scenario.caseId])
        require(audit.stateContractPassed && audit.stateContractFailures.isEmpty())
        require(audit.rootExpansionExhaustive && audit.expectedActionMatchCount == 1 && audit.reviewEligible)
        require(audit.informationStateDigest == scenario.informationState.informationStateDigest) {
            "${scenario.caseId} conformance digest differs from the review packet"
        }
        val legal = scenario.candidateExpansion.candidates.map { it.signature }.toSet()
        require(audit.expectedActionSignature in legal) {
            "${scenario.caseId} conformance expectation is absent from the packet"
        }
        require(
            audit.certificationStatus != TacticalCertificationStatus.CERTIFIED ||
                audit.expectedSingletonCertified
        ) { "${scenario.caseId} has a completed terminal proof that rejects singleton correctness" }
    }
    val accepted = labels.labels.associate { label ->
        val scenario = packet.scenarios.single { it.caseId == label.caseId }
        val audit = auditByCase.getValue(label.caseId)
        require(label.status == TacticalAcceptedSetStatus.COMPLETE) { "${label.caseId} is not complete" }
        require(label.author.isNotBlank() && label.reviewer.isNotBlank()) {
            "${label.caseId} needs nonblank author and reviewer provenance"
        }
        require(label.candidateSetExhaustive && scenario.candidateExpansion.isExhaustive)
        require(label.scenarioId == scenario.scenarioId)
        require(label.category == scenario.category)
        require(label.perspectivePlayerId == scenario.informationState.observation.perspectivePlayerId)
        require(label.informationStateDigest == scenario.informationState.informationStateDigest)
        require(label.historyCommitment == scenario.informationState.historyCommitment)
        require(label.proposalVersion == scenario.candidateExpansion.proposalVersion)
        val legal = scenario.candidateExpansion.candidates.map { it.signature }.toSet()
        val selected = label.acceptedChoices.map { it.signature }.toSet()
        require(selected.isNotEmpty() && selected.all(legal::contains)) { "${label.caseId} has an invalid accepted set" }
        require(selected.size == 1) { "${label.caseId} must have exactly one accepted raw action" }
        if (audit.authority == TacticalEvidenceAuthority.CERTIFIED) {
            require(selected == audit.acceptedSignatures) {
                "${label.caseId} human label disagrees with its terminal certificate"
            }
        }
        label.caseId to selected
    }
    return ValidatedTacticalAcceptedSet(packet, labels, conformance, accepted)
}
