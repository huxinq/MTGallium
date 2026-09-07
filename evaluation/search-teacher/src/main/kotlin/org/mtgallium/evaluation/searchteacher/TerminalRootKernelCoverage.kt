package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

/** Population may change; continuation, sampling and target meaning may not. */
internal fun requireSameTerminalTarget(first: PositionBankScreenPlan, second: PositionBankScreenPlan) {
    require(first.mode == PositionBankScreenMode.TERMINAL_CONTINUATIONS && second.mode == first.mode)
    require(first.policies == second.policies && first.policies.size == 1)
    require(first.repetitions == second.repetitions && first.searchSeedDomain == second.searchSeedDomain)
    require(first.terminalContinuation == second.terminalContinuation)
}

internal fun combineTerminalTrainingRoots(parts: List<List<RootActionKernelTrainingRoot>>): List<RootActionKernelTrainingRoot> {
    require(parts.isNotEmpty() && parts.all { it.isNotEmpty() })
    val roots = parts.flatten()
    require(roots.map { it.rootId }.distinct().size == roots.size) { "Overlapping training roots would change weighting" }
    return roots.sortedBy { it.rootId }
}

@Serializable
internal data class TerminalRootKernelCoveragePlan(
    val baseline: RootKernelFitReference,
    val added: PositionBankScreenPlan,
    val validation: TerminalRootKernelTrainingInput,
    val maximumNewContinuations: Int = 25000,
) {
    init {
        require(maximumNewContinuations > 0)
        require(added.partition == PositionBankScreenPartition.DEVELOPMENT && added.mode == PositionBankScreenMode.TERMINAL_CONTINUATIONS)
        require(added.rootIds.isNotEmpty() && added.rootIds.size == added.rootLimit && added.policies.size == 1)
    }
}

@Serializable
internal data class TerminalRootKernelCoverageReport(
    val researchRunIdentity: String, val source: ResearchRunProvenance, val plan: TerminalRootKernelCoveragePlan,
    val fit: RootKernelFitReference, val addedIdentity: String, val addedAccounting: TerminalRootScreenAccounting,
    val baselineValidation: RootActionKernelFitMetrics, val candidateValidation: RootActionKernelFitMetrics,
    val rows: List<SavedRootRegretRow>, val groups: List<SavedRootGroupRegret>,
    val interpretation: String = "Development coverage intervention with reused original terminal labels, fixed continuation, features, kernel and ridge. Frozen raw-greedy models are compared on reused method-adaptive validation targets after fitting. Conditional sampled payoff and group/root regret are not deployed strength; adding roots within existing groups does not add independent games or seed groups.",
)

internal class TerminalRootKernelCoverageRunner(private val repository: Path, private val registry: CardRegistry, private val deck: DeckManifest) {
    private data class Prepared(val baselineReport: TerminalRootKernelFitReport, val oldRoots: List<RootActionKernelTrainingRoot>,
        val addedBank: RealGamePositionBankReport, val addedRoots: List<RealGamePositionBankRoot>, val validationBank: RealGamePositionBankReport)

    private fun prepare(plan: TerminalRootKernelCoveragePlan): Prepared {
        plan.baseline.loadFrozenModel()
        val path = Path.of(plan.baseline.directory)
        val binding = evidenceJson.decodeFromString<ResearchRunBindings>(Files.readString(path.resolve("bindings.json")))
        require(binding.protocol == "terminal-root-action-kernel-fit-v1")
        val old = evidenceJson.decodeFromString<TerminalRootKernelFitReport>(Files.readString(path.resolve("report.json")))
        require(old.plan.additional.isEmpty()) { "This comparison expands the original terminal fit once" }
        val oldBank = loadVerifiedRealGamePositionBank(Path.of(old.plan.bank.directory), old.plan.bank.researchRunIdentity)
        val oldScreen = loadTerminalRootScreen(old.plan.terminal, oldBank)
        require(ResearchRunProvenance.capture(repository).checkedOutEngineCommit == oldScreen.sourceProvenance.argentum.revision)
        val oldRoots = terminalRootTrainingData(oldBank, oldScreen, PositionBankScreenPartition.DEVELOPMENT)
        val manifest = ResearchRunArtifacts.loadAndVerify(path, plan.baseline.researchRunIdentity)
        require(manifest.artifacts.any { it.relativePath == "development.json" })
        require(oldRoots == evidenceJson.decodeFromString<List<RootActionKernelTrainingRoot>>(Files.readString(path.resolve("development.json"))))
        requireSameTerminalTarget(oldScreen.plan, plan.added)
        val bank = loadVerifiedRealGamePositionBank(Path.of(plan.added.bankDirectory), plan.added.expectedBankIdentity)
        require(bank.sourceProvenance.argentum.revision == oldScreen.sourceProvenance.argentum.revision)
        val roots = selectPositionScreenRoots(plan.added, bank.roots.filter { it.partition.name == plan.added.partition.name }.sortedBy { it.rootId })
        require(roots.map { it.rootId } == plan.added.rootIds)
        require(roots.all { it.profileExpansionExhaustive && it.reconstructedCandidates.size >= 2 })
        require(roots.none { r -> oldRoots.any { it.rootId == r.rootId } })
        require(terminalRootWorkload(requireNotNull(plan.added.terminalContinuation), roots.map { it.reconstructedCandidates.size }, plan.added.repetitions, 1) <= plan.maximumNewContinuations)
        // Read only validation bank metadata here. The held-out target artifact is loaded after the new fit is frozen.
        val validation = loadVerifiedRealGamePositionBank(Path.of(plan.validation.bank.directory), plan.validation.bank.researchRunIdentity)
        val groups = oldRoots.map { it.seedGroupId }.toSet() + roots.map { it.seedGroupId }
        val validationRoots = validation.roots.filter { it.partition == RealGamePositionPartition.VALIDATION }
        require(validationRoots.isNotEmpty() && validationRoots.none { it.seedGroupId in groups })
        return Prepared(old, oldRoots, bank, roots, validation)
    }

