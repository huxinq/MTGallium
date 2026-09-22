package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdge
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DamageEdgeDirection
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ResolutionAttacker
import com.wingedsheep.engine.core.ResolutionBlocker
import com.wingedsheep.engine.core.ResolutionDefender
import com.wingedsheep.engine.core.ResolutionTargetKind
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.ExactlyOneSubmissionResult
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mtgallium.agent.infoset.core.PolicyDecisionChoiceSpec
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

/**
 * The combat request-response edge boundary: chooser-authorized contract -> policy-facing contract
 * -> canonical response payload -> native binding. These public-safe synthetic fixtures qualify the
 * reference mapping; a reachable native round trip is qualified in the private harness.
 */
class CombatEdgeReferenceTest {
    @Test
    fun `combat edge references are contract-local and invariant under native id renaming`() {
        val first = combatFixture()
        val second = combatFixture(renameNativeIds = true)

        val contractFirst = combatContract(first).contract
        val contractSecond = combatContract(second).contract
        assertEquals(contractFirst, contractSecond)

        val edgeIds = edgeIds(contractFirst)
        assertEquals(2, edgeIds.size)
        assertEquals(2, edgeIds.toSet().size)
        assertTrue(edgeIds.all { it.startsWith("combat-edge:v1:") })
        assertTrue(edgeIds.none { it.contains(first.firstEdgeNative) || it.contains(first.secondEdgeNative) })
        assertTrue(
            edgeIds.single { it.endsWith(safeReference(first.observation, first.firstBlocker)) }
                .contains(safeReference(first.observation, first.attacker)),
        )

        val responseFirst = combatResponse(first, first.firstEdgeNative to 1, first.secondEdgeNative to 0)
        val responseSecond = combatResponse(second, second.firstEdgeNative to 1, second.secondEdgeNative to 0)
        assertEquals(responseFirst.signature, responseSecond.signature)
        assertEquals(responseFirst.canonicalPayload, responseSecond.canonicalPayload)
        assertEquals(
            edgeIds.sorted(),
            responseFirst.payloadEdgeIds().sorted(),
        )
    }

    @Test
    fun `combat edge identity keeps endpoints drain role and default amounts separable`() {
        val fixture = combatFixture()
        val baseContract = combatContract(fixture).contract
        val baseEdges = edgeIds(baseContract)

        val swapped = combatSpec(
            fixture.attacker, fixture.secondBlocker, fixture.firstBlocker, fixture.chooser,
            firstEdgeId = fixture.firstEdgeNative, secondEdgeId = fixture.secondEdgeNative,
        )
        val swappedEdges = edgeIds(combatContract(fixture, swapped).contract)
        assertEquals(baseEdges.toSet(), swappedEdges.toSet())
        assertEquals(
            baseEdges.single { it.endsWith(safeReference(fixture.observation, fixture.firstBlocker)) },
            swappedEdges.single { it.endsWith(safeReference(fixture.observation, fixture.firstBlocker)) },
        )

        val drain = combatSpec(
            fixture.attacker, fixture.firstBlocker, fixture.secondBlocker, fixture.chooser,
            firstEdgeId = fixture.firstEdgeNative, secondEdgeId = fixture.secondEdgeNative,
            firstDrain = true,
        )
        val drainEdges = edgeIds(combatContract(fixture, drain).contract)
        assertNotEquals(
            baseEdges.single { it.endsWith(safeReference(fixture.observation, fixture.firstBlocker)) },
            drainEdges.single { it.endsWith(safeReference(fixture.observation, fixture.firstBlocker)) },
        )

        val amounts = combatSpec(
            fixture.attacker, fixture.firstBlocker, fixture.secondBlocker, fixture.chooser,
            firstEdgeId = fixture.firstEdgeNative, secondEdgeId = fixture.secondEdgeNative,
            firstAmount = 0, secondAmount = 1,
        )
        val amountContract = combatContract(fixture, amounts)
        assertNotEquals(baseContract, amountContract.contract)
        assertEquals(baseEdges, edgeIds(amountContract.contract))

        val unconstrained = combatSpec(
            fixture.attacker, fixture.firstBlocker, fixture.secondBlocker, fixture.chooser,
            firstEdgeId = fixture.firstEdgeNative, secondEdgeId = fixture.secondEdgeNative,
            orderConstrained = false,
        )
        assertNotEquals(baseContract, combatContract(fixture, unconstrained).contract)

        val otherChooser = combatSpec(
            fixture.attacker, fixture.firstBlocker, fixture.secondBlocker, fixture.otherPlayer,
            firstEdgeId = fixture.firstEdgeNative, secondEdgeId = fixture.secondEdgeNative,
        )
        assertNotEquals(baseContract, combatContract(fixture, otherChooser).contract)

        val original = combatResponse(fixture, fixture.firstEdgeNative to 1, fixture.secondEdgeNative to 0)
        val mirrored = combatResponse(fixture, fixture.firstEdgeNative to 0, fixture.secondEdgeNative to 1)
        assertNotEquals(original.signature, mirrored.signature)
        assertEquals(listOf(0, 1), mirrored.payloadAmounts().sorted())
    }

