package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class RolloutPolicyAnnotationRequirementTest {
    @Test
    fun `selector only policies drive terminal bounded rollout and refresh with admitted menus`() {
        val probe = ExpansionProbe()
        val seeds = mutableListOf<Pair<Long, Long>>()
        val selector = object : ActionSelector {
            override val id = "selector-only"
            override fun select(context: DecisionSiteRequest, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision = select(context.information(), context.expansion.candidates, policySeed, sampleSeed)

    fun select(opponentInformation: InformationStateRepresentation, candidates: List<SemanticChoice>,
                policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
                assertEquals(opponentInformation.actingPlayerId, opponentInformation.observation.perspectivePlayerId)
                assertEquals("admitted", candidates.first().display.label)
                seeds += policySeed to sampleSeed
                return OpponentPolicyDecision(candidates.first(), OpponentPolicyDecisionDiagnostic(id, id))
            }
        }
        val terminal = TerminalPolicyContinuationRunner(selector, selector, 16)
            .continueToTerminal(PolicyAdmissionWorld(probe, 1), "p0", 77L, 0)
        assertEquals(3, terminal.policyDecisions)
        assertEquals((1..3).map { depth ->
            ComponentSeeds.derive(77L, 0, depth, selector.id, "rollout") to
                ComponentSeeds.derive(77L, 0, depth, "rollout-sample")
        }, seeds)
        seeds.clear()
        val search = InformationSetSearch(
            InformationSetSearchConfig(simulations = 8, maxPolicyDecisions = 8,
                leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT)),
            UniformOpponentPolicy, selector, selector,
            valueSource = LeafValueSource.SampledWorld("argentum-board-v1"),
        )
        search.search("p0", belief(PolicyAdmissionWorld(probe)), 771L)
        assertTrue(seeds.isNotEmpty())
        assertTrue(probe.admissionCalls > 0)
        assertEquals(0, probe.annotationCalls)
    }

    @Test
    fun `policy quiescence respects each rollout policy admission requirement`() {
        for (admission in listOf(false, true)) {
            val probe = ExpansionProbe()
            val root = PerspectiveRecordingPolicy("quiescent-root", admission)
            val opponent = PerspectiveRecordingPolicy("quiescent-opponent", admission)
            val evaluator = object : InformationStateEvaluator {
                override val id = "mono-red-tactical-value-v3"
                override fun evaluate(information: InformationStateRepresentation, rootPlayer: String) = 0.0
            }
            val search = InformationSetSearch(
                InformationSetSearchConfig(simulations = 1, maxPolicyDecisions = 1,
                    leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, RolloutCutoff.POLICY_QUIESCENCE)),
                UniformOpponentPolicy, root, opponent,
                valueSource = LeafValueSource.Information(evaluator),
            )
            val result = search.settleFirstUnvisitedEdge(PolicyAdmissionWorld(probe, 1, volatile = true), "p0", 77L, 0)
            assertEquals(SearchSettlementOrigin.TERMINAL_PAYOFF, result.origin)
            assertEquals(0, probe.annotationCalls)
            assertEquals(if (admission) 3 else 0, probe.admissionCalls)
            assertEquals(List(3) { if (admission) "admitted" else "base-a" }, probe.acceptedLabels)
            assertTrue(root.calls > 0 && opponent.calls > 0)
            (root.perspectives + opponent.perspectives).forEach { assertEquals(it.actor, it.viewer) }
        }
    }

    @Test
    fun `menu-only rollout selection preserves seeds decisions and diagnostics while avoiding information reads`() {
        data class Run(val result: InformationSetSearchResult, val terminal: TerminalPolicyContinuation,
            val probe: ExpansionProbe, val root: MenuSelectionProbePolicy, val opponent: MenuSelectionProbePolicy)
        fun run(enabled: Boolean): Run {
            val probe = ExpansionProbe()
            val root = MenuSelectionProbePolicy("root-menu", enabled)
            val opponent = MenuSelectionProbePolicy("opponent-menu", enabled)
            val config = InformationSetSearchConfig(simulations = 8, maxPolicyDecisions = 8,
                leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT))
            val search = InformationSetSearch(config, UniformOpponentPolicy, root, opponent,
                valueSource = LeafValueSource.SampledWorld("argentum-board-v1"))
            val result = search.search("p0", belief(PolicyAdmissionWorld(probe)), 771L)
            val terminal = search.continueFirstUnvisitedEdgeToTerminal(PolicyAdmissionWorld(probe, 1), "p0", 77L, 0)
            return Run(result, terminal, probe, root, opponent)
        }
        val baseline = run(false)
        val optimized = run(true)
        // Deferred information can require a later derived-cache snapshot when a rollout prefix
        // enters the tree. Only that work counter and evaluator timing may differ.
        assertEquals(baseline.result.copy(diagnostics = baseline.result.diagnostics.copy(
            evaluatorNanos = 0, transitionCacheDerivedSnapshots = 0)),
            optimized.result.copy(diagnostics = optimized.result.diagnostics.copy(
                evaluatorNanos = 0, transitionCacheDerivedSnapshots = 0)))
        assertEquals(baseline.terminal, optimized.terminal)
        assertEquals(baseline.probe.acceptedLabels, optimized.probe.acceptedLabels)
        assertEquals(baseline.root.seeds, optimized.root.seeds)
        assertEquals(baseline.opponent.seeds, optimized.opponent.seeds)
        assertTrue(optimized.root.menuCalls > 0)
        assertTrue(optimized.opponent.menuCalls > 0)
        assertEquals(baseline.probe.informationCalls - optimized.root.menuCalls - optimized.opponent.menuCalls,
            optimized.probe.informationCalls)
    }

    @Test
    fun `zero-weight annotation consumers are not called to build the distribution`() {
        val world = PolicyAdmissionWorld(ExpansionProbe())
        val mixture = zeroWeightMixture()
        val context = world.decisionContext(mixture.decisionView())
        val candidates = context.expansion.candidates

        assertFalse(mixture.requiresPolicyAnnotations)
        val distribution = mixture.distribution(context, 71L)

        assertEquals(candidates.map { 0.5 }, distribution.entries.map { it.probability })
        assertTrue(candidates.all { it.display.policyTags.isEmpty() })
    }

    @Test
    fun `zero-weight annotation consumers are not called for posterior attribution`() {
        val world = PolicyAdmissionWorld(ExpansionProbe())
        val mixture = zeroWeightMixture()
        val context = world.decisionContext(mixture.decisionView())
        val candidates = context.expansion.candidates

        val diagnostic = mixture.decisionDiagnostic(
            context, candidates.first(), 71L, 72L,
        )

        assertEquals(UniformOpponentPolicy.id, diagnostic.selectedComponentId)
        assertEquals(UniformOpponentPolicy.id, diagnostic.effectivePolicyId)
    }

    @Test
    fun `inactive components retain original indices in active policy and attribution seeds`() {
        val world = PolicyAdmissionWorld(ExpansionProbe())
        val first = SeedRecordingMixturePolicy("first")
        val second = SeedRecordingMixturePolicy("second")
        val mixture = MixtureOpponentPolicy("interleaved-mixture", listOf(
            OpponentPolicyMixtureEntry(InactiveAnnotationPolicy, 0.0),
            OpponentPolicyMixtureEntry(first, 1.0),
            OpponentPolicyMixtureEntry(InactiveAnnotationPolicy, 0.0),
            OpponentPolicyMixtureEntry(second, 2.0),
        ))
        val policySeed = 71L
        val attributionSeed = 72L
        val context = world.decisionContext(mixture.decisionView())
        val candidates = context.expansion.candidates

        mixture.distribution(context, policySeed)
        val diagnostic = mixture.decisionDiagnostic(
            context, candidates.first(), policySeed, attributionSeed,
        )

        for ((index, policy) in listOf(1 to first, 3 to second)) {
            val expectedSeed = ComponentSeeds.derive(policySeed, index, policy.id)
            assertEquals(listOf(expectedSeed, expectedSeed), policy.distributionSeeds)
            val expectedDiagnostics = if (diagnostic.selectedComponentId == policy.id) listOf(
                expectedSeed to ComponentSeeds.derive(attributionSeed, index, policy.id, "nested-component-attribution")
            ) else emptyList()
            assertEquals(expectedDiagnostics, policy.diagnosticSeeds)
        }
    }

    private fun zeroWeightMixture() = MixtureOpponentPolicy("zero-weight-mixture", listOf(
        OpponentPolicyMixtureEntry(InactiveAnnotationPolicy, 0.0),
        OpponentPolicyMixtureEntry(UniformOpponentPolicy, 1.0),
    ))

    @Test
    fun `annotation-free rollout policies use policy admission without materializing annotations`() {
        val probe = ExpansionProbe()
        val rootRollout = PerspectiveRecordingPolicy("root-rollout-no-annotations")
        val opponentRollout = PerspectiveRecordingPolicy("opponent-rollout-no-annotations")
        val treeOpponent = PerspectiveRecordingPolicy("tree-opponent-no-annotations")
        val evaluator = object : InformationStateEvaluator {
            override val id = "mono-red-visible-board-v2"
            override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double = 0.0
        }
        val search = InformationSetSearch(
            config = InformationSetSearchConfig(
                simulations = 8,
                maxPolicyDecisions = 8,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.BOUNDED_ROLLOUT,
                    ),
            ),
            opponentPolicy = treeOpponent,
            rolloutPolicy = rootRollout,
            rolloutOpponentPolicy = opponentRollout,
            valueSource = LeafValueSource.Information(evaluator),
        )

        val result = search.search("p0", belief(PolicyAdmissionWorld(probe)), 771L)

        assertEquals(0, probe.annotationCalls)
        assertTrue(probe.admissionCalls > 0)
        assertTrue(probe.acceptedLabels.any { it == "admitted" })
        assertTrue(rootRollout.calls > 0)
        assertTrue(opponentRollout.calls > 0)
        assertTrue(treeOpponent.calls > 0)
        assertEquals(treeOpponent.id, result.diagnostics.opponentModelId)
        assertEquals(rootRollout.id, result.diagnostics.rootRolloutPolicyId)
        assertEquals(opponentRollout.id, result.diagnostics.opponentRolloutPolicyId)
        assertEquals(0, result.diagnostics.policyAnnotatedExpansions)
        (rootRollout.perspectives + opponentRollout.perspectives + treeOpponent.perspectives).forEach { witness ->
            assertEquals(witness.actor, witness.viewer)
            assertTrue(witness.candidateLabels.contains("admitted"))
        }
    }

    @Test
    fun `plain-menu policies omit production admission in tree and both rollouts`() {
        val probe = ExpansionProbe()
        val rootRollout = PerspectiveRecordingPolicy("root-plain", false)
        val opponentRollout = PerspectiveRecordingPolicy("opponent-plain", false)
        val treeOpponent = PerspectiveRecordingPolicy("tree-plain", false)
        val evaluator = object : InformationStateEvaluator {
            override val id = "mono-red-visible-board-v2"
            override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double = 0.0
        }
        val search = InformationSetSearch(
            config = InformationSetSearchConfig(
                simulations = 8,
                maxPolicyDecisions = 8,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.BOUNDED_ROLLOUT,
                    ),
            ),
            opponentPolicy = treeOpponent,
            rolloutPolicy = rootRollout,
            rolloutOpponentPolicy = opponentRollout,
            valueSource = LeafValueSource.Information(evaluator),
        )

        val result = search.search("p0", belief(PolicyAdmissionWorld(probe)), 771L)

        search.settleFirstUnvisitedEdge(PolicyAdmissionWorld(probe, 1), "p0", 77L, 0)
        search.continueFirstUnvisitedEdgeToTerminal(PolicyAdmissionWorld(probe, 1), "p0", 77L, 0)

        assertEquals(0, probe.annotationCalls)
        assertEquals(0, probe.admissionCalls)
        assertTrue(probe.acceptedLabels.all { it.startsWith("base-") })
        assertTrue(rootRollout.calls > 0)
        assertTrue(opponentRollout.calls > 0)
        assertTrue(treeOpponent.calls > 0)
        assertEquals(treeOpponent.id, result.diagnostics.opponentModelId)
        assertEquals(rootRollout.id, result.diagnostics.rootRolloutPolicyId)
        assertEquals(opponentRollout.id, result.diagnostics.opponentRolloutPolicyId)
        assertEquals(0, result.diagnostics.policyAnnotatedExpansions)
        (rootRollout.perspectives + opponentRollout.perspectives + treeOpponent.perspectives).forEach { witness ->
            assertEquals(witness.actor, witness.viewer)
            assertFalse(witness.candidateLabels.contains("admitted"))
        }
    }

    @Test
    fun `policy admission preserves the annotated candidate identity while omitting tags`() {
        val probe = ExpansionProbe()
        val world = PolicyAdmissionWorld(probe)
        val admitted = world.expandChoicesForPolicyAdmission()
        val annotated = world.expandChoicesWithPolicyAnnotations()

        assertEquals(admitted.candidates.map { it.signature }, annotated.candidates.map { it.signature })
        assertEquals(admitted.candidates.map { it.canonicalPayload }, annotated.candidates.map { it.canonicalPayload })
        assertTrue(admitted.candidates.none { "test-policy-annotation" in it.display.policyTags })
        assertEquals(1, annotated.candidates.count { "test-policy-annotation" in it.display.policyTags })
        assertEquals(1, probe.admissionCalls)
        assertEquals(1, probe.annotationCalls)
        admitted.candidates.forEach { assertTrue(world.fork().step(it).accepted) }
    }

    @Test
    fun `private belief choices honor plain versus admitted menu and mixture zero weights`() {
        for (admission in listOf(false, true)) {
            val probe = ExpansionProbe()
            val plain = PerspectiveRecordingPolicy("private-policy", admission)
            val policy = MixtureOpponentPolicy("private-mixture", listOf(
                OpponentPolicyMixtureEntry(InactiveAnnotationPolicy, 0.0),
                OpponentPolicyMixtureEntry(plain, 1.0),
            ))
            assertEquals(admission, policy.requiresProductionAdmission)
            assertEquals(admission, policy.behaviorSpecification.requiresProductionAdmission)
            val particles = ParticleBelief.from(belief(PolicyAdmissionWorld(probe, 1)), BeliefMode.CONSISTENCY_ONLY_V1)
            assertEquals(1, particles.advanceUnobserved("p1", policy, 91L).belief.size)
            assertEquals(0, probe.annotationCalls)
            assertEquals(if (admission) 1 else 0, probe.admissionCalls)
            assertEquals(if (admission) "admitted" else "base-a", probe.acceptedLabels.single())
            assertTrue(plain.perspectives.isNotEmpty())
            plain.perspectives.forEach { assertEquals("p1", it.viewer) }
        }
    }

    private fun belief(world: SearchWorld) = BeliefBatch(
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
        ),
    )
}

