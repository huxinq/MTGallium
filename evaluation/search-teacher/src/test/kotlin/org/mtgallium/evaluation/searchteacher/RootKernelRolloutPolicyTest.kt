package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.*

@Tag("public-source")
class RootKernelRolloutPolicyTest {
    @Test
    fun `casting context uses raw frozen argmax and preserves acting-player information`() {
        val world = castingWorld()
        val actor = requireNotNull(world.actorToAct())
        val information = world.informationState(actor)
        val menu = world.expandChoicesForPolicyAdmission().candidates
        assertTrue(menu.any { it.operationFamily == SemanticOperationFamily.CAST_SPELL })
        val features = rootActionKernelFeatures(information, menu)
        val model = RootActionKernelModel(ridge = .001, centers = features,
            coefficients = List(menu.size) { (it + 1) * 100.0 })
        val policy = RootKernelRolloutPolicy(model, "synthetic-fit", "a".repeat(64))
        for (choices in listOf(menu, menu.reversed())) {
            val scores = CompiledRootActionKernel(model).scores(rootActionKernelFeatures(information, choices))
            val expected = choices[scores.indices.maxBy { scores[it] }]
            val actual = policy.select(information, choices, 8L, 9L)
            assertEquals(expected, actual.choice)
            assertEquals(policy.id, actual.diagnostic.selectedComponentId)
            assertNull(actual.diagnostic.replacement)
        }
        assertEquals(information, world.informationState(actor))
        assertFails { policy.distribution(information.copy(actingPlayerId = "other"), menu, 0L) }
        assertFails { policy.distribution(information, menu + menu.first(), 0L) }

        val tied = RootKernelRolloutPolicy(model.copy(coefficients = List(menu.size) { 0.0 }), "tie", "b".repeat(64))
        assertEquals(menu.first(), tied.select(information, menu, 1L, 2L).choice)
        assertEquals(menu.last(), tied.select(information, menu.reversed(), 1L, 2L).choice)
        assertNotEquals(policy.behaviorSpecification, tied.behaviorSpecification)
    }

    @Test
    fun `non-casting contexts delegate production choice and retain missing-annotation replacement`() {
        val world = world(skipMulligans = false)
        val actor = requireNotNull(world.actorToAct())
        val information = world.informationState(actor)
        val plain = world.expandChoices().candidates
        val annotated = world.expandChoicesWithPolicyAnnotations().candidates
        assertTrue(plain.none { it.operationFamily == SemanticOperationFamily.CAST_SPELL })
        val f = RootActionKernelFeatures(RootActionKernelVector(listOf(0), listOf(1.0)),
            RootActionKernelVector(listOf(0), listOf(1.0)))
        val production = SearchTeacherSearchFactory.rootRolloutPolicy()
        val policy = RootKernelRolloutPolicy(RootActionKernelModel(ridge = .001, centers = listOf(f),
            coefficients = listOf(100.0)), "synthetic-fit", "a".repeat(64), production)
        assertEquals(production.requiresPolicyAnnotations, policy.requiresPolicyAnnotations)
        for (menu in listOf(plain, annotated)) {
            assertEquals(production.distribution(information, menu, 10L).entries,
                policy.distribution(information, menu, 10L).entries)
            val expected = production.select(information, menu, 10L, 11L)
            val actual = policy.select(information, menu, 10L, 11L)
            assertEquals(expected.choice, actual.choice)
            assertEquals(expected.diagnostic.copy(declaredPolicyId = policy.id), actual.diagnostic)
            assertEquals(production.usedFallback(menu), policy.usedFallback(menu))
        }
        assertTrue(requireNotNull(policy.select(information, plain, 10L, 11L).diagnostic.replacement).invalidatesEvidence)
        assertNull(policy.select(information, annotated, 10L, 11L).diagnostic.replacement)
    }

