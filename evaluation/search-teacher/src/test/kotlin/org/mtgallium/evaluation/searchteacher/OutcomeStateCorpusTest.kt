package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.infoset.argentum.ArgentumRawTransition
import com.wingedsheep.engine.core.AbilityResolvedEvent
import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionRequestedEvent
import com.wingedsheep.engine.core.DecisionSubmittedEvent
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.event.DelayedTriggeredAbility
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayHeader
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayJson
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayRecord
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayRecorder
import org.mtgallium.evaluation.searchteacher.replay.ReplayCompletionStatus
import org.mtgallium.evaluation.searchteacher.replay.ReplayTransitionOrigin
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.PlayerYields
import com.wingedsheep.engine.state.components.battlefield.LastKnownPermanentComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AbilityIdentity
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.effects.GainLifeEffect
import com.wingedsheep.sdk.scripting.references.Player
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.PolicyAudience
import org.mtgallium.agent.infoset.core.PolicyAudienceScope
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyInspectionBundle
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SearchStepResult
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatureCompiler
import org.mtgallium.research.run.ResearchSourceProvenance
import org.mtgallium.research.run.ResearchSourceTreeState

@org.junit.jupiter.api.Tag("public-source")
class OutcomeStateCorpusTest {
    @Test
    fun `pair split is outcome blind deterministic exact and keeps both legs together`() {
        val pairs = (50 until 150).toList()
        val first = OutcomeStateCorpusSplitBinding.create(
            pairs,
            OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            trainPairs = 70,
            validationPairs = 15,
            testPairs = 15,
        )
        val repeated = OutcomeStateCorpusSplitBinding.create(
            pairs,
            OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            trainPairs = 70,
            validationPairs = 15,
            testPairs = 15,
        )

        assertEquals(first, repeated)
        assertEquals(70, first.assignments.count { it.split == OutcomeStateCorpusSplit.TRAIN })
        assertEquals(15, first.assignments.count { it.split == OutcomeStateCorpusSplit.VALIDATION })
        assertEquals(15, first.assignments.count { it.split == OutcomeStateCorpusSplit.TEST })
        assertEquals(
            OutcomeStateCorpusSplit.TRAIN,
            first.splitFor(SEARCH_BUDGET_FRONTIER_EXTENSION_START),
            "the fixed preflight witness must remain inside TRAIN",
        )
        assertEquals(pairs, first.assignments.map { it.pairIndex })
        pairs.forEach { pairIndex ->
            val legA = first.splitFor(pairIndex)
            val legB = first.splitFor(pairIndex)
            assertEquals(legA, legB)
        }
    }

    @Test
    fun `verified canonical replay produces root-safe actor and opponent decision states`() {
        val directory = createTempDirectory("outcome-state-projector")
        val fixture = writeReplayFixture(directory.resolve("fixture.replay.jsonl.gz"))
        val projector = CanonicalOutcomeStateProjector(
            SemanticReplayWorldFactory { FixtureReplayWorld(fixture) }
        )

        val result = projector.project(
            fixture.path,
            fixture.expectation(),
            OutcomeStateInspectionIdentity(
                producerOuterCommit = PRODUCER_COMMIT,
                controlPolicyEvidenceIdentity = OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID,
            ),
        )
        val bundle = result.bundle

        assertEquals(UUID.fromString(bundle.gameId).toString(), bundle.gameId)
        assertNotEquals(fixture.historicalGameId, bundle.gameId)
        assertEquals(fixture.createdAtUtc, bundle.createdAtUtc)
        assertEquals(PRODUCER_COMMIT, bundle.outerCommit)
        assertEquals("p0", bundle.perspectivePlayerId)
        assertEquals(2, result.decisionBoundaryStates)
        assertEquals(2, result.semanticDecisions)
        assertEquals(2, result.rawTransitions)
        assertEquals(0, result.replayCompatibilityAudit.syntheticAbilityMappingCount)
        assertEquals(1, result.rootActorStates)
        assertEquals(1, result.opponentActorStates)
        assertEquals(listOf("p0", "p1"), bundle.frames.filterNot { it.terminated }.map { it.actingPlayerId })
        assertTrue(bundle.frames.none { it.search != null })
        assertTrue(bundle.frames.dropLast(1).all { it.winnerId == null })
        assertEquals("p0", bundle.frames.last().winnerId)
        assertEquals(1.0, result.actualTerminalPayoff)
        assertEquals(1.0, bundle.outcome.resultByPlayer.getValue("p0"))

        val hiddenTransition = requireNotNull(bundle.frames.last().transition)
        assertEquals("p1", hiddenTransition.actorId)
        assertTrue(hiddenTransition.privateResponse)
        assertEquals(null, hiddenTransition.observedChoice)
        assertEquals(1, result.privateOpponentResponses)
        assertTrue(bundle.informationState(1).candidates.isEmpty())
        val encoded = PolicyJson.format.encodeToString(bundle)
        assertFalse(fixture.choices[1].display.label in encoded)
        bundle.frames.indices.forEach { frameIndex ->
            val state = bundle.informationState(frameIndex)
            assertEquals("p0", state.observation.perspectivePlayerId)
            assertEquals("p0", state.knowledge.perspectivePlayerId)
            assertTrue(state.observation.currentTurnStateComplete)
            assertTrue(state.knowledge.epistemicallyComplete)
        }
    }

    @Test
    fun `shared outcome-value frame extraction preserves recorded root and compiler output`() {
        val split = OutcomeStateCorpusSplitBinding.create(
            (50 until 150).toList(),
            OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            trainPairs = 70,
            validationPairs = 15,
            testPairs = 15,
        )
        val game = testGameArtifacts(
            split,
            "outcome-state-training-projection-v4-sha256:${sha256("synthetic-projection")}",
        ).first { it.pairIndex == 50 && it.leg == "a" }
        val bundle = fixtureInspectionBundle()

        val root = outcomeValueCorpusFrame(game, 0, bundle.informationState(0))

        assertEquals("p0", root.rootPlayerId)
        assertEquals(OutcomeValueActorRelation.ROOT, root.example.actorRelation)
        assertEquals(
            LearnedOutcomeValueFeatureCompiler.compile(bundle.informationState(0), game.rootPlayerId).values,
            root.example.features.values,
        )
    }

    @Test
    fun `canonical authentication refuses a tampered replay before projection`() {
        val directory = createTempDirectory("outcome-state-tamper")
        val fixture = writeReplayFixture(directory.resolve("fixture.replay.jsonl.gz"))
        val records = readCanonicalReplay(fixture.path).toMutableList()
        records[0] = (records.first() as CanonicalReplayHeader).copy(initialStateDigest = "0".repeat(64))
        val tampered = directory.resolve("tampered.replay.jsonl.gz")
        writeReplayRecords(tampered, records)
        val expectation = fixture.expectation().copy(parentReplaySha256 = sha256File(tampered))

        assertFailsWith<IllegalArgumentException> {
            CanonicalOutcomeStateProjector(
                SemanticReplayWorldFactory { FixtureReplayWorld(fixture) }
            ).project(
                tampered,
                expectation,
                OutcomeStateInspectionIdentity(
                    PRODUCER_COMMIT,
                    OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID,
                ),
            )
        }
    }

    @Test
    fun `pair publication leaves no authoritative half pair after refusal`() {
        val parent = createTempDirectory("outcome-state-atomic")
        val final = parent.resolve("pair-50")

        assertFailsWith<IllegalStateException> {
            publishOutcomeStatePairAtomically(final) { pending ->
                Files.writeString(pending.resolve("leg-a"), "complete")
                error("leg b failed")
            }
        }

        assertFalse(Files.exists(final))
        assertTrue(Files.list(parent).use { entries -> entries.noneMatch { it.fileName.toString().startsWith(".pair-50.") } })
    }

    @Test
    fun `recorded replay action comparison normalizes only submit-decision routing nonce`() {
        val recorded = SubmitDecision(EntityId.of("p0"), YesNoResponse("recorded-id", choice = true))
        val reconstructed = SubmitDecision(EntityId.of("p0"), YesNoResponse("live-id", choice = true))

        assertTrue(recordedReplayActionEquals(recorded, reconstructed))
        assertFalse(
            recordedReplayActionEquals(
                recorded,
                reconstructed.copy(response = YesNoResponse("live-id", choice = false)),
            )
        )
        assertFalse(recordedReplayActionEquals(recorded, reconstructed.copy(playerId = EntityId.of("p1"))))
        assertFalse(
            recordedReplayActionEquals(
                PassPriority(EntityId.of("p0")),
                PassPriority(EntityId.of("p1")),
            )
        )
        assertTrue(
            recordedReplayActionEquals(
                PassPriority(EntityId.of("p0")),
                PassPriority(EntityId.of("p0")),
            )
        )
    }

