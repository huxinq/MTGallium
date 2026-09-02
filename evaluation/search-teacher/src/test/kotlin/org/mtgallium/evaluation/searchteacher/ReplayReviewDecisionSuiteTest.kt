package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile

class ReplayReviewDecisionSuiteTest {
    @Test
    fun `catalog requires one grade for every visible candidate name`() {
        val source = ReplayReviewDecisionCatalog.cases.single().source
        val failure = assertFailsWith<IllegalArgumentException> {
            ReplayReviewDecisionCase(
                id = "malformed",
                source = source,
                gameSeed = 1L,
                searchBaseSeed = 1L,
                startingPlayerIndex = 0,
                semanticPrefix = listOf(SemanticActionIntentKind.KEEP_HAND),
                expectedVisibleHand = mapOf("Mountain" to 7),
                gradesByCandidateCard = emptyMap(),
                reviewerJudgment = "malformed fixture",
            )
        }
        assertTrue("exactly one candidate grade" in failure.message.orEmpty())
    }

    @Test
    fun `first case preserves tiered judgment rather than one accepted answer`() {
        val case = ReplayReviewDecisionCatalog.cases.single()
        assertEquals(
            setOf("Hexing Squelcher", "Razorkin Needlehead"),
            case.gradesByCandidateCard.filterValues {
                it == ReplayReviewDecisionGrade.PREFERRED
            }.keys,
        )
        assertEquals(
            setOf("Nova Hellkite", "Lightning Strike"),
            case.gradesByCandidateCard.filterValues {
                it == ReplayReviewDecisionGrade.PLAUSIBLE
            }.keys,
        )
        assertEquals(
            setOf("Burnout Bashtronaut", "Mountain"),
            case.gradesByCandidateCard.filterValues {
                it == ReplayReviewDecisionGrade.UNACCEPTABLE
            }.keys,
        )
    }

    @Test
    @ScenarioExecutionTest
    fun `recorded seed and semantic prefix reconstruct exact visible bottom decision`() {
        val case = ReplayReviewDecisionCatalog.cases.single()
        val position = ReplayReviewDecisionCatalog.reconstruct(case, buildRegistry(), loadDeckManifest())

        assertEquals("p0", position.actor)
        assertEquals(case.gradesByCandidateCard.keys, position.candidateCardsBySignature.values.toSet())
        assertEquals(6, position.candidateCardsBySignature.size)
        assertTrue(position.currentInformationStateDigest.isNotBlank())
    }

    @Test
    @ScenarioExecutionTest
    fun `policy runner retains exact choices and tier for v2 and v3`() {
        val report = ReplayReviewDecisionRunner(
            registry = buildRegistry(),
            manifest = loadDeckManifest(),
            outerCommit = "outer-test",
            argentumCommit = "argentum-test",
        ).run(
            particles = 1,
            simulations = 1,
            maxPolicyDecisions = 1,
        )

        assertEquals(2, report.results.size)
        assertEquals(setOf("production-visible-v2", "experimental-tactical-v3"), report.results.map { it.policyId }.toSet())
        report.results.forEach { result ->
            assertEquals(6, result.candidates.size)
            assertEquals(
                result.chosenGrade,
                result.candidates.single { it.signature == result.chosenCandidateSignature }.grade,
            )
            assertEquals(ReplayReviewDecisionRunner.SNAPSHOT_EVALUATION_LIFECYCLE, result.evaluationLifecycle)
            assertEquals(1, result.actualSimulations)
        }
    }

    @Test
    fun `authenticated case rejects an incomplete tiering`() {
        val source = replayReviewDraftSourceForTest()
        assertFailsWith<IllegalArgumentException> {
            AuthenticatedReplayReviewDecisionCase(
                id = "incomplete",
                source = source,
                safeBundleSha256 = "a".repeat(64),
                canonicalReplaySha256 = "b".repeat(64),
                gameSeed = 1,
                searchBaseSeed = 1,
                startingPlayerIndex = 0,
                actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
                semanticPrefix = emptyList(),
                candidates = listOf(ReplayReviewDraftCandidate("only", "Only", null)),
                reviewerId = "owner",
                reviewerJudgment = "visible facts",
                authenticatedAtUtc = "2026-08-28T00:00:00Z",
                intakeBindingSha256 = "0".repeat(64),
            )
        }
    }

    @Test
    fun `trusted intake binding rejects a changed reviewer grade or judgment`() {
        val unsigned = AuthenticatedReplayReviewDecisionCase(
            id = "sealed",
            source = replayReviewDraftSourceForTest(),
            safeBundleSha256 = "a".repeat(64),
            canonicalReplaySha256 = "b".repeat(64),
            gameSeed = 1,
            searchBaseSeed = 1,
            startingPlayerIndex = 0,
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            semanticPrefix = emptyList(),
            candidates = listOf(
                ReplayReviewDraftCandidate("preferred", "Preferred", ReplayReviewDecisionGrade.PREFERRED),
                ReplayReviewDraftCandidate("bad", "Bad", ReplayReviewDecisionGrade.UNACCEPTABLE),
            ),
            reviewerId = "owner",
            reviewerJudgment = "visible facts",
            authenticatedAtUtc = "2026-08-28T00:00:00Z",
            intakeBindingSha256 = "0".repeat(64),
        )
        val sealed = unsigned.copy(intakeBindingSha256 = unsigned.expectedIntakeBinding())
        sealed.requireIntakeBinding()
        assertFailsWith<IllegalArgumentException> {
            sealed.copy(reviewerJudgment = "changed after intake").requireIntakeBinding()
        }
        assertFailsWith<IllegalArgumentException> {
            sealed.copy(
                candidates = sealed.candidates.mapIndexed { index, candidate ->
                    candidate.copy(grade = if (index == 0) ReplayReviewDecisionGrade.UNACCEPTABLE else ReplayReviewDecisionGrade.PREFERRED)
                },
            ).requireIntakeBinding()
        }
    }
}

private fun replayReviewDraftSourceForTest() = ReplayReviewDraftSource(
    runIdentity = "test-run",
    gameId = "2251957f-c6dc-4b39-9cfd-4963cf99bb71",
    perspectivePlayerId = "p0",
    safeBundleSha256 = "a".repeat(64),
    outerCommit = "outer",
    argentumCommit = "argentum",
    frameIndex = 4,
    decisionIndex = 4,
    informationStateDigest = "information",
    historyCommitment = ReplayReviewHistoryCommitment("sha256-chain-v1", 0, "c".repeat(64)),
    candidateSchemaVersion = 2,
    proposalVersion = "proposal",
    chosenSignature = "choice",
)
