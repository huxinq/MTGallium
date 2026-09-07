package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlinx.serialization.Serializable
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal data class VisibleV2ActionCalibrationPlan(
    val previousExperiment: CloningComparisonInput,
    val cacheParticles: Int = 8, val cacheSimulationsPerAction: Int = 16,
    val fit: VisibleV2FitConfig = VisibleV2FitConfig(),
) { init { require(cacheParticles > 0 && cacheSimulationsPerAction > 0) } }

@Serializable
internal data class VisibleV2ActionCalibrationReport(
    val researchRunIdentity: String, val source: ResearchRunProvenance,
    val plan: VisibleV2ActionCalibrationPlan, val cacheIdentity: String,
    val trainingRoots: Int, val trainingSeedGroups: Int, val fit: VisibleV2ActionFitResult,
    val candidateSelection: SavedRootPolicyInput, val reusedHandSelection: Boolean,
    val regret: SavedRootRegretReport,
    val interpretation: String = "Action-centered fitting uses DEVELOPMENT roots only and reuses the prior expensive action references. One fitted candidate is tested in fresh search on the prior held-out panel. That panel has already informed the choice of calibration method, so it remains coefficient-held-out but is no longer an untouched independent method test. Cached continuations are a training surrogate, never counterfactual search authority.",
)

internal fun requireVisibleV2TraceRow(row: PositionBankScreenRow, hand: MonoRedVisibleEvaluatorConfig) {
    require(row.disposition == PositionBankScreenDisposition.ACTION_CONDITIONAL)
    require(row.visibleV2ActionTraces.size == row.rootActionEstimates.size && row.visibleV2ActionTraces.isNotEmpty())
    row.visibleV2ActionTraces.zip(row.rootActionEstimates).forEach { (trace, estimate) ->
        require(trace.action == estimate.action && trace.visits == estimate.visits)
        require(trace.handMeanValue == estimate.meanBackedValue && trace.settlementCounts == estimate.settlementCounts)
        require(abs(trace.meanValue(hand) - estimate.meanBackedValue) < 1e-12)
    }
}

