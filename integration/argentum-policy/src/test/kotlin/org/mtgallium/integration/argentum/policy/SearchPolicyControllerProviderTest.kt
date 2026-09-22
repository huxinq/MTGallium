package org.mtgallium.integration.argentum.policy

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
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ByteArrayResource
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
import org.mtgallium.agent.argentum.policy.LivePolicyConfig

class SearchPolicyControllerProviderTest {
    @Test
    fun `yaml binding retains bracketed card names in open-deck declarations`() {
        val source = YamlPropertySourceLoader().load(
            "open-decks",
            ByteArrayResource(
                """
                game:
                  ai:
                    search-teacher:
                      known-decks:
                        p0:
                          "[Mountain]": 8
                          "[Lightning Bolt]": 4
                        p1:
                          "[Island]": 9
                          "[Counterspell]": 3
                """.trimIndent().toByteArray(),
            ),
        ).single()
        val environment = StandardEnvironment().also { it.propertySources.addFirst(source) }

        val properties = Binder.get(environment)
            .bind("game.ai.search-teacher", Bindable.of(SearchPolicyProperties::class.java))
            .get()

        assertEquals(
            mapOf(
                "p0" to mapOf("Mountain" to 8, "Lightning Bolt" to 4),
                "p1" to mapOf("Island" to 9, "Counterspell" to 3),
            ),
            properties.knownDecks,
        )
        SearchPolicyControllerProvider(fullRegistry(), LivePolicyConfig(), properties.knownDecks)
    }

    @Test
    fun `provider rejects incomplete open-deck declarations before game start`() {
        assertFailsWith<IllegalArgumentException> {
            SearchPolicyControllerProvider(
                fullRegistry(), LivePolicyConfig(), mapOf("p0" to mapOf("Mountain" to 8)),
            )
        }
    }

    @Test
    fun `provider rejects invalid open-deck declarations before game start`() {
        val registry = fullRegistry()
        listOf(
            mapOf("p0" to mapOf("Mountain" to 8), "p1" to emptyMap()),
            mapOf("p0" to mapOf("Mountain" to 0), "p1" to mapOf("Mountain" to 8)),
            mapOf("p0" to mapOf("not-a-card" to 8), "p1" to mapOf("Mountain" to 8)),
        ).forEach { declarations ->
            assertFailsWith<IllegalArgumentException> {
                SearchPolicyControllerProvider(registry, LivePolicyConfig(), declarations)
            }
        }
    }

    @Test
    fun `provider snapshots caller deck declarations`() {
        val registry = fullRegistry()
        val declarations = linkedMapOf(
            "p0" to linkedMapOf("Island" to 8),
            "p1" to linkedMapOf("Mountain" to 9),
        )
        val provider = SearchPolicyControllerProvider(registry, LivePolicyConfig(), declarations)
        declarations.getValue("p0")["Island"] = 1
        declarations.getValue("p1").clear()

        val p0 = EntityId("snapshot-p0")
        val p1 = EntityId("snapshot-p1")
        val humanDeck = Deck.of("Island" to 8)
        val policyDeck = Deck.of("Mountain" to 9)
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(GameConfig(players = listOf(
                PlayerConfig("Human", humanDeck, playerId = p0),
                PlayerConfig("Search policy", policyDeck, playerId = p1),
            ), skipMulligans = false, useHandSmoother = false, startingPlayerIndex = 1, seed = 17L))
        }
        val setup = replaySetup(17L, 1, p0, humanDeck, p1, policyDeck)
        val controller = provider.create(AiControllerContext(
            playerId = p1,
            gameSessionId = "snapshot-test",
            snapshot = { AiRuntimeSnapshot(environment.state, AiReplayHistory.Complete(setup, emptyList(), emptyList())) },
        ))
        controller.setDeckList(mapOf("Mountain" to 9))

