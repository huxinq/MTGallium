package org.mtgallium.agent.searchteacher

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.PolicyAttackerView
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyCombatView
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.PolicyZoneView

class MonoRedTacticalEvaluatorSchema2Test {
    private val evaluator = MonoRedTacticalEvaluatorSchema2()

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
        val cold = MonoRedTacticalEvaluatorSchema2(
            MonoRedTacticalEvaluatorSchema2Settings(outputTemperature = 1.0)
        ).evaluate(candidate, "p0")
        val standard = evaluator.evaluate(candidate, "p0")
        val warm = MonoRedTacticalEvaluatorSchema2(
            MonoRedTacticalEvaluatorSchema2Settings(outputTemperature = 4.0)
        ).evaluate(candidate, "p0")

        assertTrue(cold > standard && standard > warm && warm > 0.0)
    }

    @Test
    fun `durable mana rewards developed empty hand and is stable when land taps`() {
        val developed = state(
            rootBattlefield = listOf(card("m", "Mountain", "BATTLEFIELD", types = setOf("LAND"))),
        )
        val held = state(rootBattlefield = emptyList())
        val tapped = state(
            rootBattlefield = listOf(card("m", "Mountain", "BATTLEFIELD", types = setOf("LAND"), tapped = true)),
        )
        assertTrue(evaluator.evaluateDetailed(developed, "p0").components.getValue("phiDurableMana") > 0.0)
        assertEquals(
            evaluator.evaluateDetailed(developed, "p0").components.getValue("phiDurableMana"),
            evaluator.evaluateDetailed(tapped, "p0").components.getValue("phiDurableMana"),
        )
        assertTrue(evaluator.evaluate(developed, "p0") > evaluator.evaluate(held, "p0"))
    }

    @Test
    fun `one generic source and rockface cannot cast red burn`() {
        val hand = listOf(card("shock", "Shock", "HAND"))
        val sanctuary = state(
            rootHand = hand,
            rootBattlefield = listOf(card("s", "Soulstone Sanctuary", "BATTLEFIELD", types = setOf("LAND"))),
        )
        val rockface = state(
            rootHand = hand,
            rootBattlefield = listOf(card("r", "Rockface Village", "BATTLEFIELD", types = setOf("LAND"))),
        )
        assertEquals(0.0, evaluator.evaluateDetailed(sanctuary, "p0").components.getValue("rootBurnNow"))
        assertEquals(0.0, evaluator.evaluateDetailed(rockface, "p0").components.getValue("rootBurnNow"))
    }

    @Test
    fun `restricted mana and summoning-sick creature lands are not ordinary sources`() {
        val restrictedOnly = state(
            rootHand = listOf(card("shock", "Shock", "HAND")),
            rootBattlefield = emptyList(),
            rootMana = PolicyManaPool(
                restricted = listOf(
                    org.mtgallium.agent.infoset.core.PolicyRestrictedMana(
                        color = "R",
                        spendRestriction = "creature spells only",
                        count = 1,
                    ),
                ),
            ),
        )
        val sickCreatureLand = state(
            rootHand = listOf(card("shock", "Shock", "HAND")),
            rootBattlefield = listOf(
                card(
                    "land",
                    "Mountain",
                    "BATTLEFIELD",
                    types = setOf("LAND", "CREATURE"),
                    summoningSick = true,
                ),
            ),
        )

        assertEquals(0.0, evaluator.evaluateDetailed(restrictedOnly, "p0").components.getValue("rootBurnNow"))
        assertTrue(evaluator.evaluateDetailed(restrictedOnly, "p0").flags.contains("restricted-mana-omitted"))
        assertEquals(0.0, evaluator.evaluateDetailed(sickCreatureLand, "p0").components.getValue("rootBurnNow"))
    }

    @Test
    fun `represented attackers exclude ready creatures after attackers are committed`() {
        val attacker = creature("attacker", power = 2, tapped = true)
        val declined = creature("declined", power = 3)
        val combat = PolicyCombatView(
            attackingPlayerId = "p0",
            attackers = listOf(PolicyAttackerView("attacker", "p1")),
            blockers = emptyList(),
        )
        val information = state(
            rootBattlefield = listOf(attacker, declined),
            phase = "COMBAT",
            step = "DECLARE_BLOCKERS",
            combat = combat,
        )

        assertEquals(
            2.0 / 8.0,
            evaluator.evaluateDetailed(information, "p0").components.getValue("phiAttackCapacity"),
        )
    }

    @Test
    fun `reserve assigns burn to separate current and next untap windows`() {
        val oneMountain = listOf(card("m", "Mountain", "BATTLEFIELD", types = setOf("LAND")))
        val strike = state(
            opponentLife = 20,
            rootHand = listOf(card("strike", "Lightning Strike", "HAND")),
            rootBattlefield = oneMountain,
        )
        val shocks = state(
            opponentLife = 20,
            rootHand = listOf(card("s1", "Shock", "HAND"), card("s2", "Shock", "HAND")),
            rootBattlefield = oneMountain,
        )

        val strikeResult = evaluator.evaluateDetailed(strike, "p0")
        val shocksResult = evaluator.evaluateDetailed(shocks, "p0")
        assertEquals(0.0, strikeResult.components.getValue("rootBurnNow"))
        assertEquals(0.0, strikeResult.components.getValue("phiReach"))
        assertEquals(2.0, shocksResult.components.getValue("rootBurnNow"))
        val fourDamageReserve = (ln(21.0) - ln(17.0)) / ln(41.0)
        assertEquals(fourDamageReserve, shocksResult.components.getValue("phiReach"), absoluteTolerance = 1e-12)
    }

    @Test
    fun `reach family gates both held damage terms`() {
        val information = state(rootHand = listOf(card("shock", "Shock", "HAND")))
        val full = evaluator.evaluateDetailed(information, "p0")
        val disabled = MonoRedTacticalEvaluatorSchema2(
            MonoRedTacticalEvaluatorSchema2Settings(
                enabledFamilies = TacticalSchema2FeatureFamily.entries.toSet() - TacticalSchema2FeatureFamily.REACH,
            ),
        ).evaluateDetailed(information, "p0")
        assertTrue(full.components.getValue("phiReach") != 0.0 || full.components.getValue("phiLethal") != 0.0)
        assertEquals(0.0, disabled.components.getValue("phiReach"))
        assertEquals(0.0, disabled.components.getValue("phiLethal"))
    }

    @Test
    fun `casting Hired Claw improves value when its Mountain taps`() {
        val before = state(
            rootLife = 20,
            opponentLife = 20,
            rootHand = listOf(card("claw", "Hired Claw", "HAND")),
            rootBattlefield = listOf(card("mountain", "Mountain", "BATTLEFIELD", types = setOf("LAND"))),
        )
        val after = state(
            rootLife = 20,
            opponentLife = 20,
            rootBattlefield = listOf(
                card("mountain", "Mountain", "BATTLEFIELD", types = setOf("LAND"), tapped = true),
                card(
                    "claw",
                    "Hired Claw",
                    "BATTLEFIELD",
                    types = setOf("CREATURE"),
                    power = 1,
                    toughness = 2,
                    summoningSick = true,
                ),
            ),
        )

        val beforeResult = evaluator.evaluateDetailed(before, "p0")
        val afterResult = evaluator.evaluateDetailed(after, "p0")
        assertTrue(afterResult.rawScore > beforeResult.rawScore)
        assertTrue(afterResult.value > beforeResult.value)
    }

    @Test
    fun `family set is snapshotted when evaluator is constructed`() {
        val mutableFamilies = TacticalSchema2FeatureFamily.entries.toMutableSet()
        val snapshotted = MonoRedTacticalEvaluatorSchema2(
            MonoRedTacticalEvaluatorSchema2Settings(enabledFamilies = mutableFamilies),
        )
        val information = state(rootHand = listOf(card("shock", "Shock", "HAND")))
        val before = snapshotted.evaluateDetailed(information, "p0")
        mutableFamilies.clear()
        assertEquals(before, snapshotted.evaluateDetailed(information, "p0"))
    }

    @Test
    fun `each disabled family zeroes only its weighted components`() {
        val information = state(
            rootLife = 10,
            opponentLife = 20,
            rootHand = listOf(card("shock", "Shock", "HAND"), card("claw", "Hired Claw", "HAND")),
            rootBattlefield = listOf(
                card("m", "Mountain", "BATTLEFIELD", types = setOf("LAND")),
                creature("c", power = 2),
            ),
        )
        fun disabled(family: TacticalSchema2FeatureFamily) = MonoRedTacticalEvaluatorSchema2(
            MonoRedTacticalEvaluatorSchema2Settings(enabledFamilies = TacticalSchema2FeatureFamily.entries.toSet() - family),
        ).evaluateDetailed(information, "p0").components

        val life = disabled(TacticalSchema2FeatureFamily.NONLINEAR_LIFE)
        assertEquals(0.0, life.getValue("phiLife"))
        val combat = disabled(TacticalSchema2FeatureFamily.COMBAT_READINESS)
        assertEquals(0.0, combat.getValue("phiBody"))
        assertEquals(0.0, combat.getValue("phiAttackCapacity"))
        assertEquals(0.0, combat.getValue("phiBlock"))
        val reach = disabled(TacticalSchema2FeatureFamily.REACH)
        assertEquals(0.0, reach.getValue("phiLethal"))
        assertEquals(0.0, reach.getValue("phiReach"))
        val hand = disabled(TacticalSchema2FeatureFamily.HAND_VALUE)
        assertEquals(0.0, hand.getValue("phiHand"))
        val mana = disabled(TacticalSchema2FeatureFamily.DURABLE_MANA)
        assertEquals(0.0, mana.getValue("phiDurableMana"))
        val initiative = disabled(TacticalSchema2FeatureFamily.INITIATIVE)
        assertEquals(0.0, initiative.getValue("phiInitiative"))
    }

    @Test
    fun `settings record corrected schema and annotation strings`() {
        val s = MonoRedTacticalEvaluatorSchema2Settings()
        assertEquals(2, s.schemaVersion)
        assertEquals("mono-red-tactical-annotations-v2", s.annotationVersion)
        assertTrue(s.configurationId.contains("schema-2"))
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
        phase: String = "PRECOMBAT_MAIN",
        step: String = "PRECOMBAT_MAIN",
        combat: PolicyCombatView? = null,
        rootMana: PolicyManaPool = PolicyManaPool(),
    ): PolicyInformationState {
        val observation = PolicyObservation(
            perspectivePlayerId = "p0",
            turnNumber = 4,
            phase = phase,
            step = step,
            activePlayerId = "p0",
            priorityPlayerId = "p0",
            players = listOf(
                PolicyPlayerView(
                    "p0", "Root", rootLife, rootHand.size, 40, 0, 0,
                    rootMana, active = true, priority = true, lost = false,
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
            combat = combat,
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
