package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PolicyUtilityContractTest {
    @Test
    fun `component seed derivation is tagged and repeatable`() {
        val first = ComponentSeeds.derive("game-17", 3, 22, "belief")
        assertEquals(first, ComponentSeeds.derive("game-17", 3, 22, "belief"))
        assertNotEquals(first, ComponentSeeds.derive("game-17", 3, 22, "proposal"))
    }

    @Test
    fun `canonical json is map-order independent`() {
        val left = kotlinx.serialization.json.buildJsonObject {
            put("z", kotlinx.serialization.json.JsonPrimitive(1))
            put("a", kotlinx.serialization.json.JsonPrimitive(2))
        }
        val right = kotlinx.serialization.json.buildJsonObject {
            put("a", kotlinx.serialization.json.JsonPrimitive(2))
            put("z", kotlinx.serialization.json.JsonPrimitive(1))
        }
        assertEquals(PolicyJson.digest(left), PolicyJson.digest(right))
        assertFalse(PolicyJson.canonical(left).startsWith("{\"z\""))
    }
}
