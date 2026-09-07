package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag

@Tag("public-source")
class DirectAttackKernelScreenTest {
    @Test fun `direct control contrast integrates the entire stochastic distribution`() {
        val contrast = directAttackContrasts(listOf(-1.0, .5, 1.0), 1, 0, listOf(.1, .2, .7))
        assertEquals(1.5, contrast.first)
        assertEquals(-.2, contrast.second, 1e-12)
        assertFails { directAttackContrasts(listOf(-1.0, 1.0), 1, 0, listOf(.1, .7)) }
        assertFails { directAttackContrasts(listOf(-1.0, Double.NaN), 1, 0, listOf(.1, .9)) }
    }

    @Test fun `gate requires both repetitions against both controls and breadth in sixteen distinct groups`() {
        fun rows(positive: Int = 8) = (0 until 32).map { i ->
            val gain = if (i / 2 < positive) .2 else -.01
            DirectAttackScreenRow("r$i", "g${i / 2}", "learned", listOf("planner", "planner"), listOf(.5, .5),
                listOf(gain, gain), listOf(gain, gain))
        }
        assertTrue(directAttackScreenGate(rows()).passed)
        assertFalse(directAttackScreenGate(rows(7)).passed)
        assertFalse(directAttackScreenGate(rows().map { it.copy(heuristicImprovement = listOf(.1, -.01)) }).passed)
        assertFalse(directAttackScreenGate(rows().map { it.copy(plannerImprovement = listOf(-.01, .1)) }).passed)
        assertFails { directAttackScreenGate(rows().map { it.copy(group = "one-group") }) }
        assertFails { directAttackScreenGate(rows().map { it.copy(rootId = "one-root") }) }
    }

    @Test fun `unequal group sizes retain equal group weight`() {
        val rows = (0 until 16).flatMap { group ->
            val count = if (group < 8) 1 else 3
            (0 until count).map { root ->
                val gain = if (group < 8) .6 else -.4
                DirectAttackScreenRow("r$group-$root", "g$group", "learned", listOf("planner", "planner"),
                    listOf(.5, .5), listOf(gain, gain), listOf(gain, gain))
            }
        }
        val gate = directAttackScreenGate(rows)
        assertEquals(.1, gate.plannerImprovementByRepetition.first(), 1e-12)
        assertTrue(gate.passed) // Pooled-root weighting would reverse the sign.
    }
}
