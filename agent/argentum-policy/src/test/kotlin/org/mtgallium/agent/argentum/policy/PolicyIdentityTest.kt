package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.monored.MonoRedTacticalEvaluator
import org.mtgallium.agent.monored.MonoRedTacticalEvaluatorSettings
import org.mtgallium.agent.monored.MonoRedTacticalEvaluatorWeights
import org.mtgallium.agent.infoset.core.SingletonSelectionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.argentum.ArgentumHeuristicProfile
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpansionSpecification
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.infoset.core.MixtureOpponentPolicy
import org.mtgallium.agent.infoset.core.ActionSelector
import org.mtgallium.agent.infoset.core.DecisionSiteRequest
import org.mtgallium.agent.infoset.core.OpponentPolicyDecision
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionDiagnostic
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicyBehaviorSpecification
import org.mtgallium.agent.infoset.core.OpponentPolicyMixtureEntry
import org.mtgallium.agent.infoset.core.OpponentPolicyReplacementEvidenceDisposition
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.ProbabilityDistribution
import org.mtgallium.agent.infoset.core.RolloutTurnHorizon
import org.mtgallium.agent.infoset.core.RolloutCutoff
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import org.mtgallium.agent.infoset.core.UnresolvedLeafHandling

class PolicyIdentityTest {
    @Test fun `current behavior describes no retired feature and does not impersonate the old schema`() {
        val specification = PolicyIdentity.specification(parameters(), decks(), mixture())
        val encoded = PolicyJson.format.encodeToJsonElement(specification) as JsonObject
        assertFalse("policyCompression" in encoded)
        assertFalse("searchReuse" in encoded)
        assertFalse("compressPolicySingletonPasses" in (encoded.getValue("search") as JsonObject))
        assertFalse("supportsTraceReuse" in (encoded.getValue("evaluator") as JsonObject))
        val decoded = PolicyJson.format.decodeFromJsonElement<PolicyBehaviorSpecification>(encoded)
        assertEquals(specification, decoded)
        assertEquals(PolicyIdentity.identity(specification), PolicyIdentity.identity(decoded))
        val identities = listOf(identity(),
            identity(parameters = parameters().copy(beliefMode = BeliefMode.POLICY_CONDITIONED_V1)),
            identity(parameters = parameters().copy(searchHeuristicProfile = ArgentumHeuristicProfile.PRODUCTION_EXPIRING)))
        assertEquals(identities.size, identities.distinct().size)
        // Removed fields intentionally change identities; historical evidence keeps the old source.
        assertNotEquals("6fa2130c39432f110f641f004a511973d7b23ec8627b0ffbdc5512fe78321789",
            PolicyJson.sha256(identities.joinToString("\n")))
    }

