package org.mtgallium.agent.infoset.core

import java.util.Collections
import kotlinx.serialization.json.*

/** Detached, read-only semantic values. Canonical encoding remains an identity operation, not a getter. */
internal fun <T> snapshotList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <T> snapshotSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
internal fun <K, V> snapshotMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))

internal fun JsonElement.semanticSnapshot(): JsonElement = when (this) {
    is JsonObject -> JsonObject(snapshotMap(mapValues { it.value.semanticSnapshot() }))
    is JsonArray -> JsonArray(snapshotList(map { it.semanticSnapshot() }))
    is JsonPrimitive -> this
}
private fun JsonObject.frozen() = semanticSnapshot() as JsonObject
private fun JsonArray.frozen() = semanticSnapshot() as JsonArray

internal fun PlayerObservationSnapshot.semanticSnapshot(): PlayerObservationSnapshot = copy(
    players = snapshotList(players.map { player -> player.copy(mana = player.mana.copy(
        restricted = snapshotList(player.mana.restricted.map { it.copy(spellRiders = snapshotList(it.spellRiders)) }))) }),
    zones = snapshotList(zones.map { it.copy(cards = snapshotList(it.cards.map(ObjectView::semanticSnapshot))) }),
    stack = snapshotList(stack.map { it.copy(targets = snapshotList(it.targets)) }),
    combat = combat?.copy(
        attackers = snapshotList(combat.attackers.map { it.copy(blockerObjectRefs = snapshotList(it.blockerObjectRefs)) }),
        blockers = snapshotList(combat.blockers.map { it.copy(blockedAttackerObjectRefs = snapshotList(it.blockedAttackerObjectRefs)) })),
    pendingDecision = pendingDecision?.copy(choiceSpec = pendingDecision.choiceSpec?.semanticSnapshot()),
)

internal fun ObjectView.semanticSnapshot(): ObjectView = copy(
    types = snapshotSet(types), subtypes = snapshotSet(subtypes), colors = snapshotSet(colors), keywords = snapshotSet(keywords),
    counters = snapshotMap(counters), attachments = snapshotList(attachments),
)

private fun PendingDecisionOptions.semanticSnapshot(): PendingDecisionOptions = when (this) {
    is PendingDecisionOptions.Targets -> copy(requirements = requirements.frozen(), legalTargets = snapshotMap(legalTargets.mapValues { snapshotList(it.value) }))
    is PendingDecisionOptions.Cards -> copy(options = snapshotList(options), constraints = constraints.frozen(), cardMetadata = cardMetadata?.frozen())
    is PendingDecisionOptions.YesNo -> this
    is PendingDecisionOptions.BatchYesNo -> this
    is PendingDecisionOptions.Modes -> copy(modes = modes.frozen())
    is PendingDecisionOptions.Colors -> copy(colors = snapshotList(colors))
    is PendingDecisionOptions.Number -> this
    is PendingDecisionOptions.Distribution -> copy(targets = snapshotList(targets), maximumPerTarget = snapshotMap(maximumPerTarget))
    is PendingDecisionOptions.Order -> copy(objects = snapshotList(objects), cardMetadata = cardMetadata?.frozen())
    is PendingDecisionOptions.Piles -> copy(cards = snapshotList(cards), labels = snapshotList(labels), cardMetadata = cardMetadata?.frozen())
    is PendingDecisionOptions.Options -> copy(options = snapshotList(options), optionCards = optionCards?.let { snapshotMap(it.mapValues { e -> snapshotList(e.value) }) }, metadata = metadata.frozen())
    is PendingDecisionOptions.Replacement -> copy(fromOptions = snapshotList(fromOptions), toOptions = snapshotList(toOptions),
        fromMetadata = fromMetadata.frozen(), toMetadata = toMetadata.frozen(), allowedToByFrom = snapshotList(allowedToByFrom.map(::snapshotList)))
    is PendingDecisionOptions.LibrarySearch -> copy(options = snapshotList(options), cards = cards.frozen())
    is PendingDecisionOptions.LibraryReorder -> copy(cards = snapshotList(cards), cardMetadata = cardMetadata.frozen())
    is PendingDecisionOptions.DamageAssignment -> copy(orderedTargets = snapshotList(orderedTargets), minimumAssignments = snapshotMap(minimumAssignments), defaultAssignments = snapshotMap(defaultAssignments))
    is PendingDecisionOptions.CombatResolution -> copy(contract = contract.frozen())
    is PendingDecisionOptions.ManaSources -> copy(contract = contract.frozen())
    is PendingDecisionOptions.BudgetModal -> copy(contract = contract.frozen())
}

