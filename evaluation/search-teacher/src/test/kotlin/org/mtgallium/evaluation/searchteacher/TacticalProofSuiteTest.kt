package org.mtgallium.evaluation.searchteacher

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ScenarioExecutionTest
class TacticalProofSuiteTest {
    private val factory = TacticalProofScenarioFactory(buildRegistry(), loadDeckManifest())

    @Test
    fun `catalog is balanced and every paired hidden variant has identical public meaning`() {
        TacticalProofCatalog.validate()

        TacticalProofCatalog.cases.forEach { case ->
            val left = factory.create(case, hiddenVariant = 1)
            val right = factory.create(case, hiddenVariant = 2)
            val leftState = left.informationState(case.rootPlayer)
            val rightState = right.informationState(case.rootPlayer)
            val leftExpansion = left.expandChoices(2_048)
            val rightExpansion = right.expandChoices(2_048)

            assertTrue(leftExpansion.isExhaustive, case.id)
            assertTrue(rightExpansion.isExhaustive, case.id)
            assertTrue(leftExpansion.proposalVersion.endsWith("rules-exact-v1"), case.id)
            assertTrue(rightExpansion.proposalVersion.endsWith("rules-exact-v1"), case.id)
            assertEquals(leftState.observation, rightState.observation, case.id)
            assertEquals(leftState.history, rightState.history, case.id)
            assertEquals(leftState.knowledge, rightState.knowledge, case.id)
            assertEquals(leftState.informationStateDigest, rightState.informationStateDigest, case.id)
            assertEquals(
                leftExpansion.candidates.map { it.signature }.toSet(),
                rightExpansion.candidates.map { it.signature }.toSet(),
                case.id,
            )
            val leftExpected = leftExpansion.candidates
                .filter { case.acceptedPattern.matches(it, leftState) }
                .map { it.signature }
                .toSet()
            val rightExpected = rightExpansion.candidates
                .filter { case.acceptedPattern.matches(it, rightState) }
                .map { it.signature }
                .toSet()
            assertTrue(leftExpected.isNotEmpty(), "${case.id} predicate matched no legal root action")
            assertEquals(leftExpected, rightExpected, "${case.id} predicate was hidden-world dependent")
            if (case.category == TacticalProofCategory.BLOCK) {
                assertEquals(
                    left.expandChoices(16).candidates.map { it.signature },
                    right.expandChoices(16).candidates.map { it.signature },
                    "${case.id} structured prefix disagrees across hidden worlds",
                )
            }
        }
    }

    @Test
    fun `stack and combat captures have real semantic provenance`() {
        TacticalProofCatalog.cases.forEach { case ->
            val state = factory.create(case, hiddenVariant = 1).informationState(case.rootPlayer)
            when (case.category) {
                TacticalProofCategory.STACK_RACE -> {
                    assertTrue(state.observation.stack.isNotEmpty(), case.id)
                    assertTrue(state.history.any { it.kind.name == "PRIORITY_PASS" }, case.id)
                    assertTrue(state.history.any { it.payload["operationFamily"]?.toString() == "\"MANA_ABILITY\"" }, case.id)
                }
                TacticalProofCategory.BLOCK -> {
                    assertEquals("DECLARE_BLOCKERS", state.observation.step, case.id)
                    assertTrue(state.observation.combat?.attackers?.isNotEmpty() == true, case.id)
                }
                else -> Unit
            }
        }
    }

    @Test
    fun `stack-race oracle proves a unique visible lethal in both hidden worlds`() {
        val case = TacticalProofCatalog.cases.first { it.id == "stack-race-01" }
        val oracle = TacticalProofOracle()
        val results = (1..2).map { variant -> oracle.evaluate(case, factory.create(case, variant), variant) }

        assertEquals(results[0].acceptedSignatures, results[1].acceptedSignatures)
        assertEquals(1, results[0].acceptedSignatures.size)
        val accepted = results[0].actionValues.single { it.choice.signature in results[0].acceptedSignatures }
        assertEquals("Shock", accepted.choice.display.sourceName)
        assertTrue("Player 1" in accepted.choice.display.targetNames)
        assertEquals(TacticalTerminalOutcome.WIN, accepted.outcome)
    }

    @Test
    fun `nonterminal authored cutoffs are diagnostic and never machine certified`() {
        val report = TacticalProofRunner(buildRegistry(), loadDeckManifest()).run().report
        report.cases.filter { it.definition.expiry != TacticalProofExpiry.TERMINAL }.forEach { result ->
            assertEquals(TacticalCertificationStatus.NOT_REQUESTED, result.certificationStatus, result.definition.id)
            assertEquals(TacticalEvidenceAuthority.DIAGNOSTIC, result.authority, result.definition.id)
            assertFalse(result.oraclePassed, result.definition.id)
        }
    }

    @Test
    fun `structured block proposer is prefix stable on exhaustive small boards`() {
        TacticalProofCatalog.cases.filter { it.category == TacticalProofCategory.BLOCK }.forEach { case ->
            val world = factory.create(case, 1)
            val full = world.expandChoices(2_048)
            val prefix = world.expandChoices(16)
            assertEquals(full.candidates.take(prefix.candidates.size).map { it.signature }, prefix.candidates.map { it.signature })
        }
    }

    @Test
    fun `large Limited-style block proposal stream is prefix-stable through widening`() {
        val limits = listOf(64, 128, 256, 512)
        lateinit var expansions: List<org.mtgallium.agent.infoset.core.PolicyExpansion>
        val elapsed = measureTimeMillis {
            val world = factory.createBlockProposalStress()
            expansions = limits.map(world::expandChoices)
        }

        expansions.forEachIndexed { index, expansion ->
            assertEquals(limits[index], expansion.candidates.size)
            assertFalse(expansion.isExhaustive)
            assertTrue(expansion.candidates.all {
                it.operationFamily == org.mtgallium.agent.infoset.core.SemanticOperationFamily.DECLARE_BLOCKERS
            })
            if (index > 0) {
                assertEquals(
                    expansions[index - 1].candidates.map { it.signature },
                    expansion.candidates.take(limits[index - 1]).map { it.signature },
                )
            }
        }
        assertTrue(elapsed < 20_000, "Structured 6x6 block widening took ${elapsed}ms")
    }
}
