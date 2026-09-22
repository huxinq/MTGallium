package org.mtgallium.agent.infoset.core

/**
 * Aggregate estimates for other players' pooled hands (one opponent in the current two-player
 * scope). A snapshot stays bound to its original approximation when the belief later changes.
 * Implementations expose no worlds or per-particle hands. Estimates of zero or one remain
 * properties of the approximation, not new represented facts about the actual game. Names are
 * exact nonblank card names and copy thresholds are nonnegative. An empty conjunction selects
 * the entire normalized mass. Finite-particle sums retain ordinary floating-point error.
 */
interface OpponentHandBeliefQueries {
    val viewerAlias: String

    /** Probability of at least the requested number of copies; a zero threshold is always met. */
    fun probabilityAtLeast(cardName: String, minimumCopies: Int = 1): Double

    /** Joint thresholds evaluated within the same hypothesis, not multiplied marginals. */
    fun probabilityAllOf(minimumCopiesByCard: Map<String, Int>): Double

    /** Probability of any named card being present, counting overlap once. Empty union is zero. */
    fun probabilityAnyOf(cardNames: Set<String>): Double

    fun expectedCopies(cardName: String): Double

    /** Sorted, immutable presence estimates; zero-mass hypotheses may contribute zero-valued keys. */
    fun presenceMarginals(): Map<String, Double>
}
