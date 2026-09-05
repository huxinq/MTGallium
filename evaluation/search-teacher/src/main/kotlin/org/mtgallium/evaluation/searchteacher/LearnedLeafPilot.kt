package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import org.mtgallium.evaluation.searchteacher.replay.ReplayCompletionStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.searchteacher.SEARCH_TEACHER_UNPROFILED_RUNTIME_ID
import org.mtgallium.agent.searchteacher.SearchTeacherEvaluatorRegistry
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints

internal const val LEARNED_LEAF_PILOT_CONTROL_ID =
    "$SEARCH_TEACHER_UNPROFILED_RUNTIME_ID-8x64-bounded-control"
internal const val LEARNED_LEAF_PILOT_TREATMENT_ID =
    "$SEARCH_TEACHER_UNPROFILED_RUNTIME_ID-8x64-learned-treatment"
internal const val LEARNED_LEAF_PILOT_CANDIDATE_PROTOCOL = "learned-outcome-value-8x64-pilot-candidate-v1"
internal const val LEARNED_LEAF_PILOT_SMOKE_PROTOCOL = "learned-outcome-value-8x64-pilot-smoke-v1"
internal const val LEARNED_LEAF_PILOT_PROTOCOL = "learned-outcome-value-8x64-pilot-v1"
internal const val LEARNED_LEAF_PILOT_REQUIRED_PAIRS = 50

/**
 * The model host supplies a promotion capability at execution time. Future pilot research-run
 * evidence must separately bind its validation-run/gate and training-envelope identities; policy
 * behavior continues to bind only the canonical evaluator checkpoint.
 */
internal object LearnedLeafPilotRoster {
    fun parameters(): Pair<SearchTeacherPolicyParameters, SearchTeacherPolicyParameters> {
        val control = SearchBudgetFrontierRoster.runtime.policyParameters()
        val learned = control.copy(
            leaf = LeafEvaluationConfig(
                LeafStateSource.CURRENT_INFORMATION_STATE,
                LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1,
            ),
        )
        require(control.particles == 8 && control.simulations == 64)
        require(learned.particles == 8 && learned.simulations == 64)
        require(learned.copy(leaf = control.leaf) == control) {
            "Learned-leaf pilot treatment must differ from control only in its leaf configuration"
        }
        return control to learned
    }

    fun policies(promotion: PromotedOutcomeValueCheckpoint): Pair<ArenaPolicySpec, ArenaPolicySpec> {
        val (control, learned) = parameters()
        return ArenaPolicySpec(
            id = LEARNED_LEAF_PILOT_CONTROL_ID,
            kind = ArenaPolicyKind.SEARCH,
            parameters = control,
        ) to ArenaPolicySpec(
            id = LEARNED_LEAF_PILOT_TREATMENT_ID,
            kind = ArenaPolicyKind.SEARCH,
            parameters = learned,
            informationEvaluator = promotion.evaluator(),
        )
    }
}

/**
 * The only preparation authority for a future pilot. It captures current source, the actual
 * manifest, exact roster behavior bindings, and the fixed schedule from one passed promotion;
 * callers cannot ask it to sign substituted policy, deck, or corpus material.
 */
internal class PreparedLearnedLeafPilotCandidate private constructor(
    val sourceRun: RunProvenance,
    val sourceProvenance: PolicySourceProvenance,
    val arena: SearchTeacherArena,
    val control: ArenaPolicySpec,
    val learned: ArenaPolicySpec,
    val candidateBindings: ResearchRunBindings,
    val smokeBindings: ResearchRunBindings,
    val deckHash: String,
    val cardPoolHash: String,
    val baseSeed: Long,
    val trainingRunIdentity: String,
    val validationRunIdentity: String,
    val validationGateIdentity: String,
    val validatorSourceIdentity: String,
    val trainingEnvelopePayloadSha256: String,
    val corpusIdentity: String,
    val pairSplitIdentity: String,
    val policyEvidenceIdentities: Map<String, String>,
    val smokePolicyEvidenceIdentities: Map<String, String>,
) {
    companion object {
        fun fromPromotion(
            promotion: PromotedOutcomeValueCheckpoint,
            root: Path,
            registry: CardRegistry,
            manifest: DeckManifest,
            baseSeed: Long,
        ): PreparedLearnedLeafPilotCandidate {
            val sourceRun = RunProvenance.capture(root)
            sourceRun.requireReady()
            require(!sourceRun.outerDirty && !sourceRun.engineDirty) {
                "Learned-leaf pilot preparation requires clean committed outer and Argentum source"
            }
            val sourceProvenance = requireNotNull(sourceRun.sourceProvenance)
            val (control, learned) = LearnedLeafPilotRoster.policies(promotion)
            val parameters = learned.effectiveParameters(baseSeed)
            promotion.requirePilotCompatibility(
                manifest.deckHash(), manifest.cardPoolHash(), parameters.actionSpaceProfile,
                sourceRun.checkedOutArgentumCommit,
            )
            val arena = SearchTeacherArena(registry, manifest, SearchTeacherArena.smokeProfile(), baseSeed)
            val policyEvidenceIdentities = listOf(control, learned).associate { policy ->
                policy.id to arena.evidenceBinding(policy, null, sourceProvenance).identity
            }.toSortedMap()
            val smokePolicyEvidenceIdentities = listOf(control, learned).associate { policy ->
                policy.id to arena.evidenceBinding(policy, 1, sourceProvenance).identity
            }.toSortedMap()
            val candidateBindings = ResearchRunBindings(
                protocol = LEARNED_LEAF_PILOT_CANDIDATE_PROTOCOL,
                material = mapOf(
                    "source-provenance" to sha256(evidenceJson.encodeToString(sourceProvenance)),
                    // These are policy-behavior identities, including checkpoint configuration.
                    "policy-evidence" to sha256(evidenceJson.encodeToString<Map<String, String>>(
                        policyEvidenceIdentities,
                    )),
                    "deck" to manifest.deckHash(),
                    "card-pool" to manifest.cardPoolHash(),
                    "base-seed" to baseSeed.toString(),
                    "pair-count" to LEARNED_LEAF_PILOT_REQUIRED_PAIRS.toString(),
                    "training-run" to promotion.trainingRunIdentity,
                    "validation-run" to promotion.validationRunIdentity,
                    "validation-gate" to promotion.validationGateIdentity,
                    "validator-source" to promotion.validatorSourceIdentity,
                    "training-envelope" to promotion.trainingEnvelopePayloadSha256,
                    "corpus" to promotion.corpusIdentity,
                    "pair-split" to promotion.pairSplitIdentity,
                ),
            )
            val smokeBindings = ResearchRunBindings(
                protocol = LEARNED_LEAF_PILOT_SMOKE_PROTOCOL,
                material = mapOf(
                    "pilot-candidate" to candidateBindings.identity,
                    "smoke-policy-evidence" to sha256(evidenceJson.encodeToString<Map<String, String>>(
                        smokePolicyEvidenceIdentities,
                    )),
                    "witness" to "one-genuine-8x64-learned-nonterminal-settlement-v1",
                ),
            )
            return PreparedLearnedLeafPilotCandidate(
                sourceRun, sourceProvenance, arena, control, learned, candidateBindings, smokeBindings,
                manifest.deckHash(), manifest.cardPoolHash(), baseSeed,
                promotion.trainingRunIdentity, promotion.validationRunIdentity,
                promotion.validationGateIdentity, promotion.validatorSourceIdentity,
                promotion.trainingEnvelopePayloadSha256, promotion.corpusIdentity,
                promotion.pairSplitIdentity, policyEvidenceIdentities, smokePolicyEvidenceIdentities,
            )
        }
    }
}

