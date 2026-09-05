package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.SearchSettlementOrigin
import org.mtgallium.agent.infoset.core.SearchSettlement
import org.mtgallium.agent.infoset.core.SearchStepResult
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.searchteacher.PolicyCompressionConfig
import org.mtgallium.agent.searchteacher.SearchReuseConfig
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatureCompiler
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueTrainingBinding
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueCheckpointPayload
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueEvaluator
import org.mtgallium.agent.searchteacher.SEARCH_TEACHER_UNPROFILED_RUNTIME_ID
import org.junit.jupiter.api.Tag

@Tag("public-source")
class LearnedLeafFixedRootDiagnosticTest {
    @Test
    fun `deployed feature moments keep repeated floating values inside their exact range`() {
        val key = "state/repeated-rounding-witness"
        val repeated = -0.0030045090202987217

        val moment = summarizeFeatureMoments(
            compiled = List(64) { mapOf(key to repeated) },
            train = emptyMap(),
        ).single()

        assertEquals(64, moment.observations)
        assertEquals(repeated, moment.minimum)
        assertEquals(repeated, moment.mean)
        assertEquals(repeated, moment.maximum)
        assertEquals(0.0, moment.populationVariance)
    }

    @Test
    fun `direct sibling report retains ranking regret heuristic and per-candidate feature evidence`() {
        val root = fixedRootSelection()
        val stageTwo = FixedRootForcedPassWorld(2).informationState("p0")
        val stageThree = FixedRootForcedPassWorld(3).informationState("p0")
        val compiled = LearnedOutcomeValueFeatureCompiler.compile(stageTwo, "p0").values
        val selectedKeys = compiled.keys.take(2)
        val moments = listOf(
            OutcomeValueTrainFeatureMoment(selectedKeys[0], 0.0, 1.0),
            OutcomeValueTrainFeatureMoment(selectedKeys[1], 0.0, 0.0),
        ).sortedBy { it.key }
        val training = dummyTrainingBinding()
        val reference = OutcomeValueTrainFeatureReference(
            training = training,
            rows = 4,
            games = 2,
            constantBaseline = 0.0,
            moments = moments,
            referenceDigest = outcomeValueTrainFeatureReferenceDigest(training, 4, 2, 0.0, moments),
        )
        fun settled(value: Double, origin: SearchSettlementOrigin) =
            LearnedLeafTreatmentOutcome.Settled(SearchSettlement(value, origin))
        fun leaf(
            signature: String,
            scheduleIndex: Int,
            learned: Double,
            control: Double,
            heuristic: Double,
            information: PolicyInformationState,
            rawLearned: Double = learned,
        ) = LearnedLeafDirectSiblingLeaf.Nonterminal(
            root.id, signature, scheduleIndex, root.schedule.scheduleDigest,
            postActionInformation = FixedRootForcedPassWorld(1).informationState("p0"),
            deployedLearnedLeaf = LearnedLeafRecordedEvaluation(
                information, "p0", learned, heuristic, rawLearned,
            ),
            learnedTreatment = settled(learned, SearchSettlementOrigin.LEARNED_OUTCOME_ESTIMATE),
            boundedRolloutControl = settled(control, SearchSettlementOrigin.HEURISTIC_SETTLEMENT),
        )
        val leaves = listOf(
            leaf("a", 0, 1.0, 0.2, -0.5, stageTwo, rawLearned = 1.8),
            leaf("a", 1, 1.0, 0.2, -0.5, stageThree, rawLearned = 1.6),
            leaf("b", 0, 0.1, 0.9, 0.5, stageTwo),
            leaf("b", 1, 0.1, 0.9, 0.5, stageThree),
        )

        val outcome = LearnedLeafDirectSiblingComparator(reference).compareMaterialized(root, leaves)
        val report = (outcome as LearnedLeafDirectSiblingOutcome.Complete).report
        val learned = report.comparisonsToControl.single { it.arm == LearnedLeafFixedRootArm.LEARNED_TREATMENT }
        val heuristic = report.directSiblingArms.single {
            it.arm == LearnedLeafFixedRootArm.DIRECT_SIBLING_VISIBLE_HEURISTIC
        }
        val raw = report.directSiblingArms.single {
            it.arm == LearnedLeafFixedRootArm.DIRECT_SIBLING_RAW_LEARNED_SCORE
        }

        assertEquals(false, learned.sameTopSignatureAsControl)
        assertEquals(1, learned.pairwiseCompared)
        assertEquals(0, learned.pairwiseAgreed)
        assertEquals(0.0, learned.pairwiseAccuracy)
        assertEquals(0.7, learned.controlRegret, 1e-12)
        assertEquals(0.9, learned.learnedValuedRegretOfControlSelectedAction, 1e-12)
        assertEquals("b", heuristic.topSignature)
        assertEquals("a", raw.topSignature)
        assertEquals(1.7, raw.candidateMeans.getValue("a"), 1e-12)
        assertEquals(0.1, raw.candidateMeans.getValue("b"), 1e-12)
        assertEquals(4, report.rawImmediateLearnedScores.size)
        assertEquals(1.8, report.rawImmediateLearnedScores.single {
            it.candidateSignature == "a" && it.scheduleIndex == 0
        }.value)
        assertEquals(1.0, report.rawImmediateLearnedScores.single {
            it.candidateSignature == "a" && it.scheduleIndex == 0
        }.deployedClippedValue)
        assertEquals(2, report.featureAudit.perCandidate.size)
        assertTrue(report.featureAudit.overallFeatureMoments.isNotEmpty())
        assertTrue(report.featureAudit.overallFeatureMoments.all { it.observations == 4 })
        assertTrue(report.featureAudit.perCandidate.all { it.featureMoments.isNotEmpty() })
        assertTrue(report.featureAudit.zeroVarianceFeatureOccurrences > 0)
        assertTrue(report.featureAudit.unseenFeatureOccurrences > 0)
        assertTrue(report.featureAudit.perCandidate.all { candidate ->
            candidate.featureMoments.all { it.observations == 2 && it.minimum <= it.maximum }
        })
    }

