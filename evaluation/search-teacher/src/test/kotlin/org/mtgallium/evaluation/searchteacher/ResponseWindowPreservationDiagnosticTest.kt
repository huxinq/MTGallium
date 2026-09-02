package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

class ResponseWindowPreservationDiagnosticTest {
    @Test
    fun `frozen main deck source denominator separates spells from non-mana abilities`() {
        val cells = poolSourceFamilies(buildRegistry(), loadDeckManifest())

        assertEquals(16, cells.size)
        assertEquals(12, cells.count { it.submissionFamily == SemanticOperationFamily.CAST_SPELL })
        assertEquals(4, cells.count { it.submissionFamily == SemanticOperationFamily.ACTIVATE_ABILITY })
        assertEquals(
            setOf("Burnout Bashtronaut", "Hired Claw", "Rockface Village", "Soulstone Sanctuary"),
            cells.filter { it.submissionFamily == SemanticOperationFamily.ACTIVATE_ABILITY }
                .map { it.sourceCardName }.toSet(),
        )
        assertTrue(cells.none { it.sourceCardName == "Mountain" })
    }

    @ScenarioExecutionTest
    @Test
    fun `targeted production steps retain both players and nested response roots`() {
        val report = focusedReport(
            pairedCases = listOf("immediate-01", "within-turn-03"),
            naturalGames = 1,
        )
        val targeted = report.targetedTraces
        val targetedWindows = targeted.flatMap(M01SubmissionTrace::windows)

        assertEquals(setOf("p0", "p1"), targeted.map(M01SubmissionTrace::submittedBy).toSet())
        assertEquals(setOf("p0", "p1"), targetedWindows.map(M01ResponseWindow::actingPlayer).toSet())
        assertTrue(targeted.maxOf(M01SubmissionTrace::maximumObservedStackDepth) >= 3)
        assertTrue(targeted.any(M01SubmissionTrace::reachedPendingDecision))
        assertTrue(targeted.any(M01SubmissionTrace::reachedPriorityWindow))
        assertTrue(targeted.any(M01SubmissionTrace::reachedRulesSingletonWindow))
        assertTrue(targeted.any(M01SubmissionTrace::reachedMultiAlternativeWindow))
        assertTrue(targeted.all(M01SubmissionTrace::reachedThroughProductionStep))

        val natural = report.naturalArenaRuns.single()
        assertTrue(natural.decisions > 0)
        assertTrue(natural.submittedSpellsOrAbilities > 0)
        assertTrue(natural.reachedResponseWindows > 0)
        assertTrue(natural.traces.all(M01SubmissionTrace::reachedThroughProductionStep))
    }

    @Test
    fun `diagnostic wrapper never suppresses an engine pending decision`() {
        val registry = buildRegistry()
        val manifest = loadDeckManifest()
        val case = TacticalHorizonCatalog.cases.single { it.id == "within-turn-06" }
        val world = TacticalHorizonScenarioFactory(registry, manifest).create(case)
        val actor = assertNotNull(world.actorToAct())
        assertNotNull(world.informationState(actor).observation.pendingDecision)
        val audit = MutableM01SuppressionAudit()
        val wrapped = M01DiagnosticNoResponseWorld(world, audit)
        val expansion = wrapped.expandChoices(2_048)

        assertTrue(expansion.candidates.isNotEmpty())
        val result = wrapped.step(expansion.candidates.first())

        assertTrue(result.accepted)
        assertEquals(0, audit.snapshot().suppressedPriorityTransitionExecutions)
        assertEquals(0, audit.snapshot().refusals)
    }

    @ScenarioExecutionTest
    @Test
    fun `paired comparison binds inputs and excludes invalid dispositions from changed-choice denominator`() {
        val report = focusedReport(
            pairedCases = listOf("immediate-01", "immediate-04", "within-turn-03"),
            naturalGames = 1,
        )

        assertEquals(3, report.totalPairedComparisons)
        assertTrue(report.pairedComparisons.all(M01PairedRootComparison::pairedInputsAndSeedsMatched))
        assertTrue(report.pairedComparisons.all(M01PairedRootComparison::stopDispositionMatched))
        assertTrue(report.pairedComparisons.all(M01PairedRootComparison::replacementDispositionMatched))
        assertTrue(report.pairedComparisons.all { it.preserved.acceptedParticles == 2 })
        assertTrue(report.pairedComparisons.all { it.diagnosticNoResponse.acceptedParticles == 2 })
        assertTrue(report.pairedComparisons.all {
            assertNotNull(it.diagnosticNoResponse.suppression).refusals == 0
        })
        assertTrue(report.pairedComparisons.any {
            assertNotNull(it.diagnosticNoResponse.suppression)
                .suppressedPriorityTransitionExecutions > 0
        })
        report.pairedComparisons.forEach { pair ->
            val preservedRootValue = assertNotNull(pair.preserved.evaluatedSystemRootValue)
            val suppressedRootValue = assertNotNull(pair.diagnosticNoResponse.evaluatedSystemRootValue)
            assertEquals(
                suppressedRootValue - preservedRootValue,
                assertNotNull(pair.rawEvaluatedSystemRootValueDelta),
                absoluteTolerance = 1e-12,
            )
            if (pair.comparisonDisposition == M01ComparisonDisposition.EVIDENCE_ELIGIBLE) {
                assertEquals(pair.rawChosenRootActionChanged, pair.eligibleChosenRootActionChanged)
                assertEquals(
                    pair.rawEvaluatedSystemRootValueDelta,
                    pair.eligibleEvaluatedSystemRootValueDelta,
                )
            } else {
                assertNull(pair.eligibleChosenRootActionChanged)
                assertNull(pair.eligibleEvaluatedSystemRootValueDelta)
            }
        }
        assertEquals(
            report.changedChosenRootActions,
            report.pairedComparisons.count { it.eligibleChosenRootActionChanged == true },
        )
        assertEquals(
            report.eligiblePairedComparisons,
            report.pairedComparisons.count {
                it.comparisonDisposition == M01ComparisonDisposition.EVIDENCE_ELIGIBLE
            },
        )
        assertFalse(report.permittedConclusion.contains("every Magic response", ignoreCase = true))
    }

    @ScenarioExecutionTest
    @Test
    fun `generated narrative leads with denominators and refuses strategic regret`() {
        val report = focusedReport(listOf("immediate-01"), naturalGames = 1)
        val markdown = renderM01ResponseWindowReport(report)

        assertTrue(markdown.startsWith("# Engine-emitted response windows"))
        assertTrue("${report.eligiblePairedComparisons}/${report.totalPairedComparisons}" in markdown)
        assertTrue("raw outputs of the evaluated search leaf" in markdown)
        assertTrue("strategic value and regret are refused" in markdown)
        assertTrue("Eligible Δ N−P" in markdown)
        assertTrue("diagnostic suppression, not legal equivalence" in markdown)
        assertTrue("Limits on the conclusion" in markdown)
    }

    private fun focusedReport(
        pairedCases: List<String>,
        naturalGames: Int,
    ): M01ResponseWindowReport = M01ResponseWindowDiagnostic(
        registry = buildRegistry(),
        manifest = loadDeckManifest(),
        outerCommit = "focused-test-outer",
        argentumCommit = "focused-test-argentum",
        particles = 2,
        simulations = 8,
        maximumPolicyDecisions = 12,
        pairedCaseIds = pairedCases,
        naturalGameCount = naturalGames,
    ).run(baseSeed = 20260827L)
}
