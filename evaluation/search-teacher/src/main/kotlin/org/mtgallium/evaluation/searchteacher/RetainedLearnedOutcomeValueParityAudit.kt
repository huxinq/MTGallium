package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.SearchSettlementCounts
import org.mtgallium.agent.infoset.core.SearchStepResult
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import org.mtgallium.agent.infoset.core.Weighted
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueEvaluator
import org.mtgallium.agent.searchteacher.LearnedOutcomeValueFeatureCompiler
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory

/**
 * Fail-closed private-evidence audit for the first learned-leaf treatment.  It verifies existing
 * registered evidence and evaluates four retained TRAIN information states through the production
 * Search Teacher composition path.  It writes no evidence and cannot fit, promote, or read TEST.
 */
internal object RetainedLearnedOutcomeValueParityAudit {
    const val RETAINED_CORPUS_IDENTITY =
        "research-run-v1-sha256:2bfa611b3787046ccde4de4eacf712792fb3ffafa43d220d3db24c0890bf0957"
    const val RETAINED_TRAINING_IDENTITY =
        "research-run-v1-sha256:079cc6f71664ba1adcd26094c9fcf83f31d3c6e5b63fc9216f20cd4388615343"
    const val RETAINED_CHECKPOINT_PAYLOAD_SHA256 =
        "962d39cc861a55202b6efd1815cdfa71e9a82aec845441534e4286d2f1ca7876"

    fun run(
        corpusDirectory: Path,
        trainingDirectory: Path,
    ): RetainedLearnedOutcomeValueParityReport {
        val frames = loadVerifiedOutcomeValueCorpusRetainedTrainFrames(corpusDirectory, RETAINED_CORPUS_IDENTITY)
        val checkpoint = OutcomeValueCheckpointHostLoader.loadRetained(
            trainingDirectory,
            RETAINED_TRAINING_IDENTITY,
            RETAINED_CORPUS_IDENTITY,
            RETAINED_CHECKPOINT_PAYLOAD_SHA256,
        )
        val evaluator = checkpoint.evaluator
        require(evaluator.checkpointIdentity.training.corpusIdentity == RETAINED_CORPUS_IDENTITY) {
            "Retained parity audit refuses a substituted corpus identity"
        }
        require(checkpoint.envelope.payloadSha256 == RETAINED_CHECKPOINT_PAYLOAD_SHA256) {
            "Retained parity audit refuses a substituted checkpoint payload"
        }

        require(frames.map { it.rootPlayerId to it.example.actorRelation } == listOf("p0", "p1").flatMap { rootPlayerId ->
            listOf(OutcomeValueActorRelation.ROOT, OutcomeValueActorRelation.OPPONENT).map { rootPlayerId to it }
        }) { "Retained corpus selector did not return the predeclared four TRAIN strata" }
        val auditedFrames = frames
        val wrapperFrames = auditedFrames.filter { it.example.actorRelation == OutcomeValueActorRelation.ROOT }
            .associateBy(OutcomeValueCorpusFrame::rootPlayerId)
        require(wrapperFrames.keys == setOf("p0", "p1") && wrapperFrames.values.all { it.information.candidates.isNotEmpty() }) {
            "Retained parity wrapper requires one candidate-bearing root-actor frame for each recorded root player"
        }

        val reload = LearnedOutcomeValueEvaluator.load(evaluator.canonicalCheckpointBytes())
        require(reload.canonicalCheckpointBytes().contentEquals(evaluator.canonicalCheckpointBytes())) {
            "Canonical checkpoint serialization changes after reload"
        }
        val audited = auditedFrames.map { frame ->
            val direct = LearnedOutcomeValueFeatureCompiler.compile(
                frame.information,
                frame.rootPlayerId,
            )
            require(sameFeatureBits(frame.example.features.values, direct.values)) {
                "Corpus row feature map differs from direct information-state compilation"
            }
            val originalPrediction = evaluator.evaluate(frame.example.features)
            val reloadedPrediction = reload.evaluate(direct)
            require(originalPrediction.toBits() == reloadedPrediction.toBits()) {
                "Canonical checkpoint reload changes retained TRAIN prediction"
            }
            val live = runProductionLeaf(evaluator, frame, wrapperFrames.getValue(frame.rootPlayerId), terminal = false)
            require(live.rootValue.toBits() == originalPrediction.toBits()) {
                "Production Search Teacher path changed the retained model prediction"
            }
            require(live.settlement.learnedOutcomeEstimateBackups == 1 && live.settlement.terminalPayoffBackups == 0) {
                "Nonterminal retained leaf did not invoke exactly one learned settlement"
            }
            val terminal = runProductionLeaf(evaluator, frame, wrapperFrames.getValue(frame.rootPlayerId), terminal = true)
            require(terminal.rootValue.toBits() == frame.example.actualTerminalPayoff.toBits()) {
                "Terminal twin did not preserve its terminal payoff"
            }
            require(terminal.settlement.terminalPayoffBackups == 1 && terminal.settlement.learnedOutcomeEstimateBackups == 0) {
                "Terminal twin did not bypass learned inference"
            }
            RetainedLearnedOutcomeValueParityFrameReport(
                pairIndex = frame.example.pairIndex,
                leg = frame.example.leg,
                frameIndex = frame.example.frameIndex,
                actorRelation = frame.example.actorRelation,
                rootPlayerId = frame.rootPlayerId,
                informationStateDigest = frame.information.informationStateDigest,
                predictionBits = originalPrediction.toBits(),
            )
        }
        return RetainedLearnedOutcomeValueParityReport(
            corpusIdentity = RETAINED_CORPUS_IDENTITY,
            trainingIdentity = RETAINED_TRAINING_IDENTITY,
            checkpointPayloadSha256 = RETAINED_CHECKPOINT_PAYLOAD_SHA256,
            frames = audited,
            limitations = listOf(
                "The retained corpus stores one perspective-safe information state for its recorded root player; " +
                    "it does not retain a same-state opposite-perspective counterpart, so this audit does not synthesize one.",
            ),
        )
    }

