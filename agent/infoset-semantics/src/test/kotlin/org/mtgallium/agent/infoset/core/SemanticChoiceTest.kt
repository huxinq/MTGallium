package org.mtgallium.agent.infoset.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SemanticChoiceTest {
    @Test
    fun `signature binds both operation family and canonical payload`() {
        val payload = buildJsonObject { put("type", JsonPrimitive("fixture")) }
        val pass = choice(SemanticOperationFamily.PASS_PRIORITY, payload)
        val mana = choice(SemanticOperationFamily.MANA_ABILITY, payload)

        assertNotEquals(pass.signature, mana.signature)
        assertFailsWith<IllegalArgumentException> {
            pass.copy(operationFamily = SemanticOperationFamily.MANA_ABILITY)
        }
        assertFailsWith<IllegalArgumentException> {
            pass.copy(canonicalPayload = buildJsonObject { put("type", JsonPrimitive("changed")) })
        }
    }

    @Test
    fun `only an exhaustive literal singleton pass is compressible`() {
        val pass = choice(SemanticOperationFamily.PASS_PRIORITY)
        val mana = choice(SemanticOperationFamily.MANA_ABILITY)
        val spell = choice(SemanticOperationFamily.CAST_SPELL)

        assertEquals(pass, expansion(listOf(pass), exhaustive = true).exactSingletonPassOrNull())
        assertNull(expansion(listOf(pass), exhaustive = false).exactSingletonPassOrNull())
        assertNull(expansion(listOf(spell), exhaustive = true).exactSingletonPassOrNull())
        assertNull(expansion(listOf(pass, mana), exhaustive = true).exactSingletonPassOrNull())
    }

    @Test
    fun `profile singleton pass is distinct from rules forced pass`() {
        val pass = choice(SemanticOperationFamily.PASS_PRIORITY)
        val profiled = PolicyExpansion(
            candidates = listOf(pass),
            isExhaustive = false,
            estimatedCandidateCount = null,
            proposalVersion = "profile-singleton-test-v1",
            isProfileExhaustive = true,
            omissionReasons = setOf(PolicyExpansionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA),
        )

        assertNull(profiled.exactSingletonPassOrNull())
        assertEquals(pass, profiled.policySingletonPassOrNull())
    }

    private fun choice(
        family: SemanticOperationFamily,
        payload: kotlinx.serialization.json.JsonObject = buildJsonObject {
            put("type", JsonPrimitive(family.name))
        },
    ): SemanticChoice = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION,
        operationFamily = family,
        display = SemanticChoiceDisplay(family.name),
        canonicalPayload = payload,
    )

    private fun expansion(candidates: List<SemanticChoice>, exhaustive: Boolean) = PolicyExpansion(
        candidates = candidates,
        isExhaustive = exhaustive,
        estimatedCandidateCount = candidates.size.toLong(),
        proposalVersion = "semantic-choice-test-v1",
    )
}
