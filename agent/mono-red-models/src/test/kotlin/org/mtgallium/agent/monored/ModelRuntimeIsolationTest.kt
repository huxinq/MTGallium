package org.mtgallium.agent.monored

import kotlin.test.*

class ModelRuntimeIsolationTest {
    @Test fun `frozen model runtime needs neither planner engine nor evidence storage`() {
        for (name in listOf("org.mtgallium.agent.infoset.core.InformationSetSearch",
            "org.mtgallium.agent.infoset.core.SearchWorld", "org.mtgallium.agent.infoset.core.ParticleBelief",
            "org.mtgallium.agent.infoset.core.LeafValueSource", "org.mtgallium.agent.infoset.core.RootActionSelector",
            "org.mtgallium.agent.infoset.core.RootActionSelection", "com.wingedsheep.engine.state.GameState")) {
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        }
        val payload = LinearWeights(
             bias = 0.0, weights = mapOf("state/synthetic" to .25))
        val model = LinearValueEvaluator(payload)
        assertEquals(model.configurationId, LinearValueEvaluator.load(model.model.toJson()).configurationId)

    }
}
