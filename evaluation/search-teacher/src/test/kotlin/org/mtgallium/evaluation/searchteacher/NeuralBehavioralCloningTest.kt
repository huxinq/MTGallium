package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.BoundedPolicyInput
import org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyZoneView
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

class NeuralBehavioralCloningTest {
    @Test
    fun `semantic entity ordinals survive candidate feature projection`() {
        val observation = projectionWitnessObservation()
        val repaired = NeuralBehavioralCloningFeatureEncoder(candidateDimension = 65_537)
        val original = NeuralBehavioralCloningFeatureEncoder(
            candidateDimension = 65_537,
            projection = NeuralBcFeatureProjection.ISSUE_0022_ORIGINAL,
        )
        val semantic = repaired.auditedSemanticObjectReferences(
            observation,
            "p0",
            CANDIDATE_SCHEMA_V3,
        ).single()
        val zero = buildJsonObject {
            put("assignments", buildJsonObject { put("$semantic#0", JsonPrimitive("assigned")) })
        }
        val one = buildJsonObject {
            put("assignments", buildJsonObject { put("$semantic#1", JsonPrimitive("assigned")) })
        }

        assertEquals(
            original.auditedCandidateJsonFeatures(
                observation, "p0", CANDIDATE_SCHEMA_V3, "candidate.payload", zero,
            ),
            original.auditedCandidateJsonFeatures(
                observation, "p0", CANDIDATE_SCHEMA_V3, "candidate.payload", one,
            ),
        )
        val zeroFeatures = repaired.auditedCandidateJsonFeatures(
            observation, "p0", CANDIDATE_SCHEMA_V3, "candidate.payload", zero,
        )
        val oneFeatures = repaired.auditedCandidateJsonFeatures(
            observation, "p0", CANDIDATE_SCHEMA_V3, "candidate.payload", one,
        )
        assertNotEquals(zeroFeatures, oneFeatures)
        assertTrue(zeroFeatures.any { "semanticOrdinal=0" in it })
        assertTrue(oneFeatures.any { "semanticOrdinal=1" in it })
    }

    @Test
    fun `ordered candidate arrays retain their element positions`() {
        val observation = projectionWitnessObservation()
        val repaired = NeuralBehavioralCloningFeatureEncoder(candidateDimension = 65_537)
        val original = NeuralBehavioralCloningFeatureEncoder(
            candidateDimension = 65_537,
            projection = NeuralBcFeatureProjection.ISSUE_0022_ORIGINAL,
        )
        val first = buildJsonObject {
            put("ordered", JsonArray(listOf(JsonPrimitive("A"), JsonPrimitive("B"))))
        }
        val second = buildJsonObject {
            put("ordered", JsonArray(listOf(JsonPrimitive("B"), JsonPrimitive("A"))))
        }

        assertEquals(
            original.auditedCandidateJsonFeatures(
                observation, "p0", CANDIDATE_SCHEMA_V3, "candidate.payload", first,
            ),
            original.auditedCandidateJsonFeatures(
                observation, "p0", CANDIDATE_SCHEMA_V3, "candidate.payload", second,
            ),
        )
        val firstFeatures = repaired.auditedCandidateJsonFeatures(
            observation, "p0", CANDIDATE_SCHEMA_V3, "candidate.payload", first,
        )
        val secondFeatures = repaired.auditedCandidateJsonFeatures(
            observation, "p0", CANDIDATE_SCHEMA_V3, "candidate.payload", second,
        )
        assertNotEquals(firstFeatures, secondFeatures)
        assertTrue("candidate.payload.ordered.item.0=A" in firstFeatures)
        assertTrue("candidate.payload.ordered.item.1=B" in firstFeatures)
    }

