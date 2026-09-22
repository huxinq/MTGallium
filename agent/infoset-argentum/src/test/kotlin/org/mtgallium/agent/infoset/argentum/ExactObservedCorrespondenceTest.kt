package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import org.mtgallium.agent.infoset.core.*
import kotlin.test.*

/** Small authored initialized/component fixtures, independent of retained research replay material. */
class ExactObservedCorrespondenceTest {
    private val registry = CardRegistry().apply { register(PortalSet.cards); register(PortalSet.basicLands) }
    private val mode = PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2
    private fun environment(): GameEnvironment = GameEnvironment.create(registry).also {
        val deck = Deck.of("Raging Goblin" to 8, "Goblin Bully" to 8, "Mountain" to 24)
        it.reset(GameConfig(players = listOf(PlayerConfig("Alice", deck), PlayerConfig("Bob", deck)),
            seed = 42613L, startingPlayerIndex = 0, skipMulligans = true, useHandSmoother = false))
    }

    @Test fun `observed native handling never expands proposals even when configured expansion fails`() {
        val env = environment()
        val expander = UnifiedSemanticExpander(maxResponses = 1, maxAttempts = 1)
        val world = ArgentumSearchWorld.create(env, "no-proposal-dependency", 21L, 42613L,
            expander = expander, historyObjectReference = mode)
        assertFailsWith<IllegalArgumentException> { world.expandChoices(64) }
        val attempts = expander.expansionAttempts
        val native = PassPriority(requireNotNull(env.state.priorityPlayerId))
        val expected = env.fork()
        assertIs<com.wingedsheep.gym.ExactlyOneSubmissionResult.Applied>(expected.stepExactlyOne(native))
        val before = world.epistemicState("p0")
        assertTrue(world.applyObservedAction(native).result.accepted)
        assertEquals(attempts, expander.expansionAttempts)
        assertEquals(0, world.observedProposalExpansionAttempts())
        assertEquals(ArgentumStateFingerprint.of(expected.state), world.authoritativeFingerprint())
        assertNotEquals(before.historyCommitment, world.epistemicState("p0").historyCommitment)
    }
    private data class Fixture(val world: ArgentumSearchWorld, val attacker: EntityId,
        val blockers: List<EntityId>, val players: List<EntityId>, val attackers: List<EntityId>)

