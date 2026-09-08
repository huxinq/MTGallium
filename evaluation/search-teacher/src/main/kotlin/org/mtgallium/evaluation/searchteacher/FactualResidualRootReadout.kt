package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.*
import org.mtgallium.research.run.*

internal class FactualResidualDeadline(maximumSeconds: Int) {
    private val started = System.nanoTime()
    private val budget = Math.multiplyExact(maximumSeconds.toLong(), 1_000_000_000L)
    val elapsedSeconds: Double get() = (System.nanoTime() - started) / 1_000_000_000.0
    fun requireRemaining() {
        check(!Thread.currentThread().isInterrupted && System.nanoTime() - started < budget) { "Factual residual Stage A deadline reached" }
    }
}

@Serializable
internal enum class FactualResidualArm { INCUMBENT, RESIDUAL }
@Serializable
internal enum class FactualResidualReadoutDisposition { SEARCHED, REFUSED, UNEXECUTED }

@Serializable
internal data class FactualResidualSearchRow(
    val rootId: String,
    val seedGroupId: String,
    val gameId: String,
    val viewer: String,
    val leg: Int,
    val repetition: Int,
    val arm: FactualResidualArm,
    val searchSeed: Long,
    val disposition: FactualResidualReadoutDisposition,
    val searchAttempted: Boolean,
    val reconstructionSeconds: Double,
    /** Wall time from before actual information/menu construction through selection, including validation overhead. */
    val allSelectionMillis: Double?,
    val chosen: SemanticChoice? = null,
    val candidateStatistics: List<SearchCandidateStatistics> = emptyList(),
    val searchDiagnostics: InformationSetSearchDiagnostics? = null,
    val policyIdentity: String? = null,
    val rootBeliefWeights: List<Double> = emptyList(),
    val rootInformationDigest: String? = null,
    val learnedCutoffCalls: Int = 0,
    val failure: String? = null,
) {
    init {
        require(viewer in setOf("p0", "p1") && leg in 0..1 && repetition in 0..1)
        require(reconstructionSeconds.isFinite() && reconstructionSeconds >= 0)
        require(allSelectionMillis == null || allSelectionMillis.isFinite() && allSelectionMillis >= 0)
        require(learnedCutoffCalls >= 0 && (arm == FactualResidualArm.RESIDUAL || learnedCutoffCalls == 0))
        if (disposition == FactualResidualReadoutDisposition.SEARCHED) {
            require(searchAttempted && chosen != null && searchDiagnostics != null && policyIdentity != null && failure == null)
            require(candidateStatistics.any { it.choice == chosen } && rootBeliefWeights.size == 8 && rootInformationDigest != null)
            require(allSelectionMillis != null)
        } else require(chosen == null && candidateStatistics.isEmpty() && searchDiagnostics == null && failure != null)
        if (disposition == FactualResidualReadoutDisposition.UNEXECUTED) require(!searchAttempted)
    }
}

@Serializable
internal data class FactualResidualReadoutGate(
    val plannedSearches: Int,
    val attemptedSearches: Int,
    val completeSearches: Int,
    val refusedRows: Int,
    val unexecutedSearches: Int,
    val changedGroups: Int,
    val learnedCutoffCalls: Int,
    val incumbentAllSelectionMillis: Double,
    val residualAllSelectionMillis: Double,
    val selectionCostRatio: Double?,
    val checks: Map<String, Boolean>,
    val eligibleForFactualTargets: Boolean,
) {
    init {
        require(plannedSearches == 128 && completeSearches + refusedRows + unexecutedSearches == plannedSearches)
        require(attemptedSearches in completeSearches..plannedSearches && changedGroups in 0..16)
        require(selectionCostRatio == null || selectionCostRatio.isFinite() && selectionCostRatio >= 0)
        require(eligibleForFactualTargets == checks.values.all { it } && checks.isNotEmpty())
    }
}

