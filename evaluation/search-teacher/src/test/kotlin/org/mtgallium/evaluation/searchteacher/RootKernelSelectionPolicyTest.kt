package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.*
import org.mtgallium.research.run.*

@Tag("public-source")
class RootKernelSelectionPolicyTest {
    private val deck = SearchTeacherDeckManifest("synthetic", "Synthetic", "synthetic", "2026-09-07",
        "public synthetic fixture", mapOf("Mountain" to 60), emptyMap())

    @Test fun `frozen kernel uses safe supplied menu and saturates scores without changing represented state`() {
        val world = world()
        val info = world.informationState(requireNotNull(world.actorToAct()))
        val menu = world.expandChoices().candidates
        val features = rootActionKernelFeatures(info, menu)
        val model = RootActionKernelModel(ridge = .001, centers = features, coefficients = List(menu.size) { if (it == 0) 100.0 else -100.0 })
        val policy = fixture(model).load()
        assertTrue(COMPILED_ROOT_ACTION_KERNEL_ID in policy.configurationId)
        val expected = features.map { model.score(it).coerceIn(-1.0, 1.0) }
        assertTrue(expected.any { it == 1.0 || it == -1.0 })
        assertEquals(menu.indices.associate { menu[it].signature to expected[it] }, policy.scores(info, menu))
        assertEquals(info.informationStateDigest, world.informationState(requireNotNull(world.actorToAct())).informationStateDigest)
        assertEquals(menu.map { it.signature }.toSet(), policy.scores(info, menu.reversed()).keys)
        assertFails { policy.scores(info, menu + menu.first()) }
        assertFails { policy.scores(info.copy(actingPlayerId = "other"), menu) }
    }

