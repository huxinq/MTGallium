package org.mtgallium.integration.argentum.policy

import com.wingedsheep.ai.ActionResponse
import com.wingedsheep.ai.AiPlayerController
import com.wingedsheep.ai.llm.BottomCardsInfo
import com.wingedsheep.ai.llm.CardSummary
import com.wingedsheep.ai.llm.MulliganInfo
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.view.ClientGameState
import com.wingedsheep.engine.view.LegalActionInfo
import com.wingedsheep.gameserver.GameServerApplication
import com.wingedsheep.gameserver.ai.AiControllerContext
import com.wingedsheep.gameserver.ai.AiControllerProvider
import com.wingedsheep.gameserver.ai.AiReplayHistory
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.model.EntityId
import org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumStateFingerprint
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.argentum.policy.ResolvedPolicyDecision
import org.mtgallium.agent.infoset.core.RootActionSelection
import org.mtgallium.agent.argentum.policy.LivePolicyConfig
import org.mtgallium.agent.argentum.policy.LivePolicySession
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration(proxyBeanMethods = false)
@Import(GameServerApplication::class)
@EnableConfigurationProperties(SearchPolicyProperties::class)
class ArgentumPolicyConfiguration {
    @Bean
    fun searchPolicyControllerProvider(
        registry: CardRegistry,
        properties: SearchPolicyProperties,
    ): AiControllerProvider = SearchPolicyControllerProvider(
        registry,
        LivePolicyConfig(
            baseSeed = properties.baseSeed,
        ).let { current -> current.copy(
            profileId = if (
                properties.particles == current.particles &&
                properties.simulations == current.simulations &&
                properties.maxPolicyDecisions == current.maxPolicyDecisions &&
                properties.explorationConstant == current.explorationConstant
            ) current.profileId else "configuration-override",
            particles = properties.particles,
            simulations = properties.simulations,
            maxPolicyDecisions = properties.maxPolicyDecisions,
            explorationConstant = properties.explorationConstant,
        ) },
        properties.knownDecks,
    )
}

fun main(args: Array<String>) {
    SpringApplication.run(ArgentumPolicyConfiguration::class.java, *args)
}

class SearchPolicyControllerProvider(
    private val registry: CardRegistry,
    private val runtimeConfig: LivePolicyConfig,
    knownDecks: Map<String, Map<String, Int>>,
    private val insightSink: (AiControllerContext, SearchPolicyInsight) -> Unit = { _, _ -> },
) : AiControllerProvider {
    private val knownDecks = snapshotKnownDecks(knownDecks, registry)

    override val mode = "search-teacher"

    override fun create(context: AiControllerContext): AiPlayerController = SearchPolicyController(
        context,
        registry,
        runtimeConfig,
        knownDecks,
        publishInsight = { insight -> insightSink(context, insight) },
    )
}

