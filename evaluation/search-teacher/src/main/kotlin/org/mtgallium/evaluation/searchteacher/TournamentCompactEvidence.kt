package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionSummary
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile

@Serializable
internal data class TournamentGameSummary(
    val schemaVersion: Int = 3,
    val gameId: String,
    val seed: Long,
    val p0PolicyId: String,
    val p1PolicyId: String,
    val winner: String?,
    val terminal: Boolean,
    val disposition: GameRunDisposition = GameRunDisposition.LEGACY_UNCLASSIFIED,
    val evidenceStop: EvidenceStopMetadata? = null,
    val decisions: Int,
    val searchSeat: String?,
    val searchScore: Double?,
    val illegalResponses: Int,
    val fallbacks: Int,
    val heuristicResolutionCounts: Map<String, Int>,
    val liveOpponentPolicyDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    val searchOpponentPolicyDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    val heuristicComparatorDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    val stepLimit: Boolean,
    val exception: String?,
    val informationLedgerComplete: Boolean,
    val unsupportedInformationEvents: List<String>,
    val replayPath: String?,
    val replaySha256: String?,
    val replayVerified: Boolean,
    val replayVerificationDiagnostic: String?,
    val cleanupDiscardEvents: Int,
    val mainPhasePassesWithProactiveOptions: Int,
    val elapsedMillis: Double?,
)

@Serializable
internal data class TournamentMatchupSummary(
    val firstPolicyId: String,
    val secondPolicyId: String,
    val pairCount: Int,
    val winsFirst: Int,
    val draws: Int,
    val winsSecond: Int,
    val firstPointRate: Double,
    val games: List<TournamentGameSummary>,
    /** Shared library-order block assigned to each consecutive seat-swapped pair. */
    val pairIndices: List<Int> = (0 until pairCount).toList(),
)

@Serializable
internal data class CoreSixTournamentCompactReport(
    val schemaVersion: Int = 1,
    val sourceSchemaVersion: Int,
    val tournamentVersion: String,
    val runIdentity: String,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val deckHash: String,
    val benchmarkPath: String,
    val benchmarkSha256: String,
    val benchmarkOuterCommit: String,
    val benchmarkArgentumCommit: String,
    val reviewManifestSha256: String,
    val reviewerName: String,
    val reviewedAtUtc: String,
    val reviewReference: String,
    val baseSeed: Long,
    val pairsPerMatchup: Int,
    val workerThreads: Int,
    val runtimeWarmupWorkers: Int,
    val policies: List<TournamentPolicyDescription>,
    val matchups: List<TournamentMatchupSummary>,
    val standings: List<TournamentStanding>,
    val startingPlayerRating: Double,
    val completePairs: Int,
    val gameCount: Int,
    val cleanupDiscardEvents: Int,
    val gamesWithCleanupDiscard: Int,
    val cleanupDiscardGameRate: Double,
    val policyQualityWarnings: List<String>,
    val valid: Boolean,
    val failureReasons: List<String>,
) {
    val games: List<TournamentGameSummary> get() = matchups.flatMap(TournamentMatchupSummary::games)
}

internal fun GameRunResult.compact(): TournamentGameSummary = TournamentGameSummary(
    gameId = gameId,
    seed = seed,
    p0PolicyId = p0PolicyId,
    p1PolicyId = p1PolicyId,
    winner = winner,
    terminal = terminal,
    disposition = disposition,
    evidenceStop = evidenceStop,
    decisions = decisions,
    searchSeat = searchSeat,
    searchScore = searchScore,
    illegalResponses = illegalResponses,
    fallbacks = fallbacks,
    heuristicResolutionCounts = heuristicResolutionCounts,
    liveOpponentPolicyDecisions = liveOpponentPolicyDecisions,
    searchOpponentPolicyDecisions = searchOpponentPolicyDecisions,
    heuristicComparatorDecisions = heuristicComparatorDecisions,
    stepLimit = stepLimit,
    exception = exception,
    informationLedgerComplete = informationLedgerComplete,
    unsupportedInformationEvents = unsupportedInformationEvents,
    replayPath = replayPath,
    replaySha256 = replaySha256,
    replayVerified = replayVerified,
    replayVerificationDiagnostic = replayVerificationDiagnostic,
    cleanupDiscardEvents = cleanupDiscardEvents,
    mainPhasePassesWithProactiveOptions = mainPhasePassesWithProactiveOptions,
    elapsedMillis = elapsedMillis,
)

