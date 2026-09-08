package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.research.run.*

@Tag("public-source")
class DirectAttackKernelGameplayTest {
    @Test fun `both comparator descriptors preserve frozen search fallback evaluator and rollouts`() {
        val screen = screenPlan()
        for (comparator in DirectAttackGameplayComparator.entries) {
            val plan = plan(comparator)
            val calibration = directAttackGameplayCalibration(screen, plan)
            assertEquals(screen.control, calibration.control.copy(id = screen.control.id, directAttackHeuristic = false))
            assertEquals(comparator == DirectAttackGameplayComparator.DIRECT_HEURISTIC, calibration.control.directAttackHeuristic)
            assertEquals(screen.control, calibration.candidates.single().copy(id = screen.control.id, directAttackKernelFit = null))
            assertEquals(screen.fit, calibration.candidates.single().directAttackKernelFit)
            assertEquals(screen.control.parameters(9), calibration.candidates.single().parameters(9))
            assertEquals(screen.control.parameters(9), calibration.control.parameters(9))
            assertEquals(SearchTeacherCalibrationPhase.CONFIRMATION, calibration.phase)
            assertEquals(plan.rule.maximumPairs, calibration.pairCount)
            assertEquals(0, calibration.pairOffset)
            assertEquals(plan, evidenceJson.decodeFromString<DirectAttackKernelGameplayPlan>(evidenceJson.encodeToString(plan)))
        }
        assertNotEquals(directAttackGameplayCalibration(screen, plan()).baseSeed,
            directAttackGameplayCalibration(screen, plan(DirectAttackGameplayComparator.DIRECT_HEURISTIC)).baseSeed)
    }

    @Test fun `failed incomplete wrong-population and inconsistent parent screens refuse gameplay`() {
        val rows = (0 until 32).map { i -> DirectAttackScreenRow("root-${i.toString().padStart(2, '0')}",
            "group-${i / 2}", "learned", listOf("planner", "planner"), listOf(.5, .5),
            listOf(.1, .1), listOf(.1, .1)) }
        val gate = directAttackScreenGate(rows)
        val screen = DirectAttackKernelScreenReport("synthetic-screen", source(), screenPlan(), 1024,
            emptyList(), emptyList(), "DEVELOPMENT_GATE_PASSED_GAMEPLAY_REQUIRED", rows, gate)
        requireDirectAttackGameplayScreen(screen)
        for (disposition in listOf("DEVELOPMENT_GATE_FAILED", "TARGET_FAILURE", "SELECTION_FAILURE")) {
            assertFailsWith<IllegalArgumentException> { requireDirectAttackGameplayScreen(screen.copy(disposition = disposition)) }
        }
        assertFailsWith<IllegalArgumentException> { requireDirectAttackGameplayScreen(screen.copy(gate = gate.copy(passed = false))) }
        assertFailsWith<IllegalArgumentException> { requireDirectAttackGameplayScreen(screen.copy(gate = null)) }
        assertFailsWith<IllegalArgumentException> { requireDirectAttackGameplayScreen(screen.copy(rows = rows.dropLast(1))) }
        assertFailsWith<IllegalArgumentException> {
            requireDirectAttackGameplayScreen(screen.copy(rows = rows.mapIndexed { index, row ->
                if (index == 0) row.copy(rootId = "another-root") else row }))
        }
        val failedRows = rows.map { it.copy(heuristicImprovement = listOf(-.1, -.1)) }
        assertFailsWith<IllegalArgumentException> { requireDirectAttackGameplayScreen(screen.copy(rows = failedRows,
            gate = directAttackScreenGate(failedRows))) }
    }

