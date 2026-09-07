package org.mtgallium.research.run

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ResearchBuildTest {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val tree = ResearchSourceTreeState("a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64))
    private val source = ResearchRunProvenance(tree.revision, tree.revision, tree.revision, false, false,
        ResearchSourceProvenance(expectedArgentumRevision = tree.revision, outer = tree, argentum = tree))

    @Test fun `attested runtime refuses stale source changed bytes and unregistered build log`() {
        val path = createTempDirectory("local-build")
        val runtime = mapOf("classpath-0" to "synthetic-runtime-hash", "java.version" to "test", "vm-arguments" to "[]")
        Files.writeString(path.resolve("build.log"), "synthetic successful build")
        Files.writeString(path.resolve("classpath.txt"), "synthetic-runtime")
        val invocation = ResearchBuildInvocation(repository = "/synthetic/repository", sourceCommitBefore = tree.revision,
            engineCommitBefore = tree.revision, commands = RESEARCH_BUILD_COMMANDS, exitCode = 0, elapsedMillis = 1.0,
            logSha256 = researchSha256File(path.resolve("build.log")))
        val bindings = researchBuildBindings(source, invocation, runtime)
        Files.writeString(path.resolve("build-invocation.json"), json.encodeToString(invocation))
        Files.writeString(path.resolve("build-report.json"), json.encodeToString(ResearchBuildReport(bindings, source, invocation, runtime)))
        ResearchRunArtifacts(path, bindings.identity).apply {
            listOf("build.log", "classpath.txt", "build-invocation.json", "build-report.json").forEach(::register); finalize()
        }
        val reference = ResearchBuildReference(path.toString(), bindings.identity, researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
        verifyResearchBuild(reference, source, runtime)
        // VM workload options are separately captured by the study; classpath/source may not drift.
        verifyResearchBuild(reference, source, runtime + ("vm-arguments" to "[-Xmx8g]"))
        assertFails { verifyResearchBuild(reference, source.copy(outerCommit = "changed"), runtime) }
        assertFails { verifyResearchBuild(reference, source.copy(outerDirty = true), runtime) }
        assertFails { verifyResearchBuild(reference, source, runtime + ("classpath-0" to "stale-bytecode")) }
        Files.writeString(path.resolve("build.log"), "changed log")
        assertFails { verifyResearchBuild(reference, source, runtime) }
    }

    @Test fun `an incremental or unsuccessful build cannot become a forced-build attestation`() {
        fun invocation(commands: List<List<String>>, exit: Int) = ResearchBuildInvocation(repository = "/synthetic", sourceCommitBefore = "source",
            engineCommitBefore = "engine", commands = commands, exitCode = exit, elapsedMillis = 0.0, logSha256 = "a".repeat(64))
        assertFails { invocation(RESEARCH_BUILD_COMMANDS.map { it - "--rerun-tasks" }, 0) }
        assertFails { invocation(RESEARCH_BUILD_COMMANDS, 1) }
    }
}
