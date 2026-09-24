package org.mtgallium.agent.neural

import java.util.Collections
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.*

/** Bytes encode factual structure; learned encoders, not this adapter, choose useful features. */
@Serializable
data class FactualTensorSchema(
    val version: String = "factual-policy-json-bytes-v1",
    val maximumViewBytes: Int = 16384,
    val maximumEventBytes: Int = 4096,
    val maximumActionBytes: Int = 4096,
    val maximumCandidates: Int = 64,
) {
    init {
        require(version == "factual-policy-json-bytes-v1")
        require(maximumViewBytes in 256..65536 && maximumEventBytes in 128..16384)
        require(maximumActionBytes in 128..16384 && maximumCandidates in 1..256)
    }
    val identity: String get() = PolicyJson.digest(PolicyJson.format.encodeToJsonElement(this))
}

/** Unpadded byte tokens are UTF-8 bytes plus one; zero is reserved for transport padding. */
@Serializable
data class FactualDecisionTensors(
    val view: List<Int>,
    val actions: List<List<Int>>,
    val rulesExhaustive: Boolean,
    val profileExhaustive: Boolean,
) {
    fun validate(schema: FactualTensorSchema) {
        require(view.size in 1..schema.maximumViewBytes && view.all { it in 1..256 })
        require(actions.size in 1..schema.maximumCandidates)
        require(actions.all { row -> row.size in 1..schema.maximumActionBytes && row.all { it in 1..256 } })
        require(!rulesExhaustive || profileExhaustive)
    }
}

class FactualEncodingException(message: String) : IllegalArgumentException(message)

/**
 * Source-independent encoding of the existing safe language. Reference spellings are never tokens.
 * [referenceGroups] comes from the trusted adapter: semantic action references may name a GROUP of
 * visible objects, not an exact occurrence. Its members must already occur in the current safe view.
 */
class FactualPolicyEncoder(val schema: FactualTensorSchema = FactualTensorSchema()) {
    fun decision(site: DecisionSite, referenceGroups: Map<String, List<String>> = emptyMap()): FactualDecisionTensors {
        val state = site.epistemic
        require(site.actor == state.perspectivePlayerId && !state.terminated)
        val observation = PolicyJson.format.encodeToJsonElement(state.observation).jsonObject.toMutableMap()
        // Player names and card-definition keys are presentation/registry identifiers, not inputs.
        observation["players"] = JsonArray(state.observation.players.map { player ->
            JsonObject(PolicyJson.format.encodeToJsonElement(player).jsonObject.filterKeys { it != "name" })
        })
        val knowledge = PolicyJson.format.encodeToJsonElement(state.knowledge).jsonObject.toMutableMap()
        knowledge["deckCardCounts"] = JsonObject(state.observation.players.associate { player ->
            player.playerId to (state.knowledge.deckCardCounts[player.playerId]?.let {
                PolicyJson.format.encodeToJsonElement(it)
            } ?: JsonNull)
        })
        knowledge["unlocatedCardCounts"] = JsonObject(state.observation.players.associate { player ->
            player.playerId to (state.knowledge.unlocatedCardCounts[player.playerId]?.let {
                PolicyJson.format.encodeToJsonElement(it)
            } ?: JsonNull)
        })
        val rawView = buildJsonObject {
            put("observation", JsonObject(observation)); put("exactKnowledge", JsonObject(knowledge))
        }
        val scope = References(state.perspectivePlayerId, state.observation.players.map { it.playerId }, rememberedAt(state.history))
        scope.collect(rawView)
        val groups = referenceGroups.mapValues { (name, members) ->
            require(members.isNotEmpty() && members.distinct().size == members.size) { "Malformed visible-reference group: $name" }
            members.map { scope.existing(it) }.sorted()
        }
        val view = bytes(scope.transform(rawView), schema.maximumViewBytes, "current view")
        val actions = site.expansion.candidates.map { action ->
            val raw = buildJsonObject {
                put("kind", action.kind.name); put("operationFamily", action.operationFamily.name)
                put("intent", PolicyJson.format.encodeToJsonElement(action.actionIntent))
                put("payload", action.canonicalPayload)
            }
            bytes(scope.transform(raw, groups = groups), schema.maximumActionBytes, "candidate action")
        }
        return FactualDecisionTensors(view, frozen(actions), site.expansion.isExhaustive,
            site.expansion.isProfileExhaustive).also { it.validate(schema) }
    }

    /** Isolated-event convenience. Stateful consumers use [events] to retain qualified continuity. */
    fun event(event: PolicyHistoryEvent, player: String, players: List<String>): List<Int> =
        encodeEvent(event, player, players, linkedMapOf())

    /** Prefix names are assigned before the suffix; future events assign no earlier names. */
    fun events(history: List<PolicyHistoryEvent>, player: String, players: List<String>, fromCursor: Int = 0): List<List<Int>> {
        require(fromCursor in 0..history.size)
        val remembered = rememberedAt(history.take(fromCursor))
        return frozen(history.drop(fromCursor).map { encodeEvent(it, player, players, remembered) })
    }

