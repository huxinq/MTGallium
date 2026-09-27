package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.WarpedComponent
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.eoe.cards.NovaHellkite
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V4
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

class RuntimeSemanticChoiceIdentityTest {
    private val registry = CardRegistry().apply {
        register(NovaHellkite)
        register(PortalSet.basicLands)
    }
    private val deck = mapOf("Mountain" to 16, "Nova Hellkite" to 4)

    @Test
    fun `every current policy card runtime fact splits otherwise identical semantic references`() {
        val env = environment()
        val viewer = env.playerIds[0]
        val observation = ObservationBuilder(registry).build(env.state, viewer, env.legalActions())
            .observation as TrainingObservation
        val identical = observation.zones.flatMap { it.cards }
            .filter { it.ownerId == viewer && it.name == "Mountain" }
            .take(2)
        assertEquals(2, identical.size)
        val first = identical[0].entityId
        val second = identical[1].entityId
        val projector = PlayerObservationProjector()

        val baseline = projector.project(observation)
        assertEquals(
            baseline.references.semanticReference(first),
            baseline.references.semanticReference(second),
        )

        val runtimeCases = mapOf(
            "isWarped" to ArgentumPolicyCardRuntime(isWarped = true),
            "isWarpExiled" to ArgentumPolicyCardRuntime(isWarpExiled = true),
            "playableFromExile" to ArgentumPolicyCardRuntime(playableFromExile = true),
            "hasActivatedAbilityThisTurn" to ArgentumPolicyCardRuntime(hasActivatedAbilityThisTurn = true),
        )
        runtimeCases.forEach { (field, runtime) ->
            val projected = projector.project(
                observation,
                playerAliases = null,
                runtime = ArgentumPolicyRuntimeProjection(cards = mapOf(second to runtime)),
            )
            assertNotEquals(
                projected.references.semanticReference(first),
                projected.references.semanticReference(second),
                field,
            )
        }
    }

    private fun environment(): GameEnvironment = GameEnvironment.create(registry).also { environment ->
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                    PlayerConfig("Bob", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                ),
                seed = 23_023L,
                skipMulligans = true,
                startingPlayerIndex = 0,
            )
        )
    }
}
