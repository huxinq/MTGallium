package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlayerChoiceInventoryDiagnosticTest {
    @ScenarioExecutionTest
    @Test
    fun `production run records exact root and internal decision denominators`() {
        val report = M02PlayerChoiceInventoryDiagnostic(
            registry = buildRegistry(),
            manifest = loadDeckManifest(),
            outerCommit = "focused-test-outer",
            argentumCommit = "focused-test-argentum",
        ).run(seed = 20260827L)

        assertEquals(263, report.reachedArenaRootDecisions)
        assertTrue(report.multiAlternativeArenaRootDecisions > 0)
        assertEquals(1, report.searchedArenaRootDecisions)

        val heuristic = report.arenaRuns.single { it.id == "heuristic-mirror-full-game" }
        assertTrue(heuristic.terminal)
        assertEquals(262, heuristic.reachedRootDecisions)
        assertEquals(262, heuristic.replacementDecisionOpportunities)

        val search = report.arenaRuns.single { it.id == "search-versus-heuristic-one-search-decision" }
        assertTrue(search.stoppedAtDeclaredSearchDecisionLimit)
        assertEquals(1, search.reachedRootDecisions)
        assertEquals(1, search.searchedRootDecisions)
        assertEquals(0, search.profileSingletonAutomaticRootDecisions)
        assertEquals(0, search.scriptOrPolicySelectedRootDecisions)
        assertEquals(0, search.compressedPolicySingletonSimulationPasses)
        assertEquals(
            search.policySelectedSimulationDecisions,
            search.opponentModelSimulationDecisions + search.rootRolloutSimulationDecisions +
                search.opponentRolloutSimulationDecisions + search.beliefPrivateChoiceDecisions,
        )
        assertEquals(1, search.byFamily.single { it.family == M02ChoiceFamily.MULLIGAN }.reachedDecisions)
        report.arenaRuns.forEach { run ->
            assertEquals(0, run.profileSingletonAutomaticRootDecisions)
            assertEquals(
                run.reachedRootDecisions,
                run.rulesProvenSingletonRootDecisions + run.profileSingletonAutomaticRootDecisions +
                    run.scriptOrPolicySelectedRootDecisions + run.searchedRootDecisions,
            )
        }
    }

    @Test
    fun `authored inventory covers every pending decision subtype and distinguishes fixed choices`() {
        val probes = authoredDecisionProbes()

        assertEquals(
            setOf(
                "AssignDamageDecision",
                "BatchYesNoDecision",
                "BudgetModalDecision",
                "ChooseColorDecision",
                "ChooseModeDecision",
                "ChooseNumberDecision",
                "ChooseOptionDecision",
                "ChooseReplacementDecision",
                "ChooseTargetsDecision",
                "CombatResolutionDecision",
                "DistributeDecision",
                "OrderObjectsDecision",
                "ReorderLibraryDecision",
                "SearchLibraryDecision",
                "SelectCardsDecision",
                "SelectManaSourcesDecision",
                "SplitPilesDecision",
                "YesNoDecision",
            ),
            probes.map { it.engineDecisionType }.toSet(),
        )
        assertEquals(20, probes.size)
        assertTrue(probes.all { it.enumeratedResponses > 0 })
        assertTrue(probes.all { it.trivialResponseWasEnumerated != false })
        assertTrue(probes.all { it.fastRolloutResponseValidated })
        assertEquals(
            listOf("distribution"),
            probes.filterNot { it.fastRolloutResponseWasEnumerated }.map { it.id },
            "The script omits an explicit zero-valued map entry; the validator accepts the same allocation.",
        )

        listOf(
            "target-with-cancel",
            "yes-no-decline",
            "batch-yes-no-decline",
            "option-with-cancel",
            "library-search-decline",
            "mana-source-with-decline",
        ).forEach { id ->
            assertTrue(requireNotNull(probes.singleOrNull { it.id == id }).cancelOrDeclineResponses > 0, id)
        }

        assertFalse(probes.single { it.id == "damage-assignment" }.trivialResponderSelected)
        assertFalse(probes.single { it.id == "mana-source-with-decline" }.trivialResponderSelected)
        assertEquals(0, probes.count { it.trivialResponderBypassedAlternatives })
        assertEquals(18, probes.count { it.fastRolloutResponderBypassedAlternatives })
        assertTrue(probes.single { it.id == "rules-single-color-control" }.rulesProvenSingleton)
        assertTrue(probes.single { it.id == "rules-single-order-control" }.rulesProvenSingleton)
    }

    @Test
    fun `inventory names every production route and refuses unsupported regret`() {
        val paths = productionPaths()

        assertEquals(
            setOf(
                "semantic-expansion",
                "search-root",
                "search-opponent-and-belief",
                "root-and-opponent-rollout",
                "pregame",
                "arena",
                "live-host",
                "llm-controller",
                "argentum-production-heuristic",
                "argentum-trivial-and-playout-responders",
            ),
            paths.map { it.id }.toSet(),
        )
        assertTrue(paths.all { it.coveredFamilies.isNotEmpty() })

        val probes = authoredDecisionProbes()
        assertTrue(probes.all { it.regretStatus == M02RegretStatus.REFUSED_NO_INDEPENDENT_VALUE_REFERENCE })
        assertTrue(probes.all { !it.regretRefusal.isNullOrBlank() })
    }

    @Test
    fun `mana source fixture measures survival without claiming game-value regret`() {
        val case = manaSourceSurvivalCase()

        assertEquals(4, case.minimalSufficientPayments.size)
        assertEquals(listOf("Rockface Village"), case.fixedAutoPaySuggestion)
        assertEquals(listOf("Mountain A"), case.preservingPayment)
        assertFalse(case.fixedSelectionFutureActionAvailable)
        assertTrue(case.preservingSelectionFutureActionAvailable)
        assertEquals(1, case.scopedActionAvailabilityRegret)
        assertTrue("not game value" in case.limitation.lowercase())
    }
}
