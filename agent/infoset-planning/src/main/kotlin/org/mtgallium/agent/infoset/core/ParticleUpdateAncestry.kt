package org.mtgallium.agent.infoset.core

import java.util.Collections

/** The construction route, not a claim that a rejuvenator left the hidden state unchanged. */
enum class ParticleOffspringRoute { RETAINED, FORK, REJUVENATOR }

data class ParticleOffspring(
    val inputIndex: Int,
    val route: ParticleOffspringRoute,
    val duplicateIndex: Int? = null,
)

/**
 * Trusted, update-local ancestry. List position is the output index in weightedWorlds();
 * inputIndex addresses the population BEFORE filtering. No world or native identity is retained.
 * Parents identify construction lineage, not physical-card identity or posterior correctness.
 */
class ParticleUpdateAncestry internal constructor(
    val inputCount: Int,
    val resampled: Boolean,
    offspring: List<ParticleOffspring>,
) {
    val offspring: List<ParticleOffspring> = Collections.unmodifiableList(ArrayList(offspring))

    init {
        require(inputCount > 0 && offspring.isNotEmpty())
        require(offspring.all { it.inputIndex in 0 until inputCount })
        require(offspring.all {
            if (resampled) it.duplicateIndex != null && it.duplicateIndex >= 0 &&
                it.route == if (it.duplicateIndex == 0) ParticleOffspringRoute.FORK else ParticleOffspringRoute.REJUVENATOR
            else it.duplicateIndex == null && it.route == ParticleOffspringRoute.RETAINED
        })
    }
}

/** Allocated only for an explicitly requested readout; no random draws or changes to selection. */
internal class ParticleAncestryRecorder(private val inputCount: Int) {
    private val survivors = ArrayList<Int>()
    private val copies = ArrayList<ParticleOffspring>()

    fun survived(inputIndex: Int) { survivors += inputIndex }

    fun copied(survivorIndex: Int, duplicateIndex: Int) {
        copies += ParticleOffspring(survivors[survivorIndex],
            if (duplicateIndex == 0) ParticleOffspringRoute.FORK else ParticleOffspringRoute.REJUVENATOR,
            duplicateIndex)
    }

    fun finish(resampled: Boolean, outputCount: Int): ParticleUpdateAncestry {
        val rows = if (resampled) copies else survivors.map { ParticleOffspring(it, ParticleOffspringRoute.RETAINED) }
        check(rows.size == outputCount)
        return ParticleUpdateAncestry(inputCount, resampled, rows)
    }
}
