package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.sth.StrongholdSet
import com.wingedsheep.sdk.model.Deck
import kotlin.test.*
import org.mtgallium.agent.infoset.core.*

class OpponentHandBeliefQueriesTest {
    @Test fun `particle backend queries and generated hypotheses retain exactly the same frozen population`() {
        val (root, batch) = nativeFixture()
        val belief = ParticleBelief.from(batch, BeliefMode.POLICY_CONDITIONED_V1)
        val information = root.informationState("p1")
        val before = belief.weightedWorlds().map { (it.value as ArgentumSearchWorld).freshAuthoritativeFingerprintForHost() }
        val weights = belief.weightedWorlds().map { it.weight }
        val snapshot = ArgentumParticleBeliefSnapshot.capture(belief, information, batch.diagnostics, "test-inference")
        val generated = snapshot.hypotheses.materialize()
        snapshot.queries.requireSameSnapshot(generated)
        assertEquals(weights, generated.batch.particles.map { it.weight })
        assertEquals(before, generated.batch.particles.map { (it.value as ArgentumSearchWorld).freshAuthoritativeFingerprintForHost() })
        assertEquals(legacyMarginals(belief, "p1"), snapshot.queries.opponentHand.presenceMarginals())
        assertEquals(EpistemicState.capture(information).epistemicDigest, snapshot.queries.binding.epistemicDigest)
        var shockMass = 0.0
        generated.batch.particles.forEach { weighted ->
            val world = weighted.value as ArgentumSearchWorld
            val opponent = world.rawPlayerIds().getValue("p0")
            if (world.authoritativeState().getHand(opponent).any { world.authoritativeState().getEntity(it)?.get<CardComponent>()?.name == "Shock" }) shockMass += weighted.weight
        }
        assertEquals(shockMass, snapshot.queries.opponentHand.probabilityAtLeast("Shock"))
        val mutated = generated.batch.particles.first().value
        val pass = mutated.expandChoices().candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
        assertTrue(mutated.step(pass).accepted)
        assertEquals(before, snapshot.hypotheses.materialize().batch.particles.map { (it.value as ArgentumSearchWorld).freshAuthoritativeFingerprintForHost() })
        assertEquals(before, belief.weightedWorlds().map { (it.value as ArgentumSearchWorld).freshAuthoritativeFingerprintForHost() })
        val another = ArgentumParticleBeliefSnapshot.capture(belief, information, batch.diagnostics, "test-inference")
        assertFailsWith<IllegalArgumentException> { snapshot.queries.requireSameSnapshot(another.hypotheses.materialize()) }
        assertEquals(shockMass, snapshot.queries.opponentHand.probabilityAtLeast("Shock"))
    }

    @Test
    fun `identical marginals retain different correlations and overlapping unions`() {
        val separate = snapshot(.5 to mapOf("A" to 1), .5 to mapOf("B" to 1))
        val together = snapshot(.5 to mapOf("A" to 1, "B" to 1), .5 to emptyMap())
        assertEquals(mapOf("A" to .5, "B" to .5), separate.presenceMarginals())
        assertEquals(separate.presenceMarginals(), together.presenceMarginals())
        assertEquals(0.0, separate.probabilityAllOf(mapOf("A" to 1, "B" to 1)))
        assertEquals(.5, together.probabilityAllOf(mapOf("A" to 1, "B" to 1)))
        assertEquals(1.0, separate.probabilityAnyOf(setOf("A", "B")))
        assertEquals(.5, together.probabilityAnyOf(setOf("A", "B")))
    }

    @Test
    fun `copy thresholds expected counts and empty predicates have explicit meanings`() {
        val query = snapshot(.25 to mapOf("A" to 2, "B" to 1), .75 to mapOf("A" to 1))
        assertEquals("p0", query.viewerAlias)
        assertEquals(1.0, query.probabilityAtLeast("A"))
        assertEquals(.25, query.probabilityAtLeast("A", 2))
        assertEquals(0.0, query.probabilityAtLeast("A", 3))
        assertEquals(1.25, query.expectedCopies("A"))
        assertEquals(.25, query.expectedCopies("B"))
        assertEquals(.25, query.probabilityAllOf(mapOf("A" to 2, "B" to 1)))
        assertEquals(0.0, query.probabilityAllOf(mapOf("A" to 2, "B" to 2)))
        assertEquals(0.0, query.probabilityAtLeast("Unknown"))
        assertEquals(0.0, query.expectedCopies("Unknown"))
        assertEquals(1.0, query.probabilityAtLeast("Unknown", 0))
        assertEquals(1.0, query.probabilityAllOf(emptyMap()))
        assertEquals(0.0, query.probabilityAnyOf(emptySet()))
        assertEquals(.25, query.probabilityAllOf(mapOf("B" to 1, "Unknown" to 0)))
    }

