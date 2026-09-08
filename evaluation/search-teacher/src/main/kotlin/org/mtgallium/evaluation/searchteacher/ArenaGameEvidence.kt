package org.mtgallium.evaluation.searchteacher

internal fun tournamentDescriptor(
    first: ArenaPolicySpec,
    second: ArenaPolicySpec,
    pairIndex: Int,
    legIndex: Int,
): TournamentGameDescriptor {
    require(legIndex in 0..1)
    val leg = if (legIndex == 0) "a" else "b"
    return TournamentGameDescriptor(
        gameId = "tournament-${first.id}-${second.id}-$pairIndex-$leg",
        firstPolicyId = first.id,
        secondPolicyId = second.id,
        pairIndex = pairIndex,
        leg = leg,
        p0PolicyId = if (legIndex == 0) first.id else second.id,
        p1PolicyId = if (legIndex == 0) second.id else first.id,
    )
}

internal fun operationallyValidGame(game: GameRunResult): Boolean =
    game.disposition == GameRunDisposition.GAME_ENDED && game.evidenceStop == null &&
    game.terminal && !game.stepLimit && game.exception == null &&
    game.illegalResponses == 0 && game.fallbacks == 0 && game.informationLedgerComplete &&
    game.replayVerified && game.replayPath != null && game.replaySha256 != null &&
    game.seatDiagnostics.values.all { seat ->
        seat.searchDecisionsDetail.all { it.searchDiagnostics.rejectedTransitions == 0 }
    }
