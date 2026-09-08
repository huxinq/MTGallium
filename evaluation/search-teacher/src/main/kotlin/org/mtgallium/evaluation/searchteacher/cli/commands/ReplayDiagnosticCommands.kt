package org.mtgallium.evaluation.searchteacher.cli.commands

import java.nio.file.Files
import java.time.Instant
import kotlinx.serialization.decodeFromString
import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val replayDiagnosticCommands = listOf(
    SearchTeacherCommand(
        "root-search-evidence-repeatability", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runRootSearchEvidenceRepeatability,
    ),
    SearchTeacherCommand(
        "standalone-mana-timing-experiment", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runStandaloneManaTimingExperiment,
    ),
    SearchTeacherCommand(
        "issue-0013-stage-a", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runIssue0013StageA,
    ),
    SearchTeacherCommand(
        "issue-0013-stage-b-panel", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runIssue0013StageBPanel,
    ),
    SearchTeacherCommand(
        "issue-0013-stage-b", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runIssue0013StageB,
    ),
    SearchTeacherCommand(
        "issue-0013-stage-b-reviewed-secondary", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runIssue0013StageBReviewedSecondary,
    ),
    SearchTeacherCommand(
        "issue-0013-blinded-review", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runIssue0013BlindedReview,
    ),
    SearchTeacherCommand(
        "replay-review-case-intake", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runReplayReviewCaseIntake,
    ),
    SearchTeacherCommand(
        "replay-review-decisions", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runReplayReviewDecisions,
    ),
    SearchTeacherCommand(
        "response-window-inventory", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runResponseWindowInventory,
    ),
    SearchTeacherCommand(
        "player-choice-inventory", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runPlayerChoiceInventory,
    ),
)

private fun SearchTeacherCommandContext.runRootSearchEvidenceRepeatability() {
    val panelPath = store.work("issue-0013-fresh-world/stage-b/panel.json")
    require(Files.isRegularFile(panelPath) && !Files.isSymbolicLink(panelPath)) {
        "Freeze the current-revision source-bound panel before running root evidence: $panelPath"
    }
    val panel = evidenceJson.decodeFromString<FreshWorldFrozenRootPanel>(Files.readString(panelPath))
    require(panel.currentArgentumCommit == provenance.checkedOutArgentumCommit) {
        "Frozen panel uses ${panel.currentArgentumCommit}, not ${provenance.checkedOutArgentumCommit}"
    }
    val sourceProvenance = requireNotNull(provenance.sourceProvenance)
    val population = RootEvidencePopulationBuilder(
        root = root,
        registry = registry,
        manifest = manifest,
        panel = panel,
        sourceProvenance = sourceProvenance,
    ).build(options.rootLimit)
    val report = RootSearchEvidenceRepeatabilityExperiment(
        root = root,
        registry = registry,
        manifest = manifest,
        panel = panel,
        population = population,
    ).run(
        repetitions = options.repetitions,
        workerThreads = options.threads,
        generatedAtUtc = Instant.now().toString(),
        progress =(::println),
    )
    val directory = diagnosticOutput(
        "root-search-evidence/r${options.rootLimit}-x${options.repetitions}"
    )
    val populationPath = directory.resolve("population.json")
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(populationPath, population)
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderRootSearchEvidenceRepeatability(report))
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(populationPath)}  population.json\n" +
            "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n",
    )
    println(
        "Root-search evidence completed ${report.trials.size} searches on " +
            "${population.roots.size} roots; failures=${report.failures.size}. " +
            "Outputs: $populationPath; $jsonPath; $markdownPath"
    )
    check(report.completed) { "Root-search evidence experiment had failures: ${report.typedFailureCounts}" }
}

private fun SearchTeacherCommandContext.runStandaloneManaTimingExperiment() {
    val report = StandaloneManaTimingExperiment(
        root = root,
        registry = registry,
        manifest = manifest,
        sourceRepositoryCommit = provenance.outerCommit,
        argentumCommit = provenance.checkedOutArgentumCommit,
    ).run(
        rootLimit = options.rootLimit,
        repetitions = options.repetitions,
        workerThreads = options.threads,
        progress =(::println),
    )
    val directory = diagnosticOutput(
        "action-profile-standalone-mana-timing/r${options.rootLimit}-x${options.repetitions}"
    )
    val jsonPath = directory.resolve("report.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderStandaloneManaTimingReport(report))
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n${sha256File(markdownPath)}  report.md\n",
    )
    println(
        "Standalone-mana timing experiment completed ${report.population.getValue("scheduledPairedTrials")} " +
            "paired trials; oracle=${report.oraclePassed}; failures=" +
            "${report.summaries.getValue("ALL_REVIEWED_23").technicalFailures}. " +
            "Outputs: $jsonPath; $markdownPath"
    )
    check(report.oraclePassed) { "Standalone-mana semantic oracle failed: ${report.oracleFailures}" }
}

