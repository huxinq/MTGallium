package org.mtgallium.agent.argentum.policy

import kotlinx.serialization.SerialName
import org.mtgallium.agent.infoset.core.SingletonSelectionConfig
import org.mtgallium.agent.infoset.core.ActionSelector
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToJsonElement
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.argentum.ArgentumHeuristicProfile
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpansionSpecification
import org.mtgallium.agent.infoset.core.BOUNDED_POLICY_INPUT_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.KNOWLEDGE_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.RolloutCutoff
import org.mtgallium.agent.infoset.core.PolicyComponent
import org.mtgallium.agent.infoset.core.OpponentPolicyBehaviorSpecification
import org.mtgallium.agent.infoset.core.POLICY_HISTORY_COMMITMENT_ALGORITHM
import org.mtgallium.agent.infoset.core.POLICY_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.monored.MonoRedInformationEvaluator

const val SEARCH_POLICY_BEHAVIOR_SCHEMA_V1: Int = 1
const val SEARCH_POLICY_BEHAVIOR_IDENTITY_PREFIX: String =
    "search-teacher-behavior-v1-sha256"

@Serializable
@SerialName("org.mtgallium.agent.searchteacher.KnownDeckCardSpecification")
data class KnownDeckCardSpecification(
    val cardName: String,
    val count: Int,
) {
    init {
        require(cardName.isNotBlank())
        require(count > 0)
    }
}

@Serializable
@SerialName("org.mtgallium.agent.searchteacher.KnownDeckSpecification")
data class KnownDeckSpecification(
    val playerId: String,
    val cards: List<KnownDeckCardSpecification>,
) {
    init {
        require(playerId.isNotBlank())
        require(cards.map(KnownDeckCardSpecification::cardName).distinct().size == cards.size)
        require(cards == cards.sortedBy(KnownDeckCardSpecification::cardName))
    }
}

@Serializable
@SerialName("org.mtgallium.agent.searchteacher.SearchTeacherInputSchemaSpecification")
data class InputSchemaSpecification(
    val playerInformationSchema: Int = POLICY_SCHEMA_CURRENT,
    val candidateSchema: Int = CANDIDATE_SCHEMA_CURRENT,
    val boundedPolicyInputSchema: Int = BOUNDED_POLICY_INPUT_SCHEMA_CURRENT,
    val knowledgeSchema: Int = KNOWLEDGE_SCHEMA_CURRENT,
    val historyCommitmentAlgorithm: String = POLICY_HISTORY_COMMITMENT_ALGORITHM,
)

@Serializable
@SerialName("org.mtgallium.agent.searchteacher.SearchTeacherActionSpaceSpecification")
data class ActionSpaceSpecification(
    val profileId: String,
    val rulesEquivalent: Boolean,
    val suppressesStandaloneManaAbilities: Boolean,
    val expansion: UnifiedSemanticExpansionSpecification,
) {
    init {
        require(profileId == expansion.actionSpaceProfile.profileId)
        require(rulesEquivalent == expansion.actionSpaceProfile.rulesEquivalent)
        require(
            suppressesStandaloneManaAbilities ==
                expansion.actionSpaceProfile.suppressesStandaloneManaAbilities
        )
    }
}

@Serializable
@SerialName("org.mtgallium.agent.searchteacher.SearchTeacherEvaluatorSpecification")
data class EvaluatorSpecification(
    val evaluatorId: String,
    val evaluatorConfigurationId: String,
    val valueSource: String,
    val settlesAtRolloutHorizon: Boolean,
    val unresolvedLeafHandling: String,
)

/**
 * Host choices that can change which algorithm runs or when a stored game stops. Source-tree and
 * source-tree changes are deliberately recorded by the artifact layer, not inferred here.
 */
@Serializable
@SerialName("org.mtgallium.agent.searchteacher.SearchTeacherIntegrationSpecification")
data class IntegrationSpecification(
    val schemaVersion: Int = 1,
    val hostMode: String = "live-single-engine-choice-v1",
    val searchPlanner: String = "shared-information-set-tree-v1",
    val maximumGameDecisions: Int? = null,
    val maximumSearchDecisions: Int? = null,
    val additionalBindings: Map<String, String> = emptyMap(),
) {
    init {
        require(schemaVersion == 1)
        require(hostMode.isNotBlank())
        require(searchPlanner.isNotBlank())
        require(maximumGameDecisions == null || maximumGameDecisions > 0)
        require(maximumSearchDecisions == null || maximumSearchDecisions > 0)
        require(additionalBindings.keys.all(String::isNotBlank))
    }
}

/** Every session-bound input that this policy layer can observe and that may change behavior. */
@Serializable
@SerialName("org.mtgallium.agent.searchteacher.SearchTeacherBehaviorSpecification")
data class PolicyBehaviorSpecification(
    val schemaVersion: Int = SEARCH_POLICY_BEHAVIOR_SCHEMA_V1,
    val declaredProfileId: String,
    val particles: Int,
    val search: InformationSetSearchConfig,
    val beliefMode: String,
    val beliefArchitecture: String,
    val baseSeed: Long,
    val actionSpace: ActionSpaceSpecification,
    val evaluator: EvaluatorSpecification,
    val opponentPolicy: OpponentPolicyBehaviorSpecification,
    val rootRolloutPolicy: OpponentPolicyBehaviorSpecification,
    val opponentRolloutPolicy: OpponentPolicyBehaviorSpecification,
    val knownDecks: List<KnownDeckSpecification>,
    val inputSchemas: InputSchemaSpecification,
    val integration: IntegrationSpecification,
    // An absent field retains the previous behavior and its canonical identity on re-encoding.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val singletonSelection: SingletonSelectionConfig = SingletonSelectionConfig(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rootSelectionGuidanceId: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val directRootSelectionId: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val searchPriorId: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val searchHeuristicProfile: ArgentumHeuristicProfile = ArgentumHeuristicProfile.PRODUCTION,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val conditionedBeliefMaintenance: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val historyEventOrder: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val historyObjectReference: String? = null,
) {
    init {
        require(schemaVersion == SEARCH_POLICY_BEHAVIOR_SCHEMA_V1)
        require(declaredProfileId.isNotBlank())
        require(particles > 0)
        require(knownDecks.map(KnownDeckSpecification::playerId).distinct().size == knownDecks.size)
        require(knownDecks == knownDecks.sortedBy(KnownDeckSpecification::playerId))
    }
}

