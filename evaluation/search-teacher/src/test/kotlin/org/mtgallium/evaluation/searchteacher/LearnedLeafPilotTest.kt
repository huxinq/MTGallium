package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.InformationSetSearchDiagnostics
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.SearchSettlementCounts
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueCheckpointPayload
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueEvaluator
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueTrainingBinding
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueException
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFailure
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFailureKind
import org.mtgallium.agent.searchteacher.LearnedOutcomeValuePolicyStopException
import org.mtgallium.research.run.ResearchRunArtifacts

@org.junit.jupiter.api.Tag("public-source")
class LearnedLeafPilotTest {
    @Test
    fun `learned leaf roster preserves the canonical 8x64 control except leaf`() {
        val (control, learned) = LearnedLeafPilotRoster.parameters()

        assertEquals(8, control.particles)
        assertEquals(64, control.simulations)
        assertEquals(8, learned.particles)
        assertEquals(64, learned.simulations)
        assertEquals(control, learned.copy(leaf = control.leaf))
        assertFalse(control.policyCompression.enabled)
        assertFalse(learned.policyCompression.enabled)
        assertFalse(control.searchReuse.enabled)
        assertFalse(learned.searchReuse.enabled)
    }

    @Test
    fun `pilot execution requires a promoted candidate and a separately verified smoke capability`() {
        val method = LearnedLeafPilotRoster::class.java.getDeclaredMethod(
            "policies",
            PromotedOutcomeValueCheckpoint::class.java,
        )
        assertEquals(PromotedOutcomeValueCheckpoint::class.java, method.parameterTypes.single())
        assertFailsWith<NoSuchMethodException> {
            LearnedLeafPilotRoster::class.java.getDeclaredMethod(
                "policies",
                LearnedOutcomeValueEvaluator::class.java,
            )
        }
        assertEquals(50, LEARNED_LEAF_PILOT_REQUIRED_PAIRS)
        assertTrue(LEARNED_LEAF_PILOT_CANDIDATE_PROTOCOL != LEARNED_LEAF_PILOT_SMOKE_PROTOCOL)
        assertTrue(LEARNED_LEAF_PILOT_SMOKE_PROTOCOL != LEARNED_LEAF_PILOT_PROTOCOL)
        assertTruePrivateConstructor(PreparedLearnedLeafPilotCandidate::class.java)
        assertTruePrivateConstructor(AdmittedLearnedLeafPilotExecution::class.java)
        assertEquals(
            AdmittedLearnedLeafPilotExecution::class.java,
            LearnedLeafPilotRunner::class.java.getDeclaredMethod(
                "run", AdmittedLearnedLeafPilotExecution::class.java, Int::class.javaPrimitiveType!!,
                java.nio.file.Path::class.java,
            ).parameterTypes.first(),
        )
        assertFailsWith<NoSuchMethodException> {
            LearnedLeafPilotRunner::class.java.getDeclaredMethod(
                "run", PreparedLearnedLeafPilotCandidate::class.java, Int::class.javaPrimitiveType!!,
                java.nio.file.Path::class.java,
            )
        }
    }

    @Test
    fun `existing smoke artifacts fail closed when incomplete or bound to another candidate`() {
        val incomplete = kotlin.io.path.createTempDirectory("learned-leaf-smoke-incomplete")
        assertFailsWith<IllegalArgumentException> {
            loadCompletedLearnedLeafPilotSmokeArtifact(incomplete, "learned-smoke:expected")
        }

        val mismatched = kotlin.io.path.createTempDirectory("learned-leaf-smoke-mismatch")
        Files.writeString(mismatched.resolve("report.json"), "{}")
        ResearchRunArtifacts(mismatched, "learned-smoke:other").also {
            it.register("report.json")
            it.finalize()
        }
        assertFailsWith<IllegalArgumentException> {
            loadCompletedLearnedLeafPilotSmokeArtifact(mismatched, "learned-smoke:expected")
        }
    }

    @Test
    fun `parallel completion progress is monotone regardless of pair index order`() {
        val completion = LearnedLeafPilotCompletionProgress()
        assertEquals(listOf(1, 2, 3), listOf(49, 1, 32).map { completion.completePair() })
    }

