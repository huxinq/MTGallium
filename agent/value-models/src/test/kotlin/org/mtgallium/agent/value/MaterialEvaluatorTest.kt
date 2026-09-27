package org.mtgallium.agent.value

import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.ObjectView
import org.mtgallium.agent.infoset.core.HistoryHashChain
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.CanonicalJson
import org.mtgallium.agent.infoset.core.ManaPoolView
import org.mtgallium.agent.infoset.core.PlayerObservationSnapshot
import org.mtgallium.agent.infoset.core.PlayerView
import org.mtgallium.agent.infoset.core.ZoneView

class MaterialEvaluatorTest {
    private val defaults = MaterialWeights()

    @Test
    fun `default coefficients and cached rescoring preserve visible-v2 bits`() {
        val evaluator = MaterialEvaluator(defaults)
        for (index in 0..40) {
            val state = state(index)
            for (root in listOf("p0", "p1")) {
                val expected = legacyMaterialValue(state, root).toBits()
                assertEquals(expected, evaluator.evaluate(state, root).toBits(), "state $index root $root")
                assertEquals(expected, MaterialFeatures.extract(state, root).evaluate(defaults).toBits())
            }
        }
        (0..15).forEach { lands ->
            assertEquals(legacyDevelopedManaValue(lands).toBits(),
                defaults.developedManaValue(lands).toBits())
        }
        assertNotEquals(MaterialEvaluator().id, evaluator.configurationId)
    }

    @Test
    fun `default sentinel and explicit equal weights preserve separate historical identities`() {
        val default = MaterialEvaluator()
        val explicit = MaterialEvaluator(MaterialWeights())
        val copied = MaterialEvaluator(MaterialWeights.DEFAULT.copy())
        val decodedWeights = CanonicalJson.format.decodeFromString(MaterialWeights.serializer(), "{}")
        val decoded = MaterialEvaluator(decodedWeights)
        assertEquals("mono-red-visible-board-v2", default.id)
        assertEquals(default.id, default.configurationId)
        assertEquals(default.configurationId, MaterialEvaluator(MaterialWeights.DEFAULT).configurationId)
        assertEquals(MaterialWeights().configurationId, explicit.configurationId)
        assertEquals(explicit.configurationId, copied.configurationId)
        assertEquals(explicit.configurationId, decoded.configurationId)
        assertNotEquals(default.configurationId, explicit.configurationId)
        for (index in 0..40) for (root in listOf("p0", "p1")) {
            val information = state(index)
            val expected = legacyMaterialValue(information, root).toBits()
            for (evaluator in listOf(default, explicit, copied, decoded))
                assertEquals(expected, evaluator.evaluate(information, root).toBits())
        }
        assertFailsWith<UnsupportedOperationException> {
            (default.weights.landMarginals as MutableList<Double>)[0] = 999.0
        }
        assertFailsWith<UnsupportedOperationException> {
            (MaterialWeights.DEFAULT.landMarginals as MutableList<Double>)[0] = 999.0
        }
    }

