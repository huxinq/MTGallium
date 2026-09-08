package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

/** One prospective fit; the existing Production study and its target meaning remain separate. */
@Serializable
internal data class AttackKernelLearningPlan(
    val build: ResearchBuildReference,
    val bank: CloningComparisonInput,
    val pilot: SavedRootPolicyInput,
    val control: SearchTeacherCalibrationPolicy,
    val targetSeedDomain: String,
    val pilotBank: CloningComparisonInput = bank,
    val workers: Int = 8,
    val maximumContinuations: Int = 25000,
    val ridge: Double = .001,
) {
    init {
        require(workers in 1..8 && maximumContinuations == 25000 && ridge == .001)
        require(targetSeedDomain.isNotBlank())
        require(control.particles == 8 && control.simulations == 56 && control.maxPolicyDecisions == 16)
        require(control.tacticalEvaluator == null && control.evaluator == MonoRedVisibleEvaluatorConfig())
        require(control.rolloutTurnHorizon == null && control.attackRootKernelRolloutFit == null)
        require(control.fastRootKernelRolloutFit != null && control.fastRootKernelRolloutFit == control.fastOpponentKernelRolloutFit)
        require(control.fastRootKernelRolloutFit.researchRunIdentity ==
            "research-run-v1-sha256:210ff28dbad987372548ca4f98c438469307586cd95bd1aaebd68cec0ce2a796")
    }
}

@Serializable
internal data class AttackValidationRow(val rootId: String, val group: String,
    val chosenSignature: String, val incumbentProbabilities: List<Double>, val improvementByRepetition: List<Double>)

@Serializable
internal data class AttackValidationResult(val rows: List<AttackValidationRow>,
    val equalGroupImprovementByRepetition: List<Double>, val groupImprovements: Map<String, Double>, val passed: Boolean)

internal fun attackValidationResult(rows: List<AttackValidationRow>): AttackValidationResult {
    require(rows.size == 48 && rows.map { it.rootId }.distinct().size == 48)
    require(rows.all { it.improvementByRepetition.size == 2 && it.improvementByRepetition.all(Double::isFinite) })
    val groups = rows.groupBy { it.group }
    require(groups.size == 12 && groups.values.all { it.size == 4 })
    val byRepetition = (0..1).map { rep -> groups.values.map { rs -> rs.map { it.improvementByRepetition[rep] }.average() }.average() }
    val means = groups.toSortedMap().mapValues { (_, rs) -> rs.flatMap { it.improvementByRepetition }.average() }
    return AttackValidationResult(rows, byRepetition, means, byRepetition.all { it > 0 } && means.values.count { it > 0 } >= 6)
}

@Serializable
internal data class AttackKernelLearningReport(val researchRunIdentity: String, val source: ResearchRunProvenance,
    val plan: AttackKernelLearningPlan, val assignedContinuationsIncludingPilot: Int,
    val development: List<SavedRootPolicyInput>, val validation: List<SavedRootPolicyInput> = emptyList(),
    val fit: RootKernelFitReference? = null, val validationResult: AttackValidationResult? = null,
    val disposition: String,
    val interpretation: String = "One attack kernel, fixed features/ridge. Targets are terminal payoffs conditional on support-checked hypotheses and frozen fast continuations. The comparator is the incumbent stochastic distribution, not one sampled action. Model-held-out groups from campaign-observed games are not pristine gameplay confirmation. This report never establishes deployed strength.")

internal fun requireAttackAllocation(bank: RealGamePositionBankReport) {
    require(bank.complete && bank.roots.size == 176)
    val groups = bank.roots.groupBy { it.seedGroupId }
    require(groups.size == 44 && groups.values.all { it.size == 4 && it.map { r -> r.partition }.distinct().size == 1 })
    require(bank.roots.count { it.partition == RealGamePositionPartition.DEVELOPMENT } == 128)
    require(bank.roots.count { it.partition == RealGamePositionPartition.VALIDATION } == 48)
    bank.roots.forEach { root ->
        require(attackKernelScope(root.reconstructedCandidates, root.profileExpansionExhaustive))
        require(root.candidates.toSet() == root.reconstructedCandidates.toSet())
        val features = rootActionKernelFeatures(root.information, root.reconstructedCandidates)
        require(features.distinct().size == features.size) { "Attack features alias supplied choices at ${root.rootId}" }
    }
}

