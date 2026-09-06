package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.searchteacher.MonoRedVisibleFeatures
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints
import org.mtgallium.research.run.ResearchRunFiles

internal const val REAL_GAME_POSITION_BANK_PROTOCOL = "real-game-position-bank-calibration-v1"
internal const val REAL_GAME_POSITION_BANK_VALIDATION_FRACTION = 0.25

@Serializable
internal data class RealGamePositionBankSource(val runDirectory: String, val expectedRunIdentity: String) {
    init { require(Path.of(runDirectory).isAbsolute && expectedRunIdentity.isNotBlank()) }
}

@Serializable
internal data class RealGamePositionBankPlan(
    val schemaVersion: Int = 1,
    val sources: List<RealGamePositionBankSource>,
    val rootLimit: Int,
    val maxRootsPerGame: Int,
    val validationFraction: Double,
    val selectionSeed: Long,
) {
    init {
        require(schemaVersion == 1 && sources.isNotEmpty())
        require(sources.map { it.expectedRunIdentity }.distinct().size == sources.size)
        require(rootLimit > 0 && maxRootsPerGame > 0)
        require(validationFraction == REAL_GAME_POSITION_BANK_VALIDATION_FRACTION) {
            "Position bank v1 freezes a quarter of library-seed groups for validation"
        }
    }
}

@Serializable
internal enum class RealGamePositionPartition { DEVELOPMENT, VALIDATION }

@Serializable
internal enum class RealGamePositionDecisionFamily { KEEP_OR_MULLIGAN, BOTTOM_CARDS, ATTACKERS, BLOCKERS, RESPONSE, PRIORITY, OTHER }

@Serializable
internal enum class RealGamePositionAssignmentStatus { EXCLUDED, SELECTED, RECONSTRUCTED, REFUSED }

@Serializable
internal data class RealGamePositionBankSourceBinding(
    val runDirectory: String,
    val runIdentity: String,
    val reportSha256: String,
    val manifestSha256: String,
    val sourceProvenance: PolicySourceProvenance,
    val deckHash: String,
    val cardPoolHash: String,
    val sourcePlan: SearchTeacherCalibrationPlan,
    val policies: List<SearchTeacherCalibrationPolicyReport>,
    val assignedGames: Int,
    val validPairGames: Int,
    val searchedDecisions: Int,
)

/** Inventory of every retained searched decision, including unselected and refused roots. No values or labels. */
@Serializable
internal data class RealGamePositionBankAssignment(
    val rootId: String,
    val sourceRunIdentity: String,
    val sourceGameId: String,
    val sourcePolicyId: String,
    val actor: String,
    val decisionIndex: Int,
    val pairIndex: Int,
    val seedGroupId: String,
    val partition: RealGamePositionPartition,
    val decisionFamily: RealGamePositionDecisionFamily,
    val status: RealGamePositionAssignmentStatus,
    val reasons: List<String> = emptyList(),
)

@Serializable
internal data class RealGamePositionBankGame(
    val sourceRunIdentity: String,
    val sourceGameId: String,
    val pairIndex: Int,
    val leg: Int,
    val seedGroupId: String,
    val partition: RealGamePositionPartition,
    val validPair: Boolean,
    val searchedDecisions: Int,
    val exclusionReasons: List<String>,
)

/** Safe derived cache. Candidate admission and the observed source choice do not make strategic labels. */
@Serializable
internal data class RealGamePositionBankRoot(
    val rootId: String,
    val sourceRunIdentity: String,
    val sourceGameId: String,
    val sourcePolicyId: String,
    val sourceDescriptor: SearchTeacherCalibrationPolicy,
    val pairIndex: Int,
    val actor: String,
    val decisionIndex: Int,
    val gameSeed: Long,
    val baseSeed: Long,
    val replayRelativePath: String,
    val replaySha256: String,
    val replayTerminalRecordDigest: String,
    val semanticPrefixDigest: String,
    val informationStateDigest: String,
    val information: PolicyInformationState,
    val visibleFeatures: MonoRedVisibleFeatures,
    /** Authenticated source search-admitted candidates. */
    val candidates: List<SemanticChoice>,
    /** Current profile expansion at this exact replayed root, kept distinct from source admission. */
    val reconstructedCandidates: List<SemanticChoice>,
    val proposalVersion: String,
    val profileExpansionExhaustive: Boolean,
    val observedSourceChosen: SemanticChoice,
    val seedGroupId: String,
    val partition: RealGamePositionPartition,
    val decisionFamily: RealGamePositionDecisionFamily,
)

