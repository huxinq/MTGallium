package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag

@Tag("public-source")
class TerminalPredictionDiagnosticTest {
    @Test fun `diagnostic is reachable from suite catalog`() {
        assertEquals("terminal-prediction-diagnostic", org.mtgallium.evaluation.searchteacher.cli.SearchTeacherSuites.require("terminal-prediction-diagnostic").id)
    }
    @Test fun `opposite noise and persistent error remain distinct and offsets cancel`() {
        val noisy = terminalPredictionMoments(listOf(0.0, 0.0), listOf(listOf(1.0, -1.0), listOf(-1.0, 1.0)))
        assertEquals(0.0, noisy.pooledResidualSquared)
        assertEquals(1.0, noisy.repetitionDifferenceSquaredOverFour)
        assertEquals(-1.0, noisy.residualCrossProduct) // Never clip a negative descriptive cross-product.
        val persistent = terminalPredictionMoments(listOf(4.0, 4.0), listOf(listOf(1.0, -1.0), listOf(1.0, -1.0)))
        assertEquals(1.0, persistent.residualCrossProduct)
        assertEquals(0.0, persistent.repetitionDifferenceSquaredOverFour)
        val mixed = terminalPredictionMoments(listOf(.2, -.3, .1), listOf(listOf(1.0, 0.0, -.5), listOf(.5, -.5, 0.0)))
        assertEquals(mixed.pooledResidualSquared, mixed.residualCrossProduct + mixed.repetitionDifferenceSquaredOverFour, 1e-12)
        assertFails { terminalPredictionMoments(listOf(0.0, 0.0), listOf(listOf(1.0, -1.0))) }
    }
    @Test fun `groups contribute equally even with unequal root counts`() {
        val zero = terminalPredictionMoments(listOf(0.0, 0.0), List(2) { listOf(0.0, 0.0) })
        val one = terminalPredictionMoments(listOf(0.0, 0.0), List(2) { listOf(1.0, -1.0) })
        val groupA = averageTerminalPredictionMoments(listOf(zero, zero, zero))
        val groupB = averageTerminalPredictionMoments(listOf(one))
        assertEquals(.5, averageTerminalPredictionMoments(listOf(groupA, groupB)).residualCrossProduct)
    }
}
