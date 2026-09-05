package org.mtgallium.evaluation.searchteacher

import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueEvaluator

internal const val OUTCOME_VALUE_GLOBAL_SIGNAL_PROTOCOL = "learned-outcome-value-global-signal-v1"
internal const val OUTCOME_VALUE_GLOBAL_SIGNAL_DOCUMENT_KIND = "outcome-value-global-signal-report-v1"

/**
 * A non-persistent row used only by the global-signal diagnostic.  Its additional metadata is
 * derived from the same root-projected nonterminal information state as [example], and never
 * enters fitting or the checkpoint.
 */
internal data class OutcomeValueSignalExample(
    val example: OutcomeValueExample,
    val phase: String,
    val rootSeat: String,
    val visibleHeuristicPrediction: Double,
) {
    init {
        require(phase.isNotBlank())
        require(rootSeat.isNotBlank())
        require(visibleHeuristicPrediction.isFinite())
    }
}

@Serializable
internal data class OutcomeValueSignalCalibrationBin(
    val id: String,
    val lowerInclusive: Double,
    val upperExclusive: Double?,
    val rows: Int,
    val games: Int,
    val meanPrediction: Double?,
    val meanTerminalPayoff: Double?,
    val equalFrameMse: Double?,
    val equalGameMse: Double?,
)

@Serializable
internal data class OutcomeValueSignalMetrics(
    val rows: Int,
    val games: Int,
    val equalFrameMse: Double,
    val equalGameMse: Double,
    val gameMeanPredictionTerminalPayoffPearson: Double?,
    val equalFramePredictionMean: Double,
    val equalFramePredictionStandardDeviation: Double,
    val equalGamePredictionMean: Double,
    val equalGamePredictionStandardDeviation: Double,
    val predictionMinimum: Double,
    val predictionMaximum: Double,
    val equalFrameSignAccuracy: Double?,
    val equalGameSignAccuracy: Double?,
)

@Serializable
internal data class OutcomeValueSignalSlice(
    val id: String,
    val metrics: OutcomeValueSignalMetrics,
)

@Serializable
internal data class OutcomeValueTrajectoryVariation(
    val games: Int,
    /** Mean of each game's population prediction variance: every game has equal weight. */
    val equalGameMeanWithinTrajectoryPredictionVariance: Double,
    /** Population variance of game-mean predictions: every game has equal weight. */
    val equalGameBetweenTrajectoryPredictionVariance: Double,
    /** The corresponding all-frame variance, reported separately rather than mixed into the decomposition. */
    val equalFramePredictionVariance: Double,
)

@Serializable
internal data class OutcomeValueSignalProgressSlice(
    val id: String,
    val lowerInclusive: Double,
    val upperExclusive: Double?,
    /** Null means this fixed progress region had no retained frames in this partition. */
    val metrics: OutcomeValueSignalMetrics?,
)

@Serializable
internal data class OutcomeValueSignalTemporalDiagnostics(
    val games: Int,
    /** A singleton has normalized progress 0.0 but no within-trajectory progress correlation. */
    val singletonGames: Int,
    /** Games with at least two frames and nonconstant prediction contribute one correlation each. */
    val equalGameProgressCorrelationGames: Int,
    /** Mean of eligible per-game prediction/progress Pearson values; every eligible game has equal weight. */
    val equalGameMeanWithinTrajectoryPredictionProgressPearson: Double?,
    /** Secondary all-frame correlation; games with more retained frames receive more weight. */
    val equalFramePredictionProgressPearson: Double?,
    /** Fixed normalized-progress slices: [0,1/3), [1/3,2/3), and [2/3,1]. */
    val byNormalizedFrameProgress: List<OutcomeValueSignalProgressSlice>,
    /** Across games, one game-mean prediction and one retained-frame count per game. */
    val equalGameGameMeanPredictionFrameCountPearson: Double?,
    /** Secondary all-frame version; repeated game frame counts weight longer games more. */
    val equalFramePredictionFrameCountPearson: Double?,
    /** Label/length confounding check, with one completed payoff and frame count per game. */
    val equalGameTerminalPayoffFrameCountPearson: Double?,
    /** Secondary all-frame version; repeated terminal payoffs weight longer games more. */
    val equalFrameTerminalPayoffFrameCountPearson: Double?,
)