    @Test fun `seed schedule rejects any training or bank group and reserves both complete domains`() {
        val screen = screenPlan()
        val plannerPlan = plan().copy(rule = rule().copy(maximumPairs = 16))
        val calibration = directAttackGameplayCalibration(screen, plannerPlan)
        val seeds = directAttackGameplaySeedSchedule(plannerPlan, calibration, emptySet(), Long::toString)
        assertEquals(16, seeds.size)
        assertEquals(seeds, directAttackGameplaySeedSchedule(plannerPlan, calibration, setOf("unrelated"), Long::toString))
        for (excluded in listOf(setOf(seeds.first().toString()), setOf(seeds.last().toString()))) {
            assertFailsWith<IllegalArgumentException> {
                directAttackGameplaySeedSchedule(plannerPlan, calibration, excluded, Long::toString)
            }
        }
        val heuristicPlan = plan(DirectAttackGameplayComparator.DIRECT_HEURISTIC)
        val heuristic = directAttackGameplayCalibration(screen, heuristicPlan)
        val otherSeeds = directAttackGameplaySeedSchedule(heuristicPlan, heuristic, emptySet(), Long::toString)
        assertEquals(256, otherSeeds.size)
        assertTrue(seeds.toSet().intersect(otherSeeds.toSet()).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            directAttackGameplaySeedSchedule(plannerPlan, heuristic.copy(pairCount = 16), emptySet(), Long::toString)
        }
    }

    @Test fun `first strength prefix survives overshoot while all executed cost can refuse the gate`() {
        val decisive = decisiveScores()
        val prefix = directAttackGameplayResult(rule(), decisive)
        val scores = decisive + (0..2).map { PairedSequentialScore(decisive.size + it, 0.0) }
        val result = directAttackGameplayResult(rule(), scores)
        assertEquals(PairedSequentialDisposition.ABOVE_NULL, result.disposition)
        assertEquals(prefix.orderedPrefixSha256, result.orderedPrefixSha256)
        assertEquals(prefix.confidenceSequence, result.confidenceSequence)
        assertEquals(3, result.operationalOvershootPairs)
        val pairs = scores.mapIndexed { index, _ -> pair(index, candidateMillis = if (index < decisive.size) 50.0 else 10000.0) }
        val prefixCost = directGameplayDecisionCost(pairs.take(decisive.size), "control", "candidate")
        assertTrue(prefixCost.costGatePassed)
        val allCost = directGameplayDecisionCost(pairs, "control", "candidate")
        val stop = directAttackGameplayStop(true, emptyList(), allCost, false)
        assertEquals(DirectAttackGameplayStop.COST_LIMIT, stop)
        assertFalse(passed(result, stop = stop, costPassed = allCost.costGatePassed))
        val invalidScores = decisive + PairedSequentialScore(decisive.size, null, listOf("stopped overshoot"))
        val invalidResult = directAttackGameplayResult(rule(), invalidScores)
        assertEquals(prefix.orderedPrefixSha256, invalidResult.orderedPrefixSha256)
        assertEquals(prefix.confidenceSequence, invalidResult.confidenceSequence)
        assertEquals(1, invalidResult.operationalOvershootPairs)
        assertFalse(passed(invalidResult, operationalValid = false))
    }

    @Test fun `overshoot-only direct exposure cannot qualify either learned arm or same-role control`() {
        val scores = decisiveScores()
        val result = directAttackGameplayResult(rule(), scores + PairedSequentialScore(scores.size, .5))
        val pairs = (0..scores.size).map { index -> pair(index, learned = if (index == scores.size) 1 else 0) }
        val exposure = directAttackGameplayExposure(pairs, result.inspectedPairs, "control", "candidate")
        assertEquals(2L, exposure.learnedDirectSelections)
        assertEquals(0L, exposure.inspectedLearnedDirectSelections)
        assertFalse(passed(result, exposure = exposure))
        val heuristicPairs = (0..scores.size).map { index -> pair(index, heuristic = if (index == scores.size) 1 else 0) }
        val heuristicExposure = directAttackGameplayExposure(heuristicPairs, result.inspectedPairs, "control", "candidate")
        assertTrue(heuristicExposure.inspectedLearnedDirectSelections > 0)
        assertEquals(2L, heuristicExposure.controlDirectSelections)
        assertEquals(0L, heuristicExposure.inspectedControlDirectSelections)
        assertFalse(passed(result, comparator = DirectAttackGameplayComparator.DIRECT_HEURISTIC, exposure = heuristicExposure))
        assertFalse(passed(result, exposure = heuristicExposure), "Planner must make no direct selections even in overshoot")
    }

