package org.mtgallium.agent.searchteacher

import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.PolicyZoneView

class ConfiguredMonoRedInformationEvaluatorTest {
    private val defaults = MonoRedVisibleEvaluatorConfig()

    @Test
    fun `default coefficients and cached rescoring preserve visible-v2 bits`() {
        val evaluator = ConfiguredMonoRedInformationEvaluator(defaults)
        for (index in 0..40) {
            val state = state(index)
            for (root in listOf("p0", "p1")) {
                val expected = MonoRedInformationEvaluator.evaluate(state, root).toBits()
                assertEquals(expected, evaluator.evaluate(state, root).toBits(), "state $index root $root")
                assertEquals(expected, MonoRedVisibleFeatures.extract(state, root).evaluate(defaults).toBits())
            }
        }
        (0..15).forEach { lands ->
            assertEquals(MonoRedInformationEvaluator.developedManaValue(lands).toBits(),
                defaults.developedManaValue(lands).toBits())
        }
        assertEquals(MonoRedInformationEvaluator.id,
            LeafValueSource.Information(MonoRedInformationEvaluator).invokedEvaluatorConfigurationId)
        assertNotEquals(MonoRedInformationEvaluator.id, evaluator.configurationId)
    }

    @Test
    fun `known feature case includes stats on noncreatures and preserves weighted arithmetic`() {
        val config = defaults.copy(life = 2.0, hand = 3.0, power = 5.0, toughness = 7.0,
            haste = 11.0, landMarginals = listOf(13.0, 17.0), landTail = 19.0, tanhScale = 23.0)
        val information = fixture(14, 11, 2, 1, listOf(
            card("artifact", "p0", power = 3, toughness = 2, haste = true),
            card("land-a", "p0", land = true), card("land-b", "p0", land = true),
            card("opponent", "p1", power = 2, toughness = 3), card("land-c", "p1", land = true)))
        val features = MonoRedVisibleFeatures.extract(information, "p0")
        // 6 life + 3 hand + (40 body + 30 lands) - (31 body + 13 lands).
        assertEquals(35.0, features.rawScore(config))
        assertEquals(tanh(35.0 / 23.0).toBits(), features.evaluate(config).toBits())
        assertEquals(features.evaluate(config).toBits(),
            ConfiguredMonoRedInformationEvaluator(config).evaluate(information, "p0").toBits())
        assertEquals(68.0, config.developedManaValue(4))
    }

    @Test
    fun `every coefficient and marginal position changes detached configuration identity`() {
        val changes = listOf(defaults.copy(life = .2), defaults.copy(hand = .5), defaults.copy(power = 2.0),
            defaults.copy(toughness = .8), defaults.copy(haste = .6), defaults.copy(landTail = .2),
            defaults.copy(tanhScale = 6.0), defaults.copy(landMarginals = defaults.landMarginals.reversed()),
            defaults.copy(landMarginals = defaults.landMarginals + .1)) + defaults.landMarginals.indices.map { index ->
            defaults.copy(landMarginals = defaults.landMarginals.mapIndexed { i, value -> if (i == index) value + .1 else value })
        }
        changes.forEach { config ->
            assertNotEquals(defaults.configurationId, config.configurationId)
            val encoded = PolicyJson.format.encodeToString(MonoRedVisibleEvaluatorConfig.serializer(), config)
            assertEquals(config, PolicyJson.format.decodeFromString<MonoRedVisibleEvaluatorConfig>(encoded))
            assertEquals(config.configurationId,
                LeafValueSource.Information(ConfiguredMonoRedInformationEvaluator(config)).invokedEvaluatorConfigurationId)
        }
        val mutableMarginals = defaults.landMarginals.toMutableList()
        val evaluator = ConfiguredMonoRedInformationEvaluator(defaults.copy(landMarginals = mutableMarginals))
        val before = evaluator.evaluate(state(6), "p0")
        mutableMarginals[0] = 100.0
        assertEquals(defaults.configurationId, evaluator.configurationId)
        assertEquals(before.toBits(), evaluator.evaluate(state(6), "p0").toBits())
    }

