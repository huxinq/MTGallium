package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.state.GameState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.mtgallium.agent.infoset.core.PolicyJson

/** Full-truth digest for trusted live/shadow synchronization checks. */
object ArgentumStateFingerprint {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        allowStructuredMapKeys = true
        classDiscriminator = "type"
        serializersModule = engineSerializersModule
    }

    fun of(state: GameState): String {
        val semanticState = normalizeRoutingIds(json.encodeToJsonElement(state))
        return PolicyJson.sha256(json.encodeToString(JsonElement.serializer(), semanticState))
    }

    /** Privileged diagnostic hashes by serialized GameState root field; values never leave trusted evidence. */
    fun componentDigests(state: GameState): Map<String, String> {
        val semanticState = normalizeRoutingIds(json.encodeToJsonElement(state)) as JsonObject
        return componentDigests(semanticState)
    }

    fun evidence(state: GameState): ArgentumAuthoritativeStateEvidence {
        val semanticState = normalizeRoutingIds(json.encodeToJsonElement(state)) as JsonObject
        return ArgentumAuthoritativeStateEvidence(
            fingerprint = PolicyJson.sha256(json.encodeToString(JsonElement.serializer(), semanticState)),
            componentDigests = componentDigests(semanticState),
        )
    }

    /** Compatibility verifier for privileged replay schemas written before routing-identity v2. */
    fun legacyReplayEvidence(state: GameState): ArgentumAuthoritativeStateEvidence {
        val semanticState = normalizeLegacyDecisionRouting(json.encodeToJsonElement(state)) as JsonObject
        return ArgentumAuthoritativeStateEvidence(
            fingerprint = PolicyJson.sha256(json.encodeToString(JsonElement.serializer(), semanticState)),
            componentDigests = componentDigests(semanticState),
        )
    }

    private fun componentDigests(semanticState: JsonObject): Map<String, String> =
        semanticState.entries.sortedBy { it.key }.associate { (key, value) ->
            key to PolicyJson.sha256(json.encodeToString(JsonElement.serializer(), value))
        }

    /**
     * Decision ids and delayed-trigger ids are generated correlation tokens. A replay constructs
     * fresh UUIDs even when it reaches the same rules state. Both kinds can be copied into linked
     * state, so canonicalize each distinct id by stable serialized occurrence/list order and then
     * replace every reference to it. This preserves ordering, uniqueness, and reference equality
     * while removing only the UUID spelling. Everything else remains byte-for-byte in the digest,
     * including all hidden zones, RNG state, delayed-trigger rules payloads, and continuations.
     */
    private fun normalizeRoutingIds(element: JsonElement): JsonElement {
        val root = element as? JsonObject
        val decisionIds = buildList {
            ((root?.get("pendingDecision") as? JsonObject)?.get("id") as? JsonPrimitive)
                ?.content
                ?.let(::add)
            collectValuesForKey(element, "decisionId", this)
        }.distinct()
        val decisionRouting = decisionIds
            .mapIndexed { index, id -> id to "<decision-routing-id:$index>" }
            .toMap()
        val delayedTriggerRouting = ((element as? JsonObject)?.get("delayedTriggers") as? JsonArray)
            .orEmpty()
            .mapNotNull { trigger ->
                ((trigger as? JsonObject)?.get("id") as? JsonPrimitive)?.content
            }
            .distinct()
            .mapIndexed { index, id -> id to "<delayed-trigger-routing-id:$index>" }
            .toMap()
        return normalizeRoutingIds(element, decisionRouting + delayedTriggerRouting)
    }

    private fun normalizeRoutingIds(
        element: JsonElement,
        routingIds: Map<String, String>,
    ): JsonElement =
        when (element) {
            is JsonArray -> JsonArray(element.map {
                normalizeRoutingIds(it, routingIds)
            })
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> normalizeRoutingIds(value, routingIds) })
            is JsonPrimitive -> routingIds[element.content]?.let(::JsonPrimitive) ?: element
        }

    private fun collectValuesForKey(element: JsonElement, targetKey: String, destination: MutableList<String>) {
        when (element) {
            is JsonArray -> element.forEach { collectValuesForKey(it, targetKey, destination) }
            is JsonObject -> element.forEach { (key, value) ->
                if (key == targetKey && value is JsonPrimitive) destination += value.content
                collectValuesForKey(value, targetKey, destination)
            }
            is JsonPrimitive -> Unit
        }
    }

    private fun normalizeLegacyDecisionRouting(
        element: JsonElement,
        insidePendingDecision: Boolean = false,
    ): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { normalizeLegacyDecisionRouting(it, insidePendingDecision) })
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            when {
                key == "decisionId" -> JsonPrimitive("<decision-routing-id>")
                insidePendingDecision && key == "id" -> JsonPrimitive("<decision-routing-id>")
                else -> normalizeLegacyDecisionRouting(
                    value,
                    insidePendingDecision = insidePendingDecision || key == "pendingDecision",
                )
            }
        })
        is JsonPrimitive -> element
    }
}

data class ArgentumAuthoritativeStateEvidence(
    val fingerprint: String,
    val componentDigests: Map<String, String>,
)
