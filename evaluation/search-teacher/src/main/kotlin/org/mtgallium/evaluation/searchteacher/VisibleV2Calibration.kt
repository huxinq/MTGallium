package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.agent.searchteacher.MonoRedVisibleFeatures
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal data class VisibleV2FitConfig(
    val iterations: Int = 2000, val learningRate: Double = 0.03, val priorPenalty: Double = 0.001,
) {
    init {
        require(iterations in 1..100_000 && learningRate.isFinite() && learningRate > 0 && learningRate <= 1)
        require(priorPenalty.isFinite() && priorPenalty >= 0)
    }
}

@Serializable
internal data class VisibleV2CalibrationPlan(
    val bankDirectory: String, val bankIdentity: String,
    /** DEVELOPMENT targets only. Validation targets are not opened by fitting. */
    val reference: SavedRootPolicyInput,
    val hand: MonoRedVisibleEvaluatorConfig = MonoRedVisibleEvaluatorConfig(),
    val fit: VisibleV2FitConfig = VisibleV2FitConfig(),
)

@Serializable
internal data class VisibleV2Target(
    val rootId: String, val seedGroupId: String, val features: MonoRedVisibleFeatures,
    val target: Double, val actionMeans: Map<String, Double>,
)

@Serializable
internal data class VisibleV2FitStep(val iteration: Int, val meanSquaredError: Double, val penalizedObjective: Double)

@Serializable
internal data class VisibleV2FitResult(
    val fitted: MonoRedVisibleEvaluatorConfig, val selectedIteration: Int,
    val handMeanSquaredError: Double, val fittedMeanSquaredError: Double,
    val trace: List<VisibleV2FitStep>,
)

@Serializable
internal data class VisibleV2CalibrationReport(
    val researchRunIdentity: String, val source: ResearchRunProvenance,
    val plan: VisibleV2CalibrationPlan, val trainingRoots: Int, val trainingSeedGroups: Int,
    val targets: List<VisibleV2Target>, val fit: VisibleV2FitResult,
    val targetMeaning: String = "Maximum across admitted actions of the mean retained forced-action search backup over reference repetitions, from this actor's information state. Deeper-search heuristic target, not an observed outcome; the maximum is noisy and selection-biased.",
    val fitMeaning: String = "Existing visible-v2 coefficients only, with tanhScale fixed to remove the common coefficient/scale degeneracy. Full-batch Adam minimizes equal-seed-group root value MSE plus a fixed squared-distance penalty to hand coefficients. The minimum training penalized objective chooses the checkpoint; validation targets and regret never select coefficients. Fresh-search held-out action regret is a separate evaluation.",
)

internal fun visibleV2Coefficients(c: MonoRedVisibleEvaluatorConfig): DoubleArray {
    require(c.landMarginals.size == 5) { "This calibration retains v2's existing five land marginals" }
    return (listOf(c.life, c.hand, c.power, c.toughness, c.haste) + c.landMarginals + c.landTail).toDoubleArray()
}
internal fun visibleV2Configured(base: MonoRedVisibleEvaluatorConfig, x: DoubleArray): MonoRedVisibleEvaluatorConfig {
    require(x.size == 11)
    return base.copy(life = x[0], hand = x[1], power = x[2], toughness = x[3], haste = x[4],
        landMarginals = x.slice(5..9), landTail = x[10])
}

/** Derivatives of the exact existing raw formula; no added feature family or card annotation. */
internal fun visibleV2CoefficientFeatures(f: MonoRedVisibleFeatures): DoubleArray = doubleArrayOf(
    (f.rootLife - f.opponentLife).toDouble(), (f.rootHandSize - f.opponentHandSize).toDouble(),
    f.rootPermanents.sumOf { it.power }.toDouble() - f.opponentPermanents.sumOf { it.power },
    f.rootPermanents.sumOf { it.toughness }.toDouble() - f.opponentPermanents.sumOf { it.toughness },
    (f.rootPermanents.count { it.haste } - f.opponentPermanents.count { it.haste }).toDouble(),
    *((1..5).map { n -> (if (f.rootLands >= n) 1.0 else 0.0) - (if (f.opponentLands >= n) 1.0 else 0.0) }.toDoubleArray()),
    ((f.rootLands - 5).coerceAtLeast(0) - (f.opponentLands - 5).coerceAtLeast(0)).toDouble(),
)

/** Both fitting and error summaries give each independent library-seed group equal weight. */
internal fun visibleV2TargetWeights(targets: List<VisibleV2Target>): List<Double> {
    require(targets.isNotEmpty() && targets.map { it.rootId }.distinct().size == targets.size)
    require(targets.all { it.target.isFinite() && it.target in -1.0..1.0 })
    val counts = targets.groupingBy { it.seedGroupId }.eachCount()
    return targets.map { 1.0 / counts.size / counts.getValue(it.seedGroupId) }
}

