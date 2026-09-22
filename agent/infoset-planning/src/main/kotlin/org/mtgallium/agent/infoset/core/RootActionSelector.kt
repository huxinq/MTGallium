package org.mtgallium.agent.infoset.core

/** Live admitted-menu dispatch, independent of engine worlds and search construction. */
class RootActionSelector(
    private val singletonSelectionEnabled: Boolean,
    private val directPolicy: DecisionPolicy?,
) {
    fun select(context: DecisionSiteRequest, searchSeed: Long, fallback: () -> RootActionSelection): RootActionSelection =
        dispatch(context.expansion, { directPolicy?.choose(context, searchSeed) }, fallback)

    private fun dispatch(expansion: PolicyExpansion, direct: () -> SemanticChoice?,
        fallback: () -> RootActionSelection): RootActionSelection {
        check(expansion.candidates.isNotEmpty()) { "No semantic candidates are available" }
        expansion.exactSingletonPassOrNull()?.let { return RootActionSelection.RulesForcedPass(it) }
        if (singletonSelectionEnabled && expansion.isProfileExhaustive &&
            expansion.omissionReasons.all { it.intentionalProfileOmission }
        ) {
            expansion.candidates.singleOrNull()?.let { choice ->
                if (choice.kind == SemanticChoiceKind.ACTION &&
                    choice.operationFamily != SemanticOperationFamily.MULLIGAN &&
                    choice.operationFamily != SemanticOperationFamily.DECISION_RESPONSE
                ) return RootActionSelection.PolicySingletonAction(choice)
            }
        }
        direct()?.let { choice ->
            require(choice in expansion.candidates) { "Direct root policy returned a non-admitted choice" }
            return RootActionSelection.DirectPolicyAction(choice)
        }
        return fallback()
    }
}