internal fun PlayerKnowledge.semanticSnapshot(): PlayerKnowledge = copy(
    deckCardCounts = snapshotMap(deckCardCounts.mapValues { snapshotMap(it.value) }),
    zones = snapshotList(zones.map { it.copy(knownCardCounts = snapshotMap(it.knownCardCounts)) }),
    knownObjects = snapshotList(knownObjects),
    knownLibraryOrders = snapshotList(knownLibraryOrders.map { it.copy(top = snapshotList(it.top), bottom = snapshotList(it.bottom)) }),
    unlocatedCardCounts = snapshotMap(unlocatedCardCounts.mapValues { snapshotMap(it.value) }), unsupportedReasons = snapshotList(unsupportedReasons),
)

internal fun ObservedEvent.semanticSnapshot(): ObservedEvent = copy(
    audience = audience.copy(entitledPlayerIds = snapshotSet(audience.entitledPlayerIds)),
    payload = payload.frozen(), detail = detail?.semanticSnapshot(),
)

private fun ObservedEventDetail.semanticSnapshot(): ObservedEventDetail = when (this) {
    is ObservedEventDetail.Choice -> copy(libraryBottomCardNames = snapshotList(libraryBottomCardNames), libraryBottomKnowledgeObjectKeys = snapshotList(libraryBottomKnowledgeObjectKeys))
    is ObservedEventDetail.ZoneChange -> this
    is ObservedEventDetail.Draw -> copy(knownCardNames = snapshotList(knownCardNames), knowledgeObjectKeys = snapshotList(knowledgeObjectKeys))
    is ObservedEventDetail.Reveal -> copy(cardNames = snapshotList(cardNames), knowledgeObjectKeys = snapshotList(knowledgeObjectKeys))
    is ObservedEventDetail.Look -> copy(cardNames = snapshotList(cardNames), knowledgeObjectKeys = snapshotList(knowledgeObjectKeys))
    is ObservedEventDetail.LibraryReorder -> copy(orderedCardNames = snapshotList(orderedCardNames))
    is ObservedEventDetail.Shuffle -> copy(invalidatedKnowledgeObjectKeys = snapshotList(invalidatedKnowledgeObjectKeys))
    is ObservedEventDetail.LifeChange -> this
    is ObservedEventDetail.Damage -> this
    is ObservedEventDetail.CounterChange -> this
    is ObservedEventDetail.ObjectState -> copy(relatedObjectRefs = snapshotList(relatedObjectRefs))
    is ObservedEventDetail.Causal -> copy(targetNames = snapshotList(targetNames), targetObjectRefs = snapshotList(targetObjectRefs))
    is ObservedEventDetail.ResourceChange -> this
    is ObservedEventDetail.CharacteristicChange -> this
    is ObservedEventDetail.Combat -> copy(assignments = snapshotMap(assignments.mapValues { snapshotList(it.value) }),
        subjects = snapshotList(subjects.map { it.copy(relatedObjectRefs = snapshotList(it.relatedObjectRefs),
            relatedObjectNames = snapshotList(it.relatedObjectNames), amountsByRelatedObjectRef = snapshotMap(it.amountsByRelatedObjectRef)) }))
    is ObservedEventDetail.TurnStructure -> this
    is ObservedEventDetail.Terminal -> this
    is ObservedEventDetail.UnsupportedVisibleTransition -> this
}

internal fun SemanticChoice.semanticSnapshot(): SemanticChoice = copy(
    actionIntent = actionIntent.copy(targetRelations = snapshotSet(actionIntent.targetRelations)),
    display = display.copy(targetNames = snapshotList(display.targetNames), policyTags = snapshotSet(display.policyTags)),
    canonicalPayload = canonicalPayload.frozen(),
)

internal fun ActionMenu.semanticSnapshot(): ActionMenu = copy(
    candidates = snapshotList(candidates.map(SemanticChoice::semanticSnapshot)), omissionReasons = snapshotSet(omissionReasons),
)
