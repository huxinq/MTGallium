package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.SearchTeacherIntegrationSpecification
import org.mtgallium.agent.searchteacher.LearnedOutcomeValuePolicyStopException
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import java.io.BufferedWriter
import java.io.Closeable
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.mtgallium.agent.infoset.argentum.ArgentumPrivilegedDebugSnapshot
import org.mtgallium.agent.infoset.argentum.ArgentumBeliefSupportException
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumHeuristicChoiceDiagnosis
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.argentum.UnsupportedInformationStateException
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.searchteacher.DeterminizedArgentumHeuristicOpponentPolicy
import org.mtgallium.agent.searchteacher.ARGENTUM_HEURISTIC_ANNOTATION_UNAVAILABLE_TRIGGER_V1
import org.mtgallium.agent.searchteacher.FaceBurnOpponentPolicy
import org.mtgallium.agent.searchteacher.HoldBurnOpponentPolicy
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.InformationSetSearchDiagnostics
import org.mtgallium.agent.infoset.core.InformationSetSearchResult
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicyBehaviorSpecification
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionCounter
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionDiagnostic
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionSummary
import org.mtgallium.agent.infoset.core.OpponentPolicyReplacementDiagnostic
import org.mtgallium.agent.infoset.core.OpponentPolicyReplacementEvidenceDisposition
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyBehaviorBinding
import org.mtgallium.agent.infoset.core.PolicyInspectionExecutionBinding
import org.mtgallium.agent.infoset.core.PolicyInspectionPolicyExecutionIdentity
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicyInspectionOutcome
import org.mtgallium.agent.infoset.core.PolicyBeliefSummary
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.PolicyTrajectoryDecision
import org.mtgallium.agent.infoset.core.PolicyTrajectoryForcedTransition
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryCompletion
import org.mtgallium.agent.infoset.core.PolicyTrajectoryOutcome
import org.mtgallium.agent.infoset.core.PolicyTrajectoryStopReason
import org.mtgallium.agent.infoset.core.PolicyTrajectoryWriter
import org.mtgallium.agent.infoset.core.PlannerEvidenceBinding
import org.mtgallium.agent.infoset.core.PlannerEvidenceSidecar
import org.mtgallium.agent.infoset.core.PlannerEvidenceDecision
import org.mtgallium.agent.infoset.core.plannerEvidenceDecision
import org.mtgallium.agent.infoset.core.ProbabilityDistribution
import org.mtgallium.agent.infoset.core.ProbabilityMass
import org.mtgallium.agent.infoset.core.RejectedSearchTransitionException
import org.mtgallium.agent.infoset.core.SearchCandidateStatistics
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SearchStepResult
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticActionIntent
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.searchteacher.SemanticHeuristicOpponentPolicy
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import org.mtgallium.agent.infoset.core.Weighted
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherBehaviorSpecification
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyIdentity
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.agent.searchteacher.SearchTeacherPilotSpecification

internal data class GameEvidenceOptions(
    val publicTrajectory: Path? = null,
    /** Safe planner outputs bound to [publicTrajectory], never a duplicate policy input. */
    val plannerEvidence: Path? = null,
    /**
     * Perspective for a detached safe trajectory when both seats search.  The historical default
     * remains the sole search seat; a dual-search experiment must select one safe perspective.
     */
    val publicTrajectoryPerspective: String? = null,
    val publicTrajectoryReference: String? = null,
    val researchRunIdentity: String? = null,
    val privilegedDebug: Path? = null,
    val inspection: Path? = null,
    val privilegedInspection: Path? = null,
    val inspectionPerspective: String? = null,
    val outerCommit: String = "unknown",
    val argentumCommit: String = "unknown",
    val profileHash: String = "unknown",
    val sourceProvenance: PolicySourceProvenance? = null,
    val inspectionExecutionCommitment: InspectionExecutionCommitment? = null,
) {
    init {
        require(privilegedInspection == null || inspection != null) {
            "Privileged inspection requires a separately written safe inspection bundle"
        }
        require(inspectionPerspective == null || inspection != null)
        require(publicTrajectory == null || sourceProvenance != null) {
            "A detached public trajectory requires full source provenance"
        }
        require((plannerEvidence == null) == (publicTrajectoryReference == null)) {
            "Planner evidence requires exactly one safe trajectory reference"
        }
        require(plannerEvidence == null || publicTrajectory != null) {
            "Planner evidence must bind to an emitted safe trajectory"
        }
        require(publicTrajectoryPerspective == null ||
            (publicTrajectory != null && publicTrajectoryPerspective in setOf("p0", "p1"))
        ) { "Public trajectory perspective must identify one arena seat" }
        require(publicTrajectoryReference == null ||
            (publicTrajectoryReference.isNotBlank() && !publicTrajectoryReference.startsWith('/') && ':' !in publicTrajectoryReference &&
                !publicTrajectoryReference.replace('\\', '/').contains("privileged", ignoreCase = true))
        ) { "Planner evidence trajectory reference must be a safe relative artifact path" }
        require(researchRunIdentity == null || researchRunIdentity.isNotBlank())
        require(inspectionExecutionCommitment == null || inspection != null) {
            "An inspection execution commitment requires an ordinary inspection replay"
        }
        require(inspectionExecutionCommitment == null || sourceProvenance != null) {
            "An inspection execution commitment requires full source provenance"
        }
        sourceProvenance?.let { source ->
            require(source.outer.revision == outerCommit)
            require(source.argentum.revision == argentumCommit)
        }
    }
}

/** Pre-game protocol fields; the arena supplies the schedule and runtime facts itself. */
internal data class InspectionExecutionCommitment(
    val protocolId: String,
    val manifestSha256: String,
    val randomizationUnitId: String,
    val declaredBehaviorSha256: String,
    val declaredPopulationSha256: String,
    val declaredLimitsSha256: String,
    val executionLimits: InspectionExecutionLimits,
) {
    init {
        require(protocolId.isNotBlank())
        require(randomizationUnitId.isNotBlank())
        listOf(
            manifestSha256,
            declaredBehaviorSha256,
            declaredPopulationSha256,
            declaredLimitsSha256,
        ).forEach { require(it.matches(Regex("[0-9a-f]{64}"))) }
    }
}

/** Limits the arena can enforce and report for one governed inspection execution. */
internal data class InspectionExecutionLimits(
    val maximumActionProposals: Int,
    val perDecisionTimeoutMillis: Long,
    val wholeGameWallClockMillis: Long,
    val maximumTurns: Int,
    val maximumPolicyDecisions: Int,
    val maximumTransitions: Int,
    val concurrency: Int,
    val additionalComputeLimits: Map<String, Long> = emptyMap(),
) {
    init {
        require(maximumActionProposals == ArgentumSearchWorld.DEFAULT_EXPANSION_LIMIT) {
            "The current arena can bind only its actual ${ArgentumSearchWorld.DEFAULT_EXPANSION_LIMIT}-proposal root limit"
        }
        require(perDecisionTimeoutMillis > 0)
        require(wholeGameWallClockMillis > 0)
        require(perDecisionTimeoutMillis <= Long.MAX_VALUE / 1_000_000L)
        require(wholeGameWallClockMillis <= Long.MAX_VALUE / 1_000_000L)
        require(maximumTurns > 0)
        require(maximumPolicyDecisions > 0)
        require(maximumTransitions > 0)
        require(concurrency == 1) { "One inspection execution is single-game and single-threaded" }
        require(additionalComputeLimits.isEmpty()) {
            "The current arena cannot claim enforcement of undeclared additional compute limits"
        }
    }

    fun asRuntimeFacts(runtime: SearchTeacherPolicyParameters?): Map<String, Long> = buildMap {
        put("concurrency", concurrency.toLong())
        put("maximumActionProposals", maximumActionProposals.toLong())
        put("maximumGameDecisions", maximumPolicyDecisions.toLong())
        put("maximumTransitions", maximumTransitions.toLong())
        put("maximumTurns", maximumTurns.toLong())
        put("perDecisionTimeoutMillis", perDecisionTimeoutMillis)
        put("wholeGameWallClockMillis", wholeGameWallClockMillis)
        runtime?.let {
            put("maximumSearchDepth", it.maxPolicyDecisions.toLong())
            put("particlesPerDecision", it.particles.toLong())
            put("simulationsPerDecision", it.simulations.toLong())
        }
    }.toSortedMap()
}

private fun SearchTeacherPolicyParameters.beliefVersion(): String =
    "${beliefArchitecture.name.lowercase()}:${beliefMode.name.lowercase()}"

private fun behaviorBinding(
    specification: SearchTeacherBehaviorSpecification,
    sourceProvenance: PolicySourceProvenance,
): PolicyBehaviorBinding = PolicyBehaviorBinding.create(
    behaviorIdentity = SearchTeacherPolicyIdentity.identity(specification),
    behaviorSpecification = PolicyJson.format.encodeToJsonElement(
        SearchTeacherBehaviorSpecification.serializer(),
        specification,
    ).jsonObject,
    sourceProvenance = sourceProvenance,
)

/** Internal control flow for O-04(a): never turn a representation or transition failure into a game result. */
private class EvidenceRunStopException(
    val disposition: GameRunDisposition,
    val metadata: EvidenceStopMetadata,
) : IllegalStateException(metadata.triggerCodes.joinToString(",")) {
    init {
        require(disposition == GameRunDisposition.STOPPED_REPRESENTATION ||
            disposition == GameRunDisposition.STOPPED_SOFTWARE)
    }
}

/** Convert a controlled adapter refusal into stable evidence without forwarding exception text. */
internal fun beliefSupportStopMetadata(
    failure: ArgentumBeliefSupportException,
    currentDecisionIndex: Int,
    triggeringDecisionIndex: Int?,
): EvidenceStopMetadata {
    val refusedDecisionIndex = triggeringDecisionIndex?.plus(1) ?: currentDecisionIndex
    return EvidenceStopMetadata(
        triggerCodes = failure.reasonCodes.map { "BELIEF_SUPPORT:$it" }.sorted(),
        affectedViewers = listOf(failure.viewerAlias),
        firstDetectedBeforeDecision = refusedDecisionIndex,
        detectionPoint = if (triggeringDecisionIndex == null) {
            EvidenceStopDetectionPoint.UNCAUGHT_SOFTWARE_FAILURE
        } else {
            EvidenceStopDetectionPoint.DURING_SOFTWARE_TRANSITION
        },
        triggeringDecisionIndex = triggeringDecisionIndex,
        refusedPolicyDecisionIndex = refusedDecisionIndex,
    )
}

/** Record a policy-session refusal as typed evidence, never a game value or a fallback. */
internal fun learnedOutcomeValueStopMetadata(
    failure: LearnedOutcomeValuePolicyStopException,
    currentDecisionIndex: Int,
): EvidenceStopMetadata = EvidenceStopMetadata(
    triggerCodes = listOf("LEARNED_VALUE:${failure.failureKind.name}"),
    affectedViewers = emptyList(),
    firstDetectedBeforeDecision = currentDecisionIndex,
    detectionPoint = EvidenceStopDetectionPoint.BEFORE_POLICY_CHOICE,
    refusedPolicyDecisionIndex = currentDecisionIndex,
)

/** Internal test seam; production uses [DefaultRepresentationBoundaryDetector]. */
internal fun interface RepresentationBoundaryDetector {
    fun detect(
        world: ArgentumSearchWorld,
        detectionPoint: EvidenceStopDetectionPoint,
        triggeringDecisionIndex: Int?,
    ): RepresentationBoundaryFailure?
}

internal data class RepresentationBoundaryFailure(
    val triggerCodes: List<String>,
    val affectedViewers: List<String>,
) {
    init {
        require(triggerCodes.isNotEmpty())
        require(triggerCodes == triggerCodes.distinct().sorted())
        require(affectedViewers == affectedViewers.distinct().sorted())
    }
}

