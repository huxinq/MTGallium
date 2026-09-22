package org.mtgallium.agent.infoset.core

import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AdmissionBoundaryTest {
    @Test fun `terminal rejects executable omitted selection before transition without payoff`() {
        for (actor in listOf("p0", "p1")) {
            val world = executableOmission(actor)
            val policy = MenuSelector(omitted)
            val trace = TerminalContinuationTraceCollector(TerminalContinuationTracePolicy(4), "p0")
            val failure = assertFailsWith<IllegalArgumentException> {
                TerminalPolicyContinuationRunner(policy, policy, 2)
                    .continueToTerminal(world, "p0", 71L, 0, trace = trace)
            }
            assertTrue(failure.message.orEmpty().contains("non-admitted choice"))
            assertEquals(1, policy.calls)
            assertEquals(0, world.probe.transitions)
            assertEquals(0, world.probe.informationReads)
            assertEquals(TerminalContinuationStop.NON_GAME_FAILURE, trace.result().stop)
            assertNull(trace.result().terminalPayoff)
        }
    }

    @Test fun `bounded rollout rejects executable omitted selection before transition`() {
        for (actor in listOf("p0", "p1")) {
            val world = executableOmission(actor)
            val policy = MenuSelector(omitted)
            val failure = assertFailsWith<IllegalArgumentException> {
                search(policy).settleFirstUnvisitedEdge(world, "p0", 71L, 0)
            }
            assertTrue(failure.message.orEmpty().contains("non-admitted choice"))
            assertEquals(1, policy.calls)
            assertEquals(0, world.probe.transitions)
            assertEquals(0, world.probe.informationReads)
            assertEquals(0, world.probe.leafReads)
        }
    }

    @Test fun `opponent model rejects omitted positive support even when sample would be admitted`() {
        val world = executableOmission("p1")
        val distribution = withOutsideMass(Double.MIN_VALUE)
        val sampleSeed = ComponentSeeds.derive(71L, 0, 0, "opponent-sample")
        assertEquals(admitted, sampleOpponentPolicyDistribution(distribution, sampleSeed))
        val model = MenuModel(distribution)
        val failure = assertFailsWith<IllegalArgumentException> {
            search(MenuSelector(admitted), model).search("p0", BeliefBatch(listOf(Weighted<SearchWorld>(world, 1.0)),
                BeliefDiagnostics(BeliefMode.CONSISTENCY_ONLY_V1, 1, 1, 0, 1.0, 1.0, 0.0, 0)), 71L)
        }
        assertTrue(failure.message.orEmpty().contains("positive probability"))
        assertEquals(1, model.calls)
        assertEquals(0, world.probe.transitions)
        assertEquals(0, world.probe.informationReads)
        assertEquals(0, world.probe.leafReads)
    }

    @Test fun `valid menu only terminal and bounded choices stay lazy with unchanged decision seeds`() {
        for (actor in listOf("p0", "p1")) {
            val terminalWorld = World(actor)
            val terminalPolicy = MenuSelector(admitted)
            val terminal = TerminalPolicyContinuationRunner(terminalPolicy, terminalPolicy, 2)
                .continueToTerminal(terminalWorld, "p0", 71L, 0)
            assertEquals(0.75, terminal.payoff)
            assertEquals(1, terminal.policyDecisions)
            val boundedWorld = World(actor)
            val boundedPolicy = MenuSelector(admitted)
            val bounded = search(boundedPolicy).settleFirstUnvisitedEdge(boundedWorld, "p0", 71L, 0)
            assertEquals(SearchSettlementOrigin.TERMINAL_PAYOFF, bounded.origin)
            assertEquals(0.75, bounded.backedValue)
            val expectedSeeds = listOf(ComponentSeeds.derive(71L, 0, 1, terminalPolicy.id, "rollout") to
                ComponentSeeds.derive(71L, 0, 1, "rollout-sample"))
            assertEquals(expectedSeeds, terminalPolicy.seeds)
            assertEquals(expectedSeeds, boundedPolicy.seeds)
            for (world in listOf(terminalWorld, boundedWorld)) {
                assertEquals(1, world.probe.transitions)
                assertEquals(0, world.probe.informationReads)
                assertEquals(0, world.probe.leafReads)
                assertNull(world.terminalPayoff("p0")) // Continuations mutate only their fork.
            }
        }
    }

    @Test fun `zero probability omitted entry is retained and allowed but cannot be selected`() {
        val distribution = withOutsideMass(0.0)
        val originalEntries = distribution.entries.toList()
        assertSame(distribution, distribution.requireAdmittedSupport(menu))
        assertEquals(originalEntries, distribution.entries)
        assertEquals(0.0, distribution.entries.last().probability)
        assertFailsWith<IllegalArgumentException> { omitted.requireAdmittedChoice(menu) }
        val world = executableOmission("p1")
        val model = MenuModel(distribution)
        val produced = model.distribution(world.decisionContext(DecisionView(2)), 71L).requireAdmittedSupport(menu)
        assertSame(distribution, produced)
        val selected = sampleOpponentPolicyDistribution(produced, 71L).requireAdmittedChoice(menu)
        assertEquals(admitted, selected)
        assertTrue(world.step(selected).accepted)
        assertEquals(1, model.calls)
        assertEquals(1, world.probe.transitions)
        assertEquals(0, world.probe.informationReads)
        assertEquals(0, world.probe.leafReads)
    }

    @Test fun `admission checks exact choice rather than signature alone`() {
        val changed = admitted.copy(kind = SemanticChoiceKind.DECISION)
        assertEquals(admitted.signature, changed.signature)
        assertFailsWith<IllegalArgumentException> { changed.requireAdmittedChoice(menu) }
        assertFailsWith<IllegalArgumentException> {
            ProbabilityDistribution.uniform(listOf(changed)).requireAdmittedSupport(menu)
        }
        assertSame(admitted, admitted.requireAdmittedChoice(menu))
    }

    private fun search(selector: ActionSelector, model: ActionDistributionModel = MenuModel(
        ProbabilityDistribution.uniform(menu))): InformationSetSearch {
        val leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT)
        return InformationSetSearch(InformationSetSearchConfig(simulations = 1, maxPolicyDecisions = 4,
            initialExpansionLimit = 2, leaf = leaf), model, selector, selector,
            LeafValueSource.SampledWorld("argentum-board-v1"))
    }

    /** The control executes the omitted choice; rejection cannot be attributed to engine legality. */
    private fun executableOmission(actor: String): World {
        val control = World(actor)
        assertFalse(omitted in control.decisionContext(DecisionView(2)).expansion.candidates)
        assertTrue(control.step(omitted).accepted)
        assertEquals(1, control.probe.transitions)
        assertEquals(0.75, control.terminalPayoff("p0"))
        return World(actor)
    }

    private class MenuSelector(private val selected: SemanticChoice) : ActionSelector {
        override val id = "admission-menu-selector"
        var calls = 0
        val seeds = mutableListOf<Pair<Long, Long>>()
        override fun select(context: DecisionSiteRequest, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
            assertEquals(menu, context.expansion.candidates)
            calls++
            seeds += policySeed to sampleSeed
            return OpponentPolicyDecision(selected, OpponentPolicyDecisionDiagnostic(id, id))
        }
    }

    private class MenuModel(private val result: ProbabilityDistribution<SemanticChoice>) : ActionDistributionModel {
        override val id = "admission-menu-model"
        override val distributionIsSeedInvariant = true
        var calls = 0
        override fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
            assertEquals(menu, context.expansion.candidates)
            calls++
            return result
        }
        override fun decisionDiagnostic(context: DecisionSiteRequest, chosen: SemanticChoice, policySeed: Long,
            attributionSeed: Long) = OpponentPolicyDecisionDiagnostic(id, id)
    }

    private class Probe {
        var transitions = 0
        // Acting-player policy projection; the observer-only search preflight is separate.
        var informationReads = 0
        var leafReads = 0
    }

    private class World(private val actor: String, val probe: Probe = Probe(), private var done: Boolean = false) : SearchWorld {
        override fun actorToAct(): String? = actor.takeUnless { done }
        override fun decisionContext(view: DecisionView): DecisionSiteRequest = DecisionSiteRequest.capture(
            requireNotNull(actorToAct()), PolicyExpansion(menu, false, 2, "admission-boundary-test-v1"),
            { epistemicState(actor) }, view, "admission-boundary-test-v1")
        override fun epistemicState(viewer: String): EpistemicState {
            probe.informationReads++
            error("Menu-only boundary must not project information")
        }
        override fun informationState(viewer: String): InformationStateRepresentation {
            // Search may inspect the non-acting root's view before sampling its opponent.
            // Acting-player projection remains forbidden, including from the model context.
            if (viewer != actor) return InformationStateRepresentation(
                actingPlayerId = actorToAct(),
                observation = PlayerObservationSnapshot(viewer, 1, "TEST", "PRIORITY", actor, actorToAct(),
                    emptyList(), emptyList(), emptyList(), pendingDecision = null,
                    observationDigest = "admission-observer-$viewer-$done"),
                informationStateDigest = "admission-observer-information-$viewer-$done",
                historyCommitment = PolicyHistoryCommitment.empty(), history = emptyList(),
                candidates = emptyList(), terminated = done)
            probe.informationReads++
            error("Menu-only boundary must not project compatibility information")
        }
        override fun expandChoices(): PolicyExpansion = error("Use the native admitted context")
        override fun step(choice: SemanticChoice): SearchStepResult {
            probe.transitions++ // Count attempts too, shared across every continuation fork.
            require(!done && choice in listOf(admitted, omitted))
            done = true
            return SearchStepResult(true)
        }
        override fun fork(): SearchWorld = World(actor, probe, done)
        override fun terminalPayoff(rootPlayer: String): Double? = 0.75.takeIf { done }
        override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double {
            probe.leafReads++
            error("Admission failure must not become a leaf value")
        }
    }

    companion object {
        private fun choice(label: String) = SemanticChoice.create(SemanticChoiceKind.ACTION,
            SemanticOperationFamily.CAST_SPELL, display = SemanticChoiceDisplay(label),
            canonicalPayload = buildJsonObject { put("choice", label) })
        private val admitted = choice("admitted")
        private val omitted = choice("executable-but-omitted")
        private val menu = listOf(admitted)
        private fun withOutsideMass(mass: Double) = ProbabilityDistribution.normalized(listOf(
            ProbabilityMass(admitted, 1.0), ProbabilityMass(omitted, mass)))
    }
}