private object InactiveAnnotationPolicy : OpponentPolicy {
    override val id = "inactive-annotation-policy"
    override val requiresPolicyAnnotations = true
    override fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.expansion.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> = error("A zero-weight component must not execute")
}

private class SeedRecordingMixturePolicy(override val id: String) : OpponentPolicy {
    val distributionSeeds = mutableListOf<Long>()
    val diagnosticSeeds = mutableListOf<Pair<Long, Long>>()

    override fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.expansion.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> {
        distributionSeeds += policySeed
        return ProbabilityDistribution.uniform(candidates)
    }

    override fun decisionDiagnostic(context: DecisionSiteRequest, chosen: SemanticChoice, policySeed: Long, attributionSeed: Long): OpponentPolicyDecisionDiagnostic = decisionDiagnostic(context.information(), context.expansion.candidates, chosen, policySeed, attributionSeed)

    fun decisionDiagnostic(opponentInformation: InformationStateRepresentation,
        candidates: List<SemanticChoice>,
        chosen: SemanticChoice,
        policySeed: Long,
        attributionSeed: Long,
    ): OpponentPolicyDecisionDiagnostic {
        diagnosticSeeds += policySeed to attributionSeed
        return OpponentPolicyDecisionDiagnostic(declaredPolicyId = id, selectedComponentId = id)
    }
}

