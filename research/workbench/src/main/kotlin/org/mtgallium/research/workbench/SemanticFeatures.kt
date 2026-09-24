package org.mtgallium.research.workbench

import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.*

/** Hashed features of current player information and choices. No labels, study IDs or artifact DTOs. */
internal class SemanticFeatures(
    private val information: InformationStateRepresentation,
    private val stateDimension: Int,
    private val candidateDimension: Int,
) {
    init {
        require(stateDimension > 0 && candidateDimension > 0)
        require(!information.terminated && information.actingPlayerId != null)
        require(information.actingPlayerId == information.observation.perspectivePlayerId)
        require(information.observation.currentTurnStateComplete)
    }
    private val context = VisibleSemanticContext(information.observation, information.actingPlayerId)

    fun state(): RootActionKernelVector {
        val sink = FeatureSink(stateDimension)
        val observation = information.observation
        sink.numeric("state.turn", observation.turnNumber / 20.0)
        sink.category("state.phase", observation.phase)
        sink.category("state.step", observation.step)
        sink.category("state.active", context.playerRelation(observation.activePlayerId))
        sink.category("state.priority", context.playerRelation(observation.priorityPlayerId))
        observation.players.forEach { player ->
            val role = context.playerRelation(player.playerId)
            val prefix = "state.player.$role"
            sink.numeric("$prefix.life", player.life / 20.0)
            sink.numeric("$prefix.hand", player.handSize / 7.0)
            sink.numeric("$prefix.library", player.librarySize / 60.0)
            sink.numeric("$prefix.graveyard", player.graveyardSize / 20.0)
            sink.numeric("$prefix.exile", player.exileSize / 20.0)
            sink.numeric("$prefix.speed", player.speed / 4.0)
            sink.flag("$prefix.active", player.active)
            sink.flag("$prefix.priority", player.priority)
            sink.flag("$prefix.lost", player.lost)
            sink.numeric("$prefix.noncreatureSpells", player.noncreatureSpellsCastThisTurn / 4.0)
            sink.flag("$prefix.lostLifeThisTurn", player.lostLifeThisTurn)
            sink.flag("$prefix.speedFired", player.speedIncreaseTriggerFiredThisTurn)
            sink.numeric("$prefix.redNoncombatDamage", player.redNoncombatDamageDealtThisTurn / 8.0)
            sink.numeric("$prefix.landPlaysRemaining", player.landPlaysRemainingThisTurn.toDouble())
            val mana = player.mana
            sink.numeric("$prefix.mana.white", mana.white / 5.0)
            sink.numeric("$prefix.mana.blue", mana.blue / 5.0)
            sink.numeric("$prefix.mana.black", mana.black / 5.0)
            sink.numeric("$prefix.mana.red", mana.red / 5.0)
            sink.numeric("$prefix.mana.green", mana.green / 5.0)
            sink.numeric("$prefix.mana.colorless", mana.colorless / 5.0)
            mana.restricted.forEach { restricted ->
                val restrictedPrefix = "$prefix.restricted.${restricted.color ?: "colorless"}"
                sink.category("$restrictedPrefix.rule", restricted.spendRestriction)
                sink.category("$restrictedPrefix.expiry", restricted.expiresAt)
                sink.numeric("$restrictedPrefix.count", restricted.count.toDouble())
                restricted.spellRiders.forEach { sink.category("$restrictedPrefix.rider", it) }
            }
        }
        observation.zones.forEach { zone ->
            val role = context.playerRelation(zone.ownerId)
            sink.numeric("state.zone.$role.${zone.zone}.size", zone.size / 20.0)
            sink.flag("state.zone.$role.${zone.zone}.hidden", zone.hidden)
            zone.cards.forEach { card -> emitCard(sink, "state.card.$role.${zone.zone}", card) }
        }
        observation.stack.forEach { stack -> emitStack(sink, "state.stack", stack) }
        sink.numeric("state.stack.count", observation.stack.size / 5.0)
        observation.combat?.let { combat ->
            sink.category("state.combat.attacker", context.playerRelation(combat.attackingPlayerId))
            sink.numeric("state.combat.attackers", combat.attackers.size / 8.0)
            sink.numeric("state.combat.blockers", combat.blockers.size / 8.0)
            combat.attackers.forEach { attacker ->
                context.safeObjects[attacker.attackerObjectRef]?.let {
                    emitCard(sink, "state.combat.attackingCard", it)
                }
                sink.numeric("state.combat.blockerCountPerAttacker", attacker.blockerObjectRefs.size / 4.0)
            }
            combat.blockers.forEach { blocker ->
                context.safeObjects[blocker.blockerObjectRef]?.let {
                    emitCard(sink, "state.combat.blockingCard", it)
                }
            }
        }
        observation.pendingDecision?.let { pending ->
            sink.category("state.pending.kind", pending.decisionKind)
            sink.category("state.pending.player", context.playerRelation(pending.playerId))
            sink.category("state.pending.sourceName", pending.sourceName ?: "none")
            sink.category("state.pending.phase", pending.phase)
            sink.flag("state.pending.canRespond", pending.canRespond)
            pending.choiceSpec?.let { choiceSpec ->
                val json = PolicyJson.format.encodeToJsonElement(
                    org.mtgallium.agent.infoset.core.PolicyDecisionChoiceSpec.serializer(),
                    choiceSpec,
                )
                emitSafeJson(sink, "state.pending.spec", json, JsonFeatureMode.STATE)
            }
        }

        val knowledge = information.knowledge
        knowledge.zones.forEach { zone ->
            val prefix = "state.knowledge.zone.${context.playerRelation(zone.ownerId)}.${zone.zone}"
            sink.numeric("$prefix.size", zone.size / 20.0)
            zone.knownCardCounts.forEach { (name, count) ->
                sink.numeric("$prefix.known.$name", count / 4.0)
            }
        }
        knowledge.knownObjects.forEach { known ->
            val prefix = "state.knowledge.object.${context.playerRelation(known.ownerId)}.${known.zone}"
            sink.category("$prefix.card", known.cardName)
        }
        knowledge.knownLibraryOrders.forEach { order ->
            val role = context.playerRelation(order.playerId)
            order.top.forEachIndexed { index, name ->
                if (name != null) sink.category("state.knowledge.$role.libraryTop.$index", name)
            }
            order.bottom.forEachIndexed { index, name ->
                if (name != null) sink.category("state.knowledge.$role.libraryBottom.$index", name)
            }
        }
        knowledge.unlocatedCardCounts.forEach { (player, counts) ->
            val role = context.playerRelation(player)
            counts.forEach { (name, count) ->
                sink.numeric("state.knowledge.$role.unlocated.$name", count / 4.0)
            }
        }
        sink.flag("state.knowledge.complete", knowledge.epistemicallyComplete)

        val recentEvents = BoundedPolicyInputCompiler.recentEventWindow(information.history, BoundedPolicyInputConfig()).events
        recentEvents.forEachIndexed { offset, event ->
            emitEvent(sink, event, recentEvents.size - offset)
        }
        sink.numeric("state.history.windowSize", recentEvents.size / 64.0)
        return sink.build()
    }

    fun candidate(choice: SemanticChoice): RootActionKernelVector {
        val sink = FeatureSink(candidateDimension)
        sink.category("candidate.kind", choice.kind.name)
        sink.category("candidate.family", choice.operationFamily.name)
        sink.category("candidate.intent", choice.actionIntent.kind.name)
        choice.actionIntent.sourceCardName?.let { sink.category("candidate.source", it) }
        choice.actionIntent.targetRelations.forEach { sink.category("candidate.targetRelation", it.name) }
        emitSafeJson(sink, "candidate.payload", choice.canonicalPayload, JsonFeatureMode.CANDIDATE)
        return sink.build()
    }

    private fun emitEvent(
        sink: FeatureSink,
        event: PolicyHistoryEvent,
        recency: Int,
    ) {
        val bucket = when (recency) {
            1 -> "last"
            in 2..4 -> "recent4"
            in 5..16 -> "recent16"
            else -> "older"
        }
        val prefix = "state.event.$bucket"
        sink.category("$prefix.kind", event.kind.name)
        sink.category("$prefix.actor", context.playerRelation(event.actor))
        sink.category("$prefix.audience", event.audience.scope.name)
        event.detail?.let { detail ->
            val json = PolicyJson.format.encodeToJsonElement(PerspectiveEventDetail.serializer(), detail)
            emitSafeJson(sink, "$prefix.detail", json, JsonFeatureMode.STATE)
        }
    }

    private fun emitSafeJson(
        sink: FeatureSink,
        path: String,
        value: JsonElement,
        mode: JsonFeatureMode,
    ) {
        when (value) {
            JsonNull -> sink.category(path, "null")
            is JsonArray -> {
                sink.numeric("$path.count", value.size / 8.0)
                value.forEachIndexed { index, child ->
                    val itemPath = if (
                        mode == JsonFeatureMode.CANDIDATE
                    ) {
                        "$path.item.$index"
                    } else {
                        "$path.item"
                    }
                    emitSafeJson(sink, itemPath, child, mode)
                }
            }
            is JsonObject -> value.entries.forEach { (rawKey, child) ->
                if (forbiddenJsonField(rawKey)) return@forEach
                val keyReference = context.resolveReference(rawKey, mode)
                if (keyReference != null) {
                    emitReference(sink, "$path.key", keyReference)
                    emitSafeJson(sink, "$path.value", child, mode)
                } else if (!looksLikeOpaqueReference(rawKey)) {
                    emitSafeJson(sink, "$path.$rawKey", child, mode)
                }
            }
            is JsonPrimitive -> {
                if (value.isString) {
                    val content = value.content
                    val reference = context.resolveReference(content, mode)
                    when {
                        reference != null -> emitReference(sink, path, reference)
                        looksLikeOpaqueReference(content) -> sink.category("$path.referenceKind", referenceKind(content))
                        else -> sink.category(path, content)
                    }
                } else {
                    value.booleanOrNull?.let { sink.flag(path, it) }
                        ?: value.doubleOrNull?.let { number ->
                            sink.numeric(path, number.coerceIn(-20.0, 20.0) / 10.0)
                            sink.category("$path.bucket", numericBucket(number))
                        }
                }
            }
        }
    }

    private fun emitReference(
        sink: FeatureSink,
        path: String,
        reference: VisibleReference,
    ) {
        when (reference) {
            is VisibleReference.Player -> sink.category("$path.player", context.playerRelation(reference.playerId))
            is VisibleReference.Card -> {
                emitCard(sink, "$path.card", reference.card)
                reference.semanticOrdinal?.let { ordinal ->
                    sink.category("$path.card.semanticOrdinal", ordinal.toString())
                }
            }
            is VisibleReference.Stack -> emitStack(sink, "$path.stack", reference.item)
            is VisibleReference.AuthorizedChoice -> sink.category("$path.referenceKind", "authorized-choice")
        }
    }

    private fun emitCard(
        sink: FeatureSink,
        prefix: String,
        card: PolicyCardView,
    ) {
        sink.category("$prefix.name", card.name)
        sink.category("$prefix.zone", card.zone)
        sink.category("$prefix.owner", context.playerRelation(card.ownerId))
        sink.category("$prefix.controller", context.playerRelation(card.controllerId))
        card.types.forEach { sink.category("$prefix.type", it) }
        card.subtypes.forEach { sink.category("$prefix.subtype", it) }
        card.colors.forEach { sink.category("$prefix.color", it) }
        card.keywords.forEach { sink.category("$prefix.keyword", it) }
        sink.category("$prefix.manaCost", card.manaCost)
        sink.numeric("$prefix.manaValue", card.manaValue / 6.0)
        card.power?.let { sink.numeric("$prefix.power", it / 6.0) }
        card.toughness?.let { sink.numeric("$prefix.toughness", it / 6.0) }
        sink.flag("$prefix.tapped", card.tapped)
        sink.flag("$prefix.summoningSick", card.summoningSick)
        sink.flag("$prefix.faceDown", card.faceDown)
        sink.numeric("$prefix.damage", card.damageMarked / 6.0)
        card.counters.forEach { (kind, count) -> sink.numeric("$prefix.counter.$kind", count / 4.0) }
        sink.flag("$prefix.warped", card.isWarped)
        sink.flag("$prefix.warpExiled", card.isWarpExiled)
        sink.flag("$prefix.playableFromExile", card.playableFromExile)
        sink.flag("$prefix.activationUsed", card.hasActivatedAbilityThisTurn)
    }

    private fun emitStack(
        sink: FeatureSink,
        prefix: String,
        item: PolicyStackItemView,
    ) {
        sink.category("$prefix.name", item.name)
        sink.category("$prefix.kind", item.kind)
        sink.category("$prefix.controller", context.playerRelation(item.controllerId))
        sink.numeric("$prefix.targets", item.targets.size / 4.0)
    }

    private fun forbiddenJsonField(field: String): Boolean {
        val normalized = field.lowercase()
        return normalized == "schemaversion" ||
            "signature" in normalized || "digest" in normalized ||
            "objectref" in normalized || "knowledgeobjectkey" in normalized ||
            normalized == "decisionid" || normalized == "eventid" ||
            normalized == "proposalseed"
    }

    private fun looksLikeOpaqueReference(value: String): Boolean =
        value.matches(Regex("[0-9a-f]{64}")) ||
            value.startsWith("object:") || value.startsWith("zone:") ||
            value.startsWith("choice:") || value.startsWith("stack-target:") ||
            value == "\$CURRENT_DECISION_ID"

    private fun referenceKind(value: String): String = when {
        value.startsWith("object:") -> "visible-object"
        value.startsWith("zone:") -> "observation-object"
        value.startsWith("choice:") -> "authorized-choice"
        value.startsWith("stack:") -> "stack"
        value.startsWith("stack-target:") -> "stack-target"
        else -> "opaque"
    }

    private fun numericBucket(value: Double): String = when {
        value < 0.0 -> "negative"
        value == 0.0 -> "zero"
        value <= 1.0 -> "one"
        value <= 3.0 -> "two-three"
        value <= 7.0 -> "four-seven"
        else -> "eight-plus"
    }
}