internal fun prepareLearnedLeafPilot(
    promotion: PromotedOutcomeValueCheckpoint,
    root: Path,
    registry: CardRegistry,
    manifest: DeckManifest,
    baseSeed: Long,
): PreparedLearnedLeafPilotCandidate =
    PreparedLearnedLeafPilotCandidate.fromPromotion(promotion, root, registry, manifest, baseSeed)

/**
 * The only capability accepted by the 50-pair runner. It is minted after a matching smoke
 * artifact is verified; neither promotion nor a caller-supplied report can invoke the population.
 */
internal class AdmittedLearnedLeafPilotExecution private constructor(
    private val candidate: PreparedLearnedLeafPilotCandidate,
    val smokeReport: LearnedLeafPilotSmokeReport,
) {
    val sourceRun: RunProvenance get() = candidate.sourceRun
    val sourceProvenance: PolicySourceProvenance get() = candidate.sourceProvenance
    val arena: SearchTeacherArena get() = candidate.arena
    val control: ArenaPolicySpec get() = candidate.control
    val learned: ArenaPolicySpec get() = candidate.learned
    val deckHash: String get() = candidate.deckHash
    val cardPoolHash: String get() = candidate.cardPoolHash
    val baseSeed: Long get() = candidate.baseSeed
    val trainingRunIdentity: String get() = candidate.trainingRunIdentity
    val validationRunIdentity: String get() = candidate.validationRunIdentity
    val validationGateIdentity: String get() = candidate.validationGateIdentity
    val validatorSourceIdentity: String get() = candidate.validatorSourceIdentity
    val trainingEnvelopePayloadSha256: String get() = candidate.trainingEnvelopePayloadSha256
    val corpusIdentity: String get() = candidate.corpusIdentity
    val pairSplitIdentity: String get() = candidate.pairSplitIdentity
    val policyEvidenceIdentities: Map<String, String> get() = candidate.policyEvidenceIdentities
    val smokePolicyEvidenceIdentities: Map<String, String> get() = candidate.smokePolicyEvidenceIdentities
    val candidateRunIdentity: String get() = candidate.candidateBindings.identity
    val smokeRunIdentity: String get() = smokeReport.runIdentity

    /** The population identity includes actual execution parallelism because it affects telemetry. */
    fun runBindings(workerThreads: Int): ResearchRunBindings {
        require(workerThreads > 0)
        return ResearchRunBindings(
            protocol = LEARNED_LEAF_PILOT_PROTOCOL,
            material = mapOf(
                "pilot-candidate" to candidate.candidateBindings.identity,
                "smoke-evidence" to smokeReport.runIdentity,
                "worker-threads" to workerThreads.toString(),
            ),
        )
    }

    companion object {
        internal fun fromVerifiedSmoke(
            candidate: PreparedLearnedLeafPilotCandidate,
            smoke: VerifiedLearnedLeafPilotSmoke,
        ): AdmittedLearnedLeafPilotExecution {
            return AdmittedLearnedLeafPilotExecution(candidate, smoke.report)
        }
    }
}

@Serializable
internal data class LearnedLeafPilotPair(
    val pairIndex: Int,
    val seed: Long,
    val games: List<GameRunResult>,
    val valid: Boolean,
    val invalidationReasons: List<String>,
    val treatmentPoints: Double? = null,
)

/** Per-arm totals are taken only from actual arena search-decision diagnostics. */
@Serializable
internal data class LearnedLeafPilotOperationalSummary(
    val policyId: String,
    val configuredParticles: Int,
    val configuredSimulations: Int,
    val games: Int,
    val validGames: Int,
    val invalidGames: Int,
    val searchedDecisions: Int,
    val wholeSearchLatencyMillisTotal: Double,
    val wholeSearchLatencyMillisMean: Double?,
    val wholeSearchLatencyMillisP50: Double?,
    val wholeSearchLatencyMillisP95: Double?,
    val actualSimulationsTotal: Int,
    val evaluatorCallsTotal: Int,
    val evaluatorNanosTotal: Long,
    val rolloutDecisionsTotal: Int,
    val simulatedWorldStepsTotal: Int,
    /** Historical/partial records cannot turn a default zero into provenance evidence. */
    val settlementCountsUnavailableDecisions: Int,
    val terminalPayoffBackupsTotal: Int,
    val heuristicSettlementBackupsTotal: Int,
    val learnedOutcomeEstimateBackupsTotal: Int,
    val neutralUnresolvedSettlementBackupsTotal: Int,
)

