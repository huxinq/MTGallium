package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable

@Serializable
internal enum class DirectGameplayDecisionCostIssueKind {
    NO_EXECUTED_GAMES,
    POLICY_SEAT_COUNT_MISMATCH,
    MISSING_SEAT_DIAGNOSTICS,
    POLICY_SEAT_ID_MISMATCH,
    MISSING_MEASUREMENT,
    INVALID_MEASUREMENT,
    INVALID_SELECTION_COUNTS,
    FEWER_MEASUREMENTS_THAN_SELECTIONS,
    NON_FINITE_TOTAL,
    NON_POSITIVE_CONTROL_TOTAL,
    NON_FINITE_RATIO,
}

@Serializable
internal data class DirectGameplayDecisionCostIssue(
    val kind: DirectGameplayDecisionCostIssueKind,
    val policyId: String? = null,
    val pairIndex: Int? = null,
    val gameId: String? = null,
)

@Serializable
internal data class DirectGameplayGameDecisionCost(
    val pairIndex: Int,
    val gameId: String,
    val seat: String?,
    val measuredAttempts: Int?,
    /** Null denotes missing or invalid measurement; an observed empty list totals zero. */
    val totalDecisionComputationMillis: Double?,
)

@Serializable
internal data class DirectGameplayPolicyDecisionCost(
    val policyId: String,
    val executedGames: Int,
    val measuredGames: Int,
    /** Attempts in validly measured games, including failed calls retained by the arena's finally block. */
    val measuredAttempts: Long,
    /** Available only when every executed game has a valid measurement. */
    val totalDecisionComputationMillis: Double?,
    val decisionComputationMillisPerGame: Double?,
    val games: List<DirectGameplayGameDecisionCost>,
)

@Serializable
internal data class DirectGameplayDecisionCost(
    val control: DirectGameplayPolicyDecisionCost,
    val candidate: DirectGameplayPolicyDecisionCost,
    val candidateControlRatio: Double?,
    val costGatePassed: Boolean,
    val issues: List<DirectGameplayDecisionCostIssue>,
)

/**
 * Cumulative operational cost over every supplied executed game, including stopped games and
 * dispatched overshoot. The caller supplies all chunks' pairs, without strength-prefix filtering.
 * Search-only latency is deliberately not an input to this all-selection measurement.
 */
internal fun directGameplayDecisionCost(
    pairs: List<SearchBudgetFrontierPair>,
    controlPolicyId: String,
    candidatePolicyId: String,
): DirectGameplayDecisionCost {
    require(controlPolicyId.isNotBlank() && candidatePolicyId.isNotBlank() && controlPolicyId != candidatePolicyId)
    val issues = mutableListOf<DirectGameplayDecisionCostIssue>()
    fun policyCost(policyId: String): DirectGameplayPolicyDecisionCost {
        val games = pairs.flatMap { pair -> pair.games.map { game ->
            val firstIssue = issues.size
            fun issue(kind: DirectGameplayDecisionCostIssueKind) {
                issues += DirectGameplayDecisionCostIssue(kind, policyId, pair.pairIndex, game.gameId)
            }
            val seats = mapOf("p0" to game.p0PolicyId, "p1" to game.p1PolicyId).filterValues { it == policyId }.keys
            val seat = seats.singleOrNull()
            if (seat == null) issue(DirectGameplayDecisionCostIssueKind.POLICY_SEAT_COUNT_MISMATCH)
            val diagnostic = seat?.let { game.seatDiagnostics[it] }
            if (seat != null && diagnostic == null) issue(DirectGameplayDecisionCostIssueKind.MISSING_SEAT_DIAGNOSTICS)
            if (diagnostic != null && (diagnostic.policyId != policyId ||
                    game.seatDiagnostics.count { it.value.policyId == policyId } != 1)) {
                issue(DirectGameplayDecisionCostIssueKind.POLICY_SEAT_ID_MISMATCH)
            }
            val measurements = diagnostic?.decisionComputationMillis
            if (diagnostic != null && measurements == null) issue(DirectGameplayDecisionCostIssueKind.MISSING_MEASUREMENT)
            if (measurements != null) {
                if (measurements.any { !it.isFinite() || it < 0.0 }) issue(DirectGameplayDecisionCostIssueKind.INVALID_MEASUREMENT)
                val counts = diagnostic.selectionCounts.values
                if (counts.any { it < 0 }) issue(DirectGameplayDecisionCostIssueKind.INVALID_SELECTION_COUNTS)
                // A failed or timed-out choose call can be timed without a successful selection count.
                if (measurements.size.toLong() < counts.sumOf { it.toLong() }) {
                    issue(DirectGameplayDecisionCostIssueKind.FEWER_MEASUREMENTS_THAN_SELECTIONS)
                }
            }
            val total = measurements?.sum()
            if (total != null && !total.isFinite()) issue(DirectGameplayDecisionCostIssueKind.NON_FINITE_TOTAL)
            val valid = issues.size == firstIssue && total != null
            DirectGameplayGameDecisionCost(pair.pairIndex, game.gameId, seat,
                if (valid) measurements!!.size else null, total.takeIf { valid })
        } }
        val measured = games.filter { it.totalDecisionComputationMillis != null }
        val total = if (games.isNotEmpty() && measured.size == games.size) {
            measured.sumOf { requireNotNull(it.totalDecisionComputationMillis) }.takeIf { it.isFinite() }.also {
                if (it == null) issues += DirectGameplayDecisionCostIssue(DirectGameplayDecisionCostIssueKind.NON_FINITE_TOTAL, policyId)
            }
        } else null
        return DirectGameplayPolicyDecisionCost(policyId, games.size, measured.size,
            measured.sumOf { requireNotNull(it.measuredAttempts).toLong() }, total, total?.div(games.size), games)
    }
    val control = policyCost(controlPolicyId)
    val candidate = policyCost(candidatePolicyId)
    if (control.executedGames == 0) issues += DirectGameplayDecisionCostIssue(DirectGameplayDecisionCostIssueKind.NO_EXECUTED_GAMES)
    val denominator = control.totalDecisionComputationMillis
    if (denominator != null && denominator <= 0.0) issues += DirectGameplayDecisionCostIssue(
        DirectGameplayDecisionCostIssueKind.NON_POSITIVE_CONTROL_TOTAL, controlPolicyId)
    val ratio = if (issues.isEmpty() && denominator != null && candidate.totalDecisionComputationMillis != null) {
        (candidate.totalDecisionComputationMillis / denominator).takeIf { it.isFinite() }.also {
            if (it == null) issues += DirectGameplayDecisionCostIssue(DirectGameplayDecisionCostIssueKind.NON_FINITE_RATIO)
        }
    } else null
    return DirectGameplayDecisionCost(control, candidate, ratio, ratio != null && ratio <= 1.10, issues)
}
