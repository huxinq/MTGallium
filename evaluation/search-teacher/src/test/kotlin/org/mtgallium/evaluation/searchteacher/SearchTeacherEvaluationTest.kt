package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.TakeMulligan
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayHeader
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayJson
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayRecord
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayTerminal
import org.mtgallium.evaluation.searchteacher.replay.ReplayCompletionStatus
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.mtgallium.agent.infoset.argentum.ArgentumKnownDeckBeliefWorldSource
import org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.argentum.PerspectiveProjectionAudit
import org.mtgallium.agent.infoset.argentum.PerspectiveProjectionAuditSink
import org.mtgallium.agent.infoset.argentum.ProjectionDisposition
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.INSPECTION_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyInspectionBundle
import org.mtgallium.agent.infoset.core.PolicyInspectionPresentation
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionCounter
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionDiagnostic
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PublicArtifactPrivacy
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.PolicyTrajectoryDecision
import org.mtgallium.agent.infoset.core.PolicyTrajectoryForcedTransition
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord
import org.mtgallium.agent.infoset.core.PlannerEvidenceSidecar
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.infoset.core.TRAJECTORY_SCHEMA_CURRENT
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.agent.searchteacher.SearchTeacherBehaviorSpecification
import org.mtgallium.agent.searchteacher.SearchTeacherIntegrationSpecification
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyIdentity

