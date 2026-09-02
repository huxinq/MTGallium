package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.event.SpeedAbilities
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.permanent.types.TransformEffectExecutor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.CastSpellRecord
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AbilityActivatedThisTurnComponent
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.TriggeredAbilityFiredThisTurnComponent
import com.wingedsheep.engine.state.components.battlefield.WarpedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.DoubleFacedComponent
import com.wingedsheep.engine.state.components.identity.WarpExiledComponent
import com.wingedsheep.engine.state.components.player.LandDropsComponent
import com.wingedsheep.engine.state.components.player.LifeLostThisTurnComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.PlayerSpeedComponent
import com.wingedsheep.engine.state.components.player.RedNoncombatDamageDealtThisTurnComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.player.RestrictedManaEntry
import com.wingedsheep.engine.state.permissions.MayPlayPermission
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.blb.cards.HiredClaw
import com.wingedsheep.mtg.sets.definitions.blb.cards.RockfaceVillage
import com.wingedsheep.mtg.sets.definitions.eoe.cards.NovaHellkite
import com.wingedsheep.mtg.sets.definitions.lci.cards.OjerAxonilDeepestMight
import com.wingedsheep.mtg.sets.definitions.otj.cards.MagebaneLizard
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.sth.cards.Shock
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import com.wingedsheep.sdk.scripting.effects.TransformEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.BoundedPolicyInput
import org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.PolicyAudience
import org.mtgallium.agent.infoset.core.PolicyAudienceScope
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyInformationStateDigest
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

/** Paired evidence for runtime distinctions reachable in the frozen Mono-Red main deck. */
class ReachableSemanticTrustTest {
    private val registry = CardRegistry().apply {
        register(listOf(HiredClaw, RockfaceVillage, NovaHellkite, OjerAxonilDeepestMight, MagebaneLizard, Shock))
        register(PortalSet.basicLands)
    }

    @Test
    fun `same warp-exiled Nova is castable only with permission across rules observation and belief worlds`() {
        val deck = mapOf("Mountain" to 16, "Nova Hellkite" to 4)
        val base = environment(deck)
        val player = base.playerIds[0]
        val nova = (base.state.getHand(player) + base.state.getLibrary(player)).first {
            cardName(base.state, it) == "Nova Hellkite"
        }
        val (withoutPermission, permissionId) = exiledNovaState(base.state, player, nova)
        val withPermission = withoutPermission.copy(
            mayPlayPermissions = listOf(
                MayPlayPermission(
                    id = permissionId,
                    cardIds = setOf(nova),
                    controllerId = player,
                    sourceId = nova,
                    permanent = true,
                    timestamp = withoutPermission.timestamp,
                )
            ),
        )
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        val denied = world(base, withoutPermission, "warp-denied", knownDecks)
        val permitted = world(base, withPermission, "warp-permitted", knownDecks)

        val deniedInformation = denied.informationState("p0")
        val permittedInformation = permitted.informationState("p0")
        val deniedNova = deniedInformation.observation.card("p0", "EXILE", "Nova Hellkite")
        val permittedNova = permittedInformation.observation.card("p0", "EXILE", "Nova Hellkite")

        assertTrue(deniedNova.isWarpExiled)
        assertTrue(permittedNova.isWarpExiled)
        assertFalse(deniedNova.playableFromExile)
        assertTrue(permittedNova.playableFromExile)
        assertFalse(hasCastFor(denied, nova))
        assertTrue(hasCastFor(permitted, nova))
        val permittedChoice = permitted.expandChoices().candidates.single {
            ((permitted.resolveChoice(it) as? ArgentumResolvedChoice.Action)?.value as? CastSpell)?.cardId == nova
        }
        val permittedCast = (permitted.resolveChoice(permittedChoice) as ArgentumResolvedChoice.Action).value as CastSpell
        assertFalse(permittedCast.useAlternativeCost, "Warp exile grants only the ordinary-cost recast")
        assertNotEquals(deniedInformation.observation.observationDigest, permittedInformation.observation.observationDigest)
        val deniedBounded = BoundedPolicyInputCompiler.compile(deniedInformation)
        val permittedBounded = BoundedPolicyInputCompiler.compile(permittedInformation)
        assertFalse(deniedBounded.observation.card("p0", "EXILE", "Nova Hellkite").playableFromExile)
        assertTrue(permittedBounded.observation.card("p0", "EXILE", "Nova Hellkite").playableFromExile)
        assertFalse(deniedBounded.candidates.any { it.signature == permittedChoice.signature })
        assertTrue(permittedBounded.candidates.any { it.signature == permittedChoice.signature })
        // The permission is viewer-specific, while the public Warp provenance remains visible.
        val opponentNova = permitted.informationState("p1").observation.card("p0", "EXILE", "Nova Hellkite")
        assertTrue(opponentNova.isWarpExiled)
        assertFalse(opponentNova.playableFromExile)

        val rebuilt = ArgentumKnownDeckBeliefWorldSource(permitted, registry).sample(
            permittedInformation,
            knownDecks,
            beliefSeed = 9_019L,
            count = 4,
        )
        rebuilt.particles.forEach { weighted ->
            val sampled = weighted.value as ArgentumSearchWorld
            assertNull(sampled.knowledgeSupportFailure("p0", permittedInformation))
            assertTrue(sampled.informationState("p0").observation
                .card("p0", "EXILE", "Nova Hellkite").playableFromExile)
            assertTrue(hasCastFor(sampled, nova))
        }
    }

