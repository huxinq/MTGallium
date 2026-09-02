package org.mtgallium.agent.infoset.core

import kotlinx.serialization.Serializable

const val OPPONENT_POLICY_BEHAVIOR_SCHEMA_V1: Int = 1

/**
 * Stable, recursively inspectable description of the behavior supplied by an opponent policy.
 * The declared id remains useful for reports, but mixture membership and weights are recorded
 * separately so changing a composition cannot hide behind an unchanged display id.
 */
@Serializable
data class OpponentPolicyBehaviorSpecification(
    val schemaVersion: Int = OPPONENT_POLICY_BEHAVIOR_SCHEMA_V1,
    val implementationId: String,
    val declaredId: String,
    val distributionIsSeedInvariant: Boolean,
    val parameters: Map<String, String> = emptyMap(),
    val components: List<OpponentPolicyComponentSpecification> = emptyList(),
) {
    init {
        require(schemaVersion == OPPONENT_POLICY_BEHAVIOR_SCHEMA_V1)
        require(implementationId.isNotBlank())
        require(declaredId.isNotBlank())
        require(parameters.keys.all(String::isNotBlank))
    }
}

@Serializable
data class OpponentPolicyComponentSpecification(
    val weight: Double,
    val policy: OpponentPolicyBehaviorSpecification,
) {
    init {
        require(weight.isFinite() && weight >= 0.0)
    }
}

/** Whether a declared replacement may remain in an evidence-producing run. */
@Serializable
enum class OpponentPolicyReplacementEvidenceDisposition {
    INVALIDATES_EVIDENCE,
    PREDECLARED_EVIDENCE_ELIGIBLE,
}

@Serializable
data class OpponentPolicyReplacementDiagnostic(
    val triggerId: String,
    val replacementPolicyId: String,
    val evidenceDisposition: OpponentPolicyReplacementEvidenceDisposition,
) {
    init {
        require(triggerId.isNotBlank())
        require(replacementPolicyId.isNotBlank())
    }

    val invalidatesEvidence: Boolean
        get() = evidenceDisposition == OpponentPolicyReplacementEvidenceDisposition.INVALIDATES_EVIDENCE
}

/** One latent component attribution for one action sampled from a policy distribution. */
@Serializable
data class OpponentPolicyDecisionDiagnostic(
    val declaredPolicyId: String,
    val selectedComponentId: String,
    val effectivePolicyId: String = selectedComponentId,
    val replacement: OpponentPolicyReplacementDiagnostic? = null,
) {
    init {
        require(declaredPolicyId.isNotBlank())
        require(selectedComponentId.isNotBlank())
        require(effectivePolicyId.isNotBlank())
    }
}

data class OpponentPolicyDecision(
    val choice: SemanticChoice,
    val diagnostic: OpponentPolicyDecisionDiagnostic,
)

/** Exact denominators for a set of sampled opponent-policy decisions. */
@Serializable
data class OpponentPolicyDecisionSummary(
    val decisions: Int = 0,
    val selectedComponents: Map<String, Int> = emptyMap(),
    val effectivePolicies: Map<String, Int> = emptyMap(),
    val replacements: Map<String, Int> = emptyMap(),
    val evidenceInvalidatingReplacements: Int = 0,
) {
    init {
        require(decisions >= 0)
        require(selectedComponents.values.all { it > 0 })
        require(effectivePolicies.values.all { it > 0 })
        require(replacements.values.all { it > 0 })
        require(selectedComponents.values.sum() == decisions)
        require(effectivePolicies.values.sum() == decisions)
        require(replacements.values.sum() <= decisions)
        require(evidenceInvalidatingReplacements in 0..replacements.values.sum())
    }

    val replacementDecisions: Int get() = replacements.values.sum()

    operator fun plus(other: OpponentPolicyDecisionSummary): OpponentPolicyDecisionSummary =
        OpponentPolicyDecisionSummary(
            decisions = decisions + other.decisions,
            selectedComponents = mergeCounts(selectedComponents, other.selectedComponents),
            effectivePolicies = mergeCounts(effectivePolicies, other.effectivePolicies),
            replacements = mergeCounts(replacements, other.replacements),
            evidenceInvalidatingReplacements =
                evidenceInvalidatingReplacements + other.evidenceInvalidatingReplacements,
        )

    private fun mergeCounts(left: Map<String, Int>, right: Map<String, Int>): Map<String, Int> =
        (left.keys + right.keys).associateWith { key ->
            left.getOrDefault(key, 0) + right.getOrDefault(key, 0)
        }.filterValues { it > 0 }.toSortedMap()
}

class OpponentPolicyDecisionCounter {
    private var decisions: Int = 0
    private val selectedComponents = mutableMapOf<String, Int>()
    private val effectivePolicies = mutableMapOf<String, Int>()
    private val replacements = mutableMapOf<String, Int>()
    private var evidenceInvalidatingReplacements: Int = 0

    fun record(diagnostic: OpponentPolicyDecisionDiagnostic) {
        decisions++
        selectedComponents.increment(diagnostic.selectedComponentId)
        effectivePolicies.increment(diagnostic.effectivePolicyId)
        diagnostic.replacement?.let { replacement ->
            replacements.increment("${replacement.triggerId}->${replacement.replacementPolicyId}")
            if (replacement.invalidatesEvidence) evidenceInvalidatingReplacements++
        }
    }

    fun summary(): OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(
        decisions = decisions,
        selectedComponents = selectedComponents.toSortedMap(),
        effectivePolicies = effectivePolicies.toSortedMap(),
        replacements = replacements.toSortedMap(),
        evidenceInvalidatingReplacements = evidenceInvalidatingReplacements,
    )

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = getOrDefault(key, 0) + 1
    }
}
