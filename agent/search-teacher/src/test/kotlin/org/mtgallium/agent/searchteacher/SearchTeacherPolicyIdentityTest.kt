package org.mtgallium.agent.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpansionSpecification
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.MixtureOpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicyBehaviorSpecification
import org.mtgallium.agent.infoset.core.OpponentPolicyMixtureEntry
import org.mtgallium.agent.infoset.core.OpponentPolicyReplacementEvidenceDisposition
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.ProbabilityDistribution
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy

class SearchTeacherPolicyIdentityTest {
    @Test
    fun `every declared search belief action and optimization input changes the identity`() {
        val base = parameters()
        val expected = identity(parameters = base)
        val mutations = linkedMapOf(
            "declared profile" to base.copy(profileId = "profile-b"),
            "particle count" to base.copy(particles = base.particles + 1),
            "simulation count" to base.copy(simulations = base.simulations + 1),
            "exploration" to base.copy(explorationConstant = 10.0),
            "policy decision limit" to base.copy(maxPolicyDecisions = base.maxPolicyDecisions + 1),
            "initial action limit" to base.copy(initialExpansionLimit = 32),
            "widening thresholds" to base.copy(wideningThresholds = listOf(65, 256, 1024)),
            "widening action limits" to base.copy(wideningLimits = listOf(129, 256, 512)),
            "quiescence decision limit" to
                base.copy(maxQuiescenceDecisions = base.maxQuiescenceDecisions + 1),
            "quiescence forced-pass limit" to
                base.copy(maxQuiescenceForcedPasses = base.maxQuiescenceForcedPasses + 1),
            "transition cache" to base.copy(cacheSimulationTransitions = false),
            "wall-clock budget" to base.copy(wallClockBudgetMillis = 250),
            "minimum simulations" to base.copy(minimumSimulations = 2),
            "base seed" to base.copy(baseSeed = base.baseSeed + 1),
            "belief mode" to base.copy(beliefMode = BeliefMode.POLICY_CONDITIONED_V1),
            "belief architecture" to base.copy(beliefArchitecture = BeliefArchitecture.HYBRID_C_V1),
            "leaf state source" to base.copy(
                leaf = base.leaf.copy(stateSource = LeafStateSource.CURRENT_INFORMATION_STATE)
            ),
            "action-space semantics" to base.copy(
                actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1
            ),
            "reuse fresh minimum" to base.copy(
                searchReuse = base.searchReuse.copy(minimumFreshSimulations = 17)
            ),
            "reuse fraction" to base.copy(
                searchReuse = base.searchReuse.copy(maximumReuseFraction = 0.5)
            ),
        )

        mutations.forEach { (description, changed) ->
            assertNotEquals(expected, identity(parameters = changed), description)
        }
    }

    @Test
    fun `evaluator selection and configured weights change the identity`() {
        val base = parameters()
        val evaluator = MonoRedTacticalEvaluator()
        val expected = identity(parameters = base, informationEvaluator = evaluator)

        assertNotEquals(
            expected,
            identity(
                parameters = base.copy(
                    leaf = LeafEvaluationConfig(
                        LeafStateSource.BOUNDED_ROLLOUT,
                        LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                    )
                ),
                informationEvaluator = null,
            ),
        )
        assertNotEquals(
            expected,
            identity(
                parameters = base,
                informationEvaluator = MonoRedTacticalEvaluator(
                    MonoRedTacticalEvaluatorSettings(
                        weights = MonoRedTacticalEvaluatorWeights(life = 1.3)
                    )
                ),
            ),
        )
    }

    @Test
    fun `main and rollout policy composition and weights change the identity`() {
        val expected = identity()

        assertNotEquals(expected, identity(opponentPolicy = mixture(weight = 0.6)))
        assertNotEquals(expected, identity(opponentPolicy = mixture(secondId = "component-c")))
        assertNotEquals(expected, identity(rootRolloutPolicy = fixedPolicy("root-rollout-b")))
        assertNotEquals(
            expected,
            identity(opponentRolloutPolicy = fixedPolicy("opponent-rollout-b")),
        )
    }

