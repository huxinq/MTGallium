package org.mtgallium.evaluation.searchteacher

import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mtgallium.agent.searchteacher.LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueCheckpointPayload
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueTrainingBinding
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints

@org.junit.jupiter.api.Tag("public-source")
class HistoricalOutcomeValueCheckpointAuditTest {
    @Test
    fun `historical diagnostic checkpoint is final and has no visible constructor`() {
        val type = HistoricalOutcomeValueDiagnosticCheckpoint::class.java

        assertTrue(java.lang.reflect.Modifier.isFinal(type.modifiers))
        assertTrue(!type.isInterface)
        assertTrue(type.constructors.all { it.isSynthetic })
    }

    @Test
    fun `historical audit accepts fully cross-bound synthetic evidence`() {
        val fixture = fixture()

        assertNotNull(invokeAudit(fixture))
    }

    @Test
    fun `historical audit retains the exact recorded frozen train payoff mean`() {
        val fixture = fixture(trainTerminalPayoffMean = 0.25)
        val evidence = invokeAudit(fixture)
        val frozen = evidence.javaClass.getDeclaredField("frozenTrainConstantPrediction").also { it.isAccessible = true }

        assertTrue(frozen.getDouble(evidence) == 0.25)
    }

    @Test
    fun `historical audit rejects an altered checkpoint envelope identity`() {
        val fixture = fixture()
        ResearchRunCheckpoints.persist(
            fixture.trainingDirectory.resolve(LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE),
            identity("wrong-training-run"),
            LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1,
            0,
            fixture.payloadBytes,
        )
        finalizeTraining(fixture)

        assertFailsWith<IllegalArgumentException> { invokeAudit(fixture) }
    }

    @Test
    fun `historical audit rejects an altered checkpoint payload hash`() {
        val fixture = fixture()
        val checkpoint = fixture.trainingDirectory.resolve(LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE)
        val original = Files.readString(checkpoint)
        Files.writeString(checkpoint, original.replace(fixture.payloadSha256, "0".repeat(64)))
        finalizeTraining(fixture)

        assertFailsWith<IllegalArgumentException> { invokeAudit(fixture) }
    }

    @Test
    fun `historical audit rejects a payload training binding that disagrees with corpus authority`() {
        val fixture = fixture(payloadTraining = binding("other-corpus"))

        assertFailsWith<IllegalArgumentException> { invokeAudit(fixture) }
    }

    @Test
    fun `historical audit rejects a canonical-looking test report with a forged identity`() {
        val fixture = fixture(testRunIdentity = identity("forged-test-run"))

        assertFailsWith<IllegalArgumentException> { invokeAudit(fixture) }
    }

    private fun invokeAudit(fixture: Fixture): Any {
        val owner = Class.forName("org.mtgallium.evaluation.searchteacher.LearnedOutcomeValueTrainingKt")
        val method = owner.getDeclaredMethod(
            "verifyHistoricalOutcomeValueCheckpointEvidence",
            LearnedOutcomeValueTrainingBinding::class.java,
            Path::class.java,
            Path::class.java,
            Path::class.java,
        )
        method.isAccessible = true
        return try {
            requireNotNull(method.invoke(null, fixture.expectedTraining, fixture.trainingDirectory, fixture.validationDirectory, fixture.testDirectory))
        } catch (failure: InvocationTargetException) {
            throw requireNotNull(failure.cause)
        }
    }

