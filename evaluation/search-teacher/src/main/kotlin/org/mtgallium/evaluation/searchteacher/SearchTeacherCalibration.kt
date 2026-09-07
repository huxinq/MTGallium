package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import org.mtgallium.agent.searchteacher.ConfiguredMonoRedInformationEvaluator
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluator
import org.mtgallium.agent.searchteacher.MonoRedTacticalEvaluatorSettings
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.MixtureOpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicyBehaviorSpecification
import org.mtgallium.agent.infoset.core.OpponentPolicyMixtureEntry
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.SemanticHeuristicOpponentPolicy
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.PolicyBehaviorBinding
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.RolloutTurnHorizon
import org.mtgallium.agent.searchteacher.PolicySingletonSelectionConfig
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherRuntimeConfig
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints
import org.mtgallium.research.run.ResearchRunFiles

internal const val SEARCH_TEACHER_CALIBRATION_PROTOCOL = "search-teacher-calibration-v1"
private const val CALIBRATION_CHECKPOINT_SCHEMA = "search-teacher-calibration-game-v1"
private const val CALIBRATION_SCHEDULE = "search-teacher-calibration-library-orders-v1"

@Serializable
internal enum class SearchTeacherCalibrationPhase { PREFLIGHT, DEVELOPMENT, CONFIRMATION }

/** Existing rollout implementations exposed to plan configuration; this is not a plugin registry. */
@Serializable
internal enum class SearchTeacherCalibrationRolloutPolicy {
    PRODUCTION_ARGENTUM,
    SEMANTIC_HEURISTIC,
    UNIFORM,
}