    @Test
    fun `actual Warp cast exposes its battlefield lifecycle marker to bounded policy input`() {
        val deck = mapOf("Mountain" to 16, "Nova Hellkite" to 4)
        val env = environment(deck)
        val player = env.playerIds[0]
        val nova = (env.state.getHand(player) + env.state.getLibrary(player)).first {
            cardName(env.state, it) == "Nova Hellkite"
        }
        val prepared = putInHand(mainPhase(env.state, player, redMana = 3), player, nova)
        env.restore(prepared, env.playerIds, env.stepCount)
        val world = ArgentumSearchWorld.create(
            env,
            "actual-warp-cast",
            9_020L,
            cardRegistry = registry,
            knownDecks = mapOf("p0" to deck, "p1" to deck),
        )
        val warp = world.expandChoices().candidates.single { choice ->
            val cast = (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value as? CastSpell
            cast?.cardId == nova && cast.useAlternativeCost
        }

        assertTrue(world.step(warp).accepted)
        repeat(6) {
            if (nova in world.authoritativeState().getBattlefield(player)) return@repeat
            val pass = world.expandChoices().candidates.single {
                it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
            }
            assertTrue(world.step(pass).accepted)
        }

        assertTrue(nova in world.authoritativeState().getBattlefield(player))
        assertTrue(world.authoritativeState().getEntity(nova)?.has<WarpedComponent>() == true)
        val policyNova = world.informationState("p0").observation.card("p0", "BATTLEFIELD", "Nova Hellkite")
        assertTrue(policyNova.isWarped)
        assertTrue(
            BoundedPolicyInputCompiler.compile(world.informationState("p0"))
                .observation.card("p0", "BATTLEFIELD", "Nova Hellkite").isWarped,
        )
    }

    @Test
    fun `Rockface creature-only mana differs from ordinary red in policy input and legal casts`() {
        val deck = mapOf("Mountain" to 14, "Hired Claw" to 3, "Shock" to 3)
        val base = environment(deck)
        val player = base.playerIds[0]
        val hired = (base.state.getHand(player) + base.state.getLibrary(player)).first {
            cardName(base.state, it) == "Hired Claw"
        }
        val shock = (base.state.getHand(player) + base.state.getLibrary(player)).first {
            cardName(base.state, it) == "Shock"
        }
        val ready = putInHand(putInHand(mainPhase(base.state, player), player, hired), player, shock)
        val ordinary = ready.updateEntity(player) { it.with(ManaPoolComponent(red = 1)) }
        val creatureOnly = ready.updateEntity(player) {
            it.with(
                ManaPoolComponent(
                    restrictedMana = listOf(
                        RestrictedManaEntry(Color.RED, ManaRestriction.CreatureSpellsOnly)
                    ),
                )
            )
        }
        val ordinaryWorld = world(base, ordinary, "ordinary-red")
        val restrictedWorld = world(base, creatureOnly, "creature-only-red")

        assertTrue(hasCastFor(ordinaryWorld, hired))
        assertTrue(hasCastFor(ordinaryWorld, shock))
        assertTrue(hasCastFor(restrictedWorld, hired))
        assertFalse(hasCastFor(restrictedWorld, shock))
        val mana = restrictedWorld.informationState("p0").observation.players.single { it.playerId == "p0" }.mana
        assertEquals(0, mana.red)
        assertEquals(1, mana.restricted.single().count)
        assertEquals("RED", mana.restricted.single().color)
        assertEquals("Spend this mana only to cast creature spells", mana.restricted.single().spendRestriction)
        assertEquals(
            mana,
            BoundedPolicyInputCompiler.compile(restrictedWorld.informationState("p0"))
                .observation.players.single { it.playerId == "p0" }.mana,
        )
    }

    @Test
    fun `Magebane spell memory remains explicit after its cast event leaves bounded history`() {
        val deck = mapOf("Mountain" to 14, "Magebane Lizard" to 1, "Shock" to 5)
        val base = environment(deck)
        val player = base.playerIds[0]
        val opponent = base.playerIds[1]
        val magebane = findCard(base.state, player, "Magebane Lizard")
        val shocks = findCards(base.state, player, "Shock")
        val currentShock = shocks[0]
        val earlierShock = shocks[1]
        val common = move(
            move(
                putInHand(mainPhase(base.state, player, redMana = 1), player, currentShock),
                earlierShock,
                ZoneKey(player, Zone.GRAVEYARD),
            ),
            magebane,
            ZoneKey(player, Zone.BATTLEFIELD),
        )
        val noEarlierCast = common.copy(
            spellsCastThisTurn = 0,
            playerSpellsCastThisTurn = common.playerSpellsCastThisTurn - player,
            spellsCastThisTurnByPlayer = common.spellsCastThisTurnByPlayer - player,
        )
        val oneEarlierCast = common.copy(
            spellsCastThisTurn = 1,
            playerSpellsCastThisTurn = common.playerSpellsCastThisTurn + (player to 1),
            spellsCastThisTurnByPlayer = common.spellsCastThisTurnByPlayer +
                (player to listOf(
                    CastSpellRecord(
                        typeLine = TypeLine.parse("Instant"),
                        manaValue = 1,
                        colors = setOf(Color.RED),
                        isFaceDown = false,
                        sourceEntityId = earlierShock,
                        castFromZone = Zone.HAND,
                        name = "Shock",
                    )
                )),
        )
        val first = world(base, noEarlierCast, "magebane-turn-memory")
        val second = world(base, oneEarlierCast, "magebane-turn-memory")
        val (firstInformation, secondInformation) = withAgedCausalPrefixes(
            first,
            second,
            firstCause = "UNRELATED_TRIGGER_CONTEXT",
            secondCause = "SPELL_CAST_NONCREATURE",
        )

        assertTurnFactsAreOnlySafeDifference(firstInformation, secondInformation)
        val firstInput = BoundedPolicyInputCompiler.compile(firstInformation)
        val secondInput = BoundedPolicyInputCompiler.compile(secondInformation)
        assertAgedInputsAliasWithoutTurnFacts(firstInput, secondInput)
        assertEquals(0, firstInput.observation.player("p0").noncreatureSpellsCastThisTurn)
        assertEquals(1, secondInput.observation.player("p0").noncreatureSpellsCastThisTurn)
        assertNotEquals(firstInput.inputDigest, secondInput.inputDigest)

        val firstCast = castChoice(first, currentShock, opponent)
        val secondCast = castChoice(second, currentShock, opponent)
        assertEquals(firstCast.signature, secondCast.signature)
        assertTrue(first.step(firstCast).accepted)
        assertTrue(second.step(secondCast).accepted)
        resolveStack(first)
        resolveStack(second)

        assertEquals(19, first.authoritativeState().lifeTotal(player))
        assertEquals(18, second.authoritativeState().lifeTotal(player))
    }

    @Test
    fun `speed trigger-fired memory changes the next life-loss transition without changing the cast`() {
        val deck = mapOf("Mountain" to 16, "Shock" to 4)
        val base = environment(deck)
        val player = base.playerIds[0]
        val opponent = base.playerIds[1]
        val shock = findCard(base.state, player, "Shock")
        val common = putInHand(mainPhase(base.state, player, redMana = 1), player, shock)
            .updateEntity(player) {
                it.with(PlayerSpeedComponent(2)).without<TriggeredAbilityFiredThisTurnComponent>()
            }
        val notFired = common
        val alreadyFired = common.updateEntity(player) {
            it.with(
                TriggeredAbilityFiredThisTurnComponent()
                    .withFired(SpeedAbilities.INHERENT_SPEED_ABILITY_ID)
            )
        }
        val first = world(base, notFired, "speed-turn-memory")
        val second = world(base, alreadyFired, "speed-turn-memory")
        val (firstInformation, secondInformation) = withAgedCausalPrefixes(
            first,
            second,
            firstCause = "UNRELATED_TRIGGER_CONTEXT",
            secondCause = "SPEED_INCREASE_TRIGGER_FIRED",
        )

        assertTurnFactsAreOnlySafeDifference(firstInformation, secondInformation)
        val firstInput = BoundedPolicyInputCompiler.compile(firstInformation)
        val secondInput = BoundedPolicyInputCompiler.compile(secondInformation)
        assertAgedInputsAliasWithoutTurnFacts(firstInput, secondInput)
        assertFalse(firstInput.observation.player("p0").speedIncreaseTriggerFiredThisTurn)
        assertTrue(secondInput.observation.player("p0").speedIncreaseTriggerFiredThisTurn)
        assertNotEquals(firstInput.inputDigest, secondInput.inputDigest)

        val firstCast = castChoice(first, shock, opponent)
        val secondCast = castChoice(second, shock, opponent)
        assertEquals(firstCast.signature, secondCast.signature)
        assertTrue(first.step(firstCast).accepted)
        assertTrue(second.step(secondCast).accepted)
        resolveStack(first)
        resolveStack(second)

        assertEquals(3, first.authoritativeState().speed(player))
        assertEquals(2, second.authoritativeState().speed(player))
    }

    @Test
    fun `Hired Claw remembers both the life-loss gate and its spent activation`() {
        val deck = mapOf("Mountain" to 14, "Hired Claw" to 3, "Shock" to 3)
        val base = environment(deck)
        val player = base.playerIds[0]
        val opponent = base.playerIds[1]
        val hired = findCard(base.state, player, "Hired Claw")
        val shock = findCard(base.state, player, "Shock")
        val counterAbility = registry.requireCard("Hired Claw").activatedAbilities.single().id
        val common = move(
            putInHand(mainPhase(base.state, player, redMana = 3), player, shock),
            hired,
            ZoneKey(player, Zone.BATTLEFIELD),
        ).updateEntity(hired) {
            it.with(CountersComponent(mapOf(CounterType.PLUS_ONE_PLUS_ONE to 1)))
                .without<AbilityActivatedThisTurnComponent>()
        }.updateEntity(opponent) { it.without<LifeLostThisTurnComponent>() }
        val priorTurnCounter = common
        val spentThisTurn = common
            .updateEntity(opponent) { it.with(LifeLostThisTurnComponent) }
            .updateEntity(hired) {
                it.with(AbilityActivatedThisTurnComponent().withActivated(counterAbility))
            }
        val first = world(base, priorTurnCounter, "hired-claw-turn-memory")
        val second = world(base, spentThisTurn, "hired-claw-turn-memory")
        val (firstInformation, secondInformation) = withAgedCausalPrefixes(
            first,
            second,
            firstCause = "UNRELATED_TRIGGER_CONTEXT",
            secondCause = "HIRED_CLAW_ACTIVATED_AFTER_LIFE_LOSS",
        )

        assertFalse(hasActivationFor(first, hired, counterAbility))
        assertFalse(hasActivationFor(second, hired, counterAbility))
        assertTurnFactsAreOnlySafeDifference(
            firstInformation,
            secondInformation,
            runtimeSemanticIdentityDiffers = true,
        )
        val firstInput = BoundedPolicyInputCompiler.compile(firstInformation)
        val secondInput = BoundedPolicyInputCompiler.compile(secondInformation)
        assertAgedInputsAliasWithoutTurnFacts(
            firstInput,
            secondInput,
            runtimeSemanticIdentityDiffers = true,
        )
        assertFalse(firstInput.observation.player("p1").lostLifeThisTurn)
        assertTrue(secondInput.observation.player("p1").lostLifeThisTurn)
        assertFalse(firstInput.observation.card("p0", "BATTLEFIELD", "Hired Claw").hasActivatedAbilityThisTurn)
        assertTrue(secondInput.observation.card("p0", "BATTLEFIELD", "Hired Claw").hasActivatedAbilityThisTurn)
        assertNotEquals(firstInput.inputDigest, secondInput.inputDigest)

        val firstCast = castChoice(first, shock, opponent)
        val secondCast = castChoice(second, shock, opponent)
        assertEquals(firstCast.signature, secondCast.signature)
        assertTrue(first.step(firstCast).accepted)
        assertTrue(second.step(secondCast).accepted)
        resolveStack(first)
        resolveStack(second)

        assertTrue(hasActivationFor(first, hired, counterAbility))
        assertFalse(hasActivationFor(second, hired, counterAbility))
    }

    @Test
    fun `Temple damage tally survives combat timing and controls later transform legality`() {
        val deck = mapOf("Mountain" to 16, "Ojer Axonil, Deepest Might" to 4)
        val base = environment(deck)
        val player = base.playerIds[0]
        val ojer = findCard(base.state, player, "Ojer Axonil, Deepest Might")
        val common = transformedTemple(
            combatPhase(base.state, player, redMana = 3),
            player,
            ojer,
        )
        val belowThreshold = common.updateEntity(player) {
            it.with(RedNoncombatDamageDealtThisTurnComponent(3))
        }
        val atThreshold = common.updateEntity(player) {
            it.with(RedNoncombatDamageDealtThisTurnComponent(4))
        }
        val transformAbility = registry.requireCard("Temple of Power").activatedAbilities
            .single { !it.isManaAbility }.id
        val first = world(base, belowThreshold, "temple-turn-memory")
        val second = world(base, atThreshold, "temple-turn-memory")
        val (firstInformation, secondInformation) = withAgedCausalPrefixes(
            first,
            second,
            firstCause = "RED_NONCOMBAT_DAMAGE_TOTAL_3",
            secondCause = "RED_NONCOMBAT_DAMAGE_TOTAL_4",
        )

        assertFalse(hasActivationFor(first, ojer, transformAbility))
        assertFalse(hasActivationFor(second, ojer, transformAbility))
        assertTurnFactsAreOnlySafeDifference(firstInformation, secondInformation)
        val firstInput = BoundedPolicyInputCompiler.compile(firstInformation)
        val secondInput = BoundedPolicyInputCompiler.compile(secondInformation)
        assertAgedInputsAliasWithoutTurnFacts(firstInput, secondInput)
        assertEquals(3, firstInput.observation.player("p0").redNoncombatDamageDealtThisTurn)
        assertEquals(4, secondInput.observation.player("p0").redNoncombatDamageDealtThisTurn)
        assertNotEquals(firstInput.inputDigest, secondInput.inputDigest)

        advanceToPostcombatMain(first)
        advanceToPostcombatMain(second)
        assertEquals(
            3,
            first.authoritativeState().getEntity(player)
                ?.get<RedNoncombatDamageDealtThisTurnComponent>()?.amount,
        )
        assertEquals(
            4,
            second.authoritativeState().getEntity(player)
                ?.get<RedNoncombatDamageDealtThisTurnComponent>()?.amount,
        )
        val laterFirst = world(
            base,
            first.authoritativeState().updateEntity(player) { it.with(ManaPoolComponent(red = 3)) },
            "temple-later",
        )
        val laterSecond = world(
            base,
            second.authoritativeState().updateEntity(player) { it.with(ManaPoolComponent(red = 3)) },
            "temple-later",
        )
        assertFalse(hasActivationFor(laterFirst, ojer, transformAbility))
        val transform = activationChoice(laterSecond, ojer, transformAbility)
        assertTrue(laterSecond.step(transform).accepted)
    }

    @Test
    fun `remaining land play is explicit during combat and controls the later main-phase action`() {
        val deck = mapOf("Mountain" to 20)
        val base = environment(deck)
        val player = base.playerIds[0]
        val mountain = base.state.getHand(player).first()
        val common = combatPhase(base.state, player)
        val available = common.updateEntity(player) { it.with(LandDropsComponent(remaining = 1)) }
        val spent = common.updateEntity(player) { it.with(LandDropsComponent(remaining = 0)) }
        val first = world(base, available, "land-turn-memory")
        val second = world(base, spent, "land-turn-memory")
        val (firstInformation, secondInformation) = withAgedCausalPrefixes(
            first,
            second,
            firstCause = "UNRELATED_TRIGGER_CONTEXT",
            secondCause = "LAND_PLAYED_THIS_TURN",
        )

        assertFalse(hasLandPlayFor(first, mountain))
        assertFalse(hasLandPlayFor(second, mountain))
        assertTurnFactsAreOnlySafeDifference(firstInformation, secondInformation)
        val firstInput = BoundedPolicyInputCompiler.compile(firstInformation)
        val secondInput = BoundedPolicyInputCompiler.compile(secondInformation)
        assertAgedInputsAliasWithoutTurnFacts(firstInput, secondInput)
        assertEquals(1, firstInput.observation.player("p0").landPlaysRemainingThisTurn)
        assertEquals(0, secondInput.observation.player("p0").landPlaysRemainingThisTurn)
        assertNotEquals(firstInput.inputDigest, secondInput.inputDigest)

        advanceToPostcombatMain(first)
        advanceToPostcombatMain(second)
        assertEquals(
            1,
            first.authoritativeState().getEntity(player)?.get<LandDropsComponent>()?.remaining,
        )
        assertEquals(
            0,
            second.authoritativeState().getEntity(player)?.get<LandDropsComponent>()?.remaining,
        )
        val play = landChoice(first, mountain)
        assertFalse(hasLandPlayFor(second, mountain))
        assertTrue(first.step(play).accepted)
        assertTrue(mountain in first.authoritativeState().getBattlefield(player))
        assertEquals(
            0,
            first.authoritativeState().getEntity(player)?.get<LandDropsComponent>()?.remaining,
        )
    }

    @Test
    fun `London mulligan bottom order is private knowledge and a hard world-support constraint`() {
        val deck = mapOf(
            "Mountain" to 4,
            "Hired Claw" to 4,
            "Rockface Village" to 4,
            "Nova Hellkite" to 4,
            "Shock" to 4,
        )
        val env = environment(deck, skipMulligans = false)
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        val world = ArgentumSearchWorld.create(
            env,
            "known-mulligan-bottom",
            9_021L,
            cardRegistry = registry,
            knownDecks = knownDecks,
        )

        var rootMulligans = 0
        var rootKept = false
        var mulliganDecisions = 0
        while (!rootKept) {
            check(mulliganDecisions++ < 8)
            val rootActs = world.actorToAct() == "p0"
            val takeRootMulligan = rootActs && rootMulligans < 2
            val expansion = world.expandChoices()
            val choice = expansion.candidates.singleOrNull { candidate ->
                val action = (world.resolveChoice(candidate) as? ArgentumResolvedChoice.Action)?.value
                if (takeRootMulligan) action is TakeMulligan else action is KeepHand
            } ?: error(
                "No ${if (takeRootMulligan) "take" else "keep"} action for ${world.actorToAct()}: " +
                    expansion.candidates.joinToString { it.display.label },
            )
            assertTrue(world.step(choice).accepted)
            if (takeRootMulligan) rootMulligans++
            if (rootActs && !takeRootMulligan) rootKept = true
        }
        assertEquals(2, rootMulligans)
        var bottomExpansion = world.expandChoices()
        while (bottomExpansion.candidates.none { candidate ->
                (world.resolveChoice(candidate) as? ArgentumResolvedChoice.Action)?.value is BottomCards
            }
        ) {
            val keep = bottomExpansion.candidates.single { candidate ->
                (world.resolveChoice(candidate) as? ArgentumResolvedChoice.Action)?.value is KeepHand
            }
            assertTrue(world.step(keep).accepted)
            bottomExpansion = world.expandChoices()
        }
        val bottomChoice = bottomExpansion.candidates.firstOrNull { choice ->
            val action = (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value as? BottomCards
            action != null && action.cardIds.map { cardName(world.authoritativeState(), it) }.distinct().size == 2
        } ?: error("No distinct two-card bottom action: ${bottomExpansion.candidates.joinToString { it.display.label }}")
        val action = (world.resolveChoice(bottomChoice) as ArgentumResolvedChoice.Action).value as BottomCards
        val expectedNames = action.cardIds.map { cardName(world.authoritativeState(), it) }

        assertTrue(world.step(bottomChoice).accepted)
        val information = world.informationState("p0")
        assertEquals(expectedNames, information.knowledge.knownLibraryOrders.single { it.playerId == "p0" }.bottom)
        assertTrue(world.informationState("p1").knowledge.knownLibraryOrders
            .single { it.playerId == "p0" }.bottom.isEmpty())
        assertEquals(action.cardIds, world.authoritativeState().getLibrary(env.playerIds[0]).takeLast(2))

        val rebuilt = ArgentumKnownDeckBeliefWorldSource(world, registry).sample(
            information,
            knownDecks,
            beliefSeed = 9_022L,
            count = 4,
        )
        rebuilt.particles.forEach { weighted ->
            val sampled = weighted.value as ArgentumSearchWorld
            assertEquals(expectedNames, sampled.authoritativeState().getLibrary(env.playerIds[0])
                .takeLast(2).map { cardName(sampled.authoritativeState(), it) })
            assertNull(sampled.knowledgeSupportFailure("p0", information))
        }

        val library = world.authoritativeState().getLibrary(env.playerIds[0])
        val reversedBottom = library.dropLast(2) + library.takeLast(2).reversed()
        val contradictoryState = world.authoritativeState().copy(
            zones = world.authoritativeState().zones +
                (ZoneKey(env.playerIds[0], Zone.LIBRARY) to reversedBottom),
        )
        val contradictory = world.withSampledState(contradictoryState, futureChanceStreamIdentity = 9_023L)
        assertEquals("LIBRARY_BOTTOM_ORDER_MISMATCH", contradictory.knowledgeSupportFailure("p0", information))
    }

    private fun environment(deck: Map<String, Int>, skipMulligans: Boolean = true): GameEnvironment =
        GameEnvironment.create(registry).also { environment ->
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                        PlayerConfig("Bob", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                    ),
                    seed = 9_018L,
                    skipMulligans = skipMulligans,
                    startingPlayerIndex = 0,
                )
            )
        }

