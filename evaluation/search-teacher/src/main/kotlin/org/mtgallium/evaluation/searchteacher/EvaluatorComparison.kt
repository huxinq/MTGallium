package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.atanh
import kotlin.math.tanh
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.infoset.core.InformationStateEvaluator
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.searchteacher.MonoRedInformationEvaluator
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluator
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluatorSettings
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val EVALUATOR_COMPARISON_VERSION = "evaluator-comparison-v1"

internal object EvaluatorImplementationSources {
    const val VISIBLE_V2 =
        "agent/search-teacher/src/main/kotlin/org/mtgallium/agent/searchteacher/MonoRedInformationEvaluator.kt"
    const val TACTICAL_V3 =
        "agent/search-teacher/src/main/kotlin/org/mtgallium/agent/searchteacher/MonoRedTacticalEvaluator.kt"
}

@Serializable
internal data class TacticalReferenceEntry(
    val suiteVersion: String,
    val caseId: String,
    val horizon: TacticalHorizon? = null,
    val authority: TacticalEvidenceAuthority,
    val certificationStatus: TacticalCertificationStatus = TacticalCertificationStatus.NOT_REQUESTED,
    val acceptedSignatureCount: Int = 0,
    val discriminatory: Boolean? = null,
    val diagnostic: String? = null,
)

@Serializable
internal data class TacticalReferenceReport(
    val schemaVersion: Int = 1,
    val documentKind: String = "tactical-reference-report-v1",
    val generatedAtUtc: String,
    val terminalOrdering: String = "WIN > DRAW > LOSS",
    val policyConstraint: String = "Equal actor information states must take the same action; policies may diverge only after an observable split.",
    val entries: List<TacticalReferenceEntry>,
    val machineProvedCases: Int,
    val humanAcceptedSetCases: Int,
    val diagnosticCases: Int,
    val disputedCases: Int,
    val disputesBlockEvaluation: Boolean = true,
    val legacyDiagnosticCases: Int,
    val proofReportSha256: String,
    val horizonPacketSha256: String,
    val horizonConformanceSha256: String,
    val horizonAcceptedSetSha256: String,
)

@Serializable
internal data class EvaluatorFactorialCell(
    val configuration: EvaluatorFactorialConfiguration,
    val summary: TacticalHorizonBenchmarkResult,
)

@Serializable
internal data class EvaluatorFactorialReport(
    val cases: List<String>,
    val structuralCells: List<EvaluatorFactorialCell>,
    val scaleCells: List<EvaluatorFactorialCell>,
)

@Serializable
internal data class EvaluatorComponentDiagnosis(
    val primaryAttribution: String,
    val fixedSimulationDelta: Int,
    val fixedTimeDelta: Int,
    val v2OnlyAcceptedCasesAtFixedSimulation: List<String>,
    val v3OnlyAcceptedCasesAtFixedSimulation: List<String>,
    val bothRejectedCasesAtFixedSimulation: List<String>,
    val horizonDeltasAtFixedSimulation: Map<TacticalHorizon, Int>,
    val horizonDeltasAtFixedTime: Map<TacticalHorizon, Int>,
    val pairedStructuralCellDeltas: List<Int>,
    val evidence: List<String>,
    val caveats: List<String>,
)

@Serializable
internal data class EvaluatorComparisonReport(
    val schemaVersion: Int = 1,
    val documentKind: String = "evaluator-comparison-v1",
    val comparisonVersion: String = EVALUATOR_COMPARISON_VERSION,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceChanges: List<String>,
    val referenceReportSha256: String,
    val v2ImplementationSha256: String,
    val v3ImplementationSha256: String,
    val v2EvaluatorId: String,
    val v3ConfigurationId: String,
    val stages: List<EvaluatorStageResult>,
    val componentDiagnosis: EvaluatorComponentDiagnosis,
    val conclusion: String,
    val limitations: List<String>,
)

