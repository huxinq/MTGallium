package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.BoundedPolicyInput
import org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V4
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicyStackItemView
import org.mtgallium.agent.infoset.core.PolicyTrajectoryDecision
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticActionIntent
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance

internal const val NEURAL_BC_PROTOCOL = "neural-behavioral-cloning-v1"
internal const val NEURAL_BC_ORIGINAL_FEATURE_SCHEMA = "bounded-v5-semantic-candidates-v1"
internal const val NEURAL_BC_FEATURE_SCHEMA =
    "bounded-v5-semantic-candidates-v2-ordered-arrays-and-entity-ordinals"
internal const val NEURAL_BC_MODEL_SCHEMA = 1
private const val SPLIT_PROTOCOL = "complete-game-sha256-70-15-15-v1"
internal const val PRIMARY_MIN_CANDIDATES = 2

internal enum class NeuralBcFeatureProjection {
    /** Exact issue-0022 projection retained only to reproduce its collision diagnostic. */
    ISSUE_0022_ORIGINAL,

    /** Preserve ordered candidate arrays and semantic entity ordinals. */
    REPAIRED,
}

/** Feature-only candidate view shared by current V4 inference and the narrow historical V3 reader. */
internal data class NeuralBcFeatureCandidate(
    val kind: SemanticChoiceKind,
    val operationFamily: SemanticOperationFamily,
    val actionIntent: SemanticActionIntent,
    val canonicalPayload: JsonObject,
)

/**
 * Fields legitimately consumed by the compact encoder. Historical readers validate their own
 * persisted contract before constructing this view; no V3 artifact is promoted to a V4 DTO.
 */
internal data class NeuralBcFeatureInput(
    val actingPlayerId: String?,
    val observation: PolicyObservation,
    val knowledge: PolicyKnowledgeState,
    val recentEvents: List<PolicyHistoryEvent>,
    val candidates: List<NeuralBcFeatureCandidate>,
    val candidateSchemaVersion: Int,
) {
    init {
        require(candidateSchemaVersion == CANDIDATE_SCHEMA_V3 || candidateSchemaVersion == CANDIDATE_SCHEMA_V4)
        require(candidates.isNotEmpty())
    }

    companion object {
        fun current(input: BoundedPolicyInput): NeuralBcFeatureInput = NeuralBcFeatureInput(
            actingPlayerId = input.actingPlayerId,
            observation = input.observation,
            knowledge = input.knowledge,
            recentEvents = input.recentEvents,
            candidates = input.candidates.map(SemanticChoice::toNeuralBcFeatureCandidate),
            candidateSchemaVersion = input.candidateSchemaVersion,
        )
    }
}

/** One named pre-hash contribution retained only for feature-scale diagnostics. */
internal data class NeuralBcFeatureEmission(
    val feature: String,
    val value: Double,
    val bucket: Int,
)

internal data class NeuralBcFeatureEmissionAudit(
    val state: SparseFeatureVector,
    val stateEmissions: List<NeuralBcFeatureEmission>,
    val candidates: List<SparseFeatureVector>,
    val candidateEmissions: List<List<NeuralBcFeatureEmission>>,
)

private fun SemanticChoice.toNeuralBcFeatureCandidate(): NeuralBcFeatureCandidate = NeuralBcFeatureCandidate(
    kind = kind,
    operationFamily = operationFamily,
    actionIntent = actionIntent,
    canonicalPayload = canonicalPayload,
)

/**
 * One deliberately narrow neural boundary over the already-admitted BC tuple.
 *
 * Integrity hashes, semantic signatures, display text, object routing references, evidence
 * provenance, search statistics, belief/search samples, and terminal returns never become model
 * features. Semantic payload references are used only as joins to visible card/stack descriptors;
 * neither the reference nor its descriptor digest is emitted.
 */