    @Test
    fun `known feature case includes stats on noncreatures and preserves weighted arithmetic`() {
        val config = defaults.copy(life = 2.0, hand = 3.0, power = 5.0, toughness = 7.0,
            haste = 11.0, landMarginals = listOf(13.0, 17.0), landTail = 19.0, tanhScale = 23.0)
        val information = fixture(14, 11, 2, 1, listOf(
            card("artifact", "p0", power = 3, toughness = 2, haste = true),
            card("land-a", "p0", land = true), card("land-b", "p0", land = true),
            card("opponent", "p1", power = 2, toughness = 3), card("land-c", "p1", land = true)))
        val features = MaterialFeatures.extract(information, "p0")
        // 6 life + 3 hand + (40 body + 30 lands) - (31 body + 13 lands).
        assertEquals(35.0, features.linearPredictor(config))
        assertEquals(tanh(35.0 / 23.0).toBits(), features.evaluate(config).toBits())
        assertEquals(features.evaluate(config).toBits(),
            MaterialEvaluator(config).evaluate(information, "p0").toBits())
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
            val encoded = CanonicalJson.format.encodeToString(MaterialWeights.serializer(), config)
            assertEquals(config, CanonicalJson.format.decodeFromString<MaterialWeights>(encoded))
            assertEquals(config.configurationId,
                MaterialEvaluator(config).configurationId)
        }
        val mutableMarginals = defaults.landMarginals.toMutableList()
        val evaluator = MaterialEvaluator(defaults.copy(landMarginals = mutableMarginals))
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
        assertTrue(MaterialEvaluator(largest).evaluate(state(40), "p0").isFinite())
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
        val features = MaterialFeatures.extract(first, "p0")
        assertEquals(features, MaterialFeatures.extract(second, "p0"))
        assertEquals(features.evaluate(defaults), MaterialEvaluator(defaults).evaluate(second, "p0"))
        val encoded = CanonicalJson.format.encodeToString(MaterialFeatures.serializer(), features)
        assertTrue("objectRef" !in encoded && "definitionId" !in encoded && "oracleText" !in encoded)
    }

    private fun state(index: Int) = fixture(7 + index % 20, 12 + index % 7, index % 8, (index + 3) % 8,
        List(index % 17) { n -> card("p$n", if (n % 3 == 0) "p1" else "p0",
            power = if (n % 4 == 0) null else n % 6 - 1,
            toughness = if (n % 5 == 0) null else n % 7,
            land = n % 2 == 0, haste = n % 3 == 1) })

    private fun fixture(rootLife: Int, opponentLife: Int, rootHand: Int, opponentHand: Int,
        battlefield: List<ObjectView>): InformationStateRepresentation {
        val observation = PlayerObservationSnapshot("p0", 4, "PRECOMBAT_MAIN", "PRECOMBAT_MAIN", "p0", "p0",
            listOf(PlayerView("p0", "Root", rootLife, rootHand, 40, 0, 0, ManaPoolView(), true, true, false),
                PlayerView("p1", "Opponent", opponentLife, opponentHand, 40, 0, 0, ManaPoolView(), false, false, false)),
            listOf(ZoneView("p0", "BATTLEFIELD", false, battlefield.size, battlefield),
                ZoneView("p0", "HAND", true, rootHand, List(rootHand) { card("root-$it", "p0").copy(zone = "HAND") }),
                ZoneView("p1", "HAND", true, opponentHand, List(opponentHand) { card("hidden-$it", "p1").copy(zone = "HAND") })),
            emptyList(), pendingDecision = null, observationDigest = "synthetic")
        return InformationStateRepresentation(actingPlayerId = "p0", observation = observation,
            informationStateDigest = "synthetic", historyCommitment = HistoryHashChain.empty(),
            history = emptyList(), candidates = emptyList(), terminated = false)
    }

    private fun card(ref: String, controller: String, power: Int? = null, toughness: Int? = null,
        land: Boolean = false, haste: Boolean = false) = ObjectView(objectRef = ref, definitionId = "synthetic:$ref",
        name = ref, zone = "BATTLEFIELD", ownerId = controller, controllerId = controller,
        types = if (land) setOf("land") else setOf("ARTIFACT"), subtypes = emptySet(), colors = emptySet(),
        keywords = if (haste) setOf("haste") else emptySet(), manaCost = "", manaValue = 0, oracleText = "",
        power = power, toughness = toughness, tapped = false, summoningSick = false, faceDown = false,
        damageMarked = 0, counters = emptyMap(), attachedTo = null, attachments = emptyList())
    private fun legacyMaterialValue(information: InformationStateRepresentation, rootPlayer: String): Double {
        val observation = information.observation
        val root = observation.players.single { it.playerId == rootPlayer }
        val opponent = observation.players.first { it.playerId != rootPlayer }
        val battlefield = observation.zones.filter { it.zone == "BATTLEFIELD" }.flatMap { it.cards }
        fun boardValue(player: String): Double {
            val permanents = battlefield.filter { it.controllerId == player }
            val lands = permanents.count { card -> card.types.any { it.equals("LAND", ignoreCase = true) } }
            val creatures = permanents.sumOf { card ->
                (card.power ?: 0) * 1.2 + (card.toughness ?: 0) * 0.4 +
                    if (card.keywords.any { it.equals("HASTE", ignoreCase = true) }) 0.3 else 0.0
            }
            return creatures + legacyDevelopedManaValue(lands)
        }
        fun handValue(size: Int): Double = size * 0.35
        val score = (root.life - opponent.life) * 0.12 +
            handValue(root.handSize) - handValue(opponent.handSize) +
            boardValue(rootPlayer) - boardValue(opponent.playerId)
        return tanh(score / 8.0)
    }


    private fun legacyDevelopedManaValue(lands: Int): Double {
        require(lands >= 0)
        val earlyMarginals = doubleArrayOf(1.00, 0.85, 0.70, 0.45, 0.25)
        return earlyMarginals.take(lands).sum() + (lands - earlyMarginals.size).coerceAtLeast(0) * 0.15
    }

}
