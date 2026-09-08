package org.mtgallium.evaluation.searchteacher.cli.commands

import java.nio.file.Files
import kotlinx.serialization.encodeToString
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunFiles
import org.mtgallium.research.run.ResearchRunProvenance
import org.mtgallium.research.run.researchSha256
import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val outcomeLearningCommands = listOf(
    SearchTeacherCommand(
        "outcome-state-corpus-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runOutcomeStateCorpusPreflight,
    ),
    SearchTeacherCommand(
        "outcome-state-corpus", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runOutcomeStateCorpus,
    ),
    SearchTeacherCommand(
        "learned-outcome-value-gate", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runLearnedOutcomeValueGate,
    ),
    SearchTeacherCommand(
        "learned-outcome-value-global-signal", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runLearnedOutcomeValueGlobalSignal,
    ),
    SearchTeacherCommand(
        "learned-leaf-fixed-root-bind", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runLearnedLeafFixedRootBind,
    ),
    SearchTeacherCommand(
        "learned-leaf-fixed-root-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runLearnedLeafFixedRootPreflight,
    ),
    SearchTeacherCommand(
        "learned-leaf-fixed-root-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runLearnedLeafFixedRootPreflight,
    ),
    SearchTeacherCommand(
        "learned-leaf-pilot", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runLearnedLeafPilot,
    ),
    SearchTeacherCommand(
        "learned-leaf-pilot-smoke", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runLearnedLeafPilotSmoke,
    ),
)

private fun SearchTeacherCommandContext.runOutcomeStateCorpusPreflight() {
    val parentManifest = requireNotNull(options.corpusManifest) {
        "The retained Search Teacher extension research-run manifest is required via --corpus-manifest"
    }
    val output = diagnosticOutput(requireNotNull(options.outputPath) {
        "Replay-derived outcome-state preflight output is required via --output"
    })
    val report = OutcomeStateCorpusProducer(root, registry, manifest)
        .preflightPair(parentManifest)
    writeJsonAtomically(output, report)
    println(
        "Replay-derived outcome-state preflight verified pair ${report.pairIndex}, both replay legs, and " +
            "${report.games.sumOf { it.decisionBoundaryStates }} perspective-safe decision states: $output"
    )
}

private fun SearchTeacherCommandContext.runOutcomeStateCorpus() {
    val parentManifest = requireNotNull(options.corpusManifest) {
        "The retained Search Teacher extension research-run manifest is required via --corpus-manifest"
    }
    val output = diagnosticOutput(requireNotNull(options.outputPath) {
        "Replay-derived outcome-state corpus output is required via --output"
    })
    val corpus = OutcomeStateCorpusProducer(root, registry, manifest).run(parentManifest, output)
    println(
        "Replay-derived outcome-state corpus ${corpus.researchRunIdentity} verified and retained " +
            "${corpus.games.size} complete games across " +
            "${corpus.games.sumOf { it.decisionBoundaryStates }} perspective-safe decision states: $output"
    )
}

private fun SearchTeacherCommandContext.runLearnedOutcomeValueGate() {
    val corpusDirectory = requireNotNull(options.outcomeCorpus) {
        "A completed verified outcome-state corpus directory is required via --outcome-corpus"
    }
    val output = diagnosticOutput(requireNotNull(options.outputPath) {
        "Learned outcome-value gate output root is required via --output"
    })
    val result = LearnedOutcomeValueGateRunner(root).run(corpusDirectory, output)
    println(
        "Learned outcome-value gate training=${result.trainingRunIdentity}; " +
            "validation=${result.validationRunIdentity}; passed=${result.validationPassed}; " +
            "test=${result.testRunIdentity ?: "sealed"}; output=${result.outputRoot}"
    )
}

private fun SearchTeacherCommandContext.runLearnedOutcomeValueGlobalSignal() {
    val corpusDirectory = requireNotNull(options.outcomeCorpus) {
        "A completed verified outcome-state corpus directory is required via --outcome-corpus"
    }
    val gateDirectory = requireNotNull(options.learnedGate) {
        "A completed learned outcome-value gate directory is required via --learned-gate"
    }
    val output = diagnosticOutput(requireNotNull(options.outputPath) {
        "A fresh global-signal diagnostic output directory is required via --output"
    })
    val report = OutcomeValueGlobalSignalRunner(root).run(corpusDirectory, gateDirectory, output)
    println(
        "Learned outcome-value global signal ${report.diagnosticRunIdentity}; " +
            "partitions=${report.partitions.joinToString { it.split }}; output=$output",
    )
}

