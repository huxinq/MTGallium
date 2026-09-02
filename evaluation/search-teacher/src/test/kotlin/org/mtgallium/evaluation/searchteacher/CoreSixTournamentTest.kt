package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.SearchTeacherLeafConfigurations

class CoreSixTournamentTest {
    @Test
    fun `stopped games are refused while an engine-ended null winner remains a draw`() {
        fun game(
            id: String,
            terminal: Boolean,
            disposition: GameRunDisposition,
            winner: String?,
        ) = GameRunResult(
            gameId = id,
            seed = 1L,
            p0Policy = ArenaPolicyKind.SEARCH,
            p1Policy = ArenaPolicyKind.HEURISTIC,
            winner = winner,
            terminal = terminal,
            disposition = disposition,
            evidenceStop = if (disposition == GameRunDisposition.STOPPED_SOFTWARE) {
                EvidenceStopMetadata(listOf("REJECTED_SEARCH_TRANSITION"), emptyList(), 0)
            } else null,
            decisions = 0,
            searchSeat = "p0",
            searchScore = null,
            illegalResponses = 0,
            fallbacks = 0,
            stepLimit = false,
            p0PolicyId = "first",
            p1PolicyId = "second",
        )

        assertFailsWith<IllegalArgumentException> {
            summarizeMatchup(
                "first",
                "second",
                1,
                listOf(game("stopped", false, GameRunDisposition.STOPPED_SOFTWARE, null)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            summarizeMatchup(
                "first",
                "second",
                1,
                listOf(game("legacy-nonterminal", false, GameRunDisposition.LEGACY_UNCLASSIFIED, null)),
            )
        }
        val summary = summarizeMatchup(
            "first",
            "second",
            1,
            listOf(game("draw", true, GameRunDisposition.GAME_ENDED, null)),
        )
        assertEquals(1, summary.draws)
        assertEquals(0.5, summary.firstPointRate)
    }

    @Test
    fun `shared pair-index blocks retain both seats from every matchup`() {
        fun game(id: String, p0: String, p1: String) = GameRunResult(
            gameId = id,
            seed = 1L,
            p0Policy = ArenaPolicyKind.SEARCH,
            p1Policy = ArenaPolicyKind.SEARCH,
            winner = null,
            terminal = true,
            disposition = GameRunDisposition.GAME_ENDED,
            decisions = 1,
            searchSeat = null,
            searchScore = null,
            illegalResponses = 0,
            fallbacks = 0,
            stepLimit = false,
            p0PolicyId = p0,
            p1PolicyId = p1,
        )
        fun matchup(first: String, second: String) = TournamentMatchupReport(
            firstPolicyId = first,
            secondPolicyId = second,
            pairCount = 2,
            winsFirst = 0,
            draws = 4,
            winsSecond = 0,
            firstPointRate = 0.5,
            games = listOf(
                game("$first-$second-7-a", first, second),
                game("$first-$second-7-b", second, first),
                game("$first-$second-9-a", first, second),
                game("$first-$second-9-b", second, first),
            ),
            pairIndices = listOf(7, 9),
        )

        val blocks = tournamentPairIndexBlocks(
            listOf(matchup("a", "b"), matchup("a", "c"))
        )

        assertEquals(listOf(7, 9), blocks.map(TournamentPairIndexBlock::pairIndex))
        assertEquals(listOf(4, 4), blocks.map { it.games.size })
        assertTrue(blocks[0].games.all { "-7-" in it.gameId })
        assertTrue(blocks[1].games.all { "-9-" in it.gameId })
    }

    @Test
    fun `tournament summary states the checked behavior and its strategic limit`() {
        val markdown = buildString {
            appendTournamentExecutionSummary(
                gameCount = 150,
                completePairs = 75,
                conditionsSatisfied = true,
                failureCount = 0,
                gamesWithCleanupDiscard = 3,
            )
        }

        assertTrue("All 150 recorded games formed 75 complete seat-swapped pairs" in markdown)
        assertTrue("hits the step limit" in markdown)
        assertTrue("does not show that every legal action was offered" in markdown)
        assertTrue("3/150 games" in markdown)
        assertTrue("Valid:" !in markdown)
    }

    @Test
    fun `core six roster varies only the five supported leaves`() {
        val policies = CoreSixRoster.policies()
        val search = policies.filter { it.kind == ArenaPolicyKind.SEARCH }

        assertEquals(6, policies.size)
        assertEquals(
            SearchTeacherLeafConfigurations.supported.toSet(),
            search.map { requireNotNull(it.profile).leaf }.toSet(),
        )
        assertEquals(1, search.map { it.profile?.particles }.distinct().size)
        assertEquals(1, search.map { it.profile?.simulations }.distinct().size)
        assertEquals(1, search.map { it.profile?.maxPolicyDecisions }.distinct().size)
        assertTrue(search.all { it.profile?.actionSpaceProfile == SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1 })
        assertTrue(search.all { !it.policyCompression.enabled && !it.searchReuse.enabled })
        assertTrue(search.all { it.beliefArchitecture == org.mtgallium.agent.infoset.core.BeliefArchitecture.SEQUENTIAL_B_V1 })
        assertEquals(listOf("argentum-production-heuristic"), policies.filter { it.kind == ArenaPolicyKind.HEURISTIC }.map { it.id })
    }

    @Test
    fun `Bradley Terry orders a synthetic dominant policy and remains finite`() {
        val ids = listOf("dominant", "middle", "argentum-production-heuristic")
        fun game(id: String, p0: String, p1: String, winner: String?) = GameRunResult(
            gameId = id,
            seed = 1L,
            p0Policy = if (p0 == "argentum-production-heuristic") ArenaPolicyKind.HEURISTIC else ArenaPolicyKind.SEARCH,
            p1Policy = if (p1 == "argentum-production-heuristic") ArenaPolicyKind.HEURISTIC else ArenaPolicyKind.SEARCH,
            winner = winner,
            terminal = true,
            disposition = GameRunDisposition.GAME_ENDED,
            decisions = 1,
            searchSeat = null,
            searchScore = null,
            illegalResponses = 0,
            fallbacks = 0,
            stepLimit = false,
            p0PolicyId = p0,
            p1PolicyId = p1,
        )
        fun matchup(first: String, second: String, firstWins: Boolean) = TournamentMatchupReport(
            firstPolicyId = first,
            secondPolicyId = second,
            pairCount = 2,
            winsFirst = if (firstWins) 4 else 0,
            draws = 0,
            winsSecond = if (firstWins) 0 else 4,
            firstPointRate = if (firstWins) 1.0 else 0.0,
            games = listOf(
                game("$first-$second-a0", first, second, if (firstWins) "p0" else "p1"),
                game("$first-$second-b0", second, first, if (firstWins) "p1" else "p0"),
                game("$first-$second-a1", first, second, if (firstWins) "p0" else "p1"),
                game("$first-$second-b1", second, first, if (firstWins) "p1" else "p0"),
            ),
        )
        val result = BradleyTerry.fit(
            ids,
            listOf(
                matchup("dominant", "middle", true),
                matchup("dominant", "argentum-production-heuristic", true),
                matchup("middle", "argentum-production-heuristic", true),
            ),
            seed = 17L,
            bootstrapSamples = 100,
        )

        assertEquals(ids, result.standings.map { it.policyId })
        assertTrue(result.standings.all { it.rating.isFinite() && it.confidenceLower.isFinite() && it.confidenceUpper.isFinite() })
        assertTrue(kotlin.math.abs(result.startingPlayerRating) < 1.0)
    }

    @Test
    fun `Bradley Terry bootstrap converges for the completed calibrated star`() {
        val v2 = "search-current_information_state-mtgallium_visible_v2"
        val bounded = "search-bounded_rollout-mtgallium_visible_v2"
        val heuristic = "argentum-production-heuristic"
        val v3 = TOURNAMENT_V3_POLICY_ID
        fun matchup(first: String, pairOutcomes: String): TournamentMatchupReport {
            val pairs = pairOutcomes.split(",")
            val games = pairs.flatMapIndexed { pairIndex, outcomes ->
                outcomes.mapIndexed { leg, outcome ->
                    val p0 = if (leg == 0) first else v3
                    val p1 = if (leg == 0) v3 else first
                    val firstWon = outcome == 'W'
                    GameRunResult(
                        gameId = "$first-$pairIndex-$leg",
                        seed = pairIndex.toLong(),
                        p0Policy = if (p0 == heuristic) ArenaPolicyKind.HEURISTIC else ArenaPolicyKind.SEARCH,
                        p1Policy = if (p1 == heuristic) ArenaPolicyKind.HEURISTIC else ArenaPolicyKind.SEARCH,
                        winner = if ((leg == 0) == firstWon) "p0" else "p1",
                        terminal = true,
                        disposition = GameRunDisposition.GAME_ENDED,
                        decisions = 1,
                        searchSeat = null,
                        searchScore = null,
                        illegalResponses = 0,
                        fallbacks = 0,
                        stepLimit = false,
                        p0PolicyId = p0,
                        p1PolicyId = p1,
                    )
                }
            }
            val firstWins = pairOutcomes.count { it == 'W' }
            val secondWins = games.size - firstWins
            return TournamentMatchupReport(
                first,
                v3,
                pairs.size,
                firstWins,
                0,
                secondWins,
                firstWins.toDouble() / games.size,
                games,
            )
        }
        val result = BradleyTerry.fit(
            listOf(v2, bounded, heuristic, v3),
            listOf(
                matchup(v2, "WW,WW,WW,WW,WL,WW,WW,WW,WW,WW,WW,WW,WW,WW,LW,LW,WW,WW,WL,WW,WW,WW,WW,WW,LW"),
                matchup(bounded, "WW,WW,WL,WW,WW,WW,WW,WW,WW,LW,LW,WW,WW,WW,WW,WW,WW,WW,LL,LW,LW,WW,WW,LW,LW"),
                matchup(heuristic, "LL,WL,WL,WL,WW,LW,WW,WL,WW,LW,LW,LL,WW,WW,WW,LL,LW,WW,WL,LW,LL,LL,WL,LW,WW"),
            ),
            seed = 20260823L,
            bootstrapSamples = 10_000,
        )

        assertEquals(listOf(v2, bounded, heuristic, v3), result.standings.map { it.policyId })
        assertTrue(result.standings.all {
            it.rating.isFinite() && it.confidenceLower.isFinite() && it.confidenceUpper.isFinite()
        })
    }
}
