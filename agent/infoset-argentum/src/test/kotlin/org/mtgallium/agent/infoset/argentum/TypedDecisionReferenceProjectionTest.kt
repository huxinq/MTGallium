package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.BudgetModeOption
import com.wingedsheep.engine.core.ConditionalSelectionMinimum
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdge
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DamageEdgeDirection
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.OptionMetadata
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ResolutionAttacker
import com.wingedsheep.engine.core.ResolutionBlocker
import com.wingedsheep.engine.core.ResolutionDefender
import com.wingedsheep.engine.core.ResolutionTargetKind
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.WaterbendPermanentChoice
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.BatchYesNoChoiceSpec
import com.wingedsheep.gym.contract.BudgetModesChoiceSpec
import com.wingedsheep.gym.contract.CardsChoiceSpec
import com.wingedsheep.gym.contract.ColorsChoiceSpec
import com.wingedsheep.gym.contract.CombatResolutionChoiceSpec
import com.wingedsheep.gym.contract.DamageAssignmentChoiceSpec
import com.wingedsheep.gym.contract.DecisionChoiceSpec
import com.wingedsheep.gym.contract.DistributionChoiceSpec
import com.wingedsheep.gym.contract.LibraryReorderChoiceSpec
import com.wingedsheep.gym.contract.LibrarySearchChoiceSpec
import com.wingedsheep.gym.contract.ManaSourceChoice
import com.wingedsheep.gym.contract.ManaSourcesChoiceSpec
import com.wingedsheep.gym.contract.ModesChoiceSpec
import com.wingedsheep.gym.contract.NumberChoiceSpec
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.OptionsChoiceSpec
import com.wingedsheep.gym.contract.OrderChoiceSpec
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PendingDecisionView
import com.wingedsheep.gym.contract.PilesChoiceSpec
import com.wingedsheep.gym.contract.ReplacementChoiceSpec
import com.wingedsheep.gym.contract.TargetsChoiceSpec
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.contract.YesNoChoiceSpec
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mtgallium.agent.infoset.core.PolicyDecisionChoiceSpec
import org.mtgallium.agent.infoset.core.PolicyJson

class TypedDecisionReferenceProjectionTest {
    @Test
    fun `all eighteen pending-decision schemas admit only their typed entity positions`() {
        val (base, actor) = observationWithMaliciousVisibleId()
        val hidden = EntityId("hidden-choice-reference")
        val info = SearchCardInfo("Known choice", "{1}", "Creature")
        val metadata = OptionMetadata(id = "edges", description = "edges", iconKey = "edges")
        val specs = listOf(
            PendingDecisionKind.CHOOSE_TARGETS to TargetsChoiceSpec(
                listOf(TargetRequirementInfo(0, "edges")),
                mapOf(0 to listOf(hidden)),
                canCancel = true,
            ),
            PendingDecisionKind.SELECT_CARDS to CardsChoiceSpec(
                options = listOf(hidden),
                minSelections = 0,
                maxSelections = 1,
                ordered = false,
                cardInfo = mapOf(hidden to info),
                selectedLabel = "edges",
                remainderLabel = "edges",
                nonSelectableOptions = listOf(hidden),
                conditionalMinimums = listOf(
                    ConditionalSelectionMinimum(1, 1, listOf(hidden), description = "edges")
                ),
            ),
            PendingDecisionKind.YES_NO to YesNoChoiceSpec("edges", "No", "edges"),
            PendingDecisionKind.BATCH_YES_NO to BatchYesNoChoiceSpec(2, "edges", "No"),
            PendingDecisionKind.CHOOSE_MODE to ModesChoiceSpec(
                listOf(ModeOption(0, "edges")),
                minModes = 1,
                maxModes = 1,
            ),
            PendingDecisionKind.CHOOSE_COLOR to ColorsChoiceSpec(listOf(Color.RED)),
            PendingDecisionKind.CHOOSE_NUMBER to NumberChoiceSpec(0, 3),
            PendingDecisionKind.DISTRIBUTE to DistributionChoiceSpec(
                2,
                listOf(hidden),
                0,
                mapOf(hidden to 2),
                false,
            ),
            PendingDecisionKind.ORDER_OBJECTS to OrderChoiceSpec(
                listOf(hidden),
                mapOf(hidden to info),
            ),
            PendingDecisionKind.SPLIT_PILES to PilesChoiceSpec(
                listOf(hidden),
                2,
                listOf("edges", "Other"),
                mapOf(hidden to info),
            ),
            PendingDecisionKind.CHOOSE_OPTION to OptionsChoiceSpec(
                options = listOf("edges"),
                defaultSearch = "edges",
                optionCardIds = mapOf(0 to listOf(hidden)),
                optionMetadata = listOf(metadata),
                canCancel = true,
            ),
            PendingDecisionKind.CHOOSE_REPLACEMENT to ReplacementChoiceSpec(
                fromOptions = listOf("edges"),
                toOptions = listOf("Other"),
                fromMetadata = listOf(metadata),
                toMetadata = listOf(OptionMetadata(id = "Other")),
                allowedToByFrom = listOf(listOf(0)),
                defaultFromIndex = 0,
            ),
            PendingDecisionKind.SEARCH_LIBRARY to LibrarySearchChoiceSpec(
                listOf(hidden),
                0,
                1,
                mapOf(hidden to info),
                "edges",
            ),
            PendingDecisionKind.REORDER_LIBRARY to LibraryReorderChoiceSpec(
                listOf(hidden),
                mapOf(hidden to info),
            ),
            PendingDecisionKind.ASSIGN_DAMAGE to DamageAssignmentChoiceSpec(
                hidden,
                2,
                listOf(hidden),
                actor,
                mapOf(hidden to 1),
                mapOf(hidden to 2),
                hasTrample = true,
                hasDeathtouch = false,
            ),
            PendingDecisionKind.COMBAT_RESOLUTION to combatSpec(hidden, actor),
            PendingDecisionKind.SELECT_MANA_SOURCES to ManaSourcesChoiceSpec(
                availableSources = listOf(
                    ManaSourceChoice(hidden, "edges", listOf(Color.RED), false, false, false)
                ),
                requiredCost = "edges",
                autoPaySuggestion = listOf(hidden),
                canDecline = true,
                waterbendPermanents = listOf(WaterbendPermanentChoice(hidden, "edges", true)),
            ),
            PendingDecisionKind.BUDGET_MODAL to BudgetModesChoiceSpec(
                3,
                listOf(BudgetModeOption(1, "edges")),
            ),
        )

        val projectedTypes = specs.map { (kind, spec) ->
            val projected = project(base, actor, kind, spec)
            val choice = assertNotNull(projected.pendingDecision?.choiceSpec)
            val encoded = PolicyJson.format.encodeToString(PolicyDecisionChoiceSpec.serializer(), choice)
            assertFalse("hidden-choice-reference" in encoded, "raw typed reference leaked for $kind")
            choice::class.simpleName
        }

        assertEquals(18, specs.size)
        assertEquals(18, projectedTypes.toSet().size)
    }