    @Test
    fun `fast continuation uses casting argmax and declared cheap component elsewhere`() {
        val cast = castingWorld()
        val actor = requireNotNull(cast.actorToAct())
        val information = cast.informationState(actor)
        val menu = cast.expandChoices().candidates
        val features = rootActionKernelFeatures(information, menu)
        val model = RootActionKernelModel(ridge = .001, centers = features,
            coefficients = List(menu.size) { (it + 1) * 100.0 })
        val fast = FastKernelRolloutPolicy(model, "synthetic-fit", "a".repeat(64))
        assertFalse(fast.requiresPolicyAnnotations)
        assertFalse(fast.requiresProductionAdmission)
        assertFalse(fast.behaviorSpecification.requiresProductionAdmission)
        for (choices in listOf(menu, menu.reversed())) {
            val scores = CompiledRootActionKernel(model).scores(rootActionKernelFeatures(information, choices))
            assertEquals(choices[scores.indices.maxBy { scores[it] }], fast.select(information, choices, 8L, 9L).choice)
        }
        assertFails { fast.distribution(information.copy(actingPlayerId = "other"), menu, 0L) }
        val mulligan = world(skipMulligans = false)
        val viewer = requireNotNull(mulligan.actorToAct())
        val mulliganInformation = mulligan.informationState(viewer)
        val mulliganMenu = mulligan.expandChoices().candidates
        val semantic = SemanticHeuristicOpponentPolicy(requiresProductionAdmission = false)
        assertEquals(semantic.distribution(mulliganInformation, mulliganMenu, 1L).entries,
            fast.distribution(mulliganInformation, mulliganMenu, 1L).entries)
        val selected = fast.select(mulliganInformation, mulliganMenu, 1L, 2L)
        assertEquals(semantic.id, selected.diagnostic.selectedComponentId)
        assertNull(selected.diagnostic.replacement)
        val baseline = RootKernelRolloutPolicy(model, "synthetic-fit", "a".repeat(64))
        assertNotEquals(baseline.behaviorSpecification, fast.behaviorSpecification)
        assertNotEquals(SemanticHeuristicOpponentPolicy().behaviorSpecification, semantic.behaviorSpecification)
        assertFalse("requiresProductionAdmission" in evidenceJson.encodeToString(
            OpponentPolicyBehaviorSpecification.serializer(), baseline.behaviorSpecification))
        assertTrue("requiresProductionAdmission" in evidenceJson.encodeToString(
            OpponentPolicyBehaviorSpecification.serializer(), fast.behaviorSpecification))
    }

    private fun castingWorld(): ArgentumSearchWorld {
        val world = world(skipMulligans = true)
        repeat(16) {
            val menu = world.expandChoices().candidates
            if (menu.any { it.operationFamily == SemanticOperationFamily.CAST_SPELL }) return world
            val selected = menu.firstOrNull { it.operationFamily == SemanticOperationFamily.PLAY_LAND }
                ?: menu.firstOrNull { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
                ?: error("Unexpected setup decision")
            assertTrue(world.step(selected).accepted)
        }
        error("Synthetic game did not reach a casting decision")
    }

    private fun world(skipMulligans: Boolean): ArgentumSearchWorld {
        val deck = SearchTeacherDeckManifest("synthetic", "Synthetic", "synthetic", "2026-09-07",
            "public synthetic fixture", mapOf("Mountain" to 30, "Hired Claw" to 30), emptyMap())
        val environment = GameEnvironment.create(buildRegistry()).also { env ->
            env.reset(GameConfig(players = listOf(PlayerConfig("p0", deck.deck()), PlayerConfig("p1", deck.deck())),
                skipMulligans = skipMulligans, useHandSmoother = false, startingPlayerIndex = 0, seed = 17L))
        }
        return ArgentumSearchWorld.create(environment, "synthetic-kernel-rollout", 99L, effectiveSetupSeed = 17L,
            expander = UnifiedSemanticExpander(actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1),
            knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck))
    }
}
