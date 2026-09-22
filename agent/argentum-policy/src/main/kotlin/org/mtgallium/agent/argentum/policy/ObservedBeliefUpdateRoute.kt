package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.infoset.core.SemanticOperationFamily

/** Public-safe provenance of the update actually requested, including deliberate compatibility. */
enum class ObservedBeliefUpdateRoute {
    HISTORICAL_SIGNATURE_V1,
    QUALIFIED_EXACT_MEMBER_V1,
    UNSUPPORTED_FAMILY_SIGNATURE_COMPATIBILITY_V1,
    PRIVATE_UNOBSERVED_V1,
}

data class ObservedBeliefUpdateEvidence(
    val route: ObservedBeliefUpdateRoute,
    val family: SemanticOperationFamily,
    val behaviorId: String,
)

const val QUALIFIED_OBSERVED_BELIEF_V1 =
    "qualified-combat-and-pass-exact-member-v1:other-families-explicit-signature-compatibility-v1"
