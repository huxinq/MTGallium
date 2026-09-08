package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable

@Serializable
internal data class TerminalStudyComparison(
    val rows: List<SavedRootRegretRow>,
    val groups: List<SavedRootGroupRegret>,
    val equalGroupMeanDifference: Double,
    val equalGroupMeanByTargetRepetition: List<Double>,
    val positiveGroups: Int,
    val negativeGroups: Int,
    val tiedGroups: Int,
)

/** Repetitions and uneven root counts never give a seed group extra weight. */
internal fun terminalTargetComparison(rows: List<SavedRootRegretRow>): TerminalStudyComparison {
    require(rows.isNotEmpty() && rows.all { it.repetition == 0 } && rows.map { it.rootId }.distinct().size == rows.size)
    val repetitions = rows.first().candidateMinusBaselineByReferenceRepetition.size
    require(repetitions > 0 && rows.all { it.candidateMinusBaselineByReferenceRepetition.size == repetitions })
    val groups = savedRootGroupRegrets(rows)
    val rowsByGroup = rows.groupBy { it.seedGroupId }.values
    val meanByRepetition = (0 until repetitions).map { repetition ->
        rowsByGroup.map { group ->
            group.map { it.candidateMinusBaselineByReferenceRepetition[repetition] }.average()
        }.average()
    }
    return TerminalStudyComparison(
        rows = rows,
        groups = groups,
        equalGroupMeanDifference = groups.map { it.candidateMinusBaseline }.average(),
        equalGroupMeanByTargetRepetition = meanByRepetition,
        positiveGroups = groups.count { it.candidateMinusBaseline > 0 },
        negativeGroups = groups.count { it.candidateMinusBaseline < 0 },
        tiedGroups = groups.count { it.candidateMinusBaseline == 0.0 },
    )
}

internal fun compareTerminalTargetChoices(
    bank: RealGamePositionBankReport,
    terminal: PositionBankScreenReport,
    targets: List<RootActionKernelTrainingRoot>,
    candidate: CompiledRootActionKernel,
    baseline: (RealGamePositionBankRoot, RootActionKernelTrainingRoot) -> String,
): List<SavedRootRegretRow> {
    val roots = bank.roots.associateBy { it.rootId }
    return targets.map { target ->
        val root = roots.getValue(target.rootId)
        val scores = candidate.scores(target.features)
        val chosen = terminalModelChoice(root.reconstructedCandidates.map { it.signature }, scores)
        val baselineChoice = baseline(root, target)
        val repetitions = (0 until terminal.plan.repetitions).map { rep ->
            terminal.rows.single { it.rootId == root.rootId && it.repetition == rep }.terminalRootActions
                .associate { it.action.signature to requireNotNull(it.meanTerminalPayoff) }
        }
        terminalTargetRegretRow(root.rootId, root.seedGroupId, repetitions, baselineChoice, chosen)
    }
}

/** Raw score argmax keeps the first action in the original menu when scores tie. */
internal fun terminalModelChoice(signatures: List<String>, scores: List<Double>): String =
    signatures[scores.indices.maxBy { scores[it] }]

internal fun terminalTargetRegretRow(
    rootId: String,
    seedGroupId: String,
    repetitions: List<Map<String, Double>>,
    baseline: String,
    candidate: String,
): SavedRootRegretRow {
    val (baselineRegret, candidateRegret, differences) = savedRootReferenceComparison(repetitions, baseline, candidate)
    return SavedRootRegretRow(
        rootId = rootId,
        seedGroupId = seedGroupId,
        repetition = 0,
        baselineAction = baseline,
        candidateAction = candidate,
        baselineRegret = baselineRegret,
        candidateRegret = candidateRegret,
        candidateMinusBaseline = differences.average(),
        candidateMinusBaselineByReferenceRepetition = differences,
    )
}
