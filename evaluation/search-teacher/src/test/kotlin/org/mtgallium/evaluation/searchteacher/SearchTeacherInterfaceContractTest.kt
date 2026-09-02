package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherSuites
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceLocation
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

class SearchTeacherInterfaceContractTest {
    private val root: Path = Path.of("").toAbsolutePath().normalize().let { current ->
        if (Files.isDirectory(current.resolve("agent"))) current else current.resolve("../..").normalize()
    }

    @Test
    fun `artifact paths cannot escape their configured root`() {
        val store = EvidenceStore(root)
        assertFailsWith<IllegalArgumentException> {
            store.resolve(EvidenceLocation.LATEST, "../../outside.json")
        }
        assertFailsWith<IllegalArgumentException> {
            store.resolve(EvidenceLocation.LATEST, "..\\..\\outside.json")
        }
        assertEquals(
            EvidenceLocation.LATEST.directory + "/tournament/analytics.json",
            EvidenceLocation.LATEST.relativePath("tournament\\analytics.json"),
        )
    }

    @Test
    fun `exploratory outputs stay below work even through link redirects`() {
        val repository = createTempDirectory("mtgallium-work-output-")
        val store = EvidenceStore(repository)
        val allowed = store.diagnostic("measurements/result.json", "a test measurement")
        assertTrue(allowed.startsWith(store.workRoot.toAbsolutePath().normalize()))

        listOf(
            store.latest("measurements/result.json"),
            store.frozen("measurements/result.json"),
            store.review("measurements/result.json"),
        ).forEach { refused ->
            assertFailsWith<IllegalArgumentException> {
                store.requireDiagnosticOutput(refused, "a test measurement")
            }
        }

        Files.createDirectories(store.workRoot)
        Files.createDirectories(store.latestRoot)
        val redirect = store.workRoot.resolve("redirect")
        Files.createSymbolicLink(redirect, store.latestRoot)
        val failure = assertFailsWith<IllegalArgumentException> {
            store.requireDiagnosticOutput(redirect.resolve("result.json"), "a test measurement")
        }
        assertTrue("through the filesystem link" in failure.message.orEmpty())
    }

    @Test
    fun `direct diagnostic producers choose work output`() {
        val repository = createTempDirectory("mtgallium-direct-diagnostic-")
        val store = EvidenceStore(repository)
        val throughput = ThroughputProfiler(
            repository,
            buildRegistry(),
            loadDeckManifest(),
            SearchTeacherArena.smokeProfile(),
        )

        assertEquals(
            store.work("throughput").toAbsolutePath().normalize(),
            throughput.outputDirectory(),
        )
    }

    @Test
    fun `cli rejects unknown input and normalizes paths`() {
        assertFailsWith<IllegalStateException> { SearchTeacherCli.parse(arrayOf("--wat")) }
        assertFailsWith<IllegalStateException> { SearchTeacherCli.parse(arrayOf("--suite")) }

        val parsed = SearchTeacherCli.parse(
            arrayOf(
                "--suite", "replay-review-case-intake",
                "--output", EvidenceLocation.WORK.relativePath("intake.json"),
            )
        )
        assertTrue(parsed.outputPath?.isAbsolute == true)
    }

    @Test
    fun `suite catalog contains technical commands and rejects removed process commands`() {
        listOf(
            "smoke",
            "arena",
            "belief",
            "corpus",
            "evaluator-comparison",
            "tournament",
            "tournament-v3-calibrated",
            "tree-reuse-validation",
            "inspection",
            "neural-stability-boundary-diagnostic",
            "neural-final-boundary-diagnostic",
            "neural-cohort-continuation-preflight",
            "neural-cohort-continuation-diagnostic",
            "neural-anchor-crossing-preflight",
            "neural-anchor-crossing-diagnostic",
            "neural-held-out-generalization-preflight",
            "neural-held-out-generalization-diagnostic",
        ).forEach { suite -> assertEquals(suite, SearchTeacherSuites.require(suite).id) }

        listOf(
            "gate",
            "pilot-gate",
            "evaluator-review",
            "baseline-hardening-promote",
            "tournament-v3-calibrated-review",
        ).forEach { suite ->
            assertFailsWith<IllegalArgumentException> { SearchTeacherSuites.require(suite) }
        }
    }

    @Test
    fun `evaluator provenance paths bind the implementations they name`() {
        mapOf(
            EvaluatorImplementationSources.VISIBLE_V2 to "object MonoRedInformationEvaluator",
            EvaluatorImplementationSources.TACTICAL_V3 to "class MonoRedTacticalEvaluator",
        ).forEach { (relative, declaration) ->
            val source = root.resolve(relative)
            assertTrue(Files.isRegularFile(source), "Missing evaluator implementation: $relative")
            assertTrue(declaration in Files.readString(source), "$relative does not declare $declaration")
        }
    }
}
