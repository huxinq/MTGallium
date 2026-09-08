package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.DirectRootSelectionPolicy

/** Frozen attack kernel at the actual decision boundary; simulated continuations are untouched. */
internal class DirectAttackKernelPolicy(
    model: RootActionKernelModel,
    fitIdentity: String,
    manifestSha256: String,
) : DirectRootSelectionPolicy {
    private val scorer = CompiledRootActionKernel(model)
    override val configurationId = "direct-attack-kernel-v1:$fitIdentity:$manifestSha256:" +
        "${model.featureSchema}:$COMPILED_ROOT_ACTION_KERNEL_ID:" +
        "profile-complete-pure-attack-decline-menu-2-through-8:raw-score-argmax-first-menu-order"

    override fun select(information: () -> PolicyInformationState, expansion: PolicyExpansion): SemanticChoice? {
        if (!directAttackScope(expansion)) return null
        val state = information()
        require(state.actingPlayerId == state.observation.perspectivePlayerId)
        val scores = scorer.scores(rootActionKernelFeatures(state, expansion.candidates))
        require(scores.all { it.isFinite() })
        return expansion.candidates[scores.indices.maxBy { scores[it] }]
    }
}

internal fun RootKernelFitReference.loadDirectAttackPolicy() =
    DirectAttackKernelPolicy(loadFrozenModel().model, researchRunIdentity, manifestSha256)

/** Shared actual-action scope, including the absence of accidental proposal omissions. */
internal fun directAttackScope(expansion: PolicyExpansion): Boolean =
    attackKernelScope(expansion.candidates, expansion.isProfileExhaustive) &&
        expansion.omissionReasons.none { !it.intentionalProfileOmission }
