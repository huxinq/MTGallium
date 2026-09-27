package org.mtgallium.agent.argentum.policy

import org.mtgallium.agent.monored.ValueEvaluationException
import org.mtgallium.agent.value.*
import org.mtgallium.agent.monored.ValueEvaluationStop
import java.util.Base64
import kotlin.math.ln1p
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.BeliefApproximation
import org.mtgallium.agent.infoset.planning.ParticleSet
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.planning.InformationSetSearchConfig
import org.mtgallium.agent.infoset.planning.LeafEvaluationConfig
import org.mtgallium.agent.infoset.planning.LeafEvaluationMethod
import org.mtgallium.agent.infoset.planning.LeafValueSource
import org.mtgallium.agent.infoset.planning.RolloutCutoff
import org.mtgallium.agent.infoset.core.ObservedEventDetail
import org.mtgallium.agent.infoset.core.EventAudience
import org.mtgallium.agent.infoset.core.EventAudienceScope
import org.mtgallium.agent.infoset.core.ObjectView
import org.mtgallium.agent.infoset.core.CombatView
import org.mtgallium.agent.infoset.core.HistoryHashChain
import org.mtgallium.agent.infoset.core.ObservedEvent
import org.mtgallium.agent.infoset.core.ObservedEventKind
import org.mtgallium.agent.infoset.core.InformationStateRepresentation
import org.mtgallium.agent.infoset.core.DecisionContext
import org.mtgallium.agent.infoset.core.MenuRequest
import org.mtgallium.agent.infoset.core.InformationState
import org.mtgallium.agent.infoset.core.CanonicalJson
import org.mtgallium.agent.infoset.core.KnownLibraryOrder
import org.mtgallium.agent.infoset.core.KnownObject
import org.mtgallium.agent.infoset.core.PlayerKnowledge
import org.mtgallium.agent.infoset.core.ManaPoolView
import org.mtgallium.agent.infoset.core.PlayerObservationSnapshot
import org.mtgallium.agent.infoset.core.PendingDecisionView
import org.mtgallium.agent.infoset.core.PlayerView
import org.mtgallium.agent.infoset.core.StackObjectView
import org.mtgallium.agent.infoset.core.ZoneKnowledge
import org.mtgallium.agent.infoset.core.ZoneView
import org.mtgallium.agent.infoset.core.ReturnSource
import org.mtgallium.agent.infoset.planning.SearchStepResult
import org.mtgallium.agent.infoset.planning.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.PendingDecisionOptions
import org.mtgallium.agent.infoset.core.ActionMenu
import org.mtgallium.agent.infoset.core.Weighted

class LearnedOutcomeDeploymentTest {
    private val learnedLeaf = LeafEvaluationConfig(
        LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
        RolloutCutoff.EVALUATE,
    )

    @Test
    fun `a supplied scorer is the explicit value source`() {
        val evaluator = evaluator()
        val source = LeafValueSource.Information(evaluator)
        assertEquals(evaluator.configurationId, source.invokedEvaluatorConfigurationId)
        assertEquals(evaluator.id, source.invokedEvaluatorId)
    }

    @Test
    fun `checkpoint observer preserves inference authority and records the exact evaluated state`() {
        val evaluator = evaluator()
        val calls = mutableListOf<Triple<InformationStateRepresentation, String, Double>>()
        val observed = evaluator.observedBy { information, rootPlayer, value ->
            calls += Triple(information, rootPlayer, value)
        }
        val input = state()

        val source = LeafValueSource.Information(observed)
        val value = observed.evaluate(input, "p0")

        assertEquals(evaluator.configurationId, source.invokedEvaluatorConfigurationId)
        assertEquals(evaluator.evaluate(input, "p0"), value)
        assertEquals(listOf(Triple(input, "p0", value)), calls)
    }