    @Test
    fun `unknown repeated stale and ambiguous combat edge references fail explicitly`() {
        val fixture = combatFixture()
        assertFailsWith<IllegalStateException> {
            combatResponse(fixture, "e999->e1" to 1)
        }
        assertFailsWith<IllegalArgumentException> {
            combatResponse(fixture, fixture.firstEdgeNative to 1, fixture.firstEdgeNative to 0)
        }
        assertFailsWith<IllegalArgumentException> {
            // Same direction, source and target as the first edge: one descriptor, two native ids.
            combatContract(
                fixture,
                combatSpec(
                    fixture.attacker, fixture.firstBlocker, fixture.secondBlocker, fixture.chooser,
                    firstEdgeId = fixture.firstEdgeNative, secondEdgeId = "e77->e78",
                    secondSource = fixture.attacker, secondTarget = fixture.firstBlocker,
                ),
            )
        }
        val stale = CombatResolutionResponse(
            "r",
            listOf(DamageEdgeAmount(fixture.firstEdgeNative, 1)),
        )
        val otherContract = prepared(
            fixture,
            combatSpec(
                fixture.attacker, fixture.firstBlocker, fixture.secondBlocker, fixture.chooser,
                firstEdgeId = "e60->e61", secondEdgeId = "e60->e62",
            ),
        )
        assertFailsWith<IllegalStateException> { encode(otherContract, stale) }
    }

    @Test
    fun `ordinary strings that resemble native edge ids are never parsed`() {
        val fixture = combatFixture()
        val ordinary = fixture.firstEdgeNative
        val projected = assertIs<PolicyDecisionChoiceSpec.ManaSources>(
            project(fixture.observation, manaSpec(fixture, ordinary)),
        ).contract
        assertEquals(ordinary, projected.getValue("requiredCost").jsonPrimitive.content)
        assertNotEquals(
            ordinary,
            projected.getValue("availableSources").jsonArray.single().jsonObject
                .getValue("entityId").jsonPrimitive.content,
        )

        val response = UnifiedSemanticExpander().encodePreparedChoice(
            ArgentumEngineChoice.Decision(
                ManaSourcesSelectedResponse("routing", selectedSources = listOf(fixture.attacker)),
            ),
            prepared(fixture, manaSpec(fixture, ordinary)),
        )
        assertFalse(ordinary in response.canonicalPayload.toString())
    }

