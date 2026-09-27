package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PlanningRuntimeIsolationTest {
    @Test fun `planner executes searched choices without an engine or model runtime`() {
        for (name in listOf("com.wingedsheep.engine.core.GameAction", "org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld",
            "org.mtgallium.agent.value.MaterialEvaluator", "org.mtgallium.agent.argentum.policy.LivePolicySession")) {
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        }
        val leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE)
        val planner = InformationSetSearch(InformationSetSearchConfig(simulations = 32, leaf = leaf),
            UniformOpponentPolicy, UniformOpponentPolicy, UniformOpponentPolicy,
            LeafValueSource.Information(testInformationEvaluator()))
        val world = ToyWorld()
        val belief = ParticleSet(listOf(Weighted<SearchWorld>(world, 1.0)),
            BeliefDiagnostics(BeliefMode.CONSISTENCY_ONLY_V1, 1, 1, 0, 1.0, 1.0, 0.0, 0))
        val result = planner.search("p0", belief, 7L)
        assertEquals(choices.last(), result.chosen)
        assertEquals(null, world.terminalPayoff("p0"))
        assertEquals(32, result.diagnostics.simulations)
    }

    private class ToyWorld(private var payoff: Double? = null) : SearchWorld {
        override fun actorToAct() = "p0".takeIf { payoff == null }
        override fun decisionContext(view: MenuRequest): DecisionContext {
            val actor = requireNotNull(actorToAct())
            return DecisionContext.capture(actor, expandChoices(), { InformationState.capture(informationState(actor)) }, view)
        }
        override fun expandChoices() = ActionMenu(if (payoff == null) choices else emptyList(), true,
            if (payoff == null) 2 else 0, "toy-v1")
        override fun informationState(viewer: String): InformationStateRepresentation {
            val observation = PlayerObservationSnapshot(viewer, 1, "MAIN", "PRECOMBAT_MAIN", "p0", actorToAct(),
                emptyList(), emptyList(), emptyList(), currentTurnStateComplete = true, pendingDecision = null,
                observationDigest = "toy-$payoff")
            return InformationStateRepresentation(actingPlayerId = actorToAct(), observation = observation,
                informationStateDigest = "toy-$viewer-$payoff", historyCommitment = HistoryHashChain.empty(),
                history = emptyList(), candidates = expandChoices().candidates, terminated = payoff != null)
        }
        override fun step(choice: SemanticChoice): SearchStepResult {
            require(payoff == null && choice in choices)
            payoff = if (choice == choices.last()) 1.0 else -1.0
            return SearchStepResult(true)
        }
        override fun fork(): SearchWorld = ToyWorld(payoff)
        override fun terminalPayoff(rootPlayer: String) = payoff
    }

    companion object {
        private val choices = listOf("lose", "win").map { label ->
            SemanticChoice.create(kind = SemanticChoiceKind.ACTION, operationFamily = SemanticOperationFamily.CAST_SPELL,
                display = SemanticChoiceDisplay(label), canonicalPayload = buildJsonObject { put("syntheticAction", label) })
        }
    }
}
