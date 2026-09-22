package org.mtgallium.agent.monored

import kotlinx.serialization.SerialName
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ln1p
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import org.mtgallium.agent.infoset.core.KNOWLEDGE_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.POLICY_HISTORY_COMMITMENT_ALGORITHM
import org.mtgallium.agent.infoset.core.POLICY_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyDecisionChoiceSpec
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyRestrictedMana
import org.mtgallium.agent.infoset.core.PERSPECTIVE_EVENT_SCHEMA_V1
import org.mtgallium.agent.infoset.core.PERSPECTIVE_EVENT_SCHEMA_V2
import org.mtgallium.agent.infoset.core.PERSPECTIVE_EVENT_SCHEMA_V3

const val VALUE_FEATURE_SCHEMA: String =
    "perspective-safe-outcome-value-features-v1"
const val VALUE_FEATURE_SCALING: String =
    "signed-log1p-after-sparse-aggregation-v1"

@Serializable
@SerialName("org.mtgallium.agent.searchteacher.ValueInputError")
enum class ValueInputError {
    INPUT_SCHEMA_MISMATCH,
    INPUT_PERSPECTIVE_MISMATCH,
    INPUT_PLAYER_CONTRACT_INVALID,
    INPUT_HISTORY_INVALID,
    INPUT_KNOWLEDGE_INCOMPLETE,
    INPUT_OUTCOME_PRESENT,
    INPUT_CURRENT_TURN_STATE_INCOMPLETE,
    INPUT_FEATURE_INVALID,
    INFERENCE_NONFINITE,
}

class ValueEvaluationException(
    val kind: ValueInputError,
    diagnostic: String,
    cause: Throwable? = null,
) : IllegalStateException("$kind: $diagnostic", cause)

/** Sparse vector produced by [ValueFeatures]. */
class ValueFeatureVector internal constructor(val values: Map<String, Double>)

/** Root-relative features from the player's current view, exact knowledge, and typed history. */
object ValueFeatures {
    private val projectedTapReasonsV1 = setOf("UNSPECIFIED", "TEAMWORK")
    private val keyEncoder = Base64.getUrlEncoder().withoutPadding()

    fun compile(
        information: InformationStateRepresentation,
        rootPlayer: String,
    ): ValueFeatureVector {
        if (rootPlayer.isBlank()) {
            failValueEvaluation(
                ValueInputError.INPUT_PERSPECTIVE_MISMATCH,
                "Root player must be explicit",
            )
        }
        if (
            information.schemaVersion != POLICY_SCHEMA_CURRENT ||
            information.knowledge.schemaVersion != KNOWLEDGE_SCHEMA_CURRENT ||
            information.historyCommitment.algorithm != POLICY_HISTORY_COMMITMENT_ALGORITHM
        ) {
            failValueEvaluation(
                ValueInputError.INPUT_SCHEMA_MISMATCH,
                "Value features require current policy, knowledge, and history schemas",
            )
        }
        if (
            information.observation.perspectivePlayerId != rootPlayer ||
            information.knowledge.perspectivePlayerId != rootPlayer
        ) {
            failValueEvaluation(
                ValueInputError.INPUT_PERSPECTIVE_MISMATCH,
                "Observation and exact knowledge must both be projected for root $rootPlayer",
            )
        }
        if (!information.observation.currentTurnStateComplete) {
            failValueEvaluation(
                ValueInputError.INPUT_CURRENT_TURN_STATE_INCOMPLETE,
                "Value features require complete current-turn state",
            )
        }
        if (!information.knowledge.epistemicallyComplete) {
            failValueEvaluation(
                ValueInputError.INPUT_KNOWLEDGE_INCOMPLETE,
                "Value features require complete represented knowledge",
            )
        }
        if (
            information.terminated || information.winnerId != null ||
            information.observation.players.any { it.lost } ||
            information.history.any {
                it.kind == PolicyHistoryEventKind.TERMINAL || it.detail is PerspectiveEventDetail.Terminal
            }
        ) {
            failValueEvaluation(
                ValueInputError.INPUT_OUTCOME_PRESENT,
                "Value features require a nonterminal position",
            )
        }
        val players = information.observation.players
        if (
            players.size != 2 || players.map { it.playerId }.distinct().size != 2 ||
            players.none { it.playerId == rootPlayer }
        ) {
            failValueEvaluation(
                ValueInputError.INPUT_PLAYER_CONTRACT_INVALID,
                "Value evaluation requires exactly one root and one opponent",
            )
        }
        val opponent = players.single { it.playerId != rootPlayer }.playerId
        val roles = RootRelativeRoles(rootPlayer, opponent)
        roles.requirePlayer(information.actingPlayerId, "acting player", nullAllowed = false)

        val features = SparseFeatureAccumulator()
        compileState(information, roles, features)
        compileObservation(information, roles, features)
        compileKnowledge(information, roles, features)
        compileHistory(information.history, roles, features)
        return ValueFeatureVector(features.snapshot())
    }

