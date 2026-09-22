package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.sth.StrongholdSet
import com.wingedsheep.sdk.model.Deck
import kotlin.test.*
import kotlinx.serialization.encodeToString
import org.mtgallium.agent.infoset.core.*

class NativeDecisionContextTest {
    @Test fun `checkpoint refresh transfers lazy native requests and exact forks reuse their captured revision`() {
        val source = fixture()
        val checkpoint = source.fork() as ArgentumSearchWorld
        val actor = requireNotNull(source.actorToAct())
        val view = DecisionView(64, DecisionAdmission.PRODUCTION, true)
        val context = source.decisionContext(view)
        assertFalse(context.informationDemanded)
        assertTrue(checkpoint.copyDerivedCachesFrom(source))
        assertSame(context, checkpoint.decisionContext(view))
        assertTrue(checkpoint.copyDerivedCachesFrom(checkpoint))
        assertSame(context, checkpoint.decisionContext(view))
        val hit = checkpoint.fork() as ArgentumSearchWorld
        assertSame(context, hit.decisionContext(view))
        // A new menu must use the transferred lazy source, not make another history capture.
        val widened = hit.decisionContext(DecisionView(128))
        assertEquals(0, checkpoint.decisionRevisionCaptureCount())
        assertEquals(0, hit.decisionRevisionCaptureCount())
        assertFalse(context.informationDemanded)
        val expectedInformation = source.informationState(actor)
        val pass = context.expansion.candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
        assertTrue(source.step(pass).accepted)
        val state = context.site().epistemic
        assertSame(state, checkpoint.epistemicState(actor))
        assertSame(state, hit.epistemicState(actor))
        assertSame(state, widened.site().epistemic)
        assertEquals(expectedCapturedInformation(expectedInformation, context), hit.decisionContext(view).information())
        assertEquals(context.expansion.candidates, hit.decisionContext(view).site().information().candidates)
        assertEquals(1, source.decisionRevisionCaptureCount())
    }

    @Test fun `checkpoint refresh transfers eager epistemic values before any native request exists`() {
        val source = fixture()
        val checkpoint = source.fork() as ArgentumSearchWorld
        val actor = requireNotNull(source.actorToAct())
        val state = source.epistemicState(actor)
        val legacy = source.informationState(actor)
        assertTrue(checkpoint.copyDerivedCachesFrom(source))
        val hit = checkpoint.fork() as ArgentumSearchWorld
        assertSame(state, checkpoint.epistemicState(actor))
        assertSame(state, hit.epistemicState(actor))
        assertSame(legacy, hit.informationState(actor))
        assertEquals(0, hit.semanticProjectionWork().first)
        assertSame(state, hit.decisionContext(DecisionView(64)).site().epistemic)
    }

    @Test fun `changed revision refuses cache transfer without disturbing the checkpoint`() {
        val source = fixture()
        val checkpoint = source.fork() as ArgentumSearchWorld
        val view = DecisionView(64)
        val oldContext = checkpoint.decisionContext(view)
        val oldState = oldContext.site().epistemic
        val pass = source.expandChoices().candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
        assertTrue(source.step(pass).accepted)
        val current = source.decisionContext(view)
        assertFalse(checkpoint.copyDerivedCachesFrom(source))
        assertSame(oldContext, checkpoint.decisionContext(view))
        assertSame(oldState, checkpoint.epistemicState(oldContext.actor))
        assertFalse(source.copyDerivedCachesFrom(checkpoint))
        assertSame(current, source.decisionContext(view))
        // The caller's existing refusal fallback remains an exact fork of the source.
        assertSame(current, (source.fork() as ArgentumSearchWorld).decisionContext(view))
    }

    @Test fun `changed profiles refuse cache transfer even at the same state and history`() {
        val source = fixture()
        val view = DecisionView(64, DecisionAdmission.PRODUCTION, true)
        val original = source.decisionContext(view)
        val reprofiled = listOf(
            source.withActionSpaceProfile(SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1),
            source.forkWithHeuristicProfile(ArgentumHeuristicProfile.PRODUCTION_EXPIRING),
        )
        for (world in reprofiled) {
            assertSame(source.authoritativeStateForHost(), world.authoritativeStateForHost())
            val ownContext = world.decisionContext(view)
            assertEquals(original.site().epistemic.historyCommitment, ownContext.site().epistemic.historyCommitment)
            assertFalse(world.copyDerivedCachesFrom(source))
            assertSame(ownContext, world.decisionContext(view))
            assertFalse(source.copyDerivedCachesFrom(world))
            assertSame(original, source.decisionContext(view))
            val ownCheckpoint = world.fork() as ArgentumSearchWorld
            assertTrue(ownCheckpoint.copyDerivedCachesFrom(world))
            assertSame(ownContext, ownCheckpoint.decisionContext(view))
        }
    }

