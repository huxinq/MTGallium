package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PolicyHistoryCommitmentTest {
    @Test
    fun `sha256 chain has stable golden vectors`() {
        val empty = HistoryHashChain.empty()
        val one = empty.append(event(0, "pass"))

        assertEquals("4f084cd6b34a9be3b984ddd20d78b292a1810f3961c9f606d27d46df554801ea", empty.digest)
        assertEquals("c36543cbcaec6a8a1019d29e1c62637e2028f0547d286ec5c378bf52ccc0676b", one.digest)
        assertEquals(one, HistoryHashChain.replay(listOf(event(0, "pass"))))
    }

    @Test
    fun `chain rejects cursor gaps and binds order and bytes`() {
        assertFailsWith<IllegalArgumentException> { HistoryHashChain.empty().append(event(1, "pass")) }
        val first = HistoryHashChain.replay(listOf(event(0, "a"), event(1, "b")))
        val changed = HistoryHashChain.replay(listOf(event(0, "b"), event(1, "a")))
        assertNotEquals(first, changed)
    }

    private fun event(id: Long, choice: String) = ObservedEvent(
        eventId = id,
        audience = EventAudience(EventAudienceScope.PUBLIC),
        actor = "p0",
        kind = ObservedEventKind.ACTION,
        payload = buildJsonObject { put("choice", choice) },
    )
}