    @Test
    fun `replacement evidence disposition changes the full policy identity`() {
        val invalidating = identity(
            opponentPolicy = defaultMonoRedOpponentPolicy(
                OpponentPolicyReplacementEvidenceDisposition.INVALIDATES_EVIDENCE
            )
        )
        val predeclared = identity(
            opponentPolicy = defaultMonoRedOpponentPolicy(
                OpponentPolicyReplacementEvidenceDisposition.PREDECLARED_EVIDENCE_ELIGIBLE
            )
        )

        assertNotEquals(invalidating, predeclared)
    }

    @Test
    fun `known deck content and integration behavior change the identity`() {
        val expected = identity()

        assertNotEquals(
            expected,
            identity(knownDecks = decks(p0MountainCount = 2)),
        )
        assertNotEquals(
            expected,
            identity(
                integration = integration().copy(searchPlanner = "independent-determinization-v1")
            ),
        )
        assertNotEquals(
            expected,
            identity(integration = integration().copy(maximumGameDecisions = 4_096)),
        )
        assertNotEquals(
            expected,
            identity(integration = integration().copy(maximumSearchDecisions = 12)),
        )
        assertNotEquals(
            expected,
            identity(integration = integration().copy(hostMode = "evaluation-arena-v2")),
        )
        assertNotEquals(
            expected,
            identity(
                integration = integration().copy(additionalBindings = mapOf("responseMode" to "b"))
            ),
        )
    }

    @Test
    fun `candidate generator limits and algorithm version change the identity`() {
        val parameters = parameters()
        val expansion = UnifiedSemanticExpander.defaultBehaviorSpecification(
            parameters.actionSpaceProfile
        )
        val expected = identity(parameters = parameters, actionExpansion = expansion)

        assertNotEquals(
            expected,
            identity(parameters = parameters, actionExpansion = expansion.copy(defaultResponseLimit = 63)),
        )
        assertNotEquals(
            expected,
            identity(parameters = parameters, actionExpansion = expansion.copy(maximumAttempts = 2_049)),
        )
        assertNotEquals(
            expected,
            identity(
                parameters = parameters,
                actionExpansion = expansion.copy(
                    proposalAlgorithmVersion = "semantic-structured-actions-v5",
                    proposalVersion =
                        "semantic-structured-actions-v5:${parameters.actionSpaceProfile.profileId}",
                ),
            ),
        )
    }

    @Test
    fun `map and serialized field ordering do not change the canonical identity`() {
        val firstDecks = linkedMapOf(
            "p0" to linkedMapOf("Mountain" to 1, "Shock" to 1),
            "p1" to linkedMapOf("Shock" to 1, "Mountain" to 1),
        )
        val reversedDecks = linkedMapOf(
            "p1" to linkedMapOf("Mountain" to 1, "Shock" to 1),
            "p0" to linkedMapOf("Shock" to 1, "Mountain" to 1),
        )
        val firstIntegration = integration().copy(
            additionalBindings = linkedMapOf("alpha" to "1", "beta" to "2")
        )
        val reversedIntegration = integration().copy(
            additionalBindings = linkedMapOf("beta" to "2", "alpha" to "1")
        )

        assertEquals(
            identity(knownDecks = firstDecks, integration = firstIntegration),
            identity(knownDecks = reversedDecks, integration = reversedIntegration),
        )

        val specification = SearchTeacherPolicyIdentity.specification(
            parameters = parameters(),
            knownDecks = firstDecks,
            opponentPolicy = mixture(),
            rootRolloutPolicy = fixedPolicy("root-rollout-a"),
            opponentRolloutPolicy = fixedPolicy("opponent-rollout-a"),
            informationEvaluator = MonoRedTacticalEvaluator(),
            integration = firstIntegration,
        )
        val encoded = PolicyJson.format.encodeToJsonElement(
            SearchTeacherBehaviorSpecification.serializer(),
            specification,
        ) as JsonObject
        val reversedFields = JsonObject(encoded.entries.reversed().associate { it.toPair() })
        assertEquals(PolicyJson.digest(encoded), PolicyJson.digest(reversedFields))
    }