/** Budget/rollout interventions are explicit; absent evaluator configuration preserves the historical production evaluator. */
@Serializable
internal data class SearchTeacherCalibrationPolicy(
    val id: String,
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val explorationConstant: Double,
    val singletonSelection: Boolean,
    val rolloutHeuristicProbability: Double,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val evaluator: MonoRedVisibleEvaluatorConfig? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rootRolloutPolicy: SearchTeacherCalibrationRolloutPolicy? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val opponentRolloutPolicy: SearchTeacherCalibrationRolloutPolicy? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rolloutTurnHorizon: RolloutTurnHorizon? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rootCloningFit: CloningFitReference? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tacticalEvaluator: MonoRedTacticalEvaluatorSettings? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rootKernelRolloutFit: RootKernelFitReference? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fastRootKernelRolloutFit: RootKernelFitReference? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fastOpponentKernelRolloutFit: RootKernelFitReference? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val attackRootKernelRolloutFit: RootKernelFitReference? = null,
    /** Direct original Argentum policy; the search fields are inactive in this mode. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val directArgentumHeuristic: Boolean = false,
) {
    init {
        require(!directArgentumHeuristic || (evaluator == null && tacticalEvaluator == null &&
            rootRolloutPolicy == null && opponentRolloutPolicy == null && rolloutTurnHorizon == null &&
            rootCloningFit == null && rootKernelRolloutFit == null && fastRootKernelRolloutFit == null &&
            fastOpponentKernelRolloutFit == null && attackRootKernelRolloutFit == null)) {
            "Direct Argentum heuristic cannot carry inactive evaluator or learned rollout interventions"
        }
        require(attackRootKernelRolloutFit == null || fastRootKernelRolloutFit != null) {
            "Attack root rollout requires the frozen fast casting continuation"
        }
        require(evaluator == null || tacticalEvaluator == null) { "Only one evaluator may be configured" }
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*")))
        require(particles > 0 && simulations > 0 && maxPolicyDecisions > 0)
        require(explorationConstant.isFinite() && explorationConstant >= 0)
        require(rolloutHeuristicProbability.isFinite() && rolloutHeuristicProbability > 0 && rolloutHeuristicProbability <= 1)
        require(rootCloningFit == null || (rootRolloutPolicy == null && rolloutHeuristicProbability == 1.0)) {
            "A learned root rollout must not silently override another root-policy configuration"
        }
        require(fastRootKernelRolloutFit == null ||
            (rootKernelRolloutFit == null && rootCloningFit == null && rootRolloutPolicy == null &&
                rolloutHeuristicProbability == 1.0)) {
            "A fast kernel root rollout must not override another root-policy configuration"
        }
        require(fastOpponentKernelRolloutFit == null ||
            (opponentRolloutPolicy == null && rolloutHeuristicProbability == 1.0)) {
            "A fast kernel opponent rollout must not override another opponent-policy configuration"
        }
        require(rootKernelRolloutFit == null ||
            (rootCloningFit == null && rootRolloutPolicy == null && rolloutHeuristicProbability == 1.0)) {
            "A kernel root rollout must not silently override another root-policy configuration"
        }
    }

    fun parameters(baseSeed: Long): SearchTeacherPolicyParameters = SearchTeacherRuntimeConfig().policyParameters().copy(
        baseSeed = baseSeed, particles = particles, simulations = simulations,
        maxPolicyDecisions = maxPolicyDecisions, explorationConstant = explorationConstant,
        singletonSelection = PolicySingletonSelectionConfig(enabled = singletonSelection),
        rolloutTurnHorizon = rolloutTurnHorizon,
        leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT,
            if (tacticalEvaluator == null) LeafEvaluator.MTGALLIUM_VISIBLE_V2 else LeafEvaluator.MTGALLIUM_TACTICAL_V3),
    )

    fun policy(baseSeed: Long) = if (directArgentumHeuristic) ArenaPolicySpec(id, ArenaPolicyKind.HEURISTIC) else ArenaPolicySpec(id, ArenaPolicyKind.SEARCH, parameters = parameters(baseSeed),
        informationEvaluator = tacticalEvaluator?.let(::MonoRedTacticalEvaluator) ?: evaluator?.let(::ConfiguredMonoRedInformationEvaluator),
        rootRolloutPolicy = fastRootKernelRolloutFit?.loadFastRolloutPolicy()?.let { incumbent ->
            attackRootKernelRolloutFit?.loadAttackRolloutPolicy(incumbent) ?: incumbent
        } ?: rootKernelRolloutFit?.loadRootRolloutPolicy() ?: rootCloningFit?.let {
            it.load()
        } ?: configuredRolloutPolicy(
            "root", rootRolloutPolicy, SearchTeacherSearchFactory.rootRolloutPolicy(),
        ),
        opponentRolloutPolicy = fastOpponentKernelRolloutFit?.loadFastRolloutPolicy() ?: configuredRolloutPolicy(
            "opponent", opponentRolloutPolicy, SearchTeacherSearchFactory.opponentRolloutPolicy(),
        ))

    private fun configuredRolloutPolicy(
        role: String,
        configured: SearchTeacherCalibrationRolloutPolicy?,
        production: OpponentPolicy,
    ): OpponentPolicy? = configured?.let { selected ->
        when (selected) {
            SearchTeacherCalibrationRolloutPolicy.PRODUCTION_ARGENTUM -> production
            SearchTeacherCalibrationRolloutPolicy.SEMANTIC_HEURISTIC -> SemanticHeuristicOpponentPolicy()
            SearchTeacherCalibrationRolloutPolicy.UNIFORM -> UniformOpponentPolicy
        }
    } ?: mixture(role, production)

    private fun mixture(role: String, heuristic: OpponentPolicy): OpponentPolicy? =
        if (rolloutHeuristicProbability == 1.0) null else MixtureOpponentPolicy(
            "$role-calibration-heuristic-uniform-v1", listOf(
                OpponentPolicyMixtureEntry(heuristic, rolloutHeuristicProbability),
                OpponentPolicyMixtureEntry(UniformOpponentPolicy, 1.0 - rolloutHeuristicProbability)))
}

@Serializable
internal data class SearchTeacherCalibrationPlan(
    val schemaVersion: Int = 1,
    val phase: SearchTeacherCalibrationPhase,
    val baseSeed: Long,
    val pairOffset: Int,
    val pairCount: Int,
    val control: SearchTeacherCalibrationPolicy,
    val candidates: List<SearchTeacherCalibrationPolicy>,
) {
    init {
        require(schemaVersion == 1)
        require(pairOffset >= 0 && pairCount > 0 && pairOffset.toLong() + pairCount <= Int.MAX_VALUE)
        require(candidates.isNotEmpty())
        require(!control.directArgentumHeuristic || candidates.none { it.directArgentumHeuristic })
        require((candidates.map { it.id } + control.id).distinct().size == candidates.size + 1)
    }

    fun pairSeed(pairIndex: Int): Long {
        require(pairIndex in pairOffset until pairOffset + pairCount)
        return calibrationPairSeed(baseSeed, pairIndex)
    }
}

internal fun calibrationPairSeed(baseSeed: Long, pairIndex: Int): Long =
    ComponentSeeds.derive(baseSeed, pairIndex, CALIBRATION_SCHEDULE)

internal fun searchTeacherCalibrationBindings(
    plan: SearchTeacherCalibrationPlan, source: PolicySourceProvenance,
    policyIdentities: Map<String, String>, deckHash: String, cardPoolHash: String, workerThreads: Int,
    sequentialRule: PairedSequentialRule? = null,
) = retainedCalibrationBindings(evidenceJson.encodeToString(plan), source, policyIdentities,
    deckHash, cardPoolHash, workerThreads, sequentialRule?.let { evidenceJson.encodeToString(it) })

/** Hash the complete retained configuration, including opaque opponent-only interventions. */
internal fun retainedCalibrationBindings(
    planJson: String, source: PolicySourceProvenance,
    policyIdentities: Map<String, String>, deckHash: String, cardPoolHash: String, workerThreads: Int,
    sequentialRuleJson: String?,
) = ResearchRunBindings(protocol = SEARCH_TEACHER_CALIBRATION_PROTOCOL, material = mapOf(
    "plan" to sha256(planJson),
    "source-provenance" to sha256(evidenceJson.encodeToString(source)),
    "policy-evidence" to sha256(evidenceJson.encodeToString<Map<String, String>>(policyIdentities.toSortedMap())),
    "deck" to deckHash, "card-pool" to cardPoolHash, "schedule" to CALIBRATION_SCHEDULE,
    // Timing is an outcome here; worker count cannot change across resumed attempts.
    "worker-threads" to workerThreads.toString(),
) + (sequentialRuleJson?.let { mapOf("sequential-rule" to sha256(it)) } ?: emptyMap()))

@Serializable
internal data class SearchTeacherCalibrationPolicyReport(
    val descriptor: SearchTeacherCalibrationPolicy,
    val policy: TournamentPolicyDescription,
    val search: InformationSetSearchConfig?,
    val binding: PolicyBehaviorBinding,
    val rootRolloutPolicy: OpponentPolicyBehaviorSpecification?,
    val opponentRolloutPolicy: OpponentPolicyBehaviorSpecification?,
)

@Serializable
internal data class SearchTeacherCalibrationCost(
    val search: SearchBudgetFrontierOperationalSummary,
    val selections: Int,
    val selectionCounts: Map<SearchTeacherSelectionKind, Int>,
    /** Search latency divided by ALL selections: excludes unmeasured non-search selection time. */
    val searchedMillisPerSelection: Double?,
    val searchedMillisPerGame: Double?,
    /** Both seats and host overhead; this is not an estimate of this policy's isolated game cost. */
    val sharedWholeGameMeanMillis: Double?,
)

@Serializable
internal data class SearchTeacherCalibrationComparison(
    val candidateId: String,
    val assignedPairs: Int,
    val pairs: List<SearchBudgetFrontierPair>,
    val validPairs: Int,
    val validGames: Int,
    val invalidPairs: Int,
    val incompletePairs: Int,
    val candidateBySeat: List<SearchBudgetFrontierSeatSummary>,
    val candidatePointRate: Double?,
    val pairedBootstrap95Lower: Double?,
    val pairedBootstrap95Upper: Double?,
    val operationalByPolicy: List<SearchTeacherCalibrationCost>,
)

@Serializable
internal data class SearchTeacherCalibrationReport(
    val schemaVersion: Int = 1,
    val protocol: String = SEARCH_TEACHER_CALIBRATION_PROTOCOL,
    val runIdentity: String,
    val generatedAtUtc: String,
    val sourceProvenance: PolicySourceProvenance,
    val deckHash: String,
    val cardPoolHash: String,
    val plan: SearchTeacherCalibrationPlan,
    val workerThreads: Int,
    /** Elapsed time of this invocation, excluding any prior resumed attempts. */
    val currentAttemptElapsedMillis: Double,
    val policies: List<SearchTeacherCalibrationPolicyReport>,
    val comparisons: List<SearchTeacherCalibrationComparison>,
    val valid: Boolean,
    val limitations: List<String> = listOf(
        "Strength includes only complete valid seat-swapped pairs; operational counters include all attempted games.",
        "Candidates share library seeds and each plays the control; these are not candidate-versus-candidate games.",
        "Development selection is exploratory. Confirmation requires a separately frozen plan and disjoint explicit pair offset.",
        "Paired bootstrap intervals are descriptive and unadjusted for multiple candidates; no automatic promotion is made.",
        "Search latency omits non-search selection cost. Whole-game elapsed includes both policies and host overhead.",
        "Valid games require a verified private canonical replay, one search-seat-safe trajectory and planner sidecar; stopped or failed games retain only emitted artifacts.",
    ),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sequentialRule: PairedSequentialRule? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sequentialResult: PairedSequentialResult? = null,
    /** Completed work after the first stopping prefix; excluded from comparison strength summaries. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sequentialOvershootPairs: List<SearchBudgetFrontierPair>? = null,
    /** All attempted pairs, including post-stop work; distinct from the first-prefix validity above. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sequentialOperationalValid: Boolean? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sequentialPopulation: SearchTeacherSequentialPopulation? = null,
)

@Serializable
internal data class SearchTeacherCalibrationCheckpoint(
    val pairIndex: Int, val leg: Int, val game: GameRunResult, val artifactSha256: Map<String, String>,
)

/** An existing corrupt or mismatched checkpoint is an error, never permission to silently rerun a game. */
internal fun loadSearchTeacherCalibrationCheckpoint(
    path: Path, directory: Path, identity: String, pairIndex: Int, leg: Int, seed: Long,
    p0PolicyId: String, p1PolicyId: String, gameId: String,
): GameRunResult? {
    if (!Files.exists(path)) return null
    val envelope = ResearchRunCheckpoints.load(path)
    require(envelope.researchRunIdentity == identity && envelope.payloadSchema == CALIBRATION_CHECKPOINT_SCHEMA)
    require(envelope.sequence == leg.toLong())
    val checkpoint = evidenceJson.decodeFromString<SearchTeacherCalibrationCheckpoint>(envelope.payload().decodeToString())
    require(checkpoint.pairIndex == pairIndex && checkpoint.leg == leg)
    require(checkpoint.game.seed == seed && checkpoint.game.gameId == gameId)
    require(checkpoint.game.p0PolicyId == p0PolicyId && checkpoint.game.p1PolicyId == p1PolicyId)
    require(checkpoint.artifactSha256 == calibrationArtifactHashes(directory, checkpoint.game)) {
        "Changed checkpoint artifact population or content: $gameId"
    }
    return checkpoint.game
}

internal fun calibrationEvidencePerspective(p0: ArenaPolicyKind, p1: ArenaPolicyKind): String {
    require(p0 == ArenaPolicyKind.SEARCH || p1 == ArenaPolicyKind.SEARCH) {
        "Calibration evidence requires at least one search policy"
    }
    return if (p0 == ArenaPolicyKind.SEARCH) "p0" else "p1"
}

private fun calibrationGameArtifacts(gameId: String, perspective: String) = listOf(
    "replays/$gameId.privileged.replay.jsonl.gz", "public/$gameId.$perspective.jsonl.gz",
    "public/planner/$gameId.$perspective.planner.json.gz",
)

/** Failed games may end before sidecar emission. Present files must remain ordinary, hash-bound artifacts. */
internal fun calibrationArtifactHashes(directory: Path, game: GameRunResult): Map<String, String> {
    val expected = calibrationGameArtifacts(game.gameId, calibrationEvidencePerspective(game.p0Policy, game.p1Policy))
    val emitted = expected.mapNotNull { relative ->
        val path = ResearchRunFiles.resolveBelow(directory, relative)
        if (!Files.exists(path)) null else {
            require(Files.isRegularFile(path)) { "Calibration artifact is not a regular file: $relative" }
            relative to sha256File(path)
        }
    }.toMap()
    if (searchBudgetFrontierInvalidationReasons(game).isEmpty()) {
        require(emitted.keys == expected.toSet()) {
            "Otherwise valid game ${game.gameId} is missing required artifacts: ${expected - emitted.keys}"
        }
    }
    return emitted
}

/** Legacy arena presentation only; both actual seats always receive explicit policy parameters. */
internal fun calibrationPresentationProfile(source: PolicySourceProvenance): FrozenSearchProfile {
    val baseline = SearchTeacherRuntimeConfig().policyParameters()
    return FrozenSearchProfile(id = "fast-arena-v1", generatedAtUtc = "UNFROZEN-CALIBRATION",
        outerCommit = source.outer.revision, argentumCommit = source.argentum.revision,
        host = System.getProperty("os.name"), particles = baseline.particles, simulations = baseline.simulations,
        leaf = baseline.leaf, actionSpaceProfile = baseline.actionSpaceProfile,
        maxPolicyDecisions = baseline.maxPolicyDecisions, explorationConstant = baseline.explorationConstant,
        measuredP95Millis = 0.0, tacticalScore = 0.0, standardError = 0.0,
        calibrationReportHash = "UNFROZEN-CALIBRATION")
}

internal class SearchTeacherCalibrationRunner(
    private val root: Path, private val registry: CardRegistry, private val manifest: DeckManifest,
) {
    @JvmOverloads
    fun run(plan: SearchTeacherCalibrationPlan, output: Path, workerThreads: Int,
        sequentialRule: PairedSequentialRule? = null, publishProgress: Boolean = true): SearchTeacherCalibrationReport {
        require(workerThreads > 0)
        sequentialRule?.let { SearchTeacherSequentialPlan(plan, it) }
        val started = System.nanoTime()
        val sourceRun = RunProvenance.capture(root).also { it.requireReady() }
        require(!sourceRun.outerDirty && !sourceRun.engineDirty) { "Calibration requires committed clean treatment and engine source" }
        val source = requireNotNull(sourceRun.sourceProvenance)
        val directory = EvidenceStore(root).requireDiagnosticOutput(output, "Search Teacher calibration evidence")
        val arena = SearchTeacherArena(registry, manifest, calibrationPresentationProfile(source), plan.baseSeed)
        val descriptors = listOf(plan.control) + plan.candidates
        val policies = descriptors.associate { it.id to it.policy(plan.baseSeed) }
        val bindings = policies.mapValues { arena.evidenceBinding(it.value, null, source) }
        val identity = searchTeacherCalibrationBindings(plan, source, bindings.mapValues { it.value.identity },
            manifest.deckHash(), manifest.cardPoolHash(), workerThreads, sequentialRule).identity
        if (Files.exists(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE))) {
            ResearchRunArtifacts.loadAndVerify(directory, identity)
            return readEvidenceJson(directory.resolve("report.json"), SearchTeacherCalibrationReport.serializer())
                .also { require(it.runIdentity == identity && it.plan == plan && it.sequentialRule == sequentialRule) }
        }
        val planPath = directory.resolve("plan.json")
        if (Files.exists(planPath)) require(evidenceJson.decodeFromString<SearchTeacherCalibrationPlan>(Files.readString(planPath)) == plan)
        else writeJsonAtomically(planPath, plan)
        if (sequentialRule != null) {
            val sequentialPath = directory.resolve("sequential-plan.json")
            val sequentialPlan = SearchTeacherSequentialPlan(plan, sequentialRule)
            if (Files.exists(sequentialPath)) require(evidenceJson.decodeFromString<SearchTeacherSequentialPlan>(
                Files.readString(sequentialPath)) == sequentialPlan)
            else writeJsonAtomically(sequentialPath, sequentialPlan)
        }
        val completed = AtomicInteger(0)
        val total = Math.multiplyExact(plan.candidates.size, plan.pairCount)
        val progressPath = if (publishProgress) System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of) else null
        publishDurableRunProgress(progressPath, 0, total, "calibration ${plan.phase}", "preparing paired gameplay")
        fun playTask(task: Int): Pair<String, SearchBudgetFrontierPair> {
            val candidate = plan.candidates[task / plan.pairCount]
            val pairIndex = plan.pairOffset + task % plan.pairCount
            val seed = plan.pairSeed(pairIndex)
            val games = (0..1).map { leg ->
                val p0 = policies.getValue(if (leg == 0) plan.control.id else candidate.id)
                val p1 = policies.getValue(if (leg == 0) candidate.id else plan.control.id)
                val gameId = "${candidate.id}-pair-$pairIndex-leg-$leg"
                val checkpointPath = directory.resolve("checkpoints/$gameId.json")
                loadSearchTeacherCalibrationCheckpoint(checkpointPath, directory, identity, pairIndex, leg, seed,
                    p0.id, p1.id, gameId) ?: run {
                    val perspective = calibrationEvidencePerspective(p0.kind, p1.kind)
                    val paths = calibrationGameArtifacts(gameId, perspective).map { ResearchRunFiles.resolveBelow(directory, it) }
                    val game = arena.playWithPolicies(gameId, seed, p0, p1,
                        evidence = GameEvidenceOptions(publicTrajectory = paths[1], plannerEvidence = paths[2],
                            publicTrajectoryPerspective = perspective, publicTrajectoryReference = root.relativize(paths[1]).toString(),
                            researchRunIdentity = identity, outerCommit = sourceRun.outerCommit,
                            argentumCommit = sourceRun.checkedOutArgentumCommit,
                            profileHash = sha256(evidenceJson.encodeToString(plan)), sourceProvenance = source),
                        replay = GameReplayOptions(paths[0], root.relativize(paths[0]).toString(), identity,
                            sourceRun.outerCommit, sourceRun.checkedOutArgentumCommit))
                    val checkpoint = SearchTeacherCalibrationCheckpoint(pairIndex, leg, game,
                        calibrationArtifactHashes(directory, game))
                    ResearchRunCheckpoints.persist(checkpointPath, identity, CALIBRATION_CHECKPOINT_SCHEMA, leg.toLong(),
                        evidenceJson.encodeToString(checkpoint).encodeToByteArray())
                    game
                }
            }
            publishDurableRunProgress(progressPath, completed.incrementAndGet(), total, "calibration ${plan.phase}",
                "${candidate.id} pair $pairIndex complete")
            return candidate.id to searchBudgetFrontierPair(pairIndex, seed, games, candidate.id)
        }
        val sequential = sequentialRule?.let { rule ->
            executePairedSequentialSchedule(rule, plan.pairOffset, workerThreads) { pairIndex ->
                playTask(pairIndex - plan.pairOffset).second
            }
        }
        val pairs = sequential?.pairs?.map { plan.candidates.single().id to it }
            ?: parallelMapOrdered(total, workerThreads, ::playTask)
        val comparisonPairs = sequential?.let { pairs.take(it.result.inspectedPairs) } ?: pairs
        val comparisons = plan.candidates.map { candidate ->
            calibrationComparison(plan, candidate, comparisonPairs.filter { it.first == candidate.id }.map { it.second },
                operationalGames = pairs.filter { it.first == candidate.id }.flatMap { it.second.games },
                assignedPairs = sequential?.result?.inspectedPairs ?: plan.pairCount)
        }
        val report = SearchTeacherCalibrationReport(runIdentity = identity, generatedAtUtc = Instant.now().toString(),
            sourceProvenance = source, deckHash = manifest.deckHash(), cardPoolHash = manifest.cardPoolHash(),
            plan = plan, workerThreads = workerThreads, currentAttemptElapsedMillis = (System.nanoTime() - started) / 1_000_000.0,
            policies = descriptors.map { SearchTeacherCalibrationPolicyReport(it, describeTournamentPolicy(policies.getValue(it.id)),
                it.takeUnless { it.directArgentumHeuristic }?.parameters(plan.baseSeed)?.searchConfig(), bindings.getValue(it.id),
                policies.getValue(it.id).takeUnless { it.kind == ArenaPolicyKind.HEURISTIC }?.effectiveRootRolloutPolicy()?.behaviorSpecification,
                policies.getValue(it.id).takeUnless { it.kind == ArenaPolicyKind.HEURISTIC }?.effectiveOpponentRolloutPolicy()?.behaviorSpecification) }, comparisons = comparisons,
            valid = if (sequential == null) comparisons.all { it.validPairs == plan.pairCount }
                else sequential.valid,
            sequentialRule = sequentialRule, sequentialResult = sequential?.result,
            sequentialOvershootPairs = sequential?.let { it.pairs.drop(it.result.inspectedPairs) },
            sequentialOperationalValid = sequential?.operationalValid, sequentialPopulation = sequential?.population)
        writeEvidenceJsonStream(directory.resolve("report.json"), report, SearchTeacherCalibrationReport.serializer())
        writeTextAtomically(directory.resolve("report.md"), renderSearchTeacherCalibration(report))
        ResearchRunArtifacts(directory, identity).also { artifacts ->
            listOf("plan.json", "report.json", "report.md").forEach(artifacts::register)
            if (sequentialRule != null) artifacts.register("sequential-plan.json")
            pairs.flatMap { it.second.games }.forEach { game ->
                artifacts.register("checkpoints/${game.gameId}.json")
                val checkpoint = evidenceJson.decodeFromString<SearchTeacherCalibrationCheckpoint>(
                    ResearchRunCheckpoints.load(directory.resolve("checkpoints/${game.gameId}.json")).payload().decodeToString())
                require(checkpoint.artifactSha256 == calibrationArtifactHashes(directory, game))
                checkpoint.artifactSha256.forEach { (relative, hash) ->
                    require(artifacts.register(relative).sha256 == hash)
                }
            }
            artifacts.finalize()
        }
        return report
    }
}

