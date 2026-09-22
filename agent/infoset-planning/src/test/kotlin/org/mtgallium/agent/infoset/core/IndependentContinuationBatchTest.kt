package org.mtgallium.agent.infoset.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.*

class IndependentContinuationBatchTest {
    private fun request(id: String, state: () -> IndependentContinuationState, rootAction: SemanticChoice? = null,
        cap: Int = 8, simulation: Int = 3) = IndependentContinuationRequest(id, "p0", 712L, simulation, 16,
        maximumContinuationPolicyDecisions = cap, rootAction = rootAction, prepare = state)

    @Test fun `deck neutral independent configurations preserve exact seeds decisions and payoff at widths one and two`() {
        fun execute(width: Int): Pair<List<IndependentContinuationOutcome>, List<List<Pair<Long, Long>>>> {
            val policies = List(4) { BatchPolicy() }
            val worlds = List(4) { BatchWorld(horizon = it + 2, hidden = it) }
            val requests = worlds.indices.map { i -> request("coordinate-${i * 7}", {
                IndependentContinuationState(worlds[i], policies[i], policies[i])
            }, simulation = i * 11) }
            val result = IndependentContinuationBatch(4, width).execute(requests)
            assertEquals(requests.map { it.id }, result.results.map { it.id })
            assertEquals(4, result.preparedRequests)
            assertEquals(width, result.workerTasks)
            worlds.forEach { assertEquals(0, it.depth) }
            result.results.forEachIndexed { i, row ->
                assertEquals(i + 2, assertIs<IndependentContinuationOutcome.Terminal>(row.outcome).continuation.policyDecisions)
                assertFalse(row.conditionedActionApplied)
            }
            return result.results.map { it.outcome } to policies.map { it.seeds.toList() }
        }
        val serial = execute(1)
        assertEquals(serial, execute(2))
        // The existing scalar owner remains the independent oracle for each logical coordinate.
        for (i in 0..3) {
            val policy = BatchPolicy()
            val expected = TerminalPolicyContinuationRunner(policy, policy, 16).continueToTerminal(
                BatchWorld(horizon = i + 2, hidden = i), "p0", 712L, i * 11, maximumContinuationPolicyDecisions = 8)
            assertEquals(expected, assertIs<IndependentContinuationOutcome.Terminal>(serial.first[i]).continuation)
            assertEquals(policy.seeds, serial.second[i])
        }
    }

    @Test fun `completion order does not reorder outputs and failures never become payoff`() {
        val fast = CountDownLatch(1)
        val stepped = AtomicInteger()
        val requests = listOf(
            request("slow", { val p = BatchPolicy(); IndependentContinuationState(
                BatchWorld(horizon = 1, beforeStep = { check(fast.await(5, TimeUnit.SECONDS)) }), p, p) }),
            request("fast", { val p = BatchPolicy(); IndependentContinuationState(
                BatchWorld(horizon = 1, beforeStep = { fast.countDown() }), p, p) }),
            request("rejected", { val p = BatchPolicy(); IndependentContinuationState(BatchWorld(reject = true), p, p) }),
            request("capped", { val p = BatchPolicy(); IndependentContinuationState(BatchWorld(), p, p) }, cap = 1),
            request("bad-policy", { val p = BatchPolicy(refuseAdmission = true); IndependentContinuationState(
                BatchWorld(beforeStep = { stepped.incrementAndGet() }), p, p) }),
            request("bad-factory", { error("PRIVATE-WORLD-DETAIL") }),
        )
        val result = IndependentContinuationBatch(6, 2).execute(requests)
        assertEquals(requests.map { it.id }, result.results.map { it.id })
        result.results.take(2).forEach { assertIs<IndependentContinuationOutcome.Terminal>(it.outcome) }
        result.results.drop(2).forEach { assertIs<IndependentContinuationOutcome.NonGameFailure>(it.outcome) }
        assertEquals(0, stepped.get())
        assertFalse(result.toString().contains("PRIVATE-WORLD-DETAIL"))
    }

    @Test fun `conditioned action is admitted before stepping and counted separately from subsequent policy decisions`() {
        val steps = AtomicInteger()
        fun state(): IndependentContinuationState { val p = BatchPolicy(); return IndependentContinuationState(
            BatchWorld(beforeStep = { steps.incrementAndGet() }), p, p) }
        val result = IndependentContinuationBatch(2).execute(listOf(
            request("admitted", ::state, rootAction = batchMenu.first()),
            request("unadmitted", ::state, rootAction = batchChoice("outside")),
        ))
        assertEquals(3, steps.get())
        assertTrue(result.results[0].conditionedActionApplied)
        assertEquals(2, assertIs<IndependentContinuationOutcome.Terminal>(result.results[0].outcome).continuation.policyDecisions)
        assertFalse(result.results[1].conditionedActionApplied)
        assertIs<IndependentContinuationOutcome.NonGameFailure>(result.results[1].outcome)
    }