internal class NeuralBehavioralCloningFeatureEncoder(
    val stateDimension: Int = 1_024,
    val candidateDimension: Int = 512,
    internal val projection: NeuralBcFeatureProjection = NeuralBcFeatureProjection.REPAIRED,
) {
    init {
        require(stateDimension > 0)
        require(candidateDimension > 0)
    }

    fun encode(example: BehavioralCloningExample): EncodedBcDecision {
        val label = example.policyInput.candidates.indexOf(example.teacherAction)
        require(label >= 0) {
            "Teacher action is not an exact member of the bounded candidate set for " +
                "${example.gameId}:${example.decisionIndex}"
        }
        return encode(example.policyInput, label, example.gameId, example.decisionIndex)
    }

    fun encode(
        input: BoundedPolicyInput,
        labelIndex: Int,
        gameId: String = "inference",
        decisionIndex: Int = 0,
    ): EncodedBcDecision {
        input.requireValidDigest()
        return encode(
            input = NeuralBcFeatureInput.current(input),
            labelIndex = labelIndex,
            gameId = gameId,
            decisionIndex = decisionIndex,
        )
    }

    internal fun encode(
        input: NeuralBcFeatureInput,
        labelIndex: Int,
        gameId: String,
        decisionIndex: Int,
    ): EncodedBcDecision {
        require(labelIndex in input.candidates.indices)
        val context = VisibleSemanticContext(
            input.observation,
            input.actingPlayerId,
            input.candidateSchemaVersion,
            projection,
        )
        return EncodedBcDecision(
            gameId = gameId,
            decisionIndex = decisionIndex,
            decisionFamily = decisionFamily(input),
            state = encodeState(input, context, audit = null),
            candidates = input.candidates.map { encodeCandidate(it, context, audit = null) },
            candidateFamilies = input.candidates.map { it.operationFamily },
            candidateIntents = input.candidates.map { it.actionIntent.kind },
            labelIndex = labelIndex,
        )
    }

    fun encodeForInference(input: BoundedPolicyInput): EncodedBcDecision =
        encode(input, labelIndex = 0)

    internal fun auditedStateFeatures(input: BoundedPolicyInput): Set<String> {
        input.requireValidDigest()
        val audit = linkedSetOf<String>()
        val featureInput = NeuralBcFeatureInput.current(input)
        encodeState(
            featureInput,
            VisibleSemanticContext(
                featureInput.observation,
                featureInput.actingPlayerId,
                featureInput.candidateSchemaVersion,
                projection,
            ),
            audit,
        )
        return audit
    }

    internal fun auditedCandidateFeatures(input: BoundedPolicyInput, choice: SemanticChoice): Set<String> {
        input.requireValidDigest()
        val audit = linkedSetOf<String>()
        encodeCandidate(
            choice.toNeuralBcFeatureCandidate(),
            VisibleSemanticContext(
                input.observation,
                input.actingPlayerId,
                input.candidateSchemaVersion,
                projection,
            ),
            audit,
        )
        return audit
    }

    internal fun auditedSemanticObjectReferences(input: BoundedPolicyInput): Set<String> {
        input.requireValidDigest()
        return auditedSemanticObjectReferences(
            input.observation,
            input.actingPlayerId,
            input.candidateSchemaVersion,
        )
    }

    internal fun auditedSemanticObjectReferences(
        observation: PolicyObservation,
        actingPlayerId: String?,
        candidateSchemaVersion: Int,
    ): Set<String> {
        return VisibleSemanticContext(
            observation,
            actingPlayerId,
            candidateSchemaVersion,
            projection,
        ).semanticObjectReferences()
    }

    /** Tiny projection witness for JSON-only feature invariants; it bypasses engine setup. */
    internal fun auditedCandidateJsonFeatures(
        observation: PolicyObservation,
        actingPlayerId: String?,
        candidateSchemaVersion: Int,
        path: String,
        value: JsonElement,
    ): Set<String> {
        val audit = linkedSetOf<String>()
        emitSafeJson(
            sink = FeatureSink(candidateDimension, audit),
            path = path,
            value = value,
            context = VisibleSemanticContext(
                observation,
                actingPlayerId,
                candidateSchemaVersion,
                projection,
            ),
            mode = JsonFeatureMode.CANDIDATE,
        )
        return audit
    }

    /**
     * Retains the named additive contributions that produce the exact hashed vectors. This is an
     * evaluation-only scale audit: names never become model inputs and the ordinary encoding path
     * remains unchanged.
     */
    internal fun auditedFeatureEmissions(input: NeuralBcFeatureInput): NeuralBcFeatureEmissionAudit {
        val context = VisibleSemanticContext(
            input.observation,
            input.actingPlayerId,
            input.candidateSchemaVersion,
            projection,
        )
        val stateEmissions = mutableListOf<NeuralBcFeatureEmission>()
        val state = encodeState(input, context, audit = null, emissions = stateEmissions)
        val candidateAudits = input.candidates.map { choice ->
            val emissions = mutableListOf<NeuralBcFeatureEmission>()
            encodeCandidate(choice, context, audit = null, emissions = emissions) to emissions
        }
        val candidates = candidateAudits.map { it.first }
        val candidateEmissions = candidateAudits.map { it.second }
        require(sameSparseFeatureVector(reconstructFeatureVector(stateEmissions), state))
        candidates.indices.forEach { index ->
            require(
                sameSparseFeatureVector(
                    reconstructFeatureVector(candidateEmissions[index]),
                    candidates[index],
                )
            )
        }
        return NeuralBcFeatureEmissionAudit(
            state = state,
            stateEmissions = stateEmissions,
            candidates = candidates,
            candidateEmissions = candidateEmissions,
        )
    }

    private fun encodeState(
        input: NeuralBcFeatureInput,
        context: VisibleSemanticContext,
        audit: MutableSet<String>?,
        emissions: MutableList<NeuralBcFeatureEmission>? = null,
    ): SparseFeatureVector {
        val sink = FeatureSink(stateDimension, audit, emissions)
        val observation = input.observation
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
            zone.cards.forEach { card -> emitCard(sink, "state.card.$role.${zone.zone}", card, context) }
        }
        observation.stack.forEach { stack -> emitStack(sink, "state.stack", stack, context) }
        sink.numeric("state.stack.count", observation.stack.size / 5.0)
        observation.combat?.let { combat ->
            sink.category("state.combat.attacker", context.playerRelation(combat.attackingPlayerId))
            sink.numeric("state.combat.attackers", combat.attackers.size / 8.0)
            sink.numeric("state.combat.blockers", combat.blockers.size / 8.0)
            combat.attackers.forEach { attacker ->
                context.safeObjects[attacker.attackerObjectRef]?.let {
                    emitCard(sink, "state.combat.attackingCard", it, context)
                }
                sink.numeric("state.combat.blockerCountPerAttacker", attacker.blockerObjectRefs.size / 4.0)
            }
            combat.blockers.forEach { blocker ->
                context.safeObjects[blocker.blockerObjectRef]?.let {
                    emitCard(sink, "state.combat.blockingCard", it, context)
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
                emitSafeJson(sink, "state.pending.spec", json, context, JsonFeatureMode.STATE)
            }
        }

        val knowledge = input.knowledge
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

        input.recentEvents.forEachIndexed { offset, event ->
            emitEvent(sink, event, input.recentEvents.size - offset, context)
        }
        sink.numeric("state.history.windowSize", input.recentEvents.size / 64.0)
        return sink.build()
    }

    private fun encodeCandidate(
        choice: NeuralBcFeatureCandidate,
        context: VisibleSemanticContext,
        audit: MutableSet<String>?,
        emissions: MutableList<NeuralBcFeatureEmission>? = null,
    ): SparseFeatureVector {
        val sink = FeatureSink(candidateDimension, audit, emissions)
        sink.category("candidate.kind", choice.kind.name)
        sink.category("candidate.family", choice.operationFamily.name)
        sink.category("candidate.intent", choice.actionIntent.kind.name)
        choice.actionIntent.sourceCardName?.let { sink.category("candidate.source", it) }
        choice.actionIntent.targetRelations.forEach { sink.category("candidate.targetRelation", it.name) }
        emitSafeJson(sink, "candidate.payload", choice.canonicalPayload, context, JsonFeatureMode.CANDIDATE)
        return sink.build()
    }

    private fun emitEvent(
        sink: FeatureSink,
        event: PolicyHistoryEvent,
        recency: Int,
        context: VisibleSemanticContext,
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
            emitSafeJson(sink, "$prefix.detail", json, context, JsonFeatureMode.STATE)
        }
    }

    private fun emitSafeJson(
        sink: FeatureSink,
        path: String,
        value: JsonElement,
        context: VisibleSemanticContext,
        mode: JsonFeatureMode,
    ) {
        when (value) {
            JsonNull -> sink.category(path, "null")
            is JsonArray -> {
                sink.numeric("$path.count", value.size / 8.0)
                value.forEachIndexed { index, child ->
                    val itemPath = if (
                        projection == NeuralBcFeatureProjection.REPAIRED && mode == JsonFeatureMode.CANDIDATE
                    ) {
                        "$path.item.$index"
                    } else {
                        "$path.item"
                    }
                    emitSafeJson(sink, itemPath, child, context, mode)
                }
            }
            is JsonObject -> value.entries.forEach { (rawKey, child) ->
                if (forbiddenJsonField(rawKey)) return@forEach
                val keyReference = context.resolveReference(rawKey, mode)
                if (keyReference != null) {
                    emitReference(sink, "$path.key", keyReference, context)
                    emitSafeJson(sink, "$path.value", child, context, mode)
                } else if (!looksLikeOpaqueReference(rawKey)) {
                    emitSafeJson(sink, "$path.$rawKey", child, context, mode)
                }
            }
            is JsonPrimitive -> {
                if (value.isString) {
                    val content = value.content
                    val reference = context.resolveReference(content, mode)
                    when {
                        reference != null -> emitReference(sink, path, reference, context)
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
        context: VisibleSemanticContext,
    ) {
        when (reference) {
            is VisibleReference.Player -> sink.category("$path.player", context.playerRelation(reference.playerId))
            is VisibleReference.Card -> {
                emitCard(sink, "$path.card", reference.card, context)
                reference.semanticOrdinal?.let { ordinal ->
                    sink.category("$path.card.semanticOrdinal", ordinal.toString())
                }
            }
            is VisibleReference.Stack -> emitStack(sink, "$path.stack", reference.item, context)
            is VisibleReference.AuthorizedChoice -> sink.category("$path.referenceKind", "authorized-choice")
        }
    }

    private fun emitCard(
        sink: FeatureSink,
        prefix: String,
        card: PolicyCardView,
        context: VisibleSemanticContext,
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
        context: VisibleSemanticContext,
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
            normalized == "proposalSeed".lowercase()
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
        value.matches(Regex("[0-9a-f]{64}")) -> "opaque"
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

    private fun decisionFamily(input: NeuralBcFeatureInput): String {
        val families = input.candidates.map { it.operationFamily }.toSet()
        return when {
            families == setOf(SemanticOperationFamily.MULLIGAN) -> "MULLIGAN"
            families == setOf(SemanticOperationFamily.DECLARE_ATTACKERS) -> "DECLARE_ATTACKERS"
            families == setOf(SemanticOperationFamily.DECLARE_BLOCKERS) -> "DECLARE_BLOCKERS"
            families == setOf(SemanticOperationFamily.DECISION_RESPONSE) -> "DECISION_RESPONSE"
            else -> "ORDINARY_ACTION"
        }
    }
}

private enum class JsonFeatureMode { STATE, CANDIDATE }

private sealed interface VisibleReference {
    data class Player(val playerId: String) : VisibleReference
    data class Card(val card: PolicyCardView, val semanticOrdinal: Int? = null) : VisibleReference
    data class Stack(val item: PolicyStackItemView) : VisibleReference
    data object AuthorizedChoice : VisibleReference
}

/** Reconstructs the adapter's raw-ID-free semantic object keys solely to join them to visible data. */
private class VisibleSemanticContext(
    observation: PolicyObservation,
    private val actor: String?,
    candidateSchemaVersion: Int,
    private val projection: NeuralBcFeatureProjection,
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
            if (candidateSchemaVersion >= CANDIDATE_SCHEMA_V4 && (
                    card.isWarped || card.isWarpExiled || card.playableFromExile ||
                        card.hasActivatedAbilityThisTurn
                )
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
        repeat(4) {
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
            if (next == refined) return@repeat
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
        projectionSemanticReference(value)?.let { (base, ordinal) ->
            semanticObjects[base]?.let { return VisibleReference.Card(it, ordinal) }
        }
        semanticStacks[value]?.let { return VisibleReference.Stack(it) }
        if (mode == JsonFeatureMode.STATE) {
            safeObjects[value]?.let { return VisibleReference.Card(it) }
            safeStacks[value]?.let { return VisibleReference.Stack(it) }
        }
        return if (value.startsWith("choice:")) VisibleReference.AuthorizedChoice else null
    }

    private fun projectionSemanticReference(value: String): Pair<String, Int>? {
        if (projection != NeuralBcFeatureProjection.REPAIRED || !value.startsWith("object:")) return null
        val match = SEMANTIC_ORDINAL.matchEntire(value) ?: return null
        return match.groupValues[1] to match.groupValues[2].toInt()
    }

    fun semanticObjectReferences(): Set<String> = semanticObjects.keys

    private fun descriptorDigest(parts: List<String>): String = PolicyJson.sha256(parts.joinToString("\u001f"))

    companion object {
        private val SEMANTIC_ORDINAL = Regex("(.+)#([0-9]+)")
    }
}

internal data class SparseFeatureVector(
    val indices: IntArray,
    val values: DoubleArray,
) {
    init { require(indices.size == values.size) }
}

private class FeatureSink(
    private val dimension: Int,
    private val audit: MutableSet<String>?,
    private val emissions: MutableList<NeuralBcFeatureEmission>? = null,
) {
    private val values = linkedMapOf<Int, Double>()

    fun category(name: String, value: String) = add("$name=$value", 1.0)
    fun flag(name: String, value: Boolean) = category(name, value.toString())
    fun numeric(name: String, value: Double) {
        if (value.isFinite() && value != 0.0) add(name, value.coerceIn(-4.0, 4.0))
    }

    private fun add(feature: String, value: Double) {
        require(!feature.contains(Regex("[0-9a-f]{64}"))) {
            "Opaque digest reached the neural feature boundary: $feature"
        }
        require("signature" !in feature.lowercase() && "digest" !in feature.lowercase())
        audit?.add(feature)
        val index = stableBucket(feature, dimension)
        emissions?.add(NeuralBcFeatureEmission(feature = feature, value = value, bucket = index))
        values[index] = values.getOrDefault(index, 0.0) + value
    }

    fun build(): SparseFeatureVector {
        val sorted = values.entries.sortedBy { it.key }
        return SparseFeatureVector(
            indices = sorted.map { it.key }.toIntArray(),
            values = sorted.map { it.value }.toDoubleArray(),
        )
    }

    private fun stableBucket(value: String, dimension: Int): Int {
        var hash = -3750763034362895579L
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 1099511628211L
        }
        return ((hash xor (hash ushr 32)).toInt() and Int.MAX_VALUE) % dimension
    }
}

private fun reconstructFeatureVector(emissions: List<NeuralBcFeatureEmission>): SparseFeatureVector {
    val values = linkedMapOf<Int, Double>()
    emissions.forEach { emission ->
        values[emission.bucket] = values.getOrDefault(emission.bucket, 0.0) + emission.value
    }
    val sorted = values.entries.sortedBy { it.key }
    return SparseFeatureVector(
        indices = sorted.map { it.key }.toIntArray(),
        values = sorted.map { it.value }.toDoubleArray(),
    )
}

internal data class EncodedBcDecision(
    val gameId: String,
    val decisionIndex: Int,
    val decisionFamily: String,
    val state: SparseFeatureVector,
    val candidates: List<SparseFeatureVector>,
    val candidateFamilies: List<SemanticOperationFamily>,
    val candidateIntents: List<SemanticActionIntentKind>,
    val labelIndex: Int,
) {
    val candidateCount: Int get() = candidates.size
    val labelFamily: SemanticOperationFamily get() = candidateFamilies[labelIndex]
}

@Serializable
internal data class NeuralBcModelConfig(
    val schemaVersion: Int = NEURAL_BC_MODEL_SCHEMA,
    val featureSchema: String = NEURAL_BC_FEATURE_SCHEMA,
    val stateDimension: Int = 1_024,
    val candidateDimension: Int = 512,
    val hiddenDimension: Int = 32,
    val parameterCount: Int = hiddenDimension * (stateDimension + candidateDimension) + hiddenDimension * 3,
)

@Serializable
internal data class NeuralBcModelArtifact(
    val schemaVersion: Int = NEURAL_BC_MODEL_SCHEMA,
    val protocol: String = NEURAL_BC_PROTOCOL,
    val config: NeuralBcModelConfig,
    val trainingSeed: Long,
    val bestEpoch: Int,
    val stateWeights: DoubleArray,
    val stateBias: DoubleArray,
    val candidateWeights: DoubleArray,
    val candidateBias: DoubleArray,
    val globalQuery: DoubleArray,
)

internal interface NeuralBcScoringPolicy {
    fun scores(decision: EncodedBcDecision): DoubleArray

    fun selectIndex(decision: EncodedBcDecision): Int {
        val values = scores(decision)
        return values.indices.maxBy { values[it] }
    }
}

internal class CandidateConditionedNeuralPolicy private constructor(
    val artifact: NeuralBcModelArtifact,
) : NeuralBcScoringPolicy {
    private val config = artifact.config

    override fun scores(decision: EncodedBcDecision): DoubleArray {
        val state = project(
            decision.state,
            artifact.stateWeights,
            artifact.stateBias,
            config.stateDimension,
        )
        val query = DoubleArray(config.hiddenDimension) { state[it] + artifact.globalQuery[it] }
        val scale = 1.0 / sqrt(config.hiddenDimension.toDouble())
        return DoubleArray(decision.candidateCount) { index ->
            val candidate = project(
                decision.candidates[index],
                artifact.candidateWeights,
                artifact.candidateBias,
                config.candidateDimension,
            )
            dot(query, candidate) * scale
        }
    }

    fun select(input: BoundedPolicyInput, encoder: NeuralBehavioralCloningFeatureEncoder): SemanticChoice {
        val decision = encoder.encodeForInference(input)
        return input.candidates[selectIndex(decision)]
    }

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        writeTextAtomically(path, evidenceJson.encodeToString(artifact) + "\n")
    }

    companion object {
        fun initialize(config: NeuralBcModelConfig, seed: Long): CandidateConditionedNeuralPolicy {
            val random = Random(seed)
            fun weights(rows: Int, columns: Int): DoubleArray {
                val scale = sqrt(2.0 / (rows + columns).toDouble())
                return DoubleArray(rows * columns) { gaussian(random) * scale }
            }
            return CandidateConditionedNeuralPolicy(
                NeuralBcModelArtifact(
                    config = config,
                    trainingSeed = seed,
                    bestEpoch = 0,
                    stateWeights = weights(config.hiddenDimension, config.stateDimension),
                    stateBias = DoubleArray(config.hiddenDimension),
                    candidateWeights = weights(config.hiddenDimension, config.candidateDimension),
                    candidateBias = DoubleArray(config.hiddenDimension),
                    globalQuery = DoubleArray(config.hiddenDimension),
                )
            )
        }

        fun fromArtifact(artifact: NeuralBcModelArtifact): CandidateConditionedNeuralPolicy =
            CandidateConditionedNeuralPolicy(artifact)

        fun load(path: Path): CandidateConditionedNeuralPolicy = fromArtifact(
            evidenceJson.decodeFromString(Files.readString(path))
        )

        private fun gaussian(random: Random): Double {
            val first = random.nextDouble().coerceAtLeast(1e-12)
            return sqrt(-2.0 * ln(first)) * cos(2.0 * PI * random.nextDouble())
        }
    }

    private fun project(
        vector: SparseFeatureVector,
        weights: DoubleArray,
        bias: DoubleArray,
        inputDimension: Int,
    ): DoubleArray = DoubleArray(config.hiddenDimension) { hidden ->
        var value = bias[hidden]
        val offset = hidden * inputDimension
        vector.indices.indices.forEach { position ->
            value += weights[offset + vector.indices[position]] * vector.values[position]
        }
        tanh(value)
    }

    private fun dot(left: DoubleArray, right: DoubleArray): Double {
        var result = 0.0
        left.indices.forEach { result += left[it] * right[it] }
        return result
    }
}

@Serializable
internal data class NeuralBcTrainingConfig(
    val schemaVersion: Int = 1,
    val maximumEpochs: Int = 60,
    val learningRate: Double = 0.01,
    /** Multiplies only candidate projection weight/bias parameter updates after Adam normalization. */
    val candidateProjectionUpdateScale: Double = 1.0,
    val adamBeta1: Double = 0.9,
    val adamBeta2: Double = 0.999,
    val adamEpsilon: Double = 1e-8,
    val initializationSeeds: List<Long> = listOf(1729L, 3253L, 6997L),
)

internal data class TrainedBcModel(
    val policy: CandidateConditionedNeuralPolicy,
    val bestEpoch: Int,
    val bestValidationLoss: Double,
    val selectedCheckpointTrainingLoss: Double,
    val maximumTrainingAccuracy: Double,
)

internal class NeuralBcTrainer(
    private val modelConfig: NeuralBcModelConfig,
    private val trainingConfig: NeuralBcTrainingConfig,
) {
    fun train(
        train: List<EncodedBcDecision>,
        validation: List<EncodedBcDecision>,
        seed: Long,
    ): TrainedBcModel {
        require(train.any { it.candidateCount >= PRIMARY_MIN_CANDIDATES })
        require(validation.any { it.candidateCount >= PRIMARY_MIN_CANDIDATES })
        var policy = CandidateConditionedNeuralPolicy.initialize(modelConfig, seed)
        val adam = SparseAdam(policy.artifact, trainingConfig)
        var bestArtifact = copyNeuralBcModelArtifact(policy.artifact)
        var bestEpoch = 0
        var bestValidationLoss = Double.POSITIVE_INFINITY
        var maximumTrainingAccuracy = 0.0
        val effectiveTrain = train.filter { it.candidateCount >= PRIMARY_MIN_CANDIDATES }
        for (epoch in 1..trainingConfig.maximumEpochs) {
            effectiveTrain.shuffled(Random(seed xor epoch.toLong())).forEach { decision ->
                adam.step(decision)
            }
            maximumTrainingAccuracy = maxOf(maximumTrainingAccuracy, accuracy(policy, effectiveTrain))
            val validationLoss = meanLoss(policy, validation)
            if (validationLoss < bestValidationLoss - 1e-7) {
                bestValidationLoss = validationLoss
                bestEpoch = epoch
                bestArtifact = copyNeuralBcModelArtifact(policy.artifact).copy(bestEpoch = epoch)
            }
        }
        policy = CandidateConditionedNeuralPolicy.fromArtifact(bestArtifact)
        return TrainedBcModel(
            policy = policy,
            bestEpoch = bestEpoch,
            bestValidationLoss = bestValidationLoss,
            selectedCheckpointTrainingLoss = meanLoss(policy, train),
            maximumTrainingAccuracy = maximumTrainingAccuracy,
        )
    }

    private fun meanLoss(
        policy: CandidateConditionedNeuralPolicy,
        examples: List<EncodedBcDecision>,
    ): Double {
        val selected = examples.filter { it.candidateCount >= PRIMARY_MIN_CANDIDATES }
        return selected.sumOf { decision ->
            neuralBcCrossEntropy(policy.scores(decision), decision.labelIndex)
        } / selected.size
    }

    private fun accuracy(
        policy: CandidateConditionedNeuralPolicy,
        examples: List<EncodedBcDecision>,
    ): Double = examples.count { policy.selectIndex(it) == it.labelIndex }.toDouble() / examples.size

}

/**
 * Exact calls made to [SparseAdam.update]. Candidate feature occurrences are deliberately not the
 * unit here: candidate gradients are accumulated by weight index across every current candidate,
 * then each touched candidate weight receives at most one update call per decision. The optimizer's
 * bias-correction clock is global ([decisionSteps]), not local to a sparse parameter.
 */
internal data class SparseAdamUpdateExposure(
    val decisionSteps: Int,
    val stateWeightUpdateCounts: LongArray,
    val stateBiasUpdateCounts: LongArray,
    val candidateWeightUpdateCounts: LongArray,
    val candidateBiasUpdateCounts: LongArray,
    val globalQueryUpdateCounts: LongArray,
)

/**
 * Complete continuation state for the ordinary sparse Adam implementation.
 *
 * A model checkpoint alone is not a training fork: the global bias-correction clock and sparse
 * moments affect the next parameter update. Descriptive update counts are retained for an exact
 * continuation audit. Arrays are copied both when captured and restored so branches cannot alias.
 */
@Serializable
internal data class SparseAdamState(
    val schemaVersion: Int = 1,
    val decisionSteps: Int,
    val stateFirstMoment: DoubleArray,
    val stateSecondMoment: DoubleArray,
    val stateBiasFirstMoment: DoubleArray,
    val stateBiasSecondMoment: DoubleArray,
    val candidateFirstMoment: DoubleArray,
    val candidateSecondMoment: DoubleArray,
    val candidateBiasFirstMoment: DoubleArray,
    val candidateBiasSecondMoment: DoubleArray,
    val queryFirstMoment: DoubleArray,
    val querySecondMoment: DoubleArray,
    val stateWeightUpdateCounts: LongArray,
    val stateBiasUpdateCounts: LongArray,
    val candidateWeightUpdateCounts: LongArray,
    val candidateBiasUpdateCounts: LongArray,
    val globalQueryUpdateCounts: LongArray,
)

internal class SparseAdam(
    private val artifact: NeuralBcModelArtifact,
    private val config: NeuralBcTrainingConfig,
    initialState: SparseAdamState? = null,
) {
    private val stateM = initialState?.stateFirstMoment?.copyOf() ?: DoubleArray(artifact.stateWeights.size)
    private val stateV = initialState?.stateSecondMoment?.copyOf() ?: DoubleArray(artifact.stateWeights.size)
    private val stateBiasM = initialState?.stateBiasFirstMoment?.copyOf() ?: DoubleArray(artifact.stateBias.size)
    private val stateBiasV = initialState?.stateBiasSecondMoment?.copyOf() ?: DoubleArray(artifact.stateBias.size)
    private val candidateM = initialState?.candidateFirstMoment?.copyOf() ?: DoubleArray(artifact.candidateWeights.size)
    private val candidateV = initialState?.candidateSecondMoment?.copyOf() ?: DoubleArray(artifact.candidateWeights.size)
    private val candidateBiasM = initialState?.candidateBiasFirstMoment?.copyOf() ?: DoubleArray(artifact.candidateBias.size)
    private val candidateBiasV = initialState?.candidateBiasSecondMoment?.copyOf() ?: DoubleArray(artifact.candidateBias.size)
    private val queryM = initialState?.queryFirstMoment?.copyOf() ?: DoubleArray(artifact.globalQuery.size)
    private val queryV = initialState?.querySecondMoment?.copyOf() ?: DoubleArray(artifact.globalQuery.size)
    private val stateWeightUpdateCounts = initialState?.stateWeightUpdateCounts?.copyOf()
        ?: LongArray(artifact.stateWeights.size)
    private val stateBiasUpdateCounts = initialState?.stateBiasUpdateCounts?.copyOf()
        ?: LongArray(artifact.stateBias.size)
    private val candidateWeightUpdateCounts = initialState?.candidateWeightUpdateCounts?.copyOf()
        ?: LongArray(artifact.candidateWeights.size)
    private val candidateBiasUpdateCounts = initialState?.candidateBiasUpdateCounts?.copyOf()
        ?: LongArray(artifact.candidateBias.size)
    private val globalQueryUpdateCounts = initialState?.globalQueryUpdateCounts?.copyOf()
        ?: LongArray(artifact.globalQuery.size)
    private var time = initialState?.decisionSteps ?: 0

    init {
        require(config.candidateProjectionUpdateScale.isFinite() && config.candidateProjectionUpdateScale > 0.0)
        initialState?.let { state ->
            require(state.schemaVersion == 1)
            require(state.decisionSteps >= 0)
            require(state.stateFirstMoment.size == artifact.stateWeights.size)
            require(state.stateSecondMoment.size == artifact.stateWeights.size)
            require(state.stateBiasFirstMoment.size == artifact.stateBias.size)
            require(state.stateBiasSecondMoment.size == artifact.stateBias.size)
            require(state.candidateFirstMoment.size == artifact.candidateWeights.size)
            require(state.candidateSecondMoment.size == artifact.candidateWeights.size)
            require(state.candidateBiasFirstMoment.size == artifact.candidateBias.size)
            require(state.candidateBiasSecondMoment.size == artifact.candidateBias.size)
            require(state.queryFirstMoment.size == artifact.globalQuery.size)
            require(state.querySecondMoment.size == artifact.globalQuery.size)
            require(state.stateWeightUpdateCounts.size == artifact.stateWeights.size)
            require(state.stateBiasUpdateCounts.size == artifact.stateBias.size)
            require(state.candidateWeightUpdateCounts.size == artifact.candidateWeights.size)
            require(state.candidateBiasUpdateCounts.size == artifact.candidateBias.size)
            require(state.globalQueryUpdateCounts.size == artifact.globalQuery.size)
        }
    }

    fun step(decision: EncodedBcDecision) {
        time++
        val hidden = artifact.config.hiddenDimension
        val state = forward(
            decision.state,
            artifact.stateWeights,
            artifact.stateBias,
            artifact.config.stateDimension,
            hidden,
        )
        val query = DoubleArray(hidden) { state.values[it] + artifact.globalQuery[it] }
        val candidates = decision.candidates.map { vector ->
            forward(
                vector,
                artifact.candidateWeights,
                artifact.candidateBias,
                artifact.config.candidateDimension,
                hidden,
            )
        }
        val scale = 1.0 / sqrt(hidden.toDouble())
        val scores = DoubleArray(candidates.size) { index -> dot(query, candidates[index].values) * scale }
        val probabilities = softmax(scores)
        val stateGradient = DoubleArray(hidden)
        val candidateBiasGradient = DoubleArray(hidden)
        val candidateWeightGradients = linkedMapOf<Int, Double>()
        candidates.forEachIndexed { candidateIndex, candidate ->
            val scoreGradient = probabilities[candidateIndex] - if (candidateIndex == decision.labelIndex) 1.0 else 0.0
            (0 until hidden).forEach { h ->
                stateGradient[h] += scoreGradient * candidate.values[h] * scale
                val preGradient = scoreGradient * query[h] * scale * (1.0 - candidate.values[h] * candidate.values[h])
                candidateBiasGradient[h] += preGradient
                val offset = h * artifact.config.candidateDimension
                decision.candidates[candidateIndex].indices.indices.forEach { position ->
                    val weightIndex = offset + decision.candidates[candidateIndex].indices[position]
                    val gradient = preGradient * decision.candidates[candidateIndex].values[position]
                    candidateWeightGradients[weightIndex] =
                        candidateWeightGradients.getOrDefault(weightIndex, 0.0) + gradient
                }
            }
        }
        val stateBiasGradient = DoubleArray(hidden) { h ->
            stateGradient[h] * (1.0 - state.values[h] * state.values[h])
        }
        (0 until hidden).forEach { h ->
            val offset = h * artifact.config.stateDimension
            decision.state.indices.indices.forEach { position ->
                val weightIndex = offset + decision.state.indices[position]
                update(
                    artifact.stateWeights,
                    stateM,
                    stateV,
                    stateWeightUpdateCounts,
                    weightIndex,
                    stateBiasGradient[h] * decision.state.values[position],
                )
            }
            update(
                artifact.stateBias,
                stateBiasM,
                stateBiasV,
                stateBiasUpdateCounts,
                h,
                stateBiasGradient[h],
            )
            update(
                artifact.candidateBias,
                candidateBiasM,
                candidateBiasV,
                candidateBiasUpdateCounts,
                h,
                candidateBiasGradient[h],
                config.candidateProjectionUpdateScale,
            )
            update(
                artifact.globalQuery,
                queryM,
                queryV,
                globalQueryUpdateCounts,
                h,
                stateGradient[h],
            )
        }
        candidateWeightGradients.forEach { (index, gradient) ->
            update(
                artifact.candidateWeights,
                candidateM,
                candidateV,
                candidateWeightUpdateCounts,
                index,
                gradient,
                config.candidateProjectionUpdateScale,
            )
        }
    }

    fun updateExposureSnapshot(): SparseAdamUpdateExposure = SparseAdamUpdateExposure(
        decisionSteps = time,
        stateWeightUpdateCounts = stateWeightUpdateCounts.copyOf(),
        stateBiasUpdateCounts = stateBiasUpdateCounts.copyOf(),
        candidateWeightUpdateCounts = candidateWeightUpdateCounts.copyOf(),
        candidateBiasUpdateCounts = candidateBiasUpdateCounts.copyOf(),
        globalQueryUpdateCounts = globalQueryUpdateCounts.copyOf(),
    )

    fun snapshotState(): SparseAdamState = SparseAdamState(
        decisionSteps = time,
        stateFirstMoment = stateM.copyOf(),
        stateSecondMoment = stateV.copyOf(),
        stateBiasFirstMoment = stateBiasM.copyOf(),
        stateBiasSecondMoment = stateBiasV.copyOf(),
        candidateFirstMoment = candidateM.copyOf(),
        candidateSecondMoment = candidateV.copyOf(),
        candidateBiasFirstMoment = candidateBiasM.copyOf(),
        candidateBiasSecondMoment = candidateBiasV.copyOf(),
        queryFirstMoment = queryM.copyOf(),
        querySecondMoment = queryV.copyOf(),
        stateWeightUpdateCounts = stateWeightUpdateCounts.copyOf(),
        stateBiasUpdateCounts = stateBiasUpdateCounts.copyOf(),
        candidateWeightUpdateCounts = candidateWeightUpdateCounts.copyOf(),
        candidateBiasUpdateCounts = candidateBiasUpdateCounts.copyOf(),
        globalQueryUpdateCounts = globalQueryUpdateCounts.copyOf(),
    )

    private fun update(
        parameters: DoubleArray,
        firstMoment: DoubleArray,
        secondMoment: DoubleArray,
        updateCounts: LongArray,
        index: Int,
        gradient: Double,
        updateScale: Double = 1.0,
    ) {
        updateCounts[index]++
        val beta1 = config.adamBeta1
        val beta2 = config.adamBeta2
        firstMoment[index] = beta1 * firstMoment[index] + (1.0 - beta1) * gradient
        secondMoment[index] = beta2 * secondMoment[index] + (1.0 - beta2) * gradient * gradient
        val correctedFirst = firstMoment[index] / (1.0 - beta1.pow(time))
        val correctedSecond = secondMoment[index] / (1.0 - beta2.pow(time))
        parameters[index] -= config.learningRate * updateScale * correctedFirst /
            (sqrt(correctedSecond) + config.adamEpsilon)
    }

    private data class Projection(val values: DoubleArray)

    private fun forward(
        vector: SparseFeatureVector,
        weights: DoubleArray,
        bias: DoubleArray,
        inputDimension: Int,
        hidden: Int,
    ): Projection = Projection(DoubleArray(hidden) { h ->
        var total = bias[h]
        val offset = h * inputDimension
        vector.indices.indices.forEach { position ->
            total += weights[offset + vector.indices[position]] * vector.values[position]
        }
        tanh(total)
    })

    private fun Double.pow(exponent: Int): Double = pow(exponent.toDouble())
    private fun dot(left: DoubleArray, right: DoubleArray): Double =
        left.indices.sumOf { left[it] * right[it] }
}

private fun softmax(scores: DoubleArray): DoubleArray {
    val maximum = scores.maxOrNull() ?: error("Empty candidate set")
    val exponentials = DoubleArray(scores.size) { exp(scores[it] - maximum) }
    val total = exponentials.sum()
    return DoubleArray(scores.size) { exponentials[it] / total }
}

internal fun neuralBcCrossEntropy(scores: DoubleArray, label: Int): Double {
    val probabilities = softmax(scores)
    return -ln(probabilities[label].coerceAtLeast(1e-15))
}

internal fun copyNeuralBcModelArtifact(artifact: NeuralBcModelArtifact): NeuralBcModelArtifact = artifact.copy(
    stateWeights = artifact.stateWeights.copyOf(),
    stateBias = artifact.stateBias.copyOf(),
    candidateWeights = artifact.candidateWeights.copyOf(),
    candidateBias = artifact.candidateBias.copyOf(),
    globalQuery = artifact.globalQuery.copyOf(),
)

@Serializable
internal data class NeuralBcGameSplit(
    val schemaVersion: Int = 1,
    val protocol: String = SPLIT_PROTOCOL,
    val datasetIdentity: String,
    val splitIdentity: String,
    val trainGames: List<String>,
    val validationGames: List<String>,
    val testGames: List<String>,
) {
    init {
        require(trainGames.isNotEmpty() && validationGames.isNotEmpty() && testGames.isNotEmpty())
        val all = trainGames + validationGames + testGames
        require(all.distinct().size == all.size) { "A complete game appears in more than one split" }
    }
}

internal fun deterministicBcGameSplit(
    datasetIdentity: String,
    gameIds: Collection<String>,
): NeuralBcGameSplit {
    require(gameIds.size >= 10) { "At least ten complete games are required for a useful train/validation/test split" }
    val ordered = gameIds.distinct().sortedBy { PolicyJson.sha256("$SPLIT_PROTOCOL\u0000$datasetIdentity\u0000$it") }
    val validationCount = maxOf(2, (ordered.size * 0.15).toInt())
    val testCount = maxOf(2, (ordered.size * 0.15).toInt())
    val trainCount = ordered.size - validationCount - testCount
    require(trainCount > validationCount && trainCount > testCount)
    val train = ordered.take(trainCount).sorted()
    val validation = ordered.drop(trainCount).take(validationCount).sorted()
    val test = ordered.takeLast(testCount).sorted()
    val material = listOf(datasetIdentity, train.joinToString(","), validation.joinToString(","), test.joinToString(","))
        .joinToString("\u0000")
    return NeuralBcGameSplit(
        datasetIdentity = datasetIdentity,
        splitIdentity = "neural-bc-split-v1-sha256:${PolicyJson.sha256(material)}",
        trainGames = train,
        validationGames = validation,
        testGames = test,
    )
}

@Serializable
internal data class BcAccuracy(
    val decisions: Int,
    val expectedCorrect: Double,
    val accuracy: Double?,
)

@Serializable
internal data class BcCohortMetrics(
    val cohort: String,
    val decisions: Int,
    val uniform: BcAccuracy,
    val stateIgnorantEmpirical: BcAccuracy,
    val neuralAccuracyBySeed: Map<Long, Double>,
    val neuralMeanAccuracy: Double?,
    val neuralMinimumAccuracy: Double?,
    val neuralMaximumAccuracy: Double?,
)

@Serializable
internal data class NeuralBcSeedResult(
    val seed: Long,
    val bestEpoch: Int,
    val bestValidationLoss: Double,
    val selectedCheckpointTrainingLoss: Double,
    val maximumTrainingAccuracy: Double,
    val selectedCheckpointTrainingAccuracy: Double,
    val validationAccuracy: Double,
    val testAccuracy: Double,
    val modelPath: String,
    val modelSha256: String,
)

@Serializable
internal data class NeuralBcTechnicalExecution(
    val schemaVersion: Int = 1,
    val gameId: String,
    val attemptedDecisions: Int,
    val acceptedDecisions: Int,
    val candidateCounts: List<Int>,
    val decisionFamilies: List<String>,
    val selectedFamilies: List<SemanticOperationFamily>,
    val allSelectionsWereCurrentCandidates: Boolean,
    val allSemanticStepsAccepted: Boolean,
    val usedOnlyBoundedInputsForScoring: Boolean,
    val passed: Boolean,
)

@Serializable
internal data class NeuralBcRepresentationDiagnostics(
    val trainingDecisions: Int,
    val distinctEncodedDecisionKeys: Int,
    val repeatedEncodedDecisionGroups: Int,
    val repeatedGroupsWithConflictingLabels: Int,
    val decisionsWithCandidateFeatureCollisions: Int,
    val labelsSharingFeaturesWithAnotherCurrentCandidate: Int,
    val maximumTrainingAccuracyPermittedByEncodedFeatures: Double,
)

@Serializable
internal data class NeuralBcExperimentReport(
    val schemaVersion: Int = 1,
    val protocol: String = NEURAL_BC_PROTOCOL,
    val generatedAtUtc: String,
    val implementationSourceProvenance: PolicySourceProvenance,
    val sourceProvenance: PolicySourceProvenance,
    val argentumRevision: String,
    val deckId: String,
    val deckManifestHash: String,
    val cardPoolHash: String,
    val profileId: String,
    val profileHash: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val corpusAttemptManifestPath: String,
    val corpusManifestPath: String,
    val corpusDatasetIdentity: String,
    val requestedGames: Int,
    val admittedGames: Int,
    val admittedDecisions: Int,
    val excludedGames: Int,
    val admissionFailures: List<String>,
    val singletonDecisions: Int,
    val nontrivialDecisions: Int,
    val split: NeuralBcGameSplit,
    val decisionsBySplit: Map<String, Int>,
    val nontrivialDecisionsBySplit: Map<String, Int>,
    val featureSchema: String,
    val forbiddenFeatureClasses: List<String>,
    val omittedBoundedBeliefProbabilities: Boolean,
    val modelConfig: NeuralBcModelConfig,
    val trainingConfig: NeuralBcTrainingConfig,
    val representationDiagnostics: NeuralBcRepresentationDiagnostics,
    val seedResults: List<NeuralBcSeedResult>,
    val primaryTest: BcCohortMetrics,
    val allHeldOutCoverage: BcCohortMetrics,
    val byDecisionFamily: List<BcCohortMetrics>,
    val byCandidateCountRange: List<BcCohortMetrics>,
    val byActionFrequency: List<BcCohortMetrics>,
    val technicalExecution: NeuralBcTechnicalExecution,
    val persistenceRoundTripPassed: Boolean,
    val rootEvidenceSidecarPath: String,
    val rootEvidenceSidecarSha256: String,
    val searchTeacherRepeatabilityContext: String,
    val conclusion: String,
    val limitations: List<String>,
    val passed: Boolean,
)

internal class EmpiricalIntentBaseline(train: List<EncodedBcDecision>) {
    private val counts = train.groupBy(EncodedBcDecision::decisionFamily).mapValues { (_, decisions) ->
        decisions.groupingBy { it.candidateIntents[it.labelIndex] }.eachCount()
    }

    fun expectedCorrect(decision: EncodedBcDecision): Double {
        val familyCounts = counts[decision.decisionFamily].orEmpty()
        val scores = decision.candidateIntents.map { familyCounts[it] ?: 0 }
        val maximum = scores.maxOrNull() ?: return 0.0
        val tied = scores.indices.filter { scores[it] == maximum }
        return if (decision.labelIndex in tied) 1.0 / tied.size else 0.0
    }
}

internal class NeuralBehavioralCloningExperiment(
    private val root: Path,
    private val registry: CardRegistry,
    private val deck: DeckManifest,
    private val profile: FrozenSearchProfile,
    private val baseSeed: Long,
    private val outputDirectory: Path,
    private val trainingConfig: NeuralBcTrainingConfig = NeuralBcTrainingConfig(),
) {
    fun run(
        gameCount: Int,
        workerThreads: Int,
        suppliedCorpusManifest: Path? = null,
        progress: (String) -> Unit = {},
    ): NeuralBcExperimentReport {
        require(gameCount >= 10)
        Files.createDirectories(outputDirectory)
        val implementationProvenance = RunProvenance.capture(root).also { it.requireReady() }
        val implementationSourceProvenance = requireNotNull(implementationProvenance.sourceProvenance)
        val currentProfileHash = sha256(evidenceJson.encodeToString(profile))
        val suppliedOrAttemptManifestPath = suppliedCorpusManifest ?: outputDirectory
            .resolve("corpus-attempt-manifest.json").also { target ->
            progress("Generating $gameCount complete Search Teacher corpus games")
            val generated = SearchTeacherCorpus(root, registry, deck, profile, baseSeed)
                .generate(gameCount, workerThreads)
            writePublicJsonAtomically(target, generated)
            require(generated.passed) {
                "Strict corpus generation failed; no labels are trainable. See the quarantine attempt report."
            }
        }
        val attemptedManifest = evidenceJson.decodeFromString<CorpusManifest>(
            Files.readString(suppliedOrAttemptManifestPath)
        )
        val teacherProfile = if (suppliedCorpusManifest == null) profile else profile.copy(
            outerCommit = attemptedManifest.outerCommit,
            argentumCommit = attemptedManifest.argentumCommit,
        )
        val profileHash = sha256(evidenceJson.encodeToString(teacherProfile))
        require(profileHash == attemptedManifest.profileHash) {
            "Supplied corpus profile differs from the current experiment except for its bound source revisions"
        }
        if (suppliedCorpusManifest == null) require(profileHash == currentProfileHash)
        val attemptManifestPath = outputDirectory.resolve("corpus-attempt-manifest.json")
        writePublicJsonAtomically(attemptManifestPath, attemptedManifest)
        val scope = BehavioralCloningAdmissionScope.frozenMonoRed(deck, teacherProfile)
        val initialAdmission = BehavioralCloningAdmission(root, scope).extract(attemptManifestPath)
        val admissionFailures = initialAdmission.failures
        val admittedGameIds = if (initialAdmission.passed) {
            attemptedManifest.entries.map(CorpusEntry::gameId).toSet()
        } else {
            strictWholeGameAdmissionSelection(
                files = initialAdmission.validation.files,
                failures = initialAdmission.failures,
                minimumGames = 10,
            ).also { selected ->
                progress("Strictly excluding ${attemptedManifest.entries.size - selected.size} whole game(s): " +
                    initialAdmission.failures.joinToString("; "))
            }
        }
        val manifest = subsetCorpusManifest(attemptedManifest, admittedGameIds)
        val manifestPath = outputDirectory.resolve("corpus-manifest.json")
        writePublicJsonAtomically(manifestPath, manifest)
        val admission = BehavioralCloningAdmission(root, scope).extract(manifestPath)
        require(admission.passed) { "Behavioral-cloning admission failed: ${admission.failures}" }
        require(admission.examples.map { it.gameId }.distinct().size >= 10)
        require(admission.examples.all { example -> example.teacherAction in example.policyInput.candidates })
        progress("Admitted ${admission.examples.size} decisions from ${manifest.entries.size} complete games")

        val split = deterministicBcGameSplit(manifest.datasetIdentity, admission.examples.map { it.gameId }.toSet())
        writeJsonAtomically(outputDirectory.resolve("split.json"), split)
        val encoder = NeuralBehavioralCloningFeatureEncoder()
        val encoded = admission.examples.map(encoder::encode)
        val train = encoded.filter { it.gameId in split.trainGames }
        val validation = encoded.filter { it.gameId in split.validationGames }
        val test = encoded.filter { it.gameId in split.testGames }
        require((train + validation + test).size == encoded.size)
        require(listOf(train, validation, test).all { population ->
            population.any { it.candidateCount >= PRIMARY_MIN_CANDIDATES }
        })
        val empirical = EmpiricalIntentBaseline(train)
        val modelConfig = NeuralBcModelConfig(
            stateDimension = encoder.stateDimension,
            candidateDimension = encoder.candidateDimension,
        )
        val models = linkedMapOf<Long, CandidateConditionedNeuralPolicy>()
        val seedResults = trainingConfig.initializationSeeds.map { seed ->
            progress("Training neural BC seed $seed")
            val trained = NeuralBcTrainer(modelConfig, trainingConfig).train(train, validation, seed)
            val modelPath = outputDirectory.resolve("model-seed-$seed.json")
            trained.policy.save(modelPath)
            models[seed] = trained.policy
            NeuralBcSeedResult(
                seed = seed,
                bestEpoch = trained.bestEpoch,
                bestValidationLoss = trained.bestValidationLoss,
                selectedCheckpointTrainingLoss = trained.selectedCheckpointTrainingLoss,
                maximumTrainingAccuracy = trained.maximumTrainingAccuracy,
                selectedCheckpointTrainingAccuracy = neuralAccuracy(trained.policy, train.filter(::isNontrivial)),
                validationAccuracy = neuralAccuracy(trained.policy, validation.filter(::isNontrivial)),
                testAccuracy = neuralAccuracy(trained.policy, test.filter(::isNontrivial)),
                modelPath = root.relativize(modelPath).toString(),
                modelSha256 = sha256File(modelPath),
            )
        }

        val firstSeed = trainingConfig.initializationSeeds.first()
        val firstModelPath = outputDirectory.resolve("model-seed-$firstSeed.json")
        val reloaded = CandidateConditionedNeuralPolicy.load(firstModelPath)
        val roundTripProbe = test.first(::isNontrivial)
        val roundTripPassed = models.getValue(firstSeed).scores(roundTripProbe)
            .contentEquals(reloaded.scores(roundTripProbe))
        require(roundTripPassed)

        val sidecarPath = outputDirectory.resolve("root-evidence-sidecar.jsonl.gz")
        writeRootEvidenceSidecar(root, manifest, admission.examples, sidecarPath)
        val technical = executeTechnicalSmoke(reloaded, encoder)
        require(technical.passed) { "Neural semantic execution smoke failed: $technical" }

        val primary = test.filter(::isNontrivial)
        val commonFamilies = train.groupingBy(EncodedBcDecision::labelFamily).eachCount().filterValues { count ->
            count >= maxOf(20, (train.size * 0.05).toInt())
        }.keys
        val byActionFrequency = listOf(
            cohort("common-action-families", primary.filter { it.labelFamily in commonFamilies }, empirical, models),
            cohort("less-frequent-action-families", primary.filter { it.labelFamily !in commonFamilies }, empirical, models),
        )
        val byCandidateCount = listOf("2", "3-4", "5+").map { range ->
            cohort(range, primary.filter { candidateRange(it.candidateCount) == range }, empirical, models)
        }
        val primaryMetrics = cohort("test-nontrivial-primary", primary, empirical, models)
        val meanNeural = requireNotNull(primaryMetrics.neuralMeanAccuracy)
        val baseline = maxOf(
            requireNotNull(primaryMetrics.uniform.accuracy),
            requireNotNull(primaryMetrics.stateIgnorantEmpirical.accuracy),
        )
        val meaningfulFamilyMetrics = primary.groupBy(EncodedBcDecision::decisionFamily)
            .filterValues { it.size >= 10 }
            .values
            .map { decisions -> cohort("family", decisions, empirical, models) }
        val familyCoverage = meaningfulFamilyMetrics.size >= 2 && meaningfulFamilyMetrics.all(::clearsBothBaselines)
        val materialCandidateMetrics = byCandidateCount.filter { it.decisions >= 10 }
        val candidateComplexityCoverage = materialCandidateMetrics.size >= 2 &&
            materialCandidateMetrics.all(::doesNotTrailStrongestBaseline)
        val materialFrequencyMetrics = byActionFrequency.filter { it.decisions >= 10 }
        val actionFrequencyCoverage = materialFrequencyMetrics.size == 2 &&
            materialFrequencyMetrics.all(::doesNotTrailStrongestBaseline)
        val stable = seedResults.maxOf { it.testAccuracy } - seedResults.minOf { it.testAccuracy } <= 0.08
        val passed = meanNeural > baseline + 0.02 && familyCoverage &&
            candidateComplexityCoverage && actionFrequencyCoverage && stable && technical.passed
        val conclusion = when {
            passed -> "ADMIT_CURRENT_NEURAL_POLICY_INTERFACE_FOR_NEXT_RESEARCH_STAGE"
            meanNeural <= baseline + 0.02 -> "DO_NOT_ADMIT_HELD_OUT_IMITATION_DID_NOT_CLEAR_BASELINES"
            meaningfulFamilyMetrics.size < 2 -> "DO_NOT_ADMIT_DECISION_FAMILY_EVIDENCE_IS_INSUFFICIENT"
            !familyCoverage -> "DO_NOT_ADMIT_DECISION_FAMILY_GENERALIZATION_IS_UNEVEN"
            !candidateComplexityCoverage -> "DO_NOT_ADMIT_CANDIDATE_COMPLEXITY_GENERALIZATION_IS_UNEVEN"
            !actionFrequencyCoverage -> "DO_NOT_ADMIT_RARE_ACTION_GENERALIZATION_IS_UNEVEN"
            !stable -> "DO_NOT_ADMIT_INITIALIZATION_VARIATION_IS_MATERIAL"
            else -> "DO_NOT_ADMIT_TECHNICAL_EXECUTION_FAILED"
        }
        return NeuralBcExperimentReport(
            generatedAtUtc = Instant.now().toString(),
            implementationSourceProvenance = implementationSourceProvenance,
            sourceProvenance = manifest.sourceProvenance,
            argentumRevision = manifest.argentumCommit,
            deckId = deck.id,
            deckManifestHash = deck.deckHash(),
            cardPoolHash = deck.cardPoolHash(),
            profileId = teacherProfile.id,
            profileHash = profileHash,
            actionSpaceProfile = teacherProfile.actionSpaceProfile,
            corpusAttemptManifestPath = root.relativize(attemptManifestPath).toString(),
            corpusManifestPath = root.relativize(manifestPath).toString(),
            corpusDatasetIdentity = manifest.datasetIdentity,
            requestedGames = attemptedManifest.requestedGames,
            admittedGames = manifest.entries.size,
            admittedDecisions = encoded.size,
            excludedGames = attemptedManifest.entries.size - manifest.entries.size,
            admissionFailures = admissionFailures,
            singletonDecisions = encoded.count { !isNontrivial(it) },
            nontrivialDecisions = encoded.count(::isNontrivial),
            split = split,
            decisionsBySplit = mapOf("train" to train.size, "validation" to validation.size, "test" to test.size),
            nontrivialDecisionsBySplit = mapOf(
                "train" to train.count(::isNontrivial),
                "validation" to validation.count(::isNontrivial),
                "test" to test.count(::isNontrivial),
            ),
            featureSchema = NEURAL_BC_FEATURE_SCHEMA,
            forbiddenFeatureClasses = listOf(
                "semantic signatures and candidate display text",
                "routing/object/knowledge references and integrity digests",
                "source/evidence provenance and seeds",
                "search visits, values, ranks, root values, diagnostics, particles, and worlds",
                "terminal returns and referee state",
            ),
            omittedBoundedBeliefProbabilities = true,
            modelConfig = modelConfig,
            trainingConfig = trainingConfig,
            representationDiagnostics = neuralBcRepresentationDiagnostics(train),
            seedResults = seedResults,
            primaryTest = primaryMetrics,
            allHeldOutCoverage = cohort("test-all-technical-coverage", test, empirical, models),
            byDecisionFamily = primary.groupBy(EncodedBcDecision::decisionFamily).toSortedMap().map { (name, rows) ->
                cohort(name, rows, empirical, models)
            },
            byCandidateCountRange = byCandidateCount,
            byActionFrequency = byActionFrequency,
            technicalExecution = technical,
            persistenceRoundTripPassed = roundTripPassed,
            rootEvidenceSidecarPath = root.relativize(sidecarPath).toString(),
            rootEvidenceSidecarSha256 = sha256File(sidecarPath),
            searchTeacherRepeatabilityContext =
                "Prior selected-panel evidence: 75.1% pairwise final-action agreement at 64 simulations and " +
                    "14/32 unanimous roots; this is context, not a ceiling or normalization constant.",
            conclusion = conclusion,
            limitations = listOf(
                "The result measures imitation of one realized, materially imperfect Search Teacher label per root, not strategic correctness.",
                "The population is a small fixed-deck Mono-Red mirror under mono-red-fast-mana-pruned-v1.",
                "The feature projection is deliberately compact and does not claim a general Magic representation.",
                "Some semantically distinct current candidates can share this compact feature projection; the report quantifies the resulting fit ceiling.",
                "The technical execution smoke is routing/information-boundary evidence, not playing-strength evidence.",
                "No search statistic, value target, outcome target, self-play, DAgger, or neural-guided search was evaluated.",
            ),
            passed = passed,
        )
    }

    private fun cohort(
        name: String,
        decisions: List<EncodedBcDecision>,
        empirical: EmpiricalIntentBaseline,
        models: Map<Long, CandidateConditionedNeuralPolicy>,
    ): BcCohortMetrics {
        val uniformCorrect = decisions.sumOf { 1.0 / it.candidateCount }
        val empiricalCorrect = decisions.sumOf(empirical::expectedCorrect)
        val neural = models.mapValues { (_, model) -> neuralAccuracy(model, decisions) }
        val reportable = decisions.size >= 10
        return BcCohortMetrics(
            cohort = name,
            decisions = decisions.size,
            uniform = accuracy(decisions.size, uniformCorrect),
            stateIgnorantEmpirical = accuracy(decisions.size, empiricalCorrect),
            neuralAccuracyBySeed = if (reportable) neural else emptyMap(),
            neuralMeanAccuracy = neural.values.takeIf { reportable }?.average(),
            neuralMinimumAccuracy = neural.values.takeIf { reportable }?.minOrNull(),
            neuralMaximumAccuracy = neural.values.takeIf { reportable }?.maxOrNull(),
        )
    }

    private fun accuracy(decisions: Int, correct: Double): BcAccuracy = BcAccuracy(
        decisions = decisions,
        expectedCorrect = correct,
        // Tiny cohorts retain their exact count/correct mass without publishing a percentage that
        // would invite over-interpretation.
        accuracy = if (decisions < 10) null else correct / decisions,
    )

    private fun neuralAccuracy(
        policy: CandidateConditionedNeuralPolicy,
        decisions: List<EncodedBcDecision>,
    ): Double = if (decisions.isEmpty()) Double.NaN else {
        decisions.count { policy.selectIndex(it) == it.labelIndex }.toDouble() / decisions.size
    }

    private fun clearsBothBaselines(metric: BcCohortMetrics): Boolean {
        val neural = requireNotNull(metric.neuralMeanAccuracy)
        val strongestBaseline = maxOf(
            requireNotNull(metric.uniform.accuracy),
            requireNotNull(metric.stateIgnorantEmpirical.accuracy),
        )
        return neural > strongestBaseline + 0.02
    }

    private fun doesNotTrailStrongestBaseline(metric: BcCohortMetrics): Boolean {
        val neural = requireNotNull(metric.neuralMeanAccuracy)
        val strongestBaseline = maxOf(
            requireNotNull(metric.uniform.accuracy),
            requireNotNull(metric.stateIgnorantEmpirical.accuracy),
        )
        return neural >= strongestBaseline
    }

    private fun executeTechnicalSmoke(
        policy: CandidateConditionedNeuralPolicy,
        encoder: NeuralBehavioralCloningFeatureEncoder,
    ): NeuralBcTechnicalExecution {
        val gameId = "neural-bc-technical-smoke"
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Player 0", deck.deck()),
                    PlayerConfig("Player 1", deck.deck()),
                ),
                skipMulligans = false,
                useHandSmoother = false,
                startingPlayerIndex = 0,
                seed = baseSeed xor 0x4e42534d4f4b45L,
            )
        )
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = gameId,
            seedBase = baseSeed xor 0x534d4f4b454e42L,
            effectiveSetupSeed = baseSeed xor 0x4e42534d4f4b45L,
           expander = UnifiedSemanticExpander(actionSpaceProfile = profile.actionSpaceProfile),
           knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck),
        )
        val candidateCounts = mutableListOf<Int>()
        val decisionFamilies = mutableListOf<String>()
        val selectedFamilies = mutableListOf<SemanticOperationFamily>()
        var accepted = 0
        var currentMembership = true
        var allAccepted = true
        repeat(12) {
            val actor = world.actorToAct() ?: return@repeat
            val information = world.informationState(actor)
            if (information.terminated) return@repeat
            val input = BoundedPolicyInputCompiler.compile(information)
            val encoded = encoder.encodeForInference(input)
            val selectedIndex = policy.selectIndex(encoded)
            val selected = input.candidates[selectedIndex]
            candidateCounts += input.candidates.size
            decisionFamilies += encoded.decisionFamily
            selectedFamilies += selected.operationFamily
            currentMembership = currentMembership && selected in input.candidates
            val result = world.step(selected)
            allAccepted = allAccepted && result.accepted
            if (!result.accepted) return@repeat
            accepted++
        }
        val passed = accepted == candidateCounts.size && accepted >= 8 && currentMembership && allAccepted
        return NeuralBcTechnicalExecution(
            gameId = gameId,
            attemptedDecisions = candidateCounts.size,
            acceptedDecisions = accepted,
            candidateCounts = candidateCounts,
            decisionFamilies = decisionFamilies,
            selectedFamilies = selectedFamilies,
            allSelectionsWereCurrentCandidates = currentMembership,
            allSemanticStepsAccepted = allAccepted,
            usedOnlyBoundedInputsForScoring = true,
            passed = passed,
        )
    }

    private fun isNontrivial(decision: EncodedBcDecision): Boolean =
        decision.candidateCount >= PRIMARY_MIN_CANDIDATES

    private fun candidateRange(count: Int): String = when (count) {
        2 -> "2"
        in 3..4 -> "3-4"
        else -> "5+"
    }
}

