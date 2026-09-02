package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.json.encodeToJsonElement
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionSummary
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.BOUNDED_POLICY_INPUT_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.POLICY_HISTORY_COMMITMENT_ALGORITHM
import org.mtgallium.agent.infoset.core.POLICY_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.TRAJECTORY_SCHEMA_CURRENT
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.PolicyCompressionConfig
import org.mtgallium.agent.searchteacher.SearchReuseConfig
import org.mtgallium.agent.searchteacher.SearchTeacherLeafConfigurations
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind

internal typealias DeckManifest = SearchTeacherDeckManifest

@Serializable
data class SearchGridManifest(
    val schemaVersion: Int,
    val particles: List<Int>,
    val simulations: List<Int>,
    val leafConfigurations: List<LeafEvaluationConfig>,
    val actionSpaceProfiles: List<SearchActionSpaceProfile>,
    val fastP95LimitMillis: Long,
    val deepP95LimitMillis: Long,
) {
    init {
        require(schemaVersion == 3)
        require(particles == listOf(8, 16, 32, 64))
        require(simulations == listOf(64, 256, 1024, 4096))
        require(leafConfigurations.toSet() == SearchTeacherLeafConfigurations.supported.toSet())
        require(actionSpaceProfiles == listOf(
            SearchActionSpaceProfile.RULES_EXACT_V1,
            SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
        ))
    }
}

@Serializable
data class FrozenSearchProfile(
    val schemaVersion: Int = 3,
    val id: String,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val host: String,
    val particles: Int,
    val simulations: Int,
    val leaf: LeafEvaluationConfig,
    val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
    val maxPolicyDecisions: Int = 256,
    val explorationConstant: Double = 1.4,
    val measuredP95Millis: Double,
    val tacticalScore: Double,
    val standardError: Double,
    val calibrationReportHash: String,
) {
    init {
        require(schemaVersion == 3)
        require(id in setOf("fast-arena-v1", "deep-teacher-v1"))
        val expectedActionSpace = when (id) {
            "fast-arena-v1" -> SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1
            else -> SearchActionSpaceProfile.RULES_EXACT_V1
        }
        require(actionSpaceProfile == expectedActionSpace) {
            "$id requires ${expectedActionSpace.profileId}, not ${actionSpaceProfile.profileId}"
        }
        require(particles in setOf(8, 16, 32, 64))
        require(simulations in setOf(64, 256, 1024, 4096))
    }
}

internal fun FrozenSearchProfile.policyParameters(
    baseSeed: Long,
    beliefMode: BeliefMode,
    beliefArchitecture: BeliefArchitecture,
    policyCompression: PolicyCompressionConfig = PolicyCompressionConfig(),
    searchReuse: SearchReuseConfig = SearchReuseConfig(),
): SearchTeacherPolicyParameters = SearchTeacherPolicyParameters(
    particles = particles,
    simulations = simulations,
    maxPolicyDecisions = maxPolicyDecisions,
    explorationConstant = explorationConstant,
    leaf = leaf,
    actionSpaceProfile = actionSpaceProfile,
    beliefMode = beliefMode,
    beliefArchitecture = beliefArchitecture,
    baseSeed = baseSeed,
    profileId = id,
    policyCompression = policyCompression,
    searchReuse = searchReuse,
)

@Serializable
data class CalibrationPoint(
    val particles: Int,
    val simulations: Int,
    val leaf: LeafEvaluationConfig,
    val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
    val decisionLatenciesMillis: List<Double>,
    val p50Millis: Double,
    val p95Millis: Double,
    val tacticalScore: Double,
    val standardError: Double,
    val meanExpansionMillis: Double,
    val meanBeliefMillis: Double,
    val meanSearchMillis: Double,
)

@Serializable
data class ComputeImprovementInterval(
    val particles: Int,
    val leaf: LeafEvaluationConfig,
    val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
    val fromSimulations: Int,
    val toSimulations: Int,
    val fromTacticalScore: Double,
    val toTacticalScore: Double,
    val scoreImprovement: Double,
    val improved: Boolean,
)

@Serializable
data class CalibrationCheckpoint(
    val schemaVersion: Int = 1,
    val outerCommit: String,
    val argentumCommit: String,
    val host: String,
    val deckHash: String,
    val caseIds: List<String>,
    val gridSchemaVersion: Int,
    val point: CalibrationPoint,
)

