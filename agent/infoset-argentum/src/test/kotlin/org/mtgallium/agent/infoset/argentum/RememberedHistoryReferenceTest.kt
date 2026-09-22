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
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import kotlin.test.*

/** Authored component fixtures. Legal gameplay is separately qualified by a native retained witness. */
class RememberedHistoryReferenceTest {
    private val registry = CardRegistry().apply { register(PortalSet.cards); register(PortalSet.basicLands) }
    private val mode = PerspectiveHistoryObjectReference.REMEMBERED_BATTLEFIELD_V1
    private data class Fixture(val history: PerspectiveHistory, val state: GameState,
        val source: EntityId, val other: EntityId, val owner: EntityId, val players: List<EntityId>,
        val template: ArgentumSearchWorld)
    private fun projections(s: GameState, players: List<EntityId>) = players.associateWith { viewer ->
        SafeObservationProjector().project(ObservationBuilder(registry).build(s,viewer,emptyList()).observation as TrainingObservation)
    }
    private fun fixture(reverse: Boolean=false, identity: PerspectiveHistoryObjectReference=mode): Fixture {
        val env=GameEnvironment.create(registry)
        val deck=Deck.of("Mountain" to 24,"Raging Goblin" to 36)
        env.reset(GameConfig(players=listOf(PlayerConfig("Alice",deck),PlayerConfig("Bob",deck)),seed=817L,
            startingPlayerIndex=0,skipMulligans=true,useHandSmoother=false))
        val owner=env.playerIds[1]
        val ids=env.state.getLibrary(owner).filter { env.state.getEntity(it)?.get<CardComponent>()?.name=="Mountain" }
            .sortedBy { it.value }.take(2).let { if(reverse) it.reversed() else it }
        assertEquals(2,ids.size)
        val history=PerspectiveHistory(env.playerIds,objectReference=identity)
        var state=env.state
        for(id in ids) {
            val before=state
            state=state.removeFromZone(ZoneKey(owner,Zone.LIBRARY),id).addToZone(ZoneKey(owner,Zone.BATTLEFIELD),id)
            history.recordEngineEvents(listOf(ZoneChangeEvent(id,"Mountain",Zone.LIBRARY,Zone.BATTLEFIELD,owner)),
                owner,before,state,projections(before,env.playerIds),projections(state,env.playerIds))
        }
        state=state.updateEntity(ids[1]) { it.with(TappedComponent) }
        val template=ArgentumSearchWorld.create(env,"reference-components",817L,817L,historyObjectReference=identity)
            .withSampledState(state,817L)
        return Fixture(history,state,ids[0],ids[1],owner,env.playerIds,template)
    }
    private fun record(f:Fixture, h:PerspectiveHistory=f.history, before:GameState=f.state,
        after:GameState=before.updateEntity(f.source) { it.with(TappedComponent) },
        events:List<GameEvent> = listOf(TappedEvent(f.source,"Mountain",f.owner))) {
        h.recordEngineEvents(events,f.owner,before,after,projections(before,f.players),projections(after,f.players))
    }
    private fun lastRef(h:PerspectiveHistory,v:EntityId) = (h.forViewer(v).last().detail as PerspectiveEventDetail.ObjectState).objectRef

    @Test fun `resolution source mode retains continuous battlefield eligibility and unrelated events`() {
        val old = fixture()
        val current = fixture(identity = PerspectiveHistoryObjectReference.REMEMBERED_BATTLEFIELD_AND_RESOLUTION_SOURCE_V1)
        record(old); record(current)
        for (v in old.players) {
            assertEquals(old.history.forViewer(v), current.history.forViewer(v))
            assertTrue(lastRef(current.history, v)!!.startsWith("history-object:v1:"))
        }
        assertEquals(old.history.trustedReferenceStateDigest(), current.history.trustedReferenceStateDigest())
    }

