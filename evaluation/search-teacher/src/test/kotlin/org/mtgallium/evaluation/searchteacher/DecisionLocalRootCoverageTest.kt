package org.mtgallium.evaluation.searchteacher

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli

@org.junit.jupiter.api.Tag("public-source")
class DecisionLocalRootCoverageTest {
    @Test
    fun `coverage assigns independent whole lineages before outcomes and excludes old indices`() {
        val assignments = coverageAssignments()
        assertEquals((50 until 300).toList(), assignments.map { it.pairIndex })
        assertEquals(200, assignments.count { it.split == DecisionLocalSplit.TRAIN })
        assertEquals(50, assignments.count { it.split == DecisionLocalSplit.VALIDATION })
        assertEquals(250, assignments.map { it.seed }.toSet().size)
        val oldSeeds = (0 until 50).map { ComponentSeeds.derive(ROOT_COVERAGE_BASE_SEED, it, "learned-leaf-pilot-library-orders") }.toSet()
        assertTrue(assignments.none { it.seed in oldSeeds })
        assertEquals(assignments, coverageAssignments())
    }

    @Test
    fun `selected leg preserves control seat and historical game seed domain`() {
        val (controlParameters, learnedParameters) = LearnedLeafPilotRoster.parameters()
        val control = ArenaPolicySpec(LEARNED_LEAF_PILOT_CONTROL_ID, ArenaPolicyKind.SEARCH, parameters = controlParameters)
        val learned = ArenaPolicySpec(LEARNED_LEAF_PILOT_TREATMENT_ID, ArenaPolicyKind.SEARCH, parameters = learnedParameters)
        for (a in coverageAssignments()) {
            val descriptor = tournamentDescriptor(control, learned, a.pairIndex, a.leg)
            assertEquals(control.id, if (a.pairIndex % 2 == 0) descriptor.p0PolicyId else descriptor.p1PolicyId)
            assertEquals(learned.id, if (a.pairIndex % 2 == 0) descriptor.p1PolicyId else descriptor.p0PolicyId)
            assertTrue(descriptor.gameId.endsWith("-${a.pairIndex}-${if (a.leg == 0) "a" else "b"}"))
            assertEquals(ComponentSeeds.derive(ROOT_COVERAGE_BASE_SEED, a.pairIndex, "learned-leaf-pilot-library-orders"), a.seed)
        }
    }

    @Test
    fun `expanded fit cannot substitute evaluation or duplicate roots for new TRAIN roots`() {
        val old = (0 until 34).map { root(it) }
        val fresh = coverageAssignments().filter { it.split == DecisionLocalSplit.TRAIN }.map { root(it.pairIndex) }
        val model = fitExpandedCoverageModel(old, fresh)
        assertTrue(model.score(fresh.first().candidates[0]) > model.score(fresh.first().candidates[1]))
        val evaluation = coverageAssignments().first { it.split == DecisionLocalSplit.VALIDATION }
        assertFailsWith<IllegalArgumentException> { fitExpandedCoverageModel(old, fresh.dropLast(1) + root(evaluation.pairIndex)) }
        assertFailsWith<IllegalArgumentException> { fitExpandedCoverageModel(old, fresh.dropLast(1) + fresh.first()) }
        assertFailsWith<IllegalArgumentException> { fitExpandedCoverageModel(old, fresh.map { it.copy(split = DecisionLocalSplit.VALIDATION) }) }
    }

    @Test
    fun `failed tasks stop queued work without turning failure into an outcome`() {
        val attempted = AtomicInteger()
        val error = assertFailsWith<IllegalStateException> {
            coverageParallel(100, 4) {
                attempted.incrementAndGet()
                error("unsupported root witness")
            }
        }
        assertTrue(error.message.orEmpty().contains("unsupported root witness"))
        assertTrue(attempted.get() in 1..4)
    }

    @Test
    fun `coverage CLI requires historical inputs and admits no challenge panel`() {
        val args = arrayOf("--suite", "decision-local-root-coverage-preflight", "--coverage-parent", "parent",
            "--fixed-root-pilot", "pilot", "--outcome-corpus", "corpus", "--fixed-root-gate", "gate",
            "--deck-manifest", "deck.json", "--threads", "4", "--output", "output")
        assertEquals(4, SearchTeacherCli.parse(args).threads)
        assertFailsWith<IllegalArgumentException> { SearchTeacherCli.parse(args + arrayOf("--challenge-manifest", "test-panel")) }
        assertFailsWith<IllegalArgumentException> { SearchTeacherCli.parse(args.take(4).toTypedArray()) }
    }

    private fun root(index: Int) = DecisionLocalRootEvidence(
        rootId = "root-$index", split = DecisionLocalSplit.TRAIN, pairIndex = index,
        decisionFamily = "CAST_SPELL", phase = "PRECOMBAT_MAIN", turnNumber = 2, rootActor = "p0",
        representedKnowledgeCategory = "represented-history", candidateFamilyDigest = "family", productionScheduleDigest = "schedule",
        primaryReplicates = 32, independentReplicates = 0,
        candidates = listOf(candidate("a", 1.0), candidate("b", -1.0)),
    )

    private fun candidate(signature: String, value: Double) = DecisionLocalCandidateEvidence(
        signature, 64, 64, 0, 0.0, mapOf("x" to value), "feature-$signature", -value, 0.0,
        List(32) { value }, emptyList(), 32, 1.0,
    )
}