    @Test
    fun `exact settlement provenance must partition actual simulations`() {
        assertFailsWith<IllegalArgumentException> {
            ArenaSearchDecisionDiagnostic(
                decisionIndex = 0,
                turnNumber = 1,
                phase = "MAIN",
                step = "PRECOMBAT_MAIN",
                latencyMillis = 1.0,
                searchDiagnostics = diagnostics(),
                settlementCounts = SearchSettlementCounts(learnedOutcomeEstimateBackups = 1),
                settlementCountsAvailability = SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1,
            )
        }
    }

    @Test
    fun `legacy v3 games decode unavailable settlement provenance while new games advertise v4`() {
        val legacyDiagnostic = """
            {
              "decisionIndex": 0,
              "turnNumber": 1,
              "phase": "MAIN",
              "step": "PRECOMBAT_MAIN",
              "latencyMillis": 1.0,
              "searchDiagnostics": ${evidenceJson.encodeToString(diagnostics())},
              "settlementCounts": ${evidenceJson.encodeToString(SearchSettlementCounts())}
            }
        """.trimIndent()
        val legacyGame = """
            {
              "schemaVersion": 3,
              "gameId": "legacy-game",
              "seed": 1,
              "p0Policy": "SEARCH",
              "p1Policy": "SEARCH",
              "winner": null,
              "terminal": false,
              "decisions": 1,
              "searchSeat": null,
              "searchScore": null,
              "illegalResponses": 0,
              "fallbacks": 0,
              "stepLimit": true,
              "seatDiagnostics": {
                "p0": {
                  "policyId": "search",
                  "searchDecisions": 1,
                  "searchLatenciesMillis": [1.0],
                  "searchDecisionsDetail": [$legacyDiagnostic]
                }
              }
            }
        """.trimIndent()
        val decoded = evidenceJson.decodeFromString<GameRunResult>(legacyGame)
        assertEquals(3, decoded.schemaVersion)
        assertEquals(
            SettlementCountsAvailability.UNAVAILABLE_HISTORICAL,
            decoded.seatDiagnostics.getValue("p0").searchDecisionsDetail.single().settlementCountsAvailability,
        )
        assertEquals(
            4,
            GameRunResult(
                gameId = "new-game",
                seed = 1L,
                p0Policy = ArenaPolicyKind.SEARCH,
                p1Policy = ArenaPolicyKind.SEARCH,
                winner = null,
                terminal = false,
                decisions = 0,
                searchSeat = null,
                searchScore = null,
                illegalResponses = 0,
                fallbacks = 0,
                stepLimit = true,
            ).schemaVersion,
        )
    }

    @Test
    fun `per arm pilot telemetry aggregates arena search diagnostics without inferring work`() {
        val game = GameRunResult(
            gameId = "pilot-game",
            seed = 1L,
            p0Policy = ArenaPolicyKind.SEARCH,
            p1Policy = ArenaPolicyKind.SEARCH,
            winner = "p0",
            terminal = true,
            disposition = GameRunDisposition.GAME_ENDED,
            decisions = 1,
            searchSeat = "p0",
            searchScore = 1.0,
            illegalResponses = 0,
            fallbacks = 0,
            stepLimit = false,
            p0PolicyId = LEARNED_LEAF_PILOT_TREATMENT_ID,
            p1PolicyId = LEARNED_LEAF_PILOT_CONTROL_ID,
            elapsedMillis = 999.0,
            seatDiagnostics = mapOf(
                "p0" to ArenaSeatDiagnostics(
                    policyId = LEARNED_LEAF_PILOT_TREATMENT_ID,
                    searchDecisions = 1,
                    searchLatenciesMillis = listOf(7.5),
                    searchDecisionsDetail = listOf(
                        ArenaSearchDecisionDiagnostic(
                            decisionIndex = 0,
                            turnNumber = 1,
                            phase = "MAIN",
                            step = "PRECOMBAT_MAIN",
                            latencyMillis = 7.5,
                            searchDiagnostics = diagnostics(),
                            settlementCounts = SearchSettlementCounts(
                                terminalPayoffBackups = 2,
                                heuristicSettlementBackups = 3,
                                learnedOutcomeEstimateBackups = 5,
                                neutralUnresolvedSettlementBackups = 54,
                            ),
                            settlementCountsAvailability = SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1,
                        )
                    ),
                ),
            ),
        )

        val summary = learnedLeafPilotOperational(
            LEARNED_LEAF_PILOT_TREATMENT_ID,
            LearnedLeafPilotRoster.parameters().second,
            listOf(game),
        )

        assertEquals(1, summary.searchedDecisions)
        assertEquals(7.5, summary.wholeSearchLatencyMillisTotal)
        assertEquals(64, summary.actualSimulationsTotal)
        assertEquals(11, summary.evaluatorCallsTotal)
        assertEquals(13L, summary.evaluatorNanosTotal)
        assertEquals(17, summary.rolloutDecisionsTotal)
        assertEquals(19, summary.simulatedWorldStepsTotal)
        assertEquals(2, summary.terminalPayoffBackupsTotal)
        assertEquals(3, summary.heuristicSettlementBackupsTotal)
        assertEquals(5, summary.learnedOutcomeEstimateBackupsTotal)
        assertEquals(54, summary.neutralUnresolvedSettlementBackupsTotal)
        assertEquals(0, summary.settlementCountsUnavailableDecisions)
        assertEquals(8, summary.configuredParticles)
        assertEquals(64, summary.configuredSimulations)
    }