    @Test
    fun `recorder captures deployed learned leaf after production forced-pass quiescence`() {
        val beforeFeatures = LearnedOutcomeValueFeatureCompiler.compile(
            FixedRootForcedPassWorld(1).informationState("p0"), "p0",
        ).values
        val afterInformation = FixedRootForcedPassWorld(2).informationState("p0")
        val afterFeatures = LearnedOutcomeValueFeatureCompiler.compile(afterInformation, "p0").values
        val changingKey = (beforeFeatures.keys + afterFeatures.keys).single {
            (beforeFeatures[it] ?: 0.0) != (afterFeatures[it] ?: 0.0) &&
                it in afterFeatures && it in beforeFeatures
        }
        val delegate = LearnedOutcomeValueEvaluator.fromCheckpoint(
            LearnedOutcomeValueCheckpointPayload(
                training = dummyTrainingBinding(), bias = 0.0, weights = mapOf(changingKey to 0.1),
            ),
        )
        val recorder = RecordedLearnedEvaluator(delegate)
        val search = SearchTeacherSearchFactory.create(
            composition().parameters(
                17L,
                LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1,
                ),
            ).searchConfig(),
            informationEvaluator = recorder.evaluator,
        )
        val postAction = FixedRootForcedPassWorld(stage = 1).informationState("p0")

        val settlement = search.settleFirstUnvisitedEdge(
            FixedRootForcedPassWorld(stage = 1), "p0", 19L, 0,
        )
        val captured = requireNotNull(recorder.captureFor(LearnedLeafTreatmentOutcome.Settled(settlement)))

