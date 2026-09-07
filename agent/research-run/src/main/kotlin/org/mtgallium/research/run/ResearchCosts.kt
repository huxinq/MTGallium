package org.mtgallium.research.run

import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ResearchStageDisposition { COMPLETED, REUSED, FAILED }

/** Invocation wall/CPU cost is separate from accumulated worker/component time and historical work. */
@Serializable
data class ResearchStageCost(
    val stage: String,
    val startedAtUtc: String,
    val disposition: ResearchStageDisposition,
    val wallMillis: Double,
    val processCpuMillis: Double?,
    val evidenceIdentity: String? = null,
    val counts: Map<String, Long> = emptyMap(),
    val accumulatedComponentMillis: Map<String, Double> = emptyMap(),
    val diagnostic: String? = null,
) {
    init {
        require(stage.matches(Regex("[a-z][a-z0-9-]*")))
        require(wallMillis.isFinite() && wallMillis >= 0)
        require(processCpuMillis == null || processCpuMillis.isFinite() && processCpuMillis >= 0)
        require(counts.values.all { it >= 0 })
        require(accumulatedComponentMillis.values.all { it.isFinite() && it >= 0 })
        require((disposition == ResearchStageDisposition.FAILED) == (diagnostic != null))
    }
}

@Serializable
data class ResearchInvocationCosts(
    val schemaVersion: Int = 1,
    val startedAtUtc: String,
    val wallMillis: Double,
    val processCpuMillis: Double?,
    val stages: List<ResearchStageCost>,
    val interpretation: String = "Wall time is elapsed time of this invocation. Process CPU includes all JVM threads, not child processes. Component times may overlap each other and wall time. Reused evidence has zero new scientific work; its original production cost is not estimated or added. Unavailable CPU is null. Agent effort and machine-wide energy are not measured.",
)

/** A small stage journal, not a scheduler. Persist after every stage, including failure. */
class ResearchCostRecorder(
    private val output: Path,
    private val nanoTime: () -> Long = System::nanoTime,
    private val processCpuNanos: () -> Long? = {
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.processCpuTime?.takeIf { it >= 0 }
    },
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val startedAt = Instant.now().toString()
    private val started = nanoTime()
    private val cpuStarted = processCpuNanos()
    private val stages = mutableListOf<ResearchStageCost>()

    fun <T> measure(stage: String, reused: Boolean = false, describe: (T) -> ResearchMeasuredWork = { ResearchMeasuredWork() }, validate: (T) -> Unit = {}, action: () -> T): T {
        require(stages.none { it.stage == stage }) { "Duplicate stage in one invocation: $stage" }
        val utc = Instant.now().toString(); val begin = nanoTime(); val cpu = processCpuNanos()
        var described: ResearchMeasuredWork? = null
        try {
            val result = action()
            val work = describe(result)
            described = work
            validate(result)
            require(!reused || (work.counts.values.all { it == 0L } && work.accumulatedComponentMillis.values.all { it == 0.0 })) { "Reused stage must not count historical work as new work" }
            stages += ResearchStageCost(stage, utc, if (reused) ResearchStageDisposition.REUSED else ResearchStageDisposition.COMPLETED,
                elapsed(begin), cpuElapsed(cpu), work.evidenceIdentity, work.counts, work.accumulatedComponentMillis)
            persist()
            return result
        } catch (failure: Exception) {
            stages += ResearchStageCost(stage, utc, ResearchStageDisposition.FAILED, elapsed(begin), cpuElapsed(cpu),
                evidenceIdentity = described?.evidenceIdentity, counts = described?.counts ?: emptyMap(),
                accumulatedComponentMillis = described?.accumulatedComponentMillis ?: emptyMap(), diagnostic = "${failure.javaClass.simpleName}: ${failure.message}")
            persist()
            throw failure
        }
    }

    private fun elapsed(begin: Long) = (nanoTime() - begin).coerceAtLeast(0) / 1_000_000.0
    private fun cpuElapsed(begin: Long?): Double? = begin?.let { first -> processCpuNanos()?.let { (it - first).coerceAtLeast(0) / 1_000_000.0 } }
    fun snapshot() = ResearchInvocationCosts(startedAtUtc = startedAt, wallMillis = elapsed(started), processCpuMillis = cpuElapsed(cpuStarted), stages = stages.toList())
    fun persist() = ResearchRunFiles.atomicWrite(output, json.encodeToString(snapshot()))
}

data class ResearchMeasuredWork(
    val evidenceIdentity: String? = null,
    val counts: Map<String, Long> = emptyMap(),
    val accumulatedComponentMillis: Map<String, Double> = emptyMap(),
)