    @Test
    fun `asymmetric combat response round trip binds the intended native edges`() {
        val registry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of("Raging Goblin" to 12, "Mountain" to 28)),
                        PlayerConfig("Bob", Deck.of("Raging Goblin" to 12, "Mountain" to 28)),
                    ),
                    seed = 90210L,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                ),
            )
        }
        val p0 = environment.playerIds[0]
        val p1 = environment.playerIds[1]
        var state = environment.state
        state = moveToBattlefield(state, p0, "Raging Goblin")
        state = moveToBattlefield(state, p1, "Raging Goblin")
        state = moveToBattlefield(state, p1, "Raging Goblin")
        environment.restore(state, environment.playerIds, environment.stepCount)

        val attacker = environment.state.getBattlefield(p0)
            .single { cardName(environment.state, it) == "Raging Goblin" }
        val blockers = environment.state.getBattlefield(p1)
            .filter { cardName(environment.state, it) == "Raging Goblin" }
            .take(2)
        assertEquals(2, blockers.size)

        advanceTo(environment, Step.DECLARE_ATTACKERS)
        assertIs<ExactlyOneSubmissionResult.Applied>(
            environment.stepExactlyOne(DeclareAttackers(p0, mapOf(attacker to p1))),
        )
        advanceTo(environment, Step.DECLARE_BLOCKERS)
        assertIs<ExactlyOneSubmissionResult.Applied>(
            environment.stepExactlyOne(
                DeclareBlockers(p1, mapOf(blockers[0] to listOf(attacker), blockers[1] to listOf(attacker))),
            ),
        )
        (environment.state.pendingDecision as? OrderObjectsDecision)?.let { order ->
            assertIs<ExactlyOneSubmissionResult.Applied>(
                environment.stepExactlyOne(SubmitDecision(order.playerId, OrderedResponse(order.id, blockers))),
            )
        }
        var combat: CombatResolutionDecision? = null
        var passes = 0
        while (combat == null && passes++ < 64) {
            val pending = environment.state.pendingDecision
            if (pending is CombatResolutionDecision) {
                combat = pending
                break
            }
            check(pending == null) { "Unexpected decision ${pending!!::class.simpleName}" }
            environment.step(PassPriority(requireNotNull(environment.state.priorityPlayerId)))
        }
        val decision = requireNotNull(combat) { "Combat resolution board not reached" }

        val knownDecks = mapOf(
            "p0" to mapOf("Mountain" to 28, "Raging Goblin" to 12),
            "p1" to mapOf("Mountain" to 28, "Raging Goblin" to 12),
        )
        val world = ArgentumSearchWorld.create(
            environment.fork(),
            "combat-edge-round-trip",
            90210L,
            effectiveSetupSeed = 90210L,
            knownDecks = knownDecks,
        )
        val information = world.informationState("p0")
        val contract = assertIs<PolicyDecisionChoiceSpec.CombatResolution>(
            requireNotNull(information.observation.pendingDecision?.choiceSpec),
        ).contract
        val blockerRefs = contract.getValue("blockers").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(decision.blockers.size, blockerRefs.size)

        val candidate = world.expandChoices().candidates.first { choice ->
            choice.operationFamily == SemanticOperationFamily.DECISION_RESPONSE &&
                choice.payloadAmounts().sorted() == listOf(0, 1) &&
                choice.payloadAmounts().count { it == 1 } == 1
        }
        val native = assertIs<ArgentumResolvedChoice.Decision>(world.resolveChoice(candidate)).value
        val combatResponse = assertIs<CombatResolutionResponse>(native)
        val chosenNative = combatResponse.edges.single { it.amount == 1 }
        val chosenTarget = decision.edges.single { it.id == chosenNative.edgeId }.targetId
        val chosenBlockerIndex = decision.blockers.indexOfFirst { it.id == chosenTarget }
        assertTrue(chosenBlockerIndex >= 0)
        val chosenLocal = candidate.payloadEdgeIds().single { local ->
            local.endsWith(blockerRefs[chosenBlockerIndex])
        }
        assertTrue(chosenLocal.startsWith("combat-edge:v1:"))
        assertEquals(1, candidate.payloadAmountsByEdge().getValue(chosenLocal))
        val otherLocal = candidate.payloadEdgeIds().single { it != chosenLocal }
        assertEquals(0, candidate.payloadAmountsByEdge().getValue(otherLocal))
        assertFalse(combatResponse.edges.any { it.edgeId in candidate.canonicalPayload.toString() })

        val direct = environment.fork()
        assertIs<ExactlyOneSubmissionResult.Applied>(
            direct.stepExactlyOne(SubmitDecision(decision.playerId, combatResponse)),
        )
        assertTrue(world.step(candidate).accepted)
        assertTrue(
            ArgentumStateFingerprint.routingNormalizedEquals(direct.state, world.authoritativeStateForHost()),
            "Semantic response did not execute the native binding it resolved to",
        )

        val after = world.authoritativeStateForHost()
        val otherBlocker = decision.blockers.first { it.id != chosenTarget }.id
        assertTrue(chosenTarget in after.getGraveyard(p1))
        assertTrue(otherBlocker in after.getBattlefield(p1))
        assertEquals(0, after.getEntity(otherBlocker)?.get<DamageComponent>()?.amount ?: 0)
    }

    private fun advanceTo(environment: GameEnvironment, step: Step, maxPasses: Int = 96) {
        repeat(maxPasses) {
            if (environment.state.step == step) return
            check(environment.state.pendingDecision == null) {
                "Unexpected decision before $step: ${environment.state.pendingDecision!!::class.simpleName}"
            }
            environment.step(PassPriority(requireNotNull(environment.state.priorityPlayerId)))
        }
        error("Did not reach $step")
    }

    private fun moveToBattlefield(state: GameState, player: EntityId, name: String): GameState {
        val handKey = ZoneKey(player, Zone.HAND)
        val libraryKey = ZoneKey(player, Zone.LIBRARY)
        val battlefieldKey = ZoneKey(player, Zone.BATTLEFIELD)
        val hand = state.zones[handKey].orEmpty()
        val library = state.zones[libraryKey].orEmpty()
        val fromHand = hand.any { cardName(state, it) == name }
        val sourceKey = if (fromHand) handKey else libraryKey
        val card = if (fromHand) hand.first { cardName(state, it) == name }
        else library.first { cardName(state, it) == name }
        return state.copy(
            zones = state.zones +
                (sourceKey to state.zones[sourceKey].orEmpty().filterNot { it == card }) +
                (battlefieldKey to (state.zones[battlefieldKey].orEmpty() + card)),
        )
    }

    @Test
    fun `same-descriptor blockers keep distinct edge references`() {
        val fixture = combatFixture()
        val identical = fixture.observation.zones.flatMap { it.cards }
            .filter { it.name == "Mountain" }
            .take(2)
        require(identical.size == 2) { "Fixture needs two same-descriptor visible cards" }
        val blockerA = identical[0].entityId
        val blockerB = identical[1].entityId
        assertNotEquals(
            safeReference(fixture.observation, blockerA),
            safeReference(fixture.observation, blockerB),
        )
        val spec = combatSpec(
            fixture.attacker, blockerA, blockerB, fixture.chooser,
            firstEdgeId = "e10->e11", secondEdgeId = "e10->e12",
        )
        val edges = edgeIds(combatContract(fixture, spec).contract)
        assertEquals(2, edges.size)
        assertEquals(2, edges.toSet().size)
        assertTrue(edges.any { it.endsWith(safeReference(fixture.observation, blockerA)) })
        assertTrue(edges.any { it.endsWith(safeReference(fixture.observation, blockerB)) })
    }

    private fun combatContract(
        fixture: CombatFixture,
        spec: CombatResolutionChoiceSpec = combatSpec(
            fixture.attacker, fixture.firstBlocker, fixture.secondBlocker, fixture.chooser,
            firstEdgeId = fixture.firstEdgeNative, secondEdgeId = fixture.secondEdgeNative,
        ),
    ): IdentifiedContract {
        val projected = project(fixture.observation, spec)
        return IdentifiedContract(assertIs<PolicyDecisionChoiceSpec.CombatResolution>(projected).contract)
    }

    private fun combatResponse(fixture: CombatFixture, vararg amounts: Pair<String, Int>): SemanticChoice {
        val spec = combatSpec(
            fixture.attacker, fixture.firstBlocker, fixture.secondBlocker, fixture.chooser,
            firstEdgeId = fixture.firstEdgeNative, secondEdgeId = fixture.secondEdgeNative,
        )
        return encode(
            prepared(fixture, spec),
            CombatResolutionResponse("routing", amounts.map { DamageEdgeAmount(it.first, it.second) }),
        )
    }

    private fun encode(
        prepared: PreparedSemanticExpansionInput,
        response: CombatResolutionResponse,
    ): SemanticChoice = UnifiedSemanticExpander().encodePreparedChoice(
        ArgentumEngineChoice.Decision(response),
        prepared,
    )

    private fun project(observation: TrainingObservation, choice: DecisionChoiceSpec): PolicyDecisionChoiceSpec {
        val refs = SafeReferenceMap(observation).also { it.admitAuthorizedChoiceReferences(choice) }
        return SafeObservationProjector().projectChoice(choice, refs)
    }

    private fun prepared(
        fixture: CombatFixture,
        choice: DecisionChoiceSpec,
    ): PreparedSemanticExpansionInput {
        val visible = SafeObservationProjector().project(fixture.observation)
        val references = SafeReferenceMap(fixture.observation)
            .also { it.admitAuthorizedChoiceReferences(choice) }
        return PreparedSemanticExpansionInput(
            actor = fixture.chooser,
            legalActions = emptyList(),
            observation = fixture.observation,
            projection = SafeObservationProjection(visible.observation, references),
        )
    }

    private fun safeReference(observation: TrainingObservation, entityId: EntityId): String =
        SafeReferenceMap(observation).reference(entityId)

    private fun edgeIds(contract: JsonObject): List<String> = contract.getValue("edges").jsonArray.map {
        it.jsonObject.getValue("id").jsonPrimitive.content
    }

    private fun SemanticChoice.payloadEdgeIds(): List<String> = canonicalPayload
        .getValue("body").jsonObject.getValue("edges").jsonArray
        .map { it.jsonObject.getValue("edgeId").jsonPrimitive.content }

    private fun SemanticChoice.payloadAmounts(): List<Int> = canonicalPayload
        .getValue("body").jsonObject.getValue("edges").jsonArray
        .map { it.jsonObject.getValue("amount").jsonPrimitive.content.toInt() }

    private fun SemanticChoice.payloadAmountsByEdge(): Map<String, Int> = canonicalPayload
        .getValue("body").jsonObject.getValue("edges").jsonArray
        .associate { edge ->
            edge.jsonObject.getValue("edgeId").jsonPrimitive.content to
                edge.jsonObject.getValue("amount").jsonPrimitive.content.toInt()
        }

    private fun manaSpec(fixture: CombatFixture, ordinaryCost: String) = ManaSourcesChoiceSpec(
        availableSources = listOf(
            ManaSourceChoice(fixture.attacker, "Mountain", listOf(Color.RED), false, false, false),
        ),
        requiredCost = ordinaryCost,
        autoPaySuggestion = listOf(fixture.attacker),
        canDecline = false,
        waterbendPermanents = emptyList(),
    )

    private class IdentifiedContract(val contract: JsonObject)

    private data class CombatFixture(
        val observation: TrainingObservation,
        val attacker: EntityId,
        val firstBlocker: EntityId,
        val secondBlocker: EntityId,
        val chooser: EntityId,
        val otherPlayer: EntityId,
    ) {
        val firstEdgeNative: String get() = "${attacker.value}->${firstBlocker.value}"
        val secondEdgeNative: String get() = "${attacker.value}->${secondBlocker.value}"
    }

    private fun combatSpec(
        attacker: EntityId,
        firstBlocker: EntityId,
        secondBlocker: EntityId,
        chooser: EntityId,
        firstEdgeId: String,
        secondEdgeId: String,
        firstAmount: Int = 1,
        secondAmount: Int = 0,
        firstDrain: Boolean = false,
        orderConstrained: Boolean = true,
        secondSource: EntityId = attacker,
        secondTarget: EntityId = secondBlocker,
    ) = CombatResolutionChoiceSpec(
        firstStrike = false,
        attackers = listOf(
            ResolutionAttacker(
                id = attacker,
                name = "Raging Goblin",
                power = 1,
                toughness = 1,
                hasTrample = false,
                hasDeathtouch = false,
                hasFirstStrike = false,
                hasDoubleStrike = false,
                dealsDamageThisStep = true,
                bandId = null,
                attackedDefenderId = chooser,
                blockedByIds = listOf(firstBlocker, secondBlocker),
                markedDamage = 0,
            ),
        ),
        blockers = listOf(
            ResolutionBlocker(
                firstBlocker, "Raging Goblin", 1, 1, false, false, false, true,
                listOf(attacker), listOf(attacker), 0,
            ),
            ResolutionBlocker(
                secondBlocker, "Raging Goblin", 1, 1, false, false, false, true,
                listOf(attacker), listOf(attacker), 0,
            ),
        ),
        defenders = listOf(ResolutionDefender(chooser, ResolutionTargetKind.PLAYER, "Alice", 20)),
        edges = listOf(
            DamageEdge(
                id = firstEdgeId,
                sourceId = attacker,
                targetId = firstBlocker,
                direction = DamageEdgeDirection.ATTACKER_TO_BLOCKER,
                amount = firstAmount,
                maximum = 1,
                lethal = 1,
                orderConstrained = orderConstrained,
                isTrampleDrain = firstDrain,
                editableBy = chooser,
            ),
            DamageEdge(
                id = secondEdgeId,
                sourceId = secondSource,
                targetId = secondTarget,
                direction = DamageEdgeDirection.ATTACKER_TO_BLOCKER,
                amount = secondAmount,
                maximum = 1,
                lethal = 1,
                orderConstrained = orderConstrained,
                isTrampleDrain = false,
                editableBy = chooser,
            ),
        ),
        coChooserId = null,
    )

    private fun combatFixture(renameNativeIds: Boolean = false): CombatFixture {
        val registry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of("Raging Goblin" to 4, "Goblin Bully" to 4, "Hulking Goblin" to 4, "Mountain" to 28)),
                        PlayerConfig("Bob", Deck.of("Mountain" to 40)),
                    ),
                    seed = 611L,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                ),
            )
        }
        val chooser = environment.playerIds[0]
        val otherPlayer = environment.playerIds[1]
        val state = forceHand(environment.state, chooser, listOf("Raging Goblin", "Goblin Bully", "Hulking Goblin"))
        val visible = ObservationBuilder(registry).build(state, chooser, emptyList())
            .observation as TrainingObservation
        val named = listOf("Raging Goblin", "Goblin Bully", "Hulking Goblin").map { name ->
            visible.zones.flatMap { it.cards }.first { it.name == name }
        }
        val original = named.map { it.entityId }
        val renamed = if (!renameNativeIds) original else listOf(
            EntityId("native-e40"),
            EntityId("native-e60"),
            EntityId("native-e80"),
        )
        return CombatFixture(
            observation = visible.renamed(original.zip(renamed).toMap()),
            attacker = renamed[0],
            firstBlocker = renamed[1],
            secondBlocker = renamed[2],
            chooser = chooser,
            otherPlayer = otherPlayer,
        )
    }

    private fun forceHand(state: GameState, player: EntityId, names: List<String>): GameState {
        val hand = state.getHand(player).toMutableList()
        val library = state.getLibrary(player).toMutableList()
        names.forEachIndexed { index, name ->
            if (hand.any { cardName(state, it) == name }) return@forEachIndexed
            val fromLibrary = requireNotNull(library.firstOrNull { cardName(state, it) == name })
            val displaced = hand[index]
            hand[index] = fromLibrary
            library[library.indexOf(fromLibrary)] = displaced
        }
        return state.copy(
            zones = state.zones +
                (ZoneKey(player, Zone.HAND) to hand) +
                (ZoneKey(player, Zone.LIBRARY) to library),
        )
    }

    private fun TrainingObservation.renamed(rename: Map<EntityId, EntityId>): TrainingObservation = copy(
        zones = zones.map { zone ->
            zone.copy(cards = zone.cards.map { card ->
                rename[card.entityId]?.let { card.copy(entityId = it) } ?: card
            })
        },
    )

    private fun cardName(state: GameState, id: EntityId): String =
        requireNotNull(state.getEntity(id)?.get<CardComponent>()?.name)
}
