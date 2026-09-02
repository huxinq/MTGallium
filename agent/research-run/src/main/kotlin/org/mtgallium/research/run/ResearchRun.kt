package org.mtgallium.research.run

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val researchRunJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

private val sha256Pattern = Regex("[0-9a-f]{64}")

fun researchSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).lowerHex()

fun researchSha256(value: String): String = researchSha256(value.toByteArray(StandardCharsets.UTF_8))

fun researchSha256File(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().lowerHex()
}

@Serializable
data class ResearchSourceTreeState(
    val revision: String,
    val trackedDiffSha256: String,
    val untrackedContentSha256: String,
    val statusSha256: String,
) {
    init {
        require(revision.isNotBlank())
        listOf(trackedDiffSha256, untrackedContentSha256, statusSha256).forEach {
            require(it.matches(sha256Pattern)) { "Source fingerprints must be lowercase SHA-256 values" }
        }
    }
}

/** Exact source/engine state. It is a scientific binding only when a caller includes it in its material bindings. */
@Serializable
data class ResearchSourceProvenance(
    val schemaVersion: Int = 1,
    /** Historical field names are retained because real evidence serializes them. */
    val expectedArgentumRevision: String,
    val outer: ResearchSourceTreeState,
    val argentum: ResearchSourceTreeState,
) {
    init {
        require(schemaVersion == 1)
        require(expectedArgentumRevision.isNotBlank())
    }

    val engineGitlinkMatchesCheckout: Boolean get() = expectedArgentumRevision == argentum.revision
    val gitlinkMatchesCheckout: Boolean get() = engineGitlinkMatchesCheckout
}

@Serializable
data class ResearchRunProvenance(
    val outerCommit: String,
    val expectedEngineCommit: String,
    val checkedOutEngineCommit: String,
    val outerDirty: Boolean,
    val engineDirty: Boolean,
    val sourceProvenance: ResearchSourceProvenance,
) {
    val expectedArgentumCommit: String get() = expectedEngineCommit
    val checkedOutArgentumCommit: String get() = checkedOutEngineCommit
    val argentumDirty: Boolean get() = engineDirty
    val consistent: Boolean get() = expectedEngineCommit == checkedOutEngineCommit

    fun requireReady() {
        require(consistent) {
            "Engine checkout $checkedOutEngineCommit does not match gitlink $expectedEngineCommit"
        }
    }

    companion object {
        fun capture(root: Path, engineRelativePath: String = "third_party/argentum-engine"): ResearchRunProvenance {
            val repositoryRoot = root.toAbsolutePath().normalize()
            val engineRoot = repositoryRoot.resolve(engineRelativePath).normalize()
            require(engineRoot.startsWith(repositoryRoot)) { "Engine path escapes repository: $engineRelativePath" }
            val outerCommit = gitText(repositoryRoot, "rev-parse", "HEAD")
            val expectedEngineCommit = gitText(repositoryRoot, "rev-parse", "HEAD:$engineRelativePath")
            val checkedOutEngineCommit = gitText(engineRoot, "rev-parse", "HEAD")
            val outer = captureSourceTree(repositoryRoot, outerCommit)
            val engine = captureSourceTree(engineRoot, checkedOutEngineCommit)
            return ResearchRunProvenance(
                outerCommit, expectedEngineCommit, checkedOutEngineCommit, outer.dirty, engine.dirty,
                ResearchSourceProvenance(expectedArgentumRevision = expectedEngineCommit, outer = outer.state, argentum = engine.state),
            )
        }
    }
}

/** Immutable named bindings. Values must already be stable scientific identities, revisions, or hashes. */
@Serializable
data class ResearchRunBindings(
    val schemaVersion: Int = 1,
    val protocol: String,
    val material: Map<String, String>,
) {
    init {
        require(schemaVersion == 1)
        require(protocol.isNotBlank())
        require(material.isNotEmpty())
        material.forEach { (name, value) ->
            require(name.matches(Regex("[a-z][a-z0-9-]*"))) { "Invalid material binding name: $name" }
            require(value.isNotBlank()) { "Material binding $name cannot be blank" }
        }
    }

    val identity: String get() = "research-run-v1-sha256:${researchSha256(canonicalMaterial())}"

    private fun canonicalMaterial(): String = buildString {
        append("schema=1\nprotocol=").append(protocol).append('\n')
        material.toSortedMap().forEach { (name, value) ->
            append(name.length).append(':').append(name).append('=').append(value.length).append(':').append(value).append('\n')
        }
    }
}

@Serializable
enum class ResearchRunState { PREPARED, RUNNING, COMPLETE, STOPPED, FAILED }

/** Operational facts deliberately excluded from [ResearchRunBindings]. */
@Serializable
data class ResearchRunOperationalMetadata(
    val state: ResearchRunState,
    val updatedAtUtc: String = Instant.now().toString(),
    val durableRunId: String? = null,
    val attemptId: String? = null,
)

