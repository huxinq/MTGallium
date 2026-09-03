package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyAttackerView
import org.mtgallium.agent.infoset.core.PolicyBlockerView
import org.mtgallium.agent.infoset.core.PolicyCombatView
import org.mtgallium.agent.infoset.core.PolicyDecisionChoiceSpec
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPendingDecisionView
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.PolicyStackItemView
import org.mtgallium.agent.infoset.core.PolicyZoneView

/** Result of one trusted projection. The routing map never crosses into infoset-core. */
class SafeObservationProjection internal constructor(
    val observation: PolicyObservation,
    internal val references: SafeReferenceMap,
) {
    /**
     * Exact projection update for the engine's pure priority-transfer transition. The matching
     * engine handler changes only priority bookkeeping; no object, zone, decision, or other
     * perspective-visible field changes. Recompute the contract digest through the same canonical
     * path as a full projection so this is an optimization, not a second information contract.
     */
    internal fun withPriority(priorityPlayerId: String): SafeObservationProjection {
        val updated = observation.copy(
            priorityPlayerId = priorityPlayerId,
            players = observation.players.map { player ->
                player.copy(priority = player.playerId == priorityPlayerId)
            },
            observationDigest = "",
        )
        val element = PolicyJson.format.encodeToJsonElement(PolicyObservation.serializer(), updated)
        return SafeObservationProjection(
            observation = updated.copy(observationDigest = PolicyJson.digest(element)),
            references = references,
        )
    }
}

/**
 * Converts Argentum's masked Gym observation into the engine-independent policy contract.
 *
 * Raw entity ids are replaced by semantic, observation-scoped references. Unknown ids are only
 * admitted while walking an authorized choice specification, where they name cards the rules have
 * explicitly allowed that chooser to inspect.
 */
class SafeObservationProjector {
    fun project(
        observation: TrainingObservation,
        playerAliases: Map<EntityId, String>? = null,
    ): SafeObservationProjection = project(
        observation,
        playerAliases,
        ArgentumPolicyRuntimeProjection.EMPTY,
    )

