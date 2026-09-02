package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance

/**
 * Stable composition boundary for the corrected issue-0027-and-later neural diagnostics.
 *
 * A new diagnostic should start here, then keep its research-specific intervention in its own
 * issue source. This boundary owns only the fixed historical population, feature reconstruction,
 * nested order, corrected scales, learner shape, optimizer recipe, seeds, and reliability budget.
 * It deliberately does not own training topology, telemetry, interpretation, output, or launch.
 */
internal const val CORRECTED_NEURAL_SUBSET_PROTOCOL = "sha256-nested-training-decisions-v1"
internal const val CORRECTED_NEURAL_TRAINING_DECISIONS = 389
internal const val CORRECTED_NEURAL_EXPECTED_ORDER_SHA256 =
    "d5cf812bf30a94db545e44b875d5a0a36b1c2ed6ffe55023ede8b720419ab06e"
internal const val CORRECTED_NEURAL_STATE_INPUT_SCALE = 1.0 / 32.0
internal const val CORRECTED_NEURAL_CANDIDATE_UPDATE_SCALE = 1.0 / 8.0
internal const val CORRECTED_NEURAL_MAXIMUM_EPOCHS = 1_500
internal const val CORRECTED_NEURAL_CONFIRMATION_EPOCHS = 20
internal val CORRECTED_NEURAL_SEEDS = listOf(1729L, 3253L, 6997L)

internal data class CorrectedNeuralPreparationPhaseTiming(
    val phase: String,
    val elapsedMillis: Double,
)

internal data class CorrectedNeuralDiagnosticCohorts(
    val rawAnchor: List<EncodedBcDecision>,
    val rawAdded: List<EncodedBcDecision>,
    val anchor: List<EncodedBcDecision>,
    val added: List<EncodedBcDecision>,
    val anchorIdentity: String,
    val addedIdentity: String,
) {
    val combined: List<EncodedBcDecision> = anchor + added
}

internal data class CorrectedNeuralDiagnosticInputs(
    val implementationSourceProvenance: PolicySourceProvenance,
    val population: Issue0022HistoricalPopulation,
    val rawOrderedDecisions: List<EncodedBcDecision>,
    val orderedDecisions: List<EncodedBcDecision>,
    val subsetSelectionOrderSha256: String,
    val modelConfig: NeuralBcModelConfig,
    val trainingConfig: NeuralBcTrainingConfig,
    val phaseTimings: List<CorrectedNeuralPreparationPhaseTiming>,
) {
    val totalPreparationMillis: Double = phaseTimings.sumOf { it.elapsedMillis }

    init {
        require(rawOrderedDecisions.size == CORRECTED_NEURAL_TRAINING_DECISIONS)
        require(orderedDecisions.size == rawOrderedDecisions.size)
        require(subsetSelectionOrderSha256 == CORRECTED_NEURAL_EXPECTED_ORDER_SHA256)
    }

    fun rawPrefix(decisions: Int): List<EncodedBcDecision> {
        require(decisions in 1..rawOrderedDecisions.size)
        return rawOrderedDecisions.take(decisions)
    }

    fun prefix(decisions: Int): List<EncodedBcDecision> {
        require(decisions in 1..orderedDecisions.size)
        return orderedDecisions.take(decisions)
    }

    fun rawPrefixIdentity(decisions: Int): String = decisionIdentity(rawPrefix(decisions))

    fun splitAt(anchorDecisions: Int): CorrectedNeuralDiagnosticCohorts {
        require(anchorDecisions in 1 until rawOrderedDecisions.size)
        val rawAnchor = rawOrderedDecisions.take(anchorDecisions)
        val rawAdded = rawOrderedDecisions.drop(anchorDecisions)
        return CorrectedNeuralDiagnosticCohorts(
            rawAnchor = rawAnchor,
            rawAdded = rawAdded,
            anchor = orderedDecisions.take(anchorDecisions),
            added = orderedDecisions.drop(anchorDecisions),
            anchorIdentity = decisionIdentity(rawAnchor),
            addedIdentity = decisionIdentity(rawAdded),
        )
    }

    private fun decisionIdentity(decisions: List<EncodedBcDecision>): String = PolicyJson.sha256(
        decisions.joinToString("\n") { "${it.gameId}:${it.decisionIndex}" }
    )
}

internal class CorrectedNeuralDiagnosticPreparation(private val root: Path) {
    fun prepare(historicalManifestPath: Path): CorrectedNeuralDiagnosticInputs {
        val timings = mutableListOf<CorrectedNeuralPreparationPhaseTiming>()
        val implementation = timed(timings, "source provenance") {
            requireNotNull(RunProvenance.capture(root).sourceProvenance)
        }
        val population = timed(timings, "corpus load and contract validation") {
            Issue0022HistoricalCorpusReader(root).read(historicalManifestPath)
        }
        val encoder = NeuralBehavioralCloningFeatureEncoder()
        val train = timed(timings, "training-population feature reconstruction") {
            val trainGames = population.split.trainGames.toSet()
            population.examples.filter {
                it.gameId in trainGames && it.input.candidates.size >= PRIMARY_MIN_CANDIDATES
            }.map { it.encode(encoder) }.also {
                require(it.size == CORRECTED_NEURAL_TRAINING_DECISIONS)
            }
        }
        lateinit var rawOrdered: List<EncodedBcDecision>
        lateinit var ordered: List<EncodedBcDecision>
        lateinit var orderSha256: String
        timed(timings, "deterministic order and cohort accounting") {
            rawOrdered = deterministicNeuralMemorizationOrder(
                train,
                population.manifest.datasetIdentity,
                CORRECTED_NEURAL_SUBSET_PROTOCOL,
            )
            orderSha256 = PolicyJson.sha256(
                rawOrdered.joinToString("\n") { "${it.gameId}:${it.decisionIndex}" }
            )
            require(orderSha256 == CORRECTED_NEURAL_EXPECTED_ORDER_SHA256)
            ordered = rawOrdered.map { it.withStateInputScale(CORRECTED_NEURAL_STATE_INPUT_SCALE) }
        }
        val modelConfig = NeuralBcModelConfig(
            stateDimension = encoder.stateDimension,
            candidateDimension = encoder.candidateDimension,
        )
        val trainingConfig = NeuralBcTrainingConfig(
            maximumEpochs = CORRECTED_NEURAL_MAXIMUM_EPOCHS,
            learningRate = 0.01,
            candidateProjectionUpdateScale = CORRECTED_NEURAL_CANDIDATE_UPDATE_SCALE,
            initializationSeeds = CORRECTED_NEURAL_SEEDS,
        )
        return CorrectedNeuralDiagnosticInputs(
            implementationSourceProvenance = implementation,
            population = population,
            rawOrderedDecisions = rawOrdered,
            orderedDecisions = ordered,
            subsetSelectionOrderSha256 = orderSha256,
            modelConfig = modelConfig,
            trainingConfig = trainingConfig,
            phaseTimings = timings,
        )
    }

    private fun <T> timed(
        timings: MutableList<CorrectedNeuralPreparationPhaseTiming>,
        phase: String,
        block: () -> T,
    ): T {
        val started = System.nanoTime()
        return block().also {
            timings += CorrectedNeuralPreparationPhaseTiming(
                phase = phase,
                elapsedMillis = (System.nanoTime() - started) / 1_000_000.0,
            )
        }
    }
}
