package org.mtgallium.agent.searchteacher

import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.SemanticChoice

/** Actual-player selection only. Null delegates to the unchanged Search Teacher path. */
interface DirectRootSelectionPolicy {
    val configurationId: String
    fun select(information: () -> PolicyInformationState, expansion: PolicyExpansion): SemanticChoice?

    /** Per-decision seed; deterministic implementations retain their original behavior. */
    fun select(information: () -> PolicyInformationState, expansion: PolicyExpansion, searchSeed: Long): SemanticChoice? =
        select(information, expansion)
}