    @Test
    fun `snapshot is detached from mutable inputs and exposes immutable sorted marginals`() {
        val counts = linkedMapOf("B" to 2, "A" to 1)
        val hands = mutableListOf(Weighted<Map<String, Int>>(counts, .25), Weighted<Map<String, Int>>(emptyMap(), .75))
        val query = opponentHandQuerySnapshot("p0", hands)
        val before = query.presenceMarginals().toMap()
        counts.clear()
        hands.clear()
        assertEquals(before, query.presenceMarginals())
        assertEquals(listOf("A", "B"), query.presenceMarginals().keys.toList())
        assertEquals(.25, query.probabilityAllOf(mapOf("A" to 1, "B" to 2)))
        assertEquals(.5, query.expectedCopies("B"))
        val exposed = query.presenceMarginals() as MutableMap<String, Double>
        assertFailsWith<UnsupportedOperationException> { exposed["A"] = 1.0 }
        assertFailsWith<UnsupportedOperationException> { exposed.clear() }
        assertFailsWith<UnsupportedOperationException> { exposed.entries.first().setValue(.75) }
        assertEquals(before, query.presenceMarginals())
    }

    @Test
    fun `zero mass keys and original floating additions are preserved without normalization`() {
        val query = snapshot(.1 to mapOf("A" to 1), .2 to mapOf("A" to 1),
            .7 to mapOf("A" to 1), 0.0 to mapOf("Z" to 1), 0.0 to mapOf("Absent" to 0))
        var expected = 0.0
        for (weight in listOf(.1, .2, .7)) expected += weight
        assertEquals(mapOf("A" to expected, "Z" to 0.0), query.presenceMarginals())
        assertEquals(expected, query.probabilityAtLeast("A"))
        assertEquals(0.0, query.probabilityAtLeast("Z"))
        // The internal seam validates normalization without adjusting existing approximate weights.
        val nearOne = 1.0 - 1e-12
        val unadjusted = snapshot(nearOne to mapOf("A" to 1))
        assertEquals(nearOne, unadjusted.probabilityAtLeast("A"))
        assertEquals(nearOne, unadjusted.probabilityAllOf(emptyMap()))
    }