    @Test
    fun `core terminal payoff bypasses model while nonterminal leaves invoke it`() {
        val evaluator = evaluator()
        val terminal = search(evaluator).search(
            rootPlayer = "p0",
            belief = belief(LearnedTestWorld(terminalAfterStep = true)),
            searchSeed = 11L,
        )
        val nonterminal = search(evaluator).search(
            rootPlayer = "p0",
            belief = belief(LearnedTestWorld(terminalAfterStep = false)),
            searchSeed = 12L,
        )

        assertEquals(1.0, terminal.rootValue)
        assertEquals(0, terminal.diagnostics.evaluatorCalls)
        assertTrue(terminal.candidateSettlementCounts.values.all {
            it.successfulBackups == 1 && it.terminalPayoffBackups == 1
        })
        assertEquals(1, nonterminal.diagnostics.evaluatorCalls)
        assertTrue(nonterminal.candidateSettlementCounts.values.all {
            it.successfulBackups == 1 && it.learnedOutcomeEstimateBackups == 1 &&
                it.heuristicSettlementBackups == 0
        })
    }

    @Test
    fun `leaf input failure aborts search instead of becoming a strategic value`() {
        val failure = assertFailsWith<ValueEvaluationException> {
            search(evaluator()).search(
                rootPlayer = "p0",
                belief = belief(
                    LearnedTestWorld(
                        terminalAfterStep = false,
                        incompleteLeafTurnState = true,
                    )
                ),
                searchSeed = 13L,
            )
        }

        assertEquals(
            ValueInputError.INPUT_CURRENT_TURN_STATE_INCOMPLETE,
            failure.kind,
        )
    }

    private fun search(evaluator: LinearValueEvaluator) =
        modelTestSearch(
            config = InformationSetSearchConfig(simulations = 1, leaf = learnedLeaf),
            valueSource = LeafValueSource.Information(evaluator),
        )

    private fun evaluator(
        weights: Map<String, Double>? = null,
    ): LinearValueEvaluator {
        val defaultKey = ValueFeatures.compile(state(), "p0").values.keys.first()
        return LinearValueEvaluator(
            checkpoint(weights = weights ?: mapOf(defaultKey to 0.1))
        )
    }

    private fun checkpoint(
        weights: Map<String, Double>,
    ): LinearWeights = LinearWeights(
        bias = 0.0,
        weights = weights,
    )

    private fun identity(name: String, digit: Char): String =
        "$name-sha256:${digit.toString().repeat(64)}"

    private fun belief(world: SearchWorld): ParticleSet<Weighted<SearchWorld>> = ParticleSet(
        particles = listOf(Weighted(world, 1.0)),
        diagnostics = BeliefDiagnostics(
            mode = BeliefMode.CONSISTENCY_ONLY_V1,
            requestedParticles = 1,
            acceptedParticles = 1,
            rejectedParticles = 0,
            effectiveSampleSizeBefore = 1.0,
            effectiveSampleSizeAfter = 1.0,
            entropy = 0.0,
            resamplingCount = 0,
            architecture = BeliefApproximation.SEQUENTIAL_B_V1,
        ),
    )