object PolicyIdentity {
    fun specification(
        parameters: SearchPolicyConfig,
        knownDecks: Map<String, Map<String, Int>>,
        opponentPolicy: PolicyComponent,
        rootRolloutPolicy: ActionSelector = PolicyDefaults.rootRolloutPolicy(),
        opponentRolloutPolicy: ActionSelector = PolicyDefaults.opponentRolloutPolicy(),
        valueSource: LeafValueSource = LeafValueSource.Information(MonoRedInformationEvaluator),
        actionExpansion: UnifiedSemanticExpansionSpecification =
            UnifiedSemanticExpander.defaultBehaviorSpecification(parameters.actionSpaceProfile),
        integration: IntegrationSpecification = IntegrationSpecification(),
    ): PolicyBehaviorSpecification {
        require(actionExpansion.actionSpaceProfile == parameters.actionSpaceProfile) {
            "Policy action-space profile does not match the world's candidate generator"
        }
        return PolicyBehaviorSpecification(
            declaredProfileId = parameters.profileId,
            particles = parameters.particles,
            search = parameters.searchConfig(),
            beliefMode = parameters.beliefMode.name,
            beliefArchitecture = parameters.beliefArchitecture.name,
            baseSeed = parameters.baseSeed,
            actionSpace = ActionSpaceSpecification(
                profileId = parameters.actionSpaceProfile.profileId,
                rulesEquivalent = parameters.actionSpaceProfile.rulesEquivalent,
                suppressesStandaloneManaAbilities =
                    parameters.actionSpaceProfile.suppressesStandaloneManaAbilities,
                expansion = actionExpansion,
            ),
            evaluator = valueSource.behaviorSpecification(parameters.leaf),
            opponentPolicy = opponentPolicy.behaviorSpecification,
            rootRolloutPolicy = rootRolloutPolicy.behaviorSpecification,
            opponentRolloutPolicy = opponentRolloutPolicy.behaviorSpecification,
            knownDecks = normalizeKnownDecks(knownDecks),
            inputSchemas = InputSchemaSpecification(),
            integration = integration,
            singletonSelection = parameters.singletonSelection,
            searchHeuristicProfile = parameters.searchHeuristicProfile,
            conditionedBeliefMaintenance = conditionedBeliefMaintenanceIdentity(parameters.beliefMode),
        )
    }

    fun identity(specification: PolicyBehaviorSpecification): String {
        val element = PolicyJson.format.encodeToJsonElement(
            PolicyBehaviorSpecification.serializer(),
            specification,
        )
        return "$SEARCH_POLICY_BEHAVIOR_IDENTITY_PREFIX:${PolicyJson.digest(element)}"
    }

    fun identity(
        parameters: SearchPolicyConfig,
        knownDecks: Map<String, Map<String, Int>>,
        opponentPolicy: PolicyComponent,
        rootRolloutPolicy: ActionSelector = PolicyDefaults.rootRolloutPolicy(),
        opponentRolloutPolicy: ActionSelector = PolicyDefaults.opponentRolloutPolicy(),
        valueSource: LeafValueSource = LeafValueSource.Information(MonoRedInformationEvaluator),
        actionExpansion: UnifiedSemanticExpansionSpecification =
            UnifiedSemanticExpander.defaultBehaviorSpecification(parameters.actionSpaceProfile),
        integration: IntegrationSpecification = IntegrationSpecification(),
    ): String = identity(
        specification(
            parameters = parameters,
            knownDecks = knownDecks,
            opponentPolicy = opponentPolicy,
            rootRolloutPolicy = rootRolloutPolicy,
            opponentRolloutPolicy = opponentRolloutPolicy,
            valueSource = valueSource,
            actionExpansion = actionExpansion,
            integration = integration,
        )
    )

    private fun normalizeKnownDecks(
        knownDecks: Map<String, Map<String, Int>>,
    ): List<KnownDeckSpecification> = knownDecks.entries.sortedBy { it.key }.map {
        (playerId, deck) ->
        KnownDeckSpecification(
            playerId = playerId,
            cards = deck.entries.asSequence()
                .filter { it.value != 0 }
                .sortedBy { it.key }
                .map { (cardName, count) -> KnownDeckCardSpecification(cardName, count) }
                .toList(),
        )
    }

    private fun LeafValueSource.behaviorSpecification(leaf: LeafEvaluationConfig): EvaluatorSpecification =
        EvaluatorSpecification(
            evaluatorId = invokedEvaluatorId,
            evaluatorConfigurationId = invokedEvaluatorConfigurationId,
            valueSource = when (this) {
                is LeafValueSource.Information -> "root-player-policy-information-v1"
                is LeafValueSource.SampledWorld -> "sampled-world-allowlist-v1"
            },
            settlesAtRolloutHorizon = leaf.cutoff != RolloutCutoff.EVALUATE,
            unresolvedLeafHandling = leaf.unresolved.name,
        )
}
