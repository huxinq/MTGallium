package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Path
import java.time.Instant
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.searchteacher.DeterminizedArgentumHeuristicOpponentPolicy
import org.mtgallium.agent.searchteacher.FaceBurnOpponentPolicy
import org.mtgallium.agent.searchteacher.HoldBurnOpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy

internal class BeliefComparisonEvaluation(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val profile: FrozenSearchProfile,
    private val baseSeed: Long,
    private val checkpointRoot: Path? = null,
) {
    fun run(mainPairs: Int, heldOutPairs: Int, workerThreads: Int): BeliefComparisonReport {
        val opponents = listOf(
            ArenaPolicyKind.HEURISTIC to mainPairs,
            ArenaPolicyKind.CONSERVATIVE_COMBAT to heldOutPairs,
            ArenaPolicyKind.AGGRESSIVE_TRADE to heldOutPairs,
            ArenaPolicyKind.RANDOMIZED_HEURISTIC_20 to heldOutPairs,
        )
        val crossPlay = opponents.map { (opponent, pairs) ->
            val consistency = arena(BeliefMode.CONSISTENCY_ONLY_V1)
            val conditioned = arena(BeliefMode.POLICY_CONDITIONED_V1)
            val consistencyReport = pairedArena(
                consistency, "${profile.id}:consistency", opponent, pairs, baseSeed, workerThreads,
                checkpointRoot,
            )
            val conditionedReport = pairedArena(
                conditioned, "${profile.id}:conditioned", opponent, pairs, baseSeed, workerThreads,
                checkpointRoot,
            )
            BeliefModeCrossPlay(
                opponent = opponent,
                consistencyOnly = consistencyReport,
                policyConditioned = conditionedReport,
                conditionedMinusConsistency = conditionedReport.pointImprovement - consistencyReport.pointImprovement,
            )
        }
        val conditionedGames = crossPlay.flatMap { it.policyConditioned.games }
        val updates = conditionedGames.sumOf { it.beliefUpdates }
        val lowEssFraction = if (updates == 0) 1.0 else {
            conditionedGames.sumOf { it.lowEssUpdates }.toDouble() / updates
        }
        val invalidWeights = conditionedGames.sumOf { it.invalidBeliefWeights }
        val operationalFailures = buildList {
            if (crossPlay.any { mode ->
                    listOf(mode.consistencyOnly, mode.policyConditioned).any { arena ->
                        arena.completeGames != arena.gameCount
                    }
                }) add("belief cross-play contains non-terminal games")
            if (crossPlay.flatMap { listOf(it.consistencyOnly, it.policyConditioned) }
                    .flatMap(PairedArenaReport::games)
                    .any { it.exception != null || it.fallbacks > 0 || it.illegalResponses > 0 || it.stepLimit }) {
                add("belief cross-play contains an operational failure")
            }
        }
        val failures = buildList {
            val main = crossPlay.single { it.opponent == ArenaPolicyKind.HEURISTIC }
            if (main.conditionedMinusConsistency < 0.0) {
                add("policy-conditioned cross-play underperformed consistency-only")
            }
            crossPlay.filter { it.opponent != ArenaPolicyKind.HEURISTIC }.forEach { heldOut ->
                if (heldOut.conditionedMinusConsistency < -0.02) {
                    add("${heldOut.opponent} regressed by more than two percentage points")
                }
            }
            if (invalidWeights > 0) add("policy-conditioned belief produced invalid weights")
            if (lowEssFraction >= 0.05) add("at least 5% of conditioned updates fell below K/10 ESS")
            addAll(operationalFailures)
        }
        val conditionedModeSelected = failures.isEmpty()
        return BeliefComparisonReport(
            generatedAtUtc = Instant.now().toString(),
            profileId = profile.id,
            mainPairs = mainPairs,
            heldOutPairs = heldOutPairs,
            crossPlay = crossPlay,
            conditionedLowEssFraction = lowEssFraction,
            conditionedInvalidWeights = invalidWeights,
            selectedTeacherMode = if (conditionedModeSelected) {
                BeliefMode.POLICY_CONDITIONED_V1
            } else {
                BeliefMode.CONSISTENCY_ONLY_V1
            },
            evaluationPassed = operationalFailures.isEmpty(),
            conditionedModeSelected = conditionedModeSelected,
            failureReasons = failures,
        )
    }

    private fun arena(mode: BeliefMode) = SearchTeacherArena(
        registry = registry,
        manifest = manifest,
        profile = profile,
        baseSeed = baseSeed,
        beliefMode = mode,
    )
}

