package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable

@Serializable
data class BaselineHardeningArtifact(val kind: String, val path: String, val sha256: String)

@Serializable
data class BaselineHardeningManifest(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val runIdentity: String,
    val outerCommit: String,
    val argentumCommit: String,
    val profileHash: String,
    val sourceCorpusHash: String,
    val conformanceGames: Int,
    val artifacts: List<BaselineHardeningArtifact>,
    val passed: Boolean,
)

@Serializable
data class ThroughputStageBreakdown(
    val expansionMillis: Double,
    val beliefMillis: Double,
    val searchMillis: Double,
    val expansionFraction: Double,
    val beliefFraction: Double,
    val searchFraction: Double,
)

@Serializable
data class FlameStackSample(val collapsedStack: String, val samples: Int)

@Serializable
data class ThroughputReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val profileId: String,
    val profileHash: String,
    val host: String,
    val deckHash: String,
    val caseIds: List<String>,
    val elapsedMillis: Double,
    val decisionP50Millis: Double,
    val decisionP95Millis: Double,
    val decisionsPerSecond: Double,
    val simulationsPerSecond: Double,
    val stageBreakdown: ThroughputStageBreakdown,
    val jfrPath: String,
    val executionSamples: Int,
    val topFlameStacks: List<FlameStackSample>,
    val passed: Boolean,
    val failureReasons: List<String>,
)

@Serializable
data class LatencyPreflightSlowCase(
    val id: String,
    val latencyMillis: Double,
    val expansionMillis: Double,
    val beliefMillis: Double,
    val searchMillis: Double,
)

@Serializable
data class LatencyPreflightCandidate(
    val profile: FrozenSearchProfile,
    val warmupPoint: CalibrationPoint,
    val measuredPoint: CalibrationPoint,
    val warmupElapsedMillis: Double,
    val measuredElapsedMillis: Double,
    val warmupTacticalPassed: Boolean,
    val measuredTacticalPassed: Boolean,
    val tacticalFailureReasons: List<String>,
    val choiceSignaturesStable: Boolean,
    val failedCaseIds: List<String>,
    val jfrPath: String,
    val executionSamples: Int,
    val topFlameStacks: List<FlameStackSample>,
    val slowestCases: List<LatencyPreflightSlowCase>,
    val fastLatencyEligible: Boolean,
    val passed: Boolean,
    val failureReasons: List<String>,
)

@Serializable
data class LatencyPreflightReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val host: String,
    val deckHash: String,
    val caseIds: List<String>,
    val expectedCaseCount: Int,
    val particles: Int,
    val simulations: Int,
    val maximumFastP95Millis: Double,
    val minimumTacticalScore: Double,
    val candidates: List<LatencyPreflightCandidate>,
    val passed: Boolean,
    val failureReasons: List<String>,
)