    @Test
    fun `feature boundary ignores integrity display and routing values but distinguishes semantic intent`() {
        val (_, input) = realInput()
        assertTrue(input.candidates.size > 1)
        val encoder = NeuralBehavioralCloningFeatureEncoder(stateDimension = 257, candidateDimension = 127)
        val stateFeatures = encoder.auditedStateFeatures(input)
        val firstFeatures = encoder.auditedCandidateFeatures(input, input.candidates.first())
        val secondFeatures = encoder.auditedCandidateFeatures(input, input.candidates[1])

        assertNotEquals(firstFeatures, secondFeatures)
        assertTrue((stateFeatures + firstFeatures + secondFeatures).none { feature ->
            val lower = feature.lowercase()
            "signature" in lower || "digest" in lower || "objectref" in lower ||
                "knowledgeobjectkey" in lower || feature.contains(Regex("[0-9a-f]{64}")) ||
                "object:" in feature || "zone:p" in feature || "choice:" in feature
        })

        val changedDisplay = input.candidates.first().copy(
            display = input.candidates.first().display.copy(label = "presentation must not be a feature")
        )
        val displayInput = recompile(input, input.candidates.toMutableList().also { it[0] = changedDisplay })
        assertEquals(
            firstFeatures,
            encoder.auditedCandidateFeatures(displayInput, changedDisplay),
        )

        val changedIntegrity = BoundedPolicyInputCompiler.compile(
            input.toInformationState(emptyList()).copy(
                informationStateDigest = PolicyJson.sha256("different-integrity-only")
            ),
            input.belief,
        )
        assertEquals(stateFeatures, encoder.auditedStateFeatures(changedIntegrity))
    }

    @Test
    fun `complete-game split is deterministic disjoint and independent of input order`() {
        val games = (0 until 20).map { "game-${it.toString().padStart(2, '0')}" }
        val first = deterministicBcGameSplit("dataset", games)
        val second = deterministicBcGameSplit("dataset", games.reversed())

        assertEquals(first, second)
        assertEquals(games.toSet(), (first.trainGames + first.validationGames + first.testGames).toSet())
        assertTrue(first.trainGames.toSet().intersect(first.validationGames.toSet()).isEmpty())
        assertTrue(first.trainGames.toSet().intersect(first.testGames.toSet()).isEmpty())
        assertTrue(first.validationGames.toSet().intersect(first.testGames.toSet()).isEmpty())
    }

    @Test
    fun `label must index an actual current candidate`() {
        val (_, input) = realInput()
        val encoder = NeuralBehavioralCloningFeatureEncoder()

        assertEquals(input.candidates.size, encoder.encode(input, 0).candidateCount)
        assertFailsWith<IllegalArgumentException> { encoder.encode(input, input.candidates.size) }
    }

    @Test
    fun `named scale audit reconstructs the exact ordinary hashed vectors`() {
        val (_, input) = realInput()
        val encoder = NeuralBehavioralCloningFeatureEncoder()
        val encoded = encoder.encode(input, 0)
        val audit = encoder.auditedFeatureEmissions(NeuralBcFeatureInput.current(input))

        assertTrue(audit.stateEmissions.isNotEmpty())
        assertTrue(audit.candidateEmissions.all { it.isNotEmpty() })
        assertTrue(sameSparseFeatureVector(encoded.state, audit.state))
        encoded.candidates.indices.forEach { index ->
            assertTrue(sameSparseFeatureVector(encoded.candidates[index], audit.candidates[index]))
        }
    }