    @Test fun `production session supplies actual safe root once and zero guidance preserves real engine search`() {
        val parameters = SearchTeacherCalibrationPolicy("synthetic", 2, 4, 2, 1.4, false, 1.0).parameters(99L)
        fun select(provider: RootSelectionPolicy?): Pair<SearchTeacherPolicySession, SearchTeacherPolicySelection> {
            val world = world()
            val actor = requireNotNull(world.actorToAct())
            val session = SearchTeacherPolicySession(world, actor, mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck),
                parameters, defaultMonoRedOpponentPolicy(), "synthetic-root-kernel", rootSelectionPolicy = provider)
            return session to session.select(world, actor, 812L)
        }
        val expectedWorld = world()
        val expectedInfo = expectedWorld.informationState(requireNotNull(expectedWorld.actorToAct()))
        var calls = 0
        val zero = object : RootSelectionPolicy {
            override val configurationId = "synthetic-zero"
            override fun scores(information: PolicyInformationState, candidates: List<SemanticChoice>): Map<String, Double> {
                calls++
                assertEquals(expectedInfo, information)
                assertEquals(expectedWorld.expandChoices().candidates, candidates)
                return candidates.associate { it.signature to 0.0 }
            }
        }
        val (plainSession, plain) = select(null)
        val (guidedSession, guided) = select(zero)
        assertEquals(1, calls)
        val plainSearch = requireNotNull(plain.search)
        val guidedSearch = requireNotNull(guided.search)
        assertEquals(plainSearch.copy(diagnostics = plainSearch.diagnostics.copy(evaluatorNanos = 0)),
            guidedSearch.copy(diagnostics = guidedSearch.diagnostics.copy(evaluatorNanos = 0, rootSelectionGuidance = null)))
        assertNotEquals(plainSession.policyIdentity, guidedSession.policyIdentity)
        assertEquals(plainSession.behaviorSpecification, guidedSession.behaviorSpecification.copy(rootSelectionGuidanceId = null))
        val info = expectedInfo
        val menu = expectedWorld.expandChoices().candidates
        val model = RootActionKernelModel(ridge = .001, centers = rootActionKernelFeatures(info, menu),
            coefficients = List(menu.size) { if (it == 0) .5 else -.5 })
        val policy = fixture(model).load()
        val (kernelSession, kernel) = select(policy)
        assertNotEquals(plainSession.policyIdentity, kernelSession.policyIdentity)
        assertEquals(policy.scores(info, menu), requireNotNull(kernel.search?.diagnostics?.rootSelectionGuidance).scores)
        assertTrue(expectedWorld.step(kernel.choice).accepted)
    }

    @Test fun `loader refuses altered artifacts wrong bindings and mismatched model shape`() {
        val f = RootActionKernelFeatures(RootActionKernelVector(listOf(0), listOf(1.0)), RootActionKernelVector(listOf(0), listOf(1.0)))
        val model = RootActionKernelModel(ridge = .001, centers = listOf(f), coefficients = listOf(.2))
        val valid = fixture(model)
        valid.load()
        valid.loadRootRolloutPolicy()
        assertFails { valid.copy(manifestSha256 = "0".repeat(64)).load() }
        assertFails { valid.copy(researchRunIdentity = "research-run-v1-sha256:" + "0".repeat(64)).load() }
        assertFails { fixture(model, protocol = "wrong-protocol").load() }
        assertFails { fixture(model, actions = 2).load() }
        assertFails { fixture(model, bindWrongPlan = true).load() }
        Files.writeString(Path.of(valid.directory).resolve("model.json"), "changed")
        assertFails { valid.load() }
        assertFails { valid.loadRootRolloutPolicy() }
    }

    @Test fun `kernel rollout binding preserves opponent continuation and rejects competing root policies`() {
        val f = RootActionKernelFeatures(RootActionKernelVector(listOf(0), listOf(1.0)), RootActionKernelVector(listOf(0), listOf(1.0)))
        val reference = fixture(RootActionKernelModel(ridge = .001, centers = listOf(f), coefficients = listOf(.2)), terminal = true)
        val baseline = SearchTeacherCalibrationPolicy("control", 8, 64, 32, 1.4, true, 1.0)
        assertFalse("rootKernelRolloutFit" in evidenceJson.encodeToString(baseline))
        val candidate = baseline.copy(id = "kernel", rootKernelRolloutFit = reference)
        assertEquals(candidate, evidenceJson.decodeFromString<SearchTeacherCalibrationPolicy>(evidenceJson.encodeToString(candidate)))
        val policy = candidate.policy(1L)
        assertEquals(baseline.parameters(1L), policy.effectiveParameters(1L))
        assertEquals(baseline.policy(1L).effectiveOpponentRolloutPolicy().behaviorSpecification,
            policy.effectiveOpponentRolloutPolicy().behaviorSpecification)
        assertEquals(reference.researchRunIdentity, policy.effectiveRootRolloutPolicy().behaviorSpecification.parameters["fitIdentity"])
        assertNotEquals(baseline.policy(1L).effectiveRootRolloutPolicy().behaviorSpecification,
            policy.effectiveRootRolloutPolicy().behaviorSpecification)
        assertFails { candidate.copy(rootRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.UNIFORM) }
        assertFails { candidate.copy(rolloutHeuristicProbability = .5) }
        assertFails { candidate.copy(rootCloningFit = CloningFitReference(reference.directory, reference.researchRunIdentity, reference.manifestSha256)) }
    }

    @Test fun `fast rollout configuration binds each actor independently and rejects silent override`() {
        val f = RootActionKernelFeatures(RootActionKernelVector(listOf(0), listOf(1.0)), RootActionKernelVector(listOf(0), listOf(1.0)))
        val reference = fixture(RootActionKernelModel(ridge = .001, centers = listOf(f), coefficients = listOf(.2)), terminal = true)
        val baseline = SearchTeacherCalibrationPolicy("control", 8, 56, 32, 1.4, true, 1.0)
        assertFalse("fastRootKernelRolloutFit" in evidenceJson.encodeToString(baseline))
        assertFalse("fastOpponentKernelRolloutFit" in evidenceJson.encodeToString(baseline))
        for ((root, opponent) in listOf(true to false, false to true, true to true)) {
            val treatment = baseline.copy(fastRootKernelRolloutFit = reference.takeIf { root },
                fastOpponentKernelRolloutFit = reference.takeIf { opponent })
            assertEquals(treatment, evidenceJson.decodeFromString<SearchTeacherCalibrationPolicy>(evidenceJson.encodeToString(treatment)))
            val policy = treatment.policy(1L)
            assertEquals(!root, policy.effectiveRootRolloutPolicy().requiresProductionAdmission)
            assertEquals(!opponent, policy.effectiveOpponentRolloutPolicy().requiresProductionAdmission)
            assertEquals(baseline.parameters(1L), policy.effectiveParameters(1L))
        }
        val fast = baseline.copy(fastRootKernelRolloutFit = reference, fastOpponentKernelRolloutFit = reference)
        assertFails { fast.copy(rootKernelRolloutFit = reference) }
        assertFails { fast.copy(rootRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.UNIFORM) }
        assertFails { fast.copy(opponentRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.UNIFORM) }
        assertFails { fast.copy(rolloutHeuristicProbability = .5) }
    }

    @Test fun `terminal fit has distinct authenticated target protocol and retains frozen model`() {
        val f = RootActionKernelFeatures(RootActionKernelVector(listOf(0), listOf(1.0)), RootActionKernelVector(listOf(0), listOf(1.0)))
        val model = RootActionKernelModel(ridge = .001, centers = listOf(f), coefficients = listOf(.2))
        val terminal = fixture(model, terminal = true)
        assertEquals(model, terminal.loadFrozenModel().model)
        assertNotEquals(fixture(model).load().configurationId, terminal.load().configurationId)
        assertFails { fixture(model, terminal = true, wrongTarget = true).load() }
        assertFails { fixture(model, terminal = true, bindWrongPlan = true).load() }
        assertFails { fixture(model, terminal = true, actions = 2).load() }
    }

    @Test fun `screen binds kernel manifest and rejects unsupported modes while preserving historical defaults`() {
        val plain = PositionBankScreenPolicy(SearchTeacherCalibrationPolicy("synthetic", 2, 4, 2, 1.4, false, 1.0), MonoRedVisibleEvaluatorConfig())
        assertFalse("rootKernel" in evidenceJson.encodeToString(plain))
        val kernel = plain.copy(rootKernel = RootKernelFitReference("/tmp/synthetic", "research-run-v1-sha256:" + "a".repeat(64), "b".repeat(64)))
        val plan = PositionBankScreenPlan(bankDirectory = "/tmp/bank", expectedBankIdentity = "synthetic", partition = PositionBankScreenPartition.DEVELOPMENT,
            mode = PositionBankScreenMode.SEARCH, rootLimit = 1, repetitions = 1, policies = listOf(kernel))
        assertEquals(plan, evidenceJson.decodeFromString<PositionBankScreenPlan>(evidenceJson.encodeToString(plan)))
        assertFails { plan.copy(mode = PositionBankScreenMode.ACTION_CONDITIONAL) }
        assertFails { plan.copy(mode = PositionBankScreenMode.FEATURES) }
    }

    /** Entirely synthetic source/report metadata; this fixture is not historical evidence. */
    private fun fixture(model: RootActionKernelModel, protocol: String = "root-action-kernel-fit-v1", actions: Int = model.centers.size,
        bindWrongPlan: Boolean = false, terminal: Boolean = false, wrongTarget: Boolean = false): RootKernelFitReference {
        val directory = Files.createTempDirectory("synthetic-root-kernel-")
        val state = ResearchSourceTreeState("a".repeat(40), "0".repeat(64), "0".repeat(64), "0".repeat(64))
        val source = ResearchRunProvenance(state.revision, state.revision, state.revision, false, false,
            ResearchSourceProvenance(expectedArgentumRevision = state.revision, outer = state, argentum = state))
        val plan = RootActionKernelPlan(CloningComparisonInput("/tmp/synthetic", "synthetic"))
        val terminalPlan = TerminalRootKernelFitPlan(CloningComparisonInput("/tmp/bank", "synthetic"), SavedRootPolicyInput("/tmp/terminal", "synthetic", "terminal"), model.ridge)
        val bindings = ResearchRunBindings(protocol = if (terminal) "terminal-root-action-kernel-fit-v1" else protocol, material = mapOf(
            "source" to sha256(evidenceJson.encodeToString(source)),
            "plan" to if (bindWrongPlan) "wrong" else sha256(if (terminal) evidenceJson.encodeToString(terminalPlan) else evidenceJson.encodeToString(plan)),
            "target" to if (wrongTarget) "search-backup" else "conditional-terminal-payoff-equal-repetition-mean-root-centered-v1",
            "feature-schema" to NEURAL_BC_FEATURE_SCHEMA,
            "kernel" to "l2-state-l2-candidate-root-centered-candidate-plus-state-tensor-candidate-v1"))
        val metrics = RootActionKernelFitMetrics(1, 1, actions, 0.0, 0.0)
        val report = RootActionKernelReport(bindings.identity, source, plan, metrics, metrics, 0.0, 0.0, 0, emptyList(), emptyList(), emptyList())
        Files.writeString(directory.resolve("model.json"), evidenceJson.encodeToString(model))
        Files.writeString(directory.resolve("bindings.json"), evidenceJson.encodeToString(bindings))
        val terminalReport = TerminalRootKernelFitReport(bindings.identity, source, terminalPlan, metrics,
            TerminalRootScreenAccounting(1, 1, 0, actions, actions, 0, 0, 0, actions, 0, 0, 0, 0.0))
        Files.writeString(directory.resolve("report.json"), if (terminal) evidenceJson.encodeToString(terminalReport) else evidenceJson.encodeToString(report))
        ResearchRunArtifacts(directory, bindings.identity).also {
            listOf("model.json", "bindings.json", "report.json").forEach(it::register); it.finalize()
        }
        return RootKernelFitReference(directory.toString(), bindings.identity, researchSha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
    }

    private fun world(): ArgentumSearchWorld {
        val environment = GameEnvironment.create(buildRegistry()).also { env ->
            env.reset(GameConfig(players = listOf(PlayerConfig("p0", deck.deck()), PlayerConfig("p1", deck.deck())),
                skipMulligans = false, useHandSmoother = false, startingPlayerIndex = 0, seed = 17L))
        }
        return ArgentumSearchWorld.create(environment, "synthetic-root-kernel", 99L, effectiveSetupSeed = 17L,
            expander = UnifiedSemanticExpander(actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1),
            knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck))
    }
}
