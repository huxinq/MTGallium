package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*

/** Terminal rollout samples have a different target and budget from adaptive search backups. */
@Serializable
internal data class TerminalRootContinuationConfig(
    val samplesPerAction: Int,
    val maximumContinuationPolicyDecisions: Int = 4096,
    val maximumTotalContinuations: Int,
    val seedRule: String = "position-root-terminal-continuation-v1",
) {
    init {
        require(samplesPerAction > 0 && maximumContinuationPolicyDecisions > 0 && maximumTotalContinuations > 0)
        require(seedRule == "position-root-terminal-continuation-v1")
    }
}

@Serializable
internal data class TerminalRootSample(
    val sampleIndex: Int, val particleIndex: Int, val futureSeed: Long, val continuationSeed: Long,
    val payoff: Double, val policyDecisions: Int,
    val rootPolicyDecisions: OpponentPolicyDecisionSummary,
    val opponentPolicyDecisions: OpponentPolicyDecisionSummary,
    val elapsedMillis: Double,
) {
    init {
        require(sampleIndex >= 0 && particleIndex >= 0 && payoff.isFinite() && payoff in -1.0..1.0)
        require(policyDecisions >= 0 && policyDecisions == rootPolicyDecisions.decisions + opponentPolicyDecisions.decisions)
        require(rootPolicyDecisions.evidenceInvalidatingReplacements == 0 && opponentPolicyDecisions.evidenceInvalidatingReplacements == 0)
        require(elapsedMillis.isFinite() && elapsedMillis >= 0)
    }
}

@Serializable
internal enum class TerminalRootActionDisposition { COMPLETE, NON_GAME_FAILURE, NOT_EXECUTED }

@Serializable
internal data class TerminalRootActionSamples(
    val action: SemanticChoice, val requestedSamples: Int,
    val samples: List<TerminalRootSample>, val disposition: TerminalRootActionDisposition,
    val diagnostic: String? = null,
    val meanTerminalPayoff: Double? = if (disposition == TerminalRootActionDisposition.COMPLETE) samples.map { it.payoff }.average() else null,
) {
    init {
        require(requestedSamples > 0 && samples.size <= requestedSamples)
        require(samples.map { it.sampleIndex } == samples.indices.toList())
        require(meanTerminalPayoff == if (disposition == TerminalRootActionDisposition.COMPLETE) samples.map { it.payoff }.average() else null)
        when (disposition) {
            TerminalRootActionDisposition.COMPLETE -> require(samples.size == requestedSamples && diagnostic == null)
            TerminalRootActionDisposition.NON_GAME_FAILURE -> require(samples.size < requestedSamples && !diagnostic.isNullOrBlank())
            TerminalRootActionDisposition.NOT_EXECUTED -> require(samples.isEmpty() && !diagnostic.isNullOrBlank())
        }
    }
}

/** Stop the root at its first failure, retaining completed samples and explicitly unexecuted work. */
internal fun collectTerminalRootActions(
    candidates: List<SemanticChoice>, requestedSamples: Int,
    sample: (SemanticChoice, Int) -> TerminalRootSample,
): List<TerminalRootActionSamples> {
    require(candidates.isNotEmpty() && candidates.map { it.signature }.distinct().size == candidates.size && requestedSamples > 0)
    var stopped = false
    return candidates.map { action ->
        if (stopped) TerminalRootActionSamples(action, requestedSamples, emptyList(), TerminalRootActionDisposition.NOT_EXECUTED,
            "Root stopped after a prior non-game failure")
        else {
            val completed = mutableListOf<TerminalRootSample>()
            try {
                repeat(requestedSamples) { index ->
                    val result = sample(action, index)
                    require(result.sampleIndex == index)
                    completed += result
                }
                TerminalRootActionSamples(action, requestedSamples, completed.toList(), TerminalRootActionDisposition.COMPLETE)
            } catch (failure: Exception) {
                stopped = true
                TerminalRootActionSamples(action, requestedSamples, completed.toList(), TerminalRootActionDisposition.NON_GAME_FAILURE,
                    "${failure::class.simpleName}: ${failure.message}")
            }
        }
    }
}

