package org.mtgallium.evaluation.searchteacher.evidence

import java.nio.file.Path
import org.mtgallium.research.run.PrivateEvidencePaths
import org.mtgallium.research.run.ResearchRunFiles
import org.mtgallium.research.run.ResearchRunProvenance

private fun canonicalEvidenceRelative(relative: String): String {
    require(relative.isNotBlank()) { "Evidence path cannot be blank" }
    val portable = relative.replace('\\', '/')
    require(!portable.startsWith('/') && ':' !in portable) { "Evidence path must be repository-relative: $relative" }
    val normalized = Path.of(portable).normalize()
    require(!normalized.isAbsolute && !normalized.startsWith("..")) { "Artifact path escapes its root: $relative" }
    return normalized.toString().replace('\\', '/')
}

internal enum class EvidenceLocation(val directory: String) {
    WORK("reports/search-teacher/work"),
    /** Historical artifact path names retained for readers; they confer no approval or lifecycle state. */
    LATEST("reports/search-teacher/latest"),
    FROZEN("reports/search-teacher/frozen"),
    REVIEW("reports/search-teacher/reviews"),

    ;

    fun relativePath(relative: String): String {
        val normalized = canonicalEvidenceRelative(relative)
        return "$directory/$normalized"
    }
}

internal class EvidenceStore(private val root: Path) {
    val workRoot: Path get() = directory(EvidenceLocation.WORK)
    val latestRoot: Path get() = directory(EvidenceLocation.LATEST)
    val frozenRoot: Path get() = directory(EvidenceLocation.FROZEN)
    val reviewRoot: Path get() = directory(EvidenceLocation.REVIEW)

    fun work(relative: String): Path = resolve(EvidenceLocation.WORK, relative)
    fun latest(relative: String): Path = resolve(EvidenceLocation.LATEST, relative)
    fun frozen(relative: String): Path = resolve(EvidenceLocation.FROZEN, relative)
    fun review(relative: String): Path = resolve(EvidenceLocation.REVIEW, relative)

    /** Returns a safe exploratory output path beneath the work tree. */
    fun diagnostic(relative: String, description: String): Path =
        requireDiagnosticOutput(work(relative), description)

    fun requireDiagnosticOutput(path: Path, description: String): Path {
        require(description.isNotBlank()) { "Diagnostic output description cannot be blank" }
        val base = workRoot.toAbsolutePath().normalize()
        val target = path.toAbsolutePath().normalize()
        require(target != base && target.startsWith(base)) {
            diagnosticDestinationRefusal(description, target)
        }
        return try {
            ResearchRunFiles.resolveBelow(base, base.relativize(target).toString())
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(diagnosticLinkRefusal(description, target), error)
        }
    }

    fun resolve(location: EvidenceLocation, relative: String): Path {
        return ResearchRunFiles.resolveBelow(directory(location), canonicalEvidenceRelative(relative))
    }

    private fun directory(location: EvidenceLocation): Path =
        PrivateEvidencePaths.resolve(root, location.directory)

    private fun diagnosticDestinationRefusal(description: String, target: Path): String =
        "This producer would write $description to $target outside the configured private Search Teacher work root. " +
            "Choose a file or child directory beneath that root. This check protects historical artifacts."

    private fun diagnosticLinkRefusal(description: String, link: Path): String =
        "This producer would write $description through the filesystem link $link. A work-looking path could " +
            "therefore alter an unrelated or historical artifact. Use an ordinary path beneath the configured " +
            "private work root."

    fun writeEncoded(path: Path, encoded: String): Path {
        return ResearchRunFiles.atomicWrite(path, encoded)
    }
}

/** Compatibility import for the former evaluation-local source capture name. */
internal typealias RunProvenance = ResearchRunProvenance
