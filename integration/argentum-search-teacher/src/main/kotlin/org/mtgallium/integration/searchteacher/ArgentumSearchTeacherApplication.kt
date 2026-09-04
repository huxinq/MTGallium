package org.mtgallium.integration.searchteacher

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
import org.mtgallium.agent.searchteacher.SearchTeacherDecision
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.searchteacher.SearchTeacherRuntimeConfig
import org.mtgallium.agent.searchteacher.SearchTeacherRuntimeSession
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration(proxyBeanMethods = false)
@Import(GameServerApplication::class)
@EnableConfigurationProperties(SearchTeacherHostProperties::class)
class ArgentumSearchTeacherConfiguration {
    @Bean
    fun searchTeacherControllerProvider(
        registry: CardRegistry,
        properties: SearchTeacherHostProperties,
    ): AiControllerProvider = SearchTeacherAiControllerProvider(
        registry,
        SearchTeacherRuntimeConfig(
            baseSeed = properties.baseSeed,
        ).let { current -> current.copy(
            profileId = if (
                properties.particles == current.particles &&
                properties.simulations == current.simulations &&
                properties.maxPolicyDecisions == current.maxPolicyDecisions &&
                properties.explorationConstant == current.explorationConstant
            ) current.profileId else "experimental-override",
            particles = properties.particles,
            simulations = properties.simulations,
            maxPolicyDecisions = properties.maxPolicyDecisions,
            explorationConstant = properties.explorationConstant,
        ) },
    )
}

fun main(args: Array<String>) {
    SpringApplication.run(ArgentumSearchTeacherConfiguration::class.java, *args)
}

class SearchTeacherAiControllerProvider(
    private val registry: CardRegistry,
    private val runtimeConfig: SearchTeacherRuntimeConfig,
    private val manifest: SearchTeacherDeckManifest = SearchTeacherDeckManifest.frozenMonoRed(),
    private val insightSink: (AiControllerContext, SearchTeacherInsight) -> Unit = { _, _ -> },
) : AiControllerProvider {
    override val mode = "search-teacher"

    init {
        val missing = manifest.mainDeck.keys.filter { registry.getCard(it) == null }
        require(missing.isEmpty()) {
            "Search Teacher deck contains cards missing from the active registry: ${missing.joinToString()}"
        }
        // The frozen source records the published sideboard for provenance, but v1 intentionally
        // locks and exposes only the 60-card main deck.
    }

    override fun create(context: AiControllerContext): AiPlayerController = SearchTeacherController(
        context,
        registry,
        runtimeConfig,
        manifest,
        publishInsight = { insight -> insightSink(context, insight) },
    )
}

