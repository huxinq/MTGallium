package org.mtgallium.agent.infoset.core

/** Existing tree lookup equivalence, explicitly separate from the exact menu's decision-site digest. */
internal data class PlanningContextKey(val informationStateDigest: String, val actor: String?)

/** Lookup alone does not need a candidate index or a retained conformance owner. */
internal fun DecisionSiteRequest.planningKey(): PlanningContextKey =
    PlanningContextKey(information().informationStateDigest, actor)

/** Native admitted contexts authorize node conformance and widening; legacy lookup bytes stay unchanged. */
internal class PlanningDecisionContext(val initial: DecisionSiteRequest) {
    val key: PlanningContextKey by lazy { initial.planningKey() }
    // Signatures bind semantic payloads but deliberately exclude the ACTION/DECISION routing kind.
    private val initialKinds = initial.expansion.candidates.associate { it.signature to it.kind }

    fun requireInitial(other: DecisionSiteRequest) {
        if (initial === other) return
        requireSameSource(other)
        val expansion = other.expansion
        val expected = initial.expansion
        if (expansion.proposalVersion != expected.proposalVersion || expansion.proposalSeed != expected.proposalSeed ||
            expansion.isExhaustive != expected.isExhaustive || expansion.isProfileExhaustive != expected.isProfileExhaustive ||
            expansion.omissionReasons != expected.omissionReasons || expansion.estimatedCandidateCount != expected.estimatedCandidateCount ||
            expansion.candidates.size != initialKinds.size || expansion.candidates.any { initialKinds[it.signature] != it.kind }) {
            throw InformationSetConformanceException("One planning context produced incompatible initial candidate families")
        }
    }
    fun refine(previous: DecisionSiteRequest, next: DecisionSiteRequest): AdmittedMenuRefinement {
        requireSameSource(next)
        return AdmittedMenuRefinement.admit(previous.expansion, next.expansion)
    }
    private fun requireSameSource(other: DecisionSiteRequest) {
        if (initial === other) return
        if (initial.actor != other.actor || initial.sourceContractIdentity != other.sourceContractIdentity ||
            initial.view.admission != other.view.admission || initial.view.annotations != other.view.annotations ||
            initial.site().epistemic.epistemicDigest != other.site().epistemic.epistemicDigest) {
            throw InformationSetConformanceException("A planning context changed its actor, knowledge or admission contract")
        }
    }
}