    @Test fun `shared world policy or nonisolating fork refuses before trial state mutation`() {
        val world = BatchWorld()
        val policy = BatchPolicy()
        val batch = IndependentContinuationBatch(2, 2)
        val sharedWorld = batch.execute(List(2) { i -> request("w$i", {
            val p = BatchPolicy(); IndependentContinuationState(world, p, p)
        }) })
        val sharedPolicy = batch.execute(List(2) { i -> request("p$i", {
            IndependentContinuationState(BatchWorld(), policy, policy)
        }) })
        (sharedWorld.results + sharedPolicy.results).forEach {
            assertEquals(IndependentContinuationOutcome.NonGameFailure("SharedContinuationState"), it.outcome)
        }
        assertEquals(0, world.depth); assertEquals(0, policy.seeds.size)
        val invalidFork = batch.execute(listOf(request("fork", {
            val p = BatchPolicy(); IndependentContinuationState(BatchWorld(badFork = true), p, p)
        })))
        assertIs<IndependentContinuationOutcome.NonGameFailure>(invalidFork.results.single().outcome)
    }

    @Test fun `conditioned root forwards its exact admission view and refusal precedes stepping`() {
        val steps = AtomicInteger()
        val requestedView = DecisionView(9, DecisionAdmission.PRODUCTION)
        fun guarded(): SearchWorld {
            val delegate = BatchWorld(beforeStep = { steps.incrementAndGet() })
            return object : SearchWorld by delegate {
                override fun fork() = guarded()
                override fun decisionContext(view: DecisionView): DecisionSiteRequest {
                    assertEquals(requestedView, view)
                    error("PRIVATE-ADMISSION-REFUSAL")
                }
            }
        }
        val result = IndependentContinuationBatch(1).execute(listOf(IndependentContinuationRequest(
            id = "production-root", rootPlayer = "p0", searchSeed = 1, simulationIndex = 0,
            initialExpansionLimit = 16, rootAction = batchMenu.first(), rootView = requestedView,
            prepare = { val p = BatchPolicy(); IndependentContinuationState(guarded(), p, p) },
        )))
        assertIs<IndependentContinuationOutcome.NonGameFailure>(result.results.single().outcome)
        assertEquals(0, steps.get())
        assertFalse(result.toString().contains("PRIVATE-ADMISSION-REFUSAL"))
    }

    @Test fun `lazy empty bounded and cancelled batches never silently omit a coordinate`() {
        var prepared = 0
        val token = IndependentContinuationCancellation()
        val declared = List(2) { i -> request("c$i", {
            prepared++; val p = BatchPolicy(); IndependentContinuationState(
                BatchWorld(beforeStep = { token.cancel() }), p, p)
        }) }
        val batch = IndependentContinuationBatch(2)
        assertEquals(0, prepared)
        assertEquals(emptyList(), batch.execute(emptyList()).results)
        assertFailsWith<IllegalArgumentException> { batch.execute(declared + declared.first()) }
        assertFailsWith<IllegalArgumentException> { batch.execute(listOf(declared.first(), declared.first())) }
        assertEquals(0, prepared)
        val result = batch.execute(declared, token)
        assertEquals(List(2) { IndependentContinuationOutcome.Cancelled }, result.results.map { it.outcome })
        assertEquals(2, prepared)
        val cancelled = batch.execute(declared, token)
        assertEquals(0, cancelled.preparedRequests)
        assertEquals(2, prepared)
        assertEquals(List(2) { IndependentContinuationOutcome.Cancelled }, cancelled.results.map { it.outcome })
    }

    @Test fun `menu only policies remain lazy when safe information projection is unavailable`() {
        val policy = BatchPolicy(menuOnly = true)
        val result = IndependentContinuationBatch(1).execute(listOf(request("lazy", {
            IndependentContinuationState(BatchWorld(unavailableInformation = true), policy, policy)
        })))
        assertIs<IndependentContinuationOutcome.Terminal>(result.results.single().outcome)
    }

    @Test fun `hidden payoff differences cannot change same information policy inputs or seed path`() {
        val policies = List(2) { BatchPolicy() }
        val results = IndependentContinuationBatch(2, 2).execute(List(2) { i -> request("hidden-$i", {
            IndependentContinuationState(BatchWorld(hidden = i), policies[i], policies[i])
        }) }).results
        assertEquals(policies[0].seeds, policies[1].seeds)
        assertNotEquals(assertIs<IndependentContinuationOutcome.Terminal>(results[0].outcome).continuation.payoff,
            assertIs<IndependentContinuationOutcome.Terminal>(results[1].outcome).continuation.payoff)
    }

