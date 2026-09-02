package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.research.run.ResearchRunCheckpoints
import org.junit.jupiter.api.io.TempDir

class Issue0031NeuralCohortContinuationDiagnosticTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `matched continuation uses equal optimizer steps rather than equal epochs`() {
        val model = NeuralBcModelConfig(stateDimension = 8, candidateDimension = 8, hiddenDimension = 4)
        val training = NeuralBcTrainingConfig(candidateProjectionUpdateScale = 1.0 / 8.0)
        val initial = CandidateConditionedNeuralPolicy.initialize(model, 41L).artifact
        val shortArtifact = copyNeuralBcModelArtifact(initial)
        val longArtifact = copyNeuralBcModelArtifact(initial)
        val checkpoint = SparseAdam(initial, training).snapshotState()
        val short = Issue0031ContinuationBranch(
            seed = 41L,
            forkEpoch = 7,
            decisions = (0 until 3).map(::decision),
            artifact = shortArtifact,
            optimizer = SparseAdam(shortArtifact, training, checkpoint),
        )
        val long = Issue0031ContinuationBranch(
            seed = 41L,
            forkEpoch = 7,
            decisions = (0 until 5).map(::decision),
            artifact = longArtifact,
            optimizer = SparseAdam(longArtifact, training, checkpoint),
        )

        short.advance(5)
        long.advance(5)

        assertEquals(5L, short.postForkSteps)
        assertEquals(short.postForkSteps, long.postForkSteps)
        assertEquals(1, short.epochsCompleted)
        assertEquals(2, short.epochPosition)
        assertEquals(1, long.epochsCompleted)
        assertEquals(0, long.epochPosition)
    }

    @Test
    fun `persisted fork round trip retains complete optimizer continuation state`() {
        val model = NeuralBcModelConfig(stateDimension = 8, candidateDimension = 8, hiddenDimension = 4)
        val training = NeuralBcTrainingConfig(candidateProjectionUpdateScale = 1.0 / 8.0)
        val artifact = CandidateConditionedNeuralPolicy.initialize(model, 41L).artifact
        val adam = SparseAdam(artifact, training)
        adam.step(decision(0))
        val checkpoint = Issue0031TrainingForkCheckpoint(
            seed = 41L,
            completedAnchorEpochs = 1,
            firstPerfectAnchorEpoch = 1,
            consecutivePerfectAnchorEpochs = 20,
            nextAbsoluteEpoch = 2,
            model = artifact,
            optimizer = adam.snapshotState(),
        )

        val decoded = evidenceJson.decodeFromString<Issue0031TrainingForkCheckpoint>(
            evidenceJson.encodeToString(checkpoint)
        )

        assertTrue(sameSparseAdamState(checkpoint.optimizer, decoded.optimizer))
        assertTrue(checkpoint.model.stateWeights.contentEquals(decoded.model.stateWeights))
        assertTrue(checkpoint.model.candidateWeights.contentEquals(decoded.model.candidateWeights))
        assertEquals(checkpoint.nextAbsoluteEpoch, decoded.nextAbsoluteEpoch)
    }

    @Test
    fun `generic checkpoint envelope restores the complete neural continuation payload`() {
        val model = NeuralBcModelConfig(stateDimension = 8, candidateDimension = 8, hiddenDimension = 4)
        val artifact = CandidateConditionedNeuralPolicy.initialize(model, 41L).artifact
        val checkpoint = Issue0031TrainingForkCheckpoint(
            seed = 41L, completedAnchorEpochs = 1, firstPerfectAnchorEpoch = 1,
            consecutivePerfectAnchorEpochs = 20, nextAbsoluteEpoch = 2, model = artifact,
            optimizer = SparseAdam(artifact, NeuralBcTrainingConfig(candidateProjectionUpdateScale = 1.0 / 8.0)).snapshotState(),
        )
        val path = temporaryDirectory.resolve("fork.json")
        ResearchRunCheckpoints.persist(
            path, "run", "issue-0031-training-fork-v1", 0,
            evidenceJson.encodeToString(checkpoint).encodeToByteArray(),
        )

        val restored = loadIssue0031ForkCheckpoint(path, "run")
        assertTrue(sameSparseAdamState(checkpoint.optimizer, restored.optimizer))
        assertTrue(checkpoint.model.stateWeights.contentEquals(restored.model.stateWeights))
        assertEquals(checkpoint.nextAbsoluteEpoch, restored.nextAbsoluteEpoch)
    }

    @Test
    fun `combined cohort metrics reconstruct the exact scalar objective`() {
        val model = NeuralBcModelConfig(stateDimension = 8, candidateDimension = 8, hiddenDimension = 4)
        val policy = CandidateConditionedNeuralPolicy.initialize(model, 41L)
        val anchorDecisions = listOf(decision(0), decision(1), decision(2))
        val addedDecisions = listOf(decision(3), decision(4))
        val anchor = cohortMetrics(policy, anchorDecisions)
        val added = cohortMetrics(policy, addedDecisions)
        val combined = cohortMetrics(policy, anchorDecisions + addedDecisions)

        requireCombinedMetrics(anchor, added, combined)

        assertEquals(
            (3.0 * anchor.meanCrossEntropy + 2.0 * added.meanCrossEntropy) / 5.0,
            combined.meanCrossEntropy,
            absoluteTolerance = 1e-15,
        )
        assertEquals(anchor.strictRankingCorrect + added.strictRankingCorrect, combined.strictRankingCorrect)
    }

    private fun decision(index: Int): EncodedBcDecision = EncodedBcDecision(
        gameId = "issue-0031-test",
        decisionIndex = index,
        decisionFamily = "ORDINARY_ACTION",
        state = sparse(index % 3),
        candidates = listOf(sparse(4), sparse(5)),
        candidateFamilies = List(2) { SemanticOperationFamily.CAST_SPELL },
        candidateIntents = List(2) { SemanticActionIntentKind.CAST_SPELL },
        labelIndex = index % 2,
    )

    private fun sparse(index: Int): SparseFeatureVector =
        SparseFeatureVector(intArrayOf(index), doubleArrayOf(1.0))
}