internal fun sampleTerminalRootActions(
    belief: BeliefBatch<Weighted<SearchWorld>>, information: PolicyInformationState,
    candidates: List<SemanticChoice>, search: InformationSetSearch,
    config: TerminalRootContinuationConfig, searchSeed: Long,
): List<TerminalRootActionSamples> {
    val actor = requireNotNull(information.actingPlayerId)
    require(actor == information.observation.perspectivePlayerId && !information.terminated)
    val menu = candidates.map { it.signature }.toSet()
    val worlds = belief.particles.map { particle ->
        require(particle.weight.isFinite() && particle.weight >= 0)
        val world = particle.value as? ArgentumSearchWorld ?: error("Terminal sampling requires adapter-owned hypothesis worlds")
        require(world.actorToAct() == actor && world.informationState(actor).informationStateDigest == information.informationStateDigest)
        val expansion = world.expandChoices()
        require(expansion.isProfileExhaustive && expansion.candidates.map { it.signature }.toSet() == menu)
        world
    }
    val indices = InformationSetSearch.productionRootParticleIndices(belief.particles.map { it.weight }, searchSeed, config.samplesPerAction)
    return collectTerminalRootActions(candidates, config.samplesPerAction) { action, index ->
        val started = System.nanoTime()
        // These coordinates intentionally omit the action: siblings share a posterior draw and
        // future stream. Divergent event consumption still prevents a claim of exact coupling.
        val futureSeed = ComponentSeeds.derive(searchSeed, config.seedRule, index, "future")
        val continuationSeed = ComponentSeeds.derive(searchSeed, config.seedRule, index, "continuation")
        val child = worlds[indices[index]].forkForHypotheticalSearch(futureSeed)
        val rebound = child.expandChoices().candidates.single { it.signature == action.signature }
        val first = child.step(rebound)
        check(first.accepted) { first.diagnostic ?: "Conditioned root transition was rejected" }
        val continuation = search.continueFirstUnvisitedEdgeToTerminal(child, actor, continuationSeed, index,
            childDepth = 1, maximumContinuationPolicyDecisions = config.maximumContinuationPolicyDecisions)
        TerminalRootSample(index, indices[index], futureSeed, continuationSeed, continuation.payoff, continuation.policyDecisions,
            continuation.rootPolicyDecisions, continuation.opponentPolicyDecisions, (System.nanoTime() - started) / 1_000_000.0)
    }
}

/** Declared prospective cap is checked before constructing worlds or starting continuation work. */
internal fun terminalRootWorkload(config: TerminalRootContinuationConfig, actionCounts: List<Int>, repetitions: Int, policies: Int): Int {
    require(actionCounts.isNotEmpty() && actionCounts.all { it > 0 } && repetitions > 0 && policies > 0)
    val actions = actionCounts.fold(0, Math::addExact)
    val total = Math.multiplyExact(Math.multiplyExact(actions, config.samplesPerAction), Math.multiplyExact(repetitions, policies))
    require(total <= config.maximumTotalContinuations) { "Terminal continuation workload $total exceeds declared cap ${config.maximumTotalContinuations}" }
    return total
}

@Serializable
internal data class TerminalRootScreenAccounting(
    val rootRepetitionRows: Int, val completeRows: Int, val refusedRows: Int,
    val requestedContinuations: Int, val completedTerminalSamples: Int,
    val nonGameFailedAttempts: Int, val unexecutedContinuations: Int,
    val preparationFailureRows: Int,
    val terminalWins: Int, val terminalLosses: Int, val terminalDraws: Int,
    val continuationPolicyDecisions: Int, val accumulatedSelectionMillis: Double,
)

