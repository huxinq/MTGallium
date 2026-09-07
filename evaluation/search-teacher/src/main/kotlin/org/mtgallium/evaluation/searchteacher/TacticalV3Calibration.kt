package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.mtgallium.agent.searchteacher.*
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal data class TacticalV3FitTarget(val rootId: String, val seedGroupId: String,
    val features: MonoRedTacticalLinearFeatures, val target: Double)

@Serializable
internal data class TacticalV3FitResult(val fitted: MonoRedTacticalEvaluatorSettings, val selectedIteration: Int,
    val handMeanSquaredError: Double, val fittedMeanSquaredError: Double, val trace: List<VisibleV2FitStep>)

internal fun tacticalV3Coefficients(w: MonoRedTacticalEvaluatorWeights): DoubleArray =
    doubleArrayOf(w.life, w.lethal, w.body, w.attack, w.block, w.reach, w.hand, w.mana, w.landConversion, w.initiative)

internal fun tacticalV3Configured(base: MonoRedTacticalEvaluatorSettings, x: DoubleArray): MonoRedTacticalEvaluatorSettings {
    require(x.size == 10)
    return base.copy(weights = MonoRedTacticalEvaluatorWeights(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], x[8], x[9]))
}

internal fun tacticalV3TargetWeights(targets: List<TacticalV3FitTarget>): List<Double> {
    require(targets.isNotEmpty() && targets.map { it.rootId }.distinct().size == targets.size)
    require(targets.all { it.rootId.isNotBlank() && it.seedGroupId.isNotBlank() && it.target.isFinite() && it.target in -1.0..1.0 })
    val counts = targets.groupingBy { it.seedGroupId }.eachCount()
    return targets.map { 1.0 / counts.size / counts.getValue(it.seedGroupId) }
}

/** The existing clipping, amplitude and temperature stay fixed; only the ten raw-score weights fit. */
internal fun tacticalV3Derivative(features: MonoRedTacticalLinearFeatures, c: MonoRedTacticalEvaluatorSettings): Double {
    val raw = features.rawScore(c.weights)
    if (raw <= -6 || raw >= 6) return 0.0
    val scaled = features.evaluate(c) / .95
    return .95 * (1 - scaled * scaled) / c.outputTemperature
}

internal fun fitTacticalV3Coefficients(targets: List<TacticalV3FitTarget>, hand: MonoRedTacticalEvaluatorSettings,
    fit: VisibleV2FitConfig): TacticalV3FitResult {
    val weights = tacticalV3TargetWeights(targets)
    val prior = tacticalV3Coefficients(hand.weights); val x = prior.copyOf()
    val first = DoubleArray(x.size); val second = DoubleArray(x.size)
    fun loss(c: MonoRedTacticalEvaluatorSettings) = targets.indices.sumOf { i ->
        weights[i] * (targets[i].features.evaluate(c) - targets[i].target).pow(2)
    }
    fun penalty() = x.indices.sumOf { (x[it] - prior[it]).pow(2) } * fit.priorPenalty / x.size
    val initial = loss(hand)
    var bestObjective = initial; var best = hand; var bestIteration = 0
    val trace = mutableListOf(VisibleV2FitStep(0, initial, initial))
    for (iteration in 1..fit.iterations) {
        val current = tacticalV3Configured(hand, x)
        val gradient = DoubleArray(x.size) { 2 * fit.priorPenalty * (x[it] - prior[it]) / x.size }
        targets.forEachIndexed { i, target ->
            val factor = 2 * weights[i] * (target.features.evaluate(current) - target.target) * tacticalV3Derivative(target.features, current)
            gradient.indices.forEach { j -> gradient[j] += factor * target.features.values[j] }
        }
        for (j in x.indices) {
            require(gradient[j].isFinite())
            first[j] = .9 * first[j] + .1 * gradient[j]
            second[j] = .999 * second[j] + .001 * gradient[j] * gradient[j]
            x[j] -= fit.learningRate * (first[j] / (1 - .9.pow(iteration))) /
                (sqrt(second[j] / (1 - .999.pow(iteration))) + 1e-8)
        }
        val configured = tacticalV3Configured(hand, x)
        val mse = loss(configured); val objective = mse + penalty()
        require(objective.isFinite())
        if (objective < bestObjective) { bestObjective = objective; best = configured; bestIteration = iteration }
        if (iteration % 50 == 0 || iteration == fit.iterations) trace += VisibleV2FitStep(iteration, mse, objective)
    }
    return TacticalV3FitResult(best, bestIteration, initial, loss(best), trace)
}

