package org.mtgallium.agent.neural

import ai.onnxruntime.*
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** Only dimensions used by this runtime; the graph owns its internal network architecture. */
@Serializable
data class NeuralModelConfig(
    val architecture: NeuralArchitecture,
    val hiddenSize: Int = 24,
    val contextEvents: Int = 16,
) {
    init { require(hiddenSize > 0 && contextEvents > 0) }
}

/** Runtime descriptor: tensor limits, memory layout, and graph locations. */
@Serializable
data class NeuralModelDescriptor(
    val schema: FactualTensorSchema = FactualTensorSchema(),
    val config: NeuralModelConfig,
    val maximumBatch: Int = 1,
    val graphs: Map<String, String>,
) {
    init {
        require(maximumBatch > 0)
        val roles = setOf("scoreSingle") + (if (maximumBatch > 1) setOf("scoreBatch") else emptySet()) +
            (if (config.architecture != NeuralArchitecture.CURRENT_VIEW)
                setOf("updateSingle") + (if (maximumBatch > 1) setOf("updateBatch") else emptySet()) else emptySet())
        require(graphs.keys.containsAll(roles)) { "Missing score/update graph for the requested batch and memory layout" }
    }
}

open class NeuralInferenceException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Cumulative work at this loaded runtime, never part of player inputs or model identity. */
@Serializable
data class NeuralRuntimeProfile(val calls: Long = 0, val lanes: Long = 0, val packingNanos: Long = 0,
    val inferenceNanos: Long = 0, val outputNanos: Long = 0, val releaseNanos: Long = 0)

/**
 * CPU graph sessions with shared weights and caller-owned player memory.
 * The caller supplies graph bytes; this loader checks the tensor interface.
 */
