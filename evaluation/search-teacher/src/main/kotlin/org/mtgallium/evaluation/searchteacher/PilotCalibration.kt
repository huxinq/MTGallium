package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.SearchTeacherPilotSpecification

internal const val PILOT_CANDIDATE_POLICY_ID = "search-bounded_rollout-mtgallium_visible_v2"
private const val PILOT_CALIBRATION_VERSION = "mono-red-pilot-calibration-v1"
private const val PILOT_REPETITIONS = 2
private const val PILOT_MAX_P95_MILLIS = 5_000.0

@Serializable
internal data class PilotCalibrationCell(
    val particles: Int,
    val simulations: Int,
    val leaf: LeafEvaluationConfig,
    val repetitions: List<TacticalProofLeafBenchmarkReport>,
    val deterministicAcrossRepetitions: Boolean,
    val completedTrialsPerRepetition: List<Int>,
    val solvedTrialsPerRepetition: List<Int>,
    val rolloutFallbacksPerRepetition: List<Int>,
    val p95TotalMillisPerRepetition: List<Double?>,
    val technicallyComplete: Boolean,
    val candidateQualified: Boolean,
    val failureReasons: List<String>,
)

@Serializable
internal data class PilotCalibrationReport(
    val schemaVersion: Int = 1,
    val version: String = PILOT_CALIBRATION_VERSION,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val host: String,
    val pilotSpecification: SearchTeacherPilotSpecification,
    val pilotSpecificationSha256: String,
    val oracleReportPath: String,
    val oracleReportSha256: String,
    val repetitionsPerCell: Int,
    val cells: List<PilotCalibrationCell>,
    val candidatePolicyId: String,
    val candidateQualified: Boolean,
    val passed: Boolean,
    val failureReasons: List<String>,
)

@Serializable
internal data class PilotFrozenProfileEvidence(
    val schemaVersion: Int = 1,
    val id: String,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val pilotSpecification: SearchTeacherPilotSpecification,
    val pilotSpecificationSha256: String,
    val calibrationReportPath: String,
    val calibrationReportSha256: String,
    val oracleReportSha256: String,
    val candidatePolicyId: String,
    val candidateQualified: Boolean,
)