internal fun neuralBcRepresentationDiagnostics(
    decisions: List<EncodedBcDecision>,
): NeuralBcRepresentationDiagnostics {
    val selected = decisions.filter { it.candidateCount >= PRIMARY_MIN_CANDIDATES }
    val groups = selected.groupBy(::encodedNeuralBcDecisionKey)
    val maximumCorrect = groups.values.sumOf { repeated ->
        // Equal candidate vectors must receive equal scores. The production inference tie-break
        // makes only the first index in each equal-vector class a possible exact prediction.
        repeated.groupingBy(EncodedBcDecision::labelIndex).eachCount().filterKeys { labelIndex ->
            repeated.first().candidates.indices.none { earlierIndex ->
                earlierIndex < labelIndex && sameSparseFeatureVector(
                    repeated.first().candidates[earlierIndex],
                    repeated.first().candidates[labelIndex],
                )
            }
        }.values.maxOrNull() ?: 0
    }
    return NeuralBcRepresentationDiagnostics(
        trainingDecisions = selected.size,
        distinctEncodedDecisionKeys = groups.size,
        repeatedEncodedDecisionGroups = groups.count { it.value.size > 1 },
        repeatedGroupsWithConflictingLabels = groups.count { (_, repeated) ->
            repeated.map { it.labelIndex }.distinct().size > 1
        },
        decisionsWithCandidateFeatureCollisions = selected.count(::hasCandidateFeatureCollision),
        labelsSharingFeaturesWithAnotherCurrentCandidate = selected.count { decision ->
            decision.candidates.indices.any { index ->
                index != decision.labelIndex &&
                    sameSparseFeatureVector(decision.candidates[index], decision.candidates[decision.labelIndex])
            }
        },
        maximumTrainingAccuracyPermittedByEncodedFeatures = maximumCorrect.toDouble() / selected.size,
    )
}

