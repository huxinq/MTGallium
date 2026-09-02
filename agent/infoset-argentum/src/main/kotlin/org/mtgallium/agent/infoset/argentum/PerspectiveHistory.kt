package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.CardRevealedFromDrawEvent
import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.HandRevealedEvent
import com.wingedsheep.engine.core.LibraryReorderedEvent
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.LookedAtCardsEvent
import com.wingedsheep.engine.core.ShuffleCause
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.PolicyAudience
import org.mtgallium.agent.infoset.core.PolicyAudienceScope
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyInformationStateDigest
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyKnowledgeAccumulator
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail

/** Mutable only inside one trusted world; [fork] performs a structural copy. */
internal class PerspectiveHistory private constructor(
    private val aliases: Map<EntityId, String>,
    private val events: MutableMap<EntityId, PersistentList<PolicyHistoryEvent>>,
    private val historyCommitments: MutableMap<EntityId, PolicyHistoryCommitment>,
    private val auditSink: PerspectiveProjectionAuditSink,
    /** Raw ids remain trusted; values are chronology-derived, perspective-local knowledge handles. */
    private val knowledgeHandles: MutableMap<EntityId, MutableMap<EntityId, String>>,
    private val nextKnowledgeHandle: MutableMap<EntityId, Int>,
    /** Raw top-order tracking is trusted adapter state and never crosses the perspective boundary. */
    private val knownLibraryOrderIds: MutableMap<EntityId, MutableMap<EntityId, MutableList<EntityId>>>,
    /** Forkable exact reducers let forward play apply each safe event once. */
    private val knowledgeAccumulators: MutableMap<EntityId, PolicyKnowledgeAccumulator>,
    /** Event numbering is perspective-local so omitted private events cannot create observable gaps. */
    private val nextEventId: MutableMap<EntityId, Long>,
) {
    constructor(
        playerIds: List<EntityId>,
        auditSink: PerspectiveProjectionAuditSink = PerspectiveProjectionAuditSink.NONE,
    ) : this(
        aliases = playerIds.mapIndexed { index, id -> id to "p$index" }.toMap(),
        events = playerIds.associateWith { persistentListOf<PolicyHistoryEvent>() }.toMutableMap(),
        historyCommitments = playerIds.associateWith { PolicyHistoryCommitment.empty() }.toMutableMap(),
        auditSink = auditSink,
        knowledgeHandles = playerIds.associateWith { mutableMapOf<EntityId, String>() }.toMutableMap(),
        nextKnowledgeHandle = playerIds.associateWith { 0 }.toMutableMap(),
        knownLibraryOrderIds = playerIds.associateWith { mutableMapOf<EntityId, MutableList<EntityId>>() }.toMutableMap(),
        knowledgeAccumulators = playerIds.associateWith { PolicyKnowledgeAccumulator() }.toMutableMap(),
        nextEventId = playerIds.associateWith { 0L }.toMutableMap(),
    )

    fun fork(): PerspectiveHistory = PerspectiveHistory(
        aliases = aliases,
        events = events.toMutableMap(),
        historyCommitments = historyCommitments.toMutableMap(),
        auditSink = auditSink,
        knowledgeHandles = knowledgeHandles.mapValues { (_, value) -> value.toMutableMap() }.toMutableMap(),
        nextKnowledgeHandle = nextKnowledgeHandle.toMutableMap(),
        knownLibraryOrderIds = knownLibraryOrderIds.mapValues { (_, byOwner) ->
            byOwner.mapValues { (_, order) -> order.toMutableList() }.toMutableMap()
        }.toMutableMap(),
        knowledgeAccumulators = knowledgeAccumulators.mapValues { (_, value) -> value.fork() }.toMutableMap(),
        nextEventId = nextEventId.toMutableMap(),
    )

    fun forViewer(viewer: EntityId): List<PolicyHistoryEvent> = events.getValue(viewer)

    fun commitmentForViewer(viewer: EntityId): PolicyHistoryCommitment = historyCommitments.getValue(viewer)

    /** Trusted binding used only to verify that a sampled world preserves a remembered object. */
    fun knowledgeObjectBindingsForViewer(viewer: EntityId): Map<String, EntityId> =
        knowledgeHandles.getValue(viewer).entries.associate { (rawId, handle) -> handle to rawId }

    fun sharesLedgerPrefixWith(other: PerspectiveHistory, viewer: EntityId): Boolean =
        events.getValue(viewer) === other.events.getValue(viewer)

    fun knowledgeForViewer(
        viewer: EntityId,
        currentObservation: PolicyObservation,
        knownDecks: Map<String, Map<String, Int>>,
    ): PolicyKnowledgeState = if (knownDecks.isEmpty()) {
        PolicyKnowledgeState.empty(currentObservation.perspectivePlayerId)
    } else {
        knowledgeAccumulators.getValue(viewer).snapshot(
            perspectivePlayerId = currentObservation.perspectivePlayerId,
            knownDecks = knownDecks,
            currentObservation = currentObservation,
        )
    }

    fun recordEngineEvents(
        engineEvents: List<GameEvent>,
        actorViewer: EntityId,
        beforeState: GameState,
        afterState: GameState,
        before: Map<EntityId, SafeObservationProjection>,
        after: Map<EntityId, SafeObservationProjection>,
    ): List<PolicyHistoryEvent> {
        val actorEvents = mutableListOf<PolicyHistoryEvent>()
        val invalidatedRevealIds = events.keys.associateWith { mutableSetOf<EntityId>() }
        fun randomizedIds(eventIndex: Int, event: LibraryShuffledEvent): Set<EntityId> = buildSet {
            val owner = event.playerId
            addAll(beforeState.getLibrary(owner))
            if (event.cause == ShuffleCause.MULLIGAN) addAll(beforeState.getHand(owner))
            engineEvents.take(eventIndex).forEach { priorEvent ->
                when (priorEvent) {
                    is ZoneChangeEvent -> if (priorEvent.ownerId == owner) {
                        if (priorEvent.fromZone == com.wingedsheep.sdk.core.Zone.LIBRARY) remove(priorEvent.entityId)
                        if (priorEvent.toZone == com.wingedsheep.sdk.core.Zone.LIBRARY) add(priorEvent.entityId)
                    }
                    is CardsDrawnEvent -> if (priorEvent.playerId == owner) removeAll(priorEvent.cardIds.toSet())
                    else -> Unit
                }
            }
        }
        engineEvents.forEachIndexed { eventIndex, engineEvent ->
            val randomizedObjectIds = (engineEvent as? LibraryShuffledEvent)?.let {
                randomizedIds(eventIndex, it)
            }.orEmpty()
            for (viewer in events.keys) {
                val eventId = nextEventId.getValue(viewer)
                val knownDrawIds = engineEvents.drop(eventIndex)
                    .filterIsInstance<CardsDrawnEvent>()
                    .flatMap { draw ->
                        val knownOrder = knownLibraryOrderIds.getValue(viewer)[draw.playerId].orEmpty()
                        knownOrder.take(draw.count).filter { it in draw.cardIds }
                    }.toSet()
                val projected = PerspectiveEventProjector.project(
                    eventId = eventId,
                    event = engineEvent,
                    viewer = viewer,
                    aliases = aliases,
                    beforeState = beforeState,
                    afterState = afterState,
                    beforeRefs = before.getValue(viewer).references,
                    afterRefs = after.getValue(viewer).references,
                    knowledgeObjectKey = { rawId -> knowledgeObjectKey(viewer, rawId) },
                    knownLibraryDrawObject = { rawId -> rawId in knownDrawIds },
                    revealIdentityInvalidated = { rawId -> rawId in invalidatedRevealIds.getValue(viewer) },
                    shuffleInvalidatedKnowledgeObjectKeys = randomizedObjectIds.mapNotNull { rawId ->
                        knowledgeHandles.getValue(viewer)[rawId]
                    }.distinct().sorted(),
                )
                auditSink.record(
                    PerspectiveProjectionAudit(
                        rawEventType = engineEvent::class.simpleName ?: "UnknownGameEvent",
                        viewerAlias = aliases.getValue(viewer),
                        disposition = when {
                            projected == null -> ProjectionDisposition.INTENTIONALLY_OMITTED
                            projected.kind == PolicyHistoryEventKind.UNSUPPORTED_VISIBLE_TRANSITION ->
                                ProjectionDisposition.UNSUPPORTED
                            else -> ProjectionDisposition.PROJECTED
                        },
                        projectedKind = projected?.kind?.name,
                        detailType = projected?.detail?.let { it::class.simpleName },
                    )
                )
                if (projected == null) continue
                append(viewer, projected)
                if (viewer == actorViewer) actorEvents += projected
            }
            updateRawKnowledgeTracking(engineEvent, afterState, randomizedObjectIds)
            when (engineEvent) {
                is LibraryShuffledEvent -> {
                    invalidatedRevealIds.values.forEach { it += randomizedObjectIds }
                }
                is CardRevealedFromDrawEvent -> invalidatedRevealIds.values.forEach {
                    it.remove(engineEvent.cardEntityId)
                }
                is CardsRevealedEvent -> invalidatedRevealIds.values.forEach { it.removeAll(engineEvent.cardIds.toSet()) }
                is HandRevealedEvent -> invalidatedRevealIds.values.forEach { it.removeAll(engineEvent.cardIds.toSet()) }
                is HandLookedAtEvent -> invalidatedRevealIds.getValue(engineEvent.viewingPlayerId)
                    .removeAll(engineEvent.cardIds.toSet())
                is LookedAtCardsEvent -> invalidatedRevealIds.getValue(engineEvent.playerId)
                    .removeAll(engineEvent.cardIds.toSet())
                else -> Unit
            }
        }
        val ceasedObjectIds = engineEvents.filterIsInstance<ZoneChangeEvent>()
            .map { it.entityId }
            .filter { afterState.getEntity(it) == null }
            .toSet()
        if (ceasedObjectIds.isNotEmpty()) {
            knowledgeHandles.values.forEach { handles -> handles.keys.removeAll(ceasedObjectIds) }
        }
        return actorEvents
    }

    private fun updateRawKnowledgeTracking(
        event: GameEvent,
        afterState: GameState,
        randomizedObjectIds: Set<EntityId>,
    ) {
        when (event) {
            is LookedAtCardsEvent -> {
                knownLibraryOrderIds[event.playerId]?.set(event.playerId, event.cardIds.toMutableList())
            }
            is LibraryReorderedEvent -> {
                knownLibraryOrderIds[event.playerId]?.set(
                    event.playerId,
                    afterState.getLibrary(event.playerId).take(event.cardCount).toMutableList(),
                )
            }
            is CardsDrawnEvent -> {
                for (byOwner in knownLibraryOrderIds.values) {
                    val order = byOwner[event.playerId] ?: continue
                    repeat(minOf(event.count, order.size)) { order.removeAt(0) }
                    if (order.isEmpty()) byOwner.remove(event.playerId)
                }
            }
            is LibraryShuffledEvent -> {
                for (viewer in events.keys) {
                    knownLibraryOrderIds.getValue(viewer).remove(event.playerId)
                    knowledgeHandles.getValue(viewer).keys.removeAll(randomizedObjectIds)
                }
            }
            else -> Unit
        }
    }

    private fun append(viewer: EntityId, event: PolicyHistoryEvent) {
        events[viewer] = events.getValue(viewer).adding(event)
        historyCommitments[viewer] = historyCommitments.getValue(viewer).append(event)
        knowledgeAccumulators.getValue(viewer).append(event)
        nextEventId[viewer] = event.eventId + 1
    }

    /**
     * Mint an opaque handle from the order in which this viewer first learned an object's
     * continuity. Two observationally equivalent histories therefore mint the same handle even
     * when Argentum assigned different raw entity ids.
     */
    private fun knowledgeObjectKey(viewer: EntityId, rawId: EntityId): String =
        knowledgeHandles.getValue(viewer).getOrPut(rawId) {
            val index = nextKnowledgeHandle.getValue(viewer)
            nextKnowledgeHandle[viewer] = index + 1
            "knowledge-object-$index"
        }

    /** Record a semantic choice without ever forwarding the engine event stream. */
    fun recordChoice(
        actor: EntityId,
        choice: SemanticChoice,
        privateToActor: Boolean,
        kind: PolicyHistoryEventKind,
        strategicallyOptional: Boolean = true,
        libraryBottomObjects: List<LibraryBottomKnowledge> = emptyList(),
    ) {
        require(libraryBottomObjects.isEmpty() || privateToActor) {
            "Library-bottom choice knowledge must remain private to its actor"
        }
        val actorAlias = aliases.getValue(actor)
        for (viewer in events.keys) {
            val eventId = nextEventId.getValue(viewer)
            val entitled = !privateToActor || viewer == actor
            val payload = if (entitled) {
                buildJsonObject {
                    put("signature", JsonPrimitive(choice.signature))
                    put("choiceKind", JsonPrimitive(choice.kind.name))
                    put("operationFamily", JsonPrimitive(choice.operationFamily.name))
                    put("label", JsonPrimitive(choice.display.label))
                    choice.display.sourceName?.let { put("sourceName", JsonPrimitive(it)) }
                    put("targetNames", JsonArray(choice.display.targetNames.map(::JsonPrimitive)))
                    if (privateToActor) put("privatePayload", choice.canonicalPayload)
                }
            } else {
                buildJsonObject {
                    put("privateDecisionOccurred", JsonPrimitive(true))
                    put("choiceKind", JsonPrimitive(choice.kind.name))
                }
            }
            val event = PolicyHistoryEvent(
                eventId = eventId,
                audience = if (privateToActor && entitled) {
                    PolicyAudience(PolicyAudienceScope.ENTITLED_PLAYERS, setOf(actorAlias))
                } else {
                    PolicyAudience(PolicyAudienceScope.PUBLIC)
                },
                actor = actorAlias,
                kind = if (entitled) kind else PolicyHistoryEventKind.PRIVATE_DECISION_OCCURRED,
                payload = payload,
                detail = PerspectiveEventDetail.Choice(
                    semanticSignature = if (entitled) choice.signature else null,
                    choiceKind = choice.kind.name,
                    operationFamily = choice.operationFamily.takeIf { entitled },
                    privateToActor = privateToActor,
                    strategicallyOptional = strategicallyOptional.takeIf { viewer == actor },
                    libraryBottomCardNames = libraryBottomObjects
                        .takeIf { entitled }
                        .orEmpty()
                        .map(LibraryBottomKnowledge::cardName),
                    libraryBottomKnowledgeObjectKeys = libraryBottomObjects
                        .takeIf { entitled }
                        .orEmpty()
                        .map { knowledgeObjectKey(viewer, it.objectId) },
                ),
            )
            append(viewer, event)
        }
    }

    /**
     * Record only changes already present in each viewer's masked before/after observations.
     * This intentionally reconstructs semantic history instead of filtering raw GameEvents.
     */
    fun recordVisibleTransition(
        before: Map<EntityId, PolicyObservation>,
        after: Map<EntityId, PolicyObservation>,
        returnViewer: EntityId,
    ): List<PolicyHistoryEvent> {
        val emitted = mutableListOf<PolicyHistoryEvent>()
        for ((viewer, beforeObservation) in before) {
            val afterObservation = after.getValue(viewer)
            if (beforeObservation.observationDigest == afterObservation.observationDigest) continue
            val event = PolicyHistoryEvent(
                eventId = nextEventId.getValue(viewer),
                audience = PolicyAudience(
                    PolicyAudienceScope.ENTITLED_PLAYERS,
                    setOf(aliases.getValue(viewer)),
                ),
                actor = null,
                kind = if (afterObservation.pendingDecision != null ||
                    beforeObservation.pendingDecision != null
                ) {
                    PolicyHistoryEventKind.FORCED_TRANSITION
                } else {
                    PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION
                },
                payload = buildJsonObject {
                    put("fromObservation", JsonPrimitive(beforeObservation.observationDigest))
                    put("toObservation", JsonPrimitive(afterObservation.observationDigest))
                    put("zoneDelta", visibleZoneDelta(beforeObservation, afterObservation))
                    if (beforeObservation.priorityPlayerId != afterObservation.priorityPlayerId) {
                        put("priorityFrom", beforeObservation.priorityPlayerId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                        put("priorityTo", afterObservation.priorityPlayerId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                    }
                },
            )
            append(viewer, event)
            if (viewer == returnViewer) emitted += event
        }
        return emitted
    }

    private fun visibleZoneDelta(before: PolicyObservation, after: PolicyObservation): JsonArray {
        val beforeCounts = visibleCardCounts(before)
        val afterCounts = visibleCardCounts(after)
        val keys = (beforeCounts.keys + afterCounts.keys).sorted()
        return buildJsonArray {
            for (key in keys) {
                val old = beforeCounts[key] ?: 0
                val new = afterCounts[key] ?: 0
                if (old != new) add(buildJsonObject {
                    put("key", JsonPrimitive(key))
                    put("before", JsonPrimitive(old))
                    put("after", JsonPrimitive(new))
                })
            }
        }
    }

    private fun visibleCardCounts(observation: PolicyObservation): Map<String, Int> =
        observation.zones.flatMap { zone ->
            zone.cards.map { card -> "${zone.ownerId}:${zone.zone}:${card.name}" }
        }.groupingBy { it }.eachCount()
}

/** Trusted raw binding for one actor-known London-mulligan bottom choice. */
internal data class LibraryBottomKnowledge(
    val objectId: EntityId,
    val cardName: String,
)

internal object PolicyInformationStateFactory {
    fun build(
        projection: SafeObservationProjection,
        history: List<PolicyHistoryEvent>,
        historyCommitment: PolicyHistoryCommitment,
        expansion: PolicyExpansion,
        actingPlayerId: String?,
        terminated: Boolean,
        winnerId: String?,
        knowledge: PolicyKnowledgeState,
    ): PolicyInformationState {
        require(historyCommitment.cursor == history.size)
        val informationDigest = PolicyInformationStateDigest.compute(
            observationDigest = projection.observation.observationDigest,
            historyCommitment = historyCommitment,
            knowledgeDigest = knowledge.knowledgeDigest,
            actingPlayerId = actingPlayerId,
            candidateSignatures = expansion.candidates.map { it.signature },
            proposalVersion = expansion.proposalVersion,
        )
        return PolicyInformationState(
            actingPlayerId = actingPlayerId,
            observation = projection.observation,
            informationStateDigest = informationDigest,
            historyCommitment = historyCommitment,
            history = history,
            knowledge = knowledge,
            candidates = expansion.candidates,
            terminated = terminated,
            winnerId = winnerId,
        )
    }
}
