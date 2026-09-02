package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

class CorrectedNeuralDiagnosticHarnessTest {
    private val root: Path = Path.of("").toAbsolutePath().normalize().let { current ->
        if (Files.isDirectory(current.resolve("agent"))) current else current.resolve("../..").normalize()
    }

    @Test
    fun `representative no-op intervention composes from exact corrected inputs without training`() {
        val prepared = CorrectedNeuralDiagnosticPreparation(root).prepare(
            EvidenceStore(root).work("neural-behavioral-cloning/g20-seed20260902/corpus-manifest.json")
        )
        val cohorts = prepared.splitAt(323)

        assertEquals(
            "070eb011b92c6b95000425cf8ba20ac6bb499afff61cd5d788435621a3230cd2",
            prepared.population.manifestSha256,
        )
        assertEquals(
            "acf7357dcbde8830a845ac8c412c4e5ef8f05a177a4c40f79f20cc674487e7be",
            prepared.population.splitSha256,
        )
        assertEquals(CORRECTED_NEURAL_TRAINING_DECISIONS, prepared.rawOrderedDecisions.size)
        assertEquals(CORRECTED_NEURAL_EXPECTED_ORDER_SHA256, prepared.subsetSelectionOrderSha256)
        assertEquals(
            "57e0f2d245f49b5169002f0193b2e6f0193a23e7caf4776cb0b3e9793d3003b5",
            prepared.rawPrefixIdentity(128),
        )
        assertEquals(
            "3e8d3a740680e7326d6daa8845a1dedc78190559ca649ede52b3ae81c65582e1",
            prepared.rawPrefixIdentity(256),
        )
        assertEquals(323, cohorts.anchor.size)
        assertEquals(66, cohorts.added.size)
        assertEquals(prepared.orderedDecisions, cohorts.combined)
        assertEquals(
            "d1edeb67c53f2f040e1533ce506454f5f3d8fa32e75fa59bab2f8048fe76a799",
            cohorts.anchorIdentity,
        )
        assertEquals(
            "d9a5e153acc4e8711022b536cac45362ac4c569fcf5f9a2add5300d7ac0c95d5",
            cohorts.addedIdentity,
        )

        val rawFirst = prepared.rawOrderedDecisions.first()
        val correctedFirst = prepared.orderedDecisions.first()
        assertContentEquals(rawFirst.state.indices, correctedFirst.state.indices)
        assertContentEquals(
            rawFirst.state.values.map { it * CORRECTED_NEURAL_STATE_INPUT_SCALE }.toDoubleArray(),
            correctedFirst.state.values,
        )
        assertEquals(rawFirst.candidates, correctedFirst.candidates)
        assertEquals(49_248, prepared.modelConfig.parameterCount)
        assertEquals(0.01, prepared.trainingConfig.learningRate)
        assertEquals(CORRECTED_NEURAL_MAXIMUM_EPOCHS, prepared.trainingConfig.maximumEpochs)
        assertEquals(
            CORRECTED_NEURAL_CANDIDATE_UPDATE_SCALE,
            prepared.trainingConfig.candidateProjectionUpdateScale,
        )
        assertEquals(CORRECTED_NEURAL_SEEDS, prepared.trainingConfig.initializationSeeds)
        assertEquals(20, CORRECTED_NEURAL_CONFIRMATION_EPOCHS)

        val zeroStepModel = CandidateConditionedNeuralPolicy.initialize(
            prepared.modelConfig,
            CORRECTED_NEURAL_SEEDS.first(),
        ).artifact
        assertEquals(0, SparseAdam(zeroStepModel, prepared.trainingConfig).snapshotState().decisionSteps)
        assertEquals(
            listOf(
                "source provenance",
                "corpus load and contract validation",
                "training-population feature reconstruction",
                "deterministic order and cohort accounting",
            ),
            prepared.phaseTimings.map(CorrectedNeuralPreparationPhaseTiming::phase),
        )
        assertTrue(prepared.phaseTimings.all { it.elapsedMillis >= 0.0 })
        assertEquals(prepared.phaseTimings.sumOf { it.elapsedMillis }, prepared.totalPreparationMillis)
        println(
            "Corrected neural preparation: " + prepared.phaseTimings.joinToString { timing ->
                "${timing.phase}=${"%.3f".format(timing.elapsedMillis)}ms"
            } + "; total=${"%.3f".format(prepared.totalPreparationMillis)}ms"
        )
    }
}
