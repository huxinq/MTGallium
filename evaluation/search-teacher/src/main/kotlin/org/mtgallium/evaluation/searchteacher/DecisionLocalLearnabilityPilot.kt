package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.searchteacher.LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1
import org.mtgallium.research.run.*

internal const val DECISION_LOCAL_LEARNABILITY_PROTOCOL = "decision-local-retained-32-learnability-v1"
internal const val DECISION_LOCAL_LEARNABILITY_PRECISION =
    "research-run-v1-sha256:6751a32591956affd799a79f9d72d93dbfc92c3f780c92b307123c325dbef1f0"

/** Original envelopes use split-prefixed filenames; precision envelopes have their own format. */
internal fun loadLearnabilityRootCheckpoints(parent: Path, precision: Path, rootId: String,
    split: DecisionLocalSplit, pairIndex: Int): Pair<ResearchRunCheckpointEnvelope, ResearchRunCheckpointEnvelope> {
    require(split in setOf(DecisionLocalSplit.TRAIN, DecisionLocalSplit.VALIDATION))
    val old = ResearchRunCheckpoints.load(ResearchRunFiles.resolveBelow(parent, "roots/${split.name.lowercase()}-$rootId.json"))
    val fresh = ResearchRunCheckpoints.load(ResearchRunFiles.resolveBelow(precision, "roots/$rootId.json"))
    require(old.researchRunIdentity == DECISION_LOCAL_PRECISION_PARENT && old.payloadSchema == DECISION_LOCAL_ROOT_CHECKPOINT_SCHEMA)
    require(fresh.researchRunIdentity == DECISION_LOCAL_LEARNABILITY_PRECISION && fresh.payloadSchema == "decision-local-precision-root-v1")
    listOf(old, fresh).forEach { require(it.sequence == pairIndex.toLong() && it.parentPayloadSha256 == null) }
    return old to fresh
}

/** Derived fitting rows, never a replacement for either historical checkpoint. */
internal fun combineLearnabilityRoot(old: DecisionLocalRootEvidence, fresh: PrecisionRootSamples): DecisionLocalRootEvidence {
    require(old.split in setOf(DecisionLocalSplit.TRAIN, DecisionLocalSplit.VALIDATION))
    require(old.primaryReplicates == 8 && old.independentReplicates == 0 && old.failures.isEmpty())
    require(fresh.rootId == old.rootId && fresh.failure == null)
    require(fresh.candidates.map { it.signature } == old.candidates.map { it.signature })
    return old.copy(primaryReplicates = 32, candidates = old.candidates.zip(fresh.candidates) { a, b ->
        require(a.primaryTerminalPayoffs.size == 8 && a.independentTerminalPayoffs.isEmpty())
        require(b.samples.map { it.replicate } == decisionLocalPrecisionReplicates)
        require((a.primaryTerminalPayoffs + b.samples.map { it.payoff }).all { it in setOf(-1.0, 0.0, 1.0) })
        require(a.cheapHeuristicScore.isFinite() && a.terminalFeatureOffset.isFinite())
        a.copy(primaryTerminalPayoffs = a.primaryTerminalPayoffs + b.samples.map { it.payoff },
            continuationPolicyDecisions = a.continuationPolicyDecisions + b.samples.sumOf { it.policyDecisions },
            continuationRuntimeMillis = a.continuationRuntimeMillis + b.samples.sumOf { it.elapsedMillis })
    })
}

@Serializable
internal data class LearnabilityCandidatePrediction(
    val signature: String, val observedMean32: Double, val modelScore: Double, val cheapHeuristicScore: Double,
)