    private fun state(
        rootPlayer: String = "p0",
        opponentPlayer: String = "p1",
        actor: String = rootPlayer,
        excludedSalt: String = "stable",
        currentTurnStateComplete: Boolean = true,
        repeatedHistoryCount: Int = 1,
        repeatedPermanentCount: Int = 1,
    ): InformationStateRepresentation {
        val history = List(repeatedHistoryCount) { eventIndex ->
            ObservedEvent(
                eventId = eventIndex.toLong(),
                audience = EventAudience(EventAudienceScope.PUBLIC),
                actor = rootPlayer,
                kind = ObservedEventKind.DAMAGE,
                payload = buildJsonObject { put("excluded", JsonPrimitive(excludedSalt)) },
                detail = ObservedEventDetail.Damage(
                    sourceName = "Shock",
                    sourceObjectRef = "source-$eventIndex-$excludedSalt",
                    targetName = "Opponent",
                    targetObjectRef = opponentPlayer,
                    amount = 2,
                    combat = false,
                ),
            )
        }
        val rootCard = card(
            ref = "root-card-$excludedSalt",
            name = "Shock",
            owner = rootPlayer,
            zone = "HAND",
            excludedSalt = excludedSalt,
        )
        val opponentHidden = card(
            ref = "opponent-hidden-$excludedSalt",
            name = if (excludedSalt == "second") "Mountain" else "Shock",
            owner = opponentPlayer,
            zone = "HAND",
            excludedSalt = excludedSalt,
        )
        val permanents = List(repeatedPermanentCount) { permanentIndex ->
            card(
                ref = "permanent-$permanentIndex-$excludedSalt",
                name = "Mountain",
                owner = rootPlayer,
                zone = "BATTLEFIELD",
                excludedSalt = excludedSalt,
                types = setOf("LAND"),
            )
        }
        val observation = PlayerObservationSnapshot(
            viewerId = rootPlayer,
            turnNumber = 4,
            phase = "PRECOMBAT_MAIN",
            step = "PRECOMBAT_MAIN",
            activePlayerId = rootPlayer,
            priorityPlayerId = actor,
            players = listOf(
                PlayerView(
                    playerId = rootPlayer,
                    name = "Root",
                    life = 14,
                    handSize = 1,
                    librarySize = 40,
                    graveyardSize = 2,
                    exileSize = 0,
                    mana = ManaPoolView(red = 1),
                    active = true,
                    priority = actor == rootPlayer,
                    lost = false,
                    noncreatureSpellsCastThisTurn = 1,
                    redNoncombatDamageDealtThisTurn = 2,
                    landPlaysRemainingThisTurn = 1,
                ),
                PlayerView(
                    playerId = opponentPlayer,
                    name = "Opponent",
                    life = 12,
                    handSize = 1,
                    librarySize = 40,
                    graveyardSize = 1,
                    exileSize = 0,
                    mana = ManaPoolView(),
                    active = false,
                    priority = actor == opponentPlayer,
                    lost = false,
                ),
            ),
            zones = listOf(
                ZoneView(rootPlayer, "HAND", hidden = true, size = 1, cards = listOf(rootCard)),
                ZoneView(opponentPlayer, "HAND", hidden = true, size = 1, cards = listOf(opponentHidden)),
                ZoneView(
                    rootPlayer,
                    "BATTLEFIELD",
                    hidden = false,
                    size = repeatedPermanentCount,
                    cards = permanents,
                ),
                ZoneView(opponentPlayer, "BATTLEFIELD", hidden = false, size = 0, cards = emptyList()),
            ),
            stack = listOf(
                StackObjectView(
                    objectRef = "stack-$excludedSalt",
                    controllerId = rootPlayer,
                    name = "Shock",
                    kind = "SPELL",
                    oracleText = "excluded-$excludedSalt",
                    targets = listOf("stack-target-$excludedSalt"),
                )
            ),
            combat = CombatView(
                attackingPlayerId = rootPlayer,
                attackers = emptyList(),
                blockers = emptyList(),
            ),
            currentTurnStateComplete = currentTurnStateComplete,
            pendingDecision = PendingDecisionView(
                decisionKind = "ChooseTargets",
                playerId = actor,
                prompt = "excluded-$excludedSalt",
                sourceObjectRef = "decision-source-$excludedSalt",
                sourceName = "Shock",
                phase = "PRECOMBAT_MAIN",
                subjectObjectRef = "decision-subject-$excludedSalt",
                canRespond = true,
                choiceSpec = PendingDecisionOptions.Targets(
                    requirements = kotlinx.serialization.json.JsonArray(emptyList()),
                    legalTargets = mapOf(0 to listOf("target-$excludedSalt")),
                    canCancel = false,
                ),
            ),
            observationDigest = "excluded-observation-$excludedSalt",
        )
        val knowledge = PlayerKnowledge(
            viewerId = rootPlayer,
            deckCardCounts = mapOf(
                rootPlayer to mapOf("Mountain" to 20, "Shock" to 4),
                opponentPlayer to mapOf("Mountain" to 20, "Shock" to 4),
            ),
            zones = listOf(
                ZoneKnowledge(rootPlayer, "HAND", 1, mapOf("Shock" to 1)),
                ZoneKnowledge(opponentPlayer, "HAND", 1),
            ),
            knownObjects = listOf(
                KnownObject("knowledge-$excludedSalt", rootPlayer, "HAND", "Shock")
            ),
            knownLibraryOrders = listOf(
                KnownLibraryOrder(rootPlayer, 0, top = listOf("Mountain"))
            ),
            unlocatedCardCounts = mapOf(
                rootPlayer to mapOf("Mountain" to 19, "Shock" to 3),
                opponentPlayer to mapOf("Mountain" to 20, "Shock" to 4),
            ),
            isComplete = true,
            unsupportedReasons = emptyList(),
            knowledgeDigest = "excluded-knowledge-$excludedSalt",
        )
        return InformationStateRepresentation(
            actingPlayerId = actor,
            observation = observation,
            informationStateDigest = "excluded-information-$excludedSalt",
            historyCommitment = HistoryHashChain.replay(history),
            history = history,
            knowledge = knowledge,
            candidates = listOf(choice("candidate-$excludedSalt")),
            terminated = false,
        )
    }

