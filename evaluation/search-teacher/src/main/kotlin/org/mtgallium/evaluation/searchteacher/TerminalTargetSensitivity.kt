package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.research.run.*
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

@Serializable
internal data class TerminalTargetVariant(val id: String, val plan: PositionBankScreenPlan) {
    init { require(id.matches(Regex("[a-z][a-z0-9-]*")) && id != "baseline") }
}

@Serializable
internal data class TerminalTargetSensitivityPlan(
    val schemaVersion: Int = 1,
    val build: ResearchBuildReference,
    val campaignId: String, val campaignDirectory: String,
    val bank: CloningComparisonInput,
    val baselineTargets: SavedRootPolicyInput,
    val productionControl: CloningComparisonInput,
    val baselineModel: RootKernelFitReference,
    val candidateModel: RootKernelFitReference,
    val rootIds: List<String>, val samplePrefixes: List<Int>,
    val variants: List<TerminalTargetVariant>,
    val workers: Int = 2,
    val maximumNewContinuations: Int,
    val maximumProjectedCollectionSeconds: Double = 1800.0,
) {
    init {
        require(schemaVersion == 1 && workers > 0 && maximumNewContinuations > 0)
        require(rootIds.isNotEmpty() && rootIds == rootIds.distinct().sorted())
        require(samplePrefixes.isNotEmpty() && samplePrefixes == samplePrefixes.distinct().sorted() && samplePrefixes.first() > 0)
        require(variants.isNotEmpty() && variants.map { it.id }.distinct().size == variants.size)
        require(maximumProjectedCollectionSeconds.isFinite() && maximumProjectedCollectionSeconds > 0)
        variants.forEach { variant ->
            val p = variant.plan
            require(p.mode == PositionBankScreenMode.TERMINAL_CONTINUATIONS && p.partition == PositionBankScreenPartition.VALIDATION)
            require(p.rootIds == rootIds && p.expectedBankIdentity == bank.researchRunIdentity && p.bankDirectory == bank.directory && p.policies.size == 1)
            require(samplePrefixes.last() <= requireNotNull(p.terminalContinuation).samplesPerAction)
            val policy = p.policies.single().search
            require(policy.rootCloningFit == null && policy.rootKernelRolloutFit == null) { "Sensitivity controls are declared hand-written continuation policies, not fitted candidates" }
        }
    }
}

@Serializable
internal data class TerminalTargetRankingChange(
    val rootId: String, val seedGroupId: String,
    val baselineBestActions: List<String>, val variantBestActions: List<String>,
    val reversedStrictPairs: Int, val tiedAtEitherEndPairs: Int, val actionPairs: Int,
)

internal fun targetRankingChange(rootId: String, group: String, baseline: Map<String, Double>, variant: Map<String, Double>): TerminalTargetRankingChange {
    require(baseline.keys == variant.keys && baseline.size >= 2 && baseline.values.all { it.isFinite() } && variant.values.all { it.isFinite() })
    val actions = baseline.keys.sorted(); var reversed = 0; var tied = 0; var pairs = 0
    for (i in actions.indices) for (j in i+1 until actions.size) {
        pairs++
        val a = baseline.getValue(actions[i]) - baseline.getValue(actions[j]); val b = variant.getValue(actions[i]) - variant.getValue(actions[j])
        if (a == 0.0 || b == 0.0) tied++ else if ((a > 0) != (b > 0)) reversed++
    }
    return TerminalTargetRankingChange(rootId, group, baseline.filterValues { it == baseline.values.max() }.keys.sorted(),
        variant.filterValues { it == variant.values.max() }.keys.sorted(), reversed, tied, pairs)
}

/** Prefixes reuse samples rather than inventing independent repetitions or fitted targets. */
internal fun terminalTargetPrefixValues(report: PositionBankScreenReport, rootId: String, samples: Int): List<Map<String, Double>> {
    require(samples > 0 && samples <= requireNotNull(report.plan.terminalContinuation).samplesPerAction && report.plan.policies.size == 1)
    return (0 until report.plan.repetitions).map { rep ->
        val row = report.rows.single { it.rootId == rootId && it.repetition == rep }
        require(row.disposition == PositionBankScreenDisposition.TERMINAL_CONTINUATIONS)
        row.terminalRootActions.associate { action ->
            require(action.disposition == TerminalRootActionDisposition.COMPLETE && action.samples.size == action.requestedSamples)
            action.action.signature to action.samples.take(samples).map { it.payoff }.average()
        }
    }
}

@Serializable
internal data class TerminalTargetSensitivityCell(
    val variantId: String, val samplesPerAction: Int,
    val targets: SavedRootPolicyInput,
    val candidateVersusOld: TerminalStudyComparison,
    val candidateVersusProduction: TerminalStudyComparison,
    val rankings: List<TerminalTargetRankingChange>,
    val equalGroupStrictReversalFraction: Double,
    val disjointBestActionSets: Int,
)