    private fun world(
        source: GameEnvironment,
        state: GameState,
        id: String,
        knownDecks: Map<String, Map<String, Int>> = emptyMap(),
    ): ArgentumSearchWorld {
        val environment = source.fork().also { it.restore(state, source.playerIds, source.stepCount) }
        return ArgentumSearchWorld.create(
            environment,
            id,
            9_018L,
            cardRegistry = registry,
            knownDecks = knownDecks,
        )
    }

    private fun mainPhase(state: GameState, player: EntityId, redMana: Int = 0): GameState =
        state.copy(
            turnNumber = maxOf(1, state.turnNumber),
            activePlayerId = player,
            priorityPlayerId = player,
            phase = Phase.PRECOMBAT_MAIN,
            step = Step.PRECOMBAT_MAIN,
            priorityPassedBy = emptySet(),
            pendingDecision = null,
        ).updateEntity(player) { it.with(ManaPoolComponent(red = redMana)) }

    private fun combatPhase(state: GameState, player: EntityId, redMana: Int = 0): GameState =
        state.copy(
            turnNumber = maxOf(1, state.turnNumber),
            activePlayerId = player,
            priorityPlayerId = player,
            phase = Phase.COMBAT,
            step = Step.BEGIN_COMBAT,
            priorityPassedBy = emptySet(),
            pendingDecision = null,
        ).updateEntity(player) { it.with(ManaPoolComponent(red = redMana)) }

