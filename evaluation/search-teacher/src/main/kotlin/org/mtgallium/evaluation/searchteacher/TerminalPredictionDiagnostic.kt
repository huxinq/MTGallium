package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.research.run.*
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

@Serializable
internal data class TerminalPredictionDiagnosticPlan(
    val bank: CloningComparisonInput, val targets: SavedRootPolicyInput,
    val model: RootKernelFitReference,
)

@Serializable
internal data class TerminalPredictionMoments(
    val pooledResidualSquared: Double,
    val repetitionDifferenceSquaredOverFour: Double,
    val residualCrossProduct: Double,
    val targetCrossProduct: Double,
    val predictionSquared: Double,
    val predictionTargetProduct: Double,
)

/** Descriptive identities, not an unconditional variance decomposition: repetitions share a posterior. */
internal fun terminalPredictionMoments(scores: List<Double>, repetitions: List<List<Double>>): TerminalPredictionMoments {
    require(scores.size >= 2 && scores.all(Double::isFinite))
    require(repetitions.size == 2 && repetitions.all { it.size == scores.size && it.all { v -> v.isFinite() && v in -1.0..1.0 } })
    fun center(x: List<Double>): List<Double> = x.map { it - x.average() }
    val p = center(scores); val a = center(repetitions[0]); val b = center(repetitions[1])
    fun mean(f: (Int) -> Double) = scores.indices.map(f).average()
    return TerminalPredictionMoments(
        mean { val e = p[it] - (a[it] + b[it]) / 2; e * e },
        mean { val d = a[it] - b[it]; d * d / 4 },
        mean { (p[it] - a[it]) * (p[it] - b[it]) },
        mean { a[it] * b[it] }, mean { p[it] * p[it] }, mean { p[it] * (a[it] + b[it]) / 2 },
    )
}

internal fun averageTerminalPredictionMoments(rows: List<TerminalPredictionMoments>): TerminalPredictionMoments {
    require(rows.isNotEmpty())
    return TerminalPredictionMoments(rows.map { it.pooledResidualSquared }.average(),
        rows.map { it.repetitionDifferenceSquaredOverFour }.average(), rows.map { it.residualCrossProduct }.average(),
        rows.map { it.targetCrossProduct }.average(), rows.map { it.predictionSquared }.average(),
        rows.map { it.predictionTargetProduct }.average())
}

@Serializable
internal data class TerminalPredictionRootDiagnostic(
    val rootId: String, val seedGroupId: String, val actions: List<String>, val scores: List<Double>,
    val targets: List<List<Double>>, val moments: TerminalPredictionMoments,
    val chosenAction: String, val sampledRegretByRepetition: List<Double>,
)

@Serializable
internal data class TerminalPredictionGroupDiagnostic(val seedGroupId: String, val roots: Int, val moments: TerminalPredictionMoments)

@Serializable
internal data class TerminalPredictionDiagnosticReport(
    val identity: String, val source: ResearchRunProvenance, val plan: TerminalPredictionDiagnosticPlan,
    val targetSource: org.mtgallium.agent.infoset.core.PolicySourceProvenance,
    val accounting: TerminalRootScreenAccounting, val roots: List<TerminalPredictionRootDiagnostic>,
    val groups: List<TerminalPredictionGroupDiagnostic>, val equalGroupMoments: TerminalPredictionMoments,
    val interpretation: String = "Development-only descriptive diagnosis with frozen predictions on groups absent from model training. " +
        "Action-centered moments average actions within roots, roots within groups, then groups equally. " +
        "Pooled residual squared equals residual cross-product plus repetition difference squared / 4. " +
        "Repetitions share sampled posterior preparation; cross-products include persistent posterior error and are not pure representation error. " +
        "Conditional continuation noise, fitted bias, model capacity, representation and posterior error are not causally isolated. " +
        "Sample-max regret is optimistic; inspected development groups are not fresh confirmation. No new samples, fit, promotion or gameplay.",
)