@Serializable
data class PreparedResearchRun(
    val researchRunIdentity: String,
    val bindings: ResearchRunBindings,
    val sourceProvenance: ResearchSourceProvenance,
) {
    init { require(researchRunIdentity == bindings.identity) { "Prepared identity disagrees with material bindings" } }
}

@Serializable
data class ResearchRunCheckpointEnvelope(
    val schemaVersion: Int = 1,
    val researchRunIdentity: String,
    val payloadSchema: String,
    val sequence: Long,
    val parentPayloadSha256: String? = null,
    val payloadSha256: String,
    val payloadBase64: String,
) {
    init {
        require(researchRunIdentity.isNotBlank())
        require(payloadSchema.isNotBlank())
        require(sequence >= 0)
        require(parentPayloadSha256 == null || parentPayloadSha256.matches(sha256Pattern))
        require(payloadSha256.matches(sha256Pattern))
        require(researchSha256(Base64.getDecoder().decode(payloadBase64)) == payloadSha256) {
            "Checkpoint payload hash does not match payload"
        }
    }

    fun payload(): ByteArray = Base64.getDecoder().decode(payloadBase64)
}

object ResearchRunCheckpoints {
    fun persist(
        path: Path,
        researchRunIdentity: String,
        payloadSchema: String,
        sequence: Long,
        payload: ByteArray,
        parentPayloadSha256: String? = null,
    ): ResearchRunCheckpointEnvelope {
        val envelope = ResearchRunCheckpointEnvelope(
            researchRunIdentity = researchRunIdentity,
            payloadSchema = payloadSchema,
            sequence = sequence,
            parentPayloadSha256 = parentPayloadSha256,
            payloadSha256 = researchSha256(payload),
            payloadBase64 = Base64.getEncoder().encodeToString(payload),
        )
        ResearchRunFiles.atomicWrite(path, researchRunJson.encodeToString(envelope) + "\n")
        return envelope
    }

    fun load(path: Path): ResearchRunCheckpointEnvelope =
        researchRunJson.decodeFromString(Files.readString(path))
}

@Serializable
data class ResearchRunProgress(
    val researchRunIdentity: String,
    val state: ResearchRunState,
    val completed: Long,
    val total: Long? = null,
    val phase: String,
    val detail: String? = null,
    val updatedAtUtc: String = Instant.now().toString(),
) {
    init {
        require(completed >= 0)
        require(total == null || total >= completed)
        require(phase.isNotBlank())
    }
}

object ResearchRunFiles {
    fun resolveBelow(root: Path, relative: String): Path {
        val normalized = relative.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && ':' !in normalized) {
            "Research-run path must be relative: $relative"
        }
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(normalized).normalize()
        require(target != base && target.startsWith(base)) { "Research-run path escapes its root: $relative" }
        rejectSymbolicLinks(base, target)
        return target
    }

    fun atomicWrite(path: Path, content: String): Path = atomicWrite(path, content.toByteArray(StandardCharsets.UTF_8))

    fun atomicWrite(path: Path, content: ByteArray): Path {
        Files.createDirectories(requireNotNull(path.parent))
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.write(temporary, content)
            runCatching { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .getOrElse { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return path
    }

    private fun rejectSymbolicLinks(base: Path, target: Path) {
        var current = base
        require(!Files.isSymbolicLink(current)) { "Research-run root is a symbolic link: $current" }
        base.relativize(target).forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Research-run path crosses a symbolic link: $current" }
        }
    }
}

@Serializable
data class ResearchRunArtifact(val relativePath: String, val sha256: String, val bytes: Long) {
    init { require(relativePath.isNotBlank() && sha256.matches(sha256Pattern) && bytes >= 0) }
}

@Serializable
data class ResearchRunArtifactManifest(
    val schemaVersion: Int = 1,
    val researchRunIdentity: String,
    val state: ResearchRunState = ResearchRunState.COMPLETE,
    val artifacts: List<ResearchRunArtifact>,
) {
    init {
        require(state == ResearchRunState.COMPLETE)
        require(artifacts.isNotEmpty())
        require(artifacts.map(ResearchRunArtifact::relativePath).distinct().size == artifacts.size)
    }
}

/** One final manifest authority: entries are registered from files, then written once as `research-run-manifest.json`. */
class ResearchRunArtifacts(private val outputRoot: Path, private val researchRunIdentity: String) {
    private val registered = linkedMapOf<String, ResearchRunArtifact>()

    fun register(relativePath: String): ResearchRunArtifact {
        require(relativePath != MANIFEST_FILE) { "The final manifest cannot register itself" }
        val path = ResearchRunFiles.resolveBelow(outputRoot, relativePath)
        require(Files.isRegularFile(path)) { "Registered artifact does not exist: $relativePath" }
        return ResearchRunArtifact(relativePath.replace('\\', '/'), researchSha256File(path), Files.size(path)).also {
            check(registered.putIfAbsent(it.relativePath, it) == null) { "Artifact registered twice: ${it.relativePath}" }
        }
    }

