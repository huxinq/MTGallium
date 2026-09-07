package org.mtgallium.evaluation.searchteacher

import java.util.Locale

/** Compact observations keep composed reporting independent of large per-decision search trees. */
internal data class GameplayLengthObservation(
    val pairIndex: Int, val gameId: String, val eligible: Boolean,
    val candidateOutcome: String, val decisions: Int, val terminalTurnNumber: Int?,
    val searchedDecisions: Int?, val elapsedMillis: Double?,
)

internal fun gameplayLengthObservations(pairs: List<SearchBudgetFrontierPair>, candidateId: String): List<GameplayLengthObservation> =
    pairs.flatMap { pair ->
        val eligible = pair.valid && pair.games.size == 2 && pair.games.all {
            it.terminal && it.disposition == GameRunDisposition.GAME_ENDED && searchBudgetFrontierInvalidationReasons(it).isEmpty()
        }
        pair.games.map { game ->
            if (eligible) require(game.winner in setOf(null, "p0", "p1")) { "Unknown winner in terminal game ${game.gameId}" }
            val winner = when (game.winner) { "p0" -> game.p0PolicyId; "p1" -> game.p1PolicyId; else -> null }
            GameplayLengthObservation(pair.pairIndex, game.gameId, eligible,
                if (winner == null) "draw" else if (winner == candidateId) "candidate win" else "control win",
                game.decisions, game.terminalTurnNumber,
                game.seatDiagnostics.takeIf { it.keys.containsAll(listOf("p0", "p1")) }
                    ?.let { seats -> listOf("p0", "p1").sumOf { seats.getValue(it).searchDecisions } }, game.elapsedMillis)
        }
    }.also { require(it.map { row -> row.gameId }.distinct().size == it.size) { "Duplicate game length observations" } }

internal data class GameplayLengthDistribution(val count: Int, val mean: Double?, val median: Double?, val p90: Double?)

internal fun gameplayLengthDistribution(values: List<Double>): GameplayLengthDistribution {
    require(values.all { it.isFinite() && it >= 0 })
    if (values.isEmpty()) return GameplayLengthDistribution(0, null, null, null)
    val sorted = values.sorted()
    val middle = sorted.size / 2
    val median = if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    // Nearest-rank quantile: deterministic for small cohorts and not an uncertainty interval.
    return GameplayLengthDistribution(values.size, values.average(), median, sorted[(kotlin.math.ceil(.9 * values.size).toInt() - 1)])
}

internal fun renderGameplayLengths(rows: List<GameplayLengthObservation>, inspectedPairs: Int, firstPairIndex: Int): String = buildString {
    require(inspectedPairs >= 0 && firstPairIndex >= 0 && rows.all { it.pairIndex >= firstPairIndex })
    require(rows.map { it.gameId }.distinct().size == rows.size) { "Duplicate game length observations" }
    val inspectedEnd = firstPairIndex.toLong() + inspectedPairs
    val prefix = rows.filter { it.pairIndex.toLong() < inspectedEnd }
    val overshoot = rows.filter { it.pairIndex.toLong() >= inspectedEnd }
    val valid = rows.filter { it.eligible }
    appendLine("\nGame length (whole games shared by both players): ${valid.size} terminal games in valid complete pairs; ${rows.size - valid.size} attempted games excluded because their pair is invalid/incomplete. Unexecuted games are absent.")
    appendLine("Accepted decisions include passes, responses and singleton choices; searched decisions count both seats. Turns count individual player turns begun, including the terminal turn, not rounds. Missing historical turns/timings/search counts remain unavailable.")
    appendLine()
    appendLine("| Population | Games | Accepted decisions mean / median / p90 | Player turns mean (available) | Searched decisions mean (available) | Game seconds mean (available) |")
    appendLine("|---|---:|---:|---:|---:|---:|")
    val groups = listOf("All executed valid" to valid,
        "Inspected valid" to prefix.filter { it.eligible }, "Operational overshoot valid" to overshoot.filter { it.eligible }) +
        listOf("candidate win", "control win", "draw").map { outcome ->
            "Inspected: $outcome" to prefix.filter { it.eligible && it.candidateOutcome == outcome }
        }
    groups.forEach { (label, group) ->
        val decisions = gameplayLengthDistribution(group.map { it.decisions.toDouble() })
        fun available(values: List<Double>): String = gameplayLengthDistribution(values).let { "${gameplayNumber(it.mean)} (${it.count}/${group.size})" }
        appendLine("| $label | ${group.size} | ${gameplayNumber(decisions.mean)} / ${gameplayNumber(decisions.median)} / ${gameplayNumber(decisions.p90)} | " +
            "${available(group.mapNotNull { it.terminalTurnNumber?.toDouble() })} | ${available(group.mapNotNull { it.searchedDecisions?.toDouble() })} | " +
            "${available(group.mapNotNull { it.elapsedMillis?.div(1000) })} |")
    }
    appendLine()
    appendLine("Outcome groups are descriptive, selected after play: shorter wins do not establish a causal effect of strength. Every game contains both policies. Mean game seconds includes both players and concurrent host overhead; it is not policy cost or reciprocal tournament throughput. p90 is a length percentile, not a confidence bound.")
}

internal fun gameplayNumber(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "unavailable"
