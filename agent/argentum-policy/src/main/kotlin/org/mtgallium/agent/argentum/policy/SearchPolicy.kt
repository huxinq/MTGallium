package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.infoset.core.BeliefSnapshot
import org.mtgallium.agent.infoset.core.BeliefQueryView

import org.mtgallium.agent.monored.ValueEvaluationStop
import org.mtgallium.agent.monored.ValueEvaluationException
import org.mtgallium.agent.monored.ValueInputError
import org.mtgallium.agent.infoset.core.DecisionPolicy
import org.mtgallium.agent.infoset.core.ActionSelector
import org.mtgallium.agent.infoset.core.ROOT_SELECTION_GUIDANCE_RULE
import org.mtgallium.agent.infoset.core.RootSelectionGuidance
import org.mtgallium.agent.infoset.core.RootSelectionPolicy
import org.mtgallium.agent.infoset.core.RootActionSelection
import org.mtgallium.agent.infoset.core.RootActionSelector
import org.mtgallium.agent.infoset.core.SingletonSelectionConfig

import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumHeuristicProfile
import org.mtgallium.agent.infoset.argentum.ArgentumBeliefProposalAuditSink
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.InformationStateEvaluator
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.monored.MonoRedInformationEvaluator
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.PolicyComponent
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.RolloutTurnHorizon
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.Weighted
import kotlinx.serialization.Serializable

const val SEARCH_POLICY_UNPROFILED_RUNTIME_ID: String = "unprofiled-search-teacher-o02-v1"

/** All behavior-affecting inputs shared by live play and offline evaluation. */
data class SearchPolicyConfig(
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val explorationConstant: Double,
    val leaf: LeafEvaluationConfig,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val beliefMode: BeliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
    val beliefArchitecture: BeliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
    val baseSeed: Long,
    val profileId: String = SEARCH_POLICY_UNPROFILED_RUNTIME_ID,
    val initialExpansionLimit: Int = 64,
    val wideningThresholds: List<Int> = listOf(64, 256, 1024),
    val wideningLimits: List<Int> = listOf(128, 256, 512),
    val maxQuiescenceDecisions: Int = 32,
    val maxQuiescenceForcedPasses: Int = 256,
    val cacheSimulationTransitions: Boolean = true,
    val wallClockBudgetMillis: Long? = null,
    val minimumSimulations: Int = 1,
    val singletonSelection: SingletonSelectionConfig = SingletonSelectionConfig(),
    /** Opt-in simulated-tree/root-and-opponent heuristic annotation only; belief updates are unchanged. */
    val searchHeuristicProfile: ArgentumHeuristicProfile = ArgentumHeuristicProfile.PRODUCTION,
    val rolloutTurnHorizon: RolloutTurnHorizon? = null,
) {
    init {
        require(particles > 0)
        require(simulations > 0)
        require(maxPolicyDecisions > 0)
        require(explorationConstant >= 0.0 && explorationConstant.isFinite())
        require(profileId.isNotBlank())
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
        cacheSimulationTransitions = cacheSimulationTransitions,
        wallClockBudgetMillis = wallClockBudgetMillis,
        minimumSimulations = minimumSimulations,
        rolloutTurnHorizon = rolloutTurnHorizon,
    )

    fun behaviorSpecification(
        knownDecks: Map<String, Map<String, Int>>,
        opponentPolicy: PolicyComponent,
        rootRolloutPolicy: ActionSelector = PolicyDefaults.rootRolloutPolicy(),
        opponentRolloutPolicy: ActionSelector = PolicyDefaults.opponentRolloutPolicy(),
        valueSource: LeafValueSource = LeafValueSource.Information(MonoRedInformationEvaluator),
        integration: IntegrationSpecification = IntegrationSpecification(),
    ): PolicyBehaviorSpecification = PolicyIdentity.specification(
        parameters = this,
        knownDecks = knownDecks,
        opponentPolicy = opponentPolicy,
        rootRolloutPolicy = rootRolloutPolicy,
        opponentRolloutPolicy = opponentRolloutPolicy,
        valueSource = valueSource,
        integration = integration,
    )

    fun policyIdentity(
        knownDecks: Map<String, Map<String, Int>>,
        opponentPolicy: PolicyComponent,
        rootRolloutPolicy: ActionSelector = PolicyDefaults.rootRolloutPolicy(),
        opponentRolloutPolicy: ActionSelector = PolicyDefaults.opponentRolloutPolicy(),
        valueSource: LeafValueSource = LeafValueSource.Information(MonoRedInformationEvaluator),
        integration: IntegrationSpecification = IntegrationSpecification(),
    ): String = PolicyIdentity.identity(
        behaviorSpecification(
            knownDecks = knownDecks,
            opponentPolicy = opponentPolicy,
            rootRolloutPolicy = rootRolloutPolicy,
            opponentRolloutPolicy = opponentRolloutPolicy,
            valueSource = valueSource,
            integration = integration,
        )
    )
}