    internal fun project(
        observation: TrainingObservation,
        playerAliases: Map<EntityId, String>?,
        runtime: ArgentumPolicyRuntimeProjection,
        pendingDecision: PendingDecision? = null,
    ): SafeObservationProjection {
        val refs = SafeReferenceMap(observation, playerAliases, runtime.cards)
        val chooserDecision = pendingDecision?.takeIf { decision ->
            decision.id == observation.pendingDecision?.decisionId &&
                decision.playerId == observation.perspectivePlayerId
        }
        val choiceSpec = chooserDecision?.toDecisionChoiceSpec()
        choiceSpec?.let(refs::admitAuthorizedChoiceReferences)

        val safe = PolicyObservation(
            perspectivePlayerId = refs.player(observation.perspectivePlayerId),
            turnNumber = observation.turnNumber,
            phase = observation.phase.name,
            step = observation.step.name,
            activePlayerId = observation.activePlayerId?.let(refs::player),
            priorityPlayerId = observation.priorityPlayerId?.let(refs::player),
            players = observation.players.map { player ->
                val playerRuntime = runtime.players[player.id] ?: ArgentumPolicyPlayerRuntime()
                PolicyPlayerView(
                    playerId = refs.player(player.id),
                    name = player.name,
                    life = player.lifeTotal,
                    handSize = player.handSize,
                    librarySize = player.librarySize,
                    graveyardSize = player.graveyardSize,
                    exileSize = player.exileSize,
                    mana = PolicyManaPool(
                        white = player.manaPool.white,
                        blue = player.manaPool.blue,
                        black = player.manaPool.black,
                        red = player.manaPool.red,
                        green = player.manaPool.green,
                        colorless = player.manaPool.colorless,
                        restricted = runtime.players[player.id]?.restrictedMana.orEmpty(),
                    ),
                    speed = playerRuntime.speed,
                    active = player.isActive,
                    priority = player.hasPriority,
                    lost = player.hasLost,
                    noncreatureSpellsCastThisTurn = playerRuntime.noncreatureSpellsCast,
                    lostLifeThisTurn = playerRuntime.lostLife,
                    speedIncreaseTriggerFiredThisTurn = playerRuntime.speedIncreaseFired,
                    redNoncombatDamageDealtThisTurn = playerRuntime.redNoncombatDamageDealt,
                    landPlaysRemainingThisTurn = playerRuntime.landDropsRemaining,
                )
            },
            zones = observation.zones.map { zone ->
                PolicyZoneView(
                    ownerId = refs.player(zone.ownerId),
                    zone = zone.zoneType.name,
                    hidden = zone.hidden,
                    size = zone.size,
                    cards = zone.cards.sortedBy { refs.objectRef(it.entityId) }.map { card ->
                        val cardRuntime = runtime.cards[card.entityId]
                        PolicyCardView(
                            objectRef = refs.objectRef(card.entityId),
                            definitionId = card.cardDefinitionId,
                            name = card.name,
                            zone = card.zone.name,
                            ownerId = card.ownerId?.let(refs::player),
                            controllerId = card.controllerId?.let(refs::player),
                            types = card.types,
                            subtypes = card.subtypes,
                            colors = card.colors,
                            keywords = card.keywords,
                            manaCost = card.manaCost,
                            manaValue = card.manaValue,
                            oracleText = card.oracleText,
                            power = card.power,
                            toughness = card.toughness,
                            tapped = card.tapped,
                            summoningSick = card.summoningSick,
                            faceDown = card.faceDown,
                            damageMarked = card.damageMarked,
                            counters = card.counters.toSortedMap(),
                            attachedTo = card.attachedTo?.let(refs::objectRef),
                            attachments = card.attachments.map(refs::objectRef),
                            isWarped = cardRuntime?.isWarped == true,
                            isWarpExiled = cardRuntime?.isWarpExiled == true,
                            playableFromExile = cardRuntime?.playableFromExile == true,
                            hasActivatedAbilityThisTurn = cardRuntime?.hasActivatedAbilityThisTurn == true,
                        )
                    },
                )
            },
            stack = observation.stack.map { stack ->
                PolicyStackItemView(
                    objectRef = refs.objectRef(stack.entityId),
                    controllerId = stack.controllerId?.let(refs::player),
                    name = stack.name,
                    kind = stack.kind.name,
                    oracleText = stack.oracleText,
                    targets = stack.targets.map(refs::reference),
                )
            },
            combat = runtime.combat?.let { combat ->
                PolicyCombatView(
                    attackingPlayerId = combat.attackingPlayerId?.let(refs::player),
                    attackers = combat.attackers.map { attacker ->
                        PolicyAttackerView(
                            attackerObjectRef = refs.objectRef(attacker.attackerId),
                            defenderObjectRef = refs.reference(attacker.defenderId),
                            blockerObjectRefs = attacker.blockerIds.map(refs::objectRef),
                        )
                    },
                    blockers = combat.blockers.map { blocker ->
                        PolicyBlockerView(
                            blockerObjectRef = refs.objectRef(blocker.blockerId),
                            blockedAttackerObjectRefs = blocker.blockedAttackerIds.map(refs::objectRef),
                        )
                    },
                )
            },
            currentTurnStateComplete = runtime.currentTurnComplete,
            pendingDecision = observation.pendingDecision?.let { decision ->
                val liveDecision = pendingDecision?.takeIf { it.id == decision.decisionId }
                PolicyPendingDecisionView(
                    decisionKind = liveDecision?.policyKindName() ?: decision.kind.name,
                    playerId = refs.player(decision.playerId),
                    prompt = decision.prompt,
                    sourceObjectRef = refs.referenceOrNull(decision.sourceEntityId),
                    sourceName = decision.sourceName,
                    triggeringObjectRef = refs.referenceOrNull(decision.triggeringEntityId),
                    effectHint = decision.effectHint,
                    phase = liveDecision?.context?.phase?.name ?: "RESOLUTION",
                    subjectObjectRef = refs.referenceOrNull(liveDecision?.context?.subjectEntityId),
                    canRespond = (liveDecision?.playerId ?: decision.playerId) ==
                        observation.perspectivePlayerId,
                    choiceSpec = choiceSpec?.let { projectChoice(it, refs) },
                )
            },
            observationDigest = "",
        )
        val element = PolicyJson.format.encodeToJsonElement(PolicyObservation.serializer(), safe)
        return SafeObservationProjection(
            observation = safe.copy(observationDigest = PolicyJson.digest(element)),
            references = refs,
        )
    }

