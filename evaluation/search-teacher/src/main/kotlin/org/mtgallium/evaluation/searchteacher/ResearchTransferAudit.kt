package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.research.run.*
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

@Serializable
internal data class ResearchTransferLink(
    val name: String,
    val model: RootKernelFitReference,
    val bank: CloningComparisonInput,
    val terminalTargets: SavedRootPolicyInput,
    val rolloutControl: CloningComparisonInput,
    val gameplay: CloningComparisonInput,
    val candidatePolicyId: String,
) {
    init { require(name.isNotBlank() && candidatePolicyId.isNotBlank()) }
}

@Serializable
internal data class ResearchTransferAuditPlan(val schemaVersion: Int = 1, val links: List<ResearchTransferLink>) {
    init { require(schemaVersion == 1 && links.isNotEmpty() && links.map { it.name }.distinct().size == links.size) }
}

@Serializable
internal data class ResearchTransferObservation(
    val link: ResearchTransferLink,
    val savedPositionComparison: TerminalStudyComparison,
    val gameplaySource: PolicySourceProvenance,
    val population: SearchTeacherSequentialPopulation,
    val disposition: PairedSequentialDisposition,
    val inspectedCandidatePointRate: Double,
    val candidateSimulations: Int, val controlSimulations: Int,
    val meanSearchedDecisionCostRatio: Double,
    val searchedTimePerGameRatio: Double,
    val evidenceForStrengthIncrease: Boolean,
    val costGatePassed: Boolean,
    val sharedTrainingAndGameplayGroups: List<String>,
    val sharedScreenAndGameplayGroups: List<String>,
)

@Serializable
internal data class ResearchTransferAuditReport(
    val identity: String, val plan: ResearchTransferAuditPlan,
    val observations: List<ResearchTransferObservation>,
    val positiveScreens: Int, val positiveScreensWithStrengthEvidence: Int,
    val inconclusiveGameplay: Int,
    val interpretation: String = "Authenticated links between a frozen CAST-context root-rollout model's saved-position comparison and its actual paired gameplay treatment. Screen gains and gameplay effects have different populations and meanings. Inconclusive gameplay is not a failed or successful transfer measurement, and this selected/adaptive set is not an unbiased estimate of benchmark predictive accuracy. Budgets, costs, overlap and original source identities are explicit; no pooled win rate, correlation claim or promotion is produced.",
)

/** A link must match the deployed model and isolated intervention, not merely a filename or policy name. */
internal fun requireKernelRolloutTransferPolicy(link: ResearchTransferLink, report: SearchTeacherCalibrationReport) {
    requireKernelRolloutTransferDescriptors(link.model, link.candidatePolicyId, report.plan.control,
        report.plan.candidates.single(), requireNotNull(report.sequentialRule))
    report.policies.forEach { policy ->
        val actual = policy.descriptor.policy(report.plan.baseSeed)
        require(policy.rootRolloutPolicy == actual.effectiveRootRolloutPolicy().behaviorSpecification)
        require(policy.opponentRolloutPolicy == actual.effectiveOpponentRolloutPolicy().behaviorSpecification)
    }
}

internal fun requireKernelRolloutTransferDescriptors(model: RootKernelFitReference, candidateId: String,
    control: SearchTeacherCalibrationPolicy, candidate: SearchTeacherCalibrationPolicy, rule: PairedSequentialRule) {
    require(rule.nullPointRate == 0.5 && rule.targetPointRate == 0.5) { "Strength-increase link requires the declared parity comparison" }
    require(candidate.id == candidateId && candidate.rootKernelRolloutFit == model)
    require(control.rootKernelRolloutFit == null && control.rootCloningFit == null && control.rootRolloutPolicy == null && control.rolloutHeuristicProbability == 1.0)
    require(control.opponentRolloutPolicy in listOf(null, SearchTeacherCalibrationRolloutPolicy.PRODUCTION_ARGENTUM))
    require(candidate.copy(id = control.id, simulations = control.simulations, rootKernelRolloutFit = null) == control) {
        "Gameplay changed controls beyond the kernel rollout and declared simulation budget"
    }
}