@Serializable
data class CalibrationReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val host: String,
    val deckHash: String,
    val caseIds: List<String>,
    val expectedPointCount: Int,
    val resumedPointCount: Int,
    val points: List<CalibrationPoint>,
    val selectedFast: FrozenSearchProfile?,
    val selectedDeep: FrozenSearchProfile?,
    val computeImprovementIntervals: List<ComputeImprovementInterval>,
    val computeTrendPassed: Boolean,
    val passed: Boolean,
    val failureReasons: List<String>,
)

@Serializable
enum class TacticalCategory {
    FORCED_LETHAL,
    FORCED_SURVIVAL,
    BURN_ALLOCATION,
    ATTACK,
    BLOCK,
    RACE,
    SEQUENCING,
    HOLD_OR_CAST,
    PRIORITY_STACK,
    MULLIGAN,
    HIDDEN_STATE,
    LARGE_RESPONSE,
}

@Serializable
data class TacticalCaseDefinition(
    val id: String,
    val category: TacticalCategory,
    val description: String,
    val rootSeed: Long,
    val captureDecisionIndex: Int,
    val mechanicallyVerifiable: Boolean,
    val hiddenFamily: String? = null,
    val requiresMoreThan64Responses: Boolean = false,
    val acceptableSignatures: Set<String> = emptySet(),
    val referenceValues: Map<String, Double> = emptyMap(),
    val startingStateRationale: String? = null,
)

@Serializable
data class TacticalCaseResult(
    val id: String,
    val solved: Boolean?,
    val chosenSignature: String,
    val chosenLabel: String? = null,
    val heuristicSignature: String,
    val heuristicLabel: String? = null,
    val heuristicFallback: Boolean = false,
    val candidateCount: Int,
    val estimatedCandidateCount: Long?,
    val searchValue: Double,
    val regret: Double?,
    val heuristicRegret: Double?,
    val referenceBestSignature: String? = null,
    val referenceBestValue: Double? = null,
    val referenceSeparation: Double? = null,
    val proposalRegret: Double? = null,
    val latencyMillis: Double,
    val expansionMillis: Double,
    val beliefMillis: Double,
    val searchMillis: Double,
    val rootRolloutPolicyId: String? = null,
    val opponentRolloutPolicyId: String? = null,
    val rootRolloutDecisions: Int = 0,
    val opponentRolloutDecisions: Int = 0,
    val rootRolloutFallbacks: Int = 0,
    val opponentRolloutFallbacks: Int = 0,
    val maximumDepth: Int? = null,
    val quiescenceForcedPasses: Int = 0,
    val quiescenceStrategicDecisions: Int = 0,
    val quiescenceFallbacks: Int = 0,
    val diagnostic: String? = null,
)

@Serializable
data class TacticalReport(
    val schemaVersion: Int = 2,
    val outerCommit: String,
    val argentumCommit: String,
    val profileId: String,
    val cases: List<TacticalCaseResult>,
    val mechanicallyForcedSolved: Int,
    val mechanicallyForcedTotal: Int,
    val meanStrategicRegret: Double?,
    val heuristicMeanStrategicRegret: Double?,
    val strategicSeparatedCases: Int,
    val regretReductionFraction: Double?,
    val regretImprovementConfidenceLower: Double?,
    val regretImprovementConfidenceUpper: Double?,
    val maximumProposalRegret: Double?,
    val hiddenStateFailures: Int,
    val proposalStressFailures: Int,
    val heuristicFallbacks: Int = 0,
    val rootRolloutDecisions: Int = 0,
    val opponentRolloutDecisions: Int = 0,
    val rootRolloutFallbacks: Int = 0,
    val opponentRolloutFallbacks: Int = 0,
    val quiescenceForcedPasses: Int = 0,
    val quiescenceStrategicDecisions: Int = 0,
    val quiescenceFallbacks: Int = 0,
    val passed: Boolean,
    val failureReasons: List<String>,
)

@Serializable
enum class ArenaPolicyKind {
    SEARCH,
    HEURISTIC,
    UNIFORM_RANDOM,
    FACE_BURN,
    HOLD_BURN,
    CONSERVATIVE_COMBAT,
    AGGRESSIVE_TRADE,
    RANDOMIZED_HEURISTIC_20,
}

@Serializable
enum class SearchPlannerKind {
    SHARED_TREE,
    INDEPENDENT_DETERMINIZATION,
    PERFECT_INFORMATION_ORACLE,
    NO_SEARCH_HEURISTIC,
}

