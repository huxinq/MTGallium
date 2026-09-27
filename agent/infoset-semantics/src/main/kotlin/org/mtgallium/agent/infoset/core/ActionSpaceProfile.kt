package org.mtgallium.agent.infoset.core

import kotlinx.serialization.Serializable

/** Versioned policy-level action-space contracts. */
@Serializable @kotlinx.serialization.SerialName("org.mtgallium.agent.infoset.core.SearchActionSpaceProfile")
enum class ActionSpaceProfile(
    val profileId: String,
    val rulesEquivalent: Boolean,
    val suppressesStandaloneManaAbilities: Boolean,
) {
    RULES_EXACT_V1(
        profileId = "rules-exact-v1",
        rulesEquivalent = true,
        suppressesStandaloneManaAbilities = false,
    ),
    MONO_RED_FAST_MANA_PRUNED_V1(
        profileId = "mono-red-fast-mana-pruned-v1",
        rulesEquivalent = false,
        suppressesStandaloneManaAbilities = true,
    );
}
