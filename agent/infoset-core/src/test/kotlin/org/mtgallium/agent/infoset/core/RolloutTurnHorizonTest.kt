package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class RolloutTurnHorizonTest {
    @Test
    fun `one through five turn endpoints stay anchored to the root across unequal branches`() {
        for (turns in 1..5) {
            val probe = TurnProbe()
            val result = search(probe, turns).search("p0", belief(TurnWorld(probe)), 617L)
            assertEquals(setOf(7 + turns), probe.evaluatedTurns.toSet())
            assertEquals(setOf("finish", "wait"), probe.evaluatedBranches.toSet())
            assertEquals(8, result.candidateSettlementCounts.values.sumOf { it.heuristicSettlementBackups })
            assertTrue(probe.actedTurns.all { it < 7 + turns })
        }
    }

    @Test
    fun `first-edge diagnostics require the original root even when that edge crosses a turn`() {
        val probe = TurnProbe()
        val child = TurnWorld(probe)
        assertTrue(child.step(child.expandChoices().candidates.first()).accepted)
        assertEquals(8, child.informationState("p0").observation.turnNumber)
        val search = search(probe, 1)
        assertFailsWith<IllegalArgumentException> { search.settleFirstUnvisitedEdge(child, "p0", 618L, 0) }
        val before = probe.actedTurns.size
        val value = search.settleFirstUnvisitedEdge(child, "p0", 618L, 0, rootTurnNumber = 7)
        assertEquals(SearchSettlementOrigin.HEURISTIC_SETTLEMENT, value.origin)
        assertEquals(listOf(8), probe.evaluatedTurns)
        assertEquals(before, probe.actedTurns.size)
    }

    @Test
    fun `decision safety limit is a typed failure rather than an earlier heuristic leaf`() {
        val probe = TurnProbe()
        val failure = assertFailsWith<RolloutTurnHorizonException> {
            search(probe, 5, cap = 1).search("p0", belief(TurnWorld(probe)), 619L)
        }
        assertEquals(RolloutTurnHorizonFailure.DECISION_LIMIT, failure.failure)
        assertEquals(12, failure.targetTurnNumber)
        assertEquals(1, failure.policyDecisions)
        assertTrue(probe.evaluatedTurns.isEmpty())
    }

    @Test
    fun `terminal bypass and a missing decision retain different meanings`() {
        val probe = TurnProbe()
        val result = search(probe, 5).search("p0", belief(TurnWorld(probe, terminalAtTick = 6)), 620L)
        assertEquals(1.0, result.rootValue)
        assertEquals(8, result.candidateSettlementCounts.values.sumOf { it.terminalPayoffBackups })
        assertTrue(probe.evaluatedTurns.isEmpty())
        val failure = assertFailsWith<RolloutTurnHorizonException> {
            search(probe, 5).search("p0", belief(TurnWorld(probe, missingAtTick = 6)), 621L)
        }
        assertEquals(RolloutTurnHorizonFailure.MISSING_DECISION, failure.failure)
        assertTrue(probe.evaluatedTurns.isEmpty())
    }

    private fun search(probe: TurnProbe, turns: Int, cap: Int = 64): InformationSetSearch {
        val evaluator = object : InformationStateEvaluator {
            override val id = LeafEvaluator.MTGALLIUM_VISIBLE_V2.evaluatorId
            override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double {
                probe.evaluatedTurns += information.observation.turnNumber
                probe.evaluatedBranches += information.observation.observationDigest.substringBefore(':')
                return 0.25
            }
        }
        return InformationSetSearch(
            config = InformationSetSearchConfig(
                simulations = 8, maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
                rolloutTurnHorizon = RolloutTurnHorizon(turns, cap),
            ),
            opponentPolicy = UniformOpponentPolicy,
            rolloutPolicy = UniformOpponentPolicy,
            rolloutOpponentPolicy = UniformOpponentPolicy,
            leafEvaluationStrategy = LeafEvaluationStrategy(evaluator.id, LeafValueSource.Information(evaluator)),
        )
    }

    private fun belief(world: SearchWorld) = BeliefBatch(
        listOf(Weighted(world, 1.0)),
        BeliefDiagnostics(
            mode = BeliefMode.CONSISTENCY_ONLY_V1, requestedParticles = 1, acceptedParticles = 1,
            rejectedParticles = 0, effectiveSampleSizeBefore = 1.0, effectiveSampleSizeAfter = 1.0,
            entropy = 0.0, resamplingCount = 0,
        ),
    )
}

private class TurnProbe {
    val evaluatedTurns = mutableListOf<Int>()
    val evaluatedBranches = mutableListOf<String>()
    val actedTurns = mutableListOf<Int>()
}

/** Root is just before turn 7 ends; one root choice crosses it, the other spends an extra decision. */
private class TurnWorld(
    private val probe: TurnProbe,
    private var tick: Int = 4,
    private var branch: String? = null,
    private val terminalAtTick: Int? = null,
    private val missingAtTick: Int? = null,
) : SearchWorld {
    private val turn: Int get() = 7 + tick / 5
    override fun actorToAct(): String = if (turn % 2 == 1) "p0" else "p1"
    override fun informationState(viewer: String): PolicyInformationState = PolicyInformationState(
        actingPlayerId = actorToAct(),
        observation = PolicyObservation(
            perspectivePlayerId = viewer, turnNumber = turn, phase = "BEGINNING", step = "UPKEEP",
            activePlayerId = actorToAct(), priorityPlayerId = actorToAct(), players = emptyList(),
            zones = emptyList(), stack = emptyList(), pendingDecision = null,
            observationDigest = "$branch:$tick",
        ),
        informationStateDigest = PolicyJson.sha256("$viewer:$branch:$tick"),
        historyCommitment = PolicyHistoryCommitment.empty(), history = emptyList(),
        candidates = if (viewer == actorToAct()) expandChoices().candidates else emptyList(), terminated = false,
    )
    override fun expandChoices(): PolicyExpansion {
        val choices = if (missingAtTick != null && tick >= missingAtTick) emptyList() else {
            (if (branch == null) listOf("finish", "wait") else listOf("advance", "advance-alternative")).map { label ->
                SemanticChoice.create(
                    kind = SemanticChoiceKind.ACTION, operationFamily = SemanticOperationFamily.OTHER,
                    display = SemanticChoiceDisplay(label), canonicalPayload = buildJsonObject { put("choice", JsonPrimitive(label)) },
                )
            }
        }
        return PolicyExpansion(choices, true, choices.size.toLong(), "turn-test-v1", 1L)
    }
    override fun step(choice: SemanticChoice): SearchStepResult {
        if (choice !in expandChoices().candidates) return SearchStepResult(false)
        probe.actedTurns += turn
        if (branch == null) {
            branch = choice.display.label
            if (branch == "finish") tick++
        } else tick++
        return SearchStepResult(true)
    }
    override fun fork(): SearchWorld = TurnWorld(probe, tick, branch, terminalAtTick, missingAtTick)
    override fun terminalPayoff(rootPlayer: String): Double? = if (terminalAtTick != null && tick >= terminalAtTick) 1.0 else null
    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double = error("information evaluator only")
}