@Serializable
enum class GameRunDisposition {
    /** Compatibility value for records written before the O-04 disposition contract. Never score it. */
    LEGACY_UNCLASSIFIED,
    /** Argentum reported a game end; winner and payoff-shaped fields may be populated. */
    GAME_ENDED,
    /** A player-visible fact could not be represented before another policy choice was made. */
    STOPPED_REPRESENTATION,
    /** A selected search or adapter transition failed without becoming a Magic result. */
    STOPPED_SOFTWARE,
    /** A declared evaluation decision bound stopped an otherwise healthy diagnostic run. */
    STOPPED_LIMIT,
}

@Serializable
data class EvidenceFailureAccounting(
    val reached: Int = 1,
    val refused: Int = 1,
    val degraded: Int = 0,
) {
    init {
        require(reached >= 0 && refused >= 0 && degraded >= 0)
        require(refused + degraded <= reached) { "Refused and degraded counts cannot exceed reached count" }
    }
}

/** The precise execution boundary at which the first O-04 stop was observed. */
@Serializable
enum class EvidenceStopDetectionPoint {
    BEFORE_POLICY_CHOICE,
    AFTER_ACCEPTED_TRANSITION,
    DURING_SOFTWARE_TRANSITION,
    UNCAUGHT_SOFTWARE_FAILURE,
}

/** Stable, first-occurrence metadata retained for a stopped evidence game. */
@Serializable
data class EvidenceStopMetadata(
    val triggerCodes: List<String>,
    val affectedViewers: List<String>,
    /**
     * Legacy spelling for the refused policy-decision index.  New writers also
     * populate [refusedPolicyDecisionIndex] and [detectionPoint] explicitly.
     */
    val firstDetectedBeforeDecision: Int,
    val accounting: EvidenceFailureAccounting = EvidenceFailureAccounting(),
    val detectionPoint: EvidenceStopDetectionPoint = EvidenceStopDetectionPoint.BEFORE_POLICY_CHOICE,
    /** The accepted transition / decision which exposed the failure, when one exists. */
    val triggeringDecisionIndex: Int? = null,
    /** The next policy decision refused by this stop; no later choice may be recorded. */
    val refusedPolicyDecisionIndex: Int = firstDetectedBeforeDecision,
) {
    init {
        require(triggerCodes.isNotEmpty())
        require(triggerCodes == triggerCodes.distinct().sorted()) { "Trigger codes must be stable and sorted" }
        require(affectedViewers == affectedViewers.distinct().sorted()) { "Affected viewers must be stable and sorted" }
        require(firstDetectedBeforeDecision >= 0)
        require(triggeringDecisionIndex == null || triggeringDecisionIndex >= 0)
        require(refusedPolicyDecisionIndex >= 0)
        require(refusedPolicyDecisionIndex == firstDetectedBeforeDecision) {
            "Legacy and explicit refused policy-decision indices must agree"
        }
        when (detectionPoint) {
            EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION,
            EvidenceStopDetectionPoint.DURING_SOFTWARE_TRANSITION ->
                require(triggeringDecisionIndex != null) { "$detectionPoint needs a triggering decision index" }
            EvidenceStopDetectionPoint.BEFORE_POLICY_CHOICE,
            EvidenceStopDetectionPoint.UNCAUGHT_SOFTWARE_FAILURE -> Unit
        }
    }
}

/**
 * Compact, result-free form of an O-04 stop for diagnostics that do not retain a full
 * [GameRunResult].  Keeping this shared prevents producer-specific reports from collapsing a
 * representation/software refusal into an ordinary decision-limit stop.
 */
@Serializable
data class EvidenceRunStopSummary(
    val disposition: GameRunDisposition,
    val triggerCodes: List<String>,
    val affectedViewers: List<String>,
    val detectionPoint: EvidenceStopDetectionPoint,
    val triggeringDecisionIndex: Int?,
    val refusedPolicyDecisionIndex: Int,
    val reached: Int,
    val refused: Int,
    val degraded: Int,
) {
    init {
        require(disposition in setOf(
            GameRunDisposition.STOPPED_REPRESENTATION,
            GameRunDisposition.STOPPED_SOFTWARE,
        )) { "Only O-04 representation/software stops have compact stop summaries" }
        require(triggerCodes.isNotEmpty())
        require(triggerCodes == triggerCodes.distinct().sorted())
        require(affectedViewers == affectedViewers.distinct().sorted())
        require(triggeringDecisionIndex == null || triggeringDecisionIndex >= 0)
        require(refusedPolicyDecisionIndex >= 0)
        require(reached >= 0 && refused >= 0 && degraded >= 0)
        require(refused + degraded <= reached)
    }
}

