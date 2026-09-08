package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlinx.serialization.Serializable
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.research.run.*
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

/** A developmental deployment-role test of one already frozen fit, with no fitting or promotion. */
@Serializable
internal data class DirectAttackKernelScreenPlan(
    val build: ResearchBuildReference,
    val bank: CloningComparisonInput,
    val rootIds: List<String>,
    val fit: RootKernelFitReference,
    val control: SearchTeacherCalibrationPolicy,
    val selectionSeedDomain: String,
    val targetSeedDomain: String,
    val workers: Int = 8,
) {
    init {
        require(rootIds.size == 32 && rootIds == rootIds.distinct().sorted())
        require(workers in 1..8 && selectionSeedDomain.isNotBlank() && targetSeedDomain.isNotBlank())
        require(selectionSeedDomain != targetSeedDomain)
        require(control.fastRootKernelRolloutFit != null &&
            control.fastRootKernelRolloutFit == control.fastOpponentKernelRolloutFit)
        require(control == SearchTeacherCalibrationPolicy(control.id, 8, 56, 16, 1.4, true, 1.0,
            evaluator = MonoRedVisibleEvaluatorConfig(), fastRootKernelRolloutFit = control.fastRootKernelRolloutFit,
            fastOpponentKernelRolloutFit = control.fastOpponentKernelRolloutFit)) {
            "Direct attack screen freezes the shared fast16/V2 planner and continuation configuration"
        }
    }

    fun candidate() = control.copy(id = "direct-attack-screen-candidate-v1", directAttackKernelFit = fit)
}

@Serializable
internal data class DirectAttackScreenRow(
    val rootId: String, val group: String, val learnedSignature: String,
    val plannerSignatures: List<String>, val heuristicProbabilities: List<Double>,
    val plannerImprovement: List<Double>, val heuristicImprovement: List<Double>,
)

@Serializable
internal data class DirectAttackScreenGate(
    val plannerImprovementByRepetition: List<Double>, val heuristicImprovementByRepetition: List<Double>,
    val plannerGroupImprovements: Map<String, Double>, val heuristicGroupImprovements: Map<String, Double>,
    val passed: Boolean,
)

/** Compare to the full stochastic control, not its most likely action or a sampled shortcut. */
internal fun directAttackContrasts(values: List<Double>, learned: Int, planner: Int,
    probabilities: List<Double>): Pair<Double, Double> {
    require(values.size in 2..8 && probabilities.size == values.size)
    require(learned in values.indices && planner in values.indices)
    require(values.all { it.isFinite() && it in -1.0..1.0 })
    require(probabilities.all { it.isFinite() && it in 0.0..1.0 } && abs(probabilities.sum() - 1.0) < 1e-9)
    return values[learned] - values[planner] to
        values[learned] - values.indices.sumOf { values[it] * probabilities[it] }
}

internal fun directAttackScreenGate(rows: List<DirectAttackScreenRow>): DirectAttackScreenGate {
    require(rows.size == 32 && rows.map { it.rootId }.distinct().size == 32)
    require(rows.all { row -> listOf(row.plannerImprovement, row.heuristicImprovement).all { values ->
        values.size == 2 && values.all { it.isFinite() && it in -2.0..2.0 }
    } })
    val groups = rows.groupBy { it.group }.toSortedMap()
    require(groups.size == 16 && groups.values.all { it.size in 1..4 })
    fun repetitions(values: (DirectAttackScreenRow) -> List<Double>) = (0..1).map { rep ->
        groups.values.map { rs -> rs.map { values(it)[rep] }.average() }.average()
    }
    fun means(values: (DirectAttackScreenRow) -> List<Double>) =
        groups.mapValues { (_, rs) -> rs.flatMap(values).average() }
    val planner = repetitions { it.plannerImprovement }; val heuristic = repetitions { it.heuristicImprovement }
    val plannerGroups = means { it.plannerImprovement }; val heuristicGroups = means { it.heuristicImprovement }
    return DirectAttackScreenGate(planner, heuristic, plannerGroups, heuristicGroups,
        (planner + heuristic).all { it > 0 } && plannerGroups.values.count { it > 0 } >= 8 &&
            heuristicGroups.values.count { it > 0 } >= 8)
}

