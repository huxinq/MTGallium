package org.mtgallium.research.workbench

import kotlin.test.*
import kotlinx.serialization.json.*
import org.mtgallium.agent.value.LinearValueEvaluator
import org.mtgallium.agent.value.InverseLink
import org.mtgallium.agent.value.LinearWeights
import org.mtgallium.agent.value.MaterialEvaluator
import org.mtgallium.agent.value.ValueFeatures

class ResearchPoliciesTest {
    private val registry by lazy(::buildRegistry)

    private fun advanceToSearchDecision(game: PythonGame) {
        repeat(64) {
            val menu = game.world.decisionContext().menu
            if (menu.candidates.size > 1) return
            check(game.world.step(menu.candidates.single()).accepted)
        }
        error("No multi-action decision reached")
    }

    @Test fun `generic search honors the explicit value link`() {
        val deck = mapOf("Mountain" to 30, "Shock" to 30)
        val weights = LinearWeights(bias = 0.3)
        val game = PythonGame.create(ResearchGameConfig(decks = listOf(deck, deck),
            startingHandSize = 7, skipMulligans = true, seed = 70,
            particles = 1, simulations = 2, searchDepth = 1,
            valueWeights = weights, valueLink = InverseLink.TANH), registry, "linked-search")
        advanceToSearchDecision(game)
        val diagnostics = game.select("search", 904_333L).getValue("search").jsonObject
            .getValue("diagnostics").jsonObject
        assertEquals(LinearValueEvaluator(weights, InverseLink.TANH).configurationId,
            diagnostics.getValue("evaluatorConfigurationId").jsonPrimitive.content)
    }

    @Test fun `value snapshot matches direct feature and V2 evaluation for both players`() {
        val game = PythonGame.create(ResearchGameConfig(
            decks = List(2) { mapOf("Mountain" to 8) }, startingHandSize = 2,
            skipMulligans = true, seed = 69,
        ), registry, "value-snapshot")
        val snapshot = game.valueSnapshot()
        val factual = game.valueSnapshot(org.mtgallium.agent.neural.ByteTokenSchema())

        listOf("p0", "p1").forEach { player ->
            val information = game.world.informationState(player)
            val actual = snapshot.getValue(player).jsonObject
            assertFalse("view" in actual)
            assertEquals(researchJson.encodeToJsonElement(
                org.mtgallium.agent.neural.InformationStateByteEncoder().view(information)),
                factual.getValue(player).jsonObject.getValue("view"))
            val expectedFeatures = researchJson.encodeToJsonElement(
                ValueFeatures.compile(information, player).values)

            assertEquals(expectedFeatures, actual.getValue("features"))
            assertEquals(MaterialEvaluator().evaluate(information, player),
                actual.getValue("v2").jsonPrimitive.double, 1e-12)
            assertEquals(information.observation.turnNumber,
                actual.getValue("turn").jsonPrimitive.int)
        }
    }

    private fun publicPlan(seed: Long = 81) = ResearchGameConfig(
        decks = List(2) { mapOf("Mountain" to 1) }, policies = listOf("production", "production"),
        seed = seed, startingHandSize = 0, skipMulligans = true,
    )

    @Test fun `compare counts production decisions without changing the native trajectory`() {
        val plan = publicPlan()
        val comparedGame = PythonGame.create(plan, registry, "compare")
        val plainGame = PythonGame.create(plan, registry, "plain")
        val compared = comparedGame.compare("p0", "production", 2, null)
        val plain = plainGame.play(listOf("production", "production"), 2, null)
        val result = researchJson.decodeFromJsonElement<GameResult>(compared.getValue("result"))

        assertEquals(plain, result)
        assertEquals(plainGame.world.stateFingerprint(), comparedGame.world.stateFingerprint())
        assertEquals(0, compared.getValue("changedDecisions").jsonPrimitive.int)
        assertTrue(compared.getValue("candidateDecisions").jsonPrimitive.int > 0)
        assertEquals(GameStatus.DECISION_LIMIT, result.status)
        assertNull(result.payoffs)
    }
}
