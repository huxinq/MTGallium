package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.ai.engine.TrivialDecisions
import com.wingedsheep.ai.engine.rollout.FastDecisionResponder
import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.BatchYesNoDecision
import com.wingedsheep.engine.core.BudgetModalDecision
import com.wingedsheep.engine.core.BudgetModeOption
import com.wingedsheep.engine.core.CancelDecisionResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseReplacementDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.DamageEdge
import com.wingedsheep.engine.core.DamageEdgeDirection
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.ResolutionAttacker
import com.wingedsheep.engine.core.ResolutionBlocker
import com.wingedsheep.engine.core.ResolutionDefender
import com.wingedsheep.engine.core.ResolutionTargetKind
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.trainer.defaults.BoundedStructuredDecisionExpander
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.exactSingletonPassOrNull
import org.mtgallium.agent.searchteacher.PolicyCompressionConfig

private const val M02_PLAYER_CHOICE_INVENTORY_VERSION =
    "m02-production-choice-inventory-v2"

@Serializable
internal enum class M02ChoiceFamily {
    TARGET,
    CARD_SELECTION,
    OPTIONAL_YES_NO,
    BATCH_OPTIONAL_YES_NO,
    MODE,
    COLOR,
    NUMBER,
    DISTRIBUTION,
    ORDER,
    PILES,
    OPTION,
    TEXT_REPLACEMENT,
    DAMAGE_ASSIGNMENT,
    COMBAT_RESOLUTION,
    LIBRARY_SEARCH,
    LIBRARY_REORDER,
    PAYMENT_MANA_SOURCE,
    BUDGET_MODAL,
    MULLIGAN,
    BOTTOM_CARDS,
    PRIORITY_PASS,
    STANDALONE_MANA_ABILITY,
    NORMAL_ACTION,
}

@Serializable
internal enum class M02SelectionAuthority {
    RULES_PROVEN_SINGLETON,
    PROFILE_SINGLETON_AUTOMATIC,
    SHARED_PREGAME_SCRIPT,
    SEARCHED,
    DECLARED_POLICY_OR_HEURISTIC,
}

@Serializable
internal enum class M02RegretStatus {
    SCOPED_ACTION_AVAILABILITY,
    REFUSED_NO_INDEPENDENT_VALUE_REFERENCE,
}

@Serializable
internal data class M02DecisionProbe(
    val id: String,
    val family: M02ChoiceFamily,
    val engineDecisionType: String,
    val enumeratedResponses: Int,
    val exhaustive: Boolean,
    val estimatedResponses: Long?,
    val cancelOrDeclineResponses: Int,
    val rulesProvenSingleton: Boolean,
    val trivialResponderSelected: Boolean,
    val trivialResponderBypassedAlternatives: Boolean,
    val trivialResponseWasEnumerated: Boolean?,
    val fastRolloutResponderSelected: Boolean,
    val fastRolloutResponderBypassedAlternatives: Boolean,
    val fastRolloutResponseWasEnumerated: Boolean,
    val fastRolloutResponseValidated: Boolean,
    val regretStatus: M02RegretStatus = M02RegretStatus.REFUSED_NO_INDEPENDENT_VALUE_REFERENCE,
    val regretRefusal: String? = "No independent value reference was supplied for this authored decision contract.",
)

@Serializable
internal data class M02ProductionPath(
    val id: String,
    val productionEntryPoints: List<String>,
    val selectionAuthority: String,
    val coveredFamilies: List<M02ChoiceFamily>,
    val observableBehavior: String,
)

@Serializable
internal data class M02ReachedFamilyCount(
    val family: M02ChoiceFamily,
    val reachedDecisions: Int,
    val multiAlternativeDecisions: Int,
    val cancelOrDeclineAvailableDecisions: Int,
    val rulesProvenSingletonDecisions: Int,
    val profileSingletonAutomaticDecisions: Int,
    val scriptOrPolicySelectedDecisions: Int,
    val searchedDecisions: Int,
)

@Serializable
internal data class M02ArenaRunMeasurement(
    val id: String,
    val description: String,
    val terminal: Boolean,
    val disposition: GameRunDisposition = GameRunDisposition.LEGACY_UNCLASSIFIED,
    /** Present only for a typed O-04 representation/software refusal; never contains result data. */
    val evidenceStop: EvidenceRunStopSummary? = null,
    val stoppedAtDeclaredSearchDecisionLimit: Boolean,
    val reachedRootDecisions: Int,
    val multiAlternativeRootDecisions: Int,
    val cancelOrDeclineAvailableRootDecisions: Int,
    val rulesProvenSingletonRootDecisions: Int,
    val profileSingletonAutomaticRootDecisions: Int,
    val scriptOrPolicySelectedRootDecisions: Int,
    val searchedRootDecisions: Int,
    val replacementDecisions: Int,
    val replacementDecisionOpportunities: Int,
    val evidenceInvalidatingReplacements: Int,
    val liveDeclaredPolicyDecisions: Int,
    val opponentModelSimulationDecisions: Int,
    val rootRolloutSimulationDecisions: Int,
    val opponentRolloutSimulationDecisions: Int,
    val beliefPrivateChoiceDecisions: Int,
    val policySelectedSimulationDecisions: Int,
    val compressedPolicySingletonSimulationPasses: Int,
    val searchWorldSteps: Int,
    val heuristicComparatorDecisions: Int,
    val byFamily: List<M02ReachedFamilyCount>,
    val regretStatus: M02RegretStatus = M02RegretStatus.REFUSED_NO_INDEPENDENT_VALUE_REFERENCE,
    val regretRefusal: String =
        "Search values and scripted scores are not an independent value reference, so this run reports no strategic regret.",
)

