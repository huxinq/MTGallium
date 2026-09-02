package org.mtgallium.agent.infoset.core

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

const val POLICY_SCHEMA_V1: Int = 1
const val POLICY_SCHEMA_V2: Int = 2
const val POLICY_SCHEMA_V3: Int = 3
const val POLICY_SCHEMA_V4: Int = 4
const val POLICY_SCHEMA_V5: Int = 5
const val POLICY_SCHEMA_V6: Int = 6
const val POLICY_SCHEMA_CURRENT: Int = POLICY_SCHEMA_V6
const val CANDIDATE_SCHEMA_V1: Int = 1
const val CANDIDATE_SCHEMA_V2: Int = 2
const val CANDIDATE_SCHEMA_V3: Int = 3
const val CANDIDATE_SCHEMA_V4: Int = 4
const val CANDIDATE_SCHEMA_CURRENT: Int = CANDIDATE_SCHEMA_V4

/** Complete perspective-safe state for search and replay; neural policies use [BoundedPolicyInput]. */
@Serializable
data class PolicyInformationState(
    val schemaVersion: Int = POLICY_SCHEMA_CURRENT,
    val actingPlayerId: String?,
    val observation: PolicyObservation,
    val informationStateDigest: String,
    val historyCommitment: PolicyHistoryCommitment,
    val history: List<PolicyHistoryEvent>,
    /** Exact facts reconstructed from known decks and this viewer's safe event ledger. */
    val knowledge: PolicyKnowledgeState = PolicyKnowledgeState.empty(observation.perspectivePlayerId),
    val candidates: List<SemanticChoice>,
    val candidateSchemaVersion: Int = CANDIDATE_SCHEMA_CURRENT,
    val terminated: Boolean,
    val winnerId: String? = null,
) {
    init {
        require(schemaVersion == POLICY_SCHEMA_CURRENT) { "Unknown policy schema $schemaVersion" }
        require(historyCommitment.cursor == history.size) {
            "Policy-history commitment cursor does not match the supplied ledger"
        }
        require(candidateSchemaVersion == CANDIDATE_SCHEMA_CURRENT) {
            "Unknown candidate schema $candidateSchemaVersion"
        }
        require(candidates.map { it.signature }.distinct().size == candidates.size) {
            "Semantic candidate signatures must be unique"
        }
    }

    val historyDigest: String get() = historyCommitment.digest
    val historyCursor: Int get() = historyCommitment.cursor
}

object PolicyInformationStateDigest {
    fun compute(
        observationDigest: String,
        historyCommitment: PolicyHistoryCommitment,
        knowledgeDigest: String,
        actingPlayerId: String?,
        candidateSignatures: List<String>,
        proposalVersion: String,
    ): String = PolicyJson.digest(kotlinx.serialization.json.buildJsonObject {
        put("observation", JsonPrimitive(observationDigest))
        put("history", JsonPrimitive(historyCommitment.digest))
        put("knowledge", JsonPrimitive(knowledgeDigest))
        put("actor", actingPlayerId?.let(::JsonPrimitive) ?: JsonNull)
        put("candidates", JsonArray(candidateSignatures.map(::JsonPrimitive)))
        put("proposalVersion", JsonPrimitive(proposalVersion))
    })
}

@Serializable
data class PolicyObservation(
    val perspectivePlayerId: String,
    val turnNumber: Int,
    val phase: String,
    val step: String,
    val activePlayerId: String?,
    val priorityPlayerId: String?,
    val players: List<PolicyPlayerView>,
    val zones: List<PolicyZoneView>,
    val stack: List<PolicyStackItemView>,
    /** Public combat relationships. Null outside combat or in legacy schema-v1 data. */
    val combat: PolicyCombatView? = null,
    /** False only for snapshot-only/legacy projection helpers that lack authoritative turn state. */
    val currentTurnStateComplete: Boolean = false,
    val pendingDecision: PolicyPendingDecisionView?,
    /** Digest of the safe snapshot. It is not a complete information-history key. */
    val observationDigest: String,
)