    @Test
    fun `recorded replay event comparison normalizes only boundary-correlated decision routing nonces`() {
        val p0 = EntityId.of("p0")
        val p1 = EntityId.of("p1")
        val recordedId = "7efd2f98-eb9f-4b29-bb95-d078bcd76874"
        val reconstructedId = "2f7bfc2e-5022-43f5-beb2-842b51256b91"
        val base = GameState(turnOrder = listOf(p0, p1))
        fun pending(id: String) = YesNoDecision(
            id = id,
            playerId = p0,
            prompt = "Choose carefully",
            context = DecisionContext(),
        )
        val recordedBefore = base.withPendingDecision(pending(recordedId))
        val reconstructedBefore = base.withPendingDecision(pending(reconstructedId))
        val recordedSubmit = SubmitDecision(p0, YesNoResponse(recordedId, choice = true))
        val reconstructedSubmit = SubmitDecision(p0, YesNoResponse(reconstructedId, choice = true))
        fun submittedDifference(
            expected: List<com.wingedsheep.engine.core.GameEvent>,
            actual: List<com.wingedsheep.engine.core.GameEvent>,
        ) = recordedReplayEventDifference(
            expectedEvents = expected,
            actualEvents = actual,
            expectedAction = recordedSubmit,
            actualAction = reconstructedSubmit,
            expectedBefore = recordedBefore,
            actualBefore = reconstructedBefore,
            expectedAfter = base,
            actualAfter = base,
        )

        val recordedSubmitted = DecisionSubmittedEvent(recordedId, p0, description = "Chose Yes")
        val reconstructedSubmitted = DecisionSubmittedEvent(reconstructedId, p0, description = "Chose Yes")
        assertEquals(null, submittedDifference(listOf(recordedSubmitted), listOf(reconstructedSubmitted)))
        assertTrue(
            submittedDifference(
                listOf(recordedSubmitted),
                listOf(reconstructedSubmitted.copy(decisionId = "uncorrelated")),
            ) != null,
        )
        assertTrue(
            submittedDifference(
                listOf(recordedSubmitted.copy(decisionId = "uncorrelated")),
                listOf(reconstructedSubmitted),
            ) != null,
        )
        assertTrue(
            submittedDifference(
                listOf(recordedSubmitted.copy(decisionId = "shared-but-uncorrelated")),
                listOf(reconstructedSubmitted.copy(decisionId = "shared-but-uncorrelated")),
            ) != null,
        )
        assertTrue(
            submittedDifference(
                listOf(recordedSubmitted),
                listOf(reconstructedSubmitted.copy(playerId = p1)),
            ) != null,
        )
        assertTrue(
            submittedDifference(
                listOf(recordedSubmitted),
                listOf(reconstructedSubmitted.copy(description = "Chose No")),
            ) != null,
        )

        val ordinaryRecordedUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val ordinaryActualUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        assertTrue(
            submittedDifference(
                listOf(AbilityResolvedEvent(p0, ordinaryRecordedUuid)),
                listOf(AbilityResolvedEvent(p0, ordinaryActualUuid)),
            ) != null,
        )
        assertTrue(
            submittedDifference(
                listOf(recordedSubmitted, AbilityResolvedEvent(p0, "done")),
                listOf(AbilityResolvedEvent(p0, "done"), reconstructedSubmitted),
            ) != null,
        )
        assertTrue(submittedDifference(listOf(recordedSubmitted), emptyList()) != null)

        val pass = PassPriority(p0)
        val recordedAfter = base.withPendingDecision(pending(recordedId))
        val reconstructedAfter = base.withPendingDecision(pending(reconstructedId))
        val recordedRequested = DecisionRequestedEvent(recordedId, p0, "YES_NO", "Choose carefully")
        fun requestedDifference(
            actualEvent: DecisionRequestedEvent,
            expectedEvent: DecisionRequestedEvent = recordedRequested,
        ) = recordedReplayEventDifference(
            expectedEvents = listOf(expectedEvent),
            actualEvents = listOf(actualEvent),
            expectedAction = pass,
            actualAction = pass,
            expectedBefore = base,
            actualBefore = base,
            expectedAfter = recordedAfter,
            actualAfter = reconstructedAfter,
        )
        val reconstructedRequested =
            DecisionRequestedEvent(reconstructedId, p0, "YES_NO", "Choose carefully")
        assertEquals(null, requestedDifference(reconstructedRequested))
        assertTrue(requestedDifference(reconstructedRequested.copy(decisionId = "uncorrelated")) != null)
        assertTrue(
            requestedDifference(
                actualEvent = reconstructedRequested,
                expectedEvent = recordedRequested.copy(decisionId = "uncorrelated"),
            ) != null,
        )
        assertTrue(
            requestedDifference(
                actualEvent = reconstructedRequested.copy(decisionId = "shared-but-uncorrelated"),
                expectedEvent = recordedRequested.copy(decisionId = "shared-but-uncorrelated"),
            ) != null,
        )
        assertTrue(requestedDifference(reconstructedRequested.copy(playerId = p1)) != null)
        assertTrue(requestedDifference(reconstructedRequested.copy(decisionType = "SELECT_CARDS")) != null)
        assertTrue(requestedDifference(reconstructedRequested.copy(prompt = "A different choice")) != null)
    }

    @Test
    fun `recorded replay event comparison audits only fixed 3eda Time Lord type-line splitting`() {
        val fixture = legacyTimeLordFixture()
        val p0 = fixture.playerId
        val permanent = fixture.entityId
        val base = fixture.base
        val historicalTypeLine = requireNotNull(fixture.historicalSnapshot.typeLine)
        val reconstructedTypeLine = requireNotNull(fixture.reconstructedSnapshot.typeLine)
        assertEquals(historicalTypeLine.toString(), reconstructedTypeLine.toString())
        assertNotEquals(historicalTypeLine, reconstructedTypeLine)

        val reconstructed = fixture.reconstructedEvent
        val historical = fixture.historicalEvent
        val fixedArgentumRoundTrip = CanonicalReplayJson.decodeFromString(
            GameEvent.serializer(),
            CanonicalReplayJson.encodeToString(GameEvent.serializer(), reconstructed),
        ) as ZoneChangeEvent
        assertEquals(historical, fixedArgentumRoundTrip)
        fun compare(
            expected: ZoneChangeEvent,
            actual: ZoneChangeEvent,
        ): RecordedReplayEventComparison = recordedReplayEventComparison(
            expectedEvents = listOf(expected),
            actualEvents = listOf(actual),
            expectedAction = PassPriority(p0),
            actualAction = PassPriority(p0),
            expectedBefore = base,
            actualBefore = base,
            expectedAfter = base,
            actualAfter = base,
        )

        val admitted = compare(historical, reconstructed)
        assertEquals(null, admitted.difference)
        assertEquals(
            listOf(RecordedReplayLegacyTypeLineNormalization(0, permanent)),
            admitted.legacyTimeLordTypeLineNormalizations,
        )

        assertTrue(compare(reconstructed, historical).difference != null)
        assertTrue(
            compare(
                historical,
                reconstructed.copy(
                    lastKnown = requireNotNull(reconstructed.lastKnown).copy(power = 4),
                ),
            ).difference != null,
        )
        val arbitraryHistorical = historical.copy(
            lastKnown = requireNotNull(historical.lastKnown).copy(
                typeLine = TypeLine(
                    cardTypes = setOf(CardType.LAND, CardType.CREATURE),
                    subtypes = linkedSetOf(Subtype("Advisor"), Subtype("Alpha"), Subtype("Beta")),
                ),
            ),
        )
        val arbitraryReconstructed = reconstructed.copy(
            lastKnown = requireNotNull(reconstructed.lastKnown).copy(
                typeLine = TypeLine(
                    cardTypes = setOf(CardType.LAND, CardType.CREATURE),
                    subtypes = linkedSetOf(Subtype("Advisor"), Subtype("Alpha Beta")),
                ),
            ),
        )
        assertEquals(
            arbitraryHistorical.lastKnown?.typeLine.toString(),
            arbitraryReconstructed.lastKnown?.typeLine.toString(),
        )
        assertTrue(compare(arbitraryHistorical, arbitraryReconstructed).difference != null)

        val exact = compare(reconstructed, reconstructed)
        assertEquals(null, exact.difference)
        assertTrue(exact.legacyTimeLordTypeLineNormalizations.isEmpty())
    }

    @Test
    fun `recorded replay state comparison audits only event-correlated Time Lord last-known state`() {
        val fixture = legacyTimeLordFixture()
        val action = PassPriority(fixture.playerId)
        val eventComparison = recordedReplayEventComparison(
            expectedEvents = listOf(fixture.historicalEvent),
            actualEvents = listOf(fixture.reconstructedEvent),
            expectedAction = action,
            actualAction = action,
            expectedBefore = fixture.base,
            actualBefore = fixture.base,
            expectedAfter = fixture.historicalState,
            actualAfter = fixture.reconstructedState,
        )
        assertEquals(null, eventComparison.difference)

        val equivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        assertEquals(null, equivalence.initialDifference(fixture.base, fixture.base))
        assertEquals(
            null,
            equivalence.transitionDifference(
                expectedAction = action,
                actualAction = action,
                expectedEvents = listOf(fixture.historicalEvent),
                actualEvents = listOf(fixture.reconstructedEvent),
                expectedBefore = fixture.base,
                actualBefore = fixture.base,
                expectedAfter = fixture.historicalState,
                actualAfter = fixture.reconstructedState,
                expectedAccepted = true,
                actualAccepted = true,
                rawOrdinal = 0,
                legacyTimeLordTypeLineNormalizations =
                    eventComparison.legacyTimeLordTypeLineNormalizations,
            ),
        )
        assertEquals(
            null,
            equivalence.finalDifference(
                fixture.historicalState,
                fixture.reconstructedState,
                rawTransitionCount = 1,
            ),
        )
        assertEquals(null, equivalence.safeInspectionBundleDifference(fixtureInspectionBundle()))
        val audit = equivalence.completedAudit()
        val normalization = audit.legacyTimeLordTypeLineNormalizations.single()
        assertEquals(0, normalization.rawOrdinal)
        assertEquals(0, normalization.eventIndex)
        assertEquals(fixture.entityId.value, normalization.entityId)
        assertEquals(null, normalization.retirementRawOrdinal)
        assertTrue(normalization.presentAtFinal)
        assertEquals(1, audit.activeLegacyTimeLordTypeLineMappingsAtFinal)
        assertEquals(0, audit.activeMappingsAtFinal)

        val uncorrelated = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        val difference = uncorrelated.initialDifference(
            fixture.historicalState,
            fixture.reconstructedState,
        )
        assertTrue(difference != null)
        assertTrue(difference.reason.contains("outside a correlated normalized leave snapshot"))
    }

