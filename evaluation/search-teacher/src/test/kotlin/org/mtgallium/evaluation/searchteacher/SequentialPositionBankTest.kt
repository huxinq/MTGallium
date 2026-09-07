package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.PolicyBehaviorBinding
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory

@Tag("public-source")
class SequentialPositionBankTest {
    private val control = SearchTeacherCalibrationPolicy("control", 8, 64, 32, 1.4, false, 1.0)
    private val candidate = control.copy(id = "candidate", simulations = 56)
    private val plan = SearchTeacherCalibrationPlan(phase = SearchTeacherCalibrationPhase.CONFIRMATION,
        baseSeed = 73, pairOffset = 40, pairCount = 12, control = control, candidates = listOf(candidate))
    private val rule = PairedSequentialRule(nullPointRate = .5, targetPointRate = .5,
        falsePositiveRate = .025, falseNegativeRate = .025, maximumPairs = 12,
        betFractions = listOf(.8), stopForFutility = true)
    private val tree = PolicySourceTreeState("synthetic", "a".repeat(64), "b".repeat(64), "c".repeat(64))
    private val source = PolicySourceProvenance(expectedArgentumRevision = "synthetic", outer = tree, argentum = tree)

    private fun pair(index: Int): SearchBudgetFrontierPair = searchBudgetFrontierPair(index, plan.pairSeed(index),
        (0..1).map { leg -> GameRunResult(gameId = "candidate-pair-$index-leg-$leg", seed = plan.pairSeed(index),
            p0Policy = ArenaPolicyKind.SEARCH, p1Policy = ArenaPolicyKind.SEARCH,
            winner = "p0", terminal = true, disposition = GameRunDisposition.GAME_ENDED,
            decisions = 1, searchSeat = null, searchScore = null, illegalResponses = 0, fallbacks = 0,
            stepLimit = false, replayVerified = true,
            p0PolicyId = if (leg == 0) control.id else candidate.id,
            p1PolicyId = if (leg == 0) candidate.id else control.id) }, candidate.id)

    private fun report(): SearchTeacherCalibrationReport {
        val pairs = (40..47).map(::pair)
        val result = pairedSequentialTest(rule, pairs.map { PairedSequentialScore(it.pairIndex, .5) }, 40)
        val execution = PairedSequentialExecution(pairs, result)
        val policies = listOf(control, candidate).map { descriptor ->
            val binding = PolicyBehaviorBinding.create("synthetic:${descriptor.id}",
                JsonObject(mapOf("synthetic" to JsonPrimitive(descriptor.id))), source)
            SearchTeacherCalibrationPolicyReport(descriptor, describeTournamentPolicy(descriptor.policy(73)),
                descriptor.parameters(73).searchConfig(), binding,
                SearchTeacherSearchFactory.rootRolloutPolicy().behaviorSpecification,
                SearchTeacherSearchFactory.opponentRolloutPolicy().behaviorSpecification)
        }
        val identity = searchTeacherCalibrationBindings(plan, source,
            policies.associate { it.descriptor.id to it.binding.identity }, "deck", "pool", 4, rule).identity
        return SearchTeacherCalibrationReport(runIdentity = identity, generatedAtUtc = "synthetic",
            sourceProvenance = source, deckHash = "deck", cardPoolHash = "pool", plan = plan,
            workerThreads = 4, currentAttemptElapsedMillis = 0.0, policies = policies,
            comparisons = listOf(calibrationComparison(plan, candidate, pairs.take(result.inspectedPairs),
                pairs.flatMap { it.games }, result.inspectedPairs)), valid = execution.valid,
            sequentialRule = rule, sequentialResult = result,
            sequentialOvershootPairs = pairs.drop(result.inspectedPairs),
            sequentialOperationalValid = execution.operationalValid, sequentialPopulation = execution.population)
    }

