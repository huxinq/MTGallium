package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli

@org.junit.jupiter.api.Tag("public-source")
class DecisionLocalSiblingOutcomeExperimentTest {
    @Test
    fun `stage one accepts no challenge input and refuses later stage panels`() {
        val args = arrayOf(
            "--suite", "decision-local-sibling-signal", "--deck-manifest", "deck.json",
            "--fixed-root-pilot", "pilot", "--fixed-root-manifest", "roots.json",
            "--outcome-corpus", "corpus", "--fixed-root-gate", "gate", "--output", "output",
        )
        assertEquals("decision-local-sibling-signal", SearchTeacherCli.parse(args).suite)
        assertFailsWith<IllegalArgumentException> {
            SearchTeacherCli.parse(args + arrayOf("--challenge-manifest", "panel.json"))
        }
    }

    @Test
    fun `stage one never interprets a partial or absent population as a signal pass`() {
        val signal = decisionLocalSignal(listOf(
            root("r1", listOf(candidate("a", 1.0, 1.0), candidate("b", 0.0, -1.0))),
        ))
        assertEquals(DecisionLocalConclusion.STAGE_ONE_SIGNAL_SUFFICIENT, decisionLocalStageOneConclusion(signal, 1))
        assertEquals(DecisionLocalConclusion.STAGE_ONE_INCOMPLETE, decisionLocalStageOneConclusion(signal, 2))
        assertEquals(DecisionLocalConclusion.STAGE_ONE_INCOMPLETE, decisionLocalStageOneConclusion(null, 2))
        val flat = decisionLocalSignal(listOf(
            root("r1", listOf(candidate("a", 1.0, 1.0), candidate("b", 0.0, 1.0))),
        ))
        assertEquals(DecisionLocalConclusion.STAGE_ONE_SIGNAL_INSUFFICIENT, decisionLocalStageOneConclusion(flat, 1))
    }

    @Test
    fun `execution cli retains both repeated challenge manifests`() {
        val parsed = SearchTeacherCli.parse(arrayOf(
            "--suite", "decision-local-sibling-outcome",
            "--deck-manifest", "deck.json",
            "--fixed-root-pilot", "pilot",
            "--fixed-root-manifest", "roots.json",
            "--outcome-corpus", "corpus",
            "--fixed-root-gate", "gate",
            "--challenge-manifest", "panel-14.json",
            "--challenge-manifest", "panel-34.json",
            "--output", "output",
        ))

        assertEquals(
            listOf(Path.of("panel-14.json"), Path.of("panel-34.json")).map { it.toAbsolutePath().normalize() },
            parsed.challengeManifests,
        )
    }

    @Test
    fun `root centered fit learns sibling direction without a global intercept`() {
        val roots = listOf(
            root("r1", listOf(candidate("a", 1.0, 1.0), candidate("b", 0.0, -1.0))),
            root("r2", listOf(candidate("a", 2.0, 1.0), candidate("b", 0.0, -1.0))),
        )

        val model = fitDecisionLocalModel(roots)

        assertTrue(model.score(roots[0].candidates[0]) > model.score(roots[0].candidates[1]))
        assertTrue(model.score(roots[1].candidates[0]) > model.score(roots[1].candidates[1]))
        assertTrue(model.modelId.startsWith("decision-local-linear-v1-sha256:"))
        assertEquals(model.modelId, evidenceJson.decodeFromString<DecisionLocalModelCheckpoint>(
            evidenceJson.encodeToString(DecisionLocalModelCheckpoint.serializer(), model)
        ).modelId)
    }

    @Test
    fun `label signal gate recognizes matched deterministic sibling separation`() {
        val signal = decisionLocalSignal(listOf(
            root("r1", listOf(candidate("a", 1.0, 1.0), candidate("b", 0.0, -1.0))),
            root("r2", listOf(candidate("a", 1.0, 1.0), candidate("b", 0.0, -1.0))),
        ))

        assertTrue(signal.sufficientToTrain)
        assertEquals(2, signal.meaningfulSpreadRoots)
        assertEquals(2, signal.distinguishableBestFromRunnerUpRoots)
        assertEquals(0.0, signal.meanCandidateStandardError)
    }

    @Test
    fun `deployment gate fails an ambiguity against the cheap heuristic`() {
        val learned = metric("decision-local-model", pairwise = 0.70, regret = 0.20, optimism = 0.10)
        val heuristic = metric("cheap-visible-heuristic", pairwise = 0.69, regret = 0.21, optimism = 0.10)

        val gate = evaluateGate(listOf(learned, heuristic))

        assertFalse(gate.passed)
        assertTrue(gate.reasons.any { it.contains("cheap-visible-heuristic") })
    }

    private fun root(id: String, candidates: List<DecisionLocalCandidateEvidence>) = DecisionLocalRootEvidence(
        rootId = id,
        split = DecisionLocalSplit.TRAIN,
        pairIndex = id.removePrefix("r").toInt(),
        decisionFamily = "CAST_SPELL",
        phase = "PRECOMBAT_MAIN",
        turnNumber = 2,
        rootActor = "p0",
        representedKnowledgeCategory = "represented-history",
        candidateFamilyDigest = "family-$id",
        productionScheduleDigest = "schedule-$id",
        primaryReplicates = 8,
        independentReplicates = 0,
        candidates = candidates.sortedBy { it.signature },
    )

    private fun candidate(signature: String, feature: Double, payoff: Double) = DecisionLocalCandidateEvidence(
        signature = signature,
        featureWorlds = 64,
        nonterminalFeatureWorlds = 64,
        terminalFeatureWorlds = 0,
        terminalFeatureOffset = 0.0,
        featureMeans = mapOf("x" to feature),
        featureScheduleDigest = "features-$signature-$feature",
        cheapHeuristicScore = feature,
        failedGlobalModelScore = -feature,
        primaryTerminalPayoffs = List(8) { payoff },
        independentTerminalPayoffs = emptyList(),
        continuationPolicyDecisions = 8,
        continuationRuntimeMillis = 1.0,
    )

    private fun metric(method: String, pairwise: Double, regret: Double, optimism: Double) =
        DecisionLocalMethodMetrics(
            method = method,
            roots = 10,
            pairwiseOrderingAccuracy = pairwise,
            withinRootRankCorrelation = pairwise,
            correctBestActionRate = pairwise,
            primarySelectedActionRegret = regret,
            independentSelectedActionRegret = regret,
            worstRootIndependentRegret = regret,
            meanPositiveSelectedOptimism = optimism,
            p90PositiveSelectedOptimism = optimism,
            meanPredictedMargin = 0.1,
            meanActualIndependentMargin = 0.1,
            composite = pairwise - regret - optimism,
            earlyGameIndependentRegret = regret,
            mulliganIndependentRegret = regret,
            regretByCandidateCount = mapOf(2 to regret),
        )
}