    @Test fun `separate equal configuration owners transfer native caches and lazy revision`() {
        val environment = fixtureEnvironment()
        val source = fixture(environment)
        val target = fixture(environment)
        val view = DecisionView(64, DecisionAdmission.PRODUCTION, true)
        val context = source.decisionContext(view)
        assertFalse(context.informationDemanded)
        val equalProfiles = listOf(target,
            source.withActionSpaceProfile(SearchActionSpaceProfile.RULES_EXACT_V1),
            source.forkWithHeuristicProfile(ArgentumHeuristicProfile.PRODUCTION))
        for (world in equalProfiles) {
            assertSame(source.authoritativeStateForHost(), world.authoritativeStateForHost())
            assertTrue(world.copyDerivedCachesFrom(source))
            val hit = world.fork() as ArgentumSearchWorld
            assertSame(context, hit.decisionContext(view))
            val coldView = hit.decisionContext(DecisionView(128))
            assertSame(context.site().epistemic, coldView.site().epistemic)
            assertEquals(context.information(), hit.decisionContext(view).information())
            assertEquals(0, hit.decisionRevisionCaptureCount())
        }
    }

    @Test fun `different proposal perspective and knowledge inputs refuse cache transfer`() {
        val environment = fixtureEnvironment()
        val decks = mapOf("p0" to fixtureDeck, "p1" to fixtureDeck)
        fun world(gameId: String = "synthetic-native-decision", seed: Long = 8L,
            knownDecks: Map<String, Map<String, Int>>? = decks,
            expander: UnifiedSemanticExpander = UnifiedSemanticExpander(),
            env: GameEnvironment = environment) = ArgentumSearchWorld.create(
                env, gameId, seed, effectiveSetupSeed = 901L, knownDecks = knownDecks, expander = expander)
        val source = world()
        val context = source.decisionContext(DecisionView(64))
        val reversedPlayers = environment.fork().also {
            it.restore(environment.state, environment.playerIds.reversed(), environment.stepCount)
        }
        val incompatible = listOf(
            "game ID" to world(gameId = "different-game"),
            "proposal seed base" to world(seed = 9L),
            "known decks" to world(knownDecks = decks + ("p1" to mapOf("Mountain" to 20))),
            "aliases" to world(env = reversedPlayers),
            "expansion limit configuration" to world(expander = UnifiedSemanticExpander(maxResponses = 32)),
            "expansion attempt configuration" to world(expander = UnifiedSemanticExpander(maxAttempts = 4096)),
            "proposal algorithm" to world(expander = UnifiedSemanticExpander(proposalAlgorithmVersion = "test-other-v1")),
        )
        for ((label, other) in incompatible) {
            assertSame(source.authoritativeStateForHost(), other.authoritativeStateForHost(), label)
            assertFalse(other.copyDerivedCachesFrom(source), label)
            assertFalse(source.copyDerivedCachesFrom(other), label)
            assertSame(context, source.decisionContext(DecisionView(64)), label)
        }
        // null and empty decks normalize to equal world knowledge maps, but only empty enables
        // the production annotator. Its presence is therefore a separate configuration input.
        val disabled = world(knownDecks = null)
        val enabled = world(knownDecks = emptyMap())
        assertFalse(disabled.copyDerivedCachesFrom(enabled))
        assertFalse(enabled.copyDerivedCachesFrom(disabled))
    }

