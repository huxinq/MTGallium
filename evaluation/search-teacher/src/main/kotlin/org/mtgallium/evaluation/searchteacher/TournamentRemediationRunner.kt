package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.MulliganStateComponent
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

private const val TOURNAMENT_REMEDIATION_VERSION = "stopped-run-remediation-v4-searchable-land-holds"

/** Seeds whose stopped-run ledgers first exposed cleanup hand-size discards. */
internal val STOPPED_RUN_CLEANUP_SEEDS = listOf(
    -1_251_887_670_764_137_565L,
    6_818_463_127_811_328_205L,
    -1_578_955_385_839_793_753L,
)

@Serializable
internal data class PrivilegedOpeningDiagnostic(
    val decisionIndex: Int,
    val actorId: String,
    val choiceLabel: String,
    val selectionKind: SearchTeacherSelectionKind?,
    val mulligansTaken: Int,
    val handSize: Int,
    val landCount: Int,
    val matchesSharedPregameRule: Boolean?,
)

@Serializable
internal data class PrivilegedCleanupDiagnostic(
    val afterDecisionIndex: Int,
    val turnNumber: Int,
    val playerId: String,
    val handSize: Int,
    val landsInHand: Int,
    val landsOnBattlefield: Int,
    val keptOpeningLandCount: Int?,
    val lowLandKeepExplanation: Boolean,
    /** A searched land hold earlier in this turn; descriptive evidence, not an invalid action. */
    val landAvailablePassDecisionIndex: Int? = null,
)

@Serializable
internal data class PrivilegedProactivePassDiagnostic(
    val decisionIndex: Int,
    val turnNumber: Int,
    val actorId: String,
    val handSize: Int,
    val landsInHand: Int,
    val landsOnBattlefield: Int,
    val candidateFamilies: Set<SemanticOperationFamily>,
    val landPlayAvailable: Boolean,
)

@Serializable
internal data class PrivilegedNondevelopmentSequenceDiagnostic(
    val actorId: String,
    val turnNumbers: List<Int>,
    val decisionIndices: List<Int>,
    val keptOpeningLandCount: Int?,
)

@Serializable
internal data class TournamentRemediationGame(
    val game: GameRunResult,
    val replayPath: String,
    val opening: List<PrivilegedOpeningDiagnostic>,
    val cleanup: List<PrivilegedCleanupDiagnostic>,
    val proactiveMainPhasePasses: List<PrivilegedProactivePassDiagnostic>,
    val repeatedZeroLandNondevelopment: List<PrivilegedNondevelopmentSequenceDiagnostic>,
)

@Serializable
internal data class TournamentRemediationReport(
    val schemaVersion: Int = 2,
    val version: String = TOURNAMENT_REMEDIATION_VERSION,
    val generatedAtUtc: String,
    val stoppedRunIdentity: String = STOPPED_TOURNAMENT_RUN_ID,
    val outerCommit: String,
    val argentumCommit: String,
    val deckHash: String,
    val seeds: List<Long>,
    val games: List<TournamentRemediationGame>,
    val cleanupDiscardCount: Int,
    val lowLandExplainedCleanupDiscardCount: Int,
    val unexplainedCleanupDiscardCount: Int,
    val cleanupAfterLandAvailablePassCount: Int,
    val proactiveMainPhasePassCount: Int,
    val proactiveMainPhasePassWithLandCount: Int,
    val repeatedZeroLandNondevelopmentSequenceCount: Int,
    val rejectedSearchTransitionCount: Int,
    val passed: Boolean,
    val failureReasons: List<String>,
)

@Serializable
internal data class TournamentReplayAuditEntry(
    val replayPath: String,
    val verified: Boolean,
    val decisions: Int,
    val diagnostic: String? = null,
)

@Serializable
internal data class TournamentReplayAuditReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val remediationVersion: String = TOURNAMENT_REMEDIATION_VERSION,
    val runIdentity: String,
    val entries: List<TournamentReplayAuditEntry>,
)

@Serializable
internal data class TournamentRemediationProbeReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val runIdentity: String,
    val game: GameRunResult,
)

/**
 * A bounded six-game diagnostic, not a tournament. It regenerates only the three implicated seed
 * pairs with exact privileged replays and independently inspects their opening and cleanup states.
 */
