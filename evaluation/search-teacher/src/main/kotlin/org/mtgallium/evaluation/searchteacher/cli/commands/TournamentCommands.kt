package org.mtgallium.evaluation.searchteacher.cli.commands

import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import org.mtgallium.research.run.ResearchRunArtifacts
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
        "pilot-calibrate", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runPilotCalibrate,
    ),
    SearchTeacherCommand(
        "tournament-amendment", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTournamentAmendment,
    ),
    SearchTeacherCommand(
        "tournament-v3-calibrated", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTournamentV3Calibrated,
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
    SearchTeacherCommand(
        "baseline-factorial-tournament", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runBaselineFactorialTournament,
    ),
    SearchTeacherCommand(
        "baseline-factorial-smoke", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runBaselineFactorialSmoke,
    ),
    SearchTeacherCommand(
        "tournament-fallback-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTournamentFallbackDiagnostic,
    ),
    SearchTeacherCommand(
        "tree-reuse-validation", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTreeReuseValidation,
    ),
    SearchTeacherCommand(
        "tournament-performance", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTournamentPerformance,
    ),
    SearchTeacherCommand(
        "tournament-remediation", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTournamentRemediation,
    ),
    SearchTeacherCommand(
        "tournament-remediation-check", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTournamentRemediationCheck,
    ),
    SearchTeacherCommand(
        "tournament-remediation-probe", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTournamentRemediationProbe,
    ),
    SearchTeacherCommand(
        "tournament", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTournament,
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

private fun SearchTeacherCommandContext.runPilotCalibrate() {
    val oraclePath = options.proofReview ?: store.latest("tactical-proof/report.json")
    require(Files.isRegularFile(oraclePath)) { "Missing machine tactical oracle report: $oraclePath" }
    val oracle = evidenceJson.decodeFromString<TacticalProofReport>(Files.readString(oraclePath))
    val report = PilotCalibrationRunner(root, registry, manifest).run(oracle, oraclePath)
    val directory = diagnosticOutput("pilot-calibration")
    val jsonPath = directory.resolve("report.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderPilotCalibration(report))
    if (report.passed) {
        val profilePath = directory.resolve("pilot-teacher-v1.json")
        writeJsonAtomically(
            profilePath,
            PilotFrozenProfileEvidence(
                id = report.pilotSpecification.id,
                generatedAtUtc = report.generatedAtUtc,
                outerCommit = report.outerCommit,
                argentumCommit = report.argentumCommit,
                pilotSpecification = report.pilotSpecification,
                pilotSpecificationSha256 = report.pilotSpecificationSha256,
                calibrationReportPath = root.relativize(jsonPath).toString(),
                calibrationReportSha256 = sha256File(jsonPath),
                oracleReportSha256 = report.oracleReportSha256,
                candidatePolicyId = report.candidatePolicyId,
                candidateQualified = report.candidateQualified,
            ),
        )
        println("Recorded pilot profile: $profilePath")
    }
    println(
        "The proposed pilot settings ${if (report.passed) "met" else "did not meet"} every declared " +
            "finite-case, repeat-choice, substituted-action, and 5,000 ms p95 condition. This does not " +
            "establish strong play outside those checks. Outputs: $jsonPath; $markdownPath"
    )
    check(report.passed) { "Pilot calibration failed: ${report.failureReasons}" }
}

private fun SearchTeacherCommandContext.runTournamentAmendment() {
    val sourceRunIdentity = requireNotNull(options.sourceRunIdentity) {
        "--source-run is required for tournament-amendment"
    }
    val (report, reportPath) = TournamentAmendmentRunner(root, registry, manifest)
        .run(sourceRunIdentity, options.threads)
    println(
        "The amendment replayed ${report.replacements.size}/${report.selectedGameCount} selected games; " +
            "every amendment condition was satisfied: ${report.passed}. The aggregate still mixes source " +
            "and repair revisions and remains historical diagnosis. Report: $reportPath"
    )
    check(report.passed) { "Tournament amendment failed: ${report.failureReasons}" }
}

private fun SearchTeacherCommandContext.runTournamentV3Calibrated() {
    val report = TournamentV3CalibratedRunner(
        root,
        registry,
        manifest,
        options.seed,
    ).run(options.pairs, options.threads)
    val reportPath = writeTournamentV3CalibratedArtifacts(root, report)
    println(
        "The run recorded ${report.completePairs}/$TOURNAMENT_V3_CALIBRATED_PAIRS required seat-swapped " +
            "pairs; every schedule, provenance, replay, and recorded execution condition was satisfied: " +
            "${report.valid}. This does not establish performance outside the declared gauntlet. " +
            "Report: $reportPath"
    )
    check(report.valid) { "V3 calibrated tournament failed: ${report.failureReasons}" }
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

private fun SearchTeacherCommandContext.runBaselineFactorialTournament() {
    require(options.seed == BASELINE_FACTORIAL_BASE_SEED) {
        "The baseline factorial run requires base seed $BASELINE_FACTORIAL_BASE_SEED"
    }
    val report = BaselineFactorialTournamentRunner(root, registry, manifest, options.seed)
        .run(options.pairs, options.threads)
    val reportPath = diagnosticOutput(
        "baseline-factorial-v1/${baselineArtifactDirectoryKey(report.runIdentity)}/report.json"
    )
    println(
        "The work-only factorial baseline recorded ${report.gameCount}/$BASELINE_FACTORIAL_GAMES games and " +
            "${report.completePairs}/${BASELINE_FACTORIAL_PAIRS * 5} seat-swapped pairs. It remains unpublished diagnostic evidence. " +
            "Report: ${root.relativize(reportPath)}"
    )
    check(report.valid) { "Baseline factorial tournament failed: ${report.failureReasons}" }
}

private fun SearchTeacherCommandContext.runBaselineFactorialSmoke() {
    require(options.seed == BASELINE_FACTORIAL_BASE_SEED && options.pairs == 1 && options.threads == BASELINE_FACTORIAL_SMOKE_WORKERS) {
        "The baseline smoke requires its fixed seed, one pair, and four workers"
    }
    val report = BaselineFactorialSmokeRunner(root, registry, manifest, options.seed).run(options.threads)
    println(
        "The unscored work-only factorial smoke recorded ${report.games.size}/$BASELINE_FACTORIAL_SMOKE_GAMES games; " +
            "passed=${report.passed}. It is never pooled with the scored baseline. Smoke: ${report.smokeIdentity}"
    )
    check(report.passed) { "Baseline factorial smoke failed: ${report.failureReasons}" }
}

private fun SearchTeacherCommandContext.runTournamentFallbackDiagnostic() {
    val sourceRunIdentity = requireNotNull(options.sourceRunIdentity) {
        "--source-run is required for tournament-fallback-diagnostic"
    }
    val (report, reportPath) = TournamentFallbackDiagnosticRunner(root, registry, manifest)
        .run(sourceRunIdentity, options.threads)
    println(
        "Tournament fallback diagnostic ${if (report.reproduced) "REPRODUCED" else "DIVERGED"}: " +
            "${report.reproducedFallbackCount}/${report.sourceFallbackCount} fallbacks; $reportPath"
    )
}

private fun SearchTeacherCommandContext.runTreeReuseValidation() {
    val reviewDirectory = diagnosticOutput("tree-reuse-validation")
    val report = TreeReuseValidationRunner(registry, options.seed).run(provenance)
    val jsonPath = reviewDirectory.resolve("report.json")
    writeJsonAtomically(jsonPath, report)
    val markdown = renderTreeReuseValidation(report)
    val markdownPath = reviewDirectory.resolve("report.md")
    writeTextAtomically(markdownPath, markdown)
    println(
        "Every fixed-root work, choice, latency, and memory condition in the retained-simulation diagnostic " +
            "was satisfied: ${report.passed}. The result does not make retained path counts proportional to " +
            "current hidden-position probabilities, so production reuse remains disabled. Outputs: " +
            "$jsonPath; $markdownPath"
    )
    check(report.passed) { "Tree-reuse validation gates failed: ${report.gates}" }
}

private fun SearchTeacherCommandContext.runTournamentPerformance() {
    val report = TournamentPerformanceProfiler(root, registry, manifest, options.seed).run()
    val path = diagnosticOutput("tournament-performance/report.json")
    writeJsonAtomically(path, report)
    println(
        "Every configured tournament throughput and memory condition was satisfied: ${report.passed}. " +
            "This work-only measurement does not establish playing strength. " +
            "single p50/p95=${"%.1f".format(report.singleWorker.p50SearchMillis)}/" +
            "${"%.1f".format(report.singleWorker.p95SearchMillis)}ms, " +
            "four-worker p50/p95=${"%.1f".format(report.fourWorkers.p50SearchMillis)}/" +
            "${"%.1f".format(report.fourWorkers.p95SearchMillis)}ms " +
            "(${"%.2f".format(report.fourWorkers.searchesPerSecond)} searches/s), " +
            "eight-worker p50/p95=${"%.1f".format(report.eightWorkers.p50SearchMillis)}/" +
            "${"%.1f".format(report.eightWorkers.p95SearchMillis)}ms " +
            "(${"%.2f".format(report.eightWorkers.searchesPerSecond)} searches/s saturation diagnostic); $path"
    )
}

private fun SearchTeacherCommandContext.runTournamentRemediation() {
    val report = TournamentRemediationRunner(root, registry, manifest, options.seed).run()
    val path = diagnosticOutput("tournament-remediation/report.json")
    writeJsonAtomically(path, report)
    println(
        "Every declared cleanup, proactive-pass, and rejected-transition diagnostic condition was satisfied: " +
            "${report.passed}. This work-only result diagnoses the named paths and does not establish general " +
            "play quality: " +
            "cleanup=${report.cleanupDiscardCount}, " +
            "low-land-explained=${report.lowLandExplainedCleanupDiscardCount}, " +
            "unexplained=${report.unexplainedCleanupDiscardCount}, " +
            "cleanup-after-land-hold=${report.cleanupAfterLandAvailablePassCount}, " +
            "proactive-main-pass=${report.proactiveMainPhasePassCount}, " +
            "with-land=${report.proactiveMainPhasePassWithLandCount}, " +
            "repeated-zero-land=${report.repeatedZeroLandNondevelopmentSequenceCount}, " +
            "rejected-search-steps=${report.rejectedSearchTransitionCount}; $path"
    )
    check(report.passed) { "Tournament remediation gates failed: ${report.failureReasons}" }
}

private fun SearchTeacherCommandContext.runTournamentRemediationCheck() {
    val report = TournamentRemediationRunner(root, registry, manifest, options.seed)
        .auditExistingReplays()
    val path = diagnosticOutput("tournament-remediation/replay-check.json")
    writeJsonAtomically(path, report)
    println(
        "Tournament remediation replay check: ${report.entries.count { it.verified }}/" +
            "${report.entries.size} verified; $path"
    )
}

private fun SearchTeacherCommandContext.runTournamentRemediationProbe() {
    val report = TournamentRemediationRunner(root, registry, manifest, options.seed).runReplayProbe()
    val path = diagnosticOutput("tournament-remediation/replay-probe.json")
    writeJsonAtomically(path, report)
    println(
        "Tournament remediation replay probe: verified=${report.game.replayVerified}, " +
            "exception=${report.game.exception}, diagnostic=${report.game.replayVerificationDiagnostic}; $path"
    )
}

private fun SearchTeacherCommandContext.runTournament() {
    val benchmarkPath = store.latest("tactical/legacy-leaf-benchmark.json")
    val report = CoreSixTournament(
        root,
        registry,
        manifest,
        options.seed,
        benchmarkPath,
    )
        .run(options.pairs, options.threads)
    val reportPath = store.diagnostic(
        "tournament/${report.runIdentity}/report.json",
        "the tournament work report",
    )
    writeJsonAtomically(reportPath, report)
    val markdownPath = reportPath.parent.resolve("report.md")
    writeTextAtomically(markdownPath, renderCoreSixTournament(report))
    ResearchRunArtifacts(reportPath.parent, report.runIdentity).also {
        it.register("report.json")
        it.register("report.md")
        it.finalize()
    }
    println(
        "The run recorded ${report.completePairs}/${report.pairsPerMatchup * 15} required seat-swapped pairs; " +
            "every schedule and recorded execution condition was satisfied: ${report.valid}. This does not " +
            "show that every legal action was offered or that any policy played strongly. Outputs: " +
            "$reportPath; $markdownPath"
    )
    check(report.valid) { "Core-six tournament had operational failures: ${report.failureReasons}" }
}
