package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.mtgallium.agent.searchteacher.LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueCheckpointPayload
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueTrainingBinding
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints
import org.mtgallium.research.run.ResearchRunProvenance
import org.mtgallium.research.run.ResearchSourceProvenance
import org.mtgallium.research.run.ResearchSourceTreeState
import org.mtgallium.research.run.researchSha256

@org.junit.jupiter.api.Tag("public-source")
class LearnedOutcomeValueTrainingTest {
    @Test
    fun `equal game weighting makes each completed game total one over G`() {
        val rows = examples(pairIndex = 1, leg = "a", label = 1.0, frames = 1) +
            examples(pairIndex = 1, leg = "b", label = -1.0, frames = 3)

        val weights = equalGameOutcomeValueWeights(rows)

        assertEquals(0.5, weights.filter { it.example.leg == "a" }.sumOf { it.weight })
        assertEquals(0.5, weights.filter { it.example.leg == "b" }.sumOf { it.weight })
        assertEquals(0.0, equalGameTerminalPayoffMean(rows))
    }

    @Test
    fun `fixed order uses pair leg frame and utf8 feature keys`() {
        val unordered = examples(2, "b", 1.0, 1) + examples(1, "b", -1.0, 1) + examples(1, "a", 1.0, 2)
        val ordered = canonicalOutcomeValueOrder(unordered)

        assertEquals(listOf(Triple(1, "a", 0), Triple(1, "a", 1), Triple(1, "b", 0), Triple(2, "b", 0)), ordered.map { Triple(it.pairIndex, it.leg, it.frameIndex) })
        assertEquals(listOf("state/a", "state/z", "state/é"), listOf("state/é", "state/z", "state/a").sortedWith(utf8BytewiseStringComparator))
    }

    @Test
    fun `fit is train only deterministic and reaches its fixed KKT criterion`() {
        val train = trainExamples()
        val first = LearnedOutcomeValueTrainer.fit(train, binding())
        val alteredHeldOut = examples(9, "a", -1.0, 2, value = 999.0) + examples(9, "b", 1.0, 2, value = -999.0)
        val second = LearnedOutcomeValueTrainer.fit(train, binding())

        assertContentEquals(first.evaluator.canonicalCheckpointBytes(), second.evaluator.canonicalCheckpointBytes())
        assertTrue(first.maxKktResidual <= 1e-8)
        assertTrue(first.solverIterations <= 2 * first.trainFeatureKeys.size)
        // Held-out data never enters fit: changing it has no train parameter path at all.
        assertEquals(first.evaluator.canonicalCheckpointPayload, LearnedOutcomeValueTrainer.fit(train, binding()).evaluator.canonicalCheckpointPayload)
        assertTrue(alteredHeldOut.isNotEmpty())
    }

    @Test
    fun `nonfinite solver arithmetic is typed`() {
        val bad = OutcomeValueExample(1, "a", 0, OutcomeValueActorRelation.ROOT, features(Double.MAX_VALUE), 1.0)
        val failure = assertFailsWith<OutcomeValueTrainingException> { LearnedOutcomeValueTrainer.fit(listOf(bad), binding()) }
        assertEquals(OutcomeValueTrainingFailureKind.NONFINITE_PARAMETER, failure.kind)
    }

    @Test
    fun `centered Jacobi PCG solves the correlated two row ridge system and independently certifies it`() {
        val rows = listOf(
            numericExample(1, "a", -1.0, mapOf("state/x1" to 2.9, "state/x2" to 5.8)),
            numericExample(1, "b", 1.0, mapOf("state/x1" to 3.1, "state/x2" to 6.2)),
        )

        val fit = LearnedOutcomeValueTrainer.fit(rows, binding())
        val payload = checkpointPayload(fit)

        assertEquals(5.0 / 3.0, payload.weights.getValue("state/x1"), 1e-8)
        assertEquals(10.0 / 3.0, payload.weights.getValue("state/x2"), 1e-8)
        assertEquals(-25.0, payload.bias, 1e-8)
        assertEquals(-5.0 / 6.0, fit.evaluator.evaluate(rows[0].features), 1e-8)
        assertEquals(5.0 / 6.0, fit.evaluator.evaluate(rows[1].features), 1e-8)
        assertTrue(independentOriginalKkt(rows, payload) <= 1e-8)
    }

