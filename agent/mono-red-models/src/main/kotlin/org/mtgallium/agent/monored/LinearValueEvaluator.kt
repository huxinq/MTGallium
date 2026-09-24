package org.mtgallium.agent.monored

import java.util.Collections
import kotlin.math.tanh
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SearchSettlementOrigin

internal const val MONO_RED_LEARNED_OUTCOME_VALUE_V1_EVALUATOR_ID = "mono-red-learned-outcome-value-v1"

@Serializable
enum class LinearValueLink {
    @SerialName("clip") CLIP,
    @SerialName("tanh") TANH,
}

/** Linear score and its deployed value in [-1, 1]. */
data class LinearValueEstimate(
    val rawScore: Double,
    val deployedValue: Double,
) {
    init {
        require(rawScore.isFinite())
        require(deployedValue.isFinite() && deployedValue in -1.0..1.0)
    }
}

/** bias + sum(weight * feature), mapped to [-1, 1] by [link]. */
class LinearValueEvaluator(
    model: LinearWeights,
    val link: LinearValueLink = LinearValueLink.CLIP,
) : ConfiguredInformationStateEvaluator {
    val model: LinearWeights = model.copy(weights = Collections.unmodifiableMap(model.weights.toSortedMap()))
    override val id: String = MONO_RED_LEARNED_OUTCOME_VALUE_V1_EVALUATOR_ID
    override val configurationId: String = "linear-value-sha256:" + PolicyJson.sha256(
        "$id:$VALUE_FEATURE_SCHEMA:$VALUE_FEATURE_SCALING:${this.model.toJson()}" +
            if (link == LinearValueLink.CLIP) "" else ":link=${link.name.lowercase()}")
    override val settlementOrigin: SearchSettlementOrigin = SearchSettlementOrigin.LEARNED_OUTCOME_ESTIMATE

    override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double =
        evaluateDetailed(information, rootPlayer).deployedValue

    fun evaluateDetailed(
        information: InformationStateRepresentation,
        rootPlayer: String,
    ): LinearValueEstimate =
        evaluateDetailed(ValueFeatures.compile(information, rootPlayer))

    fun observedBy(
        observer: (InformationStateRepresentation, String, Double) -> Unit,
    ): ConfiguredInformationStateEvaluator = observedEvaluationBy { information, rootPlayer, evaluation ->
        observer(information, rootPlayer, evaluation.deployedValue)
    }

    fun observedEvaluationBy(
        observer: (InformationStateRepresentation, String, LinearValueEstimate) -> Unit,
    ): ConfiguredInformationStateEvaluator = ObservedLinearValueEvaluator(this, observer)

    fun evaluate(features: ValueFeatureVector): Double = evaluateDetailed(features).deployedValue

    fun evaluateDetailed(features: ValueFeatureVector): LinearValueEstimate {
        var score = model.bias
        features.values.forEach { (key, value) ->
            val weight = model.weights[key] ?: return@forEach
            score += weight * value
        }
        if (!score.isFinite()) failValueEvaluation(ValueInputError.INFERENCE_NONFINITE,
            "Linear value score is non-finite")
        val deployed = when (link) {
            LinearValueLink.CLIP -> score.coerceIn(-1.0, 1.0)
            LinearValueLink.TANH -> tanh(score)
        }
        return LinearValueEstimate(rawScore = score, deployedValue = deployed)
    }

    companion object {
        fun load(
            text: String,
            link: LinearValueLink = LinearValueLink.CLIP,
        ): LinearValueEvaluator = LinearValueEvaluator(LinearWeights.load(text), link)
    }
}

private class ObservedLinearValueEvaluator(
    private val delegate: LinearValueEvaluator,
    private val observer: (InformationStateRepresentation, String, LinearValueEstimate) -> Unit,
) : ConfiguredInformationStateEvaluator {
    override val id: String get() = delegate.id
    override val configurationId: String get() = delegate.configurationId
    override val settlementOrigin: SearchSettlementOrigin get() = delegate.settlementOrigin

    override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double {
        val evaluation = delegate.evaluateDetailed(information, rootPlayer)
        observer(information, rootPlayer, evaluation)
        return evaluation.deployedValue
    }
}
