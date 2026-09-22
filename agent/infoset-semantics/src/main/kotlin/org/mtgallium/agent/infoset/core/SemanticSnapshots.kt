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
    zones = snapshotList(zones.map { it.copy(cards = snapshotList(it.cards.map(PolicyCardView::semanticSnapshot))) }),
    stack = snapshotList(stack.map { it.copy(targets = snapshotList(it.targets)) }),
    combat = combat?.copy(
        attackers = snapshotList(combat.attackers.map { it.copy(blockerObjectRefs = snapshotList(it.blockerObjectRefs)) }),
        blockers = snapshotList(combat.blockers.map { it.copy(blockedAttackerObjectRefs = snapshotList(it.blockedAttackerObjectRefs)) })),
    pendingDecision = pendingDecision?.copy(choiceSpec = pendingDecision.choiceSpec?.semanticSnapshot()),
)

internal fun PolicyCardView.semanticSnapshot(): PolicyCardView = copy(
    types = snapshotSet(types), subtypes = snapshotSet(subtypes), colors = snapshotSet(colors), keywords = snapshotSet(keywords),
    counters = snapshotMap(counters), attachments = snapshotList(attachments),
)

private fun PolicyDecisionChoiceSpec.semanticSnapshot(): PolicyDecisionChoiceSpec = when (this) {
    is PolicyDecisionChoiceSpec.Targets -> copy(requirements = requirements.frozen(), legalTargets = snapshotMap(legalTargets.mapValues { snapshotList(it.value) }))
    is PolicyDecisionChoiceSpec.Cards -> copy(options = snapshotList(options), constraints = constraints.frozen(), cardMetadata = cardMetadata?.frozen())
    is PolicyDecisionChoiceSpec.YesNo -> this
    is PolicyDecisionChoiceSpec.BatchYesNo -> this
    is PolicyDecisionChoiceSpec.Modes -> copy(modes = modes.frozen())
    is PolicyDecisionChoiceSpec.Colors -> copy(colors = snapshotList(colors))
    is PolicyDecisionChoiceSpec.Number -> this
    is PolicyDecisionChoiceSpec.Distribution -> copy(targets = snapshotList(targets), maximumPerTarget = snapshotMap(maximumPerTarget))
    is PolicyDecisionChoiceSpec.Order -> copy(objects = snapshotList(objects), cardMetadata = cardMetadata?.frozen())
    is PolicyDecisionChoiceSpec.Piles -> copy(cards = snapshotList(cards), labels = snapshotList(labels), cardMetadata = cardMetadata?.frozen())
    is PolicyDecisionChoiceSpec.Options -> copy(options = snapshotList(options), optionCards = optionCards?.let { snapshotMap(it.mapValues { e -> snapshotList(e.value) }) }, metadata = metadata.frozen())
    is PolicyDecisionChoiceSpec.Replacement -> copy(fromOptions = snapshotList(fromOptions), toOptions = snapshotList(toOptions),
        fromMetadata = fromMetadata.frozen(), toMetadata = toMetadata.frozen(), allowedToByFrom = snapshotList(allowedToByFrom.map(::snapshotList)))
    is PolicyDecisionChoiceSpec.LibrarySearch -> copy(options = snapshotList(options), cards = cards.frozen())
    is PolicyDecisionChoiceSpec.LibraryReorder -> copy(cards = snapshotList(cards), cardMetadata = cardMetadata.frozen())
    is PolicyDecisionChoiceSpec.DamageAssignment -> copy(orderedTargets = snapshotList(orderedTargets), minimumAssignments = snapshotMap(minimumAssignments), defaultAssignments = snapshotMap(defaultAssignments))
    is PolicyDecisionChoiceSpec.CombatResolution -> copy(contract = contract.frozen())
    is PolicyDecisionChoiceSpec.ManaSources -> copy(contract = contract.frozen())
    is PolicyDecisionChoiceSpec.BudgetModal -> copy(contract = contract.frozen())
}

