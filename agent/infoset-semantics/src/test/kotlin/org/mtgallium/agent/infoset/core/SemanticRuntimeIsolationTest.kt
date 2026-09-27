package org.mtgallium.agent.infoset.core

import kotlin.test.*
import kotlinx.serialization.json.*

class SemanticRuntimeIsolationTest {
    @Test fun `a direct policy executes with neither search worlds UCT particles nor engine classes`() {
        for (name in listOf("org.mtgallium.agent.infoset.planning.SearchWorld",
            "org.mtgallium.agent.infoset.planning.InformationSetSearch", "org.mtgallium.agent.infoset.planning.ParticleBelief",
            "org.mtgallium.agent.infoset.planning.RootActionSelector",
            "org.mtgallium.agent.infoset.planning.RootActionSelection",
            "org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld")) {
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        }
        val state = InformationState.capture(PlayerObservationSnapshot("p0", 1, "MAIN", "PRECOMBAT_MAIN", "p0", "p0",
            emptyList(), emptyList(), emptyList(), currentTurnStateComplete = true, pendingDecision = null, observationDigest = "authored"),
            emptyList(), HistoryHashChain.empty(), PlayerKnowledge.empty("p0"), false)
        val choices = listOf("a", "b").map { label -> SemanticChoice.create(SemanticChoiceKind.ACTION, SemanticOperationFamily.CAST_SPELL,
            display = SemanticChoiceDisplay(label), canonicalPayload = buildJsonObject { put("choice", label) }) }
        val context = DecisionContext.capture("p0", ActionMenu(choices, true, 2, "authored"), { state })
        val policy = object : DecisionPolicy {
            override val configurationId = "authored-direct-policy-v1"
            override fun choose(context: DecisionContext, decisionSeed: Long): SemanticChoice {
                assertEquals("p0", context.site().epistemic.viewerId)
                return context.menu.candidates.last()
            }
        }
        assertEquals(choices.last(), policy.choose(context, 123L))
        assertEquals(choices, context.site().menu.candidates)
        val stochastic = UniformOpponentPolicy.distribution(context, 123L)
        assertEquals(listOf(.5, .5), stochastic.entries.map { it.probability })
    }


}
