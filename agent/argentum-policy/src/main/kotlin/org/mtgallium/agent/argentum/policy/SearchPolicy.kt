package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.infoset.planning.BeliefSnapshot
import org.mtgallium.agent.infoset.core.BeliefQueryView

import org.mtgallium.agent.infoset.core.DecisionPolicy
import org.mtgallium.agent.infoset.core.ActionSelector
import org.mtgallium.agent.infoset.planning.RootActionSelection
import org.mtgallium.agent.infoset.planning.RootActionSelector
import org.mtgallium.agent.infoset.planning.SingletonMenuShortcutConfig

import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumBeliefProposalAuditSink
import org.mtgallium.agent.infoset.core.BeliefApproximation
import org.mtgallium.agent.infoset.planning.ParticleSet
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.planning.InformationSetSearchConfig
import org.mtgallium.agent.infoset.planning.LeafValueSource
import org.mtgallium.agent.value.MaterialEvaluator
import org.mtgallium.agent.infoset.planning.LeafEvaluationConfig
import org.mtgallium.agent.infoset.planning.LeafEvaluationMethod
import org.mtgallium.agent.infoset.core.PolicyComponent
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.planning.RolloutTurnHorizon
import org.mtgallium.agent.infoset.core.ActionSpaceProfile
import org.mtgallium.agent.infoset.planning.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.Weighted

const val SEARCH_POLICY_UNPROFILED_RUNTIME_ID: String = "unprofiled-search-teacher-o02-v1"