@Serializable
internal data class LearnedLeafPilotFailureSummary(
    val unsupportedInformationGames: Int,
    val invalidBeliefWeightUpdates: Int,
    val illegalResponses: Int,
    val fallbacks: Int,
    val exceptionGames: Int,
    val typedStopGames: Int,
    val replayInvalidGames: Int,
)

@Serializable
internal data class LearnedLeafPilotReport(
    val schemaVersion: Int = 4,
    val protocol: String = LEARNED_LEAF_PILOT_PROTOCOL,
    val runIdentity: String,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourceProvenance: PolicySourceProvenance,
    val deckHash: String,
    val cardPoolHash: String,
    val baseSeed: Long,
    val workerThreads: Int,
    val assignedPairs: Int,
    val assignedGames: Int,
    val policies: List<TournamentPolicyDescription>,
    val trainingRunIdentity: String,
    val validationRunIdentity: String,
    val validationGateIdentity: String,
    val validatorSourceIdentity: String,
    val trainingEnvelopePayloadSha256: String,
    val corpusIdentity: String,
    val pairSplitIdentity: String,
    val policyEvidenceIdentities: Map<String, String>,
    val candidateRunIdentity: String,
    val smokeRunIdentity: String,
    val pairs: List<LearnedLeafPilotPair>,
    val validPairs: Int,
    val invalidPairs: Int,
    val incompletePairs: Int,
    val validGames: Int,
    val invalidGames: Int,
    val incompleteGames: Int,
    val treatmentWins: Int,
    val treatmentDraws: Int,
    val treatmentLosses: Int,
    val treatmentBySeat: List<SearchBudgetFrontierSeatSummary>,
    val treatmentPointRate: Double?,
    val pairedBootstrap95Lower: Double?,
    val pairedBootstrap95Upper: Double?,
    val visibleSeatPointRateDifference: Double?,
    val operationalByPolicy: List<LearnedLeafPilotOperationalSummary>,
    val failureSummary: LearnedLeafPilotFailureSummary,
    val valid: Boolean,
    val failureReasons: List<String>,
)

/**
 * The only execution owner for the predeclared 50-pair learned-leaf pilot.  It cannot extend or
 * promote a result: its sole input authority is an already admitted execution capability.
 */
