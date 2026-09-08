package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mtgallium.agent.infoset.core.*

@Tag("public-source")
class FactualResidualStudyTest {
    @Test fun `training group selection is lexical development only and refuses missing legs`() {
        val inventory = games().map { g -> RealGamePositionBankGame(g.sourceRunIdentity, g.gameId,
            g.seedGroupId.removePrefix("group-").toInt(), g.leg, g.seedGroupId, g.originalPartition, true, 1, emptyList()) }
        assertEquals((0..15).map { group(it) }.toSet(), factualResidualTrainingGroups(inventory.reversed()))
        assertFails { factualResidualTrainingGroups(inventory.dropLast(1)) }
        assertFails { factualResidualTrainingGroups(inventory.mapIndexed { i, g -> if (i == 0) g.copy(validPair = false) else g }) }
        assertFails { requireFactualResidualAllocation(games().mapIndexed { i, g ->
            if (i == 0) g.copy(originalPartition = RealGamePositionPartition.VALIDATION) else g }) }
    }

    @Test fun `fitting frames retain endpoints without extra weight for longer legs`() {
        assertEquals(listOf(0), factualResidualFrameIndices(1))
        assertEquals((0..11).toList(), factualResidualFrameIndices(12))
        val frames = factualResidualFrameIndices(322)
        assertEquals(32, frames.distinct().size)
        assertEquals(0, frames.first())
        assertEquals(321, frames.last())
        assertEquals(1.0 / 32, frames.sumOf { 1.0 / (16 * 2 * frames.size) })
        assertFails { factualResidualFrameIndices(0) }
    }

    @Test fun `screen root hash selection ignores input ordering and requires full distinct menu`() {
        val candidates = games().mapNotNull { it.root }
        assertEquals(selectFactualResidualRoot(candidates), selectFactualResidualRoot(candidates.reversed()))
        assertFails { selectFactualResidualRoot(emptyList()) }
        assertFails { selectFactualResidualRoot(candidates + candidates.first()) }
        assertFails { candidates.first().copy(candidates = listOf(choices.first())) }
        assertFails { candidates.first().copy(candidates = listOf(choices.first(), choices.first())) }
    }

    @Test fun `readout counts changed groups once and requires matched information complete cost and exposure`() {
        val allocation = allocation()
        val rows = successful(allocation)
        val gate = factualResidualReadoutGate(allocation, rows)
        assertTrue(gate.eligibleForFactualTargets)
        assertEquals(16, gate.changedGroups)
        assertEquals(1.0, gate.selectionCostRatio)
        assertFalse(factualResidualReadoutGate(allocation, rows.map { if (it.arm == FactualResidualArm.RESIDUAL)
            it.copy(allSelectionMillis = 111.0) else it }).eligibleForFactualTargets)
        assertFalse(factualResidualReadoutGate(allocation, rows.map { it.copy(learnedCutoffCalls = 0) }).eligibleForFactualTargets)
        assertFalse(factualResidualReadoutGate(allocation, rows.map { if (it.arm == FactualResidualArm.RESIDUAL)
            it.copy(rootInformationDigest = "other") else it }).eligibleForFactualTargets)
        val unchanged = rows.map { it.copy(chosen = choices.first()) }
        assertEquals(0, factualResidualReadoutGate(allocation, unchanged).changedGroups)
        assertFails { factualResidualReadoutGate(allocation, rows.dropLast(1)) }
    }

    @Test fun `unexecuted and refused coordinates remain distinct and cannot become outcomes`() {
        val allocation = allocation()
        val rows = successful(allocation).mapIndexed { i, row -> if (i < 2) row.copy(
            disposition = if (i == 0) FactualResidualReadoutDisposition.UNEXECUTED else FactualResidualReadoutDisposition.REFUSED,
            searchAttempted = i != 0, allSelectionMillis = if (i == 0) null else 100.0,
            chosen = null, candidateStatistics = emptyList(), searchDiagnostics = null, failure = "synthetic stop") else row }
        val gate = factualResidualReadoutGate(allocation, rows)
        assertEquals(126, gate.completeSearches)
        assertEquals(127, gate.attemptedSearches)
        assertEquals(1, gate.refusedRows)
        assertEquals(1, gate.unexecutedSearches)
        assertNull(gate.selectionCostRatio)
        assertFalse(gate.eligibleForFactualTargets)
        assertFails { rows.first().copy(disposition = FactualResidualReadoutDisposition.SEARCHED) }
        assertFails { FactualResidualCorpusEntry(allocation.games.first(), null,
            FactualIncumbentTrajectoryDisposition.ADMITTED, 322, null) }
    }

    private fun successful(allocation: FactualResidualAllocation): List<FactualResidualSearchRow> =
        allocation.games.filter { it.root != null }.flatMap { game -> (0..1).flatMap { repetition ->
            FactualResidualArm.entries.map { arm -> FactualResidualSearchRow(game.root!!.assignment.rootId,
                game.seedGroupId, game.gameId, game.viewer, game.leg, repetition, arm, repetition.toLong(),
                FactualResidualReadoutDisposition.SEARCHED, true, 0.0, 100.0,
                choices[arm.ordinal], choices.map { SearchCandidateStatistics(it, 28, 0.0, 0.5) },
                InformationSetSearchDiagnostics(56, 8, 1, 1, 1, 0, 0, "synthetic",
                    LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_VISIBLE_V2)),
                "policy", List(8) { 0.125 }, "information", if (arm == FactualResidualArm.RESIDUAL) 1 else 0)
            }
        } }

    private fun allocation(): FactualResidualAllocation {
        val ref = FactualResidualInput("/synthetic", "research-run-v1-sha256:" + "a".repeat(64), "b".repeat(64))
        val policy = SearchTeacherCalibrationPolicy("synthetic", 8, 56, 16, 1.4, true, 1.0)
        val games = games()
        val bindings = factualResidualAllocationBindings(ref.identity, ref, policy, games)
        return FactualResidualAllocation(bindings, ref.identity, ref, policy, games)
    }

    private fun games(): List<FactualResidualGameAllocation> = (0..31).flatMap { group -> (0..1).map { leg ->
        val role = if (group < 16) FactualResidualDataRole.TRAIN else FactualResidualDataRole.SCREEN
        val partition = if (group < 25) RealGamePositionPartition.DEVELOPMENT else RealGamePositionPartition.VALIDATION
        val game = "game-$group-$leg"
        val root = if (role == FactualResidualDataRole.TRAIN) null else FactualResidualRootMetadata(
            RealGamePositionBankAssignment("root-$group-$leg", "source", game, "synthetic", "p$leg", 0, group,
                group(group), partition, RealGamePositionDecisionFamily.PRIORITY, RealGamePositionAssignmentStatus.EXCLUDED), choices)
        FactualResidualGameAllocation("source", game, group(group), partition, role, leg, "p$leg", 322,
            if (role == FactualResidualDataRole.TRAIN) factualResidualFrameIndices(322) else emptyList(), root)
    } }
    private fun group(index: Int) = "group-" + index.toString().padStart(2, '0')
    private val choices = (0..1).map { SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay("synthetic-$it"),
        canonicalPayload = buildJsonObject { put("synthetic", it) }) }
}
