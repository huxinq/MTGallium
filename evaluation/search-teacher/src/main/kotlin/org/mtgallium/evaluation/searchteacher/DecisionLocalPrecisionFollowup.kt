package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy
import org.mtgallium.research.run.*

internal const val DECISION_LOCAL_PRECISION_PROTOCOL = "decision-local-terminal-precision-8-plus-24-v1"
internal const val DECISION_LOCAL_PRECISION_PARENT =
    "research-run-v1-sha256:77f706c9ff6794c1f3dfea3de5434a2dbe78bbab1eda3294c52fb5da76f5ce13"
internal val decisionLocalPrecisionReplicates = (8 until 32).toList()

@Serializable
internal data class FrozenPrecisionComparison(
    val rootId: String,
    val bestSignature: String,
    val runnerUpSignature: String,
    val originalBestTied: Boolean,
    val originalGap: Double,
)

/** Ties use the already canonical signature order; tied selections carry no claimed direction. */
internal fun freezePrecisionComparison(root: DecisionLocalRootEvidence): FrozenPrecisionComparison {
    val ordered = root.candidates.sortedWith(compareByDescending<DecisionLocalCandidateEvidence> { it.primaryMean }.thenBy { it.signature })
    return FrozenPrecisionComparison(root.rootId, ordered[0].signature, ordered[1].signature,
        ordered[0].primaryMean == ordered[1].primaryMean, ordered[0].primaryMean - ordered[1].primaryMean)
}

@Serializable
internal data class PrecisionCandidateSamples(val signature: String, val samples: List<DecisionLocalTerminalSample>)

@Serializable
internal data class PrecisionRootSamples(
    val rootId: String,
    val originalCheckpointPayloadSha256: String,
    val candidates: List<PrecisionCandidateSamples>,
    val failure: String? = null,
)

@Serializable
internal data class PrecisionPairEffect(
    val firstSignature: String,
    val secondSignature: String,
    val originalMeanDifference: Double,
    val freshMeanDifference: Double,
    val combinedMeanDifference: Double,
    val freshPairedStandardError: Double,
    /** Descriptive normal approximation; not a family-wise or sequential confidence guarantee. */
    val freshApproximate95Lower: Double,
    val freshApproximate95Upper: Double,
    val freshDiscordantSamples: Int,
)

@Serializable
internal data class PrecisionRootAnalysis(
    val rootId: String,
    val frozenComparison: FrozenPrecisionComparison,
    val frozenComparisonFreshEffect: PrecisionPairEffect,
    val freshTopSignatures: List<String>,
    val originalSelectedStillAmongFreshBest: Boolean,
    val originalNonTiedPairs: Int,
    val reproducedPairDirections: Int,
    val pairEffects: List<PrecisionPairEffect>,
)

internal fun precisionPairEffect(first: String, second: String, oldA: List<Double>, oldB: List<Double>,
    freshA: List<Double>, freshB: List<Double>): PrecisionPairEffect {
    require(oldA.size == 8 && oldB.size == 8 && freshA.size == 24 && freshB.size == 24)
    val old = oldA.zip(oldB) { a, b -> a - b }
    val fresh = freshA.zip(freshB) { a, b -> a - b }
    val mean = fresh.average()
    val se = sqrt(fresh.sumOf { (it - mean) * (it - mean) } / (fresh.size - 1) / fresh.size)
    return PrecisionPairEffect(first, second, old.average(), mean, (old + fresh).average(), se,
        mean - 1.96 * se, mean + 1.96 * se, fresh.count { it != 0.0 })
}

internal fun analyzePrecisionRoot(old: DecisionLocalRootEvidence, fresh: PrecisionRootSamples,
    frozen: FrozenPrecisionComparison): PrecisionRootAnalysis {
    require(fresh.failure == null && fresh.rootId == old.rootId && frozen == freezePrecisionComparison(old))
    val oldBy = old.candidates.associateBy { it.signature }
    val newBy = fresh.candidates.associateBy { it.signature }
    require(newBy.size == fresh.candidates.size && oldBy.keys == newBy.keys)
    newBy.values.forEach { require(it.samples.map { s -> s.replicate } == decisionLocalPrecisionReplicates) }
    fun effect(a: String, b: String) = precisionPairEffect(a, b, oldBy.getValue(a).primaryTerminalPayoffs,
        oldBy.getValue(b).primaryTerminalPayoffs, newBy.getValue(a).samples.map { it.payoff },
        newBy.getValue(b).samples.map { it.payoff })
    val keys = oldBy.keys.sorted()
    val pairs = keys.flatMapIndexed { i, a -> keys.drop(i + 1).map { b -> effect(a, b) } }
    val means = newBy.mapValues { (_, candidate) -> candidate.samples.map { it.payoff }.average() }
    val top = means.filterValues { it == means.values.max() }.keys.sorted()
    return PrecisionRootAnalysis(old.rootId, frozen, effect(frozen.bestSignature, frozen.runnerUpSignature), top,
        frozen.bestSignature in top, pairs.count { it.originalMeanDifference != 0.0 },
        pairs.count { it.originalMeanDifference * it.freshMeanDifference > 0.0 }, pairs)
}

