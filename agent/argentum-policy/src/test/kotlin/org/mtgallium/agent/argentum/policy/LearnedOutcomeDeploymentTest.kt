package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.value.fixtures.*
import org.mtgallium.agent.monored.ValueEvaluationException
import org.mtgallium.agent.value.*
import org.mtgallium.agent.monored.ValueEvaluationStop
import java.util.Base64
import kotlin.math.ln1p
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.BeliefApproximation
import org.mtgallium.agent.infoset.planning.ParticleSet
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.planning.InformationSetSearchConfig
import org.mtgallium.agent.infoset.planning.LeafEvaluationConfig
import org.mtgallium.agent.infoset.planning.LeafEvaluationMethod
import org.mtgallium.agent.infoset.planning.LeafValueSource
import org.mtgallium.agent.infoset.planning.RolloutCutoff
import org.mtgallium.agent.infoset.core.ObservedEventDetail
import org.mtgallium.agent.infoset.core.EventAudience
import org.mtgallium.agent.infoset.core.EventAudienceScope
import org.mtgallium.agent.infoset.core.ObjectView
import org.mtgallium.agent.infoset.core.CombatView
import org.mtgallium.agent.infoset.core.HistoryHashChain
import org.mtgallium.agent.infoset.core.ObservedEvent
import org.mtgallium.agent.infoset.core.ObservedEventKind
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.DecisionContext
import org.mtgallium.agent.infoset.core.MenuRequest
import org.mtgallium.agent.infoset.core.InformationState
import org.mtgallium.agent.infoset.core.CanonicalJson
import org.mtgallium.agent.infoset.core.KnownLibraryOrder
import org.mtgallium.agent.infoset.core.KnownObject
import org.mtgallium.agent.infoset.core.PlayerKnowledge
import org.mtgallium.agent.infoset.core.ManaPoolView
import org.mtgallium.agent.infoset.core.PlayerObservationSnapshot
import org.mtgallium.agent.infoset.core.PendingDecisionView
import org.mtgallium.agent.infoset.core.PlayerView
import org.mtgallium.agent.infoset.core.StackObjectView
import org.mtgallium.agent.infoset.core.ZoneKnowledge
import org.mtgallium.agent.infoset.core.ZoneView
import org.mtgallium.agent.infoset.core.ReturnSource
import org.mtgallium.agent.infoset.planning.SearchStepResult
import org.mtgallium.agent.infoset.planning.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.PendingDecisionOptions
import org.mtgallium.agent.infoset.core.ActionMenu
import org.mtgallium.agent.infoset.core.Weighted

class LearnedOutcomeDeploymentTest {
    private val learnedLeaf = LeafEvaluationConfig(
        LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
        RolloutCutoff.EVALUATE,
    )

    @Test
    fun `a supplied scorer is the explicit value source`() {
        val evaluator = evaluator()
        val source = LeafValueSource.Information(evaluator)
        assertEquals(evaluator.configurationId, source.invokedEvaluatorConfigurationId)
        assertEquals(evaluator.id, source.invokedEvaluatorId)
    }

    @Test
    fun `checkpoint observer preserves inference authority and records the exact evaluated state`() {
        val evaluator = evaluator()
        val calls = mutableListOf<Triple<InformationStateRepresentation, String, Double>>()
        val observed = evaluator.observedBy { information, rootPlayer, value ->
            calls += Triple(information, rootPlayer, value)
        }
        val input = state()

        val source = LeafValueSource.Information(observed)
        val value = observed.evaluate(input, "p0")

        assertEquals(evaluator.configurationId, source.invokedEvaluatorConfigurationId)
        assertEquals(evaluator.evaluate(input, "p0"), value)
        assertEquals(listOf(Triple(input, "p0", value)), calls)
    }