class SearchTeacherEvaluationTest {
    @Test
    fun `multi-response pregame choice is made by the declared search policy`() {
        val manifest = loadDeckManifest()
        val registry = buildRegistry()
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
                    seed = 17L,
                )
            )
        }
        val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = "pregame-policy-owner",
            seedBase = 99L,
            effectiveSetupSeed = 17L,
            expander = UnifiedSemanticExpander(
                actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
            ),
            cardRegistry = registry,
            knownDecks = knownDecks,
        )
        val expansion = world.expandChoices()
        assertEquals(2, expansion.candidates.size)
        val selection = SearchTeacherPolicySession(
            root = world,
            viewer = "p0",
            registry = registry,
            knownDecks = knownDecks,
            parameters = SearchTeacherPolicyParameters(
                particles = 1,
                simulations = 1,
                maxPolicyDecisions = 1,
                explorationConstant = 1.4,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
                actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
                baseSeed = 99L,
                profileId = "o02-pregame-policy-test-v1",
            ),
            opponentPolicy = defaultMonoRedOpponentPolicy(),
            gameId = "pregame-policy-owner",
        ).select(world, "p0", searchSeed = 101L)

        assertEquals(SearchTeacherSelectionKind.SEARCHED, selection.kind)
        assertTrue(selection.search != null)
        assertTrue(expansion.candidates.any { it.signature == selection.choice.signature })
    }

    @Test
    fun `sequential belief observes two accepted mulligans without contradictory remembered facts`() {
        val manifest = loadDeckManifest()
        val registry = buildRegistry()
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
                    seed = 17L,
                )
            )
        }
        val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = "sequential-double-mulligan",
            seedBase = 99L,
            effectiveSetupSeed = 17L,
            cardRegistry = registry,
            knownDecks = knownDecks,
        )
        val session = SearchTeacherPolicySession(
            root = world,
            viewer = "p0",
            registry = registry,
            knownDecks = knownDecks,
            parameters = SearchTeacherPolicyParameters(
                particles = 8,
                simulations = 1,
                maxPolicyDecisions = 1,
                explorationConstant = 1.4,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
                actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
                baseSeed = 99L,
                profileId = "sequential-double-mulligan-test-v1",
            ),
            opponentPolicy = defaultMonoRedOpponentPolicy(),
            gameId = "sequential-double-mulligan",
        )

        repeat(2) { decisionIndex ->
            val actor = requireNotNull(world.actorToAct())
            val choice = world.expandChoices().candidates.single { candidate ->
                (world.resolveChoice(candidate) as? ArgentumResolvedChoice.Action)?.value is TakeMulligan
            }
            val step = world.step(choice)

            assertTrue(step.accepted, step.diagnostic)
            session.observeAccepted(world, actor, choice, decisionIndex, step.privateToActor)
        }

        assertEquals(8, session.beliefBatch(world).particles.size)
        assertNull(world.knowledgeSupportFailure("p0", world.informationState("p0")))
    }

    @Test
    fun `public corpus and surprising-line schemas reject seed-bearing v1`() {
        assertFailsWith<IllegalArgumentException> {
            CorpusManifest(
                schemaVersion = 1,
                generatedAtUtc = "2026-08-23T00:00:00Z",
                profileId = "test",
                profileHash = "hash",
                outerCommit = "outer",
                argentumCommit = "fork",
                sourceProvenance = testSourceProvenance("outer", "fork"),
                requestedGames = 0,
                terminalGames = 0,
                replayVerifiedGames = 0,
                entries = emptyList(),
                passed = false,
                datasetIdentity = "invalid-old-schema",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SurprisingLineReport(
                schemaVersion = 1,
                generatedAtUtc = "2026-08-23T00:00:00Z",
                outerCommit = "outer",
                argentumCommit = "fork",
                sourceCorpusHash = "hash",
                requestedCases = 0,
                verifiedCases = 0,
                cases = emptyList(),
                passed = false,
            )
        }
    }

    @Test
    fun `public corpus manifest contains neither replay seed nor privileged file reference`() {
        val sourceProvenance = testSourceProvenance("outer", "fork")
        val entries = listOf(
            CorpusEntry(
                    gameId = "game-0",
                    publicTrajectory = "corpus/v5/public/game-0.jsonl.gz",
                    publicSha256 = "public-hash",
                    publicSizeBytes = 1L,
                    policyEvidenceIdentity = "policy-evidence-v1-sha256:${"a".repeat(64)}",
                    behaviorSpecificationSha256 = "b".repeat(64),
                    replayVerified = true,
                    game = CorpusGameSummary(
                        gameId = "game-0",
                        p0Policy = ArenaPolicyKind.SEARCH,
                        p1Policy = ArenaPolicyKind.HEURISTIC,
                        winner = "p0",
                        terminal = true,
                        disposition = GameRunDisposition.GAME_ENDED,
                        decisions = 3,
                        searchSeat = "p0",
                        searchScore = 1.0,
                        illegalResponses = 0,
                        fallbacks = 0,
                        stepLimit = false,
                    ),
                )
        )
        val datasetIdentity = CorpusManifest.computeDatasetIdentity(
            profileId = "test",
            profileHash = "hash",
            sourceProvenance = sourceProvenance,
            requestedGames = 1,
            terminalGames = 1,
            replayVerifiedGames = 1,
            entries = entries,
            passed = true,
        )
        val corpus = CorpusManifest(
            generatedAtUtc = "2026-08-23T00:00:00Z",
            profileId = "test",
            profileHash = "hash",
            outerCommit = "outer",
            argentumCommit = "fork",
            sourceProvenance = sourceProvenance,
            requestedGames = 1,
            terminalGames = 1,
            replayVerifiedGames = 1,
            entries = entries,
            passed = true,
            datasetIdentity = datasetIdentity,
        )

        val encoded = org.mtgallium.agent.infoset.core.PolicyJson.format.encodeToString(corpus)
        PublicArtifactPrivacy.requireSafeJson(encoded, "test corpus manifest")
        assertFalse("seed" in encoded.lowercase())
        assertFalse("privileged" in encoded)
        assertFalse("replayDiagnostic" in encoded)
    }

    @Test
    fun `frozen deck and complete compute grid load`() {
        val deck = loadDeckManifest()
        val grid = loadSearchGrid()

        assertEquals(60, deck.mainDeck.values.sum())
        assertEquals(15, deck.sideboard.values.sum())
        assertEquals(
            160,
            grid.particles.size * grid.simulations.size * grid.leafConfigurations.size *
                grid.actionSpaceProfiles.size,
        )
        assertEquals(
            listOf(
                SearchActionSpaceProfile.RULES_EXACT_V1,
                SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            ),
            grid.actionSpaceProfiles,
        )
        assertTrue(SearchActionSpaceProfile.RULES_EXACT_V1.rulesEquivalent)
        assertFalse(SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1.rulesEquivalent)
    }

    @ScenarioExecutionTest
    @Test
    fun `heuristic mirror completes mulligans and a full game without recovery`() {
        val manifest = loadDeckManifest()
        val registry = buildRegistry()
        val arena = SearchTeacherArena(registry, manifest, SearchTeacherArena.smokeProfile(), 9L)
        val evidenceRoot = createTempDirectory("mtgallium-inspection")
        val publicPath = evidenceRoot.resolve("p0.inspection.json")
        val privilegedPath = evidenceRoot.resolve("privileged/p0.privileged-inspection.json")
        val replayPath = evidenceRoot.resolve("replays/game.privileged.replay.jsonl.gz")
        val audits = mutableListOf<PerspectiveProjectionAudit>()
        var hiddenProbePassed = false
        var supportProbePassed = false
        var progressGameStarted = false
        var progressFinished: GameRunResult? = null
        val progressStarts = mutableListOf<ArenaDecisionProgress>()
        val progressCompletions = mutableListOf<ArenaDecisionProgress>()
        val progressObserver = object : ArenaProgressObserver {
            override fun gameStarted(gameId: String, p0PolicyId: String, p1PolicyId: String) {
                progressGameStarted = gameId == "00000000-0000-4000-8000-000000000117" &&
                    p0PolicyId == "heuristic" && p1PolicyId == "heuristic"
            }

            override fun decisionStarted(progress: ArenaDecisionProgress) {
                progressStarts += progress
            }

            override fun decisionCompleted(progress: ArenaDecisionProgress, elapsedMillis: Double) {
                assertTrue(elapsedMillis >= 0.0)
                progressCompletions += progress
            }

            override fun gameFinished(result: GameRunResult) {
                progressFinished = result
            }
        }

        val game = arena.play(
            "00000000-0000-4000-8000-000000000117",
            117L,
            ArenaPolicyKind.HEURISTIC,
            ArenaPolicyKind.HEURISTIC,
            evidence = GameEvidenceOptions(
                inspection = publicPath,
                privilegedInspection = privilegedPath,
                inspectionPerspective = "p0",
                outerCommit = "outer-test",
                argentumCommit = "fork-test",
                profileHash = "profile-test",
                sourceProvenance = testSourceProvenance("outer-test", "fork-test"),
            ),
            projectionAuditSink = PerspectiveProjectionAuditSink { audits += it },
            rootProbe = { world, actor, decisionIndex ->
                if (decisionIndex == 0) {
                    hiddenProbePassed = world.hiddenTruthConformanceProbe(actor).passed
                    supportProbePassed = world.verifyKnowledgeSupport(actor, 123L, 4).passed
                }
            },
            replay = GameReplayOptions(
                finalPath = replayPath,
                referencePath = "replays/game.privileged.replay.jsonl.gz",
                runIdentity = "test-run",
                outerCommit = "outer-test",
                argentumCommit = "fork-test",
            ),
            progressObserver = progressObserver,
        )

        assertTrue(game.terminal, game.toString())
        assertEquals(null, game.exception)
        assertEquals(0, game.illegalResponses)
        assertTrue(!game.stepLimit)
        assertEquals(game.decisions, game.liveOpponentPolicyDecisions.decisions)
        assertEquals(
            game.liveOpponentPolicyDecisions.decisions,
            game.liveOpponentPolicyDecisions.selectedComponents.values.sum(),
        )
        assertEquals(
            game.liveOpponentPolicyDecisions.replacementDecisions,
            game.liveOpponentPolicyDecisions.replacements.values.sum(),
        )
        assertEquals(
            game.liveOpponentPolicyDecisions.evidenceInvalidatingReplacements,
            game.fallbacks,
        )
        assertEquals(0, game.searchOpponentPolicyDecisions.decisions)
        assertEquals(0, game.heuristicComparatorDecisions.decisions)
        assertTrue(game.informationLedgerComplete, game.unsupportedInformationEvents.toString())
        assertEquals("replays/game.privileged.replay.jsonl.gz", game.replayPath)
        assertEquals(sha256File(replayPath), game.replaySha256)
        assertTrue(game.replayVerified, game.replayVerificationDiagnostic)
        assertTrue(TournamentReplayVerifier(registry, manifest).verify(replayPath).verified)
        val replayLines = readCanonicalReplay(replayPath)
        assertTrue(replayLines.size >= game.decisions + 2)
        assertEquals(game.gameId, (replayLines.first() as CanonicalReplayHeader).gameId)
        assertEquals(
            (0 until game.decisions).toList(),
            replayLines.mapNotNull { record ->
                (record as? org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayTransition)
                    ?.extensions?.get("mtgallium.decisionIndex")?.jsonPrimitive?.content?.toInt()
            },
        )
        val replayOpponentDecisions = replayLines.mapNotNull { record ->
            (record as? org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayTransition)
                ?.extensions?.get("mtgallium.opponentPolicyDecision")?.let { encoded ->
                    PolicyJson.format.decodeFromJsonElement(
                        OpponentPolicyDecisionDiagnostic.serializer(),
                        encoded,
                    )
                }
        }
        assertEquals(game.decisions, replayOpponentDecisions.size)
        assertEquals(
            game.liveOpponentPolicyDecisions,
            OpponentPolicyDecisionCounter().also { counter ->
                replayOpponentDecisions.forEach(counter::record)
            }.summary(),
        )
        val tamperedPath = evidenceRoot.resolve("replays/tampered.privileged.replay.jsonl.gz")
        val tampered = replayLines.mapIndexed { index, line ->
            if (index == 0) (line as CanonicalReplayHeader).copy(initialStateDigest = "0".repeat(64)) else line
        }
        GZIPOutputStream(Files.newOutputStream(tamperedPath)).bufferedWriter().use { output ->
            tampered.forEach { line ->
                output.appendLine(CanonicalReplayJson.encodeToString(CanonicalReplayRecord.serializer(), line))
            }
        }
        val tamperResult = TournamentReplayVerifier(registry, manifest).verify(tamperedPath)
        assertFalse(tamperResult.verified)

        val partialTarget = evidenceRoot.resolve("replays/interrupted.privileged.replay.jsonl.gz")
        val interrupted = CanonicalTournamentReplayWriter.create(
            options = GameReplayOptions(partialTarget, runIdentity = "interrupted-test"),
            initialState = GameState(),
            initializationEvents = emptyList(),
            players = listOf("p0", "p1"),
            extensions = buildJsonObject {
                put("gameId", JsonPrimitive("interrupted-test"))
                put("createdAtUtc", JsonPrimitive("2026-08-26T00:00:00Z"))
            },
        )
        val partialPath = interrupted.partialPath
        interrupted.preservePartial()
        assertFalse(Files.exists(partialTarget))
        assertTrue(Files.isRegularFile(partialPath))
        assertEquals(1, Files.readAllLines(partialPath).size)
        assertTrue(audits.isNotEmpty())
        assertTrue(audits.none { it.disposition == ProjectionDisposition.UNSUPPORTED })
        assertTrue(hiddenProbePassed)
        assertTrue(supportProbePassed)
        assertTrue(progressGameStarted)
        assertEquals(game.gameId, progressFinished?.gameId)
        assertEquals(game.decisions, progressStarts.size)
        assertEquals(progressStarts, progressCompletions)
        assertEquals((0 until game.decisions).toList(), progressStarts.map { it.decisionIndex })
        assertTrue(progressStarts.all { it.turnNumber > 0 && it.actor in setOf("p0", "p1") })
        assertTrue(progressStarts.all { it.lifeTotals.keys == setOf("p0", "p1") })
        val inspection = PolicyJson.format.decodeFromString<PolicyInspectionBundle>(Files.readString(publicPath))
        val privileged = PolicyJson.format.decodeFromString<PrivilegedInspectionBundle>(Files.readString(privilegedPath))
        val publicJson = Files.readString(publicPath)
        assertEquals(game.decisions + 1, inspection.frames.size)
        assertEquals(inspection.ledger, inspection.informationState(inspection.frames.lastIndex).history)
        assertEquals(INSPECTION_SCHEMA_CURRENT, inspection.schemaVersion)
        assertEquals(INSPECTION_SCHEMA_CURRENT, privileged.schemaVersion)
        assertTrue(inspection.presentation.cardImages.isNotEmpty())
        assertTrue(inspection.presentation.cardImages.any { it.cardName == "Hired Claw" })
        assertTrue(inspection.presentation.cardImages.all { it.imageUri.startsWith("https://cards.scryfall.io/") })
        assertEquals(
            inspection.presentation.cardImages.sortedBy { it.key },
            inspection.presentation.cardImages,
        )
        val safeWithoutPresentation = PolicyJson.format.encodeToString(
            PolicyInspectionBundle.serializer(),
            inspection.copy(presentation = PolicyInspectionPresentation()),
        )
        inspection.presentation.cardImages.forEach { image ->
            assertTrue(image.cardName in safeWithoutPresentation, "Art catalog introduced unsafe name ${image.cardName}")
        }
        val policyInput = PolicyJson.format.encodeToString(
            org.mtgallium.agent.infoset.core.PolicyInformationState.serializer(),
            inspection.informationState(0),
        )
        assertFalse("imageUri" in policyInput)
        assertFalse("presentation" in policyInput)
        assertEquals(sha256File(publicPath), privileged.sourcePublicSha256)
        assertEquals(inspection.frames.size, privileged.frames.size)
        PublicArtifactPrivacy.requireSafeJson(publicJson, "test inspection")
        assertFalse("seed" in publicJson.lowercase())
        assertFalse("hiddenHands" in publicJson)
        assertFalse("libraries" in publicJson)

        val resolver = InspectionCardPresentationResolver(registry, manifest.mainDeck.keys)
        assertEquals(inspection.presentation, resolver.safe(inspection))
        val firstVisibleCard = inspection.frames.asSequence()
            .flatMap { frame -> frame.observation.zones.asSequence().flatMap { it.cards.asSequence() } }
            .first()
        val withFaceDownCard = inspection.copy(
            frames = inspection.frames.mapIndexed { index, frame ->
                if (index != 0) frame else frame.copy(
                    observation = frame.observation.copy(
                        zones = frame.observation.zones.mapIndexed { zoneIndex, zone ->
                            if (zoneIndex != 0) zone else zone.copy(
                                cards = zone.cards + firstVisibleCard.copy(
                                    definitionId = null,
                                    name = "Plains",
                                    faceDown = true,
                                ),
                            )
                        },
                    ),
                )
            },
        )
        assertFalse(resolver.safe(withFaceDownCard).cardImages.any { it.cardName == "Plains" })
        val withBackFaceKnowledge = inspection.copy(
            frames = inspection.frames.mapIndexed { index, frame ->
                if (index != 0) frame else frame.copy(
                    knowledge = frame.knowledge.copy(
                        deckCardCounts = frame.knowledge.deckCardCounts +
                            ("art-test" to mapOf("Temple of Power" to 1)),
                    ),
                )
            },
        )
        assertTrue(resolver.safe(withBackFaceKnowledge).cardImages.any { it.cardName == "Temple of Power" })

        assertTrue(registry.hasCard("Plains"))
        val truthOnly = privileged.copy(
            frames = privileged.frames.mapIndexed { index, frame ->
                if (index != 0) frame else frame.copy(
                    snapshot = frame.snapshot.copy(
                        hiddenHands = frame.snapshot.hiddenHands + ("truth-only" to listOf("Plains")),
                    ),
                )
            },
        )
        assertTrue(resolver.privileged(truthOnly).cardImages.any { it.cardName == "Plains" })
        assertFalse(inspection.presentation.cardImages.any { it.cardName == "Plains" })
    }

    @Test
    fun `arena writes normalized v3 policy inputs reconstructable from the event ledger`() {
        val manifest = loadDeckManifest()
        val profile = SearchTeacherArena.smokeProfile().copy(maxPolicyDecisions = 1)
        val trajectory = createTempDirectory("mtgallium-v3-trajectory").resolve("game.jsonl.gz")
        val planner = trajectory.parent.resolve("game.planner.json.gz")
        val game = SearchTeacherArena(buildRegistry(), manifest, profile, 13L).play(
            gameId = "normalized-v3-test",
            gameSeed = 117L,
            p0Policy = ArenaPolicyKind.SEARCH,
            p1Policy = ArenaPolicyKind.HEURISTIC,
            evidence = GameEvidenceOptions(
                publicTrajectory = trajectory,
                plannerEvidence = planner,
                publicTrajectoryReference = "public/game.jsonl.gz",
                researchRunIdentity = "research-run-v1-sha256:smoke",
                outerCommit = "outer-test",
                argentumCommit = "fork-test",
                profileHash = "profile-test",
                sourceProvenance = testSourceProvenance("outer-test", "fork-test"),
            ),
            maxSearchDecisions = 1,
        )

        assertFalse(game.terminal, game.toString())
        assertEquals(null, game.exception)
        val records = GZIPInputStream(Files.newInputStream(trajectory)).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }
                .map { PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(it) }
                .toList()
        }
        assertTrue(records.all { it.schemaVersion == TRAJECTORY_SCHEMA_CURRENT })
        assertTrue(records.any { it is PolicyTrajectoryDecision })
        val sidecar = PlannerEvidenceSidecar.readCompressed(planner)
        assertEquals(sha256File(trajectory), sidecar.binding.safeTrajectorySha256)
        assertEquals("public/game.jsonl.gz", sidecar.binding.safeTrajectoryReference)
        val trajectoryDecisions = records.filterIsInstance<PolicyTrajectoryDecision>()
        assertEquals(trajectoryDecisions.map { it.decisionIndex }, sidecar.decisions.map { it.decisionIndex })
        sidecar.decisions.forEach { sidecarDecision ->
            val trajectoryDecision = trajectoryDecisions.single { it.decisionIndex == sidecarDecision.decisionIndex }
            assertEquals(trajectoryDecision.chosen.signature, sidecarDecision.selectedCandidateSignature)
            assertEquals(
                trajectoryDecision.candidates.map { it.choice.signature }.toSet(),
                sidecarDecision.candidates.map { it.candidateSignature }.toSet(),
            )
            assertTrue(sidecarDecision.candidates.all { it.rawVisits == it.settlementCounts.successfulBackups })
        }
        val header = records.first() as PolicyTrajectoryHeader
        val expectedBehavior = profile.policyParameters(
            baseSeed = 13L,
            beliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
            beliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
        ).behaviorSpecification(
            knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
            opponentPolicy = defaultMonoRedOpponentPolicy(),
            integration = SearchTeacherIntegrationSpecification(
                hostMode = "evaluation-arena-v1",
                searchPlanner = SearchPlannerKind.SHARED_TREE.name,
                maximumGameDecisions = 2_048,
                maximumSearchDecisions = 1,
                additionalBindings = mapOf(
                    "arenaPolicyId" to ArenaPolicyKind.SEARCH.name.lowercase(),
                    "arenaPolicyKind" to ArenaPolicyKind.SEARCH.name,
                ),
            ),
        )
        assertEquals(header.policyVersion, header.behaviorBinding.identity)
        assertEquals(
            SearchTeacherPolicyIdentity.identity(expectedBehavior),
            header.behaviorBinding.behaviorIdentity,
        )
        assertEquals(
            PolicyJson.digest(
                PolicyJson.format.encodeToJsonElement(
                    SearchTeacherBehaviorSpecification.serializer(),
                    expectedBehavior,
                ),
            ),
            header.behaviorBinding.behaviorSpecificationSha256,
        )
        assertFalse(records.first().toString().contains("baseSeed"))
        assertEquals(profile.actionSpaceProfile, expectedBehavior.actionSpace.expansion.actionSpaceProfile)
        assertEquals(manifest.mainDeck, expectedBehavior.knownDecks.single { it.playerId == "p0" }
            .cards.associate { it.cardName to it.count })

        val ledger = mutableListOf<PolicyHistoryEvent>()
        records.forEach { record ->
            when (record) {
                is PolicyTrajectoryDecision -> {
                    record.policyInput.requireValidDigest()
                    assertEquals(record.historyCursor, ledger.size)
                    assertEquals(record.informationStateDigest, record.informationState(ledger).informationStateDigest)
                    assertTrue(record.policyInput.recentEvents.size <= 64)
                }
                is PolicyTrajectoryForcedTransition -> ledger += record.events
                else -> Unit
            }
        }
    }

    @Test
    fun `pre-choice representation stop prevents any policy choice`() {
        val roots = mutableListOf<Int>()
        val detector = RepresentationBoundaryDetector { _, point, _ ->
            if (point == EvidenceStopDetectionPoint.BEFORE_POLICY_CHOICE) {
                RepresentationBoundaryFailure(listOf("TEST_PRE_CHOICE"), listOf("p0"))
            } else {
                null
            }
        }
        val game = SearchTeacherArena(
            buildRegistry(),
            loadDeckManifest(),
            SearchTeacherArena.smokeProfile(),
            9L,
            representationBoundaryDetector = detector,
        ).play(
            "00000000-0000-4000-8000-000000000400",
            400L,
            ArenaPolicyKind.HEURISTIC,
            ArenaPolicyKind.HEURISTIC,
            rootProbe = { _, _, decisionIndex -> roots += decisionIndex },
        )

        assertEquals(emptyList(), roots)
        assertEquals(GameRunDisposition.STOPPED_REPRESENTATION, game.disposition)
        assertEquals(EvidenceStopDetectionPoint.BEFORE_POLICY_CHOICE, game.evidenceStop?.detectionPoint)
        assertNull(game.evidenceStop?.triggeringDecisionIndex)
        assertEquals(0, game.evidenceStop?.refusedPolicyDecisionIndex)
    }

    @Test
    fun `post-transition representation stop refuses the next policy choice`() {
        val roots = mutableListOf<Int>()
        val detector = RepresentationBoundaryDetector { _, point, decisionIndex ->
            if (point == EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION && decisionIndex == 0) {
                RepresentationBoundaryFailure(listOf("TEST_POST_TRANSITION"), listOf("p0"))
            } else {
                null
            }
        }
        val game = SearchTeacherArena(
            buildRegistry(),
            loadDeckManifest(),
            SearchTeacherArena.smokeProfile(),
            9L,
            representationBoundaryDetector = detector,
        ).play(
            "00000000-0000-4000-8000-000000000401",
            401L,
            ArenaPolicyKind.HEURISTIC,
            ArenaPolicyKind.HEURISTIC,
            rootProbe = { _, _, decisionIndex -> roots += decisionIndex },
        )

        assertEquals(listOf(0), roots)
        assertEquals(GameRunDisposition.STOPPED_REPRESENTATION, game.disposition)
        assertFalse(game.terminal)
        assertNull(game.winner)
        assertNull(game.searchScore)
        assertEquals(EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION, game.evidenceStop?.detectionPoint)
        assertEquals(0, game.evidenceStop?.triggeringDecisionIndex)
        assertEquals(1, game.evidenceStop?.refusedPolicyDecisionIndex)
    }

    @ScenarioExecutionTest
    @Test
    fun `representation trigger on a terminal engine action suppresses every result label`() {
        var terminalTransitionObserved = false
        val detector = RepresentationBoundaryDetector { world, point, decisionIndex ->
            if (point == EvidenceStopDetectionPoint.AFTER_ACCEPTED_TRANSITION &&
                decisionIndex != null && world.terminalPayoff("p0") != null
            ) {
                terminalTransitionObserved = true
                RepresentationBoundaryFailure(listOf("TEST_TERMINAL_TRIGGER"), listOf("p0"))
            } else {
                null
            }
        }
        val root = createTempDirectory("mtgallium-o04-terminal-stop")
        val inspection = root.resolve("inspection.json")
        val replay = root.resolve("terminal-stop.privileged.replay.jsonl.gz")
        val game = SearchTeacherArena(
            buildRegistry(),
            loadDeckManifest(),
            SearchTeacherArena.smokeProfile(),
            9L,
            representationBoundaryDetector = detector,
        ).play(
            "00000000-0000-4000-8000-000000000117",
            117L,
            ArenaPolicyKind.HEURISTIC,
            ArenaPolicyKind.HEURISTIC,
            evidence = GameEvidenceOptions(inspection = inspection, inspectionPerspective = "p0"),
            replay = GameReplayOptions(replay, runIdentity = "o04-terminal-trigger"),
        )

        assertTrue(terminalTransitionObserved)
        assertEquals(GameRunDisposition.STOPPED_REPRESENTATION, game.disposition)
        assertFalse(game.terminal)
        assertNull(game.winner)
        assertNull(game.searchScore)
        assertFalse(Files.exists(inspection), "Stopped runs must not emit an inspection outcome/payoff label")
        val terminal = readCanonicalReplay(replay).last() as CanonicalReplayTerminal
        assertEquals(ReplayCompletionStatus.INCOMPLETE, terminal.status)
        assertNull(terminal.winnerId)
    }

    private fun testSourceProvenance(
        outerRevision: String,
        argentumRevision: String,
    ): PolicySourceProvenance {
        val empty = PolicyJson.sha256("")
        return PolicySourceProvenance(
            expectedArgentumRevision = argentumRevision,
            outer = PolicySourceTreeState(outerRevision, empty, empty, empty),
            argentum = PolicySourceTreeState(argentumRevision, empty, empty, empty),
        )
    }

    @Test
    fun `paired bootstrap is deterministic and detects a sweep`() {
        val values = List(20) { 0.5 }
        val first = bootstrapInterval(values, seed = 4L, samples = 10_000)
        val second = bootstrapInterval(values, seed = 4L, samples = 10_000)

        assertEquals(first, second)
        assertEquals(0.5 to 0.5, first)
    }

    @Test
    fun `tactical catalog has the promised coverage`() {
        TacticalBenchmarkCatalog.validate()

        assertEquals(48, TacticalBenchmarkCatalog.cases.size)
        assertTrue(TacticalBenchmarkCatalog.cases.all { it.mechanicallyVerifiable })
        assertEquals(18, TacticalBenchmarkCatalog.cases.count { it.id.startsWith("lethal-") })
        assertEquals(6, TacticalBenchmarkCatalog.cases.count { it.id.startsWith("attack-") })
        assertEquals(12, TacticalBenchmarkCatalog.cases.count { it.id.startsWith("block-") })
        assertFalse(TacticalBenchmarkCatalog.cases.any { it.requiresMoreThan64Responses })
        assertEquals(6, TacticalBenchmarkCatalog.cases.mapNotNull { it.hiddenFamily }.distinct().size)
    }

    @ScenarioExecutionTest
    @Test
    fun `scenario roots use bounded combat quotients and masked hidden pairs`() {
        val manifest = loadDeckManifest()
        val factory = TacticalScenarioFactory(buildRegistry(), manifest)
        fun named(id: String) = TacticalBenchmarkCatalog.cases.single { it.id == id }

        val triggerAttack = factory.create(named("attack-06")).expandChoices(2_048)
        // Two identical Hired Claws form a count class while the Squelcher remains distinct.
        assertEquals(6, triggerAttack.candidates.size)
        assertEquals(6L, triggerAttack.estimatedCandidateCount)
        assertTrue(triggerAttack.isExhaustive)

        val liveAttack = factory.create(named("attack-06"))
        liveAttack.expandChoices()
        assertTrue(liveAttack.step(triggerAttack.candidates.last()).accepted)

        val blocking = factory.create(named("block-01"))
        assertEquals("p1", blocking.actorToAct())
        val blockingInformation = blocking.informationState("p1")
        assertEquals("DECLARE_BLOCKERS", blockingInformation.observation.step)
        assertTrue(blockingInformation.history.isNotEmpty())
        assertTrue(
            blockingInformation.observation.zones
                .single { it.ownerId == "p0" && it.zone == "BATTLEFIELD" }
                .cards.all { it.tapped }
        )
        val blockChoices = blocking.expandChoices(2_048)
        assertEquals(4, blockChoices.candidates.size)
        assertEquals(4L, blockChoices.estimatedCandidateCount)
        assertTrue(blockChoices.isExhaustive)
        val blockTopologies = blockChoices.candidates.map { choice ->
            val assignments = choice.canonicalPayload["body"]!!.jsonObject["blockers"]!!.jsonObject
            assignments.values.flatMap { targets -> targets.jsonArray.map { it.jsonPrimitive.content } }
                .groupingBy { it }.eachCount().values.sorted()
        }.toSet()
        assertEquals(setOf(emptyList(), listOf(1), listOf(2), listOf(1, 1)), blockTopologies)
        assertTrue(blocking.step(blockChoices.candidates.last()).accepted)

        val largerBlocking = factory.create(named("block-02")).expandChoices(2_048)
        assertEquals(7, largerBlocking.candidates.size)
        assertEquals(7L, largerBlocking.estimatedCandidateCount)
        assertTrue(largerBlocking.isExhaustive)

        val tutorialBlockCounts = mapOf(
            "block-01" to 4,
            "block-02" to 7,
            "block-03" to 12,
            "block-04" to 3,
            "block-05" to 3,
            "block-06" to 6,
            "block-07" to 2,
            "block-08" to 3,
            "block-09" to 2,
            "block-10" to 4,
            "block-11" to 9,
            "block-12" to 6,
        )
        tutorialBlockCounts.forEach { (id, expected) ->
            val expansion = factory.create(named(id)).expandChoices(2_048)
            assertEquals(expected, expansion.candidates.size, id)
            assertEquals(expected.toLong(), expansion.estimatedCandidateCount, id)
            assertTrue(expansion.isExhaustive, id)
        }

        (1..12).map { "block-${it.toString().padStart(2, '0')}" }.forEach { id ->
            val initialBlockExpansion = factory.create(named(id)).expandChoices()
            assertTrue(initialBlockExpansion.isExhaustive, "$id exceeds the initial semantic proposal")
            assertEquals(initialBlockExpansion.candidates.size.toLong(), initialBlockExpansion.estimatedCandidateCount)
        }

        (1..12).map { "block-${it.toString().padStart(2, '0')}" }.forEach { id ->
            val definition = named(id)
            val world = factory.create(definition)
            val choices = world.expandChoices(2_048).candidates
            val acceptable = mechanicallyAcceptableChoices(world, "p1", choices, definition)
            assertTrue(acceptable.isNotEmpty(), "$id has no survival line")
            assertTrue(acceptable.size < choices.size, "$id has no failing alternative")
        }
        mapOf("block-04" to 2, "block-05" to 1, "block-06" to 1).forEach { (id, expected) ->
            val definition = named(id)
            val world = factory.create(definition)
            val acceptable = mechanicallyAcceptableChoices(world, "p1", world.expandChoices(2_048).candidates, definition)
            assertEquals(expected, acceptable.size, "$id survival alternatives")
        }
        assertTrue((1..12).map { "block-${it.toString().padStart(2, '0')}" }.all { named(it).mechanicallyVerifiable })

        (1..6).forEach { family ->
            val hiddenA = factory.create(named("hidden-$family-1")).informationState("p0")
            val hiddenB = factory.create(named("hidden-$family-2")).informationState("p0")
            assertEquals(hiddenA.informationStateDigest, hiddenB.informationStateDigest, "hidden family $family")
            assertEquals(
                hiddenA.candidates.map { it.signature },
                hiddenB.candidates.map { it.signature },
                "hidden family $family candidates",
            )
        }
    }

    @ScenarioExecutionTest
    @Test
    fun `every tactical puzzle is exhaustive bounded and mechanically discriminating`() {
        val factory = TacticalScenarioFactory(buildRegistry(), loadDeckManifest())

        TacticalBenchmarkCatalog.cases.forEach { definition ->
            val world = factory.create(definition)
            val actor = requireNotNull(world.actorToAct())
            val expansion = world.expandChoices(2_048)
            assertTrue(expansion.isExhaustive, "${definition.id} is not exhaustive")
            assertTrue(expansion.candidates.size in 2..12, "${definition.id} has ${expansion.candidates.size} choices")
            val acceptable = mechanicallyAcceptableChoices(world, actor, expansion.candidates, definition)
            assertTrue(acceptable.isNotEmpty(), "${definition.id} has no mechanically winning line")
            assertTrue(acceptable.size < expansion.candidates.size, "${definition.id} accepts every legal choice")
        }
    }

    @Test
    fun `parallel evaluation preserves deterministic index order`() {
        val sequential = parallelMapOrdered(64, 1) { it * it }
        val parallel = parallelMapOrdered(64, 4) { it * it }

        assertEquals(sequential, parallel)
    }

    @Test
    fun `compute trend checks consecutive simulation budgets within one configuration family`() {
        fun point(simulations: Int, score: Double) = CalibrationPoint(
            particles = 8,
            simulations = simulations,
            leaf = LeafEvaluationConfig(
                LeafStateSource.CURRENT_INFORMATION_STATE,
                LeafEvaluator.MTGALLIUM_VISIBLE_V2,
            ),
            decisionLatenciesMillis = listOf(1.0),
            p50Millis = 1.0,
            p95Millis = 1.0,
            tacticalScore = score,
            standardError = 0.0,
            meanExpansionMillis = 0.1,
            meanBeliefMillis = 0.2,
            meanSearchMillis = 0.7,
        )
        val intervals = computeImprovementIntervals(
            listOf(point(1_024, 0.5), point(64, 0.25), point(256, 0.5))
        )

        assertEquals(listOf(64 to 256, 256 to 1_024), intervals.map { it.fromSimulations to it.toSimulations })
        assertTrue(intervals.first().improved)
        assertFalse(intervals.last().improved)
    }

    @Test
    fun `arena shards merge in global pair order and preserve paired statistics`() {
        fun game(pair: Int, suffix: String, searchSeat: String) = GameRunResult(
            gameId = "pair-$pair-$suffix",
            seed = pair.toLong(),
            p0Policy = if (searchSeat == "p0") ArenaPolicyKind.SEARCH else ArenaPolicyKind.HEURISTIC,
            p1Policy = if (searchSeat == "p1") ArenaPolicyKind.SEARCH else ArenaPolicyKind.HEURISTIC,
            winner = searchSeat,
            terminal = true,
            disposition = GameRunDisposition.GAME_ENDED,
            decisions = 1,
            searchSeat = searchSeat,
            searchScore = 1.0,
            illegalResponses = 0,
            fallbacks = 0,
            stepLimit = false,
        )
        fun shard(pair: Int) = PairedArenaShard(
            generatedAtUtc = "test",
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            profileId = "fast-arena-v1",
            runIdentity = "run-v1",
            opponent = ArenaPolicyKind.HEURISTIC,
            baseSeed = 17L,
            pairOffset = pair,
            pairCount = 1,
            workerThreads = 1,
            pairIndexes = listOf(pair),
            games = listOf(game(pair, "a", "p0"), game(pair, "b", "p1")),
        )

        val report = aggregatePairedArenaShards(
            profileId = "fast-arena-v1",
            runIdentity = "run-v1",
            opponent = ArenaPolicyKind.HEURISTIC,
            expectedPairCount = 2,
            baseSeed = 17L,
            shards = listOf(shard(1), shard(0)),
        )

        assertEquals(listOf("pair-0-a", "pair-0-b", "pair-1-a", "pair-1-b"), report.games.map { it.gameId })
        assertEquals(0.5, report.pointImprovement)
        assertTrue(report.primaryGatePassed)

        val legacyShard = shard(0).let { candidate ->
            candidate.copy(
                games = candidate.games.map {
                    it.copy(disposition = GameRunDisposition.LEGACY_UNCLASSIFIED)
                },
            )
        }
        val legacyReport = aggregatePairedArenaShards(
            profileId = "fast-arena-v1",
            runIdentity = "run-v1",
            opponent = ArenaPolicyKind.HEURISTIC,
            expectedPairCount = 1,
            baseSeed = 17L,
            shards = listOf(legacyShard),
        )

        assertEquals(0, legacyReport.completePairs)
        assertFalse(legacyReport.primaryGatePassed)
        assertTrue(legacyReport.failureReasons.any { "legacy-unclassified" in it })
    }

    @ScenarioExecutionTest
    @Test
    fun `independent determinization returns a live semantic root action`() {
        val manifest = loadDeckManifest()
        val registry = buildRegistry()
        val world = TacticalScenarioFactory(registry, manifest).create(
            TacticalBenchmarkCatalog.cases.single { it.id == "lethal-01" }
        )
        val actor = requireNotNull(world.actorToAct())
        val belief = ArgentumKnownDeckBeliefWorldSource(world).sample(
            world.informationState(actor),
            mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
            ComponentSeeds.derive("independent-test"),
            8,
        )

        val result = independentDeterminizationSearch(
            actor,
            belief,
            19L,
            SearchTeacherArena.smokeProfile(),
            defaultMonoRedOpponentPolicy(),
        )

        assertTrue(result.chosen.signature in world.expandChoices(2_048).candidates.map { it.signature })
        assertEquals(64, result.diagnostics.simulations)
    }

    @Test
    fun `strategic regret requires a separated reference and is normalized`() {
        val comparison = compareWithReference(
            values = mapOf("best" to 0.8, "search" to 0.6, "heuristic" to -0.2),
            chosenSignature = "search",
            heuristicSignature = "heuristic",
        )

        requireNotNull(comparison)
        assertEquals("best", comparison.bestSignature)
        assertEquals(0.1, comparison.searchRegret, absoluteTolerance = 1e-12)
        assertEquals(0.5, comparison.heuristicRegret, absoluteTolerance = 1e-12)
        assertNull(
            compareWithReference(
                values = mapOf("a" to 0.4, "b" to 0.39),
                chosenSignature = "a",
                heuristicSignature = "b",
            )
        )
    }

    @Test
    fun `privileged snapshot hashes authoritative state and remains one json line`() {
        val manifest = loadDeckManifest()
        val world = TacticalScenarioFactory(buildRegistry(), manifest).create(
            TacticalBenchmarkCatalog.cases.single { it.id == "lethal-01" }
        )
        val record = PrivilegedDebugLine(
            gameId = "debug-codec",
            decisionIndex = 0,
            chosenChoice = null,
            snapshot = world.privilegedDebugSnapshot(),
        )
        val encoded = org.mtgallium.agent.infoset.core.PolicyJson.format.encodeToString(record)

        assertFalse(encoded.contains('\n'))
        assertEquals(
            record,
            org.mtgallium.agent.infoset.core.PolicyJson.format.decodeFromString<PrivilegedDebugLine>(encoded),
        )
        assertEquals(64, record.snapshot.authoritativeSemanticDigest.length)
    }
}
