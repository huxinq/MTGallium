package org.mtgallium.research.workbench

import com.wingedsheep.engine.state.components.identity.CardComponent
import kotlin.test.*
import org.mtgallium.agent.argentum.policy.*
import org.mtgallium.agent.infoset.core.*

/** Deliberately unsafe controls, never registered on the production classpath. */
class LeakingConformancePolicies : JvmPolicyProvider {
    override val policies = setOf("test-hidden-hand", "test-privileged-search")

    override fun create(name: String, game: NativePolicyContext, actor: String): JvmPolicy {
        if (name == "test-hidden-hand") return JvmPolicy.Memoryless(GameAgent { request, _ ->
            val state = game.world.trueState()
            val opponent = state.turnOrder[1 - actor.removePrefix("p").toInt()]
            val truth = state.getHand(opponent).map { state.getEntity(it)!!.get<CardComponent>()!!.name }
            request.menu.candidates[Math.floorMod(truth.hashCode(), request.menu.candidates.size)]
        })
        return JvmPolicy.SearchSession(SearchPolicySession(game.world, actor, game.knownDecks,
            SearchPolicyConfig(2, 8, 16, 1.4, game.plan.leaf, game.plan.actionProfile,
                baseSeed = game.plan.seed, beliefArchitecture = BeliefApproximation.PRIVILEGED_O_V1),
            game.opponentModel(), game.gameId))
    }
}

class HiddenInformationConformanceTest {
    private val plan = HiddenInformationCheckPlan(
        ResearchGameConfig(decks = List(2) { mapOf("Mountain" to 12, "Shock" to 12, "Raging Goblin" to 12) },
            policies = listOf("heuristic", "production"), particles = 2, simulations = 4, searchDepth = 2),
        seeds = listOf(101, 102), positionsPerCategory = 1, maximumCorpusDecisions = 80, permutations = 3)
    private val corpus by lazy { hiddenInformationCorpus(plan) }

    @Test fun `direct builtins are invariant and corpus is reproducible for both seats`() {
        val reports = checkHiddenInformation(plan.copy(policies = listOf("random", "heuristic", "production")), corpus)
        reports.forEach {
            assertTrue(it.passed, "${it.policy}: ${it.findings.take(2)}")
            assertEquals(corpus.size * plan.permutations, it.permutationsAccepted + it.permutationsRejected)
            assertTrue(it.permutationsAccepted > 0)
        }
        assertEquals(setOf("p0", "p1"), corpus.map { it.actor }.toSet())
        fun keys(rows: List<HiddenInformationPosition>) = rows.map {
            listOf(it.seed, it.decisionIndex, it.actor, it.category, it.world.informationState(it.actor).informationStateDigest)
        }
        assertEquals(keys(corpus), keys(hiddenInformationCorpus(plan)))
    }

    @Test fun `hidden hand and privileged search controls are detected`() {
        val reports = checkHiddenInformation(plan.copy(policies = listOf("test-hidden-hand", "test-privileged-search")),
            corpus, listOf(LeakingConformancePolicies()))
        reports.forEach { report ->
            assertTrue(report.findings.none { it.level == "ERROR" || it.level == "NONDETERMINISTIC" }, report.findings.toString())
            assertTrue(report.findings.any { it.level in setOf("DECISION_DIFFERS", "STATISTICS_DIFFER") }, report.toString())
        }
        assertTrue(reports.first().findings.any { it.level == "DECISION_DIFFERS" })
    }

    @Test fun `complex printed abilities remain coherent after permutation`() {
        val complex = plan.copy(game = plan.game.copy(decks = List(2) {
            mapOf("Mountain" to 20, "Hired Claw" to 20, "Shock" to 20)
        }), seeds = listOf(101), maximumCorpusDecisions = 2,
            policies = listOf("heuristic", "production"))
        checkHiddenInformation(complex).forEach { assertTrue(it.passed, it.toString()) }
    }

    @Test fun `empty coverage cannot pass`() {
        assertFalse(checkHiddenInformation(plan.copy(policies = listOf("random")), emptyList()).single().passed)
    }
}
