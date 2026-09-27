package org.mtgallium.agent.value

import kotlinx.serialization.SerialName
import kotlin.math.tanh
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.mtgallium.agent.infoset.core.ParameterizedInformationStateEvaluator
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.CanonicalJson

/** Coefficients for the material evaluator; serialized weight names and defaults are stable. */
@Serializable @SerialName("org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig")
data class MaterialWeights(
    val formVersion: Int = 1,
    val life: Double = 0.12,
    val hand: Double = 0.35,
    val power: Double = 1.2,
    val toughness: Double = 0.4,
    val haste: Double = 0.3,
    val landMarginals: List<Double> = listOf(1.00, 0.85, 0.70, 0.45, 0.25),
    val landTail: Double = 0.15,
    val tanhScale: Double = 8.0,
) {
    init {
        require(formVersion == 1) { "Unsupported visible-v2 formula version: $formVersion" }
        require(landMarginals.size <= 64)
        require((listOf(life, hand, power, toughness, haste, landTail) + landMarginals)
            .all { it.isFinite() && it in -1_000_000.0..1_000_000.0 }) {
            "Visible-v2 coefficients must be finite and bounded in [-1000000, 1000000]"
        }
        require(tanhScale.isFinite() && tanhScale in 0.000001..1_000_000.0) {
            "Visible-v2 tanh scale must be finite and in [0.000001, 1000000]"
        }
    }

    companion object {
        /** Distinguished default identity. Equal copied or decoded weights remain explicitly configured. */
        val DEFAULT = MaterialWeights(landMarginals = java.util.Collections.unmodifiableList(
            listOf(1.00, 0.85, 0.70, 0.45, 0.25)))
    }

    val configurationId: String
        get() = "mono-red-visible-v2-config-v1-sha256:" + CanonicalJson.digest(
            CanonicalJson.format.encodeToJsonElement(serializer(), this).jsonObject)

    fun developedManaValue(lands: Int): Double {
        require(lands >= 0)
        return landMarginals.take(lands).sum() + (lands - landMarginals.size).coerceAtLeast(0) * landTail
    }
}

/** Numeric summands only. All battlefield permanents with these fields contribute, regardless of type. */
@Serializable @SerialName("org.mtgallium.agent.searchteacher.MonoRedVisiblePermanentFeatures")
data class MaterialPermanentFeatures(val power: Int, val toughness: Int, val haste: Boolean)

/**
 * Cache once from the projected observation and rescore without reading cards or represented knowledge.
 * Per-permanent order is retained because aggregating power before weighting changes floating-point sums.
 */
@Serializable @SerialName("org.mtgallium.agent.searchteacher.MonoRedVisibleFeatures")
data class MaterialFeatures(
    val rootLife: Int,
    val opponentLife: Int,
    val rootHandSize: Int,
    val opponentHandSize: Int,
    val rootLands: Int,
    val opponentLands: Int,
    val rootPermanents: List<MaterialPermanentFeatures>,
    val opponentPermanents: List<MaterialPermanentFeatures>,
) {
    init {
        require(rootHandSize >= 0 && opponentHandSize >= 0 && rootLands >= 0 && opponentLands >= 0)
    }

    fun linearPredictor(config: MaterialWeights): Double {
        fun boardValue(permanents: List<MaterialPermanentFeatures>, lands: Int): Double {
            val bodies = permanents.sumOf { permanent ->
                permanent.power * config.power + permanent.toughness * config.toughness +
                    if (permanent.haste) config.haste else 0.0
            }
            return bodies + config.developedManaValue(lands)
        }
        fun handValue(size: Int): Double = size * config.hand
        // Keep visible-v2's exact grouping, including separate hand and board values for each side.
        return (rootLife - opponentLife) * config.life +
            handValue(rootHandSize) - handValue(opponentHandSize) +
            boardValue(rootPermanents, rootLands) - boardValue(opponentPermanents, opponentLands)
    }

    fun evaluate(config: MaterialWeights): Double = tanh(linearPredictor(config) / config.tanhScale)

    companion object {
        fun extract(information: InformationStateRepresentation, rootPlayer: String): MaterialFeatures {
            val observation = information.observation
            val root = observation.players.single { it.playerId == rootPlayer }
            val opponent = observation.players.first { it.playerId != rootPlayer }
            val battlefield = observation.zones.filter { it.zone == "BATTLEFIELD" }.flatMap { it.cards }
            val rootPermanents = battlefield.filter { it.controllerId == rootPlayer }
            val opponentPermanents = battlefield.filter { it.controllerId == opponent.playerId }
            fun features(cards: List<org.mtgallium.agent.infoset.core.ObjectView>) = cards.map {
                MaterialPermanentFeatures(it.power ?: 0, it.toughness ?: 0,
                    it.keywords.any { keyword -> keyword.equals("HASTE", ignoreCase = true) })
            }
            return MaterialFeatures(root.life, opponent.life, root.handSize, opponent.handSize,
                rootPermanents.count { it.types.any { type -> type.equals("LAND", ignoreCase = true) } },
                opponentPermanents.count { it.types.any { type -> type.equals("LAND", ignoreCase = true) } },
                features(rootPermanents), features(opponentPermanents))
        }
    }
}

/** Material value with a separate historical identity for explicitly supplied weights. */
class MaterialEvaluator(weights: MaterialWeights = MaterialWeights.DEFAULT) : ParameterizedInformationStateEvaluator {
    private val usesDefaultIdentity = weights === MaterialWeights.DEFAULT
    // Snapshot before publishing: a caller's list cannot change values after identity is bound.
    val weights: MaterialWeights = weights.copy(
        landMarginals = java.util.Collections.unmodifiableList(weights.landMarginals.toList()))
    override val id: String = "mono-red-visible-board-v2" // persisted: evaluator identity
    override val configurationId: String = if (usesDefaultIdentity) id else this.weights.configurationId

    override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double =
        MaterialFeatures.extract(information, rootPlayer).evaluate(weights)
}
