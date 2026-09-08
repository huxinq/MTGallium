package org.mtgallium.evaluation.searchteacher.cli.commands

import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val researchWorkflowCommands = listOf(
    SearchTeacherCommand(
        "direct-attack-kernel-gameplay", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runDirectAttackKernelGameplay,
    ),
    SearchTeacherCommand(
        "research-preflight", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runResearchPreflight,
    ),
    SearchTeacherCommand(
        "research-preflight-verify", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runResearchPreflight,
    ),
    SearchTeacherCommand(
        "terminal-prediction-diagnostic", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runTerminalPredictionDiagnostic,
    ),
    SearchTeacherCommand(
        "attack-kernel-gameplay", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runAttackKernelGameplay,
    ),
    SearchTeacherCommand(
        "attack-kernel-learning", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runAttackKernelLearning,
    ),
    SearchTeacherCommand(
        "factual-residual-study", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runFactualResidualStudy,
    ),
    SearchTeacherCommand(
        "direct-attack-kernel-screen", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runDirectAttackKernelScreen,
    ),
    SearchTeacherCommand(
        "terminal-kernel-study", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runTerminalKernelStudy,
    ),
    SearchTeacherCommand(
        "terminal-target-sensitivity", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runTerminalTargetSensitivity,
    ),
    SearchTeacherCommand(
        "research-transfer-audit", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runResearchTransferAudit,
    ),
    SearchTeacherCommand(
        "campaign-data-use", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runCampaignDataUse,
    ),
    SearchTeacherCommand(
        "campaign-data-snapshot", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runCampaignDataSnapshot,
    ),
    SearchTeacherCommand(
        "real-game-position-bank", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runRealGamePositionBank,
    ),
    SearchTeacherCommand(
        "position-bank-terminal-continuations", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runPositionBankTerminalContinuations,
    ),
    SearchTeacherCommand(
        "position-bank-screen", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runPositionBankScreen,
    ),
    SearchTeacherCommand(
        "search-teacher-continuation", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runSearchTeacherContinuation,
    ),
    SearchTeacherCommand(
        "search-teacher-continuation-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runSearchTeacherContinuation,
    ),
    SearchTeacherCommand(
        "search-teacher-sequential", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runSearchTeacherSequential,
    ),
    SearchTeacherCommand(
        "search-teacher-calibration", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runSearchTeacherCalibration,
    ),
)

private fun SearchTeacherCommandContext.runResearchPreflight() {
    val output = diagnosticOutput(requireNotNull(options.outputPath))
    val report = ResearchPreflightRunner(root).run(requireNotNull(options.profilePath), output,
        verifyOnly = options.suite == "research-preflight-verify")
    println("Research preflight passed: ${report.bindings.identity}; output=$output")
}

private fun SearchTeacherCommandContext.runTerminalPredictionDiagnostic() {
    val plan = readPlan<TerminalPredictionDiagnosticPlan>()
    val report = runTerminalPredictionDiagnostic(root, plan, diagnosticOutput(requireNotNull(options.outputPath)))
    println("Terminal prediction diagnostic ${report.identity}: ${report.roots.size} development roots; descriptive only")
}

private fun SearchTeacherCommandContext.runAttackKernelGameplay() {
    val plan = readPlan<AttackKernelGameplayPlan>()
    val report = AttackKernelGameplayRunner(root).run(plan, diagnosticOutput(requireNotNull(options.outputPath)), requireNotNull(options.deckManifest))
    println("Attack kernel gameplay ${report.identity}: ${report.disposition}; passed=${report.strengthAndCostGatePassed}")
}

private fun SearchTeacherCommandContext.runAttackKernelLearning() {
    val plan = readPlan<AttackKernelLearningPlan>()
    val report = AttackKernelLearningRunner(root).run(plan, diagnosticOutput(requireNotNull(options.outputPath)), requireNotNull(options.deckManifest))
    println("Attack kernel learning ${report.researchRunIdentity}: ${report.disposition}")
}

private fun SearchTeacherCommandContext.runFactualResidualStudy() {
    val plan = readPlan<FactualResidualStudyPlan>()
    val output = diagnosticOutput(requireNotNull(options.outputPath))
    val report = FactualResidualStudy(root, registry, manifest).run(plan, output)
    println("Factual residual study ${report.bindings.identity}; report=${output.resolve("report.json")}")
}

private fun SearchTeacherCommandContext.runDirectAttackKernelScreen() {
    val plan = readPlan<DirectAttackKernelScreenPlan>()
    val report = DirectAttackKernelScreenRunner(root).run(plan, diagnosticOutput(requireNotNull(options.outputPath)), requireNotNull(options.deckManifest))
    println("Direct attack kernel screen ${report.identity}: ${report.disposition}; no deployed-strength conclusion")
}

private fun SearchTeacherCommandContext.runTerminalKernelStudy() {
    val plan = readPlan<TerminalKernelStudyPlan>()
    val report = TerminalKernelStudyRunner(root).run(plan, diagnosticOutput(requireNotNull(options.outputPath)), requireNotNull(options.deckManifest))
    println("Terminal kernel study ${report.identity}: exploratory gate=${report.gate.passed}; not a gameplay-strength result")
}