internal fun fitVisibleV2Coefficients(
    targets: List<VisibleV2Target>, hand: MonoRedVisibleEvaluatorConfig, fit: VisibleV2FitConfig,
): VisibleV2FitResult {
    val weights = visibleV2TargetWeights(targets)
    val prior = visibleV2Coefficients(hand)
    val x = prior.copyOf(); val first = DoubleArray(x.size); val second = DoubleArray(x.size)
    val features = targets.map { visibleV2CoefficientFeatures(it.features) }
    fun loss(c: MonoRedVisibleEvaluatorConfig) = targets.indices.sumOf { i ->
        (targets[i].features.evaluate(c) - targets[i].target).pow(2) * weights[i]
    }
    fun penalty() = x.indices.sumOf { (x[it] - prior[it]).pow(2) } * fit.priorPenalty / x.size
    val initialLoss = loss(hand)
    var bestObjective = initialLoss; var best = hand; var bestIteration = 0
    val trace = mutableListOf(VisibleV2FitStep(0, initialLoss, initialLoss))
    for (iteration in 1..fit.iterations) {
        val current = visibleV2Configured(hand, x)
        val gradient = DoubleArray(x.size) { j -> 2 * fit.priorPenalty * (x[j] - prior[j]) / x.size }
        targets.indices.forEach { i ->
            val prediction = targets[i].features.evaluate(current)
            val factor = 2 * weights[i] * (prediction - targets[i].target) * (1 - prediction * prediction) / hand.tanhScale
            gradient.indices.forEach { j -> gradient[j] += factor * features[i][j] }
        }
        for (j in x.indices) {
            require(gradient[j].isFinite())
            first[j] = 0.9 * first[j] + 0.1 * gradient[j]
            second[j] = 0.999 * second[j] + 0.001 * gradient[j] * gradient[j]
            x[j] -= fit.learningRate * (first[j] / (1 - 0.9.pow(iteration))) /
                (sqrt(second[j] / (1 - 0.999.pow(iteration))) + 1e-8)
        }
        val configured = visibleV2Configured(hand, x)
        val mse = loss(configured); val objective = mse + penalty()
        require(objective.isFinite())
        if (objective < bestObjective) { bestObjective = objective; best = configured; bestIteration = iteration }
        if (iteration % 50 == 0 || iteration == fit.iterations) trace += VisibleV2FitStep(iteration, mse, objective)
    }
    return VisibleV2FitResult(best, bestIteration, initialLoss, loss(best), trace)
}

/** Authenticated complete-menu target extraction, shared by fit and held-out value diagnostics. */
internal fun visibleV2ReferenceTargets(
    bank: RealGamePositionBankReport, input: SavedRootPolicyInput, partition: PositionBankScreenPartition,
    expectedHand: MonoRedVisibleEvaluatorConfig,
): List<VisibleV2Target> {
    val directory = Path.of(input.directory)
    val verified = ResearchRunArtifacts.loadAndVerify(directory, input.identity)
    require(verified.artifacts.any { it.relativePath == "report.json" })
    val report = evidenceJson.decodeFromString<PositionBankScreenReport>(Files.readString(directory.resolve("report.json")))
    require(report.researchRunIdentity == input.identity && report.valid)
    require(report.plan.expectedBankIdentity == bank.bankIdentity && report.plan.partition == partition)
    require(report.plan.mode == PositionBankScreenMode.ACTION_CONDITIONAL)
    val policy = report.plan.policies.single { it.search.id == input.policyId }
    require(policy.evaluator == expectedHand && policy.search.rootCloningFit == null)
    val roots = bank.roots.filter { it.partition.name == partition.name }.sortedBy { it.rootId }.take(report.plan.rootLimit)
    require(report.selectedRootIds == roots.map { it.rootId })
    require(report.rows.count { it.policyId == input.policyId } == roots.size * report.plan.repetitions)
    return roots.map { root ->
        val menu = root.reconstructedCandidates.map { it.signature }.toSet()
        val repetitions = (0 until report.plan.repetitions).map { repetition ->
            val row = report.rows.single { it.rootId == root.rootId && it.policyId == input.policyId && it.repetition == repetition }
            require(row.disposition == PositionBankScreenDisposition.ACTION_CONDITIONAL)
            require(row.rootActionEstimates.size == menu.size)
            row.rootActionEstimates.associate { estimate ->
                requireValidScreenSearch(estimate.diagnostics)
                estimate.action.signature to estimate.meanBackedValue
            }.also { require(it.keys == menu && it.values.all { value -> value.isFinite() && value in -1.0..1.0 }) }
        }
        val means = menu.associateWith { action -> repetitions.map { it.getValue(action) }.average() }
        VisibleV2Target(root.rootId, root.seedGroupId, root.visibleFeatures, means.values.max(), means)
    }
}

