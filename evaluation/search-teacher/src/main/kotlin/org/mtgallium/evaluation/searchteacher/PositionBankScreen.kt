package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.InformationSetSearchDiagnostics
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.SearchCandidateStatistics
import org.mtgallium.agent.infoset.core.SearchSettlementCounts
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.RootActionSearchEstimate
import org.mtgallium.agent.infoset.core.InformationSetSearchReuseConfig
import org.mtgallium.agent.searchteacher.ConfiguredMonoRedInformationEvaluator
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.agent.searchteacher.MonoRedVisibleFeatures
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunFiles

@Serializable
internal enum class PositionBankScreenMode { FEATURES, SEARCH, ACTION_CONDITIONAL, ACTION_CONDITIONAL_V2_TRACES }

@Serializable
internal enum class PositionBankScreenPartition { DEVELOPMENT, VALIDATION }

@Serializable
internal data class PositionBankScreenPolicy(
    val search: SearchTeacherCalibrationPolicy,
    val evaluator: MonoRedVisibleEvaluatorConfig,
) {
    init {
        require(search.evaluator == null || search.evaluator == evaluator) {
            "Search and screen evaluator configurations must agree"
        }
    }
}

@Serializable
internal data class PositionBankScreenPlan(
    val schemaVersion: Int = 1,
    val bankDirectory: String,
    val expectedBankIdentity: String,
    val partition: PositionBankScreenPartition,
    val mode: PositionBankScreenMode,
    val rootLimit: Int,
    val repetitions: Int,
    val policies: List<PositionBankScreenPolicy>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val searchSeedDomain: String = "position-bank-screen-v1",
) {
    init {
        require(schemaVersion == 1 && bankDirectory.isNotBlank() && expectedBankIdentity.isNotBlank())
        require(rootLimit > 0 && repetitions > 0 && policies.isNotEmpty())
        require(searchSeedDomain.isNotBlank())
        require(policies.map { it.search.id }.distinct().size == policies.size)
        require(mode != PositionBankScreenMode.FEATURES || repetitions == 1) {
            "Deterministic feature rescoring has no stochastic repetitions"
        }
    }
}

@Serializable
internal enum class PositionBankScreenDisposition { SCORED, SEARCHED, AUTOMATIC_SELECTION, ACTION_CONDITIONAL, REFUSED }

@Serializable
internal data class PositionBankScreenRow(
    val rootId: String,
    val policyId: String,
    val evaluatorConfigurationId: String,
    val repetition: Int,
    val disposition: PositionBankScreenDisposition,
    val rawRootHeuristic: Double,
    val boundedRootHeuristic: Double,
    val policyIdentity: String? = null,
    val searchSeed: Long? = null,
    val chosen: SemanticChoice? = null,
    val selectionKind: String? = null,
    val searchRootValue: Double? = null,
    val candidateStatistics: List<SearchCandidateStatistics> = emptyList(),
    val candidateSettlementCounts: Map<String, SearchSettlementCounts> = emptyMap(),
    val searchDiagnostics: InformationSetSearchDiagnostics? = null,
    val reconstructionMillis: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reusedRootPreparation: Boolean = false,
    val selectionMillis: Double? = null,
    val diagnostic: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rootActionEstimates: List<RootActionSearchEstimate> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val visibleV2ActionTraces: List<VisibleV2ActionTrace> = emptyList(),
)

@Serializable
internal data class PositionBankScreenReport(
    val schemaVersion: Int = 1,
    val researchRunIdentity: String,
    val sourceProvenance: PolicySourceProvenance,
    val generatedAtUtc: String,
    val plan: PositionBankScreenPlan,
    val workerThreads: Int,
    val eligibleRoots: Int,
    val selectedRootIds: List<String>,
    val rows: List<PositionBankScreenRow>,
    val valid: Boolean,
    val limitations: List<String> = listOf(
        "Root feature scores, search values and observed source choices are diagnostics, not correct-action or outcome labels.",
        "Feature mode rescales cached information-state features; it does not replay a changed policy counterfactually.",
        "Search modes reconstruct remembered information and sequential belief once per root/policy, then reuse that preparation across repetitions; referee state is never supplied as a belief particle. Search trees remain fresh.",
        "Matched root/repetition seeds are shared across policies. Different particle/search settings are explicit interventions.",
        "Root selection is capped in fixed root-id order within the requested whole-seed-group partition. Validation access is explicit and must be reported in later tuning claims.",
        "No tactical grading, parameter winner or playing-strength conclusion is generated by this screen.",
        "Action-conditional mode spends the configured simulations on every initially admitted root action; subsequent decisions use normal search.",
        "Action-conditional backed means retain their configured settlement meaning, not terminal-outcome authority. Adaptive tree backups are not independent uncertainty replicates.",
        "Reconstruction cost is charged once to the first repetition of each root/policy; later repetitions report zero and reusedRootPreparation=true. Selection cost remains per repetition. Timing is for this concurrent screening workload.",
    ),
)

