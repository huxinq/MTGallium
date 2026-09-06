package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.InformationSetSearch
import org.mtgallium.agent.infoset.core.OpponentPolicyBehaviorSpecification
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionSummary
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.searchteacher.ConfiguredMonoRedInformationEvaluator
import org.mtgallium.agent.searchteacher.MonoRedVisibleFeatures
import org.mtgallium.agent.searchteacher.SearchTeacherBehaviorSpecification
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings

@Serializable
internal data class PositionBankTerminalContinuationPlan(
    val schemaVersion: Int = 1,
    val bankDirectory: String,
    val expectedBankIdentity: String,
    val partition: PositionBankScreenPartition,
    val rootIds: List<String>,
    val policy: PositionBankScreenPolicy,
    val samplesPerRoot: Int,
    val maxContinuationDecisions: Int,
    val baseSeed: Long,
    val maximumCandidates: Int,
) {
    init {
        require(schemaVersion == 1 && bankDirectory.isNotBlank() && expectedBankIdentity.isNotBlank())
        require(partition == PositionBankScreenPartition.DEVELOPMENT) { "Terminal diagnostics are development-only" }
        require(rootIds.isNotEmpty() && rootIds.all(String::isNotBlank) && rootIds.distinct().size == rootIds.size)
        require(samplesPerRoot > 0 && maxContinuationDecisions > 0 && maximumCandidates > 0)
    }
}

/** One paired coordinate is shared by every candidate, independent of candidate order/signature. */
@Serializable
internal data class PositionBankTerminalCoordinate(
    val replicate: Int,
    val particleIndex: Int,
    val particleSamplingSeed: Long,
    val futureSeed: Long,
    val continuationSeed: Long,
)

internal fun positionBankTerminalCoordinates(
    baseSeed: Long, rootId: String, weights: List<Double>, samples: Int,
): List<PositionBankTerminalCoordinate> {
    val samplingSeed = ComponentSeeds.derive(baseSeed, rootId, "position-bank-terminal-particles-v1")
    return InformationSetSearch.productionRootParticleIndices(weights, samplingSeed, samples).mapIndexed { replicate, index ->
        PositionBankTerminalCoordinate(replicate, index, samplingSeed,
            ComponentSeeds.derive(baseSeed, rootId, replicate, "position-bank-terminal-future-v1"),
            ComponentSeeds.derive(baseSeed, rootId, replicate, "position-bank-terminal-continuation-v1"))
    }
}

@Serializable
internal enum class PositionBankTerminalRefusal {
    RECONSTRUCTION, CANDIDATE_CAP, BELIEF, HYPOTHETICAL_FORK, FIRST_ACTION, CONTINUATION, CONTINUATION_LIMIT,
}

@Serializable
internal data class PositionBankTerminalSample(
    val candidateSignature: String,
    val coordinate: PositionBankTerminalCoordinate,
    val elapsedMillis: Double,
    val payoff: Double? = null,
    val policyDecisions: Int? = null,
    val rootPolicyDecisions: OpponentPolicyDecisionSummary? = null,
    val opponentPolicyDecisions: OpponentPolicyDecisionSummary? = null,
    /** Root-player projection after the assigned first action, before any continuation decision. */
    val hypotheticalAfterActionFeatures: MonoRedVisibleFeatures? = null,
    val hypotheticalAfterActionInformationDigest: String? = null,
    val terminalImmediatelyAfterAction: Boolean? = null,
    val refusal: PositionBankTerminalRefusal? = null,
    val diagnostic: String? = null,
) {
    init {
        require(candidateSignature.isNotBlank() && elapsedMillis.isFinite() && elapsedMillis >= 0)
        require((payoff != null) != (refusal != null))
        require((hypotheticalAfterActionFeatures == null) == (hypotheticalAfterActionInformationDigest == null))
        require((hypotheticalAfterActionFeatures == null) == (terminalImmediatelyAfterAction == null))
        if (payoff != null) {
            require(payoff in -1.0..1.0 && policyDecisions != null && policyDecisions >= 0)
            require(rootPolicyDecisions != null && opponentPolicyDecisions != null)
            require(rootPolicyDecisions.decisions + opponentPolicyDecisions.decisions == policyDecisions)
            require(rootPolicyDecisions.evidenceInvalidatingReplacements == 0 &&
                opponentPolicyDecisions.evidenceInvalidatingReplacements == 0)
        } else {
            // The production seam does not return partial audits when it throws.
            require(policyDecisions == null && rootPolicyDecisions == null && opponentPolicyDecisions == null)
        }
    }
}

