package org.mtgallium.agent.infoset.core

import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Trusted state factory output; every request owns its world and mutable policies exclusively. */
class IndependentContinuationState(
    val world: SearchWorld,
    val rootPolicy: ActionSelector,
    val opponentPolicy: ActionSelector,
)

class IndependentContinuationRequest(
    val id: String,
    val rootPlayer: String,
    val searchSeed: Long,
    val simulationIndex: Int,
    val initialExpansionLimit: Int,
    val maximumContinuationPolicyDecisions: Int = 4096,
    /** Null means the caller has already applied any conditioned edge. */
    val rootAction: SemanticChoice? = null,
    val childDepth: Int = 1,
    /** Optional declared world/chance coordinate; the factory applies it without rederivation. */
    val futureChanceSeed: Long? = null,
    /** Exact admission/annotation request under which the conditioned action must be present. */
    val rootView: DecisionView = DecisionView(initialExpansionLimit),
    val prepare: () -> IndependentContinuationState,
) {
    init {
        require(id.isNotBlank() && rootPlayer.isNotBlank() && simulationIndex >= 0)
        require(initialExpansionLimit > 0 && maximumContinuationPolicyDecisions > 0 && childDepth > 0)
    }
}

class IndependentContinuationCancellation {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    val isCancelled: Boolean get() = cancelled.get()
    internal fun check() {
        if (isCancelled || Thread.currentThread().isInterrupted) throw CancellationException()
    }
}

sealed interface IndependentContinuationOutcome {
    data class Terminal(val continuation: TerminalPolicyContinuation) : IndependentContinuationOutcome
    /** No exception message, hidden world or stack trace escapes into batch diagnostics. */
    data class NonGameFailure(val code: String) : IndependentContinuationOutcome
    data object Cancelled : IndependentContinuationOutcome
}

data class IndependentContinuationResult(
    val id: String,
    val outcome: IndependentContinuationOutcome,
    val conditionedActionApplied: Boolean,
    /** Execution time within the job; excludes preparation and queue delay. */
    val executionNanos: Long,
)

data class IndependentContinuationBatchResult(
    val results: List<IndependentContinuationResult>,
    val elapsedNanos: Long,
    val preparedRequests: Int,
    val workerTasks: Int,
)

/**
 * Bounded independent jobs, never adaptive tree work. Prepares at most maximumBatchSize states
 * and shares one worker pool across this call. Factories run serially before jobs, allowing
 * duplicate ownership to be refused before either aliased job steps. Distinct wrappers that
 * secretly share mutable internals cannot be detected: factories/fork implementations own that
 * contract. Cancellation is cooperative; there is no hard timeout for an engine/policy call.
 */
class IndependentContinuationBatch(val maximumBatchSize: Int, val workers: Int = 1) {
    init { require(maximumBatchSize > 0 && workers in 1..maximumBatchSize) }

