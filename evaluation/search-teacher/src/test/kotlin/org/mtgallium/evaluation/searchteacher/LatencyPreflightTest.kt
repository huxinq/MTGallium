package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceLocation

class LatencyPreflightTest {
    private val caseIds = TacticalBenchmarkCatalog.cases.map(TacticalCaseDefinition::id)
    private val informationLeaf = latencyPreflightLeaves[0]
    private val trustedLeaf = latencyPreflightLeaves[1]

    @Test
    fun `preflight passes when both candidates are sound and one has latency margin`() {
        val informationState = candidate(informationLeaf, p95Millis = 4_400.0)
        val trustedWorld = candidate(trustedLeaf, p95Millis = 5_200.0)

        val report = report(listOf(informationState, trustedWorld))

        assertTrue(informationState.fastLatencyEligible)
        assertFalse(trustedWorld.fastLatencyEligible)
        assertTrue(report.passed, report.failureReasons.toString())
    }

    @Test
    fun `preflight fails when the tactical catalog is incomplete`() {
        val report = assessLatencyPreflight(
            generatedAtUtc = "test",
            outerCommit = "outer",
            argentumCommit = "argentum",
            host = "host",
            deckHash = "deck",
            caseIds = caseIds.dropLast(1),
            candidates = listOf(
                candidate(informationLeaf, p95Millis = 4_400.0),
                candidate(trustedLeaf, p95Millis = 5_200.0),
            ),
        )

        assertFalse(report.passed)
        assertTrue(report.failureReasons.any { it.contains("all 48") })
    }

    @Test
    fun `candidate fails closed on tactical or score regression`() {
        val lowScore = candidate(
            informationLeaf,
            p95Millis = 4_400.0,
            tacticalScore = 0.79,
        )
        val tacticalFailure = candidate(
            trustedLeaf,
            p95Millis = 4_400.0,
            tacticalPassed = false,
        )

        val report = report(listOf(lowScore, tacticalFailure))

        assertFalse(lowScore.passed)
        assertTrue(lowScore.failureReasons.any { it.contains("below 0.8") })
        assertFalse(tacticalFailure.passed)
        assertTrue(tacticalFailure.failureReasons.any { it.contains("tactical pass failed") })
        assertFalse(report.passed)
    }

    @Test
    fun `preflight fails when neither candidate has ten percent latency margin`() {
        val report = report(
            listOf(
                candidate(informationLeaf, p95Millis = 4_501.0),
                candidate(trustedLeaf, p95Millis = 6_000.0),
            )
        )

        assertFalse(report.passed)
        assertTrue(report.failureReasons.any { it.contains("4500.0 ms") })
    }

    private fun report(candidates: List<LatencyPreflightCandidate>) = assessLatencyPreflight(
        generatedAtUtc = "test",
        outerCommit = "outer",
        argentumCommit = "argentum",
        host = "host",
        deckHash = "deck",
        caseIds = caseIds,
        candidates = candidates,
    )

    private fun candidate(
        leaf: LeafEvaluationConfig,
        p95Millis: Double,
        tacticalScore: Double = 0.80,
        tacticalPassed: Boolean = true,
    ): LatencyPreflightCandidate {
        val profile = FrozenSearchProfile(
            id = "fast-arena-v1",
            generatedAtUtc = "test",
            outerCommit = "outer",
            argentumCommit = "argentum",
            host = "host",
            particles = LATENCY_PREFLIGHT_PARTICLES,
            simulations = LATENCY_PREFLIGHT_SIMULATIONS,
            leaf = leaf,
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            measuredP95Millis = 0.0,
            tacticalScore = 0.0,
            standardError = 0.0,
            calibrationReportHash = "work-only",
        )
        val point = CalibrationPoint(
            particles = profile.particles,
            simulations = profile.simulations,
            leaf = leaf,
            actionSpaceProfile = profile.actionSpaceProfile,
            decisionLatenciesMillis = List(caseIds.size) { p95Millis },
            p50Millis = p95Millis,
            p95Millis = p95Millis,
            tacticalScore = tacticalScore,
            standardError = 0.0,
            meanExpansionMillis = 1.0,
            meanBeliefMillis = 1.0,
            meanSearchMillis = p95Millis - 2.0,
        )
        val tactical = tacticalReport(profile.id, tacticalPassed)
        return assessLatencyPreflightCandidate(
            profile = profile,
            warmupPoint = point,
            measuredPoint = point,
            warmupElapsedMillis = p95Millis * caseIds.size,
            measuredElapsedMillis = p95Millis * caseIds.size,
            warmup = tactical,
            measured = tactical,
            jfrPath = EvidenceLocation.WORK.relativePath("latency-preflight/test.jfr"),
            flameSamples = listOf(FlameStackSample("example.Stack", 1)),
        )
    }

    private fun tacticalReport(profileId: String, passed: Boolean): TacticalReport = TacticalReport(
        outerCommit = "outer",
        argentumCommit = "argentum",
        profileId = profileId,
        cases = TacticalBenchmarkCatalog.cases.map { case ->
            TacticalCaseResult(
                id = case.id,
                solved = if (case.mechanicallyVerifiable) passed else null,
                chosenSignature = "choice-${case.hiddenFamily ?: case.id}",
                heuristicSignature = "heuristic",
                candidateCount = 1,
                estimatedCandidateCount = 1,
                searchValue = 0.0,
                regret = null,
                heuristicRegret = null,
                latencyMillis = 1.0,
                expansionMillis = 0.1,
                beliefMillis = 0.1,
                searchMillis = 0.8,
            )
        },
        mechanicallyForcedSolved = if (passed) caseIds.size else caseIds.size - 1,
        mechanicallyForcedTotal = caseIds.size,
        meanStrategicRegret = null,
        heuristicMeanStrategicRegret = null,
        strategicSeparatedCases = 0,
        regretReductionFraction = null,
        regretImprovementConfidenceLower = null,
        regretImprovementConfidenceUpper = null,
        maximumProposalRegret = null,
        hiddenStateFailures = if (passed) 0 else 1,
        proposalStressFailures = 0,
        passed = passed,
        failureReasons = if (passed) emptyList() else listOf("synthetic tactical failure"),
    )
}
