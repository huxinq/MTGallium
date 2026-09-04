package org.mtgallium.evaluation.searchteacher.replay

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.state.GameState
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

const val CANONICAL_REPLAY_SCHEMA_V1: Int = 1
const val CANONICAL_REPLAY_SCHEMA_CURRENT: Int = CANONICAL_REPLAY_SCHEMA_V1
const val CANONICAL_REPLAY_FULL_STATE_INTERVAL: Int = 128

/**
 * MTGallium-owned stable codec for the privileged, exhaustive replay contract.
 *
 * Replay state trees are canonicalized separately before hashing. Keeping the wire codec here lets
 * every host use the same action/event polymorphic names and structured-map representation.
 */
val CanonicalReplayJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    classDiscriminator = "type"
    allowStructuredMapKeys = true
    serializersModule = engineSerializersModule
}

@Serializable
enum class ReplayTransitionOrigin {
    PLAYER,
    POLICY,
    AUTO_PASS,
    AUTO_DECISION,
    SYSTEM,
}

@Serializable
enum class ReplayCompletionStatus { COMPLETE, INCOMPLETE }

@Serializable
enum class ReplayIncompleteReason {
    INTERRUPTED,
    ABANDONED,
    RECORDING_FAILURE,
    RESUME_STATE_MISMATCH,
    UNKNOWN,
}

@Serializable
enum class ReplaySystemMutationKind {
    SET_YIELD,
    CLEAR_YIELD,
    CLEAR_ALL_YIELDS,
    UNDO,
    FORCED_TERMINATION,
    OTHER,
}

@Serializable
data class ReplaySystemMutation(
    val kind: ReplaySystemMutationKind,
    val actorId: String? = null,
    val detail: JsonObject = JsonObject(emptyMap()),
)

@Serializable
sealed interface ReplayStateEncoding

@Serializable
@SerialName("full")
data class ReplayFullState(val value: JsonElement) : ReplayStateEncoding

@Serializable
@SerialName("patch")
data class ReplayPatchedState(val operations: List<ReplayPatchOperation>) : ReplayStateEncoding

@Serializable
sealed interface ReplayPatchOperation { val path: String }

@Serializable
@SerialName("set")
data class ReplayPatchSet(
    override val path: String,
    val value: JsonElement,
) : ReplayPatchOperation

@Serializable
@SerialName("remove")
data class ReplayPatchRemove(override val path: String) : ReplayPatchOperation

@Serializable
@SerialName("splice")
data class ReplayPatchSplice(
    override val path: String,
    val start: Int,
    val deleteCount: Int,
    val values: List<JsonElement>,
) : ReplayPatchOperation {
    init {
        require(start >= 0)
        require(deleteCount >= 0)
    }
}

@Serializable
sealed interface CanonicalReplayRecord {
    val schemaVersion: Int
    val gameId: String
    val previousRecordDigest: String?
    val recordDigest: String
}

@Serializable
@SerialName("header")
data class CanonicalReplayHeader(
    override val schemaVersion: Int = CANONICAL_REPLAY_SCHEMA_CURRENT,
    override val gameId: String,
    val createdAtUtc: String,
    val engineVersion: String,
    val producer: String,
    val players: List<String>,
    val initialState: JsonElement,
    val initialStateDigest: String,
    val initializationEvents: List<GameEvent> = emptyList(),
    val extensions: JsonObject = JsonObject(emptyMap()),
    override val previousRecordDigest: String? = null,
    override val recordDigest: String,
) : CanonicalReplayRecord {
    init {
        require(schemaVersion == CANONICAL_REPLAY_SCHEMA_CURRENT)
        require(gameId.isNotBlank())
        require(previousRecordDigest == null)
    }
}