private object DefaultRepresentationBoundaryDetector : RepresentationBoundaryDetector {
    override fun detect(
        world: ArgentumSearchWorld,
        detectionPoint: EvidenceStopDetectionPoint,
        triggeringDecisionIndex: Int?,
    ): RepresentationBoundaryFailure? {
        val codes = sortedSetOf<String>()
        val viewers = sortedSetOf<String>()
        listOf("p0", "p1").forEach { viewer ->
            try {
                val knowledge = world.informationState(viewer).knowledge
                if (!knowledge.epistemicallyComplete) {
                    viewers += viewer
                    if (knowledge.unsupportedReasons.isEmpty()) {
                        codes += "UNREPRESENTED_PLAYER_VISIBLE_FACT"
                    } else {
                        codes += knowledge.unsupportedReasons.map { "UNREPRESENTED:$it" }
                    }
                }
            } catch (failure: UnsupportedInformationStateException) {
                viewers += viewer
                codes += failure.reasonCodes.map { "UNREPRESENTED:$it" }
            }
        }
        return codes.takeIf { it.isNotEmpty() }?.let {
            RepresentationBoundaryFailure(it.toList(), viewers.toList())
        }
    }
}

@Serializable
private data class ArenaDirectPolicyBehaviorSpecification(
    val schemaVersion: Int = 1,
    val policyId: String,
    val policyKind: ArenaPolicyKind,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val maximumGameDecisions: Int,
    val maximumSearchDecisions: Int?,
    val directPolicy: OpponentPolicyBehaviorSpecification,
    val deckManifestSha256: String,
    val cardPoolSha256: String,
)

