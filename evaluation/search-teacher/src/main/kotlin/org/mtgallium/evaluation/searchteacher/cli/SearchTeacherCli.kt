package org.mtgallium.evaluation.searchteacher.cli

import java.nio.file.Path
import org.mtgallium.evaluation.searchteacher.ArenaPolicyKind

internal data class SearchTeacherSuite(val id: String)

internal object SearchTeacherSuites {
    private val definitions = setOf(
        "smoke",
        "arena",
        "arena-shard",
        "arena-merge",
        "tactical",
        "tactical-authoring",
        "tactical-horizon-authoring",
        "tactical-horizon-check",
        "evaluator-comparison",
        "tactical-proof",
        "tactical-proof-benchmark",
        "replay-review-decisions",
        "replay-review-case-intake",
        "pilot-calibrate",
        "legacy-tactical-benchmark",
        "tournament",
        "tournament-v3-calibrated",
        "outcome-qualification-preflight",
        "outcome-qualification-pilot",
        "search-budget-frontier-preflight",
        "search-budget-frontier-pilot",
        "search-budget-frontier-extension-preflight",
        "search-budget-frontier-extension",
        "baseline-factorial-tournament",
        "baseline-factorial-smoke",
        "tree-reuse-validation",
        "calibrate",
        "corpus",
        "ablations",
        "belief",
        "opponent-models",
        "population",
        "review",
        "replay",
        "throughput",
        "latency-preflight",
        "tournament-performance",
        "tournament-remediation",
        "tournament-remediation-check",
        "tournament-remediation-probe",
        "tournament-fallback-diagnostic",
        "response-window-inventory",
        "player-choice-inventory",
        "tournament-amendment",
        "inspection",
        "play",
        "baseline-hardening",
        "issue-0013-stage-a",
        "issue-0013-stage-b-panel",
        "issue-0013-stage-b",
        "issue-0013-stage-b-reviewed-secondary",
        "issue-0013-blinded-review",
        "standalone-mana-timing-experiment",
        "root-search-evidence-repeatability",
        "neural-behavioral-cloning",
        "neural-capacity-diagnostic",
        "neural-memorization-diagnostic",
        "neural-saturation-trajectory-diagnostic",
        "neural-candidate-update-scale-diagnostic",
        "neural-population-scaling-diagnostic",
        "neural-stability-boundary-diagnostic",
        "neural-final-boundary-diagnostic",
        "neural-cohort-continuation-preflight",
        "neural-cohort-continuation-diagnostic",
        "neural-anchor-crossing-preflight",
        "neural-anchor-crossing-diagnostic",
        "neural-held-out-generalization-preflight",
        "neural-held-out-generalization-diagnostic",
    ).associateWith(::SearchTeacherSuite)

    fun require(id: String): SearchTeacherSuite =
        requireNotNull(definitions[id]) { "Unknown suite $id" }
}

