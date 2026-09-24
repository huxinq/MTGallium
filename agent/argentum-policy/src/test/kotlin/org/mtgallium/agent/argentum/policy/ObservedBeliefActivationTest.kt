package org.mtgallium.agent.argentum.policy

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.Deck
import org.mtgallium.agent.infoset.argentum.*
import org.mtgallium.agent.infoset.core.*
import kotlin.test.*
import kotlinx.serialization.json.*

/** Public authored game, with no retained replay or fabricated history. */
class ObservedBeliefActivationTest {
    private val qualified = PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2
    private val decks = mapOf("p0" to mapOf("Observed Test Bear" to 40),
        "p1" to mapOf("Observed Test Bear" to 40))

    private fun world(mode: PerspectiveHistoryObjectReference): ArgentumSearchWorld {
        val registry = CardRegistry().apply {
            register(CardDefinition(name = "Observed Test Bear", manaCost = ManaCost.parse("{0}"),
                typeLine = TypeLine.parse("Creature — Bear"), creatureStats = CreatureStats(1, 1)))
        }
        val environment = GameEnvironment.create(registry)
        val deck = Deck.of("Observed Test Bear" to 40)
        environment.reset(GameConfig(players = listOf(PlayerConfig("Alice", deck), PlayerConfig("Bob", deck)),
            seed = 42613L, startingPlayerIndex = 0, skipMulligans = true, useHandSmoother = false))
        val world = ArgentumSearchWorld.create(environment, "observed-live-test", 42613L, 42613L,
            knownDecks = decks, historyObjectReference = mode)
        repeat(32) {
            if (world.authoritativeStateForHost().step == Step.PRECOMBAT_MAIN) return world
            assertNull(world.authoritativeStateForHost().pendingDecision)
            assertTrue(world.applyObservedAction(pass(world)).result.accepted)
        }
        error("Authored game did not reach its first main phase")
    }

    private fun pass(world: ArgentumSearchWorld): PassPriority =
        PassPriority(requireNotNull(world.authoritativeStateForHost().priorityPlayerId))

    private fun cast(world: ArgentumSearchWorld): CastSpell {
        val state = world.authoritativeStateForHost()
        val actor = requireNotNull(state.priorityPlayerId)
        return CastSpell(actor, state.getHand(actor).first())
    }

    private fun preparation(world: ArgentumSearchWorld) = BeliefPreparation(
        world, "p0", decks, BeliefConfig(8, BeliefMode.POLICY_CONDITIONED_V1),
        UniformOpponentPolicy, "observed-live-test")

