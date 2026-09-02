package org.mtgallium.agent.searchteacher

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.PolicyZoneView

class MonoRedTacticalEvaluatorTest {
    private val evaluator = MonoRedTacticalEvaluator()

    @Test
    fun `visible evaluator values early development without making every land drop mandatory`() {
        fun lands(count: Int, zone: String) = List(count) { index ->
            card("land-$zone-$index", "Mountain", zone = zone, types = setOf("LAND"))
        }
        val heldFirstLand = state(rootHand = lands(7, "HAND"), rootBattlefield = emptyList())
        val developedFirstLand = state(
            rootHand = lands(6, "HAND"),
            rootBattlefield = lands(1, "BATTLEFIELD"),
        )

        assertTrue(
            MonoRedInformationEvaluator.evaluate(developedFirstLand, "p0") >
                MonoRedInformationEvaluator.evaluate(heldFirstLand, "p0"),
        )
        val firstLandMarginal = MonoRedInformationEvaluator.developedManaValue(1)
        val sixthLandMarginal = MonoRedInformationEvaluator.developedManaValue(6) -
            MonoRedInformationEvaluator.developedManaValue(5)
        assertTrue(firstLandMarginal > 0.35, "Early mana development should offset moving a card from hand")
        assertTrue(sixthLandMarginal < 0.35, "An excess land may remain more valuable in hand")
    }

    @Test
    fun `visible evaluator formula remains bit exact across its material features`() {
        val information = state(
            rootLife = 14,
            opponentLife = 11,
            rootHand = listOf(
                card("root-hand-1", "Shock", zone = "HAND"),
                card("root-hand-2", "Mountain", zone = "HAND", types = setOf("LAND")),
            ),
            opponentHidden = listOf(card("opponent-hand", "Shock", zone = "HAND", owner = "p1")),
            rootBattlefield = listOf(
                card("root-land", "Mountain", zone = "BATTLEFIELD", types = setOf("LAND")),
                card(
                    "root-creature",
                    "Hasty Creature",
                    zone = "BATTLEFIELD",
                    types = setOf("CREATURE"),
                    power = 3,
                    toughness = 2,
                    keywords = setOf("HASTE"),
                ),
            ),
            opponentBattlefield = listOf(
                card("opponent-land-1", "Mountain", zone = "BATTLEFIELD", owner = "p1", types = setOf("LAND")),
                card("opponent-land-2", "Mountain", zone = "BATTLEFIELD", owner = "p1", types = setOf("LAND")),
                card(
                    "opponent-creature",
                    "Blocking Creature",
                    zone = "BATTLEFIELD",
                    owner = "p1",
                    types = setOf("CREATURE"),
                    power = 2,
                    toughness = 3,
                ),
            ),
        )

        assertEquals(
            4_593_270_064_458_002_125L,
            MonoRedInformationEvaluator.evaluate(information, "p0").toBits(),
        )
    }

    @Test
    fun `perspective binding fails closed`() {
        val state = state()
        assertFailsWith<IllegalArgumentException> { evaluator.evaluate(state, "p1") }
    }