        assertEquals(SearchSettlementOrigin.LEARNED_OUTCOME_ESTIMATE, settlement.origin)
        assertEquals(2, captured.information.observation.turnNumber)
        assertEquals("p0", captured.rootPlayer)
        assertEquals(delegate.evaluate(afterInformation, "p0"), captured.learnedValue)
        assertEquals(delegate.evaluateDetailed(afterInformation, "p0").rawScore, captured.rawLearnedScore)
        assertNotEquals(postAction.informationStateDigest, captured.information.informationStateDigest)
    }

    @Test
    fun `an unfilled panel is explicit and cannot be scored`() {
        val manifest = LearnedLeafFixedRootManifest(
            manifestId = "unfilled",
            sourceStubSha256 = LEARNED_LEAF_FIXED_ROOT_STUB_SHA256,
            sourceStubSchemaVersion = 1,
            selectionWasResultBlind = true,
            selectionRule = "synthetic",
            mtgalliumSourceCommit = LEARNED_LEAF_FIXED_ROOT_SOURCE_COMMIT,
            pilot = pilotBinding(),
            roots = emptyList(),
        )

        assertEquals(LEARNED_LEAF_FIXED_ROOT_SOURCE_COMMIT, manifest.mtgalliumSourceCommit)
        assertFailsWith<IllegalArgumentException> { manifest.requireComplete() }
    }

    @Test
    fun `manifest accepts only explicitly frozen selection stub identities`() {
        val accepted = LearnedLeafFixedRootManifest(
            manifestId = "unfilled",
            sourceStubSha256 = LEARNED_LEAF_FIRST_DIVERGENCE_MULLIGAN_STUB_SHA256,
            sourceStubSchemaVersion = 1,
            selectionWasResultBlind = true,
            selectionRule = "all exact first keep-take divergences",
            mtgalliumSourceCommit = LEARNED_LEAF_FIXED_ROOT_SOURCE_COMMIT,
            pilot = pilotBinding(),
            roots = emptyList(),
        )

        assertEquals(LEARNED_LEAF_FIRST_DIVERGENCE_MULLIGAN_STUB_SHA256, accepted.sourceStubSha256)
        assertFailsWith<IllegalArgumentException> {
            accepted.copy(sourceStubSha256 = "f".repeat(64))
        }
    }

    @Test
    fun `manifest cannot substitute a later diagnostic checkout for the historical learned source`() {
        assertFailsWith<IllegalArgumentException> {
            LearnedLeafFixedRootManifest(
                manifestId = "wrong-source",
                sourceStubSha256 = LEARNED_LEAF_FIXED_ROOT_STUB_SHA256,
                sourceStubSchemaVersion = 1,
                selectionWasResultBlind = true,
                selectionRule = "synthetic",
                mtgalliumSourceCommit = "f".repeat(40),
                pilot = pilotBinding(),
                roots = emptyList(),
            )
        }
    }

    @Test
    fun `private panel loading is path-explicit and keeps an unfilled synthetic panel unscorable`() {
        val manifest = LearnedLeafFixedRootManifest(
            manifestId = "synthetic-private-panel",
            sourceStubSha256 = LEARNED_LEAF_FIXED_ROOT_STUB_SHA256,
            sourceStubSchemaVersion = 1,
            selectionWasResultBlind = true,
            selectionRule = "synthetic",
            mtgalliumSourceCommit = LEARNED_LEAF_FIXED_ROOT_SOURCE_COMMIT,
            pilot = pilotBinding(),
            roots = emptyList(),
        )
        val path = createTempFile("fixed-root-panel", ".json")
        Files.writeString(path, evidenceJson.encodeToString(LearnedLeafFixedRootManifest.serializer(), manifest))

        val loaded = readLearnedLeafFixedRootManifest(path)

        assertEquals(manifest, loaded.manifest)
        assertEquals(sha256File(path), loaded.sha256)
        assertFailsWith<IllegalArgumentException> { loaded.manifest.requireComplete() }
    }

    @Test
    fun `a frozen schedule binds source reconstruction and production particle coordinates`() {
        val schedule = schedule()

        assertEquals(learnedLeafFixedRootScheduleDigest(
            schedule.originalGameId, schedule.replayGameSeed, schedule.replayBaseSeed,
            schedule.policySearchBaseSeed, schedule.decisionIndex, schedule.beliefLifecycleVersion,
            schedule.beliefDerivationVersion, schedule.coordinates,
        ), schedule.scheduleDigest)
        assertFailsWith<IllegalArgumentException> {
            LearnedLeafFixedRootSchedule(
                originalGameId = schedule.originalGameId,
                replayGameSeed = schedule.replayGameSeed,
                replayBaseSeed = schedule.replayBaseSeed,
                policySearchBaseSeed = schedule.policySearchBaseSeed,
                decisionIndex = schedule.decisionIndex,
                beliefLifecycleVersion = schedule.beliefLifecycleVersion,
                beliefDerivationVersion = schedule.beliefDerivationVersion,
                coordinates = schedule.coordinates.reversed(),
                scheduleDigest = schedule.scheduleDigest,
            )
        }
    }

    @Test
    fun `schedule digest has one unambiguous seed tuple`() {
        val coordinates = listOf(LearnedLeafFixedRootScheduleCoordinate(0, 3))
        val digest = learnedLeafFixedRootScheduleDigest(
            "game-a", 99L, 20260823L, 20260825L, 9,
            "sequential-b-v1", "production-root-particle-indices-v1", coordinates,
        )

        assertEquals(
            PolicyJson.sha256(
                "learned-leaf-fixed-root-schedule-v2\n" +
                    "game-a:99:20260823:20260825:9\n" +
                    "sequential-b-v1:production-root-particle-indices-v1\n" +
                    "0:3\n",
            ),
            digest,
        )
    }

    @Test
    fun `a fixed-root schedule refuses diagnostic replacement of either historical seed role`() {
        val schedule = schedule()

        assertFailsWith<IllegalArgumentException> {
            schedule.copy(replayBaseSeed = 7L)
        }
        assertFailsWith<IllegalArgumentException> {
            schedule.copy(policySearchBaseSeed = 8L)
        }
    }

    @Test
    fun `policy composition refuses a changed retained fresh-search budget`() {
        assertFailsWith<IllegalArgumentException> { composition().copy(simulations = 32) }
    }

    @Test
    fun `first comparable divergence is the earliest disagreement inside one game`() {
        val witness = firstComparableDivergence(
            listOf(
                LearnedLeafComparableRoot("game-a", 7, "pass", "pass"),
                LearnedLeafComparableRoot("game-a", 9, "cast", "attack"),
                LearnedLeafComparableRoot("game-a", 12, "play", "pass"),
            )
        )

        assertEquals(LearnedLeafFirstComparableDivergence("game-a", 9, "cast", "attack"), witness)
    }

    @Test
    fun `selection metadata binds the exact candidate family rather than a category`() {
        val signatures = listOf("cast:one", "pass")
        val root = LearnedLeafFixedRootSelection(
            id = "root-01", sourceGameId = "game-a", pairIndex = 0, leg = "b", decisionIndex = 9,
            sourcePolicyId = "learned", sourceDecisionFamily = "MAIN_ACTION", sourcePhase = "MAIN",
            sourceStep = "MAIN", turnNumber = 3,
            rootActor = "p0", sourceSeat = "PLAY", representedKnowledgeCategory = "BASELINE",
            selectionReason = "FIRST_COMPARABLE_DIVERGENCE", marginBand = "HIGH",
            marginMetadata = "selector-v1: margin >= frozen threshold",
            rootInformationStateDigest = "a".repeat(64), semanticPrefixDigest = "b".repeat(64),
            replayRelativePath = "replays/game-a.privileged.replay.jsonl.gz", replaySha256 = "c".repeat(64),
            candidateSignatures = signatures, candidateFamilyDigest = learnedLeafCandidateFamilyDigest(signatures),
            schedule = schedule(),
        )

        assertEquals(learnedLeafCandidateFamilyDigest(signatures), root.candidateFamilyDigest)
        assertEquals(
            "initial-state-sha256:${"e".repeat(64)}",
            root.copy(retainedPreStateDigest = "initial-state-sha256:${"e".repeat(64)}").retainedPreStateDigest,
        )
        assertFailsWith<IllegalArgumentException> { root.copy(candidateFamilyDigest = "d".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { root.copy(retainedPreStateDigest = "not-a-state-identity") }
    }
}

private fun fixedRootSelection(): LearnedLeafFixedRootSelection = LearnedLeafFixedRootSelection(
    id = "root-report",
    sourceGameId = "game-a",
    pairIndex = 0,
    leg = "a",
    decisionIndex = 9,
    sourcePolicyId = "learned",
    sourceDecisionFamily = "OTHER",
    sourcePhase = "TEST",
    sourceStep = "QUIET",
    turnNumber = 2,
    rootActor = "p0",
    sourceSeat = "PLAY",
    representedKnowledgeCategory = "COMPLETE_DECK_AND_VISIBLE_FACTS",
    selectionReason = "SYNTHETIC",
    marginBand = "CONTEXT",
    marginMetadata = "synthetic",
    rootInformationStateDigest = "a".repeat(64),
    semanticPrefixDigest = "b".repeat(64),
    replayRelativePath = "replay.gz",
    replaySha256 = "c".repeat(64),
    candidateSignatures = listOf("a", "b"),
    candidateFamilyDigest = learnedLeafCandidateFamilyDigest(listOf("a", "b")),
    schedule = LearnedLeafFixedRootSchedule(
        originalGameId = "game-a",
        replayGameSeed = 99L,
        replayBaseSeed = 20260823L,
        policySearchBaseSeed = 20260825L,
        decisionIndex = 9,
        beliefLifecycleVersion = "sequential-b-v1",
        beliefDerivationVersion = "production-root-particle-indices-v1",
        coordinates = listOf(
            LearnedLeafFixedRootScheduleCoordinate(0, 0),
            LearnedLeafFixedRootScheduleCoordinate(1, 1),
        ),
        scheduleDigest = learnedLeafFixedRootScheduleDigest(
            "game-a", 99L, 20260823L, 20260825L, 9,
            "sequential-b-v1", "production-root-particle-indices-v1",
            listOf(
                LearnedLeafFixedRootScheduleCoordinate(0, 0),
                LearnedLeafFixedRootScheduleCoordinate(1, 1),
            ),
        ),
    ),
)

private fun dummyTrainingBinding(): LearnedOutcomeValueTrainingBinding {
    fun identity(name: String, fill: Char) = "$name-sha256:${fill.toString().repeat(64)}"
    return LearnedOutcomeValueTrainingBinding(
        corpusIdentity = identity("corpus", '1'),
        pairSplitIdentity = identity("split", '2'),
        learnerConfigurationIdentity = identity("learner", '3'),
        projectionIdentity = identity("projection", '4'),
        rootBehaviorPolicyIdentity = identity("root-policy", '5'),
        opponentBehaviorPolicyIdentity = identity("opponent-policy", '6'),
        environmentProfileIdentity = identity("environment", '7'),
    )
}

private class FixedRootForcedPassWorld(
    private var stage: Int,
) : SearchWorld {
    override fun actorToAct(): String = "p0"

    override fun informationState(viewer: String): PolicyInformationState {
        val expansion = expandChoices()
        val observation = PolicyObservation(
            perspectivePlayerId = viewer,
            turnNumber = stage,
            phase = if (stage == 1) "COMBAT" else "TEST",
            step = if (stage == 1) "COMBAT_DAMAGE" else "QUIET",
            activePlayerId = "p0",
            priorityPlayerId = "p0",
            players = listOf(
                PolicyPlayerView("p0", "Root", 20, 0, 40, 0, 0, PolicyManaPool(), true, true, false),
                PolicyPlayerView("p1", "Opponent", 20, 0, 40, 0, 0, PolicyManaPool(), false, false, false),
            ),
            zones = emptyList(),
            stack = emptyList(),
            currentTurnStateComplete = true,
            pendingDecision = null,
            observationDigest = PolicyJson.sha256("fixed-root-recorder-observation:$viewer:$stage"),
        )
        return PolicyInformationState(
            actingPlayerId = "p0",
            observation = observation,
            informationStateDigest = PolicyJson.sha256("fixed-root-recorder-information:$viewer:$stage"),
            historyCommitment = PolicyHistoryCommitment.empty(),
            history = emptyList(),
            knowledge = PolicyKnowledgeState(
                perspectivePlayerId = viewer,
                knowledgeDigest = PolicyJson.sha256("fixed-root-recorder-knowledge:$viewer"),
            ),
            candidates = expansion.candidates,
            terminated = false,
        )
    }

    override fun expandChoices(): PolicyExpansion {
        val candidates = if (stage == 1) {
            listOf(fixedRootTestChoice("Pass", SemanticOperationFamily.PASS_PRIORITY))
        } else {
            listOf(fixedRootTestChoice("A"), fixedRootTestChoice("B"))
        }
        return PolicyExpansion(candidates, true, candidates.size.toLong(), "fixed-root-recorder-v1", 1L)
    }

    override fun step(choice: SemanticChoice): SearchStepResult {
        if (choice.signature !in expandChoices().candidates.map { it.signature }) return SearchStepResult(false)
        stage++
        return SearchStepResult(true)
    }

    override fun fork(): SearchWorld = FixedRootForcedPassWorld(stage)
    override fun terminalPayoff(rootPlayer: String): Double? = null
    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double = error("not used")
}

private fun fixedRootTestChoice(
    label: String,
    family: SemanticOperationFamily = SemanticOperationFamily.OTHER,
) = SemanticChoice.create(
    kind = SemanticChoiceKind.ACTION,
    operationFamily = family,
    display = SemanticChoiceDisplay(label),
    canonicalPayload = kotlinx.serialization.json.buildJsonObject {
        put("choice", kotlinx.serialization.json.JsonPrimitive(label))
    },
)

private fun pilotBinding() = LearnedLeafFixedRootPilotBinding(
    runIdentity = "research-run-v1-sha256:f79",
    argentumCommit = "3eda577fdd10d08e0e62d66b4727ab53f1b41ff5",
    checkpointPayloadSha256 = "e".repeat(64),
    corpusIdentity = "corpus",
    trainingRunIdentity = "training",
    validationRunIdentity = "validation",
    testRunIdentity = "test",
    learnedModelConfigurationId = "model-config",
    control = LearnedLeafFixedRootPolicyBinding(
        "control", "control-evidence", LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_VISIBLE_V2), composition(),
    ),
    learned = LearnedLeafFixedRootPolicyBinding(
        "learned", "learned-evidence", LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1), composition(),
    ),
)