internal class SearchTeacherArena(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val profile: FrozenSearchProfile,
    private val baseSeed: Long,
    private val beliefMode: BeliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
    private val beliefArchitecture: BeliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
    private val opponentModel: OpponentPolicy = defaultMonoRedOpponentPolicy(),
    private val searchPlanner: SearchPlannerKind = SearchPlannerKind.SHARED_TREE,
    private val representationBoundaryDetector: RepresentationBoundaryDetector = DefaultRepresentationBoundaryDetector,
    private val gameDecisionLimit: Int = MAX_GAME_DECISIONS,
) {
    init { require(gameDecisionLimit in 1..MAX_GAME_DECISIONS) }

    private val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)
    internal val runIdentity: String = evidenceBinding(
        legacyPolicy(ArenaPolicyKind.SEARCH),
        maxSearchDecisions = null,
        sourceProvenance = currentSourceProvenance(),
    ).identity

    private data class DirectPolicyBehavior(
        val identity: String,
        val specification: JsonObject,
    )

    /** Exact behavior/source commitment used by tournament and dataset identities. */
    internal fun evidenceBinding(
        policy: ArenaPolicySpec,
        maxSearchDecisions: Int?,
        sourceProvenance: PolicySourceProvenance,
    ): PolicyBehaviorBinding {
        if (policy.kind == ArenaPolicyKind.SEARCH &&
            policy.searchPlanner != SearchPlannerKind.NO_SEARCH_HEURISTIC
        ) {
            val specification = policy.effectiveParameters(baseSeed).behaviorSpecification(
                knownDecks = knownDecks,
                opponentPolicy = opponentModel,
                informationEvaluator = policy.informationEvaluator,
                integration = SearchTeacherIntegrationSpecification(
                    hostMode = "evaluation-arena-v1",
                    searchPlanner = policy.searchPlanner.name,
                    maximumGameDecisions = gameDecisionLimit,
                    maximumSearchDecisions = maxSearchDecisions,
                    additionalBindings = mapOf(
                        "arenaPolicyId" to policy.id,
                        "arenaPolicyKind" to policy.kind.name,
                    ),
                ),
            )
            return behaviorBinding(specification, sourceProvenance)
        }

        val direct = directPolicyBehavior(
            policy = policy,
            maxSearchDecisions = maxSearchDecisions,
            actionSpaceProfile = policy.parameters?.actionSpaceProfile ?: policy.profile?.actionSpaceProfile
                ?: profile.actionSpaceProfile,
        )
        return PolicyBehaviorBinding.create(
            behaviorIdentity = direct.identity,
            behaviorSpecification = direct.specification,
            sourceProvenance = sourceProvenance,
        )
    }

    private fun directPolicyBehavior(
        policy: ArenaPolicySpec,
        maxSearchDecisions: Int?,
        actionSpaceProfile: SearchActionSpaceProfile,
    ): DirectPolicyBehavior {
        val direct = if (policy.kind == ArenaPolicyKind.HEURISTIC ||
            policy.searchPlanner == SearchPlannerKind.NO_SEARCH_HEURISTIC
        ) {
            DeterminizedArgentumHeuristicOpponentPolicy()
        } else {
            policy(policy.kind)
        }
        val specification = ArenaDirectPolicyBehaviorSpecification(
            policyId = policy.id,
            policyKind = policy.kind,
            actionSpaceProfile = actionSpaceProfile,
            maximumGameDecisions = gameDecisionLimit,
            maximumSearchDecisions = maxSearchDecisions,
            directPolicy = direct.behaviorSpecification,
            deckManifestSha256 = manifest.deckHash(),
            cardPoolSha256 = manifest.cardPoolHash(),
        )
        val encoded = PolicyJson.format.encodeToJsonElement(
            ArenaDirectPolicyBehaviorSpecification.serializer(),
            specification,
        ).jsonObject
        return DirectPolicyBehavior(
            identity = "arena-direct-policy-v1-sha256:${PolicyJson.digest(encoded)}",
            specification = encoded,
        )
    }

    fun play(
        gameId: String,
        gameSeed: Long,
        p0Policy: ArenaPolicyKind,
        p1Policy: ArenaPolicyKind,
        evidence: GameEvidenceOptions? = null,
        projectionAuditSink: org.mtgallium.agent.infoset.argentum.PerspectiveProjectionAuditSink =
            org.mtgallium.agent.infoset.argentum.PerspectiveProjectionAuditSink.NONE,
        rootProbe: ((ArgentumSearchWorld, String, Int) -> Unit)? = null,
        acceptedStepProbe: ((ArgentumSearchWorld, String, Int, SemanticChoice, SearchStepResult) -> Unit)? = null,
        replay: GameReplayOptions? = null,
        maxSearchDecisions: Int? = null,
        progressObserver: ArenaProgressObserver = ArenaProgressObserver.NONE,
    ): GameRunResult = playInternal(
        gameId = gameId,
        gameSeed = gameSeed,
        p0Policy = legacyPolicy(p0Policy),
        p1Policy = legacyPolicy(p1Policy),
        evidence = evidence,
        projectionAuditSink = projectionAuditSink,
        rootProbe = rootProbe,
        acceptedStepProbe = acceptedStepProbe,
        replay = replay,
        maxSearchDecisions = maxSearchDecisions,
        progressObserver = progressObserver,
    )

    internal fun playWithPolicies(
        gameId: String,
        gameSeed: Long,
        p0Policy: ArenaPolicySpec,
        p1Policy: ArenaPolicySpec,
        evidence: GameEvidenceOptions? = null,
        replay: GameReplayOptions? = null,
        maxSearchDecisions: Int? = null,
        progressObserver: ArenaProgressObserver = ArenaProgressObserver.NONE,
        rootProbe: ((ArgentumSearchWorld, String, Int) -> Unit)? = null,
        acceptedStepProbe: ((ArgentumSearchWorld, String, Int, SemanticChoice, SearchStepResult) -> Unit)? = null,
    ): GameRunResult = playInternal(
        gameId,
        gameSeed,
        p0Policy,
        p1Policy,
        evidence = evidence,
        replay = replay,
        maxSearchDecisions = maxSearchDecisions,
        progressObserver = progressObserver,
        rootProbe = rootProbe,
        acceptedStepProbe = acceptedStepProbe,
    )

    private fun legacyPolicy(kind: ArenaPolicyKind): ArenaPolicySpec = ArenaPolicySpec(
        id = kind.name.lowercase(),
        kind = kind,
        profile = profile.takeIf { kind == ArenaPolicyKind.SEARCH },
        beliefMode = beliefMode,
        beliefArchitecture = beliefArchitecture,
        searchPlanner = searchPlanner,
    )

    private fun playInternal(
        gameId: String,
        gameSeed: Long,
        p0Policy: ArenaPolicySpec,
        p1Policy: ArenaPolicySpec,
        evidence: GameEvidenceOptions? = null,
        projectionAuditSink: org.mtgallium.agent.infoset.argentum.PerspectiveProjectionAuditSink =
            org.mtgallium.agent.infoset.argentum.PerspectiveProjectionAuditSink.NONE,
        rootProbe: ((ArgentumSearchWorld, String, Int) -> Unit)? = null,
        acceptedStepProbe: ((ArgentumSearchWorld, String, Int, SemanticChoice, SearchStepResult) -> Unit)? = null,
        replay: GameReplayOptions? = null,
        maxSearchDecisions: Int? = null,
        progressObserver: ArenaProgressObserver = ArenaProgressObserver.NONE,
    ): GameRunResult {
        require(maxSearchDecisions == null || maxSearchDecisions > 0)
        val gameStartedAtNanos = System.nanoTime()
        val seatPolicies = mapOf("p0" to p0Policy, "p1" to p1Policy)
        val actionSpaceProfiles = seatPolicies.values.mapNotNull {
            it.parameters?.actionSpaceProfile ?: it.profile?.actionSpaceProfile
        }.distinct()
        require(actionSpaceProfiles.size <= 1) { "Both seats must use the same action-space profile" }
        val actionSpaceProfile = actionSpaceProfiles.singleOrNull() ?: profile.actionSpaceProfile
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Player 0", manifest.deck()),
                    PlayerConfig("Player 1", manifest.deck()),
                ),
                skipMulligans = false,
                useHandSmoother = false,
                startingPlayerIndex = 0,
                seed = gameSeed,
            )
        )
        val heuristicResolutionCounters = ConcurrentHashMap<String, AtomicInteger>()
        fun heuristicResolutionCounts(): Map<String, Int> = heuristicResolutionCounters.entries
            .associate { (resolution, count) -> resolution to count.get() }
            .toSortedMap()
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = gameId,
            seedBase = baseSeed,
            effectiveSetupSeed = gameSeed,
           expander = UnifiedSemanticExpander(actionSpaceProfile = actionSpaceProfile),
           knownDecks = knownDecks,
            projectionAuditSink = projectionAuditSink,
            heuristicResolutionSink = { resolution ->
                heuristicResolutionCounters.computeIfAbsent(resolution.name) { AtomicInteger() }
                    .incrementAndGet()
            },
        )
        if (progressObserver !== ArenaProgressObserver.NONE) {
            notifyProgress(gameId) {
                progressObserver.gameStarted(gameId, p0Policy.id, p1Policy.id)
            }
        }
        val policies = seatPolicies.mapValues { it.value.kind }
        val searchSeat = seatPolicies.entries.singleOrNull { it.value.kind == ArenaPolicyKind.SEARCH }?.key
        val publicTrajectorySeat = evidence?.publicTrajectory?.let {
            evidence.publicTrajectoryPerspective ?: searchSeat
        }
        val inspectionPerspective = evidence?.inspection?.let {
            evidence.inspectionPerspective ?: searchSeat ?: "p0"
        }
        require(inspectionPerspective == null || inspectionPerspective in policies.keys) {
            "Inspection perspective must be p0 or p1"
        }
        // This exact per-seat object is both the policy-session input and new runtime-metadata authority.
        val policyParametersBySeat = seatPolicies.mapNotNull { (viewer, policy) ->
            policy.takeIf { it.kind == ArenaPolicyKind.SEARCH && it.searchPlanner != SearchPlannerKind.NO_SEARCH_HEURISTIC }
                ?.let { viewer to it.effectiveParameters(baseSeed) }
        }.toMap()
        require(publicTrajectorySeat == null || publicTrajectorySeat in policyParametersBySeat) {
            "Public policy evidence requires the selected perspective to use Search Teacher"
        }
        val policySessions = policyParametersBySeat.mapValues { (viewer, parameters) ->
            val policy = seatPolicies.getValue(viewer)
            SearchTeacherPolicySession(
                root = world,
               viewer = viewer,
               knownDecks = knownDecks,
                parameters = parameters,
                opponentPolicy = opponentModel,
                gameId = gameId,
                informationEvaluator = policy.informationEvaluator,
                integration = SearchTeacherIntegrationSpecification(
                    hostMode = "evaluation-arena-v1",
                    searchPlanner = policy.searchPlanner.name,
                    maximumGameDecisions = gameDecisionLimit,
                    maximumSearchDecisions = maxSearchDecisions,
                    additionalBindings = mapOf(
                        "arenaPolicyId" to policy.id,
                        "arenaPolicyKind" to policy.kind.name,
                    ),
                ),
            )
        }
        val searchSession = searchSeat?.let(policySessions::get)
        val policyBindings = evidence?.sourceProvenance?.let { source ->
            seatPolicies.mapValues { (seat, policy) ->
                policySessions[seat]?.let { session ->
                    behaviorBinding(session.behaviorSpecification, source)
                } ?: directPolicyBehavior(
                    policy = policy,
                    maxSearchDecisions = maxSearchDecisions,
                    actionSpaceProfile = actionSpaceProfile,
                ).let { direct ->
                    PolicyBehaviorBinding.create(
                        behaviorIdentity = direct.identity,
                        behaviorSpecification = direct.specification,
                        sourceProvenance = source,
                    )
                }
            }
        }
        val inspectionExecutionPolicyBindings = evidence?.inspectionExecutionCommitment?.let {
            requireNotNull(policyBindings).toSortedMap()
        }
        val inspectionExecutionSearchRuntime = evidence?.inspectionExecutionCommitment?.let {
            val runtimes = policyParametersBySeat.values.toList()
            require(
                runtimes.map {
                    Triple(it.maxPolicyDecisions, it.particles, it.simulations)
                }.distinct().size <= 1
            ) {
                "Inspection execution-limit metadata cannot flatten different per-seat search limits"
            }
            runtimes.firstOrNull()
        }
        val latenciesBySeat = mutableMapOf("p0" to mutableListOf<Double>(), "p1" to mutableListOf())
        val selectionCountsBySeat = mutableMapOf(
            "p0" to mutableMapOf<SearchTeacherSelectionKind, Int>(),
            "p1" to mutableMapOf(),
        )
        val liveOpponentDecisionCounters = mapOf(
            "p0" to OpponentPolicyDecisionCounter(),
            "p1" to OpponentPolicyDecisionCounter(),
        )
        val heuristicComparatorDecisionCounters = mapOf(
            "p0" to OpponentPolicyDecisionCounter(),
            "p1" to OpponentPolicyDecisionCounter(),
        )
        val searchDecisionDetailsBySeat = mutableMapOf(
            "p0" to mutableListOf<ArenaSearchDecisionDiagnostic>(),
            "p1" to mutableListOf(),
        )
        fun seatDiagnostics(): Map<String, ArenaSeatDiagnostics> = seatPolicies.mapValues { (seat, policy) ->
            val session = policySessions[seat]
            ArenaSeatDiagnostics(
                policyId = policy.id,
                searchDecisions = latenciesBySeat.getValue(seat).size,
                searchLatenciesMillis = latenciesBySeat.getValue(seat),
                beliefUpdates = session?.beliefDiagnosticsHistory?.size ?: 0,
                lowEssUpdates = session?.beliefLowEssUpdates ?: 0,
                invalidBeliefWeights = session?.beliefInvalidWeights ?: 0,
                beliefResamplingCount = session?.latestBeliefDiagnostics?.resamplingCount ?: 0,
                beliefReconditionings = session?.beliefReconditionings ?: 0,
                beliefParticleDepletions = session?.beliefParticleDepletions ?: 0,
                meanBeliefEntropy = session?.beliefDiagnosticsHistory?.map { it.entropy }
                    ?.takeIf { it.isNotEmpty() }?.average(),
                selectionCounts = selectionCountsBySeat.getValue(seat).toMap(),
                liveOpponentPolicyDecisions = liveOpponentDecisionCounters.getValue(seat).summary(),
                heuristicComparatorDecisions = heuristicComparatorDecisionCounters.getValue(seat).summary(),
                searchDecisionsDetail = searchDecisionDetailsBySeat.getValue(seat).toList(),
            )
        }
        fun liveOpponentDecisionSummary(): OpponentPolicyDecisionSummary =
            liveOpponentDecisionCounters.values.map { it.summary() }.combined()
        fun heuristicComparatorDecisionSummary(): OpponentPolicyDecisionSummary =
            heuristicComparatorDecisionCounters.values.map { it.summary() }.combined()
        fun searchOpponentDecisionSummary(): OpponentPolicyDecisionSummary =
            searchDecisionDetailsBySeat.values.asSequence().flatten().map { detail ->
                detail.searchDiagnostics.opponentModelPolicyDecisions +
                    detail.searchDiagnostics.rootRolloutPolicyDecisions +
                    detail.searchDiagnostics.opponentRolloutPolicyDecisions
            }.toList().combined() + policySessions.values.flatMap { session ->
                session.beliefDiagnosticsHistory.map { it.opponentPolicyDecisions }
            }.combined()
        fun policyIdentityFor(seat: String?): String {
            if (seat == null) return policyVersion()
            return policyBindings?.get(seat)?.identity
                ?: policySessions[seat]?.policyIdentity
                ?: directPolicyBehavior(
                    policy = seatPolicies.getValue(seat),
                    maxSearchDecisions = maxSearchDecisions,
                    actionSpaceProfile = actionSpaceProfile,
                ).identity
        }
        val latencies = mutableListOf<Double>()
        val publicSequence = mutableListOf<SemanticChoice?>()
        var illegal = 0
        var decisions = 0
        var activePolicyTransitionDecisionIndex: Int? = null
        var cleanupDiscardEvents = 0
        var mainPhasePassesWithProactiveOptions = 0
        var publicWriter: PolicyTrajectoryWriter? = null
        val plannerEvidenceDecisions = mutableListOf<PlannerEvidenceDecision>()
        var debugWriter: PrivilegedDebugWriter? = null
        var inspectionRecorder: PolicyInspectionRecorder? = null
        var privilegedInspectionRecorder: PrivilegedInspectionRecorder? = null
        var replayWriter: CanonicalTournamentReplayWriter? = null
        val inspectionLimits = evidence?.inspectionExecutionCommitment?.executionLimits
        require(inspectionLimits == null || gameDecisionLimit == MAX_GAME_DECISIONS) {
            "Inspection execution limits and an arena decision limit must not compete"
        }
        val maximumGameDecisions = inspectionLimits?.maximumPolicyDecisions ?: gameDecisionLimit
        val wholeGameDeadlineNanos = inspectionLimits?.let {
            gameStartedAtNanos + it.wholeGameWallClockMillis * 1_000_000L
        }
        var transitions = 0
        var declaredLimitStopReason: PolicyTrajectoryStopReason? = null
        fun stopForRepresentationBoundary(
            detectionPoint: EvidenceStopDetectionPoint,
            triggeringDecisionIndex: Int? = null,
        ) {
            representationBoundaryDetector.detect(world, detectionPoint, triggeringDecisionIndex)?.let { failure ->
                val refusedDecision = when (detectionPoint) {
                    EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION -> decisions + 1
                    EvidenceStopDetectionPoint.BEFORE_POLICY_CHOICE -> decisions
                    else -> error("Representation boundary cannot be detected at $detectionPoint")
                }
                throw EvidenceRunStopException(
                    GameRunDisposition.STOPPED_REPRESENTATION,
                    EvidenceStopMetadata(
                        triggerCodes = failure.triggerCodes,
                        affectedViewers = failure.affectedViewers,
                        firstDetectedBeforeDecision = refusedDecision,
                        detectionPoint = detectionPoint,
                        triggeringDecisionIndex = triggeringDecisionIndex,
                        refusedPolicyDecisionIndex = refusedDecision,
                    ),
                )
            }
        }

        fun stopForSoftwareTransition(code: String) {
            throw EvidenceRunStopException(
                GameRunDisposition.STOPPED_SOFTWARE,
                EvidenceStopMetadata(
                    triggerCodes = listOf(code),
                    affectedViewers = emptyList(),
                    firstDetectedBeforeDecision = decisions + 1,
                    detectionPoint = EvidenceStopDetectionPoint.DURING_SOFTWARE_TRANSITION,
                    triggeringDecisionIndex = decisions,
                    refusedPolicyDecisionIndex = decisions + 1,
                ),
            )
        }
        try {
            replay?.let { replayOptions ->
                val createdAtUtc = Instant.now().toString()
                replayWriter = CanonicalTournamentReplayWriter.create(
                    options = replayOptions,
                    initialState = world.authoritativeStateForHost(),
                    initializationEvents = world.initializationEventsForHost(),
                    players = listOf("p0", "p1"),
                    extensions = canonicalTournamentReplayExtensions(
                        gameId = gameId,
                        createdAtUtc = createdAtUtc,
                        options = replayOptions,
                        gameSeed = gameSeed,
                        baseSeed = baseSeed,
                        manifest = manifest,
                    ),
                )
            }
            if (evidence?.publicTrajectory != null) {
                val evidenceSeat = requireNotNull(publicTrajectorySeat) {
                    "Public policy evidence requires an explicit Search Teacher perspective"
                }
                val searchRuntime = requireNotNull(policyParametersBySeat[evidenceSeat]) {
                    "Public policy evidence requires an acting Search Teacher runtime"
                }
                publicWriter = PolicyTrajectoryWriter.compressed(evidence.publicTrajectory)
                publicWriter.append(
                    PolicyTrajectoryHeader(
                        gameId = gameId,
                        createdAtUtc = Instant.now().toString(),
                        outerCommit = evidence.outerCommit,
                        argentumCommit = evidence.argentumCommit,
                        deckManifestHash = manifest.deckHash(),
                        cardPoolHash = manifest.cardPoolHash(),
                        perspectivePlayerId = evidenceSeat,
                        profileManifestHash = evidence.profileHash,
                        behaviorBinding = requireNotNull(policyBindings?.get(evidenceSeat)) {
                            "Public policy evidence requires a behavior-and-source binding"
                        },
                        policyVersion = policyIdentityFor(evidenceSeat),
                        evaluatorVersion = searchRuntime.leaf.evaluator.evaluatorId,
                        leaf = searchRuntime.leaf,
                        actionSpaceProfile = searchRuntime.actionSpaceProfile,
                        beliefVersion = searchRuntime.beliefVersion(),
                        opponentModelVersion = opponentModel.id,
                    )
                )
            }
            evidence?.privilegedDebug?.let { debugWriter = PrivilegedDebugWriter(it, gameId) }
            if (inspectionPerspective != null) {
                val policyVersion = policyIdentityFor(inspectionPerspective)
                val inspectionRuntime = policyParametersBySeat[inspectionPerspective]
                inspectionRecorder = PolicyInspectionRecorder(
                    gameId = gameId,
                    outerCommit = evidence.outerCommit,
                    argentumCommit = evidence.argentumCommit,
                    deckManifestHash = manifest.deckHash(),
                    cardPoolHash = manifest.cardPoolHash(),
                    profileManifestHash = evidence.profileHash,
                    perspectivePlayerId = inspectionPerspective,
                    policyVersion = policyVersion,
                    evaluatorVersion = inspectionRuntime?.leaf?.evaluator?.evaluatorId
                        ?: "not-run",
                    beliefVersion = inspectionRuntime?.beliefVersion()
                        ?: "not-run",
                    opponentModelVersion = opponentModel.id.takeIf { inspectionRuntime != null } ?: "not-run",
                    runtimeLeaf = inspectionRuntime?.leaf,
                    runtimeBeliefMode = inspectionRuntime?.beliefMode,
                    runtimeBeliefArchitecture = inspectionRuntime?.beliefArchitecture,
                ).also { it.recordInitial(world.informationState(inspectionPerspective)) }
                if (evidence.privilegedInspection != null) {
                    privilegedInspectionRecorder = PrivilegedInspectionRecorder(
                        gameId,
                        evidence.outerCommit,
                        evidence.argentumCommit,
                    ).also { it.recordInitial(world.privilegedDebugSnapshot()) }
                }
            }

            while (
                world.terminalPayoff("p0") == null && declaredLimitStopReason == null &&
                decisions < maximumGameDecisions &&
                (maxSearchDecisions == null || latencies.size < maxSearchDecisions)
            ) {
                if (wholeGameDeadlineNanos != null && System.nanoTime() >= wholeGameDeadlineNanos) {
                    declaredLimitStopReason = PolicyTrajectoryStopReason.WHOLE_GAME_TIME_LIMIT_REACHED
                    break
                }
                val actor = requireNotNull(world.actorToAct()) { "Non-terminal game has no actor" }
                // O-04(a): an existing incomplete ledger refuses the next policy decision.
                stopForRepresentationBoundary(EvidenceStopDetectionPoint.BEFORE_POLICY_CHOICE)
                val information = world.informationState(actor)
                if (inspectionLimits != null && information.observation.turnNumber > inspectionLimits.maximumTurns) {
                    declaredLimitStopReason = PolicyTrajectoryStopReason.TURN_LIMIT_REACHED
                    break
                }
                rootProbe?.invoke(world, actor, decisions)
                val decisionProgress = if (progressObserver !== ArenaProgressObserver.NONE) {
                    ArenaDecisionProgress.from(gameId, decisions, actor, information.observation)
                } else {
                    null
                }
                val policyDecisionStartedAt = System.nanoTime()
                val decisionStartedAt = policyDecisionStartedAt.takeIf { decisionProgress != null }
                decisionProgress?.let { progress ->
                    notifyProgress(gameId) { progressObserver.decisionStarted(progress) }
                }
                val evidenceHistorySize = publicTrajectorySeat?.let { world.informationState(it).history.size }
                val expansion = world.expandChoices()
                check(expansion.candidates.isNotEmpty()) { "No semantic choices at decision $decisions" }
                debugWriter?.append(decisions, null, world.privilegedDebugSnapshot())

                val authoritativeBeforeSelection = world.authoritativeFingerprint()
                val selection = choose(
                    world = world,
                    actor = actor,
                    policy = seatPolicies.getValue(actor),
                    decisionIndex = decisions,
                    gameId = gameId,
                    policySession = policySessions[actor],
                )
                if (inspectionLimits != null) {
                    val selectionFinishedAt = System.nanoTime()
                    val policyDeadline = policyDecisionStartedAt +
                        inspectionLimits.perDecisionTimeoutMillis * 1_000_000L
                    val earliestDeadline = minOf(policyDeadline, requireNotNull(wholeGameDeadlineNanos))
                    if (selectionFinishedAt >= earliestDeadline) {
                        declaredLimitStopReason = if (wholeGameDeadlineNanos <= policyDeadline) {
                            PolicyTrajectoryStopReason.WHOLE_GAME_TIME_LIMIT_REACHED
                        } else {
                            PolicyTrajectoryStopReason.PER_DECISION_TIME_LIMIT_REACHED
                        }
                        break
                    }
                }
                if (selection.searchResult != null) {
                    check(world.freshAuthoritativeFingerprintForHost() == authoritativeBeforeSelection) {
                        "Policy search mutated the authoritative world at decision $decisions"
                    }
                }
                selection.selectionKind?.let { kind ->
                    val counts = selectionCountsBySeat.getValue(actor)
                    counts[kind] = (counts[kind] ?: 0) + 1
                }
                selection.opponentPolicyDecision?.let {
                    liveOpponentDecisionCounters.getValue(actor).record(it)
                }
                if (selection.heuristicFallback) {
                    notifyProgress(gameId) {
                        progressObserver.policyFallback(
                            ArenaPolicyFallbackProgress(
                                gameId = gameId,
                                decisionIndex = decisions,
                                actor = actor,
                                diagnosis = requireNotNull(selection.heuristicDiagnosis),
                            )
                        )
                    }
                }
                selection.searchResult?.let { search ->
                    var comparatorReplacement = false
                    val heuristicChoice = expansion.candidates.singleOrNull {
                        org.mtgallium.agent.infoset.argentum.ARGENTUM_HEURISTIC_CHOICE_TAG_V1 in
                            it.display.policyTags
                    }?.takeIf { expansion.isExhaustive }
                        ?: world.determinizedHeuristicChoiceOrNull()
                        ?: sample(
                            SemanticHeuristicOpponentPolicy().distribution(
                                information,
                                expansion.candidates,
                                ComponentSeeds.derive(gameId, decisions, "heuristic-evidence-fallback"),
                            ),
                            ComponentSeeds.derive(gameId, decisions, "heuristic-evidence-fallback"),
                        ).also { comparatorReplacement = true }
                    heuristicComparatorDecisionCounters.getValue(actor).record(
                        heuristicDecisionDiagnostic(replaced = comparatorReplacement)
                    )
                    publicWriter?.takeIf { actor == publicTrajectorySeat }?.let { writer ->
                        val beliefDiagnostics = requireNotNull(selection.beliefDiagnostics)
                        val actingRuntime = requireNotNull(policyParametersBySeat[actor]) {
                            "Search evidence requires the acting seat's runtime configuration"
                        }
                        val policyInput = BoundedPolicyInputCompiler.compile(
                            information,
                            PolicyBeliefSummary.from(
                                beliefDiagnostics,
                                information.knowledge.knowledgeDigest,
                            ),
                        )
                        writer.append(
                            PolicyTrajectoryDecision(
                                gameId = gameId,
                                decisionIndex = decisions,
                                actingPlayerId = actor,
                                policyVersion = policyIdentityFor(actor),
                                evaluatorVersion = actingRuntime.leaf.evaluator.evaluatorId,
                                leaf = actingRuntime.leaf,
                                actionSpaceProfile = actingRuntime.actionSpaceProfile,
                                beliefVersion = actingRuntime.beliefVersion(),
                                opponentModelVersion = opponentModel.id,
                                policyInput = policyInput,
                                expansion = expansion,
                                candidates = search.candidates,
                                chosen = selection.choice,
                                heuristicChoice = heuristicChoice,
                                rootValue = search.rootValue,
                                beliefDiagnostics = beliefDiagnostics,
                                searchDiagnostics = search.diagnostics,
                            )
                        )
                        if (evidence?.plannerEvidence != null) {
                            plannerEvidenceDecisions += search.plannerEvidenceDecision(
                                gameId = gameId,
                                decisionIndex = decisions,
                                actingPlayerId = actor,
                                informationStateDigest = policyInput.informationStateDigest,
                                latencyMillis = selection.latencyMillis,
                            )
                        }
                    }
                    if (actor == inspectionPerspective) {
                        inspectionRecorder?.recordSearch(
                            decisionIndex = decisions,
                            expansion = expansion,
                            result = search,
                            heuristicChoice = heuristicChoice,
                            beliefDiagnostics = requireNotNull(selection.beliefDiagnostics),
                        )
                    }
                    latencies += selection.latencyMillis
                    latenciesBySeat.getValue(actor) += selection.latencyMillis
                    searchDecisionDetailsBySeat.getValue(actor) += ArenaSearchDecisionDiagnostic(
                        decisionIndex = decisions,
                        turnNumber = information.observation.turnNumber,
                        phase = information.observation.phase,
                        step = information.observation.step,
                        latencyMillis = selection.latencyMillis,
                        searchDiagnostics = search.diagnostics,
                        settlementCounts = search.candidateSettlementCounts.values.fold(
                            org.mtgallium.agent.infoset.core.SearchSettlementCounts(),
                        ) { total, counts -> total.plus(counts) },
                        settlementCountsAvailability =
                            SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1,
                        chosen = selection.choice,
                        rootValue = search.rootValue,
                        candidateStatistics = search.candidates,
                    )
                }
                val mainPhasePassWithProactiveOption =
                    selection.choice.operationFamily == SemanticOperationFamily.PASS_PRIORITY &&
                        (information.observation.phase.contains("MAIN") ||
                            information.observation.step.contains("MAIN")) &&
                        information.observation.stack.isEmpty() &&
                        information.observation.activePlayerId == actor &&
                        expansion.candidates.any {
                            it.operationFamily in setOf(
                                SemanticOperationFamily.PLAY_LAND,
                                SemanticOperationFamily.CAST_SPELL,
                                SemanticOperationFamily.ACTIVATE_ABILITY,
                            )
                        }
                val replayStep = if (replayWriter != null) {
                    world.stepWithReplayTrace(selection.choice)
                } else null
                val step = replayStep?.result ?: world.step(selection.choice)
                replayStep?.let { traced ->
                    replayWriter?.appendChoice(
                        decisionIndex = decisions,
                        semanticChoice = selection.choice,
                        opponentPolicyDecision = selection.opponentPolicyDecision,
                        rawTransitions = traced.rawTransitions,
                    )
                }
                if (!step.accepted) {
                    illegal++
                    stopForSoftwareTransition("LIVE_SEMANTIC_STEP_REJECTED")
                }
                transitions += 1 + step.forcedTransitions.size
                // Check immediately after the accepted engine transition, even if it just ended the game.
                // This must precede observers, policy-session updates, trajectory outcome, and another choice.
                stopForRepresentationBoundary(
                    EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION,
                    triggeringDecisionIndex = decisions,
                )
                // Diagnostic observers see the authoritative world immediately after acceptance,
                // before policy-session observation, counters, or the decision index advance.
                acceptedStepProbe?.invoke(world, actor, decisions, selection.choice, step)
                if (mainPhasePassWithProactiveOption) mainPhasePassesWithProactiveOptions++
                val cleanupDiscardPlayers = step.forcedTransitions.mapNotNull { event ->
                    (event.detail as? PerspectiveEventDetail.Causal)?.takeIf {
                        it.eventType == "CLEANUP_DISCARD_REQUIRED"
                    }?.actorId
                }
                cleanupDiscardEvents += cleanupDiscardPlayers.size
                activePolicyTransitionDecisionIndex = decisions
                policySessions.values.forEach { session ->
                    session.observeAccepted(
                        actual = world,
                        actor = actor,
                        choice = selection.choice,
                        decisionIndex = decisions,
                        privateToActor = step.privateToActor,
                    )
                }
                activePolicyTransitionDecisionIndex = null
                publicSequence += selection.choice.takeUnless { step.privateToActor && actor != publicTrajectorySeat }
                if (publicWriter != null) {
                    val visibleEvents = world.informationState(requireNotNull(publicTrajectorySeat)).history
                        .drop(requireNotNull(evidenceHistorySize))
                    check(visibleEvents.isNotEmpty()) { "Decision $decisions produced no perspective-safe history event" }
                    publicWriter.append(
                        PolicyTrajectoryForcedTransition(
                            gameId = gameId,
                            afterDecisionIndex = decisions,
                            events = visibleEvents,
                        )
                    )
                }
                inspectionRecorder?.recordTransition(
                    decisionIndex = decisions,
                    actorId = actor,
                    actualChoice = selection.choice,
                    privateToActor = step.privateToActor,
                    informationAfter = world.informationState(requireNotNull(inspectionPerspective)),
                )
                privilegedInspectionRecorder?.recordTransition(
                    decisions,
                    selection.choice,
                    world.privilegedDebugSnapshot(),
                )
                debugWriter?.append(decisions, selection.choice, world.privilegedDebugSnapshot())
                decisions++
                decisionProgress?.let { progress ->
                    notifyProgress(gameId) {
                        progressObserver.decisionCompleted(
                            progress,
                            (System.nanoTime() - requireNotNull(decisionStartedAt)) / 1_000_000.0,
                        )
                    }
                }
                if (inspectionLimits != null) {
                    val gameEnded = world.terminalPayoff("p0") != null
                    declaredLimitStopReason = when {
                        transitions > inspectionLimits.maximumTransitions ->
                            PolicyTrajectoryStopReason.TRANSITION_LIMIT_REACHED
                        !gameEnded && transitions == inspectionLimits.maximumTransitions ->
                            PolicyTrajectoryStopReason.TRANSITION_LIMIT_REACHED
                        System.nanoTime() >= requireNotNull(wholeGameDeadlineNanos) ->
                            PolicyTrajectoryStopReason.WHOLE_GAME_TIME_LIMIT_REACHED
                        else -> null
                    }
                }
            }
            // Covers an initial terminal state and protects final serialization from a last-observed ledger change.
            if (declaredLimitStopReason == null) {
                stopForRepresentationBoundary(
                    if (decisions == 0) EvidenceStopDetectionPoint.BEFORE_POLICY_CHOICE
                    else EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION,
                    triggeringDecisionIndex = (decisions - 1).takeIf { decisions > 0 },
                )
            }
            val engineTerminal = world.terminalPayoff("p0") != null
            val terminal = engineTerminal && declaredLimitStopReason == null
            val stopReason = if (terminal) {
                null
            } else if (declaredLimitStopReason != null) {
                declaredLimitStopReason
            } else if (decisions >= maximumGameDecisions) {
                PolicyTrajectoryStopReason.GAME_DECISION_LIMIT_REACHED
            } else {
                check(maxSearchDecisions != null && latencies.size >= maxSearchDecisions)
                PolicyTrajectoryStopReason.SEARCH_DECISION_LIMIT_REACHED
            }
            val finalKnowledge = listOf("p0", "p1").map { world.informationState(it).knowledge }
            val unsupportedInformationEvents = finalKnowledge.flatMap { it.unsupportedReasons }.distinct().sorted()
            val winner = when (world.terminalPayoff("p0")) {
                1.0 -> "p0"
                -1.0 -> "p1"
                else -> null
            }
            publicWriter?.append(
                PolicyTrajectoryOutcome(
                    gameId = gameId,
                    decisions = decisions,
                    completion = if (terminal) {
                        PolicyTrajectoryCompletion.GAME_ENDED
                    } else {
                        PolicyTrajectoryCompletion.STOPPED_BEFORE_GAME_END
                    },
                    stopReason = stopReason,
                    winnerId = winner,
                    resultByPlayer = if (terminal) {
                        mapOf(
                            "p0" to requireNotNull(world.terminalPayoff("p0")),
                            "p1" to requireNotNull(world.terminalPayoff("p1")),
                        )
                    } else null,
                    semanticResponseSequence = publicSequence,
                )
            )
            publicWriter?.close()
            publicWriter = null
            evidence?.plannerEvidence?.let { plannerPath ->
                val safeTrajectory = requireNotNull(evidence.publicTrajectory)
                val evidenceSeat = requireNotNull(publicTrajectorySeat)
                val plannerRuntime = requireNotNull(policyParametersBySeat[evidenceSeat]) {
                    "Planner evidence requires the Search Teacher runtime"
                }
                PlannerEvidenceSidecar(
                    binding = PlannerEvidenceBinding(
                        gameId = gameId,
                        safeTrajectoryReference = requireNotNull(evidence.publicTrajectoryReference),
                        safeTrajectorySha256 = sha256File(safeTrajectory),
                        trajectorySchemaVersion = org.mtgallium.agent.infoset.core.TRAJECTORY_SCHEMA_CURRENT,
                        candidateSchemaVersion = org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_CURRENT,
                        behaviorBinding = requireNotNull(policyBindings?.get(evidenceSeat)) {
                            "Planner evidence requires the Search Teacher behavior binding"
                        },
                        actionSpaceProfile = plannerRuntime.actionSpaceProfile,
                        researchRunIdentity = evidence.researchRunIdentity,
                    ),
                    decisions = plannerEvidenceDecisions.toList(),
                ).writeCompressed(plannerPath)
            }
            debugWriter?.close()
            debugWriter = null
            if (inspectionRecorder != null && !(engineTerminal && !terminal)) {
                val publicPath = requireNotNull(evidence?.inspection)
                writeInspectionPair(
                    publicPath = publicPath,
                    privilegedPath = evidence.privilegedInspection,
                    publicBundle = requireNotNull(inspectionRecorder).finish(
                        outcome = PolicyInspectionOutcome(
                            decisions = decisions,
                            terminated = terminal,
                            truncated = !terminal,
                            winnerId = winner,
                            resultByPlayer = mapOf(
                                "p0" to (world.terminalPayoff("p0") ?: 0.0),
                                "p1" to (world.terminalPayoff("p1") ?: 0.0),
                            ),
                        ),
                        executionBinding = evidence.inspectionExecutionCommitment?.let { commitment ->
                            val bindings = requireNotNull(inspectionExecutionPolicyBindings)
                            PolicyInspectionExecutionBinding(
                                protocolId = commitment.protocolId,
                                manifestSha256 = commitment.manifestSha256,
                                scheduledExecutionSha256 = inspectionScheduledExecutionSha256(
                                    commitment.protocolId,
                                    gameId,
                                    gameSeed,
                                    commitment.randomizationUnitId,
                                ),
                                declaredBehaviorSha256 = commitment.declaredBehaviorSha256,
                                declaredPopulationSha256 = commitment.declaredPopulationSha256,
                                declaredLimitsSha256 = commitment.declaredLimitsSha256,
                                actualPolicyByPlayer = bindings.mapValues { (seat, binding) ->
                                    PolicyInspectionPolicyExecutionIdentity(
                                        policyId = seatPolicies.getValue(seat).id,
                                        behaviorIdentity = binding.identity,
                                        behaviorSpecificationSha256 = binding.behaviorSpecificationSha256,
                                    )
                                }.toSortedMap(),
                                actualDeckSha256ByPlayer = policies.keys.associateWith { manifest.deckHash() }
                                    .toSortedMap(),
                                actualGameConfigurationSha256 = inspectionGameConfigurationSha256(
                                    gameId = gameId,
                                    gameSeed = gameSeed,
                                    policyIdsByPlayer = seatPolicies.mapValues { it.value.id }.toSortedMap(),
                                    deckSha256ByPlayer = policies.keys.associateWith { manifest.deckHash() }
                                        .toSortedMap(),
                                ),
                                actualRuntimeIdentitySha256 = inspectionRuntimeIdentitySha256(),
                                actualExecutionLimits = requireNotNull(inspectionLimits).asRuntimeFacts(
                                    inspectionExecutionSearchRuntime,
                                ),
                                liveOpponentPolicyDecisions = liveOpponentDecisionSummary(),
                                searchOpponentPolicyDecisions = searchOpponentDecisionSummary(),
                                heuristicComparatorDecisions = heuristicComparatorDecisionSummary(),
                            )
                        },
                    ),
                    privilegedRecorder = privilegedInspectionRecorder,
                    registry = registry,
                    baseCardNames = manifest.mainDeck.keys,
                )
            }
            val provisional = GameRunResult(
                gameId = gameId,
                seed = gameSeed,
                p0Policy = p0Policy.kind,
                p1Policy = p1Policy.kind,
                searchPlanner = searchSeat?.let { seatPolicies.getValue(it).searchPlanner },
                winner = winner,
                terminal = terminal,
                disposition = if (terminal) GameRunDisposition.GAME_ENDED else GameRunDisposition.STOPPED_LIMIT,
                decisions = decisions,
                searchSeat = searchSeat,
                searchScore = searchSeat?.let { seat ->
                    when {
                        !terminal -> null
                        winner == null -> 0.5
                        winner == seat -> 1.0
                        else -> 0.0
                    }
                },
                illegalResponses = illegal,
                fallbacks = liveOpponentDecisionSummary().evidenceInvalidatingReplacements +
                    searchOpponentDecisionSummary().evidenceInvalidatingReplacements +
                    heuristicComparatorDecisionSummary().evidenceInvalidatingReplacements,
                heuristicResolutionCounts = heuristicResolutionCounts(),
                liveOpponentPolicyDecisions = liveOpponentDecisionSummary(),
                searchOpponentPolicyDecisions = searchOpponentDecisionSummary(),
                heuristicComparatorDecisions = heuristicComparatorDecisionSummary(),
                stepLimit = !terminal,
                searchLatenciesMillis = latencies,
                beliefUpdates = searchSession?.beliefDiagnosticsHistory?.size ?: 0,
                lowEssUpdates = searchSession?.beliefLowEssUpdates ?: 0,
                invalidBeliefWeights = searchSession?.beliefInvalidWeights ?: 0,
                beliefResamplingCount = searchSession?.latestBeliefDiagnostics?.resamplingCount ?: 0,
                beliefReconditionings = searchSession?.beliefReconditionings ?: 0,
                beliefParticleDepletions = searchSession?.beliefParticleDepletions ?: 0,
                meanBeliefEntropy = searchSession?.beliefDiagnosticsHistory?.map { it.entropy }
                    ?.takeIf { it.isNotEmpty() }?.average(),
                informationLedgerComplete = finalKnowledge.all { it.epistemicallyComplete },
                unsupportedInformationEvents = unsupportedInformationEvents,
                p0PolicyId = p0Policy.id,
                p1PolicyId = p1Policy.id,
                seatDiagnostics = seatDiagnostics(),
                cleanupDiscardEvents = cleanupDiscardEvents,
                mainPhasePassesWithProactiveOptions = mainPhasePassesWithProactiveOptions,
                elapsedMillis = (System.nanoTime() - gameStartedAtNanos) / 1_000_000.0,
            )
            val replayArtifact = replayWriter?.finish(
                finalState = world.authoritativeStateForHost(),
                complete = terminal,
                winnerId = winner,
            )
            replayWriter = null
            val result = provisional.copy(
                replayPath = replayArtifact?.referencePath,
                replaySha256 = replayArtifact?.sha256,
                replayVerified = replayArtifact?.verified == true,
                replayVerificationDiagnostic = replayArtifact?.verificationDiagnostic,
            )
            if (progressObserver !== ArenaProgressObserver.NONE) {
                notifyProgress(gameId) { progressObserver.gameFinished(result) }
            }
            return result
        } catch (error: Throwable) {
            val explicitStop = error as? EvidenceRunStopException
            val informationFailure = error as? UnsupportedInformationStateException
            val rejectedTransition = error as? RejectedSearchTransitionException
            val beliefSupportFailure = error as? ArgentumBeliefSupportException
            val learnedValueStopFailure = error as? LearnedOutcomeValuePolicyStopException
            val disposition = explicitStop?.disposition ?: when {
                informationFailure != null -> GameRunDisposition.STOPPED_REPRESENTATION
                else -> GameRunDisposition.STOPPED_SOFTWARE
            }
            val evidenceStop = explicitStop?.metadata ?: when {
                informationFailure != null -> EvidenceStopMetadata(
                    triggerCodes = informationFailure.reasonCodes.map { "UNREPRESENTED:$it" }.distinct().sorted(),
                    affectedViewers = emptyList(),
                    firstDetectedBeforeDecision = decisions,
                    detectionPoint = EvidenceStopDetectionPoint.BEFORE_POLICY_CHOICE,
                    refusedPolicyDecisionIndex = decisions,
                )
                rejectedTransition != null -> EvidenceStopMetadata(
                    triggerCodes = listOf("REJECTED_SEARCH_TRANSITION"),
                    affectedViewers = emptyList(),
                    firstDetectedBeforeDecision = decisions + 1,
                    detectionPoint = EvidenceStopDetectionPoint.DURING_SOFTWARE_TRANSITION,
                    triggeringDecisionIndex = decisions,
                    refusedPolicyDecisionIndex = decisions + 1,
                )
                beliefSupportFailure != null -> beliefSupportStopMetadata(
                    failure = beliefSupportFailure,
                    currentDecisionIndex = decisions,
                    triggeringDecisionIndex = activePolicyTransitionDecisionIndex,
                )
                learnedValueStopFailure != null -> learnedOutcomeValueStopMetadata(
                    learnedValueStopFailure,
                    decisions,
                )
                else -> EvidenceStopMetadata(
                    triggerCodes = listOf("SOFTWARE:${error::class.simpleName ?: "UNKNOWN"}"),
                    affectedViewers = emptyList(),
                    firstDetectedBeforeDecision = decisions,
                    detectionPoint = EvidenceStopDetectionPoint.UNCAUGHT_SOFTWARE_FAILURE,
                    refusedPolicyDecisionIndex = decisions,
                )
            }
            publicWriter?.let { writer ->
                val completedDecisions = writer.completedDecisions
                runCatching {
                    writer.append(
                        PolicyTrajectoryOutcome(
                            gameId = gameId,
                            decisions = completedDecisions,
                            completion = PolicyTrajectoryCompletion.STOPPED_BEFORE_GAME_END,
                            stopReason = when (disposition) {
                                GameRunDisposition.STOPPED_REPRESENTATION ->
                                    PolicyTrajectoryStopReason.REPRESENTATION_FAILURE
                                GameRunDisposition.STOPPED_SOFTWARE ->
                                    PolicyTrajectoryStopReason.SOFTWARE_TRANSITION_FAILURE
                                else -> PolicyTrajectoryStopReason.TECHNICAL_FAILURE
                            },
                            winnerId = null,
                            resultByPlayer = null,
                            semanticResponseSequence = publicSequence.take(completedDecisions),
                        )
                    )
                }
                runCatching { writer.close() }
            }
            publicWriter = null
            runCatching { debugWriter?.close() }
            val exceptionText = when (disposition) {
                GameRunDisposition.STOPPED_REPRESENTATION ->
                    "UNSUPPORTED_INFORMATION_STATE:${evidenceStop.triggerCodes.joinToString(",")}"
                GameRunDisposition.STOPPED_SOFTWARE -> when (val policyStop = learnedValueStopFailure) {
                    null -> "SOFTWARE_TRANSITION_FAILURE:" + evidenceStop.triggerCodes.joinToString(",")
                    else -> "LEARNED_OUTCOME_VALUE_FAILURE:${policyStop.failureKind}:" +
                        "${policyStop.cause?.javaClass?.simpleName ?: "NO_CAUSE"}"
                }
                else -> "${error::class.qualifiedName}: ${error.message}"
            }
            val replayArtifact = replayWriter?.let { writer ->
                runCatching {
                    writer.finish(
                        finalState = world.authoritativeStateForHost(),
                        complete = false,
                        winnerId = null,
                        incompleteReason = org.mtgallium.evaluation.searchteacher.replay.ReplayIncompleteReason.RECORDING_FAILURE,
                    )
                }.getOrElse {
                    writer.preservePartial()
                    null
                }
            }
            replayWriter = null
            val result = GameRunResult(
                gameId = gameId,
                seed = gameSeed,
                p0Policy = p0Policy.kind,
                p1Policy = p1Policy.kind,
                searchPlanner = searchSeat?.let { seatPolicies.getValue(it).searchPlanner },
                winner = null,
                terminal = false,
                disposition = disposition,
                evidenceStop = evidenceStop,
                decisions = decisions,
                searchSeat = searchSeat,
                searchScore = null,
                illegalResponses = illegal,
                fallbacks = liveOpponentDecisionSummary().evidenceInvalidatingReplacements +
                    searchOpponentDecisionSummary().evidenceInvalidatingReplacements +
                    heuristicComparatorDecisionSummary().evidenceInvalidatingReplacements,
                heuristicResolutionCounts = heuristicResolutionCounts(),
                liveOpponentPolicyDecisions = liveOpponentDecisionSummary(),
                searchOpponentPolicyDecisions = searchOpponentDecisionSummary(),
                heuristicComparatorDecisions = heuristicComparatorDecisionSummary(),
                stepLimit = false,
                exception = exceptionText,
                searchLatenciesMillis = latencies,
                beliefUpdates = searchSession?.beliefDiagnosticsHistory?.size ?: 0,
                lowEssUpdates = searchSession?.beliefLowEssUpdates ?: 0,
                invalidBeliefWeights = searchSession?.beliefInvalidWeights ?: 0,
                beliefResamplingCount = searchSession?.latestBeliefDiagnostics?.resamplingCount ?: 0,
                beliefReconditionings = searchSession?.beliefReconditionings ?: 0,
                beliefParticleDepletions = searchSession?.beliefParticleDepletions ?: 0,
                meanBeliefEntropy = searchSession?.beliefDiagnosticsHistory?.map { it.entropy }
                    ?.takeIf { it.isNotEmpty() }?.average(),
                informationLedgerComplete = disposition != GameRunDisposition.STOPPED_REPRESENTATION && runCatching {
                    listOf("p0", "p1").all { world.informationState(it).knowledge.epistemicallyComplete }
                }.getOrDefault(false),
                unsupportedInformationEvents = informationFailure?.reasonCodes ?: runCatching {
                    listOf("p0", "p1").flatMap {
                        world.informationState(it).knowledge.unsupportedReasons
                    }.distinct().sorted()
                }.getOrDefault(listOf("ledger unavailable after exception")),
                p0PolicyId = p0Policy.id,
                p1PolicyId = p1Policy.id,
                seatDiagnostics = seatDiagnostics(),
                replayPath = replayArtifact?.referencePath,
                replaySha256 = replayArtifact?.sha256,
                replayVerified = replayArtifact?.verified == true,
                replayVerificationDiagnostic = replayArtifact?.verificationDiagnostic,
                cleanupDiscardEvents = cleanupDiscardEvents,
                mainPhasePassesWithProactiveOptions = mainPhasePassesWithProactiveOptions,
                elapsedMillis = (System.nanoTime() - gameStartedAtNanos) / 1_000_000.0,
            )
            if (progressObserver !== ArenaProgressObserver.NONE) {
                notifyProgress(gameId) { progressObserver.gameFinished(result) }
            }
            return result
        }
    }

    private fun notifyProgress(gameId: String, notification: () -> Unit) {
        runCatching(notification).onFailure { error ->
            System.err.println(
                "Arena progress observer failed for $gameId: ${error::class.simpleName}: ${error.message}"
            )
        }
    }

    private fun choose(
        world: ArgentumSearchWorld,
        actor: String,
        policy: ArenaPolicySpec,
        decisionIndex: Int,
        gameId: String,
        policySession: SearchTeacherPolicySession?,
    ): Selection = when (policy.kind) {
        ArenaPolicyKind.SEARCH -> {
            val seatProfile = policy.profile
            val parameters = policy.effectiveParameters(baseSeed)
            if (policy.searchPlanner == SearchPlannerKind.NO_SEARCH_HEURISTIC) {
                heuristicSelection(world, actor, decisionIndex, gameId)
            } else {
                val session = requireNotNull(policySession) { "Search policy requires a production policy session" }
                val started = System.nanoTime()
                val searchSeed = ComponentSeeds.derive(gameId, decisionIndex, "search")
                when (policy.searchPlanner) {
                    SearchPlannerKind.SHARED_TREE -> {
                        val selected = session.select(
                            world = world,
                            actor = actor,
                            searchSeed = ComponentSeeds.derive(
                                gameId, decisionIndex, parameters.baseSeed, "live-search"
                            ),
                        )
                        Selection(
                            choice = selected.choice,
                            searchResult = selected.search,
                            beliefDiagnostics = session.latestBeliefDiagnostics,
                            latencyMillis = (System.nanoTime() - started) / 1_000_000.0,
                            selectionKind = selected.kind,
                        )
                    }
                    SearchPlannerKind.INDEPENDENT_DETERMINIZATION,
                    SearchPlannerKind.PERFECT_INFORMATION_ORACLE -> {
                        val historicalProfile = requireNotNull(seatProfile) {
                            "Independent/oracle arena planners require a historical frozen profile"
                        }
                        val result = when (policy.searchPlanner) {
                            SearchPlannerKind.INDEPENDENT_DETERMINIZATION -> independentDeterminizationSearch(
                                actor, session.beliefBatch(world), searchSeed, historicalProfile, opponentModel,
                            )
                            SearchPlannerKind.PERFECT_INFORMATION_ORACLE -> search(historicalProfile, opponentModel).search(
                                actor,
                                BeliefBatch(
                                    particles = listOf(
                                        Weighted(
                                            world.forkForHypotheticalSearch(
                                                ComponentSeeds.derive(
                                                    searchSeed,
                                                    "perfect-information-oracle-world",
                                                )
                                            ),
                                            1.0,
                                        )
                                    ),
                                    diagnostics = session.latestBeliefDiagnostics.copy(
                                        requestedParticles = 1,
                                        acceptedParticles = 1,
                                        rejectedParticles = 0,
                                        effectiveSampleSizeBefore = 1.0,
                                        effectiveSampleSizeAfter = 1.0,
                                        entropy = 0.0,
                                    ),
                                ),
                                searchSeed,
                            )
                            else -> error("planner branch mismatch")
                        }
                        Selection(
                            choice = result.chosen,
                            searchResult = result,
                            beliefDiagnostics = session.latestBeliefDiagnostics,
                            latencyMillis = (System.nanoTime() - started) / 1_000_000.0,
                            selectionKind = SearchTeacherSelectionKind.SEARCHED,
                        )
                    }
                    SearchPlannerKind.NO_SEARCH_HEURISTIC -> error("handled before belief construction")
                }
            }
        }
        ArenaPolicyKind.HEURISTIC -> heuristicSelection(world, actor, decisionIndex, gameId)
        else -> {
            val candidates = world.expandChoices().candidates
            val opponentPolicy = policy(policy.kind)
            val information = world.informationState(actor)
            val policySeed = ComponentSeeds.derive(gameId, decisionIndex, policy.kind, "policy")
            val distribution = opponentPolicy.distribution(
                information,
                candidates,
                policySeed,
            )
            val choice = sample(
                distribution,
                ComponentSeeds.derive(gameId, decisionIndex, policy.kind, "sample"),
            )
            Selection(
                choice = choice,
                searchResult = null,
                beliefDiagnostics = null,
                latencyMillis = 0.0,
                opponentPolicyDecision = opponentPolicy.decisionDiagnostic(
                    opponentInformation = information,
                    candidates = candidates,
                    chosen = choice,
                    policySeed = policySeed,
                    attributionSeed = ComponentSeeds.derive(
                        gameId,
                        decisionIndex,
                        policy.kind,
                        "component-attribution",
                    ),
                ),
            )
        }
    }

    private fun search(searchProfile: FrozenSearchProfile, model: OpponentPolicy) = SearchTeacherSearchFactory.create(
        config = InformationSetSearchConfig(
            simulations = searchProfile.simulations,
            explorationConstant = searchProfile.explorationConstant,
            maxPolicyDecisions = searchProfile.maxPolicyDecisions,
            leaf = searchProfile.leaf,
        ),
        opponentPolicy = model,
    )

    private fun policyVersion(): String =
        "infoset-search-v2:${searchPlanner.name}:${profile.actionSpaceProfile.profileId}"

    private fun policy(kind: ArenaPolicyKind): OpponentPolicy = when (kind) {
        ArenaPolicyKind.UNIFORM_RANDOM -> UniformOpponentPolicy
        ArenaPolicyKind.FACE_BURN -> FaceBurnOpponentPolicy()
        ArenaPolicyKind.HOLD_BURN -> HoldBurnOpponentPolicy()
        ArenaPolicyKind.CONSERVATIVE_COMBAT -> ScoredPolicy(
            id = "conservative-combat-v2",
            scoreTable = "attack0.5-block8-pass4-other3",
        ) { choice ->
            when (choice.actionIntent.kind) {
                SemanticActionIntentKind.DECLARE_ATTACKERS,
                SemanticActionIntentKind.DECLINE_ATTACK -> 0.5
                SemanticActionIntentKind.DECLARE_BLOCKERS,
                SemanticActionIntentKind.DECLINE_BLOCK -> 8.0
                SemanticActionIntentKind.PASS_PRIORITY -> 4.0
                else -> 3.0
            }
        }
        ArenaPolicyKind.AGGRESSIVE_TRADE -> ScoredPolicy(
            id = "aggressive-trade-v2",
            scoreTable = "attack9-block7-pass0.5-other3",
        ) { choice ->
            when (choice.actionIntent.kind) {
                SemanticActionIntentKind.DECLARE_ATTACKERS,
                SemanticActionIntentKind.DECLINE_ATTACK -> 9.0
                SemanticActionIntentKind.DECLARE_BLOCKERS,
                SemanticActionIntentKind.DECLINE_BLOCK -> 7.0
                SemanticActionIntentKind.PASS_PRIORITY -> 0.5
                else -> 3.0
            }
        }
        ArenaPolicyKind.RANDOMIZED_HEURISTIC_20 -> org.mtgallium.agent.infoset.core.MixtureOpponentPolicy(
            id = "heuristic-randomized-20-v2",
            components = listOf(
                org.mtgallium.agent.infoset.core.OpponentPolicyMixtureEntry(SemanticHeuristicOpponentPolicy(), 0.8),
                org.mtgallium.agent.infoset.core.OpponentPolicyMixtureEntry(UniformOpponentPolicy, 0.2),
            ),
        )
        ArenaPolicyKind.SEARCH, ArenaPolicyKind.HEURISTIC -> error("$kind is selected by a dedicated path")
    }

    private data class Selection(
        val choice: SemanticChoice,
        val searchResult: InformationSetSearchResult?,
        val beliefDiagnostics: BeliefDiagnostics?,
        val latencyMillis: Double,
        val heuristicFallback: Boolean = false,
        val heuristicDiagnosis: ArgentumHeuristicChoiceDiagnosis? = null,
        val selectionKind: SearchTeacherSelectionKind? = null,
        val opponentPolicyDecision: OpponentPolicyDecisionDiagnostic? = null,
    )

    private fun heuristicSelection(
        world: ArgentumSearchWorld,
        actor: String,
        decisionIndex: Int,
        gameId: String,
    ): Selection {
        val diagnosis = world.determinizedHeuristicChoiceDiagnosis()
        diagnosis.choice?.let {
            return Selection(
                it,
                null,
                null,
                0.0,
                heuristicDiagnosis = diagnosis,
                opponentPolicyDecision = heuristicDecisionDiagnostic(replaced = false),
            )
        }
        val candidates = world.expandChoices().candidates
        val seed = ComponentSeeds.derive(gameId, decisionIndex, "heuristic-fallback")
        val distribution = SemanticHeuristicOpponentPolicy().distribution(
            world.informationState(actor),
            candidates,
            seed,
        )
        return Selection(
            sample(distribution, seed),
            null,
            null,
            0.0,
            heuristicFallback = true,
            heuristicDiagnosis = diagnosis,
            opponentPolicyDecision = heuristicDecisionDiagnostic(replaced = true),
        )
    }

    private fun heuristicDecisionDiagnostic(replaced: Boolean): OpponentPolicyDecisionDiagnostic =
        if (replaced) {
            OpponentPolicyDecisionDiagnostic(
                declaredPolicyId = "determinized-argentum-heuristic-v2",
                selectedComponentId = "determinized-argentum-heuristic-v2",
                effectivePolicyId = "semantic-argentum-heuristic-v2",
                replacement = OpponentPolicyReplacementDiagnostic(
                    triggerId = ARGENTUM_HEURISTIC_ANNOTATION_UNAVAILABLE_TRIGGER_V1,
                    replacementPolicyId = "semantic-argentum-heuristic-v2",
                    evidenceDisposition = OpponentPolicyReplacementEvidenceDisposition.INVALIDATES_EVIDENCE,
                ),
            )
        } else {
            OpponentPolicyDecisionDiagnostic(
                declaredPolicyId = "determinized-argentum-heuristic-v2",
                selectedComponentId = "determinized-argentum-heuristic-v2",
            )
        }

    companion object {
        const val MAX_GAME_DECISIONS = 2_048

        fun smokeProfile(): FrozenSearchProfile {
            val pilot = SearchTeacherPilotSpecification.frozenMonoRed()
            return FrozenSearchProfile(
                // The smoke/evaluation default is the exact versioned pilot configuration. It remains
                // unfrozen as evidence until the reduced pilot gate binds a clean calibration report.
                id = "fast-arena-v1",
                generatedAtUtc = "UNFROZEN-SMOKE",
                outerCommit = currentOuterCommit(),
                argentumCommit = currentArgentumCommit(),
                host = "i9-13900K",
                particles = pilot.particles,
                simulations = pilot.simulations,
                leaf = pilot.leaf,
                actionSpaceProfile = pilot.actionSpaceProfile,
                maxPolicyDecisions = pilot.maxPolicyDecisions,
                measuredP95Millis = 0.0,
                tacticalScore = 0.0,
                standardError = 0.0,
                calibrationReportHash = "UNFROZEN-SMOKE",
            )
        }
    }
}

