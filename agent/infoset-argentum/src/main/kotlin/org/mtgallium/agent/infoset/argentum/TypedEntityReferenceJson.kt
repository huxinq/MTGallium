package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.BatchYesNoResponse
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CancelDecisionResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseManaColor
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.Concede
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.DamageAssignmentResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.ForetellCard
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderBlockers
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlotCard
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.SaddleMount
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.SuspendCardFromHand
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.core.TypecycleCard
import com.wingedsheep.engine.core.UnlockRoomDoor
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.AlternativePaymentChoice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.core.PolicyJson

/**
 * Rewrites only statically typed [EntityId] positions in engine action payloads.
 *
 * The serialized payload remains the source of every ordinary value. The typed action/response
 * controls which leaves and map keys may be treated as routing references, so a label, edge ID,
 * ability ID, or object key that happens to equal an engine ID remains byte-for-byte ordinary data.
 */
internal fun SafeReferenceMap.semanticActionJson(
    action: GameAction,
    encoded: JsonObject,
): JsonObject = TypedEntityJsonMask(this, encoded).action(action)

internal fun SafeReferenceMap.semanticDecisionResponseJson(
    response: DecisionResponse,
    encoded: JsonObject,
): JsonObject = TypedEntityJsonMask(this, encoded).response(response)

