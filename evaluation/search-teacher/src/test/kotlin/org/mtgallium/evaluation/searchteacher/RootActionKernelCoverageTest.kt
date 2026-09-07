package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag

@Tag("public-source")
class RootActionKernelCoverageTest {
    @Test fun `fit comparison preserves root pairing and reference repetition differences`() {
        val old = SavedRootRegretRow("root", "group", 0, "control", "old", .1, .3, -.2, listOf(-.3, -.1))
        val new = old.copy(candidateAction = "new", candidateRegret = .2, candidateMinusBaseline = -.1,
            candidateMinusBaselineByReferenceRepetition = listOf(-.2, 0.0))
        val result = compareRootKernelFits(listOf(old), listOf(new)).single()
        assertEquals("old", result.baselineAction); assertEquals("new", result.candidateAction)
        assertEquals(.3, result.baselineRegret); assertEquals(.2, result.candidateRegret)
        assertEquals(.1, result.candidateMinusBaseline, 1e-12)
        result.candidateMinusBaselineByReferenceRepetition.forEach { assertEquals(.1, it, 1e-12) }
        assertFailsWith<IllegalArgumentException> { compareRootKernelFits(listOf(old), listOf(new.copy(rootId = "other"))) }
        assertFailsWith<IllegalArgumentException> { compareRootKernelFits(listOf(old), listOf(new.copy(baselineAction = "foreign"))) }
        assertFailsWith<IllegalArgumentException> { compareRootKernelFits(listOf(old, old), listOf(new, new)) }
    }
    @Test fun `original fit plan omits optional extension and its ridge is unchanged`() {
        val plan = RootActionKernelPlan(CloningComparisonInput("/tmp/old", "id"))
        assertFalse(evidenceJson.encodeToString(RootActionKernelPlan.serializer(), plan).contains("developmentExtension"))
        val extended = plan.copy(developmentExtension = RootActionKernelExtension("/tmp/new", "bank", SavedRootPolicyInput("/tmp/ref", "ref", "policy")))
        assertEquals(plan.ridge, extended.ridge)
        assertEquals(plan.referenceExperiment, extended.referenceExperiment)
    }
}
