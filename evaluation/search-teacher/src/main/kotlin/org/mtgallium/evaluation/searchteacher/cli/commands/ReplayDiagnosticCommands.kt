package org.mtgallium.evaluation.searchteacher.cli.commands

import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val replayDiagnosticCommands = listOf(
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