private fun SearchTeacherCommandContext.runTerminalTargetSensitivity() {
    val plan = readPlan<TerminalTargetSensitivityPlan>()
    val report = TerminalTargetSensitivityRunner(root).run(plan, diagnosticOutput(requireNotNull(options.outputPath)), requireNotNull(options.deckManifest))
    println("Terminal target sensitivity ${report.identity}: ${report.cells.size} declared cells; no target or model selection")
}

private fun SearchTeacherCommandContext.runResearchTransferAudit() {
    val plan = readPlan<ResearchTransferAuditPlan>()
    val report = ResearchTransferAuditRunner(root).run(plan, diagnosticOutput(requireNotNull(options.outputPath)))
    println("Screen-to-gameplay audit ${report.identity}: ${report.observations.size} authenticated links, ${report.inconclusiveGameplay} inconclusive gameplay results")
}

private fun SearchTeacherCommandContext.runCampaignDataUse() {
    val plan = readPlan<CampaignDataUsePlan>()
    val record = CampaignDataRegistry(root, diagnosticOutput(requireNotNull(options.outputPath)), plan.campaignId).record(plan)
    println("Campaign population use ${record.identity}: ${record.seedGroups.size} seed groups, ${record.rootIds.size} roots")
}

private fun SearchTeacherCommandContext.runCampaignDataSnapshot() {
    val plan = readPlan<CampaignSnapshotPlan>()
    val snapshot = retainCampaignSnapshot(root, plan, diagnosticOutput(requireNotNull(options.outputPath)))
    println("Campaign snapshot: ${snapshot.records.size} records, ${snapshot.groups.size} seed groups; unregistered access is unknown")
}

private fun SearchTeacherCommandContext.runRealGamePositionBank() {
    val plan = readPlan<RealGamePositionBankPlan>()
    val output = requireNotNull(options.outputPath)
    val report = RealGamePositionBankRunner(root, registry, manifest).run(plan, output)
    println("Real-game position bank ${report.bankIdentity}; report=${output.resolve("report.json")}")
    check(report.complete) { "Position bank includes reconstruction refusals; inspect the retained report" }
}

private fun SearchTeacherCommandContext.runPositionBankTerminalContinuations() {
    val plan = readPlan<PositionBankTerminalContinuationPlan>()
    val output = requireNotNull(options.outputPath)
    val report = PositionBankTerminalContinuationRunner(root, registry, manifest).run(plan, output, options.threads)
    println("Position terminal continuations complete=${report.complete}; report=${output.resolve("report.json")}")
    check(report.complete) { "Position continuations include refusals; inspect the retained report" }
}

private fun SearchTeacherCommandContext.runPositionBankScreen() {
    val plan = readPlan<PositionBankScreenPlan>()
    val output = requireNotNull(options.outputPath)
    val report = PositionBankScreenRunner(root, registry, manifest).run(plan, output, options.threads)
    println("Position screen ${report.researchRunIdentity}; valid=${report.valid}; report=${output.resolve("report.json")}")
    check(report.valid) { "Position screen includes refusals; inspect the retained report" }
}

private fun SearchTeacherCommandContext.runSearchTeacherContinuation() {
    val plan = readPlan<SearchTeacherContinuationPlan>()
    val report = SearchTeacherContinuationRunner(root, registry, manifest).run(plan, requireNotNull(options.outputPath),
        preflightOnly = options.suite == "search-teacher-continuation-preflight")
    println("Optional continuation: ${report?.result?.disposition ?: "preflight verified"}")
    if (report != null) check(report.operationalValid && report.treatmentIssues.isEmpty()) {
        "Continuation contains invalid gameplay or treatment issues; inspect its report"
    }
}

private fun SearchTeacherCommandContext.runSearchTeacherSequential() {
    val plan = readPlan<SearchTeacherSequentialPlan>()
    val output = requireNotNull(options.outputPath)
    val report = SearchTeacherCalibrationRunner(root, registry, manifest)
        .run(plan.calibration, output, options.threads, plan.rule)
    println("Sequential gameplay ${report.sequentialResult?.disposition}; valid=${report.valid}; report=${output.resolve("report.md")}")
    check(report.valid) { "Sequential gameplay includes invalid pairs; inspect the retained report" }
}

private fun SearchTeacherCommandContext.runSearchTeacherCalibration() {
    val plan = readPlan<SearchTeacherCalibrationPlan>()
    val output = requireNotNull(options.outputPath)
    val report = SearchTeacherCalibrationRunner(root, registry, manifest).run(plan, output, options.threads)
    println("Search Teacher calibration ${plan.phase}: valid=${report.valid}; report=${output.resolve("report.md")}")
    check(report.valid) { "Calibration includes invalid pairs; inspect the retained report" }
}

private fun SearchTeacherCommandContext.runDirectAttackKernelGameplay() {
    val plan = readPlan<DirectAttackKernelGameplayPlan>()
    val report = DirectAttackKernelGameplayRunner(root).run(plan, diagnosticOutput(requireNotNull(options.outputPath)), requireNotNull(options.deckManifest))
    println("Direct attack gameplay " + report.identity + ": comparator=" + plan.comparator +
        "; passed=" + report.comparisonStrengthAndCostGatePassed)
}