private class SearchTeacherController(
    private val context: AiControllerContext,
    private val registry: CardRegistry,
    private val config: SearchTeacherRuntimeConfig,
    private val manifest: SearchTeacherDeckManifest,
    private val publishInsight: (SearchTeacherInsight) -> Unit,
) : AiPlayerController {
    private var synchronized: SynchronizedRuntime? = null

    override fun chooseAction(
        state: ClientGameState,
        legalActions: List<LegalActionInfo>,
        pendingDecision: com.wingedsheep.engine.core.PendingDecision?,
        recentGameLog: List<String>,
    ): ActionResponse = failClosed { resolve(choose()) }

    override fun decideMulligan(mulliganMessage: MulliganInfo): Boolean = failClosed {
        when (val resolved = choose(waitForTeacherTurn = true).resolved) {
            is ArgentumResolvedChoice.Action -> when (resolved.value) {
                is KeepHand -> true
                is TakeMulligan -> false
                else -> error("Search Teacher selected ${resolved.value::class.simpleName} during mulligan")
            }
            is ArgentumResolvedChoice.Decision -> error("Search Teacher selected a decision response during mulligan")
        }
    }

    override fun chooseBottomCards(message: BottomCardsInfo): List<EntityId> = failClosed {
        when (val resolved = choose().resolved) {
            is ArgentumResolvedChoice.Action -> (resolved.value as? BottomCards)?.cardIds
                ?: error("Search Teacher selected ${resolved.value::class.simpleName} while bottoming cards")
            is ArgentumResolvedChoice.Decision -> error("Search Teacher selected a decision response while bottoming cards")
        }
    }

    override fun setDeckList(deckList: Map<String, Int>, archetype: String?) {
        require(deckList == manifest.mainDeck) { "Search Teacher deck differs from the locked manifest" }
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
    private fun choose(waitForTeacherTurn: Boolean = false): SearchTeacherDecision {
        val deadline = System.nanoTime() + MULLIGAN_TURN_WAIT_NANOS
        while (true) {
            val snapshot = context.snapshot() ?: error("Authoritative replay snapshot is not available")
            val current = synchronize(snapshot)
            if (current.runtime.canChoose) {
                val decision = current.runtime.choose()
                publishDecision(decision, current.fingerprint)
                return decision
            }
            check(waitForTeacherTurn) { "Search Teacher received a decision while another seat must act" }
            check(System.nanoTime() < deadline) { "MULLIGAN_TURN_TIMEOUT" }
            // Argentum offers opening-hand choices to both sockets concurrently, while the
            // semantic reducer orders those choices by seat. Let the human finish the earlier
            // semantic action, then search the teacher's real root.
            Thread.sleep(MULLIGAN_TURN_POLL_MILLIS)
        }
    }

    private fun synchronize(snapshot: com.wingedsheep.gameserver.ai.AiRuntimeSnapshot): SynchronizedRuntime {
        val history = when (val replay = snapshot.replayHistory) {
            AiReplayHistory.Unavailable -> error(
                "REPLAY_HISTORY_UNAVAILABLE: Search Teacher cannot reconstruct the live state without recorded replay inputs"
            )
            is AiReplayHistory.Complete -> replay
            is AiReplayHistory.TruncatedPrefix -> error(
                "REPLAY_HISTORY_TRUNCATED: Search Teacher cannot reconstruct the live state from a replay prefix"
            )
        }
        check(history.yields.isEmpty()) {
            "PERSISTENT_YIELD_HISTORY_UNSUPPORTED: Search Teacher cannot yet replay out-of-band yields"
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
                SearchTeacherInsight(
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
        require(setup.players.size == 2) { "Search Teacher v1 requires exactly two seats" }
        require(setup.format == com.wingedsheep.sdk.core.Format.Standard) {
            "Search Teacher v1 requires the Standard runtime format"
        }
        require(setup.teams == null) { "Search Teacher v1 does not support teams" }
        require(!setup.useHandSmoother) { "Search Teacher requires hand smoothing to be disabled" }
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
        val knownDecks = setup.players.mapIndexed { index, player ->
            "p$index" to player.deck.cards.groupingBy { it }.eachCount()
        }.toMap()
        require(knownDecks.values.all { it == manifest.mainDeck }) {
            "Both Search Teacher seats must use the locked deck manifest"
        }
        val teacherIndex = setup.players.indexOfFirst { it.playerId == context.playerId.value }
        require(teacherIndex >= 0) { "Search Teacher seat is absent from replay setup" }
        val gameSessionId = requireNotNull(context.gameSessionId) {
            "Search Teacher controllers can choose only after attachment to a live game"
        }
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = gameSessionId,
            seedBase = config.baseSeed,
            expander = UnifiedSemanticExpander(actionSpaceProfile = config.actionSpaceProfile),
            cardRegistry = registry,
            effectiveSetupSeed = setup.seed,
            knownDecks = knownDecks,
        )
        val runtime = SearchTeacherRuntimeSession(
            world = world,
            teacher = "p$teacherIndex",
            registry = registry,
            knownDecks = knownDecks,
            gameId = gameSessionId,
            config = config,
        )
        actions.forEach(runtime::applyObserved)
        return SynchronizedRuntime(runtime, actions.toList(), runtime.authoritativeFingerprint)
    }

    private fun resolve(decision: SearchTeacherDecision): ActionResponse = when (val selected = decision.resolved) {
        is ArgentumResolvedChoice.Action -> ActionResponse.SubmitAction(selected.value)
        is ArgentumResolvedChoice.Decision -> ActionResponse.SubmitDecision(context.playerId, selected.value)
    }

    private fun publishDecision(decision: SearchTeacherDecision, fingerprint: String) {
        val search = decision.search
        val diagnostics = search?.diagnostics
        publishInsight(
            SearchTeacherInsight(
                actionIndex = decision.decisionIndex,
                chosenLabel = decision.choice.display.label,
                chosenSignature = decision.choice.signature,
                candidates = search?.candidates?.sortedByDescending { it.visits }?.map { candidate ->
                    SearchTeacherCandidateInsight(
                        label = candidate.choice.display.label,
                        signature = candidate.choice.signature,
                        visits = candidate.visits,
                        meanValue = candidate.meanValue,
                        policyProbability = candidate.policyProbability,
                        chosen = candidate.choice.signature == decision.choice.signature,
                    )
                } ?: listOf(
                    SearchTeacherCandidateInsight(
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
    } catch (fatal: SearchTeacherControllerFailure) {
        throw fatal
    } catch (failure: Throwable) {
        val snapshot = context.snapshot()
        val code = when {
            failure.message?.startsWith("UNSUPPORTED_INFORMATION_STATE") == true -> "UNSUPPORTED_INFORMATION_STATE"
            failure.message?.startsWith("REPLAY_HISTORY_TRUNCATED") == true -> "REPLAY_HISTORY_TRUNCATED"
            failure.message?.startsWith("PERSISTENT_YIELD_HISTORY_UNSUPPORTED") == true ->
                "PERSISTENT_YIELD_HISTORY_UNSUPPORTED"
            failure.message?.contains("FINGERPRINT") == true -> "SYNCHRONIZATION_FAILURE"
            failure.message?.contains("Observed") == true -> "REDUCER_FAILURE"
            else -> "SEARCH_TEACHER_FAILURE"
        }
        publishInsight(
            SearchTeacherInsight(
                actionIndex = snapshot?.replayHistory?.actions?.size ?: synchronized?.actions?.size ?: 0,
                failureCode = code,
                diagnostic = (failure.message ?: failure::class.simpleName ?: "Search Teacher failure").take(500),
                authoritativeFingerprint = snapshot?.state?.let(ArgentumStateFingerprint::of),
                shadowFingerprint = synchronized?.runtime?.authoritativeFingerprint,
            )
        )
        throw SearchTeacherControllerFailure("$code: ${failure.message}", failure)
    }

    private fun <T> unsupported(kind: String): T = throw SearchTeacherControllerFailure(
        "UNSUPPORTED_GAME_TYPE: Search Teacher v1 does not support $kind"
    )

    private data class SynchronizedRuntime(
        val runtime: SearchTeacherRuntimeSession,
        val actions: List<com.wingedsheep.engine.core.GameAction>,
        val fingerprint: String,
    )

    private companion object {
        const val MULLIGAN_TURN_POLL_MILLIS = 25L
        const val MULLIGAN_TURN_WAIT_NANOS = 300_000_000_000L
    }
}
