package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.buildJsonObject

class BoundedPolicyInputTest {
    @Test
    fun `recent event window keeps byte cutoff and oversized event refusal`() {
        val history = (0L until 100L).map { event(it, null) }
        val eventBytes = CanonicalJson.format.encodeToString(ObservedEvent.serializer(), history.last())
            .toByteArray(Charsets.UTF_8).size
        val config = PolicyInputLimits(recentEventByteLimit = eventBytes * 3)
        val window = PolicyInputCompiler.recentEventWindow(history, config)
        assertEquals(history.takeLast(3), window.events)
        assertEquals(4, window.eventsExamined)
        assertFailsWith<IllegalArgumentException> {
            PolicyInputCompiler.recentEventWindow(history, config.copy(recentEventByteLimit = 1))
        }
    }

    private fun event(id: Long, detail: ObservedEventDetail?) = ObservedEvent(
        eventId = id,
        audience = EventAudience(EventAudienceScope.PUBLIC),
        actor = null,
        kind = ObservedEventKind.TURN_STRUCTURE,
        payload = buildJsonObject { },
        detail = detail,
    )
}
