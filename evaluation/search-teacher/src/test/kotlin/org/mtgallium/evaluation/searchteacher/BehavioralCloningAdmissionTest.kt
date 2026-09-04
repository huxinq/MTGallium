package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.BOUNDED_POLICY_INPUT_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.BOUNDED_POLICY_INPUT_SCHEMA_V4
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler
import org.mtgallium.agent.infoset.core.PolicyBeliefSummary
import org.mtgallium.agent.infoset.core.PolicyBehaviorBinding
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyInformationStateDigest
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.infoset.core.PolicyTrajectoryCompletion
import org.mtgallium.agent.infoset.core.PolicyTrajectoryDecision
import org.mtgallium.agent.infoset.core.PolicyTrajectoryForcedTransition
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryOutcome
import org.mtgallium.agent.infoset.core.PolicyTrajectoryWriter
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.PolicyExpansionOmissionReason
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.searchteacher.SearchTeacherBehaviorSpecification
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

class BehavioralCloningAdmissionTest {
    @Test
    fun `compact oracle with production projection session accepted step and writer yields one tuple`() {
        val fixture = fixture()

        val result = admission(fixture).extract(fixture.manifest)

        assertTrue(result.passed, result.failures.toString())
        val example = result.examples.single()
        assertEquals(source.decision.policyInput, example.policyInput)
        assertEquals(source.decision.chosen, example.teacherAction)
        assertEquals("p0", example.actingPlayerId)
        assertEquals(BOUNDED_POLICY_INPUT_SCHEMA_CURRENT, example.policyInput.schemaVersion)
        assertTrue(example.policyInput.observation.currentTurnStateComplete)
        assertEquals(SearchPlannerKind.SHARED_TREE, example.evidence.searchPlanner)
        assertEquals(SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1, example.evidence.actionSpaceProfile)
        assertEquals(source.profile.particles, example.evidence.particles)
        assertEquals(source.profile.simulations, example.evidence.simulations)
        assertEquals(source.binding.identity, example.evidence.policyEvidenceIdentity)

        val encoded = PolicyJson.format.encodeToString(BehavioralCloningExample.serializer(), example)
        listOf("beliefDiagnostics", "searchDiagnostics", "rootValue", "proposalSeed").forEach { privateField ->
            assertFalse(privateField in encoded, encoded)
        }
    }

    @Test
    fun `privileged belief display-equivalent response and obsolete bounded schema are refused`() {
        val privilegedDiagnostics = source.decision.beliefDiagnostics.copy(
            architecture = BeliefArchitecture.PRIVILEGED_O_V1,
        )
        val privilegedInput = BoundedPolicyInputCompiler.compile(
            source.decision.informationState(emptyList()),
            PolicyBeliefSummary.from(
                privilegedDiagnostics,
                source.decision.policyInput.knowledge.knowledgeDigest,
            ),
        )
        val privilegedBeliefVersion = beliefVersion(privilegedDiagnostics.architecture, privilegedDiagnostics.mode)
        val privileged = source.decision.copy(
            beliefVersion = privilegedBeliefVersion,
            policyInput = privilegedInput,
            beliefDiagnostics = privilegedDiagnostics,
        )
        assertRefused(
            fixture(
                header = source.header.copy(beliefVersion = privilegedBeliefVersion),
                decision = privileged,
            ),
            "privileged belief representation",
        )

        val displayEquivalentAccepted = source.decision.chosen.copy(
            display = source.decision.chosen.display.copy(
                label = source.decision.chosen.display.label + " (different durable response)",
            ),
        )
        assertEquals(source.decision.chosen.signature, displayEquivalentAccepted.signature)
        assertFalse(source.decision.chosen == displayEquivalentAccepted)
        assertRefused(
            fixture(outcome = outcome(displayEquivalentAccepted)),
            "does not equal the accepted semantic response",
        )

        val differentTransitionChoice = source.decision.expansion.candidates.first {
            it.signature != source.decision.chosen.signature
        }
        val transitionWithDifferentChoice = source.transition.copy(
            events = source.transition.events.map { event ->
                val detail = event.detail as? PerspectiveEventDetail.Choice ?: return@map event
                event.copy(
                    payload = JsonObject(
                        event.payload + ("signature" to JsonPrimitive(differentTransitionChoice.signature))
                    ),
                    detail = detail.copy(semanticSignature = differentTransitionChoice.signature),
                )
            },
        )
        assertRefused(
            fixture(transition = transitionWithDifferentChoice),
            "accepted response disagrees with the perspective-safe transition",
        )

        val transitionWithWrongChoiceKind = source.transition.copy(
            events = source.transition.events.map { event ->
                val detail = event.detail as? PerspectiveEventDetail.Choice ?: return@map event
                event.copy(
                    detail = detail.copy(
                        choiceKind = if (source.decision.chosen.kind == SemanticChoiceKind.ACTION) {
                            SemanticChoiceKind.DECISION.name
                        } else {
                            SemanticChoiceKind.ACTION.name
                        },
                    ),
                )
            },
        )
        assertRefused(
            fixture(transition = transitionWithWrongChoiceKind),
            "response type disagrees with the perspective-safe transition",
        )

        assertRefused(
            fixture(
                header = source.header.copy(
                    boundedInputSchemaVersion = BOUNDED_POLICY_INPUT_SCHEMA_V4,
                ),
            ),
            "obsolete bounded-policy schema",
        )
    }

