package org.mtgallium.evaluation.searchteacher.cli.commands

import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val tournamentCommands = listOf(
    SearchTeacherCommand(
        "evaluator-comparison", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runEvaluatorComparison,
    ),
    SearchTeacherCommand(
        "outcome-qualification-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runOutcomeQualificationPreflight,
    ),
    SearchTeacherCommand(
        "outcome-qualification-pilot", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runOutcomeQualificationPreflight,
    ),
    SearchTeacherCommand(
        "search-budget-frontier-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runSearchBudgetFrontierPreflight,
    ),
    SearchTeacherCommand(
        "search-budget-frontier-pilot", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runSearchBudgetFrontierPreflight,
    ),
    SearchTeacherCommand(
        "search-budget-frontier-extension-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runSearchBudgetFrontierExtensionPreflight,
    ),
    SearchTeacherCommand(
        "search-budget-frontier-extension", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runSearchBudgetFrontierExtensionPreflight,
    ),
)

private fun SearchTeacherCommandContext.runEvaluatorComparison() {
    val bundle = EvaluatorComparisonRunner(root, registry, manifest).run()
    val directory = diagnosticOutput("evaluator-comparison")
    val referencesPath = directory.resolve("tactical-references.json")
    val stage0Path = directory.resolve("stage-0.json")
    val stage1Path = directory.resolve("stage-1-fixed-simulation.json")
    val stage2Path = directory.resolve("stage-2-fixed-time.json")
    val stage3Path = directory.resolve("stage-3-factorial.json")
    val reportPath = directory.resolve("report.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(referencesPath, bundle.references)
    writeJsonAtomically(stage0Path, bundle.stage0)
    writeJsonAtomically(stage1Path, bundle.stage1)
    writeJsonAtomically(stage2Path, bundle.stage2)
    writeJsonAtomically(stage3Path, bundle.stage3)
    writeJsonAtomically(reportPath, bundle.report)
    writeTextAtomically(markdownPath, renderEvaluatorComparison(bundle.report))
    println(
        "The bounded evaluator comparison completed stages 0-3 and diagnosed " +
            "${bundle.report.componentDiagnosis.primaryAttribution}. Accepted-set agreement is a tactical " +
            "screening metric, not a game-win estimate. Outputs: $reportPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runOutcomeQualificationPreflight() {
    val preflight = options.suite == "outcome-qualification-preflight"
    require(options.pairs == if (preflight) 1 else 50) {
        if (preflight) "Outcome-qualification preflight requires exactly one smoke pair" else
            "Outcome-qualification pilot requires exactly 50 assigned pairs"
    }
    val report = OutcomeQualificationPilotRunner(root, registry, manifest, options.seed)
        .run(options.pairs, options.threads, options.outputPath)
    val directory = options.outputPath ?: store.work(
        "outcome-qualification-pilot/${report.runIdentity.substringAfterLast(':').take(24)}"
    )
    println(
        "Outcome qualification ${if (preflight) "preflight" else "pilot"} recorded " +
            "${report.validPairs}/${report.assignedPairs} valid pairs; report: ${directory.resolve("report.md")}"
    )
    check(report.valid) { "Outcome qualification run did not materialize every assigned pair: ${report.failureReasons}" }
}

private fun SearchTeacherCommandContext.runSearchBudgetFrontierPreflight() {
    val preflight = options.suite == "search-budget-frontier-preflight"
    require(options.pairs == if (preflight) 1 else SEARCH_BUDGET_FRONTIER_REQUIRED_PAIRS) {
        if (preflight) "Search-budget frontier preflight requires exactly one smoke pair" else
            "Search-budget frontier pilot requires exactly $SEARCH_BUDGET_FRONTIER_REQUIRED_PAIRS assigned pairs"
    }
    val report = SearchBudgetFrontierRunner(root, registry, manifest, options.seed)
        .run(options.pairs, options.threads, options.outputPath)
    val directory = options.outputPath ?: store.work(
        "search-budget-frontier/${report.runIdentity.substringAfterLast(':').take(24)}"
    )
    println(
        "Search-budget frontier ${if (preflight) "preflight" else "pilot"} recorded " +
            "${report.validPairs}/${report.assignedPairs} valid pairs; decision=${report.decision}; " +
            "report: ${directory.resolve("report.md")}",
    )
    check(report.valid) { "Search-budget frontier run is invalid: ${report.failureReasons}" }
}

private fun SearchTeacherCommandContext.runSearchBudgetFrontierExtensionPreflight() {
    val preflight = options.suite == "search-budget-frontier-extension-preflight"
    require(options.pairs == if (preflight) 1 else SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS) {
        if (preflight) "Search-budget frontier extension preflight requires exactly one smoke pair" else
            "Search-budget frontier extension requires exactly $SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS assigned pairs"
    }
    val pairStart = if (preflight) SEARCH_BUDGET_FRONTIER_EXTENSION_START + SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS
        else SEARCH_BUDGET_FRONTIER_EXTENSION_START
    val report = SearchBudgetFrontierExtensionRunner(root, registry, manifest, options.seed)
        .run(pairStart, options.pairs, options.threads, options.outputPath)
    val directory = options.outputPath ?: store.work(
        "search-budget-frontier-extension/${report.extensionIdentity.substringAfterLast(':').take(24)}"
    )
    println(
        "Search-budget frontier ${if (preflight) "extension preflight" else "extension"} recorded " +
            "${report.extension.validPairs}/${report.extension.assignedPairs} valid extension pairs; " +
            "planner sidecars ${report.plannerArtifactsPresent}/${report.plannerArtifactsExpected}; " +
            "decision=${report.decision}; report: ${directory.resolve("report.md")}",
    )
    check(report.valid) { "Search-budget frontier extension is invalid: ${report.failureReasons}" }
}
