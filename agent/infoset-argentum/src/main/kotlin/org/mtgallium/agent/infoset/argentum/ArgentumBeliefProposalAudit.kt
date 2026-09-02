package org.mtgallium.agent.infoset.argentum

/** Exact production limits retained by the remembered-fact sampling repair. */
const val ARGENTUM_KNOWN_DECK_MAX_PROPOSAL_ATTEMPTS_PER_PARTICLE = 512
const val ARGENTUM_CONDITIONAL_REJUVENATION_MAX_PROPOSAL_ATTEMPTS = 512

enum class ArgentumBeliefProposalSource {
    KNOWN_DECK_CONSTRUCTION,
    CONDITIONAL_REJUVENATION,
}

enum class ArgentumBeliefProposalDisposition {
    ACCEPTED,
    REJECTED_BY_REPRESENTED_FACT_SUPPORT,
    REJECTED_BY_ENGINE_SAMPLER,
}

/**
 * Redacted, read-only observation of one hidden-world proposal. The default sink is a no-op and
 * the sink is never consulted when the sampler decides whether to accept a world.
 */
data class ArgentumBeliefProposalAudit(
    val source: ArgentumBeliefProposalSource,
    val context: String,
    val attemptIndex: Int,
    val proposalSeed: Long,
    val disposition: ArgentumBeliefProposalDisposition,
    val redactedReasons: List<String> = emptyList(),
)

fun interface ArgentumBeliefProposalAuditSink {
    fun record(event: ArgentumBeliefProposalAudit)

    companion object {
        val NONE = ArgentumBeliefProposalAuditSink { }
    }
}
