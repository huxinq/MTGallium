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

internal const val TACTICAL_HORIZON_BENCHMARK_VERSION = "tactical-horizon-benchmark-v1"

@Serializable
internal data class TacticalHorizonBenchmarkTrial(
    val caseId: String,
    val horizon: TacticalHorizon,
    val category: TacticalCategory,
    val informationStateDigest: String,
    val authority: TacticalEvidenceAuthority = TacticalEvidenceAuthority.HUMAN_AUTHORITY,
    val chosenSignature: String? = null,
    val chosenLabel: String? = null,
    val humanAccepted: Boolean = false,
    val acceptedSignatureCount: Int,
    val candidateCount: Int? = null,
    val rootValue: Double? = null,
    val actualSimulations: Int? = null,
    val evaluatorConfigurationId: String? = null,
    val evaluatorCalls: Int = 0,
    val evaluatorNanos: Long = 0,
    val evaluatorOutputChecksum: String? = null,
    val beliefMillis: Double,
    val searchMillis: Double,
    val totalMillis: Double,
    val maximumDepth: Int? = null,
    val quiescenceUnresolvedBackups: Int = 0,
    val diagnostic: String? = null,
)

@Serializable
internal data class TacticalHorizonBandBenchmark(
    val horizon: TacticalHorizon,
    val acceptedTrials: Int,
    val completedTrials: Int,
    val totalTrials: Int,
)

@Serializable
internal data class TacticalHorizonCategoryBenchmark(
    val category: TacticalCategory,
    val acceptedTrials: Int,
    val completedTrials: Int,
    val totalTrials: Int,
)

@Serializable
internal data class TacticalHorizonBenchmarkResult(
    val leaf: LeafEvaluationConfig,
    val evaluatorConfigurationId: String?,
    val trials: List<TacticalHorizonBenchmarkTrial>,
    val completedTrials: Int,
    val acceptedTrials: Int,
    val totalTrials: Int,
    val acceptedRate: Double,
    val horizonResults: List<TacticalHorizonBandBenchmark>,
    val categoryResults: List<TacticalHorizonCategoryBenchmark>,
    val p50SearchMillis: Double?,
    val p95SearchMillis: Double?,
    val p50EvaluatorMicros: Double?,
    val p95EvaluatorMicros: Double?,
    val medianActualSimulations: Double?,
    val quiescenceUnresolvedBackups: Int,
)

@Serializable
internal data class TacticalHorizonBenchmarkReport(
    val schemaVersion: Int = 1,
    val documentKind: String = "tactical-horizon-benchmark-v1",
    val benchmarkVersion: String = TACTICAL_HORIZON_BENCHMARK_VERSION,
    val suiteVersion: String = TACTICAL_HORIZON_SUITE_VERSION,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val host: String,
    val sourcePacketSha256: String,
    val acceptedSetSha256: String,
    val authority: TacticalEvidenceAuthority = TacticalEvidenceAuthority.HUMAN_AUTHORITY,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val maxQuiescenceDecisions: Int,
    val explorationConstant: Double,
    val wallClockBudgetMillis: Long? = null,
    val cases: List<String>,
    val leafResults: List<TacticalHorizonBenchmarkResult>,
    val completed: Boolean,
    val failureReasons: List<String>,
)