@Serializable
data class PolicyPlayerView(
    val playerId: String,
    val name: String,
    val life: Int,
    val handSize: Int,
    val librarySize: Int,
    val graveyardSize: Int,
    val exileSize: Int,
    val mana: PolicyManaPool,
    val active: Boolean,
    val priority: Boolean,
    val lost: Boolean,
    /** Public Start Your Engines speed; zero means speed has not started. */
    val speed: Int = 0,
    /** Public current-turn rules facts that remain relevant after their events leave the suffix. */
    val noncreatureSpellsCastThisTurn: Int = 0,
    val lostLifeThisTurn: Boolean = false,
    val speedIncreaseTriggerFiredThisTurn: Boolean = false,
    val redNoncombatDamageDealtThisTurn: Int = 0,
    val landPlaysRemainingThisTurn: Int = 0,
) {
    init {
        require(noncreatureSpellsCastThisTurn >= 0)
        require(redNoncombatDamageDealtThisTurn >= 0)
        require(landPlaysRemainingThisTurn >= 0)
    }
}

@Serializable
data class PolicyCombatView(
    val attackingPlayerId: String?,
    val attackers: List<PolicyAttackerView>,
    val blockers: List<PolicyBlockerView>,
)

@Serializable
data class PolicyAttackerView(
    val attackerObjectRef: String,
    val defenderObjectRef: String,
    val blockerObjectRefs: List<String> = emptyList(),
)

@Serializable
data class PolicyBlockerView(
    val blockerObjectRef: String,
    val blockedAttackerObjectRefs: List<String>,
)

@Serializable
data class PolicyManaPool(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0,
    /** Public floating mana whose legal spend differs from an ordinary colored mana unit. */
    val restricted: List<PolicyRestrictedMana> = emptyList(),
)

/** Grouped, player-visible spend semantics for floating restricted mana. */
@Serializable
data class PolicyRestrictedMana(
    /** `null` denotes colorless mana. */
    val color: String?,
    /** Stable rules-facing description supplied by the authoritative mana restriction. */
    val spendRestriction: String,
    /** Public consequences carried by spending this particular mana unit. */
    val spellRiders: List<String> = emptyList(),
    /** Rules lifecycle of the mana unit, for example `END_OF_TURN`. */
    val expiresAt: String = "END_OF_TURN",
    val count: Int,
) {
    init {
        require(spendRestriction.isNotBlank())
        require(count > 0)
    }
}

@Serializable
data class PolicyZoneView(
    val ownerId: String,
    val zone: String,
    val hidden: Boolean,
    val size: Int,
    val cards: List<PolicyCardView>,
)

/** `objectRef` is an opaque, observation-scoped routing reference, not an engine entity id. */
@Serializable
data class PolicyCardView(
    val objectRef: String,
    val definitionId: String?,
    val name: String,
    val zone: String,
    val ownerId: String?,
    val controllerId: String?,
    val types: Set<String>,
    val subtypes: Set<String>,
    val colors: Set<String>,
    val keywords: Set<String>,
    val manaCost: String,
    val manaValue: Int,
    val oracleText: String,
    val power: Int?,
    val toughness: Int?,
    val tapped: Boolean,
    val summoningSick: Boolean,
    val faceDown: Boolean,
    val damageMarked: Int,
    val counters: Map<String, Int>,
    val attachedTo: String?,
    val attachments: List<String>,
    /** Public marker for a permanent cast for its Warp alternative cost. */
    val isWarped: Boolean = false,
    /** Public marker for a card exiled by Warp, independent of the current viewer's permission. */
    val isWarpExiled: Boolean = false,
    /** Whether this perspective currently has an active permission to play the exiled card. */
    val playableFromExile: Boolean = false,
    /** Frozen-scope gate for a visible object's tracked once-per-turn activation state. */
    val hasActivatedAbilityThisTurn: Boolean = false,
)

@Serializable
data class PolicyStackItemView(
    val objectRef: String,
    val controllerId: String?,
    val name: String,
    val kind: String,
    val oracleText: String,
    val targets: List<String>,
)

@Serializable
data class PolicyPendingDecisionView(
    val decisionKind: String,
    val playerId: String,
    val prompt: String,
    val sourceObjectRef: String? = null,
    val sourceName: String? = null,
    val triggeringObjectRef: String? = null,
    val effectHint: String? = null,
    val phase: String,
    val subjectObjectRef: String? = null,
    val canRespond: Boolean,
    val choiceSpec: PolicyDecisionChoiceSpec?,
)

