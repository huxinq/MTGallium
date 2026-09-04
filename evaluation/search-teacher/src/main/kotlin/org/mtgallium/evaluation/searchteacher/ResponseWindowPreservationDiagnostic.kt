package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumKnownDeckBeliefWorldSource
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.InformationSetSearchDiagnostics
import org.mtgallium.agent.infoset.core.PolicyAnnotatedSearchWorld
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.ProgressiveSearchWorld
import org.mtgallium.agent.infoset.core.SearchStepResult
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticActionTargetRelation
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.Weighted
import org.mtgallium.agent.infoset.core.exactSingletonPassOrNull
import org.mtgallium.agent.searchteacher.SearchTeacherPilotSpecification
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

internal const val M01_RESPONSE_WINDOW_DIAGNOSTIC_VERSION =
    "m01-response-window-preservation-v2"

@Serializable
internal enum class M01WindowKind {
    ENGINE_PENDING_DECISION,
    STACK_PRIORITY_WINDOW,
}

@Serializable
internal enum class M01ComparisonDisposition {
    EVIDENCE_ELIGIBLE,
    REFUSED_OR_STOPPED,
    INVALIDATING_REPLACEMENT,
    REJECTED_TRANSITION,
    INPUT_OR_SEED_MISMATCH,
}

@Serializable
internal enum class M01ValueStatus {
    REFUSED_NO_INDEPENDENT_OR_EXACT_STRATEGIC_REFERENCE,
    EXACT_SCOPED_ACTION_AVAILABILITY,
}

@Serializable
internal data class M01ActionSummary(
    val signature: String,
    val operationFamily: SemanticOperationFamily,
    val intentKind: SemanticActionIntentKind,
    val sourceCardName: String?,
    val targetRelations: Set<SemanticActionTargetRelation>,
)

@Serializable
internal data class M01FamilyAlternatives(
    val family: SemanticOperationFamily,
    val alternatives: Int,
)

@Serializable
internal data class M01ResponseWindow(
    val decisionIndex: Int,
    val windowOrdinal: Int,
    val actingPlayer: String,
    val activePlayer: String?,
    val stackDepth: Int,
    val kind: M01WindowKind,
    val pendingDecisionKind: String?,
    val legalAlternativeCount: Int,
    val estimatedAlternativeCount: Long?,
    val exhaustive: Boolean,
    val alternativesByFamily: List<M01FamilyAlternatives>,
    val passAvailable: Boolean,
    val nonPassAlternatives: Int,
    val rulesProvenSingletonPass: Boolean,
    val chosenAction: M01ActionSummary?,
)

@Serializable
internal data class M01SubmissionTrace(
    val id: String,
    val runId: String,
    val submissionDecisionIndex: Int,
    val submittedBy: String,
    val submittedAction: M01ActionSummary,
    val stackDepthAfterSubmission: Int,
    val reachedThroughProductionStep: Boolean,
    val windows: List<M01ResponseWindow>,
    val maximumObservedStackDepth: Int,
    val reachedPendingDecision: Boolean,
    val reachedPriorityWindow: Boolean,
    val reachedRulesSingletonWindow: Boolean,
    val reachedMultiAlternativeWindow: Boolean,
    val completedOrExitedWindowChain: Boolean,
    val stopOrExitReason: String,
    val valueStatus: M01ValueStatus =
        M01ValueStatus.REFUSED_NO_INDEPENDENT_OR_EXACT_STRATEGIC_REFERENCE,
    val regretRefusal: String =
        "Legal response availability is measured, but no independent strategic value reference was supplied.",
)

@Serializable
internal data class M01PoolSourceFamily(
    val sourceCardName: String,
    val copiesInMainDeck: Int,
    val submissionFamily: SemanticOperationFamily,
    val printedNonManaAbilityDefinitions: Int,
)

@Serializable
internal data class M01NaturalArenaRun(
    val id: String,
    val gameSeed: Long,
    val terminal: Boolean,
    val disposition: GameRunDisposition = GameRunDisposition.LEGACY_UNCLASSIFIED,
    /** Typed O-04 stop accounting, deliberately free of winner/payoff/label fields. */
    val evidenceStop: EvidenceRunStopSummary? = null,
    val decisionLimitStop: Boolean,
    val decisions: Int,
    val submittedSpellsOrAbilities: Int,
    val reachedResponseWindows: Int,
    val pendingDecisionWindows: Int,
    val priorityWindows: Int,
    val rulesSingletonWindows: Int,
    val multiAlternativeWindows: Int,
    val nonPassAlternatives: Int,
    val submittingPlayers: Set<String>,
    val actingPlayersInWindows: Set<String>,
    val sourceFamiliesReached: List<M01PoolSourceFamily>,
    val replacementDecisions: Int,
    val replacementDecisionOpportunities: Int,
    val evidenceInvalidatingReplacements: Int,
    val illegalResponses: Int,
    val fallbacks: Int,
    val elapsedMillis: Double?,
    val exception: String?,
    val traces: List<M01SubmissionTrace>,
)

@Serializable
internal data class M01SuppressionAudit(
    val suppressedPriorityTransitionExecutions: Int,
    val suppressedMultiAlternativeTransitionExecutions: Int,
    val suppressedRulesSingletonTransitionExecutions: Int,
    val nonPassAlternativesRemovedAcrossExecutions: Int,
    val stoppedBeforePendingDecision: Int,
    val stoppedAfterStackResolved: Int,
    val stoppedAtTerminal: Int,
    val refusals: Int,
    val refusalReasons: Map<String, Int>,
)

@Serializable
internal data class M01PairedArm(
    val id: String,
    val completed: Boolean,
    val chosenAction: M01ActionSummary?,
    val evaluatedSystemRootValue: Double?,
    val beliefMillis: Double,
    val searchMillis: Double,
    val totalMillis: Double,
    val acceptedParticles: Int,
    val rejectedParticles: Int,
    val beliefFailures: Map<String, Int>,
    val nodes: Int?,
    val maximumDepth: Int?,
    val exhaustiveNodes: Int?,
    val nonExhaustiveNodes: Int?,
    val wideningEvents: Int?,
    val searchWorldSteps: Int?,
    val rejectedTransitions: Int,
    val opponentPolicyDecisionOpportunities: Int,
    val replacementDecisions: Int,
    val evidenceInvalidatingReplacements: Int,
    val suppression: M01SuppressionAudit?,
    val stopOrRefusal: String?,
)

@Serializable
internal data class M01PairedRootComparison(
    val caseId: String,
    val rootSeed: Long,
    val beliefSeed: Long,
    val searchSeed: Long,
    val rootInformationDigest: String,
    val candidateSignatureDigest: String,
    val legalRootActions: Int,
    val legalRootFamilies: List<M01FamilyAlternatives>,
    val preserved: M01PairedArm,
    val diagnosticNoResponse: M01PairedArm,
    val pairedInputsAndSeedsMatched: Boolean,
    val stopDispositionMatched: Boolean,
    val replacementDispositionMatched: Boolean,
    val comparisonDisposition: M01ComparisonDisposition,
    val rawChosenRootActionChanged: Boolean?,
    val eligibleChosenRootActionChanged: Boolean?,
    val rawEvaluatedSystemRootValueDelta: Double?,
    val eligibleEvaluatedSystemRootValueDelta: Double?,
    val evaluatedSystemRootValueInterpretation: String =
        "Diagnostic-no-response rootValue minus preserved-response rootValue; a descriptive output of the evaluated search leaf and opponent policies, not an independent strategic value.",
    val strategicValueAndRegretStatus: M01ValueStatus =
        M01ValueStatus.REFUSED_NO_INDEPENDENT_OR_EXACT_STRATEGIC_REFERENCE,
    val strategicValueAndRegretRefusal: String =
        "Raw rootValue and its delta are reported descriptively, but strategic value and regret are refused because the search leaf and opponent policies are outputs of the evaluated system rather than an independent reference.",
)

