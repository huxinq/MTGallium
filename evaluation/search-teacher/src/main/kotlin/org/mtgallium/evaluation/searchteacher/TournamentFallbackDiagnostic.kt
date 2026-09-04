package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

private const val TOURNAMENT_FALLBACK_DIAGNOSTIC_VERSION = "tournament-fallback-diagnostic-v1"

@Serializable
internal data class TournamentFallbackDecisionDiagnostic(
    val gameId: String,
    val replayPath: String,
    val decisionIndex: Int,
    val actorId: String,
    val turnNumber: Int,
    val phase: String,
    val step: String,
    val lifeTotals: Map<String, Int>,
    val unavailableReason: String,
    val reasonCodes: List<String>,
    val selectedEngineChoiceClass: String? = null,
    /** Privileged engine payload. This report must remain beneath the work-evidence root. */
    val selectedEngineChoiceDescription: String? = null,
    val candidateEngineChoiceClasses: List<String>,
    val selectedAcceptedBySampledState: Boolean? = null,
    val selectedAcceptedByAuthoritativeState: Boolean? = null,
    val closestCandidateEngineChoices: List<String>,
    val selectedSemanticSignature: String? = null,
    val semanticEquivalentCandidateSignatures: List<String>,
    val proposedCandidateCount: Int,
    val candidateFamilies: List<String>,
    val candidateLabels: List<String>,
    val fallbackChoiceFamily: String,
    val fallbackChoiceLabel: String,
    val fallbackChoiceSignature: String,
)

@Serializable
internal data class TournamentFallbackGameDiagnostic(
    val original: GameRunResult,
    val sourceReplayPath: String,
    val sourceFallbackDecisions: List<TournamentFallbackDecisionDiagnostic>,
    val rerun: GameRunResult,
    val rerunReplayPath: String,
    val rerunFallbackDecisions: List<TournamentFallbackDecisionDiagnostic>,
    val actionSequenceMatched: Boolean,
    val terminalFingerprintMatched: Boolean,
    val fallbackDiagnosisMatched: Boolean,
)

@Serializable
internal data class TournamentFallbackDiagnosticReport(
    val schemaVersion: Int = 1,
    val version: String = TOURNAMENT_FALLBACK_DIAGNOSTIC_VERSION,
    val generatedAtUtc: String,
    val sourceRunIdentity: String,
    val diagnosticRunIdentity: String,
    val sourceFailedGameCount: Int,
    val sourceFallbackCount: Int,
    val reproducedFallbackCount: Int,
    val games: List<TournamentFallbackGameDiagnostic>,
    /** True means the defect reproduced exactly; it does not mean the policy is operationally valid. */
    val reproduced: Boolean,
    val failureReasons: List<String>,
)

internal class TournamentFallbackDiagnosticRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
) {
    private val evidence = EvidenceStore(root)

    fun run(sourceRunIdentity: String, workerThreads: Int): Pair<TournamentFallbackDiagnosticReport, Path> {
        require(workerThreads > 0)
        require(sourceRunIdentity.matches(Regex("[a-f0-9]{64}"))) { "Invalid --source-run identity" }
        val sourceDirectory = evidence.work("tournament/$sourceRunIdentity")
        require(Files.isDirectory(sourceDirectory)) { "Missing source tournament $sourceDirectory" }
        val failedGames = loadFailedGames(sourceDirectory)
        require(failedGames.isNotEmpty()) { "No checkpointed fallback games in $sourceDirectory" }
        val diagnosticIdentity = sha256(
            listOf(
                TOURNAMENT_FALLBACK_DIAGNOSTIC_VERSION,
                sourceRunIdentity,
                currentOuterCommit(),
                currentArgentumCommit(),
                failedGames.joinToString(",") { "${it.gameId}:${it.replaySha256}" },
            ).joinToString(":"),
        )
        val outputDirectory = evidence.diagnostic(
            "tournament-fallback-diagnostic/$diagnosticIdentity",
            "the tournament replacement-behavior diagnostic",
        )
        Files.createDirectories(outputDirectory.resolve("replays"))
        val observer = DiagnosticProgressObserver()

        val games = parallelMapOrdered(failedGames.size, minOf(workerThreads, failedGames.size)) { index ->
            val original = failedGames[index]
            val sourceReplay = resolveReplay(original)
            val sourceLines = readPrivilegedReplay(sourceReplay)
            val header = requireNotNull(sourceLines.first().header)
            val sourceDiagnostics = inspectReplay(sourceReplay)
            check(sourceDiagnostics.size == original.fallbacks) {
                "${original.gameId}: checkpoint reports ${original.fallbacks} fallback(s), " +
                    "but replay-state inspection found ${sourceDiagnostics.size}"
            }
            val p0 = header.p0Policy.toArenaPolicySpec()
            val p1 = header.p1Policy.toArenaPolicySpec()
            val arenaProfile = p0.profile ?: p1.profile ?: SearchTeacherArena.smokeProfile()
            val rerunReplay = outputDirectory.resolve("replays/${header.gameId}.privileged.replay.jsonl.gz")
            val rerun = SearchTeacherArena(registry, manifest, arenaProfile, header.baseSeed).playWithPolicies(
                gameId = header.gameId,
                gameSeed = header.gameSeed,
                p0Policy = p0,
                p1Policy = p1,
                replay = GameReplayOptions(
                    finalPath = rerunReplay,
                    referencePath = root.relativize(rerunReplay).toString(),
                    runIdentity = diagnosticIdentity,
                ),
                progressObserver = observer,
            )
            val rerunDiagnostics = inspectReplay(rerunReplay)
            val rerunLines = readPrivilegedReplay(rerunReplay)
            TournamentFallbackGameDiagnostic(
                original = original,
                sourceReplayPath = root.relativize(sourceReplay).toString(),
                sourceFallbackDecisions = sourceDiagnostics,
                rerun = rerun,
                rerunReplayPath = root.relativize(rerunReplay).toString(),
                rerunFallbackDecisions = rerunDiagnostics,
                actionSequenceMatched = actionSequence(sourceLines) == actionSequence(rerunLines),
                terminalFingerprintMatched = sourceLines.last().terminal?.finalAuthoritativeFingerprint ==
                    rerunLines.last().terminal?.finalAuthoritativeFingerprint,
                fallbackDiagnosisMatched = diagnosticIdentity(sourceDiagnostics) == diagnosticIdentity(rerunDiagnostics),
            )
        }

        val failures = buildList {
            games.filter { !it.rerun.replayVerified }.forEach { add("${it.original.gameId}: rerun replay did not verify") }
            games.filter { it.rerun.fallbacks != it.original.fallbacks }.forEach {
                add("${it.original.gameId}: fallback count changed ${it.original.fallbacks} -> ${it.rerun.fallbacks}")
            }
            games.filter { !it.actionSequenceMatched }.forEach { add("${it.original.gameId}: action sequence changed") }
            games.filter { !it.terminalFingerprintMatched }.forEach {
                add("${it.original.gameId}: terminal authoritative fingerprint changed")
            }
            games.filter { !it.fallbackDiagnosisMatched }.forEach {
                add("${it.original.gameId}: fallback decision or diagnosis changed")
            }
        }
        val report = TournamentFallbackDiagnosticReport(
            generatedAtUtc = Instant.now().toString(),
            sourceRunIdentity = sourceRunIdentity,
            diagnosticRunIdentity = diagnosticIdentity,
            sourceFailedGameCount = failedGames.size,
            sourceFallbackCount = failedGames.sumOf(GameRunResult::fallbacks),
            reproducedFallbackCount = games.sumOf { it.rerun.fallbacks },
            games = games,
            reproduced = failures.isEmpty(),
            failureReasons = failures,
        )
        val reportPath = outputDirectory.resolve("report.json")
        writeJsonAtomically(reportPath, report)
        return report to reportPath
    }

    private fun loadFailedGames(sourceDirectory: Path): List<GameRunResult> =
        Files.walk(sourceDirectory).use { paths ->
            paths.filter { it.fileName.toString().startsWith("pair-") && it.fileName.toString().endsWith(".json") }
                .map { path -> runCatching { evidenceJson.decodeFromString<TournamentPairCheckpoint>(Files.readString(path)) }.getOrNull() }
                .filter { it != null }
                .flatMap { it!!.games.stream() }
                .filter { it.fallbacks > 0 }
                .sorted(Comparator.comparing(GameRunResult::gameId))
                .toList()
                .distinctBy(GameRunResult::gameId)
        }

    private fun resolveReplay(game: GameRunResult): Path {
        val reference = requireNotNull(game.replayPath) { "${game.gameId} has no replay path" }
        val raw = Path.of(reference)
        return (if (raw.isAbsolute) raw else root.resolve(raw)).also {
            require(Files.isRegularFile(it)) { "Missing replay for ${game.gameId}: $it" }
        }
    }

    private fun inspectReplay(path: Path): List<TournamentFallbackDecisionDiagnostic> {
        val lines = readPrivilegedReplay(path)
        val header = requireNotNull(lines.first().header)
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Player 0", manifest.deck()),
                        PlayerConfig("Player 1", manifest.deck()),
                    ),
                    skipMulligans = false,
                    useHandSmoother = false,
                    startingPlayerIndex = 0,
                    seed = header.gameSeed,
                )
            )
        }
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = header.gameId,
            seedBase = header.baseSeed,
            effectiveSetupSeed = header.gameSeed,
           expander = UnifiedSemanticExpander(actionSpaceProfile = header.actionSpaceProfile),
           knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
        )
        val policyKinds = mapOf("p0" to header.p0Policy.kind, "p1" to header.p1Policy.kind)
        return buildList {
            lines.mapNotNull(PrivilegedReplayLine::transition).forEach { transition ->
                check(world.actorToAct() == transition.actorId) {
                    "${header.gameId}: actor mismatch at ${transition.decisionIndex}"
                }
                if (policyKinds.getValue(transition.actorId) == ArenaPolicyKind.HEURISTIC) {
                    val information = world.informationState(transition.actorId)
                    val diagnosis = world.determinizedHeuristicChoiceDiagnosis()
                    if (diagnosis.choice == null) {
                        add(
                            TournamentFallbackDecisionDiagnostic(
                                gameId = header.gameId,
                                replayPath = root.relativize(path).toString(),
                                decisionIndex = transition.decisionIndex,
                                actorId = transition.actorId,
                                turnNumber = information.observation.turnNumber,
                                phase = information.observation.phase,
                                step = information.observation.step,
                                lifeTotals = information.observation.players.associate {
                                    it.playerId to it.life
                                }.toSortedMap(),
                                unavailableReason = requireNotNull(diagnosis.unavailableReason).name,
                                reasonCodes = diagnosis.reasonCodes,
                                selectedEngineChoiceClass = diagnosis.selectedEngineChoiceClass,
                                selectedEngineChoiceDescription = diagnosis.selectedEngineChoiceDescription,
                                candidateEngineChoiceClasses = diagnosis.candidateEngineChoiceClasses,
                                selectedAcceptedBySampledState = diagnosis.selectedAcceptedBySampledState,
                                selectedAcceptedByAuthoritativeState = diagnosis.selectedAcceptedByAuthoritativeState,
                                closestCandidateEngineChoices = diagnosis.closestCandidateEngineChoices,
                                selectedSemanticSignature = diagnosis.selectedSemanticSignature,
                                semanticEquivalentCandidateSignatures = diagnosis.semanticEquivalentCandidateSignatures,
                                proposedCandidateCount = transition.candidates.size,
                                candidateFamilies = transition.candidates.map { it.operationFamily.name }.distinct().sorted(),
                                candidateLabels = transition.candidates.map(ReplayCandidateSummary::label),
                                fallbackChoiceFamily = transition.choice.operationFamily.name,
                                fallbackChoiceLabel = transition.choice.display.label,
                                fallbackChoiceSignature = transition.choice.signature,
                            )
                        )
                    }
                }
                val accepted = world.step(transition.choice)
                check(accepted.accepted) {
                    "${header.gameId}: replay choice rejected at ${transition.decisionIndex}: ${accepted.diagnostic}"
                }
            }
        }
    }

    private fun ReplayPolicyConfiguration.toArenaPolicySpec(): ArenaPolicySpec = ArenaPolicySpec(
        id = id,
        kind = kind,
        profile = profile,
        beliefMode = beliefMode,
        beliefArchitecture = beliefArchitecture,
        searchPlanner = searchPlanner,
        policyCompression = policyCompression,
        searchReuse = searchReuse,
    )

    private fun actionSequence(lines: List<PrivilegedReplayLine>): List<String> =
        lines.mapNotNull(PrivilegedReplayLine::transition).map {
            "${it.actorId}:${it.choice.signature}:${it.choice.canonicalPayload}"
        }

    private fun diagnosticIdentity(diagnostics: List<TournamentFallbackDecisionDiagnostic>): List<String> =
        diagnostics.map { "${it.decisionIndex}:${it.actorId}:${it.unavailableReason}:${it.reasonCodes}" }
}