    fun execute(
        requests: List<IndependentContinuationRequest>,
        cancellation: IndependentContinuationCancellation = IndependentContinuationCancellation(),
    ): IndependentContinuationBatchResult {
        require(requests.size <= maximumBatchSize)
        val declared = requests.toList()
        require(declared.map { it.id }.distinct().size == declared.size) { "Duplicate continuation ID" }
        val started = System.nanoTime()
        if (declared.isEmpty()) return IndependentContinuationBatchResult(emptyList(), System.nanoTime() - started, 0, 0)
        val states = arrayOfNulls<IndependentContinuationState>(declared.size)
        val results = arrayOfNulls<IndependentContinuationResult>(declared.size)
        val owners = IdentityHashMap<Any, Int>()
        val aliased = mutableSetOf<Int>()
        var prepared = 0
        declared.forEachIndexed { index, request ->
            try {
                cancellation.check()
                val state = request.prepare()
                prepared++
                states[index] = state
                // The root/opponent roles may share a policy WITHIN one independently owned job.
                for (owned in listOf(state.world, state.rootPolicy, state.opponentPolicy)) {
                    val previous = owners[owned]
                    if (previous != null && previous != index) { aliased += previous; aliased += index }
                    else owners[owned] = index
                }
            } catch (_: CancellationException) {
                results[index] = IndependentContinuationResult(request.id, IndependentContinuationOutcome.Cancelled, false, 0)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                cancellation.cancel()
                results[index] = IndependentContinuationResult(request.id, IndependentContinuationOutcome.Cancelled, false, 0)
            } catch (failure: Exception) {
                results[index] = IndependentContinuationResult(request.id,
                    IndependentContinuationOutcome.NonGameFailure(failure.javaClass.simpleName), false, 0)
            }
        }
        for (index in aliased) results[index] = IndependentContinuationResult(declared[index].id,
            IndependentContinuationOutcome.NonGameFailure("SharedContinuationState"), false, 0)

        // Include prepared worlds and every returned fork in one call-local identity registry.
        val worlds = IdentityHashMap<SearchWorld, Boolean>()
        states.filterNotNull().forEach { worlds[it.world] = true }
        fun ownFork(world: SearchWorld): SearchWorld {
            cancellation.check()
            val fork = world.fork()
            synchronized(worlds) { check(worlds.put(fork, true) == null) { "Continuation fork reused a world" } }
            return fork
        }
        fun run(index: Int): IndependentContinuationResult {
            val request = declared[index]
            val jobStarted = System.nanoTime()
            var applied = false
            val outcome = try {
                cancellation.check()
                val state = checkNotNull(states[index])
                val runner = TerminalPolicyContinuationRunner(state.rootPolicy, state.opponentPolicy,
                    request.initialExpansionLimit)
                val world = CancellableContinuationWorld(
                    if (request.rootAction == null) state.world else ownFork(state.world), cancellation, ::ownFork)
                if (request.rootAction != null) {
                    check(world.terminalPayoff(request.rootPlayer) == null)
                    check(world.actorToAct() == request.rootPlayer) { "Conditioned action perspective mismatch" }
                    request.rootAction.requireAdmittedChoice(world.decisionContext(request.rootView).expansion.candidates)
                    check(world.step(request.rootAction).accepted) { "Conditioned action rejected" }
                    applied = true
                }
                val continuation = runner.continueToTerminal(world, request.rootPlayer, request.searchSeed,
                    request.simulationIndex, request.childDepth, request.maximumContinuationPolicyDecisions)
                IndependentContinuationOutcome.Terminal(continuation)
            } catch (_: CancellationException) {
                IndependentContinuationOutcome.Cancelled
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                cancellation.cancel()
                IndependentContinuationOutcome.Cancelled
            } catch (failure: Exception) {
                IndependentContinuationOutcome.NonGameFailure(failure.javaClass.simpleName)
            }
            return IndependentContinuationResult(request.id, outcome, applied, System.nanoTime() - jobStarted)
        }
        val pending = declared.indices.filter { results[it] == null }
        val lanes = minOf(workers, pending.size)
        var interrupted = false
        if (lanes <= 1) {
            pending.forEach { results[it] = run(it) }
        } else {
            val pool = Executors.newFixedThreadPool(lanes)
            try {
                // Execution stripes and result coordinates stay deterministic. Observe completion
                // separately so a failed later lane can cancel an earlier blocked lane.
                val completions = ExecutorCompletionService<Unit>(pool)
                repeat(lanes) { lane ->
                    completions.submit(java.util.concurrent.Callable {
                        var cursor = lane
                        while (cursor < pending.size) {
                            val index = pending[cursor]
                            results[index] = run(index)
                            cursor += lanes
                        }
                    })
                }
                repeat(lanes) {
                    while (true) {
                        try { completions.take().get(); break }
                        catch (_: InterruptedException) { interrupted = true; cancellation.cancel() }
                    }
                }
            } catch (failure: Throwable) {
                cancellation.cancel()
                throw failure
            } finally {
                pool.shutdownNow()
                // Ownership returns only after every lane exits, including a sibling whose engine
                // call ignores interruption. Cancellation is cooperative, not a hard engine timeout.
                while (!pool.isTerminated) {
                    try { pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
                    catch (_: InterruptedException) { interrupted = true; cancellation.cancel() }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        return IndependentContinuationBatchResult(results.map { checkNotNull(it) }, System.nanoTime() - started, prepared, lanes)
    }
}

/** Keep the original safe decision-context projection; no evaluator receives the delegate world. */
private class CancellableContinuationWorld(
    private val delegate: SearchWorld,
    private val cancellation: IndependentContinuationCancellation,
    private val ownFork: (SearchWorld) -> SearchWorld,
) : SearchWorld {
    private inline fun <T> checked(action: () -> T): T { cancellation.check(); return action() }
    override fun actorToAct() = checked { delegate.actorToAct() }
    override fun epistemicState(viewer: String) = checked { delegate.epistemicState(viewer) }
    override fun decisionContext(view: DecisionView) = checked { delegate.decisionContext(view) }
    override fun informationState(viewer: String) = checked { delegate.informationState(viewer) }
    override fun expandChoices() = checked { delegate.expandChoices() }
    override fun step(choice: SemanticChoice) = checked { delegate.step(choice) }
    override fun terminalPayoff(rootPlayer: String) = checked { delegate.terminalPayoff(rootPlayer) }
    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double =
        error("Terminal continuation cannot use a leaf evaluator")
    override fun fork(): SearchWorld = checked { CancellableContinuationWorld(ownFork(delegate), cancellation, ownFork) }
}