    @Test fun `cold views capture history once and preserve deferred per-view selected menus across advancement`() {
        val world = fixture()
        val oracle = world.fork() as ArgentumSearchWorld
        val actor = requireNotNull(world.actorToAct())
        val views = listOf(DecisionView(1), DecisionView(32), DecisionView(64),
            DecisionView(64, DecisionAdmission.PRODUCTION),
            DecisionView(64, DecisionAdmission.PRODUCTION, true))
        // Independently project each selected expansion without using decisionContext.
        // Read each oracle fork, leaving the oracle's own information cache as expansion left it.
        val expected = views.map { view ->
            val limit = requireNotNull(view.limit)
            val expansion = when {
                view.annotations -> oracle.expandChoicesWithPolicyAnnotations(limit)
                view.admission == DecisionAdmission.PRODUCTION -> oracle.expandChoicesForPolicyAdmission(limit)
                else -> oracle.expandChoices(limit)
            }
            expansion to (oracle.fork() as ArgentumSearchWorld).informationState(actor)
        }
        assertEquals(0, world.decisionRevisionCaptureCount())
        val contexts = views.map(world::decisionContext)
        // A per-view fork would perform five captures; sharing epistemic values alone is insufficient.
        assertEquals(1, world.decisionRevisionCaptureCount())
        assertTrue(contexts.none { it.informationDemanded })
        assertSame(contexts.first(), world.decisionContext(views.first()))
        assertEquals(1, world.decisionRevisionCaptureCount())

        val exactFork = world.fork() as ArgentumSearchWorld
        assertSame(contexts.first(), exactFork.decisionContext(views.first()))
        val forkContext = exactFork.decisionContext(DecisionView(16))
        assertEquals(0, exactFork.decisionRevisionCaptureCount())
        val pass = contexts.last().expansion.candidates.single {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        }
        assertTrue(world.step(pass).accepted)
        // Demand all old values only after advancement, in reverse order to expose menu overwrite.
        for (index in contexts.indices.reversed()) {
            val context = contexts[index]
            val (expansion, information) = expected[index]
            assertEquals(expansion, context.expansion)
            assertEquals(actor, context.actor)
            assertEquals(PolicyJson.format.encodeToString(expectedCapturedInformation(information, context)),
                PolicyJson.format.encodeToString(context.information()))
            val epistemic = context.site().epistemic
            assertEquals(information.observation, epistemic.observation)
            assertEquals(information.knowledge, epistemic.knowledge)
            assertEquals(information.history, epistemic.history)
            assertEquals(information.historyCommitment, epistemic.historyCommitment)
            assertSame(contexts.first().site().epistemic, epistemic)
        }
        assertSame(contexts.first().site().epistemic, forkContext.site().epistemic)
        val next = world.decisionContext(DecisionView(32))
        world.decisionContext(DecisionView(64, DecisionAdmission.PRODUCTION, true))
        assertEquals(2, world.decisionRevisionCaptureCount())
        assertNotEquals(contexts.first().site().epistemic.epistemicDigest, next.site().epistemic.epistemicDigest)
        assertEquals(0, exactFork.decisionRevisionCaptureCount())
    }

    @Test fun `cold decision views share one captured epistemic projection before and after a live step`() {
        val world = fixture()
        val contexts = listOf(DecisionView(32), DecisionView(64, DecisionAdmission.PRODUCTION),
            DecisionView(64, DecisionAdmission.PRODUCTION, true)).map(world::decisionContext)
        assertTrue(contexts.none { it.informationDemanded })
        val state = contexts.last().site().epistemic
        contexts.forEach { assertSame(state, it.site().epistemic) }
        assertSame(state, world.epistemicState(contexts.first().actor))
        val fork = world.fork() as ArgentumSearchWorld
        assertSame(state, fork.decisionContext(DecisionView(16)).site().epistemic)
        val pass = contexts.first().expansion.candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
        assertTrue(world.step(pass).accepted)
        assertNotSame(state, world.epistemicState(contexts.first().actor))
        contexts.forEach { assertSame(state, it.site().epistemic) }
    }

    @Test fun `deferred context keeps its captured actor information and menu after live transition`() {
        val world = fixture()
        val actor = requireNotNull(world.actorToAct())
        val expected = world.informationState(actor)
        val context = world.decisionContext(DecisionView(64))
        val pass = context.expansion.candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
        assertTrue(world.step(pass).accepted)
        // No site or compatibility information was demanded before advancing the live world.
        val site = context.site()
        assertEquals(expected.observation, site.epistemic.observation)
        assertEquals(expected.knowledge, site.epistemic.knowledge)
        assertEquals(expected.historyCommitment, site.epistemic.historyCommitment)
        assertEquals(expected.candidates, site.expansion.candidates)
        assertEquals(PolicyJson.format.encodeToString(expected), PolicyJson.format.encodeToString(context.information()))
        assertNotEquals(site.epistemic.epistemicDigest, world.epistemicState(actor).epistemicDigest)
        assertSame(site, context.site())
    }