@Serializable
internal data class RealGamePositionBankAccounting(
    val assignedGames: Int,
    val validPairGames: Int,
    val searchedDecisions: Int,
    val eligibleRoots: Int,
    val selectedRoots: Int,
    val reconstructedRoots: Int,
    val refusedRoots: Int,
    val exclusionsByReason: Map<String, Int>,
    val allSeedGroupsByPartition: Map<RealGamePositionPartition, Int>,
    val selectedSeedGroupsByPartition: Map<RealGamePositionPartition, Int>,
    val rootsByPartition: Map<RealGamePositionPartition, Int>,
    val rootsByFamily: Map<RealGamePositionDecisionFamily, Int>,
)

@Serializable
internal data class RealGamePositionBankReport(
    val schemaVersion: Int = 1,
    val protocol: String = REAL_GAME_POSITION_BANK_PROTOCOL,
    val bankIdentity: String,
    val generatedAtUtc: String,
    val sourceProvenance: PolicySourceProvenance,
    val plan: RealGamePositionBankPlan,
    val sources: List<RealGamePositionBankSourceBinding>,
    val games: List<RealGamePositionBankGame>,
    val assignments: List<RealGamePositionBankAssignment>,
    val roots: List<RealGamePositionBankRoot>,
    val accounting: RealGamePositionBankAccounting,
    val complete: Boolean,
    val interpretation: String = "Unlabelled real-game roots: source choices are observations, never correct-action labels. Safe snapshots and features are derived caches; canonical source replays remain authority. No outcome targets or grades are generated.",
    val splitRule: String = "Fixed quarter split by hash of deck, card pool and library seed; every candidate comparison and both legs in that group share a partition, including repeated libraries under different policy seeds. Policy seed, selection seed and bank version do not enter the split.",
)

/** Does not include policy/base seed, run, seat, root, or selection seed: repeated initial libraries stay together. */
internal fun realGamePositionSeedGroup(deckHash: String, cardPoolHash: String, gameSeed: Long): String =
    ResearchRunBindings(protocol = "real-game-position-seed-group-v1", material = mapOf(
        "deck" to deckHash, "card-pool" to cardPoolHash,
        "game-seed" to gameSeed.toString())).identity

internal fun realGamePositionPartition(group: String, validationFraction: Double): RealGamePositionPartition {
    require(validationFraction == REAL_GAME_POSITION_BANK_VALIDATION_FRACTION)
    val unit = sha256("real-game-position-partition-v1:$group").take(13).toLong(16).toDouble() / 4_503_599_627_370_496.0
    return if (unit < validationFraction) RealGamePositionPartition.VALIDATION else RealGamePositionPartition.DEVELOPMENT
}

internal fun realGamePositionFamily(candidates: List<SemanticChoice>): RealGamePositionDecisionFamily {
    val families = candidates.map { it.operationFamily }.toSet()
    return when {
        candidates.any { it.actionIntent.kind == SemanticActionIntentKind.BOTTOM_CARDS } -> RealGamePositionDecisionFamily.BOTTOM_CARDS
        SemanticOperationFamily.MULLIGAN in families -> RealGamePositionDecisionFamily.KEEP_OR_MULLIGAN
        SemanticOperationFamily.DECLARE_ATTACKERS in families -> RealGamePositionDecisionFamily.ATTACKERS
        SemanticOperationFamily.DECLARE_BLOCKERS in families -> RealGamePositionDecisionFamily.BLOCKERS
        SemanticOperationFamily.DECISION_RESPONSE in families -> RealGamePositionDecisionFamily.RESPONSE
        families.any { it in setOf(SemanticOperationFamily.PASS_PRIORITY, SemanticOperationFamily.CAST_SPELL,
            SemanticOperationFamily.PLAY_LAND, SemanticOperationFamily.ACTIVATE_ABILITY) } -> RealGamePositionDecisionFamily.PRIORITY
        else -> RealGamePositionDecisionFamily.OTHER
    }
}