@Serializable
internal data class PositionBankTerminalGap(
    val firstCandidate: String, val secondCandidate: String, val meanFirstMinusSecond: Double,
)

@Serializable
internal data class PositionBankTerminalSummary(
    val assignedSamples: Int,
    val attemptedSamples: Int,
    val terminalSamples: Int,
    val refusedSamples: Int,
    val unattemptedSamples: Int,
    val complete: Boolean,
    val candidateMeanPayoffs: Map<String, Double> = emptyMap(),
    val pairedGaps: List<PositionBankTerminalGap> = emptyList(),
)

/** A complete rectangular paired population is required before any root value is reported. */
internal fun summarizePositionBankTerminalSamples(
    candidateSignatures: List<String>, samplesPerRoot: Int,
    samples: List<PositionBankTerminalSample>, rootRefused: Boolean = false,
): PositionBankTerminalSummary {
    require(candidateSignatures.isNotEmpty() && candidateSignatures.distinct().size == candidateSignatures.size)
    require(samplesPerRoot > 0)
    val assigned = Math.multiplyExact(candidateSignatures.size, samplesPerRoot)
    require(samples.all { it.candidateSignature in candidateSignatures && it.coordinate.replicate in 0 until samplesPerRoot })
    require(samples.map { it.candidateSignature to it.coordinate.replicate }.distinct().size == samples.size) {
        "Duplicate candidate/replicate samples cannot form a paired population"
    }
    require(samples.groupBy { it.coordinate.replicate }.values.all { group -> group.map { it.coordinate }.distinct().size == 1 }) {
        "Candidate samples must share the same particle and continuation coordinates"
    }
    val terminal = samples.count { it.payoff != null }
    val complete = !rootRefused && terminal == assigned
    val means = if (complete) candidateSignatures.associateWith { signature ->
        samples.filter { it.candidateSignature == signature }.map { requireNotNull(it.payoff) }.average()
    } else emptyMap()
    val pairedGaps = if (complete) candidateSignatures.flatMapIndexed { index, first ->
        candidateSignatures.drop(index + 1).map { second ->
            val a = samples.filter { it.candidateSignature == first }.associateBy { it.coordinate.replicate }
            val b = samples.filter { it.candidateSignature == second }.associateBy { it.coordinate.replicate }
            PositionBankTerminalGap(first, second, (0 until samplesPerRoot).map {
                requireNotNull(a.getValue(it).payoff) - requireNotNull(b.getValue(it).payoff)
            }.average())
        }
    } else emptyList()
    return PositionBankTerminalSummary(assigned, samples.size, terminal, samples.size - terminal,
        assigned - samples.size, complete, means, pairedGaps)
}

@Serializable
internal data class PositionBankTerminalRoot(
    val rootId: String,
    val actor: String,
    val beliefBaseSeed: Long,
    val candidates: List<SemanticChoice>,
    val sourceAdmittedCandidates: List<SemanticChoice>,
    val proposalVersion: String,
    val profileExpansionExhaustive: Boolean,
    val summary: PositionBankTerminalSummary,
    val samples: List<PositionBankTerminalSample> = emptyList(),
    val policyIdentity: String? = null,
    val behaviorSpecification: SearchTeacherBehaviorSpecification? = null,
    val rootRolloutPolicy: OpponentPolicyBehaviorSpecification? = null,
    val opponentRolloutPolicy: OpponentPolicyBehaviorSpecification? = null,
    val particleWeights: List<Double> = emptyList(),
    val beliefDiagnostics: BeliefDiagnostics? = null,
    val reconstructionMillis: Double? = null,
    val beliefMillis: Double? = null,
    val elapsedMillis: Double,
    val refusal: PositionBankTerminalRefusal? = null,
    val diagnostic: String? = null,
)