internal fun fitLearnabilityModel(roots: List<DecisionLocalRootEvidence>): DecisionLocalModelCheckpoint {
    require(roots.map { it.rootId }.distinct().size == roots.size)
    require(roots.all { it.split in setOf(DecisionLocalSplit.TRAIN, DecisionLocalSplit.VALIDATION) })
    require(roots.all { r -> r.primaryReplicates == 32 && r.independentReplicates == 0 &&
        r.candidates.all { it.primaryTerminalPayoffs.size == 32 && it.independentTerminalPayoffs.isEmpty() } })
    return fitDecisionLocalModel(roots.filter { it.split == DecisionLocalSplit.TRAIN })
}

@Serializable
internal data class LearnabilityMethodRoot(
    val method: String,
    /** Uniform baseline represents an expectation over all candidates; other methods select one. */
    val selectedSignatures: List<String>,
    val observedSelectedPayoff: Double,
    val observedBestRegret: Double,
    val observedBestSelectionRate: Double,
    val nonTiedPairAccuracy: Double?,
    val centeredMeanSquaredError: Double?,
)

@Serializable
internal data class LearnabilityRootResult(
    val rootId: String, val split: DecisionLocalSplit, val pairIndex: Int, val decisionFamily: String,
    val candidates: List<LearnabilityCandidatePrediction>,
    val nonTiedObservedPairs: Int, val tiedObservedPairs: Int,
    val methods: List<LearnabilityMethodRoot>,
    val modelMinusCheapPairedPayoff: Double,
    /** Within this root's retained 32 draws; descriptive, not a across-root generalization interval. */
    val modelMinusCheapPairedStandardError: Double,
)

internal fun evaluateLearnabilityRoot(root: DecisionLocalRootEvidence, model: DecisionLocalModelCheckpoint): LearnabilityRootResult {
    require(root.primaryReplicates == 32 && root.independentReplicates == 0)
    require(root.candidates.all { it.primaryTerminalPayoffs.size == 32 && it.independentTerminalPayoffs.isEmpty() })
    val candidates = root.candidates
    val actual = candidates.map { it.primaryMean }
    val predicted = candidates.map { model.score(it) }
    val cheap = candidates.map { it.cheapHeuristicScore }
    require((predicted + cheap).all(Double::isFinite))
    val pairs = candidates.indices.flatMap { a -> (a + 1 until candidates.size).map { b -> a to b } }
    val nonTied = pairs.filter { (a, b) -> actual[a] != actual[b] }
    fun metric(method: String, scores: List<Double>, uniform: Boolean = false): LearnabilityMethodRoot {
        val selected = if (uniform) candidates.indices.toList() else listOf(candidates.indices.sortedWith(
            compareByDescending<Int> { scores[it] }.thenBy { candidates[it].signature }).first())
        val accuracy = nonTied.map { (a, b) ->
            val sign = (scores[a] - scores[b]).compareTo(0.0)
            when { sign == 0 -> 0.5; sign == (actual[a] - actual[b]).compareTo(0.0) -> 1.0; else -> 0.0 }
        }.takeIf { it.isNotEmpty() }?.average()
        val mse = if (method == "cheap-visible-heuristic") null else candidates.indices.map { i ->
            val error = (scores[i] - scores.average()) - (actual[i] - actual.average())
            error * error
        }.average()
        val selectedPayoff = selected.map { actual[it] }.average()
        return LearnabilityMethodRoot(method, selected.map { candidates[it].signature }, selectedPayoff,
            actual.max() - selectedPayoff, selected.count { actual[it] == actual.max() }.toDouble() / selected.size,
            accuracy, mse)
    }
    val methods = listOf(metric("decision-local-model", predicted), metric("cheap-visible-heuristic", cheap),
        metric("uniform", List(candidates.size) { 0.0 }, uniform = true))
    val chosen = methods.take(2).map { m -> candidates.single { it.signature == m.selectedSignatures.single() } }
    val paired = chosen[0].primaryTerminalPayoffs.zip(chosen[1].primaryTerminalPayoffs) { a, b -> a - b }
    val mean = paired.average()
    val se = sqrt(paired.sumOf { (it - mean) * (it - mean) } / 31 / 32)
    return LearnabilityRootResult(root.rootId, root.split, root.pairIndex, root.decisionFamily,
        candidates.indices.map { i -> LearnabilityCandidatePrediction(candidates[i].signature, actual[i], predicted[i], cheap[i]) },
        nonTied.size, pairs.size - nonTied.size, methods, mean, se)
}