    @Test
    fun `fixed-root prefix reconstruction uses historical typed state equivalence`() {
        val fixture = legacyTimeLordFixture()
        val action = PassPriority(fixture.playerId)
        val eventComparison = recordedReplayEventComparison(
            expectedEvents = listOf(fixture.historicalEvent),
            actualEvents = listOf(fixture.reconstructedEvent),
            expectedAction = action,
            actualAction = action,
            expectedBefore = fixture.base,
            actualBefore = fixture.base,
            expectedAfter = fixture.historicalState,
            actualAfter = fixture.reconstructedState,
        )
        val equivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        assertEquals(null, equivalence.initialDifference(fixture.base, fixture.base))

        assertEquals(
            null,
            fixedRootReplayTransitionDifference(
                stateEquivalence = equivalence,
                expectedAction = action,
                actualAction = action,
                expectedEvents = listOf(fixture.historicalEvent),
                actualEvents = listOf(fixture.reconstructedEvent),
                expectedBefore = fixture.base,
                actualBefore = fixture.base,
                expectedAfter = fixture.historicalState,
                actualAfter = fixture.reconstructedState,
                expectedAccepted = true,
                actualAccepted = true,
                rawOrdinal = 0,
                eventComparison = eventComparison,
            ),
        )
    }

    @Test
    fun `recorded replay state comparison rejects Time Lord normalization on a rejected transition`() {
        val fixture = legacyTimeLordFixture()
        val action = PassPriority(fixture.playerId)
        val eventComparison = recordedReplayEventComparison(
            expectedEvents = listOf(fixture.historicalEvent),
            actualEvents = listOf(fixture.reconstructedEvent),
            expectedAction = action,
            actualAction = action,
            expectedBefore = fixture.base,
            actualBefore = fixture.base,
            expectedAfter = fixture.historicalState,
            actualAfter = fixture.reconstructedState,
        )
        assertEquals(null, eventComparison.difference)

        val equivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        assertEquals(null, equivalence.initialDifference(fixture.base, fixture.base))
        val difference = equivalence.transitionDifference(
            expectedAction = action,
            actualAction = action,
            expectedEvents = listOf(fixture.historicalEvent),
            actualEvents = listOf(fixture.reconstructedEvent),
            expectedBefore = fixture.base,
            actualBefore = fixture.base,
            expectedAfter = fixture.historicalState,
            actualAfter = fixture.reconstructedState,
            expectedAccepted = false,
            actualAccepted = false,
            rawOrdinal = 0,
            legacyTimeLordTypeLineNormalizations =
                eventComparison.legacyTimeLordTypeLineNormalizations,
        )
        assertTrue(difference != null)
        assertTrue(difference.reason.contains("does not identify its exact typed leave event"))
    }

    @Test
    fun `recorded replay state comparison requires the leave event to source the exact last-known state`() {
        val fixture = legacyTimeLordFixture()
        val action = PassPriority(fixture.playerId)
        val eventComparison = recordedReplayEventComparison(
            expectedEvents = listOf(fixture.historicalEvent),
            actualEvents = listOf(fixture.reconstructedEvent),
            expectedAction = action,
            actualAction = action,
            expectedBefore = fixture.base,
            actualBefore = fixture.base,
            expectedAfter = fixture.historicalState,
            actualAfter = fixture.reconstructedState,
        )
        val mismatchedSnapshot = fixture.reconstructedSnapshot.copy(power = 4)
        val mismatchedAfter = fixture.reconstructedState.copy(
            entities = mapOf(
                fixture.entityId to ComponentContainer.of(
                    LastKnownPermanentComponent(mismatchedSnapshot),
                )
            ),
        )

        val equivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        assertEquals(null, equivalence.initialDifference(fixture.base, fixture.base))
        val difference = equivalence.transitionDifference(
            expectedAction = action,
            actualAction = action,
            expectedEvents = listOf(fixture.historicalEvent),
            actualEvents = listOf(fixture.reconstructedEvent),
            expectedBefore = fixture.base,
            actualBefore = fixture.base,
            expectedAfter = fixture.historicalState,
            actualAfter = mismatchedAfter,
            expectedAccepted = true,
            actualAccepted = true,
            rawOrdinal = 0,
            legacyTimeLordTypeLineNormalizations =
                eventComparison.legacyTimeLordTypeLineNormalizations,
        )
        assertTrue(difference != null)
        assertTrue(difference.reason.contains("exact source of its last-known state snapshot"))
    }

    @Test
    fun `recorded replay state comparison tombstones a retired Time Lord normalization`() {
        val fixture = legacyTimeLordFixture()
        val action = PassPriority(fixture.playerId)
        val eventComparison = recordedReplayEventComparison(
            expectedEvents = listOf(fixture.historicalEvent),
            actualEvents = listOf(fixture.reconstructedEvent),
            expectedAction = action,
            actualAction = action,
            expectedBefore = fixture.base,
            actualBefore = fixture.base,
            expectedAfter = fixture.historicalState,
            actualAfter = fixture.reconstructedState,
        )
        val equivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        assertEquals(null, equivalence.initialDifference(fixture.base, fixture.base))
        assertEquals(
            null,
            equivalence.transitionDifference(
                expectedAction = action,
                actualAction = action,
                expectedEvents = listOf(fixture.historicalEvent),
                actualEvents = listOf(fixture.reconstructedEvent),
                expectedBefore = fixture.base,
                actualBefore = fixture.base,
                expectedAfter = fixture.historicalState,
                actualAfter = fixture.reconstructedState,
                expectedAccepted = true,
                actualAccepted = true,
                rawOrdinal = 0,
                legacyTimeLordTypeLineNormalizations =
                    eventComparison.legacyTimeLordTypeLineNormalizations,
            ),
        )

        val retired = fixture.base.copy(timestamp = 2)
        assertEquals(
            null,
            equivalence.transitionDifference(
                expectedAction = action,
                actualAction = action,
                expectedEvents = emptyList(),
                actualEvents = emptyList(),
                expectedBefore = fixture.historicalState,
                actualBefore = fixture.reconstructedState,
                expectedAfter = retired,
                actualAfter = retired,
                expectedAccepted = true,
                actualAccepted = true,
                rawOrdinal = 1,
            ),
        )

        val historicalReappearance = fixture.historicalState.copy(timestamp = 3)
        val reconstructedReappearance = fixture.reconstructedState.copy(timestamp = 3)
        val difference = equivalence.transitionDifference(
            expectedAction = action,
            actualAction = action,
            expectedEvents = listOf(fixture.historicalEvent),
            actualEvents = listOf(fixture.reconstructedEvent),
            expectedBefore = retired,
            actualBefore = retired,
            expectedAfter = historicalReappearance,
            actualAfter = reconstructedReappearance,
            expectedAccepted = true,
            actualAccepted = true,
            rawOrdinal = 2,
            legacyTimeLordTypeLineNormalizations =
                eventComparison.legacyTimeLordTypeLineNormalizations,
        )
        assertTrue(difference != null)
        assertTrue(difference.reason.contains("normalized more than once"))
    }

    @Test
    fun `recorded replay state comparison admits only a correlated delayed ability identity`() {
        val equivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        val creation = syntheticDelayedAbilityTransition()

        assertEquals(null, creation.difference(equivalence, rawOrdinal = 0))
        assertEquals(1, equivalence.admittedSyntheticAbilityMappings)

        val expectedStillStacked = creation.expectedAfter.copy(timestamp = creation.expectedAfter.timestamp + 1)
        val actualStillStacked = creation.actualAfter.copy(timestamp = creation.actualAfter.timestamp + 1)
        assertEquals(
            null,
            equivalence.transitionDifference(
                expectedAction = creation.action,
                actualAction = creation.action,
                expectedEvents = emptyList(),
                actualEvents = emptyList(),
                expectedBefore = creation.expectedAfter,
                actualBefore = creation.actualAfter,
                expectedAfter = expectedStillStacked,
                actualAfter = actualStillStacked,
                expectedAccepted = true,
                actualAccepted = true,
                rawOrdinal = 1,
            ),
        )

        val expectedRemoved = expectedStillStacked.withoutSyntheticStackAbility()
        val actualRemoved = actualStillStacked.withoutSyntheticStackAbility()
        assertEquals(
            null,
            equivalence.transitionDifference(
                expectedAction = creation.action,
                actualAction = creation.action,
                expectedEvents = emptyList(),
                actualEvents = emptyList(),
                expectedBefore = expectedStillStacked,
                actualBefore = actualStillStacked,
                expectedAfter = expectedRemoved,
                actualAfter = actualRemoved,
                expectedAccepted = true,
                actualAccepted = true,
                rawOrdinal = 2,
            ),
        )
        assertEquals(1, equivalence.admittedSyntheticAbilityMappings, "removed mappings remain tombstones")

        val expectedReuse = expectedRemoved.copy(
            pendingDecision = syntheticPendingDecision(SYNTHETIC_EXPECTED_ABILITY_ID),
        )
        val actualReuse = actualRemoved.copy(
            pendingDecision = syntheticPendingDecision(SYNTHETIC_ACTUAL_ABILITY_ID),
        )
        val reused = equivalence.transitionDifference(
            expectedAction = creation.action,
            actualAction = creation.action,
            expectedEvents = emptyList(),
            actualEvents = emptyList(),
            expectedBefore = expectedReuse,
            actualBefore = actualReuse,
            expectedAfter = expectedReuse,
            actualAfter = actualReuse,
            expectedAccepted = true,
            actualAccepted = true,
            rawOrdinal = 3,
        )
        assertTrue(reused != null)
        assertEquals("before", reused.boundary)
        assertEquals("/pendingDecision/prompt", reused.path)
    }

