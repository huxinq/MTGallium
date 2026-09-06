package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class RolloutPolicyAnnotationRequirementTest {
    @Test
    fun `annotation-free rollout policies use policy admission without materializing annotations`() {
        val probe = ExpansionProbe()
        val rootRollout = PerspectiveRecordingPolicy("root-rollout-no-annotations")
        val opponentRollout = PerspectiveRecordingPolicy("opponent-rollout-no-annotations")
        val treeOpponent = PerspectiveRecordingPolicy("tree-opponent-no-annotations")
        val evaluator = object : InformationStateEvaluator {
            override val id = LeafEvaluator.MTGALLIUM_VISIBLE_V2.evaluatorId
            override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double = 0.0
        }
        val search = InformationSetSearch(
            config = InformationSetSearchConfig(
                simulations = 8,
                maxPolicyDecisions = 8,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.BOUNDED_ROLLOUT,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
            ),
            opponentPolicy = treeOpponent,
            rolloutPolicy = rootRollout,
            rolloutOpponentPolicy = opponentRollout,
            leafEvaluationStrategy = LeafEvaluationStrategy(
                evaluator.id,
                LeafValueSource.Information(evaluator),
            ),
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

private data class PerspectiveWitness(
    val actor: String,
    val viewer: String,
    val candidateLabels: List<String>,
)

private class PerspectiveRecordingPolicy(override val id: String) : OpponentPolicy {
    var calls: Int = 0
    val perspectives = mutableListOf<PerspectiveWitness>()
    override val distributionIsSeedInvariant: Boolean = true

    override fun distribution(
        opponentInformation: PolicyInformationState,
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
    var admissionCalls: Int = 0
    var annotationCalls: Int = 0
    val acceptedLabels = mutableListOf<String>()
}

private class PolicyAdmissionWorld(
    private val probe: ExpansionProbe,
    private var tick: Int = 0,
) : PolicyAnnotatedSearchWorld {
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

    override fun informationState(viewer: String): PolicyInformationState {
        val actor = actorToAct()
        return PolicyInformationState(
            actingPlayerId = actor,
            observation = PolicyObservation(
                perspectivePlayerId = viewer,
                turnNumber = 1 + tick / 2,
                phase = "TEST",
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

    override fun fork(): SearchWorld = PolicyAdmissionWorld(probe, tick)

    override fun terminalPayoff(rootPlayer: String): Double? = if (tick >= 4) 0.0 else null

    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double = 0.0
}
