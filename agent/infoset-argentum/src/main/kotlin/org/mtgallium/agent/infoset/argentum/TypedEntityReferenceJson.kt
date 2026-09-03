package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.TypedEntityReferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.core.PolicyJson

/**
 * Rewrites only serializer-typed entity references in an engine action payload.
 *
 * Argentum owns exhaustive schema traversal. MTGallium owns the semantic alias assigned to each
 * occurrence and collision-preserving JSON representation. Ordinary strings that happen to equal
 * an entity id never appear in the typed occurrence set and remain byte-for-byte unchanged.
 */
internal fun SafeReferenceMap.semanticActionJson(
    action: GameAction,
    encoded: JsonObject,
): JsonObject = maskTypedReferences(
    encoded,
    when (val projection = TypedEntityReferences.action(action)) {
        is TypedEntityReferences.Projection.Complete -> projection.occurrences
        is TypedEntityReferences.Projection.Incomplete -> error(
            "Incomplete typed action reference projection for ${projection.rootType}: ${projection.failure}"
        )
    },
)

/** Rewrites only serializer-typed entity references in a decision-response payload. */
internal fun SafeReferenceMap.semanticDecisionResponseJson(
    response: DecisionResponse,
    encoded: JsonObject,
): JsonObject = maskTypedReferences(
    encoded,
    when (val projection = TypedEntityReferences.response(response)) {
        is TypedEntityReferences.Projection.Complete -> projection.occurrences
        is TypedEntityReferences.Projection.Incomplete -> error(
            "Incomplete typed response reference projection for ${projection.rootType}: ${projection.failure}"
        )
    },
)

private fun SafeReferenceMap.maskTypedReferences(
    encoded: JsonObject,
    occurrences: List<TypedEntityReferences.Occurrence>,
): JsonObject {
    val normalized = occurrences.map { occurrence ->
        occurrence.copy(path = occurrence.path.filterNot {
            it is TypedEntityReferences.PathSegment.PolymorphicPayload
        })
    }
    return rewriteTypedNode(encoded, normalized) as JsonObject
}

private fun SafeReferenceMap.rewriteTypedNode(
    node: JsonElement,
    occurrences: List<TypedEntityReferences.Occurrence>,
): JsonElement {
    if (occurrences.isEmpty()) return node
    if (occurrences.all { it.path.isEmpty() }) {
        val ids = occurrences.map { it.entityId }.distinct()
        require(ids.size == 1) { "One serialized leaf resolved to several entity ids: $ids" }
        val primitive = node as? JsonPrimitive
            ?: error("Typed entity leaf is not a JSON primitive: $node")
        require(primitive.isString && primitive.content == ids.single().value) {
            "Typed entity leaf ${primitive.content} disagrees with ${ids.single().value}"
        }
        return JsonPrimitive(semanticReference(ids.single()))
    }
    require(occurrences.none { it.path.isEmpty() }) {
        "Typed entity path ended beside nested occurrences"
    }

    return when (node) {
        is JsonArray -> JsonArray(node.mapIndexed { index, value ->
            val nested = occurrences.mapNotNull { occurrence ->
                val head = occurrence.path.firstOrNull() as? TypedEntityReferences.PathSegment.Element
                    ?: return@mapNotNull null
                if (head.index != index) null else occurrence.copy(path = occurrence.path.drop(1))
            }
            rewriteTypedNode(value, nested)
        })
        is JsonObject -> when {
            occurrences.any { it.path.firstOrNull() is TypedEntityReferences.PathSegment.MapEntry } ->
                rewriteTypedMap(node, occurrences)
            else -> JsonObject(node.mapValues { (name, value) ->
                val nested = occurrences.mapNotNull { occurrence ->
                    val head = occurrence.path.firstOrNull() as? TypedEntityReferences.PathSegment.Field
                        ?: return@mapNotNull null
                    if (head.name != name) null else occurrence.copy(path = occurrence.path.drop(1))
                }
                rewriteTypedNode(value, nested)
            })
        }
        else -> error("Typed entity path continues through scalar JSON: $node")
    }
}

private fun SafeReferenceMap.rewriteTypedMap(
    node: JsonObject,
    occurrences: List<TypedEntityReferences.Occurrence>,
): JsonObject {
    data class Entry(val key: String, val value: JsonElement)

    val entries = node.entries.mapIndexed { index, (rawKey, rawValue) ->
        val atEntry = occurrences.mapNotNull { occurrence ->
            val head = occurrence.path.firstOrNull() as? TypedEntityReferences.PathSegment.MapEntry
                ?: return@mapNotNull null
            if (head.index != index) null else head.role to occurrence.copy(path = occurrence.path.drop(1))
        }
        val keyOccurrences = atEntry.filter { it.first == TypedEntityReferences.PathSegment.MapEntry.Role.KEY }
            .map { it.second }
        val valueOccurrences = atEntry.filter { it.first == TypedEntityReferences.PathSegment.MapEntry.Role.VALUE }
            .map { it.second }
        val key = if (keyOccurrences.isEmpty()) {
            rawKey
        } else {
            require(keyOccurrences.all { it.path.isEmpty() }) { "Map-key entity path continued past its key" }
            val ids = keyOccurrences.map { it.entityId }.distinct()
            require(ids.size == 1 && rawKey == ids.single().value) {
                "Typed entity map key $rawKey disagrees with $ids"
            }
            semanticReference(ids.single())
        }
        Entry(key, rewriteTypedNode(rawValue, valueOccurrences))
    }

    val converted = linkedMapOf<String, JsonElement>()
    entries.groupBy(Entry::key).toSortedMap().forEach { (semanticKey, equivalentEntries) ->
        if (equivalentEntries.size == 1) {
            converted[semanticKey] = equivalentEntries.single().value
        } else {
            equivalentEntries.map(Entry::value).sortedBy(PolicyJson::canonical)
                .forEachIndexed { index, value -> converted["$semanticKey#$index"] = value }
        }
    }
    return JsonObject(converted)
}
