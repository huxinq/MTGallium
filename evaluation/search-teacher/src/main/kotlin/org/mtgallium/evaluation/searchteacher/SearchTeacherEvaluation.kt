package org.mtgallium.evaluation.searchteacher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherSuites
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunFiles
import org.mtgallium.research.run.ResearchRunProvenance
import org.mtgallium.research.run.researchSha256

fun main(args: Array<String>) {
    runSearchTeacher(Path.of("").toAbsolutePath().normalize(), args)
}

internal fun runSearchTeacher(root: Path, args: Array<String>) {
    val options = SearchTeacherCli.parse(args)
    val suite = SearchTeacherSuites.require(options.suite)
    val store = EvidenceStore(root)
    fun diagnosticOutput(relative: String): Path =
        store.diagnostic(relative, "the ${suite.id} command output")

    fun diagnosticOutput(path: Path): Path =
        store.requireDiagnosticOutput(path, "the ${suite.id} command output")

    // This audit verifies already-finalized private artifacts. It is intentionally outside normal
    // run-provenance capture: the checked-out source must not be misrepresented as the historical
    // trainer, and the audit neither creates nor changes research evidence.
    if (options.suite == "learned-outcome-value-retained-parity-audit") {
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
        return
    }

    val provenance = RunProvenance.capture(root)
    provenance.requireReady()
    // Corpus-only neural suites return before either authority is needed. Keeping these lazy avoids
    // constructing the full Argentum card registry during their fail-fast input-validation phase.
    val manifest by lazy { loadDeckManifest(options.deckManifest) }
    val registry by lazy(::buildRegistry)
    if (options.suite == "decision-local-root-freeze") {
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
        return
    }
    if (options.suite == "decision-local-throughput-preflight") {
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
        return
    }
    if (options.suite == "decision-local-sibling-outcome") {
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
        )
        println(
            "Decision-local sibling outcome experiment ${report.scientificEvidenceIdentity}; " +
                "conclusion=${report.conclusion}; roots=${report.admittedRoots}/${report.primaryRoots}; output=$output",
        )
        return
    }
    if (options.suite == "outcome-state-corpus-preflight") {
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
        return
    }
    if (options.suite == "outcome-state-corpus") {
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
        return
    }
    if (options.suite == "learned-outcome-value-gate") {
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
        return
    }
    if (options.suite == "learned-outcome-value-global-signal") {
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
        return
    }
    if (options.suite == "learned-leaf-fixed-root-bind") {
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
        return
    }
    if (options.suite in setOf("learned-leaf-fixed-root-preflight", "learned-leaf-fixed-root-diagnostic")) {
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
        return
    }
    if (options.suite == "learned-leaf-pilot") {
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
        return
    }
    if (options.suite == "learned-leaf-pilot-smoke") {
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
        return
    }
    if (options.suite == "neural-held-out-generalization-preflight") {
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
        return
    }
    if (options.suite == "neural-held-out-generalization-diagnostic") {
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
        return
    }
    if (options.suite == "neural-anchor-crossing-preflight") {
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
        return
    }
    if (options.suite == "neural-anchor-crossing-diagnostic") {
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
        return
    }
    if (options.suite == "neural-cohort-continuation-preflight") {
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
        return
    }
    if (options.suite == "neural-cohort-continuation-diagnostic") {
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
        return
    }
    if (options.suite == "neural-final-boundary-diagnostic") {
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
        return
    }
    if (options.suite == "neural-stability-boundary-diagnostic") {
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
        return
    }
    if (options.suite == "neural-population-scaling-diagnostic") {
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
        return
    }
    if (options.suite == "neural-candidate-update-scale-diagnostic") {
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
        return
    }
    if (options.suite == "neural-saturation-trajectory-diagnostic") {
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
        return
    }
    if (options.suite == "neural-memorization-diagnostic") {
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
        return
    }
    if (options.suite == "neural-capacity-diagnostic") {
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
        return
    }
    if (options.suite == "root-search-evidence-repeatability") {
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
        return
    }
    if (options.suite == "neural-behavioral-cloning") {
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
        return
    }
    if (options.suite == "standalone-mana-timing-experiment") {
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
        return
    }
    if (options.suite == "issue-0013-stage-a") {
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
        return
    }
    if (options.suite == "issue-0013-stage-b-panel") {
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
        return
    }
    if (options.suite == "issue-0013-stage-b") {
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
        return
    }
    if (options.suite == "issue-0013-stage-b-reviewed-secondary") {
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
        return
    }
    if (options.suite == "issue-0013-blinded-review") {
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
        return
    }
    if (options.suite == "replay-review-case-intake") {
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
        return
    }
    if (options.suite == "replay-review-decisions") {
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
        return
    }
    if (options.suite == "response-window-inventory") {
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
        return
    }
    if (options.suite == "player-choice-inventory") {
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
        return
    }
    if (options.suite == "tactical-horizon-check") {
        val limit = options.caseLimit.coerceAtMost(TacticalHorizonCatalog.cases.size)
        val (packet, packetPath) = TacticalAuthoringPacketGenerator(root, registry, manifest)
            .generateHorizonSuite(limit)
        val report = TacticalHorizonConformanceRunner(
            registry = registry,
            manifest = manifest,
            sourcePacketSha256 = sha256File(packetPath),
        ).run(TacticalHorizonCatalog.cases.take(limit))
        val reportPath = diagnosticOutput(
            "tactical-authoring/$TACTICAL_HORIZON_SUITE_VERSION.conformance.json"
        )
        writeJsonAtomically(reportPath, report)
        println(
            "${report.contractPassedCases}/${packet.scenarios.size} supplied roots satisfied the declared setup " +
                "and state checks; ${report.certifiedCases} reached the finite terminal proposition and " +
                "${report.diagnosticCases} remained diagnostic. This does not establish general strategy. " +
                "Every declared conformance condition was satisfied: ${report.readyForBlindReview}. " +
                "Report: $reportPath; packet=$packetPath"
        )
        check(report.readyForBlindReview) { "Tactical horizon root conformance failed: ${report.failureReasons}" }
        return
    }
    if (options.suite == "evaluator-comparison") {
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
        return
    }
    if (options.suite == "pilot-calibrate") {
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
        return
    }
    if (options.suite == "tournament-amendment") {
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
        return
    }
    if (options.suite == "tournament-v3-calibrated") {
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
        return
    }
    if (options.suite in setOf("outcome-qualification-preflight", "outcome-qualification-pilot")) {
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
        return
    }
    if (options.suite in setOf("search-budget-frontier-preflight", "search-budget-frontier-pilot")) {
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
        return
    }
    if (options.suite in setOf("search-budget-frontier-extension-preflight", "search-budget-frontier-extension")) {
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
        return
    }
    if (options.suite == "baseline-factorial-tournament") {
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
        return
    }
    if (options.suite == "baseline-factorial-smoke") {
        require(options.seed == BASELINE_FACTORIAL_BASE_SEED && options.pairs == 1 && options.threads == BASELINE_FACTORIAL_SMOKE_WORKERS) {
            "The baseline smoke requires its fixed seed, one pair, and four workers"
        }
        val report = BaselineFactorialSmokeRunner(root, registry, manifest, options.seed).run(options.threads)
        println(
            "The unscored work-only factorial smoke recorded ${report.games.size}/$BASELINE_FACTORIAL_SMOKE_GAMES games; " +
                "passed=${report.passed}. It is never pooled with the scored baseline. Smoke: ${report.smokeIdentity}"
        )
        check(report.passed) { "Baseline factorial smoke failed: ${report.failureReasons}" }
        return
    }
    if (options.suite == "tournament-fallback-diagnostic") {
        val sourceRunIdentity = requireNotNull(options.sourceRunIdentity) {
            "--source-run is required for tournament-fallback-diagnostic"
        }
        val (report, reportPath) = TournamentFallbackDiagnosticRunner(root, registry, manifest)
            .run(sourceRunIdentity, options.threads)
        println(
            "Tournament fallback diagnostic ${if (report.reproduced) "REPRODUCED" else "DIVERGED"}: " +
                "${report.reproducedFallbackCount}/${report.sourceFallbackCount} fallbacks; $reportPath"
        )
        return
    }
    if (options.suite == "tree-reuse-validation") {
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
        return
    }
    if (options.suite == "tournament-performance") {
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
        return
    }
    if (options.suite == "tournament-remediation") {
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
        return
    }
    if (options.suite == "tournament-remediation-check") {
        val report = TournamentRemediationRunner(root, registry, manifest, options.seed)
            .auditExistingReplays()
        val path = diagnosticOutput("tournament-remediation/replay-check.json")
        writeJsonAtomically(path, report)
        println(
            "Tournament remediation replay check: ${report.entries.count { it.verified }}/" +
                "${report.entries.size} verified; $path"
        )
        return
    }
    if (options.suite == "tournament-remediation-probe") {
        val report = TournamentRemediationRunner(root, registry, manifest, options.seed).runReplayProbe()
        val path = diagnosticOutput("tournament-remediation/replay-probe.json")
        writeJsonAtomically(path, report)
        println(
            "Tournament remediation replay probe: verified=${report.game.replayVerified}, " +
                "exception=${report.game.exception}, diagnostic=${report.game.replayVerificationDiagnostic}; $path"
        )
        return
    }
    if (options.suite == "play") {
        SemanticReducerPlaySession(
            registry = registry,
            manifest = manifest,
            seed = options.seed,
            input = BufferedReader(InputStreamReader(System.`in`)),
            output = PrintWriter(System.out, true),
        ).run()
        return
    }
    if (options.suite == "tactical-proof") {
        val runner = TacticalProofRunner(registry, manifest)
        var run = runner.run()
        val packetPath = diagnosticOutput("tactical-proof/blinded-authoring.json")
        val packetJson = evidenceJson.encodeToString(run.packet)
        writeJsonAtomically(packetPath, run.packet)
        val packetDigest = sha256(packetJson)
        val reviewPath = options.proofReview
            ?: store.review("tactical-proof-v1.review.json")
        if (Files.isRegularFile(reviewPath)) {
            val review = evidenceJson.decodeFromString<TacticalProofHumanReview>(Files.readString(reviewPath))
            run = runner.applyReview(run, review, packetDigest)
        }
        val reportPath = diagnosticOutput("tactical-proof/report.json")
        writeJsonAtomically(reportPath, run.report)
        val proved = run.report.cases.count { it.authority == TacticalEvidenceAuthority.CERTIFIED }
        val diagnostic = run.report.cases.count { it.authority == TacticalEvidenceAuthority.DIAGNOSTIC }
        println(
            "$proved/${run.report.cases.size} supplied cases were proved over the finite terminal proposition; " +
                "$diagnostic remained diagnostic. Human-label status: ${run.report.humanReviewStatus}. " +
                "The repository-authored checker shares the engine and action representation and does not " +
                "establish general strategic truth. Machine proof and the supplied human accepted set agree: " +
                "${run.report.promotionPassed}. Report: $reportPath; blinded packet: $packetPath " +
                "sha256=$packetDigest"
        )
        return
    }
    if (options.suite == "tactical-proof-benchmark") {
        val proof = TacticalProofRunner(registry, manifest).run().report
        check(proof.oraclePassed) { "The finite tactical solver did not establish every requested proposition" }
        val certifiedIds = proof.cases.filter {
            it.authority == TacticalEvidenceAuthority.CERTIFIED
        }.map { it.definition.id }.toSet()
        val certifiedCases = TacticalProofCatalog.cases.filter { it.id in certifiedIds }
        val cases = certifiedCases.take(options.caseLimit.coerceAtMost(certifiedCases.size))
        val report = TacticalProofLeafBenchmarkRunner(
            registry = registry,
            manifest = manifest,
            particles = options.particles,
            simulations = options.simulations,
            maxPolicyDecisions = options.maxPolicyDecisions,
        ).run(proof, cases)
        val jsonPath = diagnosticOutput("tactical-proof/leaf-benchmark.json")
        writeJsonAtomically(jsonPath, report)
        val markdownPath = diagnosticOutput("tactical-proof/leaf-benchmark.md")
        writeTextAtomically(markdownPath, renderTacticalProofLeafBenchmark(report))
        report.leafResults.forEach { result ->
            println(
                "${result.leaf}: ${result.solvedTrials}/${result.totalTrials} accepted-set trials, " +
                    "p95 search=${result.p95SearchMillis?.let { "%.1f".format(it) }} ms"
            )
        }
        println(
            "Every requested finite-case evaluator trial finished without a technical failure counted by the " +
                "runner: ${report.completed}. Agreement is relative to the repository-authored accepted sets, " +
                "not general strategy. Outputs: $jsonPath; $markdownPath"
        )
        check(report.completed) { "Tactical proof leaf benchmark had technical failures: ${report.failureReasons}" }
        return
    }
    if (options.suite == "legacy-tactical-benchmark") {
        val cases = TacticalBenchmarkCatalog.cases.take(options.caseLimit)
        val report = LegacyTacticalLeafBenchmarkRunner(
            registry = registry,
            manifest = manifest,
            particles = options.particles,
            simulations = options.simulations,
            maxPolicyDecisions = options.maxPolicyDecisions,
        ).run(cases)
        val jsonPath = diagnosticOutput("tactical/legacy-leaf-benchmark.json")
        writeJsonAtomically(jsonPath, report)
        val markdownPath = diagnosticOutput("tactical/legacy-leaf-benchmark.md")
        writeTextAtomically(markdownPath, renderLegacyTacticalLeafBenchmark(report))
        report.leafResults.forEach { result ->
            println(
                "${result.leaf}: ${result.solvedTrials}/${result.totalTrials} mechanical, " +
                    "${result.hiddenPairsStable}/${result.hiddenPairsTotal} hidden pairs, " +
                    "p95 search=${"%.1f".format(result.p95SearchMillis)} ms"
            )
        }
        println(
            "Every requested hand-authored lethal/survival trial finished without a technical failure counted " +
                "by the runner: ${report.completed}. The predicates are a legacy diagnostic, not independent " +
                "strategic truth. Outputs: $jsonPath; $markdownPath"
        )
        check(report.completed) { "Legacy tactical leaf benchmark had technical failures: ${report.failureReasons}" }
        return
    }
    if (options.suite == "tournament") {
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
        return
    }
    val profile = loadProfile(root, options)
    val arena = SearchTeacherArena(registry, manifest, profile, options.seed)
    println("Search-teacher ${options.suite}: ${registry.size} cards, profile ${profile.id}")

    when (options.suite) {
        "smoke" -> {
            val heuristic = arena.play("smoke-heuristic", options.seed, ArenaPolicyKind.HEURISTIC, ArenaPolicyKind.HEURISTIC)
            val search = arena.play("smoke-search", options.seed + 1, ArenaPolicyKind.SEARCH, ArenaPolicyKind.HEURISTIC)
            val report = SmokeReport(
                generatedAtUtc = Instant.now().toString(),
                deckId = manifest.id,
                deckHash = manifest.deckHash(),
                gridConfigurations = loadSearchGrid().particles.size * loadSearchGrid().simulations.size *
                    loadSearchGrid().leafConfigurations.size * loadSearchGrid().actionSpaceProfiles.size,
                heuristicGame = heuristic,
                searchGame = search,
                passed = listOf(heuristic, search).all {
                    it.terminal && it.exception == null && it.illegalResponses == 0 && !it.stepLimit
                },
            )
            val path = diagnosticOutput("smoke/report.json")
            writeJsonAtomically(path, report)
            println(
                "Both smoke games reached an engine-reported game end without an exception, illegal response, " +
                    "or step-limit stop: ${report.passed}. This checks two executions, not strategic quality. " +
                    "Report: $path"
            )
            check(report.passed) { "Search-teacher smoke failed: $report" }
        }
        "inspection" -> {
            val gameId = UUID.randomUUID().toString()
            val directory = diagnosticOutput("inspection/$gameId")
            val publicPath = directory.resolve("${options.perspective}.inspection.json")
            val privilegedPath = directory.resolve(
                "privileged/${options.perspective}.privileged-inspection.json"
            )
            val game = arena.play(
                gameId = gameId,
                gameSeed = options.seed,
                p0Policy = if (options.perspective == "p0") ArenaPolicyKind.SEARCH else ArenaPolicyKind.HEURISTIC,
                p1Policy = if (options.perspective == "p1") ArenaPolicyKind.SEARCH else ArenaPolicyKind.HEURISTIC,
                evidence = GameEvidenceOptions(
                    inspection = publicPath,
                    privilegedInspection = privilegedPath,
                    inspectionPerspective = options.perspective,
                    outerCommit = currentOuterCommit(),
                    argentumCommit = currentArgentumCommit(),
                    profileHash = sha256(evidenceJson.encodeToString(profile)),
                ),
            )
            val attemptReportPath = directory.resolve("attempt-report.json")
            writeJsonAtomically(attemptReportPath, game.evidenceRunAttemptSummary())
            check(
                game.disposition == GameRunDisposition.GAME_ENDED &&
                    game.terminal && game.evidenceStop == null && game.exception == null &&
                    game.informationLedgerComplete
            ) {
                "Inspection game did not complete with a conformant ledger; work-only attempt accounting: " +
                    "$attemptReportPath"
            }
            println("Inspection replay: $publicPath")
            println("Privileged unlock: $privilegedPath")
        }
        "arena" -> {
            val report = pairedArena(
                arena = arena,
                profileId = profile.id,
                opponent = options.opponent,
                pairCount = options.pairs,
                baseSeed = options.seed,
                workerThreads = options.threads,
                checkpointRoot = diagnosticOutput("arena"),
            )
            val path = diagnosticOutput("arena/${options.opponent.name.lowercase()}.json")
            writeJsonAtomically(path, report)
            println(
                "Arena ${report.completeGames}/${report.gameCount}, improvement " +
                    "${"%.3f".format(report.pointImprovement)}, " +
                "CI [${"%.3f".format(report.confidenceLower)}, ${"%.3f".format(report.confidenceUpper)}]: $path"
            )
        }
        "arena-shard" -> {
            val shard = pairedArenaShard(
                arena = arena,
                profileId = profile.id,
                opponent = options.opponent,
                pairOffset = options.pairOffset,
                pairCount = options.pairs,
                baseSeed = options.seed,
                workerThreads = options.threads,
                checkpointRoot = diagnosticOutput("arena"),
            )
            val name = "${options.opponent.name.lowercase()}-${options.pairOffset}-${options.pairs}.json"
            val path = diagnosticOutput("arena/shards/$name")
            writeJsonAtomically(path, shard)
            println("Arena shard ${shard.pairOffset} until ${shard.pairOffset + shard.pairCount}: $path")
        }
        "arena-merge" -> {
            val shardDirectory = options.shardDirectory
                ?: store.work("arena/shards")
            val shards = loadArenaShards(shardDirectory).filter {
                it.profileId == profile.id &&
                    it.runIdentity == arena.runIdentity &&
                    it.opponent == options.opponent &&
                    it.baseSeed == options.seed
            }
            val report = aggregatePairedArenaShards(
                profileId = profile.id,
                runIdentity = arena.runIdentity,
                opponent = options.opponent,
                expectedPairCount = options.pairs,
                baseSeed = options.seed,
                shards = shards,
            )
            val path = diagnosticOutput("arena/${options.opponent.name.lowercase()}.json")
            writeJsonAtomically(path, report)
            println("Merged ${shards.size} shards into ${report.completePairs}/${report.pairCount} pairs: $path")
        }
        "tactical" -> {
            val report = TacticalBenchmarkRunner(registry, manifest, profile).run(includeStrategicReference = true)
            val path = diagnosticOutput("tactical/report.json")
            writeJsonAtomically(path, report)
            println(
                "Tactical check: ${report.mechanicallyForcedSolved}/${report.mechanicallyForcedTotal} " +
                    "hand-authored mechanically forced cases solved, " +
                    "${report.strategicSeparatedCases} separated strategic references, " +
                    "${report.proposalStressFailures} proposal and ${report.hiddenStateFailures} hidden-state failures " +
                    "and every declared condition satisfied=${report.passed}. This does not establish general " +
                    "strategy or complete action coverage. Report: $path"
            )
        }
        "tactical-authoring" -> {
            val (packet, path) = TacticalAuthoringPacketGenerator(root, registry, manifest)
                .generate(options.caseLimit)
            println("Tactical authoring packet ${packet.scenarios.size} scenarios: $path")
        }
        "tactical-horizon-authoring" -> {
            val limit = options.caseLimit.coerceAtMost(TacticalHorizonCatalog.cases.size)
            val (packet, path) = TacticalAuthoringPacketGenerator(root, registry, manifest)
                .generateHorizonSuite(limit)
            println("Tactical horizon authoring packet ${packet.scenarios.size} scenarios: $path")
        }
        "calibrate" -> {
            val calibration = SearchCalibration(manifest, registry, root, options.caseLimit)
            val report = calibration.run(resume = options.resume)
            val path = diagnosticOutput("calibration/report.json")
            writeJsonAtomically(path, report)
            val profiles = if (report.passed) calibration.writeProfiles(report) else emptyList()
            println(
                "Calibration recorded ${report.points.size} grid points; " +
                    "fast=${report.selectedFast?.let { "${it.particles}x${it.simulations}/${it.leaf}" }}, " +
                    "deep=${report.selectedDeep?.let { "${it.particles}x${it.simulations}/${it.leaf}" }}: " +
                "every declared grid, tactical-agreement, and compute-trend condition satisfied=${report.passed}. " +
                    "This does not establish optimal settings outside the supplied grid and cases. " +
                    "Report: $path; recorded profiles=$profiles"
            )
        }
        "latency-preflight" -> {
            val report = LatencyPreflightRunner(root, registry, manifest, options.caseLimit).run()
            val path = diagnosticOutput("latency-preflight/report.json")
            writeJsonAtomically(path, report)
            report.candidates.forEach { candidate ->
                println(
                    "Preflight ${candidate.profile.leaf}: " +
                        "p95=${"%.1f".format(candidate.measuredPoint.p95Millis)} ms, " +
                        "score=${"%.3f".format(candidate.measuredPoint.tacticalScore)}, " +
                        "JFR samples=${candidate.executionSamples}"
                )
            }
            println(
                "Every declared finite-case agreement, repeated-choice, p95 latency, and profiling-sample " +
                    "condition was satisfied: ${report.passed}. This work-only result does not authorize profile " +
                    "selection. Report: $path"
            )
            check(report.passed) { "Latency preflight failed: ${report.failureReasons}" }
        }
        "corpus" -> {
            val corpus = SearchTeacherCorpus(root, registry, manifest, profile, options.seed)
                .generate(options.games, options.threads)
            require(corpus.passed) {
                "Corpus generation retained stopped or ineligible games in work-only quarantine; no labels were " +
                    "admitted. Attempt accounting: " +
                    store.work("corpus-quarantine/${corpus.profileId}-${options.seed}/attempt-report.json")
            }
            val path = diagnosticOutput("corpus/v5/manifest.json")
            writePublicJsonAtomically(path, corpus)
            println(
                "${corpus.replayVerifiedGames}/${corpus.requestedGames} requested corpus games replayed with the " +
                    "recorded choices and outcomes; every corpus condition was satisfied=${corpus.passed}. Replay " +
                    "agreement does not establish strategic label quality. Manifest: $path"
            )
        }
        "ablations" -> {
            val report = SearchMethodAblations(
                registry, manifest, profile, options.seed,
                diagnosticOutput("arena"),
            )
                .run(options.pairs, options.threads)
            val path = diagnosticOutput("ablations/search-methods.json")
            writeJsonAtomically(path, report)
            println("Search-method ablations ${report.methods.size} methods x ${options.pairs} pairs: $path")
        }
        "belief" -> {
            val report = BeliefComparisonEvaluation(
                registry, manifest, profile, options.seed,
                diagnosticOutput("arena"),
            )
                .run(options.pairs, options.heldOutPairs, options.threads)
            val path = diagnosticOutput("belief/comparison.json")
            writeJsonAtomically(path, report)
            println(
                "The declared comparison selected policy-conditioned unseen-card weighting for later teacher " +
                    "experiments: ${report.conditionedModeSelected}. This selection is relative to the recorded opponents, games, " +
                    "and threshold and does not establish that the probabilities match human beliefs. Report: $path"
            )
        }
        "opponent-models" -> {
            val report = OpponentModelEvaluation(
                registry, manifest, profile, options.seed,
                diagnosticOutput("arena"),
            )
                .run(options.pairs, options.threads)
            val path = diagnosticOutput("ablations/opponent-models.json")
            writeJsonAtomically(path, report)
            println("Opponent-model ablations ${report.models.size} models x ${options.pairs} pairs: $path")
        }
        "population" -> {
            val report = PopulationEvaluation(
                registry, manifest, profile, options.seed,
                diagnosticOutput("arena"),
            )
                .run(options.pairs, options.threads)
            val path = diagnosticOutput("population/cross-play.json")
            writeJsonAtomically(path, report)
            println(
                "Every declared comparison against the recorded policy population met its report conditions: " +
                    "${report.passed}. This does not establish performance against unlisted policies or people. " +
                    "Report: $path"
            )
        }
        "review" -> {
            val result = ReviewEvidenceGenerator(root, registry, manifest, options.seed).generate(
                corpusManifestPath = options.corpusManifest
                    ?: store.work("corpus/v5/manifest.json").takeIf(Files::isRegularFile)
                    ?: store.latest("corpus/v5/manifest.json"),
                reviewItems = options.reviewItems,
                surprisingCases = options.surprisingCases,
            )
            val packetPath = diagnosticOutput("review/expert-packet.json")
            writePublicJsonAtomically(packetPath, result.packet)
            val replayPath = diagnosticOutput("review/privileged/surprising-lines.privileged.json")
            writeJsonAtomically(replayPath, result.surprisingLines)
            println(
                "Review packet ${result.packet.itemCount} blinded decisions; " +
                    "${result.surprisingLines.verifiedCases}/${result.surprisingLines.requestedCases} " +
                    "surprising lines replayed: $packetPath; $replayPath"
            )
        }
        "replay" -> {
            val manifestPath = options.corpusManifest
                ?: store.work("corpus/v5/manifest.json").takeIf(Files::isRegularFile)
                ?: store.latest("corpus/v5/manifest.json")
            val corpus = evidenceJson.decodeFromString<CorpusManifest>(Files.readString(manifestPath))
            val verifier = SearchTeacherCorpus(root, registry, manifest, profile, options.seed)
            val results = corpus.entries.map { entry ->
                val publicPath = root.resolve(entry.publicTrajectory)
                val canonicalPath = privilegedCanonicalReplayPath(publicPath)
                entry.gameId to verifier.verifyExisting(
                    publicPath,
                    canonicalPath.takeIf(Files::exists) ?: privilegedDebugPath(publicPath),
                )
            }
            results.forEach { (gameId, replay) -> println("$gameId: $replay") }
            check(results.all { it.second.verified }) { "One or more corpus replays diverged" }
        }
        "throughput" -> {
            val report = ThroughputProfiler(root, registry, manifest, profile).run(options.caseLimit)
            val path = diagnosticOutput("throughput/${profile.id}.json")
            writeJsonAtomically(path, report)
            println(
                "Throughput recorded ${"%.2f".format(report.decisionsPerSecond)} decisions/s, " +
                    "p95=${"%.1f".format(report.decisionP95Millis)} ms, " +
                    "${report.executionSamples} JFR samples, and every declared timing/profiling condition " +
                    "satisfied=${report.passed}. This host-specific measurement does not establish playing " +
                    "strength or latency elsewhere. Report: $path"
            )
        }
        "baseline-hardening" -> {
            val corpusPath = options.corpusManifest
                ?: store.work("corpus/v5/manifest.json").takeIf(Files::isRegularFile)
                ?: store.latest("corpus/v5/manifest.json")
            val hardening = BaselineHardeningRunner(root, registry, manifest, profile, options.seed)
                .run(corpusPath, options.games, options.threads)
            println("Baseline hardening work bundle: $hardening")
        }
        else -> error("Suite ${options.suite} is not wired yet")
    }
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

private fun loadProfile(root: Path, options: SearchTeacherCli): FrozenSearchProfile {
    val path = options.profilePath ?: EvidenceStore(root).frozen("fast-profile-v1.json")
    if (Files.exists(path)) return evidenceJson.decodeFromString(Files.readString(path))
    require(options.suite in setOf(
        "smoke", "calibrate", "latency-preflight", "tactical-authoring", "tactical-horizon-authoring", "inspection"
    )) {
        "A calibrated frozen profile is required for ${options.suite}: $path"
    }
    return SearchTeacherArena.smokeProfile()
}

@Serializable
private data class SmokeReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val deckId: String,
    val deckHash: String,
    val gridConfigurations: Int,
    val heuristicGame: GameRunResult,
    val searchGame: GameRunResult,
    val passed: Boolean,
)

private fun loadArenaShards(directory: Path): List<PairedArenaShard> {
    require(Files.isDirectory(directory)) { "Arena shard directory does not exist: $directory" }
    return Files.list(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
            .sorted()
            .map { evidenceJson.decodeFromString<PairedArenaShard>(Files.readString(it)) }
            .toList()
    }
}