internal class LearnedLeafPilotRunner(
    private val root: Path,
) {
    private val evidence = EvidenceStore(root)

    fun run(
        execution: AdmittedLearnedLeafPilotExecution,
        workerThreads: Int,
        output: Path? = null,
    ): LearnedLeafPilotReport {
        require(workerThreads > 0)
        val bindings = execution.runBindings(workerThreads)
        val directory = output ?: evidence.diagnostic(
            "learned-leaf-pilot/${learnedLeafPilotDirectoryKey(bindings.identity)}",
            "the learned outcome-value paired pilot output",
        )
        evidence.requireDiagnosticOutput(directory, "the learned outcome-value paired pilot output")
        val reportPath = directory.resolve("report.json")
        val artifactManifest = directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)
        if (Files.exists(artifactManifest)) {
            require(Files.isRegularFile(reportPath)) {
                "Completed learned-leaf pilot manifest has no report; refusing to resume or overwrite: $directory"
            }
            ResearchRunArtifacts.loadAndVerify(directory, bindings.identity)
            val retained = evidenceJson.decodeFromString<LearnedLeafPilotReport>(Files.readString(reportPath))
            val checkpointPairs = loadCompletedPairs(directory, execution, bindings)
            return deriveReport(execution, bindings, retained.workerThreads, checkpointPairs, retained.generatedAtUtc, directory)
                .also { require(it == retained) { "Completed learned-leaf pilot report disagrees with retained games" } }
        }
        require(!Files.exists(reportPath)) {
            "Incomplete learned-leaf pilot output has a report without its completion manifest: $directory"
        }
        val progress = LearnedLeafPilotDurableProgress(LEARNED_LEAF_PILOT_REQUIRED_PAIRS)
        progress.update(0, "preparing 8x64 bounded versus learned-leaf seat-swapped pairs")
        val completedPairs = LearnedLeafPilotCompletionProgress()
        val pairs = parallelMapOrdered(LEARNED_LEAF_PILOT_REQUIRED_PAIRS, workerThreads) { pairIndex ->
            val seed = ComponentSeeds.derive(execution.baseSeed, pairIndex, "learned-leaf-pilot-library-orders")
            val checkpoint = directory.resolve("pairs/pair-$pairIndex.json")
            val games = loadPair(
                checkpoint, directory, execution, bindings, pairIndex, seed,
            ).toMutableList()
            while (games.size < 2) {
                val leg = games.size
                val descriptor = tournamentDescriptor(execution.control, execution.learned, pairIndex, leg)
                games += execution.arena.playWithPolicies(
                    descriptor.gameId,
                    seed,
                    if (leg == 0) execution.control else execution.learned,
                    if (leg == 0) execution.learned else execution.control,
                    replay = replayOptions(directory, bindings.identity, descriptor),
                )
                persistPair(checkpoint, bindings.identity, pairIndex, seed, games)
            }
            learnedLeafPilotPair(pairIndex, seed, games, execution.learned.id).also {
                progress.update(completedPairs.completePair(), "completed pair $pairIndex")
            }
        }.sortedBy(LearnedLeafPilotPair::pairIndex)
        val report = deriveReport(execution, bindings, workerThreads, pairs, Instant.now().toString(), directory)
        writeJsonAtomically(reportPath, report)
        ResearchRunArtifacts(directory, bindings.identity).also {
            it.register("report.json")
            report.pairs.forEach { pair ->
                it.register("pairs/pair-${pair.pairIndex}.json")
                pair.games.forEachIndexed { leg, game ->
                    it.register("replays/${tournamentDescriptor(execution.control, execution.learned, pair.pairIndex, leg).gameId}.privileged.replay.jsonl.gz")
                }
            }
            it.finalize()
        }
        progress.complete("completed fixed 50-pair pilot")
        return report
    }

    /** One report authority for both fresh execution and completed-artifact reloading. */
    private fun deriveReport(
        execution: AdmittedLearnedLeafPilotExecution,
        bindings: ResearchRunBindings,
        workerThreads: Int,
        retainedPairs: List<LearnedLeafPilotPair>,
        generatedAtUtc: String,
        directory: Path,
    ): LearnedLeafPilotReport {
        val pairs = retainedPairs.sortedBy(LearnedLeafPilotPair::pairIndex).mapIndexed { expectedPairIndex, retained ->
            require(retained.pairIndex == expectedPairIndex) {
                "Learned-leaf pilot pair schedule is not the fixed 0..49 population"
            }
            val expectedSeed = ComponentSeeds.derive(
                execution.baseSeed, retained.pairIndex, "learned-leaf-pilot-library-orders",
            )
            require(retained.seed == expectedSeed)
            retained.games.forEachIndexed { leg, game ->
                requireRetainedGame(
                    directory, execution, bindings,
                    tournamentDescriptor(execution.control, execution.learned, retained.pairIndex, leg),
                    game, expectedSeed,
                )
            }
            learnedLeafPilotPair(retained.pairIndex, expectedSeed, retained.games, execution.learned.id).also {
                require(it == retained) { "Retained learned-leaf pair summary is not derived from its games" }
            }
        }
        val allGames = pairs.flatMap(LearnedLeafPilotPair::games)
        val valid = pairs.size == LEARNED_LEAF_PILOT_REQUIRED_PAIRS && pairs.all(LearnedLeafPilotPair::valid)
        val validPairs = pairs.filter(LearnedLeafPilotPair::valid)
        val pairScores = validPairs.map {
            TournamentPairIndexScore(it.pairIndex, requireNotNull(it.treatmentPoints) / 2.0)
        }
        val interval = pairScores.takeIf { it.isNotEmpty() }?.let {
            pairIndexBootstrapInterval(it, ComponentSeeds.derive(execution.baseSeed, "learned-leaf-pilot-bootstrap"))
        }
        val treatmentScores = validPairs.flatMap(LearnedLeafPilotPair::games).map {
            searchBudgetFrontierScore(it, execution.learned.id)
        }
        return LearnedLeafPilotReport(
            runIdentity = bindings.identity,
            generatedAtUtc = generatedAtUtc,
            outerCommit = execution.sourceRun.outerCommit,
            argentumCommit = execution.sourceRun.checkedOutArgentumCommit,
            sourceProvenance = execution.sourceProvenance,
            deckHash = execution.deckHash,
            cardPoolHash = execution.cardPoolHash,
            baseSeed = execution.baseSeed,
            workerThreads = workerThreads,
            assignedPairs = LEARNED_LEAF_PILOT_REQUIRED_PAIRS,
            assignedGames = LEARNED_LEAF_PILOT_REQUIRED_PAIRS * 2,
            policies = listOf(execution.control, execution.learned).map(::describeTournamentPolicy),
            trainingRunIdentity = execution.trainingRunIdentity,
            validationRunIdentity = execution.validationRunIdentity,
            validationGateIdentity = execution.validationGateIdentity,
            validatorSourceIdentity = execution.validatorSourceIdentity,
            trainingEnvelopePayloadSha256 = execution.trainingEnvelopePayloadSha256,
            corpusIdentity = execution.corpusIdentity,
            pairSplitIdentity = execution.pairSplitIdentity,
            policyEvidenceIdentities = execution.policyEvidenceIdentities,
            candidateRunIdentity = execution.candidateRunIdentity,
            smokeRunIdentity = execution.smokeRunIdentity,
            pairs = pairs,
            validPairs = validPairs.size,
            invalidPairs = pairs.count { it.games.size == 2 && !it.valid },
            incompletePairs = pairs.count { it.games.size != 2 },
            validGames = allGames.count(::learnedLeafPilotGameValid),
            invalidGames = allGames.count { !learnedLeafPilotGameValid(it) },
            incompleteGames = LEARNED_LEAF_PILOT_REQUIRED_PAIRS * 2 - allGames.size,
            treatmentWins = treatmentScores.count { it == 1.0 },
            treatmentDraws = treatmentScores.count { it == 0.5 },
            treatmentLosses = treatmentScores.count { it == 0.0 },
            treatmentBySeat = listOf("p0", "p1").map {
                searchBudgetFrontierSeat(it, validPairs.map(::asFrontierPair), execution.learned.id)
            },
            treatmentPointRate = pairScores.takeIf { it.isNotEmpty() }?.map(TournamentPairIndexScore::value)?.average(),
            pairedBootstrap95Lower = interval?.first,
            pairedBootstrap95Upper = interval?.second,
            visibleSeatPointRateDifference = searchBudgetFrontierSeatDifference(
                validPairs.map(::asFrontierPair), execution.learned.id,
            ),
            operationalByPolicy = listOf(
                learnedLeafPilotOperational(execution.control.id, execution.control.effectiveParameters(execution.baseSeed), allGames),
                learnedLeafPilotOperational(execution.learned.id, execution.learned.effectiveParameters(execution.baseSeed), allGames),
            ),
            failureSummary = learnedLeafPilotFailureSummary(allGames),
            valid = valid,
            failureReasons = buildList {
                if (pairs.size != LEARNED_LEAF_PILOT_REQUIRED_PAIRS) {
                    add("assigned pair materialization incomplete: ${pairs.size}/$LEARNED_LEAF_PILOT_REQUIRED_PAIRS")
                }
                pairs.filterNot(LearnedLeafPilotPair::valid).forEach { pair ->
                    add("pair ${pair.pairIndex} invalid: ${pair.invalidationReasons.joinToString("; ")}")
                }
            },
        )
    }

    private fun requireRetainedGame(
        directory: Path,
        execution: AdmittedLearnedLeafPilotExecution,
        bindings: ResearchRunBindings,
        descriptor: TournamentGameDescriptor,
        game: GameRunResult,
        seed: Long,
    ) {
        require(game.gameId == descriptor.gameId && game.seed == seed &&
            game.p0PolicyId == descriptor.p0PolicyId && game.p1PolicyId == descriptor.p1PolicyId &&
            game.p0Policy == ArenaPolicyKind.SEARCH && game.p1Policy == ArenaPolicyKind.SEARCH)
        requirePilotSearchDiagnostics(game, execution)
        val expected = replayOptions(directory, bindings.identity, descriptor)
        require(game.replayVerified && game.replayPath == expected.referencePath) {
            "Retained learned-leaf game does not name its exact verified replay"
        }
        val replayPath = expected.finalPath
        require(Files.isRegularFile(replayPath) && !Files.isSymbolicLink(replayPath))
        require(game.replaySha256 == sha256File(replayPath)) { "Retained learned-leaf replay hash changed" }
        require(CanonicalTournamentReplayVerifier.verify(replayPath).verified) {
            "Retained learned-leaf replay no longer reconstructs"
        }
        val replay = reconstructCanonicalTournamentReplay(replayPath)
        require(replay.header.gameId == descriptor.gameId &&
            replay.header.requireExtensionString("mtgallium.runIdentity") == bindings.identity &&
            replay.header.requireExtensionString("mtgallium.outerCommit") == execution.sourceRun.outerCommit &&
            replay.header.requireExtensionString("mtgallium.argentumCommit") == execution.sourceRun.checkedOutArgentumCommit &&
            replay.header.requireExtensionLong("mtgallium.gameSeed") == seed &&
            replay.header.requireExtensionLong("mtgallium.baseSeed") == execution.baseSeed &&
            replay.header.requireExtensionString("mtgallium.deckHash") == execution.deckHash &&
            replay.header.requireExtensionString("mtgallium.cardPoolHash") == execution.cardPoolHash) {
            "Retained learned-leaf replay header does not bind the expected scheduled game"
        }
        val completed = replay.terminal.status == ReplayCompletionStatus.COMPLETE
        val semanticDecisions = replay.transitions.count { "mtgallium.semanticChoice" in it.extensions }
        require(game.terminal == completed && game.winner == replay.terminal.winnerId &&
            game.decisions == semanticDecisions &&
            (game.disposition == GameRunDisposition.GAME_ENDED) == completed) {
            "Retained learned-leaf outcome facts disagree with its reconstructed canonical replay"
        }
    }

    private fun requirePilotSearchDiagnostics(
        game: GameRunResult,
        execution: AdmittedLearnedLeafPilotExecution,
    ) {
        val expectedPolicies = mapOf("p0" to game.p0PolicyId, "p1" to game.p1PolicyId)
        require(game.seatDiagnostics.keys == expectedPolicies.keys)
        game.seatDiagnostics.forEach { (seat, diagnostics) ->
            require(diagnostics.policyId == expectedPolicies.getValue(seat) &&
                diagnostics.searchDecisions == diagnostics.searchDecisionsDetail.size &&
                diagnostics.searchLatenciesMillis == diagnostics.searchDecisionsDetail.map(ArenaSearchDecisionDiagnostic::latencyMillis))
            val parameters = if (diagnostics.policyId == execution.control.id) {
                execution.control.effectiveParameters(execution.baseSeed)
            } else {
                require(diagnostics.policyId == execution.learned.id)
                execution.learned.effectiveParameters(execution.baseSeed)
            }
            diagnostics.searchDecisionsDetail.forEach { detail ->
                require(detail.searchDiagnostics.particles == parameters.particles &&
                    detail.searchDiagnostics.simulations == parameters.simulations &&
                    detail.searchDiagnostics.leaf == parameters.leaf &&
                    detail.settlementCountsAvailability == SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1 &&
                    detail.settlementCounts.successfulBackups == detail.searchDiagnostics.simulations)
                requirePilotEvaluatorDiagnostic(
                    detail,
                    parameters,
                    if (diagnostics.policyId == execution.control.id) execution.control else execution.learned,
                )
            }
        }
    }

    private fun loadPair(
        path: Path,
        directory: Path,
        execution: AdmittedLearnedLeafPilotExecution,
        bindings: ResearchRunBindings,
        pairIndex: Int,
        seed: Long,
    ): List<GameRunResult> {
        if (!Files.exists(path)) return emptyList()
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
            "Learned-leaf pair checkpoint is not a regular non-link file: $path"
        }
        val envelope = ResearchRunCheckpoints.load(path)
        require(envelope.researchRunIdentity == bindings.identity &&
            envelope.payloadSchema == LEARNED_LEAF_PILOT_CHECKPOINT_SCHEMA)
        val checkpoint = evidenceJson.decodeFromString<LearnedLeafPilotCheckpoint>(envelope.payload().decodeToString())
        require(checkpoint.pairIndex == pairIndex && checkpoint.seed == seed && checkpoint.games.size in 1..2 &&
            envelope.sequence == checkpoint.games.size.toLong()) {
            "Learned-leaf pair checkpoint identity, sequence, or retained leg count is invalid"
        }
        checkpoint.games.forEachIndexed { leg, game ->
            requireRetainedGame(
                directory, execution, bindings,
                tournamentDescriptor(execution.control, execution.learned, pairIndex, leg), game, seed,
            )
        }
        return checkpoint.games
    }

    private fun loadCompletedPairs(
        directory: Path,
        execution: AdmittedLearnedLeafPilotExecution,
        bindings: ResearchRunBindings,
    ): List<LearnedLeafPilotPair> = (0 until LEARNED_LEAF_PILOT_REQUIRED_PAIRS).map { pairIndex ->
        val seed = ComponentSeeds.derive(execution.baseSeed, pairIndex, "learned-leaf-pilot-library-orders")
        val games = loadPair(directory.resolve("pairs/pair-$pairIndex.json"), directory, execution, bindings, pairIndex, seed)
        require(games.size == 2) { "Completed learned-leaf pilot is missing a retained leg for pair $pairIndex" }
        learnedLeafPilotPair(pairIndex, seed, games, execution.learned.id)
    }

    private fun persistPair(path: Path, identity: String, pairIndex: Int, seed: Long, games: List<GameRunResult>) {
        ResearchRunCheckpoints.persist(
            path, identity, LEARNED_LEAF_PILOT_CHECKPOINT_SCHEMA, games.size.toLong(),
            evidenceJson.encodeToString(LearnedLeafPilotCheckpoint(pairIndex, seed, games)).encodeToByteArray(),
        )
    }

    private fun replayOptions(
        directory: Path,
        identity: String,
        descriptor: TournamentGameDescriptor,
    ): GameReplayOptions {
        val path = directory.resolve("replays/${descriptor.gameId}.privileged.replay.jsonl.gz")
        return GameReplayOptions(path, root.relativize(path).toString(), identity)
    }
}