/** Reconcile all requested work, including rows refused before any terminal sample was attempted. */
internal fun terminalRootScreenAccounting(report: PositionBankScreenReport, bank: RealGamePositionBankReport): TerminalRootScreenAccounting {
    require(report.plan.mode == PositionBankScreenMode.TERMINAL_CONTINUATIONS && report.plan.expectedBankIdentity == bank.bankIdentity)
    val config = requireNotNull(report.plan.terminalContinuation)
    val roots = bank.roots.associateBy { it.rootId }
    require(report.rows.map { Triple(it.rootId, it.policyId, it.repetition) }.distinct().size == report.rows.size)
    val expectedRows = report.selectedRootIds.flatMap { root -> report.plan.policies.flatMap { policy ->
        (0 until report.plan.repetitions).map { Triple(root, policy.search.id, it) }
    } }.toSet()
    require(report.rows.map { Triple(it.rootId, it.policyId, it.repetition) }.toSet() == expectedRows)
    require(report.valid == report.rows.none { it.disposition == PositionBankScreenDisposition.REFUSED })
    require(report.rows.groupBy { it.rootId to it.policyId }.values.all { rows ->
        rows.map { it.terminalBeliefWeights }.filter { it.isNotEmpty() }.distinct().size <= 1
    })
    val requested = terminalRootWorkload(config, report.selectedRootIds.map { roots.getValue(it).reconstructedCandidates.size },
        report.plan.repetitions, report.plan.policies.size)
    val samples = report.rows.flatMap { row ->
        require(row.disposition in setOf(PositionBankScreenDisposition.TERMINAL_CONTINUATIONS, PositionBankScreenDisposition.REFUSED))
        require(row.searchDiagnostics == null && row.searchRootValue == null && row.candidateStatistics.isEmpty() && row.rootActionEstimates.isEmpty())
        if (row.terminalRootActions.isEmpty()) require(row.disposition == PositionBankScreenDisposition.REFUSED)
        else {
            require(row.terminalRootActions.map { it.action } == roots.getValue(row.rootId).reconstructedCandidates)
            require(row.terminalRootActions.all { it.requestedSamples == config.samplesPerAction })
            require(row.terminalBeliefWeights.isNotEmpty() && row.terminalBeliefWeights.all { it.isFinite() && it >= 0 })
            val seed = requireNotNull(row.searchSeed)
            val coordinates = InformationSetSearch.productionRootParticleIndices(row.terminalBeliefWeights, seed, config.samplesPerAction)
            row.terminalRootActions.flatMap { it.samples }.forEach { sample ->
                require(sample.particleIndex == coordinates[sample.sampleIndex])
                require(sample.futureSeed == ComponentSeeds.derive(seed, config.seedRule, sample.sampleIndex, "future"))
                require(sample.continuationSeed == ComponentSeeds.derive(seed, config.seedRule, sample.sampleIndex, "continuation"))
                require(sample.policyDecisions <= config.maximumContinuationPolicyDecisions)
            }
            require((row.disposition == PositionBankScreenDisposition.TERMINAL_CONTINUATIONS) ==
                row.terminalRootActions.all { it.disposition == TerminalRootActionDisposition.COMPLETE })
        }
        row.terminalRootActions.flatMap { it.samples }
    }
    require(samples.all { it.payoff in setOf(-1.0, 0.0, 1.0) }) { "This two-player screen expects win/loss/draw terminal payoffs" }
    val failures = report.rows.sumOf { row -> row.terminalRootActions.count { it.disposition == TerminalRootActionDisposition.NON_GAME_FAILURE } }
    val unexecuted = requested - samples.size - failures
    require(unexecuted >= 0)
    return TerminalRootScreenAccounting(report.rows.size, report.rows.count { it.disposition == PositionBankScreenDisposition.TERMINAL_CONTINUATIONS },
        report.rows.count { it.disposition == PositionBankScreenDisposition.REFUSED }, requested, samples.size, failures, unexecuted,
        report.rows.count { it.terminalRootActions.isEmpty() }, samples.count { it.payoff == 1.0 }, samples.count { it.payoff == -1.0 },
        samples.count { it.payoff == 0.0 }, samples.sumOf { it.policyDecisions }, report.rows.sumOf { it.selectionMillis ?: 0.0 })
}