@Serializable
internal data class DirectAttackKernelScreenReport(
    val identity: String, val source: ResearchRunProvenance, val plan: DirectAttackKernelScreenPlan,
    val assignedContinuations: Int, val selection: List<CloningComparisonInput>,
    val terminal: List<SavedRootPolicyInput>, val disposition: String,
    val rows: List<DirectAttackScreenRow> = emptyList(), val gate: DirectAttackScreenGate? = null,
    val interpretation: String = "Developmental test of a frozen attack kernel in a new direct role. Training-disjoint seed groups may remain campaign-observed. Current engine reconstruction must match retained information and the full semantic menu; this does not establish unchanged continuation gameplay across engine revisions. Fresh terminal targets condition on posterior hypotheses and common fast continuations, while later deployed decisions use search. Gains over both the actual planner choice and the direct stochastic heuristic are exploratory conditional-return evidence, never a gameplay-strength or recursive-learning result.",
)

internal class DirectAttackKernelScreenRunner(private val repository: Path) {
    fun run(plan: DirectAttackKernelScreenPlan, output: Path, deckPath: Path): DirectAttackKernelScreenReport {
        val source = ResearchRunProvenance.capture(repository).also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        verifyResearchBuild(plan.build, source)
        plan.fit.loadFrozenModel()
        val fitPath = Path.of(plan.fit.directory)
        require(ResearchRunArtifacts.loadAndVerify(fitPath, plan.fit.researchRunIdentity).artifacts.any {
            it.relativePath == "development.json"
        })
        val training = evidenceJson.decodeFromString<List<RootActionKernelTrainingRoot>>(
            Files.readString(fitPath.resolve("development.json")))
        val trainingGroups = training.map { it.seedGroupId }.toSet()
        val bank = loadVerifiedRealGamePositionBank(Path.of(plan.bank.directory), plan.bank.researchRunIdentity)
        val roots = plan.rootIds.map { id -> bank.roots.single { it.rootId == id } }
        require(roots.groupBy { it.seedGroupId }.let { it.size == 16 && it.values.all { rs -> rs.size in 1..4 } })
        roots.forEach { root ->
            require(root.partition == RealGamePositionPartition.DEVELOPMENT && root.seedGroupId !in trainingGroups)
            require(attackKernelScope(root.reconstructedCandidates, root.profileExpansionExhaustive))
            require(root.candidates.toSet() == root.reconstructedCandidates.toSet())
            require(rootActionKernelFeatures(root.information, root.reconstructedCandidates).distinct().size == root.reconstructedCandidates.size)
        }
        val assigned = roots.sumOf { it.reconstructedCandidates.size } * 16
        require(assigned <= 4096)
        val bindings = ResearchRunBindings(protocol = "direct-attack-kernel-screen-v1", material = mapOf(
            "plan" to sha256(evidenceJson.encodeToString(DirectAttackKernelScreenPlan.serializer(), plan)),
            "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "bank-manifest" to researchSha256File(Path.of(plan.bank.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        ))
        val directory = EvidenceStore(repository).requireDiagnosticOutput(output, "direct attack kernel screen")
        require(!Files.exists(directory)) { "Inspect retained screen output instead of rerunning" }
        Files.createDirectories(directory)
        writeJsonAtomically(directory.resolve("plan.json"), plan)
        writeJsonAtomically(directory.resolve("bindings.json"), bindings)
        val selection = mutableListOf<CloningComparisonInput>()
        val terminals = mutableListOf<SavedRootPolicyInput>()
        fun finish(disposition: String, rows: List<DirectAttackScreenRow> = emptyList(), gate: DirectAttackScreenGate? = null): DirectAttackKernelScreenReport {
            val report = DirectAttackKernelScreenReport(bindings.identity, source, plan, assigned, selection.toList(),
                terminals.toList(), disposition, rows, gate)
            writeJsonAtomically(directory.resolve("report.json"), report)
            finalizeResearchWorkflowArtifacts(directory, bindings.identity)
            return report
        }
        val runner = PositionBankScreenRunner(repository, buildRegistry(), loadDeckManifest(deckPath))
        val selectionRows = mutableListOf<PositionBankScreenRow>()
        val terminalRows = mutableListOf<PositionBankScreenRow>()
        val candidate = plan.candidate().also { require(it.id != plan.control.id) }
        fun screenPlan(ids: List<String>, mode: PositionBankScreenMode) = PositionBankScreenPlan(
            bankDirectory = plan.bank.directory, expectedBankIdentity = plan.bank.researchRunIdentity,
            partition = PositionBankScreenPartition.DEVELOPMENT, mode = mode, rootLimit = ids.size, rootIds = ids,
            repetitions = 2, policies = (if (mode == PositionBankScreenMode.SEARCH) listOf(plan.control, candidate)
                else listOf(plan.control)).map { PositionBankScreenPolicy(it, plan.control.evaluator) },
            searchSeedDomain = if (mode == PositionBankScreenMode.SEARCH) plan.selectionSeedDomain else plan.targetSeedDomain,
            terminalContinuation = if (mode == PositionBankScreenMode.TERMINAL_CONTINUATIONS)
                TerminalRootContinuationConfig(8, maximumContinuationPolicyDecisions = 4096, maximumTotalContinuations = 4096) else null,
        )
        // All choices are frozen before the first target is accessed. A failed worker-sized batch stops dispatch.
        for ((index, ids) in plan.rootIds.chunked(plan.workers).withIndex()) {
            val path = directory.resolve("selection-$index")
            val report = runner.run(screenPlan(ids, PositionBankScreenMode.SEARCH), path, plan.workers)
            selection += CloningComparisonInput(path.toString(), report.researchRunIdentity)
            if (!report.valid) return finish("SELECTION_FAILURE")
            require(report.rows.all { if (it.policyId == candidate.id)
                it.selectionKind == "DIRECT_POLICY_ACTION" && it.searchDiagnostics == null
                else it.selectionKind == "SEARCHED" && it.searchDiagnostics?.simulations == 56 })
            selectionRows += report.rows
        }
        for ((index, ids) in plan.rootIds.chunked(plan.workers).withIndex()) {
            val path = directory.resolve("terminal-$index")
            val report = runner.run(screenPlan(ids, PositionBankScreenMode.TERMINAL_CONTINUATIONS), path, plan.workers)
            val ref = SavedRootPolicyInput(path.toString(), report.researchRunIdentity, plan.control.id)
            terminals += ref
            loadTerminalRootScreen(ref, bank)
            if (!report.valid) return finish("TARGET_FAILURE")
            terminalRootTrainingData(bank, report, PositionBankScreenPartition.DEVELOPMENT)
            terminalRows += report.rows
        }
        val incumbent = plan.control.policy(0).effectiveRootRolloutPolicy()
        val rows = roots.map { root ->
            val menu = root.reconstructedCandidates
            val selected = selectionRows.filter { it.rootId == root.rootId }
            val learned = selected.filter { it.policyId == candidate.id }.map { requireNotNull(it.chosen) }.distinct().single()
            val planner = (0..1).map { rep -> requireNotNull(selected.single {
                it.policyId == plan.control.id && it.repetition == rep
            }.chosen) }
            val distribution = incumbent.distribution(root.information, menu, 0)
            val probabilities = menu.map { choice -> distribution.probabilityOf { it.signature == choice.signature } }
            val contrasts = (0..1).map { rep ->
                val targets = terminalRows.single { it.rootId == root.rootId && it.repetition == rep }.terminalRootActions
                require(targets.map { it.action } == menu)
                directAttackContrasts(targets.map { requireNotNull(it.meanTerminalPayoff) }, menu.indexOf(learned),
                    menu.indexOf(planner[rep]), probabilities)
            }
            DirectAttackScreenRow(root.rootId, root.seedGroupId, learned.signature, planner.map { it.signature },
                probabilities, contrasts.map { it.first }, contrasts.map { it.second })
        }
        val gate = directAttackScreenGate(rows)
        return finish(if (gate.passed) "DEVELOPMENT_GATE_PASSED_GAMEPLAY_REQUIRED" else "DEVELOPMENT_GATE_FAILED", rows, gate)
    }
}
