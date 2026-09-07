package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.research.run.*

internal data class GameplayLengthComparison(
    val control: SearchTeacherCalibrationPolicy, val candidate: SearchTeacherCalibrationPolicy,
    val rows: List<GameplayLengthObservation>, val firstPairIndex: Int, val inspectedPairs: Int,
)
internal data class GameplayLengthOrigin(val identity: String, val source: PolicySourceProvenance, val workers: Int)
internal data class RetainedGameplayLengths(
    val directory: Path, val identity: String, val origins: List<GameplayLengthOrigin>,
    val deckHash: String, val cardPoolHash: String, val disposition: String,
    val comparisons: List<GameplayLengthComparison>,
)

/** Finalized evidence only. No arena, fit loading, replay execution, or producer invocation. */
internal fun loadRetainedGameplayLengths(input: Path): RetainedGameplayLengths {
    val directory = input.toAbsolutePath().normalize()
    require(Files.isRegularFile(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE))) {
        "Gameplay summary needs a finalized run: $directory. An active root has no authenticated complete chunk index; inspect a finalized child explicitly or wait for completion."
    }
    val manifest = ResearchRunArtifacts.loadAndVerify(directory)
    val entries = manifest.artifacts.associateBy { it.relativePath }
    fun registered(name: String): Path = ResearchRunFiles.resolveBelow(directory, name).also {
        require(entries.containsKey(name)) { "Unregistered gameplay input: $name" }
    }
    val plan = evidenceJson.parseToJsonElement(Files.readString(registered("plan.json"))).jsonObject
    return when {
        "parentDirectory" in plan -> loadContinuationLengths(directory, manifest.researchRunIdentity, registered("report.json"))
        "studyManifestSha256" in plan -> loadAttackLengths(directory, manifest.researchRunIdentity, registered("report.json"))
        "phase" in plan -> {
            val report = if ("sequential-plan.json" in entries)
                loadCompletedSequentialCalibration(directory, manifest.researchRunIdentity)
            else loadCompletedCalibration(directory, manifest.researchRunIdentity)
            RetainedGameplayLengths(directory, report.runIdentity,
                listOf(GameplayLengthOrigin(report.runIdentity, report.sourceProvenance, report.workerThreads)),
                report.deckHash, report.cardPoolHash, report.sequentialResult?.disposition?.name ?: "FIXED_CALIBRATION",
                report.comparisons.map { comparison -> GameplayLengthComparison(report.plan.control,
                    report.plan.candidates.single { it.id == comparison.candidateId },
                    gameplayLengthObservations(comparison.pairs + report.sequentialOvershootPairs.orEmpty(), comparison.candidateId),
                    report.plan.pairOffset, comparison.pairs.size) })
        }
        else -> error("Unsupported gameplay plan at $directory: expected calibration, sequential calibration, continuation or attack gameplay")
    }
}

private data class LengthChunks(val rows: List<GameplayLengthObservation>, val scores: List<PairedSequentialScore>,
    val origins: List<GameplayLengthOrigin>, val deckHash: String?, val cardPoolHash: String?)

private fun pairScores(pairs: List<SearchBudgetFrontierPair>) = pairs.map {
    PairedSequentialScore(it.pairIndex, if (it.valid) requireNotNull(it.treatmentPoints) / 2.0 else null, it.invalidationReasons)
}

/** Child manifests, schedules and checkpoints remain under each actual execution identity. */
private fun loadLengthChunks(directory: Path, chunks: List<ContinuationChunkBinding>, calibration: SearchTeacherCalibrationPlan,
    source: PolicySourceProvenance, rule: PairedSequentialRule, workers: Int,
    initialScores: List<PairedSequentialScore> = emptyList(), expectedBehavior: List<String>? = null): LengthChunks {
    require(chunks.map { it.directory }.distinct().size == chunks.size)
    require(chunks.map { it.identity }.distinct().size == chunks.size)
    val parentEntries = ResearchRunArtifacts.loadAndVerify(directory).artifacts.associateBy { it.relativePath }
    val scores = initialScores.toMutableList()
    val rows = mutableListOf<GameplayLengthObservation>()
    val origins = mutableListOf<GameplayLengthOrigin>()
    var deck: String? = null
    var pool: String? = null
    chunks.forEach { chunk ->
        val range = requireNotNull(continuationChunk(rule, scores, calibration.pairOffset, workers)) {
            "Retained chunk follows a sequential stopping boundary"
        }
        require(chunk.pairOffset == range.first && chunk.executedPairs == range.count())
        require(chunk.directory == "chunks/pair-${range.first}")
        val path = ResearchRunFiles.resolveBelow(directory, chunk.directory)
        val relativeManifest = "${chunk.directory}/${ResearchRunArtifacts.MANIFEST_FILE}"
        require(parentEntries.getValue(relativeManifest).sha256 == chunk.manifestSha256)
        require(researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == chunk.manifestSha256)
        val report = loadCompletedCalibration(path, chunk.identity)
        require(report.plan == calibration.copy(pairOffset = range.first, pairCount = range.count()))
        require(report.sourceProvenance == source && report.workerThreads == workers)
        if (expectedBehavior != null) require(report.policies.map { it.binding.behaviorSpecificationSha256 } == expectedBehavior)
        if (deck != null) require(report.deckHash == deck && report.cardPoolHash == pool)
        deck = report.deckHash; pool = report.cardPoolHash
        val pairs = report.comparisons.single().pairs
        require(report.comparisons.single() == calibrationComparison(report.plan, report.plan.candidates.single(), pairs))
        scores += pairScores(pairs)
        rows += gameplayLengthObservations(pairs, calibration.candidates.single().id)
        origins += GameplayLengthOrigin(report.runIdentity, report.sourceProvenance, report.workerThreads)
    }
    return LengthChunks(rows, scores, origins, deck, pool)
}