internal fun calibrationComparison(plan: SearchTeacherCalibrationPlan, candidate: SearchTeacherCalibrationPolicy,
    pairs: List<SearchBudgetFrontierPair>, operationalGames: List<GameRunResult> = pairs.flatMap { it.games },
    assignedPairs: Int = plan.pairCount): SearchTeacherCalibrationComparison {
    val valid = pairs.filter { it.valid }
    val scores = valid.map { TournamentPairIndexScore(it.pairIndex, requireNotNull(it.treatmentPoints) / 2) }
    val interval = scores.takeIf { it.isNotEmpty() }?.let {
        pairIndexBootstrapInterval(it, ComponentSeeds.derive(plan.baseSeed, "search-teacher-calibration-bootstrap-v1"))
    }
    val games = operationalGames
    return SearchTeacherCalibrationComparison(candidate.id, assignedPairs, pairs, valid.size, valid.size * 2,
        pairs.count { !it.valid }, pairs.count { it.games.size != 2 },
        listOf("p0", "p1").map { searchBudgetFrontierSeat(it, valid, candidate.id) },
        scores.takeIf { it.isNotEmpty() }?.map { it.value }?.average(), interval?.first, interval?.second,
        listOf(plan.control, candidate).map { policy ->
            val search = searchBudgetFrontierOperational(policy.id, policy.parameters(plan.baseSeed), games)
                .let { if (policy.directArgentumHeuristic) it.copy(configuredSimulations = 0) else it }
            val counts = games.flatMap { it.seatDiagnostics.values }.filter { it.policyId == policy.id }
                .flatMap { it.selectionCounts.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
            val selected = counts.values.sum()
            val latency = games.flatMap { it.seatDiagnostics.values }.filter { it.policyId == policy.id }
                .flatMap { it.searchDecisionsDetail }.sumOf { it.latencyMillis }
            SearchTeacherCalibrationCost(search, selected, counts, latency.takeIf { selected > 0 }?.div(selected),
                latency.takeIf { games.isNotEmpty() }?.div(games.size),
                search.wholeGameElapsedMillis.takeIf { games.isNotEmpty() }?.div(games.size))
        })
}

internal fun renderSearchTeacherCalibration(report: SearchTeacherCalibrationReport) = buildString {
    appendLine("# Search Teacher calibration: ${report.plan.phase}")
    appendLine("Run `${report.runIdentity}`; source `${report.sourceProvenance.outer.revision}`; Argentum `${report.sourceProvenance.argentum.revision}`.")
    appendLine("Control `${report.plan.control.id}`; pairs ${report.plan.pairOffset} until ${report.plan.pairOffset + report.plan.pairCount}; valid=${report.valid}.")
    appendLine("Worker threads: ${report.workerThreads}. Timing describes this concurrent arena workload.")
    appendLine("Current calibration attempt through report construction: ${report.currentAttemptElapsedMillis / 1_000.0} seconds; excludes caller setup, prior resumed attempts and subsequent report writing/finalization/verification.")
    (listOf(report.plan.control) + report.plan.candidates).forEach { policy ->
        if (policy.directArgentumHeuristic) appendLine("Policy `${policy.id}`: original direct Argentum heuristic; no search, evaluator or rollout policy.")
        else appendLine("Policy `${policy.id}`: particles=${policy.particles}, simulations=${policy.simulations}, decision horizon=${policy.maxPolicyDecisions}.")
    }
    val controlFields = evidenceJson.encodeToJsonElement(SearchTeacherCalibrationPolicy.serializer(), report.plan.control) as kotlinx.serialization.json.JsonObject
    report.plan.candidates.forEach { candidate ->
        val fields = evidenceJson.encodeToJsonElement(SearchTeacherCalibrationPolicy.serializer(), candidate) as kotlinx.serialization.json.JsonObject
        val changed = (controlFields.keys + fields.keys).filter { it != "id" && controlFields[it] != fields[it] }.sorted()
        appendLine("Configuration changes for `${candidate.id}` (excluding display ID): ${changed.joinToString { field -> "$field: ${controlFields[field] ?: "default"} → ${fields[field] ?: "default"}" }.ifEmpty { "none" }}.")
    }
    report.sequentialResult?.let { result ->
        appendLine("Sequential rule: ${result.disposition} after ${result.inspectedPairs} inspected pairs; ${result.operationalOvershootPairs} completed overshoot pairs.")
        if (result.disposition == PairedSequentialDisposition.BUDGET_EXHAUSTED) {
            appendLine("Inconclusive: the pair cap was reached without satisfying the declared stopping criterion.")
        }
        result.confidenceSequence?.let { interval ->
            val acceptance = requireNotNull(result.rule.practicalAcceptance)
            appendLine("Practical objective=${acceptance.objective}; margin=${acceptance.margin}; score floor=${result.rule.nullPointRate}; equivalence band=(${result.rule.nullPointRate}, ${result.rule.targetPointRate}).")
            appendLine("Anytime confidence sequence (${interval.simultaneousCoverageAtLeast} simultaneous coverage): [${interval.lower}, ${interval.upper}] over ${interval.validScoredPairs} valid pairs; maximum compatible distance from parity=${interval.maximumCompatibleDistanceFromParity}.")
            appendLine("This strength criterion does not establish a runtime improvement or stronger learned play. Invalid execution prevents acceptance; the interval alone cannot override it.")
        }
        if (result.disposition == PairedSequentialDisposition.FUTILITY) {
            appendLine("Stopped for futility: neither directional boundary remains reachable within the planned pair cap. Inconclusive; parity or equivalence is not established.")
        }
        appendLine("First-prefix valid=${report.valid}; all-attempt operational valid=${report.sequentialOperationalValid}.")
        report.sequentialPopulation?.let { population ->
            appendLine("Pairs: planned=${population.plannedPairs}, executed=${population.executedPairs}, inspected=${population.inspectedPairs}, planned but unexecuted=${population.plannedUnexecutedPairs}, overshoot=${population.overshootPairs}.")
            appendLine("Invalid pairs: inspected=${population.invalidInspectedPairs}, overshoot=${population.invalidOvershootPairs}.")
        }
        appendLine("Comparison strength uses the first stopping prefix only. Bootstrap intervals are descriptive, not optional-stopping guarantees; the sequential result carries the declared test.")
        appendLine("Operational counters include the stopping prefix and the separately retained overshoot; comparison denominators count inspected pairs.")
    }
    report.comparisons.forEach { comparison ->
        appendLine("- ${comparison.candidateId}: ${comparison.validPairs}/${comparison.assignedPairs} valid ${if (report.sequentialResult == null) "pairs" else "inspected pairs"}; point rate=${comparison.candidatePointRate}; paired bootstrap 95%=[${comparison.pairedBootstrap95Lower}, ${comparison.pairedBootstrap95Upper}].")
        appendLine("  Candidate W/L/draw=${comparison.candidateBySeat.sumOf { it.wins }}/${comparison.candidateBySeat.sumOf { it.losses }}/${comparison.candidateBySeat.sumOf { it.draws }} over ${comparison.validGames} complete valid games; invalid pairs=${comparison.invalidPairs}; incomplete pairs=${comparison.incompletePairs}. Counts use the comparison prefix, excluding sequential overshoot.")
        comparison.operationalByPolicy.forEach { cost ->
            appendLine("  ${cost.search.policyId}: searched ${cost.search.searchedDecisions}/${cost.selections} selections; singleton=${cost.selectionCounts[SearchTeacherSelectionKind.POLICY_SINGLETON_ACTION] ?: 0}; search ms/selection=${cost.searchedMillisPerSelection}; search ms/game=${cost.searchedMillisPerGame}; shared game ms=${cost.sharedWholeGameMeanMillis}.")
        }
    }
    report.limitations.forEach { appendLine("- $it") }
}