@Serializable
internal data class LearnabilityMethodSummary(
    val method: String, val roots: Int, val rootsWithNonTiedPairs: Int,
    val rootMeanNonTiedPairAccuracy: Double?, val rootMeanObservedSelectedPayoff: Double,
    val rootMeanObservedBestRegret: Double, val rootMeanObservedBestSelectionRate: Double,
    val rootMeanCenteredMeanSquaredError: Double?,
)

internal fun summarizeLearnability(roots: List<LearnabilityRootResult>): List<LearnabilityMethodSummary> {
    require(roots.isNotEmpty() && roots.map { it.split }.distinct().size == 1)
    return roots.first().methods.map { method ->
        val rows = roots.map { root -> root.methods.single { it.method == method.method } }
        val accuracy = rows.mapNotNull { it.nonTiedPairAccuracy }
        LearnabilityMethodSummary(method.method, roots.size, accuracy.size,
            accuracy.takeIf { it.isNotEmpty() }?.average(), rows.map { it.observedSelectedPayoff }.average(),
            rows.map { it.observedBestRegret }.average(), rows.map { it.observedBestSelectionRate }.average(),
            rows.mapNotNull { it.centeredMeanSquaredError }.takeIf { it.isNotEmpty() }?.average())
    }
}

@Serializable
internal data class DecisionLocalLearnabilityPlan(
    val protocol: String = DECISION_LOCAL_LEARNABILITY_PROTOCOL,
    val bindings: ResearchRunBindings,
    val analysisProvenance: ResearchRunProvenance,
    val originalTreatmentSource: String,
    val precisionTreatmentProvenance: ResearchRunProvenance,
    val historicalEngine: String,
    val originalPayloadHashes: Map<String, String>,
    val precisionPayloadHashes: Map<String, String>,
    val rootSplits: Map<String, DecisionLocalSplit>,
    val candidatesBySplit: Map<String, Int>,
    val retainedLabelsBySplit: Map<String, Int>,
    val specification: List<String> = listOf(
        "Fit existing root-centered ridge (0.01), equal weight per TRAIN root and equal weight per sibling; unchanged features and terminal offsets.",
        "Combine original indices 0..7 and precision indices 8..31 exactly once: 32 terminal labels per candidate.",
        "Use the existing 34 TRAIN and six VALIDATION whole-pair assignments. Fit one model without validation tuning.",
        "Compare learned score with retained cheap-visible-heuristic and expected uniform selection. Score ties select lexical-smallest signature.",
        "Report root-equal centered MSE for model and zero-gap baseline; root-equal ordering accuracy only among roots with observed non-tied pairs, predicted ties count half.",
        "All roots remain in selected-payoff, sample-best regret, and best-selection summaries, including observed ties.",
        "Regret against the best retained mean is descriptive and selection-biased; no independent outcome batch is available.",
        "Validation roots are excluded from fitting but development outcomes have already been inspected. Six roots cannot establish generalization.",
        "Historical features and labels retain their original engine/source meaning. Current source performs offline arithmetic only.",
        "No engine simulation, new features, TEST outcomes, challenge panel, hyperparameter search, gameplay treatment, or automatic promotion.",
    ),
)

