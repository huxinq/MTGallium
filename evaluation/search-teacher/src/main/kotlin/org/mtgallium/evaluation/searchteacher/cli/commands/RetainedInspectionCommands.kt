package org.mtgallium.evaluation.searchteacher.cli.commands

import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val retainedInspectionCommands = listOf(
    SearchTeacherCommand(
        "gameplay-summary", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runGameplaySummary,
    ),
    SearchTeacherCommand(
        "search-profile-summary", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runSearchProfileSummary,
    ),
    SearchTeacherCommand(
        "learned-outcome-value-retained-parity-audit", CommandPreparation.HANDLER,
        SearchTeacherCommandContext::runLearnedOutcomeValueRetainedParityAudit,
    ),
)

private fun SearchTeacherCommandContext.runGameplaySummary() {
    print(renderRetainedGameplayLengths(options.runDirectories.map(::loadRetainedGameplayLengths)))
}

private fun SearchTeacherCommandContext.runSearchProfileSummary() {
    require(options.outputPath == null) { "Profile inspection writes to stdout; it does not modify retained evidence" }
    print(summarizeRegisteredSearchProfile(requireNotNull(options.profilePath) {
        "Pass a JFR registered in its parent directory's finalized manifest via --profile"
    }, options.sourceRunIdentity))
}

private fun SearchTeacherCommandContext.runLearnedOutcomeValueRetainedParityAudit() {
    val corpusDirectory = requireNotNull(options.outcomeCorpus) {
        "The retained verified outcome-state corpus is required via --outcome-corpus"
    }
    val trainingDirectory = requireNotNull(options.learnedGate) {
        "The retained verified training directory is required via --learned-gate"
    }
    val output = diagnosticOutput(requireNotNull(options.outputPath) {
        "An explicit private diagnostic output is required via --output"
    })
    val report = RetainedLearnedOutcomeValueParityAudit.run(corpusDirectory, trainingDirectory)
    writeJsonAtomically(output, report)
    val checksumPath = output.resolveSibling(output.fileName.toString() + ".sha256")
    writeTextAtomically(checksumPath, "${sha256File(output)}  ${output.fileName}\n")
    println(
        "Retained learned-outcome parity audit verified corpus=${report.corpusIdentity}; " +
            "training=${report.trainingIdentity}; checkpoint=${report.checkpointPayloadSha256}; " +
            "frames=${report.frames.joinToString { "${it.rootPlayerId}:${it.leg}:${it.frameIndex}:${it.actorRelation}" }}; " +
            "report=$output; checksum=$checksumPath",
    )
}
