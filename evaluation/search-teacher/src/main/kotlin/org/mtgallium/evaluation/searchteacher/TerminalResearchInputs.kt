package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import org.mtgallium.research.run.ResearchRunArtifacts

internal fun loadRetainedTerminalResearchScreen(
    reference: CloningComparisonInput,
    expected: PositionBankScreenPlan,
): PositionBankScreenReport {
    val path = Path.of(reference.directory)
    val manifest = ResearchRunArtifacts.loadAndVerify(path, reference.researchRunIdentity)
    require(manifest.artifacts.map { it.relativePath }.containsAll(listOf("report.json", "plan.json")))
    return evidenceJson.decodeFromString<PositionBankScreenReport>(Files.readString(path.resolve("report.json"))).also {
        require(it.researchRunIdentity == reference.researchRunIdentity && it.plan == expected)
        require(it.plan == evidenceJson.decodeFromString<PositionBankScreenPlan>(Files.readString(path.resolve("plan.json"))))
    }
}

internal fun requireProductionRolloutControl(
    report: PositionBankScreenReport,
    plan: PositionBankScreenPlan,
    expectedRootIds: List<String> = plan.rootIds,
) {
    require(plan.mode == PositionBankScreenMode.ROOT_ROLLOUT_SELECTION && plan.repetitions == 1 && plan.policies.size == 1)
    require(expectedRootIds.isNotEmpty() && expectedRootIds == expectedRootIds.distinct().sorted())
    require(report.plan == plan && report.valid && report.selectedRootIds == expectedRootIds && report.rows.size == expectedRootIds.size)
    require(report.rows.map { it.rootId }.toSet() == expectedRootIds.toSet())
    report.rows.forEach { row ->
        require(row.disposition == PositionBankScreenDisposition.ROLLOUT_SELECTED && row.chosen != null)
        require(
            row.searchDiagnostics == null && row.searchRootValue == null &&
                row.candidateStatistics.isEmpty() && row.rootActionEstimates.isEmpty(),
        )
        require(
            row.rolloutPolicyDecision?.declaredPolicyId == "root-argentum-production-rollout-v2" &&
                row.rolloutPolicyDecision.replacement == null,
        )
    }
}
