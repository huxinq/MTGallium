package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.event.DelayedTriggeredAbility
import com.wingedsheep.engine.mechanics.layers.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.*
import com.wingedsheep.engine.state.components.player.*
import com.wingedsheep.engine.state.components.battlefield.NotedCreatureTypesComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.DrawCardsEffect
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Diagnostic witnesses for omitted state, not a replacement observation implementation.
 * OBSERVATION_REQUIRE_COMPLETE=1 turns each equality witness into the desired failing contract.
 * Empty legal actions deliberately isolate the observation from possible indirect menu changes.
 */
class ObservationGapAuditTest {
    @TestFactory
    fun omittedState(): List<DynamicTest> {
        val registry = CardRegistry().apply { register(PortalSet.cards); register(PortalSet.basicLands) }
        val env = GameEnvironment.create(registry)
        env.reset(GameConfig(players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 10, "Raging Goblin" to 10)),
            PlayerConfig("Bob", Deck.of("Mountain" to 10, "Raging Goblin" to 10))),
            seed = 77L, skipMulligans = true, startingPlayerIndex = 0))
        val player = env.playerIds[0]
        val source = env.state.getHand(player).first { env.state.getEntity(it)!!.get<CardComponent>()!!.name == "Raging Goblin" }
        val state = env.state.moveToZone(source, ZoneKey(player, Zone.HAND), ZoneKey(player, Zone.BATTLEFIELD))
        val aliases = env.playerIds.mapIndexed { index, id -> id to "p$index" }.toMap()
        fun view(s: GameState): String {
            val gym = ObservationBuilder(registry).build(s, player, emptyList()).observation as TrainingObservation
            return PlayerObservationProjector().project(gym, aliases,
                ArgentumPolicyRuntimeProjector.project(s, player, registry, gym), s.pendingDecision)
                .observation.observationDigest
        }
        val pairs = mutableListOf<Triple<String, GameState, GameState>>()
        fun gap(name: String, changed: GameState) { pairs += Triple(name, state, changed) }
        gap("spellsCastThisTurn", state.copy(spellsCastThisTurn = 3))
        gap("playerSpellsCastThisTurn", state.copy(playerSpellsCastThisTurn = mapOf(player to 3)))
        gap("lastCardDrawnThisTurnByPlayer (owner)", state.copy(lastCardDrawnThisTurnByPlayer = mapOf(player to state.getHand(player).first())))
        gap("dayNight", state.copy(dayNight = DayNight.NIGHT))
        gap("previousTurnActivePlayerId", state.copy(previousTurnActivePlayerId = player))
        gap("previousTurnActiveTeamSpellCounts", state.copy(previousTurnActiveTeamSpellCounts = mapOf(player to 2)))
        gap("spellWarpedThisTurn", state.copy(spellWarpedThisTurn = true))
        gap("damageCantBePreventedThisTurn", state.copy(damageCantBePreventedThisTurn = true))
        gap("powerUpRestrictedTurns", state.copy(powerUpRestrictedTurns = setOf(state.turnNumber + 1)))
        gap("nonlandPermanentLeftBattlefieldThisTurn", state.copy(nonlandPermanentLeftBattlefieldThisTurn = true))
        gap("permanentsSacrificedThisTurn", state.copy(permanentsSacrificedThisTurn = 2))
        gap("playersWhoCommittedCrimeThisTurn", state.copy(playersWhoCommittedCrimeThisTurn = setOf(player)))
        gap("lastCastSpellColors", state.copy(lastCastSpellColors = setOf(Color.BLUE)))
        gap("drawStepStartDrawCountByPlayer", state.copy(drawStepStartDrawCountByPlayer = mapOf(player to 2)))
        gap("pendingSpellCopies", state.copy(pendingSpellCopies = listOf(PendingSpellCopy(player, 2, source, "Public spell"))))
        gap("pendingUncounterableSpells", state.copy(pendingUncounterableSpells = listOf(
            PendingUncounterableSpell(player, sourceId = source, sourceName = "Public spell"))))
        gap("pendingNextSpellAffinities", state.copy(pendingNextSpellAffinities = listOf(
            PendingNextSpellAffinity(player, GameObjectFilter.Any, CardType.ARTIFACT, source, "Public spell"))))
        gap("pendingFreeCastSpells", state.copy(pendingFreeCastSpells = listOf(
            PendingFreeCastSpell(player, GameObjectFilter.Any, source, "Public spell"))))
        gap("turnSpellCostReductions", state.copy(turnSpellCostReductions = listOf(
            TurnSpellCostReduction(player, GameObjectFilter.Any, 2, source, "Public spell"))))
        gap("commanderDamage", state.copy(commanderDamage = listOf(CommanderDamageEntry(source, env.playerIds[1], 4))))
        gap("delayedTriggers", state.copy(delayedTriggers = listOf(DelayedTriggeredAbility(
            "audit-trigger", DrawCardsEffect(1), Step.END, source, "Public spell", player))))
        val components: List<Component> = listOf(
            PlayerCitysBlessingComponent, PlayerEnduringStoryComponent, TheRingComponent(2),
            PlayerNoMaximumHandSizeComponent, PlayerMaximumHandSizeReductionComponent(3),
            SkipNextTurnComponent(1), SkipCombatPhasesComponent, SkipDrawStepComponent,
            AdditionalPhasesComponent(listOf(QueuedPhase(ExtraPhaseKind.COMBAT))),
            AdditionalUpkeepStepsComponent(2), AdditionalEndStepsComponent(2),
            PlayerTurnsTakenComponent(3), CardsLeftGraveyardThisTurnComponent(2),
            CardsPutIntoExileThisTurnComponent(2), PermanentsSacrificedThisTurnComponent(2),
            LifeGainedAmountThisTurnComponent(3), LifeLostAmountThisTurnComponent(3),
            SacrificedFoodThisTurnComponent, SacrificedArtifactThisTurnComponent,
            WasDealtCombatDamageThisTurnComponent, WasDealtCombatDamageByLegendaryCreatureThisTurnComponent,
        )
        for (component in components) gap(component::class.simpleName!!,
            state.updateEntity(player) { ComponentContainer(it.all().associateBy { c -> c.javaClass } + (component.javaClass to component)) })
        pairs += Triple("mana provenance",
            state.updateEntity(player) { it.with(ManaPoolComponent(red = 1)) },
            state.updateEntity(player) { it.with(ManaPoolComponent(red = 1, manaBySource = mapOf(source to 1))) })
        val floating = ActiveFloatingEffect(EntityId("audit-effect"), FloatingEffectData(
            Layer.POWER_TOUGHNESS, Sublayer.MODIFICATIONS,
            SerializableModification.ModifyPowerToughness(1, 0), setOf(source)),
            Duration.EndOfTurn, source, "Public pump", player, 1)
        pairs += Triple("floating effect duration", state.copy(floatingEffects = listOf(floating)),
            state.copy(floatingEffects = listOf(floating.copy(duration = Duration.Permanent))))
        pairs += Triple("base characteristics versus floating modification",
            state.copy(floatingEffects = listOf(floating)),
            state.updateEntity(source) { it.with(it.get<CardComponent>()!!.copy(baseStats = CreatureStats(2, 1))) })
        pairs += Triple("secret noted creature type (chooser)",
            state.updateEntity(source) { it.with(NotedCreatureTypesComponent(setOf("Elf"), player)) },
            state.updateEntity(source) { it.with(NotedCreatureTypesComponent(setOf("Goblin"), player)) })
        gap("public noted creature type", state.updateEntity(source) {
            it.with(NotedCreatureTypesComponent(setOf("Elf"))) })
        // Sensitivity control: this harness sees a current P/T change.
        assertNotEquals(view(state), view(state.copy(floatingEffects = listOf(floating))))
        return pairs.map { (name, before, after) -> DynamicTest.dynamicTest(name) {
            assertNotEquals(before, after, "Witness must change engine state")
            val a = view(before); val b = view(after)
            println("OBSERVATION_GAP $name $a $b")
            if (System.getenv("OBSERVATION_REQUIRE_COMPLETE") == "1") assertNotEquals(a, b, name)
            else assertEquals(a, b, "Audit no longer reproduces: $name")
        } }
    }
}