    @Test
    fun `centered Jacobi PCG agrees with a dense three feature ridge reference`() {
        val rows = listOf(
            numericExample(1, "a", -1.0, mapOf("state/x1" to 1.0, "state/x2" to 2.1, "state/x3" to -1.1), frameIndex = 0),
            numericExample(1, "a", -1.0, mapOf("state/x1" to 2.0, "state/x2" to 4.0), frameIndex = 1),
            numericExample(1, "a", -1.0, mapOf("state/x1" to 2.6, "state/x3" to -2.5), frameIndex = 2),
            numericExample(1, "b", 1.0, mapOf("state/x1" to 4.1, "state/x2" to 8.3), frameIndex = 0),
        )
        // Three a-leg frames receive one sixth each; the b leg's sole frame receives one half.
        val expectedRowWeights = listOf(1.0 / 6.0, 1.0 / 6.0, 1.0 / 6.0, 1.0 / 2.0)

        val fit = LearnedOutcomeValueTrainer.fit(rows, binding())
        val actual = checkpointPayload(fit)
        val expected = denseCenteredRidgeReference(rows, expectedRowWeights)

        assertEquals(expected.bias, actual.bias, 1e-8)
        expected.weights.forEach { (key, value) -> assertEquals(value, actual.weights.getValue(key), 1e-8, key) }
        assertTrue(independentOriginalKkt(rows, actual, expectedRowWeights) <= 1e-8)
    }

    @Test
    fun `constant-label optimum is independently certified without a CG iteration`() {
        val rows = listOf(
            numericExample(1, "a", 0.25, mapOf("state/x1" to -2.0, "state/x2" to 4.0)),
            numericExample(1, "b", 0.25, mapOf("state/x1" to 3.0)),
        )

        val fit = LearnedOutcomeValueTrainer.fit(rows, binding())
        val payload = checkpointPayload(fit)

        assertEquals(0, fit.solverIterations)
        assertEquals(0.25, payload.bias)
        assertTrue(payload.weights.values.all { it == 0.0 })
        assertTrue(independentOriginalKkt(rows, payload) <= 1e-8)
    }

    @Test
    fun `pair clustered mse averages frames then games then pairs`() {
        val rows = examples(1, "a", 1.0, 2) + examples(1, "b", -1.0, 1) + examples(2, "a", 1.0, 1)
        // Constant zero has every frame loss one, so the clustering must still yield exactly one.
        assertEquals(1.0, equalGameConstantPairMse(rows, 0.0))
    }

    @Test
    fun `host loader verifies envelope and checkpoint bindings before evaluator load`() {
        val directory = Files.createTempDirectory("learned-value-envelope")
        val run = trainingRun()
        val fit = LearnedOutcomeValueTrainer.fit(trainExamples(), binding())
        val path = directory.resolve(LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE)
        ResearchRunCheckpoints.persist(path, run.identity, "mtgallium-learned-outcome-value-checkpoint-v1", 0, fit.evaluator.canonicalCheckpointBytes())

        val loaded = OutcomeValueCheckpointHostLoader.load(path, run, binding())
        assertEquals(fit.evaluator.checkpointIdentity.payloadSha256, loaded.envelope.payloadSha256)
        assertFailsWith<OutcomeValueTrainingException> {
            OutcomeValueCheckpointHostLoader.load(
                path,
                trainingRun().copy(material = trainingRun().material + ("corpus" to identity("wrong"))),
                binding(),
            )
        }
        val projectionMismatch = run.copy(material = run.material + ("projection" to identity("wrong-projection")))
        val mismatchedPath = directory.resolve("projection-mismatch.json")
        ResearchRunCheckpoints.persist(
            mismatchedPath,
            projectionMismatch.identity,
            LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1,
            0,
            fit.evaluator.canonicalCheckpointBytes(),
        )
        assertFailsWith<OutcomeValueTrainingException> {
            OutcomeValueCheckpointHostLoader.load(mismatchedPath, projectionMismatch, binding())
        }
    }