/** Durable work-only attempt identity/accounting with no winner, payoff, score, or training label. */
@Serializable
data class EvidenceRunAttemptSummary(
    val gameId: String,
    val disposition: GameRunDisposition,
    val terminal: Boolean,
    val decisions: Int,
    val evidenceStop: EvidenceRunStopSummary? = null,
    val failureCategory: String? = null,
) {
    init {
        require(gameId.isNotBlank())
        require(decisions >= 0)
        require((disposition in setOf(
            GameRunDisposition.STOPPED_REPRESENTATION,
            GameRunDisposition.STOPPED_SOFTWARE,
        )) == (evidenceStop != null))
        require(disposition != GameRunDisposition.GAME_ENDED || terminal)
        require(disposition !in setOf(
            GameRunDisposition.STOPPED_REPRESENTATION,
            GameRunDisposition.STOPPED_SOFTWARE,
            GameRunDisposition.STOPPED_LIMIT,
        ) || !terminal)
    }
}

@Serializable
data class GameRunResult(
    val schemaVersion: Int = 3,
    val gameId: String,
    val seed: Long,
    val p0Policy: ArenaPolicyKind,
    val p1Policy: ArenaPolicyKind,
    val searchPlanner: SearchPlannerKind? = null,
    val winner: String?,
    val terminal: Boolean,
    val disposition: GameRunDisposition = GameRunDisposition.LEGACY_UNCLASSIFIED,
    val evidenceStop: EvidenceStopMetadata? = null,
    val decisions: Int,
    val searchSeat: String?,
    val searchScore: Double?,
    val illegalResponses: Int,
    val fallbacks: Int,
    /** Privileged adapter-resolution counts across live play and this game's search worlds. */
    val heuristicResolutionCounts: Map<String, Int> = emptyMap(),
    /** Sampled live actions chosen by named direct opponent policies. */
    val liveOpponentPolicyDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    /** Sampled opponent/root-rollout actions inside all search calls in this game. */
    val searchOpponentPolicyDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    /** Baseline heuristic choices stored alongside searched decisions. */
    val heuristicComparatorDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    val stepLimit: Boolean,
    val exception: String? = null,
    val searchLatenciesMillis: List<Double> = emptyList(),
    val beliefUpdates: Int = 0,
    val lowEssUpdates: Int = 0,
    val invalidBeliefWeights: Int = 0,
    val beliefResamplingCount: Int = 0,
    val beliefReconditionings: Int = 0,
    val beliefParticleDepletions: Int = 0,
    val meanBeliefEntropy: Double? = null,
    /** True only when every knowledge-changing event on both player ledgers was reviewed. */
    val informationLedgerComplete: Boolean = true,
    val unsupportedInformationEvents: List<String> = emptyList(),
    val p0PolicyId: String = p0Policy.name.lowercase(),
    val p1PolicyId: String = p1Policy.name.lowercase(),
    val seatDiagnostics: Map<String, ArenaSeatDiagnostics> = emptyMap(),
    /** Privileged exact action replay. Never include this path in a public policy corpus. */
    val replayPath: String? = null,
    val replaySha256: String? = null,
    val replayVerified: Boolean = false,
    val replayVerificationDiagnostic: String? = null,
    val cleanupDiscardEvents: Int = 0,
    val mainPhasePassesWithProactiveOptions: Int = 0,
    val elapsedMillis: Double? = null,
) {
    init {
        when (disposition) {
            GameRunDisposition.GAME_ENDED -> {
                require(terminal) { "GAME_ENDED requires an engine terminal state" }
                require(evidenceStop == null)
            }
            GameRunDisposition.STOPPED_REPRESENTATION,
            GameRunDisposition.STOPPED_SOFTWARE -> {
                require(!terminal) { "An O-04 stop cannot be a game end" }
                require(winner == null && searchScore == null) { "An O-04 stop cannot assign a Magic result" }
                require(evidenceStop != null) { "An O-04 stop requires first-trigger accounting" }
            }
            GameRunDisposition.STOPPED_LIMIT -> {
                require(!terminal)
                require(winner == null && searchScore == null)
                require(evidenceStop == null)
            }
            GameRunDisposition.LEGACY_UNCLASSIFIED -> require(evidenceStop == null)
        }
    }
}

/** A stopped producer can retain this summary without copying any winner, payoff, or label field. */
fun GameRunResult.evidenceRunStopSummary(): EvidenceRunStopSummary? {
    if (disposition !in setOf(
            GameRunDisposition.STOPPED_REPRESENTATION,
            GameRunDisposition.STOPPED_SOFTWARE,
        )) return null
    val stop = requireNotNull(evidenceStop) { "$disposition requires evidence-stop metadata" }
    return EvidenceRunStopSummary(
        disposition = disposition,
        triggerCodes = stop.triggerCodes,
        affectedViewers = stop.affectedViewers,
        detectionPoint = stop.detectionPoint,
        triggeringDecisionIndex = stop.triggeringDecisionIndex,
        refusedPolicyDecisionIndex = stop.refusedPolicyDecisionIndex,
        reached = stop.accounting.reached,
        refused = stop.accounting.refused,
        degraded = stop.accounting.degraded,
    )
}

