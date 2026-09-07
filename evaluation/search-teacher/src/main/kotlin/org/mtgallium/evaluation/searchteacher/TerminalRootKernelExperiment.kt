package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

/** Keeps production terminal targets distinct from diagnostic continuation interventions. */
internal fun requireProductionTerminalTarget(screen: PositionBankScreenPlan) {
    require(screen.mode == PositionBankScreenMode.TERMINAL_CONTINUATIONS && screen.policies.size == 1)
    val policy = screen.policies.single().search
    require(policy.rolloutHeuristicProbability == 1.0 && policy.rootCloningFit == null && policy.rootKernelRolloutFit == null)
    require(policy.fastRootKernelRolloutFit == null && policy.fastOpponentKernelRolloutFit == null && policy.attackRootKernelRolloutFit == null)
    require(policy.rootRolloutPolicy in listOf(null, SearchTeacherCalibrationRolloutPolicy.PRODUCTION_ARGENTUM))
    require(policy.opponentRolloutPolicy in listOf(null, SearchTeacherCalibrationRolloutPolicy.PRODUCTION_ARGENTUM))
}

/** A fixed target intervention; validation cannot choose the fit or its ridge. */
@Serializable
internal data class TerminalRootKernelExperimentPlan(
    val development: PositionBankScreenPlan,
    val validation: PositionBankScreenPlan,
    val baselineFit: RootKernelFitReference,
    val ridge: Double = .001,
    val maximumTotalContinuations: Int = 25000,
) {
    init {
        require(ridge.isFinite() && ridge > 0 && maximumTotalContinuations > 0)
        require(development.partition == PositionBankScreenPartition.DEVELOPMENT && validation.partition == PositionBankScreenPartition.VALIDATION)
        for (screen in listOf(development, validation)) {
            require(screen.mode == PositionBankScreenMode.TERMINAL_CONTINUATIONS && screen.rootIds.isNotEmpty())
            require(screen.policies.size == 1 && screen.rootLimit == screen.rootIds.size)
            requireProductionTerminalTarget(screen)
        }
        require(development.policies == validation.policies && development.repetitions == validation.repetitions)
        require(development.terminalContinuation == validation.terminalContinuation)
        require(development.searchSeedDomain == validation.searchSeedDomain)
    }
}

@Serializable
internal data class TerminalRootKernelTrainingInput(val bank: CloningComparisonInput, val terminal: SavedRootPolicyInput)

@Serializable
internal data class TerminalRootKernelFitPlan(val bank: CloningComparisonInput, val terminal: SavedRootPolicyInput, val ridge: Double,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val additional: List<TerminalRootKernelTrainingInput> = emptyList(),
) {
    init { require(ridge.isFinite() && ridge > 0) }
}

@Serializable
internal data class TerminalRootKernelFitReport(
    val researchRunIdentity: String, val source: ResearchRunProvenance, val plan: TerminalRootKernelFitPlan,
    val development: RootActionKernelFitMetrics, val accounting: TerminalRootScreenAccounting,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val additionalAccounting: List<TerminalRootScreenAccounting> = emptyList(),
    val interpretation: String = "Centered conditional terminal payoff after each forced root action and the declared continuation policies; not authoritative hidden truth, observed gameplay, search backups or optimal value. Fit only complete DEVELOPMENT roots with equal group/root/action weighting and fixed ridge.",
)

@Serializable
internal data class TerminalRootKernelExperimentReport(
    val researchRunIdentity: String, val source: ResearchRunProvenance, val plan: TerminalRootKernelExperimentPlan,
    val fit: RootKernelFitReference, val developmentIdentity: String, val validationIdentity: String,
    val developmentAccounting: TerminalRootScreenAccounting, val validationAccounting: TerminalRootScreenAccounting,
    val baselineValidation: RootActionKernelFitMetrics, val candidateValidation: RootActionKernelFitMetrics,
    val rows: List<SavedRootRegretRow>, val groups: List<SavedRootGroupRegret>,
    val interpretation: String = "One fixed kernel/ridge target intervention on the same development population. Compare frozen V2-target and terminal-target raw greedy actions using held-out terminal samples. Shared production continuation policies and finite belief samples condition these values; sample maxima are optimistic. Held-out groups were excluded from fitting but this is a method-adaptive panel, not deployed strength or pristine confirmation. One comparison row per root retains differences for each target repetition; repeated target estimates are not independent policy selections.",
)