internal fun inconclusiveTransferGameplay(disposition: PairedSequentialDisposition): Boolean = disposition in listOf(
    PairedSequentialDisposition.FUTILITY, PairedSequentialDisposition.BUDGET_EXHAUSTED, PairedSequentialDisposition.BOTH_BOUNDARIES_CROSSED)

private data class AuditedTransferLink(
    val observation: ResearchTransferObservation,
    val manifestHashes: Map<String, String>,
)

internal class ResearchTransferAuditRunner(private val repository: Path) {
    fun run(plan: ResearchTransferAuditPlan, output: Path): ResearchTransferAuditReport {
        val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "screen-to-gameplay transfer audit")
        require(!Files.exists(destination))
        val source = ResearchRunProvenance.capture(repository).also { it.requireReady() }
        require(!source.outerDirty && !source.engineDirty)
        val manifests = sortedMapOf<String, String>()
        val observations = plan.links.map { link ->
            val audited = auditLink(link)
            manifests.putAll(audited.manifestHashes)
            audited.observation
        }
        val bindings = ResearchRunBindings(protocol = "research-screen-gameplay-transfer-v1", material = mapOf(
            "plan" to sha256(evidenceJson.encodeToString(ResearchTransferAuditPlan.serializer(), plan)),
            "analysis-source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
            "input-manifests" to sha256(manifests.entries.joinToString("\n") { "${it.key}=${it.value}" })))
        val report = ResearchTransferAuditReport(bindings.identity, plan, observations,
            observations.count { it.savedPositionComparison.equalGroupMeanDifference > 0 },
            observations.count { it.savedPositionComparison.equalGroupMeanDifference > 0 && it.evidenceForStrengthIncrease },
            observations.count { inconclusiveTransferGameplay(it.disposition) })
        writeJsonAtomically(destination.resolve("bindings.json"), bindings)
        writeJsonAtomically(destination.resolve("report.json"), report)
        writeJsonAtomically(destination.resolve("plan.json"), plan)
        finalizeResearchWorkflowArtifacts(destination, bindings.identity)
        return report
    }

    private fun auditLink(link: ResearchTransferLink): AuditedTransferLink {
        link.model.loadFrozenModel()
        val bank = loadVerifiedRealGamePositionBank(Path.of(link.bank.directory), link.bank.researchRunIdentity)
        val terminal = loadTerminalRootScreen(link.terminalTargets, bank)
        requireProductionTerminalTarget(terminal.plan)
        val targets = terminalRootTrainingData(bank, terminal, PositionBankScreenPartition.VALIDATION)
        val controlPlan = evidenceJson.decodeFromString<PositionBankScreenPlan>(Files.readString(Path.of(link.rolloutControl.directory).resolve("plan.json")))
        val control = loadRetainedTerminalResearchScreen(link.rolloutControl, controlPlan)
        val controlRoots = selectPositionScreenRoots(controlPlan, bank.roots.filter { it.partition.name == controlPlan.partition.name }.sortedBy { it.rootId })
        requireProductionRolloutControl(control, controlPlan, controlRoots.map { it.rootId })
        require(controlPlan.expectedBankIdentity == bank.bankIdentity && control.selectedRootIds == terminal.selectedRootIds)
        require(controlPlan.policies.single().search.rootKernelRolloutFit == null && controlPlan.policies.single().search.rootCloningFit == null)
        val model = CompiledRootActionKernel(link.model.loadFrozenModel().model)
        val rows = compareTerminalTargetChoices(bank, terminal, targets, model) { root, _ -> requireNotNull(control.rows.single { it.rootId == root.rootId }.chosen).signature }
        val comparison = terminalTargetComparison(rows)
        val gamePath = Path.of(link.gameplay.directory)
        val gameplay = loadCompletedSequentialCalibration(gamePath, link.gameplay.researchRunIdentity)
        require(gameplay.sourceProvenance.argentum.revision == terminal.sourceProvenance.argentum.revision) { "Target and gameplay engine differ" }
        requireKernelRolloutTransferPolicy(link, gameplay)
        val pairs = completedSequentialBankPairs(gameplay)
        val candidate = gameplay.plan.candidates.single()
        requireTransferDecisionDiagnostics(pairs, candidate, gameplay.plan.control)
        val costs = gameplay.comparisons.single().operationalByPolicy
        val candidateCost = costs.single { it.search.policyId == candidate.id }
        val controlCost = costs.single { it.search.policyId == gameplay.plan.control.id }
        val decisionCostRatio = requireNotNull(candidateCost.search.latencyMeanMillis) / requireNotNull(controlCost.search.latencyMeanMillis)
        require(decisionCostRatio.isFinite() && decisionCostRatio > 0)
        val searchTimePerGameRatio = requireNotNull(candidateCost.searchedMillisPerGame) / requireNotNull(controlCost.searchedMillisPerGame)
        val modelPath = Path.of(link.model.directory)
        require(ResearchRunArtifacts.loadAndVerify(modelPath, link.model.researchRunIdentity).artifacts.any { it.relativePath == "development.json" })
        val trainingGroups = evidenceJson.decodeFromString<List<RootActionKernelTrainingRoot>>(Files.readString(modelPath.resolve("development.json"))).map { it.seedGroupId }.toSet()
        val gameplayGroups = pairs.map { realGamePositionSeedGroup(gameplay.deckHash, gameplay.cardPoolHash, it.seed) }.toSet()
        val screenGroups = targets.map { it.seedGroupId }.toSet()
        val manifests = sortedMapOf<String, String>()
        listOf(link.model.directory, link.bank.directory, link.terminalTargets.directory, link.rolloutControl.directory, link.gameplay.directory).forEach { dir ->
            manifests[dir] = researchSha256File(Path.of(dir).resolve(ResearchRunArtifacts.MANIFEST_FILE))
        }
        val result = requireNotNull(gameplay.sequentialResult)
        val observation = ResearchTransferObservation(
            link = link,
            savedPositionComparison = comparison,
            gameplaySource = gameplay.sourceProvenance,
            population = requireNotNull(gameplay.sequentialPopulation),
            disposition = result.disposition,
            inspectedCandidatePointRate = requireNotNull(gameplay.comparisons.single().candidatePointRate),
            candidateSimulations = candidate.simulations,
            controlSimulations = gameplay.plan.control.simulations,
            meanSearchedDecisionCostRatio = decisionCostRatio,
            searchedTimePerGameRatio = searchTimePerGameRatio,
            evidenceForStrengthIncrease = result.disposition == PairedSequentialDisposition.ABOVE_NULL,
            costGatePassed = decisionCostRatio <= 1.0,
            sharedTrainingAndGameplayGroups = (trainingGroups intersect gameplayGroups).sorted(),
            sharedScreenAndGameplayGroups = (screenGroups intersect gameplayGroups).sorted(),
        )
        return AuditedTransferLink(observation, manifests)
    }
}

private fun requireTransferDecisionDiagnostics(
    pairs: List<SearchBudgetFrontierPair>,
    candidate: SearchTeacherCalibrationPolicy,
    control: SearchTeacherCalibrationPolicy,
) {
    for (game in pairs.flatMap { it.games }) {
        for (seat in game.seatDiagnostics.values) {
            for (decision in seat.searchDecisionsDetail) {
                val diagnostics = decision.searchDiagnostics
                val candidateSeat = seat.policyId == candidate.id
                require(diagnostics.freshSimulations == if (candidateSeat) candidate.simulations else control.simulations)
                require(diagnostics.reusedSimulations == 0 && diagnostics.rootSelectionGuidance == null)
                require(diagnostics.rootRolloutPolicyId == if (candidateSeat) "cast-context-root-kernel-rollout-v1" else "root-argentum-production-rollout-v2")
                require(diagnostics.opponentRolloutPolicyId == "opponent-argentum-production-rollout-v2")
                requireValidScreenSearch(diagnostics)
            }
        }
    }
}
