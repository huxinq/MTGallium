package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import org.mtgallium.research.run.ResearchCostRecorder
import org.mtgallium.research.run.ResearchMeasuredWork

internal data class TerminalTargetStage(
    val reference: SavedRootPolicyInput,
    val report: PositionBankScreenReport,
)

internal fun terminalResearchPilotRoots(roots: List<RealGamePositionBankRoot>): List<String> {
    require(roots.map { it.seedGroupId }.distinct().size >= 2)
    val largest = roots.sortedWith(
        compareByDescending<RealGamePositionBankRoot> { it.reconstructedCandidates.size }.thenBy { it.rootId },
    ).first()
    val other = roots.filter { it.seedGroupId != largest.seedGroupId }.minBy { it.rootId }
    return listOf(largest.rootId, other.rootId).sorted()
}

internal fun terminalMeasuredWork(
    report: PositionBankScreenReport,
    bank: RealGamePositionBankReport,
): ResearchMeasuredWork {
    val accounting = terminalRootScreenAccounting(report, bank)
    return ResearchMeasuredWork(
        evidenceIdentity = report.researchRunIdentity,
        counts = mapOf(
            "requested-continuations" to accounting.requestedContinuations.toLong(),
            "terminal-samples" to accounting.completedTerminalSamples.toLong(),
            "failed-attempts" to accounting.nonGameFailedAttempts.toLong(),
            "unexecuted-continuations" to accounting.unexecutedContinuations.toLong(),
            "policy-decisions" to accounting.continuationPolicyDecisions.toLong(),
        ),
        accumulatedComponentMillis = mapOf(
            "reconstruction" to report.rows.sumOf { it.reconstructionMillis ?: 0.0 },
            "selection" to accounting.accumulatedSelectionMillis,
        ),
    )
}

internal fun runTerminalTargetStage(
    name: String,
    data: TerminalStudyData,
    bank: RealGamePositionBankReport,
    output: Path,
    runner: PositionBankScreenRunner,
    costs: ResearchCostRecorder,
    workers: Int,
): TerminalTargetStage {
    val report = costs.measure(
        "$name-targets",
        reused = data.retained != null,
        describe = { report ->
            if (data.retained != null) ResearchMeasuredWork(report.researchRunIdentity)
            else terminalMeasuredWork(report, bank)
        },
        validate = { terminalRootTrainingData(bank, it, data.plan.partition) },
    ) {
        data.retained?.let {
            loadTerminalRootScreen(it, bank).also { retained ->
                require(retained.plan == data.plan) { "Retained target plan changed" }
            }
        } ?: runner.run(data.plan, output.resolve("$name-terminal"), workers)
    }
    val reference = data.retained ?: SavedRootPolicyInput(
        directory = output.resolve("$name-terminal").toString(),
        identity = report.researchRunIdentity,
        policyId = data.plan.policies.single().search.id,
    )
    return TerminalTargetStage(reference, report)
}

/** Pilot coordinates overlap primary samples; they never add independent target evidence. */
internal fun terminalResearchPilotPlan(
    plan: PositionBankScreenPlan,
    rootIds: List<String>,
): PositionBankScreenPlan = plan.copy(
    rootLimit = rootIds.size,
    rootIds = rootIds,
    repetitions = 1,
    terminalContinuation = requireNotNull(plan.terminalContinuation).copy(samplesPerAction = 2),
)

internal fun projectedTerminalCollectionSeconds(
    accounting: TerminalRootScreenAccounting,
    newContinuations: Long,
    workers: Int,
): Double = accounting.accumulatedSelectionMillis / accounting.completedTerminalSamples * newContinuations / workers / 1000.0