@Serializable
internal data class DecisionLocalPrecisionPlan(
    val protocol: String = DECISION_LOCAL_PRECISION_PROTOCOL,
    val bindings: ResearchRunBindings,
    val provenance: ResearchRunProvenance,
    val parentIdentity: String,
    val rootManifest: DecisionLocalRootManifest,
    val frozenComparisons: List<FrozenPrecisionComparison>,
    val originalPayloadHashes: Map<String, String>,
    val assignedRoots: Int = 40,
    val assignedCandidates: Int = 117,
    val assignedFreshContinuations: Int = 2808,
    val freshReplicateIndices: List<Int> = decisionLocalPrecisionReplicates,
    val interpretation: List<String> = listOf(
        "All comparisons are frozen from the original eight samples before any new outcome is generated.",
        "Indices 8 through 31 extend the original common-across-siblings seed schedule and use the same frozen finite belief.",
        "Fresh effects assess original comparisons; pooled 32-sample effects and new rankings are descriptive.",
        "Normal intervals do not adjust across roots or action pairs; zero observed variance is not proof of zero uncertainty.",
        "No training, TEST evaluation, challenge panel, gameplay treatment, adaptive extension, or automatic promotion.",
    ),
)

@Serializable
internal data class DecisionLocalPrecisionReport(
    val researchRunIdentity: String,
    val generatedAtUtc: String,
    val plan: DecisionLocalPrecisionPlan,
    val completedRoots: Int,
    val failedRoots: Int,
    val completedTerminalContinuations: Int,
    val uncompletedAssignedContinuations: Int,
    val roots: List<PrecisionRootAnalysis>,
    val failures: Map<String, String>,
    val elapsedMillis: Double,
    val conclusion: String,
)

private data class PreparedPrecision(
    val plan: DecisionLocalPrecisionPlan,
    val oldRoots: Map<String, DecisionLocalRootEvidence>,
)