internal class OpponentModelEvaluation(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val profile: FrozenSearchProfile,
    private val baseSeed: Long,
    private val checkpointRoot: Path? = null,
) {
    fun run(pairCount: Int, workerThreads: Int): OpponentModelAblationReport {
        val models: List<OpponentPolicy> = listOf(
            defaultMonoRedOpponentPolicy(),
            DeterminizedArgentumHeuristicOpponentPolicy(),
            UniformOpponentPolicy,
            FaceBurnOpponentPolicy(),
            HoldBurnOpponentPolicy(),
        )
        return OpponentModelAblationReport(
            generatedAtUtc = Instant.now().toString(),
            profileId = profile.id,
            pairCountPerModel = pairCount,
            models = models.map { model ->
                val arena = SearchTeacherArena(
                    registry = registry,
                    manifest = manifest,
                    profile = profile,
                    baseSeed = baseSeed,
                    opponentModel = model,
                )
                OpponentModelAblation(
                    modelId = model.id,
                    arena = pairedArena(
                        arena, "${profile.id}:${model.id}", ArenaPolicyKind.HEURISTIC,
                        pairCount, baseSeed, workerThreads,
                        checkpointRoot,
                    ),
                )
            },
        )
    }
}

internal class PopulationEvaluation(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val profile: FrozenSearchProfile,
    private val baseSeed: Long,
    private val checkpointRoot: Path? = null,
) {
    fun run(pairCount: Int, workerThreads: Int): PopulationEvaluationReport {
        val opponents = listOf(
            ArenaPolicyKind.UNIFORM_RANDOM,
            ArenaPolicyKind.HEURISTIC,
            ArenaPolicyKind.FACE_BURN,
            ArenaPolicyKind.HOLD_BURN,
            ArenaPolicyKind.CONSERVATIVE_COMBAT,
            ArenaPolicyKind.AGGRESSIVE_TRADE,
            ArenaPolicyKind.RANDOMIZED_HEURISTIC_20,
        )
        val reports = opponents.map { opponent ->
            pairedArena(
                arena = SearchTeacherArena(registry, manifest, profile, baseSeed),
                profileId = profile.id,
                opponent = opponent,
                pairCount = pairCount,
                baseSeed = baseSeed,
                workerThreads = workerThreads,
                checkpointRoot = checkpointRoot,
            )
        }
        val heldOut = reports.filter {
            it.opponent in setOf(
                ArenaPolicyKind.CONSERVATIVE_COMBAT,
                ArenaPolicyKind.AGGRESSIVE_TRADE,
                ArenaPolicyKind.RANDOMIZED_HEURISTIC_20,
            )
        }
        val aggregate = heldOut.map { it.pointImprovement }.average()
        val random = reports.single { it.opponent == ArenaPolicyKind.UNIFORM_RANDOM }
        val failures = buildList {
            if (random.confidenceLower <= 0.0) add("search did not decisively beat uniform random")
            if (aggregate <= 0.0) add("held-out aggregate improvement is not positive")
            heldOut.filter { it.pointImprovement < -0.02 }.forEach {
                add("${it.opponent} regression exceeds two percentage points")
            }
            if (reports.any { it.completeGames != it.gameCount }) add("population contains non-terminal games")
            if (reports.flatMap { it.games }.any { it.exception != null || it.fallbacks > 0 || it.illegalResponses > 0 }) {
                add("population contains an operational failure")
            }
        }
        return PopulationEvaluationReport(
            generatedAtUtc = Instant.now().toString(),
            profileId = profile.id,
            pairCountPerOpponent = pairCount,
            crossPlay = reports,
            heldOutAggregateImprovement = aggregate,
            passed = failures.isEmpty(),
            failureReasons = failures,
        )
    }
}
