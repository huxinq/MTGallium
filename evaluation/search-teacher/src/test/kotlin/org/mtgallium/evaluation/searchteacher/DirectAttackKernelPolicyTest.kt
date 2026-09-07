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
class DirectAttackKernelPolicyTest {
    @Test fun `actual attack choice can differ from search without visits and fallback retains search`() {
        val deck = SearchTeacherDeckManifest("synthetic", "Synthetic", "synthetic", "2026-09-08",
            "public attack fixture", mapOf("Mountain" to 24, "Monastery Swiftspear" to 36), emptyMap())
        val known = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck)
        val parameters = SearchTeacherRuntimeConfig(
            leaf = LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.ARGENTUM_BOARD_V1)
        ).policyParameters().copy(particles = 1, simulations = 1, maxPolicyDecisions = 1)
        val env = GameEnvironment.create(buildRegistry()).also {
            it.reset(GameConfig(players = listOf(PlayerConfig("p0", deck.deck()), PlayerConfig("p1", deck.deck())),
                skipMulligans = true, useHandSmoother = false, startingPlayerIndex = 0, seed = 17L))
        }
        val world = ArgentumSearchWorld.create(env, "direct-attack-witness", 99L, effectiveSetupSeed = 17L,
            knownDecks = known, expander = UnifiedSemanticExpander(actionSpaceProfile = parameters.actionSpaceProfile))
        var witnessed = false
        for (stepIndex in 0 until 180) {
            val expansion = world.expandChoices()
            if (attackKernelScope(expansion.candidates, expansion.isProfileExhaustive)) {
                val actor = requireNotNull(world.actorToAct())
                val info = world.informationState(actor)
                fun session(policy: DirectRootSelectionPolicy? = null) = SearchTeacherPolicySession(world,
                    actor, known, parameters, defaultMonoRedOpponentPolicy(), "direct-attack-witness",
                    directRootSelectionPolicy = policy)
                val controlSession = session()
                val control = controlSession.select(world, actor, 27L)
                assertEquals(SearchTeacherSelectionKind.SEARCHED, control.kind)
                val features = rootActionKernelFeatures(info, expansion.candidates)
                val controlIndex = expansion.candidates.indexOf(control.choice)
                val preferred = features.indices.first { features[it] != features[controlIndex] }
                val model = RootActionKernelModel(ridge = .001,
                    centers = listOf(features[preferred], features[controlIndex]), coefficients = listOf(10.0, -10.0))
                val direct = DirectAttackKernelPolicy(model, "synthetic-fit", "f".repeat(64))
                val candidateSession = session(direct)
                val fingerprint = world.freshAuthoritativeFingerprintForHost()
                val selected = candidateSession.select(world, actor, 27L)
                assertNotEquals(control.choice, selected.choice)
                assertEquals(SearchTeacherSelectionKind.DIRECT_POLICY_ACTION, selected.kind)
                assertNull(selected.search)
                assertNotEquals(controlSession.policyIdentity, candidateSession.policyIdentity)
                assertEquals(info, world.informationState(actor))
                assertEquals(fingerprint, world.freshAuthoritativeFingerprintForHost())
                assertNull(direct.select({ error("Incomplete menus must not construct information") }, expansion.copy(
                    isExhaustive = false, isProfileExhaustive = false,
                    omissionReasons = setOf(PolicyExpansionOmissionReason.SOURCE_NON_EXHAUSTIVE))))
                val mandatory = expansion.candidates.filter { it.actionIntent.kind != SemanticActionIntentKind.DECLINE_ATTACK }
                assertNull(direct.select({ error("Mandatory menus are out of scope") }, expansion.copy(candidates = mandatory)))
                val large = List(9) { index -> expansion.candidates[index % expansion.candidates.size]
                    .let { original -> SemanticChoice.create(original.kind, original.operationFamily, original.actionIntent,
                        original.display, kotlinx.serialization.json.JsonObject(mapOf("fixture" to
                            kotlinx.serialization.json.JsonPrimitive(index)))) } }
                assertNull(direct.select({ error("Large menus are out of scope") }, expansion.copy(
                    candidates = large, estimatedCandidateCount = 9)))
                val pass = SemanticChoice.create(SemanticChoiceKind.ACTION, SemanticOperationFamily.PASS_PRIORITY,
                    display = SemanticChoiceDisplay("Pass"), canonicalPayload = kotlinx.serialization.json.JsonObject(emptyMap()))
                assertNull(direct.select({ error("Other action menus are out of scope") }, expansion.copy(candidates = listOf(pass))))
                val fallbackSession = session(object : DirectRootSelectionPolicy {
                    override val configurationId = "always-delegate-fixture"
                    override fun select(information: () -> PolicyInformationState, expansion: PolicyExpansion): SemanticChoice? = null
                })
                val fallback = fallbackSession.select(world, actor, 27L)
                assertEquals(control.choice, fallback.choice)
                assertEquals(control.search!!.candidates, fallback.search!!.candidates)
                assertEquals(control.search!!.rootValue, fallback.search!!.rootValue)
                assertEquals(control.search!!.candidateSettlementCounts, fallback.search!!.candidateSettlementCounts)
                val accepted = world.step(selected.choice)
                assertTrue(accepted.accepted)
                candidateSession.observeAccepted(world, actor, selected.choice, stepIndex, accepted.privateToActor)
                witnessed = true
                break
            }
            if (world.actorToAct() == null) break
            assertTrue(world.step(expansion.candidates.maxBy { when (it.operationFamily) {
                SemanticOperationFamily.PLAY_LAND -> 10
                SemanticOperationFamily.CAST_SPELL -> 9
                else -> 0
            } }).accepted)
        }
        assertTrue(witnessed)
    }

    @Test fun `arena binds direct selection and measures every call for both seats`() {
        val deck = SearchTeacherDeckManifest("timing", "Timing", "synthetic", "2026-09-08",
            "public fixture", mapOf("Mountain" to 60), emptyMap())
        val parameters = SearchTeacherRuntimeConfig(leaf = LeafEvaluationConfig(
            LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.ARGENTUM_BOARD_V1)
        ).policyParameters().copy(particles = 1, simulations = 1, maxPolicyDecisions = 1)
        val profile = FrozenSearchProfile(id = "fast-arena-v1", generatedAtUtc = "test", outerCommit = "test",
            argentumCommit = "test", host = "test", particles = 8, simulations = 64, leaf = parameters.leaf,
            actionSpaceProfile = parameters.actionSpaceProfile, maxPolicyDecisions = 1, measuredP95Millis = 0.0,
            tacticalScore = 0.0, standardError = 0.0, calibrationReportHash = "test")
        val direct = object : DirectRootSelectionPolicy {
            override val configurationId = "synthetic-delegation-timing"
            override fun select(information: () -> PolicyInformationState, expansion: PolicyExpansion): SemanticChoice? = null
        }
        val control = ArenaPolicySpec("control", ArenaPolicyKind.SEARCH, parameters = parameters)
        val candidate = control.copy(id = "candidate", directRootSelectionPolicy = direct)
        val arena = SearchTeacherArena(buildRegistry(), deck, profile, 19L, gameDecisionLimit = 12)
        val result = arena.playWithPolicies("00000000-0000-4000-8000-000000009018", 19L, candidate, control)
        assertEquals(GameRunDisposition.STOPPED_LIMIT, result.disposition, result.exception)
        assertEquals(12, result.decisions)
        assertEquals(12, result.seatDiagnostics.values.sumOf { requireNotNull(it.decisionComputationMillis).size })
        result.seatDiagnostics.values.forEach { seat ->
            assertTrue(requireNotNull(seat.decisionComputationMillis).all { it >= 0.0 })
            assertTrue(seat.decisionComputationMillis!!.sum() >= seat.searchLatenciesMillis.sum())
        }
        val empty = PolicyJson.sha256("")
        val source = PolicySourceProvenance(expectedArgentumRevision = "engine",
            outer = PolicySourceTreeState("outer", empty, empty, empty),
            argentum = PolicySourceTreeState("engine", empty, empty, empty))
        assertNotEquals(arena.evidenceBinding(control, null, source).identity,
            arena.evidenceBinding(candidate.copy(id = control.id), null, source).identity)
    }

    @Test fun `saved position plans refuse modes that ignore direct selection`() {
        val fit = RootKernelFitReference("/tmp/synthetic-direct-fit", "research-run-v1-sha256:" + "a".repeat(64), "b".repeat(64))
        val policy = SearchTeacherCalibrationPolicy("direct", 1, 1, 1, 1.4, true, 1.0, directAttackKernelFit = fit)
        val plan = PositionBankScreenPlan(bankDirectory = "/tmp/synthetic-bank", expectedBankIdentity = "bank",
            partition = PositionBankScreenPartition.DEVELOPMENT, mode = PositionBankScreenMode.SEARCH,
            rootLimit = 1, repetitions = 1, policies = listOf(PositionBankScreenPolicy(policy, MonoRedVisibleEvaluatorConfig())))
        assertFailsWith<IllegalArgumentException> { plan.copy(mode = PositionBankScreenMode.FEATURES) }
        assertFailsWith<IllegalArgumentException> { plan.copy(policies = listOf(plan.policies.single().copy(rootKernel = fit))) }
        assertFailsWith<IllegalArgumentException> { policy.copy(directArgentumHeuristic = true) }
    }

    @Test fun `absent direct configuration and decision timing preserve legacy serialization`() {
        val policy = SearchTeacherCalibrationPolicy("legacy", 1, 1, 1, 1.4, true, 1.0)
        val encoded = evidenceJson.encodeToString(SearchTeacherCalibrationPolicy.serializer(), policy)
        assertFalse(encoded.contains("directAttackKernelFit"))
        assertEquals(policy, evidenceJson.decodeFromString(SearchTeacherCalibrationPolicy.serializer(), encoded))
        val seat = ArenaSeatDiagnostics("legacy")
        val seatEncoded = evidenceJson.encodeToString(ArenaSeatDiagnostics.serializer(), seat)
        assertFalse(seatEncoded.contains("decisionComputationMillis"))
        assertNull(evidenceJson.decodeFromString(ArenaSeatDiagnostics.serializer(), seatEncoded).decisionComputationMillis)
    }
}
