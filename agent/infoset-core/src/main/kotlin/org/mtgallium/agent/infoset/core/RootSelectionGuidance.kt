package org.mtgallium.agent.infoset.core

import kotlinx.serialization.Serializable

const val ROOT_SELECTION_GUIDANCE_RULE = "root-progressive-bias-unit-weight-v1"

/** Safe, deterministic policy preferences. Implementations never receive a sampled world. */
interface RootSelectionPolicy {
    val configurationId: String
    fun scores(information: PolicyInformationState, candidates: List<SemanticChoice>): Map<String, Double>
}

/**
 * Selection only: highest score orders unvisited edges, then score / (1 + visits) augments UCT.
 * Scores do not initialize visits, enter backups, or alter the final visit-based winner rule.
 * The first version requires an exhaustive initial menu and disables reuse and compression.
 */
@Serializable
data class RootSelectionGuidance(
    val configurationId: String,
    val informationStateDigest: String,
    val scores: Map<String, Double>,
    val rule: String = ROOT_SELECTION_GUIDANCE_RULE,
) {
    init {
        require(configurationId.isNotBlank() && informationStateDigest.isNotBlank())
        require(rule == ROOT_SELECTION_GUIDANCE_RULE)
        require(scores.isNotEmpty() && scores.keys.all { it.isNotBlank() })
        require(scores.values.all { it.isFinite() && it in -1.0..1.0 })
    }
}
