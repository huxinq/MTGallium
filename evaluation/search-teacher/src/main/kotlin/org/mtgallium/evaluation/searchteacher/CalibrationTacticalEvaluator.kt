package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluator
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluatorSettings
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluatorSchema2
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluatorSchema2Settings
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluatorSchema2Weights

/** Two existing wire contracts, each retaining its original formula and configuration identity. */
@Serializable(with = CalibrationTacticalEvaluatorSerializer::class)
internal sealed interface CalibrationTacticalEvaluator {
    fun informationEvaluator(): ConfiguredInformationStateEvaluator

    data class Settings(val settings: MonoRedTacticalEvaluatorSettings) : CalibrationTacticalEvaluator {
        override fun informationEvaluator() = MonoRedTacticalEvaluator(settings)
    }
}

/** Historical screening strings select schema 2; the settings-object form selects schema 1. */
@Serializable
internal enum class SearchTeacherCalibrationTacticalEvaluator : CalibrationTacticalEvaluator {
    V3_DEFAULT,
    V3_WITHOUT_ATTACK_AND_INITIATIVE;

    override fun informationEvaluator() = MonoRedTacticalEvaluatorSchema2(when (this) {
        V3_DEFAULT -> MonoRedTacticalEvaluatorSchema2Settings()
        V3_WITHOUT_ATTACK_AND_INITIATIVE -> MonoRedTacticalEvaluatorSchema2Settings(
            weights = MonoRedTacticalEvaluatorSchema2Weights(attack = 0.0, initiative = 0.0))
    })
}

internal object CalibrationTacticalEvaluatorSerializer : KSerializer<CalibrationTacticalEvaluator> {
    override val descriptor = buildClassSerialDescriptor("CalibrationTacticalEvaluator")

    override fun deserialize(decoder: Decoder): CalibrationTacticalEvaluator {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Tactical selection requires JSON")
        return when (val value = input.decodeJsonElement()) {
            is JsonObject -> CalibrationTacticalEvaluator.Settings(
                input.json.decodeFromJsonElement(MonoRedTacticalEvaluatorSettings.serializer(), value))
            is JsonPrimitive -> input.json.decodeFromJsonElement(SearchTeacherCalibrationTacticalEvaluator.serializer(), value)
            else -> throw SerializationException("Expected schema-1 settings or a schema-2 screening selector")
        }
    }

    override fun serialize(encoder: Encoder, value: CalibrationTacticalEvaluator) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Tactical selection requires JSON")
        output.encodeJsonElement(when (value) {
            is CalibrationTacticalEvaluator.Settings -> output.json.encodeToJsonElement(
                MonoRedTacticalEvaluatorSettings.serializer(), value.settings)
            is SearchTeacherCalibrationTacticalEvaluator -> output.json.encodeToJsonElement(
                SearchTeacherCalibrationTacticalEvaluator.serializer(), value)
        })
    }
}

/** Retained plan identities hash JSON bytes, including the original branch's field order. */
@OptIn(ExperimentalSerializationApi::class)
internal object SearchTeacherCalibrationPolicySerializer :
    JsonTransformingSerializer<SearchTeacherCalibrationPolicy>(SearchTeacherCalibrationPolicy.generatedSerializer()) {
    override fun transformSerialize(element: JsonElement): JsonElement {
        val policy = element.jsonObject
        val screeningSelector = (policy["tacticalEvaluator"] as? JsonPrimitive)?.isString == true
        if (!screeningSelector && policy["searchHeuristicProfile"] == null &&
            policy["rolloutHorizonSettlementOverride"] == null) return policy
        val screeningOrder = listOf("id", "particles", "simulations", "maxPolicyDecisions", "explorationConstant",
            "singletonSelection", "rolloutHeuristicProbability", "evaluator", "tacticalEvaluator",
            "rolloutHorizonSettlementOverride", "searchHeuristicProfile", "rolloutTurnHorizon")
        return JsonObject(buildMap {
            screeningOrder.forEach { key -> policy[key]?.let { put(key, it) } }
            policy.forEach { (key, value) -> if (key !in this) put(key, value) }
        })
    }
}