/** All behavior-affecting inputs shared by live play and offline evaluation. */
data class SearchPolicyConfig(
    val particles: Int = 8,
    val simulations: Int = 64,
    val maxPolicyDecisions: Int = 32,
    val explorationConstant: Double = 1.4,
    val leaf: LeafEvaluationConfig = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT),
    val actionSpaceProfile: ActionSpaceProfile = ActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
    val beliefMode: BeliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
    val beliefArchitecture: BeliefApproximation = BeliefApproximation.SEQUENTIAL_B_V1,
    val baseSeed: Long = 20260825L,
    val profileId: String = SEARCH_POLICY_UNPROFILED_RUNTIME_ID,
    val initialExpansionLimit: Int = 64,
    val wideningThresholds: List<Int> = listOf(64, 256, 1024),
    val wideningLimits: List<Int> = listOf(128, 256, 512),
    val maxQuiescenceDecisions: Int = 32,
    val maxQuiescenceForcedPasses: Int = 256,
    val singletonSelection: SingletonMenuShortcutConfig = SingletonMenuShortcutConfig(),
    val rolloutTurnHorizon: RolloutTurnHorizon? = null,
) {
    init {
        require(particles > 0)
        require(simulations > 0)
        require(maxPolicyDecisions > 0)
        require(explorationConstant >= 0.0 && explorationConstant.isFinite())
        require(profileId.isNotBlank())
    }

    val displayName: String get() = "$profileId · ${particles}×${simulations} · ${actionSpaceProfile.profileId}"

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
        rolloutTurnHorizon = rolloutTurnHorizon,
    )

    /** The host must admit every root action reachable by this search budget. */
    internal fun decisionView(
        prior: org.mtgallium.agent.infoset.planning.SearchPrior? = null,
        widen: Boolean = true,
    ): org.mtgallium.agent.infoset.core.MenuRequest {
        if (prior != null) return org.mtgallium.agent.infoset.core.MenuRequest(
            limit = prior.candidateLimit, admission = prior.admission)
        if (!widen) return org.mtgallium.agent.infoset.core.MenuRequest()
        val reachableLimit = wideningThresholds.zip(wideningLimits)
            .filter { (visits, _) -> visits < simulations }
            .fold(initialExpansionLimit) { limit, (_, widened) -> maxOf(limit, widened) }
        return org.mtgallium.agent.infoset.core.MenuRequest(
            limit = reachableLimit.takeUnless { it == 64 && initialExpansionLimit == 64 })
    }

    fun behaviorSpecification(
        knownDecks: Map<String, Map<String, Int>>,
        opponentPolicy: PolicyComponent,
        rootRolloutPolicy: ActionSelector = PolicyDefaults.rootRolloutPolicy(),
        opponentRolloutPolicy: ActionSelector = PolicyDefaults.opponentRolloutPolicy(),
        valueSource: LeafValueSource = LeafValueSource.Information(MaterialEvaluator()),
    ): PolicyBehaviorSpecification = PolicyIdentity.specification(
        parameters = this,
        knownDecks = knownDecks,
        opponentPolicy = opponentPolicy,
        rootRolloutPolicy = rootRolloutPolicy,
        opponentRolloutPolicy = opponentRolloutPolicy,
        valueSource = valueSource,
    )

    fun policyIdentity(
        knownDecks: Map<String, Map<String, Int>>,
        opponentPolicy: PolicyComponent,
        rootRolloutPolicy: ActionSelector = PolicyDefaults.rootRolloutPolicy(),
        opponentRolloutPolicy: ActionSelector = PolicyDefaults.opponentRolloutPolicy(),
        valueSource: LeafValueSource = LeafValueSource.Information(MaterialEvaluator()),
    ): String = PolicyIdentity.identity(
        behaviorSpecification(
            knownDecks = knownDecks,
            opponentPolicy = opponentPolicy,
            rootRolloutPolicy = rootRolloutPolicy,
            opponentRolloutPolicy = opponentRolloutPolicy,
            valueSource = valueSource,
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
    private val valueSource: LeafValueSource = LeafValueSource.Information(MaterialEvaluator()),
    private val beliefProposalAuditSink: ArgentumBeliefProposalAuditSink =
        ArgentumBeliefProposalAuditSink.NONE,
    private val directRootSelectionPolicy: DecisionPolicy? = null,
    private val searchPrior: org.mtgallium.agent.infoset.planning.SearchPrior? = null,
    forkedFrom: SearchPolicySession?,
) {
    constructor(root: ArgentumSearchWorld, viewer: String, knownDecks: Map<String, Map<String, Int>>,
        parameters: SearchPolicyConfig, opponentPolicy: OpponentPolicy, gameId: String,
        rolloutPolicy: ActionSelector = PolicyDefaults.rootRolloutPolicy(),
        rolloutOpponentPolicy: ActionSelector = PolicyDefaults.opponentRolloutPolicy(),
        valueSource: LeafValueSource = LeafValueSource.Information(MaterialEvaluator()),
        beliefProposalAuditSink: ArgentumBeliefProposalAuditSink = ArgentumBeliefProposalAuditSink.NONE,
        directRootSelectionPolicy: DecisionPolicy? = null,
        searchPrior: org.mtgallium.agent.infoset.planning.SearchPrior? = null) :
        this(root, viewer, knownDecks, parameters, opponentPolicy, gameId, rolloutPolicy, rolloutOpponentPolicy,
            valueSource, beliefProposalAuditSink, directRootSelectionPolicy, searchPrior, null)

    val behaviorSpecification: PolicyBehaviorSpecification =
        PolicyIdentity.specification(
            parameters = parameters,
            knownDecks = knownDecks,
            opponentPolicy = opponentPolicy,
            rootRolloutPolicy = rolloutPolicy,
            opponentRolloutPolicy = rolloutOpponentPolicy,
            valueSource = valueSource,
            actionExpansion = root.semanticExpansionSpecification(),
        ).copy(historyEventOrder = root.historyEventOrder.takeUnless {
            it == org.mtgallium.agent.infoset.argentum.HistoryEventOrdering.LEGACY_ENGINE_ORDER_V1
        }?.name, historyObjectReference = root.historyObjectReference.takeUnless {
            it == org.mtgallium.agent.infoset.argentum.HistoryObjectReferencing.LEGACY_SNAPSHOT_V1
        }?.name, directRootSelectionId = directRootSelectionPolicy?.configurationId?.also { require(it.isNotBlank()) },
            searchPriorId = searchPrior?.let { "puct-v1:${it.configurationId}:limit=${it.candidateLimit}:admission=${it.admission.name}:c=${it.explorationConstant}" })
    init {
        require(forkedFrom == null || behaviorSpecification == forkedFrom.behaviorSpecification)
    }
    private val belief: ArgentumParticleFilter = forkedFrom?.belief?.fork(root) ?: ArgentumParticleFilter(
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
        searchPrior = searchPrior,
    )
    private var acceptedDecisionCount: Int = forkedFrom?.acceptedDecisionCount ?: 0

    /**
     * Fork the current belief lifecycle without constructing new initial particles. The caller owns
     * the independent factual world and future search seeds. Policy components remain the same configured components, as in independent arena sessions.
     * Information compatibility is checked here; factual provenance is the experiment host's duty.
     */
    fun forkForFactualContinuation(root: ArgentumSearchWorld): SearchPolicySession {
        return SearchPolicySession(root, viewer, knownDecks, parameters, opponentPolicy, gameId,
            rolloutPolicy, rolloutOpponentPolicy, valueSource, beliefProposalAuditSink,
            directRootSelectionPolicy, searchPrior, this)
    }

    val latestBeliefDiagnostics: BeliefDiagnostics get() = belief.latestDiagnostics
    /** Host admission includes progressive widening and an optional wider search prior. */
    val decisionView: org.mtgallium.agent.infoset.core.MenuRequest
        get() = parameters.decisionView(searchPrior,
            widen = directRootSelectionPolicy == null)
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

    fun beliefBatch(actual: ArgentumSearchWorld? = null): ParticleSet<Weighted<SearchWorld>> {
        actual?.let { belief.synchronize(it, acceptedDecisionCount) }
        return profiledBeliefBatch()
    }

    private fun profiledBeliefBatch(): ParticleSet<Weighted<SearchWorld>> =
        belief.snapshot().hypotheses.materialize().batch

    fun select(
        world: ArgentumSearchWorld,
        actor: String,
        searchSeed: Long,
    ): RootActionSelection {
        require(actor == viewer) { "Policy session for $viewer cannot choose for $actor" }
        val context = world.decisionContext(decisionView)
        return RootActionSelector(parameters.singletonSelection.enabled, directRootSelectionPolicy).select(
            context, searchSeed,
        ) {
            // Sequential particles are still advanced after every accepted action, but the expensive
            // all-particle digest audit is needed only when its result can affect an actual search.
            belief.synchronize(world, acceptedDecisionCount)
            val result = search.search(
                rootPlayer = actor,
                belief = profiledBeliefBatch(),
                searchSeed = searchSeed,
            )
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