    @Test
    fun `completed sequential admission preserves futility prefix overshoot and unexecuted schedule`() {
        val report = report()
        requireRealGamePositionBankSourceIdentity(report, report.runIdentity, completedSequential = true)
        val pairs = completedSequentialBankPairs(report)
        assertEquals((40..47).toList(), pairs.map { it.pairIndex })
        assertEquals(SearchTeacherSequentialPopulation(12, 8, 6, 4, 2, 0, 0), report.sequentialPopulation)
        assertEquals(PairedSequentialDisposition.FUTILITY, report.sequentialResult!!.disposition)
        // Default admission remains strict; callers cannot silently reinterpret old source plans.
        assertFailsWith<IllegalArgumentException> { requireRealGamePositionBankSourceIdentity(report, report.runIdentity) }
        assertFailsWith<IllegalArgumentException> { requireRealGamePositionBankSourceIdentity(
            report.copy(sequentialRule = rule.copy(stopForFutility = false)), report.runIdentity, completedSequential = true) }
        val source = RealGamePositionBankSource("/tmp/source", "identity")
        assertFalse(evidenceJson.encodeToString(RealGamePositionBankSource.serializer(), source).contains("completedSequential"))
        assertTrue(evidenceJson.encodeToString(RealGamePositionBankSource.serializer(),
            source.copy(completedSequentialReferenceOnly = true)).contains("completedSequentialReferenceOnly"))
        assertFailsWith<IllegalArgumentException> { source.copy(retainedReferenceOnly = true, completedSequentialReferenceOnly = true) }
    }

    @Test
    fun `compact report counts only inspected games and names the configuration intervention`() {
        val report = report()
        val rendered = renderSearchTeacherCalibration(report)
        // Six split inspected pairs score 6-6; the two split overshoot pairs must not make it 8-8.
        assertTrue("Candidate W/L/draw=6/6/0 over 12 complete valid games" in rendered)
        assertFalse("Candidate W/L/draw=8/8/0" in rendered)
        assertTrue("simulations: 64 → 56" in rendered)
        assertTrue("decision horizon=32" in rendered)
        assertTrue("through report construction" in rendered)
        assertTrue("subsequent report writing/finalization/verification" in rendered)
        val budget = report.copy(sequentialResult = report.sequentialResult!!.copy(disposition = PairedSequentialDisposition.BUDGET_EXHAUSTED))
        assertTrue("Inconclusive: the pair cap" in renderSearchTeacherCalibration(budget))
    }

    @Test
    fun `sequential admission rejects forged outcome population missing overshoot and ongoing work`() {
        val r = report()
        assertFailsWith<IllegalArgumentException> { completedSequentialBankPairs(r.copy(sequentialPopulation = null)) }
        assertFailsWith<IllegalArgumentException> { completedSequentialBankPairs(r.copy(sequentialOvershootPairs = emptyList())) }
        assertFailsWith<IllegalArgumentException> { completedSequentialBankPairs(r.copy(sequentialOperationalValid = false)) }
        assertFailsWith<IllegalArgumentException> { completedSequentialBankPairs(r.copy(valid = false)) }
        assertFailsWith<IllegalArgumentException> { completedSequentialBankPairs(r.copy(sequentialResult =
            r.sequentialResult!!.copy(disposition = PairedSequentialDisposition.CONTINUE))) }
        val first = r.comparisons.single().pairs.first()
        val forged = first.copy(treatmentPoints = 2.0)
        assertFailsWith<IllegalArgumentException> { completedSequentialBankPairs(r.copy(comparisons = listOf(
            r.comparisons.single().copy(pairs = listOf(forged) + r.comparisons.single().pairs.drop(1))))) }
        assertFailsWith<IllegalArgumentException> { completedSequentialBankPairs(r.copy(sequentialOvershootPairs =
            r.sequentialOvershootPairs!!.reversed())) }
        val stopped = pair(46).let { p -> searchBudgetFrontierPair(p.pairIndex, p.seed,
            p.games.map { it.copy(terminal = false, winner = null, disposition = GameRunDisposition.STOPPED_LIMIT, stepLimit = true) }, candidate.id) }
        assertFailsWith<IllegalArgumentException> { completedSequentialBankPairs(r.copy(sequentialOvershootPairs = listOf(stopped, pair(47)))) }
    }
}
