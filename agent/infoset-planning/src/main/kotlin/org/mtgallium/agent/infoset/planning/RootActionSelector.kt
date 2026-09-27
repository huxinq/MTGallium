package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

/** Live admitted-menu dispatch, independent of engine worlds and search construction. */
class RootActionSelector(
    private val singletonSelectionEnabled: Boolean,
    private val directPolicy: DecisionPolicy?,
) {
    fun select(context: DecisionContext, searchSeed: Long, fallback: () -> RootActionSelection): RootActionSelection =
        dispatch(context.menu, { directPolicy?.choose(context, searchSeed) }, fallback)

    private fun dispatch(menu: ActionMenu, direct: () -> SemanticChoice?,
        fallback: () -> RootActionSelection): RootActionSelection {
        check(menu.candidates.isNotEmpty()) { "No semantic candidates are available" }
        menu.exactSingletonPassOrNull()?.let { return RootActionSelection.RulesForcedPass(it) }
        if (singletonSelectionEnabled && menu.isProfileExhaustive &&
            menu.omissionReasons.all { it.intentionalProfileOmission }
        ) {
            menu.candidates.singleOrNull()?.let { choice ->
                if (choice.kind == SemanticChoiceKind.ACTION &&
                    choice.operationFamily != SemanticOperationFamily.MULLIGAN &&
                    choice.operationFamily != SemanticOperationFamily.DECISION_RESPONSE
                ) return RootActionSelection.PolicySingletonAction(choice)
            }
        }
        direct()?.let { choice ->
            require(choice in menu.candidates) { "Direct root policy returned a non-admitted choice" }
            return RootActionSelection.DirectPolicyAction(choice)
        }
        return fallback()
    }
}