private fun requireLengthResult(rule: PairedSequentialRule, scores: List<PairedSequentialScore>, first: Int,
    retained: PairedSequentialResult, confidenceSequence: Boolean) {
    val raw = pairedSequentialTest(rule, scores, first)
    val expected = if (!confidenceSequence) raw else raw.copy(confidenceSequence = pairedMeanConfidenceSequence(rule,
        scores.take(raw.validScoredPairs).map { requireNotNull(it.pointRate) }))
    require(retained == expected) { "Retained stopping prefix differs from authenticated game scores" }
}

private fun loadContinuationLengths(directory: Path, identity: String, reportPath: Path): RetainedGameplayLengths {
    val report = readEvidenceJson(reportPath, SearchTeacherContinuationReport.serializer())
    val plan = report.plan
    require(report.identity == identity)
    require(readEvidenceJson(directory.resolve("plan.json"), SearchTeacherContinuationPlan.serializer()) == plan)
    val parentDirectory = Path.of(plan.parentDirectory)
    require(researchSha256File(parentDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == plan.parentManifestSha256)
    val parent = loadCompletedSequentialCalibration(parentDirectory, plan.parentIdentity)
    require(parent.sourceProvenance == report.parentSource && parent.sequentialResult == report.parentResult)
    require(report.source.outer.revision == plan.expectedSourceCommit && report.source.argentum == parent.sourceProvenance.argentum)
    val parentPairs = completedSequentialBankPairs(parent)
    val scores = pairScores(parentPairs)
    require(parentPairs.size == report.parentPairs && report.parentResult.orderedPrefixSha256 == plan.parentPrefixSha256)
    val original = requireNotNull(parent.sequentialRule)
    val rule = if (plan.seekSuperiority) superiorityContinuationRule(original, report.parentResult, scores,
        parent.plan.pairOffset, plan.totalPairCap) else continuationRule(original, report.parentResult, scores,
        parent.plan.pairOffset, plan.totalPairCap)
    val bindings = continuationBindings(plan, report.source, original, rule)
    require(bindings.identity == identity && bindings.protocol == report.protocol)
    val chunks = loadLengthChunks(directory, report.chunks, parent.plan, report.source, rule, plan.workerThreads, scores,
        parent.policies.map { it.binding.behaviorSpecificationSha256 })
    require(chunks.scores == report.scores)
    require(chunks.deckHash == null || (chunks.deckHash == parent.deckHash && chunks.cardPoolHash == parent.cardPoolHash))
    requireLengthResult(rule, chunks.scores, parent.plan.pairOffset, report.result, plan.seekSuperiority)
    require(report.newExecutedPairs == chunks.scores.size - parentPairs.size &&
        report.newInspectedPairs == (report.result.inspectedPairs - parentPairs.size).coerceAtLeast(0) &&
        report.plannedUnexecutedPairs == rule.maximumPairs - chunks.scores.size)
    val rows = gameplayLengthObservations(parentPairs, parent.plan.candidates.single().id) + chunks.rows
    require(rows.map { it.gameId }.distinct().size == rows.size)
    return RetainedGameplayLengths(directory, identity,
        listOf(GameplayLengthOrigin(parent.runIdentity, parent.sourceProvenance, parent.workerThreads)) + chunks.origins,
        parent.deckHash, parent.cardPoolHash, "${report.result.disposition}; operationalValid=${report.operationalValid}; treatmentIssues=${report.treatmentIssues.size}",
        listOf(GameplayLengthComparison(parent.plan.control, parent.plan.candidates.single(), rows,
            parent.plan.pairOffset, report.result.inspectedPairs)))
}

private fun loadAttackLengths(directory: Path, identity: String, reportPath: Path): RetainedGameplayLengths {
    val report = readEvidenceJson(reportPath, AttackKernelGameplayReport.serializer())
    require(report.identity == identity)
    require(readEvidenceJson(directory.resolve("plan.json"), AttackKernelGameplayPlan.serializer()) == report.plan)
    report.source.requireReady()
    require(!report.source.outerDirty && !report.source.engineDirty)
    val bindings = attackGameplayBindings(report.plan, report.source, report.calibration)
    require(bindings.identity == identity)
    require(readEvidenceJson(directory.resolve("bindings.json"), ResearchRunBindings.serializer()) == bindings)
    val rule = attackGameplayRule()
    require(report.calibration.baseSeed == report.plan.baseSeed && report.calibration.pairOffset == 0 &&
        report.calibration.pairCount == rule.maximumPairs && report.calibration.candidates.size == 1)
    val chunks = loadLengthChunks(directory, report.chunks, report.calibration, report.source.sourceProvenance,
        rule, report.plan.workers)
    require(chunks.scores == report.scores)
    requireLengthResult(rule, chunks.scores, 0, report.result, true)
    return RetainedGameplayLengths(directory, identity, chunks.origins,
        chunks.deckHash ?: "unavailable (no games)", chunks.cardPoolHash ?: "unavailable (no games)",
        "${report.disposition}; sequential=${report.result.disposition}; operationalValid=${report.operationalValid}; treatmentIssues=${report.treatmentIssues.size}",
        listOf(GameplayLengthComparison(report.calibration.control, report.calibration.candidates.single(), chunks.rows, 0, report.result.inspectedPairs)))
}

internal fun renderRetainedGameplayLengths(runs: List<RetainedGameplayLengths>): String = buildString {
    require(runs.size in 1..2)
    appendLine("# Verified gameplay lengths")
    appendLine("Finalized manifests, source/configuration bindings, game checkpoints and composed stopping prefixes verified. Historical artifacts are read only; no games or replays are executed.")
    runs.forEachIndexed { index, run ->
        appendLine("\n## Run ${index + 1}: ${run.disposition}\n")
        appendLine("Directory `${run.directory}`; identity `${run.identity}`.")
        appendLine("Deck `${run.deckHash}`; card pool `${run.cardPoolHash}`.")
        run.origins.groupBy { it.source to it.workers }.forEach { (key, origins) ->
            val (source, workers) = key
            appendLine("${origins.size} authenticated execution run(s): source `${source.outer.revision}`; expected Argentum `${source.expectedArgentumRevision}`, actual `${source.argentum.revision}`; workers=$workers.")
        }
        run.comparisons.forEach { comparison ->
            appendLine("\nControl `${comparison.control.id}` versus candidate `${comparison.candidate.id}`.")
            for ((role, policy) in listOf("Control" to comparison.control, "Candidate" to comparison.candidate))
                appendLine("$role: particles=${policy.particles}, simulations=${policy.simulations}, decision horizon=${policy.maxPolicyDecisions}; descriptor SHA256 `${sha256(evidenceJson.encodeToString(policy))}`.")
            appendLine("Changed configuration fields: ${gameplayChangedFields(comparison.control, comparison.candidate).ifEmpty { listOf("none") }.joinToString()}.")
            append(renderGameplayLengths(comparison.rows, comparison.inspectedPairs, comparison.firstPairIndex))
        }
    }
    if (runs.size == 2) {
        appendLine("\n## Between-run comparison\n")
        if (runs.any { it.comparisons.size != 1 }) appendLine("Multiple candidate populations: no automatic pooling or ratio.")
        else {
            val first = runs[0].comparisons.single(); val second = runs[1].comparisons.single()
            appendLine("All executed terminal games in valid complete pairs, including operational overshoot; Run 2 / Run 1. Each run retains its own seeds and stopping population.")
            appendLine("| Measure | Run 1 | Run 2 | Ratio |\n|---|---:|---:|---:|")
            for ((label, select) in listOf<Pair<String, (GameplayLengthObservation) -> Double?>>(
                "Accepted decisions/game" to { it.decisions.toDouble() },
                "Player turns/game" to { it.terminalTurnNumber?.toDouble() },
                "Searched decisions/game" to { it.searchedDecisions?.toDouble() },
                "Concurrent game seconds" to { it.elapsedMillis?.div(1000) })) {
                fun mean(c: GameplayLengthComparison) = c.rows.filter { it.eligible }.let { rows ->
                    rows.mapNotNull(select).takeIf { it.isNotEmpty() && it.size == rows.size }?.average()
                }
                val a = mean(first); val b = mean(second)
                appendLine("| $label | ${gameplayNumber(a)} | ${gameplayNumber(b)} | ${gameplayNumber(a?.takeIf { it > 0 }?.let { b?.div(it) })} |")
            }
            appendLine("Cross-run control changes: ${gameplayChangedFields(first.control, second.control).ifEmpty { listOf("none") }.joinToString()}.")
            appendLine("Cross-run candidate changes: ${gameplayChangedFields(first.candidate, second.candidate).ifEmpty { listOf("none") }.joinToString()}.")
            appendLine("Same deck/pool=${runs[0].deckHash == runs[1].deckHash && runs[0].cardPoolHash == runs[1].cardPoolHash}. Sources and worker counts appear above. These ratios do not isolate a strength effect, policy cost, hardware effect, or controlled speedup.")
        }
    }
}

private fun gameplayChangedFields(a: SearchTeacherCalibrationPolicy, b: SearchTeacherCalibrationPolicy): List<String> {
    val left = evidenceJson.parseToJsonElement(evidenceJson.encodeToString(a)).jsonObject
    val right = evidenceJson.parseToJsonElement(evidenceJson.encodeToString(b)).jsonObject
    return (left.keys + right.keys).filter { it != "id" && left[it] != right[it] }.sorted()
}
