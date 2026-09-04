package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayHeader
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayTransition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.searchteacher.PolicyCompressionConfig
import org.mtgallium.agent.searchteacher.SearchReuseConfig
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val STANDALONE_MANA_TIMING_PROTOCOL = "standalone-mana-timing-selection-v1"

private const val EXPECTED_REVIEW_PACKET_SHA256 =
    "17c008c983014519a71e57ef7eec3dde8583452b6493485eb1c5403641b8c598"
private const val EXPECTED_UNBLINDING_SHA256 =
    "f41748be2e48f39fbaf4230638d7ed64629eeb96722fb8af1e74bbf0742c5fb2"
private const val EXPECTED_CONTINUATION_AUDIT_SHA256 =
    "bde593eefb5546c8a9675db329e6971ffd8179a984754cca4adfc7b406a68816"
private const val EXPECTED_INVENTORY_SHA256 =
    "9712dc58e75e3e96bd9a8bd995e4c2e043274cd683ad5a77bbea2cc518b324ad"
private const val CHARACTERIZATION_CHECKPOINT_COMMIT =
    "bf80da9c823e5ed5295e6683dbe2510502173ffb"
private const val CONTINUATION_CHECKPOINT_COMMIT =
    "b449d5eceb87b76c1d2553f429b897ab276764ab"
private const val SOURCE_RUN_DIRECTORY =
    "baseline-factorial-v1-sha256-9432649f71ad5f8ac7406e9f0c2ccc5fb7ffb4f3ae8e3e04fe9bb3f5cba55b09"
private const val MAXIMUM_EXPANSION = 2_048
private const val MAXIMUM_MANA_PATH_ACTIONS = 8

private val productionActionProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1
private val experimentalActionProfile = SearchActionSpaceProfile.EXPERIMENTAL_STANDALONE_MANA_TIMING_V1
private val experimentLeaf = LeafEvaluationConfig(
    LeafStateSource.BOUNDED_ROLLOUT,
    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
)

@Serializable
internal enum class StandaloneManaContinuationMatch {
    EXPLICIT_PRE_FLOAT_WITNESS,
    PROFILE_ADMITTED_WITNESS,
    NEITHER,
}

@Serializable
internal data class StandaloneManaSearchConfiguration(
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val explorationConstant: Double,
    val leaf: LeafEvaluationConfig,
    val beliefMode: BeliefMode,
    val beliefArchitecture: BeliefArchitecture,
    val opponentPolicyId: String,
    val policyCompressionEnabled: Boolean,
    val treeReuseEnabled: Boolean,
    val repetitions: Int,
    val seedSchedule: String,
    val pairedRandomness: String,
)

@Serializable
internal data class StandaloneManaSourceBindings(
    val retainedUnblindingPath: String,
    val retainedUnblindingSha256: String,
    val retainedReviewPacketPath: String,
    val retainedReviewPacketSha256: String,
    val retainedContinuationAuditPath: String,
    val retainedContinuationAuditSha256: String,
    val retainedRootInventoryPath: String,
    val retainedRootInventorySha256: String,
    val sourceRunIdentity: String,
    val sourceManifestSha256: String,
    val sourceOuterCommit: String,
    val characterizationCheckpointCommit: String,
    val continuationCheckpointCommit: String,
    val deckId: String,
    val deckHash: String,
    val cardPoolHash: String,
)

@Serializable
internal data class StandaloneManaOracleEvidence(
    val retainedProductionCandidateCount: Int,
    val currentProductionCandidateCount: Int,
    val retainedExactCandidateCount: Int,
    val currentExperimentalCandidateCount: Int,
    val targetSignature: String,
    val targetLabel: String,
    val targetAbsentFromProduction: Boolean,
    val targetPresentInExperimental: Boolean,
    val targetExecutable: Boolean,
    val productionProfileExhaustive: Boolean,
    val experimentalExpansionExhaustive: Boolean,
    val addedOperationFamilies: List<SemanticOperationFamily>,
    val removedCandidateCount: Int,
    val unrelatedAddedCandidateCount: Int,
    val nonManaCandidateSetsEqual: Boolean,
    val retainedExplicitWitnessAction: String,
    val retainedProfileWitnessAction: String,
    val explicitWitnessReproduced: Boolean,
    val profileWitnessReproduced: Boolean,
) {
    val passed: Boolean get() =
        retainedProductionCandidateCount == currentProductionCandidateCount &&
            retainedExactCandidateCount == currentExperimentalCandidateCount &&
            targetAbsentFromProduction && targetPresentInExperimental && targetExecutable &&
            productionProfileExhaustive && experimentalExpansionExhaustive &&
            addedOperationFamilies.toSet() == setOf(SemanticOperationFamily.MANA_ABILITY) &&
            removedCandidateCount == 0 && unrelatedAddedCandidateCount == 0 &&
            nonManaCandidateSetsEqual && explicitWitnessReproduced && profileWitnessReproduced
}

@Serializable
internal data class StandaloneManaCandidateWork(
    val presentInSearchTable: Boolean,
    val visits: Int,
    val meanValue: Double?,
    val selected: Boolean,
)

@Serializable
internal data class StandaloneManaSelectionEvidence(
    val stage: Int,
    val signature: String,
    val label: String,
    val operationFamily: SemanticOperationFamily,
    val selectionKind: String,
    val simulations: Int,
    val rootCandidateEdges: Int,
    val visitedCandidateEdges: Int,
    val selectedVisits: Int?,
    val selectedMeanValue: Double?,
)

@Serializable
internal data class StandaloneManaArmFailure(
    val stage: String,
    val code: String,
)

@Serializable
internal data class StandaloneManaArmOutcome(
    val profile: SearchActionSpaceProfile,
    val targetAvailable: Boolean,
    val targetSearchWork: StandaloneManaCandidateWork?,
    val selectedStandaloneManaAtRoot: Boolean,
    val selectedAuditedPreFloatAtRoot: Boolean,
    val selections: List<StandaloneManaSelectionEvidence>,
    val continuationMatch: StandaloneManaContinuationMatch,
    val stoppedReason: String,
    val failure: StandaloneManaArmFailure? = null,
)

@Serializable
internal data class StandaloneManaPairedTrial(
    val repetition: Int,
    val searchScheduleSha256: String,
    val production: StandaloneManaArmOutcome,
    val experimental: StandaloneManaArmOutcome,
)

@Serializable
internal data class StandaloneManaCaseResult(
    val reviewCaseId: String,
    val reviewOrder: Int,
    val auditCaseId: String,
    val gameId: String,
    val replaySha256: String,
    val decisionIndex: Int,
    val actor: String,
    val mechanism: String,
    val stratum: String,
    val furtherEvaluation: Boolean,
    val humanPreference: String,
    val oracle: StandaloneManaOracleEvidence,
    val trials: List<StandaloneManaPairedTrial>,
)

