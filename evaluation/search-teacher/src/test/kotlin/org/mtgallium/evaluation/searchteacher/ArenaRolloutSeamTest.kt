package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicyBehaviorSpecification
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.searchteacher.SearchTeacherRuntimeConfig

@Tag("public-source")
class ArenaRolloutSeamTest {
    private val parameters = SearchTeacherRuntimeConfig().policyParameters()
        .copy(particles = 1, simulations = 2, maxPolicyDecisions = 8)
    private val control = ArenaPolicySpec("rollout-seam", ArenaPolicyKind.SEARCH, parameters = parameters)

    @Test
    fun `arena invokes the rollout policies committed in its behavior binding`() {
        val rootPolicy = CountingUniformPolicy("root-rollout-witness")
        val opponentPolicy = CountingUniformPolicy("opponent-rollout-witness")
        val treatment = control.copy(rootRolloutPolicy = rootPolicy, opponentRolloutPolicy = opponentPolicy)
        val manifest = SearchTeacherDeckManifest(
            "rollout-test", "Rollout fixture", "synthetic", "2026-09-06", "public synthetic fixture",
            mapOf("Mountain" to 60), emptyMap(),
        )
        val profile = FrozenSearchProfile(
            id = "fast-arena-v1", generatedAtUtc = "synthetic", outerCommit = "test-outer",
            argentumCommit = "test-engine", host = "synthetic", particles = 8, simulations = 64,
            leaf = parameters.leaf, actionSpaceProfile = parameters.actionSpaceProfile,
            maxPolicyDecisions = 8, measuredP95Millis = 0.0, tacticalScore = 0.0,
            standardError = 0.0, calibrationReportHash = "synthetic",
        )
        val arena = SearchTeacherArena(buildRegistry(), manifest, profile, 19L)
        val empty = PolicyJson.sha256("")
        val source = PolicySourceProvenance(
            expectedArgentumRevision = "test-engine",
            outer = PolicySourceTreeState("test-outer", empty, empty, empty),
            argentum = PolicySourceTreeState("test-engine", empty, empty, empty),
        )
        val binding = arena.evidenceBinding(treatment, 1, source)
        assertNotEquals(arena.evidenceBinding(control, 1, source).identity, binding.identity)
        assertNotEquals(
            arena.evidenceBinding(treatment.copy(opponentRolloutPolicy = null), 1, source).identity,
            binding.identity,
        )
        val game = arena.playWithPolicies(
            "00000000-0000-4000-8000-000000009061", 19L,
            treatment, ArenaPolicySpec("heuristic", ArenaPolicyKind.HEURISTIC), maxSearchDecisions = 1,
        )
        assertEquals(GameRunDisposition.STOPPED_LIMIT, game.disposition, game.exception)
        val search = game.seatDiagnostics.getValue("p0").searchDecisionsDetail.single().searchDiagnostics
        assertEquals(rootPolicy.id, search.rootRolloutPolicyId)
        assertEquals(opponentPolicy.id, search.opponentRolloutPolicyId)
        assertTrue(rootPolicy.calls + opponentPolicy.calls > 0, "The declared policies must execute")
    }

    @Test
    fun `custom rollout policies cannot be silently ignored on unsupported paths`() {
        assertFailsWith<IllegalArgumentException> {
            control.copy(searchPlanner = SearchPlannerKind.INDEPENDENT_DETERMINIZATION,
                rootRolloutPolicy = UniformOpponentPolicy)
        }
        assertFailsWith<IllegalArgumentException> {
            control.copy(parameters = parameters.copy(leaf = parameters.leaf.copy(
                stateSource = LeafStateSource.CURRENT_INFORMATION_STATE,
            )), rootRolloutPolicy = UniformOpponentPolicy)
        }
        assertFailsWith<IllegalArgumentException> {
            ArenaPolicySpec("direct", ArenaPolicyKind.HEURISTIC, rootRolloutPolicy = UniformOpponentPolicy)
        }
    }

    private class CountingUniformPolicy(override val id: String) : OpponentPolicy {
        var calls = 0
        override val distributionIsSeedInvariant = true
        override val behaviorSpecification = OpponentPolicyBehaviorSpecification(
            implementationId = "uniform-rollout-witness", declaredId = id, distributionIsSeedInvariant = true,
        )
        override fun distribution(opponentInformation: PolicyInformationState, candidates: List<SemanticChoice>, policySeed: Long) =
            UniformOpponentPolicy.distribution(opponentInformation, candidates, policySeed).also { calls++ }
    }
}