@Serializable
internal data class M02ManaSourceSurvivalCase(
    val id: String = "rockface-village-survival-v1",
    val requiredCost: String = "{1}",
    val availableSources: List<String>,
    val minimalSufficientPayments: List<List<String>>,
    val fixedAutoPaySuggestion: List<String>,
    val preservingPayment: List<String>,
    val futureAction: String,
    val fixedSelectionFutureActionAvailable: Boolean,
    val preservingSelectionFutureActionAvailable: Boolean,
    val scopedActionAvailabilityRegret: Int,
    val regretUnit: String = "one independently checked future legal-action availability",
    val limitation: String =
        "This authored payment contract checks source survival and future-action preconditions, not game value or win-rate regret.",
)

@Serializable
internal data class M02PlayerChoiceInventoryReport(
    val schemaVersion: Int = 2,
    val documentKind: String = "work-only-remediation-diagnostic",
    val diagnosticVersion: String = M02_PLAYER_CHOICE_INVENTORY_VERSION,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val scope: String =
        "Authored exhaustive decision contracts plus one production Arena game and one one-search-decision production-path run.",
    val probes: List<M02DecisionProbe>,
    val productionPaths: List<M02ProductionPath>,
    val arenaRuns: List<M02ArenaRunMeasurement>,
    val manaSourceSurvival: M02ManaSourceSurvivalCase,
    val reachedAuthoredDecisionContracts: Int,
    val multiAlternativeAuthoredDecisionContracts: Int,
    val trivialResponderBypassContracts: Int,
    val fastRolloutResponderBypassContracts: Int,
    val fixedResponderBypassContracts: Int,
    val reachedArenaRootDecisions: Int,
    val multiAlternativeArenaRootDecisions: Int,
    val replacementArenaAndSimulationDecisions: Int,
    val replacementArenaAndSimulationDecisionOpportunities: Int,
    val scriptOrPolicySelectedArenaRootDecisions: Int,
    val searchedArenaRootDecisions: Int,
    val conclusionsPermitted: List<String>,
    val limitations: List<String>,
)

internal class M02PlayerChoiceInventoryDiagnostic(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val outerCommit: String,
    private val argentumCommit: String,
    private val representationBoundaryDetector: RepresentationBoundaryDetector? = null,
) {
    fun run(seed: Long): M02PlayerChoiceInventoryReport {
        val probes = authoredDecisionProbes()
        val profile = SearchTeacherArena.smokeProfile()
        val arena = representationBoundaryDetector?.let { detector ->
            SearchTeacherArena(
                registry,
                manifest,
                profile,
                seed,
                representationBoundaryDetector = detector,
            )
        } ?: SearchTeacherArena(registry, manifest, profile, seed)

        val heuristicCollector = M02ArenaChoiceCollector(
            policies = mapOf("p0" to ArenaPolicyKind.HEURISTIC, "p1" to ArenaPolicyKind.HEURISTIC),
        )
        val heuristicGame = arena.play(
            gameId = "00000000-0000-4000-8000-000000000202",
            gameSeed = seed,
            p0Policy = ArenaPolicyKind.HEURISTIC,
            p1Policy = ArenaPolicyKind.HEURISTIC,
            rootProbe = heuristicCollector::record,
        )

        val workOnlyCompression = PolicyCompressionConfig(enabled = false)
        val searchCollector = M02ArenaChoiceCollector(
            policies = mapOf("p0" to ArenaPolicyKind.SEARCH, "p1" to ArenaPolicyKind.HEURISTIC),
        )
        val searchGame = arena.playWithPolicies(
            gameId = "00000000-0000-4000-8000-000000000203",
            gameSeed = seed + 1,
            p0Policy = ArenaPolicySpec(
                id = "m02-search-probe",
                kind = ArenaPolicyKind.SEARCH,
                profile = profile,
                policyCompression = workOnlyCompression,
            ),
            p1Policy = ArenaPolicySpec("m02-heuristic-probe", ArenaPolicyKind.HEURISTIC),
            maxSearchDecisions = 1,
            rootProbe = searchCollector::record,
        )

        val runs = listOf(
            heuristicCollector.finish(
                id = "heuristic-mirror-full-game",
                description = "A complete frozen-deck game with the determinized heuristic controlling both seats.",
                game = heuristicGame,
                stoppedAtSearchLimit = false,
            ),
            searchCollector.finish(
                id = "search-versus-heuristic-one-search-decision",
                description =
                    "Production Search Teacher callbacks through pregame and exactly one searched root decision; simulations retain separate policy denominators.",
                game = searchGame,
                stoppedAtSearchLimit = true,
            ),
        )
        val mana = manaSourceSurvivalCase()
        return M02PlayerChoiceInventoryReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = outerCommit,
            argentumCommit = argentumCommit,
            probes = probes,
            productionPaths = productionPaths(),
            arenaRuns = runs,
            manaSourceSurvival = mana,
            reachedAuthoredDecisionContracts = probes.size,
            multiAlternativeAuthoredDecisionContracts = probes.count { it.enumeratedResponses > 1 },
            trivialResponderBypassContracts = probes.count { it.trivialResponderBypassedAlternatives },
            fastRolloutResponderBypassContracts = probes.count { it.fastRolloutResponderBypassedAlternatives },
            fixedResponderBypassContracts = probes.count {
                it.trivialResponderBypassedAlternatives || it.fastRolloutResponderBypassedAlternatives
            },
            reachedArenaRootDecisions = runs.sumOf { it.reachedRootDecisions },
            multiAlternativeArenaRootDecisions = runs.sumOf { it.multiAlternativeRootDecisions },
            replacementArenaAndSimulationDecisions = runs.sumOf { it.replacementDecisions },
            replacementArenaAndSimulationDecisionOpportunities =
                runs.sumOf { it.replacementDecisionOpportunities },
            scriptOrPolicySelectedArenaRootDecisions = runs.sumOf { it.scriptOrPolicySelectedRootDecisions },
            searchedArenaRootDecisions = runs.sumOf { it.searchedRootDecisions },
            conclusionsPermitted = listOf(
                "Every current PendingDecision subtype has a validated authored expansion probe, including explicit cancel and decline responses where the contract permits them.",
                "The recorded Arena populations distinguish rules-proven singleton, declared-policy, and searched root choices.",
                "The Rockface Village fixture establishes one unit of future-action availability loss under its declared payment preconditions.",
            ),
            limitations = listOf(
                "The authored probes establish response enumeration for supplied contracts; they do not measure natural frozen-deck reachability.",
                "Only one full heuristic game and one truncated search-path run were sampled, so their frequencies are seed-scoped.",
                "Internal search retains policy-call, replacement, step, and compressed-singleton totals but not candidate cardinality or family at every simulated decision; multi-alternative counts are therefore reported only for authored contracts and Arena roots.",
                "The authored fixed-response comparison executes TrivialDecisions and FastDecisionResponder. DecisionResponder requires a coherent live state; its route is inventoried and its reached Arena choices are counted, but it is not invoked against synthetic pending-decision-only states.",
                "No independent strategic evaluator or human judgment was supplied. Strategic regret is therefore refused outside the narrow mana-source action-availability predicate.",
                "The live host is inventoried by its shared SearchTeacherRuntimeSession routing; this diagnostic did not start a network game server.",
            ),
        )
    }
}

