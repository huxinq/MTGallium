package org.mtgallium.agent.infoset.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** A diagnostic bound, never a simulation stopping rule. */
@Serializable
data class TerminalContinuationTracePolicy(val maximumCapturedDecisions: Int = 16) {
    init { require(maximumCapturedDecisions in 1..256) }
}

@Serializable
enum class TerminalContinuationStop { TERMINAL_PAYOFF, DECISION_CAP, NON_GAME_FAILURE }

/**
 * Each input belongs only to its named actor. A sequence combines different players' private
 * views in sampled hypotheses and is PRIVATE research diagnosis, never a root-player input,
 * a public replay, a factual game record, or evidence for a posterior distribution.
 */
@Serializable
data class TerminalContinuationTrace(
    val schemaVersion: Int = 1,
    val authority: String = "private-actor-scoped-hypothesis-diagnostic-v1",
    val rootPlayer: String,
    val capturePolicy: TerminalContinuationTracePolicy,
    val decisions: List<TerminalContinuationDecisionWitness>,
    val observedDecisions: Int,
    val stop: TerminalContinuationStop,
    val terminalPayoff: Double? = null,
    val failureCode: String? = null,
    /** Snapshot construction only; excludes selection, execution and final JSON/file writing. */
    val captureMillis: Double,
) {
    init {
        require(schemaVersion == 1 && authority == "private-actor-scoped-hypothesis-diagnostic-v1")
        require(rootPlayer.isNotBlank() && observedDecisions >= decisions.size)
        require(decisions.size <= capturePolicy.maximumCapturedDecisions)
        require(decisions.map { it.decisionIndex } == decisions.indices.toList())
        require((terminalPayoff != null) == (stop == TerminalContinuationStop.TERMINAL_PAYOFF))
        require(terminalPayoff == null || terminalPayoff.isFinite() && terminalPayoff in -1.0..1.0)
        require((failureCode != null) == (stop == TerminalContinuationStop.NON_GAME_FAILURE))
        require(captureMillis.isFinite() && captureMillis >= 0)
    }
}

@Serializable
data class TerminalContinuationDecisionWitness(
    val decisionIndex: Int,
    val actor: String,
    /** Full represented safe history and exact knowledge, not just the current observation. */
    val information: InformationStateRepresentation?,
    val informationUnavailable: String? = null,
    /** The complete supplied admission, in the order actually passed to the policy. */
    val admittedMenu: List<SemanticChoice>,
    val isProfileExhaustive: Boolean,
    /** Null only for the explicitly conditioned root action. */
    val policy: OpponentPolicyBehaviorSpecification?,
    val policySeed: Long? = null,
    val sampleSeed: Long? = null,
    val selectedSignature: String,
    val selectionDiagnostic: OpponentPolicyDecisionDiagnostic? = null,
    val accepted: Boolean?,
    /** This is the same actor's post-transition view, including legitimately revealed events. */
    val afterInformation: InformationStateRepresentation? = null,
    val afterInformationUnavailable: String? = null,
    val nextActor: String? = null,
    /** Generic policies do not expose a numeric score; never infer it from the selected action. */
    val scoreReadout: String = "not-captured-use-bound-policy-and-actual-input-for-reconstruction",
) {
    init {
        require(decisionIndex >= 0 && actor.isNotBlank())
        require(admittedMenu.isNotEmpty() && admittedMenu.map { it.signature }.distinct().size == admittedMenu.size)
        require(admittedMenu.any { it.signature == selectedSignature })
        require(information == null || information.observation.perspectivePlayerId == actor && information.actingPlayerId == actor)
        require(afterInformation == null || afterInformation.observation.perspectivePlayerId == actor)
        require((information == null) == (informationUnavailable != null))
        require((policy == null) == (policySeed == null && sampleSeed == null && selectionDiagnostic == null))
        require(policy == null || policySeed != null && sampleSeed != null && selectionDiagnostic != null)
    }
}

/** One continuation owns a collector. Captured objects are detached before the world advances. */
class TerminalContinuationTraceCollector(val policy: TerminalContinuationTracePolicy, val rootPlayer: String) {
    private val decisions = mutableListOf<TerminalContinuationDecisionWitness>()
    private var observed = 0
    private var captureNanos = 0L
    private var outcome: TerminalContinuationStop? = null
    private var payoff: Double? = null
    private var failure: String? = null

    fun recordDecision(
        information: () -> InformationStateRepresentation, actor: String, candidates: List<SemanticChoice>,
        complete: Boolean, selected: OpponentPolicyDecision, declaredPolicy: OpponentPolicyBehaviorSpecification?,
        policySeed: Long? = null, sampleSeed: Long? = null,
    ) {
        check(outcome == null)
        val index = observed++
        if (index >= policy.maximumCapturedDecisions) return
        val started = System.nanoTime()
        val input = runCatching { snapshot(information()) }
        val menu = PolicyJson.format.decodeFromString<List<SemanticChoice>>(PolicyJson.format.encodeToString(candidates))
        decisions += TerminalContinuationDecisionWitness(index, actor, input.getOrNull(),
            input.exceptionOrNull()?.let { "${it::class.simpleName}: safe input unavailable" },
            menu, complete, declaredPolicy, policySeed, sampleSeed, selected.choice.signature,
            selected.diagnostic.takeIf { declaredPolicy != null }, accepted = null)
        captureNanos += System.nanoTime() - started
    }

    fun recordTransition(accepted: Boolean, afterInformation: () -> InformationStateRepresentation, nextActor: String?) {
        if (observed > policy.maximumCapturedDecisions) return
        val started = System.nanoTime()
        val after = runCatching { snapshot(afterInformation()) }
        val previous = decisions.last()
        decisions[decisions.lastIndex] = previous.copy(accepted = accepted, afterInformation = after.getOrNull(),
            afterInformationUnavailable = after.exceptionOrNull()?.let { "${it::class.simpleName}: safe input unavailable" },
            nextActor = nextActor)
        captureNanos += System.nanoTime() - started
    }

    fun finish(stop: TerminalContinuationStop, terminalPayoff: Double? = null, failureCode: String? = null) {
        if (outcome != null) return
        outcome = stop
        payoff = terminalPayoff
        failure = failureCode
    }

    fun result(): TerminalContinuationTrace = TerminalContinuationTrace(rootPlayer = rootPlayer, capturePolicy = policy,
        decisions = decisions.toList(), observedDecisions = observed, stop = checkNotNull(outcome),
        terminalPayoff = payoff, failureCode = failure, captureMillis = captureNanos / 1_000_000.0)

    private fun snapshot(information: InformationStateRepresentation): InformationStateRepresentation =
        PolicyJson.format.decodeFromString(PolicyJson.format.encodeToString(information))
}
