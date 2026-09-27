package org.mtgallium.agent.value

import kotlin.test.*

class ModelRuntimeIsolationTest {
    @Test fun `frozen model runtime needs neither planner engine nor evidence storage`() {
        for (name in listOf("org.mtgallium.agent.infoset.planning.InformationSetSearch",
            "org.mtgallium.agent.infoset.planning.SearchWorld", "org.mtgallium.agent.infoset.planning.ParticleBelief",
            "org.mtgallium.agent.infoset.planning.LeafValueSource", "org.mtgallium.agent.infoset.planning.RootActionSelector",
            "org.mtgallium.agent.infoset.planning.RootActionSelection", "com.wingedsheep.engine.state.GameState")) {
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        }
        val payload = LinearWeights(
             bias = 0.0, weights = mapOf("state/synthetic" to .25))
        val model = LinearValueEvaluator(payload)
        assertEquals(model.configurationId, LinearValueEvaluator.load(model.model.toJson()).configurationId)

    }
}