    @Test
    fun `producer failures and non-strategic decisions fail closed`() {
        val limitExhaustedDecision = source.decision.copy(
            expansion = source.decision.expansion.copy(
                isExhaustive = false,
                isProfileExhaustive = false,
                omissionReasons = setOf(PolicyExpansionOmissionReason.RESPONSE_LIMIT),
            ),
        )
        val rejectedTransitionDecision = source.decision.copy(
            searchDiagnostics = source.decision.searchDiagnostics.copy(rejectedTransitions = 1),
        )
        val wrongInvokedEvaluatorDecision = source.decision.copy(
            searchDiagnostics = source.decision.searchDiagnostics.copy(
                invokedEvaluatorId = "not-the-declared-evaluator",
            ),
        )
        val incompleteFixedWorkDecision = source.decision.copy(
            searchDiagnostics = source.decision.searchDiagnostics.copy(
                simulations = source.profile.simulations - 1,
                freshSimulations = source.profile.simulations - 1,
            ),
        )
        val cases = listOf(
            RejectionCase(
                "incomplete represented-information ledger",
                game = { it.copy(
                    informationLedgerComplete = false,
                    unsupportedInformationEvents = listOf("UNREPRESENTED:fixture"),
                ) },
            ),
            RejectionCase(
                "stopped run",
                game = { it.copy(disposition = GameRunDisposition.STOPPED_LIMIT) },
            ),
            RejectionCase(
                "software failure category",
                game = { it.copy(failureCategory = "FixtureFailure") },
            ),
            RejectionCase(
                "illegal live response",
                game = { it.copy(illegalResponses = 1) },
            ),
            RejectionCase(
                "evidence-invalidating fallback",
                game = { it.copy(fallbacks = 1) },
            ),
            RejectionCase(
                "game or decision limit",
                game = { it.copy(stepLimit = true) },
            ),
            RejectionCase(
                "canonical replay is not verified",
                replayVerified = false,
            ),
            RejectionCase(
                "manifest was not admitted",
                manifestPassed = false,
            ),
            RejectionCase(
                "does not bind exactly one actual Search Teacher seat",
                game = { it.copy(searchSeat = "p1") },
            ),
            RejectionCase(
                "search decision is not bound to the current accepted decision",
                decision = source.decision.copy(decisionIndex = 1),
            ),
            RejectionCase(
                "teacher search contains a rejected transition",
                decision = rejectedTransitionDecision,
            ),
            RejectionCase(
                "search invoked a different evaluator",
                decision = wrongInvokedEvaluatorDecision,
            ),
            RejectionCase(
                "did not complete the admitted fixed-work profile",
                decision = incompleteFixedWorkDecision,
            ),
            RejectionCase(
                "teacher expansion exhausted",
                decision = limitExhaustedDecision,
            ),
        )

        cases.forEach { case ->
            assertRefused(
                fixture(
                    decision = case.decision,
                    game = case.game,
                    replayVerified = case.replayVerified,
                    manifestPassed = case.manifestPassed,
                ),
                case.expectedFailure,
            )
        }
    }

