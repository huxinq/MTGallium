package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunFiles

/**
 * Minimal host composition for the fixed learned outcome-value technical gate.  It deliberately
 * delegates corpus partition authority, fitting, checkpoint verification, validation, and TEST
 * sealing to their dedicated owners; this type only records and resumes their stage artifacts.
 */
internal data class LearnedOutcomeValueGateResult(
    val trainingRunIdentity: String,
    val validationRunIdentity: String,
    val validationPassed: Boolean,
    val testRunIdentity: String?,
    val outputRoot: Path,
)

internal class LearnedOutcomeValueGateRunner(
    private val repositoryRoot: Path,
) {
    fun run(corpusDirectory: Path, outputRoot: Path): LearnedOutcomeValueGateResult {
        val root = outputRoot.toAbsolutePath().normalize()
        requireFreshOrCompleteStages(root)

        val prepared = prepareLoadedOutcomeValueTraining(corpusDirectory, repositoryRoot)
        val trainingDirectory = stage(root, TRAINING_STAGE)
        val validationDirectory = stage(root, VALIDATION_STAGE)
        val admission = OutcomeValueAdmission.fromPrepared(prepared, trainingDirectory, validationDirectory)

        // A recorded validation failure is a terminal technical-gate result.  In particular, it
        // does not cause another fit and it never opens the TEST partition.
        if (!admission.passed) {
            require(!isCompleteStage(stage(root, TEST_STAGE))) {
                "Gate records validation FAIL but also contains a completed TEST stage"
            }
            return LearnedOutcomeValueGateResult(
                prepared.trainingRunIdentity,
                admission.validationRunIdentity,
                validationPassed = false,
                testRunIdentity = null,
                outputRoot = root,
            )
        }

        val testDirectory = stage(root, TEST_STAGE)
        val test = if (isCompleteStage(testDirectory)) admission.loadTest(testDirectory) else admission.evaluateAndPersistTest(testDirectory)
        return LearnedOutcomeValueGateResult(
            prepared.trainingRunIdentity,
            admission.validationRunIdentity,
            validationPassed = true,
            testRunIdentity = test.testRunIdentity,
            outputRoot = root,
        )
    }

    /**
     * Reopens only completed training and validation evidence, then returns the same opaque
     * promotion capability used by the gate.  A pilot therefore cannot create one from a report,
     * checkpoint, or caller-supplied TEST examples.
     */
    fun loadPromoted(corpusDirectory: Path, outputRoot: Path): PromotedOutcomeValueCheckpoint {
        val root = outputRoot.toAbsolutePath().normalize()
        require(isCompleteStage(stage(root, TRAINING_STAGE))) {
            "Learned outcome-value pilot requires a completed training stage"
        }
        require(isCompleteStage(stage(root, VALIDATION_STAGE))) {
            "Learned outcome-value pilot requires a completed validation stage"
        }
        val prepared = prepareLoadedOutcomeValueTraining(corpusDirectory, repositoryRoot)
        return OutcomeValueAdmission.fromPrepared(
            prepared,
            stage(root, TRAINING_STAGE),
            stage(root, VALIDATION_STAGE),
        ).promote()
    }

    private fun requireFreshOrCompleteStages(outputRoot: Path) {
        if (Files.exists(outputRoot)) require(Files.isDirectory(outputRoot)) { "Gate output root is not a directory: $outputRoot" }
        listOf(TRAINING_STAGE, VALIDATION_STAGE, TEST_STAGE).forEach { name ->
            val directory = stage(outputRoot, name)
            if (!Files.exists(directory)) return@forEach
            require(Files.isDirectory(directory)) { "Gate stage is not a directory: $directory" }
            if (!Files.exists(ResearchRunFiles.resolveBelow(directory, ResearchRunArtifacts.MANIFEST_FILE))) {
                error("Gate stage has partial output without ${ResearchRunArtifacts.MANIFEST_FILE}: $directory")
            }
        }
    }

    private fun isCompleteStage(directory: Path): Boolean =
        Files.exists(ResearchRunFiles.resolveBelow(directory, ResearchRunArtifacts.MANIFEST_FILE))

    private fun stage(outputRoot: Path, name: String): Path = ResearchRunFiles.resolveBelow(outputRoot, name)

    private companion object {
        const val TRAINING_STAGE = "training"
        const val VALIDATION_STAGE = "validation"
        const val TEST_STAGE = "test"
    }
}
