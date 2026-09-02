package org.mtgallium.agent.searchteacher

import org.mtgallium.agent.infoset.core.InformationSetSearch
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.InformationSetSearchReuseConfig
import org.mtgallium.agent.infoset.core.InformationSetSearchSession
import org.mtgallium.agent.infoset.core.InformationStateEvaluator
import org.mtgallium.agent.infoset.core.OpponentPolicy

/** The single production composition root for generic search and Search Teacher policies. */
object SearchTeacherSearchFactory {
    fun create(
        config: InformationSetSearchConfig,
        opponentPolicy: OpponentPolicy = defaultMonoRedOpponentPolicy(),
        rolloutPolicy: OpponentPolicy = rootRolloutPolicy(),
        rolloutOpponentPolicy: OpponentPolicy = opponentRolloutPolicy(),
        informationEvaluator: InformationStateEvaluator? = null,
        reuseConfig: InformationSetSearchReuseConfig = InformationSetSearchReuseConfig.DISABLED,
    ): InformationSetSearch = InformationSetSearch(
        config = config,
        opponentPolicy = opponentPolicy,
        rolloutPolicy = rolloutPolicy,
        rolloutOpponentPolicy = rolloutOpponentPolicy,
        leafEvaluationStrategy = SearchTeacherEvaluatorRegistry.strategy(
            config.leaf.evaluator,
            informationEvaluator,
        ),
        reuseConfig = reuseConfig,
    )

    fun session(
        config: InformationSetSearchConfig,
        opponentPolicy: OpponentPolicy = defaultMonoRedOpponentPolicy(),
        rolloutPolicy: OpponentPolicy = rootRolloutPolicy(),
        rolloutOpponentPolicy: OpponentPolicy = opponentRolloutPolicy(),
        informationEvaluator: InformationStateEvaluator? = null,
        reuseConfig: InformationSetSearchReuseConfig,
    ): InformationSetSearchSession = InformationSetSearchSession(
        config = config,
        opponentPolicy = opponentPolicy,
        rolloutPolicy = rolloutPolicy,
        rolloutOpponentPolicy = rolloutOpponentPolicy,
        leafEvaluationStrategy = SearchTeacherEvaluatorRegistry.strategy(
            config.leaf.evaluator,
            informationEvaluator,
        ),
        reuseConfig = reuseConfig,
    )

    fun rootRolloutPolicy(): OpponentPolicy = DeterminizedArgentumHeuristicOpponentPolicy(
        id = "root-argentum-production-rollout-v2",
    )

    fun opponentRolloutPolicy(): OpponentPolicy = DeterminizedArgentumHeuristicOpponentPolicy(
        id = "opponent-argentum-production-rollout-v2",
    )
}