/** Typed union for the complete visible decision contract. */
@Serializable
sealed interface PolicyDecisionChoiceSpec {
    @Serializable @SerialName("Targets")
    data class Targets(
        val requirements: JsonArray,
        val legalTargets: Map<Int, List<String>>,
        val canCancel: Boolean,
    ) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("Cards")
    data class Cards(
        val options: List<String>,
        val minSelections: Int,
        val maxSelections: Int,
        val ordered: Boolean,
        val constraints: JsonObject,
        val cardMetadata: JsonObject? = null,
    ) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("YesNo")
    data class YesNo(val yesText: String, val noText: String, val hint: String? = null) :
        PolicyDecisionChoiceSpec

    @Serializable @SerialName("BatchYesNo")
    data class BatchYesNo(val count: Int, val yesText: String, val noText: String) :
        PolicyDecisionChoiceSpec

    @Serializable @SerialName("Modes")
    data class Modes(val modes: JsonArray, val minModes: Int, val maxModes: Int) :
        PolicyDecisionChoiceSpec

    @Serializable @SerialName("Colors")
    data class Colors(val colors: List<String>) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("Number")
    data class Number(val minimum: Int, val maximum: Int) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("Distribution")
    data class Distribution(
        val total: Int,
        val targets: List<String>,
        val minimumPerTarget: Int,
        val maximumPerTarget: Map<String, Int>,
        val allowPartial: Boolean,
    ) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("Order")
    data class Order(val objects: List<String>, val cardMetadata: JsonObject? = null) :
        PolicyDecisionChoiceSpec

    @Serializable @SerialName("Piles")
    data class Piles(
        val cards: List<String>,
        val numberOfPiles: Int,
        val labels: List<String>,
        val cardMetadata: JsonObject? = null,
    ) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("Options")
    data class Options(
        val options: List<String>,
        val defaultSearch: String? = null,
        val optionCards: Map<Int, List<String>>? = null,
        val metadata: JsonArray = JsonArray(emptyList()),
        val canCancel: Boolean,
    ) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("Replacement")
    data class Replacement(
        val fromOptions: List<String>,
        val toOptions: List<String>,
        val fromMetadata: JsonArray,
        val toMetadata: JsonArray,
        val allowedToByFrom: List<List<Int>>,
        val defaultFromIndex: Int?,
    ) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("LibrarySearch")
    data class LibrarySearch(
        val options: List<String>,
        val minSelections: Int,
        val maxSelections: Int,
        val cards: JsonObject,
        val filterDescription: String,
    ) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("LibraryReorder")
    data class LibraryReorder(val cards: List<String>, val cardMetadata: JsonObject) :
        PolicyDecisionChoiceSpec

    @Serializable @SerialName("DamageAssignment")
    data class DamageAssignment(
        val attacker: String,
        val availablePower: Int,
        val orderedTargets: List<String>,
        val defender: String?,
        val minimumAssignments: Map<String, Int>,
        val defaultAssignments: Map<String, Int>,
        val hasTrample: Boolean,
        val hasDeathtouch: Boolean,
    ) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("CombatResolution")
    data class CombatResolution(val contract: JsonObject) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("ManaSources")
    data class ManaSources(val contract: JsonObject) : PolicyDecisionChoiceSpec

    @Serializable @SerialName("BudgetModal")
    data class BudgetModal(val contract: JsonObject) : PolicyDecisionChoiceSpec
}

@Serializable
data class PolicyHistoryEvent(
    val eventId: Long,
    val audience: PolicyAudience,
    val actor: String?,
    val kind: PolicyHistoryEventKind,
    val payload: JsonObject,
    /** Typed safe meaning. Null only for legacy schema-v1 records during migration. */
    val detail: PerspectiveEventDetail? = null,
)

@Serializable
data class PolicyAudience(
    val scope: PolicyAudienceScope,
    /** Empty for public events. Perspective histories never contain an unauthorized id. */
    val entitledPlayerIds: Set<String> = emptySet(),
)

@Serializable
enum class PolicyAudienceScope { PUBLIC, ENTITLED_PLAYERS }

