package org.mtgallium.evaluation.searchteacher

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

class NeuralCohortGradientDiagnosticTest {
    @Test
    fun `analytical cohort gradient matches the actual scalar objective`() {
        val config = NeuralBcModelConfig(stateDimension = 5, candidateDimension = 5, hiddenDimension = 3)
        val artifact = CandidateConditionedNeuralPolicy.initialize(config, seed = 73L).artifact
        val decisions = listOf(decision(0, 0), decision(1, 1))
        val gradient = neuralBcObjectiveGradient(artifact, decisions)
        val epsilon = 1e-6

        listOf(
            artifact.stateWeights to gradient.stateWeights,
            artifact.stateBias to gradient.stateBias,
            artifact.candidateWeights to gradient.candidateWeights,
            artifact.candidateBias to gradient.candidateBias,
            artifact.globalQuery to gradient.globalQuery,
        ).forEach { (parameters, gradients) ->
            val active = gradients.indices.maxBy { abs(gradients[it]) }
            val original = parameters[active]
            parameters[active] = original + epsilon
            val plus = objective(artifact, decisions)
            parameters[active] = original - epsilon
            val minus = objective(artifact, decisions)
            parameters[active] = original
            assertEquals(
                gradients[active],
                (plus - minus) / (2.0 * epsilon),
                absoluteTolerance = 2e-8,
            )
        }
    }

    @Test
    fun `cohort geometry preserves combined objective weighting and size normalized comparison`() {
        val config = NeuralBcModelConfig(stateDimension = 5, candidateDimension = 5, hiddenDimension = 3)
        val artifact = CandidateConditionedNeuralPolicy.initialize(config, seed = 73L).artifact
        val anchor = listOf(decision(0, 0), decision(1, 1), decision(2, 0))
        val added = listOf(decision(3, 1))
        val geometry = neuralCohortGradientGeometry(artifact, anchor, added, "test")
        val anchorMean = neuralBcObjectiveGradient(artifact, anchor)
        val addedMean = neuralBcObjectiveGradient(artifact, added)
        val combined = anchorMean.scaled(3.0 / 4.0).plus(addedMean.scaled(1.0 / 4.0))
        val all = geometry.groups.single { it.parameterGroup == "ALL_PARAMETERS" }

        assertEquals(norm(anchorMean) * 3.0 / 4.0, all.actualCombinedObjectiveAnchorContributionNorm, 1e-12)
        assertEquals(norm(addedMean) / 4.0, all.actualCombinedObjectiveAddedContributionNorm, 1e-12)
        assertEquals(norm(combined), all.actualCombinedObjectiveGradientNorm, 1e-12)
        assertEquals(norm(anchorMean), all.sizeNormalizedAnchorGradientNorm, 1e-12)
        assertEquals(norm(addedMean), all.sizeNormalizedAddedGradientNorm, 1e-12)
        assertTrue(all.sizeNormalizedCosine!! in -1.0..1.0)
    }

    @Test
    fun `analytical score-difference gradient matches the vulnerable ranking margin`() {
        val config = NeuralBcModelConfig(stateDimension = 5, candidateDimension = 5, hiddenDimension = 3)
        val artifact = CandidateConditionedNeuralPolicy.initialize(config, seed = 73L).artifact
        val decision = decision(2, 0)
        val gradient = neuralBcScoreDifferenceGradient(artifact, decision, 0, 1)
        val epsilon = 1e-6

        listOf(
            artifact.stateWeights to gradient.stateWeights,
            artifact.stateBias to gradient.stateBias,
            artifact.candidateWeights to gradient.candidateWeights,
            artifact.candidateBias to gradient.candidateBias,
            artifact.globalQuery to gradient.globalQuery,
        ).forEach { (parameters, gradients) ->
            val active = gradients.indices.maxBy { abs(gradients[it]) }
            val original = parameters[active]
            parameters[active] = original + epsilon
            val plus = scoreDifference(artifact, decision, 0, 1)
            parameters[active] = original - epsilon
            val minus = scoreDifference(artifact, decision, 0, 1)
            parameters[active] = original
            assertEquals(
                gradients[active],
                (plus - minus) / (2.0 * epsilon),
                absoluteTolerance = 2e-8,
            )
        }
    }

    private fun objective(
        artifact: NeuralBcModelArtifact,
        decisions: List<EncodedBcDecision>,
    ): Double {
        val policy = CandidateConditionedNeuralPolicy.fromArtifact(artifact)
        return decisions.map { neuralBcCrossEntropy(policy.scores(it), it.labelIndex) }.average()
    }

    private fun scoreDifference(
        artifact: NeuralBcModelArtifact,
        decision: EncodedBcDecision,
        winner: Int,
        loser: Int,
    ): Double {
        val scores = CandidateConditionedNeuralPolicy.fromArtifact(artifact).scores(decision)
        return scores[winner] - scores[loser]
    }

    private fun norm(gradient: DenseNeuralBcGradient): Double {
        val arrays = listOf(
            gradient.stateWeights,
            gradient.stateBias,
            gradient.candidateWeights,
            gradient.candidateBias,
            gradient.globalQuery,
        )
        return sqrt(arrays.sumOf { array -> array.sumOf { it * it } })
    }

    private fun decision(index: Int, label: Int): EncodedBcDecision = EncodedBcDecision(
        gameId = "gradient-test",
        decisionIndex = index,
        decisionFamily = "ORDINARY_ACTION",
        state = sparse(index % 3, 1.0 + index / 10.0),
        candidates = listOf(sparse(3, 1.0), sparse(4, 1.0 + index / 20.0)),
        candidateFamilies = List(2) { SemanticOperationFamily.CAST_SPELL },
        candidateIntents = List(2) { SemanticActionIntentKind.CAST_SPELL },
        labelIndex = label,
    )

    private fun sparse(index: Int, value: Double): SparseFeatureVector =
        SparseFeatureVector(intArrayOf(index), doubleArrayOf(value))
}
