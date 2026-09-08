package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

@Tag("public-source")
class ArenaGameEvidenceTest {
    @Test
    fun `paired descriptors retain game identity and reverse only the policy seats`() {
        val first = ArenaPolicySpec("first", ArenaPolicyKind.HEURISTIC)
        val second = ArenaPolicySpec("second", ArenaPolicyKind.HEURISTIC)
        val firstLeg = tournamentDescriptor(first, second, 7, 0)
        val secondLeg = tournamentDescriptor(first, second, 7, 1)

        assertEquals("tournament-first-second-7-a", firstLeg.gameId)
        assertEquals("tournament-first-second-7-b", secondLeg.gameId)
        assertEquals(firstLeg.firstPolicyId, secondLeg.firstPolicyId)
        assertEquals(firstLeg.secondPolicyId, secondLeg.secondPolicyId)
        assertEquals(firstLeg.pairIndex, secondLeg.pairIndex)
        assertEquals(firstLeg.p0PolicyId, secondLeg.p1PolicyId)
        assertEquals(firstLeg.p1PolicyId, secondLeg.p0PolicyId)
        assertFailsWith<IllegalArgumentException> { tournamentDescriptor(first, second, 7, 2) }
    }

    @Test
    fun `operational admission permits cleanup but refuses stopped substituted or unverified games`() {
        val game = validGame("cleanup", "alpha", "beta").copy(
            replayPath = "replays/cleanup.jsonl.gz",
            replaySha256 = "abc123",
            replayVerified = true,
            cleanupDiscardEvents = 1,
        )

        assertTrue(operationallyValidGame(game))
        assertFailsWith<IllegalArgumentException> { game.copy(terminal = false) }
        assertFalse(operationallyValidGame(game.copy(
            terminal = false,
            winner = null,
            disposition = GameRunDisposition.STOPPED_LIMIT,
            stepLimit = true,
        )))
        assertFalse(operationallyValidGame(game.copy(
            terminal = false,
            winner = null,
            disposition = GameRunDisposition.STOPPED_SOFTWARE,
            evidenceStop = EvidenceStopMetadata(
                triggerCodes = listOf("REJECTED_TRANSITION"),
                affectedViewers = listOf("p0"),
                firstDetectedBeforeDecision = 9,
            ),
        )))
        assertFalse(operationallyValidGame(game.copy(fallbacks = 1)))
        assertFalse(operationallyValidGame(game.copy(replayVerified = false)))
    }

    private fun validGame(gameId: String, p0: String, p1: String) = GameRunResult(
        gameId = gameId,
        seed = 1L,
        p0Policy = ArenaPolicyKind.SEARCH,
        p1Policy = ArenaPolicyKind.SEARCH,
        winner = "p0",
        terminal = true,
        disposition = GameRunDisposition.GAME_ENDED,
        decisions = 8,
        searchSeat = null,
        searchScore = null,
        illegalResponses = 0,
        fallbacks = 0,
        stepLimit = false,
        p0PolicyId = p0,
        p1PolicyId = p1,
    )

}
