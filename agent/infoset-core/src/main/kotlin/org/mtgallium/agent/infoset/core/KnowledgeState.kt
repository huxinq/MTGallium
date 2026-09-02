package org.mtgallium.agent.infoset.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PERSPECTIVE_EVENT_SCHEMA_V1: Int = 1
const val PERSPECTIVE_EVENT_SCHEMA_V2: Int = 2
const val PERSPECTIVE_EVENT_SCHEMA_V3: Int = 3
const val KNOWLEDGE_SCHEMA_V1: Int = 1
const val KNOWLEDGE_SCHEMA_V2: Int = 2
const val KNOWLEDGE_SCHEMA_CURRENT: Int = KNOWLEDGE_SCHEMA_V2

/**
 * A typed, perspective-safe description of what changed between two policy decisions.
 *
 * These records describe what the viewer was entitled to learn. They are not serialized engine
 * events and must never contain authoritative entity ids, hidden card identities, or engine RNG.
 */
@Serializable
sealed interface PerspectiveEventDetail {
    val schemaVersion: Int

    @Serializable
    @SerialName("choice")
    data class Choice(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V3,
        val semanticSignature: String?,
        val choiceKind: String,
        val operationFamily: SemanticOperationFamily?,
        val privateToActor: Boolean,
        /** Actor-only exact optionality. Null prevents other viewers learning the rejected set. */
        val strategicallyOptional: Boolean?,
        /**
         * Actor-only, top-to-bottom order within the suffix put on the library bottom by a London
         * mulligan choice. Empty for every other choice and for non-entitled viewers.
         */
        val libraryBottomCardNames: List<String> = emptyList(),
        /** Stable perspective-local bindings corresponding to [libraryBottomCardNames]. */
        val libraryBottomKnowledgeObjectKeys: List<String> = emptyList(),
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("zone_change")
    data class ZoneChange(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V2,
        val ownerId: String,
        val fromZone: String?,
        val toZone: String,
        /** Null means that the viewer observed a card movement but not its identity. */
        val cardName: String?,
        /** Stable only within the viewer's safe history; never an Argentum EntityId. */
        val knowledgeObjectKey: String? = null,
        /**
         * Whether this observed move leaves a current object for this perspective.
         *
         * False records an observed named movement such as a token leaving the battlefield that
         * subsequently ceases to exist. The key is removed rather than relocated. Defaulting to
         * true preserves the meaning of already-persisted zone-change records.
         */
        val continuesAsCurrentObject: Boolean = true,
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("draw")
    data class Draw(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val playerId: String,
        val count: Int,
        /** Contains only cards whose identities the viewer was entitled to know. */
        val knownCardNames: List<String> = emptyList(),
        val knowledgeObjectKeys: List<String> = emptyList(),
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("reveal")
    data class Reveal(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val ownerId: String,
        val zone: String?,
        val cardNames: List<String>,
        val knowledgeObjectKeys: List<String> = emptyList(),
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("look")
    data class Look(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val ownerId: String,
        val zone: String,
        /** Ordered when the rules exposed an order, otherwise treated as a multiset. */
        val cardNames: List<String>,
        val knowledgeObjectKeys: List<String> = emptyList(),
        val ordered: Boolean,
        val fromTop: Boolean,
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("library_reorder")
    data class LibraryReorder(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val playerId: String,
        /** Top card first. Empty is permitted only when the identities were not observable. */
        val orderedCardNames: List<String>,
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("shuffle")
    data class Shuffle(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V2,
        val playerId: String,
        val cause: String,
        /**
         * Perspective-local opaque keys whose earlier knowledge no longer names a current object
         * after this shuffle. Empty preserves the behavior of schema-v1 records.
         */
        val invalidatedKnowledgeObjectKeys: List<String> = emptyList(),
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("life_change")
    data class LifeChange(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val playerId: String,
        val oldLife: Int,
        val newLife: Int,
        val reason: String,
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("damage")
    data class Damage(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val sourceName: String?,
        val sourceObjectRef: String?,
        val targetName: String?,
        val targetObjectRef: String?,
        val amount: Int,
        val combat: Boolean,
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("counter_change")
    data class CounterChange(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val objectRef: String?,
        val objectName: String,
        val counterType: String,
        val delta: Int,
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("object_state")
    data class ObjectState(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val objectRef: String?,
        val objectName: String?,
        val change: String,
        val value: String? = null,
        val relatedObjectRefs: List<String> = emptyList(),
    ) : PerspectiveEventDetail

    /** Public cause-and-result structure that is not recoverable from a later board snapshot. */
    @Serializable
    @SerialName("causal")
    data class Causal(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val eventType: String,
        val actorId: String?,
        val sourceName: String?,
        val sourceObjectRef: String?,
        val targetNames: List<String> = emptyList(),
        val targetObjectRefs: List<String> = emptyList(),
        val result: String? = null,
        val numericValue: Int? = null,
        /** Perspective-local continuity handle when this event visibly relocates its source. */
        val sourceKnowledgeObjectKey: String? = null,
        val sourceOwnerId: String? = null,
        val sourceZoneAfter: String? = null,
    ) : PerspectiveEventDetail

    /** A visible resource change such as mana being added or spent. */
    @Serializable
    @SerialName("resource_change")
    data class ResourceChange(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val playerId: String,
        val resource: String,
        val delta: Int,
        val reason: String?,
        val sourceName: String? = null,
        val sourceObjectRef: String? = null,
    ) : PerspectiveEventDetail

    /** A visible characteristic change whose cause matters even if it later expires. */
    @Serializable
    @SerialName("characteristic_change")
    data class CharacteristicChange(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val objectRef: String?,
        val objectName: String,
        val characteristic: String,
        val value: String,
        val sourceName: String?,
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("combat")
    data class Combat(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V2,
        val declaration: String,
        val actorId: String?,
        /** Legacy v1 routing map, retained so old artifacts remain readable. */
        val assignments: Map<String, List<String>> = emptyMap(),
        /** Named, perspective-safe relationships used by replay and future policy consumers. */
        val subjects: List<PerspectiveCombatSubject> = emptyList(),
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("turn_structure")
    data class TurnStructure(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val turnNumber: Int?,
        val phase: String?,
        val step: String?,
        val activePlayerId: String?,
        val priorityPlayerId: String?,
    ) : PerspectiveEventDetail

    @Serializable
    @SerialName("terminal")
    data class Terminal(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val winnerId: String?,
        val reason: String,
    ) : PerspectiveEventDetail

    /**
     * A safe snapshot changed but the transition family is outside the supported event vocabulary.
     * Presence of this record prevents a false losslessness claim: the knowledge reducer marks the
     * resulting state incomplete and hybrid sampling must fail closed.
     */
    @Serializable
    @SerialName("unsupported_visible_transition")
    data class UnsupportedVisibleTransition(
        override val schemaVersion: Int = PERSPECTIVE_EVENT_SCHEMA_V1,
        val engineEventType: String,
        val reason: String,
    ) : PerspectiveEventDetail
}

@Serializable
data class PerspectiveCombatSubject(
    val objectRef: String?,
    val objectName: String,
    val relatedObjectRefs: List<String> = emptyList(),
    val relatedObjectNames: List<String> = emptyList(),
    /** Used by damage-assignment records; null for declarations and ordering. */
    val amountsByRelatedObjectRef: Map<String, Int> = emptyMap(),
)

@Serializable
data class PolicyZoneKnowledge(
    val ownerId: String,
    val zone: String,
    val size: Int,
    /** Exact identities known to the viewer, represented as a multiset. */
    val knownCardCounts: Map<String, Int> = emptyMap(),
)

@Serializable
data class PolicyKnownObject(
    val knowledgeObjectKey: String,
    val ownerId: String,
    val zone: String,
    val cardName: String,
)

@Serializable
data class PolicyKnownLibraryOrder(
    val playerId: String,
    val shuffleEpoch: Int,
    /** Top card first; null entries are positions whose identity remains unknown. */
    val top: List<String?> = emptyList(),
    /** Top-to-bottom within the known suffix; the final entry is the bottommost card. */
    val bottom: List<String?> = emptyList(),
)

/**
 * Deterministic facts recoverable from the known decklists and the viewer's safe event ledger.
 * Probabilities and sampled worlds deliberately do not live in this DTO.
 */
@Serializable
data class PolicyKnowledgeState(
    val schemaVersion: Int = KNOWLEDGE_SCHEMA_CURRENT,
    val perspectivePlayerId: String,
    val deckCardCounts: Map<String, Map<String, Int>> = emptyMap(),
    val zones: List<PolicyZoneKnowledge> = emptyList(),
    val knownObjects: List<PolicyKnownObject> = emptyList(),
    val knownLibraryOrders: List<PolicyKnownLibraryOrder> = emptyList(),
    /** Known-deck cards not assigned to a viewer-known identity in a particular zone. */
    val unlocatedCardCounts: Map<String, Map<String, Int>> = emptyMap(),
    /** False means at least one visible transition could not be represented safely and exactly. */
    val epistemicallyComplete: Boolean = true,
    val unsupportedReasons: List<String> = emptyList(),
    val knowledgeDigest: String,
) {
    init {
        require(schemaVersion == KNOWLEDGE_SCHEMA_CURRENT)
        require(zones.all { it.size >= 0 })
        require(deckCardCounts.values.flatMap { it.values }.all { it >= 0 })
        require(unlocatedCardCounts.values.flatMap { it.values }.all { it >= 0 })
        require(epistemicallyComplete || unsupportedReasons.isNotEmpty())
    }

    companion object {
        fun empty(perspectivePlayerId: String): PolicyKnowledgeState = PolicyKnowledgeState(
            perspectivePlayerId = perspectivePlayerId,
            epistemicallyComplete = false,
            unsupportedReasons = listOf("known deck and safe event ledger were not supplied"),
            knowledgeDigest = PolicyJson.sha256("empty-knowledge:$perspectivePlayerId"),
        )
    }
}

/**
 * Forkable event-derived knowledge state used during ordinary forward play.
 *
 * [PolicyKnowledgeReducer] remains the replay oracle: it constructs a fresh accumulator and applies
 * the complete safe prefix. Production worlds keep one accumulator per perspective and append each
 * event once.
 */
class PolicyKnowledgeAccumulator private constructor(
    private val knownObjects: LinkedHashMap<String, PolicyKnownObject>,
    private val shuffleEpochs: LinkedHashMap<String, Int>,
    private val knownOrders: LinkedHashMap<String, MutableList<String?>>,
    private val knownBottomOrders: LinkedHashMap<String, MutableList<String?>>,
    private val unsupported: MutableList<String>,
) {
    constructor() : this(linkedMapOf(), linkedMapOf(), linkedMapOf(), linkedMapOf(), mutableListOf())

    fun fork(): PolicyKnowledgeAccumulator = PolicyKnowledgeAccumulator(
        knownObjects = LinkedHashMap(knownObjects),
        shuffleEpochs = LinkedHashMap(shuffleEpochs),
        knownOrders = LinkedHashMap(knownOrders.mapValues { (_, order) -> order.toMutableList() }),
        knownBottomOrders = LinkedHashMap(knownBottomOrders.mapValues { (_, order) -> order.toMutableList() }),
        unsupported = unsupported.toMutableList(),
    )

    fun append(event: PolicyHistoryEvent) {
        when (val detail = event.detail) {
            null -> Unit // Legacy compatibility; snapshot data still supplies current facts.
            is PerspectiveEventDetail.Choice -> {
                if (detail.libraryBottomCardNames.isNotEmpty()) {
                    require(detail.libraryBottomCardNames.size == detail.libraryBottomKnowledgeObjectKeys.size)
                    val owner = requireNotNull(event.actor) { "Library-bottom knowledge requires an actor" }
                    knownBottomOrders[owner] = detail.libraryBottomCardNames
                        .map { it as String? }
                        .toMutableList()
                    detail.libraryBottomCardNames.forEachIndexed { index, name ->
                        val key = detail.libraryBottomKnowledgeObjectKeys[index]
                        knownObjects[key] = PolicyKnownObject(key, owner, "LIBRARY", name)
                    }
                }
            }
            is PerspectiveEventDetail.ZoneChange -> {
                if (!detail.continuesAsCurrentObject) {
                    detail.knowledgeObjectKey?.let(knownObjects::remove)
                } else if (detail.cardName != null && detail.knowledgeObjectKey != null) {
                    knownObjects[detail.knowledgeObjectKey] = PolicyKnownObject(
                        detail.knowledgeObjectKey,
                        detail.ownerId,
                        detail.toZone,
                        detail.cardName,
                    )
                } else if (detail.fromZone != null) {
                    // A hidden-to-hidden move may have moved any previously known copy in the
                    // source zone. Until disjunctive location constraints are represented, retain
                    // the evidence in the ledger but fail the exact reducer closed.
                    val ambiguous = knownObjects.values.filter {
                        it.ownerId == detail.ownerId && it.zone == detail.fromZone
                    }
                    if (ambiguous.isNotEmpty()) {
                        ambiguous.forEach { knownObjects.remove(it.knowledgeObjectKey) }
                        unsupported += "ambiguous hidden move at event ${event.eventId}: " +
                            "${detail.ownerId}:${detail.fromZone}->${detail.toZone} requires a location constraint"
                    }
                }
            }
            is PerspectiveEventDetail.Causal -> {
                val key = detail.sourceKnowledgeObjectKey
                val owner = detail.sourceOwnerId
                val zone = detail.sourceZoneAfter
                val name = detail.sourceName
                if (key != null && owner != null && zone != null && name != null) {
                    knownObjects[key] = PolicyKnownObject(key, owner, zone, name)
                }
            }
            is PerspectiveEventDetail.Draw -> {
                detail.knownCardNames.forEachIndexed { index, name ->
                    val key = detail.knowledgeObjectKeys.getOrNull(index) ?: return@forEachIndexed
                    knownObjects[key] = PolicyKnownObject(key, detail.playerId, "HAND", name)
                }
                val order = knownOrders[detail.playerId]
                if (order != null) {
                    repeat(minOf(detail.count, order.size)) { order.removeAt(0) }
                    if (order.isEmpty()) knownOrders.remove(detail.playerId)
                }
            }
            is PerspectiveEventDetail.Reveal -> {
                detail.cardNames.forEachIndexed { index, name ->
                    val key = detail.knowledgeObjectKeys.getOrNull(index) ?: return@forEachIndexed
                    knownObjects[key] = PolicyKnownObject(
                        key,
                        detail.ownerId,
                        detail.zone ?: "UNKNOWN",
                        name,
                    )
                }
            }
            is PerspectiveEventDetail.Look -> {
                detail.cardNames.forEachIndexed { index, name ->
                    val key = detail.knowledgeObjectKeys.getOrNull(index) ?: return@forEachIndexed
                    knownObjects[key] = PolicyKnownObject(key, detail.ownerId, detail.zone, name)
                }
                if (detail.zone == "LIBRARY" && detail.ordered && detail.fromTop) {
                    knownOrders[detail.ownerId] = detail.cardNames.map { it as String? }.toMutableList()
                }
            }
            is PerspectiveEventDetail.LibraryReorder -> {
                knownOrders[detail.playerId] = detail.orderedCardNames.map { it as String? }.toMutableList()
            }
            is PerspectiveEventDetail.Shuffle -> {
                shuffleEpochs.compute(detail.playerId) { _, epoch -> (epoch ?: 0) + 1 }
                knownOrders.remove(detail.playerId)
                knownBottomOrders.remove(detail.playerId)
                detail.invalidatedKnowledgeObjectKeys.forEach(knownObjects::remove)
                knownObjects.entries.removeAll { (_, known) ->
                    known.ownerId == detail.playerId && known.zone == "LIBRARY"
                }
            }
            is PerspectiveEventDetail.UnsupportedVisibleTransition -> {
                unsupported += "${detail.engineEventType}: ${detail.reason}"
            }
            else -> Unit
        }
    }

    fun append(events: Iterable<PolicyHistoryEvent>) = events.forEach(::append)

    fun snapshot(
        perspectivePlayerId: String,
        knownDecks: Map<String, Map<String, Int>>,
        currentObservation: PolicyObservation,
    ): PolicyKnowledgeState {
        val zoneSizes = linkedMapOf<Pair<String, String>, Int>()
        val knownByZone = linkedMapOf<Pair<String, String>, MutableMap<String, Int>>()
        currentObservation.zones.forEach { zone ->
            val key = zone.ownerId to zone.zone
            zoneSizes[key] = zone.size
            val counts = zone.cards.groupingBy { it.name }.eachCount().toMutableMap()
            knownByZone[key] = counts
        }

        knownObjects.values.groupBy { it.ownerId to it.zone }.forEach { (zone, objects) ->
            val counts = objects.groupingBy { it.cardName }.eachCount()
            val current = knownByZone.getOrPut(zone) { linkedMapOf() }
            counts.forEach { (name, count) -> current[name] = maxOf(current[name] ?: 0, count) }
        }

        val zones = zoneSizes.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (key, size) ->
            PolicyZoneKnowledge(
                ownerId = key.first,
                zone = key.second,
                size = size,
                knownCardCounts = knownByZone[key].orEmpty().toSortedMap(),
            )
        }
        val unlocated = knownDecks.mapValues { (player, deck) ->
            val located = zones.filter { it.ownerId == player }
                .flatMap { it.knownCardCounts.entries }
                .groupingBy { it.key }
                .fold(0) { total, entry -> total + entry.value }
            deck.mapValues { (name, count) -> (count - (located[name] ?: 0)).coerceAtLeast(0) }
                .filterValues { it > 0 }
                .toSortedMap()
        }.toSortedMap()
        val orders = knownDecks.keys.sorted().map { player ->
            val librarySize = zoneSizes[player to "LIBRARY"] ?: 0
            PolicyKnownLibraryOrder(
                playerId = player,
                shuffleEpoch = shuffleEpochs[player] ?: 0,
                top = knownOrders[player].orEmpty(),
                // Once the library becomes shorter than a remembered suffix, draws consumed the
                // topward entries. The bottommost tail remains exactly known.
                bottom = knownBottomOrders[player].orEmpty().takeLast(librarySize),
            )
        }
        val provisional = PolicyKnowledgeState(
            perspectivePlayerId = perspectivePlayerId,
            deckCardCounts = knownDecks.mapValues { it.value.toSortedMap() }.toSortedMap(),
            zones = zones,
            knownObjects = knownObjects.values.sortedBy { it.knowledgeObjectKey },
            knownLibraryOrders = orders,
            unlocatedCardCounts = unlocated,
            epistemicallyComplete = unsupported.isEmpty(),
            unsupportedReasons = unsupported.distinct().sorted(),
            knowledgeDigest = "",
        )
        val element = PolicyJson.format.encodeToJsonElement(PolicyKnowledgeState.serializer(), provisional)
        return provisional.copy(knowledgeDigest = PolicyJson.digest(element))
    }

    companion object {
        fun replay(history: Iterable<PolicyHistoryEvent>): PolicyKnowledgeAccumulator =
            PolicyKnowledgeAccumulator().also { it.append(history) }
    }
}

/** Pure full-prefix replay oracle for deterministic deck-local knowledge. */
object PolicyKnowledgeReducer {
    fun reduce(
        perspectivePlayerId: String,
        knownDecks: Map<String, Map<String, Int>>,
        currentObservation: PolicyObservation,
        history: List<PolicyHistoryEvent>,
    ): PolicyKnowledgeState = PolicyKnowledgeAccumulator.replay(history).snapshot(
        perspectivePlayerId = perspectivePlayerId,
        knownDecks = knownDecks,
        currentObservation = currentObservation,
    )
}
