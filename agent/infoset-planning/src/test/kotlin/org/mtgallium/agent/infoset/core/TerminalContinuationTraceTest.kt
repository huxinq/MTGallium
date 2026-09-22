package org.mtgallium.agent.infoset.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class TerminalContinuationTraceTest {
    private val config = InformationSetSearchConfig(simulations = 1, maxPolicyDecisions = 1,
        leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT))
    private fun search() = InformationSetSearch(config, UniformOpponentPolicy, UniformOpponentPolicy,
        UniformOpponentPolicy, LeafValueSource.SampledWorld("argentum-board-v1"))

    @Test fun `actual same-actor and opponent choices retain admission execution and fixed payoff perspective`() {
        val collector = TerminalContinuationTraceCollector(TerminalContinuationTracePolicy(8), "p0")
        val result = search().continueFirstUnvisitedEdgeToTerminal(TraceWorld(), "p0", 7L, 0, trace = collector)
        val trace = collector.result()
        assertEquals(listOf("p0", "p0", "p1"), trace.decisions.map { it.actor })
        assertEquals(3, result.policyDecisions)
        assertEquals(0.75, result.payoff)
        assertEquals(TerminalContinuationStop.TERMINAL_PAYOFF, trace.stop)
        assertEquals(0.75, trace.terminalPayoff)
        trace.decisions.forEach { decision ->
            assertEquals(decision.actor, decision.information!!.observation.perspectivePlayerId)
            assertEquals(listOf("Pass", "Alternative"), decision.admittedMenu.map { it.display.label })
            assertTrue(decision.admittedMenu.any { it.signature == decision.selectedSignature })
            assertEquals(true, decision.accepted)
            assertEquals(decision.actor, decision.afterInformation!!.observation.perspectivePlayerId)
            assertNotNull(decision.policySeed)
        }
        assertEquals(trace, PolicyJson.format.decodeFromString<TerminalContinuationTrace>(PolicyJson.format.encodeToString(trace)))
    }

    @Test fun `capture prefix does not shorten continuation and cap is never payoff or heuristic settlement`() {
        val prefix = TerminalContinuationTraceCollector(TerminalContinuationTracePolicy(1), "p0")
        val result = search().continueFirstUnvisitedEdgeToTerminal(TraceWorld(), "p0", 7L, 0, trace = prefix)
        assertEquals(3, result.policyDecisions)
        assertEquals(3, prefix.result().observedDecisions)
        assertEquals(1, prefix.result().decisions.size)
        val capped = TerminalContinuationTraceCollector(TerminalContinuationTracePolicy(8), "p0")
        assertFailsWith<IllegalStateException> {
            search().continueFirstUnvisitedEdgeToTerminal(TraceWorld(), "p0", 7L, 0,
                maximumContinuationPolicyDecisions = 2, trace = capped)
        }
        assertEquals(TerminalContinuationStop.DECISION_CAP, capped.result().stop)
        assertNull(capped.result().terminalPayoff)
        assertNull(capped.result().failureCode)
    }

    @Test fun `rejected transition retains prior decisions and failure without payoff`() {
        val collector = TerminalContinuationTraceCollector(TerminalContinuationTracePolicy(8), "p0")
        assertFailsWith<IllegalStateException> {
            search().continueFirstUnvisitedEdgeToTerminal(TraceWorld(rejectAt = 1), "p0", 7L, 0, trace = collector)
        }
        val trace = collector.result()
        assertEquals(TerminalContinuationStop.NON_GAME_FAILURE, trace.stop)
        assertEquals(listOf(true, false), trace.decisions.map { it.accepted })
        assertNull(trace.terminalPayoff)
        assertNotNull(trace.failureCode)
        assertFalse(PolicyJson.format.encodeToString(trace).contains("PRIVATE-REFEREE-REJECTION"))
    }

    @Test fun `unavailable diagnostic projection does not disable menu-only selection`() {
        val policy = object : OpponentPolicy {
            override val id = "menu-only-trace-fixture-v1"
            override fun select(context: DecisionSiteRequest, policySeed: Long, sampleSeed: Long) =
                OpponentPolicyDecision(context.expansion.candidates.first(), OpponentPolicyDecisionDiagnostic(id, id))
            override fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.expansion.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation, candidates: List<SemanticChoice>, policySeed: Long):
                ProbabilityDistribution<SemanticChoice> = error("Menu-only selection must not request information")
        }
        val search = InformationSetSearch(config, policy, policy, policy,
            LeafValueSource.SampledWorld("argentum-board-v1"))
        val trace = TerminalContinuationTraceCollector(TerminalContinuationTracePolicy(4), "p0")
        val result = search.continueFirstUnvisitedEdgeToTerminal(TraceWorld(unavailableInformation = true), "p0", 1L, 0, trace = trace)
        assertEquals(0.75, result.payoff)
        assertTrue(trace.result().decisions.all { it.information == null && it.informationUnavailable != null })
    }

    @Test fun `accepted decision observer records only verified accepted transitions`() {
        val runner = TerminalPolicyContinuationRunner(UniformOpponentPolicy, UniformOpponentPolicy, 4)
        val accepted = mutableListOf<String>()
        val result = runner.continueToTerminal(TraceWorld(), "p0", 7L, 0, childDepth = 1,
            acceptedDecisionObserver = { actor, _ -> accepted += actor })
        assertEquals(3, result.policyDecisions)
        assertEquals(0.75, result.payoff)
        assertEquals(listOf("p0", "p0", "p1"), accepted)

        val beforeRejection = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            runner.continueToTerminal(TraceWorld(rejectAt = 1), "p0", 7L, 0, childDepth = 1,
                acceptedDecisionObserver = { actor, _ -> beforeRejection += actor })
        }
        assertEquals(listOf("p0"), beforeRejection)
    }

    @Test fun `retained inputs are detached from caller-owned mutable menus`() {
        val information = TraceWorld().informationState("p0")
        val menu = information.candidates.toMutableList()
        val trace = TerminalContinuationTraceCollector(TerminalContinuationTracePolicy(1), "p0")
        trace.recordDecision({ information.copy(candidates = menu) }, "p0", menu, true,
            OpponentPolicyDecision(menu.first(), OpponentPolicyDecisionDiagnostic("conditioned-root", "conditioned-root")), null)
        menu.clear()
        trace.finish(TerminalContinuationStop.NON_GAME_FAILURE, failureCode = "StoppedBeforeSubmission")
        assertEquals(2, trace.result().decisions.single().admittedMenu.size)
        assertEquals(2, trace.result().decisions.single().information!!.candidates.size)
        assertNull(trace.result().decisions.single().accepted)
    }
}

