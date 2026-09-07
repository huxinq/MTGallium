package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import kotlinx.serialization.encodeToString
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.*

@Tag("public-source")
class RootRolloutSelectionScreenTest {
    @Test
    fun `production rollout selection requests annotation and leaves the root and search counts unchanged`() {
        val world = world()
        val actor = requireNotNull(world.actorToAct())
        val information = world.informationState(actor)
        val menu = world.expandChoices().candidates
        val policy = SearchTeacherSearchFactory.rootRolloutPolicy()
        assertTrue(policy.usedFallback(menu))
        val expected = policy.select(information, world.expandChoicesWithPolicyAnnotations().candidates, 71L, 99L)
        assertNull(expected.diagnostic.replacement)
        val actual = selectPositionScreenRollout(world, actor, menu, policy, 71L)
        assertEquals(expected.choice.signature, actual.choice.signature)
        assertEquals(expected.diagnostic, actual.diagnostic)
        assertTrue(actual.choice in menu)
        assertEquals(information, world.informationState(actor))

        val row = PositionBankScreenRow("root", "control", "unused", 0,
            PositionBankScreenDisposition.ROLLOUT_SELECTED, 0.0, 0.0,
            chosen = actual.choice, rolloutPolicyDecision = actual.diagnostic)
        assertNull(row.searchDiagnostics)
        assertNull(row.searchRootValue)
        assertTrue(row.candidateStatistics.isEmpty() && row.candidateSettlementCounts.isEmpty())
        assertEquals(row, evidenceJson.decodeFromString<PositionBankScreenRow>(evidenceJson.encodeToString(row)))
        assertFalse("rolloutPolicyDecision" in evidenceJson.encodeToString(row.copy(rolloutPolicyDecision = null)))
    }

    @Test
    fun `missing production annotation and a changed admission menu remain refusals`() {
        val world = world()
        val actor = requireNotNull(world.actorToAct())
        val menu = world.expandChoices().candidates
        val policy = SearchTeacherSearchFactory.rootRolloutPolicy()
        val unannotated = object : PolicyAnnotatedSearchWorld by world {
            override fun expandChoicesWithPolicyAnnotations() = world.expandChoices()
        }
        val row = PositionBankScreenRow("root", "control", "unused", 0,
            PositionBankScreenDisposition.SCORED, 0.0, 0.0)
        val refused = screenPositionBankRepetition(row, 12.0) {
            selectPositionScreenRollout(unannotated, actor, menu, policy, 7L)
            error("A missing required annotation must not reach this point")
        }
        assertEquals(PositionBankScreenDisposition.REFUSED, refused.disposition)
        assertTrue(requireNotNull(refused.diagnostic).contains("evidence-invalidating policy replacement"))
        assertNull(refused.chosen)
        assertEquals(12.0, refused.reconstructionMillis)
        assertFailsWith<IllegalArgumentException> {
            selectPositionScreenRollout(world, actor, menu.take(1), policy, 7L)
        }
    }

    private fun world(): ArgentumSearchWorld {
        val deck = SearchTeacherDeckManifest("synthetic", "Synthetic", "synthetic", "2026-09-07",
            "public synthetic fixture", mapOf("Mountain" to 60), emptyMap())
        val environment = GameEnvironment.create(buildRegistry()).also { env ->
            env.reset(GameConfig(players = listOf(PlayerConfig("p0", deck.deck()), PlayerConfig("p1", deck.deck())),
                skipMulligans = false, useHandSmoother = false, startingPlayerIndex = 0, seed = 17L))
        }
        return ArgentumSearchWorld.create(environment, "synthetic-rollout-screen", 99L, effectiveSetupSeed = 17L,
            expander = UnifiedSemanticExpander(actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1),
            knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck))
    }
}