@Serializable
internal data class M01SearchConfiguration(
    val profileId: String,
    val particles: Int,
    val simulations: Int,
    val maximumPolicyDecisions: Int,
    val maximumQuiescenceDecisions: Int,
    val explorationConstant: Double,
    val leafStateSource: String,
    val leafEvaluator: String,
    val actionSpaceProfile: String,
    val counterfactual: String =
        "Diagnostic-only wrapper forces pass at stack-priority windows after submitted spells or non-mana abilities; it never suppresses engine pending decisions.",
)

@Serializable
internal data class M01ResponseWindowReport(
    val schemaVersion: Int = 2,
    val documentKind: String = "work-only-remediation-diagnostic",
    val diagnosticVersion: String = M01_RESPONSE_WINDOW_DIAGNOSTIC_VERSION,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val baseSeed: Long,
    val deckFixturePath: String,
    val deckId: String,
    val deckManifestSha256: String,
    val cardPoolSha256: String,
    val mainDeckCards: Int,
    val distinctMainDeckCards: Int,
    val searchConfiguration: M01SearchConfiguration,
    val poolSourceFamilies: List<M01PoolSourceFamily>,
    val targetedTraces: List<M01SubmissionTrace>,
    val naturalArenaRuns: List<M01NaturalArenaRun>,
    val pairedComparisons: List<M01PairedRootComparison>,
    val reachedPoolSourceFamilies: Int,
    val eligiblePoolSourceFamilies: Int,
    val targetedWindows: Int,
    val naturalWindows: Int,
    val pendingDecisionWindows: Int,
    val priorityWindows: Int,
    val rulesSingletonWindows: Int,
    val multiAlternativeWindows: Int,
    val eligiblePairedComparisons: Int,
    val changedChosenRootActions: Int,
    val totalPairedComparisons: Int,
    val naturalReplacementDecisions: Int,
    val naturalReplacementDecisionOpportunities: Int,
    val pairedReplacementDecisions: Int,
    val pairedReplacementDecisionOpportunities: Int,
    val evidenceInvalidatingPairedComparisons: Int,
    val permittedConclusion: String,
    val limitations: List<String>,
)