private fun SearchTeacherCommandContext.runLearnedLeafFixedRootBind() {
    val pilotDirectory = requireNotNull(options.fixedRootPilot)
    val stubPath = requireNotNull(options.fixedRootStub)
    val manifestPath = requireNotNull(options.fixedRootManifest)
    val corpusDirectory = requireNotNull(options.outcomeCorpus)
    val gateDirectory = requireNotNull(options.fixedRootGate)
    val historical = HistoricalOutcomeValueDiagnosticCheckpoint.load(corpusDirectory, gateDirectory)
    val diagnosticProvenance = ResearchRunProvenance.capture(root)
    diagnosticProvenance.requireReady()
    require(!diagnosticProvenance.outerDirty && !diagnosticProvenance.engineDirty) {
        "Fixed-root binding requires committed clean source and Argentum"
    }
    val bound = LearnedLeafFixedRootProductionBinder(
        pilotDirectory, registry, manifest, historical,
    ).bind(stubPath, manifestPath)
    println(
        "Bound ${bound.roots.size} result-blind fixed roots without settlement; " +
            "manifest=${bound.manifestId}; sha256=${sha256File(manifestPath)}; output=$manifestPath",
    )
}

private fun SearchTeacherCommandContext.runLearnedLeafFixedRootPreflight() {
    val pilotDirectory = requireNotNull(options.fixedRootPilot)
    val manifestPath = requireNotNull(options.fixedRootManifest)
    val corpusDirectory = requireNotNull(options.outcomeCorpus)
    val gateDirectory = requireNotNull(options.fixedRootGate)
    val loadedPanel = readLearnedLeafFixedRootManifest(manifestPath)
    val panel = loadedPanel.manifest.requireComplete()
    // The sole historical authority authenticates the retained completed gate directly. It
    // never recreates current trainer/validator provenance or materializes TEST frames.
    val historical = HistoricalOutcomeValueDiagnosticCheckpoint.load(corpusDirectory, gateDirectory)
    require(panel.pilot.corpusIdentity == historical.corpusIdentity)
    require(panel.pilot.trainingRunIdentity == historical.trainingRunIdentity)
    require(panel.pilot.validationRunIdentity == historical.validationRunIdentity)
    require(panel.pilot.testRunIdentity == historical.testRunIdentity)
    require(panel.pilot.checkpointPayloadSha256 == historical.checkpointPayloadSha256)
    val evaluator = historical.diagnosticEvaluator()
    require(panel.pilot.learnedModelConfigurationId == evaluator.configurationId)
    // Preflight is source-bound as well: do not authenticate a retained population through a
    // dirty diagnostic checkout whose behavior cannot later be reproduced or identified.
    val diagnosticProvenance = ResearchRunProvenance.capture(root)
    diagnosticProvenance.requireReady()
    require(!diagnosticProvenance.outerDirty && !diagnosticProvenance.engineDirty) {
        "Fixed-root diagnostic requires committed clean source and Argentum"
    }
    val diagnosticSourceIdentity = learnedLeafFixedRootDiagnosticSourceIdentity(diagnosticProvenance)
    val input = LearnedLeafFixedRootInputBinding(
        manifestSha256 = loadedPanel.sha256,
        pilotRunIdentity = panel.pilot.runIdentity,
        corpusIdentity = historical.corpusIdentity,
        trainingRunIdentity = historical.trainingRunIdentity,
        validationRunIdentity = historical.validationRunIdentity,
        testRunIdentity = historical.testRunIdentity,
        checkpointPayloadSha256 = historical.checkpointPayloadSha256,
        learnedModelConfigurationId = evaluator.configurationId,
        historicalSourceCommit = panel.mtgalliumSourceCommit,
        argentumCommit = panel.pilot.argentumCommit,
        diagnosticSourceIdentity = diagnosticSourceIdentity,
    )
    val materializer = LearnedLeafFixedRootProductionMaterializer(
        pilotDirectory, registry, manifest, panel, evaluator,
    )
    if (options.suite == "learned-leaf-fixed-root-preflight") {
        val refusals = panel.roots.mapNotNull { root ->
            (materializer.verifyBinding(root) as? LearnedLeafFixedRootBindingVerification.Refused)?.failures
        }.flatten()
        require(refusals.isEmpty()) { "Fixed-root source binding preflight refused: $refusals" }
        println("Learned-leaf fixed-root preflight authenticated ${panel.roots.size} source-bound roots; no settlement executed")
        return
    }
    val output = diagnosticOutput(requireNotNull(options.outputPath) {
        "Fixed-root diagnostic output is required via --output"
    })
    require(!Files.exists(output)) { "Fixed-root diagnostic output must be a fresh directory: $output" }
    val bindings = ResearchRunBindings(
        protocol = LEARNED_LEAF_FIXED_ROOT_PROTOCOL,
        material = mapOf(
            "selection-manifest" to input.manifestSha256,
            "pilot-run" to input.pilotRunIdentity,
            "corpus" to input.corpusIdentity,
            "training-run" to input.trainingRunIdentity,
            "validation-run" to input.validationRunIdentity,
            "test-run" to input.testRunIdentity,
            "checkpoint" to input.checkpointPayloadSha256,
            "model-configuration" to input.learnedModelConfigurationId,
            "historical-source" to input.historicalSourceCommit,
            "argentum" to input.argentumCommit,
            "diagnostic-source" to input.diagnosticSourceIdentity,
            "analysis" to input.analysisIdentity,
        ),
    )
    val report = runLearnedLeafFixedRootDiagnostic(
        panel, materializer, historical.trainOnlyFeatureReference(), input, bindings.identity,
    )
    Files.createDirectories(output)
    ResearchRunFiles.atomicWrite(
        ResearchRunFiles.resolveBelow(output, "report.json"),
        evidenceJson.encodeToString(report) + "\n",
    )
    ResearchRunArtifacts(output, bindings.identity).also {
        it.register("report.json")
        it.finalize()
    }
    println(
        "Learned-leaf fixed-root diagnostic materialized ${report.roots.size} frozen roots; " +
            "run=${report.diagnosticRunIdentity}; output=$output",
    )
}

