package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.research.run.*

private const val COVERAGE_PERFORMANCE_PROTOCOL = "decision-local-historical-performance-check-v1"
internal val coveragePerformanceIndices = listOf(2, 25, 43)

@Serializable
internal data class CoveragePerformanceTrial(val ordinal: Int, val warmup: Boolean,
    val materializationMillis: Double, val searchMillis: Double,
    val roots: List<DecisionLocalRootEvidence>, val stoppedSearch: GameRunResult)

@Serializable
internal data class CoveragePerformanceReport(val bindings: ResearchRunBindings,
    val provenance: ResearchRunProvenance, val generatedAtUtc: String,
    val preparationMillis: Double, val trials: List<CoveragePerformanceTrial>,
    val scope: String = "One excluded warmup and three measured repetitions in one JVM. " +
        "Each repetition reconstructs TRAIN roots 2,25,43, generates their 64-coordinate features and eight retained-seed terminal labels per candidate, " +
        "and plays the first eight historical control-versus-learned search decisions of pair8 legb. " +
        "Stopped searches are not game outcomes. No fresh expansion labels, fitting, or TEST use. " +
        "Cross-engine comparison must match all retained parity fields; this is fixed-work timing, not full-game throughput.")

/** Remove only measured clock fields; preserve choices, scores, work counts, and terminal labels. */
internal fun coveragePerformanceRootParity(root: DecisionLocalRootEvidence) = root.copy(
    candidates = root.candidates.map { it.copy(continuationRuntimeMillis = 0.0) })

internal fun coveragePerformanceGameParity(game: GameRunResult) = game.copy(
    elapsedMillis = 0.0,
    searchLatenciesMillis = game.searchLatenciesMillis.map { 0.0 },
    seatDiagnostics = game.seatDiagnostics.mapValues { (_, seat) -> seat.copy(
        searchLatenciesMillis = seat.searchLatenciesMillis.map { 0.0 },
        searchDecisionsDetail = seat.searchDecisionsDetail.map { it.copy(latencyMillis = 0.0,
            searchDiagnostics = it.searchDiagnostics.copy(evaluatorNanos = 0)) }) })

internal class DecisionLocalPerformanceCheck(private val repositoryRoot: Path,
    private val registry: CardRegistry, private val deck: DeckManifest) {
    fun run(parent: Path, pilot: Path, corpus: Path, gate: Path, output: Path): CoveragePerformanceReport {
        require(!Files.exists(output)) { "Performance outputs must be fresh" }
        val start = System.nanoTime()
        val source = ResearchRunProvenance.capture(repositoryRoot).also { it.requireReady() }
        // A benchmark may compare another committed engine; the ordinary 250-root runner keeps its historical guard.
        val p = DecisionLocalRootCoverage(repositoryRoot, registry, deck).prepare(parent, pilot, corpus, gate, 1,
            executionEngine = source.checkedOutEngineCommit)
        val assignments = coveragePerformanceIndices.map { i -> p.oldManifest.assignments.single { it.root.pairIndex == i } }
        require(assignments.all { it.split == DecisionLocalSplit.TRAIN })
        val binding = ResearchRunBindings(protocol = COVERAGE_PERFORMANCE_PROTOCOL, material = mapOf(
            "preparation" to p.plan.bindings.identity, "source" to source.outerCommit,
            "engine" to source.checkedOutEngineCommit, "historical-engine" to ROOT_COVERAGE_ENGINE,
            "roots" to coveragePerformanceIndices.joinToString(","), "schedule" to "one-warmup-three-measured:one-worker:8-primary-labels:64-feature-coordinates",
            "search" to "historical-pair8-legb-first8-search-decisions:8x64:frozen-opponent",
            "timing" to "monotonic-wall:exclude-preparation-and-artifact-writes:separate-materialization-and-search",
            "parity" to "complete-root-evidence-and-game-result:only-clock-fields-zeroed"))
        val artifacts = ResearchRunArtifacts(output, binding.identity)
        ResearchRunFiles.atomicWrite(output.resolve("bindings.json"), evidenceJson.encodeToString(binding))
        artifacts.register("bindings.json")
        ResearchRunFiles.atomicWrite(output.resolve("preparation.json"), evidenceJson.encodeToString(p.plan))
        artifacts.register("preparation.json")
        val preparationMillis = (System.nanoTime() - start) / 1e6
        val trials = mutableListOf<CoveragePerformanceTrial>()
        repeat(4) { ordinal ->
            val rootStart = System.nanoTime()
            val roots = assignments.map {
                DecisionLocalEvidenceMaterializer(pilot, registry, deck, p.plan.historicalPilot, p.historical).materialize(it, 8, 0)
            }
            val materializationMillis = (System.nanoTime() - rootStart) / 1e6
            val descriptor = tournamentDescriptor(p.control, p.learned, 8, 1)
            val searchStart = System.nanoTime()
            val game = p.arena.playWithPolicies(descriptor.gameId,
                ComponentSeeds.derive(ROOT_COVERAGE_BASE_SEED, 8, "learned-leaf-pilot-library-orders"),
                p.learned, p.control, maxSearchDecisions = 8)
            val searchMillis = (System.nanoTime() - searchStart) / 1e6
            require(!game.terminal && game.disposition == GameRunDisposition.STOPPED_LIMIT && game.exception == null)
            require(game.illegalResponses == 0 && game.fallbacks == 0 && game.invalidBeliefWeights == 0)
            require(game.seatDiagnostics.values.sumOf { it.searchDecisions } == 8)
            val normalizedRoots = roots.map(::coveragePerformanceRootParity)
            val normalizedGame = coveragePerformanceGameParity(game)
            if (trials.isNotEmpty()) {
                require(normalizedRoots == trials.first().roots) { "Root behavior changed across identical repetitions" }
                require(normalizedGame == trials.first().stoppedSearch) { "Search behavior changed across identical repetitions" }
            }
            trials += CoveragePerformanceTrial(ordinal, ordinal == 0, materializationMillis, searchMillis,
                normalizedRoots, normalizedGame)
            val name = "trial-$ordinal.json"
            ResearchRunFiles.atomicWrite(output.resolve(name), evidenceJson.encodeToString(trials.last()))
            artifacts.register(name)
            println("Performance repetition $ordinal: materialization=${materializationMillis}ms; search=${searchMillis}ms")
        }
        val report = CoveragePerformanceReport(binding, source, Instant.now().toString(), preparationMillis, trials)
        ResearchRunFiles.atomicWrite(output.resolve("report.json"), evidenceJson.encodeToString(report))
        artifacts.register("report.json")
        artifacts.finalize()
        ResearchRunArtifacts.loadAndVerify(output, binding.identity)
        return report
    }
}