private data class M02DecisionFixture(
    val id: String,
    val family: M02ChoiceFamily,
    val decision: PendingDecision,
)

internal fun authoredDecisionProbes(): List<M02DecisionProbe> {
    val expander = BoundedStructuredDecisionExpander(maxResponses = 4_096, maxAttempts = 4_096)
    return decisionFixtures().map { fixture ->
        val expansion = expander.expand(GameState(), fixture.decision)
        val trivial = TrivialDecisions.responseFor(GameState(), fixture.decision)
        val fastRollout = FastDecisionResponder().respond(
            GameState(),
            fixture.decision,
        )
        val declines = expansion.responses.count(::isCancelOrDecline)
        M02DecisionProbe(
            id = fixture.id,
            family = fixture.family,
            engineDecisionType = requireNotNull(fixture.decision::class.simpleName),
            enumeratedResponses = expansion.responses.size,
            exhaustive = expansion.isExhaustive,
            estimatedResponses = expansion.estimatedResponseCount,
            cancelOrDeclineResponses = declines,
            rulesProvenSingleton = expansion.isExhaustive && expansion.responses.size == 1 && declines == 0,
            trivialResponderSelected = trivial != null,
            trivialResponderBypassedAlternatives = trivial != null && expansion.responses.size > 1,
            trivialResponseWasEnumerated = trivial?.let(expansion.responses::contains),
            fastRolloutResponderSelected = true,
            fastRolloutResponderBypassedAlternatives = expansion.responses.size > 1,
            fastRolloutResponseWasEnumerated = fastRollout in expansion.responses,
            fastRolloutResponseValidated =
                DecisionValidators.validate(fixture.decision, fastRollout, GameState()) == null,
        )
    }
}