    private fun compileState(
        information: InformationStateRepresentation,
        roles: RootRelativeRoles,
        out: SparseFeatureAccumulator,
    ) {
        val observation = information.observation
        out.number("state", "turn-number", observation.turnNumber)
        out.category("state", "phase", observation.phase)
        out.category("state", "step", observation.step)
        out.category("state", "active-role", roles.role(observation.activePlayerId, "active player"))
        out.category("state", "priority-role", roles.role(observation.priorityPlayerId, "priority player"))
        out.category("state", "actor-role", roles.role(information.actingPlayerId, "acting player"))
    }

    private fun compileObservation(
        information: InformationStateRepresentation,
        roles: RootRelativeRoles,
        out: SparseFeatureAccumulator,
    ) {
        val observation = information.observation
        observation.players.forEach { player ->
            val role = roles.role(player.playerId, "player")
            out.number("player", "life", player.life, role)
            out.number("player", "hand-size", player.handSize, role)
            out.number("player", "library-size", player.librarySize, role)
            out.number("player", "graveyard-size", player.graveyardSize, role)
            out.number("player", "exile-size", player.exileSize, role)
            out.number("player", "speed", player.speed, role)
            out.number("player", "noncreature-spells-this-turn", player.noncreatureSpellsCastThisTurn, role)
            out.number("player", "red-noncombat-damage-this-turn", player.redNoncombatDamageDealtThisTurn, role)
            out.number("player", "land-plays-remaining", player.landPlaysRemainingThisTurn, role)
            out.flag("player", "active", player.active, role)
            out.flag("player", "priority", player.priority, role)
            out.flag("player", "lost-life-this-turn", player.lostLifeThisTurn, role)
            out.flag("player", "speed-trigger-fired-this-turn", player.speedIncreaseTriggerFiredThisTurn, role)
            compileMana(player.mana, role, out)
        }
        observation.zones.forEach { zone ->
            val ownerRole = roles.role(zone.ownerId, "zone owner")
            out.number("zone", "size", zone.size, ownerRole, zone.zone)
            out.flag("zone", "hidden", zone.hidden, ownerRole, zone.zone)
            // A hidden opponent zone contributes only its policy-visible size. Even a malformed
            // upstream DTO cannot turn contained referee identities into model features here.
            if (!zone.hidden || ownerRole == "root") {
                zone.cards.forEach { compileCard(it, ownerRole, zone.zone, roles, out) }
            }
        }
        observation.stack.forEach { item ->
            out.category("stack", "kind", item.kind)
            out.category("stack", "name", item.name)
            out.category("stack", "controller-role", roles.role(item.controllerId, "stack controller"))
            out.number("stack", "target-count", item.targets.size)
        }
        out.number("stack", "size", observation.stack.size)
        observation.combat?.let { combat ->
            out.category(
                "combat",
                "attacking-role",
                roles.role(combat.attackingPlayerId, "attacking player"),
            )
            out.number("combat", "attacker-count", combat.attackers.size)
            out.number("combat", "blocker-count", combat.blockers.size)
            out.number("combat", "attacker-blocker-edges", combat.attackers.sumOf { it.blockerObjectRefs.size })
            out.number(
                "combat",
                "blocker-attacker-edges",
                combat.blockers.sumOf { it.blockedAttackerObjectRefs.size },
            )
        } ?: out.category("combat", "present", "false")
        observation.pendingDecision?.let { compileDecision(it.choiceSpec, out) ;
            out.category("decision", "present", "true")
            out.category("decision", "kind", it.decisionKind)
            out.category("decision", "chooser-role", roles.role(it.playerId, "decision chooser"))
            out.category("decision", "phase", it.phase)
            it.sourceName?.let { name -> out.category("decision", "source-name", name) }
            out.flag("decision", "can-respond", it.canRespond)
        } ?: out.category("decision", "present", "false")
    }

    private fun compileMana(
        mana: PolicyManaPool,
        role: String,
        out: SparseFeatureAccumulator,
    ) {
        out.number("mana", "white", mana.white, role)
        out.number("mana", "blue", mana.blue, role)
        out.number("mana", "black", mana.black, role)
        out.number("mana", "red", mana.red, role)
        out.number("mana", "green", mana.green, role)
        out.number("mana", "colorless", mana.colorless, role)
        mana.restricted.forEach { compileRestrictedMana(it, role, out) }
    }

    private fun compileRestrictedMana(
        mana: PolicyRestrictedMana,
        role: String,
        out: SparseFeatureAccumulator,
    ) {
        out.number(
            "mana",
            "restricted",
            mana.count,
            role,
            mana.color ?: "colorless",
            mana.spendRestriction,
            mana.expiresAt,
        )
        mana.spellRiders.forEach { out.number("mana", "restricted-rider", mana.count, role, it) }
    }

