package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mtgallium.agent.infoset.argentum.ArgentumRawTransition
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.MonoRedInformationEvaluator
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.evaluation.searchteacher.replay.*
import org.mtgallium.research.run.*

@Tag("public-source")
class FactualIncumbentTrajectoryTest {
    @Test
    fun `factual projection retains every boundary history exact knowledge V2 and same viewer payoff`() = withFixture { f ->
        val replay = readVerifiedCanonicalSemanticReplay(f.replay)
        for (viewer in listOf("p0", "p1")) {
            val (rows, audit) = projectFactualIncumbentTrajectory(replay, viewer, FixtureWorld(f))
            assertEquals(listOf(0, 1), rows.map { it.decisionIndex })
            assertEquals(listOf("p0", "p1"), rows.map { it.information.actingPlayerId })
            assertEquals(List(2) { if (viewer == "p0") 1.0 else -1.0 }, rows.map { it.actualTerminalPayoff })
            rows.forEachIndexed { index, row ->
                assertEquals(f.information(viewer, index), row.information)
                assertEquals(MonoRedInformationEvaluator.evaluate(row.information, viewer), row.v2Value)
                assertTrue(row.v2Value != 0.0)
            }
            assertEquals(listOf(PolicyKnownLibraryOrder(viewer, 0, top = listOf("Mountain"))), rows[1].information.knowledge.knownLibraryOrders)
            assertEquals(1, rows[1].information.history.size)
            assertFalse(evidenceJson.encodeToString(rows).contains("\"e0\""))
            assertEquals(0, audit.legacyTimeLordTypeLineNormalizationCount)
        }
    }

    @Test
    fun `wrong perspective unsupported late frame nonterminal and wrong payoff cannot yield rows`() = withFixture { f ->
        val replay = readVerifiedCanonicalSemanticReplay(f.replay)
        assertFails { projectFactualIncumbentTrajectory(replay, "referee", FixtureWorld(f)) }
        assertFails { projectFactualIncumbentTrajectory(replay, "p0", FixtureWorld(f, wrongViewer = true)) }
        assertFails { projectFactualIncumbentTrajectory(replay, "p0", FixtureWorld(f, unsupportedAt = 1)) }
        assertFails { projectFactualIncumbentTrajectory(replay, "p0", FixtureWorld(f, unsupportedAt = 2)) }
        assertFails { projectFactualIncumbentTrajectory(replay, "p0", FixtureWorld(f, terminal = false)) }
        assertFails { projectFactualIncumbentTrajectory(replay, "p1", FixtureWorld(f, wrongPayoff = true)) }
        assertFails { projectFactualIncumbentTrajectory(replay.copy(terminal = replay.terminal.copy(
            status = ReplayCompletionStatus.INCOMPLETE, incompleteReason = ReplayIncompleteReason.INTERRUPTED)), "p0", FixtureWorld(f)) }
        assertFails { projectFactualIncumbentTrajectory(replay.copy(header = replay.header.copy(engineVersion = "wrong")), "p0", FixtureWorld(f)) }
        val changed = replay.copy(states = replay.states.mapIndexed { index, state -> if (index == 1) state.copy(turnNumber = 8) else state })
        assertFails { projectFactualIncumbentTrajectory(changed, "p0", FixtureWorld(f)) }
    }

    @Test
    fun `late row privacy refusal preserves the array path`() = withFixture { f ->
        val failure = assertFailsWith<IllegalArgumentException> {
            projectFactualIncumbentTrajectory(readVerifiedCanonicalSemanticReplay(f.replay), "p0",
                FixtureWorld(f, privacyLeakAt = 1))
        }
        assertTrue(requireNotNull(failure.message).contains("$[1].information.observation.phase"))
    }