internal class TournamentRemediationRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val baseSeed: Long,
) {
    private val evidence = EvidenceStore(root)

    fun run(): TournamentRemediationReport {
        val policies = CoreSixRoster.policies()
        val first = policies.single {
            it.id == "search-current_information_state-mtgallium_visible_v2"
        }
        val second = policies.single {
            it.id == "search-current_sampled_world-mtgallium_visible_v2"
        }
        val descriptors = STOPPED_RUN_CLEANUP_SEEDS.flatMapIndexed { seedIndex, seed ->
            listOf(
                DiagnosticDescriptor("seed-$seedIndex-a", seed, first, second),
                DiagnosticDescriptor("seed-$seedIndex-b", seed, second, first),
            )
        }
        val identity = runIdentity()
        val directory = runDirectory(identity)
        Files.createDirectories(directory.resolve("replays"))
        val arena = SearchTeacherArena(registry, manifest, requireNotNull(first.profile), baseSeed)
        val games = parallelMapOrdered(descriptors.size, descriptors.size) { index ->
            val descriptor = descriptors[index]
            val finalPath = directory.resolve(
                "replays/${descriptor.gameId}.privileged.replay.jsonl.gz"
            )
            val result = arena.playWithPolicies(
                gameId = "remediation-${descriptor.gameId}",
                gameSeed = descriptor.seed,
                p0Policy = descriptor.p0,
                p1Policy = descriptor.p1,
                replay = GameReplayOptions(
                    finalPath = finalPath,
                    referencePath = root.relativize(finalPath).toString(),
                    runIdentity = identity,
                ),
            )
            val inspection = inspectPrivilegedReplay(finalPath)
            TournamentRemediationGame(
                game = result,
                replayPath = root.relativize(finalPath).toString(),
                opening = inspection.opening,
                cleanup = inspection.cleanup,
                proactiveMainPhasePasses = inspection.proactivePasses,
                repeatedZeroLandNondevelopment = inspection.repeatedNondevelopment,
            )
        }
        val cleanup = games.flatMap(TournamentRemediationGame::cleanup)
        val proactivePasses = games.flatMap(TournamentRemediationGame::proactiveMainPhasePasses)
        val repeatedNondevelopment = games.flatMap(TournamentRemediationGame::repeatedZeroLandNondevelopment)
        val rejectedSearchTransitionCount = games.sumOf { game ->
            game.game.seatDiagnostics.values.sumOf { seat ->
                seat.searchDecisionsDetail.sumOf { it.searchDiagnostics.rejectedTransitions }
            }
        }
        val failures = buildList {
            games.filter { game ->
                val result = game.game
                !result.terminal || result.stepLimit || result.exception != null ||
                    result.illegalResponses != 0 || result.fallbacks != 0 ||
                    !result.informationLedgerComplete || !result.replayVerified
            }.forEach { add("${it.game.gameId}: invalid game or replay") }
            games.forEach { game ->
                val searchActors = buildSet {
                    if (game.game.p0Policy == ArenaPolicyKind.SEARCH) add("p0")
                    if (game.game.p1Policy == ArenaPolicyKind.SEARCH) add("p1")
                }
                game.opening.filter { opening ->
                    opening.actorId in searchActors && opening.choiceLabel.isKeepOrMulligan() &&
                        opening.selectionKind != SearchTeacherSelectionKind.SEARCHED
                }.forEach {
                    add("${game.game.gameId}: search pregame decision ${it.decisionIndex} bypassed declared search policy")
                }
            }
            cleanup.filter { it.landAvailablePassDecisionIndex != null }.forEach {
                add(
                    "cleanup discard for ${it.playerId} after searched land hold " +
                        "${it.landAvailablePassDecisionIndex} on turn ${it.turnNumber}"
                )
            }
            repeatedNondevelopment.forEach {
                add(
                    "repeated zero-land nondevelopment for ${it.actorId} on turns " +
                        it.turnNumbers.joinToString(",")
                )
            }
            if (rejectedSearchTransitionCount != 0) {
                add("search attempted $rejectedSearchTransitionCount rejected semantic transitions")
            }
        }
        return TournamentRemediationReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            deckHash = manifest.deckHash(),
            seeds = STOPPED_RUN_CLEANUP_SEEDS,
            games = games,
            cleanupDiscardCount = cleanup.size,
            lowLandExplainedCleanupDiscardCount = cleanup.count { it.lowLandKeepExplanation },
            unexplainedCleanupDiscardCount = cleanup.count { !it.lowLandKeepExplanation },
            cleanupAfterLandAvailablePassCount = cleanup.count { it.landAvailablePassDecisionIndex != null },
            proactiveMainPhasePassCount = proactivePasses.size,
            proactiveMainPhasePassWithLandCount = proactivePasses.count { it.landPlayAvailable },
            repeatedZeroLandNondevelopmentSequenceCount = repeatedNondevelopment.size,
            rejectedSearchTransitionCount = rejectedSearchTransitionCount,
            passed = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    fun auditExistingReplays(): TournamentReplayAuditReport {
        val identity = runIdentity()
        val replayDirectory = runDirectory(identity)
        require(Files.isDirectory(replayDirectory)) { "No remediation replays at $replayDirectory" }
        val verifier = TournamentReplayVerifier(registry, manifest)
        val entries = Files.walk(replayDirectory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".privileged.replay.jsonl.gz") }
                .sorted()
                .map { path ->
                    val result = verifier.verify(path)
                    TournamentReplayAuditEntry(
                        replayPath = root.relativize(path).toString(),
                        verified = result.verified,
                        decisions = result.decisions,
                        diagnostic = result.diagnostic,
                    )
                }
                .toList()
        }
        return TournamentReplayAuditReport(
            generatedAtUtc = Instant.now().toString(),
            runIdentity = identity,
            entries = entries,
        )
    }

    /** One previously divergent leg for localizing replay state; never a tournament run. */
    fun runReplayProbe(): TournamentRemediationProbeReport {
        val policies = CoreSixRoster.policies()
        val first = policies.single {
            it.id == "search-current_information_state-mtgallium_visible_v2"
        }
        val second = policies.single {
            it.id == "search-current_sampled_world-mtgallium_visible_v2"
        }
        val identity = runIdentity()
        val finalPath = runDirectory(identity).resolve(
            "probes/seed-1-a.privileged.replay.jsonl.gz"
        )
        val arena = SearchTeacherArena(registry, manifest, requireNotNull(first.profile), baseSeed)
        val game = arena.playWithPolicies(
            gameId = "remediation-probe-seed-1-a",
            gameSeed = STOPPED_RUN_CLEANUP_SEEDS[1],
            p0Policy = first,
            p1Policy = second,
            replay = GameReplayOptions(
                finalPath = finalPath,
                referencePath = root.relativize(finalPath).toString(),
                runIdentity = identity,
            ),
        )
        return TournamentRemediationProbeReport(
            generatedAtUtc = Instant.now().toString(),
            runIdentity = identity,
            game = game,
        )
    }

    private fun runIdentity(): String = sha256(
        listOf(
            TOURNAMENT_REMEDIATION_VERSION,
            currentOuterCommit(),
            currentArgentumCommit(),
            manifest.deckHash(),
            baseSeed,
            STOPPED_RUN_CLEANUP_SEEDS.joinToString(","),
        ).joinToString(":"),
    )

    private fun runDirectory(identity: String): Path =
        evidence.diagnostic(
            "tournament-remediation/$identity",
            "the tournament remediation replay bundle",
        )

    private fun inspectPrivilegedReplay(path: Path): PrivilegedInspection {
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
            cardRegistry = registry,
            knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
        )
        val opening = mutableListOf<PrivilegedOpeningDiagnostic>()
        val cleanup = mutableListOf<PrivilegedCleanupDiagnostic>()
        val proactive = mutableListOf<PrivilegedProactivePassDiagnostic>()
        val keptLandCount = mutableMapOf<String, Int>()
        lines.mapNotNull(PrivilegedReplayLine::transition).forEach { transition ->
            val stateBefore = world.authoritativeStateForHost()
            val rawActor = stateBefore.turnOrder[transition.actorId.removePrefix("p").toInt()]
            val turnNumber = world.informationState(transition.actorId).observation.turnNumber
            if (transition.choice.operationFamily == SemanticOperationFamily.MULLIGAN) {
                val mulligans = stateBefore.getEntity(rawActor)?.get<MulliganStateComponent>()
                    ?.mulligansTaken ?: 0
                val lands = stateBefore.getHand(rawActor).count { id -> isLand(stateBefore, id) }
                val label = transition.choice.display.label
                val matches = when {
                    label.isKeep() -> mulligans >= 2 || lands in 2..5
                    label.isTakeMulligan() -> mulligans < 2 && lands !in 2..5
                    else -> null
                }
                opening += PrivilegedOpeningDiagnostic(
                    decisionIndex = transition.decisionIndex,
                    actorId = transition.actorId,
                    choiceLabel = label,
                    selectionKind = transition.selectionKind,
                    mulligansTaken = mulligans,
                    handSize = stateBefore.getHand(rawActor).size,
                    landCount = lands,
                    matchesSharedPregameRule = matches,
                )
                if (label.isKeep()) keptLandCount[transition.actorId] = lands
            }
            if (transition.mainPhasePassWithProactiveOption) {
                val hand = stateBefore.getHand(rawActor)
                val battlefield = stateBefore.getBattlefield(rawActor)
                val families = transition.candidates.map { it.operationFamily }.toSet()
                proactive += PrivilegedProactivePassDiagnostic(
                    decisionIndex = transition.decisionIndex,
                    turnNumber = turnNumber,
                    actorId = transition.actorId,
                    handSize = hand.size,
                    landsInHand = hand.count { id -> isLand(stateBefore, id) },
                    landsOnBattlefield = battlefield.count { id -> isLand(stateBefore, id) },
                    candidateFamilies = families,
                    landPlayAvailable = SemanticOperationFamily.PLAY_LAND in families,
                )
            }
            check(world.step(transition.choice).accepted)
            transition.cleanupDiscardPlayerIds.forEach { playerId ->
                val stateAfter = world.authoritativeStateForHost()
                val rawPlayer = stateAfter.turnOrder[playerId.removePrefix("p").toInt()]
                val kept = keptLandCount[playerId]
                val priorLandHold = proactive.lastOrNull { pass ->
                    pass.actorId == playerId && pass.turnNumber == turnNumber &&
                        pass.landPlayAvailable && pass.landsInHand > 0 && pass.landsOnBattlefield == 0
                }
                cleanup += PrivilegedCleanupDiagnostic(
                    afterDecisionIndex = transition.decisionIndex,
                    turnNumber = turnNumber,
                    playerId = playerId,
                    handSize = stateAfter.getHand(rawPlayer).size,
                    landsInHand = stateAfter.getHand(rawPlayer).count { id -> isLand(stateAfter, id) },
                    landsOnBattlefield = stateAfter.getBattlefield(rawPlayer).count { id -> isLand(stateAfter, id) },
                    keptOpeningLandCount = kept,
                    lowLandKeepExplanation = kept != null && kept <= 2,
                    landAvailablePassDecisionIndex = priorLandHold?.decisionIndex,
                )
            }
        }
        val repeatedNondevelopment = proactive.groupBy(PrivilegedProactivePassDiagnostic::actorId)
            .mapNotNull { (actorId, actorPasses) ->
                val zeroLandHolds = actorPasses.filter { pass ->
                    pass.landPlayAvailable && pass.landsInHand > 0 && pass.landsOnBattlefield == 0
                }
                val turns = zeroLandHolds.map(PrivilegedProactivePassDiagnostic::turnNumber).distinct()
                if (turns.size < 2) return@mapNotNull null
                PrivilegedNondevelopmentSequenceDiagnostic(
                    actorId = actorId,
                    turnNumbers = turns,
                    decisionIndices = zeroLandHolds.map(PrivilegedProactivePassDiagnostic::decisionIndex),
                    keptOpeningLandCount = keptLandCount[actorId],
                )
            }
        return PrivilegedInspection(opening, cleanup, proactive, repeatedNondevelopment)
    }

    private fun isLand(
        state: com.wingedsheep.engine.state.GameState,
        id: com.wingedsheep.sdk.model.EntityId,
    ): Boolean {
        val stateName = state.getEntity(id)?.get<CardComponent>()?.name
        // Tokens and copied/transformed runtime objects need not have a standalone registry entry.
        return stateName != null && registry.getCard(stateName)?.isLand == true
    }

    private data class DiagnosticDescriptor(
        val gameId: String,
        val seed: Long,
        val p0: ArenaPolicySpec,
        val p1: ArenaPolicySpec,
    )

    private data class PrivilegedInspection(
        val opening: List<PrivilegedOpeningDiagnostic>,
        val cleanup: List<PrivilegedCleanupDiagnostic>,
        val proactivePasses: List<PrivilegedProactivePassDiagnostic>,
        val repeatedNondevelopment: List<PrivilegedNondevelopmentSequenceDiagnostic>,
    )
}

private fun String.isKeep(): Boolean = contains("keep", ignoreCase = true)
private fun String.isTakeMulligan(): Boolean = contains("mulligan", ignoreCase = true)
private fun String.isKeepOrMulligan(): Boolean = isKeep() || isTakeMulligan()