@Serializable
internal data class StandaloneManaAggregate(
    val cases: Int,
    val trials: Int,
    val technicalFailures: Int,
    val targetAvailableTrials: Int,
    val targetSearchedTrials: Int,
    val targetMinimumVisits: Int?,
    val targetMaximumVisits: Int?,
    val targetMeanVisits: Double?,
    val anyStandaloneManaSelectedAtRootTrials: Int,
    val targetSelectedTrials: Int,
    val targetSelectedAnywhereTrials: Int,
    val experimentalPreFloatWitnessTrials: Int,
    val experimentalProfileWitnessTrials: Int,
    val experimentalNeitherTrials: Int,
    val productionPreFloatWitnessTrials: Int,
    val productionProfileWitnessTrials: Int,
    val productionNeitherTrials: Int,
    val expandedHumanAgreementTrials: Int,
    val humanPrefersPreFloatCases: Int,
    val humanPrefersPreFloatWitnessTrials: Int,
    val humanPrefersPreFloatAnyWitnessCases: Int,
    val humanPrefersPreFloatMajorityWitnessCases: Int,
    val humanPrefersPreFloatAllWitnessCases: Int,
    val humanPrefersProfileCases: Int,
    val humanPrefersProfileRetainedTrials: Int,
    val humanPrefersProfileMajorityRetainedCases: Int,
)

@Serializable
internal data class StandaloneManaTimingExperimentReport(
    val schemaVersion: Int = 1,
    val recordKind: String = "ACTION_PROFILE_STANDALONE_MANA_TIMING_SELECTION_EXPERIMENT",
    val protocol: String = STANDALONE_MANA_TIMING_PROTOCOL,
    val generatedAtUtc: String,
    val sourceRepositoryCommit: String,
    val argentumCommit: String,
    val productionProfile: SearchActionSpaceProfile,
    val experimentalProfile: SearchActionSpaceProfile,
    val exactExperimentalDifference: String,
    val sourceBindings: StandaloneManaSourceBindings,
    val searchConfiguration: StandaloneManaSearchConfiguration,
    val population: Map<String, Int>,
    val oraclePassed: Boolean,
    val oracleFailures: List<String>,
    val summaries: Map<String, StandaloneManaAggregate>,
    val byMechanism: Map<String, StandaloneManaAggregate>,
    val byHumanPreference: Map<String, StandaloneManaAggregate>,
    val cases: List<StandaloneManaCaseResult>,
    val interpretationBoundary: List<String>,
)

@Serializable
private data class RetainedUnblinding(
    val sourceRepositoryCommit: String,
    val population: RetainedPopulation,
    val cases: List<RetainedUnblindedCase>,
)

@Serializable
private data class RetainedPopulation(
    val reviewedCases: Int,
    val furtherEvaluationYes: Int,
    val furtherEvaluationNo: Int,
    val opportunityEnriched: Boolean,
)

@Serializable
private data class RetainedUnblindedCase(
    val caseId: String,
    val reviewOrder: Int,
    val humanStrategicDisposition: RetainedStrategicDisposition,
    val explicitPreFloatBranch: String,
    val profileAdmittedBranch: String,
    val preferenceInterpretation: String,
    val mechanism: String,
    val stratum: String,
    val auditCaseId: String,
)

@Serializable
private data class RetainedStrategicDisposition(
    val furtherEvaluation: String,
)

@Serializable
private data class RetainedReviewPacket(
    val cases: List<RetainedReviewCase>,
)

@Serializable
private data class RetainedReviewCase(
    val caseId: String,
    val branchA: RetainedSafeStateSummary,
    val branchB: RetainedSafeStateSummary,
)

@Serializable
private data class RetainedContinuationAudit(
    val outerCommit: String,
    val argentumCommit: String,
    val checkpointCommit: String,
    val sourceRunIdentity: String,
    val sourceManifestSha256: String,
    val deckId: String,
    val deckHash: String,
    val cardPoolHash: String,
    val cases: List<RetainedAuditCase>,
)

@Serializable
private data class RetainedAuditCase(
    val caseId: String,
    val gameId: String,
    val decisionIndex: Int,
    val actor: String,
    val stratum: String,
    val sourceName: String,
    val suppressedActionLabel: String,
    val witnesses: List<RetainedWitness>,
)

@Serializable
private data class RetainedWitness(
    val exactSequence: List<String>,
    val nearestProfileSequence: List<String>,
)

@Serializable
private data class RetainedInventory(
    val outerCommit: String,
    val argentumCommit: String,
    val sourceRunIdentity: String,
    val sourceManifestSha256: String,
    val deckId: String,
    val deckHash: String,
    val cardPoolHash: String,
    val roots: List<RetainedInventoryRoot>,
)

@Serializable
private data class RetainedInventoryRoot(
    val gameId: String,
    val decisionIndex: Int,
    val actor: String,
    val profileCandidateCount: Int,
    val exactCandidateCount: Int,
    val suppressedActions: List<RetainedSuppressedAction>,
)

@Serializable
private data class RetainedSuppressedAction(
    val signature: String,
    val sourceName: String,
    val label: String,
)

@Serializable
private data class RetainedSafeStateSummary(
    val actorToAct: String?,
    val turnNumber: Int,
    val phase: String,
    val step: String,
    val stack: List<String>,
    val stackTargets: List<String>,
    val players: List<String>,
    val ownMana: PolicyManaPool,
    val ownHand: List<String>,
    val ownBattlefield: List<String>,
    val nextProfileActions: List<String>,
    val epistemicallyComplete: Boolean,
    val terminated: Boolean,
)

private data class PreparedStandaloneManaCase(
    val unblinded: RetainedUnblindedCase,
    val audit: RetainedAuditCase,
    val inventory: RetainedInventoryRoot,
    val target: RetainedSuppressedAction,
    val explicitBranch: RetainedSafeStateSummary,
    val profileBranch: RetainedSafeStateSummary,
    val exactWitnessAction: String,
    val profileWitnessAction: String,
    val base: ArgentumSearchWorld,
    val replaySha256: String,
    val searchBaseSeed: Long,
    val oracle: StandaloneManaOracleEvidence,
)

