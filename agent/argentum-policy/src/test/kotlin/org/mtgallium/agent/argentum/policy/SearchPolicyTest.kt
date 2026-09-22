package org.mtgallium.agent.argentum.policy

import kotlin.test.assertIs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyExpansionOmissionReason
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.DecisionPolicy
import org.mtgallium.agent.infoset.core.DecisionSiteRequest
import org.mtgallium.agent.infoset.core.RootActionSelection
import org.mtgallium.agent.infoset.core.RootActionSelector
import org.mtgallium.agent.infoset.core.SingletonSelectionConfig

class SearchPolicyTest {
    @Test
    fun `runtime config maps every behavior-affecting field into policy parameters`() {
        val config = LivePolicyConfig(
            particles = 16,
            simulations = 256,
            maxPolicyDecisions = 47,
            explorationConstant = 0.75,
            beliefMode = BeliefMode.POLICY_CONDITIONED_V1,
            beliefArchitecture = BeliefArchitecture.HYBRID_C_V1,
            baseSeed = 91L,
            initialExpansionLimit = 32,
            wideningThresholds = listOf(40, 80),
            wideningLimits = listOf(64, 128),
            maxQuiescenceDecisions = 11,
            maxQuiescenceForcedPasses = 79,
            cacheSimulationTransitions = false,
            wallClockBudgetMillis = 500,
            minimumSimulations = 7,
            singletonSelection = SingletonSelectionConfig(enabled = true),
        )

        val parameters = config.policyParameters()

        assertEquals(config.particles, parameters.particles)
        assertEquals(config.simulations, parameters.simulations)
        assertEquals(config.maxPolicyDecisions, parameters.maxPolicyDecisions)
        assertEquals(config.explorationConstant, parameters.explorationConstant)
        assertEquals(config.leaf, parameters.leaf)
        assertEquals(config.actionSpaceProfile, parameters.actionSpaceProfile)
        assertEquals(config.beliefMode, parameters.beliefMode)
        assertEquals(config.beliefArchitecture, parameters.beliefArchitecture)
        assertEquals(config.baseSeed, parameters.baseSeed)
        assertEquals(config.profileId, parameters.profileId)
        assertEquals(config.initialExpansionLimit, parameters.initialExpansionLimit)
        assertEquals(config.wideningThresholds, parameters.wideningThresholds)
        assertEquals(config.wideningLimits, parameters.wideningLimits)
        assertEquals(config.maxQuiescenceDecisions, parameters.maxQuiescenceDecisions)
        assertEquals(config.maxQuiescenceForcedPasses, parameters.maxQuiescenceForcedPasses)
        assertEquals(config.cacheSimulationTransitions, parameters.cacheSimulationTransitions)
        assertEquals(config.wallClockBudgetMillis, parameters.wallClockBudgetMillis)
        assertEquals(config.minimumSimulations, parameters.minimumSimulations)
        assertEquals(config.singletonSelection, parameters.singletonSelection)
        assertEquals(config.initialExpansionLimit, parameters.searchConfig().initialExpansionLimit)
        assertEquals(config.wideningThresholds, parameters.searchConfig().wideningThresholds)
        assertEquals(config.wideningLimits, parameters.searchConfig().wideningLimits)
    }

    @Test
    fun `runtime budgets are positive counts not a historical experiment grid`() {
        val config = LivePolicyConfig(particles = 3, simulations = 7)
        assertEquals(3, config.policyParameters().particles)
        assertEquals(7, config.policyParameters().searchConfig().simulations)
        assertFailsWith<IllegalArgumentException> { LivePolicyConfig(particles = 0) }
        assertFailsWith<IllegalArgumentException> { LivePolicyConfig(simulations = 0) }
    }

    @Test
    fun `policy parameters reject invalid search budgets`() {
        assertFailsWith<IllegalArgumentException> {
            LivePolicyConfig().policyParameters().copy(simulations = 0)
        }
    }

    @Test
    fun `runtime accepts arbitrary positive budgets`() {
        val config = LivePolicyConfig(particles = 3, simulations = 17)
        val parameters = config.policyParameters()
        assertEquals(3, parameters.particles)
        assertEquals(17, parameters.searchConfig().simulations)
        assertFailsWith<IllegalArgumentException> { LivePolicyConfig(particles = 0) }
        assertFailsWith<IllegalArgumentException> { LivePolicyConfig(simulations = -1) }
    }

