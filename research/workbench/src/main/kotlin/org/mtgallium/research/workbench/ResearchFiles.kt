package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.engineSerializersModule
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

/** Engine-state records are privileged research data. */
val researchJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    allowStructuredMapKeys = true
    serializersModule = engineSerializersModule
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> readJson(path: Path): T =
    Files.newInputStream(path).buffered().use { researchJson.decodeFromStream<T>(it) }

/** Failed encoding leaves the previous file intact. */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> writeJson(path: Path, value: T) {
    val destination = path.toAbsolutePath()
    Files.createDirectories(destination.parent)
    val temporary = Files.createTempFile(destination.parent, ".research-", ".tmp")
    try {
        Files.newOutputStream(temporary).buffered().use { researchJson.encodeToStream(value, it) }
        try {
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** Consume only needed records; the file closes on success, early return, or failure.
 * The sequence belongs to this call: consume it inside [read], not after the stream closes. */
fun <T> useJsonLines(path: Path, read: (Sequence<JsonElement>) -> T): T = Files.newInputStream(path).use { file ->
    val input = if (path.toString().endsWith(".gz")) GZIPInputStream(file) else file
    input.bufferedReader().useLines { lines -> read(lines.filter(String::isNotBlank).map(researchJson::parseToJsonElement)) }
}

/** A new log is deliberately not an implicit overwrite of a previous run. */
fun openJsonLines(path: Path): BufferedWriter {
    path.toAbsolutePath().parent?.let(Files::createDirectories)
    val file = Files.newOutputStream(path, CREATE_NEW)
    return (if (path.toString().endsWith(".gz")) GZIPOutputStream(file) else file).bufferedWriter()
}

inline fun <reified T> BufferedWriter.writeRecord(value: T) {
    write(researchJson.encodeToString(value))
    newLine()
    flush()
}

/** Informational context only; a dirty or non-Git checkout remains runnable. */
fun executionContext(): JsonObject {
    fun git(vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText().trim() }
        text.takeIf { process.waitFor() == 0 }
    } catch (_: java.io.IOException) { null }
    val source = System.getenv("MTGALLIUM_SOURCE_ROOT")
    return buildJsonObject {
        put("workingDirectory", System.getProperty("user.dir"))
        put("sourceDirectory", source?.let(::JsonPrimitive) ?: JsonNull)
        put("startedAt", java.time.Instant.now().toString())
        put("checkoutRevision", source?.let { git("-C", it, "rev-parse", "HEAD") }?.let(::JsonPrimitive) ?: JsonNull)
        put("checkoutStatus", source?.let { git("-C", it, "status", "--porcelain") }?.let(::JsonPrimitive) ?: JsonNull)
        put("engineCheckoutRevision", source?.let { git("-C", "$it/third_party/argentum-engine", "rev-parse", "HEAD") }?.let(::JsonPrimitive) ?: JsonNull)
        put("engineCheckoutStatus", source?.let { git("-C", "$it/third_party/argentum-engine", "status", "--porcelain") }?.let(::JsonPrimitive) ?: JsonNull)
        put("javaVersion", System.getProperty("java.version"))
        put("processors", Runtime.getRuntime().availableProcessors())
        put("note", "Observed checkout context, not verified execution identity. Uncommitted changes or explicitly reused compiled code are not captured by a commit SHA alone.")
    }
}
