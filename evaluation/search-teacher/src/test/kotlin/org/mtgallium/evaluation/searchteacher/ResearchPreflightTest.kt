package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.agent.infoset.core.RolloutTurnHorizon

@org.junit.jupiter.api.Tag("public-source")
class ResearchPreflightTest {
    @Test
    fun `runtime fingerprints serialize canonical map content`() {
        val first = linkedMapOf("java" to "jvm", "classpath-0" to "classes")
        val reordered = linkedMapOf("classpath-0" to "classes", "java" to "jvm")
        assertEquals(preflightRuntimeHash(first), preflightRuntimeHash(reordered))
        assertNotEquals(preflightRuntimeHash(first), preflightRuntimeHash(first + ("classpath-0" to "changed")))
    }

    @Test
    fun `gameplay reductions preserve treatment policies and use a distinct schedule`() {
        val control = SearchTeacherCalibrationPolicy("control", 8, 64, 32, 1.4, false, 1.0)
        val candidate = control.copy(id = "candidate", simulations = 2, rolloutTurnHorizon = RolloutTurnHorizon(2, 512),
            rootRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.UNIFORM)
        val full = SearchTeacherCalibrationPlan(phase = SearchTeacherCalibrationPhase.DEVELOPMENT,
            baseSeed = 71, pairOffset = 9, pairCount = 24, control = control, candidates = listOf(candidate))
        val work = ResearchPreflightWork.Gameplay("/tmp/plan.json", true, "/tmp/deck.json", 2, 72)
        val smoke = gameplayPreflightPlan(full, work)
        assertEquals(full.copy(phase = SearchTeacherCalibrationPhase.PREFLIGHT, baseSeed = 72,
            pairOffset = 0, pairCount = 1, control = control.copy(simulations = 4)), smoke)
        assertFails { gameplayPreflightPlan(full, work.copy(smokeBaseSeed = 71)) }
    }

    @Test
    fun `every learner saves reloads and scores validation without fitting it`() {
        val train = listOf(root(1), root(2))
        val validation = root(3).copy(split = DecisionLocalSplit.VALIDATION)
        for (learner in PreflightLearner.entries) {
            val work = ResearchPreflightWork.Learning("/tmp/parent", "/tmp/precision", learner,
                nonlinear = DecisionLocalNonlinearConfig(epochs = 20))
            val first = Files.createTempDirectory("learning-preflight")
            val second = Files.createTempDirectory("learning-preflight-heldout")
            val result = runLearningPreflight(train + validation, work, first)
            val changed = validation.copy(candidates = validation.candidates.map {
                it.copy(primaryTerminalPayoffs = List(32) { 0.0 })
            })
            runLearningPreflight(train + changed, work, second)
            assertEquals(Files.readString(first.resolve("model.json")), Files.readString(second.resolve("model.json")))
            assertEquals("[\n    \"root-1\",\n    \"root-2\"\n]", result.getValue("training-roots"))
            assertTrue(Files.isRegularFile(first.resolve("scores.json")))
            if (learner == PreflightLearner.NONLINEAR) {
                val model = evidenceJson.decodeFromString<DecisionLocalNonlinearModel>(Files.readString(first.resolve("model.json")))
                assertEquals(2, model.config.epochs)
                assertEquals("20", result.getValue("full-epochs"))
            }
        }
    }

    @Test
    fun `learning rejects test access and overlapping game groups`() {
        val work = ResearchPreflightWork.Learning("/tmp/parent", "/tmp/precision", PreflightLearner.LINEAR)
        val output = Files.createTempDirectory("learning-preflight-refusal")
        assertFails { runLearningPreflight(listOf(root(1), root(2).copy(split = DecisionLocalSplit.TEST)), work, output) }
        assertFails { runLearningPreflight(listOf(root(1), root(2).copy(pairIndex = 1, split = DecisionLocalSplit.VALIDATION)), work, output) }
        assertTrue(Files.list(output).use { it.findAny().isEmpty })
    }

    @Test
    fun `cli requires explicit profile and output and typed profiles roundtrip`() {
        for (suite in listOf("research-preflight", "research-preflight-verify")) {
            assertFails { SearchTeacherCli.parse(arrayOf("--suite", suite)) }
            assertEquals(suite, SearchTeacherCli.parse(arrayOf("--suite", suite, "--profile", "/tmp/plan.json", "--output", "/tmp/output")).suite)
        }
        val plan = ResearchPreflightPlan(targetOutput = "/tmp/primary", work = ResearchPreflightWork.Learning(
            "/tmp/parent", "/tmp/precision", PreflightLearner.NONLINEAR))
        assertEquals(plan, evidenceJson.decodeFromString<ResearchPreflightPlan>(evidenceJson.encodeToString(plan)))
        assertFails { plan.copy(targetOutput = "relative") }
    }

    private fun root(index: Int) = DecisionLocalRootEvidence(
        rootId = "root-$index", split = DecisionLocalSplit.TRAIN, pairIndex = index,
        decisionFamily = "CAST_SPELL", phase = "PRECOMBAT_MAIN", turnNumber = 2,
        rootActor = "p0", representedKnowledgeCategory = "history", candidateFamilyDigest = "family",
        productionScheduleDigest = "schedule", primaryReplicates = 32, independentReplicates = 0,
        candidates = listOf(-1.0, 1.0).mapIndexed { i, action -> DecisionLocalCandidateEvidence(
            signature = ('a' + i).toString(), featureWorlds = 64, nonterminalFeatureWorlds = 64,
            terminalFeatureWorlds = 0, terminalFeatureOffset = 0.0,
            featureMeans = mapOf("action" to action), featureScheduleDigest = "features",
            cheapHeuristicScore = 0.0, failedGlobalModelScore = 0.0,
            primaryTerminalPayoffs = List(32) { action }, independentTerminalPayoffs = emptyList(),
            continuationPolicyDecisions = 32, continuationRuntimeMillis = 1.0,
        ) },
    )
}
