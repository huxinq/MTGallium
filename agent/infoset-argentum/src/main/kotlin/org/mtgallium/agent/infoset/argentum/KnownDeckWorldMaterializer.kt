package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.hidden.HiddenWorldMaterializationRequest
import com.wingedsheep.engine.hidden.HiddenWorldMaterializationResult
import com.wingedsheep.engine.hidden.HiddenWorldMaterializer
import com.wingedsheep.engine.hidden.UnsupportedHiddenWorldKind
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng

/**
 * MTGallium's known-deck hypothesis policy around Argentum's hidden-slot rewrite authority.
 *
 * Argentum decides whether a complete caller-supplied assignment can be installed coherently and
 * atomically. This adapter decides which identities are unresolved for [viewerId], subtracts
 * represented public identities from the exact decklists, and samples both definitions and hidden
 * library slots from caller-owned randomness. It never treats the authoritative identities already
 * occupying unresolved slots as evidence about the hypothesis.
 */
internal class KnownDeckWorldMaterializer(cardRegistry: CardRegistry) {
    private val cardRegistry = cardRegistry
    private val visibility = Visibility(cardRegistry)
    private val materializer = HiddenWorldMaterializer(cardRegistry)

    fun materialize(
        state: GameState,
        viewerId: EntityId,
        decklists: Map<EntityId, Map<String, Int>>,
        beliefRng: GameRng,
        futureRng: GameRng,
    ): KnownDeckWorldMaterializationResult {
        val failures = mutableListOf<KnownDeckWorldFailure>()
        val hiddenByPlayer = linkedMapOf<EntityId, List<EntityId>>()
        for (playerId in state.turnOrder) {
            if (playerId !in decklists) {
                failures += KnownDeckWorldFailure.MissingDecklist(playerId)
                continue
            }
            hiddenByPlayer[playerId] = unresolvedSlots(state, playerId, viewerId)
        }
        if (failures.isNotEmpty()) {
            return KnownDeckWorldMaterializationResult.Unsupported(failures.distinct())
        }

        var currentRng = beliefRng
        var hypothesisState = state
        val assignments = linkedMapOf<EntityId, CardDefinition>()
        for ((playerId, hiddenSlots) in hiddenByPlayer) {
            if (hiddenSlots.isEmpty()) continue
            val remaining = remainingDeck(
                state = state,
                playerId = playerId,
                unresolvedSlots = hiddenSlots.toSet(),
                decklist = decklists.getValue(playerId),
                failures = failures,
            )
            if (remaining.size < hiddenSlots.size) {
                failures += KnownDeckWorldFailure.InsufficientDeckRemainder(
                    playerId = playerId,
                    hiddenSlots = hiddenSlots.size,
                    availableCards = remaining.size,
                )
                continue
            }

            val (definitions, afterDefinitions) = currentRng.shuffle(remaining)
            currentRng = afterDefinitions
            hiddenSlots.zip(definitions.take(hiddenSlots.size)).forEach { (slot, definition) ->
                assignments[slot] = definition
            }

            // Entity ids are routing identities, not hidden library positions. Randomizing which
            // unresolved id occupies each unresolved position prevents source-state ordering from
            // becoming a hidden-truth side channel while preserving every visible/pinned position.
            val libraryKey = ZoneKey(playerId, Zone.LIBRARY)
            val library = hypothesisState.getZone(libraryKey)
            val hiddenLibraryIds = hiddenSlots.filterTo(linkedSetOf()) { it in library }
            val (shuffledIds, afterLibrary) = currentRng.shuffle(library.filter { it in hiddenLibraryIds })
            currentRng = afterLibrary
            val shuffled = shuffledIds.iterator()
            hypothesisState = hypothesisState.copy(
                zones = hypothesisState.zones + (libraryKey to library.map { id ->
                    if (id in hiddenLibraryIds) shuffled.next() else id
                })
            )
        }
        if (failures.isNotEmpty()) {
            return KnownDeckWorldMaterializationResult.Unsupported(failures.distinct())
        }

        return when (val result = materializer.materialize(
            hypothesisState,
            HiddenWorldMaterializationRequest(assignments, futureRng),
        )) {
            is HiddenWorldMaterializationResult.Materialized ->
                KnownDeckWorldMaterializationResult.Materialized(
                    state = result.state,
                    rewrittenCardCount = assignments.size,
                )
            is HiddenWorldMaterializationResult.Unsupported ->
                KnownDeckWorldMaterializationResult.Unsupported(
                    listOf(
                        KnownDeckWorldFailure.EngineMaterializationRefusal(
                            kind = result.reason.kind,
                            entityId = result.reason.entityId,
                        )
                    )
                )
        }
    }

    private fun unresolvedSlots(
        state: GameState,
        playerId: EntityId,
        viewerId: EntityId,
    ): List<EntityId> = buildList {
        listOf(Zone.HAND, Zone.LIBRARY).forEach { zone ->
            val key = ZoneKey(playerId, zone)
            state.getZone(key).filterTo(this) { entityId ->
                !visibility.isCardIdentityVisibleTo(state, key, entityId, viewerId)
            }
        }
    }

    private fun remainingDeck(
        state: GameState,
        playerId: EntityId,
        unresolvedSlots: Set<EntityId>,
        decklist: Map<String, Int>,
        failures: MutableList<KnownDeckWorldFailure>,
    ): List<CardDefinition> {
        val remaining = decklist.toMutableMap()
        for ((entityId, container) in state.entities) {
            if (entityId in unresolvedSlots || container.get<OwnerComponent>()?.playerId != playerId) continue
            val name = container.get<CardComponent>()?.name ?: continue
            remaining.computeIfPresent(name) { _, count -> (count - 1).coerceAtLeast(0) }
        }
        return remaining.toSortedMap().flatMap { (name, copies) ->
            val definition = cardRegistry.getCard(name)
            if (definition == null) {
                failures += KnownDeckWorldFailure.UnknownCard(playerId, name)
                emptyList()
            } else {
                List(copies) { definition }
            }
        }
    }
}

internal sealed interface KnownDeckWorldMaterializationResult {
    data class Materialized(
        val state: GameState,
        val rewrittenCardCount: Int,
    ) : KnownDeckWorldMaterializationResult

    data class Unsupported(val reasons: List<KnownDeckWorldFailure>) : KnownDeckWorldMaterializationResult {
        init {
            require(reasons.isNotEmpty())
        }
    }
}

internal sealed interface KnownDeckWorldFailure {
    data class MissingDecklist(val playerId: EntityId) : KnownDeckWorldFailure
    data class UnknownCard(val playerId: EntityId, val cardName: String) : KnownDeckWorldFailure
    data class InsufficientDeckRemainder(
        val playerId: EntityId,
        val hiddenSlots: Int,
        val availableCards: Int,
    ) : KnownDeckWorldFailure

    data class EngineMaterializationRefusal(
        val kind: UnsupportedHiddenWorldKind,
        val entityId: EntityId?,
    ) : KnownDeckWorldFailure
}

internal fun KnownDeckWorldFailure.redactedCode(): String = when (this) {
    is KnownDeckWorldFailure.MissingDecklist -> "MISSING_DECKLIST"
    is KnownDeckWorldFailure.UnknownCard -> "UNKNOWN_CARD"
    is KnownDeckWorldFailure.InsufficientDeckRemainder -> "INSUFFICIENT_DECK_REMAINDER"
    is KnownDeckWorldFailure.EngineMaterializationRefusal -> "ENGINE_MATERIALIZATION_${kind.name}"
}