internal class StandaloneManaTimingExperiment(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val sourceRepositoryCommit: String,
    private val argentumCommit: String,
) {
    private val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)
    private val opponentPolicy = defaultMonoRedOpponentPolicy()
    private val evidence = EvidenceStore(root)

    fun run(
        rootLimit: Int = 23,
        repetitions: Int = 3,
        workerThreads: Int = 1,
        progress: (String) -> Unit = {},
    ): StandaloneManaTimingExperimentReport {
        require(rootLimit in 1..23)
        require(repetitions > 0)
        require(workerThreads > 0)
        val retained = loadRetainedEvidence()
        val selected = retained.unblinding.cases.sortedBy { it.reviewOrder }.take(rootLimit)
        val prepared = selected.mapIndexed { index, unblinded ->
            prepareCase(unblinded, retained).also {
                progress("standalone-mana oracle ${index + 1}/${selected.size}: ${unblinded.caseId}")
            }
        }
        val completed = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(workerThreads)
        val results = try {
            executor.invokeAll(prepared.map { case ->
                Callable {
                    val result = executeCase(case, repetitions)
                    val count = completed.incrementAndGet()
                    progress("standalone-mana search $count/${prepared.size}: ${case.unblinded.caseId}")
                    result
                }
            }).map { it.get() }.sortedBy { it.reviewOrder }
        } finally {
            executor.shutdown()
        }
        val material = results.filter { it.furtherEvaluation }
        val context = results.filterNot { it.furtherEvaluation }
        val summaries = linkedMapOf(
            "FURTHER_EVALUATION_17" to aggregate(material, repetitions),
            "ALL_REVIEWED_23" to aggregate(results, repetitions),
            "CONTEXT_6" to aggregate(context, repetitions),
        )
        val byMechanism = results.groupBy { it.mechanism }.toSortedMap().mapValues { (_, cases) ->
            aggregate(cases, repetitions)
        }
        val byHumanPreference = results.groupBy { it.humanPreference }.toSortedMap().mapValues { (_, cases) ->
            aggregate(cases, repetitions)
        }
        val oracleFailures = results.filterNot { it.oracle.passed }.map { it.reviewCaseId }
        return StandaloneManaTimingExperimentReport(
            generatedAtUtc = Instant.now().toString(),
            sourceRepositoryCommit = sourceRepositoryCommit,
            argentumCommit = argentumCommit,
            productionProfile = productionActionProfile,
            experimentalProfile = experimentalActionProfile,
            exactExperimentalDifference =
                "The experimental profile changes only suppressesStandaloneManaAbilities from true to false; " +
                    "the current expander therefore admits affordable legal isManaAbility actions otherwise " +
                    "suppressed by production. It remains non-production and rulesEquivalent=false.",
            sourceBindings = retained.bindings,
            searchConfiguration = StandaloneManaSearchConfiguration(
                particles = 8,
                simulations = 64,
                maxPolicyDecisions = 32,
                explorationConstant = 1.4,
                leaf = experimentLeaf,
                beliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
                beliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
                opponentPolicyId = opponentPolicy.id,
                policyCompressionEnabled = false,
                treeReuseEnabled = false,
                repetitions = repetitions,
                seedSchedule =
                    "ComponentSeeds.derive(protocol, reviewCaseId, repetition, stage, paired-search); " +
                        "belief game id protocol:reviewCaseId:repetition",
                pairedRandomness =
                    "Each arm reconstructs the same source root, uses the same belief-construction identity and " +
                        "stage search seed, and keeps source search-base/future-chance material fixed. Only the " +
                        "action-space profile differs.",
            ),
            population = linkedMapOf(
                "reviewedCases" to results.size,
                "furtherEvaluationCases" to material.size,
                "contextCases" to context.size,
                "scheduledPairedTrials" to results.size * repetitions,
            ),
            oraclePassed = oracleFailures.isEmpty(),
            oracleFailures = oracleFailures,
            summaries = summaries,
            byMechanism = byMechanism,
            byHumanPreference = byHumanPreference,
            cases = results,
            interpretationBoundary = listOf(
                "The 23 roots are opportunity-enriched and human-selected; counts are not ordinary-game prevalence.",
                "Human branch judgments are fixed retained evidence and were not re-graded.",
                "Search values explain search behavior only; they are not independent strategic proof.",
                "This experiment does not establish win rate, approve a production profile change, alter behavioral-cloning admission, or decide neural-student design.",
            ),
        )
    }

    private fun executeCase(
        prepared: PreparedStandaloneManaCase,
        repetitions: Int,
    ): StandaloneManaCaseResult {
        val trials = (0 until repetitions).map { repetition ->
            val scheduleIdentity = (0 until MAXIMUM_MANA_PATH_ACTIONS).joinToString(":") { stage ->
                ComponentSeeds.derive(
                    STANDALONE_MANA_TIMING_PROTOCOL,
                    prepared.unblinded.caseId,
                    repetition,
                    stage,
                    "paired-search",
                ).toString()
            }
            StandaloneManaPairedTrial(
                repetition = repetition,
                searchScheduleSha256 = sha256(scheduleIdentity),
                production = runArm(prepared, repetition, productionActionProfile),
                experimental = runArm(prepared, repetition, experimentalActionProfile),
            )
        }
        return StandaloneManaCaseResult(
            reviewCaseId = prepared.unblinded.caseId,
            reviewOrder = prepared.unblinded.reviewOrder,
            auditCaseId = prepared.audit.caseId,
            gameId = prepared.audit.gameId,
            replaySha256 = prepared.replaySha256,
            decisionIndex = prepared.audit.decisionIndex,
            actor = prepared.audit.actor,
            mechanism = prepared.unblinded.mechanism,
            stratum = prepared.unblinded.stratum,
            furtherEvaluation = prepared.unblinded.humanStrategicDisposition.furtherEvaluation == "YES",
            humanPreference = prepared.unblinded.preferenceInterpretation,
            oracle = prepared.oracle,
            trials = trials,
        )
    }

    private fun runArm(
        prepared: PreparedStandaloneManaCase,
        repetition: Int,
        profile: SearchActionSpaceProfile,
    ): StandaloneManaArmOutcome {
        val world = prepared.base.withActionSpaceProfile(profile)
        val targetAvailable = world.expandChoices(MAXIMUM_EXPANSION).candidates.any {
            it.signature == prepared.target.signature
        }
        val session = runCatching {
            SearchTeacherPolicySession(
                root = world,
                viewer = prepared.audit.actor,
                registry = registry,
                knownDecks = knownDecks,
                parameters = parameters(prepared.searchBaseSeed, profile),
                opponentPolicy = opponentPolicy,
                gameId = "$STANDALONE_MANA_TIMING_PROTOCOL:${prepared.unblinded.caseId}:$repetition",
            )
        }.getOrElse { failure ->
            return failedArm(profile, targetAvailable, "BELIEF_CONSTRUCTION", failure)
        }
        val selections = mutableListOf<StandaloneManaSelectionEvidence>()
        var targetWork: StandaloneManaCandidateWork? = null
        var selectedStandaloneManaAtRoot = false
        var selectedAuditedPreFloatAtRoot = false
        var continuationMatch = StandaloneManaContinuationMatch.NEITHER
        var stoppedReason = "MANA_ACTION_LIMIT"
        repeat(MAXIMUM_MANA_PATH_ACTIONS) { stage ->
            val selection = runCatching {
                session.select(
                    world = world,
                    actor = prepared.audit.actor,
                    searchSeed = ComponentSeeds.derive(
                        STANDALONE_MANA_TIMING_PROTOCOL,
                        prepared.unblinded.caseId,
                        repetition,
                        stage,
                        "paired-search",
                    ),
                )
            }.getOrElse { failure ->
                return StandaloneManaArmOutcome(
                    profile = profile,
                    targetAvailable = targetAvailable,
                    targetSearchWork = targetWork,
                    selectedStandaloneManaAtRoot = selectedStandaloneManaAtRoot,
                    selectedAuditedPreFloatAtRoot = selectedAuditedPreFloatAtRoot,
                    selections = selections,
                    continuationMatch = continuationMatch,
                    stoppedReason = "FAILURE",
                    failure = StandaloneManaArmFailure("SEARCH_$stage", failureCode(failure)),
                )
            }
            val search = selection.search
            val selectedStats = search?.candidates?.singleOrNull {
                it.choice.signature == selection.choice.signature
            }
            if (stage == 0) {
                val targetStats = search?.candidates?.singleOrNull {
                    it.choice.signature == prepared.target.signature
                }
                targetWork = StandaloneManaCandidateWork(
                    presentInSearchTable = targetStats != null,
                    visits = targetStats?.visits ?: 0,
                    meanValue = targetStats?.meanValue,
                    selected = selection.choice.signature == prepared.target.signature,
                )
                selectedStandaloneManaAtRoot =
                    selection.choice.operationFamily == SemanticOperationFamily.MANA_ABILITY
                selectedAuditedPreFloatAtRoot = selection.choice.signature == prepared.target.signature
            }
            selections += StandaloneManaSelectionEvidence(
                stage = stage,
                signature = selection.choice.signature,
                label = selection.choice.auditLabel(),
                operationFamily = selection.choice.operationFamily,
                selectionKind = selection.kind.name,
                simulations = search?.diagnostics?.simulations ?: 0,
                rootCandidateEdges = search?.candidates?.size ?: 1,
                visitedCandidateEdges = search?.candidates?.count { it.visits > 0 } ?: 1,
                selectedVisits = selectedStats?.visits,
                selectedMeanValue = selectedStats?.meanValue,
            )
            val actor = requireNotNull(world.actorToAct())
            val step = runCatching { world.step(selection.choice) }.getOrElse { failure ->
                return StandaloneManaArmOutcome(
                    profile, targetAvailable, targetWork, selectedStandaloneManaAtRoot,
                    selectedAuditedPreFloatAtRoot, selections, continuationMatch, "FAILURE",
                    StandaloneManaArmFailure("STEP_$stage", failureCode(failure)),
                )
            }
            if (!step.accepted) {
                return StandaloneManaArmOutcome(
                    profile, targetAvailable, targetWork, selectedStandaloneManaAtRoot,
                    selectedAuditedPreFloatAtRoot, selections, continuationMatch, "FAILURE",
                    StandaloneManaArmFailure("STEP_$stage", "REJECTED_TRANSITION"),
                )
            }
            runCatching {
                session.observeAccepted(world, actor, selection.choice, stage, step.privateToActor)
            }.getOrElse { failure ->
                return StandaloneManaArmOutcome(
                    profile, targetAvailable, targetWork, selectedStandaloneManaAtRoot,
                    selectedAuditedPreFloatAtRoot, selections, continuationMatch, "FAILURE",
                    StandaloneManaArmFailure("BELIEF_ADVANCE_$stage", failureCode(failure)),
                )
            }
            val summary = safeSummary(world, prepared.audit.actor)
            continuationMatch = when (summary) {
                prepared.explicitBranch -> StandaloneManaContinuationMatch.EXPLICIT_PRE_FLOAT_WITNESS
                prepared.profileBranch -> StandaloneManaContinuationMatch.PROFILE_ADMITTED_WITNESS
                else -> continuationMatch
            }
            if (selection.choice.operationFamily != SemanticOperationFamily.MANA_ABILITY) {
                stoppedReason = "FIRST_NON_MANA_ROOT_ACTION"
                return StandaloneManaArmOutcome(
                    profile, targetAvailable, targetWork, selectedStandaloneManaAtRoot,
                    selectedAuditedPreFloatAtRoot, selections, continuationMatch, stoppedReason,
                )
            }
            if (world.actorToAct() != prepared.audit.actor || world.terminalPayoff(prepared.audit.actor) != null) {
                stoppedReason = "ROOT_ACTOR_OR_GAME_BOUNDARY"
                return StandaloneManaArmOutcome(
                    profile, targetAvailable, targetWork, selectedStandaloneManaAtRoot,
                    selectedAuditedPreFloatAtRoot, selections, continuationMatch, stoppedReason,
                )
            }
        }
        return StandaloneManaArmOutcome(
            profile, targetAvailable, targetWork, selectedStandaloneManaAtRoot,
            selectedAuditedPreFloatAtRoot, selections, continuationMatch, stoppedReason,
        )
    }

    private fun failedArm(
        profile: SearchActionSpaceProfile,
        targetAvailable: Boolean,
        stage: String,
        failure: Throwable,
    ) = StandaloneManaArmOutcome(
        profile = profile,
        targetAvailable = targetAvailable,
        targetSearchWork = null,
        selectedStandaloneManaAtRoot = false,
        selectedAuditedPreFloatAtRoot = false,
        selections = emptyList(),
        continuationMatch = StandaloneManaContinuationMatch.NEITHER,
        stoppedReason = "FAILURE",
        failure = StandaloneManaArmFailure(stage, failureCode(failure)),
    )

    private fun parameters(
        searchBaseSeed: Long,
        profile: SearchActionSpaceProfile,
    ) = SearchTeacherPolicyParameters(
        particles = 8,
        simulations = 64,
        maxPolicyDecisions = 32,
        explorationConstant = 1.4,
        leaf = experimentLeaf,
        actionSpaceProfile = profile,
        beliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
        beliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
        baseSeed = searchBaseSeed,
        profileId = "$STANDALONE_MANA_TIMING_PROTOCOL-${profile.profileId}",
        policyCompression = PolicyCompressionConfig(enabled = false),
        searchReuse = SearchReuseConfig(enabled = false),
    )

    private fun prepareCase(
        unblinded: RetainedUnblindedCase,
        retained: LoadedRetainedEvidence,
    ): PreparedStandaloneManaCase {
        val audit = retained.audit.cases.single { it.caseId == unblinded.auditCaseId }
        require(audit.stratum == unblinded.stratum)
        val inventory = retained.inventory.roots.single {
            it.gameId == audit.gameId && it.decisionIndex == audit.decisionIndex
        }
        require(inventory.actor == audit.actor)
        val target = inventory.suppressedActions.single {
            it.sourceName == audit.sourceName && it.label == audit.suppressedActionLabel
        }
        require(
            PolicyJson.sha256("${audit.gameId}:${audit.decisionIndex}:${target.signature}:${audit.stratum}")
                .take(16) == audit.caseId
        ) { "Retained audit case ${audit.caseId} no longer binds its suppressed action" }
        val review = retained.packet.cases.single { it.caseId == unblinded.caseId }
        val explicit = review.branch(unblinded.explicitPreFloatBranch)
        val profile = review.branch(unblinded.profileAdmittedBranch)
        val witness = audit.witnesses.first()
        require(witness.exactSequence.size == 1 && witness.nearestProfileSequence.size == 1) {
            "Reviewed branch ${unblinded.caseId} is no longer a one-spend witness"
        }
        val replayPath = retained.sourceDirectory.resolve(
            "replays/${audit.gameId}.privileged.replay.jsonl.gz"
        )
        require(Files.isRegularFile(replayPath) && !Files.isSymbolicLink(replayPath)) {
            "Retained source replay is unavailable: $replayPath"
        }
        val records = readCanonicalReplay(replayPath)
        val header = records.firstOrNull() as? CanonicalReplayHeader
            ?: error("Retained source replay lacks a header: ${audit.gameId}")
        val choices = records.filterIsInstance<CanonicalReplayTransition>().mapNotNull { transition ->
            val index = (transition.extensions["mtgallium.decisionIndex"] as? JsonPrimitive)
                ?.content?.toInt() ?: return@mapNotNull null
            val encoded = transition.extensions["mtgallium.semanticChoice"] ?: return@mapNotNull null
            index to PolicyJson.format.decodeFromJsonElement(SemanticChoice.serializer(), encoded)
        }.sortedBy { it.first }
        require(choices.map { it.first } == choices.indices.toList())
        require(audit.decisionIndex in choices.indices)
        val searchBaseSeed = header.requiredLong("mtgallium.baseSeed")
        val base = reconstructReplayReviewWorld(
            registry = registry,
            manifest = manifest,
            gameId = audit.gameId,
            gameSeed = header.requiredLong("mtgallium.gameSeed"),
            searchBaseSeed = searchBaseSeed,
            startingPlayerIndex = 0,
            profile = productionActionProfile,
            semanticPrefix = choices.take(audit.decisionIndex).map { it.second },
        )
        require(base.actorToAct() == audit.actor)
        val provisional = PreparedStandaloneManaCase(
            unblinded, audit, inventory, target, explicit, profile,
            witness.exactSequence.single(), witness.nearestProfileSequence.single(), base,
            sha256File(replayPath), searchBaseSeed,
            oracle = StandaloneManaOracleEvidence(
                0, 0, 0, 0, "", "", false, false, false, false, false,
                emptyList(), 0, 0, false, "", "", false, false,
            ),
        )
        return provisional.copy(oracle = establishOracle(provisional))
    }

    private fun establishOracle(prepared: PreparedStandaloneManaCase): StandaloneManaOracleEvidence {
        val production = prepared.base.withActionSpaceProfile(productionActionProfile)
        val experimental = prepared.base.withActionSpaceProfile(experimentalActionProfile)
        val productionExpansion = production.expandChoices(MAXIMUM_EXPANSION)
        val experimentalExpansion = experimental.expandChoices(MAXIMUM_EXPANSION)
        val productionBySignature = productionExpansion.candidates.associateBy { it.signature }
        val experimentalBySignature = experimentalExpansion.candidates.associateBy { it.signature }
        val added = experimentalBySignature.keys - productionBySignature.keys
        val removed = productionBySignature.keys - experimentalBySignature.keys
        val addedChoices = added.map(experimentalBySignature::getValue)
        val productionNonMana = productionBySignature.values.filterNot {
            it.operationFamily == SemanticOperationFamily.MANA_ABILITY
        }.map { it.signature }.toSet()
        val experimentalNonMana = experimentalBySignature.values.filterNot {
            it.operationFamily == SemanticOperationFamily.MANA_ABILITY
        }.map { it.signature }.toSet()
        val targetExecutable = experimental.fork().let { fork ->
            val world = fork as ArgentumSearchWorld
            val choice = world.expandChoices(MAXIMUM_EXPANSION).candidates.singleOrNull {
                it.signature == prepared.target.signature
            }
            choice != null && world.step(choice).accepted
        }
        val exactReproduced = reproduceWitness(
            rootWorld = experimental,
            preFloatSignature = prepared.target.signature,
            witnessAction = prepared.exactWitnessAction,
            expected = prepared.explicitBranch,
            viewer = prepared.audit.actor,
        )
        val profileReproduced = reproduceWitness(
            rootWorld = production,
            preFloatSignature = null,
            witnessAction = prepared.profileWitnessAction,
            expected = prepared.profileBranch,
            viewer = prepared.audit.actor,
        )
        return StandaloneManaOracleEvidence(
            retainedProductionCandidateCount = prepared.inventory.profileCandidateCount,
            currentProductionCandidateCount = productionExpansion.candidates.size,
            retainedExactCandidateCount = prepared.inventory.exactCandidateCount,
            currentExperimentalCandidateCount = experimentalExpansion.candidates.size,
            targetSignature = prepared.target.signature,
            targetLabel = prepared.audit.suppressedActionLabel,
            targetAbsentFromProduction = prepared.target.signature !in productionBySignature,
            targetPresentInExperimental = prepared.target.signature in experimentalBySignature,
            targetExecutable = targetExecutable,
            productionProfileExhaustive = productionExpansion.isProfileExhaustive,
            experimentalExpansionExhaustive = experimentalExpansion.isExhaustive,
            addedOperationFamilies = addedChoices.map { it.operationFamily }.distinct().sortedBy { it.name },
            removedCandidateCount = removed.size,
            unrelatedAddedCandidateCount = addedChoices.count {
                it.operationFamily != SemanticOperationFamily.MANA_ABILITY
            },
            nonManaCandidateSetsEqual = productionNonMana == experimentalNonMana,
            retainedExplicitWitnessAction = prepared.exactWitnessAction,
            retainedProfileWitnessAction = prepared.profileWitnessAction,
            explicitWitnessReproduced = exactReproduced,
            profileWitnessReproduced = profileReproduced,
        )
    }

    private fun reproduceWitness(
        rootWorld: ArgentumSearchWorld,
        preFloatSignature: String?,
        witnessAction: String,
        expected: RetainedSafeStateSummary,
        viewer: String,
    ): Boolean {
        val afterPreFloat = rootWorld.fork() as ArgentumSearchWorld
        if (preFloatSignature != null) {
            val preFloat = afterPreFloat.expandChoices(MAXIMUM_EXPANSION).candidates.singleOrNull {
                it.signature == preFloatSignature
            } ?: return false
            if (!afterPreFloat.step(preFloat).accepted) return false
        }
        return afterPreFloat.expandChoices(MAXIMUM_EXPANSION).candidates.filter {
            it.auditLabel() == witnessAction && it.isAuditSpend()
        }.any { candidate ->
            val child = afterPreFloat.fork() as ArgentumSearchWorld
            child.step(candidate).accepted && safeSummary(child, viewer) == expected
        }
    }

    private fun loadRetainedEvidence(): LoadedRetainedEvidence {
        val auditDirectory = evidence.latest("action-profile-continuation-audit")
        val characterizationDirectory = evidence.latest("action-profile-characterization")
        val unblindingPath = auditDirectory.resolve("unblinding-analysis.json")
        val packetPath = auditDirectory.resolve("blinded-review-packet.json")
        val auditPath = auditDirectory.resolve("continuation-audit.json.gz")
        val inventoryPath = characterizationDirectory.resolve("real-root-inventory.json.gz")
        require(sha256File(unblindingPath) == EXPECTED_UNBLINDING_SHA256)
        require(sha256File(packetPath) == EXPECTED_REVIEW_PACKET_SHA256)
        require(sha256File(auditPath) == EXPECTED_CONTINUATION_AUDIT_SHA256)
        require(sha256File(inventoryPath) == EXPECTED_INVENTORY_SHA256)
        val unblinding = retainedJson.decodeFromString<RetainedUnblinding>(Files.readString(unblindingPath))
        val packet = retainedJson.decodeFromString<RetainedReviewPacket>(Files.readString(packetPath))
        val audit = retainedJson.decodeFromString<RetainedContinuationAudit>(readGzipText(auditPath))
        val inventory = retainedJson.decodeFromString<RetainedInventory>(readGzipText(inventoryPath))
        require(unblinding.population.reviewedCases == 23)
        require(unblinding.population.furtherEvaluationYes == 17)
        require(unblinding.population.furtherEvaluationNo == 6)
        require(unblinding.population.opportunityEnriched)
        require(packet.cases.size == 23 && unblinding.cases.size == 23)
        require(audit.argentumCommit == argentumCommit)
        require(inventory.argentumCommit == argentumCommit)
        require(audit.sourceRunIdentity == inventory.sourceRunIdentity)
        require(audit.sourceManifestSha256 == inventory.sourceManifestSha256)
        require(audit.deckId == manifest.id && inventory.deckId == manifest.id)
        require(audit.deckHash == manifest.deckHash() && inventory.deckHash == manifest.deckHash())
        require(audit.cardPoolHash == manifest.cardPoolHash() && inventory.cardPoolHash == manifest.cardPoolHash())
        val sourceDirectory = evidence.work("baseline-factorial-v1/$SOURCE_RUN_DIRECTORY")
        require(Files.isDirectory(sourceDirectory) && !Files.isSymbolicLink(sourceDirectory)) {
            "The retained baseline source population is unavailable: $sourceDirectory"
        }
        return LoadedRetainedEvidence(
            unblinding = unblinding,
            packet = packet,
            audit = audit,
            inventory = inventory,
            sourceDirectory = sourceDirectory,
            bindings = StandaloneManaSourceBindings(
                retainedUnblindingPath = root.relativize(unblindingPath).toString(),
                retainedUnblindingSha256 = EXPECTED_UNBLINDING_SHA256,
                retainedReviewPacketPath = root.relativize(packetPath).toString(),
                retainedReviewPacketSha256 = EXPECTED_REVIEW_PACKET_SHA256,
                retainedContinuationAuditPath = root.relativize(auditPath).toString(),
                retainedContinuationAuditSha256 = EXPECTED_CONTINUATION_AUDIT_SHA256,
                retainedRootInventoryPath = root.relativize(inventoryPath).toString(),
                retainedRootInventorySha256 = EXPECTED_INVENTORY_SHA256,
                sourceRunIdentity = audit.sourceRunIdentity,
                sourceManifestSha256 = audit.sourceManifestSha256,
                sourceOuterCommit = audit.outerCommit,
                characterizationCheckpointCommit = CHARACTERIZATION_CHECKPOINT_COMMIT,
                continuationCheckpointCommit = CONTINUATION_CHECKPOINT_COMMIT,
                deckId = audit.deckId,
                deckHash = audit.deckHash,
                cardPoolHash = audit.cardPoolHash,
            ),
        )
    }
}

