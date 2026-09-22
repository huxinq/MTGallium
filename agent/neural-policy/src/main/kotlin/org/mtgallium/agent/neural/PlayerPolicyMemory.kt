package org.mtgallium.agent.neural

import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.*

@Serializable
enum class NeuralArchitecture { CURRENT_VIEW, GRU, BOUNDED_ATTENTION }

/** Numeric model state, never an authority for exact knowledge. */
@Serializable
data class NeuralMemoryTensors(val values: List<Float>, val historyMask: List<Boolean> = emptyList()) {
    init { require(values.all(Float::isFinite)) }
    fun detached(): NeuralMemoryTensors = NeuralMemoryTensors(frozen(values), frozen(historyMask))
}

/** Shared frozen weights. Implementations do not keep player or branch memory. */
interface SequencePolicyModel : AutoCloseable {
    val identity: String
    val schema: FactualTensorSchema
    val architecture: NeuralArchitecture
    fun initialMemory(): NeuralMemoryTensors
    fun validateMemory(memory: NeuralMemoryTensors)
    fun advance(event: List<Int>, memory: NeuralMemoryTensors): NeuralMemoryTensors
    fun score(input: FactualDecisionTensors, memory: NeuralMemoryTensors): List<Float>
    val maximumBatchSize: Int get() = 1
    fun advanceBatch(events: List<List<Int>?>, memories: List<NeuralMemoryTensors>): List<NeuralMemoryTensors> {
        require(events.size in 1..maximumBatchSize && events.size == memories.size)
        return events.indices.map { i -> events[i]?.let { advance(it, memories[i].detached()) } ?: memories[i].detached() }
    }
    fun scoreBatch(inputs: List<FactualDecisionTensors>, memories: List<NeuralMemoryTensors>): List<List<Float>> {
        require(inputs.size in 1..maximumBatchSize && inputs.size == memories.size)
        return inputs.indices.map { score(inputs[it], memories[it].detached()) }
    }
}

@Serializable
data class PolicyMemoryOwner(val game: String, val player: String, val branch: String = "live") {
    init { require(game.isNotBlank() && player.isNotBlank() && branch.isNotBlank()) }
}

/** Restoration additionally requires authenticated artifact bytes when this crosses a storage boundary. */
@Serializable
data class PolicyMemoryCheckpoint(
    val version: Int = 1,
    val owner: PolicyMemoryOwner,
    val modelIdentity: String,
    val inputSchemaIdentity: String,
    val history: PolicyHistoryCommitment,
    val memory: NeuralMemoryTensors,
) { init { require(version == 1 && modelIdentity.isNotBlank() && inputSchemaIdentity.isNotBlank()) } }

@Serializable
data class PolicyObservationReceipt(val fromCursor: Int, val toCursor: Int, val newlyDeliveredEvents: Int)
data class NeuralDecisionScores(val values: List<Float>, val selectedIndex: Int, val history: PolicyHistoryCommitment)

/**
 * One game/player/branch owner. Observing is transactional; scoring is read-only. The host calls
 * observe after accepted transitions, including forced ones, and before the first score. Replaying
 * a complete safe prefix is a convenience adapter, not permission to treat proposed actions as events.
 */
