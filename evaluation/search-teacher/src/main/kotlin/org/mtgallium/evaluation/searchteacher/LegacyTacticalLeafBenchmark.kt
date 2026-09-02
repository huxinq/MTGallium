package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.SearchTeacherLeafConfigurations

internal const val LEGACY_TACTICAL_LEAF_BENCHMARK_VERSION = "legacy-tactical-leaf-benchmark-v1"

private const val LEGACY_VISIBLE_EVALUATOR_JSON = "\"MTGALLIUM_VISIBLE_V1\""
private const val CURRENT_VISIBLE_EVALUATOR_JSON = "\"MTGALLIUM_VISIBLE_V2\""

/**
 * The frozen v1 benchmark predates the visible evaluator's v2 identifier. The tournament uses
 * the benchmark as immutable launch evidence, not as an executable search configuration, so
 * migrate only that exact legacy enum token while retaining and hashing the original bytes.
 */
internal fun decodeLegacyTacticalLeafBenchmarkReport(encoded: String): LegacyTacticalLeafBenchmarkReport =
    evidenceJson.decodeFromString(
        encoded.replace(LEGACY_VISIBLE_EVALUATOR_JSON, CURRENT_VISIBLE_EVALUATOR_JSON),
    )

@Serializable
internal data class LegacyTacticalCategoryBenchmark(
    val category: TacticalCategory,
    val solvedTrials: Int,
    val totalTrials: Int,
)

@Serializable
internal data class LegacyTacticalLeafBenchmarkResult(
    val leaf: LeafEvaluationConfig,
    val trials: List<TacticalCaseResult>,
    val completedTrials: Int,
    val solvedTrials: Int,
    val totalTrials: Int,
    val hiddenPairsStable: Int,
    val hiddenPairsTotal: Int,
    val calibrationSuccesses: Int,
    val calibrationTrials: Int,
    val calibrationScore: Double,
    val p50TotalMillis: Double,
    val p95TotalMillis: Double,
    val meanTotalMillis: Double,
    val p50SearchMillis: Double,
    val p95SearchMillis: Double,
    val meanSearchMillis: Double,
    val categoryResults: List<LegacyTacticalCategoryBenchmark>,
    val maximumDepth: Int?,
    val heuristicFallbacks: Int,
    val quiescenceForcedPasses: Int,
    val quiescenceStrategicDecisions: Int,
    val quiescenceFallbacks: Int,
    val rolloutDecisions: Int,
    val rolloutFallbacks: Int,
)

@Serializable
internal data class LegacyTacticalLeafBenchmarkReport(
    val schemaVersion: Int = 1,
    val documentKind: String = "legacy-tactical-leaf-benchmark-v1",
    val benchmarkVersion: String = LEGACY_TACTICAL_LEAF_BENCHMARK_VERSION,
    val legacySuiteVersion: String = "legacy-tactical-v1",
    val predicate: String = "bounded-pass-through-immediate-lethal-or-survival-v1",
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val host: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val warmupTrialsPerLeaf: Int,
    val cases: List<String>,
    val completeHiddenPairs: Int,
    val leafResults: List<LegacyTacticalLeafBenchmarkResult>,
    val crossLeafSelectionDisagreementCases: List<String>,
    val completed: Boolean,
    val failureReasons: List<String>,
)