class OnnxSequenceModel private constructor(
    override val identity: String,
    private val descriptor: NeuralModelDescriptor,
    private val environment: OrtEnvironment,
    private val sessions: Map<String, OrtSession>,
) : SequencePolicyModel {
    override val schema = descriptor.schema
    override val architecture = descriptor.config.architecture
    override val maximumBatchSize = descriptor.maximumBatch
    val config: NeuralModelConfig get() = descriptor.config
    private var closed = false
    private var profile = NeuralRuntimeProfile()
    @Synchronized
    fun runtimeProfile(): NeuralRuntimeProfile = profile

    override fun initialMemory(): NeuralMemoryTensors = NeuralMemoryTensors(
        List(when (architecture) {
            NeuralArchitecture.CURRENT_VIEW -> 1
            NeuralArchitecture.GRU -> config.hiddenSize
            NeuralArchitecture.BOUNDED_ATTENTION -> config.contextEvents * config.hiddenSize
        }) { 0f }, if (architecture == NeuralArchitecture.BOUNDED_ATTENTION) List(config.contextEvents) { false } else emptyList()
    ).detached()

    override fun validateMemory(memory: NeuralMemoryTensors) {
        val expected = when (architecture) {
            NeuralArchitecture.CURRENT_VIEW -> 1
            NeuralArchitecture.GRU -> config.hiddenSize
            NeuralArchitecture.BOUNDED_ATTENTION -> config.contextEvents * config.hiddenSize
        }
        require(memory.values.size == expected && memory.values.all(Float::isFinite)) { "Neural memory tensor shape/value mismatch" }
        require(memory.historyMask.size == if (architecture == NeuralArchitecture.BOUNDED_ATTENTION) config.contextEvents else 0)
        if (architecture == NeuralArchitecture.CURRENT_VIEW) require(memory.values.single() == 0f)
    }

    @Synchronized
    override fun advance(event: List<Int>, memory: NeuralMemoryTensors): NeuralMemoryTensors =
        advanceBatch(listOf(event), listOf(memory)).single()

    /** Null events are inactive batch lanes, not real empty observations. */
    @Synchronized
    override fun advanceBatch(events: List<List<Int>?>, memories: List<NeuralMemoryTensors>): List<NeuralMemoryTensors> {
        checkOpen()
        require(events.size in 1..descriptor.maximumBatch && events.size == memories.size)
        memories.forEach(::validateMemory)
        events.filterNotNull().forEach { require(it.size in 1..schema.maximumEventBytes && it.all { token -> token in 1..256 }) }
        if (architecture == NeuralArchitecture.CURRENT_VIEW) return memories.map { it.detached() }
        val packingStarted = System.nanoTime()
        val width = maxOf(4, events.maxOf { it?.size ?: 0 })
        val values = linkedMapOf<String, Any>(
            "event_tokens" to Array(events.size) { i -> LongArray(width) { j -> events[i]?.getOrNull(j)?.toLong() ?: 0L } },
            "event_mask" to Array(events.size) { i -> BooleanArray(width) { j -> j < (events[i]?.size ?: 0) } },
            "event_valid" to BooleanArray(events.size) { events[it] != null },
        )
        addMemory(values, memories)
        return invoke(role("update", events.size), values, events.size, packingStarted) { result ->
            val state = result.get("next_memory").orElseThrow().value as Array<*>
            require(state.size == events.size)
            val masks = if (architecture == NeuralArchitecture.BOUNDED_ATTENTION) result.get("next_history_mask").orElseThrow().value as Array<*> else null
            events.indices.map { i ->
                NeuralMemoryTensors(flattenFloats(requireNotNull(state[i])),
                    if (masks == null) emptyList() else (masks[i] as BooleanArray).toList()).detached().also {
                    validateMemory(it)
                    if (events[i] == null) require(it == memories[i]) { "Inactive event lane changed memory" }
                }
            }
        }
    }

    @Synchronized
    override fun score(input: FactualDecisionTensors, memory: NeuralMemoryTensors): List<Float> =
        scoreBatch(listOf(input), listOf(memory)).single()

    @Synchronized
    override fun scoreBatch(inputs: List<FactualDecisionTensors>, memories: List<NeuralMemoryTensors>): List<List<Float>> {
        checkOpen()
        require(inputs.size in 1..descriptor.maximumBatch && inputs.size == memories.size)
        inputs.forEach { it.validate(schema) }
        memories.forEach(::validateMemory)
        val packingStarted = System.nanoTime()
        val b = inputs.size
        val viewWidth = maxOf(4, inputs.maxOf { it.view.size })
        val actions = maxOf(2, inputs.maxOf { it.actions.size })
        val actionWidth = maxOf(4, inputs.maxOf { frame -> frame.actions.maxOf { it.size } })
        val values = linkedMapOf<String, Any>(
            "view_tokens" to Array(b) { i -> LongArray(viewWidth) { j -> inputs[i].view.getOrNull(j)?.toLong() ?: 0L } },
            "view_mask" to Array(b) { i -> BooleanArray(viewWidth) { j -> j < inputs[i].view.size } },
            "action_tokens" to Array(b) { i -> Array(actions) { a -> LongArray(actionWidth) { j -> inputs[i].actions.getOrNull(a)?.getOrNull(j)?.toLong() ?: 0L } } },
            "action_token_mask" to Array(b) { i -> Array(actions) { a -> BooleanArray(actionWidth) { j -> j < (inputs[i].actions.getOrNull(a)?.size ?: 0) } } },
            "candidate_mask" to Array(b) { i -> BooleanArray(actions) { a -> a < inputs[i].actions.size } },
            "menu" to Array(b) { i -> floatArrayOf(if (inputs[i].rulesExhaustive) 1f else 0f, if (inputs[i].profileExhaustive) 1f else 0f) },
        )
        if (architecture != NeuralArchitecture.CURRENT_VIEW) addMemory(values, memories)
        return invoke(role("score", b), values, b, packingStarted) { result ->
            val scores = result.get("scores").orElseThrow().value as Array<*>
            require(scores.size == b)
            inputs.indices.map { i ->
                val row = scores[i] as FloatArray
                require(row.size == actions) { "Malformed neural score tensor" }
                row.take(inputs[i].actions.size).also { valid ->
                    require(valid.all(Float::isFinite)) { "Non-finite neural action score" }
                }
            }
        }
    }

    private fun addMemory(values: MutableMap<String, Any>, memories: List<NeuralMemoryTensors>) {
        values["memory"] = if (architecture == NeuralArchitecture.GRU) Array(memories.size) { memories[it].values.toFloatArray() }
            else Array(memories.size) { i -> Array(config.contextEvents) { t -> FloatArray(config.hiddenSize) { j ->
                memories[i].values[t * config.hiddenSize + j]
            } } }
        if (architecture == NeuralArchitecture.BOUNDED_ATTENTION)
            values["history_mask"] = Array(memories.size) { memories[it].historyMask.toBooleanArray() }
    }

    private fun role(prefix: String, batch: Int) = prefix + if (batch == 1) "Single" else "Batch"
    private fun checkOpen() { check(!closed) { "Frozen neural model is closed" } }
    private fun <T> invoke(role: String, values: Map<String, Any>, lanes: Int, packingStarted: Long,
        read: (OrtSession.Result) -> T): T {
        val tensors = linkedMapOf<String, OnnxTensor>()
        var result: OrtSession.Result? = null
        var packing = 0L
        var inference = 0L
        var output = 0L
        try {
            for ((name, value) in values) tensors[name] = OnnxTensor.createTensor(environment, value)
            packing = System.nanoTime() - packingStarted
            val runStarted = System.nanoTime()
            try { result = sessions.getValue(role).run(tensors) } finally { inference = System.nanoTime() - runStarted }
            val outputStarted = System.nanoTime()
            try { return read(requireNotNull(result)) } finally { output = System.nanoTime() - outputStarted }
        } catch (failure: OrtException) {
            throw NeuralInferenceException("ONNX $role failed; no policy fallback was executed", failure)
        } finally {
            if (packing == 0L) packing = System.nanoTime() - packingStarted
            val releaseStarted = System.nanoTime()
            try { result?.close() } finally {
                try { tensors.values.forEach { it.close() } } finally {
                    profile = NeuralRuntimeProfile(profile.calls + 1, profile.lanes + lanes, profile.packingNanos + packing,
                        profile.inferenceNanos + inference, profile.outputNanos + output,
                        profile.releaseNanos + System.nanoTime() - releaseStarted)
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            sessions.values.distinct().forEach { it.close() }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        /** Graph names are caller-defined. The same graph may serve both single and batch roles. */
        fun load(descriptorBytes: ByteArray, graphBytes: (String) -> ByteArray): OnnxSequenceModel {
            val descriptor = json.decodeFromString<NeuralModelDescriptor>(descriptorBytes.decodeToString())
            val environment = OrtEnvironment.getEnvironment()
            val sessions = linkedMapOf<String, OrtSession>()
            val loaded = linkedMapOf<String, OrtSession>()
            val graphDigests = linkedMapOf<String, String>()
            try {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
                    options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    options.addConfigEntry("session.intra_op.allow_spinning", "0")
                    options.addConfigEntry("session.inter_op.allow_spinning", "0")
                    for ((role, name) in descriptor.graphs) {
                        val session = loaded.getOrPut(name) {
                            val bytes = graphBytes(name)
                            graphDigests[name] = hash(bytes)
                            environment.createSession(bytes, options)
                        }
                        validateInterface(session, role, descriptor.config.architecture)
                        sessions[role] = session
                    }
                }
                val identity = hash((json.encodeToString(descriptor) + json.encodeToString(graphDigests)).toByteArray())
                return OnnxSequenceModel("neural-model-sha256:$identity", descriptor, environment, sessions)
            } catch (failure: Throwable) {
                loaded.values.forEach { it.close() }
                throw failure
            }
        }

        private fun validateInterface(session: OrtSession, role: String, architecture: NeuralArchitecture) {
            val names = if (role.startsWith("score")) mutableSetOf("view_tokens", "view_mask", "action_tokens", "action_token_mask", "candidate_mask", "menu")
                else mutableSetOf("event_tokens", "event_mask", "event_valid", "memory")
            if (role.startsWith("score") && architecture != NeuralArchitecture.CURRENT_VIEW) names += "memory"
            if (architecture == NeuralArchitecture.BOUNDED_ATTENTION) names += "history_mask"
            require(session.inputNames == names) { "ONNX input ABI differs" }
            for ((name, node) in session.inputInfo) {
                val info = node.info as? TensorInfo ?: error("ONNX policy input is not a tensor")
                val type = if (name.endsWith("tokens")) OnnxJavaType.INT64
                    else if (name.endsWith("mask") || name == "event_valid") OnnxJavaType.BOOL else OnnxJavaType.FLOAT
                require(info.type == type) { "ONNX policy input dtype differs: $name" }
                val rank = when (name) {
                    "event_valid" -> 1
                    "action_tokens", "action_token_mask" -> 3
                    "memory" -> if (architecture == NeuralArchitecture.BOUNDED_ATTENTION) 3 else 2
                    else -> 2
                }
                require(info.shape.size == rank) { "ONNX policy input rank differs: $name" }
            }
            val outputs = if (role.startsWith("score")) setOf("scores") else if (architecture == NeuralArchitecture.GRU)
                setOf("next_memory") else setOf("next_memory", "next_history_mask")
            require(session.outputNames == outputs)
        }

        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun flattenFloats(value: Any): List<Float> = when (value) {
            is FloatArray -> value.toList()
            is Array<*> -> value.flatMap { flattenFloats(requireNotNull(it)) }
            else -> throw NeuralInferenceException("Unexpected memory tensor element type")
        }
    }
}
