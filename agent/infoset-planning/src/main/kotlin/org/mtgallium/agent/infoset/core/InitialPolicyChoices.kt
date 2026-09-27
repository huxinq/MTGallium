package org.mtgallium.agent.infoset.core

/** Shared admission owner for ordinary search, including observer-only roots. */
internal fun SearchWorld.initialPolicyChoices(limit: Int): PolicyExpansion =
    if (actorToAct() != null) decisionContext(DecisionView(limit)).expansion
    else if (this is ProgressiveSearchWorld) expandChoices(limit) else expandChoices()
