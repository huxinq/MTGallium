package org.mtgallium.agent.infoset.core

import java.util.PriorityQueue

/**
 * A grammar for incrementally constructing one family of complete policy actions.
 *
 * Partials are proposer-local values. They are never policy choices, history entries, or search
 * worlds. [finalize] is the only path from this grammar to an executable action.
 */
interface StructuredActionSpace<Partial, Extension, Action> {
    fun start(): Partial

    /**
     * Extensions must be returned in best-first order for the priority of their resulting
     * partials. The proposer keeps only the next sibling live, so even a very broad extension
     * family remains lazy.
     */
    fun legalExtensions(partial: Partial): Sequence<Extension>

    fun extend(partial: Partial, extension: Extension): Partial
    fun isComplete(partial: Partial): Boolean
    fun finalize(partial: Partial): Action

    /** Equal keys assert that the partials have exactly the same legal continuation language. */
    fun canonicalPartialKey(partial: Partial): String

    /** Equal keys assert that either completed value is an equivalent execution representative. */
    fun canonicalActionKey(partial: Partial): String = canonicalPartialKey(partial)

    fun priority(partial: Partial): StructuredProposalPriority

    /**
     * A small deterministic set of complete structural anchors, such as no blocks, spread, and
     * pile assignments. They improve early diversity without removing the exhaustive stream.
     */
    fun preferredCompletions(): Sequence<Partial> = emptySequence()
}

/**
 * Lexicographic best-first priority. Larger tiers and larger learned priors rank first; the
 * stable key is the final ascending tie-break. A future neural proposer can fill [learnedPrior]
 * while retaining the same structured action grammar.
 */
data class StructuredProposalPriority(
    val tiers: List<Long>,
    val learnedPrior: Double = 0.0,
    val stableKey: String,
) {
    init {
        require(learnedPrior.isFinite()) { "Structured-action prior must be finite" }
    }
}

/**
 * Deterministic, lazy best-first enumeration of complete structured actions.
 *
 * Only one unvisited sibling from each expanded partial is held in the frontier. Duplicate
 * partials are merged only when the action space gives them the same canonical key. Complete
 * anchors and ordinary traversal share a canonical output set, so no construction route creates
 * a duplicate action.
 */
class BestFirstStructuredActionProposer<Partial, Extension, Action>(
    private val space: StructuredActionSpace<Partial, Extension, Action>,
) {
    fun proposals(): Sequence<Action> = sequence {
        val emitted = mutableSetOf<String>()
        for (partial in space.preferredCompletions()) {
            require(space.isComplete(partial)) { "A preferred structured completion was partial" }
            if (emitted.add(space.canonicalActionKey(partial))) yield(space.finalize(partial))
        }

        val frontier = PriorityQueue(queuedComparator())
        val visited = mutableSetOf<String>()
        frontier += QueuedPartial(space.start(), null)

        while (frontier.isNotEmpty()) {
            val queued = frontier.remove()
            queued.siblings?.enqueueNext(frontier)
            val partial = queued.partial
            if (!visited.add(space.canonicalPartialKey(partial))) continue

            if (space.isComplete(partial)) {
                if (emitted.add(space.canonicalActionKey(partial))) yield(space.finalize(partial))
                continue
            }

            SiblingCursor(partial, space.legalExtensions(partial).iterator())
                .enqueueNext(frontier)
        }
    }

    private fun queuedComparator(): Comparator<QueuedPartial> = Comparator { left, right ->
        comparePriorities(space.priority(left.partial), space.priority(right.partial))
    }

    private fun comparePriorities(
        left: StructuredProposalPriority,
        right: StructuredProposalPriority,
    ): Int {
        val count = maxOf(left.tiers.size, right.tiers.size)
        for (index in 0 until count) {
            val leftTier = left.tiers.getOrElse(index) { 0L }
            val rightTier = right.tiers.getOrElse(index) { 0L }
            if (leftTier != rightTier) return rightTier.compareTo(leftTier)
        }
        if (left.learnedPrior != right.learnedPrior) {
            return right.learnedPrior.compareTo(left.learnedPrior)
        }
        return left.stableKey.compareTo(right.stableKey)
    }

    private inner class QueuedPartial(
        val partial: Partial,
        val siblings: SiblingCursor?,
    )

    private inner class SiblingCursor(
        private val parent: Partial,
        private val extensions: Iterator<Extension>,
    ) {
        fun enqueueNext(frontier: PriorityQueue<QueuedPartial>) {
            if (!extensions.hasNext()) return
            frontier += QueuedPartial(space.extend(parent, extensions.next()), this)
        }
    }
}
