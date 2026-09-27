package org.mtgallium.agent.infoset.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class RootActionSelectionTest {
    @Test fun `unsearched variants retain their choice`() {
        val choice = choice("Pass")
        for (selection in listOf(
            RootActionSelection.RulesForcedPass(choice),
            RootActionSelection.PolicySingletonAction(choice),
            RootActionSelection.DirectPolicyAction(choice),
        )) {
            assertIs<RootActionSelection.Unsearched>(selection)
            assertSame(choice, selection.choice)
        }
    }

    @Test fun `searched choice is derived from the retained result including after copying`() {
        val result = InformationSetSearchResult(
            chosen = choice("First"), rootValue = 0.25, candidates = emptyList(), candidateSettlementCounts = emptyMap(),
            diagnostics = InformationSetSearchDiagnostics(simulations = 0, particles = 1, nodes = 1, maximumDepth = 0,
                exhaustiveNodes = 1, nonExhaustiveNodes = 0, wideningEvents = 0, opponentModelId = "selection-test",
                leaf = LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE).diagnostic()),
        )
        val selection: RootActionSelection = RootActionSelection.Searched(result)
        assertIs<RootActionSelection.Searched>(selection)
        assertSame(result, selection.search)
        assertSame(result.chosen, selection.choice)
        val replacement = result.copy(chosen = choice("Second"))
        val copied = selection.copy(search = replacement)
        assertSame(replacement, copied.search)
        assertSame(replacement.chosen, copied.choice)
        assertSame(result.chosen, selection.choice)
    }

    @Test fun `full diagnostics retain old opaque fields while default guidance stays absent`() {
        val serializer = InformationSetSearchDiagnostics.serializer()
        val defaults = InformationSetSearchDiagnostics(
            simulations = 0, particles = 1, nodes = 1, maximumDepth = 0,
            exhaustiveNodes = 1, nonExhaustiveNodes = 0, wideningEvents = 0,
            opponentModelId = "diagnostic-wire-test",
            leaf = LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE).diagnostic(),
        )
        val defaultJson = PolicyJson.format.encodeToJsonElement(serializer, defaults) as JsonObject
        assertFalse("rootSelectionGuidance" in defaultJson)
        assertEquals(JsonNull, defaultJson["wallClockBudgetMillis"])
        assertEquals(defaults, PolicyJson.format.decodeFromJsonElement(serializer, defaultJson))

        val oldGuidance = JsonObject(mapOf(
            "configurationId" to JsonPrimitive("legacy-profile"),
            "informationStateDigest" to JsonPrimitive("legacy-digest"),
            "scores" to JsonObject(mapOf("choice-signature" to JsonPrimitive(0.25))),
            "rule" to JsonPrimitive("root-progressive-bias-unit-weight-v1"),
        ))
        val oldLeaf = JsonObject(mapOf(
            "stateSource" to JsonPrimitive("CURRENT_SAMPLED_WORLD"),
            "cutoff" to JsonPrimitive("EVALUATE"),
            "unresolved" to JsonPrimitive("BACK_UP_NEUTRAL"),
        ))
        val historical = JsonObject(defaultJson + mapOf(
            "leaf" to oldLeaf,
            "rootSelectionGuidance" to oldGuidance,
            "wallClockBudgetMillis" to JsonPrimitive(250L),
        ))
        val decoded = PolicyJson.format.decodeFromJsonElement(serializer, historical)
        assertEquals("CURRENT_SAMPLED_WORLD", decoded.leaf.stateSource)
        assertEquals("BACK_UP_NEUTRAL", decoded.leaf.unresolved)
        assertEquals(oldGuidance, decoded.rootSelectionGuidance)
        assertEquals(250L, decoded.wallClockBudgetMillis)
        assertEquals(historical, PolicyJson.format.encodeToJsonElement(serializer, decoded))

        val withoutBudget = JsonObject(historical + ("wallClockBudgetMillis" to JsonNull))
        assertNull(PolicyJson.format.decodeFromJsonElement(serializer, withoutBudget).wallClockBudgetMillis)
    }

    private fun choice(label: String) = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION, operationFamily = SemanticOperationFamily.PASS_PRIORITY,
        display = SemanticChoiceDisplay(label), canonicalPayload = JsonObject(
            mapOf("action" to kotlinx.serialization.json.JsonPrimitive(label))),
    )
}
