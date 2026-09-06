package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.SearchReuseConfig
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySelection
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

@Tag("public-source")
class PositionBankSearchRepetitionTest {
    @Test
    fun `unchanged reconstructed session gives fresh-session results for each repetition seed`() {
        // Explicit public synthetic fixture; no frozen research deck or private evidence resource.
        val manifest = SearchTeacherDeckManifest(
            id = "synthetic-position-bank-repetitions", name = "Synthetic repetition fixture",
            format = "synthetic", publishedDate = "2026-09-06", source = "public synthetic fixture",
            mainDeck = mapOf("Mountain" to 40, "Shock" to 4, "Lightning Bolt" to 4,
                "Goblin Piker" to 4, "Raging Goblin" to 4, "Monastery Swiftspear" to 4),
            sideboard = emptyMap(),
        )
        val registry = buildRegistry()
        val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)
        val gameId = "position-bank-repetition-test"
        val parameters = SearchTeacherPolicyParameters(
            particles = 4, simulations = 8, maxPolicyDecisions = 4, explorationConstant = 1.4,
            leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
            actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
            beliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
            beliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
            baseSeed = 99L, profileId = "position-bank-repetition-test-v1",
            searchReuse = SearchReuseConfig(enabled = false), wallClockBudgetMillis = null,
        )
        fun fresh(): Pair<ArgentumSearchWorld, SearchTeacherPolicySession> {
            val environment = GameEnvironment.create(registry).also { env ->
                env.reset(GameConfig(
                    players = listOf(PlayerConfig("Player 0", manifest.deck()), PlayerConfig("Player 1", manifest.deck())),
                    skipMulligans = false, useHandSmoother = false, startingPlayerIndex = 0, seed = 17L,
                ))
            }
            val world = ArgentumSearchWorld.create(environment = environment, gameId = gameId,
                seedBase = 99L, effectiveSetupSeed = 17L,
                expander = UnifiedSemanticExpander(actionSpaceProfile = parameters.actionSpaceProfile), knownDecks = knownDecks)
            assertEquals(2, world.expandChoices().candidates.size)
            return world to SearchTeacherPolicySession(root = world, viewer = "p0", knownDecks = knownDecks,
                parameters = parameters, opponentPolicy = defaultMonoRedOpponentPolicy(), gameId = gameId)
        }
        fun counters(session: SearchTeacherPolicySession) = listOf(session.beliefReconditionings,
            session.beliefParticleDepletions, session.beliefLowEssUpdates, session.beliefInvalidWeights)

        val (world, session) = fresh()
        val initialDigest = world.informationState("p0").informationStateDigest
        val seedA = 101L
        val seedB = 202L
        val repeatedA = session.select(world, "p0", seedA)
        assertEquals(initialDigest, world.informationState("p0").informationStateDigest)
        val countersAfterA = counters(session)
        val lifecycleAfterA = session.beliefLifecycleDiagnostics
        val diagnosticsAfterA = session.latestBeliefDiagnostics
        val historyAfterA = session.beliefDiagnosticsHistory.toList()
        val repeatedB = session.select(world, "p0", seedB)
        assertEquals(initialDigest, world.informationState("p0").informationStateDigest)
        assertEquals(countersAfterA, counters(session))
        assertEquals(lifecycleAfterA, session.beliefLifecycleDiagnostics)
        assertEquals(diagnosticsAfterA, session.latestBeliefDiagnostics)
        assertEquals(historyAfterA, session.beliefDiagnosticsHistory)

        val (worldA, sessionA) = fresh()
        val (worldB, sessionB) = fresh()
        assertEquals(initialDigest, worldA.informationState("p0").informationStateDigest)
        assertEquals(initialDigest, worldB.informationState("p0").informationStateDigest)
        assertEquivalent(sessionA.select(worldA, "p0", seedA), repeatedA)
        assertEquivalent(sessionB.select(worldB, "p0", seedB), repeatedB)
    }

    private fun assertEquivalent(expected: SearchTeacherPolicySelection, actual: SearchTeacherPolicySelection) {
        assertEquals(SearchTeacherSelectionKind.SEARCHED, expected.kind)
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.choice, actual.choice)
        val expectedSearch = assertNotNull(expected.search)
        val actualSearch = assertNotNull(actual.search)
        assertEquals(expectedSearch.rootValue.toBits(), actualSearch.rootValue.toBits())
        assertEquals(expectedSearch.candidates, actualSearch.candidates)
        assertEquals(expectedSearch.candidateSettlementCounts, actualSearch.candidateSettlementCounts)
        assertTrue(actualSearch.candidates.size > 1)
        assertEquals(8, actualSearch.diagnostics.simulations)
        assertEquals(0, actualSearch.diagnostics.reusedSimulations)
        assertNull(actualSearch.diagnostics.wallClockBudgetMillis)
        // Only measured evaluator duration is excluded; all work, value and policy audits must match.
        assertEquals(expectedSearch.diagnostics.copy(evaluatorNanos = 0), actualSearch.diagnostics.copy(evaluatorNanos = 0))
    }
}