class PlayerPolicySession(
    private val model: SequencePolicyModel,
    val owner: PolicyMemoryOwner,
) : AutoCloseable {
    private val identity = model.identity
    private val encoder = FactualPolicyEncoder(model.schema)
    private var checkpoint: PolicyMemoryCheckpoint? = initial()

    private fun initial() = PolicyMemoryCheckpoint(owner = owner, modelIdentity = identity,
        inputSchemaIdentity = encoder.schema.identity, history = PolicyHistoryCommitment.empty(),
        memory = model.initialMemory().detached()).also { model.validateMemory(it.memory) }

    private fun current(): PolicyMemoryCheckpoint {
        require(model.identity == identity && model.schema.identity == encoder.schema.identity) { "Shared model binding changed" }
        return checkNotNull(checkpoint) { "Policy session is closed" }
    }

    private val lockOrder = nextLockOrder.getAndIncrement()
    private val lock = ReentrantLock()

    fun observe(state: EpistemicState): PolicyObservationReceipt = observeBatch(listOf(this), listOf(state)).single()

    private data class PendingObservation(val before: PolicyMemoryCheckpoint, val target: PolicyHistoryCommitment,
        val events: List<List<Int>>)

    private fun prepareObservation(state: EpistemicState): PendingObservation {
        val before = current()
        require(state.perspectivePlayerId == owner.player) { "Cannot consume another player's information" }
        require(state.history.size >= before.history.cursor) { "Policy history rewound without reset" }
        if (state.historyCommitment == before.history) return PendingObservation(before, before.history, emptyList())
        // Prefix proof and reference continuity stay outside tensors. Only the new suffix is
        // processed by the neural update; batching does not reset or concatenate player histories.
        require(PolicyHistoryCommitment.replay(state.history.take(before.history.cursor)) == before.history) {
            "Policy history does not extend the consumed prefix"
        }
        val players = state.observation.players.map { it.playerId }
        require(owner.player in players)
        return PendingObservation(before, state.historyCommitment,
            encoder.events(state.history, owner.player, players, before.history.cursor))
    }

    fun score(site: DecisionSite, referenceGroups: Map<String, List<String>> = emptyMap()): NeuralDecisionScores = lock.withLock {
        val current = current()
        require(site.actor == owner.player && site.epistemic.perspectivePlayerId == owner.player)
        require(site.epistemic.historyCommitment == current.history) { "Observe the complete accepted prefix before scoring" }
        val input = encoder.decision(site, referenceGroups)
        val scores = model.score(input, current.memory.detached())
        require(scores.size == input.actions.size && scores.all(Float::isFinite)) { "Invalid neural score output" }
        val selected = scores.indices.maxBy { scores[it] }
        NeuralDecisionScores(frozen(scores), selected, current.history)
    }

    fun snapshot(): PolicyMemoryCheckpoint = lock.withLock { current().let { it.copy(memory = it.memory.detached()) } }

    fun restore(saved: PolicyMemoryCheckpoint, state: EpistemicState) = lock.withLock {
        current()
        require(saved.owner == owner && state.perspectivePlayerId == owner.player) { "Memory owner differs" }
        require(saved.modelIdentity == identity && saved.inputSchemaIdentity == encoder.schema.identity) { "Memory/model/schema mismatch" }
        require(saved.history == state.historyCommitment) { "Memory event position differs from the supplied state" }
        val captured = saved.copy(memory = saved.memory.detached())
        model.validateMemory(captured.memory)
        checkpoint = captured
    }

    fun fork(branch: String): PlayerPolicySession = lock.withLock {
        require(branch.isNotBlank() && branch != owner.branch)
        val saved = snapshot()
        PlayerPolicySession(model, owner.copy(branch = branch)).also { child ->
            child.checkpoint = saved.copy(owner = child.owner, memory = saved.memory.detached())
        }
    }

    fun reset() = lock.withLock { current(); checkpoint = initial() }

    /** Closing one player never closes the shared model. */
    override fun close() = lock.withLock { checkpoint = null }

    companion object {
        private val nextLockOrder = AtomicLong()

        /** Commit independent accepted prefixes only after every numerical update succeeds. */
        fun observeBatch(sessions: List<PlayerPolicySession>, states: List<EpistemicState>): List<PolicyObservationReceipt> =
            observeCaptured(sessions.toList(), states.toList())

        private fun observeCaptured(sessions: List<PlayerPolicySession>, states: List<EpistemicState>): List<PolicyObservationReceipt> {
            require(sessions.isNotEmpty() && sessions.size == states.size && sessions.distinct().size == sessions.size)
            val ordered = sessions.sortedBy { it.lockOrder }
            ordered.forEach { it.lock.lock() }
            return try {
                val model = sessions.first().model
                require(sessions.all { it.model === model }) {
                    "An observation batch requires one shared frozen model instance"
                }
                val pending = sessions.indices.map { sessions[it].prepareObservation(states[it]) }
                val memories = pending.map { it.before.memory.detached() }.toMutableList()
                for (offset in 0 until pending.maxOf { it.events.size }) {
                    val active = pending.indices.filter { offset < pending[it].events.size }
                    for (lanes in active.chunked(model.maximumBatchSize)) {
                        val advanced = model.advanceBatch(lanes.map { pending[it].events[offset] }, lanes.map { memories[it].detached() })
                        require(advanced.size == lanes.size) { "Neural update lost a batch lane" }
                        for ((position, lane) in lanes.withIndex()) {
                            val memory = advanced[position].detached()
                            model.validateMemory(memory)
                            memories[lane] = memory
                        }
                    }
                }
                val saved = pending.indices.map { pending[it].before.copy(history = pending[it].target, memory = memories[it]) }
                sessions.indices.forEach { sessions[it].checkpoint = saved[it] }
                pending.map { PolicyObservationReceipt(it.before.history.cursor, it.target.cursor, it.events.size) }
            } finally {
                ordered.asReversed().forEach { it.lock.unlock() }
            }
        }
    }
}

private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
