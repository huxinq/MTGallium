package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.infoset.core.PolicyTrajectoryDecision
import org.mtgallium.agent.infoset.core.PolicyTrajectoryForcedTransition
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryOutcome
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord
import org.mtgallium.agent.infoset.core.PolicyTrajectoryStopReason
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.searchteacher.PolicySingletonSelectionConfig
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherRuntimeConfig
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

@Tag("public-source")
class SingletonSelectionTest {
    private val deck = SearchTeacherDeckManifest(
        "singleton-test", "Singleton fixture", "synthetic", "2026-09-05", "public synthetic fixture",
        mapOf("Mountain" to 60), emptyMap(),
    )
    private val parameters = SearchTeacherRuntimeConfig(
        leaf = LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.ARGENTUM_BOARD_V1),
    ).policyParameters().copy(particles = 1, simulations = 1, maxPolicyDecisions = 1)
    private val enabled = parameters.copy(singletonSelection = PolicySingletonSelectionConfig(enabled = true))

    @Test
    fun `singleton selection preserves the live state and advances belief on explicit acceptance`() {
        val environment = GameEnvironment.create(buildRegistry()).also {
            it.reset(GameConfig(
                players = listOf(PlayerConfig("p0", deck.deck()), PlayerConfig("p1", deck.deck())),
                skipMulligans = true, useHandSmoother = false, startingPlayerIndex = 0, seed = 19L,
            ))
        }
        val knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck)
        val world = ArgentumSearchWorld.create(
            environment, "singleton-explicit-step", seedBase = 19L, effectiveSetupSeed = 19L,
            expander = UnifiedSemanticExpander(actionSpaceProfile = parameters.actionSpaceProfile),
            knownDecks = knownDecks,
        )
        var decisionIndex = 0
        while (true) {
            val expansion = world.expandChoices()
            if (!expansion.isExhaustive && expansion.isProfileExhaustive && expansion.candidates.size == 1) break
            check(decisionIndex < 20) { "Fixture did not reach a profile singleton" }
            val choice = expansion.candidates.firstOrNull { it.operationFamily == SemanticOperationFamily.PLAY_LAND }
                ?: expansion.candidates.first { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
            assertTrue(world.step(choice).accepted)
            decisionIndex++
        }
        val actor = requireNotNull(world.actorToAct())
        fun session(singleton: Boolean) = SearchTeacherPolicySession(
            root = world, viewer = actor, knownDecks = knownDecks,
            parameters = if (singleton) enabled else parameters,
            opponentPolicy = defaultMonoRedOpponentPolicy(), gameId = "singleton-explicit-step",
        )
        val searched = session(false)
        val selected = session(true)
        val fingerprint = world.freshAuthoritativeFingerprintForHost()
        val information = world.informationState(actor)
        val beliefBefore = selected.latestBeliefDiagnostics
        val control = searched.select(world, actor, 27L)
        val treatment = selected.select(world, actor, 27L)
        assertEquals(SearchTeacherSelectionKind.SEARCHED, control.kind)
        assertNotNull(control.search)
        assertEquals(control.choice, treatment.choice)
        assertEquals(SearchTeacherSelectionKind.POLICY_SINGLETON_ACTION, treatment.kind)
        assertNull(treatment.search)
        assertEquals(beliefBefore, selected.latestBeliefDiagnostics)
        assertEquals(fingerprint, world.freshAuthoritativeFingerprintForHost())
        assertEquals(information, world.informationState(actor))

        val beforeUpdates = selected.beliefLifecycleDiagnostics.sequentialUpdateAttempts
        val step = world.step(treatment.choice)
        assertTrue(step.accepted)
        for (policy in listOf(searched, selected)) {
            policy.observeAccepted(world, actor, treatment.choice, decisionIndex, step.privateToActor)
        }
        assertEquals(beforeUpdates + 1, selected.beliefLifecycleDiagnostics.sequentialUpdateAttempts)
        assertTrue(world.informationState(actor).history.size > information.history.size)
        val expected = searched.beliefBatch(world)
        val actual = selected.beliefBatch(world)
        assertEquals(expected.particles.map { it.weight }, actual.particles.map { it.weight })
        assertEquals(
            expected.particles.map { it.value.informationState(actor) },
            actual.particles.map { it.value.informationState(actor) },
        )
    }

    @Test
    fun `arena records accepted singleton decisions without search labels and binds the decision limit`() {
        val root = createTempDirectory("singleton-evidence-")
        val trajectory = root.resolve("trace.jsonl.gz")
        val empty = PolicyJson.sha256("")
        val source = PolicySourceProvenance(
            expectedArgentumRevision = "test-engine",
            outer = PolicySourceTreeState("test-outer", empty, empty, empty),
            argentum = PolicySourceTreeState("test-engine", empty, empty, empty),
        )
        val profile = FrozenSearchProfile(
            id = "fast-arena-v1", generatedAtUtc = "test", outerCommit = "test-outer",
            argentumCommit = "test-engine", host = "test", particles = 8, simulations = 64,
            leaf = enabled.leaf, actionSpaceProfile = enabled.actionSpaceProfile,
            maxPolicyDecisions = 1, measuredP95Millis = 0.0, tacticalScore = 0.0,
            standardError = 0.0, calibrationReportHash = "synthetic-test",
        )
        val policy = ArenaPolicySpec("singleton-test", ArenaPolicyKind.SEARCH, parameters = enabled)
        val arena = SearchTeacherArena(buildRegistry(), deck, profile, 19L, gameDecisionLimit = 24)
        val accepted = mutableListOf<Int>()
        val result = arena.playWithPolicies(
            "00000000-0000-4000-8000-000000009019", 19L, policy, policy,
            evidence = GameEvidenceOptions(
                publicTrajectory = trajectory, publicTrajectoryPerspective = "p0",
                sourceProvenance = source, outerCommit = "test-outer", argentumCommit = "test-engine",
            ),
            maxSearchDecisions = 24,
            acceptedStepProbe = { _, _, index, _, step -> assertTrue(step.accepted); accepted += index },
        )
        assertEquals(GameRunDisposition.STOPPED_LIMIT, result.disposition, result.exception)
        assertEquals((0 until 24).toList(), accepted)
        assertEquals(24, result.decisions)
        assertFalse(result.terminal)
        assertNull(result.searchScore)
        assertTrue(result.searchLatenciesMillis.size < 24)
        assertTrue(result.seatDiagnostics.values.sumOf {
            it.selectionCounts[SearchTeacherSelectionKind.POLICY_SINGLETON_ACTION] ?: 0
        } > 0)
        val records = GZIPInputStream(Files.newInputStream(trajectory)).bufferedReader().useLines { lines ->
            lines.map { PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(it) }.toList()
        }
        val header = records.first() as PolicyTrajectoryHeader
        assertEquals(arena.evidenceBinding(policy, 24, source), header.behaviorBinding)
        assertNotEquals(
            arena.evidenceBinding(policy, 24, source).identity,
            arena.evidenceBinding(policy.copy(parameters = parameters), 24, source).identity,
        )
        assertNotEquals(
            arena.evidenceBinding(policy, 24, source).identity,
            SearchTeacherArena(buildRegistry(), deck, profile, 19L, gameDecisionLimit = 25)
                .evidenceBinding(policy, 24, source).identity,
        )
        val searched = records.filterIsInstance<PolicyTrajectoryDecision>()
        assertEquals(result.seatDiagnostics.getValue("p0").searchDecisions, searched.size)
        assertEquals(
            (0 until 24).toList(),
            records.filterIsInstance<PolicyTrajectoryForcedTransition>().map { it.afterDecisionIndex },
        )
        assertEquals(PolicyTrajectoryStopReason.GAME_DECISION_LIMIT_REACHED,
            (records.last() as PolicyTrajectoryOutcome).stopReason)
    }
}