/** Result-blind round robin across available decision families, with deterministic within-family ranks. */
internal fun selectRealGamePositionAssignments(plan: RealGamePositionBankPlan,
    assignments: List<RealGamePositionBankAssignment>): List<RealGamePositionBankAssignment> {
    require(assignments.map { it.rootId }.distinct().size == assignments.size)
    val queues = assignments.filter { it.reasons.isEmpty() }.groupBy { it.decisionFamily }.toSortedMap()
        .mapValues { (_, rows) -> ArrayDeque(rows.sortedBy { sha256("real-game-position-selection-v1:${plan.selectionSeed}:${it.rootId}") }) }
    val selected = mutableSetOf<String>()
    val capped = mutableSetOf<String>()
    val perGame = mutableMapOf<Pair<String, String>, Int>()
    while (selected.size < plan.rootLimit && queues.values.any { it.isNotEmpty() }) {
        queues.values.forEach { queue ->
            while (selected.size < plan.rootLimit && queue.isNotEmpty()) {
                val row = queue.removeFirst()
                val game = row.sourceRunIdentity to row.sourceGameId
                if (perGame.getOrDefault(game, 0) >= plan.maxRootsPerGame) capped += row.rootId else {
                    selected += row.rootId
                    perGame[game] = perGame.getOrDefault(game, 0) + 1
                    break
                }
            }
        }
    }
    return assignments.map { row -> when {
        row.reasons.isNotEmpty() -> row
        row.rootId in selected -> row.copy(status = RealGamePositionAssignmentStatus.SELECTED)
        else -> row.copy(status = RealGamePositionAssignmentStatus.EXCLUDED,
            reasons = listOf(if (row.rootId in capped) "per-game-cap" else "root-limit"))
    } }
}

private fun bankBindings(plan: RealGamePositionBankPlan, source: PolicySourceProvenance,
    sources: List<RealGamePositionBankSourceBinding>) = ResearchRunBindings(
    protocol = REAL_GAME_POSITION_BANK_PROTOCOL, material = mapOf(
        "plan" to sha256(evidenceJson.encodeToString(plan)),
        "source-provenance" to sha256(evidenceJson.encodeToString(source)),
        "source-runs" to sha256(evidenceJson.encodeToString(sources))))

private data class BankSource(val binding: RealGamePositionBankSourceBinding,
    val report: SearchTeacherCalibrationReport)

internal fun requireRealGamePositionBankSourceIdentity(report: SearchTeacherCalibrationReport, expectedIdentity: String) {
    require(report.sequentialRule == null && report.sequentialResult == null &&
        report.sequentialOvershootPairs == null && report.sequentialOperationalValid == null && report.sequentialPopulation == null) {
        "Position bank v1 does not admit sequential calibration populations"
    }
    require(report.schemaVersion == 1 && report.protocol == SEARCH_TEACHER_CALIBRATION_PROTOCOL && report.workerThreads > 0)
    require(report.sourceProvenance.gitlinkMatchesCheckout && report.runIdentity == expectedIdentity)
    val descriptors = listOf(report.plan.control) + report.plan.candidates
    require(report.policies.map { it.descriptor } == descriptors)
    require(report.policies.all { it.policy.id == it.descriptor.id && it.binding.sourceProvenance == report.sourceProvenance })
    require(searchTeacherCalibrationBindings(report.plan, report.sourceProvenance,
        report.policies.associate { it.descriptor.id to it.binding.identity }, report.deckHash, report.cardPoolHash,
        report.workerThreads).identity == report.runIdentity)
}