/** Pool equal-sized completed repetitions, never completed fragments of a refused root. */
internal fun terminalActionMeans(rows: List<PositionBankScreenRow>, candidates: List<SemanticChoice>, repetitions: Int, policyId: String): List<Double> {
    require(repetitions > 0 && candidates.isNotEmpty() && candidates.map { it.signature }.distinct().size == candidates.size)
    require(rows.size == repetitions && rows.map { it.repetition }.sorted() == (0 until repetitions).toList())
    require(rows.map { it.rootId }.distinct().size == 1)
    require(rows.all { it.policyId == policyId && it.disposition == PositionBankScreenDisposition.TERMINAL_CONTINUATIONS })
    require(rows.all { it.terminalRootActions.map { a -> a.action } == candidates &&
        it.terminalRootActions.all { a -> a.disposition == TerminalRootActionDisposition.COMPLETE } })
    require(rows.flatMap { it.terminalRootActions }.map { it.requestedSamples }.distinct().size == 1)
    return candidates.indices.map { i -> rows.map { requireNotNull(it.terminalRootActions[i].meanTerminalPayoff) }.average() }
}

internal fun terminalRootTrainingData(bank: RealGamePositionBankReport, report: PositionBankScreenReport,
    partition: PositionBankScreenPartition): List<RootActionKernelTrainingRoot> {
    val accounting = terminalRootScreenAccounting(report, bank)
    require(report.valid && report.plan.partition == partition && report.plan.policies.size == 1)
    require(accounting.refusedRows == 0 && accounting.completedTerminalSamples == accounting.requestedContinuations)
    val roots = bank.roots.associateBy { it.rootId }
    return report.selectedRootIds.map { id ->
        val root = roots.getValue(id)
        require(root.partition.name == partition.name && root.profileExpansionExhaustive && root.reconstructedCandidates.size >= 2)
        RootActionKernelTrainingRoot(id, root.seedGroupId, rootActionKernelFeatures(root.information, root.reconstructedCandidates),
            terminalActionMeans(report.rows.filter { it.rootId == id }, root.reconstructedCandidates, report.plan.repetitions,
                report.plan.policies.single().search.id))
    }
}

internal fun rootKernelMetrics(model: CompiledRootActionKernel, roots: List<RootActionKernelTrainingRoot>): RootActionKernelFitMetrics {
    require(roots.isNotEmpty())
    val groups = roots.groupingBy { it.seedGroupId }.eachCount()
    var mse = 0.0; var regret = 0.0
    roots.forEach { root ->
        val scores = model.scores(root.features)
        val residuals = centeredActionResiduals(scores, root.actionMeans)
        mse += residuals.map { it * it }.average() / groups.size / groups.getValue(root.seedGroupId)
        regret += root.actionMeans.max() - root.actionMeans[scores.indices.maxBy { scores[it] }]
    }
    return RootActionKernelFitMetrics(roots.size, groups.size, roots.sumOf { it.features.size }, mse, regret / roots.size)
}

internal fun loadTerminalRootScreen(input: SavedRootPolicyInput, bank: RealGamePositionBankReport): PositionBankScreenReport {
    val path = Path.of(input.directory)
    val manifest = ResearchRunArtifacts.loadAndVerify(path, input.identity)
    require(manifest.artifacts.map { it.relativePath }.containsAll(listOf("report.json", "plan.json")))
    val report = evidenceJson.decodeFromString<PositionBankScreenReport>(Files.readString(path.resolve("report.json")))
    require(report.researchRunIdentity == input.identity && report.plan.policies.single().search.id == input.policyId)
    require(report.plan == evidenceJson.decodeFromString<PositionBankScreenPlan>(Files.readString(path.resolve("plan.json"))))
    terminalRootScreenAccounting(report, bank)
    return report
}

