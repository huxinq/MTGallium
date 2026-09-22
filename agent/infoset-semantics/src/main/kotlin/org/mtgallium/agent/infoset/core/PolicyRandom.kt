package org.mtgallium.agent.infoset.core

/** Reproducible caller-seeded policy randomness; never a referee RNG accessor. */
class SplitMix64(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -7046029254386353131L
        var value = state
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    fun nextDouble(): Double = nextLong().ushr(11).toDouble() / (1L shl 53).toDouble()
}
