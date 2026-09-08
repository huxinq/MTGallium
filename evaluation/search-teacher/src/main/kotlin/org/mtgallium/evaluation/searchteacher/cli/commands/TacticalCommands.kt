package org.mtgallium.evaluation.searchteacher.cli.commands

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommand
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCommandContext
import org.mtgallium.evaluation.searchteacher.cli.CommandPreparation

internal val tacticalCommands = listOf(
    SearchTeacherCommand(
        "tactical-horizon-check", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTacticalHorizonCheck,
    ),
    SearchTeacherCommand(
        "play", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runPlay,
    ),
    SearchTeacherCommand(
        "tactical-proof", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTacticalProof,
    ),
    SearchTeacherCommand(
        "tactical-proof-benchmark", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runTacticalProofBenchmark,
    ),
    SearchTeacherCommand(
        "legacy-tactical-benchmark", CommandPreparation.CURRENT_SOURCE,
        SearchTeacherCommandContext::runLegacyTacticalBenchmark,
    ),
)

private fun SearchTeacherCommandContext.runTacticalHorizonCheck() {
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
}

private fun SearchTeacherCommandContext.runPlay() {
    SemanticReducerPlaySession(
        registry = registry,
        manifest = manifest,
        seed = options.seed,
        input = BufferedReader(InputStreamReader(System.`in`)),
        output = PrintWriter(System.out, true),
    ).run()
}

private fun SearchTeacherCommandContext.runTacticalProof() {
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
}

private fun SearchTeacherCommandContext.runTacticalProofBenchmark() {
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
}

private fun SearchTeacherCommandContext.runLegacyTacticalBenchmark() {
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
}
