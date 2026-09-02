package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.junit.jupiter.api.Tag

@Tag("public-source")
class NeuralMemorizationDiagnosticTest {
    @Test
    fun `full scorer audit proves a consistent repeated-state ranking constructively`() {
        val decisions = listOf(
            decision("game-a", 0, state = 0, candidates = listOf(1, 2), label = 1),
            decision("game-b", 0, state = 0, candidates = listOf(1, 2, 3), label = 2),
        )

        val audit = auditNeuralFullInputRealizability(decisions)

        assertEquals(1, audit.distinctEncodedStates)
        assertEquals(1, audit.repeatedEncodedStateGroups)
        assertEquals(2, audit.decisionsWithRepeatedEncodedState)
        assertEquals(3, audit.distinctScorerInputs)
        assertEquals(2, audit.exactDuplicateScorerInputGroups)
        assertEquals(0, audit.contradictoryRankingComponents.size)
        assertTrue(audit.unrestrictedDeterministicScorerIsConsistent)
        assertEquals(2, audit.constructiveStrictRankingCorrect)
        assertEquals(1, audit.constructiveMinimumTeacherMargin)
    }

    @Test
    fun `full scorer audit exposes an exact contradictory ranking cycle`() {
        val decisions = listOf(
            decision("game-a", 0, state = 0, candidates = listOf(1, 2), label = 0),
            decision("game-b", 0, state = 0, candidates = listOf(1, 2), label = 1),
        )

        val audit = auditNeuralFullInputRealizability(decisions)

        assertEquals(1, audit.exactDuplicateDecisionInputGroups)
        assertEquals(2, audit.decisionsInExactDuplicateInputGroups)
        assertEquals(1, audit.contradictoryRankingComponents.size)
        assertEquals(2, audit.contradictoryConstraints)
        assertEquals(2, audit.decisionsAffectedByContradictions)
        assertFalse(audit.unrestrictedDeterministicScorerIsConsistent)
        assertEquals(0, audit.constructiveStrictRankingCorrect)
    }

    @Test
    fun `full scorer audit exposes a teacher-alternative self constraint`() {
        val audit = auditNeuralFullInputRealizability(
            listOf(decision("game-a", 0, state = 0, candidates = listOf(1, 1), label = 0))
        )

        assertEquals(1, audit.selfContradictoryConstraints)
        assertEquals(1, audit.contradictoryRankingComponents.size)
        assertEquals(1, audit.decisionsAffectedByContradictions)
        assertFalse(audit.unrestrictedDeterministicScorerIsConsistent)
    }

    @Test
    fun `nested subset order is deterministic and prefix preserving`() {
        val decisions = (0 until 20).map { index ->
            decision("game-${index / 2}", index, state = index, candidates = listOf(1, 2), label = index % 2)
        }

        val first = deterministicNeuralMemorizationOrder(decisions, "dataset", "protocol")
        val second = deterministicNeuralMemorizationOrder(decisions.reversed(), "dataset", "protocol")

        assertEquals(first.map { it.gameId to it.decisionIndex }, second.map { it.gameId to it.decisionIndex })
        assertEquals(first.take(4), first.take(16).take(4))
    }

    @Test
    fun `original bilinear trainer memorizes a small state-conditioned mapping`() {
        val decisions = listOf(
            decision("game-a", 0, state = 0, candidates = listOf(4, 5), label = 0),
            decision("game-a", 1, state = 1, candidates = listOf(4, 5), label = 1),
            decision("game-b", 0, state = 2, candidates = listOf(6, 7), label = 0),
            decision("game-b", 1, state = 3, candidates = listOf(6, 7), label = 1),
        )
        val trained = NeuralBcMemorizationTrainer(
            modelConfig = NeuralBcModelConfig(
                stateDimension = 8,
                candidateDimension = 8,
                hiddenDimension = 8,
            ),
            trainingConfig = NeuralBcTrainingConfig(
                maximumEpochs = 400,
                learningRate = 0.02,
                initializationSeeds = listOf(41L),
            ),
            perfectConfirmationEpochs = 5,
        ).train(decisions, seed = 41L)

        assertEquals(decisions.size, trained.bestStrictRankingCorrect)
        assertEquals(1.0, trained.bestStrictRankingAccuracy)
        assertTrue(trained.firstPerfectEpoch != null)
        assertTrue(trained.epochMetrics.all { it.meanCrossEntropy.isFinite() })
        assertTrue(trained.stoppedAfterPerfectConfirmation)
    }

