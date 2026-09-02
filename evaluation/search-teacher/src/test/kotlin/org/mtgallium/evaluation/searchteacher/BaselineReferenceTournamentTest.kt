package org.mtgallium.evaluation.searchteacher

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource

class BaselineFactorialTournamentTest {
    @Test fun `factorial roster binds both v2 and v3 leaf modes plus heuristic`() {
        val policies = BaselineFactorialRoster.policies()
        val byId = policies.associateBy { it.id }
        assertEquals(listOf(BaselineFactorialRoster.ROLLOUT_V2_ID, BaselineFactorialRoster.CURRENT_V2_ID,
            BaselineFactorialRoster.ROLLOUT_V3_ID, BaselineFactorialRoster.CURRENT_V3_ID,
            BaselineFactorialRoster.HEURISTIC_ID), policies.map { it.id })
        listOf(BaselineFactorialRoster.ROLLOUT_V2_ID, BaselineFactorialRoster.ROLLOUT_V3_ID).forEach {
            assertEquals(LeafStateSource.BOUNDED_ROLLOUT, byId.getValue(it).profile?.leaf?.stateSource)
        }
        listOf(BaselineFactorialRoster.CURRENT_V2_ID, BaselineFactorialRoster.CURRENT_V3_ID).forEach {
            assertEquals(LeafStateSource.CURRENT_INFORMATION_STATE, byId.getValue(it).profile?.leaf?.stateSource)
        }
        assertEquals(LeafEvaluator.MTGALLIUM_VISIBLE_V2, byId.getValue(BaselineFactorialRoster.ROLLOUT_V2_ID).profile?.leaf?.evaluator)
        assertEquals(LeafEvaluator.MTGALLIUM_TACTICAL_V3, byId.getValue(BaselineFactorialRoster.ROLLOUT_V3_ID).profile?.leaf?.evaluator)
        policies.filter { it.profile != null }.forEach { assertFalse(it.policyCompression.enabled); assertFalse(it.searchReuse.enabled) }
    }

    @Test fun `factorial declares the exact five accepted edges`() {
        assertEquals(listOf(
            BaselineFactorialRoster.ROLLOUT_V2_ID to BaselineFactorialRoster.HEURISTIC_ID,
            BaselineFactorialRoster.ROLLOUT_V2_ID to BaselineFactorialRoster.CURRENT_V2_ID,
            BaselineFactorialRoster.ROLLOUT_V3_ID to BaselineFactorialRoster.CURRENT_V3_ID,
            BaselineFactorialRoster.ROLLOUT_V2_ID to BaselineFactorialRoster.ROLLOUT_V3_ID,
            BaselineFactorialRoster.CURRENT_V2_ID to BaselineFactorialRoster.CURRENT_V3_ID,
        ), BaselineFactorialRoster.matchups().map { it.first.id to it.second.id })
    }

    @Test fun `pair jobs are deterministic complete and prefix balanced`() {
        val jobs = interleavedTournamentPairJobs(5, BASELINE_FACTORIAL_PAIRS)
        assertEquals(jobs, interleavedTournamentPairJobs(5, BASELINE_FACTORIAL_PAIRS))
        assertEquals(5 * BASELINE_FACTORIAL_PAIRS, jobs.size)
        assertEquals(5, jobs.take(5).map { it.matchupIndex }.toSet().size)
        assertEquals(jobs.size, jobs.map { it.matchupIndex to it.pairIndex }.toSet().size)
        jobs.indices.forEach { end ->
            val counts = (0 until 5).map { matchup -> jobs.take(end + 1).count { it.matchupIndex == matchup } }
            assertTrue(counts.max() - counts.min() <= 1, "prefix ${end + 1} was imbalanced: $counts")
        }
    }

    @Test fun `scored manifest has 250 unique seat-swapped games in interleaved matchup order`() {
        val games = baselineScheduledGames("scored", "baseline-factorial-library-orders", BASELINE_FACTORIAL_PAIRS)
        assertEquals(BASELINE_FACTORIAL_GAMES, games.size)
        assertEquals(games.size, games.map { it.gameId }.distinct().size)
        assertEquals(5, games.take(10).map { it.firstPolicyId to it.secondPolicyId }.toSet().size)
        games.chunked(2).forEach { (a, b) ->
            assertEquals(a.p0PolicyId, b.p1PolicyId); assertEquals(a.p1PolicyId, b.p0PolicyId); assertEquals(a.pairIndex, b.pairIndex)
        }
    }