    @Test
    fun `source admission binds viewer plan policy source engine and group`() = withFixture { f ->
        val request = f.request()
        requireFactualIncumbentSource(request, f.report, f.source, DECK, POOL)
        assertFails { request.copy(viewer = "other") }
        assertFails { requireFactualIncumbentSource(request.copy(sourceRunIdentity = "wrong"), f.report, f.source, DECK, POOL) }
        assertFails { requireFactualIncumbentSource(request.copy(sourceProvenance = f.source.copy(
            outer = f.source.outer.copy(revision = "other"))), f.report, f.source, DECK, POOL) }
        assertFails { requireFactualIncumbentSource(request.copy(calibrationPlan = f.plan.copy(
            control = f.plan.control.copy(maxPolicyDecisions = 17))), f.report, f.source, DECK, POOL) }
        assertFails { requireFactualIncumbentSource(request, f.report, f.source.copy(
            expectedArgentumRevision = "wrong", argentum = f.source.argentum.copy(revision = "wrong")), DECK, POOL) }
        assertFails { requireFactualIncumbentSource(request, f.report, f.source, "other-deck", POOL) }
        requireFactualIncumbentGame(request, f.pair, f.game, DECK, POOL)
        val wrongPolicy = f.game.copy(p0PolicyId = "unbound-policy")
        assertFails { requireFactualIncumbentGame(request, f.pair.copy(games = listOf(wrongPolicy)), wrongPolicy, DECK, POOL) }
        assertFails { requireFactualIncumbentGame(request.copy(seedGroupId = "wrong"), f.pair, f.game, DECK, POOL) }
        val replay = readVerifiedCanonicalSemanticReplay(f.replay)
        requireFactualIncumbentReplay(request, f.report, f.game, replay)
        assertFails { requireFactualIncumbentReplay(request, f.report, f.game, replay.copy(header = replay.header.copy(
            extensions = JsonObject(replay.header.extensions + ("mtgallium.outerCommit" to JsonPrimitive("wrong")))))) }
    }

    @Test
    fun `failed incomplete unverified and unsupported games cannot become labels`() = withFixture { f ->
        val changes = listOf(f.game.copy(terminal = false, winner = null, searchScore = null,
            disposition = GameRunDisposition.STOPPED_LIMIT), f.game.copy(stepLimit = true),
            f.game.copy(exception = "stopped"), f.game.copy(replayVerified = false),
            f.game.copy(informationLedgerComplete = false), f.game.copy(unsupportedInformationEvents = listOf("unknown")),
            f.game.copy(disposition = GameRunDisposition.LEGACY_UNCLASSIFIED))
        changes.forEach { game ->
            assertFails { requireFactualIncumbentGame(f.request(), f.pair.copy(games = listOf(game)), game, DECK, POOL) }
        }
    }

    @Test
    fun `authenticated calibration loader refuses changed manifest checkpoint identity and replay bytes`() = withFixture { f ->
        assertEquals(f.report, loadFactualIncumbentCalibration(f.request()))
        assertFails { loadFactualIncumbentCalibration(f.request().copy(sourceManifestSha256 = "0".repeat(64))) }
        val checkpointPath = f.directory.resolve("checkpoints/${f.game.gameId}.json")
        val checkpoint = ResearchRunCheckpoints.load(checkpointPath)
        Files.delete(checkpointPath)
        ResearchRunCheckpoints.persist(checkpointPath, "wrong-run", checkpoint.payloadSchema, checkpoint.sequence, checkpoint.payload())
        f.finalizeArtifacts()
        assertFails { loadFactualIncumbentCalibration(f.request()) }
        Files.delete(checkpointPath)
        ResearchRunCheckpoints.persist(checkpointPath, f.report.runIdentity, checkpoint.payloadSchema, checkpoint.sequence, checkpoint.payload())
        Files.writeString(f.replay, "tampered")
        f.finalizeArtifacts()
        assertFails { loadFactualIncumbentCalibration(f.request()) }
    }

    @Test
    fun `artifact round trip retains complete labels and a refusal cannot retain partial rows`() = withFixture { f ->
        val report = factualReport(f)
        assertFails { report.copy(disposition = FactualIncumbentTrajectoryDisposition.REPLAY_REFUSED, refusal = "late failure") }
        val refused = report.copy(disposition = FactualIncumbentTrajectoryDisposition.REPLAY_REFUSED,
            refusal = "late failure", source = null, replayAudit = null, rows = emptyList())
        assertTrue(refused.rows.isEmpty())
        val output = f.directory.resolve("derived"); Files.createDirectories(output)
        writeEvidenceJsonStream(output.resolve("report.json"), report, FactualIncumbentTrajectoryReport.serializer())
        assertEquals(evidenceJson.encodeToString(report) + "\n", Files.readString(output.resolve("report.json")))
        ResearchRunArtifacts(output, report.bindings.identity).also { it.register("report.json"); it.finalize() }
        assertEquals(report, loadVerifiedFactualIncumbentTrajectory(output, report.bindings.identity))
        assertFails { loadVerifiedFactualIncumbentTrajectory(output, "wrong-identity") }
        // A valid same-identity manifest authenticates only its registered files.
        // Keep the complete, structurally valid report beside it, but omit that report from the manifest.
        Files.delete(output.resolve(ResearchRunArtifacts.MANIFEST_FILE))
        Files.writeString(output.resolve("other.txt"), "registered unrelated artifact")
        ResearchRunArtifacts(output, report.bindings.identity).also { it.register("other.txt"); it.finalize() }
        assertEquals(listOf("other.txt"), ResearchRunArtifacts.loadAndVerify(output, report.bindings.identity)
            .artifacts.map { it.relativePath })
        assertFailsWith<IllegalArgumentException> {
            loadVerifiedFactualIncumbentTrajectory(output, report.bindings.identity)
        }
    }