/** Complete stateful policy lifecycle shared by every production and evaluation host. */
class SearchPolicySession private constructor(
    root: ArgentumSearchWorld,
    private val viewer: String,
    private val knownDecks: Map<String, Map<String, Int>>,
    private val parameters: SearchPolicyConfig,
    private val opponentPolicy: OpponentPolicy,
    private val gameId: String,
    private val rolloutPolicy: ActionSelector = PolicyDefaults.rootRolloutPolicy(),
    private val rolloutOpponentPolicy: ActionSelector = PolicyDefaults.opponentRolloutPolicy(),
    private val valueSource: LeafValueSource = LeafValueSource.Information(MonoRedInformationEvaluator),
    private val integration: IntegrationSpecification = IntegrationSpecification(),
    private val beliefProposalAuditSink: ArgentumBeliefProposalAuditSink =
        ArgentumBeliefProposalAuditSink.NONE,
    private val rootSelectionPolicy: RootSelectionPolicy? = null,
    private val directRootSelectionPolicy: DecisionPolicy? = null,
    forkedFrom: SearchPolicySession?,
) {
    constructor(root: ArgentumSearchWorld, viewer: String, knownDecks: Map<String, Map<String, Int>>,
        parameters: SearchPolicyConfig, opponentPolicy: OpponentPolicy, gameId: String,
        rolloutPolicy: ActionSelector = PolicyDefaults.rootRolloutPolicy(),
        rolloutOpponentPolicy: ActionSelector = PolicyDefaults.opponentRolloutPolicy(),
        valueSource: LeafValueSource = LeafValueSource.Information(MonoRedInformationEvaluator),
        integration: IntegrationSpecification = IntegrationSpecification(),
        beliefProposalAuditSink: ArgentumBeliefProposalAuditSink = ArgentumBeliefProposalAuditSink.NONE,
        rootSelectionPolicy: RootSelectionPolicy? = null, directRootSelectionPolicy: DecisionPolicy? = null) :
        this(root, viewer, knownDecks, parameters, opponentPolicy, gameId, rolloutPolicy, rolloutOpponentPolicy,
            valueSource, integration, beliefProposalAuditSink, rootSelectionPolicy, directRootSelectionPolicy, null)

    val behaviorSpecification: PolicyBehaviorSpecification =
        PolicyIdentity.specification(
            parameters = parameters,
            knownDecks = knownDecks,
            opponentPolicy = opponentPolicy,
            rootRolloutPolicy = rolloutPolicy,
            opponentRolloutPolicy = rolloutOpponentPolicy,
            valueSource = valueSource,
            actionExpansion = root.semanticExpansionSpecification(),
            integration = integration,
        ).copy(historyEventOrder = root.historyEventOrder.takeUnless {
            it == org.mtgallium.agent.infoset.argentum.PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1
        }?.name, historyObjectReference = root.historyObjectReference.takeUnless {
            it == org.mtgallium.agent.infoset.argentum.PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1
        }?.name, rootSelectionGuidanceId = rootSelectionPolicy?.let {
            require(it.configurationId.isNotBlank())
            "$ROOT_SELECTION_GUIDANCE_RULE:${it.configurationId}"
        }, directRootSelectionId = directRootSelectionPolicy?.configurationId?.also { require(it.isNotBlank()) })
    init {
        require(directRootSelectionPolicy == null || rootSelectionPolicy == null)
        require(forkedFrom == null || behaviorSpecification == forkedFrom.behaviorSpecification)
    }
    private val belief: ArgentumParticleBeliefBackend = forkedFrom?.belief?.fork(root) ?: ArgentumParticleBeliefBackend(
        root = root,
        viewer = viewer,
        knownDecks = knownDecks,
        parameters = parameters,
        opponentModel = opponentPolicy,
        gameId = gameId,
        proposalAuditSink = beliefProposalAuditSink,
    )
    private val search = createSearch(
        config = parameters.searchConfig(),
        opponentPolicy = opponentPolicy,
        rolloutPolicy = rolloutPolicy,
        rolloutOpponentPolicy = rolloutOpponentPolicy,
        valueSource = valueSource,
    )
    private var acceptedDecisionCount: Int = forkedFrom?.acceptedDecisionCount ?: 0

    /**
     * Fork the current belief lifecycle without constructing new initial particles. The caller owns
     * the independent factual world and future search seeds. Policy components remain the same configured components, as in independent arena sessions.
     * Information compatibility is checked here; factual provenance is the experiment host's duty.
     */
    fun forkForFactualContinuation(root: ArgentumSearchWorld): SearchPolicySession {
        return SearchPolicySession(root, viewer, knownDecks, parameters, opponentPolicy, gameId,
            rolloutPolicy, rolloutOpponentPolicy, valueSource, integration, beliefProposalAuditSink,
            rootSelectionPolicy, directRootSelectionPolicy, this)
    }

    val latestBeliefDiagnostics: BeliefDiagnostics get() = belief.latestDiagnostics
    val beliefDiagnosticsHistory: List<BeliefDiagnostics> get() = belief.diagnosticsHistory
    val beliefReconditionings: Int get() = belief.reconditionings
    val beliefParticleDepletions: Int get() = belief.particleDepletions
    val beliefLowEssUpdates: Int get() = belief.lowEssUpdates
    val beliefInvalidWeights: Int get() = belief.invalidWeights
    val beliefLifecycleDiagnostics: BeliefUpdateDiagnostics
        get() = belief.lifecycleDiagnostics
    val policyIdentity: String = PolicyIdentity.identity(behaviorSpecification)
    fun beliefSnapshot(actual: ArgentumSearchWorld? = null): BeliefSnapshot {
        actual?.let { belief.synchronize(it, acceptedDecisionCount) }
        return belief.snapshot()
    }

    fun beliefQueries(actual: ArgentumSearchWorld? = null): BeliefQueryView = beliefSnapshot(actual).queries

    fun beliefBatch(actual: ArgentumSearchWorld? = null): BeliefBatch<Weighted<SearchWorld>> {
        actual?.let { belief.synchronize(it, acceptedDecisionCount) }
        return profiledBeliefBatch()
    }

    private fun profiledBeliefBatch(): BeliefBatch<Weighted<SearchWorld>> =
        belief.snapshot().hypotheses.materialize().batch.withHeuristicProfile(parameters.searchHeuristicProfile)

    fun select(
        world: ArgentumSearchWorld,
        actor: String,
        searchSeed: Long,
    ): RootActionSelection {
        require(actor == viewer) { "Policy session for $viewer cannot choose for $actor" }
        val context = world.decisionContext()
        val expansion = context.expansion
        return RootActionSelector(parameters.singletonSelection.enabled, directRootSelectionPolicy).select(
            context, searchSeed,
        ) {
            // Sequential particles are still advanced after every accepted action, but the expensive
            // all-particle digest audit is needed only when its result can affect an actual search.
            belief.synchronize(world, acceptedDecisionCount)
            val guidance = rootSelectionPolicy?.let { policy ->
                require(expansion.isProfileExhaustive) { "Root guidance requires a profile-exhaustive admitted menu" }
                val information = context.information()
                RootSelectionGuidance(policy.configurationId,
                    information.informationStateDigest, policy.scores(context.site()))
            }
            val result = try {
                search.search(
                    rootPlayer = actor,
                    belief = profiledBeliefBatch(),
                    searchSeed = searchSeed,
                    rootSelectionGuidance = guidance,
                )
            } catch (failure: ValueEvaluationException) {
                throw ValueEvaluationStop(failure)
            }
            RootActionSelection.Searched(result)
        }
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

    val lastObservedUpdate: ObservedBeliefUpdateEvidence? get() = belief.lastObservedUpdate
}