    private fun exiledNovaState(
        state: GameState,
        player: EntityId,
        nova: EntityId,
    ): Pair<GameState, EntityId> {
        val handKey = ZoneKey(player, Zone.HAND)
        val libraryKey = ZoneKey(player, Zone.LIBRARY)
        val exileKey = ZoneKey(player, Zone.EXILE)
        val moved = mainPhase(state, player, redMana = 5).copy(
            zones = state.zones +
                (handKey to state.getHand(player).filterNot { it == nova }) +
                (libraryKey to state.getLibrary(player).filterNot { it == nova }) +
                (exileKey to (state.getExile(player).filterNot { it == nova } + nova)),
        ).updateEntity(nova) { it.with(WarpExiledComponent(player)) }
        val (permissionId, allocated) = moved.newEntity()
        return allocated to permissionId
    }

    private fun putInHand(state: GameState, player: EntityId, card: EntityId): GameState {
        if (card in state.getHand(player)) return state
        val handKey = ZoneKey(player, Zone.HAND)
        val libraryKey = ZoneKey(player, Zone.LIBRARY)
        val hand = state.getHand(player)
        val library = state.getLibrary(player)
        require(card in library)
        val displaced = hand.first { existing ->
            cardName(state, existing) !in setOf("Nova Hellkite", "Hired Claw", "Shock")
        }
        return state.copy(
            zones = state.zones +
                (handKey to hand.map { if (it == displaced) card else it }) +
                (libraryKey to library.map { if (it == card) displaced else it }),
        )
    }

