package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile

internal fun measurementProfile(
    generatedAt: String,
    host: String,
    particles: Int,
    simulations: Int,
    leaf: LeafEvaluationConfig,
    actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
    maxPolicyDecisions: Int = 256,
    id: String = "deep-teacher-v1",
    reportHash: String = "calibration-in-progress",
) = FrozenSearchProfile(
    id = id,
    generatedAtUtc = generatedAt,
    outerCommit = currentOuterCommit(),
    argentumCommit = currentArgentumCommit(),
    host = host,
    particles = particles,
    simulations = simulations,
    leaf = leaf,
    actionSpaceProfile = actionSpaceProfile,
    maxPolicyDecisions = maxPolicyDecisions,
    measuredP95Millis = 0.0,
    tacticalScore = 0.0,
    standardError = 0.0,
    calibrationReportHash = reportHash,
)

internal fun summarizeCalibrationPoint(
    particles: Int,
    simulations: Int,
    leaf: LeafEvaluationConfig,
    actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
    cases: List<TacticalCaseDefinition>,
    report: TacticalReport,
): CalibrationPoint {
    require(report.cases.map(TacticalCaseResult::id) == cases.map(TacticalCaseDefinition::id)) {
        "Tactical results do not match the requested calibration cases"
    }
    val latencies = report.cases.map(TacticalCaseResult::latencyMillis)
    val scored = report.cases.filter { result ->
        cases.single { it.id == result.id }.mechanicallyVerifiable
    }
    val hiddenGroups = report.cases.filter { it.id.startsWith("hidden-") }
        .groupBy { it.id.substringBeforeLast('-') }
    val hiddenScore = hiddenGroups.count { (_, pair) ->
        pair.size == 2 && pair.map(TacticalCaseResult::chosenSignature).distinct().size == 1
    }
    val trials = scored.size + hiddenGroups.size
    val successes = scored.count { it.solved == true } + hiddenScore
    val score = if (trials == 0) 0.0 else successes.toDouble() / trials
    return CalibrationPoint(
        particles = particles,
        simulations = simulations,
        leaf = leaf,
        actionSpaceProfile = actionSpaceProfile,
        decisionLatenciesMillis = latencies,
        p50Millis = percentile(latencies, 0.50),
        p95Millis = percentile(latencies, 0.95),
        tacticalScore = score,
        standardError = bernoulliStandardError(score, trials),
        meanExpansionMillis = report.cases.map(TacticalCaseResult::expansionMillis).average(),
        meanBeliefMillis = report.cases.map(TacticalCaseResult::beliefMillis).average(),
        meanSearchMillis = report.cases.map(TacticalCaseResult::searchMillis).average(),
    )
}

internal fun bernoulliStandardError(score: Double, trials: Int): Double = when {
    trials <= 0 -> 0.0
    else -> sqrt(score * (1.0 - score) / trials)
}

internal fun calibrationHost(): String {
    val cpu = runCatching {
        Files.readAllLines(Path.of("/proc/cpuinfo"))
            .firstOrNull { it.startsWith("model name") }
            ?.substringAfter(':')
            ?.trim()
    }.getOrNull()
    return listOfNotNull(
        cpu,
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        "${Runtime.getRuntime().availableProcessors()}-processors",
    ).joinToString(" | ")
}
