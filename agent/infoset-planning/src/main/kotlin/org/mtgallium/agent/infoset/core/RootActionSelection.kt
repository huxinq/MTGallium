package org.mtgallium.agent.infoset.core

/** Live selection, before rebinding or acceptance. Only a searched choice carries search evidence. */
sealed interface RootActionSelection {
    val choice: SemanticChoice

    sealed interface Unsearched : RootActionSelection

    @ConsistentCopyVisibility
    data class RulesForcedPass internal constructor(override val choice: SemanticChoice) : Unsearched

    @ConsistentCopyVisibility
    data class PolicySingletonAction internal constructor(override val choice: SemanticChoice) : Unsearched

    @ConsistentCopyVisibility
    data class DirectPolicyAction internal constructor(override val choice: SemanticChoice) : Unsearched

    /** The chosen action has one owner: callers cannot pair this result with a different choice. */
    data class Searched(val search: InformationSetSearchResult) : RootActionSelection {
        override val choice: SemanticChoice get() = search.chosen
    }
}
