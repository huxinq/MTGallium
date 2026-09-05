package org.mtgallium.agent.infoset.core

/** A complete sampled game world behind the information firewall. */
interface SearchWorld {
    fun actorToAct(): String?
    fun informationState(viewer: String): PolicyInformationState
    fun expandChoices(): PolicyExpansion
    fun step(choice: SemanticChoice): SearchStepResult
    fun fork(): SearchWorld
    fun terminalPayoff(rootPlayer: String): Double?
    fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double
}

/**
 * Opaque, runtime-only identity for comparing complete sampled worlds during reuse.
 *
 * The digest has no accessor and its textual form is always redacted. It must never be serialized
 * or copied into information-safe diagnostics.
 */
class SearchWorldReuseKey private constructor(private val trustedDigest: String) {
    override fun equals(other: Any?): Boolean =
        other is SearchWorldReuseKey && trustedDigest == other.trustedDigest

    override fun hashCode(): Int = trustedDigest.hashCode()

    override fun toString(): String = "<private-search-world-key>"

    companion object {
        fun fromTrustedDigest(digest: String): SearchWorldReuseKey {
            require(digest.isNotBlank())
            return SearchWorldReuseKey(digest)
        }
    }
}

/** Optional trusted capability; ordinary information-safe worlds need not implement it. */
interface ReusableSearchWorld : SearchWorld {
    fun privateSearchReuseKey(): SearchWorldReuseKey
}

/**
 * Optional trusted capability for refreshing an exact cached snapshot after its source world has
 * materialized lazy, state-derived data. Implementations must reject a source with a different
 * authoritative state or history. This transfers caches only; it must not advance either world.
 */
interface DerivedCacheTransferSearchWorld : SearchWorld {
    fun copyDerivedCachesFrom(source: SearchWorld): Boolean
}

/** Optional extension for deterministic progressive widening above the default 64 choices. */
interface ProgressiveSearchWorld : SearchWorld {
    fun expandChoices(limit: Int): PolicyExpansion
}

/**
 * Optional information-safe policy annotation. Search requests it only where a policy actually
 * consumes annotations; forced-pass and UCT expansion paths use the cheaper semantic family.
 */
interface PolicyAnnotatedSearchWorld : SearchWorld {
    fun expandChoicesWithPolicyAnnotations(): PolicyExpansion
    fun expandChoicesWithPolicyAnnotations(limit: Int): PolicyExpansion
}

data class SearchStepResult(
    val accepted: Boolean,
    val diagnostic: String? = null,
    val forcedTransitions: List<PolicyHistoryEvent> = emptyList(),
    /** True only when the response payload is entitled to its actor and must be masked from others. */
    val privateToActor: Boolean = false,
)

/**
 * Search selected a choice advertised by a sampled world, but that same world refused to apply it.
 * This aborts the simulation; it is not a loss, draw, or heuristic value for either player.
 */
class RejectedSearchTransitionException(
    val choiceSignature: String,
    val rejectionDiagnostic: String?,
) : IllegalStateException(
    "A sampled world rejected an expanded search choice $choiceSignature" +
        (rejectionDiagnostic?.let { ": $it" } ?: ""),
)

interface BeliefWorldSource {
    fun sample(
        rootInformation: PolicyInformationState,
        knownDecks: Map<String, Map<String, Int>>,
        beliefSeed: Long,
        count: Int,
    ): BeliefBatch<Weighted<SearchWorld>>
}

data class Weighted<out T>(val value: T, val weight: Double) {
    init {
        require(weight.isFinite() && weight >= 0.0) { "Weight must be finite and non-negative" }
    }
}

data class BeliefBatch<out T>(
    val particles: List<T>,
    val diagnostics: BeliefDiagnostics,
)

/**
 * Optional fixed-simulation experiment input.
 *
 * Search still draws the ordinary root-particle index for every simulation, but uses the world at
 * the corresponding schedule index. This lets a controlled experiment pair an independently
 * derived future-chance stream across treatments without changing tree policy, rollout policy, or
 * any production caller. Scheduled worlds are runtime-only complete hypotheses and must never be
 * serialized into policy-facing artifacts.
 */
class SimulationWorldSchedule(val worlds: List<SearchWorld>) {
    init {
        require(worlds.isNotEmpty()) { "A simulation-world schedule cannot be empty" }
    }
}

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

interface OpponentPolicy {
    val id: String
    /** Enables exact per-state distribution memoization; sampling remains independently seeded. */
    val distributionIsSeedInvariant: Boolean get() = false

    /**
     * Versioned behavior supplied to policy and dataset fingerprints. Implementations with
     * configurable behavior must override this rather than relying on the declared report id.
     */
    val behaviorSpecification: OpponentPolicyBehaviorSpecification
        get() = OpponentPolicyBehaviorSpecification(
            implementationId = "opaque-declared-policy-v1",
            declaredId = id,
            distributionIsSeedInvariant = distributionIsSeedInvariant,
        )

    /** Information-safe diagnostic hook for policies that can fall back from a preferred choice. */
    fun usedFallback(candidates: List<SemanticChoice>): Boolean = false

