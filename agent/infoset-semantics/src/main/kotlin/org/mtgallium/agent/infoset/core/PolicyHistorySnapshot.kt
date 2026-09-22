package org.mtgallium.agent.infoset.core

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/** A persistent, deeply immutable ledger whose commitment is advanced exactly once per appended event. */
class PolicyHistorySnapshot private constructor(
    private val events: PersistentList<PolicyHistoryEvent>,
    val commitment: PolicyHistoryCommitment,
) : AbstractList<PolicyHistoryEvent>(), java.util.RandomAccess {
    override val size: Int get() = events.size
    override fun get(index: Int): PolicyHistoryEvent = events[index]

    fun append(event: PolicyHistoryEvent): PolicyHistorySnapshot {
        val frozen = event.semanticSnapshot()
        return PolicyHistorySnapshot(events.adding(frozen), commitment.append(frozen))
    }

    /** Prefix sharing is an implementation property, not a claim about game-history equivalence. */
    fun sharesStorageWith(other: PolicyHistorySnapshot): Boolean = events === other.events

    companion object {
        fun empty(): PolicyHistorySnapshot = PolicyHistorySnapshot(persistentListOf(), PolicyHistoryCommitment.empty())
        fun capture(events: List<PolicyHistoryEvent>, expected: PolicyHistoryCommitment): PolicyHistorySnapshot {
            val snapshot = if (events is PolicyHistorySnapshot) events else events.fold(empty()) { prefix, event -> prefix.append(event) }
            require(snapshot.commitment == expected) { "Represented history does not match its commitment" }
            return snapshot
        }
    }
}
