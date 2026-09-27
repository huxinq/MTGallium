package org.mtgallium.agent.infoset.core

import kotlin.test.*
import kotlinx.serialization.json.*

class AdmittedMenuRefinementTest {
    @Test fun `retaining a signature does not authorize a change of action kind`() {
        val original = choice("original")
        val previous = ActionMenu(listOf(original), false, 2, "fixture-proposal")
        val changed = original.copy(kind = SemanticChoiceKind.DECISION)
        assertEquals(original.signature, changed.signature)
        val next = ActionMenu(listOf(changed, choice("additional")), true, 2, "fixture-proposal")
        assertFailsWith<IllegalArgumentException> { MenuWidening.admit(previous, next) }
    }

    @Test fun `compatible widening preserves meanings while allowing presentation changes`() {
        val original = choice("original")
        val previous = ActionMenu(listOf(original), false, 2, "fixture-proposal")
        val additional = choice("additional")
        val next = ActionMenu(listOf(original.copy(display = SemanticChoiceDisplay("relabelled")), additional),
            true, 2, "fixture-proposal")
        val refined = MenuWidening.admit(previous, next)
        assertEquals(listOf(additional), refined.addedChoices)
        assertEquals(next, refined.menu)
    }

    private fun choice(label: String) = SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.OTHER, display = SemanticChoiceDisplay(label),
        canonicalPayload = buildJsonObject { put("syntheticChoice", label) })
}
