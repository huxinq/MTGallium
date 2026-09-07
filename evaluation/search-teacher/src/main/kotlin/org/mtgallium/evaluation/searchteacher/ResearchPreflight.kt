package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal data class ResearchPreflightPlan(
    val schemaVersion: Int = 1,
    val targetOutput: String,
    val work: ResearchPreflightWork,
) {
    init { require(schemaVersion == 1); absolutePreflightPath(targetOutput) }
}

@Serializable
internal sealed class ResearchPreflightWork {
    @Serializable
    @SerialName("gameplay")
    data class Gameplay(
        val planPath: String,
        val sequential: Boolean,
        val deckManifest: String,
        val threads: Int,
        val smokeBaseSeed: Long,
        val smokeSimulations: Int = 4,
    ) : ResearchPreflightWork() {
        init {
            absolutePreflightPath(planPath); absolutePreflightPath(deckManifest)
            require(threads > 0 && smokeSimulations > 0)
        }
    }

    @Serializable
    @SerialName("position-screen")
    data class PositionScreen(
        val planPath: String,
        val deckManifest: String,
        val threads: Int,
        val smokeSimulations: Int = 4,
        val smokeRootLimit: Int = 1,
        val smokeRepetitions: Int = 1,
    ) : ResearchPreflightWork() {
        init {
            absolutePreflightPath(planPath); absolutePreflightPath(deckManifest)
            require(threads > 0 && smokeSimulations > 0 && smokeRootLimit > 0 && smokeRepetitions > 0)
        }
    }

    /** Initial learning adapter deliberately uses the existing verified retained-32 population. */
    @Serializable
    @SerialName("decision-local-learning")
    data class Learning(
        val parentDirectory: String,
        val precisionDirectory: String,
        val learner: PreflightLearner,
        val nonlinear: DecisionLocalNonlinearConfig = DecisionLocalNonlinearConfig(),
        val smokeTrainingRoots: Int = 2,
        val smokeValidationRoots: Int = 1,
        val smokeEpochs: Int = 2,
    ) : ResearchPreflightWork() {
        init {
            absolutePreflightPath(parentDirectory); absolutePreflightPath(precisionDirectory)
            require(smokeTrainingRoots > 0 && smokeValidationRoots > 0 && smokeEpochs > 0)
        }
    }
}

@Serializable
internal enum class PreflightLearner { LINEAR, PHASE_LINEAR, NONLINEAR }

private fun absolutePreflightPath(value: String): Path = Path.of(value).also {
    require(it.isAbsolute) { "Preflight paths must be absolute: $value" }
}.normalize()

internal fun gameplayPreflightPlan(plan: SearchTeacherCalibrationPlan, work: ResearchPreflightWork.Gameplay): SearchTeacherCalibrationPlan {
    require(work.smokeBaseSeed != plan.baseSeed) { "Smoke gameplay requires a separate explicit base seed" }
    fun reduce(policy: SearchTeacherCalibrationPolicy) = policy.copy(simulations = minOf(policy.simulations, work.smokeSimulations))
    return plan.copy(phase = SearchTeacherCalibrationPhase.PREFLIGHT, baseSeed = work.smokeBaseSeed,
        pairOffset = 0, pairCount = 1, control = reduce(plan.control), candidates = plan.candidates.map(::reduce))
}

internal fun positionScreenPreflightPlan(plan: PositionBankScreenPlan, work: ResearchPreflightWork.PositionScreen): PositionBankScreenPlan {
    require(plan.mode != PositionBankScreenMode.FEATURES) { "Position preflight must exercise search" }
    return plan.copy(rootLimit = minOf(plan.rootLimit, work.smokeRootLimit),
        rootIds = if (plan.rootIds.isEmpty()) emptyList() else plan.rootIds.take(work.smokeRootLimit),
        repetitions = minOf(plan.repetitions, work.smokeRepetitions),
        policies = plan.policies.map { it.copy(search = it.search.copy(simulations = minOf(it.search.simulations, work.smokeSimulations))) })
}

