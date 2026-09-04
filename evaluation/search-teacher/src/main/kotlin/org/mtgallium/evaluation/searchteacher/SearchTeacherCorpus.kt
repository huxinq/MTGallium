package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord
import org.mtgallium.agent.infoset.core.PolicyTrajectoryCompletion
import org.mtgallium.agent.infoset.core.PolicyTrajectoryOutcome
import org.mtgallium.agent.infoset.core.PLANNER_EVIDENCE_SCHEMA_CURRENT
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings

@Serializable
internal data class CorpusQuarantineAttemptReport(
    val schemaVersion: Int = 1,
    val documentKind: String = "work-only-corpus-attempt-accounting-v1",
    val generatedAtUtc: String,
    val profileId: String,
    val baseSeed: Long,
    val requestedGames: Int,
    val recordedAttempts: Int,
    val dispositionCounts: Map<GameRunDisposition, Int>,
    val reachedStops: Int,
    val refusedStops: Int,
    val degradedStops: Int,
    val attempts: List<EvidenceRunAttemptSummary>,
)

internal class SearchTeacherCorpus(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val profile: FrozenSearchProfile,
    private val baseSeed: Long,
) {
    private val arena = SearchTeacherArena(registry, manifest, profile, baseSeed)

    fun generate(gameCount: Int, workerThreads: Int): CorpusManifest {
        require(gameCount > 0)
        val profileHash = sha256(evidenceJson.encodeToString(profile))
        val provenance = org.mtgallium.evaluation.searchteacher.evidence.RunProvenance.capture(root)
        provenance.requireReady()
        val outerCommit = provenance.outerCommit
        val argentumCommit = provenance.checkedOutArgentumCommit
        val sourceProvenance = requireNotNull(provenance.sourceProvenance)
        val researchRunIdentity = ResearchRunBindings(
            protocol = "search-teacher-corpus-planner-evidence-v1",
            material = mapOf(
                "profile-hash" to profileHash,
                "requested-games" to gameCount.toString(),
                "source-provenance" to PolicyJson.digest(
                    PolicyJson.format.encodeToJsonElement(PolicySourceProvenance.serializer(), sourceProvenance)
                ),
                "planner-evidence-schema" to PLANNER_EVIDENCE_SCHEMA_CURRENT.toString(),
            ),
        ).identity
        // Every attempted corpus game starts in a work-only quarantine.  A stopped game can contain
        // pre-stop choices, but those choices must never become an eligible training corpus label.
        val store = EvidenceStore(root)
        val directory = store.diagnostic(
            "corpus-quarantine/${profile.id}-$baseSeed",
            "the work-only corpus quarantine",
        )
        val entries = parallelMapOrdered(gameCount, workerThreads) { index ->
            val gameId = "teacher-corpus-${index.toString().padStart(6, '0')}"
            val seed = ComponentSeeds.derive(baseSeed, index, "corpus-game")
            val publicPath = directory.resolve("public/$gameId.jsonl.gz")
            val plannerPath = directory.resolve("public/planner/$gameId.planner.json.gz")
            val debugPath = directory.resolve("privileged/$gameId.privileged.jsonl.gz")
            val replayPath = directory.resolve("privileged/$gameId.privileged.replay.jsonl.gz")
            val searchOnPlay = index % 2 == 0
            val game = arena.play(
                gameId = gameId,
                gameSeed = seed,
                p0Policy = if (searchOnPlay) ArenaPolicyKind.SEARCH else ArenaPolicyKind.HEURISTIC,
                p1Policy = if (searchOnPlay) ArenaPolicyKind.HEURISTIC else ArenaPolicyKind.SEARCH,
                evidence = GameEvidenceOptions(
                    publicTrajectory = publicPath,
                    plannerEvidence = plannerPath,
                    publicTrajectoryReference = root.relativize(publicPath).toString(),
                    researchRunIdentity = researchRunIdentity,
                    privilegedDebug = debugPath,
                    outerCommit = outerCommit,
                    argentumCommit = argentumCommit,
                    profileHash = profileHash,
                    sourceProvenance = sourceProvenance,
                ),
                replay = GameReplayOptions(
                    finalPath = replayPath,
                    referencePath = root.relativize(replayPath).toString(),
                    runIdentity = "corpus-v5-${arena.runIdentity}",
                    outerCommit = outerCommit,
                    argentumCommit = argentumCommit,
                ),
            )
            val replay = if (game.terminal && Files.exists(replayPath)) {
                verifyCanonicalReplay(replayPath)
            } else {
                ReplayVerification(false, game.exception ?: "game did not produce a terminal canonical replay")
            }
            val publicHeader = publicPath.takeIf(Files::exists)?.let { path ->
                runCatching {
                    val first = readGzipLines(path).first()
                    PolicyJson.format.decodeFromString(PolicyTrajectoryRecord.serializer(), first)
                        as PolicyTrajectoryHeader
                }.getOrNull()
            }
            CorpusEntry(
                gameId = gameId,
                publicTrajectory = root.relativize(publicPath).toString(),
                publicSha256 = publicPath.takeIf(Files::exists)?.let(::sha256File),
                publicSizeBytes = publicPath.takeIf(Files::exists)?.let(Files::size) ?: 0L,
                policyEvidenceIdentity = publicHeader?.behaviorBinding?.identity,
                behaviorSpecificationSha256 =
                    publicHeader?.behaviorBinding?.behaviorSpecificationSha256,
                plannerEvidence = plannerPath.takeIf(Files::exists)?.let { path ->
                    PlannerEvidenceArtifact(
                        reference = root.relativize(path).toString(),
                        sha256 = sha256File(path),
                        sizeBytes = Files.size(path),
                        schemaVersion = PLANNER_EVIDENCE_SCHEMA_CURRENT,
                    )
                },
                replayVerified = replay.verified,
                game = game.toCorpusGameSummary(),
            )
        }
        val passed = entries.all { entry ->
            entry.game.disposition == GameRunDisposition.GAME_ENDED && entry.game.terminal &&
                entry.game.informationLedgerComplete && entry.game.unsupportedInformationEvents.isEmpty() &&
                entry.game.evidenceStop == null && entry.game.failureCategory == null &&
                entry.game.illegalResponses == 0 && entry.game.fallbacks == 0 && !entry.game.stepLimit &&
                entry.replayVerified
        }
        val terminalGames = entries.count { it.game.terminal }
        val replayVerifiedGames = entries.count { it.replayVerified }
        val attemptSummaries = entries.map { entry ->
            val game = entry.game
            EvidenceRunAttemptSummary(
                gameId = game.gameId,
                disposition = game.disposition,
                terminal = game.terminal,
                decisions = game.decisions,
                evidenceStop = game.evidenceStop?.let { stop ->
                    EvidenceRunStopSummary(
                        disposition = game.disposition,
                        triggerCodes = stop.triggerCodes,
                        affectedViewers = stop.affectedViewers,
                        detectionPoint = stop.detectionPoint,
                        triggeringDecisionIndex = stop.triggeringDecisionIndex,
                        refusedPolicyDecisionIndex = stop.refusedPolicyDecisionIndex,
                        reached = stop.accounting.reached,
                        refused = stop.accounting.refused,
                        degraded = stop.accounting.degraded,
                    )
                },
                failureCategory = game.failureCategory,
            )
        }
        val stops = attemptSummaries.mapNotNull { it.evidenceStop }
        store.writeEncoded(
            directory.resolve("attempt-report.json"),
            evidenceJson.encodeToString(
                CorpusQuarantineAttemptReport(
                    generatedAtUtc = Instant.now().toString(),
                    profileId = profile.id,
                    baseSeed = baseSeed,
                    requestedGames = gameCount,
                    recordedAttempts = attemptSummaries.size,
                    dispositionCounts = attemptSummaries.groupingBy { it.disposition }.eachCount().toSortedMap(),
                    reachedStops = stops.sumOf { it.reached },
                    refusedStops = stops.sumOf { it.refused },
                    degradedStops = stops.sumOf { it.degraded },
                    attempts = attemptSummaries,
                ),
            ) + "\n",
        )
        val datasetIdentity = CorpusManifest.computeDatasetIdentity(
            profileId = profile.id,
            profileHash = profileHash,
            sourceProvenance = sourceProvenance,
            requestedGames = gameCount,
            terminalGames = terminalGames,
            replayVerifiedGames = replayVerifiedGames,
            entries = entries,
            passed = passed,
        )
        val corpus = CorpusManifest(
            generatedAtUtc = Instant.now().toString(),
            profileId = profile.id,
            profileHash = profileHash,
            outerCommit = outerCommit,
            argentumCommit = argentumCommit,
            sourceProvenance = sourceProvenance,
            requestedGames = gameCount,
            terminalGames = terminalGames,
            replayVerifiedGames = replayVerifiedGames,
            entries = entries,
            passed = passed,
            datasetIdentity = datasetIdentity,
        )
        ResearchRunArtifacts(directory, researchRunIdentity).also { artifacts ->
            Files.walk(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .map { directory.relativize(it).toString() }
                    .filter { it != ResearchRunArtifacts.MANIFEST_FILE }
                    .sorted()
                    .forEach(artifacts::register)
            }
        }.finalize()
        return corpus
    }

    internal fun verifyExisting(publicPath: Path, companionPath: Path): ReplayVerification =
        if (companionPath.fileName.toString().endsWith(".privileged.replay.jsonl.gz")) {
            verifyCanonicalReplay(companionPath)
        } else {
            verifyReplay(publicPath, companionPath)
        }

    private fun verifyCanonicalReplay(replayPath: Path): ReplayVerification {
        val result = CanonicalTournamentReplayVerifier.verify(replayPath)
        return ReplayVerification(result.verified, result.diagnostic)
    }

    private fun verifyReplay(publicPath: Path, debugPath: Path): ReplayVerification = runCatching {
        val records = readGzipLines(publicPath).map { line ->
            PolicyJson.format.decodeFromString(PolicyTrajectoryRecord.serializer(), line)
        }
        val header = records.firstOrNull() as? PolicyTrajectoryHeader
            ?: error("trajectory does not begin with a header")
        val outcome = records.lastOrNull() as? PolicyTrajectoryOutcome
            ?: error("trajectory does not end with an outcome record")
        require(outcome.completion == PolicyTrajectoryCompletion.GAME_ENDED) {
            "trajectory stopped before the game ended: ${outcome.stopReason}"
        }
        require(outcome.decisions == outcome.semanticResponseSequence.size)
        val debug = readGzipLines(debugPath).map { PolicyJson.format.decodeFromString<PrivilegedDebugLine>(it) }
        require(debug.size == outcome.decisions * 2) {
            "expected ${outcome.decisions * 2} privileged lines, found ${debug.size}"
        }
        val replaySeeds = privilegedReplaySeeds(debug)

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
                seed = replaySeeds.gameSeed,
            )
        )
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = header.gameId,
            seedBase = replaySeeds.searchBaseSeed,
            effectiveSetupSeed = replaySeeds.gameSeed,
           expander = UnifiedSemanticExpander(actionSpaceProfile = header.actionSpaceProfile),
           knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
        )
        debug.chunked(2).forEachIndexed { index, pair ->
            val choice = requireNotNull(pair[1].chosenChoice) { "privileged replay choice missing at $index" }
            val before = debug[index * 2]
            val after = debug[index * 2 + 1]
            require(before.decisionIndex == index && before.chosenChoice == null)
            require(after.decisionIndex == index && after.chosenChoice == choice)
            require(world.privilegedDebugSnapshot() == before.snapshot) { "pre-step digest mismatch at $index" }
            val diagnosticRoot = world.fork() as ArgentumSearchWorld
            val step = world.step(choice)
            require(step.accepted) { "replay rejected decision $index: ${step.diagnostic}" }
            val actualAfter = world.privilegedDebugSnapshot()
            require(actualAfter == after.snapshot) {
                val highExpansion = diagnosticRoot.fork() as ArgentumSearchWorld
                highExpansion.expandChoices(2_048)
                val highStep = highExpansion.step(choice)
                val highSnapshot = highExpansion.privilegedDebugSnapshot()
                "post-step digest mismatch at $index; expected=${after.snapshot.authoritativeSemanticDigest}, " +
                    "default=${actualAfter.authoritativeSemanticDigest}, " +
                    "highExpansionAccepted=${highStep.accepted}, " +
                    "highExpansion=${highSnapshot.authoritativeSemanticDigest}, " +
                    "hiddenEqual=${actualAfter.hiddenHands == after.snapshot.hiddenHands &&
                        actualAfter.libraries == after.snapshot.libraries}"
            }
        }
        require(world.terminalPayoff("p0") != null) { "replayed trajectory is not terminal" }
        ReplayVerification(true, null)
    }.getOrElse { error -> ReplayVerification(false, "${error::class.simpleName}: ${error.message}") }
}