private enum class JsonFeatureMode { STATE, CANDIDATE }

private sealed interface VisibleReference {
    data class Player(val playerId: String) : VisibleReference
    data class Card(val card: PolicyCardView, val semanticOrdinal: Int? = null) : VisibleReference
    data class Stack(val item: PolicyStackItemView) : VisibleReference
    data object AuthorizedChoice : VisibleReference
}

/** The visible card an action payload's semantic object key names, as the acting player sees it. */
fun visibleCard(information: InformationStateRepresentation, reference: String): PolicyCardView? =
    (VisibleSemanticContext(information.observation, information.actingPlayerId)
        .resolveReference(reference, JsonFeatureMode.CANDIDATE) as? VisibleReference.Card)?.card

/** Reconstructs the adapter's raw-ID-free semantic object keys solely to join them to visible data. */
private class VisibleSemanticContext(
    observation: PlayerObservationSnapshot,
    private val actor: String?,
) {
    val safeObjects: Map<String, PolicyCardView> = observation.zones.flatMap { it.cards }
        .associateBy(PolicyCardView::objectRef)
    private val safeStacks = observation.stack.associateBy(PolicyStackItemView::objectRef)
    private val semanticObjects: Map<String, PolicyCardView>
    private val semanticStacks: Map<String, PolicyStackItemView>
    private val players = observation.players.map { it.playerId }.toSet()

    init {
        val cards = safeObjects.values.toList()
        val base = cards.associate { card ->
            val descriptor = mutableListOf(
                "card",
                card.definitionId.orEmpty(),
                card.name,
                card.zone,
                card.ownerId.orEmpty(),
                card.controllerId.orEmpty(),
                card.types.sorted().joinToString(","),
                card.subtypes.sorted().joinToString(","),
                card.colors.sorted().joinToString(","),
                card.keywords.sorted().joinToString(","),
                card.manaCost,
                card.manaValue.toString(),
                card.oracleText,
                card.power?.toString().orEmpty(),
                card.toughness?.toString().orEmpty(),
                card.tapped.toString(),
                card.summoningSick.toString(),
                card.faceDown.toString(),
                card.damageMarked.toString(),
                card.counters.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" },
            )
            if (
                    card.isWarped || card.isWarpExiled || card.playableFromExile ||
                        card.hasActivatedAbilityThisTurn
            ) {
                descriptor += listOf(
                    "runtime",
                    "isWarped=${card.isWarped}",
                    "isWarpExiled=${card.isWarpExiled}",
                    "playableFromExile=${card.playableFromExile}",
                    "hasActivatedAbilityThisTurn=${card.hasActivatedAbilityThisTurn}",
                )
            }
            card.objectRef to descriptorDigest(descriptor)
        }
        val stackBase = observation.stack.associate { stack -> stack.objectRef to descriptorDigest(listOf(
            "stack",
            stack.objectRef.substringAfter("stack:", "0"),
            stack.controllerId.orEmpty(),
            stack.name,
            stack.kind,
            stack.oracleText,
        )) }
        var refined = base + stackBase
        for (iteration in 0 until 4) {
            val next = linkedMapOf<String, String>()
            cards.forEach { card ->
                next[card.objectRef] = descriptorDigest(listOf(
                    base.getValue(card.objectRef),
                    card.attachedTo?.let(refined::get).orEmpty(),
                    card.attachments.mapNotNull(refined::get).sorted().joinToString(","),
                ))
            }
            observation.stack.forEach { stack ->
                next[stack.objectRef] = descriptorDigest(listOf(
                    stackBase.getValue(stack.objectRef),
                    stack.targets.map { target ->
                        when {
                            target in players -> target
                            target in refined -> refined.getValue(target)
                            else -> target
                        }
                    }.joinToString(","),
                ))
            }
            if (next == refined) break
            refined = next
        }
        semanticObjects = cards.associateBy { card ->
            "object:${card.ownerId}:${card.zone}:${refined.getValue(card.objectRef)}"
        }
        semanticStacks = observation.stack.mapIndexed { index, stack -> "stack:$index" to stack }.toMap()
    }

    fun playerRelation(playerId: String?): String = when (playerId) {
        null -> "none"
        actor -> "self"
        else -> "opponent"
    }

    fun resolveReference(value: String, mode: JsonFeatureMode): VisibleReference? {
        if (value in players) return VisibleReference.Player(value)
        semanticObjects[value]?.let { return VisibleReference.Card(it) }
        SEMANTIC_ORDINAL.matchEntire(value)?.let { match ->
            semanticObjects[match.groupValues[1]]?.let { return VisibleReference.Card(it, match.groupValues[2].toInt()) }
        }
        semanticStacks[value]?.let { return VisibleReference.Stack(it) }
        if (mode == JsonFeatureMode.STATE) {
            safeObjects[value]?.let { return VisibleReference.Card(it) }
            safeStacks[value]?.let { return VisibleReference.Stack(it) }
        }
        return if (value.startsWith("choice:")) VisibleReference.AuthorizedChoice else null
    }

    private fun descriptorDigest(parts: List<String>): String = PolicyJson.sha256(parts.joinToString("\u001f"))

    companion object {
        private val SEMANTIC_ORDINAL = Regex("(.+)#([0-9]+)")
    }
}

private class FeatureSink(private val dimension: Int) {
    private val values = linkedMapOf<Int, Double>()

    fun category(name: String, value: String) = add("$name=$value", 1.0)
    fun flag(name: String, value: Boolean) = category(name, value.toString())
    fun numeric(name: String, value: Double) {
        require(value.isFinite()) { "Non-finite semantic feature: $name" }
        if (value != 0.0) add(name, value.coerceIn(-4.0, 4.0))
    }
    private fun add(feature: String, value: Double) {
        var hash = -3750763034362895579L
        for (byte in feature.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 1099511628211L
        }
        val index = ((hash xor (hash ushr 32)).toInt() and Int.MAX_VALUE) % dimension
        values[index] = values.getOrDefault(index, 0.0) + value
    }
    fun build(): RootActionKernelVector {
        val sorted = values.toSortedMap()
        return RootActionKernelVector(sorted.keys.toList(), sorted.values.toList())
    }
}