/**
 * Post-PASS preflight. This runs exactly one genuine arena decision through the admitted pilot
 * roster and never creates a pair checkpoint or enters the 50-pair population.
 */
internal class LearnedLeafPilotSmokeRunner(
    private val root: Path,
) {
    private val evidence = EvidenceStore(root)

    fun run(candidate: PreparedLearnedLeafPilotCandidate, output: Path): LearnedLeafPilotSmokeReport {
        evidence.requireDiagnosticOutput(output, "the learned outcome-value pilot smoke output")
        if (Files.exists(output)) return VerifiedLearnedLeafPilotSmoke.load(candidate, output).report
        Files.createDirectories(output)
        val game = candidate.arena.playWithPolicies(
            gameId = "learned-leaf-pilot-smoke-${learnedLeafPilotDirectoryKey(candidate.smokeBindings.identity)}",
            gameSeed = ComponentSeeds.derive(candidate.baseSeed, "learned-leaf-pilot-smoke"),
            p0Policy = candidate.learned,
            p1Policy = candidate.control,
            maxSearchDecisions = 1,
        )
        val detail = requireSmokeGame(candidate, game)
        return LearnedLeafPilotSmokeReport(
            runIdentity = candidate.smokeBindings.identity,
            candidateRunIdentity = candidate.candidateBindings.identity,
            game = game,
            learnedPolicyId = candidate.learned.id,
            policyEvidenceIdentities = candidate.smokePolicyEvidenceIdentities,
            particles = detail.searchDiagnostics.particles,
            simulations = detail.searchDiagnostics.simulations,
            evaluatorCalls = detail.searchDiagnostics.evaluatorCalls,
            evaluatorNanos = detail.searchDiagnostics.evaluatorNanos,
            settlementCounts = detail.settlementCounts,
        ).also { report ->
            writeJsonAtomically(output.resolve("report.json"), report)
            ResearchRunArtifacts(output, candidate.smokeBindings.identity).also {
                it.register("report.json")
                it.finalize()
            }
        }
    }

    /** Loads only an already completed matching smoke artifact; it never creates or reruns one. */
    fun loadAdmitted(
        candidate: PreparedLearnedLeafPilotCandidate,
        output: Path,
    ): AdmittedLearnedLeafPilotExecution =
        AdmittedLearnedLeafPilotExecution.fromVerifiedSmoke(
            candidate, VerifiedLearnedLeafPilotSmoke.load(candidate, output),
        )
}