    @Test fun `runtime invalidity treatment failure missing costs and absent exposure cannot pass`() {
        val result = directAttackGameplayResult(rule(), decisiveScores())
        assertTrue(passed(result))
        val good = directGameplayDecisionCost(listOf(pair(0)), "control", "candidate")
        assertNull(directAttackGameplayStop(true, emptyList(), good, false))
        assertEquals(DirectAttackGameplayStop.TIME_LIMIT, directAttackGameplayStop(true, emptyList(), good, true))
        assertEquals(DirectAttackGameplayStop.INVALID_GAMEPLAY, directAttackGameplayStop(false, emptyList(), good, false))
        assertEquals(DirectAttackGameplayStop.TREATMENT_FAILURE, directAttackGameplayStop(true, listOf("changed rollout"), good, false))
        val missing = pair(0).let { pair -> pair.copy(games = pair.games.map { game -> game.copy(
            seatDiagnostics = game.seatDiagnostics.mapValues { (_, diagnostic) -> diagnostic.copy(decisionComputationMillis = null) }) }) }
        val missingCost = directGameplayDecisionCost(listOf(missing), "control", "candidate")
        assertEquals(DirectAttackGameplayStop.COST_DATA_FAILURE, directAttackGameplayStop(true, emptyList(), missingCost, false))
        for (stop in DirectAttackGameplayStop.entries) assertFalse(passed(result, stop = stop))
        assertFalse(passed(result, issues = listOf("changed budget")))
        assertFalse(passed(result, costPassed = false))
        assertFalse(passed(result, exposure = DirectAttackGameplayExposure(0, 0, 0, 0)))
        assertTrue(passed(result, comparator = DirectAttackGameplayComparator.DIRECT_HEURISTIC,
            exposure = DirectAttackGameplayExposure(2, 2, 1, 1)))
        assertFalse(passed(directAttackGameplayResult(rule(), emptyList())))
    }

