package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.mtg.sets.tokens.PredefinedTokens
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumRawTransition
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.argentum.policy.SearchPolicySession

fun buildRegistry(): CardRegistry = CardRegistry().apply {
    register(PredefinedTokens.allTokens)
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
        set.basicLandsFallback?.let { register(it.basicLands) }
    }
}

/** Create a game with explicit known decks for player information and belief sampling. */
fun createWorld(
    config: GameConfig,
    knownDecks: Map<String, Map<String, Int>>,
    registry: CardRegistry = buildRegistry(),
    gameId: String = "game-${config.seed}",
    policySeed: Long = requireNotNull(config.seed) { "Supply a setup seed" },
    actionProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
): ArgentumSearchWorld {
    val environment = GameEnvironment.create(registry)
    environment.reset(config)
    return ArgentumSearchWorld.create(environment, gameId, policySeed,
        requireNotNull(config.seed), UnifiedSemanticExpander(actionSpaceProfile = actionProfile), knownDecks)
}

/** Policy callbacks receive a player-view decision, not privileged engine state. */
class Player(
    val view: DecisionView = DecisionView(),
    val observe: (String, SemanticChoice, SearchStepResult, Int) -> Unit = { _, _, _, _ -> },
    val choose: (DecisionSiteRequest, Long) -> SemanticChoice,
)

fun selectorPlayer(selector: ActionSelector): Player = Player(
    view = DecisionView(admission = if (selector.requiresProductionAdmission)
        DecisionAdmission.PRODUCTION else DecisionAdmission.SEMANTIC,
        annotations = selector.requiresPolicyAnnotations),
) { context, seed ->
    selector.select(context, seed, ComponentSeeds.derive(seed, "research", "sample")).choice
}

/** Keep the session accessible to the caller, including its explicit factual-continuation fork. */
fun searchPlayer(world: ArgentumSearchWorld, session: SearchPolicySession): Player = Player(
    view = session.decisionView,
    observe = { actor, choice, step, index ->
        session.observeAccepted(world, actor, choice, index, step.privateToActor)
    },
) { context, seed -> session.select(world, context.actor, seed).choice }

@Serializable
data class GameDecision(
    val index: Int,
    val information: InformationStateRepresentation,
    val rulesExhaustive: Boolean,
    val profileExhaustive: Boolean,
    val selectedIndex: Int,
    val accepted: Boolean,
    val decisionNanos: Long,
)

@Serializable
enum class GameStatus { TERMINAL, DECISION_LIMIT, TIME_LIMIT }

@Serializable
data class GameResult(val status: GameStatus, val decisions: Int, val payoffs: Map<String, Double>?)

/**
 * One real decision per iteration. A rejected transition, policy exception, or observer failure
 * propagates; it does not become a draw. Optional limits return no payoff for unfinished games.
 * beforeChoice and rawTrace are privileged research hooks, separate from the policy interface.
 * Limits and returned counts cover this call; records, policy seeds and observations use the
 * world's existing accepted-choice coordinate, including when continuing or branching a game.
 */
fun playGame(
    world: ArgentumSearchWorld,
    players: Map<String, Player>,
    policySeed: Long = 0,
    maximumDecisions: Int? = 2048,
    maximumSeconds: Double? = null,
    record: ((GameDecision) -> Unit)? = null,
    rawTrace: ((ArgentumRawTransition) -> Unit)? = null,
    beforeChoice: ((ArgentumSearchWorld, DecisionSiteRequest, Int) -> Unit)? = null,
    luckCorrection: LuckCorrection? = null,
): GameResult {
    require(luckCorrection == null || maximumSeconds == null) {
        "Luck correction does not support maximumSeconds; use a decision limit"
    }
    require(players.isNotEmpty())
    require(maximumDecisions == null || maximumDecisions >= 0)
    require(maximumSeconds == null || (maximumSeconds.isFinite() && maximumSeconds > 0))
    val started = System.nanoTime()
    val initialIndex = world.acceptedDecisionCountForHost
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Game interrupted")
        val index = world.acceptedDecisionCountForHost
        val completed = index - initialIndex
        if (world.terminalPayoff(players.keys.first()) != null) return GameResult(
            GameStatus.TERMINAL, completed, players.keys.associateWith { requireNotNull(world.terminalPayoff(it)) })
        if (maximumDecisions != null && completed >= maximumDecisions)
            return GameResult(GameStatus.DECISION_LIMIT, completed, null)
        if (maximumSeconds != null && (System.nanoTime() - started) / 1e9 >= maximumSeconds)
            return GameResult(GameStatus.TIME_LIMIT, completed, null)
        val actor = requireNotNull(world.actorToAct()) { "A nonterminal world has no actor" }
        val player = players.getValue(actor)
        val context = world.decisionContext(player.view)
        val site = context.site()
        check(site.epistemic.knowledge.epistemicallyComplete) {
            "Player information is incomplete: ${site.epistemic.knowledge.unsupportedReasons}"
        }
        beforeChoice?.invoke(world, context, index)
        val decisionStarted = System.nanoTime()
        val choice = player.choose(context, ComponentSeeds.derive(policySeed, actor, index.toString()))
        val nanos = System.nanoTime() - decisionStarted
        val selected = context.expansion.candidates.indexOf(choice)
        check(selected >= 0) { "Policy returned a choice outside its current decision menu" }
        val luckBefore = luckCorrection?.before(world)
        val trace = if (rawTrace != null || luckBefore != null) world.stepWithReplayTrace(choice) else null
        val step = trace?.result ?: world.step(choice)
        trace?.rawTransitions?.forEach { rawTrace?.invoke(it) }
        record?.invoke(GameDecision(index, site.information(),
            context.expansion.isExhaustive, context.expansion.isProfileExhaustive, selected, step.accepted, nanos))
        check(step.accepted) { "Engine rejected decision $index: ${step.diagnostic}" }
        if (luckBefore != null) luckCorrection.after(luckBefore, choice, world, requireNotNull(trace))
        players.values.forEach { it.observe(actor, choice, step, index) }
    }
}

/** Run independent jobs in parallel, preserving input order in the results. */
fun <T, R> parallelMap(values: List<T>, threads: Int = 1, block: (T) -> R): List<R> {
    require(threads > 0)
    if (threads == 1) return values.map(block)
    return Executors.newFixedThreadPool(threads).use { executor ->
        val tasks = values.map { value -> executor.submit(Callable { block(value) }) }
        try { tasks.map { it.get() } } finally { tasks.forEach { it.cancel(true) } }
    }
}
