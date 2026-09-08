package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind

@Tag("public-source")
class DirectGameplayDecisionCostTest {
    @Test fun `direct work can fail cost ceiling despite cheaper searched decisions`() {
        val game = game("direct-work", listOf(100.0), listOf(20.0, 100.0))
        assertTrue(game.seatDiagnostics.getValue("p1").searchLatenciesMillis.sum() <
            game.seatDiagnostics.getValue("p0").searchLatenciesMillis.sum())
        val cost = cost(pair(0, game))
        assertEquals(1.2, cost.candidateControlRatio)
        assertFalse(cost.costGatePassed)
        assertEquals(120.0, cost.candidate.totalDecisionComputationMillis)
        assertEquals(120.0, cost.candidate.decisionComputationMillisPerGame)
        assertEquals(2L, cost.candidate.measuredAttempts)
        assertTrue(cost.issues.isEmpty())
        assertEquals(cost, evidenceJson.decodeFromString<DirectGameplayDecisionCost>(evidenceJson.encodeToString(cost)))
    }

    @Test fun `cumulative cost includes invalid stopped games and dispatched overshoot`() {
        val first = pair(0, game("first", listOf(100.0), listOf(50.0)))
        val stopped = pair(1, game("stopped", listOf(100.0), listOf(250.0), stopped = true)).copy(
            valid = false, invalidationReasons = listOf("stopped"), treatmentPoints = null)
        val overshoot = pair(2, game("overshoot", listOf(100.0), listOf(150.0), swap = true))
        assertTrue(cost(first).costGatePassed)
        val cumulative = cost(first, stopped, overshoot)
        assertEquals(3, cumulative.control.executedGames)
        assertEquals(3, cumulative.candidate.measuredGames)
        assertEquals(450.0, cumulative.candidate.totalDecisionComputationMillis)
        assertEquals(150.0, cumulative.candidate.decisionComputationMillisPerGame)
        assertEquals(listOf("first", "stopped", "overshoot"), cumulative.candidate.games.map { it.gameId })
        assertEquals("p0", cumulative.candidate.games.last().seat)
        assertEquals(1.5, cumulative.candidateControlRatio)
        assertFalse(cumulative.costGatePassed)
        assertTrue(cumulative.issues.isEmpty())
    }

    @Test fun `unknown malformed and mismatched measurements refuse the gate`() {
        val valid = game("valid", listOf(100.0), listOf(50.0))
        val candidate = valid.seatDiagnostics.getValue("p1")
        fun replaced(seat: ArenaSeatDiagnostics) = valid.copy(seatDiagnostics = valid.seatDiagnostics + ("p1" to seat))
        val cases = listOf(
            replaced(candidate.copy(decisionComputationMillis = null)) to DirectGameplayDecisionCostIssueKind.MISSING_MEASUREMENT,
            replaced(candidate.copy(decisionComputationMillis = listOf(-1.0))) to DirectGameplayDecisionCostIssueKind.INVALID_MEASUREMENT,
            replaced(candidate.copy(decisionComputationMillis = listOf(Double.NaN))) to DirectGameplayDecisionCostIssueKind.INVALID_MEASUREMENT,
            replaced(candidate.copy(decisionComputationMillis = listOf(Double.POSITIVE_INFINITY))) to DirectGameplayDecisionCostIssueKind.INVALID_MEASUREMENT,
            replaced(candidate.copy(decisionComputationMillis = listOf(Double.MAX_VALUE, Double.MAX_VALUE))) to DirectGameplayDecisionCostIssueKind.NON_FINITE_TOTAL,
            valid.copy(seatDiagnostics = valid.seatDiagnostics - "p1") to DirectGameplayDecisionCostIssueKind.MISSING_SEAT_DIAGNOSTICS,
            valid.copy(p1PolicyId = "control") to DirectGameplayDecisionCostIssueKind.POLICY_SEAT_COUNT_MISMATCH,
            replaced(candidate.copy(policyId = "control")) to DirectGameplayDecisionCostIssueKind.POLICY_SEAT_ID_MISMATCH,
            valid.copy(seatDiagnostics = valid.seatDiagnostics + ("extra" to candidate)) to DirectGameplayDecisionCostIssueKind.POLICY_SEAT_ID_MISMATCH,
            replaced(candidate.copy(selectionCounts = mapOf(SearchTeacherSelectionKind.SEARCHED to 2))) to
                DirectGameplayDecisionCostIssueKind.FEWER_MEASUREMENTS_THAN_SELECTIONS,
            replaced(candidate.copy(selectionCounts = mapOf(SearchTeacherSelectionKind.SEARCHED to -1))) to
                DirectGameplayDecisionCostIssueKind.INVALID_SELECTION_COUNTS,
        )
        for ((game, kind) in cases) {
            val result = cost(pair(0, game))
            assertFalse(result.costGatePassed, kind.name)
            assertNull(result.candidateControlRatio, kind.name)
            assertTrue(result.issues.any { it.kind == kind }, kind.name)
            assertNull(result.candidate.totalDecisionComputationMillis, kind.name)
            assertNull(result.candidate.games.single().measuredAttempts, kind.name)
        }
        val partial = cost(pair(0, valid), pair(1, replaced(candidate.copy(decisionComputationMillis = null))))
        assertEquals(2, partial.candidate.executedGames)
        assertEquals(1, partial.candidate.measuredGames)
        assertEquals(1L, partial.candidate.measuredAttempts)
        assertNull(partial.candidate.totalDecisionComputationMillis)
    }