    @Test fun `plan binds explicit parity rule and cost timing budget without changing old rollout protocol`() {
        val plan = plan()
        assertFailsWith<IllegalArgumentException> { plan.copy(rule = rule().copy(nullPointRate = .48, targetPointRate = .52)) }
        assertFailsWith<IllegalArgumentException> { plan.copy(rule = rule().copy(stopForFutility = true)) }
        assertFailsWith<IllegalArgumentException> { plan.copy(rule = rule().copy(maximumPairs = 257)) }
        assertFailsWith<IllegalArgumentException> { plan.copy(maximumWallSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { plan.copy(maximumWallSeconds = 7201) }
        assertFailsWith<IllegalArgumentException> { plan.copy(workers = 9) }
        assertFailsWith<IllegalArgumentException> { plan.copy(screenManifestSha256 = "unbound") }
        val calibration = directAttackGameplayCalibration(screenPlan(), plan)
        val bindings = directAttackGameplayBindings(plan, source(), calibration)
        assertEquals("direct-attack-kernel-fresh-gameplay-v1", bindings.protocol)
        for (changed in listOf(plan.copy(baseSeed = 73), plan.copy(comparator = DirectAttackGameplayComparator.DIRECT_HEURISTIC),
            plan.copy(screenManifestSha256 = "f".repeat(64)), plan.copy(maximumWallSeconds = 61),
            plan.copy(rule = rule().copy(falsePositiveRate = .01)))) {
            assertNotEquals(bindings.identity, directAttackGameplayBindings(changed, source(), directAttackGameplayCalibration(screenPlan(), changed)).identity)
        }
    }

    @Test fun `cli requires explicit plan output and deck inputs`() {
        val args = listOf("--suite", "direct-attack-kernel-gameplay", "--profile", "/tmp/plan.json",
            "--output", "/tmp/output", "--deck-manifest", "/tmp/deck.json")
        val parsed = SearchTeacherCli.parse(args.toTypedArray())
        assertEquals("direct-attack-kernel-gameplay", parsed.suite)
        assertEquals(Path.of("/tmp/plan.json"), parsed.profilePath)
        assertEquals(Path.of("/tmp/output"), parsed.outputPath)
        assertEquals(Path.of("/tmp/deck.json"), parsed.deckManifest)
        for (index in listOf(2, 4, 6)) assertFailsWith<IllegalArgumentException> {
            SearchTeacherCli.parse(args.filterIndexed { i, _ -> i !in index..index + 1 }.toTypedArray())
        }
    }

    private fun passed(result: PairedSequentialResult, stop: DirectAttackGameplayStop? = null,
        operationalValid: Boolean = true, issues: List<String> = emptyList(), costPassed: Boolean = true,
        comparator: DirectAttackGameplayComparator = DirectAttackGameplayComparator.PLANNER,
        exposure: DirectAttackGameplayExposure = DirectAttackGameplayExposure(1, 0, 1, 0)) =
        directAttackGameplayPassed(result, stop, operationalValid, issues, costPassed, comparator, exposure)

    private fun decisiveScores(): List<PairedSequentialScore> {
        val scores = mutableListOf<PairedSequentialScore>()
        while (pairedSequentialTest(rule(), scores, 0).disposition == PairedSequentialDisposition.CONTINUE) {
            scores += PairedSequentialScore(scores.size, 1.0)
        }
        assertEquals(PairedSequentialDisposition.ABOVE_NULL, pairedSequentialTest(rule(), scores, 0).disposition)
        return scores
    }

    private fun rule() = PairedSequentialRule(nullPointRate = .5, targetPointRate = .5,
        falsePositiveRate = .025, falseNegativeRate = .025, maximumPairs = 256)

    private fun plan(comparator: DirectAttackGameplayComparator = DirectAttackGameplayComparator.PLANNER) =
        DirectAttackKernelGameplayPlan(ResearchBuildReference("/tmp/build", "synthetic-build", "a".repeat(64)),
            CloningComparisonInput("/tmp/screen", "synthetic-screen"), "b".repeat(64), comparator, 71L,
            rule(), 60, 2000000000.0)

    private fun screenPlan(): DirectAttackKernelScreenPlan {
        val casting = RootKernelFitReference("/tmp/casting", "research-run-v1-sha256:" + "c".repeat(64), "d".repeat(64))
        val attack = casting.copy(directory = "/tmp/attack", researchRunIdentity = "research-run-v1-sha256:" + "e".repeat(64))
        return DirectAttackKernelScreenPlan(plan().build, CloningComparisonInput("/tmp/bank", "synthetic-bank"),
            (0 until 32).map { "root-${it.toString().padStart(2, '0')}" }, attack,
            SearchTeacherCalibrationPolicy("frozen-control", 8, 56, 16, 1.4, true, 1.0,
                evaluator = MonoRedVisibleEvaluatorConfig(), fastRootKernelRolloutFit = casting, fastOpponentKernelRolloutFit = casting),
            "selection", "target")
    }

    private fun source(): ResearchRunProvenance {
        val tree = ResearchSourceTreeState("a".repeat(40), "0".repeat(64), "0".repeat(64), "0".repeat(64))
        return ResearchRunProvenance(tree.revision, tree.revision, tree.revision, false, false,
            ResearchSourceProvenance(expectedArgentumRevision = tree.revision, outer = tree, argentum = tree))
    }

    private fun pair(index: Int, candidateMillis: Double = 50.0, learned: Int = 1, heuristic: Int = 0): SearchBudgetFrontierPair {
        val games = (0..1).map { leg ->
            val p0 = if (leg == 0) "control" else "candidate"
            val p1 = if (leg == 0) "candidate" else "control"
            fun diagnostics(policy: String) = ArenaSeatDiagnostics(policy, decisionComputationMillis = listOf(
                if (policy == "control") 100.0 else candidateMillis), selectionCounts = mapOf(
                SearchTeacherSelectionKind.DIRECT_POLICY_ACTION to if (policy == "control") heuristic else learned))
            GameRunResult(gameId = "game-$index-$leg", seed = index.toLong(), p0Policy = ArenaPolicyKind.SEARCH,
                p1Policy = ArenaPolicyKind.SEARCH, winner = null, terminal = true, disposition = GameRunDisposition.GAME_ENDED,
                decisions = 2, searchSeat = null, searchScore = null, illegalResponses = 0, fallbacks = 0,
                stepLimit = false, p0PolicyId = p0, p1PolicyId = p1,
                seatDiagnostics = mapOf("p0" to diagnostics(p0), "p1" to diagnostics(p1)))
        }
        return SearchBudgetFrontierPair(index, index.toLong(), games, true, emptyList(), 2.0)
    }
}