internal class DecisionLocalPrecisionFollowup(
    private val repositoryRoot: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val deck: DeckManifest,
) {
    private fun prepare(parentDirectory: Path, rootManifestPath: Path, pilotDirectory: Path): PreparedPrecision {
        val provenance = ResearchRunProvenance.capture(repositoryRoot)
        provenance.requireReady()
        require(!provenance.outerDirty && !provenance.engineDirty)
        val populationArtifacts = ResearchRunArtifacts.loadAndVerify(rootManifestPath.parent)
        require(populationArtifacts.artifacts.any { it.relativePath == rootManifestPath.fileName.toString() })
        val manifest = loadDecisionLocalRootManifest(rootManifestPath)
        require(manifest.argentumCommit == provenance.checkedOutArgentumCommit)
        val artifacts = ResearchRunArtifacts.loadAndVerify(parentDirectory, DECISION_LOCAL_PRECISION_PARENT)
        val parent = evidenceJson.decodeFromString<DecisionLocalExperimentReport>(Files.readString(parentDirectory.resolve("report.json")))
        require(parent.researchRunIdentity == DECISION_LOCAL_PRECISION_PARENT && parent.rootManifestId == manifest.manifestId)
        require(parent.argentumSha == manifest.argentumCommit && parent.historicalRootSourceMtgalliumSha == manifest.historicalRootSourceCommit)
        require(parent.admittedRoots == 40 && parent.excludedRoots == 0 && parent.terminalContinuations == 936)
        require(parent.model == null && parent.testMetrics.isEmpty() && parent.challengeMetrics.isEmpty())
        val assignments = manifest.assignments.filter { it.split != DecisionLocalSplit.TEST }
        require(assignments.size == 40 && assignments.map { it.root.pairIndex }.distinct().size == 40)
        val rootEntries = artifacts.artifacts.filter { it.relativePath.startsWith("roots/") }
        require(rootEntries.size == 40)
        val payloadHashes = linkedMapOf<String, String>()
        val old = rootEntries.map { entry ->
            val envelope = ResearchRunCheckpoints.load(ResearchRunFiles.resolveBelow(parentDirectory, entry.relativePath))
            require(envelope.researchRunIdentity == parent.researchRunIdentity && envelope.payloadSchema == "decision-local-root-evidence-v1")
            val root = evidenceJson.decodeFromString<DecisionLocalRootEvidence>(envelope.payload().decodeToString())
            require(envelope.sequence == root.pairIndex.toLong() && envelope.parentPayloadSha256 == null)
            val assignment = assignments.single { it.root.id == root.rootId }
            require(root.split == assignment.split && root.pairIndex == assignment.root.pairIndex)
            require(root.candidateFamilyDigest == assignment.root.candidateFamilyDigest && root.productionScheduleDigest == assignment.root.schedule.scheduleDigest)
            require(root.candidates.map { it.signature } == assignment.root.candidateSignatures)
            require(root.primaryReplicates == 8 && root.independentReplicates == 0 && root.failures.isEmpty())
            require(root.candidates.all { it.primaryTerminalPayoffs.size == 8 && it.independentTerminalPayoffs.isEmpty() })
            require(payloadHashes.put(root.rootId, envelope.payloadSha256) == null)
            root
        }.associateBy { it.rootId }
        require(old.size == 40 && old.values.sumOf { it.candidates.size } == 117)
        ResearchRunArtifacts.loadAndVerify(pilotDirectory, manifest.sourcePilotRunIdentity)
        val pilot = evidenceJson.decodeFromString<LearnedLeafPilotReport>(Files.readString(pilotDirectory.resolve("report.json")))
        require(pilot.runIdentity == manifest.sourcePilotRunIdentity && pilot.argentumCommit == manifest.argentumCommit)
        require(pilot.deckHash == deck.deckHash() && pilot.cardPoolHash == deck.cardPoolHash())
        require(assignments.all { it.root.sourcePolicyId == manifest.pilot.control.id })
        require(manifest.pilot.control == learnedLeafFixedRootPolicyBinding(pilot, manifest.pilot.control.id))
        val frozen = old.values.sortedBy { it.pairIndex }.map(::freezePrecisionComparison)
        val bindings = ResearchRunBindings(protocol = DECISION_LOCAL_PRECISION_PROTOCOL, material = mapOf(
            "treatment-source" to provenance.outerCommit,
            "source-provenance" to researchSha256(evidenceJson.encodeToString(provenance)),
            "parent-run" to parent.researchRunIdentity,
            "parent-manifest" to researchSha256File(parentDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "root-manifest" to manifest.manifestId,
            "argentum" to manifest.argentumCommit,
            "deck" to pilot.deckHash,
            "card-pool" to pilot.cardPoolHash,
            "control" to researchSha256(evidenceJson.encodeToString(manifest.pilot.control)),
            "root-continuation" to SearchTeacherSearchFactory.rootRolloutPolicy().behaviorSpecification.toString(),
            "opponent-continuation" to SearchTeacherSearchFactory.opponentRolloutPolicy().behaviorSpecification.toString(),
            "replicates" to "original-0..7-retained:fresh-8..31-only",
            "continuation-seeds" to DECISION_LOCAL_CONTINUATION_SEED_RULE,
            "comparisons" to researchSha256(evidenceJson.encodeToString(frozen)),
            "continuation-cap" to "4096-policy-decisions:terminal-only:reject-replacement",
            "analysis" to "frozen-old-best-runner-up-and-all-pairs:paired-new-24-normal-approx95:pooled32-descriptive:no-promotion",
        ))
        return PreparedPrecision(DecisionLocalPrecisionPlan(bindings = bindings, provenance = provenance,
            parentIdentity = parent.researchRunIdentity, rootManifest = manifest, frozenComparisons = frozen,
            originalPayloadHashes = payloadHashes.toSortedMap()), old)
    }

    /** Replays two already observed outcomes; no fresh continuation coordinate is consumed. */
    fun preflight(parent: Path, roots: Path, pilot: Path, output: Path) {
        require(!Files.exists(output))
        val started = System.nanoTime()
        val prepared = prepare(parent, roots, pilot)
        val manifest = prepared.plan.rootManifest
        val assignment = manifest.assignments.filter { it.split == DecisionLocalSplit.TRAIN }
            .minWith(compareBy<DecisionLocalRootAssignment> { it.root.decisionIndex }.thenBy { it.root.pairIndex })
        val reconstructed = reconstructDecisionLocalRoot(pilot, registry, deck, manifest.pilot, assignment.root)
        val parameters = manifest.pilot.control.composition.parameters(assignment.root.schedule.policySearchBaseSeed, manifest.pilot.control.leaf)
        val search = SearchTeacherSearchFactory.create(parameters.searchConfig(), defaultMonoRedOpponentPolicy())
        val old = prepared.oldRoots.getValue(assignment.root.id)
        old.candidates.take(2).forEach { candidate ->
            val sample = continueDecisionLocalCandidate(reconstructed, assignment.root, assignment.split, candidate.signature, 0, search)
            require(sample.payoff == candidate.primaryTerminalPayoffs[0]) { "Historical continuation witness changed for ${candidate.signature}" }
        }
        ResearchRunFiles.atomicWrite(output.resolve("plan.json"), evidenceJson.encodeToString(prepared.plan))
        ResearchRunFiles.atomicWrite(output.resolve("preflight.json"),
            """{"rootId":"${assignment.root.id}","historicalOutcomesReproduced":2,"freshOutcomesGenerated":0,"elapsedMillis":${(System.nanoTime()-started)/1_000_000.0}}""")
        ResearchRunArtifacts(output, prepared.plan.bindings.identity).also {
            it.register("plan.json"); it.register("preflight.json"); it.finalize()
        }
    }

    fun run(parent: Path, roots: Path, pilot: Path, output: Path, progressPath: Path?): DecisionLocalPrecisionReport {
        require(!Files.exists(output.resolve(ResearchRunArtifacts.MANIFEST_FILE))) { "Completed precision runs are immutable" }
        val started = System.nanoTime()
        val prepared = prepare(parent, roots, pilot)
        val plan = prepared.plan
        // Persist the exact old-data selection before entering the first fresh continuation.
        val planPath = output.resolve("plan.json")
        if (Files.exists(output)) {
            require(Files.isRegularFile(planPath)) { "Existing precision output has no authenticated plan" }
            require(evidenceJson.decodeFromString<DecisionLocalPrecisionPlan>(Files.readString(planPath)) == plan) {
                "Resume plan differs from current source, parent, population, or fixed configuration"
            }
        } else ResearchRunFiles.atomicWrite(planPath, evidenceJson.encodeToString(plan))
        val results = mutableListOf<PrecisionRootSamples>()
        var completed = 0
        fun progress(detail: String) {
            progressPath?.let { ResearchRunFiles.atomicWrite(it,
                """{"schemaVersion":1,"updatedAt":"${Instant.now()}","completed":$completed,"total":2808,"unit":"terminal continuations","phase":"fixed precision follow-up","detail":"$detail"}""") }
        }
        progress("prepared 40 roots; original comparisons frozen")
        for (assignment in plan.rootManifest.assignments.filter { it.split != DecisionLocalSplit.TEST }) {
            val root = assignment.root
            val checkpointPath = output.resolve("roots/${root.id}.json")
            if (Files.exists(checkpointPath)) {
                val envelope = ResearchRunCheckpoints.load(checkpointPath)
                require(envelope.researchRunIdentity == plan.bindings.identity &&
                    envelope.payloadSchema == "decision-local-precision-root-v1" &&
                    envelope.sequence == root.pairIndex.toLong() && envelope.parentPayloadSha256 == null)
                val retained = evidenceJson.decodeFromString<PrecisionRootSamples>(envelope.payload().decodeToString())
                require(retained.rootId == root.id && retained.originalCheckpointPayloadSha256 == plan.originalPayloadHashes.getValue(root.id))
                require(retained.candidates.map { it.signature } == root.candidateSignatures.take(retained.candidates.size))
                val liveSeed = ComponentSeeds.derive(root.schedule.originalGameId, root.schedule.decisionIndex,
                    root.schedule.policySearchBaseSeed, "live-search")
                retained.candidates.forEach { candidate ->
                    require(candidate.samples.map { it.replicate } == decisionLocalPrecisionReplicates.take(candidate.samples.size))
                    candidate.samples.forEach { sample ->
                        require(sample.particleIndex == root.schedule.coordinates[sample.replicate].rootParticleIndex)
                        require(sample.futureSeed == ComponentSeeds.derive(DECISION_LOCAL_CONTINUATION_SEED_RULE, root.id, assignment.split.name, sample.replicate))
                        require(sample.continuationSeed == ComponentSeeds.derive(liveSeed, assignment.split.name, sample.replicate, "terminal-continuation"))
                    }
                }
                if (retained.failure == null) analyzePrecisionRoot(prepared.oldRoots.getValue(root.id), retained,
                    plan.frozenComparisons.single { it.rootId == root.id })
                completed += retained.candidates.sumOf { it.samples.size }
                results += retained
                progress("retained completed checkpoint ${root.id}")
                continue
            }
            val candidates = mutableListOf<PrecisionCandidateSamples>()
            var currentSignature = "reconstruction"
            var currentReplicate = -1
            val failure = runCatching {
                val reconstructed = reconstructDecisionLocalRoot(pilot, registry, deck, plan.rootManifest.pilot, root)
                val parameters = plan.rootManifest.pilot.control.composition.parameters(root.schedule.policySearchBaseSeed, plan.rootManifest.pilot.control.leaf)
                val search = SearchTeacherSearchFactory.create(parameters.searchConfig(), defaultMonoRedOpponentPolicy())
                for (signature in root.candidateSignatures) {
                    currentSignature = signature
                    val samples = mutableListOf<DecisionLocalTerminalSample>()
                    try {
                        for (replicate in decisionLocalPrecisionReplicates) {
                            currentReplicate = replicate
                            samples += continueDecisionLocalCandidate(reconstructed, root, assignment.split, signature, replicate, search)
                            completed++
                            progress("${root.id} replicate $replicate")
                        }
                    } finally { candidates += PrecisionCandidateSamples(signature, samples.toList()) }
                }
            }.exceptionOrNull()?.let { "$currentSignature:replicate=$currentReplicate:${it::class.simpleName}:${it.message}" }
            val result = PrecisionRootSamples(root.id, plan.originalPayloadHashes.getValue(root.id), candidates, failure)
            ResearchRunCheckpoints.persist(output.resolve("roots/${root.id}.json"), plan.bindings.identity,
                "decision-local-precision-root-v1", root.pairIndex.toLong(), evidenceJson.encodeToString(result).encodeToByteArray())
            results += result
        }
        val successful = results.filter { it.failure == null }
        val analysis = successful.map { fresh -> analyzePrecisionRoot(prepared.oldRoots.getValue(fresh.rootId), fresh,
            plan.frozenComparisons.single { it.rootId == fresh.rootId }) }
        val report = DecisionLocalPrecisionReport(plan.bindings.identity, Instant.now().toString(), plan,
            successful.size, results.size - successful.size, completed, 2808 - completed, analysis,
            results.filter { it.failure != null }.associate { it.rootId to requireNotNull(it.failure) },
            (System.nanoTime() - started) / 1_000_000.0,
            if (successful.size == 40 && completed == 2808) "FIXED_PRECISION_COMPLETE_NO_PROMOTION" else "INCOMPLETE_PRECISION_POPULATION")
        ResearchRunFiles.atomicWrite(output.resolve("report.json"), evidenceJson.encodeToString(report))
        val text = buildString {
            append("# Fixed terminal-outcome precision follow-up\n\n")
            append("${report.conclusion}\n\n")
            append("- Identity: `${report.researchRunIdentity}`\n- Completed roots: ${report.completedRoots}/40\n")
            append("- Fresh terminal continuations: $completed/2808\n- Original outcomes remain separate: 936\n\n")
            append("| Root | Original selected gap | Fresh paired gap | Approximate 95% interval | Original selected among fresh best |\n")
            append("| --- | ---: | ---: | --- | --- |\n")
            analysis.forEach { r -> val e = r.frozenComparisonFreshEffect
                append("| ${r.rootId} | ${r.frozenComparison.originalGap} | ${e.freshMeanDifference} | ${e.freshApproximate95Lower} to ${e.freshApproximate95Upper} | ${r.originalSelectedStillAmongFreshBest} |\n")
            }
            append("\nIntervals are descriptive and unadjusted across comparisons. Tied original tops have no original directional claim.\n")
            append("No training, TEST evaluation, adaptive extension, or automatic promotion occurred. Return to owner interpretation.\n")
        }
        ResearchRunFiles.atomicWrite(output.resolve("report.md"), text)
        ResearchRunArtifacts(output, plan.bindings.identity).also { artifacts ->
            artifacts.register("plan.json"); artifacts.register("report.json"); artifacts.register("report.md")
            results.forEach { artifacts.register("roots/${it.rootId}.json") }; artifacts.finalize()
        }
        ResearchRunArtifacts.loadAndVerify(output, plan.bindings.identity)
        return report
    }
}