@Serializable
@SerialName("transition")
data class CanonicalReplayTransition(
    override val schemaVersion: Int = CANONICAL_REPLAY_SCHEMA_CURRENT,
    override val gameId: String,
    val ordinal: Int,
    val origin: ReplayTransitionOrigin,
    val actorId: String? = null,
    val submitterId: String? = null,
    val action: GameAction? = null,
    val systemMutation: ReplaySystemMutation? = null,
    val accepted: Boolean,
    val rejectionReason: String? = null,
    val events: List<GameEvent> = emptyList(),
    val state: ReplayStateEncoding,
    val resultingStateDigest: String,
    val extensions: JsonObject = JsonObject(emptyMap()),
    override val previousRecordDigest: String,
    override val recordDigest: String,
) : CanonicalReplayRecord {
    init {
        require(schemaVersion == CANONICAL_REPLAY_SCHEMA_CURRENT)
        require(ordinal >= 0)
        require((action != null) xor (systemMutation != null)) {
            "A replay transition must contain exactly one action or system mutation"
        }
        require(accepted == (rejectionReason == null)) {
            "Accepted transitions cannot have a rejection reason, and rejected transitions must have one"
        }
    }
}

@Serializable
@SerialName("terminal")
data class CanonicalReplayTerminal(
    override val schemaVersion: Int = CANONICAL_REPLAY_SCHEMA_CURRENT,
    override val gameId: String,
    val transitions: Int,
    val status: ReplayCompletionStatus,
    val incompleteReason: ReplayIncompleteReason? = null,
    val winnerId: String? = null,
    val finalStateDigest: String,
    val extensions: JsonObject = JsonObject(emptyMap()),
    override val previousRecordDigest: String,
    override val recordDigest: String,
) : CanonicalReplayRecord {
    init {
        require(schemaVersion == CANONICAL_REPLAY_SCHEMA_CURRENT)
        require(transitions >= 0)
        require((status == ReplayCompletionStatus.COMPLETE) == (incompleteReason == null))
    }
}

/** Canonical JSON tree operations used by replay hashing and lossless state patches. */
object ReplayCanonicalJson {
    fun state(state: GameState): JsonElement = canonicalize(
        CanonicalReplayJson.encodeToJsonElement(GameState.serializer(), state)
    )