    fun preflight(plan: TerminalRootKernelCoveragePlan, output: Path, workers: Int): PositionBankScreenReport {
        val p = prepare(plan)
        val largest = p.addedRoots.maxWith(compareBy<RealGamePositionBankRoot> { it.reconstructedCandidates.size }.thenByDescending { it.rootId })
        val other = p.addedRoots.filter { it.rootId != largest.rootId }.minBy { it.rootId }
        val ids = listOf(largest.rootId, other.rootId).sorted()
        val screen = plan.added.copy(rootLimit = 2, rootIds = ids, repetitions = 1,
            terminalContinuation = requireNotNull(plan.added.terminalContinuation).copy(samplesPerAction = 2))
        return PositionBankScreenRunner(repository, registry, deck).run(screen, output, workers).also {
            terminalRootTrainingData(p.addedBank, it, PositionBankScreenPartition.DEVELOPMENT)
        }
    }

    fun run(plan: TerminalRootKernelCoveragePlan, output: Path, workers: Int): TerminalRootKernelCoverageReport {
        val source = ResearchRunProvenance.capture(repository).also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "terminal kernel coverage")
        require(!Files.exists(destination))
        val p = prepare(plan)
        val bindings = ResearchRunBindings(protocol = "terminal-root-kernel-coverage-v1", material = mapOf(
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "plan" to sha256(evidenceJson.encodeToString(TerminalRootKernelCoveragePlan.serializer(), plan)),
            "baseline-manifest" to plan.baseline.manifestSha256,
            "added-bank-manifest" to researchSha256File(Path.of(plan.added.bankDirectory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "validation-bank-manifest" to researchSha256File(Path.of(plan.validation.bank.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "selection" to "raw-score-first-menu-argmax-v1", "scorer" to COMPILED_ROOT_ACTION_KERNEL_ID, "workers" to workers.toString(),
        ))
        Files.createDirectories(destination)
        writeJsonAtomically(destination.resolve("bindings.json"), bindings); writeJsonAtomically(destination.resolve("plan.json"), plan)
        val addedPath = destination.resolve("added-terminal")
        val added = PositionBankScreenRunner(repository, registry, deck).run(plan.added, addedPath, workers)
        terminalRootTrainingData(p.addedBank, added, PositionBankScreenPartition.DEVELOPMENT)
        val fitPath = destination.resolve("fit")
        val fit = fitTerminalRootKernel(repository, p.baselineReport.plan.copy(additional = listOf(TerminalRootKernelTrainingInput(
            CloningComparisonInput(plan.added.bankDirectory, plan.added.expectedBankIdentity),
            SavedRootPolicyInput(addedPath.toString(), added.researchRunIdentity, plan.added.policies.single().search.id)))), fitPath)
        val reference = RootKernelFitReference(fitPath.toString(), fit.researchRunIdentity, researchSha256File(fitPath.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
        val candidate = CompiledRootActionKernel(reference.loadFrozenModel().model)
        val baseline = CompiledRootActionKernel(plan.baseline.loadFrozenModel().model)
        val validation = loadTerminalRootScreen(plan.validation.terminal, p.validationBank)
        requireSameTerminalTarget(plan.added, validation.plan)
        require(validation.sourceProvenance.argentum.revision == added.sourceProvenance.argentum.revision)
        val targets = terminalRootTrainingData(p.validationBank, validation, PositionBankScreenPartition.VALIDATION)
        val roots = p.validationBank.roots.associateBy { it.rootId }
        val rows = targets.map { target ->
            val menu = roots.getValue(target.rootId).reconstructedCandidates
            val old = baseline.scores(target.features); val new = candidate.scores(target.features)
            val oldAction = menu[old.indices.maxBy { old[it] }].signature; val newAction = menu[new.indices.maxBy { new[it] }].signature
            val values = (0 until validation.plan.repetitions).map { rep -> validation.rows.single { it.rootId == target.rootId && it.repetition == rep }
                .terminalRootActions.associate { it.action.signature to requireNotNull(it.meanTerminalPayoff) } }
            val (a, b, delta) = savedRootReferenceComparison(values, oldAction, newAction)
            SavedRootRegretRow(target.rootId, target.seedGroupId, 0, oldAction, newAction, a, b, delta.average(), delta)
        }
        val report = TerminalRootKernelCoverageReport(bindings.identity, source, plan, reference, added.researchRunIdentity,
            terminalRootScreenAccounting(added, p.addedBank), rootKernelMetrics(baseline, targets), rootKernelMetrics(candidate, targets), rows, savedRootGroupRegrets(rows))
        writeJsonAtomically(destination.resolve("report.json"), report)
        ResearchRunArtifacts(destination, bindings.identity).also { artifacts ->
            Files.walk(destination).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { artifacts.register(destination.relativize(it).toString()) } }; artifacts.finalize()
        }
        ResearchRunArtifacts.loadAndVerify(destination, bindings.identity)
        return report
    }
}
