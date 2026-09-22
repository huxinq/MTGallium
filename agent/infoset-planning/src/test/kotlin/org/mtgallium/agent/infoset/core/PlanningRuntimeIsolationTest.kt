package org.mtgallium.agent.infoset.core

import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PlanningRuntimeIsolationTest {
    @Test fun `planner executes searched choices without an engine or model runtime`() {
        for (name in listOf("com.wingedsheep.engine.core.GameAction", "org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld",
            "org.mtgallium.agent.monored.MonoRedInformationEvaluator", "org.mtgallium.agent.argentum.policy.LivePolicySession")) {
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        }
        val leaf = LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD)
        val planner = InformationSetSearch(InformationSetSearchConfig(simulations = 32, leaf = leaf),
            UniformOpponentPolicy, UniformOpponentPolicy, UniformOpponentPolicy,
            LeafValueSource.SampledWorld("argentum-board-v1"))
        val world = ToyWorld()
        val belief = BeliefBatch(listOf(Weighted<SearchWorld>(world, 1.0)),
            BeliefDiagnostics(BeliefMode.CONSISTENCY_ONLY_V1, 1, 1, 0, 1.0, 1.0, 0.0, 0))
        val result = planner.search("p0", belief, 7L)
        assertEquals(choices.last(), result.chosen)
        assertEquals(null, world.terminalPayoff("p0"))
        assertEquals(32, result.diagnostics.simulations)
    }

    private class ToyWorld(private var payoff: Double? = null) : SearchWorld {
        override fun actorToAct() = "p0".takeIf { payoff == null }
        override fun decisionContext(view: DecisionView): DecisionSiteRequest {
            val actor = requireNotNull(actorToAct())
            return DecisionSiteRequest.capture(actor, expandChoices(), { EpistemicState.capture(informationState(actor)) }, view)
        }
        override fun expandChoices() = PolicyExpansion(if (payoff == null) choices else emptyList(), true,
            if (payoff == null) 2 else 0, "toy-v1")
        override fun informationState(viewer: String): InformationStateRepresentation {
            val observation = PlayerObservationSnapshot(viewer, 1, "MAIN", "PRECOMBAT_MAIN", "p0", actorToAct(),
                emptyList(), emptyList(), emptyList(), currentTurnStateComplete = true, pendingDecision = null,
                observationDigest = "toy-$payoff")
            return InformationStateRepresentation(actingPlayerId = actorToAct(), observation = observation,
                informationStateDigest = "toy-$viewer-$payoff", historyCommitment = PolicyHistoryCommitment.empty(),
                history = emptyList(), candidates = expandChoices().candidates, terminated = payoff != null)
        }
        override fun step(choice: SemanticChoice): SearchStepResult {
            require(payoff == null && choice in choices)
            payoff = if (choice == choices.last()) 1.0 else -1.0
            return SearchStepResult(true)
        }
        override fun fork(): SearchWorld = ToyWorld(payoff)
        override fun terminalPayoff(rootPlayer: String) = payoff
        override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String) = 0.0
    }

    companion object {
        private val choices = listOf("lose", "win").map { label ->
            SemanticChoice.create(kind = SemanticChoiceKind.ACTION, operationFamily = SemanticOperationFamily.CAST_SPELL,
                display = SemanticChoiceDisplay(label), canonicalPayload = buildJsonObject { put("syntheticAction", label) })
        }
    }
}
