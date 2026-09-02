package org.mtgallium.agent.searchteacher

import org.mtgallium.agent.infoset.argentum.ARGENTUM_HEURISTIC_CHOICE_TAG_V1
import org.mtgallium.agent.infoset.core.MixtureOpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicyBehaviorSpecification
import org.mtgallium.agent.infoset.core.OpponentPolicyComponentSpecification
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionDiagnostic
import org.mtgallium.agent.infoset.core.OpponentPolicyMixtureEntry
import org.mtgallium.agent.infoset.core.OpponentPolicyReplacementDiagnostic
import org.mtgallium.agent.infoset.core.OpponentPolicyReplacementEvidenceDisposition
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.ProbabilityDistribution
import org.mtgallium.agent.infoset.core.ProbabilityMass
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticActionIntent
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticActionTargetRelation
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy

const val ARGENTUM_HEURISTIC_ANNOTATION_UNAVAILABLE_TRIGGER_V1: String =
    "required-heuristic-annotation-cardinality-not-one"

/** Safe approximation of Argentum's proactive heuristic using only projected choice metadata. */
class SemanticHeuristicOpponentPolicy(
    override val id: String = "semantic-argentum-heuristic-v2",
) : OpponentPolicy {
    override val distributionIsSeedInvariant: Boolean = true
    override val behaviorSpecification: OpponentPolicyBehaviorSpecification =
        OpponentPolicyBehaviorSpecification(
            implementationId = "semantic-typed-action-intent-score-table-v2",
            declaredId = id,
            distributionIsSeedInvariant = distributionIsSeedInvariant,
            parameters = mapOf(
                "actionIntentSchema" to SemanticActionIntent.SCHEMA_V1.toString(),
                "scoreTable" to "land7-cast6-activate5-attack4-block4-keep3-pass1-other2",
            ),
        )

    override fun distribution(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> = softScores(candidates) { choice ->
        when (choice.actionIntent.kind) {
            SemanticActionIntentKind.PLAY_LAND -> 7.0
            SemanticActionIntentKind.CAST_SPELL -> 6.0
            SemanticActionIntentKind.ACTIVATE_ABILITY -> 5.0
            SemanticActionIntentKind.DECLARE_ATTACKERS,
            SemanticActionIntentKind.DECLINE_ATTACK -> 4.0
            SemanticActionIntentKind.DECLARE_BLOCKERS,
            SemanticActionIntentKind.DECLINE_BLOCK -> 4.0
            SemanticActionIntentKind.KEEP_HAND -> 3.0
            SemanticActionIntentKind.PASS_PRIORITY -> 1.0
            else -> 2.0
        }
    }
}

/** Consumes the trusted adapter's information-safe determinized heuristic annotation. */
class DeterminizedArgentumHeuristicOpponentPolicy(
    override val id: String = "determinized-argentum-heuristic-v2",
    private val fallback: OpponentPolicy = SemanticHeuristicOpponentPolicy(),
    private val replacementEvidenceDisposition: OpponentPolicyReplacementEvidenceDisposition =
        OpponentPolicyReplacementEvidenceDisposition.INVALIDATES_EVIDENCE,
) : OpponentPolicy {
    override val distributionIsSeedInvariant: Boolean = fallback.distributionIsSeedInvariant
    override val behaviorSpecification: OpponentPolicyBehaviorSpecification
        get() = OpponentPolicyBehaviorSpecification(
            implementationId = "determinized-argentum-annotation-with-declared-replacement-v2",
            declaredId = id,
            distributionIsSeedInvariant = distributionIsSeedInvariant,
            parameters = mapOf(
                "requiredPolicyTag" to ARGENTUM_HEURISTIC_CHOICE_TAG_V1,
                "requiredAnnotationCardinality" to "exactly-one",
                "replacementTrigger" to ARGENTUM_HEURISTIC_ANNOTATION_UNAVAILABLE_TRIGGER_V1,
                "replacementEvidenceDisposition" to replacementEvidenceDisposition.name,
            ),
            components = listOf(
                OpponentPolicyComponentSpecification(1.0, fallback.behaviorSpecification)
            ),
        )

    override fun usedFallback(candidates: List<SemanticChoice>): Boolean =
        candidates.count { ARGENTUM_HEURISTIC_CHOICE_TAG_V1 in it.display.policyTags } != 1

    override fun distribution(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> {
        val selected = candidates.singleOrNull {
            ARGENTUM_HEURISTIC_CHOICE_TAG_V1 in it.display.policyTags
        } ?: return fallback.distribution(opponentInformation, candidates, policySeed)
        return ProbabilityDistribution.normalized(candidates.map { candidate ->
            ProbabilityMass(candidate, if (candidate.signature == selected.signature) 1.0 else 0.0)
        })
    }

    override fun decisionDiagnostic(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        chosen: SemanticChoice,
        policySeed: Long,
        attributionSeed: Long,
    ): OpponentPolicyDecisionDiagnostic {
        if (!usedFallback(candidates)) {
            return OpponentPolicyDecisionDiagnostic(
                declaredPolicyId = id,
                selectedComponentId = id,
            )
        }
        val replacement = fallback.decisionDiagnostic(
            opponentInformation,
            candidates,
            chosen,
            policySeed,
            attributionSeed,
        )
        return OpponentPolicyDecisionDiagnostic(
            declaredPolicyId = id,
            selectedComponentId = id,
            effectivePolicyId = replacement.effectivePolicyId,
            replacement = OpponentPolicyReplacementDiagnostic(
                triggerId = ARGENTUM_HEURISTIC_ANNOTATION_UNAVAILABLE_TRIGGER_V1,
                replacementPolicyId = fallback.id,
                evidenceDisposition = replacementEvidenceDisposition,
            ),
        )
    }
}

class FaceBurnOpponentPolicy : OpponentPolicy {
    override val id: String = "face-burn-v2"
    override val distributionIsSeedInvariant: Boolean = true
    private val burnNames = setOf("Shock", "Burst Lightning", "Lightning Strike", "Sear", "Broadside Barrage")
    override val behaviorSpecification: OpponentPolicyBehaviorSpecification =
        OpponentPolicyBehaviorSpecification(
            implementationId = "face-burn-typed-action-intent-score-table-v2",
            declaredId = id,
            distributionIsSeedInvariant = distributionIsSeedInvariant,
            parameters = mapOf(
                "burnNames" to burnNames.sorted().joinToString("\u001f"),
                "actionIntentSchema" to SemanticActionIntent.SCHEMA_V1.toString(),
                "scoreTable" to "burn-face12-burn5-attack6-pass1-other3",
            ),
        )

    override fun distribution(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> {
        return softScores(candidates) { choice ->
            when {
                choice.actionIntent.sourceCardName in burnNames &&
                    SemanticActionTargetRelation.OPPONENT_PLAYER in choice.actionIntent.targetRelations -> 12.0
                choice.actionIntent.sourceCardName in burnNames -> 5.0
                choice.actionIntent.kind.isAttackDeclaration() -> 6.0
                choice.actionIntent.kind == SemanticActionIntentKind.PASS_PRIORITY -> 1.0
                else -> 3.0
            }
        }
    }
}

class HoldBurnOpponentPolicy : OpponentPolicy {
    override val id: String = "hold-burn-v2"
    override val distributionIsSeedInvariant: Boolean = true
    private val burnNames = setOf("Shock", "Burst Lightning", "Lightning Strike", "Sear", "Broadside Barrage")
    override val behaviorSpecification: OpponentPolicyBehaviorSpecification =
        OpponentPolicyBehaviorSpecification(
            implementationId = "hold-burn-typed-action-intent-score-table-v2",
            declaredId = id,
            distributionIsSeedInvariant = distributionIsSeedInvariant,
            parameters = mapOf(
                "burnNames" to burnNames.sorted().joinToString("\u001f"),
                "actionIntentSchema" to SemanticActionIntent.SCHEMA_V1.toString(),
                "scoreTable" to "burn0.5-pass5-attack4-other3",
            ),
        )

    override fun distribution(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> = softScores(candidates) { choice ->
        when {
            choice.actionIntent.sourceCardName in burnNames -> 0.5
            choice.actionIntent.kind == SemanticActionIntentKind.PASS_PRIORITY -> 5.0
            choice.actionIntent.kind.isAttackDeclaration() -> 4.0
            else -> 3.0
        }
    }
}

fun defaultMonoRedOpponentPolicy(
    replacementEvidenceDisposition: OpponentPolicyReplacementEvidenceDisposition =
        OpponentPolicyReplacementEvidenceDisposition.INVALIDATES_EVIDENCE,
): OpponentPolicy = MixtureOpponentPolicy(
    id = "mono-red-mixture-70-10-10-10-v2",
    components = listOf(
        OpponentPolicyMixtureEntry(
            DeterminizedArgentumHeuristicOpponentPolicy(
                replacementEvidenceDisposition = replacementEvidenceDisposition,
            ),
            0.70,
        ),
        OpponentPolicyMixtureEntry(UniformOpponentPolicy, 0.10),
        OpponentPolicyMixtureEntry(FaceBurnOpponentPolicy(), 0.10),
        OpponentPolicyMixtureEntry(HoldBurnOpponentPolicy(), 0.10),
    ),
)

private fun SemanticActionIntentKind.isAttackDeclaration(): Boolean =
    this == SemanticActionIntentKind.DECLARE_ATTACKERS || this == SemanticActionIntentKind.DECLINE_ATTACK

private fun softScores(
    candidates: List<SemanticChoice>,
    score: (SemanticChoice) -> Double,
): ProbabilityDistribution<SemanticChoice> = ProbabilityDistribution.normalized(
    candidates.map { choice -> ProbabilityMass(choice, score(choice).coerceAtLeast(0.001)) },
)
