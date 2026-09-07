package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import jdk.jfr.consumer.RecordingFile
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.researchSha256File

/** Stable precedence makes the cost table a partition, unlike inclusive flame-stack counts. */
internal const val SEARCH_COST_CLASSIFICATION = "search-stack-cost-v1"

internal data class SearchProfileStack(val frames: List<String>, val truncated: Boolean = false)

internal class SearchProfileSummary {
    var executionSamples = 0L
        private set
    var missingStackSamples = 0L
        private set
    var truncatedStackSamples = 0L
        private set
    var searchSamples = 0L
        private set
    var otherSamples = 0L
        private set
    val exclusiveCosts = linkedMapOf<String, Long>()
    val inclusiveCosts = linkedMapOf<String, Long>()
    val digestCallers = linkedMapOf<String, Long>()
    val allocationWeights = linkedMapOf<String, Long>()
    var allocationSamples = 0L
        private set

    fun addExecution(stack: SearchProfileStack) {
        executionSamples++
        when {
            stack.frames.isEmpty() -> missingStackSamples++
            stack.truncated -> truncatedStackSamples++
            !stack.isSearch() -> otherSamples++
            else -> {
                searchSamples++
                val category = COST_PATHS.firstOrNull { (_, paths) -> paths.any(stack::has) }?.first ?: "Other search"
                exclusiveCosts.increment(category)
                INCLUSIVE_PATHS.forEach { (label, path) -> if (stack.has(path)) inclusiveCosts.increment(label) }
                val digest = stack.frames.indexOfFirst { it.endsWith("PolicyJson.digest") }
                if (digest >= 0) digestCallers.increment(stack.frames.drop(digest + 1)
                    .firstOrNull { !it.contains("PolicyJson") } ?: "unknown caller")
            }
        }
    }

    fun addAllocation(stack: SearchProfileStack, weight: Long) {
        require(weight >= 0)
        if (stack.truncated || !stack.isSearch()) return
        allocationSamples++
        val caller = stack.frames.firstOrNull { it.startsWith("org.mtgallium.") || it.startsWith("com.wingedsheep.") }
            ?: "unknown caller"
        allocationWeights.increment(caller, weight)
    }

    companion object {
        private val COST_PATHS = listOf(
            "Canonical digest" to listOf("PolicyJson.digest"),
            "Legal enumeration" to listOf("LegalActionEnumerator.enumerate"),
            "Engine action processing" to listOf("ActionProcessor.process"),
            "Other observation projection" to listOf("SafeObservationProjector.project", "SafeObservationProjection.withPriority", "ObservationBuilder.build"),
            "Other semantic expansion" to listOf("UnifiedSemanticExpander."),
            "Learned feature encoding" to listOf("NeuralBehavioralCloningFeatureEncoder."),
            "Learned matrix inference" to listOf("CompiledRootActionKernel."),
            "Other history / knowledge" to listOf("PerspectiveHistory.", "PolicyKnowledgeAccumulator."),
        )
        private val INCLUSIVE_PATHS = listOf(
            "Rollout" to "InformationSetSearch.rollout",
            "Full safe projection" to "SafeObservationProjector.project",
            "Priority-only safe projection" to "SafeObservationProjection.withPriority",
            "Gym observation construction" to "ObservationBuilder.build",
            "Knowledge snapshot" to "PolicyKnowledgeAccumulator.snapshot",
            "History fork" to "PerspectiveHistory.fork",
            "Knowledge fork" to "PolicyKnowledgeAccumulator.fork",
        )
    }
}

private fun SearchProfileStack.has(path: String): Boolean = frames.any { path in it }
private fun SearchProfileStack.isSearch(): Boolean = frames.any {
    it.startsWith("org.mtgallium.agent.infoset.core.InformationSetSearch.")
}
private fun MutableMap<String, Long>.increment(key: String, count: Long = 1) { this[key] = (this[key] ?: 0) + count }

