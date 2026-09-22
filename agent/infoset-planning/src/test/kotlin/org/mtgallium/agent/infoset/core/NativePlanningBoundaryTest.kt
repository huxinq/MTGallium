package org.mtgallium.agent.infoset.core

import kotlin.test.*
import kotlinx.serialization.json.*

class NativePlanningBoundaryTest {
    @Test fun `tree opponent and bounded continuation use native contexts with widening preserving visits`() {
        val world = NativeWorld()
        val leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT)
        val search = InformationSetSearch(InformationSetSearchConfig(simulations = 64, maxPolicyDecisions = 8,
            initialExpansionLimit = 2, wideningThresholds = listOf(4), wideningLimits = listOf(4), leaf = leaf),
            NativePolicy, NativePolicy, NativePolicy,
            valueSource = LeafValueSource.SampledWorld("argentum-board-v1"))
        val result = search.search("p0", batch(world), 83L)
        assertEquals(64, result.candidates.sumOf { it.visits })
        assertEquals(4, result.candidates.size)
        assertTrue(result.diagnostics.wideningEvents > 0)
        assertEquals(choices.last(), result.chosen)
        assertNull(world.terminalPayoff("p0"))
    }

    @Test fun `terminal continuation and model selection never assemble the old information expansion pair`() {
        val world = NativeWorld(stage = 1, value = 1.0)
        val result = TerminalPolicyContinuationRunner(NativePolicy, NativePolicy, 4).continueToTerminal(world, "p0", 42L, 0)
        assertEquals(1.0, result.payoff)
        assertEquals(1, result.policyDecisions)
        assertNull(world.terminalPayoff("p0"))
    }

    @Test fun `exact sites change under refinement while native planning context retains its lookup`() {
        val world = NativeWorld()
        val first = world.decisionContext(DecisionView(2))
        val second = world.decisionContext(DecisionView(4))
        val context = PlanningDecisionContext(first)
        val refined = context.refine(first, second)
        assertEquals(2, refined.addedChoices.size)
        assertEquals(first.site().epistemic.epistemicDigest, second.site().epistemic.epistemicDigest)
        assertNotEquals(first.site().decisionSiteDigest, second.site().decisionSiteDigest)
        context.requireInitial(world.fork().decisionContext(DecisionView(2)))
        assertEquals(context.key, PlanningDecisionContext(world.fork().decisionContext(DecisionView(2))).key)
        assertFailsWith<IllegalArgumentException> { AdmittedMenuRefinement.admit(second.expansion, first.expansion) }
        assertFailsWith<IllegalArgumentException> { AdmittedMenuRefinement.admit(first.expansion, second.expansion.copy(proposalSeed = 1)) }
        assertFailsWith<IllegalArgumentException> { AdmittedMenuRefinement.admit(first.expansion,
            second.expansion.copy(candidates = second.expansion.candidates.drop(1), estimatedCandidateCount = 3)) }
    }

    @Test fun `repeated initial menus cannot change routing kind behind an unchanged signature`() {
        val first = NativeWorld().decisionContext(DecisionView(2))
        val changed = first.expansion.copy(candidates = first.expansion.candidates.mapIndexed { index, choice ->
            if (index == 0) choice.copy(kind = SemanticChoiceKind.DECISION) else choice
        })
        val other = DecisionSiteRequest.capture(first.actor, changed, { first.site().epistemic },
            first.view, first.sourceContractIdentity)
        assertEquals(first.expansion.candidates.map { it.signature }, other.expansion.candidates.map { it.signature })
        assertFailsWith<InformationSetConformanceException> { PlanningDecisionContext(first).requireInitial(other) }
    }

    @Test fun `initial conformance binds completeness and omission provenance`() {
        val first = NativeWorld().decisionContext(DecisionView(2))
        val variations = listOf(
            first.expansion.copy(isExhaustive = true, isProfileExhaustive = true, omissionReasons = emptySet()),
            first.expansion.copy(isProfileExhaustive = true,
                omissionReasons = setOf(PolicyExpansionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA)),
            first.expansion.copy(omissionReasons = setOf(PolicyExpansionOmissionReason.RESPONSE_LIMIT)),
        )
        for (expansion in variations) {
            val changed = DecisionSiteRequest.capture(first.actor, expansion, { first.site().epistemic },
                first.view, first.sourceContractIdentity)
            assertFailsWith<InformationSetConformanceException> { PlanningDecisionContext(first).requireInitial(changed) }
        }
    }

    @Test fun `root population conformance rejects inconsistent completeness before sampling a world`() {
        val ordinary = NativeWorld()
        val contradictory = object : SearchWorld by ordinary {
            override fun decisionContext(view: DecisionView): DecisionSiteRequest {
                val first = ordinary.decisionContext(view)
                return DecisionSiteRequest.capture(first.actor,
                    first.expansion.copy(isExhaustive = true, isProfileExhaustive = true, omissionReasons = emptySet()),
                    { first.site().epistemic }, first.view, first.sourceContractIdentity)
            }
        }
        val leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT)
        val search = InformationSetSearch(InformationSetSearchConfig(simulations = 1, maxPolicyDecisions = 8,
            initialExpansionLimit = 2, leaf = leaf), NativePolicy, NativePolicy, NativePolicy,
            valueSource = LeafValueSource.SampledWorld("argentum-board-v1"))
        val population = batch(ordinary).copy(particles = listOf(Weighted<SearchWorld>(ordinary, .5), Weighted<SearchWorld>(contradictory, .5)))
        assertFailsWith<InformationSetConformanceException> { search.search("p0", population, 83L) }
    }

    private object NativePolicy : OpponentPolicy {
        override val id = "native-only-policy"
        override val requiresProductionAdmission = false
        override fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
            assertEquals(context.actor, context.site().epistemic.perspectivePlayerId)
            return ProbabilityDistribution.uniform(context.expansion.candidates)
        }
        override fun select(context: DecisionSiteRequest, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision =
            OpponentPolicyDecision(context.expansion.candidates.first(), OpponentPolicyDecisionDiagnostic(id, id))
        override fun decisionDiagnostic(context: DecisionSiteRequest, chosen: SemanticChoice, policySeed: Long, attributionSeed: Long) =
            OpponentPolicyDecisionDiagnostic(id, id)
    }

    private class NativeWorld(private var stage: Int = 0, private var value: Double = 0.0) : ProgressiveSearchWorld {
        override fun actorToAct(): String? = when(stage) { 0 -> "p0"; 1 -> "p1"; else -> null }
        override fun informationState(viewer: String): InformationStateRepresentation = error("Split compatibility information used")
        override fun expandChoices(): PolicyExpansion = error("Split compatibility menu used")
        override fun expandChoices(limit: Int): PolicyExpansion = error("Split compatibility widening used")
        override fun epistemicState(viewer: String): EpistemicState = EpistemicState.capture(
            PlayerObservationSnapshot(viewer, 1, "MAIN", "PRECOMBAT_MAIN", "p0", actorToAct(), emptyList(), emptyList(), emptyList(),
                currentTurnStateComplete = true, pendingDecision = null, observationDigest = "native-$stage-$value"),
            emptyList(), PolicyHistoryCommitment.empty(), PolicyKnowledgeState.empty(viewer), stage == 2, null)
        override fun decisionContext(view: DecisionView): DecisionSiteRequest {
            val actor = requireNotNull(actorToAct())
            val state = epistemicState(actor)
            val menu = if (stage == 0) choices.take(view.limit ?: 2) else listOf(choices.first())
            val complete = stage != 0 || menu.size == choices.size
            val expansion = PolicyExpansion(menu, complete, if (stage == 0) 4 else 1, "native-toy-v1")
            return DecisionSiteRequest.capture(actor, expansion, { state }, view, "native-toy-contract-v1")
        }
        override fun step(choice: SemanticChoice): SearchStepResult {
            require(stage < 2 && choice in choices)
            if (stage == 0) value = if (choice == choices.last()) 1.0 else -1.0
            stage++
            return SearchStepResult(true)
        }
        override fun fork(): SearchWorld = NativeWorld(stage, value)
        override fun terminalPayoff(rootPlayer: String): Double? = value.takeIf { stage == 2 }
        override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String) = value
    }
    companion object {
        private val choices = (0..3).map { index -> SemanticChoice.create(SemanticChoiceKind.ACTION, SemanticOperationFamily.CAST_SPELL,
            display = SemanticChoiceDisplay("Choice $index"), canonicalPayload = buildJsonObject { put("choice", index) }) }
        private fun batch(world: SearchWorld) = BeliefBatch(listOf(Weighted(world, 1.0)),
            BeliefDiagnostics(BeliefMode.CONSISTENCY_ONLY_V1, 1, 1, 0, 1.0, 1.0, 0.0, 0))
    }
}
