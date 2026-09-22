package org.mtgallium.agent.argentum.policy

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.neural.*

/** An inspectable choice, not an accepted transition or a learned likelihood distribution. */
data class NeuralHostDecision(
    val site: DecisionSite,
    val input: FactualDecisionTensors,
    val scores: List<Float>?,
    val selectedIndex: Int,
    val memoryCursor: Int,
    val rulesForced: Boolean,
    val projectionNanos: Long,
    /** Null when measured collectively by the batch, rather than attributed to an individual lane. */
    val scoringNanos: Long?,
)

data class NeuralHostTransition(
    val decision: NeuralHostDecision,
    val result: SearchStepResult,
    val delivery: Map<String, PolicyObservationReceipt>,
    val engineNanos: Long,
    /** Null for collective batch delivery. Zero means no delivery followed a native rejection. */
    val deliveryNanos: Long?,
)

enum class NeuralBatchFailureStage { PREPARATION, SCORING, SUBMISSION, DELIVERY }
class NeuralHostExecutionException(val stage: NeuralBatchFailureStage, val accepted: Boolean?, message: String,
    cause: Throwable) : NeuralInferenceException(message, cause)
sealed interface NeuralHostLaneResult {
    data class Transition(val value: NeuralHostTransition) : NeuralHostLaneResult
    /** Failure has no payoff; null acceptance means submission may have advanced before throwing. */
    data class Failure(val stage: NeuralBatchFailureStage, val accepted: Boolean?, val detail: String) : NeuralHostLaneResult
}
data class NeuralHostBatchResult(val lanes: List<NeuralHostLaneResult>, val projectionNanos: Long,
    val scoringNanos: Long, val engineNanos: Long, val deliveryNanos: Long, val elapsedNanos: Long)

/**
 * Caller transfers exclusive ownership of the world. Shared weights never own game/player memory.
 * Scalar and batched execution share preparation, submission and accepted-observation semantics.
 */
