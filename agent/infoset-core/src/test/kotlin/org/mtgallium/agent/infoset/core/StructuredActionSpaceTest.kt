package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuredActionSpaceTest {
    @Test
    fun `proposer returns only complete actions and stable widening prefixes`() {
        val proposer = BestFirstStructuredActionProposer(BinarySpace()).proposals()
        val first = proposer.take(2).toList()
        val wider = BestFirstStructuredActionProposer(BinarySpace()).proposals().take(4).toList()

        assertEquals(listOf("00", "11"), first)
        assertEquals(first, wider.take(first.size))
        assertEquals(setOf("00", "01", "10", "11"), wider.toSet())
        assertTrue(wider.all { it.length == 2 })
    }

    @Test
    fun `canonical partial and completion keys merge duplicate construction routes`() {
        val actions = BestFirstStructuredActionProposer(DuplicateRouteSpace()).proposals().toList()

        assertEquals(listOf("A", "B"), actions)
    }

    private data class Bits(val value: String)

    private class BinarySpace : StructuredActionSpace<Bits, Char, String> {
        override fun start() = Bits("")
        override fun legalExtensions(partial: Bits) = sequenceOf('0', '1')
        override fun extend(partial: Bits, extension: Char) = Bits(partial.value + extension)
        override fun isComplete(partial: Bits) = partial.value.length == 2
        override fun finalize(partial: Bits) = partial.value
        override fun canonicalPartialKey(partial: Bits) = partial.value
        override fun priority(partial: Bits) = StructuredProposalPriority(
            tiers = listOf(partial.value.length.toLong()),
            stableKey = partial.value,
        )
        override fun preferredCompletions() = sequenceOf(Bits("00"), Bits("11"))
    }

    private data class Routed(val depth: Int, val value: String)

    private class DuplicateRouteSpace : StructuredActionSpace<Routed, String, String> {
        override fun start() = Routed(0, "")
        override fun legalExtensions(partial: Routed) = when (partial.depth) {
            0 -> sequenceOf("left", "right")
            1 -> sequenceOf("A", "B")
            else -> emptySequence()
        }
        override fun extend(partial: Routed, extension: String) = when (partial.depth) {
            0 -> Routed(1, extension)
            else -> Routed(2, extension)
        }
        override fun isComplete(partial: Routed) = partial.depth == 2
        override fun finalize(partial: Routed) = partial.value
        override fun canonicalPartialKey(partial: Routed) = when (partial.depth) {
            2 -> partial.value
            else -> "${partial.depth}:${partial.value}"
        }
        override fun priority(partial: Routed) = StructuredProposalPriority(
            tiers = listOf(partial.depth.toLong()),
            stableKey = canonicalPartialKey(partial),
        )
    }
}