internal class PositionBankScreenRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
) {
    fun run(plan: PositionBankScreenPlan, output: Path, workerThreads: Int): PositionBankScreenReport {
        require(workerThreads > 0)
        val sourceRun = RunProvenance.capture(root).also { it.requireReady() }
        require(!sourceRun.outerDirty && !sourceRun.engineDirty) { "Bank screening requires a committed clean treatment" }
        val source = requireNotNull(sourceRun.sourceProvenance)
        val bankDirectory = Path.of(plan.bankDirectory)
        val bank = loadVerifiedRealGamePositionBank(bankDirectory, plan.expectedBankIdentity)
        val eligible = bank.roots.filter { it.partition.name == plan.partition.name }.sortedBy { it.rootId }
        val selected = eligible.take(plan.rootLimit)
        require(selected.isNotEmpty()) { "The requested bank partition has no roots" }
        val bindings = ResearchRunBindings(protocol = "real-game-position-screen-v1", material = mapOf(
            "plan" to sha256(evidenceJson.encodeToString(plan)),
            "bank" to bank.bankIdentity,
            "bank-manifest" to sha256File(bankDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE)),
            "source" to sha256(evidenceJson.encodeToString(source)),
            "deck" to manifest.deckHash(), "card-pool" to manifest.cardPoolHash(),
            "worker-threads" to workerThreads.toString(),
        ))
        val directory = EvidenceStore(root).requireDiagnosticOutput(output, "Real-game position screening")
        if (Files.exists(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE))) {
            ResearchRunArtifacts.loadAndVerify(directory, bindings.identity)
            return evidenceJson.decodeFromString<PositionBankScreenReport>(Files.readString(directory.resolve("report.json")))
                .also { require(it.researchRunIdentity == bindings.identity && it.plan == plan) }
        }
        val total = Math.multiplyExact(selected.size, Math.multiplyExact(plan.policies.size, plan.repetitions))
        val progressPath = System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of)
        val completed = AtomicInteger(0)
        publishDurableRunProgress(progressPath, 0, total, "position screen", "screening selected roots", "rows")
        val groups = Math.multiplyExact(selected.size, plan.policies.size)
        val rows = parallelMapOrdered(groups, workerThreads) { task ->
            val position = selected[task / plan.policies.size]
            val policy = plan.policies[task % plan.policies.size]
            val evaluator = ConfiguredMonoRedInformationEvaluator(policy.evaluator)
            // The cache is a derived view, not a replacement authority for the represented state.
            require(MonoRedVisibleFeatures.extract(position.information, position.actor) == position.visibleFeatures)
            val raw = position.visibleFeatures.rawScore(evaluator.config)
            val bounded = position.visibleFeatures.evaluate(evaluator.config)
            val parameters = policy.search.parameters(position.baseSeed)
            require(!parameters.searchReuse.enabled) { "Screen repetitions require fresh search trees" }
            // Lazy so a feature-only screen does not load a model or construct an engine world.
            val arenaPolicy by lazy { policy.search.policy(position.baseSeed) }
            val prepared = if (plan.mode == PositionBankScreenMode.FEATURES) null else runCatching {
                val reconstructionStarted = System.nanoTime()
                val sourceEntry = bank.plan.sources.single { it.expectedRunIdentity == position.sourceRunIdentity }
                val sourceDirectory = Path.of(sourceEntry.runDirectory)
                val replayPath = ResearchRunFiles.resolveBelow(sourceDirectory, position.replayRelativePath)
                require(sha256File(replayPath) == position.replaySha256)
                val replay = readVerifiedCanonicalSemanticReplay(replayPath)
                require(replay.header.gameId == position.sourceGameId)
                require(replay.header.requireExtensionString("mtgallium.runIdentity") == position.sourceRunIdentity)
                require(replay.header.requireExtensionString("mtgallium.deckHash") == manifest.deckHash())
                require(replay.header.requireExtensionString("mtgallium.cardPoolHash") == manifest.cardPoolHash())
                require(replay.header.requireExtensionLong("mtgallium.gameSeed") == position.gameSeed)
                require(replay.header.requireExtensionLong("mtgallium.baseSeed") == position.baseSeed)
                val prefix = replay.decisions.take(position.decisionIndex).map { it.choice }
                require(PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }) == position.semanticPrefixDigest)
                val actual = createSemanticReplayWorld(registry, manifest, position.sourceGameId, position.gameSeed,
                    position.baseSeed, 0, parameters.actionSpaceProfile)
                val session = SearchTeacherPolicySession(actual, position.actor,
                    mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck), parameters,
                    defaultMonoRedOpponentPolicy(), position.sourceGameId,
                    arenaPolicy.effectiveRootRolloutPolicy(), arenaPolicy.effectiveOpponentRolloutPolicy(), evaluator)
                replayFixedRootPrefix(position.decisionIndex, replay, actual, session)
                require(actual.actorToAct() == position.actor)
                require(actual.informationState(position.actor).informationStateDigest == position.informationStateDigest)
                val candidates = actual.expandChoices().candidates
                require(candidates == position.reconstructedCandidates) { "Current candidate expansion changed" }
                val reconstructionMillis = (System.nanoTime() - reconstructionStarted) / 1_000_000.0
                PreparedPositionBankRoot(actual, session, candidates, reconstructionMillis)
            }
            List(plan.repetitions) { repetition ->
                val scored = PositionBankScreenRow(position.rootId, policy.search.id, evaluator.configurationId,
                    repetition, PositionBankScreenDisposition.SCORED, raw, bounded)
                val row = if (plan.mode == PositionBankScreenMode.FEATURES) scored else screenPositionBankRepetition(
                    scored, prepared?.getOrNull()?.reconstructionMillis,
                ) { accounted ->
                    val root = requireNotNull(prepared).getOrThrow()
                    val actual = root.actual
                    val session = root.session
                    val candidates = root.candidates
                    val searchSeed = ComponentSeeds.derive(position.sourceGameId, position.decisionIndex,
                        position.baseSeed, plan.searchSeedDomain, repetition)
                    val selectionStarted = System.nanoTime()
                    if (plan.mode == PositionBankScreenMode.ACTION_CONDITIONAL || plan.mode == PositionBankScreenMode.ACTION_CONDITIONAL_V2_TRACES) {
                        val belief = session.beliefBatch(actual)
                        val recorder = if (plan.mode == PositionBankScreenMode.ACTION_CONDITIONAL_V2_TRACES)
                            RecordingVisibleV2Evaluator(policy.evaluator) else null
                        val traces = mutableListOf<VisibleV2ActionTrace>()
                        val search = SearchTeacherSearchFactory.create(parameters.searchConfig(), defaultMonoRedOpponentPolicy(),
                            arenaPolicy.effectiveRootRolloutPolicy(), arenaPolicy.effectiveOpponentRolloutPolicy(), recorder ?: evaluator,
                            InformationSetSearchReuseConfig.DISABLED)
                        val estimates = candidates.map { choice ->
                            recorder?.reset()
                            search.estimateRootAction(position.actor, belief, choice.signature, searchSeed).also {
                                requireValidScreenSearch(it.diagnostics)
                                recorder?.let { capture -> traces += capture.finish(it) }
                            }
                        }
                        accounted.copy(disposition = PositionBankScreenDisposition.ACTION_CONDITIONAL,
                            policyIdentity = session.policyIdentity, searchSeed = searchSeed,
                            selectionMillis = (System.nanoTime() - selectionStarted) / 1_000_000.0,
                            rootActionEstimates = estimates, visibleV2ActionTraces = traces)
                    } else {
                        val selection = session.select(actual, position.actor, searchSeed)
                        val selectionMillis = (System.nanoTime() - selectionStarted) / 1_000_000.0
                        require(candidates.any { it == selection.choice })
                        val search = selection.search
                        search?.diagnostics?.let(::requireValidScreenSearch)
                        accounted.copy(disposition = if (search == null) PositionBankScreenDisposition.AUTOMATIC_SELECTION
                            else PositionBankScreenDisposition.SEARCHED,
                            policyIdentity = session.policyIdentity, searchSeed = searchSeed,
                            chosen = selection.choice, selectionKind = selection.kind.name,
                            searchRootValue = search?.rootValue, candidateStatistics = search?.candidates.orEmpty(),
                            candidateSettlementCounts = search?.candidateSettlementCounts.orEmpty(),
                            searchDiagnostics = search?.diagnostics,
                            selectionMillis = selectionMillis)
                    }
                }
                publishDurableRunProgress(progressPath, completed.incrementAndGet(), total, "position screen", position.rootId, "rows")
                row
            }
        }.flatten()
        val report = PositionBankScreenReport(researchRunIdentity = bindings.identity, sourceProvenance = source,
            generatedAtUtc = Instant.now().toString(), plan = plan, workerThreads = workerThreads,
            eligibleRoots = eligible.size, selectedRootIds = selected.map { it.rootId }, rows = rows,
            valid = rows.none { it.disposition == PositionBankScreenDisposition.REFUSED })
        writeJsonAtomically(directory.resolve("plan.json"), plan)
        writeJsonAtomically(directory.resolve("report.json"), report)
        writeTextAtomically(directory.resolve("report.md"), buildString {
            appendLine("# Real-game position screen")
            appendLine("Run `${report.researchRunIdentity}`; bank `${bank.bankIdentity}`; mode ${plan.mode}; partition ${plan.partition}.")
            appendLine("${selected.size}/${eligible.size} roots, ${rows.size} rows, valid=${report.valid}. No correctness labels or promotion are inferred.")
            plan.policies.forEach { policy ->
                val group = rows.filter { it.policyId == policy.search.id }
                appendLine("- ${policy.search.id}: ${group.groupingBy { it.disposition }.eachCount()}; " +
                    "mean cached heuristic=${group.map { it.boundedRootHeuristic }.average()}; " +
                    "selection ms=${group.mapNotNull { it.selectionMillis }.takeIf { it.isNotEmpty() }?.average()}.")
            }
            report.limitations.forEach { appendLine("- $it") }
        })
        ResearchRunArtifacts(directory, bindings.identity).also {
            listOf("plan.json", "report.json", "report.md").forEach(it::register)
            it.finalize()
        }
        return report
    }
}