@Serializable
internal data class OutcomeValueSignalModelReport(
    val modelId: String,
    val overall: OutcomeValueSignalMetrics,
    val calibrationByEqualGamePrediction: List<OutcomeValueSignalCalibrationBin>,
    val byPhase: List<OutcomeValueSignalSlice>,
    val byRootSeat: List<OutcomeValueSignalSlice>,
    val byActorRelation: List<OutcomeValueSignalSlice>,
    val trajectoryVariation: OutcomeValueTrajectoryVariation,
    val temporal: OutcomeValueSignalTemporalDiagnostics,
)

@Serializable
internal data class OutcomeValueSignalNegativeControls(
    /** Cyclically rotate completed-game labels in canonical pair/leg order; features/scores stay fixed. */
    val wholeGameLabelRotation: OutcomeValueSignalMetrics,
    /** Cyclically rotate game-mean predictions in canonical order; this deliberately removes game alignment. */
    val gameMeanPredictionRotationPearson: Double?,
    val gameMeanPredictionRotationSignAccuracy: Double?,
    val gameMeanPredictionRotationMse: Double,
)

@Serializable
internal data class OutcomeValueGlobalSignalPartitionReport(
    val split: String,
    val learned: OutcomeValueSignalModelReport,
    /** Same root-projected information state, scored by the existing visible-state heuristic. */
    val visibleHeuristic: OutcomeValueSignalModelReport,
    /** One frozen TRAIN-only equal-game terminal-payoff mean, reused unchanged in every split. */
    val frozenTrainConstant: OutcomeValueSignalModelReport,
    val learnedNegativeControls: OutcomeValueSignalNegativeControls,
)

@Serializable
internal data class OutcomeValueGlobalSignalReport(
    val documentKind: String = OUTCOME_VALUE_GLOBAL_SIGNAL_DOCUMENT_KIND,
    val schemaVersion: Int = 4,
    val diagnosticRunIdentity: String,
    val trainingRunIdentity: String,
    val validationRunIdentity: String,
    /** Recorded historical TEST report identity authenticated before this diagnostic opens TEST bundles. */
    val historicalTestRunIdentity: String,
    val checkpointPayloadSha256: String,
    val corpusIdentity: String,
    val pairSplitIdentity: String,
    /** Explicit fixed-bin/calculation protocol, independently bound into diagnostic evidence. */
    val analysisSpecification: String = OUTCOME_VALUE_GLOBAL_SIGNAL_PROTOCOL +
        ":equal-frame-and-equal-game-v1:fixed-score-bins-5:canonical-game-rotation-1:temporal-progress-length-v1:frozen-train-constant-v1",
    val partitions: List<OutcomeValueGlobalSignalPartitionReport>,
) {
    init {
        require(documentKind == OUTCOME_VALUE_GLOBAL_SIGNAL_DOCUMENT_KIND)
        require(schemaVersion == 4)
        require(partitions.map { it.split }.distinct().size == partitions.size)
    }
}

/**
 * Pure diagnostic arithmetic. It never fits, updates, or selects a checkpoint. Production callers
 * receive its rows only from the verified corpus/checkpoint host; synthetic tests may use it
 * directly to establish its aggregation and negative-control semantics.
 */
internal object OutcomeValueGlobalSignalDiagnostic {
    fun evaluate(
        split: OutcomeStateCorpusSplit,
        rows: Iterable<OutcomeValueSignalExample>,
        learned: LearnedOutcomeValueEvaluator,
        frozenTrainConstantPrediction: Double,
    ): OutcomeValueGlobalSignalPartitionReport {
        val ordered = rows.sortedWith(signalRowComparator)
        require(ordered.isNotEmpty()) { "Global-signal diagnostic requires at least one row" }
        require(frozenTrainConstantPrediction.isFinite()) { "Frozen TRAIN-only constant prediction must be finite" }
        val learnedScores = ordered.map { learned.evaluate(it.example.features) }
        val heuristicScores = ordered.map(OutcomeValueSignalExample::visibleHeuristicPrediction)
        val constantScores = List(ordered.size) { frozenTrainConstantPrediction }
        return OutcomeValueGlobalSignalPartitionReport(
            split = split.name,
            learned = modelReport("learned-outcome-checkpoint-v1", ordered, learnedScores),
            visibleHeuristic = modelReport("mono-red-visible-board-v2", ordered, heuristicScores),
            frozenTrainConstant = modelReport("frozen-train-equal-game-terminal-payoff-v1", ordered, constantScores),
            learnedNegativeControls = negativeControls(ordered, learnedScores),
        )
    }

