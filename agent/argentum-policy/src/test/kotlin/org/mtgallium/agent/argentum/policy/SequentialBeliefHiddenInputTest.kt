package org.mtgallium.agent.argentum.policy

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.GameRng
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*
import kotlin.test.*

/**
 * Characterizes a known limit, NOT full U0 noninterference: a consistency-only rebuild copies
 * the factual non-observer ledger even when sampled states are identical. In this bounded
 * fixture historical observation hashes AND private hand-count transitions differ. Neither
 * is erased or passed through a normalized policy input. This is an executable counterexample
 * to full sampled-history independence, not a repaired sampler. No terminal values or learned
 * scores are acquired; matching bounded Production decisions does not prove U0 invariance.
 */
class SequentialBeliefHiddenInputTest {
    @Test fun `p0 preparation preserves sampled state but inherits factual opponent history`() = compareSeat(0)
    @Test fun `p1 preparation preserves sampled state but inherits factual opponent history`() = compareSeat(1)

    private fun compareSeat(seat: Int) {
        val viewer = "p$seat"
        val registry = CardRegistry().apply {
            register(CardDefinition(name = "Boundary Bear", manaCost = ManaCost.parse("{0}"),
                typeLine = TypeLine.parse("Creature — Bear"), creatureStats = CreatureStats(1, 1)))
            register(CardDefinition(name = "Boundary Bird", manaCost = ManaCost.parse("{0}"),
                typeLine = TypeLine.parse("Creature — Bird"), creatureStats = CreatureStats(2, 2)))
        }
        // The benchmark prior is supplied independently of either world's actual contents.
        val inventory = mapOf("Boundary Bear" to 20, "Boundary Bird" to 20)
        val decks = mapOf("p0" to inventory, "p1" to inventory)
        val deck = Deck.of(*inventory.entries.map { it.key to it.value }.toTypedArray())
        val original = GameEnvironment.create(registry).also { environment ->
            environment.reset(GameConfig(players = listOf(PlayerConfig("Alice", deck), PlayerConfig("Bob", deck)),
                seed = 42613L, startingHandSize = 3, startingPlayerIndex = seat, skipMulligans = true, useHandSmoother = false))
        }
        val opponent = original.playerIds[1 - seat]
        val hidden = original.state.getHand(opponent) + original.state.getLibrary(opponent)
        val first = hidden.first()
        val firstCard = original.state.getEntity(first)!!.require<CardComponent>()
        val other = hidden.last { original.state.getEntity(it)!!.require<CardComponent>().name != firstCard.name }
        val otherCard = original.state.getEntity(other)!!.require<CardComponent>()
        val changedState = original.state.updateEntity(first) { it.with(otherCard) }
            .updateEntity(other) { it.with(firstCard) }.copy(rng = GameRng.seeded(919191L))
        assertNotEquals(original.state, changedState, "Hidden perturbation must be non-vacuous")
        val altered = original.fork().also { it.restore(changedState, original.playerIds, original.stepCount) }
        fun wrap(environment: GameEnvironment) = ArgentumSearchWorld.create(environment,
            "independent-selection-coordinate", 71L, 42613L, knownDecks = decks)
        val left = wrap(original)
        val right = wrap(altered)
        fun prepare(world: ArgentumSearchWorld) = BeliefPreparation(world, viewer, decks,
            BeliefConfig(8, BeliefMode.CONSISTENCY_ONLY_V1, BeliefArchitecture.SEQUENTIAL_B_V1,
                ObservedBeliefConditioning.HISTORICAL_GROUP_SIGNATURE_V1),
            defaultMonoRedOpponentPolicy(), "declared-u0-coordinate-not-source-rng")
        assertEquals(left.informationState(viewer), right.informationState(viewer))
        val leftBelief = prepare(left)
        val rightBelief = prepare(right)
        var inheritedHistoryDifferences = 0
        val production = PolicyDefaults.rootRolloutPolicy()
        fun compareAt(cursor: Int) {
            assertEquals(left.informationState(viewer), right.informationState(viewer), "Legitimate prefix $viewer/$cursor")
            val a = leftBelief.beliefBatch(left)
            val b = rightBelief.beliefBatch(right)
            assertEquals(a.particles.map { it.weight }, b.particles.map { it.weight }, "Weights $viewer/$cursor")
            for (index in a.particles.indices) {
                val x = a.particles[index].value as ArgentumSearchWorld
                val y = b.particles[index].value as ArgentumSearchWorld
                assertEquals(x.authoritativeStateForHost(), y.authoritativeStateForHost(), "Hypothesis state $viewer/$cursor/$index")
                for (player in listOf("p0", "p1")) {
                    val xi = x.informationState(player)
                    val yi = y.informationState(player)
                    val coordinate = "$viewer/$cursor/$index/$player"
                    assertEquals(xi.observation, yi.observation, "Observation $coordinate")
                    assertEquals(xi.knowledge, yi.knowledge, "Knowledge $coordinate")
                    assertTrue(xi.knowledge.epistemicallyComplete)
                    assertEquals(xi.candidates, yi.candidates, "Candidates $coordinate")
                    assertEquals(PolicyHistoryCommitment.replay(xi.history), xi.historyCommitment)
                    assertEquals(PolicyHistoryCommitment.replay(yi.history), yi.historyCommitment)
                    if (xi == yi) continue
                    // Diagnose, rather than discard, the failed full-information invariant.
                    assertNotEquals(viewer, player, "Observer information must remain exactly equal")
                    assertTrue(a.diagnostics.resamplingCount > 0 && b.diagnostics.resamplingCount > 0)
                    assertEquals(xi.history.size, yi.history.size)
                    val differing = xi.history.indices.filter { xi.history[it] != yi.history[it] }
                    assertTrue(differing.isNotEmpty(), coordinate)
                    assertNotEquals(xi.historyCommitment, yi.historyCommitment)
                    assertNotEquals(xi.informationStateDigest, yi.informationStateDigest)
                    assertEquals(left.informationState(player).history, xi.history, "Copied factual ledger $coordinate")
                    assertEquals(right.informationState(player).history, yi.history, "Copied factual ledger $coordinate")
                    assertEquals(32, cursor, "First affected boundary in this fixed fixture")
                    assertEquals(4, differing.first(), "First differing observation-hash event")
                    val xe = xi.history[54]
                    val ye = yi.history[54]
                    assertEquals(PolicyHistoryEventKind.PUBLIC_ZONE_TRANSITION, xe.kind)
                    assertEquals(PolicyAudience(PolicyAudienceScope.ENTITLED_PLAYERS, setOf(player)), xe.audience)
                    assertNotNull(xe.payload["zoneDelta"])
                    assertNotNull(ye.payload["zoneDelta"])
                    assertNotEquals(xe.payload["zoneDelta"], ye.payload["zoneDelta"],
                        "The copied private hand-count transition differs, not merely an opaque hash")
                    inheritedHistoryDifferences++
                }
                if (cursor == 32) {
                    // Production receives original histories, including the counterexample.
                    // Eight decisions cover both actors but are not a U0 terminal comparison.
                    val nextX = x.fork() as ArgentumSearchWorld
                    val nextY = y.fork() as ArgentumSearchWorld
                    val actors = mutableSetOf<String>()
                    repeat(8) { decision ->
                        val xc = nextX.decisionContext(production.decisionView(64))
                        val yc = nextY.decisionContext(production.decisionView(64))
                        actors += xc.actor
                        assertEquals(xc.actor, yc.actor)
                        assertEquals(xc.expansion, yc.expansion)
                        val policySeed = ComponentSeeds.derive("independent-continuation", decision, "policy")
                        val sampleSeed = ComponentSeeds.derive("independent-continuation", decision, "sample")
                        val xd = production.select(xc, policySeed, sampleSeed)
                        val yd = production.select(yc, policySeed, sampleSeed)
                        assertEquals(xd, yd)
                        assertTrue(xd.diagnostic.replacement?.invalidatesEvidence != true)
                        assertTrue(nextX.step(xd.choice).accepted && nextY.step(yd.choice).accepted)
                        assertNull(nextX.terminalPayoff(viewer))
                        assertNull(nextY.terminalPayoff(viewer))
                        assertEquals(nextX.authoritativeStateForHost(), nextY.authoritativeStateForHost())
                    }
                    assertEquals(setOf("p0", "p1"), actors)
                }
            }
        }
        compareAt(0)
        // Preserve the actual observation route, including automatic changes and private draws.
        // These nonterminal passes exercise sequential updates, not fixed retained U0 means.
        repeat(32) { index ->
            assertFalse(left.epistemicState(viewer).terminated)
            val actor = assertNotNull(left.actorToAct())
            assertEquals(actor, right.actorToAct())
            val menu = left.expandChoices().candidates
            val choice = menu.firstOrNull { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
                ?: assertNotNull(menu.singleOrNull(), "Only an empty-board rules-forced choice may replace a pass: $viewer/$index")
            val counterpart = right.expandChoices().candidates.single { it.signature == choice.signature }
            val a = left.step(choice)
            val b = right.step(counterpart)
            assertTrue(a.accepted && b.accepted)
            assertEquals(a.privateToActor, b.privateToActor)
            leftBelief.observeAccepted(left, actor, choice, index, a.privateToActor)
            rightBelief.observeAccepted(right, actor, counterpart, index, b.privateToActor)
            compareAt(index + 1)
        }
        assertTrue(left.informationState(viewer).history.isNotEmpty())
        assertTrue(inheritedHistoryDifferences > 0, "This fixture must expose, not hide, the ledger dependency")
    }
}