    private fun encodeEvent(event: PolicyHistoryEvent, player: String, players: List<String>,
        remembered: MutableMap<String, String>): List<Int> {
        require(player in players && players.distinct().size == players.size)
        require(event.audience.scope == PolicyAudienceScope.PUBLIC || player in event.audience.entitledPlayerIds) {
            "Player is not entitled to this event"
        }
        val raw = eventValue(event)
        val scope = References(player, players, remembered)
        scope.collect(raw)
        return bytes(scope.transform(raw), schema.maximumEventBytes, "delivered event")
    }

    private fun eventValue(event: PolicyHistoryEvent): JsonObject {
        val detail = event.detail
        if (detail is PerspectiveEventDetail.UnsupportedVisibleTransition)
            throw FactualEncodingException("UNSUPPORTED_VISIBLE_TRANSITION")
        if (detail == null) return visibleDelta(event)
        return buildJsonObject {
            put("kind", event.kind.name); put("actor", event.actor?.let(::JsonPrimitive) ?: JsonNull)
            if (event.kind == PolicyHistoryEventKind.PRIVATE_DECISION_OCCURRED) {
                put("privateDecisionOccurred", true)
            } else {
                put("detail", PolicyJson.format.encodeToJsonElement(PerspectiveEventDetail.serializer(), detail))
                // Explicitly delivered facts only. Never infer action meaning from display labels.
                for (key in listOf("privatePayload", "sourceName", "targetNames")) event.payload[key]?.let { put(key, it) }
            }
        }
    }

    /** Payload from the native visible-transition recorder. */
    private fun visibleDelta(event: PolicyHistoryEvent): JsonObject {
        val payload = event.payload
        if (event.kind !in setOf(PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION, PolicyHistoryEventKind.FORCED_TRANSITION) ||
            !payload.keys.containsAll(setOf("fromObservation", "toObservation", "zoneDelta")) ||
            (payload.keys - setOf("fromObservation", "toObservation", "zoneDelta", "priorityFrom", "priorityTo")).isNotEmpty())
            throw FactualEncodingException("UNTYPED_HISTORY_EVENT")
        val delta = payload.getValue("zoneDelta").jsonArray.map { value ->
            val row = value.jsonObject
            require(row.keys == setOf("key", "before", "after"))
            val parts = row.getValue("key").jsonPrimitive.content.split(':', limit = 3)
            require(parts.size == 3 && parts.all { it.isNotBlank() })
            require(row.getValue("before").jsonPrimitive.int >= 0 && row.getValue("after").jsonPrimitive.int >= 0)
            buildJsonObject {
                put("owner", parts[0]); put("zone", parts[1]); put("cardName", parts[2])
                put("before", row.getValue("before")); put("after", row.getValue("after"))
            }
        }
        return buildJsonObject {
            put("kind", event.kind.name); put("visibleZoneDelta", JsonArray(delta))
            for (key in listOf("priorityFrom", "priorityTo")) payload[key]?.let { put(key, it) }
        }
    }

