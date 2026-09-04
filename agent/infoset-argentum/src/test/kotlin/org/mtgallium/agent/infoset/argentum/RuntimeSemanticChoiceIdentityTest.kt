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
import org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler
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
    fun `ordinary and warped Nova singleton attacks remain distinct through rebinding and Warp exile`() {
        val base = environment()
        val player = base.playerIds[0]
        val opponent = base.playerIds[1]
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        val availableNovas = (base.state.getHand(player) + base.state.getLibrary(player))
            .filter { cardName(base.state, it) == "Nova Hellkite" }
        assertTrue(availableNovas.size >= 2)
        val prepared = availableNovas.take(2).fold(mainPhase(base.state, player, redMana = 8)) { state, nova ->
            putInHand(state, player, nova)
        }
        val constructor = world(base, prepared, "runtime-choice-construction", knownDecks)

        val ordinaryCastChoice = constructor.expandChoices().candidates.single { choice ->
            val cast = resolvedCast(constructor, choice)
            cast?.let { cardName(constructor.authoritativeState(), it.cardId) == "Nova Hellkite" &&
                !it.useAlternativeCost } == true
        }
        val ordinaryNova = requireNotNull(resolvedCast(constructor, ordinaryCastChoice)).cardId
        assertTrue(constructor.step(ordinaryCastChoice).accepted)
        resolveStack(constructor)

        val warpedCastChoice = constructor.expandChoices().candidates.single { choice ->
            val cast = resolvedCast(constructor, choice)
            cast?.let { cardName(constructor.authoritativeState(), it.cardId) == "Nova Hellkite" &&
                it.useAlternativeCost } == true
        }
        val warpedNova = requireNotNull(resolvedCast(constructor, warpedCastChoice)).cardId
        assertNotEquals(ordinaryNova, warpedNova)
        assertTrue(constructor.step(warpedCastChoice).accepted)
        resolveStack(constructor)
        advanceToAttackDeclaration(constructor)

        val attackRoot = constructor.authoritativeState()
        assertFalse(attackRoot.getEntity(ordinaryNova)?.has<WarpedComponent>() == true)
        assertTrue(attackRoot.getEntity(warpedNova)?.has<WarpedComponent>() == true)
        assertEquals(listOf(warpedNova), attackRoot.delayedTriggers.map { it.sourceId })

        val engineRoot = base.fork().also { it.restore(attackRoot, base.playerIds, base.stepCount) }
        val declaration = engineRoot.legalActions().single { it.action is DeclareAttackers }
        assertEquals(setOf(ordinaryNova, warpedNova), declaration.validAttackers.orEmpty().toSet())
        val physicalSubsets = listOf(
            emptySet(),
            setOf(ordinaryNova),
            setOf(warpedNova),
            setOf(ordinaryNova, warpedNova),
        )
        physicalSubsets.forEach { attackers ->
            val exact = engineRoot.fork()
            exact.step(DeclareAttackers(player, attackers.associateWith { opponent }))
            assertNull(exact.lastRejection, "Rules engine rejected physical attack subset $attackers")
        }

        val source = world(base, attackRoot, "runtime-choice-source", knownDecks)
        val information = source.informationState("p0")
        val visibleNovas = information.observation.zones
            .single { it.ownerId == "p0" && it.zone == "BATTLEFIELD" }
            .cards.filter { it.name == "Nova Hellkite" }
        assertEquals(2, visibleNovas.size)
        assertEquals(listOf(false, true), visibleNovas.map { it.isWarped }.sorted())

        val expansion = source.expandChoices()
        assertEquals(CANDIDATE_SCHEMA_V4, information.candidateSchemaVersion)
        assertTrue(expansion.proposalVersion.startsWith("semantic-structured-actions-v4:"))
        assertTrue(expansion.isExhaustive)
        assertEquals(4, expansion.estimatedCandidateCount)
        assertEquals(4, expansion.candidates.size)
        val byAttackers = expansion.candidates.associateBy { choice ->
            val action = (source.resolveChoice(choice) as ArgentumResolvedChoice.Action).value as DeclareAttackers
            action.attackers.keys
        }
        assertEquals(physicalSubsets.toSet(), byAttackers.keys)
        val ordinaryOnly = byAttackers.getValue(setOf(ordinaryNova))
        val warpedOnly = byAttackers.getValue(setOf(warpedNova))
        assertNotEquals(ordinaryOnly.signature, warpedOnly.signature)
        assertNotEquals(ordinaryOnly.canonicalPayload, warpedOnly.canonicalPayload)

        val bounded = BoundedPolicyInputCompiler.compile(information)
        assertEquals(CANDIDATE_SCHEMA_V4, bounded.candidateSchemaVersion)
        assertEquals(4, bounded.candidates.size)
        assertTrue(bounded.candidates.any { it.signature == ordinaryOnly.signature })
        assertTrue(bounded.candidates.any { it.signature == warpedOnly.signature })

        val ordinaryBranch = reboundAndExecute(
            base,
            attackRoot,
            knownDecks,
            ordinaryOnly,
            expectedAttacker = ordinaryNova,
            otherNova = warpedNova,
        )
        val warpedBranch = reboundAndExecute(
            base,
            attackRoot,
            knownDecks,
            warpedOnly,
            expectedAttacker = warpedNova,
            otherNova = ordinaryNova,
        )
        advanceThroughWarpExile(ordinaryBranch, player, warpedNova)
        advanceThroughWarpExile(warpedBranch, player, warpedNova)
        listOf(ordinaryBranch, warpedBranch).forEach { branch ->
            assertTrue(ordinaryNova in branch.authoritativeState().getBattlefield(player))
            assertTrue(warpedNova in branch.authoritativeState().getExile(player))
        }
    }

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
        val projector = SafeObservationProjector()

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

    private fun reboundAndExecute(
        source: GameEnvironment,
        state: GameState,
        knownDecks: Map<String, Map<String, Int>>,
        choice: SemanticChoice,
        expectedAttacker: EntityId,
        otherNova: EntityId,
    ): ArgentumSearchWorld {
        val branch = world(source, state, "runtime-choice-rebind-${expectedAttacker.value}", knownDecks)
        assertTrue(branch.step(choice).accepted)
        assertEquals(
            source.playerIds[1],
            branch.authoritativeState().getEntity(expectedAttacker)?.get<AttackingComponent>()?.defenderId,
        )
        assertFalse(branch.authoritativeState().getEntity(otherNova)?.has<AttackingComponent>() == true)
        return branch
    }

    private fun advanceToAttackDeclaration(world: ArgentumSearchWorld) {
        repeat(32) {
            val expansion = world.expandChoices()
            if (expansion.candidates.any { it.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS }) return
            val pass = expansion.candidates.singleOrNull {
                it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
            } ?: error("No priority pass before attack declaration")
            assertTrue(world.step(pass).accepted)
        }
        error("Did not reach the declare-attackers choice")
    }

    private fun advanceThroughWarpExile(
        world: ArgentumSearchWorld,
        owner: EntityId,
        warpedNova: EntityId,
    ) {
        repeat(96) {
            if (warpedNova in world.authoritativeState().getExile(owner) &&
                world.authoritativeState().stack.isEmpty()
            ) return
            val expansion = world.expandChoices()
            val choice = expansion.candidates.singleOrNull()
                ?: expansion.candidates.singleOrNull {
                    it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
                }
                ?: expansion.candidates.singleOrNull {
                    it.actionIntent.kind == SemanticActionIntentKind.DECLINE_BLOCK
                }
                ?: error(
                    "No deterministic route to the Warp trigger at " +
                        "${world.authoritativeState().phase}/${world.authoritativeState().step}: " +
                        expansion.candidates.joinToString { it.display.label },
                )
            assertTrue(world.step(choice).accepted)
        }
        error("Warped Nova did not exile at the next end step")
    }

    private fun resolveStack(world: ArgentumSearchWorld) {
        repeat(16) {
            if (world.authoritativeState().stack.isEmpty()) return
            val pass = world.expandChoices().candidates.single {
                it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
            }
            assertTrue(world.step(pass).accepted)
        }
        error("Stack did not resolve")
    }

    private fun resolvedCast(world: ArgentumSearchWorld, choice: SemanticChoice): CastSpell? =
        (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value as? CastSpell

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

    private fun world(
        source: GameEnvironment,
        state: GameState,
        id: String,
        knownDecks: Map<String, Map<String, Int>>,
    ): ArgentumSearchWorld {
        val environment = source.fork().also { it.restore(state, source.playerIds, source.stepCount) }
        return ArgentumSearchWorld.create(
            environment,
            id,
            23_023L,
            cardRegistry = registry,
            effectiveSetupSeed = 23_023L,
            knownDecks = knownDecks,
        )
    }

    private fun mainPhase(state: GameState, player: EntityId, redMana: Int): GameState = state.copy(
        turnNumber = maxOf(1, state.turnNumber),
        activePlayerId = player,
        priorityPlayerId = player,
        phase = Phase.PRECOMBAT_MAIN,
        step = Step.PRECOMBAT_MAIN,
        priorityPassedBy = emptySet(),
        pendingDecision = null,
    ).updateEntity(player) { it.with(ManaPoolComponent(red = redMana)) }

    private fun putInHand(state: GameState, player: EntityId, card: EntityId): GameState {
        if (card in state.getHand(player)) return state
        val hand = state.getHand(player)
        val library = state.getLibrary(player)
        val displaced = hand.first { cardName(state, it) != "Nova Hellkite" }
        return state.copy(
            zones = state.zones +
                (ZoneKey(player, Zone.HAND) to hand.map { if (it == displaced) card else it }) +
                (ZoneKey(player, Zone.LIBRARY) to library.map { if (it == card) displaced else it }),
        )
    }

    private fun cardName(state: GameState, id: EntityId): String =
        requireNotNull(state.getEntity(id)?.get<CardComponent>()?.name)
}