private class TypedEntityJsonMask(
    private val refs: SafeReferenceMap,
    initial: JsonObject,
) {
    private var result: JsonObject = initial

    fun action(action: GameAction): JsonObject {
        entity("playerId", action.playerId)
        when (action) {
            is PassPriority,
            is ChooseManaColor,
            is TakeMulligan,
            is KeepHand,
            is Concede -> Unit
            is CastSpell -> {
                entity("cardId", action.cardId)
                targets("targets", action.targets)
                targetGroups("modeTargetsOrdered", action.modeTargetsOrdered)
                payment("paymentStrategy", action.paymentStrategy)
                alternativePayment("alternativePayment", action.alternativePayment)
                additionalCost("additionalCostPayment", action.additionalCostPayment)
                nullableEntity("giftRecipient", action.giftRecipient)
                entities("splicedCardIds", action.splicedCardIds)
                entityMap("damageDistribution", action.damageDistribution.orEmpty().keys)
                nestedEntityMaps("modeDamageDistribution", action.modeDamageDistribution)
                entities("conspiredCreatures", action.conspiredCreatures)
                nullableEntity("casualtyCreature", action.casualtyCreature)
            }
            is ActivateAbility -> {
                entity("sourceId", action.sourceId)
                targets("targets", action.targets)
                additionalCost("costPayment", action.costPayment)
                payment("paymentStrategy", action.paymentStrategy)
                alternativePayment("alternativePayment", action.alternativePayment)
                entityMap("damageDistribution", action.damageDistribution.orEmpty().keys)
            }
            is CycleCard -> sourceAndPayment(action.cardId, action.paymentStrategy)
            is PlotCard -> sourceAndPayment(action.cardId, action.paymentStrategy)
            is ForetellCard -> sourceAndPayment(action.cardId, action.paymentStrategy)
            is SuspendCardFromHand -> sourceAndPayment(action.cardId, action.paymentStrategy)
            is TypecycleCard -> sourceAndPayment(action.cardId, action.paymentStrategy)
            is PlayLand -> entity("cardId", action.cardId)
            is DeclareAttackers -> {
                entityToEntityMap("attackers", action.attackers)
                entityGroups("bands", action.bands.map { it.toList() })
            }
            is DeclareBlockers -> entityToEntityLists("blockers", action.blockers)
            is OrderBlockers -> {
                entity("attackerId", action.attackerId)
                entities("orderedBlockers", action.orderedBlockers)
            }
            is SubmitDecision -> nestedResponse("response", action.response)
            is BottomCards -> entities("cardIds", action.cardIds)
            is CrewVehicle -> {
                entity("vehicleId", action.vehicleId)
                entities("crewCreatures", action.crewCreatures)
            }
            is SaddleMount -> {
                entity("mountId", action.mountId)
                entities("saddleCreatures", action.saddleCreatures)
            }
            is TurnFaceUp -> {
                entity("sourceId", action.sourceId)
                entities("costTargetIds", action.costTargetIds)
                payment("paymentStrategy", action.paymentStrategy)
            }
            is UnlockRoomDoor -> {
                entity("roomId", action.roomId)
                payment("paymentStrategy", action.paymentStrategy)
            }
        }
        return result
    }

    fun response(response: DecisionResponse): JsonObject {
        when (response) {
            is TargetsResponse -> indexedEntityLists("selectedTargets", response.selectedTargets)
            is CardsSelectedResponse -> entities("selectedCards", response.selectedCards)
            is DistributionResponse -> entityMap("distribution", response.distribution.keys)
            is OrderedResponse -> entities("orderedObjects", response.orderedObjects)
            is PilesSplitResponse -> entityGroups("piles", response.piles)
            is DamageAssignmentResponse -> entityMap("assignments", response.assignments.keys)
            is ManaSourcesSelectedResponse -> {
                entities("selectedSources", response.selectedSources)
                entities("waterbendPermanents", response.waterbendPermanents)
            }
            is CombatResolutionResponse -> {
                entityToEntityLists("orderedBlockers", response.orderedBlockers)
                entityToEntityLists("orderedAttackers", response.orderedAttackers)
            }
            is YesNoResponse,
            is BatchYesNoResponse,
            is ModesChosenResponse,
            is ColorChosenResponse,
            is NumberChosenResponse,
            is OptionChosenResponse,
            is ReplacementChosenResponse,
            is BudgetModalResponse,
            is CancelDecisionResponse -> Unit
        }
        return result
    }

    private fun sourceAndPayment(id: EntityId, strategy: PaymentStrategy) {
        entity("cardId", id)
        payment("paymentStrategy", strategy)
    }

    private fun payment(name: String, strategy: PaymentStrategy) {
        if (strategy !is PaymentStrategy.Explicit) return
        nested(name) { mask -> mask.entities("manaAbilitiesToActivate", strategy.manaAbilitiesToActivate) }
    }

    private fun alternativePayment(name: String, payment: AlternativePaymentChoice?) {
        if (payment == null) return
        nested(name) { mask ->
            mask.entities("delvedCards", payment.delvedCards)
            mask.entityMap("convokedCreatures", payment.convokedCreatures.keys)
            mask.nullableEntity("harmonizeCreature", payment.harmonizeCreature)
            mask.entities("tapForGenericPermanents", payment.tapForGenericPermanents)
        }
    }

    private fun additionalCost(name: String, payment: AdditionalCostPayment?) {
        if (payment == null) return
        nested(name) { mask ->
            mask.entities("sacrificedPermanents", payment.sacrificedPermanents)
            mask.entities("discardedCards", payment.discardedCards)
            mask.entities("exiledCards", payment.exiledCards)
            mask.entities("variableCostPermanents", payment.variableCostPermanents)
            mask.entities("beheldCards", payment.beheldCards)
            mask.entities("tappedPermanents", payment.tappedPermanents)
            mask.entities("bouncedPermanents", payment.bouncedPermanents)
            mask.entities("blightTargets", payment.blightTargets)
            mask.recordEntities(
                "distributedCounterRemovals",
                payment.distributedCounterRemovals.map { mapOf("entityId" to it.entityId) },
            )
        }
    }

    private fun targets(name: String, targets: List<ChosenTarget>) {
        val raw = result[name] as? JsonArray ?: return
        require(raw.size == targets.size)
        replace(name, JsonArray(raw.indices.map { index ->
            var record = raw[index] as JsonObject
            fun set(field: String, id: EntityId) {
                record = JsonObject(record.toMutableMap().apply {
                    put(field, JsonPrimitive(refs.semanticReference(id)))
                })
            }
            when (val target = targets[index]) {
                is ChosenTarget.Player -> set("playerId", target.playerId)
                is ChosenTarget.Permanent -> set("entityId", target.entityId)
                is ChosenTarget.Card -> {
                    set("cardId", target.cardId)
                    set("ownerId", target.ownerId)
                }
                is ChosenTarget.Spell -> set("spellEntityId", target.spellEntityId)
            }
            record
        }))
    }

    private fun targetGroups(name: String, groups: List<List<ChosenTarget>>) {
        val raw = result[name] as? JsonArray ?: return
        require(raw.size == groups.size)
        replace(name, JsonArray(raw.indices.map { index ->
            val nested = TypedEntityJsonMask(refs, JsonObject(mapOf("targets" to raw[index])))
            nested.targets("targets", groups[index])
            nested.result.getValue("targets")
        }))
    }

    private fun nestedResponse(name: String, response: DecisionResponse) {
        val raw = result[name] as? JsonObject ?: return
        replace(name, TypedEntityJsonMask(refs, raw).response(response))
    }

    private fun nested(name: String, block: (TypedEntityJsonMask) -> Unit) {
        val raw = result[name] as? JsonObject ?: return
        val mask = TypedEntityJsonMask(refs, raw)
        block(mask)
        replace(name, mask.result)
    }

    private fun entity(name: String, id: EntityId) =
        replace(name, JsonPrimitive(refs.semanticReference(id)))

    private fun nullableEntity(name: String, id: EntityId?) {
        if (id != null) entity(name, id)
    }

    private fun entities(name: String, ids: Iterable<EntityId>) =
        replace(name, JsonArray(ids.map { JsonPrimitive(refs.semanticReference(it)) }))

    private fun indexedEntityLists(name: String, values: Map<Int, List<EntityId>>) = replace(
        name,
        JsonObject(values.toSortedMap().entries.associate { (index, ids) ->
            index.toString() to JsonArray(ids.map { JsonPrimitive(refs.semanticReference(it)) })
        }),
    )

    private fun entityGroups(name: String, groups: List<List<EntityId>>) = replace(
        name,
        JsonArray(groups.map { ids ->
            JsonArray(ids.map { JsonPrimitive(refs.semanticReference(it)) })
        }),
    )

    private fun entityMap(name: String, ids: Iterable<EntityId>) {
        val raw = result[name] as? JsonObject ?: return
        replace(name, semanticKeyed(raw, ids.associateWith { raw.getValue(it.value) }))
    }

    private fun entityToEntityMap(name: String, values: Map<EntityId, EntityId>) {
        val mapped = values.mapValues { (_, id) -> JsonPrimitive(refs.semanticReference(id)) }
        replace(name, semanticKeyed(result[name] as? JsonObject ?: return, mapped))
    }

    private fun entityToEntityLists(name: String, values: Map<EntityId, List<EntityId>>) {
        val mapped = values.mapValues { (_, ids) ->
            JsonArray(ids.map { JsonPrimitive(refs.semanticReference(it)) })
        }
        replace(name, semanticKeyed(result[name] as? JsonObject ?: return, mapped))
    }

    private fun nestedEntityMaps(name: String, values: Map<Int, Map<EntityId, Int>>) {
        val raw = result[name] as? JsonObject ?: return
        replace(name, JsonObject(values.toSortedMap().entries.associate { (index, assignments) ->
            val rawAssignments = raw[index.toString()] as? JsonObject ?: JsonObject(emptyMap())
            index.toString() to semanticKeyed(
                rawAssignments,
                assignments.keys.associateWith { rawAssignments.getValue(it.value) },
            )
        }))
    }

    private fun recordEntities(name: String, records: List<Map<String, EntityId>>) {
        val raw = result[name] as? JsonArray ?: return
        require(raw.size == records.size)
        replace(name, JsonArray(raw.indices.map { index ->
            JsonObject((raw[index] as JsonObject).toMutableMap().apply {
                records[index].forEach { (field, id) ->
                    put(field, JsonPrimitive(refs.semanticReference(id)))
                }
            })
        }))
    }

    /** Preserve multiplicity when observationally equivalent entities share one semantic key. */
    private fun semanticKeyed(
        raw: JsonObject,
        mappedValues: Map<EntityId, JsonElement>,
    ): JsonObject {
        require(mappedValues.keys.all { it.value in raw })
        val grouped = mappedValues.entries.groupBy { refs.semanticReference(it.key) }.toSortedMap()
        val converted = linkedMapOf<String, JsonElement>()
        grouped.forEach { (semantic, entries) ->
            val sorted = entries.map { it.value }.sortedBy(PolicyJson::canonical)
            if (sorted.size == 1) {
                converted[semantic] = sorted.single()
            } else {
                sorted.forEachIndexed { index, value -> converted["$semantic#$index"] = value }
            }
        }
        return JsonObject(converted)
    }

    private fun replace(name: String, value: JsonElement) {
        result = JsonObject(result.toMutableMap().apply { put(name, value) })
    }
}
