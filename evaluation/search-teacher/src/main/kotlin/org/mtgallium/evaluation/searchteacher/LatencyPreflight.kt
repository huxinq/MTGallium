package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import jdk.jfr.Configuration
import jdk.jfr.Recording
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val LATENCY_PREFLIGHT_PARTICLES = 8
internal const val LATENCY_PREFLIGHT_SIMULATIONS = 64
internal const val LATENCY_PREFLIGHT_MAXIMUM_P95_MILLIS = 4_500.0
internal const val LATENCY_PREFLIGHT_MINIMUM_TACTICAL_SCORE = 0.80

internal val latencyPreflightLeaves = listOf(
    LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
    LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.ARGENTUM_BOARD_V1),
)

/**
 * A deliberately work-only diagnostic before the expensive 160-point calibration.
 *
 * Each viable fast-profile leaf regime receives a complete warm-up pass followed by a JFR-profiled
 * measured pass. The bounded-rollout regime is intentionally excluded because the existing baseline
 * already rules it out for the unchanged five-second fast-profile gate.
 */
internal class LatencyPreflightRunner(
    private val root: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val manifest: DeckManifest,
    private val caseLimit: Int = TacticalBenchmarkCatalog.cases.size,
) {
    init {
        require(caseLimit in 1..TacticalBenchmarkCatalog.cases.size)
    }

    fun run(): LatencyPreflightReport {
        val generatedAt = Instant.now().toString()
        val host = calibrationHost()
        val cases = TacticalBenchmarkCatalog.cases.take(caseLimit)
        val runDirectory = EvidenceStore(root).diagnostic(
            "latency-preflight",
            "the latency preflight recordings",
        )
            .resolve(generatedAt.replace(Regex("[^A-Za-z0-9.-]"), "-"))
        Files.createDirectories(runDirectory)
        val candidates = latencyPreflightLeaves.map { leaf ->
            runCandidate(generatedAt, host, cases, leaf, runDirectory)
        }
        return assessLatencyPreflight(
            generatedAtUtc = generatedAt,
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            host = host,
            deckHash = manifest.deckHash(),
            caseIds = cases.map(TacticalCaseDefinition::id),
            candidates = candidates,
        )
    }

    private fun runCandidate(
        generatedAt: String,
        host: String,
        cases: List<TacticalCaseDefinition>,
        leaf: LeafEvaluationConfig,
        runDirectory: Path,
    ): LatencyPreflightCandidate {
        val profile = measurementProfile(
            generatedAt = generatedAt,
            host = host,
            particles = LATENCY_PREFLIGHT_PARTICLES,
            simulations = LATENCY_PREFLIGHT_SIMULATIONS,
            leaf = leaf,
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            id = "fast-arena-v1",
            reportHash = "latency-preflight-work-only",
        )
        println("Latency preflight warm-up ${profile.particles}x${profile.simulations}/$leaf")
        val warmupStarted = System.nanoTime()
        val warmup = TacticalBenchmarkRunner(registry, manifest, profile).run(cases)
        val warmupElapsedMillis = elapsedMillisSince(warmupStarted)
        val warmupPoint = summarizeCalibrationPoint(
            profile.particles,
            profile.simulations,
            leaf,
            profile.actionSpaceProfile,
            cases,
            warmup,
        )

        val fileStem = "${leaf.stateSource.name}-${leaf.evaluator.name}".lowercase().replace('_', '-')
        val jfrPath = runDirectory.resolve("p8-s64-$fileStem.jfr")
        val recording = Recording(Configuration.getConfiguration("profile")).apply {
            name = "mtgallium-latency-preflight-$fileStem"
            enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10))
        }
        println("Latency preflight measured ${profile.particles}x${profile.simulations}/$leaf")
        val measuredStarted = System.nanoTime()
        val measured = recording.use {
            it.start()
            try {
                TacticalBenchmarkRunner(registry, manifest, profile).run(cases)
            } finally {
                it.stop()
                it.dump(jfrPath)
            }
        }
        val measuredElapsedMillis = elapsedMillisSince(measuredStarted)
        val measuredPoint = summarizeCalibrationPoint(
            profile.particles,
            profile.simulations,
            leaf,
            profile.actionSpaceProfile,
            cases,
            measured,
        )
        val flameSamples = readFlameSamples(jfrPath)
        return assessLatencyPreflightCandidate(
            profile = profile,
            warmupPoint = warmupPoint,
            measuredPoint = measuredPoint,
            warmupElapsedMillis = warmupElapsedMillis,
            measuredElapsedMillis = measuredElapsedMillis,
            warmup = warmup,
            measured = measured,
            jfrPath = root.relativize(jfrPath).toString(),
            flameSamples = flameSamples,
        )
    }
}

