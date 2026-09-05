package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunFiles
import org.mtgallium.research.run.ResearchRunProvenance
import org.mtgallium.research.run.researchSha256

private const val OUTCOME_VALUE_GLOBAL_SIGNAL_REPORT_FILE = "report.json"

/**
 * Read-only host for a historically authenticated immutable checkpoint and its verified corpus.
 * It is intentionally diagnostic-only: it never creates a production promotion capability, fits
 * a model, or alters a checkpoint. Its audit verifies a recorded validation PASS and historical
 * TEST evidence before it can open the current corpus TEST partition.
 */
internal class OutcomeValueGlobalSignalRunner(
    private val repositoryRoot: Path,
) {
    fun run(
        corpusDirectory: Path,
        gateDirectory: Path,
        outputDirectory: Path,
    ): OutcomeValueGlobalSignalReport {
        val output = outputDirectory.toAbsolutePath().normalize()
        require(!Files.exists(output)) {
            "Global-signal diagnostic output must be fresh; use a new directory rather than overwriting $output"
        }
        val diagnosticProvenance = ResearchRunProvenance.capture(repositoryRoot)
        diagnosticProvenance.requireReady()
        require(!diagnosticProvenance.outerDirty && !diagnosticProvenance.engineDirty) {
            "Historical global-signal diagnostic evidence requires committed clean source and Argentum"
        }
        val historical = HistoricalOutcomeValueDiagnosticCheckpoint.load(corpusDirectory, gateDirectory)
        val bindings = ResearchRunBindings(
            protocol = OUTCOME_VALUE_GLOBAL_SIGNAL_PROTOCOL,
            material = mapOf(
                "historical-training-run" to historical.trainingRunIdentity,
                "historical-validation-run" to historical.validationRunIdentity,
                "historical-test-run" to historical.testRunIdentity,
                "checkpoint" to historical.checkpointPayloadSha256,
                "corpus" to historical.corpusIdentity,
                "pair-split" to historical.pairSplitIdentity,
                "frozen-train-constant" to historicalFrozenTrainConstantIdentity(historical),
                "diagnostic-source" to diagnosticSourceIdentity(diagnosticProvenance),
                "analysis" to OUTCOME_VALUE_GLOBAL_SIGNAL_PROTOCOL +
                    ":equal-frame-and-equal-game-v1:fixed-score-bins-5:canonical-game-rotation-1:temporal-progress-length-v1:frozen-train-constant-v1",
            ),
        )
        val report = OutcomeValueGlobalSignalReport(
            diagnosticRunIdentity = bindings.identity,
            trainingRunIdentity = historical.trainingRunIdentity,
            validationRunIdentity = historical.validationRunIdentity,
            historicalTestRunIdentity = historical.testRunIdentity,
            checkpointPayloadSha256 = historical.checkpointPayloadSha256,
            corpusIdentity = historical.corpusIdentity,
            pairSplitIdentity = historical.pairSplitIdentity,
            partitions = historical.globalSignalPartitions(),
        )
        Files.createDirectories(output)
        ResearchRunFiles.atomicWrite(
            ResearchRunFiles.resolveBelow(output, OUTCOME_VALUE_GLOBAL_SIGNAL_REPORT_FILE),
            diagnosticJson.encodeToString(report) + "\n",
        )
        ResearchRunArtifacts(output, bindings.identity).also {
            it.register(OUTCOME_VALUE_GLOBAL_SIGNAL_REPORT_FILE)
            it.finalize()
        }
        return report
    }
}

private fun historicalFrozenTrainConstantIdentity(historical: HistoricalOutcomeValueDiagnosticCheckpoint): String =
    "historical-outcome-value-frozen-train-constant-sha256:" + researchSha256(
        "${historical.trainingRunIdentity}|${historical.checkpointPayloadSha256}|${historical.frozenTrainConstantPrediction.toBits()}",
    )

private fun diagnosticSourceIdentity(provenance: ResearchRunProvenance): String =
    "historical-outcome-value-diagnostic-source-sha256:" + researchSha256(
        listOf(
            provenance.outerCommit,
            provenance.expectedEngineCommit,
            provenance.checkedOutEngineCommit,
            provenance.sourceProvenance.outer.revision,
            provenance.sourceProvenance.outer.trackedDiffSha256,
            provenance.sourceProvenance.outer.untrackedContentSha256,
            provenance.sourceProvenance.outer.statusSha256,
            provenance.sourceProvenance.argentum.revision,
            provenance.sourceProvenance.argentum.trackedDiffSha256,
            provenance.sourceProvenance.argentum.untrackedContentSha256,
            provenance.sourceProvenance.argentum.statusSha256,
        ).joinToString("\n"),
    )

private val diagnosticJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