    fun canonicalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { (key, child) ->
            key to canonicalize(child)
        })
        is JsonArray -> JsonArray(value.map(::canonicalize))
        else -> value
    }

    fun digest(value: JsonElement): String = sha256(
        CanonicalReplayJson.encodeToString(JsonElement.serializer(), canonicalize(value))
    )

    fun diff(before: JsonElement, after: JsonElement): List<ReplayPatchOperation> {
        val operations = mutableListOf<ReplayPatchOperation>()
        diffAt("", canonicalize(before), canonicalize(after), operations)
        return operations
    }

    fun apply(before: JsonElement, operations: List<ReplayPatchOperation>): JsonElement {
        var current = canonicalize(before)
        operations.forEach { operation ->
            current = when (operation) {
                is ReplayPatchSet -> setAt(current, parse(operation.path), canonicalize(operation.value))
                is ReplayPatchRemove -> removeAt(current, parse(operation.path))
                is ReplayPatchSplice -> spliceAt(
                    current,
                    parse(operation.path),
                    operation.start,
                    operation.deleteCount,
                    operation.values.map(::canonicalize),
                )
            }
        }
        return canonicalize(current)
    }

    private fun diffAt(
        path: String,
        before: JsonElement,
        after: JsonElement,
        out: MutableList<ReplayPatchOperation>,
    ) {
        if (before == after) return
        when {
            before is JsonObject && after is JsonObject -> {
                (before.keys - after.keys).sorted().forEach { key ->
                    out += ReplayPatchRemove(child(path, key))
                }
                (after.keys - before.keys).sorted().forEach { key ->
                    out += ReplayPatchSet(child(path, key), after.getValue(key))
                }
                (before.keys intersect after.keys).sorted().forEach { key ->
                    diffAt(child(path, key), before.getValue(key), after.getValue(key), out)
                }
            }
            before is JsonArray && after is JsonArray -> {
                var prefix = 0
                while (prefix < before.size && prefix < after.size && before[prefix] == after[prefix]) prefix++
                var suffix = 0
                while (
                    suffix < before.size - prefix && suffix < after.size - prefix &&
                    before[before.lastIndex - suffix] == after[after.lastIndex - suffix]
                ) suffix++
                out += ReplayPatchSplice(
                    path = path,
                    start = prefix,
                    deleteCount = before.size - prefix - suffix,
                    values = after.subList(prefix, after.size - suffix),
                )
            }
            else -> out += ReplayPatchSet(path, after)
        }
    }

    private fun setAt(root: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val head = path.first()
        val tail = path.drop(1)
        return when (root) {
            is JsonObject -> JsonObject(root + (head to setAt(root[head] ?: JsonNull, tail, value)))
            is JsonArray -> {
                val index = head.toInt()
                JsonArray(root.mapIndexed { i, child -> if (i == index) setAt(child, tail, value) else child })
            }
            else -> error("Cannot set ${pointer(path)} below a JSON primitive")
        }
    }

    private fun removeAt(root: JsonElement, path: List<String>): JsonElement {
        require(path.isNotEmpty()) { "The replay patch cannot remove the document root" }
        val head = path.first()
        val tail = path.drop(1)
        return when (root) {
            is JsonObject -> if (tail.isEmpty()) JsonObject(root - head)
            else JsonObject(root + (head to removeAt(requireNotNull(root[head]), tail)))
            is JsonArray -> {
                val index = head.toInt()
                if (tail.isEmpty()) JsonArray(root.filterIndexed { i, _ -> i != index })
                else JsonArray(root.mapIndexed { i, child -> if (i == index) removeAt(child, tail) else child })
            }
            else -> error("Cannot remove ${pointer(path)} below a JSON primitive")
        }
    }

    private fun spliceAt(
        root: JsonElement,
        path: List<String>,
        start: Int,
        deleteCount: Int,
        values: List<JsonElement>,
    ): JsonElement {
        if (path.isEmpty()) {
            val array = root as? JsonArray ?: error("Replay splice target is not an array")
            require(start <= array.size && start + deleteCount <= array.size)
            return JsonArray(array.take(start) + values + array.drop(start + deleteCount))
        }
        val head = path.first()
        val tail = path.drop(1)
        return when (root) {
            is JsonObject -> JsonObject(root + (head to spliceAt(requireNotNull(root[head]), tail, start, deleteCount, values)))
            is JsonArray -> {
                val index = head.toInt()
                JsonArray(root.mapIndexed { i, child ->
                    if (i == index) spliceAt(child, tail, start, deleteCount, values) else child
                })
            }
            else -> error("Cannot splice ${pointer(path)} below a JSON primitive")
        }
    }

    private fun child(parent: String, key: String): String =
        "$parent/${key.replace("~", "~0").replace("/", "~1")}"

    private fun parse(pointer: String): List<String> {
        if (pointer.isEmpty()) return emptyList()
        require(pointer.startsWith('/')) { "Invalid replay JSON pointer $pointer" }
        return pointer.drop(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
    }

    private fun pointer(path: List<String>): String = path.joinToString(prefix = "/", separator = "/")
}

