package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource

class TournamentV3CalibratedTest {
    @Test
    fun `fractional pair scores receive a deterministic bounded block-bootstrap interval`() {
        val scores = listOf(0.25, 0.5, 0.5, 0.75).mapIndexed { pairIndex, value ->
            TournamentPairIndexScore(pairIndex, value)
        }
        val interval = pairIndexBootstrapInterval(scores, seed = 17L, samples = 2_000)

        assertEquals(interval, pairIndexBootstrapInterval(scores, seed = 17L, samples = 2_000))
        assertTrue(interval.first in 0.0..0.5)
        assertTrue(interval.second in 0.5..1.0)
        assertTrue(interval.first < 0.5 && interval.second > 0.5)
    }

    @Test
    fun `shared-block correlation changes v3 uncertainty without changing matchup marginals`() {
        val aligned = listOf(
            compactMatchup("opponent-a", listOf(1.0, 0.0)),
            compactMatchup("opponent-b", listOf(1.0, 0.0)),
        )
        val offset = listOf(
            compactMatchup("opponent-a", listOf(1.0, 0.0)),
            compactMatchup("opponent-b", listOf(0.0, 1.0)),
        )

        val alignedRate = rateEstimate(TOURNAMENT_V3_POLICY_ID, null, aligned, seed = 19L)
        val offsetRate = rateEstimate(TOURNAMENT_V3_POLICY_ID, null, offset, seed = 19L)

        assertEquals(0.5, alignedRate.pointRate)
        assertEquals(0.5, offsetRate.pointRate)
        assertTrue(alignedRate.confidenceLower < offsetRate.confidenceLower)
        assertTrue(alignedRate.confidenceUpper > offsetRate.confidenceUpper)
        assertEquals(0.5, offsetRate.confidenceLower)
        assertEquals(0.5, offsetRate.confidenceUpper)
    }

    @Test
    fun `rate estimates refuse legacy shaped nonterminal draws`() {
        val valid = compactMatchup("opponent-a", listOf(0.5))
        val legacyNonterminal = valid.copy(
            games = valid.games.mapIndexed { index, game ->
                if (index == 0) game.copy(terminal = false, winner = null) else game
            },
        )

        assertFailsWith<IllegalArgumentException> {
            rateEstimate(TOURNAMENT_V3_POLICY_ID, null, listOf(legacyNonterminal), seed = 19L)
        }
    }

    @Test
    fun `block-bootstrap interval has reasonable exact coverage for bounded pair outcomes`() {
        val blockCount = 8
        var coveredDatasets = 0
        repeat(1 shl blockCount) { mask ->
            val scores = List(blockCount) { pairIndex ->
                TournamentPairIndexScore(pairIndex, ((mask shr pairIndex) and 1).toDouble())
            }
            val interval = pairIndexBootstrapInterval(
                scores,
                seed = ComponentSeeds.derive("coverage", mask),
                samples = 1_000,
            )
            if (interval.first <= 0.5 && interval.second >= 0.5) coveredDatasets++
        }
        val exactCoverage = coveredDatasets.toDouble() / (1 shl blockCount)

        assertTrue(exactCoverage in 0.85..0.99, "exact coverage=$exactCoverage")
    }

    @Test
    fun `standalone roster contains three current anchors plus v3 without reuse`() {
        val policies = V3CalibratedRoster.policies()
        val opponents = V3CalibratedRoster.opponents(policies)
        val v3 = policies.last()

        assertEquals(4, policies.size)
        assertEquals(TOURNAMENT_V3_CALIBRATED_OPPONENT_IDS, opponents.map(ArenaPolicySpec::id))
        assertEquals(TOURNAMENT_V3_POLICY_ID, v3.id)
        assertEquals(LeafStateSource.CURRENT_INFORMATION_STATE, v3.profile?.leaf?.stateSource)
        assertEquals(LeafEvaluator.MTGALLIUM_TACTICAL_V3, v3.profile?.leaf?.evaluator)
        assertFalse(v3.searchReuse.enabled)
        assertFalse(v3.policyCompression.enabled)
        assertEquals(8, v3.profile?.particles)
        assertEquals(64, v3.profile?.simulations)
        assertEquals(32, v3.profile?.maxPolicyDecisions)
        assertEquals(1.4, v3.profile?.explorationConstant)
    }

    @Test
    fun `three anchors are direct v2 prior winner and production heuristic`() {
        assertEquals(
            listOf(
                "search-current_information_state-mtgallium_visible_v2",
                "search-bounded_rollout-mtgallium_visible_v2",
                "argentum-production-heuristic",
            ),
            TOURNAMENT_V3_CALIBRATED_OPPONENT_IDS,
        )
    }

    @Test
    fun `calibrated schedule has 75 unique seed pairs and 150 seat-swapped games`() {
        val policies = V3CalibratedRoster.policies()
        val v3 = policies.last()
        val descriptors = V3CalibratedRoster.opponents(policies).flatMap { opponent ->
            (0 until 25).flatMap { pair ->
                listOf(
                    tournamentDescriptor(opponent, v3, pair, 0),
                    tournamentDescriptor(opponent, v3, pair, 1),
                )
            }
        }

        assertEquals(150, descriptors.size)
        assertEquals(150, descriptors.map(TournamentGameDescriptor::gameId).distinct().size)
        assertEquals(75, descriptors.map { "${it.firstPolicyId}:${it.secondPolicyId}:${it.pairIndex}" }.distinct().size)
        descriptors.chunked(2).forEach { (a, b) ->
            assertEquals(a.firstPolicyId, a.p0PolicyId)
            assertEquals(a.secondPolicyId, a.p1PolicyId)
            assertEquals(a.secondPolicyId, b.p0PolicyId)
            assertEquals(a.firstPolicyId, b.p1PolicyId)
            assertEquals(a.pairIndex to "a", parseGameSuffix(a.gameId))
            assertEquals(a.pairIndex to "b", parseGameSuffix(b.gameId))
        }
    }

