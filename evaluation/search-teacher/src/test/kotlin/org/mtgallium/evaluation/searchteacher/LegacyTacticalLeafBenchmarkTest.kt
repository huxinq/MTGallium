package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.SearchTeacherLeafConfigurations

@ScenarioExecutionTest
class LegacyTacticalLeafBenchmarkTest {
    @Test
    fun `benchmark covers all five leaf cells at one fixed legacy case`() {
        val case = TacticalBenchmarkCatalog.cases.first()
        val report = LegacyTacticalLeafBenchmarkRunner(
            registry = buildRegistry(),
            manifest = loadDeckManifest(),
            particles = 8,
            simulations = 64,
            maxPolicyDecisions = 1,
        ).run(listOf(case))

        assertTrue(report.completed, report.failureReasons.toString())
        assertEquals(SearchActionSpaceProfile.RULES_EXACT_V1, report.actionSpaceProfile)
        assertEquals(SearchTeacherLeafConfigurations.supported, report.leafResults.map { it.leaf })
        assertEquals(0, report.completeHiddenPairs)
        assertTrue(report.leafResults.all { it.totalTrials == 1 && it.completedTrials == 1 })
        val markdown = renderLegacyTacticalLeafBenchmark(report)
        assertTrue("How five position evaluators choose" in markdown)
        assertTrue("hand-authored immediate lethal or survival predicate" in markdown)
        assertTrue("does not provide an independent source of Magic strategy" in markdown)
    }
}
