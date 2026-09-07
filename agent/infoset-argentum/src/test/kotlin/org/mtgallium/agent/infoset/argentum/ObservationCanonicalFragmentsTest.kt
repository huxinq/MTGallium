package org.mtgallium.agent.infoset.argentum

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyZoneView

class ObservationCanonicalFragmentsTest {
    private val card = PolicyCardView("zone:p0:0", null, "雪\"\\\n😀", "BATTLEFIELD", "p0", "p0",
        linkedSetOf("Artifact", "Creature"), linkedSetOf("Goblin"), linkedSetOf("RED"), emptySet(),
        "{R}", 1, "Text\t\u0000", 1, 1, false, false, false, 0, linkedMapOf("z" to 2, "a" to 1), null, emptyList())
    private fun observation(cards: List<PolicyCardView> = listOf(card)) = PolicyObservation("p0", 1,
        "PRECOMBAT_MAIN", "MAIN", "p0", "p0", emptyList(),
        listOf(PolicyZoneView("p0", "BATTLEFIELD", false, cards.size, cards),
            PolicyZoneView("p1", "HAND", true, 7, emptyList())), emptyList(), pendingDecision = null, observationDigest = "ignored")

    private fun check(view: PolicyObservation, previous: ObservationCanonicalFragments? = null): ObservationCanonicalFragments {
        val fragments = ObservationCanonicalFragments.build(view, previous)
        val canonical = PolicyJson.canonical(PolicyJson.format.encodeToJsonElement(PolicyObservation.serializer(),
            view.copy(observationDigest = "")))
        assertContentEquals(canonical.toByteArray(Charsets.UTF_8), fragments.canonicalBytes())
        assertEquals(PolicyJson.sha256(canonical), fragments.digest())
        return fragments
    }

    @Test fun `canonical fragments match serializer and preserve independent sibling snapshots`() {
        val initial = observation()
        val base = check(initial)
        val beforeBytes = base.canonicalBytes()
        val priority = check(initial.copy(priorityPlayerId = "p1", turnNumber = 2), base)
        assertEquals(1, priority.reusedCards)
        assertEquals(0, priority.encodedCards)
        assertEquals(2, priority.reusedZones)
        val sibling = check(observation(listOf(card.copy(tapped = true, damageMarked = 1))), base)
        assertEquals(1, sibling.encodedCards)
        assertContentEquals(beforeBytes, base.canonicalBytes())
        assertNotEquals(priority.digest(), sibling.digest())
        check(initial, sibling)
    }

    @Test fun `each serialized mutation invalidates its fragment including array order hidden size and routing`() {
        val base = check(observation())
        val changes = listOf(card.copy(objectRef = "zone:p0:1"), card.copy(controllerId = "p1"),
            card.copy(power = 5), card.copy(attachedTo = "zone:p0:2"), card.copy(attachments = listOf("zone:p0:2")),
            card.copy(playableFromExile = true), card.copy(hasActivatedAbilityThisTurn = true),
            card.copy(types = linkedSetOf("Creature", "Artifact")), card.copy(counters = mapOf("a" to 2)),
            card.copy(name = "\uD800"))
        changes.forEach { changed -> assertEquals(1, check(observation(listOf(changed)), base).encodedCards) }
        // Counter-map insertion order has no canonical significance and can retain its bytes.
        assertEquals(1, check(observation(listOf(card.copy(counters = linkedMapOf("a" to 1, "z" to 2)))), base).reusedCards)
        check(observation().copy(zones = observation().zones.map { it.copy(size = it.size + 1) }), base)
        check(observation(listOf(card, card.copy(objectRef = "zone:p0:2"))), base)
        check(observation(emptyList()), base)
    }
}
