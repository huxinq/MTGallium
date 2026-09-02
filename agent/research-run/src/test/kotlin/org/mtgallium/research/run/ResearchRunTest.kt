package org.mtgallium.research.run

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResearchRunTest {
    @Test
    fun `material bindings are deterministic and operational metadata is not identity`() {
        val first = ResearchRunBindings(protocol = "bounded-test-v1", material = mapOf("source" to "a", "seed" to "7"))
        val reordered = ResearchRunBindings(protocol = "bounded-test-v1", material = linkedMapOf("seed" to "7", "source" to "a"))
        val changed = ResearchRunBindings(protocol = "bounded-test-v1", material = mapOf("source" to "b", "seed" to "7"))

        assertEquals(first.identity, reordered.identity)
        assertTrue(first.identity != changed.identity)
        assertEquals(
            ResearchRunOperationalMetadata(ResearchRunState.RUNNING, durableRunId = "one" ).state,
            ResearchRunOperationalMetadata(ResearchRunState.RUNNING, durableRunId = "two" ).state,
        )
    }

    @Test
    fun `checkpoint envelope validates lineage payload and atomic persistence`() {
        val directory = createTempDirectory("research-run-checkpoint")
        val path = directory.resolve("checkpoint.json")
        val first = ResearchRunCheckpoints.persist(path, "run", "domain-payload-v1", 0, "first".encodeToByteArray())
        val second = ResearchRunCheckpoints.persist(
            path, "run", "domain-payload-v1", 1, "second".encodeToByteArray(), first.payloadSha256,
        )

        val loaded = ResearchRunCheckpoints.load(path)
        assertEquals(second, loaded)
        assertEquals(first.payloadSha256, loaded.parentPayloadSha256)
        assertEquals("second", loaded.payload().decodeToString())
    }

    @Test
    fun `final manifest registers actual names and catches drift during verification`() {
        val directory = createTempDirectory("research-run-artifacts")
        ResearchRunFiles.atomicWrite(directory.resolve("report.json"), "report")
        ResearchRunFiles.atomicWrite(directory.resolve("report.md"), "readable")
        val artifacts = ResearchRunArtifacts(directory, "run")
        artifacts.register("report.json")
        artifacts.register("report.md")
        artifacts.finalize()

        assertEquals(2, ResearchRunArtifacts.loadAndVerify(directory, "run").artifacts.size)
        Files.writeString(directory.resolve("report.json"), "changed")
        assertFailsWith<IllegalArgumentException> { ResearchRunArtifacts.loadAndVerify(directory, "run") }
    }
}
