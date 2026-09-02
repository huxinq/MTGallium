package org.mtgallium.evaluation.argentum

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.mtg.sets.tokens.PredefinedTokens
import com.wingedsheep.sdk.model.Deck
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.mtgallium.research.run.PrivateEvidencePaths

internal val reportJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = false
}

internal fun loadDeckManifest(): DeckManifest {
    val stream = requireNotNull(
        DeckManifest::class.java.getResourceAsStream("/decks/mono-red-standard-2026-07-30.json")
    ) { "Deck manifest resource is missing" }
    return stream.bufferedReader().use { reportJson.decodeFromString<DeckManifest>(it.readText()) }
}

internal fun buildRegistry(): CardRegistry = CardRegistry().apply {
    register(PredefinedTokens.allTokens)
    for (set in MtgSetCatalog.all) {
        register(set.cards)
        register(set.basicLands)
        set.basicLandsFallback?.let { register(it.basicLands) }
    }
}

internal fun DeckManifest.mainDeck(): Deck = Deck.of(
    *mainDeck.entries.map { it.key to it.value }.toTypedArray()
)

internal fun diagnosticDeck(): Deck = Deck.of(
    "Mountain" to 30,
    "Raging Goblin" to 10,
)

internal fun DeckManifest.deckHash(): String {
    val canonical = buildString {
        append(id).append('|')
        mainDeck.toSortedMap().forEach { (name, count) -> append("M:").append(name).append('=').append(count).append('|') }
        sideboard.toSortedMap().forEach { (name, count) -> append("S:").append(name).append('=').append(count).append('|') }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun gitOutput(root: Path, vararg args: String): String = try {
    val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) output else "unknown ($output)"
} catch (error: Exception) {
    "unknown (${error.message})"
}

internal fun writeReport(root: Path, report: EvaluationReport) {
    val outputDir = PrivateEvidencePaths.resolve(root, "reports/argentum/latest")
    Files.createDirectories(outputDir)
    Files.writeString(outputDir.resolve("report.json"), reportJson.encodeToString(EvaluationReport.serializer(), report) + "\n")
    Files.writeString(outputDir.resolve("report.md"), renderMarkdown(report))
}

private fun renderMarkdown(report: EvaluationReport): String = buildString {
    appendLine("# Argentum focused adoption evaluation")
    appendLine()
    appendLine("- Argentum commit: `${report.metadata.argentumCommit}`")
    appendLine("- Gym schema: `${report.metadata.gymSchemaHash}`")
    appendLine("- Suite: `${report.metadata.suite}`")
    appendLine("- Deck: `${report.metadata.deckId}` (`${report.metadata.deckHash}`)")
    appendLine("- Overall verdict: **${report.overallVerdict}**")
    appendLine()
    appendLine("## Component decisions")
    appendLine()
    appendLine("| Component | Verdict | Reasons |")
    appendLine("|---|---|---|")
    report.decisions.forEach { decision ->
        appendLine("| ${decision.component} | ${decision.verdict} | ${decision.reasons.joinToString("; ")} |")
    }
    appendLine()
    appendLine("## Probes")
    appendLine()
    appendLine("| Probe | Component | Status | Severity | Finding |")
    appendLine("|---|---|---|---|---|")
    report.probes.forEach { probe ->
        appendLine("| `${probe.id}` | ${probe.component} | ${probe.status} | ${probe.severity} | ${probe.summary.replace("|", "\\|")} |")
    }
    if (report.corpora.isNotEmpty()) {
        appendLine()
        appendLine("## Reliability corpora")
        appendLine()
        appendLine("| Corpus | Complete | Rejected | Truncated | Exceptions | Steps | Wall time |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|")
        report.corpora.forEach { corpus ->
            appendLine("| `${corpus.id}` | ${corpus.completedGames}/${corpus.requestedGames} | ${corpus.rejectedGames} | ${corpus.truncatedGames} | ${corpus.exceptions} | ${corpus.totalSteps} | ${corpus.wallClockMillis} ms |")
        }
    }
    if (report.metrics.isNotEmpty()) {
        appendLine()
        appendLine("## Performance")
        appendLine()
        appendLine("| Metric | Value | Samples | Aggregation |")
        appendLine("|---|---:|---:|---|")
        report.metrics.forEach { metric ->
            appendLine("| `${metric.id}` | ${"%.3f".format(java.util.Locale.ROOT, metric.value)} ${metric.unit} | ${metric.samples} | ${metric.aggregation} |")
        }
    }
    appendLine()
    appendLine("Generated evidence is descriptive of the pinned commit and test environment; it is not a claim of human-level play.")
}