    @Test fun `exact opponent updates use the conditioning model view instead of the host capture view`() {
        val world = world(qualified)
        val actor = requireNotNull(world.actorToAct())
        val viewer = if (actor == "p0") "p1" else "p0"
        val views = mutableListOf<DecisionView>()
        val model = object : ActionDistributionModel {
            override val id = "exact-conditioning-view-test"
            override val requiresPolicyAnnotations = true
            override fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
                views += context.view
                return ProbabilityDistribution.uniform(context.expansion.candidates)
            }
        }
        val preparation = BeliefPreparation(world, viewer, decks,
            BeliefConfig(8, BeliefMode.POLICY_CONDITIONED_V1), model, UniformOpponentPolicy,
            "exact-conditioning-view-test")
        val observed = world.applyObservedAction(pass(world))
        assertTrue(observed.result.accepted)
        preparation.observeAccepted(world, actor, observed.choice, 0, observed.result.privateToActor)
        assertEquals(ObservedBeliefUpdateRoute.QUALIFIED_EXACT_MEMBER_V1, preparation.lastObservedUpdate?.route)
        assertEquals(8, views.size)
        assertTrue(views.all { it.admission == DecisionAdmission.PRODUCTION && it.annotations })
    }

    @Test fun `conditioning can be declared independently of representation without changing legacy bytes`() {
        val config = BeliefConfig(8, BeliefMode.POLICY_CONDITIONED_V1)
        val encoded = PolicyJson.format.encodeToJsonElement(BeliefConfig.serializer(), config).jsonObject
        assertEquals(setOf("particles", "beliefMode", "beliefArchitecture"), encoded.keys)
        val historical = config.copy(observedConditioning = ObservedBeliefConditioning.HISTORICAL_GROUP_SIGNATURE_V1)
        assertNotEquals(encoded, PolicyJson.format.encodeToJsonElement(BeliefConfig.serializer(), historical))
        val world = world(qualified)
        fun prepare(configuration: BeliefConfig) = BeliefPreparation(
            world, "p0", decks, configuration, UniformOpponentPolicy, "observed-live-test")
        val groupOnly = prepare(historical)
        val nativeDefault = prepare(config)
        assertNotEquals(groupOnly.beliefSnapshot().queries.binding.inferenceModelIdentity,
            nativeDefault.beliefSnapshot().queries.binding.inferenceModelIdentity)
        val actor = requireNotNull(world.actorToAct())
        val capture = world.captureObservedActionForHost("p0", pass(world))
        val observed = world.applyObservedAction(pass(world))
        assertTrue(observed.result.accepted)
        groupOnly.observeAccepted(world, actor, observed.choice, 0, observed.result.privateToActor)
        nativeDefault.observeAccepted(world, actor, observed.choice, 0, observed.result.privateToActor)
        assertEquals(ObservedBeliefUpdateRoute.HISTORICAL_SIGNATURE_V1, groupOnly.lastObservedUpdate?.route)
        assertEquals(ObservedBeliefUpdateRoute.QUALIFIED_EXACT_MEMBER_V1, nativeDefault.lastObservedUpdate?.route)
        assertFailsWith<IllegalArgumentException> {
            groupOnly.observeAccepted(world, actor, observed.choice, 0, observed.result.privateToActor, capture)
        }
    }

    @Test fun `live runtime activates exact pass and explicitly names cast compatibility only in opt-in mode`() {
        for (mode in listOf(PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1, qualified)) {
            for (isCast in listOf(false, true)) {
                val world = world(mode)
                val runtime = LivePolicySession(world, "p0", decks, "observed-live-test",
                    config = LivePolicyConfig(beliefMode = BeliefMode.POLICY_CONDITIONED_V1,
                        actionSpaceProfile = world.semanticExpansionSpecification().actionSpaceProfile),
                    opponentModel = UniformOpponentPolicy)
                assertTrue(runtime.applyObserved(if (isCast) cast(world) else pass(world)).result.accepted)
                val expected = when {
                    mode != qualified -> ObservedBeliefUpdateRoute.HISTORICAL_SIGNATURE_V1
                    isCast -> ObservedBeliefUpdateRoute.UNSUPPORTED_FAMILY_SIGNATURE_COMPATIBILITY_V1
                    else -> ObservedBeliefUpdateRoute.QUALIFIED_EXACT_MEMBER_V1
                }
                assertEquals(expected, assertNotNull(runtime.lastObservedUpdate).route)
                assertEquals(1, runtime.appliedActions)
            }
        }
    }

    @Test fun `standalone preparation uses host predecessor and binds the opt-in identity before updates`() {
        val identities = mutableMapOf<PerspectiveHistoryObjectReference, String>()
        for (mode in listOf(PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1, qualified)) {
            for (isCast in listOf(false, true)) {
                val world = world(mode)
                val preparation = preparation(world)
                val before = preparation.beliefSnapshot().queries.binding.inferenceModelIdentity
                identities[mode]?.let { assertEquals(it, before) }
                identities[mode] = before
                val actor = requireNotNull(world.actorToAct())
                val observed = world.applyObservedAction(if (isCast) cast(world) else pass(world))
                assertTrue(observed.result.accepted)
                preparation.observeAccepted(world, actor, observed.choice, 0, observed.result.privateToActor)
                assertEquals(before, preparation.beliefSnapshot().queries.binding.inferenceModelIdentity)
                assertEquals(when {
                    mode != qualified -> ObservedBeliefUpdateRoute.HISTORICAL_SIGNATURE_V1
                    isCast -> ObservedBeliefUpdateRoute.UNSUPPORTED_FAMILY_SIGNATURE_COMPATIBILITY_V1
                    else -> ObservedBeliefUpdateRoute.QUALIFIED_EXACT_MEMBER_V1
                }, assertNotNull(preparation.lastObservedUpdate).route)
            }
        }
        assertNotEquals(identities.getValue(qualified),
            identities.getValue(PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1))
    }

    @Test fun `historical representation also versions proposal independent observed history provenance`() {
        val world = world(PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1)
        val config = BeliefConfig(8, BeliefMode.POLICY_CONDITIONED_V1)
        fun identity(includeSubmission: Boolean) = "particle-inference-v1-sha256:" + PolicyJson.digest(buildJsonObject {
            put("configuration", PolicyJson.format.encodeToJsonElement(config))
            put("opponentDistribution", PolicyJson.format.encodeToJsonElement(UniformOpponentPolicy.behaviorSpecification))
            put("privateChoiceSelector", PolicyJson.format.encodeToJsonElement(UniformOpponentPolicy.behaviorSpecification))
            if (includeSubmission) put("observedSubmission", world.observedActionBehaviorId())
            put("maintenance", CONDITIONED_BELIEF_INFERENCE_MAINTENANCE)
        })
        val actual = preparation(world).beliefSnapshot().queries.binding.inferenceModelIdentity
        assertNotEquals(identity(includeSubmission = false), actual,
            "Changed observed-history provenance must not retain the historical inference identity")
        assertEquals(identity(includeSubmission = true), actual)
    }

    private fun assertLegacyActorUpdate(root: ArgentumSearchWorld, action: GameAction) {
        val actor = requireNotNull(root.actorToAct())
        val live = root.fork() as ArgentumSearchWorld
        val runtime = LivePolicySession(live, actor, decks, "legacy-observed-actor",
            config = LivePolicyConfig(particles = 8, beliefMode = BeliefMode.POLICY_CONDITIONED_V1,
                actionSpaceProfile = live.semanticExpansionSpecification().actionSpaceProfile),
            opponentModel = UniformOpponentPolicy)
        // A mismatched actor history depletes the conditioned population and throws the
        // KNOWLEDGE_ONLY_REBUILD refusal. Acceptance here must complete the actual live update.
        assertTrue(runtime.applyObserved(action).result.accepted)
        assertEquals(1, runtime.appliedActions)
        assertEquals(ObservedBeliefUpdateRoute.HISTORICAL_SIGNATURE_V1, runtime.lastObservedUpdate?.route)

        val actual = root.fork() as ArgentumSearchWorld
        val preparation = BeliefPreparation(actual, actor, decks,
            BeliefConfig(8, BeliefMode.POLICY_CONDITIONED_V1),
            UniformOpponentPolicy, "legacy-observed-actor")
        val before = preparation.beliefSnapshot()
        val observed = actual.applyObservedAction(action)
        assertTrue(observed.result.accepted)
        preparation.observeAccepted(actual, actor, observed.choice, 0, observed.result.privateToActor)
        val after = preparation.beliefSnapshot()
        assertNotEquals(before.queries.binding.epistemicDigest, after.queries.binding.epistemicDigest)
        assertEquals(before.queries.binding.inferenceModelIdentity, after.queries.binding.inferenceModelIdentity)
        val expected = actual.informationState(actor)
        assertNull(expected.history.mapNotNull { it.detail as? PerspectiveEventDetail.Choice }.last().strategicallyOptional)
        val batch = after.hypotheses.materialize().batch
        assertEquals(8, batch.particles.size)
        assertEquals(0, batch.diagnostics.rejectedParticles)
        for (particle in batch.particles) assertEquals(expected, particle.value.informationState(actor),
            "Compare complete actor successors after live signature propagation")
        assertEquals(expected, live.informationState(actor))
    }

    @Test fun `legacy live runtime and preparation propagate native empty combat and pending actor responses`() {
        val root = world(PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1)
        val players = root.authoritativeStateForHost().turnOrder
        fun accept(action: GameAction) { assertTrue(root.applyObservedAction(action).result.accepted) }
        fun advanceUntil(predicate: () -> Boolean) {
            repeat(256) {
                if (predicate()) return
                assertNull(root.authoritativeStateForHost().pendingDecision)
                accept(pass(root))
            }
            error("Legacy actor fixture did not reach its declared boundary")
        }
        accept(cast(root))
        advanceUntil {
            val state = root.authoritativeStateForHost()
            state.activePlayerId == players[1] && state.step == Step.PRECOMBAT_MAIN &&
                state.priorityPlayerId == players[1] && state.stack.isEmpty()
        }
        accept(cast(root))
        advanceUntil { root.authoritativeStateForHost().stack.isEmpty() &&
            root.authoritativeStateForHost().priorityPlayerId == players[1] }
        accept(cast(root))
        advanceUntil {
            val state = root.authoritativeStateForHost()
            state.activePlayerId == players[0] && state.step == Step.DECLARE_ATTACKERS
        }
        assertTrue(root.expandChoices().candidates.size > 1, "Empty attack must have a real alternative")
        assertLegacyActorUpdate(root, DeclareAttackers(players[0], emptyMap()))
        val attacker = root.authoritativeStateForHost().getBattlefield(players[0]).single()
        accept(DeclareAttackers(players[0], mapOf(attacker to players[1])))
        advanceUntil { root.authoritativeStateForHost().step == Step.DECLARE_BLOCKERS }
        assertTrue(root.expandChoices().candidates.size > 1, "Empty block must have a real alternative")
        assertLegacyActorUpdate(root, DeclareBlockers(players[1], emptyMap()))
        val blockers = root.authoritativeStateForHost().getBattlefield(players[1])
        assertEquals(2, blockers.size)
        accept(DeclareBlockers(players[1], blockers.associateWith { listOf(attacker) }))
        advanceUntil { root.authoritativeStateForHost().pendingDecision != null }
        val pending = assertIs<CombatResolutionDecision>(root.authoritativeStateForHost().pendingDecision)
        val choice = root.expandChoices().candidates.first()
        val response = assertIs<ArgentumResolvedChoice.Decision>(root.resolveChoice(choice)).value
        assertLegacyActorUpdate(root, SubmitDecision(pending.playerId, response))
    }

    @Test fun `live backend preserves complete exact zero mass and latches without rebuilding`() {
        val world = world(qualified)
        fun accept(action: GameAction) { assertTrue(world.applyObservedAction(action).result.accepted) }
        fun advanceUntil(predicate: () -> Boolean) {
            repeat(256) {
                if (predicate()) return
                assertNull(world.authoritativeStateForHost().pendingDecision)
                accept(pass(world))
            }
            error("Authored game did not reach its declared boundary")
        }
        val players = world.authoritativeStateForHost().turnOrder
        accept(cast(world))
        advanceUntil {
            val state = world.authoritativeStateForHost()
            state.activePlayerId == players[1] && state.step == Step.PRECOMBAT_MAIN &&
                state.priorityPlayerId == players[1] && state.stack.isEmpty()
        }
        accept(cast(world))
        advanceUntil { world.authoritativeStateForHost().stack.isEmpty() &&
            world.authoritativeStateForHost().priorityPlayerId == players[1] }
        accept(cast(world))
        advanceUntil {
            val state = world.authoritativeStateForHost()
            state.activePlayerId == players[0] && state.step == Step.DECLARE_ATTACKERS
        }
        val attacker = world.authoritativeStateForHost().getBattlefield(players[0]).single()
        accept(DeclareAttackers(players[0], mapOf(attacker to players[1])))
        advanceUntil { world.authoritativeStateForHost().step == Step.DECLARE_BLOCKERS }
        val blockers = world.authoritativeStateForHost().getBattlefield(players[1])
        assertEquals(2, blockers.size)
        val representative = world.expandChoices().candidates.mapNotNull {
            ((world.resolveChoice(it) as? ArgentumResolvedChoice.Action)?.value as? DeclareBlockers)
                ?.takeIf { action -> action.blockers.size == 1 }
        }.single()
        val omitted = blockers.single { it !in representative.blockers }
        val backend = ArgentumParticleBeliefBackend(world, "p0", decks,
            BeliefConfig(8, BeliefMode.POLICY_CONDITIONED_V1), UniformOpponentPolicy,
            "observed-live-test", ArgentumBeliefProposalAuditSink.NONE)
        val before = backend.lifecycleDiagnostics
        val observed = world.applyObservedAction(DeclareBlockers(players[1], mapOf(omitted to listOf(attacker))))
        assertTrue(observed.result.accepted)
        val failure = assertFailsWith<ConditionedBeliefReconstructionRequired> {
            backend.advance(world, "p1", observed.choice, 0, false)
        }
        assertEquals("EXACT_MEMBER_ZERO_MASS", failure.reasonCode)
        val report = assertNotNull(failure.exactObservationFailure)
        assertEquals(ExactObservationFailureCounts(8, exactMemberZeroMass = 8), report.counts)
        assertEquals(report, assertIs<ExactObservationDepletionException>(failure.cause).report)
        assertSame(failure, assertFailsWith<ConditionedBeliefReconstructionRequired> { backend.snapshot() })
        assertEquals(before.rebuildAttempts, backend.lifecycleDiagnostics.rebuildAttempts)
        assertEquals(before.sequentialUpdateDepletions + 1, backend.lifecycleDiagnostics.sequentialUpdateDepletions)
        assertEquals(ObservedBeliefUpdateRoute.QUALIFIED_EXACT_MEMBER_V1, backend.lastObservedUpdate?.route)
    }
}
