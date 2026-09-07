package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal data class TerminalStudyData(val plan: PositionBankScreenPlan, val retained: SavedRootPolicyInput? = null)

@Serializable
internal data class TerminalStudyGate(val minimumPositiveGroups: Int) {
    init { require(minimumPositiveGroups > 0) }
}

@Serializable
internal data class TerminalKernelStudyPlan(
    val schemaVersion: Int = 1,
    val campaignId: String,
    val campaignDirectory: String,
    val build: ResearchBuildReference,
    val baseline: RootKernelFitReference,
    val development: TerminalStudyData,
    val validation: TerminalStudyData,
    val control: PositionBankScreenPlan,
    val gate: TerminalStudyGate,
    val workers: Int = 2,
    val maximumNewContinuations: Int = 25000,
    val maximumProjectedCollectionSeconds: Double = 3600.0,
    val requireCastingContext: Boolean = true,
    val retainedFit: RootKernelFitReference? = null,
    val retainedControl: CloningComparisonInput? = null,
) {
    init {
        require(schemaVersion == 1 && workers > 0 && maximumNewContinuations > 0)
        require(Path.of(campaignDirectory).isAbsolute)
        require(maximumProjectedCollectionSeconds.isFinite() && maximumProjectedCollectionSeconds > 0)
        require(development.plan.partition == PositionBankScreenPartition.DEVELOPMENT && validation.plan.partition == PositionBankScreenPartition.VALIDATION)
        requireSameTerminalTarget(development.plan, validation.plan)
        for (screen in listOf(development.plan, validation.plan)) {
            require(screen.rootIds.isNotEmpty() && screen.rootLimit == screen.rootIds.size)
            requireProductionTerminalTarget(screen)
        }
        require(control.mode == PositionBankScreenMode.ROOT_ROLLOUT_SELECTION && control.partition == PositionBankScreenPartition.VALIDATION)
        require(control.bankDirectory == validation.plan.bankDirectory && control.expectedBankIdentity == validation.plan.expectedBankIdentity)
        require(control.rootIds == validation.plan.rootIds && control.repetitions == 1 && control.policies.size == 1)
        val policy = control.policies.single().search
        require(policy.rootKernelRolloutFit == null && policy.rootCloningFit == null && policy.rolloutHeuristicProbability == 1.0)
        require(policy.rootRolloutPolicy in listOf(null, SearchTeacherCalibrationRolloutPolicy.PRODUCTION_ARGENTUM))
        require(retainedFit == null || development.retained != null) { "Retained fit requires its exact retained development targets" }
    }
}

@Serializable
internal data class TerminalStudyComparison(
    val rows: List<SavedRootRegretRow>, val groups: List<SavedRootGroupRegret>,
    val equalGroupMeanDifference: Double, val equalGroupMeanByTargetRepetition: List<Double>,
    val positiveGroups: Int, val negativeGroups: Int, val tiedGroups: Int,
)

/** Repetitions and uneven root counts never give a seed group extra weight. */
internal fun terminalStudyComparison(rows: List<SavedRootRegretRow>): TerminalStudyComparison {
    require(rows.isNotEmpty() && rows.all { it.repetition == 0 } && rows.map { it.rootId }.distinct().size == rows.size)
    val repetitions = rows.first().candidateMinusBaselineByReferenceRepetition.size
    require(repetitions > 0 && rows.all { it.candidateMinusBaselineByReferenceRepetition.size == repetitions })
    val groups = savedRootGroupRegrets(rows)
    val byGroup = rows.groupBy { it.seedGroupId }.values
    return TerminalStudyComparison(rows, groups, groups.map { it.candidateMinusBaseline }.average(),
        (0 until repetitions).map { rep -> byGroup.map { group -> group.map { it.candidateMinusBaselineByReferenceRepetition[rep] }.average() }.average() },
        groups.count { it.candidateMinusBaseline > 0 }, groups.count { it.candidateMinusBaseline < 0 }, groups.count { it.candidateMinusBaseline == 0.0 })
}

@Serializable
internal data class TerminalStudyGateResult(val checks: Map<String, Boolean>, val passed: Boolean) {
    init { require(checks.isNotEmpty() && passed == checks.values.all { it }) }
}

