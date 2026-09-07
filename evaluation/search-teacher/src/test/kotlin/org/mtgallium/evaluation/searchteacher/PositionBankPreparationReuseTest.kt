package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.InformationSetSearchResult
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

@Tag("public-source")
class PositionBankPreparationReuseTest {
    private val policy = SearchTeacherCalibrationPolicy("synthetic", 2, 8, 4, 1.4, false, 1.0)
    private val parameters = policy.parameters(99L)

    @Test
    fun `repeated searches on a prepared sequential root match fresh reconstruction without retained visits`() {
        val (world, session) = prepare()
        val information = world.informationState("p0")
        val menu = world.expandChoices().candidates
        val before = session.beliefBatch(world)
        val beforeInformation = before.particles.map { it.value.informationState("p0") }
        assertTrue(information.history.isNotEmpty())
        assertTrue(menu.size > 1)
        for (seed in listOf(101L, 203L, 101L)) {
            val (fresh, freshSession) = prepare()
            val expected = assertNotNull(freshSession.select(fresh, "p0", seed).search)
            val actual = assertNotNull(session.select(world, "p0", seed).search)
            assertSearchEqual(expected, actual)
            assertEquals(8, actual.diagnostics.freshSimulations)
            assertEquals(0, actual.diagnostics.reusedSimulations)
            assertEquals(0, actual.diagnostics.retainedTraceCount)
            assertEquals(information, world.informationState("p0"))
            assertEquals(menu, world.expandChoices().candidates)
            val after = session.beliefBatch(world)
            assertEquals(before.particles.map { it.weight }, after.particles.map { it.weight })
            assertEquals(beforeInformation, after.particles.map { it.value.informationState("p0") })
        }
    }

    @Test
    fun `conditional references from reused preparation match fresh roots in either repetition order`() {
        val (world, session) = prepare()
        val information = world.informationState("p0")
        val menu = world.expandChoices().candidates
        for (seed in listOf(203L, 101L, 203L)) {
            val (fresh, freshSession) = prepare()
            val expectedBelief = freshSession.beliefBatch(fresh)
            val actualBelief = session.beliefBatch(world)
            val expectedSearch = SearchTeacherSearchFactory.create(parameters.searchConfig(), defaultMonoRedOpponentPolicy())
            val actualSearch = SearchTeacherSearchFactory.create(parameters.searchConfig(), defaultMonoRedOpponentPolicy())
            for (choice in menu) {
                val expected = expectedSearch.estimateRootAction("p0", expectedBelief, choice.signature, seed)
                val actual = actualSearch.estimateRootAction("p0", actualBelief, choice.signature, seed)
                assertEquals(expected.action, actual.action)
                assertEquals(expected.meanBackedValue, actual.meanBackedValue)
                assertEquals(expected.visits, actual.visits)
                assertEquals(expected.settlementCounts, actual.settlementCounts)
                assertEquals(8, actual.diagnostics.freshSimulations)
                assertEquals(0, actual.diagnostics.reusedSimulations)
                assertEquals(0, actual.diagnostics.retainedTraceCount)
            }
            assertEquals(information, world.informationState("p0"))
        }
    }

    @Test
    fun `refused searches retain preparation accounting while failed preparation claims no reuse`() {
        for (repetition in 0..1) {
            val scored = PositionBankScreenRow("root", "policy", "evaluator", repetition,
                PositionBankScreenDisposition.SCORED, 0.0, 0.0)
            val refusedSearch = screenPositionBankRepetition(scored, 12.0) { error("seed-specific search failure") }
            assertEquals(PositionBankScreenDisposition.REFUSED, refusedSearch.disposition)
            assertEquals(if (repetition == 0) 12.0 else 0.0, refusedSearch.reconstructionMillis)
            assertEquals(repetition > 0, refusedSearch.reusedRootPreparation)
            val failedPreparation = screenPositionBankRepetition(scored, null) { error("preparation failure") }
            assertEquals(PositionBankScreenDisposition.REFUSED, failedPreparation.disposition)
            assertNull(failedPreparation.reconstructionMillis)
            assertFalse(failedPreparation.reusedRootPreparation)
        }
    }

    private fun assertSearchEqual(expected: InformationSetSearchResult, actual: InformationSetSearchResult) {
        assertEquals(expected.chosen, actual.chosen)
        assertEquals(expected.rootValue, actual.rootValue)
        assertEquals(expected.candidates, actual.candidates)
        assertEquals(expected.candidateSettlementCounts, actual.candidateSettlementCounts)
        assertEquals(expected.diagnostics.evaluatorOutputChecksum, actual.diagnostics.evaluatorOutputChecksum)
    }

    private fun prepare(): Pair<ArgentumSearchWorld, SearchTeacherPolicySession> {
        val deck = SearchTeacherDeckManifest("synthetic", "Synthetic", "synthetic", "2026-09-07",
            "public synthetic fixture", mapOf("Mountain" to 40, "Burst Lightning" to 20), emptyMap())
        val knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck)
        val environment = GameEnvironment.create(buildRegistry()).also { env ->
            env.reset(GameConfig(players = listOf(PlayerConfig("p0", deck.deck()), PlayerConfig("p1", deck.deck())),
                skipMulligans = false, useHandSmoother = false, startingPlayerIndex = 0, seed = 17L))
        }
        val world = ArgentumSearchWorld.create(environment, "synthetic-root-reuse", 99L,
            effectiveSetupSeed = 17L, expander = UnifiedSemanticExpander(actionSpaceProfile = parameters.actionSpaceProfile),
            knownDecks = knownDecks)
        val session = SearchTeacherPolicySession(world, "p0", knownDecks, parameters,
            defaultMonoRedOpponentPolicy(), "synthetic-root-reuse")
        // Replay an accepted mulligan, exercising remembered history and sequential belief updates.
        val choice = world.expandChoices().candidates.single { it.actionIntent.kind == SemanticActionIntentKind.TAKE_MULLIGAN }
        val step = world.step(choice)
        assertTrue(step.accepted, step.diagnostic)
        session.observeAccepted(world, "p0", choice, 0, step.privateToActor)
        assertEquals("p0", world.actorToAct())
        return world to session
    }
}
