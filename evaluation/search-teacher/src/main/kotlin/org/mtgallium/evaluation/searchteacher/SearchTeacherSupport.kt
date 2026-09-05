package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.mtg.sets.tokens.PredefinedTokens
import com.wingedsheep.sdk.model.Deck
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.mtgallium.agent.infoset.core.PublicArtifactPrivacy
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance

internal val evidenceJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    classDiscriminator = "type"
}

internal fun loadDeckManifest(path: Path? = null): DeckManifest = if (path == null) {
    SearchTeacherDeckManifest.frozenMonoRed()
} else {
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
        "Private deck manifest must be an ordinary file: $path"
    }
    evidenceJson.decodeFromString<DeckManifest>(Files.readString(path))
}

internal fun loadSearchGrid(): SearchGridManifest = loadResource("/profiles/search-grid-v3.json")

private inline fun <reified T> loadResource(name: String): T {
    val stream = requireNotNull(DeckManifest::class.java.getResourceAsStream(name)) {
        "Required search-teacher resource is missing: $name"
    }
    return stream.bufferedReader().use { evidenceJson.decodeFromString<T>(it.readText()) }
}

internal fun buildRegistry(): CardRegistry = CardRegistry().apply {
    register(PredefinedTokens.allTokens)
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
        set.basicLandsFallback?.let { register(it.basicLands) }
    }
}

internal fun DeckManifest.deck(): Deck = Deck.of(
    *mainDeck.entries.map { it.key to it.value }.toTypedArray()
)

internal fun DeckManifest.deckHash(): String = sha256(
    mainDeck.toSortedMap().entries.joinToString("|") { (name, count) -> "$name=$count" }
)

internal fun DeckManifest.cardPoolHash(): String = sha256(
    (mainDeck.keys + sideboard.keys).sorted().joinToString("|")
)

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .fastLowerHex()

internal fun sha256File(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().fastLowerHex()
}

private fun ByteArray.fastLowerHex(): String {
    val alphabet = "0123456789abcdef"
    val chars = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        chars[index * 2] = alphabet[value ushr 4]
        chars[index * 2 + 1] = alphabet[value and 0x0f]
    }
    return String(chars)
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

private val processProvenance: RunProvenance by lazy {
    val workingDirectory = Path.of("").toAbsolutePath().normalize()
    val repositoryRoot = Path.of(gitOutput(workingDirectory, "rev-parse", "--show-toplevel"))
        .toAbsolutePath()
        .normalize()
    RunProvenance.capture(repositoryRoot)
}

internal fun currentOuterCommit(): String = processProvenance.outerCommit

internal fun currentArgentumCommit(): String = processProvenance.checkedOutArgentumCommit

internal fun currentSourceProvenance(): org.mtgallium.agent.infoset.core.PolicySourceProvenance =
    requireNotNull(processProvenance.sourceProvenance) {
        "Current source provenance was not captured"
    }

internal inline fun <reified T> writePublicJsonAtomically(path: Path, value: T): Path {
    val encoded = evidenceJson.encodeToString(value)
    PublicArtifactPrivacy.requireSafeJson(encoded, path.toString())
    return EvidenceStore(path.toAbsolutePath().root).writeEncoded(path, encoded + "\n")
}

internal inline fun <reified T> writeJsonAtomically(path: Path, value: T) {
    EvidenceStore(path.toAbsolutePath().root).writeEncoded(
        path,
        evidenceJson.encodeToString(value) + "\n",
    )
}

internal fun writeTextAtomically(path: Path, value: String): Path =
    EvidenceStore(path.toAbsolutePath().root).writeEncoded(path, value)

internal fun percentile(values: List<Double>, quantile: Double): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val index = kotlin.math.ceil(quantile * sorted.size).toInt().coerceIn(1, sorted.size) - 1
    return sorted[index]
}