internal fun requirePositionScreenPreflightComplete(
    report: PositionBankScreenReport,
    plan: PositionBankScreenPlan,
    expectedRoots: Map<String, List<org.mtgallium.agent.infoset.core.SemanticChoice>>,
) {
    require(report.plan == plan && report.valid && report.selectedRootIds.isNotEmpty())
    require(report.selectedRootIds == expectedRoots.keys.toList()) { "Smoke root population differs from the authenticated bank selection" }
    require(report.rows.size == report.selectedRootIds.size * plan.policies.size * plan.repetitions)
    val expected = report.selectedRootIds.flatMap { root -> plan.policies.flatMap { policy ->
        (0 until plan.repetitions).map { repetition -> Triple(root, policy.search.id, repetition) }
    } }.toSet()
    require(report.rows.map { Triple(it.rootId, it.policyId, it.repetition) }.toSet() == expected)
    report.rows.forEach { row ->
        require(row.rootId in report.selectedRootIds && row.repetition in 0 until plan.repetitions)
        val policy = plan.policies.single { it.search.id == row.policyId }
        if (plan.mode == PositionBankScreenMode.ACTION_CONDITIONAL) {
            require(row.disposition == PositionBankScreenDisposition.ACTION_CONDITIONAL && row.rootActionEstimates.size >= 2)
            require(row.rootActionEstimates.map { it.action.signature } == expectedRoots.getValue(row.rootId).map { it.signature }) {
                "Smoke action coverage differs from the authenticated bank menu"
            }
            require(row.rootActionEstimates.all { it.visits == policy.search.simulations })
        } else require(row.disposition == PositionBankScreenDisposition.SEARCHED && row.searchDiagnostics?.simulations == policy.search.simulations)
    }
}

/** No wins, payoff, loss improvement, or strategic stopping boundary enters the technical gate. */
internal fun requireGameplayPreflightComplete(report: SearchTeacherCalibrationReport, plan: SearchTeacherCalibrationPlan) {
    require(report.plan == plan && report.valid)
    require(report.comparisons.map { it.candidateId }.toSet() == plan.candidates.map { it.id }.toSet())
    require(report.comparisons.size == plan.candidates.size)
    report.comparisons.forEach {
        require(it.assignedPairs == 1 && it.validPairs == 1 && it.validGames == 2 &&
            it.invalidPairs == 0 && it.incompletePairs == 0) { "Smoke gameplay did not complete its two valid games: ${it.candidateId}" }
    }
    require(report.sequentialOperationalValid != false)
}