internal class M01ResponseWindowDiagnostic(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val outerCommit: String,
    private val argentumCommit: String,
    private val particles: Int = SearchTeacherPilotSpecification.frozenMonoRed().particles,
    private val simulations: Int = SearchTeacherPilotSpecification.frozenMonoRed().simulations,
    private val maximumPolicyDecisions: Int =
        SearchTeacherPilotSpecification.frozenMonoRed().maxPolicyDecisions,
    private val pairedCaseIds: List<String> = DEFAULT_PAIRED_CASE_IDS,
    private val naturalGameCount: Int = 2,
    private val representationBoundaryDetector: RepresentationBoundaryDetector? = null,
) {
    init {
        require(particles > 0)
        require(simulations > 0)
        require(maximumPolicyDecisions > 0)
        require(pairedCaseIds.isNotEmpty())
        require(naturalGameCount > 0)
    }

    fun run(baseSeed: Long): M01ResponseWindowReport {
        val pilot = SearchTeacherPilotSpecification.frozenMonoRed()
        val sourceFamilies = poolSourceFamilies(registry, manifest)
        val targeted = targetedResponseTraces(baseSeed)
        val natural = naturalArenaRuns(baseSeed, sourceFamilies)
        val paired = pairedComparisons(baseSeed, pilot)
        val allTraces = targeted + natural.flatMap(M01NaturalArenaRun::traces)
        val allWindows = allTraces.flatMap(M01SubmissionTrace::windows)
        val reachedCells = allTraces.map {
            it.submittedAction.sourceCardName to it.submittedAction.operationFamily
        }.toSet()
        val eligiblePairs = paired.filter {
            it.comparisonDisposition == M01ComparisonDisposition.EVIDENCE_ELIGIBLE
        }
        return M01ResponseWindowReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = outerCommit,
            argentumCommit = argentumCommit,
            baseSeed = baseSeed,
            deckFixturePath = "fixtures/decks/mono-red-standard-2026-07-30.json",
            deckId = manifest.id,
            deckManifestSha256 = manifest.deckHash(),
            cardPoolSha256 = manifest.cardPoolHash(),
            mainDeckCards = manifest.mainDeck.values.sum(),
            distinctMainDeckCards = manifest.mainDeck.size,
            searchConfiguration = M01SearchConfiguration(
                profileId = pilot.id,
                particles = particles,
                simulations = simulations,
                maximumPolicyDecisions = maximumPolicyDecisions,
                maximumQuiescenceDecisions = 32,
                explorationConstant = pilot.explorationConstant,
                leafStateSource = pilot.leaf.stateSource.name,
                leafEvaluator = pilot.leaf.evaluator.evaluatorId,
                actionSpaceProfile = pilot.actionSpaceProfile.name,
            ),
            poolSourceFamilies = sourceFamilies,
            targetedTraces = targeted,
            naturalArenaRuns = natural,
            pairedComparisons = paired,
            reachedPoolSourceFamilies = sourceFamilies.count { cell ->
                cell.sourceCardName to cell.submissionFamily in reachedCells
            },
            eligiblePoolSourceFamilies = sourceFamilies.size,
            targetedWindows = targeted.sumOf { it.windows.size },
            naturalWindows = natural.sumOf(M01NaturalArenaRun::reachedResponseWindows),
            pendingDecisionWindows = allWindows.count { it.kind == M01WindowKind.ENGINE_PENDING_DECISION },
            priorityWindows = allWindows.count { it.kind == M01WindowKind.STACK_PRIORITY_WINDOW },
            rulesSingletonWindows = allWindows.count(M01ResponseWindow::rulesProvenSingletonPass),
            multiAlternativeWindows = allWindows.count { it.legalAlternativeCount > 1 },
            eligiblePairedComparisons = eligiblePairs.size,
            changedChosenRootActions = eligiblePairs.count { it.eligibleChosenRootActionChanged == true },
            totalPairedComparisons = paired.size,
            naturalReplacementDecisions = natural.sumOf(M01NaturalArenaRun::replacementDecisions),
            naturalReplacementDecisionOpportunities =
                natural.sumOf(M01NaturalArenaRun::replacementDecisionOpportunities),
            pairedReplacementDecisions = paired.sumOf {
                it.preserved.replacementDecisions + it.diagnosticNoResponse.replacementDecisions
            },
            pairedReplacementDecisionOpportunities = paired.sumOf {
                it.preserved.opponentPolicyDecisionOpportunities +
                    it.diagnosticNoResponse.opponentPolicyDecisionOpportunities
            },
            evidenceInvalidatingPairedComparisons = paired.count {
                it.comparisonDisposition == M01ComparisonDisposition.INVALIDATING_REPLACEMENT
            },
            permittedConclusion =
                "The production one-action boundary preserved every engine-emitted decision in the reached " +
                    "targeted and seeded Arena population; only eligible, unreplaced paired roots may support " +
                    "the reported changed-choice and branching comparison.",
            limitations = listOf(
                "The source-family denominator covers castable main-deck card definitions and printed non-mana activated abilities; it excludes sideboard cards, mana abilities, triggered abilities, and mechanics outside the fixture.",
                "Targeted fixtures establish reachability for their supplied engine states. Natural frequencies come from a small, seed-scoped heuristic mirror and are not population estimates.",
                "The diagnostic-only counterfactual deliberately suppresses legal stack-priority choices by selecting pass. Suppression is never classified as legal or strategic equivalence and is not production behavior.",
                "Engine pending decisions are recorded and never suppressed by the counterfactual.",
                "Candidate generation can still omit a legal Magic response. This diagnostic covers only engine-emitted, adapter-enumerated alternatives.",
                "Wall-clock latency is a single-host observation without randomized repetition or confidence intervals.",
                "Search values use the evaluated leaf and opponent policies, so strategic value and regret are refused without an independent or exact scoped reference.",
                "A replacement or rejected transition makes its paired changed-choice comparison ineligible rather than silently entering the denominator.",
            ),
        )
    }

    private fun targetedResponseTraces(baseSeed: Long): List<M01SubmissionTrace> {
        val factory = TacticalHorizonScenarioFactory(registry, manifest)
        return listOf(
            targetedShockStackRace(factory, baseSeed),
            targetedActivatedAbility(factory),
            targetedPendingDecision(factory),
        ).flatten()
    }

    private fun naturalArenaRuns(
        baseSeed: Long,
        sourceFamilies: List<M01PoolSourceFamily>,
    ): List<M01NaturalArenaRun> {
        val profile = SearchTeacherArena.smokeProfile()
        val arena = representationBoundaryDetector?.let { detector ->
            SearchTeacherArena(
                registry,
                manifest,
                profile,
                baseSeed,
                representationBoundaryDetector = detector,
            )
        } ?: SearchTeacherArena(registry, manifest, profile, baseSeed)
        return (0 until naturalGameCount).map { index ->
            val runId = "natural-heuristic-mirror-${index + 1}"
            val gameSeed = ComponentSeeds.derive(baseSeed, M01_RESPONSE_WINDOW_DIAGNOSTIC_VERSION, "arena", index)
            val recorder = M01WindowRecorder(runId)
            val result = arena.play(
                gameId = "00000000-0000-4000-8100-${(index + 1).toString().padStart(12, '0')}",
                gameSeed = gameSeed,
                p0Policy = ArenaPolicyKind.HEURISTIC,
                p1Policy = ArenaPolicyKind.HEURISTIC,
                rootProbe = recorder::recordRoot,
                acceptedStepProbe = recorder::recordAcceptedStep,
            )
            recorder.finish(if (result.terminal) "GAME_ENDED" else "GAME_STOPPED")
            val traces = recorder.traces()
            val windows = traces.flatMap(M01SubmissionTrace::windows)
            val decisions = result.liveOpponentPolicyDecisions
            val searchDecisions = result.searchOpponentPolicyDecisions
            val comparator = result.heuristicComparatorDecisions
            val reached = traces.mapNotNull { trace ->
                val source = trace.submittedAction.sourceCardName ?: return@mapNotNull null
                sourceFamilies.singleOrNull {
                    it.sourceCardName == source &&
                        it.submissionFamily == trace.submittedAction.operationFamily
                }
            }.distinct().sortedWith(
                compareBy<M01PoolSourceFamily> { it.sourceCardName }
                    .thenBy { it.submissionFamily.name }
            )
            M01NaturalArenaRun(
                id = runId,
                gameSeed = gameSeed,
                terminal = result.terminal,
                disposition = result.disposition,
                evidenceStop = result.evidenceRunStopSummary(),
                decisionLimitStop = result.disposition == GameRunDisposition.STOPPED_LIMIT,
                decisions = result.decisions,
                submittedSpellsOrAbilities = traces.size,
                reachedResponseWindows = windows.size,
                pendingDecisionWindows = windows.count { it.kind == M01WindowKind.ENGINE_PENDING_DECISION },
                priorityWindows = windows.count { it.kind == M01WindowKind.STACK_PRIORITY_WINDOW },
                rulesSingletonWindows = windows.count(M01ResponseWindow::rulesProvenSingletonPass),
                multiAlternativeWindows = windows.count { it.legalAlternativeCount > 1 },
                nonPassAlternatives = windows.sumOf(M01ResponseWindow::nonPassAlternatives),
                submittingPlayers = traces.map(M01SubmissionTrace::submittedBy).toSet(),
                actingPlayersInWindows = windows.map(M01ResponseWindow::actingPlayer).toSet(),
                sourceFamiliesReached = reached,
                replacementDecisions = decisions.replacementDecisions + searchDecisions.replacementDecisions +
                    comparator.replacementDecisions,
                replacementDecisionOpportunities = decisions.decisions + searchDecisions.decisions +
                    comparator.decisions,
                evidenceInvalidatingReplacements = decisions.evidenceInvalidatingReplacements +
                    searchDecisions.evidenceInvalidatingReplacements +
                    comparator.evidenceInvalidatingReplacements,
                illegalResponses = result.illegalResponses,
                fallbacks = result.fallbacks,
                elapsedMillis = result.elapsedMillis,
                exception = result.exception,
                traces = traces,
            )
        }
    }

    private fun pairedComparisons(
        baseSeed: Long,
        pilot: org.mtgallium.agent.searchteacher.SearchTeacherPilotSpecification,
    ): List<M01PairedRootComparison> {
        val cases = pairedCaseIds.map { id ->
            requireNotNull(TacticalHorizonCatalog.cases.singleOrNull { it.id == id }) {
                "Unknown M-01 paired case $id"
            }
        }
        val factory = TacticalHorizonScenarioFactory(registry, manifest, pilot.actionSpaceProfile)
        return cases.map { case -> pairedComparison(factory, case, baseSeed, pilot) }
    }

    private fun pairedComparison(
        factory: TacticalHorizonScenarioFactory,
        case: TacticalHorizonCase,
        baseSeed: Long,
        pilot: org.mtgallium.agent.searchteacher.SearchTeacherPilotSpecification,
    ): M01PairedRootComparison {
        val root = factory.create(case)
        val actor = requireNotNull(root.actorToAct())
        val information = root.informationState(actor)
        val expansion = root.expandChoices(2_048)
        require(expansion.isExhaustive) { "M-01 paired root ${case.id} must be exhaustive" }
        val candidateDigest = sha256(expansion.candidates.joinToString("\n") { it.signature })
        val beliefSeed = ComponentSeeds.derive(baseSeed, case.id, "m01-paired-belief")
        val searchSeed = ComponentSeeds.derive(baseSeed, case.id, "m01-paired-search")
        val preserved = runPairedArm(
            id = "preserved-response-production-path",
            root = root,
            actor = actor,
            beliefSeed = beliefSeed,
            searchSeed = searchSeed,
            pilot = pilot,
            suppressResponses = false,
        )
        val counterfactualRoot = factory.create(case)
        val counterfactualActor = requireNotNull(counterfactualRoot.actorToAct())
        val counterfactualInformation = counterfactualRoot.informationState(counterfactualActor)
        val counterfactualExpansion = counterfactualRoot.expandChoices(2_048)
        val counterfactual = runPairedArm(
            id = "diagnostic-only-no-response-counterfactual",
            root = counterfactualRoot,
            actor = counterfactualActor,
            beliefSeed = beliefSeed,
            searchSeed = searchSeed,
            pilot = pilot,
            suppressResponses = true,
        )
        val pairedInputs = actor == counterfactualActor &&
            information.informationStateDigest == counterfactualInformation.informationStateDigest &&
            expansion.isExhaustive == counterfactualExpansion.isExhaustive &&
            candidateDigest == sha256(
                counterfactualExpansion.candidates.joinToString("\n") { it.signature }
            )
        val stopMatched = when {
            preserved.completed && counterfactual.completed -> true
            !preserved.completed && !counterfactual.completed ->
                preserved.stopOrRefusal == counterfactual.stopOrRefusal
            else -> false
        }
        val replacementMatched =
            preserved.evidenceInvalidatingReplacements == 0 &&
                counterfactual.evidenceInvalidatingReplacements == 0
        val disposition = when {
            !pairedInputs -> M01ComparisonDisposition.INPUT_OR_SEED_MISMATCH
            preserved.evidenceInvalidatingReplacements > 0 ||
                counterfactual.evidenceInvalidatingReplacements > 0 ->
                M01ComparisonDisposition.INVALIDATING_REPLACEMENT
            preserved.rejectedTransitions > 0 || counterfactual.rejectedTransitions > 0 ->
                M01ComparisonDisposition.REJECTED_TRANSITION
            !preserved.completed || !counterfactual.completed || !stopMatched ->
                M01ComparisonDisposition.REFUSED_OR_STOPPED
            else -> M01ComparisonDisposition.EVIDENCE_ELIGIBLE
        }
        val rawChanged = if (preserved.chosenAction != null && counterfactual.chosenAction != null) {
            preserved.chosenAction.signature != counterfactual.chosenAction.signature
        } else null
        val rawRootValueDelta = preserved.evaluatedSystemRootValue?.let { preservedValue ->
            counterfactual.evaluatedSystemRootValue?.minus(preservedValue)
        }
        return M01PairedRootComparison(
            caseId = case.id,
            rootSeed = case.rootSeed,
            beliefSeed = beliefSeed,
            searchSeed = searchSeed,
            rootInformationDigest = information.informationStateDigest,
            candidateSignatureDigest = candidateDigest,
            legalRootActions = expansion.candidates.size,
            legalRootFamilies = familyCounts(expansion),
            preserved = preserved,
            diagnosticNoResponse = counterfactual,
            pairedInputsAndSeedsMatched = pairedInputs,
            stopDispositionMatched = stopMatched,
            replacementDispositionMatched = replacementMatched,
            comparisonDisposition = disposition,
            rawChosenRootActionChanged = rawChanged,
            eligibleChosenRootActionChanged = rawChanged.takeIf {
                disposition == M01ComparisonDisposition.EVIDENCE_ELIGIBLE
            },
            rawEvaluatedSystemRootValueDelta = rawRootValueDelta,
            eligibleEvaluatedSystemRootValueDelta = rawRootValueDelta.takeIf {
                disposition == M01ComparisonDisposition.EVIDENCE_ELIGIBLE
            },
        )
    }

    private fun runPairedArm(
        id: String,
        root: ArgentumSearchWorld,
        actor: String,
        beliefSeed: Long,
        searchSeed: Long,
        pilot: org.mtgallium.agent.searchteacher.SearchTeacherPilotSpecification,
        suppressResponses: Boolean,
    ): M01PairedArm {
        var beliefMillis = 0.0
        var searchMillis = 0.0
        var acceptedParticles = 0
        var rejectedParticles = 0
        var beliefFailures: Map<String, Int> = emptyMap()
        val audit = MutableM01SuppressionAudit()
        return runCatching {
            val beliefStarted = System.nanoTime()
            val information = root.informationState(actor)
            val sampled = ArgentumKnownDeckBeliefWorldSource(root).sample(
                information,
                mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
                beliefSeed,
                particles,
            )
            beliefMillis = elapsedMillisM01(beliefStarted)
            acceptedParticles = sampled.diagnostics.acceptedParticles
            rejectedParticles = sampled.diagnostics.rejectedParticles
            beliefFailures = sampled.diagnostics.failures
            val belief = if (suppressResponses) {
                BeliefBatch(
                    sampled.particles.map { particle ->
                        Weighted<SearchWorld>(M01DiagnosticNoResponseWorld(particle.value, audit), particle.weight)
                    },
                    sampled.diagnostics,
                )
            } else sampled
            val config = InformationSetSearchConfig(
                simulations = simulations,
                explorationConstant = pilot.explorationConstant,
                maxPolicyDecisions = maximumPolicyDecisions,
                maxQuiescenceDecisions = 32,
                leaf = pilot.leaf,
            )
            val search = SearchTeacherSearchFactory.create(
                config = config,
                opponentPolicy = defaultMonoRedOpponentPolicy(),
            )
            val searchStarted = System.nanoTime()
            val result = search.search(actor, belief, searchSeed)
            searchMillis = elapsedMillisM01(searchStarted)
            pairedArm(
                id = id,
                result = result.diagnostics,
                chosen = result.chosen,
                rootValue = result.rootValue,
                beliefMillis = beliefMillis,
                searchMillis = searchMillis,
                acceptedParticles = acceptedParticles,
                rejectedParticles = rejectedParticles,
                beliefFailures = beliefFailures,
                suppression = audit.snapshot().takeIf { suppressResponses },
            )
        }.getOrElse { failure ->
            M01PairedArm(
                id = id,
                completed = false,
                chosenAction = null,
                evaluatedSystemRootValue = null,
                beliefMillis = beliefMillis,
                searchMillis = searchMillis,
                totalMillis = beliefMillis + searchMillis,
                acceptedParticles = acceptedParticles,
                rejectedParticles = rejectedParticles,
                beliefFailures = beliefFailures,
                nodes = null,
                maximumDepth = null,
                exhaustiveNodes = null,
                nonExhaustiveNodes = null,
                wideningEvents = null,
                searchWorldSteps = null,
                rejectedTransitions = 0,
                opponentPolicyDecisionOpportunities = 0,
                replacementDecisions = 0,
                evidenceInvalidatingReplacements = 0,
                suppression = audit.snapshot().takeIf { suppressResponses },
                stopOrRefusal = failure::class.simpleName + ":" + failure.message.orEmpty(),
            )
        }
    }

    private fun pairedArm(
        id: String,
        result: InformationSetSearchDiagnostics,
        chosen: SemanticChoice,
        rootValue: Double,
        beliefMillis: Double,
        searchMillis: Double,
        acceptedParticles: Int,
        rejectedParticles: Int,
        beliefFailures: Map<String, Int>,
        suppression: M01SuppressionAudit?,
    ): M01PairedArm {
        val decisions = result.opponentModelPolicyDecisions +
            result.rootRolloutPolicyDecisions + result.opponentRolloutPolicyDecisions
        return M01PairedArm(
            id = id,
            completed = true,
            chosenAction = chosen.summary(),
            evaluatedSystemRootValue = rootValue,
            beliefMillis = beliefMillis,
            searchMillis = searchMillis,
            totalMillis = beliefMillis + searchMillis,
            acceptedParticles = acceptedParticles,
            rejectedParticles = rejectedParticles,
            beliefFailures = beliefFailures,
            nodes = result.nodes,
            maximumDepth = result.maximumDepth,
            exhaustiveNodes = result.exhaustiveNodes,
            nonExhaustiveNodes = result.nonExhaustiveNodes,
            wideningEvents = result.wideningEvents,
            searchWorldSteps = result.searchWorldSteps,
            rejectedTransitions = result.rejectedTransitions,
            opponentPolicyDecisionOpportunities = decisions.decisions,
            replacementDecisions = decisions.replacementDecisions,
            evidenceInvalidatingReplacements = decisions.evidenceInvalidatingReplacements,
            suppression = suppression,
            stopOrRefusal = null,
        )
    }

    companion object {
        val DEFAULT_PAIRED_CASE_IDS = listOf(
            "immediate-01",
            "immediate-04",
            "immediate-05",
            "immediate-06",
            "within-turn-02",
            "within-turn-03",
        )
    }
}

