package org.mtgallium.agent.searchteacher

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToJsonElement
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpansionSpecification
import org.mtgallium.agent.infoset.core.BOUNDED_POLICY_INPUT_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.KNOWLEDGE_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.LeafEvaluationStrategy
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicyBehaviorSpecification
import org.mtgallium.agent.infoset.core.POLICY_HISTORY_COMMITMENT_ALGORITHM
import org.mtgallium.agent.infoset.core.POLICY_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.PolicyJson

const val SEARCH_TEACHER_BEHAVIOR_SCHEMA_V1: Int = 1
const val SEARCH_TEACHER_BEHAVIOR_IDENTITY_PREFIX: String =
    "search-teacher-behavior-v1-sha256"

@Serializable
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
data class SearchTeacherInputSchemaSpecification(
    val playerInformationSchema: Int = POLICY_SCHEMA_CURRENT,
    val candidateSchema: Int = CANDIDATE_SCHEMA_CURRENT,
    val boundedPolicyInputSchema: Int = BOUNDED_POLICY_INPUT_SCHEMA_CURRENT,
    val knowledgeSchema: Int = KNOWLEDGE_SCHEMA_CURRENT,
    val historyCommitmentAlgorithm: String = POLICY_HISTORY_COMMITMENT_ALGORITHM,
)

