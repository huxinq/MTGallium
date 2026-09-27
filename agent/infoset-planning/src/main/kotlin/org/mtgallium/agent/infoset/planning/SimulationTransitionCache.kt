package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

private const val MAX_ROLLOUT_CACHE_SNAPSHOTS = 4096

/** Exact per-root-particle prefix cache; keys are semantic paths, never lossy state hashes. */
internal class SimulationTransitionCache(private val counters: SearchCounters) {
    private val roots = mutableMapOf<Int, SimulationTransitionNode>()
    private var rolloutSnapshots = 0

    fun root(particleIndex: Int): SimulationTransitionNode =
        roots.getOrPut(particleIndex, ::SimulationTransitionNode)

    /**
     * The edge snapshot is taken immediately after a step, before the child is projected.
     * Replace it once with a fork carrying the exact wrapper's derived caches so later prefix
     * hits reuse those computations as well as the engine state.
     */
    fun retainDerived(node: SimulationTransitionNode?, world: SearchWorld, features: Int) {
        if (node == null) return
        if (node.snapshot == null) {
            counters.transitionCacheSnapshots++
            node.snapshot = world.fork()
        }
        if (node.derivedFeatures and features == features) return
        counters.transitionCacheDerivedSnapshots++
        val transferred = (node.snapshot as? DerivedCacheTransferSearchWorld)
            ?.copyDerivedCachesFrom(world) == true
        if (!transferred) node.snapshot = world.fork()
        node.derivedFeatures = node.derivedFeatures or features
    }

    fun annotatedContext(node: SimulationTransitionNode?, compute: () -> DecisionContext): DecisionContext {
        if (node?.snapshot == null) return compute()
        node.annotatedContext?.let {
            counters.policyAnnotationCacheHits++
            return it
        }
        counters.policyAnnotationCacheMisses++
        return compute().also { node.annotatedContext = it }
    }

    fun opponentDistribution(
        node: SimulationTransitionNode?,
        compute: () -> ProbabilityDistribution<SemanticChoice>,
    ): ProbabilityDistribution<SemanticChoice> {
        if (node?.snapshot == null) return compute()
        node.opponentDistribution?.let {
            counters.opponentDistributionCacheHits++
            return it
        }
        counters.opponentDistributionCacheMisses++
        return compute().also { node.opponentDistribution = it }
    }

    fun advance(world: SearchWorld, choice: SemanticChoice, node: SimulationTransitionNode, rollout: Boolean): CachedAdvance {
        node.children[choice.signature]?.let { cached ->
            counters.transitionCacheHits++
            if (rollout) counters.rolloutTransitionCacheHits++
            return CachedAdvance(requireNotNull(cached.snapshot).fork(), cached)
        }
        counters.transitionCacheMisses++
        counters.steps++
        val result = world.step(choice)
        if (!result.accepted) {
            counters.rejectedTransitions++
            throw RejectedSearchTransitionException(choice.signature, result.diagnostic)
        }
        // Rollout branching can retain far more snapshots than tree traversal. A full cache
        // falls back to ordinary stepping; existing exact-prefix hits remain usable.
        if (rollout && rolloutSnapshots >= MAX_ROLLOUT_CACHE_SNAPSHOTS) {
            counters.rolloutTransitionCacheBypasses++
            return CachedAdvance(world, null)
        }
        if (rollout) {
            rolloutSnapshots++
            counters.rolloutTransitionCacheSnapshots++
        }
        counters.transitionCacheSnapshots++
        val child = SimulationTransitionNode(world.fork())
        node.children[choice.signature] = child
        return CachedAdvance(world, child)
    }
}

internal class SimulationTransitionNode(var snapshot: SearchWorld? = null) {
    val children = mutableMapOf<String, SimulationTransitionNode>()
    var derivedFeatures: Int = 0
    var annotatedContext: DecisionContext? = null
    var opponentDistribution: ProbabilityDistribution<SemanticChoice>? = null
}

internal data class CachedAdvance(val world: SearchWorld, val node: SimulationTransitionNode?)
