package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.research.run.*
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

@Serializable
internal data class RootActionKernelCoveragePlan(val previousFit: CloningComparisonInput,
    val bankRootLimit: Int = 256, val maxRootsPerGame: Int = 8, val maxAdditionalActions: Int = 1600) {
    init { require(bankRootLimit > 0 && maxRootsPerGame > 0 && maxAdditionalActions > 0) }
}

@Serializable
internal data class RootActionKernelCoverageReport(val researchRunIdentity: String, val source: ResearchRunProvenance,
    val plan: RootActionKernelCoveragePlan, val bankIdentity: String, val bankComplete: Boolean,
    val reconstructedRoots: Int, val bankRefusedRoots: Int, val addedRootIds: List<String>,
    val overlappingRootIds: List<String>, val excludedNonExhaustiveRootIds: List<String>,
    val referenceIdentity: String, val fit: RootActionKernelReport,
    val previousFitMeanRegret: Double, val expandedFitMeanRegret: Double,
    val previousFitComparison: List<SavedRootRegretRow>, val previousFitGroups: List<SavedRootGroupRegret>,
    val interpretation: String = "Only development coverage expands. Reuse all original training targets and compute reference values only for added reconstructed, exhaustive DEVELOPMENT roots. Keep encoder, kernel, ridge, reference continuation/budgets and original validation comparisons fixed. Reconstruction refusals stop the extension; non-exhaustive menus are excluded explicitly, not assigned values. This adds roots mostly within existing game/seed groups; it is not independent confirmation. No search-prior wiring or gameplay is implied.")

internal fun compareRootKernelFits(old: List<SavedRootRegretRow>, new: List<SavedRootRegretRow>): List<SavedRootRegretRow> {
    require(old.isNotEmpty() && old.size == new.size)
    fun key(row: SavedRootRegretRow) = row.rootId to row.repetition
    require(old.map(::key).distinct().size == old.size && new.map(::key).distinct().size == new.size)
    val prior = old.associateBy(::key)
    return new.map { row ->
        val baseline = requireNotNull(prior[key(row)])
        require(baseline.seedGroupId == row.seedGroupId && baseline.baselineAction == row.baselineAction && baseline.baselineRegret == row.baselineRegret)
        require(baseline.candidateMinusBaselineByReferenceRepetition.size == row.candidateMinusBaselineByReferenceRepetition.size)
        val differences = row.candidateMinusBaselineByReferenceRepetition.zip(baseline.candidateMinusBaselineByReferenceRepetition) { a, b -> a - b }
        SavedRootRegretRow(row.rootId, row.seedGroupId, row.repetition, baseline.candidateAction, row.candidateAction,
            baseline.candidateRegret, row.candidateRegret, baseline.candidateRegret - row.candidateRegret, differences)
    }
}

