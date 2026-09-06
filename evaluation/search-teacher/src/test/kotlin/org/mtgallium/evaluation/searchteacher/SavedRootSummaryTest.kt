package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag

@Tag("public-source")
class SavedRootSummaryTest {
    private fun row(root: String, rep: Int, delta: Double, group: String = "g") =
        SavedRootRegretRow(root, group, rep, "a", "b", 0.5, 0.5 - delta, delta, listOf(delta))

    @Test fun `group summaries weight roots equally when repetition counts differ`() {
        val rows = listOf(row("a", 0, .2), row("a", 1, .2), row("b", 0, -.2), row("c", 0, .1, "h"))
        val groups = savedRootGroupRegrets(rows)
        assertEquals(2, groups.size)
        assertEquals(0.0, groups.first().candidateMinusBaseline)
        assertEquals(2, groups.first().roots)
        assertEquals(3, groups.first().selections)
        assertEquals(.1, groups.last().candidateMinusBaseline)
    }
    @Test fun `duplicate selections and one root assigned to different seed groups refuse`() {
        val a = row("a", 0, .1)
        assertFailsWith<IllegalArgumentException> { savedRootGroupRegrets(listOf(a, a)) }
        assertFailsWith<IllegalArgumentException> { savedRootGroupRegrets(listOf(a, a.copy(repetition = 1, seedGroupId = "other"))) }
    }
}
