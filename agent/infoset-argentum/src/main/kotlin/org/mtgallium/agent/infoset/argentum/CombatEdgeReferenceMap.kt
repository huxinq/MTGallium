package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.DamageEdge
import com.wingedsheep.sdk.model.EntityId

/**
 * One authoritative, contract-scoped mapping between native Argentum damage-edge wire ids and the
 * perspective-safe references exposed to a chooser.
 *
 * A combat edge is identified by the chooser-authorized contract alone: its direction, its
 * trample-drain role, and the viewer-safe references of its two endpoints. Native entity-id
 * spelling and contract enumeration order never participate, so corresponding edges in different
 * hypothetical worlds receive the same reference. Two edges in one contract that collapse to the
 * same descriptor are ambiguous; this boundary fails explicitly instead of selecting one by raw-id
 * order.
 *
 * The same instance relabels the projected contract's `edges[*].id` and every response
 * `DamageEdgeAmount.edgeId` encoded for that contract. The policy-facing side therefore never sees
 * a native wire id, while the adapter keeps the native ids needed for execution.
 */
internal class CombatEdgeReferenceMap {
    private val localByNative = linkedMapOf<String, String>()
    private val nativeByLocal = linkedMapOf<String, String>()

    val size: Int get() = localByNative.size

    /**
     * Registers the live chooser contract's edges and returns each edge's contract-local reference
     * in input order. Registration replaces any previous contract; a repeated native id or a
     * descriptor collision is refused rather than bound to an arbitrary edge.
     */
    fun register(edges: List<DamageEdge>, endpoint: (EntityId) -> String): List<String> {
        localByNative.clear()
        nativeByLocal.clear()
        return edges.map { edge ->
            val local = descriptor(edge, endpoint)
            require(nativeByLocal.put(local, edge.id) == null) {
                "Combat contract contains ambiguous edges for contract-local reference $local"
            }
            require(localByNative.put(edge.id, local) == null) {
                "Combat contract repeats native edge id ${edge.id}"
            }
            local
        }
    }

    /** Resolves a native wire id from the current contract, or fails as stale/unknown. */
    fun local(nativeEdgeId: String): String = localByNative[nativeEdgeId]
        ?: error("Combat response edge $nativeEdgeId is not part of the projected chooser contract")

    private fun descriptor(edge: DamageEdge, endpoint: (EntityId) -> String): String = buildString {
        append(REFERENCE_PREFIX)
        append(edge.direction.name)
        append(':')
        append(if (edge.isTrampleDrain) "drain" else "normal")
        append(':')
        append(endpoint(edge.sourceId))
        append("->")
        append(endpoint(edge.targetId))
    }

    private companion object {
        const val REFERENCE_PREFIX = "combat-edge:v1:"
    }
}