fun GameRunResult.evidenceRunAttemptSummary(): EvidenceRunAttemptSummary = EvidenceRunAttemptSummary(
    gameId = gameId,
    disposition = disposition,
    terminal = terminal,
    decisions = decisions,
    evidenceStop = evidenceRunStopSummary(),
    failureCategory = exception?.substringBefore(':'),
)

@Serializable
data class ArenaSeatDiagnostics(
    val policyId: String,
    val searchDecisions: Int = 0,
    val searchLatenciesMillis: List<Double> = emptyList(),
    val beliefUpdates: Int = 0,
    val lowEssUpdates: Int = 0,
    val invalidBeliefWeights: Int = 0,
    val beliefResamplingCount: Int = 0,
    val beliefReconditionings: Int = 0,
    val beliefParticleDepletions: Int = 0,
    val meanBeliefEntropy: Double? = null,
    val selectionCounts: Map<SearchTeacherSelectionKind, Int> = emptyMap(),
    val liveOpponentPolicyDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    val heuristicComparatorDecisions: OpponentPolicyDecisionSummary = OpponentPolicyDecisionSummary(),
    val searchDecisionsDetail: List<ArenaSearchDecisionDiagnostic> = emptyList(),
)

@Serializable
data class ArenaSearchDecisionDiagnostic(
    val decisionIndex: Int,
    val turnNumber: Int,
    val phase: String,
    val step: String,
    val latencyMillis: Double,
    val searchDiagnostics: org.mtgallium.agent.infoset.core.InformationSetSearchDiagnostics,
    /** Root evidence retained so a surprising live choice can be reviewed without rerunning it. */
    val chosen: org.mtgallium.agent.infoset.core.SemanticChoice? = null,
    val rootValue: Double? = null,
    val candidateStatistics: List<org.mtgallium.agent.infoset.core.SearchCandidateStatistics> = emptyList(),
)

@Serializable
data class PairedArenaReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val profileId: String,
    val runIdentity: String,
    val opponent: ArenaPolicyKind,
    val pairCount: Int,
    val completePairs: Int,
    val gameCount: Int,
    val completeGames: Int,
    val pointImprovement: Double,
    val confidenceLower: Double,
    val confidenceUpper: Double,
    val playPointImprovement: Double,
    val drawPointImprovement: Double,
    val illegalResponses: Int,
    val fallbacks: Int,
    val exceptions: Int,
    val deadlocksOrStepLimits: Int,
    val workerThreads: Int,
    val primaryGatePassed: Boolean,
    val failureReasons: List<String>,
    val games: List<GameRunResult>,
)

@Serializable
data class PairedArenaShard(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val profileId: String,
    val runIdentity: String,
    val opponent: ArenaPolicyKind,
    val baseSeed: Long,
    val pairOffset: Int,
    val pairCount: Int,
    val workerThreads: Int,
    val pairIndexes: List<Int>,
    val games: List<GameRunResult>,
) {
    init {
        require(pairOffset >= 0)
        require(pairCount > 0)
        require(pairIndexes == (pairOffset until pairOffset + pairCount).toList())
        require(games.size == pairCount * 2)
    }
}

@Serializable
data class PairedArenaPairCheckpoint(
    val schemaVersion: Int = 1,
    val outerCommit: String,
    val argentumCommit: String,
    val profileId: String,
    val runIdentity: String,
    val opponent: ArenaPolicyKind,
    val baseSeed: Long,
    val pairIndex: Int,
    val games: List<GameRunResult>,
) {
    init {
        require(pairIndex >= 0)
        require(games.size == 2)
    }
}

@Serializable
data class BeliefAblationSpec(
    val mode: BeliefMode,
    val opponentModelId: String,
)

@Serializable
data class SearchMethodAblation(
    val id: String,
    val planner: SearchPlannerKind,
    val beliefMode: BeliefMode,
    val beliefArchitecture: BeliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
    val deployable: Boolean,
    val arena: PairedArenaReport,
)

@Serializable
data class SearchMethodAblationReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val profileId: String,
    val pairCountPerMethod: Int,
    val methods: List<SearchMethodAblation>,
)