private fun decisionFixtures(): List<M02DecisionFixture> {
    val player = entity("player")
    val a = entity("a")
    val b = entity("b")
    val attacker = entity("attacker")
    val defender = entity("defender")
    val context = DecisionContext()
    fun fixture(
        id: String,
        family: M02ChoiceFamily,
        decision: PendingDecision,
    ) = M02DecisionFixture(id, family, decision)

    return listOf(
        fixture(
            "target-with-cancel",
            M02ChoiceFamily.TARGET,
            ChooseTargetsDecision(
                "target", player, "Choose a target", context,
                listOf(TargetRequirementInfo(0, "target")), mapOf(0 to listOf(a, b)), canCancel = true,
            ),
        ),
        fixture(
            "card-selection",
            M02ChoiceFamily.CARD_SELECTION,
            SelectCardsDecision("cards", player, "Choose a card", context, listOf(a, b), 1, 1),
        ),
        fixture(
            "yes-no-decline",
            M02ChoiceFamily.OPTIONAL_YES_NO,
            YesNoDecision("yes-no", player, "Use it?", context),
        ),
        fixture(
            "batch-yes-no-decline",
            M02ChoiceFamily.BATCH_OPTIONAL_YES_NO,
            BatchYesNoDecision("batch", player, "Use these?", context, count = 2),
        ),
        fixture(
            "mode",
            M02ChoiceFamily.MODE,
            ChooseModeDecision(
                "mode", player, "Choose a mode", context,
                listOf(ModeOption(0, "A"), ModeOption(1, "B")), 1, 1,
            ),
        ),
        fixture(
            "color",
            M02ChoiceFamily.COLOR,
            ChooseColorDecision("color", player, "Choose a color", context, setOf(Color.RED, Color.GREEN)),
        ),
        fixture(
            "number",
            M02ChoiceFamily.NUMBER,
            ChooseNumberDecision("number", player, "Choose a number", context, 0, 2),
        ),
        fixture(
            "distribution",
            M02ChoiceFamily.DISTRIBUTION,
            DistributeDecision("distribution", player, "Divide two", context, 2, listOf(a, b)),
        ),
        fixture(
            "order",
            M02ChoiceFamily.ORDER,
            OrderObjectsDecision("order", player, "Choose an order", context, listOf(a, b)),
        ),
        fixture(
            "piles",
            M02ChoiceFamily.PILES,
            SplitPilesDecision("piles", player, "Split piles", context, listOf(a, b), 2),
        ),
        fixture(
            "option-with-cancel",
            M02ChoiceFamily.OPTION,
            ChooseOptionDecision("option", player, "Choose an option", context, listOf("A", "B"), canCancel = true),
        ),
        fixture(
            "text-replacement",
            M02ChoiceFamily.TEXT_REPLACEMENT,
            ChooseReplacementDecision(
                "replacement", player, "Replace text", context,
                fromOptions = listOf("red", "green"),
                toOptions = listOf("red", "green"),
                allowedToByFrom = listOf(listOf(1), listOf(0)),
                defaultFromIndex = 0,
            ),
        ),
        fixture(
            "damage-assignment",
            M02ChoiceFamily.DAMAGE_ASSIGNMENT,
            AssignDamageDecision(
                "damage", player, "Assign damage", context,
                attackerId = attacker,
                availablePower = 3,
                orderedTargets = listOf(a, b),
                defenderId = null,
                minimumAssignments = mapOf(a to 2, b to 2),
                defaultAssignments = mapOf(a to 2, b to 1),
                hasTrample = false,
                hasDeathtouch = false,
            ),
        ),
        fixture(
            "combat-resolution",
            M02ChoiceFamily.COMBAT_RESOLUTION,
            CombatResolutionDecision(
                id = "combat-resolution",
                playerId = player,
                prompt = "Assign combat damage",
                context = context,
                firstStrike = false,
                attackers = listOf(
                    ResolutionAttacker(
                        attacker, "Attacker", 3, 3, false, false, false, false,
                        true, null, defender, listOf(a, b), 0,
                    )
                ),
                blockers = listOf(
                    ResolutionBlocker(a, "Blocker A", 2, 2, false, false, false, true, listOf(attacker), listOf(attacker), 0),
                    ResolutionBlocker(b, "Blocker B", 2, 2, false, false, false, true, listOf(attacker), listOf(attacker), 0),
                ),
                defenders = listOf(ResolutionDefender(defender, ResolutionTargetKind.PLAYER, "Defender", 20)),
                edges = listOf(
                    DamageEdge(
                        "attacker-a", attacker, a, DamageEdgeDirection.ATTACKER_TO_BLOCKER,
                        2, 3, 2, true, false, player,
                    ),
                    DamageEdge(
                        "attacker-b", attacker, b, DamageEdgeDirection.ATTACKER_TO_BLOCKER,
                        1, 3, 2, true, false, player,
                    ),
                ),
            ),
        ),
        fixture(
            "library-search-decline",
            M02ChoiceFamily.LIBRARY_SEARCH,
            SearchLibraryDecision(
                "library-search", player, "Find a card", context,
                options = listOf(a, b), minSelections = 0, maxSelections = 1,
                cards = mapOf(
                    a to SearchCardInfo("A", "", "Land"),
                    b to SearchCardInfo("B", "{R}", "Instant"),
                ),
                filterDescription = "a card",
            ),
        ),
        fixture(
            "library-reorder",
            M02ChoiceFamily.LIBRARY_REORDER,
            ReorderLibraryDecision(
                "library-reorder", player, "Reorder", context, listOf(a, b),
                mapOf(
                    a to SearchCardInfo("A", "", "Land"),
                    b to SearchCardInfo("B", "{R}", "Instant"),
                ),
            ),
        ),
        fixture(
            "mana-source-with-decline",
            M02ChoiceFamily.PAYMENT_MANA_SOURCE,
            SelectManaSourcesDecision(
                "mana", player, "Pay {1}", context,
                availableSources = listOf(
                    ManaSourceOption(a, "Mountain", setOf(Color.RED), false),
                    ManaSourceOption(b, "Rockface Village", setOf(Color.RED), false),
                ),
                requiredCost = "{1}",
                autoPaySuggestion = listOf(a),
                canDecline = true,
            ),
        ),
        fixture(
            "budget-modal",
            M02ChoiceFamily.BUDGET_MODAL,
            BudgetModalDecision(
                "budget", player, "Choose modes", context, budget = 2,
                modes = listOf(BudgetModeOption(1, "A"), BudgetModeOption(2, "B")),
            ),
        ),
        fixture(
            "rules-single-color-control",
            M02ChoiceFamily.COLOR,
            ChooseColorDecision("single-color", player, "Choose a color", context, setOf(Color.RED)),
        ),
        fixture(
            "rules-single-order-control",
            M02ChoiceFamily.ORDER,
            OrderObjectsDecision("single-order", player, "Confirm order", context, listOf(a)),
        ),
    )
}