    @Test fun `menu demand views share knowledge and repeated native requests reuse their captured values`() {
        val world = fixture()
        val actor = requireNotNull(world.actorToAct())
        val state = world.epistemicState(actor)
        assertSame(state, world.epistemicState(actor))
        val before = world.freshAuthoritativeFingerprintForHost()
        val semantic = world.decisionContext(DecisionView(64))
        assertSame(semantic, world.decisionContext(DecisionView(64)))
        val admitted = world.decisionContext(DecisionView(64, DecisionAdmission.PRODUCTION))
        val annotated = world.decisionContext(DecisionView(64, DecisionAdmission.PRODUCTION, true))
        for (context in listOf(semantic, admitted, annotated)) {
            assertSame(state, context.site().epistemic)
            assertSame(state.history, context.site().epistemic.history)
            assertEquals(actor, context.actor)
            assertEquals(state.epistemicDigest, context.site().epistemic.epistemicDigest)
        }
        assertEquals(before, world.freshAuthoritativeFingerprintForHost())
        val other = world.epistemicState(if (actor == "p0") "p1" else "p0")
        assertNotEquals(actor, other.perspectivePlayerId)
        assertEquals(world.informationState(other.perspectivePlayerId).historyCommitment, other.historyCommitment)
        val fork = world.fork() as ArgentumSearchWorld
        assertSame(state, fork.epistemicState(actor))
        assertSame(semantic, fork.decisionContext(DecisionView(64)))
    }

    @Test fun `native contexts record each selected semantic production annotated and limited menu`() {
        val world = fixture().withActionSpaceProfile(SearchActionSpaceProfile.RULES_EXACT_V1)
        advanceToMultipleChoice(world)
        val actor = requireNotNull(world.actorToAct())
        val expected = world.informationState(actor)
        val contexts = listOf(DecisionView(1), DecisionView(64),
            DecisionView(64, DecisionAdmission.PRODUCTION),
            DecisionView(64, DecisionAdmission.PRODUCTION, true)).map(world::decisionContext)
        contexts.forEach { context ->
            val information = context.information()
            assertEquals(expectedCapturedInformation(expected, context), information)
            assertEquals(context.expansion.candidates, information.candidates)
            assertEquals(EpistemicState.capture(expected).epistemicDigest, context.site().epistemic.epistemicDigest)
        }
        assertNotEquals(contexts[0].expansion.candidates.map { it.signature },
            contexts[1].expansion.candidates.map { it.signature })
        assertNotEquals(contexts[0].information().informationStateDigest,
            contexts[1].information().informationStateDigest)
    }

    private val fixtureDeck = mapOf("Mountain" to 18, "Shock" to 2)

    private fun fixtureEnvironment(): GameEnvironment {
        val registry = CardRegistry().apply { register(PortalSet.basicLands); register(StrongholdSet.cards) }
        return GameEnvironment.create(registry).also { env -> env.reset(GameConfig(
            players = listOf("Alice", "Bob").map { PlayerConfig(it, Deck.of(*fixtureDeck.entries.map { e -> e.key to e.value }.toTypedArray())) },
            seed = 901L, skipMulligans = true, startingHandSize = 7, startingPlayerIndex = 0)) }
    }

    private fun fixture(environment: GameEnvironment = fixtureEnvironment()): ArgentumSearchWorld {
        return ArgentumSearchWorld.create(environment, "synthetic-native-decision", 8L, effectiveSetupSeed = 901L,
            knownDecks = mapOf("p0" to fixtureDeck, "p1" to fixtureDeck))
    }

    private fun expectedCapturedInformation(
        base: InformationStateRepresentation,
        context: DecisionSiteRequest,
    ): InformationStateRepresentation = base.copy(
        informationStateDigest = InformationStateRepresentationDigest.compute(
            base.observation.observationDigest,
            base.historyCommitment,
            base.knowledge.knowledgeDigest,
            context.actor,
            context.expansion.candidates.map { it.signature },
            context.expansion.proposalVersion,
        ),
        candidates = context.expansion.candidates,
    )

    private fun advanceToMultipleChoice(world: ArgentumSearchWorld) {
        repeat(16) {
            if (world.expandChoices().candidates.size > 1) return
            val pass = world.expandChoices().candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
            check(world.step(pass).accepted)
        }
        error("Fixture did not reach a multiple-choice decision")
    }
}