    @Test
    fun `library-order seed derivation is shared only within a seat-swapped pair`() {
        val baseSeed = 20260823L
        val pair0a = ComponentSeeds.derive(baseSeed, 0, "v3-calibrated-library-orders")
        val pair0b = ComponentSeeds.derive(baseSeed, 0, "v3-calibrated-library-orders")
        val pair1 = ComponentSeeds.derive(baseSeed, 1, "v3-calibrated-library-orders")

        assertEquals(pair0a, pair0b)
        assertTrue(pair0a != pair1)
    }

    @Test
    fun `standalone progress begins at zero and contains no extension offsets`() {
        val directory = createTempDirectory("mtgallium-v3-calibrated-progress")
        val tracker = TournamentProgressTracker(
            progressPath = directory.resolve("progress.json"),
            tournamentVersion = TOURNAMENT_V3_CALIBRATED_VERSION,
            runIdentity = "calibrated-test",
            outerCommit = "outer",
            argentumCommit = "argentum",
            baseSeed = 20260823L,
            pairsPerMatchup = 25,
            workerThreads = 4,
            matchupCount = TOURNAMENT_V3_CALIBRATED_MATCHUPS,
            recordKind = "CALIBRATED",
            policyIds = V3CalibratedRoster.policies().map(ArenaPolicySpec::id),
            heartbeatMillis = 60_000,
            output = {},
            installShutdownHook = false,
        )

        val initial = tracker.snapshot()
        assertEquals(TOURNAMENT_V3_CALIBRATED_PAIRS, initial.totalPairs)
        assertEquals(TOURNAMENT_V3_CALIBRATED_GAMES, initial.totalGames)
        assertEquals(0, initial.finishedPairs)
        assertEquals(0, initial.finishedGames)
        assertEquals(TOURNAMENT_V3_CALIBRATED_GAMES, initial.queuedGames)
        assertEquals(0, initial.baselinePairs)
        assertEquals(0, initial.baselineGames)
        assertNull(initial.extensionPairs)
        assertNull(initial.extensionGames)
        assertEquals("CALIBRATED", initial.recordKind)
        tracker.finish(TournamentRunProgressState.INTERRUPTED)
        assertTrue(Files.isRegularFile(directory.resolve("progress.json")))
    }

    private fun compactMatchup(
        opponentPolicyId: String,
        v3PairScores: List<Double>,
    ): TournamentMatchupSummary {
        val games = v3PairScores.flatMapIndexed { pairIndex, score ->
            require(score == 0.0 || score == 0.5 || score == 1.0)
            val v3Wins = when (score) {
                1.0 -> setOf("a", "b")
                0.5 -> setOf("a")
                else -> emptySet()
            }
            listOf("a", "b").map { leg ->
                val v3IsP0 = leg == "b"
                val p0 = if (v3IsP0) TOURNAMENT_V3_POLICY_ID else opponentPolicyId
                val p1 = if (v3IsP0) opponentPolicyId else TOURNAMENT_V3_POLICY_ID
                val v3Won = leg in v3Wins
                val winner = if (v3Won == v3IsP0) "p0" else "p1"
                TournamentGameSummary(
                    gameId = "tournament-$opponentPolicyId-$TOURNAMENT_V3_POLICY_ID-$pairIndex-$leg",
                    seed = pairIndex.toLong(),
                    p0PolicyId = p0,
                    p1PolicyId = p1,
                    winner = winner,
                    terminal = true,
                    disposition = GameRunDisposition.GAME_ENDED,
                    decisions = 1,
                    searchSeat = null,
                    searchScore = null,
                    illegalResponses = 0,
                    fallbacks = 0,
                    heuristicResolutionCounts = emptyMap(),
                    stepLimit = false,
                    exception = null,
                    informationLedgerComplete = true,
                    unsupportedInformationEvents = emptyList(),
                    replayPath = "replay-$pairIndex-$leg",
                    replaySha256 = "hash-$pairIndex-$leg",
                    replayVerified = true,
                    replayVerificationDiagnostic = null,
                    cleanupDiscardEvents = 0,
                    mainPhasePassesWithProactiveOptions = 0,
                    elapsedMillis = 1.0,
                )
            }
        }
        val v3Wins = games.count { game ->
            (game.winner == "p0" && game.p0PolicyId == TOURNAMENT_V3_POLICY_ID) ||
                (game.winner == "p1" && game.p1PolicyId == TOURNAMENT_V3_POLICY_ID)
        }
        return TournamentMatchupSummary(
            firstPolicyId = opponentPolicyId,
            secondPolicyId = TOURNAMENT_V3_POLICY_ID,
            pairCount = v3PairScores.size,
            winsFirst = games.size - v3Wins,
            draws = 0,
            winsSecond = v3Wins,
            firstPointRate = (games.size - v3Wins).toDouble() / games.size,
            games = games,
            pairIndices = v3PairScores.indices.toList(),
        )
    }

}
