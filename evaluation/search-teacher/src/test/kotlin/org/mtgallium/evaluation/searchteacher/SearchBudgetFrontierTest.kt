package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.infoset.core.ComponentSeeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchBudgetFrontierTest {
    @Test
    fun `frontier roster differs only in simulations`() {
        val (control, treatment) = SearchBudgetFrontierRoster.policies()
        val controlParameters = control.effectiveParameters(20260902L)
        val treatmentParameters = treatment.effectiveParameters(20260902L)

        assertEquals(SEARCH_BUDGET_FRONTIER_CONTROL_ID, control.id)
        assertEquals(SEARCH_BUDGET_FRONTIER_TREATMENT_ID, treatment.id)
        assertEquals(64, controlParameters.simulations)
        assertEquals(32, treatmentParameters.simulations)
        assertEquals(controlParameters, treatmentParameters.copy(simulations = 64))
        assertEquals(controlParameters.actionSpaceProfile, treatmentParameters.actionSpaceProfile)
        assertEquals(controlParameters.leaf, treatmentParameters.leaf)
        assertFalse(controlParameters.policyCompression.enabled)
        assertFalse(treatmentParameters.policyCompression.enabled)
        assertFalse(controlParameters.searchReuse.enabled)
        assertFalse(treatmentParameters.searchReuse.enabled)
    }

    @Test
    fun `frontier decision preserves cost before strength interpretation`() {
        assertEquals(
            SearchBudgetFrontierDecision.COST_HYPOTHESIS_NOT_SUPPORTED,
            searchBudgetFrontierDecision(true, 0.29, 0.6, 0.7),
        )
        assertEquals(
            SearchBudgetFrontierDecision.STRENGTH_COST_TRADEOFF_SUPPORTED,
            searchBudgetFrontierDecision(true, 0.30, 0.4, 0.49),
        )
        assertEquals(
            SearchBudgetFrontierDecision.STRENGTH_RESULT_AMBIGUOUS,
            searchBudgetFrontierDecision(true, 0.30, 0.48, 0.60),
        )
        assertEquals(
            SearchBudgetFrontierDecision.BUDGET_SLACK_SUPPORTED,
            searchBudgetFrontierDecision(true, 0.30, 0.50, 0.60),
        )
        assertEquals(
            SearchBudgetFrontierDecision.PILOT_INVALID,
            searchBudgetFrontierDecision(false, 0.50, 1.0, 1.0),
        )
    }

    @Test
    fun `scientific identity binds budget policies but not worker metadata`() {
        val material = SearchBudgetFrontierRoster.policies().let { (control, treatment) ->
            mapOf(
                "control" to control.effectiveParameters(20260902L).simulations.toString(),
                "treatment" to treatment.effectiveParameters(20260902L).simulations.toString(),
            )
        }

        assertEquals("64", material["control"])
        assertEquals("32", material["treatment"])
        assertFalse(material.containsKey("worker-threads"))
        assertTrue(SEARCH_BUDGET_FRONTIER_MATERIAL_LATENCY_REDUCTION == 0.30)
    }

    @Test
    fun `extension schedule is exactly disjoint and final classification is predeclared`() {
        val original = (0 until SEARCH_BUDGET_FRONTIER_REQUIRED_PAIRS).map {
            ComponentSeeds.derive(20260902L, it, "search-budget-frontier-library-orders")
        }.toSet()
        val extension = (SEARCH_BUDGET_FRONTIER_EXTENSION_START until
            SEARCH_BUDGET_FRONTIER_EXTENSION_START + SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS).map {
            ComponentSeeds.derive(20260902L, it, "search-budget-frontier-library-orders")
        }
        assertEquals(SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS, extension.distinct().size)
        assertTrue(extension.none(original::contains))

        fun summary(lower: Double, upper: Double) = SearchBudgetFrontierTrancheSummary(
            150, 150, 300, 0, 0, 0, 0, 0, 0.5, lower, upper, emptyList(), null,
        )
        assertEquals(SearchBudgetFrontierExtensionDecision.CLEAR_STRENGTH_DEFICIT, extensionDecision(true, summary(.40, .49)))
        assertEquals(SearchBudgetFrontierExtensionDecision.BUDGET_SLACK_SUPPORTED, extensionDecision(true, summary(.50, .60)))
        assertEquals(SearchBudgetFrontierExtensionDecision.RESIDUAL_NEAR_PARITY_AMBIGUITY, extensionDecision(true, summary(.45, .55)))
        assertEquals(SearchBudgetFrontierExtensionDecision.EXTENSION_INVALID, extensionDecision(false, summary(.50, .60)))
    }
}
