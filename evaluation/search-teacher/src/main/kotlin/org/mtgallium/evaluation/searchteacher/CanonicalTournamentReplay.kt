package org.mtgallium.evaluation.searchteacher

import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayHeader
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayJson
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayRecord
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayRecorder
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayReconstructor
import org.mtgallium.evaluation.searchteacher.replay.ReplayCompletionStatus
import org.mtgallium.evaluation.searchteacher.replay.ReplayIncompleteReason
import org.mtgallium.evaluation.searchteacher.replay.ReplayTransitionOrigin
import com.wingedsheep.engine.state.GameState
import java.io.BufferedWriter
import java.io.Closeable
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionDiagnostic
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.argentum.ArgentumRawTransition

/** New-write-only canonical replay path; TournamentReplay.kt remains the legacy decoder. */
internal class CanonicalTournamentReplayWriter private constructor(
    private val options: GameReplayOptions,
    private val recorder: CanonicalReplayRecorder,
    private val output: FileOutputStream,
    private val writer: BufferedWriter,
    val partialPath: Path,
) : Closeable {
    private var closed = false
    private var terminalWritten = false

    init { appendRecord(recorder.header) }

    fun appendChoice(
        decisionIndex: Int,
        semanticChoice: SemanticChoice,
        opponentPolicyDecision: OpponentPolicyDecisionDiagnostic?,
        rawTransitions: List<ArgentumRawTransition>,
    ) {
        require(rawTransitions.isNotEmpty()) { "A canonical replay choice needs at least one raw transition" }
        rawTransitions.forEachIndexed { rawIndex, raw ->
            val extensions = if (rawIndex == 0) {
                buildJsonObject {
                    put("mtgallium.decisionIndex", JsonPrimitive(decisionIndex))
                    put(
                        "mtgallium.semanticChoice",
                        PolicyJson.format.encodeToJsonElement(SemanticChoice.serializer(), semanticChoice),
                    )
                    opponentPolicyDecision?.let { diagnostic ->
                        put(
                            "mtgallium.opponentPolicyDecision",
                            PolicyJson.format.encodeToJsonElement(
                                OpponentPolicyDecisionDiagnostic.serializer(),
                                diagnostic,
                            ),
                        )
                    }
                }
            } else JsonObject(emptyMap())
            appendRecord(
                recorder.appendAction(
                    origin = ReplayTransitionOrigin.POLICY,
                    action = raw.action,
                    accepted = raw.accepted,
                    rejectionReason = raw.rejectionReason,
                    resultingState = raw.afterState,
                    events = raw.events,
                    extensions = extensions,
                )
            )
        }
    }

    fun finish(
        finalState: GameState,
        complete: Boolean,
        winnerId: String?,
        incompleteReason: ReplayIncompleteReason? = null,
    ): ReplayArtifact {
        check(!terminalWritten)
        appendRecord(
            recorder.finish(
                status = if (complete) ReplayCompletionStatus.COMPLETE else ReplayCompletionStatus.INCOMPLETE,
                finalState = finalState,
                winnerId = winnerId,
                incompleteReason = incompleteReason ?: ReplayIncompleteReason.UNKNOWN.takeUnless { complete },
            )
        )
        terminalWritten = true
        close()

        val target = options.finalPath
        target.parent?.let(Files::createDirectories)
        val temporary = target.resolveSibling("${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.newInputStream(partialPath).use { source ->
                GZIPOutputStream(Files.newOutputStream(temporary)).use(source::copyTo)
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            val verification = CanonicalTournamentReplayVerifier.verify(target)
            return ReplayArtifact(
                referencePath = options.referencePath,
                sha256 = sha256File(target),
                verified = verification.verified,
                verificationDiagnostic = verification.diagnostic,
            ).also { artifact -> if (artifact.verified) Files.deleteIfExists(partialPath) }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun preservePartial() {
        runCatching { close() }
    }

    private fun appendRecord(record: CanonicalReplayRecord) {
        check(!closed)
        writer.write(CanonicalReplayJson.encodeToString(CanonicalReplayRecord.serializer(), record))
        writer.newLine()
        writer.flush()
        output.channel.force(false)
    }

    override fun close() {
        if (closed) return
        closed = true
        writer.flush()
        output.channel.force(false)
        writer.close()
    }

    companion object {
        fun create(
            options: GameReplayOptions,
            initialState: GameState,
            initializationEvents: List<com.wingedsheep.engine.core.GameEvent>,
            players: List<String>,
            extensions: JsonObject,
        ): CanonicalTournamentReplayWriter {
            options.finalPath.parent?.let(Files::createDirectories)
            val name = options.finalPath.fileName.toString().removeSuffix(".gz")
            val partial = options.finalPath.resolveSibling(
                "$name.${UUID.randomUUID()}.partial.canonical.replay.jsonl"
            )
            val output = FileOutputStream(partial.toFile(), false)
            val buffered = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
            val recorder = CanonicalReplayRecorder(
                gameId = extensions["gameId"]?.let { (it as JsonPrimitive).content } ?: error("gameId missing"),
                createdAtUtc = extensions["createdAtUtc"]?.let { (it as JsonPrimitive).content }
                    ?: error("createdAtUtc missing"),
                engineVersion = options.argentumCommit,
                producer = "mtgallium-search-teacher",
                players = players,
                initialState = initialState,
                initializationEvents = initializationEvents,
                extensions = extensions,
            )
            return CanonicalTournamentReplayWriter(options, recorder, output, buffered, partial)
        }
    }
}

internal object CanonicalTournamentReplayVerifier {
    fun verify(path: Path): ReplayVerificationResult = runCatching {
        val records = readCanonicalReplay(path)
        val replay = CanonicalReplayReconstructor.reconstruct(records)
        ReplayVerificationResult(
            verified = true,
            decisions = replay.transitions.count { "mtgallium.semanticChoice" in it.extensions },
        )
    }.getOrElse { error ->
        val replayFrame = error.stackTrace.firstOrNull {
            it.className.startsWith("org.mtgallium.evaluation.searchteacher.replay.")
        }
        ReplayVerificationResult(
            false,
            diagnostic = buildString {
                append("${error::class.simpleName}: ${error.message}")
                replayFrame?.let { append(" at ${it.fileName}:${it.lineNumber}") }
            },
        )
    }
}

internal fun readCanonicalReplay(path: Path): List<CanonicalReplayRecord> =
    GZIPInputStream(Files.newInputStream(path)).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
        lines.filter(String::isNotBlank).map {
            CanonicalReplayJson.decodeFromString(CanonicalReplayRecord.serializer(), it)
        }.toList()
    }

internal fun canonicalTournamentReplayExtensions(
    gameId: String,
    createdAtUtc: String,
    options: GameReplayOptions,
    gameSeed: Long,
    baseSeed: Long,
    manifest: DeckManifest,
): JsonObject = buildJsonObject {
    put("gameId", JsonPrimitive(gameId))
    put("createdAtUtc", JsonPrimitive(createdAtUtc))
    put("mtgallium.runIdentity", JsonPrimitive(options.runIdentity))
    put("mtgallium.outerCommit", JsonPrimitive(options.outerCommit))
    put("mtgallium.argentumCommit", JsonPrimitive(options.argentumCommit))
    put("mtgallium.gameSeed", JsonPrimitive(gameSeed))
    put("mtgallium.baseSeed", JsonPrimitive(baseSeed))
    put("mtgallium.deckHash", JsonPrimitive(manifest.deckHash()))
    put("mtgallium.cardPoolHash", JsonPrimitive(manifest.cardPoolHash()))
}
