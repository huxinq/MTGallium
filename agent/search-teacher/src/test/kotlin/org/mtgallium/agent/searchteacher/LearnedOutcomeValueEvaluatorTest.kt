package org.mtgallium.agent.searchteacher

import java.util.Base64
import kotlin.math.ln1p
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.PolicyAudience
import org.mtgallium.agent.infoset.core.PolicyAudienceScope
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyCombatView
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyKnownLibraryOrder
import org.mtgallium.agent.infoset.core.PolicyKnownObject
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPendingDecisionView
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.PolicyStackItemView
import org.mtgallium.agent.infoset.core.PolicyZoneKnowledge
import org.mtgallium.agent.infoset.core.PolicyZoneView
import org.mtgallium.agent.infoset.core.SearchSettlementOrigin
import org.mtgallium.agent.infoset.core.SearchStepResult
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.PolicyDecisionChoiceSpec
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.Weighted

class LearnedOutcomeValueEvaluatorTest {
    private val learnedLeaf = LeafEvaluationConfig(
        LeafStateSource.CURRENT_INFORMATION_STATE,
        LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1,
    )

    @Test
    fun `root-relative features support opponent-to-act leaves and ignore raw player identities`() {
        val rootToAct = state(rootPlayer = "p0", opponentPlayer = "p1", actor = "p0")
        val opponentToAct = state(rootPlayer = "p0", opponentPlayer = "p1", actor = "p1")
        val renamedOpponentToAct = state(
            rootPlayer = "renamed-root",
            opponentPlayer = "renamed-opponent",
            actor = "renamed-opponent",
        )

        val rootFeatures = LearnedOutcomeValueFeatureCompiler.compile(rootToAct, "p0")
        val opponentFeatures = LearnedOutcomeValueFeatureCompiler.compile(opponentToAct, "p0")
        val renamedFeatures = LearnedOutcomeValueFeatureCompiler.compile(
            renamedOpponentToAct,
            "renamed-root",
        )
        val seatSwappedFeatures = LearnedOutcomeValueFeatureCompiler.compile(
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
            LearnedOutcomeValueFeatureCompiler.compile(first, "p0").values,
            LearnedOutcomeValueFeatureCompiler.compile(second, "p0").values,
        )
    }

    @Test
    fun `final sparse aggregation log scales repeated history and visible cards`() {
        val repeats = 16
        val features = LearnedOutcomeValueFeatureCompiler.compile(
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
            val expectedFailure: LearnedOutcomeValueFailureKind? = null,
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
                LearnedOutcomeValueFailureKind.INPUT_OUTCOME_PRESENT,
            ),
            Case(
                "unsupported",
                PolicyHistoryEventKind.UNSUPPORTED_VISIBLE_TRANSITION,
                PerspectiveEventDetail.UnsupportedVisibleTransition(engineEventType = "FutureEvent", reason = "not modeled"),
            ),
        )

