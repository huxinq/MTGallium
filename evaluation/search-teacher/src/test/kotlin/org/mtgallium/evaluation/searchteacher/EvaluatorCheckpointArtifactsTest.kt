package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

class EvaluatorCheckpointArtifactsTest {
    @Test
    fun `completed checkpoint report schema remains readable`() {
        val path = createTempFile("evaluator-checkpoint-report", ".json")
        Files.writeString(
            path,
            """
            {
              "schemaVersion": 1,
              "documentKind": "evaluator-checkpoint-diagnostic-report-v1",
              "protocolVersion": "$EVALUATOR_CHECKPOINT_PROTOCOL_VERSION",
              "generatedAtUtc": "2026-08-29T00:00:00Z",
              "source": {
                "runIdentity": "$EVALUATOR_CHECKPOINT_SOURCE_IDENTITY",
                "manifestSha256": "$EVALUATOR_CHECKPOINT_SOURCE_MANIFEST_SHA256",
                "reportSha256": "$EVALUATOR_CHECKPOINT_SOURCE_REPORT_SHA256",
                "outerCommit": "c4716f37eb681755db46abfd3af55d3cf7c2a3c1",
                "argentumCommit": "b41971b09f88c0a8a1d2c1d70d596ee782f444cd",
                "deckHash": "deck",
                "cardPoolHash": "pool",
                "policyEvidenceIdentities": {}
              },
              "protocolIdentitySha256": "27bd2c11e734b03c72a371516cc94bf19c93f331dc477fef2343b07dd2581914",
              "population": "16 preserved positions",
              "assignedPositions": 16,
              "includedByFamily": {"MULLIGAN": 2},
              "exclusionCounts": {"FAMILY_QUOTA_FILLED": 3038},
              "typedFailureCounts": {},
              "positionsWhereAnyVariantChangedTheSelectedAction": 11,
              "directObservations": [],
              "positions": [],
              "permittedConclusion": "Sensitivity was observed; improvement was not established.",
              "limits": []
            }
            """.trimIndent(),
        )

        val report = EvaluatorCheckpointArtifacts.readReport(path)

        assertEquals(16, report.assignedPositions)
        assertEquals(2, report.includedByFamily[EvaluatorCheckpointDecisionFamily.MULLIGAN])
        assertEquals(EVALUATOR_CHECKPOINT_SOURCE_IDENTITY, report.source.runIdentity)
    }
}
