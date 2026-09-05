package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.searchteacher.LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueCheckpointPayload
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueEvaluator
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatures
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueTrainingBinding
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherSuites

@org.junit.jupiter.api.Tag("public-source")
class OutcomeValueGlobalSignalDiagnosticTest {
    @Test
    fun `reports explicit frame game slice calibration and trajectory aggregates deterministically`() {
        val first = OutcomeValueGlobalSignalDiagnostic.evaluate(OutcomeStateCorpusSplit.TRAIN, rows(), evaluator(), 0.25)
        val second = OutcomeValueGlobalSignalDiagnostic.evaluate(OutcomeStateCorpusSplit.TRAIN, rows().reversed(), evaluator(), 0.25)

        assertEquals(first, second)
        assertEquals(4, first.learned.overall.rows)
        assertEquals(2, first.learned.overall.games)
        assertEquals(0.125, first.learned.overall.equalFrameMse, 1e-12)
        assertEquals(0.125, first.learned.overall.equalGameMse, 1e-12)
        assertEquals(1.0, first.learned.overall.gameMeanPredictionTerminalPayoffPearson)
        assertEquals(1.0, first.learned.overall.equalFrameSignAccuracy)
        assertEquals(2, first.learned.byRootSeat.size)
        assertEquals(2, first.learned.byActorRelation.size)
        assertEquals(2, first.learned.byPhase.size)
        assertEquals(0.0625, first.learned.trajectoryVariation.equalGameMeanWithinTrajectoryPredictionVariance, 1e-12)
        assertEquals(0.5625, first.learned.trajectoryVariation.equalGameBetweenTrajectoryPredictionVariance, 1e-12)
        assertEquals(5, first.learned.calibrationByEqualGamePrediction.size)
        assertTrue(first.learned.calibrationByEqualGamePrediction.sumOf { it.games } == 2)
        assertEquals(0.25, first.frozenTrainConstant.overall.equalFramePredictionMean)
        assertEquals(1.0625, first.frozenTrainConstant.overall.equalFrameMse, 1e-12)
        assertEquals(2, first.learned.temporal.equalGameProgressCorrelationGames)
        assertEquals(1.0, first.learned.temporal.equalGameMeanWithinTrajectoryPredictionProgressPearson)
        assertEquals(listOf("EARLY", "MIDDLE", "LATE"), first.learned.temporal.byNormalizedFrameProgress.map { it.id })
    }

    @Test
    fun `predeclared temporal length diagnostics use equal game weighting and define singleton progress`() {
        val temporal = OutcomeValueGlobalSignalDiagnostic
            .evaluate(OutcomeStateCorpusSplit.TRAIN, temporalRows(), evaluator(), 0.0)
            .learned.temporal

        assertEquals(3, temporal.games)
        assertEquals(1, temporal.singletonGames)
        assertEquals(2, temporal.equalGameProgressCorrelationGames)
        assertEquals(1.0, temporal.equalGameMeanWithinTrajectoryPredictionProgressPearson)
        assertTrue(temporal.equalFramePredictionProgressPearson != null)
        assertEquals(3, temporal.byNormalizedFrameProgress.size)
        assertTrue(temporal.byNormalizedFrameProgress.all { (it.metrics?.rows ?: 0) > 0 })
        assertTrue(temporal.equalGameGameMeanPredictionFrameCountPearson != null)
        assertTrue(temporal.equalGameTerminalPayoffFrameCountPearson != null)
    }

    @Test
    fun `whole game label and game mean prediction rotations break aligned signal without fitting`() {
        val report = OutcomeValueGlobalSignalDiagnostic.evaluate(OutcomeStateCorpusSplit.VALIDATION, rows(), evaluator(), 0.25)
        val controls = report.learnedNegativeControls

        assertEquals(-1.0, controls.wholeGameLabelRotation.gameMeanPredictionTerminalPayoffPearson)
        assertEquals(-1.0, controls.gameMeanPredictionRotationPearson)
        assertEquals(0.0, controls.wholeGameLabelRotation.equalGameSignAccuracy)
        assertEquals(0.0, controls.gameMeanPredictionRotationSignAccuracy)
        assertTrue(controls.wholeGameLabelRotation.equalGameMse > report.learned.overall.equalGameMse)
    }

