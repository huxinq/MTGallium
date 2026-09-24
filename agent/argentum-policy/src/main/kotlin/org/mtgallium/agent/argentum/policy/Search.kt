package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.infoset.core.ActionDistributionModel
import org.mtgallium.agent.infoset.core.ActionSelector
import org.mtgallium.agent.infoset.core.InformationSetSearch
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.monored.MonoRedInformationEvaluator

/** Search with Argentum rollout policies and Mono-Red evaluator defaults. */
fun createSearch(
    config: InformationSetSearchConfig,
    opponentPolicy: ActionDistributionModel = defaultMonoRedOpponentPolicy(),
    rolloutPolicy: ActionSelector = PolicyDefaults.rootRolloutPolicy(),
    rolloutOpponentPolicy: ActionSelector = PolicyDefaults.opponentRolloutPolicy(),
    valueSource: LeafValueSource = LeafValueSource.Information(MonoRedInformationEvaluator),
    searchPrior: org.mtgallium.agent.infoset.core.SearchPrior? = null,
): InformationSetSearch = InformationSetSearch(
    config = config,
    opponentPolicy = opponentPolicy,
    rolloutPolicy = rolloutPolicy,
    rolloutOpponentPolicy = rolloutOpponentPolicy,
    valueSource = valueSource,
    searchPrior = searchPrior,
)
