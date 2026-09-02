package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.SearchTeacherLeafConfigurations

@ScenarioExecutionTest
class TacticalProofLeafBenchmarkTest {
    @Test
    fun `benchmark covers all five leaf cells and both hidden variants`() {
        val registry = buildRegistry()
        val manifest = loadDeckManifest()
        val case = TacticalProofCatalog.cases.first()
        val factory = TacticalProofScenarioFactory(registry, manifest)
        val oracle = TacticalProofOracle()
        val variants = (1..2).map { hidden ->
            oracle.evaluate(case, factory.create(case, hidden), hidden)
        }
        val accepted = variants.first().acceptedSignatures
        val oracleReport = TacticalProofReport(
            generatedAtUtc = "2026-08-25T00:00:00Z",
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            cases = listOf(
                TacticalProofCaseResult(
                    definition = case,
                    variants = variants,
                    acceptedSignatures = accepted,
                    predicateSignatures = accepted,
                    predicateMatchesOracle = true,
                    hiddenWorldsAgree = true,
                    oraclePassed = true,
                )
            ),
            oraclePassed = true,
            humanReviewStatus = TacticalProofReviewStatus.PENDING,
            humanReviewMatchesOracle = null,
            promotionPassed = false,
            failureReasons = listOf("BLINDED_HUMAN_REVIEW_PENDING"),
        )

        val report = TacticalProofLeafBenchmarkRunner(
            registry = registry,
            manifest = manifest,
            particles = 1,
            simulations = 1,
            maxPolicyDecisions = 1,
        ).run(oracleReport, listOf(case))

        assertTrue(report.completed, report.failureReasons.toString())
        assertEquals(SearchActionSpaceProfile.RULES_EXACT_V1, report.actionSpaceProfile)
        assertEquals(SearchTeacherLeafConfigurations.supported, report.leafResults.map { it.leaf })
        assertTrue(report.leafResults.all { it.totalTrials == 2 && it.completedTrials == 2 })
        val markdown = renderTacticalProofLeafBenchmark(report)
        assertTrue("agree with a finite terminal-position checker" in markdown)
        assertTrue("shares the rules engine and action representation" in markdown)
        assertTrue("does not establish general strategic quality" in markdown)
    }
}