private class TraceWorld(private var depth: Int = 0, private val rejectAt: Int? = null,
    private val unavailableInformation: Boolean = false) : SearchWorld {
    private val menu = listOf("Pass", "Alternative").map { label ->
        SemanticChoice.create(kind = SemanticChoiceKind.ACTION, operationFamily = SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay(label), canonicalPayload = JsonObject(mapOf("fixture" to JsonPrimitive(label))))
    }
    override fun actorToAct(): String? = when (depth) { 0, 1 -> "p0"; 2 -> "p1"; else -> null }
    override fun decisionContext(view: DecisionView): DecisionSiteRequest {
        val captured = fork()
        return DecisionSiteRequest.capture(requireNotNull(actorToAct()), expandChoices(),
            { captured.epistemicState(requireNotNull(captured.actorToAct())) }, view, "trace-fixture-v1")
    }
    override fun informationState(viewer: String): InformationStateRepresentation {
        check(!unavailableInformation) { "PRIVATE-REFEREE-PROJECTION" }
        val observation = PlayerObservationSnapshot(viewer, depth, "TEST", "PRIORITY", "p0", actorToAct(),
            emptyList(), emptyList(), emptyList(), pendingDecision = null, observationDigest = PolicyJson.sha256("$viewer:$depth"))
        return InformationStateRepresentation(actingPlayerId = actorToAct(), observation = observation,
            informationStateDigest = PolicyJson.sha256("information:$viewer:$depth"),
            historyCommitment = PolicyHistoryCommitment.empty(), history = emptyList(),
            candidates = if (viewer == actorToAct()) menu else emptyList(), terminated = depth == 3)
    }
    override fun expandChoices() = PolicyExpansion(menu, true, 2L, "trace-fixture-v1", 7L)
    override fun step(choice: SemanticChoice): SearchStepResult {
        require(choice in menu)
        if (depth == rejectAt) return SearchStepResult(false, "PRIVATE-REFEREE-REJECTION")
        depth++
        return SearchStepResult(true)
    }
    override fun fork(): SearchWorld = TraceWorld(depth, rejectAt, unavailableInformation)
    override fun terminalPayoff(rootPlayer: String): Double? = if (depth == 3) { if (rootPlayer == "p0") .75 else -.75 } else null
    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double = error("No heuristic allowed")
}
