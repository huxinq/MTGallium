package org.mtgallium.evaluation.searchteacher

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli

@org.junit.jupiter.api.Tag("public-source")
class DecisionLocalRootCoverageTest {
    @Test
    fun `performance parity ignores clocks but preserves labels and search values`() {
        val r = root(2)
        assertEquals(coveragePerformanceRootParity(r), coveragePerformanceRootParity(r.copy(
            candidates = r.candidates.map { it.copy(continuationRuntimeMillis = 999.0) })))
        assertNotEquals(coveragePerformanceRootParity(r), coveragePerformanceRootParity(r.copy(
            candidates = r.candidates.map { it.copy(primaryTerminalPayoffs = List(32) { 0.0 }) })))
        val parameters = LearnedLeafPilotRoster.parameters().first
        val detail = ArenaSearchDecisionDiagnostic(0, 1, "BEGINNING", "UPKEEP", 5.0,
            org.mtgallium.agent.infoset.core.InformationSetSearchDiagnostics(
                64, 8, 1, 1, 1, 0, 0, "opponent", parameters.leaf, evaluatorNanos = 10), rootValue = 0.5)
        val game = GameRunResult(gameId = "parity", seed = 1, p0Policy = ArenaPolicyKind.SEARCH,
            p1Policy = ArenaPolicyKind.SEARCH, winner = null, terminal = false, disposition = GameRunDisposition.STOPPED_LIMIT,
            decisions = 1, searchSeat = null, searchScore = null, illegalResponses = 0, fallbacks = 0, stepLimit = true,
            elapsedMillis = 10.0, searchLatenciesMillis = listOf(5.0), seatDiagnostics = mapOf(
                "p0" to ArenaSeatDiagnostics("control", searchDecisions = 1, searchLatenciesMillis = listOf(5.0), searchDecisionsDetail = listOf(detail))))
        val changedClock = game.copy(elapsedMillis = 99.0, searchLatenciesMillis = listOf(88.0),
            seatDiagnostics = mapOf("p0" to game.seatDiagnostics.getValue("p0").copy(searchLatenciesMillis = listOf(88.0),
                searchDecisionsDetail = listOf(detail.copy(latencyMillis = 88.0, searchDiagnostics = detail.searchDiagnostics.copy(evaluatorNanos = 99))))))
        assertEquals(coveragePerformanceGameParity(game), coveragePerformanceGameParity(changedClock))
        assertNotEquals(coveragePerformanceGameParity(game), coveragePerformanceGameParity(game.copy(
            seatDiagnostics = mapOf("p0" to game.seatDiagnostics.getValue("p0").copy(
                searchDecisionsDetail = listOf(detail.copy(rootValue = -0.5)))))))
        assertEquals(listOf(2, 25, 43), coveragePerformanceIndices)
    }

    @Test
    fun `arena fallback needs no private pilot fixture and retains control configuration`() {
        val control = LearnedLeafPilotRoster.parameters().first
        val profile = coverageArenaProfile("synthetic-source")
        assertEquals(control.particles, profile.particles)
        assertEquals(control.simulations, profile.simulations)
        assertEquals(control.leaf, profile.leaf)
        assertEquals(control.actionSpaceProfile, profile.actionSpaceProfile)
        assertEquals(control.maxPolicyDecisions, profile.maxPolicyDecisions)
        assertEquals(control.explorationConstant, profile.explorationConstant)
        assertEquals("synthetic-source", profile.outerCommit)
        assertEquals(ROOT_COVERAGE_ENGINE, profile.argentumCommit)
    }

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
