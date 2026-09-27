package org.mtgallium.agent.argentum.policy

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import org.mtgallium.agent.infoset.argentum.ArgentumBeliefProposalAuditSink
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pins the retained snapshot backend's rebuild and publication lifecycle. */
class SnapshotABeliefLifecycleTest {
    @Test
    fun `accepted action rebuilds snapshot A and invalidates the published snapshot`() {
        val deck = mapOf("Mountain" to 20)
        val decks = mapOf("p0" to deck, "p1" to deck)
        val registry = CardRegistry().apply {
            register(CardDefinition(name = "Mountain", manaCost = ManaCost.parse("{0}"),
                typeLine = TypeLine.parse("Basic Land — Mountain")))
        }
        val environment = GameEnvironment.create(registry)
        val cards = Deck.of("Mountain" to 20)
        environment.reset(GameConfig(
            players = listOf(PlayerConfig("Alice", cards), PlayerConfig("Bob", cards)),
            seed = 44117L,
            startingPlayerIndex = 0,
            skipMulligans = true,
            useHandSmoother = false,
        ))
        val world = ArgentumSearchWorld.create(environment, "snapshot-a-lifecycle", 44117L, 44117L,
            knownDecks = decks)
        val backend = ArgentumParticleBeliefBackend(
            world, "p0", decks,
            BeliefConfig(4, BeliefMode.CONSISTENCY_ONLY_V1, BeliefArchitecture.SNAPSHOT_A_V1),
            UniformOpponentPolicy, "snapshot-a-lifecycle", ArgentumBeliefProposalAuditSink.NONE,
        )
        val before = backend.snapshot()
        assertEquals(1, backend.lifecycleDiagnostics.initialConstructionCompletions)
        assertEquals(0, backend.lifecycleDiagnostics.rebuildCompletions)

        val actor = requireNotNull(world.actorToAct())
        val priorityPlayer = requireNotNull(world.authoritativeStateForHost().priorityPlayerId)
        val observed = world.applyObservedAction(PassPriority(priorityPlayer))
        assertTrue(observed.result.accepted)
        backend.advance(world, actor, observed.choice, 0, observed.result.privateToActor)

        val after = backend.snapshot()
        assertNotSame(before, after)
        assertNotSame(before.queries.binding.snapshotToken, after.queries.binding.snapshotToken)
        assertEquals(1, backend.lifecycleDiagnostics.rebuildAttempts)
        assertEquals(1, backend.lifecycleDiagnostics.rebuildCompletions)
        assertEquals(0, backend.lifecycleDiagnostics.sequentialUpdateAttempts)
        assertEquals(1L, backend.continuityEpoch)
        assertEquals(BeliefArchitecture.SNAPSHOT_A_V1, backend.latestDiagnostics.architecture)
        assertEquals(1, backend.latestDiagnostics.resamplingCount)
        assertEquals(world.informationState("p0").knowledge.knowledgeDigest,
            backend.latestDiagnostics.knowledgeDigest)
        assertSame(after, backend.snapshot())
    }
}