/** Every allocated coordinate is present; unsuccessful execution cannot become a zero-valued comparison. */
internal fun factualResidualReadoutGate(allocation: FactualResidualAllocation,
    rows: List<FactualResidualSearchRow>): FactualResidualReadoutGate {
    val roots = allocation.games.filter { it.role == FactualResidualDataRole.SCREEN }
    val expected = roots.flatMap { game -> (0..1).flatMap { repetition -> FactualResidualArm.entries.map { arm ->
        Triple(requireNotNull(game.root).assignment.rootId, repetition, arm)
    } } }.toSet()
    require(rows.size == 128 && rows.map { Triple(it.rootId, it.repetition, it.arm) }.toSet() == expected)
    rows.forEach { row ->
        val game = roots.single { it.root!!.assignment.rootId == row.rootId }
        require(row.seedGroupId == game.seedGroupId && row.gameId == game.gameId && row.viewer == game.viewer && row.leg == game.leg)
    }
    val complete = rows.all { it.disposition == FactualResidualReadoutDisposition.SEARCHED }
    var matched = complete
    val changed = mutableSetOf<String>()
    roots.forEach { game -> (0..1).forEach { repetition ->
        val pair = rows.filter { it.rootId == game.root!!.assignment.rootId && it.repetition == repetition }
        val control = pair.single { it.arm == FactualResidualArm.INCUMBENT }
        val learned = pair.single { it.arm == FactualResidualArm.RESIDUAL }
        require(control.searchSeed == learned.searchSeed)
        if (control.disposition == FactualResidualReadoutDisposition.SEARCHED && learned.disposition == FactualResidualReadoutDisposition.SEARCHED) {
            val menu = game.root!!.candidates.sortedBy { it.signature }
            matched = matched && control.candidateStatistics.map { it.choice }.sortedBy { it.signature } == menu &&
                learned.candidateStatistics.map { it.choice }.sortedBy { it.signature } == menu &&
                control.rootBeliefWeights == learned.rootBeliefWeights && control.rootInformationDigest == learned.rootInformationDigest
            if (control.chosen != learned.chosen) changed += game.seedGroupId
        }
    } }
    val controlCost = rows.filter { it.arm == FactualResidualArm.INCUMBENT }.sumOf { it.allSelectionMillis ?: 0.0 }
    val learnedCost = rows.filter { it.arm == FactualResidualArm.RESIDUAL }.sumOf { it.allSelectionMillis ?: 0.0 }
    val ratio = if (complete && controlCost > 0) learnedCost / controlCost else null
    val exposure = rows.sumOf { it.learnedCutoffCalls }
    val checks = linkedMapOf("complete-128-searches" to complete, "matched-menus-information-belief-weights" to matched,
        "at-least-eight-changed-groups" to (changed.size >= 8), "learned-cutoff-exposure" to (exposure > 0),
        "all-selection-cost-at-most-1.10" to (ratio != null && ratio <= 1.10))
    return FactualResidualReadoutGate(128, rows.count { it.searchAttempted },
        rows.count { it.disposition == FactualResidualReadoutDisposition.SEARCHED },
        rows.count { it.disposition == FactualResidualReadoutDisposition.REFUSED },
        rows.count { it.disposition == FactualResidualReadoutDisposition.UNEXECUTED }, changed.size, exposure,
        controlCost, learnedCost, ratio, checks, checks.values.all { it })
}

