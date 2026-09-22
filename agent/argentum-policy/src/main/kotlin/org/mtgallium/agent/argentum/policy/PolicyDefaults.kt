package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.infoset.core.OpponentPolicy

/** Frozen continuation defaults shared by direct, terminal and search composition. */
object PolicyDefaults {
    fun rootRolloutPolicy(): OpponentPolicy = DeterminizedArgentumHeuristicOpponentPolicy(
        id = "root-argentum-production-rollout-v2",
    )

    fun opponentRolloutPolicy(): OpponentPolicy = DeterminizedArgentumHeuristicOpponentPolicy(
        id = "opponent-argentum-production-rollout-v2",
    )
}