internal fun attackLearningScreenPlan(pilot: PositionBankScreenPlan, bank: CloningComparisonInput,
    partition: PositionBankScreenPartition, roots: List<String>, seedDomain: String): PositionBankScreenPlan =
    pilot.copy(bankDirectory = bank.directory, expectedBankIdentity = bank.researchRunIdentity,
        partition = partition, rootIds = roots, rootLimit = roots.size, searchSeedDomain = seedDomain)

internal class AttackKernelLearningRunner(private val repository: Path) {
    fun run(plan: AttackKernelLearningPlan, output: Path, deckPath: Path): AttackKernelLearningReport {
        val source = ResearchRunProvenance.capture(repository).also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        verifyResearchBuild(plan.build, source)
        val bank = loadVerifiedRealGamePositionBank(Path.of(plan.bank.directory), plan.bank.researchRunIdentity)
        requireAttackAllocation(bank)
        val pilotBank = if (plan.pilotBank == plan.bank) bank else
            loadVerifiedRealGamePositionBank(Path.of(plan.pilotBank.directory), plan.pilotBank.researchRunIdentity)
        require(pilotBank.sourceProvenance.argentum.revision == bank.sourceProvenance.argentum.revision)
        val pilot = loadTerminalRootScreen(plan.pilot, pilotBank)
        val pilotCount = terminalRootScreenAccounting(pilot, pilotBank)
        require(pilot.valid && pilotCount.completedTerminalSamples == pilotCount.requestedContinuations)
        require(pilot.plan.partition == PositionBankScreenPartition.DEVELOPMENT && pilot.plan.rootIds.size == 2)
        val pilotRoots = pilot.plan.rootIds.map { id -> pilotBank.roots.single { it.rootId == id } }
        require(pilotRoots.map { it.seedGroupId }.distinct().size == 2)
        require(pilotRoots.all { it.partition == RealGamePositionPartition.DEVELOPMENT &&
            attackKernelScope(it.reconstructedCandidates, it.profileExpansionExhaustive) })
        require(pilot.plan.policies.single().search == plan.control && pilot.plan.repetitions == 2)
        require(pilot.plan.searchSeedDomain != plan.targetSeedDomain)
        val config = requireNotNull(pilot.plan.terminalContinuation)
        require(config.samplesPerAction == 8 && config.maximumContinuationPolicyDecisions == 4096)
        require(config.maximumTotalContinuations == plan.maximumContinuations)
        val assigned = bank.roots.sumOf { it.reconstructedCandidates.size } * 16 + pilotCount.requestedContinuations
        require(assigned <= plan.maximumContinuations)
        val bindings = ResearchRunBindings(protocol = "attack-kernel-learning-v1", material = mapOf(
            "plan" to sha256(evidenceJson.encodeToString(AttackKernelLearningPlan.serializer(), plan)),
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "bank-manifest" to researchSha256File(Path.of(plan.bank.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "pilot-manifest" to researchSha256File(Path.of(plan.pilot.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "pilot-bank-manifest" to researchSha256File(Path.of(plan.pilotBank.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        ))
        val directory = EvidenceStore(repository).requireDiagnosticOutput(output, "attack kernel learning")
        require(!Files.exists(directory)) { "One fit only; inspect retained output instead of rerunning" }
        Files.createDirectories(directory)
        writeJsonAtomically(directory.resolve("plan.json"), plan)
        writeJsonAtomically(directory.resolve("bindings.json"), bindings)
        val runner = PositionBankScreenRunner(repository, buildRegistry(), loadDeckManifest(deckPath))
        fun screen(partition: PositionBankScreenPartition): List<Pair<SavedRootPolicyInput, PositionBankScreenReport>> {
            val ids = bank.roots.filter { it.partition.name == partition.name }.map { it.rootId }.sorted()
            val completed = mutableListOf<Pair<SavedRootPolicyInput, PositionBankScreenReport>>()
            // Only one worker-sized batch is dispatched. A failed batch cannot launch later roots.
            for ((index, chunk) in ids.chunked(plan.workers).withIndex()) {
                val screenPlan = attackLearningScreenPlan(pilot.plan, plan.bank, partition, chunk, plan.targetSeedDomain)
                val path = directory.resolve("${partition.name.lowercase()}-$index")
                val report = runner.run(screenPlan, path, plan.workers)
                val ref = SavedRootPolicyInput(path.toString(), report.researchRunIdentity, plan.control.id)
                loadTerminalRootScreen(ref, bank)
                completed += ref to report
                if (!report.valid) break
            }
            return completed
        }
        fun finish(report: AttackKernelLearningReport): AttackKernelLearningReport {
            writeJsonAtomically(directory.resolve("report.json"), report)
            finalizeResearchWorkflowArtifacts(directory, bindings.identity)
            return report
        }
        val dev = screen(PositionBankScreenPartition.DEVELOPMENT)
        val devRefs = dev.map { it.first }
        if (dev.any { !it.second.valid }) return finish(AttackKernelLearningReport(bindings.identity, source, plan, assigned, devRefs,
            disposition = "DEVELOPMENT_TARGET_FAILURE"))
        val fitPath = directory.resolve("fit")
        val fitPlan = TerminalRootKernelFitPlan(plan.bank, devRefs.first(), plan.ridge,
            additional = devRefs.drop(1).map { TerminalRootKernelTrainingInput(plan.bank, it) })
        val fitReport = fitTerminalRootKernel(repository, fitPlan, fitPath)
        val fit = RootKernelFitReference(fitPath.toString(), fitReport.researchRunIdentity,
            researchSha256File(fitPath.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
        val model = fit.loadFrozenModel().model
        writeJsonAtomically(directory.resolve("frozen-fit.json"), fit)
        // First validation-target access follows the single frozen development fit.
        val validation = screen(PositionBankScreenPartition.VALIDATION)
        val valRefs = validation.map { it.first }
        if (validation.any { !it.second.valid }) return finish(AttackKernelLearningReport(bindings.identity, source, plan, assigned, devRefs,
            valRefs, fit, disposition = "VALIDATION_TARGET_FAILURE"))
        validation.forEach { terminalRootTrainingData(bank, it.second, PositionBankScreenPartition.VALIDATION) }
        val validationRows = validation.flatMap { it.second.rows }
        val scorer = CompiledRootActionKernel(model)
        val incumbent = plan.control.policy(0).effectiveRootRolloutPolicy()
        val deployed = fit.loadAttackRolloutPolicy(incumbent)
        val rows = bank.roots.filter { it.partition == RealGamePositionPartition.VALIDATION }.sortedBy { it.rootId }.map { root ->
            val menu = root.reconstructedCandidates
            val scores = scorer.scores(rootActionKernelFeatures(root.information, menu))
            val selectedIndex = scores.indices.maxBy { scores[it] }
            val selected = deployed.selectForExpansion({ root.information }, menu, root.profileExpansionExhaustive, 0, 0)
            require(selected.choice == menu[selectedIndex] && selected.diagnostic.selectedComponentId == deployed.id)
            val distribution = incumbent.distribution(root.information, menu, 0)
            val probabilities = menu.map { choice -> distribution.probabilityOf { it.signature == choice.signature } }
            val improvements = (0..1).map { rep ->
                val targets = validationRows.single { it.rootId == root.rootId && it.repetition == rep }.terminalRootActions
                require(targets.map { it.action } == menu)
                val values = targets.map { requireNotNull(it.meanTerminalPayoff) }
                values[selectedIndex] - values.indices.sumOf { values[it] * probabilities[it] }
            }
            AttackValidationRow(root.rootId, root.seedGroupId, menu[selectedIndex].signature, probabilities, improvements)
        }
        val result = attackValidationResult(rows)
        return finish(AttackKernelLearningReport(bindings.identity, source, plan, assigned, devRefs, valRefs, fit, result,
            if (result.passed) "VALIDATION_PASSED_GAMEPLAY_REQUIRED" else "VALIDATION_FAILED"))
    }
}
