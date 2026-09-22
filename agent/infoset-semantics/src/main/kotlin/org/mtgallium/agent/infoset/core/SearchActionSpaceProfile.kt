package org.mtgallium.agent.infoset.core

import kotlinx.serialization.Serializable

/** Versioned policy-level action-space contracts. */
@Serializable
enum class SearchActionSpaceProfile(
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
    ),
    /**
     * Experiment-only treatment for the issue-0019 standalone-mana timing study.
     *
     * This deliberately changes only the production profile's mana-ability admission switch. It
     * is not a production profile and makes no broader claim of rules equivalence.
     */
    EXPERIMENTAL_STANDALONE_MANA_TIMING_V1(
        profileId = "experimental-standalone-mana-timing-v1",
        rulesEquivalent = false,
        suppressesStandaloneManaAbilities = false,
    ),
}