internal fun inspectionScheduledExecutionSha256(
    protocolId: String,
    gameId: String,
    gameSeed: Long,
    randomizationUnitId: String,
): String = PolicyJson.sha256(
    listOf(protocolId, gameId, gameSeed.toString(), randomizationUnitId).joinToString("\u0000")
)

@Serializable
private data class InspectionGameConfigurationFingerprint(
    val schemaVersion: Int = 1,
    val gameId: String,
    val gameSeed: Long,
    val policyIdsByPlayer: Map<String, String>,
    val deckSha256ByPlayer: Map<String, String>,
    val startingPlayerIndex: Int = 0,
    val skipMulligans: Boolean = false,
    val useHandSmoother: Boolean = false,
)

internal fun inspectionGameConfigurationSha256(
    gameId: String,
    gameSeed: Long,
    policyIdsByPlayer: Map<String, String>,
    deckSha256ByPlayer: Map<String, String>,
): String = PolicyJson.digest(
    PolicyJson.format.encodeToJsonElement(
        InspectionGameConfigurationFingerprint.serializer(),
        InspectionGameConfigurationFingerprint(
            gameId = gameId,
            gameSeed = gameSeed,
            policyIdsByPlayer = policyIdsByPlayer.toSortedMap(),
            deckSha256ByPlayer = deckSha256ByPlayer.toSortedMap(),
        ),
    ),
)

