package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.research.run.ResearchRunArtifacts

@Serializable
internal data class SavedRootPolicyInput(val directory: String, val identity: String, val policyId: String)

@Serializable
internal data class SavedRootRegretPlan(
    val bankDirectory: String, val bankIdentity: String,
    val reference: SavedRootPolicyInput, val baseline: SavedRootPolicyInput, val candidate: SavedRootPolicyInput,
)

@Serializable
internal data class SavedRootRegretRow(
    val rootId: String, val seedGroupId: String, val repetition: Int,
    val baselineAction: String, val candidateAction: String,
    val baselineRegret: Double, val candidateRegret: Double,
    /** Positive means the candidate selected a higher reference mean. */
    val candidateMinusBaseline: Double,
    val candidateMinusBaselineByReferenceRepetition: List<Double>,
)

@Serializable
internal data class SavedRootRegretReport(
    val plan: SavedRootRegretPlan, val roots: Int, val seedGroups: Int, val changedChoices: Int,
    val meanBaselineRegret: Double, val meanCandidateRegret: Double,
    val rows: List<SavedRootRegretRow>,
    val interpretation: String = "Equal-root and equal-selection-repetition descriptive regret against the maximum retained action mean, in original backed-value units. Reference repetitions share prepared posterior approximations; adaptive backups are not independent observations. This is reference-policy/settlement agreement, not observed gameplay strength or an unbiased estimate of optimal-action regret.",
)

/** Complete identical semantic menus are required; an absent chosen action is never assigned zero value. */
internal fun savedRootReferenceComparison(
    reference: List<Map<String, Double>>, baseline: String, candidate: String,
): Triple<Double, Double, List<Double>> {
    require(reference.isNotEmpty() && reference.first().isNotEmpty())
    val menu = reference.first().keys
    require(reference.all { it.keys == menu && it.values.all { v -> v.isFinite() && v in -1.0..1.0 } })
    require(baseline in menu && candidate in menu)
    val means = menu.associateWith { action -> reference.map { it.getValue(action) }.average() }
    val best = means.values.max()
    return Triple(best - means.getValue(baseline), best - means.getValue(candidate),
        reference.map { it.getValue(candidate) - it.getValue(baseline) })
}

/** Read-only analysis of authenticated producer reports; no parallel evidence verifier or simulation. */
internal object SavedRootRegret {
    fun analyze(plan: SavedRootRegretPlan): SavedRootRegretReport {
        val bank = loadVerifiedRealGamePositionBank(Path.of(plan.bankDirectory), plan.bankIdentity)
        fun load(input: SavedRootPolicyInput): PositionBankScreenReport {
            val path = Path.of(input.directory)
            ResearchRunArtifacts.loadAndVerify(path, input.identity)
            return evidenceJson.decodeFromString<PositionBankScreenReport>(Files.readString(path.resolve("report.json"))).also {
                require(it.valid && it.researchRunIdentity == input.identity)
                require(it.plan.expectedBankIdentity == bank.bankIdentity)
                require(it.plan.policies.any { policy -> policy.search.id == input.policyId })
                require(it.rows.filter { row -> row.policyId == input.policyId }.size ==
                    it.selectedRootIds.size * it.plan.repetitions)
            }
        }
        val reference = load(plan.reference)
        val baseline = load(plan.baseline)
        val candidate = load(plan.candidate)
        require(reference.plan.mode == PositionBankScreenMode.ACTION_CONDITIONAL)
        require(baseline.plan.mode == PositionBankScreenMode.SEARCH && candidate.plan.mode == PositionBankScreenMode.SEARCH)
        require(baseline.selectedRootIds == candidate.selectedRootIds && baseline.selectedRootIds == reference.selectedRootIds)
        require(baseline.plan.repetitions == candidate.plan.repetitions)
        require(baseline.plan.searchSeedDomain == candidate.plan.searchSeedDomain)
        val roots = bank.roots.associateBy { it.rootId }
        val rows = baseline.selectedRootIds.flatMap { rootId ->
            val root = roots.getValue(rootId)
            val menu = root.reconstructedCandidates.map { it.signature }.toSet()
            val values = (0 until reference.plan.repetitions).map { repetition ->
                val row = reference.rows.single { it.rootId == rootId && it.policyId == plan.reference.policyId && it.repetition == repetition }
                require(row.disposition == PositionBankScreenDisposition.ACTION_CONDITIONAL)
                require(row.rootActionEstimates.size == menu.size)
                row.rootActionEstimates.associate { estimate ->
                    requireValidScreenSearch(estimate.diagnostics)
                    estimate.action.signature to estimate.meanBackedValue
                }.also { require(it.keys == menu) }
            }
            (0 until baseline.plan.repetitions).map { repetition ->
                fun chosen(report: PositionBankScreenReport, id: String) = report.rows.single {
                    it.rootId == rootId && it.policyId == id && it.repetition == repetition
                }.also {
                    require(it.disposition == PositionBankScreenDisposition.SEARCHED ||
                        it.disposition == PositionBankScreenDisposition.AUTOMATIC_SELECTION)
                    it.searchDiagnostics?.let(::requireValidScreenSearch)
                }
                val old = chosen(baseline, plan.baseline.policyId)
                val new = chosen(candidate, plan.candidate.policyId)
                require(old.searchSeed == new.searchSeed)
                val oldAction = requireNotNull(old.chosen).signature
                val newAction = requireNotNull(new.chosen).signature
                val (oldRegret, newRegret, differences) = savedRootReferenceComparison(values, oldAction, newAction)
                SavedRootRegretRow(rootId, root.seedGroupId, repetition, oldAction, newAction,
                    oldRegret, newRegret, differences.average(), differences)
            }
        }
        require(rows.isNotEmpty())
        return SavedRootRegretReport(plan, baseline.selectedRootIds.size, rows.map { it.seedGroupId }.distinct().size,
            rows.count { it.baselineAction != it.candidateAction }, rows.map { it.baselineRegret }.average(),
            rows.map { it.candidateRegret }.average(), rows)
    }
}
