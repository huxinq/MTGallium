package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.state.ObjectRef
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable
import kotlin.test.*

class ArgentumNativeIdSwapTest {
    @Serializable private data class Typed(val id: EntityId, val text: String,
        val references: Map<EntityId, List<ObjectRef>>)

    @Test fun `typed swap changes all nested IDs but not equal-looking ordinary strings or generations`() {
        val a = EntityId("e10"); val b = EntityId("e20")
        val original = Typed(a, "e10", linkedMapOf(a to listOf(ObjectRef(b, 17)), b to listOf(ObjectRef(a, 23))))
        val swap = ArgentumNativeIdSwap(a, b)
        val renamed = swap.apply(Typed.serializer(), original)
        assertEquals(Typed(b, "e10", linkedMapOf(b to listOf(ObjectRef(a, 17)), a to listOf(ObjectRef(b, 23)))), renamed)
        assertEquals(original, swap.apply(Typed.serializer(), renamed))
        assertFailsWith<IllegalArgumentException> { ArgentumNativeIdSwap(a, a) }
    }
}
