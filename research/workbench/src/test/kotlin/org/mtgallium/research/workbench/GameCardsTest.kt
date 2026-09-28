package org.mtgallium.research.workbench

import kotlin.test.*
import kotlinx.serialization.json.*

class GameCardsTest {
    @Test fun aFreshConnectionCanRenderAReplayWithoutFirstCreatingItsGame() {
        val connection = GameServerConnection()
        val created = connection.request(buildJsonObject {
            put("command", "create")
            put("plan", researchJson.encodeToJsonElement(ResearchGameConfig(
                decks = List(2) { mapOf("Mountain" to 20, "Raging Goblin" to 20) })))
        }).jsonObject
        fun request(command: String) = connection.request(buildJsonObject {
            put("command", command); put("game", created.getValue("game"))
        })
        assertEquals(request("replay-views"), GameServerConnection().request(buildJsonObject {
            put("command", "render-replay-state"); put("state", request("state"))
        }))
    }

    @Test fun onlyRequestedCardsAndBootstrapAreRegistered() {
        val registry = buildRegistry(listOf("Raging Goblin"))
        assertTrue(registry.hasCard("Raging Goblin"))
        assertTrue(registry.hasCard("Mountain"))
        assertFalse(registry.hasCard("Lightning Bolt"))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            registry.requireCard("Lightning Bolt")
        }.message!!.contains("Lightning Bolt"))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            buildRegistry(listOf("Not a real card"))
        }.message!!.contains("Not a real card"))
    }

    @Test fun variantsAndBothFacesSurviveLoading() {
        val registry = buildRegistry(listOf("Delver of Secrets"))
        assertEquals("Delver of Secrets", registry.getFrontFace("Insectile Aberration")?.name)
        registry.getCardsByName("Mountain").forEach { land ->
            val number = land.metadata.collectorNumber ?: return@forEach
            val key = "Mountain#${land.setCode?.let { "$it-" }.orEmpty()}$number"
            assertEquals(land, registry.requireCard(key))
        }
    }
}