    private fun projectChoice(spec: DecisionChoiceSpec, refs: SafeReferenceMap): PolicyDecisionChoiceSpec {
        val full = refs.maskChoiceSpecJson(spec)
        return when (spec) {
            is TargetsChoiceSpec -> PolicyDecisionChoiceSpec.Targets(
                requirements = full.array("requirements"),
                legalTargets = spec.legalTargets.mapValues { (_, ids) -> ids.map(refs::reference) },
                canCancel = spec.canCancel,
            )
            is CardsChoiceSpec -> PolicyDecisionChoiceSpec.Cards(
                options = spec.options.map(refs::reference),
                minSelections = spec.minSelections,
                maxSelections = spec.maxSelections,
                ordered = spec.ordered,
                constraints = full,
                cardMetadata = full["cardInfo"] as? JsonObject,
            )
            is YesNoChoiceSpec -> PolicyDecisionChoiceSpec.YesNo(spec.yesText, spec.noText, spec.hint)
            is BatchYesNoChoiceSpec -> PolicyDecisionChoiceSpec.BatchYesNo(spec.count, spec.yesText, spec.noText)
            is ModesChoiceSpec -> PolicyDecisionChoiceSpec.Modes(
                modes = full.array("modes"),
                minModes = spec.minModes,
                maxModes = spec.maxModes,
            )
            is ColorsChoiceSpec -> PolicyDecisionChoiceSpec.Colors(spec.colors.map { it.name })
            is NumberChoiceSpec -> PolicyDecisionChoiceSpec.Number(spec.minValue, spec.maxValue)
            is DistributionChoiceSpec -> PolicyDecisionChoiceSpec.Distribution(
                total = spec.totalAmount,
                targets = spec.targets.map(refs::reference),
                minimumPerTarget = spec.minPerTarget,
                maximumPerTarget = spec.maxPerTarget.mapKeys { refs.reference(it.key) },
                allowPartial = spec.allowPartial,
            )
            is OrderChoiceSpec -> PolicyDecisionChoiceSpec.Order(
                objects = spec.objects.map(refs::reference),
                cardMetadata = full["cardInfo"] as? JsonObject,
            )
            is PilesChoiceSpec -> PolicyDecisionChoiceSpec.Piles(
                cards = spec.cards.map(refs::reference),
                numberOfPiles = spec.numberOfPiles,
                labels = spec.pileLabels,
                cardMetadata = full["cardInfo"] as? JsonObject,
            )
            is OptionsChoiceSpec -> PolicyDecisionChoiceSpec.Options(
                options = spec.options,
                defaultSearch = spec.defaultSearch,
                optionCards = spec.optionCardIds?.mapValues { (_, ids) -> ids.map(refs::reference) },
                metadata = full.array("optionMetadata"),
                canCancel = spec.canCancel,
            )
            is ReplacementChoiceSpec -> PolicyDecisionChoiceSpec.Replacement(
                fromOptions = spec.fromOptions,
                toOptions = spec.toOptions,
                fromMetadata = full.array("fromMetadata"),
                toMetadata = full.array("toMetadata"),
                allowedToByFrom = spec.allowedToByFrom,
                defaultFromIndex = spec.defaultFromIndex,
            )
            is LibrarySearchChoiceSpec -> PolicyDecisionChoiceSpec.LibrarySearch(
                options = spec.options.map(refs::reference),
                minSelections = spec.minSelections,
                maxSelections = spec.maxSelections,
                cards = full.objectValue("cards"),
                filterDescription = spec.filterDescription,
            )
            is LibraryReorderChoiceSpec -> PolicyDecisionChoiceSpec.LibraryReorder(
                cards = spec.cards.map(refs::reference),
                cardMetadata = full.objectValue("cardInfo"),
            )
            is DamageAssignmentChoiceSpec -> PolicyDecisionChoiceSpec.DamageAssignment(
                attacker = refs.reference(spec.attackerId),
                availablePower = spec.availablePower,
                orderedTargets = spec.orderedTargets.map(refs::reference),
                defender = spec.defenderId?.let(refs::reference),
                minimumAssignments = spec.minimumAssignments.mapKeys { refs.reference(it.key) },
                defaultAssignments = spec.defaultAssignments.mapKeys { refs.reference(it.key) },
                hasTrample = spec.hasTrample,
                hasDeathtouch = spec.hasDeathtouch,
            )
            is CombatResolutionChoiceSpec -> PolicyDecisionChoiceSpec.CombatResolution(full)
            is ManaSourcesChoiceSpec -> PolicyDecisionChoiceSpec.ManaSources(full)
            is BudgetModesChoiceSpec -> PolicyDecisionChoiceSpec.BudgetModal(full)
        }
    }
}

