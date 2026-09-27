package org.mtgallium.agent.infoset.core

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/** A persistent, deeply immutable ledger whose commitment is advanced exactly once per appended event. */
class ObservationHistory private constructor(
    private val events: PersistentList<ObservedEvent>,
    val commitment: HistoryHashChain,
) : AbstractList<ObservedEvent>(), java.util.RandomAccess {
    override val size: Int get() = events.size
    override fun get(index: Int): ObservedEvent = events[index]

    fun append(event: ObservedEvent): ObservationHistory {
        val frozen = event.semanticSnapshot()
        return ObservationHistory(events.adding(frozen), commitment.append(frozen))
    }

    /** Prefix sharing is an implementation property, not a claim about game-history equivalence. */
    fun sharesStorageWith(other: ObservationHistory): Boolean = events === other.events

    companion object {
        fun empty(): ObservationHistory = ObservationHistory(persistentListOf(), HistoryHashChain.empty())
        fun capture(events: List<ObservedEvent>, expected: HistoryHashChain): ObservationHistory {
            val snapshot = if (events is ObservationHistory) events else events.fold(empty()) { prefix, event -> prefix.append(event) }
            require(snapshot.commitment == expected) { "Represented history does not match its commitment" }
            return snapshot
        }
    }
}
