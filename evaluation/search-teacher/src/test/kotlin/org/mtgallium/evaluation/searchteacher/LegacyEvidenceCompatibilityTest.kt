package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LegacyEvidenceCompatibilityTest {
    @Test
    fun `schema one privileged replay record remains decodable`() {
        val fixture = assertNotNull(javaClass.getResourceAsStream("/legacy/privileged-replay-v1.jsonl"))
            .use { it.readBytes() }
        val archive = Files.createTempFile("legacy-privileged-replay-v1", ".jsonl.gz")
        try {
            GZIPOutputStream(Files.newOutputStream(archive)).use { it.write(fixture) }

            val terminal = assertNotNull(readPrivilegedReplay(archive).single().terminal)
            assertEquals(1, terminal.schemaVersion)
            assertEquals("legacy-final-fingerprint", terminal.finalAuthoritativeFingerprint)
        } finally {
            Files.deleteIfExists(archive)
        }
    }
}