@Serializable
data class BeliefModeCrossPlay(
    val opponent: ArenaPolicyKind,
    val consistencyOnly: PairedArenaReport,
    val policyConditioned: PairedArenaReport,
    val conditionedMinusConsistency: Double,
)

@Serializable
data class BeliefComparisonReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val profileId: String,
    val mainPairs: Int,
    val heldOutPairs: Int,
    val crossPlay: List<BeliefModeCrossPlay>,
    val conditionedLowEssFraction: Double,
    val conditionedInvalidWeights: Int,
    val selectedTeacherMode: BeliefMode,
    val evaluationPassed: Boolean,
    val conditionedModeSelected: Boolean,
    val failureReasons: List<String>,
)

@Serializable
data class OpponentModelAblation(
    val modelId: String,
    val arena: PairedArenaReport,
)

@Serializable
data class OpponentModelAblationReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val profileId: String,
    val pairCountPerModel: Int,
    val models: List<OpponentModelAblation>,
)

@Serializable
data class PopulationEvaluationReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val profileId: String,
    val pairCountPerOpponent: Int,
    val crossPlay: List<PairedArenaReport>,
    val heldOutAggregateImprovement: Double,
    val passed: Boolean,
    val failureReasons: List<String>,
)

@Serializable
data class PlannerEvidenceArtifact(
    val reference: String,
    val sha256: String,
    val sizeBytes: Long,
    val schemaVersion: Int,
) {
    init {
        require(reference.isNotBlank() && !reference.startsWith('/') && ':' !in reference)
        require(!reference.replace('\\', '/').contains("privileged", ignoreCase = true))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(sizeBytes >= 0)
        require(schemaVersion == org.mtgallium.agent.infoset.core.PLANNER_EVIDENCE_SCHEMA_CURRENT)
    }
}

