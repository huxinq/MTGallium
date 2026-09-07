package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.TransformedEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.permanent.types.TransformEffectExecutor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.DoubleFacedComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.lci.cards.OjerAxonilDeepestMight
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.effects.TransformEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail

class RememberedTransformKnowledgeTest {
    @Test
    fun `public Temple transformation preserves remembered object support for both viewers`() {
        val registry = CardRegistry().apply {
            register(PortalSet.basicLands)
            register(OjerAxonilDeepestMight)
        }
        val deck = mapOf("Mountain" to 17, "Ojer Axonil, Deepest Might" to 3)
        val env = GameEnvironment.create(registry).also {
            it.reset(GameConfig(players = listOf(
                PlayerConfig("First", Deck.of("Mountain" to 17, "Ojer Axonil, Deepest Might" to 3)),
                PlayerConfig("Second", Deck.of("Mountain" to 17, "Ojer Axonil, Deepest Might" to 3))),
                seed = 17L, skipMulligans = true, useHandSmoother = false, startingPlayerIndex = 0))
        }
        val owner = env.playerIds[1]
        val objectId = (env.state.getHand(owner) + env.state.getLibrary(owner)).first {
            env.state.getEntity(it)?.get<CardComponent>()?.name == "Ojer Axonil, Deepest Might"
        }
        val sourceZone = env.state.zones.entries.single { objectId in it.value }.key
        val battlefield = env.state.removeFromZone(sourceZone, objectId).addToZone(ZoneKey(owner, Zone.BATTLEFIELD), objectId)
            .updateEntity(objectId) { it.with(ControllerComponent(owner)).with(
                DoubleFacedComponent("Ojer Axonil, Deepest Might", "Temple of Power")) }
        val executor = TransformEffectExecutor(registry)
        val context = EffectContext(sourceId = objectId, controllerId = owner)
        val toTemple = executor.execute(battlefield, TransformEffect(), context)
        assertNull(toTemple.error)
        val temple = toTemple.state
        assertEquals("Temple of Power", temple.getEntity(objectId)?.get<CardComponent>()?.name)
        fun projections(state: GameState) = env.playerIds.associateWith { viewer ->
            SafeObservationProjector().project(
                ObservationBuilder(registry).build(state, viewer, emptyList()).observation as TrainingObservation)
        }
        val before = projections(temple)
        val history = PerspectiveHistory(env.playerIds)
        // A public battlefield entry establishes the exact Temple object before its later transform.
        history.recordEngineEvents(listOf(ZoneChangeEvent(entityId = objectId, entityName = "Temple of Power",
            fromZone = null, toZone = Zone.BATTLEFIELD, ownerId = owner)), owner,
            temple, temple, before, before)
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        val remembered = env.playerIds.associateWith { viewer ->
            history.knowledgeForViewer(viewer, before.getValue(viewer).observation, knownDecks)
        }
        val toOjer = executor.execute(temple, TransformEffect(), context)
        assertNull(toOjer.error)
        assertEquals("Ojer Axonil, Deepest Might", assertIs<TransformedEvent>(toOjer.events.single()).newFaceName)
        val after = projections(toOjer.state)
        history.recordEngineEvents(toOjer.events, owner, temple, toOjer.state, before, after)
        val aliases = env.playerIds.mapIndexed { index, player -> "p$index" to player }.toMap()
        env.playerIds.forEach { viewer ->
            val binding = history.knowledgeObjectBindingsForViewer(viewer)
            val prior = remembered.getValue(viewer)
            assertNull(ArgentumRememberedFactSupport.failure(temple, aliases, binding, prior))
            // The retained failure is reachable without a stochastic game: the old name contradicts the transformed state.
            assertEquals("KNOWN_OBJECT_CARD_MISMATCH",
                ArgentumRememberedFactSupport.failure(toOjer.state, aliases, binding, prior))
            val detail = assertIs<PerspectiveEventDetail.ObjectState>(history.forViewer(viewer).last().detail)
            assertNotNull(detail.knowledgeObjectKey)
            assertEquals(prior.knownObjects.single().knowledgeObjectKey, detail.knowledgeObjectKey)
            assertNotEquals(objectId.value, detail.knowledgeObjectKey)
            assertNotEquals(detail.objectRef, detail.knowledgeObjectKey)
            val current = history.knowledgeForViewer(viewer, after.getValue(viewer).observation, knownDecks)
            assertEquals(prior.knownObjects.single().copy(cardName = "Ojer Axonil, Deepest Might"), current.knownObjects.single())
            assertNull(ArgentumRememberedFactSupport.failure(toOjer.state, aliases, binding, current))
        }
    }
}
