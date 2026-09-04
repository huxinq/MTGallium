package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.SearchTeacherLeafConfigurations
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

import com.wingedsheep.engine.registry.CardRegistry
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumKnownDeckBeliefWorldSource
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.InformationStateEvaluator
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile

internal const val TACTICAL_PROOF_LEAF_BENCHMARK_VERSION = "tactical-proof-leaf-benchmark-v1"

@Serializable
internal data class TacticalProofLeafBenchmarkTrial(
    val caseId: String,
    val category: TacticalProofCategory,
    val hiddenVariant: Int,
    val publicInformationStateDigest: String,
    val chosenSignature: String? = null,
    val chosenLabel: String? = null,
    val oracleAccepted: Boolean = false,
    /** One is best. Equal terminal outcomes share a rank. */
    val oracleRank: Int? = null,
    val selectedTerminalOutcome: TacticalTerminalOutcome? = null,
    val candidateCount: Int? = null,
    val rootValue: Double? = null,
    val actualSimulations: Int? = null,
    val evaluatorConfigurationId: String? = null,
    val evaluatorCalls: Int = 0,
    val evaluatorNanos: Long = 0,
    val evaluatorOutputChecksum: String? = null,
    val quiescenceUnresolvedBackups: Int = 0,
    val beliefMillis: Double,
    val searchMillis: Double,
    val totalMillis: Double,
    val maximumDepth: Int? = null,
    val quiescenceForcedPasses: Int = 0,
    val quiescenceStrategicDecisions: Int = 0,
    val quiescenceFallbacks: Int = 0,
    val rootRolloutDecisions: Int = 0,
    val opponentRolloutDecisions: Int = 0,
    val rootRolloutFallbacks: Int = 0,
    val opponentRolloutFallbacks: Int = 0,
    val diagnostic: String? = null,
)

@Serializable
internal data class TacticalProofCategoryBenchmark(
    val category: TacticalProofCategory,
    val solvedTrials: Int,
    val totalTrials: Int,
)

@Serializable
internal data class TacticalProofLeafBenchmarkResult(
    val leaf: LeafEvaluationConfig,
    val trials: List<TacticalProofLeafBenchmarkTrial>,
    val completedTrials: Int,
    val solvedTrials: Int,
    val totalTrials: Int,
    val solvedCasesAcrossBothHiddenVariants: Int,
    val totalCases: Int,
    val hiddenVariantSelectionDisagreements: Int,
    val oracleAgreementRate: Double,
    val meanOracleRank: Double?,
    val worstOracleRank: Int?,
    val p50TotalMillis: Double?,
    val p95TotalMillis: Double?,
    val meanTotalMillis: Double?,
    val p50SearchMillis: Double?,
    val p95SearchMillis: Double?,
    val meanSearchMillis: Double?,
    val p50EvaluatorMicros: Double? = null,
    val p95EvaluatorMicros: Double? = null,
    val categoryResults: List<TacticalProofCategoryBenchmark>,
    val quiescenceForcedPasses: Int,
    val quiescenceStrategicDecisions: Int,
    val quiescenceFallbacks: Int,
    val rolloutDecisions: Int,
    val rolloutFallbacks: Int,
    val quiescenceUnresolvedBackups: Int = 0,
)

@Serializable
internal data class TacticalProofLeafBenchmarkReport(
    val schemaVersion: Int = 1,
    val documentKind: String = "tactical-proof-leaf-benchmark-v1",
    val benchmarkVersion: String = TACTICAL_PROOF_LEAF_BENCHMARK_VERSION,
    val proofSuiteVersion: String = TACTICAL_PROOF_SUITE_VERSION,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val host: String,
    val oracleReportSha256: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val warmupTrialsPerLeaf: Int,
    val explorationConstant: Double = 1.4,
    val maxQuiescenceDecisions: Int = 32,
    val wallClockBudgetMillis: Long? = null,
    val cases: List<String>,
    val hiddenVariantsPerCase: Int,
    val leafResults: List<TacticalProofLeafBenchmarkResult>,
    val completed: Boolean,
    val failureReasons: List<String>,
)