internal fun hasCandidateFeatureCollision(decision: EncodedBcDecision): Boolean =
    decision.candidates.indices.any { left ->
        (left + 1 until decision.candidateCount).any { right ->
            sameSparseFeatureVector(decision.candidates[left], decision.candidates[right])
        }
    }

internal fun sameSparseFeatureVector(left: SparseFeatureVector, right: SparseFeatureVector): Boolean =
    left.indices.contentEquals(right.indices) && left.values.contentEquals(right.values)

internal fun encodedNeuralBcDecisionKey(decision: EncodedBcDecision): String = buildString {
    append(neuralBcVectorKey(decision.state))
    decision.candidates.forEach { candidate -> append('|').append(neuralBcVectorKey(candidate)) }
}

internal fun neuralBcVectorKey(vector: SparseFeatureVector): String =
    vector.indices.indices.joinToString(",") { index ->
        "${vector.indices[index]}:${vector.values[index].toBits()}"
    }

internal fun strictWholeGameAdmissionSelection(
    files: List<CorpusValidationFile>,
    failures: List<String>,
    minimumGames: Int,
): Set<String> {
    val fileFailures = files.flatMap { file -> file.failures.map { "${file.gameId}: $it" } }
    require(failures == fileFailures) {
        "Corpus-wide admission failed and cannot be repaired by whole-game exclusion: $failures"
    }
    val admitted = files.filter(CorpusValidationFile::passed).map(CorpusValidationFile::gameId).toSet()
    require(admitted.size >= minimumGames) {
        "Whole-game exclusion left ${admitted.size} games; at least $minimumGames are required"
    }
    return admitted
}

