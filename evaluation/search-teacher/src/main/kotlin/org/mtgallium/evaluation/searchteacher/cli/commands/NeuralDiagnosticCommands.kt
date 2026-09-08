package org.mtgallium.evaluation.searchteacher.cli.commands

import java.nio.file.Path
import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val neuralDiagnosticCommands = listOf(
    SearchTeacherCommand(
        "neural-held-out-generalization-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralHeldOutGeneralizationPreflight,
    ),
    SearchTeacherCommand(
        "neural-held-out-generalization-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralHeldOutGeneralizationDiagnostic,
    ),
    SearchTeacherCommand(
        "neural-anchor-crossing-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralAnchorCrossingPreflight,
    ),
    SearchTeacherCommand(
        "neural-anchor-crossing-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralAnchorCrossingDiagnostic,
    ),
    SearchTeacherCommand(
        "neural-cohort-continuation-preflight", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralCohortContinuationPreflight,
    ),
    SearchTeacherCommand(
        "neural-cohort-continuation-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralCohortContinuationDiagnostic,
    ),
    SearchTeacherCommand(
        "neural-final-boundary-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralFinalBoundaryDiagnostic,
    ),
    SearchTeacherCommand(
        "neural-stability-boundary-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralStabilityBoundaryDiagnostic,
    ),
    SearchTeacherCommand(
        "neural-population-scaling-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralPopulationScalingDiagnostic,
    ),
    SearchTeacherCommand(
        "neural-candidate-update-scale-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralCandidateUpdateScaleDiagnostic,
    ),
    SearchTeacherCommand(
        "neural-saturation-trajectory-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralSaturationTrajectoryDiagnostic,
    ),
    SearchTeacherCommand(
        "neural-memorization-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralMemorizationDiagnostic,
    ),
    SearchTeacherCommand(
        "neural-capacity-diagnostic", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralCapacityDiagnostic,
    ),
    SearchTeacherCommand(
        "neural-behavioral-cloning", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runNeuralBehavioralCloning,
    ),
)

private fun SearchTeacherCommandContext.runNeuralHeldOutGeneralizationPreflight() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work(
            "neural-held-out-generalization-diagnostic/issue-0035-fixed-v3"
        )
    )
    val report = Issue0035NeuralHeldOutGeneralizationDiagnostic(root, directory)
        .preflight(historicalManifest)
    println(renderIssue0035Preflight(report))
}

private fun SearchTeacherCommandContext.runNeuralHeldOutGeneralizationDiagnostic() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work(
            "neural-held-out-generalization-diagnostic/issue-0035-fixed-v3"
        )
    )
    val report = Issue0035NeuralHeldOutGeneralizationDiagnostic(root, directory)
        .run(historicalManifest)
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderIssue0035NeuralHeldOutGeneralization(report))
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n",
    )
    println(
        "Neural held-out generalization diagnostic completed; case=${report.diagnosticCase}. " +
            "Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runNeuralAnchorCrossingPreflight() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work(
            "neural-anchor-crossing-diagnostic/issue-0034-fixed-v3"
        )
    )
    val report = Issue0034NeuralAnchorCrossingDiagnostic(root, directory)
        .preflight(historicalManifest)
    println(renderIssue0034Preflight(report))
}