    private fun modelReport(
        modelId: String,
        rows: List<OutcomeValueSignalExample>,
        scores: List<Double>,
    ): OutcomeValueSignalModelReport {
        require(rows.size == scores.size)
        ensureFinite(scores, "diagnostic predictions")
        return OutcomeValueSignalModelReport(
            modelId = modelId,
            overall = metrics(rows, scores),
            calibrationByEqualGamePrediction = calibration(rows, scores),
            byPhase = slices(rows, scores) { it.phase },
            byRootSeat = slices(rows, scores) { it.rootSeat },
            byActorRelation = slices(rows, scores) { it.example.actorRelation.name },
            trajectoryVariation = trajectoryVariation(rows, scores),
            temporal = temporalDiagnostics(rows, scores),
        )
    }

    private fun metrics(rows: List<OutcomeValueSignalExample>, scores: List<Double>): OutcomeValueSignalMetrics {
        require(rows.size == scores.size && rows.isNotEmpty())
        val games = gameRows(rows, scores)
        val frameLosses = rows.indices.map { squaredError(scores[it], rows[it].example.actualTerminalPayoff) }
        val gameMeanScores = games.map { (_, game) -> mean(game.map { it.score }) }
        val labels = games.map { (_, game) -> terminalPayoff(game) }
        val gameMse = games.map { (_, game) -> mean(game.map { squaredError(it.score, it.row.example.actualTerminalPayoff) }) }
        return OutcomeValueSignalMetrics(
            rows = rows.size,
            games = games.size,
            equalFrameMse = mean(frameLosses),
            equalGameMse = mean(gameMse),
            gameMeanPredictionTerminalPayoffPearson = pearson(gameMeanScores, labels),
            equalFramePredictionMean = mean(scores),
            equalFramePredictionStandardDeviation = standardDeviation(scores),
            equalGamePredictionMean = mean(gameMeanScores),
            equalGamePredictionStandardDeviation = standardDeviation(gameMeanScores),
            predictionMinimum = scores.min(),
            predictionMaximum = scores.max(),
            equalFrameSignAccuracy = signAccuracy(scores, rows.map { it.example.actualTerminalPayoff }),
            equalGameSignAccuracy = signAccuracy(gameMeanScores, labels),
        )
    }

    /** Fixed equal-width bins over the evaluator's bounded output interval [-1, 1]. */
    private fun calibration(
        rows: List<OutcomeValueSignalExample>,
        scores: List<Double>,
    ): List<OutcomeValueSignalCalibrationBin> {
        val games = gameRows(rows, scores).map { (_, game) ->
            GamePrediction(
                game.first().row.example.gameKey,
                mean(game.map { it.score }),
                terminalPayoff(game),
                game.size,
                mean(game.map { squaredError(it.score, it.row.example.actualTerminalPayoff) }),
            )
        }
        return calibrationEdges.zipWithNext().mapIndexed { index, (lower, upper) ->
            val isLast = index == calibrationEdges.lastIndex - 1
            val selected = games.filter { game -> game.prediction >= lower && (isLast || game.prediction < upper) }
            OutcomeValueSignalCalibrationBin(
                id = "bin-$index",
                lowerInclusive = lower,
                upperExclusive = if (isLast) null else upper,
                rows = selected.sumOf(GamePrediction::rows),
                games = selected.size,
                meanPrediction = selected.takeIf { it.isNotEmpty() }?.let { mean(it.map(GamePrediction::prediction)) },
                meanTerminalPayoff = selected.takeIf { it.isNotEmpty() }?.let { mean(it.map(GamePrediction::label)) },
                equalFrameMse = selected.takeIf { it.isNotEmpty() }?.let { selectedGames ->
                    selectedGames.sumOf { it.rows * it.frameMse }.div(selectedGames.sumOf(GamePrediction::rows))
                },
                equalGameMse = selected.takeIf { it.isNotEmpty() }?.let { mean(it.map(GamePrediction::frameMse)) },
            )
        }
    }

    private fun slices(
        rows: List<OutcomeValueSignalExample>,
        scores: List<Double>,
        key: (OutcomeValueSignalExample) -> String,
    ): List<OutcomeValueSignalSlice> = rows.indices.groupBy { key(rows[it]) }.toSortedMap().map { (id, indices) ->
        OutcomeValueSignalSlice(id, metrics(indices.map(rows::get), indices.map(scores::get)))
    }

    private fun trajectoryVariation(
        rows: List<OutcomeValueSignalExample>,
        scores: List<Double>,
    ): OutcomeValueTrajectoryVariation {
        val games = gameRows(rows, scores)
        val gameMeans = games.map { (_, game) -> mean(game.map { it.score }) }
        return OutcomeValueTrajectoryVariation(
            games = games.size,
            equalGameMeanWithinTrajectoryPredictionVariance = mean(games.map { (_, game) -> variance(game.map { it.score }) }),
            equalGameBetweenTrajectoryPredictionVariance = variance(gameMeans),
            equalFramePredictionVariance = variance(scores),
        )
    }

