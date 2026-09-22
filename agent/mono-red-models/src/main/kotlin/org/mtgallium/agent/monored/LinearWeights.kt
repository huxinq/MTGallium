package org.mtgallium.agent.monored

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Sparse coefficients; omitted features have weight zero. */
@Serializable
data class LinearWeights(
    val bias: Double = 0.0,
    @Required val weights: Map<String, Double> = emptyMap(),
) {
    init {
        require(bias.isFinite() && weights.values.all(Double::isFinite)) { "Linear coefficients must be finite" }
    }

    fun toJson(): String = json.encodeToString(copy(weights = weights.toSortedMap()))

    companion object {
        private val json = Json { encodeDefaults = true }
        fun load(text: String): LinearWeights = json.decodeFromString(text)
    }
}
