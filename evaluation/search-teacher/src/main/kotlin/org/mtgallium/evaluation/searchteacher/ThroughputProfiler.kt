package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import jdk.jfr.Configuration
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

/** Produces latency-stage and sampled-stack evidence without changing deterministic search budgets. */
internal class ThroughputProfiler(
    private val root: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val manifest: DeckManifest,
    private val profile: FrozenSearchProfile,
) {
    internal fun outputDirectory(): Path = EvidenceStore(root).diagnostic(
        "throughput",
        "the throughput profiler recording",
    )

    fun run(caseLimit: Int = TacticalBenchmarkCatalog.cases.size): ThroughputReport {
        require(caseLimit in 1..TacticalBenchmarkCatalog.cases.size)
        val cases = TacticalBenchmarkCatalog.cases.take(caseLimit)
        val outputDirectory = outputDirectory()
        Files.createDirectories(outputDirectory)
        val jfrPath = outputDirectory.resolve("${profile.id}.jfr")
        val recording = Recording(Configuration.getConfiguration("profile")).apply {
            name = "mtgallium-${profile.id}"
            enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10))
        }
        val started = System.nanoTime()
        val tactical = recording.use {
            it.start()
            try {
                TacticalBenchmarkRunner(registry, manifest, profile).run(cases)
            } finally {
                it.stop()
                it.dump(jfrPath)
            }
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000.0
        val latencies = tactical.cases.map(TacticalCaseResult::latencyMillis)
        val expansion = tactical.cases.sumOf(TacticalCaseResult::expansionMillis)
        val belief = tactical.cases.sumOf(TacticalCaseResult::beliefMillis)
        val search = tactical.cases.sumOf(TacticalCaseResult::searchMillis)
        val measuredTotal = expansion + belief + search
        val flameSamples = readFlameSamples(jfrPath)
        val executionSamples = flameSamples.sumOf(FlameStackSample::samples)
        val latencyLimit = when (profile.id) {
            "fast-arena-v1" -> loadSearchGrid().fastP95LimitMillis
            "deep-teacher-v1" -> loadSearchGrid().deepP95LimitMillis
            else -> 0L
        }
        val p95 = percentile(latencies, 0.95)
        val failures = buildList {
            if (cases.size != TacticalBenchmarkCatalog.cases.size) {
                add("profiled ${cases.size} cases; the frozen gate requires ${TacticalBenchmarkCatalog.cases.size}")
            }
            if (latencyLimit == 0L) add("profile is not a recognized frozen profile")
            if (p95 > latencyLimit) add("measured p95 $p95 ms exceeds the $latencyLimit ms profile limit")
            if (executionSamples == 0) add("JFR contained no execution samples")
            if (tactical.cases.any { !it.latencyMillis.isFinite() || it.latencyMillis <= 0.0 }) {
                add("one or more tactical latency measurements were invalid")
            }
        }
        return ThroughputReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            profileId = profile.id,
            profileHash = sha256(evidenceJson.encodeToString(profile)),
            host = calibrationHost(),
            deckHash = manifest.deckHash(),
            caseIds = cases.map(TacticalCaseDefinition::id),
            elapsedMillis = elapsedMillis,
            decisionP50Millis = percentile(latencies, 0.50),
            decisionP95Millis = p95,
            decisionsPerSecond = cases.size * 1_000.0 / elapsedMillis,
            simulationsPerSecond = cases.size.toDouble() * profile.simulations * 1_000.0 / elapsedMillis,
            stageBreakdown = ThroughputStageBreakdown(
                expansionMillis = expansion,
                beliefMillis = belief,
                searchMillis = search,
                expansionFraction = expansion / measuredTotal,
                beliefFraction = belief / measuredTotal,
                searchFraction = search / measuredTotal,
            ),
            jfrPath = root.relativize(jfrPath).toString(),
            executionSamples = executionSamples,
            topFlameStacks = flameSamples.take(50),
            passed = failures.isEmpty(),
            failureReasons = failures,
        )
    }
}

internal fun readFlameSamples(path: Path): List<FlameStackSample> {
    val counts = mutableMapOf<String, Int>()
    RecordingFile(path).use { recording ->
        while (recording.hasMoreEvents()) {
            val event = recording.readEvent()
            if (event.eventType.name != "jdk.ExecutionSample") continue
            val frames = event.stackTrace?.frames.orEmpty()
            if (frames.isEmpty()) continue
            val collapsed = frames.asReversed().joinToString(";") { frame ->
                "${frame.method.type.name}.${frame.method.name}"
            }
            counts[collapsed] = (counts[collapsed] ?: 0) + 1
        }
    }
    return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { FlameStackSample(it.key, it.value) }
}