private data class LoadedRetainedEvidence(
    val unblinding: RetainedUnblinding,
    val packet: RetainedReviewPacket,
    val audit: RetainedContinuationAudit,
    val inventory: RetainedInventory,
    val sourceDirectory: Path,
    val bindings: StandaloneManaSourceBindings,
)

private fun aggregate(
    cases: List<StandaloneManaCaseResult>,
    repetitions: Int,
): StandaloneManaAggregate {
    val trials = cases.flatMap { case -> case.trials.map { case to it } }
    val targetVisits = trials.mapNotNull { (_, trial) ->
        trial.experimental.targetSearchWork?.visits
    }
    val prefersPreFloat = cases.filter { it.humanPreference == "PREFERS_PRE_FLOAT" }
    val prefersProfile = cases.filter { it.humanPreference == "PREFERS_PROFILE" }
    fun StandaloneManaArmOutcome.isMatch(value: StandaloneManaContinuationMatch) =
        failure == null && continuationMatch == value
    fun humanAgreement(case: StandaloneManaCaseResult, outcome: StandaloneManaArmOutcome): Boolean =
        outcome.failure == null && when (case.humanPreference) {
            "PREFERS_PRE_FLOAT" -> outcome.continuationMatch ==
                StandaloneManaContinuationMatch.EXPLICIT_PRE_FLOAT_WITNESS
            "PREFERS_PROFILE" -> outcome.continuationMatch ==
                StandaloneManaContinuationMatch.PROFILE_ADMITTED_WITNESS
            "BOTH" -> outcome.continuationMatch != StandaloneManaContinuationMatch.NEITHER
            "NEITHER" -> outcome.continuationMatch == StandaloneManaContinuationMatch.NEITHER
            else -> false
        }
    fun preFloatWins(case: StandaloneManaCaseResult): Int = case.trials.count {
        it.experimental.isMatch(StandaloneManaContinuationMatch.EXPLICIT_PRE_FLOAT_WITNESS)
    }
    fun profileWins(case: StandaloneManaCaseResult): Int = case.trials.count {
        it.experimental.isMatch(StandaloneManaContinuationMatch.PROFILE_ADMITTED_WITNESS)
    }
    return StandaloneManaAggregate(
        cases = cases.size,
        trials = trials.size,
        technicalFailures = trials.sumOf { (_, trial) ->
            listOf(trial.production, trial.experimental).count { it.failure != null }
        },
        targetAvailableTrials = trials.count { (_, trial) -> trial.experimental.targetAvailable },
        targetSearchedTrials = trials.count { (_, trial) ->
            (trial.experimental.targetSearchWork?.visits ?: 0) > 0
        },
        targetMinimumVisits = targetVisits.minOrNull(),
        targetMaximumVisits = targetVisits.maxOrNull(),
        targetMeanVisits = targetVisits.takeIf { it.isNotEmpty() }?.average(),
        anyStandaloneManaSelectedAtRootTrials = trials.count { (_, trial) ->
            trial.experimental.selectedStandaloneManaAtRoot
        },
        targetSelectedTrials = trials.count { (_, trial) -> trial.experimental.selectedAuditedPreFloatAtRoot },
        targetSelectedAnywhereTrials = trials.count { (case, trial) ->
            trial.experimental.selections.any { it.signature == case.oracle.targetSignature }
        },
        experimentalPreFloatWitnessTrials = trials.count { (_, trial) ->
            trial.experimental.isMatch(StandaloneManaContinuationMatch.EXPLICIT_PRE_FLOAT_WITNESS)
        },
        experimentalProfileWitnessTrials = trials.count { (_, trial) ->
            trial.experimental.isMatch(StandaloneManaContinuationMatch.PROFILE_ADMITTED_WITNESS)
        },
        experimentalNeitherTrials = trials.count { (_, trial) ->
            trial.experimental.isMatch(StandaloneManaContinuationMatch.NEITHER)
        },
        productionPreFloatWitnessTrials = trials.count { (_, trial) ->
            trial.production.isMatch(StandaloneManaContinuationMatch.EXPLICIT_PRE_FLOAT_WITNESS)
        },
        productionProfileWitnessTrials = trials.count { (_, trial) ->
            trial.production.isMatch(StandaloneManaContinuationMatch.PROFILE_ADMITTED_WITNESS)
        },
        productionNeitherTrials = trials.count { (_, trial) ->
            trial.production.isMatch(StandaloneManaContinuationMatch.NEITHER)
        },
        expandedHumanAgreementTrials = trials.count { (case, trial) -> humanAgreement(case, trial.experimental) },
        humanPrefersPreFloatCases = prefersPreFloat.size,
        humanPrefersPreFloatWitnessTrials = prefersPreFloat.sumOf(::preFloatWins),
        humanPrefersPreFloatAnyWitnessCases = prefersPreFloat.count { preFloatWins(it) > 0 },
        humanPrefersPreFloatMajorityWitnessCases = prefersPreFloat.count {
            preFloatWins(it) > repetitions / 2
        },
        humanPrefersPreFloatAllWitnessCases = prefersPreFloat.count { preFloatWins(it) == repetitions },
        humanPrefersProfileCases = prefersProfile.size,
        humanPrefersProfileRetainedTrials = prefersProfile.sumOf(::profileWins),
        humanPrefersProfileMajorityRetainedCases = prefersProfile.count {
            profileWins(it) > repetitions / 2
        },
    )
}

