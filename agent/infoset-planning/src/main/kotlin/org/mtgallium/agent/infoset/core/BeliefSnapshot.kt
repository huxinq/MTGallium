package org.mtgallium.agent.infoset.core

/** Privileged search/continuation capability. Returned worlds belong to the caller. */
interface HypothesisGeneration {
    val binding: BeliefSnapshotBinding
    fun materialize(): GeneratedHypotheses
}

/** Generation is bound to the very same snapshot as its semantic queries. */
class GeneratedHypotheses internal constructor(
    val binding: BeliefSnapshotBinding,
    val batch: BeliefBatch<Weighted<SearchWorld>>,
)

fun BeliefQueryView.requireSameSnapshot(hypotheses: GeneratedHypotheses) {
    require(binding.snapshotToken === hypotheses.binding.snapshotToken) {
        "Queries and continuation hypotheses belong to different belief snapshots"
    }
}

/** A backend's maintenance algorithm is separate from the consumers of its prepared snapshot. */
interface BeliefSnapshotSource {
    fun snapshot(): BeliefSnapshot
}

/**
 * One backend owns both facets. A subclass supplies estimates and independent hypothesis copies
 * from a captured distribution; the shared binding prevents stale/cross-snapshot joins but cannot
 * prove that an arbitrary backend's approximation is mathematically correct.
 */
abstract class BeliefSnapshot protected constructor(
    perspectivePlayerId: String,
    epistemicDigest: String,
    inferenceModelIdentity: String,
) {
    init { require(perspectivePlayerId.isNotBlank() && epistemicDigest.isNotBlank() && inferenceModelIdentity.isNotBlank()) }
    private val binding = BeliefSnapshotBinding.create(perspectivePlayerId, epistemicDigest, inferenceModelIdentity)
    protected abstract fun handQueries(): OpponentHandBeliefQueries
    protected abstract fun generateHypotheses(): BeliefBatch<Weighted<SearchWorld>>

    // Retaining a query capability must not retain the backend's materialized worlds.
    val queries: BeliefQueryView by lazy { DetachedQueryView(binding, handQueries()) }

    private class DetachedQueryView(
        override val binding: BeliefSnapshotBinding,
        override val opponentHand: OpponentHandBeliefQueries,
    ) : BeliefQueryView {
        init { require(opponentHand.viewerAlias == binding.perspectivePlayerId) }
    }
    val hypotheses: HypothesisGeneration = object : HypothesisGeneration {
        override val binding: BeliefSnapshotBinding get() = this@BeliefSnapshot.binding
        override fun materialize(): GeneratedHypotheses {
            val batch = generateHypotheses()
            require(batch.particles.isNotEmpty()) { "A hypothesis generator returned no represented support" }
            val weights = batch.particles.map { it.weight }
            require(weights.all { it.isFinite() && it >= 0.0 } && kotlin.math.abs(weights.sum() - 1.0) <= 1e-9) {
                "Generated hypotheses must retain normalized finite weights"
            }
            return GeneratedHypotheses(binding, batch.copy(particles = java.util.Collections.unmodifiableList(ArrayList(batch.particles))))
        }
    }
}
