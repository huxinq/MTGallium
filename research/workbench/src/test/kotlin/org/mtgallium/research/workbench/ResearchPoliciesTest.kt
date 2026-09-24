package org.mtgallium.research.workbench

import kotlin.test.*
import kotlinx.serialization.json.*
import org.mtgallium.agent.monored.LinearValueEvaluator
import org.mtgallium.agent.monored.LinearValueLink
import org.mtgallium.agent.monored.LinearWeights
import org.mtgallium.agent.monored.MonoRedInformationEvaluator
import org.mtgallium.agent.monored.ValueFeatures

class ResearchPoliciesTest {
    private val registry by lazy(::buildRegistry)

    private fun advanceToSearchDecision(game: PythonGame) {
        repeat(64) {
            val expansion = game.world.decisionContext().expansion
            if (expansion.candidates.size > 1) return
            check(game.world.step(expansion.candidates.single()).accepted)
        }
        error("No multi-action decision reached")
    }

    @Test fun `generic search honors the explicit value link`() {
        val deck = mapOf("Mountain" to 30, "Shock" to 30)
        val weights = LinearWeights(bias = 0.3)
        val game = PythonGame.create(GamesPlan(decks = listOf(deck, deck),
            startingHandSize = 7, skipMulligans = true, seed = 70,
            particles = 1, simulations = 2, searchDepth = 1,
            valueWeights = weights, valueLink = LinearValueLink.TANH), registry, "linked-search")
        advanceToSearchDecision(game)
        val diagnostics = game.select("search", 904_333L).getValue("search").jsonObject
            .getValue("diagnostics").jsonObject
        assertEquals(LinearValueEvaluator(weights, LinearValueLink.TANH).configurationId,
            diagnostics.getValue("evaluatorConfigurationId").jsonPrimitive.content)
    }

    @Test fun `value snapshot matches direct feature and V2 evaluation for both players`() {
        val game = PythonGame.create(GamesPlan(
            decks = List(2) { mapOf("Mountain" to 8) }, startingHandSize = 2,
            skipMulligans = true, seed = 69,
        ), registry, "value-snapshot")
        val snapshot = game.valueSnapshot()

        listOf("p0", "p1").forEach { player ->
            val information = game.world.informationState(player)
            val actual = snapshot.getValue(player).jsonObject
            val expectedFeatures = researchJson.encodeToJsonElement(
                ValueFeatures.compile(information, player).values)

            assertEquals(expectedFeatures, actual.getValue("features"))
            assertEquals(MonoRedInformationEvaluator.evaluate(information, player),
                actual.getValue("v2").jsonPrimitive.double, 1e-12)
            assertEquals(information.observation.turnNumber,
                actual.getValue("turn").jsonPrimitive.int)
        }
    }

    private fun publicPlan(seed: Long = 81) = GamesPlan(
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
        assertEquals(plainGame.world.authoritativeFingerprint(), comparedGame.world.authoritativeFingerprint())
        assertEquals(0, compared.getValue("changedDecisions").jsonPrimitive.int)
        assertTrue(compared.getValue("candidateDecisions").jsonPrimitive.int > 0)
        assertEquals(GameStatus.DECISION_LIMIT, result.status)
        assertNull(result.payoffs)
    }
}
