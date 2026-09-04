package org.mtgallium.agent.searchteacher

import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumBeliefProposalAuditSink
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.InformationSetSearchResult
import org.mtgallium.agent.infoset.core.InformationSetSearchReuseConfig
import org.mtgallium.agent.infoset.core.InformationStateEvaluator
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.Weighted
import org.mtgallium.agent.infoset.core.exactSingletonPassOrNull
import kotlinx.serialization.Serializable

const val SEARCH_TEACHER_UNPROFILED_RUNTIME_ID: String = "unprofiled-search-teacher-o02-v1"

@Serializable
data class PolicyCompressionConfig(
    val schemaVersion: Int = 1,
    val enabled: Boolean = false,
) {
    init { require(schemaVersion == 1) }
}

@Serializable
data class SearchReuseConfig(
    val schemaVersion: Int = 1,
    val enabled: Boolean = false,
    val minimumFreshSimulations: Int = 16,
    val maximumReuseFraction: Double = 0.75,
) {
    init {
        require(schemaVersion == 1)
        require(!enabled) {
            "Production search reuse is disabled until retained paths are reweighted against the current hidden-world distribution"
        }
        require(minimumFreshSimulations > 0)
        require(maximumReuseFraction.isFinite() && maximumReuseFraction in 0.0..1.0)
    }

    internal fun coreConfig(): InformationSetSearchReuseConfig = InformationSetSearchReuseConfig(
        enabled = enabled,
        minimumFreshSimulations = minimumFreshSimulations,
        maximumReuseFraction = maximumReuseFraction,
    )
}

/** All behavior-affecting inputs shared by live play and offline evaluation. */
data class SearchTeacherPolicyParameters(
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val explorationConstant: Double,
    val leaf: LeafEvaluationConfig,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val beliefMode: BeliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
    val beliefArchitecture: BeliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
    val baseSeed: Long,
    val profileId: String = SEARCH_TEACHER_UNPROFILED_RUNTIME_ID,
    val policyCompression: PolicyCompressionConfig = PolicyCompressionConfig(),
    val searchReuse: SearchReuseConfig = SearchReuseConfig(),
    val initialExpansionLimit: Int = 64,
    val wideningThresholds: List<Int> = listOf(64, 256, 1024),
    val wideningLimits: List<Int> = listOf(128, 256, 512),
    val maxQuiescenceDecisions: Int = 32,
    val maxQuiescenceForcedPasses: Int = 256,
    val cacheSimulationTransitions: Boolean = true,
    val wallClockBudgetMillis: Long? = null,
    val minimumSimulations: Int = 1,
) {
    init {
        require(particles > 0)
        require(simulations > 0)
        require(maxPolicyDecisions > 0)
        require(explorationConstant >= 0.0 && explorationConstant.isFinite())
        require(profileId.isNotBlank())
        require(!policyCompression.enabled) {
            "Profile-only singleton automation is disabled because it can remove genuine player decisions"
        }
    }

    fun searchConfig(): InformationSetSearchConfig = InformationSetSearchConfig(
        simulations = simulations,
        explorationConstant = explorationConstant,
        maxPolicyDecisions = maxPolicyDecisions,
        leaf = leaf,
        initialExpansionLimit = initialExpansionLimit,
        wideningThresholds = wideningThresholds,
        wideningLimits = wideningLimits,
        maxQuiescenceDecisions = maxQuiescenceDecisions,
        maxQuiescenceForcedPasses = maxQuiescenceForcedPasses,
        compressPolicySingletonPasses = policyCompression.enabled,
        cacheSimulationTransitions = cacheSimulationTransitions,
        wallClockBudgetMillis = wallClockBudgetMillis,
        minimumSimulations = minimumSimulations,
    )

    fun behaviorSpecification(
        knownDecks: Map<String, Map<String, Int>>,
        opponentPolicy: OpponentPolicy,
        rootRolloutPolicy: OpponentPolicy = SearchTeacherSearchFactory.rootRolloutPolicy(),
        opponentRolloutPolicy: OpponentPolicy = SearchTeacherSearchFactory.opponentRolloutPolicy(),
        informationEvaluator: InformationStateEvaluator? = null,
        integration: SearchTeacherIntegrationSpecification = SearchTeacherIntegrationSpecification(),
    ): SearchTeacherBehaviorSpecification = SearchTeacherPolicyIdentity.specification(
        parameters = this,
        knownDecks = knownDecks,
        opponentPolicy = opponentPolicy,
        rootRolloutPolicy = rootRolloutPolicy,
        opponentRolloutPolicy = opponentRolloutPolicy,
        informationEvaluator = informationEvaluator,
        integration = integration,
    )

    fun policyIdentity(
        knownDecks: Map<String, Map<String, Int>>,
        opponentPolicy: OpponentPolicy,
        rootRolloutPolicy: OpponentPolicy = SearchTeacherSearchFactory.rootRolloutPolicy(),
        opponentRolloutPolicy: OpponentPolicy = SearchTeacherSearchFactory.opponentRolloutPolicy(),
        informationEvaluator: InformationStateEvaluator? = null,
        integration: SearchTeacherIntegrationSpecification = SearchTeacherIntegrationSpecification(),
    ): String = SearchTeacherPolicyIdentity.identity(
        behaviorSpecification(
            knownDecks = knownDecks,
            opponentPolicy = opponentPolicy,
            rootRolloutPolicy = rootRolloutPolicy,
            opponentRolloutPolicy = opponentRolloutPolicy,
            informationEvaluator = informationEvaluator,
            integration = integration,
        )
    )
}