    @Test
    fun `read only epoch observer preserves exact optimizer result`() {
        val decisions = listOf(
            decision("game-a", 0, state = 0, candidates = listOf(4, 5), label = 0),
            decision("game-a", 1, state = 1, candidates = listOf(4, 5), label = 1),
            decision("game-b", 0, state = 2, candidates = listOf(6, 7), label = 0),
            decision("game-b", 1, state = 3, candidates = listOf(6, 7), label = 1),
        )
        val model = NeuralBcModelConfig(stateDimension = 8, candidateDimension = 8, hiddenDimension = 8)
        val training = NeuralBcTrainingConfig(maximumEpochs = 40, learningRate = 0.02)
        val trainer = NeuralBcMemorizationTrainer(model, training, perfectConfirmationEpochs = 5)
        val plain = trainer.train(decisions, seed = 41L)
        val tracedEpochs = mutableListOf<Int>()
        val traced = trainer.train(decisions, seed = 41L) { policy, metric ->
            tracedEpochs += metric.epoch
            if (metric.epoch <= 1) traceNeuralBcProjection(policy, decisions, metric)
        }

        assertEquals(plain.epochMetrics, traced.epochMetrics)
        assertEquals(plain.bestEpoch, traced.bestEpoch)
        assertEquals((0..traced.epochsCompleted).toList(), tracedEpochs)
        assertContentEquals(plain.policy.artifact.stateWeights, traced.policy.artifact.stateWeights)
        assertContentEquals(plain.policy.artifact.candidateWeights, traced.policy.artifact.candidateWeights)
        assertContentEquals(plain.policy.artifact.globalQuery, traced.policy.artifact.globalQuery)
    }

    @Test
    fun `state scale intervention preserves distinctions and leaves candidates unchanged`() {
        val original = decision("game-a", 0, state = 2, candidates = listOf(4, 5), label = 0)
        val scaled = original.withStateInputScale(1.0 / 32.0)

        assertContentEquals(original.state.indices, scaled.state.indices)
        assertContentEquals(doubleArrayOf(1.0 / 32.0), scaled.state.values)
        assertEquals(original.candidates, scaled.candidates)
        assertEquals(original.labelIndex, scaled.labelIndex)
    }

    @Test
    fun `activation audit exposes saturation-induced hidden ranking aliases`() {
        val decisions = listOf(
            decision("game-a", 0, state = 0, candidates = listOf(0, 1), label = 0),
            decision("game-b", 0, state = 1, candidates = listOf(0, 1), label = 1),
        )
        val config = NeuralBcModelConfig(
            stateDimension = 2,
            candidateDimension = 2,
            hiddenDimension = 1,
        )
        val policy = CandidateConditionedNeuralPolicy.fromArtifact(
            NeuralBcModelArtifact(
                config = config,
                trainingSeed = 17L,
                bestEpoch = 9,
                stateWeights = doubleArrayOf(100.0, 100.0),
                stateBias = doubleArrayOf(0.0),
                candidateWeights = doubleArrayOf(100.0, -100.0),
                candidateBias = doubleArrayOf(0.0),
                globalQuery = doubleArrayOf(0.0),
            )
        )

        val audit = auditNeuralBcActivationSaturation(
            policy = policy,
            decisions = decisions,
            hardDecisions = setOf("game-a" to 0, "game-b" to 0),
        )

        assertEquals(1.0, audit.stateExactlySaturatedFraction)
        assertEquals(1, audit.effectiveHiddenInputRealizability.repeatedEncodedStateGroups)
        assertEquals(1, audit.effectiveHiddenInputRealizability.contradictoryRankingComponents.size)
        assertEquals(2, audit.hardDecisionsInRepeatedProjectedStateGroups)
    }

    @Test
    fun `projection trace distinguishes learned candidate collapse from a raw alias`() {
        val decisions = listOf(
            decision("learned", 0, state = 0, candidates = listOf(0, 1), label = 0),
            decision("raw", 0, state = 1, candidates = listOf(2, 2), label = 0),
        )
        val config = NeuralBcModelConfig(
            stateDimension = 3,
            candidateDimension = 3,
            hiddenDimension = 1,
        )
        val policy = CandidateConditionedNeuralPolicy.fromArtifact(
            NeuralBcModelArtifact(
                config = config,
                trainingSeed = 19L,
                bestEpoch = 0,
                stateWeights = doubleArrayOf(0.0, 0.0, 0.0),
                stateBias = doubleArrayOf(0.0),
                candidateWeights = doubleArrayOf(100.0, 100.0, -100.0),
                candidateBias = doubleArrayOf(0.0),
                globalQuery = doubleArrayOf(0.0),
            )
        )
        val point = traceNeuralBcProjection(
            policy,
            decisions,
            NeuralMemorizationEpochMetric(0, 0.0, 0, 0.0, 0, 0.0, 0.0),
        )

        assertEquals(1, point.candidateCollapse.decisionsWithLearnedCandidateCollapse)
        assertEquals(1, point.candidateCollapse.learnedCandidateCollapseGroups)
        assertEquals(2, point.candidateCollapse.candidateOccurrencesInLearnedCollapseGroups)
        assertEquals(1, point.candidateCollapse.teacherLabelsInLearnedCandidateCollapseGroups)
        assertEquals(1, point.candidateCollapse.rawCandidateAliasGroups)
    }

    private fun decision(
        gameId: String,
        decisionIndex: Int,
        state: Int,
        candidates: List<Int>,
        label: Int,
    ): EncodedBcDecision = EncodedBcDecision(
        gameId = gameId,
        decisionIndex = decisionIndex,
        decisionFamily = "ORDINARY_ACTION",
        state = sparse(state),
        candidates = candidates.map(::sparse),
        candidateFamilies = candidates.map { SemanticOperationFamily.CAST_SPELL },
        candidateIntents = candidates.map { SemanticActionIntentKind.CAST_SPELL },
        labelIndex = label,
    )

    private fun sparse(index: Int): SparseFeatureVector =
        SparseFeatureVector(intArrayOf(index), doubleArrayOf(1.0))
}