    @Test
    fun `combat mana and option ordinary strings named like entities are never rewritten`() {
        val (base, actor) = observationWithMaliciousVisibleId()
        val hidden = EntityId("hidden-choice-reference")

        val combat = assertIs<PolicyDecisionChoiceSpec.CombatResolution>(
            project(base, actor, PendingDecisionKind.COMBAT_RESOLUTION, combatSpec(hidden, actor))
                .pendingDecision?.choiceSpec
        ).contract
        assertNotNull(combat["edges"])
        val combatEdge = combat.getValue("edges").jsonArray.single().jsonObject
        assertEquals("edges", combatEdge.getValue("id").jsonPrimitive.content)
        assertNotEquals("hidden-choice-reference", combatEdge.getValue("sourceId").jsonPrimitive.content)

        val mana = assertIs<PolicyDecisionChoiceSpec.ManaSources>(
            project(
                base,
                actor,
                PendingDecisionKind.SELECT_MANA_SOURCES,
                ManaSourcesChoiceSpec(
                    listOf(ManaSourceChoice(hidden, "edges", listOf(Color.RED), false, false, false)),
                    "edges",
                    listOf(hidden),
                    false,
                    listOf(WaterbendPermanentChoice(hidden, "edges", true)),
                ),
            ).pendingDecision?.choiceSpec
        ).contract
        val manaSource = mana.getValue("availableSources").jsonArray.single().jsonObject
        assertEquals("edges", manaSource.getValue("name").jsonPrimitive.content)
        assertEquals("edges", mana.getValue("requiredCost").jsonPrimitive.content)

        val options = assertIs<PolicyDecisionChoiceSpec.Options>(
            project(
                base,
                actor,
                PendingDecisionKind.CHOOSE_OPTION,
                OptionsChoiceSpec(listOf("edges"), "edges", mapOf(0 to listOf(hidden)), emptyList(), false),
            ).pendingDecision?.choiceSpec
        )
        assertEquals(listOf("edges"), options.options)
        assertEquals("edges", options.defaultSearch)
    }