private data class MutableM01Submission(
    val id: String,
    val runId: String,
    val submissionDecisionIndex: Int,
    val submittedBy: String,
    val submittedAction: M01ActionSummary,
    val stackDepthAfterSubmission: Int,
    val reachedThroughProductionStep: Boolean,
    val windows: MutableList<M01ResponseWindow> = mutableListOf(),
    var completedOrExitedWindowChain: Boolean = false,
    var stopOrExitReason: String = "RUN_STILL_ACTIVE",
) {
    fun immutable(): M01SubmissionTrace = M01SubmissionTrace(
        id = id,
        runId = runId,
        submissionDecisionIndex = submissionDecisionIndex,
        submittedBy = submittedBy,
        submittedAction = submittedAction,
        stackDepthAfterSubmission = stackDepthAfterSubmission,
        reachedThroughProductionStep = reachedThroughProductionStep,
        windows = windows.toList(),
        maximumObservedStackDepth = windows.maxOfOrNull(M01ResponseWindow::stackDepth)
            ?: stackDepthAfterSubmission,
        reachedPendingDecision = windows.any { it.kind == M01WindowKind.ENGINE_PENDING_DECISION },
        reachedPriorityWindow = windows.any { it.kind == M01WindowKind.STACK_PRIORITY_WINDOW },
        reachedRulesSingletonWindow = windows.any(M01ResponseWindow::rulesProvenSingletonPass),
        reachedMultiAlternativeWindow = windows.any { it.legalAlternativeCount > 1 },
        completedOrExitedWindowChain = completedOrExitedWindowChain,
        stopOrExitReason = stopOrExitReason,
    )
}