    /** Only qualified remembered handles survive between frames; snapshot references remain local. */
    private fun rememberedAt(history: List<PolicyHistoryEvent>): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        fun visit(value: JsonElement, field: String = "", depth: Int = 0) {
            require(depth <= 64)
            when (value) {
                is JsonObject -> value.filterKeys { it !in OMIT }.forEach { (key, child) ->
                    rememberedKey(key, "")?.let { result.getOrPut(it) { "remembered-${result.size}" } }
                    visit(child, key, depth + 1)
                }
                is JsonArray -> value.forEach { visit(it, field, depth + 1) }
                is JsonPrimitive -> if (value.isString) rememberedKey(value.content, field)?.let {
                    result.getOrPut(it) { "remembered-${result.size}" }
                }
            }
        }
        history.forEach { visit(eventValue(it)) }
        return result
    }

    private fun bytes(value: JsonElement, limit: Int, kind: String): List<Int> {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > limit) throw FactualEncodingException("$kind exceeds declared byte limit: ${bytes.size} > $limit")
        return frozen(bytes.map { (it.toInt() and 255) + 1 })
    }

    private class References(player: String, players: List<String>, private val remembered: MutableMap<String, String>) {
        private val players = (listOf(player) + players.filter { it != player }).mapIndexed { i, id ->
            id to if (i == 0) "self" else "other-$i"
        }.toMap()
        private val objects = linkedMapOf<String, String>()
        fun existing(value: String): String = players[value] ?: objects[value]
            ?: throw FactualEncodingException("Action reference group contains an object absent from the safe view")
        fun collect(value: JsonElement, field: String = "", depth: Int = 0) {
            require(depth <= 64) { "Factual input nesting exceeds its bound" }
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    if (key !in OMIT) {
                        if (isReference(key)) bind(key, "")
                        collect(child, key, depth + 1)
                    }
                }
                is JsonArray -> value.forEach { collect(it, field, depth + 1) }
                is JsonPrimitive -> if (value.isString && value.content !in players &&
                    (field in REFERENCE_FIELDS || isReference(value.content))) bind(value.content, field)
            }
        }
        private fun bind(value: String, field: String) {
            objects.getOrPut(value) {
                rememberedKey(value, field)?.let { remembered.getOrPut(it) { "remembered-${remembered.size}" } }
                    ?: "ref-${objects.size}"
            }
        }
        private fun group(value: String, groups: Map<String, List<String>>): Pair<List<String>, Int?>? {
            groups[value]?.let { return it to null }
            val marker = value.lastIndexOf('#')
            if (marker < 0) return null
            val ordinal = value.substring(marker + 1).toIntOrNull() ?: return null
            val members = groups[value.substring(0, marker)] ?: return null
            return if (ordinal in members.indices) members to ordinal else null
        }
        fun transform(value: JsonElement, field: String = "", groups: Map<String, List<String>> = emptyMap(), depth: Int = 0): JsonElement {
            require(depth <= 64)
            return when (value) {
                is JsonObject -> {
                    val entries = value.filterKeys { it !in OMIT }.map { (key, child) ->
                        val mapped = players[key] ?: objects[key] ?: group(key, groups)?.let { (members, ordinal) ->
                            members.joinToString(prefix = "visibleGroup(", postfix = ")") +
                                (ordinal?.let { "#$it" } ?: "")
                        }
                            ?: key.also {
                                if (isReference(it) || HASH.matches(it) || ENTITY.matches(it))
                                    throw FactualEncodingException("Unqualified reference used as a factual map key")
                            }
                        mapped to transform(child, key, groups, depth + 1)
                    }
                    require(entries.map { it.first }.distinct().size == entries.size) { "Reference-key normalization collided" }
                    JsonObject(entries.sortedBy { it.first }.toMap())
                }
                is JsonArray -> {
                    val items = value.map { transform(it, field, groups, depth + 1) }
                    JsonArray(if (field in SET_FIELDS) items.sortedBy { it.toString() } else items)
                }
                is JsonPrimitive -> {
                    if (!value.isString) value
                    else {
                        val text = value.content
                        when {
                            text in players -> JsonPrimitive(players.getValue(text))
                            group(text, groups) != null -> {
                                val (members, ordinal) = requireNotNull(group(text, groups))
                                buildJsonObject {
                                    put("visibleGroup", JsonArray(members.map(::JsonPrimitive)))
                                    ordinal?.let { put("ordinal", it) }
                                }
                            }
                            text in objects -> JsonPrimitive(objects.getValue(text))
                            isReference(text) || HASH.matches(text) || ENTITY.matches(text) ->
                                throw FactualEncodingException("Unqualified opaque reference in factual input")
                            else -> value
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val OMIT = setOf("observationDigest", "knowledgeDigest", "informationStateDigest", "signature",
            "semanticSignature", "eventId", "proposalSeed", "proposalVersion", "schemaVersion", "definitionId",
            "cardDefinitionId", "winnerId", "label", "policyTags", "shuffleEpoch", "decisionId", "unsupportedReasons", "prompt")
        private val SET_FIELDS = setOf("types", "subtypes", "colors", "keywords", "targetRelations")
        private val REFERENCE_FIELDS = setOf("objectRef", "sourceObjectRef", "targetObjectRef", "targetObjectRefs",
            "relatedObjectRefs", "defenderObjectRef", "attackerObjectRef", "blockerObjectRef", "blockerObjectRefs",
            "blockedAttackerObjectRefs", "knowledgeObjectKey", "knowledgeObjectKeys", "libraryBottomKnowledgeObjectKeys",
            "sourceKnowledgeObjectKey", "invalidatedKnowledgeObjectKeys", "triggeringObjectRef", "subjectObjectRef", "attachedTo", "attachments")
        private val KNOWLEDGE_FIELDS = setOf("knowledgeObjectKey", "knowledgeObjectKeys", "sourceKnowledgeObjectKey",
            "libraryBottomKnowledgeObjectKeys", "invalidatedKnowledgeObjectKeys")
        private fun rememberedKey(value: String, field: String): String? = when {
            value.startsWith("history-object:v1:") -> value.removePrefix("history-object:v1:")
            field in KNOWLEDGE_FIELDS -> value
            else -> null
        }
        private val HASH = Regex("[a-fA-F0-9]{32,64}")
        private val ENTITY = Regex("e[0-9]+")
        private fun isReference(value: String) = listOf("zone:", "object:", "history-object:", "stack:",
            "stack-target:", "choice:", "combat-edge:", "knowledge-object-").any(value::startsWith)
        private fun <T> frozen(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
    }
}
