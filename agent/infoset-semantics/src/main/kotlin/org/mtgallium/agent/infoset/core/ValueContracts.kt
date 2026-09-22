package org.mtgallium.agent.infoset.core

import kotlinx.serialization.Serializable

/** Why one simulated continuation supplied the scalar that search backed up. */
@Serializable
enum class SearchSettlementOrigin {
    TERMINAL_PAYOFF,
    HEURISTIC_SETTLEMENT,
    LEARNED_OUTCOME_ESTIMATE,
    NEUTRAL_UNRESOLVED_SETTLEMENT,
}