    @Test
    fun `core terminal payoff bypasses model while nonterminal leaves invoke it`() {
        val evaluator = evaluator()
        val terminal = search(evaluator).search(
            rootPlayer = "p0",
            belief = belief(LearnedTestWorld(terminalAfterStep = true)),
            searchSeed = 11L,
        )
        val nonterminal = search(evaluator).search(
            rootPlayer = "p0",
            belief = belief(LearnedTestWorld(terminalAfterStep = false)),
            searchSeed = 12L,
        )

        assertEquals(1.0, terminal.rootValue)
        assertEquals(0, terminal.diagnostics.evaluatorCalls)
        assertTrue(terminal.candidateSettlementCounts.values.all {
            it.successfulBackups == 1 && it.terminalPayoffBackups == 1
        })
        assertEquals(1, nonterminal.diagnostics.evaluatorCalls)
        assertTrue(nonterminal.candidateSettlementCounts.values.all {
            it.successfulBackups == 1 && it.learnedOutcomeEstimateBackups == 1 &&
                it.heuristicSettlementBackups == 0
        })
    }

    @Test
    fun `leaf input failure aborts search instead of becoming a strategic value`() {
        val failure = assertFailsWith<ValueEvaluationException> {
            search(evaluator()).search(
                rootPlayer = "p0",
                belief = belief(
                    LearnedTestWorld(
                        terminalAfterStep = false,
                        incompleteLeafTurnState = true,
                    )
                ),
                searchSeed = 13L,
            )
        }

        assertEquals(
            ValueInputError.INPUT_CURRENT_TURN_STATE_INCOMPLETE,
            failure.kind,
        )
    }

    private fun search(evaluator: LinearValueEvaluator) =
        modelTestSearch(
            config = InformationSetSearchConfig(simulations = 1, leaf = learnedLeaf),
            valueSource = LeafValueSource.Information(evaluator),
        )

    private fun belief(world: SearchWorld): ParticleSet<Weighted<SearchWorld>> = ParticleSet(
        particles = listOf(Weighted(world, 1.0)),
        diagnostics = BeliefDiagnostics(
            mode = BeliefMode.CONSISTENCY_ONLY_V1,
            requestedParticles = 1,
            acceptedParticles = 1,
            rejectedParticles = 0,
            effectiveSampleSizeBefore = 1.0,
            effectiveSampleSizeAfter = 1.0,
            entropy = 0.0,
            resamplingCount = 0,
            architecture = BeliefApproximation.SEQUENTIAL_B_V1,
        ),
    )

    private inner class LearnedTestWorld(
        private val terminalAfterStep: Boolean,
        private val incompleteLeafTurnState: Boolean = false,
        private var depth: Int = 0,
    ) : SearchWorld {
        override fun actorToAct(): String? = if (depth == 0) "p0" else "p1"

        override fun decisionContext(view: MenuRequest): DecisionContext {
            val actor = requireNotNull(actorToAct())
            val information = informationState(actor)
            return DecisionContext.capture(actor, expandChoices(), { InformationState.capture(information) }, view)
        }

        override fun informationState(viewer: String): InformationStateRepresentation {
            return state(
                rootPlayer = viewer,
                opponentPlayer = if (viewer == "p0") "p1" else "p0",
                actor = actorToAct()!!,
                currentTurnStateComplete = !(depth > 0 && incompleteLeafTurnState),
            )
        }

        override fun expandChoices(): ActionMenu = if (depth == 0) {
            ActionMenu(
                candidates = listOf(choice("advance")),
                isExhaustive = true,
                estimatedCandidateCount = 1,
                proposalVersion = "learned-test-v1",
            )
        } else {
            ActionMenu(
                candidates = emptyList(),
                isExhaustive = true,
                estimatedCandidateCount = 0,
                proposalVersion = "learned-test-v1",
            )
        }

        override fun step(choice: SemanticChoice): SearchStepResult {
            depth++
            return SearchStepResult(accepted = true)
        }

        override fun fork(): SearchWorld = LearnedTestWorld(
            terminalAfterStep,
            incompleteLeafTurnState,
            depth,
        )

        override fun terminalPayoff(rootPlayer: String): Double? =
            1.0.takeIf { terminalAfterStep && depth > 0 }


    }
}
