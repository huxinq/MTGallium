package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

class Issue0034NeuralAnchorCrossingDiagnosticTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `retained forks restore exact next updates without emitting research evidence`() {
        val root = repositoryRoot()
        val output = temporaryDirectory.resolve("must-not-be-created")
        val report = Issue0034NeuralAnchorCrossingDiagnostic(root, output).preflight(
            EvidenceStore(root).work("neural-behavioral-cloning/g20-seed20260902/corpus-manifest.json")
        )

        assertEquals(CORRECTED_NEURAL_SEEDS, report.seeds.map { it.seed })
        assertTrue(report.seeds.all { it.forkStrictRankingCorrect == 323 })
        assertTrue(report.seeds.all(Issue0034PreflightSeed::restoredNextUpdateMatchesExactly))
        assertEquals(0, report.researchTrainingUpdatesRetainedByPreflight)
        assertEquals(0, report.researchArtifactsEmittedByPreflight)
        assertFalse(Files.exists(output))
    }

    private fun repositoryRoot(): Path = Path.of("").toAbsolutePath().normalize().let { current ->
        if (Files.isDirectory(current.resolve("evaluation/search-teacher"))) current else current.parent.parent
    }
}