internal fun assessLatencyPreflightCandidate(
    profile: FrozenSearchProfile,
    warmupPoint: CalibrationPoint,
    measuredPoint: CalibrationPoint,
    warmupElapsedMillis: Double,
    measuredElapsedMillis: Double,
    warmup: TacticalReport,
    measured: TacticalReport,
    jfrPath: String,
    flameSamples: List<FlameStackSample>,
): LatencyPreflightCandidate {
    val executionSamples = flameSamples.sumOf(FlameStackSample::samples)
    val expectedCaseIds = TacticalBenchmarkCatalog.cases.map(TacticalCaseDefinition::id)
    val warmupCaseIds = warmup.cases.map(TacticalCaseResult::id)
    val measuredCaseIds = measured.cases.map(TacticalCaseResult::id)
    val signaturesStable = warmup.cases.map { it.id to it.chosenSignature } ==
        measured.cases.map { it.id to it.chosenSignature }
    val failures = buildList {
        if (profile.particles != LATENCY_PREFLIGHT_PARTICLES ||
            profile.simulations != LATENCY_PREFLIGHT_SIMULATIONS
        ) {
            add("candidate is not the fixed 8-particle, 64-simulation preflight configuration")
        }
        if (profile.leaf !in latencyPreflightLeaves) {
            add("candidate uses an unsupported preflight leaf configuration: ${profile.leaf}")
        }
        if (!pointMatchesProfile(warmupPoint, profile) || !pointMatchesProfile(measuredPoint, profile)) {
            add("one or more point summaries do not match the candidate profile")
        }
        if (warmupCaseIds != expectedCaseIds || measuredCaseIds != expectedCaseIds) {
            add("candidate did not execute all ${expectedCaseIds.size} tactical cases in catalog order")
        }
        if (!warmup.passed) add("warm-up tactical pass failed: ${warmup.failureReasons.joinToString()}")
        if (!measured.passed) add("measured tactical pass failed: ${measured.failureReasons.joinToString()}")
        if (warmupPoint.tacticalScore < LATENCY_PREFLIGHT_MINIMUM_TACTICAL_SCORE) {
            add(
                "warm-up tactical score ${warmupPoint.tacticalScore} is below " +
                    LATENCY_PREFLIGHT_MINIMUM_TACTICAL_SCORE
            )
        }
        if (measuredPoint.tacticalScore < LATENCY_PREFLIGHT_MINIMUM_TACTICAL_SCORE) {
            add(
                "measured tactical score ${measuredPoint.tacticalScore} is below " +
                    LATENCY_PREFLIGHT_MINIMUM_TACTICAL_SCORE
            )
        }
        if (!signaturesStable) add("chosen signatures changed between warm-up and measured passes")
        if (!validTiming(warmupPoint, warmupElapsedMillis) || !validTiming(measuredPoint, measuredElapsedMillis)) {
            add("one or more preflight timing measurements were invalid")
        }
        if (executionSamples == 0) add("JFR contained no execution samples")
        if (jfrPath.isBlank()) add("JFR path was not recorded")
    }
    val tacticalFailures = buildList {
        addAll(warmup.failureReasons.map { "warm-up: $it" })
        addAll(measured.failureReasons.map { "measured: $it" })
    }
    return LatencyPreflightCandidate(
        profile = profile,
        warmupPoint = warmupPoint,
        measuredPoint = measuredPoint,
        warmupElapsedMillis = warmupElapsedMillis,
        measuredElapsedMillis = measuredElapsedMillis,
        warmupTacticalPassed = warmup.passed,
        measuredTacticalPassed = measured.passed,
        tacticalFailureReasons = tacticalFailures,
        choiceSignaturesStable = signaturesStable,
        failedCaseIds = measured.cases.filter { it.solved == false || it.diagnostic != null }
            .map(TacticalCaseResult::id),
        jfrPath = jfrPath,
        executionSamples = executionSamples,
        topFlameStacks = flameSamples.take(50),
        slowestCases = measured.cases.sortedByDescending(TacticalCaseResult::latencyMillis).take(12).map {
            LatencyPreflightSlowCase(
                id = it.id,
                latencyMillis = it.latencyMillis,
                expansionMillis = it.expansionMillis,
                beliefMillis = it.beliefMillis,
                searchMillis = it.searchMillis,
            )
        },
        fastLatencyEligible = measuredPoint.p95Millis <= LATENCY_PREFLIGHT_MAXIMUM_P95_MILLIS,
        passed = failures.isEmpty(),
        failureReasons = failures,
    )
}