    @Test
    fun `arena rejects evaluators for direct and mismatched policies while preserving typed stop metadata`() {
        val evaluator = evaluator(weight = 0.1)
        val (control, _) = LearnedLeafPilotRoster.parameters()

        assertFailsWith<IllegalArgumentException> {
            ArenaPolicySpec(
                id = "learned-direct-invalid",
                kind = ArenaPolicyKind.HEURISTIC,
                informationEvaluator = evaluator,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ArenaPolicySpec(
                id = "learned-mismatched-invalid",
                kind = ArenaPolicyKind.SEARCH,
                parameters = control,
                informationEvaluator = evaluator,
            ).effectiveParameters(20260903L)
        }

        val metadata = learnedOutcomeValueStopMetadata(
            LearnedOutcomeValuePolicyStopException(
                LearnedOutcomeValueException(
                    LearnedOutcomeValueFailure(
                        LearnedOutcomeValueFailureKind.INPUT_KNOWLEDGE_INCOMPLETE,
                        "private cause text",
                    )
                )
            ),
            currentDecisionIndex = 3,
        )
        assertEquals(listOf("LEARNED_VALUE:INPUT_KNOWLEDGE_INCOMPLETE"), metadata.triggerCodes)
        assertFalse(metadata.triggerCodes.single().contains("private"))
        assertEquals(3, metadata.refusedPolicyDecisionIndex)
    }

    private fun evaluator(weight: Double): LearnedOutcomeValueEvaluator =
        LearnedOutcomeValueEvaluator.fromCheckpoint(
            LearnedOutcomeValueCheckpointPayload(
                training = LearnedOutcomeValueTrainingBinding(
                    corpusIdentity = identity("corpus", 'a'),
                    pairSplitIdentity = identity("split", 'b'),
                    learnerConfigurationIdentity = identity("learner", 'c'),
                    projectionIdentity = identity("projection", 'd'),
                    rootBehaviorPolicyIdentity = identity("root-policy", 'e'),
                    opponentBehaviorPolicyIdentity = identity("opponent-policy", 'f'),
                    environmentProfileIdentity = identity("environment", '1'),
                ),
                bias = 0.0,
                weights = mapOf("state/test" to weight),
            )
        )

    private fun identity(name: String, digit: Char): String =
        "$name-sha256:${digit.toString().repeat(64)}"

    private fun diagnostics(): InformationSetSearchDiagnostics = InformationSetSearchDiagnostics(
        simulations = 64,
        particles = 8,
        nodes = 1,
        maximumDepth = 1,
        exhaustiveNodes = 1,
        nonExhaustiveNodes = 0,
        wideningEvents = 0,
        opponentModelId = "test-opponent",
        leaf = LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1),
        rootRolloutDecisions = 8,
        opponentRolloutDecisions = 9,
        searchWorldSteps = 19,
        evaluatorCalls = 11,
        evaluatorNanos = 13L,
    )

    private fun assertTruePrivateConstructor(type: Class<*>) {
        assertTrue(type.declaredConstructors.filterNot { it.isSynthetic }
            .all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
    }

}