@Serializable
enum class PolicyHistoryEventKind {
    ACTION,
    PRIORITY_PASS,
    MULLIGAN,
    COMBAT_DECLARATION,
    PUBLIC_ZONE_TRANSITION,
    REVEAL,
    DECISION,
    PRIVATE_DECISION_OCCURRED,
    FORCED_TRANSITION,
    TERMINAL,
    DRAW,
    LIFE_CHANGE,
    DAMAGE,
    COUNTER_CHANGE,
    OBJECT_STATE,
    CAUSAL,
    RESOURCE_CHANGE,
    CHARACTERISTIC_CHANGE,
    SHUFFLE,
    TURN_STRUCTURE,
    UNSUPPORTED_VISIBLE_TRANSITION,
}

@Serializable
data class SemanticChoice(
    val schemaVersion: Int = CANDIDATE_SCHEMA_CURRENT,
    /** Hash of the operation family and semantic content; excludes observation-scoped routing ids. */
    val signature: String,
    val kind: SemanticChoiceKind,
    val operationFamily: SemanticOperationFamily,
    /** Adapter-derived policy meaning. This must never be inferred from [display]. */
    val actionIntent: SemanticActionIntent,
    val display: SemanticChoiceDisplay,
    val canonicalPayload: JsonObject,
) {
    init {
        require(schemaVersion == CANDIDATE_SCHEMA_CURRENT) { "Unknown candidate schema $schemaVersion" }
        require(signature.isNotBlank()) { "A semantic choice requires a signature" }
        require(actionIntent.isCompatibleWith(operationFamily)) {
            "Action intent ${actionIntent.kind} is incompatible with $operationFamily"
        }
        require(signature == computeSignature(operationFamily, actionIntent, canonicalPayload)) {
            "Semantic choice signature does not bind its operation family, typed intent, and canonical payload"
        }
    }

    companion object {
        fun create(
            kind: SemanticChoiceKind,
            operationFamily: SemanticOperationFamily,
            actionIntent: SemanticActionIntent = SemanticActionIntent.forOperationFamily(operationFamily),
            display: SemanticChoiceDisplay,
            canonicalPayload: JsonObject,
        ): SemanticChoice = SemanticChoice(
            signature = computeSignature(operationFamily, actionIntent, canonicalPayload),
            kind = kind,
            operationFamily = operationFamily,
            actionIntent = actionIntent,
            display = display,
            canonicalPayload = canonicalPayload,
        )

        fun computeSignature(
            operationFamily: SemanticOperationFamily,
            actionIntent: SemanticActionIntent,
            canonicalPayload: JsonObject,
        ): String = PolicyJson.digest(kotlinx.serialization.json.buildJsonObject {
            put("operationFamily", JsonPrimitive(operationFamily.name))
            put(
                "actionIntent",
                PolicyJson.format.encodeToJsonElement(SemanticActionIntent.serializer(), actionIntent),
            )
            put("canonicalPayload", canonicalPayload)
        })

        /** Convenience for synthetic fixtures whose family fully determines their typed intent. */
        fun computeSignature(
            operationFamily: SemanticOperationFamily,
            canonicalPayload: JsonObject,
        ): String = computeSignature(
            operationFamily,
            SemanticActionIntent.forOperationFamily(operationFamily),
            canonicalPayload,
        )
    }
}

@Serializable
enum class SemanticChoiceKind { ACTION, DECISION }

/** Stable semantic action grammar shared by exact search, pruning profiles, history, and training. */
@Serializable
enum class SemanticOperationFamily {
    PASS_PRIORITY,
    MANA_ABILITY,
    CAST_SPELL,
    PLAY_LAND,
    ACTIVATE_ABILITY,
    DECLARE_ATTACKERS,
    DECLARE_BLOCKERS,
    MULLIGAN,
    DECISION_RESPONSE,
    OTHER,
}