    @Test
    fun `recorded replay state comparison rejects printed or uncorrelated ability id differences`() {
        val printed = syntheticDelayedAbilityTransition(
            expectedBefore = syntheticState(delayedId = null, stackAbilityId = null),
            actualBefore = syntheticState(delayedId = null, stackAbilityId = null),
            expectedAbilityId = "ability_printed_expected",
            actualAbilityId = "ability_printed_actual",
        )
        val printedDifference = printed.difference(
            RecordedReplayStateEquivalence(historicalProjectionAuthority()),
            rawOrdinal = 0,
        )
        assertTrue(printedDifference != null)
        assertTrue(printedDifference.reason.contains("not uniquely correlated"))

        val creation = syntheticDelayedAbilityTransition()
        val promptEquivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        assertEquals(null, creation.difference(promptEquivalence, rawOrdinal = 0))
        val expectedPrompt = creation.expectedAfter.copy(
            pendingDecision = syntheticPendingDecision(SYNTHETIC_EXPECTED_ABILITY_ID),
        )
        val actualPrompt = creation.actualAfter.copy(
            pendingDecision = syntheticPendingDecision(SYNTHETIC_ACTUAL_ABILITY_ID),
        )
        val promptDifference = promptEquivalence.transitionDifference(
            expectedAction = creation.action,
            actualAction = creation.action,
            expectedEvents = emptyList(),
            actualEvents = emptyList(),
            expectedBefore = creation.expectedAfter,
            actualBefore = creation.actualAfter,
            expectedAfter = expectedPrompt,
            actualAfter = actualPrompt,
            expectedAccepted = true,
            actualAccepted = true,
            rawOrdinal = 1,
        )
        assertTrue(promptDifference != null)
        assertEquals("/pendingDecision/prompt", promptDifference.path)

        val yieldEquivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        assertEquals(null, creation.difference(yieldEquivalence, rawOrdinal = 0))
        val expectedYield = creation.expectedAfter.withSyntheticYield(SYNTHETIC_EXPECTED_ABILITY_ID)
        val actualYield = creation.actualAfter.withSyntheticYield(SYNTHETIC_ACTUAL_ABILITY_ID)
        val yieldDifference = yieldEquivalence.transitionDifference(
            expectedAction = creation.action,
            actualAction = creation.action,
            expectedEvents = emptyList(),
            actualEvents = emptyList(),
            expectedBefore = creation.expectedAfter,
            actualBefore = creation.actualAfter,
            expectedAfter = expectedYield,
            actualAfter = actualYield,
            expectedAccepted = true,
            actualAccepted = true,
            rawOrdinal = 1,
        )
        assertTrue(yieldDifference != null)
        assertTrue(yieldDifference.path.contains("yieldsByPlayer"))

        val eventEquivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        val eventDifference = creation.copy(
            expectedEvents = creation.expectedEvents +
                AbilityResolvedEvent(SYNTHETIC_SOURCE_ID, SYNTHETIC_EXPECTED_ABILITY_ID),
            actualEvents = creation.actualEvents +
                AbilityResolvedEvent(SYNTHETIC_SOURCE_ID, SYNTHETIC_ACTUAL_ABILITY_ID),
        ).difference(eventEquivalence, rawOrdinal = 0)
        assertTrue(eventDifference != null)
        assertEquals("transition", eventDifference.boundary)
        assertTrue(eventDifference.reason.contains("action or event"))
    }

    @Test
    fun `recorded replay state comparison preserves admitted identity and fixed authority`() {
        val creation = syntheticDelayedAbilityTransition()
        val equivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        assertEquals(null, creation.difference(equivalence, rawOrdinal = 0))

        val sharedThirdId = "ability_shared_third"
        val expectedChanged = creation.expectedAfter.withSyntheticStackAbility(sharedThirdId)
        val actualChanged = creation.actualAfter.withSyntheticStackAbility(sharedThirdId)
        val changed = equivalence.transitionDifference(
            expectedAction = creation.action,
            actualAction = creation.action,
            expectedEvents = emptyList(),
            actualEvents = emptyList(),
            expectedBefore = expectedChanged,
            actualBefore = actualChanged,
            expectedAfter = expectedChanged,
            actualAfter = actualChanged,
            expectedAccepted = true,
            actualAccepted = true,
            rawOrdinal = 1,
        )
        assertTrue(changed != null)
        assertEquals("before", changed.boundary)
        assertEquals(SYNTHETIC_ABILITY_STATE_PATH, changed.path)

        assertFailsWith<IllegalArgumentException> {
            RecordedReplayStateEquivalence(
                historicalProjectionAuthority().copy(outerCommit = "f".repeat(40))
            )
        }

        val activeAtFinal = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        assertEquals(null, creation.difference(activeAtFinal, rawOrdinal = 0))
        val finalDifference = activeAtFinal.finalDifference(
            creation.expectedAfter,
            creation.actualAfter,
            rawTransitionCount = 1,
        )
        assertTrue(finalDifference != null)
        assertTrue(finalDifference.reason.contains("remains active"))
    }

    @Test
    fun `recorded replay state mapping requires accepted pass and fresh prefix ids`() {
        val creation = syntheticDelayedAbilityTransition()
        val rejected = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        val rejectedDifference = creation.difference(
            rejected,
            rawOrdinal = 0,
            expectedAccepted = false,
            actualAccepted = false,
        )
        assertTrue(rejectedDifference != null)
        assertEquals(0, rejected.admittedSyntheticAbilityMappings)

        val submitted = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        val submit = SubmitDecision(
            SYNTHETIC_ACTOR_ID,
            YesNoResponse("synthetic-decision-id", choice = true),
        )
        val submittedDifference = creation.difference(
            submitted,
            rawOrdinal = 0,
            expectedAction = submit,
            actualAction = submit,
        )
        assertTrue(submittedDifference != null)
        assertEquals(0, submitted.admittedSyntheticAbilityMappings)

        val initialPrefix = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        val initialWithFutureId = syntheticState(null, null).copy(
            pendingDecision = syntheticPendingDecision(SYNTHETIC_EXPECTED_ABILITY_ID),
        )
        assertEquals(null, initialPrefix.initialDifference(initialWithFutureId, initialWithFutureId))
        val initialPrefixDifference = creation.difference(initialPrefix, rawOrdinal = 0)
        assertTrue(initialPrefixDifference != null)
        assertTrue(initialPrefixDifference.reason.contains("appeared before"))

        val eventPrefix = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        val emptyState = syntheticState(null, null)
        val priorEvent = AbilityResolvedEvent(SYNTHETIC_SOURCE_ID, SYNTHETIC_ACTUAL_ABILITY_ID)
        assertEquals(
            null,
            eventPrefix.transitionDifference(
                expectedAction = creation.action,
                actualAction = creation.action,
                expectedEvents = listOf(priorEvent),
                actualEvents = listOf(priorEvent),
                expectedBefore = emptyState,
                actualBefore = emptyState,
                expectedAfter = emptyState,
                actualAfter = emptyState,
                expectedAccepted = true,
                actualAccepted = true,
                rawOrdinal = 0,
            ),
        )
        val eventPrefixDifference = creation.difference(eventPrefix, rawOrdinal = 1)
        assertTrue(eventPrefixDifference != null)
        assertTrue(eventPrefixDifference.reason.contains("appeared before"))
    }

    @Test
    fun `recorded replay state audit retains sequential mapping lifecycles without ids`() {
        val equivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        val first = syntheticDelayedAbilityTransition()
        assertEquals(null, first.difference(equivalence, rawOrdinal = 0))
        val expectedBetween = first.expectedAfter.withoutSyntheticStackAbility().copy(
            delayedTriggers = listOf(syntheticDelayedTrigger("second-delayed-expected")),
        )
        val actualBetween = first.actualAfter.withoutSyntheticStackAbility().copy(
            delayedTriggers = listOf(syntheticDelayedTrigger("second-delayed-actual")),
        )
        assertEquals(
            null,
            equivalence.transitionDifference(
                expectedAction = first.action,
                actualAction = first.action,
                expectedEvents = emptyList(),
                actualEvents = emptyList(),
                expectedBefore = first.expectedAfter,
                actualBefore = first.actualAfter,
                expectedAfter = expectedBetween,
                actualAfter = actualBetween,
                expectedAccepted = true,
                actualAccepted = true,
                rawOrdinal = 1,
            ),
        )

        val second = syntheticDelayedAbilityTransition(
            expectedBefore = expectedBetween,
            actualBefore = actualBetween,
            expectedAbilityId = "ability_second_expected",
            actualAbilityId = "ability_second_actual",
            stackEntityId = SYNTHETIC_SECOND_STACK_ID,
        )
        assertEquals(null, second.difference(equivalence, rawOrdinal = 2))
        val expectedFinal = second.expectedAfter.copy(
            entities = second.expectedAfter.entities - SYNTHETIC_SECOND_STACK_ID,
            stack = emptyList(),
            timestamp = second.expectedAfter.timestamp + 1,
        )
        val actualFinal = second.actualAfter.copy(
            entities = second.actualAfter.entities - SYNTHETIC_SECOND_STACK_ID,
            stack = emptyList(),
            timestamp = second.actualAfter.timestamp + 1,
        )
        assertEquals(
            null,
            equivalence.transitionDifference(
                expectedAction = second.action,
                actualAction = second.action,
                expectedEvents = emptyList(),
                actualEvents = emptyList(),
                expectedBefore = second.expectedAfter,
                actualBefore = second.actualAfter,
                expectedAfter = expectedFinal,
                actualAfter = actualFinal,
                expectedAccepted = true,
                actualAccepted = true,
                rawOrdinal = 3,
            ),
        )
        assertEquals(null, equivalence.finalDifference(expectedFinal, actualFinal, rawTransitionCount = 4))
        assertEquals(null, equivalence.safeInspectionBundleDifference(fixtureInspectionBundle()))
        val audit = equivalence.completedAudit()
        assertEquals(2, audit.syntheticAbilityMappingCount)
        assertEquals(listOf(0, 2), audit.syntheticAbilityMappings.map { it.creationRawOrdinal })
        assertEquals(listOf(1, 3), audit.syntheticAbilityMappings.map { it.retirementRawOrdinal })
        assertEquals(
            listOf(SYNTHETIC_STACK_ID.value, SYNTHETIC_SECOND_STACK_ID.value),
            audit.syntheticAbilityMappings.map { it.stackEntityId },
        )
        assertEquals(0, audit.activeMappingsAtFinal)
        assertEquals(0, audit.forbiddenOccurrenceCount)
        assertEquals(0, audit.legacyTimeLordTypeLineNormalizationCount)
        val encoded = evidenceJson.encodeToString(OutcomeStateReplayCompatibilityAudit.serializer(), audit)
        assertFalse(encoded.contains(SYNTHETIC_EXPECTED_ABILITY_ID))
        assertFalse(encoded.contains(SYNTHETIC_ACTUAL_ABILITY_ID))
        assertFalse(encoded.contains("ability_second_expected"))
        assertFalse(encoded.contains("ability_second_actual"))
        assertFalse(encoded.contains("e-time-lord"))
    }