    private fun fixture(
        payloadTraining: LearnedOutcomeValueTrainingBinding = binding("corpus"),
        testRunIdentity: String? = null,
        trainTerminalPayoffMean: Double = 0.0,
    ): Fixture {
        val root = Files.createTempDirectory("historical-outcome-value-audit")
        val trainingDirectory = Files.createDirectories(root.resolve("training"))
        val validationDirectory = Files.createDirectories(root.resolve("validation"))
        val testDirectory = Files.createDirectories(root.resolve("test"))
        val expectedTraining = binding("corpus")
        val trainingRunIdentity = identity("training-run")
        val validationRunIdentity = identity("validation-run")
        val payload = LearnedOutcomeValueCheckpointPayload(
            training = payloadTraining,
            bias = 0.0,
            weights = mapOf("state/signal" to 1.0),
        )
        val payloadBytes = json.encodeToString(payload).encodeToByteArray()
        val envelope = ResearchRunCheckpoints.persist(
            trainingDirectory.resolve(LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE),
            trainingRunIdentity,
            LEARNED_OUTCOME_VALUE_CHECKPOINT_PAYLOAD_SCHEMA_V1,
            0,
            payloadBytes,
        )
        val trainingReport = OutcomeValueTrainingReport(
            trainingRunIdentity = trainingRunIdentity,
            checkpointPayloadSha256 = envelope.payloadSha256,
            rows = 2,
            games = 1,
            trainTerminalPayoffMean = trainTerminalPayoffMean,
            solverIterations = 1,
            maxKktResidual = 0.0,
            coefficientL2Norm = 1.0,
        )
        Files.writeString(trainingDirectory.resolve(LEARNED_OUTCOME_VALUE_TRAINING_REPORT_FILE), json.encodeToString(trainingReport))
        val validationReport = OutcomeValueValidationReport(
            validationRunIdentity = validationRunIdentity,
            trainingRunIdentity = trainingRunIdentity,
            immutableCheckpointPayloadSha256 = envelope.payloadSha256,
            validationPairSplitIdentity = expectedTraining.pairSplitIdentity,
            frozenTrainConstantBaselineMse = 1.0,
            metrics = metrics(),
            passed = true,
            failures = emptyList(),
        )
        Files.writeString(validationDirectory.resolve(LEARNED_OUTCOME_VALUE_VALIDATION_REPORT_FILE), json.encodeToString(validationReport))
        val canonicalTestRun = ResearchRunBindings(
            protocol = LEARNED_OUTCOME_VALUE_TEST_PROTOCOL,
            material = mapOf(
                "validation-run" to validationRunIdentity,
                "checkpoint" to envelope.payloadSha256,
                "corpus" to expectedTraining.corpusIdentity,
                "test-pair-split" to expectedTraining.pairSplitIdentity,
            ),
        ).identity
        val reportTestRun = testRunIdentity ?: canonicalTestRun
        val testReport = OutcomeValueTestReport(
            testRunIdentity = reportTestRun,
            validationRunIdentity = validationRunIdentity,
            immutableCheckpointPayloadSha256 = envelope.payloadSha256,
            metrics = metrics(),
        )
        Files.writeString(testDirectory.resolve(LEARNED_OUTCOME_VALUE_TEST_REPORT_FILE), json.encodeToString(testReport))
        val fixture = Fixture(
            expectedTraining,
            trainingDirectory,
            validationDirectory,
            testDirectory,
            trainingRunIdentity,
            validationRunIdentity,
            reportTestRun,
            payloadBytes,
            envelope.payloadSha256,
        )
        finalizeTraining(fixture)
        ResearchRunArtifacts(validationDirectory, validationRunIdentity).also {
            it.register(LEARNED_OUTCOME_VALUE_VALIDATION_REPORT_FILE)
            it.finalize()
        }
        ResearchRunArtifacts(testDirectory, reportTestRun).also {
            it.register(LEARNED_OUTCOME_VALUE_TEST_REPORT_FILE)
            it.finalize()
        }
        return fixture
    }

    private fun finalizeTraining(fixture: Fixture) {
        ResearchRunArtifacts(fixture.trainingDirectory, fixture.trainingRunIdentity).also {
            it.register(LEARNED_OUTCOME_VALUE_CHECKPOINT_FILE)
            it.register(LEARNED_OUTCOME_VALUE_TRAINING_REPORT_FILE)
            it.finalize()
        }
    }

    private fun binding(corpus: String): LearnedOutcomeValueTrainingBinding = LearnedOutcomeValueTrainingBinding(
        corpusIdentity = identity(corpus),
        pairSplitIdentity = identity("split"),
        learnerConfigurationIdentity = identity("configuration"),
        projectionIdentity = identity("projection"),
        rootBehaviorPolicyIdentity = identity("root-policy"),
        opponentBehaviorPolicyIdentity = identity("opponent-policy"),
        environmentProfileIdentity = identity("environment"),
    )

    private fun metrics() = OutcomeValueMetricReport(
        pairClusteredMse = 0.0,
        rawScoreMinimum = 0.0,
        rawScoreMaximum = 0.0,
        clippedFraction = 0.0,
        unseenFeatureOccurrences = 0,
        unseenFeatureKeys = 0,
        equalGamePredictionStandardDeviation = 0.0,
        rootActorMse = 0.0,
        opponentActorMse = 0.0,
    )

    private fun identity(name: String): String = "historical-audit-$name-sha256:" + "a".repeat(64)

    private data class Fixture(
        val expectedTraining: LearnedOutcomeValueTrainingBinding,
        val trainingDirectory: Path,
        val validationDirectory: Path,
        val testDirectory: Path,
        val trainingRunIdentity: String,
        val validationRunIdentity: String,
        val testRunIdentity: String,
        val payloadBytes: ByteArray,
        val payloadSha256: String,
    )

    private companion object {
        val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    }
}