    @Test
    fun `selector only root continuation changes only its own behavior identity`() {
        val beliefModel = mixture()
        val opponentContinuation = fixedPolicy("fixed-opponent")
        val control = PolicyIdentity.specification(parameters(), decks(), beliefModel,
            opponentRolloutPolicy = opponentContinuation)
        val root = object : ActionSelector {
            override val id = "selector-only-root"
            override fun select(context: DecisionSiteRequest,
                policySeed: Long, sampleSeed: Long) = OpponentPolicyDecision(context.expansion.candidates.first(),
                OpponentPolicyDecisionDiagnostic(id, id))
        }
        val treatment = PolicyIdentity.specification(parameters(), decks(), beliefModel,
            rootRolloutPolicy = root, opponentRolloutPolicy = opponentContinuation)
        assertEquals(control.copy(rootRolloutPolicy = root.behaviorSpecification), treatment)
        assertEquals(beliefModel.behaviorSpecification, treatment.opponentPolicy)
        assertEquals(opponentContinuation.behaviorSpecification, treatment.opponentRolloutPolicy)
        assertNotEquals(PolicyIdentity.identity(control), PolicyIdentity.identity(treatment))
    }

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
            "rollout turn horizon" to base.copy(
                rolloutTurnHorizon = RolloutTurnHorizon(2, 96),
                leaf = LeafEvaluationConfig(
                    LeafStateSource.BOUNDED_ROLLOUT,
                    RolloutCutoff.EVALUATE,
                    UnresolvedLeafHandling.EVALUATE,
                ),
            ),
            "base seed" to base.copy(baseSeed = base.baseSeed + 1),
            "belief mode" to base.copy(beliefMode = BeliefMode.POLICY_CONDITIONED_V1),
            "belief architecture" to base.copy(beliefArchitecture = BeliefArchitecture.HYBRID_C_V1),
            "leaf state source" to base.copy(
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    RolloutCutoff.EVALUATE,
                    UnresolvedLeafHandling.EVALUATE,
                )
            ),
            "action-space semantics" to base.copy(
                actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1
            ),
            "singleton selection" to base.copy(
                singletonSelection = SingletonSelectionConfig(enabled = true)
            ),
            "simulated heuristic profile" to base.copy(
                searchHeuristicProfile = ArgentumHeuristicProfile.PRODUCTION_EXPIRING
            ),
        )

        mutations.forEach { (description, changed) ->
            assertNotEquals(expected, identity(parameters = changed), description)
        }
    }

    @Test
    fun `optional singleton selection roundtrips and changes current behavior identity`() {
        val specification = PolicyIdentity.specification(
            parameters = LivePolicyConfig().policyParameters(),
            knownDecks = decks(),
            opponentPolicy = UniformOpponentPolicy,
        )
        val encoded = PolicyJson.format.encodeToJsonElement(specification) as JsonObject
        assertFalse("singletonSelection" in encoded)
        assertFalse("rootSelectionGuidanceId" in encoded)
        assertNotEquals(PolicyIdentity.identity(specification),
            PolicyIdentity.identity(specification.copy(rootSelectionGuidanceId = "rule:model")))
        assertFalse("rolloutTurnHorizon" in (encoded.getValue("search") as JsonObject))
        assertFalse("searchHeuristicProfile" in encoded)
        val decoded = PolicyJson.format.decodeFromJsonElement<PolicyBehaviorSpecification>(encoded)
        assertFalse(decoded.singletonSelection.enabled)
        assertEquals(ArgentumHeuristicProfile.PRODUCTION, decoded.searchHeuristicProfile)
        assertEquals(encoded, PolicyJson.format.encodeToJsonElement(decoded))
        assertEquals(
            "$SEARCH_POLICY_BEHAVIOR_IDENTITY_PREFIX:${PolicyJson.digest(encoded)}",
            PolicyIdentity.identity(decoded),
        )
        val enabled = specification.copy(singletonSelection = SingletonSelectionConfig(enabled = true))
        val enabledJson = PolicyJson.format.encodeToJsonElement(enabled) as JsonObject
        assertTrue("singletonSelection" in enabledJson)
        assertNotEquals(PolicyIdentity.identity(specification), PolicyIdentity.identity(enabled))
        assertEquals(enabled, PolicyJson.format.decodeFromJsonElement<PolicyBehaviorSpecification>(enabledJson))
        val expiring = specification.copy(searchHeuristicProfile = ArgentumHeuristicProfile.PRODUCTION_EXPIRING)
        val expiringJson = PolicyJson.format.encodeToJsonElement(expiring) as JsonObject
        assertTrue("searchHeuristicProfile" in expiringJson)
        assertNotEquals(PolicyIdentity.identity(specification), PolicyIdentity.identity(expiring))
    }

    @Test
    fun `evaluator selection and configured weights change the identity`() {
        val base = parameters()
        val evaluator = MonoRedTacticalEvaluator()
        val expected = identity(parameters = base, valueSource = LeafValueSource.Information(evaluator))

        assertNotEquals(
            expected,
            identity(
                parameters = base.copy(
                    leaf = LeafEvaluationConfig(
                        LeafStateSource.BOUNDED_ROLLOUT,
                        RolloutCutoff.EVALUATE,
                        UnresolvedLeafHandling.EVALUATE,
                    )
                ),
                valueSource = LeafValueSource.Information(MonoRedTacticalEvaluator()),
            ),
        )
        assertNotEquals(
            expected,
            identity(
                parameters = base,
                valueSource = LeafValueSource.Information(MonoRedTacticalEvaluator(
                    MonoRedTacticalEvaluatorSettings(
                        weights = MonoRedTacticalEvaluatorWeights(life = 1.3)
                    )
                )),
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
                    proposalAlgorithmVersion = "semantic-structured-actions-test-alternative",
                    proposalVersion =
                        "semantic-structured-actions-test-alternative:${parameters.actionSpaceProfile.profileId}",
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

        val specification = PolicyIdentity.specification(
            parameters = parameters(),
            knownDecks = firstDecks,
            opponentPolicy = mixture(),
            rootRolloutPolicy = fixedPolicy("root-rollout-a"),
            opponentRolloutPolicy = fixedPolicy("opponent-rollout-a"),
            valueSource = LeafValueSource.Information(MonoRedTacticalEvaluator()),
            integration = firstIntegration,
        )
        val encoded = PolicyJson.format.encodeToJsonElement(
            PolicyBehaviorSpecification.serializer(),
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
                Regex("${Regex.escape(SEARCH_POLICY_BEHAVIOR_IDENTITY_PREFIX)}:[0-9a-f]{64}")
            )
        )
        assertEquals(expectedIdentity, identity())
    }

    private fun parameters(): SearchPolicyConfig = LivePolicyConfig(
        leaf = LeafEvaluationConfig(
            LeafStateSource.BOUNDED_ROLLOUT,
            RolloutCutoff.QUIESCENCE,
            UnresolvedLeafHandling.BACK_UP_NEUTRAL,
        ),
    ).policyParameters()

    private fun decks(p0MountainCount: Int = 1): Map<String, Map<String, Int>> = mapOf(
        "p0" to mapOf("Mountain" to p0MountainCount, "Shock" to 1),
        "p1" to mapOf("Mountain" to 1, "Shock" to 1),
    )

    private fun integration(): IntegrationSpecification =
        IntegrationSpecification(
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

        override fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
            return ProbabilityDistribution.uniform(context.expansion.candidates)
        }
    }

    private fun identity(
        parameters: SearchPolicyConfig = parameters(),
        knownDecks: Map<String, Map<String, Int>> = decks(),
        opponentPolicy: OpponentPolicy = mixture(),
        rootRolloutPolicy: OpponentPolicy = fixedPolicy("root-rollout-a"),
        opponentRolloutPolicy: OpponentPolicy = fixedPolicy("opponent-rollout-a"),
        valueSource: LeafValueSource = LeafValueSource.Information(MonoRedTacticalEvaluator()),
        actionExpansion: UnifiedSemanticExpansionSpecification =
            UnifiedSemanticExpander.defaultBehaviorSpecification(parameters.actionSpaceProfile),
        integration: IntegrationSpecification = integration(),
    ): String = PolicyIdentity.identity(
        parameters = parameters,
        knownDecks = knownDecks,
        opponentPolicy = opponentPolicy,
        rootRolloutPolicy = rootRolloutPolicy,
        opponentRolloutPolicy = opponentRolloutPolicy,
        valueSource = valueSource,
        actionExpansion = actionExpansion,
        integration = integration,
    )
}
