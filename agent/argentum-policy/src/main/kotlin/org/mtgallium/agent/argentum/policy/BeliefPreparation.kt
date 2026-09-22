package org.mtgallium.agent.argentum.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import org.mtgallium.agent.infoset.argentum.ArgentumBeliefProposalAuditSink
import org.mtgallium.agent.infoset.argentum.ArgentumHeuristicProfile
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*

/** An explicit diagnostic choice, independent of the represented history format. */
@Serializable
enum class ObservedBeliefConditioning { HISTORICAL_GROUP_SIGNATURE_V1, QUALIFIED_SUPPORTED_FAMILIES_V1 }

/** Inputs actually used by the shared belief tracker, independently of tree execution. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("org.mtgallium.agent.searchteacher.SearchTeacherBeliefConfiguration")
data class BeliefConfig(
    val particles: Int,
    val beliefMode: BeliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
    val beliefArchitecture: BeliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
    /** Absent preserves historical bytes and the host's representation-bound default. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val observedConditioning: ObservedBeliefConditioning? = null,
) {
    init { require(particles > 0) }
}

internal fun SearchPolicyConfig.beliefConfiguration() =
    BeliefConfig(particles, beliefMode, beliefArchitecture)

/** Replay and synchronize the existing belief lifecycle without allocating a search session. */
class BeliefPreparation(
    root: ArgentumSearchWorld,
    viewer: String,
    knownDecks: Map<String, Map<String, Int>>,
    configuration: BeliefConfig,
    opponentDistribution: ActionDistributionModel,
    privateChoiceSelector: ActionSelector,
    gameId: String,
    private val heuristicProfile: ArgentumHeuristicProfile = ArgentumHeuristicProfile.PRODUCTION,
    proposalAuditSink: ArgentumBeliefProposalAuditSink = ArgentumBeliefProposalAuditSink.NONE,
) {
    /** Legacy configuration binds the same model to likelihood and private selection. */
    constructor(root: ArgentumSearchWorld, viewer: String, knownDecks: Map<String, Map<String, Int>>,
        configuration: BeliefConfig, opponentPolicy: OpponentPolicy, gameId: String,
        heuristicProfile: ArgentumHeuristicProfile = ArgentumHeuristicProfile.PRODUCTION,
        proposalAuditSink: ArgentumBeliefProposalAuditSink = ArgentumBeliefProposalAuditSink.NONE) :
        this(root, viewer, knownDecks, configuration, opponentPolicy, opponentPolicy, gameId,
            heuristicProfile, proposalAuditSink)

    private val belief = ArgentumParticleBeliefBackend(root, viewer, knownDecks, configuration,
        opponentDistribution, privateChoiceSelector, gameId, proposalAuditSink)
    private var acceptedDecisionCount = 0
    val lastObservedUpdate: ObservedBeliefUpdateEvidence? get() = belief.lastObservedUpdate

    fun observeAccepted(actual: ArgentumSearchWorld, actor: String, choice: SemanticChoice,
        decisionIndex: Int, privateToActor: Boolean,
        exactObservedAction: org.mtgallium.agent.infoset.argentum.ArgentumObservedActionCapture? = null) {
        belief.advance(actual, actor, choice, decisionIndex, privateToActor, exactObservedAction)
        acceptedDecisionCount = decisionIndex + 1
    }

    fun beliefSnapshot(actual: ArgentumSearchWorld? = null): BeliefSnapshot {
        actual?.let { belief.synchronize(it, acceptedDecisionCount) }
        return belief.snapshot()
    }

    fun beliefQueries(actual: ArgentumSearchWorld? = null): BeliefQueryView = beliefSnapshot(actual).queries

    fun beliefBatch(actual: ArgentumSearchWorld? = null): BeliefBatch<Weighted<SearchWorld>> {
        actual?.let { belief.synchronize(it, acceptedDecisionCount) }
        return belief.snapshot().hypotheses.materialize().batch.withHeuristicProfile(heuristicProfile)
    }
}

internal fun BeliefBatch<Weighted<SearchWorld>>.withHeuristicProfile(profile: ArgentumHeuristicProfile): BeliefBatch<Weighted<SearchWorld>> {
    if (profile == ArgentumHeuristicProfile.PRODUCTION) return this
    return copy(particles = particles.map { weighted ->
        val world = weighted.value as? ArgentumSearchWorld
            ?: error("Search policy belief particle is not an Argentum world")
        weighted.copy(value = world.forkWithHeuristicProfile(profile))
    })
}
