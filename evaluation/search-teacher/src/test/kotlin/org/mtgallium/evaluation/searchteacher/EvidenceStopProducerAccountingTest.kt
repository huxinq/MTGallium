package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.infoset.argentum.ArgentumBeliefSupportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

@Tag("public-source")
class EvidenceStopProducerAccountingTest {
    private val acceptedTransitionStop = RepresentationBoundaryDetector { _, detectionPoint, _ ->
        if (detectionPoint == EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION) {
            RepresentationBoundaryFailure(
                triggerCodes = listOf("TEST_ACCEPTED_TRANSITION_VISIBLE_FACT_UNREPRESENTED"),
                affectedViewers = listOf("p0", "p1"),
            )
        } else {
            null
        }
    }

    private val terminalTransitionStop = RepresentationBoundaryDetector { world, detectionPoint, _ ->
        if (detectionPoint == EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION &&
            world.terminalPayoff("p0") != null
        ) {
            RepresentationBoundaryFailure(
                triggerCodes = listOf("TEST_TERMINAL_VISIBLE_FACT_UNREPRESENTED"),
                affectedViewers = listOf("p0", "p1"),
            )
        } else {
            null
        }
    }

    @Test
    fun `belief support refusal records a redacted accepted-transition diagnosis`() {
        val metadata = beliefSupportStopMetadata(
            failure = ArgentumBeliefSupportException(
                viewerAlias = "p1",
                requestedParticles = 8,
                acceptedParticles = 0,
                attempts = 4_096,
                failureCounts = mapOf("KnowledgeSupport:KNOWN_OBJECT_CARD_MISMATCH" to 4_096),
            ),
            currentDecisionIndex = 166,
            triggeringDecisionIndex = 166,
        )

        assertEquals(listOf("BELIEF_SUPPORT:KNOWN_OBJECT_CARD_MISMATCH"), metadata.triggerCodes)
        assertEquals(listOf("p1"), metadata.affectedViewers)
        assertEquals(EvidenceStopDetectionPoint.DURING_SOFTWARE_TRANSITION, metadata.detectionPoint)
        assertEquals(166, metadata.triggeringDecisionIndex)
        assertEquals(167, metadata.refusedPolicyDecisionIndex)
    }

    @Test
    fun `durable attempt summary carries stop accounting but no result-shaped fields`() {
        val game = GameRunResult(
            gameId = "attempt-stop",
            seed = 7L,
            p0Policy = ArenaPolicyKind.SEARCH,
            p1Policy = ArenaPolicyKind.HEURISTIC,
            winner = null,
            terminal = false,
            disposition = GameRunDisposition.STOPPED_SOFTWARE,
            evidenceStop = EvidenceStopMetadata(
                triggerCodes = listOf("REJECTED_TRANSITION"),
                affectedViewers = listOf("p0"),
                firstDetectedBeforeDecision = 4,
                detectionPoint = EvidenceStopDetectionPoint.DURING_SOFTWARE_TRANSITION,
                triggeringDecisionIndex = 3,
                refusedPolicyDecisionIndex = 4,
            ),
            decisions = 3,
            searchSeat = "p0",
            searchScore = null,
            illegalResponses = 0,
            fallbacks = 0,
            stepLimit = false,
        )

        val encoded = evidenceJson.encodeToString(game.evidenceRunAttemptSummary())
        assertTrue("REJECTED_TRANSITION" in encoded)
        assertTrue("DURING_SOFTWARE_TRANSITION" in encoded)
        listOf("winner", "searchScore", "payoff", "trainingTarget").forEach { forbidden ->
            assertFalse(encoded.contains(forbidden, ignoreCase = true), encoded)
        }
    }

    @ScenarioExecutionTest
    @Test
    fun `M02 preserves a terminal-transition representation stop without calling it a limit`() {
        val report = M02PlayerChoiceInventoryDiagnostic(
            registry = buildRegistry(),
            manifest = loadDeckManifest(),
            outerCommit = "focused-test-outer",
            argentumCommit = "focused-test-argentum",
            representationBoundaryDetector = terminalTransitionStop,
        ).run(seed = 20260827L)

        val stopped = report.arenaRuns.single { it.id == "heuristic-mirror-full-game" }
        assertTypedAcceptedTransitionStop(stopped.disposition, stopped.terminal, stopped.evidenceStop)
        assertFalse(stopped.stoppedAtDeclaredSearchDecisionLimit)

        val declaredLimit = report.arenaRuns.single {
            it.id == "search-versus-heuristic-one-search-decision"
        }
        assertEquals(GameRunDisposition.STOPPED_LIMIT, declaredLimit.disposition)
        assertTrue(declaredLimit.stoppedAtDeclaredSearchDecisionLimit)
        assertNull(declaredLimit.evidenceStop)
    }

    @ScenarioExecutionTest
    @Test
    fun `M01 retains terminal-transition refusal accounting in its compressed natural run`() {
        val report = M01ResponseWindowDiagnostic(
            registry = buildRegistry(),
            manifest = loadDeckManifest(),
            outerCommit = "focused-test-outer",
            argentumCommit = "focused-test-argentum",
            particles = 1,
            simulations = 1,
            maximumPolicyDecisions = 4,
            pairedCaseIds = listOf("immediate-01"),
            naturalGameCount = 1,
            representationBoundaryDetector = terminalTransitionStop,
        ).run(baseSeed = 20260827L)

        val stopped = report.naturalArenaRuns.single()
        assertTypedAcceptedTransitionStop(stopped.disposition, stopped.terminal, stopped.evidenceStop)
        assertFalse(stopped.decisionLimitStop)
    }

    @ScenarioExecutionTest
    @Test
    fun `information conformance retains accepted-transition refusal accounting per game`() {
        val report = InformationConformanceRunner(
            root = java.nio.file.Path.of(".").toAbsolutePath().normalize(),
            registry = buildRegistry(),
            manifest = loadDeckManifest(),
            profile = SearchTeacherArena.smokeProfile(),
            baseSeed = 20260827L,
            representationBoundaryDetector = acceptedTransitionStop,
        ).run(gameCount = 1, workerThreads = 1)

        val stopped = report.games.single()
        assertTypedAcceptedTransitionStop(
            stopped.disposition,
            stopped.terminal,
            stopped.evidenceStop,
            expectedTriggerCode = "TEST_ACCEPTED_TRANSITION_VISIBLE_FACT_UNREPRESENTED",
        )
        assertTrue(report.failures.any { "non-terminal" in it })
    }

    private fun assertTypedAcceptedTransitionStop(
        disposition: GameRunDisposition,
        terminal: Boolean,
        summary: EvidenceRunStopSummary?,
        expectedTriggerCode: String = "TEST_TERMINAL_VISIBLE_FACT_UNREPRESENTED",
    ) {
        assertEquals(GameRunDisposition.STOPPED_REPRESENTATION, disposition, summary.toString())
        assertFalse(terminal)
        val stop = assertNotNull(summary)
        assertEquals(EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION, stop.detectionPoint)
        assertEquals(listOf(expectedTriggerCode), stop.triggerCodes)
        assertEquals(listOf("p0", "p1"), stop.affectedViewers)
        val triggeringDecisionIndex = assertNotNull(stop.triggeringDecisionIndex)
        assertEquals(triggeringDecisionIndex + 1, stop.refusedPolicyDecisionIndex)
        assertEquals(1, stop.reached)
        assertEquals(1, stop.refused)
        assertEquals(0, stop.degraded)
    }
}