internal fun renderStandaloneManaTimingReport(
    report: StandaloneManaTimingExperimentReport,
): String = buildString {
    val primary = report.summaries.getValue("FURTHER_EVALUATION_17")
    appendLine("# Standalone-mana timing Search Teacher experiment")
    appendLine()
    appendLine("## Answer")
    appendLine()
    appendLine(
        "The experimental action profile genuinely exposed only the retained standalone-mana " +
            "capability on all ${report.population.getValue("reviewedCases")} reviewed roots. On the " +
            "${primary.humanPrefersPreFloatCases} material cases where the fixed human judgment preferred " +
            "pre-floating, the expanded teacher reached the retained pre-float witness in " +
            "${primary.humanPrefersPreFloatWitnessTrials}/${primary.humanPrefersPreFloatCases * report.searchConfiguration.repetitions} " +
            "paired repetitions, with ${primary.humanPrefersPreFloatMajorityWitnessCases}/" +
            "${primary.humanPrefersPreFloatCases} cases doing so in a majority of repetitions."
    )
    appendLine(
        "On the ${primary.humanPrefersProfileCases} material cases where the human preferred the profile " +
            "branch, the expanded teacher reached that retained branch in " +
            "${primary.humanPrefersProfileRetainedTrials}/" +
            "${primary.humanPrefersProfileCases * report.searchConfiguration.repetitions} repetitions. " +
            "A `NEITHER` outcome means the teacher selected a third continuation, not that the opposite " +
            "reviewed branch was selected."
    )
    appendLine()
    appendLine(
        "This is a bounded selection result on an opportunity-enriched reviewed population, not a win-rate " +
            "result or a production-profile decision."
    )
    appendLine()
    appendLine("## Treatment and control")
    appendLine()
    appendLine("- Production: `${report.productionProfile.profileId}`.")
    appendLine("- Experimental: `${report.experimentalProfile.profileId}` (non-production, not rules-equivalent).")
    appendLine("- Difference: ${report.exactExperimentalDifference}")
    appendLine(
        "- Search: ${report.searchConfiguration.particles} particles × " +
            "${report.searchConfiguration.simulations} simulations, depth " +
            "${report.searchConfiguration.maxPolicyDecisions}, ${report.searchConfiguration.repetitions} paired repetitions."
    )
    appendLine("- Pairing: ${report.searchConfiguration.pairedRandomness}")
    appendLine()
    appendLine("## Semantic oracle")
    appendLine()
    appendLine(
        "Oracle status: **${if (report.oraclePassed) "PASS" else "FAIL"}**. Every case had the target " +
            "absent from production, present and executable under the experimental profile, an unchanged " +
            "non-mana candidate set, only `MANA_ABILITY` additions, and executable paths reproducing both " +
            "retained reviewed branch states."
    )
    if (report.oracleFailures.isNotEmpty()) appendLine("Failures: ${report.oracleFailures.joinToString()}.")
    appendLine()
    appendLine("## Selection results")
    appendLine()
    appendLine("| Population | Cases | Trials | Target searched | Any mana at root | Audited at root | Audited anywhere | Expanded pre-float | Expanded profile | Expanded neither | Human agreement | Failures |")
    appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    report.summaries.forEach { (name, summary) ->
        appendLine(
            "| ${name.replace('_', ' ')} | ${summary.cases} | ${summary.trials} | " +
                "${summary.targetSearchedTrials} | ${summary.anyStandaloneManaSelectedAtRootTrials} | " +
                "${summary.targetSelectedTrials} | ${summary.targetSelectedAnywhereTrials} | " +
                "${summary.experimentalPreFloatWitnessTrials} | ${summary.experimentalProfileWitnessTrials} | " +
                "${summary.experimentalNeitherTrials} | ${summary.expandedHumanAgreementTrials} | " +
                "${summary.technicalFailures} |"
        )
    }
    val all = report.summaries.getValue("ALL_REVIEWED_23")
    appendLine()
    appendLine(
        "The audited action received search work in every paired trial (visit range " +
            "${all.targetMinimumVisits}–${all.targetMaximumVisits}, mean " +
            "${"%.2f".format(Locale.ROOT, requireNotNull(all.targetMeanVisits))} visits out of " +
            "${report.searchConfiguration.simulations})."
    )
    appendLine()
    appendLine("### By fixed human judgment")
    appendLine()
    appendLine("| Human judgment | Cases | Trials | Expanded pre-float | Expanded profile | Expanded neither | Production pre-float | Production profile | Production neither | Expanded agreement |")
    appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    report.byHumanPreference.forEach { (name, summary) ->
        appendLine(
            "| $name | ${summary.cases} | ${summary.trials} | " +
                "${summary.experimentalPreFloatWitnessTrials} | ${summary.experimentalProfileWitnessTrials} | " +
                "${summary.experimentalNeitherTrials} | ${summary.productionPreFloatWitnessTrials} | " +
                "${summary.productionProfileWitnessTrials} | ${summary.productionNeitherTrials} | " +
                "${summary.expandedHumanAgreementTrials} |"
        )
    }
    appendLine()
    appendLine("### By mechanism")
    appendLine()
    appendLine("| Mechanism | Cases | Trials | Any mana at root | Audited at root | Audited anywhere | Expanded pre-float | Expanded profile | Expanded neither | Failures |")
    appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    report.byMechanism.forEach { (name, summary) ->
        appendLine(
            "| $name | ${summary.cases} | ${summary.trials} | " +
                "${summary.anyStandaloneManaSelectedAtRootTrials} | ${summary.targetSelectedTrials} | " +
                "${summary.targetSelectedAnywhereTrials} | " +
                "${summary.experimentalPreFloatWitnessTrials} | ${summary.experimentalProfileWitnessTrials} | " +
                "${summary.experimentalNeitherTrials} | ${summary.technicalFailures} |"
        )
    }
    appendLine()
    appendLine("### Human-preferred pre-float cases not selected in every repetition")
    appendLine()
    val missed = report.cases.filter { case ->
        case.humanPreference == "PREFERS_PRE_FLOAT" && case.trials.any {
            it.experimental.continuationMatch != StandaloneManaContinuationMatch.EXPLICIT_PRE_FLOAT_WITNESS
        }
    }
    if (missed.isEmpty()) {
        appendLine("None.")
    } else {
        appendLine("| Case | Mechanism | Target searched | Audited at root | Audited anywhere | Pre-float witness | Experimental outcomes |")
        appendLine("|---|---|---:|---:|---:|---:|---|")
        missed.forEach { case ->
            val searched = case.trials.count { (it.experimental.targetSearchWork?.visits ?: 0) > 0 }
            val selected = case.trials.count { it.experimental.selectedAuditedPreFloatAtRoot }
            val selectedAnywhere = case.trials.count { trial ->
                trial.experimental.selections.any { it.signature == case.oracle.targetSignature }
            }
            val reached = case.trials.count {
                it.experimental.continuationMatch == StandaloneManaContinuationMatch.EXPLICIT_PRE_FLOAT_WITNESS
            }
            val outcomes = case.trials.groupingBy { it.experimental.continuationMatch.name }
                .eachCount().toSortedMap().entries.joinToString { "${it.key}=${it.value}" }
            appendLine(
                "| `${case.reviewCaseId}` | ${case.mechanism} | $searched/${case.trials.size} | " +
                    "$selected/${case.trials.size} | $selectedAnywhere/${case.trials.size} | " +
                    "$reached/${case.trials.size} | $outcomes |"
            )
        }
    }
    appendLine()
    appendLine("## Provenance and interpretation")
    appendLine()
    appendLine("- Source implementation commit: `${report.sourceRepositoryCommit}`.")
    appendLine("- Argentum: `${report.argentumCommit}`.")
    appendLine("- Source run: `${report.sourceBindings.sourceRunIdentity}`.")
    appendLine("- Deck: `${report.sourceBindings.deckId}`; deck hash `${report.sourceBindings.deckHash}`.")
    appendLine("- Card-pool hash: `${report.sourceBindings.cardPoolHash}`.")
    report.interpretationBoundary.forEach { appendLine("- $it") }
}