@Serializable
data class SearchTeacherActionSpaceSpecification(
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
data class SearchTeacherEvaluatorSpecification(
    val configuredEvaluatorId: String,
    val invokedEvaluatorId: String,
    val invokedEvaluatorConfigurationId: String,
    val valueSource: String,
    val supportsTraceReuse: Boolean,
    val settlesAtRolloutHorizon: Boolean,
    val unresolvedLeafHandling: String,
)

/**
 * Host choices that can change which algorithm runs or when a stored game stops. Source-tree and
 * source-tree changes are deliberately recorded by the artifact layer, not inferred here.
 */
@Serializable
data class SearchTeacherIntegrationSpecification(
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
data class SearchTeacherBehaviorSpecification(
    val schemaVersion: Int = SEARCH_TEACHER_BEHAVIOR_SCHEMA_V1,
    val declaredProfileId: String,
    val particles: Int,
    val search: InformationSetSearchConfig,
    val beliefMode: String,
    val beliefArchitecture: String,
    val baseSeed: Long,
    val policyCompression: PolicyCompressionConfig,
    val searchReuse: SearchReuseConfig,
    val actionSpace: SearchTeacherActionSpaceSpecification,
    val evaluator: SearchTeacherEvaluatorSpecification,
    val opponentPolicy: OpponentPolicyBehaviorSpecification,
    val rootRolloutPolicy: OpponentPolicyBehaviorSpecification,
    val opponentRolloutPolicy: OpponentPolicyBehaviorSpecification,
    val knownDecks: List<KnownDeckSpecification>,
    val inputSchemas: SearchTeacherInputSchemaSpecification,
    val integration: SearchTeacherIntegrationSpecification,
    // An absent field retains the previous behavior and its canonical identity on re-encoding.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val singletonSelection: PolicySingletonSelectionConfig = PolicySingletonSelectionConfig(),
) {
    init {
        require(schemaVersion == SEARCH_TEACHER_BEHAVIOR_SCHEMA_V1)
        require(declaredProfileId.isNotBlank())
        require(particles > 0)
        require(knownDecks.map(KnownDeckSpecification::playerId).distinct().size == knownDecks.size)
        require(knownDecks == knownDecks.sortedBy(KnownDeckSpecification::playerId))
        require(search.compressPolicySingletonPasses == policyCompression.enabled)
    }
}

object SearchTeacherPolicyIdentity {
    fun specification(
        parameters: SearchTeacherPolicyParameters,
        knownDecks: Map<String, Map<String, Int>>,
        opponentPolicy: OpponentPolicy,
        rootRolloutPolicy: OpponentPolicy = SearchTeacherSearchFactory.rootRolloutPolicy(),
        opponentRolloutPolicy: OpponentPolicy = SearchTeacherSearchFactory.opponentRolloutPolicy(),
        informationEvaluator: org.mtgallium.agent.infoset.core.InformationStateEvaluator? = null,
        actionExpansion: UnifiedSemanticExpansionSpecification =
            UnifiedSemanticExpander.defaultBehaviorSpecification(parameters.actionSpaceProfile),
        integration: SearchTeacherIntegrationSpecification = SearchTeacherIntegrationSpecification(),
    ): SearchTeacherBehaviorSpecification {
        require(actionExpansion.actionSpaceProfile == parameters.actionSpaceProfile) {
            "Policy action-space profile does not match the world's candidate generator"
        }
        val evaluator = SearchTeacherEvaluatorRegistry.strategy(
            parameters.leaf,
            informationEvaluator,
        )
        return SearchTeacherBehaviorSpecification(
            declaredProfileId = parameters.profileId,
            particles = parameters.particles,
            search = parameters.searchConfig(),
            beliefMode = parameters.beliefMode.name,
            beliefArchitecture = parameters.beliefArchitecture.name,
            baseSeed = parameters.baseSeed,
            policyCompression = parameters.policyCompression,
            searchReuse = parameters.searchReuse,
            actionSpace = SearchTeacherActionSpaceSpecification(
                profileId = parameters.actionSpaceProfile.profileId,
                rulesEquivalent = parameters.actionSpaceProfile.rulesEquivalent,
                suppressesStandaloneManaAbilities =
                    parameters.actionSpaceProfile.suppressesStandaloneManaAbilities,
                expansion = actionExpansion,
            ),
            evaluator = evaluator.behaviorSpecification(),
            opponentPolicy = opponentPolicy.behaviorSpecification,
            rootRolloutPolicy = rootRolloutPolicy.behaviorSpecification,
            opponentRolloutPolicy = opponentRolloutPolicy.behaviorSpecification,
            knownDecks = normalizeKnownDecks(knownDecks),
            inputSchemas = SearchTeacherInputSchemaSpecification(),
            integration = integration,
            singletonSelection = parameters.singletonSelection,
        )
    }

    fun identity(specification: SearchTeacherBehaviorSpecification): String {
        val element = PolicyJson.format.encodeToJsonElement(
            SearchTeacherBehaviorSpecification.serializer(),
            specification,
        )
        return "$SEARCH_TEACHER_BEHAVIOR_IDENTITY_PREFIX:${PolicyJson.digest(element)}"
    }

    fun identity(
        parameters: SearchTeacherPolicyParameters,
        knownDecks: Map<String, Map<String, Int>>,
        opponentPolicy: OpponentPolicy,
        rootRolloutPolicy: OpponentPolicy = SearchTeacherSearchFactory.rootRolloutPolicy(),
        opponentRolloutPolicy: OpponentPolicy = SearchTeacherSearchFactory.opponentRolloutPolicy(),
        informationEvaluator: org.mtgallium.agent.infoset.core.InformationStateEvaluator? = null,
        actionExpansion: UnifiedSemanticExpansionSpecification =
            UnifiedSemanticExpander.defaultBehaviorSpecification(parameters.actionSpaceProfile),
        integration: SearchTeacherIntegrationSpecification = SearchTeacherIntegrationSpecification(),
    ): String = identity(
        specification(
            parameters = parameters,
            knownDecks = knownDecks,
            opponentPolicy = opponentPolicy,
            rootRolloutPolicy = rootRolloutPolicy,
            opponentRolloutPolicy = opponentRolloutPolicy,
            informationEvaluator = informationEvaluator,
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

    private fun LeafEvaluationStrategy.behaviorSpecification(): SearchTeacherEvaluatorSpecification =
        SearchTeacherEvaluatorSpecification(
            configuredEvaluatorId = configuredEvaluatorId,
            invokedEvaluatorId = source.invokedEvaluatorId,
            invokedEvaluatorConfigurationId = source.invokedEvaluatorConfigurationId,
            valueSource = when (source) {
                is LeafValueSource.Information -> "root-player-policy-information-v1"
                is LeafValueSource.SampledWorld -> "sampled-world-allowlist-v1"
            },
            supportsTraceReuse = supportsTraceReuse,
            settlesAtRolloutHorizon = settleAtRolloutHorizon,
            unresolvedLeafHandling = unresolvedLeafHandling.name,
        )
}