internal class TacticalProofLeafBenchmarkRunner(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val particles: Int = 8,
    private val simulations: Int = 64,
    private val maxPolicyDecisions: Int = 32,
    private val explorationConstant: Double = 1.4,
    private val maxQuiescenceDecisions: Int = 32,
    private val wallClockBudgetMillis: Long? = null,
    private val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
    private val leafConfigurations: List<LeafEvaluationConfig> = SearchTeacherLeafConfigurations.supported,
    private val informationEvaluatorFactory: ((LeafEvaluationConfig) -> InformationStateEvaluator?)? = null,
) {
    private val factory = TacticalProofScenarioFactory(registry, manifest, actionSpaceProfile)
    private val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)

    init {
        require(particles > 0)
        require(simulations > 0)
        require(maxPolicyDecisions > 0)
        require(explorationConstant >= 0.0 && explorationConstant.isFinite())
        require(maxQuiescenceDecisions > 0)
        require(wallClockBudgetMillis == null || wallClockBudgetMillis > 0)
        require(leafConfigurations.isNotEmpty())
        require(leafConfigurations.distinct().size == leafConfigurations.size)
        require(leafConfigurations.all {
            it in SearchTeacherLeafConfigurations.supported || it in SearchTeacherLeafConfigurations.experimental
        })
    }

    fun run(
        oracleReport: TacticalProofReport,
        cases: List<TacticalProofCase> = TacticalProofCatalog.cases,
    ): TacticalProofLeafBenchmarkReport {
        require(oracleReport.oraclePassed) { "The tactical proof oracle must pass before benchmarking" }
        require(oracleReport.suiteVersion == TACTICAL_PROOF_SUITE_VERSION)
        require(cases.isNotEmpty())
        val oracleCases = oracleReport.cases.associateBy { it.definition.id }
        require(cases.all { it.id in oracleCases }) { "The oracle report does not cover every benchmark case" }

        val leafResults = leafConfigurations.map { leaf ->
            val warmupCase = cases.first()
            runTrial(leaf, warmupCase, oracleCases.getValue(warmupCase.id), hiddenVariant = 1)
            println("Proof leaf benchmark $leaf")
            val trials = cases.flatMap { case ->
                val oracleCase = oracleCases.getValue(case.id)
                (1..2).map { variant -> runTrial(leaf, case, oracleCase, variant) }
            }
            summarize(leaf, cases, trials)
        }
        val failures = leafResults.flatMap { result ->
            result.trials.mapNotNull { trial ->
                trial.diagnostic?.let { "${result.leaf}/${trial.caseId}/hidden-${trial.hiddenVariant}:$it" }
            }
        }
        return TacticalProofLeafBenchmarkReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            host = calibrationHost(),
            oracleReportSha256 = sha256(evidenceJson.encodeToString(TacticalProofReport.serializer(), oracleReport)),
            actionSpaceProfile = actionSpaceProfile,
            particles = particles,
            simulations = simulations,
            maxPolicyDecisions = maxPolicyDecisions,
            warmupTrialsPerLeaf = 1,
            explorationConstant = explorationConstant,
            maxQuiescenceDecisions = maxQuiescenceDecisions,
            wallClockBudgetMillis = wallClockBudgetMillis,
            cases = cases.map(TacticalProofCase::id),
            hiddenVariantsPerCase = 2,
            leafResults = leafResults,
            completed = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    private fun runTrial(
        leaf: LeafEvaluationConfig,
        case: TacticalProofCase,
        oracleCase: TacticalProofCaseResult,
        hiddenVariant: Int,
    ): TacticalProofLeafBenchmarkTrial {
        val world = factory.create(case, hiddenVariant)
        val information = world.informationState(case.rootPlayer)
        var beliefMillis = 0.0
        var searchMillis = 0.0
        return runCatching {
            val beliefStarted = System.nanoTime()
            val belief = ArgentumKnownDeckBeliefWorldSource(world).sample(
                information,
                knownDecks,
                ComponentSeeds.derive(case.rootSeed, "proof-leaf-benchmark-belief"),
                particles,
            )
            beliefMillis = elapsedMillis(beliefStarted)
            val searchConfig = InformationSetSearchConfig(
                simulations = simulations,
                explorationConstant = explorationConstant,
                maxPolicyDecisions = maxPolicyDecisions,
                maxQuiescenceDecisions = maxQuiescenceDecisions,
                leaf = leaf,
                wallClockBudgetMillis = wallClockBudgetMillis,
            )
            val evaluator = informationEvaluatorFactory?.invoke(leaf)
            val search = if (evaluator == null) {
                SearchTeacherSearchFactory.create(searchConfig, defaultMonoRedOpponentPolicy())
            } else {
                SearchTeacherSearchFactory.create(
                    searchConfig,
                    defaultMonoRedOpponentPolicy(),
                    informationEvaluator = evaluator,
                )
            }
            val searchStarted = System.nanoTime()
            val result = search.search(
                case.rootPlayer,
                belief,
                ComponentSeeds.derive(case.rootSeed, "proof-leaf-benchmark-search"),
            )
            searchMillis = elapsedMillis(searchStarted)
            val oracleVariant = oracleCase.variants.single { it.hiddenVariant == hiddenVariant }
            val selected = oracleVariant.actionValues.single { it.choice.signature == result.chosen.signature }
            val rankedOutcomes = oracleVariant.actionValues.map(TacticalProofActionValue::outcome)
                .distinct()
                .sortedDescending()
            TacticalProofLeafBenchmarkTrial(
                caseId = case.id,
                category = case.category,
                hiddenVariant = hiddenVariant,
                publicInformationStateDigest = information.informationStateDigest,
                chosenSignature = result.chosen.signature,
                chosenLabel = result.chosen.display.label,
                oracleAccepted = result.chosen.signature in oracleVariant.acceptedSignatures,
                oracleRank = rankedOutcomes.indexOf(selected.outcome) + 1,
                selectedTerminalOutcome = selected.outcome,
                candidateCount = result.candidates.size,
                rootValue = result.rootValue,
                actualSimulations = result.diagnostics.simulations,
                evaluatorConfigurationId = result.diagnostics.invokedEvaluatorConfigurationId,
                evaluatorCalls = result.diagnostics.evaluatorCalls,
                evaluatorNanos = result.diagnostics.evaluatorNanos,
                evaluatorOutputChecksum = result.diagnostics.evaluatorOutputChecksum,
                quiescenceUnresolvedBackups = result.diagnostics.quiescenceUnresolvedBackups,
                beliefMillis = beliefMillis,
                searchMillis = searchMillis,
                totalMillis = beliefMillis + searchMillis,
                maximumDepth = result.diagnostics.maximumDepth,
                quiescenceForcedPasses = result.diagnostics.quiescenceForcedPasses,
                quiescenceStrategicDecisions = result.diagnostics.quiescenceStrategicDecisions,
                quiescenceFallbacks = result.diagnostics.quiescenceFallbacks,
                rootRolloutDecisions = result.diagnostics.rootRolloutDecisions,
                opponentRolloutDecisions = result.diagnostics.opponentRolloutDecisions,
                rootRolloutFallbacks = result.diagnostics.rootRolloutFallbacks,
                opponentRolloutFallbacks = result.diagnostics.opponentRolloutFallbacks,
            )
        }.getOrElse { failure ->
            TacticalProofLeafBenchmarkTrial(
                caseId = case.id,
                category = case.category,
                hiddenVariant = hiddenVariant,
                publicInformationStateDigest = information.informationStateDigest,
                beliefMillis = beliefMillis,
                searchMillis = searchMillis,
                totalMillis = beliefMillis + searchMillis,
                diagnostic = "${failure::class.simpleName}:${failure.message}",
            )
        }
    }

    private fun summarize(
        leaf: LeafEvaluationConfig,
        cases: List<TacticalProofCase>,
        trials: List<TacticalProofLeafBenchmarkTrial>,
    ): TacticalProofLeafBenchmarkResult {
        val completed = trials.filter { it.diagnostic == null }
        val totalMillis = completed.map(TacticalProofLeafBenchmarkTrial::totalMillis)
        val searchMillis = completed.map(TacticalProofLeafBenchmarkTrial::searchMillis)
        val ranks = completed.mapNotNull(TacticalProofLeafBenchmarkTrial::oracleRank)
        val evaluatorMicros = completed.flatMap { trial ->
            if (trial.evaluatorCalls == 0) emptyList() else {
                listOf(trial.evaluatorNanos / trial.evaluatorCalls.toDouble() / 1_000.0)
            }
        }
        val byCase = trials.groupBy(TacticalProofLeafBenchmarkTrial::caseId)
        return TacticalProofLeafBenchmarkResult(
            leaf = leaf,
            trials = trials,
            completedTrials = completed.size,
            solvedTrials = completed.count(TacticalProofLeafBenchmarkTrial::oracleAccepted),
            totalTrials = trials.size,
            solvedCasesAcrossBothHiddenVariants = cases.count { case ->
                byCase.getValue(case.id).all { it.diagnostic == null && it.oracleAccepted }
            },
            totalCases = cases.size,
            hiddenVariantSelectionDisagreements = cases.count { case ->
                byCase.getValue(case.id).mapNotNull(TacticalProofLeafBenchmarkTrial::chosenSignature).distinct().size > 1
            },
            oracleAgreementRate = completed.count(TacticalProofLeafBenchmarkTrial::oracleAccepted).toDouble() /
                completed.size.coerceAtLeast(1),
            meanOracleRank = ranks.takeIf { it.isNotEmpty() }?.average(),
            worstOracleRank = ranks.maxOrNull(),
            p50TotalMillis = totalMillis.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) },
            p95TotalMillis = totalMillis.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
            meanTotalMillis = totalMillis.takeIf { it.isNotEmpty() }?.average(),
            p50SearchMillis = searchMillis.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) },
            p95SearchMillis = searchMillis.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
            meanSearchMillis = searchMillis.takeIf { it.isNotEmpty() }?.average(),
            p50EvaluatorMicros = evaluatorMicros.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) },
            p95EvaluatorMicros = evaluatorMicros.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
            categoryResults = TacticalProofCategory.entries.map { category ->
                val categoryTrials = trials.filter { it.category == category }
                TacticalProofCategoryBenchmark(
                    category,
                    categoryTrials.count { it.diagnostic == null && it.oracleAccepted },
                    categoryTrials.size,
                )
            },
            quiescenceForcedPasses = completed.sumOf(TacticalProofLeafBenchmarkTrial::quiescenceForcedPasses),
            quiescenceStrategicDecisions = completed.sumOf(TacticalProofLeafBenchmarkTrial::quiescenceStrategicDecisions),
            quiescenceFallbacks = completed.sumOf(TacticalProofLeafBenchmarkTrial::quiescenceFallbacks),
            rolloutDecisions = completed.sumOf {
                it.rootRolloutDecisions + it.opponentRolloutDecisions
            },
            rolloutFallbacks = completed.sumOf {
                it.rootRolloutFallbacks + it.opponentRolloutFallbacks
            },
            quiescenceUnresolvedBackups = completed.sumOf(
                TacticalProofLeafBenchmarkTrial::quiescenceUnresolvedBackups
            ),
        )
    }

    private fun elapsedMillis(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0
}