private class M01WindowRecorder(private val runId: String) {
    private val submissions = mutableListOf<MutableM01Submission>()
    private val activeStack = mutableListOf<MutableM01Submission>()
    private val recordedWindowByDecision = mutableMapOf<Int, MutableM01Submission>()

    fun beginExternalSubmission(
        submittedBy: String,
        sourceCardName: String,
        family: SemanticOperationFamily,
        stackDepth: Int,
        submissionDecisionIndex: Int = -1,
    ) {
        val intent = when (family) {
            SemanticOperationFamily.CAST_SPELL -> SemanticActionIntentKind.CAST_SPELL
            SemanticOperationFamily.ACTIVATE_ABILITY -> SemanticActionIntentKind.ACTIVATE_ABILITY
            else -> error("External M-01 submission must be a spell or non-mana ability")
        }
        val submission = MutableM01Submission(
            id = "$runId-submission-${submissions.size}",
            runId = runId,
            submissionDecisionIndex = submissionDecisionIndex,
            submittedBy = submittedBy,
            submittedAction = M01ActionSummary(
                signature = "fixture:$runId:$sourceCardName:$family",
                operationFamily = family,
                intentKind = intent,
                sourceCardName = sourceCardName,
                targetRelations = emptySet(),
            ),
            stackDepthAfterSubmission = stackDepth,
            reachedThroughProductionStep = true,
        )
        submissions += submission
        activeStack += submission
    }

    fun recordRoot(world: ArgentumSearchWorld, actor: String, decisionIndex: Int) {
        val information = world.informationState(actor)
        val observation = information.observation
        pruneResolved(observation.stack.size, observation.pendingDecision != null)
        val active = activeStack.lastOrNull() ?: return
        if (observation.stack.isEmpty() && observation.pendingDecision == null) return
        val expansion = world.expandChoices(2_048)
        val pending = observation.pendingDecision
        active.windows += M01ResponseWindow(
            decisionIndex = decisionIndex,
            windowOrdinal = active.windows.size,
            actingPlayer = actor,
            activePlayer = observation.activePlayerId,
            stackDepth = observation.stack.size,
            kind = if (pending != null) {
                M01WindowKind.ENGINE_PENDING_DECISION
            } else {
                M01WindowKind.STACK_PRIORITY_WINDOW
            },
            pendingDecisionKind = pending?.decisionKind,
            legalAlternativeCount = expansion.candidates.size,
            estimatedAlternativeCount = expansion.estimatedCandidateCount,
            exhaustive = expansion.isExhaustive,
            alternativesByFamily = familyCounts(expansion),
            passAvailable = expansion.candidates.any {
                it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
            },
            nonPassAlternatives = if (pending == null) {
                expansion.candidates.count {
                    it.operationFamily != SemanticOperationFamily.PASS_PRIORITY
                }
            } else {
                0
            },
            rulesProvenSingletonPass = expansion.exactSingletonPassOrNull() != null,
            chosenAction = null,
        )
        recordedWindowByDecision[decisionIndex] = active
    }

    fun recordAcceptedStep(
        world: ArgentumSearchWorld,
        actor: String,
        decisionIndex: Int,
        choice: SemanticChoice,
        step: SearchStepResult,
    ) {
        require(step.accepted)
        recordedWindowByDecision.remove(decisionIndex)?.let { owner ->
            val index = owner.windows.indexOfLast { it.decisionIndex == decisionIndex }
            if (index >= 0) owner.windows[index] = owner.windows[index].copy(chosenAction = choice.summary())
        }
        if (choice.actionIntent.kind in SUBMITTED_STACK_INTENTS) {
            val stackDepth = world.informationState(requireNotNull(world.actorToAct())).observation.stack.size
            val submission = MutableM01Submission(
                id = "$runId-submission-${submissions.size}",
                runId = runId,
                submissionDecisionIndex = decisionIndex,
                submittedBy = actor,
                submittedAction = choice.summary(),
                stackDepthAfterSubmission = stackDepth,
                reachedThroughProductionStep = true,
            )
            submissions += submission
            activeStack += submission
        }
        val nextActor = world.actorToAct()
        if (nextActor == null) {
            finish("TERMINAL_AFTER_ACCEPTED_STEP")
        } else {
            val observation = world.informationState(nextActor).observation
            pruneResolved(observation.stack.size, observation.pendingDecision != null)
        }
    }

    fun finish(reason: String) {
        activeStack.forEach { active ->
            active.completedOrExitedWindowChain = true
            active.stopOrExitReason = reason
        }
        activeStack.clear()
        submissions.filterNot(MutableM01Submission::completedOrExitedWindowChain).forEach { trace ->
            trace.completedOrExitedWindowChain = true
            trace.stopOrExitReason = reason
        }
    }

    fun traces(): List<M01SubmissionTrace> = submissions.map(MutableM01Submission::immutable)

    private fun pruneResolved(stackDepth: Int, pendingDecision: Boolean) {
        if (pendingDecision) return
        while (activeStack.isNotEmpty() && activeStack.last().stackDepthAfterSubmission > stackDepth) {
            activeStack.removeLast().apply {
                completedOrExitedWindowChain = true
                stopOrExitReason = "SUBMITTED_STACK_ITEM_LEFT_STACK"
            }
        }
        if (stackDepth == 0) {
            activeStack.forEach { active ->
                active.completedOrExitedWindowChain = true
                active.stopOrExitReason = "STACK_EMPTIED"
            }
            activeStack.clear()
        }
    }
}