    @Test
    fun `combat and mana responses rewrite only typed entity positions`() {
        val (base, actor) = observationWithMaliciousVisibleId()
        val hidden = EntityId("hidden-choice-reference")
        val combatPrepared = prepared(
            base,
            actor,
            PendingDecisionKind.COMBAT_RESOLUTION,
            combatSpec(hidden, actor),
        )
        val combatBody = UnifiedSemanticExpander().encodePreparedChoice(
            ArgentumEngineChoice.Decision(
                CombatResolutionResponse(
                    decisionId = "routing",
                    edges = listOf(DamageEdgeAmount("edges", 2)),
                    orderedBlockers = mapOf(hidden to listOf(hidden)),
                    orderedAttackers = mapOf(hidden to listOf(hidden)),
                )
            ),
            combatPrepared,
        ).canonicalPayload.getValue("body").jsonObject

        assertEquals(
            "edges",
            combatBody.getValue("edges").jsonArray.single().jsonObject
                .getValue("edgeId").jsonPrimitive.content,
        )
        assertFalse("hidden-choice-reference" in combatBody.toString())

        val manaPrepared = prepared(
            base,
            actor,
            PendingDecisionKind.SELECT_MANA_SOURCES,
            ManaSourcesChoiceSpec(
                listOf(ManaSourceChoice(hidden, "edges", listOf(Color.RED), false, false, false)),
                "edges",
                listOf(hidden),
                false,
                listOf(WaterbendPermanentChoice(hidden, "edges", true)),
            ),
        )
        val manaBody = UnifiedSemanticExpander().encodePreparedChoice(
            ArgentumEngineChoice.Decision(
                ManaSourcesSelectedResponse(
                    decisionId = "routing",
                    selectedSources = listOf(hidden),
                    waterbendPermanents = setOf(hidden),
                )
            ),
            manaPrepared,
        ).canonicalPayload.getValue("body").jsonObject

        assertFalse("hidden-choice-reference" in manaBody.toString())
    }

    @Test
    fun `action identity rewrites typed source but not an ability id with the same bytes`() {
        val (observation, actor) = observationWithMaliciousVisibleId()
        val projection = SafeObservationProjector().project(observation)
        val action = ActivateAbility(
            playerId = actor,
            sourceId = EntityId("edges"),
            abilityId = AbilityId("edges"),
        )
        val encoded = UnifiedSemanticExpander().encodePreparedChoice(
            ArgentumEngineChoice.Action(action),
            PreparedSemanticExpansionInput(actor, emptyList(), observation, projection),
        ).canonicalPayload.getValue("body").jsonObject

        assertNotEquals("edges", encoded.getValue("sourceId").jsonPrimitive.content)
        assertEquals("edges", encoded.getValue("abilityId").jsonPrimitive.content)
    }

    private fun project(
        base: TrainingObservation,
        actor: EntityId,
        kind: PendingDecisionKind,
        choice: DecisionChoiceSpec,
    ) = prepared(base, actor, kind, choice).projection.observation

    private fun prepared(
        base: TrainingObservation,
        actor: EntityId,
        kind: PendingDecisionKind,
        choice: DecisionChoiceSpec,
    ): PreparedSemanticExpansionInput {
        val observation = base.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "routing",
                kind = kind,
                playerId = actor,
                prompt = "edges",
                choiceSpec = choice,
            ),
        )
        return PreparedSemanticExpansionInput(
            actor = actor,
            legalActions = emptyList(),
            observation = observation,
            projection = SafeObservationProjector().project(observation),
        )
    }

    private fun combatSpec(hidden: EntityId, actor: EntityId) = CombatResolutionChoiceSpec(
        firstStrike = false,
        attackers = listOf(
            ResolutionAttacker(
                id = hidden,
                name = "edges",
                power = 2,
                toughness = 2,
                hasTrample = false,
                hasDeathtouch = false,
                hasFirstStrike = false,
                hasDoubleStrike = false,
                dealsDamageThisStep = true,
                bandId = "edges",
                attackedDefenderId = actor,
                blockedByIds = listOf(hidden),
                markedDamage = 0,
            )
        ),
        blockers = listOf(
            ResolutionBlocker(hidden, "edges", 2, 2, false, false, false, true, listOf(hidden), listOf(hidden), 0)
        ),
        defenders = listOf(ResolutionDefender(actor, ResolutionTargetKind.PLAYER, "edges", 20)),
        edges = listOf(
            DamageEdge(
                id = "edges",
                sourceId = hidden,
                targetId = hidden,
                direction = DamageEdgeDirection.ATTACKER_TO_BLOCKER,
                amount = 2,
                maximum = 2,
                lethal = 2,
                orderConstrained = true,
                isTrampleDrain = false,
                editableBy = actor,
            )
        ),
        coChooserId = actor,
    )

    private fun observationWithMaliciousVisibleId(): Pair<TrainingObservation, EntityId> {
        val registry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of("Mountain" to 17, "Raging Goblin" to 3)),
                        PlayerConfig("Bob", Deck.of("Mountain" to 17, "Raging Goblin" to 3)),
                    ),
                    seed = 611L,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                )
            )
        }
        val actor = environment.playerIds[0]
        val original = ObservationBuilder(registry).build(environment.state, actor, emptyList()).observation
            as TrainingObservation
        val visible = original.zones.asSequence().flatMap { it.cards.asSequence() }.first()
        val zones = original.zones.map { zone ->
            zone.copy(cards = zone.cards.map { card ->
                if (card.entityId == visible.entityId) card.copy(entityId = EntityId("edges")) else card
            })
        }
        return original.copy(zones = zones) to actor
    }
}