private fun requirePilotEvaluatorDiagnostic(
    detail: ArenaSearchDecisionDiagnostic,
    parameters: SearchTeacherPolicyParameters,
    policy: ArenaPolicySpec,
) {
    val expected = SearchTeacherEvaluatorRegistry.strategy(parameters.leaf, policy.informationEvaluator)
    require(detail.searchDiagnostics.configuredEvaluatorId == expected.configuredEvaluatorId &&
        detail.searchDiagnostics.invokedEvaluatorId == expected.source.invokedEvaluatorId &&
        detail.searchDiagnostics.invokedEvaluatorConfigurationId ==
            expected.source.invokedEvaluatorConfigurationId) {
        "Retained learned-leaf diagnostic invoked an evaluator other than its configured policy authority"
    }
}

private fun requireSmokeGame(
    candidate: PreparedLearnedLeafPilotCandidate,
    game: GameRunResult,
): ArenaSearchDecisionDiagnostic {
    val learnedParameters = candidate.learned.effectiveParameters(candidate.baseSeed)
    val learnedSeat = requireNotNull(game.seatDiagnostics["p0"]) { "Pilot smoke lost p0 diagnostics" }
    val controlSeat = requireNotNull(game.seatDiagnostics["p1"]) { "Pilot smoke lost p1 diagnostics" }
    val detail = learnedSeat.searchDecisionsDetail.singleOrNull()
        ?: error("Pilot smoke did not reach exactly one genuine learned-policy search decision")
    requirePilotEvaluatorDiagnostic(detail, learnedParameters, candidate.learned)
    require(game.gameId == "learned-leaf-pilot-smoke-${learnedLeafPilotDirectoryKey(candidate.smokeBindings.identity)}" &&
        game.seed == ComponentSeeds.derive(candidate.baseSeed, "learned-leaf-pilot-smoke") &&
        game.p0Policy == ArenaPolicyKind.SEARCH && game.p1Policy == ArenaPolicyKind.SEARCH &&
        game.p0PolicyId == candidate.learned.id && game.p1PolicyId == candidate.control.id &&
        game.disposition == GameRunDisposition.STOPPED_LIMIT && !game.terminal && game.stepLimit &&
        game.decisions == 1 && game.evidenceStop == null && game.exception == null &&
        game.illegalResponses == 0 && game.fallbacks == 0 && game.unsupportedInformationEvents.isEmpty() &&
        game.seatDiagnostics.keys == setOf("p0", "p1") &&
        learnedSeat.policyId == candidate.learned.id && learnedSeat.searchDecisions == 1 &&
        learnedSeat.searchLatenciesMillis == listOf(detail.latencyMillis) && learnedSeat.invalidBeliefWeights == 0 &&
        controlSeat.policyId == candidate.control.id && controlSeat.searchDecisions == 0 &&
        controlSeat.searchDecisionsDetail.isEmpty() && controlSeat.searchLatenciesMillis.isEmpty() &&
        controlSeat.invalidBeliefWeights == 0 &&
        game.seatDiagnostics.values.sumOf(ArenaSeatDiagnostics::searchDecisions) == 1 &&
        detail.searchDiagnostics.particles == learnedParameters.particles &&
        detail.searchDiagnostics.simulations == learnedParameters.simulations &&
        detail.searchDiagnostics.freshSimulations == learnedParameters.simulations &&
        detail.searchDiagnostics.reusedSimulations == 0 &&
        detail.searchDiagnostics.rejectedTransitions == 0 &&
        detail.searchDiagnostics.leaf == learnedParameters.leaf &&
        detail.settlementCountsAvailability == SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1 &&
        detail.settlementCounts.successfulBackups == detail.searchDiagnostics.simulations &&
        detail.settlementCounts.learnedOutcomeEstimateBackups > 0) {
        "Pilot smoke does not retain the required one-search learned 8x64 limit-stop witness"
    }
    return detail
}

