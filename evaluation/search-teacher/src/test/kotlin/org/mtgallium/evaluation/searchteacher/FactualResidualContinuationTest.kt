package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.*
import org.mtgallium.research.run.*

/** All artifacts are synthetic protocol fixtures; no canonical replay, engine, fit, or private input is executed. */
@Tag("public-source")
class FactualResidualContinuationTest {
    @TempDir lateinit var directory: Path

    @Test fun `continuation preserves every admitted child identity and the missing population coordinate`() {
        val f = Fixture(directory, 63)
        val result = f.load()
        assertEquals((0 until 63).toSet(), result.entries.keys)
        assertEquals(listOf(63), f.allocation.games.indices.filter { it !in result.entries })
        assertEquals(f.parentReport, result.parent)
        assertEquals(f.allocation, result.allocation)
        result.entries.forEach { (index, entry) ->
            assertEquals(f.allocation.games[index], entry.allocation)
            assertEquals(f.parentReference.identity, entry.reusedFromStudyIdentity)
            assertEquals(FactualIncumbentTrajectoryDisposition.ADMITTED, entry.disposition)
            assertEquals(1, entry.rows)
            val original = f.child(index)
            assertEquals(original.bindings.identity, requireNotNull(entry.trajectory).identity)
            assertEquals(f.producer, original.producer)
            assertEquals(f.runtime, original.runtime)
            assertEquals(f.parentPlan.build, original.request.build)
        }
        val completeCoordinates = f.allocation.games.mapIndexed { index, game -> result.entries[index] ?:
            FactualResidualCorpusEntry(game, null, null, 0, "Admission remains pending") }
        val corpus = FactualResidualCorpusReport(ResearchRunBindings(protocol = "synthetic-corpus-v1", material = mapOf("fixture" to "synthetic")),
            requireNotNull(f.parentReport.allocation), completeCoordinates)
        assertEquals(64, corpus.entries.size)
        assertFalse(corpus.complete)
        assertFalse(evidenceJson.encodeToString(f.parentPlan).contains("admissionParent"))
        val reused = result.entries.getValue(0)
        assertFalse(evidenceJson.encodeToString(reused.copy(reusedFromStudyIdentity = null)).contains("reusedFromStudyIdentity"))
        assertEquals(reused, evidenceJson.decodeFromString<FactualResidualCorpusEntry>(evidenceJson.encodeToString(reused)))
    }

    @Test fun `scientific allocation and original cumulative budget stay fixed`() {
        val f = Fixture(directory, 1)
        val changedRoot = f.allocation.games.indexOfFirst { it.root != null }
        val changedGames = f.allocation.games.mapIndexed { index, game -> if (index != changedRoot) game else {
            val root = requireNotNull(game.root)
            game.copy(root = root.copy(assignment = root.assignment.copy(decisionFamily = RealGamePositionDecisionFamily.OTHER)))
        } }
        val plan = f.currentPlan()
        val changed = FactualResidualAllocation(factualResidualAllocationBindings("different-study", plan.inventory, plan.incumbent, changedGames),
            "different-study", plan.inventory, plan.incumbent, changedGames)
        assertFails { f.load(allocation = changed) }
        assertFails { f.load(plan = plan.copy(incumbent = plan.incumbent.copy(id = "changed-incumbent"))) }
        assertFails { f.load(plan = plan.copy(maximumSeconds = 1483)) }
        assertEquals(1, f.load(plan = plan.copy(maximumSeconds = 1482, workers = 2, admissionWorkers = 1,
            build = plan.build.copy(directory = directory.resolve("different-current-build").toString()))).entries.size)
    }