    @Test
    fun `accepted action must be the searched winner and terminal or forced decisions are refused`() {
        val acceptedNonWinner = source.decision.candidates.first {
            it.choice != source.decision.chosen
        }.choice
        assertRefused(
            fixture(
                decision = source.decision.copy(chosen = acceptedNonWinner),
                transition = transitionFor(acceptedNonWinner),
                outcome = outcome(acceptedNonWinner),
            ),
            "deterministic winner of serialized search statistics",
        )

        val terminalInformation = source.decision.informationState(emptyList()).copy(
            terminated = true,
            winnerId = "p0",
        )
        val terminalInput = BoundedPolicyInputCompiler.compile(
            terminalInformation,
            source.decision.policyInput.belief,
        )
        assertRefused(
            fixture(decision = source.decision.copy(policyInput = terminalInput)),
            "terminal policy input is not a strategic Search Teacher decision",
        )

        val pass = SemanticChoice.create(
            kind = SemanticChoiceKind.ACTION,
            operationFamily = SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay("Pass priority"),
            canonicalPayload = JsonObject(emptyMap()),
        )
        val passExpansion = PolicyExpansion(
            candidates = listOf(pass),
            isExhaustive = true,
            estimatedCandidateCount = 1,
            proposalVersion = "forced-pass-fixture-v1",
        )
        val priorInformation = source.decision.informationState(emptyList())
        val passInformation = priorInformation.copy(
            informationStateDigest = PolicyInformationStateDigest.compute(
                observationDigest = priorInformation.observation.observationDigest,
                historyCommitment = priorInformation.historyCommitment,
                knowledgeDigest = priorInformation.knowledge.knowledgeDigest,
                actingPlayerId = priorInformation.actingPlayerId,
                candidateSignatures = listOf(pass.signature),
                proposalVersion = passExpansion.proposalVersion,
            ),
            candidates = listOf(pass),
        )
        val passInput = BoundedPolicyInputCompiler.compile(
            passInformation,
            source.decision.policyInput.belief,
        )
        val forcedPassDecision = source.decision.copy(
            policyInput = passInput,
            expansion = passExpansion,
            candidates = listOf(source.decision.candidates.first().copy(choice = pass)),
            chosen = pass,
            heuristicChoice = pass,
        )
        assertRefused(
            fixture(
                decision = forcedPassDecision,
                transition = transitionFor(pass),
                outcome = outcome(pass),
            ),
            "rules-forced pass is not a searched strategic decision",
        )
    }

    private fun admission(fixture: Fixture): BehavioralCloningAdmission = BehavioralCloningAdmission(
        root = fixture.root,
        scope = source.scope,
    )

    private fun transitionFor(choice: SemanticChoice): PolicyTrajectoryForcedTransition =
        source.transition.copy(
            events = source.transition.events.map { event ->
                val detail = event.detail as? PerspectiveEventDetail.Choice ?: return@map event
                event.copy(
                    payload = JsonObject(
                        event.payload + ("signature" to JsonPrimitive(choice.signature))
                    ),
                    detail = detail.copy(semanticSignature = choice.signature),
                )
            },
        )

    private fun assertRefused(fixture: Fixture, expectedFailure: String) {
        val result = admission(fixture).extract(fixture.manifest)
        assertFalse(result.passed, "unexpected admission for $expectedFailure")
        assertTrue(result.examples.isEmpty())
        assertTrue(
            result.failures.any { expectedFailure in it },
            "Expected '$expectedFailure', found ${result.failures}",
        )
    }

