package org.mtgallium.agent.searchteacher

import java.util.Collections
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SearchSettlementOrigin

const val FACTUAL_OUTCOME_RESIDUAL_RIDGE: Double = 0.001
const val FACTUAL_OUTCOME_RESIDUAL_CHECKPOINT_PAYLOAD_SCHEMA = "factual-outcome-residual-checkpoint-v1"
const val FACTUAL_OUTCOME_RESIDUAL_TARGET = "factual-same-viewer-actual-terminal-payoff-minus-visible-v2-v1"
const val FACTUAL_OUTCOME_RESIDUAL_MODEL = "sparse-linear-v2-residual-clipped-v1"
const val FACTUAL_OUTCOME_RESIDUAL_OBJECTIVE = "normalized-weighted-mse-ridge-0.001-including-intercept-v1"
const val FACTUAL_OUTCOME_RESIDUAL_ANCHOR = "mono-red-visible-board-v2"
const val FACTUAL_OUTCOME_RESIDUAL_DEPLOYMENT = "bounded-rollout-policy-decision-horizon16-v1"

/** Common inference/fit order, including keys whose UTF-8 order differs from JVM UTF-16 order. */
val factualOutcomeResidualFeatureComparator: Comparator<String> = Comparator { left, right ->
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

private val residualJson = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

/** Verified factual data and allocation are owned by the producer, never inferred from model weights. */
@Serializable
data class FactualOutcomeResidualTrainingBinding(
    val corpusIdentity: String,
    val allocationIdentity: String,
    val trainingRunIdentity: String,
    val environmentProfileIdentity: String,
) {
    init {
        require(listOf(corpusIdentity, allocationIdentity, trainingRunIdentity, environmentProfileIdentity).all {
            it.matches(Regex("[a-z][a-z0-9-]*-sha256:[0-9a-f]{64}"))
        }) { "Residual training bindings require named SHA-256 identities" }
    }
}

@Serializable
data class FactualOutcomeResidualCheckpointPayload(
    val schemaVersion: Int = 1,
    val evaluatorId: String = LeafEvaluator.MTGALLIUM_FACTUAL_OUTCOME_RESIDUAL_V1.evaluatorId,
    val anchorId: String = FACTUAL_OUTCOME_RESIDUAL_ANCHOR,
    val featureSchemaId: String = LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1,
    val featureScalingId: String = LEARNED_OUTCOME_VALUE_FEATURE_SCALING_V1,
    val targetId: String = FACTUAL_OUTCOME_RESIDUAL_TARGET,
    val modelAlgorithmId: String = FACTUAL_OUTCOME_RESIDUAL_MODEL,
    val objectiveId: String = FACTUAL_OUTCOME_RESIDUAL_OBJECTIVE,
    val deploymentId: String = FACTUAL_OUTCOME_RESIDUAL_DEPLOYMENT,
    val training: FactualOutcomeResidualTrainingBinding,
    val bias: Double,
    /** Training vocabulary only. Missing inference features have coefficient zero. */
    val weights: Map<String, Double>,
) {
    init {
        require(schemaVersion == 1 && evaluatorId == LeafEvaluator.MTGALLIUM_FACTUAL_OUTCOME_RESIDUAL_V1.evaluatorId)
        require(anchorId == FACTUAL_OUTCOME_RESIDUAL_ANCHOR && anchorId == MonoRedInformationEvaluator.id)
        require(featureSchemaId == LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1 && featureScalingId == LEARNED_OUTCOME_VALUE_FEATURE_SCALING_V1)
        require(targetId == FACTUAL_OUTCOME_RESIDUAL_TARGET && modelAlgorithmId == FACTUAL_OUTCOME_RESIDUAL_MODEL)
        require(objectiveId == FACTUAL_OUTCOME_RESIDUAL_OBJECTIVE && deploymentId == FACTUAL_OUTCOME_RESIDUAL_DEPLOYMENT)
        require(bias.isFinite() && weights.values.all(Double::isFinite))
        require(weights.keys.all(LearnedOutcomeValueFeatureCompiler::isAllowedFeatureKey))
    }
}

@Serializable
data class FactualOutcomeResidualCheckpointIdentity(
    val payloadSha256: String,
    val training: FactualOutcomeResidualTrainingBinding,
    val checkpointPayloadSchema: String = FACTUAL_OUTCOME_RESIDUAL_CHECKPOINT_PAYLOAD_SCHEMA,
    val evaluatorId: String = LeafEvaluator.MTGALLIUM_FACTUAL_OUTCOME_RESIDUAL_V1.evaluatorId,
) {
    init {
        require(payloadSha256.matches(Regex("[0-9a-f]{64}")))
        require(checkpointPayloadSchema == FACTUAL_OUTCOME_RESIDUAL_CHECKPOINT_PAYLOAD_SCHEMA)
        require(evaluatorId == LeafEvaluator.MTGALLIUM_FACTUAL_OUTCOME_RESIDUAL_V1.evaluatorId)
    }
    val configurationId: String get() = "factual-outcome-residual-configuration-v1-sha256:" +
        PolicyJson.digest(PolicyJson.format.encodeToJsonElement(serializer(), this))
}

sealed interface CheckpointBackedFactualOutcomeResidualEvaluator : ConfiguredInformationStateEvaluator

data class FactualOutcomeResidualEvaluation(
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

/** A factual-outcome residual applied only through the bounded16 registry contract. */
class FactualOutcomeResidualEvaluator private constructor(
    private val checkpoint: FactualOutcomeResidualCheckpointPayload,
    val canonicalCheckpointPayload: String,
    val checkpointIdentity: FactualOutcomeResidualCheckpointIdentity,
) : CheckpointBackedFactualOutcomeResidualEvaluator {
    override val id: String = LeafEvaluator.MTGALLIUM_FACTUAL_OUTCOME_RESIDUAL_V1.evaluatorId
    override val configurationId: String = checkpointIdentity.configurationId
    override val settlementOrigin: SearchSettlementOrigin = SearchSettlementOrigin.LEARNED_OUTCOME_ESTIMATE
    val isZeroResidual: Boolean = checkpoint.bias == 0.0 && checkpoint.weights.values.all { it == 0.0 }

    fun canonicalCheckpointBytes(): ByteArray = canonicalCheckpointPayload.toByteArray(Charsets.UTF_8)

    override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double =
        evaluateDetailed(information, rootPlayer).deployedValue

    fun evaluateDetailed(information: PolicyInformationState, rootPlayer: String): FactualOutcomeResidualEvaluation {
        if (information.terminated || information.winnerId != null) residualFailure(
            LearnedOutcomeValueFailureKind.INPUT_OUTCOME_PRESENT, "Residual inference requires a nonterminal cutoff")
        if (information.observation.perspectivePlayerId != rootPlayer) residualFailure(
            LearnedOutcomeValueFailureKind.INPUT_PERSPECTIVE_MISMATCH, "Residual input must use the root player's information")
        val anchor = MonoRedInformationEvaluator.evaluate(information, rootPlayer)
        if (!anchor.isFinite()) residualFailure(LearnedOutcomeValueFailureKind.INFERENCE_NONFINITE, "Non-finite V2 anchor")
        // The zero control retains V2's legitimate nonterminal input domain and exact floating-point result.
        if (isZeroResidual) return FactualOutcomeResidualEvaluation(anchor, 0.0, anchor, anchor)
        val features = LearnedOutcomeValueFeatureCompiler.compile(information, rootPlayer)
        var residual = checkpoint.bias
        checkpoint.weights.forEach { (key, weight) ->
            val value = features.values[key] ?: return@forEach
            residual += weight * value
            if (!residual.isFinite()) residualFailure(LearnedOutcomeValueFailureKind.INFERENCE_NONFINITE, "Non-finite residual score")
        }
        val raw = anchor + residual
        if (!raw.isFinite()) residualFailure(LearnedOutcomeValueFailureKind.INFERENCE_NONFINITE, "Non-finite anchored residual score")
        return FactualOutcomeResidualEvaluation(anchor, residual, raw, raw.coerceIn(-1.0, 1.0))
    }

    fun observedEvaluationBy(observer: (PolicyInformationState, String, FactualOutcomeResidualEvaluation) -> Unit):
        ConfiguredInformationStateEvaluator = ObservedFactualOutcomeResidualEvaluator(this, observer)

    companion object {
        fun load(serializedCheckpoint: ByteArray): FactualOutcomeResidualEvaluator = load(serializedCheckpoint.toString(Charsets.UTF_8))
        fun load(serializedCheckpoint: String): FactualOutcomeResidualEvaluator {
            val payload = try { residualJson.decodeFromString<FactualOutcomeResidualCheckpointPayload>(serializedCheckpoint) }
            catch (failure: Exception) { throw LearnedOutcomeValueException(LearnedOutcomeValueFailure(
                LearnedOutcomeValueFailureKind.CHECKPOINT_PAYLOAD_INVALID, "Invalid factual residual checkpoint"), failure) }
            return fromCheckpoint(payload)
        }
        fun fromCheckpoint(payload: FactualOutcomeResidualCheckpointPayload): FactualOutcomeResidualEvaluator {
            val normalized = payload.copy(weights = Collections.unmodifiableMap(payload.weights.toSortedMap(factualOutcomeResidualFeatureComparator)))
            val encoded = encodeCanonicalCheckpoint(normalized)
            return FactualOutcomeResidualEvaluator(normalized, encoded,
                FactualOutcomeResidualCheckpointIdentity(PolicyJson.sha256(encoded), normalized.training))
        }
        fun encodeCanonicalCheckpoint(payload: FactualOutcomeResidualCheckpointPayload): String =
            residualJson.encodeToString(payload.copy(weights = payload.weights.toSortedMap(factualOutcomeResidualFeatureComparator)))
    }
}

private class ObservedFactualOutcomeResidualEvaluator(private val delegate: FactualOutcomeResidualEvaluator,
    private val observer: (PolicyInformationState, String, FactualOutcomeResidualEvaluation) -> Unit) : CheckpointBackedFactualOutcomeResidualEvaluator {
    override val id: String get() = delegate.id
    override val configurationId: String get() = delegate.configurationId
    override val settlementOrigin: SearchSettlementOrigin get() = delegate.settlementOrigin
    override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double {
        val evaluation = delegate.evaluateDetailed(information, rootPlayer)
        observer(information, rootPlayer, evaluation)
        return evaluation.deployedValue
    }
}

private fun residualFailure(kind: LearnedOutcomeValueFailureKind, diagnostic: String): Nothing =
    throw LearnedOutcomeValueException(LearnedOutcomeValueFailure(kind, diagnostic))
