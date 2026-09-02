package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.event.SpeedAbilities
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.AbilityActivatedThisTurnComponent
import com.wingedsheep.engine.state.components.battlefield.TriggeredAbilityFiredThisTurnComponent
import com.wingedsheep.engine.state.components.battlefield.WarpedComponent
import com.wingedsheep.engine.state.components.identity.WarpExiledComponent
import com.wingedsheep.engine.state.components.player.LandDropsComponent
import com.wingedsheep.engine.state.components.player.LifeLostThisTurnComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.RedNoncombatDamageDealtThisTurnComponent
import com.wingedsheep.engine.state.permissions.hasMayPlayFor
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import org.mtgallium.agent.infoset.core.PolicyRestrictedMana

/** Public runtime facts omitted by Argentum's training observation but present in its client view. */
internal data class ArgentumPolicyRuntimeProjection(
    val cards: Map<EntityId, ArgentumPolicyCardRuntime> = emptyMap(),
    val players: Map<EntityId, ArgentumPolicyPlayerRuntime> = emptyMap(),
    val currentTurnComplete: Boolean = false,
) {
    companion object {
        val EMPTY = ArgentumPolicyRuntimeProjection()
    }
}

internal data class ArgentumPolicyCardRuntime(
    val isWarped: Boolean = false,
    val isWarpExiled: Boolean = false,
    val playableFromExile: Boolean = false,
    val hasActivatedAbilityThisTurn: Boolean = false,
)

internal data class ArgentumPolicyPlayerRuntime(
    val restrictedMana: List<PolicyRestrictedMana> = emptyList(),
    val noncreatureSpellsCast: Int = 0,
    val lostLife: Boolean = false,
    val speedIncreaseFired: Boolean = false,
    val redNoncombatDamageDealt: Int = 0,
    val landDropsRemaining: Int = 0,
)

/**
 * Reads full state only for objects already admitted by the masked observation. This is a trusted
 * adapter operation: it enriches those public objects with rules state and never chooses visibility.
 */
internal object ArgentumPolicyRuntimeProjector {
    fun project(
        state: GameState,
        viewer: EntityId,
        cardRegistry: CardRegistry,
        observation: TrainingObservation,
    ): ArgentumPolicyRuntimeProjection {
        val conditionEvaluator = ConditionEvaluator()
        val visibleCards = observation.zones.asSequence()
            .flatMap { it.cards.asSequence() }
            .associate { card ->
                val container = requireNotNull(state.getEntity(card.entityId)) {
                    "Visible card ${card.entityId} is absent from authoritative state"
                }
                val activated = container.get<AbilityActivatedThisTurnComponent>()
                card.entityId to ArgentumPolicyCardRuntime(
                    isWarped = card.zone == Zone.BATTLEFIELD && container.has<WarpedComponent>(),
                    isWarpExiled = card.zone == Zone.EXILE && container.has<WarpExiledComponent>(),
                    playableFromExile = card.zone == Zone.EXILE && state.hasMayPlayFor(
                        card.entityId,
                        viewer,
                        conditionEvaluator,
                        cardRegistry,
                    ),
                    hasActivatedAbilityThisTurn = activated?.let { tracker ->
                        tracker.abilityIds.isNotEmpty() || tracker.loyaltyActivationCount > 0 ||
                            tracker.activationCounts.values.any { it > 0 }
                    } == true,
                )
            }
        val players = observation.players.associate { player ->
            val container = requireNotNull(state.getEntity(player.id)) {
                "Observed player ${player.id} is absent from authoritative state"
            }
            val entries = container.get<ManaPoolComponent>()?.restrictedMana.orEmpty()
            val grouped = entries.groupingBy { entry ->
                RestrictedManaKey(
                    color = entry.color?.name,
                    spendRestriction = entry.restriction.description.ifBlank { "Any spend" },
                    spellRiders = entry.riders.map { it.description }.sorted(),
                    expiresAt = entry.expiry.name,
                )
            }.eachCount().entries
                .sortedWith(compareBy({ it.key.color.orEmpty() }, { it.key.spendRestriction }, { it.key.expiresAt }))
                .map { (key, count) ->
                    PolicyRestrictedMana(
                        color = key.color,
                        spendRestriction = key.spendRestriction,
                        spellRiders = key.spellRiders,
                        expiresAt = key.expiresAt,
                        count = count,
                    )
                }
            player.id to ArgentumPolicyPlayerRuntime(
                restrictedMana = grouped,
                noncreatureSpellsCast = state.spellsCastThisTurnByPlayer[player.id]
                    .orEmpty()
                    .count { !it.isFaceDown && !it.typeLine.isCreature },
                lostLife = container.has<LifeLostThisTurnComponent>(),
                speedIncreaseFired = container.get<TriggeredAbilityFiredThisTurnComponent>()
                    ?.hasFired(SpeedAbilities.INHERENT_SPEED_ABILITY_ID) == true,
                redNoncombatDamageDealt = container.get<RedNoncombatDamageDealtThisTurnComponent>()
                    ?.amount ?: 0,
                landDropsRemaining = (container.get<LandDropsComponent>() ?: LandDropsComponent()).remaining,
            )
        }
        return ArgentumPolicyRuntimeProjection(visibleCards, players, currentTurnComplete = true)
    }

    private data class RestrictedManaKey(
        val color: String?,
        val spendRestriction: String,
        val spellRiders: List<String>,
        val expiresAt: String,
    )
}