    private fun compileCard(
        card: PolicyCardView,
        zoneOwnerRole: String,
        zone: String,
        roles: RootRelativeRoles,
        out: SparseFeatureAccumulator,
    ) {
        val ownerRole = roles.role(card.ownerId, "card owner")
        val controllerRole = roles.role(card.controllerId, "card controller")
        out.category("card", "present", zoneOwnerRole, zone, ownerRole, controllerRole)
        if (!card.faceDown) out.category("card", "name", card.name, zoneOwnerRole, zone, controllerRole)
        card.types.forEach { out.category("card", "type", it, zoneOwnerRole, zone, controllerRole) }
        card.subtypes.forEach { out.category("card", "subtype", it, zoneOwnerRole, zone, controllerRole) }
        card.colors.forEach { out.category("card", "color", it, zoneOwnerRole, zone, controllerRole) }
        card.keywords.forEach { out.category("card", "keyword", it, zoneOwnerRole, zone, controllerRole) }
        out.category("card", "mana-cost", card.manaCost, zoneOwnerRole, zone, controllerRole)
        out.number("card", "mana-value", card.manaValue, zoneOwnerRole, zone, controllerRole)
        card.power?.let { out.number("card", "power", it, zoneOwnerRole, zone, controllerRole) }
        card.toughness?.let { out.number("card", "toughness", it, zoneOwnerRole, zone, controllerRole) }
        out.number("card", "damage-marked", card.damageMarked, zoneOwnerRole, zone, controllerRole)
        out.flag("card", "tapped", card.tapped, zoneOwnerRole, zone, controllerRole)
        out.flag("card", "summoning-sick", card.summoningSick, zoneOwnerRole, zone, controllerRole)
        out.flag("card", "face-down", card.faceDown, zoneOwnerRole, zone, controllerRole)
        out.flag("card", "warped", card.isWarped, zoneOwnerRole, zone, controllerRole)
        out.flag("card", "warp-exiled", card.isWarpExiled, zoneOwnerRole, zone, controllerRole)
        out.flag("card", "playable-from-exile", card.playableFromExile, zoneOwnerRole, zone, controllerRole)
        out.flag(
            "card",
            "tracked-activation-used",
            card.hasActivatedAbilityThisTurn,
            zoneOwnerRole,
            zone,
            controllerRole,
        )
        card.counters.forEach { (kind, count) ->
            out.number("card", "counter", count, zoneOwnerRole, zone, controllerRole, kind)
        }
    }

    private fun compileDecision(
        spec: PolicyDecisionChoiceSpec?,
        out: SparseFeatureAccumulator,
    ) {
        when (spec) {
            null -> out.category("decision", "choice-shape", "none")
            is PolicyDecisionChoiceSpec.Targets -> {
                out.category("decision", "choice-shape", "targets")
                out.number("decision", "requirement-count", spec.requirements.size)
                out.number("decision", "legal-target-count", spec.legalTargets.values.sumOf { it.size })
                out.flag("decision", "can-cancel", spec.canCancel)
            }
            is PolicyDecisionChoiceSpec.Cards -> {
                out.category("decision", "choice-shape", "cards")
                out.number("decision", "option-count", spec.options.size)
                out.number("decision", "minimum", spec.minSelections)
                out.number("decision", "maximum", spec.maxSelections)
                out.flag("decision", "ordered", spec.ordered)
            }
            is PolicyDecisionChoiceSpec.YesNo -> out.category("decision", "choice-shape", "yes-no")
            is PolicyDecisionChoiceSpec.BatchYesNo -> {
                out.category("decision", "choice-shape", "batch-yes-no")
                out.number("decision", "count", spec.count)
            }
            is PolicyDecisionChoiceSpec.Modes -> {
                out.category("decision", "choice-shape", "modes")
                out.number("decision", "option-count", spec.modes.size)
                out.number("decision", "minimum", spec.minModes)
                out.number("decision", "maximum", spec.maxModes)
            }
            is PolicyDecisionChoiceSpec.Colors -> {
                out.category("decision", "choice-shape", "colors")
                spec.colors.forEach { out.category("decision", "color", it) }
            }
            is PolicyDecisionChoiceSpec.Number -> {
                out.category("decision", "choice-shape", "number")
                out.number("decision", "minimum", spec.minimum)
                out.number("decision", "maximum", spec.maximum)
            }
            is PolicyDecisionChoiceSpec.Distribution -> {
                out.category("decision", "choice-shape", "distribution")
                out.number("decision", "total", spec.total)
                out.number("decision", "target-count", spec.targets.size)
                out.number("decision", "minimum-per-target", spec.minimumPerTarget)
                out.flag("decision", "allow-partial", spec.allowPartial)
            }
            is PolicyDecisionChoiceSpec.Order -> {
                out.category("decision", "choice-shape", "order")
                out.number("decision", "object-count", spec.objects.size)
            }
            is PolicyDecisionChoiceSpec.Piles -> {
                out.category("decision", "choice-shape", "piles")
                out.number("decision", "card-count", spec.cards.size)
                out.number("decision", "pile-count", spec.numberOfPiles)
            }
            is PolicyDecisionChoiceSpec.Options -> {
                out.category("decision", "choice-shape", "options")
                out.number("decision", "option-count", spec.options.size)
                out.flag("decision", "can-cancel", spec.canCancel)
            }
            is PolicyDecisionChoiceSpec.Replacement -> {
                out.category("decision", "choice-shape", "replacement")
                out.number("decision", "from-count", spec.fromOptions.size)
                out.number("decision", "to-count", spec.toOptions.size)
            }
            is PolicyDecisionChoiceSpec.LibrarySearch -> {
                out.category("decision", "choice-shape", "library-search")
                out.number("decision", "option-count", spec.options.size)
                out.number("decision", "minimum", spec.minSelections)
                out.number("decision", "maximum", spec.maxSelections)
            }
            is PolicyDecisionChoiceSpec.LibraryReorder -> {
                out.category("decision", "choice-shape", "library-reorder")
                out.number("decision", "card-count", spec.cards.size)
            }
            is PolicyDecisionChoiceSpec.DamageAssignment -> {
                out.category("decision", "choice-shape", "damage-assignment")
                out.number("decision", "target-count", spec.orderedTargets.size)
                out.number("decision", "minimum-entry-count", spec.minimumAssignments.size)
                out.number("decision", "default-entry-count", spec.defaultAssignments.size)
                out.flag("decision", "trample", spec.hasTrample)
                out.flag("decision", "deathtouch", spec.hasDeathtouch)
            }
            is PolicyDecisionChoiceSpec.CombatResolution -> {
                out.category("decision", "choice-shape", "combat-resolution")
                out.number("decision", "contract-field-count", spec.contract.size)
            }
            is PolicyDecisionChoiceSpec.ManaSources -> {
                out.category("decision", "choice-shape", "mana-sources")
                out.number("decision", "contract-field-count", spec.contract.size)
            }
            is PolicyDecisionChoiceSpec.BudgetModal -> {
                out.category("decision", "choice-shape", "budget-modal")
                out.number("decision", "contract-field-count", spec.contract.size)
            }
        }
    }