    @Test
    fun `invalid coefficients and unsupported forms cannot produce a configuration`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1_000_001.0).forEach { bad ->
            assertFailsWith<IllegalArgumentException> { defaults.copy(life = bad) }
            assertFailsWith<IllegalArgumentException> { defaults.copy(hand = bad) }
            assertFailsWith<IllegalArgumentException> { defaults.copy(power = bad) }
            assertFailsWith<IllegalArgumentException> { defaults.copy(toughness = bad) }
            assertFailsWith<IllegalArgumentException> { defaults.copy(haste = bad) }
            assertFailsWith<IllegalArgumentException> { defaults.copy(landMarginals = listOf(bad)) }
            assertFailsWith<IllegalArgumentException> { defaults.copy(landTail = bad) }
            assertFailsWith<IllegalArgumentException> { defaults.copy(tanhScale = bad) }
        }
        listOf(0.0, -1.0, 1e-12).forEach { bad ->
            assertFailsWith<IllegalArgumentException> { defaults.copy(tanhScale = bad) }
        }
        assertFailsWith<IllegalArgumentException> { defaults.copy(formVersion = 2) }
        assertFailsWith<IllegalArgumentException> { defaults.copy(landMarginals = List(65) { 1.0 }) }
        val largest = defaults.copy(life = 1e6, hand = 1e6, power = 1e6, toughness = 1e6,
            haste = 1e6, landTail = 1e6, tanhScale = 1e-6)
        assertTrue(ConfiguredMonoRedInformationEvaluator(largest).evaluate(state(40), "p0").isFinite())
    }

    @Test
    fun `features ignore hidden card identity order and unrelated observation metadata`() {
        val first = state(9)
        val second = first.copy(informationStateDigest = "different-safe-digest", observation = first.observation.copy(
            observationDigest = "other-observation", zones = first.observation.zones.map { zone ->
                zone.copy(cards = zone.cards.map { card -> card.copy(objectRef = "changed-${card.objectRef}",
                    definitionId = "different", name = "Different card", oracleText = "Different text") }
                    .let { if (zone.zone == "HAND") it.reversed() else it })
            }))
        val features = MonoRedVisibleFeatures.extract(first, "p0")
        assertEquals(features, MonoRedVisibleFeatures.extract(second, "p0"))
        assertEquals(features.evaluate(defaults), ConfiguredMonoRedInformationEvaluator(defaults).evaluate(second, "p0"))
        val encoded = PolicyJson.format.encodeToString(MonoRedVisibleFeatures.serializer(), features)
        assertTrue("objectRef" !in encoded && "definitionId" !in encoded && "oracleText" !in encoded)
    }

    private fun state(index: Int) = fixture(7 + index % 20, 12 + index % 7, index % 8, (index + 3) % 8,
        List(index % 17) { n -> card("p$n", if (n % 3 == 0) "p1" else "p0",
            power = if (n % 4 == 0) null else n % 6 - 1,
            toughness = if (n % 5 == 0) null else n % 7,
            land = n % 2 == 0, haste = n % 3 == 1) })

    private fun fixture(rootLife: Int, opponentLife: Int, rootHand: Int, opponentHand: Int,
        battlefield: List<PolicyCardView>): PolicyInformationState {
        val observation = PolicyObservation("p0", 4, "PRECOMBAT_MAIN", "PRECOMBAT_MAIN", "p0", "p0",
            listOf(PolicyPlayerView("p0", "Root", rootLife, rootHand, 40, 0, 0, PolicyManaPool(), true, true, false),
                PolicyPlayerView("p1", "Opponent", opponentLife, opponentHand, 40, 0, 0, PolicyManaPool(), false, false, false)),
            listOf(PolicyZoneView("p0", "BATTLEFIELD", false, battlefield.size, battlefield),
                PolicyZoneView("p0", "HAND", true, rootHand, List(rootHand) { card("root-$it", "p0").copy(zone = "HAND") }),
                PolicyZoneView("p1", "HAND", true, opponentHand, List(opponentHand) { card("hidden-$it", "p1").copy(zone = "HAND") })),
            emptyList(), pendingDecision = null, observationDigest = "synthetic")
        return PolicyInformationState(actingPlayerId = "p0", observation = observation,
            informationStateDigest = "synthetic", historyCommitment = PolicyHistoryCommitment.empty(),
            history = emptyList(), candidates = emptyList(), terminated = false)
    }

    private fun card(ref: String, controller: String, power: Int? = null, toughness: Int? = null,
        land: Boolean = false, haste: Boolean = false) = PolicyCardView(objectRef = ref, definitionId = "synthetic:$ref",
        name = ref, zone = "BATTLEFIELD", ownerId = controller, controllerId = controller,
        types = if (land) setOf("land") else setOf("ARTIFACT"), subtypes = emptySet(), colors = emptySet(),
        keywords = if (haste) setOf("haste") else emptySet(), manaCost = "", manaValue = 0, oracleText = "",
        power = power, toughness = toughness, tapped = false, summoningSick = false, faceDown = false,
        damageMarked = 0, counters = emptyMap(), attachedTo = null, attachments = emptyList())
}
