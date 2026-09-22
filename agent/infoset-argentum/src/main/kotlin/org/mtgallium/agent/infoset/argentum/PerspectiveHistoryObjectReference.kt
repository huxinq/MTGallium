package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ObjectRef
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.ResolvedEvent
import com.wingedsheep.engine.core.SpellCastEvent
import com.wingedsheep.engine.core.SpellCopiedEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Independent of event ordering; legacy snapshot locators remain the default. */
enum class PerspectiveHistoryObjectReference {
    LEGACY_SNAPSHOT_V1,
    REMEMBERED_BATTLEFIELD_V1,
    REMEMBERED_BATTLEFIELD_AND_RESOLUTION_SOURCE_V1,
    /**
     * Acquired battlefield handles also identify current visible objects; unordered combat rows normalize.
     * Accepted-choice optionality uses bounded native witnesses on both search and observed routes;
     * an unqualified cardinality remains null, independently of the search menu.
     */
    QUALIFIED_OBSERVED_OBJECTS_V2;

    internal val remembersBattlefield: Boolean get() = when (this) {
        LEGACY_SNAPSHOT_V1 -> false
        REMEMBERED_BATTLEFIELD_V1, REMEMBERED_BATTLEFIELD_AND_RESOLUTION_SOURCE_V1,
        QUALIFIED_OBSERVED_OBJECTS_V2 -> true
    }
}

/**
 * A batch-start stack occurrence, not the resulting permanent or a persistent card identity.
 * Native old/new incarnations qualify the association; only the viewer-admitted locator escapes.
 * Deliberately excludes multiple resolutions, multiple source moves, and other stack moves.
 */
internal fun qualifiedResolutionSources(
    events: List<GameEvent>,
    before: GameState,
    after: GameState,
    beforeRefs: SafeReferenceMap,
    batchStartEventId: Long,
): Map<EntityId, String> {
    val resolved = events.filterIsInstance<ResolvedEvent>().singleOrNull() ?: return emptyMap()
    if (events.any { it is SpellCastEvent || it is SpellCopiedEvent }) return emptyMap()
    val id = resolved.entityId
    if (before.stack.count { it == id } != 1 || !before.isSpellOnStack(id) || id in after.stack)
        return emptyMap()
    val moves = events.filterIsInstance<ZoneChangeEvent>()
    val move = moves.filter { it.entityId == id }.singleOrNull() ?: return emptyMap()
    if (move.fromZone != Zone.STACK || move.toZone != Zone.BATTLEFIELD ||
        events.indexOf(move) >= events.indexOf(resolved) ||
        moves.any { it !== move && (it.fromZone == Zone.STACK || it.toZone == Zone.STACK) })
        return emptyMap()
    val old = move.oldObject ?: return emptyMap()
    val new = move.newObject ?: return emptyMap()
    if (old == new || old != before.objectRef(id) || new != after.objectRef(id) ||
        id !in after.getBattlefield() || before.logicalZone(id)?.zoneType != Zone.STACK ||
        after.logicalZone(id)?.zoneType != Zone.BATTLEFIELD ||
        before.stack.filterNot { it == id } != after.stack)
        return emptyMap()
    val source = beforeRefs.referenceOrNull(id)?.takeIf { it.startsWith("stack:") } ?: return emptyMap()
    return mapOf(id to "resolution-source-before:v1:$batchStartEventId:$source")
}

/** Eligibility only: this class never allocates or rebinds viewer knowledge handles. */
internal class RememberedHistoryReferences(
    private val origins: MutableMap<String, ObjectRef> = mutableMapOf(),
) {
    fun fork() = RememberedHistoryReferences(origins.toMutableMap())

    fun swapNativeIds(swap: ArgentumNativeIdSwap) = RememberedHistoryReferences(
        origins.mapValues { (_, ref) -> ref.copy(entityId = swap.id(ref.entityId)) }.toMutableMap())

    fun qualifiedAt(state: GameState, handles: Map<EntityId, String>): Map<EntityId, String> =
        handles.filter { (id, handle) ->
            id in state.getBattlefield() && origins[handle]?.let { it == state.objectRef(id) } == true
        }

    /** Trusted cache identity only; raw origins never enter player information. */
    fun trustedState(): JsonObject = buildJsonObject {
        for ((handle, origin) in origins.toSortedMap()) putJsonObject(handle) {
            put("entity", origin.entityId.value)
            put("generation", origin.generation)
        }
    }

    fun bindBoundary(state: GameState, refs: SafeReferenceMap, handles: Map<EntityId, String>) {
        for (id in state.getBattlefield()) {
            val handle = handles[id] ?: continue
            if (refs.referenceOrNull(id) == null) continue
            val origin = state.objectRef(id) ?: continue
            // A later incarnation must never inherit this handle's history identity.
            origins.putIfAbsent(handle, origin)
        }
    }

    fun eligible(
        before: GameState,
        after: GameState,
        beforeRefs: SafeReferenceMap,
        afterRefs: SafeReferenceMap,
        priorHandles: Map<EntityId, String>,
        excluded: Set<EntityId>,
    ): Map<EntityId, String> {
        val continuous = before.getBattlefield().toSet().intersect(after.getBattlefield().toSet()) - excluded
        return continuous.mapNotNull { id ->
            val handle = priorHandles[id] ?: return@mapNotNull null
            val origin = origins[handle] ?: return@mapNotNull null
            if (beforeRefs.referenceOrNull(id) == null || afterRefs.referenceOrNull(id) == null ||
                before.objectRef(id) != origin || after.objectRef(id) != origin) return@mapNotNull null
            id to handle
        }.toMap()
    }
}

/** The opaque history namespace links to an existing viewer-local knowledge key. */
internal class PerspectiveEventReferenceResolver(
    private val before: SafeReferenceMap,
    private val after: SafeReferenceMap,
    private val eligibleHandles: Map<EntityId, String> = emptyMap(),
    private val resolutionSources: Map<EntityId, String> = emptyMap(),
    private val currentHandle: (EntityId) -> String? = { null },
) {
    fun resolutionSource(id: EntityId): String? = resolutionSources[id] ?: resolve(id)

    fun resolve(id: EntityId?): String? {
        if (id == null) return null
        val handle = eligibleHandles[id]
        if (handle != null && currentHandle(id) == handle) return "history-object:v1:$handle"
        return after.referenceOrNull(id) ?: before.referenceOrNull(id)
    }
}
