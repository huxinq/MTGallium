package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

import kotlin.test.*
import kotlinx.serialization.json.*

class NativePlanningBoundaryTest {
    @Test fun `sampled nonterminal leaf records volatility without information projection`() {
        val world = NativeWorld()
        val search = InformationSetSearch(InformationSetSearchConfig(
            simulations = 1, maxPolicyDecisions = 1,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)),
            NativePolicy, NativePolicy, NativePolicy,
            valueSource = LeafValueSource.Information(testInformationEvaluator()))

        val result = search.search("p0", batch(world), 84L)
        assertEquals(1, result.diagnostics.evaluatorCalls)
        assertEquals(0, result.diagnostics.nonQuietLeafEvaluations)
    }

    @Test fun `tree opponent and bounded continuation use native contexts with widening preserving visits`() {
        val world = NativeWorld()
        val leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)
        val search = InformationSetSearch(InformationSetSearchConfig(simulations = 64, maxPolicyDecisions = 8,
            initialExpansionLimit = 2, wideningThresholds = listOf(4), wideningLimits = listOf(4), leaf = leaf),
            NativePolicy, NativePolicy, NativePolicy,
            valueSource = LeafValueSource.Information(testInformationEvaluator()))
        val result = search.search("p0", batch(world), 83L)
        assertEquals(64, result.candidates.sumOf { it.visits })
        assertEquals(4, result.candidates.size)
        assertTrue(result.diagnostics.wideningEvents > 0)
        assertEquals(choices.last(), result.chosen)
        assertNull(world.terminalPayoff("p0"))
    }

    @Test fun `exact sites change under refinement while native planning context retains its lookup`() {
        val world = NativeWorld()
        val first = world.decisionContext(MenuRequest(2))
        val second = world.decisionContext(MenuRequest(4))
        val context = PlanningDecisionContext(first)
        val refined = context.refine(first, second)
        assertEquals(2, refined.addedChoices.size)
        assertEquals(first.site().epistemic.epistemicDigest, second.site().epistemic.epistemicDigest)
        assertNotEquals(first.site().decisionSiteDigest, second.site().decisionSiteDigest)
        context.requireInitial(world.fork().decisionContext(MenuRequest(2)))
        assertEquals(context.key, PlanningDecisionContext(world.fork().decisionContext(MenuRequest(2))).key)
        assertFailsWith<IllegalArgumentException> { MenuWidening.admit(second.menu, first.menu) }
        assertFailsWith<IllegalArgumentException> { MenuWidening.admit(first.menu, second.menu.copy(proposalSeed = 1)) }
        assertFailsWith<IllegalArgumentException> { MenuWidening.admit(first.menu,
            second.menu.copy(candidates = second.menu.candidates.drop(1), estimatedCandidateCount = 3)) }
    }

    @Test fun `repeated initial menus cannot change routing kind behind an unchanged signature`() {
        val first = NativeWorld().decisionContext(MenuRequest(2))
        val changed = first.menu.copy(candidates = first.menu.candidates.mapIndexed { index, choice ->
            if (index == 0) choice.copy(kind = SemanticChoiceKind.DECISION) else choice
        })
        val other = DecisionContext.capture(first.actor, changed, { first.site().epistemic },
            first.view, first.sourceContractIdentity)
        assertEquals(first.menu.candidates.map { it.signature }, other.menu.candidates.map { it.signature })
        assertFailsWith<InformationSetConformanceException> { PlanningDecisionContext(first).requireInitial(other) }
    }

    @Test fun `initial conformance binds completeness and omission provenance`() {
        val first = NativeWorld().decisionContext(MenuRequest(2))
        val variations = listOf(
            first.menu.copy(isExhaustive = true, isProfileExhaustive = true, omissionReasons = emptySet()),
            first.menu.copy(isProfileExhaustive = true,
                omissionReasons = setOf(ActionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA)),
            first.menu.copy(omissionReasons = setOf(ActionOmissionReason.RESPONSE_LIMIT)),
        )
        for (menu in variations) {
            val changed = DecisionContext.capture(first.actor, menu, { first.site().epistemic },
                first.view, first.sourceContractIdentity)
            assertFailsWith<InformationSetConformanceException> { PlanningDecisionContext(first).requireInitial(changed) }
        }
    }

    @Test fun `root population conformance rejects inconsistent completeness before sampling a world`() {
        val ordinary = NativeWorld()
        val contradictory = object : SearchWorld by ordinary {
            override fun decisionContext(view: MenuRequest): DecisionContext {
                val first = ordinary.decisionContext(view)
                return DecisionContext.capture(first.actor,
                    first.menu.copy(isExhaustive = true, isProfileExhaustive = true, omissionReasons = emptySet()),
                    { first.site().epistemic }, first.view, first.sourceContractIdentity)
            }
        }
        val leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)
        val search = InformationSetSearch(InformationSetSearchConfig(simulations = 1, maxPolicyDecisions = 8,
            initialExpansionLimit = 2, leaf = leaf), NativePolicy, NativePolicy, NativePolicy,
            valueSource = LeafValueSource.Information(testInformationEvaluator()))
        val population = batch(ordinary).copy(particles = listOf(Weighted<SearchWorld>(ordinary, .5), Weighted<SearchWorld>(contradictory, .5)))
        assertFailsWith<InformationSetConformanceException> { search.search("p0", population, 83L) }
    }

    private object NativePolicy : OpponentPolicy {
        override val id = "native-only-policy"
        override val requiresArgentumAiChoiceOnMenu = false
        override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
            assertEquals(context.actor, context.site().epistemic.viewerId)
            return ProbabilityDistribution.uniform(context.menu.candidates)
        }
        override fun select(context: DecisionContext, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision =
            OpponentPolicyDecision(context.menu.candidates.first(), OpponentPolicyDecisionDiagnostic(id, id))
        override fun decisionDiagnostic(context: DecisionContext, chosen: SemanticChoice, policySeed: Long, attributionSeed: Long) =
            OpponentPolicyDecisionDiagnostic(id, id)
    }

    private class NativeWorld(private var stage: Int = 0, private var value: Double = 0.0) : ProgressiveSearchWorld {
        override fun actorToAct(): String? = when(stage) { 0 -> "p0"; 1 -> "p1"; else -> null }
        override fun informationState(viewer: String): InformationStateRepresentation {
            if (actorToAct() == viewer) return decisionContext(MenuRequest()).information()
            val state = informationStateWithoutMenu(viewer)
            return InformationStateRepresentation(actingPlayerId = actorToAct(), observation = state.observation,
                informationStateDigest = "native-information-$viewer-$stage-$value",
                historyCommitment = state.historyCommitment, history = emptyList(), knowledge = state.knowledge,
                candidates = emptyList(), terminated = state.terminated)
        }
        override fun expandChoices(): ActionMenu = error("Split compatibility menu used")
        override fun expandChoices(limit: Int): ActionMenu = error("Split compatibility widening used")
        override fun informationStateWithoutMenu(viewer: String): InformationState = InformationState.capture(
            PlayerObservationSnapshot(viewer, 1, "MAIN", "PRECOMBAT_MAIN", "p0", actorToAct(), emptyList(), emptyList(), emptyList(),
                currentTurnStateComplete = true, pendingDecision = null,
                observationDigest = "native-$stage-$value:test-value:$value"),
            emptyList(), HistoryHashChain.empty(), PlayerKnowledge.empty(viewer), stage == 2, null)
        override fun decisionContext(view: MenuRequest): DecisionContext {
            val actor = requireNotNull(actorToAct())
            val state = informationStateWithoutMenu(actor)
            val candidates = if (stage == 0) choices.take(view.limit ?: 2) else listOf(choices.first())
            val complete = stage != 0 || candidates.size == choices.size
            val menu = ActionMenu(candidates, complete, if (stage == 0) 4 else 1, "native-toy-v1")
            return DecisionContext.capture(actor, menu, { state }, view, "native-toy-contract-v1")
        }
        override fun step(choice: SemanticChoice): SearchStepResult {
            require(stage < 2 && choice in choices)
            if (stage == 0) value = if (choice == choices.last()) 1.0 else -1.0
            stage++
            return SearchStepResult(true)
        }
        override fun fork(): SearchWorld = NativeWorld(stage, value)
        override fun terminalPayoff(rootPlayer: String): Double? = value.takeIf { stage == 2 }
    }
    companion object {
        private val choices = (0..3).map { index -> SemanticChoice.create(SemanticChoiceKind.ACTION, SemanticOperationFamily.CAST_SPELL,
            display = SemanticChoiceDisplay("Choice $index"), canonicalPayload = buildJsonObject { put("choice", index) }) }
        private fun batch(world: SearchWorld) = ParticleSet(listOf(Weighted(world, 1.0)),
            BeliefDiagnostics(BeliefMode.CONSISTENCY_ONLY_V1, 1, 1, 0, 1.0, 1.0, 0.0, 0))
    }
}
