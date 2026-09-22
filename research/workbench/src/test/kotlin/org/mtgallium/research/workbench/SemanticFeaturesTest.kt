package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.sdk.model.Deck
import kotlin.test.*
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.*

class SemanticFeaturesTest {
    companion object {
        private val information by lazy {
            val deck = mapOf("Mountain" to 8)
            createWorld(GameConfig(players = List(2) { index -> PlayerConfig("Player $index", Deck.of("Mountain" to 8)) },
                startingHandSize = 2, skipMulligans = true, startingPlayerIndex = 0, seed = 61),
                mapOf("p0" to deck, "p1" to deck)).decisionContext().site().information()
        }
    }
    private fun choice(payload: JsonObject, display: String = "fixture"): SemanticChoice {
        val original = information.candidates.first()
        return SemanticChoice.create(original.kind, original.operationFamily, original.actionIntent,
            SemanticChoiceDisplay(display), payload)
    }

    @Test fun `ordered candidate payloads remain distinct without a historical projection mode`() {
        fun payload(first: String, second: String) = buildJsonObject {
            put("ordered", JsonArray(listOf(JsonPrimitive(first), JsonPrimitive(second))))
        }
        val features = SemanticFeatures(information, 1024, 4096)
        assertNotEquals(features.candidate(choice(payload("alpha", "beta"))),
            features.candidate(choice(payload("beta", "alpha"))))
    }

    @Test fun `display text and opaque bookkeeping do not become predictive features`() {
        val clean = buildJsonObject { put("mode", "normal") }
        val bookkeeping = JsonObject(clean + mapOf(
            "debugDigest" to JsonPrimitive("a".repeat(64)),
            "objectRef" to JsonPrimitive("a raw routing reference"),
            "decisionId" to JsonPrimitive("a changing decision id")))
        val original = choice(clean, "ordinary text")
        val changed = choice(bookkeeping, "a pretend outcome that is not policy information")
        assertNotEquals(original.signature, changed.signature)
        val features = SemanticFeatures(information, 1024, 4096)
        assertEquals(features.candidate(original), features.candidate(changed))
    }

    @Test fun `direct features need no chosen label or invented game identity`() {
        val menu = listOf(choice(buildJsonObject { put("mode", "alpha") }), choice(buildJsonObject { put("mode", "beta") }))
        val forward = rootActionKernelFeatures(information, menu)
        val reversed = rootActionKernelFeatures(information, menu.reversed())
        assertEquals(forward, reversed.reversed())
        assertTrue(forward.isNotEmpty())
        assertFailsWith<IllegalArgumentException> { rootActionKernelFeatures(information, emptyList()) }
        assertFailsWith<IllegalArgumentException> { rootActionKernelFeatures(information, stateDimension = 0) }
    }
}
