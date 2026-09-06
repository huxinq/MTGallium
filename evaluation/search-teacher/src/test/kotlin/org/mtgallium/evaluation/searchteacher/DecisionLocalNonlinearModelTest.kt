package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@org.junit.jupiter.api.Tag("public-source")
class DecisionLocalNonlinearModelTest {
    @Test
    fun `nonlinear scorer learns context interaction erased by centering inputs`() {
        val roots = listOf(root(1, 1.0), root(2, -1.0), root(3, 1.0), root(4, -1.0))
        val baseline = fitLearnabilityModel(roots)
        val model = fitDecisionLocalNonlinearModel(roots, config())
        assertTrue(model.finalObjective < model.initialObjective * 0.1)
        assertTrue(roots.map { accuracy(it, it.candidates.map(baseline::score)) }.average() <= 0.5)
        roots.forEach { assertEquals(1.0, accuracy(it, model.scores(it))) }
        val restored = evidenceJson.decodeFromString(DecisionLocalNonlinearModel.serializer(),
            evidenceJson.encodeToString(DecisionLocalNonlinearModel.serializer(), model))
        assertEquals(model.modelId, restored.modelId)
        roots.forEach { assertEquals(model.scores(it), restored.scores(it)) }
        assertEquals(model, fitDecisionLocalNonlinearModel(roots.reversed(), config()))
    }

    @Test
    fun `phase interactions retain labels and separate opposite phase preferences`() {
        val roots = listOf(root(1, 1.0), root(2, -1.0).copy(phase = "COMBAT"))
            .map { r -> r.copy(candidates = r.candidates.map { it.copy(featureMeans = it.featureMeans - "shared") }) }
        val projected = roots.map(::conditionDecisionLocalFeatures)
        roots.zip(projected).forEach { (original, changed) ->
            assertEquals(original, changed.copy(candidates = changed.candidates.map { c ->
                c.copy(featureMeans = c.featureMeans.filterKeys { !it.startsWith("root-context/") })
            }))
        }
        val model = fitDecisionLocalPhaseModel(roots)
        val restored = evidenceJson.decodeFromString(DecisionLocalPhaseModel.serializer(),
            evidenceJson.encodeToString(DecisionLocalPhaseModel.serializer(), model))
        assertEquals(model.modelId, restored.modelId)
        roots.forEach {
            assertEquals(1.0, accuracy(it, restored.scores(it)))
            assertEquals(model.scores(it), restored.scores(it))
        }
    }

    @Test
    fun `inference never consumes outcome source action family or sibling heuristic`() {
        val roots = listOf(root(1, 1.0), root(2, -1.0))
        val model = fitDecisionLocalNonlinearModel(roots, config().copy(epochs = 5))
        val original = roots.first()
        val changed = original.copy(decisionFamily = "OTHER_CHOSEN_ACTION", candidates = original.candidates.map {
            it.copy(primaryTerminalPayoffs = it.primaryTerminalPayoffs.map { value -> -value },
                cheapHeuristicScore = 2000.0, failedGlobalModelScore = -2000.0,
                featureMeans = it.featureMeans + ("unseen-validation-feature" to 1000.0))
        })
        assertEquals(model.scores(original), model.scores(changed))
        val terminal = original.copy(candidates = original.candidates.map { it.copy(
            featureMeans = emptyMap(), nonterminalFeatureWorlds = 0, terminalFeatureWorlds = 64,
            terminalFeatureOffset = 0.75) })
        assertEquals(listOf(0.75, 0.75), model.scores(terminal))
    }

    @Test
    fun `fitting rejects heldout roots duplicate game groups and nonterminal labels`() {
        val root = root(1, 1.0)
        assertFailsWith<IllegalArgumentException> {
            fitDecisionLocalNonlinearModel(listOf(root, root.copy(rootId = "held", split = DecisionLocalSplit.VALIDATION)))
        }
        assertFailsWith<IllegalArgumentException> {
            fitDecisionLocalNonlinearModel(listOf(root, root.copy(rootId = "same-game")))
        }
        assertFailsWith<IllegalArgumentException> {
            fitDecisionLocalNonlinearModel(listOf(root.copy(candidates = root.candidates.map {
                it.copy(primaryTerminalPayoffs = List(32) { 0.4 })
            })))
        }
        assertFailsWith<IllegalArgumentException> { evaluateLearnabilityRoot(root, listOf(0.0)) }
        assertFailsWith<IllegalArgumentException> { evaluateLearnabilityRoot(root, listOf(Double.NaN, 0.0)) }
    }

    private fun config() = DecisionLocalNonlinearConfig(hiddenUnits = 8, epochs = 500,
        learningRate = 0.02, regularization = 0.0001, seed = 7L)

    private fun accuracy(root: DecisionLocalRootEvidence, scores: List<Double>) =
        evaluateLearnabilityRoot(root, scores).methods.first().nonTiedPairAccuracy!!

    private fun root(index: Int, context: Double) = DecisionLocalRootEvidence(
        rootId = "root-$index", split = DecisionLocalSplit.TRAIN, pairIndex = index,
        decisionFamily = "CAST_SPELL", phase = "PRECOMBAT_MAIN", turnNumber = 2,
        rootActor = "p0", representedKnowledgeCategory = "history", candidateFamilyDigest = "family",
        productionScheduleDigest = "schedule", primaryReplicates = 32, independentReplicates = 0,
        candidates = listOf(-1.0, 1.0).mapIndexed { i, action -> DecisionLocalCandidateEvidence(
            signature = ('a' + i).toString(), featureWorlds = 64, nonterminalFeatureWorlds = 64,
            terminalFeatureWorlds = 0, terminalFeatureOffset = 0.0,
            featureMeans = mapOf("action" to action, "shared" to context),
            featureScheduleDigest = "features", cheapHeuristicScore = 0.0, failedGlobalModelScore = 0.0,
            primaryTerminalPayoffs = List(32) { action * context }, independentTerminalPayoffs = emptyList(),
            continuationPolicyDecisions = 32, continuationRuntimeMillis = 1.0,
        ) },
    )
}