    @Test
    fun `raw validation reports cannot create a production promotion capability`() {
        val directory = Files.createTempDirectory("learned-value-gate")
        val trainingRun = trainingRun()
        val fit = LearnedOutcomeValueTrainer.fit(trainExamples(), binding())
        val path = directory.resolve(LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE)
        ResearchRunCheckpoints.persist(path, trainingRun.identity, LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1, 0, fit.evaluator.canonicalCheckpointBytes())
        val loaded = OutcomeValueCheckpointHostLoader.load(path, trainingRun, binding())
        val provenance = validatorProvenance()
        val validationRun = learnedOutcomeValueValidationBindings(trainingRun, loaded, binding(), provenance)
        val report = trainingReport(trainingRun, loaded, fit)
        val validation = validationExamples()
        val pass = validateOutcomeValueCheckpoint(
            validationRun, trainingRun, loaded, binding(), trainExamples(), validation, report,
            committedOutcomeValueValidatorSource(provenance),
        )

        assertTrue(pass.passed, pass.failures.joinToString())
        assertTrue(OutcomeValueAdmission::class.java.methods.none { it.name.contains("record", ignoreCase = true) })
        assertTrue(PromotedOutcomeValueCheckpoint::class.java.methods.none { it.name.contains("loadFor", ignoreCase = true) })
        assertTrue(PromotedOutcomeValueCheckpoint::class.java.methods.none { it.name == "evaluateTest" })
    }

    @Test
    fun `validation rejects recomputed artifact identities with altered protocol gate or validator source`() {
        val directory = Files.createTempDirectory("learned-value-validation-contract")
        val trainingRun = trainingRun()
        val fit = LearnedOutcomeValueTrainer.fit(trainExamples(), binding())
        val checkpointPath = directory.resolve(LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE)
        ResearchRunCheckpoints.persist(checkpointPath, trainingRun.identity, LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1, 0, fit.evaluator.canonicalCheckpointBytes())
        val checkpoint = OutcomeValueCheckpointHostLoader.load(checkpointPath, trainingRun, binding())
        val provenance = validatorProvenance()
        val canonical = learnedOutcomeValueValidationBindings(trainingRun, checkpoint, binding(), provenance)
        val source = committedOutcomeValueValidatorSource(provenance)
        val report = trainingReport(trainingRun, checkpoint, fit)
        fun rejects(candidate: org.mtgallium.research.run.ResearchRunBindings) {
            assertTrue(candidate.identity != canonical.identity)
            assertFailsWith<IllegalArgumentException> {
                validateOutcomeValueCheckpoint(candidate, trainingRun, checkpoint, binding(), trainExamples(), validationExamples(), report, source)
            }
        }
        rejects(canonical.copy(protocol = "altered-validation-protocol-v1"))
        rejects(canonical.copy(material = canonical.material + ("gate-specification" to identity("altered-gate"))))
        rejects(canonical.copy(material = canonical.material + ("validator-source" to identity("altered-validator-source"))))
        assertFailsWith<IllegalArgumentException> {
            validateOutcomeValueCheckpoint(
                canonical,
                trainingRun,
                checkpoint,
                binding(),
                trainExamples(),
                validationExamples(),
                report.copy(maxKktResidual = report.maxKktResidual + 1e-12),
                source,
            )
        }
    }

