package org.mtgallium.evaluation.searchteacher.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import org.mtgallium.evaluation.searchteacher.*
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance

/** Invocation-scoped dependencies. Corpus and inspection commands need not construct the engine. */
internal class SearchTeacherCommandContext(val root: Path, val options: SearchTeacherCli) {
    val store = EvidenceStore(root)
    val provenance by lazy { RunProvenance.capture(root) }
    val manifest by lazy { loadDeckManifest(options.deckManifest) }
    val registry by lazy(::buildRegistry)
    val profile by lazy { loadProfile(root, options) }
    val arena by lazy { SearchTeacherArena(registry, manifest, profile, options.seed) }

    fun requireCurrentSource() = provenance.requireReady()

    fun prepareArena() {
        // Preserve legacy initialization order, even for commands that only use part of the arena.
        profile
        arena
        println("Search-teacher ${options.suite}: ${registry.size} cards, profile ${profile.id}")
    }

    fun diagnosticOutput(relative: String): Path =
        store.diagnostic(relative, "the ${options.suite} command output")

    fun diagnosticOutput(path: Path): Path =
        store.requireDiagnosticOutput(path, "the ${options.suite} command output")

    inline fun <reified T> readPlan(): T =
        evidenceJson.decodeFromString(Files.readString(requireNotNull(options.profilePath)))
}

private fun loadProfile(root: Path, options: SearchTeacherCli): FrozenSearchProfile {
    val path = options.profilePath ?: EvidenceStore(root).frozen("fast-profile-v1.json")
    if (Files.exists(path)) return evidenceJson.decodeFromString(Files.readString(path))
    require(options.suite in setOf(
        "smoke", "calibrate", "latency-preflight", "tactical-authoring", "tactical-horizon-authoring", "inspection"
    )) {
        "A calibrated frozen profile is required for ${options.suite}: $path"
    }
    return SearchTeacherArena.smokeProfile()
}