@Serializable
internal data class DecisionLocalLearnabilityReport(
    val researchRunIdentity: String, val generatedAtUtc: String, val plan: DecisionLocalLearnabilityPlan,
    val model: DecisionLocalModelCheckpoint,
    val train: List<LearnabilityMethodSummary>, val validation: List<LearnabilityMethodSummary>,
    val roots: List<LearnabilityRootResult>,
    val assignedRoots: Int, val completedRoots: Int, val excludedRoots: Int,
    val retainedTerminalLabels: Int, val generatedTerminalLabels: Int,
    val preparationMillis: Double, val fittingMillis: Double, val evaluationMillis: Double,
    val conclusion: String = "DEVELOPMENT_LEARNABILITY_COMPLETE_NO_PROMOTION",
)

/** Offline consumer. No registry, deck loader, historical reconstruction, or continuation call path. */
internal class DecisionLocalLearnabilityPilot(private val repositoryRoot: Path) {
    fun run(parentDirectory: Path, precisionDirectory: Path, output: Path): DecisionLocalLearnabilityReport {
        require(!Files.exists(output)) { "Learnability output must be fresh: $output" }
        val started = System.nanoTime()
        val provenance = ResearchRunProvenance.capture(repositoryRoot)
        provenance.requireReady()
        require(!provenance.outerDirty && !provenance.engineDirty) { "Commit the analysis treatment before fitting" }
        val oldArtifacts = ResearchRunArtifacts.loadAndVerify(parentDirectory, DECISION_LOCAL_PRECISION_PARENT)
        val freshArtifacts = ResearchRunArtifacts.loadAndVerify(precisionDirectory, DECISION_LOCAL_LEARNABILITY_PRECISION)
        fun registered(directory: Path, paths: Set<String>, name: String): String {
            require(name in paths) { "Required artifact is not registered: $name" }
            return Files.readString(ResearchRunFiles.resolveBelow(directory, name))
        }
        val oldPaths = oldArtifacts.artifacts.map { it.relativePath }.toSet()
        val freshPaths = freshArtifacts.artifacts.map { it.relativePath }.toSet()
        val oldReport = evidenceJson.decodeFromString<DecisionLocalExperimentReport>(registered(parentDirectory, oldPaths, "report.json"))
        val precision = evidenceJson.decodeFromString<DecisionLocalPrecisionReport>(registered(precisionDirectory, freshPaths, "report.json"))
        val precisionPlan = evidenceJson.decodeFromString<DecisionLocalPrecisionPlan>(registered(precisionDirectory, freshPaths, "plan.json"))
        require(precisionPlan == precision.plan && precisionPlan.protocol == DECISION_LOCAL_PRECISION_PROTOCOL)
        require(precision.researchRunIdentity == DECISION_LOCAL_LEARNABILITY_PRECISION && precisionPlan.bindings.identity == precision.researchRunIdentity)
        require(precisionPlan.parentIdentity == DECISION_LOCAL_PRECISION_PARENT && oldReport.researchRunIdentity == precisionPlan.parentIdentity)
        require(precisionPlan.bindings.material.getValue("parent-manifest") == researchSha256File(parentDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
        require(precision.completedRoots == 40 && precision.failedRoots == 0 && precision.failures.isEmpty())
        require(precision.completedTerminalContinuations == 2808 && precision.uncompletedAssignedContinuations == 0)
        require(oldReport.admittedRoots == 40 && oldReport.excludedRoots == 0 && oldReport.terminalContinuations == 936)
        require(oldReport.model == null && oldReport.testMetrics.isEmpty() && oldReport.challengeMetrics.isEmpty())
        require(oldReport.featureSchema == LEARNED_OUTCOME_VALUE_FEATURE_SCHEMA_V1)
        val manifest = precisionPlan.rootManifest
        require(oldReport.rootManifestId == manifest.manifestId && oldReport.argentumSha == manifest.argentumCommit)
        require(oldReport.historicalRootSourceMtgalliumSha == manifest.historicalRootSourceCommit)
        val assignments = manifest.assignments.filter { it.split != DecisionLocalSplit.TEST }.sortedBy { it.root.pairIndex }
        val expectedOldPaths = assignments.map { "roots/${it.split.name.lowercase()}-${it.root.id}.json" }.toSet()
        val expectedFreshPaths = assignments.map { "roots/${it.root.id}.json" }.toSet()
        require(oldPaths.filter { it.startsWith("roots/") }.toSet() == expectedOldPaths)
        require(freshPaths.filter { it.startsWith("roots/") }.toSet() == expectedFreshPaths)
        val oldHashes = linkedMapOf<String, String>()
        val freshHashes = linkedMapOf<String, String>()
        val combined = assignments.map { assignment ->
            val r = assignment.root
            val (oldEnvelope, freshEnvelope) = loadLearnabilityRootCheckpoints(parentDirectory, precisionDirectory, r.id, assignment.split, r.pairIndex)
            val old = evidenceJson.decodeFromString<DecisionLocalRootEvidence>(oldEnvelope.payload().decodeToString())
            val fresh = evidenceJson.decodeFromString<PrecisionRootSamples>(freshEnvelope.payload().decodeToString())
            require(old.rootId == r.id && old.split == assignment.split && old.pairIndex == r.pairIndex && old.rootActor == r.rootActor)
            require(old.candidateFamilyDigest == r.candidateFamilyDigest && old.productionScheduleDigest == r.schedule.scheduleDigest)
            require(old.candidates.map { it.signature } == r.candidateSignatures)
            require(fresh.originalCheckpointPayloadSha256 == oldEnvelope.payloadSha256 && precisionPlan.originalPayloadHashes.getValue(r.id) == oldEnvelope.payloadSha256)
            val liveSeed = ComponentSeeds.derive(r.schedule.originalGameId, r.schedule.decisionIndex, r.schedule.policySearchBaseSeed, "live-search")
            fresh.candidates.forEach { candidate -> candidate.samples.forEach { s ->
                require(s.replicate in decisionLocalPrecisionReplicates)
                require(s.particleIndex == r.schedule.coordinates[s.replicate].rootParticleIndex)
                require(s.futureSeed == ComponentSeeds.derive(DECISION_LOCAL_CONTINUATION_SEED_RULE, r.id, assignment.split.name, s.replicate))
                require(s.continuationSeed == ComponentSeeds.derive(liveSeed, assignment.split.name, s.replicate, "terminal-continuation"))
            } }
            val frozen = precisionPlan.frozenComparisons.single { it.rootId == r.id }
            require(analyzePrecisionRoot(old, fresh, frozen) == precision.roots.single { it.rootId == r.id })
            oldHashes[r.id] = oldEnvelope.payloadSha256
            freshHashes[r.id] = freshEnvelope.payloadSha256
            combineLearnabilityRoot(old, fresh)
        }
        require(combined.size == 40 && combined.sumOf { it.candidates.size } == 117)
        val train = combined.filter { it.split == DecisionLocalSplit.TRAIN }
        val validation = combined.filter { it.split == DecisionLocalSplit.VALIDATION }
        require(train.size == 34 && validation.size == 6)
        val bindings = ResearchRunBindings(protocol = DECISION_LOCAL_LEARNABILITY_PROTOCOL, material = mapOf(
            "analysis-source" to provenance.outerCommit,
            "analysis-provenance" to researchSha256(evidenceJson.encodeToString(provenance)),
            "original-run" to oldReport.researchRunIdentity,
            "original-manifest" to researchSha256File(parentDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "precision-run" to precision.researchRunIdentity,
            "precision-manifest" to researchSha256File(precisionDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "root-manifest" to manifest.manifestId,
            "features" to oldReport.featureSchema,
            "model" to "$DECISION_LOCAL_MODEL_OBJECTIVE:$DECISION_LOCAL_SOLVER:ridge=0.01:tol=1e-7",
            "labels" to "retained-original8-plus-fresh24-equal32-per-candidate",
            "split" to "original-whole-pairs-34-train-fit-6-validation-evaluate-no-test",
            "metrics" to "root-equal-ordering-predicted-tie-half-observed-tie-excluded:all-root-sample-best-regret-and-selected-payoff:centered-mse-model-and-zero:no-independent-regret:no-gate",
        ))
        val candidateCounts = combined.groupBy { it.split.name }.mapValues { (_, rows) -> rows.sumOf { it.candidates.size } }
        val plan = DecisionLocalLearnabilityPlan(bindings = bindings, analysisProvenance = provenance,
            originalTreatmentSource = oldReport.treatmentMtgalliumSha, precisionTreatmentProvenance = precisionPlan.provenance,
            historicalEngine = manifest.argentumCommit, originalPayloadHashes = oldHashes, precisionPayloadHashes = freshHashes,
            rootSplits = combined.associate { it.rootId to it.split }, candidatesBySplit = candidateCounts,
            retainedLabelsBySplit = candidateCounts.mapValues { it.value * 32 })
        ResearchRunFiles.atomicWrite(output.resolve("plan.json"), evidenceJson.encodeToString(plan))
        val fitStart = System.nanoTime()
        val model = fitLearnabilityModel(combined)
        val fitEnd = System.nanoTime()
        ResearchRunFiles.atomicWrite(output.resolve("model.json"), evidenceJson.encodeToString(model))
        val results = combined.map { evaluateLearnabilityRoot(it, model) }
        val report = DecisionLocalLearnabilityReport(bindings.identity, Instant.now().toString(), plan, model,
            summarizeLearnability(results.filter { it.split == DecisionLocalSplit.TRAIN }),
            summarizeLearnability(results.filter { it.split == DecisionLocalSplit.VALIDATION }), results,
            40, results.size, 0, combined.sumOf { it.candidates.sumOf { c -> c.primaryTerminalPayoffs.size } }, 0,
            (fitStart - started) / 1e6, (fitEnd - fitStart) / 1e6, (System.nanoTime() - fitEnd) / 1e6)
        ResearchRunFiles.atomicWrite(output.resolve("report.json"), evidenceJson.encodeToString(report))
        val markdown = buildString {
            append("# Retained decision-local learnability pilot\n\n${report.conclusion}\n\n")
            append("Run: `${report.researchRunIdentity}`\n\n")
            append("34 TRAIN roots; six VALIDATION roots; 117 candidates; 3,744 retained terminal labels; zero generated labels.\n\n")
            append("| Split | Method | Roots with non-tied pairs / all | Root-mean ordering accuracy | Sample-best regret | Selected payoff | Centered MSE |\n")
            append("| --- | --- | --- | --- | --- | --- | --- |\n")
            listOf("TRAIN" to report.train, "VALIDATION" to report.validation).forEach { (split, methods) -> methods.forEach { m ->
                append("| $split | ${m.method} | ${m.rootsWithNonTiedPairs}/${m.roots} | ${m.rootMeanNonTiedPairAccuracy} | ${m.rootMeanObservedBestRegret} | ${m.rootMeanObservedSelectedPayoff} | ${m.rootMeanCenteredMeanSquaredError} |\n")
            } }
            append("\nModel and uniform MSE compare within-root differences; uniform supplies the zero-gap baseline. Heuristic scores are compared by ranking and selection, not calibrated MSE.\n\n")
            append("Sample-best regret uses the same 32 retained outcomes to name the best action and evaluate selection; it is descriptive, not independent regret. Validation excluded from fitting; development outcomes were previously inspected. No TEST, tuning, new simulation, or promotion.\n")
        }
        ResearchRunFiles.atomicWrite(output.resolve("report.md"), markdown)
        ResearchRunArtifacts(output, bindings.identity).also { artifacts ->
            listOf("plan.json", "model.json", "report.json", "report.md").forEach { artifacts.register(it) }
            artifacts.finalize()
        }
        ResearchRunArtifacts.loadAndVerify(output, bindings.identity)
        return report
    }
}
