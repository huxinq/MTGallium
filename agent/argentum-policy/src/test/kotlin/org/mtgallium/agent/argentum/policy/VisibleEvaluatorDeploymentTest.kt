package org.mtgallium.agent.argentum.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.monored.*

class VisibleEvaluatorDeploymentTest {
    @Test fun `leaf binding preserves default and configured visible evaluator identities`() {
        assertEquals(MonoRedInformationEvaluator.id,
            LeafValueSource.Information(MonoRedInformationEvaluator).invokedEvaluatorConfigurationId)
        val defaults = MonoRedVisibleEvaluatorConfig()
        val changes = listOf(defaults, defaults.copy(life = .2), defaults.copy(hand = .5), defaults.copy(power = 2.0),
            defaults.copy(toughness = .8), defaults.copy(haste = .6), defaults.copy(landTail = .2),
            defaults.copy(tanhScale = 6.0), defaults.copy(landMarginals = defaults.landMarginals.reversed()),
            defaults.copy(landMarginals = defaults.landMarginals + .1)) + defaults.landMarginals.indices.map { index ->
                defaults.copy(landMarginals = defaults.landMarginals.mapIndexed { i, value -> if (i == index) value + .1 else value })
            }
        changes.forEach { config -> assertEquals(config.configurationId,
            LeafValueSource.Information(ConfiguredMonoRedInformationEvaluator(config)).invokedEvaluatorConfigurationId) }
    }
}
