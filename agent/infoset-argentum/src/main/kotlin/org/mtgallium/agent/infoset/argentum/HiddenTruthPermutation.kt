package org.mtgallium.agent.infoset.argentum

/** A rejected proposal never exposes its world to the policy comparison. */
data class HiddenTruthPermutation(
    val world: ArgentumSearchWorld?,
    val rejection: String?,
    val changedObjects: Int,
)
