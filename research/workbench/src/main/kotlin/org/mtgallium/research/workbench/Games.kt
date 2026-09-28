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
import org.mtgallium.agent.infoset.argentum.ArgentumActionGenerator
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.infoset.planning.*
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
    actionProfile: ActionSpaceProfile = ActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
): ArgentumSearchWorld {
    val environment = GameEnvironment.create(registry)
    environment.reset(config)
    return ArgentumSearchWorld.create(environment, gameId, policySeed,
        requireNotNull(config.seed), ArgentumActionGenerator(actionSpaceProfile = actionProfile), knownDecks)
}

/** Policy callbacks receive a player-view decision, not privileged engine state. */
class GameAgent(
    val view: MenuRequest = MenuRequest(),
    val observe: (String, SemanticChoice, SearchStepResult, Int) -> Unit = { _, _, _, _ -> },
    /** Set for a player at an Argentum seat, which acts through its seat instead of [choose]. */
    val seat: SeatAgent? = null,
    val choose: (DecisionContext, Long) -> SemanticChoice,
)

fun seatPlayer(agent: SeatAgent): GameAgent =
    GameAgent(seat = agent) { _, _ -> error("A seat agent acts through its seat") }

fun selectorPlayer(selector: ActionSelector): GameAgent = GameAgent(
    view = MenuRequest(admission = if (selector.requiresArgentumAiChoiceOnMenu)
        MenuSource.PRODUCTION else MenuSource.SEMANTIC,
        annotations = selector.requiresArgentumAiChoiceTag),
) { context, seed ->
    selector.select(context, seed, ComponentSeeds.derive(seed, "research", "sample")).choice
}

/** Keep the session accessible to the caller, including its explicit factual-continuation fork. */
fun searchPlayer(world: ArgentumSearchWorld, session: SearchPolicySession): GameAgent = GameAgent(
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
    players: Map<String, GameAgent>,
    policySeed: Long = 0,
    maximumDecisions: Int? = 2048,
    maximumSeconds: Double? = null,
    record: ((GameDecision) -> Unit)? = null,
    rawTrace: ((ArgentumRawTransition) -> Unit)? = null,
    beforeChoice: ((ArgentumSearchWorld, DecisionContext, Int) -> Unit)? = null,
    luckCorrection: ChanceControlVariate? = null,
    seats: SeatHost? = null,
): GameResult {
    require(luckCorrection == null || maximumSeconds == null) {
        "Luck correction does not support maximumSeconds; use a decision limit"
    }
    require(players.isNotEmpty())
    require(maximumDecisions == null || maximumDecisions >= 0)
    require(maximumSeconds == null || (maximumSeconds.isFinite() && maximumSeconds > 0))
    val started = System.nanoTime()
    val initialIndex = world.acceptedDecisionCount
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Game interrupted")
        val index = world.acceptedDecisionCount
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
        check(site.epistemic.knowledge.isComplete) {
            "Player information is incomplete: ${site.epistemic.knowledge.unsupportedReasons}"
        }
        seats?.beforeDecision(index, actor, context.menu.candidates.size)
        beforeChoice?.invoke(world, context, index)
        if (player.seat != null) {
            // The seat acts through its host, which has already applied the action for both seats.
            val host = requireNotNull(seats) { "A seat agent needs a seat host" }
            require(luckCorrection == null) { "Luck correction does not support seat agents" }
            val decisionStarted = System.nanoTime()
            val action = host.act(actor, world.trueState())
            val nanos = System.nanoTime() - decisionStarted
            val transitions = mutableListOf<ArgentumRawTransition>()
            val observed = world.applyObservedAction(action, transitions)
            host.checkFollowed(world.trueState())
            transitions.forEach { rawTrace?.invoke(it) }
            val step = observed.result
            record?.invoke(GameDecision(index, site.information(), context.menu.isExhaustive, context.menu.isProfileExhaustive,
                context.menu.candidates.indexOf(observed.choice), step.accepted, nanos))
            check(step.accepted) { "Engine rejected seat action $index: ${step.diagnostic}" }
            players.values.forEach { it.observe(actor, observed.choice, step, index) }
            continue
        }
        val decisionStarted = System.nanoTime()
        val choice = player.choose(context, ComponentSeeds.derive(policySeed, actor, index.toString()))
        val nanos = System.nanoTime() - decisionStarted
        val selected = context.menu.candidates.indexOf(choice)
        check(selected >= 0) {
            "Policy returned a choice outside its current decision menu: actor=$actor decision=$index " +
                "view=${player.view} candidates=${context.menu.candidates.size} choice=$choice"
        }
        val luckBefore = luckCorrection?.before(world)
        val trace = if (rawTrace != null || luckBefore != null || seats != null) world.stepWithReplayTrace(choice) else null
        val step = trace?.result ?: world.step(choice)
        trace?.rawTransitions?.forEach { seats?.accept(it); rawTrace?.invoke(it) }
        record?.invoke(GameDecision(index, site.information(),
            context.menu.isExhaustive, context.menu.isProfileExhaustive, selected, step.accepted, nanos))
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
