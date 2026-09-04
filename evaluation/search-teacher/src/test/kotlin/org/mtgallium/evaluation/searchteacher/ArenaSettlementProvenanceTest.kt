package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.InformationSetSearchDiagnostics
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.SearchSettlementCounts

@Tag("public-source")
class ArenaSettlementProvenanceTest {
    @Test
    fun `exact settlement provenance preserves measured zero categories`() {
        val recorded = ArenaSearchDecisionDiagnostic(
            decisionIndex = 0,
            turnNumber = 1,
            phase = "MAIN",
            step = "PRECOMBAT_MAIN",
            latencyMillis = 1.0,
            searchDiagnostics = diagnostics(simulations = 4),
            settlementCounts = SearchSettlementCounts(
                terminalPayoffBackups = 1,
                heuristicSettlementBackups = 0,
                neutralUnresolvedSettlementBackups = 3,
            ),
            settlementCountsAvailability = SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1,
        )

        val decoded = evidenceJson.decodeFromString<ArenaSearchDecisionDiagnostic>(
            evidenceJson.encodeToString(recorded)
        )

        assertEquals(
            SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1,
            decoded.settlementCountsAvailability,
        )
        assertEquals(0, decoded.settlementCounts.heuristicSettlementBackups)
        assertEquals(4, decoded.settlementCounts.successfulBackups)
    }

    @Test
    fun `exact settlement provenance must partition completed simulations`() {
        assertFailsWith<IllegalArgumentException> {
            ArenaSearchDecisionDiagnostic(
                decisionIndex = 0,
                turnNumber = 1,
                phase = "MAIN",
                step = "PRECOMBAT_MAIN",
                latencyMillis = 1.0,
                searchDiagnostics = diagnostics(simulations = 4),
                settlementCounts = SearchSettlementCounts(terminalPayoffBackups = 3),
                settlementCountsAvailability =
                    SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1,
            )
        }
    }

    @Test
    fun `schema three arena game decodes missing settlement provenance as unavailable`() {
        val legacyGame = """
            {
              "schemaVersion": 3,
              "gameId": "public-schema-v3",
              "seed": 1,
              "p0Policy": "SEARCH",
              "p1Policy": "HEURISTIC",
              "winner": null,
              "terminal": false,
              "decisions": 1,
              "searchSeat": "p0",
              "searchScore": null,
              "illegalResponses": 0,
              "fallbacks": 0,
              "stepLimit": true,
              "seatDiagnostics": {
                "p0": {
                  "policyId": "search",
                  "searchDecisions": 1,
                  "searchLatenciesMillis": [1.0],
                  "searchDecisionsDetail": [
                    {
                      "decisionIndex": 0,
                      "turnNumber": 1,
                      "phase": "MAIN",
                      "step": "PRECOMBAT_MAIN",
                      "latencyMillis": 1.0,
                      "searchDiagnostics": {
                        "simulations": 4,
                        "particles": 1,
                        "nodes": 1,
                        "maximumDepth": 1,
                        "exhaustiveNodes": 1,
                        "nonExhaustiveNodes": 0,
                        "wideningEvents": 0,
                        "opponentModelId": "synthetic-opponent",
                        "leaf": {
                          "stateSource": "CURRENT_INFORMATION_STATE",
                          "evaluator": "MTGALLIUM_TACTICAL_V3"
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val decoded = evidenceJson.decodeFromString<GameRunResult>(legacyGame)
        val decision = decoded.seatDiagnostics.getValue("p0").searchDecisionsDetail.single()

        assertEquals(3, decoded.schemaVersion)
        assertEquals(
            SettlementCountsAvailability.UNAVAILABLE_HISTORICAL,
            decision.settlementCountsAvailability,
        )
        assertEquals(SearchSettlementCounts(), decision.settlementCounts)
        assertEquals(4, newGame().schemaVersion)
    }

    private fun diagnostics(simulations: Int): InformationSetSearchDiagnostics =
        InformationSetSearchDiagnostics(
            simulations = simulations,
            particles = 1,
            nodes = 1,
            maximumDepth = 1,
            exhaustiveNodes = 1,
            nonExhaustiveNodes = 0,
            wideningEvents = 0,
            opponentModelId = "synthetic-opponent",
            leaf = LeafEvaluationConfig(
                LeafStateSource.CURRENT_INFORMATION_STATE,
                LeafEvaluator.MTGALLIUM_TACTICAL_V3,
            ),
        )

    private fun newGame(): GameRunResult = GameRunResult(
        gameId = "public-schema-v4",
        seed = 1L,
        p0Policy = ArenaPolicyKind.SEARCH,
        p1Policy = ArenaPolicyKind.HEURISTIC,
        winner = null,
        terminal = false,
        decisions = 0,
        searchSeat = null,
        searchScore = null,
        illegalResponses = 0,
        fallbacks = 0,
        stepLimit = true,
    )
}
