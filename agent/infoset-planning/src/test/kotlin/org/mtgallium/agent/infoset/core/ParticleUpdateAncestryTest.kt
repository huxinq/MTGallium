package org.mtgallium.agent.infoset.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class ParticleUpdateAncestryTest {
    @Test fun `parent indices survive filtering across all update routes even for equal worlds`() {
        for (route in listOf("observed", "private", "information")) {
            val worlds = List(6) { World(compatible = it == 1 || it == 4) }
            val original = belief(worlds)
            fun execute(capture: Boolean): ParticleBeliefUpdate = when (route) {
                "observed" -> original.advance("p0", pass.signature, updateSeed = 77,
                    observation = compatible, captureAncestry = capture)
                "private" -> original.advanceUnobserved("p0", first, 77,
                    observation = compatible, captureAncestry = capture)
                else -> original.conditionOnInformationState("p0", worlds[1].informationState("p0").informationStateDigest,
                    77, captureAncestry = capture)
            }
            val unobserved = execute(false)
            val observed = execute(true)
            val ancestry = assertNotNull(observed.ancestry)
            assertNull(unobserved.ancestry)
            assertTrue(ancestry.resampled)
            assertEquals(6, ancestry.inputCount)
            assertEquals(listOf(1, 1, 1, 4, 4, 4), ancestry.offspring.map { it.inputIndex })
            assertEquals(listOf(0, 1, 2, 0, 1, 2), ancestry.offspring.map { it.duplicateIndex })
            assertEquals(listOf(ParticleOffspringRoute.FORK, ParticleOffspringRoute.REJUVENATOR,
                ParticleOffspringRoute.REJUVENATOR).let { it + it }, ancestry.offspring.map { it.route })
            assertEquals(unobserved.diagnostics, observed.diagnostics)
            assertEquals(values(unobserved.belief), values(observed.belief))
            // Surviving worlds deliberately expose the same information. Equality cannot recover these parent slots.
            assertEquals(1, observed.belief.weightedWorlds().map { it.value.informationState("p0") }.distinct().size)
            assertTrue(worlds.all { it.depth == 0 })
        }
    }

    @Test fun `identity ancestry is immutable and composable across successive updates`() {
        val initial = belief(List(3) { World(true) })
        val first = initial.advance("p0", pass.signature, updateSeed = 19, captureAncestry = true)
        val retained = assertNotNull(first.ancestry)
        assertFalse(retained.resampled)
        assertEquals(listOf(0, 1, 2), retained.offspring.map { it.inputIndex })
        assertTrue(retained.offspring.all { it.route == ParticleOffspringRoute.RETAINED && it.duplicateIndex == null })
        assertFailsWith<UnsupportedOperationException> { (retained.offspring as MutableList).clear() }
        val second = first.belief.advance("p0", pass.signature, updateSeed = 20, captureAncestry = true)
        assertEquals(retained.offspring, second.ancestry!!.offspring)
        assertEquals(listOf(0, 1, 2), second.ancestry!!.offspring.map { retained.offspring[it.inputIndex].inputIndex })
        assertEquals(List(3) { 1 }, first.belief.weightedWorlds().map { (it.value as World).depth })
        assertEquals(List(3) { 2 }, second.belief.weightedWorlds().map { (it.value as World).depth })
    }

    @Test fun `skewed weights record actual sampler choices without changing seeds or rejuvenation`() {
        fun execute(capture: Boolean): Pair<ParticleBeliefUpdate, List<Pair<Int, Long>>> {
            val calls = mutableListOf<Pair<Int, Long>>()
            val original = belief(List(4) { World(true) }, listOf(.97, .01, .01, .01))
            val update = original.advance("p0", pass.signature, updateSeed = 13,
                rejuvenator = ParticleRejuvenator { world, duplicate, seed ->
                    calls += duplicate to seed
                    (world.fork() as World).also { it.marker = seed }
                }, captureAncestry = capture)
            return update to calls
        }
        val (plain, plainCalls) = execute(false)
        val (recorded, recordedCalls) = execute(true)
        assertEquals(plainCalls, recordedCalls)
        assertTrue(recordedCalls.isNotEmpty())
        assertEquals(values(plain.belief), values(recorded.belief))
        assertEquals(plain.diagnostics, recorded.diagnostics)
        val ancestry = assertNotNull(recorded.ancestry)
        assertTrue(ancestry.resampled)
        assertEquals(recordedCalls.size, ancestry.offspring.count { it.route == ParticleOffspringRoute.REJUVENATOR })
        // A changed child state still has a parent; ancestry does not assert copy-only semantics.
        assertTrue(recorded.belief.weightedWorlds().any { (it.value as World).marker != 0L })
    }

    @Test fun `depletion and failed rejuvenation never return a successful ancestry record`() {
        val initial = belief(List(3) { World(it == 2) })
        assertFailsWith<ParticleDepletionException> {
            initial.advance("p0", pass.signature, updateSeed = 1,
                observation = ParticleObservationCondition(null) { false }, captureAncestry = true)
        }
        assertFailsWith<IllegalStateException> {
            initial.advance("p0", pass.signature, updateSeed = 1, observation = compatible,
                rejuvenator = ParticleRejuvenator { _, _, _ -> World(false) }, captureAncestry = true)
        }
        assertTrue(initial.weightedWorlds().all { (it.value as World).depth == 0 })
    }

    private fun values(belief: ParticleBelief) = belief.weightedWorlds().map {
        val world = it.value as World
        Triple(it.weight, world.depth, world.marker)
    }
    private fun belief(worlds: List<World>, weights: List<Double> = List(worlds.size) { 1.0 / worlds.size }) =
        ParticleBelief.from(BeliefBatch(worlds.mapIndexed { i, w -> Weighted<SearchWorld>(w, weights[i]) },
            BeliefDiagnostics(BeliefMode.CONSISTENCY_ONLY_V1, worlds.size, worlds.size, 0,
                worlds.size.toDouble(), worlds.size.toDouble(), 0.0, 0)), BeliefMode.CONSISTENCY_ONLY_V1)

    private class World(val compatible: Boolean, var depth: Int = 0, var marker: Long = 0) : SearchWorld {
        override fun actorToAct() = "p0"
        override fun decisionContext(view: DecisionView): DecisionSiteRequest = DecisionSiteRequest.capture(
            "p0", expandChoices(), { EpistemicState.capture(informationState("p0")) }, view)
        override fun expandChoices() = PolicyExpansion(listOf(pass), true, 1L, "ancestry-test-v1", 0)
        override fun fork(): SearchWorld = World(compatible, depth, marker)
        override fun step(choice: SemanticChoice): SearchStepResult { check(choice == pass); depth++; return SearchStepResult(true) }
        override fun terminalPayoff(rootPlayer: String): Double? = null
        override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String) = 0.0
        override fun informationState(viewer: String) = InformationStateRepresentation(
            actingPlayerId = "p0", observation = PlayerObservationSnapshot(viewer, depth, "TEST", "TEST", "p0", "p0",
                emptyList(), emptyList(), emptyList(), pendingDecision = null,
                observationDigest = PolicyJson.sha256("$viewer:$compatible:$depth")),
            informationStateDigest = PolicyJson.sha256("info:$viewer:$compatible:$depth"),
            historyCommitment = PolicyHistoryCommitment.empty(), history = emptyList(),
            candidates = if (viewer == "p0") listOf(pass) else emptyList(), terminated = false)
    }

    companion object {
        private val pass = SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
            operationFamily = SemanticOperationFamily.PASS_PRIORITY,
            canonicalPayload = buildJsonObject { put("type", "pass") }, display = SemanticChoiceDisplay("Pass"))
        private val compatible = ParticleObservationCondition(null) { (it as World).compatible }
        private val first = object : OpponentPolicy {
            override val id = "ancestry-first-v1"
            override val requiresProductionAdmission = false
            override fun distribution(context: DecisionSiteRequest, policySeed: Long) =
                ProbabilityDistribution.normalized(context.expansion.candidates.map { ProbabilityMass(it, 1.0) })
        }
    }
}
