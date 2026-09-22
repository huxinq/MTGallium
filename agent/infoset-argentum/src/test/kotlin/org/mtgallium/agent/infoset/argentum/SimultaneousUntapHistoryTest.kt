package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import kotlin.test.*
import org.mtgallium.agent.infoset.core.*

/** Relational tests: equivalent encodings agree, while consequential distinctions survive. */
class SimultaneousUntapHistoryTest {
    private val registry = CardRegistry().apply { register(PortalSet.cards); register(PortalSet.basicLands) }
    private val deck = mapOf("Mountain" to 24, "Raging Goblin" to 36)
    private data class Fixture(val parent: ArgentumSearchWorld, val action: SemanticChoice,
        val before: GameState, val after: GameState, val events: List<GameEvent>, val players: List<EntityId>, val range: IntRange)

    private fun fixture(mode: PerspectiveHistoryEventOrder = PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1): Fixture {
        val env = GameEnvironment.create(registry)
        env.reset(GameConfig(players = listOf("Alice", "Bob").map {
            PlayerConfig(it, Deck.of(*deck.entries.map { e -> e.key to e.value }.toTypedArray()))
        }, seed = 711L, startingPlayerIndex = 0, skipMulligans = true, useHandSmoother = false))
        val world = ArgentumSearchWorld.create(env, "untap-metamorphic", 711L, 711L,
            knownDecks = mapOf("p0" to deck, "p1" to deck), historyEventOrder = mode)
        repeat(256) {
            val menu = world.expandChoices().candidates
            val action = menu.minBy { choice -> when (choice.actionIntent.kind) {
                SemanticActionIntentKind.PLAY_LAND -> 0
                SemanticActionIntentKind.CAST_SPELL -> 1
                SemanticActionIntentKind.DECLARE_ATTACKERS -> 2
                SemanticActionIntentKind.DECLINE_BLOCK -> 3
                SemanticActionIntentKind.PASS_PRIORITY -> 4
                else -> 5
            } }
            val parent = world.fork() as ArgentumSearchWorld
            val trace = world.stepWithReplayTrace(action)
            assertTrue(trace.result.accepted)
            val raw = trace.rawTransitions.single()
            val range = qualifiedTurnUntapRange(raw.events, raw.beforeState, raw.afterState)
            if (range != null) return Fixture(parent, action, raw.beforeState, raw.afterState, raw.events, env.playerIds, range)
            assertNotNull(world.actorToAct(), "Fixture must reach a multi-permanent untap before ending")
        }
        error("Fixture failed to reach the qualified native untap")
    }

    private fun projections(state: GameState, players: List<EntityId>) = players.associateWith { viewer ->
        val observation = ObservationBuilder(registry).build(state, viewer, emptyList()).observation as TrainingObservation
        SafeObservationProjector().project(observation)
    }
    private fun record(f: Fixture, events: List<GameEvent>, mode: PerspectiveHistoryEventOrder): PerspectiveHistory {
        val history = PerspectiveHistory(f.players, eventOrder = mode)
        history.recordEngineEvents(events, f.players.first(), f.before, f.after,
            projections(f.before, f.players), projections(f.after, f.players))
        return history
    }
    private fun reversed(f: Fixture) = f.events.toMutableList().also { list ->
        f.range.zip(f.range.reversed()).forEach { (destination, source) -> list[destination] = f.events[source] }
    }.toList()