class ArgentumNeuralPolicyHost private constructor(
    private val world: ArgentumSearchWorld,
    private val sessions: Map<String, PlayerPolicySession>,
    private val model: SequencePolicyModel,
    private val view: DecisionView,
) : AutoCloseable {
    private val lockOrder = nextLockOrder.getAndIncrement()
    private val lock = ReentrantLock()
    private var failed = false
    private var closed = false
    private val encoder = FactualPolicyEncoder(model.schema)

    constructor(world: ArgentumSearchWorld, game: String, players: List<String>, model: SequencePolicyModel,
        view: DecisionView = DecisionView(limit = 32)) : this(world,
        players.associateWith { PlayerPolicySession(model, PolicyMemoryOwner(game, it)) }, model, view) {
        require(players.isNotEmpty() && players.distinct().size == players.size)
        try { deliver() } catch (failure: Throwable) { close(); throw failure }
    }

    private data class Prepared(val site: DecisionSite, val input: FactualDecisionTensors,
        val memory: PolicyMemoryCheckpoint, val forced: Boolean, val nanos: Long) {
        fun scored(scores: List<Float>?, duration: Long?): NeuralHostDecision {
            require((scores == null) == forced)
            if (scores != null) require(scores.size == input.actions.size && scores.all(Float::isFinite)) {
                "Invalid neural batch score output"
            }
            return NeuralHostDecision(site, input, scores?.toList(), scores?.indices?.maxBy { scores[it] } ?: 0,
                memory.history.cursor, forced, nanos, duration)
        }
    }

    private fun prepare(): Prepared {
        ready()
        val start = System.nanoTime()
        val projection = world.policyDecisionProjection(view)
        val site = projection.site
        val memory = sessions.getValue(site.actor).snapshot()
        check(memory.history == site.epistemic.historyCommitment) { "Host missed an accepted event delivery" }
        val input = encoder.decision(site, projection.semanticReferenceGroups)
        return Prepared(site, input, memory, site.expansion.isExhaustive && site.expansion.candidates.size == 1,
            System.nanoTime() - start)
    }

    fun inspect(): NeuralHostDecision = lock.withLock {
        val prepared = try { prepare() } catch (error: Exception) {
            throw NeuralHostExecutionException(NeuralBatchFailureStage.PREPARATION, false, "Decision preparation failed before submission", error)
        }
        val start = System.nanoTime()
        try {
            val scores = if (prepared.forced) null else model.score(prepared.input, prepared.memory.memory)
            return prepared.scored(scores, System.nanoTime() - start)
        } catch (error: Exception) {
            throw NeuralHostExecutionException(NeuralBatchFailureStage.SCORING, false, "Decision scoring failed before submission", error)
        }
    }

    private data class Submitted(val decision: NeuralHostDecision, val result: SearchStepResult, val nanos: Long)
    private fun submit(decision: NeuralHostDecision): Submitted {
        ready()
        val start = System.nanoTime()
        try {
            return Submitted(decision, world.step(decision.site.expansion.candidates[decision.selectedIndex]), System.nanoTime() - start)
        } catch (failure: Throwable) {
            failed = true
            throw NeuralHostExecutionException(NeuralBatchFailureStage.SUBMISSION, null,
                "Native submission or transition projection failed; host cannot continue", failure)
        }
    }

    fun step(): NeuralHostTransition = lock.withLock {
        val submitted = submit(inspect())
        if (!submitted.result.accepted) return NeuralHostTransition(submitted.decision, submitted.result, emptyMap(), submitted.nanos, 0)
        val start = System.nanoTime()
        try {
            val receipts = deliver()
            return NeuralHostTransition(submitted.decision, submitted.result, receipts, submitted.nanos, System.nanoTime() - start)
        } catch (failure: Throwable) {
            failed = true
            throw NeuralHostExecutionException(NeuralBatchFailureStage.DELIVERY, true,
                "Accepted game transition could not be delivered to policy memory", failure)
        }
    }

    fun memory(player: String): PolicyMemoryCheckpoint = lock.withLock { ready(); sessions.getValue(player).snapshot() }

    fun terminalPayoff(player: String): Double? = lock.withLock { ready(); world.terminalPayoff(player) }

    fun fork(branch: String): ArgentumNeuralPolicyHost = lock.withLock {
        ready()
        return ArgentumNeuralPolicyHost(world.fork() as ArgentumSearchWorld,
            sessions.mapValues { it.value.fork(branch) }, model, view)
    }

    private fun deliver() = sessions.mapValues { (player, session) -> session.observe(world.epistemicState(player)) }
    private fun ready() { check(!closed && !failed) { "Neural game host is closed or failed" } }

    override fun close() = lock.withLock { if (!closed) { closed = true; sessions.values.forEach { it.close() } } }

    companion object {
        private val nextLockOrder = AtomicLong()

        /**
         * One real decision per supplied, nonterminal host. All scores validate before any submission.
         * Engine transitions remain independent and ordered by input lane. Accepted observations are
         * then batched across player sessions, never concatenated into a shared recurrent history.
         */
        fun stepBatch(hosts: List<ArgentumNeuralPolicyHost>): NeuralHostBatchResult {
            val captured = hosts.toList()
            require(captured.isNotEmpty() && captured.distinct().size == captured.size) { "Supply nonempty distinct game hosts" }
            val ordered = captured.sortedBy { it.lockOrder }
            ordered.forEach { it.lock.lock() }
            try {
                return runBatch(captured)
            } finally {
                ordered.asReversed().forEach { it.lock.unlock() }
            }
        }

        private fun runBatch(hosts: List<ArgentumNeuralPolicyHost>): NeuralHostBatchResult {
            val started = System.nanoTime()
            val model = hosts.first().model
            require(hosts.all { it.model === model }) { "Batch hosts must share one frozen model instance" }
            var projectionNanos = 0L
            var scoringNanos = 0L
            var engineNanos = 0L
            var deliveryNanos = 0L
            fun result(lanes: List<NeuralHostLaneResult>) = NeuralHostBatchResult(lanes.toList(), projectionNanos,
                scoringNanos, engineNanos, deliveryNanos, System.nanoTime() - started)
            fun failure(stage: NeuralBatchFailureStage, accepted: Boolean?, error: Exception) =
                NeuralHostLaneResult.Failure(stage, accepted, "${error.javaClass.simpleName}:${error.message}".take(1024))
            val prepared = try {
                val start = System.nanoTime()
                try { hosts.map { it.prepare() } } finally { projectionNanos = System.nanoTime() - start }
            } catch (error: Exception) {
                return result(List(hosts.size) { failure(NeuralBatchFailureStage.PREPARATION, false, error) })
            }
            val decisions = try {
                val start = System.nanoTime()
                try {
                    val scores = arrayOfNulls<List<Float>>(hosts.size)
                    for (lanes in prepared.indices.filter { !prepared[it].forced }.chunked(model.maximumBatchSize)) {
                        val values = model.scoreBatch(lanes.map { prepared[it].input }, lanes.map { prepared[it].memory.memory.detached() })
                        require(values.size == lanes.size) { "Neural scorer lost a batch lane" }
                        lanes.forEachIndexed { i, lane -> scores[lane] = values[i] }
                    }
                    prepared.indices.map { prepared[it].scored(scores[it], null) }
                } finally { scoringNanos = System.nanoTime() - start }
            } catch (error: Exception) {
                return result(List(hosts.size) { failure(NeuralBatchFailureStage.SCORING, false, error) })
            }
            val lanes = arrayOfNulls<NeuralHostLaneResult>(hosts.size)
            val submitted = arrayOfNulls<Submitted>(hosts.size)
            val engineStart = System.nanoTime()
            for (i in hosts.indices) {
                try {
                    val value = hosts[i].submit(decisions[i])
                    submitted[i] = value
                    if (!value.result.accepted) lanes[i] = NeuralHostLaneResult.Transition(
                        NeuralHostTransition(value.decision, value.result, emptyMap(), value.nanos, 0))
                } catch (error: Exception) {
                    lanes[i] = failure(NeuralBatchFailureStage.SUBMISSION, null, error)
                }
            }
            engineNanos = System.nanoTime() - engineStart
            val deliveryStart = System.nanoTime()
            val deliveries = mutableListOf<Triple<Int, String, EpistemicState>>()
            for (i in hosts.indices.filter { submitted[it]?.result?.accepted == true }) {
                try {
                    val states = hosts[i].sessions.keys.map { player -> Triple(i, player, hosts[i].world.epistemicState(player)) }
                    deliveries.addAll(states)
                } catch (error: Throwable) {
                    hosts[i].failed = true
                    if (error !is Exception) {
                        // Other accepted games also lack delivery if a fatal error aborts this batch.
                        hosts.indices.filter { submitted[it]?.result?.accepted == true }.forEach { hosts[it].failed = true }
                        throw error
                    }
                    lanes[i] = failure(NeuralBatchFailureStage.DELIVERY, true, error)
                }
            }
            if (deliveries.isNotEmpty()) {
                try {
                    val receipts = PlayerPolicySession.observeBatch(deliveries.map { hosts[it.first].sessions.getValue(it.second) },
                        deliveries.map { it.third })
                    for (i in deliveries.map { it.first }.distinct()) {
                        val value = requireNotNull(submitted[i])
                        val receiptMap = deliveries.indices.filter { deliveries[it].first == i }.associate { deliveries[it].second to receipts[it] }
                        lanes[i] = NeuralHostLaneResult.Transition(NeuralHostTransition(value.decision, value.result, receiptMap, value.nanos, null))
                    }
                } catch (error: Throwable) {
                    // Engines already advanced. Invalidate before propagating fatal errors too.
                    for (i in deliveries.map { it.first }.distinct()) hosts[i].failed = true
                    if (error !is Exception) throw error
                    for (i in deliveries.map { it.first }.distinct())
                        lanes[i] = failure(NeuralBatchFailureStage.DELIVERY, true, error)
                }
            }
            deliveryNanos = System.nanoTime() - deliveryStart
            return result(lanes.map { requireNotNull(it) })
        }
    }
}