private fun RetainedReviewCase.branch(label: String): RetainedSafeStateSummary = when (label) {
    "A" -> branchA
    "B" -> branchB
    else -> error("Unknown retained branch $label")
}

private fun safeSummary(world: ArgentumSearchWorld, viewer: String): RetainedSafeStateSummary {
    val information = world.withActionSpaceProfile(productionActionProfile).informationState(viewer)
    return safeSummary(information)
}

private fun safeSummary(information: PolicyInformationState): RetainedSafeStateSummary {
    val observation = information.observation
    val viewer = observation.perspectivePlayerId
    val cards = observation.zones.flatMap { it.cards }
    val referenceNames = cards.associate { it.objectRef to it.name }
    val ownBattlefield = cards.filter { it.controllerId == viewer && it.zone == "BATTLEFIELD" }
        .map { it.retainedSemanticDescription() }.sorted()
    val ownHand = cards.filter { it.ownerId == viewer && it.zone == "HAND" }.map { it.name }.sorted()
    return RetainedSafeStateSummary(
        actorToAct = information.actingPlayerId,
        turnNumber = observation.turnNumber,
        phase = observation.phase,
        step = observation.step,
        stack = observation.stack.map { "${it.kind}:${it.name}" },
        stackTargets = observation.stack.flatMap { it.targets }.map {
            referenceNames[it] ?: "visible-object"
        },
        players = observation.players.sortedBy { it.playerId }.map {
            "${it.playerId}|life=${it.life}|hand=${it.handSize}|library=${it.librarySize}|" +
                "mana=${PolicyJson.format.encodeToString(PolicyManaPool.serializer(), it.mana)}|" +
                "speed=${it.speed}|lost=${it.lost}"
        },
        ownMana = observation.players.single { it.playerId == viewer }.mana,
        ownHand = ownHand,
        ownBattlefield = ownBattlefield,
        nextProfileActions = information.candidates.filterNot {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        }.map { it.auditLabel() }.sorted(),
        epistemicallyComplete = information.knowledge.epistemicallyComplete,
        terminated = information.terminated,
    )
}