internal class RootActionKernelCoverageRunner(private val repository: Path, private val registry: CardRegistry, private val deck: DeckManifest) {
    private fun previous(plan: RootActionKernelCoveragePlan): RootActionKernelReport {
        val path = Path.of(plan.previousFit.directory)
        val artifacts = ResearchRunArtifacts.loadAndVerify(path, plan.previousFit.researchRunIdentity)
        require(artifacts.artifacts.any { it.relativePath == "report.json" } && artifacts.artifacts.any { it.relativePath == "bindings.json" })
        val report = evidenceJson.decodeFromString<RootActionKernelReport>(Files.readString(path.resolve("report.json")))
        val bindings = evidenceJson.decodeFromString<ResearchRunBindings>(Files.readString(path.resolve("bindings.json")))
        require(report.researchRunIdentity == plan.previousFit.researchRunIdentity && bindings.identity == report.researchRunIdentity)
        require(bindings.protocol == "root-action-kernel-fit-v1" && report.plan.developmentExtension == null)
        return report
    }
    private fun reference(previous: RootActionKernelReport): VisibleV2ExperimentReport {
        val path = Path.of(previous.plan.referenceExperiment.directory)
        ResearchRunArtifacts.loadAndVerify(path, previous.plan.referenceExperiment.researchRunIdentity)
        return evidenceJson.decodeFromString<VisibleV2ExperimentReport>(Files.readString(path.resolve("report.json"))).also {
            require(it.researchRunIdentity == previous.plan.referenceExperiment.researchRunIdentity)
        }
    }
    private fun requireOverlap(original: RealGamePositionBankReport, added: RealGamePositionBankReport) {
        val old = original.roots.associateBy { it.rootId }
        added.roots.forEach { root -> old[root.rootId]?.let { require(it == root) { "Reconstructed overlap differs: ${root.rootId}" } } }
    }
    fun preflight(plan: RootActionKernelCoveragePlan, output: Path, workers: Int): PositionBankScreenReport {
        val old = previous(plan); val reference = reference(old)
        val originalBank = loadVerifiedRealGamePositionBank(Path.of(reference.plan.bankDirectory), reference.plan.bankIdentity)
        val bankPath = output.resolve("bank")
        val bank = RealGamePositionBankRunner(repository, registry, deck).run(originalBank.plan.copy(rootLimit = 2,
            maxRootsPerGame = plan.maxRootsPerGame, selectionPartition = RealGamePositionPartition.DEVELOPMENT), bankPath)
        requireOverlap(originalBank, bank)
        require(bank.complete && bank.roots.size == 2 && bank.roots.all { it.partition == RealGamePositionPartition.DEVELOPMENT })
        val ids = bank.roots.map { it.rootId }.sorted()
        val result = PositionBankScreenRunner(repository, registry, deck).run(PositionBankScreenPlan(bankDirectory = bankPath.toString(),
            expectedBankIdentity = bank.bankIdentity, partition = PositionBankScreenPartition.DEVELOPMENT,
            mode = PositionBankScreenMode.ACTION_CONDITIONAL, rootLimit = ids.size, repetitions = 1,
            policies = listOf(PositionBankScreenPolicy(reference.plan.reference.copy(simulations = 4), reference.plan.hand)),
            searchSeedDomain = "visible-v2-calibration-reference-v1", rootIds = ids), output.resolve("reference"), workers)
        require(result.valid && result.selectedRootIds == ids)
        visibleV2ReferenceTargets(bank, SavedRootPolicyInput(output.resolve("reference").toString(), result.researchRunIdentity,
            reference.plan.reference.id), PositionBankScreenPartition.DEVELOPMENT, reference.plan.hand)
        return result
    }
    fun run(plan: RootActionKernelCoveragePlan, output: Path, workers: Int): RootActionKernelCoverageReport {
        val source = ResearchRunProvenance.capture(repository, "third_party/argentum-engine").also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "root action kernel coverage")
        require(!Files.exists(destination))
        val old = previous(plan); val reference = reference(old)
        val originalBank = loadVerifiedRealGamePositionBank(Path.of(reference.plan.bankDirectory), reference.plan.bankIdentity)
        val oldRootIds = originalBank.roots.filter { it.partition == RealGamePositionPartition.DEVELOPMENT }.map { it.rootId }.toSet()
        require(oldRootIds.size == old.development.roots)
        val bindings = ResearchRunBindings(protocol = "root-action-kernel-coverage-v1", material = mapOf(
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "plan" to sha256(evidenceJson.encodeToString(RootActionKernelCoveragePlan.serializer(), plan)),
            "previous-fit-manifest" to researchSha256File(Path.of(plan.previousFit.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "worker-threads" to workers.toString(), "intervention" to "additional-development-roots-fixed-kernel-ridge-reference-and-validation-v1"))
        Files.createDirectories(destination)
        writeJsonAtomically(destination.resolve("plan.json"), plan); writeJsonAtomically(destination.resolve("bindings.json"), bindings)
        val bankPath = destination.resolve("bank")
        val bank = RealGamePositionBankRunner(repository, registry, deck).run(originalBank.plan.copy(rootLimit = plan.bankRootLimit,
            maxRootsPerGame = plan.maxRootsPerGame, selectionPartition = RealGamePositionPartition.DEVELOPMENT), bankPath)
        require(bank.complete) { "Coverage bank reconstruction must complete before reference compute" }
        requireOverlap(originalBank, bank)
        require(bank.roots.all { it.partition == RealGamePositionPartition.DEVELOPMENT })
        val overlap = bank.roots.filter { it.rootId in oldRootIds }.map { it.rootId }.sorted()
        val nonExhaustive = bank.roots.filter { it.rootId !in oldRootIds && !it.profileExpansionExhaustive }.map { it.rootId }.sorted()
        val ids = bank.roots.filter { it.rootId !in oldRootIds && it.profileExpansionExhaustive }.map { it.rootId }.sorted()
        require(ids.size >= oldRootIds.size) { "Coverage extension must add at least as many roots as the original training panel" }
        val additionalActions = bank.roots.filter { it.rootId in ids }.sumOf { it.reconstructedCandidates.size }
        require(additionalActions <= plan.maxAdditionalActions) { "Additional menus contain $additionalActions actions, exceeding the declared ${plan.maxAdditionalActions} action compute cap" }
        val referencePath = destination.resolve("additional-reference")
        val extraReference = PositionBankScreenRunner(repository, registry, deck).run(PositionBankScreenPlan(bankDirectory = bankPath.toString(),
            expectedBankIdentity = bank.bankIdentity, partition = PositionBankScreenPartition.DEVELOPMENT,
            mode = PositionBankScreenMode.ACTION_CONDITIONAL, rootLimit = ids.size, repetitions = reference.plan.repetitions,
            policies = listOf(PositionBankScreenPolicy(reference.plan.reference, reference.plan.hand)),
            searchSeedDomain = "visible-v2-calibration-reference-v1", rootIds = ids), referencePath, workers)
        require(extraReference.valid && extraReference.selectedRootIds == ids)
        val fit = runRootActionKernelExperiment(repository, old.plan.copy(developmentExtension = RootActionKernelExtension(
            bankPath.toString(), bank.bankIdentity, SavedRootPolicyInput(referencePath.toString(), extraReference.researchRunIdentity, reference.plan.reference.id))),
            destination.resolve("fit"))
        require(fit.development.roots == old.development.roots + ids.size)
        require(fit.validation.roots == old.validation.roots && fit.validation.seedGroups == old.validation.seedGroups && fit.validation.actions == old.validation.actions)
        val comparisons = compareRootKernelFits(old.rows, fit.rows)
        val report = RootActionKernelCoverageReport(bindings.identity, source, plan, bank.bankIdentity, bank.complete,
            bank.roots.size, bank.accounting.refusedRoots, ids, overlap, nonExhaustive, extraReference.researchRunIdentity,
            fit, old.candidateMeanRegret, fit.candidateMeanRegret, comparisons, savedRootGroupRegrets(comparisons))
        writeJsonAtomically(destination.resolve("report.json"), report)
        val artifacts = ResearchRunArtifacts(destination, bindings.identity)
        Files.walk(destination).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { artifacts.register(destination.relativize(it).toString()) } }
        artifacts.finalize(); ResearchRunArtifacts.loadAndVerify(destination, bindings.identity)
        return report
    }
}