@Serializable
enum class SearchTeacherSelectionKind {
    RULES_FORCED_PASS,
    /** Retained only so historical records remain decodable; current policy never emits it. */
    POLICY_SINGLETON_PASS,
    /** Retained only so historical records remain decodable; current policy never emits it. */
    SHARED_PREGAME,
    SEARCHED,
}

data class SearchTeacherPolicySelection(
    val choice: SemanticChoice,
    val search: InformationSetSearchResult?,
    val kind: SearchTeacherSelectionKind,
)

/** Pure production classifier used before any search budget is allocated. */
object SearchTeacherAutomaticSelection {
    fun classify(expansion: PolicyExpansion): SearchTeacherPolicySelection? {
        expansion.exactSingletonPassOrNull()?.let { forced ->
            return SearchTeacherPolicySelection(
                choice = forced,
                search = null,
                kind = SearchTeacherSelectionKind.RULES_FORCED_PASS,
            )
        }
        return null
    }
}

/** Complete stateful policy lifecycle shared by every production and evaluation host. */
class SearchTeacherPolicySession(
    root: ArgentumSearchWorld,
    private val viewer: String,
    knownDecks: Map<String, Map<String, Int>>,
    private val parameters: SearchTeacherPolicyParameters,
    private val opponentPolicy: OpponentPolicy,
    private val gameId: String,
    private val rolloutPolicy: OpponentPolicy = SearchTeacherSearchFactory.rootRolloutPolicy(),
    private val rolloutOpponentPolicy: OpponentPolicy = SearchTeacherSearchFactory.opponentRolloutPolicy(),
    private val informationEvaluator: InformationStateEvaluator? = null,
    private val integration: SearchTeacherIntegrationSpecification = SearchTeacherIntegrationSpecification(),
    private val beliefProposalAuditSink: ArgentumBeliefProposalAuditSink =
        ArgentumBeliefProposalAuditSink.NONE,
) {
    val behaviorSpecification: SearchTeacherBehaviorSpecification =
        SearchTeacherPolicyIdentity.specification(
            parameters = parameters,
            knownDecks = knownDecks,
            opponentPolicy = opponentPolicy,
            rootRolloutPolicy = rolloutPolicy,
            opponentRolloutPolicy = rolloutOpponentPolicy,
            informationEvaluator = informationEvaluator,
            actionExpansion = root.semanticExpansionSpecification(),
            integration = integration,
        )
    private val belief = SearchTeacherBeliefTracker(
        root = root,
        viewer = viewer,
        knownDecks = knownDecks,
        parameters = parameters,
        opponentModel = opponentPolicy,
        gameId = gameId,
        proposalAuditSink = beliefProposalAuditSink,
    )
    private val search = SearchTeacherSearchFactory.session(
        config = parameters.searchConfig(),
        opponentPolicy = opponentPolicy,
        rolloutPolicy = rolloutPolicy,
        rolloutOpponentPolicy = rolloutOpponentPolicy,
        informationEvaluator = informationEvaluator,
        reuseConfig = parameters.searchReuse.coreConfig(),
    )
    private var acceptedDecisionCount: Int = 0

    val latestBeliefDiagnostics: BeliefDiagnostics get() = belief.latestDiagnostics
    val beliefDiagnosticsHistory: List<BeliefDiagnostics> get() = belief.diagnosticsHistory
    val beliefReconditionings: Int get() = belief.reconditionings
    val beliefParticleDepletions: Int get() = belief.particleDepletions
    val beliefLowEssUpdates: Int get() = belief.lowEssUpdates
    val beliefInvalidWeights: Int get() = belief.invalidWeights
    val beliefLifecycleDiagnostics: SearchTeacherBeliefLifecycleDiagnostics
        get() = belief.lifecycleDiagnostics
    val policyIdentity: String = SearchTeacherPolicyIdentity.identity(behaviorSpecification)
    fun beliefBatch(actual: ArgentumSearchWorld? = null): BeliefBatch<Weighted<SearchWorld>> {
        actual?.let { belief.synchronize(it, acceptedDecisionCount) }
        return belief.batch()
    }

    fun select(
        world: ArgentumSearchWorld,
        actor: String,
        searchSeed: Long,
    ): SearchTeacherPolicySelection {
        require(actor == viewer) { "Policy session for $viewer cannot choose for $actor" }
        val expansion = world.expandChoices()
        check(expansion.candidates.isNotEmpty()) { "No semantic candidates are available" }
        SearchTeacherAutomaticSelection.classify(expansion)?.let { return it }
        // Sequential particles are still advanced after every accepted action, but the expensive
        // all-particle digest audit is needed only when its result can affect an actual search.
        belief.synchronize(world, acceptedDecisionCount)
        val result = search.search(
            rootPlayer = actor,
            belief = belief.batch(),
            searchSeed = searchSeed,
            beliefContinuityEpoch = belief.continuityEpoch,
        )
        return SearchTeacherPolicySelection(
            choice = result.chosen,
            search = result,
            kind = SearchTeacherSelectionKind.SEARCHED,
        )
    }

    fun observeAccepted(
        actual: ArgentumSearchWorld,
        actor: String,
        choice: SemanticChoice,
        decisionIndex: Int,
        privateToActor: Boolean,
    ) {
        belief.advance(actual, actor, choice, decisionIndex, privateToActor)
        acceptedDecisionCount = decisionIndex + 1
    }
}