@Serializable
internal data class PositionBankTerminalContinuationReport(
    val schemaVersion: Int = 1,
    val researchRunIdentity: String,
    val sourceProvenance: PolicySourceProvenance,
    val generatedAtUtc: String,
    val plan: PositionBankTerminalContinuationPlan,
    val workerThreads: Int,
    val roots: List<PositionBankTerminalRoot>,
    val assignedSamples: Int,
    val attemptedSamples: Int,
    val terminalSamples: Int,
    val refusedSamples: Int,
    val unattemptedSamples: Int,
    val completeRoots: Int,
    val invalidRoots: Int,
    val complete: Boolean,
    val limitations: List<String> = listOf(
        "Payoffs are actual terminals of sampled hypothetical worlds conditioned on the declared fixed rollout policies; they are not observed source-game results.",
        "No leaf evaluator or repeated full Search Teacher search generates these payoffs. Search simulations, search depth, exploration and evaluator coefficients are not optimized by this diagnostic.",
        "Sequential belief reconstruction uses each source root's base seed; the plan base seed controls paired weighted particle draws, hypothetical future chance and continuation policies.",
        "Every candidate in the reconstructed current profile expansion is assigned every replicate. Roots exceeding the candidate cap are refused without truncation; this profile need not enumerate every legal action.",
        "Any invalid or missing sample suppresses all means and gaps for its root. Continuation exceptions lack partial decision audits and do not imply zero work or a strategic outcome.",
        "Paired finite-sample gaps are descriptive only: no confidence intervals, correct-action labels, tactical proofs, parameter winners or playing-strength claims.",
        "Cached after-action features are the root player's legitimate projection in each hypothetical world immediately after the assigned action, before rollout; this cache is not the production leaf-settlement distribution.",
        "Only explicitly selected development roots are used; timings describe this concurrent workload and include reconstruction separately.",
    ),
) {
    init {
        require(roots.map { it.rootId } == plan.rootIds)
        require(assignedSamples == roots.sumOf { it.summary.assignedSamples })
        require(attemptedSamples == roots.sumOf { it.summary.attemptedSamples })
        require(terminalSamples == roots.sumOf { it.summary.terminalSamples })
        require(refusedSamples == roots.sumOf { it.summary.refusedSamples })
        require(unattemptedSamples == roots.sumOf { it.summary.unattemptedSamples })
        require(completeRoots == roots.count { it.summary.complete } && invalidRoots == roots.size - completeRoots)
        require(complete == (completeRoots == plan.rootIds.size && terminalSamples == assignedSamples &&
            refusedSamples == 0 && unattemptedSamples == 0))
    }
}