private fun isCancelOrDecline(response: com.wingedsheep.engine.core.DecisionResponse): Boolean = when (response) {
    is CancelDecisionResponse -> true
    is ManaSourcesSelectedResponse -> response.declined
    is YesNoResponse -> !response.choice
    is com.wingedsheep.engine.core.BatchYesNoResponse -> !response.choice
    is CardsSelectedResponse -> response.selectedCards.isEmpty()
    is com.wingedsheep.engine.core.BudgetModalResponse -> response.selectedModeIndices.isEmpty()
    is com.wingedsheep.engine.core.DistributionResponse -> response.distribution.values.sum() == 0
    else -> false
}

internal fun productionPaths(): List<M02ProductionPath> {
    val pending = M02ChoiceFamily.entries.filter { it.ordinal <= M02ChoiceFamily.BUDGET_MODAL.ordinal }
    return listOf(
        M02ProductionPath(
            "semantic-expansion",
            listOf("UnifiedSemanticExpander", "BoundedStructuredDecisionExpander"),
            "ENUMERATES_AND_VALIDATES",
            pending + listOf(M02ChoiceFamily.MULLIGAN, M02ChoiceFamily.BOTTOM_CARDS),
            "Every pending-decision subtype is expanded into validated semantic candidates; target/option cancel and mana decline are explicit candidates when allowed.",
        ),
        M02ProductionPath(
            "search-root",
            listOf("SearchTeacherPolicySession.select", "InformationSetSearch"),
            "SEARCHED_EXCEPT_RULES_PROVEN_SINGLETON",
            M02ChoiceFamily.entries,
            "Only a rules-exhaustive singleton pass may bypass search; all other root choices, including mulligan and bottom-card selection, are searched.",
        ),
        M02ProductionPath(
            "search-opponent-and-belief",
            listOf("InformationSetSearch.simulate", "ParticleBelief.advanceUnobserved"),
            "DECLARED_OPPONENT_POLICY",
            M02ChoiceFamily.entries,
            "Opponent and private-belief choices sample the declared opponent policy across the adapter candidates and report their own decision denominators.",
        ),
        M02ProductionPath(
            "root-and-opponent-rollout",
            listOf("InformationSetSearch.rollout", "InformationSetSearch.refreshTrace"),
            "DECLARED_ROLLOUT_POLICY",
            M02ChoiceFamily.entries,
            "Both rollout seats use separately identified determinized-heuristic policies; only a rules-exhaustive singleton pass may be compressed before the policy sample.",
        ),
        M02ProductionPath(
            "pregame",
            listOf("SearchTeacherPolicySession.select", "InformationSetSearch"),
            "DECLARED_SEARCH_POLICY",
            listOf(M02ChoiceFamily.MULLIGAN, M02ChoiceFamily.BOTTOM_CARDS),
            "Pregame candidates use the same responsible Search Teacher policy as every other non-forced root choice.",
        ),
        M02ProductionPath(
            "arena",
            listOf("SearchTeacherArena.choose", "SearchTeacherPolicySession"),
            "SEARCH_SESSION_OR_NAMED_DIRECT_POLICY",
            M02ChoiceFamily.entries,
            "Search seats use the production policy session. Direct seats use a named heuristic or scripted opponent policy; the diagnostic classifies every reached root before selection.",
        ),
        M02ProductionPath(
            "live-host",
            listOf("SearchTeacherController.chooseAction", "decideMulligan", "chooseBottomCards", "SearchTeacherRuntimeSession.choose"),
            "SHARED_PRODUCTION_SEARCH_SESSION",
            M02ChoiceFamily.entries,
            "Action, mulligan, bottom-card, and pending-decision callbacks all resolve a choice returned by the same production SearchTeacherRuntimeSession.",
        ),
        M02ProductionPath(
            "llm-controller",
            listOf("LlmAiPlayerController", "AiDecisionHandlerRegistry", "AiWebSocketSession"),
            "DECLARED_LLM_OR_NAMED_FALLBACK",
            M02ChoiceFamily.entries,
            "Player choices route to the LLM or its named fallback. Missing or unparseable policy answers fail with a typed responsible-policy refusal; no-state host handling only submits a lone authoritative PassPriority.",
        ),
        M02ProductionPath(
            "argentum-production-heuristic",
            listOf("ArgentumHeuristicAnnotator", "AIPlayer", "DecisionResponder", "MeaningfulActionFilter"),
            "DECLARED_DETERMINIZED_HEURISTIC_COMPONENT",
            pending,
            "Pending decisions route to DecisionResponder; action priority can auto-pass; the selected engine choice is mapped back to a typed semantic candidate or reported as a replacement.",
        ),
        M02ProductionPath(
            "argentum-trivial-and-playout-responders",
            listOf("TrivialDecisions", "FastDecisionResponder", "PlayoutEngine"),
            "RULES_PROOF_OR_DECLARED_PLAYOUT_POLICY",
            pending,
            "TrivialDecisions bypasses policy only for independently validated exhaustive singleton contracts; FastDecisionResponder is the declared rollout policy and evaluates from the responsible player's perspective.",
        ),
    )
}