    @Test
    fun `production capability interfaces expose no raw report binding or corpus constructors`() {
        listOf(PreparedOutcomeValueTraining::class.java, OutcomeValueAdmission::class.java, PromotedOutcomeValueCheckpoint::class.java)
            .forEach { capability ->
                assertTrue(!capability.isInterface)
                assertTrue(java.lang.reflect.Modifier.isFinal(capability.modifiers))
                assertTrue(capability.declaredConstructors.any { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
                assertTrue(capability.constructors.none { constructor ->
                    !constructor.isSynthetic && (java.lang.reflect.Modifier.isPublic(constructor.modifiers) || java.lang.reflect.Modifier.isProtected(constructor.modifiers))
                })
                assertTrue(capability.methods.none { method -> method.name.contains("record", true) || method.name.contains("loadfor", true) })
            }
    }

    private fun binding() = LearnedOutcomeValueTrainingBinding(
        corpusIdentity = identity("corpus"),
        pairSplitIdentity = identity("split"),
        learnerConfigurationIdentity = identity("configuration"),
        projectionIdentity = identity("projection"),
        rootBehaviorPolicyIdentity = identity("root"),
        opponentBehaviorPolicyIdentity = identity("opponent"),
        environmentProfileIdentity = identity("environment"),
    )

    private fun identity(name: String) = "$name-sha256:${researchSha256(name)}"

    private fun trainingReport(
        trainingRun: ResearchRunBindings,
        checkpoint: LoadedOutcomeValueCheckpoint,
        fit: OutcomeValueFit,
    ) = OutcomeValueTrainingReport(
        trainingRunIdentity = trainingRun.identity,
        checkpointPayloadSha256 = checkpoint.envelope.payloadSha256,
        rows = trainExamples().size,
        games = trainExamples().map(OutcomeValueExample::gameKey).distinct().size,
        trainTerminalPayoffMean = equalGameTerminalPayoffMean(trainExamples()),
        solverIterations = fit.solverIterations,
        maxKktResidual = fit.maxKktResidual,
        coefficientL2Norm = fit.coefficientL2Norm,
    )

    private fun validatorProvenance(): ResearchRunProvenance {
        val empty = researchSha256("")
        val outer = ResearchSourceTreeState("test-outer", empty, empty, empty)
        val engine = ResearchSourceTreeState(OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT, empty, empty, empty)
        return ResearchRunProvenance(
            outerCommit = "test-outer",
            expectedEngineCommit = OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
            checkedOutEngineCommit = OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
            outerDirty = false,
            engineDirty = false,
            sourceProvenance = ResearchSourceProvenance(
                expectedArgentumRevision = OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
                outer = outer,
                argentum = engine,
            ),
        )
    }

    private fun trainingRun(): ResearchRunBindings = ResearchRunBindings(
        protocol = LEARNED_OUTCOME_VALUE_TRAINING_PROTOCOL,
        material = mapOf(
            "corpus" to binding().corpusIdentity,
            "pair-split" to binding().pairSplitIdentity,
            "projection" to binding().projectionIdentity,
            "learner-configuration" to binding().learnerConfigurationIdentity,
            "corpus-manifest" to identity("manifest"),
            "producer-source" to identity("producer"),
            "trainer-source" to identity("trainer"),
            "argentum" to OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
            "feature-schema" to "perspective-safe-outcome-value-features-v1",
            "feature-scaling" to "signed-log1p-after-sparse-aggregation-v1",
            "model" to "sparse-linear-clipped-ridge-v1",
            "target" to "policy-conditional-expected-actual-terminal-payoff-root-v1",
            "root-behavior" to binding().rootBehaviorPolicyIdentity,
            "opponent-behavior" to binding().opponentBehaviorPolicyIdentity,
            "environment" to binding().environmentProfileIdentity,
            "deck" to researchSha256("deck"),
            "card-pool" to researchSha256("pool"),
            "solver" to LEARNED_OUTCOME_VALUE_SOLVER,
            "solver-cap" to "two-times-feature-count-resource-guard-v1",
            "solver-restart" to "one-restart-after-true-residual-at-feature-count-v1",
            "solver-preconditioner" to "jacobi-centered-normal-diagonal-v1",
            "solver-start" to "zero-coefficients-v1",
            "solver-residual" to "rebuild-true-h-minus-Hw-at-feature-count-v1",
            "solver-certification" to "rebuild-raw-scores-and-audit-original-intercept-and-ridge-gradients-v1",
            "lambda" to "0.01",
            "ordering" to LEARNED_OUTCOME_VALUE_ORDERING,
            "weighting" to LEARNED_OUTCOME_VALUE_WEIGHTING,
        ),
    )

    private fun trainExamples(): List<OutcomeValueExample> =
        examples(1, "a", -1.0, 2, -1.0) + examples(1, "b", 1.0, 2, 1.0) +
            examples(2, "a", -1.0, 2, -1.0) + examples(2, "b", 1.0, 2, 1.0)

    private fun validationExamples(): List<OutcomeValueExample> =
        examples(3, "a", -1.0, 2, -1.0) + examples(3, "b", 1.0, 2, 1.0) +
            examples(4, "a", -1.0, 2, -1.0) + examples(4, "b", 1.0, 2, 1.0)

    private fun examples(pairIndex: Int, leg: String, label: Double, frames: Int, value: Double = label): List<OutcomeValueExample> =
        (0 until frames).map { frame ->
            OutcomeValueExample(
                pairIndex = pairIndex,
                leg = leg,
                frameIndex = frame,
                actorRelation = if (frame % 2 == 0) OutcomeValueActorRelation.ROOT else OutcomeValueActorRelation.OPPONENT,
                features = features(value),
                actualTerminalPayoff = label,
            )
        }

    private fun numericExample(
        pairIndex: Int,
        leg: String,
        label: Double,
        values: Map<String, Double>,
        frameIndex: Int = 0,
    ) = OutcomeValueExample(
        pairIndex = pairIndex,
        leg = leg,
        frameIndex = frameIndex,
        actorRelation = OutcomeValueActorRelation.ROOT,
        features = features(values),
        actualTerminalPayoff = label,
    )

    private fun checkpointPayload(fit: OutcomeValueFit): LearnedOutcomeValueCheckpointPayload =
        Json.decodeFromString(fit.evaluator.canonicalCheckpointPayload)

    private fun independentOriginalKkt(
        rows: List<OutcomeValueExample>,
        payload: LearnedOutcomeValueCheckpointPayload,
        expectedRowWeights: List<Double> = equalGameOutcomeValueWeights(rows).map { it.weight },
    ): Double {
        require(rows.size == expectedRowWeights.size)
        var interceptGradient = 0.0
        val featureGradients = payload.weights.mapValues { 0.0 }.toMutableMap()
        rows.indices.forEach { index ->
            val row = rows[index]
            val raw = payload.bias + row.features.values.entries.sumOf { (key, value) -> payload.weights.getOrDefault(key, 0.0) * value }
            val weightedResidual = expectedRowWeights[index] * (raw - row.actualTerminalPayoff)
            interceptGradient += weightedResidual
            row.features.values.forEach { (key, value) ->
                featureGradients[key] = featureGradients.getValue(key) + value * weightedResidual
            }
        }
        return maxOf(
            abs(interceptGradient),
            featureGradients.maxOf { (key, gradient) -> abs(gradient + 0.01 * payload.weights.getValue(key)) },
        )
    }

    private fun denseCenteredRidgeReference(
        rows: List<OutcomeValueExample>,
        expectedRowWeights: List<Double>,
    ): LearnedOutcomeValueCheckpointPayload {
        require(rows.size == expectedRowWeights.size)
        val keys = rows.flatMap { it.features.values.keys }.toSet().sortedWith(utf8BytewiseStringComparator)
        val totalWeight = expectedRowWeights.sum()
        val mean = DoubleArray(keys.size) { index ->
            rows.indices.sumOf { row -> expectedRowWeights[row] * (rows[row].features.values[keys[index]] ?: 0.0) } / totalWeight
        }
        val labelMean = rows.indices.sumOf { row -> expectedRowWeights[row] * rows[row].actualTerminalPayoff } / totalWeight
        val system = Array(keys.size) { row -> DoubleArray(keys.size + 1) }
        keys.indices.forEach { left ->
            keys.indices.forEach { right ->
                system[left][right] = rows.indices.sumOf { row ->
                    expectedRowWeights[row] * (rows[row].features.values[keys[left]] ?: 0.0) * (rows[row].features.values[keys[right]] ?: 0.0)
                } - totalWeight * mean[left] * mean[right] + if (left == right) 0.01 else 0.0
            }
            system[left][keys.size] = rows.indices.sumOf { row ->
                expectedRowWeights[row] * (rows[row].features.values[keys[left]] ?: 0.0) * (rows[row].actualTerminalPayoff - labelMean)
            }
        }
        keys.indices.forEach { pivot ->
            val pivotRow = (pivot until keys.size).maxBy { candidate -> abs(system[candidate][pivot]) }
            val swap = system[pivot]
            system[pivot] = system[pivotRow]
            system[pivotRow] = swap
            val divisor = system[pivot][pivot]
            assertTrue(abs(divisor) > 1e-12)
            (pivot..keys.size).forEach { column -> system[pivot][column] /= divisor }
            keys.indices.filter { it != pivot }.forEach { row ->
                val factor = system[row][pivot]
                (pivot..keys.size).forEach { column -> system[row][column] -= factor * system[pivot][column] }
            }
        }
        val coefficients = keys.indices.associate { index -> keys[index] to system[index][keys.size] }
        val bias = labelMean - keys.indices.sumOf { index -> mean[index] * coefficients.getValue(keys[index]) }
        return LearnedOutcomeValueCheckpointPayload(training = binding(), bias = bias, weights = coefficients)
    }

    /** Synthetic numeric seam for optimizer arithmetic; production rows come only from the compiler. */
    private fun features(value: Double): org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatures {
        return features(mapOf("state/signal" to value))
    }

    private fun features(values: Map<String, Double>): org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatures {
        val constructor = org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatures::class.java
            .getDeclaredConstructor(String::class.java, Map::class.java)
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(
            "perspective-safe-outcome-value-features-v1",
            values,
        ) as org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatures
    }
}