    @Test
    fun `invalid query declarations and invalid populations refuse`() {
        assertFailsWith<IllegalArgumentException> { opponentHandQuerySnapshot("p0", emptyList()) }
        assertFailsWith<IllegalArgumentException> { opponentHandQuerySnapshot(" ", listOf(Weighted(emptyMap(), 1.0))) }
        for (weights in listOf(listOf(0.0), listOf(.2, .2), listOf(1.1))) {
            assertFailsWith<IllegalArgumentException> {
                opponentHandQuerySnapshot("p0", weights.map { Weighted(emptyMap(), it) })
            }
        }
        for (weight in listOf(-.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { snapshot(weight to emptyMap()) }
        }
        for (counts in listOf(mapOf("" to 1), mapOf(" " to 1), mapOf("A" to -1))) {
            assertFailsWith<IllegalArgumentException> { snapshot(1.0 to counts) }
        }
        val query = snapshot(1.0 to emptyMap())
        for (name in listOf("", " ")) {
            assertFailsWith<IllegalArgumentException> { query.probabilityAtLeast(name) }
            assertFailsWith<IllegalArgumentException> { query.probabilityAnyOf(setOf(name)) }
            assertFailsWith<IllegalArgumentException> { query.probabilityAllOf(mapOf(name to 0)) }
            assertFailsWith<IllegalArgumentException> { query.expectedCopies(name) }
        }
        assertFailsWith<IllegalArgumentException> { query.probabilityAtLeast("A", -1) }
        assertFailsWith<IllegalArgumentException> { query.probabilityAllOf(mapOf("A" to -1)) }
    }

    @Test
    fun `native snapshot preserves legacy marginals hidden state RNG and represented information`() {
        val (root, batch) = nativeFixture()
        val belief = ParticleBelief.from(batch, BeliefMode.POLICY_CONDITIONED_V1)
        val worlds = belief.weightedWorlds().map { it.value as ArgentumSearchWorld }
        val fingerprints = (listOf(root) + worlds).map { it.freshAuthoritativeFingerprintForHost() }
        val information = (listOf(root) + worlds).map { world -> listOf("p0", "p1").map(world::informationState) }
        val expected = legacyMarginals(belief, "p1")
        val query = ArgentumHandBeliefQueries.snapshot(belief, "p1")
        assertEquals(expected, query.presenceMarginals())
        assertEquals(expected, ArgentumParticleDiagnostics.opponentHandMarginals(belief, "p1"))
        expected.forEach { (name, p) -> assertEquals(p, query.probabilityAtLeast(name)) }
        assertEquals(1.0, query.probabilityAllOf(emptyMap()), 1e-12)
        query.probabilityAllOf(mapOf("Shock" to 1, "Mountain" to 2))
        query.probabilityAnyOf(setOf("Shock", "Mountain"))
        assertEquals(7.0, query.expectedCopies("Shock") + query.expectedCopies("Mountain"), 1e-12)
        assertEquals(fingerprints, (listOf(root) + worlds).map { it.freshAuthoritativeFingerprintForHost() })
        assertEquals(information, (listOf(root) + worlds).map { world -> listOf("p0", "p1").map(world::informationState) })
        assertTrue(belief.weightedWorlds().map { it.weight }.distinct().size > 1, "Use genuinely nonuniform native weights")

        assertFailsWith<IllegalArgumentException> { ArgentumHandBeliefQueries.snapshot(belief, "unknown") }
        val unsupported = object : SearchWorld by root {}
        val wrong = ParticleBelief.from(batch.copy(particles = listOf(Weighted<SearchWorld>(unsupported, 1.0))),
            BeliefMode.POLICY_CONDITIONED_V1)
        assertFailsWith<IllegalArgumentException> { ArgentumHandBeliefQueries.snapshot(wrong, "p1") }
    }

    @Test
    fun `native conditioning updates a fresh snapshot without changing the old query`() {
        val (root, batch) = nativeFixture()
        val belief = ParticleBelief.from(batch, BeliefMode.POLICY_CONDITIONED_V1)
        val original = ArgentumHandBeliefQueries.snapshot(belief, "p1")
        val originalMarginals = original.presenceMarginals().toMap()
        val prior = original.probabilityAtLeast("Shock")
        assertTrue(prior > 0.0 && prior < 1.0)
        val model = object : ActionDistributionModel {
            override val id = "synthetic-query-likelihood"
            override val requiresProductionAdmission = false
            override fun distribution(context: DecisionSiteRequest, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
        val opponentInformation = context.information()
        val candidates = context.expansion.candidates
                assertEquals("p0", opponentInformation.actingPlayerId)
                assertEquals("p0", opponentInformation.observation.perspectivePlayerId)
                val hasShock = opponentInformation.observation.zones
                    .filter { it.zone == "HAND" && it.ownerId == "p0" }.flatMap { it.cards }.any { it.name == "Shock" }
                val p = if (hasShock) .6 else .4
                val pass = candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
                val alternative = candidates.first { it != pass }
                return ProbabilityDistribution.normalized(candidates.map {
                    ProbabilityMass(it, when (it) { pass -> p; alternative -> 1.0 - p; else -> 0.0 })
                })
            }
        }
        val pass = root.expandChoices().candidates.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
        val updated = belief.advance("p0", pass.signature, model, 991L)
        val current = ArgentumHandBeliefQueries.snapshot(updated.belief, "p1")
        assertEquals(belief.resamplingCount, updated.belief.resamplingCount)
        assertEquals(prior * .6 / (prior * .6 + (1 - prior) * .4), current.probabilityAtLeast("Shock"), 1e-12)
        assertTrue(current.probabilityAtLeast("Shock") > prior)
        assertEquals(legacyMarginals(updated.belief, "p1"), current.presenceMarginals())
        assertEquals(prior, original.probabilityAtLeast("Shock"))
        assertEquals(originalMarginals, original.presenceMarginals())
    }

    private fun snapshot(vararg values: Pair<Double, Map<String, Int>>) =
        opponentHandQuerySnapshot("p0", values.map { (weight, counts) -> Weighted(counts, weight) })

    /** Prior production algorithm is a test reference, retaining its exact accumulation order. */
    private fun legacyMarginals(belief: ParticleBelief, viewerAlias: String): Map<String, Double> {
        val probabilities = mutableMapOf<String, Double>()
        belief.weightedWorlds().forEach { weighted ->
            val world = weighted.value as ArgentumSearchWorld
            val viewer = world.rawPlayerIds().getValue(viewerAlias)
            world.authoritativeState().turnOrder.filter { it != viewer }
                .flatMap { world.authoritativeState().getHand(it) }
                .mapNotNull { world.authoritativeState().getEntity(it)?.get<CardComponent>()?.name }
                .toSet().forEach { name -> probabilities[name] = probabilities.getOrDefault(name, 0.0) + weighted.weight }
        }
        return probabilities.toSortedMap()
    }

    /** Authored small engine fixture, with no historical games or claimed research population. */
    private fun nativeFixture(): Pair<ArgentumSearchWorld, BeliefBatch<Weighted<SearchWorld>>> {
        val registry = CardRegistry().apply { register(PortalSet.basicLands); register(StrongholdSet.cards) }
        val deck = mapOf("Mountain" to 18, "Shock" to 2)
        val decks = mapOf("p0" to deck, "p1" to deck)
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(GameConfig(players = listOf("Alice", "Bob").map { name ->
                PlayerConfig(name, Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray()))
            }, seed = 901L, skipMulligans = true, startingPlayerIndex = 0))
        }
        val root = ArgentumSearchWorld.create(environment, "synthetic-hand-queries", 8L,
            effectiveSetupSeed = 901L, knownDecks = decks)
        var ready = false
        for (index in 0 until 32) {
            val menu = root.expandChoices().candidates
            if (root.actorToAct() == "p0" && menu.any { it.operationFamily == SemanticOperationFamily.PLAY_LAND }) {
                ready = true
                break
            }
            val pass = menu.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
            assertTrue(root.step(pass).accepted)
        }
        check(ready) { "Fixture failed to reach a genuine main-phase menu" }
        val batch = ArgentumHybridBeliefWorldSource(root).sample(root.informationState("p1"), decks, 77L, 8)
        return root to batch
    }
}
