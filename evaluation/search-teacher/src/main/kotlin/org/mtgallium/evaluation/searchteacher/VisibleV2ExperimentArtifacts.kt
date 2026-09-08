package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.research.run.ResearchRunProvenance

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