internal data class ReplayVerification(val verified: Boolean, val diagnostic: String?)

internal data class PrivilegedReplaySeeds(val gameSeed: Long, val searchBaseSeed: Long)

internal fun privilegedReplaySeeds(debug: List<PrivilegedDebugLine>): PrivilegedReplaySeeds {
    val trace = debug.firstOrNull()?.snapshot?.chanceTrace ?: error("privileged replay trace is empty")
    fun value(prefix: String): Long = trace.singleOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)?.toLongOrNull()
        ?: error("privileged replay trace has no unique $prefix entry")
    return PrivilegedReplaySeeds(
        gameSeed = value("initial-seed:"),
        searchBaseSeed = value("search-base-seed:"),
    )
}

private fun readGzipLines(path: Path): List<String> = GZIPInputStream(Files.newInputStream(path)).bufferedReader().use {
    it.readLines()
}

internal fun privilegedDebugPath(publicPath: Path): Path {
    val fileName = publicPath.fileName.toString()
    require(fileName.endsWith(".jsonl.gz")) { "Unexpected public trajectory path $publicPath" }
    val gameId = fileName.removeSuffix(".jsonl.gz")
    val corpusRoot = requireNotNull(publicPath.parent?.parent) { "Public trajectory has no corpus root: $publicPath" }
    return corpusRoot.resolve("privileged/$gameId.privileged.jsonl.gz")
}

internal fun privilegedCanonicalReplayPath(publicPath: Path): Path {
    val fileName = publicPath.fileName.toString()
    require(fileName.endsWith(".jsonl.gz")) { "Unexpected public trajectory path $publicPath" }
    val gameId = fileName.removeSuffix(".jsonl.gz")
    val corpusRoot = requireNotNull(publicPath.parent?.parent) { "Public trajectory has no corpus root: $publicPath" }
    return corpusRoot.resolve("privileged/$gameId.privileged.replay.jsonl.gz")
}
