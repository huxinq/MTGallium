package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig

@Tag("public-source")
class TerminalRootKernelExperimentTest {
    private fun action(key: String) = SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay(key),
        canonicalPayload = JsonObject(mapOf("key" to JsonPrimitive(key))))
    private val menu = listOf(action("a"), action("b"))
    private fun sample(index: Int, payoff: Double) = TerminalRootSample(index, 0, 0L, 0L, payoff, 0,
        OpponentPolicyDecisionSummary(), OpponentPolicyDecisionSummary(), 0.0)
    private fun row(repetition: Int, outcomes: List<List<Double>>) = PositionBankScreenRow("root", "terminal", "unused", repetition,
        PositionBankScreenDisposition.TERMINAL_CONTINUATIONS, 0.0, 0.0,
        terminalRootActions = menu.mapIndexed { i, choice -> TerminalRootActionSamples(choice, outcomes[i].size,
            outcomes[i].mapIndexed(::sample), TerminalRootActionDisposition.COMPLETE) })

    @Test fun `complete terminal repetitions pool in menu order and survive serialized roundtrip`() {
        val rows = listOf(row(1, listOf(listOf(1.0, -1.0), listOf(1.0, 1.0))),
            row(0, listOf(listOf(-1.0, -1.0), listOf(1.0, -1.0))))
        assertEquals(listOf(-.5, .5), terminalActionMeans(rows, menu, 2, "terminal"))
        val encoded = evidenceJson.encodeToString(rows)
        assertEquals(rows, evidenceJson.decodeFromString<List<PositionBankScreenRow>>(encoded))
        assertEquals(listOf(-.5, .5), terminalActionMeans(evidenceJson.decodeFromString(encoded), menu, 2, "terminal"))
    }

    @Test fun `a refused root is not a training target even when some actions completed`() {
        val complete = row(0, listOf(listOf(1.0, -1.0), listOf(1.0, 1.0)))
        val partial = complete.copy(disposition = PositionBankScreenDisposition.REFUSED,
            terminalRootActions = listOf(complete.terminalRootActions.first(),
                TerminalRootActionSamples(menu[1], 2, listOf(sample(0, 1.0)), TerminalRootActionDisposition.NON_GAME_FAILURE, "stopped")))
        assertFails { terminalActionMeans(listOf(partial), menu, 1, "terminal") }
        assertFails { terminalActionMeans(listOf(complete, complete), menu, 2, "terminal") }
        assertFails { terminalActionMeans(listOf(complete.copy(disposition = PositionBankScreenDisposition.ACTION_CONDITIONAL)), menu, 1, "terminal") }
        assertFails { terminalActionMeans(listOf(complete), menu.reversed(), 1, "terminal") }
        assertFails { terminalActionMeans(listOf(complete), menu, 1, "other-policy") }
        assertFails { terminalActionMeans(listOf(complete, row(1, listOf(listOf(1.0), listOf(-1.0)))), menu, 2, "terminal") }
    }

    @Test fun `fixed experiment refuses changed continuation and swapped partition meaning`() {
        val policy = PositionBankScreenPolicy(SearchTeacherCalibrationPolicy("terminal", 8, 64, 32, 1.4, true, 1.0), MonoRedVisibleEvaluatorConfig())
        val dev = PositionBankScreenPlan(bankDirectory = "/tmp/dev", expectedBankIdentity = "synthetic", partition = PositionBankScreenPartition.DEVELOPMENT,
            mode = PositionBankScreenMode.TERMINAL_CONTINUATIONS, rootLimit = 1, repetitions = 2, policies = listOf(policy), rootIds = listOf("a"),
            terminalContinuation = TerminalRootContinuationConfig(8, maximumTotalContinuations = 1000))
        val validation = dev.copy(bankDirectory = "/tmp/validation", partition = PositionBankScreenPartition.VALIDATION, rootIds = listOf("b"))
        val plan = TerminalRootKernelExperimentPlan(dev, validation,
            RootKernelFitReference("/tmp/model", "research-run-v1-sha256:" + "a".repeat(64), "b".repeat(64)))
        assertEquals(plan, evidenceJson.decodeFromString<TerminalRootKernelExperimentPlan>(evidenceJson.encodeToString(plan)))
        assertFails { plan.copy(development = validation) }
        assertFails { plan.copy(validation = validation.copy(repetitions = 1)) }
        assertFails { plan.copy(validation = validation.copy(searchSeedDomain = "different")) }
        assertFails { plan.copy(development = dev.copy(policies = listOf(policy.copy(search = policy.search.copy(rolloutHeuristicProbability = .5))))) }
        assertFails { plan.copy(development = dev.copy(policies = listOf(policy.copy(search = policy.search.copy(rootRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.UNIFORM))))) }
        assertFails { plan.copy(development = dev.copy(policies = listOf(policy.copy(search = policy.search.copy(rootKernelRolloutFit = plan.baselineFit))))) }
    }

    @Test fun `direct terminal action ordering uses raw scores rather than saturation ties`() {
        fun f(value: Double) = RootActionKernelFeatures(RootActionKernelVector(emptyList(), emptyList()),
            RootActionKernelVector(listOf(0), listOf(value)))
        val model = CompiledRootActionKernel(RootActionKernelModel(ridge = .001, centers = listOf(f(1.0)), coefficients = listOf(1.0)))
        val root = RootActionKernelTrainingRoot("r", "g", listOf(f(-5.0), f(2.0), f(3.0)), listOf(-1.0, 0.0, 1.0))
        assertEquals(listOf(-5.0, 2.0, 3.0), model.scores(root.features))
        assertEquals(0.0, rootKernelMetrics(model, listOf(root)).equalRootReferenceRegret)
    }

    @Test fun `terminal fit preserves root offsets`() {
        fun vector(x: Double) = RootActionKernelVector(listOf(0), listOf(x))
        fun root(id: String, group: String, offset: Double) = RootActionKernelTrainingRoot(id, group,
            listOf(RootActionKernelFeatures(vector(1.0), vector(-1.0)), RootActionKernelFeatures(vector(1.0), vector(1.0))),
            listOf(-.4 + offset, .4 + offset))
        val dev = listOf(root("a", "g1", 0.0), root("b", "g2", .2))
        val model = CompiledRootActionKernel(fitRootActionKernel(dev, .001))
        val metrics = rootKernelMetrics(model, dev)
        assertEquals(0.0, metrics.equalRootReferenceRegret)
        assertTrue(metrics.equalGroupCenteredMeanSquaredError < 1e-5)
        assertEquals(2, metrics.seedGroups)
        val shifted = rootKernelMetrics(model, listOf(root("a", "g1", .1), root("b", "g2", .3)))
        assertEquals(metrics.equalGroupCenteredMeanSquaredError, shifted.equalGroupCenteredMeanSquaredError, 1e-15)
        assertEquals(metrics.equalRootReferenceRegret, shifted.equalRootReferenceRegret)
    }
    @Test fun `coverage retains the full old population and refuses duplicate weighting`() {
        val features = listOf(RootActionKernelFeatures(RootActionKernelVector(emptyList(), emptyList()), RootActionKernelVector(emptyList(), emptyList())))
        val a = RootActionKernelTrainingRoot("a", "g", features + features, listOf(.5, -.5))
        val b = a.copy(rootId = "b", actionMeans = listOf(-.5, .5))
        assertEquals(listOf(a, b), combineTerminalTrainingRoots(listOf(listOf(b), listOf(a))))
        assertFails { combineTerminalTrainingRoots(listOf(listOf(a), listOf(a))) }
        assertFails { combineTerminalTrainingRoots(listOf(listOf(a), emptyList())) }
    }

    @Test fun `coverage refuses a different sampling target and retains old fit plan serialization`() {
        val policy = PositionBankScreenPolicy(SearchTeacherCalibrationPolicy("terminal", 8, 64, 32, 1.4, true, 1.0), MonoRedVisibleEvaluatorConfig())
        val first = PositionBankScreenPlan(bankDirectory = "/tmp/a", expectedBankIdentity = "synthetic", partition = PositionBankScreenPartition.DEVELOPMENT,
            mode = PositionBankScreenMode.TERMINAL_CONTINUATIONS, rootLimit = 1, repetitions = 2, policies = listOf(policy), rootIds = listOf("a"),
            terminalContinuation = TerminalRootContinuationConfig(8, maximumTotalContinuations = 25000))
        requireSameTerminalTarget(first, first.copy(bankDirectory = "/tmp/b", rootIds = listOf("b")))
        assertFails { requireSameTerminalTarget(first, first.copy(repetitions = 1)) }
        assertFails { requireSameTerminalTarget(first, first.copy(searchSeedDomain = "changed")) }
        assertFails { requireSameTerminalTarget(first, first.copy(terminalContinuation = first.terminalContinuation!!.copy(samplesPerAction = 16))) }
        assertFails { requireSameTerminalTarget(first, first.copy(policies = listOf(policy.copy(search = policy.search.copy(particles = 16))))) }
        val plan = TerminalRootKernelFitPlan(CloningComparisonInput("/tmp/a", "synthetic"), SavedRootPolicyInput("/tmp/t", "synthetic", "terminal"), .001)
        val encoded = evidenceJson.encodeToString(plan)
        assertFalse(encoded.contains("additional"))
        assertEquals(plan, evidenceJson.decodeFromString<TerminalRootKernelFitPlan>(encoded))
    }

}