@Serializable
private data class InspectionRuntimeIdentity(
    val schemaVersion: Int = 1,
    val hardwareIdentity: String,
    val jvmIdentity: String,
    val runtimeIdentity: String,
)

internal fun currentInspectionHardwareIdentity(): String =
    "${System.getProperty("os.name")}|${System.getProperty("os.arch")}|" +
        "processors=${Runtime.getRuntime().availableProcessors()}"

internal fun currentInspectionJvmIdentity(): String =
    "${System.getProperty("java.vendor")}|${System.getProperty("java.runtime.version")}|" +
        System.getProperty("java.vm.name")

internal fun currentInspectionRuntimeIdentity(): String = "kotlin-${KotlinVersion.CURRENT}"

internal fun inspectionRuntimeIdentitySha256(
    hardwareIdentity: String = currentInspectionHardwareIdentity(),
    jvmIdentity: String = currentInspectionJvmIdentity(),
    runtimeIdentity: String = currentInspectionRuntimeIdentity(),
): String = PolicyJson.digest(
    PolicyJson.format.encodeToJsonElement(
        InspectionRuntimeIdentity.serializer(),
        InspectionRuntimeIdentity(
            hardwareIdentity = hardwareIdentity,
            jvmIdentity = jvmIdentity,
            runtimeIdentity = runtimeIdentity,
        ),
    ),
)

