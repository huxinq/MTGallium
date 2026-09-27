package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AdmissionBoundaryTest {
    @Test fun `bounded rollout rejects executable omitted selection before transition`() {
        for (actor in listOf("p0", "p1")) {
            val world = executableOmission(actor)
            val policy = MenuSelector(omitted)
            val failure = assertFailsWith<IllegalArgumentException> {
                search(policy).search("p0", belief(world.precededByRoot()), 71L)
            }
            assertTrue(failure.message.orEmpty().contains("non-admitted choice"))
            assertEquals(1, policy.calls)
            assertEquals(0, world.probe.transitions)
            assertEquals(0, world.probe.informationReads)
        }
    }

    @Test fun `opponent model rejects omitted positive support even when sample would be admitted`() {
        val world = executableOmission("p1")
        val distribution = withOutsideMass(Double.MIN_VALUE)
        val sampleSeed = ComponentSeeds.derive(71L, 0, 0, "opponent-sample")
        assertEquals(admitted, sampleOpponentPolicyDistribution(distribution, sampleSeed))
        val model = MenuModel(distribution)
        val failure = assertFailsWith<IllegalArgumentException> {
            search(MenuSelector(admitted), model).search("p0", ParticleSet(listOf(Weighted<SearchWorld>(world, 1.0)),
                BeliefDiagnostics(BeliefMode.CONSISTENCY_ONLY_V1, 1, 1, 0, 1.0, 1.0, 0.0, 0)), 71L)
        }
        assertTrue(failure.message.orEmpty().contains("positive probability"))
        assertEquals(1, model.calls)
        assertEquals(0, world.probe.transitions)
        assertEquals(0, world.probe.informationReads)
    }

    @Test fun `valid menu only bounded choices stay lazy with unchanged decision seeds`() {
        for (actor in listOf("p0", "p1")) {
            val boundedWorld = World(actor)
            val boundedPolicy = MenuSelector(admitted)
            val bounded = search(boundedPolicy).search("p0", belief(boundedWorld.precededByRoot()), 71L)
            assertEquals(1, bounded.candidateSettlementCounts.values.sumOf { it.terminalPayoffBackups })
            assertEquals(0.75, bounded.rootValue)
            val expectedSeeds = listOf(ComponentSeeds.derive(71L, 0, 1, boundedPolicy.id, "rollout") to
                ComponentSeeds.derive(71L, 0, 1, "rollout-sample"))
            assertEquals(expectedSeeds, boundedPolicy.seeds)
            for (world in listOf(boundedWorld)) {
                assertEquals(1, world.probe.transitions)
                assertEquals(0, world.probe.informationReads)
                assertNull(world.terminalPayoff("p0")) // Search mutates only its fork.
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
        val produced = model.distribution(world.decisionContext(MenuRequest(2)), 71L).requireAdmittedSupport(menu)
        assertSame(distribution, produced)
        val selected = sampleOpponentPolicyDistribution(produced, 71L).requireAdmittedChoice(menu)
        assertEquals(admitted, selected)
        assertTrue(world.step(selected).accepted)
        assertEquals(1, model.calls)
        assertEquals(1, world.probe.transitions)
        assertEquals(0, world.probe.informationReads)
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
        val leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)
        return InformationSetSearch(InformationSetSearchConfig(simulations = 1, maxPolicyDecisions = 4,
            initialExpansionLimit = 2, leaf = leaf), model, selector, selector,
            LeafValueSource.Information(object : InformationStateEvaluator {
                override val id = "admission-boundary-leaf-guard"
                override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double =
                    error("Admission failure must not become a leaf value")
            }))
    }

    private fun belief(world: SearchWorld) = ParticleSet(listOf(Weighted(world, 1.0)),
        BeliefDiagnostics(mode = BeliefMode.CONSISTENCY_ONLY_V1, requestedParticles = 1,
            acceptedParticles = 1, rejectedParticles = 0, effectiveSampleSizeBefore = 1.0,
            effectiveSampleSizeAfter = 1.0, entropy = 0.0, resamplingCount = 0))

    /** The control executes the omitted choice; rejection cannot be attributed to engine legality. */
    private fun executableOmission(actor: String): World {
        val control = World(actor)
        assertFalse(omitted in control.decisionContext(MenuRequest(2)).menu.candidates)
        assertTrue(control.step(omitted).accepted)
        assertEquals(1, control.probe.transitions)
        assertEquals(0.75, control.terminalPayoff("p0"))
        return World(actor)
    }

    private class MenuSelector(private val selected: SemanticChoice) : ActionSelector {
        override val id = "admission-menu-selector"
        var calls = 0
        val seeds = mutableListOf<Pair<Long, Long>>()
        override fun select(context: DecisionContext, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
            assertEquals(menu, context.menu.candidates)
            calls++
            seeds += policySeed to sampleSeed
            return OpponentPolicyDecision(selected, OpponentPolicyDecisionDiagnostic(id, id))
        }
    }

    private class MenuModel(private val result: ProbabilityDistribution<SemanticChoice>) : ActionDistributionModel {
        override val id = "admission-menu-model"
        override val distributionIsSeedInvariant = true
        var calls = 0
        override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
            assertEquals(menu, context.menu.candidates)
            calls++
            return result
        }
        override fun decisionDiagnostic(context: DecisionContext, chosen: SemanticChoice, policySeed: Long,
            attributionSeed: Long) = OpponentPolicyDecisionDiagnostic(id, id)
    }

    private class Probe {
        var transitions = 0
        // Acting-player policy projection; the observer-only search preflight is separate.
        var informationReads = 0
    }

    private class World(private val actor: String, val probe: Probe = Probe(), private var done: Boolean = false,
        private var atRoot: Boolean = false) : SearchWorld {
        fun precededByRoot(): World = World(actor, probe, done, atRoot = true)
        override fun actorToAct(): String? = if (atRoot) "p0" else actor.takeUnless { done }
        override fun decisionContext(view: MenuRequest): DecisionContext = DecisionContext.capture(
            requireNotNull(actorToAct()), ActionMenu(menu, false, 2, "admission-boundary-test-v1"),
            { if (atRoot) InformationState.capture(informationState("p0")) else informationStateWithoutMenu(actor) }, view, "admission-boundary-test-v1")
        override fun informationStateWithoutMenu(viewer: String): InformationState {
            if (atRoot) return InformationState.capture(informationState(viewer))
            probe.informationReads++
            error("Menu-only boundary must not project information")
        }
        override fun informationState(viewer: String): InformationStateRepresentation {
            // Search may inspect the non-acting root's view before sampling its opponent.
            // Acting-player projection remains forbidden, including from the model context.
            if (atRoot || viewer != actor) return InformationStateRepresentation(
                actingPlayerId = actorToAct(),
                observation = PlayerObservationSnapshot(viewer, 1, "TEST", "PRIORITY", actor, actorToAct(),
                    emptyList(), emptyList(), emptyList(), pendingDecision = null,
                    observationDigest = "admission-observer-$viewer-$done-$atRoot"),
                informationStateDigest = "admission-observer-information-$viewer-$done-$atRoot",
                historyCommitment = HistoryHashChain.empty(), history = emptyList(),
                candidates = if (atRoot) menu else emptyList(), terminated = done)
            probe.informationReads++
            error("Menu-only boundary must not project compatibility information")
        }
        override fun expandChoices(): ActionMenu = error("Use the native admitted context")
        override fun step(choice: SemanticChoice): SearchStepResult {
            if (atRoot) {
                require(choice == admitted)
                atRoot = false
                return SearchStepResult(true)
            }
            probe.transitions++ // Count attempts after the synthetic root edge, shared across forks.
            require(!done && choice in listOf(admitted, omitted))
            done = true
            return SearchStepResult(true)
        }
        override fun fork(): SearchWorld = World(actor, probe, done, atRoot)
        override fun terminalPayoff(rootPlayer: String): Double? = 0.75.takeIf { done }
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