internal fun readoutFactualResidualRoots(plan: FactualResidualStudyPlan, allocation: FactualResidualAllocation,
    inputs: LoadedFactualResidualInputs, trajectories: Map<Pair<String, String>, FactualResidualInput>,
    registry: CardRegistry, deck: DeckManifest, evaluator: FactualOutcomeResidualEvaluator, output: Path,
    deadline: FactualResidualDeadline): List<FactualResidualSearchRow> {
    val games = allocation.games.filter { it.role == FactualResidualDataRole.SCREEN }
    val completed = AtomicInteger()
    val progress = System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of)
    return parallelMapOrdered(games.size, plan.workers) { rootIndex ->
        val game = games[rootIndex]
        val root = requireNotNull(game.root)
        val parent = inputs.parents.getValue(game.sourceRunIdentity)
        // Reading the whole factual trajectory stays outside decision cost, inside the stage deadline.
        val prepared = runCatching {
            deadline.requireRemaining()
            val input = trajectories.getValue(game.sourceRunIdentity to game.gameId)
            verifyFactualResidualInput(input)
            val trajectory = loadVerifiedFactualIncumbentTrajectory(Path.of(input.directory), input.identity)
            require(trajectory.disposition == FactualIncumbentTrajectoryDisposition.ADMITTED)
            require(trajectory.request.gameId == game.gameId && trajectory.request.viewer == game.viewer &&
                trajectory.request.sourceRunIdentity == game.sourceRunIdentity && trajectory.request.seedGroupId == game.seedGroupId)
            val replayPath = ResearchRunFiles.resolveBelow(Path.of(trajectory.request.sourceDirectory), requireNotNull(trajectory.source).replayReference)
            require(researchSha256File(replayPath) == trajectory.source.replaySha256)
            val replay = readVerifiedCanonicalSemanticReplay(replayPath)
            requireFactualIncumbentReplay(trajectory.request, parent,
                parent.comparisons.flatMap { it.pairs }.flatMap { it.games }.single { it.gameId == game.gameId }, replay)
            Triple(trajectory, replay, plan.incumbent.policy(parent.plan.baseSeed))
        }
        (0..1).flatMap { repetition ->
            // Counterbalance interleaved host load; each coordinate still gets its own reconstructed world/session.
            val arms = if ((rootIndex + repetition) % 2 == 0) FactualResidualArm.entries.toList() else FactualResidualArm.entries.reversed()
            arms.map { arm ->
                val seed = ComponentSeeds.derive(game.gameId, root.assignment.decisionIndex, parent.plan.baseSeed, plan.searchSeedDomain, repetition)
                var attempted = false
                var reconstruction = 0.0
                var selectionStarted: Long? = null
                var selectionMillis: Double? = null
                var exposure = 0
                val row = runCatching {
                    deadline.requireRemaining()
                    val (trajectory, replay, policy) = prepared.getOrThrow()
                    val parameters = plan.incumbent.parameters(parent.plan.baseSeed).let { baseline ->
                        if (arm == FactualResidualArm.INCUMBENT) baseline else baseline.copy(leaf = baseline.leaf.copy(
                            evaluator = LeafEvaluator.MTGALLIUM_FACTUAL_OUTCOME_RESIDUAL_V1))
                    }
                    require(!parameters.searchReuse.enabled)
                    val informationEvaluator = if (arm == FactualResidualArm.INCUMBENT) plan.incumbent.informationEvaluator()
                        else evaluator.observedEvaluationBy { _, _, _ -> exposure++ }
                    val reconstructionStarted = System.nanoTime()
                    val actual = createSemanticReplayWorld(registry, deck, game.gameId, trajectory.source!!.gameSeed,
                        parent.plan.baseSeed, 0, parameters.actionSpaceProfile)
                    val session = SearchTeacherPolicySession(actual, game.viewer,
                        mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck), parameters, defaultMonoRedOpponentPolicy(), game.gameId,
                        policy.effectiveRootRolloutPolicy(), policy.effectiveOpponentRolloutPolicy(), informationEvaluator)
                    replaySemanticPrefix(root.assignment.decisionIndex, replay, ArgentumSemanticReplayWorld(actual),
                        RecordedReplayStateEquivalence.currentEngine(replay.header.engineVersion),
                        beforeDecision = { deadline.requireRemaining() },
                        afterDecision = { index, actor, choice, private -> session.observeAccepted(actual, actor, choice, index, private) })
                    reconstruction = (System.nanoTime() - reconstructionStarted) / 1e9
                    deadline.requireRemaining()
                    selectionStarted = System.nanoTime()
                    require(actual.actorToAct() == game.viewer)
                    val information = actual.informationState(game.viewer)
                    require(information.informationStateDigest == trajectory.rows[root.assignment.decisionIndex].information.informationStateDigest)
                    val menu = actual.expandChoices().candidates
                    require(menu.sortedBy { it.signature } == root.candidates.sortedBy { it.signature })
                    attempted = true
                    val selection = session.select(actual, game.viewer, seed)
                    selectionMillis = (System.nanoTime() - selectionStarted!!) / 1e6
                    val search = requireNotNull(selection.search) { "Allocated searched root became automatic" }
                    require(selection.kind == SearchTeacherSelectionKind.SEARCHED && selection.choice in menu)
                    requireValidScreenSearch(search.diagnostics)
                    require(search.diagnostics.simulations == 56 && search.diagnostics.freshSimulations == 56 &&
                        search.diagnostics.reusedSimulations == 0 && search.diagnostics.particles == 8)
                    require(search.candidates.map { it.choice }.sortedBy { it.signature } == root.candidates.sortedBy { it.signature })
                    FactualResidualSearchRow(root.assignment.rootId, game.seedGroupId, game.gameId, game.viewer, game.leg,
                        repetition, arm, seed, FactualResidualReadoutDisposition.SEARCHED, true, reconstruction, selectionMillis,
                        selection.choice, search.candidates, search.diagnostics, session.policyIdentity,
                        session.beliefBatch().particles.map { it.weight }, information.informationStateDigest, exposure)
                }.getOrElse { failure ->
                    if (selectionMillis == null) selectionMillis = selectionStarted?.let { (System.nanoTime() - it) / 1e6 }
                    FactualResidualSearchRow(root.assignment.rootId, game.seedGroupId, game.gameId, game.viewer, game.leg,
                        repetition, arm, seed, if (attempted) FactualResidualReadoutDisposition.REFUSED else FactualResidualReadoutDisposition.UNEXECUTED,
                        attempted, reconstruction, selectionMillis, learnedCutoffCalls = exposure,
                        failure = "${failure.javaClass.simpleName}: ${failure.message}")
                }
                writeJsonAtomically(output.resolve("rows/${root.assignment.rootId.substringAfterLast(':')}-$repetition-${arm.name}.json"), row)
                publishDurableRunProgress(progress, completed.incrementAndGet(), 128, "factual residual root readout", root.assignment.rootId, "searches")
                row
            }
        }
    }.flatten().sortedWith(compareBy({ it.rootId }, { it.repetition }, { it.arm.ordinal }))
}