    @Test
    fun `whole-game exclusion accepts only trajectory-local failures`() {
        fun file(gameId: String, passed: Boolean, failures: List<String> = emptyList()) =
            CorpusValidationFile(gameId, "$gameId.jsonl.gz", 1, 1, 1, 1, passed, failures)
        val files = (0 until 11).map { index -> file("game-$index", passed = true) } +
            file("game-bad", passed = false, failures = listOf("generation limit"))

        assertEquals(
            (0 until 11).map { "game-$it" }.toSet(),
            strictWholeGameAdmissionSelection(
                files,
                failures = listOf("game-bad: generation limit"),
                minimumGames = 10,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            strictWholeGameAdmissionSelection(
                files,
                failures = listOf("manifest outer revision is outside scope", "game-bad: generation limit"),
                minimumGames = 10,
            )
        }
    }

    @Test
    fun `small bilinear policy fits a state-dependent candidate ranking and survives save load`() {
        val modelConfig = NeuralBcModelConfig(stateDimension = 8, candidateDimension = 8, hiddenDimension = 8)
        val trainingConfig = NeuralBcTrainingConfig(
            maximumEpochs = 80,
            learningRate = 0.02,
            initializationSeeds = listOf(41L),
        )
        val examples = (0 until 40).map { index -> syntheticDecision(index) }
        val trained = NeuralBcTrainer(modelConfig, trainingConfig).train(
            train = examples.take(30),
            validation = examples.drop(30),
            seed = 41L,
        )
        val accuracy = examples.count { trained.policy.selectIndex(it) == it.labelIndex }.toDouble() / examples.size
        assertTrue(accuracy >= 0.95, "state-dependent fit was $accuracy")

        val path = createTempDirectory("neural-bc-model").resolve("model.json")
        trained.policy.save(path)
        val reloaded = CandidateConditionedNeuralPolicy.load(path)
        assertContentEquals(trained.policy.scores(examples.first()), reloaded.scores(examples.first()))
    }

    @Test
    fun `candidate update scale changes only candidate projection parameter steps`() {
        val config = NeuralBcModelConfig(stateDimension = 8, candidateDimension = 8, hiddenDimension = 4)
        val initial = CandidateConditionedNeuralPolicy.initialize(config, seed = 73L).artifact
        val full = copyNeuralBcModelArtifact(initial)
        val reduced = copyNeuralBcModelArtifact(initial)
        val decision = syntheticDecision(1)

        SparseAdam(
            full,
            NeuralBcTrainingConfig(learningRate = 0.01, candidateProjectionUpdateScale = 1.0),
        ).step(decision)
        SparseAdam(
            reduced,
            NeuralBcTrainingConfig(learningRate = 0.01, candidateProjectionUpdateScale = 1.0 / 8.0),
        ).step(decision)

        assertContentEquals(full.stateWeights, reduced.stateWeights)
        assertContentEquals(full.stateBias, reduced.stateBias)
        assertContentEquals(full.globalQuery, reduced.globalQuery)
        assertScaledParameterDeltas(initial.candidateWeights, full.candidateWeights, reduced.candidateWeights)
        assertScaledParameterDeltas(initial.candidateBias, full.candidateBias, reduced.candidateBias)
    }

    @Test
    fun `sparse Adam exposure counts actual aggregated parameter update calls`() {
        val config = NeuralBcModelConfig(stateDimension = 8, candidateDimension = 8, hiddenDimension = 4)
        val artifact = CandidateConditionedNeuralPolicy.initialize(config, seed = 73L).artifact
        val decision = syntheticDecision(1).copy(
            state = SparseFeatureVector(intArrayOf(0, 1), doubleArrayOf(1.0, 2.0)),
            candidates = listOf(
                SparseFeatureVector(intArrayOf(2, 3), doubleArrayOf(1.0, 1.0)),
                SparseFeatureVector(intArrayOf(2, 4), doubleArrayOf(1.0, 1.0)),
            ),
        )
        val adam = SparseAdam(artifact, NeuralBcTrainingConfig())

        adam.step(decision)
        val exposure = adam.updateExposureSnapshot()

        assertEquals(1, exposure.decisionSteps)
        assertEquals(8, exposure.stateWeightUpdateCounts.count { it == 1L })
        assertEquals(12, exposure.candidateWeightUpdateCounts.count { it == 1L })
        assertEquals(4, exposure.stateBiasUpdateCounts.count { it == 1L })
        assertEquals(4, exposure.candidateBiasUpdateCounts.count { it == 1L })
        assertEquals(4, exposure.globalQueryUpdateCounts.count { it == 1L })
        assertTrue(exposure.stateWeightUpdateCounts.all { it in 0L..1L })
        assertTrue(exposure.candidateWeightUpdateCounts.all { it in 0L..1L })

        val reconstructed = reconstructSparseAdamUpdateExposure(listOf(decision), config, epochs = 1)
        assertContentEquals(exposure.stateWeightUpdateCounts, reconstructed.stateWeightUpdateCounts)
        assertContentEquals(exposure.stateBiasUpdateCounts, reconstructed.stateBiasUpdateCounts)
        assertContentEquals(exposure.candidateWeightUpdateCounts, reconstructed.candidateWeightUpdateCounts)
        assertContentEquals(exposure.candidateBiasUpdateCounts, reconstructed.candidateBiasUpdateCounts)
        assertContentEquals(exposure.globalQueryUpdateCounts, reconstructed.globalQueryUpdateCounts)
    }

    @Test
    fun `sparse Adam checkpoint restores the exact next update without branch aliasing`() {
        val model = NeuralBcModelConfig(stateDimension = 8, candidateDimension = 8, hiddenDimension = 4)
        val training = NeuralBcTrainingConfig(
            learningRate = 0.01,
            candidateProjectionUpdateScale = 1.0 / 8.0,
        )
        val sourceArtifact = CandidateConditionedNeuralPolicy.initialize(model, seed = 73L).artifact
        val sourceAdam = SparseAdam(sourceArtifact, training)
        sourceAdam.step(syntheticDecision(1))
        sourceAdam.step(syntheticDecision(2))
        val checkpointArtifact = copyNeuralBcModelArtifact(sourceArtifact)
        val checkpointOptimizer = sourceAdam.snapshotState()
        val firstArtifact = copyNeuralBcModelArtifact(checkpointArtifact)
        val secondArtifact = copyNeuralBcModelArtifact(checkpointArtifact)
        val first = SparseAdam(firstArtifact, training, checkpointOptimizer)
        val second = SparseAdam(secondArtifact, training, checkpointOptimizer)

        first.step(syntheticDecision(3))
        second.step(syntheticDecision(3))

        assertContentEquals(firstArtifact.stateWeights, secondArtifact.stateWeights)
        assertContentEquals(firstArtifact.stateBias, secondArtifact.stateBias)
        assertContentEquals(firstArtifact.candidateWeights, secondArtifact.candidateWeights)
        assertContentEquals(firstArtifact.candidateBias, secondArtifact.candidateBias)
        assertContentEquals(firstArtifact.globalQuery, secondArtifact.globalQuery)
        val firstState = first.snapshotState()
        val secondState = second.snapshotState()
        assertEquals(3, firstState.decisionSteps)
        assertContentEquals(firstState.stateFirstMoment, secondState.stateFirstMoment)
        assertContentEquals(firstState.stateSecondMoment, secondState.stateSecondMoment)
        assertContentEquals(firstState.candidateFirstMoment, secondState.candidateFirstMoment)
        assertContentEquals(firstState.candidateSecondMoment, secondState.candidateSecondMoment)
        assertContentEquals(firstState.stateWeightUpdateCounts, secondState.stateWeightUpdateCounts)
        assertContentEquals(firstState.candidateWeightUpdateCounts, secondState.candidateWeightUpdateCounts)

        val secondStateWeightsBeforeFirstBranchMoves = secondArtifact.stateWeights.copyOf()
        first.step(syntheticDecision(0))
        assertContentEquals(
            secondStateWeightsBeforeFirstBranchMoves,
            secondArtifact.stateWeights,
            "mutating the first branch must not mutate the second",
        )
    }

    @Test
    fun `interaction policy fits a state-dependent candidate ranking and survives save load`() {
        val modelConfig = NeuralBcInteractionModelConfig(
            stateDimension = 8,
            candidateDimension = 8,
            projectionDimension = 8,
            interactionDimension = 16,
        )
        val trainingConfig = NeuralBcTrainingConfig(
            maximumEpochs = 80,
            learningRate = 0.01,
            initializationSeeds = listOf(41L),
        )
        val examples = (0 until 40).map { index -> syntheticDecision(index) }
        val trained = NeuralBcInteractionTrainer(modelConfig, trainingConfig).train(
            train = examples.take(30),
            validation = examples.drop(30),
            seed = 41L,
        )
        assertTrue(neuralBcAccuracy(trained.policy, examples) >= 0.95)

        val path = createTempDirectory("neural-bc-interaction-model").resolve("model.json")
        trained.policy.save(path)
        val reloaded = CandidateConditionedInteractionPolicy.load(path)
        assertContentEquals(trained.policy.scores(examples.first()), reloaded.scores(examples.first()))
    }

    @Test
    fun `loaded policy chooses and semantically executes a real profile candidate`() {
        val (world, input) = realInput()
        val encoder = NeuralBehavioralCloningFeatureEncoder()
        val policy = CandidateConditionedNeuralPolicy.initialize(NeuralBcModelConfig(), seed = 73L)

        val selected = policy.select(input, encoder)
        assertTrue(selected in input.candidates)
        val step = world.step(selected)
        assertTrue(step.accepted, step.diagnostic)
    }

    private fun syntheticDecision(index: Int): EncodedBcDecision {
        val stateClass = index % 2
        return EncodedBcDecision(
            gameId = "synthetic-${index / 4}",
            decisionIndex = index,
            decisionFamily = "ORDINARY_ACTION",
            state = sparse(stateClass, 1.0),
            candidates = listOf(sparse(2, 1.0), sparse(3, 1.0)),
            candidateFamilies = listOf(
                SemanticOperationFamily.PASS_PRIORITY,
                SemanticOperationFamily.CAST_SPELL,
            ),
            candidateIntents = listOf(
                SemanticActionIntentKind.PASS_PRIORITY,
                SemanticActionIntentKind.CAST_SPELL,
            ),
            labelIndex = stateClass,
        )
    }

    private fun sparse(index: Int, value: Double): SparseFeatureVector =
        SparseFeatureVector(intArrayOf(index), doubleArrayOf(value))

    private fun projectionWitnessObservation(): PolicyObservation = PolicyObservation(
        perspectivePlayerId = "p0",
        turnNumber = 1,
        phase = "MAIN1",
        step = "MAIN",
        activePlayerId = "p0",
        priorityPlayerId = "p0",
        players = emptyList(),
        zones = listOf(
            PolicyZoneView(
                ownerId = "p0",
                zone = "BATTLEFIELD",
                hidden = false,
                size = 1,
                cards = listOf(
                    PolicyCardView(
                        objectRef = "object:witness",
                        definitionId = "witness-card",
                        name = "Witness",
                        zone = "BATTLEFIELD",
                        ownerId = "p0",
                        controllerId = "p0",
                        types = setOf("CREATURE"),
                        subtypes = emptySet(),
                        colors = setOf("RED"),
                        keywords = emptySet(),
                        manaCost = "{R}",
                        manaValue = 1,
                        oracleText = "",
                        power = 1,
                        toughness = 1,
                        tapped = false,
                        summoningSick = false,
                        faceDown = false,
                        damageMarked = 0,
                        counters = emptyMap(),
                        attachedTo = null,
                        attachments = emptyList(),
                    )
                ),
            )
        ),
        stack = emptyList(),
        currentTurnStateComplete = true,
        pendingDecision = null,
        observationDigest = PolicyJson.sha256("projection-witness"),
    )

    private fun assertScaledParameterDeltas(
        initial: DoubleArray,
        full: DoubleArray,
        reduced: DoubleArray,
    ) {
        initial.indices.forEach { index ->
            assertEquals(
                (full[index] - initial[index]) / 8.0,
                reduced[index] - initial[index],
                absoluteTolerance = 1e-15,
                message = "candidate parameter delta at index $index",
            )
        }
    }

    private fun recompile(input: BoundedPolicyInput, candidates: List<org.mtgallium.agent.infoset.core.SemanticChoice>): BoundedPolicyInput =
        BoundedPolicyInputCompiler.compile(
            input.toInformationState(emptyList()).copy(candidates = candidates),
            input.belief,
        )

    private fun choiceWithPayload(choice: SemanticChoice, payload: kotlinx.serialization.json.JsonObject): SemanticChoice =
        SemanticChoice.create(
            kind = choice.kind,
            operationFamily = choice.operationFamily,
            actionIntent = choice.actionIntent,
            display = choice.display,
            canonicalPayload = payload,
        )

    private fun realInput(): Pair<ArgentumSearchWorld, BoundedPolicyInput> {
        val deck = loadDeckManifest()
        val registry = buildRegistry()
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Player 0", deck.deck()),
                    PlayerConfig("Player 1", deck.deck()),
                ),
                skipMulligans = false,
                useHandSmoother = false,
                startingPlayerIndex = 0,
                seed = 901L,
            )
        )
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = "neural-bc-test",
            seedBase = 902L,
            expander = UnifiedSemanticExpander(
                actionSpaceProfile = SearchTeacherArena.smokeProfile().actionSpaceProfile,
            ),
            cardRegistry = registry,
            knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck),
        )
        val actor = requireNotNull(world.actorToAct())
        val input = BoundedPolicyInputCompiler.compile(world.informationState(actor))
        return world to input
    }
}
