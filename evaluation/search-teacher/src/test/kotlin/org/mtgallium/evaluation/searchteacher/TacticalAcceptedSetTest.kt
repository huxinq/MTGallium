package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource

@ScenarioExecutionTest
class TacticalAcceptedSetTest {
    @Test
    fun `validated human accepted set drives the horizon benchmark`() {
        val root = createTempDirectory("tactical-accepted-set")
        val registry = buildRegistry()
        val manifest = loadDeckManifest()
        val (packet, packetPath) = TacticalAuthoringPacketGenerator(root, registry, manifest)
            .generateHorizonSuite()
        val packetSha = sha256File(packetPath)
        val labels = TacticalAcceptedSetLabelSet(
            schemaVersion = 3,
            documentKind = "tactical-accepted-set-label-set-v3",
            sourcePacketSha256 = packetSha,
            outerCommit = packet.outerCommit,
            argentumCommit = packet.argentumCommit,
            exportedAtUtc = packet.generatedAtUtc,
            labels = packet.scenarios.map { scenario ->
                TacticalAcceptedSetLabel(
                    schemaVersion = 3,
                    documentKind = "tactical-accepted-set-label-v3",
                    sourcePacketSha256 = packetSha,
                    outerCommit = packet.outerCommit,
                    argentumCommit = packet.argentumCommit,
                    scenarioId = scenario.scenarioId,
                    caseId = scenario.caseId,
                    category = scenario.category,
                    perspectivePlayerId = scenario.informationState.observation.perspectivePlayerId,
                    informationStateDigest = scenario.informationState.informationStateDigest,
                    historyCommitment = scenario.informationState.historyCommitment,
                    proposalVersion = scenario.candidateExpansion.proposalVersion,
                    candidateSetExhaustive = scenario.candidateExpansion.isExhaustive,
                    estimatedCandidateCount = scenario.candidateExpansion.estimatedCandidateCount,
                    status = TacticalAcceptedSetStatus.COMPLETE,
                    acceptedChoices = listOf(scenario.candidateExpansion.candidates.first()),
                    author = "Codex",
                    reviewer = "test reviewer",
                    provenance = TacticalReviewProvenance("HUMAN_REVIEW"),
                    scenarioNotes = "",
                    createdAtUtc = packet.generatedAtUtc,
                    updatedAtUtc = packet.generatedAtUtc,
                )
            },
        )
        val labelPath = root.resolve("accepted.json")
        Files.writeString(labelPath, evidenceJson.encodeToString(TacticalAcceptedSetLabelSet.serializer(), labels))
        val conformance = TacticalHorizonConformanceReport(
            generatedAtUtc = packet.generatedAtUtc,
            outerCommit = packet.outerCommit,
            argentumCommit = packet.argentumCommit,
            sourcePacketSha256 = packetSha,
            oracleMaxStrategicDepth = 128,
            oracleMaxExpandedNodesPerCase = 100_000,
            oracleMaxWallClockMillisPerCase = 60_000,
            cases = packet.scenarios.map { scenario ->
                val definition = TacticalHorizonCatalog.cases.single { it.id == scenario.caseId }
                TacticalHorizonCaseAudit(
                    caseId = scenario.caseId,
                    horizon = definition.horizon,
                    category = scenario.category,
                    informationStateDigest = scenario.informationState.informationStateDigest,
                    stateContractPassed = true,
                    stateContractFailures = emptyList(),
                    rootExpansionExhaustive = true,
                    rootActionCount = scenario.candidateExpansion.candidates.size,
                    expectedActionMatchCount = 1,
                    expectedActionSignature = scenario.candidateExpansion.candidates.first().signature,
                    reviewEligible = true,
                    certificationStatus = TacticalCertificationStatus.UNCERTIFIED,
                    authority = TacticalEvidenceAuthority.DIAGNOSTIC,
                    acceptedSignatures = emptySet(),
                    expectedSingletonCertified = false,
                    nondiscriminating = false,
                    actionValues = emptyList(),
                    diagnostic = "TEST_RESOURCE_LIMIT",
                )
            },
            contractPassedCases = packet.scenarios.size,
            reviewEligibleCases = packet.scenarios.size,
            certifiedCases = 0,
            diagnosticCases = packet.scenarios.size,
            readyForBlindReview = true,
            allCasesCertified = false,
            failureReasons = emptyList(),
        )
        val conformancePath = root.resolve("conformance.json")
        Files.writeString(
            conformancePath,
            evidenceJson.encodeToString(TacticalHorizonConformanceReport.serializer(), conformance),
        )
        val validated = loadValidatedTacticalAcceptedSet(packetPath, labelPath, conformancePath)

        assertEquals(28, validated.acceptedByCase.size)
        val report = TacticalHorizonBenchmarkRunner(
            registry = registry,
            manifest = manifest,
            particles = 2,
            simulations = 4,
            maxPolicyDecisions = 4,
            maxQuiescenceDecisions = 4,
            leafConfigurations = listOf(
                LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                )
            ),
        ).run(validated, sha256File(labelPath), TacticalHorizonCatalog.cases.take(1))

        assertTrue(report.completed, report.failureReasons.toString())
        assertEquals(1, report.leafResults.single().completedTrials)
    }
}