    @Test
    fun `historical projection authority remains fixed and current authority refuses uncorrelated codec normalization`() {
        assertFails { RecordedReplayStateEquivalence(historicalProjectionAuthority().copy(argentumCommit = FACTUAL_INCUMBENT_ARGENTUM_REVISION)) }
        assertFails { RecordedReplayStateEquivalence.currentEngine(OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT) }
        val current = RecordedReplayStateEquivalence.currentEngine(FACTUAL_INCUMBENT_ARGENTUM_REVISION)
        val state = GameState()
        assertNull(current.initialDifference(state, state))
        assertNotNull(current.transitionDifference(PassPriority(EntityId("p")), PassPriority(EntityId("p")),
            emptyList(), emptyList(), state, state, state, state, true, true, 0,
            listOf(RecordedReplayLegacyTypeLineNormalization(0, EntityId("card")))))
    }

    @Test
    fun `compact root preparation preserves verified coordinates and refuses another source or frame`() = withFixture { f ->
        val report = factualReport(f)
        val game = rootAllocation(f)
        val replay = readVerifiedCanonicalSemanticReplay(f.replay)
        val policy = f.control.policy(f.plan.baseSeed)
        val prepared = compactFactualResidualRoot(report, game, replay, policy)
        assertEquals(f.game.seed, prepared.gameSeed)
        assertEquals(report.rows[0].information.informationStateDigest, prepared.expectedRootInformationDigest)
        assertSame(replay, prepared.replay)
        assertSame(policy, prepared.policy)
        val root = requireNotNull(game.root)
        val alternatives = listOf(
            game.copy(sourceRunIdentity = "other", root = root.copy(assignment = root.assignment.copy(sourceRunIdentity = "other"))),
            game.copy(gameId = "other", root = root.copy(assignment = root.assignment.copy(sourceGameId = "other"))),
            game.copy(seedGroupId = "other", root = root.copy(assignment = root.assignment.copy(seedGroupId = "other"))),
            game.copy(viewer = "p1", root = root.copy(assignment = root.assignment.copy(actor = "p1"))),
            game.copy(leg = 1),
            game.copy(root = root.copy(assignment = root.assignment.copy(decisionIndex = 1))),
            game.copy(semanticDecisions = 3, root = root.copy(assignment = root.assignment.copy(decisionIndex = 2))),
        )
        alternatives.forEach { alternative -> assertFailsWith<IllegalArgumentException> {
            compactFactualResidualRoot(report, alternative, replay, policy)
        } }
        val shrinkingRows = report.rows.toMutableList()
        val shrinkingReport = report.copy(rows = shrinkingRows)
        shrinkingRows.clear()
        assertFailsWith<IllegalArgumentException> { compactFactualResidualRoot(shrinkingReport, game, replay, policy) }
        val refused = report.copy(disposition = FactualIncumbentTrajectoryDisposition.REPLAY_REFUSED,
            refusal = "synthetic late refusal", source = null, replayAudit = null, rows = emptyList())
        assertFailsWith<IllegalArgumentException> { compactFactualResidualRoot(refused, game, replay, policy) }
    }

    @Test
    fun `compact root preparation releases the trajectory and its unselected history before search`() = withFixture { f ->
        val (prepared, references) = compactRetentionWitness(f)
        // Keep the actual prepared object live while observing collection of the former large inputs.
        // A closure or report/row reference in preparation would keep at least one witness reachable.
        repeat(20) {
            if (references.any { it.get() != null }) {
                System.gc()
                Thread.sleep(10)
            }
        }
        assertTrue(references.all { it.get() == null }, "Prepared root retains factual trajectory data")
        assertEquals(f.information("p0", 0).informationStateDigest, prepared.expectedRootInformationDigest)
        assertEquals(f.game.gameId, prepared.replay.header.gameId)
        Reference.reachabilityFence(prepared)
    }