    @Test fun `all permutations of a qualified simultaneous untap have the same safe history`() {
        val f = fixture()
        fun <T> permutations(items: List<T>): List<List<T>> = if (items.size <= 1) listOf(items) else
            items.indices.flatMap { i -> permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it } }
        assertTrue(f.range.count() in 2..4, "Keep exhaustive permutation test bounded")
        val base = record(f, f.events, PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1)
        for (permutation in permutations(f.range.map { f.events[it] })) {
            val input = f.events.toMutableList().also { list -> f.range.zip(permutation).forEach { (i, e) -> list[i] = e } }.toList()
            val original = input.toList()
            val actual = record(f, input, PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1)
            for (viewer in f.players) {
                assertEquals(base.forViewer(viewer), actual.forViewer(viewer))
                assertEquals(base.commitmentForViewer(viewer), actual.commitmentForViewer(viewer))
                assertEquals(actual.forViewer(viewer).indices.map { it.toLong() }, actual.forViewer(viewer).map { it.eventId })
            }
            assertEquals(original, input, "Raw engine evidence must not be reordered")
        }
        val legacy = record(f, f.events, PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1)
        val swapped = record(f, reversed(f), PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1)
        assertTrue(f.players.all { legacy.commitmentForViewer(it) != swapped.commitmentForViewer(it) },
            "The relational check must expose the original order sensitivity")
    }

    @Test fun `native entity-map permutation cannot change the qualified observer history`() {
        val f = fixture()
        val left = f.parent.withSampledState(f.before, 913L)
        val right = f.parent.withSampledState(f.before.copy(entities = f.before.entities.entries.reversed().associate { it.toPair() }), 913L)
        for (viewer in listOf("p0", "p1")) assertEquals(left.informationState(viewer), right.informationState(viewer))
        val l = left.stepWithReplayTrace(f.action)
        val r = right.stepWithReplayTrace(f.action)
        assertTrue(l.result.accepted && r.result.accepted)
        assertNotEquals(l.rawTransitions.single().events, r.rawTransitions.single().events,
            "The test must actually perturb native event order")
        // Fingerprints intentionally preserve serialized map order; compare full structured
        // state with the existing routing-normalized equality instead of weakening state checks.
        assertTrue(ArgentumStateFingerprint.routingNormalizedEquals(left.authoritativeStateForHost(), right.authoritativeStateForHost()),
            ArgentumStateFingerprint.firstRoutingNormalizedDifference(left.authoritativeStateForHost(), right.authoritativeStateForHost()).toString())
        for (viewer in listOf("p0", "p1")) assertEquals(left.informationState(viewer), right.informationState(viewer))
        assertEquals(PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1, (left.fork() as ArgentumSearchWorld).historyEventOrder)
    }

    @Test fun `sequential untaps and ambiguous batches retain their original ordering`() {
        val f = fixture()
        val onlyUntaps = f.range.map { f.events[it] }
        assertNull(qualifiedTurnUntapRange(onlyUntaps, f.before, f.after))
        val a = record(f, onlyUntaps, PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1)
        val b = record(f, onlyUntaps.reversed(), PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1)
        assertTrue(f.players.all { a.commitmentForViewer(it) != b.commitmentForViewer(it) })
        val interrupted = f.events.toMutableList().also { it.add(f.range.first + 1, StepChangedEvent(Step.UNTAP)) }
        assertNull(qualifiedTurnUntapRange(interrupted, f.before, f.after))
        assertNull(qualifiedTurnUntapRange(f.events, f.before, f.after.copy(turnNumber = f.before.turnNumber)))
        assertNull(qualifiedTurnUntapRange(f.events, f.before, f.after.copy(step = Step.PRECOMBAT_MAIN)))
        val duplicate = f.events.toMutableList().also { it[f.range.last] = it[f.range.first] }
        assertNull(qualifiedTurnUntapRange(duplicate, f.before, f.after))
        val retained = record(f, duplicate, PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1)
        for (viewer in f.players) assertEquals(f.events.size, retained.forViewer(viewer).size, "Do not deduplicate occurrences")
    }

    @Test fun `canonicalization preserves which objects untapped and the legacy default`() {
        val f = fixture()
        val missing = f.events.filterIndexed { i, _ -> i != f.range.first }
        val full = record(f, f.events, PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1)
        val fewer = record(f, missing, PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1)
        assertTrue(f.players.all { full.commitmentForViewer(it) != fewer.commitmentForViewer(it) })
        assertEquals(PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1, PerspectiveHistory(f.players).eventOrder)
        assertEquals(PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1, full.fork().eventOrder)
        val legacy = fixture(PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1).parent
        assertEquals(legacy.authoritativeFingerprint(), f.parent.authoritativeFingerprint())
        assertNotEquals(legacy.exactRevision(), f.parent.exactRevision())
        assertFalse(legacy.copyDerivedCachesFrom(f.parent))
    }

    @Test fun `v2 mode changes reuse identity and refuses cross-mode cache reuse`() {
        val env = GameEnvironment.create(registry)
        env.reset(GameConfig(players = listOf("Alice", "Bob").map {
            PlayerConfig(it, Deck.of(*deck.entries.map { e -> e.key to e.value }.toTypedArray()))
        }, seed = 711L, startingPlayerIndex = 0, skipMulligans = true, useHandSmoother = false))
        fun world(mode: PerspectiveHistoryEventOrder) = ArgentumSearchWorld.create(
            env, "reuse-history-mode-fixture", 711L, 711L,
            knownDecks = mapOf("p0" to deck, "p1" to deck), historyEventOrder = mode,
        )
        val v1 = world(PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1)
        val v2 = world(PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2)
        val sameMode = world(PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2)
        assertEquals(PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V1, v1.historyEventOrder)
        assertEquals(PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2, v2.historyEventOrder)
        // All three worlds share one environment state and decision index, so the mode is the only
        // relevant difference; a same-mode copy still succeeds.
        assertSame(v1.authoritativeStateForHost(), v2.authoritativeStateForHost())
        assertEquals(v1.authoritativeFingerprint(), v2.authoritativeFingerprint())
        assertNotEquals(v1.exactRevision(), v2.exactRevision())
        assertTrue(v2.copyDerivedCachesFrom(sameMode))
        assertFalse(v1.copyDerivedCachesFrom(v2))
        assertFalse(v2.copyDerivedCachesFrom(v1))
        assertEquals(PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2, (v2.fork() as ArgentumSearchWorld).historyEventOrder)
    }

    private data class CleanupDiscardFixture(val fixture: Fixture, val discardChoices: List<SemanticChoice>)

    /**
     * A real cleanup step that pauses on the active player's hand-size discard and then resumes
     * into the next player's automatic untaps, retaining the discard and turn-boundary events.
     */
    private fun cleanupDiscardFixture(
        mode: PerspectiveHistoryEventOrder = PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2,
        discardIndex: Int = 0,
    ): CleanupDiscardFixture {
        val env = GameEnvironment.create(registry)
        env.reset(GameConfig(players = listOf("Alice", "Bob").map {
            PlayerConfig(it, Deck.of(*deck.entries.map { e -> e.key to e.value }.toTypedArray()))
        }, seed = 811L, startingPlayerIndex = 0, skipMulligans = true, useHandSmoother = false))
        val p0 = env.playerIds[0]
        val p1 = env.playerIds[1]
        var state = env.state
        repeat(3) { state = tappedOnBattlefield(state, p0, "Mountain") }
        state = tappedOnBattlefield(state, p0, "Raging Goblin")
        state = fillHand(state, p1, 8, required = listOf("Mountain", "Raging Goblin"))
        state = state.copy(
            turnNumber = 2,
            activePlayerId = p1,
            priorityPlayerId = p1,
            phase = Phase.ENDING,
            step = Step.END,
            priorityPassedBy = emptySet(),
        )
        env.restore(state, env.playerIds, env.stepCount)
        val world = ArgentumSearchWorld.create(
            env, "cleanup-untap-discard", 811L, 811L,
            knownDecks = mapOf("p0" to deck, "p1" to deck), historyEventOrder = mode,
        )
        repeat(128) {
            val candidates = world.expandChoices().candidates
            val discards = candidates.filter { it.operationFamily == SemanticOperationFamily.DECISION_RESPONSE }
            if (discards.isNotEmpty()) {
                val action = discards[discardIndex.coerceIn(0, discards.size - 1)]
                val parent = world.fork() as ArgentumSearchWorld
                val trace = world.stepWithReplayTrace(action)
                assertTrue(trace.result.accepted)
                val raw = trace.rawTransitions.single()
                assertNotNull(raw.events.firstOrNull { it is CardsDiscardedEvent }, "Fixture must contain the cleanup discard")
                val range = qualifiedTurnUntapRange(
                    raw.events, raw.beforeState, raw.afterState, allowOrderedPrefix = true,
                )
                assertNotNull(range, "Discard-prefix transition must qualify in v2")
                return CleanupDiscardFixture(
                    Fixture(parent, action, raw.beforeState, raw.afterState, raw.events, env.playerIds, range),
                    discards,
                )
            }
            val pass = candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
            assertTrue(world.step(pass).accepted)
        }
        error("Cleanup discard fixture did not reach the cleanup discard")
    }

    private fun tappedOnBattlefield(state: GameState, player: EntityId, name: String): GameState {
        val libraryKey = ZoneKey(player, Zone.LIBRARY)
        val battlefieldKey = ZoneKey(player, Zone.BATTLEFIELD)
        val card = state.getZone(libraryKey).first { entity ->
            state.getEntity(entity)?.get<CardComponent>()?.name == name
        }
        val moved = state.copy(
            zones = state.zones +
                (libraryKey to state.getZone(libraryKey).filterNot { it == card }) +
                (battlefieldKey to (state.getZone(battlefieldKey) + card)),
        )
        return moved.updateEntity(card) { it.with(TappedComponent) }
    }

    private fun fillHand(
        state: GameState,
        player: EntityId,
        size: Int,
        required: List<String> = emptyList(),
    ): GameState {
        val handKey = ZoneKey(player, Zone.HAND)
        val libraryKey = ZoneKey(player, Zone.LIBRARY)
        val hand = state.getZone(handKey).toMutableList()
        val library = state.getZone(libraryKey).toMutableList()
        fun nameOf(id: EntityId) = state.getEntity(id)?.get<CardComponent>()?.name
        for (name in required) {
            if (hand.any { nameOf(it) == name }) continue
            val fromLibrary = library.first { nameOf(it) == name }
            library.remove(fromLibrary)
            library.add(hand.removeAt(0))
            hand.add(fromLibrary)
        }
        while (hand.size < size) hand += library.removeAt(0)
        return state.copy(zones = state.zones + (handKey to hand) + (libraryKey to library))
    }

    @Test fun `cleanup discard prefix stays ordered while v2 canonicalizes only the later untap`() {
        val f = cleanupDiscardFixture().fixture
        // V1 keeps its historical prefix restriction; V2 qualifies the untap group, not the prefix.
        assertNull(qualifiedTurnUntapRange(f.events, f.before, f.after))
        assertEquals(f.range, qualifiedTurnUntapRange(f.events, f.before, f.after, allowOrderedPrefix = true))
        val reversedEvents = reversed(f)
        val rawInput = f.events.toList()
        val v2 = record(f, f.events, PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2)
        val v2Permuted = record(f, reversedEvents, PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2)
        val legacy = record(f, f.events, PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1)
        val legacyPermuted = record(f, reversedEvents, PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1)
        for (viewer in f.players) {
            assertEquals(v2.forViewer(viewer), v2Permuted.forViewer(viewer))
            assertEquals(v2.commitmentForViewer(viewer), v2Permuted.commitmentForViewer(viewer))
            assertNotEquals(legacy.commitmentForViewer(viewer), legacyPermuted.commitmentForViewer(viewer))
            val ordered = legacy.forViewer(viewer)
            val repaired = v2.forViewer(viewer)
            assertEquals(ordered.size, repaired.size)
            val differing = ordered.indices.filter { ordered[it] != repaired[it] }
            if (differing.isNotEmpty()) {
                assertEquals(f.range.count(), differing.size)
                assertTrue(differing.zipWithNext().all { (left, right) -> right == left + 1 })
                val block = differing.first()..differing.last()
                for (index in ordered.indices) if (index !in block) assertEquals(ordered[index], repaired[index])
                assertEquals(
                    ordered.slice(block).map { it.copy(eventId = 0) }.toSet(),
                    repaired.slice(block).map { it.copy(eventId = 0) }.toSet(),
                )
            }
            val discard = repaired.indexOfFirst {
                (it.detail as? PerspectiveEventDetail.Causal)?.eventType == "CARDS_DISCARDED"
            }
            val turn = repaired.indexOfFirst {
                (it.detail as? PerspectiveEventDetail.TurnStructure)?.activePlayerId != null
            }
            val untap = repaired.indexOfFirst {
                (it.detail as? PerspectiveEventDetail.ObjectState)?.change == "UNTAPPED"
            }
            assertTrue(discard >= 0 && turn >= 0 && untap >= 0)
            assertTrue(
                discard < turn && turn < untap,
                "The ordered discard and turn-boundary facts must precede the untap representation",
            )
        }
        assertEquals(rawInput, f.events, "Raw engine evidence must not be reordered")
        assertEquals(
            PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2,
            v2.fork().eventOrder,
        )
    }

    @Test fun `v2 complete player information is invariant under native entity-map permutation`() {
        val f = cleanupDiscardFixture().fixture
        val left = f.parent.withSampledState(f.before, 913L)
        val right = f.parent.withSampledState(
            f.before.copy(entities = f.before.entities.entries.reversed().associate { it.toPair() }), 913L,
        )
        val l = left.stepWithReplayTrace(f.action)
        val r = right.stepWithReplayTrace(f.action)
        assertTrue(l.result.accepted && r.result.accepted)
        assertNotEquals(
            l.rawTransitions.single().events,
            r.rawTransitions.single().events,
            "The test must actually perturb native event order",
        )
        assertTrue(
            ArgentumStateFingerprint.routingNormalizedEquals(
                left.authoritativeStateForHost(), right.authoritativeStateForHost(),
            ),
        )
        for (viewer in listOf("p0", "p1")) assertEquals(left.informationState(viewer), right.informationState(viewer))
        assertEquals(PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2, (left.fork() as ArgentumSearchWorld).historyEventOrder)
    }

    @Test fun `v2 prefix guard keeps state-changing prefixes outside normalization`() {
        val f = cleanupDiscardFixture().fixture
        val turnIndex = f.events.indexOfFirst { it is TurnChangedEvent }
        assertTrue(turnIndex > 0)
        val untapEntity = (f.events[f.range.first] as UntappedEvent).entityId
        fun prefix(event: GameEvent): List<GameEvent> =
            f.events.toMutableList().also { it.add(turnIndex, event) }
        val zoneChange = ZoneChangeEvent(
            entityId = untapEntity,
            entityName = "Mountain",
            fromZone = Zone.BATTLEFIELD,
            toZone = Zone.GRAVEYARD,
            ownerId = f.players[0],
        )
        assertNull(qualifiedTurnUntapRange(prefix(zoneChange), f.before, f.after, allowOrderedPrefix = true))
        assertNull(qualifiedTurnUntapRange(prefix(UntappedEvent(untapEntity, "Mountain")), f.before, f.after, allowOrderedPrefix = true))
        assertNull(qualifiedTurnUntapRange(prefix(TappedEvent(untapEntity, "Mountain")), f.before, f.after, allowOrderedPrefix = true))
        assertNull(qualifiedTurnUntapRange(prefix(PhasedOutEvent(untapEntity, "Mountain")), f.before, f.after, allowOrderedPrefix = true))
        assertNull(qualifiedTurnUntapRange(prefix(PhasedInEvent(untapEntity, "Mountain")), f.before, f.after, allowOrderedPrefix = true))
        // An unrelated ordered prefix event does not prevent qualification: the guard is
        // object-directed, not a whitelist of harmless event names.
        assertNotNull(qualifiedTurnUntapRange(prefix(PriorityChangedEvent(f.players[1])), f.before, f.after, allowOrderedPrefix = true))
    }

    @Test fun `v2 keeps a different discarded card distinguishable`() {
        val c = cleanupDiscardFixture()
        val f = c.fixture
        assertTrue(c.discardChoices.size >= 2)
        val second = c.discardChoices.first { it.signature != f.action.signature }
        val firstWorld = f.parent.fork() as ArgentumSearchWorld
        val firstStep = firstWorld.stepWithReplayTrace(f.action)
        val secondWorld = f.parent.fork() as ArgentumSearchWorld
        val secondStep = secondWorld.stepWithReplayTrace(second)
        assertTrue(firstStep.result.accepted && secondStep.result.accepted)
        fun historyFor(trace: ArgentumReplayStep): Map<EntityId, List<PolicyHistoryEvent>> {
            val raw = trace.rawTransitions.single()
            val history = PerspectiveHistory(f.players, eventOrder = PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2)
            history.recordEngineEvents(
                raw.events, f.players.first(), raw.beforeState, raw.afterState,
                projections(raw.beforeState, f.players), projections(raw.afterState, f.players),
            )
            return f.players.associateWith { viewer -> history.forViewer(viewer) }
        }
        val firstHistory = historyFor(firstStep)
        val secondHistory = historyFor(secondStep)
        for (viewer in f.players) assertNotEquals(firstHistory.getValue(viewer), secondHistory.getValue(viewer))
    }
}
