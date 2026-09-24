package org.mtgallium.agent.monored

import org.mtgallium.agent.monored.ValueEvaluationStop
import java.util.Base64
import kotlin.math.ln1p
import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.PERSPECTIVE_EVENT_SCHEMA_V2
import org.mtgallium.agent.infoset.core.PolicyAudience
import org.mtgallium.agent.infoset.core.PolicyAudienceScope
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyCombatView
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyKnownLibraryOrder
import org.mtgallium.agent.infoset.core.PolicyKnownObject
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PlayerObservationSnapshot
import org.mtgallium.agent.infoset.core.PolicyPendingDecisionView
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.PolicyStackItemView
import org.mtgallium.agent.infoset.core.PolicyZoneKnowledge
import org.mtgallium.agent.infoset.core.PolicyZoneView
import org.mtgallium.agent.infoset.core.SearchSettlementOrigin
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.PolicyDecisionChoiceSpec
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.Weighted

class LinearValueEvaluatorTest {
    @Test
    fun `root-relative features support opponent-to-act leaves and ignore raw player identities`() {
        val rootToAct = state(rootPlayer = "p0", opponentPlayer = "p1", actor = "p0")
        val opponentToAct = state(rootPlayer = "p0", opponentPlayer = "p1", actor = "p1")
        val renamedOpponentToAct = state(
            rootPlayer = "renamed-root",
            opponentPlayer = "renamed-opponent",
            actor = "renamed-opponent",
        )

        val rootFeatures = ValueFeatures.compile(rootToAct, "p0")
        val opponentFeatures = ValueFeatures.compile(opponentToAct, "p0")
        val renamedFeatures = ValueFeatures.compile(
            renamedOpponentToAct,
            "renamed-root",
        )
        val seatSwappedFeatures = ValueFeatures.compile(
            state(rootPlayer = "p1", opponentPlayer = "p0", actor = "p0"),
            "p1",
        )

        assertEquals(opponentFeatures.values, renamedFeatures.values)
        assertEquals(opponentFeatures.values, seatSwappedFeatures.values)
        assertNotEquals(rootFeatures.values, opponentFeatures.values)
        val opponentActorKey = (opponentFeatures.values.keys - rootFeatures.values.keys).first()
        val evaluator = evaluator(weights = mapOf(opponentActorKey to 1.0))
        assertTrue(evaluator.evaluate(opponentToAct, "p0") > evaluator.evaluate(rootToAct, "p0"))
    }

    @Test
    fun `feature allowlist ignores candidates digests opaque refs payloads and hidden opponent cards`() {
        val first = state(excludedSalt = "first")
        val second = state(excludedSalt = "second")

        assertEquals(
            ValueFeatures.compile(first, "p0").values,
            ValueFeatures.compile(second, "p0").values,
        )
    }

    @Test
    fun `final sparse aggregation log scales repeated history and visible cards`() {
        val repeats = 16
        val features = ValueFeatures.compile(
            state(repeatedHistoryCount = repeats, repeatedPermanentCount = repeats),
            "p0",
        ).values

        assertEquals(
            ln1p(repeats.toDouble()),
            features.getValue(featureKey("history", "kind", "DAMAGE")),
            1e-12,
        )
        assertEquals(
            ln1p(2.0 * repeats),
            features.getValue(featureKey("history", "damage", "noncombat")),
            1e-12,
        )
        assertEquals(
            ln1p(repeats.toDouble()),
            features.getValue(featureKey("card", "present", "root", "BATTLEFIELD", "root", "root")),
            1e-12,
        )
        assertTrue(features.getValue(featureKey("history", "kind", "DAMAGE")) < repeats)
    }

