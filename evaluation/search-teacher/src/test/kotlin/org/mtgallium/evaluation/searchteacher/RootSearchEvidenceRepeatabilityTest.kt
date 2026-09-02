package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

class RootSearchEvidenceRepeatabilityTest {
    @Test
    fun `summary keeps action allocation rank value and support evidence separate`() {
        val trials = listOf(
            trial(0, "a", candidate("a", 40, 0.40), candidate("b", 24, 0.20)),
            trial(1, "a", candidate("a", 36, 0.30), candidate("b", 28, 0.10)),
            trial(2, "b", candidate("a", 30, 0.20), candidate("b", 34, 0.25)),
        )

        val summary = summarizeRootEvidenceTrials(root(), 64, trials)

        assertEquals(2, summary.distinctSelectedActions)
        assertEquals(2.0 / 3.0, summary.modalFraction)
        assertEquals(1.0 / 3.0, summary.pairwiseSelectedActionAgreement)
        assertEquals(6, summary.supportCounts.sixteenOrMoreVisits)
        assertEquals(0, summary.supportCounts.unvisited)
        assertEquals(-1.0 / 3.0, summary.rankStability.single {
            it.minimumVisitsInBothRepetitions == 8
        }.meanKendallTauB)
        assertEquals(3, summary.rankStability.single {
            it.minimumVisitsInBothRepetitions == 8
        }.comparableRepetitionPairs)
    }

    @Test
    fun `unvisited sentinel is support evidence but not a comparable value rank`() {
        val trials = listOf(
            trial(0, "a", candidate("a", 64, 0.40), candidate("b", 0, 0.0)),
            trial(1, "a", candidate("a", 60, 0.30), candidate("b", 4, 0.10)),
        )

        val summary = summarizeRootEvidenceTrials(root(), 64, trials)

        assertEquals(1, summary.supportCounts.unvisited)
        assertEquals(1, summary.supportCounts.fourToSevenVisits)
        assertEquals(0, summary.rankStability.single {
            it.minimumVisitsInBothRepetitions == 1
        }.comparableRepetitionPairs)
        assertNull(summary.rankStability.single {
            it.minimumVisitsInBothRepetitions == 1
        }.meanKendallTauB)
        assertEquals(0, summary.candidates.single { it.signature == "b" }.minimumVisits)
    }

    private fun root() = RootEvidenceRoot(
        rootId = "root",
        panelIndex = 0,
        sourceGameId = "game",
        decisionIndex = 1,
        actor = "p0",
        regime = FrozenRootRegime.ORDINARY_MIDGAME,
        decisionFamily = RootEvidenceDecisionFamily.ORDINARY_ACTION,
        tactical = false,
        turnNumber = 2,
        phase = "PRECOMBAT_MAIN",
        step = "MAIN",
        visibleStackDepth = 0,
        opponentHandSize = 5,
        opponentLibrarySize = 50,
        initialCandidateCount = 2,
        fullProfileCandidateCount = 2,
        initialUnexpandedProfileCandidates = 0,
        initialProfileExhaustive = true,
        fullProfileExhaustive = true,
        initialRulesExhaustive = false,
        initialOmissionReasons = emptySet(),
        initialOperationFamilies = mapOf(
            SemanticOperationFamily.PASS_PRIORITY to 1,
            SemanticOperationFamily.PLAY_LAND to 1,
        ),
        informationStateDigest = "information",
        semanticPrefixDigest = "prefix",
        replayPath = "replay",
        replaySha256 = "sha",
        gameSeed = 1L,
        sourceSearchBaseSeed = 2L,
    )

    private fun trial(
        repetition: Int,
        chosen: String,
        vararg candidates: RootEvidenceCandidateOutcome,
    ): RootEvidenceTrial {
        val rootValue = candidates.sumOf { it.visits * it.meanValue } / candidates.sumOf { it.visits }
        return RootEvidenceTrial(
            rootId = "root",
            panelIndex = 0,
            budget = 64,
            repetition = repetition,
            searchIdentity = "search-$repetition",
            searchSeed = repetition.toLong(),
            chosenSignature = chosen,
            chosenLabel = chosen,
            rootValue = rootValue,
            candidates = candidates.toList(),
            searchMillis = 1.0,
            beliefAcceptedParticles = 8,
            beliefRejectedParticles = 0,
            beliefEffectiveSampleSize = 8.0,
            beliefEntropy = 2.0,
            beliefFailures = emptyMap(),
            work = RootEvidenceWorkDiagnostics(
                simulations = 64,
                particles = 8,
                nodes = 1,
                maximumDepth = 1,
                exhaustiveNodes = 1,
                nonExhaustiveNodes = 0,
                wideningEvents = 0,
                searchWorldSteps = 64,
                rejectedTransitions = 0,
                evaluatorCalls = 64,
                quiescenceOverflows = 0,
                quiescenceFallbacks = 0,
                rootRolloutDecisions = 0,
                opponentRolloutDecisions = 0,
            ),
        )
    }

    private fun candidate(
        signature: String,
        visits: Int,
        meanValue: Double,
    ) = RootEvidenceCandidateOutcome(
        signature = signature,
        label = signature,
        operationFamily = if (signature == "a") {
            SemanticOperationFamily.PASS_PRIORITY
        } else {
            SemanticOperationFamily.PLAY_LAND
        },
        visits = visits,
        meanValue = meanValue,
        policyProbability = visits / 64.0,
    )
}