        assertTrue(controller.decideMulligan(mulliganInfo(environment, p1, true)))
    }

    @Test
    fun `provider rejects an opponent replay deck that differs from its declaration`() {
        val registry = fullRegistry()
        val declarations = mapOf("p0" to mapOf("Island" to 8), "p1" to mapOf("Mountain" to 9))
        val p0 = EntityId("mismatch-p0")
        val p1 = EntityId("mismatch-p1")
        val actualOpponentDeck = Deck.of("Mountain" to 8)
        val policyDeck = Deck.of("Mountain" to 9)
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(GameConfig(players = listOf(
                PlayerConfig("Human", actualOpponentDeck, playerId = p0),
                PlayerConfig("Search policy", policyDeck, playerId = p1),
            ), skipMulligans = false, useHandSmoother = false, startingPlayerIndex = 1, seed = 18L))
        }
        val setup = replaySetup(18L, 1, p0, actualOpponentDeck, p1, policyDeck)
        val controller = SearchPolicyControllerProvider(registry, LivePolicyConfig(), declarations).create(
            AiControllerContext(
                playerId = p1,
                gameSessionId = "mismatch-test",
                snapshot = { AiRuntimeSnapshot(environment.state, AiReplayHistory.Complete(setup, emptyList(), emptyList())) },
            )
        )

        val failure = assertFailsWith<PolicyControllerFailure> {
            controller.decideMulligan(mulliganInfo(environment, p1, true))
        }
        assertTrue(failure.message.orEmpty().contains("Replay initial decks differ"))
    }

    @Test
    fun `deck-order permutations retain the configured open-deck knowledge`() {
        val registry = fullRegistry()
        val declarations = linkedMapOf(
            "p0" to linkedMapOf("Island" to 1, "Mountain" to 8),
            "p1" to linkedMapOf("Mountain" to 9),
        )
        val p0 = EntityId("permutation-p0")
        val p1 = EntityId("permutation-p1")
        val opponentDeck = Deck.of("Mountain" to 8, "Island" to 1)
        val policyDeck = Deck.of("Mountain" to 9)
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(GameConfig(players = listOf(
                PlayerConfig("Human", opponentDeck, playerId = p0),
                PlayerConfig("Search policy", policyDeck, playerId = p1),
            ), skipMulligans = false, useHandSmoother = false, startingPlayerIndex = 1, seed = 19L))
        }
        val setup = replaySetup(19L, 1, p0, opponentDeck, p1, policyDeck)
        val controller = SearchPolicyControllerProvider(registry, LivePolicyConfig(maxPolicyDecisions = 1), declarations).create(
            AiControllerContext(
                playerId = p1,
                gameSessionId = "permutation-test",
                snapshot = { AiRuntimeSnapshot(environment.state, AiReplayHistory.Complete(setup, emptyList(), emptyList())) },
            )
        )
        controller.setDeckList(mapOf("Mountain" to 9))

        assertTrue(controller.decideMulligan(mulliganInfo(environment, p1, true)))
    }

    @Test
    fun `live mulligan routes through declared search policy without authoritative mutation`() {
        val registry = fullRegistry()
        val knownDecks = publicMulliganFixtureDecks()
        val deck = Deck.of(*knownDecks.getValue("p0").entries.map { it.key to it.value }.toTypedArray())
        val p0 = EntityId("human-test-p0")
        val p1 = EntityId("policy-test-p1")
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Human", deck, playerId = p0),
                        PlayerConfig("Search policy", deck, playerId = p1),
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
                ReplayPlayerSetup(p1.value, "Search policy", deck),
            ),
            seatRoster = emptyList(),
        )
        val insights = mutableListOf<SearchPolicyInsight>()
        val snapshot = AiRuntimeSnapshot(
            environment.state,
            AiReplayHistory.Complete(setup, emptyList(), emptyList()),
        )
        val world = ArgentumSearchWorld.create(
            environment.fork(),
            gameId = "second-seat-information-stability",
            seedBase = 20260825L,
            effectiveSetupSeed = 818L,
            knownDecks = knownDecks,
        )
        val firstInformation = world.informationState("p1")
        repeat(10) { assertEquals(firstInformation, world.informationState("p1"), "projection call $it") }
        val beliefSource = ArgentumKnownDeckBeliefWorldSource(world)
        assertEquals(firstInformation, world.informationState("p1"))
        beliefSource.sample(
            firstInformation,
            knownDecks,
            beliefSeed = 17L,
            count = 1,
        )
        val provider = SearchPolicyControllerProvider(
            registry,
            LivePolicyConfig(maxPolicyDecisions = 1),
            knownDecks,
            insightSink = { _, insight -> insights += insight },
        )
        val controller = provider.create(
            AiControllerContext(
                playerId = p1,
                gameSessionId = "live-mulligan-test",
                snapshot = { snapshot },
            )
        )
        controller.setDeckList(knownDecks.getValue("p1"))
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
        val knownDecks = publicMulliganFixtureDecks()
        val deck = Deck.of(*knownDecks.getValue("p0").entries.map { it.key to it.value }.toTypedArray())
        val p0 = EntityId("history-test-p0")
        val p1 = EntityId("history-test-p1")
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Human", deck, playerId = p0),
                        PlayerConfig("Search policy", deck, playerId = p1),
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
                ReplayPlayerSetup(p1.value, "Search policy", deck),
            ),
            seatRoster = emptyList(),
        )
        val cases = listOf(
            Triple("REPLAY_HISTORY_UNAVAILABLE", AiReplayHistory.Unavailable, null),
            Triple(
                "REPLAY_HISTORY_TRUNCATED",
                AiReplayHistory.TruncatedPrefix(setup, emptyList(), emptyList()),
                0,
            ),
            Triple(
                "PERSISTENT_YIELD_HISTORY_UNSUPPORTED",
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
                0,
            ),
        )

        for ((expectedCode, history, expectedActionIndex) in cases) {
            val insights = mutableListOf<SearchPolicyInsight>()
            val controller = SearchPolicyControllerProvider(
                registry,
                LivePolicyConfig(maxPolicyDecisions = 1),
                knownDecks,
                insightSink = { _, insight -> insights += insight },
            ).create(
                AiControllerContext(
                    playerId = p1,
                    gameSessionId = "history-contract-test",
                    snapshot = { AiRuntimeSnapshot(environment.state, history) },
                )
            )

            val failure = assertFailsWith<PolicyControllerFailure> {
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
            assertEquals(expectedActionIndex, insights.single().actionIndex)
        }
    }

    @Test
    fun `second-seat policy player waits for the earlier human mulligan action`() {
        val registry = fullRegistry()
        val knownDecks = publicMulliganFixtureDecks()
        val deck = Deck.of(*knownDecks.getValue("p0").entries.map { it.key to it.value }.toTypedArray())
        val human = EntityId("mulligan-human-p0")
        val policyPlayer = EntityId("mulligan-policy-p1")
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Human", deck, playerId = human),
                        PlayerConfig("Search policy", deck, playerId = policyPlayer),
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
                ReplayPlayerSetup(policyPlayer.value, "Search policy", deck),
            ),
            seatRoster = emptyList(),
        )
        val lock = Any()
        val actions = mutableListOf<com.wingedsheep.engine.core.GameAction>()
        val controller = SearchPolicyControllerProvider(
            registry,
            LivePolicyConfig(maxPolicyDecisions = 1),
            knownDecks,
        ).create(
            AiControllerContext(
                playerId = policyPlayer,
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
                    hand = synchronized(lock) { environment.state.getHand(policyPlayer) },
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

    private fun publicMulliganFixtureDecks(): Map<String, Map<String, Int>> = mapOf(
        "p0" to mapOf("Mountain" to 60),
        "p1" to mapOf("Mountain" to 60),
    )

    private fun replaySetup(
        seed: Long,
        startingPlayerIndex: Int,
        p0: EntityId,
        p0Deck: Deck,
        p1: EntityId,
        p1Deck: Deck,
    ) = ReplaySetup(
        seed = seed,
        format = Format.Standard,
        attackMode = AttackMode.MULTIPLE,
        skipMulligans = false,
        useHandSmoother = false,
        startingPlayerIndex = startingPlayerIndex,
        players = listOf(
            ReplayPlayerSetup(p0.value, "Human", p0Deck),
            ReplayPlayerSetup(p1.value, "Search policy", p1Deck),
        ),
        seatRoster = emptyList(),
    )

    private fun mulliganInfo(
        environment: GameEnvironment,
        playerId: EntityId,
        isOnThePlay: Boolean,
    ) = MulliganInfo(
        hand = environment.state.getHand(playerId),
        mulliganCount = 0,
        cardsToPutOnBottom = 0,
        isOnThePlay = isOnThePlay,
    )
}