    private fun temporalDiagnostics(
        rows: List<OutcomeValueSignalExample>,
        scores: List<Double>,
    ): OutcomeValueSignalTemporalDiagnostics {
        val games = gameRows(rows, scores)
        val gameTemporal = games.map { (_, game) -> temporalGame(game) }
        val progressCorrelations = gameTemporal.mapNotNull(TemporalGame::predictionProgressPearson)
        val maximumFrameIndices = maximumFrameIndices(games)
        val frameProgress = rows.map { normalizedProgress(it, maximumFrameIndices) }
        val frameCounts = rows.indices.map { rowIndex ->
            gameTemporal.single { game -> game.key == rows[rowIndex].example.gameKey }.frames.toDouble()
        }
        val gameMeans = gameTemporal.map(TemporalGame::meanPrediction)
        val gameFrameCounts = gameTemporal.map { it.frames.toDouble() }
        val gameLabels = gameTemporal.map(TemporalGame::terminalPayoff)
        val frameLabels = rows.map { it.example.actualTerminalPayoff }
        return OutcomeValueSignalTemporalDiagnostics(
            games = games.size,
            singletonGames = gameTemporal.count { it.frames == 1 },
            equalGameProgressCorrelationGames = progressCorrelations.size,
            equalGameMeanWithinTrajectoryPredictionProgressPearson = progressCorrelations.takeIf { it.isNotEmpty() }?.let(::mean),
            equalFramePredictionProgressPearson = pearson(scores, frameProgress),
            byNormalizedFrameProgress = progressSlices(rows, scores, games),
            equalGameGameMeanPredictionFrameCountPearson = pearson(gameMeans, gameFrameCounts),
            equalFramePredictionFrameCountPearson = pearson(scores, frameCounts),
            equalGameTerminalPayoffFrameCountPearson = pearson(gameLabels, gameFrameCounts),
            equalFrameTerminalPayoffFrameCountPearson = pearson(frameLabels, frameCounts),
        )
    }

    private fun progressSlices(
        rows: List<OutcomeValueSignalExample>,
        scores: List<Double>,
        games: List<Pair<OutcomeValueGameKey, List<ScoredRow>>>,
    ): List<OutcomeValueSignalProgressSlice> {
        val maximumFrameIndices = maximumFrameIndices(games)
        val progressByRow = rows.indices.associateWith { normalizedProgress(rows[it], maximumFrameIndices) }
        return progressEdges.zipWithNext().mapIndexed { index, (lower, upper) ->
            val last = index == progressEdges.lastIndex - 1
            val indices = rows.indices.filter { rowIndex ->
                progressByRow.getValue(rowIndex) >= lower && (last || progressByRow.getValue(rowIndex) < upper)
            }
            OutcomeValueSignalProgressSlice(
                id = progressSliceIds[index],
                lowerInclusive = lower,
                upperExclusive = if (last) null else upper,
                metrics = indices.takeIf { it.isNotEmpty() }?.let {
                    metrics(it.map(rows::get), it.map(scores::get))
                },
            )
        }
    }

    private fun temporalGame(game: List<ScoredRow>): TemporalGame {
        val key = game.first().row.example.gameKey
        val maximumFrameIndex = game.maxOf { it.row.example.frameIndex }
        val progress = game.map { scored ->
            if (maximumFrameIndex == 0) 0.0 else scored.row.example.frameIndex.toDouble() / maximumFrameIndex
        }
        return TemporalGame(
            key = key,
            frames = game.size,
            meanPrediction = mean(game.map { it.score }),
            terminalPayoff = terminalPayoff(game),
            predictionProgressPearson = pearson(game.map { it.score }, progress),
        )
    }

    private fun maximumFrameIndices(
        games: List<Pair<OutcomeValueGameKey, List<ScoredRow>>>,
    ): Map<OutcomeValueGameKey, Int> = games.associate { (key, game) ->
        key to game.maxOf { it.row.example.frameIndex }
    }

    private fun normalizedProgress(
        row: OutcomeValueSignalExample,
        maximumFrameIndices: Map<OutcomeValueGameKey, Int>,
    ): Double {
        val maximumFrameIndex = requireNotNull(maximumFrameIndices[row.example.gameKey])
        return if (maximumFrameIndex == 0) 0.0 else row.example.frameIndex.toDouble() / maximumFrameIndex
    }