private class ScoredPolicy(
    override val id: String,
    private val scoreTable: String,
    private val score: (SemanticChoice) -> Double,
) : OpponentPolicy {
    override val distributionIsSeedInvariant: Boolean = true
    override val behaviorSpecification: OpponentPolicyBehaviorSpecification =
        OpponentPolicyBehaviorSpecification(
            implementationId = "arena-typed-action-intent-score-table-v2",
            declaredId = id,
            distributionIsSeedInvariant = distributionIsSeedInvariant,
            parameters = mapOf(
                "actionIntentSchema" to SemanticActionIntent.SCHEMA_V1.toString(),
                "scoreTable" to scoreTable,
            ),
        )

    override fun distribution(
        opponentInformation: org.mtgallium.agent.infoset.core.PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> = ProbabilityDistribution.normalized(
        candidates.map { ProbabilityMass(it, score(it).coerceAtLeast(0.001)) }
    )
}

private fun Iterable<OpponentPolicyDecisionSummary>.combined(): OpponentPolicyDecisionSummary =
    fold(OpponentPolicyDecisionSummary(), OpponentPolicyDecisionSummary::plus)

internal fun sample(distribution: ProbabilityDistribution<SemanticChoice>, seed: Long): SemanticChoice {
    val target = ((ComponentSeeds.derive(seed, "distribution") ushr 11).toDouble() /
        (1L shl 53).toDouble())
    var cumulative = 0.0
    for (entry in distribution.entries) {
        cumulative += entry.probability
        if (target < cumulative) return entry.value
    }
    return distribution.entries.last().value
}

@Serializable
internal data class PrivilegedDebugLine(
    val schemaVersion: Int = 1,
    val gameId: String,
    val decisionIndex: Int,
    val chosenChoice: SemanticChoice?,
    val snapshot: ArgentumPrivilegedDebugSnapshot,
) {
    init { require(schemaVersion == 1) { "Unknown privileged debug schema $schemaVersion" } }
}

private class PrivilegedDebugWriter(path: Path, private val gameId: String) : Closeable {
    private val writer: BufferedWriter

    init {
        require(path.fileName.toString().endsWith(".privileged.jsonl.gz"))
        path.parent?.let(Files::createDirectories)
        writer = BufferedWriter(
            OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8)
        )
    }

    fun append(decisionIndex: Int, choice: SemanticChoice?, snapshot: ArgentumPrivilegedDebugSnapshot) {
        writer.write(
            PolicyJson.format.encodeToString(
                PrivilegedDebugLine(
                    gameId = gameId,
                    decisionIndex = decisionIndex,
                    chosenChoice = choice,
                    snapshot = snapshot,
                )
            )
        )
        writer.newLine()
    }

    override fun close() = writer.close()
}

