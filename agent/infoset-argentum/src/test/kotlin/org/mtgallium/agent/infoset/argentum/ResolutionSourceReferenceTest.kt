package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import kotlin.test.*

/** Authored component states using native transitions/resolution, not a reachable gameplay proof. */
class ResolutionSourceReferenceTest {
    private val registry = CardRegistry().apply { register(PortalSet.cards); register(PortalSet.basicLands) }
    private val mode = PerspectiveHistoryObjectReference.REMEMBERED_BATTLEFIELD_AND_RESOLUTION_SOURCE_V1
    private data class Fixture(val state: GameState, val source: EntityId, val other: EntityId,
        val owner: EntityId, val players: List<EntityId>)

    private fun fixture(reverse: Boolean = false, bothStack: Boolean = false): Fixture {
        val env = GameEnvironment.create(registry)
        val deck = Deck.of("Mountain" to 24, "Raging Goblin" to 36)
        env.reset(GameConfig(players = listOf(PlayerConfig("Alice", deck), PlayerConfig("Bob", deck)),
            seed = 817L, startingPlayerIndex = 0, skipMulligans = true, useHandSmoother = false))
        val owner = env.playerIds[1]
        val ids = env.state.getLibrary(owner).filter {
            env.state.getEntity(it)?.get<CardComponent>()?.name == "Raging Goblin"
        }.sortedBy { it.value }.take(2).let { if (reverse) it.reversed() else it }
        var state = env.state
        for ((index, id) in ids.withIndex()) {
            state = state.removeFromZone(ZoneKey(owner, Zone.LIBRARY), id)
            state = if (index == 0 || bothStack) state.pushToStack(id)
                .updateEntity(id) { it.with(SpellOnStackComponent(owner)) }
            else state.addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)
        }
        return Fixture(state, ids[0], ids[1], owner, env.playerIds)
    }

    private fun projections(state: GameState, f: Fixture) = f.players.associateWith { viewer ->
        SafeObservationProjector().project(ObservationBuilder(registry)
            .build(state, viewer, emptyList()).observation as TrainingObservation)
    }

    private fun record(f: Fixture, after: GameState, events: List<GameEvent>,
        history: PerspectiveHistory = PerspectiveHistory(f.players, objectReference = mode),
        before: GameState = f.state): PerspectiveHistory {
        history.recordEngineEvents(events, f.owner, before, after, projections(before, f), projections(after, f))
        return history
    }

    private fun source(history: PerspectiveHistory, viewer: EntityId) = history.forViewer(viewer)
        .mapNotNull { it.detail as? PerspectiveEventDetail.Causal }
        .last { it.eventType == "SPELL_OR_ABILITY_RESOLVED" }.sourceObjectRef

    @Test fun `native permanent resolution ignores after snapshot ordinal only in explicit new mode`() {
        val left = fixture(); val right = fixture(reverse = true)
        val a = StackResolver(registry).resolveTop(left.state)
        val b = StackResolver(registry).resolveTop(right.state)
        val move = a.events.filterIsInstance<ZoneChangeEvent>().single { it.entityId == left.source }
        assertEquals(left.state.objectRef(left.source), move.oldObject)
        assertEquals(a.state.objectRef(left.source), move.newObject)
        assertNotEquals(move.oldObject, move.newObject)
        for (identity in PerspectiveHistoryObjectReference.entries) {
            val h1 = record(left, a.state, a.events, PerspectiveHistory(left.players, objectReference = identity))
            val h2 = record(right, b.state, b.events, PerspectiveHistory(right.players, objectReference = identity))
            for (v in left.players) {
                assertEquals(projections(left.state, left).getValue(v).observation,
                    projections(right.state, right).getValue(v).observation)
                assertEquals(projections(a.state, left).getValue(v).observation,
                    projections(b.state, right).getValue(v).observation)
                if (identity == mode || identity == PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2) {
                    assertEquals(h1.forViewer(v), h2.forViewer(v))
                    assertEquals(h1.commitmentForViewer(v), h2.commitmentForViewer(v))
                    assertEquals("resolution-source-before:v1:0:stack:0", source(h1, v))
                } else assertNotEquals(source(h1, v), source(h2, v))
                val zone = h1.forViewer(v).mapNotNull { it.detail as? PerspectiveEventDetail.ZoneChange }.single()
                assertEquals("STACK", zone.fromZone)
                assertEquals("BATTLEFIELD", zone.toZone)
            }
        }
    }

    @Test fun `distinct admitted pre stack sources are not merged by identical permanent appearance`() {
        val f = fixture(bothStack = true)
        // Two alternative component transitions from the same stack, not two claims of legal top resolution.
        fun branch(id: EntityId): PerspectiveHistory {
            val moved = ZoneTransitionService.moveToZone(f.state, id, Zone.BATTLEFIELD)
            return record(f, moved.state, moved.events + ResolvedEvent(id, "Raging Goblin"))
        }
        val a = branch(f.source); val b = branch(f.other)
        for (v in f.players) {
            assertEquals("resolution-source-before:v1:0:stack:0", source(a, v))
            assertEquals("resolution-source-before:v1:0:stack:1", source(b, v))
            assertNotEquals(source(a, v), source(b, v))
        }
    }

    @Test fun `unsupported and ambiguous witnesses retain historical fallback`() {
        val f = fixture()
        val moved = ZoneTransitionService.moveToZone(f.state, f.source, Zone.BATTLEFIELD)
        val move = moved.events.filterIsInstance<ZoneChangeEvent>().single()
        val resolved = ResolvedEvent(f.source, "Raging Goblin")
        val variants = listOf(
            listOf(resolved), listOf(resolved, move), listOf(move, move, resolved),
            listOf(move, resolved, resolved), listOf(move.copy(oldObject = null), resolved),
            listOf(move.copy(newObject = null), resolved),
            listOf(move.copy(oldObject = move.newObject), resolved),
            listOf(move.copy(newObject = move.oldObject), resolved),
            listOf(move.copy(fromZone = Zone.HAND), resolved),
            listOf(move.copy(toZone = Zone.EXILE), resolved),
        )
        for (events in variants) {
            val current = record(f, moved.state, events)
            val old = record(f, moved.state, events, PerspectiveHistory(f.players,
                objectReference = PerspectiveHistoryObjectReference.REMEMBERED_BATTLEFIELD_V1))
            for (v in f.players) assertEquals(old.forViewer(v), current.forViewer(v))
        }
        val refs = projections(f.state, f).getValue(f.players[0]).references
        val events = moved.events + resolved
        assertTrue(qualifiedResolutionSources(events,
            f.state.updateEntity(f.source) { it.without<SpellOnStackComponent>() }, moved.state, refs, 0).isEmpty())
        val returned = ZoneTransitionService.moveToZone(moved.state, f.source, Zone.EXILE)
        assertTrue(qualifiedResolutionSources(events + returned.events, f.state, returned.state, refs, 0).isEmpty())
        val missingIdentity = moved.state.copy(objectIdentities = moved.state.objectIdentities - f.source)
        assertTrue(qualifiedResolutionSources(events, f.state, missingIdentity, refs, 0).isEmpty())
    }

    @Test fun `source admission is viewer local and requires a pre stack locator`() {
        val f = fixture(); val moved = ZoneTransitionService.moveToZone(f.state, f.source, Zone.BATTLEFIELD)
        val events = moved.events + ResolvedEvent(f.source, "Raging Goblin")
        val viewer = f.players[0]
        val raw = ObservationBuilder(registry).build(f.state, viewer, emptyList()).observation as TrainingObservation
        // Deliberately withhold stack admission for this viewer, leaving the trusted state unchanged.
        val withheld = SafeReferenceMap(raw.copy(stack = emptyList()))
        assertTrue(qualifiedResolutionSources(events, f.state, moved.state, withheld, 0).isEmpty())
        val admitted = projections(f.state, f).getValue(f.owner).references
        val qualified = qualifiedResolutionSources(events, f.state, moved.state, admitted, 0)
        assertEquals("resolution-source-before:v1:0:stack:0", qualified[f.source])
        assertFalse(qualified.values.any { f.source.value in it || "Goblin" in it })
        assertNotEquals(qualified, qualifiedResolutionSources(events, f.state, moved.state, admitted, 3))
    }

    @Test fun `fork preserves mode and resolution does not rebind a prior battlefield handle`() {
        val f = fixture()
        val oldVisit = ZoneTransitionService.moveToZone(f.state, f.source, Zone.BATTLEFIELD)
        val history = record(f, oldVisit.state, oldVisit.events)
        val back = oldVisit.state.removeFromZone(ZoneKey(f.owner, Zone.BATTLEFIELD), f.source)
            .pushToStack(f.source).updateEntity(f.source) { it.with(SpellOnStackComponent(f.owner)) }
        record(f, back, listOf(ZoneChangeEvent(f.source, "Raging Goblin", Zone.BATTLEFIELD, Zone.STACK, f.owner)),
            history, oldVisit.state)
        val fork = history.fork()
        val result = StackResolver(registry).resolveTop(back)
        record(f, result.state, result.events, history, back)
        assertNotEquals(history.forViewer(f.owner), fork.forViewer(f.owner))
        record(f, result.state, result.events, fork, back)
        assertEquals(mode, fork.objectReference)
        assertEquals(history.trustedReferenceStateDigest(), fork.trustedReferenceStateDigest())
        for (v in f.players) assertEquals(history.forViewer(v), fork.forViewer(v))
        val tapped = result.state.updateEntity(f.source) { it.with(TappedComponent) }
        record(f, tapped, listOf(TappedEvent(f.source, "Raging Goblin", f.owner)), history, result.state)
        for (v in f.players) {
            val ref = (history.forViewer(v).last().detail as PerspectiveEventDetail.ObjectState).objectRef!!
            assertTrue(ref.startsWith("zone:"), "New permanent must not resurrect an old battlefield handle")
        }
    }
}