    fun finalize(): Path {
        val manifest = ResearchRunArtifactManifest(researchRunIdentity = researchRunIdentity, artifacts = registered.values.toList())
        return ResearchRunFiles.atomicWrite(
            ResearchRunFiles.resolveBelow(outputRoot, MANIFEST_FILE),
            researchRunJson.encodeToString(manifest) + "\n",
        )
    }

    companion object {
        const val MANIFEST_FILE = "research-run-manifest.json"

        fun loadAndVerify(outputRoot: Path, expectedResearchRunIdentity: String? = null): ResearchRunArtifactManifest {
            val manifestPath = ResearchRunFiles.resolveBelow(outputRoot, MANIFEST_FILE)
            val manifest = researchRunJson.decodeFromString<ResearchRunArtifactManifest>(Files.readString(manifestPath))
            require(expectedResearchRunIdentity == null || manifest.researchRunIdentity == expectedResearchRunIdentity) {
                "Artifact manifest belongs to ${manifest.researchRunIdentity}, not $expectedResearchRunIdentity"
            }
            manifest.artifacts.forEach { artifact ->
                val path = ResearchRunFiles.resolveBelow(outputRoot, artifact.relativePath)
                require(Files.isRegularFile(path) && Files.size(path) == artifact.bytes && researchSha256File(path) == artifact.sha256) {
                    "Artifact verification failed: ${artifact.relativePath}"
                }
            }
            return manifest
        }

        /** Read-only compatibility verifier for retained pre-consolidation SHA256SUMS files. */
        fun verifyLegacyChecksums(outputRoot: Path, checksumFile: String = "SHA256SUMS"): List<ResearchRunArtifact> {
            val checksumPath = ResearchRunFiles.resolveBelow(outputRoot, checksumFile)
            require(Files.isRegularFile(checksumPath)) { "Historical checksum manifest does not exist: $checksumFile" }
            return Files.readAllLines(checksumPath).filter { it.isNotBlank() }.map { line ->
                val match = Regex("^([0-9a-f]{64})  (.+)$").matchEntire(line)
                    ?: error("Invalid historical checksum entry: $line")
                val relative = match.groupValues[2]
                val path = ResearchRunFiles.resolveBelow(outputRoot, relative)
                require(Files.isRegularFile(path) && researchSha256File(path) == match.groupValues[1]) {
                    "Historical artifact verification failed: $relative"
                }
                ResearchRunArtifact(relative, match.groupValues[1], Files.size(path))
            }
        }
    }
}

private data class CapturedSourceTree(val state: ResearchSourceTreeState, val dirty: Boolean)

private fun captureSourceTree(root: Path, revision: String): CapturedSourceTree {
    val trackedDiff = git(root, "diff", "--binary", "--no-ext-diff", "--full-index", "--ignore-submodules=none", "HEAD", "--")
    val status = git(root, "status", "--porcelain=v1", "--untracked-files=all", "-z")
    return CapturedSourceTree(
        ResearchSourceTreeState(revision, researchSha256(trackedDiff), untrackedContentSha256(root), researchSha256(status)),
        status.isNotEmpty(),
    )
}

private fun untrackedContentSha256(root: Path): String {
    val listed = git(root, "ls-files", "--others", "--exclude-standard", "-z")
    val paths = splitZeroTerminated(listed).map { String(it, StandardCharsets.UTF_8) }.sorted()
    val digest = MessageDigest.getInstance("SHA-256")
    paths.forEach { relative ->
        val path = root.resolve(relative).normalize()
        require(path.startsWith(root)) { "Git returned an untracked path outside its repository: $relative" }
        val content = when {
            Files.isSymbolicLink(path) -> Files.readSymbolicLink(path).toString().toByteArray(StandardCharsets.UTF_8)
            Files.isRegularFile(path) -> Files.readAllBytes(path)
            else -> error("Unsupported untracked source path: $relative")
        }
        digest.update(relative.toByteArray(StandardCharsets.UTF_8)); digest.update(0)
        digest.update(researchSha256(content).toByteArray(StandardCharsets.UTF_8)); digest.update(0)
    }
    return digest.digest().lowerHex()
}

private fun splitZeroTerminated(bytes: ByteArray): List<ByteArray> {
    val result = mutableListOf<ByteArray>()
    var start = 0
    bytes.forEachIndexed { index, byte ->
        if (byte == 0.toByte()) {
            if (index > start) result += bytes.copyOfRange(start, index)
            start = index + 1
        }
    }
    require(start == bytes.size) { "Expected a NUL-terminated Git path list" }
    return result
}

private fun gitText(root: Path, vararg arguments: String): String = String(git(root, *arguments), StandardCharsets.UTF_8).trim()

private fun git(root: Path, vararg arguments: String): ByteArray {
    val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes()
    require(process.waitFor() == 0) { "Git ${arguments.joinToString(" ")} failed in $root: ${String(output, StandardCharsets.UTF_8).trim()}" }
    return output
}

private fun ByteArray.lowerHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