    private fun compactRetentionWitness(f: Fixture): Pair<PreparedFactualResidualRoot, List<WeakReference<Any>>> {
        val report = factualReport(f)
        val references = listOf(report, report.rows, report.rows[1], report.rows[1].information.history)
            .map { WeakReference<Any>(it) }
        return compactFactualResidualRoot(report, rootAllocation(f), readVerifiedCanonicalSemanticReplay(f.replay),
            f.control.policy(f.plan.baseSeed)) to references
    }

    private fun rootAllocation(f: Fixture): FactualResidualGameAllocation {
        val request = f.request()
        return FactualResidualGameAllocation(request.sourceRunIdentity, request.gameId, request.seedGroupId,
            RealGamePositionPartition.DEVELOPMENT, FactualResidualDataRole.SCREEN, 0, request.viewer, 2, emptyList(),
            FactualResidualRootMetadata(RealGamePositionBankAssignment("synthetic-root", request.sourceRunIdentity,
                request.gameId, "control", request.viewer, 0, 0, request.seedGroupId, RealGamePositionPartition.DEVELOPMENT,
                RealGamePositionDecisionFamily.PRIORITY, RealGamePositionAssignmentStatus.EXCLUDED), f.choices))
    }

    private fun factualReport(f: Fixture): FactualIncumbentTrajectoryReport {
        val request = f.request()
        val producer = ResearchRunProvenance(f.source.outer.revision, FACTUAL_INCUMBENT_ARGENTUM_REVISION,
            FACTUAL_INCUMBENT_ARGENTUM_REVISION, false, false, f.source)
        val replay = readVerifiedCanonicalSemanticReplay(f.replay)
        val (rows, audit) = projectFactualIncumbentTrajectory(replay, request.viewer, FixtureWorld(f))
        val checkpoint = f.directory.resolve("checkpoints/${f.game.gameId}.json")
        return FactualIncumbentTrajectoryReport(bindings = factualIncumbentTrajectoryBindings(request, producer, emptyMap()),
            request = request, producer = producer, runtime = emptyMap(), disposition = FactualIncumbentTrajectoryDisposition.ADMITTED,
            source = FactualIncumbentTrajectorySource(sha256File(f.directory.resolve("report.json")),
                "checkpoints/${f.game.gameId}.json", sha256File(checkpoint), ResearchRunCheckpoints.load(checkpoint).payloadSha256,
                "replays/${f.game.gameId}.privileged.replay.jsonl.gz", sha256File(f.replay), replay.terminal.recordDigest,
                0, 0, f.game.seed, f.policies[0], f.policies[1], DECK, POOL, 2, 2), replayAudit = audit, rows = rows)
    }

    private class FixtureWorld(val f: Fixture, val wrongViewer: Boolean = false, val unsupportedAt: Int? = null,
        val terminal: Boolean = true, val wrongPayoff: Boolean = false, val privacyLeakAt: Int? = null) : SemanticReplayWorld {
        private var index = 0
        override fun actorToAct(): String? = listOf("p0", "p1").getOrNull(index)
        override fun informationState(viewer: String): PolicyInformationState {
            val info = f.information(if (wrongViewer) "p1" else viewer, index)
            return when {
                index == privacyLeakAt -> info.copy(observation = info.observation.copy(phase = "late.privileged.phase"))
                index == unsupportedAt -> info.copy(knowledge = info.knowledge.copy(epistemicallyComplete = false, unsupportedReasons = listOf("unknown")))
                index == 2 && !terminal -> info.copy(terminated = false)
                else -> info
            }
        }
        override fun expandChoices(): List<SemanticChoice> = listOf(f.choices[index])
        override fun stepWithReplayTrace(choice: SemanticChoice): SemanticReplayStep {
            require(choice == f.choices[index])
            val i = index++
            return SemanticReplayStep(SearchStepResult(accepted = true, privateToActor = false), listOf(
                ArgentumRawTransition(f.actions[i], f.states[i], f.states[i + 1], emptyList(), null)))
        }
        override fun authoritativeState(): GameState = f.states[index]
        override fun terminalPayoff(rootPlayer: String): Double? = if (index != 2 || !terminal) null else
            if (wrongPayoff || rootPlayer == "p0") 1.0 else -1.0
    }