internal class TacticalHorizonBenchmarkRunner(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val particles: Int = 8,
    private val simulations: Int = 64,
    private val maxPolicyDecisions: Int = 32,
    private val explorationConstant: Double = 1.4,
    private val maxQuiescenceDecisions: Int = 32,
    private val wallClockBudgetMillis: Long? = null,
    private val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
    private val leafConfigurations: List<LeafEvaluationConfig>,
    private val informationEvaluatorFactory: ((LeafEvaluationConfig) -> InformationStateEvaluator?)? = null,
) {
    private val factory = TacticalHorizonScenarioFactory(registry, manifest, actionSpaceProfile)
    private val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)

    init {
        require(particles > 0)
        require(simulations > 0)
        require(maxPolicyDecisions > 0)
        require(maxQuiescenceDecisions > 0)
        require(explorationConstant >= 0.0 && explorationConstant.isFinite())
        require(wallClockBudgetMillis == null || wallClockBudgetMillis > 0)
        require(leafConfigurations.isNotEmpty() && leafConfigurations.distinct().size == leafConfigurations.size)
        require(leafConfigurations.all {
            it in SearchTeacherLeafConfigurations.supported || it in SearchTeacherLeafConfigurations.experimental
        })
    }

    fun run(
        review: ValidatedTacticalAcceptedSet,
        acceptedSetSha256: String,
        cases: List<TacticalHorizonCase> = TacticalHorizonCatalog.cases,
    ): TacticalHorizonBenchmarkReport {
        require(cases.isNotEmpty())
        val packetByCase = review.packet.scenarios.associateBy { it.caseId }
        require(cases.all { it.id in review.acceptedByCase && it.id in packetByCase })
        cases.forEach { case ->
            val information = factory.create(case).informationState("p0")
            require(information.informationStateDigest == packetByCase.getValue(case.id).informationState.informationStateDigest) {
                "${case.id} no longer reproduces its reviewed information state"
            }
        }

        val results = leafConfigurations.map { leaf ->
            runTrial(leaf, cases.first(), review.acceptedByCase.getValue(cases.first().id))
            println("Horizon benchmark $leaf")
            summarize(leaf, cases.map { case ->
                runTrial(leaf, case, review.acceptedByCase.getValue(case.id))
            })
        }
        val failures = results.flatMap { result ->
            result.trials.mapNotNull { trial ->
                trial.diagnostic?.let { "${result.leaf}/${trial.caseId}:$it" }
            }
        }
        return TacticalHorizonBenchmarkReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            host = calibrationHost(),
            sourcePacketSha256 = review.labels.sourcePacketSha256,
            acceptedSetSha256 = acceptedSetSha256,
            actionSpaceProfile = actionSpaceProfile,
            particles = particles,
            simulations = simulations,
            maxPolicyDecisions = maxPolicyDecisions,
            maxQuiescenceDecisions = maxQuiescenceDecisions,
            explorationConstant = explorationConstant,
            wallClockBudgetMillis = wallClockBudgetMillis,
            cases = cases.map(TacticalHorizonCase::id),
            leafResults = results,
            completed = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    private fun runTrial(
        leaf: LeafEvaluationConfig,
        case: TacticalHorizonCase,
        acceptedSignatures: Set<String>,
    ): TacticalHorizonBenchmarkTrial {
        val world = factory.create(case)
        val information = world.informationState("p0")
        var beliefMillis = 0.0
        var searchMillis = 0.0
        return runCatching {
            val legal = world.expandChoices(2_048)
            require(legal.isExhaustive)
            require(acceptedSignatures.isNotEmpty() && acceptedSignatures.all {
                accepted -> legal.candidates.any { it.signature == accepted }
            })
            val beliefStarted = System.nanoTime()
            val belief = ArgentumKnownDeckBeliefWorldSource(world, registry).sample(
                information,
                knownDecks,
                ComponentSeeds.derive(case.rootSeed, "horizon-benchmark-belief"),
                particles,
            )
            beliefMillis = elapsedMillis(beliefStarted)
            val evaluator = informationEvaluatorFactory?.invoke(leaf)
            val searchConfig = InformationSetSearchConfig(
                simulations = simulations,
                explorationConstant = explorationConstant,
                maxPolicyDecisions = maxPolicyDecisions,
                maxQuiescenceDecisions = maxQuiescenceDecisions,
                leaf = leaf,
                wallClockBudgetMillis = wallClockBudgetMillis,
            )
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
                "p0",
                belief,
                ComponentSeeds.derive(case.rootSeed, "horizon-benchmark-search"),
            )
            searchMillis = elapsedMillis(searchStarted)
            TacticalHorizonBenchmarkTrial(
                caseId = case.id,
                horizon = case.horizon,
                category = case.category,
                informationStateDigest = information.informationStateDigest,
                chosenSignature = result.chosen.signature,
                chosenLabel = result.chosen.display.label,
                humanAccepted = result.chosen.signature in acceptedSignatures,
                acceptedSignatureCount = acceptedSignatures.size,
                candidateCount = result.candidates.size,
                rootValue = result.rootValue,
                actualSimulations = result.diagnostics.simulations,
                evaluatorConfigurationId = result.diagnostics.invokedEvaluatorConfigurationId,
                evaluatorCalls = result.diagnostics.evaluatorCalls,
                evaluatorNanos = result.diagnostics.evaluatorNanos,
                evaluatorOutputChecksum = result.diagnostics.evaluatorOutputChecksum,
                beliefMillis = beliefMillis,
                searchMillis = searchMillis,
                totalMillis = beliefMillis + searchMillis,
                maximumDepth = result.diagnostics.maximumDepth,
                quiescenceUnresolvedBackups = result.diagnostics.quiescenceUnresolvedBackups,
            )
        }.getOrElse { failure ->
            TacticalHorizonBenchmarkTrial(
                caseId = case.id,
                horizon = case.horizon,
                category = case.category,
                informationStateDigest = information.informationStateDigest,
                acceptedSignatureCount = acceptedSignatures.size,
                beliefMillis = beliefMillis,
                searchMillis = searchMillis,
                totalMillis = beliefMillis + searchMillis,
                diagnostic = "${failure::class.simpleName}:${failure.message}",
            )
        }
    }

    private fun summarize(
        leaf: LeafEvaluationConfig,
        trials: List<TacticalHorizonBenchmarkTrial>,
    ): TacticalHorizonBenchmarkResult {
        val completed = trials.filter { it.diagnostic == null }
        val searchMillis = completed.map(TacticalHorizonBenchmarkTrial::searchMillis)
        val evaluatorMicros = completed.mapNotNull { trial ->
            trial.evaluatorCalls.takeIf { it > 0 }?.let { trial.evaluatorNanos / it.toDouble() / 1_000.0 }
        }
        val simulations = completed.mapNotNull(TacticalHorizonBenchmarkTrial::actualSimulations).sorted()
        val configurations = completed.mapNotNull(TacticalHorizonBenchmarkTrial::evaluatorConfigurationId).distinct()
        return TacticalHorizonBenchmarkResult(
            leaf = leaf,
            evaluatorConfigurationId = configurations.singleOrNull(),
            trials = trials,
            completedTrials = completed.size,
            acceptedTrials = completed.count(TacticalHorizonBenchmarkTrial::humanAccepted),
            totalTrials = trials.size,
            acceptedRate = completed.count(TacticalHorizonBenchmarkTrial::humanAccepted).toDouble() /
                completed.size.coerceAtLeast(1),
            horizonResults = TacticalHorizon.entries.map { horizon ->
                val selected = trials.filter { it.horizon == horizon }
                TacticalHorizonBandBenchmark(
                    horizon,
                    selected.count { it.diagnostic == null && it.humanAccepted },
                    selected.count { it.diagnostic == null },
                    selected.size,
                )
            },
            categoryResults = TacticalCategory.entries.map { category ->
                val selected = trials.filter { it.category == category }
                TacticalHorizonCategoryBenchmark(
                    category,
                    selected.count { it.diagnostic == null && it.humanAccepted },
                    selected.count { it.diagnostic == null },
                    selected.size,
                )
            }.filter { it.totalTrials > 0 },
            p50SearchMillis = searchMillis.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) },
            p95SearchMillis = searchMillis.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
            p50EvaluatorMicros = evaluatorMicros.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) },
            p95EvaluatorMicros = evaluatorMicros.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
            medianActualSimulations = simulations.takeIf { it.isNotEmpty() }?.let { values ->
                if (values.size % 2 == 1) values[values.size / 2].toDouble()
                else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
            },
            quiescenceUnresolvedBackups = completed.sumOf(TacticalHorizonBenchmarkTrial::quiescenceUnresolvedBackups),
        )
    }

    private fun elapsedMillis(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0
}
