package org.mtgallium.agent.searchteacher

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.PolicyZoneKnowledge
import org.mtgallium.agent.infoset.core.PolicyZoneView

class MonoRedTacticalPriorTest {
    private val evaluator = MonoRedTacticalEvaluator()

    @Test
    fun `known opponent strike cannot borrow a second mana from unrelated unknown cards`() {
        val strike = evaluate(known = mapOf("Lightning Strike" to 1))
        val unrelatedPool = evaluate(
            known = mapOf("Lightning Strike" to 1),
            unlocated = mapOf("Shock" to 4),
        )

        assertEquals(0.0, strike.components.getValue("opponentExpectedBurnNow"))
        assertEquals(0.0, strike.components.getValue("phiReach"))
        assertEquals(strike.components, unrelatedPool.components)
    }

    @Test
    fun `two certain unknown shocks share one current red mana budget`() {
        val uncertain = evaluate(handSize = 2, unlocated = mapOf("Shock" to 2))
        val known = evaluate(known = mapOf("Shock" to 2))

        assertEquals(2.0, uncertain.components.getValue("opponentExpectedBurnNow"))
        assertEquals(known.components, uncertain.components)
        assertEquals(known.value, uncertain.value)
    }

    @Test
    fun `collapsing hand uncertainty preserves utility for creatures and burn removal options`() {
        for (name in listOf("Hired Claw", "Shock", "Lightning Strike")) {
            val uncertain = evaluate(handSize = 1, unlocated = mapOf(name to 1))
            val known = evaluate(known = mapOf(name to 1))

            assertEquals(known.components.getValue("phiHand"), uncertain.components.getValue("phiHand"), name)
            assertTrue(uncertain.components.getValue("phiHand") < 0.0, name)
            assertEquals(known.value, uncertain.value, name)
        }
    }

    @Test
    fun `lethal and reserve utilities average over possible hands before nonlinear output`() {
        val uncertain = evaluate(
            handSize = 1,
            unlocated = mapOf("Shock" to 1, "Mountain" to 1),
            rootLife = 2,
        )
        val shock = evaluate(known = mapOf("Shock" to 1), rootLife = 2)
        val land = evaluate(known = mapOf("Mountain" to 1), rootLife = 2)

        for (component in listOf("phiLethal", "phiReach", "phiHand")) {
            assertEquals(
                (shock.components.getValue(component) + land.components.getValue(component)) / 2.0,
                uncertain.components.getValue(component),
                1e-12,
                component,
            )
        }
        assertEquals(1.0, uncertain.components.getValue("opponentExpectedBurnNow"))
        fun sigmoid(value: Double): Double = 1.0 / (1.0 + exp(-value))
        val treatingExpectedDamageAsFact = sigmoid((0.0 - 20.0 + 0.5) / 1.25) -
            sigmoid((1.0 - 2.0 + 0.5) / 1.25)
        assertTrue(abs(uncertain.components.getValue("phiLethal") - treatingExpectedDamageAsFact) > 1e-4)
    }

    @Test
    fun `known and unknown burn compete in the same affordability calculation`() {
        val mixed = evaluate(
            known = mapOf("Shock" to 1),
            handSize = 2,
            unlocated = mapOf("Shock" to 1),
        )
        val known = evaluate(known = mapOf("Shock" to 2))

        assertEquals(2.0, mixed.components.getValue("opponentExpectedBurnNow"))
        assertEquals(known.components, mixed.components)
    }

    @Test
    fun `insufficient unknown pool is reported without inventing burn or producing nan`() {
        val result = evaluate(handSize = 2, unlocated = mapOf("Shock" to 1))

        assertTrue("opponent-prior-missing" in result.flags)
        assertEquals(0.0, result.components.getValue("opponentExpectedBurnNow"))
        assertTrue(result.value.isFinite())
    }

    private fun evaluate(
        known: Map<String, Int> = emptyMap(),
        handSize: Int = known.values.sum(),
        unlocated: Map<String, Int> = emptyMap(),
        rootLife: Int = 20,
    ): TacticalEvaluationResult {
        val mountain = PolicyCardView(
            objectRef = "opponent-mountain",
            definitionId = "fixture:mountain",
            name = "Mountain",
            zone = "BATTLEFIELD",
            ownerId = "p1",
            controllerId = "p1",
            types = setOf("LAND"),
            subtypes = setOf("Mountain"),
            colors = emptySet(),
            keywords = emptySet(),
            manaCost = "",
            manaValue = 0,
            oracleText = "",
            power = null,
            toughness = null,
            tapped = false,
            summoningSick = false,
            faceDown = false,
            damageMarked = 0,
            counters = emptyMap(),
            attachedTo = null,
            attachments = emptyList(),
        )
        val observation = PolicyObservation(
            perspectivePlayerId = "p0",
            turnNumber = 4,
            phase = "PRECOMBAT_MAIN",
            step = "PRECOMBAT_MAIN",
            activePlayerId = "p0",
            priorityPlayerId = "p0",
            players = listOf(
                PolicyPlayerView("p0", "Root", rootLife, 0, 0, 0, 0, PolicyManaPool(), true, true, false),
                PolicyPlayerView(
                    "p1", "Opponent", 20, handSize,
                    (unlocated.values.sum() - handSize + known.values.sum()).coerceAtLeast(0),
                    0, 0, PolicyManaPool(), false, false, false,
                ),
            ),
            zones = listOf(
                PolicyZoneView("p0", "HAND", hidden = true, size = 0, cards = emptyList()),
                // Exact knowledge is carried separately; no opponent hand identities in the observation.
                PolicyZoneView("p1", "HAND", hidden = true, size = handSize, cards = emptyList()),
                PolicyZoneView("p1", "BATTLEFIELD", hidden = false, size = 1, cards = listOf(mountain)),
            ),
            stack = emptyList(),
            pendingDecision = null,
            observationDigest = "synthetic-prior-observation",
        )
        val information = PolicyInformationState(
            actingPlayerId = "p0",
            observation = observation,
            informationStateDigest = "synthetic-prior-information",
            historyCommitment = PolicyHistoryCommitment.empty(),
            history = emptyList(),
            knowledge = PolicyKnowledgeState(
                perspectivePlayerId = "p0",
                zones = listOf(PolicyZoneKnowledge("p1", "HAND", handSize, known)),
                unlocatedCardCounts = mapOf("p1" to unlocated),
                knowledgeDigest = "synthetic-prior-knowledge",
            ),
            candidates = emptyList(),
            terminated = false,
        )
        return evaluator.evaluateDetailed(information, "p0")
    }
}