private data class PerspectiveWitness(
    val actor: String,
    val viewer: String,
    val candidateLabels: List<String>,
)

private class PerspectiveRecordingPolicy(
    override val id: String, override val requiresProductionAdmission: Boolean = true,
) : OpponentPolicy {
    var calls: Int = 0
    val perspectives = mutableListOf<PerspectiveWitness>()
    override val distributionIsSeedInvariant: Boolean = true

    override fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.expansion.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> {
        calls++
        perspectives += PerspectiveWitness(
            actor = opponentInformation.actingPlayerId ?: error("policy decision without actor"),
            viewer = opponentInformation.observation.perspectivePlayerId,
            candidateLabels = candidates.map { it.display.label },
        )
        return ProbabilityDistribution.normalized(candidates.mapIndexed { index, choice ->
            ProbabilityMass(choice, if (index == 0) 1.0 else 0.0)
        })
    }
}

private class ExpansionProbe {
    var informationCalls: Int = 0
    var admissionCalls: Int = 0
    var annotationCalls: Int = 0
    val acceptedLabels = mutableListOf<String>()
}

private class PolicyAdmissionWorld(
    private val probe: ExpansionProbe,
    private var tick: Int = 0,
    private val volatile: Boolean = false,
) : PolicyAnnotatedSearchWorld {
    override fun decisionContext(view: DecisionView): DecisionSiteRequest {
        val expansion = when {
            view.annotations -> expandChoicesWithPolicyAnnotations()
            view.admission == DecisionAdmission.PRODUCTION -> expandChoicesForPolicyAdmission()
            else -> expandChoices()
        }
        val captured = fork()
        return DecisionSiteRequest.capture(requireNotNull(actorToAct()), expansion,
            { captured.epistemicState(requireNotNull(captured.actorToAct())) }, view, expansion.proposalVersion)
    }
    private fun choice(label: String, annotated: Boolean = false): SemanticChoice = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.OTHER,
        display = SemanticChoiceDisplay(
            label = label,
            policyTags = if (annotated) setOf("test-policy-annotation") else emptySet(),
        ),
        canonicalPayload = buildJsonObject { put("choice", JsonPrimitive(label)) },
    )

    private fun baseCandidates(): List<SemanticChoice> = listOf(choice("base-a"), choice("base-b"))
    private fun admittedCandidates(annotated: Boolean = false): List<SemanticChoice> = listOf(
        choice("admitted", annotated),
        choice("base-b"),
    )

    override fun actorToAct(): String? = if (tick >= 4) null else if (tick % 2 == 0) "p0" else "p1"

    override fun informationState(viewer: String): InformationStateRepresentation {
        probe.informationCalls++
        val actor = actorToAct()
        return InformationStateRepresentation(
            actingPlayerId = actor,
            observation = PlayerObservationSnapshot(
                perspectivePlayerId = viewer,
                turnNumber = 1 + tick / 2,
                phase = if (volatile) "COMBAT" else "TEST",
                step = "POLICY",
                activePlayerId = actor,
                priorityPlayerId = actor,
                players = emptyList(),
                zones = emptyList(),
                stack = emptyList(),
                pendingDecision = null,
                observationDigest = PolicyJson.sha256("admission-observation:$viewer:$tick"),
            ),
            informationStateDigest = PolicyJson.sha256("admission-information:$viewer:$tick"),
            historyCommitment = PolicyHistoryCommitment.empty(),
            history = emptyList(),
            candidates = if (actor == viewer) baseCandidates() else emptyList(),
            terminated = tick >= 4,
        )
    }

    override fun expandChoices(): PolicyExpansion = PolicyExpansion(
        candidates = if (tick >= 4) emptyList() else baseCandidates(),
        isExhaustive = true,
        estimatedCandidateCount = if (tick >= 4) 0 else 2,
        proposalVersion = "annotation-requirement-test-v1",
        proposalSeed = 11L,
    )

    override fun expandChoicesForPolicyAdmission(): PolicyExpansion {
        probe.admissionCalls++
        return expandChoices().copy(candidates = if (tick >= 4) emptyList() else admittedCandidates())
    }

    override fun expandChoicesForPolicyAdmission(limit: Int): PolicyExpansion = expandChoicesForPolicyAdmission()

    override fun expandChoicesWithPolicyAnnotations(): PolicyExpansion {
        probe.annotationCalls++
        return expandChoices().copy(candidates = if (tick >= 4) emptyList() else admittedCandidates(annotated = true))
    }

    override fun expandChoicesWithPolicyAnnotations(limit: Int): PolicyExpansion = expandChoicesWithPolicyAnnotations()

    override fun step(choice: SemanticChoice): SearchStepResult {
        val accepted = tick < 4 && choice.display.label in setOf("base-a", "base-b", "admitted")
        if (!accepted) return SearchStepResult(false, "candidate rejected by test world")
        probe.acceptedLabels += choice.display.label
        tick++
        return SearchStepResult(true)
    }

    override fun fork(): SearchWorld = PolicyAdmissionWorld(probe, tick, volatile)

    override fun terminalPayoff(rootPlayer: String): Double? = if (tick >= 4) 0.0 else null

    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double = 0.0
}