private fun SearchTeacherCommandContext.runIssue0013StageA() {
    val report = FreshWorldReferenceOracle(
        registry = registry,
        outerCommit = provenance.outerCommit,
        argentumCommit = provenance.checkedOutArgentumCommit,
    ).run()
    val directory = diagnosticOutput("issue-0013-fresh-world/stage-a")
    val jsonPath = directory.resolve("report.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderFreshWorldReferenceOracle(report))
    println(
        "Issue 0013 Stage A checked ${report.cases.size} exact chance-only/hard-conditioned laws; " +
            "${report.cases.count { it.passed }}/${report.cases.size} passed. " +
            "Outputs: $jsonPath; $markdownPath"
    )
    check(report.passed) { "Fresh-world reference-law oracle failed: ${report.failureReasons}" }
}

private fun SearchTeacherCommandContext.runIssue0013StageBPanel() {
    val panel = FreshWorldFrozenRootPanelBuilder(
        root = root,
        registry = registry,
        manifest = manifest,
        currentOuterCommit = provenance.outerCommit,
        currentArgentumCommit = provenance.checkedOutArgentumCommit,
    ).build()
    val directory = diagnosticOutput("issue-0013-fresh-world/stage-b")
    val jsonPath = directory.resolve("panel.json")
    val markdownPath = directory.resolve("panel.md")
    writeJsonAtomically(jsonPath, panel)
    writeTextAtomically(markdownPath, renderFreshWorldFrozenRootPanel(panel))
    println(
        "Issue 0013 froze ${panel.roots.size} result-blind RV2 roots from " +
            "${panel.candidateRoots} current-revision candidates in distinct source games; " +
            "replay refusals=${panel.replayRefusals.size}. Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runIssue0013StageB() {
    val panelPath = store.work("issue-0013-fresh-world/stage-b/panel.json")
    require(Files.isRegularFile(panelPath) && !Files.isSymbolicLink(panelPath)) {
        "Freeze the Stage-B panel before running either arm: $panelPath"
    }
    val panel = evidenceJson.decodeFromString<FreshWorldFrozenRootPanel>(Files.readString(panelPath))
    val directory = diagnosticOutput(
        "issue-0013-fresh-world/stage-b/fixed-work-r${options.rootLimit}-x${options.repetitions}"
    )
    val checkpointPath = directory.resolve("checkpoint.json")
    val existing = checkpointPath.takeIf { options.resume && Files.isRegularFile(it) }
        ?.let { evidenceJson.decodeFromString<FreshWorldFixedWorkCheckpoint>(Files.readString(it)) }
    val experiment = FreshWorldFixedWorkExperiment(
        root = root,
        registry = registry,
        manifest = manifest,
        panel = panel,
        outerCommit = provenance.outerCommit,
        argentumCommit = provenance.checkedOutArgentumCommit,
    )
    val report = experiment.run(
        rootLimit = options.rootLimit,
        repetitions = options.repetitions,
        workerThreads = options.threads,
        existing = existing,
        checkpoint = { writeJsonAtomically(checkpointPath, it) },
        progress =(::println),
    )
    val jsonPath = directory.resolve("report.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderFreshWorldFixedWorkReport(report))
    println(
        "Issue 0013 Stage B completed ${report.completePairs}/${report.scheduledPairs} paired trials; " +
            "action mismatches=${report.pairedActionMismatches}; modal root changes=" +
            "${report.rootsWithModalActionChange}; future-chance mismatches=" +
            "${report.pairedFutureChanceMismatches}. Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runIssue0013StageBReviewedSecondary() {
    val panelPath = store.work("issue-0013-fresh-world/stage-b/panel.json")
    require(Files.isRegularFile(panelPath) && !Files.isSymbolicLink(panelPath))
    val panel = evidenceJson.decodeFromString<FreshWorldFrozenRootPanel>(Files.readString(panelPath))
    val report = FreshWorldReviewedSecondaryRunner(
        root = root,
        registry = registry,
        manifest = manifest,
        primaryPanel = panel,
        outerCommit = provenance.outerCommit,
        argentumCommit = provenance.checkedOutArgentumCommit,
    ).run(repetitions = options.repetitions, workerThreads = options.threads, progress =(::println))
    val directory = diagnosticOutput("issue-0013-fresh-world/stage-b/reviewed-secondary")
    val jsonPath = directory.resolve("report.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderFreshWorldReviewedSecondary(report))
    println("Issue 0013 reviewed secondary: ${report.interpretation} Outputs: $jsonPath; $markdownPath")
}

private fun SearchTeacherCommandContext.runIssue0013BlindedReview() {
    val (packet, path) = Issue0013BlindedReviewGenerator(
        root = root,
        registry = registry,
        manifest = manifest,
        outerCommit = provenance.outerCommit,
        argentumCommit = provenance.checkedOutArgentumCommit,
    ).generate()
    println(
        "Issue 0013 blinded review reconstructed ${packet.cases.size} safe current-revision roots " +
            "with ${packet.cases.sumOf { it.candidateExpansion.candidates.size }} neutralized legal " +
            "action entries. No search arm ran. Packet: $path"
    )
}

private fun SearchTeacherCommandContext.runReplayReviewCaseIntake() {
    val authenticated = ReplayReviewDecisionIntake.authenticate(
        root = root,
        draftPath = requireNotNull(options.replayReviewDraft) { "--replay-review-draft is required" },
        safeBundlePath = requireNotNull(options.replayReviewSafeBundle) { "--safe-inspection is required" },
        canonicalReplayPath = requireNotNull(options.replayReviewCanonicalReplay) { "--canonical-replay is required" },
        outputPath = requireNotNull(options.outputPath) { "--output is required" },
        registry = registry,
        manifest = manifest,
    )
    println(
        "The trusted intake authenticated safe decision ${authenticated.source.decisionIndex} from " +
            "game ${authenticated.source.gameId} against its canonical replay and wrote one privileged, " +
            "work-only executable case. It does not publish evidence or establish strategic truth. " +
        "Output: ${options.outputPath}"
    )
}

private fun SearchTeacherCommandContext.runReplayReviewDecisions() {
    val authenticatedCases = options.replayReviewCase?.let { path ->
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
            "Authenticated replay-review case is not a regular non-link file: $path"
        }
        listOf(
            evidenceJson.decodeFromString<AuthenticatedReplayReviewDecisionCase>(Files.readString(path)).also {
                it.requireIntakeBinding()
            },
        )
    }.orEmpty()
    val report = ReplayReviewDecisionRunner(
        registry = registry,
        manifest = manifest,
        outerCommit = provenance.outerCommit,
        argentumCommit = provenance.checkedOutArgentumCommit,
    ).run(
        particles = options.particles,
        simulations = options.simulations,
        maxPolicyDecisions = options.maxPolicyDecisions,
        authenticatedCases = authenticatedCases,
    )
    val directory = diagnosticOutput("replay-review-decisions")
    val jsonPath = directory.resolve("report.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderReplayReviewDecisionReport(report))
    println(
        "The diagnostic evaluated ${report.results.size} policy/case combinations across " +
            "${report.cases.size} replay-derived case(s), including ${report.trustedIntakeCases} " +
            "trusted-intake case(s); " +
            "${report.unacceptableSelections} exact selections were graded unacceptable. " +
            "This does not establish general strategy or overall playing strength. " +
            "Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runResponseWindowInventory() {
    val report = M01ResponseWindowDiagnostic(
        registry = registry,
        manifest = manifest,
        outerCommit = provenance.outerCommit,
        argentumCommit = provenance.checkedOutArgentumCommit,
    ).run(options.seed)
    val directory = diagnosticOutput("remediation/m01-response-windows")
    val jsonPath = directory.resolve("report.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderM01ResponseWindowReport(report))
    println(
        "The work-only response diagnostic recorded " +
            "${report.targetedWindows + report.naturalWindows} engine-emitted response-window roots " +
            "(${report.priorityWindows} stack-priority; ${report.pendingDecisionWindows} pending; " +
            "${report.rulesSingletonWindows} rules-singleton) and retained " +
            "${report.eligiblePairedComparisons}/${report.totalPairedComparisons} paired comparisons " +
            "after stop, rejection, and replacement checks; " +
            "${report.changedChosenRootActions} eligible chosen root actions changed under diagnostic " +
            "suppression. This does not establish complete Magic-response coverage or strategic regret. " +
            "Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runPlayerChoiceInventory() {
    val report = M02PlayerChoiceInventoryDiagnostic(
        registry = registry,
        manifest = manifest,
        outerCommit = provenance.outerCommit,
        argentumCommit = provenance.checkedOutArgentumCommit,
    ).run(options.seed)
    val directory = diagnosticOutput("remediation/m02-player-choice-inventory")
    val jsonPath = directory.resolve("report.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderM02PlayerChoiceInventory(report))
    println(
        "The work-only inventory reached ${report.reachedAuthoredDecisionContracts} authored contracts " +
            "(${report.multiAlternativeAuthoredDecisionContracts} multi-alternative; " +
            "${report.trivialResponderBypassContracts} TrivialDecisions and " +
            "${report.fastRolloutResponderBypassContracts} fast-rollout script bypasses) and " +
            "${report.reachedArenaRootDecisions} production Arena root decisions " +
            "(${report.multiAlternativeArenaRootDecisions} multi-alternative; " +
            "${report.scriptOrPolicySelectedArenaRootDecisions} script/policy-selected; " +
            "${report.searchedArenaRootDecisions} searched; " +
            "${report.replacementArenaAndSimulationDecisions}/" +
            "${report.replacementArenaAndSimulationDecisionOpportunities} replacements). " +
            "Strategic regret is refused except for the declared mana-source availability predicate. " +
            "Outputs: $jsonPath; $markdownPath"
    )
}
