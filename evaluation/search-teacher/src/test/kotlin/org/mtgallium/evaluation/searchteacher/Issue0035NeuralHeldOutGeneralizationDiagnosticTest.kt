package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

class Issue0035NeuralHeldOutGeneralizationDiagnosticTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `retained fitted models and exact held-out population load without training or output`() {
        val root = repositoryRoot()
        val output = temporaryDirectory.resolve("must-not-be-created")
        val report = Issue0035NeuralHeldOutGeneralizationDiagnostic(root, output).preflight(
            EvidenceStore(root).work("neural-behavioral-cloning/g20-seed20260902/corpus-manifest.json")
        )

        assertEquals(mapOf("train" to 389, "validation" to 36, "test" to 69), report.nontrivialDecisionsBySplit)
        assertEquals(listOf("teacher-corpus-000002", "teacher-corpus-000009"), report.testGames)
        assertEquals(CORRECTED_NEURAL_SEEDS, report.retainedModels.map { it.seed })
        assertTrue(report.retainedModels.all { it.trainingStrictRankingCorrect == 389 })
        assertTrue(report.retainedModels.all { it.trainingMinimumMargin > 0.0 })
        assertTrue(report.retainedModels.all { it.heldOutSmokeScores >= 2 })
        assertTrue(report.noHeldOutTrainingOrStoppingInfluence)
        assertEquals(0, report.researchTrainingUpdates)
        assertEquals(0, report.researchArtifactsEmitted)
        assertFalse(Files.exists(output))
    }

    @Test
    fun `held-out seed metrics preserve exact selection margin loss and probability`() {
        val family = SemanticOperationFamily.entries.first()
        val intent = SemanticActionIntentKind.entries.first()
        fun decision(index: Int, label: Int) = EncodedBcDecision(
            gameId = "metric-test",
            decisionIndex = index,
            decisionFamily = "TEST",
            state = SparseFeatureVector(IntArray(0), DoubleArray(0)),
            candidates = List(2) { SparseFeatureVector(IntArray(0), DoubleArray(0)) },
            candidateFamilies = List(2) { family },
            candidateIntents = List(2) { intent },
            labelIndex = label,
        )
        val decisions = listOf(decision(0, 0), decision(1, 1))
        val policy = object : NeuralBcScoringPolicy {
            override fun scores(decision: EncodedBcDecision): DoubleArray = when (decision.decisionIndex) {
                0 -> doubleArrayOf(2.0, 0.0)
                else -> doubleArrayOf(1.0, 0.0)
            }
        }

        val metric = issue0035SeedMetrics(1729, policy, decisions)

        assertEquals(1, metric.exactTeacherSelections)
        assertEquals(0.5, metric.exactTeacherActionAccuracy)
        assertEquals(1, metric.strictPositiveMargins)
        assertEquals(-1.0, metric.margins.minimum)
        assertEquals(0.5 * (-ln(1.0 / (1.0 + kotlin.math.exp(-2.0))) - ln(1.0 / (1.0 + kotlin.math.exp(1.0)))), metric.meanCrossEntropy, 1e-12)
        assertTrue(metric.meanTeacherProbability in 0.0..1.0)
    }

    private fun repositoryRoot(): Path = Path.of("").toAbsolutePath().normalize().let { current ->
        if (Files.isDirectory(current.resolve("evaluation/search-teacher"))) current else current.parent.parent
    }
}
