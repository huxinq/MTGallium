package org.mtgallium.agent.value.fixtures

import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.value.*

/** Shared information-state and linear-model builders for value-model tests. */
fun evaluator(
    weights: Map<String, Double>? = null,
): LinearValueEvaluator {
    val defaultKey = ValueFeatures.compile(state(), "p0").values.keys.first()
    return LinearValueEvaluator(
        checkpoint(weights = weights ?: mapOf(defaultKey to 0.1))
    )
}

fun checkpoint(
    weights: Map<String, Double>,
): LinearWeights = LinearWeights(

    bias = 0.0,
    weights = weights,
)

fun identity(name: String, digit: Char): String =
    "$name-sha256:${digit.toString().repeat(64)}"

fun state(
    rootPlayer: String = "p0",
    opponentPlayer: String = "p1",
    actor: String = rootPlayer,
    excludedSalt: String = "stable",
    currentTurnStateComplete: Boolean = true,
    repeatedHistoryCount: Int = 1,
    repeatedPermanentCount: Int = 1,
): InformationStateRepresentation {
    val history = List(repeatedHistoryCount) { eventIndex ->
        ObservedEvent(
            eventId = eventIndex.toLong(),
            audience = EventAudience(EventAudienceScope.PUBLIC),
            actor = rootPlayer,
            kind = ObservedEventKind.DAMAGE,
            payload = buildJsonObject { put("excluded", JsonPrimitive(excludedSalt)) },
            detail = ObservedEventDetail.Damage(
                sourceName = "Shock",
                sourceObjectRef = "source-$eventIndex-$excludedSalt",
                targetName = "Opponent",
                targetObjectRef = opponentPlayer,
                amount = 2,
                combat = false,
            ),
        )
    }
    val rootCard = card(
        ref = "root-card-$excludedSalt",
        name = "Shock",
        owner = rootPlayer,
        zone = "HAND",
        excludedSalt = excludedSalt,
    )
    val opponentHidden = card(
        ref = "opponent-hidden-$excludedSalt",
        name = if (excludedSalt == "second") "Mountain" else "Shock",
        owner = opponentPlayer,
        zone = "HAND",
        excludedSalt = excludedSalt,
    )
    val permanents = List(repeatedPermanentCount) { permanentIndex ->
        card(
            ref = "permanent-$permanentIndex-$excludedSalt",
            name = "Mountain",
            owner = rootPlayer,
            zone = "BATTLEFIELD",
            excludedSalt = excludedSalt,
            types = setOf("LAND"),
        )
    }
    val observation = PlayerObservationSnapshot(
        viewerId = rootPlayer,
        turnNumber = 4,
        phase = "PRECOMBAT_MAIN",
        step = "PRECOMBAT_MAIN",
        activePlayerId = rootPlayer,
        priorityPlayerId = actor,
        players = listOf(
            PlayerView(
                playerId = rootPlayer,
                name = "Root",
                life = 14,
                handSize = 1,
                librarySize = 40,
                graveyardSize = 2,
                exileSize = 0,
                mana = ManaPoolView(red = 1),
                active = true,
                priority = actor == rootPlayer,
                lost = false,
                noncreatureSpellsCastThisTurn = 1,
                redNoncombatDamageDealtThisTurn = 2,
                landPlaysRemainingThisTurn = 1,
            ),
            PlayerView(
                playerId = opponentPlayer,
                name = "Opponent",
                life = 12,
                handSize = 1,
                librarySize = 40,
                graveyardSize = 1,
                exileSize = 0,
                mana = ManaPoolView(),
                active = false,
                priority = actor == opponentPlayer,
                lost = false,
            ),
        ),
        zones = listOf(
            ZoneView(rootPlayer, "HAND", hidden = true, size = 1, cards = listOf(rootCard)),
            ZoneView(opponentPlayer, "HAND", hidden = true, size = 1, cards = listOf(opponentHidden)),
            ZoneView(
                rootPlayer,
                "BATTLEFIELD",
                hidden = false,
                size = repeatedPermanentCount,
                cards = permanents,
            ),
            ZoneView(opponentPlayer, "BATTLEFIELD", hidden = false, size = 0, cards = emptyList()),
        ),
        stack = listOf(
            StackObjectView(
                objectRef = "stack-$excludedSalt",
                controllerId = rootPlayer,
                name = "Shock",
                kind = "SPELL",
                oracleText = "excluded-$excludedSalt",
                targets = listOf("stack-target-$excludedSalt"),
            )
        ),
        combat = CombatView(
            attackingPlayerId = rootPlayer,
            attackers = emptyList(),
            blockers = emptyList(),
        ),
        currentTurnStateComplete = currentTurnStateComplete,
        pendingDecision = PendingDecisionView(
            decisionKind = "ChooseTargets",
            playerId = actor,
            prompt = "excluded-$excludedSalt",
            sourceObjectRef = "decision-source-$excludedSalt",
            sourceName = "Shock",
            phase = "PRECOMBAT_MAIN",
            subjectObjectRef = "decision-subject-$excludedSalt",
            canRespond = true,
            choiceSpec = PendingDecisionOptions.Targets(
                requirements = kotlinx.serialization.json.JsonArray(emptyList()),
                legalTargets = mapOf(0 to listOf("target-$excludedSalt")),
                canCancel = false,
            ),
        ),
        observationDigest = "excluded-observation-$excludedSalt",
    )
    val knowledge = PlayerKnowledge(
        viewerId = rootPlayer,
        deckCardCounts = mapOf(
            rootPlayer to mapOf("Mountain" to 20, "Shock" to 4),
            opponentPlayer to mapOf("Mountain" to 20, "Shock" to 4),
        ),
        zones = listOf(
            ZoneKnowledge(rootPlayer, "HAND", 1, mapOf("Shock" to 1)),
            ZoneKnowledge(opponentPlayer, "HAND", 1),
        ),
        knownObjects = listOf(
            KnownObject("knowledge-$excludedSalt", rootPlayer, "HAND", "Shock")
        ),
        knownLibraryOrders = listOf(
            KnownLibraryOrder(rootPlayer, 0, top = listOf("Mountain"))
        ),
        unlocatedCardCounts = mapOf(
            rootPlayer to mapOf("Mountain" to 19, "Shock" to 3),
            opponentPlayer to mapOf("Mountain" to 20, "Shock" to 4),
        ),
        isComplete = true,
        unsupportedReasons = emptyList(),
        knowledgeDigest = "excluded-knowledge-$excludedSalt",
    )
    return InformationStateRepresentation(
        actingPlayerId = actor,
        observation = observation,
        informationStateDigest = "excluded-information-$excludedSalt",
        historyCommitment = HistoryHashChain.replay(history),
        history = history,
        knowledge = knowledge,
        candidates = listOf(choice("candidate-$excludedSalt")),
        terminated = false,
    )
}

fun card(
    ref: String,
    name: String,
    owner: String,
    zone: String,
    excludedSalt: String,
    types: Set<String> = emptySet(),
): ObjectView = ObjectView(
    objectRef = ref,
    definitionId = "excluded-definition-$excludedSalt",
    name = name,
    zone = zone,
    ownerId = owner,
    controllerId = owner,
    types = types,
    subtypes = emptySet(),
    colors = setOf("RED"),
    keywords = emptySet(),
    manaCost = if (name == "Shock") "{R}" else "",
    manaValue = if (name == "Shock") 1 else 0,
    oracleText = "excluded-oracle-$excludedSalt",
    power = null,
    toughness = null,
    tapped = false,
    summoningSick = false,
    faceDown = false,
    damageMarked = 0,
    counters = emptyMap(),
    attachedTo = "excluded-attached-$excludedSalt",
    attachments = listOf("excluded-attachment-$excludedSalt"),
)

fun choice(label: String): SemanticChoice = SemanticChoice.create(
    kind = SemanticChoiceKind.ACTION,
    operationFamily = SemanticOperationFamily.OTHER,
    display = SemanticChoiceDisplay(label),
    canonicalPayload = buildJsonObject { put("excluded", JsonPrimitive(label)) },
)
