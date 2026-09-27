package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Independent SHA-256/UTF-8 vectors for persisted component streams. */
class ComponentSeedGoldenTest {
    @Test
    fun `seed derivation preserves separator null Unicode and signed big endian bytes`() {
        assertEquals(-2947158467655307117L, ComponentSeeds.derive("game-17", 3, 22, "belief"))
        assertEquals(227370916542124717L, ComponentSeeds.derive("game-17", 3, 22, "proposal"))
        assertEquals(6212715169039257229L, ComponentSeeds.derive("game-17", 0, 17, "live-search"))
        assertEquals(4025757372246487923L, ComponentSeeds.derive(null, "☃", -7, "known-deck-future"))
        assertEquals(7513887910239782866L, ComponentSeeds.derive(71, 0, 0, "opponent-sample"))
    }
}
