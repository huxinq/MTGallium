package org.mtgallium.agent.infoset.core

import kotlin.math.ln
import kotlin.math.sqrt

/** History-keyed, shared-tree information-set Monte Carlo search. */
class InformationSetSearch(
    private val config: InformationSetSearchConfig,
    private val opponentPolicy: OpponentPolicy,
    private val rolloutPolicy: OpponentPolicy,
    private val rolloutOpponentPolicy: OpponentPolicy,
    private val leafEvaluationStrategy: LeafEvaluationStrategy,
    private val reuseConfig: InformationSetSearchReuseConfig = InformationSetSearchReuseConfig.DISABLED,
) {
    private var retainedTraces: List<SimulationTrace> = emptyList()
    private var continuityEpoch: Long? = null
    private val invokedEvaluatorId: String = leafEvaluationStrategy.source.invokedEvaluatorId
    private val invokedEvaluatorConfigurationId: String =
        leafEvaluationStrategy.source.invokedEvaluatorConfigurationId

    init {
        listOf(opponentPolicy, rolloutPolicy, rolloutOpponentPolicy).forEach { policy ->
            require(!policy.requiresPolicyAnnotations || policy.requiresProductionAdmission) {
                "Policy annotations require production admission"
            }
        }
        require(leafEvaluationStrategy.configuredEvaluatorId == config.leaf.evaluator.evaluatorId) {
            "Configured leaf ${config.leaf.evaluator.evaluatorId} does not match strategy " +
                leafEvaluationStrategy.configuredEvaluatorId
        }
        require(
            config.leaf.stateSource != LeafStateSource.CURRENT_INFORMATION_STATE ||
                leafEvaluationStrategy.source is LeafValueSource.Information
        ) {
            "A current-information-state leaf requires an information-state evaluator"
        }
        require(config.wallClockBudgetMillis == null || !reuseConfig.enabled) {
            "Wall-clock experiments must disable trace reuse so the measured budget is identifiable"
        }
        require(config.rolloutTurnHorizon == null || !reuseConfig.enabled) {
            "Completed-turn horizons do not support retained trace reuse"
        }
        require(leafEvaluationStrategy.supportsTraceReuse || !reuseConfig.enabled) {
            "The configured leaf evaluator has not passed reuse-specific validation"
        }
    }

    fun invalidateReuse() {
        retainedTraces = emptyList()
        continuityEpoch = null
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
    ): TerminalPolicyContinuation {
        require(childDepth > 0)
        require(maximumContinuationPolicyDecisions > 0)
        check(!config.compressPolicySingletonPasses) {
            "Terminal evidence requires the configured uncompressed rollout-policy decision path"
        }
        val world = childWorld.fork()
        val rootAudit = OpponentPolicyDecisionCounter()
        val opponentAudit = OpponentPolicyDecisionCounter()
        var depth = childDepth
        repeat(maximumContinuationPolicyDecisions) {
            world.terminalPayoff(rootPlayer)?.let { payoff ->
                return TerminalPolicyContinuation(
                    payoff,
                    depth - childDepth,
                    rootAudit.summary(),
                    opponentAudit.summary(),
                )
            }
            val expansion = initialExpansion(world)
            check(expansion.candidates.isNotEmpty()) {
                "Terminal continuation reached a nonterminal world without candidates"
            }
            val actor = checkNotNull(world.actorToAct()) {
                "Terminal continuation reached a nonterminal world without an actor"
            }
            val singleton = expansion.exactSingletonPassOrNull().takeIf {
                config.compressPolicySingletonPasses
            }
            val selected = singleton ?: run {
                val policy = if (actor == rootPlayer) rolloutPolicy else rolloutOpponentPolicy
                val policyExpansion = if (world is PolicyAnnotatedSearchWorld) {
                    initialPolicyExpansion(world, policy)
                } else {
                    expansion
                }
                val decision = policy.selectForExpansion(
                    opponentInformation = { world.informationState(actor) },
                    candidates = policyExpansion.candidates,
                    isProfileExhaustive = policyExpansion.isProfileExhaustive,
                    policySeed = ComponentSeeds.derive(
                        searchSeed,
                        simulationIndex,
                        depth,
                        policy.id,
                        "rollout",
                    ),
                    sampleSeed = ComponentSeeds.derive(
                        searchSeed,
                        simulationIndex,
                        depth,
                        "rollout-sample",
                    ),
                )
                check(decision.diagnostic.replacement?.invalidatesEvidence != true) {
                    "Terminal continuation policy replacement invalidates evidence"
                }
                if (actor == rootPlayer) rootAudit.record(decision.diagnostic)
                else opponentAudit.record(decision.diagnostic)
                decision.choice
            }
            val result = world.step(selected)
            check(result.accepted) {
                result.diagnostic ?: "Terminal continuation rejected ${selected.signature}"
            }
            depth++
        }
        world.terminalPayoff(rootPlayer)?.let { payoff ->
            return TerminalPolicyContinuation(
                payoff,
                depth - childDepth,
                rootAudit.summary(),
                opponentAudit.summary(),
            )
        }
        error("Terminal continuation exhausted $maximumContinuationPolicyDecisions policy decisions")
    }

    fun search(
        rootPlayer: String,
        belief: BeliefBatch<Weighted<SearchWorld>>,
        searchSeed: Long,
        beliefContinuityEpoch: Long = 0L,
        simulationWorldSchedule: SimulationWorldSchedule? = null,
        rootSelectionGuidance: RootSelectionGuidance? = null,
    ): InformationSetSearchResult = searchInternal(
        rootPlayer, belief, searchSeed, beliefContinuityEpoch, simulationWorldSchedule, null, rootSelectionGuidance,
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
        require(!reuseConfig.enabled) { "Action-conditional estimates cannot reuse ordinary search traces" }
        require(!config.compressPolicySingletonPasses) { "Action-conditional estimates require uncompressed root-edge accounting" }
        require(config.wallClockBudgetMillis == null) { "Action-conditional estimates require a fixed simulation budget" }
        val result = searchInternal(rootPlayer, belief, searchSeed, 0L, simulationWorldSchedule, rootActionSignature)
        val action = result.candidates.single { it.choice.signature == rootActionSignature }
        check(action.visits == config.simulations && result.candidates.filterNot { it == action }.all { it.visits == 0 })
        return RootActionSearchEstimate(action.choice, action.meanValue, action.visits,
            result.settlementCountsFor(action.choice), result.diagnostics)
    }

    private fun searchInternal(
        rootPlayer: String,
        belief: BeliefBatch<Weighted<SearchWorld>>,
        searchSeed: Long,
        beliefContinuityEpoch: Long,
        simulationWorldSchedule: SimulationWorldSchedule?,
        rootActionSignature: String?,
        rootSelectionGuidance: RootSelectionGuidance? = null,
    ): InformationSetSearchResult {
        require(belief.particles.isNotEmpty())
        require(simulationWorldSchedule == null || !reuseConfig.enabled) {
            "A fixed simulation-world schedule is incompatible with retained-trace reuse"
        }
        require(simulationWorldSchedule == null || simulationWorldSchedule.worlds.size == config.simulations) {
            "A fixed simulation-world schedule must contain exactly ${config.simulations} worlds"
        }
        requireConformantRoot(rootPlayer, belief)
        // Snapshot the caller's map so one immutable preference set owns the entire search.
        val guidance = rootSelectionGuidance?.copy(scores = rootSelectionGuidance.scores.toMap())
        guidance?.let {
            require(!reuseConfig.enabled && !config.compressPolicySingletonPasses && rootActionSignature == null)
            val representative = belief.particles.first().value
            require(representative.actorToAct() == rootPlayer)
            require(representative.informationState(rootPlayer).informationStateDigest == it.informationStateDigest) {
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
            belief.particles.first().value.informationState(rootPlayer).observation.turnNumber
        })
        simulationWorldSchedule?.let { requireConformantRoot(rootPlayer, it.worlds, "Scheduled root world") }
        val rootParticleIndices = productionRootParticleIndices(
            belief.particles.map { it.weight },
            searchSeed,
            config.simulations,
        )
        val tree = linkedMapOf<NodeKey, SearchNode>()
        val rolloutAudit = RolloutPolicyAudit()
        val quiescenceAudit = QuiescenceAudit()
        val workAudit = SearchWorkAudit()
        val transitionCache = if (config.cacheSimulationTransitions) {
            SimulationTransitionCache(workAudit)
        } else {
            null
        }
        val reuseAudit = ReuseAudit()
        var maximumDepth = 0
        var wideningEvents = 0
        if (!reuseConfig.enabled) {
            invalidateReuse()
        } else if (continuityEpoch != null && continuityEpoch != beliefContinuityEpoch) {
            reuseAudit.discard("BELIEF_CONTINUITY_CHANGED", retainedTraces.size)
            retainedTraces = emptyList()
        }
        continuityEpoch = beliefContinuityEpoch

        val rootDescriptor = rootDescriptor(belief.particles.first().value, rootPlayer)
        val reusable = if (reuseConfig.enabled) {
            selectReusableTraces(belief, rootDescriptor, searchSeed, reuseAudit)
        } else {
            emptyList()
        }
        val nextTraces = mutableListOf<SimulationTrace>()
        reusable.forEachIndexed { reuseIndex, match ->
            val trimmed = trimAndRefreshTrace(
                match,
                rootPlayer,
                ComponentSeeds.derive(searchSeed, reuseIndex, "reuse-refresh"),
                rolloutAudit,
                workAudit,
            )
            injectTrace(trimmed, tree, rootPlayer)
            maximumDepth = maxOf(maximumDepth, trimmed.points.maxOfOrNull { it.depth } ?: 0)
            if (trimmed.refreshed) reuseAudit.refreshed++
            nextTraces += trimmed
        }

        val maximumFreshSimulations = config.simulations - reusable.size
        val freshStartedAt = System.nanoTime()
        var freshSimulations = 0
        while (freshSimulations < maximumFreshSimulations) {
            if (
                freshSimulations >= config.minimumSimulations &&
                config.wallClockBudgetMillis != null &&
                System.nanoTime() - freshStartedAt >= config.wallClockBudgetMillis * 1_000_000L
            ) break
            val simulationIndex = freshSimulations
            val particleIndex = rootParticleIndices[simulationIndex]
            workAudit.forks++
            val world = (
                simulationWorldSchedule?.worlds?.get(simulationIndex)
                    ?: belief.particles[particleIndex].value
                ).fork()
            val recorder = if (reuseConfig.enabled) {
                SimulationTraceRecorder(PolicyJson.sha256("$searchSeed:$simulationIndex:fresh-trace"))
            } else {
                null
            }
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
                recorder = recorder,
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
            recorder?.build(outcome)?.let(nextTraces::add)
            freshSimulations++
        }
        if (reuseConfig.enabled) {
            retainedTraces = retainWithinMemoryCap(nextTraces)
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
        return InformationSetSearchResult(
            chosen = chosen.choice,
            rootValue = if (visited.isEmpty()) 0.0 else {
                visited.sumOf { it.meanValue * it.visits } / visited.sumOf { it.visits }
            },
            candidates = statistics,
            candidateSettlementCounts = root.edges.mapValues { (_, edge) -> edge.settlementCounts },
            diagnostics = InformationSetSearchDiagnostics(
                simulations = freshSimulations + reusable.size,
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
                rootRolloutDecisions = rolloutAudit.rootDecisions,
                opponentRolloutDecisions = rolloutAudit.opponentDecisions,
                rootRolloutFallbacks = rolloutAudit.rootFallbacks,
                opponentRolloutFallbacks = rolloutAudit.opponentFallbacks,
                opponentModelPolicyDecisions = rolloutAudit.opponentModelSummary(),
                rootRolloutPolicyDecisions = rolloutAudit.rootSummary(),
                opponentRolloutPolicyDecisions = rolloutAudit.opponentSummary(),
                quiescenceForcedPasses = quiescenceAudit.forcedPasses,
                quiescenceStrategicDecisions = quiescenceAudit.strategicDecisions,
                quiescenceOverflows = quiescenceAudit.overflows,
                quiescenceFallbacks = quiescenceAudit.fallbacks,
                freshSimulations = freshSimulations,
                reusedSimulations = reusable.size,
                refreshedSimulations = reuseAudit.refreshed,
                reuseDiscardReasons = reuseAudit.discards.toSortedMap(),
                retainedTraceCount = retainedTraces.size,
                retainedSnapshotCount = retainedTraces.sumOf { it.points.size + if (it.frontier != null) 1 else 0 },
                compressedPolicySingletonPasses = workAudit.compressedPolicySingletonPasses,
                searchWorldSteps = workAudit.steps,
                reuseTopologyCandidates = reuseAudit.topologyCandidates,
                privateReuseKeyComputations = reuseAudit.privateKeyComputations,
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
                configuredEvaluatorId = config.leaf.evaluator.evaluatorId,
                invokedEvaluatorId = invokedEvaluatorId,
                invokedEvaluatorConfigurationId = invokedEvaluatorConfigurationId,
                evaluatorCalls = workAudit.evaluatorCalls,
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
        tree: MutableMap<NodeKey, SearchNode>,
        searchSeed: Long,
        simulationIndex: Int,
        depth: Int,
        onDepth: (Int) -> Unit,
        onWiden: () -> Unit,
        rolloutAudit: RolloutPolicyAudit,
        quiescenceAudit: QuiescenceAudit,
        recorder: SimulationTraceRecorder?,
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
            recorder?.finish(TraceCutoff.TERMINAL, world, depth, transitionNode?.snapshot)
            return SearchSettlement(it, SearchSettlementOrigin.TERMINAL_PAYOFF)
        }
        if (rolloutTargetTurn != null &&
            world.informationState(rootPlayer).observation.turnNumber >= rolloutTargetTurn
        ) {
            recorder?.finish(TraceCutoff.HORIZON, world, depth, transitionNode?.snapshot)
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
            recorder?.finish(TraceCutoff.HORIZON, world, depth, transitionNode?.snapshot)
            return value
        }

        workAudit.expansions++
        var expansion = initialExpansion(world)
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

        if (!quiescenceMode && config.compressPolicySingletonPasses) {
            val pass = expansion.exactSingletonPassOrNull()
            if (pass != null) {
                transitionCache?.retainDerived(transitionNode, world, DERIVED_BASE)
                val advanced = advanceCached(world, pass, transitionCache, transitionNode, workAudit)
                workAudit.compressedPolicySingletonPasses++
                return simulate(
                    advanced.world, rootPlayer, tree, searchSeed, simulationIndex, depth + 1,
                    onDepth, onWiden, rolloutAudit, quiescenceAudit, recorder, workAudit,
                    transitionCache, advanced.node, quiescenceMode, quiescenceDepth, rolloutTargetTurn,
                )
            }
        }

        // The configured opponent is part of the stochastic environment. Its private actions may
        // legitimately differ between sampled worlds, so they must not become edges in the root
        // player's shared information tree.
        if (actor != rootPlayer) {
            if (world is PolicyAnnotatedSearchWorld) {
                expansion = if (opponentPolicy.requiresPolicyAnnotations) {
                    workAudit.policyAnnotatedExpansions++
                    transitionCache?.annotatedExpansion(transitionNode) {
                        initialPolicyAnnotatedExpansion(world)
                    } ?: initialPolicyAnnotatedExpansion(world)
                } else if (opponentPolicy.requiresProductionAdmission) {
                    initialPolicyAdmissionExpansion(world)
                } else {
                    expansion
                }
            }
            val policySeed = ComponentSeeds.derive(searchSeed, simulationIndex, depth, "opponent")
            val opponentInformation = world.informationState(actor)
            fun computeDistribution(): ProbabilityDistribution<SemanticChoice> {
                transitionCache?.retainDerived(
                    transitionNode,
                    world,
                    DERIVED_BASE or DERIVED_POLICY_EXPANSION or DERIVED_INFORMATION,
                )
                return opponentPolicy.distribution(
                    opponentInformation,
                    expansion.candidates,
                    policySeed,
                )
            }
            val distribution = if (opponentPolicy.distributionIsSeedInvariant) {
                transitionCache?.opponentDistribution(transitionNode, ::computeDistribution)
                    ?: computeDistribution()
            } else {
                computeDistribution()
            }
            val selected = sample(
                distribution,
                SplitMix64(ComponentSeeds.derive(searchSeed, simulationIndex, depth, "opponent-sample")),
            )
            rolloutAudit.recordOpponentModel(
                opponentPolicy.decisionDiagnostic(
                    opponentInformation = opponentInformation,
                    candidates = expansion.candidates,
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
                recorder,
                workAudit,
                transitionCache,
                advanced.node,
                quiescenceMode,
                if (quiescenceMode) quiescenceDepth + 1 else quiescenceDepth,
                rolloutTargetTurn,
            )
        }

        // Only root-player strategic nodes can become a future live search root. Recording
        // opponent and compressed-pass snapshots inflated histories and computed unusable keys.
        val rootInformation = world.informationState(rootPlayer)
        val key = NodeKey(rootInformation.informationStateDigest, actor)
        transitionCache?.retainDerived(
            transitionNode,
            world,
            DERIVED_BASE or DERIVED_INFORMATION,
        )
        val tracePoint = if (!quiescenceMode) {
            recorder?.record(
                world = world,
                informationStateDigest = rootInformation.informationStateDigest,
                actor = actor,
                expansion = expansion,
                depth = depth,
                retainedSnapshot = transitionNode?.snapshot,
            )
        } else {
            null
        }
        val node = tree.getOrPut(key) { SearchNode(expansion, config.initialExpansionLimit) }
        node.requireCompatible(expansion)
        if (node.expansionLimit > config.initialExpansionLimit && world is ProgressiveSearchWorld) {
            expansion = world.expandChoices(node.expansionLimit)
            node.merge(expansion)
        }
        val wideningIndex = config.wideningThresholds.indexOfLast { node.visits >= it }
        if (wideningIndex >= 0 && !node.exhaustive && world is ProgressiveSearchWorld) {
            val desired = config.wideningLimits[wideningIndex]
            if (desired > node.expansionLimit) {
                expansion = world.expandChoices(desired)
                node.expansionLimit = desired
                node.merge(expansion)
                onWiden()
            }
        }

        val edge = if (rootActionSignature == null) selectUct(node, searchSeed, simulationIndex, depth, rootSelectionGuidance)
            else {
                check(depth == 0 && actor == rootPlayer)
                requireNotNull(node.edges[rootActionSignature]) { "Conditioned root action is absent from this world" }
            }
        recorder?.choose(tracePoint, edge.choice)
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
                recorder,
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
                recorder,
                workAudit,
                transitionCache,
                advanced.node,
                quiescenceMode = false,
                quiescenceDepth = 0,
                rolloutTargetTurn = rolloutTargetTurn,
            )
        }
        if (edge.visits == 0) {
            recorder?.finish(
                TraceCutoff.FIRST_EXPANSION,
                advanced.world,
                depth + 1,
                advanced.node?.snapshot,
            )
        }
        node.visits++
        node.valueSum += value.backedValue
        edge.record(value)
        return value
    }

    private fun leafValue(
        world: SearchWorld,
        rootPlayer: String,
        tree: MutableMap<NodeKey, SearchNode>,
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
            recorder = null,
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

    private fun settleStaticLeaf(
        world: SearchWorld,
        rootPlayer: String,
        audit: QuiescenceAudit,
        workAudit: SearchWorkAudit,
    ): StaticLeafSettlement {
        var forcedPasses = 0
        while (true) {
            world.terminalPayoff(rootPlayer)?.let {
                return StaticLeafSettlement.Value(SearchSettlement(it, SearchSettlementOrigin.TERMINAL_PAYOFF))
            }
            workAudit.expansions++
            val expansion = initialExpansion(world)
            val pass = expansion.exactSingletonPassOrNull()
            if (pass != null) {
                if (forcedPasses >= config.maxQuiescenceForcedPasses) {
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
                continue
            }
            if (!isVolatile(world.informationState(rootPlayer))) {
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
        if (leafEvaluationStrategy.unresolvedLeafHandling == UnresolvedLeafHandling.BACK_UP_NEUTRAL) {
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
        val (rawValue, origin) = when (val source = leafEvaluationStrategy.source) {
            is LeafValueSource.Information -> source.evaluator.evaluate(
                world.informationState(rootPlayer),
                rootPlayer,
            ) to source.evaluator.settlementOrigin
            is LeafValueSource.SampledWorld -> world.sampledWorldLeafValue(
                rootPlayer,
                source.invokedEvaluatorId,
            ) to SearchSettlementOrigin.HEURISTIC_SETTLEMENT
        }
        val value = rawValue.coerceIn(-1.0, 1.0)
        workAudit.recordEvaluator(value, System.nanoTime() - started)
        return SearchSettlement(value, origin)
    }

    private fun isVolatile(information: PolicyInformationState): Boolean {
        val observation = information.observation
        if (observation.stack.isNotEmpty()) return true
        if (observation.phase == "COMBAT" && observation.step != "END_COMBAT") return true
        val pendingKind = observation.pendingDecision?.decisionKind.orEmpty()
        if (listOf("Combat", "Damage", "Order").any(pendingKind::contains)) return true
        return observation.zones.asSequence().flatMap { it.cards.asSequence() }.any { card ->
            card.zone == "BATTLEFIELD" && card.toughness != null && card.damageMarked >= card.toughness
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
        workAudit.forks++
        var world = startingWorld.fork()
        var transitionNode = startingTransitionNode
        var depth = startingDepth
        var rolloutDecisions = 0
        while (rolloutTargetTurn != null || depth < config.maxPolicyDecisions) {
            world.terminalPayoff(rootPlayer)?.let {
                return SearchSettlement(it, SearchSettlementOrigin.TERMINAL_PAYOFF)
            }
            if (rolloutTargetTurn != null) {
                if (world.informationState(rootPlayer).observation.turnNumber >= rolloutTargetTurn) {
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
            workAudit.expansions++
            val expansion = initialExpansion(world)
            if (expansion.candidates.isEmpty()) break
            val actor = world.actorToAct() ?: break
            val singleton = expansion.exactSingletonPassOrNull().takeIf {
                config.compressPolicySingletonPasses
            }
            var policyInformationUsed = false
            val selected = singleton ?: run {
                // The outer opponent remains an independently configurable stochastic environment.
                val policy = if (actor == rootPlayer) rolloutPolicy else rolloutOpponentPolicy
                val policyExpansion = if (world is PolicyAnnotatedSearchWorld) {
                    initialPolicyExpansion(world, policy, workAudit)
                } else {
                    expansion
                }
                val decision = policy.selectForExpansion(
                    opponentInformation = { world.informationState(actor).also { policyInformationUsed = true } },
                    candidates = policyExpansion.candidates,
                    isProfileExhaustive = policyExpansion.isProfileExhaustive,
                    policySeed = ComponentSeeds.derive(
                        searchSeed,
                        simulationIndex,
                        depth,
                        policy.id,
                        "rollout",
                    ),
                    sampleSeed = ComponentSeeds.derive(searchSeed, simulationIndex, depth, "rollout-sample"),
                )
                audit.record(actor == rootPlayer, decision.diagnostic)
                (policy as? BoundedRolloutObserver)?.observeBoundedRollout(
                    { world.informationState(actor) }, policyExpansion.candidates,
                    policyExpansion.isProfileExhaustive, decision.choice, searchSeed, simulationIndex, depth)
                decision.choice
            }
            // Cache exact world prefixes, never the rollout policy's distribution or sampled choice.
            // The same node may later enter the tree with a different opponent policy or wider menu.
            transitionCache?.retainDerived(
                transitionNode, world,
                DERIVED_BASE or (if (singleton == null) DERIVED_POLICY_EXPANSION else 0) or
                    (if (policyInformationUsed) DERIVED_INFORMATION else 0),
            )
            val advanced = advanceCached(world, selected, transitionCache, transitionNode, workAudit, rollout = true)
            world = advanced.world
            transitionNode = advanced.node
            if (singleton != null) workAudit.compressedPolicySingletonPasses++
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
        if (!leafEvaluationStrategy.settleAtRolloutHorizon) {
            return staticLeafValue(world, rootPlayer, workAudit)
        }
        return when (val settled = settleStaticLeaf(world, rootPlayer, quiescenceAudit, workAudit)) {
            is StaticLeafSettlement.Value -> settled.settlement
            StaticLeafSettlement.VolatileBranch -> {
                quiescenceAudit.fallbacks++
                unresolvedLeafValue(world, rootPlayer, quiescenceAudit, workAudit)
            }
        }
    }

    private sealed interface StaticLeafSettlement {
        data class Value(val settlement: SearchSettlement) : StaticLeafSettlement
        data object VolatileBranch : StaticLeafSettlement
    }

    private class QuiescenceAudit {
        var forcedPasses = 0
        var strategicDecisions = 0
        var overflows = 0
        var fallbacks = 0
        var unresolvedBackups = 0
    }

    private class RolloutPolicyAudit {
        private val opponentModel = OpponentPolicyDecisionCounter()
        private val root = OpponentPolicyDecisionCounter()
        private val opponent = OpponentPolicyDecisionCounter()

        val rootDecisions: Int get() = root.summary().decisions
        val opponentDecisions: Int get() = opponent.summary().decisions
        val rootFallbacks: Int get() = root.summary().replacementDecisions
        val opponentFallbacks: Int get() = opponent.summary().replacementDecisions

        fun recordOpponentModel(diagnostic: OpponentPolicyDecisionDiagnostic) {
            opponentModel.record(diagnostic)
        }

        fun record(rootSeat: Boolean, diagnostic: OpponentPolicyDecisionDiagnostic) {
            if (rootSeat) {
                root.record(diagnostic)
            } else {
                opponent.record(diagnostic)
            }
        }

        fun opponentModelSummary(): OpponentPolicyDecisionSummary = opponentModel.summary()
        fun rootSummary(): OpponentPolicyDecisionSummary = root.summary()
        fun opponentSummary(): OpponentPolicyDecisionSummary = opponent.summary()
    }

    private class SearchWorkAudit {
        var expansions = 0
        var forks = 0
        var steps = 0
        var compressedPolicySingletonPasses = 0
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
        var evaluatorNanos = 0L
        private var evaluatorChecksum = 1_125_899_906_842_597L

        fun recordEvaluator(value: Double, elapsedNanos: Long) {
            evaluatorCalls++
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
                audit.forks++
                audit.transitionCacheSnapshots++
                node.snapshot = world.fork()
            }
            if (node.derivedFeatures and features == features) return
            audit.transitionCacheDerivedSnapshots++
            val transferred = (node.snapshot as? DerivedCacheTransferSearchWorld)
                ?.copyDerivedCachesFrom(world) == true
            if (!transferred) {
                audit.forks++
                node.snapshot = world.fork()
            }
            node.derivedFeatures = node.derivedFeatures or features
        }

        fun annotatedExpansion(
            node: SimulationTransitionNode?,
            compute: () -> PolicyExpansion,
        ): PolicyExpansion {
            if (node?.snapshot == null) return compute()
            node.annotatedExpansion?.let {
                audit.policyAnnotationCacheHits++
                return it
            }
            audit.policyAnnotationCacheMisses++
            return compute().also { node.annotatedExpansion = it }
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
                audit.forks++
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
            audit.forks++
            audit.transitionCacheSnapshots++
            val child = SimulationTransitionNode(world.fork())
            node.children[choice.signature] = child
            return CachedAdvance(world, child)
        }
    }

    private class SimulationTransitionNode(var snapshot: SearchWorld? = null) {
        val children = mutableMapOf<String, SimulationTransitionNode>()
        var derivedFeatures: Int = 0
        var annotatedExpansion: PolicyExpansion? = null
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

    private class ReuseAudit {
        val discards = linkedMapOf<String, Int>()
        var refreshed = 0
        var topologyCandidates = 0
        var privateKeyComputations = 0

        fun discard(reason: String, count: Int = 1) {
            if (count > 0) discards[reason] = (discards[reason] ?: 0) + count
        }
    }

    private enum class TraceCutoff { TERMINAL, HORIZON, FIRST_EXPANSION }

    private data class RootDescriptor(
        val informationStateDigest: String,
        val actor: String?,
        val proposalVersion: String,
        val proposalSeed: Long,
        val candidateSignatures: Set<String>,
    )

    private class TracePoint(
        val descriptor: RootDescriptor,
        val expansion: PolicyExpansion,
        val world: SearchWorld,
        var depth: Int,
        var choice: SemanticChoice? = null,
    ) {
        private var keyComputed = false
        private var cachedPrivateKey: SearchWorldReuseKey? = null

        fun privateKey(audit: ReuseAudit): SearchWorldReuseKey? {
            if (!keyComputed) {
                audit.privateKeyComputations++
                cachedPrivateKey = (world as? ReusableSearchWorld)?.privateSearchReuseKey()
                keyComputed = true
            }
            return cachedPrivateKey
        }

        fun shifted(shift: Int): TracePoint = TracePoint(
            descriptor = descriptor,
            expansion = expansion,
            world = world,
            depth = depth - shift,
            choice = choice,
        )
    }

    private data class SimulationTrace(
        val id: String,
        val points: List<TracePoint>,
        val settlement: SearchSettlement,
        val cutoff: TraceCutoff,
        val frontier: SearchWorld?,
        val frontierDepth: Int,
        val refreshed: Boolean = false,
    )

    private inner class SimulationTraceRecorder(private val id: String) {
        private val points = mutableListOf<TracePoint>()
        private var cutoff: TraceCutoff? = null
        private var frontier: SearchWorld? = null
        private var frontierDepth: Int = 0

        fun record(
            world: SearchWorld,
            informationStateDigest: String,
            actor: String?,
            expansion: PolicyExpansion,
            depth: Int,
            retainedSnapshot: SearchWorld?,
        ): Int {
            points += TracePoint(
                descriptor = descriptor(informationStateDigest, actor, expansion),
                expansion = expansion,
                // Transition-cache snapshots are immutable search checkpoints. A trace only
                // reads/forks them, so retaining the same exact checkpoint avoids another full
                // engine/history fork for every simulation and every recorded strategic node.
                world = retainedSnapshot ?: world.fork(),
                depth = depth,
            )
            return points.lastIndex
        }

        fun choose(point: Int?, choice: SemanticChoice) {
            if (point != null) points[point].choice = choice
        }

        fun finish(
            reason: TraceCutoff,
            world: SearchWorld,
            depth: Int,
            retainedSnapshot: SearchWorld? = null,
        ) {
            if (cutoff != null) return
            cutoff = reason
            frontier = retainedSnapshot ?: world.fork()
            frontierDepth = depth
        }

        fun build(settlement: SearchSettlement): SimulationTrace? {
            if (points.isEmpty()) return null
            return SimulationTrace(
                id = id,
                points = points.toList(),
                settlement = settlement,
                cutoff = cutoff ?: TraceCutoff.FIRST_EXPANSION,
                frontier = frontier,
                frontierDepth = frontierDepth,
            )
        }
    }

    private data class TraceMatch(
        val trace: SimulationTrace,
        val pointIndex: Int,
        val score: Double,
    )

    private fun selectReusableTraces(
        belief: BeliefBatch<Weighted<SearchWorld>>,
        root: RootDescriptor,
        searchSeed: Long,
        audit: ReuseAudit,
    ): List<TraceMatch> {
        if (retainedTraces.isEmpty()) return emptyList()
        val topology = retainedTraces.mapNotNull { trace ->
            val points = trace.points.withIndex().filter { (_, point) ->
                point.descriptor == root && point.choice != null
            }
            if (points.isEmpty()) {
                audit.discard("ROOT_TOPOLOGY_MISS")
                null
            } else {
                audit.topologyCandidates += points.size
                trace to points
            }
        }
        if (topology.isEmpty()) return emptyList()
        val support = linkedMapOf<SearchWorldReuseKey, Double>()
        for (particle in belief.particles) {
            audit.privateKeyComputations++
            val key = (particle.value as? ReusableSearchWorld)?.privateSearchReuseKey()
            if (key == null) {
                audit.discard("PRIVATE_REUSE_KEY_UNAVAILABLE", topology.size)
                return emptyList()
            }
            support[key] = (support[key] ?: 0.0) + particle.weight
        }
        val matches = topology.mapNotNull { (trace, topologyPoints) ->
            val matched = topologyPoints.firstOrNull { (_, point) -> point.privateKey(audit) in support }
            if (matched == null) {
                audit.discard("POSTERIOR_SUPPORT_MISS")
                return@mapNotNull null
            }
            val mass = support.getValue(requireNotNull(matched.value.privateKey(audit)))
            val draw = SplitMix64(ComponentSeeds.derive(searchSeed, trace.id, "reuse-reservoir"))
                .nextDouble().coerceAtLeast(1e-15)
            TraceMatch(trace, matched.index, -kotlin.math.ln(draw) / mass.coerceAtLeast(1e-15))
        }
        val minimumFresh = minOf(config.simulations, reuseConfig.minimumFreshSimulations)
        val fractionLimit = kotlin.math.floor(config.simulations * reuseConfig.maximumReuseFraction).toInt()
        val maximum = minOf(config.simulations - minimumFresh, fractionLimit).coerceAtLeast(0)
        return matches.sortedWith(compareBy<TraceMatch> { it.score }.thenBy { it.trace.id }).take(maximum)
    }

    private fun retainWithinMemoryCap(traces: List<SimulationTrace>): List<SimulationTrace> {
        val snapshotCap = config.simulations * config.maxPolicyDecisions
        var snapshots = 0
        return buildList {
            for (trace in traces.take(config.simulations)) {
                val traceSnapshots = trace.points.size + if (trace.frontier != null) 1 else 0
                if (snapshots + traceSnapshots > snapshotCap) continue
                add(trace)
                snapshots += traceSnapshots
            }
        }
    }

    private fun trimAndRefreshTrace(
        match: TraceMatch,
        rootPlayer: String,
        refreshSeed: Long,
        rolloutAudit: RolloutPolicyAudit,
        workAudit: SearchWorkAudit,
    ): SimulationTrace {
        val shift = match.trace.points[match.pointIndex].depth
        val points = match.trace.points.drop(match.pointIndex).map { point ->
            point.shifted(shift)
        }
        var settlement = match.trace.settlement
        var frontier = match.trace.frontier
        var refreshed = false
        if (match.trace.cutoff == TraceCutoff.HORIZON && shift > 0 && frontier != null) {
            val refreshedFrontier = refreshFrontier(
                frontier,
                shift,
                rootPlayer,
                refreshSeed,
                rolloutAudit,
                workAudit,
            )
            settlement = refreshedFrontier.first
            frontier = refreshedFrontier.second
            refreshed = true
        }
        return SimulationTrace(
            id = match.trace.id,
            points = points,
            settlement = settlement,
            cutoff = match.trace.cutoff,
            frontier = frontier,
            frontierDepth = (match.trace.frontierDepth - shift).coerceAtLeast(0) + if (refreshed) shift else 0,
            refreshed = refreshed,
        )
    }

    private fun refreshFrontier(
        startingWorld: SearchWorld,
        debt: Int,
        rootPlayer: String,
        refreshSeed: Long,
        rolloutAudit: RolloutPolicyAudit,
        workAudit: SearchWorkAudit,
    ): Pair<SearchSettlement, SearchWorld> {
        workAudit.forks++
        val world = startingWorld.fork()
        repeat(debt) { refreshDepth ->
            world.terminalPayoff(rootPlayer)?.let {
                return SearchSettlement(it, SearchSettlementOrigin.TERMINAL_PAYOFF) to world
            }
            workAudit.expansions++
            val expansion = initialExpansion(world)
            if (expansion.candidates.isEmpty()) return staticLeafValue(world, rootPlayer, workAudit) to world
            val actor = world.actorToAct() ?: return staticLeafValue(world, rootPlayer, workAudit) to world
            val singleton = expansion.exactSingletonPassOrNull().takeIf {
                config.compressPolicySingletonPasses
            }
            val selected = singleton ?: run {
                val policy = if (actor == rootPlayer) rolloutPolicy else rolloutOpponentPolicy
                val policyExpansion = if (world is PolicyAnnotatedSearchWorld) {
                    initialPolicyExpansion(world, policy, workAudit)
                } else {
                    expansion
                }
                val decision = policy.selectForExpansion(
                    opponentInformation = { world.informationState(actor) },
                    candidates = policyExpansion.candidates,
                    isProfileExhaustive = policyExpansion.isProfileExhaustive,
                    policySeed = ComponentSeeds.derive(
                        refreshSeed,
                        refreshDepth,
                        policy.id,
                        "frontier-policy",
                    ),
                    sampleSeed = ComponentSeeds.derive(refreshSeed, refreshDepth, "frontier-sample"),
                )
                rolloutAudit.record(actor == rootPlayer, decision.diagnostic)
                decision.choice
            }
            workAudit.steps++
            val result = world.step(selected)
            if (!result.accepted) {
                workAudit.rejectedTransitions++
                throw RejectedSearchTransitionException(selected.signature, result.diagnostic)
            }
            if (singleton != null) workAudit.compressedPolicySingletonPasses++
        }
        return (world.terminalPayoff(rootPlayer)?.let {
            SearchSettlement(it, SearchSettlementOrigin.TERMINAL_PAYOFF)
        } ?: staticLeafValue(world, rootPlayer, workAudit)) to world
    }

    private fun injectTrace(
        trace: SimulationTrace,
        tree: MutableMap<NodeKey, SearchNode>,
        rootPlayer: String,
    ) {
        trace.points.forEach { point ->
            val choice = point.choice ?: return@forEach
            if (point.descriptor.actor != rootPlayer) return@forEach
            if (config.compressPolicySingletonPasses && point.expansion.exactSingletonPassOrNull() == choice) {
                return@forEach
            }
            val key = NodeKey(point.descriptor.informationStateDigest, point.descriptor.actor)
            val node = tree.getOrPut(key) { SearchNode(point.expansion, config.initialExpansionLimit) }
            node.requireCompatible(point.expansion)
            val edge = node.edges[choice.signature] ?: return@forEach
            node.visits++
            node.valueSum += trace.settlement.backedValue
            edge.record(trace.settlement)
        }
    }

    private fun rootDescriptor(world: SearchWorld, rootPlayer: String): RootDescriptor {
        val expansion = initialExpansion(world)
        val information = world.informationState(rootPlayer)
        return descriptor(information.informationStateDigest, world.actorToAct(), expansion)
    }

    private fun initialExpansion(world: SearchWorld): PolicyExpansion =
        if (world is ProgressiveSearchWorld) {
            world.expandChoices(config.initialExpansionLimit)
        } else {
            world.expandChoices()
        }

    private fun initialPolicyAdmissionExpansion(world: PolicyAnnotatedSearchWorld): PolicyExpansion =
        world.expandChoicesForPolicyAdmission(config.initialExpansionLimit)

    private fun initialPolicyAnnotatedExpansion(world: PolicyAnnotatedSearchWorld): PolicyExpansion =
        world.expandChoicesWithPolicyAnnotations(config.initialExpansionLimit)

    private fun initialPolicyExpansion(
        world: PolicyAnnotatedSearchWorld,
        policy: OpponentPolicy,
        workAudit: SearchWorkAudit? = null,
    ): PolicyExpansion = if (policy.requiresPolicyAnnotations) {
        if (workAudit != null) workAudit.policyAnnotatedExpansions++
        initialPolicyAnnotatedExpansion(world)
    } else if (policy.requiresProductionAdmission) {
        initialPolicyAdmissionExpansion(world)
    } else {
        initialExpansion(world)
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

    private fun nodeKey(world: SearchWorld, rootPlayer: String): NodeKey {
        val rootInformation = world.informationState(rootPlayer)
        return NodeKey(
            informationStateDigest = rootInformation.informationStateDigest,
            actor = world.actorToAct(),
        )
    }

    private data class NodeKey(
        val informationStateDigest: String,
        val actor: String?,
    )

    private class SearchNode(expansion: PolicyExpansion, var expansionLimit: Int) {
        val edges = linkedMapOf<String, SearchEdge>()
        var visits: Int = 0
        var valueSum: Double = 0.0
        var exhaustive: Boolean = expansion.isExhaustive
        private val initialCandidateSignatures = expansion.candidates.mapTo(
            linkedSetOf(),
            SemanticChoice::signature,
        )
        private val proposalVersion = expansion.proposalVersion
        private val proposalSeed = expansion.proposalSeed
        private var lastCompatibleExpansion: PolicyExpansion? = expansion

        init { merge(expansion) }

        fun requireCompatible(expansion: PolicyExpansion) {
            if (lastCompatibleExpansion === expansion) return
            if (proposalVersion != expansion.proposalVersion ||
                proposalSeed != expansion.proposalSeed ||
                initialCandidateSignatures.size != expansion.candidates.size ||
                expansion.candidates.any { it.signature !in initialCandidateSignatures }
            ) {
                throw InformationSetConformanceException(
                    "One policy information state produced incompatible semantic candidate families"
                )
            }
            lastCompatibleExpansion = expansion
        }

        fun merge(expansion: PolicyExpansion) {
            expansion.candidates.forEach { choice -> edges.putIfAbsent(choice.signature, SearchEdge(choice)) }
            exhaustive = expansion.isExhaustive
        }
    }

    private class SearchEdge(val choice: SemanticChoice) {
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

        private fun <T> sample(distribution: ProbabilityDistribution<T>, random: SplitMix64): T {
            val target = random.nextDouble()
            var cumulative = 0.0
            for (entry in distribution.entries) {
                cumulative += entry.probability
                if (target < cumulative) return entry.value
            }
            return distribution.entries.last().value
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
        data class RootContract(
            val informationStateDigest: String,
            val actor: String?,
            val proposalVersion: String,
            val proposalSeed: Long,
            val candidateSignatures: Set<String>,
        )
        val contracts = worlds.map { world ->
            // Root contract methods are semantically read-only. Running them on the retained
            // particle lets state-owning worlds cache the verified expansion so exact simulation
            // forks do not rebuild the same candidate family for every simulation.
            val expansion = initialExpansion(world)
            val information = world.informationState(rootPlayer)
            RootContract(
                informationStateDigest = information.informationStateDigest,
                actor = world.actorToAct(),
                proposalVersion = expansion.proposalVersion,
                proposalSeed = expansion.proposalSeed,
                candidateSignatures = expansion.candidates.mapTo(linkedSetOf(), SemanticChoice::signature),
            )
        }
        val representative = contracts.first()
        val mismatch = contracts.indexOfFirst { it != representative }
        if (mismatch >= 0) {
            throw InformationSetConformanceException(
                "$subject $mismatch disagrees with ${subject.lowercase()} 0 about policy-visible state or semantic candidates"
            )
        }
    }
}
