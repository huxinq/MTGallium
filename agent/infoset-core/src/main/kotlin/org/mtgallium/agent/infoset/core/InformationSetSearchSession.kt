package org.mtgallium.agent.infoset.core

/** Stateful search lifecycle. Production callers compose it through their capability root. */
class InformationSetSearchSession(
    config: InformationSetSearchConfig,
    opponentPolicy: OpponentPolicy,
    rolloutPolicy: OpponentPolicy,
    rolloutOpponentPolicy: OpponentPolicy,
    leafEvaluationStrategy: LeafEvaluationStrategy,
    reuseConfig: InformationSetSearchReuseConfig,
) {
    private val delegate = InformationSetSearch(
        config = config,
        opponentPolicy = opponentPolicy,
        rolloutPolicy = rolloutPolicy,
        rolloutOpponentPolicy = rolloutOpponentPolicy,
        leafEvaluationStrategy = leafEvaluationStrategy,
        reuseConfig = reuseConfig,
    )

    fun search(
        rootPlayer: String,
        belief: BeliefBatch<Weighted<SearchWorld>>,
        searchSeed: Long,
        beliefContinuityEpoch: Long,
    ): InformationSetSearchResult = delegate.search(
        rootPlayer = rootPlayer,
        belief = belief,
        searchSeed = searchSeed,
        beliefContinuityEpoch = beliefContinuityEpoch,
    )

    fun invalidate() = delegate.invalidateReuse()
}