private class M02ArenaChoiceCollector(
    private val policies: Map<String, ArenaPolicyKind>,
) {
    private data class MutableCount(
        var reached: Int = 0,
        var multi: Int = 0,
        var cancelOrDecline: Int = 0,
        var rulesSingleton: Int = 0,
        var profileSingleton: Int = 0,
        var scriptOrPolicy: Int = 0,
        var searched: Int = 0,
    )

    private val counts = mutableMapOf<M02ChoiceFamily, MutableCount>()

    fun record(world: ArgentumSearchWorld, actor: String, decisionIndex: Int) {
        require(decisionIndex >= 0)
        val expansion = world.expandChoices()
        val family = choiceFamily(world, expansion)
        val hasCancelOrDecline = expansion.candidates.any { candidate ->
            when (val resolved = world.resolveChoice(candidate)) {
                is ArgentumResolvedChoice.Action -> false
                is ArgentumResolvedChoice.Decision -> isCancelOrDecline(resolved.value)
            }
        }
        val authority = selectionAuthority(expansion, policies.getValue(actor))
        counts.getOrPut(family, ::MutableCount).apply {
            reached++
            if (expansion.candidates.size > 1) multi++
            if (hasCancelOrDecline) cancelOrDecline++
            when (authority) {
                M02SelectionAuthority.RULES_PROVEN_SINGLETON -> rulesSingleton++
                M02SelectionAuthority.PROFILE_SINGLETON_AUTOMATIC -> profileSingleton++
                M02SelectionAuthority.SHARED_PREGAME_SCRIPT,
                M02SelectionAuthority.DECLARED_POLICY_OR_HEURISTIC -> scriptOrPolicy++
                M02SelectionAuthority.SEARCHED -> searched++
            }
        }
    }

    fun finish(
        id: String,
        description: String,
        game: GameRunResult,
        stoppedAtSearchLimit: Boolean,
    ): M02ArenaRunMeasurement {
        val byFamily = counts.entries.sortedBy { it.key.name }.map { (family, count) ->
            M02ReachedFamilyCount(
                family,
                count.reached,
                count.multi,
                count.cancelOrDecline,
                count.rulesSingleton,
                count.profileSingleton,
                count.scriptOrPolicy,
                count.searched,
            )
        }
        val searchDetails = game.seatDiagnostics.values.flatMap { it.searchDecisionsDetail }
        val opponentModel = searchDetails.sumOf { it.searchDiagnostics.opponentModelPolicyDecisions.decisions }
        val rootRollout = searchDetails.sumOf { it.searchDiagnostics.rootRolloutPolicyDecisions.decisions }
        val opponentRollout = searchDetails.sumOf { it.searchDiagnostics.opponentRolloutPolicyDecisions.decisions }
        val beliefPrivate = (
            game.searchOpponentPolicyDecisions.decisions - opponentModel - rootRollout - opponentRollout
        ).coerceAtLeast(0)
        val compressedSingletons = searchDetails.sumOf {
            it.searchDiagnostics.compressedPolicySingletonPasses
        }
        val searchWorldSteps = searchDetails.sumOf { it.searchDiagnostics.searchWorldSteps }
        return M02ArenaRunMeasurement(
            id = id,
            description = description,
            terminal = game.terminal,
            disposition = game.disposition,
            evidenceStop = game.evidenceRunStopSummary(),
            stoppedAtDeclaredSearchDecisionLimit =
                stoppedAtSearchLimit && game.disposition == GameRunDisposition.STOPPED_LIMIT,
            reachedRootDecisions = byFamily.sumOf { it.reachedDecisions },
            multiAlternativeRootDecisions = byFamily.sumOf { it.multiAlternativeDecisions },
            cancelOrDeclineAvailableRootDecisions = byFamily.sumOf { it.cancelOrDeclineAvailableDecisions },
            rulesProvenSingletonRootDecisions = byFamily.sumOf { it.rulesProvenSingletonDecisions },
            profileSingletonAutomaticRootDecisions = byFamily.sumOf { it.profileSingletonAutomaticDecisions },
            scriptOrPolicySelectedRootDecisions = byFamily.sumOf { it.scriptOrPolicySelectedDecisions },
            searchedRootDecisions = byFamily.sumOf { it.searchedDecisions },
            replacementDecisions = game.liveOpponentPolicyDecisions.replacementDecisions +
                game.searchOpponentPolicyDecisions.replacementDecisions +
                game.heuristicComparatorDecisions.replacementDecisions,
            replacementDecisionOpportunities = game.liveOpponentPolicyDecisions.decisions +
                game.searchOpponentPolicyDecisions.decisions +
                game.heuristicComparatorDecisions.decisions,
            evidenceInvalidatingReplacements = game.fallbacks,
            liveDeclaredPolicyDecisions = game.liveOpponentPolicyDecisions.decisions,
            opponentModelSimulationDecisions = opponentModel,
            rootRolloutSimulationDecisions = rootRollout,
            opponentRolloutSimulationDecisions = opponentRollout,
            beliefPrivateChoiceDecisions = beliefPrivate,
            policySelectedSimulationDecisions = opponentModel + rootRollout + opponentRollout + beliefPrivate,
            compressedPolicySingletonSimulationPasses = compressedSingletons,
            searchWorldSteps = searchWorldSteps,
            heuristicComparatorDecisions = game.heuristicComparatorDecisions.decisions,
            byFamily = byFamily,
        )
    }
}

private fun selectionAuthority(
    expansion: PolicyExpansion,
    policy: ArenaPolicyKind,
): M02SelectionAuthority = when {
    expansion.exactSingletonPassOrNull() != null -> M02SelectionAuthority.RULES_PROVEN_SINGLETON
    policy == ArenaPolicyKind.SEARCH -> M02SelectionAuthority.SEARCHED
    else -> M02SelectionAuthority.DECLARED_POLICY_OR_HEURISTIC
}

