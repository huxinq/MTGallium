package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.SemanticHeuristicOpponentPolicy
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig

@Tag("public-source")
class AttackKernelLearningTest {
    @Test fun `new target plans bind the primary bank while retained pilot identity stays unchanged`() {
        val pilot = PositionBankScreenPlan(bankDirectory = "/tmp/pilot-bank", expectedBankIdentity = "pilot-bank",
            partition = PositionBankScreenPartition.DEVELOPMENT, mode = PositionBankScreenMode.TERMINAL_CONTINUATIONS,
            rootLimit = 2, repetitions = 2, policies = listOf(PositionBankScreenPolicy(
                SearchTeacherCalibrationPolicy("incumbent", 8, 56, 16, 1.4, true, 1.0), MonoRedVisibleEvaluatorConfig())),
            rootIds = listOf("old-a", "old-b"), searchSeedDomain = "pilot-seeds",
            terminalContinuation = TerminalRootContinuationConfig(8, maximumTotalContinuations = 25000))
        val main = attackLearningScreenPlan(pilot, CloningComparisonInput("/tmp/primary-bank", "primary-bank"),
            PositionBankScreenPartition.VALIDATION, listOf("new-a"), "new-seeds")
        assertEquals("/tmp/primary-bank", main.bankDirectory)
        assertEquals("primary-bank", main.expectedBankIdentity)
        assertEquals(listOf("new-a"), main.rootIds)
        assertEquals("new-seeds", main.searchSeedDomain)
        assertEquals(pilot.policies, main.policies)
        assertEquals(pilot.terminalContinuation, main.terminalContinuation)
        assertEquals("pilot-bank", pilot.expectedBankIdentity)
        assertEquals(listOf("old-a", "old-b"), pilot.rootIds)
    }
    @Test fun `validation requires both repetitions and six positive groups without group duplication`() {
        fun rows(positive: Int, second: Double = .1) = (0 until 48).map { i ->
            AttackValidationRow("r$i", "g${i / 4}", "a", listOf(.5, .5),
                listOf(if (i / 4 < positive) 1.0 else -.01, if (i / 4 < positive) second else -.01))
        }
        assertTrue(attackValidationResult(rows(6)).passed)
        assertFalse(attackValidationResult(rows(5)).passed)
        assertFalse(attackValidationResult(rows(12, -.01)).passed)
        assertFails { attackValidationResult(rows(12).map { it.copy(group = "g0") }) }
    }

    @Test fun `reachable attack and decline features select through the complete menu witness only`() {
        val cards = SearchTeacherDeckManifest("synthetic", "Synthetic", "synthetic", "2026-09-08",
            "public attack fixture", mapOf("Mountain" to 24, "Monastery Swiftspear" to 36), emptyMap()).deck()
        val env = GameEnvironment.create(buildRegistry()).also {
            it.reset(GameConfig(players = listOf(PlayerConfig("p0", cards), PlayerConfig("p1", cards)),
                skipMulligans = true, useHandSmoother = false, startingPlayerIndex = 0, seed = 17L))
        }
        val world = ArgentumSearchWorld.create(env, "attack-public-witness", 99L, effectiveSetupSeed = 17L,
            expander = UnifiedSemanticExpander(actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1))
        val incumbent = SemanticHeuristicOpponentPolicy(requiresProductionAdmission = false)
        var witnessed = false
        for (step in 0 until 180) {
            val expansion = world.expandChoices()
            if (attackKernelScope(expansion.candidates, expansion.isProfileExhaustive)) {
                val info = world.informationState(requireNotNull(world.actorToAct()))
                val menu = expansion.candidates
                val features = rootActionKernelFeatures(info, menu)
                assertEquals(menu.size, features.distinct().size)
                assertFalse(attackKernelScope(menu, false))
                val mandatory = menu.filter { it.actionIntent.kind != SemanticActionIntentKind.DECLINE_ATTACK }
                assertFalse(attackKernelScope(mandatory, true))
                val preferred = menu.indexOfFirst { it.actionIntent.kind != SemanticActionIntentKind.DECLINE_ATTACK }
                val model = RootActionKernelModel(ridge = .001, centers = features,
                    coefficients = features.indices.map { if (it == preferred) 10.0 else 0.0 })
                val policy = AttackKernelRolloutPolicy(model, "synthetic-fit", "f".repeat(64), incumbent)
                val selected = policy.selectForExpansion({ info }, menu, true, 37L, 19L)
                assertEquals(menu[preferred], selected.choice)
                assertEquals(policy.id, selected.diagnostic.selectedComponentId)
                val fallback = policy.selectForExpansion({ error("Incomplete menu must retain lazy incumbent") }, menu, false, 37L, 19L)
                val expected = incumbent.selectFromCandidates(menu, 37L, 19L)
                assertEquals(expected.copy(diagnostic = expected.diagnostic.copy(declaredPolicyId = policy.id)), fallback)
                assertFails { policy.distribution(info, menu, 37L) }
                assertTrue(world.step(selected.choice).accepted)
                witnessed = true
                break
            }
            if (world.actorToAct() == null) break
            val choice = expansion.candidates.maxBy { when (it.operationFamily) {
                SemanticOperationFamily.PLAY_LAND -> 10
                SemanticOperationFamily.CAST_SPELL -> 9
                else -> 0
            } }
            assertTrue(world.step(choice).accepted)
        }
        assertTrue(witnessed, "The deterministic public game must reach a genuine attack decision")
    }
}