private class SearchPolicyController(
    private val context: AiControllerContext,
    private val registry: CardRegistry,
    private val config: LivePolicyConfig,
    private val knownDecks: Map<String, Map<String, Int>>,
    private val publishInsight: (SearchPolicyInsight) -> Unit,
) : AiPlayerController {
    private var synchronized: SynchronizedRuntime? = null
    private var suppliedDeckList: Map<String, Int>? = null
    private var suppliedDeckSeat: String? = null

    override fun chooseAction(
        state: ClientGameState,
        legalActions: List<LegalActionInfo>,
        pendingDecision: com.wingedsheep.engine.core.PendingDecision?,
        recentGameLog: List<String>,
    ): ActionResponse = failClosed { resolve(choose()) }

    override fun decideMulligan(mulliganMessage: MulliganInfo): Boolean = failClosed {
        when (val resolved = choose(waitForPolicyTurn = true).resolved) {
            is ArgentumResolvedChoice.Action -> when (resolved.value) {
                is KeepHand -> true
                is TakeMulligan -> false
                else -> error("Search policy selected ${resolved.value::class.simpleName} during mulligan")
            }
            is ArgentumResolvedChoice.Decision -> error("Search policy selected a decision response during mulligan")
        }
    }

    override fun chooseBottomCards(message: BottomCardsInfo): List<EntityId> = failClosed {
        when (val resolved = choose().resolved) {
            is ArgentumResolvedChoice.Action -> (resolved.value as? BottomCards)?.cardIds
                ?: error("Search policy selected ${resolved.value::class.simpleName} while bottoming cards")
            is ArgentumResolvedChoice.Decision -> error("Search policy selected a decision response while bottoming cards")
        }
    }

    override fun setDeckList(deckList: Map<String, Int>, archetype: String?) {
        suppliedDeckList = snapshotDeck(deckList, registry, "Search policy deck list")
        suppliedDeckSeat = null
        synchronized?.playerSeat?.let(::checkSuppliedDeckList)
    }

    override fun chooseDraftPick(
        pack: List<CardSummary>, pickedSoFar: List<CardSummary>, packNumber: Int,
        pickNumber: Int, picksRequired: Int, passDirection: String,
    ): List<String> = unsupported("BOOSTER_DRAFT")

    override fun chooseWinstonAction(
        pileCards: List<CardSummary>, pileIndex: Int, pileSizes: List<Int>, pickedSoFar: List<CardSummary>,
    ): Boolean = unsupported("WINSTON_DRAFT")

    override fun chooseGridDraftPick(
        grid: List<CardSummary?>, availableSelections: List<String>, pickedSoFar: List<CardSummary>,
    ): String = unsupported("GRID_DRAFT")

    @Synchronized
    private fun choose(waitForPolicyTurn: Boolean = false): ResolvedPolicyDecision {
        val deadline = System.nanoTime() + MULLIGAN_TURN_WAIT_NANOS
        while (true) {
            val snapshot = context.snapshot() ?: error("Authoritative replay snapshot is not available")
            val current = synchronize(snapshot)
            if (current.runtime.canChoose) {
                val decision = current.runtime.choose()
                publishDecision(decision, current.fingerprint)
                return decision
            }
            check(waitForPolicyTurn) { "Search policy received a decision while another seat must act" }
            check(System.nanoTime() < deadline) { "MULLIGAN_TURN_TIMEOUT" }
            // Opening-hand choices arrive concurrently, but semantic reduction orders them by seat.
            // Wait for the earlier action before searching this controller's root.
            Thread.sleep(MULLIGAN_TURN_POLL_MILLIS)
        }
    }

    private fun synchronize(snapshot: com.wingedsheep.gameserver.ai.AiRuntimeSnapshot): SynchronizedRuntime {
        val history = when (val replay = snapshot.replayHistory) {
            AiReplayHistory.Unavailable -> error(
                "REPLAY_HISTORY_UNAVAILABLE: Search policy cannot reconstruct the live state without recorded replay inputs"
            )
            is AiReplayHistory.Complete -> replay
            is AiReplayHistory.TruncatedPrefix -> error(
                "REPLAY_HISTORY_TRUNCATED: Search policy cannot reconstruct the live state from a replay prefix"
            )
        }
        check(history.yields.isEmpty()) {
            "PERSISTENT_YIELD_HISTORY_UNSUPPORTED: Search policy cannot yet replay out-of-band yields"
        }
        val previous = synchronized
        val next = if (previous != null && history.actions.size >= previous.actions.size &&
            history.actions.subList(0, previous.actions.size) == previous.actions
        ) {
            history.actions.drop(previous.actions.size).forEach(previous.runtime::applyObserved)
            previous.copy(actions = history.actions.toList())
        } else {
            rebuild(history.setup, history.actions)
        }
        val authoritative = ArgentumStateFingerprint.of(snapshot.state)
        val shadow = next.runtime.authoritativeFingerprint
        if (authoritative != shadow) {
            publishInsight(
                SearchPolicyInsight(
                    actionIndex = history.actions.size,
                    failureCode = "AUTHORITATIVE_FINGERPRINT_MISMATCH",
                    diagnostic = "Shadow reconstruction did not match the authoritative action prefix",
                    authoritativeFingerprint = authoritative,
                    shadowFingerprint = shadow,
                )
            )
            error("AUTHORITATIVE_FINGERPRINT_MISMATCH")
        }
        val verified = next.copy(fingerprint = authoritative)
        synchronized = verified
        return verified
    }

    private fun rebuild(
        setup: ReplaySetup,
        actions: List<com.wingedsheep.engine.core.GameAction>,
    ): SynchronizedRuntime {
        require(setup.players.size == 2) { "Search policy v1 requires exactly two seats" }
        require(setup.format == com.wingedsheep.sdk.core.Format.Standard) {
            "Search policy v1 requires the Standard runtime format"
        }
        require(setup.teams == null) { "Search policy v1 does not support teams" }
        require(!setup.useHandSmoother) { "Search policy requires hand smoothing to be disabled" }
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = setup.players.map { player ->
                    PlayerConfig(
                        name = player.name,
                        deck = player.deck,
                        startingLife = player.startingLife,
                        playerId = EntityId(player.playerId),
                        commanderCardName = player.commanderCardName,
                    )
                },
                startingHandSize = setup.startingHandSize,
                skipMulligans = setup.skipMulligans,
                useHandSmoother = setup.useHandSmoother,
                handSmootherCandidates = setup.handSmootherCandidates,
                startingPlayerIndex = setup.startingPlayerIndex,
                format = setup.format,
                attackMode = setup.attackMode,
                teams = setup.teams,
                seed = setup.seed,
            )
        )
        val replayDecks = setup.players.mapIndexed { index, player ->
            "p$index" to player.deck.cards.groupingBy { it }.eachCount()
        }.toMap()
        require(replayDecks == knownDecks) {
            "Replay initial decks differ from configured open-deck declarations"
        }
        val playerIndex = setup.players.indexOfFirst { it.playerId == context.playerId.value }
        require(playerIndex >= 0) { "Search policy seat is absent from replay setup" }
        val playerSeat = "p$playerIndex"
        checkSuppliedDeckList(playerSeat)
        val gameSessionId = requireNotNull(context.gameSessionId) {
            "Search policy controllers can choose only after attachment to a live game"
        }
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = gameSessionId,
            seedBase = config.baseSeed,
            expander = UnifiedSemanticExpander(actionSpaceProfile = config.actionSpaceProfile),
            effectiveSetupSeed = setup.seed,
            knownDecks = knownDecks,
        )
        val runtime = LivePolicySession(
            world = world,
            player = playerSeat,
            knownDecks = knownDecks,
            gameId = gameSessionId,
            config = config,
        )
        actions.forEach(runtime::applyObserved)
        return SynchronizedRuntime(runtime, actions.toList(), runtime.authoritativeFingerprint, playerSeat)
    }

    private fun checkSuppliedDeckList(seat: String) {
        val supplied = suppliedDeckList ?: return
        if (suppliedDeckSeat == seat) return
        require(supplied == knownDecks.getValue(seat)) {
            "Search policy deck list differs from configured declaration for $seat"
        }
        suppliedDeckSeat = seat
    }

    private fun resolve(decision: ResolvedPolicyDecision): ActionResponse = when (val selected = decision.resolved) {
        is ArgentumResolvedChoice.Action -> ActionResponse.SubmitAction(selected.value)
        is ArgentumResolvedChoice.Decision -> ActionResponse.SubmitDecision(context.playerId, selected.value)
    }

    private fun publishDecision(decision: ResolvedPolicyDecision, fingerprint: String) {
        val search = (decision.selection as? RootActionSelection.Searched)?.search
        val diagnostics = search?.diagnostics
        publishInsight(
            SearchPolicyInsight(
                actionIndex = decision.decisionIndex,
                chosenLabel = decision.choice.display.label,
                chosenSignature = decision.choice.signature,
                candidates = search?.candidates?.sortedByDescending { it.visits }?.map { candidate ->
                    SearchCandidateInsight(
                        label = candidate.choice.display.label,
                        signature = candidate.choice.signature,
                        visits = candidate.visits,
                        meanValue = candidate.meanValue,
                        policyProbability = candidate.policyProbability,
                        chosen = candidate.choice.signature == decision.choice.signature,
                    )
                } ?: listOf(
                    SearchCandidateInsight(
                        label = decision.choice.display.label,
                        signature = decision.choice.signature,
                        visits = 0,
                        meanValue = 0.0,
                        policyProbability = 1.0,
                        chosen = true,
                    )
                ),
                rootValue = search?.rootValue,
                thinkTimeMs = decision.latencyMillis,
                simulations = diagnostics?.simulations ?: 0,
                particles = diagnostics?.particles ?: decision.belief.acceptedParticles,
                nodes = diagnostics?.nodes ?: 0,
                maximumDepth = diagnostics?.maximumDepth ?: 0,
                exhaustiveNodes = diagnostics?.exhaustiveNodes ?: 0,
                nonExhaustiveNodes = diagnostics?.nonExhaustiveNodes ?: 0,
                wideningEvents = diagnostics?.wideningEvents ?: 0,
                beliefEntropy = decision.belief.entropy,
                effectiveSampleSize = decision.belief.effectiveSampleSizeAfter,
                resamplingCount = decision.belief.resamplingCount,
                reconditioningCount = decision.belief.resamplingCount,
                authoritativeFingerprint = fingerprint,
                shadowFingerprint = fingerprint,
            )
        )
    }

    private inline fun <T> failClosed(block: () -> T): T = try {
        block()
    } catch (fatal: PolicyControllerFailure) {
        throw fatal
    } catch (failure: Throwable) {
        val snapshot = context.snapshot()
        val code = when {
            failure.message?.startsWith("UNSUPPORTED_INFORMATION_STATE") == true -> "UNSUPPORTED_INFORMATION_STATE"
            failure.message?.startsWith("REPLAY_HISTORY_UNAVAILABLE") == true -> "REPLAY_HISTORY_UNAVAILABLE"
            failure.message?.startsWith("REPLAY_HISTORY_TRUNCATED") == true -> "REPLAY_HISTORY_TRUNCATED"
            failure.message?.startsWith("PERSISTENT_YIELD_HISTORY_UNSUPPORTED") == true ->
                "PERSISTENT_YIELD_HISTORY_UNSUPPORTED"
            failure.message?.contains("FINGERPRINT") == true -> "SYNCHRONIZATION_FAILURE"
            failure.message?.contains("Observed") == true -> "REDUCER_FAILURE"
            else -> "SEARCH_POLICY_FAILURE"
        }
        publishInsight(
            SearchPolicyInsight(
                actionIndex = failureActionIndex(snapshot),
                failureCode = code,
                diagnostic = (failure.message ?: failure::class.simpleName ?: "Search policy failure").take(500),
                authoritativeFingerprint = snapshot?.state?.let(ArgentumStateFingerprint::of),
                shadowFingerprint = synchronized?.runtime?.authoritativeFingerprint,
            )
        )
        throw PolicyControllerFailure("$code: ${failure.message}", failure)
    }

    /**
     * Failure telemetry reports only a count whose provenance is explicit: a complete or
     * truncated recorded prefix, or the already synchronized replay. An unavailable history has
     * no action count, so it remains null rather than being presented as an inferred zero.
     */
    private fun failureActionIndex(
        snapshot: com.wingedsheep.gameserver.ai.AiRuntimeSnapshot?,
    ): Int? = when (val replay = snapshot?.replayHistory) {
        null -> synchronized?.actions?.size
        AiReplayHistory.Unavailable -> null
        is AiReplayHistory.Complete -> replay.actions.size
        is AiReplayHistory.TruncatedPrefix -> replay.actions.size
    }

    private fun <T> unsupported(kind: String): T = throw PolicyControllerFailure(
        "UNSUPPORTED_GAME_TYPE: Search policy v1 does not support $kind"
    )

    private data class SynchronizedRuntime(
        val runtime: LivePolicySession,
        val actions: List<com.wingedsheep.engine.core.GameAction>,
        val fingerprint: String,
        val playerSeat: String,
    )

    private companion object {
        const val MULLIGAN_TURN_POLL_MILLIS = 25L
        const val MULLIGAN_TURN_WAIT_NANOS = 300_000_000_000L
    }
}

private fun snapshotKnownDecks(
    knownDecks: Map<String, Map<String, Int>>,
    registry: CardRegistry,
): Map<String, Map<String, Int>> {
    require(knownDecks.keys == setOf("p0", "p1")) {
        "Search policy open-deck declarations must contain exactly p0 and p1"
    }
    return knownDecks.mapValues { (seat, deck) ->
        snapshotDeck(deck, registry, "Search policy open-deck declaration for $seat")
    }.toMap()
}

private fun snapshotDeck(
    deck: Map<String, Int>,
    registry: CardRegistry,
    subject: String,
): Map<String, Int> {
    require(deck.isNotEmpty()) { "$subject must not be empty" }
    deck.forEach { (cardName, count) ->
        require(cardName.isNotBlank()) { "$subject contains a blank card name" }
        require(count > 0) { "$subject has a non-positive count for $cardName" }
        require(registry.getCard(cardName) != null) { "$subject contains an unknown card: $cardName" }
    }
    return deck.toMap()
}
