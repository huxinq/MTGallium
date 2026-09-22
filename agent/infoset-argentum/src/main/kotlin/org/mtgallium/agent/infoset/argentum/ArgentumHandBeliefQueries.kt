package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.state.components.identity.CardComponent
import java.util.Collections
import kotlin.math.abs
import org.mtgallium.agent.infoset.core.OpponentHandBeliefQueries
import org.mtgallium.agent.infoset.core.ParticleBelief
import org.mtgallium.agent.infoset.core.Weighted

/** Trusted projection of supplied hypotheses. The caller owns their provenance and perspective consistency. */
object ArgentumHandBeliefQueries {
    fun snapshot(belief: ParticleBelief, viewerAlias: String): OpponentHandBeliefQueries {
        require(viewerAlias.isNotBlank()) { "A belief query needs a viewer alias" }
        val hands = belief.weightedWorlds().map { weighted ->
            val world = weighted.value as? ArgentumSearchWorld
                ?: throw IllegalArgumentException("Hand belief queries require Argentum hypothetical worlds")
            val viewer = world.rawPlayerIds()[viewerAlias]
                ?: throw IllegalArgumentException("Unknown belief query viewer: $viewerAlias")
            val state = world.authoritativeState()
            val counts = state.turnOrder.filter { it != viewer }
                .flatMap { state.getHand(it) }
                .mapNotNull { state.getEntity(it)?.get<CardComponent>()?.name }
                .groupingBy { it }.eachCount()
            Weighted<Map<String, Int>>(counts, weighted.weight)
        }
        return opponentHandQuerySnapshot(viewerAlias, hands)
    }
}

/** Adapter-internal count projection seam for synthetic fixtures; no count tables enter policy APIs. */
internal fun opponentHandQuerySnapshot(
    viewerAlias: String,
    hands: List<Weighted<Map<String, Int>>>,
): OpponentHandBeliefQueries = ParticleHandQuerySnapshot(viewerAlias, hands)

private class ParticleHandQuerySnapshot(
    override val viewerAlias: String,
    hands: List<Weighted<Map<String, Int>>>,
) : OpponentHandBeliefQueries {
    private val population = hands.map { weighted ->
        Weighted<Map<String, Int>>(Collections.unmodifiableMap(LinkedHashMap(weighted.value)), weighted.weight)
    }

    init {
        require(viewerAlias.isNotBlank()) { "A belief query needs a viewer alias" }
        require(population.isNotEmpty()) { "An empty population cannot answer belief queries" }
        val total = population.sumOf { it.weight }
        require(total.isFinite() && abs(total - 1.0) <= 1e-9) { "Belief query weights must already be normalized" }
        require(population.all { sample -> sample.value.all { (name, count) -> name.isNotBlank() && count >= 0 } }) {
            "Hand counts require nonblank card names and nonnegative copy counts"
        }
    }

    // Preserve the previous diagnostic algorithm's particle order, additions, zero-weight keys,
    // and final sorted keys. No renormalization or clamping changes historical floating-point values.
    private val marginals: Map<String, Double> = run {
        val values = mutableMapOf<String, Double>()
        population.forEach { sample ->
            sample.value.forEach { (name, count) ->
                if (count > 0) values[name] = values.getOrDefault(name, 0.0) + sample.weight
            }
        }
        Collections.unmodifiableMap(values.toSortedMap())
    }

    override fun probabilityAtLeast(cardName: String, minimumCopies: Int): Double {
        require(cardName.isNotBlank() && minimumCopies >= 0) { "Use a nonblank card name and nonnegative threshold" }
        return probability { it.getOrDefault(cardName, 0) >= minimumCopies }
    }

    override fun probabilityAllOf(minimumCopiesByCard: Map<String, Int>): Double {
        val thresholds = minimumCopiesByCard.toMap()
        require(thresholds.all { (name, count) -> name.isNotBlank() && count >= 0 }) {
            "Use nonblank card names and nonnegative thresholds"
        }
        return probability { hand -> thresholds.all { (name, count) -> hand.getOrDefault(name, 0) >= count } }
    }

    override fun probabilityAnyOf(cardNames: Set<String>): Double {
        val names = cardNames.toSet()
        require(names.all { it.isNotBlank() }) { "Use nonblank card names" }
        return probability { hand -> names.any { hand.getOrDefault(it, 0) > 0 } }
    }

    override fun expectedCopies(cardName: String): Double {
        require(cardName.isNotBlank()) { "Use a nonblank card name" }
        var total = 0.0
        population.forEach { total += it.weight * it.value.getOrDefault(cardName, 0) }
        return total
    }

    override fun presenceMarginals(): Map<String, Double> = marginals

    /** Floating-point sums over the original normalized approximation, including the empty conjunction. */
    private fun probability(predicate: (Map<String, Int>) -> Boolean): Double {
        var total = 0.0
        population.forEach { if (predicate(it.value)) total += it.weight }
        return total
    }
}