    private fun fixture(
        header: PolicyTrajectoryHeader = source.header,
        decision: PolicyTrajectoryDecision = source.decision,
        transition: PolicyTrajectoryForcedTransition = source.transition,
        outcome: PolicyTrajectoryOutcome = source.outcome,
        game: (CorpusGameSummary) -> CorpusGameSummary = { it },
        replayVerified: Boolean = true,
        manifestPassed: Boolean = true,
    ): Fixture {
        val root = createTempDirectory("bc-admission")
        val relative = "corpus/v5/public/${source.gameId}.jsonl.gz"
        val trajectory = root.resolve(relative)
        PolicyTrajectoryWriter.compressed(trajectory).use { writer ->
            writer.append(header)
            writer.append(decision)
            writer.append(transition)
            writer.append(outcome)
        }
        // The projection, teacher selection, accepted engine step, safe ledger delta, and trajectory
        // writer are production paths. Only the terminal summary and replay-verified bit are compact
        // oracle metadata; this focused test does not claim to have run a complete canonical replay.
        val summary = game(
            CorpusGameSummary(
                gameId = source.gameId,
                p0Policy = ArenaPolicyKind.SEARCH,
                p1Policy = ArenaPolicyKind.HEURISTIC,
                searchPlanner = SearchPlannerKind.SHARED_TREE,
                winner = null,
                terminal = true,
                disposition = GameRunDisposition.GAME_ENDED,
                decisions = 1,
                searchSeat = "p0",
                searchScore = source.decision.rootValue,
                illegalResponses = 0,
                fallbacks = 0,
                stepLimit = false,
            )
        )
        val entry = CorpusEntry(
            gameId = source.gameId,
            publicTrajectory = relative,
            publicSha256 = sha256File(trajectory),
            publicSizeBytes = Files.size(trajectory),
            policyEvidenceIdentity = header.behaviorBinding.identity,
            behaviorSpecificationSha256 = header.behaviorBinding.behaviorSpecificationSha256,
            replayVerified = replayVerified,
            game = summary,
        )
        val entries = listOf(entry)
        val terminalGames = entries.count { it.game.terminal }
        val replayVerifiedGames = entries.count { it.replayVerified }
        val datasetIdentity = CorpusManifest.computeDatasetIdentity(
            profileId = source.profile.id,
            profileHash = source.scope.profileHash,
            sourceProvenance = source.provenance,
            requestedGames = 1,
            terminalGames = terminalGames,
            replayVerifiedGames = replayVerifiedGames,
            entries = entries,
            passed = manifestPassed,
        )
        val manifest = CorpusManifest(
            generatedAtUtc = "2026-08-31T00:00:00Z",
            profileId = source.profile.id,
            profileHash = source.scope.profileHash,
            outerCommit = source.provenance.outer.revision,
            argentumCommit = source.provenance.argentum.revision,
            sourceProvenance = source.provenance,
            requestedGames = 1,
            terminalGames = terminalGames,
            replayVerifiedGames = replayVerifiedGames,
            entries = entries,
            passed = manifestPassed,
            datasetIdentity = datasetIdentity,
        )
        val manifestPath = root.resolve("corpus/v5/manifest.json")
        Files.createDirectories(manifestPath.parent)
        Files.writeString(manifestPath, evidenceJson.encodeToString(manifest))
        return Fixture(root, manifestPath)
    }

    private data class Fixture(val root: Path, val manifest: Path)

    private data class RejectionCase(
        val expectedFailure: String,
        val game: (CorpusGameSummary) -> CorpusGameSummary = { it },
        val replayVerified: Boolean = true,
        val manifestPassed: Boolean = true,
        val decision: PolicyTrajectoryDecision = source.decision,
    )

