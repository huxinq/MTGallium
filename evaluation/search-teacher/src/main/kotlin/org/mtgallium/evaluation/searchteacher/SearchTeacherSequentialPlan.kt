package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable

/** Prospectively frozen one-candidate game schedule and stopping rule. Neither chooses a policy winner. */
@Serializable
internal data class SearchTeacherSequentialPlan(
    val calibration: SearchTeacherCalibrationPlan,
    val rule: PairedSequentialRule,
) {
    init {
        require(calibration.candidates.size == 1) { "Sequential gameplay requires exactly one candidate" }
        require(rule.maximumPairs == calibration.pairCount) { "Sequential maximum must equal the planned pair cap" }
    }
}

@Serializable
internal data class SearchTeacherSequentialPopulation(
    val plannedPairs: Int,
    val executedPairs: Int,
    val inspectedPairs: Int,
    val plannedUnexecutedPairs: Int,
    val overshootPairs: Int,
    val invalidInspectedPairs: Int,
    val invalidOvershootPairs: Int,
) {
    init {
        require(plannedPairs > 0 && executedPairs >= 0 && inspectedPairs >= 0 && plannedUnexecutedPairs >= 0 && overshootPairs >= 0)
        require(plannedPairs == executedPairs + plannedUnexecutedPairs && executedPairs == inspectedPairs + overshootPairs)
        require(invalidInspectedPairs in 0..inspectedPairs && invalidOvershootPairs in 0..overshootPairs)
    }
}

internal data class PairedSequentialExecution(
    val pairs: List<SearchBudgetFrontierPair>,
    val result: PairedSequentialResult,
) {
    /** The first stopping prefix owns inference; later work cannot retroactively alter its validity. */
    val valid: Boolean get() = result.disposition != PairedSequentialDisposition.CONTINUE &&
        result.disposition != PairedSequentialDisposition.INVALID_PAIR && pairs.take(result.inspectedPairs).all { it.valid }
    val operationalValid: Boolean get() = pairs.all { it.valid }
    val population: SearchTeacherSequentialPopulation get() = SearchTeacherSequentialPopulation(
        result.rule.maximumPairs, pairs.size, result.inspectedPairs, result.rule.maximumPairs - pairs.size,
        pairs.size - result.inspectedPairs, pairs.take(result.inspectedPairs).count { !it.valid },
        pairs.drop(result.inspectedPairs).count { !it.valid })
}

/**
 * Complete deterministic worker-sized chunks; inspect every prefix in order and launch no later chunk
 * after a stopping prefix. Already completed work in that chunk is retained only as operational overshoot.
 */
internal fun executePairedSequentialSchedule(
    rule: PairedSequentialRule,
    firstPairIndex: Int,
    workerThreads: Int,
    playPair: (Int) -> SearchBudgetFrontierPair,
): PairedSequentialExecution {
    require(workerThreads > 0 && firstPairIndex >= 0 && firstPairIndex.toLong() + rule.maximumPairs <= Int.MAX_VALUE)
    val pairs = mutableListOf<SearchBudgetFrontierPair>()
    var result = pairedSequentialTest(rule, emptyList(), firstPairIndex)
    while (result.disposition == PairedSequentialDisposition.CONTINUE) {
        val chunkStart = firstPairIndex + pairs.size
        val chunkSize = minOf(workerThreads, rule.maximumPairs - pairs.size)
        pairs += parallelMapOrdered(chunkSize, workerThreads) { offset ->
            playPair(chunkStart + offset).also { require(it.pairIndex == chunkStart + offset) }
        }
        result = pairedSequentialTest(rule, pairs.map { pair ->
            PairedSequentialScore(pair.pairIndex,
                if (pair.valid) requireNotNull(pair.treatmentPoints) / 2.0 else null,
                pair.invalidationReasons)
        }, firstPairIndex)
    }
    return PairedSequentialExecution(pairs.toList(), result)
}
