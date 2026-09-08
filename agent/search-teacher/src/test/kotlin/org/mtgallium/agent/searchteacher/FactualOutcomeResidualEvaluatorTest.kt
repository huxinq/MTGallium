package org.mtgallium.agent.searchteacher

import kotlin.test.*
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.*

class FactualOutcomeResidualEvaluatorTest {
    private val leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_FACTUAL_OUTCOME_RESIDUAL_V1)
    private fun payload(bias: Double = 0.0, weights: Map<String, Double> = emptyMap()) = FactualOutcomeResidualCheckpointPayload(
        training = FactualOutcomeResidualTrainingBinding("corpus-sha256:${"a".repeat(64)}", "allocation-sha256:${"b".repeat(64)}",
            "run-sha256:${"c".repeat(64)}", "environment-sha256:${"d".repeat(64)}"), bias = bias, weights = weights)

    @Test
    fun `zero residual preserves exact V2 values and its legitimate nonterminal domain`() {
        val zero = FactualOutcomeResidualEvaluator.fromCheckpoint(payload())
        for (viewer in listOf("p0", "p1")) for (life in listOf(1, 10, 20, 31)) {
            val info = information(viewer, life = life).copy(knowledge = PolicyKnowledgeState.empty(viewer))
            assertEquals(MonoRedInformationEvaluator.evaluate(info, viewer).toBits(), zero.evaluate(info, viewer).toBits())
        }
        assertFailsWith<LearnedOutcomeValueException> { zero.evaluate(information("p0").copy(terminated = true, winnerId = "p0"), "p0") }
        assertFailsWith<LearnedOutcomeValueException> { zero.evaluate(information("p0"), "p1") }
    }

    @Test
    fun `anchored inference clips only after adding residual and missing vocabulary is zero`() {
        val info = information("p0")
        val feature = LearnedOutcomeValueFeatureCompiler.compile(info, "p0").values.entries.first()
        val evaluator = FactualOutcomeResidualEvaluator.fromCheckpoint(payload(0.25, mapOf(feature.key to 0.5)))
        val detail = evaluator.evaluateDetailed(info, "p0")
        assertEquals(MonoRedInformationEvaluator.evaluate(info, "p0"), detail.anchorValue)
        assertEquals(0.25 + 0.5 * feature.value, detail.residualValue)
        assertEquals((detail.anchorValue + detail.residualValue).coerceIn(-1.0, 1.0), detail.deployedValue)
        assertEquals(1.0, FactualOutcomeResidualEvaluator.fromCheckpoint(payload(3.0)).evaluate(info, "p0"))
        assertEquals(-1.0, FactualOutcomeResidualEvaluator.fromCheckpoint(payload(-3.0)).evaluate(info, "p0"))
        val other = information("p0", phase = "POSTCOMBAT_MAIN")
        val newKey = (LearnedOutcomeValueFeatureCompiler.compile(other, "p0").values.keys -
            LearnedOutcomeValueFeatureCompiler.compile(info, "p0").values.keys).first()
        val unseen = FactualOutcomeResidualEvaluator.fromCheckpoint(payload(0.1, mapOf(newKey to 100.0)))
        assertEquals(MonoRedInformationEvaluator.evaluate(info, "p0") + 0.1, unseen.evaluate(info, "p0"))
    }

    @Test
    fun `checkpoint bytes identities and observers remain bound while incompatible payloads fail`() {
        val keys = LearnedOutcomeValueFeatureCompiler.compile(information("p0"), "p0").values.keys.take(2)
        val first = FactualOutcomeResidualEvaluator.fromCheckpoint(payload(weights = linkedMapOf(keys[1] to 0.2, keys[0] to 0.1)))
        val second = FactualOutcomeResidualEvaluator.load(first.canonicalCheckpointBytes())
        assertEquals(first.canonicalCheckpointPayload, FactualOutcomeResidualEvaluator.encodeCanonicalCheckpoint(payload(weights = mapOf(keys[0] to 0.1, keys[1] to 0.2))))
        assertEquals(first.checkpointIdentity, second.checkpointIdentity)
        assertNotEquals(first.configurationId, FactualOutcomeResidualEvaluator.fromCheckpoint(payload(0.1)).configurationId)
        for (field in listOf("anchorId", "targetId", "modelAlgorithmId", "objectiveId", "featureScalingId", "deploymentId", "evaluatorId")) {
            val json = Json.parseToJsonElement(first.canonicalCheckpointPayload).jsonObject
            assertFailsWith<LearnedOutcomeValueException> { FactualOutcomeResidualEvaluator.load(JsonObject(json + (field to JsonPrimitive("wrong"))).toString()) }
        }
        assertFailsWith<LearnedOutcomeValueException> { LearnedOutcomeValueEvaluator.load(first.canonicalCheckpointPayload) }
        val seen = mutableListOf<FactualOutcomeResidualEvaluation>()
        val observed = first.observedEvaluationBy { _, _, value -> seen += value }
        val strategy = SearchTeacherEvaluatorRegistry.strategy(leaf, observed)
        assertEquals(first.configurationId, strategy.source.invokedEvaluatorConfigurationId)
        assertEquals(first.evaluate(information("p0"), "p0"), observed.evaluate(information("p0"), "p0"))
        assertEquals(1, seen.size)
    }

    @Test
    fun `registry refuses absent spoofed checkpoint wrong horizon mode overrides and trace reuse`() {
        val evaluator = FactualOutcomeResidualEvaluator.fromCheckpoint(payload())
        assertFailsWith<IllegalArgumentException> { SearchTeacherEvaluatorRegistry.strategy(leaf) }
        assertFailsWith<IllegalArgumentException> { SearchTeacherEvaluatorRegistry.strategy(leaf, object : InformationStateEvaluator {
            override val id = evaluator.id
            override fun evaluate(information: PolicyInformationState, rootPlayer: String) = 0.0
        }) }
        for (source in listOf(LeafStateSource.CURRENT_INFORMATION_STATE, LeafStateSource.CURRENT_SAMPLED_WORLD)) {
            assertFailsWith<IllegalArgumentException> { SearchTeacherEvaluatorRegistry.strategy(leaf.copy(stateSource = source), evaluator) }
        }
        assertFailsWith<IllegalArgumentException> { search(config().copy(maxPolicyDecisions = 17), evaluator) }
        assertFailsWith<IllegalArgumentException> { search(config().copy(rolloutTurnHorizon = RolloutTurnHorizon(1)), evaluator) }
        assertFailsWith<IllegalArgumentException> { SearchTeacherEvaluatorRegistry.strategy(leaf.copy(rolloutHorizonSettlementOverride = RolloutHorizonSettlementOverride.DIRECT_EVALUATION), evaluator) }
        assertFailsWith<IllegalArgumentException> { SearchTeacherSearchFactory.create(config(), informationEvaluator = evaluator,
            reuseConfig = InformationSetSearchReuseConfig(enabled = true)) }
        assertFailsWith<IllegalArgumentException> { SearchTeacherEvaluatorRegistry.strategy(
            LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1), evaluator) }
    }

    @Test
    fun `zero residual matches bounded16 search choices values visits and terminal bypass`() {
        val depths = mutableListOf<Int>()
        val evaluator = FactualOutcomeResidualEvaluator.fromCheckpoint(payload())
        val observed = evaluator.observedEvaluationBy { info, _, _ -> depths += info.observation.turnNumber }
        for (seed in listOf(11L, 29L)) {
            val zero = search(config(), observed).search("p0", belief(FixtureWorld()), seed)
            val v2 = search(config().copy(leaf = leaf.copy(evaluator = LeafEvaluator.MTGALLIUM_VISIBLE_V2)), MonoRedInformationEvaluator)
                .search("p0", belief(FixtureWorld()), seed)
            assertEquals(v2.chosen, zero.chosen)
            assertEquals(v2.rootValue.toBits(), zero.rootValue.toBits())
            assertEquals(v2.candidates, zero.candidates)
            assertTrue(zero.candidateSettlementCounts.values.sumOf { it.learnedOutcomeEstimateBackups } > 0)
        }
        assertTrue(depths.isNotEmpty() && depths.all { it == 16 })
        depths.clear()
        val terminal = search(config(), observed).search("p0", belief(FixtureWorld(terminalAt = 1)), 7L)
        assertEquals(1.0, terminal.rootValue)
        assertTrue(depths.isEmpty())
        assertTrue(terminal.candidateSettlementCounts.values.all { it.terminalPayoffBackups == it.successfulBackups })
    }

    private fun config() = InformationSetSearchConfig(simulations = 8, maxPolicyDecisions = 16, leaf = leaf)
    private fun search(config: InformationSetSearchConfig, evaluator: InformationStateEvaluator) = SearchTeacherSearchFactory.create(config,
        UniformOpponentPolicy, UniformOpponentPolicy, UniformOpponentPolicy, evaluator)
    private fun belief(world: SearchWorld) = BeliefBatch(particles = listOf(Weighted(world, 1.0)), diagnostics = BeliefDiagnostics(
        mode = BeliefMode.CONSISTENCY_ONLY_V1, requestedParticles = 1, acceptedParticles = 1, rejectedParticles = 0,
        effectiveSampleSizeBefore = 1.0, effectiveSampleSizeAfter = 1.0, entropy = 0.0, resamplingCount = 0))
    private inner class FixtureWorld(private val terminalAt: Int? = null, private var depth: Int = 0, private var branch: Int = 0) : SearchWorld {
        override fun actorToAct(): String = if (depth % 2 == 0) "p0" else "p1"
        override fun informationState(viewer: String) = information(viewer, actorToAct(), 20 + branch, depth)
        override fun expandChoices() = PolicyExpansion(if (depth == 0) listOf(choice("a"), choice("b")) else listOf(choice("advance")), true, if (depth == 0) 2 else 1, "residual-fixture")
        override fun step(choice: SemanticChoice): SearchStepResult { if (depth == 0) branch = if (choice == choice("b")) 4 else 0; depth++; return SearchStepResult(true) }
        override fun fork(): SearchWorld = FixtureWorld(terminalAt, depth, branch)
        override fun terminalPayoff(rootPlayer: String): Double? = if (terminalAt != null && depth >= terminalAt) 1.0 else null
        override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double = error("No sampled state evaluator")
    }
    private fun choice(label: String) = SemanticChoice.create(kind = SemanticChoiceKind.ACTION, operationFamily = SemanticOperationFamily.OTHER,
        display = SemanticChoiceDisplay(label), canonicalPayload = buildJsonObject { put("label", label) })
    private fun information(viewer: String, actor: String = "p0", life: Int = 20, depth: Int = 1, phase: String = "PRECOMBAT_MAIN") =
        PolicyInformationState(actingPlayerId = actor, observation = PolicyObservation(perspectivePlayerId = viewer,
            turnNumber = depth, phase = phase, step = phase, activePlayerId = "p0", priorityPlayerId = actor,
            players = listOf(PolicyPlayerView("p0", "First", life, 1, 40, 0, 0, PolicyManaPool(), true, actor == "p0", false),
                PolicyPlayerView("p1", "Second", 10, 1, 40, 0, 0, PolicyManaPool(), false, actor == "p1", false)),
            zones = emptyList(), stack = emptyList(), currentTurnStateComplete = true, pendingDecision = null,
            observationDigest = PolicyJson.sha256("$viewer:$actor:$life:$depth:$phase")),
            informationStateDigest = PolicyJson.sha256("information:$viewer:$actor:$life:$depth:$phase"),
            history = emptyList(), historyCommitment = PolicyHistoryCommitment.replay(emptyList()),
            knowledge = PolicyKnowledgeState(perspectivePlayerId = viewer, knowledgeDigest = PolicyJson.sha256("knowledge:$viewer")),
            candidates = emptyList(), terminated = false, winnerId = null)
}