    @Test
    fun `compiler accepts every current typed history detail only with its projector kind`() {
        data class Case(
            val name: String,
            val kind: PolicyHistoryEventKind,
            val detail: PerspectiveEventDetail,
            val expectedFailure: ValueInputError? = null,
        )

        val cases = listOf(
            Case("choice", PolicyHistoryEventKind.ACTION, PerspectiveEventDetail.Choice(
                semanticSignature = "choice", choiceKind = "ACTION", operationFamily = SemanticOperationFamily.OTHER,
                privateToActor = false, strategicallyOptional = true,
            )),
            Case("combat choice", PolicyHistoryEventKind.COMBAT_DECLARATION, PerspectiveEventDetail.Choice(
                semanticSignature = "combat-choice", choiceKind = "ACTION",
                operationFamily = SemanticOperationFamily.DECLARE_ATTACKERS,
                privateToActor = false, strategicallyOptional = true,
            )),
            Case("zone change", PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION, PerspectiveEventDetail.ZoneChange(
                ownerId = "p0", fromZone = "HAND", toZone = "BATTLEFIELD", cardName = "Shock",
            )),
            Case("draw", PolicyHistoryEventKind.DRAW, PerspectiveEventDetail.Draw(
                playerId = "p0", count = 1,
            )),
            Case("reveal", PolicyHistoryEventKind.REVEAL, PerspectiveEventDetail.Reveal(
                ownerId = "p0", zone = "HAND", cardNames = listOf("Shock"),
            )),
            Case("look", PolicyHistoryEventKind.REVEAL, PerspectiveEventDetail.Look(
                ownerId = "p0", zone = "LIBRARY", cardNames = listOf("Mountain"), ordered = true, fromTop = true,
            )),
            Case("library reorder", PolicyHistoryEventKind.REVEAL, PerspectiveEventDetail.LibraryReorder(
                playerId = "p0", orderedCardNames = listOf("Mountain"),
            )),
            Case("shuffle", PolicyHistoryEventKind.SHUFFLE, PerspectiveEventDetail.Shuffle(
                playerId = "p0", cause = "EFFECT",
            )),
            Case("life", PolicyHistoryEventKind.LIFE_CHANGE, PerspectiveEventDetail.LifeChange(
                playerId = "p0", oldLife = 20, newLife = 19, reason = "DAMAGE",
            )),
            Case("damage", PolicyHistoryEventKind.DAMAGE, PerspectiveEventDetail.Damage(
                sourceName = "Shock", sourceObjectRef = null, targetName = "Opponent", targetObjectRef = "p1",
                amount = 2, combat = false,
            )),
            Case("counter", PolicyHistoryEventKind.COUNTER_CHANGE, PerspectiveEventDetail.CounterChange(
                objectRef = null, objectName = "Creature", counterType = "+1/+1", delta = 1,
            )),
            Case("object state", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque-ref", objectName = "Mountain", change = "TAPPED", value = "UNSPECIFIED",
            )),
            Case("untapped object state", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque-ref", objectName = "Mountain", change = "UNTAPPED",
            )),
            Case("attached object state", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque-ref", objectName = "Aura", change = "ATTACHED", relatedObjectRefs = listOf("host-ref"),
            )),
            Case("unattached object state", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque-ref", objectName = "Aura", change = "UNATTACHED", relatedObjectRefs = listOf("host-ref"),
            )),
            Case("transformed object state", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque-ref", objectName = "Werewolf", change = "TRANSFORMED", value = "true",
            )),
            Case("current transformed object state", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                schemaVersion = PERSPECTIVE_EVENT_SCHEMA_V2,
                objectRef = "opaque-ref",
                objectName = "Werewolf",
                change = "TRANSFORMED",
                value = "true",
                knowledgeObjectKey = "remembered-object",
            )),
            Case("controller object state", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque-ref", objectName = "Creature", change = "CONTROLLER_CHANGED", value = "p1",
            )),
            Case("causal", PolicyHistoryEventKind.CAUSAL, PerspectiveEventDetail.Causal(
                eventType = "SPELL_RESOLVED", actorId = "p0", sourceName = "Shock", sourceObjectRef = null,
            )),
            Case("resource", PolicyHistoryEventKind.RESOURCE_CHANGE, PerspectiveEventDetail.ResourceChange(
                playerId = "p0", resource = "MANA", delta = 1, reason = "ADDED",
            )),
            Case("characteristic", PolicyHistoryEventKind.CHARACTERISTIC_CHANGE, PerspectiveEventDetail.CharacteristicChange(
                objectRef = null, objectName = "Creature", characteristic = "POWER", value = "3", sourceName = null,
            )),
            Case("combat", PolicyHistoryEventKind.COMBAT_DECLARATION, PerspectiveEventDetail.Combat(
                declaration = "ATTACKERS", actorId = "p0",
            )),
            Case("turn", PolicyHistoryEventKind.TURN_STRUCTURE, PerspectiveEventDetail.TurnStructure(
                turnNumber = 4, phase = "MAIN", step = "MAIN", activePlayerId = "p0", priorityPlayerId = "p1",
            )),
            Case(
                "terminal",
                PolicyHistoryEventKind.TERMINAL,
                PerspectiveEventDetail.Terminal(winnerId = "p0", reason = "CONCEDE"),
                ValueInputError.INPUT_OUTCOME_PRESENT,
            ),
            Case(
                "unsupported",
                PolicyHistoryEventKind.UNSUPPORTED_VISIBLE_TRANSITION,
                PerspectiveEventDetail.UnsupportedVisibleTransition(engineEventType = "FutureEvent", reason = "not modeled"),
            ),
        )

        cases.forEach { case ->
            val compile = {
                ValueFeatures.compile(
                    stateWithHistory(historyEvent(case.kind, case.detail)),
                    "p0",
                )
            }
            if (case.expectedFailure == null) {
                compile()
            } else {
                assertEquals(
                    case.expectedFailure,
                    assertFailsWith<ValueEvaluationException> { compile() }.kind,
                    case.name,
                )
            }
        }
    }

    @Test
    fun `compiler rejects unknown history schemas kind mismatches and object-state impostors`() {
        data class Case(
            val name: String,
            val kind: PolicyHistoryEventKind,
            val detail: PerspectiveEventDetail,
            val expectedFailure: ValueInputError = ValueInputError.INPUT_HISTORY_INVALID,
        )

        val cases = listOf(
            Case("unknown schema", PolicyHistoryEventKind.DAMAGE, PerspectiveEventDetail.Damage(
                schemaVersion = 99, sourceName = "Shock", sourceObjectRef = null, targetName = "Opponent",
                targetObjectRef = "p1", amount = 2, combat = false,
            )),
            Case("kind mismatch", PolicyHistoryEventKind.DRAW, PerspectiveEventDetail.Damage(
                sourceName = "Shock", sourceObjectRef = null, targetName = "Opponent", targetObjectRef = "p1",
                amount = 2, combat = false,
            )),
            Case("tapped false enum", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque", objectName = "Mountain", change = "TAPPED", value = "ATTACK",
            )),
            Case("untapped false string", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque", objectName = "Mountain", change = "UNTAPPED", value = "null",
            )),
            Case("attached missing relation", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque", objectName = "Aura", change = "ATTACHED",
            )),
            Case("transformed false boolean", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque", objectName = "Werewolf", change = "TRANSFORMED", value = "TRUE",
            )),
            Case("schema v2 non-transform", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                schemaVersion = PERSPECTIVE_EVENT_SCHEMA_V2,
                objectRef = "opaque",
                objectName = "Mountain",
                change = "UNTAPPED",
            )),
            Case(
                "controller raw reference",
                PolicyHistoryEventKind.OBJECT_STATE,
                PerspectiveEventDetail.ObjectState(
                    objectRef = "opaque", objectName = "Creature", change = "CONTROLLER_CHANGED", value = "entity-42",
                ),
                ValueInputError.INPUT_PLAYER_CONTRACT_INVALID,
            ),
            Case("unknown object-state change", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque", objectName = "Creature", change = "BECAME_MYSTERIOUS", value = "anything",
            )),
        )

        cases.forEach { case ->
            val failure = assertFailsWith<ValueEvaluationException> {
                ValueFeatures.compile(
                    stateWithHistory(historyEvent(case.kind, case.detail)),
                    "p0",
                )
            }
            assertEquals(case.expectedFailure, failure.kind, case.name)
        }
    }

    @Test
    fun `compiler admits only the live coarse visible-transition null-detail record`() {
        val validCases = listOf(
            PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION to coarseTransitionPayload(),
            PolicyHistoryEventKind.FORCED_TRANSITION to coarseTransitionPayload(priorityChange = true),
        )
        validCases.forEach { (kind, payload) ->
            val features = ValueFeatures.compile(
                stateWithHistory(coarseTransitionEvent(kind, payload)),
                "p0",
            )
            val encodedDigest = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "opaque-before-observation".toByteArray(Charsets.UTF_8),
            )
            assertTrue(features.values.keys.none { encodedDigest in it })
        }

        data class Case(
            val name: String,
            val event: PolicyHistoryEvent,
        )
        val cases = listOf(
            Case(
                "legacy-looking public audience",
                coarseTransitionEvent(
                    PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION,
                    coarseTransitionPayload(),
                    audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
                ),
            ),
            Case(
                "non-null actor",
                coarseTransitionEvent(
                    PolicyHistoryEventKind.FORCED_TRANSITION,
                    coarseTransitionPayload(),
                    actor = "p0",
                ),
            ),
            Case(
                "unproduced kind",
                coarseTransitionEvent(PolicyHistoryEventKind.ACTION, coarseTransitionPayload()),
            ),
            Case(
                "payload identifier field",
                coarseTransitionEvent(
                    PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION,
                    buildJsonObject {
                        put("fromObservation", JsonPrimitive("opaque-before-observation"))
                        put("toObservation", JsonPrimitive("opaque-after-observation"))
                        put("zoneDelta", JsonArray(emptyList()))
                        put("rawEngineId", JsonPrimitive("never admitted"))
                    },
                ),
            ),
            Case(
                "malformed zone delta",
                coarseTransitionEvent(
                    PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION,
                    coarseTransitionPayload(zoneDelta = buildJsonArray {
                        add(buildJsonObject {
                            put("key", JsonPrimitive("p0:BATTLEFIELD:Mountain"))
                            put("before", JsonPrimitive(1))
                            put("after", JsonPrimitive(1))
                        })
                    }),
                ),
            ),
            Case(
                "partial priority pair",
                coarseTransitionEvent(
                    PolicyHistoryEventKind.FORCED_TRANSITION,
                    buildJsonObject {
                        put("fromObservation", JsonPrimitive("opaque-before-observation"))
                        put("toObservation", JsonPrimitive("opaque-after-observation"))
                        put("zoneDelta", JsonArray(emptyList()))
                        put("priorityFrom", JsonNull)
                    },
                ),
            ),
        )
        cases.forEach { case ->
            val failure = assertFailsWith<ValueEvaluationException> {
                ValueFeatures.compile(stateWithHistory(case.event), "p0")
            }
            assertEquals(ValueInputError.INPUT_HISTORY_INVALID, failure.kind, case.name)
        }
    }

    @Test
    fun `compiler rejects mismatched perspective incomplete knowledge and incomplete current turn`() {
        fun failure(block: () -> Unit): ValueInputError =
            assertFailsWith<ValueEvaluationException>(block = block).kind

        assertEquals(
            ValueInputError.INPUT_PERSPECTIVE_MISMATCH,
            failure { ValueFeatures.compile(state(), "p1") },
        )
        assertEquals(
            ValueInputError.INPUT_KNOWLEDGE_INCOMPLETE,
            failure {
                val incomplete = state().let {
                    it.copy(knowledge = it.knowledge.copy(
                        epistemicallyComplete = false,
                        unsupportedReasons = listOf("unsupported witness"),
                    ))
                }
                ValueFeatures.compile(incomplete, "p0")
            },
        )
        assertEquals(
            ValueInputError.INPUT_CURRENT_TURN_STATE_INCOMPLETE,
            failure {
                val source = state()
                ValueFeatures.compile(
                    source.copy(
                        observation = source.observation.copy(currentTurnStateComplete = false)
                    ),
                    "p0",
                )
            },
        )
    }

    @Test
    fun `terminal and outcome-bearing information cannot enter learned inference`() {
        val terminal = state().copy(terminated = true, winnerId = "p0")
        val failure = assertFailsWith<ValueEvaluationException> {
            evaluator().evaluate(terminal, "p0")
        }

        assertEquals(ValueInputError.INPUT_OUTCOME_PRESENT, failure.kind)
    }

    @Test
    fun `coefficient loading requires weights and rejects unknown fields`() {
        val features = ValueFeatures.compile(state(), "p0")
        val key = features.values.maxBy { it.value }.key
        val model = LinearWeights(weights = mapOf(key to 0.25))
        assertTrue(LinearValueEvaluator.load(model.toJson()).evaluate(features).isFinite())
        assertEquals(0.0, LinearValueEvaluator.load("""{"weights":{}}""").evaluate(features))
        assertFails { LinearValueEvaluator.load("{}") }
        assertFails { LinearValueEvaluator.load("""{"bias":0.125}""") }
        assertFails { LinearValueEvaluator.load("""{"weights":{},"note":"hand authored"}""") }
        assertFails { LinearValueEvaluator.load("not json") }
        assertFails { LinearValueEvaluator.load("""{"bias":1e309}""") }
        assertFails { LinearValueEvaluator.load("""{"weights":{"$key":1e309}}""") }
        val overflow = assertFailsWith<ValueEvaluationException> {
            evaluator(weights = mapOf(key to Double.MAX_VALUE)).evaluate(features)
        }
        assertEquals(ValueInputError.INFERENCE_NONFINITE, overflow.kind)
    }

    @Test
    fun `checkpoint factory isolates inference and identity from caller owned weights`() {
        val key = ValueFeatures.compile(state(), "p0").values.keys.first()
        val features = ValueFeatureVector(mapOf(key to 2.0))
        val weights = mutableMapOf(key to 0.25)
        val payload = checkpoint(weights)
        val encoded = payload.toJson()
        val evaluator = LinearValueEvaluator(payload)
        val identity = evaluator.configurationId

        weights[key] = -0.5
        weights.clear()

        assertEquals(LinearValueEstimate(0.5, 0.5), evaluator.evaluateDetailed(features))
        assertEquals(encoded, evaluator.model.toJson())
        assertEquals(identity, LinearValueEvaluator.load(evaluator.model.toJson()).configurationId)
    }

    @Test
    fun `linear calculation clips after scoring`() {
        val feature = ValueFeatures.compile(state(), "p0")
        val key = feature.values.keys.first()
        val positive = evaluator(weights = mapOf(key to 100.0)).evaluateDetailed(feature)
        val negative = evaluator(weights = mapOf(key to -100.0)).evaluateDetailed(feature)

        assertTrue(positive.rawScore > 1.0)
        assertEquals(1.0, positive.deployedValue)
        assertTrue(negative.rawScore < -1.0)
        assertEquals(-1.0, negative.deployedValue)
        assertEquals(
            0.5,
            evaluator(weights = mapOf(key to 0.5 / feature.values.getValue(key))).evaluate(feature),
            1e-12,
        )
    }

    @Test
    fun `tanh link maps the raw score without changing the default clipped model`() {
        val payload = LinearWeights(bias = 2.0, weights = emptyMap())
        val clipped = LinearValueEvaluator(payload)
        val linked = LinearValueEvaluator(payload, LinearValueLink.TANH)
        val features = ValueFeatureVector(emptyMap())

        assertEquals(LinearValueEstimate(2.0, 1.0), clipped.evaluateDetailed(features))
        val actual = linked.evaluateDetailed(features)
        assertEquals(2.0, actual.rawScore)
        assertEquals(tanh(2.0), actual.deployedValue, 1e-12)
        assertNotEquals(clipped.configurationId, linked.configurationId)
    }

    @Test
    fun `model roundtrip preserves coefficients and its computational identity`() {
        val key = ValueFeatures.compile(state(), "p0").values.keys.first()
        val base = LinearValueEvaluator(LinearWeights(weights = mapOf(key to 0.25)))
        val loaded = LinearValueEvaluator.load(base.model.toJson())
        assertEquals(base.configurationId, loaded.configurationId)
        assertEquals(base.evaluate(state(), "p0"), loaded.evaluate(state(), "p0"))
        assertNotEquals(base.configurationId,
            LinearValueEvaluator(LinearWeights(weights = mapOf(key to 0.5))).configurationId)
        assertEquals(setOf("bias", "weights"), Json.parseToJsonElement(base.model.toJson()).jsonObject.keys)
    }

    @Test
    fun `detailed checkpoint observer records raw and deployed values from one inference authority`() {
        val features = ValueFeatures.compile(state(), "p0")
        val key = features.values.keys.first()
        val evaluator = evaluator(weights = mapOf(key to 100.0))
        val calls = mutableListOf<LinearValueEstimate>()
        val observed = evaluator.observedEvaluationBy { _, _, evaluation -> calls += evaluation }

        val deployed = observed.evaluate(state(), "p0")

        assertEquals(1, calls.size)
        assertTrue(calls.single().rawScore > 1.0)
        assertEquals(1.0, calls.single().deployedValue)
        assertEquals(calls.single().deployedValue, deployed)
    }

    @Test
    fun `learned leaf failure becomes a typed policy stop without a private diagnostic`() {
        val policyStop = ValueEvaluationStop(
            ValueEvaluationException(
                ValueInputError.INPUT_KNOWLEDGE_INCOMPLETE,
                "private card identity must not reach evidence",
            )
        )

        assertEquals(ValueInputError.INPUT_KNOWLEDGE_INCOMPLETE, policyStop.failureKind)
        assertTrue(policyStop.cause is ValueEvaluationException)
        assertTrue(!policyStop.message.orEmpty().contains("private"))
    }

    private fun evaluator(
        weights: Map<String, Double>? = null,
    ): LinearValueEvaluator {
        val defaultKey = ValueFeatures.compile(state(), "p0").values.keys.first()
        return LinearValueEvaluator(
            checkpoint(weights = weights ?: mapOf(defaultKey to 0.1))
        )
    }

    private fun checkpoint(
        weights: Map<String, Double>,
    ): LinearWeights = LinearWeights(

        bias = 0.0,
        weights = weights,
    )

    private fun identity(name: String, digit: Char): String =
        "$name-sha256:${digit.toString().repeat(64)}"

    private fun historyEvent(
        kind: PolicyHistoryEventKind,
        detail: PerspectiveEventDetail,
    ): PolicyHistoryEvent = PolicyHistoryEvent(
        eventId = 0,
        audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
        actor = "p0",
        kind = kind,
        payload = buildJsonObject { },
        detail = detail,
    )

    private fun coarseTransitionEvent(
        kind: PolicyHistoryEventKind,
        payload: JsonObject,
        audience: PolicyAudience = PolicyAudience(PolicyAudienceScope.ENTITLED_PLAYERS, setOf("p0")),
        actor: String? = null,
    ): PolicyHistoryEvent = PolicyHistoryEvent(
        eventId = 0,
        audience = audience,
        actor = actor,
        kind = kind,
        payload = payload,
        detail = null,
    )

    private fun coarseTransitionPayload(
        zoneDelta: JsonArray = JsonArray(emptyList()),
        priorityChange: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("fromObservation", JsonPrimitive("opaque-before-observation"))
        put("toObservation", JsonPrimitive("opaque-after-observation"))
        put("zoneDelta", zoneDelta)
        if (priorityChange) {
            put("priorityFrom", JsonNull)
            put("priorityTo", JsonPrimitive("p0"))
        }
    }

    private fun stateWithHistory(history: PolicyHistoryEvent): InformationStateRepresentation = state().copy(
        history = listOf(history),
        historyCommitment = PolicyHistoryCommitment.replay(listOf(history)),
    )

    private fun featureKey(namespace: String, vararg parts: String): String =
        "$namespace/${parts.joinToString("/") {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
        }}"

    private fun knownDecks(): Map<String, Map<String, Int>> = mapOf(
        "p0" to mapOf("Mountain" to 20, "Shock" to 4),
        "p1" to mapOf("Mountain" to 20, "Shock" to 4),
    )

    private fun state(
        rootPlayer: String = "p0",
        opponentPlayer: String = "p1",
        actor: String = rootPlayer,
        excludedSalt: String = "stable",
        currentTurnStateComplete: Boolean = true,
        repeatedHistoryCount: Int = 1,
        repeatedPermanentCount: Int = 1,
    ): InformationStateRepresentation {
        val history = List(repeatedHistoryCount) { eventIndex ->
            PolicyHistoryEvent(
                eventId = eventIndex.toLong(),
                audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
                actor = rootPlayer,
                kind = PolicyHistoryEventKind.DAMAGE,
                payload = buildJsonObject { put("excluded", JsonPrimitive(excludedSalt)) },
                detail = PerspectiveEventDetail.Damage(
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
            perspectivePlayerId = rootPlayer,
            turnNumber = 4,
            phase = "PRECOMBAT_MAIN",
            step = "PRECOMBAT_MAIN",
            activePlayerId = rootPlayer,
            priorityPlayerId = actor,
            players = listOf(
                PolicyPlayerView(
                    playerId = rootPlayer,
                    name = "Root",
                    life = 14,
                    handSize = 1,
                    librarySize = 40,
                    graveyardSize = 2,
                    exileSize = 0,
                    mana = PolicyManaPool(red = 1),
                    active = true,
                    priority = actor == rootPlayer,
                    lost = false,
                    noncreatureSpellsCastThisTurn = 1,
                    redNoncombatDamageDealtThisTurn = 2,
                    landPlaysRemainingThisTurn = 1,
                ),
                PolicyPlayerView(
                    playerId = opponentPlayer,
                    name = "Opponent",
                    life = 12,
                    handSize = 1,
                    librarySize = 40,
                    graveyardSize = 1,
                    exileSize = 0,
                    mana = PolicyManaPool(),
                    active = false,
                    priority = actor == opponentPlayer,
                    lost = false,
                ),
            ),
            zones = listOf(
                PolicyZoneView(rootPlayer, "HAND", hidden = true, size = 1, cards = listOf(rootCard)),
                PolicyZoneView(opponentPlayer, "HAND", hidden = true, size = 1, cards = listOf(opponentHidden)),
                PolicyZoneView(
                    rootPlayer,
                    "BATTLEFIELD",
                    hidden = false,
                    size = repeatedPermanentCount,
                    cards = permanents,
                ),
                PolicyZoneView(opponentPlayer, "BATTLEFIELD", hidden = false, size = 0, cards = emptyList()),
            ),
            stack = listOf(
                PolicyStackItemView(
                    objectRef = "stack-$excludedSalt",
                    controllerId = rootPlayer,
                    name = "Shock",
                    kind = "SPELL",
                    oracleText = "excluded-$excludedSalt",
                    targets = listOf("stack-target-$excludedSalt"),
                )
            ),
            combat = PolicyCombatView(
                attackingPlayerId = rootPlayer,
                attackers = emptyList(),
                blockers = emptyList(),
            ),
            currentTurnStateComplete = currentTurnStateComplete,
            pendingDecision = PolicyPendingDecisionView(
                decisionKind = "ChooseTargets",
                playerId = actor,
                prompt = "excluded-$excludedSalt",
                sourceObjectRef = "decision-source-$excludedSalt",
                sourceName = "Shock",
                phase = "PRECOMBAT_MAIN",
                subjectObjectRef = "decision-subject-$excludedSalt",
                canRespond = true,
                choiceSpec = PolicyDecisionChoiceSpec.Targets(
                    requirements = kotlinx.serialization.json.JsonArray(emptyList()),
                    legalTargets = mapOf(0 to listOf("target-$excludedSalt")),
                    canCancel = false,
                ),
            ),
            observationDigest = "excluded-observation-$excludedSalt",
        )
        val knowledge = PolicyKnowledgeState(
            perspectivePlayerId = rootPlayer,
            deckCardCounts = mapOf(
                rootPlayer to mapOf("Mountain" to 20, "Shock" to 4),
                opponentPlayer to mapOf("Mountain" to 20, "Shock" to 4),
            ),
            zones = listOf(
                PolicyZoneKnowledge(rootPlayer, "HAND", 1, mapOf("Shock" to 1)),
                PolicyZoneKnowledge(opponentPlayer, "HAND", 1),
            ),
            knownObjects = listOf(
                PolicyKnownObject("knowledge-$excludedSalt", rootPlayer, "HAND", "Shock")
            ),
            knownLibraryOrders = listOf(
                PolicyKnownLibraryOrder(rootPlayer, 0, top = listOf("Mountain"))
            ),
            unlocatedCardCounts = mapOf(
                rootPlayer to mapOf("Mountain" to 19, "Shock" to 3),
                opponentPlayer to mapOf("Mountain" to 20, "Shock" to 4),
            ),
            epistemicallyComplete = true,
            unsupportedReasons = emptyList(),
            knowledgeDigest = "excluded-knowledge-$excludedSalt",
        )
        return InformationStateRepresentation(
            actingPlayerId = actor,
            observation = observation,
            informationStateDigest = "excluded-information-$excludedSalt",
            historyCommitment = PolicyHistoryCommitment.replay(history),
            history = history,
            knowledge = knowledge,
            candidates = listOf(choice("candidate-$excludedSalt")),
            terminated = false,
        )
    }

    private fun card(
        ref: String,
        name: String,
        owner: String,
        zone: String,
        excludedSalt: String,
        types: Set<String> = emptySet(),
    ): PolicyCardView = PolicyCardView(
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

    private fun choice(label: String): SemanticChoice = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.OTHER,
        display = SemanticChoiceDisplay(label),
        canonicalPayload = buildJsonObject { put("excluded", JsonPrimitive(label)) },
    )


}
