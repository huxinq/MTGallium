package org.mtgallium.agent.infoset.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class IndependentContinuationLifecycleTest {
    @Test fun `fatal worker failure waits for sibling engine work before returning`() = failureScenario(fatalFirst = true)

    @Test fun `later fatal lane cancels an earlier blocked lane before waiting for cleanup`() = failureScenario(fatalFirst = false)

    private fun failureScenario(fatalFirst: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val observedFailure = AtomicReference<Throwable>()
        fun request(id: String, step: () -> Unit) = IndependentContinuationRequest(id, "p0", 3L, 0, 8,
            prepare = { val policy = MenuPolicy(); IndependentContinuationState(World(step), policy, policy) })
        val caller = Thread {
            try {
                val requests = listOf(
                    request("fatal") {
                        check(entered.await(5, TimeUnit.SECONDS))
                        throw AssertionError("Authored worker failure")
                    },
                    request("blocked") {
                        entered.countDown()
                        var waiting = true
                        while (waiting) {
                            try { release.await(); waiting = false }
                            catch (_: InterruptedException) { interrupted.countDown() }
                        }
                        exited.countDown()
                    },
                )
                IndependentContinuationBatch(2, 2).execute(if (fatalFirst) requests else requests.reversed())
            } catch (failure: Throwable) {
                observedFailure.set(failure)
            } finally { returned.countDown() }
        }
        caller.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            assertFalse(returned.await(250, TimeUnit.MILLISECONDS), "Sibling engine work is still active")
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertEquals(0L, exited.count)
            assertIs<AssertionError>(assertIs<ExecutionException>(observedFailure.get()).cause)
        } finally {
            release.countDown()
            caller.join(5000)
            assertFalse(caller.isAlive)
        }
    }

    private class MenuPolicy : ActionSelector {
        override val id = "lifecycle-test"
        override val requiresProductionAdmission = false
        override fun select(context: DecisionSiteRequest, policySeed: Long, sampleSeed: Long) =
            OpponentPolicyDecision(context.expansion.candidates.first(), OpponentPolicyDecisionDiagnostic(id, id))
    }

    private class World(private val onStep: () -> Unit, private var complete: Boolean = false) : SearchWorld {
        override fun actorToAct(): String? = if (complete) null else "p0"
        override fun decisionContext(view: DecisionView): DecisionSiteRequest = DecisionSiteRequest.capture(
            "p0", expandChoices(), { EpistemicState.capture(informationState("p0")) }, view)
        override fun informationState(viewer: String): InformationStateRepresentation = error("Menu-only fixture")
        override fun expandChoices() = PolicyExpansion(menu, true, 2L, "lifecycle-test")
        override fun fork(): SearchWorld = World(onStep, complete)
        override fun step(choice: SemanticChoice): SearchStepResult {
            require(!complete && choice in menu)
            onStep()
            complete = true
            return SearchStepResult(true)
        }
        override fun terminalPayoff(rootPlayer: String): Double? = if (complete) 1.0 else null
        override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double = error("Terminal fixture")
    }

    companion object {
        private val menu = (0..1).map { index -> SemanticChoice.create(SemanticChoiceKind.ACTION,
            SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay("Choice $index"),
            canonicalPayload = buildJsonObject { put("fixtureChoice", index) }) }
    }
}