internal fun terminalStudyGate(gate: TerminalStudyGate, old: TerminalStudyComparison, production: TerminalStudyComparison): TerminalStudyGateResult {
    require(old.rows.map { it.rootId } == production.rows.map { it.rootId })
    require(gate.minimumPositiveGroups <= old.groups.size)
    val checks = linkedMapOf("positive-old-model-mean" to (old.equalGroupMeanDifference > 0),
        "positive-all-old-model-target-repetitions" to old.equalGroupMeanByTargetRepetition.all { it > 0 },
        "minimum-positive-groups" to (old.positiveGroups >= gate.minimumPositiveGroups),
        "nonnegative-production-mean" to (production.equalGroupMeanDifference >= 0),
        "nonnegative-all-production-target-repetitions" to production.equalGroupMeanByTargetRepetition.all { it >= 0 })
    return TerminalStudyGateResult(checks, checks.values.all { it })
}

@Serializable
internal data class TerminalKernelStudyReport(
    val identity: String, val source: ResearchRunProvenance, val plan: TerminalKernelStudyPlan,
    val fit: RootKernelFitReference,
    val development: SavedRootPolicyInput, val validation: SavedRootPolicyInput, val control: CloningComparisonInput,
    val trainingRoots: Int, val trainingGroups: Int, val validationRoots: Int, val validationGroups: Int,
    val baselineComparison: TerminalStudyComparison, val productionComparison: TerminalStudyComparison,
    val gate: TerminalStudyGateResult,
    val baselineMetrics: RootActionKernelFitMetrics, val candidateMetrics: RootActionKernelFitMetrics,
    val developmentAccounting: TerminalRootScreenAccounting, val validationAccounting: TerminalRootScreenAccounting,
    val costs: ResearchInvocationCosts,
    val interpretation: String = "One fixed terminal-kernel development expansion with a frozen model before validation-target access. Conditional production-continuation payoffs and exploratory group gates are not observed gameplay strength, optimal action values or statistical confirmation. Campaign records report known data use only. Historical reused targets and fits retain their original source identities and costs.",
)

/** Public composition of the recurring study, not an arbitrary experiment graph. */
internal class TerminalKernelStudyRunner(private val repository: Path) {
    fun run(plan: TerminalKernelStudyPlan, output: Path, deckPath: Path): TerminalKernelStudyReport {
        val store = EvidenceStore(repository)
        val destination = store.requireDiagnosticOutput(output, "terminal kernel study")
        require(!Files.exists(destination)) { "Choose a fresh study output; reuse completed stages through explicit retained references" }
        val costs = ResearchCostRecorder(destination.resolve("costs.json"))
        val source = ResearchRunProvenance.capture(repository).also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        val runtime = researchPreflightRuntime()
        verifyResearchBuild(plan.build, source, runtime)
        val retainedDirectories = listOf(plan.development.plan.bankDirectory, plan.validation.plan.bankDirectory, plan.baseline.directory, plan.build.directory) +
            listOfNotNull(plan.development.retained?.directory, plan.validation.retained?.directory, plan.retainedFit?.directory, plan.retainedControl?.directory)
        val inputManifestHashes = retainedDirectories.distinct().sorted().associateWith { researchSha256File(Path.of(it).resolve(ResearchRunArtifacts.MANIFEST_FILE)) }
        val bindings = ResearchRunBindings(protocol = "terminal-kernel-study-v1", material = mapOf(
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "plan" to sha256(evidenceJson.encodeToString(TerminalKernelStudyPlan.serializer(), plan)),
            "runtime" to preflightRuntimeHash(runtime), "deck" to researchSha256File(deckPath),
            "build-manifest" to plan.build.manifestSha256,
            "input-manifests" to sha256(inputManifestHashes.entries.joinToString("\n") { "${it.key}=${it.value}" }),
        ))
        val inputs = listOf(Path.of(plan.development.plan.bankDirectory), Path.of(plan.validation.plan.bankDirectory),
            Path.of(plan.baseline.directory), Path.of(plan.build.directory), Path.of(plan.campaignDirectory), deckPath) +
            listOfNotNull(plan.development.retained?.directory, plan.validation.retained?.directory, plan.retainedFit?.directory, plan.retainedControl?.directory).map(Path::of)
        inputs.forEach { p -> require(!destination.startsWith(p) && !p.startsWith(destination)) { "Study input/output overlap: $p" } }
        Files.createDirectories(destination)
        writeJsonAtomically(destination.resolve("bindings.json"), bindings)
        writeJsonAtomically(destination.resolve("plan.json"), plan)
        writeJsonAtomically(destination.resolve("runtime.json"), runtime)
        writeJsonAtomically(destination.resolve("input-manifests.json"), inputManifestHashes)
        try {
            return execute(plan, destination, deckPath, source, bindings, costs).also { report ->
                writeJsonAtomically(destination.resolve("report.json"), report)
                finalizeStudyArtifacts(destination, bindings.identity)
            }
        } catch (failure: Exception) {
            costs.persist()
            writeTextAtomically(destination.resolve("failure.txt"), "${failure.javaClass.simpleName}: ${failure.message}\n")
            // Failed top-level studies have no successful final manifest. Valid child manifests survive.
            throw failure
        }
    }