private fun SearchTeacherCommandContext.runLearnedLeafPilot() {
    require(options.pairs == LEARNED_LEAF_PILOT_REQUIRED_PAIRS) {
        "Learned leaf pilot requires exactly $LEARNED_LEAF_PILOT_REQUIRED_PAIRS assigned pairs"
    }
    val corpusDirectory = requireNotNull(options.outcomeCorpus) {
        "A completed verified outcome-state corpus directory is required via --outcome-corpus"
    }
    val gateDirectory = requireNotNull(options.learnedGate) {
        "A completed learned outcome-value gate directory is required via --learned-gate"
    }
    val smokeDirectory = requireNotNull(options.learnedSmoke) {
        "A completed matching learned-leaf smoke directory is required via --learned-smoke"
    }
    val output = diagnosticOutput(requireNotNull(options.outputPath) {
        "Learned leaf pilot output is required via --output"
    })
    val promotion = LearnedOutcomeValueGateRunner(root).loadPromoted(corpusDirectory, gateDirectory)
    val candidate = prepareLearnedLeafPilot(promotion, root, registry, manifest, options.seed)
    val execution = LearnedLeafPilotSmokeRunner(root).loadAdmitted(candidate, smokeDirectory)
    val report = LearnedLeafPilotRunner(root).run(execution, options.threads, output)
    println(
        "Learned leaf pilot recorded ${report.assignedPairs} assigned pairs; valid=${report.valid}; " +
            "report: ${output.resolve("report.json")}",
    )
    check(report.valid) { "Learned leaf pilot is invalid: ${report.failureReasons}" }
}

private fun SearchTeacherCommandContext.runLearnedLeafPilotSmoke() {
    require(options.pairs == 1) { "Learned leaf pilot smoke requires exactly one non-population witness" }
    val corpusDirectory = requireNotNull(options.outcomeCorpus) {
        "A completed verified outcome-state corpus directory is required via --outcome-corpus"
    }
    val gateDirectory = requireNotNull(options.learnedGate) {
        "A completed learned outcome-value gate directory is required via --learned-gate"
    }
    val output = diagnosticOutput(requireNotNull(options.outputPath) {
        "Learned leaf pilot smoke output is required via --output"
    })
    val promotion = LearnedOutcomeValueGateRunner(root).loadPromoted(corpusDirectory, gateDirectory)
    val candidate = prepareLearnedLeafPilot(promotion, root, registry, manifest, options.seed)
    val report = LearnedLeafPilotSmokeRunner(root).run(candidate, output)
    println(
        "Learned leaf pilot smoke recorded one genuine 8x64 learned decision; " +
            "learned settlements=${report.settlementCounts.learnedOutcomeEstimateBackups}; " +
            "report: ${output.resolve("report.json")}",
    )
}

private fun learnedLeafFixedRootDiagnosticSourceIdentity(provenance: ResearchRunProvenance): String =
    "learned-leaf-fixed-root-diagnostic-source-sha256:" + researchSha256(
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
