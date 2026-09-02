package org.mtgallium.quality.architecture

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class RepositoryArchitectureConformanceTest {
    @Test
    fun `outer repository conforms to its architecture policy`() {
        val root = ArchitectureRules.locateRepositoryRoot(Path.of(""))
        val violations = ArchitectureRules(root).evaluate()

        assertTrue(
            violations.isEmpty(),
            "Architecture violations (${violations.size}):\n" + violations.joinToString("\n"),
        )
    }
}
