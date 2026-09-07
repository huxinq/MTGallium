package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import java.nio.file.Files
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.agent.searchteacher.MonoRedVisibleFeatures
import org.mtgallium.agent.searchteacher.MonoRedVisiblePermanentFeatures

@Tag("public-source")
class VisibleV2CalibrationTest {
    private val hand = MonoRedVisibleEvaluatorConfig()
    private val empty = MonoRedVisibleFeatures(20, 20, 0, 0, 0, 0, emptyList(), emptyList())
    private fun target(id: String, group: String, features: MonoRedVisibleFeatures, c: MonoRedVisibleEvaluatorConfig) =
        VisibleV2Target(id, group, features, features.evaluate(c), mapOf("synthetic" to features.evaluate(c)))

    @Test fun `coefficient coordinates retain body hand and all land marginal contributions`() {
        val f = empty.copy(rootLife = 13, opponentLife = 19, rootHandSize = 5, opponentHandSize = 2,
            rootLands = 8, opponentLands = 2,
            rootPermanents = listOf(MonoRedVisiblePermanentFeatures(3, 2, true), MonoRedVisiblePermanentFeatures(-1, 5, false)),
            opponentPermanents = listOf(MonoRedVisiblePermanentFeatures(1, 2, false)))
        val basis = visibleV2CoefficientFeatures(f)
        val coefficients = (listOf(hand.life, hand.hand, hand.power, hand.toughness, hand.haste) + hand.landMarginals + hand.landTail)
        assertEquals(f.rawScore(hand), basis.indices.sumOf { basis[it] * coefficients[it] }, 1e-12)
        assertEquals(listOf(0.0, 0.0, 1.0, 1.0, 1.0, 3.0), basis.slice(5..10))
    }
    @Test fun `seed groups receive equal weight and duplicate or invalid targets refuse`() {
        val rows = listOf(target("a", "g1", empty, hand), target("b", "g1", empty, hand), target("c", "g2", empty, hand))
        assertEquals(listOf(.25, .25, .5), visibleV2TargetWeights(rows))
        assertFailsWith<IllegalArgumentException> { visibleV2TargetWeights(rows + rows.first()) }
        assertFailsWith<IllegalArgumentException> { visibleV2TargetWeights(listOf(rows.first().copy(target = Double.NaN))) }
    }
    @Test fun `fixed form recovers synthetic changed coefficients and generalizes to combined coordinates`() {
        val truth = hand.copy(life = .4, hand = .8, power = .6, toughness = .2, haste = .1,
            landMarginals = listOf(.7, .6, .5, .4, .3), landTail = .1)
        val states = buildList {
            for (n in 1..5) { add(empty.copy(rootLife = 20 + n)); add(empty.copy(rootHandSize = n)) }
            for (n in 1..3) {
                add(empty.copy(rootPermanents = listOf(MonoRedVisiblePermanentFeatures(n, 0, false))))
                add(empty.copy(rootPermanents = listOf(MonoRedVisiblePermanentFeatures(0, n, false))))
            }
            add(empty.copy(rootPermanents = listOf(MonoRedVisiblePermanentFeatures(0, 0, true))))
            for (n in 1..7) add(empty.copy(rootLands = n))
        }
        val rows = states.mapIndexed { i, f -> target("r$i", "g${i % 3}", f, truth) }
        val fit = fitVisibleV2Coefficients(rows, hand, VisibleV2FitConfig(iterations = 2000, priorPenalty = 0.0))
        assertTrue(fit.fittedMeanSquaredError < 1e-8, "${fit.fittedMeanSquaredError}")
        assertTrue(fit.fittedMeanSquaredError < fit.handMeanSquaredError)
        assertEquals(hand.tanhScale, fit.fitted.tanhScale)
        val heldOut = empty.copy(rootLife = 17, rootHandSize = 3, rootLands = 6, opponentLands = 2,
            rootPermanents = listOf(MonoRedVisiblePermanentFeatures(2, 3, true)))
        assertEquals(heldOut.evaluate(truth), heldOut.evaluate(fit.fitted), 0.002)
    }
    @Test fun `matching hand targets retain initial coefficients with no artificial optimizer movement`() {
        val rows = listOf(target("a", "g1", empty.copy(rootLife = 23), hand), target("b", "g2", empty.copy(rootLands = 7), hand))
        val fit = fitVisibleV2Coefficients(rows, hand, VisibleV2FitConfig(iterations = 20))
        assertEquals(hand, fit.fitted)
        assertEquals(0, fit.selectedIteration)
        assertEquals(0.0, fit.fittedMeanSquaredError)
    }
    @Test fun `authenticated validation reference cannot supply fitting targets`() {
        val tree = PolicySourceTreeState("synthetic", "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val source = PolicySourceProvenance(expectedArgentumRevision = "synthetic", outer = tree, argentum = tree)
        val bank = RealGamePositionBankReport(bankIdentity = "bank", generatedAtUtc = "synthetic", sourceProvenance = source,
            plan = RealGamePositionBankPlan(sources = listOf(RealGamePositionBankSource("/synthetic", "source")),
                rootLimit = 1, maxRootsPerGame = 1, validationFraction = .25, selectionSeed = 1),
            sources = emptyList(), games = emptyList(), assignments = emptyList(), roots = emptyList(),
            accounting = RealGamePositionBankAccounting(0, 0, 0, 0, 0, 0, 0, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()),
            complete = false)
        val policy = SearchTeacherCalibrationPolicy("reference", 32, 64, 32, 1.4, true, 1.0)
        val plan = PositionBankScreenPlan(bankDirectory = "/synthetic", expectedBankIdentity = "bank",
            partition = PositionBankScreenPartition.VALIDATION, mode = PositionBankScreenMode.ACTION_CONDITIONAL,
            rootLimit = 1, repetitions = 2, policies = listOf(PositionBankScreenPolicy(policy, hand)))
        val directory = Files.createTempDirectory("v2-heldout-ref")
        val report = PositionBankScreenReport(researchRunIdentity = "reference-run", sourceProvenance = source,
            generatedAtUtc = "synthetic", plan = plan, workerThreads = 1, eligibleRoots = 0,
            selectedRootIds = emptyList(), rows = emptyList(), valid = true)
        writeJsonAtomically(directory.resolve("report.json"), report)
        ResearchRunArtifacts(directory, "reference-run").also { it.register("report.json"); it.finalize() }
        assertFailsWith<IllegalArgumentException> {
            visibleV2ReferenceTargets(bank, SavedRootPolicyInput(directory.toString(), "reference-run", "reference"),
                PositionBankScreenPartition.DEVELOPMENT, hand)
        }
    }

    @Test fun `reference intervention cannot silently change continuation or evaluator`() {
        val selection = SearchTeacherCalibrationPolicy("select", 8, 64, 32, 1.4, true, 1.0)
        val reference = selection.copy(id = "reference", particles = 32)
        VisibleV2ExperimentPlan("/bank", "bank", selection, reference)
        assertFailsWith<IllegalArgumentException> { VisibleV2ExperimentPlan("/bank", "bank", selection, reference.copy(maxPolicyDecisions = 64)) }
        assertFailsWith<IllegalArgumentException> { VisibleV2ExperimentPlan("/bank", "bank", selection, reference.copy(evaluator = hand.copy(life = .4))) }
    }

}
