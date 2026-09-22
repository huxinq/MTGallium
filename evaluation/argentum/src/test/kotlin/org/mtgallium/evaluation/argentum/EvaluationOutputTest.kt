package org.mtgallium.evaluation.argentum

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EvaluationOutputTest {
    @Test fun `the selected external report root needs no artifact manifest`() {
        assertEquals(Path.of("/tmp/private/argentum/latest"),
            reportOutputDirectory(Path.of("/tmp/source"), "/tmp/private", true))
    }

    @Test fun `the private checkout retains its existing local report location`() {
        assertEquals(Path.of("/tmp/source/reports/argentum/latest"),
            reportOutputDirectory(Path.of("/tmp/source"), "", false))
    }

    @Test fun `public source requires an external report destination`() {
        assertFailsWith<IllegalArgumentException> { reportOutputDirectory(Path.of("/tmp/source"), "", true) }
        assertFailsWith<IllegalArgumentException> { reportOutputDirectory(Path.of("/tmp/source"), "/tmp/source/data", true) }
    }
}