    @Test fun `registered partial and refused children cannot be silently omitted`() {
        val partial = Fixture(directory.resolve("partial"), 2)
        partial.sealParent(omit = setOf("corpus/trajectories/game-1/${ResearchRunArtifacts.MANIFEST_FILE}"))
        assertFails { partial.load() }
        val refused = Fixture(directory.resolve("refused"), 2)
        refused.replaceChild(1) { it.copy(disposition = FactualIncumbentTrajectoryDisposition.REPLAY_REFUSED,
            source = null, replayAudit = null, rows = emptyList(), refusal = "Synthetic refusal") }
        assertFails { refused.load() }
    }

    @Test fun `child source build producer runtime and own bindings remain authoritative`() {
        val source = Fixture(directory.resolve("source"), 1)
        source.replaceChild(0) { old -> old.withRequest(old.request.copy(sourceDirectory = directory.resolve("wrong-source").toString())) }
        assertFails { source.load() }
        val build = Fixture(directory.resolve("build"), 1)
        build.replaceChild(0) { old -> old.withRequest(old.request.copy(build = old.request.build.copy(identity = "wrong-build"))) }
        assertFails { build.load() }
        val runtime = Fixture(directory.resolve("runtime"), 1)
        runtime.replaceChild(0) { old ->
            val altered = old.runtime + ("vm-arguments" to "different admission runtime")
            old.copy(runtime = altered, bindings = factualIncumbentTrajectoryBindings(old.request, old.producer, altered))
        }
        assertFails { runtime.load() }
        val bindings = Fixture(directory.resolve("bindings"), 1)
        bindings.replaceChild(0) { it.copy(bindings = ResearchRunBindings(protocol = FACTUAL_INCUMBENT_TRAJECTORY_PROTOCOL, material = mapOf("wrong" to "binding"))) }
        assertFails { bindings.load() }
    }

    @Test fun `post corpus fitted chained empty and already complete parents are refused`() {
        val postCorpus = Fixture(directory.resolve("post-corpus"), 1)
        postCorpus.parentReport = postCorpus.parentReport.copy(corpus = postCorpus.parentReport.allocation)
        postCorpus.sealParent()
        assertFails { postCorpus.load() }
        val fitted = Fixture(directory.resolve("fitted"), 1)
        writeJsonAtomically(fitted.parentDirectory.resolve("training/report.json"), buildJsonObject { put("synthetic", true) })
        fitted.sealParent()
        assertFails { fitted.load() }
        val chained = Fixture(directory.resolve("chained"), 1)
        val priorPlan = chained.parentPlan.copy(admissionParent = chained.parentReference)
        chained.parentReport = chained.parentReport.copy(plan = priorPlan,
            bindings = factualResidualStudyBindings(priorPlan, chained.producer, chained.runtime, chained.deck))
        chained.sealParent()
        assertFails { chained.load() }
        assertFails { Fixture(directory.resolve("empty"), 0).load() }
        assertFails { Fixture(directory.resolve("full"), 64).load() }
    }

    private fun FactualIncumbentTrajectoryReport.withRequest(request: FactualIncumbentTrajectoryRequest) =
        copy(request = request, bindings = factualIncumbentTrajectoryBindings(request, producer, runtime))

