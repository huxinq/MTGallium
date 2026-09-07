package org.mtgallium.agent.infoset.argentum

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyZoneView

/**
 * Private immutable canonical-byte fragments, after complete perspective-safe projection.
 * Equality uses the complete emitted DTO, never raw entity identity or an invalidation heuristic.
 * New views still rebuild references and runtime facts before consulting this previous-view cache.
 */
internal class ObservationCanonicalFragments private constructor(
    private val root: Fragment,
    private val zones: List<ZoneFragment>,
    val reusedCards: Int,
    val encodedCards: Int,
    val reusedZones: Int,
) {
    fun digest(): String {
        val digest = SHA256.get().apply { reset() }
        root.visit(digest::update)
        return HexFormat.of().formatHex(digest.digest())
    }

    /** Oracle inspection only; normal hashing never joins the full observation into a buffer. */
    fun canonicalBytes(): ByteArray = ByteArrayOutputStream().also { output -> root.visit(output::write) }.toByteArray()

    companion object {
        /** Full serializer oracle for the bounded diagnostic, not used by the optimized path. */
        fun canonicalOracle(observation: PolicyObservation): String = PolicyJson.canonical(
            PolicyJson.format.encodeToJsonElement(PolicyObservation.serializer(), observation.copy(observationDigest = "")))

        fun build(observation: PolicyObservation, previous: ObservationCanonicalFragments? = null): ObservationCanonicalFragments {
            var reusedCards = 0
            var encodedCards = 0
            var reusedZones = 0
            val oldZones = previous?.zones.orEmpty().associateBy { it.view.ownerId to it.view.zone }
            val zones = observation.zones.map { zone ->
                val old = oldZones[zone.ownerId to zone.zone]
                if (old != null && sameZone(old.view, zone)) {
                    reusedZones++
                    reusedCards += zone.cards.size
                    old
                } else {
                    val oldCards = old?.cards.orEmpty().associateBy { it.view.objectRef }
                    val cards = zone.cards.map { card ->
                        oldCards[card.objectRef]?.takeIf { sameCard(it.view, card) }?.also { reusedCards++ }
                            ?: CardFragment(card, bytes(PolicyJson.canonical(PolicyJson.format.encodeToJsonElement(
                                PolicyCardView.serializer(), card)))).also { encodedCards++ }
                    }
                    val frame = PolicyJson.format.encodeToJsonElement(PolicyZoneView.serializer(), zone.copy(cards = emptyList())) as JsonObject
                    ZoneFragment(zone, cards, objectFragment(frame, mapOf("cards" to arrayFragment(cards.map { it.fragment }))))
                }
            }
            // The source serializer owns every field and default. Only the already encoded zones
            // are substituted; newly added non-zone fields automatically enter the digest.
            val frame = PolicyJson.format.encodeToJsonElement(PolicyObservation.serializer(),
                observation.copy(zones = emptyList(), observationDigest = "")) as JsonObject
            return ObservationCanonicalFragments(objectFragment(frame,
                mapOf("zones" to arrayFragment(zones.map { it.fragment }))), zones, reusedCards, encodedCards, reusedZones)
        }

        private val SHA256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
        // Kotlin Set equality ignores iteration order; the existing serializer emits these as
        // arrays, whose order IS canonical evidence. Maps are canonicalized by sorted key.
        private fun sameCard(a: PolicyCardView, b: PolicyCardView): Boolean = a === b || a == b &&
            a.types.toList() == b.types.toList() && a.subtypes.toList() == b.subtypes.toList() &&
            a.colors.toList() == b.colors.toList() && a.keywords.toList() == b.keywords.toList()
        private fun sameZone(a: PolicyZoneView, b: PolicyZoneView): Boolean = a === b || a == b &&
            a.cards.zip(b.cards).all { (left, right) -> sameCard(left, right) }
        private fun bytes(text: String) = Fragment.Bytes(text.toByteArray(Charsets.UTF_8))
        private val COMMA = bytes(",")
        private fun arrayFragment(children: List<Fragment>): Fragment = sequence("[", "]", children)
        private fun objectFragment(frame: JsonObject, replacements: Map<String, Fragment>): Fragment = sequence("{", "}",
            frame.entries.sortedBy { it.key }.map { (key, value) -> Fragment.Sequence(listOf(
                bytes(PolicyJson.canonical(JsonPrimitive(key)) + ":"),
                replacements[key] ?: bytes(PolicyJson.canonical(value)),
            )) })
        private fun sequence(open: String, close: String, children: List<Fragment>): Fragment = Fragment.Sequence(buildList {
            add(bytes(open))
            children.forEachIndexed { index, child -> if (index > 0) add(COMMA); add(child) }
            add(bytes(close))
        })
    }

    private data class CardFragment(val view: PolicyCardView, val fragment: Fragment)
    private data class ZoneFragment(val view: PolicyZoneView, val cards: List<CardFragment>, val fragment: Fragment)
    private sealed interface Fragment {
        fun visit(consume: (ByteArray) -> Unit)
        class Bytes(private val value: ByteArray) : Fragment {
            override fun visit(consume: (ByteArray) -> Unit) = consume(value)
        }
        class Sequence(private val children: List<Fragment>) : Fragment {
            override fun visit(consume: (ByteArray) -> Unit) = children.forEach { it.visit(consume) }
        }
    }
}