    @Test
    fun `recorded replay state audit rejects privileged ids in completed safe bundle`() {
        val creation = syntheticDelayedAbilityTransition()
        val equivalence = RecordedReplayStateEquivalence(historicalProjectionAuthority())
        assertEquals(null, creation.difference(equivalence, rawOrdinal = 0))
        val expectedFinal = creation.expectedAfter.withoutSyntheticStackAbility()
        val actualFinal = creation.actualAfter.withoutSyntheticStackAbility()
        assertEquals(
            null,
            equivalence.transitionDifference(
                expectedAction = creation.action,
                actualAction = creation.action,
                expectedEvents = emptyList(),
                actualEvents = emptyList(),
                expectedBefore = creation.expectedAfter,
                actualBefore = creation.actualAfter,
                expectedAfter = expectedFinal,
                actualAfter = actualFinal,
                expectedAccepted = true,
                actualAccepted = true,
                rawOrdinal = 1,
            ),
        )
        assertEquals(null, equivalence.finalDifference(expectedFinal, actualFinal, rawTransitionCount = 2))

        val leakedBundle = fixtureInspectionBundle().copy(policyVersion = SYNTHETIC_EXPECTED_ABILITY_ID)
        val difference = equivalence.safeInspectionBundleDifference(leakedBundle)
        assertTrue(difference != null)
        assertEquals("safe-inspection-bundle", difference.boundary)
        assertEquals("/policyVersion", difference.path)
        assertTrue(difference.reason.contains("derived safe inspection bundle"))
        assertFailsWith<IllegalArgumentException> { equivalence.completedAudit() }
    }

    @Test
    fun `retained parity rejects another corpus before decoding frames or loading checkpoint`() {
        val split = OutcomeStateCorpusSplitBinding.create(
            (50 until 150).toList(),
            OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            trainPairs = 70,
            validationPairs = 15,
            testPairs = 15,
        )
        val inventory = testInventory()
        val producer = testProducerAuthority(PRODUCER_COMMIT)
        val historical = testHistoricalAuthority()
        val trainingProjection = OutcomeStateTrainingProjectionAuthority(
            historicalProjection = historical.projection,
            compatibility = testCompatibility(),
            currentProducer = producer,
        )
        val manifest = OutcomeStateCorpusManifest(
            researchRunIdentity = "research-run-v1-sha256:${sha256("derived-run")}",
            generatedAtUtc = "2026-09-03T01:00:00Z",
            historical = historical,
            producer = producer,
            trainingProjection = trainingProjection,
            inputInventory = inventory,
            inputInventorySha256 = sha256(evidenceJson.encodeToString(inventory)),
            deckHash = sha256("deck"),
            cardPoolHash = sha256("pool"),
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            split = split,
            splitBindingSha256 = split.bindingSha256(),
            games = testGameArtifacts(split, trainingProjection.identity()),
        )
        val bindings = outcomeStateCorpusBindings(
            historical = manifest.historical, producer = manifest.producer,
            trainingProjection = manifest.trainingProjection,
            inputInventorySha256 = manifest.inputInventorySha256,
            deckHash = manifest.deckHash, cardPoolHash = manifest.cardPoolHash,
            splitBindingSha256 = manifest.splitBindingSha256,
        )
        val other = manifest.copy(researchRunIdentity = bindings.identity)
        val directory = createTempDirectory("substituted-parity-corpus")
        Files.writeString(directory.resolve("corpus.json"), evidenceJson.encodeToString(other))
        org.mtgallium.research.run.ResearchRunArtifacts(directory, bindings.identity).also {
            it.register("corpus.json")
            it.finalize()
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            RetainedLearnedOutcomeValueParityAudit.run(directory, directory.resolve("absent-training"))
        }
        assertTrue(failure.message.orEmpty().contains("substituted corpus identity"))
    }