/** Fail-closed artifact loading seam shared by the admission path and focused persistence tests. */
internal fun loadCompletedLearnedLeafPilotSmokeArtifact(
    output: Path,
    expectedSmokeIdentity: String,
): LearnedLeafPilotSmokeReport {
    require(Files.isDirectory(output)) { "Existing learned-leaf smoke output is not a directory: $output" }
    val reportPath = output.resolve("report.json")
    require(Files.isRegularFile(reportPath)) {
        "Existing learned-leaf smoke output is incomplete; refusing to overwrite or rerun it: $output"
    }
    ResearchRunArtifacts.loadAndVerify(output, expectedSmokeIdentity)
    return evidenceJson.decodeFromString(Files.readString(reportPath))
}

@Serializable
internal data class LearnedLeafPilotSmokeReport(
    val schemaVersion: Int = 4,
    val runIdentity: String,
    val candidateRunIdentity: String,
    val game: GameRunResult,
    val learnedPolicyId: String,
    val policyEvidenceIdentities: Map<String, String>,
    val particles: Int,
    val simulations: Int,
    val evaluatorCalls: Int,
    val evaluatorNanos: Long,
    val settlementCounts: org.mtgallium.agent.infoset.core.SearchSettlementCounts,
)

/** Opaque proof that [LearnedLeafPilotSmokeRunner] loaded and checked a completed smoke artifact. */
internal class VerifiedLearnedLeafPilotSmoke private constructor(
    val report: LearnedLeafPilotSmokeReport,
) {
    companion object {
        fun load(candidate: PreparedLearnedLeafPilotCandidate, output: Path): VerifiedLearnedLeafPilotSmoke =
            VerifiedLearnedLeafPilotSmoke(
                loadCompletedLearnedLeafPilotSmokeArtifact(output, candidate.smokeBindings.identity).also {
                    requireLoadedSmokeReport(it, candidate)
                },
            )
    }
}

@Serializable
private data class LearnedLeafPilotCheckpoint(
    val pairIndex: Int,
    val seed: Long,
    val games: List<GameRunResult>,
)

private const val LEARNED_LEAF_PILOT_CHECKPOINT_SCHEMA = "learned-leaf-pilot-pair-v2"

internal fun learnedLeafPilotPair(
    pairIndex: Int,
    seed: Long,
    games: List<GameRunResult>,
    treatmentId: String,
): LearnedLeafPilotPair {
    val reasons = games.flatMap(::learnedLeafPilotInvalidationReasons).distinct().sorted()
    val valid = games.size == 2 && reasons.isEmpty()
    return LearnedLeafPilotPair(
        pairIndex, seed, games, valid,
        if (games.size == 2) reasons else reasons + "incomplete seat-swapped pair",
        treatmentPoints = if (valid) games.sumOf { searchBudgetFrontierScore(it, treatmentId) } else null,
    )
}

internal fun learnedLeafPilotGameValid(game: GameRunResult): Boolean =
    learnedLeafPilotInvalidationReasons(game).isEmpty()

internal fun learnedLeafPilotInvalidationReasons(game: GameRunResult): List<String> =
    searchBudgetFrontierInvalidationReasons(game) + buildList {
        if (game.seatDiagnostics.keys != setOf("p0", "p1")) {
            add("${game.gameId}: missing paired search diagnostics")
        }
        game.seatDiagnostics.forEach { (seat, diagnostics) ->
            if (diagnostics.searchDecisions != diagnostics.searchDecisionsDetail.size ||
                diagnostics.searchLatenciesMillis != diagnostics.searchDecisionsDetail.map(ArenaSearchDecisionDiagnostic::latencyMillis)
            ) add("${game.gameId}: $seat search diagnostic summary disagrees with details")
            diagnostics.searchDecisionsDetail.forEach { detail ->
                if (detail.settlementCountsAvailability != SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1) {
                    add("${game.gameId}: $seat settlement provenance unavailable")
                } else if (detail.settlementCounts.successfulBackups != detail.searchDiagnostics.simulations) {
                    add("${game.gameId}: $seat settlement counts do not partition simulations")
                }
            }
        }
    }