    @Test
    fun `policy singleton selection retains the action without claiming a search or rules authority`() {
        val pass = choice("Pass priority", SemanticOperationFamily.PASS_PRIORITY)
        val expansion = PolicyExpansion(
            candidates = listOf(pass),
            isExhaustive = false,
            estimatedCandidateCount = null,
            proposalVersion = "profile-singleton-test-v1",
            isProfileExhaustive = true,
            omissionReasons = setOf(PolicyExpansionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA),
        )
        val selected = rootSelection(expansion)
        assertEquals(pass, selected.choice)
        assertIs<RootActionSelection.PolicySingletonAction>(selected)
        assertIs<RootActionSelection.Unsearched>(selected)

        val enabled = LivePolicyConfig(
            singletonSelection = SingletonSelectionConfig(enabled = true),
        ).policyParameters()
        assertTrue(enabled.singletonSelection.enabled)
        assertEquals(false, LivePolicyConfig().singletonSelection.enabled)
    }

    @Test
    fun `singleton selection refuses bounded enumeration responses and pregame choices`() {
        val pass = choice("Pass priority", SemanticOperationFamily.PASS_PRIORITY)
        val exact = PolicyExpansion(listOf(pass), true, 1, "singleton-test-v1")
        PolicyExpansionOmissionReason.entries.filterNot { it.intentionalProfileOmission }.forEach { omission ->
            // Even an inconsistent profile-exhaustive claim cannot hide an explicit bounded omission.
            for (profileExhaustive in listOf(false, true)) {
                assertIs<RootActionSelection.DirectPolicyAction>(rootSelection(exact.copy(
                    isExhaustive = false, isProfileExhaustive = profileExhaustive,
                    omissionReasons = setOf(omission),
                )))
            }
        }
        val response = SemanticChoice.create(
            kind = SemanticChoiceKind.DECISION,
            operationFamily = SemanticOperationFamily.DECISION_RESPONSE,
            display = SemanticChoiceDisplay("Respond"),
            canonicalPayload = buildJsonObject { put("response", JsonPrimitive("only")) },
        )
        assertIs<RootActionSelection.DirectPolicyAction>(rootSelection(exact.copy(candidates = listOf(response))))
        assertIs<RootActionSelection.DirectPolicyAction>(rootSelection(exact.copy(
            candidates = listOf(choice("Keep hand", SemanticOperationFamily.MULLIGAN)),
        )))
        assertIs<RootActionSelection.DirectPolicyAction>(rootSelection(exact.copy(
            candidates = listOf(pass, choice("Play land", SemanticOperationFamily.PLAY_LAND)),
            estimatedCandidateCount = 2,
        )))
        val block = choice("Decline block", SemanticOperationFamily.DECLARE_BLOCKERS)
        assertEquals(block, rootSelection(exact.copy(candidates = listOf(block))).choice)
    }

    @Test
    fun `profile singleton pass never bypasses the responsible policy`() {
        val pass = SemanticChoice.create(
            kind = SemanticChoiceKind.ACTION,
            operationFamily = SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay("Pass priority"),
            canonicalPayload = buildJsonObject { put("action", JsonPrimitive("pass")) },
        )
        val profiled = PolicyExpansion(
            candidates = listOf(pass),
            isExhaustive = false,
            estimatedCandidateCount = null,
            proposalVersion = "profile-singleton-test-v1",
            isProfileExhaustive = true,
            omissionReasons = setOf(
                PolicyExpansionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA
            ),
        )

        assertIs<RootActionSelection.PolicySingletonAction>(rootSelection(profiled))

        val rulesForced = rootSelection(
            profiled.copy(
                isExhaustive = true,
                estimatedCandidateCount = 1,
                isProfileExhaustive = true,
                omissionReasons = emptySet(),
            ),
        )
        assertIs<RootActionSelection.RulesForcedPass>(rulesForced)
    }

    @Test
    fun `a land-available pass remains a searched strategic choice`() {
        val pass = choice("Pass priority", SemanticOperationFamily.PASS_PRIORITY)
        val land = choice("Play Mountain", SemanticOperationFamily.PLAY_LAND)
        val expansion = PolicyExpansion(
            candidates = listOf(pass, land),
            isExhaustive = true,
            estimatedCandidateCount = 2,
            proposalVersion = "land-hold-test-v1",
        )

        assertIs<RootActionSelection.DirectPolicyAction>(rootSelection(expansion))
    }

    private fun choice(label: String, family: SemanticOperationFamily): SemanticChoice = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION,
        operationFamily = family,
        display = SemanticChoiceDisplay(label),
        canonicalPayload = buildJsonObject { put("action", JsonPrimitive(label)) },
    )

    private fun rootSelection(expansion: PolicyExpansion): RootActionSelection {
        val fallback = expansion.candidates.first()
        val direct = object : DecisionPolicy {
            override val configurationId = "selector-test"
            override fun choose(context: DecisionSiteRequest, decisionSeed: Long) = fallback
        }
        return RootActionSelector(true, direct).select(
            DecisionSiteRequest.capture("p0", expansion, { error("Information was forced") }), 0L
        ) { error("Direct policy should run when automatic selection does not apply") }
    }

}