    private fun card(
        ref: String,
        name: String,
        owner: String,
        zone: String,
        excludedSalt: String,
        types: Set<String> = emptySet(),
    ): ObjectView = ObjectView(
        objectRef = ref,
        definitionId = "excluded-definition-$excludedSalt",
        name = name,
        zone = zone,
        ownerId = owner,
        controllerId = owner,
        types = types,
        subtypes = emptySet(),
        colors = setOf("RED"),
        keywords = emptySet(),
        manaCost = if (name == "Shock") "{R}" else "",
        manaValue = if (name == "Shock") 1 else 0,
        oracleText = "excluded-oracle-$excludedSalt",
        power = null,
        toughness = null,
        tapped = false,
        summoningSick = false,
        faceDown = false,
        damageMarked = 0,
        counters = emptyMap(),
        attachedTo = "excluded-attached-$excludedSalt",
        attachments = listOf("excluded-attachment-$excludedSalt"),
    )

    private fun choice(label: String): SemanticChoice = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.OTHER,
        display = SemanticChoiceDisplay(label),
        canonicalPayload = buildJsonObject { put("excluded", JsonPrimitive(label)) },
    )

    private inner class LearnedTestWorld(
        private val terminalAfterStep: Boolean,
        private val incompleteLeafTurnState: Boolean = false,
        private var depth: Int = 0,
    ) : SearchWorld {
        override fun actorToAct(): String? = if (depth == 0) "p0" else "p1"

        override fun decisionContext(view: MenuRequest): DecisionContext {
            val actor = requireNotNull(actorToAct())
            val information = informationState(actor)
            return DecisionContext.capture(actor, expandChoices(), { InformationState.capture(information) }, view)
        }

        override fun informationState(viewer: String): InformationStateRepresentation {
            return state(
                rootPlayer = viewer,
                opponentPlayer = if (viewer == "p0") "p1" else "p0",
                actor = actorToAct()!!,
                currentTurnStateComplete = !(depth > 0 && incompleteLeafTurnState),
            )
        }

        override fun expandChoices(): ActionMenu = if (depth == 0) {
            ActionMenu(
                candidates = listOf(choice("advance")),
                isExhaustive = true,
                estimatedCandidateCount = 1,
                proposalVersion = "learned-test-v1",
            )
        } else {
            ActionMenu(
                candidates = emptyList(),
                isExhaustive = true,
                estimatedCandidateCount = 0,
                proposalVersion = "learned-test-v1",
            )
        }

        override fun step(choice: SemanticChoice): SearchStepResult {
            depth++
            return SearchStepResult(accepted = true)
        }

        override fun fork(): SearchWorld = LearnedTestWorld(
            terminalAfterStep,
            incompleteLeafTurnState,
            depth,
        )

        override fun terminalPayoff(rootPlayer: String): Double? =
            1.0.takeIf { terminalAfterStep && depth > 0 }


    }
}
