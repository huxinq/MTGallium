package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.infoset.core.*

internal fun attackKernelScope(candidates: List<SemanticChoice>, complete: Boolean): Boolean =
    complete && candidates.size in 2..8 &&
        candidates.all { it.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS } &&
        candidates.any { it.actionIntent.kind == SemanticActionIntentKind.DECLINE_ATTACK } &&
        candidates.any { it.actionIntent.kind != SemanticActionIntentKind.DECLINE_ATTACK }

/** Root-rollout-only intervention. Completeness comes from the adapter, never from menu size. */
internal class AttackKernelRolloutPolicy(
    model: RootActionKernelModel,
    fitIdentity: String,
    manifestSha256: String,
    private val incumbent: OpponentPolicy,
) : OpponentPolicy {
    override val id = "attack-kernel-complete-small-menu-v1"
    override val requiresProductionAdmission = false
    override val distributionIsSeedInvariant = true
    private val scorer = CompiledRootActionKernel(model)
    init { require(!incumbent.requiresProductionAdmission && !incumbent.requiresPolicyAnnotations) }
    override val behaviorSpecification = OpponentPolicyBehaviorSpecification(
        implementationId = id, declaredId = id, requiresProductionAdmission = false,
        distributionIsSeedInvariant = true,
        parameters = mapOf("fitIdentity" to fitIdentity, "manifestSha256" to manifestSha256,
            "featureSchema" to model.featureSchema, "scorer" to COMPILED_ROOT_ACTION_KERNEL_ID,
            "scope" to "profile-complete-pure-attack-decline-menu-2-through-8-v1",
            "selection" to "raw-score-argmax-first-menu-order-v1"),
        components = listOf(OpponentPolicyComponentSpecification(1.0, incumbent.behaviorSpecification)),
    )

    override fun selectForExpansion(opponentInformation: () -> PolicyInformationState,
        candidates: List<SemanticChoice>, isProfileExhaustive: Boolean,
        policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
        if (!attackKernelScope(candidates, isProfileExhaustive)) return incumbent.selectForExpansion(
            opponentInformation, candidates, isProfileExhaustive, policySeed, sampleSeed).let {
            it.copy(diagnostic = it.diagnostic.copy(declaredPolicyId = id))
        }
        val information = opponentInformation()
        require(information.actingPlayerId == information.observation.perspectivePlayerId)
        val scores = scorer.scores(rootActionKernelFeatures(information, candidates))
        return OpponentPolicyDecision(candidates[scores.indices.maxBy { scores[it] }],
            OpponentPolicyDecisionDiagnostic(declaredPolicyId = id, selectedComponentId = id))
    }

    override fun distribution(opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>, policySeed: Long): ProbabilityDistribution<SemanticChoice> =
        error("Attack rollout requires the caller's expansion-completeness witness; use selectForExpansion")
}

internal fun RootKernelFitReference.loadAttackRolloutPolicy(incumbent: OpponentPolicy): OpponentPolicy =
    AttackKernelRolloutPolicy(loadFrozenModel().model, researchRunIdentity, manifestSha256, incumbent)
