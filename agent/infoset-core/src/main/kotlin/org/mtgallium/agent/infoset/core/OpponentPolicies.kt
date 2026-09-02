package org.mtgallium.agent.infoset.core

data class OpponentPolicyMixtureEntry(
    val policy: OpponentPolicy,
    val weight: Double,
) {
    init {
        require(weight.isFinite() && weight >= 0.0)
    }
}

class MixtureOpponentPolicy(
    override val id: String,
    private val components: List<OpponentPolicyMixtureEntry>,
) : OpponentPolicy {
    override val distributionIsSeedInvariant: Boolean = components.all {
        it.policy.distributionIsSeedInvariant
    }
    init {
        require(components.isNotEmpty())
        require(components.sumOf { it.weight } > 0.0)
    }

    override val behaviorSpecification: OpponentPolicyBehaviorSpecification
        get() = OpponentPolicyBehaviorSpecification(
            implementationId = "weighted-mixture-with-posterior-attribution-v2",
            declaredId = id,
            distributionIsSeedInvariant = distributionIsSeedInvariant,
            parameters = mapOf(
                "decisionComponentAttribution" to "posterior-given-sampled-action-v1",
            ),
            components = components.map { component ->
                OpponentPolicyComponentSpecification(
                    weight = component.weight,
                    policy = component.policy.behaviorSpecification,
                )
            },
        )

    override fun distribution(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> {
        val totals = candidates.associate { it.signature to 0.0 }.toMutableMap()
        val normalizer = components.sumOf { it.weight }
        for ((componentIndex, component) in components.withIndex()) {
            val distribution = component.policy.distribution(
                opponentInformation,
                candidates,
                ComponentSeeds.derive(policySeed, componentIndex, component.policy.id),
            )
            for (mass in distribution.entries) {
                totals.computeIfPresent(mass.value.signature) { _, current ->
                    current + component.weight / normalizer * mass.probability
                }
            }
        }
        return ProbabilityDistribution.normalized(candidates.map { choice ->
            ProbabilityMass(choice, totals.getValue(choice.signature))
        })
    }

    override fun decisionDiagnostic(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        chosen: SemanticChoice,
        policySeed: Long,
        attributionSeed: Long,
    ): OpponentPolicyDecisionDiagnostic {
        val contributions = components.mapIndexed { componentIndex, component ->
            val componentSeed = ComponentSeeds.derive(policySeed, componentIndex, component.policy.id)
            val probability = component.policy.distribution(
                opponentInformation,
                candidates,
                componentSeed,
            ).probabilityOf { it.signature == chosen.signature }
            Triple(component, componentSeed, component.weight * probability)
        }
        val selectedIndex = samplePositiveWeights(
            contributions.map { it.third },
            attributionSeed,
        )
        val (selected, selectedPolicySeed) = contributions[selectedIndex].let { it.first to it.second }
        val nested = selected.policy.decisionDiagnostic(
            opponentInformation = opponentInformation,
            candidates = candidates,
            chosen = chosen,
            policySeed = selectedPolicySeed,
            attributionSeed = ComponentSeeds.derive(
                attributionSeed,
                selectedIndex,
                selected.policy.id,
                "nested-component-attribution",
            ),
        )
        return OpponentPolicyDecisionDiagnostic(
            declaredPolicyId = id,
            selectedComponentId = selected.policy.id,
            effectivePolicyId = nested.effectivePolicyId,
            replacement = nested.replacement,
        )
    }
}

object UniformOpponentPolicy : OpponentPolicy {
    override val id: String = "uniform-v1"
    override val distributionIsSeedInvariant: Boolean = true
    override val behaviorSpecification: OpponentPolicyBehaviorSpecification =
        OpponentPolicyBehaviorSpecification(
            implementationId = "uniform-distribution-v1",
            declaredId = id,
            distributionIsSeedInvariant = distributionIsSeedInvariant,
        )

    override fun distribution(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> = ProbabilityDistribution.uniform(candidates)
}

internal fun <T> sampleOpponentPolicyDistribution(
    distribution: ProbabilityDistribution<T>,
    seed: Long,
): T {
    val target = SplitMix64(seed).nextDouble()
    var cumulative = 0.0
    distribution.entries.forEach { entry ->
        cumulative += entry.probability
        if (target < cumulative) return entry.value
    }
    return distribution.entries.last().value
}

private fun samplePositiveWeights(weights: List<Double>, seed: Long): Int {
    require(weights.isNotEmpty())
    require(weights.all { it.isFinite() && it >= 0.0 })
    val total = weights.sum()
    require(total > 0.0) { "A sampled action must have positive mass under at least one component" }
    val target = SplitMix64(seed).nextDouble() * total
    var cumulative = 0.0
    weights.forEachIndexed { index, weight ->
        cumulative += weight
        if (target < cumulative) return index
    }
    return weights.lastIndex
}
