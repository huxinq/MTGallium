package org.mtgallium.agent.infoset.core

/** Opt-in PUCT prior over the current acting player's captured menu; never receives a world. */
interface SearchPrior {
    val configurationId: String
    /** Menu to score before retaining the highest-prior initialExpansionLimit edges. */
    val candidateLimit: Int
    val admission: DecisionAdmission get() = DecisionAdmission.SEMANTIC
    val explorationConstant: Double
    fun probabilities(context: DecisionSiteRequest): Map<String, Double>
}

/** Select a continuation policy by zero-based rollout step, independently for each simulation. */
interface RolloutPolicySchedule : ActionSelector {
    fun atStep(step: Int): ActionSelector
}