/** Fits TRAIN only, persists the model before scoring, and verifies exact reload predictions. */
internal fun runLearningPreflight(
    roots: List<DecisionLocalRootEvidence>, work: ResearchPreflightWork.Learning, output: Path,
): Map<String, String> {
    require(roots.isNotEmpty() && roots.all { it.split != DecisionLocalSplit.TEST })
    require(roots.map { it.rootId }.distinct().size == roots.size)
    require(roots.map { it.pairIndex }.distinct().size == roots.size) { "Whole-game groups overlap" }
    val train = roots.filter { it.split == DecisionLocalSplit.TRAIN }.sortedBy { it.rootId }.take(work.smokeTrainingRoots)
    val validation = roots.filter { it.split == DecisionLocalSplit.VALIDATION }.sortedBy { it.rootId }.take(work.smokeValidationRoots)
    require(train.isNotEmpty() && validation.isNotEmpty())
    val modelPath = output.resolve("model.json")
    fun write(bytes: String) = ResearchRunFiles.atomicWrite(modelPath, bytes)
    val before: (DecisionLocalRootEvidence) -> List<Double>
    val after: (DecisionLocalRootEvidence) -> List<Double>
    val modelId: String
    when (work.learner) {
        PreflightLearner.LINEAR -> {
            val model = fitLearnabilityModel(train)
            write(evidenceJson.encodeToString(model))
            val restored = evidenceJson.decodeFromString<DecisionLocalModelCheckpoint>(Files.readString(modelPath))
            require(restored.modelId == model.modelId)
            before = { it.candidates.map(model::score) }; after = { it.candidates.map(restored::score) }; modelId = model.modelId
        }
        PreflightLearner.PHASE_LINEAR -> {
            val model = fitDecisionLocalPhaseModel(train)
            write(evidenceJson.encodeToString(model))
            val restored = evidenceJson.decodeFromString<DecisionLocalPhaseModel>(Files.readString(modelPath))
            require(restored.modelId == model.modelId)
            before = model::scores; after = restored::scores; modelId = model.modelId
        }
        PreflightLearner.NONLINEAR -> {
            val model = fitDecisionLocalNonlinearModel(train, work.nonlinear.copy(epochs = minOf(work.nonlinear.epochs, work.smokeEpochs)))
            write(evidenceJson.encodeToString(model))
            val restored = evidenceJson.decodeFromString<DecisionLocalNonlinearModel>(Files.readString(modelPath))
            require(restored.modelId == model.modelId)
            before = model::scores; after = restored::scores; modelId = model.modelId
        }
    }
    val scores = validation.associate { root ->
        val original = before(root)
        val reloaded = after(root)
        require(original.size == root.candidates.size && original.all(Double::isFinite) && original == reloaded)
        root.rootId to reloaded
    }
    ResearchRunFiles.atomicWrite(output.resolve("scores.json"), evidenceJson.encodeToString(scores))
    return mapOf("learner" to work.learner.name, "model-id" to modelId,
        "training-roots" to evidenceJson.encodeToString(train.map { it.rootId }),
        "validation-roots" to evidenceJson.encodeToString(validation.map { it.rootId }),
        "full-training-roots" to roots.count { it.split == DecisionLocalSplit.TRAIN }.toString(),
        "full-validation-roots" to roots.count { it.split == DecisionLocalSplit.VALIDATION }.toString(),
        "full-epochs" to if (work.learner == PreflightLearner.NONLINEAR) work.nonlinear.epochs.toString() else "not-applicable",
        "smoke-epochs" to if (work.learner == PreflightLearner.NONLINEAR) minOf(work.nonlinear.epochs, work.smokeEpochs).toString() else "not-applicable")
}

internal fun preflightRuntimeHash(runtime: Map<String, String>): String =
    researchSha256(evidenceJson.encodeToString<Map<String, String>>(runtime.toSortedMap()))

private data class PreflightContext(
    val plan: ResearchPreflightPlan,
    val provenance: ResearchRunProvenance,
    val runtime: Map<String, String>,
    val bindings: ResearchRunBindings,
)

internal class ResearchPreflightRunner(private val root: Path) {
    private val store = EvidenceStore(root)