/** No search, replay reconstruction, registry construction or model loading occurs here. */
internal fun readSearchProfileSummary(path: Path): SearchProfileSummary = SearchProfileSummary().also { summary ->
    RecordingFile(path).use { recording ->
        while (recording.hasMoreEvents()) {
            val event = recording.readEvent()
            if (event.eventType.name !in setOf("jdk.ExecutionSample", "jdk.ObjectAllocationSample")) continue
            val trace = event.stackTrace
            val stack = SearchProfileStack(trace?.frames.orEmpty().map { "${it.method.type.name}.${it.method.name}" },
                trace?.isTruncated == true)
            if (event.eventType.name == "jdk.ExecutionSample") summary.addExecution(stack)
            else summary.addAllocation(stack, event.getLong("weight"))
        }
    }
}

/** A retained profile is admitted by the existing artifact authority before any attribution. */
internal fun summarizeRegisteredSearchProfile(path: Path, expectedIdentity: String? = null): String {
    val absolute = path.toAbsolutePath().normalize()
    val verified = ResearchRunArtifacts.loadAndVerify(absolute.parent, expectedIdentity)
    require(verified.artifacts.any { it.relativePath == absolute.fileName.toString() }) {
        "The JFR must be registered in its parent directory's finalized manifest"
    }
    return renderSearchProfileSummary(readSearchProfileSummary(absolute),
        "Input `${absolute.fileName}`; verified parent `${verified.researchRunIdentity}`; JFR SHA-256 `${researchSha256File(absolute)}`.")
}

internal fun renderSearchProfileSummary(summary: SearchProfileSummary, identity: String): String = buildString {
    appendLine("# Search cost profile")
    appendLine(identity)
    appendLine("Classification `$SEARCH_COST_CLASSIFICATION`; sampled stack attribution, not measured speedup or whole-game timing.")
    appendLine("Execution samples: ${summary.executionSamples}; complete search stacks: ${summary.searchSamples}; other: ${summary.otherSamples}; truncated excluded: ${summary.truncatedStackSamples}; missing stacks: ${summary.missingStackSamples}.")
    fun percentage(count: Long): String = if (summary.searchSamples == 0L) "n/a" else
        "%.1f%%".format(java.util.Locale.ROOT, 100.0 * count / summary.searchSamples)
    fun rows(counts: Map<String, Long>, limit: Int = counts.size) = counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(limit)
    appendLine("\n## Exclusive costs\n")
    appendLine("Each complete search sample appears once. Precedence: digest, legal enumeration, engine processing, observation, expansion, encoding, matrix inference, history/knowledge, other.")
    appendLine("\n| Cost | Samples | Search share |\n| --- | ---: | ---: |")
    rows(summary.exclusiveCosts).forEach { appendLine("| ${it.key} | ${it.value} | ${percentage(it.value)} |") }
    if (summary.searchSamples == 0L) appendLine("No complete search samples; no cost share can be inferred.")
    appendLine("\n## Inclusive entry points\n")
    appendLine("These paths can overlap with each other and the exclusive table; do not add them.")
    appendLine("\n| Entry point | Samples | Search share |\n| --- | ---: | ---: |")
    rows(summary.inclusiveCosts).forEach { appendLine("| ${it.key} | ${it.value} | ${percentage(it.value)} |") }
    appendLine("\n## Canonical digest callers\n")
    rows(summary.digestCallers, 8).forEach { appendLine("- `${it.key}`: ${it.value} samples (${percentage(it.value)} of search).") }
    appendLine("\n## Sampled allocation\n")
    appendLine("${summary.allocationSamples} complete search allocation samples. Weights estimate allocated bytes at the first project/engine frame; they are neither exact allocation counters nor GC pause durations.")
    rows(summary.allocationWeights, 8).forEach { appendLine("- `${it.key}`: ${it.value} weighted bytes.") }
    appendLine("\nOnly Java execution samples with an untruncated InformationSetSearch stack enter the search denominator. Reconstruction, loading, native work and GC are not assigned search shares. Historical execution source and configuration belong to the verified parent artifacts; the analysis source does not replace them.")
}