private fun choiceFamily(
    world: ArgentumSearchWorld,
    expansion: PolicyExpansion,
): M02ChoiceFamily {
    world.authoritativeStateForHost().pendingDecision?.let { return choiceFamily(it) }
    val intents = expansion.candidates.map { it.actionIntent.kind }.toSet()
    if (SemanticActionIntentKind.BOTTOM_CARDS in intents) return M02ChoiceFamily.BOTTOM_CARDS
    if (intents.any {
            it == SemanticActionIntentKind.KEEP_HAND || it == SemanticActionIntentKind.TAKE_MULLIGAN
        }) return M02ChoiceFamily.MULLIGAN
    val candidate = expansion.candidates.first()
    return when (candidate.operationFamily) {
        SemanticOperationFamily.DECISION_RESPONSE ->
            error("Decision-response expansion has no authoritative pending-decision contract")
        SemanticOperationFamily.PASS_PRIORITY -> M02ChoiceFamily.PRIORITY_PASS
        SemanticOperationFamily.MANA_ABILITY -> M02ChoiceFamily.STANDALONE_MANA_ABILITY
        else -> M02ChoiceFamily.NORMAL_ACTION
    }
}

private fun choiceFamily(decision: PendingDecision): M02ChoiceFamily = when (decision) {
    is ChooseTargetsDecision -> M02ChoiceFamily.TARGET
    is SelectCardsDecision -> M02ChoiceFamily.CARD_SELECTION
    is YesNoDecision -> M02ChoiceFamily.OPTIONAL_YES_NO
    is BatchYesNoDecision -> M02ChoiceFamily.BATCH_OPTIONAL_YES_NO
    is ChooseModeDecision -> M02ChoiceFamily.MODE
    is ChooseColorDecision -> M02ChoiceFamily.COLOR
    is ChooseNumberDecision -> M02ChoiceFamily.NUMBER
    is DistributeDecision -> M02ChoiceFamily.DISTRIBUTION
    is OrderObjectsDecision -> M02ChoiceFamily.ORDER
    is SplitPilesDecision -> M02ChoiceFamily.PILES
    is ChooseOptionDecision -> M02ChoiceFamily.OPTION
    is ChooseReplacementDecision -> M02ChoiceFamily.TEXT_REPLACEMENT
    is AssignDamageDecision -> M02ChoiceFamily.DAMAGE_ASSIGNMENT
    is CombatResolutionDecision -> M02ChoiceFamily.COMBAT_RESOLUTION
    is SearchLibraryDecision -> M02ChoiceFamily.LIBRARY_SEARCH
    is ReorderLibraryDecision -> M02ChoiceFamily.LIBRARY_REORDER
    is SelectManaSourcesDecision -> M02ChoiceFamily.PAYMENT_MANA_SOURCE
    is BudgetModalDecision -> M02ChoiceFamily.BUDGET_MODAL
}

internal fun manaSourceSurvivalCase(): M02ManaSourceSurvivalCase {
    data class Source(val name: String, val producesRed: Boolean, val activatesRockface: Boolean = false)

    val sourceContracts = listOf(
        Source("Mountain A", producesRed = true),
        Source("Mountain B", producesRed = true),
        Source("Mountain C", producesRed = true),
        Source("Rockface Village", producesRed = true, activatesRockface = true),
    )
    val sources = sourceContracts.map(Source::name)
    val payments = sourceContracts.filter(Source::producesRed).map { listOf(it.name) }
    val fixed = listOf("Rockface Village")
    val preserving = listOf("Mountain A")
    fun futureActionAvailable(payment: List<String>): Boolean {
        val survivors = sourceContracts.filterNot { it.name in payment }
        val village = survivors.singleOrNull(Source::activatesRockface) ?: return false
        val manaSources = survivors.filterNot { it === village }
        return manaSources.size >= 2 && manaSources.any(Source::producesRed)
    }
    val fixedFutureAvailable = futureActionAvailable(fixed)
    val preservingFutureAvailable = futureActionAvailable(preserving)
    return M02ManaSourceSurvivalCase(
        availableSources = sources,
        minimalSufficientPayments = payments,
        fixedAutoPaySuggestion = fixed,
        preservingPayment = preserving,
        futureAction = "Pay {1}{R} with two surviving Mountains, then tap Rockface Village to give a creature +1/+0 and haste.",
        fixedSelectionFutureActionAvailable = fixedFutureAvailable,
        preservingSelectionFutureActionAvailable = preservingFutureAvailable,
        scopedActionAvailabilityRegret =
            (if (preservingFutureAvailable) 1 else 0) - (if (fixedFutureAvailable) 1 else 0),
    )
}

private fun entity(value: String): EntityId = EntityId.of("m02-$value")

