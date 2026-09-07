package org.mtgallium.evaluation.searchteacher

import kotlin.math.exp
import kotlin.math.ln
import kotlinx.serialization.Serializable

@Serializable
internal data class CloningGeneralizationRow(
    val gameId: String,
    val pairGroup: String,
    val decisionIndex: Int,
    val candidateCount: Int,
    val teacherIndex: Int,
    val selectedIndex: Int,
    val uncappedCrossEntropy: Double,
)

@Serializable
internal data class CloningGeneralizationMetrics(
    val decisions: Int,
    val games: Int,
    val pairGroups: Int,
    val exactActions: Int,
    val exactActionAgreement: Double,
    val meanUncappedCrossEntropy: Double,
    val rows: List<CloningGeneralizationRow>,
)

/** Final diagnostic NLL, distinct from the trainer's probability-floored selection criterion. */
internal fun cloningUncappedCrossEntropy(scores: DoubleArray, teacherIndex: Int): Double {
    require(scores.isNotEmpty() && teacherIndex in scores.indices && scores.all(Double::isFinite))
    val maximum = scores.max()
    val loss = maximum - scores[teacherIndex] + ln(scores.sumOf { exp(it - maximum) })
    require(loss.isFinite())
    return loss
}

/** Evaluate exactly one declared population; callers compare policies on identical coordinates. */
internal fun evaluateCloningGeneralization(
    policy: NeuralBcScoringPolicy,
    decisions: List<EncodedBcDecision>,
    groupByGame: Map<String, String>,
): CloningGeneralizationMetrics {
    require(decisions.isNotEmpty() && decisions.all { it.candidateCount >= 2 })
    require(decisions.map { it.gameId to it.decisionIndex }.distinct().size == decisions.size)
    val rows = decisions.map { decision ->
        val scores = policy.scores(decision)
        require(scores.size == decision.candidateCount)
        val loss = cloningUncappedCrossEntropy(scores, decision.labelIndex)
        CloningGeneralizationRow(decision.gameId, groupByGame.getValue(decision.gameId), decision.decisionIndex,
            decision.candidateCount, decision.labelIndex, scores.indices.maxBy { scores[it] }, loss)
    }
    return summarizeCloningGeneralization(rows)
}

internal fun summarizeCloningGeneralization(rows: List<CloningGeneralizationRow>): CloningGeneralizationMetrics {
    require(rows.isNotEmpty() && rows.map { it.gameId to it.decisionIndex }.distinct().size == rows.size)
    val correct = rows.count { it.teacherIndex == it.selectedIndex }
    return CloningGeneralizationMetrics(rows.size, rows.map { it.gameId }.distinct().size,
        rows.map { it.pairGroup }.distinct().size, correct, correct.toDouble() / rows.size,
        rows.map { it.uncappedCrossEntropy }.average(), rows)
}

/** A counterpart leg inherits its source group's frozen assignment, never a fresh row-level split. */
internal fun cloningGroupPartitions(
    originalSplitByGame: Map<String, String>,
    originalGroupByGame: Map<String, String>,
    seedByGroup: Map<String, Long>,
): Map<String, String> {
    require(originalSplitByGame.isNotEmpty())
    val result = linkedMapOf<String, String>()
    val partitionBySeed = mutableMapOf<Long, String>()
    originalSplitByGame.forEach { (game, partition) ->
        require(partition in setOf("TRAIN", "VALIDATION"))
        val group = originalGroupByGame.getValue(game)
        val previousSeed = partitionBySeed.put(seedByGroup.getValue(group), partition)
        require(previousSeed == null || previousSeed == partition) { "A source seed crosses TRAIN and VALIDATION" }
        val previous = result.put(group, partition)
        require(previous == null || previous == partition) { "A source pair/seed group crosses TRAIN and VALIDATION" }
    }
    require(result.values.toSet() == setOf("TRAIN", "VALIDATION"))
    return result
}
