package org.mtgallium.agent.argentum.policy

import kotlin.test.*
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.monored.*

class FrozenModelCompositionTest {
    @Test fun `host binds actual model configuration into unchanged behavior vocabulary`() {
        fun model(weight: Double) = LinearValueEvaluator(
            LinearWeights(weights = mapOf("state/synthetic" to weight)))
        val parameters = LivePolicyConfig(leaf = LeafEvaluationConfig(
            LeafStateSource.CURRENT_INFORMATION_STATE,
            RolloutCutoff.EVALUATE,
            UnresolvedLeafHandling.EVALUATE,
        )).policyParameters()
        val decks = mapOf("p0" to mapOf("Mountain" to 20, "Shock" to 4), "p1" to mapOf("Mountain" to 20, "Shock" to 4))
        val base = model(.25)
        val specification = parameters.behaviorSpecification(
            decks, defaultMonoRedOpponentPolicy(), valueSource = LeafValueSource.Information(base))
        assertEquals("root-player-policy-information-v1", specification.evaluator.valueSource)
        assertEquals(base.configurationId, specification.evaluator.evaluatorConfigurationId)
        assertNotEquals(
            parameters.policyIdentity(decks, defaultMonoRedOpponentPolicy(), valueSource = LeafValueSource.Information(base)),
            parameters.policyIdentity(decks, defaultMonoRedOpponentPolicy(), valueSource = LeafValueSource.Information(model(.5))),
        )
    }
    private fun identity(name: String) = "$name-sha256:${"a".repeat(64)}"
}
