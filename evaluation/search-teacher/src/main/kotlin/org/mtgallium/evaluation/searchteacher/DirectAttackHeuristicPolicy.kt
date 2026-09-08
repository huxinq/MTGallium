package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.json.encodeToJsonElement
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.DirectRootSelectionPolicy

/** Same actual-action role as the attack kernel, sampling the configured frozen incumbent. */
internal class DirectAttackHeuristicPolicy(private val incumbent: FastKernelRolloutPolicy) : DirectRootSelectionPolicy {
    override val configurationId = "direct-attack-heuristic-v1:" +
        "profile-complete-pure-attack-decline-menu-2-through-8:no-accidental-omissions:" +
        "full-distribution-splitmix64:" +
        "ComponentSeeds(searchSeed,direct-attack-heuristic-policy-v1):" +
        "ComponentSeeds(searchSeed,direct-attack-heuristic-sample-v1):" +
        PolicyJson.digest(PolicyJson.format.encodeToJsonElement(incumbent.behaviorSpecification))

    override fun select(information: () -> PolicyInformationState, expansion: PolicyExpansion): SemanticChoice? {
        if (!directAttackScope(expansion)) return null
        error("Direct attack heuristic requires the per-decision search seed")
    }

    override fun select(information: () -> PolicyInformationState, expansion: PolicyExpansion, searchSeed: Long): SemanticChoice? {
        if (!directAttackScope(expansion)) return null
        val state = information()
        require(state.actingPlayerId == state.observation.perspectivePlayerId)
        return sampleOpponentPolicyDistribution(
            incumbent.distribution(state, expansion.candidates,
                ComponentSeeds.derive(searchSeed, "direct-attack-heuristic-policy-v1")),
            ComponentSeeds.derive(searchSeed, "direct-attack-heuristic-sample-v1"),
        )
    }
}