internal class LegacyTacticalLeafBenchmarkRunner(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val particles: Int = 8,
    private val simulations: Int = 64,
    private val maxPolicyDecisions: Int = 32,
) {
    init {
        require(particles in setOf(8, 16, 32, 64))
        require(simulations in setOf(64, 256, 1_024, 4_096))
        require(maxPolicyDecisions > 0)
    }

    fun run(
        cases: List<TacticalCaseDefinition> = TacticalBenchmarkCatalog.cases,
    ): LegacyTacticalLeafBenchmarkReport {
        TacticalBenchmarkCatalog.validate()
        require(cases.isNotEmpty())
        require(cases.map(TacticalCaseDefinition::id).distinct().size == cases.size)
        val generatedAt = Instant.now().toString()
        val host = calibrationHost()
        val results = SearchTeacherLeafConfigurations.supported.map { leaf ->
            val profile = measurementProfile(
                generatedAt = generatedAt,
                host = host,
                particles = particles,
                simulations = simulations,
                leaf = leaf,
                actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
                maxPolicyDecisions = maxPolicyDecisions,
            )
            val runner = TacticalBenchmarkRunner(registry, manifest, profile)
            runner.runCase(cases.first())
            println("Legacy tactical leaf benchmark $leaf")
            summarize(leaf, cases, runner.run(cases))
        }
        val trialsByCase = cases.associate { case ->
            case.id to results.map { result -> result.trials.single { it.id == case.id } }
        }
        val crossLeafDisagreements = trialsByCase.filterValues { trials ->
            trials.map(TacticalCaseResult::chosenSignature).distinct().size > 1
        }.keys.toList()
        val failures = results.flatMap { result ->
            result.trials.mapNotNull { trial ->
                trial.diagnostic?.let { "${result.leaf}/${trial.id}:$it" }
            }
        }
        return LegacyTacticalLeafBenchmarkReport(
            generatedAtUtc = generatedAt,
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            host = host,
            actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
            particles = particles,
            simulations = simulations,
            maxPolicyDecisions = maxPolicyDecisions,
            warmupTrialsPerLeaf = 1,
            cases = cases.map(TacticalCaseDefinition::id),
            completeHiddenPairs = completeHiddenPairs(cases).size,
            leafResults = results,
            crossLeafSelectionDisagreementCases = crossLeafDisagreements,
            completed = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    private fun summarize(
        leaf: LeafEvaluationConfig,
        cases: List<TacticalCaseDefinition>,
        report: TacticalReport,
    ): LegacyTacticalLeafBenchmarkResult {
        val resultById = report.cases.associateBy(TacticalCaseResult::id)
        val hiddenPairs = completeHiddenPairs(cases)
        val stableHiddenPairs = hiddenPairs.count { pair ->
            pair.map { resultById.getValue(it.id).chosenSignature }.distinct().size == 1
        }
        val completed = report.cases.filter { it.diagnostic == null }
        val solved = completed.count { it.solved == true }
        val calibrationTrials = cases.count(TacticalCaseDefinition::mechanicallyVerifiable) + hiddenPairs.size
        val calibrationSuccesses = solved + stableHiddenPairs
        return LegacyTacticalLeafBenchmarkResult(
            leaf = leaf,
            trials = report.cases,
            completedTrials = completed.size,
            solvedTrials = solved,
            totalTrials = cases.size,
            hiddenPairsStable = stableHiddenPairs,
            hiddenPairsTotal = hiddenPairs.size,
            calibrationSuccesses = calibrationSuccesses,
            calibrationTrials = calibrationTrials,
            calibrationScore = calibrationSuccesses.toDouble() / calibrationTrials.coerceAtLeast(1),
            p50TotalMillis = percentile(completed.map(TacticalCaseResult::latencyMillis), 0.50),
            p95TotalMillis = percentile(completed.map(TacticalCaseResult::latencyMillis), 0.95),
            meanTotalMillis = completed.map(TacticalCaseResult::latencyMillis).average(),
            p50SearchMillis = percentile(completed.map(TacticalCaseResult::searchMillis), 0.50),
            p95SearchMillis = percentile(completed.map(TacticalCaseResult::searchMillis), 0.95),
            meanSearchMillis = completed.map(TacticalCaseResult::searchMillis).average(),
            categoryResults = cases.map(TacticalCaseDefinition::category).distinct().map { category ->
                val categoryIds = cases.filter { it.category == category }.map(TacticalCaseDefinition::id).toSet()
                val categoryTrials = completed.filter { it.id in categoryIds }
                LegacyTacticalCategoryBenchmark(
                    category = category,
                    solvedTrials = categoryTrials.count { it.solved == true },
                    totalTrials = categoryIds.size,
                )
            },
            maximumDepth = completed.mapNotNull(TacticalCaseResult::maximumDepth).maxOrNull(),
            heuristicFallbacks = completed.count(TacticalCaseResult::heuristicFallback),
            quiescenceForcedPasses = completed.sumOf(TacticalCaseResult::quiescenceForcedPasses),
            quiescenceStrategicDecisions = completed.sumOf(TacticalCaseResult::quiescenceStrategicDecisions),
            quiescenceFallbacks = completed.sumOf(TacticalCaseResult::quiescenceFallbacks),
            rolloutDecisions = completed.sumOf {
                it.rootRolloutDecisions + it.opponentRolloutDecisions
            },
            rolloutFallbacks = completed.sumOf {
                it.rootRolloutFallbacks + it.opponentRolloutFallbacks
            },
        )
    }

    private fun completeHiddenPairs(cases: List<TacticalCaseDefinition>): List<List<TacticalCaseDefinition>> =
        cases.filter { it.hiddenFamily != null }
            .groupBy { requireNotNull(it.hiddenFamily) }
            .values
            .filter { it.size == 2 }
}

internal fun renderLegacyTacticalLeafBenchmark(report: LegacyTacticalLeafBenchmarkReport): String = buildString {
    appendLine("# How five position evaluators choose in 48 hand-authored lethal and survival cases")
    appendLine()
    appendLine("## What was measured")
    appendLine()
    appendLine(
        "Each evaluator searched ${report.cases.size} supplied positions and was counted as solving a case when " +
            "its chosen action satisfied the suite's hand-authored immediate lethal or survival predicate. The " +
            "report also compares ${report.completeHiddenPairs} paired variants per evaluator to see whether hiding " +
            "a fact unavailable to the actor changed the selected action."
    )
    appendLine()
    appendLine(
        "For example, a case can count an action as successful when it immediately produces the authored lethal " +
            "outcome. This measures agreement with those predicates on these positions; it does not provide an " +
            "independent source of Magic strategy, cover nonterminal planning generally, or show that a selected " +
            "action remains best after omitted legal actions are restored."
    )
    appendLine()
    appendLine(
        "All trials ${if (report.completed) "finished without a technical failure counted by this runner" else "did not finish without a technical failure counted by this runner"}. " +
            "The five evaluators disagreed on ${report.crossLeafSelectionDisagreementCases.size}/${report.cases.size} supplied positions."
    )
    appendLine()
    appendLine("## Implementation trace and declared budget")
    appendLine()
    appendLine("- Suite: `${report.legacySuiteVersion}`")
    appendLine("- Predicate: `${report.predicate}`")
    appendLine("- Budget: ${report.particles} particles × ${report.simulations} simulations; depth ${report.maxPolicyDecisions}")
    appendLine("- Action space: `${report.actionSpaceProfile.profileId}`")
    appendLine()
    appendLine("## Agreement with the hand-authored predicates")
    appendLine()
    appendLine("| Leaf source | Evaluator | Mechanical | Hidden pairs | Calibration score | p50 search ms | p95 search ms | Mean search ms |")
    appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    report.leafResults.forEach { result ->
        appendLine(
            "| ${result.leaf.stateSource} | ${result.leaf.evaluator} | " +
                "${result.solvedTrials}/${result.totalTrials} | " +
                "${result.hiddenPairsStable}/${result.hiddenPairsTotal} | " +
                "${result.calibrationSuccesses}/${result.calibrationTrials} (${(result.calibrationScore * 100).format(1)}%) | " +
                "${result.p50SearchMillis.format(1)} | ${result.p95SearchMillis.format(1)} | " +
                "${result.meanSearchMillis.format(1)} |"
        )
    }
    appendLine()
    appendLine("## Category results")
    appendLine()
    appendLine("| Leaf | Category | Solved |")
    appendLine("| --- | --- | ---: |")
    report.leafResults.forEach { result ->
        result.categoryResults.forEach { category ->
            appendLine(
                "| ${result.leaf.stateSource}/${result.leaf.evaluator} | ${category.category} | " +
                    "${category.solvedTrials}/${category.totalTrials} |"
            )
        }
    }
    appendLine()
    appendLine("## Search activity recorded by internal counters")
    appendLine()
    appendLine(
        "When the ordinary horizon ends with more simulation work requested, the runner records automatically " +
            "passed steps, additional policy decisions, and substituted behavior separately. These counters do " +
            "not show that every Magic response opportunity was preserved. The stored field names use " +
            "`quiescence` and `fallback` for these internal categories."
    )
    appendLine()
    appendLine("| Leaf | Max depth | Automatically passed steps | Additional policy decisions | Substituted actions after ordinary horizon | Rollout decisions | Substituted rollout actions |")
    appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    report.leafResults.forEach { result ->
        appendLine(
            "| ${result.leaf.stateSource}/${result.leaf.evaluator} | ${result.maximumDepth ?: 0} | " +
                "${result.quiescenceForcedPasses} | ${result.quiescenceStrategicDecisions} | " +
                "${result.quiescenceFallbacks} | ${result.rolloutDecisions} | ${result.rolloutFallbacks} |"
        )
    }
    appendLine()
    appendLine("## Mechanical misses and technical failures")
    appendLine()
    val misses = report.leafResults.flatMap { result ->
        result.trials.filter { it.solved != true || it.diagnostic != null }.map { result.leaf to it }
    }
    if (misses.isEmpty()) {
        appendLine("None.")
    } else {
        appendLine("| Leaf | Case | Choice | Diagnostic |")
        appendLine("| --- | --- | --- | --- |")
        misses.forEach { (leaf, trial) ->
            appendLine(
                "| ${leaf.stateSource}/${leaf.evaluator} | ${trial.id} | " +
                    "${trial.chosenLabel ?: trial.chosenSignature} | ${trial.diagnostic ?: "wrong legacy predicate action"} |"
            )
        }
    }
}

private fun Double.format(digits: Int): String = "%.${digits}f".format(this)
