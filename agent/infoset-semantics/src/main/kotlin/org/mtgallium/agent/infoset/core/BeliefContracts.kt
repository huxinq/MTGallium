package org.mtgallium.agent.infoset.core

/** Serializable information-safe belief metadata; no hypothesis or maintenance algorithm. */
@kotlinx.serialization.Serializable
data class BeliefDiagnostics(
    val mode: BeliefMode,
    val requestedParticles: Int,
    val acceptedParticles: Int,
    val rejectedParticles: Int,
    val effectiveSampleSizeBefore: Double,
    val effectiveSampleSizeAfter: Double,
    val entropy: Double,
    val resamplingCount: Int,
    val marginalCardProbabilities: Map<String, Double> = emptyMap(),
    val modelSensitivity: Double? = null,
    val failures: Map<String, Int> = emptyMap(),
    val architecture: BeliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
    val knowledgeDigest: String? = null,
    val strata: List<BeliefStratumDiagnostic> = emptyList(),
    val proposalAttempts: Int = 0,
    /** Sampled private-opponent decisions made while advancing hidden worlds. */
    val opponentPolicyDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
)

@kotlinx.serialization.Serializable
enum class BeliefArchitecture {
    /** Fresh known-deck determinization from the current safe snapshot. */
    SNAPSHOT_A_V1,
    /** Sequential complete-world particles with consistency or policy-conditioned updates. */
    SEQUENTIAL_B_V1,
    /** Exact deterministic constraints plus weighted residual complete-world particles. */
    HYBRID_C_V1,
    /** True authoritative world; offline diagnostics only. */
    PRIVILEGED_O_V1,
}

@kotlinx.serialization.Serializable
data class BeliefStratumDiagnostic(
    val id: String,
    val exactMass: Double,
    val particles: Int,
) {
    init {
        require(exactMass.isFinite() && exactMass in 0.0..1.0)
        require(particles >= 0)
    }
}

@kotlinx.serialization.Serializable
enum class BeliefMode { CONSISTENCY_ONLY_V1, POLICY_CONDITIONED_V1 }
