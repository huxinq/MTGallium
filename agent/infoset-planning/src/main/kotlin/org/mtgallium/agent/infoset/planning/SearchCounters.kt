package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

internal const val DERIVED_BASE = 1
internal const val DERIVED_POLICY_EXPANSION = 2
internal const val DERIVED_INFORMATION = 4

/** Work, quiescence and rollout counters of one search, and their diagnostics mapping. */
internal class SearchCounters {
    val opponentModel = OpponentPolicyDecisionCounter()
    private val rootRollout = OpponentPolicyDecisionCounter()
    private val opponentRollout = OpponentPolicyDecisionCounter()
    var forcedPasses = 0
    var profileForcedPasses = 0
    var strategicDecisions = 0
    var overflows = 0
    var fallbacks = 0
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
    private var evaluatorCalls = 0
    private var nonQuietLeafEvaluations = 0
    private var evaluatorNanos = 0L
    private var evaluatorChecksum = 1_125_899_906_842_597L

    fun recordRollout(rootSeat: Boolean, diagnostic: OpponentPolicyDecisionDiagnostic) {
        (if (rootSeat) rootRollout else opponentRollout).record(diagnostic)
    }

    fun recordEvaluator(value: Double, elapsedNanos: Long, nonQuiet: Boolean) {
        evaluatorCalls++
        if (nonQuiet) nonQuietLeafEvaluations++
        evaluatorNanos += elapsedNanos.coerceAtLeast(0L)
        evaluatorChecksum = evaluatorChecksum * 31L + value.toBits()
    }

    fun diagnostics(
        simulations: Int,
        particles: Int,
        nodes: Int,
        maximumDepth: Int,
        exhaustiveNodes: Int,
        wideningEvents: Int,
        opponentModelId: String,
        leaf: LeafEvaluationDiagnostic,
        rootRolloutPolicyId: String?,
        opponentRolloutPolicyId: String?,
        evaluatorId: String,
        evaluatorConfigurationId: String,
    ): InformationSetSearchDiagnostics {
        val root = rootRollout.summary()
        val opponent = opponentRollout.summary()
        return InformationSetSearchDiagnostics(
            simulations = simulations,
            particles = particles,
            nodes = nodes,
            maximumDepth = maximumDepth,
            exhaustiveNodes = exhaustiveNodes,
            nonExhaustiveNodes = nodes - exhaustiveNodes,
            wideningEvents = wideningEvents,
            opponentModelId = opponentModelId,
            leaf = leaf,
            rootRolloutPolicyId = rootRolloutPolicyId,
            opponentRolloutPolicyId = opponentRolloutPolicyId,
            rootRolloutDecisions = root.decisions,
            opponentRolloutDecisions = opponent.decisions,
            rootRolloutFallbacks = root.replacementDecisions,
            opponentRolloutFallbacks = opponent.replacementDecisions,
            opponentModelPolicyDecisions = opponentModel.summary(),
            rootRolloutPolicyDecisions = root,
            opponentRolloutPolicyDecisions = opponent,
            quiescenceForcedPasses = forcedPasses,
            quiescenceProfileForcedPasses = profileForcedPasses,
            quiescenceStrategicDecisions = strategicDecisions,
            quiescenceOverflows = overflows,
            quiescenceFallbacks = fallbacks,
            searchWorldSteps = steps,
            policyAnnotatedExpansions = policyAnnotatedExpansions,
            transitionCacheHits = transitionCacheHits,
            transitionCacheMisses = transitionCacheMisses,
            transitionCacheSnapshots = transitionCacheSnapshots,
            transitionCacheDerivedSnapshots = transitionCacheDerivedSnapshots,
            rolloutTransitionCacheHits = rolloutTransitionCacheHits,
            rolloutTransitionCacheSnapshots = rolloutTransitionCacheSnapshots,
            rolloutTransitionCacheBypasses = rolloutTransitionCacheBypasses,
            policyAnnotationCacheHits = policyAnnotationCacheHits,
            policyAnnotationCacheMisses = policyAnnotationCacheMisses,
            opponentDistributionCacheHits = opponentDistributionCacheHits,
            opponentDistributionCacheMisses = opponentDistributionCacheMisses,
            rejectedTransitions = rejectedTransitions,
            evaluatorId = evaluatorId,
            evaluatorConfigurationId = evaluatorConfigurationId,
            evaluatorCalls = evaluatorCalls,
            nonQuietLeafEvaluations = nonQuietLeafEvaluations,
            evaluatorNanos = evaluatorNanos,
            evaluatorOutputChecksum = java.lang.Long.toUnsignedString(evaluatorChecksum, 16).padStart(16, '0'),
        )
    }
}