    private class Fixture(val directory: Path, admitted: Int) {
        val deck = DeckManifest("synthetic", "Synthetic", "test", "2026-01-01", "public test", mapOf("Mountain" to 60), emptyMap())
        private val sourceTree = ResearchSourceTreeState("a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64))
        private val source = ResearchSourceProvenance(expectedArgentumRevision = FACTUAL_INCUMBENT_ARGENTUM_REVISION,
            outer = sourceTree, argentum = sourceTree.copy(revision = FACTUAL_INCUMBENT_ARGENTUM_REVISION))
        val producer = ResearchRunProvenance(sourceTree.revision, FACTUAL_INCUMBENT_ARGENTUM_REVISION,
            FACTUAL_INCUMBENT_ARGENTUM_REVISION, false, false, source)
        val runtime = mapOf("java.version" to "synthetic", "classpath-0" to "synthetic runtime", "vm-arguments" to "[]")
        private val build: ResearchBuildReference
        private val incumbent: SearchTeacherCalibrationPolicy
        private val calibrationPlan: SearchTeacherCalibrationPlan
        private val policies: List<SearchTeacherCalibrationPolicyReport>
        private val calibration: SearchTeacherCalibrationReport
        private val sourceReference: FactualResidualInput
        private val inputs: LoadedFactualResidualInputs
        val parentDirectory = directory.resolve("parent")
        val parentPlan: FactualResidualStudyPlan
        val allocation: FactualResidualAllocation
        var parentReport: FactualResidualStudyReport
        lateinit var parentReference: FactualResidualInput
            private set

        init {
            Files.createDirectories(directory)
            build = retainBuild(directory.resolve("build"))
            val casting = RootKernelFitReference(directory.resolve("unexecuted-casting").toString(),
                ResearchRunBindings(protocol = "synthetic-fit-v1", material = mapOf("fixture" to "synthetic")).identity, "c".repeat(64))
            incumbent = SearchTeacherCalibrationPolicy("incumbent", 8, 56, 16, 1.4, true, 1.0,
                evaluator = MonoRedVisibleEvaluatorConfig(), fastRootKernelRolloutFit = casting, fastOpponentKernelRolloutFit = casting)
            val peer = incumbent.copy(id = "peer")
            calibrationPlan = SearchTeacherCalibrationPlan(phase = SearchTeacherCalibrationPhase.DEVELOPMENT,
                baseSeed = 71, pairOffset = 0, pairCount = 32, control = incumbent, candidates = listOf(peer))
            policies = listOf(incumbent, peer).map { descriptor -> SearchTeacherCalibrationPolicyReport(descriptor,
                TournamentPolicyDescription(descriptor.id, ArenaPolicyKind.SEARCH), descriptor.parameters(71).searchConfig(),
                PolicyBehaviorBinding.create("synthetic:${descriptor.id}", buildJsonObject { put("synthetic", descriptor.id) }, source),
                SearchTeacherSearchFactory.rootRolloutPolicy().behaviorSpecification,
                SearchTeacherSearchFactory.opponentRolloutPolicy().behaviorSpecification) }
            val sourceIdentity = searchTeacherCalibrationBindings(calibrationPlan, source,
                policies.associate { it.descriptor.id to it.binding.identity }, deck.deckHash(), deck.cardPoolHash(), 1).identity
            val sourceDirectory = directory.resolve("source")
            Files.createDirectories(sourceDirectory)
            val pairs = (0 until 32).map { pairIndex ->
                val games = (0..1).map { leg ->
                    val id = "game-$pairIndex-$leg"
                    val replay = sourceDirectory.resolve("replays/$id.privileged.replay.jsonl.gz")
                    Files.createDirectories(replay.parent)
                    Files.writeString(replay, "synthetic opaque retained replay bytes $id")
                    val game = GameRunResult(gameId = id, seed = calibrationPlan.pairSeed(pairIndex),
                        p0Policy = ArenaPolicyKind.SEARCH, p1Policy = ArenaPolicyKind.SEARCH, winner = "p0", terminal = true,
                        disposition = GameRunDisposition.GAME_ENDED, decisions = 1, searchSeat = "p0", searchScore = 1.0,
                        illegalResponses = 0, fallbacks = 0, stepLimit = false,
                        p0PolicyId = if (leg == 0) incumbent.id else peer.id, p1PolicyId = if (leg == 0) peer.id else incumbent.id,
                        replaySha256 = researchSha256File(replay), replayVerified = true)
                    ResearchRunCheckpoints.persist(sourceDirectory.resolve("checkpoints/$id.json"), sourceIdentity,
                        "synthetic-checkpoint-v1", (pairIndex * 2 + leg).toLong(), "synthetic payload $id".encodeToByteArray())
                    game
                }
                SearchBudgetFrontierPair(pairIndex, calibrationPlan.pairSeed(pairIndex), games, true, emptyList(), 1.0)
            }
            calibration = SearchTeacherCalibrationReport(runIdentity = sourceIdentity, generatedAtUtc = "synthetic",
                sourceProvenance = source, deckHash = deck.deckHash(), cardPoolHash = deck.cardPoolHash(), plan = calibrationPlan,
                workerThreads = 1, currentAttemptElapsedMillis = 0.0, policies = policies,
                comparisons = listOf(SearchTeacherCalibrationComparison(peer.id, 32, pairs, 32, 64, 0, 0, emptyList(),
                    .5, .5, .5, emptyList())), valid = true)
            writeJsonAtomically(sourceDirectory.resolve("report.json"), calibration)
            writeJsonAtomically(sourceDirectory.resolve("plan.json"), calibrationPlan)
            finalizeResearchWorkflowArtifacts(sourceDirectory, sourceIdentity)
            sourceReference = factualResidualReference(sourceDirectory, sourceIdentity)
            val grouped = pairs.sortedBy { realGamePositionSeedGroup(deck.deckHash(), deck.cardPoolHash(), it.seed) }
            val games = grouped.flatMapIndexed { rank, pair -> pair.games.mapIndexed { leg, game ->
                val group = realGamePositionSeedGroup(deck.deckHash(), deck.cardPoolHash(), game.seed)
                val partition = if (rank < 25) RealGamePositionPartition.DEVELOPMENT else RealGamePositionPartition.VALIDATION
                val role = if (rank < 16) FactualResidualDataRole.TRAIN else FactualResidualDataRole.SCREEN
                val root = if (role == FactualResidualDataRole.TRAIN) null else FactualResidualRootMetadata(
                    RealGamePositionBankAssignment("root-${game.gameId}", sourceIdentity, game.gameId, incumbent.id, "p$leg", 0,
                        pair.pairIndex, group, partition, RealGamePositionDecisionFamily.PRIORITY, RealGamePositionAssignmentStatus.EXCLUDED), choices())
                FactualResidualGameAllocation(sourceIdentity, game.gameId, group, partition, role, leg, "p$leg", 1,
                    if (role == FactualResidualDataRole.TRAIN) listOf(0) else emptyList(), root)
            } }
            val bankPlan = RealGamePositionBankPlan(sources = listOf(RealGamePositionBankSource(sourceDirectory.toString(), sourceIdentity)),
                rootLimit = 32, maxRootsPerGame = 1, validationFraction = .25, selectionSeed = 1)
            val bank = RealGamePositionBankReport(bankIdentity = ResearchRunBindings(protocol = "synthetic-inventory-v1", material = mapOf("fixture" to "synthetic")).identity,
                generatedAtUtc = "synthetic", sourceProvenance = source, plan = bankPlan, sources = emptyList(),
                games = emptyList(), assignments = emptyList(), roots = emptyList(),
                accounting = RealGamePositionBankAccounting(64, 64, 64, 32, 32, 32, 0, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()),
                complete = true)
            val inventoryDirectory = directory.resolve("inventory")
            writeJsonAtomically(inventoryDirectory.resolve("report.json"), bank)
            writeJsonAtomically(inventoryDirectory.resolve("plan.json"), bankPlan)
            finalizeResearchWorkflowArtifacts(inventoryDirectory, bank.bankIdentity)
            val inventoryReference = factualResidualReference(inventoryDirectory, bank.bankIdentity)
            inputs = LoadedFactualResidualInputs(bank, mapOf(sourceIdentity to calibration), mapOf(sourceIdentity to sourceReference))
            parentPlan = FactualResidualStudyPlan(inventory = inventoryReference, incumbent = incumbent, build = build, admissionWorkers = 2)
            val bindings = factualResidualStudyBindings(parentPlan, producer, runtime, deck)
            allocation = FactualResidualAllocation(factualResidualAllocationBindings(bindings.identity, inventoryReference, incumbent, games),
                bindings.identity, inventoryReference, incumbent, games)
            writeJsonAtomically(parentDirectory.resolve("allocation/report.json"), allocation)
            finalizeResearchWorkflowArtifacts(parentDirectory.resolve("allocation"), allocation.bindings.identity)
            val allocationReference = factualResidualReference(parentDirectory.resolve("allocation"), allocation.bindings.identity)
            parentReport = FactualResidualStudyReport(bindings, parentPlan, producer, runtime,
                FactualResidualStudyDisposition.STUDY_REFUSED, 317.2, allocationReference, null, null, null, emptyList(), null, "Synthetic admission interruption")
            repeat(admitted) { index -> retainChild(index, trajectory(index)) }
            sealParent()
        }

        fun currentPlan() = parentPlan.copy(admissionParent = parentReference, maximumSeconds = 1480)
        fun load(plan: FactualResidualStudyPlan = currentPlan(), allocation: FactualResidualAllocation? = null): FactualResidualContinuation {
            val identity = factualResidualStudyBindings(plan, producer, runtime, deck).identity
            val current = allocation ?: FactualResidualAllocation(factualResidualAllocationBindings(identity, plan.inventory, plan.incumbent, this.allocation.games),
                identity, plan.inventory, plan.incumbent, this.allocation.games)
            return loadFactualResidualContinuation(parentReference, plan, current, deck, inputs)
        }
        fun child(index: Int) = readEvidenceJson(parentDirectory.resolve("corpus/trajectories/game-$index/report.json"), FactualIncumbentTrajectoryReport.serializer())
        fun replaceChild(index: Int, change: (FactualIncumbentTrajectoryReport) -> FactualIncumbentTrajectoryReport) {
            retainChild(index, change(child(index)))
            sealParent()
        }
        private fun retainChild(index: Int, report: FactualIncumbentTrajectoryReport) {
            val path = parentDirectory.resolve("corpus/trajectories/game-$index")
            writeEvidenceJsonStream(path.resolve("report.json"), report, FactualIncumbentTrajectoryReport.serializer())
            Files.deleteIfExists(path.resolve(ResearchRunArtifacts.MANIFEST_FILE))
            ResearchRunArtifacts(path, report.bindings.identity).apply { register("report.json"); finalize() }
        }
        fun sealParent(omit: Set<String> = emptySet()) {
            writeJsonAtomically(parentDirectory.resolve("plan.json"), parentReport.plan)
            writeJsonAtomically(parentDirectory.resolve("bindings.json"), parentReport.bindings)
            writeJsonAtomically(parentDirectory.resolve("report.json"), parentReport)
            Files.deleteIfExists(parentDirectory.resolve(ResearchRunArtifacts.MANIFEST_FILE))
            ResearchRunArtifacts(parentDirectory, parentReport.bindings.identity).apply {
                Files.walk(parentDirectory).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                    val relative = parentDirectory.relativize(path).toString()
                    if (relative !in omit) register(relative)
                } }
                finalize()
            }
            parentReference = factualResidualReference(parentDirectory, parentReport.bindings.identity)
        }
        private fun trajectory(index: Int): FactualIncumbentTrajectoryReport {
            val game = allocation.games[index]
            val pair = calibration.comparisons.single().pairs.single { it.games.any { actual -> actual.gameId == game.gameId } }
            val actual = pair.games[game.leg]
            val request = FactualIncumbentTrajectoryRequest(sourceReference.directory, sourceReference.identity, sourceReference.manifestSha256,
                source, calibrationPlan, game.gameId, game.viewer, game.seedGroupId, build)
            val information = information(game.viewer)
            val row = FactualIncumbentTrajectoryRow(0, game.viewer, information,
                MonoRedInformationEvaluator.evaluate(information, game.viewer), if (game.viewer == "p0") 1.0 else -1.0)
            val checkpoint = Path.of(sourceReference.directory).resolve("checkpoints/${game.gameId}.json")
            return FactualIncumbentTrajectoryReport(bindings = factualIncumbentTrajectoryBindings(request, producer, runtime),
                request = request, producer = producer, runtime = runtime, disposition = FactualIncumbentTrajectoryDisposition.ADMITTED,
                source = FactualIncumbentTrajectorySource(researchSha256File(Path.of(sourceReference.directory).resolve("report.json")),
                    "checkpoints/${game.gameId}.json", researchSha256File(checkpoint), ResearchRunCheckpoints.load(checkpoint).payloadSha256,
                    "replays/${game.gameId}.privileged.replay.jsonl.gz", requireNotNull(actual.replaySha256), "e".repeat(64), pair.pairIndex,
                    game.leg, actual.seed, policies.single { it.descriptor.id == actual.p0PolicyId }, policies.single { it.descriptor.id == actual.p1PolicyId },
                    deck.deckHash(), deck.cardPoolHash(), 1, 1),
                replayAudit = OutcomeStateReplayCompatibilityAudit(syntheticAbilityMappings = emptyList()), rows = listOf(row))
        }
        private fun retainBuild(path: Path): ResearchBuildReference {
            Files.createDirectories(path)
            Files.writeString(path.resolve("build.log"), "Synthetic successful forced-build record")
            Files.writeString(path.resolve("classpath.txt"), "synthetic runtime")
            val invocation = ResearchBuildInvocation(repository = directory.toString(), sourceCommitBefore = producer.outerCommit,
                engineCommitBefore = producer.checkedOutEngineCommit, commands = RESEARCH_BUILD_COMMANDS,
                exitCode = 0, elapsedMillis = 1.0, logSha256 = researchSha256File(path.resolve("build.log")))
            val json = Json { prettyPrint = true; encodeDefaults = true }
            val bindings = ResearchRunBindings(protocol = "research-local-build-v1", material = mapOf(
                "source" to researchSha256(json.encodeToString(producer)), "invocation" to researchSha256(json.encodeToString(invocation)),
                "runtime" to researchSha256(json.encodeToString<Map<String, String>>(runtime.toSortedMap()))))
            writeJsonAtomically(path.resolve("build-invocation.json"), invocation)
            writeJsonAtomically(path.resolve("build-report.json"), ResearchBuildReport(bindings, producer, invocation, runtime))
            ResearchRunArtifacts(path, bindings.identity).apply {
                listOf("build.log", "classpath.txt", "build-invocation.json", "build-report.json").forEach(::register)
                finalize()
            }
            return ResearchBuildReference(path.toString(), bindings.identity, researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
        }
        private fun choices() = (0..1).map { value -> SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
            operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay("Synthetic $value"),
            canonicalPayload = buildJsonObject { put("choice", value) }) }
        private fun information(viewer: String) = PolicyInformationState(actingPlayerId = viewer,
            observation = PolicyObservation(perspectivePlayerId = viewer, turnNumber = 1, phase = "BEGINNING", step = "UPKEEP",
                activePlayerId = "p0", priorityPlayerId = viewer,
                players = listOf(PolicyPlayerView("p0", "First", 20, 0, 53, 0, 0, PolicyManaPool(), viewer == "p0", viewer == "p0", false),
                    PolicyPlayerView("p1", "Second", 10, 0, 53, 0, 0, PolicyManaPool(), viewer == "p1", viewer == "p1", false)),
                zones = emptyList(), stack = emptyList(), currentTurnStateComplete = true, pendingDecision = null,
                observationDigest = sha256("observation:$viewer")), informationStateDigest = sha256("information:$viewer"),
            history = emptyList(), historyCommitment = PolicyHistoryCommitment.replay(emptyList()),
            knowledge = PolicyKnowledgeState(perspectivePlayerId = viewer, knowledgeDigest = sha256("knowledge:$viewer")),
            candidates = choices(), terminated = false, winnerId = null)
    }
}