    /**
     * Attributes one sampled action to the policy component that generated it. Mixtures override
     * this with a posterior component draw, preserving the declared marginal action distribution.
     */
    fun decisionDiagnostic(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        chosen: SemanticChoice,
        policySeed: Long,
        attributionSeed: Long,
    ): OpponentPolicyDecisionDiagnostic = OpponentPolicyDecisionDiagnostic(
        declaredPolicyId = id,
        selectedComponentId = id,
    )

    /** Samples one action and returns the component/replacement record for that exact site. */
    fun select(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
        sampleSeed: Long,
    ): OpponentPolicyDecision {
        val chosen = sampleOpponentPolicyDistribution(
            distribution(opponentInformation, candidates, policySeed),
            sampleSeed,
        )
        return OpponentPolicyDecision(
            choice = chosen,
            diagnostic = decisionDiagnostic(
                opponentInformation,
                candidates,
                chosen,
                policySeed,
                ComponentSeeds.derive(sampleSeed, id, "component-attribution"),
            ),
        )
    }

    fun distribution(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice>
}

data class ProbabilityMass<out T>(val value: T, val probability: Double)

class ProbabilityDistribution<T> private constructor(
    val entries: List<ProbabilityMass<T>>,
) {
    init {
        require(entries.isNotEmpty()) { "A probability distribution cannot be empty" }
        require(entries.all { it.probability.isFinite() && it.probability >= 0.0 })
        require(kotlin.math.abs(entries.sumOf { it.probability } - 1.0) <= 1e-9) {
            "Probabilities must sum to one"
        }
    }

    fun probabilityOf(predicate: (T) -> Boolean): Double =
        entries.filter { predicate(it.value) }.sumOf { it.probability }

    companion object {
        fun <T> normalized(weighted: List<ProbabilityMass<T>>): ProbabilityDistribution<T> {
            require(weighted.isNotEmpty())
            val total = weighted.sumOf { it.probability }
            require(total.isFinite() && total > 0.0) { "Probability mass must be positive and finite" }
            return ProbabilityDistribution(weighted.map { it.copy(probability = it.probability / total) })
        }

        fun <T> uniform(values: List<T>): ProbabilityDistribution<T> {
            require(values.isNotEmpty())
            val p = 1.0 / values.size
            return ProbabilityDistribution(values.map { ProbabilityMass(it, p) })
        }
    }
}

interface InformationStateEvaluator {
    val id: String
    /** Semantic origin of this evaluator's nonterminal leaf values. */
    val settlementOrigin: SearchSettlementOrigin
        get() = SearchSettlementOrigin.HEURISTIC_SETTLEMENT
    fun evaluate(information: PolicyInformationState, rootPlayer: String): Double
}

/** Evaluator metadata that remains stable across search traces and evidence packets. */
interface ConfiguredInformationStateEvaluator : InformationStateEvaluator {
    val configurationId: String
}

/** Type-safe source for a leaf value; only core can expose a complete sampled world to its route. */
sealed interface LeafValueSource {
    val invokedEvaluatorId: String
    val invokedEvaluatorConfigurationId: String

    data class Information(val evaluator: InformationStateEvaluator) : LeafValueSource {
        override val invokedEvaluatorId: String = evaluator.id
        override val invokedEvaluatorConfigurationId: String =
            (evaluator as? ConfiguredInformationStateEvaluator)?.configurationId ?: evaluator.id
    }

    data class SampledWorld(
        override val invokedEvaluatorId: String,
    ) : LeafValueSource {
        override val invokedEvaluatorConfigurationId: String = invokedEvaluatorId

        init {
            require(invokedEvaluatorId.isNotBlank())
        }
    }
}

enum class UnresolvedLeafHandling { EVALUATE, BACK_UP_NEUTRAL }

/**
 * Runtime-only search semantics supplied by the capability that owns the configured evaluator.
 * Persisted evaluator selection remains in [LeafEvaluationConfig], without teaching core how a
 * particular implementation is routed or settled.
 */
data class LeafEvaluationStrategy(
    val configuredEvaluatorId: String,
    val source: LeafValueSource,
    val supportsTraceReuse: Boolean = true,
    val settleAtRolloutHorizon: Boolean = false,
    val unresolvedLeafHandling: UnresolvedLeafHandling = UnresolvedLeafHandling.EVALUATE,
) {
    init {
        require(configuredEvaluatorId.isNotBlank())
        require(source.invokedEvaluatorId == configuredEvaluatorId) {
            "Configured evaluator $configuredEvaluatorId does not match invoked ${source.invokedEvaluatorId}"
        }
    }
}

object ComponentSeeds {
    private val separator = byteArrayOf(0x1f)

    /** Stable seed derivation; changing parallelism cannot change a component's stream. */
    fun derive(vararg parts: Any?): Long {
        val hasher = reusableSha256()
        parts.forEachIndexed { index, part ->
            if (index > 0) hasher.update(separator)
            hasher.update((part?.toString() ?: "<null>").toByteArray(Charsets.UTF_8))
        }
        val digest = hasher.digest()
        return (0 until Long.SIZE_BYTES).fold(0L) { value, index ->
            (value shl 8) or (digest[index].toLong() and 0xffL)
        }
    }
}