private fun composition() = LearnedLeafFixedRootPolicyComposition(
    profileId = SEARCH_TEACHER_UNPROFILED_RUNTIME_ID,
    particles = 8,
    simulations = 64,
    maxPolicyDecisions = 32,
    explorationConstant = 1.4,
    actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
    beliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
    beliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
    planner = SearchPlannerKind.SHARED_TREE,
    opponentPolicyId = "mono-red-mixture-70-10-10-10-v2",
    policyCompression = PolicyCompressionConfig(enabled = false),
    searchReuse = SearchReuseConfig(enabled = false),
)

private fun schedule(): LearnedLeafFixedRootSchedule {
    val coordinates = listOf(
        LearnedLeafFixedRootScheduleCoordinate(0, 0),
        LearnedLeafFixedRootScheduleCoordinate(1, 1),
    )
    return LearnedLeafFixedRootSchedule(
        originalGameId = "game-a",
        replayGameSeed = 99L,
        replayBaseSeed = 20260823L,
        policySearchBaseSeed = 20260825L,
        decisionIndex = 9,
        beliefLifecycleVersion = "sequential-b-v1",
        beliefDerivationVersion = "production-root-particle-indices-v1",
        coordinates = coordinates,
        scheduleDigest = learnedLeafFixedRootScheduleDigest(
            "game-a", 99L, 20260823L, 20260825L, 9,
            "sequential-b-v1", "production-root-particle-indices-v1", coordinates,
        ),
    )
}
