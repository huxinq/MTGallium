package org.mtgallium.integration.searchteacher

import com.wingedsheep.ai.llm.MulliganInfo
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gameserver.ai.AiControllerContext
import com.wingedsheep.gameserver.ai.AiReplayHistory
import com.wingedsheep.gameserver.ai.AiRuntimeSnapshot
import com.wingedsheep.gameserver.replay.ReplayPlayerSetup
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.gameserver.replay.ReplayYieldEntry
import com.wingedsheep.gameserver.replay.ReplayYieldOp
import com.wingedsheep.gym.ExactlyOneSubmissionResult
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.mtg.sets.tokens.PredefinedTokens
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.mtgallium.agent.infoset.argentum.ArgentumStateFingerprint
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumKnownDeckBeliefWorldSource
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.searchteacher.SearchTeacherRuntimeConfig

class SearchTeacherAiControllerProviderTest {
    @Test
    fun `provider fails before game start when the frozen manifest is unavailable`() {
        assertFailsWith<IllegalArgumentException> {
            SearchTeacherAiControllerProvider(
                CardRegistry(), SearchTeacherRuntimeConfig(),
            )
        }
    }

    @Test
    fun `live mulligan routes through declared search policy without authoritative mutation`() {
        val registry = fullRegistry()
        val manifest = publicMulliganFixtureManifest()
        val deck = Deck.of(*manifest.mainDeck.entries.map { it.key to it.value }.toTypedArray())
        val p0 = EntityId("human-test-p0")
        val p1 = EntityId("teacher-test-p1")
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Human", deck, playerId = p0),
                        PlayerConfig("Search Teacher", deck, playerId = p1),
                    ),
                    skipMulligans = false,
                    useHandSmoother = false,
                    startingPlayerIndex = 1,
                    seed = 818L,
                )
            )
        }
        val setup = ReplaySetup(
            seed = 818L,
            format = Format.Standard,
            attackMode = AttackMode.MULTIPLE,
            skipMulligans = false,
            useHandSmoother = false,
            startingPlayerIndex = 1,
            players = listOf(
                ReplayPlayerSetup(p0.value, "Human", deck),
                ReplayPlayerSetup(p1.value, "Search Teacher", deck),
            ),
            seatRoster = emptyList(),
        )
        val insights = mutableListOf<SearchTeacherInsight>()
        val snapshot = AiRuntimeSnapshot(
            environment.state,
            AiReplayHistory.Complete(setup, emptyList(), emptyList()),
        )
        val world = ArgentumSearchWorld.create(
            environment.fork(),
            gameId = "second-seat-information-stability",
            seedBase = 20260825L,
            cardRegistry = registry,
            effectiveSetupSeed = 818L,
            knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
        )
        val firstInformation = world.informationState("p1")
        repeat(10) { assertEquals(firstInformation, world.informationState("p1"), "projection call $it") }
        val beliefSource = ArgentumKnownDeckBeliefWorldSource(world, registry)
        assertEquals(firstInformation, world.informationState("p1"))
        beliefSource.sample(
            firstInformation,
            mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
            beliefSeed = 17L,
            count = 1,
        )
        val provider = SearchTeacherAiControllerProvider(
            registry,
            SearchTeacherRuntimeConfig(maxPolicyDecisions = 1),
            manifest,
            insightSink = { _, insight -> insights += insight },
        )
        val controller = provider.create(
            AiControllerContext(
                playerId = p1,
                gameSessionId = "live-mulligan-test",
                snapshot = { snapshot },
            )
        )
        controller.setDeckList(manifest.mainDeck)
        val before = ArgentumStateFingerprint.of(environment.state)

        controller.decideMulligan(
            MulliganInfo(
                hand = environment.state.getHand(p1),
                mulliganCount = 0,
                cardsToPutOnBottom = 0,
                isOnThePlay = true,
            )
        )

        assertEquals(before, ArgentumStateFingerprint.of(environment.state))
        val insight = assertNotNull(insights.lastOrNull())
        assertTrue(insight.failureCode == null)
        assertEquals(64, insight.simulations)
        assertEquals(8, insight.particles)
        assertEquals(2, insight.candidates.size)
        assertEquals(1, insight.candidates.count { it.chosen })
    }

    @Test
    fun `provider refuses replay inputs that cannot reproduce the live state`() {
        val registry = fullRegistry()
        val manifest = publicMulliganFixtureManifest()
        val deck = Deck.of(*manifest.mainDeck.entries.map { it.key to it.value }.toTypedArray())
        val p0 = EntityId("history-test-p0")
        val p1 = EntityId("history-test-p1")
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Human", deck, playerId = p0),
                        PlayerConfig("Search Teacher", deck, playerId = p1),
                    ),
                    skipMulligans = false,
                    useHandSmoother = false,
                    startingPlayerIndex = 1,
                    seed = 828L,
                )
            )
        }
        val setup = ReplaySetup(
            seed = 828L,
            format = Format.Standard,
            attackMode = AttackMode.MULTIPLE,
            skipMulligans = false,
            useHandSmoother = false,
            startingPlayerIndex = 1,
            players = listOf(
                ReplayPlayerSetup(p0.value, "Human", deck),
                ReplayPlayerSetup(p1.value, "Search Teacher", deck),
            ),
            seatRoster = emptyList(),
        )
        val cases = listOf(
            "REPLAY_HISTORY_UNAVAILABLE" to AiReplayHistory.Unavailable,
            "REPLAY_HISTORY_TRUNCATED" to
                AiReplayHistory.TruncatedPrefix(setup, emptyList(), emptyList()),
            "PERSISTENT_YIELD_HISTORY_UNSUPPORTED" to
                AiReplayHistory.Complete(
                    setup,
                    emptyList(),
                    listOf(
                        ReplayYieldEntry(
                            afterActionCount = 0,
                            playerId = p1.value,
                            op = ReplayYieldOp.CLEAR_ALL,
                        )
                    ),
                ),
        )

        for ((expectedCode, history) in cases) {
            val insights = mutableListOf<SearchTeacherInsight>()
            val controller = SearchTeacherAiControllerProvider(
                registry,
                SearchTeacherRuntimeConfig(maxPolicyDecisions = 1),
                manifest,
                insightSink = { _, insight -> insights += insight },
            ).create(
                AiControllerContext(
                    playerId = p1,
                    gameSessionId = "history-contract-test",
                    snapshot = { AiRuntimeSnapshot(environment.state, history) },
                )
            )

            val failure = assertFailsWith<SearchTeacherControllerFailure> {
                controller.decideMulligan(
                    MulliganInfo(
                        hand = environment.state.getHand(p1),
                        mulliganCount = 0,
                        cardsToPutOnBottom = 0,
                        isOnThePlay = true,
                    )
                )
            }

            assertTrue(failure.message.orEmpty().startsWith(expectedCode))
            assertEquals(expectedCode, insights.single().failureCode)
        }
    }

    @Test
    fun `second-seat teacher waits for the earlier human mulligan action`() {
        val registry = fullRegistry()
        val manifest = publicMulliganFixtureManifest()
        val deck = Deck.of(*manifest.mainDeck.entries.map { it.key to it.value }.toTypedArray())
        val human = EntityId("mulligan-human-p0")
        val teacher = EntityId("mulligan-teacher-p1")
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Human", deck, playerId = human),
                        PlayerConfig("Search Teacher", deck, playerId = teacher),
                    ),
                    skipMulligans = false,
                    useHandSmoother = false,
                    startingPlayerIndex = 0,
                    seed = 919L,
                )
            )
        }
        val setup = ReplaySetup(
            seed = 919L,
            format = Format.Standard,
            attackMode = AttackMode.MULTIPLE,
            skipMulligans = false,
            useHandSmoother = false,
            startingPlayerIndex = 0,
            players = listOf(
                ReplayPlayerSetup(human.value, "Human", deck),
                ReplayPlayerSetup(teacher.value, "Search Teacher", deck),
            ),
            seatRoster = emptyList(),
        )
        val lock = Any()
        val actions = mutableListOf<com.wingedsheep.engine.core.GameAction>()
        val controller = SearchTeacherAiControllerProvider(
            registry,
            SearchTeacherRuntimeConfig(maxPolicyDecisions = 1),
            manifest,
        ).create(
            AiControllerContext(
                playerId = teacher,
                gameSessionId = "ordered-mulligan-test",
                snapshot = {
                    synchronized(lock) {
                        AiRuntimeSnapshot(
                            environment.state,
                            AiReplayHistory.Complete(setup, actions.toList(), emptyList()),
                        )
                    }
                },
            )
        )

        val result = CompletableFuture.supplyAsync {
            controller.decideMulligan(
                MulliganInfo(
                    hand = synchronized(lock) { environment.state.getHand(teacher) },
                    mulliganCount = 0,
                    cardsToPutOnBottom = 0,
                    isOnThePlay = false,
                )
            )
        }
        Thread.sleep(100)
        assertFalse(result.isDone)

        synchronized(lock) {
            val action = KeepHand(human)
            assertTrue(environment.stepExactlyOne(action) is ExactlyOneSubmissionResult.Applied)
            assertTrue(environment.lastRejection == null)
            actions += action
        }

        result.get(30, TimeUnit.SECONDS)
        assertTrue(result.isDone)
    }

    private fun fullRegistry() = CardRegistry().apply {
        register(PredefinedTokens.allTokens)
        MtgSetCatalog.all.forEach { set ->
            register(set.cards)
            register(set.basicLands)
            set.basicLandsFallback?.let { register(it.basicLands) }
        }
    }

    /**
     * A self-contained witness for controller routing. It intentionally does not claim
     * to represent the unpublished frozen experimental deck or profile.
     */
    private fun publicMulliganFixtureManifest() = SearchTeacherDeckManifest(
        id = "public-mulligan-fixture-v1",
        name = "Public mulligan fixture",
        format = "synthetic",
        publishedDate = "2026-09-02",
        source = "first-party public test fixture",
        mainDeck = mapOf("Mountain" to 60),
        sideboard = emptyMap(),
    )
}
