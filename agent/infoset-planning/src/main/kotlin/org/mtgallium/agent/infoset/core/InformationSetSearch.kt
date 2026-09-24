package org.mtgallium.agent.infoset.core

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
    private val invokedEvaluatorId: String = valueSource.invokedEvaluatorId
    private val invokedEvaluatorConfigurationId: String =
        valueSource.invokedEvaluatorConfigurationId

    init {
        searchPrior?.let {
            require(it.configurationId.isNotBlank() && it.candidateLimit >= config.initialExpansionLimit)
            require(it.explorationConstant.isFinite() && it.explorationConstant >= 0.0)
        }
        listOf(opponentPolicy, rolloutPolicy, rolloutOpponentPolicy).forEach { policy ->
            require(!policy.requiresPolicyAnnotations || policy.requiresProductionAdmission) {
                "Policy annotations require production admission"
            }
        }
        require(
            config.leaf.stateSource != LeafStateSource.CURRENT_INFORMATION_STATE ||
                valueSource is LeafValueSource.Information
        ) {
            "A current-information-state leaf requires an information-state evaluator"
        }
    }

    /**
     * Settles the child just selected through an unvisited production edge.
     *
     * This is the authoritative narrow seam for fixed-root diagnostics: it uses precisely the
     * configured leaf route, terminal bypass, clipping, quiescence, forced-pass handling, and
     * rollout seed derivation that [search] uses at `edge.visits == 0`. It does not create a tree,
     * allocate visits, or expose a complete world outside its caller's existing trusted boundary.
     */
    @JvmOverloads
    fun settleFirstUnvisitedEdge(
        childWorld: SearchWorld,
        rootPlayer: String,
        searchSeed: Long,
        simulationIndex: Int,
        childDepth: Int = 1,
        rootTurnNumber: Int? = null,
    ): SearchSettlement {
        require(childDepth > 0)
        return leafValue(
            world = childWorld,
            rootPlayer = rootPlayer,
            tree = linkedMapOf(),
            searchSeed = searchSeed,
            simulationIndex = simulationIndex,
            depth = childDepth,
            onDepth = {},
            onWiden = {},
            rolloutAudit = RolloutPolicyAudit(),
            quiescenceAudit = QuiescenceAudit(),
            workAudit = SearchWorkAudit(),
            rolloutTargetTurn = rolloutTargetTurn(rootTurnNumber),
        )
    }

    /**
     * Continues an already-applied first edge to an actual engine terminal state with the exact
     * configured root/opponent rollout-policy pair and seed derivation. This diagnostic seam never
     * invokes a leaf evaluator or converts exhaustion, missing choices, rejection, or policy
     * replacement into a payoff.
     */
    fun continueFirstUnvisitedEdgeToTerminal(
        childWorld: SearchWorld,
        rootPlayer: String,
        searchSeed: Long,
        simulationIndex: Int,
        childDepth: Int = 1,
        maximumContinuationPolicyDecisions: Int = 4096,
        trace: TerminalContinuationTraceCollector? = null,
    ): TerminalPolicyContinuation = terminalContinuation().continueToTerminal(
        childWorld, rootPlayer, searchSeed, simulationIndex, childDepth, maximumContinuationPolicyDecisions, trace,
    )

    /** Compatibility access to the same terminal runner; constructing it creates no search state. */
    fun terminalContinuation(): TerminalPolicyContinuationRunner = TerminalPolicyContinuationRunner(
        rolloutPolicy, rolloutOpponentPolicy, config.initialExpansionLimit,
    )

    fun search(
        rootPlayer: String,
        belief: BeliefBatch<Weighted<SearchWorld>>,
        searchSeed: Long,
        simulationWorldSchedule: SimulationWorldSchedule? = null,
        rootSelectionGuidance: RootSelectionGuidance? = null,
    ): InformationSetSearchResult = searchInternal(
        rootPlayer, belief, searchSeed, simulationWorldSchedule, null, rootSelectionGuidance,
    )

    /**
     * Diagnostic search conditioned on an initially admitted root action. Every simulation takes
     * that first edge, then uses normal information-set tree/rollout policies and settlement.
     * The mean is an adaptive search estimate, not an observed game payoff or an uncertainty bound.
     */
    fun estimateRootAction(
        rootPlayer: String,
        belief: BeliefBatch<Weighted<SearchWorld>>,
        rootActionSignature: String,
        searchSeed: Long,
        simulationWorldSchedule: SimulationWorldSchedule? = null,
    ): RootActionSearchEstimate {
        require(rootActionSignature.isNotBlank())
        require(config.wallClockBudgetMillis == null) { "Action-conditional estimates require a fixed simulation budget" }
        val result = searchInternal(rootPlayer, belief, searchSeed, simulationWorldSchedule, rootActionSignature)
        val action = result.candidates.single { it.choice.signature == rootActionSignature }
        check(action.visits == config.simulations && result.candidates.filterNot { it == action }.all { it.visits == 0 })
        return RootActionSearchEstimate(action.choice, action.meanValue, action.visits,
            result.settlementCountsFor(action.choice), result.diagnostics)
    }

    private fun searchInternal(
        rootPlayer: String,
        belief: BeliefBatch<Weighted<SearchWorld>>,
        searchSeed: Long,
        simulationWorldSchedule: SimulationWorldSchedule?,
        rootActionSignature: String?,
        rootSelectionGuidance: RootSelectionGuidance? = null,
    ): InformationSetSearchResult {
        require(belief.particles.isNotEmpty())
        require(simulationWorldSchedule == null || simulationWorldSchedule.worlds.size == config.simulations) {
            "A fixed simulation-world schedule must contain exactly ${config.simulations} worlds"
        }
        requireConformantRoot(rootPlayer, belief)
        // Snapshot the caller's map so one immutable preference set owns the entire search.
        val guidance = rootSelectionGuidance?.copy(scores = rootSelectionGuidance.scores.toMap())
        require(searchPrior == null || guidance == null) { "PUCT and root UCT guidance are mutually exclusive" }
        require(searchPrior == null || rootActionSignature == null) { "Conditioned root search does not support prior-ranked pruning" }
        guidance?.let {
            require(rootActionSignature == null)
            val representative = belief.particles.first().value
            require(representative.actorToAct() == rootPlayer)
            require(initialDecision(representative).information().informationStateDigest == it.informationStateDigest) {
                "Root guidance belongs to another information state"
            }
            val expansion = initialExpansion(representative)
            require(expansion.isProfileExhaustive && expansion.candidates.map { choice -> choice.signature }.toSet() == it.scores.keys) {
                "Root guidance requires the exact profile-exhaustive initial admitted menu"
            }
        }
        val selectionGuidance = guidance?.takeIf { it.scores.values.any { score -> score != 0.0 } }
        if (rootActionSignature != null) {
            require(initialExpansion(belief.particles.first().value).candidates.any { it.signature == rootActionSignature }) {
                "Conditioned root action is absent from the initial admitted menu"
            }
        }
        val rolloutTargetTurn = rolloutTargetTurn(config.rolloutTurnHorizon?.let {
            belief.particles.first().value.epistemicState(rootPlayer).observation.turnNumber
        })
        simulationWorldSchedule?.let { requireConformantRoot(rootPlayer, it.worlds, "Scheduled root world") }
        val rootParticleIndices = productionRootParticleIndices(
            belief.particles.map { it.weight },
            searchSeed,
            config.simulations,
        )
        val tree = linkedMapOf<PlanningContextKey, SearchNode>()
        val rolloutAudit = RolloutPolicyAudit()
        val quiescenceAudit = QuiescenceAudit()
        val workAudit = SearchWorkAudit()
        val transitionCache = if (config.cacheSimulationTransitions) {
            SimulationTransitionCache(workAudit)
        } else {
            null
        }
        var maximumDepth = 0
        var wideningEvents = 0
        val startedAt = System.nanoTime()
        var completedSimulations = 0
        while (completedSimulations < config.simulations) {
            if (
                completedSimulations >= config.minimumSimulations &&
                config.wallClockBudgetMillis != null &&
                System.nanoTime() - startedAt >= config.wallClockBudgetMillis * 1_000_000L
            ) break
            val simulationIndex = completedSimulations
            val particleIndex = rootParticleIndices[simulationIndex]
            val world = (
                simulationWorldSchedule?.worlds?.get(simulationIndex)
                    ?: belief.particles[particleIndex].value
                ).fork()
            val outcome = simulate(
                world = world,
                rootPlayer = rootPlayer,
                tree = tree,
                searchSeed = searchSeed,
                simulationIndex = simulationIndex,
                depth = 0,
                onDepth = { maximumDepth = maxOf(maximumDepth, it) },
                onWiden = { wideningEvents++ },
                rolloutAudit = rolloutAudit,
                quiescenceAudit = quiescenceAudit,
                workAudit = workAudit,
                transitionCache = transitionCache,
                transitionNode = transitionCache?.root(
                    if (simulationWorldSchedule == null) particleIndex else simulationIndex
                ),
                quiescenceMode = false,
                quiescenceDepth = 0,
                rolloutTargetTurn = rolloutTargetTurn,
                rootActionSignature = rootActionSignature,
                rootSelectionGuidance = selectionGuidance,
            )
            require(outcome.backedValue.isFinite()) { "Search produced a non-finite value" }
            completedSimulations++
        }

        val representative = belief.particles.first().value
        val rootKey = nodeKey(representative, rootPlayer)
        val root = tree[rootKey] ?: error("Search never expanded the root information state")
        val totalVisits = root.edges.values.sumOf { it.visits }.coerceAtLeast(1)
        val statistics = root.edges.values.sortedBy { it.choice.signature }.map { edge ->
            SearchCandidateStatistics(
                choice = edge.choice,
                visits = edge.visits,
                meanValue = edge.meanValue(),
                policyProbability = edge.visits.toDouble() / totalVisits,
            )
        }
        val chosen = statistics.selectedSearchWinnerOrNull() ?: error("Root has no candidate edges")
        val visited = statistics.filter { it.visits > 0 }
        val rootPolicyDecisions = rolloutAudit.root.summary()
        val opponentPolicyDecisions = rolloutAudit.opponent.summary()
        return InformationSetSearchResult(
            chosen = chosen.choice,
            rootValue = if (visited.isEmpty()) 0.0 else {
                visited.sumOf { it.meanValue * it.visits } / visited.sumOf { it.visits }
            },
            candidates = statistics,
            candidateSettlementCounts = root.edges.mapValues { (_, edge) -> edge.settlementCounts },
            diagnostics = InformationSetSearchDiagnostics(
                simulations = completedSimulations,
                particles = belief.particles.size,
                nodes = tree.size,
                maximumDepth = maximumDepth,
                exhaustiveNodes = tree.values.count { it.exhaustive },
                nonExhaustiveNodes = tree.values.count { !it.exhaustive },
                wideningEvents = wideningEvents,
                opponentModelId = opponentPolicy.id,
                leaf = config.leaf,
                rootRolloutPolicyId = rolloutPolicy.id.takeIf {
                    config.leaf.stateSource == LeafStateSource.BOUNDED_ROLLOUT
                },
                opponentRolloutPolicyId = rolloutOpponentPolicy.id.takeIf {
                    config.leaf.stateSource == LeafStateSource.BOUNDED_ROLLOUT
                },
                rootRolloutDecisions = rootPolicyDecisions.decisions,
                opponentRolloutDecisions = opponentPolicyDecisions.decisions,
                rootRolloutFallbacks = rootPolicyDecisions.replacementDecisions,
                opponentRolloutFallbacks = opponentPolicyDecisions.replacementDecisions,
                opponentModelPolicyDecisions = rolloutAudit.opponentModel.summary(),
                rootRolloutPolicyDecisions = rootPolicyDecisions,
                opponentRolloutPolicyDecisions = opponentPolicyDecisions,
                quiescenceForcedPasses = quiescenceAudit.forcedPasses,
                quiescenceProfileForcedPasses = quiescenceAudit.profileForcedPasses,
                quiescenceStrategicDecisions = quiescenceAudit.strategicDecisions,
                quiescenceOverflows = quiescenceAudit.overflows,
                quiescenceFallbacks = quiescenceAudit.fallbacks,
                searchWorldSteps = workAudit.steps,
                policyAnnotatedExpansions = workAudit.policyAnnotatedExpansions,
                transitionCacheHits = workAudit.transitionCacheHits,
                transitionCacheMisses = workAudit.transitionCacheMisses,
                transitionCacheSnapshots = workAudit.transitionCacheSnapshots,
                transitionCacheDerivedSnapshots = workAudit.transitionCacheDerivedSnapshots,
                rolloutTransitionCacheHits = workAudit.rolloutTransitionCacheHits,
                rolloutTransitionCacheSnapshots = workAudit.rolloutTransitionCacheSnapshots,
                rolloutTransitionCacheBypasses = workAudit.rolloutTransitionCacheBypasses,
                policyAnnotationCacheHits = workAudit.policyAnnotationCacheHits,
                policyAnnotationCacheMisses = workAudit.policyAnnotationCacheMisses,
                opponentDistributionCacheHits = workAudit.opponentDistributionCacheHits,
                opponentDistributionCacheMisses = workAudit.opponentDistributionCacheMisses,
                rejectedTransitions = workAudit.rejectedTransitions,
                evaluatorId = invokedEvaluatorId,
                evaluatorConfigurationId = invokedEvaluatorConfigurationId,
                evaluatorCalls = workAudit.evaluatorCalls,
                unsettledLeafEvaluations = workAudit.unsettledLeafEvaluations,
                evaluatorNanos = workAudit.evaluatorNanos,
                evaluatorOutputChecksum = workAudit.evaluatorOutputChecksum(),
                quiescenceUnresolvedBackups = quiescenceAudit.unresolvedBackups,
                wallClockBudgetMillis = config.wallClockBudgetMillis,
                rootSelectionGuidance = guidance,
            ),
        )
    }

    private fun simulate(
        world: SearchWorld,
        rootPlayer: String,
        tree: MutableMap<PlanningContextKey, SearchNode>,
        searchSeed: Long,
        simulationIndex: Int,
        depth: Int,
        onDepth: (Int) -> Unit,
        onWiden: () -> Unit,
        rolloutAudit: RolloutPolicyAudit,
        quiescenceAudit: QuiescenceAudit,
        workAudit: SearchWorkAudit,
        transitionCache: SimulationTransitionCache?,
        transitionNode: SimulationTransitionNode?,
        quiescenceMode: Boolean,
        quiescenceDepth: Int,
        rolloutTargetTurn: Int?,
        rootActionSignature: String? = null,
        rootSelectionGuidance: RootSelectionGuidance? = null,
    ): SearchSettlement {
        onDepth(depth)
        world.terminalPayoff(rootPlayer)?.let {
            return SearchSettlement(it, SearchSettlementOrigin.TERMINAL_PAYOFF)
        }
        if (rolloutTargetTurn != null &&
            world.epistemicState(rootPlayer).observation.turnNumber >= rolloutTargetTurn
        ) {
            return staticLeafValue(world, rootPlayer, workAudit)
        }
        if (quiescenceMode) {
            when (val settled = settleStaticLeaf(world, rootPlayer, quiescenceAudit, workAudit)) {
                is StaticLeafSettlement.Value -> return settled.settlement
                StaticLeafSettlement.VolatileBranch -> if (quiescenceDepth >= config.maxQuiescenceDecisions) {
                    quiescenceAudit.overflows++
                    quiescenceAudit.fallbacks++
                    return unresolvedLeafValue(world, rootPlayer, quiescenceAudit, workAudit)
                }
            }
        } else if (depth >= config.maxPolicyDecisions) {
            val value = leafValue(
                world,
                rootPlayer,
                tree,
                searchSeed,
                simulationIndex,
                depth,
                onDepth,
                onWiden,
                rolloutAudit,
                quiescenceAudit,
                workAudit,
                rolloutTargetTurn,
                transitionCache,
                transitionNode,
            )
            return value
        }

        val initialContext = world.actorToAct()?.let {
            if (it == rootPlayer) initialDecision(world)
            else world.decisionContext(DecisionView(config.initialExpansionLimit))
        }
        var expansion = initialContext?.expansion ?: initialExpansion(world)
        if (expansion.candidates.isEmpty()) {
            transitionCache?.retainDerived(transitionNode, world, DERIVED_BASE)
            if (quiescenceMode) quiescenceAudit.fallbacks++
            return if (quiescenceMode) {
                unresolvedLeafValue(world, rootPlayer, quiescenceAudit, workAudit)
            } else {
                leafValue(
                    world, rootPlayer, tree, searchSeed, simulationIndex, depth, onDepth, onWiden,
                    rolloutAudit, quiescenceAudit, workAudit, rolloutTargetTurn,
                    transitionCache, transitionNode,
                )
            }
        }
        val actor = world.actorToAct()
            ?: run {
                transitionCache?.retainDerived(transitionNode, world, DERIVED_BASE)
                if (quiescenceMode) quiescenceAudit.fallbacks++
                return if (quiescenceMode) {
                    unresolvedLeafValue(world, rootPlayer, quiescenceAudit, workAudit)
                } else {
                    leafValue(
                        world, rootPlayer, tree, searchSeed, simulationIndex, depth, onDepth, onWiden,
                        rolloutAudit, quiescenceAudit, workAudit, rolloutTargetTurn,
                        transitionCache, transitionNode,
                    )
                }
            }

        if (quiescenceMode) quiescenceAudit.strategicDecisions++

        // The configured opponent is part of the stochastic environment. Its private actions may
        // legitimately differ between sampled worlds, so they must not become edges in the root
        // player's shared information tree.
        if (actor != rootPlayer) {
            val opponentContext = if (world is PolicyAnnotatedSearchWorld && opponentPolicy.requiresPolicyAnnotations) {
                workAudit.policyAnnotatedExpansions++
                transitionCache?.annotatedContext(transitionNode) {
                    world.decisionContext(opponentPolicy.decisionView(config.initialExpansionLimit))
                } ?: world.decisionContext(opponentPolicy.decisionView(config.initialExpansionLimit))
            } else {
                world.decisionContext(opponentPolicy.decisionView(config.initialExpansionLimit))
            }
            expansion = opponentContext.expansion
            val policySeed = ComponentSeeds.derive(searchSeed, simulationIndex, depth, "opponent")
            fun computeDistribution(): ProbabilityDistribution<SemanticChoice> {
                transitionCache?.retainDerived(
                    transitionNode,
                    world,
                    DERIVED_BASE or DERIVED_POLICY_EXPANSION or DERIVED_INFORMATION,
                )
                return opponentPolicy.distribution(opponentContext, policySeed)
                    .requireAdmittedSupport(opponentContext.expansion.candidates)
            }
            val distribution = if (opponentPolicy.distributionIsSeedInvariant) {
                transitionCache?.opponentDistribution(transitionNode, ::computeDistribution)
                    ?: computeDistribution()
            } else {
                computeDistribution()
            }
            val selected = sampleOpponentPolicyDistribution(
                distribution,
                ComponentSeeds.derive(searchSeed, simulationIndex, depth, "opponent-sample"),
            ).requireAdmittedChoice(opponentContext.expansion.candidates)
            rolloutAudit.opponentModel.record(
                opponentPolicy.decisionDiagnostic(
                    context = opponentContext,
                    chosen = selected,
                    policySeed = policySeed,
                    attributionSeed = ComponentSeeds.derive(
                        searchSeed,
                        simulationIndex,
                        depth,
                        "opponent-component-attribution",
                    ),
                )
            )
            val advanced = advanceCached(world, selected, transitionCache, transitionNode, workAudit)
            return simulate(
                advanced.world,
                rootPlayer,
                tree,
                searchSeed,
                simulationIndex,
                depth + 1,
                onDepth,
                onWiden,
                rolloutAudit,
                quiescenceAudit,
                workAudit,
                transitionCache,
                advanced.node,
                quiescenceMode,
                if (quiescenceMode) quiescenceDepth + 1 else quiescenceDepth,
                rolloutTargetTurn,
            )
        }

        // Root-player choices alone form the shared information tree.
        val rootContext = requireNotNull(initialContext)
        val key = rootContext.planningKey()
        transitionCache?.retainDerived(
            transitionNode,
            world,
            DERIVED_BASE or DERIVED_INFORMATION,
        )
        val node = tree.getOrPut(key) {
            SearchNode(rootContext, config.initialExpansionLimit).also { node ->
                searchPrior?.let { prior ->
                    val probabilities = prior.probabilities(rootContext)
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
            }
        }
        node.requireCompatible(rootContext)
        if (searchPrior == null && node.expansionLimit > config.initialExpansionLimit && world is ProgressiveSearchWorld) {
            val widened = world.decisionContext(DecisionView(node.expansionLimit))
            node.merge(widened)
        }
        val wideningIndex = config.wideningThresholds.indexOfLast { node.visits >= it }
        // A profile-exhaustive menu is complete for this action space; a higher limit adds nothing.
        if (searchPrior == null && wideningIndex >= 0 && !node.profileExhaustive && world is ProgressiveSearchWorld) {
            val desired = config.wideningLimits[wideningIndex]
            if (desired > node.expansionLimit) {
                val widened = world.decisionContext(DecisionView(desired))
                node.expansionLimit = desired
                node.merge(widened)
                onWiden()
            }
        }

        val edge = if (rootActionSignature == null) selectUct(node, searchSeed, simulationIndex, depth, rootSelectionGuidance)
            else {
                check(depth == 0 && actor == rootPlayer)
                requireNotNull(node.edges[rootActionSignature]) { "Conditioned root action is absent from this world" }
            }
        val advanced = advanceCached(world, edge.choice, transitionCache, transitionNode, workAudit)
        val value = if (quiescenceMode) {
            simulate(
                advanced.world,
                rootPlayer,
                tree,
                searchSeed,
                simulationIndex,
                depth + 1,
                onDepth,
                onWiden,
                rolloutAudit,
                quiescenceAudit,
                workAudit,
                transitionCache = null,
                transitionNode = null,
                quiescenceMode = true,
                quiescenceDepth = quiescenceDepth + 1,
                rolloutTargetTurn = rolloutTargetTurn,
            )
        } else if (edge.visits == 0) {
            leafValue(
                advanced.world,
                rootPlayer,
                tree,
                searchSeed,
                simulationIndex,
                depth + 1,
                onDepth,
                onWiden,
                rolloutAudit,
                quiescenceAudit,
                workAudit,
                rolloutTargetTurn,
                transitionCache,
                advanced.node,
            )
        } else {
            simulate(
                advanced.world,
                rootPlayer,
                tree,
                searchSeed,
                simulationIndex,
                depth + 1,
                onDepth,
                onWiden,
                rolloutAudit,
                quiescenceAudit,
                workAudit,
                transitionCache,
                advanced.node,
                quiescenceMode = false,
                quiescenceDepth = 0,
                rolloutTargetTurn = rolloutTargetTurn,
            )
        }
        node.visits++
        edge.record(value)
        return value
    }

    private fun leafValue(
        world: SearchWorld,
        rootPlayer: String,
        tree: MutableMap<PlanningContextKey, SearchNode>,
        searchSeed: Long,
        simulationIndex: Int,
        depth: Int,
        onDepth: (Int) -> Unit,
        onWiden: () -> Unit,
        rolloutAudit: RolloutPolicyAudit,
        quiescenceAudit: QuiescenceAudit,
        workAudit: SearchWorkAudit,
        rolloutTargetTurn: Int?,
        transitionCache: SimulationTransitionCache? = null,
        transitionNode: SimulationTransitionNode? = null,
    ): SearchSettlement = when (config.leaf.stateSource) {
        LeafStateSource.CURRENT_INFORMATION_STATE,
        LeafStateSource.CURRENT_SAMPLED_WORLD -> simulate(
            world,
            rootPlayer,
            tree,
            searchSeed,
            simulationIndex,
            depth,
            onDepth,
            onWiden,
            rolloutAudit,
            quiescenceAudit,
            workAudit = workAudit,
            transitionCache = null,
            transitionNode = null,
            quiescenceMode = true,
            quiescenceDepth = 0,
            rolloutTargetTurn = rolloutTargetTurn,
        )
        LeafStateSource.BOUNDED_ROLLOUT -> rollout(
            world,
            rootPlayer,
            searchSeed,
            simulationIndex,
            depth,
            rolloutAudit,
            quiescenceAudit,
            workAudit,
            rolloutTargetTurn,
            transitionCache,
            transitionNode,
        )
    }

    private fun rolloutTargetTurn(rootTurnNumber: Int?): Int? = config.rolloutTurnHorizon?.let { horizon ->
        Math.addExact(requireNotNull(rootTurnNumber) {
            "A completed-turn diagnostic requires the original root turn number"
        }, horizon.completedTurns)
    }

    /**
     * Horizon quiescence advances an exact singleton priority pass and, under
     * [QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1], a profile-exhaustive singleton pass in
     * a volatile position. A quiet state is one
     * for which [isVolatile] is false: no stack, no combat except END_COMBAT, no pending Combat,
     * Damage, or Order decision, and no lethal-damage battlefield creature. It need not have no
     * candidates. Thus this method never consumes a genuine branching decision, a singleton mana
     * ability, or another strategic action while seeking a state to evaluate.
     */
    private fun settleStaticLeaf(
        world: SearchWorld,
        rootPlayer: String,
        audit: QuiescenceAudit,
        workAudit: SearchWorkAudit,
        maximumForcedPasses: Int = config.maxQuiescenceForcedPasses,
    ): StaticLeafSettlement {
        var forcedPasses = 0
        while (true) {
            world.terminalPayoff(rootPlayer)?.let {
                return StaticLeafSettlement.Value(SearchSettlement(it, SearchSettlementOrigin.TERMINAL_PAYOFF))
            }
            val expansion = initialExpansion(world)
            val profilePass = if (expansion.exactSingletonPassOrNull() == null &&
                config.leaf.quiescencePasses == QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1) {
                expansion.policySingletonPassOrNull()
                    ?.takeIf { isVolatile(world.informationState(rootPlayer).observation) }
            } else null
            val pass = expansion.exactSingletonPassOrNull() ?: profilePass
            if (pass != null) {
                if (forcedPasses >= maximumForcedPasses) {
                    audit.overflows++
                    audit.fallbacks++
                    return StaticLeafSettlement.Value(
                        unresolvedLeafValue(world, rootPlayer, audit, workAudit)
                    )
                }
                workAudit.steps++
                val result = world.step(pass)
                if (!result.accepted) {
                    workAudit.rejectedTransitions++
                    throw RejectedSearchTransitionException(pass.signature, result.diagnostic)
                }
                forcedPasses++
                audit.forcedPasses++
                if (profilePass != null) audit.profileForcedPasses++
                continue
            }
            if (!isVolatile(world.informationState(rootPlayer).observation)) {
                return StaticLeafSettlement.Value(staticLeafValue(world, rootPlayer, workAudit))
            }
            return if (expansion.candidates.isNotEmpty() && world.actorToAct() != null) {
                StaticLeafSettlement.VolatileBranch
            } else {
                audit.fallbacks++
                StaticLeafSettlement.Value(unresolvedLeafValue(world, rootPlayer, audit, workAudit))
            }
        }
    }

    private fun unresolvedLeafValue(
        world: SearchWorld,
        rootPlayer: String,
        audit: QuiescenceAudit,
        workAudit: SearchWorkAudit,
    ): SearchSettlement {
        if (config.leaf.unresolved == UnresolvedLeafHandling.BACK_UP_NEUTRAL) {
            audit.unresolvedBackups++
            return SearchSettlement(0.0, SearchSettlementOrigin.NEUTRAL_UNRESOLVED_SETTLEMENT)
        }
        return staticLeafValue(world, rootPlayer, workAudit)
    }

    private fun staticLeafValue(
        world: SearchWorld,
        rootPlayer: String,
        workAudit: SearchWorkAudit,
    ): SearchSettlement {
        world.terminalPayoff(rootPlayer)?.let {
            return SearchSettlement(it, SearchSettlementOrigin.TERMINAL_PAYOFF)
        }
        val started = System.nanoTime()
        val information = if (valueSource is LeafValueSource.Information) world.informationState(rootPlayer) else null
        val (rawValue, origin) = when (val source = valueSource) {
            is LeafValueSource.Information -> source.evaluator.evaluate(
                requireNotNull(information),
                rootPlayer,
            ) to source.evaluator.settlementOrigin
            is LeafValueSource.SampledWorld -> world.sampledWorldLeafValue(
                rootPlayer,
                source.invokedEvaluatorId,
            ) to SearchSettlementOrigin.HEURISTIC_SETTLEMENT
        }
        require(rawValue.isFinite()) { "Leaf evaluator returned a non-finite score: $rawValue" }
        val value = rawValue.coerceIn(-1.0, 1.0)
        val elapsed = System.nanoTime() - started
        workAudit.recordEvaluator(value, elapsed,
            isVolatile(information?.observation ?: world.epistemicState(rootPlayer).observation))
        return SearchSettlement(value, origin)
    }

    private fun isVolatile(observation: PlayerObservationSnapshot): Boolean {
        if (observation.stack.isNotEmpty()) return true
        if (observation.phase == "COMBAT" && observation.step != "END_COMBAT") return true
        val pendingKind = observation.pendingDecision?.decisionKind.orEmpty()
        if (listOf("Combat", "Damage", "Order").any(pendingKind::contains)) return true
        return observation.zones.asSequence().flatMap { it.cards.asSequence() }.any { card ->
            card.zone == "BATTLEFIELD" && card.toughness?.let { card.damageMarked >= it } == true
        }
    }

    private fun rollout(
        startingWorld: SearchWorld,
        rootPlayer: String,
        searchSeed: Long,
        simulationIndex: Int,
        startingDepth: Int,
        audit: RolloutPolicyAudit,
        quiescenceAudit: QuiescenceAudit,
        workAudit: SearchWorkAudit,
        rolloutTargetTurn: Int? = null,
        transitionCache: SimulationTransitionCache? = null,
        startingTransitionNode: SimulationTransitionNode? = null,
    ): SearchSettlement {
        require((rolloutTargetTurn != null) == (config.rolloutTurnHorizon != null))
        var world = startingWorld.fork()
        var transitionNode = startingTransitionNode
        var depth = startingDepth
        var rolloutDecisions = 0
        while (rolloutTargetTurn != null || depth < config.maxPolicyDecisions) {
            world.terminalPayoff(rootPlayer)?.let {
                return SearchSettlement(it, SearchSettlementOrigin.TERMINAL_PAYOFF)
            }
            if (rolloutTargetTurn != null) {
                if (world.epistemicState(rootPlayer).observation.turnNumber >= rolloutTargetTurn) {
                    // The old turn's cleanup is complete. Leave the new turn's first genuine
                    // choice untouched, including any upkeep trigger/response it presents.
                    return staticLeafValue(world, rootPlayer, workAudit)
                }
                if (rolloutDecisions >= requireNotNull(config.rolloutTurnHorizon).maxPolicyDecisions) {
                    throw RolloutTurnHorizonException(
                        RolloutTurnHorizonFailure.DECISION_LIMIT,
                        rolloutTargetTurn,
                        rolloutDecisions,
                    )
                }
            }
            val expansion = initialExpansion(world)
            if (expansion.candidates.isEmpty()) break
            val actor = world.actorToAct() ?: break
            var policyInformationUsed = false
            val selected = run {
                // The outer opponent remains an independently configurable stochastic environment.
                val basePolicy = if (actor == rootPlayer) rolloutPolicy else rolloutOpponentPolicy
                val policy = (basePolicy as? RolloutPolicySchedule)?.atStep(rolloutDecisions) ?: basePolicy
                val policyContext = initialPolicyContext(world, policy, workAudit)
                val decision = policy.select(
                    context = policyContext,
                    policySeed = ComponentSeeds.derive(
                        searchSeed,
                        simulationIndex,
                        depth,
                        policy.id,
                        "rollout",
                    ),
                    sampleSeed = ComponentSeeds.derive(searchSeed, simulationIndex, depth, "rollout-sample"),
                )
                decision.choice.requireAdmittedChoice(policyContext.expansion.candidates)
                policyInformationUsed = policyContext.informationDemanded
                audit.record(actor == rootPlayer, decision.diagnostic)
                (policy as? BoundedRolloutObserver)?.observeDecision(
                    policyContext, decision.choice, searchSeed, simulationIndex, depth)
                decision.choice
            }
            // Cache exact world prefixes, never the rollout policy's distribution or sampled choice.
            // The same node may later enter the tree with a different opponent policy or wider menu.
            transitionCache?.retainDerived(
                transitionNode, world,
                DERIVED_BASE or DERIVED_POLICY_EXPANSION or
                    (if (policyInformationUsed) DERIVED_INFORMATION else 0),
            )
            val advanced = advanceCached(world, selected, transitionCache, transitionNode, workAudit, rollout = true)
            world = advanced.world
            transitionNode = advanced.node
            depth++
            rolloutDecisions++
        }
        world.terminalPayoff(rootPlayer)?.let {
            return SearchSettlement(it, SearchSettlementOrigin.TERMINAL_PAYOFF)
        }
        if (rolloutTargetTurn != null) {
            throw RolloutTurnHorizonException(
                RolloutTurnHorizonFailure.MISSING_DECISION,
                rolloutTargetTurn,
                rolloutDecisions,
            )
        }
        if (config.leaf.cutoff == RolloutCutoff.EVALUATE) {
            return staticLeafValue(world, rootPlayer, workAudit)
        }
        if (config.leaf.cutoff == RolloutCutoff.POLICY_QUIESCENCE) {
            return settleWithRolloutPolicies(
                world, rootPlayer, searchSeed, simulationIndex, depth,
                audit, quiescenceAudit, workAudit,
            )
        }
        return when (val settled = settleStaticLeaf(world, rootPlayer, quiescenceAudit, workAudit)) {
            is StaticLeafSettlement.Value -> settled.settlement
            StaticLeafSettlement.VolatileBranch -> {
                quiescenceAudit.fallbacks++
                unresolvedLeafValue(world, rootPlayer, quiescenceAudit, workAudit)
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
    private fun settleWithRolloutPolicies(
        world: SearchWorld,
        rootPlayer: String,
        searchSeed: Long,
        simulationIndex: Int,
        rolloutDepth: Int,
        rolloutAudit: RolloutPolicyAudit,
        audit: QuiescenceAudit,
        workAudit: SearchWorkAudit,
    ): SearchSettlement {
        val initialForcedPasses = audit.forcedPasses
        var decisions = 0
        while (true) {
            val remainingPasses = config.maxQuiescenceForcedPasses -
                (audit.forcedPasses - initialForcedPasses)
            when (val settled = settleStaticLeaf(world, rootPlayer, audit, workAudit, remainingPasses)) {
                is StaticLeafSettlement.Value -> return settled.settlement
                StaticLeafSettlement.VolatileBranch -> Unit
            }
            if (decisions >= config.maxQuiescenceDecisions) {
                audit.overflows++
                audit.fallbacks++
                return unresolvedLeafValue(world, rootPlayer, audit, workAudit)
            }
            val actor = checkNotNull(world.actorToAct())
            val policy = if (actor == rootPlayer) rolloutPolicy else rolloutOpponentPolicy
            val policyContext = initialPolicyContext(world, policy, workAudit)
            val decision = policy.select(
                context = policyContext,
                policySeed = ComponentSeeds.derive(
                    searchSeed, simulationIndex, rolloutDepth, decisions, policy.id, "rollout-quiescence",
                ),
                sampleSeed = ComponentSeeds.derive(
                    searchSeed, simulationIndex, rolloutDepth, decisions, "rollout-quiescence-sample",
                ),
            )
            decision.choice.requireAdmittedChoice(policyContext.expansion.candidates)
            rolloutAudit.record(actor == rootPlayer, decision.diagnostic)
            audit.strategicDecisions++
            workAudit.steps++
            val result = world.step(decision.choice)
            if (!result.accepted) {
                workAudit.rejectedTransitions++
                throw RejectedSearchTransitionException(decision.choice.signature, result.diagnostic)
            }
            decisions++
        }
    }

    private sealed interface StaticLeafSettlement {
        data class Value(val settlement: SearchSettlement) : StaticLeafSettlement
        data object VolatileBranch : StaticLeafSettlement
    }

    private class QuiescenceAudit {
        var forcedPasses = 0
        var profileForcedPasses = 0
        var strategicDecisions = 0
        var overflows = 0
        var fallbacks = 0
        var unresolvedBackups = 0
    }

    private class RolloutPolicyAudit {
        val opponentModel = OpponentPolicyDecisionCounter()
        val root = OpponentPolicyDecisionCounter()
        val opponent = OpponentPolicyDecisionCounter()

        fun record(rootSeat: Boolean, diagnostic: OpponentPolicyDecisionDiagnostic) {
            (if (rootSeat) root else opponent).record(diagnostic)
        }
    }

    private class SearchWorkAudit {
        var steps = 0
        var policyAnnotatedExpansions = 0
        var transitionCacheHits = 0
        var transitionCacheMisses = 0
        var transitionCacheSnapshots = 0
        var transitionCacheDerivedSnapshots = 0
        var rolloutTransitionCacheHits = 0
        var rolloutTransitionCacheSnapshots = 0
        var rolloutTransitionCacheBypasses = 0
        var policyAnnotationCacheHits = 0
        var policyAnnotationCacheMisses = 0
        var opponentDistributionCacheHits = 0
        var opponentDistributionCacheMisses = 0
        var rejectedTransitions = 0
        var evaluatorCalls = 0
        var unsettledLeafEvaluations = 0
        var evaluatorNanos = 0L
        private var evaluatorChecksum = 1_125_899_906_842_597L

        fun recordEvaluator(value: Double, elapsedNanos: Long, unsettled: Boolean) {
            evaluatorCalls++
            if (unsettled) unsettledLeafEvaluations++
            evaluatorNanos += elapsedNanos.coerceAtLeast(0L)
            evaluatorChecksum = evaluatorChecksum * 31L + value.toBits()
        }

        fun evaluatorOutputChecksum(): String =
            java.lang.Long.toUnsignedString(evaluatorChecksum, 16).padStart(16, '0')
    }

    /** Exact per-root-particle prefix cache; keys are semantic paths, never lossy state hashes. */
    private class SimulationTransitionCache(private val audit: SearchWorkAudit) {
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
                audit.transitionCacheSnapshots++
                node.snapshot = world.fork()
            }
            if (node.derivedFeatures and features == features) return
            audit.transitionCacheDerivedSnapshots++
            val transferred = (node.snapshot as? DerivedCacheTransferSearchWorld)
                ?.copyDerivedCachesFrom(world) == true
            if (!transferred) node.snapshot = world.fork()
            node.derivedFeatures = node.derivedFeatures or features
        }

        fun annotatedContext(
            node: SimulationTransitionNode?,
            compute: () -> DecisionSiteRequest,
        ): DecisionSiteRequest {
            if (node?.snapshot == null) return compute()
            node.annotatedContext?.let {
                audit.policyAnnotationCacheHits++
                return it
            }
            audit.policyAnnotationCacheMisses++
            return compute().also { node.annotatedContext = it }
        }

        fun opponentDistribution(
            node: SimulationTransitionNode?,
            compute: () -> ProbabilityDistribution<SemanticChoice>,
        ): ProbabilityDistribution<SemanticChoice> {
            if (node?.snapshot == null) return compute()
            node.opponentDistribution?.let {
                audit.opponentDistributionCacheHits++
                return it
            }
            audit.opponentDistributionCacheMisses++
            return compute().also { node.opponentDistribution = it }
        }

        fun advance(
            world: SearchWorld,
            choice: SemanticChoice,
            node: SimulationTransitionNode,
            rollout: Boolean,
        ): CachedAdvance {
            node.children[choice.signature]?.let { cached ->
                audit.transitionCacheHits++
                if (rollout) audit.rolloutTransitionCacheHits++
                return CachedAdvance(requireNotNull(cached.snapshot).fork(), cached)
            }
            audit.transitionCacheMisses++
            audit.steps++
            val result = world.step(choice)
            if (!result.accepted) {
                audit.rejectedTransitions++
                throw RejectedSearchTransitionException(choice.signature, result.diagnostic)
            }
            // Rollout branching can retain far more snapshots than tree traversal. A full cache
            // falls back to ordinary stepping; existing exact-prefix hits remain usable.
            if (rollout && rolloutSnapshots >= MAX_ROLLOUT_CACHE_SNAPSHOTS) {
                audit.rolloutTransitionCacheBypasses++
                return CachedAdvance(world, null)
            }
            if (rollout) {
                rolloutSnapshots++
                audit.rolloutTransitionCacheSnapshots++
            }
            audit.transitionCacheSnapshots++
            val child = SimulationTransitionNode(world.fork())
            node.children[choice.signature] = child
            return CachedAdvance(world, child)
        }
    }

    private class SimulationTransitionNode(var snapshot: SearchWorld? = null) {
        val children = mutableMapOf<String, SimulationTransitionNode>()
        var derivedFeatures: Int = 0
        var annotatedContext: DecisionSiteRequest? = null
        var opponentDistribution: ProbabilityDistribution<SemanticChoice>? = null
    }

    private data class CachedAdvance(
        val world: SearchWorld,
        val node: SimulationTransitionNode?,
    )

    private fun advanceCached(
        world: SearchWorld,
        choice: SemanticChoice,
        cache: SimulationTransitionCache?,
        node: SimulationTransitionNode?,
        audit: SearchWorkAudit,
        rollout: Boolean = false,
    ): CachedAdvance {
        if (cache != null && node != null) return cache.advance(world, choice, node, rollout)
        audit.steps++
        val result = world.step(choice)
        if (!result.accepted) {
            audit.rejectedTransitions++
            throw RejectedSearchTransitionException(choice.signature, result.diagnostic)
        }
        return CachedAdvance(world, null)
    }

    private data class RootDescriptor(
        val informationStateDigest: String,
        val actor: String?,
        val proposalVersion: String,
        val proposalSeed: Long,
        val candidateSignatures: Set<String>,
    )

    private fun rootDescriptor(world: SearchWorld, rootPlayer: String): RootDescriptor {
        val context = world.actorToAct()?.let { initialDecision(world) }
        val expansion = context?.expansion ?: initialExpansion(world)
        val information = if (context?.actor == rootPlayer) context.information() else world.informationState(rootPlayer)
        return descriptor(information.informationStateDigest, context?.actor, expansion)
    }

    private fun initialDecision(world: SearchWorld): DecisionSiteRequest =
        world.decisionContext(DecisionView(searchPrior?.candidateLimit ?: config.initialExpansionLimit,
            admission = searchPrior?.admission ?: DecisionAdmission.SEMANTIC))

    private fun initialExpansion(world: SearchWorld): PolicyExpansion =
        world.initialPolicyChoices(config.initialExpansionLimit)

    private fun initialPolicyContext(world: SearchWorld, policy: PolicyComponent, workAudit: SearchWorkAudit): DecisionSiteRequest {
        if (world is PolicyAnnotatedSearchWorld && policy.requiresPolicyAnnotations) workAudit.policyAnnotatedExpansions++
        return world.decisionContext(policy.decisionView(config.initialExpansionLimit))
    }

    private fun descriptor(
        informationStateDigest: String,
        actor: String?,
        expansion: PolicyExpansion,
    ): RootDescriptor = RootDescriptor(
        informationStateDigest = informationStateDigest,
        actor = actor,
        proposalVersion = expansion.proposalVersion,
        proposalSeed = expansion.proposalSeed,
        candidateSignatures = expansion.candidates.mapTo(linkedSetOf(), SemanticChoice::signature),
    )

    private fun selectUct(
        node: SearchNode,
        searchSeed: Long,
        simulationIndex: Int,
        depth: Int,
        guidance: RootSelectionGuidance?,
    ): SearchEdge {
        check(guidance == null || depth == 0)
        searchPrior?.let { prior ->
            return node.edges.values.maxWith(compareBy<SearchEdge> {
                it.meanValue() + prior.explorationConstant * it.prior * sqrt(node.visits + 1.0) / (1.0 + it.visits)
            }.thenByDescending { it.choice.signature })
        }
        val unvisited = node.edges.values.filter { it.visits == 0 }
        if (unvisited.isNotEmpty()) {
            if (guidance == null) return unvisited.minBy { edge ->
                PolicyJson.sha256("$searchSeed:$simulationIndex:$depth:${edge.choice.signature}")
            }
            return unvisited.minWith(compareByDescending<SearchEdge> { edge ->
                guidance.scores.getValue(edge.choice.signature)
            }.thenBy { edge ->
                PolicyJson.sha256("$searchSeed:$simulationIndex:$depth:${edge.choice.signature}")
            })
        }
        val logParent = ln((node.visits + 1).toDouble())
        return node.edges.values.maxWith(
            compareBy<SearchEdge> { edge ->
                val uct = edge.meanValue() + config.explorationConstant * sqrt(logParent / edge.visits)
                val score = guidance?.scores?.getValue(edge.choice.signature) ?: 0.0
                if (score == 0.0) uct else uct + score / (1.0 + edge.visits)
            }.thenByDescending { it.choice.signature }
        )
    }

    private fun nodeKey(world: SearchWorld, rootPlayer: String): PlanningContextKey {
        if (world.actorToAct() == rootPlayer) return initialDecision(world).planningKey()
        // Observer-only compatibility lookup; no acting-player decision is assembled here.
        return PlanningContextKey(world.informationState(rootPlayer).informationStateDigest, world.actorToAct())
    }

    private class SearchNode(context: DecisionSiteRequest, var expansionLimit: Int) {
        val edges = linkedMapOf<String, SearchEdge>()
        var visits: Int = 0
        var exhaustive: Boolean = context.expansion.isExhaustive
        var profileExhaustive: Boolean = context.expansion.isProfileExhaustive
        private val planning = PlanningDecisionContext(context)
        private var currentContext = context
        init { context.expansion.candidates.forEach { edges[it.signature] = SearchEdge(it) } }

        fun requireCompatible(context: DecisionSiteRequest) = planning.requireInitial(context)
        fun merge(context: DecisionSiteRequest) {
            val refinement = planning.refine(currentContext, context)
            refinement.addedChoices.forEach { edges.putIfAbsent(it.signature, SearchEdge(it)) }
            exhaustive = refinement.expansion.isExhaustive
            profileExhaustive = refinement.expansion.isProfileExhaustive
            currentContext = context
        }
    }

    private class SearchEdge(val choice: SemanticChoice) {
        var prior: Double = 0.0
        var visits: Int = 0
        var valueSum: Double = 0.0
        var settlementCounts: SearchSettlementCounts = SearchSettlementCounts()

        fun record(settlement: SearchSettlement) {
            visits++
            valueSum += settlement.backedValue
            settlementCounts = settlementCounts.plus(SearchSettlementCounts.one(settlement.origin))
        }

        fun meanValue(): Double = if (visits == 0) 0.0 else valueSum / visits
    }

    companion object {
        private const val DERIVED_BASE = 1
        private const val DERIVED_POLICY_EXPANSION = 2
        private const val DERIVED_INFORMATION = 4
        private const val MAX_ROLLOUT_CACHE_SNAPSHOTS = 4096

        private fun normalize(weights: List<Double>): List<Double> {
            require(weights.all { it.isFinite() && it >= 0.0 })
            val total = weights.sum()
            require(total > 0.0)
            return weights.map { it / total }
        }

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
            val normalized = normalize(weights)
            return List(simulations) { simulationIndex ->
                sampleIndex(
                    normalized,
                    SplitMix64(ComponentSeeds.derive(searchSeed, simulationIndex, "root-particle")),
                )
            }
        }

        private fun sampleIndex(weights: List<Double>, random: SplitMix64): Int {
            val target = random.nextDouble()
            var cumulative = 0.0
            for (index in weights.indices) {
                cumulative += weights[index]
                if (target < cumulative) return index
            }
            return weights.lastIndex
        }

    }

    private fun requireConformantRoot(
        rootPlayer: String,
        belief: BeliefBatch<Weighted<SearchWorld>>,
    ) = requireConformantRoot(rootPlayer, belief.particles.map { it.value }, "Root particle")

    private fun requireConformantRoot(
        rootPlayer: String,
        worlds: List<SearchWorld>,
        subject: String,
    ) {
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
}