    @Test fun `measured empty calls differ from historical absence no games and zero denominator`() {
        val emptyCandidate = cost(pair(0, game("empty-candidate", listOf(100.0), emptyList())))
        assertEquals(1, emptyCandidate.candidate.measuredGames)
        assertEquals(0L, emptyCandidate.candidate.measuredAttempts)
        assertEquals(0.0, emptyCandidate.candidate.totalDecisionComputationMillis)
        assertEquals(0.0, emptyCandidate.candidateControlRatio)
        assertTrue(emptyCandidate.costGatePassed)
        val none = cost()
        assertNull(none.control.totalDecisionComputationMillis)
        assertNull(none.candidateControlRatio)
        assertFalse(none.costGatePassed)
        assertEquals(DirectGameplayDecisionCostIssueKind.NO_EXECUTED_GAMES, none.issues.single().kind)
        val zero = cost(pair(0, game("zero", emptyList(), emptyList())))
        assertEquals(0.0, zero.control.totalDecisionComputationMillis)
        assertNull(zero.candidateControlRatio)
        assertFalse(zero.costGatePassed)
        assertEquals(DirectGameplayDecisionCostIssueKind.NON_POSITIVE_CONTROL_TOTAL, zero.issues.single().kind)
    }

    @Test fun `failed measured attempts may exceed successful counts and threshold is inclusive`() {
        val game = game("failed-attempt", listOf(50.0, 50.0), listOf(50.0, 60.0))
        val result = cost(pair(0, game.copy(seatDiagnostics = game.seatDiagnostics.mapValues { (_, diagnostic) ->
            diagnostic.copy(selectionCounts = mapOf(SearchTeacherSelectionKind.SEARCHED to 1))
        })))
        assertEquals(2L, result.control.measuredAttempts)
        assertEquals(1.1, result.candidateControlRatio)
        assertTrue(result.costGatePassed)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun `aggregate overflow and ratio overflow cannot pass or serialize nonfinite values`() {
        val huge = game("huge", listOf(Double.MAX_VALUE), listOf(1.0))
        val overflow = cost(pair(0, huge), pair(1, huge.copy(gameId = "huge2")))
        assertNull(overflow.control.totalDecisionComputationMillis)
        assertFalse(overflow.costGatePassed)
        assertTrue(overflow.issues.any { it.kind == DirectGameplayDecisionCostIssueKind.NON_FINITE_TOTAL })
        val ratio = cost(pair(0, game("ratio-overflow", listOf(Double.MIN_VALUE), listOf(Double.MAX_VALUE))))
        assertNull(ratio.candidateControlRatio)
        assertFalse(ratio.costGatePassed)
        assertTrue(ratio.issues.any { it.kind == DirectGameplayDecisionCostIssueKind.NON_FINITE_RATIO })
        evidenceJson.encodeToString(ratio)
    }

    private fun cost(vararg pairs: SearchBudgetFrontierPair) = directGameplayDecisionCost(pairs.toList(), "control", "candidate")

    private fun pair(index: Int, game: GameRunResult) = SearchBudgetFrontierPair(index, index.toLong(), listOf(game), true, emptyList(), 1.0)

    private fun game(id: String, control: List<Double>, candidate: List<Double>, stopped: Boolean = false, swap: Boolean = false): GameRunResult {
        fun seat(policy: String, values: List<Double>) = ArenaSeatDiagnostics(policy,
            searchLatenciesMillis = listOf(if (policy == "control") 90.0 else 10.0),
            selectionCounts = mapOf(SearchTeacherSelectionKind.SEARCHED to values.size),
            decisionComputationMillis = values)
        val p0 = if (swap) "candidate" else "control"
        val p1 = if (swap) "control" else "candidate"
        return GameRunResult(gameId = id, seed = 1L, p0Policy = ArenaPolicyKind.SEARCH, p1Policy = ArenaPolicyKind.SEARCH,
            winner = null, terminal = !stopped, disposition = if (stopped) GameRunDisposition.STOPPED_LIMIT else GameRunDisposition.GAME_ENDED,
            decisions = control.size + candidate.size, searchSeat = null, searchScore = null, illegalResponses = 0,
            fallbacks = 0, stepLimit = stopped, p0PolicyId = p0, p1PolicyId = p1,
            seatDiagnostics = mapOf("p0" to seat(p0, if (swap) candidate else control),
                "p1" to seat(p1, if (swap) control else candidate)))
    }
}