internal fun runVisibleV2Calibration(repository: Path, plan: VisibleV2CalibrationPlan, output: Path): VisibleV2CalibrationReport {
    val source = ResearchRunProvenance.capture(repository, "third_party/argentum-engine").also { it.requireReady() }
    require(!source.outerDirty && !source.engineDirty)
    val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "visible-v2 coefficient calibration")
    require(!Files.exists(destination))
    val bank = loadVerifiedRealGamePositionBank(Path.of(plan.bankDirectory), plan.bankIdentity)
    val targets = visibleV2ReferenceTargets(bank, plan.reference, PositionBankScreenPartition.DEVELOPMENT, plan.hand)
    require(targets.map { it.seedGroupId }.distinct().size >= 2)
    val heldOut = bank.roots.filter { it.partition == RealGamePositionPartition.VALIDATION }.map { it.seedGroupId }.toSet()
    require(heldOut.isNotEmpty() && targets.none { it.seedGroupId in heldOut })
    val bindings = ResearchRunBindings(protocol = "visible-v2-coefficient-calibration-v1", material = mapOf(
        "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
        "plan" to sha256(evidenceJson.encodeToString(VisibleV2CalibrationPlan.serializer(), plan)),
        "bank-manifest" to researchSha256File(Path.of(plan.bankDirectory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        "reference-manifest" to researchSha256File(Path.of(plan.reference.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        "target" to "max-action-of-repetition-means-from-root-actor-v1",
        "optimizer" to "fullbatch-adam-b1=.9-b2=.999-eps=1e-8-equal-seed-group-mse-prior-l2-per-coefficient-v1",
        "selection" to "minimum-development-penalized-objective-fixed-iteration-cap-v1",
    ))
    val fit = fitVisibleV2Coefficients(targets, plan.hand, plan.fit)
    val report = VisibleV2CalibrationReport(bindings.identity, source, plan, targets.size,
        targets.map { it.seedGroupId }.distinct().size, targets, fit)
    Files.createDirectories(destination)
    writeJsonAtomically(destination.resolve("plan.json"), plan)
    writeJsonAtomically(destination.resolve("bindings.json"), bindings)
    writeJsonAtomically(destination.resolve("evaluator.json"), fit.fitted)
    val restored = evidenceJson.decodeFromString<MonoRedVisibleEvaluatorConfig>(Files.readString(destination.resolve("evaluator.json")))
    require(restored == fit.fitted && targets.all { it.features.evaluate(restored) == it.features.evaluate(fit.fitted) })
    writeJsonAtomically(destination.resolve("report.json"), report)
    ResearchRunArtifacts(destination, bindings.identity).also {
        listOf("plan.json", "bindings.json", "evaluator.json", "report.json").forEach(it::register); it.finalize()
    }
    ResearchRunArtifacts.loadAndVerify(destination, bindings.identity)
    return report
}

@Serializable
internal data class VisibleV2HeldOutValueReport(
    val fitIdentity: String, val referenceIdentity: String, val roots: Int, val seedGroups: Int,
    val handMeanSquaredError: Double, val fittedMeanSquaredError: Double,
    val interpretation: String = "Secondary equal-seed-group value diagnostic on the frozen fit's bank validation partition. Does not select parameters or substitute for fresh-search action regret.",
)

internal fun evaluateVisibleV2HeldOutValues(
    fitDirectory: Path, fitIdentity: String, reference: SavedRootPolicyInput,
): VisibleV2HeldOutValueReport {
    val artifacts = ResearchRunArtifacts.loadAndVerify(fitDirectory, fitIdentity)
    require(setOf("report.json", "bindings.json", "evaluator.json").all { required -> artifacts.artifacts.any { it.relativePath == required } })
    val bindings = evidenceJson.decodeFromString<ResearchRunBindings>(Files.readString(fitDirectory.resolve("bindings.json")))
    require(bindings.protocol == "visible-v2-coefficient-calibration-v1" && bindings.identity == fitIdentity)
    val fit = evidenceJson.decodeFromString<VisibleV2CalibrationReport>(Files.readString(fitDirectory.resolve("report.json")))
    require(fit.researchRunIdentity == fitIdentity)
    val evaluator = evidenceJson.decodeFromString<MonoRedVisibleEvaluatorConfig>(Files.readString(fitDirectory.resolve("evaluator.json")))
    require(evaluator == fit.fit.fitted)
    val bank = loadVerifiedRealGamePositionBank(Path.of(fit.plan.bankDirectory), fit.plan.bankIdentity)
    val targets = visibleV2ReferenceTargets(bank, reference, PositionBankScreenPartition.VALIDATION, fit.plan.hand)
    val trainedGroups = fit.targets.map { it.seedGroupId }.toSet()
    require(targets.none { it.seedGroupId in trainedGroups })
    val weights = visibleV2TargetWeights(targets)
    fun error(config: MonoRedVisibleEvaluatorConfig) = targets.indices.sumOf { i ->
        (targets[i].features.evaluate(config) - targets[i].target).pow(2) * weights[i]
    }
    return VisibleV2HeldOutValueReport(fitIdentity, reference.identity, targets.size,
        targets.map { it.seedGroupId }.distinct().size, error(fit.plan.hand), error(evaluator))
}