internal fun assessLatencyPreflight(
    generatedAtUtc: String,
    outerCommit: String,
    argentumCommit: String,
    host: String,
    deckHash: String,
    caseIds: List<String>,
    candidates: List<LatencyPreflightCandidate>,
): LatencyPreflightReport {
    val expectedCaseIds = TacticalBenchmarkCatalog.cases.map(TacticalCaseDefinition::id)
    val failures = buildList {
        if (caseIds != expectedCaseIds) {
            add("preflight used ${caseIds.size} cases; the diagnostic requires all ${expectedCaseIds.size} in catalog order")
        }
        if (candidates.map { it.profile.leaf }.toSet() != latencyPreflightLeaves.toSet() ||
            candidates.size != latencyPreflightLeaves.size
        ) {
            add("preflight did not measure exactly the information-state and trusted-world candidates")
        }
        candidates.filterNot(LatencyPreflightCandidate::passed).forEach { candidate ->
            add("${candidate.profile.leaf} candidate failed: ${candidate.failureReasons.joinToString()}")
        }
        if (candidates.none(LatencyPreflightCandidate::fastLatencyEligible)) {
            add("no candidate met the $LATENCY_PREFLIGHT_MAXIMUM_P95_MILLIS ms preflight p95 limit")
        }
    }
    return LatencyPreflightReport(
        generatedAtUtc = generatedAtUtc,
        outerCommit = outerCommit,
        argentumCommit = argentumCommit,
        host = host,
        deckHash = deckHash,
        caseIds = caseIds,
        expectedCaseCount = expectedCaseIds.size,
        particles = LATENCY_PREFLIGHT_PARTICLES,
        simulations = LATENCY_PREFLIGHT_SIMULATIONS,
        maximumFastP95Millis = LATENCY_PREFLIGHT_MAXIMUM_P95_MILLIS,
        minimumTacticalScore = LATENCY_PREFLIGHT_MINIMUM_TACTICAL_SCORE,
        candidates = candidates,
        passed = failures.isEmpty(),
        failureReasons = failures,
    )
}

private fun pointMatchesProfile(point: CalibrationPoint, profile: FrozenSearchProfile): Boolean =
    point.particles == profile.particles &&
        point.simulations == profile.simulations &&
        point.leaf == profile.leaf &&
        point.actionSpaceProfile == profile.actionSpaceProfile

private fun validTiming(point: CalibrationPoint, elapsedMillis: Double): Boolean =
    elapsedMillis.isFinite() && elapsedMillis > 0.0 &&
        point.decisionLatenciesMillis.isNotEmpty() &&
        point.decisionLatenciesMillis.all { it.isFinite() && it > 0.0 } &&
        point.p50Millis.isFinite() && point.p50Millis > 0.0 &&
        point.p95Millis.isFinite() && point.p95Millis > 0.0

private fun elapsedMillisSince(startedNanos: Long): Double =
    (System.nanoTime() - startedNanos) / 1_000_000.0
