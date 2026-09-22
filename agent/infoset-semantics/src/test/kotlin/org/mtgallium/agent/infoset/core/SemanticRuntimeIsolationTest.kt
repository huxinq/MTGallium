package org.mtgallium.agent.infoset.core

import kotlin.test.*
import kotlinx.serialization.json.*

class SemanticRuntimeIsolationTest {
    @Test fun `a direct policy executes with neither search worlds UCT particles nor engine classes`() {
        for (name in listOf("org.mtgallium.agent.infoset.core.SearchWorld",
            "org.mtgallium.agent.infoset.core.InformationSetSearch", "org.mtgallium.agent.infoset.core.ParticleBelief",
            "org.mtgallium.agent.infoset.core.TerminalPolicyContinuationRunner", "org.mtgallium.agent.infoset.core.RootActionSelector",
            "org.mtgallium.agent.infoset.core.RootActionSelection",
            "org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld")) {
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        }
        val state = EpistemicState.capture(PlayerObservationSnapshot("p0", 1, "MAIN", "PRECOMBAT_MAIN", "p0", "p0",
            emptyList(), emptyList(), emptyList(), currentTurnStateComplete = true, pendingDecision = null, observationDigest = "authored"),
            emptyList(), PolicyHistoryCommitment.empty(), PolicyKnowledgeState.empty("p0"), false)
        val choices = listOf("a", "b").map { label -> SemanticChoice.create(SemanticChoiceKind.ACTION, SemanticOperationFamily.CAST_SPELL,
            display = SemanticChoiceDisplay(label), canonicalPayload = buildJsonObject { put("choice", label) }) }
        val context = DecisionSiteRequest.capture("p0", PolicyExpansion(choices, true, 2, "authored"), { state })
        val policy = object : DecisionPolicy {
            override val configurationId = "authored-direct-policy-v1"
            override fun choose(context: DecisionSiteRequest, decisionSeed: Long): SemanticChoice {
                assertEquals("p0", context.site().epistemic.perspectivePlayerId)
                return context.expansion.candidates.last()
            }
        }
        assertEquals(choices.last(), policy.choose(context, 123L))
        assertEquals(choices, context.site().expansion.candidates)
        val stochastic = UniformOpponentPolicy.distribution(context, 123L)
        assertEquals(listOf(.5, .5), stochastic.entries.map { it.probability })
    }

    @Test fun `persisted semantic descriptors retain their established names after physical module extraction`() {
        assertEquals("org.mtgallium.agent.infoset.core.SemanticChoice", SemanticChoice.serializer().descriptor.serialName)
        assertEquals("org.mtgallium.agent.infoset.core.SearchSettlementOrigin", SearchSettlementOrigin.serializer().descriptor.serialName)
    }
}
