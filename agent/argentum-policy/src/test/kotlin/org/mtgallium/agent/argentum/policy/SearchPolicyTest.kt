package org.mtgallium.agent.argentum.policy

import kotlin.test.assertIs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.ActionMenu
import org.mtgallium.agent.infoset.core.ActionOmissionReason
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.DecisionPolicy
import org.mtgallium.agent.infoset.core.DecisionContext
import org.mtgallium.agent.infoset.planning.RootActionSelection
import org.mtgallium.agent.infoset.planning.RootActionSelector
import org.mtgallium.agent.infoset.planning.SingletonMenuShortcutConfig

class SearchPolicyTest {
    @Test
    fun `host menu covers reachable widening without changing the default view`() {
        val parameters = SearchPolicyConfig()
        assertNull(parameters.copy(simulations = 56).decisionView().limit)
        assertNull(parameters.copy(simulations = 64).decisionView().limit)
        assertEquals(128, parameters.copy(simulations = 65).decisionView().limit)
        assertEquals(128, parameters.copy(simulations = 186).decisionView().limit)
        assertNull(parameters.copy(simulations = 186).decisionView(widen = false).limit)
        assertEquals(128, parameters.copy(simulations = 256).decisionView().limit)
        assertEquals(256, parameters.copy(simulations = 257).decisionView().limit)
        assertEquals(32, parameters.copy(simulations = 1, initialExpansionLimit = 32).decisionView().limit)
        val prior = object : org.mtgallium.agent.infoset.planning.SearchPrior {
            override val configurationId = "host-menu-test"
            override val candidateLimit = 96
            override val admission = org.mtgallium.agent.infoset.core.MenuSource.PRODUCTION
            override val explorationConstant = 1.0
            override fun probabilities(context: DecisionContext): Map<String, Double> = error("unused")
        }
        assertEquals(96, parameters.copy(simulations = 1025).decisionView(prior).limit)
        assertEquals(prior.admission, parameters.decisionView(prior).admission)
    }

    @Test
    fun `policy parameters reject invalid search budgets`() {
        assertFailsWith<IllegalArgumentException> {
            SearchPolicyConfig().copy(simulations = 0)
        }
    }

    @Test
    fun `policy singleton selection retains the action without claiming a search or rules authority`() {
        val pass = choice("Pass priority", SemanticOperationFamily.PASS_PRIORITY)
        val menu = ActionMenu(
            candidates = listOf(pass),
            isExhaustive = false,
            estimatedCandidateCount = null,
            proposalVersion = "profile-singleton-test-v1",
            isProfileExhaustive = true,
            omissionReasons = setOf(ActionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA),
        )
        val selected = rootSelection(menu)
        assertEquals(pass, selected.choice)
        assertIs<RootActionSelection.PolicySingletonAction>(selected)
        assertIs<RootActionSelection.Unsearched>(selected)

        val enabled = SearchPolicyConfig(
            singletonSelection = SingletonMenuShortcutConfig(enabled = true),
        )
        assertTrue(enabled.singletonSelection.enabled)
        assertEquals(false, SearchPolicyConfig().singletonSelection.enabled)
    }

    @Test
    fun `singleton selection refuses bounded enumeration responses and pregame choices`() {
        val pass = choice("Pass priority", SemanticOperationFamily.PASS_PRIORITY)
        val exact = ActionMenu(listOf(pass), true, 1, "singleton-test-v1")
        ActionOmissionReason.entries.filterNot { it.intentionalProfileOmission }.forEach { omission ->
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
        val profiled = ActionMenu(
            candidates = listOf(pass),
            isExhaustive = false,
            estimatedCandidateCount = null,
            proposalVersion = "profile-singleton-test-v1",
            isProfileExhaustive = true,
            omissionReasons = setOf(
                ActionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA
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
        val menu = ActionMenu(
            candidates = listOf(pass, land),
            isExhaustive = true,
            estimatedCandidateCount = 2,
            proposalVersion = "land-hold-test-v1",
        )

        assertIs<RootActionSelection.DirectPolicyAction>(rootSelection(menu))
    }

    private fun choice(label: String, family: SemanticOperationFamily): SemanticChoice = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION,
        operationFamily = family,
        display = SemanticChoiceDisplay(label),
        canonicalPayload = buildJsonObject { put("action", JsonPrimitive(label)) },
    )

    private fun rootSelection(menu: ActionMenu): RootActionSelection {
        val fallback = menu.candidates.first()
        val direct = object : DecisionPolicy {
            override val configurationId = "selector-test"
            override fun choose(context: DecisionContext, decisionSeed: Long) = fallback
        }
        return RootActionSelector(true, direct).select(
            DecisionContext.capture("p0", menu, { error("Information was forced") }), 0L
        ) { error("Direct policy should run when automatic selection does not apply") }
    }
}