    private fun negativeControls(
        rows: List<OutcomeValueSignalExample>,
        scores: List<Double>,
    ): OutcomeValueSignalNegativeControls {
        val games = gameRows(rows, scores)
        require(games.size >= 2) { "Global-signal negative controls require at least two games" }
        val labels = games.map { (_, game) -> terminalPayoff(game) }
        val rotatedLabels = labels.drop(1) + labels.take(1)
        val rotatedRows = games.flatMapIndexed { gameIndex, (_, game) ->
            game.map { it.row.copy(example = it.row.example.copy(actualTerminalPayoff = rotatedLabels[gameIndex])) }
        }
        val rotatedScores = games.flatMap { (_, game) -> game.map { it.score } }
        val rotatedMetrics = metrics(rotatedRows, rotatedScores)
        val means = games.map { (_, game) -> mean(game.map { it.score }) }
        val rotatedMeans = means.drop(1) + means.take(1)
        return OutcomeValueSignalNegativeControls(
            wholeGameLabelRotation = rotatedMetrics,
            gameMeanPredictionRotationPearson = pearson(rotatedMeans, labels),
            gameMeanPredictionRotationSignAccuracy = signAccuracy(rotatedMeans, labels),
            gameMeanPredictionRotationMse = mean(rotatedMeans.indices.map { squaredError(rotatedMeans[it], labels[it]) }),
        )
    }

    private fun gameRows(
        rows: List<OutcomeValueSignalExample>,
        scores: List<Double>,
    ): List<Pair<OutcomeValueGameKey, List<ScoredRow>>> = rows.indices
        .map { ScoredRow(rows[it], scores[it]) }
        .groupBy { it.row.example.gameKey }
        .toSortedMap()
        .map { (key, game) -> key to game }

    private fun terminalPayoff(game: List<ScoredRow>): Double {
        val labels = game.map { it.row.example.actualTerminalPayoff }.distinct()
        require(labels.size == 1) { "A diagnostic game must carry one terminal payoff" }
        return labels.single()
    }

    private fun pearson(left: List<Double>, right: List<Double>): Double? {
        require(left.size == right.size)
        if (left.size < 2) return null
        val leftVariance = variance(left)
        val rightVariance = variance(right)
        if (leftVariance == 0.0 || rightVariance == 0.0) return null
        val covariance = left.indices.sumOf { (left[it] - mean(left)) * (right[it] - mean(right)) } / left.size
        return (covariance / sqrt(leftVariance * rightVariance)).also { require(it.isFinite()) }
    }

    private fun signAccuracy(predictions: List<Double>, labels: List<Double>): Double? {
        require(predictions.size == labels.size)
        val eligible = predictions.indices.filter { labels[it] != 0.0 }
        return eligible.takeIf { it.isNotEmpty() }?.let { indices ->
            indices.count { predictions[it].compareTo(0.0) == labels[it].compareTo(0.0) }.toDouble() / indices.size
        }
    }

    private fun squaredError(prediction: Double, label: Double): Double =
        (prediction - label).let { it * it }.also { require(it.isFinite()) }

    private fun mean(values: List<Double>): Double {
        require(values.isNotEmpty())
        return values.average().also { require(it.isFinite()) }
    }

    private fun variance(values: List<Double>): Double {
        require(values.isNotEmpty())
        val mean = mean(values)
        return values.sumOf { (it - mean) * (it - mean) }.div(values.size).also { require(it.isFinite() && it >= 0.0) }
    }

    private fun standardDeviation(values: List<Double>): Double = sqrt(variance(values))

    private fun ensureFinite(values: List<Double>, field: String) {
        require(values.all(Double::isFinite)) { "$field must be finite" }
    }

    private data class ScoredRow(val row: OutcomeValueSignalExample, val score: Double)
    private data class GamePrediction(
        val key: OutcomeValueGameKey,
        val prediction: Double,
        val label: Double,
        val rows: Int,
        val frameMse: Double,
    )
    private data class TemporalGame(
        val key: OutcomeValueGameKey,
        val frames: Int,
        val meanPrediction: Double,
        val terminalPayoff: Double,
        val predictionProgressPearson: Double?,
    )

    private val signalRowComparator = compareBy<OutcomeValueSignalExample>(
        { it.example.pairIndex }, { if (it.example.leg == "a") 0 else 1 }, { it.example.frameIndex },
    )
    private val calibrationEdges = listOf(-1.0, -0.6, -0.2, 0.2, 0.6, 1.0)
    private val progressEdges = listOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0)
    private val progressSliceIds = listOf("EARLY", "MIDDLE", "LATE")
}
