package org.mtgallium.evaluation.searchteacher.cli.commands

import java.nio.file.Path
import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val behavioralCloningCommands = listOf(
    SearchTeacherCommand(
        "neural-behavioral-cloning", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralBehavioralCloning,
    ),
)

private fun SearchTeacherCommandContext.runNeuralBehavioralCloning() {
    val profile = SearchTeacherArena.smokeProfile()
    val directory = diagnosticOutput(
        options.outputPath ?: store.work("neural-behavioral-cloning/g${options.games}-seed${options.seed}")
    )
    val report = NeuralBehavioralCloningExperiment(
        root = root,
        registry = registry,
        deck = manifest,
        profile = profile,
        baseSeed = options.seed,
        outputDirectory = directory,
    ).run(
        gameCount = options.games,
        workerThreads = options.threads,
        suppliedCorpusManifest = options.corpusManifest,
        progress = ::println,
    )
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderNeuralBehavioralCloning(report))
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n" +
            "${report.rootEvidenceSidecarSha256}  root-evidence-sidecar.jsonl.gz\n" +
            report.seedResults.joinToString("") { result ->
                "${result.modelSha256}  ${Path.of(result.modelPath).fileName}\n"
            },
    )
    println(
        "Neural BC admitted ${report.admittedDecisions} decisions from ${report.admittedGames} games; " +
            "primary held-out mean=${report.primaryTest.neuralMeanAccuracy}, " +
            "conclusion=${report.conclusion}. Outputs: $jsonPath; $markdownPath"
    )
}