    companion object {
        private val source: ProductionEvidence by lazy(::productionEvidence)

        private fun productionEvidence(): ProductionEvidence {
            val gameId = "bc-current-frozen-mono-red"
            val deck = loadDeckManifest()
            val profile = SearchTeacherArena.smokeProfile()
            val scope = BehavioralCloningAdmissionScope.frozenMonoRed(
                deck = deck,
                profile = profile,
            )
            val emptyHash = PolicyJson.sha256("")
            val provenance = PolicySourceProvenance(
                expectedArgentumRevision = profile.argentumCommit,
                outer = PolicySourceTreeState(profile.outerCommit, emptyHash, emptyHash, emptyHash),
                argentum = PolicySourceTreeState(profile.argentumCommit, emptyHash, emptyHash, emptyHash),
            )
            val registry = buildRegistry()
            val environment = GameEnvironment.create(registry).also { env ->
                env.reset(
                    GameConfig(
                        players = listOf(
                            PlayerConfig("Player 0", deck.deck()),
                            PlayerConfig("Player 1", deck.deck()),
                        ),
                        skipMulligans = false,
                        useHandSmoother = false,
                        startingPlayerIndex = 0,
                        seed = 17L,
                    )
                )
            }
            val knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck)
            val world = ArgentumSearchWorld.create(
                environment = environment,
                gameId = gameId,
                seedBase = 99L,
                effectiveSetupSeed = 17L,
                expander = UnifiedSemanticExpander(
                    actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
                ),
                cardRegistry = registry,
                knownDecks = knownDecks,
            )
            val actor = assertNotNull(world.actorToAct())
            assertEquals("p0", actor)
            val information = world.informationState(actor)
            assertTrue(information.observation.currentTurnStateComplete)
            assertTrue(information.knowledge.epistemicallyComplete)
            assertTrue(information.history.isEmpty())
            val expansion = world.expandChoices()
            assertTrue(expansion.isProfileExhaustive)
            assertTrue(expansion.candidates.size > 1)
            val opponent = defaultMonoRedOpponentPolicy()
            val parameters = profile.policyParameters(
                baseSeed = 99L,
                beliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
                beliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
            )
            val session = SearchTeacherPolicySession(
                root = world,
                viewer = actor,
                registry = registry,
                knownDecks = knownDecks,
                parameters = parameters,
                opponentPolicy = opponent,
                gameId = gameId,
            )
            val selected = session.select(world, actor, searchSeed = 101L)
            val search = assertNotNull(selected.search)
            val belief = session.latestBeliefDiagnostics
            val beliefVersion = beliefVersion(belief.architecture, belief.mode)
            val binding = PolicyBehaviorBinding.create(
                behaviorIdentity = session.policyIdentity,
                behaviorSpecification = PolicyJson.format.encodeToJsonElement(
                    SearchTeacherBehaviorSpecification.serializer(),
                    session.behaviorSpecification,
                ).jsonObject,
                sourceProvenance = provenance,
            )
            val header = PolicyTrajectoryHeader(
                gameId = gameId,
                createdAtUtc = "2026-08-31T00:00:00Z",
                outerCommit = provenance.outer.revision,
                argentumCommit = provenance.argentum.revision,
                deckManifestHash = deck.deckHash(),
                cardPoolHash = deck.cardPoolHash(),
                perspectivePlayerId = actor,
                profileManifestHash = scope.profileHash,
                behaviorBinding = binding,
                policyVersion = binding.identity,
                evaluatorVersion = parameters.leaf.evaluator.evaluatorId,
                leaf = parameters.leaf,
                actionSpaceProfile = parameters.actionSpaceProfile,
                beliefVersion = beliefVersion,
                opponentModelVersion = opponent.id,
            )
            val input = BoundedPolicyInputCompiler.compile(
                information,
                PolicyBeliefSummary.from(belief, information.knowledge.knowledgeDigest),
            )
            val decision = PolicyTrajectoryDecision(
                gameId = gameId,
                decisionIndex = 0,
                actingPlayerId = actor,
                policyVersion = binding.identity,
                evaluatorVersion = parameters.leaf.evaluator.evaluatorId,
                leaf = parameters.leaf,
                actionSpaceProfile = parameters.actionSpaceProfile,
                beliefVersion = beliefVersion,
                opponentModelVersion = opponent.id,
                policyInput = input,
                expansion = expansion,
                candidates = search.candidates,
                chosen = selected.choice,
                heuristicChoice = expansion.candidates.first(),
                rootValue = search.rootValue,
                beliefDiagnostics = belief,
                searchDiagnostics = search.diagnostics,
            )
            val priorHistorySize = information.history.size
            val accepted = world.step(selected.choice)
            assertTrue(accepted.accepted, accepted.diagnostic)
            val visibleEvents = world.informationState(actor).history.drop(priorHistorySize)
            assertTrue(visibleEvents.isNotEmpty())
            val transition = PolicyTrajectoryForcedTransition(
                gameId = gameId,
                afterDecisionIndex = 0,
                events = visibleEvents,
            )
            return ProductionEvidence(
                gameId = gameId,
                profile = profile,
                scope = scope,
                provenance = provenance,
                binding = binding,
                knownDecks = knownDecks,
                header = header,
                decision = decision,
                transition = transition,
                outcome = outcome(selected.choice),
            )
        }

        private fun outcome(accepted: SemanticChoice): PolicyTrajectoryOutcome = PolicyTrajectoryOutcome(
            gameId = "bc-current-frozen-mono-red",
            decisions = 1,
            completion = PolicyTrajectoryCompletion.GAME_ENDED,
            winnerId = null,
            resultByPlayer = mapOf("p0" to 0.0, "p1" to 0.0),
            semanticResponseSequence = listOf(accepted),
        )

        private fun beliefVersion(architecture: BeliefArchitecture, mode: BeliefMode): String =
            "${architecture.name.lowercase()}:${mode.name.lowercase()}"
    }

    private data class ProductionEvidence(
        val gameId: String,
        val profile: FrozenSearchProfile,
        val scope: BehavioralCloningAdmissionScope,
        val provenance: PolicySourceProvenance,
        val binding: PolicyBehaviorBinding,
        val knownDecks: Map<String, Map<String, Int>>,
        val header: PolicyTrajectoryHeader,
        val decision: PolicyTrajectoryDecision,
        val transition: PolicyTrajectoryForcedTransition,
        val outcome: PolicyTrajectoryOutcome,
    )
}
