package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

/** Shared admission owner for ordinary search, including observer-only roots. */
internal fun SearchWorld.initialPolicyChoices(limit: Int): ActionMenu =
    if (actorToAct() != null) decisionContext(MenuRequest(limit)).menu
    else if (this is ProgressiveSearchWorld) expandChoices(limit) else expandChoices()
