package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.ParticleRejuvenator

/** Retained identity for the original copying-only maintenance boundary. */
const val CONDITIONED_BELIEF_MAINTENANCE_V1 = "copy-only-refuse-knowledge-reconstruction-v1"

/** Joint action/observation conditioning precedes the copying-only resampling step. */
const val CONDITIONED_BELIEF_MAINTENANCE_V2 = "condition-before-resample-copy-only-refuse-rebuild-v2"

/**
 * The observed-action likelihood uses the conditioning policy's declared decision view, and each
 * resampled duplicate keeps its hidden state but draws a fresh future-chance stream.
 */
const val CONDITIONED_BELIEF_MAINTENANCE_V3 = "policy-view-likelihood-fresh-chance-copy-refuse-rebuild-v3"

/** Inference-model component of a conditioned belief snapshot's identity. */
internal const val CONDITIONED_BELIEF_INFERENCE_MAINTENANCE =
    "descendant-information-knowledge-fresh-chance-copying-resampling-v2"

internal fun conditionedBeliefMaintenanceIdentity(mode: BeliefMode): String? =
    if (mode == BeliefMode.POLICY_CONDITIONED_V1) CONDITIONED_BELIEF_MAINTENANCE_V3 else null

/**
 * Resampling copies a particle's complete hidden state. Only its future game-chance stream is
 * redrawn, so duplicated hypotheses do not also replay identical future draws and shuffles.
 */
internal val FRESH_CHANCE_COPY = ParticleRejuvenator { world, _, seed ->
    (world as? ArgentumSearchWorld ?: error("Conditioned resampling received an untrusted world implementation"))
        .forkForHypotheticalSearch(seed)
}

/** A non-game stop: no qualified reconstruction of the current action-conditioned population exists. */
class ConditionedBeliefReconstructionRequired(
    val reasonCode: String,
    val exactObservationFailure: org.mtgallium.agent.infoset.core.ExactObservationFailureReport? = null,
    cause: Throwable? = null,
) : IllegalStateException(
    "CONDITIONED_BELIEF_RECONSTRUCTION_REQUIRED:$reasonCode", cause,
)