    private fun context(profile: Path, output: Path): PreflightContext {
        val bytes = Files.readAllBytes(profile)
        val plan = evidenceJson.decodeFromString<ResearchPreflightPlan>(bytes.decodeToString())
        val target = store.requireDiagnosticOutput(absolutePreflightPath(plan.targetOutput), "the intended research run")
        require(!target.startsWith(output) && !output.startsWith(target)) { "Smoke and primary output directories must be separate" }
        val source = ResearchRunProvenance.capture(root)
        val runtime = researchPreflightRuntime()
        val inputs = when (val work = plan.work) {
            is ResearchPreflightWork.Gameplay -> listOf(absolutePreflightPath(work.planPath), absolutePreflightPath(work.deckManifest))
            is ResearchPreflightWork.PositionScreen -> {
                val screen = evidenceJson.decodeFromString<PositionBankScreenPlan>(Files.readString(absolutePreflightPath(work.planPath)))
                val bank = absolutePreflightPath(screen.bankDirectory)
                loadVerifiedRealGamePositionBank(bank, screen.expectedBankIdentity)
                listOf(absolutePreflightPath(work.planPath), absolutePreflightPath(work.deckManifest), bank)
            }
            is ResearchPreflightWork.Learning -> listOf(absolutePreflightPath(work.parentDirectory), absolutePreflightPath(work.precisionDirectory))
        }
        val material = linkedMapOf("profile" to researchSha256(bytes), "source" to researchSha256(evidenceJson.encodeToString(source)),
            "runtime" to preflightRuntimeHash(runtime), "target-output" to target.toString())
        inputs.forEachIndexed { index, path ->
            require(!path.startsWith(output) && !path.startsWith(target) && !output.startsWith(path) && !target.startsWith(path)) {
                "Input and output paths overlap: $path"
            }
            if (Files.isDirectory(path)) {
                ResearchRunArtifacts.loadAndVerify(path)
                material["input-$index"] = researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE))
            } else material["input-$index"] = researchSha256File(path)
        }
        return PreflightContext(plan, source, runtime, ResearchRunBindings(protocol = "research-preflight-v1", material = material))
    }

    private fun requireReady(context: PreflightContext) {
        context.provenance.requireReady()
        require(!context.provenance.outerDirty && !context.provenance.engineDirty) { "Commit clean treatment and engine source before preflight" }
        val target = absolutePreflightPath(context.plan.targetOutput)
        if (Files.exists(target)) {
            require(Files.isDirectory(target) && Files.list(target).use { it.findAny().isEmpty }) { "Primary output must be empty: $target" }
        }
        val existed = Files.exists(target)
        Files.createDirectories(target)
        try {
            val probe = Files.createTempFile(target, ".preflight-write-", ".tmp")
            Files.delete(probe)
        } finally {
            if (!existed) Files.delete(target)
        }
    }

    fun run(profile: Path, requestedOutput: Path, verifyOnly: Boolean = false): ResearchPreflightReport {
        val output = store.requireDiagnosticOutput(requestedOutput, "research preflight evidence")
        if (verifyOnly || Files.exists(output.resolve(ResearchRunArtifacts.MANIFEST_FILE))) {
            val current = context(profile, output)
            requireReady(current)
            return ResearchPreflights.verify(output, current.bindings)
        }
        require(!Files.exists(output)) { "Preflight output must be fresh or contain a completed manifest: $output" }
        // Refusals before a complete binding exists remain structured failures, never reusable passes.
        val initial = try { context(profile, output) } catch (error: Exception) {
            writeFailure(output, error); throw error
        }
        val checks = mutableListOf<ResearchPreflightCheck>()
        val artifacts = mutableListOf("profile.json", "source.json", "runtime.json")
        val children = linkedMapOf<String, String>()
        val workload = linkedMapOf<String, String>()
        fun check(name: String, action: () -> Unit) {
            try { action(); checks += ResearchPreflightCheck(name, true, "passed") }
            catch (error: Exception) {
                checks += ResearchPreflightCheck(name, false, "${error.javaClass.simpleName}: ${error.message}")
                throw error
            }
        }
        ResearchRunFiles.atomicWrite(output.resolve("profile.json"), Files.readAllBytes(profile))
        ResearchRunFiles.atomicWrite(output.resolve("source.json"), evidenceJson.encodeToString(initial.provenance))
        ResearchRunFiles.atomicWrite(output.resolve("runtime.json"), evidenceJson.encodeToString(initial.runtime))
        try {
            check("readiness") { requireReady(initial) }
            check("smoke-execution-and-artifact-roundtrip") {
                when (val work = initial.plan.work) {
                    is ResearchPreflightWork.Gameplay -> {
                        val planBytes = Files.readString(absolutePreflightPath(work.planPath))
                        val sequential = if (work.sequential) evidenceJson.decodeFromString<SearchTeacherSequentialPlan>(planBytes) else null
                        val full = sequential?.calibration ?: evidenceJson.decodeFromString<SearchTeacherCalibrationPlan>(planBytes)
                        val smoke = gameplayPreflightPlan(full, work)
                        ResearchRunFiles.atomicWrite(output.resolve("primary-plan.json"), planBytes)
                        ResearchRunFiles.atomicWrite(output.resolve("deck.json"), Files.readAllBytes(absolutePreflightPath(work.deckManifest)))
                        artifacts += listOf("primary-plan.json", "deck.json")
                        val rule = sequential?.rule?.copy(maximumPairs = 1)
                        ResearchRunFiles.atomicWrite(output.resolve("smoke-plan.json"), evidenceJson.encodeToString(smoke))
                        artifacts += "smoke-plan.json"
                        if (rule != null) {
                            ResearchRunFiles.atomicWrite(output.resolve("smoke-rule.json"), evidenceJson.encodeToString(rule))
                            artifacts += "smoke-rule.json"
                        }
                        val report = SearchTeacherCalibrationRunner(root, buildRegistry(), loadDeckManifest(absolutePreflightPath(work.deckManifest)))
                            .run(smoke, output.resolve("gameplay"), work.threads, rule)
                        ResearchRunArtifacts.loadAndVerify(output.resolve("gameplay"), report.runIdentity)
                        children["gameplay"] = report.runIdentity
                        requireGameplayPreflightComplete(report, smoke)
                        workload.putAll(mapOf("full-pairs-per-candidate" to full.pairCount.toString(), "smoke-pairs-per-candidate" to "1",
                            "simulation-cap" to work.smokeSimulations.toString(), "smoke-base-seed" to smoke.baseSeed.toString(),
                            "full-plan-sha256" to researchSha256(planBytes), "worker-threads" to work.threads.toString()))
                    }
                    is ResearchPreflightWork.Learning -> {
                        val inputs = loadDecisionLocalLearnabilityInputs(absolutePreflightPath(work.parentDirectory), absolutePreflightPath(work.precisionDirectory))
                        workload.putAll(runLearningPreflight(inputs.combined, work, output))
                        artifacts += listOf("model.json", "scores.json")
                    }
                    is ResearchPreflightWork.PositionScreen -> {
                        val planBytes = Files.readString(absolutePreflightPath(work.planPath))
                        val full = evidenceJson.decodeFromString<PositionBankScreenPlan>(planBytes)
                        val smoke = positionScreenPreflightPlan(full, work)
                        ResearchRunFiles.atomicWrite(output.resolve("primary-plan.json"), planBytes)
                        ResearchRunFiles.atomicWrite(output.resolve("smoke-plan.json"), evidenceJson.encodeToString(smoke))
                        ResearchRunFiles.atomicWrite(output.resolve("deck.json"), Files.readAllBytes(absolutePreflightPath(work.deckManifest)))
                        artifacts += listOf("primary-plan.json", "smoke-plan.json", "deck.json")
                        val report = PositionBankScreenRunner(root, buildRegistry(), loadDeckManifest(absolutePreflightPath(work.deckManifest)))
                            .run(smoke, output.resolve("screen"), work.threads)
                        ResearchRunArtifacts.loadAndVerify(output.resolve("screen"), report.researchRunIdentity)
                        val bank = loadVerifiedRealGamePositionBank(absolutePreflightPath(smoke.bankDirectory), smoke.expectedBankIdentity)
                        val expectedRoots = selectPositionScreenRoots(smoke, bank.roots.filter { it.partition.name == smoke.partition.name }.sortedBy { it.rootId })
                            .associate { it.rootId to it.reconstructedCandidates }
                        requirePositionScreenPreflightComplete(report, smoke, expectedRoots)
                        children["screen"] = report.researchRunIdentity
                        workload.putAll(mapOf("full-plan-sha256" to researchSha256(planBytes), "worker-threads" to work.threads.toString(),
                            "full-root-limit" to full.rootLimit.toString(), "full-repetitions" to full.repetitions.toString(),
                            "smoke-root-limit" to smoke.rootLimit.toString(), "smoke-repetitions" to smoke.repetitions.toString(),
                            "simulation-cap" to work.smokeSimulations.toString()))
                    }
                }
            }
            check("source-runtime-input-stability") { require(context(profile, output).bindings == initial.bindings) { "Source, runtime, profile, or inputs changed during preflight" } }
        } catch (error: Exception) {
            val failed = ResearchPreflightReport(bindings = initial.bindings, checks = checks, workload = workload, childRuns = children)
            ResearchPreflights.persist(output, failed, artifacts.filter { Files.isRegularFile(output.resolve(it)) })
            throw error
        }
        val report = ResearchPreflightReport(bindings = initial.bindings, checks = checks, workload = workload, childRuns = children)
        ResearchPreflights.persist(output, report, artifacts)
        return ResearchPreflights.verify(output, initial.bindings)
    }

    private fun writeFailure(output: Path, error: Exception) {
        ResearchRunFiles.atomicWrite(output.resolve("preflight-failure.json"), evidenceJson.encodeToString(
            ResearchPreflightCheck("binding-preparation", false, "${error.javaClass.simpleName}: ${error.message}")))
    }
}
