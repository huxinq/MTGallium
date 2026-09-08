package org.mtgallium.evaluation.searchteacher.cli.commands

import java.nio.file.Path
import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val decisionLocalCommands = listOf(
    SearchTeacherCommand(
        "decision-local-learnability-pilot", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runDecisionLocalLearnabilityPilot,
    ),
    SearchTeacherCommand(
        "decision-local-performance-check", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runDecisionLocalPerformanceCheck,
    ),
    SearchTeacherCommand(
        "decision-local-root-coverage", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runDecisionLocalRootCoverage,
    ),
    SearchTeacherCommand(
        "decision-local-root-coverage-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runDecisionLocalRootCoverage,
    ),
    SearchTeacherCommand(
        "decision-local-precision-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runDecisionLocalPrecisionPreflight,
    ),
    SearchTeacherCommand(
        "decision-local-precision-followup", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runDecisionLocalPrecisionPreflight,
    ),
    SearchTeacherCommand(
        "decision-local-root-freeze", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runDecisionLocalRootFreeze,
    ),
    SearchTeacherCommand(
        "decision-local-throughput-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runDecisionLocalThroughputPreflight,
    ),
    SearchTeacherCommand(
        "decision-local-sibling-outcome", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runDecisionLocalSiblingOutcome,
    ),
    SearchTeacherCommand(
        "decision-local-sibling-signal", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runDecisionLocalSiblingOutcome,
    ),
)

private fun SearchTeacherCommandContext.runDecisionLocalLearnabilityPilot() {
    val output = diagnosticOutput(requireNotNull(options.outputPath))
    val report = DecisionLocalLearnabilityPilot(root).run(requireNotNull(options.precisionParent),
        requireNotNull(options.precisionRun), output)
    println("Learnability ${report.researchRunIdentity}: ${report.conclusion}; output=$output")
}

private fun SearchTeacherCommandContext.runDecisionLocalPerformanceCheck() {
    DecisionLocalPerformanceCheck(root, registry, manifest).run(requireNotNull(options.coverageParent),
        requireNotNull(options.fixedRootPilot), requireNotNull(options.outcomeCorpus), requireNotNull(options.fixedRootGate),
        diagnosticOutput(requireNotNull(options.outputPath)))
}

private fun SearchTeacherCommandContext.runDecisionLocalRootCoverage() {
    val output = diagnosticOutput(requireNotNull(options.outputPath))
    val runner = DecisionLocalRootCoverage(root, registry, manifest)
    if (options.suite == "decision-local-root-coverage-preflight") {
        runner.preflight(requireNotNull(options.coverageParent), requireNotNull(options.fixedRootPilot),
            requireNotNull(options.outcomeCorpus), requireNotNull(options.fixedRootGate), options.threads, output)
        println("Root coverage preflight passed; output=$output")
    } else {
        val report = runner.run(requireNotNull(options.coverageParent), requireNotNull(options.fixedRootPilot),
            requireNotNull(options.outcomeCorpus), requireNotNull(options.fixedRootGate), options.threads, output,
            System.getenv("MTGALLIUM_PROGRESS_FILE")?.takeIf(String::isNotBlank)?.let(Path::of))
        println("Root coverage ${report.researchRunIdentity}: ${report.conclusion}; output=$output")
    }
}

private fun SearchTeacherCommandContext.runDecisionLocalPrecisionPreflight() {
    val output = diagnosticOutput(requireNotNull(options.outputPath))
    val runner = DecisionLocalPrecisionFollowup(root, registry, manifest)
    if (options.suite == "decision-local-precision-preflight") {
        runner.preflight(requireNotNull(options.precisionParent), requireNotNull(options.fixedRootManifest),
            requireNotNull(options.fixedRootPilot), output)
        println("Precision preflight verified the retained population and two old terminal outcomes; output=$output")
    } else {
        val progress = System.getenv("MTGALLIUM_PROGRESS_FILE")?.takeIf(String::isNotBlank)?.let(Path::of)
        val report = runner.run(requireNotNull(options.precisionParent), requireNotNull(options.fixedRootManifest),
            requireNotNull(options.fixedRootPilot), output, progress)
        println("Precision follow-up ${report.researchRunIdentity}: ${report.conclusion}; output=$output")
        check(report.failedRoots == 0 && report.completedTerminalContinuations == 2808) { "Inspect retained incomplete precision population" }
    }
}

private fun SearchTeacherCommandContext.runDecisionLocalRootFreeze() {
    val output = diagnosticOutput(requireNotNull(options.outputPath))
    val frozen = DecisionLocalRootFreezer(root, registry, manifest).freeze(
        pilotDirectory = requireNotNull(options.fixedRootPilot),
        corpusDirectory = requireNotNull(options.outcomeCorpus),
        gateDirectory = requireNotNull(options.fixedRootGate),
        output = output,
    )
    println(
        "Froze ${frozen.assignments.size} result-blind primary roots; " +
            "manifest=${frozen.manifestId}; output=${output.resolve("root-manifest.json")}",
    )
}

private fun SearchTeacherCommandContext.runDecisionLocalThroughputPreflight() {
    val output = diagnosticOutput(requireNotNull(options.outputPath))
    val report = DecisionLocalExperimentRunner(root, registry, manifest).preflight(
        pilotDirectory = requireNotNull(options.fixedRootPilot),
        rootManifestPath = requireNotNull(options.fixedRootManifest),
        corpusDirectory = requireNotNull(options.outcomeCorpus),
        gateDirectory = requireNotNull(options.fixedRootGate),
        output = output,
    )
    println(
        "Decision-local throughput preflight completed ${report.terminalContinuationsCompleted} terminal continuations " +
            "without serializing outcomes; elapsed=${report.elapsedMillis}ms; failures=${report.failures.size}",
    )
}

private fun SearchTeacherCommandContext.runDecisionLocalSiblingOutcome() {
    val output = diagnosticOutput(requireNotNull(options.outputPath))
    val progress = System.getenv("MTGALLIUM_PROGRESS_FILE")?.takeIf(String::isNotBlank)?.let(Path::of)
    val report = DecisionLocalExperimentRunner(root, registry, manifest).run(
        pilotDirectory = requireNotNull(options.fixedRootPilot),
        rootManifestPath = requireNotNull(options.fixedRootManifest),
        corpusDirectory = requireNotNull(options.outcomeCorpus),
        gateDirectory = requireNotNull(options.fixedRootGate),
        challengeManifestPaths = options.challengeManifests,
        output = output,
        progressPath = progress,
        signalOnly = options.suite == "decision-local-sibling-signal",
    )
    println(
        "Decision-local sibling outcome experiment ${report.scientificEvidenceIdentity}; " +
            "conclusion=${report.conclusion}; roots=${report.admittedRoots}/${report.primaryRoots}; output=$output",
    )
}
