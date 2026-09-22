package org.mtgallium.agent.infoset.core

/** A compatible enlargement, not a replacement action universe or another observation. */
class AdmittedMenuRefinement private constructor(val expansion: PolicyExpansion, val addedChoices: List<SemanticChoice>) {
    companion object {
        fun admit(previous: PolicyExpansion, next: PolicyExpansion): AdmittedMenuRefinement {
            require(previous.proposalVersion == next.proposalVersion && previous.proposalSeed == next.proposalSeed) {
                "Menu refinement changes its proposal contract or runtime seed"
            }
            val bySignature = next.candidates.associateBy { it.signature }
            require(previous.candidates.all { old -> bySignature[old.signature]?.let {
                it.kind == old.kind && it.operationFamily == old.operationFamily &&
                    it.actionIntent == old.actionIntent && it.canonicalPayload == old.canonicalPayload
            } == true }) { "A refinement must retain every previously admitted action and its meaning" }
            require(!previous.isExhaustive || next.isExhaustive && next.candidates.size == previous.candidates.size)
            require(!previous.isProfileExhaustive || next.isProfileExhaustive && next.candidates.size == previous.candidates.size)
            val frozen = next.semanticSnapshot()
            val previousSignatures = previous.candidates.mapTo(hashSetOf()) { it.signature }
            return AdmittedMenuRefinement(frozen, snapshotList(frozen.candidates.filter { it.signature !in previousSignatures }))
        }
    }
}
