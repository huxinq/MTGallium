package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.infoset.planning.RootActionSelection
import com.wingedsheep.engine.core.GameAction
import org.mtgallium.agent.infoset.argentum.ArgentumObservedStep
import org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.planning.LeafValueSource
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.value.MaterialEvaluator

/** A live selection resolved for the current engine state, not yet an accepted transition. */
class ResolvedPolicyDecision private constructor(
    val selection: RootActionSelection,
    val resolved: ArgentumResolvedChoice,
    val belief: BeliefDiagnostics,
    val latencyMillis: Double,
    val decisionIndex: Int,
) {
    val choice: SemanticChoice get() = selection.choice

    companion object {
        /** Resolution owns the action/payload pairing; callers cannot supply a second choice. */
        internal fun resolve(
            selection: RootActionSelection,
            world: ArgentumSearchWorld,
            belief: () -> BeliefDiagnostics,
            startedAtNanos: Long,
            decisionIndex: Int,
        ): ResolvedPolicyDecision {
            val resolved = world.resolveChoice(selection.choice)
            return ResolvedPolicyDecision(selection, resolved, belief(),
                (System.nanoTime() - startedAtNanos) / 1_000_000.0, decisionIndex)
        }
    }
}

/**
 * Stateful, perspective-safe policy for a live shadow world.
 *
 * The host owns reconstruction. This class owns only policy state: it consumes accepted raw
 * actions, advances the semantic history and particle belief, and resolves (but does not apply)
 * the next selected choice.
 */
class LivePolicySession(
    private val world: ArgentumSearchWorld,
    private val player: String,
    knownDecks: Map<String, Map<String, Int>>,
    private val gameId: String,
    private val config: SearchPolicyConfig = SearchPolicyConfig(),
    private val opponentModel: OpponentPolicy = defaultMonoRedOpponentPolicy(),
    private val valueSource: LeafValueSource = LeafValueSource.Information(MaterialEvaluator()),
) {
    private val parameters = config
    private val policy = SearchPolicySession(
        root = world,
        viewer = player,
        knownDecks = knownDecks,
        parameters = parameters,
        opponentPolicy = opponentModel,
        gameId = gameId,
        valueSource = valueSource,
    )
    private var decisionIndex = 0

    val appliedActions: Int get() = decisionIndex

    val stateFingerprint: String get() = world.stateFingerprint()
    val canChoose: Boolean get() = world.actorToAct() == player
    val lastObservedUpdate: ObservedBeliefUpdateEvidence? get() = policy.lastObservedUpdate

    fun applyObserved(action: GameAction): ArgentumObservedStep {
        val actor = requireNotNull(world.actorToAct()) { "Observed action after terminal state" }
        val observed = world.applyObservedAction(action)
        check(observed.result.accepted) { observed.result.diagnostic ?: "Observed semantic action was rejected" }
        policy.observeAccepted(
            actual = world,
            actor = actor,
            choice = observed.choice,
            decisionIndex = decisionIndex,
            privateToActor = observed.result.privateToActor,
        )
        decisionIndex++
        return observed
    }

    fun choose(): ResolvedPolicyDecision {
        check(canChoose) {
            "Search policy $player cannot choose for ${world.actorToAct()}"
        }
        val started = System.nanoTime()
        val selection = policy.select(
            world = world,
            actor = player,
            searchSeed = ComponentSeeds.derive(gameId, decisionIndex, config.baseSeed, "live-search"),
        )
        return ResolvedPolicyDecision.resolve(
            selection = selection,
            world = world,
            belief = { policy.latestBeliefDiagnostics },
            startedAtNanos = started,
            decisionIndex = decisionIndex,
        )
    }
}