    private fun fixture(reverse: Boolean = false,
        reference: PerspectiveHistoryObjectReference = mode, twoAttackers: Boolean = false,
        beforeAttack: Boolean = false): Fixture {
        val env = environment()
        val players = env.playerIds
        val history = PerspectiveHistory(players, objectReference = reference)
        var state = env.state
        fun projections(s: GameState) = players.associateWith { viewer ->
            val observation = ObservationBuilder(registry).build(s, viewer, emptyList()).observation as TrainingObservation
            SafeObservationProjector().project(observation, null,
                ArgentumPolicyRuntimeProjector.project(s, viewer, registry, observation),
                qualifiedBattlefieldHandles = if (reference == mode) history.qualifiedBattlefieldBindings(viewer, s) else emptyMap(),
                canonicalCombatRows = reference == mode)
        }
        fun move(id: EntityId, owner: EntityId) {
            val before = state
            val name = state.getEntity(id)!!.get<CardComponent>()!!.name
            state = state.removeFromZone(ZoneKey(owner, Zone.LIBRARY), id)
                .addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)
            history.recordEngineEvents(listOf(ZoneChangeEvent(id, name, Zone.LIBRARY, Zone.BATTLEFIELD, owner)),
                owner, before, state, projections(before), projections(state))
        }
        val attackers = state.getLibrary(players[0]).filter { state.getEntity(it)!!.get<CardComponent>()!!.name == "Raging Goblin" }
            .take(if (twoAttackers) 2 else 1)
        val attacker = attackers.first()
        val blockers = state.getLibrary(players[1]).filter { state.getEntity(it)!!.get<CardComponent>()!!.name == "Goblin Bully" }
            .sortedBy { it.value }.take(2).let { if (reverse) it.reversed() else it }
        attackers.forEach { move(it, players[0]) }
        blockers.forEach { move(it, players[1]) }
        env.restore(state, players, env.stepCount)
        val world = ArgentumSearchWorld.create(env, "authored-exact", 42613L, 42613L,
            knownDecks = mapOf("p0" to mapOf("Raging Goblin" to 8, "Goblin Bully" to 8, "Mountain" to 24),
                "p1" to mapOf("Raging Goblin" to 8, "Goblin Bully" to 8, "Mountain" to 24)),
            historyObjectReference = reference).withRememberedHistoryForVerification(history)
        advanceTo(world, Step.DECLARE_ATTACKERS)
        if (beforeAttack) return Fixture(world, attacker, blockers, players, attackers)
        assertTrue(world.applyObservedAction(DeclareAttackers(players[0], attackers.associateWith { players[1] })).result.accepted)
        advanceTo(world, Step.DECLARE_BLOCKERS)
        return Fixture(world, attacker, blockers, players, attackers)
    }

    private fun advanceTo(world: ArgentumSearchWorld, step: Step) {
        repeat(64) {
            if (world.authoritativeStateForHost().step == step) return
            val state = world.authoritativeStateForHost()
            assertNull(state.pendingDecision, "No unrelated decisions may be consumed by this fixture")
            assertTrue(world.applyObservedAction(PassPriority(requireNotNull(state.priorityPlayerId))).result.accepted)
        }
        error("Did not reach $step")
    }

    @Test fun `legal omitted declaration retains exact source and full successor across reconstruction`() {
        val a = fixture()
        val b = fixture(reverse = true)
        assertNotEquals(a.blockers[0], b.blockers[0])
        assertEquals(a.world.informationState("p0"), b.world.informationState("p0"))
        val oneBlock = a.world.expandChoices().candidates.mapNotNull { group ->
            val native = (a.world.resolveChoice(group) as? ArgentumResolvedChoice.Action)?.value as? DeclareBlockers
            native?.takeIf { it.blockers.size == 1 }?.let { group to it }
        }.single()
        val omittedId = a.blockers.single { it !in oneBlock.second.blockers }
        val declaration = mutableMapOf(omittedId to listOf(a.attacker))
        val action = DeclareBlockers(a.players[1], declaration)
        val capture = a.world.captureObservedActionForHost("p0", action)
        assertEquals(oneBlock.first, capture.searchGroup)
        assertEquals("p1", capture.actingSite.actor)
        assertEquals("p0", capture.observerInformation.perspectivePlayerId)
        val local = assertIs<ArgentumActionCorrespondence.Matched>(a.world.correspondObservedActionForHost(capture))
        assertEquals(0.0, local.memberProbability)
        val mapped = assertIs<ArgentumActionCorrespondence.Matched>(b.world.correspondObservedActionForHost(capture))
        assertNotEquals(action, mapped.action)
        val frozen = capture.observerInformation
        declaration.clear()
        assertEquals(setOf(omittedId), (capture.action as DeclareBlockers).blockers.keys)
        assertTrue(a.world.applyObservedAction(capture.action).result.accepted)
        assertTrue(b.world.applyObservedAction(mapped.action).result.accepted)
        assertEquals(a.world.informationState("p0"), b.world.informationState("p0"),
            "Compare the complete snapshot, history, commitment and knowledge, not one event")
        assertEquals(frozen, capture.observerInformation)
        assertNotEquals(frozen, a.world.epistemicState("p0"))
        val detail = a.world.informationState("p0").history.mapNotNull { it.detail as? PerspectiveEventDetail.Combat }
            .last { it.declaration == "BLOCKERS" }
        val expectedHandle = capture.bindings.single { it.nativeId == omittedId }.observerHandle
        assertEquals(setOf("history-object:v1:$expectedHandle"), detail.assignments.keys)
    }

    private fun assertNativeAndSearchSuccessors(root: ArgentumSearchWorld,
        expectedOptionality: Boolean? = null, accept: (GameAction) -> Boolean) {
        val actor = requireNotNull(root.actorToAct())
        val state = root.authoritativeStateForHost()
        val pair = root.expandChoices().candidates.map { choice -> choice to when (val resolved = root.resolveChoice(choice)) {
            is ArgentumResolvedChoice.Action -> resolved.value
            is ArgentumResolvedChoice.Decision -> SubmitDecision(requireNotNull(state.pendingDecision).playerId, resolved.value)
        } }.first { accept(it.second) }
        val selected = root.fork() as ArgentumSearchWorld
        val observed = root.fork() as ArgentumSearchWorld
        assertTrue(selected.step(pair.first).accepted)
        assertTrue(observed.applyObservedAction(pair.second).result.accepted)
        assertEquals(expectedOptionality, selected.informationState(actor).history
            .mapNotNull { it.detail as? PerspectiveEventDetail.Choice }.last().strategicallyOptional)
        for (viewer in listOf("p0", "p1")) assertEquals(selected.informationState(viewer), observed.informationState(viewer),
            "Native and search submission must agree for actor=$actor, observer=$viewer")

        fun advanceAgainst(factual: ArgentumSearchWorld, exact: ExactObservedAction? = null) {
            val expected = factual.informationState(actor)
            val belief = ParticleBelief.from(BeliefBatch<Weighted<SearchWorld>>(
                listOf(Weighted(root.fork(), 1.0)), BeliefDiagnostics(BeliefMode.POLICY_CONDITIONED_V1,
                    1, 1, 0, 1.0, 1.0, 0.0, 0)), BeliefMode.POLICY_CONDITIONED_V1)
            val update = belief.advance(actor, pair.first.signature, updateSeed = 17L, exactAction = exact,
                observation = ParticleObservationCondition(expected.knowledge.knowledgeDigest) {
                    it.informationState(actor) == expected
                })
            for (viewer in listOf("p0", "p1")) assertEquals(factual.informationState(viewer),
                update.belief.weightedWorlds().single().value.informationState(viewer))
        }
        // Direct factual submission -> signature-based search propagation, including decisions.
        advanceAgainst(observed)
        val capture = assertNotNull(selected.lastObservedActionCaptureForHost(actor))
        assertEquals(pair.second, capture.action)
        assertNull((selected.fork() as ArgentumSearchWorld).lastObservedActionCaptureForHost(actor),
            "A derived world must not retain its parent's predecessor")
        if (pair.second is SubmitDecision) {
            // Native decision execution is covered above; qualified decision transport is not
            // implemented and must not gain an artificial match solely for this regression.
            assertEquals(ArgentumCorrespondenceRefusal.UNSUPPORTED_ACTION_FAMILY,
                assertIs<ArgentumActionCorrespondence.Unsupported>(root.correspondObservedActionForHost(capture)).reason)
        } else {
            // Search factual submission -> exact observed-member propagation.
            advanceAgainst(selected, capture.particleAction())
        }
    }

    @Test fun `ordinary singleton pass preserves complete actor information across both propagation routes`() {
        val root = ArgentumSearchWorld.create(environment(), "pass-route", 42613L, 42613L,
            historyObjectReference = mode)
        assertNativeAndSearchSuccessors(root, expectedOptionality = false) { it is PassPriority }
    }

    @Test fun `historical search retains menu optionality while direct observation declares its changed behavior`() {
        val root = fixture(reference = PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1).world
        val menu = root.expandChoices()
        assertTrue(menu.candidates.size > 1)
        val choice = menu.candidates.first {
            val native = (root.resolveChoice(it) as? ArgentumResolvedChoice.Action)?.value
            native is DeclareBlockers && native.blockers.isEmpty()
        }
        val native = assertIs<ArgentumResolvedChoice.Action>(root.resolveChoice(choice)).value
        val observed = root.fork() as ArgentumSearchWorld
        val propagated = root.fork() as ArgentumSearchWorld
        val actor = requireNotNull(root.actorToAct())
        assertTrue(root.step(choice).accepted)
        assertTrue(observed.applyObservedAction(native).result.accepted)
        fun optionality(world: ArgentumSearchWorld) = world.informationState(actor).history
            .mapNotNull { it.detail as? PerspectiveEventDetail.Choice }.last().strategicallyOptional
        assertEquals(true, optionality(root))
        assertNull(optionality(observed))
        assertNull(root.observedChoicePropagationForHost(actor, choice), "Search retains historical provenance")
        assertNull((observed.fork() as ArgentumSearchWorld).observedChoicePropagationForHost(actor, choice),
            "Derived worlds must not inherit the last accepted observation's origin")
        val propagation = assertNotNull(observed.observedChoicePropagationForHost(actor, choice))
        assertTrue(propagation(propagated, choice).accepted)
        assertEquals(observed.informationState(actor), propagated.informationState(actor))
        assertFailsWith<IllegalArgumentException> { propagation(propagated, choice) }
        assertEquals("observed-native:proposal-independent-origin-aware-propagation-v5:" +
            "${root.historyEventOrder.name}:LEGACY_SNAPSHOT_V1", observed.observedActionBehaviorId())
    }

    @Test fun `empty declarations and pending responses have identical actor histories across submission routes`() {
        val attack = fixture(beforeAttack = true).world
        assertNativeAndSearchSuccessors(attack) { it is DeclareAttackers && it.attackers.isEmpty() }
        val block = fixture()
        assertNativeAndSearchSuccessors(block.world) { it is DeclareBlockers && it.blockers.isEmpty() }
        assertTrue(block.world.applyObservedAction(DeclareBlockers(block.players[1],
            block.blockers.associateWith { listOf(block.attacker) })).result.accepted)
        repeat(64) {
            if (block.world.authoritativeStateForHost().pendingDecision != null) {
                assertNativeAndSearchSuccessors(block.world) { it is SubmitDecision }
                return
            }
            assertTrue(block.world.applyObservedAction(PassPriority(requireNotNull(
                block.world.authoritativeStateForHost().priorityPlayerId))).result.accepted)
        }
        error("No genuine pending decision")
    }

    @Test fun `same-group distinct creatures yield different complete successor information`() {
        val f = fixture()
        val a = f.world.fork() as ArgentumSearchWorld
        val b = f.world.fork() as ArgentumSearchWorld
        val first = a.applyObservedAction(DeclareBlockers(f.players[1], mapOf(f.blockers[0] to listOf(f.attacker))))
        val second = b.applyObservedAction(DeclareBlockers(f.players[1], mapOf(f.blockers[1] to listOf(f.attacker))))
        assertTrue(first.result.accepted && second.result.accepted)
        assertEquals(first.choice, second.choice)
        assertNotEquals(a.informationState("p0"), b.informationState("p0"))
    }

    @Test fun `independent declaration rows normalize in complete successor information`() {
        val f = fixture(twoAttackers = true)
        val a = f.world.fork() as ArgentumSearchWorld
        val b = f.world.fork() as ArgentumSearchWorld
        val pairs = f.blockers.zip(f.attackers).map { it.first to listOf(it.second) }
        assertTrue(a.applyObservedAction(DeclareBlockers(f.players[1], pairs.toMap())).result.accepted)
        assertTrue(b.applyObservedAction(DeclareBlockers(f.players[1], pairs.reversed().toMap())).result.accepted)
        assertEquals(a.informationState("p0"), b.informationState("p0"))
        assertEquals(a.informationState("p1"), b.informationState("p1"))
    }

    @Test fun `attacker damage ordering remains distinguished`() {
        val f = fixture()
        val a = f.world.fork() as ArgentumSearchWorld
        val b = f.world.fork() as ArgentumSearchWorld
        fun action(ids: List<EntityId>) = DeclareBlockers(f.players[1], ids.associateWith { listOf(f.attacker) })
        assertTrue(a.applyObservedAction(action(f.blockers)).result.accepted)
        assertTrue(b.applyObservedAction(action(f.blockers)).result.accepted)
        // The pinned engine accepts the explicit order action at the attacker's priority window.
        assertTrue(a.applyObservedAction(PassPriority(f.players[1])).result.accepted)
        assertTrue(b.applyObservedAction(PassPriority(f.players[1])).result.accepted)
        assertTrue(a.applyObservedAction(OrderBlockers(f.players[0], f.attacker, f.blockers)).result.accepted)
        assertTrue(b.applyObservedAction(OrderBlockers(f.players[0], f.attacker, f.blockers)).result.accepted)
        assertEquals(a.informationState("p0"), b.informationState("p0"))
        val c = f.world.fork() as ArgentumSearchWorld
        assertTrue(c.applyObservedAction(action(f.blockers)).result.accepted)
        assertTrue(c.applyObservedAction(PassPriority(f.players[1])).result.accepted)
        assertTrue(c.applyObservedAction(OrderBlockers(f.players[0], f.attacker, f.blockers.reversed())).result.accepted)
        assertNotEquals(a.informationState("p0"), c.informationState("p0"))
        // Independently pin the projection's ordered blocker list: sorting declaration rows
        // must not sort a list whose order can be used for damage assignment.
        val state = a.authoritativeStateForHost()
        val reordered = a.withSampledState(state.updateEntity(f.attacker) {
            it.with(com.wingedsheep.engine.state.components.combat.BlockedComponent(f.blockers.reversed()))
        }, 72L)
        assertNotEquals(a.epistemicState("p0").observation.combat, reordered.epistemicState("p0").observation.combat)
    }

    @Test fun `wrong actors and illegal declarations cannot advance history`() {
        val f = fixture()
        val before = f.world.informationState("p0")
        assertFailsWith<IllegalArgumentException> {
            f.world.applyObservedAction(DeclareBlockers(f.players[0], mapOf(f.blockers[0] to listOf(f.attacker))))
        }
        val illegal = f.world.applyObservedAction(DeclareBlockers(f.players[1], mapOf(f.attacker to listOf(f.blockers[0]))))
        assertFalse(illegal.result.accepted)
        assertEquals(before, f.world.informationState("p0"))
        assertEquals(before.observation, f.world.epistemicState("p0").observation)
    }

    @Test fun `pending decision ids rebind while wrong responders refuse`() {
        val f = fixture()
        assertTrue(f.world.applyObservedAction(DeclareBlockers(f.players[1],
            f.blockers.associateWith { listOf(f.attacker) })).result.accepted)
        repeat(64) {
            val pending = f.world.authoritativeStateForHost().pendingDecision
            if (pending != null) {
                assertIs<CombatResolutionDecision>(pending)
                val choice = f.world.expandChoices().candidates.first()
                val response = assertIs<ArgentumResolvedChoice.Decision>(f.world.resolveChoice(choice)).value
                    .withDecisionId("foreign-capture-decision")
                val before = f.world.informationState("p0")
                assertFailsWith<IllegalArgumentException> {
                    f.world.applyObservedAction(SubmitDecision(f.players.single { it != pending.playerId }, response))
                }
                assertEquals(before, f.world.informationState("p0"))
                assertTrue(f.world.applyObservedAction(SubmitDecision(pending.playerId, response)).result.accepted)
                return
            }
            assertTrue(f.world.applyObservedAction(PassPriority(requireNotNull(f.world.authoritativeStateForHost().priorityPlayerId))).result.accepted)
        }
        error("Combat resolution decision was not reached")
    }

    @Test fun `particle likelihood keeps omitted member zero and unsupported correspondence separate`() {
        val f = fixture()
        val (group, selected) = f.world.expandChoices().candidates.mapNotNull { choice ->
            val native = (f.world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value as? DeclareBlockers
            native?.takeIf { it.blockers.size == 1 }?.let { choice to it }
        }.single()
        val omitted = DeclareBlockers(f.players[1], mapOf(f.blockers.single { it !in selected.blockers } to listOf(f.attacker)))
        val exact = f.world.captureObservedActionForHost("p0", omitted)
        fun belief() = ParticleBelief.from(BeliefBatch<Weighted<SearchWorld>>(
            listOf(Weighted(f.world, 1.0)), BeliefDiagnostics(BeliefMode.POLICY_CONDITIONED_V1,
                1, 1, 0, 1.0, 1.0, 0.0, 0)), BeliefMode.POLICY_CONDITIONED_V1)
        val depleted = assertFailsWith<ExactObservationDepletionException> {
            belief().advance("p1", group.signature, UniformOpponentPolicy, 7L, exactAction = exact.particleAction())
        }
        assertEquals(ExactObservationFailureKind.EXACT_MEMBER_ZERO_MASS, depleted.report.kind)
        assertEquals(ExactObservationFailureCounts(1, exactMemberZeroMass = 1), depleted.report.counts)
        val unsupported = assertFailsWith<UnsupportedObservedActionException> {
            belief().advance("p1", group.signature, UniformOpponentPolicy, 7L,
                exactAction = ExactObservedAction { ExactObservedActionResolution.Unsupported("MISSING_BINDING") })
        }
        assertEquals(ExactObservationFailureCounts(1, unavailableCorrespondence = 1), unsupported.report?.counts)
        assertEquals(mapOf("MISSING_BINDING" to 1), unsupported.report?.correspondenceReasons)
        val expected = f.world.fork() as ArgentumSearchWorld
        assertTrue(expected.applyObservedAction(omitted).result.accepted)
        // Without an opponent likelihood this is an observed own/consistency action, not a
        // claim that the omitted member has positive probability under the opponent model.
        val update = belief().advance("p1", group.signature, updateSeed = 7L,
            exactAction = exact.particleAction(), observation = ParticleObservationCondition(expected.informationState("p0").knowledge.knowledgeDigest) {
                (it as ArgentumSearchWorld).knowledgeConsistencyFailure("p0", expected.informationState("p0")) == null
            })
        assertEquals(expected.informationState("p0"), update.belief.weightedWorlds().single().value.informationState("p0"))
    }

    @Test fun `qualified join refuses missing ambiguous and changed incarnations without native equality fallback`() {
        val f = fixture()
        val capture = f.world.captureObservedActionForHost("p0",
            DeclareBlockers(f.players[1], mapOf(f.blockers[0] to listOf(f.attacker))))
        val bindings = capture.bindings
        assertTrue(bindings.all { it.observerIncarnationQualified })
        assertEquals(ArgentumCorrespondenceRefusal.MISSING_BINDING,
            QualifiedObservedObjectCorrespondence.bind(bindings, emptyList()).second)
        assertEquals(ArgentumCorrespondenceRefusal.AMBIGUOUS_BINDING,
            QualifiedObservedObjectCorrespondence.bind(bindings, bindings + bindings.first()).second)
        assertEquals(ArgentumCorrespondenceRefusal.INCARNATION_MISMATCH,
            QualifiedObservedObjectCorrespondence.bind(bindings, bindings.map { it.copy(observerIncarnationQualified = false) }).second)
        val state = f.world.authoritativeStateForHost()
        val id = f.blockers[0]
        val stamp = state.objectIdentities.getValue(id)
        val changed = f.world.withSampledState(state.copy(objectIdentities = state.objectIdentities +
            (id to stamp.copy(generation = stamp.generation + 1000))), 93L)
        assertIs<ArgentumActionCorrespondence.Unsupported>(changed.correspondObservedActionForHost(capture))
    }

    @Test fun `behavior modes preserve historical defaults and explicit representative mass`() {
        val f = fixture()
        val group = f.world.expandChoices().candidates.first { it.operationFamily == SemanticOperationFamily.DECLARE_BLOCKERS }
        val native = assertIs<ArgentumResolvedChoice.Action>(f.world.resolveChoice(group)).value
        val capture = f.world.captureObservedActionForHost("p0", native)
        val result = assertIs<ArgentumActionCorrespondence.Matched>(f.world.correspondObservedActionForHost(capture))
        assertEquals(1.0, result.memberProbability)
        assertEquals(ArgentumActionCorrespondence.REPRESENTATIVE_ONLY, result.memberSelectionBehaviorId)
        assertEquals(f.world.observedActionBehaviorId(), capture.behaviorId)
        val old = fixture(reference = PerspectiveHistoryObjectReference.REMEMBERED_BATTLEFIELD_AND_RESOLUTION_SOURCE_V1)
        assertEquals(old.world.expandChoices(), f.world.expandChoices())
        assertNotEquals(old.world.exactRevision(), f.world.exactRevision())
        val env = environment()
        assertEquals(PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1,
            ArgentumSearchWorld.create(env, "legacy", 1, 1).historyObjectReference)
    }

    @Test fun `genuine alternate private draw still refuses complete observer information`() {
        val env = environment()
        val original = ArgentumSearchWorld.create(env, "wrong-draw", 3, 3, historyObjectReference = mode)
        val state = original.authoritativeStateForHost()
        val viewer = env.playerIds[1]
        val library = state.getLibrary(viewer)
        val other = library.indexOfFirst { state.getEntity(it)!!.get<CardComponent>()!!.name !=
            state.getEntity(library.first())!!.get<CardComponent>()!!.name }
        assertTrue(other > 0)
        val swapped = library.toMutableList().apply { val first = this[0]; this[0] = this[other]; this[other] = first }
        val hypothesis = original.withSampledState(state.copy(zones = state.zones + (ZoneKey(viewer, Zone.LIBRARY) to swapped)), 44L)
        assertEquals(original.informationState("p1"), hypothesis.informationState("p1"))
        val handSize = state.getHand(viewer).size
        repeat(96) {
            if (original.authoritativeStateForHost().getHand(viewer).size > handSize) {
                assertNotNull(hypothesis.knowledgeConsistencyFailure("p1", original.informationState("p1")))
                return
            }
            val actions = original.expandChoices().candidates.map { original.resolveChoice(it) }
                .filterIsInstance<ArgentumResolvedChoice.Action>().map { it.value }
            val action = actions.firstOrNull { it is PassPriority }
                ?: actions.first { (it is DeclareAttackers && it.attackers.isEmpty()) ||
                    (it is DeclareBlockers && it.blockers.isEmpty()) }
            assertTrue(original.applyObservedAction(action).result.accepted)
            assertTrue(hypothesis.applyObservedAction(action).result.accepted)
        }
        error("No actual draw occurred")
    }
}