internal fun renderM02PlayerChoiceInventory(report: M02PlayerChoiceInventoryReport): String = buildString {
    appendLine("# Which player choices were searched, scripted, or rules-singleton in the M-02 probes")
    appendLine()
    appendLine(
        "The authored contracts reached ${report.reachedAuthoredDecisionContracts} probes; " +
            "${report.multiAlternativeAuthoredDecisionContracts} had more than one validated response and " +
            "${report.trivialResponderBypassContracts} were still answered by `TrivialDecisions`, while " +
            "${report.fastRolloutResponderBypassContracts} were answered by `FastDecisionResponder`."
    )
    appendLine(
        "The two Arena runs reached ${report.reachedArenaRootDecisions} root decisions: " +
            "${report.multiAlternativeArenaRootDecisions} had multiple candidates, " +
            "${report.scriptOrPolicySelectedArenaRootDecisions} were selected by a script or declared policy, " +
            "and search selected ${report.searchedArenaRootDecisions}. " +
            "They recorded ${report.replacementArenaAndSimulationDecisions}/" +
            "${report.replacementArenaAndSimulationDecisionOpportunities} replacement decisions."
    )
    appendLine()
    appendLine("This is a work-only, seed-scoped diagnostic. It does not establish whole-policy competence.")
    appendLine()
    appendLine("## Limits")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Production route inventory")
    appendLine()
    appendLine("| Route | Selection authority | Covered families | Observable behavior |")
    appendLine("| --- | --- | --- | --- |")
    report.productionPaths.forEach { path ->
        appendLine(
            "| ${path.id} | ${path.selectionAuthority} | " +
                "${path.coveredFamilies.joinToString(", ")} | ${path.observableBehavior} |"
        )
    }
    appendLine()
    appendLine("## Authored pending-decision contracts")
    appendLine()
    appendLine("| Family | Type | Responses | Cancel/decline | Rules singleton | Trivial bypass | Fast-rollout script bypass |")
    appendLine("| --- | --- | ---: | ---: | --- | --- | --- |")
    report.probes.forEach { probe ->
        appendLine(
            "| ${probe.family} | ${probe.engineDecisionType} | ${probe.enumeratedResponses} | " +
                "${probe.cancelOrDeclineResponses} | ${probe.rulesProvenSingleton} | " +
                "${probe.trivialResponderBypassedAlternatives} | " +
                "${probe.fastRolloutResponderBypassedAlternatives} |"
        )
    }
    appendLine()
    appendLine("## Production Arena measurements")
    appendLine()
    appendLine("| Run | Disposition | Reached | Multiple | Cancel/decline available | Rules singleton | Profile singleton | Script/policy | Searched | Replacements |")
    appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    report.arenaRuns.forEach { run ->
        appendLine(
            "| ${run.id} | ${run.disposition} | ${run.reachedRootDecisions} | ${run.multiAlternativeRootDecisions} | " +
                "${run.cancelOrDeclineAvailableRootDecisions} | ${run.rulesProvenSingletonRootDecisions} | " +
                "${run.profileSingletonAutomaticRootDecisions} | ${run.scriptOrPolicySelectedRootDecisions} | " +
                "${run.searchedRootDecisions} | ${run.replacementDecisions}/${run.replacementDecisionOpportunities} |"
        )
        run.evidenceStop?.let { stop ->
            appendLine(
                "> ${run.id} stopped at ${stop.detectionPoint}; trigger=${stop.triggerCodes.joinToString(",")}; " +
                    "viewers=${stop.affectedViewers.joinToString(",")}; triggeringDecision=" +
                    "${stop.triggeringDecisionIndex ?: "none"}; refusedDecision=${stop.refusedPolicyDecisionIndex}; " +
                    "reached/refused/degraded=${stop.reached}/${stop.refused}/${stop.degraded}."
            )
        }
    }
    appendLine()
    appendLine(
        "Internal search denominators are separate because the current diagnostics do not retain " +
            "candidate cardinality by simulated decision family:"
    )
    appendLine()
    appendLine("| Run | Live declared policy | Search-world transition executions | Compressed singleton passes | Opponent model | Root rollout | Opponent rollout | Belief private | Search-internal policy total | Comparator |")
    appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    report.arenaRuns.forEach { run ->
        appendLine(
            "| ${run.id} | ${run.liveDeclaredPolicyDecisions} | ${run.searchWorldSteps} | " +
                "${run.compressedPolicySingletonSimulationPasses} | " +
                "${run.opponentModelSimulationDecisions} | ${run.rootRolloutSimulationDecisions} | " +
                "${run.opponentRolloutSimulationDecisions} | ${run.beliefPrivateChoiceDecisions} | " +
                "${run.policySelectedSimulationDecisions} | ${run.heuristicComparatorDecisions} |"
        )
    }
    appendLine()
    report.arenaRuns.forEach { run ->
        appendLine("### ${run.id} by reached root family")
        appendLine()
        appendLine("| Family | Reached | Multiple | Cancel/decline | Rules singleton | Profile singleton | Script/policy | Searched |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        run.byFamily.forEach { family ->
            appendLine(
                "| ${family.family} | ${family.reachedDecisions} | ${family.multiAlternativeDecisions} | " +
                    "${family.cancelOrDeclineAvailableDecisions} | ${family.rulesProvenSingletonDecisions} | " +
                    "${family.profileSingletonAutomaticDecisions} | ${family.scriptOrPolicySelectedDecisions} | " +
                    "${family.searchedDecisions} |"
            )
        }
        appendLine()
    }
    appendLine("## Concrete mana-source survival case")
    appendLine()
    appendLine(
        "All four one-source payments satisfy ${report.manaSourceSurvival.requiredCost}. " +
            "The fixed suggestion taps ${report.manaSourceSurvival.fixedAutoPaySuggestion.single()}, " +
            "which removes the declared Rockface activation; paying with " +
            "${report.manaSourceSurvival.preservingPayment.single()} preserves it. The scoped action-availability " +
            "regret is ${report.manaSourceSurvival.scopedActionAvailabilityRegret}; its unit is " +
            "${report.manaSourceSurvival.regretUnit}."
    )
    appendLine()
    appendLine(report.manaSourceSurvival.limitation)
}
