package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.infoset.core.BeliefMode

/** Retained identity for the original copying-only maintenance boundary. */
const val CONDITIONED_BELIEF_MAINTENANCE_V1 = "copy-only-refuse-knowledge-reconstruction-v1"

/** Joint action/observation conditioning precedes the copying-only resampling step. */
const val CONDITIONED_BELIEF_MAINTENANCE_V2 = "condition-before-resample-copy-only-refuse-rebuild-v2"

internal fun conditionedBeliefMaintenanceIdentity(mode: BeliefMode): String? =
    if (mode == BeliefMode.POLICY_CONDITIONED_V1) CONDITIONED_BELIEF_MAINTENANCE_V2 else null

/** A non-game stop: no qualified reconstruction of the current action-conditioned population exists. */
class ConditionedBeliefReconstructionRequired(
    val reasonCode: String,
    val exactObservationFailure: org.mtgallium.agent.infoset.core.ExactObservationFailureReport? = null,
    cause: Throwable? = null,
) : IllegalStateException(
    "CONDITIONED_BELIEF_RECONSTRUCTION_REQUIRED:$reasonCode", cause,
)
