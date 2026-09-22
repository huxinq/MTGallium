package org.mtgallium.agent.infoset.argentum

import org.mtgallium.agent.infoset.core.DecisionSite

/** Immutable safe projection; semantic reference groups do not establish conditioning equivalence. */
class ArgentumPolicyDecisionProjection internal constructor(
    val site: DecisionSite,
    val semanticReferenceGroups: Map<String, List<String>>,
)