/** Builds a canonical replay while enforcing state continuity and record-chain integrity. */
class CanonicalReplayRecorder(
    gameId: String,
    createdAtUtc: String,
    engineVersion: String,
    producer: String,
    players: List<String>,
    initialState: GameState,
    initializationEvents: List<GameEvent> = emptyList(),
    extensions: JsonObject = JsonObject(emptyMap()),
) {
    private var currentState = ReplayCanonicalJson.state(initialState)
    private var currentStateDigest = ReplayCanonicalJson.digest(currentState)
    private var previousRecordDigest: String
    private var nextOrdinal = 0
    private var finished = false

    val header: CanonicalReplayHeader

    init {
        val unsigned = CanonicalReplayHeader(
            gameId = gameId,
            createdAtUtc = createdAtUtc,
            engineVersion = engineVersion,
            producer = producer,
            players = players,
            initialState = currentState,
            initialStateDigest = currentStateDigest,
            initializationEvents = initializationEvents,
            extensions = extensions,
            recordDigest = "",
        )
        header = unsigned.copy(recordDigest = ReplayRecordDigests.of(unsigned))
        previousRecordDigest = header.recordDigest
    }

    fun appendAction(
        origin: ReplayTransitionOrigin,
        action: GameAction,
        accepted: Boolean,
        resultingState: GameState,
        events: List<GameEvent> = emptyList(),
        rejectionReason: String? = null,
        actorId: String? = action.playerId.value,
        submitterId: String? = actorId,
        extensions: JsonObject = JsonObject(emptyMap()),
    ): CanonicalReplayTransition = append(
        origin = origin,
        actorId = actorId,
        submitterId = submitterId,
        action = action,
        systemMutation = null,
        accepted = accepted,
        rejectionReason = rejectionReason,
        events = events,
        resultingState = resultingState,
        extensions = extensions,
    )

    fun appendMutation(
        mutation: ReplaySystemMutation,
        resultingState: GameState,
        events: List<GameEvent> = emptyList(),
        accepted: Boolean = true,
        rejectionReason: String? = null,
        extensions: JsonObject = JsonObject(emptyMap()),
    ): CanonicalReplayTransition = append(
        origin = ReplayTransitionOrigin.SYSTEM,
        actorId = mutation.actorId,
        submitterId = mutation.actorId,
        action = null,
        systemMutation = mutation,
        accepted = accepted,
        rejectionReason = rejectionReason,
        events = events,
        resultingState = resultingState,
        extensions = extensions,
    )

    fun finish(
        status: ReplayCompletionStatus,
        finalState: GameState,
        winnerId: String? = null,
        incompleteReason: ReplayIncompleteReason? = null,
        extensions: JsonObject = JsonObject(emptyMap()),
    ): CanonicalReplayTerminal {
        check(!finished) { "Replay recorder is already finished" }
        val finalJson = ReplayCanonicalJson.state(finalState)
        require(ReplayCanonicalJson.digest(finalJson) == currentStateDigest) {
            "Terminal state does not match the last recorded replay state"
        }
        require(status != ReplayCompletionStatus.COMPLETE || finalState.gameOver) {
            "A complete replay must end in a terminal GameState"
        }
        val unsigned = CanonicalReplayTerminal(
            gameId = header.gameId,
            transitions = nextOrdinal,
            status = status,
            incompleteReason = incompleteReason,
            winnerId = winnerId,
            finalStateDigest = currentStateDigest,
            extensions = extensions,
            previousRecordDigest = previousRecordDigest,
            recordDigest = "",
        )
        finished = true
        return unsigned.copy(recordDigest = ReplayRecordDigests.of(unsigned))
    }

    private fun append(
        origin: ReplayTransitionOrigin,
        actorId: String?,
        submitterId: String?,
        action: GameAction?,
        systemMutation: ReplaySystemMutation?,
        accepted: Boolean,
        rejectionReason: String?,
        events: List<GameEvent>,
        resultingState: GameState,
        extensions: JsonObject,
    ): CanonicalReplayTransition {
        check(!finished) { "A finished replay cannot be extended" }
        val nextState = ReplayCanonicalJson.state(resultingState)
        if (!accepted) {
            require(nextState == currentState) { "A rejected replay input changed authoritative state" }
            require(events.isEmpty()) { "A rejected replay input emitted authoritative events" }
        }
        val patch = ReplayPatchedState(ReplayCanonicalJson.diff(currentState, nextState))
        val full = ReplayFullState(nextState)
        val mandatoryFull = nextOrdinal > 0 && nextOrdinal % CANONICAL_REPLAY_FULL_STATE_INTERVAL == 0
        val encoding = if (!mandatoryFull && encodedSize(patch) < encodedSize(full)) patch else full
        val stateDigest = ReplayCanonicalJson.digest(nextState)
        val unsigned = CanonicalReplayTransition(
            gameId = header.gameId,
            ordinal = nextOrdinal,
            origin = origin,
            actorId = actorId,
            submitterId = submitterId,
            action = action,
            systemMutation = systemMutation,
            accepted = accepted,
            rejectionReason = rejectionReason,
            events = events,
            state = encoding,
            resultingStateDigest = stateDigest,
            extensions = extensions,
            previousRecordDigest = previousRecordDigest,
            recordDigest = "",
        )
        val record = unsigned.copy(recordDigest = ReplayRecordDigests.of(unsigned))
        currentState = nextState
        currentStateDigest = stateDigest
        previousRecordDigest = record.recordDigest
        nextOrdinal++
        return record
    }

    private fun encodedSize(value: ReplayStateEncoding): Int = CanonicalReplayJson.encodeToString(
        ReplayStateEncoding.serializer(),
        value,
    ).toByteArray(Charsets.UTF_8).size

    companion object {
        /** Resume an append-only in-progress stream after persistence/restart validation. */
        fun resume(records: List<CanonicalReplayRecord>): CanonicalReplayRecorder {
            require(records.isNotEmpty())
            val header = records.first() as? CanonicalReplayHeader ?: error("Replay prefix has no header")
            require(records.none { it is CanonicalReplayTerminal }) { "A terminal replay cannot resume" }
            val initial = CanonicalReplayJson.decodeFromJsonElement(GameState.serializer(), header.initialState)
            val recorder = CanonicalReplayRecorder(
                gameId = header.gameId,
                createdAtUtc = header.createdAtUtc,
                engineVersion = header.engineVersion,
                producer = header.producer,
                players = header.players,
                initialState = initial,
                initializationEvents = header.initializationEvents,
                extensions = header.extensions,
            )
            require(recorder.header == header) { "Replay prefix header failed canonical regeneration" }
            var stateJson = ReplayCanonicalJson.canonicalize(header.initialState)
            records.drop(1).forEachIndexed { index, raw ->
                val expected = raw as? CanonicalReplayTransition
                    ?: error("Replay prefix record ${index + 1} is not a transition")
                require(expected.ordinal == index)
                stateJson = when (val encoding = expected.state) {
                    is ReplayFullState -> ReplayCanonicalJson.canonicalize(encoding.value)
                    is ReplayPatchedState -> ReplayCanonicalJson.apply(stateJson, encoding.operations)
                }
                require(ReplayCanonicalJson.digest(stateJson) == expected.resultingStateDigest)
                val state = CanonicalReplayJson.decodeFromJsonElement(GameState.serializer(), stateJson)
                val generated = expected.action?.let { action ->
                    recorder.appendAction(
                        origin = expected.origin,
                        action = action,
                        accepted = expected.accepted,
                        resultingState = state,
                        events = expected.events,
                        rejectionReason = expected.rejectionReason,
                        actorId = expected.actorId,
                        submitterId = expected.submitterId,
                        extensions = expected.extensions,
                    )
                } ?: recorder.appendMutation(
                    mutation = requireNotNull(expected.systemMutation),
                    resultingState = state,
                    events = expected.events,
                    accepted = expected.accepted,
                    rejectionReason = expected.rejectionReason,
                    extensions = expected.extensions,
                )
                require(generated == expected) { "Replay prefix transition $index failed canonical regeneration" }
            }
            return recorder
        }
    }
}