/** Stable action meaning consumed by policies; presentation text is deliberately absent. */
@Serializable
data class SemanticActionIntent(
    val schemaVersion: Int = SCHEMA_V1,
    val kind: SemanticActionIntentKind,
    /** Stable card-definition name supplied by the adapter, not a rendered action label. */
    val sourceCardName: String? = null,
    val targetRelations: Set<SemanticActionTargetRelation> = emptySet(),
) {
    init {
        require(schemaVersion == SCHEMA_V1) { "Unknown semantic action-intent schema $schemaVersion" }
        require(sourceCardName == null || sourceCardName.isNotBlank())
    }

    internal fun isCompatibleWith(family: SemanticOperationFamily): Boolean = when (kind) {
        SemanticActionIntentKind.PASS_PRIORITY -> family == SemanticOperationFamily.PASS_PRIORITY
        SemanticActionIntentKind.PRODUCE_MANA -> family == SemanticOperationFamily.MANA_ABILITY
        SemanticActionIntentKind.CAST_SPELL -> family == SemanticOperationFamily.CAST_SPELL
        SemanticActionIntentKind.PLAY_LAND -> family == SemanticOperationFamily.PLAY_LAND
        SemanticActionIntentKind.ACTIVATE_ABILITY -> family == SemanticOperationFamily.ACTIVATE_ABILITY
        SemanticActionIntentKind.DECLARE_ATTACKERS,
        SemanticActionIntentKind.DECLINE_ATTACK -> family == SemanticOperationFamily.DECLARE_ATTACKERS
        SemanticActionIntentKind.DECLARE_BLOCKERS,
        SemanticActionIntentKind.DECLINE_BLOCK -> family == SemanticOperationFamily.DECLARE_BLOCKERS
        SemanticActionIntentKind.KEEP_HAND,
        SemanticActionIntentKind.TAKE_MULLIGAN,
        SemanticActionIntentKind.BOTTOM_CARDS -> family == SemanticOperationFamily.MULLIGAN
        SemanticActionIntentKind.RESPOND_TO_DECISION -> family == SemanticOperationFamily.DECISION_RESPONSE
        SemanticActionIntentKind.OTHER -> family == SemanticOperationFamily.OTHER
    }

    companion object {
        const val SCHEMA_V1: Int = 1

        fun forOperationFamily(family: SemanticOperationFamily): SemanticActionIntent =
            SemanticActionIntent(
                kind = when (family) {
                    SemanticOperationFamily.PASS_PRIORITY -> SemanticActionIntentKind.PASS_PRIORITY
                    SemanticOperationFamily.MANA_ABILITY -> SemanticActionIntentKind.PRODUCE_MANA
                    SemanticOperationFamily.CAST_SPELL -> SemanticActionIntentKind.CAST_SPELL
                    SemanticOperationFamily.PLAY_LAND -> SemanticActionIntentKind.PLAY_LAND
                    SemanticOperationFamily.ACTIVATE_ABILITY -> SemanticActionIntentKind.ACTIVATE_ABILITY
                    SemanticOperationFamily.DECLARE_ATTACKERS -> SemanticActionIntentKind.DECLARE_ATTACKERS
                    SemanticOperationFamily.DECLARE_BLOCKERS -> SemanticActionIntentKind.DECLARE_BLOCKERS
                    SemanticOperationFamily.MULLIGAN -> SemanticActionIntentKind.KEEP_HAND
                    SemanticOperationFamily.DECISION_RESPONSE -> SemanticActionIntentKind.RESPOND_TO_DECISION
                    SemanticOperationFamily.OTHER -> SemanticActionIntentKind.OTHER
                }
            )
    }
}

@Serializable
enum class SemanticActionIntentKind {
    PASS_PRIORITY,
    PRODUCE_MANA,
    CAST_SPELL,
    PLAY_LAND,
    ACTIVATE_ABILITY,
    DECLARE_ATTACKERS,
    DECLINE_ATTACK,
    DECLARE_BLOCKERS,
    DECLINE_BLOCK,
    KEEP_HAND,
    TAKE_MULLIGAN,
    BOTTOM_CARDS,
    RESPOND_TO_DECISION,
    OTHER,
}

@Serializable
enum class SemanticActionTargetRelation {
    SELF_PLAYER,
    OPPONENT_PLAYER,
    SELF_CONTROLLED_OBJECT,
    OPPONENT_CONTROLLED_OBJECT,
    OTHER_VISIBLE_OBJECT,
}

@Serializable
data class SemanticChoiceDisplay(
    val label: String,
    val sourceName: String? = null,
    val targetNames: List<String> = emptyList(),
    /** Safe, derived policy annotations; never raw engine scores or hidden-state values. */
    val policyTags: Set<String> = emptySet(),
)

