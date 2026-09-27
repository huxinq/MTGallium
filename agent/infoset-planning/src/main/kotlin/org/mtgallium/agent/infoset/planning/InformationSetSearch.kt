package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

import kotlin.math.ln
import kotlin.math.sqrt

/** History-keyed, shared-tree information-set Monte Carlo search. */
class InformationSetSearch(
    private val config: InformationSetSearchConfig,
    private val opponentPolicy: ActionDistributionModel,
    private val rolloutPolicy: ActionSelector,
    private val rolloutOpponentPolicy: ActionSelector,
    private val valueSource: LeafValueSource,
    private val searchPrior: SearchPrior? = null,
) {
    init {
        searchPrior?.let {
            require(it.configurationId.isNotBlank() && it.candidateLimit >= config.initialExpansionLimit)
            require(it.explorationConstant.isFinite() && it.explorationConstant >= 0.0)
        }
        listOf(opponentPolicy, rolloutPolicy, rolloutOpponentPolicy).forEach { policy ->
            require(!policy.requiresArgentumAiChoiceTag || policy.requiresArgentumAiChoiceOnMenu) {
                "Policy annotations require production admission"
            }
        }
        require(
            config.leaf.stateSource != LeafEvaluationMethod.CURRENT_INFORMATION_STATE ||
                valueSource is LeafValueSource.Information
        ) {
            "A current-information-state leaf requires an information-state evaluator"
        }
    }

    fun search(
        rootPlayer: String,
        belief: ParticleSet<Weighted<SearchWorld>>,
        searchSeed: Long,
        simulationWorldSchedule: SimulationWorldSchedule? = null,
    ): InformationSetSearchResult {
        require(belief.particles.isNotEmpty())
        require(simulationWorldSchedule == null || simulationWorldSchedule.worlds.size == config.simulations) {
            "A fixed simulation-world schedule must contain exactly ${config.simulations} worlds"
        }
        requireConformantRoot(rootPlayer, belief.particles.map { it.value }, "Root particle")
        val rolloutTargetTurn = config.rolloutTurnHorizon?.let { horizon ->
            Math.addExact(belief.particles.first().value.informationStateWithoutMenu(rootPlayer)
                .observation.turnNumber, horizon.completedTurns)
        }
        simulationWorldSchedule?.let { requireConformantRoot(rootPlayer, it.worlds, "Scheduled root world") }
        val rootParticleIndices = productionRootParticleIndices(
            belief.particles.map { it.weight },
            searchSeed,
            config.simulations,
        )
        val run = SearchRun(rootPlayer, searchSeed, rolloutTargetTurn)
        for (simulationIndex in 0 until config.simulations) {
            val particleIndex = rootParticleIndices[simulationIndex]
            val world = (
                simulationWorldSchedule?.worlds?.get(simulationIndex)
                    ?: belief.particles[particleIndex].value
                ).fork()
            run.simulationIndex = simulationIndex
            val outcome = run.simulate(
                world,
                depth = 0,
                node = run.cache.root(if (simulationWorldSchedule == null) particleIndex else simulationIndex),
                quiescenceMode = false,
                quiescenceDepth = 0,
            )
            require(outcome.backedValue.isFinite()) { "Search produced a non-finite value" }
        }
        return run.result(belief)
    }

    /** Per-search state: the shared tree, the prefix cache and the counters behind the diagnostics. */
    private inner class SearchRun(
        val rootPlayer: String,
        val searchSeed: Long,
        val rolloutTargetTurn: Int?,
    ) {
        val tree = linkedMapOf<TreeNodeKey, SearchNode>()
        val counters = SearchCounters()
        val cache = SimulationTransitionCache(counters)
        var simulationIndex = 0
        var maximumDepth = 0
        var wideningEvents = 0

        fun simulate(
            world: SearchWorld,
            depth: Int,
            node: SimulationTransitionNode?,
            quiescenceMode: Boolean,
            quiescenceDepth: Int,
        ): SimulationReturn {
            maximumDepth = maxOf(maximumDepth, depth)
            world.terminalPayoff(rootPlayer)?.let {
                return SimulationReturn(it, ReturnSource.TERMINAL_PAYOFF)
            }
            if (rolloutTargetTurn != null &&
                world.informationStateWithoutMenu(rootPlayer).observation.turnNumber >= rolloutTargetTurn
            ) {
                return staticLeafValue(world)
            }
            if (quiescenceMode) {
                when (val settled = evaluateStaticLeaf(world)) {
                    is StaticLeafSettlement.Value -> return settled.settlement
                    StaticLeafSettlement.VolatileBranch -> if (quiescenceDepth >= config.maxQuiescenceDecisions) {
                        counters.overflows++
                        counters.fallbacks++
                        return staticLeafValue(world)
                    }
                }
            } else if (depth >= config.maxPolicyDecisions) {
                return leafValue(world, depth, node)
            }

            val initialContext = world.actorToAct()?.let {
                if (it == rootPlayer) initialDecision(world)
                else world.decisionContext(MenuRequest(config.initialExpansionLimit))
            }
            fun undecidedLeaf(): SimulationReturn {
                cache.retainDerived(node, world, DERIVED_BASE)
                if (quiescenceMode) counters.fallbacks++
                return if (quiescenceMode) staticLeafValue(world) else leafValue(world, depth, node)
            }
            val menu = initialContext?.menu ?: initialExpansion(world)
            if (menu.candidates.isEmpty()) return undecidedLeaf()
            val actor = world.actorToAct() ?: return undecidedLeaf()

            if (quiescenceMode) counters.strategicDecisions++

            // The configured opponent is part of the stochastic environment. Its private actions may
            // legitimately differ between sampled worlds, so they must not become edges in the root
            // player's shared information tree.
            if (actor != rootPlayer) {
                val opponentContext = if (world is PolicyAnnotatedSearchWorld && opponentPolicy.requiresArgentumAiChoiceTag) {
                    counters.policyAnnotatedExpansions++
                    cache.annotatedContext(node) {
                        world.decisionContext(opponentPolicy.decisionView(config.initialExpansionLimit))
                    }
                } else {
                    world.decisionContext(opponentPolicy.decisionView(config.initialExpansionLimit))
                }
                val policySeed = ComponentSeeds.derive(searchSeed, simulationIndex, depth, "opponent")
                fun computeDistribution(): ProbabilityDistribution<SemanticChoice> {
                    cache.retainDerived(node, world, DERIVED_BASE or DERIVED_POLICY_EXPANSION or DERIVED_INFORMATION)
                    return opponentPolicy.distribution(opponentContext, policySeed)
                        .requireAdmittedSupport(opponentContext.menu.candidates)
                }
                val distribution = if (opponentPolicy.distributionIsSeedInvariant) {
                    cache.opponentDistribution(node, ::computeDistribution)
                } else {
                    computeDistribution()
                }
                val selected = sampleOpponentPolicyDistribution(
                    distribution,
                    ComponentSeeds.derive(searchSeed, simulationIndex, depth, "opponent-sample"),
                ).requireAdmittedChoice(opponentContext.menu.candidates)
                counters.opponentModel.record(
                    opponentPolicy.decisionDiagnostic(
                        context = opponentContext,
                        chosen = selected,
                        policySeed = policySeed,
                        attributionSeed = ComponentSeeds.derive(
                            searchSeed, simulationIndex, depth, "opponent-component-attribution",
                        ),
                    )
                )
                val advanced = advance(world, selected, node)
                return simulate(
                    advanced.world, depth + 1, advanced.node, quiescenceMode,
                    if (quiescenceMode) quiescenceDepth + 1 else quiescenceDepth,
                )
            }

            // Root-player choices alone form the shared information tree.
            val rootContext = requireNotNull(initialContext)
            val key = rootContext.planningKey()
            cache.retainDerived(node, world, DERIVED_BASE or DERIVED_INFORMATION)
            val treeNode = tree.getOrPut(key) {
                SearchNode(rootContext, config.initialExpansionLimit).also { applyPrior(it, rootContext) }
            }
            treeNode.requireCompatible(rootContext)
            if (searchPrior == null && treeNode.expansionLimit > config.initialExpansionLimit && world is ProgressiveSearchWorld) {
                treeNode.merge(world.decisionContext(MenuRequest(treeNode.expansionLimit)))
            }
            val wideningIndex = config.wideningThresholds.indexOfLast { treeNode.visits >= it }
            // A profile-exhaustive menu is complete for this action space; a higher limit adds nothing.
            if (searchPrior == null && wideningIndex >= 0 && !treeNode.profileExhaustive && world is ProgressiveSearchWorld) {
                val desired = config.wideningLimits[wideningIndex]
                if (desired > treeNode.expansionLimit) {
                    val widened = world.decisionContext(MenuRequest(desired))
                    treeNode.expansionLimit = desired
                    treeNode.merge(widened)
                    wideningEvents++
                }
            }

            val edge = selectEdge(treeNode, depth)
            val advanced = advance(world, edge.choice, node)
            val value = if (quiescenceMode) {
                simulate(advanced.world, depth + 1, null, quiescenceMode = true, quiescenceDepth + 1)
            } else if (edge.visits == 0) {
                leafValue(advanced.world, depth + 1, advanced.node)
            } else {
                simulate(advanced.world, depth + 1, advanced.node, quiescenceMode = false, quiescenceDepth = 0)
            }
            treeNode.visits++
            edge.record(value)
            return value
        }

        private fun applyPrior(node: SearchNode, context: DecisionContext) {
            val prior = searchPrior ?: return
            val probabilities = prior.probabilities(context)
            require(probabilities.keys == node.edges.keys)
            require(probabilities.values.all { it.isFinite() && it >= 0.0 })
            val total = probabilities.values.sum()
            require(total.isFinite() && total > 0.0)
            val retained = node.edges.values.sortedWith(compareByDescending<SearchEdge> {
                probabilities.getValue(it.choice.signature)
            }.thenBy { it.choice.signature }).take(config.initialExpansionLimit)
            if (retained.size < node.edges.size) {
                node.exhaustive = false
                node.profileExhaustive = false
            }
            node.edges.clear()
            val retainedTotal = retained.sumOf { probabilities.getValue(it.choice.signature) }
            for (edge in retained) {
                edge.prior = probabilities.getValue(edge.choice.signature) / retainedTotal
                node.edges[edge.choice.signature] = edge
            }
        }

        private fun leafValue(world: SearchWorld, depth: Int, node: SimulationTransitionNode?): SimulationReturn =
            when (config.leaf.stateSource) {
                LeafEvaluationMethod.CURRENT_INFORMATION_STATE ->
                    simulate(world, depth, null, quiescenceMode = true, quiescenceDepth = 0)
                LeafEvaluationMethod.BOUNDED_ROLLOUT -> rollout(world, depth, node)
            }

        /**
         * Horizon quiescence advances an exact singleton priority pass and, under
         * [QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1], a profile-exhaustive singleton pass
         * in a volatile position. A quiet state is one for which [isQuiet] is true: no stack, no
         * combat except END_COMBAT, no pending Combat, Damage, or Order decision, and no
         * lethal-damage battlefield creature. It need not have no candidates. Thus this method never
         * consumes a genuine branching decision, a singleton mana ability, or another strategic
         * action while seeking a state to evaluate.
         */
        private fun evaluateStaticLeaf(
            world: SearchWorld,
            maximumForcedPasses: Int = config.maxQuiescenceForcedPasses,
        ): StaticLeafSettlement {
            var forcedPasses = 0
            while (true) {
                world.terminalPayoff(rootPlayer)?.let {
                    return StaticLeafSettlement.Value(SimulationReturn(it, ReturnSource.TERMINAL_PAYOFF))
                }
                val menu = initialExpansion(world)
                val profilePass = if (menu.exactSingletonPassOrNull() == null &&
                    config.leaf.quiescencePasses == QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1) {
                    menu.policySingletonPassOrNull()
                        ?.takeIf { !isQuiet(world.informationState(rootPlayer).observation) }
                } else null
                val pass = menu.exactSingletonPassOrNull() ?: profilePass
                if (pass != null) {
                    if (forcedPasses >= maximumForcedPasses) {
                        counters.overflows++
                        counters.fallbacks++
                        return StaticLeafSettlement.Value(staticLeafValue(world))
                    }
                    step(world, pass)
                    forcedPasses++
                    counters.forcedPasses++
                    if (profilePass != null) counters.profileForcedPasses++
                    continue
                }
                if (isQuiet(world.informationState(rootPlayer).observation)) {
                    return StaticLeafSettlement.Value(staticLeafValue(world))
                }
                return if (menu.candidates.isNotEmpty() && world.actorToAct() != null) {
                    StaticLeafSettlement.VolatileBranch
                } else {
                    counters.fallbacks++
                    StaticLeafSettlement.Value(staticLeafValue(world))
                }
            }
        }

        private fun staticLeafValue(world: SearchWorld): SimulationReturn {
            world.terminalPayoff(rootPlayer)?.let {
                return SimulationReturn(it, ReturnSource.TERMINAL_PAYOFF)
            }
            val started = System.nanoTime()
            val information = world.informationState(rootPlayer)
            val (rawValue, origin) = when (val source = valueSource) {
                is LeafValueSource.Information ->
                    source.evaluator.evaluate(information, rootPlayer) to source.evaluator.settlementOrigin
            }
            require(rawValue.isFinite()) { "Leaf evaluator returned a non-finite score: $rawValue" }
            val value = rawValue.coerceIn(-1.0, 1.0)
            counters.recordEvaluator(value, System.nanoTime() - started, !isQuiet(information.observation))
            return SimulationReturn(value, origin)
        }

        private fun rollout(
            startingWorld: SearchWorld,
            startingDepth: Int,
            startingNode: SimulationTransitionNode?,
        ): SimulationReturn {
            require((rolloutTargetTurn != null) == (config.rolloutTurnHorizon != null))
            var world = startingWorld.fork()
            var node = startingNode
            var depth = startingDepth
            var rolloutDecisions = 0
            while (rolloutTargetTurn != null || depth < config.maxPolicyDecisions) {
                world.terminalPayoff(rootPlayer)?.let {
                    return SimulationReturn(it, ReturnSource.TERMINAL_PAYOFF)
                }
                if (rolloutTargetTurn != null) {
                    if (world.informationStateWithoutMenu(rootPlayer).observation.turnNumber >= rolloutTargetTurn) {
                        // The old turn's cleanup is complete. Leave the new turn's first genuine
                        // choice untouched, including any upkeep trigger/response it presents.
                        return staticLeafValue(world)
                    }
                    if (rolloutDecisions >= requireNotNull(config.rolloutTurnHorizon).maxPolicyDecisions) {
                        throw RolloutTurnHorizonException(
                            RolloutTurnHorizonFailure.DECISION_LIMIT, rolloutTargetTurn, rolloutDecisions,
                        )
                    }
                }
                val menu = initialExpansion(world)
                if (menu.candidates.isEmpty()) break
                val actor = world.actorToAct() ?: break
                // The outer opponent remains an independently configurable stochastic environment.
                val basePolicy = if (actor == rootPlayer) rolloutPolicy else rolloutOpponentPolicy
                val policy = (basePolicy as? RolloutPolicySchedule)?.atStep(rolloutDecisions) ?: basePolicy
                val policyContext = initialPolicyContext(world, policy)
                val decision = policy.select(
                    context = policyContext,
                    policySeed = ComponentSeeds.derive(searchSeed, simulationIndex, depth, policy.id, "rollout"),
                    sampleSeed = ComponentSeeds.derive(searchSeed, simulationIndex, depth, "rollout-sample"),
                )
                decision.choice.requireAdmittedChoice(policyContext.menu.candidates)
                val informationUsed = policyContext.informationDemanded
                counters.recordRollout(actor == rootPlayer, decision.diagnostic)
                (policy as? RolloutObserver)?.observeDecision(
                    policyContext, decision.choice, searchSeed, simulationIndex, depth)
                // Cache exact world prefixes, never the rollout policy's distribution or sampled choice.
                // The same node may later enter the tree with a different opponent policy or wider menu.
                cache.retainDerived(
                    node, world,
                    DERIVED_BASE or DERIVED_POLICY_EXPANSION or
                        (if (informationUsed) DERIVED_INFORMATION else 0),
                )
                val advanced = advance(world, decision.choice, node, rollout = true)
                world = advanced.world
                node = advanced.node
                depth++
                rolloutDecisions++
            }
            world.terminalPayoff(rootPlayer)?.let {
                return SimulationReturn(it, ReturnSource.TERMINAL_PAYOFF)
            }
            if (rolloutTargetTurn != null) {
                throw RolloutTurnHorizonException(
                    RolloutTurnHorizonFailure.MISSING_DECISION, rolloutTargetTurn, rolloutDecisions,
                )
            }
            return when (config.leaf.cutoff) {
                RolloutCutoff.EVALUATE -> staticLeafValue(world)
                RolloutCutoff.POLICY_QUIESCENCE -> evaluateWithRolloutPolicies(world, depth)
                RolloutCutoff.QUIESCENCE -> when (val settled = evaluateStaticLeaf(world)) {
                    is StaticLeafSettlement.Value -> settled.settlement
                    StaticLeafSettlement.VolatileBranch -> {
                        counters.fallbacks++
                        staticLeafValue(world)
                    }
                }
            }
        }

        /**
         * An opt-in continuation of the declared rollout policies, not forced-action compression.
         * Every volatile branching decision (including targets, blockers and ordering) is selected
         * by its acting player's rollout policy and charged to both policy and quiescence counters.
         * The original pass-only settlement route remains unchanged. Exhaustion still produces an
         * explicitly heuristic fallback; it never supplies a terminal payoff.
         */
        private fun evaluateWithRolloutPolicies(world: SearchWorld, rolloutDepth: Int): SimulationReturn {
            val initialForcedPasses = counters.forcedPasses
            var decisions = 0
            while (true) {
                val remainingPasses = config.maxQuiescenceForcedPasses - (counters.forcedPasses - initialForcedPasses)
                when (val settled = evaluateStaticLeaf(world, remainingPasses)) {
                    is StaticLeafSettlement.Value -> return settled.settlement
                    StaticLeafSettlement.VolatileBranch -> Unit
                }
                if (decisions >= config.maxQuiescenceDecisions) {
                    counters.overflows++
                    counters.fallbacks++
                    return staticLeafValue(world)
                }
                val actor = checkNotNull(world.actorToAct())
                val policy = if (actor == rootPlayer) rolloutPolicy else rolloutOpponentPolicy
                val policyContext = initialPolicyContext(world, policy)
                val decision = policy.select(
                    context = policyContext,
                    policySeed = ComponentSeeds.derive(
                        searchSeed, simulationIndex, rolloutDepth, decisions, policy.id, "rollout-quiescence",
                    ),
                    sampleSeed = ComponentSeeds.derive(
                        searchSeed, simulationIndex, rolloutDepth, decisions, "rollout-quiescence-sample",
                    ),
                )
                decision.choice.requireAdmittedChoice(policyContext.menu.candidates)
                counters.recordRollout(actor == rootPlayer, decision.diagnostic)
                counters.strategicDecisions++
                step(world, decision.choice)
                decisions++
            }
        }

        private fun initialPolicyContext(world: SearchWorld, policy: PolicyComponent): DecisionContext {
            if (world is PolicyAnnotatedSearchWorld && policy.requiresArgentumAiChoiceTag) counters.policyAnnotatedExpansions++
            return world.decisionContext(policy.decisionView(config.initialExpansionLimit))
        }

        private fun step(world: SearchWorld, choice: SemanticChoice) {
            counters.steps++
            val result = world.step(choice)
            if (!result.accepted) {
                counters.rejectedTransitions++
                throw RejectedSearchTransitionException(choice.signature, result.diagnostic)
            }
        }

        private fun advance(
            world: SearchWorld,
            choice: SemanticChoice,
            node: SimulationTransitionNode?,
            rollout: Boolean = false,
        ): CachedAdvance {
            if (node != null) return cache.advance(world, choice, node, rollout)
            step(world, choice)
            return CachedAdvance(world, null)
        }

        private fun selectEdge(node: SearchNode, depth: Int): SearchEdge {
            searchPrior?.let { prior ->
                return node.edges.values.maxWith(compareBy<SearchEdge> {
                    it.meanValue() + prior.explorationConstant * it.prior * sqrt(node.visits + 1.0) / (1.0 + it.visits)
                }.thenByDescending { it.choice.signature })
            }
            val unvisited = node.edges.values.filter { it.visits == 0 }
            if (unvisited.isNotEmpty()) {
                return unvisited.minBy { edge ->
                    CanonicalJson.sha256("$searchSeed:$simulationIndex:$depth:${edge.choice.signature}")
                }
            }
            val logParent = ln((node.visits + 1).toDouble())
            return node.edges.values.maxWith(
                compareBy<SearchEdge> { edge ->
                    edge.meanValue() + config.explorationConstant * sqrt(logParent / edge.visits)
                }.thenByDescending { it.choice.signature }
            )
        }

        fun result(belief: ParticleSet<Weighted<SearchWorld>>): InformationSetSearchResult {
            val representative = belief.particles.first().value
            val rootKey = if (representative.actorToAct() == rootPlayer) {
                initialDecision(representative).planningKey()
            } else {
                // Observer-only compatibility lookup; no acting-player decision is assembled here.
                TreeNodeKey(representative.informationState(rootPlayer).informationStateDigest, representative.actorToAct())
            }
            val root = tree[rootKey] ?: error("Search never expanded the root information state")
            val totalVisits = root.edges.values.sumOf { it.visits }.coerceAtLeast(1)
            val statistics = root.edges.values.sortedBy { it.choice.signature }.map { edge ->
                RootActionStatistics(
                    choice = edge.choice,
                    visits = edge.visits,
                    meanValue = edge.meanValue(),
                    visitFraction = edge.visits.toDouble() / totalVisits,
                )
            }
            val chosen = statistics.mostVisitedActionOrNull() ?: error("Root has no candidate edges")
            val visited = statistics.filter { it.visits > 0 }
            val rollouts = config.leaf.stateSource == LeafEvaluationMethod.BOUNDED_ROLLOUT
            return InformationSetSearchResult(
                chosen = chosen.choice,
                rootValue = if (visited.isEmpty()) 0.0 else {
                    visited.sumOf { it.meanValue * it.visits } / visited.sumOf { it.visits }
                },
                candidates = statistics,
                candidateSettlementCounts = root.edges.mapValues { (_, edge) -> edge.settlementCounts },
                diagnostics = counters.diagnostics(
                    simulations = config.simulations,
                    particles = belief.particles.size,
                    nodes = tree.size,
                    maximumDepth = maximumDepth,
                    exhaustiveNodes = tree.values.count { it.exhaustive },
                    wideningEvents = wideningEvents,
                    opponentModelId = opponentPolicy.id,
                    leaf = config.leaf.diagnostic(),
                    rootRolloutPolicyId = rolloutPolicy.id.takeIf { rollouts },
                    opponentRolloutPolicyId = rolloutOpponentPolicy.id.takeIf { rollouts },
                    evaluatorId = valueSource.invokedEvaluatorId,
                    evaluatorConfigurationId = valueSource.invokedEvaluatorConfigurationId,
                ),
            )
        }
    }

    private sealed interface StaticLeafSettlement {
        data class Value(val settlement: SimulationReturn) : StaticLeafSettlement
        data object VolatileBranch : StaticLeafSettlement
    }

    private fun isQuiet(observation: PlayerObservationSnapshot): Boolean {
        if (observation.stack.isNotEmpty()) return false
        if (observation.phase == "COMBAT" && observation.step != "END_COMBAT") return false
        val pendingKind = observation.pendingDecision?.decisionKind.orEmpty()
        if (listOf("Combat", "Damage", "Order").any(pendingKind::contains)) return false
        return observation.zones.asSequence().flatMap { it.cards.asSequence() }.none { card ->
            card.zone == "BATTLEFIELD" && card.toughness?.let { card.damageMarked >= it } == true
        }
    }

    private fun initialDecision(world: SearchWorld): DecisionContext =
        world.decisionContext(MenuRequest(searchPrior?.candidateLimit ?: config.initialExpansionLimit,
            admission = searchPrior?.admission ?: MenuSource.SEMANTIC))

    private fun initialExpansion(world: SearchWorld): ActionMenu =
        world.initialPolicyChoices(config.initialExpansionLimit)

    private data class RootDescriptor(
        val informationStateDigest: String,
        val actor: String?,
        val proposalVersion: String,
        val proposalSeed: Long,
        val candidateSignatures: Set<String>,
    )

    private fun rootDescriptor(world: SearchWorld, rootPlayer: String): RootDescriptor {
        val context = world.actorToAct()?.let { initialDecision(world) }
        val menu = context?.menu ?: initialExpansion(world)
        val information = if (context?.actor == rootPlayer) context.information() else world.informationState(rootPlayer)
        return RootDescriptor(
            informationStateDigest = information.informationStateDigest,
            actor = context?.actor,
            proposalVersion = menu.proposalVersion,
            proposalSeed = menu.proposalSeed,
            candidateSignatures = menu.candidates.mapTo(linkedSetOf(), SemanticChoice::signature),
        )
    }

    private fun requireConformantRoot(rootPlayer: String, worlds: List<SearchWorld>, subject: String) {
        // Prepare the retained particles themselves so their exact forks inherit the verified expansion.
        val contracts = worlds.map { rootDescriptor(it, rootPlayer) }
        val representative = contracts.first()
        val mismatch = contracts.indexOfFirst { it != representative }
        if (mismatch >= 0) {
            throw InformationSetConformanceException(
                "$subject $mismatch disagrees with ${subject.lowercase()} 0 about policy-visible state or semantic candidates"
            )
        }
        // Validate complete root decision contracts before sampling any particle. Observer-only
        // roots keep their separate legacy descriptor path and do not compare opponent knowledge.
        if (worlds.all { it.actorToAct() == rootPlayer }) {
            val first = PlanningDecisionContext(initialDecision(worlds.first()))
            worlds.drop(1).forEach { first.requireInitial(initialDecision(it)) }
        }
    }

    companion object {
        /**
         * Reproduces the ordinary per-simulation root-particle draws without starting search.
         * This is an offline control seam for experiments which replace only the future-chance
         * stream of the selected complete world. Production search uses the same implementation.
         */
        fun productionRootParticleIndices(
            weights: List<Double>,
            searchSeed: Long,
            simulations: Int,
        ): List<Int> {
            require(simulations > 0)
            require(weights.all { it.isFinite() && it >= 0.0 })
            val total = weights.sum()
            require(total > 0.0)
            val normalized = weights.map { it / total }
            return List(simulations) { simulationIndex ->
                val target = SplitMix64(ComponentSeeds.derive(searchSeed, simulationIndex, "root-particle")).nextDouble()
                var cumulative = 0.0
                var chosen = normalized.lastIndex
                for (index in normalized.indices) {
                    cumulative += normalized[index]
                    if (target < cumulative) {
                        chosen = index
                        break
                    }
                }
                chosen
            }
        }
    }
}