data class ReconstructedCanonicalReplay(
    val header: CanonicalReplayHeader,
    val transitions: List<CanonicalReplayTransition>,
    val terminal: CanonicalReplayTerminal,
    val states: List<JsonElement>,
) {
    fun stateAt(frame: Int): GameState {
        require(frame in states.indices)
        return CanonicalReplayJson.decodeFromJsonElement(GameState.serializer(), states[frame])
    }
}

data class ReconstructedCanonicalReplayPrefix(
    val header: CanonicalReplayHeader,
    val transitions: List<CanonicalReplayTransition>,
    val states: List<JsonElement>,
) {
    fun stateAt(frame: Int): GameState {
        require(frame in states.indices)
        return CanonicalReplayJson.decodeFromJsonElement(GameState.serializer(), states[frame])
    }
}

object CanonicalReplayReconstructor {
    fun reconstruct(records: List<CanonicalReplayRecord>): ReconstructedCanonicalReplay {
        require(records.size >= 2) { "A replay needs a header and terminal" }
        val terminal = records.last() as? CanonicalReplayTerminal ?: error("Replay does not end with a terminal")
        val prefix = reconstructPrefix(records.dropLast(1))
        val header = prefix.header
        val transitions = prefix.transitions
        val current = prefix.states.last()
        val previousRecordDigest = transitions.lastOrNull()?.recordDigest ?: header.recordDigest
        require(terminal.transitions == transitions.size)
        require(terminal.gameId == header.gameId)
        require(terminal.previousRecordDigest == previousRecordDigest)
        require(ReplayRecordDigests.of(terminal.copy(recordDigest = "")) == terminal.recordDigest)
        require(terminal.finalStateDigest == ReplayCanonicalJson.digest(current))
        val finalState = CanonicalReplayJson.decodeFromJsonElement(GameState.serializer(), current)
        require(terminal.status != ReplayCompletionStatus.COMPLETE || finalState.gameOver)
        return ReconstructedCanonicalReplay(header, transitions, terminal, prefix.states)
    }

