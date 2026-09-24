package org.mtgallium.research.workbench

import kotlin.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.mtgallium.agent.argentum.policy.*
import org.mtgallium.agent.infoset.core.*

@Serializable
private data class TestPolicySettings(val testChoiceIndex: Int = 0)

/** Registered in test resources, as a private module would register its policies. */
class TestNativePolicies : NativePolicyProvider {
    override val policies = setOf("test-index", "test-search")
    override val settings = settingNames(TestPolicySettings.serializer())

    override fun create(name: String, game: NativePolicyContext, actor: String): NativePolicy = when (name) {
        "test-index" -> {
            val index = game.settings(TestPolicySettings.serializer()).testChoiceIndex
            NativePolicy.Direct(Player { context, _ -> context.expansion.candidates[index] })
        }
        else -> NativePolicy.Search(SearchPolicySession(game.world, actor, game.knownDecks,
            SearchPolicyConfig(1, 2, 1, 1.0, LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT),
                game.plan.actionProfile, baseSeed = game.plan.seed), game.opponentModel(), game.gameId))
    }
}

class NativePoliciesTest {
    private val registry by lazy(::buildRegistry)
    private val deck = buildJsonObject { put("Mountain", 30); put("Shock", 30) }

    private fun plan(vararg settings: Pair<String, JsonElement>) = JsonObject(mapOf(
        "decks" to JsonArray(listOf(deck, deck)), "startingHandSize" to JsonPrimitive(7),
        "skipMulligans" to JsonPrimitive(true), "seed" to JsonPrimitive(72)) + settings)

    private fun advanceToChoice(game: PythonGame) {
        repeat(64) {
            val expansion = game.world.decisionContext().expansion
            if (expansion.candidates.size > 1) return
            check(game.world.step(expansion.candidates.single()).accepted)
        }
        error("No multi-action decision reached")
    }

    private fun policies(vararg names: String) = "policies" to JsonArray(names.map(::JsonPrimitive))

    @Test fun `an added policy reads its setting from beside the plan fields`() {
        val plan = decodeGamesPlan(plan(policies("test-index", "random"), "testChoiceIndex" to JsonPrimitive(1)))
        assertEquals(mapOf("testChoiceIndex" to JsonPrimitive(1)), plan.extensions)
        val game = PythonGame.create(plan, registry, "added")
        advanceToChoice(game)
        val expected = game.world.decisionContext(DecisionView()).expansion.candidates[1]
        val selected = game.select("test-index", 5)
        assertEquals(expected.signature,
            selected.getValue("choice").jsonObject.getValue("signature").jsonPrimitive.content)
        assertEquals(JsonNull, selected.getValue("search"))
    }

    @Test fun `an unclaimed plan setting fails at game creation`() {
        val plan = decodeGamesPlan(plan(policies("random", "random"), "testChoiceIndx" to JsonPrimitive(1)))
        val failure = assertFailsWith<IllegalArgumentException> { PythonGame.create(plan, registry, "misspelled") }
        assertContains(failure.message.orEmpty(), "testChoiceIndx")
    }

    @Test fun `a setting given both beside and inside extensions is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            decodeGamesPlan(plan("testChoiceIndex" to JsonPrimitive(1),
                "extensions" to buildJsonObject { put("testChoiceIndex", 2) }))
        }
    }

    @Test fun `an added search policy keeps shadow memory through moves and forks`() {
        val game = PythonGame.create(decodeGamesPlan(plan(policies("random", "random"),
            "shadowPolicies" to JsonArray(listOf(JsonPrimitive("test-search"))))), registry, "shadow")
        // Accepted moves reach the shadow sessions; a fork requires them to be current.
        repeat(64) {
            val menu = game.world.decisionContext(DecisionView()).expansion.candidates
            if (menu.size > 1) return@repeat
            game.step(game.world.acceptedDecisionCountForHost, DecisionView(), menu.single(), record = false)
        }
        val child = game.fork()
        val parent = game.select("test-search", 11)
        assertIs<JsonObject>(parent.getValue("search"))
        assertEquals(parent.getValue("choice"), child.select("test-search", 11).getValue("choice"))
    }

    @Test fun `unknown policy names fail at game creation, including shadows`() {
        for (settings in listOf(arrayOf(policies("missing", "random")),
            arrayOf(policies("random", "random"), "shadowPolicies" to JsonArray(listOf(JsonPrimitive("missing")))))) {
            val failure = assertFails { PythonGame.create(decodeGamesPlan(plan(*settings)), registry, "unknown") }
            assertContains(failure.message.orEmpty(), "Unknown native policy 'missing'")
        }
    }

    @Test fun `providers may not share a policy name or name a plan field`() {
        fun provider(names: Set<String>, claimed: Set<String> = emptySet()) = object : NativePolicyProvider {
            override val policies = names
            override val settings = claimed
            override fun create(name: String, game: NativePolicyContext, actor: String) = error("unused")
        }
        assertFailsWith<IllegalStateException> { NativePolicies(listOf(BuiltinNativePolicies, provider(setOf("search")))) }
        assertFailsWith<IllegalStateException> { NativePolicies(listOf(provider(setOf("a"), setOf("seed")))) }
        assertEquals(setOf("x"), NativePolicies(listOf(provider(setOf("a"), setOf("x")), provider(setOf("b"), setOf("x")))).settings)
    }

    @Test fun `installed policies include built-in and service-registered providers`() {
        val installed = NativePolicies.installed.providers.map { it.javaClass }
        assertEquals(listOf(BuiltinNativePolicies.javaClass, TestNativePolicies::class.java), installed)
    }
}