    private fun runProductionLeaf(
        evaluator: LearnedOutcomeValueEvaluator,
        leafFrame: OutcomeValueCorpusFrame,
        wrapperFrame: OutcomeValueCorpusFrame,
        terminal: Boolean,
    ): ProductionLeafResult {
        val rootPlayer = leafFrame.rootPlayerId
        require(wrapperFrame.rootPlayerId == rootPlayer) {
            "Retained wrapper must preserve the leaf's recorded root player"
        }
        val rootChoice = requireNotNull(wrapperFrame.information.candidates.firstOrNull()) {
            "Retained root-actor wrapper frame has no candidate"
        }
        val world = RetainedLeafWorld(
            wrapperInformation = wrapperFrame.information,
            leafInformation = leafFrame.information,
            rootChoice = rootChoice,
            terminalPayoff = if (terminal) leafFrame.example.actualTerminalPayoff else null,
        )
        val search = SearchTeacherSearchFactory.create(
            config = InformationSetSearchConfig(
                simulations = 1,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1,
                ),
                maxPolicyDecisions = 1,
                initialExpansionLimit = 1,
                wideningThresholds = emptyList(),
                wideningLimits = emptyList(),
                cacheSimulationTransitions = false,
            ),
            opponentPolicy = UniformOpponentPolicy,
            informationEvaluator = evaluator,
        )
        val result = search.search(rootPlayer, singletonBelief(world), searchSeed = 0x5eedL)
        return ProductionLeafResult(result.rootValue, result.settlementCountsFor(rootChoice))
    }

    private fun singletonBelief(world: SearchWorld): BeliefBatch<Weighted<SearchWorld>> = BeliefBatch(
        particles = listOf(Weighted(world, 1.0)),
        diagnostics = BeliefDiagnostics(
            mode = BeliefMode.CONSISTENCY_ONLY_V1,
            requestedParticles = 1,
            acceptedParticles = 1,
            rejectedParticles = 0,
            effectiveSampleSizeBefore = 1.0,
            effectiveSampleSizeAfter = 1.0,
            entropy = 0.0,
            resamplingCount = 0,
        ),
    )

    private fun sameFeatureBits(left: Map<String, Double>, right: Map<String, Double>): Boolean =
        left.keys == right.keys && left.keys.all { key -> left.getValue(key).toBits() == right.getValue(key).toBits() }

    private data class ProductionLeafResult(
        val rootValue: Double,
        val settlement: SearchSettlementCounts,
    )

    /**
     * A single deterministic, candidate-bearing wrapper transition whose leaf is the exact
     * retained information state. The terminal twin changes only trusted world terminality to exercise the search
     * terminal-before-evaluator rule; it does not claim the retained nonterminal frame was final.
     */
    private class RetainedLeafWorld(
        private val wrapperInformation: PolicyInformationState,
        private val leafInformation: PolicyInformationState,
        private val rootChoice: org.mtgallium.agent.infoset.core.SemanticChoice,
        private val terminalPayoff: Double?,
        private var atRoot: Boolean = true,
    ) : SearchWorld {
        override fun actorToAct(): String? = if (atRoot) wrapperInformation.actingPlayerId else null

        override fun informationState(viewer: String): PolicyInformationState =
            if (atRoot) wrapperInformation else leafInformation

        override fun expandChoices(): PolicyExpansion = if (atRoot) {
            PolicyExpansion(
                candidates = listOf(rootChoice),
                isExhaustive = true,
                estimatedCandidateCount = 1,
                proposalVersion = "retained-leaf-parity-wrapper-v1",
            )
        } else {
            PolicyExpansion(
                candidates = emptyList(),
                isExhaustive = true,
                estimatedCandidateCount = 0,
                proposalVersion = "retained-leaf-parity-wrapper-v1",
            )
        }

        override fun step(choice: org.mtgallium.agent.infoset.core.SemanticChoice): SearchStepResult {
            require(atRoot && choice.signature == rootChoice.signature)
            atRoot = false
            return SearchStepResult(accepted = true)
        }

        override fun fork(): SearchWorld = RetainedLeafWorld(
            wrapperInformation,
            leafInformation,
            rootChoice,
            terminalPayoff,
            atRoot,
        )

        override fun terminalPayoff(rootPlayer: String): Double? = if (atRoot) null else terminalPayoff

        override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double =
            error("The retained learned-value parity wrapper must not use sampled-world evaluation")
    }
}

@Serializable
internal data class RetainedLearnedOutcomeValueParityReport(
    val corpusIdentity: String,
    val trainingIdentity: String,
    val checkpointPayloadSha256: String,
    val frames: List<RetainedLearnedOutcomeValueParityFrameReport>,
    val limitations: List<String>,
)

@Serializable
internal data class RetainedLearnedOutcomeValueParityFrameReport(
    val pairIndex: Int,
    val leg: String,
    val frameIndex: Int,
    val actorRelation: OutcomeValueActorRelation,
    val rootPlayerId: String,
    val informationStateDigest: String,
    val predictionBits: Long,
)