private fun subsetCorpusManifest(source: CorpusManifest, admittedGameIds: Set<String>): CorpusManifest {
    require(source.passed) { "A producer-rejected manifest cannot be repaired for training" }
    val entries = source.entries.filter { it.gameId in admittedGameIds }
    require(entries.size == admittedGameIds.size)
    val terminalGames = entries.count { it.game.terminal }
    val replayVerifiedGames = entries.count(CorpusEntry::replayVerified)
    val identity = CorpusManifest.computeDatasetIdentity(
        profileId = source.profileId,
        profileHash = source.profileHash,
        sourceProvenance = source.sourceProvenance,
        requestedGames = entries.size,
        terminalGames = terminalGames,
        replayVerifiedGames = replayVerifiedGames,
        entries = entries,
        passed = true,
    )
    return source.copy(
        generatedAtUtc = Instant.now().toString(),
        requestedGames = entries.size,
        terminalGames = terminalGames,
        replayVerifiedGames = replayVerifiedGames,
        entries = entries,
        passed = true,
        datasetIdentity = identity,
    )
}

@Serializable
private data class RootEvidenceSidecarHeader(
    val schemaVersion: Int = 1,
    val documentKind: String = "neural-bc-root-evidence-sidecar-v1",
    val datasetIdentity: String,
    val sourceProvenance: PolicySourceProvenance,
    val profileId: String,
    val profileHash: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val planner: SearchPlannerKind,
    val policyEvidenceIdentity: String,
    val evaluatorVersion: String,
    val invokedEvaluatorConfigurationId: String,
    val beliefVersion: String,
    val opponentModelVersion: String,
    val simulations: Int,
    val particles: Int,
    val supervisionUse: String = "NONE_RETAINED_FOR_OPTIONAL_FUTURE_RESEARCH",
)

