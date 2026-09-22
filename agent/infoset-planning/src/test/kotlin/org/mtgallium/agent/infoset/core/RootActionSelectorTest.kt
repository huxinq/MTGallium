package org.mtgallium.agent.infoset.core

import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RootActionSelectorTest {
    private val first = choice("first")
    private val second = choice("second")
    private val menu = expansion(listOf(first, second))
    private val request = DecisionSiteRequest.capture("p0", menu, { error("Information was forced") })

    @Test fun `direct dispatch uses declared policy and seed without constructing search or forcing information`() {
        var calls = 0
        val direct = object : DecisionPolicy {
            override val configurationId = "declared-direct"
            override fun choose(context: DecisionSiteRequest, decisionSeed: Long): SemanticChoice {
                calls++
                assertSame(request, context)
                assertEquals(menu, context.expansion)
                assertEquals(71L, decisionSeed)
                return second
            }
        }
        val result = RootActionSelector(false, direct).select(request, 71L) { error("Unexpected fallback") }
        assertIs<RootActionSelection.DirectPolicyAction>(result)
        assertSame(second, result.choice)
        assertEquals(1, calls)
    }

    @Test fun `outside scope calls lazy declared fallback exactly once`() {
        var directCalls = 0
        var fallbackCalls = 0
        val fallback = RootActionSelection.DirectPolicyAction(first)
        val result = RootActionSelector(false, direct { directCalls++; null }).select(request, 1L) {
            fallbackCalls++
            fallback
        }
        assertSame(fallback, result)
        assertEquals(1, directCalls)
        assertEquals(1, fallbackCalls)
    }

    @Test fun `rules forced pass and enabled singleton precede direct choice`() {
        val forbidden = direct { error("Direct called before automatic selection") }
        val pass = choice("pass", SemanticOperationFamily.PASS_PRIORITY)
        for (enabled in listOf(false, true)) {
            assertIs<RootActionSelection.RulesForcedPass>(
                RootActionSelector(enabled, forbidden).select(request(expansion(listOf(pass))), 1L) {
                    error("Forced pass fell back")
                })
        }
        assertIs<RootActionSelection.PolicySingletonAction>(
            RootActionSelector(true, forbidden).select(request(expansion(listOf(first))), 1L) {
                error("Enabled singleton fell back")
            })
        assertIs<RootActionSelection.DirectPolicyAction>(
            RootActionSelector(false, direct { first }).select(request(expansion(listOf(first))), 1L) {
                error("Direct singleton fell back")
            })
    }

    @Test fun `non admitted and throwing direct choices refuse without fallback`() {
        var fallbackCalls = 0
        assertFailsWith<IllegalArgumentException> {
            RootActionSelector(false, direct { choice("not-admitted") }).select(request, 1L) {
                fallbackCalls++; error("Fallback called")
            }
        }
        val failure = IllegalStateException("Direct policy failure")
        val actual = assertFailsWith<IllegalStateException> {
            RootActionSelector(false, direct { throw failure }).select(request, 1L) {
                fallbackCalls++; error("Fallback called")
            }
        }
        assertSame(failure, actual)
        assertEquals(0, fallbackCalls)
    }

    private fun direct(choose: () -> SemanticChoice?) = object : DecisionPolicy {
        override val configurationId = "test-direct"
        override fun choose(context: DecisionSiteRequest, decisionSeed: Long) = choose()
    }

    private fun request(expansion: PolicyExpansion) = DecisionSiteRequest.capture("p0", expansion, { error("Information was forced") })
    private fun expansion(choices: List<SemanticChoice>) = PolicyExpansion(choices, true, choices.size.toLong(), "test-v1")
    private fun choice(label: String, family: SemanticOperationFamily = SemanticOperationFamily.OTHER) = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION, operationFamily = family, display = SemanticChoiceDisplay(label),
        canonicalPayload = JsonObject(mapOf("choice" to JsonPrimitive(label))),
    )
}