@Serializable
internal data class TerminalTargetSensitivityReport(
    val identity: String, val source: ResearchRunProvenance, val plan: TerminalTargetSensitivityPlan,
    val cells: List<TerminalTargetSensitivityCell>, val costs: ResearchInvocationCosts,
    val interpretation: String = "Frozen model and production actions evaluated against a fixed grid of conditional terminal targets. Nested sample prefixes are dependent subsets, not extra independent evidence. Rankings compare with the full retained baseline target, which is itself noisy. Continuation and belief-particle variants measure sensitivity, not optimal values or a newly selected training target. Every declared cell is retained; there is no best-variant selection or model promotion.",
)

internal class TerminalTargetSensitivityRunner(private val repository: Path) {
    fun run(plan: TerminalTargetSensitivityPlan, output: Path, deckPath: Path): TerminalTargetSensitivityReport {
        val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "terminal target sensitivity")
        require(!Files.exists(destination))
        val costs = ResearchCostRecorder(destination.resolve("costs.json"))
        listOf(plan.build.directory, plan.campaignDirectory, plan.bank.directory, plan.baselineTargets.directory, plan.productionControl.directory, plan.baselineModel.directory, plan.candidateModel.directory).forEach { raw ->
            val input = Path.of(raw).toAbsolutePath().normalize()
            require(!destination.startsWith(input) && !input.startsWith(destination)) { "Sensitivity input/output overlap: $input" }
        }
        val source = ResearchRunProvenance.capture(repository).also { it.requireReady() }
        verifyResearchBuild(plan.build, source)
        val bank = loadVerifiedRealGamePositionBank(Path.of(plan.bank.directory), plan.bank.researchRunIdentity)
        val roots = bank.roots.filter { it.rootId in plan.rootIds }.sortedBy { it.rootId }
        require(roots.map { it.rootId } == plan.rootIds && roots.all { it.partition == RealGamePositionPartition.VALIDATION && it.profileExpansionExhaustive })
        val pilotIds = terminalStudyPilotRoots(roots)
        val total = plan.variants.sumOf { v ->
            terminalRootWorkload(requireNotNull(v.plan.terminalContinuation), roots.map { it.reconstructedCandidates.size }, v.plan.repetitions, 1).toLong() +
                roots.filter { it.rootId in pilotIds }.sumOf { it.reconstructedCandidates.size.toLong() * 2 }
        }
        require(total <= plan.maximumNewContinuations) { "Declared sensitivity grid and pilots exceed the fixed cap" }
        val bindings = ResearchRunBindings(protocol = "terminal-target-sensitivity-v1", material = mapOf(
            "plan" to sha256(evidenceJson.encodeToString(TerminalTargetSensitivityPlan.serializer(), plan)),
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "runtime" to preflightRuntimeHash(researchPreflightRuntime()), "deck" to researchSha256File(deckPath),
            "bank-manifest" to researchSha256File(Path.of(plan.bank.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "baseline-target-manifest" to researchSha256File(Path.of(plan.baselineTargets.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "control-manifest" to researchSha256File(Path.of(plan.productionControl.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE))))
        writeJsonAtomically(destination.resolve("bindings.json"), bindings); writeJsonAtomically(destination.resolve("plan.json"), plan)
        val campaign = CampaignDataRegistry(repository, Path.of(plan.campaignDirectory), plan.campaignId)
        campaign.record(CampaignDataUsePlan(campaignId = plan.campaignId, studyIdentity = bindings.identity,
            role = CampaignDataRole.METHOD_SELECTION, timing = CampaignDataTiming.PROSPECTIVE_RESERVATION,
            populations = listOf(CampaignPopulationInput(plan.bank, plan.rootIds)), purpose = "Declared target sensitivity on frozen model actions; adaptive method evidence"))
        try {
            // Retain intent before opening payoff-derived reports or model values; failed later admission still counts.
            val baseline = loadTerminalRootScreen(plan.baselineTargets, bank)
            requireProductionTerminalTarget(baseline.plan)
            terminalRootTrainingData(bank, baseline, PositionBankScreenPartition.VALIDATION)
            require(baseline.sourceProvenance.argentum.revision == source.checkedOutEngineCommit)
            require(plan.rootIds.all { it in baseline.selectedRootIds } && plan.samplePrefixes.last() <= requireNotNull(baseline.plan.terminalContinuation).samplesPerAction)
            plan.variants.forEach { variant ->
                require(variant.plan.repetitions == baseline.plan.repetitions && variant.plan.searchSeedDomain == baseline.plan.searchSeedDomain)
                require(variant.plan.terminalContinuation?.seedRule == baseline.plan.terminalContinuation?.seedRule)
            }
            val controlPlan = evidenceJson.decodeFromString<PositionBankScreenPlan>(Files.readString(Path.of(plan.productionControl.directory).resolve("plan.json")))
            val control = loadStudyScreen(plan.productionControl, controlPlan)
            requireTerminalStudyControl(control, controlPlan)
            require(controlPlan.expectedBankIdentity == bank.bankIdentity && plan.rootIds.all { it in control.selectedRootIds })
            val oldModel = CompiledRootActionKernel(plan.baselineModel.loadFrozenModel().model)
            val newModel = CompiledRootActionKernel(plan.candidateModel.loadFrozenModel().model)
            val runner = PositionBankScreenRunner(repository, buildRegistry(), loadDeckManifest(deckPath))
            val targets = linkedMapOf("baseline" to (plan.baselineTargets to baseline))
            for (variant in plan.variants) {
                val pilot = costs.measure("${variant.id}-preflight", describe = { terminalWork(it, bank) }, validate = { terminalRootTrainingData(bank, it, PositionBankScreenPartition.VALIDATION) }) {
                    runner.run(variant.plan.copy(rootIds = pilotIds, rootLimit = pilotIds.size, repetitions = 1,
                        terminalContinuation = requireNotNull(variant.plan.terminalContinuation).copy(samplesPerAction = 2)), destination.resolve("${variant.id}-preflight"), plan.workers)
                }
                val accounting = terminalRootScreenAccounting(pilot, bank)
                val workload = terminalRootWorkload(requireNotNull(variant.plan.terminalContinuation), roots.map { it.reconstructedCandidates.size }, variant.plan.repetitions, 1)
                require(accounting.accumulatedSelectionMillis / accounting.completedTerminalSamples * workload / plan.workers / 1000 <= plan.maximumProjectedCollectionSeconds)
                val path = destination.resolve(variant.id)
                val report = costs.measure(variant.id, describe = { terminalWork(it, bank) }, validate = { terminalRootTrainingData(bank, it, PositionBankScreenPartition.VALIDATION) }) {
                    runner.run(variant.plan, path, plan.workers)
                }
                targets[variant.id] = SavedRootPolicyInput(path.toString(), report.researchRunIdentity, variant.plan.policies.single().search.id) to report
            }
            val cells = costs.measure("compare-target-grid") {
                targets.flatMap { (id, pair) -> plan.samplePrefixes.map { prefix ->
                    val oldRows = mutableListOf<SavedRootRegretRow>(); val prodRows = mutableListOf<SavedRootRegretRow>()
                    val ranks = roots.map { root ->
                        val features = rootActionKernelFeatures(root.information, root.reconstructedCandidates)
                        fun choice(model: CompiledRootActionKernel): String { val scores = model.scores(features); return root.reconstructedCandidates[scores.indices.maxBy { scores[it] }].signature }
                        val candidate = choice(newModel); val old = choice(oldModel)
                        val production = requireNotNull(control.rows.single { it.rootId == root.rootId }.chosen).signature
                        val values = terminalTargetPrefixValues(pair.second, root.rootId, prefix)
                        fun row(base: String): SavedRootRegretRow { val (b, c, d) = savedRootReferenceComparison(values, base, candidate); return SavedRootRegretRow(root.rootId, root.seedGroupId, 0, base, candidate, b, c, d.average(), d) }
                        oldRows += row(old); prodRows += row(production)
                        val full = terminalTargetPrefixValues(baseline, root.rootId, requireNotNull(baseline.plan.terminalContinuation).samplesPerAction)
                        fun means(reps: List<Map<String, Double>>) = reps.first().keys.associateWith { a -> reps.map { it.getValue(a) }.average() }
                        targetRankingChange(root.rootId, root.seedGroupId, means(full), means(values))
                    }
                    TerminalTargetSensitivityCell(id, prefix, pair.first, terminalStudyComparison(oldRows), terminalStudyComparison(prodRows), ranks,
                        ranks.groupBy { it.seedGroupId }.values.map { group -> group.map { it.reversedStrictPairs.toDouble()/it.actionPairs }.average() }.average(),
                        ranks.count { (it.baselineBestActions intersect it.variantBestActions.toSet()).isEmpty() })
                } }
            }
            return TerminalTargetSensitivityReport(bindings.identity, source, plan, cells, costs.snapshot()).also {
                writeJsonAtomically(destination.resolve("report.json"), it); finalizeStudyArtifacts(destination, bindings.identity)
            }
        } catch (failure: Exception) {
            costs.persist(); writeTextAtomically(destination.resolve("failure.txt"), "${failure.javaClass.simpleName}: ${failure.message}\n"); throw failure
        }
    }
}
