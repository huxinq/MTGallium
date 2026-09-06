package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal data class RootActionKernelPlan(val referenceExperiment: CloningComparisonInput, val ridge: Double = .001,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val developmentExtension: RootActionKernelExtension? = null) {
    init { require(ridge.isFinite() && ridge > 0) }
}

@Serializable
internal data class RootActionKernelExtension(val bankDirectory: String, val bankIdentity: String, val reference: SavedRootPolicyInput)

@Serializable
internal data class RootActionKernelFitMetrics(val roots: Int, val seedGroups: Int, val actions: Int,
    val equalGroupCenteredMeanSquaredError: Double, val equalRootReferenceRegret: Double)

@Serializable
internal data class RootActionKernelReport(val researchRunIdentity: String, val source: ResearchRunProvenance,
    val plan: RootActionKernelPlan, val development: RootActionKernelFitMetrics, val validation: RootActionKernelFitMetrics,
    val baselineMeanRegret: Double, val candidateMeanRegret: Double, val changedChoices: Int,
    val rows: List<SavedRootRegretRow>, val groups: List<SavedRootGroupRegret>,
    val inferenceMillisByRoot: List<Double>,
    val interpretation: String = "One direct centered action-value kernel ridge fit on DEVELOPMENT references, with no neural cloning fit, simulation or search-prior wiring. Numeric features reuse the perspective-safe encoder and an explicit candidate plus state-candidate interaction kernel. All weights fit only development; fixed ridge, no validation selection. Greedy first-menu argmax is repeated against each retained V2 selection seed for paired descriptive regret, not independent candidate replication. Targets are mostly heuristic deeper hand-V2 backups; validation is coefficient-held-out but method-adaptive, not gameplay or a pristine method test.")