internal class PositionBankTerminalContinuationRunner(
    private val root: Path, private val registry: CardRegistry, private val manifest: DeckManifest,
) {
    fun run(plan: PositionBankTerminalContinuationPlan, output: Path, workerThreads: Int): PositionBankTerminalContinuationReport {
        require(workerThreads > 0)
        val sourceRun = RunProvenance.capture(root).also { it.requireReady() }
        require(!sourceRun.outerDirty && !sourceRun.engineDirty) { "Terminal diagnostics require a committed clean treatment" }
        val source = requireNotNull(sourceRun.sourceProvenance)
        val bankDirectory = Path.of(plan.bankDirectory)
        val bank = loadVerifiedRealGamePositionBank(bankDirectory, plan.expectedBankIdentity)
        val selected = plan.rootIds.map { id ->
            bank.roots.single { it.rootId == id }.also {
                require(it.partition == RealGamePositionPartition.DEVELOPMENT) { "Root $id is not a development root" }
            }
        }
        val bindings = ResearchRunBindings(protocol = "position-bank-terminal-continuations-v1", material = mapOf(
            "plan" to sha256(evidenceJson.encodeToString(plan)), "bank" to bank.bankIdentity,
            "bank-manifest" to sha256File(bankDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "source" to sha256(evidenceJson.encodeToString(source)),
            "deck" to manifest.deckHash(), "card-pool" to manifest.cardPoolHash(),
            "worker-threads" to workerThreads.toString(),
        ))
        val directory = EvidenceStore(root).requireDiagnosticOutput(output, "Position-bank terminal continuations")
        if (Files.exists(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE))) {
            ResearchRunArtifacts.loadAndVerify(directory, bindings.identity)
            return evidenceJson.decodeFromString<PositionBankTerminalContinuationReport>(Files.readString(directory.resolve("report.json")))
                .also { require(it.researchRunIdentity == bindings.identity && it.plan == plan) }
        }
        val progress = System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of)
        val completed = AtomicInteger()
        publishDurableRunProgress(progress, 0, selected.size, "terminal continuations", "reconstructing development roots", "roots")
        val roots = parallelMapOrdered(selected.size, workerThreads) { index ->
            evaluateRoot(selected[index], bank, plan).also {
                publishDurableRunProgress(progress, completed.incrementAndGet(), selected.size,
                    "terminal continuations", it.rootId, "roots")
            }
        }
        val report = PositionBankTerminalContinuationReport(researchRunIdentity = bindings.identity,
            sourceProvenance = source, generatedAtUtc = Instant.now().toString(), plan = plan,
            workerThreads = workerThreads, roots = roots,
            assignedSamples = roots.sumOf { it.summary.assignedSamples }, attemptedSamples = roots.sumOf { it.summary.attemptedSamples },
            terminalSamples = roots.sumOf { it.summary.terminalSamples }, refusedSamples = roots.sumOf { it.summary.refusedSamples },
            unattemptedSamples = roots.sumOf { it.summary.unattemptedSamples },
            completeRoots = roots.count { it.summary.complete }, invalidRoots = roots.count { !it.summary.complete },
            complete = roots.all { it.summary.complete } && roots.all { it.summary.terminalSamples == it.summary.assignedSamples })
        writeJsonAtomically(directory.resolve("plan.json"), plan)
        writeJsonAtomically(directory.resolve("report.json"), report)
        writeTextAtomically(directory.resolve("report.md"), buildString {
            appendLine("# Position-bank sampled terminal continuations")
            appendLine("Run `${report.researchRunIdentity}`; bank `${bank.bankIdentity}`; development roots only.")
            appendLine("Roots: ${report.completeRoots} complete, ${report.invalidRoots} invalid.")
            appendLine("Samples: ${report.assignedSamples} assigned, ${report.attemptedSamples} attempted, ${report.terminalSamples} terminal, ${report.refusedSamples} refused, ${report.unattemptedSamples} unattempted.")
            roots.forEach { appendLine("- ${it.rootId}: ${it.summary}; refusal=${it.refusal}.") }
            report.limitations.forEach { appendLine("- $it") }
        })
        ResearchRunArtifacts(directory, bindings.identity).also {
            listOf("plan.json", "report.json", "report.md").forEach(it::register)
            it.finalize()
        }
        return report
    }

    private fun evaluateRoot(position: RealGamePositionBankRoot, bank: RealGamePositionBankReport,
        plan: PositionBankTerminalContinuationPlan): PositionBankTerminalRoot {
        val started = System.nanoTime()
        var row = PositionBankTerminalRoot(position.rootId, position.actor, position.baseSeed,
            position.reconstructedCandidates, position.candidates,
            position.proposalVersion, position.profileExpansionExhaustive,
            summarizePositionBankTerminalSamples(position.reconstructedCandidates.map { it.signature }, plan.samplesPerRoot, emptyList()),
            elapsedMillis = 0.0)
        var phase = PositionBankTerminalRefusal.RECONSTRUCTION
        try {
            val reconstructed = reconstructPositionBankRoot(position, bank, registry, manifest, plan.policy)
            val (actual, session, candidates, reconstructionMillis) = reconstructed
            val arenaPolicy = plan.policy.search.policy(position.baseSeed)
            val rootPolicy = arenaPolicy.effectiveRootRolloutPolicy()
            val opponentPolicy = arenaPolicy.effectiveOpponentRolloutPolicy()
            row = row.copy(candidates = candidates, reconstructionMillis = reconstructionMillis,
                policyIdentity = session.policyIdentity, behaviorSpecification = session.behaviorSpecification,
                rootRolloutPolicy = rootPolicy.behaviorSpecification, opponentRolloutPolicy = opponentPolicy.behaviorSpecification)
            phase = PositionBankTerminalRefusal.CANDIDATE_CAP
            require(candidates.size <= plan.maximumCandidates) { "Complete candidate population ${candidates.size} exceeds cap ${plan.maximumCandidates}" }
            phase = PositionBankTerminalRefusal.BELIEF
            val beliefStarted = System.nanoTime()
            val belief = session.beliefBatch(actual)
            val worlds = belief.particles.map { particle ->
                (particle.value as ArgentumSearchWorld).also { require(it !== actual) { "Referee state cannot be a terminal sample particle" } }
            }
            require(belief.diagnostics.opponentPolicyDecisions.evidenceInvalidatingReplacements == 0)
            row = row.copy(particleWeights = belief.particles.map { it.weight }, beliefDiagnostics = belief.diagnostics,
                beliefMillis = (System.nanoTime() - beliefStarted) / 1_000_000.0)
            val coordinates = positionBankTerminalCoordinates(plan.baseSeed, position.rootId, row.particleWeights, plan.samplesPerRoot)
            val search = SearchTeacherSearchFactory.create(plan.policy.search.parameters(position.baseSeed).searchConfig(),
                rolloutPolicy = rootPolicy, rolloutOpponentPolicy = opponentPolicy,
                informationEvaluator = ConfiguredMonoRedInformationEvaluator(plan.policy.evaluator))
            val samples = coordinates.flatMap { coordinate -> candidates.map { candidate ->
                sampleCandidate(worlds[coordinate.particleIndex], position.actor, candidate.signature, coordinate,
                    search, plan.maxContinuationDecisions)
            } }
            row = row.copy(samples = samples, summary = summarizePositionBankTerminalSamples(
                candidates.map { it.signature }, plan.samplesPerRoot, samples))
        } catch (failure: Exception) {
            row = row.copy(refusal = phase, diagnostic = "${failure::class.simpleName}: ${failure.message}",
                summary = summarizePositionBankTerminalSamples(row.candidates.map { it.signature }, plan.samplesPerRoot, row.samples, true))
        }
        return row.copy(elapsedMillis = (System.nanoTime() - started) / 1_000_000.0)
    }
}