internal fun runTerminalPredictionDiagnostic(repository: Path, plan: TerminalPredictionDiagnosticPlan, output: Path): TerminalPredictionDiagnosticReport {
    val source = ResearchRunProvenance.capture(repository).also { it.requireReady() }
    require(!source.outerDirty && !source.engineDirty)
    val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "terminal prediction diagnostic")
    require(!Files.exists(destination))
    val model = CompiledRootActionKernel(plan.model.loadFrozenModel().model)
    val modelPath = Path.of(plan.model.directory)
    require(ResearchRunArtifacts.loadAndVerify(modelPath, plan.model.researchRunIdentity).artifacts.any { it.relativePath == "development.json" })
    val modelBindings = evidenceJson.decodeFromString<ResearchRunBindings>(Files.readString(modelPath.resolve("bindings.json")))
    require(modelBindings.protocol == "terminal-root-action-kernel-fit-v1")
    val modelReport = evidenceJson.decodeFromString<TerminalRootKernelFitReport>(Files.readString(modelPath.resolve("report.json")))
    val trainingPlan = modelReport.plan
    val trainingBank = loadVerifiedRealGamePositionBank(Path.of(trainingPlan.bank.directory), trainingPlan.bank.researchRunIdentity)
    val trainingTargets = loadTerminalRootScreen(trainingPlan.terminal, trainingBank)
    val trained = evidenceJson.decodeFromString<List<RootActionKernelTrainingRoot>>(Files.readString(modelPath.resolve("development.json")))
    val bank = loadVerifiedRealGamePositionBank(Path.of(plan.bank.directory), plan.bank.researchRunIdentity)
    val targets = loadTerminalRootScreen(plan.targets, bank)
    require(targets.plan.partition == PositionBankScreenPartition.DEVELOPMENT && targets.plan.repetitions == 2)
    requireProductionTerminalTarget(targets.plan)
    requireSameTerminalTarget(trainingTargets.plan, targets.plan)
    require(modelReport.source.checkedOutEngineCommit == targets.sourceProvenance.argentum.revision)
    require(trainingTargets.sourceProvenance.argentum.revision == targets.sourceProvenance.argentum.revision)
    val data = terminalRootTrainingData(bank, targets, PositionBankScreenPartition.DEVELOPMENT)
    val trainedGroups = trained.map { it.seedGroupId }.toSet()
    require(data.none { it.seedGroupId in trainedGroups }) { "Diagnostic roots must be from groups absent from this model's training" }
    val roots = data.map { root ->
        val menu = bank.roots.single { it.rootId == root.rootId }.reconstructedCandidates.map { it.signature }
        val scores = model.scores(root.features)
        val values = terminalTargetPrefixValues(targets, root.rootId, requireNotNull(targets.plan.terminalContinuation).samplesPerAction)
            .map { r -> menu.map { r.getValue(it) } }
        val chosen = scores.indices.maxBy { scores[it] }
        TerminalPredictionRootDiagnostic(root.rootId, root.seedGroupId, menu, scores, values,
            terminalPredictionMoments(scores, values), menu[chosen], values.map { it.max() - it[chosen] })
    }
    val groups = roots.groupBy { it.seedGroupId }.toSortedMap().map { (id, rows) ->
        TerminalPredictionGroupDiagnostic(id, rows.size, averageTerminalPredictionMoments(rows.map { it.moments }))
    }
    val bindings = ResearchRunBindings(protocol = "terminal-prediction-diagnostic-v1", material = mapOf(
        "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
        "plan" to sha256(evidenceJson.encodeToString(TerminalPredictionDiagnosticPlan.serializer(), plan)),
        "bank-manifest" to researchSha256File(Path.of(plan.bank.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        "target-manifest" to researchSha256File(Path.of(plan.targets.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        "model-manifest" to plan.model.manifestSha256,
    ))
    val report = TerminalPredictionDiagnosticReport(bindings.identity, source, plan, targets.sourceProvenance,
        terminalRootScreenAccounting(targets, bank), roots, groups, averageTerminalPredictionMoments(groups.map { it.moments }))
    Files.createDirectories(destination)
    writeJsonAtomically(destination.resolve("bindings.json"), bindings)
    writeJsonAtomically(destination.resolve("plan.json"), plan)
    writeJsonAtomically(destination.resolve("report.json"), report)
    ResearchRunArtifacts(destination, bindings.identity).also { artifacts ->
        listOf("bindings.json", "plan.json", "report.json").forEach(artifacts::register); artifacts.finalize()
    }
    ResearchRunArtifacts.loadAndVerify(destination, bindings.identity)
    return report
}