internal class PilotCalibrationRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
) {
    private val candidateLeaf = LeafEvaluationConfig(
        LeafStateSource.BOUNDED_ROLLOUT,
        LeafEvaluator.MTGALLIUM_VISIBLE_V2,
    )
    private val sensitivityLeaf = LeafEvaluationConfig(
        LeafStateSource.BOUNDED_ROLLOUT,
        LeafEvaluator.ARGENTUM_BOARD_V1,
    )

    fun run(oracleReport: TacticalProofReport, oracleReportPath: Path): PilotCalibrationReport {
        require(oracleReport.oraclePassed) { "The machine tactical oracle must pass before pilot calibration" }
        val pilot = SearchTeacherPilotSpecification.frozenMonoRed()
        val cells = listOf(8, 16).flatMap { particles ->
            listOf(64, 256).flatMap { simulations ->
                listOf(candidateLeaf, sensitivityLeaf).map { leaf ->
                    println("Pilot calibration: particles=$particles simulations=$simulations leaf=$leaf")
                    runCell(oracleReport, particles, simulations, leaf)
                }
            }
        }
        val candidate = cells.single {
            it.particles == pilot.particles && it.simulations == pilot.simulations && it.leaf == pilot.leaf
        }
        val failures = buildList {
            cells.filterNot(PilotCalibrationCell::technicallyComplete).forEach { cell ->
                add("Calibration cell ${cell.particles}x${cell.simulations}/${cell.leaf} was not technically complete")
            }
            if (!candidate.candidateQualified) {
                add("The frozen 8-particle/64-simulation BR-M candidate did not qualify")
            }
        }
        val specificationHash = sha256(evidenceJson.encodeToString(SearchTeacherPilotSpecification.serializer(), pilot))
        return PilotCalibrationReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            host = calibrationHost(),
            pilotSpecification = pilot,
            pilotSpecificationSha256 = specificationHash,
            oracleReportPath = root.relativize(oracleReportPath).toString(),
            oracleReportSha256 = sha256File(oracleReportPath),
            repetitionsPerCell = PILOT_REPETITIONS,
            cells = cells,
            candidatePolicyId = PILOT_CANDIDATE_POLICY_ID,
            candidateQualified = candidate.candidateQualified,
            passed = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    private fun runCell(
        oracleReport: TacticalProofReport,
        particles: Int,
        simulations: Int,
        leaf: LeafEvaluationConfig,
    ): PilotCalibrationCell {
        val certifiedIds = oracleReport.cases.filter {
            it.authority == TacticalEvidenceAuthority.CERTIFIED
        }.map { it.definition.id }.toSet()
        val certifiedCases = TacticalProofCatalog.cases.filter { it.id in certifiedIds }
        require(certifiedCases.isNotEmpty()) { "Pilot calibration requires terminal-certified tactical cases" }
        val reports = (1..PILOT_REPETITIONS).map {
            TacticalProofLeafBenchmarkRunner(
                registry = registry,
                manifest = manifest,
                particles = particles,
                simulations = simulations,
                maxPolicyDecisions = 32,
                actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
                leafConfigurations = listOf(leaf),
            ).run(oracleReport, certifiedCases)
        }
        val results = reports.map { it.leafResults.single() }
        val signatures = results.map { result ->
            result.trials.map { trial -> "${trial.caseId}/${trial.hiddenVariant}:${trial.chosenSignature}" }
        }
        val deterministic = signatures.distinct().size == 1
        val failures = buildList {
            reports.forEachIndexed { index, report ->
                val result = results[index]
                if (!report.completed) add("repetition-${index + 1}: ${report.failureReasons.joinToString()}")
                if (result.completedTrials != result.totalTrials) {
                    add("repetition-${index + 1}: ${result.completedTrials}/${result.totalTrials} trials completed")
                }
                if (result.rolloutFallbacks != 0) {
                    add("repetition-${index + 1}: ${result.rolloutFallbacks} rollout fallbacks")
                }
            }
            if (!deterministic) add("selected signatures differed across repetitions")
        }
        val technicallyComplete = failures.isEmpty()
        val isCandidate = particles == 8 && simulations == 64 && leaf == candidateLeaf
        val candidateFailures = buildList {
            if (isCandidate) {
                results.forEachIndexed { index, result ->
                    if (result.solvedTrials != result.totalTrials) {
                        add("repetition-${index + 1}: ${result.solvedTrials}/${result.totalTrials} oracle trials solved")
                    }
                    if (result.hiddenVariantSelectionDisagreements != 0) {
                        add("repetition-${index + 1}: hidden-state selections disagreed")
                    }
                    if ((result.p95TotalMillis ?: Double.POSITIVE_INFINITY) > PILOT_MAX_P95_MILLIS) {
                        add("repetition-${index + 1}: p95 total latency exceeded ${PILOT_MAX_P95_MILLIS.toInt()} ms")
                    }
                }
            }
        }
        return PilotCalibrationCell(
            particles = particles,
            simulations = simulations,
            leaf = leaf,
            repetitions = reports,
            deterministicAcrossRepetitions = deterministic,
            completedTrialsPerRepetition = results.map(TacticalProofLeafBenchmarkResult::completedTrials),
            solvedTrialsPerRepetition = results.map(TacticalProofLeafBenchmarkResult::solvedTrials),
            rolloutFallbacksPerRepetition = results.map(TacticalProofLeafBenchmarkResult::rolloutFallbacks),
            p95TotalMillisPerRepetition = results.map(TacticalProofLeafBenchmarkResult::p95TotalMillis),
            technicallyComplete = technicallyComplete,
            candidateQualified = isCandidate && technicallyComplete && candidateFailures.isEmpty(),
            failureReasons = failures + candidateFailures,
        )
    }
}

internal fun renderPilotCalibration(report: PilotCalibrationReport): String = buildString {
    val candidate = report.cells.singleOrNull {
        it.particles == report.pilotSpecification.particles &&
            it.simulations == report.pilotSpecification.simulations &&
            it.leaf == report.pilotSpecification.leaf
    }
    appendLine("# Whether the proposed pilot settings meet the declared finite-case and runtime conditions")
    appendLine()
    appendLine("## What was observed")
    appendLine()
    appendLine(
        "The proposed settings were run twice on the finite terminal-position suite. They " +
            "${if (report.candidateQualified) "met" else "did not meet"} the declared conditions: every expected " +
            "trial had to finish, both repetitions had to choose the same recorded actions, every accepted-set " +
            "trial had to be solved, no substituted rollout action could occur, and each repetition's p95 total " +
            "time had to be at most 5,000 ms."
    )
    appendLine()
    appendLine(
        "For example, settings fail this procedure if the second repetition chooses a different action in one " +
            "supplied case even when both runs finish. Meeting the conditions applies only to the repository-authored " +
            "terminal cases and recorded machine; it does not establish strong nonterminal play, performance against " +
            "people, or permission to store the settings for later experiments."
    )
    appendLine()
    appendLine(
        "Candidate cell observations: ${candidate?.solvedTrialsPerRepetition?.joinToString(" / ") ?: "not present"} " +
            "accepted-set trials solved per repetition; p95 total times " +
            "${candidate?.p95TotalMillisPerRepetition?.joinToString(" / ") { it?.let { value -> "%.1f".format(value) } ?: "n/a" } ?: "not present"} ms."
    )
    appendLine()
    appendLine("## Implementation trace")
    appendLine()
    appendLine("- Frozen profile: `${report.pilotSpecification.id}`")
    appendLine("- Candidate policy: `${report.candidatePolicyId}`")
    appendLine(
        "- Expected-answer report: `${report.oracleReportSha256}`. It is repository-authored and shares the " +
            "rules engine and action representation; its hash identifies the input but does not make it independent " +
            "Magic truth."
    )
    appendLine()
    appendLine("## Results by settings")
    appendLine()
    appendLine("| Hidden-position samples | Simulations | Leaf implementation | Accepted-set trials solved | p95 total ms | Same choices in both repetitions | Every expected trial finished | Proposed settings met every condition |")
    appendLine("|---:|---:|---|---|---|---|---|---|")
    report.cells.forEach { cell ->
        appendLine(
            "| ${cell.particles} | ${cell.simulations} | `${cell.leaf}` | " +
                "${cell.solvedTrialsPerRepetition.joinToString(" / ")} | " +
                "${cell.p95TotalMillisPerRepetition.joinToString(" / ") { it?.let { value -> "%.1f".format(value) } ?: "n/a" }} | " +
                "${cell.deterministicAcrossRepetitions} | ${cell.technicallyComplete} | ${cell.candidateQualified} |"
        )
    }
    if (report.failureReasons.isNotEmpty()) {
        appendLine()
        appendLine("## Failures")
        appendLine()
        report.failureReasons.forEach { appendLine("- $it") }
    }
}
