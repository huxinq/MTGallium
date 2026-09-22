package org.mtgallium.agent.infoset.core

/** Exact policy-pair terminal execution, independent of tree configuration and leaf evaluation. */
class TerminalPolicyContinuationRunner(
    private val rolloutPolicy: ActionSelector,
    private val rolloutOpponentPolicy: ActionSelector,
    private val initialExpansionLimit: Int,
) {
    init {
        require(initialExpansionLimit > 0)
        listOf(rolloutPolicy, rolloutOpponentPolicy).forEach { policy ->
            require(!policy.requiresPolicyAnnotations || policy.requiresProductionAdmission) {
                "Policy annotations require production admission"
            }
        }
    }

    /**
     * Continues an already-applied first edge to an actual engine terminal state with the exact
     * configured root/opponent rollout-policy pair and seed derivation. This diagnostic seam never
     * invokes a leaf evaluator or converts exhaustion, missing choices, rejection, or policy
     * replacement into a payoff.
     */
    fun continueToTerminal(
        childWorld: SearchWorld,
        rootPlayer: String,
        searchSeed: Long,
        simulationIndex: Int,
        childDepth: Int = 1,
        maximumContinuationPolicyDecisions: Int = 4096,
        trace: TerminalContinuationTraceCollector? = null,
        /**
         * Host-side accepted-decision bookkeeping only. Called exactly once per accepted
         * transition after acceptance is verified; it observes the acting player and the
         * accepted choice and cannot alter selection, stepping or the returned payoff.
         */
        acceptedDecisionObserver: ((actor: String, choice: SemanticChoice) -> Unit)? = null,
    ): TerminalPolicyContinuation {
        require(childDepth > 0)
        require(maximumContinuationPolicyDecisions > 0)
        require(trace == null || trace.rootPlayer == rootPlayer)
        val world = childWorld.fork()
        val rootAudit = OpponentPolicyDecisionCounter()
        val opponentAudit = OpponentPolicyDecisionCounter()
        var depth = childDepth
        try {
            repeat(maximumContinuationPolicyDecisions) {
                world.terminalPayoff(rootPlayer)?.let { payoff ->
                    trace?.finish(TerminalContinuationStop.TERMINAL_PAYOFF, payoff)
                    return TerminalPolicyContinuation(
                        payoff,
                        depth - childDepth,
                        rootAudit.summary(),
                        opponentAudit.summary(),
                    )
                }
                val expansion = world.initialPolicyChoices(initialExpansionLimit)
                check(expansion.candidates.isNotEmpty()) {
                    "Terminal continuation reached a nonterminal world without candidates"
                }
                val actor = checkNotNull(world.actorToAct()) {
                    "Terminal continuation reached a nonterminal world without an actor"
                }
                val selected = run {
                    val policy = if (actor == rootPlayer) rolloutPolicy else rolloutOpponentPolicy
                    val context = world.decisionContext(policy.decisionView(initialExpansionLimit))
                    val policyExpansion = context.expansion
                    val information by lazy(LazyThreadSafetyMode.NONE) { context.information() }
                    val policySeed = ComponentSeeds.derive(searchSeed, simulationIndex, depth, policy.id, "rollout")
                    val sampleSeed = ComponentSeeds.derive(searchSeed, simulationIndex, depth, "rollout-sample")
                    val decision = policy.select(context, policySeed, sampleSeed)
                    decision.choice.requireAdmittedChoice(policyExpansion.candidates)
                    trace?.recordDecision({ information }, actor, policyExpansion.candidates,
                        policyExpansion.isProfileExhaustive, decision, policy.behaviorSpecification, policySeed, sampleSeed)
                    check(decision.diagnostic.replacement?.invalidatesEvidence != true) {
                        "Terminal continuation policy replacement invalidates evidence"
                    }
                    if (actor == rootPlayer) rootAudit.record(decision.diagnostic)
                    else opponentAudit.record(decision.diagnostic)
                    decision.choice
                }
                val result = world.step(selected)
                trace?.recordTransition(result.accepted, { world.informationState(actor) }, world.actorToAct())
                check(result.accepted) {
                    result.diagnostic ?: "Terminal continuation rejected ${selected.signature}"
                }
                acceptedDecisionObserver?.invoke(actor, selected)
                depth++
            }
            world.terminalPayoff(rootPlayer)?.let { payoff ->
                trace?.finish(TerminalContinuationStop.TERMINAL_PAYOFF, payoff)
                return TerminalPolicyContinuation(
                    payoff,
                    depth - childDepth,
                    rootAudit.summary(),
                    opponentAudit.summary(),
                )
            }
            trace?.finish(TerminalContinuationStop.DECISION_CAP)
            error("Terminal continuation exhausted $maximumContinuationPolicyDecisions policy decisions")
        } catch (failure: Exception) {
            trace?.finish(TerminalContinuationStop.NON_GAME_FAILURE, failureCode = failure::class.simpleName ?: "Exception")
            throw failure
        }
    }

}

/** Shared admission owner for search and explicit terminal execution. */
internal fun SearchWorld.initialPolicyChoices(limit: Int): PolicyExpansion =
    if (actorToAct() != null) decisionContext(DecisionView(limit)).expansion
    else if (this is ProgressiveSearchWorld) expandChoices(limit) else expandChoices()

internal fun PolicyAnnotatedSearchWorld.choicesForPolicy(policy: PolicyComponent, limit: Int): PolicyExpansion =
    decisionContext(policy.decisionView(limit)).expansion
