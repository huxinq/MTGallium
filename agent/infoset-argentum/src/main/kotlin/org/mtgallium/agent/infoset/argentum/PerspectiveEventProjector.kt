package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.PolicyAudience
import org.mtgallium.agent.infoset.core.PolicyAudienceScope
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.PerspectiveCombatSubject

/** Trusted conversion from authoritative engine events to one viewer's safe event vocabulary. */
internal object PerspectiveEventProjector {
    fun project(
        eventId: Long,
        event: GameEvent,
        viewer: EntityId,
        aliases: Map<EntityId, String>,
        beforeState: GameState,
        afterState: GameState,
        beforeRefs: SafeReferenceMap,
        afterRefs: SafeReferenceMap,
        knowledgeObjectKey: (EntityId) -> String,
        /** True only when this object is part of a legitimately known draw in this event batch. */
        knownLibraryDrawObject: (EntityId) -> Boolean,
        /** Prevent a batch-start reveal flag from surviving an intervening shuffle. */
        revealIdentityInvalidated: (EntityId) -> Boolean = { false },
        /** Perspective-local opaque keys invalidated by this shuffle; never raw engine ids. */
        shuffleInvalidatedKnowledgeObjectKeys: List<String> = emptyList(),
    ): PolicyHistoryEvent? {
        fun alias(id: EntityId?): String? = id?.let(aliases::get)
        fun ref(id: EntityId?): String? = afterRefs.referenceOrNull(id) ?: beforeRefs.referenceOrNull(id)
        fun cardName(state: GameState, id: EntityId): String? =
            state.getEntity(id)?.get<CardComponent>()?.name
        fun name(id: EntityId): String? = cardName(afterState, id) ?: cardName(beforeState, id)
        fun knownKey(id: EntityId, cardName: String?): String? =
            cardName?.let { knowledgeObjectKey(id) }
        fun publicZone(zone: Zone?): Boolean = zone in setOf(
            Zone.BATTLEFIELD, Zone.GRAVEYARD, Zone.EXILE, Zone.STACK, Zone.COMMAND,
        )
        fun revealed(state: GameState, id: EntityId): Boolean =
            state.getEntity(id)?.get<RevealedToComponent>()?.isRevealedTo(viewer) == true
        fun identityVisible(owner: EntityId, id: EntityId, from: Zone?, to: Zone): Boolean =
            publicZone(from) || publicZone(to) ||
                (owner == viewer && (from == Zone.HAND || to == Zone.HAND)) ||
                (revealed(beforeState, id) && !revealIdentityInvalidated(id)) ||
                (from == Zone.LIBRARY && knownLibraryDrawObject(id))
        fun continuesInDestination(owner: EntityId, id: EntityId, destination: Zone): Boolean =
            afterState.getEntity(id) != null && if (destination == Zone.STACK) {
                id in afterState.stack
            } else {
                id in afterState.getZone(ZoneKey(owner, destination))
            }

        val detail: PerspectiveEventDetail
        val kind: PolicyHistoryEventKind
        val actor: String?
        val audience = when (event) {
            is HandLookedAtEvent, is LookedAtCardsEvent ->
                PolicyAudience(PolicyAudienceScope.ENTITLED_PLAYERS, setOf(aliases.getValue(viewer)))
            else -> PolicyAudience(PolicyAudienceScope.PUBLIC)
        }

        when (event) {
            is ZoneChangeEvent -> {
                val visible = identityVisible(event.ownerId, event.entityId, event.fromZone, event.toZone)
                val visibleName = event.entityName.takeIf { visible }
                val knownObjectKey = visibleName?.let { knownKey(event.entityId, it) }
                detail = PerspectiveEventDetail.ZoneChange(
                    ownerId = aliases.getValue(event.ownerId),
                    fromZone = event.fromZone?.name,
                    toZone = event.toZone.name,
                    cardName = visibleName,
                    knowledgeObjectKey = knownObjectKey,
                    continuesAsCurrentObject = knownObjectKey == null ||
                        continuesInDestination(event.ownerId, event.entityId, event.toZone),
                )
                kind = PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION
                actor = null
            }
            is LandPlayedEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "LAND_PLAYED",
                    actorId = alias(event.controllerId),
                    sourceName = name(event.cardId),
                    sourceObjectRef = ref(event.cardId),
                    result = "PLAYED_FROM_${event.fromZone.name}",
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.controllerId)
            }
            is SpeedChangedEvent -> {
                detail = PerspectiveEventDetail.ResourceChange(
                    playerId = aliases.getValue(event.playerId),
                    resource = "SPEED",
                    delta = event.newSpeed - event.oldSpeed,
                    reason = "${event.oldSpeed}_TO_${event.newSpeed}",
                    sourceName = event.sourceName,
                )
                kind = PolicyHistoryEventKind.RESOURCE_CHANGE
                actor = alias(event.playerId)
            }
            is SpellCastEvent -> {
                val owner = afterState.getEntity(event.spellEntityId)?.get<CardComponent>()?.ownerId
                    ?: beforeState.getEntity(event.spellEntityId)?.get<CardComponent>()?.ownerId
                detail = PerspectiveEventDetail.Causal(
                    eventType = "SPELL_CAST",
                    actorId = alias(event.casterId),
                    sourceName = event.cardName,
                    sourceObjectRef = ref(event.spellEntityId),
                    targetNames = event.targetNames,
                    result = event.castFromZone?.let { "CAST_FROM_${it.name}" },
                    numericValue = event.xValue,
                    sourceKnowledgeObjectKey = knownKey(event.spellEntityId, event.cardName),
                    sourceOwnerId = alias(owner),
                    sourceZoneAfter = Zone.STACK.name,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.casterId)
            }
            is AbilityActivatedEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "ABILITY_ACTIVATED",
                    actorId = alias(event.controllerId),
                    sourceName = event.sourceName,
                    sourceObjectRef = ref(event.sourceId),
                    result = if (event.isManaAbility) "MANA_ABILITY" else "STACK_ABILITY",
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.controllerId)
            }
            is AbilityTriggeredEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "ABILITY_TRIGGERED",
                    actorId = alias(event.controllerId),
                    sourceName = event.sourceName,
                    sourceObjectRef = ref(event.sourceId),
                    result = event.description,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.controllerId)
            }
            is AbilityAutoAnsweredEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "ABILITY_AUTO_ANSWERED",
                    actorId = alias(event.controllerId),
                    sourceName = event.sourceName,
                    sourceObjectRef = ref(event.sourceId),
                    result = event.answer.toString(),
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.controllerId)
            }
            is TargetsChosenEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "TARGETS_CHOSEN",
                    actorId = alias(event.chooserId),
                    sourceName = event.sourceName,
                    sourceObjectRef = ref(event.stackObjectId),
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.chooserId)
            }
            is CommitCrimeEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "CRIME_COMMITTED",
                    actorId = alias(event.playerId),
                    sourceName = event.sourceName,
                    sourceObjectRef = ref(event.sourceEntityId),
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.playerId)
            }
            is ResolvedEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "SPELL_OR_ABILITY_RESOLVED",
                    actorId = null,
                    sourceName = event.name,
                    sourceObjectRef = ref(event.entityId),
                    result = "RESOLVED",
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = null
            }
            is AbilityResolvedEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "ABILITY_RESOLVED",
                    actorId = null,
                    sourceName = name(event.sourceId),
                    sourceObjectRef = ref(event.sourceId),
                    result = event.description,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = null
            }
            is SpellCounteredEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "SPELL_COUNTERED",
                    actorId = null,
                    sourceName = event.cardName,
                    sourceObjectRef = ref(event.spellEntityId),
                    result = "COUNTERED",
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = null
            }
            is AbilityCounteredEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "ABILITY_COUNTERED",
                    actorId = alias(event.controllerId),
                    sourceName = event.sourceName ?: name(event.sourceId ?: event.abilityEntityId),
                    sourceObjectRef = ref(event.sourceId) ?: ref(event.abilityEntityId),
                    result = event.description,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.controllerId)
            }
            is SpellFizzledEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "SPELL_FIZZLED",
                    actorId = null,
                    sourceName = event.cardName,
                    sourceObjectRef = ref(event.spellEntityId),
                    result = event.reason,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = null
            }
            is AbilityFizzledEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "ABILITY_FIZZLED",
                    actorId = null,
                    sourceName = name(event.sourceId),
                    sourceObjectRef = ref(event.sourceId),
                    result = event.reason,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = null
            }
            is CardsDrawnEvent -> {
                val visibleIds = if (event.playerId == viewer) {
                    event.cardIds
                } else {
                    event.cardIds.filter {
                        (revealed(beforeState, it) && !revealIdentityInvalidated(it)) ||
                            knownLibraryDrawObject(it)
                    }
                }
                val knownCards = visibleIds.mapNotNull { id ->
                    name(id)?.let { cardName -> cardName to knowledgeObjectKey(id) }
                }
                detail = PerspectiveEventDetail.Draw(
                    playerId = aliases.getValue(event.playerId),
                    count = event.count,
                    knownCardNames = knownCards.map { it.first },
                    knowledgeObjectKeys = knownCards.map { it.second },
                )
                kind = PolicyHistoryEventKind.DRAW
                actor = alias(event.playerId)
            }
            is CardRevealedFromDrawEvent -> {
                detail = PerspectiveEventDetail.Reveal(
                    ownerId = aliases.getValue(event.playerId),
                    zone = Zone.HAND.name,
                    cardNames = listOf(event.cardName),
                    knowledgeObjectKeys = listOfNotNull(knownKey(event.cardEntityId, event.cardName)),
                )
                kind = PolicyHistoryEventKind.REVEAL
                actor = alias(event.playerId)
            }
            is CardsRevealedEvent -> {
                val owner = event.cardOwnerIds.firstOrNull() ?: event.revealingPlayerId
                val knownCards = event.cardIds.mapIndexedNotNull { index, id ->
                    event.cardNames.getOrNull(index)?.let { cardName ->
                        cardName to (knownKey(id, cardName) ?: "known:$eventId:$index")
                    }
                }
                detail = PerspectiveEventDetail.Reveal(
                    ownerId = aliases.getValue(owner),
                    zone = event.toZone?.name ?: event.fromZone?.name,
                    cardNames = knownCards.map { it.first },
                    knowledgeObjectKeys = knownCards.map { it.second },
                )
                kind = PolicyHistoryEventKind.REVEAL
                actor = alias(event.revealingPlayerId)
            }
            is HandRevealedEvent -> {
                val knownCards = event.cardIds.mapNotNull { id ->
                    name(id)?.let { cardName -> cardName to knowledgeObjectKey(id) }
                }
                detail = PerspectiveEventDetail.Reveal(
                    ownerId = aliases.getValue(event.revealingPlayerId),
                    zone = Zone.HAND.name,
                    cardNames = knownCards.map { it.first },
                    knowledgeObjectKeys = knownCards.map { it.second },
                )
                kind = PolicyHistoryEventKind.REVEAL
                actor = alias(event.revealingPlayerId)
            }
            is HandLookedAtEvent -> {
                if (event.viewingPlayerId != viewer) return null
                val knownCards = event.cardIds.mapNotNull { id ->
                    name(id)?.let { cardName -> cardName to knowledgeObjectKey(id) }
                }
                detail = PerspectiveEventDetail.Look(
                    ownerId = aliases.getValue(event.targetPlayerId),
                    zone = Zone.HAND.name,
                    cardNames = knownCards.map { it.first },
                    knowledgeObjectKeys = knownCards.map { it.second },
                    ordered = false,
                    fromTop = false,
                )
                kind = PolicyHistoryEventKind.REVEAL
                actor = alias(event.viewingPlayerId)
            }
            is LookedAtCardsEvent -> {
                if (event.playerId != viewer) return null
                val knownCards = event.cardIds.mapNotNull { id ->
                    name(id)?.let { cardName -> cardName to knowledgeObjectKey(id) }
                }
                detail = PerspectiveEventDetail.Look(
                    ownerId = aliases.getValue(event.playerId),
                    zone = Zone.LIBRARY.name,
                    cardNames = knownCards.map { it.first },
                    knowledgeObjectKeys = knownCards.map { it.second },
                    ordered = true,
                    fromTop = true,
                )
                kind = PolicyHistoryEventKind.REVEAL
                actor = alias(event.playerId)
            }
            is LibraryShuffledEvent -> {
                detail = PerspectiveEventDetail.Shuffle(
                    playerId = aliases.getValue(event.playerId),
                    cause = event.cause.name,
                    invalidatedKnowledgeObjectKeys = shuffleInvalidatedKnowledgeObjectKeys,
                )
                kind = PolicyHistoryEventKind.SHUFFLE
                actor = alias(event.playerId)
            }
            is LibraryReorderedEvent -> {
                if (event.playerId != viewer) return null
                detail = PerspectiveEventDetail.LibraryReorder(
                    playerId = aliases.getValue(event.playerId),
                    orderedCardNames = afterState.getLibrary(event.playerId).take(event.cardCount).mapNotNull(::name),
                )
                kind = PolicyHistoryEventKind.REVEAL
                actor = alias(event.playerId)
            }
            is LifeChangedEvent -> {
                detail = PerspectiveEventDetail.LifeChange(
                    playerId = aliases.getValue(event.playerId),
                    oldLife = event.oldLife,
                    newLife = event.newLife,
                    reason = event.reason.name,
                )
                kind = PolicyHistoryEventKind.LIFE_CHANGE
                actor = alias(event.playerId)
            }
            is DamageDealtEvent -> {
                detail = PerspectiveEventDetail.Damage(
                    sourceName = event.sourceName,
                    sourceObjectRef = ref(event.sourceId),
                    targetName = event.targetName,
                    targetObjectRef = ref(event.targetId) ?: alias(event.targetId),
                    amount = event.amount,
                    combat = event.isCombatDamage,
                )
                kind = PolicyHistoryEventKind.DAMAGE
                actor = null
            }
            is CountersAddedEvent -> {
                detail = PerspectiveEventDetail.CounterChange(
                    objectRef = ref(event.entityId),
                    objectName = event.entityName,
                    counterType = event.counterType,
                    delta = event.amount,
                )
                kind = PolicyHistoryEventKind.COUNTER_CHANGE
                actor = alias(event.placedBy)
            }
            is CountersRemovedEvent -> {
                detail = PerspectiveEventDetail.CounterChange(
                    objectRef = ref(event.entityId),
                    objectName = event.entityName,
                    counterType = event.counterType,
                    delta = -event.amount,
                )
                kind = PolicyHistoryEventKind.COUNTER_CHANGE
                actor = null
            }
            is StatsModifiedEvent -> {
                detail = PerspectiveEventDetail.CharacteristicChange(
                    objectRef = ref(event.targetId),
                    objectName = event.targetName,
                    characteristic = "POWER_TOUGHNESS_DELTA",
                    value = "${event.powerChange}/${event.toughnessChange}",
                    sourceName = event.sourceName,
                )
                kind = PolicyHistoryEventKind.CHARACTERISTIC_CHANGE
                actor = null
            }
            is KeywordGrantedEvent -> {
                detail = PerspectiveEventDetail.CharacteristicChange(
                    objectRef = ref(event.targetId),
                    objectName = event.targetName,
                    characteristic = "KEYWORD_GRANTED",
                    value = event.keyword,
                    sourceName = event.sourceName,
                )
                kind = PolicyHistoryEventKind.CHARACTERISTIC_CHANGE
                actor = null
            }
            is CreatureTypeChangedEvent -> {
                detail = PerspectiveEventDetail.CharacteristicChange(
                    objectRef = ref(event.targetId),
                    objectName = event.targetName,
                    characteristic = "CREATURE_TYPE",
                    value = event.newType,
                    sourceName = event.sourceName,
                )
                kind = PolicyHistoryEventKind.CHARACTERISTIC_CHANGE
                actor = null
            }
            is TappedEvent -> {
                detail = PerspectiveEventDetail.ObjectState(
                    objectRef = ref(event.entityId),
                    objectName = event.entityName,
                    change = "TAPPED",
                    value = event.reason.name,
                )
                kind = PolicyHistoryEventKind.OBJECT_STATE
                actor = alias(event.tappedById)
            }
            is UntappedEvent -> {
                detail = PerspectiveEventDetail.ObjectState(
                    objectRef = ref(event.entityId),
                    objectName = event.entityName,
                    change = "UNTAPPED",
                )
                kind = PolicyHistoryEventKind.OBJECT_STATE
                actor = null
            }
            is LandTappedForManaEvent -> {
                detail = PerspectiveEventDetail.ResourceChange(
                    playerId = aliases.getValue(event.tapperId),
                    resource = "MANA_SOURCE_TAP",
                    delta = 1,
                    reason = "LAND_TAPPED_FOR_MANA",
                    sourceName = event.landName,
                    sourceObjectRef = ref(event.landId),
                )
                kind = PolicyHistoryEventKind.RESOURCE_CHANGE
                actor = alias(event.tapperId)
            }
            is ManaAddedEvent -> {
                detail = PerspectiveEventDetail.ResourceChange(
                    playerId = aliases.getValue(event.playerId),
                    resource = "MANA",
                    delta = event.total,
                    reason = "ADDED",
                    sourceName = event.sourceName,
                    sourceObjectRef = ref(event.sourceId),
                )
                kind = PolicyHistoryEventKind.RESOURCE_CHANGE
                actor = alias(event.playerId)
            }
            is ManaSpentEvent -> {
                detail = PerspectiveEventDetail.ResourceChange(
                    playerId = aliases.getValue(event.playerId),
                    resource = "MANA",
                    delta = -event.total,
                    reason = event.reason,
                )
                kind = PolicyHistoryEventKind.RESOURCE_CHANGE
                actor = alias(event.playerId)
            }
            is CardsDiscardedEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "CARDS_DISCARDED",
                    actorId = alias(event.playerId),
                    sourceName = null,
                    sourceObjectRef = null,
                    targetNames = event.cardNames,
                    targetObjectRefs = event.cardIds.mapNotNull(::ref),
                    numericValue = event.cardIds.size,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.playerId)
            }
            is DiscardRequiredEvent -> {
                // Cleanup discard count and player are public rules information. Card identities
                // remain absent here and are disclosed only by the subsequent discard events.
                detail = PerspectiveEventDetail.Causal(
                    eventType = "CLEANUP_DISCARD_REQUIRED",
                    actorId = alias(event.playerId),
                    sourceName = null,
                    sourceObjectRef = null,
                    numericValue = event.count,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.playerId)
            }
            is DrawFailedEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "DRAW_FAILED",
                    actorId = alias(event.playerId),
                    sourceName = null,
                    sourceObjectRef = null,
                    result = event.reason,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.playerId)
            }
            is PermanentsSacrificedEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "PERMANENTS_SACRIFICED",
                    actorId = alias(event.playerId),
                    sourceName = null,
                    sourceObjectRef = null,
                    targetNames = event.permanentNames,
                    targetObjectRefs = event.permanentIds.mapNotNull(::ref),
                    numericValue = event.permanentIds.size,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.playerId)
            }
            is CreatureDestroyedEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "CREATURE_DESTROYED",
                    actorId = alias(event.controllerId),
                    sourceName = event.name,
                    sourceObjectRef = ref(event.entityId),
                    result = event.reason,
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.controllerId)
            }
            is PermanentAttachedEvent -> {
                detail = PerspectiveEventDetail.ObjectState(
                    objectRef = ref(event.attachmentId),
                    objectName = event.attachmentName,
                    change = "ATTACHED",
                    relatedObjectRefs = listOfNotNull(ref(event.attachedToId)),
                )
                kind = PolicyHistoryEventKind.OBJECT_STATE
                actor = alias(event.controllerId)
            }
            is PermanentUnattachedEvent -> {
                detail = PerspectiveEventDetail.ObjectState(
                    objectRef = ref(event.attachmentId),
                    objectName = event.attachmentName,
                    change = "UNATTACHED",
                    relatedObjectRefs = listOfNotNull(ref(event.attachedToId)),
                )
                kind = PolicyHistoryEventKind.OBJECT_STATE
                actor = alias(event.controllerId)
            }
            is TransformedEvent -> {
                detail = PerspectiveEventDetail.ObjectState(
                    objectRef = ref(event.entityId),
                    objectName = event.newFaceName,
                    change = "TRANSFORMED",
                    value = event.intoBackFace.toString(),
                )
                kind = PolicyHistoryEventKind.OBJECT_STATE
                actor = alias(event.controllerId)
            }
            is ControlChangedEvent -> {
                detail = PerspectiveEventDetail.ObjectState(
                    objectRef = ref(event.permanentId),
                    objectName = event.permanentName,
                    change = "CONTROLLER_CHANGED",
                    value = aliases.getValue(event.newControllerId),
                )
                kind = PolicyHistoryEventKind.OBJECT_STATE
                actor = alias(event.newControllerId)
            }
            is AttackersDeclaredEvent -> {
                detail = PerspectiveEventDetail.Combat(
                    declaration = "ATTACKERS",
                    actorId = alias(event.attackingPlayerId),
                    assignments = event.attackers.associate { id -> ref(id).orEmpty() to emptyList() },
                    subjects = event.attackers.mapIndexed { index, id ->
                        PerspectiveCombatSubject(
                            objectRef = ref(id),
                            objectName = event.attackerNames.getOrNull(index) ?: name(id).orEmpty(),
                        )
                    },
                )
                kind = PolicyHistoryEventKind.COMBAT_DECLARATION
                actor = alias(event.attackingPlayerId)
            }
            is BlockersDeclaredEvent -> {
                val blockingPlayer = event.blockers.keys.firstOrNull()?.let { blocker ->
                    afterState.projectedState.getController(blocker)
                        ?: beforeState.projectedState.getController(blocker)
                }
                detail = PerspectiveEventDetail.Combat(
                    declaration = "BLOCKERS",
                    actorId = alias(blockingPlayer),
                    assignments = event.blockers.entries.associate { (blocker, attackers) ->
                        ref(blocker).orEmpty() to attackers.mapNotNull(::ref)
                    },
                    subjects = event.blockers.entries.map { (blocker, attackers) ->
                        PerspectiveCombatSubject(
                            objectRef = ref(blocker),
                            objectName = event.blockerNames[blocker] ?: name(blocker).orEmpty(),
                            relatedObjectRefs = attackers.mapNotNull(::ref),
                            relatedObjectNames = attackers.map { attacker ->
                                event.attackerNames[attacker] ?: name(attacker).orEmpty()
                            },
                        )
                    },
                )
                kind = PolicyHistoryEventKind.COMBAT_DECLARATION
                actor = alias(blockingPlayer)
            }
            is BlockerOrderDeclaredEvent -> {
                detail = PerspectiveEventDetail.Combat(
                    declaration = "BLOCKER_DAMAGE_ORDER",
                    actorId = null,
                    assignments = mapOf(
                        ref(event.attackerId).orEmpty() to event.orderedBlockers.mapNotNull(::ref),
                    ),
                    subjects = listOf(PerspectiveCombatSubject(
                        objectRef = ref(event.attackerId),
                        objectName = name(event.attackerId).orEmpty(),
                        relatedObjectRefs = event.orderedBlockers.mapNotNull(::ref),
                        relatedObjectNames = event.orderedBlockers.map { name(it).orEmpty() },
                    )),
                )
                kind = PolicyHistoryEventKind.COMBAT_DECLARATION
                actor = null
            }
            is AttackerOrderDeclaredEvent -> {
                detail = PerspectiveEventDetail.Combat(
                    declaration = "ATTACKER_DAMAGE_ORDER",
                    actorId = null,
                    assignments = mapOf(
                        ref(event.blockerId).orEmpty() to event.orderedAttackers.mapNotNull(::ref),
                    ),
                    subjects = listOf(PerspectiveCombatSubject(
                        objectRef = ref(event.blockerId),
                        objectName = name(event.blockerId).orEmpty(),
                        relatedObjectRefs = event.orderedAttackers.mapNotNull(::ref),
                        relatedObjectNames = event.orderedAttackers.map { name(it).orEmpty() },
                    )),
                )
                kind = PolicyHistoryEventKind.COMBAT_DECLARATION
                actor = null
            }
            is DamageAssignedEvent -> {
                detail = PerspectiveEventDetail.Combat(
                    declaration = "DAMAGE_ASSIGNMENT",
                    actorId = null,
                    assignments = mapOf(
                        ref(event.attackerId).orEmpty() to event.assignments.entries
                            .sortedBy { ref(it.key).orEmpty() }
                            .map { (target, amount) -> "${ref(target).orEmpty()}:$amount" },
                    ),
                    subjects = listOf(PerspectiveCombatSubject(
                        objectRef = ref(event.attackerId),
                        objectName = name(event.attackerId).orEmpty(),
                        relatedObjectRefs = event.assignments.keys.mapNotNull(::ref),
                        relatedObjectNames = event.assignments.keys.map { name(it).orEmpty() },
                        amountsByRelatedObjectRef = event.assignments.mapNotNull { (target, amount) ->
                            ref(target)?.let { it to amount }
                        }.toMap(),
                    )),
                )
                kind = PolicyHistoryEventKind.COMBAT_DECLARATION
                actor = null
            }
            is BecomesTargetEvent -> {
                detail = PerspectiveEventDetail.Causal(
                    eventType = "BECAME_TARGET",
                    actorId = alias(event.controllerId),
                    sourceName = name(event.sourceEntityId),
                    sourceObjectRef = ref(event.sourceEntityId),
                    targetNames = listOf(event.targetName),
                    targetObjectRefs = listOfNotNull(ref(event.targetEntityId) ?: alias(event.targetEntityId)),
                )
                kind = PolicyHistoryEventKind.CAUSAL
                actor = alias(event.controllerId)
            }
            is PhaseChangedEvent -> {
                detail = PerspectiveEventDetail.TurnStructure(
                    turnNumber = null,
                    phase = event.newPhase.name,
                    step = null,
                    activePlayerId = null,
                    priorityPlayerId = null,
                )
                kind = PolicyHistoryEventKind.TURN_STRUCTURE
                actor = null
            }
            is StepChangedEvent -> {
                detail = PerspectiveEventDetail.TurnStructure(
                    turnNumber = null,
                    phase = null,
                    step = event.newStep.name,
                    activePlayerId = null,
                    priorityPlayerId = null,
                )
                kind = PolicyHistoryEventKind.TURN_STRUCTURE
                actor = null
            }
            is TurnChangedEvent -> {
                detail = PerspectiveEventDetail.TurnStructure(
                    turnNumber = event.turnNumber,
                    phase = null,
                    step = null,
                    activePlayerId = alias(event.activePlayerId),
                    priorityPlayerId = null,
                )
                kind = PolicyHistoryEventKind.TURN_STRUCTURE
                actor = alias(event.activePlayerId)
            }
            is PriorityChangedEvent -> {
                detail = PerspectiveEventDetail.TurnStructure(
                    turnNumber = null,
                    phase = null,
                    step = null,
                    activePlayerId = null,
                    priorityPlayerId = alias(event.playerId),
                )
                kind = PolicyHistoryEventKind.TURN_STRUCTURE
                actor = alias(event.playerId)
            }
            is GameEndedEvent -> {
                detail = PerspectiveEventDetail.Terminal(
                    winnerId = alias(event.winnerId),
                    reason = event.reason.name,
                )
                kind = PolicyHistoryEventKind.TERMINAL
                actor = null
            }
            is PlayerLostEvent -> {
                detail = PerspectiveEventDetail.Terminal(
                    winnerId = null,
                    reason = "${aliases.getValue(event.playerId)}_LOST_${event.reason.name}",
                )
                kind = PolicyHistoryEventKind.TERMINAL
                actor = alias(event.playerId)
            }
            is DecisionRequestedEvent, is DecisionSubmittedEvent -> return null // Choice ledger owns visibility.
            else -> {
                val eventType = event::class.simpleName ?: "UnknownGameEvent"
                if (eventType in IRRELEVANT_EVENT_TYPES) return null
                detail = PerspectiveEventDetail.UnsupportedVisibleTransition(
                    engineEventType = eventType,
                    reason = "event family has no reviewed perspective-safe semantic projection",
                )
                kind = PolicyHistoryEventKind.UNSUPPORTED_VISIBLE_TRANSITION
                actor = null
            }
        }
        return PolicyHistoryEvent(
            eventId = eventId,
            audience = audience,
            actor = actor,
            kind = kind,
            payload = buildJsonObject { put("eventType", JsonPrimitive(event::class.simpleName.orEmpty())) },
            detail = detail,
        )
    }

    /** Explicitly reviewed engine bookkeeping with no additional player-visible information. */
    private val IRRELEVANT_EVENT_TYPES = setOf(
        "DecisionRequestedEvent",
        "DecisionSubmittedEvent",
    )
}