    @Test
    fun `identity is a stable version marker plus a lowercase sha256`() {
        val expectedIdentity = identity()

        assertTrue(
            expectedIdentity.matches(
                Regex("${Regex.escape(SEARCH_TEACHER_BEHAVIOR_IDENTITY_PREFIX)}:[0-9a-f]{64}")
            )
        )
        assertEquals(expectedIdentity, identity())
    }

    private fun parameters(): SearchTeacherPolicyParameters = SearchTeacherRuntimeConfig(
        leaf = LeafEvaluationConfig(
            LeafStateSource.BOUNDED_ROLLOUT,
            LeafEvaluator.MTGALLIUM_TACTICAL_V3,
        ),
    ).policyParameters()

    private fun decks(p0MountainCount: Int = 1): Map<String, Map<String, Int>> = mapOf(
        "p0" to mapOf("Mountain" to p0MountainCount, "Shock" to 1),
        "p1" to mapOf("Mountain" to 1, "Shock" to 1),
    )

    private fun integration(): SearchTeacherIntegrationSpecification =
        SearchTeacherIntegrationSpecification(
            hostMode = "evaluation-arena-v1",
            searchPlanner = "shared-information-set-tree-v1",
            maximumGameDecisions = 2_048,
        )

    private fun mixture(
        weight: Double = 0.7,
        secondId: String = "component-b",
    ): OpponentPolicy = MixtureOpponentPolicy(
        id = "stable-mixture-id",
        components = listOf(
            OpponentPolicyMixtureEntry(fixedPolicy("component-a"), weight),
            OpponentPolicyMixtureEntry(fixedPolicy(secondId), 1.0 - weight),
        ),
    )

    private fun fixedPolicy(id: String): OpponentPolicy = object : OpponentPolicy {
        override val id: String = id
        override val distributionIsSeedInvariant: Boolean = true
        override val behaviorSpecification: OpponentPolicyBehaviorSpecification =
            OpponentPolicyBehaviorSpecification(
                implementationId = "identity-test-policy-v1",
                declaredId = id,
                distributionIsSeedInvariant = true,
            )

        override fun distribution(
            opponentInformation: PolicyInformationState,
            candidates: List<SemanticChoice>,
            policySeed: Long,
        ): ProbabilityDistribution<SemanticChoice> = ProbabilityDistribution.uniform(candidates)
    }

    private fun identity(
        parameters: SearchTeacherPolicyParameters = parameters(),
        knownDecks: Map<String, Map<String, Int>> = decks(),
        opponentPolicy: OpponentPolicy = mixture(),
        rootRolloutPolicy: OpponentPolicy = fixedPolicy("root-rollout-a"),
        opponentRolloutPolicy: OpponentPolicy = fixedPolicy("opponent-rollout-a"),
        informationEvaluator: org.mtgallium.agent.infoset.core.InformationStateEvaluator? =
            MonoRedTacticalEvaluator(),
        actionExpansion: UnifiedSemanticExpansionSpecification =
            UnifiedSemanticExpander.defaultBehaviorSpecification(parameters.actionSpaceProfile),
        integration: SearchTeacherIntegrationSpecification = integration(),
    ): String = SearchTeacherPolicyIdentity.identity(
        parameters = parameters,
        knownDecks = knownDecks,
        opponentPolicy = opponentPolicy,
        rootRolloutPolicy = rootRolloutPolicy,
        opponentRolloutPolicy = opponentRolloutPolicy,
        informationEvaluator = informationEvaluator,
        actionExpansion = actionExpansion,
        integration = integration,
    )
}
