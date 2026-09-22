package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/** Trusted diagnostic bijection driven by the owning serializers' type descriptors. */
@OptIn(ExperimentalSerializationApi::class)
internal class ArgentumNativeIdSwap(val first: EntityId, val second: EntityId) {
    init { require(first != second) }
    fun id(value: EntityId): EntityId = when (value) { first -> second; second -> first; else -> value }
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; allowStructuredMapKeys = true
        classDiscriminator = "type"; serializersModule = engineSerializersModule
    }

    /** Narrow combat/pass legal contracts; nested cost/target-selection schemas refuse. */
    fun legal(value: com.wingedsheep.engine.legalactions.LegalAction): com.wingedsheep.engine.legalactions.LegalAction {
        require(value.targetRequirements == null && value.additionalCostInfo == null && value.convokeCreatures == null &&
            value.delveCards == null && value.tapForGenericPermanents == null && value.harmonizeCreatures == null &&
            value.tapForPowerCreatures == null && value.modalEnumeration == null) { "NATIVE_ID_SWAP_COMPLEX_LEGAL_CONTRACT" }
        return value.copy(action = apply(com.wingedsheep.engine.core.GameAction.serializer(), value.action),
            validTargets = value.validTargets?.map(::id), validAttackers = value.validAttackers?.map(::id),
            mandatoryAttackers = value.mandatoryAttackers?.map(::id), validAttackTargets = value.validAttackTargets?.map(::id),
            validBlockers = value.validBlockers?.map(::id),
            blockerMaxBlockCounts = value.blockerMaxBlockCounts?.entries?.associate { id(it.key) to it.value },
            mandatoryBlockerAssignments = value.mandatoryBlockerAssignments?.entries?.associate { id(it.key) to it.value.map(::id) },
            autoTapPreview = value.autoTapPreview?.map(::id))
    }

    fun <T> apply(serializer: KSerializer<T>, value: T): T {
        val original = json.encodeToJsonElement(serializer, value)
        val encoded = transform(serializer.descriptor, original)
        // Always re-enter the public codec, including its current/legacy execution guards.
        val restored = json.decodeFromJsonElement(serializer, encoded)
        check(json.encodeToJsonElement(serializer, restored) == encoded) { "NATIVE_ID_SWAP_CODEC_CHANGED_CONTENT" }
        check(transform(serializer.descriptor, encoded) == original) { "NATIVE_ID_SWAP_NOT_INVOLUTIVE" }
        return restored
    }

    private fun transform(type: SerialDescriptor, value: JsonElement): JsonElement {
        if (value == JsonNull) return value
        if (type.serialName.removeSuffix("?") == EntityId.serializer().descriptor.serialName)
            return JsonPrimitive(id(EntityId(value.jsonPrimitive.content)).value)
        if (type.isInline) return transform(type.getElementDescriptor(0), value)
        // Owned CharacteristicValueSerializer encodes Fixed(n) as an integer, never an ID.
        if (type.serialName.removeSuffix("?") == "CharacteristicValue" && value is JsonPrimitive && value.intOrNull != null)
            return value
        require(type.kind !in setOf(StructureKind.CLASS, StructureKind.OBJECT) || value is JsonObject) {
            "NATIVE_ID_SWAP_CUSTOM_SHAPE:${type.serialName}:$value"
        }
        return when (type.kind) {
            StructureKind.LIST -> JsonArray(value.jsonArray.map { transform(type.getElementDescriptor(0), it) })
            StructureKind.MAP -> if (value is JsonObject) JsonObject(value.entries.associate { (key, item) ->
                transform(type.getElementDescriptor(0), JsonPrimitive(key)).jsonPrimitive.content to
                    transform(type.getElementDescriptor(1), item)
            }) else JsonArray(value.jsonArray.mapIndexed { index, item -> transform(type.getElementDescriptor(index % 2), item) })
            StructureKind.CLASS, StructureKind.OBJECT -> JsonObject(value.jsonObject.mapValues { (name, item) ->
                if (name == "type" && type.getElementIndex(name) == CompositeDecoderUnknown) item else {
                    val index = type.getElementIndex(name)
                    require(index != CompositeDecoderUnknown) { "NATIVE_ID_SWAP_UNKNOWN_FIELD:${type.serialName}:$name" }
                    transform(type.getElementDescriptor(index), item)
                }
            })
            PolymorphicKind.SEALED -> {
                val name = value.jsonObject.getValue("type").jsonPrimitive.content
                val variants = type.getElementDescriptor(1)
                val index = variants.getElementIndex(name)
                require(index != CompositeDecoderUnknown) { "NATIVE_ID_SWAP_UNKNOWN_SEALED_TYPE:$name" }
                transform(variants.getElementDescriptor(index), value)
            }
            PolymorphicKind.OPEN -> {
                val name = value.jsonObject.getValue("type").jsonPrimitive.content
                val concrete = requireNotNull(json.serializersModule.getPolymorphicDescriptors(type)
                    .singleOrNull { it.serialName == name }) { "NATIVE_ID_SWAP_UNREGISTERED_TYPE:$name" }
                transform(concrete, value)
            }
            is PrimitiveKind, SerialKind.ENUM -> value // Ordinary strings are never label-substituted.
            else -> error("NATIVE_ID_SWAP_UNSUPPORTED_DESCRIPTOR:${type.serialName}")
        }
    }

    private companion object { const val CompositeDecoderUnknown = -3 }
}