internal class VisibleV2ActionCalibrationRunner(
    private val repository: Path, private val registry: CardRegistry, private val deck: DeckManifest,
) {
    private fun previous(plan: VisibleV2ActionCalibrationPlan): VisibleV2ExperimentReport {
        val directory = Path.of(plan.previousExperiment.directory)
        val artifacts = ResearchRunArtifacts.loadAndVerify(directory, plan.previousExperiment.researchRunIdentity)
        require(artifacts.artifacts.any { it.relativePath == "report.json" })
        val report = evidenceJson.decodeFromString<VisibleV2ExperimentReport>(Files.readString(directory.resolve("report.json")))
        require(report.researchRunIdentity == plan.previousExperiment.researchRunIdentity)
        val bindings = evidenceJson.decodeFromString<ResearchRunBindings>(Files.readString(directory.resolve("bindings.json")))
        require(bindings.protocol == "visible-v2-calibration-experiment-v1" && bindings.identity == report.researchRunIdentity)
        return report
    }

    /** Same root/seed/budget as the original reference preflight, making observer parity directly checkable. */
    fun preflight(plan: VisibleV2ActionCalibrationPlan, output: Path, workers: Int): PositionBankScreenReport {
        val old = previous(plan)
        val result = PositionBankScreenRunner(repository, registry, deck).run(PositionBankScreenPlan(
            bankDirectory = old.plan.bankDirectory, expectedBankIdentity = old.plan.bankIdentity,
            partition = PositionBankScreenPartition.DEVELOPMENT, mode = PositionBankScreenMode.ACTION_CONDITIONAL_V2_TRACES,
            rootLimit = 1, repetitions = 1, policies = listOf(PositionBankScreenPolicy(old.plan.reference.copy(simulations = 4), old.plan.hand)),
            searchSeedDomain = "visible-v2-calibration-reference-v1"), output, workers)
        require(result.valid && result.rows.size == 1)
        result.rows.forEach { requireVisibleV2TraceRow(it, old.plan.hand) }
        return result
    }

    fun run(plan: VisibleV2ActionCalibrationPlan, output: Path, workers: Int): VisibleV2ActionCalibrationReport {
        val source = ResearchRunProvenance.capture(repository, "third_party/argentum-engine").also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "visible-v2 action-centered calibration")
        require(!Files.exists(destination))
        val old = previous(plan)
        val bank = loadVerifiedRealGamePositionBank(Path.of(old.plan.bankDirectory), old.plan.bankIdentity)
        val referenceInput = SavedRootPolicyInput(Path.of(plan.previousExperiment.directory).resolve("development-reference").toString(),
            old.developmentReferenceIdentity, old.plan.reference.id)
        val targets = visibleV2ReferenceTargets(bank, referenceInput, PositionBankScreenPartition.DEVELOPMENT, old.plan.hand)
        val bindings = ResearchRunBindings(protocol = "visible-v2-action-centered-calibration-v1", material = mapOf(
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "plan" to sha256(evidenceJson.encodeToString(VisibleV2ActionCalibrationPlan.serializer(), plan)),
            "previous-manifest" to researchSha256File(Path.of(plan.previousExperiment.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "worker-threads" to workers.toString(),
            "objective" to "equal-group-centered-action-value-mse-plus-hand-prior-v1",
            "selection" to "development-fixed-trace-reference-regret-then-penalized-loss-first-menu-tie-v1",
        ))
        Files.createDirectories(destination)
        writeJsonAtomically(destination.resolve("plan.json"), plan)
        writeJsonAtomically(destination.resolve("bindings.json"), bindings)
        val cachePath = destination.resolve("development-traces")
        val screen = PositionBankScreenRunner(repository, registry, deck)
        val cache = screen.run(PositionBankScreenPlan(bankDirectory = old.plan.bankDirectory, expectedBankIdentity = old.plan.bankIdentity,
            partition = PositionBankScreenPartition.DEVELOPMENT, mode = PositionBankScreenMode.ACTION_CONDITIONAL_V2_TRACES,
            rootLimit = targets.size, repetitions = 1,
            policies = listOf(PositionBankScreenPolicy(old.plan.selection.copy(id = "hand-v2-trace", particles = plan.cacheParticles,
                simulations = plan.cacheSimulationsPerAction), old.plan.hand)),
            searchSeedDomain = "visible-v2-action-calibration-traces-v1"), cachePath, workers)
        require(cache.valid && cache.selectedRootIds == targets.map { it.rootId })
        val roots = targets.map { target ->
            val row = cache.rows.single { it.rootId == target.rootId }
            requireVisibleV2TraceRow(row, old.plan.hand)
            VisibleV2ActionFitRoot(target.rootId, target.seedGroupId, row.visibleV2ActionTraces, target.actionMeans)
        }
        val heldOutGroups = bank.roots.filter { it.partition == RealGamePositionPartition.VALIDATION }.map { it.seedGroupId }.toSet()
        require(roots.map { it.seedGroupId }.distinct().size >= 2 && roots.none { it.seedGroupId in heldOutGroups })
        val fitted = fitVisibleV2ActionOrdering(roots, old.plan.hand, plan.fit)
        writeJsonAtomically(destination.resolve("fit.json"), fitted)
        writeJsonAtomically(destination.resolve("evaluator.json"), fitted.fitted)
        val restored = evidenceJson.decodeFromString<MonoRedVisibleEvaluatorConfig>(Files.readString(destination.resolve("evaluator.json")))
        require(restored == fitted.fitted && roots.flatMap { it.traces }.all { it.meanValue(restored) == it.meanValue(fitted.fitted) })
        val oldSelection = old.heldOutRegret.plan.baseline
        val unchanged = fitted.fitted == old.plan.hand
        val candidate = if (unchanged) oldSelection else {
            val path = destination.resolve("validation-selection")
            val result = screen.run(PositionBankScreenPlan(bankDirectory = old.plan.bankDirectory, expectedBankIdentity = old.plan.bankIdentity,
                partition = PositionBankScreenPartition.VALIDATION, mode = PositionBankScreenMode.SEARCH,
                rootLimit = old.heldOutRegret.roots, repetitions = old.plan.repetitions,
                policies = listOf(PositionBankScreenPolicy(old.plan.selection.copy(id = "action-fitted-v2", evaluator = fitted.fitted), fitted.fitted)),
                searchSeedDomain = "visible-v2-calibration-selection-v1"), path, workers)
            require(result.valid)
            SavedRootPolicyInput(path.toString(), result.researchRunIdentity, "action-fitted-v2")
        }
        val regret = SavedRootRegret.analyze(old.heldOutRegret.plan.copy(baseline = oldSelection, candidate = candidate))
        val report = VisibleV2ActionCalibrationReport(bindings.identity, source, plan, cache.researchRunIdentity,
            roots.size, roots.map { it.seedGroupId }.distinct().size, fitted, candidate, unchanged, regret)
        writeJsonAtomically(destination.resolve("report.json"), report)
        val artifacts = ResearchRunArtifacts(destination, bindings.identity)
        Files.walk(destination).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach {
            artifacts.register(destination.relativize(it).toString())
        } }; artifacts.finalize()
        ResearchRunArtifacts.loadAndVerify(destination, bindings.identity)
        return report
    }
}

/** A recording observer must leave the matched search's strategic outputs unchanged. */
internal fun requireVisibleV2TraceParity(recorded: PositionBankScreenReport, baseline: PositionBankScreenReport) {
    require(recorded.valid && baseline.valid && recorded.rows.isNotEmpty())
    require(recorded.plan.mode == PositionBankScreenMode.ACTION_CONDITIONAL_V2_TRACES &&
        baseline.plan.mode == PositionBankScreenMode.ACTION_CONDITIONAL)
    require(recorded.plan.copy(mode = baseline.plan.mode) == baseline.plan)
    require(recorded.selectedRootIds == baseline.selectedRootIds && recorded.rows.size == baseline.rows.size)
    recorded.rows.zip(baseline.rows).forEach { (new, old) ->
        require(new.rootId == old.rootId && new.policyId == old.policyId && new.repetition == old.repetition && new.searchSeed == old.searchSeed)
        require(new.rootActionEstimates.size == old.rootActionEstimates.size)
        requireVisibleV2TraceRow(new, recorded.plan.policies.single { it.search.id == new.policyId }.evaluator)
        new.rootActionEstimates.zip(old.rootActionEstimates).forEach { (a, b) ->
            require(a.action == b.action && a.meanBackedValue == b.meanBackedValue && a.visits == b.visits && a.settlementCounts == b.settlementCounts)
        }
    }
}