    @Test
    fun `cli registers global signal command with corpus gate and fresh output inputs`() {
        assertEquals(
            "learned-outcome-value-global-signal",
            SearchTeacherSuites.require("learned-outcome-value-global-signal").id,
        )
        val parsed = SearchTeacherCli.parse(
            arrayOf(
                "--suite", "learned-outcome-value-global-signal",
                "--outcome-corpus", "completed-corpus",
                "--learned-gate", "completed-gate",
                "--output", "fresh-diagnostic",
            ),
        )
        assertTrue(parsed.outcomeCorpus?.isAbsolute == true)
        assertTrue(parsed.learnedGate?.isAbsolute == true)
        assertTrue(parsed.outputPath?.isAbsolute == true)
    }

    private fun rows(): List<OutcomeValueSignalExample> = listOf(
        row(1, "a", 0, OutcomeValueActorRelation.ROOT, -1.0, -1.0, "MAIN", "p0", -0.8),
        row(1, "a", 1, OutcomeValueActorRelation.OPPONENT, -1.0, -0.5, "COMBAT", "p0", -0.4),
        row(1, "b", 0, OutcomeValueActorRelation.ROOT, 1.0, 0.5, "MAIN", "p1", 0.4),
        row(1, "b", 1, OutcomeValueActorRelation.OPPONENT, 1.0, 1.0, "COMBAT", "p1", 0.8),
    )

    private fun temporalRows(): List<OutcomeValueSignalExample> = listOf(
        row(1, "a", 0, OutcomeValueActorRelation.ROOT, 1.0, 0.0, "MAIN", "p0", 0.0),
        row(1, "a", 1, OutcomeValueActorRelation.ROOT, 1.0, 0.5, "MAIN", "p0", 0.5),
        row(1, "a", 2, OutcomeValueActorRelation.ROOT, 1.0, 1.0, "MAIN", "p0", 1.0),
        row(1, "b", 0, OutcomeValueActorRelation.ROOT, -1.0, -1.0, "MAIN", "p1", -1.0),
        row(1, "b", 1, OutcomeValueActorRelation.ROOT, -1.0, -0.5, "MAIN", "p1", -0.5),
        row(2, "a", 0, OutcomeValueActorRelation.ROOT, 0.0, 0.1, "MAIN", "p0", 0.1),
    )

    private fun row(
        pair: Int,
        leg: String,
        frame: Int,
        relation: OutcomeValueActorRelation,
        label: Double,
        feature: Double,
        phase: String,
        rootSeat: String,
        heuristic: Double,
    ): OutcomeValueSignalExample = OutcomeValueSignalExample(
        OutcomeValueExample(pair, leg, frame, relation, features(feature), label),
        phase,
        rootSeat,
        heuristic,
    )

    private fun evaluator(): LearnedOutcomeValueEvaluator = LearnedOutcomeValueEvaluator.fromCheckpoint(
        LearnedOutcomeValueCheckpointPayload(
            training = LearnedOutcomeValueTrainingBinding(
                corpusIdentity = identity("corpus"),
                pairSplitIdentity = identity("split"),
                learnerConfigurationIdentity = identity("config"),
                projectionIdentity = identity("projection"),
                rootBehaviorPolicyIdentity = identity("root"),
                opponentBehaviorPolicyIdentity = identity("opponent"),
                environmentProfileIdentity = identity("environment"),
            ),
            bias = 0.0,
            weights = mapOf("state/signal" to 1.0),
        ),
    )

    private fun features(value: Double): LearnedOutcomeValueFeatures {
        val constructor = LearnedOutcomeValueFeatures::class.java.getDeclaredConstructor(String::class.java, Map::class.java)
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1, mapOf("state/signal" to value)) as LearnedOutcomeValueFeatures
    }

    private fun identity(name: String): String = "synthetic-$name-sha256:" + "a".repeat(64)
}
