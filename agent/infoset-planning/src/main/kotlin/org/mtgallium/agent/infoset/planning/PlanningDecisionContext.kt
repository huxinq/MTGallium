package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

/** Existing tree lookup equivalence, explicitly separate from the exact menu's decision-site digest. */
internal data class TreeNodeKey(val informationStateDigest: String, val actor: String?)

/** Lookup alone does not need a candidate index or a retained conformance owner. */
internal fun DecisionContext.planningKey(): TreeNodeKey =
    TreeNodeKey(information().informationStateDigest, actor)

/** Native admitted contexts authorize node conformance and widening; legacy lookup bytes stay unchanged. */
internal class PlanningDecisionContext(val initial: DecisionContext) {
    val key: TreeNodeKey by lazy { initial.planningKey() }
    // Signatures bind semantic payloads but deliberately exclude the ACTION/DECISION routing kind.
    private val initialKinds = initial.menu.candidates.associate { it.signature to it.kind }

    fun requireInitial(other: DecisionContext) {
        if (initial === other) return
        requireSameSource(other)
        val menu = other.menu
        val expected = initial.menu
        if (menu.proposalVersion != expected.proposalVersion || menu.proposalSeed != expected.proposalSeed ||
            menu.isExhaustive != expected.isExhaustive || menu.isProfileExhaustive != expected.isProfileExhaustive ||
            menu.omissionReasons != expected.omissionReasons || menu.estimatedCandidateCount != expected.estimatedCandidateCount ||
            menu.candidates.size != initialKinds.size || menu.candidates.any { initialKinds[it.signature] != it.kind }) {
            throw InformationSetConformanceException("One planning context produced incompatible initial candidate families")
        }
    }
    fun refine(previous: DecisionContext, next: DecisionContext): MenuWidening {
        requireSameSource(next)
        return MenuWidening.admit(previous.menu, next.menu)
    }
    private fun requireSameSource(other: DecisionContext) {
        if (initial === other) return
        if (initial.actor != other.actor || initial.sourceContractIdentity != other.sourceContractIdentity ||
            initial.view.admission != other.view.admission || initial.view.annotations != other.view.annotations ||
            initial.site().epistemic.epistemicDigest != other.site().epistemic.epistemicDigest) {
            throw InformationSetConformanceException("A planning context changed its actor, knowledge or admission contract")
        }
    }
}
