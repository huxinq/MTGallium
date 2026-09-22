package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.random.Random
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class PolicyUtilityContractTest {
    // Retain the prior serializer-based contract as an independent compatibility oracle.
    private fun legacyCanonical(element: JsonElement): String = when (element) {
        JsonNull -> "null"
        is JsonPrimitive -> element.toString()
        is JsonArray -> element.joinToString(",", "[", "]") { legacyCanonical(it) }
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
            PolicyJson.format.encodeToString(it.key) + ":" + legacyCanonical(it.value)
        }
    }

    @Test
    fun `canonical strings preserve every UTF-16 code unit and JSON escape in keys and values`() {
        // Includes lone surrogates, valid surrogate pairs, all controls, non-ASCII, slash and quotes.
        val strings = List(65536) { it.toChar().toString() } + listOf(
            "", "plain", "\uD83D\uDE00", "a\uD800b\uDC00c", "\"\\/\b\t\n\u000C\r", "汉字é\u2028\u2029",
        )
        for (values in strings.chunked(512)) {
            val element = JsonObject(values.associateWith { JsonPrimitive(it) })
            val expected = legacyCanonical(element)
            assertEquals(expected, PolicyJson.canonical(element))
            assertEquals(PolicyJson.sha256(expected), PolicyJson.digest(element))
        }
    }

    @Test
    fun `canonical nested objects retain sorting array order and primitive spellings`() {
        val random = Random(75019)
        fun element(depth: Int): JsonElement {
            if (depth == 0) return listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(false),
                JsonPrimitive(-0.0), JsonPrimitive(1.25e30), JsonPrimitive("null"),
                JsonPrimitive(List(12) { random.nextInt(65536).toChar() }.joinToString(""))).random(random)
            return if (random.nextBoolean()) JsonArray(List(4) { element(depth - 1) })
            else JsonObject(List(4) { List(6) { random.nextInt(65536).toChar() }.joinToString("") }
                .associateWith { element(depth - 1) })
        }
        repeat(50) {
            val input = element(3)
            val expected = legacyCanonical(input)
            assertEquals(expected, PolicyJson.canonical(input))
            assertEquals(PolicyJson.sha256(expected), PolicyJson.digest(input))
        }
    }

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