internal fun runRootActionKernelExperiment(repository: Path, plan: RootActionKernelPlan, output: Path): RootActionKernelReport {
    val source = ResearchRunProvenance.capture(repository, "third_party/argentum-engine").also { it.requireReady() }
    require(!source.outerDirty && !source.engineDirty)
    val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "root action kernel experiment")
    require(!Files.exists(destination))
    val previousPath = Path.of(plan.referenceExperiment.directory)
    val previousArtifacts = ResearchRunArtifacts.loadAndVerify(previousPath, plan.referenceExperiment.researchRunIdentity)
    require(previousArtifacts.artifacts.any { it.relativePath == "report.json" } && previousArtifacts.artifacts.any { it.relativePath == "bindings.json" })
    val previous = evidenceJson.decodeFromString<VisibleV2ExperimentReport>(Files.readString(previousPath.resolve("report.json")))
    val previousBindings = evidenceJson.decodeFromString<ResearchRunBindings>(Files.readString(previousPath.resolve("bindings.json")))
    require(previous.researchRunIdentity == plan.referenceExperiment.researchRunIdentity && previousBindings.identity == previous.researchRunIdentity)
    require(previousBindings.protocol == "visible-v2-calibration-experiment-v1")
    val bank = loadVerifiedRealGamePositionBank(Path.of(previous.plan.bankDirectory), previous.plan.bankIdentity)
    val extensionBank = plan.developmentExtension?.let { loadVerifiedRealGamePositionBank(Path.of(it.bankDirectory), it.bankIdentity) }
    val bankRoots = bank.roots.associateBy { it.rootId }.toMutableMap()
    extensionBank?.roots?.forEach { root ->
        bankRoots[root.rootId]?.let { require(it == root) { "Overlapping root projection differs from original bank" } }
        bankRoots[root.rootId] = root
    }
    fun encode(targets: List<VisibleV2Target>) = targets.map { target ->
        val root = bankRoots.getValue(target.rootId)
        require(root.reconstructedCandidates.map { it.signature }.toSet() == target.actionMeans.keys)
        RootActionKernelTrainingRoot(target.rootId, target.seedGroupId,
            rootActionKernelFeatures(root.information, root.reconstructedCandidates),
            root.reconstructedCandidates.map { target.actionMeans.getValue(it.signature) })
    }
    val developmentInput = SavedRootPolicyInput(previousPath.resolve("development-reference").toString(), previous.developmentReferenceIdentity, previous.plan.reference.id)
    val developmentTargets = visibleV2ReferenceTargets(bank, developmentInput, PositionBankScreenPartition.DEVELOPMENT, previous.plan.hand)
    val extensionTargets = plan.developmentExtension?.let {
        visibleV2ReferenceTargets(requireNotNull(extensionBank), it.reference, PositionBankScreenPartition.DEVELOPMENT, previous.plan.hand)
    }.orEmpty()
    require(extensionTargets.none { added -> developmentTargets.any { it.rootId == added.rootId } })
    val development = encode(developmentTargets + extensionTargets)
    val heldOutGroups = bank.roots.filter { it.partition == RealGamePositionPartition.VALIDATION }.map { it.seedGroupId }.toSet()
    require(development.map { it.seedGroupId }.distinct().size >= 2 && heldOutGroups.isNotEmpty() && development.none { it.seedGroupId in heldOutGroups })
    val bindings = ResearchRunBindings(protocol = "root-action-kernel-fit-v1", material = mapOf(
        "source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
        "plan" to sha256(evidenceJson.encodeToString(RootActionKernelPlan.serializer(), plan)),
        "reference-manifest" to researchSha256File(previousPath.resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        "extension-reference-manifest" to (plan.developmentExtension?.let { researchSha256File(Path.of(it.reference.directory).resolve(ResearchRunArtifacts.MANIFEST_FILE)) } ?: "none"),
        "extension-bank-manifest" to (plan.developmentExtension?.let { researchSha256File(Path.of(it.bankDirectory).resolve(ResearchRunArtifacts.MANIFEST_FILE)) } ?: "none"),
        "feature-schema" to NEURAL_BC_FEATURE_SCHEMA,
        "kernel" to "l2-state-l2-candidate-root-centered-candidate-plus-state-tensor-candidate-v1",
        "fit" to "equal-group-root-action-centered-mse-plus-ridge-kernel-norm-cholesky-v1",
        "selection" to "single-fixed-ridge-no-validation-selection-greedy-first-menu-tie-v1"))
    Files.createDirectories(destination)
    writeJsonAtomically(destination.resolve("plan.json"), plan); writeJsonAtomically(destination.resolve("bindings.json"), bindings)
    val model = fitRootActionKernel(development, plan.ridge)
    writeJsonAtomically(destination.resolve("model.json"), model)
    val restored = evidenceJson.decodeFromString<RootActionKernelModel>(Files.readString(destination.resolve("model.json")))
    require(restored == model && development.flatMap { it.features }.all { restored.score(it) == model.score(it) })
    writeJsonAtomically(destination.resolve("development.json"), development)
    // Only after the model is frozen and reloaded do we open held-out reference targets.
    val validationTargets = visibleV2ReferenceTargets(bank, previous.heldOutRegret.plan.reference, PositionBankScreenPartition.VALIDATION, previous.plan.hand)
    val validation = encode(validationTargets)
    fun metrics(roots: List<RootActionKernelTrainingRoot>): RootActionKernelFitMetrics {
        val groupCounts = roots.groupingBy { it.seedGroupId }.eachCount()
        var mse = 0.0; var regret = 0.0
        roots.forEach { root ->
            val predictions = root.features.map(model::score)
            val residuals = centeredActionResiduals(predictions, root.actionMeans)
            mse += residuals.map { it * it }.average() / groupCounts.size / groupCounts.getValue(root.seedGroupId)
            regret += root.actionMeans.max() - root.actionMeans[predictions.indices.maxBy { predictions[it] }]
        }
        return RootActionKernelFitMetrics(roots.size, groupCounts.size, roots.sumOf { it.features.size }, mse, regret / roots.size)
    }
    val comparison = previous.heldOutRegret.plan
    // Reuse the already authenticated original V2 selections and reference repetitions under their exact identities.
    fun screen(input: SavedRootPolicyInput): PositionBankScreenReport {
        val path = Path.of(input.directory)
        ResearchRunArtifacts.loadAndVerify(path, input.identity)
        return evidenceJson.decodeFromString<PositionBankScreenReport>(Files.readString(path.resolve("report.json"))).also {
            require(it.valid && it.researchRunIdentity == input.identity && it.plan.expectedBankIdentity == bank.bankIdentity)
        }
    }
    val reference = screen(comparison.reference); val baseline = screen(comparison.baseline)
    require(baseline.plan.mode == PositionBankScreenMode.SEARCH && baseline.plan.partition == PositionBankScreenPartition.VALIDATION)
    require(reference.selectedRootIds == validation.map { it.rootId } && baseline.selectedRootIds == reference.selectedRootIds)
    require(baseline.rows.count { it.policyId == comparison.baseline.policyId } == baseline.selectedRootIds.size * baseline.plan.repetitions)
    val inferenceMillis = mutableListOf<Double>()
    val rows = validation.flatMap { encoded ->
        val root = bankRoots.getValue(encoded.rootId)
        val expectedScores = encoded.features.map(model::score)
        val started = System.nanoTime()
        val live = rootActionKernelFeatures(root.information, root.reconstructedCandidates)
        val scores = live.map(restored::score)
        val selected = root.reconstructedCandidates[scores.indices.maxBy { scores[it] }].signature
        inferenceMillis += (System.nanoTime() - started) / 1_000_000.0
        require(scores == expectedScores && scores.all(Double::isFinite))
        val references = (0 until reference.plan.repetitions).map { rep ->
            reference.rows.single { it.rootId == root.rootId && it.policyId == comparison.reference.policyId && it.repetition == rep }
                .rootActionEstimates.associate { it.action.signature to it.meanBackedValue }
        }
        (0 until baseline.plan.repetitions).map { rep ->
            val base = baseline.rows.single { it.rootId == root.rootId && it.policyId == comparison.baseline.policyId && it.repetition == rep }
            require(base.disposition == PositionBankScreenDisposition.SEARCHED || base.disposition == PositionBankScreenDisposition.AUTOMATIC_SELECTION)
            base.searchDiagnostics?.let(::requireValidScreenSearch)
            val action = requireNotNull(base.chosen).signature
            val (oldRegret, newRegret, delta) = savedRootReferenceComparison(references, action, selected)
            SavedRootRegretRow(root.rootId, root.seedGroupId, rep, action, selected, oldRegret, newRegret, delta.average(), delta)
        }
    }
    require(rows.isNotEmpty() && rows.map { it.baselineRegret }.average() == previous.heldOutRegret.meanBaselineRegret)
    val report = RootActionKernelReport(bindings.identity, source, plan, metrics(development), metrics(validation),
        rows.map { it.baselineRegret }.average(), rows.map { it.candidateRegret }.average(), rows.count { it.baselineAction != it.candidateAction },
        rows, savedRootGroupRegrets(rows), inferenceMillis)
    writeJsonAtomically(destination.resolve("report.json"), report)
    ResearchRunArtifacts(destination, bindings.identity).also { artifacts ->
        listOf("plan.json", "bindings.json", "model.json", "development.json", "report.json").forEach(artifacts::register); artifacts.finalize()
    }
    ResearchRunArtifacts.loadAndVerify(destination, bindings.identity)
    return report
}