private fun PolicyCardView.retainedSemanticDescription(): String = buildString {
    append(zone).append('|').append(ownerId).append('|').append(controllerId).append('|').append(name)
    append('|').append(types.sorted()).append('|').append(subtypes.sorted()).append('|').append(colors.sorted())
    append('|').append(keywords.sorted()).append('|').append(manaCost).append('|').append(manaValue)
    append('|').append(power).append('/').append(toughness)
    append('|').append(if (tapped) "tapped" else "untapped")
    append('|').append(if (summoningSick) "sick" else "ready")
    append('|').append(damageMarked).append('|').append(counters.toSortedMap())
    append('|').append(isWarped).append('|').append(isWarpExiled).append('|').append(playableFromExile)
}

private fun SemanticChoice.isAuditSpend(): Boolean =
    operationFamily == SemanticOperationFamily.CAST_SPELL ||
        operationFamily == SemanticOperationFamily.ACTIVATE_ABILITY

private fun SemanticChoice.auditLabel(): String =
    "${operationFamily.name}:${display.sourceName ?: actionIntent.sourceCardName ?: ""}:${display.label}"

private fun CanonicalReplayHeader.requiredLong(key: String): Long =
    (extensions[key] as? JsonPrimitive)?.content?.toLong()
        ?: error("Canonical replay header lacks $key")

private fun readGzipText(path: Path): String = Files.newInputStream(path).use { input ->
    GZIPInputStream(input).bufferedReader().use { it.readText() }
}

private fun failureCode(failure: Throwable): String =
    failure::class.simpleName ?: "UNKNOWN_FAILURE"

private val retainedJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}