    private fun compileKnowledge(
        information: InformationStateRepresentation,
        roles: RootRelativeRoles,
        out: SparseFeatureAccumulator,
    ) {
        val knowledge = information.knowledge
        knowledge.deckCardCounts.forEach { (player, cards) ->
            val role = roles.role(player, "known-deck player")
            cards.forEach { (name, count) -> out.number("knowledge", "deck-card", count, role, name) }
        }
        knowledge.zones.forEach { zone ->
            val role = roles.role(zone.ownerId, "knowledge zone owner")
            out.number("knowledge", "zone-size", zone.size, role, zone.zone)
            zone.knownCardCounts.forEach { (name, count) ->
                out.number("knowledge", "known-zone-card", count, role, zone.zone, name)
            }
        }
        knowledge.knownObjects.forEach { known ->
            out.category(
                "knowledge",
                "known-object",
                roles.role(known.ownerId, "known object owner"),
                known.zone,
                known.cardName,
            )
        }
        knowledge.knownLibraryOrders.forEach { order ->
            val role = roles.role(order.playerId, "known library owner")
            out.number("knowledge", "shuffle-epoch", order.shuffleEpoch, role)
            order.top.forEachIndexed { index, name ->
                out.category("knowledge", "library-top", role, index.toString(), name ?: "unknown")
            }
            order.bottom.forEachIndexed { index, name ->
                out.category("knowledge", "library-bottom", role, index.toString(), name ?: "unknown")
            }
        }
        knowledge.unlocatedCardCounts.forEach { (player, cards) ->
            val role = roles.role(player, "unlocated-card player")
            cards.forEach { (name, count) ->
                out.number("knowledge", "unlocated-card", count, role, name)
            }
        }
        out.number("knowledge", "unsupported-reason-count", knowledge.unsupportedReasons.size)
    }

    private fun compileHistory(
        history: List<PolicyHistoryEvent>,
        roles: RootRelativeRoles,
        out: SparseFeatureAccumulator,
    ) {
        out.number("history", "event-count", history.size)
        history.forEachIndexed { index, event ->
            val detail = event.detail
            if (detail == null) {
                validateCoarseVisibleTransition(event, roles)
            } else {
                validateHistoryDetail(event, detail, roles)
            }
            out.category("history", "kind", event.kind.name)
            out.category("history", "audience", event.audience.scope.name)
            event.audience.entitledPlayerIds.forEach {
                roles.requirePlayer(it, "history audience", nullAllowed = false)
                out.category("history", "entitled-role", roles.role(it, "history audience"))
            }
            out.category("history", "actor-role", roles.role(event.actor, "history actor"))
            out.category("history", "recency", event.kind.name, recencyBucket(history.lastIndex - index))
            detail?.let { compileHistoryDetail(it, roles, out) }
        }
    }