    /** Validate and reconstruct an appendable header+transition prefix with no terminal marker. */
    fun reconstructPrefix(records: List<CanonicalReplayRecord>): ReconstructedCanonicalReplayPrefix {
        require(records.isNotEmpty()) { "A replay prefix needs a header" }
        val header = records.first() as? CanonicalReplayHeader ?: error("Replay does not start with a header")
        val transitions = records.drop(1).mapIndexed { index, record ->
            (record as? CanonicalReplayTransition)
                ?.also { require(it.ordinal == index) { "Replay transition ${it.ordinal}; expected $index" } }
                ?: error("Replay record $index is not a transition")
        }
        require(ReplayRecordDigests.of(header.copy(recordDigest = "")) == header.recordDigest) {
            "Replay header digest mismatch"
        }
        var previousRecordDigest = header.recordDigest
        var current = ReplayCanonicalJson.canonicalize(header.initialState)
        require(ReplayCanonicalJson.digest(current) == header.initialStateDigest)
        val states = mutableListOf(current)
        transitions.forEach { transition ->
            require(transition.gameId == header.gameId) {
                "Replay game id mismatch at transition ${transition.ordinal}"
            }
            require(transition.previousRecordDigest == previousRecordDigest) {
                "Replay chain mismatch at transition ${transition.ordinal}"
            }
            require(ReplayRecordDigests.of(transition.copy(recordDigest = "")) == transition.recordDigest) {
                "Replay record digest mismatch at transition ${transition.ordinal}"
            }
            current = when (val encoded = transition.state) {
                is ReplayFullState -> ReplayCanonicalJson.canonicalize(encoded.value)
                is ReplayPatchedState -> ReplayCanonicalJson.apply(current, encoded.operations)
            }
            require(ReplayCanonicalJson.digest(current) == transition.resultingStateDigest) {
                "Replay state digest mismatch after transition ${transition.ordinal}"
            }
            states += current
            previousRecordDigest = transition.recordDigest
        }
        return ReconstructedCanonicalReplayPrefix(header, transitions, states)
    }
}

object ReplayRecordDigests {
    fun of(record: CanonicalReplayRecord): String {
        require(record.recordDigest.isEmpty()) { "Record digest must be blank while computing its digest" }
        val tree = CanonicalReplayJson.encodeToJsonElement(CanonicalReplayRecord.serializer(), record)
        return ReplayCanonicalJson.digest(tree)
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