internal fun requireValidScreenSearch(diagnostics: InformationSetSearchDiagnostics) {
    require(diagnostics.rejectedTransitions == 0 &&
        diagnostics.opponentModelPolicyDecisions.evidenceInvalidatingReplacements == 0 &&
        diagnostics.rootRolloutPolicyDecisions.evidenceInvalidatingReplacements == 0 &&
        diagnostics.opponentRolloutPolicyDecisions.evidenceInvalidatingReplacements == 0) {
        "Screen search contains a rejected transition or evidence-invalidating policy replacement"
    }
}

/** One worker owns this materialized root for all repetitions; it is never advanced by a search. */
private data class PreparedPositionBankRoot(
    val actual: org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld,
    val session: SearchTeacherPolicySession,
    val candidates: List<SemanticChoice>,
    val reconstructionMillis: Double,
)

/** A search refusal preserves the preparation work already paid for this requested row. */
internal fun screenPositionBankRepetition(
    scored: PositionBankScreenRow,
    preparationMillis: Double?,
    select: (PositionBankScreenRow) -> PositionBankScreenRow,
): PositionBankScreenRow {
    val accounted = scored.copy(
        reconstructionMillis = preparationMillis?.let { if (scored.repetition == 0) it else 0.0 },
        reusedRootPreparation = preparationMillis != null && scored.repetition > 0,
    )
    return try {
        select(accounted)
    } catch (failure: Exception) {
        accounted.copy(disposition = PositionBankScreenDisposition.REFUSED,
            diagnostic = "${failure::class.simpleName}: ${failure.message}")
    }
}
