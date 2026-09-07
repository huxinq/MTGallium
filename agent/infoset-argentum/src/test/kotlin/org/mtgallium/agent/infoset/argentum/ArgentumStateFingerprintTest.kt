package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.event.DelayedTriggeredAbility
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.MayAbilityContinuation
import com.wingedsheep.engine.core.ReopenManaPaymentDecisionContinuation
import com.wingedsheep.engine.core.Suspension
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.core.engineSerializersModule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ObjectIdentity
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
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
    fun `suspension routing spelling is ignored while the question and answer remain fingerprinted`() {
        val left = stateWaitingForDecision("left-generated-id")
        val equivalent = stateWaitingForDecision("right-generated-id")
        val differentAnswer = stateWaitingForDecision("right-generated-id", life = 2)

        assertEquals(ArgentumStateFingerprint.of(left), ArgentumStateFingerprint.of(equivalent))
        assertTrue(ArgentumStateFingerprint.routingNormalizedEquals(left, equivalent))
        assertEquals(null, ArgentumStateFingerprint.firstRoutingNormalizedDifference(left, equivalent))
        assertFalse(ArgentumStateFingerprint.routingNormalizedEquals(left, differentAnswer))
        assertTrue(ArgentumStateFingerprint.firstRoutingNormalizedDifference(left, differentAnswer)!!
            .path.startsWith("/continuationStack/0/answer/"))
        assertNotEquals(ArgentumStateFingerprint.of(left), ArgentumStateFingerprint.of(
            stateWaitingForDecision("right-generated-id", prompt = "Another question?")))
        assertFalse(ArgentumStateFingerprint.routingNormalizedEquals(left, equivalent.copy(turnNumber = 1)))
        assertEquals(
            ArgentumStateDifference("/turnNumber", "0", "1"),
            ArgentumStateFingerprint.firstRoutingNormalizedDifference(left, equivalent.copy(turnNumber = 1)),
        )
        assertNotEquals(ArgentumStateFingerprint.of(left),
            ArgentumStateFingerprint.of(left.copy(nextRoutingId = left.nextRoutingId + 1)))
        assertNotEquals(ArgentumStateFingerprint.of(left),
            ArgentumStateFingerprint.of(left.copy(nextObjectGeneration = left.nextObjectGeneration + 1)))
        val stamped = left.copy(objectIdentities = mapOf(
            sourceId to ObjectIdentity(1, ZoneKey(controllerId, Zone.HAND))))
        assertNotEquals(ArgentumStateFingerprint.of(stamped), ArgentumStateFingerprint.of(stamped.copy(
            objectIdentities = mapOf(sourceId to ObjectIdentity(2, ZoneKey(controllerId, Zone.HAND))))))
    }

    @Test
    fun `nested saved suspension IDs normalize while distinct question relationships remain`() {
        fun nested(savedId: String, activeId: String, life: Int = 1): GameState {
            val saved = stateWaitingForDecision(savedId, life).continuationStack.single() as Suspension
            val active = stateWaitingForDecision(activeId)
            return active.copy(continuationStack = listOf(
                ReopenManaPaymentDecisionContinuation(saved), active.continuationStack.single()))
        }
        val left = nested("saved-left", "active-left")
        assertEquals(ArgentumStateFingerprint.of(left),
            ArgentumStateFingerprint.of(nested("saved-right", "active-right")))
        assertNotEquals(ArgentumStateFingerprint.of(left),
            ArgentumStateFingerprint.of(nested("duplicate", "duplicate")))
        assertNotEquals(ArgentumStateFingerprint.of(left),
            ArgentumStateFingerprint.of(nested("saved-right", "active-right", life = 2)))
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

    private fun stateWaitingForDecision(id: String, life: Int = 1, prompt: String = "Continue?"): GameState {
        val state = GameState().suspendForDecision(
            question = { routingId -> YesNoDecision(routingId, controllerId, prompt, DecisionContext()) },
            answer = MayAbilityContinuation(controllerId, "Source", GainLifeEffect(life), null,
                EffectContext(sourceId = sourceId, controllerId = controllerId)),
        ).state
        // Import an equivalent snapshot with another routing spelling without changing allocation.
        val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }
        val encoded = json.encodeToString(state)
        return json.decodeFromString<GameState>(encoded.replace(
            json.encodeToString(state.pendingDecision!!.id), json.encodeToString(id)))
    }
}
