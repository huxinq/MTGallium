package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.sqrt
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

/** Measures the fixed compute grid and records two deterministic profile candidates. */
internal class SearchCalibration(
    private val manifest: DeckManifest,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val root: Path,
    private val caseLimit: Int = TacticalBenchmarkCatalog.cases.size,
) {
    private val evidence = EvidenceStore(root)

    init {
        require(caseLimit in 1..TacticalBenchmarkCatalog.cases.size)
    }

    fun run(resume: Boolean = true): CalibrationReport {
        val grid = loadSearchGrid()
        val cases = TacticalBenchmarkCatalog.cases.take(caseLimit)
        val caseIds = cases.map(TacticalCaseDefinition::id)
        val generatedAt = Instant.now().toString()
        val host = calibrationHost()
        var resumedPointCount = 0
        val points = buildList {
            grid.particles.forEach { particles ->
                grid.simulations.forEach { simulations ->
                    grid.leafConfigurations.forEach { leaf ->
                        grid.actionSpaceProfiles.forEach actionProfile@{ actionSpaceProfile ->
                            val checkpointPath = checkpointPath(
                                particles,
                                simulations,
                                leaf,
                                actionSpaceProfile,
                            )
                            val cached = checkpointPath.takeIf { resume && Files.exists(it) }
                                ?.let { loadCheckpoint(it, host, caseIds, grid.schemaVersion) }
                            if (cached != null) {
                                add(cached)
                                resumedPointCount++
                                println("Calibration resumed ${particles}x$simulations/$leaf/$actionSpaceProfile")
                                return@actionProfile
                            }
                            val profile = measurementProfile(
                                generatedAt = generatedAt,
                                host = host,
                                particles = particles,
                                simulations = simulations,
                                leaf = leaf,
                                actionSpaceProfile = actionSpaceProfile,
                            )
                            val report = TacticalBenchmarkRunner(registry, manifest, profile).run(cases)
                            val point = summarizeCalibrationPoint(
                                particles = particles,
                                simulations = simulations,
                                leaf = leaf,
                                actionSpaceProfile = actionSpaceProfile,
                                cases = cases,
                                report = report,
                            )
                            add(point)
                            writeCheckpoint(
                                checkpointPath,
                                CalibrationCheckpoint(
                                    outerCommit = currentOuterCommit(),
                                    argentumCommit = currentArgentumCommit(),
                                    host = host,
                                    deckHash = manifest.deckHash(),
                                    caseIds = caseIds,
                                    gridSchemaVersion = grid.schemaVersion,
                                    point = point,
                                )
                            )
                            println("Calibration saved ${particles}x$simulations/$leaf/$actionSpaceProfile")
                        }
                    }
                }
            }
        }
        val calibrationHash = sha256(
            evidenceJson.encodeToString(points) + ":${manifest.deckHash()}:$host:$caseLimit"
        )
        val fast = select(
            id = "fast-arena-v1",
            generatedAt = generatedAt,
            host = host,
            points = points,
            latencyLimitMillis = grid.fastP95LimitMillis,
            calibrationHash = calibrationHash,
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
        )
        val deep = select(
            id = "deep-teacher-v1",
            generatedAt = generatedAt,
            host = host,
            points = points,
            latencyLimitMillis = grid.deepP95LimitMillis,
            calibrationHash = calibrationHash,
            actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
        )
        val intervals = computeImprovementIntervals(points)
        val computeTrendPassed = intervals.any(ComputeImprovementInterval::improved)
        val expectedPointCount = grid.particles.size * grid.simulations.size *
            grid.leafConfigurations.size * grid.actionSpaceProfiles.size
        val failures = buildList {
            if (caseIds.size != TacticalBenchmarkCatalog.cases.size) {
                add("calibration used ${caseIds.size} cases; the full comparison uses ${TacticalBenchmarkCatalog.cases.size}")
            }
            if (points.size != expectedPointCount) add("expected $expectedPointCount grid points, recorded ${points.size}")
            if (fast == null) add("no configuration met the fast p95 latency limit")
            if (deep == null) add("no configuration met the deep p95 latency limit")
            if (!computeTrendPassed) add("no consecutive simulation-budget interval improved tactical score")
        }
        return CalibrationReport(
            generatedAtUtc = generatedAt,
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            host = host,
            deckHash = manifest.deckHash(),
            caseIds = caseIds,
            expectedPointCount = expectedPointCount,
            resumedPointCount = resumedPointCount,
            points = points,
            selectedFast = fast,
            selectedDeep = deep,
            computeImprovementIntervals = intervals,
            computeTrendPassed = computeTrendPassed,
            passed = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    fun writeProfiles(report: CalibrationReport): List<Path> {
        require(report.passed) { "Cannot record profiles from a failed calibration: ${report.failureReasons}" }
        return listOfNotNull(
            report.selectedFast?.let { writeProfile("fast-profile-v1.json", it) },
            report.selectedDeep?.let { writeProfile("deep-profile-v1.json", it) },
        )
    }

    private fun checkpointPath(
        particles: Int,
        simulations: Int,
        leaf: LeafEvaluationConfig,
        actionSpaceProfile: SearchActionSpaceProfile,
    ): Path =
        evidence.diagnostic(
            "calibration",
            "the search-setting calibration checkpoints",
        )
            .resolve(
                "p${particles}-s${simulations}-${leaf.stateSource.name.lowercase()}-" +
                    "${leaf.evaluator.name.lowercase()}-${actionSpaceProfile.profileId}.json"
            )

    private fun loadCheckpoint(
        path: Path,
        host: String,
        caseIds: List<String>,
        gridSchemaVersion: Int,
    ): CalibrationPoint? = runCatching {
        val checkpoint = evidenceJson.decodeFromString<CalibrationCheckpoint>(Files.readString(path))
        checkpoint.takeIf {
            it.outerCommit == currentOuterCommit() &&
                it.argentumCommit == currentArgentumCommit() &&
                it.host == host &&
                it.deckHash == manifest.deckHash() &&
                it.caseIds == caseIds &&
                it.gridSchemaVersion == gridSchemaVersion
        }?.point
    }.getOrNull()

    private fun writeCheckpoint(path: Path, checkpoint: CalibrationCheckpoint) {
        writeJsonAtomically(path, checkpoint)
    }

    private fun writeProfile(name: String, profile: FrozenSearchProfile): Path {
        val path = evidence.work("calibration/profiles/$name")
        Files.createDirectories(path.parent)
        writeTextAtomically(path, evidenceJson.encodeToString(profile) + "\n")
        return path
    }

    private fun select(
        id: String,
        generatedAt: String,
        host: String,
        points: List<CalibrationPoint>,
        latencyLimitMillis: Long,
        calibrationHash: String,
        actionSpaceProfile: SearchActionSpaceProfile,
    ): FrozenSearchProfile? {
        val eligible = points.filter {
            it.p95Millis <= latencyLimitMillis && it.actionSpaceProfile == actionSpaceProfile
        }
        if (eligible.isEmpty()) return null
        val best = eligible.maxBy { it.tacticalScore }
        val tied = eligible.filter { it.tacticalScore >= best.tacticalScore - best.standardError }
        val selected = tied.minWith(
            compareBy<CalibrationPoint> { it.particles.toLong() * it.simulations }
                .thenBy { it.p95Millis }
                .thenBy { it.leaf.stateSource.name }
                .thenBy { it.leaf.evaluator.name }
        )
        return FrozenSearchProfile(
            id = id,
            generatedAtUtc = generatedAt,
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            host = host,
            particles = selected.particles,
            simulations = selected.simulations,
            leaf = selected.leaf,
            actionSpaceProfile = selected.actionSpaceProfile,
            measuredP95Millis = selected.p95Millis,
            tacticalScore = selected.tacticalScore,
            standardError = selected.standardError,
            calibrationReportHash = calibrationHash,
        )
    }

}

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

internal fun computeImprovementIntervals(points: List<CalibrationPoint>): List<ComputeImprovementInterval> =
    points.groupBy { Triple(it.particles, it.leaf, it.actionSpaceProfile) }
        .toSortedMap(compareBy<Triple<Int, LeafEvaluationConfig, SearchActionSpaceProfile>> { it.first }
            .thenBy { it.second.stateSource.name }
            .thenBy { it.second.evaluator.name }
            .thenBy { it.third.name })
        .flatMap { (_, group) ->
            group.sortedBy(CalibrationPoint::simulations).zipWithNext { from, to ->
                val improvement = to.tacticalScore - from.tacticalScore
                ComputeImprovementInterval(
                    particles = from.particles,
                    leaf = from.leaf,
                    actionSpaceProfile = from.actionSpaceProfile,
                    fromSimulations = from.simulations,
                    toSimulations = to.simulations,
                    fromTacticalScore = from.tacticalScore,
                    toTacticalScore = to.tacticalScore,
                    scoreImprovement = improvement,
                    improved = improvement > 0.0,
                )
            }
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
