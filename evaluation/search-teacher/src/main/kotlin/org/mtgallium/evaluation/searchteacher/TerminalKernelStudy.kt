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

private data class ReservedTerminalStudyInputs(
    val developmentBank: RealGamePositionBankReport,
    val validationBank: RealGamePositionBankReport,
    val developmentRoots: List<RealGamePositionBankRoot>,
    val validationRoots: List<RealGamePositionBankRoot>,
    val baselinePlan: TerminalRootKernelFitPlan,
    val newContinuations: Long,
    val campaign: CampaignDataRegistry,
)

private data class TerminalStudyBanks(
    val development: RealGamePositionBankReport,
    val validation: RealGamePositionBankReport,
)

private data class TerminalStudyComparisons(
    val baseline: TerminalStudyComparison,
    val production: TerminalStudyComparison,
    val baselineMetrics: RootActionKernelFitMetrics,
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
                finalizeResearchWorkflowArtifacts(destination, bindings.identity)
            }
        } catch (failure: Exception) {
            costs.persist()
            writeTextAtomically(destination.resolve("failure.txt"), "${failure.javaClass.simpleName}: ${failure.message}\n")
            // Failed top-level studies have no successful final manifest. Valid child manifests survive.
            throw failure
        }
    }

    /** Authenticate metadata and reserve exposure before any target or fitted-value decoding. */
    private fun reserveStudyPopulations(
        plan: TerminalKernelStudyPlan,
        output: Path,
        bindings: ResearchRunBindings,
        costs: ResearchCostRecorder,
    ): ReservedTerminalStudyInputs {
        val banks = costs.measure("authenticate-inputs") {
            val bank = loadVerifiedRealGamePositionBank(Path.of(plan.development.plan.bankDirectory), plan.development.plan.expectedBankIdentity)
            val validation = if (plan.validation.plan.bankDirectory == plan.development.plan.bankDirectory && plan.validation.plan.expectedBankIdentity == bank.bankIdentity) bank
                else loadVerifiedRealGamePositionBank(Path.of(plan.validation.plan.bankDirectory), plan.validation.plan.expectedBankIdentity)
            TerminalStudyBanks(bank, validation)
        }
        val bank = banks.development
        val validationBank = banks.validation
        fun selected(screen: PositionBankScreenPlan, bank: RealGamePositionBankReport): List<RealGamePositionBankRoot> {
            val eligible = bank.roots.filter { it.partition.name == screen.partition.name }.sortedBy { it.rootId }
            return selectPositionScreenRoots(screen, eligible).also { roots ->
                require(roots.map { it.rootId } == screen.rootIds && roots.all {
                    it.profileExpansionExhaustive && it.reconstructedCandidates.size >= 2
                })
                require(!plan.requireCastingContext || roots.all { root ->
                    root.reconstructedCandidates.any { it.operationFamily == SemanticOperationFamily.CAST_SPELL }
                })
            }
        }
        val developmentRoots = selected(plan.development.plan, bank)
        val validationRoots = selected(plan.validation.plan, validationBank)
        val baselinePath = Path.of(plan.baseline.directory)
        require(ResearchRunArtifacts.loadAndVerify(baselinePath, plan.baseline.researchRunIdentity).artifacts.any { it.relativePath == "development.json" })
        val baselinePlan = evidenceJson.decodeFromString<TerminalRootKernelFitPlan>(Files.readString(baselinePath.resolve("plan.json")))
        val baselinePopulations = (listOf(TerminalRootKernelTrainingInput(baselinePlan.bank, baselinePlan.terminal)) + baselinePlan.additional).map { input ->
            val b = loadVerifiedRealGamePositionBank(Path.of(input.bank.directory), input.bank.researchRunIdentity)
            val reference = CloningComparisonInput(input.terminal.directory, input.terminal.identity)
            CampaignPopulationInput(input.bank, campaignScreenRoots(reference, b).map { it.rootId }.sorted(), reference)
        }
        val newWork = listOf(plan.development to developmentRoots, plan.validation to validationRoots).sumOf { (data, roots) ->
            val work = terminalRootWorkload(requireNotNull(data.plan.terminalContinuation), roots.map { it.reconstructedCandidates.size }, data.plan.repetitions, 1)
            if (data.retained == null) work.toLong() else 0L
        }
        val pilotRoots = terminalResearchPilotRoots(developmentRoots)
        val pilotWork = developmentRoots.filter { it.rootId in pilotRoots }.sumOf { it.reconstructedCandidates.size.toLong() * 2 }
        require(newWork + pilotWork <= plan.maximumNewContinuations)
        val campaign = CampaignDataRegistry(repository, Path.of(plan.campaignDirectory), plan.campaignId)
        val before = campaign.snapshot()
        writeJsonAtomically(output.resolve("campaign-before.json"), before)
        val priorValidation = before.groups.filter { it.seedGroupId in validationRoots.map { r -> r.seedGroupId }.toSet() }
        writeJsonAtomically(output.resolve("prior-validation-use.json"), priorValidation)
        costs.measure("register-population-use") {
            fun use(role: CampaignDataRole, populations: List<CampaignPopulationInput>, purpose: String) =
                campaign.record(CampaignDataUsePlan(
                    campaignId = plan.campaignId,
                    studyIdentity = bindings.identity,
                    role = role,
                    timing = CampaignDataTiming.PROSPECTIVE_RESERVATION,
                    populations = populations,
                    purpose = purpose,
                ))
            use(
                CampaignDataRole.TRAINING,
                baselinePopulations + CampaignPopulationInput(
                    CloningComparisonInput(plan.development.plan.bankDirectory, bank.bankIdentity),
                    developmentRoots.map { it.rootId },
                ),
                "Fixed baseline training data and declared development expansion; includes pilot access",
            )
            use(
                CampaignDataRole.METHOD_SELECTION,
                listOf(CampaignPopulationInput(
                    CloningComparisonInput(plan.validation.plan.bankDirectory, validationBank.bankIdentity),
                    validationRoots.map { it.rootId },
                )),
                "Fixed exploratory gate after fit freeze; prior registered uses remain visible",
            )
        }
        return ReservedTerminalStudyInputs(
            developmentBank = bank,
            validationBank = validationBank,
            developmentRoots = developmentRoots,
            validationRoots = validationRoots,
            baselinePlan = baselinePlan,
            newContinuations = newWork,
            campaign = campaign,
        )
    }

    private fun runDevelopmentPilot(
        plan: TerminalKernelStudyPlan,
        inputs: ReservedTerminalStudyInputs,
        output: Path,
        source: ResearchRunProvenance,
        bindings: ResearchRunBindings,
        runner: PositionBankScreenRunner,
        costs: ResearchCostRecorder,
    ) {
        // This mandatory pilot never reads validation outcomes. Its parameters bind to the primary plan.
        val pilot = costs.measure(
            "preflight",
            describe = { terminalMeasuredWork(it, inputs.developmentBank) },
            validate = { terminalRootTrainingData(inputs.developmentBank, it, PositionBankScreenPartition.DEVELOPMENT) },
        ) {
            val ids = terminalResearchPilotRoots(inputs.developmentRoots)
            val pilotPlan = terminalResearchPilotPlan(plan.development.plan, ids)
            runner.run(pilotPlan, output.resolve("preflight"), plan.workers)
        }
        val pilotAccounting = terminalRootScreenAccounting(pilot, inputs.developmentBank)
        val projected = projectedTerminalCollectionSeconds(pilotAccounting, inputs.newContinuations, plan.workers)
        require(projected <= plan.maximumProjectedCollectionSeconds) { "Projected collection exceeds declared operational limit: $projected seconds" }
        val passBindings = ResearchRunBindings(protocol = "terminal-kernel-study-preflight-v1", material = mapOf(
            "study" to bindings.identity, "pilot" to pilot.researchRunIdentity,
            "pilot-manifest" to researchSha256File(output.resolve("preflight/${ResearchRunArtifacts.MANIFEST_FILE}"))))
        val pass = ResearchPreflightReport(bindings = passBindings, checks = listOf(ResearchPreflightCheck("development-terminal-pilot", true, "Complete samples, exact menus, source-bound primary plan and bounded projection")),
            workload = mapOf("new-continuations" to inputs.newContinuations.toString(), "projected-collection-seconds" to projected.toString()), childRuns = emptyMap())
        // The pilot manifest and report are registered directly; no relative escape in child references.
        writeJsonAtomically(output.resolve("preflight-pass.json"), pass)
        verifyResearchBuild(plan.build, source)
        ResearchRunArtifacts.loadAndVerify(output.resolve("preflight"), pilot.researchRunIdentity)
    }

    private fun execute(
        plan: TerminalKernelStudyPlan,
        output: Path,
        deckPath: Path,
        source: ResearchRunProvenance,
        bindings: ResearchRunBindings,
        costs: ResearchCostRecorder,
    ): TerminalKernelStudyReport {
        val inputs = reserveStudyPopulations(plan, output, bindings, costs)
        val bank = inputs.developmentBank
        val validationBank = inputs.validationBank
        val developmentRoots = inputs.developmentRoots
        val validationRoots = inputs.validationRoots
        val baselinePath = Path.of(plan.baseline.directory)
        // Reservation is durable before decoding any fitted values or terminal targets, even if admission later fails.
        plan.baseline.loadFrozenModel()
        val baselineReport = evidenceJson.decodeFromString<TerminalRootKernelFitReport>(Files.readString(baselinePath.resolve("report.json")))
        require(baselineReport.plan == inputs.baselinePlan)
        val baselineTrainingRoots = evidenceJson.decodeFromString<List<RootActionKernelTrainingRoot>>(Files.readString(baselinePath.resolve("development.json")))
        require(baselineTrainingRoots.size == baselineReport.development.roots && baselineTrainingRoots.map { it.rootId }.distinct().size == baselineTrainingRoots.size)
        val baselineBank = loadVerifiedRealGamePositionBank(Path.of(baselineReport.plan.bank.directory), baselineReport.plan.bank.researchRunIdentity)
        val originalTarget = loadTerminalRootScreen(baselineReport.plan.terminal, baselineBank)
        requireSameTerminalTarget(originalTarget.plan, plan.development.plan)
        require(originalTarget.sourceProvenance.argentum.revision == source.checkedOutEngineCommit)
        require(developmentRoots.none { r -> baselineTrainingRoots.any { it.rootId == r.rootId } })
        val allTrainingGroups = (baselineTrainingRoots.map { it.seedGroupId } + developmentRoots.map { it.seedGroupId }).toSet()
        requireStudyGroupSeparation(allTrainingGroups, validationRoots.map { it.seedGroupId }.toSet())
        require(validationRoots.map { it.seedGroupId }.distinct().size >= plan.gate.minimumPositiveGroups)
        val registry = buildRegistry()
        val deck = loadDeckManifest(deckPath)
        val runner = PositionBankScreenRunner(repository, registry, deck)
        runDevelopmentPilot(plan, inputs, output, source, bindings, runner, costs)
        val development = runTerminalTargetStage("development", plan.development, bank, output, runner, costs, plan.workers)
        val fitPlan = baselineReport.plan.copy(additional = baselineReport.plan.additional + TerminalRootKernelTrainingInput(
            CloningComparisonInput(plan.development.plan.bankDirectory, bank.bankIdentity), development.reference))
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
        val validation = runTerminalTargetStage("validation", plan.validation, validationBank, output, runner, costs, plan.workers)
        val targets = terminalRootTrainingData(validationBank, validation.report, PositionBankScreenPartition.VALIDATION)
        val controlReport = costs.measure("production-control", reused = plan.retainedControl != null, describe = {
            if (plan.retainedControl != null) ResearchMeasuredWork(it.researchRunIdentity) else {
                val accounting = positionScreenAccounting(it).values.single()
                ResearchMeasuredWork(
                    evidenceIdentity = it.researchRunIdentity,
                    counts = mapOf("choices" to accounting.rows.toLong()),
                    accumulatedComponentMillis = mapOf(
                        "reconstruction" to accounting.accumulatedReconstructionMillis,
                        "selection" to accounting.accumulatedSelectionMillis,
                    ),
                )
            }
        }) {
            plan.retainedControl?.let { loadRetainedTerminalResearchScreen(it, plan.control) } ?: runner.run(plan.control, output.resolve("control"), plan.workers)
        }
        requireProductionRolloutControl(controlReport, plan.control)
        val controlRef = plan.retainedControl ?: CloningComparisonInput(output.resolve("control").toString(), controlReport.researchRunIdentity)
        val comparisons = costs.measure("compare-and-audit") {
            val baseline = CompiledRootActionKernel(plan.baseline.loadFrozenModel().model)
            val oldRows = compareTerminalTargetChoices(validationBank, validation.report, targets, candidate) { root, target ->
                val scores = baseline.scores(target.features)
                terminalModelChoice(root.reconstructedCandidates.map { it.signature }, scores)
            }
            val productionRows = compareTerminalTargetChoices(validationBank, validation.report, targets, candidate) { root, _ ->
                requireNotNull(controlReport.rows.single { it.rootId == root.rootId }.chosen).signature
            }
            TerminalStudyComparisons(terminalTargetComparison(oldRows), terminalTargetComparison(productionRows), rootKernelMetrics(baseline, targets))
        }
        val trained = evidenceJson.decodeFromString<List<RootActionKernelTrainingRoot>>(Files.readString(Path.of(fit.directory).resolve("development.json")))
        require(trained == combineTerminalTrainingRoots(listOf(baselineTrainingRoots, terminalRootTrainingData(bank, development.report, PositionBankScreenPartition.DEVELOPMENT))))
        writeJsonAtomically(output.resolve("campaign-after.json"), inputs.campaign.snapshot())
        return TerminalKernelStudyReport(
            identity = bindings.identity,
            source = source,
            plan = plan,
            fit = fit,
            development = development.reference,
            validation = validation.reference,
            control = controlRef,
            trainingRoots = trained.size,
            trainingGroups = allTrainingGroups.size,
            validationRoots = targets.size,
            validationGroups = targets.map { it.seedGroupId }.distinct().size,
            baselineComparison = comparisons.baseline,
            productionComparison = comparisons.production,
            gate = terminalStudyGate(plan.gate, comparisons.baseline, comparisons.production),
            baselineMetrics = comparisons.baselineMetrics,
            candidateMetrics = rootKernelMetrics(candidate, targets),
            developmentAccounting = terminalRootScreenAccounting(development.report, bank),
            validationAccounting = terminalRootScreenAccounting(validation.report, validationBank),
            costs = costs.snapshot(),
        )
    }
}
