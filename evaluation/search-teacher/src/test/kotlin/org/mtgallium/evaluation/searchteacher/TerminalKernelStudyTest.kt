package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.research.run.ResearchBuildReference

@Tag("public-source")
class TerminalKernelStudyTest {
    private fun row(id: String, group: String, delta: List<Double>) = SavedRootRegretRow(id, group, 0, "a", "b", .5, .5-delta.average(), delta.average(), delta)

    @Test fun `unequal root populations and repeated target estimates do not change group weighting`() {
        val rows = listOf(row("r1", "g1", listOf(.5, .5)), row("r2", "g1", listOf(.5, .5)), row("r3", "g2", listOf(-.5, -.5)))
        val comparison = terminalStudyComparison(rows)
        assertEquals(0.0, comparison.equalGroupMeanDifference)
        assertEquals(listOf(0.0, 0.0), comparison.equalGroupMeanByTargetRepetition)
        assertEquals(1, comparison.positiveGroups); assertEquals(1, comparison.negativeGroups)
        assertFalse(terminalStudyGate(TerminalStudyGate(1), comparison, comparison).passed)
        val conflicting = terminalStudyComparison(listOf(row("r", "g", listOf(.5, -.25))))
        assertTrue(conflicting.equalGroupMeanDifference > 0)
        assertFalse(terminalStudyGate(TerminalStudyGate(1), conflicting, conflicting).passed)
    }

    @Test fun `study cannot substitute learned terminal continuation or validation training groups`() {
        val policy = PositionBankScreenPolicy(SearchTeacherCalibrationPolicy("terminal", 8, 64, 32, 1.4, true, 1.0), MonoRedVisibleEvaluatorConfig())
        val dev = PositionBankScreenPlan(bankDirectory = "/tmp/bank", expectedBankIdentity = "bank", partition = PositionBankScreenPartition.DEVELOPMENT,
            mode = PositionBankScreenMode.TERMINAL_CONTINUATIONS, rootLimit = 1, repetitions = 2, policies = listOf(policy), rootIds = listOf("d"),
            terminalContinuation = TerminalRootContinuationConfig(8, maximumTotalContinuations = 1000))
        val validation = dev.copy(partition = PositionBankScreenPartition.VALIDATION, rootIds = listOf("v"))
        val control = validation.copy(mode = PositionBankScreenMode.ROOT_ROLLOUT_SELECTION, repetitions = 1, terminalContinuation = null)
        val fit = RootKernelFitReference("/tmp/fit", "research-run-v1-sha256:"+"a".repeat(64), "b".repeat(64))
        val p = TerminalKernelStudyPlan(campaignId = "test", campaignDirectory = "/tmp/campaign", build = ResearchBuildReference("/tmp/build", "build", "c".repeat(64)),
            baseline = fit, development = TerminalStudyData(dev), validation = TerminalStudyData(validation), control = control, gate = TerminalStudyGate(1))
        assertEquals(p, evidenceJson.decodeFromString<TerminalKernelStudyPlan>(evidenceJson.encodeToString(TerminalKernelStudyPlan.serializer(), p)))
        assertFails { p.copy(development = TerminalStudyData(dev.copy(policies = listOf(policy.copy(search = policy.search.copy(rootKernelRolloutFit = fit)))))) }
        assertFails { p.copy(control = control.copy(rootIds = listOf("other"))) }
        assertFails { p.copy(retainedFit = fit) }
        assertFails { requireStudyGroupSeparation(setOf("g1", "g2"), setOf("g2")) }
        requireStudyGroupSeparation(setOf("g1"), setOf("g2"))
    }
}