@Serializable
internal data class TacticalV3CalibrationPlan(val previousExperiment: CloningComparisonInput,
    val hand: MonoRedTacticalEvaluatorSettings = MonoRedTacticalEvaluatorSettings(), val fit: VisibleV2FitConfig = VisibleV2FitConfig())

@Serializable
internal data class TacticalV3CalibrationReport(val researchRunIdentity: String, val source: ResearchRunProvenance,
    val plan: TacticalV3CalibrationPlan, val targets: List<TacticalV3FitTarget>, val fit: TacticalV3FitResult,
    val heldOutHandMeanSquaredError: Double, val heldOutFittedMeanSquaredError: Double,
    val withinV3Regret: SavedRootRegretReport, val versusV2Regret: SavedRootRegretReport,
    val interpretation: String = "Existing ten tactical-v3 weights fit DEVELOPMENT maximum-action mean reference values from the retained deeper hand-v2 searches. Temperature, clipping, families, annotations, and all non-weight settings stay fixed. Minimum equal-group training MSE plus hand prior selects before validation. Hand and fitted v3 share registered v3 settlement rules; v2 versus v3 is not a pure formula comparison. Validation is coefficient-held-out but method-adaptive. References are mostly heuristic search backups, not observed outcomes.")

internal class TacticalV3CalibrationRunner(private val repository: Path, private val registry: CardRegistry, private val deck: DeckManifest) {
    private fun previous(plan: TacticalV3CalibrationPlan): VisibleV2ExperimentReport {
        val directory = Path.of(plan.previousExperiment.directory)
        val manifest = ResearchRunArtifacts.loadAndVerify(directory, plan.previousExperiment.researchRunIdentity)
        require(manifest.artifacts.any { it.relativePath == "report.json" } && manifest.artifacts.any { it.relativePath == "bindings.json" })
        val report = evidenceJson.decodeFromString<VisibleV2ExperimentReport>(Files.readString(directory.resolve("report.json")))
        val bindings = evidenceJson.decodeFromString<ResearchRunBindings>(Files.readString(directory.resolve("bindings.json")))
        require(report.researchRunIdentity == plan.previousExperiment.researchRunIdentity && bindings.identity == report.researchRunIdentity)
        require(bindings.protocol == "visible-v2-calibration-experiment-v1")
        return report
    }
    private fun policy(old: VisibleV2ExperimentReport, id: String, settings: MonoRedTacticalEvaluatorSettings) =
        PositionBankScreenPolicy(old.plan.selection.copy(id = id, evaluator = null, tacticalEvaluator = CalibrationTacticalEvaluator.Settings(settings)), MonoRedVisibleEvaluatorConfig())

