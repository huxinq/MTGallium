package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal data class VisibleV2ExperimentPlan(
    val bankDirectory: String, val bankIdentity: String,
    val selection: SearchTeacherCalibrationPolicy,
    val reference: SearchTeacherCalibrationPolicy,
    val repetitions: Int = 2,
    val hand: MonoRedVisibleEvaluatorConfig = MonoRedVisibleEvaluatorConfig(),
    val fit: VisibleV2FitConfig = VisibleV2FitConfig(),
) {
    init {
        require(repetitions >= 2)
        require(selection.rootCloningFit == null && reference.rootCloningFit == null)
        require(selection.evaluator == null || selection.evaluator == hand)
        require(reference.copy(id = selection.id, particles = selection.particles, simulations = selection.simulations) == selection) {
            "Reference and selection may differ only in id and search/particle budget"
        }
    }
}

@Serializable
internal data class VisibleV2ExperimentReport(
    val researchRunIdentity: String, val source: ResearchRunProvenance, val plan: VisibleV2ExperimentPlan,
    val developmentReferenceIdentity: String, val fitIdentity: String,
    val validationReferenceIdentity: String, val validationSelectionIdentity: String,
    val heldOutRegret: SavedRootRegretReport, val heldOutValues: VisibleV2HeldOutValueReport,
    val interpretation: String = "One frozen existing-form v2 fit; development reference targets precede fitting, and fitted coefficients precede held-out reference/search evaluation. Paired action regret on whole held-out seed groups is primary. Reference backups retain their explicit policy and terminal/heuristic settlement meaning; no gameplay result or production promotion.",
)

internal class VisibleV2CalibrationExperimentRunner(
    private val repository: Path, private val registry: CardRegistry, private val deck: DeckManifest,
) {
    /** Cheap engine witness for the same reference continuation and complete forced-action menu. */
    fun preflight(plan: VisibleV2ExperimentPlan, output: Path, workers: Int): PositionBankScreenReport {
        val report = PositionBankScreenRunner(repository, registry, deck).run(referencePlan(plan,
            PositionBankScreenPartition.DEVELOPMENT, 1).copy(repetitions = 1,
            policies = listOf(PositionBankScreenPolicy(plan.reference.copy(simulations = 4), plan.hand))), output, workers)
        require(report.valid && report.rows.size == 1 && report.rows.single().disposition == PositionBankScreenDisposition.ACTION_CONDITIONAL)
        return report
    }

    fun run(plan: VisibleV2ExperimentPlan, output: Path, workers: Int): VisibleV2ExperimentReport {
        val source = ResearchRunProvenance.capture(repository, "third_party/argentum-engine").also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "visible-v2 calibration experiment")
        require(!Files.exists(destination))
        val bank = loadVerifiedRealGamePositionBank(Path.of(plan.bankDirectory), plan.bankIdentity)
        fun roots(partition: RealGamePositionPartition) = bank.roots.filter { it.partition == partition }
        require(roots(RealGamePositionPartition.DEVELOPMENT).map { it.seedGroupId }.distinct().size >= 2)
        require(roots(RealGamePositionPartition.VALIDATION).map { it.seedGroupId }.distinct().size >= 2)
        val bindings = ResearchRunBindings(protocol = "visible-v2-calibration-experiment-v1", material = mapOf(
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "plan" to sha256(evidenceJson.encodeToString(VisibleV2ExperimentPlan.serializer(), plan)),
            "bank-manifest" to researchSha256File(Path.of(plan.bankDirectory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "worker-threads" to workers.toString(), "order" to "development-reference-fit-validation-reference-validation-search-regret-v1",
        ))
        Files.createDirectories(destination)
        writeJsonAtomically(destination.resolve("plan.json"), plan)
        writeJsonAtomically(destination.resolve("bindings.json"), bindings)
        val screen = PositionBankScreenRunner(repository, registry, deck)
        val developmentPath = destination.resolve("development-reference")
        val development = screen.run(referencePlan(plan, PositionBankScreenPartition.DEVELOPMENT,
            roots(RealGamePositionPartition.DEVELOPMENT).size), developmentPath, workers)
        require(development.valid)
        val fitPath = destination.resolve("fit")
        val fitted = runVisibleV2Calibration(repository, VisibleV2CalibrationPlan(plan.bankDirectory, plan.bankIdentity,
            SavedRootPolicyInput(developmentPath.toString(), development.researchRunIdentity, plan.reference.id), plan.hand, plan.fit), fitPath)
        val validationPath = destination.resolve("validation-reference")
        val validation = screen.run(referencePlan(plan, PositionBankScreenPartition.VALIDATION,
            roots(RealGamePositionPartition.VALIDATION).size), validationPath, workers)
        require(validation.valid)
        val selectionPath = destination.resolve("validation-selection")
        val selection = screen.run(PositionBankScreenPlan(bankDirectory = plan.bankDirectory, expectedBankIdentity = plan.bankIdentity,
            partition = PositionBankScreenPartition.VALIDATION, mode = PositionBankScreenMode.SEARCH,
            rootLimit = roots(RealGamePositionPartition.VALIDATION).size, repetitions = plan.repetitions,
            policies = listOf(PositionBankScreenPolicy(plan.selection.copy(id = "hand-v2", evaluator = plan.hand), plan.hand),
                PositionBankScreenPolicy(plan.selection.copy(id = "fitted-v2", evaluator = fitted.fit.fitted), fitted.fit.fitted)),
            searchSeedDomain = "visible-v2-calibration-selection-v1"), selectionPath, workers)
        require(selection.valid)
        val referenceInput = SavedRootPolicyInput(validationPath.toString(), validation.researchRunIdentity, plan.reference.id)
        val regret = SavedRootRegret.analyze(SavedRootRegretPlan(plan.bankDirectory, plan.bankIdentity, referenceInput,
            SavedRootPolicyInput(selectionPath.toString(), selection.researchRunIdentity, "hand-v2"),
            SavedRootPolicyInput(selectionPath.toString(), selection.researchRunIdentity, "fitted-v2")))
        val values = evaluateVisibleV2HeldOutValues(fitPath, fitted.researchRunIdentity, referenceInput)
        val report = VisibleV2ExperimentReport(bindings.identity, source, plan, development.researchRunIdentity,
            fitted.researchRunIdentity, validation.researchRunIdentity, selection.researchRunIdentity, regret, values)
        writeJsonAtomically(destination.resolve("report.json"), report)
        val artifacts = ResearchRunArtifacts(destination, bindings.identity)
        Files.walk(destination).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach {
            artifacts.register(destination.relativize(it).toString())
        } }
        artifacts.finalize()
        ResearchRunArtifacts.loadAndVerify(destination, bindings.identity)
        return report
    }

    private fun referencePlan(plan: VisibleV2ExperimentPlan, partition: PositionBankScreenPartition, roots: Int) =
        PositionBankScreenPlan(bankDirectory = plan.bankDirectory, expectedBankIdentity = plan.bankIdentity,
            partition = partition, mode = PositionBankScreenMode.ACTION_CONDITIONAL, rootLimit = roots,
            repetitions = plan.repetitions, policies = listOf(PositionBankScreenPolicy(plan.reference, plan.hand)),
            searchSeedDomain = "visible-v2-calibration-reference-v1")
}
