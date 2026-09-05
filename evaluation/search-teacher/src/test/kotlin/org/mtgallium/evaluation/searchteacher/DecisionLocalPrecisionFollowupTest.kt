package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli

@org.junit.jupiter.api.Tag("public-source")
class DecisionLocalPrecisionFollowupTest {
    @Test
    fun `fresh schedule is exactly 24 coordinates disjoint from retained eight`() {
        assertEquals((8..31).toList(), decisionLocalPrecisionReplicates)
        assertTrue(decisionLocalPrecisionReplicates.none { it in 0..7 })
    }

    @Test
    fun `precision CLI accepts parent without training corpus and rejects challenge panels`() {
        val args = arrayOf("--suite", "decision-local-precision-followup", "--deck-manifest", "deck.json",
            "--fixed-root-pilot", "pilot", "--fixed-root-manifest", "roots.json", "--precision-parent", "parent",
            "--output", "output")
        assertEquals("decision-local-precision-followup", SearchTeacherCli.parse(args).suite)
        assertFailsWith<IllegalArgumentException> { SearchTeacherCli.parse(args + arrayOf("--challenge-manifest", "challenge.json")) }
        assertFailsWith<IllegalArgumentException> { SearchTeacherCli.parse(args.filterIndexed { i, _ -> i !in 8..9 }.toTypedArray()) }
    }

    @Test
    fun `old winner remains frozen when independent outcomes reverse it`() {
        val old = root(listOf(candidate("a", listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, -1.0, -1.0)),
            candidate("b", List(8) { -1.0 })))
        val frozen = freezePrecisionComparison(old)
        val fresh = PrecisionRootSamples(old.rootId, "old-hash", listOf(
            samples("a", List(24) { -1.0 }), samples("b", List(24) { 1.0 })))
        val analysis = analyzePrecisionRoot(old, fresh, frozen)
        assertEquals("a", analysis.frozenComparison.bestSignature)
        assertEquals(1.5, analysis.frozenComparison.originalGap)
        assertEquals(-2.0, analysis.frozenComparisonFreshEffect.freshMeanDifference)
        assertEquals(-1.125, analysis.frozenComparisonFreshEffect.combinedMeanDifference)
        assertFalse(analysis.originalSelectedStillAmongFreshBest)
        assertEquals(0, analysis.reproducedPairDirections)
    }

    @Test
    fun `paired uncertainty uses common samples instead of marginal candidate variance`() {
        val values = List(24) { if (it % 2 == 0) 1.0 else -1.0 }
        val effect = precisionPairEffect("a", "b", List(8) { 1.0 }, List(8) { -1.0 }, values, values)
        assertEquals(0.0, effect.freshPairedStandardError)
        assertEquals(0, effect.freshDiscordantSamples)
        assertEquals(0.5, effect.combinedMeanDifference)
    }

    @Test
    fun `tied original tops have explicit tie marker and partial roots cannot be analyzed`() {
        val old = root(listOf(candidate("a", List(8) { 1.0 }), candidate("b", List(8) { 1.0 })))
        val frozen = freezePrecisionComparison(old)
        assertTrue(frozen.originalBestTied)
        val partial = PrecisionRootSamples(old.rootId, "hash", listOf(samples("a", List(23) { 1.0 }), samples("b", List(24) { 1.0 })))
        assertFailsWith<IllegalArgumentException> { analyzePrecisionRoot(old, partial, frozen) }
        assertFailsWith<IllegalArgumentException> { analyzePrecisionRoot(old, partial.copy(failure = "continuation exhausted"), frozen) }
    }

    private fun root(candidates: List<DecisionLocalCandidateEvidence>) = DecisionLocalRootEvidence(
        rootId = "root", split = DecisionLocalSplit.TRAIN, pairIndex = 0, decisionFamily = "MULLIGAN",
        phase = "PREGAME", turnNumber = 1, rootActor = "p0", representedKnowledgeCategory = "complete",
        candidateFamilyDigest = "family", productionScheduleDigest = "schedule", primaryReplicates = 8,
        independentReplicates = 0, candidates = candidates)

    private fun candidate(signature: String, values: List<Double>) = DecisionLocalCandidateEvidence(
        signature = signature, featureWorlds = 64, nonterminalFeatureWorlds = 64, terminalFeatureWorlds = 0,
        terminalFeatureOffset = 0.0, featureMeans = emptyMap(), featureScheduleDigest = "features",
        cheapHeuristicScore = 0.0, failedGlobalModelScore = 0.0, primaryTerminalPayoffs = values,
        independentTerminalPayoffs = emptyList(), continuationPolicyDecisions = 0, continuationRuntimeMillis = 0.0)

    private fun samples(signature: String, payoffs: List<Double>) = PrecisionCandidateSamples(signature,
        payoffs.mapIndexed { i, payoff -> DecisionLocalTerminalSample(i + 8, i % 8, i.toLong(), i.toLong(), payoff, 1, 1.0) })
}
