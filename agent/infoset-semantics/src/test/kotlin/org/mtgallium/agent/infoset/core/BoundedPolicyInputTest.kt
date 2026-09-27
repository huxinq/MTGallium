package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.buildJsonObject

class BoundedPolicyInputTest {
    @Test
    fun `recent event window keeps byte cutoff and oversized event refusal`() {
        val history = (0L until 100L).map { event(it, null) }
        val eventBytes = PolicyJson.format.encodeToString(PolicyHistoryEvent.serializer(), history.last())
            .toByteArray(Charsets.UTF_8).size
        val config = BoundedPolicyInputConfig(recentEventByteLimit = eventBytes * 3)
        val window = BoundedPolicyInputCompiler.recentEventWindow(history, config)
        assertEquals(history.takeLast(3), window.events)
        assertEquals(4, window.eventsExamined)
        assertFailsWith<IllegalArgumentException> {
            BoundedPolicyInputCompiler.recentEventWindow(history, config.copy(recentEventByteLimit = 1))
        }
    }

    private fun event(id: Long, detail: PerspectiveEventDetail?) = PolicyHistoryEvent(
        eventId = id,
        audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
        actor = null,
        kind = PolicyHistoryEventKind.TURN_STRUCTURE,
        payload = buildJsonObject { },
        detail = detail,
    )
}
