package org.mtgallium.agent.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionCounter
import org.mtgallium.agent.infoset.core.OpponentPolicyReplacementEvidenceDisposition
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.SemanticActionIntent
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticActionTargetRelation
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

class SearchTeacherOpponentPoliciesTest {
    @Test
    fun `renaming every display label leaves every scripted distribution and component attribution unchanged`() {
        val candidates = candidates()
        val renamed = candidates.mapIndexed { index, choice ->
            choice.copy(
                display = choice.display.copy(
                    label = "localized-presentation-$index",
                    sourceName = "localized-source-$index",
                    targetNames = listOf("localized-target-$index"),
                )
            )
        }
        val policies = listOf(
            SemanticHeuristicOpponentPolicy(),
            DeterminizedArgentumHeuristicOpponentPolicy(),
            FaceBurnOpponentPolicy(),
            HoldBurnOpponentPolicy(),
            defaultMonoRedOpponentPolicy(),
        )

        policies.forEach { policy ->
            assertEquals(
                probabilities(policy, candidates),
                probabilities(policy, renamed),
                policy.id,
            )
            repeat(128) { index ->
                val original = policy.select(information(candidates), candidates, index.toLong(), index * 17L)
                val relabeled = policy.select(information(renamed), renamed, index.toLong(), index * 17L)
                assertEquals(original.choice.signature, relabeled.choice.signature, policy.id)
                assertEquals(original.diagnostic, relabeled.diagnostic, policy.id)
            }
        }
    }

    @Test
    fun `replacement accounting has one component per decision and invalidates unless predeclared`() {
        val candidates = candidates()
        val invalidating = defaultMonoRedOpponentPolicy(
            OpponentPolicyReplacementEvidenceDisposition.INVALIDATES_EVIDENCE
        )
        val predeclared = defaultMonoRedOpponentPolicy(
            OpponentPolicyReplacementEvidenceDisposition.PREDECLARED_EVIDENCE_ELIGIBLE
        )

        val invalidatingSummary = exercise(invalidating, candidates, decisions = 1_024)
        val predeclaredSummary = exercise(predeclared, candidates, decisions = 1_024)
        val engineComponent = "determinized-argentum-heuristic-v2"

        assertEquals(1_024, invalidatingSummary.decisions)
        assertEquals(1_024, invalidatingSummary.selectedComponents.values.sum())
        assertEquals(1_024, invalidatingSummary.effectivePolicies.values.sum())
        assertEquals(
            invalidatingSummary.selectedComponents.getValue(engineComponent),
            invalidatingSummary.replacementDecisions,
        )
        assertEquals(
            invalidatingSummary.replacementDecisions,
            invalidatingSummary.evidenceInvalidatingReplacements,
        )
        assertEquals(invalidatingSummary.selectedComponents, predeclaredSummary.selectedComponents)
        assertEquals(invalidatingSummary.replacementDecisions, predeclaredSummary.replacementDecisions)
        assertEquals(0, predeclaredSummary.evidenceInvalidatingReplacements)
        assertTrue(invalidatingSummary.selectedComponents.keys.containsAll(
            setOf(
                engineComponent,
                "uniform-v1",
                "face-burn-v2",
                "hold-burn-v2",
            )
        ))
        assertNotEquals(invalidating.behaviorSpecification, predeclared.behaviorSpecification)
    }

    private fun exercise(
        policy: OpponentPolicy,
        candidates: List<SemanticChoice>,
        decisions: Int,
    ) = OpponentPolicyDecisionCounter().also { counter ->
        repeat(decisions) { index ->
            counter.record(
                policy.select(
                    opponentInformation = information(candidates),
                    candidates = candidates,
                    policySeed = index.toLong(),
                    sampleSeed = index * 31L + 7L,
                ).diagnostic
            )
        }
    }.summary()

    private fun probabilities(
        policy: OpponentPolicy,
        candidates: List<SemanticChoice>,
    ): Map<String, Double> = policy.distribution(
        information(candidates),
        candidates,
        91L,
    ).entries.associate { it.value.signature to it.probability }

    private fun candidates(): List<SemanticChoice> = listOf(
        choice("pass", SemanticActionIntentKind.PASS_PRIORITY),
        choice("land", SemanticActionIntentKind.PLAY_LAND, source = "Mountain"),
        choice(
            "shock-face",
            SemanticActionIntentKind.CAST_SPELL,
            source = "Shock",
            targetRelations = setOf(SemanticActionTargetRelation.OPPONENT_PLAYER),
        ),
        choice("attack", SemanticActionIntentKind.DECLARE_ATTACKERS),
        choice("decline", SemanticActionIntentKind.DECLINE_ATTACK),
    )

    private fun choice(
        id: String,
        kind: SemanticActionIntentKind,
        source: String? = null,
        targetRelations: Set<SemanticActionTargetRelation> = emptySet(),
    ): SemanticChoice {
        val family = when (kind) {
            SemanticActionIntentKind.PASS_PRIORITY -> SemanticOperationFamily.PASS_PRIORITY
            SemanticActionIntentKind.PLAY_LAND -> SemanticOperationFamily.PLAY_LAND
            SemanticActionIntentKind.CAST_SPELL -> SemanticOperationFamily.CAST_SPELL
            SemanticActionIntentKind.DECLARE_ATTACKERS,
            SemanticActionIntentKind.DECLINE_ATTACK -> SemanticOperationFamily.DECLARE_ATTACKERS
            else -> error("Unsupported fixture intent $kind")
        }
        return SemanticChoice.create(
            kind = SemanticChoiceKind.ACTION,
            operationFamily = family,
            actionIntent = SemanticActionIntent(
                kind = kind,
                sourceCardName = source,
                targetRelations = targetRelations,
            ),
            display = SemanticChoiceDisplay("presentation-$id", sourceName = source),
            canonicalPayload = buildJsonObject { put("fixture", JsonPrimitive(id)) },
        )
    }

    private fun information(candidates: List<SemanticChoice>): PolicyInformationState {
        val observation = PolicyObservation(
            perspectivePlayerId = "p0",
            turnNumber = 1,
            phase = "PRECOMBAT_MAIN",
            step = "PRECOMBAT_MAIN",
            activePlayerId = "p0",
            priorityPlayerId = "p0",
            players = listOf(
                PolicyPlayerView(
                    "p0", "Actor", 20, 5, 40, 0, 0,
                    PolicyManaPool(), active = true, priority = true, lost = false,
                ),
                PolicyPlayerView(
                    "p1", "Opponent", 20, 5, 40, 0, 0,
                    PolicyManaPool(), active = false, priority = false, lost = false,
                ),
            ),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            observationDigest = "opponent-policy-fixture",
        )
        return PolicyInformationState(
            actingPlayerId = "p0",
            observation = observation,
            informationStateDigest = "opponent-policy-fixture-info",
            historyCommitment = PolicyHistoryCommitment.empty(),
            history = emptyList(),
            knowledge = PolicyKnowledgeState.empty("p0"),
            candidates = candidates,
            terminated = false,
        )
    }
}