/** Modern calibration-only admission: identity, registered report/plan and checkpoint population must agree. */
private fun readBankSource(input: RealGamePositionBankSource): BankSource {
    val directory = Path.of(input.runDirectory).toAbsolutePath().normalize()
    val artifacts = ResearchRunArtifacts.loadAndVerify(directory, input.expectedRunIdentity)
    val entries = artifacts.artifacts.associateBy { it.relativePath }
    val reportPath = ResearchRunFiles.resolveBelow(directory, "report.json")
    require(entries.getValue("report.json").sha256 == sha256File(reportPath))
    val report = evidenceJson.decodeFromString<SearchTeacherCalibrationReport>(Files.readString(reportPath))
    requireRealGamePositionBankSourceIdentity(report, input.expectedRunIdentity)
    require(entries.containsKey("plan.json"))
    require(evidenceJson.decodeFromString<SearchTeacherCalibrationPlan>(Files.readString(directory.resolve("plan.json"))) == report.plan)
    require(report.comparisons.map { it.candidateId } == report.plan.candidates.map { it.id })
    report.comparisons.forEach { comparison ->
        require(comparison.assignedPairs == report.plan.pairCount)
        require(comparison.pairs.map { it.pairIndex } == (report.plan.pairOffset until report.plan.pairOffset + report.plan.pairCount).toList())
        comparison.pairs.forEach { pair ->
            require(pair.seed == report.plan.pairSeed(pair.pairIndex))
            require(pair == searchBudgetFrontierPair(pair.pairIndex, pair.seed, pair.games, comparison.candidateId))
            require(pair.games.size == 2)
            pair.games.forEachIndexed { leg, game ->
                val gameId = "${comparison.candidateId}-pair-${pair.pairIndex}-leg-$leg"
                val p0 = if (leg == 0) report.plan.control.id else comparison.candidateId
                val p1 = if (leg == 0) comparison.candidateId else report.plan.control.id
                val checkpointRelative = "checkpoints/$gameId.json"
                require(entries.containsKey(checkpointRelative))
                val checkpointPath = ResearchRunFiles.resolveBelow(directory, checkpointRelative)
                require(loadSearchTeacherCalibrationCheckpoint(checkpointPath, directory, report.runIdentity,
                    pair.pairIndex, leg, pair.seed, p0, p1, gameId) == game)
                val checkpoint = evidenceJson.decodeFromString<SearchTeacherCalibrationCheckpoint>(
                    ResearchRunCheckpoints.load(checkpointPath).payload().decodeToString())
                checkpoint.artifactSha256.forEach { (path, hash) -> require(entries.getValue(path).sha256 == hash) }
                if (pair.valid) require(entries.getValue("replays/$gameId.privileged.replay.jsonl.gz").sha256 == game.replaySha256)
            }
        }
        require(comparison.validPairs == comparison.pairs.count { it.valid } && comparison.validGames == comparison.validPairs * 2)
        require(comparison.invalidPairs == comparison.pairs.count { !it.valid } && comparison.incompletePairs == 0)
    }
    require(report.valid == report.comparisons.all { it.validPairs == report.plan.pairCount })
    val games = report.comparisons.flatMap { it.pairs }.flatMap { it.games }
    return BankSource(RealGamePositionBankSourceBinding(directory.toString(), report.runIdentity,
        entries.getValue("report.json").sha256, sha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)),
        report.sourceProvenance, report.deckHash, report.cardPoolHash, report.plan, report.policies, games.size,
        report.comparisons.sumOf { it.validGames }, games.sumOf { game -> game.seatDiagnostics.values.sumOf { it.searchDecisionsDetail.size } }),
        report)
}