/** Strategy-fusion ablation: search each determinization independently, then average root values. */
internal fun independentDeterminizationSearch(
    rootPlayer: String,
    belief: BeliefBatch<Weighted<org.mtgallium.agent.infoset.core.SearchWorld>>,
    searchSeed: Long,
    profile: FrozenSearchProfile,
    opponentPolicy: OpponentPolicy,
): InformationSetSearchResult {
    data class Aggregate(
        val choice: SemanticChoice,
        var visits: Int = 0,
        var weightedValue: Double = 0.0,
        var settlementCounts: org.mtgallium.agent.infoset.core.SearchSettlementCounts =
            org.mtgallium.agent.infoset.core.SearchSettlementCounts(),
    )

    val particleCount = belief.particles.size
    require(profile.simulations >= particleCount) {
        "Independent determinization needs at least one simulation per particle"
    }
    val base = profile.simulations / particleCount
    val remainder = profile.simulations % particleCount
    val results = belief.particles.mapIndexed { index, particle ->
        val simulations = base + if (index < remainder) 1 else 0
        val search = SearchTeacherSearchFactory.create(
            config = InformationSetSearchConfig(
                simulations = simulations,
                explorationConstant = profile.explorationConstant,
                maxPolicyDecisions = profile.maxPolicyDecisions,
                leaf = profile.leaf,
            ),
            opponentPolicy = opponentPolicy,
        )
        particle to search.search(
            rootPlayer,
            BeliefBatch(listOf(Weighted(particle.value, 1.0)), belief.diagnostics),
            ComponentSeeds.derive(searchSeed, index, "independent-determinization"),
        )
    }
    val aggregate = linkedMapOf<String, Aggregate>()
    results.forEach { (particle, result) ->
        result.candidates.forEach { candidate ->
            val value = aggregate.getOrPut(candidate.choice.signature) { Aggregate(candidate.choice) }
            value.visits += candidate.visits
            value.weightedValue += particle.weight * candidate.meanValue
            value.settlementCounts = value.settlementCounts.plus(result.settlementCountsFor(candidate.choice))
        }
    }
    val totalVisits = aggregate.values.sumOf { it.visits }.coerceAtLeast(1)
    val candidates = aggregate.values.sortedBy { it.choice.signature }.map { value ->
        SearchCandidateStatistics(
            choice = value.choice,
            visits = value.visits,
            meanValue = value.weightedValue,
            policyProbability = value.visits.toDouble() / totalVisits,
        )
    }
    val chosen = candidates.maxWith(
        compareBy<SearchCandidateStatistics> { it.meanValue }
            .thenBy { it.visits }
            .thenByDescending { it.choice.signature }
    )
    return InformationSetSearchResult(
        chosen = chosen.choice,
        rootValue = results.sumOf { (particle, result) -> particle.weight * result.rootValue },
        candidates = candidates,
        candidateSettlementCounts = aggregate.mapValues { (_, value) -> value.settlementCounts },
        diagnostics = InformationSetSearchDiagnostics(
            simulations = results.sumOf { it.second.diagnostics.simulations },
            particles = particleCount,
            nodes = results.sumOf { it.second.diagnostics.nodes },
            maximumDepth = results.maxOf { it.second.diagnostics.maximumDepth },
            exhaustiveNodes = results.sumOf { it.second.diagnostics.exhaustiveNodes },
            nonExhaustiveNodes = results.sumOf { it.second.diagnostics.nonExhaustiveNodes },
            wideningEvents = results.sumOf { it.second.diagnostics.wideningEvents },
            opponentModelId = opponentPolicy.id,
            leaf = profile.leaf,
        ),
    )
}

