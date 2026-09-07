package org.mtgallium.research.run

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val buildJson = Json { prettyPrint = true; encodeDefaults = true }

@Serializable
data class ResearchBuildInvocation(
    val schemaVersion: Int = 1,
    val repository: String,
    val sourceCommitBefore: String,
    val engineCommitBefore: String,
    val commands: List<List<String>>,
    val exitCode: Int,
    val elapsedMillis: Double,
    val logSha256: String,
) {
    init {
        require(schemaVersion == 1 && Path.of(repository).isAbsolute && exitCode == 0)
        require(elapsedMillis.isFinite() && elapsedMillis >= 0 && logSha256.matches(Regex("[0-9a-f]{64}")))
        require(commands == RESEARCH_BUILD_COMMANDS) { "Build record does not describe the supported forced rebuild" }
    }
}

val RESEARCH_BUILD_COMMANDS = listOf(
    listOf("bash", "third_party/argentum-engine/gradlew", "--project-dir", "third_party/argentum-engine", "clean", "--no-daemon"),
    listOf("bash", "tools/mtgallium-gradle", "clean", ":evaluation:search-teacher:researchRuntimeClasspath",
        "--rerun-tasks", "--no-build-cache", "-Pkotlin.incremental=false"),
)

@Serializable
data class ResearchBuildReport(
    val bindings: ResearchRunBindings,
    val source: ResearchRunProvenance,
    val invocation: ResearchBuildInvocation,
    val runtime: Map<String, String>,
    val interpretation: String = "Local build attestation: the supported wrapper observed a successful forced rebuild from unchanged clean source and retained its command/log with the frozen runtime bytes. Verification detects changed source or binaries. This is not an independently reproduced build or a signature from an external trusted builder; a party able to fabricate local records remains outside this assurance.",
)

@Serializable
data class ResearchBuildReference(val directory: String, val identity: String, val manifestSha256: String) {
    init { require(Path.of(directory).isAbsolute && identity.isNotBlank() && manifestSha256.matches(Regex("[0-9a-f]{64}"))) }
}

private fun buildRuntimeMaterial(runtime: Map<String, String>): Map<String, String> = runtime.filterKeys { it != "vm-arguments" }.toSortedMap()

/** Workload-specific VM arguments remain bound by the study, separately from the source build. */
fun verifyResearchBuild(reference: ResearchBuildReference, source: ResearchRunProvenance, runtime: Map<String, String> = researchPreflightRuntime()): ResearchBuildReport {
    val directory = Path.of(reference.directory)
    require(researchSha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == reference.manifestSha256)
    val manifest = ResearchRunArtifacts.loadAndVerify(directory, reference.identity)
    require(manifest.artifacts.map { it.relativePath }.containsAll(listOf("build-report.json", "build-invocation.json", "build.log", "classpath.txt")))
    val report = buildJson.decodeFromString<ResearchBuildReport>(Files.readString(directory.resolve("build-report.json")))
    val invocation = buildJson.decodeFromString<ResearchBuildInvocation>(Files.readString(directory.resolve("build-invocation.json")))
    require(report.bindings.identity == reference.identity && report.invocation == invocation)
    require(source == report.source && !source.outerDirty && !source.engineDirty && source.consistent) { "Build source differs from the clean execution source" }
    require(invocation.sourceCommitBefore == source.outerCommit && invocation.engineCommitBefore == source.checkedOutEngineCommit)
    require(invocation.logSha256 == researchSha256File(directory.resolve("build.log")))
    require(buildRuntimeMaterial(report.runtime) == buildRuntimeMaterial(runtime)) { "Execution runtime differs from the attested frozen build" }
    require(report.bindings == researchBuildBindings(source, invocation, report.runtime))
    return report
}

internal fun researchBuildBindings(source: ResearchRunProvenance, invocation: ResearchBuildInvocation, runtime: Map<String, String>) =
    ResearchRunBindings(protocol = "research-local-build-v1", material = mapOf(
        "source" to researchSha256(buildJson.encodeToString(source)), "invocation" to researchSha256(buildJson.encodeToString(invocation)),
        "runtime" to researchSha256(buildJson.encodeToString<Map<String, String>>(runtime.toSortedMap())),
    ))

/** Called only by tools/mtgallium-research-build after the forced build and private runtime copy. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Expected repository and private build directory" }
    val repository = Path.of(args[0]).toAbsolutePath().normalize(); val directory = Path.of(args[1]).toAbsolutePath().normalize()
    require(!directory.startsWith(repository) && !Files.exists(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
    val source = ResearchRunProvenance.capture(repository).also { it.requireReady() }
    require(!source.outerDirty && !source.engineDirty)
    val invocation = buildJson.decodeFromString<ResearchBuildInvocation>(Files.readString(directory.resolve("build-invocation.json")))
    require(Path.of(invocation.repository) == repository && invocation.sourceCommitBefore == source.outerCommit && invocation.engineCommitBefore == source.checkedOutEngineCommit)
    require(researchSha256File(directory.resolve("build.log")) == invocation.logSha256)
    val classpath = System.getProperty("java.class.path")
    require(classpath == Files.readString(directory.resolve("classpath.txt")).trim())
    classpath.split(java.io.File.pathSeparator).forEach { path ->
        val entry = Path.of(path).toAbsolutePath().normalize()
        require(entry.startsWith(directory.resolve("lib")) && Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".jar"))
    }
    val runtime = researchPreflightRuntime(); val bindings = researchBuildBindings(source, invocation, runtime)
    ResearchRunFiles.atomicWrite(directory.resolve("build-report.json"), buildJson.encodeToString(ResearchBuildReport(bindings, source, invocation, runtime)))
    ResearchRunArtifacts(directory, bindings.identity).also { artifacts ->
        Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { artifacts.register(directory.relativize(it).toString()) } }
        artifacts.finalize()
    }
    val reference = ResearchBuildReference(directory.toString(), bindings.identity, researchSha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
    verifyResearchBuild(reference, source, runtime)
    println(buildJson.encodeToString(reference))
}