internal fun CoreSixTournamentReport.compact(): CoreSixTournamentCompactReport =
    CoreSixTournamentCompactReport(
        sourceSchemaVersion = schemaVersion,
        tournamentVersion = tournamentVersion,
        runIdentity = runIdentity,
        generatedAtUtc = generatedAtUtc,
        outerCommit = outerCommit,
        argentumCommit = argentumCommit,
        deckHash = deckHash,
        benchmarkPath = benchmarkPath,
        benchmarkSha256 = benchmarkSha256,
        benchmarkOuterCommit = benchmarkOuterCommit,
        benchmarkArgentumCommit = benchmarkArgentumCommit,
        reviewManifestSha256 = reviewManifestSha256,
        reviewerName = reviewerName,
        reviewedAtUtc = reviewedAtUtc,
        reviewReference = reviewReference,
        baseSeed = baseSeed,
        pairsPerMatchup = pairsPerMatchup,
        workerThreads = workerThreads,
        runtimeWarmupWorkers = runtimeWarmupWorkers,
        policies = policies,
        matchups = matchups.map { matchup ->
            TournamentMatchupSummary(
                firstPolicyId = matchup.firstPolicyId,
                secondPolicyId = matchup.secondPolicyId,
                pairCount = matchup.pairCount,
                winsFirst = matchup.winsFirst,
                draws = matchup.draws,
                winsSecond = matchup.winsSecond,
                firstPointRate = matchup.firstPointRate,
                games = matchup.games.map(GameRunResult::compact),
                pairIndices = matchup.pairIndices,
            )
        },
        standings = standings,
        startingPlayerRating = startingPlayerRating,
        completePairs = completePairs,
        gameCount = gameCount,
        cleanupDiscardEvents = cleanupDiscardEvents,
        gamesWithCleanupDiscard = gamesWithCleanupDiscard,
        cleanupDiscardGameRate = cleanupDiscardGameRate,
        policyQualityWarnings = policyQualityWarnings,
        valid = valid,
        failureReasons = failureReasons,
    )

internal fun StringBuilder.appendTournamentExecutionSummary(
    gameCount: Int,
    completePairs: Int,
    conditionsSatisfied: Boolean,
    failureCount: Int,
    gamesWithCleanupDiscard: Int,
) {
    appendLine("## What the recorded games show")
    appendLine()
    if (conditionsSatisfied) {
        appendLine(
            "All $gameCount recorded games formed $completePairs complete seat-swapped pairs and none " +
                "triggered an execution failure counted by this tournament procedure."
        )
    } else {
        appendLine(
            "The run recorded $gameCount games and $completePairs complete seat-swapped pairs, but " +
                "$failureCount recorded condition(s) prevented the procedure from accepting the run."
        )
    }
    appendLine()
    appendLine(
        "For example, the procedure rejects a game that does not reach an engine-reported game end, " +
            "hits the step limit, throws an exception, records an illegal response or substituted action, " +
            "has an incomplete acting-player record, contains a rejected search transition, or fails replay " +
            "verification."
    )
    appendLine()
    appendLine(
        "This result describes those recorded execution conditions and the standings calculated from these " +
            "games. It does not show that every legal action was offered or that any policy chose strategically " +
            "strong lines. Cleanup discard behavior occurred in $gamesWithCleanupDiscard/$gameCount games and " +
            "must be interpreted separately as a play-quality warning."
    )
}

internal fun renderCoreSixTournament(report: CoreSixTournamentCompactReport): String = buildString {
    appendLine("# Recorded seat-swapped games comparing six declared policies")
    appendLine()
    appendTournamentExecutionSummary(
        gameCount = report.gameCount,
        completePairs = report.completePairs,
        conditionsSatisfied = report.valid,
        failureCount = report.failureReasons.size,
        gamesWithCleanupDiscard = report.gamesWithCleanupDiscard,
    )
    appendLine()
    appendLine("## Experiment trace")
    appendLine()
    appendLine("- Procedure identifier: core-six pilot tournament")
    appendLine("- Benchmark: `${report.benchmarkPath}` (`${report.benchmarkSha256}`)")
    appendLine(
        "- Candidate construction deliberately omits selected redundant mana activations. The standings " +
            "therefore apply only to that reduced action set and do not describe play with every represented " +
            "standalone mana action available " +
            "(`${SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1.profileId}`)."
    )
    appendLine()
    appendLine("## Standings calculated from the recorded games")
    appendLine()
    appendLine("| Rank | Policy | Rating | 95% interval | Points | W-D-L |")
    appendLine("| ---: | --- | ---: | ---: | ---: | ---: |")
    report.standings.forEach { standing ->
        appendLine(
            "| ${standing.rank} | ${standing.policyId} | ${"%.1f".format(standing.rating)} | " +
                "${"%.1f".format(standing.confidenceLower)} to ${"%.1f".format(standing.confidenceUpper)} | " +
                "${"%.1f".format(standing.gamePointRate * 100)}% | " +
                "${standing.wins}-${standing.draws}-${standing.losses} |"
        )
    }
    if (report.failureReasons.isNotEmpty()) {
        appendLine()
        appendLine("## Recorded conditions that prevented use")
        report.failureReasons.forEach { appendLine("- $it") }
    }
}