    /** Non-priority visible transitions contribute kind, audience, actor role, and recency. */
    private fun validateCoarseVisibleTransition(
        event: PolicyHistoryEvent,
        roles: RootRelativeRoles,
    ) {
        if (event.kind !in setOf(
                PolicyHistoryEventKind.FORCED_TRANSITION,
                PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION,
            ) || event.actor != null ||
            event.audience.scope != org.mtgallium.agent.infoset.core.PolicyAudienceScope.ENTITLED_PLAYERS ||
            event.audience.entitledPlayerIds.size != 1 ||
            !roles.isRootPlayer(event.audience.entitledPlayerIds.singleOrNull())
        ) {
            failValueEvaluation(
                ValueInputError.INPUT_HISTORY_INVALID,
                "Null-detail history event ${event.eventId} is not the current projected coarse visible transition",
            )
        }

        val payload = event.payload
        val optionalPriorityKeys = setOf("priorityFrom", "priorityTo")
        val requiredKeys = setOf("fromObservation", "toObservation", "zoneDelta")
        if (
            !payload.keys.containsAll(requiredKeys) ||
            (payload.keys - requiredKeys - optionalPriorityKeys).isNotEmpty() ||
            ("priorityFrom" in payload) != ("priorityTo" in payload) ||
            !payload.stringValue("fromObservation") ||
            !payload.stringValue("toObservation") ||
            !payload.validVisibleZoneDelta()
        ) {
            failValueEvaluation(
                ValueInputError.INPUT_HISTORY_INVALID,
                "Null-detail history event ${event.eventId} does not have the projected coarse-transition payload shape",
            )
        }
        if ("priorityFrom" in payload && !payload.validPriorityChange(roles)) {
            failValueEvaluation(
                ValueInputError.INPUT_HISTORY_INVALID,
                "Null-detail history event ${event.eventId} has an invalid projected priority change",
            )
        }
    }

    private fun JsonObject.stringValue(key: String): Boolean =
        this[key].let { it is JsonPrimitive && it.isString && it.content.isNotBlank() }

    private fun JsonObject.validVisibleZoneDelta(): Boolean {
        val changes = this["zoneDelta"] as? JsonArray ?: return false
        if (!changes.all { entry ->
                val change = entry as? JsonObject ?: return@all false
                change.keys == setOf("key", "before", "after") &&
                    change.stringValue("key") &&
                    change.visibleZoneCountChange()
            }
        ) return false
        val keys = changes.map { ((it as JsonObject).getValue("key") as JsonPrimitive).content }
        return keys == keys.distinct().sorted()
    }

    private fun JsonObject.visibleZoneCountChange(): Boolean {
        val before = (this["before"] as? JsonPrimitive)?.intOrNull
        val after = (this["after"] as? JsonPrimitive)?.intOrNull
        return before != null && after != null && before >= 0 && after >= 0 && before != after
    }

    private fun JsonObject.validPriorityChange(roles: RootRelativeRoles): Boolean {
        val before = this["priorityFrom"]
        val after = this["priorityTo"]
        if (!before.isProjectedPlayerAliasOrNull(roles) || !after.isProjectedPlayerAliasOrNull(roles)) return false
        return before != after
    }

    private fun JsonElement?.isProjectedPlayerAliasOrNull(roles: RootRelativeRoles): Boolean = when (this) {
        JsonNull -> true
        is JsonPrimitive -> isString && roles.playerRoleOrNull(content) != null
        else -> false
    }