internal fun PolicyKnowledgeState.semanticSnapshot(): PolicyKnowledgeState = copy(
    deckCardCounts = snapshotMap(deckCardCounts.mapValues { snapshotMap(it.value) }),
    zones = snapshotList(zones.map { it.copy(knownCardCounts = snapshotMap(it.knownCardCounts)) }),
    knownObjects = snapshotList(knownObjects),
    knownLibraryOrders = snapshotList(knownLibraryOrders.map { it.copy(top = snapshotList(it.top), bottom = snapshotList(it.bottom)) }),
    unlocatedCardCounts = snapshotMap(unlocatedCardCounts.mapValues { snapshotMap(it.value) }), unsupportedReasons = snapshotList(unsupportedReasons),
)

internal fun PolicyHistoryEvent.semanticSnapshot(): PolicyHistoryEvent = copy(
    audience = audience.copy(entitledPlayerIds = snapshotSet(audience.entitledPlayerIds)),
    payload = payload.frozen(), detail = detail?.semanticSnapshot(),
)

private fun PerspectiveEventDetail.semanticSnapshot(): PerspectiveEventDetail = when (this) {
    is PerspectiveEventDetail.Choice -> copy(libraryBottomCardNames = snapshotList(libraryBottomCardNames), libraryBottomKnowledgeObjectKeys = snapshotList(libraryBottomKnowledgeObjectKeys))
    is PerspectiveEventDetail.ZoneChange -> this
    is PerspectiveEventDetail.Draw -> copy(knownCardNames = snapshotList(knownCardNames), knowledgeObjectKeys = snapshotList(knowledgeObjectKeys))
    is PerspectiveEventDetail.Reveal -> copy(cardNames = snapshotList(cardNames), knowledgeObjectKeys = snapshotList(knowledgeObjectKeys))
    is PerspectiveEventDetail.Look -> copy(cardNames = snapshotList(cardNames), knowledgeObjectKeys = snapshotList(knowledgeObjectKeys))
    is PerspectiveEventDetail.LibraryReorder -> copy(orderedCardNames = snapshotList(orderedCardNames))
    is PerspectiveEventDetail.Shuffle -> copy(invalidatedKnowledgeObjectKeys = snapshotList(invalidatedKnowledgeObjectKeys))
    is PerspectiveEventDetail.LifeChange -> this
    is PerspectiveEventDetail.Damage -> this
    is PerspectiveEventDetail.CounterChange -> this
    is PerspectiveEventDetail.ObjectState -> copy(relatedObjectRefs = snapshotList(relatedObjectRefs))
    is PerspectiveEventDetail.Causal -> copy(targetNames = snapshotList(targetNames), targetObjectRefs = snapshotList(targetObjectRefs))
    is PerspectiveEventDetail.ResourceChange -> this
    is PerspectiveEventDetail.CharacteristicChange -> this
    is PerspectiveEventDetail.Combat -> copy(assignments = snapshotMap(assignments.mapValues { snapshotList(it.value) }),
        subjects = snapshotList(subjects.map { it.copy(relatedObjectRefs = snapshotList(it.relatedObjectRefs),
            relatedObjectNames = snapshotList(it.relatedObjectNames), amountsByRelatedObjectRef = snapshotMap(it.amountsByRelatedObjectRef)) }))
    is PerspectiveEventDetail.TurnStructure -> this
    is PerspectiveEventDetail.Terminal -> this
    is PerspectiveEventDetail.UnsupportedVisibleTransition -> this
}

internal fun SemanticChoice.semanticSnapshot(): SemanticChoice = copy(
    actionIntent = actionIntent.copy(targetRelations = snapshotSet(actionIntent.targetRelations)),
    display = display.copy(targetNames = snapshotList(display.targetNames), policyTags = snapshotSet(display.policyTags)),
    canonicalPayload = canonicalPayload.frozen(),
)

internal fun PolicyExpansion.semanticSnapshot(): PolicyExpansion = copy(
    candidates = snapshotList(candidates.map(SemanticChoice::semanticSnapshot)), omissionReasons = snapshotSet(omissionReasons),
)