internal fun loadVerifiedRealGamePositionBank(directory: Path, expectedIdentity: String): RealGamePositionBankReport {
    val artifacts = ResearchRunArtifacts.loadAndVerify(directory, expectedIdentity)
    require(setOf("plan.json", "report.json").all { required -> artifacts.artifacts.any { it.relativePath == required } })
    val report = evidenceJson.decodeFromString<RealGamePositionBankReport>(Files.readString(directory.resolve("report.json")))
    require(report.schemaVersion == 1 && report.protocol == REAL_GAME_POSITION_BANK_PROTOCOL && report.bankIdentity == expectedIdentity)
    require(bankBindings(report.plan, report.sourceProvenance, report.sources).identity == report.bankIdentity)
    require(evidenceJson.decodeFromString<RealGamePositionBankPlan>(Files.readString(directory.resolve("plan.json"))) == report.plan)
    require(report.complete) { "Position bank has refused reconstructions and cannot supply screening roots" }
    require(report.plan.sources.size == report.sources.size)
    report.plan.sources.zip(report.sources).forEach { (input, retained) -> require(readBankSource(input).binding == retained) }
    require(report.roots.map { it.rootId }.distinct().size == report.roots.size)
    require(report.roots.map { it.rootId }.toSet() == report.assignments.filter {
        it.status == RealGamePositionAssignmentStatus.RECONSTRUCTED }.map { it.rootId }.toSet())
    report.roots.forEach { row ->
        val source = report.sources.single { it.runIdentity == row.sourceRunIdentity }
        require(row.informationStateDigest == row.information.informationStateDigest)
        require(row.visibleFeatures == MonoRedVisibleFeatures.extract(row.information, row.actor))
        require(row.sourceDescriptor == source.policies.single { it.descriptor.id == row.sourcePolicyId }.descriptor)
        require(row.seedGroupId == realGamePositionSeedGroup(source.deckHash, source.cardPoolHash, row.gameSeed))
        require(row.partition == realGamePositionPartition(row.seedGroupId, report.plan.validationFraction))
    }
    require(report.accounting.reconstructedRoots == report.roots.size && report.accounting.refusedRoots == 0)
    return report
}