private fun asFrontierPair(pair: LearnedLeafPilotPair): SearchBudgetFrontierPair = SearchBudgetFrontierPair(
    pair.pairIndex, pair.seed, pair.games, pair.valid, pair.invalidationReasons, pair.treatmentPoints,
)

internal fun learnedLeafPilotFailureSummary(games: List<GameRunResult>): LearnedLeafPilotFailureSummary =
    LearnedLeafPilotFailureSummary(
        unsupportedInformationGames = games.count { it.unsupportedInformationEvents.isNotEmpty() },
        invalidBeliefWeightUpdates = games.sumOf { game ->
            if (game.seatDiagnostics.isEmpty()) game.invalidBeliefWeights
            else game.seatDiagnostics.values.sumOf(ArenaSeatDiagnostics::invalidBeliefWeights)
        },
        illegalResponses = games.sumOf(GameRunResult::illegalResponses),
        fallbacks = games.sumOf(GameRunResult::fallbacks),
        exceptionGames = games.count { it.exception != null },
        typedStopGames = games.count { it.evidenceStop?.triggerCodes?.any { code ->
            code.startsWith("LEARNED_VALUE:")
        } == true },
        replayInvalidGames = games.count { !it.replayVerified },
    )

internal fun learnedLeafPilotOperational(
    policyId: String,
    parameters: SearchTeacherPolicyParameters,
    games: List<GameRunResult>,
): LearnedLeafPilotOperationalSummary {
    val armGames = games.filter { it.p0PolicyId == policyId || it.p1PolicyId == policyId }
    val details = armGames.mapNotNull { game ->
        game.seatDiagnostics.entries.singleOrNull { it.value.policyId == policyId }?.value
    }.flatMap(ArenaSeatDiagnostics::searchDecisionsDetail)
    val latencies = details.map(ArenaSearchDecisionDiagnostic::latencyMillis)
    val diagnostics = details.map(ArenaSearchDecisionDiagnostic::searchDiagnostics)
    val exactSettlements = details.filter {
        it.settlementCountsAvailability == SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1
    }
    val settlements = exactSettlements.map(ArenaSearchDecisionDiagnostic::settlementCounts).fold(
        org.mtgallium.agent.infoset.core.SearchSettlementCounts(),
    ) { total, counts -> total.plus(counts) }
    return LearnedLeafPilotOperationalSummary(
        policyId = policyId,
        configuredParticles = parameters.particles,
        configuredSimulations = parameters.simulations,
        games = armGames.size,
        validGames = armGames.count(::learnedLeafPilotGameValid),
        invalidGames = armGames.count { !learnedLeafPilotGameValid(it) },
        searchedDecisions = details.size,
        wholeSearchLatencyMillisTotal = latencies.sum(),
        wholeSearchLatencyMillisMean = latencies.takeIf { it.isNotEmpty() }?.average(),
        wholeSearchLatencyMillisP50 = latencies.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) },
        wholeSearchLatencyMillisP95 = latencies.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
        actualSimulationsTotal = diagnostics.sumOf { it.simulations },
        evaluatorCallsTotal = diagnostics.sumOf { it.evaluatorCalls },
        evaluatorNanosTotal = diagnostics.sumOf { it.evaluatorNanos },
        rolloutDecisionsTotal = diagnostics.sumOf { it.rootRolloutDecisions + it.opponentRolloutDecisions },
        simulatedWorldStepsTotal = diagnostics.sumOf { it.searchWorldSteps },
        settlementCountsUnavailableDecisions = details.size - exactSettlements.size,
        terminalPayoffBackupsTotal = settlements.terminalPayoffBackups,
        heuristicSettlementBackupsTotal = settlements.heuristicSettlementBackups,
        learnedOutcomeEstimateBackupsTotal = settlements.learnedOutcomeEstimateBackups,
        neutralUnresolvedSettlementBackupsTotal = settlements.neutralUnresolvedSettlementBackups,
    )
}

internal class LearnedLeafPilotDurableProgress(private val total: Int) {
    private val path = System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of)

    fun update(completed: Int, detail: String) = publish(completed, "learned leaf pilot", detail)
    fun complete(detail: String) = publish(total, "complete", detail)

    private fun publish(completed: Int, phase: String, detail: String) {
        publishDurableRunProgress(path, completed, total, phase, detail)
    }
}

/** Completion authority for parallel pair workers: only successful completions advance progress. */
internal class LearnedLeafPilotCompletionProgress {
    private val completedPairs = AtomicInteger(0)

    fun completePair(): Int = completedPairs.incrementAndGet()
}

private fun requireLoadedSmokeReport(
    report: LearnedLeafPilotSmokeReport,
    candidate: PreparedLearnedLeafPilotCandidate,
) {
    val detail = requireSmokeGame(candidate, report.game)
    require(report.schemaVersion == 4 && report.runIdentity == candidate.smokeBindings.identity &&
        report.candidateRunIdentity == candidate.candidateBindings.identity &&
        report.learnedPolicyId == candidate.learned.id &&
        report.policyEvidenceIdentities == candidate.smokePolicyEvidenceIdentities &&
        report.particles == detail.searchDiagnostics.particles &&
        report.simulations == detail.searchDiagnostics.simulations &&
        report.evaluatorCalls == detail.searchDiagnostics.evaluatorCalls &&
        report.evaluatorNanos == detail.searchDiagnostics.evaluatorNanos &&
        report.settlementCounts == detail.settlementCounts) {
        "Learned-leaf smoke artifact does not satisfy the prepared pilot candidate"
    }
}

private fun learnedLeafPilotDirectoryKey(identity: String): String = identity.substringAfterLast(':').take(24)
