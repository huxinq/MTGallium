package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag

@Tag("public-source")
class ResearchTransferAuditTest {
    @Test fun `crossing both boundaries remains inconclusive transfer evidence`() {
        assertTrue(inconclusiveTransferGameplay(PairedSequentialDisposition.BOTH_BOUNDARIES_CROSSED))
        assertTrue(inconclusiveTransferGameplay(PairedSequentialDisposition.FUTILITY))
        assertTrue(inconclusiveTransferGameplay(PairedSequentialDisposition.BUDGET_EXHAUSTED))
        assertFalse(inconclusiveTransferGameplay(PairedSequentialDisposition.ABOVE_NULL))
    }

    @Test fun `transfer links require the exact deployed model role shared controls and parity null`() {
        val model = RootKernelFitReference("/tmp/model", "research-run-v1-sha256:"+"a".repeat(64), "b".repeat(64))
        val control = SearchTeacherCalibrationPolicy("control", 8, 64, 32, 1.4, true, 1.0)
        val candidate = control.copy(id = "candidate", simulations = 56, rootKernelRolloutFit = model)
        val rule = PairedSequentialRule(nullPointRate = .5, targetPointRate = .5, falsePositiveRate = .025, falseNegativeRate = .025, maximumPairs = 64)
        requireKernelRolloutTransferDescriptors(model, "candidate", control, candidate, rule)
        assertFails { requireKernelRolloutTransferDescriptors(model.copy(manifestSha256 = "c".repeat(64)), "candidate", control, candidate, rule) }
        assertFails { requireKernelRolloutTransferDescriptors(model, "candidate", control, candidate.copy(particles = 16), rule) }
        assertFails { requireKernelRolloutTransferDescriptors(model, "candidate", control, candidate.copy(opponentRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.UNIFORM), rule) }
        assertFails { requireKernelRolloutTransferDescriptors(model, "candidate", control, candidate.copy(rootKernelRolloutFit = null), rule) }
        assertFails { requireKernelRolloutTransferDescriptors(model, "candidate", control, candidate, rule.copy(nullPointRate = .4)) }
    }
}