internal fun pairedArena(
    arena: SearchTeacherArena,
    profileId: String,
    opponent: ArenaPolicyKind,
    pairCount: Int,
    baseSeed: Long,
    workerThreads: Int = 1,
    checkpointRoot: Path? = null,
): PairedArenaReport {
    val shard = pairedArenaShard(
        arena = arena,
        profileId = profileId,
        opponent = opponent,
        pairOffset = 0,
        pairCount = pairCount,
        baseSeed = baseSeed,
        workerThreads = workerThreads,
        checkpointRoot = checkpointRoot,
    )
    return aggregatePairedArenaShards(profileId, arena.runIdentity, opponent, pairCount, baseSeed, listOf(shard))
}

internal fun pairedArenaShard(
    arena: SearchTeacherArena,
    profileId: String,
    opponent: ArenaPolicyKind,
    pairOffset: Int,
    pairCount: Int,
    baseSeed: Long,
    workerThreads: Int = 1,
    checkpointRoot: Path? = null,
): PairedArenaShard {
    require(opponent != ArenaPolicyKind.SEARCH)
    require(pairOffset >= 0)
    require(pairCount > 0)
    require(workerThreads > 0)
    val pairRuns = parallelMapOrdered(pairCount, workerThreads) { pairIndex ->
        val absolutePairIndex = pairOffset + pairIndex
        val checkpoint = checkpointRoot?.let {
            pairedArenaCheckpointPath(it, profileId, arena.runIdentity, opponent, baseSeed, absolutePairIndex)
        }
        val cached = checkpoint?.takeIf(Files::exists)?.let {
            loadPairedArenaCheckpoint(it, profileId, arena.runIdentity, opponent, baseSeed, absolutePairIndex)
        }
        if (cached != null) {
            println("Arena resumed pair $absolutePairIndex")
            cached
        } else {
            val seed = ComponentSeeds.derive(baseSeed, absolutePairIndex, "paired-library-orders")
            val games = listOf(
                arena.play("pair-$absolutePairIndex-a", seed, ArenaPolicyKind.SEARCH, opponent),
                arena.play("pair-$absolutePairIndex-b", seed, opponent, ArenaPolicyKind.SEARCH),
            )
            checkpoint?.let {
                writeJsonAtomically(
                    it,
                    PairedArenaPairCheckpoint(
                        outerCommit = currentOuterCommit(),
                        argentumCommit = currentArgentumCommit(),
                        profileId = profileId,
                        runIdentity = arena.runIdentity,
                        opponent = opponent,
                        baseSeed = baseSeed,
                        pairIndex = absolutePairIndex,
                        games = games,
                    ),
                )
            }
            games
        }
    }
    return PairedArenaShard(
        generatedAtUtc = Instant.now().toString(),
        outerCommit = currentOuterCommit(),
        argentumCommit = currentArgentumCommit(),
        profileId = profileId,
        runIdentity = arena.runIdentity,
        opponent = opponent,
        baseSeed = baseSeed,
        pairOffset = pairOffset,
        pairCount = pairCount,
        workerThreads = workerThreads,
        pairIndexes = (pairOffset until pairOffset + pairCount).toList(),
        games = pairRuns.flatten(),
    )
}

internal fun aggregatePairedArenaShards(
    profileId: String,
    runIdentity: String,
    opponent: ArenaPolicyKind,
    expectedPairCount: Int,
    baseSeed: Long,
    shards: List<PairedArenaShard>,
): PairedArenaReport {
    require(expectedPairCount > 0)
    require(shards.isNotEmpty())
    val pairsByIndex = linkedMapOf<Int, List<GameRunResult>>()
    shards.sortedBy(PairedArenaShard::pairOffset).forEach { shard ->
        require(shard.profileId == profileId) { "Shard profile mismatch: ${shard.profileId}" }
        require(shard.outerCommit == currentOuterCommit()) { "Shard outer-commit mismatch: ${shard.outerCommit}" }
        require(shard.argentumCommit == currentArgentumCommit()) {
            "Shard Argentum-commit mismatch: ${shard.argentumCommit}"
        }
        require(shard.runIdentity == runIdentity) { "Shard run-identity mismatch: ${shard.runIdentity}" }
        require(shard.opponent == opponent) { "Shard opponent mismatch: ${shard.opponent}" }
        require(shard.baseSeed == baseSeed) { "Shard base-seed mismatch: ${shard.baseSeed}" }
        shard.pairIndexes.zip(shard.games.chunked(2)).forEach { (pairIndex, games) ->
            require(pairsByIndex.put(pairIndex, games) == null) { "Duplicate arena pair $pairIndex" }
        }
    }
    val expectedIndexes = (0 until expectedPairCount).toList()
    require(pairsByIndex.keys.toList() == expectedIndexes) {
        val missing = expectedIndexes.filterNot(pairsByIndex::containsKey).take(20)
        "Arena shards do not exactly cover 0 until $expectedPairCount; missing=$missing, actual=${pairsByIndex.keys}"
    }
    val pairRuns = expectedIndexes.map { requireNotNull(pairsByIndex[it]) }
    val games = pairRuns.flatten()
    fun GameRunResult.isEvidenceComplete(): Boolean =
        disposition == GameRunDisposition.GAME_ENDED && terminal && evidenceStop == null && searchScore != null
    val completePairs = pairRuns.filter { pair ->
        pair.size == 2 && pair.all { it.isEvidenceComplete() }
    }
    val pairImprovements = completePairs.map { pair -> pair.map { requireNotNull(it.searchScore) }.average() - 0.5 }
    val (lower, upper) = if (pairImprovements.isEmpty()) -1.0 to 1.0 else {
        bootstrapInterval(pairImprovements, baseSeed, samples = 10_000)
    }
    fun stratum(seat: String): Double = games.filter { it.searchSeat == seat && it.isEvidenceComplete() }
        .mapNotNull { it.searchScore }
        .takeIf { it.isNotEmpty() }?.average()?.minus(0.5) ?: -1.0
    val play = stratum("p0")
    val draw = stratum("p1")
    val improvement = pairImprovements.takeIf { it.isNotEmpty() }?.average() ?: -1.0
    val failures = buildList {
        if (games.size != expectedPairCount * 2) {
            add("expected ${expectedPairCount * 2} games, recorded ${games.size}")
        }
        if (games.any { !it.terminal }) add("non-terminal games present")
        if (games.any {
                it.disposition != GameRunDisposition.GAME_ENDED || it.evidenceStop != null
            }
        ) {
            add("stopped or legacy-unclassified games present")
        }
        if (games.sumOf { it.illegalResponses } > 0) add("illegal responses present")
        if (games.sumOf { it.fallbacks } > 0) add("search fallbacks present")
        if (games.any { it.exception != null }) add("uncaught game exceptions present")
        if (games.any { it.stepLimit }) add("step-limit games present")
        if (improvement < 0.02) add("point improvement is below 0.02")
        if (lower <= 0.0) add("paired 95% confidence lower bound is not above zero")
        if (play < 0.0) add("play stratum point estimate is negative")
        if (draw < 0.0) add("draw stratum point estimate is negative")
    }
    return PairedArenaReport(
        generatedAtUtc = Instant.now().toString(),
        outerCommit = currentOuterCommit(),
        argentumCommit = currentArgentumCommit(),
        profileId = profileId,
        runIdentity = runIdentity,
        opponent = opponent,
        pairCount = expectedPairCount,
        completePairs = completePairs.size,
        gameCount = games.size,
        completeGames = games.count { it.terminal },
        pointImprovement = improvement,
        confidenceLower = lower,
        confidenceUpper = upper,
        playPointImprovement = play,
        drawPointImprovement = draw,
        illegalResponses = games.sumOf { it.illegalResponses },
        fallbacks = games.sumOf { it.fallbacks },
        exceptions = games.count { it.exception != null },
        deadlocksOrStepLimits = games.count { it.stepLimit },
        workerThreads = shards.maxOf(PairedArenaShard::workerThreads),
        primaryGatePassed = failures.isEmpty(),
        failureReasons = failures,
        games = games,
    )
}

private fun pairedArenaCheckpointPath(
    checkpointRoot: Path,
    profileId: String,
    runIdentity: String,
    opponent: ArenaPolicyKind,
    baseSeed: Long,
    pairIndex: Int,
): Path {
    val runKey = sha256("$profileId:$runIdentity:${opponent.name}:$baseSeed").take(20)
    return checkpointRoot.resolve(runKey).resolve("pair-$pairIndex.json")
}

private fun loadPairedArenaCheckpoint(
    path: Path,
    profileId: String,
    runIdentity: String,
    opponent: ArenaPolicyKind,
    baseSeed: Long,
    pairIndex: Int,
): List<GameRunResult>? = runCatching {
    val checkpoint = evidenceJson.decodeFromString<PairedArenaPairCheckpoint>(Files.readString(path))
    checkpoint.takeIf {
        it.profileId == profileId &&
            it.outerCommit == currentOuterCommit() &&
            it.argentumCommit == currentArgentumCommit() &&
            it.runIdentity == runIdentity &&
            it.opponent == opponent &&
            it.baseSeed == baseSeed &&
            it.pairIndex == pairIndex &&
            it.games.map(GameRunResult::gameId) == listOf("pair-$pairIndex-a", "pair-$pairIndex-b")
    }?.games
}.getOrNull()

internal fun <T> parallelMapOrdered(count: Int, threads: Int, block: (Int) -> T): List<T> {
    require(count >= 0)
    require(threads > 0)
    if (threads == 1) return List(count, block)
    val executor = Executors.newFixedThreadPool(threads)
    return try {
        executor.invokeAll(List(count) { index -> Callable { index to block(index) } })
            .map { it.get() }
            .sortedBy { it.first }
            .map { it.second }
    } finally {
        executor.shutdown()
    }
}

internal fun bootstrapInterval(values: List<Double>, seed: Long, samples: Int): Pair<Double, Double> {
    require(values.isNotEmpty())
    val random = java.util.Random(seed)
    val means = DoubleArray(samples) {
        var sum = 0.0
        repeat(values.size) { sum += values[random.nextInt(values.size)] }
        sum / values.size
    }.sorted()
    return means[(samples * 0.025).toInt()] to means[(samples * 0.975).toInt().coerceAtMost(samples - 1)]
}