    @Test fun `interrupted engine work is cancellation with the interrupt preserved`() {
        try {
            val result = IndependentContinuationBatch(1).execute(listOf(request("interrupted", {
                val p = BatchPolicy(); IndependentContinuationState(BatchWorld(beforeStep = { throw InterruptedException() }), p, p)
            })))
            assertEquals(IndependentContinuationOutcome.Cancelled, result.results.single().outcome)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}

private fun batchChoice(label: String) = SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
    operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay(label),
    canonicalPayload = JsonObject(mapOf("fixture" to JsonPrimitive(label))))
private val batchMenu = listOf(batchChoice("a"), batchChoice("b"))

private class BatchPolicy(private val refuseAdmission: Boolean = false, private val menuOnly: Boolean = false) : ActionSelector {
    override val id = "batch-toy-policy-v1"
    val seeds = mutableListOf<Pair<Long, Long>>()
    private fun choose(candidates: List<SemanticChoice>, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
        seeds += policySeed to sampleSeed
        val choice = if (refuseAdmission) batchChoice("outside") else candidates[Random(sampleSeed).nextInt(candidates.size)]
        return OpponentPolicyDecision(choice, OpponentPolicyDecisionDiagnostic(id, id))
    }
    override fun select(context: DecisionSiteRequest, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision =
        if (menuOnly) choose(context.expansion.candidates, policySeed, sampleSeed)
        else select(context.information(), context.expansion.candidates, policySeed, sampleSeed)

    fun select(opponentInformation: InformationStateRepresentation, candidates: List<SemanticChoice>,
        policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
        assertEquals(opponentInformation.actingPlayerId, opponentInformation.observation.perspectivePlayerId)
        assertFalse(opponentInformation.toString().contains("PRIVATE-WORLD-DETAIL"))
        return choose(candidates, policySeed, sampleSeed)
    }
}

/** Deck-neutral toy with independently variable horizon/hidden payoff and real mutable trial state. */
private class BatchWorld(
    var depth: Int = 0,
    private var tally: Int = 0,
    private val horizon: Int = 3,
    private val hidden: Int = 0,
    private val reject: Boolean = false,
    private val badFork: Boolean = false,
    private val unavailableInformation: Boolean = false,
    private val beforeStep: () -> Unit = {},
) : SearchWorld {
    override fun actorToAct(): String? = if (depth >= horizon) null else if (depth % 2 == 0) "p0" else "p1"
    override fun decisionContext(view: DecisionView): DecisionSiteRequest {
        val captured = fork()
        return DecisionSiteRequest.capture(requireNotNull(actorToAct()), expandChoices(),
            { captured.epistemicState(requireNotNull(captured.actorToAct())) }, view, "batch-toy-v1")
    }
    override fun informationState(viewer: String): InformationStateRepresentation {
        check(!unavailableInformation) { "PRIVATE-WORLD-DETAIL" }
        val observation = PlayerObservationSnapshot(viewer, depth, "TEST", "PRIORITY", "p0", actorToAct(),
            emptyList(), emptyList(), emptyList(), pendingDecision = null, observationDigest = PolicyJson.sha256("$viewer:$depth"))
        return InformationStateRepresentation(actingPlayerId = actorToAct(), observation = observation,
            informationStateDigest = PolicyJson.sha256("information:$viewer:$depth"),
            historyCommitment = PolicyHistoryCommitment.empty(), history = emptyList(),
            candidates = if (viewer == actorToAct()) batchMenu else emptyList(), terminated = depth >= horizon)
    }
    override fun expandChoices() = PolicyExpansion(batchMenu, true, 2L, "batch-toy-v1", 7L)
    override fun step(choice: SemanticChoice): SearchStepResult {
        require(choice in batchMenu)
        beforeStep()
        if (reject) return SearchStepResult(false, "PRIVATE-WORLD-DETAIL")
        depth++; tally += batchMenu.indexOf(choice)
        return SearchStepResult(true)
    }
    override fun fork(): SearchWorld = if (badFork) this else BatchWorld(depth, tally, horizon, hidden, reject,
        badFork, unavailableInformation, beforeStep)
    override fun terminalPayoff(rootPlayer: String): Double? = if (depth < horizon) null else
        ((tally + hidden) % 3 - 1).toDouble() * if (rootPlayer == "p0") 1 else -1
    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double = error("No hidden-world evaluator")
}