internal class SafeReferenceMap(
    observation: TrainingObservation,
    playerAliases: Map<EntityId, String>? = null,
    cardRuntime: Map<EntityId, ArgentumPolicyCardRuntime> = emptyMap(),
) {
    private val rawToSafe = linkedMapOf<String, String>()
    private val rawToSemantic = linkedMapOf<String, String>()
    private var privateIndex = 0

    init {
        val visibleCards = observation.zones.flatMap { it.cards }
        // A normal board contains many observationally identical objects (most notably basic
        // lands). Reference identity is the digest of the visible descriptor, so hashing the
        // same exact descriptor repeatedly cannot add information. Keep this cache constructor-
        // local: it preserves byte-for-byte reference semantics while avoiding thousands of
        // MessageDigest/hex allocations across the repeated graph-refinement rounds.
        val descriptorDigests = hashMapOf<String, String>()
        fun descriptorDigest(descriptor: String): String =
            descriptorDigests.getOrPut(descriptor) { PolicyJson.sha256(descriptor) }

        if (playerAliases == null) {
            observation.players.forEachIndexed { index, player -> put(player.id, "p$index", "p$index") }
        } else {
            require(playerAliases.keys == observation.players.map { it.id }.toSet()) {
                "Explicit player aliases must exactly cover the observation roster"
            }
            require(playerAliases.values.toSet().size == playerAliases.size) {
                "Explicit player aliases must be unique"
            }
            playerAliases.forEach { (player, alias) -> put(player, alias, alias) }
        }

        // First canonicalize the visible object graph without using engine identity or container
        // order. The iterative refinement makes otherwise-identical objects distinct when their
        // visible attachment/target neighbourhoods differ.
        val base = linkedMapOf<String, String>()
        visibleCards.forEach { card ->
            val descriptorParts = mutableListOf(
                "card",
                card.cardDefinitionId.orEmpty(),
                card.name,
                card.zone.name,
                card.ownerId?.let(::player).orEmpty(),
                card.controllerId?.let(::player).orEmpty(),
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
            cardRuntime[card.entityId]
                ?.takeUnless(EMPTY_CARD_RUNTIME::equals)
                ?.let { runtime ->
                    // Semantic references may quotient only objects whose omitted distinctions
                    // are safe for every supported choice. These are the complete current
                    // policy-visible per-card runtime facts supplied by the trusted adapter;
                    // unlike engine ids, they are stable semantic characteristics of this view.
                    descriptorParts += listOf(
                        "runtime",
                        "isWarped=${runtime.isWarped}",
                        "isWarpExiled=${runtime.isWarpExiled}",
                        "playableFromExile=${runtime.playableFromExile}",
                        "hasActivatedAbilityThisTurn=${runtime.hasActivatedAbilityThisTurn}",
                    )
                }
            val descriptor = descriptorParts.joinToString("\u001f")
            base[card.entityId.value] = descriptorDigest(descriptor)
        }
        observation.stack.forEachIndexed { index, item ->
            val descriptor = listOf(
                    "stack",
                    index.toString(),
                    item.controllerId?.let(::player).orEmpty(),
                    item.name,
                    item.kind.name,
                    item.oracleText,
                ).joinToString("\u001f")
            base[item.entityId.value] = descriptorDigest(descriptor)
        }
        var refined = base.toMap()
        for (round in 0 until 4) {
            val next = linkedMapOf<String, String>()
            visibleCards.forEach { card ->
                val descriptor = listOf(
                        base.getValue(card.entityId.value),
                        card.attachedTo?.value?.let(refined::get).orEmpty(),
                        card.attachments.mapNotNull { refined[it.value] }.sorted().joinToString(","),
                    ).joinToString("\u001f")
                next[card.entityId.value] = descriptorDigest(descriptor)
            }
            observation.stack.forEach { item ->
                val descriptor = listOf(
                        base.getValue(item.entityId.value),
                        item.targets.map { target ->
                            rawToSemantic[target.value] ?: refined[target.value] ?: rawToSafe[target.value] ?: "unknown"
                        }.joinToString(","),
                    ).joinToString("\u001f")
                next[item.entityId.value] = descriptorDigest(descriptor)
            }
            if (next == refined) break
            refined = next
        }
        observation.zones.forEach { zone ->
            val owner = player(zone.ownerId)
            zone.cards.groupBy { refined.getValue(it.entityId.value) }
                .toSortedMap()
                .forEach { (descriptor, cards) ->
                    cards.sortedBy { it.entityId.value }.forEachIndexed { index, card ->
                        put(
                            card.entityId,
                            "zone:$owner:${zone.zoneType.name}:${descriptor.take(16)}:$index",
                            "object:$owner:${zone.zoneType.name}:$descriptor",
                        )
                    }
                }
            }
        observation.stack.forEachIndexed { index, item -> put(item.entityId, "stack:$index", "stack:$index") }
        // A target can remain on a public stack object after the target itself has ceased to be a
        // currently visible object. Admit that public relationship by its first structural
        // occurrence. Neither the safe reference nor its semantic identity incorporates the raw
        // engine id; repeated occurrences still retain the publicly observable equality relation.
        observation.stack.forEachIndexed { stackIndex, item ->
            item.targets.forEachIndexed { targetIndex, target ->
                if (target.value !in rawToSafe) {
                    val structural = "stack-target:$stackIndex:$targetIndex"
                    put(target, structural, structural)
                }
            }
        }
    }

    fun player(id: EntityId): String = rawToSafe[id.value]
        ?: error("Player ${id.value} was absent from the masked observation roster")

    fun objectRef(id: EntityId): String = rawToSafe[id.value]
        ?: error("Object ${id.value} was not visible to this observation")

    fun reference(id: EntityId): String = rawToSafe[id.value]
        ?: error("Reference ${id.value} was not admitted by the masked observation")

    fun semanticReference(id: EntityId): String = rawToSemantic[id.value]
        ?: error("Semantic reference ${id.value} was not admitted by the masked observation")

    fun referenceOrNull(id: EntityId?): String? = id?.value?.let(rawToSafe::get)

    fun admits(id: EntityId): Boolean = id.value in rawToSafe

    fun admitAuthorizedChoiceReferences(spec: DecisionChoiceSpec) {
        spec.entityReferences().forEach { candidate ->
            if (candidate.value !in rawToSafe) {
                val safe = "choice:${privateIndex++}"
                put(candidate, safe, safe)
            }
        }
    }

    /** Mask only fields whose concrete decision schema declares an [EntityId]. */
    fun maskChoiceSpecJson(spec: DecisionChoiceSpec): JsonObject {
        var full = engineJson.encodeToJsonElement(DecisionChoiceSpec.serializer(), spec).jsonObject()
        fun entityList(ids: Iterable<EntityId>): JsonArray =
            JsonArray(ids.map { JsonPrimitive(reference(it)) })
        fun entityMapKeys(raw: JsonElement?, ids: Iterable<EntityId>): JsonObject {
            val source = raw as? JsonObject ?: return buildJsonObject { }
            return JsonObject(ids.associate { id ->
                reference(id) to requireNotNull(source[id.value]) {
                    "Typed entity-keyed field omitted ${id.value}"
                }
            }.toSortedMap())
        }
        fun replace(name: String, value: JsonElement) {
            full = JsonObject(full.toMutableMap().apply { put(name, value) })
        }
        fun replaceRecordEntities(name: String, records: List<Map<String, JsonElement>>) {
            val raw = full[name] as? JsonArray ?: JsonArray(emptyList())
            require(raw.size == records.size) { "Typed $name record count changed during serialization" }
            replace(name, JsonArray(raw.indices.map { index ->
                JsonObject((raw[index] as JsonObject).toMutableMap().apply {
                    putAll(records[index])
                })
            }))
        }

        when (spec) {
            is TargetsChoiceSpec -> replace(
                "legalTargets",
                JsonObject(spec.legalTargets.toSortedMap().entries.associate { (index, ids) ->
                    index.toString() to entityList(ids)
                }),
            )
            is CardsChoiceSpec -> {
                replace("options", entityList(spec.options))
                replace("nonSelectableOptions", entityList(spec.nonSelectableOptions))
                spec.cardInfo?.let { replace("cardInfo", entityMapKeys(full["cardInfo"], it.keys)) }
                replaceRecordEntities(
                    "conditionalMinimums",
                    spec.conditionalMinimums.map { minimum ->
                        mapOf("matchingOptions" to entityList(minimum.matchingOptions))
                    },
                )
            }
            is DistributionChoiceSpec -> {
                replace("targets", entityList(spec.targets))
                replace("maxPerTarget", entityMapKeys(full["maxPerTarget"], spec.maxPerTarget.keys))
            }
            is OrderChoiceSpec -> {
                replace("objects", entityList(spec.objects))
                spec.cardInfo?.let { replace("cardInfo", entityMapKeys(full["cardInfo"], it.keys)) }
            }
            is PilesChoiceSpec -> {
                replace("cards", entityList(spec.cards))
                spec.cardInfo?.let { replace("cardInfo", entityMapKeys(full["cardInfo"], it.keys)) }
            }
            is OptionsChoiceSpec -> spec.optionCardIds?.let { optionCards ->
                replace(
                    "optionCardIds",
                    JsonObject(optionCards.toSortedMap().entries.associate { (index, ids) ->
                        index.toString() to entityList(ids)
                    }),
                )
            }
            is LibrarySearchChoiceSpec -> {
                replace("options", entityList(spec.options))
                replace("cards", entityMapKeys(full["cards"], spec.cards.keys))
            }
            is LibraryReorderChoiceSpec -> {
                replace("cards", entityList(spec.cards))
                replace("cardInfo", entityMapKeys(full["cardInfo"], spec.cardInfo.keys))
            }
            is DamageAssignmentChoiceSpec -> {
                replace("attackerId", JsonPrimitive(reference(spec.attackerId)))
                replace("orderedTargets", entityList(spec.orderedTargets))
                spec.defenderId?.let { replace("defenderId", JsonPrimitive(reference(it))) }
                replace(
                    "minimumAssignments",
                    entityMapKeys(full["minimumAssignments"], spec.minimumAssignments.keys),
                )
                replace(
                    "defaultAssignments",
                    entityMapKeys(full["defaultAssignments"], spec.defaultAssignments.keys),
                )
            }
            is CombatResolutionChoiceSpec -> {
                replaceRecordEntities("attackers", spec.attackers.map { attacker ->
                    mapOf(
                        "id" to JsonPrimitive(reference(attacker.id)),
                        "attackedDefenderId" to JsonPrimitive(reference(attacker.attackedDefenderId)),
                        "blockedByIds" to entityList(attacker.blockedByIds),
                    )
                })
                replaceRecordEntities("blockers", spec.blockers.map { blocker ->
                    mapOf(
                        "id" to JsonPrimitive(reference(blocker.id)),
                        "blockedAttackerIds" to entityList(blocker.blockedAttackerIds),
                        "orderedAttackers" to entityList(blocker.orderedAttackers),
                    )
                })
                replaceRecordEntities("defenders", spec.defenders.map { defender ->
                    mapOf("id" to JsonPrimitive(reference(defender.id)))
                })
                replaceRecordEntities("edges", spec.edges.map { edge ->
                    mapOf(
                        "sourceId" to JsonPrimitive(reference(edge.sourceId)),
                        "targetId" to JsonPrimitive(reference(edge.targetId)),
                        "editableBy" to JsonPrimitive(reference(edge.editableBy)),
                    )
                })
                spec.coChooserId?.let { replace("coChooserId", JsonPrimitive(reference(it))) }
            }
            is ManaSourcesChoiceSpec -> {
                replaceRecordEntities("availableSources", spec.availableSources.map { source ->
                    mapOf("entityId" to JsonPrimitive(reference(source.entityId)))
                })
                replace("autoPaySuggestion", entityList(spec.autoPaySuggestion))
                replaceRecordEntities("waterbendPermanents", spec.waterbendPermanents.map { permanent ->
                    mapOf("entityId" to JsonPrimitive(reference(permanent.entityId)))
                })
            }
            is YesNoChoiceSpec,
            is BatchYesNoChoiceSpec,
            is ModesChoiceSpec,
            is ColorsChoiceSpec,
            is NumberChoiceSpec,
            is ReplacementChoiceSpec,
            is BudgetModesChoiceSpec -> Unit
        }
        return full
    }

    private fun put(raw: EntityId, safe: String, semantic: String) {
        rawToSafe.putIfAbsent(raw.value, safe)
        rawToSemantic.putIfAbsent(raw.value, semantic)
    }

    private companion object {
        val EMPTY_CARD_RUNTIME = ArgentumPolicyCardRuntime()
        val engineJson = Json { encodeDefaults = true; explicitNulls = true; classDiscriminator = "type" }
    }
}

private fun JsonElement.jsonObject(): JsonObject = this as? JsonObject
    ?: error("Expected JSON object, got $this")

private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.objectValue(name: String): JsonObject = this[name] as? JsonObject ?: buildJsonObject { }
