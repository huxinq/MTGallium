package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory

/** Experimental root continuation; its learned scope is the casting-context population evaluated offline. */
internal class RootKernelRolloutPolicy(
    model: RootActionKernelModel,
    fitIdentity: String,
    manifestSha256: String,
    private val production: OpponentPolicy = SearchTeacherSearchFactory.rootRolloutPolicy(),
) : OpponentPolicy {
    override val id = "cast-context-root-kernel-rollout-v1"
    // Non-casting contexts must receive the same production annotation as the control.
    override val requiresPolicyAnnotations = production.requiresPolicyAnnotations
    override val distributionIsSeedInvariant = production.distributionIsSeedInvariant
    private val scorer = CompiledRootActionKernel(model)
    override val behaviorSpecification = OpponentPolicyBehaviorSpecification(
        implementationId = id, declaredId = id,
        distributionIsSeedInvariant = distributionIsSeedInvariant,
        parameters = mapOf(
            "fitIdentity" to fitIdentity, "manifestSha256" to manifestSha256,
            "featureSchema" to model.featureSchema, "scorer" to COMPILED_ROOT_ACTION_KERNEL_ID,
            "scope" to "admitted-menu-contains-cast-spell-v1",
            "selection" to "raw-score-argmax-first-menu-order-v1",
            "outsideScope" to "delegate-production-distribution-and-diagnostic-v1",
        ),
        components = listOf(OpponentPolicyComponentSpecification(1.0, production.behaviorSpecification)),
    )

    private fun learnedScope(candidates: List<SemanticChoice>): Boolean =
        candidates.any { it.operationFamily == SemanticOperationFamily.CAST_SPELL }

    override fun distribution(opponentInformation: PolicyInformationState, candidates: List<SemanticChoice>, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
        if (!learnedScope(candidates)) return production.distribution(opponentInformation, candidates, policySeed)
        // Only the supplied acting-player view and admitted menu enter the frozen model.
        val scores = scorer.scores(rootActionKernelFeatures(opponentInformation, candidates))
        val selected = scores.indices.maxBy { scores[it] }
        return ProbabilityDistribution.normalized(candidates.mapIndexed { index, choice ->
            ProbabilityMass(choice, if (index == selected) 1.0 else 0.0)
        })
    }

    override fun usedFallback(candidates: List<SemanticChoice>): Boolean =
        !learnedScope(candidates) && production.usedFallback(candidates)

    override fun decisionDiagnostic(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        chosen: SemanticChoice,
        policySeed: Long,
        attributionSeed: Long,
    ): OpponentPolicyDecisionDiagnostic = if (learnedScope(candidates)) {
        OpponentPolicyDecisionDiagnostic(declaredPolicyId = id, selectedComponentId = id)
    } else {
        production.decisionDiagnostic(opponentInformation, candidates, chosen, policySeed, attributionSeed)
            .copy(declaredPolicyId = id)
    }
}

/** Load and authenticate once; inference never accesses evidence files or full engine state. */
internal fun RootKernelFitReference.loadRootRolloutPolicy(): RootKernelRolloutPolicy =
    RootKernelRolloutPolicy(loadFrozenModel().model, researchRunIdentity, manifestSha256)