private fun sampleCandidate(particle: ArgentumSearchWorld, actor: String, signature: String,
    coordinate: PositionBankTerminalCoordinate, search: InformationSetSearch, cap: Int): PositionBankTerminalSample {
    val started = System.nanoTime()
    var phase = PositionBankTerminalRefusal.HYPOTHETICAL_FORK
    var afterActionFeatures: MonoRedVisibleFeatures? = null
    var afterActionDigest: String? = null
    var terminalImmediately: Boolean? = null
    try {
        val child = particle.forkForHypotheticalSearch(coordinate.futureSeed)
        phase = PositionBankTerminalRefusal.FIRST_ACTION
        val choice = child.expandChoices().candidates.single { it.signature == signature }
        require(child.stepWithReplayTrace(choice).result.accepted) { "Sampled first action was rejected" }
        phase = PositionBankTerminalRefusal.CONTINUATION
        val immediate = child.terminalPayoff(actor)
        val afterActionInformation = child.informationState(actor)
        val extractedFeatures = MonoRedVisibleFeatures.extract(afterActionInformation, actor)
        afterActionFeatures = extractedFeatures
        afterActionDigest = afterActionInformation.informationStateDigest
        terminalImmediately = immediate != null
        val terminal = if (immediate == null) search.continueFirstUnvisitedEdgeToTerminal(child, actor,
            coordinate.continuationSeed, coordinate.replicate, childDepth = 1,
            maximumContinuationPolicyDecisions = cap) else null
        return PositionBankTerminalSample(signature, coordinate, (System.nanoTime() - started) / 1_000_000.0,
            payoff = immediate ?: requireNotNull(terminal).payoff, policyDecisions = terminal?.policyDecisions ?: 0,
            rootPolicyDecisions = terminal?.rootPolicyDecisions ?: OpponentPolicyDecisionSummary(),
            opponentPolicyDecisions = terminal?.opponentPolicyDecisions ?: OpponentPolicyDecisionSummary(),
            hypotheticalAfterActionFeatures = afterActionFeatures, hypotheticalAfterActionInformationDigest = afterActionDigest,
            terminalImmediatelyAfterAction = terminalImmediately)
    } catch (failure: Exception) {
        val refusal = if (phase == PositionBankTerminalRefusal.CONTINUATION && failure is IllegalStateException &&
            failure.message == "Terminal continuation exhausted $cap policy decisions") PositionBankTerminalRefusal.CONTINUATION_LIMIT else phase
        return PositionBankTerminalSample(signature, coordinate, (System.nanoTime() - started) / 1_000_000.0,
            hypotheticalAfterActionFeatures = afterActionFeatures, hypotheticalAfterActionInformationDigest = afterActionDigest,
            terminalImmediatelyAfterAction = terminalImmediately,
            refusal = refusal, diagnostic = "${failure::class.simpleName}: ${failure.message}")
    }
}
