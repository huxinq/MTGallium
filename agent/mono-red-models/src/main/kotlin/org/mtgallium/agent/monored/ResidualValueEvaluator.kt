package org.mtgallium.agent.monored

import java.util.Collections
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SearchSettlementOrigin

internal const val MONO_RED_FACTUAL_OUTCOME_RESIDUAL_V1_EVALUATOR_ID = "mono-red-factual-outcome-residual-v1"

/** Common inference/fit order, including keys whose UTF-8 order differs from JVM UTF-16 order. */
val utf8FeatureOrder: Comparator<String> = Comparator { left, right ->
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    var result = 0
    var index = 0
    while (result == 0 && index < minOf(a.size, b.size)) {
        result = (a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
        index++
    }
    if (result == 0) a.size.compareTo(b.size) else result
}

data class ResidualValueEstimate(
    val anchorValue: Double,
    val residualValue: Double,
    val rawScore: Double,
    val deployedValue: Double,
) {
    init {
        require(listOf(anchorValue, residualValue, rawScore, deployedValue).all(Double::isFinite))
        require(deployedValue == rawScore.coerceIn(-1.0, 1.0))
    }
}

/** Visible-v2 plus an explicitly supplied linear residual; search placement is a caller choice. */
class ResidualValueEvaluator(model: LinearWeights) : ConfiguredInformationStateEvaluator {
    val model: LinearWeights = model.copy(weights = Collections.unmodifiableMap(
        model.weights.toSortedMap(utf8FeatureOrder)))
    override val id: String = MONO_RED_FACTUAL_OUTCOME_RESIDUAL_V1_EVALUATOR_ID
    override val configurationId: String = "residual-value-sha256:" + PolicyJson.sha256(
        "$id:$VALUE_FEATURE_SCHEMA:$VALUE_FEATURE_SCALING:${this.model.toJson()}")
    override val settlementOrigin: SearchSettlementOrigin = SearchSettlementOrigin.LEARNED_OUTCOME_ESTIMATE
    val isZeroResidual: Boolean = this.model.bias == 0.0 && this.model.weights.values.all { it == 0.0 }

    override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double =
        evaluateDetailed(information, rootPlayer).deployedValue

    fun evaluateDetailed(information: InformationStateRepresentation, rootPlayer: String): ResidualValueEstimate {
        if (information.terminated || information.winnerId != null) residualFailure(
            ValueInputError.INPUT_OUTCOME_PRESENT, "Residual inference requires a nonterminal cutoff")
        if (information.observation.perspectivePlayerId != rootPlayer) residualFailure(
            ValueInputError.INPUT_PERSPECTIVE_MISMATCH, "Residual input must use the root player's information")
        val anchor = MonoRedInformationEvaluator.evaluate(information, rootPlayer)
        if (!anchor.isFinite()) residualFailure(ValueInputError.INFERENCE_NONFINITE, "Non-finite V2 anchor")
        // The zero control retains V2's legitimate nonterminal input domain and exact floating-point result.
        if (isZeroResidual) return ResidualValueEstimate(anchor, 0.0, anchor, anchor)
        val features = ValueFeatures.compile(information, rootPlayer)
        var residual = model.bias
        model.weights.forEach { (key, weight) ->
            val value = features.values[key] ?: return@forEach
            residual += weight * value
            if (!residual.isFinite()) residualFailure(ValueInputError.INFERENCE_NONFINITE, "Non-finite residual score")
        }
        val raw = anchor + residual
        if (!raw.isFinite()) residualFailure(ValueInputError.INFERENCE_NONFINITE, "Non-finite anchored residual score")
        return ResidualValueEstimate(anchor, residual, raw, raw.coerceIn(-1.0, 1.0))
    }

    fun observedEvaluationBy(observer: (InformationStateRepresentation, String, ResidualValueEstimate) -> Unit):
        ConfiguredInformationStateEvaluator = ObservedResidualValueEvaluator(this, observer)

    companion object {
        fun load(text: String): ResidualValueEvaluator = ResidualValueEvaluator(LinearWeights.load(text))
    }
}

private class ObservedResidualValueEvaluator(private val delegate: ResidualValueEvaluator,
    private val observer: (InformationStateRepresentation, String, ResidualValueEstimate) -> Unit) : ConfiguredInformationStateEvaluator {
    override val id: String get() = delegate.id
    override val configurationId: String get() = delegate.configurationId
    override val settlementOrigin: SearchSettlementOrigin get() = delegate.settlementOrigin
    override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double {
        val evaluation = delegate.evaluateDetailed(information, rootPlayer)
        observer(information, rootPlayer, evaluation)
        return evaluation.deployedValue
    }
}

private fun residualFailure(kind: ValueInputError, diagnostic: String): Nothing =
    throw ValueEvaluationException(kind, diagnostic)