    @Test
    fun `corpus manifest keeps historical behavior and current producer authority distinct`() {
        val split = OutcomeStateCorpusSplitBinding.create(
            (50 until 150).toList(),
            OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            trainPairs = 70,
            validationPairs = 15,
            testPairs = 15,
        )
        val inventory = testInventory()
        val producer = testProducerAuthority(PRODUCER_COMMIT)
        val historical = testHistoricalAuthority()
        val trainingProjection = OutcomeStateTrainingProjectionAuthority(
            historicalProjection = historical.projection,
            compatibility = testCompatibility(),
            currentProducer = producer,
        )
        val manifest = OutcomeStateCorpusManifest(
            researchRunIdentity = "research-run-v1-sha256:${sha256("derived-run")}",
            generatedAtUtc = "2026-09-03T01:00:00Z",
            historical = historical,
            producer = producer,
            trainingProjection = trainingProjection,
            inputInventory = inventory,
            inputInventorySha256 = sha256(evidenceJson.encodeToString(inventory)),
            deckHash = sha256("deck"),
            cardPoolHash = sha256("pool"),
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            split = split,
            splitBindingSha256 = split.bindingSha256(),
            games = testGameArtifacts(split, trainingProjection.identity()),
        )

        assertEquals(OUTCOME_STATE_CORPUS_HISTORICAL_OUTER_COMMIT, manifest.historical.projection.outerCommit)
        assertEquals(PRODUCER_COMMIT, manifest.producer.projection.outerCommit)
        assertNotEquals(manifest.historical.projection.outerCommit, manifest.producer.projection.outerCommit)
        assertEquals(
            manifest.historical.projection.infosetCoreTree,
            manifest.producer.projection.infosetCoreTree,
        )
        assertNotEquals(
            manifest.historical.projection.infosetArgentumTree,
            manifest.producer.projection.infosetArgentumTree,
        )
        assertEquals(OUTCOME_STATE_CORPUS_STATE_EQUIVALENCE, manifest.trainingProjection.compatibility.stateEquivalenceAlgorithm)
        assertEquals(
            OUTCOME_STATE_CORPUS_ACTION_EQUIVALENCE,
            manifest.trainingProjection.compatibility.replayActionEquivalenceAlgorithm,
        )
        assertEquals(
            OUTCOME_STATE_CORPUS_EVENT_EQUIVALENCE,
            manifest.trainingProjection.compatibility.replayEventEquivalenceAlgorithm,
        )
        assertEquals(
            OUTCOME_STATE_CORPUS_TRANSITION_STATE_EQUIVALENCE,
            manifest.trainingProjection.compatibility.replayTransitionStateEquivalenceAlgorithm,
        )
        assertEquals(4, manifest.trainingProjection.compatibility.schemaVersion)
        assertEquals(4, manifest.trainingProjection.schemaVersion)
        assertTrue(manifest.trainingProjection.identity().startsWith("outcome-state-training-projection-v4-sha256:"))
        val learnedBinding = learnedOutcomeValueTrainingBinding(manifest)
        assertEquals(manifest.trainingProjection.identity(), learnedBinding.projectionIdentity)
        val alteredProducer = testProducerAuthority("c".repeat(40))
        val alteredProjection = trainingProjection.copy(currentProducer = alteredProducer)
        val alteredManifest = manifest.copy(
            researchRunIdentity = "research-run-v1-sha256:${sha256("altered-producer-run")}",
            producer = alteredProducer,
            trainingProjection = alteredProjection,
            games = manifest.games.map {
                it.copy(trainingProjectionIdentity = alteredProjection.identity())
            },
        )
        assertNotEquals(
            learnedBinding.projectionIdentity,
            learnedOutcomeValueTrainingBinding(alteredManifest).projectionIdentity,
        )
        assertEquals(manifest.games.size, manifest.replayCompatibilityAudit.gameCount)
        assertEquals(0, manifest.replayCompatibilityAudit.syntheticAbilityMappingCount)
        assertEquals(0, manifest.replayCompatibilityAudit.legacyTimeLordTypeLineNormalizationCount)
        assertEquals(0, manifest.replayCompatibilityAudit.activeMappingsAtFinal)
        assertEquals(0, manifest.replayCompatibilityAudit.forbiddenOccurrenceCount)
        val auditedGames = manifest.games.toMutableList().also { games ->
            games[0] = games[0].copy(
                replayCompatibilityAudit = OutcomeStateReplayCompatibilityAudit(
                    syntheticAbilityMappings = emptyList(),
                    legacyTimeLordTypeLineNormalizations = listOf(
                        OutcomeStateLegacyTypeLineNormalizationAudit(
                            rawOrdinal = 0,
                            eventIndex = 1,
                            entityId = "e-time-lord",
                        )
                    ),
                )
            )
        }
        val aggregateAudit = OutcomeStateCorpusReplayCompatibilityAudit.from(auditedGames)
        assertEquals(1, aggregateAudit.gamesWithLegacyTimeLordTypeLineNormalizations)
        assertEquals(1, aggregateAudit.legacyTimeLordTypeLineNormalizationCount)
        assertEquals(1, aggregateAudit.activeLegacyTimeLordTypeLineMappingsAtFinal)
        assertFailsWith<IllegalArgumentException> {
            OutcomeStateReplayCompatibilityAudit(
                syntheticAbilityMappings = emptyList(),
                legacyTimeLordTypeLineNormalizations = listOf(
                    OutcomeStateLegacyTypeLineNormalizationAudit(
                        rawOrdinal = 0,
                        eventIndex = 0,
                        entityId = "e-time-lord",
                        retirementRawOrdinal = 1,
                    ),
                    OutcomeStateLegacyTypeLineNormalizationAudit(
                        rawOrdinal = 2,
                        eventIndex = 0,
                        entityId = "e-time-lord",
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OutcomeStateReplayCompatibilityAudit(
                syntheticAbilityMappings = emptyList(),
                legacyTimeLordTypeLineNormalizations = listOf(
                    OutcomeStateLegacyTypeLineNormalizationAudit(
                        rawOrdinal = 0,
                        eventIndex = 0,
                        entityId = "e-time-lord-a",
                    ),
                    OutcomeStateLegacyTypeLineNormalizationAudit(
                        rawOrdinal = 0,
                        eventIndex = 0,
                        entityId = "e-time-lord-b",
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            manifest.copy(
                producer = producer.copy(
                    projection = producer.projection.copy(infosetArgentumTree = "f".repeat(40))
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            testCompatibility().copy(
                runtimeSourceDelta = testCompatibility().runtimeSourceDelta.copy(
                    path = "agent/infoset-argentum/src/main/kotlin/UnexpectedVerifier.kt",
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            testCompatibility().copy(replayEventEquivalenceAlgorithm = "unbound-event-equivalence")
        }
        assertFailsWith<IllegalArgumentException> {
            testCompatibility().copy(
                replayTransitionStateEquivalenceAlgorithm = "unbound-transition-state-equivalence"
            )
        }
    }

    private fun testInventory(): List<OutcomeStateInputPairInventory> = (50 until 150).map { pairIndex ->
        OutcomeStateInputPairInventory(
            pairIndex = pairIndex,
            pairSeed = pairIndex.toLong(),
            checkpointPayloadSha256 = sha256("checkpoint:$pairIndex"),
            games = listOf("a", "b").map { leg ->
                OutcomeStateInputGameInventory(
                    historicalGameId = "historical-$pairIndex-$leg",
                    p0PolicyId = if (leg == "a") {
                        SEARCH_BUDGET_FRONTIER_CONTROL_ID
                    } else {
                        SEARCH_BUDGET_FRONTIER_TREATMENT_ID
                    },
                    p1PolicyId = if (leg == "a") {
                        SEARCH_BUDGET_FRONTIER_TREATMENT_ID
                    } else {
                        SEARCH_BUDGET_FRONTIER_CONTROL_ID
                    },
                    replaySha256 = sha256("replay:$pairIndex:$leg"),
                )
            },
        )
    }

    private fun testGameArtifacts(
        split: OutcomeStateCorpusSplitBinding,
        trainingProjectionIdentity: String,
    ): List<OutcomeStateGameArtifact> =
        (50 until 150).flatMap { pairIndex ->
            listOf("a", "b").map { leg ->
                val root = if (leg == "a") "p0" else "p1"
                val replaySha = sha256("replay:$pairIndex:$leg")
                OutcomeStateGameArtifact(
                    pairIndex = pairIndex,
                    leg = leg,
                    split = split.splitFor(pairIndex),
                    historicalGameId = "historical-$pairIndex-$leg",
                    historicalCreatedAtUtc = "2026-09-02T00:00:00Z",
                    historicalGameSeed = pairIndex.toLong(),
                    historicalSearchBaseSeed = pairIndex.toLong() + 1,
                    historicalP0PolicyId = if (leg == "a") {
                        SEARCH_BUDGET_FRONTIER_CONTROL_ID
                    } else {
                        SEARCH_BUDGET_FRONTIER_TREATMENT_ID
                    },
                    historicalP1PolicyId = if (leg == "a") {
                        SEARCH_BUDGET_FRONTIER_TREATMENT_ID
                    } else {
                        SEARCH_BUDGET_FRONTIER_CONTROL_ID
                    },
                    rootPlayerId = root,
                    rootPolicyEvidenceIdentity = OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID,
                    trainingProjectionIdentity = trainingProjectionIdentity,
                    parentReplayReference = "replays/historical-$pairIndex-$leg.replay.jsonl.gz",
                    parentReplaySha256 = replaySha,
                    parentReplayTerminalRecordDigest = sha256("terminal:$pairIndex:$leg"),
                    derivedBundleId = outcomeStateBundleId(replaySha, root),
                    bundleReference = "pairs/pair-$pairIndex/bundles/$pairIndex-$leg.inspection.json.gz",
                    bundleSha256 = sha256("bundle:$pairIndex:$leg"),
                    bundleBytes = 1,
                    semanticDecisions = 2,
                    rawTransitions = 2,
                    replayCompatibilityAudit = OutcomeStateReplayCompatibilityAudit(
                        syntheticAbilityMappings = emptyList(),
                    ),
                    decisionBoundaryStates = 2,
                    rootActorStates = 1,
                    opponentActorStates = 1,
                    privateOpponentResponses = 0,
                    actualTerminalPayoff = 1.0,
                    winnerId = root,
                )
            }
        }

    private fun testHistoricalAuthority() = OutcomeStateHistoricalAuthority(
        parentRunIdentity = OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
        parentArtifactManifestSha256 = sha256("parent-manifest"),
        projection = historicalProjectionAuthority(),
        controlPolicyId = SEARCH_BUDGET_FRONTIER_CONTROL_ID,
        treatmentPolicyId = SEARCH_BUDGET_FRONTIER_TREATMENT_ID,
        controlPolicyEvidenceIdentity = OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID,
        treatmentPolicyEvidenceIdentity = OUTCOME_STATE_CORPUS_TREATMENT_POLICY_EVIDENCE_ID,
    )

    private fun testProducerAuthority(commit: String): OutcomeStateProducerAuthority {
        val zero = sha256("")
        val projection = OutcomeStateProjectionAuthority(
            outerCommit = commit,
            infosetCoreTree = OUTCOME_STATE_CORPUS_INFOSET_CORE_TREE,
            infosetArgentumTree = testCompatibility().producerInfosetArgentumTree,
            argentumCommit = OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
        )
        return OutcomeStateProducerAuthority(
            projection = projection,
            sourceProvenance = ResearchSourceProvenance(
                expectedArgentumRevision = OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
                outer = ResearchSourceTreeState(commit, zero, zero, zero),
                argentum = ResearchSourceTreeState(
                    OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
                    zero,
                    zero,
                    zero,
                ),
            ),
        )
    }

    private fun testCompatibility() = OutcomeStateProjectionCompatibility(
        historicalInfosetCoreTree = OUTCOME_STATE_CORPUS_INFOSET_CORE_TREE,
        producerInfosetCoreTree = OUTCOME_STATE_CORPUS_INFOSET_CORE_TREE,
        historicalInfosetArgentumTree = OUTCOME_STATE_CORPUS_INFOSET_ARGENTUM_TREE,
        producerInfosetArgentumTree = "6f83ec7b624ed3293221e00f4deded895c30421b",
        runtimeSourceDelta = OutcomeStateSourceBlobDelta(
            path = "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumStateFingerprint.kt",
            historicalBlob = "3ca7cf71c448a0a2048cd5747c4f60db30575a5b",
            producerBlob = "27f58d44f3b45e16d64c79bf8387318b607c3cf8",
        ),
        nonRuntimeTestSourceDeltas = listOf(
            OutcomeStateSourceBlobDelta(
                path = "agent/infoset-argentum/src/test/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumStateFingerprintTest.kt",
                historicalBlob = "fda623d67d6bc12628f91b5b6058011ef5a79609",
                producerBlob = "4cc2f260272fcdd30cf244bf1023bf9ef1827077",
            )
        ),
    )

    private data class LegacyTimeLordFixture(
        val playerId: EntityId,
        val entityId: EntityId,
        val base: GameState,
        val historicalSnapshot: EntitySnapshot,
        val reconstructedSnapshot: EntitySnapshot,
        val historicalEvent: ZoneChangeEvent,
        val reconstructedEvent: ZoneChangeEvent,
        val historicalState: GameState,
        val reconstructedState: GameState,
    )

    private fun legacyTimeLordFixture(): LegacyTimeLordFixture {
        val playerId = EntityId.of("p0")
        val entityId = EntityId.of("e-card")
        val base = GameState(turnOrder = listOf(playerId, EntityId.of("p1")))
        val historicalTypeLine = TypeLine(
            cardTypes = setOf(CardType.LAND, CardType.CREATURE),
            subtypes = linkedSetOf(
                Subtype("Advisor"),
                Subtype("Time"),
                Subtype("Lord"),
                Subtype("Toy"),
            ),
        )
        val reconstructedTypeLine = TypeLine(
            cardTypes = setOf(CardType.LAND, CardType.CREATURE),
            subtypes = linkedSetOf(
                Subtype("Advisor"),
                Subtype("Time Lord"),
                Subtype("Toy"),
            ),
        )
        val reconstructedSnapshot = EntitySnapshot(
            entityId = entityId,
            power = 3,
            toughness = 3,
            subtypes = setOf("Advisor", "Time Lord", "Toy"),
            typeLine = reconstructedTypeLine,
        )
        val historicalSnapshot = reconstructedSnapshot.copy(typeLine = historicalTypeLine)
        val reconstructedEvent = ZoneChangeEvent(
            entityId = entityId,
            entityName = "Soulstone Sanctuary",
            fromZone = Zone.BATTLEFIELD,
            toZone = Zone.GRAVEYARD,
            ownerId = playerId,
            lastKnown = reconstructedSnapshot,
        )
        val historicalEvent = reconstructedEvent.copy(lastKnown = historicalSnapshot)
        return LegacyTimeLordFixture(
            playerId = playerId,
            entityId = entityId,
            base = base,
            historicalSnapshot = historicalSnapshot,
            reconstructedSnapshot = reconstructedSnapshot,
            historicalEvent = historicalEvent,
            reconstructedEvent = reconstructedEvent,
            historicalState = base.copy(
                entities = mapOf(
                    entityId to ComponentContainer.of(
                        LastKnownPermanentComponent(historicalSnapshot),
                    )
                ),
                timestamp = 1,
            ),
            reconstructedState = base.copy(
                entities = mapOf(
                    entityId to ComponentContainer.of(
                        LastKnownPermanentComponent(reconstructedSnapshot),
                    )
                ),
                timestamp = 1,
            ),
        )
    }

    private data class SyntheticDelayedAbilityTransition(
        val action: PassPriority,
        val expectedEvents: List<GameEvent>,
        val actualEvents: List<GameEvent>,
        val expectedBefore: GameState,
        val actualBefore: GameState,
        val expectedAfter: GameState,
        val actualAfter: GameState,
    ) {
        fun difference(
            equivalence: RecordedReplayStateEquivalence,
            rawOrdinal: Int,
            expectedAction: com.wingedsheep.engine.core.GameAction = action,
            actualAction: com.wingedsheep.engine.core.GameAction = action,
            expectedAccepted: Boolean = true,
            actualAccepted: Boolean = true,
        ): RecordedReplayStateDifference? =
            equivalence.transitionDifference(
                expectedAction = expectedAction,
                actualAction = actualAction,
                expectedEvents = expectedEvents,
                actualEvents = actualEvents,
                expectedBefore = expectedBefore,
                actualBefore = actualBefore,
                expectedAfter = expectedAfter,
                actualAfter = actualAfter,
                expectedAccepted = expectedAccepted,
                actualAccepted = actualAccepted,
                rawOrdinal = rawOrdinal,
            )
    }

    private fun syntheticDelayedAbilityTransition(
        expectedBefore: GameState = syntheticState(SYNTHETIC_EXPECTED_DELAYED_ID, null),
        actualBefore: GameState = syntheticState(SYNTHETIC_ACTUAL_DELAYED_ID, null),
        expectedAbilityId: String = SYNTHETIC_EXPECTED_ABILITY_ID,
        actualAbilityId: String = SYNTHETIC_ACTUAL_ABILITY_ID,
        stackEntityId: EntityId = SYNTHETIC_STACK_ID,
    ): SyntheticDelayedAbilityTransition {
        val expectedAfter = syntheticState(null, expectedAbilityId, stackEntityId)
        val actualAfter = syntheticState(null, actualAbilityId, stackEntityId)
        val triggered = AbilityTriggeredEvent(
            sourceId = SYNTHETIC_SOURCE_ID,
            sourceName = SYNTHETIC_SOURCE_NAME,
            controllerId = SYNTHETIC_CONTROLLER_ID,
            description = syntheticStackComponent(expectedAbilityId).description,
            abilityEntityId = stackEntityId,
        )
        return SyntheticDelayedAbilityTransition(
            action = PassPriority(SYNTHETIC_ACTOR_ID),
            expectedEvents = listOf(triggered),
            actualEvents = listOf(triggered),
            expectedBefore = expectedBefore,
            actualBefore = actualBefore,
            expectedAfter = expectedAfter,
            actualAfter = actualAfter,
        )
    }

    private fun syntheticState(
        delayedId: String?,
        stackAbilityId: String?,
        stackEntityId: EntityId = SYNTHETIC_STACK_ID,
    ): GameState {
        val entities = buildMap {
            put(SYNTHETIC_SOURCE_ID, ComponentContainer.of(SYNTHETIC_CARD))
            stackAbilityId?.let { id ->
                put(stackEntityId, ComponentContainer.of(syntheticStackComponent(id)))
            }
        }
        return GameState(
            entities = entities,
            turnNumber = 1,
            activePlayerId = SYNTHETIC_CONTROLLER_ID,
            step = Step.END,
            stack = if (stackAbilityId == null) emptyList() else listOf(stackEntityId),
            timestamp = if (stackAbilityId == null) 0 else 1,
            delayedTriggers = delayedId?.let { listOf(syntheticDelayedTrigger(it)) }.orEmpty(),
        )
    }

    private fun syntheticDelayedTrigger(id: String) = DelayedTriggeredAbility(
        id = id,
        effect = SYNTHETIC_EFFECT,
        fireAtStep = Step.END,
        sourceId = SYNTHETIC_SOURCE_ID,
        sourceName = SYNTHETIC_SOURCE_NAME,
        controllerId = SYNTHETIC_CONTROLLER_ID,
    )

    private fun syntheticStackComponent(abilityId: String): TriggeredAbilityOnStackComponent {
        val identity = AbilityIdentity(SYNTHETIC_CARD.cardDefinitionId, AbilityId(abilityId))
        val generated = TriggeredAbility(
            id = identity.abilityId,
            trigger = EventPattern.StepEvent(Step.END, Player.Each),
            binding = TriggerBinding.ANY,
            effect = SYNTHETIC_EFFECT,
        )
        return TriggeredAbilityOnStackComponent(
            sourceId = SYNTHETIC_SOURCE_ID,
            sourceName = SYNTHETIC_SOURCE_NAME,
            controllerId = SYNTHETIC_CONTROLLER_ID,
            effect = SYNTHETIC_EFFECT,
            description = generated.description,
            abilityIdentity = identity,
        )
    }

    private fun GameState.withoutSyntheticStackAbility(): GameState = copy(
        entities = entities - SYNTHETIC_STACK_ID,
        stack = stack - SYNTHETIC_STACK_ID,
        timestamp = timestamp + 1,
    )

    private fun GameState.withSyntheticStackAbility(abilityId: String): GameState = copy(
        entities = entities + (SYNTHETIC_STACK_ID to ComponentContainer.of(syntheticStackComponent(abilityId))),
    )

    private fun GameState.withSyntheticYield(abilityId: String): GameState = copy(
        yieldsByPlayer = mapOf(
            SYNTHETIC_CONTROLLER_ID to PlayerYields(
                wholeGame = setOf(AbilityIdentity(SYNTHETIC_CARD.cardDefinitionId, AbilityId(abilityId))),
            )
        ),
    )

    private fun syntheticPendingDecision(prompt: String) = YesNoDecision(
        id = "synthetic-pending-id",
        playerId = SYNTHETIC_CONTROLLER_ID,
        prompt = prompt,
        context = DecisionContext(),
    )

    private fun fixtureInspectionBundle(): PolicyInspectionBundle {
        val directory = createTempDirectory("outcome-state-safe-bundle")
        val fixture = writeReplayFixture(directory.resolve("fixture.replay.jsonl.gz"))
        return CanonicalOutcomeStateProjector(
            SemanticReplayWorldFactory { FixtureReplayWorld(fixture) }
        ).project(
            fixture.path,
            fixture.expectation(),
            OutcomeStateInspectionIdentity(
                producerOuterCommit = PRODUCER_COMMIT,
                controlPolicyEvidenceIdentity = OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID,
            ),
        ).bundle
    }

    private data class ReplayFixture(
        val path: Path,
        val historicalGameId: String,
        val createdAtUtc: String,
        val gameSeed: Long,
        val searchBaseSeed: Long,
        val states: List<GameState>,
        val actions: List<PassPriority>,
        val choices: List<SemanticChoice>,
    ) {
        fun expectation() = OutcomeStateReplayExpectation(
            pairIndex = 50,
            leg = "a",
            split = OutcomeStateCorpusSplit.TRAIN,
            historicalGameId = historicalGameId,
            historicalGameSeed = gameSeed,
            historicalP0PolicyId = SEARCH_BUDGET_FRONTIER_CONTROL_ID,
            historicalP1PolicyId = SEARCH_BUDGET_FRONTIER_TREATMENT_ID,
            expectedWinnerId = "p0",
            rootPlayerId = "p0",
            parentRunIdentity = OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            historicalOuterCommit = OUTCOME_STATE_CORPUS_HISTORICAL_OUTER_COMMIT,
            argentumCommit = OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
            deckHash = DECK_HASH,
            cardPoolHash = CARD_POOL_HASH,
            parentReplayReference = "replays/$historicalGameId.privileged.replay.jsonl.gz",
            parentReplaySha256 = sha256File(path),
            profileManifestHash = PROFILE_HASH,
        )
    }

    private class FixtureReplayWorld(private val fixture: ReplayFixture) : SemanticReplayWorld {
        private var decisionIndex = 0

        override fun actorToAct(): String? = when (decisionIndex) {
            0 -> "p0"
            1 -> "p1"
            else -> null
        }

        override fun informationState(viewer: String): PolicyInformationState {
            require(viewer == "p0")
            val history = fixtureHistory().take(decisionIndex)
            val actor = actorToAct()
            val candidates = if (actor == viewer) listOf(fixture.choices[decisionIndex]) else emptyList()
            return fixtureInformationState(
                viewer = viewer,
                actor = actor,
                decisionIndex = decisionIndex,
                history = history,
                candidates = candidates,
                terminated = decisionIndex == fixture.choices.size,
                winnerId = "p0".takeIf { decisionIndex == fixture.choices.size },
            )
        }

        override fun expandChoices(): List<SemanticChoice> = listOf(fixture.choices[decisionIndex])

        override fun stepWithReplayTrace(choice: SemanticChoice): SemanticReplayStep {
            require(choice == fixture.choices[decisionIndex])
            val index = decisionIndex
            decisionIndex++
            return SemanticReplayStep(
                result = SearchStepResult(accepted = true, privateToActor = index == 1),
                rawTransitions = listOf(
                    ArgentumRawTransition(
                        action = fixture.actions[index],
                        beforeState = fixture.states[index],
                        afterState = fixture.states[index + 1],
                        events = emptyList(),
                        rejectionReason = null,
                    )
                ),
            )
        }

        override fun authoritativeState(): GameState = fixture.states[decisionIndex]

        override fun terminalPayoff(rootPlayer: String): Double? =
            if (decisionIndex == fixture.choices.size) {
                if (rootPlayer == "p0") 1.0 else -1.0
            } else {
                null
            }
    }

    companion object {
        private const val PRODUCER_COMMIT = "269c637ae4944ba84e4045b2973012306b9b8c88"
        private const val SYNTHETIC_EXPECTED_DELAYED_ID = "synthetic-delayed-expected"
        private const val SYNTHETIC_ACTUAL_DELAYED_ID = "synthetic-delayed-actual"
        private const val SYNTHETIC_EXPECTED_ABILITY_ID = "ability_synthetic_expected"
        private const val SYNTHETIC_ACTUAL_ABILITY_ID = "ability_synthetic_actual"
        private const val SYNTHETIC_SOURCE_NAME = "Synthetic Delayed Source"
        private const val SYNTHETIC_ABILITY_STATE_PATH =
            "/entities/synthetic-stack/" +
                "com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent/" +
                "abilityIdentity/abilityId"
        private val SYNTHETIC_SOURCE_ID = EntityId.of("synthetic-source")
        private val SYNTHETIC_STACK_ID = EntityId.of("synthetic-stack")
        private val SYNTHETIC_SECOND_STACK_ID = EntityId.of("synthetic-stack-second")
        private val SYNTHETIC_CONTROLLER_ID = EntityId.of("synthetic-controller")
        private val SYNTHETIC_ACTOR_ID = EntityId.of("synthetic-actor")
        private val SYNTHETIC_EFFECT = GainLifeEffect(1)
        private val SYNTHETIC_CARD = CardComponent(
            cardDefinitionId = "Synthetic Delayed Source#1",
            name = SYNTHETIC_SOURCE_NAME,
            manaCost = ManaCost(emptyList()),
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
        )
        private val DECK_HASH = sha256("fixture-deck")
        private val CARD_POOL_HASH = sha256("fixture-card-pool")
        private val PROFILE_HASH = sha256("fixture-profile")

        private fun writeReplayFixture(path: Path): ReplayFixture {
            val historicalGameId = "historical-search-budget-game-50-a"
            val createdAtUtc = "2026-09-02T03:04:05Z"
            val gameSeed = 117L
            val searchBaseSeed = 991L
            val e0 = EntityId("e0")
            val e1 = EntityId("e1")
            val states = listOf(
                GameState(
                    turnNumber = 1,
                    activePlayerId = e0,
                    priorityPlayerId = e0,
                    turnOrder = listOf(e0, e1),
                ),
                GameState(
                    turnNumber = 1,
                    activePlayerId = e0,
                    priorityPlayerId = e1,
                    turnOrder = listOf(e0, e1),
                    timestamp = 1,
                ),
                GameState(
                    turnNumber = 1,
                    activePlayerId = e0,
                    priorityPlayerId = null,
                    turnOrder = listOf(e0, e1),
                    timestamp = 2,
                    winnerId = e0,
                    gameOver = true,
                ),
            )
            val actions = listOf(PassPriority(e0), PassPriority(e1))
            val choices = listOf(
                fixtureChoice("root pass", "p0"),
                fixtureChoice("secret opponent answer", "p1"),
            )
            val recorder = CanonicalReplayRecorder(
                gameId = historicalGameId,
                createdAtUtc = createdAtUtc,
                engineVersion = OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
                producer = "historical-search-teacher",
                players = listOf("p0", "p1"),
                initialState = states.first(),
                extensions = buildJsonObject {
                    put("mtgallium.runIdentity", JsonPrimitive(OUTCOME_STATE_CORPUS_PARENT_IDENTITY))
                    put("mtgallium.outerCommit", JsonPrimitive(OUTCOME_STATE_CORPUS_HISTORICAL_OUTER_COMMIT))
                    put("mtgallium.argentumCommit", JsonPrimitive(OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT))
                    put("mtgallium.gameSeed", JsonPrimitive(gameSeed))
                    put("mtgallium.baseSeed", JsonPrimitive(searchBaseSeed))
                    put("mtgallium.deckHash", JsonPrimitive(DECK_HASH))
                    put("mtgallium.cardPoolHash", JsonPrimitive(CARD_POOL_HASH))
                },
            )
            val transitions = actions.indices.map { index ->
                recorder.appendAction(
                    origin = ReplayTransitionOrigin.POLICY,
                    action = actions[index],
                    accepted = true,
                    resultingState = states[index + 1],
                    extensions = semanticExtensions(index, choices[index]),
                )
            }
            val terminal = recorder.finish(
                status = ReplayCompletionStatus.COMPLETE,
                finalState = states.last(),
                winnerId = "p0",
            )
            writeReplayRecords(path, listOf(recorder.header) + transitions + terminal)
            return ReplayFixture(
                path,
                historicalGameId,
                createdAtUtc,
                gameSeed,
                searchBaseSeed,
                states,
                actions,
                choices,
            )
        }

        private fun semanticExtensions(decisionIndex: Int, choice: SemanticChoice): JsonObject =
            buildJsonObject {
                put("mtgallium.decisionIndex", JsonPrimitive(decisionIndex))
                put(
                    "mtgallium.semanticChoice",
                    PolicyJson.format.encodeToJsonElement(SemanticChoice.serializer(), choice),
                )
            }

        private fun writeReplayRecords(path: Path, records: List<CanonicalReplayRecord>) {
            GZIPOutputStream(Files.newOutputStream(path)).bufferedWriter().use { writer ->
                records.forEach { record ->
                    writer.appendLine(
                        CanonicalReplayJson.encodeToString(CanonicalReplayRecord.serializer(), record)
                    )
                }
            }
        }

        private fun fixtureChoice(label: String, playerId: String): SemanticChoice = SemanticChoice.create(
            kind = SemanticChoiceKind.ACTION,
            operationFamily = SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay(label),
            canonicalPayload = buildJsonObject {
                put("type", JsonPrimitive("PassPriority"))
                put("playerId", JsonPrimitive(playerId))
            },
        )

        private fun fixtureHistory(): List<PolicyHistoryEvent> = listOf(
            PolicyHistoryEvent(
                eventId = 0,
                audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
                actor = "p0",
                kind = PolicyHistoryEventKind.PRIORITY_PASS,
                payload = buildJsonObject { put("pass", JsonPrimitive(true)) },
            ),
            PolicyHistoryEvent(
                eventId = 1,
                audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
                actor = "p1",
                kind = PolicyHistoryEventKind.PRIVATE_DECISION_OCCURRED,
                payload = buildJsonObject { put("privateDecisionOccurred", JsonPrimitive(true)) },
            ),
        )

        private fun fixtureInformationState(
            viewer: String,
            actor: String?,
            decisionIndex: Int,
            history: List<PolicyHistoryEvent>,
            candidates: List<SemanticChoice>,
            terminated: Boolean,
            winnerId: String?,
        ): PolicyInformationState {
            val observation = PolicyObservation(
                perspectivePlayerId = viewer,
                turnNumber = 1,
                phase = "BEGINNING",
                step = "UPKEEP",
                activePlayerId = "p0",
                priorityPlayerId = actor,
                players = listOf(
                    PolicyPlayerView("p0", "Root", 20, 0, 53, 0, 0, PolicyManaPool(), true, actor == "p0", false),
                    PolicyPlayerView("p1", "Opponent", 20, 0, 53, 0, 0, PolicyManaPool(), false, actor == "p1", false),
                ),
                zones = emptyList(),
                stack = emptyList(),
                currentTurnStateComplete = true,
                pendingDecision = null,
                observationDigest = PolicyJson.sha256("fixture-observation:$viewer:$decisionIndex"),
            )
            return PolicyInformationState(
                actingPlayerId = actor,
                observation = observation,
                informationStateDigest = PolicyJson.sha256("fixture-information:$viewer:$decisionIndex"),
                historyCommitment = PolicyHistoryCommitment.replay(history),
                history = history,
                knowledge = PolicyKnowledgeState(
                    perspectivePlayerId = viewer,
                    epistemicallyComplete = true,
                    knowledgeDigest = PolicyJson.sha256("fixture-knowledge:$viewer:$decisionIndex"),
                ),
                candidates = candidates,
                terminated = terminated,
                winnerId = winnerId,
            )
        }
    }
}