        cases.forEach { case ->
            val compile = {
                LearnedOutcomeValueFeatureCompiler.compile(
                    stateWithHistory(historyEvent(case.kind, case.detail)),
                    "p0",
                )
            }
            if (case.expectedFailure == null) {
                compile()
            } else {
                assertEquals(
                    case.expectedFailure,
                    assertFailsWith<LearnedOutcomeValueException> { compile() }.failure.kind,
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
            val expectedFailure: LearnedOutcomeValueFailureKind = LearnedOutcomeValueFailureKind.INPUT_HISTORY_INVALID,
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
            Case(
                "controller raw reference",
                PolicyHistoryEventKind.OBJECT_STATE,
                PerspectiveEventDetail.ObjectState(
                    objectRef = "opaque", objectName = "Creature", change = "CONTROLLER_CHANGED", value = "entity-42",
                ),
                LearnedOutcomeValueFailureKind.INPUT_PLAYER_CONTRACT_INVALID,
            ),
            Case("unknown object-state change", PolicyHistoryEventKind.OBJECT_STATE, PerspectiveEventDetail.ObjectState(
                objectRef = "opaque", objectName = "Creature", change = "BECAME_MYSTERIOUS", value = "anything",
            )),
        )

        cases.forEach { case ->
            val failure = assertFailsWith<LearnedOutcomeValueException> {
                LearnedOutcomeValueFeatureCompiler.compile(
                    stateWithHistory(historyEvent(case.kind, case.detail)),
                    "p0",
                )
            }
            assertEquals(case.expectedFailure, failure.failure.kind, case.name)
        }
    }

    @Test
    fun `compiler admits only the live coarse visible-transition null-detail record`() {
        val validCases = listOf(
            PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION to coarseTransitionPayload(),
            PolicyHistoryEventKind.FORCED_TRANSITION to coarseTransitionPayload(priorityChange = true),
        )
        validCases.forEach { (kind, payload) ->
            val features = LearnedOutcomeValueFeatureCompiler.compile(
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
            val failure = assertFailsWith<LearnedOutcomeValueException> {
                LearnedOutcomeValueFeatureCompiler.compile(stateWithHistory(case.event), "p0")
            }
            assertEquals(LearnedOutcomeValueFailureKind.INPUT_HISTORY_INVALID, failure.failure.kind, case.name)
        }
    }

    @Test
    fun `compiler rejects mismatched perspective incomplete knowledge and broken history commitment`() {
        fun failure(block: () -> Unit): LearnedOutcomeValueFailureKind =
            assertFailsWith<LearnedOutcomeValueException>(block = block).failure.kind

        assertEquals(
            LearnedOutcomeValueFailureKind.INPUT_PERSPECTIVE_MISMATCH,
            failure { LearnedOutcomeValueFeatureCompiler.compile(state(), "p1") },
        )
        assertEquals(
            LearnedOutcomeValueFailureKind.INPUT_KNOWLEDGE_INCOMPLETE,
            failure {
                val incomplete = state().let {
                    it.copy(knowledge = it.knowledge.copy(
                        epistemicallyComplete = false,
                        unsupportedReasons = listOf("unsupported witness"),
                    ))
                }
                LearnedOutcomeValueFeatureCompiler.compile(incomplete, "p0")
            },
        )
        assertEquals(
            LearnedOutcomeValueFailureKind.INPUT_HISTORY_INVALID,
            failure {
                val source = state()
                LearnedOutcomeValueFeatureCompiler.compile(
                    source.copy(
                        historyCommitment = source.historyCommitment.copy(digest = "f".repeat(64))
                    ),
                    "p0",
                )
            },
        )
        assertEquals(
            LearnedOutcomeValueFailureKind.INPUT_CURRENT_TURN_STATE_INCOMPLETE,
            failure {
                val source = state()
                LearnedOutcomeValueFeatureCompiler.compile(
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
        val failure = assertFailsWith<LearnedOutcomeValueException> {
            evaluator().evaluate(terminal, "p0")
        }

        assertEquals(LearnedOutcomeValueFailureKind.INPUT_OUTCOME_PRESENT, failure.failure.kind)
    }

    @Test
    fun `checkpoint loading is strict and inference rejects nonfinite input and arithmetic`() {
        val feature = LearnedOutcomeValueFeatureCompiler.compile(state(), "p0")
        val key = feature.values.maxBy { it.value }.key
        val valid = checkpoint(weights = mapOf(key to 0.25))
        val encoded = PolicyJson.format.encodeToString(valid)
        val loaded = LearnedOutcomeValueEvaluator.load(encoded)
        assertTrue(loaded.evaluate(feature).isFinite())

        val malformed = assertFailsWith<LearnedOutcomeValueException> {
            LearnedOutcomeValueEvaluator.load("{}")
        }
        assertEquals(
            LearnedOutcomeValueFailureKind.CHECKPOINT_PAYLOAD_INVALID,
            malformed.failure.kind,
        )

        val mismatched = assertFailsWith<LearnedOutcomeValueException> {
            LearnedOutcomeValueEvaluator.load(
                encoded.replace(
                    LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1,
                    "wrong-feature-schema",
                )
            )
        }
        assertEquals(
            LearnedOutcomeValueFailureKind.CHECKPOINT_PAYLOAD_INVALID,
            mismatched.failure.kind,
        )

        val nonfiniteCheckpoint = assertFailsWith<LearnedOutcomeValueException> {
            LearnedOutcomeValueEvaluator.load(encoded.replace("\"bias\":0.0", "\"bias\":1e309"))
        }
        assertEquals(
            LearnedOutcomeValueFailureKind.CHECKPOINT_PAYLOAD_INVALID,
            nonfiniteCheckpoint.failure.kind,
        )

        val nonfiniteInput = assertFailsWith<LearnedOutcomeValueException> {
            LearnedOutcomeValueFeatures(
                LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1,
                mapOf(key to Double.NaN),
            )
        }
        assertEquals(
            LearnedOutcomeValueFailureKind.INPUT_FEATURE_INVALID,
            nonfiniteInput.failure.kind,
        )

        val overflow = assertFailsWith<LearnedOutcomeValueException> {
            evaluator(weights = mapOf(key to Double.MAX_VALUE)).evaluate(feature)
        }
        assertEquals(LearnedOutcomeValueFailureKind.INFERENCE_NONFINITE, overflow.failure.kind)
    }

    @Test
    fun `checkpoint contract uses clipped linear ridge and train-only provenance`() {
        val feature = LearnedOutcomeValueFeatureCompiler.compile(state(), "p0")
        val key = feature.values.keys.first()
        val positive = evaluator(weights = mapOf(key to 100.0)).evaluateDetailed(feature)
        val negative = evaluator(weights = mapOf(key to -100.0)).evaluateDetailed(feature)

        assertEquals("sparse-linear-clipped-ridge-v1", LEARNED_OUTCOME_VALUE_MODEL_V1)
        assertTrue(positive.rawScore > 1.0)
        assertEquals(1.0, positive.deployedValue)
        assertTrue(negative.rawScore < -1.0)
        assertEquals(-1.0, negative.deployedValue)
        assertEquals(
            0.5,
            evaluator(weights = mapOf(key to 0.5 / feature.values.getValue(key))).evaluate(feature),
            1e-12,
        )

        val serialized = LearnedOutcomeValueEvaluator.encodeCanonicalCheckpoint(
            checkpoint(weights = mapOf(key to 0.25)),
        )
        assertTrue(!serialized.contains("validationSummaryIdentity"))
        assertTrue(!serialized.contains("trainingSeed"))
    }

    @Test
    fun `checkpoint identity binds model joint behavior training data split and projection`() {
        val features = LearnedOutcomeValueFeatureCompiler.compile(state(), "p0")
        val key = features.values.keys.first()
        val basePayload = checkpoint(weights = mapOf(key to 0.25))
        val base = LearnedOutcomeValueEvaluator.fromCheckpoint(basePayload)
        val reordered = LearnedOutcomeValueEvaluator.fromCheckpoint(
            basePayload.copy(weights = linkedMapOf(key to 0.25))
        )
        val changedWeight = LearnedOutcomeValueEvaluator.fromCheckpoint(
            basePayload.copy(weights = mapOf(key to 0.5))
        )
        val changedSplit = LearnedOutcomeValueEvaluator.fromCheckpoint(
            basePayload.copy(
                training = basePayload.training.copy(
                    pairSplitIdentity = identity("pair-split-b", 'b'),
                )
            )
        )
        val roundTripped = LearnedOutcomeValueEvaluator.load(base.canonicalCheckpointBytes())

        assertEquals(base.configurationId, reordered.configurationId)
        assertEquals(
            LearnedOutcomeValueEvaluator.encodeCanonicalCheckpoint(basePayload),
            base.canonicalCheckpointPayload,
        )
        assertEquals(base.canonicalCheckpointPayload, roundTripped.canonicalCheckpointPayload)
        assertEquals(base.configurationId, roundTripped.configurationId)
        assertEquals(
            PolicyJson.sha256(base.canonicalCheckpointPayload),
            base.checkpointIdentity.payloadSha256,
        )
        assertNotEquals(base.configurationId, changedWeight.configurationId)
        assertNotEquals(base.configurationId, changedSplit.configurationId)

        val parameters = SearchTeacherRuntimeConfig(leaf = learnedLeaf).policyParameters()
        val specification = parameters.behaviorSpecification(
            knownDecks = knownDecks(),
            opponentPolicy = defaultMonoRedOpponentPolicy(),
            informationEvaluator = base,
        )
        assertEquals("root-player-policy-information-v1", specification.evaluator.valueSource)
        assertEquals(base.configurationId, specification.evaluator.invokedEvaluatorConfigurationId)
        assertNotEquals(
            parameters.policyIdentity(
                knownDecks(),
                defaultMonoRedOpponentPolicy(),
                informationEvaluator = base,
            ),
            parameters.policyIdentity(
                knownDecks(),
                defaultMonoRedOpponentPolicy(),
                informationEvaluator = changedWeight,
            ),
        )
    }

    @Test
    fun `registry requires checkpoint backed current-information composition without fallback`() {
        val evaluator = evaluator()
        val strategy = SearchTeacherEvaluatorRegistry.strategy(learnedLeaf, evaluator)
        assertEquals(evaluator.configurationId, strategy.source.invokedEvaluatorConfigurationId)
        assertTrue(!strategy.supportsTraceReuse)
        assertTrue(!strategy.settleAtRolloutHorizon)

        assertFailsWith<IllegalArgumentException> {
            SearchTeacherEvaluatorRegistry.strategy(learnedLeaf)
        }
        assertFailsWith<IllegalArgumentException> {
            SearchTeacherEvaluatorRegistry.strategy(
                learnedLeaf.copy(stateSource = LeafStateSource.BOUNDED_ROLLOUT),
                evaluator,
            )
        }
    }

    @Test
    fun `checkpoint observer preserves inference authority and records the exact evaluated state`() {
        val evaluator = evaluator()
        val calls = mutableListOf<Triple<PolicyInformationState, String, Double>>()
        val observed = evaluator.observedBy { information, rootPlayer, value ->
            calls += Triple(information, rootPlayer, value)
        }
        val input = state()

        val strategy = SearchTeacherEvaluatorRegistry.strategy(learnedLeaf, observed)
        val value = observed.evaluate(input, "p0")

        assertEquals(evaluator.configurationId, strategy.source.invokedEvaluatorConfigurationId)
        assertEquals(evaluator.evaluate(input, "p0"), value)
        assertEquals(listOf(Triple(input, "p0", value)), calls)
    }

    @Test
    fun `detailed checkpoint observer records raw and deployed values from one inference authority`() {
        val features = LearnedOutcomeValueFeatureCompiler.compile(state(), "p0")
        val key = features.values.keys.first()
        val evaluator = evaluator(weights = mapOf(key to 100.0))
        val calls = mutableListOf<LearnedOutcomeValueEvaluation>()
        val observed = evaluator.observedEvaluationBy { _, _, evaluation -> calls += evaluation }

        val deployed = observed.evaluate(state(), "p0")

        assertEquals(1, calls.size)
        assertTrue(calls.single().rawScore > 1.0)
        assertEquals(1.0, calls.single().deployedValue)
        assertEquals(calls.single().deployedValue, deployed)
    }

    @Test
    fun `core terminal payoff bypasses model while nonterminal leaves invoke it`() {
        val evaluator = evaluator()
        val terminal = search(evaluator).search(
            rootPlayer = "p0",
            belief = belief(LearnedTestWorld(terminalAfterStep = true)),
            searchSeed = 11L,
        )
        val nonterminal = search(evaluator).search(
            rootPlayer = "p0",
            belief = belief(LearnedTestWorld(terminalAfterStep = false)),
            searchSeed = 12L,
        )

        assertEquals(1.0, terminal.rootValue)
        assertEquals(0, terminal.diagnostics.evaluatorCalls)
        assertTrue(terminal.candidateSettlementCounts.values.all {
            it.successfulBackups == 1 && it.terminalPayoffBackups == 1
        })
        assertEquals(1, nonterminal.diagnostics.evaluatorCalls)
        assertTrue(nonterminal.candidateSettlementCounts.values.all {
            it.successfulBackups == 1 && it.learnedOutcomeEstimateBackups == 1 &&
                it.heuristicSettlementBackups == 0
        })
    }

    @Test
    fun `leaf input failure aborts search instead of becoming a strategic value`() {
        val failure = assertFailsWith<LearnedOutcomeValueException> {
            search(evaluator()).search(
                rootPlayer = "p0",
                belief = belief(
                    LearnedTestWorld(
                        terminalAfterStep = false,
                        incompleteLeafTurnState = true,
                    )
                ),
                searchSeed = 13L,
            )
        }

        assertEquals(
            LearnedOutcomeValueFailureKind.INPUT_CURRENT_TURN_STATE_INCOMPLETE,
            failure.failure.kind,
        )
    }

    @Test
    fun `learned leaf failure becomes a typed policy stop without a private diagnostic`() {
        val policyStop = learnedOutcomeValuePolicyStop(
            LearnedOutcomeValueException(
                LearnedOutcomeValueFailure(
                    LearnedOutcomeValueFailureKind.INPUT_KNOWLEDGE_INCOMPLETE,
                    "private card identity must not reach evidence",
                )
            )
        )

        assertEquals(LearnedOutcomeValueFailureKind.INPUT_KNOWLEDGE_INCOMPLETE, policyStop.failureKind)
        assertTrue(policyStop.cause is LearnedOutcomeValueException)
        assertTrue(!policyStop.message.orEmpty().contains("private"))
    }

    private fun search(evaluator: LearnedOutcomeValueEvaluator) =
        SearchTeacherSearchFactory.create(
            config = InformationSetSearchConfig(simulations = 1, leaf = learnedLeaf),
            informationEvaluator = evaluator,
        )

    private fun evaluator(
        weights: Map<String, Double>? = null,
    ): LearnedOutcomeValueEvaluator {
        val defaultKey = LearnedOutcomeValueFeatureCompiler.compile(state(), "p0").values.keys.first()
        return LearnedOutcomeValueEvaluator.fromCheckpoint(
            checkpoint(weights = weights ?: mapOf(defaultKey to 0.1))
        )
    }

    private fun checkpoint(
        weights: Map<String, Double>,
    ): LearnedOutcomeValueCheckpointPayload = LearnedOutcomeValueCheckpointPayload(
        training = LearnedOutcomeValueTrainingBinding(
            corpusIdentity = identity("outcome-corpus", 'a'),
            pairSplitIdentity = identity("pair-split", 'b'),
            learnerConfigurationIdentity = identity("learner-configuration", 'c'),
            projectionIdentity = identity("safe-replay-projection", 'd'),
            rootBehaviorPolicyIdentity = identity("root-8x64-behavior", 'e'),
            opponentBehaviorPolicyIdentity = identity("opponent-8x32-behavior", 'f'),
            environmentProfileIdentity = identity("frozen-mono-red-environment", '1'),
        ),
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

    private fun stateWithHistory(history: PolicyHistoryEvent): PolicyInformationState = state().copy(
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

    private fun belief(world: SearchWorld): BeliefBatch<Weighted<SearchWorld>> = BeliefBatch(
        particles = listOf(Weighted(world, 1.0)),
        diagnostics = BeliefDiagnostics(
            mode = BeliefMode.CONSISTENCY_ONLY_V1,
            requestedParticles = 1,
            acceptedParticles = 1,
            rejectedParticles = 0,
            effectiveSampleSizeBefore = 1.0,
            effectiveSampleSizeAfter = 1.0,
            entropy = 0.0,
            resamplingCount = 0,
            architecture = BeliefArchitecture.SEQUENTIAL_B_V1,
        ),
    )

    private fun state(
        rootPlayer: String = "p0",
        opponentPlayer: String = "p1",
        actor: String = rootPlayer,
        excludedSalt: String = "stable",
        currentTurnStateComplete: Boolean = true,
        repeatedHistoryCount: Int = 1,
        repeatedPermanentCount: Int = 1,
    ): PolicyInformationState {
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
        val observation = PolicyObservation(
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
        return PolicyInformationState(
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

    private inner class LearnedTestWorld(
        private val terminalAfterStep: Boolean,
        private val incompleteLeafTurnState: Boolean = false,
        private var depth: Int = 0,
    ) : SearchWorld {
        override fun actorToAct(): String? = if (depth == 0) "p0" else "p1"

        override fun informationState(viewer: String): PolicyInformationState {
            require(viewer == "p0")
            return state(
                actor = actorToAct()!!,
                currentTurnStateComplete = !(depth > 0 && incompleteLeafTurnState),
            )
        }

        override fun expandChoices(): PolicyExpansion = if (depth == 0) {
            PolicyExpansion(
                candidates = listOf(choice("advance")),
                isExhaustive = true,
                estimatedCandidateCount = 1,
                proposalVersion = "learned-test-v1",
            )
        } else {
            PolicyExpansion(
                candidates = emptyList(),
                isExhaustive = true,
                estimatedCandidateCount = 0,
                proposalVersion = "learned-test-v1",
            )
        }

        override fun step(choice: SemanticChoice): SearchStepResult {
            depth++
            return SearchStepResult(accepted = true)
        }

        override fun fork(): SearchWorld = LearnedTestWorld(
            terminalAfterStep,
            incompleteLeafTurnState,
            depth,
        )

        override fun terminalPayoff(rootPlayer: String): Double? =
            1.0.takeIf { terminalAfterStep && depth > 0 }

        override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double =
            error("Learned outcome value must not inspect a sampled world")
    }
}