    private fun validateHistoryDetail(
        event: PolicyHistoryEvent,
        detail: PerspectiveEventDetail,
        roles: RootRelativeRoles,
    ) {
        fun requireContract(schema: Int, vararg kinds: PolicyHistoryEventKind) {
            if (detail.schemaVersion != schema) {
                failValueEvaluation(
                    ValueInputError.INPUT_HISTORY_INVALID,
                    "History event ${event.eventId} has unsupported ${detail::class.simpleName} schema ${detail.schemaVersion}",
                )
            }
            if (event.kind !in kinds) {
                failValueEvaluation(
                    ValueInputError.INPUT_HISTORY_INVALID,
                    "History event ${event.eventId} kind ${event.kind} is incompatible with ${detail::class.simpleName}",
                )
            }
        }

        when (detail) {
            is PerspectiveEventDetail.Choice -> requireContract(
                PERSPECTIVE_EVENT_SCHEMA_V3,
                PolicyHistoryEventKind.ACTION,
                PolicyHistoryEventKind.PRIORITY_PASS,
                PolicyHistoryEventKind.MULLIGAN,
                PolicyHistoryEventKind.COMBAT_DECLARATION,
                PolicyHistoryEventKind.DECISION,
                PolicyHistoryEventKind.PRIVATE_DECISION_OCCURRED,
            )
            is PerspectiveEventDetail.ZoneChange ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V2, PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION)
            is PerspectiveEventDetail.Draw ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.DRAW)
            is PerspectiveEventDetail.Reveal,
            is PerspectiveEventDetail.Look,
            is PerspectiveEventDetail.LibraryReorder ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.REVEAL)
            is PerspectiveEventDetail.Shuffle ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V2, PolicyHistoryEventKind.SHUFFLE)
            is PerspectiveEventDetail.LifeChange ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.LIFE_CHANGE)
            is PerspectiveEventDetail.Damage ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.DAMAGE)
            is PerspectiveEventDetail.CounterChange ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.COUNTER_CHANGE)
            is PerspectiveEventDetail.ObjectState -> {
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.OBJECT_STATE)
                validateObjectState(detail, event.eventId, roles)
            }
            is PerspectiveEventDetail.Causal ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.CAUSAL)
            is PerspectiveEventDetail.ResourceChange ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.RESOURCE_CHANGE)
            is PerspectiveEventDetail.CharacteristicChange ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.CHARACTERISTIC_CHANGE)
            is PerspectiveEventDetail.Combat ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V2, PolicyHistoryEventKind.COMBAT_DECLARATION)
            is PerspectiveEventDetail.TurnStructure ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.TURN_STRUCTURE)
            is PerspectiveEventDetail.Terminal ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.TERMINAL)
            is PerspectiveEventDetail.UnsupportedVisibleTransition ->
                requireContract(PERSPECTIVE_EVENT_SCHEMA_V1, PolicyHistoryEventKind.UNSUPPORTED_VISIBLE_TRANSITION)
        }
    }

    /** Exact ObjectState vocabulary emitted by [PerspectiveEventProjector], without raw references. */
    private fun validateObjectState(
        detail: PerspectiveEventDetail.ObjectState,
        eventId: Long,
        roles: RootRelativeRoles,
    ) {
        if (detail.objectName.isNullOrBlank()) {
            failValueEvaluation(
                ValueInputError.INPUT_HISTORY_INVALID,
                "Object-state history event $eventId has no projected object name",
            )
        }
        fun requireShape(valid: Boolean, expected: String) {
            if (!valid) {
                failValueEvaluation(
                    ValueInputError.INPUT_HISTORY_INVALID,
                    "Object-state history event $eventId is not the projected $expected shape",
                )
            }
        }
        when (detail.change) {
            "TAPPED" -> requireShape(
                detail.value in projectedTapReasonsV1 && detail.relatedObjectRefs.isEmpty(),
                "TAPPED(reason enum)",
            )
            "UNTAPPED" -> requireShape(
                detail.value == null && detail.relatedObjectRefs.isEmpty(),
                "UNTAPPED(null)",
            )
            "ATTACHED", "UNATTACHED" -> requireShape(
                detail.value == null && detail.relatedObjectRefs.isNotEmpty(),
                "${detail.change}(null + related refs)",
            )
            "TRANSFORMED" -> requireShape(
                detail.value in setOf("true", "false") && detail.relatedObjectRefs.isEmpty(),
                "TRANSFORMED(boolean string)",
            )
            "CONTROLLER_CHANGED" -> {
                requireShape(detail.value != null && detail.relatedObjectRefs.isEmpty(), "CONTROLLER_CHANGED(player alias)")
                roles.requirePlayer(detail.value, "object-state controller value", nullAllowed = false)
            }
            else -> failValueEvaluation(
                ValueInputError.INPUT_HISTORY_INVALID,
                "Object-state history event $eventId has unsupported projected change ${detail.change}",
            )
        }
    }

    private fun compileHistoryDetail(
        detail: PerspectiveEventDetail,
        roles: RootRelativeRoles,
        out: SparseFeatureAccumulator,
    ) {
        when (detail) {
            is PerspectiveEventDetail.Choice -> {
                out.category("history", "choice-kind", detail.choiceKind)
                detail.operationFamily?.let { out.category("history", "choice-family", it.name) }
                out.flag("history", "choice-private", detail.privateToActor)
                detail.strategicallyOptional?.let { out.flag("history", "choice-optional", it) }
                detail.libraryBottomCardNames.forEach {
                    out.category("history", "bottomed-card", it)
                }
            }
            is PerspectiveEventDetail.ZoneChange -> {
                val role = roles.role(detail.ownerId, "zone-change owner")
                out.category("history", "zone-change", role, detail.fromZone ?: "none", detail.toZone)
                detail.cardName?.let { out.category("history", "zone-change-card", role, it) }
                out.flag("history", "zone-change-continues", detail.continuesAsCurrentObject, role)
            }
            is PerspectiveEventDetail.Draw -> {
                val role = roles.role(detail.playerId, "draw player")
                out.number("history", "draw-count", detail.count, role)
                detail.knownCardNames.forEach { out.category("history", "drawn-known-card", role, it) }
            }
            is PerspectiveEventDetail.Reveal -> {
                val role = roles.role(detail.ownerId, "reveal owner")
                detail.cardNames.forEach {
                    out.category("history", "revealed-card", role, detail.zone ?: "none", it)
                }
            }
            is PerspectiveEventDetail.Look -> {
                val role = roles.role(detail.ownerId, "look owner")
                out.number("history", "look-count", detail.cardNames.size, role, detail.zone)
                out.flag("history", "look-ordered", detail.ordered, role, detail.zone)
                out.flag("history", "look-from-top", detail.fromTop, role, detail.zone)
                detail.cardNames.forEach { out.category("history", "look-card", role, detail.zone, it) }
            }
            is PerspectiveEventDetail.LibraryReorder -> {
                val role = roles.role(detail.playerId, "library-reorder player")
                detail.orderedCardNames.forEachIndexed { index, name ->
                    out.category("history", "library-reorder", role, index.toString(), name)
                }
            }
            is PerspectiveEventDetail.Shuffle -> {
                val role = roles.role(detail.playerId, "shuffle player")
                out.category("history", "shuffle", role)
            }
            is PerspectiveEventDetail.LifeChange -> {
                val role = roles.role(detail.playerId, "life-change player")
                out.number("history", "life-delta", detail.newLife - detail.oldLife, role)
            }
            is PerspectiveEventDetail.Damage -> {
                out.number("history", "damage", detail.amount, if (detail.combat) "combat" else "noncombat")
                val sourceRole = roles.playerRoleOrNull(detail.sourceObjectRef)
                val targetRole = roles.playerRoleOrNull(detail.targetObjectRef)
                if (sourceRole != null) {
                    out.category("history", "damage-source-role", sourceRole)
                } else {
                    detail.sourceName?.let { out.category("history", "damage-source", it) }
                }
                if (targetRole != null) {
                    out.category("history", "damage-target-role", targetRole)
                } else {
                    detail.targetName?.let { out.category("history", "damage-target", it) }
                }
            }
            is PerspectiveEventDetail.CounterChange -> {
                out.number("history", "counter-delta", detail.delta, detail.objectName, detail.counterType)
            }
            is PerspectiveEventDetail.ObjectState -> {
                out.category("history", "object-state", detail.objectName ?: "unknown", detail.change)
                when (detail.change) {
                    "TAPPED" -> out.category("history", "object-state-tap-reason", detail.value!!)
                    "TRANSFORMED" -> out.flag(
                        "history",
                        "object-state-transformed-into-back-face",
                        detail.value!!.toBoolean(),
                    )
                    "CONTROLLER_CHANGED" -> out.category(
                        "history",
                        "object-state-controller-role",
                        roles.role(detail.value, "object-state controller value"),
                    )
                }
                detail.relatedObjectRefs.mapNotNull(roles::playerRoleOrNull).forEach {
                    out.category("history", "object-state-related-player", detail.change, it)
                }
            }
            is PerspectiveEventDetail.Causal -> {
                out.category("history", "causal-type", detail.eventType)
                out.category("history", "causal-actor", roles.role(detail.actorId, "causal actor"))
                roles.playerRoleOrNull(detail.sourceObjectRef)?.let {
                    out.category("history", "causal-source-role", it)
                } ?: detail.sourceName?.let { out.category("history", "causal-source", it) }
                detail.targetNames.forEachIndexed { index, name ->
                    roles.playerRoleOrNull(detail.targetObjectRefs.getOrNull(index))?.let {
                        out.category("history", "causal-target-role", it)
                    } ?: out.category("history", "causal-target", name)
                }
                detail.numericValue?.let { out.number("history", "causal-number", it, detail.eventType) }
                detail.sourceOwnerId?.let {
                    out.category("history", "causal-owner", roles.role(it, "causal source owner"))
                }
                detail.sourceZoneAfter?.let { out.category("history", "causal-zone-after", it) }
            }
            is PerspectiveEventDetail.ResourceChange -> {
                val role = roles.role(detail.playerId, "resource-change player")
                out.number("history", "resource-delta", detail.delta, role, detail.resource)
                detail.sourceName?.let { out.category("history", "resource-source", role, it) }
            }
            is PerspectiveEventDetail.CharacteristicChange -> {
                out.category(
                    "history",
                    "characteristic-change",
                    detail.objectName,
                    detail.characteristic,
                    detail.value,
                )
                detail.sourceName?.let { out.category("history", "characteristic-source", it) }
            }
            is PerspectiveEventDetail.Combat -> {
                out.category("history", "combat-declaration", detail.declaration)
                out.category("history", "combat-actor", roles.role(detail.actorId, "combat actor"))
                detail.subjects.forEach { subject ->
                    val subjectRole = roles.playerRoleOrNull(subject.objectRef)
                    if (subjectRole != null) {
                        out.category("history", "combat-subject-role", subjectRole)
                    } else {
                        out.category("history", "combat-subject", subject.objectName)
                    }
                    out.number("history", "combat-related-count", subject.relatedObjectNames.size, subject.objectName)
                    subject.relatedObjectNames.forEachIndexed { index, name ->
                        roles.playerRoleOrNull(subject.relatedObjectRefs.getOrNull(index))?.let {
                            out.category("history", "combat-related-role", subject.objectName, it)
                        } ?: out.category("history", "combat-related-name", subject.objectName, name)
                    }
                    out.number(
                        "history",
                        "combat-assigned-amount",
                        subject.amountsByRelatedObjectRef.values.sum(),
                        subject.objectName,
                    )
                }
            }
            is PerspectiveEventDetail.TurnStructure -> {
                detail.turnNumber?.let { out.number("history", "turn-number", it) }
                detail.phase?.let { out.category("history", "turn-phase", it) }
                detail.step?.let { out.category("history", "turn-step", it) }
                out.category("history", "turn-active", roles.role(detail.activePlayerId, "turn active player"))
                out.category("history", "turn-priority", roles.role(detail.priorityPlayerId, "turn priority player"))
            }
            is PerspectiveEventDetail.Terminal -> failValueEvaluation(
                ValueInputError.INPUT_OUTCOME_PRESENT,
                "Value features require nonterminal history",
            )
            is PerspectiveEventDetail.UnsupportedVisibleTransition -> {
                out.category("history", "unsupported-visible-transition", detail.engineEventType)
            }
        }
    }

    private fun recencyBucket(distance: Int): String = when (distance) {
        in 0..3 -> "0-3"
        in 4..15 -> "4-15"
        else -> "16-plus"
    }

    private fun featureKey(namespace: String, parts: Array<out String>): String =
        "$namespace/${parts.joinToString("/") { keyEncoder.encodeToString(it.toByteArray(Charsets.UTF_8)) }}"

    private class SparseFeatureAccumulator {
        private val values = linkedMapOf<String, Double>()

        fun number(namespace: String, field: String, value: Int, vararg context: String) =
            add(featureKey(namespace, arrayOf(field, *context)), value.toDouble())

        fun category(namespace: String, field: String, vararg values: String) {
            add(featureKey(namespace, arrayOf(field, *values)), 1.0)
        }

        fun flag(namespace: String, field: String, value: Boolean, vararg context: String) {
            val values = buildList {
                addAll(context)
                add(value.toString())
            }.toTypedArray()
            category(namespace, field, *values)
        }

        private fun add(key: String, amount: Double) {
            if (!amount.isFinite()) {
                failValueEvaluation(
                    ValueInputError.INPUT_FEATURE_INVALID,
                    "Feature $key is non-finite",
                )
            }
            if (amount == 0.0) return
            val updated = (values[key] ?: 0.0) + amount
            if (!updated.isFinite()) {
                failValueEvaluation(
                    ValueInputError.INPUT_FEATURE_INVALID,
                    "Feature accumulation overflowed for $key",
                )
            }
            if (updated == 0.0) values.remove(key) else values[key] = updated
        }

        fun snapshot(): Map<String, Double> = values
            .mapValues { (key, aggregate) ->
                val scaled = when {
                    aggregate > 0.0 -> ln1p(aggregate)
                    aggregate < 0.0 -> -ln1p(abs(aggregate))
                    else -> 0.0
                }
                if (!scaled.isFinite()) {
                    failValueEvaluation(
                        ValueInputError.INPUT_FEATURE_INVALID,
                        "Final scaled feature $key is non-finite",
                    )
                }
                scaled
            }
            .filterValues { it != 0.0 }
            .toSortedMap()
    }

    private class RootRelativeRoles(
        private val rootPlayer: String,
        private val opponentPlayer: String,
    ) {
        fun isRootPlayer(playerId: String?): Boolean = playerId == rootPlayer

        fun role(playerId: String?, field: String): String = when (playerId) {
            null -> "none"
            rootPlayer -> "root"
            opponentPlayer -> "opponent"
            else -> failValueEvaluation(
                ValueInputError.INPUT_PLAYER_CONTRACT_INVALID,
                "$field names a player outside the root/opponent contract",
            )
        }

        fun requirePlayer(playerId: String?, field: String, nullAllowed: Boolean) {
            if (playerId == null && nullAllowed) return
            if (playerId != rootPlayer && playerId != opponentPlayer) {
                failValueEvaluation(
                    ValueInputError.INPUT_PLAYER_CONTRACT_INVALID,
                    "$field names a player outside the root/opponent contract",
                )
            }
        }

        fun playerRoleOrNull(reference: String?): String? = when (reference) {
            rootPlayer -> "root"
            opponentPlayer -> "opponent"
            else -> null
        }
    }
}

internal fun failValueEvaluation(
    kind: ValueInputError,
    diagnostic: String,
    cause: Throwable? = null,
): Nothing = throw ValueEvaluationException(kind, diagnostic, cause)
