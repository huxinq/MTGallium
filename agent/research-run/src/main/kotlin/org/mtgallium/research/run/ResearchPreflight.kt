package org.mtgallium.research.run

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val preflightJson = Json { prettyPrint = true; encodeDefaults = true }

@Serializable
data class ResearchPreflightCheck(val name: String, val passed: Boolean, val detail: String)

/** Technical readiness only. A pass contains no scientific outcome threshold. */
@Serializable
data class ResearchPreflightReport(
    val schemaVersion: Int = 1,
    val bindings: ResearchRunBindings,
    val checks: List<ResearchPreflightCheck>,
    val workload: Map<String, String>,
    /** Child manifests are registered in the parent and all child artifacts are reverified on reuse. */
    val childRuns: Map<String, String> = emptyMap(),
) {
    init {
        require(schemaVersion == 1)
        require(checks.isNotEmpty() && checks.map { it.name }.distinct().size == checks.size)
    }
    val passed: Boolean get() = checks.all { it.passed }
}

object ResearchPreflights {
    const val REPORT_FILE = "preflight-report.json"

    fun persist(output: Path, report: ResearchPreflightReport, artifacts: List<String> = emptyList()) {
        ResearchRunFiles.atomicWrite(ResearchRunFiles.resolveBelow(output, REPORT_FILE), preflightJson.encodeToString(report))
        val registry = ResearchRunArtifacts(output, report.bindings.identity)
        registry.register(REPORT_FILE)
        artifacts.forEach(registry::register)
        report.childRuns.forEach { (relative, identity) ->
            val child = ResearchRunFiles.resolveBelow(output, relative)
            ResearchRunArtifacts.loadAndVerify(child, identity)
            registry.register("$relative/${ResearchRunArtifacts.MANIFEST_FILE}")
        }
        registry.finalize()
    }

    /** Call with freshly captured bindings immediately before launch; changed inputs never reuse a pass. */
    fun verify(output: Path, expected: ResearchRunBindings): ResearchPreflightReport {
        val manifest = ResearchRunArtifacts.loadAndVerify(output, expected.identity)
        require(manifest.artifacts.any { it.relativePath == REPORT_FILE }) { "Preflight report is not registered" }
        val report = preflightJson.decodeFromString<ResearchPreflightReport>(
            Files.readString(ResearchRunFiles.resolveBelow(output, REPORT_FILE)))
        require(report.bindings == expected && report.passed) { "Preflight is failed or belongs to another workload" }
        report.childRuns.forEach { (relative, identity) ->
            require(manifest.artifacts.any { it.relativePath == "$relative/${ResearchRunArtifacts.MANIFEST_FILE}" })
            ResearchRunArtifacts.loadAndVerify(ResearchRunFiles.resolveBelow(output, relative), identity)
        }
        return report
    }
}

/** Binds the actual JVM and ordered classpath bytes, not a claim that binaries were built from a SHA. */
fun researchPreflightRuntime(): Map<String, String> = buildMap {
    listOf("java.version", "java.vendor", "java.vm.name", "os.name", "os.arch").forEach {
        put(it, System.getProperty(it))
    }
    put("vm-arguments", preflightJson.encodeToString(ManagementFactory.getRuntimeMXBean().inputArguments))
    val javaHome = Path.of(System.getProperty("java.home"))
    put("java-executable", researchSha256File(javaHome.resolve("bin/java")))
    put("java-modules", researchSha256File(javaHome.resolve("lib/modules")))
    System.getProperty("java.class.path").split(java.io.File.pathSeparator).forEachIndexed { index, entry ->
        val path = Path.of(entry).toAbsolutePath().normalize()
        require(Files.exists(path)) { "Classpath entry is missing: $path" }
        val hash = if (Files.isDirectory(path)) {
            val contents = Files.walk(path).use { paths ->
                paths.filter { Files.isRegularFile(it) }.sorted().map {
                    listOf(path.relativize(it).toString(), researchSha256File(it))
                }.toList()
            }
            researchSha256(preflightJson.encodeToString(contents))
        } else researchSha256File(path)
        put("classpath-$index", "$path:$hash")
    }
}