internal data class SearchTeacherCli(
    val suite: String = "smoke",
    val seed: Long = 20260823L,
    val pairs: Int = 1,
    val opponent: ArenaPolicyKind = ArenaPolicyKind.HEURISTIC,
    val profilePath: Path? = null,
    val caseLimit: Int = 48,
    val threads: Int = 1,
    val games: Int = 1,
    val heldOutPairs: Int = 500,
    val corpusManifest: Path? = null,
    val reviewItems: Int = 100,
    val surprisingCases: Int = 20,
    val resume: Boolean = true,
    val pairOffset: Int = 0,
    val shardDirectory: Path? = null,
    val perspective: String = "p0",
    val proofReview: Path? = null,
    val particles: Int = 8,
    val simulations: Int = 64,
    val maxPolicyDecisions: Int = 32,
    val sourceRunIdentity: String? = null,
    val replayReviewDraft: Path? = null,
    val replayReviewSafeBundle: Path? = null,
    val replayReviewCanonicalReplay: Path? = null,
    val replayReviewCase: Path? = null,
    val outputPath: Path? = null,
    val rootLimit: Int = 32,
    val repetitions: Int = 16,
) {
    companion object {
        fun parse(args: Array<String>): SearchTeacherCli {
            var parsed = SearchTeacherCli()
            var index = 0
            while (index < args.size) {
                val option = args[index]
                parsed = when (option) {
                    "--suite" -> parsed.copy(suite = args.value(++index, option))
                    "--seed" -> parsed.copy(seed = args.value(++index, option).toLong())
                    "--pairs" -> parsed.copy(pairs = args.value(++index, option).toInt())
                    "--opponent" -> parsed.copy(
                        opponent = ArenaPolicyKind.valueOf(args.value(++index, option).uppercase())
                    )
                    "--profile" -> parsed.copy(profilePath = args.path(++index, option))
                    "--case-limit" -> parsed.copy(caseLimit = args.value(++index, option).toInt())
                    "--threads" -> parsed.copy(threads = args.value(++index, option).toInt())
                    "--games" -> parsed.copy(games = args.value(++index, option).toInt())
                    "--heldout-pairs" -> parsed.copy(heldOutPairs = args.value(++index, option).toInt())
                    "--corpus-manifest" -> parsed.copy(corpusManifest = args.path(++index, option))
                    "--review-items" -> parsed.copy(reviewItems = args.value(++index, option).toInt())
                    "--surprising-cases" -> parsed.copy(surprisingCases = args.value(++index, option).toInt())
                    "--no-resume" -> parsed.copy(resume = false)
                    "--pair-offset" -> parsed.copy(pairOffset = args.value(++index, option).toInt())
                    "--shard-dir" -> parsed.copy(shardDirectory = args.path(++index, option))
                    "--perspective" -> parsed.copy(perspective = args.value(++index, option))
                    "--proof-review" -> parsed.copy(proofReview = args.path(++index, option))
                    "--particles" -> parsed.copy(particles = args.value(++index, option).toInt())
                    "--simulations" -> parsed.copy(simulations = args.value(++index, option).toInt())
                    "--max-policy-decisions" -> parsed.copy(
                        maxPolicyDecisions = args.value(++index, option).toInt()
                    )
                    "--source-run" -> parsed.copy(sourceRunIdentity = args.value(++index, option))
                    "--replay-review-draft" -> parsed.copy(replayReviewDraft = args.path(++index, option))
                    "--safe-inspection" -> parsed.copy(replayReviewSafeBundle = args.path(++index, option))
                    "--canonical-replay" -> parsed.copy(replayReviewCanonicalReplay = args.path(++index, option))
                    "--replay-review-case" -> parsed.copy(replayReviewCase = args.path(++index, option))
                    "--output" -> parsed.copy(outputPath = args.path(++index, option))
                    "--root-limit" -> parsed.copy(rootLimit = args.value(++index, option).toInt())
                    "--repetitions" -> parsed.copy(repetitions = args.value(++index, option).toInt())
                    else -> error("Unknown option $option")
                }
                index++
            }
            parsed.validate()
            return parsed
        }

        private fun SearchTeacherCli.validate() {
            SearchTeacherSuites.require(suite)
            require(pairs > 0)
            require(pairOffset >= 0)
            require(caseLimit in 1..48)
            require(threads > 0)
            require(games > 0)
            require(heldOutPairs > 0)
            require(reviewItems > 0)
            require(surprisingCases >= 20)
            require(perspective in setOf("p0", "p1"))
            require(particles > 0)
            require(simulations > 0)
            require(maxPolicyDecisions > 0)
            require(rootLimit in 1..32)
            require(repetitions > 0)
        }

        private fun Array<String>.value(index: Int, option: String): String =
            getOrNull(index) ?: error("$option requires a value")

        private fun Array<String>.path(index: Int, option: String): Path =
            Path.of(value(index, option)).toAbsolutePath().normalize()
    }
}
