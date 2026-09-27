package org.mtgallium.agent.infoset.argentum

import org.mtgallium.agent.infoset.core.DecisionPoint

/** Immutable safe projection; semantic reference groups do not establish conditioning equivalence. */
class ArgentumPolicyDecisionProjection internal constructor(
    val site: DecisionPoint,
    val semanticReferenceGroups: Map<String, List<String>>,
)