    fun preflight(plan: TacticalV3CalibrationPlan, output: Path, workers: Int): PositionBankScreenReport {
        val old = previous(plan)
        val hand = policy(old, "hand-v3", plan.hand)
        val result = PositionBankScreenRunner(repository, registry, deck).run(PositionBankScreenPlan(
            bankDirectory = old.plan.bankDirectory, expectedBankIdentity = old.plan.bankIdentity,
            partition = PositionBankScreenPartition.DEVELOPMENT, mode = PositionBankScreenMode.SEARCH,
            rootLimit = 2, repetitions = 1, policies = listOf(hand.copy(search = hand.search.copy(simulations = 4))),
            searchSeedDomain = "visible-v2-calibration-selection-v1"), output, workers)
        require(result.valid && result.rows.size == 2)
        require(result.rows.all { it.evaluatorConfigurationId == plan.hand.configurationId })
        return result
    }
    fun run(plan: TacticalV3CalibrationPlan, output: Path, workers: Int): TacticalV3CalibrationReport {
        val source = ResearchRunProvenance.capture(repository, "third_party/argentum-engine").also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "tactical-v3 coefficient calibration")
        require(!Files.exists(destination))
        val old = previous(plan)
        val bank = loadVerifiedRealGamePositionBank(Path.of(old.plan.bankDirectory), old.plan.bankIdentity)
        val evaluator = MonoRedTacticalEvaluator(plan.hand)
        fun targets(partition: PositionBankScreenPartition, input: SavedRootPolicyInput) =
            visibleV2ReferenceTargets(bank, input, partition, old.plan.hand).map { target ->
                val root = bank.roots.single { it.rootId == target.rootId }
                TacticalV3FitTarget(target.rootId, target.seedGroupId,
                    MonoRedTacticalLinearFeatures.fromComponents(evaluator.evaluateDetailed(root.information, root.actor).components), target.target)
            }
        val development = targets(PositionBankScreenPartition.DEVELOPMENT,
            SavedRootPolicyInput(Path.of(plan.previousExperiment.directory).resolve("development-reference").toString(), old.developmentReferenceIdentity, old.plan.reference.id))
        val heldOutGroups = bank.roots.filter { it.partition == RealGamePositionPartition.VALIDATION }.map { it.seedGroupId }.toSet()
        require(development.map { it.seedGroupId }.distinct().size >= 2 && heldOutGroups.isNotEmpty() && development.none { it.seedGroupId in heldOutGroups })
        val bindings = ResearchRunBindings(protocol = "tactical-v3-coefficient-calibration-v1", material = mapOf(
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "plan" to sha256(evidenceJson.encodeToString(TacticalV3CalibrationPlan.serializer(), plan)),
            "previous-manifest" to researchSha256File(Path.of(plan.previousExperiment.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "worker-threads" to workers.toString(), "selection" to "minimum-development-equal-group-mse-plus-per-coefficient-hand-prior-v1",
            "optimizer" to "fullbatch-adam-b1=.9-b2=.999-eps=1e-8-fixed-temperature-clipping-amplitude-v1"))
        Files.createDirectories(destination)
        writeJsonAtomically(destination.resolve("plan.json"), plan); writeJsonAtomically(destination.resolve("bindings.json"), bindings)
        val fit = fitTacticalV3Coefficients(development, plan.hand, plan.fit)
        writeJsonAtomically(destination.resolve("fit.json"), fit); writeJsonAtomically(destination.resolve("evaluator.json"), fit.fitted)
        val restored = evidenceJson.decodeFromString<MonoRedTacticalEvaluatorSettings>(Files.readString(destination.resolve("evaluator.json")))
        require(restored == fit.fitted && development.all { it.features.evaluate(restored) == it.features.evaluate(fit.fitted) })
        // Held-out targets are first opened after fitting and exact checkpoint reload.
        val validation = targets(PositionBankScreenPartition.VALIDATION, old.heldOutRegret.plan.reference)
        val weights = tacticalV3TargetWeights(validation)
        fun mse(c: MonoRedTacticalEvaluatorSettings) = validation.indices.sumOf { weights[it] * (validation[it].features.evaluate(c) - validation[it].target).pow(2) }
        val selectionPath = destination.resolve("validation-selection")
        val selection = PositionBankScreenRunner(repository, registry, deck).run(PositionBankScreenPlan(
            bankDirectory = old.plan.bankDirectory, expectedBankIdentity = old.plan.bankIdentity,
            partition = PositionBankScreenPartition.VALIDATION, mode = PositionBankScreenMode.SEARCH,
            rootLimit = old.heldOutRegret.roots, repetitions = old.plan.repetitions,
            policies = listOf(policy(old, "hand-v3", plan.hand), policy(old, "fitted-v3", fit.fitted)),
            searchSeedDomain = "visible-v2-calibration-selection-v1"), selectionPath, workers)
        require(selection.valid)
        val handInput = SavedRootPolicyInput(selectionPath.toString(), selection.researchRunIdentity, "hand-v3")
        val fittedInput = SavedRootPolicyInput(selectionPath.toString(), selection.researchRunIdentity, "fitted-v3")
        val within = SavedRootRegret.analyze(old.heldOutRegret.plan.copy(baseline = handInput, candidate = fittedInput))
        val versus = SavedRootRegret.analyze(old.heldOutRegret.plan.copy(candidate = fittedInput))
        val report = TacticalV3CalibrationReport(bindings.identity, source, plan, development, fit, mse(plan.hand), mse(fit.fitted), within, versus)
        writeJsonAtomically(destination.resolve("report.json"), report)
        val artifacts = ResearchRunArtifacts(destination, bindings.identity)
        Files.walk(destination).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { artifacts.register(destination.relativize(it).toString()) } }
        artifacts.finalize(); ResearchRunArtifacts.loadAndVerify(destination, bindings.identity)
        return report
    }
}
