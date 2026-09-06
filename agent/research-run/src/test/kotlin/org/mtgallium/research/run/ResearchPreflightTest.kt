package org.mtgallium.research.run

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ResearchPreflightTest {
    private val bindings = ResearchRunBindings(protocol = "preflight-test", material = mapOf(
        "source" to "source", "engine" to "engine", "configuration" to "config", "runtime" to "runtime", "input" to "input"))
    private fun report(passed: Boolean = true) = ResearchPreflightReport(bindings = bindings,
        checks = listOf(ResearchPreflightCheck("technical-smoke", passed, "technical result")), workload = mapOf("size" to "2"))

    @Test
    fun `reuse requires the complete current binding and verified artifacts`() {
        val output = createTempDirectory("preflight-reuse")
        Files.writeString(output.resolve("model.json"), "model bytes")
        ResearchPreflights.persist(output, report(), listOf("model.json"))
        assertEquals(report(), ResearchPreflights.verify(output, bindings))
        bindings.material.keys.forEach { key ->
            assertFails { ResearchPreflights.verify(output, bindings.copy(material = bindings.material + (key to "changed"))) }
        }
        Files.writeString(output.resolve("model.json"), "changed model")
        assertFails { ResearchPreflights.verify(output, bindings) }
    }

    @Test
    fun `failed or unregistered reports never authorize launch`() {
        val output = createTempDirectory("preflight-failed")
        ResearchPreflights.persist(output, report(false))
        assertFails { ResearchPreflights.verify(output, bindings) }
        Files.writeString(output.resolve("unrelated.json"), "unrelated")
        ResearchRunArtifacts(output, bindings.identity).apply { register("unrelated.json"); finalize() }
        assertFails { ResearchPreflights.verify(output, bindings) }
    }

    @Test
    fun `child manifest identity and all child artifact bytes remain authoritative`() {
        val output = createTempDirectory("preflight-child")
        val child = output.resolve("gameplay")
        ResearchRunFiles.atomicWrite(child.resolve("replay.json"), "private synthetic replay")
        ResearchRunArtifacts(child, "child-id").apply { register("replay.json"); finalize() }
        val report = report().copy(childRuns = mapOf("gameplay" to "child-id"))
        ResearchPreflights.persist(output, report)
        assertEquals(report, ResearchPreflights.verify(output, bindings))
        Files.delete(child.resolve("replay.json"))
        assertFails { ResearchPreflights.verify(output, bindings) }
    }
}
