package org.mtgallium.agent.infoset.core

interface PolicyComponent {
    val id: String
    /** Opt out only for a declared policy over the plain semantic proposal menu, without production anchors. */
    val requiresProductionAdmission: Boolean get() = true
    /** True only when selection, distribution or diagnostics consume optional policy annotations. */
    val requiresPolicyAnnotations: Boolean get() = false

    /**
     * Versioned behavior supplied to policy and dataset fingerprints. Implementations with
     * configurable behavior must override this rather than relying on the declared report id.
     */
    val behaviorSpecification: OpponentPolicyBehaviorSpecification
        get() = OpponentPolicyBehaviorSpecification(
            requiresProductionAdmission = requiresProductionAdmission,
            implementationId = "opaque-declared-policy-v1",
            declaredId = id,
            distributionIsSeedInvariant = false,
        )

    /** Mixtures attribute a sampled action by a posterior component draw. */
    fun decisionDiagnostic(context: DecisionSiteRequest, chosen: SemanticChoice, policySeed: Long,
        attributionSeed: Long): OpponentPolicyDecisionDiagnostic = OpponentPolicyDecisionDiagnostic(
            declaredPolicyId = id, selectedComponentId = id)

    /** Information-safe diagnostic hook for policies that can fall back from a preferred choice. */
    fun usedFallback(candidates: List<SemanticChoice>): Boolean = false

}

/** Selects actions without requiring an enumerable action distribution. */
interface ActionSelector : PolicyComponent {
    /** A selector may inspect only the requested context; menu-only selectors inspect expansion alone. */
    fun select(context: DecisionSiteRequest, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision
}

/** Models action probabilities without requiring an action selector. */
interface ActionDistributionModel : PolicyComponent {
    fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice>

    /** Enables exact per-state distribution memoization; sampling remains independently seeded. */
    val distributionIsSeedInvariant: Boolean get() = false

    override val behaviorSpecification: OpponentPolicyBehaviorSpecification
        get() = OpponentPolicyBehaviorSpecification(
            requiresProductionAdmission = requiresProductionAdmission,
            implementationId = "opaque-declared-policy-v1",
            declaredId = id,
            distributionIsSeedInvariant = distributionIsSeedInvariant,
        )

}

/** Combined action-selection and distribution contract. */
interface OpponentPolicy : ActionSelector, ActionDistributionModel {
    /** Samples one action and returns the component/replacement record for that exact site. */
    override fun select(context: DecisionSiteRequest, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
        val candidates = context.expansion.candidates
        val chosen = sampleOpponentPolicyDistribution(
            distribution(context, policySeed).requireAdmittedSupport(candidates),
            sampleSeed,
        ).requireAdmittedChoice(candidates)
        return OpponentPolicyDecision(
            choice = chosen,
            diagnostic = decisionDiagnostic(context, chosen, policySeed,
                ComponentSeeds.derive(sampleSeed, id, "component-attribution"),
            ),
        )
    }

}

data class ProbabilityMass<out T>(val value: T, val probability: Double)

/** Admission is exact menu membership, independent of whether the engine could execute a choice. */
fun SemanticChoice.requireAdmittedChoice(candidates: List<SemanticChoice>): SemanticChoice = apply {
    require(this in candidates) { "Policy returned a non-admitted choice: $signature" }
}

/** Validate once when producing a distribution, before it can be sampled or cached. */
fun ProbabilityDistribution<SemanticChoice>.requireAdmittedSupport(
    candidates: List<SemanticChoice>,
): ProbabilityDistribution<SemanticChoice> = apply {
    val admitted = candidates.toHashSet()
    require(entries.all { it.probability == 0.0 || it.value in admitted }) {
        "Policy distribution assigns positive probability to a non-admitted choice"
    }
}

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

/** Scores a player's information-state representation; sampled-world evaluation is a separate route. */
interface InformationStateEvaluator {
    val id: String
    /** Semantic origin of this evaluator's nonterminal leaf values. */
    val settlementOrigin: SearchSettlementOrigin
        get() = SearchSettlementOrigin.HEURISTIC_SETTLEMENT
    fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double
}

/** Evaluator metadata that remains stable across search traces and evidence packets. */
interface ConfiguredInformationStateEvaluator : InformationStateEvaluator {
    val configurationId: String
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

/** Optional diagnostic sink for actual bounded-rollout choices; it cannot replace the choice. */
interface BoundedRolloutObserver {
    fun observeDecision(context: DecisionSiteRequest, choice: SemanticChoice, searchSeed: Long, simulationIndex: Int, depth: Int)
}

data class Weighted<out T>(val value: T, val weight: Double) {
    init {
        require(weight.isFinite() && weight >= 0.0) { "Weight must be finite and non-negative" }
    }
}


/** Translate policy requirements once; the decision source owns the actual admitted expansion. */
fun PolicyComponent.decisionView(limit: Int? = null): DecisionView = DecisionView(limit,
    if (requiresProductionAdmission) DecisionAdmission.PRODUCTION else DecisionAdmission.SEMANTIC, requiresPolicyAnnotations)

/** Safe, deterministic policy preferences. Implementations never receive a sampled world. */
interface RootSelectionPolicy {
    val configurationId: String
    fun scores(site: DecisionSite): Map<String, Double> = scores(site.information(), site.expansion.candidates)
    fun scores(information: InformationStateRepresentation, candidates: List<SemanticChoice>): Map<String, Double>
}


/** Actual-player policy over an admitted context, independent of any search result or engine. */
interface DecisionPolicy {
    val configurationId: String
    /** Null declares that this policy does not select at this site; the host owns any fallback. */
    fun choose(context: DecisionSiteRequest, decisionSeed: Long): SemanticChoice?
}
