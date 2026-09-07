package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@org.junit.jupiter.api.Tag("public-source")
class SavedRootRegretTest {
    @Test fun `regret uses best mean not mean of repetition maxima`() {
        val result = savedRootReferenceComparison(listOf(mapOf("a" to 1.0, "b" to 0.0),
            mapOf("a" to -1.0, "b" to 0.5)), "a", "b")
        assertEquals(0.25, result.first)
        assertEquals(0.0, result.second)
        assertEquals(listOf(-1.0, 1.5), result.third)
    }
    @Test fun `missing actions incomplete menus and invalid values refuse`() {
        assertFailsWith<IllegalArgumentException> { savedRootReferenceComparison(listOf(mapOf("a" to 0.0)), "a", "b") }
        assertFailsWith<IllegalArgumentException> { savedRootReferenceComparison(listOf(mapOf("a" to 0.0), mapOf("b" to 0.0)), "a", "a") }
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.1)) {
            assertFailsWith<IllegalArgumentException> { savedRootReferenceComparison(listOf(mapOf("a" to v)), "a", "a") }
        }
    }
    @Test fun `same action has zero paired difference and preserves nonzero regret`() {
        val result = savedRootReferenceComparison(listOf(mapOf("a" to -0.7, "b" to 0.5)), "a", "a")
        assertEquals(1.2, result.first)
        assertEquals(result.first, result.second)
        assertEquals(listOf(0.0), result.third)
    }
}
