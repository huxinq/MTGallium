package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.infoset.core.RolloutTurnHorizon
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.ResearchRunCheckpoints

@Tag("public-source")
class SearchTeacherCalibrationTest {
    @Test
    fun `sequential quick start output belongs beneath the private work subtree`() {
        val store = EvidenceStore(Files.createTempDirectory("sequential-example-repository-"))
        val output = store.workRoot.resolve("sequential-trial")
        assertEquals(output.toAbsolutePath().normalize(),
            store.requireDiagnosticOutput(output, "sequential example"))
        assertFails {
            store.requireDiagnosticOutput(store.workRoot.parent.parent.resolve("sequential-trial"),
                "sequential example outside work")
        }
    }

    @Test
    fun `public sequential example parses and stops decisive sequences before its cap`() {
        val fixture = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("examples/search-teacher-sequential.json") }
            .first { Files.isRegularFile(it) }
        val example = evidenceJson.decodeFromString<SearchTeacherSequentialPlan>(Files.readString(fixture))
        assertEquals(example.calibration.pairCount, example.rule.maximumPairs)
        assertEquals(64, example.calibration.control.simulations)
        assertEquals(8, example.calibration.control.particles)
        assertEquals(example.calibration.control.copy(id = "candidate-8p32s", simulations = 32),
            example.calibration.candidates.single())
        assertEquals(PairedSequentialRule(nullPointRate = 0.5, targetPointRate = 0.5,
            falsePositiveRate = 0.025, falseNegativeRate = 0.025, maximumPairs = 24,
            betFractions = listOf(0.2, 0.5, 0.8), stopForFutility = true), example.rule)
        for ((score, expected) in listOf(1.0 to PairedSequentialDisposition.ABOVE_NULL,
            0.0 to PairedSequentialDisposition.BELOW_TARGET)) {
            val result = pairedSequentialTest(example.rule,
                List(example.rule.maximumPairs) { PairedSequentialScore(it, score) }, 0)
            assertEquals(expected, result.disposition)
            assertTrue(result.inspectedPairs < example.rule.maximumPairs)
        }
    }

    private val control = SearchTeacherCalibrationPolicy("control", 8, 64, 32, 1.4, false, 1.0)
    private val candidate = control.copy(id = "candidate", simulations = 32)
    private val plan = SearchTeacherCalibrationPlan(phase = SearchTeacherCalibrationPhase.DEVELOPMENT,
        baseSeed = 20260906L, pairOffset = 0, pairCount = 2, control = control, candidates = listOf(candidate))
    private val tree = PolicySourceTreeState("source", "a".repeat(64), "b".repeat(64), "c".repeat(64))
    private val source = PolicySourceProvenance(expectedArgentumRevision = "source", outer = tree, argentum = tree)

    @Test
    fun `plan binds source full interventions and explicit schedule`() {
        fun identity(p: SearchTeacherCalibrationPlan = plan, s: PolicySourceProvenance = source,
            policies: Map<String, String> = mapOf("control" to "c", "candidate" to "t"), deck: String = "deck", workers: Int = 1) =
            searchTeacherCalibrationBindings(p, s, policies, deck, "pool", workers).identity
        val baseline = identity()
        val variations = listOf(plan.copy(phase = SearchTeacherCalibrationPhase.CONFIRMATION),
            plan.copy(pairOffset = 10), plan.copy(pairCount = 3), plan.copy(baseSeed = 1),
            plan.copy(control = control.copy(particles = 4))) + listOf(
            candidate.copy(particles = 4), candidate.copy(simulations = 16), candidate.copy(maxPolicyDecisions = 16),
            candidate.copy(explorationConstant = 0.7), candidate.copy(singletonSelection = true),
            candidate.copy(rolloutHeuristicProbability = 0.9),
            candidate.copy(rolloutTurnHorizon = RolloutTurnHorizon(2, 96)),
            candidate.copy(rootRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.UNIFORM),
            candidate.copy(opponentRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.SEMANTIC_HEURISTIC),
        ).map { plan.copy(candidates = listOf(it)) }
        variations.forEach { assertNotEquals(baseline, identity(it)) }
        assertNotEquals(baseline, identity(s = source.copy(outer = tree.copy(revision = "later"))))
        assertNotEquals(baseline, identity(policies = mapOf("control" to "c", "candidate" to "changed")))
        assertNotEquals(baseline, identity(deck = "other-deck"))
        assertNotEquals(baseline, identity(workers = 2))
    }

    @Test
    fun `explicit nonoverlapping offsets supply disjoint seeds and candidates share schedule`() {
        val confirmation = plan.copy(phase = SearchTeacherCalibrationPhase.CONFIRMATION, pairOffset = 100)
        val developmentSeeds = (0..1).map(plan::pairSeed).toSet()
        assertTrue((100..101).map(confirmation::pairSeed).none(developmentSeeds::contains))
        assertEquals(plan.pairSeed(0), plan.copy(candidates = listOf(candidate.copy(particles = 16))).pairSeed(0))
        assertFails { confirmation.pairSeed(0) }
        assertFails { plan.copy(pairOffset = Int.MAX_VALUE) }
    }

    @Test
    fun `single variable treatment preserves shared runtime parameters and baseline rollouts`() {
        assertEquals(control.parameters(71), candidate.parameters(71).copy(simulations = 64))
        assertEquals(71L, control.parameters(71).baseSeed)
        assertNull(control.policy(71).rootRolloutPolicy)
        assertNull(control.policy(71).opponentRolloutPolicy)
        val blend = candidate.copy(rolloutHeuristicProbability = 0.9).policy(71)
        assertEquals(candidate.parameters(71), blend.parameters)
        assertEquals(listOf(0.9, 1.0 - 0.9), blend.effectiveRootRolloutPolicy().behaviorSpecification.components.map { it.weight })
    }

    @Test
    fun `root and opponent rollout policies are independently configurable without changing defaults`() {
        val defaults = control.policy(71)
        assertNull(defaults.rootRolloutPolicy)
        assertNull(defaults.opponentRolloutPolicy)

        val rootOnly = control.copy(rootRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.UNIFORM).policy(71)
        assertEquals("uniform-v1", rootOnly.effectiveRootRolloutPolicy().id)
        assertNull(rootOnly.opponentRolloutPolicy)
        assertEquals(defaults.effectiveOpponentRolloutPolicy().id, rootOnly.effectiveOpponentRolloutPolicy().id)

        val independent = control.copy(
            rootRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.UNIFORM,
            opponentRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.SEMANTIC_HEURISTIC,
        ).policy(71)
        assertEquals("uniform-v1", independent.effectiveRootRolloutPolicy().id)
        assertEquals("semantic-argentum-heuristic-v2", independent.effectiveOpponentRolloutPolicy().id)
        assertEquals(control.parameters(71), independent.parameters)
    }

    @Test
    fun `invalid stopped leg excludes whole pair from strength but retains attempt counts`() {
        val valid = game("valid", true)
        val stopped = game("stopped", false)
        val pair = searchBudgetFrontierPair(0, plan.pairSeed(0), listOf(valid, stopped), candidate.id)
        val comparison = calibrationComparison(plan, candidate, listOf(pair))
        assertEquals(0, comparison.validGames)
        assertEquals(1, comparison.invalidPairs)
        assertNull(comparison.candidatePointRate)
        assertNull(comparison.pairedBootstrap95Lower)
        assertNull(pair.treatmentPoints)
        assertEquals(2, comparison.operationalByPolicy.first().search.games)
        assertEquals(1, comparison.operationalByPolicy.first().search.invalidGames)
    }

    @Test
    fun `corrupt existing checkpoint fails closed and cli requires explicit plan output deck`() {
        val directory = Files.createTempDirectory("search-calibration-checkpoint-")
        try {
            val checkpoint = directory.resolve("checkpoint.json")
            assertNull(loadSearchTeacherCalibrationCheckpoint(checkpoint, directory, "identity", 0, 0,
                plan.pairSeed(0), control.id, candidate.id, "game"))
            Files.writeString(checkpoint, "corrupt")
            assertFails { loadSearchTeacherCalibrationCheckpoint(checkpoint, directory, "identity", 0, 0,
                plan.pairSeed(0), control.id, candidate.id, "game") }
        } finally {
            Files.deleteIfExists(directory.resolve("checkpoint.json"))
            Files.delete(directory)
        }
        listOf("search-teacher-calibration", "search-teacher-sequential", "search-teacher-continuation", "search-teacher-continuation-preflight", "real-game-position-bank", "position-bank-screen").forEach { suite ->
            assertFails { SearchTeacherCli.parse(arrayOf("--suite", suite)) }
            assertEquals(suite, SearchTeacherCli.parse(arrayOf("--suite", suite, "--profile", "/tmp/plan.json",
                "--output", "/tmp/output", "--deck-manifest", "/tmp/deck.json")).suite)
        }
    }

    @Test
    fun `stopped game checkpoints retain emitted subset and reject changed artifacts`() {
        val directory = Files.createTempDirectory("search-calibration-stopped-")
        val stopped = game("stopped", false)
        try {
            assertEquals(emptyMap(), calibrationArtifactHashes(directory, stopped))
            assertFails { calibrationArtifactHashes(directory, game("valid", true)) }
            val replay = directory.resolve("replays/stopped.privileged.replay.jsonl.gz")
            Files.createDirectories(replay.parent)
            Files.writeString(replay, "synthetic stopped replay")
            val hashes = calibrationArtifactHashes(directory, stopped)
            assertEquals(setOf("replays/stopped.privileged.replay.jsonl.gz"), hashes.keys)
            val checkpoint = SearchTeacherCalibrationCheckpoint(0, 0, stopped, hashes)
            val checkpointPath = directory.resolve("checkpoint.json")
            ResearchRunCheckpoints.persist(checkpointPath, "identity", "search-teacher-calibration-game-v1", 0,
                evidenceJson.encodeToString(checkpoint).encodeToByteArray())
            assertEquals(stopped, loadSearchTeacherCalibrationCheckpoint(checkpointPath, directory, "identity", 0, 0,
                plan.pairSeed(0), control.id, candidate.id, stopped.gameId))
            Files.writeString(replay, "changed")
            assertFails { loadSearchTeacherCalibrationCheckpoint(checkpointPath, directory, "identity", 0, 0,
                plan.pairSeed(0), control.id, candidate.id, stopped.gameId) }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `rollout blend must be explicit in the plan`() {
        val encoded = evidenceJson.encodeToString(control)
        assertEquals(control, evidenceJson.decodeFromString<SearchTeacherCalibrationPolicy>(encoded))
        val withoutBlend = evidenceJson.parseToJsonElement(encoded) as kotlinx.serialization.json.JsonObject
        assertFails { evidenceJson.decodeFromString<SearchTeacherCalibrationPolicy>(
            kotlinx.serialization.json.JsonObject(withoutBlend - "rolloutHeuristicProbability").toString()) }
    }

    @Test
    fun `absent evaluator and sequential rule preserve historical descriptor bytes and material keys`() {
        val historical = """
            {
                "id": "control",
                "particles": 8,
                "simulations": 64,
                "maxPolicyDecisions": 32,
                "explorationConstant": 1.4,
                "singletonSelection": false,
                "rolloutHeuristicProbability": 1.0
            }
        """.trimIndent()
        assertEquals(historical, evidenceJson.encodeToString(control))
        assertEquals(control, evidenceJson.decodeFromString<SearchTeacherCalibrationPolicy>(historical))
        val bindings = searchTeacherCalibrationBindings(plan, source, mapOf("control" to "c"), "deck", "pool", 1)
        assertEquals(setOf("plan", "source-provenance", "policy-evidence", "deck", "card-pool", "schedule", "worker-threads"), bindings.material.keys)
        val rule = PairedSequentialRule(nullPointRate = .5, targetPointRate = .6,
            falsePositiveRate = .05, falseNegativeRate = .05, maximumPairs = plan.pairCount)
        assertNotEquals(bindings.identity, searchTeacherCalibrationBindings(plan, source,
            mapOf("control" to "c"), "deck", "pool", 1, rule).identity)
        val encodedRule = evidenceJson.encodeToString(rule)
        assertFalse("stopForFutility" in encodedRule)
        assertEquals(rule, evidenceJson.decodeFromString<PairedSequentialRule>(encodedRule))
        val futileRule = rule.copy(stopForFutility = true)
        assertEquals(futileRule, evidenceJson.decodeFromString<PairedSequentialRule>(evidenceJson.encodeToString(futileRule)))
        assertNotEquals(searchTeacherCalibrationBindings(plan, source,
            mapOf("control" to "c"), "deck", "pool", 1, rule).identity,
            searchTeacherCalibrationBindings(plan, source,
                mapOf("control" to "c"), "deck", "pool", 1, futileRule).identity)
        assertFails { SearchTeacherSequentialPlan(plan.copy(candidates = listOf(candidate, candidate.copy(id = "other"))), rule) }
        assertFails { SearchTeacherSequentialPlan(plan, rule.copy(maximumPairs = plan.pairCount + 1)) }
    }

    @Test
    fun `configured visible evaluator preserves bounded rollout and binds every coefficient`() {
        val manifest = SearchTeacherDeckManifest("calibration-evaluator-test", "Synthetic", "synthetic", "2026-09-06",
            "public synthetic fixture", mapOf("Mountain" to 60), emptyMap())
        val arena = SearchTeacherArena(buildRegistry(), manifest, calibrationPresentationProfile(source), plan.baseSeed)
        val configured = control.copy(evaluator = MonoRedVisibleEvaluatorConfig()).policy(plan.baseSeed)
        val changed = control.copy(evaluator = MonoRedVisibleEvaluatorConfig(life = .2)).policy(plan.baseSeed)
        assertEquals(LeafStateSource.BOUNDED_ROLLOUT, configured.effectiveParameters(plan.baseSeed).leaf.stateSource)
        assertEquals(control.parameters(plan.baseSeed), configured.effectiveParameters(plan.baseSeed))
        assertNotEquals(arena.evidenceBinding(control.policy(plan.baseSeed), null, source).identity,
            arena.evidenceBinding(configured, null, source).identity)
        assertNotEquals(arena.evidenceBinding(configured, null, source).identity,
            arena.evidenceBinding(changed, null, source).identity)
    }

    @Test
    fun `preflight accepts losing valid games and rejects stopped or missing legs`() {
        val smoke = plan.copy(phase = SearchTeacherCalibrationPhase.PREFLIGHT, pairCount = 1)
        val loss0 = game("loss0", true).copy(winner = "p0")
        val loss1 = game("loss1", true).copy(p0PolicyId = candidate.id, p1PolicyId = control.id, winner = "p1")
        fun report(games: List<GameRunResult>): SearchTeacherCalibrationReport {
            val comparison = calibrationComparison(smoke, candidate,
                listOf(searchBudgetFrontierPair(0, smoke.pairSeed(0), games, candidate.id)))
            return SearchTeacherCalibrationReport(runIdentity = "synthetic", generatedAtUtc = "synthetic",
                sourceProvenance = source, deckHash = "deck", cardPoolHash = "pool", plan = smoke,
                workerThreads = 1, currentAttemptElapsedMillis = 1.0, policies = emptyList(),
                comparisons = listOf(comparison), valid = comparison.validPairs == 1)
        }
        val losses = report(listOf(loss0, loss1))
        assertEquals(0.0, losses.comparisons.single().candidatePointRate)
        requireGameplayPreflightComplete(losses, smoke)
        assertFails { requireGameplayPreflightComplete(report(listOf(loss0, game("stopped", false))), smoke) }
        assertFails { requireGameplayPreflightComplete(report(listOf(loss0)), smoke) }
    }

    private fun game(id: String, terminal: Boolean) = GameRunResult(gameId = id, seed = plan.pairSeed(0),
        p0Policy = ArenaPolicyKind.SEARCH, p1Policy = ArenaPolicyKind.SEARCH,
        winner = if (terminal) "p1" else null, terminal = terminal,
        disposition = if (terminal) GameRunDisposition.GAME_ENDED else GameRunDisposition.STOPPED_LIMIT,
        decisions = 1, searchSeat = null, searchScore = null, illegalResponses = 0, fallbacks = 0,
        stepLimit = !terminal, replayVerified = true, p0PolicyId = control.id, p1PolicyId = candidate.id)
}