    private fun execute(plan: TerminalKernelStudyPlan, output: Path, deckPath: Path, source: ResearchRunProvenance,
        bindings: ResearchRunBindings, costs: ResearchCostRecorder): TerminalKernelStudyReport {
        val bankPair = costs.measure("authenticate-inputs") {
            val bank = loadVerifiedRealGamePositionBank(Path.of(plan.development.plan.bankDirectory), plan.development.plan.expectedBankIdentity)
            val validation = if (plan.validation.plan.bankDirectory == plan.development.plan.bankDirectory && plan.validation.plan.expectedBankIdentity == bank.bankIdentity) bank
                else loadVerifiedRealGamePositionBank(Path.of(plan.validation.plan.bankDirectory), plan.validation.plan.expectedBankIdentity)
            bank to validation
        }
        val (bank, validationBank) = bankPair
        fun selected(p: PositionBankScreenPlan, b: RealGamePositionBankReport) = selectPositionScreenRoots(p,
            b.roots.filter { it.partition.name == p.partition.name }.sortedBy { it.rootId }).also { roots ->
            require(roots.map { it.rootId } == p.rootIds && roots.all { it.profileExpansionExhaustive && it.reconstructedCandidates.size >= 2 })
            require(!plan.requireCastingContext || roots.all { root -> root.reconstructedCandidates.any { it.operationFamily == SemanticOperationFamily.CAST_SPELL } })
        }
        val devRoots = selected(plan.development.plan, bank); val valRoots = selected(plan.validation.plan, validationBank)
        val baselinePath = Path.of(plan.baseline.directory)
        require(ResearchRunArtifacts.loadAndVerify(baselinePath, plan.baseline.researchRunIdentity).artifacts.any { it.relativePath == "development.json" })
        val oldPlan = evidenceJson.decodeFromString<TerminalRootKernelFitPlan>(Files.readString(baselinePath.resolve("plan.json")))
        val oldPopulations = (listOf(TerminalRootKernelTrainingInput(oldPlan.bank, oldPlan.terminal)) + oldPlan.additional).map { input ->
            val b = loadVerifiedRealGamePositionBank(Path.of(input.bank.directory), input.bank.researchRunIdentity)
            val reference = CloningComparisonInput(input.terminal.directory, input.terminal.identity)
            CampaignPopulationInput(input.bank, campaignScreenRoots(reference, b).map { it.rootId }.sorted(), reference)
        }
        val newWork = listOf(plan.development to devRoots, plan.validation to valRoots).sumOf { (data, roots) ->
            val work = terminalRootWorkload(requireNotNull(data.plan.terminalContinuation), roots.map { it.reconstructedCandidates.size }, data.plan.repetitions, 1)
            if (data.retained == null) work.toLong() else 0L
        }
        val pilotRoots = terminalStudyPilotRoots(devRoots)
        val pilotWork = devRoots.filter { it.rootId in pilotRoots }.sumOf { it.reconstructedCandidates.size.toLong() * 2 }
        require(newWork + pilotWork <= plan.maximumNewContinuations)
        val campaign = CampaignDataRegistry(repository, Path.of(plan.campaignDirectory), plan.campaignId)
        val before = campaign.snapshot()
        writeJsonAtomically(output.resolve("campaign-before.json"), before)
        val priorValidation = before.groups.filter { it.seedGroupId in valRoots.map { r -> r.seedGroupId }.toSet() }
        writeJsonAtomically(output.resolve("prior-validation-use.json"), priorValidation)
        costs.measure("register-population-use") {
            fun use(role: CampaignDataRole, populations: List<CampaignPopulationInput>, purpose: String) = campaign.record(
                CampaignDataUsePlan(campaignId = plan.campaignId, studyIdentity = bindings.identity, role = role,
                    timing = CampaignDataTiming.PROSPECTIVE_RESERVATION, populations = populations, purpose = purpose))
            use(CampaignDataRole.TRAINING, oldPopulations + CampaignPopulationInput(CloningComparisonInput(plan.development.plan.bankDirectory, bank.bankIdentity), devRoots.map { it.rootId }), "Fixed baseline training data and declared development expansion; includes pilot access")
            use(CampaignDataRole.METHOD_SELECTION, listOf(CampaignPopulationInput(CloningComparisonInput(plan.validation.plan.bankDirectory, validationBank.bankIdentity), valRoots.map { it.rootId })), "Fixed exploratory gate after fit freeze; prior registered uses remain visible")
        }
        // Reservation is durable before decoding any fitted values or terminal targets, even if admission later fails.
        plan.baseline.loadFrozenModel()
        val oldReport = evidenceJson.decodeFromString<TerminalRootKernelFitReport>(Files.readString(baselinePath.resolve("report.json")))
        require(oldReport.plan == oldPlan)
        val oldData = evidenceJson.decodeFromString<List<RootActionKernelTrainingRoot>>(Files.readString(baselinePath.resolve("development.json")))
        require(oldData.size == oldReport.development.roots && oldData.map { it.rootId }.distinct().size == oldData.size)
        val oldInput = loadVerifiedRealGamePositionBank(Path.of(oldReport.plan.bank.directory), oldReport.plan.bank.researchRunIdentity)
        val originalTarget = loadTerminalRootScreen(oldReport.plan.terminal, oldInput)
        requireSameTerminalTarget(originalTarget.plan, plan.development.plan)
        require(originalTarget.sourceProvenance.argentum.revision == source.checkedOutEngineCommit)
        require(devRoots.none { r -> oldData.any { it.rootId == r.rootId } })
        val allTrainingGroups = (oldData.map { it.seedGroupId } + devRoots.map { it.seedGroupId }).toSet()
        requireStudyGroupSeparation(allTrainingGroups, valRoots.map { it.seedGroupId }.toSet())
        require(valRoots.map { it.seedGroupId }.distinct().size >= plan.gate.minimumPositiveGroups)
        val registry = buildRegistry(); val deck = loadDeckManifest(deckPath)
        val runner = PositionBankScreenRunner(repository, registry, deck)
        // This mandatory pilot never reads validation outcomes. Its parameters bind to the primary plan.
        val pilot = costs.measure("preflight", describe = { terminalWork(it, bank) }, validate = { terminalRootTrainingData(bank, it, PositionBankScreenPartition.DEVELOPMENT) }) {
            val ids = terminalStudyPilotRoots(devRoots)
            val pilotPlan = plan.development.plan.copy(rootLimit = ids.size, rootIds = ids, repetitions = 1,
                terminalContinuation = requireNotNull(plan.development.plan.terminalContinuation).copy(samplesPerAction = 2))
            runner.run(pilotPlan, output.resolve("preflight"), plan.workers)
        }
        val pilotAccounting = terminalRootScreenAccounting(pilot, bank)
        val projected = pilotAccounting.accumulatedSelectionMillis / pilotAccounting.completedTerminalSamples * newWork / plan.workers / 1000.0
        require(projected <= plan.maximumProjectedCollectionSeconds) { "Projected collection exceeds declared operational limit: $projected seconds" }
        val passBindings = ResearchRunBindings(protocol = "terminal-kernel-study-preflight-v1", material = mapOf(
            "study" to bindings.identity, "pilot" to pilot.researchRunIdentity,
            "pilot-manifest" to researchSha256File(output.resolve("preflight/${ResearchRunArtifacts.MANIFEST_FILE}"))))
        val pass = ResearchPreflightReport(bindings = passBindings, checks = listOf(ResearchPreflightCheck("development-terminal-pilot", true, "Complete samples, exact menus, source-bound primary plan and bounded projection")),
            workload = mapOf("new-continuations" to newWork.toString(), "projected-collection-seconds" to projected.toString()), childRuns = emptyMap())
        // The pilot manifest and report are registered directly; no relative escape in child references.
        writeJsonAtomically(output.resolve("preflight-pass.json"), pass)
        verifyResearchBuild(plan.build, source)
        ResearchRunArtifacts.loadAndVerify(output.resolve("preflight"), pilot.researchRunIdentity)
        val dev = terminalStage("development", plan.development, bank, output, runner, costs, plan.workers)
        val fitPlan = oldReport.plan.copy(additional = oldReport.plan.additional + TerminalRootKernelTrainingInput(
            CloningComparisonInput(plan.development.plan.bankDirectory, bank.bankIdentity), dev.first))
        val fit = costs.measure("fit", reused = plan.retainedFit != null, describe = { ResearchMeasuredWork(it.researchRunIdentity) }) {
            plan.retainedFit?.also { ref ->
                ref.loadFrozenModel()
                val report = evidenceJson.decodeFromString<TerminalRootKernelFitReport>(Files.readString(Path.of(ref.directory).resolve("report.json")))
                require(report.plan == fitPlan) { "Retained fit does not have the exact expanded training plan" }
            } ?: run {
                val path = output.resolve("fit")
                val report = fitTerminalRootKernel(repository, fitPlan, path)
                RootKernelFitReference(path.toString(), report.researchRunIdentity, researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
            }
        }
        val candidate = CompiledRootActionKernel(fit.loadFrozenModel().model)
        writeJsonAtomically(output.resolve("frozen-fit.json"), fit)
        // Validation artifacts, including retained labels, are first loaded below this frozen fit.
        val validation = terminalStage("validation", plan.validation, validationBank, output, runner, costs, plan.workers)
        val targets = terminalRootTrainingData(validationBank, validation.second, PositionBankScreenPartition.VALIDATION)
        val controlReport = costs.measure("production-control", reused = plan.retainedControl != null, describe = {
            if (plan.retainedControl != null) ResearchMeasuredWork(it.researchRunIdentity) else {
                val a = positionScreenAccounting(it).values.single()
                ResearchMeasuredWork(it.researchRunIdentity, mapOf("choices" to a.rows.toLong()), mapOf("reconstruction" to a.accumulatedReconstructionMillis, "selection" to a.accumulatedSelectionMillis))
            }
        }) {
            plan.retainedControl?.let { loadStudyScreen(it, plan.control) } ?: runner.run(plan.control, output.resolve("control"), plan.workers)
        }
        requireTerminalStudyControl(controlReport, plan.control)
        val controlRef = plan.retainedControl ?: CloningComparisonInput(output.resolve("control").toString(), controlReport.researchRunIdentity)
        val comparisons = costs.measure("compare-and-audit") {
            val baseline = CompiledRootActionKernel(plan.baseline.loadFrozenModel().model)
            val oldRows = compareTerminalStudyChoices(validationBank, validation.second, targets, candidate) { root, target ->
                val scores = baseline.scores(target.features); root.reconstructedCandidates[scores.indices.maxBy { scores[it] }].signature
            }
            val productionRows = compareTerminalStudyChoices(validationBank, validation.second, targets, candidate) { root, _ ->
                requireNotNull(controlReport.rows.single { it.rootId == root.rootId }.chosen).signature
            }
            Triple(terminalStudyComparison(oldRows), terminalStudyComparison(productionRows), rootKernelMetrics(baseline, targets))
        }
        val trained = evidenceJson.decodeFromString<List<RootActionKernelTrainingRoot>>(Files.readString(Path.of(fit.directory).resolve("development.json")))
        require(trained == combineTerminalTrainingRoots(listOf(oldData, terminalRootTrainingData(bank, dev.second, PositionBankScreenPartition.DEVELOPMENT))))
        writeJsonAtomically(output.resolve("campaign-after.json"), campaign.snapshot())
        return TerminalKernelStudyReport(bindings.identity, source, plan, fit, dev.first, validation.first, controlRef,
            trained.size, allTrainingGroups.size, targets.size, targets.map { it.seedGroupId }.distinct().size,
            comparisons.first, comparisons.second, terminalStudyGate(plan.gate, comparisons.first, comparisons.second),
            comparisons.third, rootKernelMetrics(candidate, targets), terminalRootScreenAccounting(dev.second, bank),
            terminalRootScreenAccounting(validation.second, validationBank), costs.snapshot())
    }

    private fun terminalStage(name: String, data: TerminalStudyData, bank: RealGamePositionBankReport, output: Path,
        runner: PositionBankScreenRunner, costs: ResearchCostRecorder, workers: Int): Pair<SavedRootPolicyInput, PositionBankScreenReport> {
        val report = costs.measure("$name-targets", reused = data.retained != null, describe = {
            if (data.retained != null) ResearchMeasuredWork(it.researchRunIdentity) else terminalWork(it, bank)
        }, validate = { terminalRootTrainingData(bank, it, data.plan.partition) }) {
            data.retained?.let { loadTerminalRootScreen(it, bank).also { r -> require(r.plan == data.plan) { "Retained target plan changed" } } }
                ?: runner.run(data.plan, output.resolve("$name-terminal"), workers)
        }
        return (data.retained ?: SavedRootPolicyInput(output.resolve("$name-terminal").toString(), report.researchRunIdentity, data.plan.policies.single().search.id)) to report
    }
}

internal fun terminalStudyPilotRoots(roots: List<RealGamePositionBankRoot>): List<String> {
    require(roots.map { it.seedGroupId }.distinct().size >= 2)
    val largest = roots.sortedWith(compareByDescending<RealGamePositionBankRoot> { it.reconstructedCandidates.size }.thenBy { it.rootId }).first()
    val other = roots.filter { it.seedGroupId != largest.seedGroupId }.minBy { it.rootId }
    return listOf(largest.rootId, other.rootId).sorted()
}

internal fun terminalWork(report: PositionBankScreenReport, bank: RealGamePositionBankReport): ResearchMeasuredWork {
    val a = terminalRootScreenAccounting(report, bank)
    return ResearchMeasuredWork(report.researchRunIdentity, mapOf("requested-continuations" to a.requestedContinuations.toLong(),
        "terminal-samples" to a.completedTerminalSamples.toLong(), "failed-attempts" to a.nonGameFailedAttempts.toLong(),
        "unexecuted-continuations" to a.unexecutedContinuations.toLong(), "policy-decisions" to a.continuationPolicyDecisions.toLong()),
        mapOf("reconstruction" to report.rows.sumOf { it.reconstructionMillis ?: 0.0 }, "selection" to a.accumulatedSelectionMillis))
}

internal fun loadStudyScreen(reference: CloningComparisonInput, expected: PositionBankScreenPlan): PositionBankScreenReport {
    val path = Path.of(reference.directory); val manifest = ResearchRunArtifacts.loadAndVerify(path, reference.researchRunIdentity)
    require(manifest.artifacts.map { it.relativePath }.containsAll(listOf("report.json", "plan.json")))
    return evidenceJson.decodeFromString<PositionBankScreenReport>(Files.readString(path.resolve("report.json"))).also {
        require(it.researchRunIdentity == reference.researchRunIdentity && it.plan == expected)
        require(it.plan == evidenceJson.decodeFromString<PositionBankScreenPlan>(Files.readString(path.resolve("plan.json"))))
    }
}

internal fun requireTerminalStudyControl(report: PositionBankScreenReport, plan: PositionBankScreenPlan) {
    require(report.plan == plan && report.valid && report.selectedRootIds == plan.rootIds && report.rows.size == plan.rootIds.size)
    require(report.rows.map { it.rootId }.toSet() == plan.rootIds.toSet())
    report.rows.forEach { row ->
        require(row.disposition == PositionBankScreenDisposition.ROLLOUT_SELECTED && row.chosen != null)
        require(row.searchDiagnostics == null && row.searchRootValue == null && row.candidateStatistics.isEmpty() && row.rootActionEstimates.isEmpty())
        require(row.rolloutPolicyDecision?.declaredPolicyId == "root-argentum-production-rollout-v2" && row.rolloutPolicyDecision.replacement == null)
    }
}

internal fun compareTerminalStudyChoices(bank: RealGamePositionBankReport, terminal: PositionBankScreenReport,
    targets: List<RootActionKernelTrainingRoot>, candidate: CompiledRootActionKernel,
    baseline: (RealGamePositionBankRoot, RootActionKernelTrainingRoot) -> String): List<SavedRootRegretRow> {
    val roots = bank.roots.associateBy { it.rootId }
    return targets.map { target ->
        val root = roots.getValue(target.rootId); val scores = candidate.scores(target.features)
        val chosen = root.reconstructedCandidates[scores.indices.maxBy { scores[it] }].signature
        val old = baseline(root, target)
        val repetitions = (0 until terminal.plan.repetitions).map { rep ->
            terminal.rows.single { it.rootId == root.rootId && it.repetition == rep }.terminalRootActions
                .associate { it.action.signature to requireNotNull(it.meanTerminalPayoff) }
        }
        val (oldRegret, newRegret, delta) = savedRootReferenceComparison(repetitions, old, chosen)
        SavedRootRegretRow(root.rootId, root.seedGroupId, 0, old, chosen, oldRegret, newRegret, delta.average(), delta)
    }
}

internal fun finalizeStudyArtifacts(directory: Path, identity: String) {
    ResearchRunArtifacts(directory, identity).also { artifacts ->
        Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { artifacts.register(directory.relativize(it).toString()) } }
        artifacts.finalize()
    }
    ResearchRunArtifacts.loadAndVerify(directory, identity)
}
