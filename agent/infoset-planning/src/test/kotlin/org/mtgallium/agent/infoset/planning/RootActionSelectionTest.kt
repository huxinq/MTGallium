package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

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
                leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE).diagnostic()),
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

    private fun choice(label: String) = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION, operationFamily = SemanticOperationFamily.PASS_PRIORITY,
        display = SemanticChoiceDisplay(label), canonicalPayload = JsonObject(
            mapOf("action" to kotlinx.serialization.json.JsonPrimitive(label))),
    )
}