@Serializable
data class CorpusEntry(
    val gameId: String,
    val publicTrajectory: String,
    val publicSha256: String?,
    val publicSizeBytes: Long,
    val policyEvidenceIdentity: String?,
    val behaviorSpecificationSha256: String?,
    /** Null is an explicit historical absence, never zero settlement accounting. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val plannerEvidence: PlannerEvidenceArtifact? = null,
    val replayVerified: Boolean,
    val game: CorpusGameSummary,
) {
    init {
        require((policyEvidenceIdentity == null) == (behaviorSpecificationSha256 == null)) {
            "Corpus policy identity and behavior-specification commitment must both be present or absent"
        }
        behaviorSpecificationSha256?.let {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }
    }
}

/** Seed-free game metadata safe to place beside perspective trajectories. */
@Serializable
data class CorpusGameSummary(
    val schemaVersion: Int = 3,
    val gameId: String,
    val p0Policy: ArenaPolicyKind,
    val p1Policy: ArenaPolicyKind,
    val searchPlanner: SearchPlannerKind? = null,
    val winner: String?,
    val terminal: Boolean,
    val disposition: GameRunDisposition = GameRunDisposition.LEGACY_UNCLASSIFIED,
    val evidenceStop: EvidenceStopMetadata? = null,
    val decisions: Int,
    val searchSeat: String?,
    val searchScore: Double?,
    val illegalResponses: Int,
    val fallbacks: Int,
    val stepLimit: Boolean,
    /** Safe exception class/category only; the raw message remains an operational log. */
    val failureCategory: String? = null,
    val searchLatenciesMillis: List<Double> = emptyList(),
    val beliefUpdates: Int = 0,
    val lowEssUpdates: Int = 0,
    val invalidBeliefWeights: Int = 0,
    val beliefResamplingCount: Int = 0,
    val beliefReconditionings: Int = 0,
    val beliefParticleDepletions: Int = 0,
    val meanBeliefEntropy: Double? = null,
    val informationLedgerComplete: Boolean = true,
    val unsupportedInformationEvents: List<String> = emptyList(),
)

internal fun GameRunResult.toCorpusGameSummary(): CorpusGameSummary = CorpusGameSummary(
    gameId = gameId,
    p0Policy = p0Policy,
    p1Policy = p1Policy,
    searchPlanner = searchPlanner,
    winner = winner,
    terminal = terminal,
    disposition = disposition,
    evidenceStop = evidenceStop,
    decisions = decisions,
    searchSeat = searchSeat,
    searchScore = searchScore,
    illegalResponses = illegalResponses,
    fallbacks = fallbacks,
    stepLimit = stepLimit,
    failureCategory = exception?.substringBefore(':'),
    searchLatenciesMillis = searchLatenciesMillis,
    beliefUpdates = beliefUpdates,
    lowEssUpdates = lowEssUpdates,
    invalidBeliefWeights = invalidBeliefWeights,
    beliefResamplingCount = beliefResamplingCount,
    beliefReconditionings = beliefReconditionings,
    beliefParticleDepletions = beliefParticleDepletions,
    meanBeliefEntropy = meanBeliefEntropy,
    informationLedgerComplete = informationLedgerComplete,
    unsupportedInformationEvents = unsupportedInformationEvents,
)

@Serializable
data class CorpusManifest(
    val schemaVersion: Int = CORPUS_SCHEMA_CURRENT,
    val trajectorySchemaVersion: Int = TRAJECTORY_SCHEMA_CURRENT,
    val policySchemaVersion: Int = POLICY_SCHEMA_CURRENT,
    val boundedInputSchemaVersion: Int = BOUNDED_POLICY_INPUT_SCHEMA_CURRENT,
    val historyCommitmentAlgorithm: String = POLICY_HISTORY_COMMITMENT_ALGORITHM,
    val generatedAtUtc: String,
    val profileId: String,
    val profileHash: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceProvenance: PolicySourceProvenance,
    val requestedGames: Int,
    val terminalGames: Int,
    val replayVerifiedGames: Int,
    val entries: List<CorpusEntry>,
    val passed: Boolean,
    val datasetIdentity: String,
) {
    init {
        require(schemaVersion == CORPUS_SCHEMA_CURRENT) { "Unknown corpus schema $schemaVersion" }
        require(trajectorySchemaVersion == TRAJECTORY_SCHEMA_CURRENT)
        require(policySchemaVersion == POLICY_SCHEMA_CURRENT)
        require(boundedInputSchemaVersion == BOUNDED_POLICY_INPUT_SCHEMA_CURRENT)
        require(historyCommitmentAlgorithm == POLICY_HISTORY_COMMITMENT_ALGORITHM)
        require(outerCommit == sourceProvenance.outer.revision)
        require(argentumCommit == sourceProvenance.argentum.revision)
        require(sourceProvenance.gitlinkMatchesCheckout)
        require(datasetIdentity == computeDatasetIdentity(
            profileId = profileId,
            profileHash = profileHash,
            sourceProvenance = sourceProvenance,
            requestedGames = requestedGames,
            terminalGames = terminalGames,
            replayVerifiedGames = replayVerifiedGames,
            entries = entries,
            passed = passed,
        )) { "Corpus dataset identity does not match its behavior, source, and entry commitments" }
    }

    companion object {
        fun computeDatasetIdentity(
            profileId: String,
            profileHash: String,
            sourceProvenance: PolicySourceProvenance,
            requestedGames: Int,
            terminalGames: Int,
            replayVerifiedGames: Int,
            entries: List<CorpusEntry>,
            passed: Boolean,
        ): String {
            val material = CorpusDatasetIdentityMaterial(
                profileId = profileId,
                profileHash = profileHash,
                sourceProvenance = sourceProvenance,
                requestedGames = requestedGames,
                terminalGames = terminalGames,
                replayVerifiedGames = replayVerifiedGames,
                entries = entries,
                passed = passed,
            )
            return "$CORPUS_DATASET_IDENTITY_PREFIX:${PolicyJson.digest(
                PolicyJson.format.encodeToJsonElement(CorpusDatasetIdentityMaterial.serializer(), material)
            )}"
        }
    }
}

const val CORPUS_SCHEMA_CURRENT: Int = 5
const val CORPUS_DATASET_IDENTITY_PREFIX: String = "corpus-dataset-v5-sha256"

@Serializable
private data class CorpusDatasetIdentityMaterial(
    val schemaVersion: Int = CORPUS_SCHEMA_CURRENT,
    val trajectorySchemaVersion: Int = TRAJECTORY_SCHEMA_CURRENT,
    val policySchemaVersion: Int = POLICY_SCHEMA_CURRENT,
    val boundedInputSchemaVersion: Int = BOUNDED_POLICY_INPUT_SCHEMA_CURRENT,
    val historyCommitmentAlgorithm: String = POLICY_HISTORY_COMMITMENT_ALGORITHM,
    val profileId: String,
    val profileHash: String,
    val sourceProvenance: PolicySourceProvenance,
    val requestedGames: Int,
    val terminalGames: Int,
    val replayVerifiedGames: Int,
    val entries: List<CorpusEntry>,
    val passed: Boolean,
)