private fun targetedShockStackRace(
    factory: TacticalHorizonScenarioFactory,
    baseSeed: Long,
): List<M01SubmissionTrace> {
    val case = TacticalHorizonCatalog.cases.single { it.id == "immediate-01" }
    val world = factory.create(case)
    val recorder = M01WindowRecorder("targeted-shock-stack-race-${baseSeed.toString(16)}")
    var decision = 0
    fun take(choice: SemanticChoice) {
        val actor = requireNotNull(world.actorToAct())
        recorder.recordRoot(world, actor, decision)
        val step = world.step(choice)
        require(step.accepted) { "Targeted Shock trace rejected ${choice.signature}" }
        recorder.recordAcceptedStep(world, actor, decision, choice, step)
        decision++
    }
    fun expansion(): PolicyExpansion = world.expandChoices(2_048).also {
        require(it.isExhaustive) { "Targeted Shock trace requires exhaustive actions" }
    }
    fun shockAtOpponent(): SemanticChoice? = expansion().candidates.singleOrNull {
        it.actionIntent.kind == SemanticActionIntentKind.CAST_SPELL &&
            it.actionIntent.sourceCardName == "Shock" &&
            SemanticActionTargetRelation.OPPONENT_PLAYER in it.actionIntent.targetRelations
    }
    take(requireNotNull(shockAtOpponent()) { "Initial Shock was not legal" })
    var originalCasterPassed = false
    var opponentResponseSubmitted = false
    var originalCasterNestedResponseSubmitted = false
    repeat(32) {
        if (world.terminalPayoff("p0") != null) return@repeat
        val actor = world.actorToAct() ?: return@repeat
        val current = expansion()
        val observation = world.informationState(actor).observation
        if (observation.stack.isEmpty() && observation.pendingDecision == null) return@repeat
        val responseShock = current.candidates.singleOrNull {
            it.actionIntent.kind == SemanticActionIntentKind.CAST_SPELL &&
                it.actionIntent.sourceCardName == "Shock" &&
                SemanticActionTargetRelation.OPPONENT_PLAYER in it.actionIntent.targetRelations
        }
        val selected = when {
            actor == "p0" && !originalCasterPassed ->
                current.candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
                    .also { originalCasterPassed = true }
            actor == "p1" && !opponentResponseSubmitted && responseShock != null ->
                responseShock.also { opponentResponseSubmitted = true }
            actor == "p1" && !opponentResponseSubmitted ->
                current.candidates.filter { it.operationFamily == SemanticOperationFamily.MANA_ABILITY }
                    .minByOrNull { it.signature }
                    ?: error("Opponent could neither produce mana nor cast the nested Shock")
            actor == "p0" && opponentResponseSubmitted &&
                !originalCasterNestedResponseSubmitted && responseShock != null ->
                responseShock.also { originalCasterNestedResponseSubmitted = true }
            else -> current.candidates.singleOrNull {
                it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
            } ?: error("Targeted Shock trace could not pass at decision $decision")
        }
        take(selected)
    }
    recorder.finish(
        if (world.terminalPayoff("p0") != null) "TARGETED_CHAIN_REACHED_TERMINAL"
        else "TARGETED_CHAIN_EXITED_OR_LIMITED"
    )
    return recorder.traces()
}

private fun targetedActivatedAbility(
    factory: TacticalHorizonScenarioFactory,
): List<M01SubmissionTrace> {
    val case = TacticalHorizonCatalog.cases.single { it.id == "immediate-04" }
    val world = factory.create(case)
    val recorder = M01WindowRecorder("targeted-hired-claw-ability")
    var decision = 0
    fun take(choice: SemanticChoice) {
        val actor = requireNotNull(world.actorToAct())
        recorder.recordRoot(world, actor, decision)
        val step = world.step(choice)
        require(step.accepted)
        recorder.recordAcceptedStep(world, actor, decision, choice, step)
        decision++
    }
    val activation = world.expandChoices(2_048).candidates.single {
        it.actionIntent.kind == SemanticActionIntentKind.ACTIVATE_ABILITY &&
            it.actionIntent.sourceCardName == "Hired Claw"
    }
    take(activation)
    repeat(16) {
        if (world.terminalPayoff("p0") != null) return@repeat
        val actor = world.actorToAct() ?: return@repeat
        val information = world.informationState(actor)
        if (information.observation.stack.isEmpty() && information.observation.pendingDecision == null) {
            return@repeat
        }
        val expansion = world.expandChoices(2_048)
        val pass = expansion.candidates.singleOrNull {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        } ?: return@repeat
        take(pass)
    }
    recorder.finish("TARGETED_ABILITY_CHAIN_EXITED")
    return recorder.traces()
}

private fun targetedPendingDecision(
    factory: TacticalHorizonScenarioFactory,
): List<M01SubmissionTrace> {
    val case = TacticalHorizonCatalog.cases.single { it.id == "within-turn-06" }
    val world = factory.create(case)
    val recorder = M01WindowRecorder("targeted-nova-pending-decision")
    recorder.beginExternalSubmission(
        submittedBy = "p0",
        sourceCardName = "Nova Hellkite",
        family = SemanticOperationFamily.CAST_SPELL,
        stackDepth = world.informationState(requireNotNull(world.actorToAct())).observation.stack.size,
    )
    recorder.recordRoot(world, requireNotNull(world.actorToAct()), 0)
    recorder.finish("CAPTURED_ENGINE_PENDING_DECISION_WITHOUT_SUPPRESSION")
    return recorder.traces()
}

internal fun poolSourceFamilies(
    registry: CardRegistry,
    manifest: DeckManifest,
): List<M01PoolSourceFamily> = manifest.mainDeck.entries.flatMap { (name, copies) ->
    val definition = registry.requireCard(name)
    buildList {
        if (!definition.isLand) {
            add(M01PoolSourceFamily(name, copies, SemanticOperationFamily.CAST_SPELL, 0))
        }
        val nonMana = definition.activatedAbilities.count {
            !it.isManaAbility && !it.isPlaneswalkerAbility
        }
        if (nonMana > 0) {
            add(M01PoolSourceFamily(name, copies, SemanticOperationFamily.ACTIVATE_ABILITY, nonMana))
        }
    }
}.sortedWith(
    compareBy<M01PoolSourceFamily> { it.sourceCardName }
        .thenBy { it.submissionFamily.name }
)

private val SUBMITTED_STACK_INTENTS = setOf(
    SemanticActionIntentKind.CAST_SPELL,
    SemanticActionIntentKind.ACTIVATE_ABILITY,
)

private fun SemanticChoice.summary(): M01ActionSummary = M01ActionSummary(
    signature = signature,
    operationFamily = operationFamily,
    intentKind = actionIntent.kind,
    sourceCardName = actionIntent.sourceCardName,
    targetRelations = actionIntent.targetRelations,
)

private fun familyCounts(expansion: PolicyExpansion): List<M01FamilyAlternatives> =
    expansion.candidates.groupingBy(SemanticChoice::operationFamily).eachCount().entries
        .sortedBy { it.key.name }
        .map { (family, count) -> M01FamilyAlternatives(family, count) }

private fun elapsedMillisM01(startedNanos: Long): Double =
    (System.nanoTime() - startedNanos) / 1_000_000.0

internal class MutableM01SuppressionAudit {
    private var suppressed = 0
    private var multi = 0
    private var singleton = 0
    private var removed = 0
    private var pendingStops = 0
    private var resolvedStops = 0
    private var terminalStops = 0
    private var refusals = 0
    private val reasons = mutableMapOf<String, Int>()

    fun recordSuppression(expansion: PolicyExpansion) {
        suppressed++
        if (expansion.candidates.size > 1) multi++
        if (expansion.exactSingletonPassOrNull() != null) singleton++
        removed += expansion.candidates.count {
            it.operationFamily != SemanticOperationFamily.PASS_PRIORITY
        }
    }

    fun stoppedAtPendingDecision() {
        pendingStops++
    }

    fun stoppedAfterResolution() {
        resolvedStops++
    }

    fun stoppedAtTerminal() {
        terminalStops++
    }

    fun refused(reason: String) {
        refusals++
        reasons[reason] = reasons.getOrDefault(reason, 0) + 1
    }

    fun snapshot(): M01SuppressionAudit = M01SuppressionAudit(
        suppressedPriorityTransitionExecutions = suppressed,
        suppressedMultiAlternativeTransitionExecutions = multi,
        suppressedRulesSingletonTransitionExecutions = singleton,
        nonPassAlternativesRemovedAcrossExecutions = removed,
        stoppedBeforePendingDecision = pendingStops,
        stoppedAfterStackResolved = resolvedStops,
        stoppedAtTerminal = terminalStops,
        refusals = refusals,
        refusalReasons = reasons.toSortedMap(),
    )
}

