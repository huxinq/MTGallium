package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.PolicyCompressionConfig
import org.mtgallium.agent.searchteacher.SearchReuseConfig
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory

internal data class ArenaPolicySpec(
    val id: String,
    val kind: ArenaPolicyKind,
    val profile: FrozenSearchProfile? = null,
    /**
     * Current production policy composition, when it is intentionally not one of the
     * historical frozen arena profiles.  This keeps evaluation provenance from relabeling it.
     */
    val parameters: SearchTeacherPolicyParameters? = null,
    val beliefMode: BeliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
    val beliefArchitecture: BeliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
    val searchPlanner: SearchPlannerKind = SearchPlannerKind.SHARED_TREE,
    val policyCompression: PolicyCompressionConfig = PolicyCompressionConfig(),
    val searchReuse: SearchReuseConfig = SearchReuseConfig(),
    /**
     * A session-bound evaluator implementation. Its configuration identity, rather than the
     * implementation object, is retained by the arena-owned behavior specification.
     */
    val informationEvaluator: ConfiguredInformationStateEvaluator? = null,
    /** Experimental continuation policies; null retains the production composition. */
    val rootRolloutPolicy: OpponentPolicy? = null,
    val opponentRolloutPolicy: OpponentPolicy? = null,
    val directRootSelectionPolicy: org.mtgallium.agent.searchteacher.DirectRootSelectionPolicy? = null,
) {
    init {
        require(directRootSelectionPolicy == null ||
            (kind == ArenaPolicyKind.SEARCH && searchPlanner == SearchPlannerKind.SHARED_TREE))
        require(id.isNotBlank())
        require((kind == ArenaPolicyKind.SEARCH) == (profile != null || parameters != null))
        require(profile == null || parameters == null)
        require(informationEvaluator == null ||
            (kind == ArenaPolicyKind.SEARCH && searchPlanner == SearchPlannerKind.SHARED_TREE)
        ) { "An information-state evaluator is valid only for a shared-tree Search policy" }
        require((rootRolloutPolicy == null && opponentRolloutPolicy == null) ||
            (kind == ArenaPolicyKind.SEARCH && searchPlanner == SearchPlannerKind.SHARED_TREE &&
                (parameters?.leaf ?: profile?.leaf)?.stateSource == LeafStateSource.BOUNDED_ROLLOUT)
        ) { "Custom rollout policies require bounded-rollout shared-tree search" }
    }

    fun effectiveRootRolloutPolicy(): OpponentPolicy =
        rootRolloutPolicy ?: SearchTeacherSearchFactory.rootRolloutPolicy()

    fun effectiveOpponentRolloutPolicy(): OpponentPolicy =
        opponentRolloutPolicy ?: SearchTeacherSearchFactory.opponentRolloutPolicy()

    fun effectiveParameters(arenaBaseSeed: Long): SearchTeacherPolicyParameters =
        (parameters ?: requireNotNull(profile).policyParameters(
            baseSeed = arenaBaseSeed,
            beliefMode = beliefMode,
            beliefArchitecture = beliefArchitecture,
            policyCompression = policyCompression,
            searchReuse = searchReuse,
        )).also { effective ->
            informationEvaluator?.let { evaluator ->
                require(effective.leaf.stateSource == LeafStateSource.CURRENT_INFORMATION_STATE ||
                    (effective.leaf.stateSource == LeafStateSource.BOUNDED_ROLLOUT &&
                        effective.leaf.evaluator in setOf(
                            LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                            LeafEvaluator.MTGALLIUM_TACTICAL_V3,
                        ))) {
                    "Arena evaluator overrides require a current-information leaf or a supported bounded rollout"
                }
                require(evaluator.id == effective.leaf.evaluator.evaluatorId) {
                    "Arena evaluator ${evaluator.id} does not match configured ${effective.leaf.evaluator.evaluatorId} leaf"
                }
            }
        }
}

@Serializable
internal data class TournamentPolicyDescription(
    val id: String,
    val kind: ArenaPolicyKind,
    val leaf: LeafEvaluationConfig? = null,
    val particles: Int? = null,
    val simulations: Int? = null,
    val maxPolicyDecisions: Int? = null,
    val explorationConstant: Double? = null,
    val actionSpaceProfile: SearchActionSpaceProfile? = null,
    val beliefMode: BeliefMode? = null,
    val beliefArchitecture: BeliefArchitecture? = null,
    val searchPlanner: SearchPlannerKind? = null,
    val opponentPolicyId: String? = null,
    val policyCompression: PolicyCompressionConfig? = null,
    val searchReuse: SearchReuseConfig? = null,
)

internal fun describeTournamentPolicy(policy: ArenaPolicySpec): TournamentPolicyDescription =
    TournamentPolicyDescription(
        id = policy.id,
        kind = policy.kind,
        leaf = policy.parameters?.leaf ?: policy.profile?.leaf,
        particles = policy.parameters?.particles ?: policy.profile?.particles,
        simulations = policy.parameters?.simulations ?: policy.profile?.simulations,
        maxPolicyDecisions = policy.parameters?.maxPolicyDecisions ?: policy.profile?.maxPolicyDecisions,
        explorationConstant = policy.parameters?.explorationConstant ?: policy.profile?.explorationConstant,
        actionSpaceProfile = policy.parameters?.actionSpaceProfile ?: policy.profile?.actionSpaceProfile,
        beliefMode = policy.beliefMode.takeIf { policy.kind == ArenaPolicyKind.SEARCH },
        beliefArchitecture = policy.beliefArchitecture.takeIf { policy.kind == ArenaPolicyKind.SEARCH },
        searchPlanner = policy.searchPlanner.takeIf { policy.kind == ArenaPolicyKind.SEARCH },
        opponentPolicyId = "mono-red-mixture-70-10-10-10-v2".takeIf {
            policy.kind == ArenaPolicyKind.SEARCH
        },
        policyCompression = policy.policyCompression.takeIf { policy.kind == ArenaPolicyKind.SEARCH },
        searchReuse = policy.searchReuse.takeIf { policy.kind == ArenaPolicyKind.SEARCH },
    )
