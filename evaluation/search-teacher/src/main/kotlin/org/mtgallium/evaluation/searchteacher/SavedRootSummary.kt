package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.SearchSettlementCounts

@Serializable
internal data class SavedRootGroupRegret(
    val seedGroupId: String, val roots: Int, val selections: Int,
    val baselineMeanRegret: Double, val candidateMeanRegret: Double,
    val candidateMinusBaseline: Double,
)

/** Average repetitions within a root before averaging roots; groups are reported separately, not pooled as trials. */
internal fun savedRootGroupRegrets(rows: List<SavedRootRegretRow>): List<SavedRootGroupRegret> {
    require(rows.isNotEmpty() && rows.map { it.rootId to it.repetition }.distinct().size == rows.size)
    require(rows.groupBy { it.rootId }.values.all { root -> root.map { it.seedGroupId }.distinct().size == 1 })
    return rows.groupBy { it.seedGroupId }.toSortedMap().map { (group, groupRows) ->
        val roots = groupRows.groupBy { it.rootId }.values
        SavedRootGroupRegret(group, roots.size, groupRows.size,
            roots.map { root -> root.map { it.baselineRegret }.average() }.average(),
            roots.map { root -> root.map { it.candidateRegret }.average() }.average(),
            roots.map { root -> root.map { it.candidateMinusBaseline }.average() }.average())
    }
}

@Serializable
internal data class PositionScreenAccounting(
    val rows: Int, val dispositions: Map<PositionBankScreenDisposition, Int>,
    val simulations: Int, val freshSimulations: Int, val reusedSimulations: Int,
    val forcedActionRepetitionEstimates: Int, val settlements: SearchSettlementCounts,
    val rootPreparations: Int, val reusedPreparationRows: Int,
    val accumulatedReconstructionMillis: Double, val accumulatedSelectionMillis: Double,
)

/** Operation and settlement accounting stays distinct from regret and terminal game results. */
internal fun positionScreenAccounting(report: PositionBankScreenReport): Map<String, PositionScreenAccounting> {
    require(report.plan.mode != PositionBankScreenMode.TERMINAL_CONTINUATIONS) {
        "Terminal continuation samples require their own outcome accounting, not search-backup accounting"
    }
    require(report.rows.map { Triple(it.rootId, it.policyId, it.repetition) }.distinct().size == report.rows.size)
    return report.rows.groupBy { it.policyId }.toSortedMap().mapValues { (_, rows) ->
        val diagnostics = rows.flatMap { row -> when (row.disposition) {
            PositionBankScreenDisposition.ACTION_CONDITIONAL -> row.rootActionEstimates.map { it.diagnostics }
            PositionBankScreenDisposition.SEARCHED -> listOf(requireNotNull(row.searchDiagnostics))
            else -> emptyList()
        } }
        val settlements = rows.flatMap { row -> when (row.disposition) {
            PositionBankScreenDisposition.ACTION_CONDITIONAL -> row.rootActionEstimates.map { it.settlementCounts }
            PositionBankScreenDisposition.SEARCHED -> row.candidateSettlementCounts.values.toList()
            else -> emptyList()
        } }.fold(SearchSettlementCounts()) { sum, next -> sum.plus(next) }
        require(settlements.successfulBackups == diagnostics.sumOf { it.simulations })
        PositionScreenAccounting(rows.size, rows.groupingBy { it.disposition }.eachCount(),
            diagnostics.sumOf { it.simulations }, diagnostics.sumOf { it.freshSimulations }, diagnostics.sumOf { it.reusedSimulations },
            rows.sumOf { it.rootActionEstimates.size }, settlements,
            rows.count { it.reconstructionMillis != null && !it.reusedRootPreparation }, rows.count { it.reusedRootPreparation },
            rows.sumOf { it.reconstructionMillis ?: 0.0 }, rows.sumOf { it.selectionMillis ?: 0.0 })
    }
}