/** Evaluation-only counterfactual. Production worlds never use this wrapper. */
internal class M01DiagnosticNoResponseWorld(
    private val delegate: SearchWorld,
    private val audit: MutableM01SuppressionAudit,
    private val maximumSuppressedWindowsPerSubmission: Int = 64,
) : ProgressiveSearchWorld, PolicyAnnotatedSearchWorld {
    override fun actorToAct(): String? = delegate.actorToAct()

    override fun informationState(viewer: String) = delegate.informationState(viewer)

    override fun expandChoices(): PolicyExpansion = delegate.expandChoices()

    override fun expandChoices(limit: Int): PolicyExpansion =
        (delegate as? ProgressiveSearchWorld)?.expandChoices(limit) ?: delegate.expandChoices()

    override fun expandChoicesWithPolicyAnnotations(): PolicyExpansion =
        (delegate as? PolicyAnnotatedSearchWorld)?.expandChoicesWithPolicyAnnotations()
            ?: delegate.expandChoices()

    override fun expandChoicesWithPolicyAnnotations(limit: Int): PolicyExpansion =
        (delegate as? PolicyAnnotatedSearchWorld)?.expandChoicesWithPolicyAnnotations(limit)
            ?: expandChoices(limit)

    override fun step(choice: SemanticChoice): SearchStepResult {
        val first = delegate.step(choice)
        if (!first.accepted || choice.actionIntent.kind !in SUBMITTED_STACK_INTENTS) return first
        val forcedTransitions = first.forcedTransitions.toMutableList()
        var privateToActor = first.privateToActor
        repeat(maximumSuppressedWindowsPerSubmission) {
            val actor = delegate.actorToAct()
            if (actor == null) {
                audit.stoppedAtTerminal()
                return SearchStepResult(true, forcedTransitions = forcedTransitions, privateToActor = privateToActor)
            }
            val observation = delegate.informationState(actor).observation
            if (observation.pendingDecision != null) {
                audit.stoppedAtPendingDecision()
                return SearchStepResult(true, forcedTransitions = forcedTransitions, privateToActor = privateToActor)
            }
            if (observation.stack.isEmpty()) {
                audit.stoppedAfterResolution()
                return SearchStepResult(true, forcedTransitions = forcedTransitions, privateToActor = privateToActor)
            }
            val expansion = expandChoices(2_048)
            val passes = expansion.candidates.filter {
                it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
            }
            if (passes.size != 1) {
                val reason = "PASS_CANDIDATE_COUNT_${passes.size}"
                audit.refused(reason)
                return SearchStepResult(
                    accepted = false,
                    diagnostic = "M-01 diagnostic suppression refused: $reason",
                    forcedTransitions = forcedTransitions,
                    privateToActor = privateToActor,
                )
            }
            audit.recordSuppression(expansion)
            val pass = delegate.step(passes.single())
            if (!pass.accepted) {
                val reason = "ENGINE_REJECTED_FORCED_PASS"
                audit.refused(reason)
                return SearchStepResult(
                    accepted = false,
                    diagnostic = "M-01 diagnostic suppression refused: $reason (${pass.diagnostic})",
                    forcedTransitions = forcedTransitions + pass.forcedTransitions,
                    privateToActor = privateToActor || pass.privateToActor,
                )
            }
            forcedTransitions += pass.forcedTransitions
            privateToActor = privateToActor || pass.privateToActor
        }
        val reason = "SUPPRESSION_WINDOW_LIMIT_REACHED"
        audit.refused(reason)
        return SearchStepResult(
            accepted = false,
            diagnostic = "M-01 diagnostic suppression refused: $reason",
            forcedTransitions = forcedTransitions,
            privateToActor = privateToActor,
        )
    }

    override fun fork(): SearchWorld = M01DiagnosticNoResponseWorld(
        delegate = delegate.fork(),
        audit = audit,
        maximumSuppressedWindowsPerSubmission = maximumSuppressedWindowsPerSubmission,
    )

    override fun terminalPayoff(rootPlayer: String): Double? = delegate.terminalPayoff(rootPlayer)

    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double =
        delegate.sampledWorldLeafValue(rootPlayer, evaluatorId)
}