internal fun renderTacticalProofLeafBenchmark(report: TacticalProofLeafBenchmarkReport): String = buildString {
    appendLine("# How five position evaluators agree with a finite terminal-position checker")
    appendLine()
    appendLine("## What was measured")
    appendLine()
    appendLine(
        "Each evaluator searched ${report.cases.size} supplied terminal cases across " +
            "${report.hiddenVariantsPerCase} hidden variants and was counted as agreeing when its action belonged " +
            "to the accepted set computed for that case."
    )
    appendLine()
    appendLine(
        "For example, the checker can rank actions by the game result reached in one supplied terminal position. " +
            "Its expected answers come from repository-authored cases and a checker that shares the rules engine " +
            "and action representation with the evaluated policy. Agreement therefore does not establish general " +
            "strategic quality or truth independent of those shared assumptions. The code calls the expected-answer " +
            "source its tactical proof oracle."
    )
    appendLine()
    appendLine(
        "All trials ${if (report.completed) "finished without a technical failure counted by this runner" else "did not finish without a technical failure counted by this runner"}."
    )
    appendLine()
    appendLine("## Implementation trace and declared budget")
    appendLine()
    appendLine("- Proof suite: `${report.proofSuiteVersion}`")
    appendLine("- Budget: ${report.particles} particles × ${report.simulations} simulations; depth ${report.maxPolicyDecisions}")
    appendLine("- Action space: `${report.actionSpaceProfile.profileId}`")
    appendLine()
    appendLine("## Agreement results")
    appendLine()
    appendLine("| Leaf source | Evaluator | Accepted-set trials | Cases accepted in both variants | Hidden-variant disagreements | Mean checker rank | p50 search ms | p95 search ms |")
    appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    report.leafResults.forEach { result ->
        appendLine(
            "| ${result.leaf.stateSource} | ${result.leaf.evaluator} | " +
                "${result.solvedTrials}/${result.totalTrials} | " +
                "${result.solvedCasesAcrossBothHiddenVariants}/${result.totalCases} | " +
                "${result.hiddenVariantSelectionDisagreements} | " +
                "${result.meanOracleRank?.format(3) ?: "n/a"} | " +
                "${result.p50SearchMillis?.format(1) ?: "n/a"} | " +
                "${result.p95SearchMillis?.format(1) ?: "n/a"} |"
        )
    }
    val misses = report.leafResults.flatMap { result ->
        result.trials.filter { it.diagnostic != null || !it.oracleAccepted }.map { result.leaf to it }
    }
    appendLine()
    appendLine("## Misses and technical failures")
    appendLine()
    if (misses.isEmpty()) {
        appendLine("None.")
    } else {
        appendLine("| Leaf | Case | Hidden variant | Choice | Checker rank | Diagnostic |")
        appendLine("| --- | --- | ---: | --- | ---: | --- |")
        misses.forEach { (leaf, trial) ->
            appendLine(
                "| ${leaf.stateSource}/${leaf.evaluator} | ${trial.caseId} | ${trial.hiddenVariant} | " +
                    "${trial.chosenLabel ?: "n/a"} | ${trial.oracleRank ?: 0} | ${trial.diagnostic ?: "wrong action"} |"
            )
        }
    }
}

private fun Double.format(digits: Int): String = "%.${digits}f".format(this)
