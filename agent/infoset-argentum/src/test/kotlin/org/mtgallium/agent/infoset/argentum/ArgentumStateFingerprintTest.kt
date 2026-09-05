package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.event.DelayedTriggeredAbility
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.EffectContinuation
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.GainLifeEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArgentumStateFingerprintTest {
    private val sourceId = EntityId.of("source")
    private val controllerId = EntityId.of("controller")

    @Test
    fun `fresh delayed-trigger routing ids do not change authoritative fingerprint`() {
        val left = GameState(delayedTriggers = listOf(delayedTrigger("generated-id-left")))
        val right = GameState(delayedTriggers = listOf(delayedTrigger("generated-id-right")))

        assertEquals(ArgentumStateFingerprint.of(left), ArgentumStateFingerprint.of(right))
        assertEquals(
            ArgentumStateFingerprint.componentDigests(left).getValue("delayedTriggers"),
            ArgentumStateFingerprint.componentDigests(right).getValue("delayedTriggers"),
        )
    }

    @Test
    fun `delayed-trigger semantics and id relationships remain fingerprinted`() {
        val baseline = GameState(
            delayedTriggers = listOf(
                delayedTrigger("first-generated-id"),
                delayedTrigger("second-generated-id"),
            ),
        )
        val duplicateIdentity = baseline.copy(
            delayedTriggers = listOf(
                delayedTrigger("same-generated-id"),
                delayedTrigger("same-generated-id"),
            ),
        )
        val differentEffect = baseline.copy(
            delayedTriggers = listOf(
                delayedTrigger("third-generated-id", life = 2),
                delayedTrigger("fourth-generated-id"),
            ),
        )

        assertNotEquals(ArgentumStateFingerprint.of(baseline), ArgentumStateFingerprint.of(duplicateIdentity))
        assertNotEquals(ArgentumStateFingerprint.of(baseline), ArgentumStateFingerprint.of(differentEffect))
    }

    @Test
    fun `decision routing spelling is ignored but pending-to-continuation relationship is preserved`() {
        val left = stateWaitingForDecision("left-generated-id", "left-generated-id")
        val equivalent = stateWaitingForDecision("right-generated-id", "right-generated-id")
        val brokenRouting = stateWaitingForDecision("pending-generated-id", "different-continuation-id")

        assertEquals(ArgentumStateFingerprint.of(left), ArgentumStateFingerprint.of(equivalent))
        assertNotEquals(ArgentumStateFingerprint.of(left), ArgentumStateFingerprint.of(brokenRouting))
        assertTrue(ArgentumStateFingerprint.routingNormalizedEquals(left, equivalent))
        assertEquals(null, ArgentumStateFingerprint.firstRoutingNormalizedDifference(left, equivalent))
        assertFalse(ArgentumStateFingerprint.routingNormalizedEquals(left, brokenRouting))
        assertEquals(
            "/continuationStack/0/decisionId",
            ArgentumStateFingerprint.firstRoutingNormalizedDifference(left, brokenRouting)?.path,
        )
        assertFalse(ArgentumStateFingerprint.routingNormalizedEquals(left, equivalent.copy(turnNumber = 1)))
        assertEquals(
            ArgentumStateDifference("/turnNumber", "0", "1"),
            ArgentumStateFingerprint.firstRoutingNormalizedDifference(left, equivalent.copy(turnNumber = 1)),
        )
    }

    @Test
    fun `routing normalized equality ignores map insertion order`() {
        val left = GameState(
            playerSpellsCastThisTurn = linkedMapOf(sourceId to 1, controllerId to 2),
        )
        val right = GameState(
            playerSpellsCastThisTurn = linkedMapOf(controllerId to 2, sourceId to 1),
        )

        assertEquals(left, right)
        assertTrue(ArgentumStateFingerprint.routingNormalizedEquals(left, right))
    }

    private fun delayedTrigger(id: String, life: Int = 1) = DelayedTriggeredAbility(
        id = id,
        effect = GainLifeEffect(life),
        fireAtStep = Step.END,
        sourceId = sourceId,
        sourceName = "Source",
        controllerId = controllerId,
    )

    private fun stateWaitingForDecision(pendingId: String, continuationId: String) = GameState(
        pendingDecision = YesNoDecision(
            id = pendingId,
            playerId = controllerId,
            prompt = "Continue?",
            context = DecisionContext(),
        ),
        continuationStack = listOf(
            EffectContinuation(
                decisionId = continuationId,
                remainingEffects = emptyList(),
                effectContext = EffectContext(sourceId = sourceId, controllerId = controllerId),
            ),
        ),
    )
}
