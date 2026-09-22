package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.PhasedInEvent
import com.wingedsheep.engine.core.PhasedOutEvent
import com.wingedsheep.engine.core.StepChangedEvent
import com.wingedsheep.engine.core.TappedEvent
import com.wingedsheep.engine.core.TurnChangedEvent
import com.wingedsheep.engine.core.UntappedEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.sdk.core.Step
import kotlinx.serialization.Serializable

/** History representation, not game-event execution order. Historical worlds keep the default. */
@Serializable
enum class PerspectiveHistoryEventOrder {
    LEGACY_ENGINE_ORDER_V1,
    QUALIFIED_TURN_UNTAP_V1,
    QUALIFIED_TURN_UNTAP_V2,
}

/**
 * Conservative bridge for the pinned engine's automatic turn-boundary untap occurrence.
 * Adjacency alone does not establish simultaneity: the qualifier also requires the turn and
 * upkeep framing, distinct objects controlled by the new active player, and tapped-before /
 * untapped-after states. The qualifier certifies only the emitted untap events: a replacement or
 * restriction that suppresses an untap without emitting an event (for example a consumed stun
 * counter) can accompany a response that still qualifies and is not represented by it. Optional
 * untap choices, extra beginning phases, other-player untaps and interleaved events remain
 * ordered here. General support still requires explicit causal/simultaneity metadata from the
 * rules engine.
 *
 * V1 ([allowOrderedPrefix] = false) permits only the optional cleanup step marker before the
 * turn marker, preserving its historical meaning exactly. V2 (= true) separates the occurrence
 * from unrelated earlier events in the same engine response: an ordered prefix is preserved in
 * its actual order, while only the later untap group is canonicalized. The prefix may not change
 * a member of that group's continuity, tapped state or phased presence between the response's
 * start and the occurrence; those cases remain outside normalization. This is an object-directed
 * guard, not a whitelist of harmless event names.
 */
internal fun qualifiedTurnUntapRange(
    events: List<GameEvent>,
    before: GameState,
    after: GameState,
    allowOrderedPrefix: Boolean = false,
): IntRange? {
    if (before.step !in setOf(Step.END, Step.CLEANUP) || after.step != Step.UPKEEP ||
        after.turnNumber != before.turnNumber + 1 || before.activePlayerId == after.activePlayerId) return null
    val turns = events.withIndex().filter { it.value is TurnChangedEvent }
    if (turns.size != 1) return null
    val turnIndex = turns.single().index
    val turn = turns.single().value as TurnChangedEvent
    if (turn.turnNumber != after.turnNumber || turn.activePlayerId != after.activePlayerId) return null
    val last = events.lastOrNull() as? StepChangedEvent ?: return null
    if (last.newStep != Step.UPKEEP) return null
    if (!allowOrderedPrefix &&
        events.take(turnIndex).any { it !is StepChangedEvent || it.newStep != Step.CLEANUP }) return null
    val range = (turnIndex + 1) until events.lastIndex
    if (range.count() < 2 || range.any { events[it] !is UntappedEvent }) return null
    val untaps = range.map { events[it] as UntappedEvent }
    val untappedIds = untaps.map { it.entityId }.toSet()
    if (untappedIds.size != untaps.size) return null
    // An ordered prefix is admissible only while no listed prefix event directly changes a
    // member's zone, tapped state or phased presence. Other prefix state (counters, damage,
    // attachments, control changes, expired effects) is not certified, and uninterrupted control
    // between the endpoints is not established; the prefix stays in its actual order.
    if (allowOrderedPrefix && events.take(turnIndex).any { prefixEvent ->
            when (prefixEvent) {
                is ZoneChangeEvent -> prefixEvent.entityId in untappedIds
                is TappedEvent -> prefixEvent.entityId in untappedIds
                is UntappedEvent -> prefixEvent.entityId in untappedIds
                is PhasedOutEvent -> prefixEvent.entityId in untappedIds
                is PhasedInEvent -> prefixEvent.entityId in untappedIds
                else -> false
            }
        }) return null
    if (untaps.any { event ->
        before.getEntity(event.entityId)?.has<TappedComponent>() != true ||
            after.getEntity(event.entityId)?.has<TappedComponent>() != false ||
            before.projectedState.getController(event.entityId) != after.activePlayerId ||
            after.projectedState.getController(event.entityId) != after.activePlayerId
    }) return null
    return range
}