@Serializable
data class PolicyExpansion(
    val candidates: List<SemanticChoice>,
    /** True only when the candidates cover the engine's complete legal action space. */
    val isExhaustive: Boolean,
    val estimatedCandidateCount: Long?,
    val proposalVersion: String,
    /** Runtime-only orchestration authority; public policy artifacts must never serialize it. */
    @Transient val proposalSeed: Long = 0L,
    /** True when every action admitted by the declared policy profile was enumerated. */
    val isProfileExhaustive: Boolean = isExhaustive,
    /** Typed provenance for deliberate or bounded omissions from this expansion. */
    val omissionReasons: Set<PolicyExpansionOmissionReason> = if (isExhaustive) {
        emptySet()
    } else {
        setOf(PolicyExpansionOmissionReason.SOURCE_NON_EXHAUSTIVE)
    },
) {
    init {
        require(estimatedCandidateCount == null || estimatedCandidateCount >= candidates.size)
        require(candidates.map { it.signature }.distinct().size == candidates.size)
        require(!isExhaustive || isProfileExhaustive) {
            "A rules-exhaustive expansion must also be profile-exhaustive"
        }
        require(isExhaustive || omissionReasons.isNotEmpty()) {
            "A non-rules-exhaustive expansion must identify its omission provenance"
        }
        require(isProfileExhaustive || omissionReasons.any { !it.intentionalProfileOmission }) {
            "A non-profile-exhaustive expansion must identify a bounded/source omission"
        }
    }
}

@Serializable
enum class PolicyExpansionOmissionReason(val intentionalProfileOmission: Boolean) {
    PROFILE_SUPPRESSED_STANDALONE_MANA(true),
    SOURCE_NON_EXHAUSTIVE(false),
    RESPONSE_LIMIT(false),
    ATTEMPT_LIMIT(false),
    GENERATED_SCAN_LIMIT(false),
}

/**
 * The only rules-equivalent automatic action compression in the common core.
 *
 * A singleton that resulted from a response cap or an action filter is not forced. A singleton
 * non-pass action is a real choice and remains an explicit search/game-tree edge.
 */
fun PolicyExpansion.exactSingletonPassOrNull(): SemanticChoice? =
    candidates.singleOrNull()?.takeIf {
        isExhaustive && it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
    }

/** Policy-relative singleton compression; this is an optimization, never rules authority. */
fun PolicyExpansion.policySingletonPassOrNull(): SemanticChoice? =
    candidates.singleOrNull()?.takeIf {
        isProfileExhaustive && it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
    }

object PolicyJson {
    val format = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }

    fun canonical(element: JsonElement): String = buildString { appendCanonical(element) }

    /** Same canonical bytes as the recursive join implementation, without its quadratic temporaries. */
    private fun StringBuilder.appendCanonical(element: JsonElement) {
        when (element) {
            JsonNull -> append("null")
            is JsonPrimitive -> append(element)
            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, child ->
                    if (index > 0) append(',')
                    appendCanonical(child)
                }
                append(']')
            }
            is JsonObject -> {
                append('{')
                element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                    if (index > 0) append(',')
                    append(format.encodeToString(key))
                    append(':')
                    appendCanonical(value)
                }
                append('}')
            }
        }
    }

    fun digest(element: JsonElement): String = sha256(canonical(element))

    fun sha256(value: String): String = reusableSha256()
        .digest(value.toByteArray(Charsets.UTF_8))
        .toLowerHex()
}

private val SHA_256_BY_THREAD = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

/** SHA-256 engines are reusable after [MessageDigest.digest]; reset also covers interrupted callers. */
internal fun reusableSha256(): MessageDigest = SHA_256_BY_THREAD.get().apply { reset() }

private const val LOWER_HEX = "0123456789abcdef"

/** Allocation-light lowercase hex; Formatter/regex dominated real search hash profiles. */
internal fun ByteArray.toLowerHex(): String {
    val chars = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        chars[index * 2] = LOWER_HEX[value ushr 4]
        chars[index * 2 + 1] = LOWER_HEX[value and 0x0f]
    }
    return String(chars)
}

internal fun String.lowerHexBytes(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' })
    return ByteArray(length / 2) { index ->
        val high = this[index * 2].digitToInt(16)
        val low = this[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}