internal class RealGamePositionBankRunner(private val root: Path, private val registry: CardRegistry,
    private val manifest: DeckManifest) {
    fun run(plan: RealGamePositionBankPlan, output: Path): RealGamePositionBankReport {
        val sourceRun = RunProvenance.capture(root).also { it.requireReady() }
        require(!sourceRun.outerDirty && !sourceRun.engineDirty) { "Position bank requires committed clean source" }
        val source = sourceRun.sourceProvenance
        val admitted = plan.sources.map(::readBankSource)
        admitted.forEach { parent ->
            require(parent.report.deckHash == manifest.deckHash() && parent.report.cardPoolHash == manifest.cardPoolHash())
            require(parent.report.sourceProvenance.argentum.revision == source.argentum.revision) {
                "Position bank v1 requires the exact source-game engine revision"
            }
            val arena = SearchTeacherArena(registry, manifest, calibrationPresentationProfile(parent.report.sourceProvenance),
                parent.report.plan.baseSeed)
            parent.report.policies.forEach { policy ->
                val spec = policy.descriptor.policy(parent.report.plan.baseSeed)
                require(arena.evidenceBinding(spec, null, parent.report.sourceProvenance) == policy.binding)
                require(describeTournamentPolicy(spec) == policy.policy && spec.effectiveParameters(parent.report.plan.baseSeed).searchConfig() == policy.search)
                require(spec.effectiveRootRolloutPolicy().behaviorSpecification == policy.rootRolloutPolicy &&
                    spec.effectiveOpponentRolloutPolicy().behaviorSpecification == policy.opponentRolloutPolicy)
            }
        }
        val bindings = admitted.map { it.binding }
        val identity = bankBindings(plan, source, bindings).identity
        val directory = EvidenceStore(root).requireDiagnosticOutput(output, "real-game position bank")
        if (Files.exists(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE))) return loadVerifiedRealGamePositionBank(directory, identity)
        require(!Files.exists(directory) || Files.list(directory).use { it.findAny().isEmpty }) {
            "Position bank output is nonempty and unfinished; use a new version directory"
        }
        val games = mutableListOf<RealGamePositionBankGame>()
        val rootsById = mutableMapOf<String, Triple<BankSource, GameRunResult, ArenaSearchDecisionDiagnostic>>()
        val inventory = mutableListOf<RealGamePositionBankAssignment>()
        admitted.forEach { parent -> parent.report.comparisons.forEach { comparison -> comparison.pairs.forEach { pair ->
            pair.games.forEachIndexed { leg, game ->
                val group = realGamePositionSeedGroup(parent.report.deckHash, parent.report.cardPoolHash, game.seed)
                val partition = realGamePositionPartition(group, plan.validationFraction)
                games += RealGamePositionBankGame(parent.report.runIdentity, game.gameId, pair.pairIndex, leg,
                    group, partition, pair.valid, game.seatDiagnostics.values.sumOf { it.searchDecisionsDetail.size },
                    if (pair.valid) emptyList() else pair.invalidationReasons)
                game.seatDiagnostics.forEach { (actor, seat) ->
                    require(actor in setOf("p0", "p1"))
                    require(seat.policyId == if (actor == "p0") game.p0PolicyId else game.p1PolicyId)
                    require(seat.searchDecisionsDetail.map { it.decisionIndex }.distinct().size == seat.searchDecisionsDetail.size)
                    if (pair.valid) require(seat.searchDecisionsDetail.size == seat.searchDecisions &&
                        seat.selectionCounts.getOrDefault(SearchTeacherSelectionKind.SEARCHED, 0) == seat.searchDecisions)
                    seat.searchDecisionsDetail.forEach { decision ->
                        require(decision.decisionIndex >= 0)
                        if (pair.valid) require(decision.decisionIndex < game.decisions)
                        val choices = decision.candidateStatistics.map { it.choice }
                        require(choices.map { it.signature }.distinct().size == choices.size)
                        val rootId = "real-game-root-v1-sha256:" + sha256("${parent.report.runIdentity}:${game.gameId}:$actor:${decision.decisionIndex}")
                        val reasons = buildList {
                            if (!pair.valid) add("invalid-source-pair")
                            if (choices.size < 2) add("fewer-than-two-admitted-candidates")
                            if (decision.chosen == null) add("missing-observed-source-choice")
                        }
                        inventory += RealGamePositionBankAssignment(rootId, parent.report.runIdentity, game.gameId,
                            seat.policyId, actor, decision.decisionIndex, pair.pairIndex, group, partition,
                            realGamePositionFamily(choices), RealGamePositionAssignmentStatus.EXCLUDED, reasons)
                        rootsById[rootId] = Triple(parent, game, decision)
                    }
                }
            }
        } } }
        val assignments = selectRealGamePositionAssignments(plan, inventory).toMutableList()
        val selected = assignments.filter { it.status == RealGamePositionAssignmentStatus.SELECTED }
        val reconstructed = mutableListOf<RealGamePositionBankRoot>()
        val progressPath = System.getenv("MTGALLIUM_PROGRESS_FILE")?.let(Path::of)
        var processedRoots = 0
        if (selected.isNotEmpty()) publishDurableRunProgress(progressPath, 0, selected.size,
            "position bank", "reconstructing selected roots", "roots")
        // Only selected games are decoded/reconstructed. Their privileged states never become belief particles.
        selected.groupBy { it.sourceRunIdentity to it.sourceGameId }.values.forEach { selectedGame ->
            val (parent, game, _) = rootsById.getValue(selectedGame.first().rootId)
            val replayRelative = "replays/${game.gameId}.privileged.replay.jsonl.gz"
            val replay = readVerifiedCanonicalSemanticReplay(ResearchRunFiles.resolveBelow(Path.of(parent.binding.runDirectory), replayRelative))
            authenticateBankReplay(parent.report, game, replay)
            selectedGame.forEach { assignment ->
                val index = assignments.indexOfFirst { it.rootId == assignment.rootId }
                try {
                    val diagnostic = rootsById.getValue(assignment.rootId).third
                    reconstructed += reconstructRoot(parent, game, replay, replayRelative, diagnostic, assignment)
                    assignments[index] = assignment.copy(status = RealGamePositionAssignmentStatus.RECONSTRUCTED)
                } catch (failure: Exception) {
                    assignments[index] = assignment.copy(status = RealGamePositionAssignmentStatus.REFUSED,
                        reasons = listOf("reconstruction-refused:${failure.javaClass.simpleName}:${failure.message}"))
                }
                publishDurableRunProgress(progressPath, ++processedRoots, selected.size, "position bank", assignment.rootId, "roots")
            }
        }
        val accounting = RealGamePositionBankAccounting(games.size, games.count { it.validPair }, inventory.size,
            inventory.count { it.reasons.isEmpty() }, selected.size, reconstructed.size, selected.size - reconstructed.size,
            assignments.flatMap { it.reasons }.groupingBy { it }.eachCount(),
            games.distinctBy { it.seedGroupId }.groupingBy { it.partition }.eachCount(),
            selected.distinctBy { it.seedGroupId }.groupingBy { it.partition }.eachCount(),
            reconstructed.groupingBy { it.partition }.eachCount(), reconstructed.groupingBy { it.decisionFamily }.eachCount())
        val report = RealGamePositionBankReport(bankIdentity = identity, generatedAtUtc = Instant.now().toString(),
            sourceProvenance = source, plan = plan, sources = bindings, games = games, assignments = assignments,
            roots = reconstructed, accounting = accounting, complete = selected.isNotEmpty() && reconstructed.size == selected.size)
        writeJsonAtomically(directory.resolve("plan.json"), plan)
        writeJsonAtomically(directory.resolve("report.json"), report)
        ResearchRunArtifacts(directory, identity).also { it.register("plan.json"); it.register("report.json"); it.finalize() }
        return report
    }

    private fun reconstructRoot(parent: BankSource, game: GameRunResult, replay: VerifiedCanonicalSemanticReplay,
        replayRelative: String, diagnostic: ArenaSearchDecisionDiagnostic, assignment: RealGamePositionBankAssignment): RealGamePositionBankRoot {
        val policy = parent.report.policies.single { it.descriptor.id == assignment.sourcePolicyId }
        val parameters = policy.descriptor.parameters(parent.report.plan.baseSeed)
        val actual = createSemanticReplayWorld(registry, manifest, game.gameId, game.seed, parent.report.plan.baseSeed,
            0, parameters.actionSpaceProfile)
        require(assignment.decisionIndex in replay.decisions.indices)
        replayFixedRootPrefix(assignment.decisionIndex, replay, actual, null)
        require(actual.actorToAct() == assignment.actor)
        val information = actual.informationState(assignment.actor)
        require(!information.terminated && information.actingPlayerId == assignment.actor)
        require(information.observation.turnNumber == diagnostic.turnNumber && information.observation.phase == diagnostic.phase &&
            information.observation.step == diagnostic.step)
        val expansion = actual.expandChoices()
        val admitted = diagnostic.candidateStatistics.map { it.choice }
        require(admitted.size >= 2)
        admitted.forEach { choice -> require(expansion.candidates.singleOrNull { it.signature == choice.signature } == choice) }
        val observed = requireNotNull(diagnostic.chosen)
        require(observed in admitted && replay.decisions[assignment.decisionIndex].choice == observed)
        val prefix = replay.decisions.take(assignment.decisionIndex).map { it.choice }
        return RealGamePositionBankRoot(assignment.rootId, parent.report.runIdentity, game.gameId, assignment.sourcePolicyId,
            policy.descriptor, assignment.pairIndex, assignment.actor, assignment.decisionIndex, game.seed,
            parent.report.plan.baseSeed, replayRelative, requireNotNull(game.replaySha256), replay.terminal.recordDigest,
            PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }), information.informationStateDigest,
            information, MonoRedVisibleFeatures.extract(information, assignment.actor), admitted, expansion.candidates,
            expansion.proposalVersion, expansion.isProfileExhaustive, observed, assignment.seedGroupId,
            assignment.partition, assignment.decisionFamily)
    }
}

private fun authenticateBankReplay(report: SearchTeacherCalibrationReport, game: GameRunResult,
    replay: VerifiedCanonicalSemanticReplay) {
    require(replay.header.gameId == game.gameId && replay.terminal.gameId == game.gameId)
    require(replay.header.producer == "mtgallium-search-teacher" && replay.header.engineVersion == report.sourceProvenance.argentum.revision)
    require(replay.terminal.winnerId == game.winner)
    require(replay.header.requireExtensionString("mtgallium.runIdentity") == report.runIdentity)
    require(replay.header.requireExtensionString("mtgallium.outerCommit") == report.sourceProvenance.outer.revision)
    require(replay.header.requireExtensionString("mtgallium.argentumCommit") == report.sourceProvenance.argentum.revision)
    require(replay.header.requireExtensionString("mtgallium.deckHash") == report.deckHash)
    require(replay.header.requireExtensionString("mtgallium.cardPoolHash") == report.cardPoolHash)
    require(replay.header.requireExtensionLong("mtgallium.gameSeed") == game.seed)
    require(replay.header.requireExtensionLong("mtgallium.baseSeed") == report.plan.baseSeed)
    require(replay.decisions.size == game.decisions)
}