/** Exact uniform test policy with nontrivial attribution seeds, shared with the refresh witness. */
internal class MenuSelectionProbePolicy(override val id: String, private val enabled: Boolean) : OpponentPolicy {
    override val requiresProductionAdmission = false
    val seeds = mutableListOf<Pair<Long, Long>>()
    var menuCalls = 0
    override fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.expansion.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation, candidates: List<SemanticChoice>, policySeed: Long) =
        ProbabilityDistribution.uniform(candidates)
    private fun diagnostic(policySeed: Long, attributionSeed: Long): OpponentPolicyDecisionDiagnostic {
        seeds += policySeed to attributionSeed
        return OpponentPolicyDecisionDiagnostic(declaredPolicyId = id, selectedComponentId = "$id:${attributionSeed and 1}")
    }
    override fun decisionDiagnostic(context: DecisionSiteRequest, chosen: SemanticChoice, policySeed: Long, attributionSeed: Long): OpponentPolicyDecisionDiagnostic = decisionDiagnostic(context.information(), context.expansion.candidates, chosen, policySeed, attributionSeed)

    fun decisionDiagnostic(opponentInformation: InformationStateRepresentation, candidates: List<SemanticChoice>,
        chosen: SemanticChoice, policySeed: Long, attributionSeed: Long) = diagnostic(policySeed, attributionSeed)
    override fun select(context: DecisionSiteRequest, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
        if (!enabled) return super<OpponentPolicy>.select(context, policySeed, sampleSeed)
        menuCalls++
        return OpponentPolicyDecision(sampleOpponentPolicyDistribution(ProbabilityDistribution.uniform(context.expansion.candidates), sampleSeed),
            diagnostic(policySeed, ComponentSeeds.derive(sampleSeed, id, "component-attribution")))
    }
}