    private class Fixture(val directory: Path) {
        val source = ResearchSourceProvenance(expectedArgentumRevision = FACTUAL_INCUMBENT_ARGENTUM_REVISION,
            outer = ResearchSourceTreeState("1".repeat(40), sha256(""), sha256(""), sha256("")),
            argentum = ResearchSourceTreeState(FACTUAL_INCUMBENT_ARGENTUM_REVISION, sha256(""), sha256(""), sha256("")))
        val control = SearchTeacherCalibrationPolicy("control", 8, 56, 16, 1.4, true, 1.0)
        val candidate = control.copy(id = "peer")
        val plan = SearchTeacherCalibrationPlan(phase = SearchTeacherCalibrationPhase.DEVELOPMENT,
            baseSeed = 71, pairOffset = 0, pairCount = 1, control = control, candidates = listOf(candidate))
        val policies = listOf(control, candidate).map { descriptor -> SearchTeacherCalibrationPolicyReport(
            descriptor, describeTournamentPolicy(descriptor.policy(71)), descriptor.parameters(71).searchConfig(),
            PolicyBehaviorBinding.create("synthetic:${descriptor.id}", buildJsonObject { put("synthetic", descriptor.id) }, source),
            SearchTeacherSearchFactory.rootRolloutPolicy().behaviorSpecification,
            SearchTeacherSearchFactory.opponentRolloutPolicy().behaviorSpecification) }
        val identity = searchTeacherCalibrationBindings(plan, source, policies.associate { it.descriptor.id to it.binding.identity }, DECK, POOL, 1).identity
        val e0 = EntityId("e0"); val e1 = EntityId("e1")
        val states = listOf(GameState(turnNumber = 1, activePlayerId = e0, priorityPlayerId = e0, turnOrder = listOf(e0, e1)),
            GameState(turnNumber = 1, activePlayerId = e0, priorityPlayerId = e1, turnOrder = listOf(e0, e1), timestamp = 1),
            GameState(turnNumber = 1, activePlayerId = e0, priorityPlayerId = null, turnOrder = listOf(e0, e1), timestamp = 2, winnerId = e0, gameOver = true))
        val actions = listOf(PassPriority(e0), PassPriority(e1))
        val choices = listOf("p0", "p1").map { player -> SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
            operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay("pass"),
            canonicalPayload = buildJsonObject { put("type", "PassPriority"); put("playerId", player) }) }
        val replay: Path
        val game: GameRunResult
        val pair: SearchBudgetFrontierPair
        val report: SearchTeacherCalibrationReport
        init {
            val games = (0..1).map { leg ->
                val id = "peer-pair-0-leg-$leg"
                val path = directory.resolve("replays/$id.privileged.replay.jsonl.gz")
                Files.createDirectories(path.parent)
                val recorder = CanonicalReplayRecorder(id, "2026-09-08T00:00:00Z", FACTUAL_INCUMBENT_ARGENTUM_REVISION,
                    "synthetic", listOf("p0", "p1"), states.first(), extensions = buildJsonObject {
                        put("mtgallium.runIdentity", identity); put("mtgallium.outerCommit", source.outer.revision)
                        put("mtgallium.argentumCommit", FACTUAL_INCUMBENT_ARGENTUM_REVISION)
                        put("mtgallium.gameSeed", plan.pairSeed(0)); put("mtgallium.baseSeed", plan.baseSeed)
                        put("mtgallium.deckHash", DECK); put("mtgallium.cardPoolHash", POOL)
                    })
                val transitions = actions.indices.map { index -> recorder.appendAction(ReplayTransitionOrigin.POLICY,
                    actions[index], true, states[index + 1], extensions = buildJsonObject {
                        put("mtgallium.decisionIndex", index); put("mtgallium.semanticChoice", PolicyJson.format.encodeToJsonElement(choices[index]))
                    }) }
                val terminal = recorder.finish(ReplayCompletionStatus.COMPLETE, states.last(), winnerId = "p0")
                GZIPOutputStream(Files.newOutputStream(path)).bufferedWriter().use { writer ->
                    (listOf(recorder.header) + transitions + terminal).forEach { record ->
                        writer.write(CanonicalReplayJson.encodeToString(CanonicalReplayRecord.serializer(), record)); writer.newLine()
                    }
                }
                val g = GameRunResult(gameId = id, seed = plan.pairSeed(0), p0Policy = ArenaPolicyKind.SEARCH,
                    p1Policy = ArenaPolicyKind.SEARCH, winner = "p0", terminal = true, disposition = GameRunDisposition.GAME_ENDED,
                    decisions = 2, searchSeat = "p0", searchScore = 1.0, illegalResponses = 0, fallbacks = 0, stepLimit = false,
                    p0PolicyId = if (leg == 0) control.id else candidate.id, p1PolicyId = if (leg == 0) candidate.id else control.id,
                    replayPath = path.toString(), replaySha256 = sha256File(path), replayVerified = true)
                listOf("public/$id.p0.jsonl.gz", "public/planner/$id.p0.planner.json.gz").forEach { relative ->
                    val artifact = directory.resolve(relative); Files.createDirectories(artifact.parent); Files.writeString(artifact, "synthetic sidecar")
                }
                val checkpoint = SearchTeacherCalibrationCheckpoint(0, leg, g, calibrationArtifactHashes(directory, g))
                val checkpointPath = directory.resolve("checkpoints/$id.json"); Files.createDirectories(checkpointPath.parent)
                ResearchRunCheckpoints.persist(checkpointPath, identity, "search-teacher-calibration-game-v1", leg.toLong(), evidenceJson.encodeToString(checkpoint).encodeToByteArray())
                g
            }
            game = games.first(); replay = Path.of(requireNotNull(game.replayPath))
            pair = searchBudgetFrontierPair(0, plan.pairSeed(0), games, candidate.id)
            report = SearchTeacherCalibrationReport(runIdentity = identity, generatedAtUtc = "synthetic", sourceProvenance = source,
                deckHash = DECK, cardPoolHash = POOL, plan = plan, workerThreads = 1, currentAttemptElapsedMillis = 0.0,
                policies = policies, comparisons = listOf(calibrationComparison(plan, candidate, listOf(pair))), valid = true)
            writeJsonAtomically(directory.resolve("plan.json"), plan); writeJsonAtomically(directory.resolve("report.json"), report)
            finalizeArtifacts()
        }
        fun finalizeArtifacts() {
            Files.deleteIfExists(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE))
            val artifacts = ResearchRunArtifacts(directory, identity)
            Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach {
                artifacts.register(directory.relativize(it).toString())
            } }
            artifacts.finalize()
        }
        fun request() = FactualIncumbentTrajectoryRequest(directory.toString(), identity,
            sha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)), source, plan, game.gameId, "p0",
            realGamePositionSeedGroup(DECK, POOL, game.seed), ResearchBuildReference(directory.resolve("unexecuted-build").toString(), "unused", "a".repeat(64)))
        fun information(viewer: String, index: Int): PolicyInformationState {
            val actor = listOf("p0", "p1").getOrNull(index)
            val history = if (index == 0) emptyList() else listOf(PolicyHistoryEvent(0,
                PolicyAudience(PolicyAudienceScope.PUBLIC), "p0", PolicyHistoryEventKind.PRIORITY_PASS,
                buildJsonObject { put("pass", true) }))
            return PolicyInformationState(actingPlayerId = actor, observation = PolicyObservation(
                perspectivePlayerId = viewer, turnNumber = 1, phase = "BEGINNING", step = "UPKEEP", activePlayerId = "p0", priorityPlayerId = actor,
                players = listOf(PolicyPlayerView("p0", "First", 20, 0, 53, 0, 0, PolicyManaPool(), viewer == "p0", actor == "p0", false),
                    PolicyPlayerView("p1", "Second", 10, 0, 53, 0, 0, PolicyManaPool(), viewer == "p1", actor == "p1", false)),
                zones = emptyList(), stack = emptyList(), currentTurnStateComplete = true, pendingDecision = null,
                observationDigest = sha256("observation:$viewer:$index")), informationStateDigest = sha256("information:$viewer:$index"),
                historyCommitment = PolicyHistoryCommitment.replay(history), history = history,
                knowledge = PolicyKnowledgeState(perspectivePlayerId = viewer, knownLibraryOrders = listOf(PolicyKnownLibraryOrder(viewer, 0, top = listOf("Mountain"))),
                    knowledgeDigest = sha256("knowledge:$viewer:$index")), candidates = if (actor == viewer) listOf(choices[index]) else emptyList(),
                terminated = index == 2, winnerId = "p0".takeIf { index == 2 })
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("factual-incumbent-test-")
        try { block(Fixture(directory)) } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    companion object { private val DECK = sha256("deck"); private val POOL = sha256("pool") }
}