internal fun renderM01ResponseWindowReport(report: M01ResponseWindowReport): String = buildString {
    appendLine("# Engine-emitted response windows remained separate choices in the M-01 population")
    appendLine()
    appendLine(
        "The targeted fixtures and ${report.naturalArenaRuns.size} seeded frozen-deck games recorded " +
            "${report.targetedWindows + report.naturalWindows} response-window roots: " +
            "${report.priorityWindows} stack-priority windows and ${report.pendingDecisionWindows} " +
            "engine pending decisions. ${report.rulesSingletonWindows} windows were exhaustive pass-only " +
            "rules singletons and ${report.multiAlternativeWindows} exposed more than one legal adapter action."
    )
    appendLine(
        "The paired search comparison retained ${report.eligiblePairedComparisons}/" +
            "${report.totalPairedComparisons} roots after stop, rejection, and replacement disposition checks; " +
            "${report.changedChosenRootActions}/${report.eligiblePairedComparisons} eligible roots changed their " +
            "chosen action when the diagnostic counterfactual suppressed legal stack-priority choices."
    )
    appendLine(
        "This establishes the observed engine boundary only. It does not establish that the engine or action " +
            "generator exposes every response permitted by Magic, and it supplies no strategic-regret estimate."
    )
    appendLine()
    appendLine("## The declared main deck contains ${report.eligiblePoolSourceFamilies} spell-or-ability source families")
    appendLine()
    appendLine(
        "The denominator is one source/family cell for each castable main-deck card definition and each source " +
            "with at least one printed non-mana activated ability. ${report.reachedPoolSourceFamilies}/" +
            "${report.eligiblePoolSourceFamilies} cells were reached by the targeted or natural runs."
    )
    appendLine()
    appendLine("| Source | Main-deck copies | Submitted family | Printed non-mana abilities | Reached |")
    appendLine("| --- | ---: | --- | ---: | --- |")
    val reached = (report.targetedTraces + report.naturalArenaRuns.flatMap(M01NaturalArenaRun::traces))
        .map { it.submittedAction.sourceCardName to it.submittedAction.operationFamily }.toSet()
    report.poolSourceFamilies.forEach { source ->
        appendLine(
            "| ${source.sourceCardName} | ${source.copiesInMainDeck} | ${source.submissionFamily} | " +
                "${source.printedNonManaAbilityDefinitions} | " +
                "${source.sourceCardName to source.submissionFamily in reached} |"
        )
    }
    appendLine()
    appendLine("## Targeted traces reached both players, nested spells, an activated ability, and a pending decision")
    appendLine()
    appendLine("| Submission | Submitted by | Typed action | Windows | Max stack | Pending | Priority | Singleton | Multi | Exit |")
    appendLine("| --- | --- | --- | ---: | ---: | --- | --- | --- | --- | --- |")
    report.targetedTraces.forEach { trace ->
        appendLine(
            "| ${trace.id} | ${trace.submittedBy} | ${actionText(trace.submittedAction)} | " +
                "${trace.windows.size} | ${trace.maximumObservedStackDepth} | ${trace.reachedPendingDecision} | " +
                "${trace.reachedPriorityWindow} | ${trace.reachedRulesSingletonWindow} | " +
                "${trace.reachedMultiAlternativeWindow} | ${trace.stopOrExitReason} |"
        )
    }
    appendLine()
    appendLine("## Seeded production games retained every reached response root")
    appendLine()
    appendLine("| Run | Seed | Disposition | Terminal | Decisions | Submissions | Windows | Pending | Priority | Singleton | Multi | Non-pass alternatives | Replacements |")
    appendLine("| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    report.naturalArenaRuns.forEach { run ->
        appendLine(
            "| ${run.id} | ${run.gameSeed} | ${run.disposition} | ${run.terminal} | ${run.decisions} | " +
                "${run.submittedSpellsOrAbilities} | ${run.reachedResponseWindows} | " +
                "${run.pendingDecisionWindows} | ${run.priorityWindows} | ${run.rulesSingletonWindows} | " +
                "${run.multiAlternativeWindows} | ${run.nonPassAlternatives} | " +
                "${run.replacementDecisions}/${run.replacementDecisionOpportunities} |"
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
    appendLine("### Every recorded response root and its adapter alternatives")
    appendLine()
    appendLine("| Run/submission | Root | Actor | Kind | Stack | Alternatives | Families | Pass | Non-pass | Rules singleton | Chosen |")
    appendLine("| --- | ---: | --- | --- | ---: | ---: | --- | --- | ---: | --- | --- |")
    (report.targetedTraces + report.naturalArenaRuns.flatMap(M01NaturalArenaRun::traces)).forEach { trace ->
        trace.windows.forEach { window ->
            appendLine(
                "| ${trace.id} | ${window.decisionIndex} | ${window.actingPlayer} | ${window.kind}" +
                    (window.pendingDecisionKind?.let { "/$it" } ?: "") + " | ${window.stackDepth} | " +
                    "${window.legalAlternativeCount}/${window.estimatedAlternativeCount} | " +
                    "${window.alternativesByFamily.joinToString(", ") { "${it.family}:${it.alternatives}" }} | " +
                    "${window.passAvailable} | ${window.nonPassAlternatives} | " +
                    "${window.rulesProvenSingletonPass} | ${window.chosenAction?.let(::actionText) ?: "not selected in capture"} |"
            )
        }
    }
    appendLine()
    appendLine("## Suppressing responses changed only eligible paired roots in the reported denominator")
    appendLine()
    appendLine(
        "Both arms use the same root information digest, candidate signatures, belief seed, search seed, " +
            "particle count, simulation count, policy-decision limit, evaluator, and opponent policies. The " +
            "counterfactual differs only by forcing pass after a spell or non-mana ability is submitted. " +
            "Those forced passes are diagnostic suppression, not legal equivalence."
    )
    appendLine()
    appendLine("| Root | Legal actions | Preserved choice | Suppressed choice | Eligible change | Disposition | Root value P/N | Eligible Δ N−P | Nodes P/N | Depth P/N | Search ms P/N | Suppressed executions | Replacements P/N |")
    appendLine("| --- | ---: | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    report.pairedComparisons.forEach { pair ->
        appendLine(
            "| ${pair.caseId} | ${pair.legalRootActions} | " +
                "${pair.preserved.chosenAction?.let(::actionText) ?: pair.preserved.stopOrRefusal.orEmpty()} | " +
                "${pair.diagnosticNoResponse.chosenAction?.let(::actionText) ?: pair.diagnosticNoResponse.stopOrRefusal.orEmpty()} | " +
                "${pair.eligibleChosenRootActionChanged?.toString() ?: "excluded"} | " +
                "${pair.comparisonDisposition} | " +
                "${pair.preserved.evaluatedSystemRootValue?.let(::formatRootValue) ?: "-"}/" +
                "${pair.diagnosticNoResponse.evaluatedSystemRootValue?.let(::formatRootValue) ?: "-"} | " +
                "${pair.eligibleEvaluatedSystemRootValueDelta?.let(::formatRootValue) ?: "excluded"} | " +
                "${pair.preserved.nodes ?: "-"}/${pair.diagnosticNoResponse.nodes ?: "-"} | " +
                "${pair.preserved.maximumDepth ?: "-"}/${pair.diagnosticNoResponse.maximumDepth ?: "-"} | " +
                "${formatMillis(pair.preserved.searchMillis)}/${formatMillis(pair.diagnosticNoResponse.searchMillis)} | " +
                "${pair.diagnosticNoResponse.suppression?.suppressedPriorityTransitionExecutions ?: 0} | " +
                "${pair.preserved.replacementDecisions}/${pair.diagnosticNoResponse.replacementDecisions} |"
        )
    }
    appendLine()
    appendLine("`rootValue` and Δ N−P (diagnostic no-response minus preserved response) are raw outputs of the evaluated search leaf and opponent policies. They are reported descriptively, not as independent strategic value. No independent or exact strategic reference was supplied, so strategic value and regret are refused for every paired root.")
    appendLine()
    appendLine("## Metric and procedure definitions")
    appendLine()
    appendLine("- A response window is a reached root after a submitted spell or non-mana activated ability while the stack is nonempty, or an engine pending decision directly retained by that chain.")
    appendLine("- A rules singleton requires an exhaustive adapter expansion containing only pass. A one-action proposal that is not exhaustive is not called rules-forced.")
    appendLine("- A changed root action enters the numerator only when both paired arms completed from identical inputs and seeds with no rejected transition or evidence-invalidating replacement.")
    appendLine("- A root-value delta is eligible under the same disposition rule. It is the counterfactual raw `rootValue` minus the preserved raw `rootValue`; it is not a regret estimate.")
    appendLine("- Branching is reported as search nodes and maximum policy depth. Counterfactual suppression executions count actual transition executions; transition-cache hits need not execute another forced pass.")
    appendLine("- Latency is belief construction plus fixed-work search on this host. It is descriptive, not a hardware-independent performance estimate.")
    appendLine()
    appendLine("## Stops, refusals, and replacements")
    appendLine()
    appendLine(
        "Natural runs recorded ${report.naturalReplacementDecisions}/" +
            "${report.naturalReplacementDecisionOpportunities} replacements. Paired arms recorded " +
            "${report.pairedReplacementDecisions}/${report.pairedReplacementDecisionOpportunities}; " +
            "${report.evidenceInvalidatingPairedComparisons} paired roots were excluded for an invalidating replacement."
    )
    report.pairedComparisons.filter { it.comparisonDisposition != M01ComparisonDisposition.EVIDENCE_ELIGIBLE }
        .forEach { pair ->
            appendLine(
                "- ${pair.caseId}: ${pair.comparisonDisposition}; preserved=${pair.preserved.stopOrRefusal ?: "completed"}; " +
                    "counterfactual=${pair.diagnosticNoResponse.stopOrRefusal ?: "completed"}."
            )
        }
    appendLine()
    appendLine("## Limits on the conclusion")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Recommended next step")
    appendLine()
    appendLine(
        "Retain the production one-action boundary. Use this frozen work-only result to bound C-04 to the " +
            "reached engine-emitted windows; do not broaden it into a claim that every Magic response or every " +
            "frozen-pool source family was naturally reached."
    )
    appendLine()
    appendLine("## Further questions")
    appendLine()
    appendLine("- Which unreached source/family cells need an additional reachable fixture rather than more random games?")
    appendLine("- Does candidate recall include every legal instant-speed response for the reached engine states?")
    appendLine("- How stable are changed-choice and latency results across a predeclared multi-seed, randomized-order comparison?")
    appendLine()
    appendLine("Source: `${report.deckFixturePath}`; deck hash `${report.deckManifestSha256}`; card-pool hash `${report.cardPoolSha256}`; outer `${report.outerCommit}`; Argentum `${report.argentumCommit}`; base seed `${report.baseSeed}`.")
}

private fun actionText(action: M01ActionSummary): String = buildString {
    append(action.intentKind)
    action.sourceCardName?.let { append("/").append(it) }
    if (action.targetRelations.isNotEmpty()) {
        append("→").append(action.targetRelations.sortedBy { it.name }.joinToString("+"))
    }
}

private fun formatMillis(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

private fun formatRootValue(value: Double): String = "%.6f".format(java.util.Locale.ROOT, value)