@Serializable
private data class RootEvidenceCandidate(
    val semanticSignature: String,
    val operationFamily: SemanticOperationFamily,
    val visits: Int,
    val backedMeanValue: Double,
    val selected: Boolean,
    val disposition: String = "SEARCHED_VALID_CANDIDATE",
)

@Serializable
private data class RootEvidenceSidecarDecision(
    val schemaVersion: Int = 1,
    val gameId: String,
    val decisionIndex: Int,
    val boundedInputDigest: String,
    val selectedSemanticSignature: String,
    val rootBackedMeanValue: Double,
    val completedVisits: Int,
    val profileExhaustive: Boolean,
    val initialProfileCandidateCount: Int,
    val fullProfileCandidateCount: Long?,
    val omissionReasons: List<String>,
    val acceptedAuthoritativeTransition: Boolean = true,
    val terminalGameLinked: Boolean = true,
    val rejectedTransitions: Int,
    val candidates: List<RootEvidenceCandidate>,
)

private fun writeRootEvidenceSidecar(
    root: Path,
    manifest: CorpusManifest,
    examples: List<BehavioralCloningExample>,
    target: Path,
) {
    val examplesByKey = examples.associateBy { it.gameId to it.decisionIndex }
    val first = examples.first()
    val header = RootEvidenceSidecarHeader(
        datasetIdentity = manifest.datasetIdentity,
        sourceProvenance = manifest.sourceProvenance,
        profileId = manifest.profileId,
        profileHash = manifest.profileHash,
        actionSpaceProfile = first.evidence.actionSpaceProfile,
        planner = first.evidence.searchPlanner,
        policyEvidenceIdentity = first.evidence.policyEvidenceIdentity,
        evaluatorVersion = first.evidence.evaluatorVersion,
        invokedEvaluatorConfigurationId = first.evidence.invokedEvaluatorConfigurationId,
        beliefVersion = first.evidence.beliefVersion,
        opponentModelVersion = first.evidence.opponentModelVersion,
        simulations = first.evidence.simulations,
        particles = first.evidence.particles,
    )
    Files.createDirectories(target.parent)
    GZIPOutputStream(Files.newOutputStream(target)).bufferedWriter().use { writer ->
        writer.appendLine(evidenceJson.encodeToString(header))
        manifest.entries.sortedBy { it.gameId }.forEach { entry ->
            val trajectory = root.resolve(entry.publicTrajectory)
            GZIPInputStream(Files.newInputStream(trajectory)).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val record = PolicyJson.format.decodeFromString(PolicyTrajectoryRecord.serializer(), line)
                    if (record !is PolicyTrajectoryDecision) return@forEach
                    val example = examplesByKey[record.gameId to record.decisionIndex] ?: return@forEach
                    writer.appendLine(evidenceJson.encodeToString(
                        RootEvidenceSidecarDecision(
                            gameId = record.gameId,
                            decisionIndex = record.decisionIndex,
                            boundedInputDigest = example.policyInput.inputDigest,
                            selectedSemanticSignature = example.teacherAction.signature,
                            rootBackedMeanValue = record.rootValue,
                            completedVisits = record.candidates.sumOf { it.visits },
                            profileExhaustive = record.expansion.isProfileExhaustive,
                            initialProfileCandidateCount = record.candidates.size,
                            fullProfileCandidateCount = record.expansion.estimatedCandidateCount,
                            omissionReasons = record.expansion.omissionReasons.map { it.name }.sorted(),
                            rejectedTransitions = record.searchDiagnostics.rejectedTransitions,
                            candidates = record.candidates.map { candidate ->
                                RootEvidenceCandidate(
                                    semanticSignature = candidate.choice.signature,
                                    operationFamily = candidate.choice.operationFamily,
                                    visits = candidate.visits,
                                    backedMeanValue = candidate.meanValue,
                                    selected = candidate.choice == example.teacherAction,
                                )
                            },
                        )
                    ))
                }
            }
        }
    }
}