@Serializable
data class ExpertReviewItem(
    val reviewId: String,
    val informationState: PolicyInformationState,
    val candidates: List<SemanticChoice>,
    val selectedChoice: SemanticChoice,
    val prompt: String = "Rate the selected choice: NO_ERROR, MINOR_ERROR, or MAJOR_ERROR, and suggest an alternative if needed.",
)

@Serializable
data class ExpertReviewPacket(
    val schemaVersion: Int = 3,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceCorpusHash: String,
    val itemCount: Int,
    val items: List<ExpertReviewItem>,
) {
    init { require(schemaVersion == 3) { "Unknown expert-review schema $schemaVersion" } }
}

@Serializable
data class SurprisingDecisionReplayCase(
    val caseId: String,
    val sourceTrajectory: String,
    val targetDecisionIndex: Int,
    val expectedInformationStateDigest: String,
    val chosenSignature: String,
    val heuristicSignature: String,
    val chosenMeanValue: Double,
    val heuristicMeanValue: Double?,
    val estimatedAdvantage: Double?,
    val privilegedDebugSource: String,
    val semanticPrefixDigest: String,
    val replayVerified: Boolean,
    val replayDiagnostic: String? = null,
)

@Serializable
data class SurprisingLineReport(
    val schemaVersion: Int = 3,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceCorpusHash: String,
    val requestedCases: Int,
    val verifiedCases: Int,
    val cases: List<SurprisingDecisionReplayCase>,
    val passed: Boolean,
) {
    init { require(schemaVersion == 3) { "Unknown surprising-line schema $schemaVersion" } }
}

@Serializable
data class CorpusValidationFile(
    val gameId: String,
    val trajectory: String,
    val events: Int,
    val searchDecisions: Int,
    val wrapperDecisions: Int,
    val bytes: Long,
    val passed: Boolean,
    val failures: List<String>,
)

@Serializable
data class CorpusValidationReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceManifest: String,
    val sourceManifestHash: String,
    val profileHash: String,
    val games: Int,
    val terminalGames: Int,
    val searchDecisions: Int,
    val events: Int,
    val files: List<CorpusValidationFile>,
    val passed: Boolean,
    val failures: List<String>,
)

@Serializable
data class InformationConformanceGame(
    val gameId: String,
    val p0Policy: ArenaPolicyKind,
    val p1Policy: ArenaPolicyKind,
    val terminal: Boolean,
    val disposition: GameRunDisposition = GameRunDisposition.LEGACY_UNCLASSIFIED,
    /** Typed O-04 stop accounting retained even though this report compresses the arena result. */
    val evidenceStop: EvidenceRunStopSummary? = null,
    val decisions: Int,
    val ledgerComplete: Boolean,
    val failureCategory: String?,
    val unsupportedReasons: List<String>,
    val eventCoverage: Map<String, Int>,
    val projectionCoverage: Map<String, Int>,
    val hiddenRootChecks: Int,
    val hiddenRootFailures: Int,
    val supportParticlesChecked: Int,
    val supportFailures: Int,
)

@Serializable
data class InformationConformanceReport(
    val schemaVersion: Int = 2,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val deckHash: String,
    val cardPoolHash: String,
    val requestedGames: Int,
    val terminalGames: Int,
    val perspectiveLedgers: Int,
    val hiddenRootChecks: Int,
    val hiddenRootFailures: Int,
    val supportParticlesChecked: Int,
    val supportFailures: Int,
    val eventCoverage: Map<String, Int>,
    val projectionCoverage: Map<String, Int>,
    val games: List<InformationConformanceGame>,
    val passed: Boolean,
    val failures: List<String>,
)

@Serializable
data class PolicyBoundarySyntheticPoint(
    val historyLength: Int,
    val eventsExamined: Int,
    val recentEventCount: Int,
    val recentEventBytes: Int,
    val totalInputBytes: Int,
    val compileMicros: Double,
)

@Serializable
data class PolicyBoundaryReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceManifest: String,
    val sourceManifestHash: String,
    val profileHash: String,
    val corpusGames: Int,
    val corpusDecisions: Int,
    val corpusEvents: Int,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val compileP50Micros: Double,
    val compileP95Micros: Double,
    val compileP99Micros: Double,
    val maximumInputBytes: Int,
    val inputByteLimit: Int,
    val synthetic: List<PolicyBoundarySyntheticPoint>,
    val growthRatios: Map<String, Double>,
    val compilerBounded: Boolean,
    val inputCapPassed: Boolean,
    val trajectoryGrowthPassed: Boolean,
    val commitmentAppendPassed: Boolean,
    val persistentForkPassed: Boolean,
    val passed: Boolean,
    val failures: List<String>,
)
