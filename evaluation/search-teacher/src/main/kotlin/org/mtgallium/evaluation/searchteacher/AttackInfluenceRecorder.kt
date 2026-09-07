package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.*

@Serializable
internal data class AttackInfluenceEvent(val simulationIndex: Int, val depth: Int,
    val informationDigest: String, val menu: List<String>, val actual: String,
    val incumbent: String, val learned: String)

/** Same-context paired policy proposals. Shadow choices never advance or back up a world. */
internal class AttackInfluenceRecorder(private val actualPolicy: OpponentPolicy,
    private val incumbent: OpponentPolicy, private val learned: OpponentPolicy) :
    OpponentPolicy by actualPolicy, BoundedRolloutObserver {
    val events = mutableListOf<AttackInfluenceEvent>()
    override fun observeBoundedRollout(information: () -> PolicyInformationState,
        candidates: List<SemanticChoice>, complete: Boolean, choice: SemanticChoice,
        searchSeed: Long, simulationIndex: Int, depth: Int) {
        if (!attackKernelScope(candidates, complete)) return
        val state = information()
        fun shadow(policy: OpponentPolicy) = policy.selectForExpansion({ state }, candidates, complete,
            ComponentSeeds.derive(searchSeed, simulationIndex, depth, policy.id, "rollout"),
            ComponentSeeds.derive(searchSeed, simulationIndex, depth, "rollout-sample"))
            .also { require(it.diagnostic.replacement?.invalidatesEvidence != true) }.choice
        val control = shadow(incumbent)
        val candidate = shadow(learned)
        require(choice == if (actualPolicy.id == learned.id) candidate else control)
        events += AttackInfluenceEvent(simulationIndex, depth, state.informationStateDigest,
            candidates.map { it.signature }, choice.signature, control.signature, candidate.signature)
    }
}