internal fun renderNeuralBehavioralCloning(report: NeuralBcExperimentReport): String = buildString {
    fun pct(value: Double?): String = value?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"
    appendLine("# First neural behavioral-cloning experiment")
    appendLine()
    appendLine("## Conclusion")
    appendLine()
    appendLine("`${report.conclusion}`")
    appendLine()
    appendLine("This result measures imitation of realized labels from the exact identified Search Teacher. " +
        "It is not a claim of strategic correctness or playing strength.")
    appendLine()
    appendLine("Admission requires the neural mean to clear both primary baselines by more than two points; " +
        "at least two ten-decision families must do the same; material candidate-count and common/tail cohorts " +
        "must not trail their strongest baseline; the three-seed test range must be at most eight points; and " +
        "the loaded policy must pass exact semantic execution. Training fit is diagnostic, not an admission gate.")
    appendLine()
    appendLine("## Population and boundary")
    appendLine()
    appendLine("- Experiment implementation: `${report.implementationSourceProvenance.outer.revision}`")
    appendLine("- Teacher data source: `${report.sourceProvenance.outer.revision}`; Argentum: `${report.argentumRevision}`")
    appendLine("- Corpus: ${report.admittedGames}/${report.requestedGames} complete games, " +
        "${report.admittedDecisions} admitted decisions (${report.nontrivialDecisions} nontrivial; " +
        "${report.singletonDecisions} singleton)")
    if (report.excludedGames > 0) {
        appendLine("- Whole-game exclusions: ${report.excludedGames}; ${report.admissionFailures.joinToString("; ")}")
    }
    appendLine("- Split: `${report.split.splitIdentity}`; games train/validation/test = " +
        "${report.split.trainGames.size}/${report.split.validationGames.size}/${report.split.testGames.size}")
    appendLine("- Nontrivial decisions train/validation/test = " +
        "${report.nontrivialDecisionsBySplit.getValue("train")}/" +
        "${report.nontrivialDecisionsBySplit.getValue("validation")}/" +
        "${report.nontrivialDecisionsBySplit.getValue("test")}")
    appendLine("- Input: `BoundedPolicyInput` V5 explicit safe state plus candidate-local " +
        "operation family, typed intent, source card, target relation, and canonical-payload structure " +
        "resolved to visible semantic descriptors")
    appendLine("- Explicitly absent from features: ${report.forbiddenFeatureClasses.joinToString("; ")}")
    appendLine()
    appendLine("## Primary results")
    appendLine()
    appendLine("| Population | n | Uniform | Empirical state-ignorant | Neural mean | Neural range |")
    appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
    fun row(metric: BcCohortMetrics) {
        appendLine("| ${metric.cohort} | ${metric.decisions} | ${pct(metric.uniform.accuracy)} | " +
            "${pct(metric.stateIgnorantEmpirical.accuracy)} | ${pct(metric.neuralMeanAccuracy)} | " +
            "${pct(metric.neuralMinimumAccuracy)}–${pct(metric.neuralMaximumAccuracy)} |")
    }
    row(report.primaryTest)
    row(report.allHeldOutCoverage)
    appendLine()
    appendLine("### Training initializations")
    appendLine()
    appendLine("| Seed | Best epoch | Max train fit | Selected train | Validation | Test | Model SHA-256 |")
    appendLine("| ---: | ---: | ---: | ---: | ---: | ---: | --- |")
    report.seedResults.forEach { result ->
        appendLine("| ${result.seed} | ${result.bestEpoch} | ${pct(result.maximumTrainingAccuracy)} | " +
            "${pct(result.selectedCheckpointTrainingAccuracy)} | " +
            "${pct(result.validationAccuracy)} | ${pct(result.testAccuracy)} | `${result.modelSha256}` |")
    }
    appendLine()
    appendLine("### Decision-family behavior")
    appendLine()
    appendLine("| Family | n | Uniform | Empirical | Neural mean |")
    appendLine("| --- | ---: | ---: | ---: | ---: |")
    report.byDecisionFamily.forEach { metric ->
        appendLine("| ${metric.cohort} | ${metric.decisions} | ${pct(metric.uniform.accuracy)} | " +
            "${pct(metric.stateIgnorantEmpirical.accuracy)} | ${pct(metric.neuralMeanAccuracy)} |")
    }
    appendLine()
    appendLine("### Candidate-count behavior")
    appendLine()
    appendLine("| Candidate range | n | Uniform | Empirical | Neural mean |")
    appendLine("| --- | ---: | ---: | ---: | ---: |")
    report.byCandidateCountRange.forEach { metric ->
        appendLine("| ${metric.cohort} | ${metric.decisions} | ${pct(metric.uniform.accuracy)} | " +
            "${pct(metric.stateIgnorantEmpirical.accuracy)} | ${pct(metric.neuralMeanAccuracy)} |")
    }
    appendLine()
    appendLine("### Action-frequency behavior")
    appendLine()
    report.byActionFrequency.forEach(::row)
    appendLine()
    appendLine("## Model and execution")
    appendLine()
    appendLine("The model is a ${report.modelConfig.hiddenDimension}-dimensional bilinear state/candidate scorer " +
        "with ${report.modelConfig.parameterCount} trainable parameters. It used Adam at " +
        "${report.trainingConfig.learningRate}, at most ${report.trainingConfig.maximumEpochs} epochs, " +
        "validation-only checkpoint selection, a full training-fit probe, and seeds " +
        "${report.trainingConfig.initializationSeeds}.")
    appendLine()
    val diagnostics = report.representationDiagnostics
    appendLine("The training feature representation has ${diagnostics.distinctEncodedDecisionKeys}/" +
        "${diagnostics.trainingDecisions} distinct decision keys, " +
        "${diagnostics.repeatedGroupsWithConflictingLabels} conflicting repeated groups, and " +
        "${diagnostics.labelsSharingFeaturesWithAnotherCurrentCandidate} labels sharing an encoding with another " +
        "current candidate. Its empirical fit ceiling is " +
        "${pct(diagnostics.maximumTrainingAccuracyPermittedByEncodedFeatures)}.")
    appendLine()
    appendLine("The loaded seed-${report.trainingConfig.initializationSeeds.first()} artifact selected and " +
        "semantically executed ${report.technicalExecution.acceptedDecisions}/" +
        "${report.technicalExecution.attemptedDecisions} actual profile candidates; " +
        "passed=${report.technicalExecution.passed}. Save/load scores were identical=" +
        "${report.persistenceRoundTripPassed}.")
    appendLine()
    appendLine("## Repeatability context and limitations")
    appendLine()
    appendLine(report.searchTeacherRepeatabilityContext)
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Retained evidence")
    appendLine()
    appendLine("- Corpus attempt manifest: `${report.corpusAttemptManifestPath}`")
    appendLine("- Corpus manifest: `${report.corpusManifestPath}`")
    appendLine("- Split identity: `${report.split.splitIdentity}`")
    report.seedResults.forEach { appendLine("- Model seed ${it.seed}: `${it.modelPath}`") }
    appendLine("- Root evidence sidecar: `${report.rootEvidenceSidecarPath}` " +
        "(`${report.rootEvidenceSidecarSha256}`); it was not read by training")
}
