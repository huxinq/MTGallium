package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.monored.*
import org.mtgallium.agent.infoset.core.*

internal fun modelTestSearch(
    config: InformationSetSearchConfig,
    opponentPolicy: ActionDistributionModel = UniformOpponentPolicy,
    rolloutPolicy: ActionSelector = UniformOpponentPolicy,
    rolloutOpponentPolicy: ActionSelector = UniformOpponentPolicy,
    valueSource: LeafValueSource,
): InformationSetSearch = InformationSetSearch(config = config, opponentPolicy = opponentPolicy,
    rolloutPolicy = rolloutPolicy, rolloutOpponentPolicy = rolloutOpponentPolicy,
    valueSource = valueSource)