internal fun fitTerminalRootKernel(repository: Path, plan: TerminalRootKernelFitPlan, output: Path): TerminalRootKernelFitReport {
    val source = ResearchRunProvenance.capture(repository).also { it.requireReady() }
    require(!source.outerDirty && !source.engineDirty)
    val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "terminal root kernel fit")
    require(!Files.exists(destination))
    val bank = loadVerifiedRealGamePositionBank(Path.of(plan.bank.directory), plan.bank.researchRunIdentity)
    val screen = loadTerminalRootScreen(plan.terminal, bank)
    val additions = plan.additional.map { input ->
        val extraBank = loadVerifiedRealGamePositionBank(Path.of(input.bank.directory), input.bank.researchRunIdentity)
        val extra = loadTerminalRootScreen(input.terminal, extraBank)
        requireSameTerminalTarget(screen.plan, extra.plan)
        require(screen.sourceProvenance.argentum.revision == extra.sourceProvenance.argentum.revision)
        extraBank to extra
    }
    val development = combineTerminalTrainingRoots(listOf(terminalRootTrainingData(bank, screen, PositionBankScreenPartition.DEVELOPMENT)) +
        additions.map { (b, r) -> terminalRootTrainingData(b, r, PositionBankScreenPartition.DEVELOPMENT) })
    require(development.map { it.seedGroupId }.distinct().size >= 2)
    val bindings = ResearchRunBindings(protocol = "terminal-root-action-kernel-fit-v1", material = mapOf(
        "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
        "plan" to sha256(evidenceJson.encodeToString(TerminalRootKernelFitPlan.serializer(), plan)),
        "bank-manifest" to researchSha256File(Path.of(plan.bank.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        "terminal-manifest" to researchSha256File(Path.of(plan.terminal.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        "feature-schema" to NEURAL_BC_FEATURE_SCHEMA,
        "kernel" to "l2-state-l2-candidate-root-centered-candidate-plus-state-tensor-candidate-v1",
        "target" to "conditional-terminal-payoff-equal-repetition-mean-root-centered-v1",
        "fit" to "equal-group-root-action-centered-mse-plus-ridge-kernel-norm-cholesky-v1",
        "scorer" to COMPILED_ROOT_ACTION_KERNEL_ID,
    ) + plan.additional.flatMapIndexed { index, input -> listOf(
        "additional-bank-$index-manifest" to researchSha256File(Path.of(input.bank.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        "additional-terminal-$index-manifest" to researchSha256File(Path.of(input.terminal.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
    ) }.toMap())
    Files.createDirectories(destination)
    writeJsonAtomically(destination.resolve("bindings.json"), bindings); writeJsonAtomically(destination.resolve("plan.json"), plan)
    val model = fitRootActionKernel(development, plan.ridge)
    writeJsonAtomically(destination.resolve("model.json"), model)
    val restored = evidenceJson.decodeFromString<RootActionKernelModel>(Files.readString(destination.resolve("model.json")))
    require(model == restored)
    val scorer = CompiledRootActionKernel(restored)
    writeJsonAtomically(destination.resolve("development.json"), development)
    val report = TerminalRootKernelFitReport(bindings.identity, source, plan, rootKernelMetrics(scorer, development), terminalRootScreenAccounting(screen, bank),
        additions.map { (b, r) -> terminalRootScreenAccounting(r, b) })
    writeJsonAtomically(destination.resolve("report.json"), report)
    ResearchRunArtifacts(destination, bindings.identity).also { artifacts ->
        listOf("bindings.json", "plan.json", "model.json", "development.json", "report.json").forEach(artifacts::register); artifacts.finalize()
    }
    ResearchRunArtifacts.loadAndVerify(destination, bindings.identity)
    return report
}

internal class TerminalRootKernelExperimentRunner(private val repository: Path, private val registry: CardRegistry, private val deck: DeckManifest) {
    private fun selected(plan: PositionBankScreenPlan, bank: RealGamePositionBankReport): List<RealGamePositionBankRoot> {
        val roots = selectPositionScreenRoots(plan, bank.roots.filter { it.partition.name == plan.partition.name }.sortedBy { it.rootId })
        require(roots.map { it.rootId } == plan.rootIds && roots.all { it.profileExpansionExhaustive && it.reconstructedCandidates.size >= 2 })
        return roots
    }

    /** Metadata and safe features only: no validation target may enter this pre-fit check. */
    private fun prepare(plan: TerminalRootKernelExperimentPlan): Pair<RealGamePositionBankReport, RealGamePositionBankReport> {
        val dev = loadVerifiedRealGamePositionBank(Path.of(plan.development.bankDirectory), plan.development.expectedBankIdentity)
        val validation = loadVerifiedRealGamePositionBank(Path.of(plan.validation.bankDirectory), plan.validation.expectedBankIdentity)
        val devRoots = selected(plan.development, dev); val valRoots = selected(plan.validation, validation)
        require(devRoots.map { it.seedGroupId }.distinct().size >= 2 && valRoots.map { it.seedGroupId }.distinct().size >= 2)
        require(devRoots.none { d -> valRoots.any { it.seedGroupId == d.seedGroupId || it.rootId == d.rootId } })
        val total = listOf(plan.development to devRoots, plan.validation to valRoots).sumOf { (screen, roots) ->
            terminalRootWorkload(requireNotNull(screen.terminalContinuation), roots.map { it.reconstructedCandidates.size }, screen.repetitions, 1).toLong()
        }
        require(total <= plan.maximumTotalContinuations)
        // The baseline must be the same fixed-ridge/feature/population fit with V2 targets.
        plan.baselineFit.loadFrozenModel()
        val path = Path.of(plan.baselineFit.directory)
        val bindings = evidenceJson.decodeFromString<ResearchRunBindings>(Files.readString(path.resolve("bindings.json")))
        require(bindings.protocol == "root-action-kernel-fit-v1")
        val baseline = evidenceJson.decodeFromString<RootActionKernelReport>(Files.readString(path.resolve("report.json")))
        require(baseline.plan.ridge == plan.ridge)
        require(ResearchRunArtifacts.loadAndVerify(path, plan.baselineFit.researchRunIdentity).artifacts.any { it.relativePath == "development.json" })
        val old = evidenceJson.decodeFromString<List<RootActionKernelTrainingRoot>>(Files.readString(path.resolve("development.json")))
        require(old.map { it.rootId }.toSet() == devRoots.map { it.rootId }.toSet())
        val current = devRoots.associateBy { it.rootId }
        old.forEach { root ->
            val r = current.getValue(root.rootId)
            require(root.seedGroupId == r.seedGroupId && root.features == rootActionKernelFeatures(r.information, r.reconstructedCandidates))
        }
        return dev to validation
    }

    fun preflight(plan: TerminalRootKernelExperimentPlan, output: Path, workers: Int): PositionBankScreenReport {
        val (bank, _) = prepare(plan)
        // Exercise the largest menu plus a different family, without selecting on outcomes.
        val roots = selected(plan.development, bank)
        val largest = roots.maxWith(compareBy<RealGamePositionBankRoot> { it.reconstructedCandidates.size }.thenByDescending { it.rootId })
        val other = roots.filter { it.decisionFamily != largest.decisionFamily }.minBy { it.rootId }
        val ids = listOf(largest.rootId, other.rootId).sorted()
        val screenPlan = plan.development.copy(rootLimit = ids.size, rootIds = ids, repetitions = 1,
            terminalContinuation = requireNotNull(plan.development.terminalContinuation).copy(samplesPerAction = 2))
        val report = PositionBankScreenRunner(repository, registry, deck).run(screenPlan, output, workers)
        terminalRootTrainingData(bank, report, PositionBankScreenPartition.DEVELOPMENT)
        return report
    }

    fun run(plan: TerminalRootKernelExperimentPlan, output: Path, workers: Int): TerminalRootKernelExperimentReport {
        val source = ResearchRunProvenance.capture(repository).also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "terminal root kernel experiment")
        require(!Files.exists(destination))
        val (developmentBank, validationBank) = prepare(plan)
        val bindings = ResearchRunBindings(protocol = "terminal-root-kernel-experiment-v1", material = mapOf(
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "plan" to sha256(evidenceJson.encodeToString(TerminalRootKernelExperimentPlan.serializer(), plan)),
            "development-bank-manifest" to researchSha256File(Path.of(plan.development.bankDirectory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "validation-bank-manifest" to researchSha256File(Path.of(plan.validation.bankDirectory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "baseline-fit-manifest" to plan.baselineFit.manifestSha256,
            "scorer" to COMPILED_ROOT_ACTION_KERNEL_ID, "selection" to "raw-score-first-menu-argmax-v1",
            "worker-threads" to workers.toString(),
        ))
        Files.createDirectories(destination)
        writeJsonAtomically(destination.resolve("bindings.json"), bindings); writeJsonAtomically(destination.resolve("plan.json"), plan)
        val devPath = destination.resolve("development-terminal")
        val dev = PositionBankScreenRunner(repository, registry, deck).run(plan.development, devPath, workers)
        terminalRootTrainingData(developmentBank, dev, PositionBankScreenPartition.DEVELOPMENT)
        val fitPath = destination.resolve("fit")
        val fit = fitTerminalRootKernel(repository, TerminalRootKernelFitPlan(
            CloningComparisonInput(plan.development.bankDirectory, plan.development.expectedBankIdentity),
            SavedRootPolicyInput(devPath.toString(), dev.researchRunIdentity, plan.development.policies.single().search.id), plan.ridge), fitPath)
        val fitReference = RootKernelFitReference(fitPath.toString(), fit.researchRunIdentity, researchSha256File(fitPath.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
        val candidate = CompiledRootActionKernel(fitReference.loadFrozenModel().model)
        val baseline = CompiledRootActionKernel(plan.baselineFit.loadFrozenModel().model)
        // Freeze and authenticate the model before generating any held-out target.
        val validation = PositionBankScreenRunner(repository, registry, deck).run(plan.validation, destination.resolve("validation-terminal"), workers)
        val heldOut = terminalRootTrainingData(validationBank, validation, PositionBankScreenPartition.VALIDATION)
        val roots = validationBank.roots.associateBy { it.rootId }
        val rows = heldOut.flatMap { target ->
            val menu = roots.getValue(target.rootId).reconstructedCandidates
            val old = baseline.scores(target.features); val new = candidate.scores(target.features)
            val oldAction = menu[old.indices.maxBy { old[it] }].signature
            val newAction = menu[new.indices.maxBy { new[it] }].signature
            val references = (0 until plan.validation.repetitions).map { rep ->
                validation.rows.single { it.rootId == target.rootId && it.repetition == rep }.terminalRootActions
                    .associate { it.action.signature to requireNotNull(it.meanTerminalPayoff) }
            }
            val (oldRegret, newRegret, delta) = savedRootReferenceComparison(references, oldAction, newAction)
            listOf(SavedRootRegretRow(target.rootId, target.seedGroupId, 0, oldAction, newAction, oldRegret, newRegret, delta.average(), delta))
        }
        val report = TerminalRootKernelExperimentReport(bindings.identity, source, plan, fitReference, dev.researchRunIdentity, validation.researchRunIdentity,
            terminalRootScreenAccounting(dev, developmentBank), terminalRootScreenAccounting(validation, validationBank),
            rootKernelMetrics(baseline, heldOut), rootKernelMetrics(candidate, heldOut), rows, savedRootGroupRegrets(rows))
        writeJsonAtomically(destination.resolve("report.json"), report)
        ResearchRunArtifacts(destination, bindings.identity).also { artifacts ->
            Files.walk(destination).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { artifacts.register(destination.relativize(it).toString()) } }
            artifacts.finalize()
        }
        ResearchRunArtifacts.loadAndVerify(destination, bindings.identity)
        return report
    }
}