    private fun move(state: GameState, card: EntityId, destination: ZoneKey): GameState {
        val source = state.zones.entries.single { (_, cards) -> card in cards }.key
        if (source == destination) return state
        return state.removeFromZone(source, card).addToZone(destination, card)
    }

    private fun transformedTemple(state: GameState, player: EntityId, ojer: EntityId): GameState {
        val front = requireNotNull(state.getEntity(ojer)?.get<CardComponent>())
        val battlefield = move(state, ojer, ZoneKey(player, Zone.BATTLEFIELD)).updateEntity(ojer) {
            it.with(
                DoubleFacedComponent(
                    frontCardDefinitionId = front.cardDefinitionId,
                    backCardDefinitionId = "Temple of Power",
                )
            )
        }
        val transformed = TransformEffectExecutor(registry).execute(
            battlefield,
            TransformEffect(),
            EffectContext(sourceId = ojer, controllerId = player),
        )
        check(transformed.error == null) { transformed.error.orEmpty() }
        check(cardName(transformed.state, ojer) == "Temple of Power")
        return transformed.state
    }

    private fun castChoice(
        world: ArgentumSearchWorld,
        card: EntityId,
        targetPlayer: EntityId,
    ): SemanticChoice = world.expandChoices().candidates.single { choice ->
        val cast = (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value as? CastSpell
        cast?.cardId == card && ChosenTarget.Player(targetPlayer) in cast.targets
    }

    private fun activationChoice(
        world: ArgentumSearchWorld,
        source: EntityId,
        abilityId: com.wingedsheep.sdk.scripting.AbilityId,
    ): SemanticChoice = world.expandChoices().candidates.single { choice ->
        val activation = (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value as? ActivateAbility
        activation?.sourceId == source && activation.abilityId == abilityId
    }

    private fun hasActivationFor(
        world: ArgentumSearchWorld,
        source: EntityId,
        abilityId: com.wingedsheep.sdk.scripting.AbilityId,
    ): Boolean = world.expandChoices().candidates.any { choice ->
        val activation = (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value as? ActivateAbility
        activation?.sourceId == source && activation.abilityId == abilityId
    }

    private fun landChoice(world: ArgentumSearchWorld, card: EntityId): SemanticChoice =
        world.expandChoices().candidates.single { choice ->
            val play = (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value as? PlayLand
            play?.cardId == card
        }

    private fun hasLandPlayFor(world: ArgentumSearchWorld, card: EntityId): Boolean =
        world.expandChoices().candidates.any { choice ->
            val play = (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value as? PlayLand
            play?.cardId == card
        }

    private fun resolveStack(world: ArgentumSearchWorld) {
        repeat(24) {
            if (world.authoritativeState().stack.isEmpty()) return
            val pass = world.expandChoices().candidates.singleOrNull {
                it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
            } ?: error("No priority pass while resolving ${world.authoritativeState().stack}")
            assertTrue(world.step(pass).accepted)
        }
        error("Stack did not resolve within the focused test bound")
    }

    private fun advanceToPostcombatMain(world: ArgentumSearchWorld) {
        repeat(64) {
            val state = world.authoritativeState()
            if (state.phase == Phase.POSTCOMBAT_MAIN && state.step == Step.POSTCOMBAT_MAIN) return
            val expansion = world.expandChoices()
            val choice = expansion.candidates.singleOrNull {
                it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
            } ?: expansion.candidates.singleOrNull {
                it.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS &&
                    it.actionIntent.kind == SemanticActionIntentKind.DECLINE_ATTACK
            } ?: error(
                "No deterministic no-op route through combat at ${state.phase}/${state.step}: " +
                    expansion.candidates.joinToString { it.display.label },
            )
            assertTrue(world.step(choice).accepted)
        }
        error("Combat did not advance to postcombat main within the focused test bound")
    }

    /**
     * Wrap the source-constructive engine states in two public, structurally valid ledgers whose
     * different causal record is followed by the same 65-event safe context. The last 64 events
     * are therefore identical. Current-turn overflow reachability itself is established by the
     * current-revision Case Cedar evidence; this harness locks the bounded compiler behavior for
     * each paired rules consequence without treating commitment digests as learnable features.
     */
    private fun withAgedCausalPrefixes(
        firstWorld: ArgentumSearchWorld,
        secondWorld: ArgentumSearchWorld,
        firstCause: String,
        secondCause: String,
    ): Pair<PolicyInformationState, PolicyInformationState> {
        val first = firstWorld.informationState("p0")
        val second = secondWorld.informationState("p0")
        val firstProposal = firstWorld.expandChoices().proposalVersion
        val secondProposal = secondWorld.expandChoices().proposalVersion
        assertEquals(firstProposal, secondProposal)
        return ageCausalPrefix(first, firstProposal, firstCause) to
            ageCausalPrefix(second, secondProposal, secondCause)
    }

    private fun ageCausalPrefix(
        information: PolicyInformationState,
        proposalVersion: String,
        cause: String,
    ): PolicyInformationState {
        val causeEvent = PolicyHistoryEvent(
            eventId = 0,
            audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
            actor = "p0",
            kind = PolicyHistoryEventKind.CAUSAL,
            payload = buildJsonObject { put("fixtureCause", JsonPrimitive(cause)) },
            detail = PerspectiveEventDetail.Causal(
                eventType = cause,
                actorId = "p0",
                sourceName = null,
                sourceObjectRef = null,
                result = "PUBLIC_CURRENT_TURN_CAUSE",
            ),
        )
        val commonSuffix = (1L..65L).map { eventId ->
            PolicyHistoryEvent(
                eventId = eventId,
                audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
                actor = null,
                kind = PolicyHistoryEventKind.CAUSAL,
                payload = buildJsonObject { put("contextOrdinal", JsonPrimitive(eventId)) },
                detail = PerspectiveEventDetail.Causal(
                    eventType = "VISIBLE_CONTEXT",
                    actorId = null,
                    sourceName = null,
                    sourceObjectRef = null,
                    result = "COMMON_SUFFIX_$eventId",
                ),
            )
        }
        val history = listOf(causeEvent) + commonSuffix
        val commitment = PolicyHistoryCommitment.replay(history)
        return information.copy(
            historyCommitment = commitment,
            history = history,
            informationStateDigest = PolicyInformationStateDigest.compute(
                observationDigest = information.observation.observationDigest,
                historyCommitment = commitment,
                knowledgeDigest = information.knowledge.knowledgeDigest,
                actingPlayerId = information.actingPlayerId,
                candidateSignatures = information.candidates.map { it.signature },
                proposalVersion = proposalVersion,
            ),
        )
    }

    private fun assertAgedInputsAliasWithoutTurnFacts(
        first: BoundedPolicyInput,
        second: BoundedPolicyInput,
        runtimeSemanticIdentityDiffers: Boolean = false,
    ) {
        assertEquals(64, first.recentEvents.size)
        assertEquals(first.recentEvents, second.recentEvents)
        assertEquals(2, first.recentEventStartCursor)
        assertEquals(first.recentEventStartCursor, second.recentEventStartCursor)
        assertEquals(first.actingPlayerId, second.actingPlayerId)
        assertEquals(first.knowledge, second.knowledge)
        assertEquals(first.belief, second.belief)
        if (runtimeSemanticIdentityDiffers) {
            assertCandidateSurfacesEqualWithDistinctIdentity(first.candidates, second.candidates)
        } else {
            assertEquals(first.candidates, second.candidates)
        }
        assertEquals(first.terminated, second.terminated)
        assertEquals(first.winnerId, second.winnerId)
        if (runtimeSemanticIdentityDiffers) {
            assertEquals(
                routingBlind(turnBlind(first.observation)),
                routingBlind(turnBlind(second.observation)),
            )
        } else {
            assertEquals(turnBlind(first.observation), turnBlind(second.observation))
        }
        assertNotEquals(first.historyCommitment, second.historyCommitment)
    }

    private fun assertTurnFactsAreOnlySafeDifference(
        first: PolicyInformationState,
        second: PolicyInformationState,
        runtimeSemanticIdentityDiffers: Boolean = false,
    ) {
        assertEquals(66, first.history.size)
        assertEquals(first.history.size, second.history.size)
        assertNotEquals(first.history.first(), second.history.first())
        assertEquals(first.history.drop(1), second.history.drop(1))
        assertNotEquals(first.historyCommitment, second.historyCommitment)
        assertEquals(first.knowledge, second.knowledge)
        if (runtimeSemanticIdentityDiffers) {
            assertCandidateSurfacesEqualWithDistinctIdentity(first.candidates, second.candidates)
        } else {
            assertEquals(first.candidates, second.candidates)
        }
        if (runtimeSemanticIdentityDiffers) {
            assertEquals(
                routingBlind(turnBlind(first.observation)),
                routingBlind(turnBlind(second.observation)),
            )
        } else {
            assertEquals(turnBlind(first.observation), turnBlind(second.observation))
        }
    }

    private fun assertCandidateSurfacesEqualWithDistinctIdentity(
        first: List<SemanticChoice>,
        second: List<SemanticChoice>,
    ) {
        fun surface(choice: SemanticChoice): String = listOf(
            choice.kind.name,
            choice.operationFamily.name,
            choice.actionIntent.toString(),
            choice.display.toString(),
        ).joinToString("\u001f")

        assertEquals(first.map(::surface).sorted(), second.map(::surface).sorted())
        assertNotEquals(first.map { it.signature }.toSet(), second.map { it.signature }.toSet())
    }

    /** Runtime state deliberately changes the observation-scoped reference derived from it. */
    private fun routingBlind(observation: PolicyObservation): PolicyObservation = observation.copy(
        zones = observation.zones.map { zone ->
            zone.copy(cards = zone.cards.map { card -> card.copy(objectRef = "<routing-ref>") })
        },
    )

    /** Approximation of the old V4 learnable snapshot: retain context, erase repaired turn facts. */
    private fun turnBlind(observation: PolicyObservation): PolicyObservation = observation.copy(
        players = observation.players.map { player ->
            player.copy(
                noncreatureSpellsCastThisTurn = 0,
                lostLifeThisTurn = false,
                speedIncreaseTriggerFiredThisTurn = false,
                redNoncombatDamageDealtThisTurn = 0,
                landPlaysRemainingThisTurn = 0,
            )
        },
        zones = observation.zones.map { zone ->
            zone.copy(cards = zone.cards.map { it.copy(hasActivatedAbilityThisTurn = false) })
        },
        observationDigest = "",
    )

    private fun hasCastFor(world: ArgentumSearchWorld, cardId: EntityId): Boolean =
        world.expandChoices().candidates.any { choice ->
            val action = (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value
            action is CastSpell && action.cardId == cardId
        }

    private fun cardName(state: GameState, id: EntityId): String =
        requireNotNull(state.getEntity(id)?.get<CardComponent>()?.name)

    private fun findCard(state: GameState, owner: EntityId, name: String): EntityId =
        findCards(state, owner, name).first()

    private fun findCards(state: GameState, owner: EntityId, name: String): List<EntityId> =
        (state.getHand(owner) + state.getLibrary(owner)).filter { cardName(state, it) == name }

    private fun PolicyObservation.player(playerId: String) = players.single { it.playerId == playerId }

    private fun org.mtgallium.agent.infoset.core.PolicyObservation.card(
        ownerId: String,
        zone: String,
        name: String,
    ) = zones.single { it.ownerId == ownerId && it.zone == zone }.cards.single { it.name == name }
}