    @Test
    fun `terminal states cannot impersonate the search terminal contract`() {
        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(state().copy(terminated = true, winnerId = "p0"), "p0")
        }
    }

    @Test
    fun `opponent hidden identities object refs and order do not affect value`() {
        val shock = card("hidden-a", "Shock", zone = "HAND", owner = "p1")
        val mountain = card("hidden-b", "Mountain", zone = "HAND", owner = "p1", types = setOf("LAND"))
        val first = state(opponentHidden = listOf(shock, mountain))
        val second = state(
            opponentHidden = listOf(
                mountain.copy(objectRef = "randomized-99"),
                shock.copy(objectRef = "randomized-12"),
            )
        )

        assertEquals(evaluator.evaluateDetailed(first, "p0"), evaluator.evaluateDetailed(second, "p0"))
    }

    @Test
    fun `root known burn near lethal outranks an equal hand-count land`() {
        val burn = evaluator.evaluate(state(rootHand = listOf(card("hand", "Shock", zone = "HAND"))), "p0")
        val land = evaluator.evaluate(
            state(rootHand = listOf(card("hand", "Mountain", zone = "HAND", types = setOf("LAND")))),
            "p0",
        )

        assertTrue(burn > land)
    }

    @Test
    fun `ready attacker outranks the same tapped attacker`() {
        val ready = creature("creature", tapped = false, summoningSick = false)
        val tapped = ready.copy(tapped = true)

        assertTrue(
            evaluator.evaluate(state(rootBattlefield = listOf(ready)), "p0") >
                evaluator.evaluate(state(rootBattlefield = listOf(tapped)), "p0")
        )
    }

    @Test
    fun `life utility has a larger marginal close to zero`() {
        fun value(life: Int) = evaluator.evaluate(state(rootLife = life, opponentLife = 20), "p0")
        val lowLifeMarginal = value(2) - value(1)
        val highLifeMarginal = value(20) - value(19)

        assertTrue(lowLifeMarginal > highLifeMarginal)
    }

    @Test
    fun `nonterminal scores stay strictly below proof values`() {
        listOf(
            state(rootLife = 40, opponentLife = 1, rootBattlefield = List(8) { creature("c$it", power = 8) }),
            state(rootLife = 1, opponentLife = 40, opponentBattlefield = List(8) { creature("o$it", owner = "p1", power = 8) }),
        ).forEach { candidate ->
            assertTrue(abs(evaluator.evaluate(candidate, "p0")) < 0.95)
        }
    }

    @Test
    fun `temperature changes magnitude but preserves ordering`() {
        val candidate = state(rootHand = listOf(card("hand", "Shock", zone = "HAND")))
        val cold = MonoRedTacticalEvaluator(
            MonoRedTacticalEvaluatorSettings(outputTemperature = 1.0)
        ).evaluate(candidate, "p0")
        val standard = evaluator.evaluate(candidate, "p0")
        val warm = MonoRedTacticalEvaluator(
            MonoRedTacticalEvaluatorSettings(outputTemperature = 4.0)
        ).evaluate(candidate, "p0")

        assertTrue(cold > standard && standard > warm && warm > 0.0)
    }

    private fun state(
        rootLife: Int = 3,
        opponentLife: Int = 3,
        rootHand: List<PolicyCardView> = emptyList(),
        opponentHidden: List<PolicyCardView> = emptyList(),
        rootBattlefield: List<PolicyCardView> = listOf(
            card("mountain", "Mountain", zone = "BATTLEFIELD", types = setOf("LAND"))
        ),
        opponentBattlefield: List<PolicyCardView> = emptyList(),
    ): PolicyInformationState {
        val observation = PolicyObservation(
            perspectivePlayerId = "p0",
            turnNumber = 4,
            phase = "PRECOMBAT_MAIN",
            step = "PRECOMBAT_MAIN",
            activePlayerId = "p0",
            priorityPlayerId = "p0",
            players = listOf(
                PolicyPlayerView(
                    "p0", "Root", rootLife, rootHand.size, 40, 0, 0,
                    PolicyManaPool(), active = true, priority = true, lost = false,
                ),
                PolicyPlayerView(
                    "p1", "Opponent", opponentLife, opponentHidden.size, 40, 0, 0,
                    PolicyManaPool(), active = false, priority = false, lost = false,
                ),
            ),
            zones = listOf(
                PolicyZoneView("p0", "HAND", hidden = true, rootHand.size, rootHand),
                PolicyZoneView("p1", "HAND", hidden = true, opponentHidden.size, opponentHidden),
                PolicyZoneView("p0", "BATTLEFIELD", hidden = false, rootBattlefield.size, rootBattlefield),
                PolicyZoneView("p1", "BATTLEFIELD", hidden = false, opponentBattlefield.size, opponentBattlefield),
            ),
            stack = emptyList(),
            pendingDecision = null,
            observationDigest = "fixture-observation",
        )
        return PolicyInformationState(
            actingPlayerId = "p0",
            observation = observation,
            informationStateDigest = "fixture-information",
            historyCommitment = PolicyHistoryCommitment.empty(),
            history = emptyList(),
            knowledge = PolicyKnowledgeState.empty("p0"),
            candidates = emptyList(),
            terminated = false,
        )
    }

    private fun creature(
        ref: String,
        owner: String = "p0",
        power: Int = 2,
        tapped: Boolean = false,
        summoningSick: Boolean = false,
    ): PolicyCardView = card(
        ref = ref,
        name = "Test Creature",
        zone = "BATTLEFIELD",
        owner = owner,
        types = setOf("CREATURE"),
        power = power,
        toughness = 2,
        tapped = tapped,
        summoningSick = summoningSick,
    )

    private fun card(
        ref: String,
        name: String,
        zone: String,
        owner: String = "p0",
        types: Set<String> = emptySet(),
        keywords: Set<String> = emptySet(),
        power: Int? = null,
        toughness: Int? = null,
        tapped: Boolean = false,
        summoningSick: Boolean = false,
    ): PolicyCardView = PolicyCardView(
        objectRef = ref,
        definitionId = "fixture:${name.lowercase().replace(' ', '-')}",
        name = name,
        zone = zone,
        ownerId = owner,
        controllerId = owner,
        types = types,
        subtypes = emptySet(),
        colors = emptySet(),
        keywords = keywords,
        manaCost = "",
        manaValue = if (name == "Shock") 1 else 0,
        oracleText = "",
        power = power,
        toughness = toughness,
        tapped = tapped,
        summoningSick = summoningSick,
        faceDown = false,
        damageMarked = 0,
        counters = emptyMap(),
        attachedTo = null,
        attachments = emptyList(),
    )
}