private fun SearchTeacherCommandContext.runNeuralAnchorCrossingDiagnostic() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work(
            "neural-anchor-crossing-diagnostic/issue-0034-fixed-v3"
        )
    )
    val report = Issue0034NeuralAnchorCrossingDiagnostic(root, directory)
        .run(historicalManifest, ::println)
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderIssue0034NeuralAnchorCrossing(report))
    val retainedHashes = report.seeds.joinToString("") { seed ->
        "${seed.preCrossingCheckpointSha256}  ${Path.of(seed.preCrossingCheckpointPath).fileName}\n" +
            "${seed.postCrossingCheckpointSha256}  ${Path.of(seed.postCrossingCheckpointPath).fileName}\n" +
            "${seed.mechanismVectorsSha256}  ${Path.of(seed.mechanismVectorsPath).fileName}\n"
    }
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n" + retainedHashes,
    )
    println(
        "Neural n=323 first-crossing diagnostic completed; case=${report.diagnosticCase}. " +
            "Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runNeuralCohortContinuationPreflight() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work(
            "neural-cohort-continuation-diagnostic/issue-0031-fixed-v3"
        )
    )
    val report = Issue0031NeuralCohortContinuationDiagnostic(
        root = root,
        outputDirectory = directory,
    ).preflight(historicalManifest)
    println(renderIssue0031Preflight(report))
}

private fun SearchTeacherCommandContext.runNeuralCohortContinuationDiagnostic() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work(
            "neural-cohort-continuation-diagnostic/issue-0031-fixed-v3"
        )
    )
    val report = Issue0031NeuralCohortContinuationDiagnostic(
        root = root,
        outputDirectory = directory,
    ).run(historicalManifest, ::println)
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderIssue0031NeuralCohortContinuation(report))
    val evidenceHashes = report.seeds.joinToString("") { result ->
        "${result.forkCheckpointSha256}  ${Path.of(result.forkCheckpointPath).fileName}\n" +
            "${result.finalAnchorModelSha256}  ${Path.of(result.finalAnchorModelPath).fileName}\n" +
            "${result.finalExpandedModelSha256}  ${Path.of(result.finalExpandedModelPath).fileName}\n"
    }
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n" + evidenceHashes,
    )
    println(
        "Neural n=323-to-n=389 cohort continuation diagnostic completed; " +
            "case=${report.diagnosticCase}. Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runNeuralFinalBoundaryDiagnostic() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work(
            "neural-final-boundary-diagnostic/issue-0030-fixed-v3"
        )
    )
    val report = Issue0030NeuralFinalBoundaryDiagnostic(
        root = root,
        outputDirectory = directory,
    ).run(historicalManifest, ::println)
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderIssue0030NeuralFinalBoundary(report))
    val modelHashes = report.stages.single { it.decisions == report.fixedPrefixDecisions }.seeds
        .joinToString("") { result ->
            "${result.modelSha256}  ${Path.of(result.modelPath).fileName}\n"
        }
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n" + modelHashes,
    )
    println(
        "Neural n=323 final stability-boundary diagnostic completed; " +
            "case=${report.diagnosticCase}. Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runNeuralStabilityBoundaryDiagnostic() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work(
            "neural-stability-boundary-diagnostic/issue-0029-fixed-v3"
        )
    )
    val report = Issue0029NeuralStabilityBoundaryDiagnostic(
        root = root,
        outputDirectory = directory,
    ).run(historicalManifest, ::println)
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderIssue0029NeuralStabilityBoundary(report))
    val modelHashes = report.stages.single { it.decisions == report.fixedPrefixDecisions }.seeds
        .joinToString("") { result ->
            "${result.modelSha256}  ${Path.of(result.modelPath).fileName}\n"
        }
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n" + modelHashes,
    )
    println(
        "Neural n=256 stability-boundary diagnostic completed; case=${report.diagnosticCase}. " +
            "Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runNeuralPopulationScalingDiagnostic() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work(
            "neural-population-scaling-diagnostic/issue-0028-fixed-v3"
        )
    )
    val report = Issue0028NeuralPopulationScalingDiagnostic(
        root = root,
        outputDirectory = directory,
    ).run(historicalManifest, ::println)
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderIssue0028NeuralPopulationScaling(report))
    val modelHashes = report.stages.single { it.decisions == 128 }.seeds.joinToString("") { result ->
        "${result.modelSha256}  ${Path.of(result.modelPath).fileName}\n"
    }
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n" + modelHashes,
    )
    println(
        "Neural population-scaling diagnostic completed; case=${report.diagnosticCase}. " +
            "Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runNeuralCandidateUpdateScaleDiagnostic() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work(
            "neural-candidate-update-scale-diagnostic/issue-0027-fixed-v3"
        )
    )
    val report = Issue0027NeuralCandidateUpdateScaleDiagnostic(
        root = root,
        outputDirectory = directory,
    ).run(historicalManifest, ::println)
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderIssue0027NeuralCandidateUpdateScale(report))
    val modelHashes = (report.baseline.stages + report.intervention.stages).flatMap { it.seeds }
        .joinToString("") { result ->
            "${result.modelSha256}  ${Path.of(result.modelPath).fileName}\n"
        }
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n" + modelHashes,
    )
    println(
        "Candidate-update-scale diagnostic completed; case=${report.diagnosticCase}. " +
            "Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runNeuralSaturationTrajectoryDiagnostic() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work("neural-saturation-trajectory-diagnostic/issue-0026-fixed-v3")
    )
    val report = Issue0026NeuralSaturationTrajectoryDiagnostic(
        root = root,
        outputDirectory = directory,
    ).run(historicalManifest, ::println)
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderIssue0026NeuralSaturationTrajectory(report))
    val modelHashes = (report.baseline.stages + report.intervention.stages).flatMap { it.seeds }
        .joinToString("") { result ->
            "${result.modelSha256}  ${Path.of(result.modelPath).fileName}\n"
        }
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n" + modelHashes,
    )
    println(
        "Neural saturation trajectory diagnostic completed; case=${report.diagnosticCase}. " +
            "Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runNeuralMemorizationDiagnostic() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work("neural-memorization-diagnostic/issue-0025-fixed-v3")
    )
    val report = Issue0025NeuralMemorizationDiagnostic(
        root = root,
        outputDirectory = directory,
    ).run(historicalManifest, ::println)
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderIssue0025NeuralMemorizationDiagnostic(report))
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n" +
            report.stages.flatMap(NeuralMemorizationStageResult::seedResults)
                .joinToString("") { result ->
                    "${result.modelSha256}  ${Path.of(result.modelPath).fileName}\n"
                },
    )
    println(
        "Neural memorization diagnostic completed on ${report.trainingDecisions} training decisions; " +
            "case=${report.diagnosticCase}. Outputs: $jsonPath; $markdownPath"
    )
}