private class DiagnosticProgressObserver : ArenaProgressObserver {
    override fun gameStarted(gameId: String, p0PolicyId: String, p1PolicyId: String) {
        println("Fallback diagnostic started: $gameId ($p0PolicyId vs $p1PolicyId)")
    }

    override fun decisionCompleted(progress: ArenaDecisionProgress, elapsedMillis: Double) {
        if ((progress.decisionIndex + 1) % 25 == 0) {
            println(
                "Fallback diagnostic progress: ${progress.gameId}; decision=${progress.decisionIndex + 1}; " +
                    "turn=${progress.turnNumber}; ${progress.phase}/${progress.step}; last=${"%.1f".format(elapsedMillis)} ms"
            )
        }
    }

    override fun policyFallback(progress: ArenaPolicyFallbackProgress) {
        val diagnosis = progress.diagnosis
        println(
            "Fallback reproduced: ${progress.gameId}; decision=${progress.decisionIndex}; actor=${progress.actor}; " +
                "reason=${diagnosis.unavailableReason}; codes=${diagnosis.reasonCodes}; " +
                "selected=${diagnosis.selectedEngineChoiceClass}:${diagnosis.selectedEngineChoiceDescription}"
        )
    }

    override fun gameFinished(result: GameRunResult) {
        println(
            "Fallback diagnostic finished: ${result.gameId}; decisions=${result.decisions}; " +
                "fallbacks=${result.fallbacks}; winner=${result.winner}; elapsed=${result.elapsedMillis?.let { "%.0f".format(it) }} ms"
        )
    }
}