    @Test fun `coalescing descriptors preserve existing historical identity across raw ordering`() {
        val left=fixture();val right=fixture(reverse=true)
        val beforeKeys=left.history.knowledgeObjectBindingsForViewer(left.players[0]).keys
        record(left);record(right)
        for(v in left.players) {
            assertEquals(left.history.forViewer(v),right.history.forViewer(v))
            assertEquals(left.history.commitmentForViewer(v),right.history.commitmentForViewer(v))
            assertTrue(lastRef(left.history,v)!!.startsWith("history-object:v1:knowledge-object-"))
        }
        assertEquals(beforeKeys,left.history.knowledgeObjectBindingsForViewer(left.players[0]).keys)
        val oldLeft=fixture(identity=PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1)
        val oldRight=fixture(reverse=true,identity=PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1)
        record(oldLeft);record(oldRight)
        assertNotEquals(lastRef(oldLeft.history,oldLeft.players[0]),lastRef(oldRight.history,oldRight.players[0]))
    }
    @Test fun `distinct remembered objects remain distinct when their descriptors agree`() {
        val f=fixture()
        val ready=f.state.updateEntity(f.other) { it.without<TappedComponent>() }
        val a=f.history.fork();val b=f.history.fork()
        val afterA=ready.updateEntity(f.source) { it.with(TappedComponent) }
        val afterB=ready.updateEntity(f.other) { it.with(TappedComponent) }
        record(f,a,ready,afterA)
        record(f,b,ready,afterB,listOf(TappedEvent(f.other,"Mountain",f.owner)))
        for(v in f.players) {
            assertEquals(projections(afterA,f.players).getValue(v).observation,projections(afterB,f.players).getValue(v).observation)
            assertNotEquals(lastRef(a,v),lastRef(b,v))
            assertTrue(lastRef(a,v)!!.startsWith("history-object:v1:"))
            assertTrue(lastRef(b,v)!!.startsWith("history-object:v1:"))
        }
    }
    @Test fun `reference lookup never acquires an unseen handle and forks preserve eligibility`() {
        val f=fixture();val blank=PerspectiveHistory(f.players,objectReference=mode)
        record(f,blank)
        for(v in f.players) {
            assertTrue(blank.knowledgeObjectBindingsForViewer(v).isEmpty())
            assertTrue(lastRef(blank,v)!!.startsWith("zone:"))
        }
        val fork=f.history.fork();record(f,fork);record(f)
        for(v in f.players) assertEquals(f.history.forViewer(v),fork.forViewer(v))
        assertEquals(PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1,PerspectiveHistory(f.players).objectReference)
    }
    @Test fun `missing or changed incarnations and new appearances are ineligible`() {
        val f=fixture();val v=f.players[0]
        val refs=projections(f.state,f.players).getValue(v).references
        val handles=f.history.knowledgeObjectBindingsForViewer(v).entries.associate { it.value to it.key }
        val remembered=RememberedHistoryReferences();remembered.bindBoundary(f.state,refs,handles)
        val stamp=f.state.objectIdentities.getValue(f.source)
        val changed=f.state.copy(objectIdentities=f.state.objectIdentities+(f.source to stamp.copy(generation=stamp.generation+10_000)))
        remembered.bindBoundary(changed,refs,handles) // Must not bind the old handle to the new incarnation.
        val otherOrigins=RememberedHistoryReferences().also { it.bindBoundary(changed,refs,handles) }
        assertNotEquals(remembered.trustedState(),otherOrigins.trustedState(),"Eligibility affects future output and must enter new-mode cache identity")
        assertEquals(remembered.trustedState(),remembered.fork().trustedState())
        assertFalse(f.source in remembered.eligible(changed,changed,refs,refs,handles,emptySet()))
        val missing=f.state.copy(objectIdentities=f.state.objectIdentities-f.source)
        assertFalse(f.source in remembered.eligible(missing,missing,refs,refs,handles,emptySet()))
        val absent=f.state.removeFromZone(ZoneKey(f.owner,Zone.BATTLEFIELD),f.source)
        assertFalse(f.source in remembered.eligible(absent,f.state,refs,refs,handles,emptySet()))
        assertFalse(f.source in remembered.eligible(f.state,f.state,refs,refs,handles,setOf(f.source)))
    }
    @Test fun `leave and return inside a batch cannot inherit the old history reference`() {
        val f=fixture();val old=f.state
        val returned=old.removeFromZone(ZoneKey(f.owner,Zone.BATTLEFIELD),f.source)
            .addToZone(ZoneKey(f.owner,Zone.EXILE),f.source).removeFromZone(ZoneKey(f.owner,Zone.EXILE),f.source)
            .addToZone(ZoneKey(f.owner,Zone.BATTLEFIELD),f.source)
        record(f,before=old,after=returned,events=listOf(
            ZoneChangeEvent(f.source,"Mountain",Zone.BATTLEFIELD,Zone.EXILE,f.owner),
            ZoneChangeEvent(f.source,"Mountain",Zone.EXILE,Zone.BATTLEFIELD,f.owner),
            TappedEvent(f.source,"Mountain",f.owner)))
        for(v in f.players) assertTrue(lastRef(f.history,v)!!.startsWith("zone:"))
        record(f,before=returned,after=returned)
        for(v in f.players) assertTrue(lastRef(f.history,v)!!.startsWith("zone:"),"Old handle cannot be rebound on a later batch")
    }
    @Test fun `same-mode cache reuse distinguishes retained origins with an identical board and ledger prefix`() {
        val f=fixture();val leftHistory=f.history.fork();val rightHistory=f.history.fork()
        // Controlled retained-state injection, not a claim of another legal gameplay trajectory.
        val field=PerspectiveHistory::class.java.getDeclaredField("rememberedReferences").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val rightOrigins=field.get(rightHistory) as MutableMap<EntityId,RememberedHistoryReferences>
        for(v in f.players) {
            val handles=rightHistory.knowledgeObjectBindingsForViewer(v)
            val different=handles.mapValues { (_,raw) ->
                val origin=f.state.objectRef(raw)!!
                if(raw==f.source) origin.copy(generation=origin.generation+10_000) else origin
            }.toMutableMap()
            rightOrigins[v]=RememberedHistoryReferences(different)
            assertTrue(leftHistory.sharesLedgerPrefixWith(rightHistory,v))
        }
        val left=f.template.withRememberedHistoryForVerification(leftHistory)
        val right=f.template.withRememberedHistoryForVerification(rightHistory)
        assertEquals(left.authoritativeFingerprint(),right.authoritativeFingerprint())
        assertNotEquals(left.exactRevision(),right.exactRevision())
        assertFalse(right.copyDerivedCachesFrom(left))
        assertTrue((left.fork() as ArgentumSearchWorld).copyDerivedCachesFrom(left))
        record(f,leftHistory);record(f,rightHistory)
        for(v in f.players) {
            assertTrue(lastRef(leftHistory,v)!!.startsWith("history-object:v1:"))
            assertTrue(lastRef(rightHistory,v)!!.startsWith("zone:"))
        }
    }
    @Test fun `shuffle invalidation removes acquired correspondence before later references`() {
        val f=fixture();val oldKey=f.history.knowledgeObjectBindingsForViewer(f.players[0]).entries.single { it.value==f.source }.key
        val hidden=f.state.removeFromZone(ZoneKey(f.owner,Zone.BATTLEFIELD),f.source).addToZone(ZoneKey(f.owner,Zone.LIBRARY),f.source)
        record(f,before=f.state,after=hidden,events=listOf(
            ZoneChangeEvent(f.source,"Mountain",Zone.BATTLEFIELD,Zone.LIBRARY,f.owner),LibraryShuffledEvent(f.owner)))
        for(v in f.players) assertFalse(f.source in f.history.knowledgeObjectBindingsForViewer(v).values)
        val returned=hidden.removeFromZone(ZoneKey(f.owner,Zone.LIBRARY),f.source).addToZone(ZoneKey(f.owner,Zone.BATTLEFIELD),f.source)
        record(f,before=hidden,after=returned,events=listOf(ZoneChangeEvent(f.source,"Mountain",Zone.LIBRARY,Zone.BATTLEFIELD,f.owner)))
        val newKey=f.history.knowledgeObjectBindingsForViewer(f.players[0]).entries.single { it.value==f.source }.key
        assertNotEquals(oldKey,newKey)
        record(f,before=returned,after=returned)
        assertEquals("history-object:v1:$newKey",lastRef(f.history,f.players[0]))
    }
}
