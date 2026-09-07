package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.SemanticHeuristicOpponentPolicy

/**
 * Experimental continuation over the plain semantic menu. This changes action admission and the
 * non-casting policy; it is not an acceleration of the historical Production continuation.
 * The frozen kernel stays within its casting-context scope, even when used for the other actor.
 */
internal class FastKernelRolloutPolicy(
    model: RootActionKernelModel,
    fitIdentity: String,
    manifestSha256: String,
) : OpponentPolicy {
    override val id = "cast-kernel-plain-menu-semantic-continuation-v1"
    override val requiresProductionAdmission = false
    override val distributionIsSeedInvariant = true
    private val scorer = CompiledRootActionKernel(model)
    private val outsideScope = SemanticHeuristicOpponentPolicy(requiresProductionAdmission = false)
    override val behaviorSpecification = OpponentPolicyBehaviorSpecification(
        implementationId = id, declaredId = id,
        distributionIsSeedInvariant = distributionIsSeedInvariant,
        requiresProductionAdmission = requiresProductionAdmission,
        parameters = mapOf(
            "fitIdentity" to fitIdentity, "manifestSha256" to manifestSha256,
            "featureSchema" to model.featureSchema, "scorer" to COMPILED_ROOT_ACTION_KERNEL_ID,
            "menuSource" to "plain-semantic-without-production-anchors-v1",
            "scope" to "supplied-menu-contains-cast-spell-v1",
            "selection" to "raw-score-argmax-first-menu-order-v1",
            "outsideScope" to "declared-semantic-score-distribution-v1",
        ),
        components = listOf(OpponentPolicyComponentSpecification(1.0, outsideScope.behaviorSpecification)),
    )

    private fun learnedScope(candidates: List<SemanticChoice>) =
        candidates.any { it.operationFamily == SemanticOperationFamily.CAST_SPELL }

    override fun distribution(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> {
        require(opponentInformation.actingPlayerId == opponentInformation.observation.perspectivePlayerId)
        if (!learnedScope(candidates)) return outsideScope.distribution(opponentInformation, candidates, policySeed)
        val scores = scorer.scores(rootActionKernelFeatures(opponentInformation, candidates))
        val selected = scores.indices.maxBy { scores[it] }
        return ProbabilityDistribution.normalized(candidates.mapIndexed { index, choice ->
            ProbabilityMass(choice, if (index == selected) 1.0 else 0.0)
        })
    }

    override fun decisionDiagnostic(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        chosen: SemanticChoice,
        policySeed: Long,
        attributionSeed: Long,
    ): OpponentPolicyDecisionDiagnostic = if (learnedScope(candidates)) {
        OpponentPolicyDecisionDiagnostic(declaredPolicyId = id, selectedComponentId = id)
    } else {
        // A declared continuation component, not missing-annotation failure or runtime replacement.
        outsideScope.decisionDiagnostic(opponentInformation, candidates, chosen, policySeed, attributionSeed)
            .copy(declaredPolicyId = id)
    }
}

internal fun RootKernelFitReference.loadFastRolloutPolicy(): FastKernelRolloutPolicy =
    FastKernelRolloutPolicy(loadFrozenModel().model, researchRunIdentity, manifestSha256)