internal data class EvaluatorComparisonBundle(
    val references: TacticalReferenceReport,
    val stage0: EvaluatorStage0Evidence,
    val stage1: TacticalHorizonBenchmarkReport,
    val stage2: TacticalHorizonBenchmarkReport,
    val stage3: EvaluatorFactorialReport,
    val report: EvaluatorComparisonReport,
)

internal class EvaluatorComparisonRunner(
    private val root: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val manifest: DeckManifest,
) {
    private val evidence = EvidenceStore(root)
    private val packetPath = evidence.work("tactical-authoring/tactical-horizon-v2.authoring.json")
    private val acceptedSetPath = evidence.work("tactical-authoring/tactical-horizon-v2.accepted-set.json")
    private val conformancePath = evidence.work("tactical-authoring/tactical-horizon-v2.conformance.json")
    private val proofReportPath = evidence.work("tactical-proof/report.json").takeIf(Files::isRegularFile)
        ?: evidence.latest("tactical-proof/report.json")
    private val fixedSimulationBudget = 64
    private val fixedWallClockMillis = 50L
    private val particles = 8
    private val v3 = MonoRedTacticalEvaluator()

    fun run(): EvaluatorComparisonBundle {
        val review = loadValidatedTacticalAcceptedSet(packetPath, acceptedSetPath, conformancePath)
        val proof = loadProofReport()
        val references = referenceReport(review, proof)
        require(references.disputedCases == 0) { "Tactical accepted-set conflicts must be resolved before comparison" }
        val stage0 = stage0()
        val stage1 = benchmark(review, fixedSimulationBudget, null, comparisonLeafs())
        val stage2 = benchmark(review, 4_096, fixedWallClockMillis, comparisonLeafs())
        val stage3 = stage3(review)
        val diagnosis = diagnose(stage1, stage2, stage3)
        val referenceHash = sha256(evidenceJson.encodeToString(TacticalReferenceReport.serializer(), references))
        val stages = summarizeStages(stage0, stage1, stage2, stage3)
        val report = EvaluatorComparisonReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            sourceChanges = gitOutput(root, "status", "--short").lines().filter(String::isNotBlank),
            referenceReportSha256 = referenceHash,
            v2ImplementationSha256 = sha256File(root.resolve(EvaluatorImplementationSources.VISIBLE_V2)),
            v3ImplementationSha256 = sha256File(root.resolve(EvaluatorImplementationSources.TACTICAL_V3)),
            v2EvaluatorId = MonoRedInformationEvaluator.id,
            v3ConfigurationId = v3.configurationId,
            stages = stages,
            componentDiagnosis = diagnosis,
            conclusion = "The bounded comparison diagnoses evaluator behavior; it does not by itself justify changing the current v2 production baseline.",
            limitations = listOf(
                "Accepted-set agreement is a tactical screening metric, not a game-win estimate.",
                "The comparison is deck-specific and uses the recorded finite case population.",
            ),
        )
        return EvaluatorComparisonBundle(references, stage0, stage1, stage2, stage3, report)
    }

    private fun loadProofReport(): TacticalProofReport {
        require(Files.isRegularFile(proofReportPath)) { "Missing corrected tactical proof report: $proofReportPath" }
        val proof = evidenceJson.decodeFromString<TacticalProofReport>(Files.readString(proofReportPath))
        require(proof.oraclePassed)
        return proof
    }

    private fun referenceReport(
        review: ValidatedTacticalAcceptedSet,
        proof: TacticalProofReport,
    ): TacticalReferenceReport {
        val proofEntries = proof.cases.map { result ->
            TacticalReferenceEntry(
                suiteVersion = proof.suiteVersion,
                caseId = result.definition.id,
                authority = result.authority,
                certificationStatus = result.certificationStatus,
                acceptedSignatureCount = result.acceptedSignatures.size,
                discriminatory = !result.nondiscriminating,
                diagnostic = result.diagnostic,
            )
        }
        val horizonById = TacticalHorizonCatalog.cases.associateBy(TacticalHorizonCase::id)
        val conformanceById = review.conformance.cases.associateBy(TacticalHorizonCaseAudit::caseId)
        val horizonEntries = review.labels.labels.sortedBy { it.caseId }.map { label ->
            val conformance = conformanceById.getValue(label.caseId)
            TacticalReferenceEntry(
                suiteVersion = review.packet.suiteVersion,
                caseId = label.caseId,
                horizon = horizonById.getValue(label.caseId).horizon,
                authority = if (conformance.authority == TacticalEvidenceAuthority.CERTIFIED) {
                    TacticalEvidenceAuthority.CERTIFIED
                } else {
                    TacticalEvidenceAuthority.HUMAN_AUTHORITY
                },
                certificationStatus = conformance.certificationStatus,
                acceptedSignatureCount = label.acceptedChoices.size,
                discriminatory = true,
                diagnostic = conformance.diagnostic ?: label.scenarioNotes.ifBlank { null },
            )
        }
        val legacyEntries = TacticalBenchmarkCatalog.cases.map { case ->
            TacticalReferenceEntry(
                suiteVersion = "legacy-tactical-v1",
                caseId = case.id,
                authority = TacticalEvidenceAuthority.DIAGNOSTIC,
                diagnostic = "LEGACY_EXPECTATION_NOT_REVALIDATED_UNDER_ACCEPTED_SET_AUTHORITY",
            )
        }
        val entries = proofEntries + horizonEntries + legacyEntries
        return TacticalReferenceReport(
            generatedAtUtc = Instant.now().toString(),
            entries = entries,
            machineProvedCases = entries.count { it.authority == TacticalEvidenceAuthority.CERTIFIED },
            humanAcceptedSetCases = entries.count { it.authority == TacticalEvidenceAuthority.HUMAN_AUTHORITY },
            diagnosticCases = entries.count { it.authority == TacticalEvidenceAuthority.DIAGNOSTIC },
            disputedCases = 0,
            legacyDiagnosticCases = legacyEntries.size,
            proofReportSha256 = sha256File(proofReportPath),
            horizonPacketSha256 = sha256File(packetPath),
            horizonConformanceSha256 = sha256File(conformancePath),
            horizonAcceptedSetSha256 = sha256File(acceptedSetPath),
        )
    }

    private fun stage0(): EvaluatorStage0Evidence {
        val horizonStates = TacticalHorizonCatalog.cases.map { case ->
            TacticalHorizonScenarioFactory(registry, manifest).create(case).informationState("p0")
        }
        val exactMatches = horizonStates.count { information ->
            MonoRedInformationEvaluator.evaluate(information, "p0").toBits() ==
                referenceV2(information, "p0").toBits()
        }
        val hiddenPairs = TacticalProofCatalog.cases.map { case ->
            val factory = TacticalProofScenarioFactory(registry, manifest, SearchActionSpaceProfile.RULES_EXACT_V1)
            val first = factory.create(case, 1).informationState(case.rootPlayer)
            val second = factory.create(case, 2).informationState(case.rootPlayer)
            v3.evaluateDetailed(first, case.rootPlayer) == v3.evaluateDetailed(second, case.rootPlayer)
        }
        val sample = horizonStates.first()
        val perspectiveRejected = runCatching { v3.evaluate(sample, "p1") }.isFailure
        val terminalRejected = runCatching { v3.evaluate(sample.copy(terminated = true), "p0") }.isFailure
        repeat(200) {
            MonoRedInformationEvaluator.evaluate(sample, "p0")
            v3.evaluate(sample, "p0")
        }
        fun timings(evaluator: InformationStateEvaluator): List<Double> = List(2_000) { index ->
            val information = horizonStates[index % horizonStates.size]
            val started = System.nanoTime()
            evaluator.evaluate(information, "p0")
            (System.nanoTime() - started) / 1_000.0
        }
        val v2Times = timings(MonoRedInformationEvaluator)
        val v3Times = timings(v3)
        return EvaluatorStage0Evidence(
            v2ReferenceStates = horizonStates.size,
            v2BitExactMatches = exactMatches,
            hiddenVariantPairs = hiddenPairs.size,
            v3HiddenInvariantPairs = hiddenPairs.count { it },
            perspectiveMismatchRejected = perspectiveRejected,
            terminalInputRejected = terminalRejected,
            v2P50Micros = percentile(v2Times, 0.50),
            v2P95Micros = percentile(v2Times, 0.95),
            v3P50Micros = percentile(v3Times, 0.50),
            v3P95Micros = percentile(v3Times, 0.95),
            v3ConfigurationId = v3.configurationId,
            passed = exactMatches == horizonStates.size && hiddenPairs.all { it } &&
                perspectiveRejected && terminalRejected,
        )
    }

    private fun benchmark(
        review: ValidatedTacticalAcceptedSet,
        simulations: Int,
        wallClockMillis: Long?,
        leafs: List<LeafEvaluationConfig>,
        cases: List<TacticalHorizonCase> = TacticalHorizonCatalog.cases,
        maxPolicyDecisions: Int = 32,
        maxQuiescenceDecisions: Int = 32,
        explorationConstant: Double = 1.4,
        evaluatorFactory: ((LeafEvaluationConfig) -> InformationStateEvaluator?)? = null,
    ): TacticalHorizonBenchmarkReport = TacticalHorizonBenchmarkRunner(
        registry = registry,
        manifest = manifest,
        particles = particles,
        simulations = simulations,
        maxPolicyDecisions = maxPolicyDecisions,
        maxQuiescenceDecisions = maxQuiescenceDecisions,
        explorationConstant = explorationConstant,
        wallClockBudgetMillis = wallClockMillis,
        actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
        leafConfigurations = leafs,
        informationEvaluatorFactory = evaluatorFactory,
    ).run(review, sha256File(acceptedSetPath), cases)

    private fun stage3(review: ValidatedTacticalAcceptedSet): EvaluatorFactorialReport {
        val ids = listOf("immediate-01", "within-turn-05", "short-01", "long-02")
        val cases = ids.map { id -> TacticalHorizonCatalog.cases.single { it.id == id } }
        val structural = buildList {
            for (family in comparisonFamilies()) {
                for (horizon in listOf(16, 32)) {
                    for (quiescence in listOf(8, 32)) {
                        for (simulations in listOf(64, 256)) {
                            val configuration = EvaluatorFactorialConfiguration(
                                family, simulations, horizon, quiescence, 1.4, 1.0,
                            )
                            val report = benchmark(
                                review = review,
                                simulations = simulations,
                                wallClockMillis = null,
                                leafs = listOf(leaf(family)),
                                cases = cases,
                                maxPolicyDecisions = horizon,
                                maxQuiescenceDecisions = quiescence,
                            )
                            add(EvaluatorFactorialCell(configuration, report.leafResults.single()))
                        }
                    }
                }
            }
        }
        val scales = buildList {
            for (family in comparisonFamilies()) {
                for (scale in listOf(0.75, 1.50)) {
                    for (exploration in listOf(0.7, 1.4)) {
                        val configuration = EvaluatorFactorialConfiguration(family, 64, 32, 32, exploration, scale)
                        val report = benchmark(
                            review = review,
                            simulations = 64,
                            wallClockMillis = null,
                            leafs = listOf(leaf(family)),
                            cases = cases,
                            explorationConstant = exploration,
                            evaluatorFactory = { scaledEvaluator(family, scale) },
                        )
                        add(EvaluatorFactorialCell(configuration, report.leafResults.single()))
                    }
                }
            }
        }
        return EvaluatorFactorialReport(ids, structural, scales)
    }

    private fun diagnose(
        stage1: TacticalHorizonBenchmarkReport,
        stage2: TacticalHorizonBenchmarkReport,
        stage3: EvaluatorFactorialReport,
    ): EvaluatorComponentDiagnosis {
        val v2s1 = stage1.result(LeafEvaluator.MTGALLIUM_VISIBLE_V2)
        val v3s1 = stage1.result(LeafEvaluator.MTGALLIUM_TACTICAL_V3)
        val v2s2 = stage2.result(LeafEvaluator.MTGALLIUM_VISIBLE_V2)
        val v3s2 = stage2.result(LeafEvaluator.MTGALLIUM_TACTICAL_V3)
        val v2Accepted = v2s1.trials.filter { it.humanAccepted }.map { it.caseId }.toSet()
        val v3Accepted = v3s1.trials.filter { it.humanAccepted }.map { it.caseId }.toSet()
        val all = TacticalHorizonCatalog.cases.map(TacticalHorizonCase::id).toSet()
        val pairedStructural = stage3.structuralCells.groupBy { cell ->
            cell.configuration.copy(family = LeafEvaluator.ARGENTUM_BOARD_V1)
        }.values.mapNotNull { cells ->
            val v2 = cells.singleOrNull { it.configuration.family == LeafEvaluator.MTGALLIUM_VISIBLE_V2 }
            val v3Cell = cells.singleOrNull { it.configuration.family == LeafEvaluator.MTGALLIUM_TACTICAL_V3 }
            if (v2 == null || v3Cell == null) null else v3Cell.summary.acceptedTrials - v2.summary.acceptedTrials
        }
        val fixedSimulationDelta = v3s1.acceptedTrials - v2s1.acceptedTrials
        val fixedTimeDelta = v3s2.acceptedTrials - v2s2.acceptedTrials
        val attribution = when {
            fixedSimulationDelta < 0 && pairedStructural.count { it < 0 } > pairedStructural.count { it > 0 } ->
                "V3_LEAF_FEATURES_OR_THEIR_SEARCH_INTERACTION"
            fixedSimulationDelta == 0 && fixedTimeDelta < 0 -> "V3_EVALUATION_COST_UNDER_FIXED_TIME"
            fixedSimulationDelta < 0 -> "MIXED_SEARCH_EVALUATOR_INTERACTION"
            else -> "NO_CORRECTED_TACTICAL_REGRESSION_DETECTED"
        }
        return EvaluatorComponentDiagnosis(
            primaryAttribution = attribution,
            fixedSimulationDelta = fixedSimulationDelta,
            fixedTimeDelta = fixedTimeDelta,
            v2OnlyAcceptedCasesAtFixedSimulation = (v2Accepted - v3Accepted).sorted(),
            v3OnlyAcceptedCasesAtFixedSimulation = (v3Accepted - v2Accepted).sorted(),
            bothRejectedCasesAtFixedSimulation = (all - v2Accepted - v3Accepted).sorted(),
            horizonDeltasAtFixedSimulation = horizonDeltas(v2s1, v3s1),
            horizonDeltasAtFixedTime = horizonDeltas(v2s2, v3s2),
            pairedStructuralCellDeltas = pairedStructural,
            evidence = listOf(
                "Stage 1 holds particles, simulations, root states, seeds, action space, opponent policy, and search parameters fixed; only the leaf evaluator family changes.",
                "Stage 2 holds a 50 ms root budget fixed and records actual simulations, separating throughput pressure from fixed-work behavior.",
                "Stage 3 pairs evaluator families across identical depth, quiescence, simulation, exploration, and output-scale cells on one reviewed case per horizon.",
            ),
            caveats = listOf(
                "Accepted-set agreement is a tactical screening metric, not a game-win estimate.",
                "The 28 cases use human-reviewed accepted sets and are deck-specific; only five older terminal cases have finite machine proofs.",
                "Attribution to individual v3 feature terms requires a separate feature ablation; this comparison does not modify v3.",
            ),
        )
    }

    private fun summarizeStages(
        stage0: EvaluatorStage0Evidence,
        stage1: TacticalHorizonBenchmarkReport,
        stage2: TacticalHorizonBenchmarkReport,
        stage3: EvaluatorFactorialReport,
    ): List<EvaluatorStageResult> {
        val v2s1 = stage1.result(LeafEvaluator.MTGALLIUM_VISIBLE_V2)
        val v3s1 = stage1.result(LeafEvaluator.MTGALLIUM_TACTICAL_V3)
        val v2s2 = stage2.result(LeafEvaluator.MTGALLIUM_VISIBLE_V2)
        val v3s2 = stage2.result(LeafEvaluator.MTGALLIUM_TACTICAL_V3)
        return listOf(
            EvaluatorStageResult(
                0,
                "Identity, information safety, and instrumentation",
                if (stage0.passed) EvaluatorStageStatus.PASSED else EvaluatorStageStatus.FAILED,
                listOf("stage-0.json"),
                listOf(
                    "${stage0.v2BitExactMatches}/${stage0.v2ReferenceStates} reviewed horizon states matched the v2 reference bit-exactly",
                    "${stage0.v3HiddenInvariantPairs}/${stage0.hiddenVariantPairs} authored hidden-world pairs were v3-invariant",
                ),
                if (stage0.passed) emptyList() else listOf("Stage 0 safety or compatibility failure"),
            ),
            EvaluatorStageResult(
                1,
                "Human-accepted-set fixed-simulation tactical screening",
                if (stage1.completed) EvaluatorStageStatus.PASSED else EvaluatorStageStatus.FAILED,
                listOf("stage-1-fixed-simulation.json"),
                listOf(
                    "v2 accepted ${v2s1.acceptedTrials}/${v2s1.totalTrials}",
                    "v3 accepted ${v3s1.acceptedTrials}/${v3s1.totalTrials}",
                ),
                buildList {
                    if (!stage1.completed) add("Incomplete benchmark trials")
                    if (v3s1.acceptedTrials < v2s1.acceptedTrials) add("v3 tactical accepted-set rate regressed at fixed work")
                },
            ),
            EvaluatorStageResult(
                2,
                "Human-accepted-set fixed-time tactical ablation",
                if (stage2.completed) EvaluatorStageStatus.PASSED else EvaluatorStageStatus.FAILED,
                listOf("stage-2-fixed-time.json"),
                listOf(
                    "v2 accepted ${v2s2.acceptedTrials}/${v2s2.totalTrials} at $fixedWallClockMillis ms/root",
                    "v3 accepted ${v3s2.acceptedTrials}/${v3s2.totalTrials} at $fixedWallClockMillis ms/root",
                    "median simulations v2=${v2s2.medianActualSimulations}, v3=${v3s2.medianActualSimulations}",
                ),
                buildList {
                    if (!stage2.completed) add("Incomplete benchmark trials")
                    if (v3s2.acceptedTrials < v2s2.acceptedTrials) add("v3 tactical accepted-set rate regressed at fixed time")
                },
            ),
            EvaluatorStageResult(
                3,
                "Evaluator/search interaction factorial",
                EvaluatorStageStatus.DIAGNOSTIC,
                listOf("stage-3-factorial.json"),
                listOf(
                    "${stage3.structuralCells.size} structural cells and ${stage3.scaleCells.size} scale cells executed",
                    "One human-reviewed case from each horizon band was included",
                ),
                listOf("The four-case factorial is component diagnosis, not a population-level conclusion"),
            ),
        )
    }

    private fun horizonDeltas(
        v2: TacticalHorizonBenchmarkResult,
        v3Result: TacticalHorizonBenchmarkResult,
    ): Map<TacticalHorizon, Int> = TacticalHorizon.entries.associateWith { horizon ->
        v3Result.horizonResults.single { it.horizon == horizon }.acceptedTrials -
            v2.horizonResults.single { it.horizon == horizon }.acceptedTrials
    }

    private fun TacticalHorizonBenchmarkReport.result(family: LeafEvaluator): TacticalHorizonBenchmarkResult =
        leafResults.single { it.leaf.evaluator == family }

    private fun comparisonFamilies() = listOf(
        LeafEvaluator.MTGALLIUM_VISIBLE_V2,
        LeafEvaluator.MTGALLIUM_TACTICAL_V3,
    )

    private fun leaf(family: LeafEvaluator) =
        LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE, family)

    private fun comparisonLeafs() = comparisonFamilies().map(::leaf)

    private fun scaledEvaluator(family: LeafEvaluator, scale: Double): InformationStateEvaluator = when (family) {
        LeafEvaluator.MTGALLIUM_VISIBLE_V2 -> OutputScaledEvaluator(MonoRedInformationEvaluator, scale)
        LeafEvaluator.MTGALLIUM_TACTICAL_V3 -> MonoRedTacticalEvaluator(
            MonoRedTacticalEvaluatorSettings(outputTemperature = 2.0 * scale)
        )
        LeafEvaluator.ARGENTUM_BOARD_V1 -> error("Argentum scaling is outside this comparison")
    }

    private fun referenceV2(information: PolicyInformationState, rootPlayer: String): Double {
        val observation = information.observation
        val root = observation.players.single { it.playerId == rootPlayer }
        val opponent = observation.players.single { it.playerId != rootPlayer }
        val battlefield = observation.zones.filter { it.zone == "BATTLEFIELD" }.flatMap { it.cards }
        fun board(player: String): Double {
            val permanents = battlefield.filter { it.controllerId == player }
            val lands = permanents.count { card -> card.types.any { it.equals("LAND", true) } }
            val creatures = permanents.sumOf { card ->
                (card.power ?: 0) * 1.2 + (card.toughness ?: 0) * 0.4 +
                    if (card.keywords.any { it.equals("HASTE", true) }) 0.3 else 0.0
            }
            return creatures + MonoRedInformationEvaluator.developedManaValue(lands)
        }
        val score = (root.life - opponent.life) * 0.12 +
            (root.handSize - opponent.handSize) * 0.35 + board(rootPlayer) - board(opponent.playerId)
        return tanh(score / 8.0)
    }

    private class OutputScaledEvaluator(
        private val delegate: InformationStateEvaluator,
        private val scale: Double,
    ) : ConfiguredInformationStateEvaluator {
        init { require(scale > 0.0 && scale.isFinite()) }
        override val id: String = delegate.id
        override val configurationId: String = "$id:output-scale-$scale"
        override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double {
            val value = delegate.evaluate(information, rootPlayer).coerceIn(-0.999999999, 0.999999999)
            return tanh(atanh(value) / scale)
        }
    }
}

internal fun renderEvaluatorComparison(report: EvaluatorComparisonReport): String = buildString {
    appendLine("# Evaluator comparison")
    appendLine()
    appendLine(report.conclusion)
    appendLine()
    appendLine("- Comparison: `${report.comparisonVersion}`")
    appendLine("- Diagnosed component: `${report.componentDiagnosis.primaryAttribution}`")
    appendLine("- v2 evaluator: `${report.v2EvaluatorId}`")
    appendLine("- v3 configuration: `${report.v3ConfigurationId}`")
    appendLine()
    appendLine("| Stage | Status | Finding | Limitation |")
    appendLine("|---:|---|---|---|")
    report.stages.forEach { stage ->
        appendLine(
            "| ${stage.stage} | ${stage.status} | ${stage.findings.joinToString("; ")} | " +
                (stage.limitations.joinToString("; ").ifBlank { "—" }) + " |"
        )
    }
    appendLine()
    appendLine("## Limits")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
}
