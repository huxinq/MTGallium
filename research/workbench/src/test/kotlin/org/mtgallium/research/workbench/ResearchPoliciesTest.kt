package org.mtgallium.research.workbench

import kotlin.test.*
import kotlinx.serialization.json.*

class ResearchPoliciesTest {
    private val registry by lazy(::buildRegistry)
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