    @Test fun `first policy inspection perspective follows its seat`() {
        BaselineFactorialRoster.matchups().forEach { (first, second) ->
            assertEquals("p0", baselineFirstPolicySeat(baselineDescriptor("scored", first, second, 0, 0)))
            assertEquals("p1", baselineFirstPolicySeat(baselineDescriptor("scored", first, second, 0, 1)))
        }
    }

    @Test fun `inspection game ids are deterministic opaque UUIDs and run separated`() {
        val (first, second) = BaselineFactorialRoster.matchups().first()
        val scored = baselineDescriptor("scored-run", first, second, 0, 0)
        val smoke = baselineDescriptor("smoke-run", first, second, 0, 0)
        assertEquals(scored, baselineDescriptor("scored-run", first, second, 0, 0)); UUID.fromString(scored.gameId); UUID.fromString(smoke.gameId)
        assertFalse(scored.gameId == smoke.gameId)
    }

    @Test fun `manifest identities map to portable evidence directory keys`() {
        assertEquals("baseline-factorial-v1-sha256-0123456789abcdef", baselineArtifactDirectoryKey("baseline-factorial-v1-sha256:0123456789abcdef"))
    }

    @Test fun `policy bindings serialize in sorted map order`() {
        val bindings = sortedMapOf("rollout" to "bounded", "current" to "state")
        assertEquals(encodeBaselineBindings(bindings), encodeBaselineBindings(bindings.entries.reversed().associate { it.toPair() }))
    }

    @Test fun `smoke covers only all three new v3 edges in its first worker wave`() {
        val smoke = baselineScheduledGames("smoke", "baseline-factorial-smoke-library-orders", 1, smoke = true)
        val scored = baselineScheduledGames("scored", "baseline-factorial-library-orders", BASELINE_FACTORIAL_PAIRS)
        assertEquals(BASELINE_FACTORIAL_SMOKE_GAMES, smoke.size)
        assertEquals(3, smoke.take(3).map { it.firstPolicyId to it.secondPolicyId }.toSet().size)
        assertEquals(BaselineFactorialRoster.smokeMatchups().map { it.first.id to it.second.id }.toSet(), smoke.map { it.firstPolicyId to it.secondPolicyId }.toSet())
        assertTrue(smoke.map { it.gameId }.intersect(scored.map { it.gameId }.toSet()).isEmpty())
        assertTrue(smoke.all { it.seed == ComponentSeeds.derive(BASELINE_FACTORIAL_BASE_SEED, 0, "baseline-factorial-smoke-library-orders") })
    }

    @Test fun `smoke metric helper requires six positive elapsed games and heap headroom`() {
        val games = List(BASELINE_FACTORIAL_SMOKE_GAMES) { summary(it.toString(), 1.0) }
        assertTrue(baselineSmokeMetricsPassed(games, 100, 80)); assertFalse(baselineSmokeMetricsPassed(games.dropLast(1), 100, 80))
        assertFalse(baselineSmokeMetricsPassed(games.mapIndexed { i, game -> if (i == 0) game.copy(elapsedMillis = 0.0) else game }, 100, 80))
        assertFalse(baselineSmokeMetricsPassed(games, 100, 91)); assertEquals(BASELINE_FACTORIAL_WORKERS, BASELINE_FACTORIAL_SMOKE_WORKERS)
    }

    private fun summary(id: String, elapsed: Double) = TournamentGameSummary(
        gameId = id, seed = 1, p0PolicyId = "p0", p1PolicyId = "p1", winner = "p0", terminal = true,
        disposition = GameRunDisposition.GAME_ENDED, decisions = 1, searchSeat = "p0", searchScore = 1.0,
        illegalResponses = 0, fallbacks = 0, heuristicResolutionCounts = emptyMap(), stepLimit = false, exception = null,
        informationLedgerComplete = true, unsupportedInformationEvents = emptyList(), replayPath = "r", replaySha256 = "s",
        replayVerified = true, replayVerificationDiagnostic = null, cleanupDiscardEvents = 0,
        mainPhasePassesWithProactiveOptions = 0, elapsedMillis = elapsed,
    )
}