private fun SearchTeacherCommandContext.runNeuralCapacityDiagnostic() {
    val historicalManifest = requireNotNull(options.corpusManifest) {
        "The fixed issue-0022 corpus manifest is required via --corpus-manifest"
    }
    val directory = diagnosticOutput(
        options.outputPath ?: store.work("neural-capacity-diagnostic/issue-0024-fixed-v3")
    )
    val report = Issue0024NeuralCapacityDiagnostic(
        root = root,
        registry = registry,
        deck = manifest,
        currentProfile = SearchTeacherArena.smokeProfile(),
        baseSeed = options.seed,
        outputDirectory = directory,
    ).run(historicalManifest, ::println)
    val jsonPath = directory.resolve("artifact.json")
    val markdownPath = directory.resolve("report.md")
    writeJsonAtomically(jsonPath, report)
    writeTextAtomically(markdownPath, renderIssue0024NeuralCapacityDiagnostic(report))
    writeTextAtomically(
        directory.resolve("SHA256SUMS"),
        "${sha256File(jsonPath)}  artifact.json\n" +
            "${sha256File(markdownPath)}  report.md\n" +
            (report.repairedOriginalModel.seedResults + report.strongerModel.seedResults)
                .joinToString("") { result ->
                    "${result.modelSha256}  ${Path.of(result.modelPath).fileName}\n"
                },
    )
    println(
        "Neural capacity diagnostic completed on ${report.nontrivialDecisions} nontrivial decisions; " +
            "case=${report.diagnosticCase}. Outputs: $jsonPath; $markdownPath"
    )
}

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
