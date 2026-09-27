package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class InformationSetSearchTest {
    @Test fun `wider prior menu does not widen ordinary rollout requests`() {
        val limits = mutableListOf<Int?>()
        class Traced(private val world: SearchWorld) : SearchWorld by world {
            override fun fork(): SearchWorld = Traced(world.fork())
            override fun decisionContext(view: MenuRequest): DecisionContext {
                if (world.informationState("p0").observation.turnNumber > 0) limits += view.limit
                return world.decisionContext(view)
            }
        }
        val prior = object : SearchPrior {
            override val configurationId = "tree-only"
            override val candidateLimit = 256
            override val explorationConstant = 1.0
            override fun probabilities(context: DecisionContext): Map<String, Double> =
                context.menu.candidates.associate { it.signature to 1.0 / context.menu.candidates.size }
        }
        val search = InformationSetSearch(InformationSetSearchConfig(simulations = 1, maxPolicyDecisions = 4,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)), UniformOpponentPolicy,
            UniformOpponentPolicy, UniformOpponentPolicy, LeafValueSource.Information(testInformationEvaluator()), prior)
        search.search("p0", batch(listOf(Traced(FakeWorld(terminalAtDepth = 5)))), 71L)
        assertTrue(limits.isNotEmpty())
        assertTrue(limits.all { it == 64 }, limits.toString())
    }

    @Test fun `PUCT skips low prior first tries and ranks beyond the original cap`() {
        val seen = mutableListOf<Int>()
        val prior = object : SearchPrior {
            override val configurationId = "synthetic-last"
            override val candidateLimit = 128
            override val explorationConstant = 2.0
            override fun probabilities(context: DecisionContext): Map<String, Double> {
                assertEquals("p0", context.site().epistemic.viewerId)
                seen += context.menu.candidates.size
                return context.menu.candidates.associate { it.signature to if (it.display.label == "C99") 1.0 else 0.0 }
            }
        }
        val search = InformationSetSearch(InformationSetSearchConfig(simulations = 8, maxPolicyDecisions = 1,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE)), UniformOpponentPolicy,
            UniformOpponentPolicy, UniformOpponentPolicy, LeafValueSource.Information(testInformationEvaluator()), prior)
        val result = search.search("p0", batch(listOf(FakeWorld(candidateCount = 100))), 71L)
        assertEquals("C99", result.chosen.display.label)
        assertEquals(listOf(100), seen)
        assertEquals(64, result.candidates.size)
        assertEquals(8, result.candidates.single { it.choice.display.label == "C99" }.visits)
        assertEquals(1, result.candidates.count { it.visits > 0 })
    }

    @Test fun `invalid PUCT priors fail rather than replacing probabilities`() {
        for (mass in listOf(-1.0, Double.NaN, 0.0)) {
            val prior = object : SearchPrior {
                override val configurationId = "invalid"
                override val candidateLimit = 64
                override val explorationConstant = 1.0
                override fun probabilities(context: DecisionContext) = context.menu.candidates.associate { it.signature to mass }
            }
            val search = InformationSetSearch(InformationSetSearchConfig(simulations = 1, maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE)), UniformOpponentPolicy,
                UniformOpponentPolicy, UniformOpponentPolicy, LeafValueSource.Information(testInformationEvaluator()), prior)
            assertFailsWith<IllegalArgumentException> { search.search("p0", batch(listOf(FakeWorld())), 1L) }
        }
    }

    @Test fun `rollout step resets for each continuation and spans both players`() {
        val seen = mutableListOf<Int>()
        val schedule = object : RolloutPolicySchedule {
            override val id = "step-test"
            override fun atStep(step: Int): ActionSelector { seen += step; return UniformOpponentPolicy }
            override fun select(context: DecisionContext, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision = error("step required")
        }
        val search = InformationSetSearch(InformationSetSearchConfig(simulations = 1, maxPolicyDecisions = 4,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)), UniformOpponentPolicy,
            schedule, schedule, LeafValueSource.Information(testInformationEvaluator()))
        repeat(2) {
            search.search("p0", batch(listOf(FakeWorld(terminalAtDepth = 5))), 71L)
        }
        assertEquals(listOf(0, 1, 2, 0, 1, 2), seen)
    }


    @Test
    fun `leaf scores reject nonfinite values before clipping`() {
        val config = InformationSetSearchConfig(simulations = 1, maxPolicyDecisions = 1,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT))
        fun settle(score: Double): Double {
            val source = LeafValueSource.Information(object : InformationStateEvaluator {
                override val id = "constant-test-score"
                override fun evaluate(information: InformationStateRepresentation, rootPlayer: String) = score
            })
            return coreSearch(config, UniformOpponentPolicy, valueSource = source)
                .search("p0", batch(listOf(FakeWorld(valueForA = score, valueForB = score))), 7L).rootValue
        }
        for (score in listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)) {
            assertFailsWith<IllegalArgumentException>("score=$score") { settle(score) }
        }
        for ((score, expected) in listOf(3.0 to 1.0, -3.0 to -1.0, 0.25 to 0.25)) {
            assertEquals(expected, settle(score))
        }
    }

    @Test
    fun `bounded rollout observation preserves search choices and backups with exact depths`() {
        val seen = mutableListOf<Pair<Int, Int>>()
        val policy = object : OpponentPolicy by UniformOpponentPolicy, RolloutObserver {
            override fun observeDecision(context: DecisionContext, choice: SemanticChoice,
                searchSeed: Long, simulationIndex: Int, depth: Int) {
                assertEquals(71L, searchSeed)
                assertTrue(choice in context.menu.candidates && context.menu.isProfileExhaustive)
                assertTrue(context.information().actingPlayerId != null)
                seen += simulationIndex to depth
            }
        }
        val config = InformationSetSearchConfig(simulations = 2, maxPolicyDecisions = 4,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT))
        val plain = coreSearch(config, UniformOpponentPolicy, rolloutPolicy = UniformOpponentPolicy,
            rolloutOpponentPolicy = UniformOpponentPolicy).search("p0", batch(listOf(FakeWorld(terminalAtDepth = 5))), 71L)
        val observed = coreSearch(config, UniformOpponentPolicy, rolloutPolicy = policy,
            rolloutOpponentPolicy = policy).search("p0", batch(listOf(FakeWorld(terminalAtDepth = 5))), 71L)
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { (simulation, depth) -> simulation in 0..1 && depth in 1..3 })
        assertEquals(plain.copy(diagnostics = plain.diagnostics.copy(evaluatorNanos = 0)),
            observed.copy(diagnostics = observed.diagnostics.copy(evaluatorNanos = 0)))
    }

    @Test
    fun `bounded rollouts retain the actual incomplete menu witness`() {
        class IncompleteWorld(private val wrapped: SearchWorld) : SearchWorld by wrapped {
            override fun decisionContext(view: MenuRequest) = testDecisionContext(this, view)
            override fun fork(): SearchWorld = IncompleteWorld(wrapped.fork())
            override fun expandChoices() = wrapped.expandChoices().copy(isExhaustive = false,
                isProfileExhaustive = false, omissionReasons = setOf(ActionOmissionReason.SOURCE_NON_EXHAUSTIVE))
        }
        val seen = mutableListOf<Boolean>()
        val policy = object : OpponentPolicy {
            override val id = "completeness-probe"
            override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> =
                error("Selection does not use a distribution")
            override fun select(context: DecisionContext, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
                seen += context.menu.isProfileExhaustive
                return UniformOpponentPolicy.select(context, policySeed, sampleSeed)
            }
        }
        val search = coreSearch(InformationSetSearchConfig(simulations = 2, maxPolicyDecisions = 4,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)),
            UniformOpponentPolicy, rolloutPolicy = policy, rolloutOpponentPolicy = policy)
        search.search("p0", batch(listOf(IncompleteWorld(FakeWorld(terminalAtDepth = 5)))), 71L)
        assertTrue(seen.isNotEmpty() && seen.none { it })
    }

    @Test
    fun `historical settlement counts retain no fabricated learned estimate`() {
        val historical = CanonicalJson.format.decodeFromString<ReturnSourceCounts>(
            """{"terminalPayoffBackups":1,"heuristicSettlementBackups":2,"neutralUnresolvedSettlementBackups":3}"""
        )

        assertEquals(0, historical.learnedOutcomeEstimateBackups)
        assertEquals(6, historical.successfulBackups)
    }

    @Test
    fun `serialized search winner uses the production visit value and signature ordering`() {
        val first = quiescenceChoice("first")
        val second = quiescenceChoice("second")
        val byVisits = listOf(
            RootActionStatistics(first, visits = 3, meanValue = -1.0, visitFraction = 0.3),
            RootActionStatistics(second, visits = 2, meanValue = 1.0, visitFraction = 0.2),
        )
        assertEquals(first, byVisits.mostVisitedActionOrNull()?.choice)

        val byValue = byVisits.map { it.copy(visits = 3) }
        assertEquals(second, byValue.mostVisitedActionOrNull()?.choice)

        val tied = byValue.map { it.copy(meanValue = 0.5) }
        assertEquals(
            tied.minBy { it.choice.signature }.choice,
            tied.mostVisitedActionOrNull()?.choice,
        )
    }

    @Test
    fun `opponent replacement diagnostics preserve the exact sampled-decision denominator`() {
        fun run(disposition: OpponentPolicyReplacementEvidenceDisposition) = coreSearch(
            InformationSetSearchConfig(
                simulations = 32,
                maxPolicyDecisions = 2,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
            ),
            opponentPolicy = AuditedReplacementPolicy(disposition),
        ).search("p0", batch(listOf(FakeWorld())), searchSeed = 812L)
            .diagnostics.opponentModelPolicyDecisions

        val invalidating = run(OpponentPolicyReplacementEvidenceDisposition.INVALIDATES_EVIDENCE)
        val predeclared = run(
            OpponentPolicyReplacementEvidenceDisposition.PREDECLARED_EVIDENCE_ELIGIBLE
        )

        // Two first-visit root expansions stop at a new child; the remaining 30 simulations
        // actually sample one opponent response each.
        assertEquals(30, invalidating.decisions)
        assertEquals(mapOf("engine-component" to 30), invalidating.selectedComponents)
        assertEquals(mapOf("typed-intent-replacement" to 30), invalidating.effectivePolicies)
        assertEquals(mapOf("annotation-unavailable->typed-intent-replacement" to 30), invalidating.replacements)
        assertEquals(30, invalidating.evidenceInvalidatingReplacements)
        assertEquals(invalidating.copy(evidenceInvalidatingReplacements = 0), predeclared)
    }

    @Test
    fun `search is reproducible shares root visits and prefers the better sampled-world action`() {
        val roots = List(4) { FakeWorld() }
        val batch = batch(roots)
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 64,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,
        )

        val first = search.search("p0", batch, searchSeed = 91L)
        val second = search.search("p0", batch, searchSeed = 91L)

        assertEquals(
            first.copy(diagnostics = first.diagnostics.copy(evaluatorNanos = 0)),
            second.copy(diagnostics = second.diagnostics.copy(evaluatorNanos = 0)),
        )
        assertEquals("A", first.chosen.display.label)
        assertEquals(64, first.candidates.sumOf { it.visits })
        assertTrue(roots.all { it.depth == 0 })
    }

    @Test
    fun `candidate backup settlement counts classify terminal and heuristic leaves`() {
        fun search(
            world: SearchWorld,
            leaf: LeafEvaluationConfig,
        ) =
            coreSearch(
                InformationSetSearchConfig(
                    simulations = 4,
                    maxPolicyDecisions = 1,
                    leaf = leaf,
                ),
                UniformOpponentPolicy,
                valueSource = LeafValueSource.Information(testInformationEvaluator()),
            ).search("p0", batch(listOf(world)), searchSeed = 701L)

        val terminal = search(
            FakeWorld(terminalAtDepth = 1),
            LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE),
        )
        assertTrue(terminal.candidateSettlementCounts.values.all {
            it.terminalPayoffBackups == it.successfulBackups && it.heuristicSettlementBackups == 0
        })

        val heuristic = search(
            FakeWorld(),
            LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE),
        )
        assertTrue(heuristic.candidateSettlementCounts.values.all {
            it.heuristicSettlementBackups == it.successfulBackups && it.terminalPayoffBackups == 0
        })

    }

    @Test
    fun `scorer identity cannot choose the rollout cutoff algorithm`() {
        for (id in listOf("test-information-evaluator", "hand-authored")) {
            for (cutoff in listOf(RolloutCutoff.EVALUATE, RolloutCutoff.QUIESCENCE)) {
                val probe = QuiescenceProbe()
                val result = coreSearch(
                    InformationSetSearchConfig(simulations = 2, maxPolicyDecisions = 1,
                        maxQuiescenceForcedPasses = 2,
                        leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT, cutoff)),
                    UniformOpponentPolicy,
                    valueSource = LeafValueSource.Information(recordingEvaluator(probe, id)),
                ).search("p0", batch(listOf(QuiescenceWorld(probe, QuiescenceBranch.ENDLESS_PASS))), 105L)
                val direct = cutoff == RolloutCutoff.EVALUATE
                assertEquals(0.25, result.rootValue)
                assertEquals(2, result.diagnostics.evaluatorCalls)
                assertEquals(if (direct) 0 else 4, result.diagnostics.quiescenceForcedPasses)
                assertEquals(0, result.diagnostics.quiescenceUnresolvedBackups)
                assertEquals(id, result.diagnostics.evaluatorId)
            }
        }
    }

    @Test
    fun `settlement counts partition visits without changing deterministic search output`() {
        val config = InformationSetSearchConfig(
            simulations = 32,
            maxPolicyDecisions = 1,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE),
        )
        val first = coreSearch(config, UniformOpponentPolicy).search("p0", batch(listOf(FakeWorld())), 702L)
        val second = coreSearch(config, UniformOpponentPolicy).search("p0", batch(listOf(FakeWorld())), 702L)

        assertEquals(first.chosen, second.chosen)
        assertEquals(first.candidates, second.candidates)
        assertEquals(first.rootValue, second.rootValue)
        first.candidates.forEach { candidate ->
            assertEquals(candidate.visits, first.settlementCountsFor(candidate.choice).successfulBackups)
        }
    }

    @Test
    fun `fixed simulation schedule controls worlds without changing the root contract`() {
        val config = InformationSetSearchConfig(
            simulations = 64,
            maxPolicyDecisions = 1,
            leaf = LeafEvaluationConfig(
                LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                ),
        )
        val search = coreSearch(config, UniformOpponentPolicy)
        val belief = batch(List(8) { FakeWorld() })
        val scheduled = SimulationWorldSchedule(
            List(config.simulations) { FakeWorld(valueForA = -0.2, valueForB = 0.8) }
        )

        val ordinary = search.search("p0", belief, searchSeed = 915L)
        val controlled = search.search(
            "p0",
            belief,
            searchSeed = 915L,
            simulationWorldSchedule = scheduled,
        )

        assertEquals("A", ordinary.chosen.display.label)
        assertEquals("B", controlled.chosen.display.label)
        assertEquals(64, controlled.diagnostics.simulations)
        assertEquals(8, controlled.diagnostics.particles)
        assertEquals(64, controlled.diagnostics.transitionCacheMisses)
    }

    @Test
    fun `a rejected simulated choice aborts search instead of becoming a game loss`() {
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 1,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,
        )

        val failure = assertFailsWith<RejectedSearchTransitionException> {
            search.search("p0", batch(listOf(FakeWorld(rejectAtDepth = 0))), searchSeed = 92L)
        }

        assertEquals("simulated transition failure", failure.rejectionDiagnostic)
        assertTrue(failure.choiceSignature.isNotBlank())
    }

    @Test
    fun `a rejected opponent response aborts search instead of becoming a game loss`() {
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 8,
                maxPolicyDecisions = 2,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,
        )

        val failure = assertFailsWith<RejectedSearchTransitionException> {
            search.search("p0", batch(listOf(FakeWorld(rejectAtDepth = 1))), searchSeed = 93L)
        }

        assertEquals("simulated transition failure at depth 1", failure.rejectionDiagnostic)
        assertTrue(failure.choiceSignature.isNotBlank())
    }

    @Test
    fun `a rejected rollout continuation aborts search instead of becoming a game loss`() {
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 1,
                maxPolicyDecisions = 3,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.BOUNDED_ROLLOUT,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            rolloutPolicy = UniformOpponentPolicy,
            rolloutOpponentPolicy = UniformOpponentPolicy,
        )

        val failure = assertFailsWith<RejectedSearchTransitionException> {
            search.search("p0", batch(listOf(FakeWorld(rejectAtDepth = 1))), searchSeed = 94L)
        }

        assertEquals("simulated transition failure at depth 1", failure.rejectionDiagnostic)
        assertTrue(failure.choiceSignature.isNotBlank())
    }

    @Test
    fun `a rejected forced pass before evaluation aborts search instead of becoming a game loss`() {
        val probe = QuiescenceProbe()
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 1,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(recordingEvaluator(probe)),
        )

        val failure = assertFailsWith<RejectedSearchTransitionException> {
            search.search(
                "p0",
                batch(listOf(QuiescenceWorld(probe, QuiescenceBranch.FORCED_PASS, rejectAtStage = 1))),
                searchSeed = 95L,
            )
        }

        assertEquals("simulated transition failure at stage 1", failure.rejectionDiagnostic)
        assertTrue(failure.choiceSignature.isNotBlank())
    }

    @Test
    fun `bounded rollout prefix caching preserves seed-sensitive choices and settlement accounting`() {
        fun run(): Pair<InformationSetSearchResult, List<String>> {
            val decisions = mutableListOf<String>()
            fun policy(name: String) = object : OpponentPolicy {
                override val id = name
                override val distributionIsSeedInvariant = false
                override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.menu.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
                    candidates: List<SemanticChoice>, policySeed: Long): ProbabilityDistribution<SemanticChoice> =
                    ProbabilityDistribution.normalized(candidates.mapIndexed { index, choice ->
                        ProbabilityMass(choice, if ((policySeed and 1L).toInt() == index) 3.0 else 1.0)
                    })
                override fun decisionDiagnostic(context: DecisionContext, chosen: SemanticChoice, policySeed: Long, attributionSeed: Long): OpponentPolicyDecisionDiagnostic = decisionDiagnostic(context.information(), context.menu.candidates, chosen, policySeed, attributionSeed)

    fun decisionDiagnostic(opponentInformation: InformationStateRepresentation,
                    candidates: List<SemanticChoice>, chosen: SemanticChoice, policySeed: Long,
                    attributionSeed: Long): OpponentPolicyDecisionDiagnostic {
                    decisions += "$id:${opponentInformation.informationStateDigest}:$policySeed:$attributionSeed:${chosen.signature}"
                    return OpponentPolicyDecisionDiagnostic(declaredPolicyId = id, selectedComponentId = id)
                }
            }
            val result = coreSearch(InformationSetSearchConfig(simulations = 96, maxPolicyDecisions = 16,
                leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)), UniformOpponentPolicy,
                rolloutPolicy = policy("root-seeded"), rolloutOpponentPolicy = policy("opponent-seeded"))
                .search("p0", batch(List(2) { FakeWorld() }), searchSeed = 7571L)
            return result to decisions
        }
        val (cached, decisions) = run()
        assertTrue(decisions.isNotEmpty())
        assertTrue(cached.diagnostics.rolloutTransitionCacheHits > 0)
        assertTrue(cached.diagnostics.rolloutTransitionCacheSnapshots > 0)
    }

    @Test
    fun `bounded rollout cache caps snapshots and continues after the cap`() {
        val cached = coreSearch(InformationSetSearchConfig(simulations = 64, maxPolicyDecisions = 128,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)), UniformOpponentPolicy)
            .search("p0", batch(listOf(FakeWorld())), searchSeed = 7572L)
        assertEquals(4096, cached.diagnostics.rolloutTransitionCacheSnapshots)
        assertTrue(cached.diagnostics.rolloutTransitionCacheBypasses > 0)
    }

    @Test
    fun `rollout prefixes remain separate across scheduled worlds and rejected transitions still fail`() {
        fun search() = coreSearch(InformationSetSearchConfig(simulations = 16, maxPolicyDecisions = 8,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)), UniformOpponentPolicy)
        val schedule = SimulationWorldSchedule(List(16) { i ->
            FakeWorld(valueForA = i / 16.0, valueForB = -i / 16.0, terminalAtDepth = if (i % 2 == 0) 4 else null)
        })
        val cached = search().search("p0", batch(listOf(FakeWorld())), 7573L, simulationWorldSchedule = schedule)
        assertEquals(0, cached.diagnostics.rolloutTransitionCacheHits)
        assertFailsWith<RejectedSearchTransitionException> {
            search().search("p0", batch(listOf(FakeWorld(rejectAtDepth = 3))), 7573L)
        }
    }

    @Test
    fun `rollout cache prefixes and widening preserve independent search results`() {
        fun run(): List<InformationSetSearchResult> {
            val session = coreSearch(InformationSetSearchConfig(simulations = 64, maxPolicyDecisions = 10,
                initialExpansionLimit = 2, wideningThresholds = listOf(2, 4), wideningLimits = listOf(3, 4),
                leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)), UniformOpponentPolicy)
            val roots = List(2) { FakeWorld(candidateCount = 4, hiddenVariant = "same") }
            val first = session.search("p0", batch(roots), 7574L)
            val promoted = roots.map { original -> (original.fork() as FakeWorld).also { world ->
                repeat(2) { assertTrue(world.step(world.expandChoices().candidates.first()).accepted) }
            } }
            return listOf(first, session.search("p0", batch(promoted), 7575L))
        }
        val cached = run()
        assertTrue(cached.first().diagnostics.wideningEvents > 0)
        assertTrue(cached.first().diagnostics.rolloutTransitionCacheHits > 0)
        assertTrue(cached.all { it.candidates.isNotEmpty() })
    }

    @Test
    fun `every invocation starts fresh after an earlier search and uses the requested budget`() {
        val config = InformationSetSearchConfig(simulations = 17, maxPolicyDecisions = 4,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE))
        fun make() = coreSearch(config, UniformOpponentPolicy)
        val reusedObject = make()
        reusedObject.search("p0", batch(List(2) { FakeWorld() }), 41L)
        val worlds = batch(List(2) { FakeWorld(valueForA = -.4, valueForB = .8) })
        val actual = reusedObject.search("p0", worlds, 83L)
        val expected = make().search("p0", worlds, 83L)
        assertEquals(expected.copy(diagnostics = expected.diagnostics.copy(evaluatorNanos = 0)),
            actual.copy(diagnostics = actual.diagnostics.copy(evaluatorNanos = 0)))
        assertEquals(17, actual.diagnostics.simulations)
        assertEquals(17, actual.candidates.sumOf { it.visits })
        assertEquals(17, actual.candidateSettlementCounts.values.sumOf { it.successfulBackups })
    }

    @Test
    fun `exact semantic prefix cache reuses transitions and opponent distributions`() {
        val cached = coreSearch(
            InformationSetSearchConfig(
                simulations = 64,
                maxPolicyDecisions = 6,
                leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE),
            ),
            opponentPolicy = UniformOpponentPolicy,
        ).search("p0", batch(List(2) { FakeWorld() }), searchSeed = 19L)
        assertTrue(cached.diagnostics.transitionCacheHits > 0)
        assertTrue(cached.diagnostics.opponentDistributionCacheHits > 0)
    }

    @Test
    fun `information source uses the visible evaluator`() {
        var visibleEvaluations = 0
        val visible = object : InformationStateEvaluator {
            override val id = "mono-red-visible-board-v2"
            override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double {
                visibleEvaluations++
                return if (information.observation.step.endsWith(":B")) 0.9 else -0.9
            }
        }
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 32,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(visible),
        )

        val result = search.search("p0", batch(List(2) { FakeWorld() }), 92L)

        assertEquals("B", result.chosen.display.label)
        assertTrue(visibleEvaluations > 0)
    }

    @Test
    fun `bounded rollout can score its nonterminal horizon with information evaluator`() {
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 32,
                maxPolicyDecisions = 2,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.BOUNDED_ROLLOUT,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            rolloutPolicy = UniformOpponentPolicy,
            rolloutOpponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(testInformationEvaluator()),
        )

        val result = search.search("p0", batch(listOf(FakeWorld())), 93L)

        assertEquals("A", result.chosen.display.label)
        assertEquals(LeafEvaluationMethod.BOUNDED_ROLLOUT.name, result.diagnostics.leaf.stateSource)
    }

    @Test
    fun `non-exhaustive roots widen at the configured visit threshold`() {
        val root = FakeWorld(candidateCount = 140)
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 80,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
                wideningThresholds = listOf(64),
                wideningLimits = listOf(128),
            ),
            opponentPolicy = UniformOpponentPolicy,

            valueSource = LeafValueSource.Information(testEvaluator()),
        )

        val result = search.search("p0", batch(listOf(root)), 6L)

        assertEquals(1, result.diagnostics.wideningEvents)
        assertEquals(128, result.candidates.size)
    }

    @Test
    fun `first expansion uses configured limits below equal to and above adapter default`() {
        listOf(32, 64, 128).forEach { initialLimit ->
            val root = FakeWorld(candidateCount = 300)
            val search = coreSearch(
                InformationSetSearchConfig(
                    simulations = 1,
                    maxPolicyDecisions = 1,
                    leaf = LeafEvaluationConfig(
                        LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                        ),
                    initialExpansionLimit = initialLimit,
                    wideningThresholds = listOf(4),
                    wideningLimits = listOf(256),
                ),
                opponentPolicy = UniformOpponentPolicy,

            valueSource = LeafValueSource.Information(testEvaluator()),
        )

            val result = search.search("p0", batch(listOf(root)), 6L + initialLimit)

            assertEquals(initialLimit, result.candidates.size)
            assertTrue(initialLimit in root.requestedLimits)
        }
    }

    @Test
    fun `first widening point starts above configured initial limit`() {
        val root = FakeWorld(candidateCount = 300)
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 2,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
                initialExpansionLimit = 128,
                wideningThresholds = listOf(1),
                wideningLimits = listOf(256),
            ),
            opponentPolicy = UniformOpponentPolicy,

            valueSource = LeafValueSource.Information(testEvaluator()),
        )

        val result = search.search("p0", batch(listOf(root)), 134L)

        assertEquals(1, result.diagnostics.wideningEvents)
        assertEquals(256, result.candidates.size)
    }

    @Test
    fun `widening limits cannot repeat or shrink the initial candidate bound`() {
        assertFailsWith<IllegalArgumentException> {
            InformationSetSearchConfig(
                simulations = 1,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
                initialExpansionLimit = 128,
                wideningThresholds = listOf(1),
                wideningLimits = listOf(128),
            )
        }
    }

    @Test
    fun `opponent choices are environment transitions rather than shared tree nodes`() {
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 16,
                maxPolicyDecisions = 2,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,
        )

        val result = search.search("p0", batch(List(4) { FakeWorld() }), 99L)

        assertEquals(1, result.diagnostics.nodes)
        assertTrue(result.diagnostics.maximumDepth >= 2)
    }

    @Test
    fun `bounded rollout routes both seats through dedicated rollout policies`() {
        val outerOpponent = RecordingPolicy("outer-opponent")
        val rootRollout = RecordingPolicy("root-rollout")
        val opponentRollout = RecordingPolicy("opponent-rollout")
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 4,
                maxPolicyDecisions = 5,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.BOUNDED_ROLLOUT,
                    ),
            ),
            opponentPolicy = outerOpponent,
            rolloutPolicy = rootRollout,
            rolloutOpponentPolicy = opponentRollout,
        )

        val result = search.search("p0", batch(listOf(FakeWorld())), 73L)

        assertTrue(outerOpponent.actors.isNotEmpty())
        assertTrue(outerOpponent.actors.all { it == "p1" })
        assertTrue(rootRollout.actors.isNotEmpty())
        assertTrue(rootRollout.actors.all { it == "p0" })
        assertTrue(opponentRollout.actors.isNotEmpty())
        assertTrue(opponentRollout.actors.all { it == "p1" })
        assertEquals("root-rollout", result.diagnostics.rootRolloutPolicyId)
        assertEquals("opponent-rollout", result.diagnostics.opponentRolloutPolicyId)
        assertEquals(rootRollout.actors.size, result.diagnostics.rootRolloutDecisions)
        assertEquals(opponentRollout.actors.size, result.diagnostics.opponentRolloutDecisions)
        assertEquals(0, result.diagnostics.rootRolloutFallbacks)
        assertEquals(0, result.diagnostics.opponentRolloutFallbacks)
    }

    @Test
    fun `static quiescence compresses only an exhaustive typed pass before evaluation`() {
        val probe = QuiescenceProbe()
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 4,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(recordingEvaluator(probe)),
        )

        val result = search.search(
            "p0",
            batch(List(2) { QuiescenceWorld(probe, QuiescenceBranch.FORCED_PASS) }),
            101L,
        )

        assertEquals(4, result.diagnostics.quiescenceForcedPasses)
        assertEquals(0, result.diagnostics.quiescenceStrategicDecisions)
        assertEquals(0, result.diagnostics.quiescenceFallbacks)
        assertTrue(probe.evaluatedStages.all { it == 2 })
    }

    @Test
    fun `volatile branching resumes search and singleton mana remains strategic`() {
        listOf(QuiescenceBranch.REAL_BRANCH, QuiescenceBranch.SINGLETON_MANA).forEach { branch ->
            val probe = QuiescenceProbe()
            val search = coreSearch(
                InformationSetSearchConfig(
                    simulations = 4,
                    maxPolicyDecisions = 1,
                    leaf = LeafEvaluationConfig(
                        LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                        ),
                ),
                opponentPolicy = UniformOpponentPolicy,
                valueSource = LeafValueSource.Information(recordingEvaluator(probe)),
            )

            val result = search.search("p0", batch(listOf(QuiescenceWorld(probe, branch))), 102L)

            assertEquals(0, result.diagnostics.quiescenceForcedPasses, branch.name)
            assertTrue(result.diagnostics.quiescenceStrategicDecisions > 0, branch.name)
            assertEquals(0, result.diagnostics.quiescenceFallbacks, branch.name)
            assertTrue(probe.evaluatedStages.all { it == 2 }, branch.name)
        }
    }

    @Test
    fun `quiescence budgets fail visibly instead of looping`() {
        val probe = QuiescenceProbe()
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 2,
                maxPolicyDecisions = 1,
                maxQuiescenceForcedPasses = 2,
                leaf = LeafEvaluationConfig(
                    stateSource = LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(recordingEvaluator(probe)),
        )

        val result = search.search("p0", batch(listOf(QuiescenceWorld(probe, QuiescenceBranch.ENDLESS_PASS))), 103L)

        assertEquals(2, result.diagnostics.quiescenceOverflows)
        assertEquals(2, result.diagnostics.quiescenceFallbacks)
        assertEquals(4, result.diagnostics.quiescenceForcedPasses)
    }

    @Test
    fun `profile forced passes resolve a volatile quiescence leaf only under the declared rule`() {
        fun search(rule: QuiescencePassRule): Pair<InformationSetSearchResult, QuiescenceProbe> {
            val probe = QuiescenceProbe()
            return coreSearch(
                InformationSetSearchConfig(simulations = 2, maxPolicyDecisions = 1, leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.BOUNDED_ROLLOUT, RolloutCutoff.QUIESCENCE, quiescencePasses = rule)),
                opponentPolicy = UniformOpponentPolicy,
                valueSource = LeafValueSource.Information(recordingEvaluator(probe)),
            ).search("p0", batch(listOf(QuiescenceWorld(probe, QuiescenceBranch.FORCED_PASS,
                profilePrunedPasses = true))), 113L) to probe
        }

        val (rules, rulesProbe) = search(QuiescencePassRule.RULES_FORCED_V1)
        assertEquals(listOf(1, 1), rulesProbe.evaluatedStages)
        assertEquals(0, rules.diagnostics.quiescenceForcedPasses)
        assertEquals(2, rules.diagnostics.quiescenceFallbacks)

        val (profile, profileProbe) = search(QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1)
        assertEquals(listOf(2, 2), profileProbe.evaluatedStages)
        assertEquals(2, profile.diagnostics.quiescenceForcedPasses)
        assertEquals(2, profile.diagnostics.quiescenceProfileForcedPasses)
        assertEquals(0, profile.diagnostics.quiescenceFallbacks)
    }

    @Test
    fun `profile forced passes leave quiet positions where the exact rule already advances`() {
        fun stages(profilePruned: Boolean, rule: QuiescencePassRule): List<Int> {
            val probe = QuiescenceProbe()
            coreSearch(
                InformationSetSearchConfig(simulations = 2, maxPolicyDecisions = 1, leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE, quiescencePasses = rule)),
                opponentPolicy = UniformOpponentPolicy,
                valueSource = LeafValueSource.Information(recordingEvaluator(probe)),
            ).search("p0", batch(listOf(QuiescenceWorld(probe, QuiescenceBranch.FORCED_PASS,
                profilePrunedPasses = profilePruned, quiet = true))), 114L)
            return probe.evaluatedStages
        }
        for (rule in QuiescencePassRule.entries) {
            assertEquals(listOf(2, 2), stages(profilePruned = false, rule), rule.name)
            assertEquals(listOf(1, 1), stages(profilePruned = true, rule), rule.name)
        }
    }

    @Test
    fun `the profile pass rule leaves rules forced quiescence unchanged`() {
        fun search(rule: QuiescencePassRule): InformationSetSearchResult {
            val probe = QuiescenceProbe()
            val result = coreSearch(
                InformationSetSearchConfig(simulations = 4, maxPolicyDecisions = 1, leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE, quiescencePasses = rule)),
                opponentPolicy = UniformOpponentPolicy,
                valueSource = LeafValueSource.Information(recordingEvaluator(probe)),
            ).search("p0", batch(List(2) { QuiescenceWorld(probe, QuiescenceBranch.FORCED_PASS) }), 101L)
            return result.copy(diagnostics = result.diagnostics.copy(
                leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE).diagnostic(), evaluatorNanos = 0))
        }
        val rules = search(QuiescencePassRule.RULES_FORCED_V1)
        assertEquals(4, rules.diagnostics.quiescenceForcedPasses)
        assertEquals(rules, search(QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1))
    }

    @Test
    fun `diagnostic leaf preserves current and rollout profile bytes and retired modes`() {
        val current = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE).diagnostic()
        assertEquals("""{"stateSource":"CURRENT_INFORMATION_STATE","cutoff":"EVALUATE","unresolved":"EVALUATE"}""",
            CanonicalJson.format.encodeToString(LeafEvaluationDiagnostic.serializer(), current))
        val profile = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT,
            cutoff = RolloutCutoff.POLICY_QUIESCENCE,
            quiescencePasses = QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1).diagnostic()
        assertEquals("""{"stateSource":"BOUNDED_ROLLOUT","cutoff":"POLICY_QUIESCENCE","unresolved":"EVALUATE","quiescencePasses":"PROFILE_FORCED_WHILE_VOLATILE_V1"}""",
            CanonicalJson.format.encodeToString(LeafEvaluationDiagnostic.serializer(), profile))
        val historical = """{"stateSource":"CURRENT_SAMPLED_WORLD","cutoff":"EVALUATE","unresolved":"BACK_UP_NEUTRAL"}"""
        assertEquals(historical, CanonicalJson.format.encodeToString(LeafEvaluationDiagnostic.serializer(),
            CanonicalJson.format.decodeFromString(LeafEvaluationDiagnostic.serializer(), historical)))
        assertFailsWith<IllegalArgumentException> {
            LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT,
                quiescencePasses = QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1)
        }
    }

    @Test
    fun `widening stops at a profile exhaustive menu without changing search statistics`() {
        fun search(profileExhaustive: Boolean): Pair<InformationSetSearchResult, List<Int>> {
            val limits = mutableListOf<Int>()
            val result = coreSearch(InformationSetSearchConfig(simulations = 70, maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE)), UniformOpponentPolicy)
                .search("p0", batch(listOf(OmittingProgressiveWorld(FakeWorld(), profileExhaustive, limits))), 115L)
            return result to limits
        }
        val (profile, profileLimits) = search(profileExhaustive = true)
        val (bounded, boundedLimits) = search(profileExhaustive = false)
        assertEquals(0, profile.diagnostics.wideningEvents)
        assertTrue(profileLimits.all { it <= 64 })
        assertEquals(1, bounded.diagnostics.wideningEvents)
        assertTrue(128 in boundedLimits)
        assertEquals(bounded.chosen, profile.chosen)
        assertEquals(bounded.candidates, profile.candidates)
        assertEquals(bounded.rootValue, profile.rootValue)
    }

    @Test
    fun `unsettled leaf counter follows the actual evaluation position`() {
        fun search(cutoff: RolloutCutoff): InformationSetSearchResult {
            val probe = QuiescenceProbe()
            return coreSearch(
                policyQuiescenceConfig().copy(leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.BOUNDED_ROLLOUT, cutoff)),
                opponentPolicy = UniformOpponentPolicy,
                valueSource = LeafValueSource.Information(recordingEvaluator(probe)),
            ).search("p0", batch(listOf(QuiescenceWorld(
                probe, QuiescenceBranch.REAL_BRANCH, volatileThroughStage = 1,
            ))), 107L)
        }

        val direct = search(RolloutCutoff.EVALUATE)
        val settled = search(RolloutCutoff.POLICY_QUIESCENCE)
        assertEquals(2, direct.diagnostics.evaluatorCalls)
        assertEquals(2, direct.diagnostics.nonQuietLeafEvaluations)
        assertEquals(2, settled.diagnostics.evaluatorCalls)
        assertEquals(0, settled.diagnostics.nonQuietLeafEvaluations)
        assertTrue(settled.diagnostics.quiescenceStrategicDecisions > 0)
    }

    @Test
    fun `policy quiescence selects and accounts for both actors before evaluating a quiet leaf`() {
        val probe = QuiescenceProbe()
        val root = RecordingPolicy("quiescence-root")
        val opponent = RecordingPolicy("quiescence-opponent")
        val search = coreSearch(
            policyQuiescenceConfig(),
            opponentPolicy = UniformOpponentPolicy,
            rolloutPolicy = root,
            rolloutOpponentPolicy = opponent,
            valueSource = LeafValueSource.Information(recordingEvaluator(probe, "test-information-evaluator")),
        )
        val result = search.search("p0", batch(listOf(QuiescenceWorld(
            probe, QuiescenceBranch.REAL_BRANCH, volatileThroughStage = 3, alternatingActors = true,
        ))), 107L)

        assertEquals(listOf(4, 4), probe.evaluatedStages)
        assertEquals(6, result.diagnostics.quiescenceStrategicDecisions)
        assertEquals(listOf("p0", "p0"), root.actors.map { requireNotNull(it) })
        assertEquals(listOf("p1", "p1", "p1", "p1"), opponent.actors.map { requireNotNull(it) })
        assertEquals(2, result.diagnostics.rootRolloutPolicyDecisions.decisions)
        assertEquals(4, result.diagnostics.opponentRolloutPolicyDecisions.decisions)
        assertEquals(0, result.diagnostics.quiescenceFallbacks)
        assertEquals(2, result.candidateSettlementCounts.values.sumOf { it.heuristicSettlementBackups })
    }

    @Test
    fun `policy quiescence budget exhaustion remains a heuristic fallback`() {
        val probe = QuiescenceProbe()
        val result = coreSearch(
            policyQuiescenceConfig().copy(maxQuiescenceDecisions = 1),
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(recordingEvaluator(probe, "test-information-evaluator")),
        ).search("p0", batch(listOf(QuiescenceWorld(
            probe, QuiescenceBranch.REAL_BRANCH, volatileThroughStage = 8,
        ))), 108L)

        assertEquals(listOf(2, 2), probe.evaluatedStages)
        assertEquals(2, result.diagnostics.quiescenceStrategicDecisions)
        assertEquals(2, result.diagnostics.quiescenceOverflows)
        assertEquals(2, result.diagnostics.quiescenceFallbacks)
        assertEquals(2, result.diagnostics.nonQuietLeafEvaluations)
        assertEquals(0, result.candidateSettlementCounts.values.sumOf { it.terminalPayoffBackups })
    }

    @Test
    fun `policy quiescence preserves terminal bypass and rejected decision failures`() {
        val probe = QuiescenceProbe()
        val search = coreSearch(
            policyQuiescenceConfig(),
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(recordingEvaluator(probe, "test-information-evaluator")),
        )
        val terminal = search.search("p0", batch(listOf(QuiescenceWorld(
            probe, QuiescenceBranch.REAL_BRANCH, terminalAtStage = 2, volatileThroughStage = 3,
        ))), 109L)
        assertEquals(1.0, terminal.rootValue)
        assertEquals(0, terminal.diagnostics.evaluatorCalls)
        assertEquals(0, terminal.diagnostics.nonQuietLeafEvaluations)
        assertEquals(2, terminal.candidateSettlementCounts.values.sumOf { it.terminalPayoffBackups })
        assertFailsWith<RejectedSearchTransitionException> {
            search.search("p0", batch(listOf(QuiescenceWorld(
                probe, QuiescenceBranch.REAL_BRANCH, rejectAtStage = 1,
            ))), 110L)
        }
    }

    @Test
    fun `policy quiescence does not advance a quiet strategic decision`() {
        val probe = QuiescenceProbe()
        val policy = RecordingPolicy("quiet-policy")
        val settlement = coreSearch(
            policyQuiescenceConfig().copy(simulations = 1), opponentPolicy = policy, rolloutPolicy = policy,
            rolloutOpponentPolicy = policy,
            valueSource = LeafValueSource.Information(recordingEvaluator(probe, "test-information-evaluator")),
        ).search("p0", batch(listOf(QuiescenceWorld(probe, QuiescenceBranch.REAL_BRANCH, stage = 1))), 111L)
        assertEquals(1, settlement.candidateSettlementCounts.values.sumOf { it.heuristicSettlementBackups })
        assertEquals(listOf(2), probe.evaluatedStages)
        assertTrue(policy.actors.isEmpty())
    }

    private fun policyQuiescenceConfig() = InformationSetSearchConfig(
        simulations = 2,
        maxPolicyDecisions = 1,
        maxQuiescenceDecisions = 8,
        leaf = LeafEvaluationConfig(
            LeafEvaluationMethod.BOUNDED_ROLLOUT, RolloutCutoff.POLICY_QUIESCENCE,
        ),
    )

    @Test
    fun `policy quiescence shares one forced pass budget across intervening policy choices`() {
        val probe = QuiescenceProbe()
        val result = coreSearch(
            policyQuiescenceConfig().copy(maxQuiescenceForcedPasses = 2),
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(recordingEvaluator(probe, "test-information-evaluator")),
        ).search("p0", batch(listOf(QuiescenceWorld(
            probe, QuiescenceBranch.ALTERNATING_PASS, volatileThroughStage = 8,
        ))), 112L)
        assertEquals(listOf(5, 5), probe.evaluatedStages)
        assertEquals(4, result.diagnostics.quiescenceForcedPasses)
        assertEquals(4, result.diagnostics.quiescenceStrategicDecisions)
        assertEquals(2, result.diagnostics.quiescenceOverflows)
    }

    @Test
    fun `explicit rollout horizon evaluation scores directly instead of neutral quiescence settlement`() {
        val probe = QuiescenceProbe()
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 2,
                maxPolicyDecisions = 1,
                maxQuiescenceForcedPasses = 2,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.BOUNDED_ROLLOUT,
                    RolloutCutoff.EVALUATE,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(recordingEvaluator(probe, "test-information-evaluator")),
        )

        val result = search.search(
            "p0",
            batch(listOf(QuiescenceWorld(probe, QuiescenceBranch.ENDLESS_PASS))),
            105L,
        )

        assertEquals(0, result.diagnostics.quiescenceOverflows)
        assertEquals(0, result.diagnostics.quiescenceUnresolvedBackups)
        assertEquals(2, result.diagnostics.evaluatorCalls)
        assertEquals(0.25, result.rootValue)
    }

    @Test
    fun `quiescence evaluation fallback advances forced passes and evaluates unresolved horizon`() {
        val probe = QuiescenceProbe()
        val config = InformationSetSearchConfig(
            simulations = 2,
            maxPolicyDecisions = 1,
            maxQuiescenceForcedPasses = 1,
            leaf = LeafEvaluationConfig(
                LeafEvaluationMethod.BOUNDED_ROLLOUT,
                RolloutCutoff.QUIESCENCE,
            ),
        )
        val search = coreSearch(
            config,
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(recordingEvaluator(probe, "test-information-evaluator")),
        )

        val unresolved = search.search(
            "p0",
            batch(listOf(QuiescenceWorld(probe, QuiescenceBranch.ENDLESS_PASS))),
            106L,
        )

        assertEquals(2, unresolved.diagnostics.quiescenceForcedPasses)
        assertEquals(2, unresolved.diagnostics.quiescenceOverflows)
        assertEquals(0, unresolved.diagnostics.quiescenceUnresolvedBackups)
        assertEquals(2, unresolved.diagnostics.evaluatorCalls)
        assertTrue(probe.evaluatedStages.all { it == 2 })
        assertTrue(unresolved.candidateSettlementCounts.values.sumOf {
            it.heuristicSettlementBackups
        } > 0)
        assertEquals(0, unresolved.candidateSettlementCounts.values.sumOf {
            it.neutralUnresolvedSettlementBackups
        })

        val volatileProbe = QuiescenceProbe()
        val volatile = coreSearch(
            config,
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(recordingEvaluator(volatileProbe, "test-information-evaluator")),
        ).search(
            "p0",
            batch(listOf(QuiescenceWorld(
                volatileProbe,
                QuiescenceBranch.FORCED_PASS,
                volatileThroughStage = 2,
            ))),
            107L,
        )

        assertEquals(2, volatile.diagnostics.quiescenceForcedPasses)
        assertEquals(0, volatile.diagnostics.quiescenceOverflows)
        assertEquals(0, volatile.diagnostics.quiescenceStrategicDecisions)
        assertEquals(0, volatile.diagnostics.quiescenceUnresolvedBackups)
        assertEquals(2, volatile.diagnostics.evaluatorCalls)
        assertTrue(volatileProbe.evaluatedStages.all { it == 2 })
        assertTrue(volatileProbe.evaluatedCandidateCounts.all { it == 2 })
        assertTrue(volatile.candidateSettlementCounts.values.sumOf {
            it.heuristicSettlementBackups
        } > 0)
        assertEquals(0, volatile.candidateSettlementCounts.values.sumOf {
            it.neutralUnresolvedSettlementBackups
        })

        val terminalProbe = QuiescenceProbe()
        val terminal = coreSearch(
            config,
            opponentPolicy = UniformOpponentPolicy,
            valueSource = LeafValueSource.Information(recordingEvaluator(terminalProbe, "test-information-evaluator")),
        ).search(
            "p0",
            batch(listOf(QuiescenceWorld(terminalProbe, QuiescenceBranch.FORCED_PASS, terminalAtStage = 2))),
            108L,
        )

        assertEquals(0, terminal.diagnostics.evaluatorCalls)
        assertTrue(terminal.candidateSettlementCounts.values.all {
            it.terminalPayoffBackups == it.successfulBackups && it.heuristicSettlementBackups == 0
        })
    }

    @Test
    fun `search fails closed when root particles disagree about information actor or proposal contract`() {
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 4,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,

            valueSource = LeafValueSource.Information(testEvaluator()),
        )
        val menu = FakeWorld().expandChoices()
        val mismatches = listOf(
            FakeWorld(variant = "different-information"),
            object : SearchWorld by FakeWorld() {
                override fun decisionContext(view: MenuRequest) = testDecisionContext(this, view)
                override fun actorToAct() = "p1"
            },
        ) + listOf(
            menu.copy(proposalVersion = "different-version"),
            menu.copy(proposalSeed = 2L),
            menu.copy(candidates = menu.candidates.take(1)),
        ).map { changed ->
            object : SearchWorld by FakeWorld() {
                override fun decisionContext(view: MenuRequest) = testDecisionContext(this, view)
                override fun expandChoices() = changed
            }
        }
        mismatches.forEach { changed ->
            val failure = assertFailsWith<InformationSetConformanceException> {
                search.search("p0", batch(listOf(FakeWorld(), changed)), 7L)
            }
            assertEquals(
                "Root particle 1 disagrees with root particle 0 about policy-visible state or semantic candidates",
                failure.message,
            )
        }
    }

    @Test
    fun `root conformance prepares retained belief particles before simulation forks`() {
        val roots = List(4) { FakeWorld() }
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 4,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafEvaluationMethod.CURRENT_INFORMATION_STATE,
                    ),
            ),
            opponentPolicy = UniformOpponentPolicy,

            valueSource = LeafValueSource.Information(testEvaluator()),
        )

        search.search("p0", batch(roots), 17L)

        assertTrue(roots.all { 64 in it.requestedLimits })
    }

    @Test
    fun `belief forks retain unequal weights and provenance while isolating particle worlds`() {
        val roots = List(2) { FakeWorld() }
        val initial = batch(roots)
        val belief = ParticleBelief.from(initial.copy(
            particles = listOf(Weighted(roots[0], 0.75), Weighted(roots[1], 0.25)),
            diagnostics = initial.diagnostics.copy(resamplingCount = 3, knowledgeDigest = "remembered-state")),
            BeliefMode.CONSISTENCY_ONLY_V1)
        val fork = belief.fork()
        val originalParticles = belief.weightedWorlds()
        val copiedParticles = fork.weightedWorlds()
        assertEquals(originalParticles.map { it.weight }, copiedParticles.map { it.weight })
        assertEquals(3, fork.resamplingCount)
        assertEquals(belief.mode, fork.mode)
        originalParticles.zip(copiedParticles).forEach { (original, copied) ->
            assertTrue(original.value !== copied.value)
            assertEquals(original.value.informationState("p0"), copied.value.informationState("p0"))
        }
        val expected = belief.propagateAndReweight("p0", fakeChoiceSignature("A"), updateSeed = 81L)
        val actual = fork.propagateAndReweight("p0", fakeChoiceSignature("A"), updateSeed = 81L)
        assertEquals(expected.diagnostics, actual.diagnostics)
        assertEquals("remembered-state", actual.diagnostics.knowledgeDigest)
        assertEquals(expected.belief.weightedWorlds().map { it.weight }, actual.belief.weightedWorlds().map { it.weight })
        assertTrue(copiedParticles.first().value.step(copiedParticles.first().value.expandChoices().candidates.first()).accepted)
        assertEquals(0, roots.first().depth)
    }

    @Test
    fun `distribution only observed conditioning preserves likelihoods and seeds`() {
        val roots = List(4) { FakeWorld(variant = if (it < 2) "low" else "high") }
        val seeds = mutableListOf<Long>()
        val model = object : ActionDistributionModel {
            override val id = "likelihood-only"
            override val distributionIsSeedInvariant = true
            override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.menu.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
                candidates: List<SemanticChoice>, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
                seeds += policySeed
                val p = if (opponentInformation.observation.step == "low") .25 else .75
                return ProbabilityDistribution.normalized(candidates.map {
                    ProbabilityMass(it, if (it.display.label == "A") p else 1 - p)
                })
            }
        }
        val result = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1)
            .conditionOnObservedAction("p0", fakeChoiceSignature("A"), model, 18L)
        val actualWeights = result.belief.weightedWorlds().map { it.weight }
        assertEquals(4, actualWeights.size)
        listOf(.125, .125, .375, .375).zip(actualWeights).forEach { (expected, actual) ->
            assertEquals(expected, actual, 1e-14)
        }
        assertEquals((0..3).map { ComponentSeeds.derive(18L, it, model.id, "likelihood") }, seeds)
        assertTrue(model.behaviorSpecification.distributionIsSeedInvariant)
    }

    @Test
    fun `private advance preserves legacy custom select override attribution and seeds`() {
        val seeds = mutableListOf<Pair<Long, Long>>()
        val policy = object : OpponentPolicy {
            override val id = "private-override"
            override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.menu.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
                candidates: List<SemanticChoice>, policySeed: Long): ProbabilityDistribution<SemanticChoice> =
                error("Private choices must honor select override")
            override fun select(context: DecisionContext, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision = select(context.information(), context.menu.candidates, policySeed, sampleSeed)

    fun select(opponentInformation: InformationStateRepresentation, candidates: List<SemanticChoice>,
                policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
                seeds += policySeed to sampleSeed
                return OpponentPolicyDecision(candidates.single { it.display.label == "B" },
                    OpponentPolicyDecisionDiagnostic(id, "custom-private-component"))
            }
        }
        val result = ParticleBelief.from(batch(List(4) { FakeWorld() }), BeliefMode.POLICY_CONDITIONED_V1)
            .propagateThroughHiddenChoice("p0", policy, 31L)
        assertEquals((0..3).map {
            ComponentSeeds.derive(31L, it, policy.id, "private-choice") to
                ComponentSeeds.derive(31L, it, "private-choice-sample")
        }, seeds)
        assertTrue(result.belief.weightedWorlds().all { it.value.informationState("p0").observation.step.endsWith(":B") })
        assertEquals(4, result.diagnostics.opponentPolicyDecisions.decisions)
        assertEquals(mapOf("custom-private-component" to 4), result.diagnostics.opponentPolicyDecisions.selectedComponents)
    }

    @Test
    fun `policy conditioning resamples deterministically after particle collapse`() {
        val roots = List(8) { index -> FakeWorld(variant = if (index == 0) "favored" else "unlikely") }
        val belief = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1)
        val policy = object : OpponentPolicy {
            override val id = "calibration-test"
            override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.menu.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
                candidates: List<SemanticChoice>,
                policySeed: Long,
            ): ProbabilityDistribution<SemanticChoice> {
                val probabilityA = if (opponentInformation.observation.step == "favored") 0.99 else 0.0001
                return ProbabilityDistribution.normalized(
                    listOf(
                        ProbabilityMass(candidates.single { it.display.label == "A" }, probabilityA),
                        ProbabilityMass(candidates.single { it.display.label == "B" }, 1.0 - probabilityA),
                    )
                )
            }
        }

        val first = belief.conditionOnObservedAction("p0", fakeChoiceSignature("A"), policy, updateSeed = 18L)
        val second = belief.conditionOnObservedAction("p0", fakeChoiceSignature("A"), policy, updateSeed = 18L)

        assertEquals(first.diagnostics, second.diagnostics)
        assertEquals(1, first.diagnostics.resamplingCount)
        assertEquals(8.0, first.diagnostics.effectiveSampleSizeAfter, absoluteTolerance = 1e-12)
        assertTrue(first.diagnostics.effectiveSampleSizeBefore < 4.0)
        assertTrue(first.belief.weightedWorlds().all { kotlin.math.abs(it.weight - 0.125) < 1e-12 })
    }

    @Test
    fun `consistency filtering restores the requested particle count`() {
        val roots = List(8) { index ->
            FakeWorld(candidateCount = if (index < 2) 2 else 1)
        }
        val belief = ParticleBelief.from(batch(roots), BeliefMode.CONSISTENCY_ONLY_V1)

        val update = belief.propagateAndReweight(
            actor = "p0",
            observedSignature = fakeChoiceSignature("B"),
            updateSeed = 71L,
        )

        assertEquals(8, update.belief.size)
        assertEquals(6, update.diagnostics.rejectedParticles)
        assertEquals(1, update.diagnostics.resamplingCount)
        assertEquals(8.0, update.diagnostics.effectiveSampleSizeAfter, absoluteTolerance = 1e-12)
    }

    @Test
    fun `safe information conditioning preserves compatible posterior worlds`() {
        val roots = List(8) { index ->
            FakeWorld(variant = if (index < 2) "observed" else "other")
        }
        val belief = ParticleBelief.from(batch(roots), BeliefMode.CONSISTENCY_ONLY_V1)
        val expectedDigest = roots.first().informationState("p0").informationStateDigest

        val update = belief.conditionOnInformationState(
            viewer = "p0",
            expectedInformationStateDigest = expectedDigest,
            updateSeed = 72L,
            updatedKnowledgeDigest = "knowledge-after-observation",
        )

        assertEquals(8, update.belief.size)
        assertEquals(6, update.diagnostics.rejectedParticles)
        assertEquals(1, update.diagnostics.resamplingCount)
        assertEquals("knowledge-after-observation", update.diagnostics.knowledgeDigest)
        assertTrue(update.belief.weightedWorlds().all {
            it.value.informationState("p0").informationStateDigest == expectedDigest
        })
    }

    @Test
    fun `unobserved private choices advance without conditioning on hidden response`() {
        val roots = List(4) { FakeWorld() }
        val belief = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1)
        val alwaysSecond = object : OpponentPolicy {
            override val id = "private-choice-test"
            override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.menu.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
                candidates: List<SemanticChoice>,
                policySeed: Long,
            ) = ProbabilityDistribution.normalized(candidates.map { candidate ->
                ProbabilityMass(candidate, if (candidate.display.label == "B") 1.0 else 0.0)
            })
        }

        val first = belief.propagateThroughHiddenChoice("p0", alwaysSecond, updateSeed = 31L)
        val second = belief.propagateThroughHiddenChoice("p0", alwaysSecond, updateSeed = 31L)

        assertEquals(first.diagnostics, second.diagnostics)
        assertEquals(0, first.diagnostics.rejectedParticles)
        assertEquals(4.0, first.diagnostics.effectiveSampleSizeAfter, absoluteTolerance = 1e-12)
        assertTrue(first.belief.weightedWorlds().all { (it.value as FakeWorld).depth == 1 })
    }

    @Test
    fun `unobserved private choices honor required policy annotations`() {
        val annotationTag = "private-choice-annotation"
        class AnnotationProbe(var calls: Int = 0)
        class AnnotatedPrivateChoiceWorld(
            private val probe: AnnotationProbe,
            var depth: Int = 0,
        ) : PolicyAnnotatedSearchWorld {
            private fun expansion(annotated: Boolean): ActionMenu {
                val ordinary = listOf(quiescenceChoice("A"), quiescenceChoice("B"))
                val candidates = if (!annotated) ordinary else ordinary.map { choice ->
                    if (choice.display.label == "B") {
                        choice.copy(display = choice.display.copy(policyTags = setOf(annotationTag)))
                    } else {
                        choice
                    }
                }
                return ActionMenu(candidates, true, candidates.size.toLong(), "private-choice-v1", 1L)
            }

            override fun actorToAct(): String = "p0"

            override fun informationState(viewer: String): InformationStateRepresentation {
                val candidates = expansion(annotated = false).candidates
                val observation = PlayerObservationSnapshot(
                    viewerId = viewer,
                    turnNumber = depth,
                    phase = "TEST",
                    step = "PRIVATE_CHOICE",
                    activePlayerId = "p0",
                    priorityPlayerId = "p0",
                    players = listOf(
                        PlayerView("p0", "Actor", 20, 1, 1, 0, 0, ManaPoolView(), true, true, false),
                        PlayerView("p1", "Viewer", 20, 1, 1, 0, 0, ManaPoolView(), false, false, false),
                    ),
                    zones = emptyList(),
                    stack = emptyList(),
                    pendingDecision = null,
                    observationDigest = CanonicalJson.sha256("private-choice:$viewer:$depth"),
                )
                return InformationStateRepresentation(
                    actingPlayerId = "p0",
                    observation = observation,
                    informationStateDigest = CanonicalJson.sha256("private-choice-info:$viewer:$depth"),
                    historyCommitment = HistoryHashChain.empty(),
                    history = emptyList(),
                    candidates = candidates,
                    terminated = false,
                )
            }

            override fun expandChoices(): ActionMenu = expansion(annotated = false)

            override fun decisionContext(view: MenuRequest): DecisionContext = testDecisionContext(this, view)

            override fun expandChoicesWithPolicyAnnotations(): ActionMenu {
                probe.calls++
                return expansion(annotated = true)
            }

            override fun expandChoicesWithPolicyAnnotations(limit: Int): ActionMenu =
                expandChoicesWithPolicyAnnotations()

            override fun step(choice: SemanticChoice): SearchStepResult {
                depth++
                return SearchStepResult(true)
            }

            override fun fork(): SearchWorld = AnnotatedPrivateChoiceWorld(probe, depth)

            override fun terminalPayoff(rootPlayer: String): Double? = null

        }
        val probes = List(4) { AnnotationProbe() }
        val roots = probes.map(::AnnotatedPrivateChoiceWorld)
        val belief = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1)
        val requiresAnnotation = object : OpponentPolicy {
            override val id: String = "requires-private-choice-annotation"
            override val requiresArgentumAiChoiceTag: Boolean = true

            override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.menu.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
                candidates: List<SemanticChoice>,
                policySeed: Long,
            ): ProbabilityDistribution<SemanticChoice> {
                val selected = candidates.single { annotationTag in it.display.policyTags }
                return ProbabilityDistribution.normalized(candidates.map { candidate ->
                    ProbabilityMass(candidate, if (candidate.signature == selected.signature) 1.0 else 0.0)
                })
            }
        }

        val update = belief.propagateThroughHiddenChoice("p0", requiresAnnotation, updateSeed = 32L)

        assertEquals(4, probes.sumOf { it.calls })
        assertEquals(4, update.diagnostics.opponentPolicyDecisions.decisions)
        assertEquals(0, update.diagnostics.opponentPolicyDecisions.replacementDecisions)
        assertTrue(update.belief.weightedWorlds().all {
            (it.value as AnnotatedPrivateChoiceWorld).depth == 1
        })
    }

    @Test
    fun `observed choices are resolved through progressive expansion`() {
        val roots = List(4) { FakeWorld(candidateCount = 140) }
        val belief = ParticleBelief.from(batch(roots), BeliefMode.CONSISTENCY_ONLY_V1)

        val update = belief.propagateAndReweight("p0", fakeChoiceSignature("C100"), updateSeed = 44L)

        assertEquals(0, update.diagnostics.rejectedParticles)
        assertTrue(update.belief.weightedWorlds().all { (it.value as FakeWorld).depth == 1 })
    }

    private fun coreSearch(
        config: InformationSetSearchConfig,
        opponentPolicy: OpponentPolicy,
        rolloutPolicy: OpponentPolicy = UniformOpponentPolicy,
        rolloutOpponentPolicy: OpponentPolicy = UniformOpponentPolicy,
        valueSource: LeafValueSource = LeafValueSource.Information(testInformationEvaluator()),
    ): InformationSetSearch = InformationSetSearch(
        config = config,
        opponentPolicy = opponentPolicy,
        rolloutPolicy = rolloutPolicy,
        rolloutOpponentPolicy = rolloutOpponentPolicy,
        valueSource = valueSource,
    )

    @Test
    fun `numeric readout exposes actual filtered masses before systematic resampling`() {
        val roots = List(4) { FakeWorld(variant = "case-$it") }
        val input = batch(roots).copy(particles = roots.mapIndexed { i, w ->
            Weighted<SearchWorld>(w, listOf(.7, .2, .09, .01)[i])
        })
        var readout = emptyList<ParticleAdvanceMass>()
        val result = ParticleBelief.from(input, BeliefMode.POLICY_CONDITIONED_V1).propagateAndReweight(
            "p0", fakeChoiceSignature("A"), UniformOpponentPolicy, 18L,
            observation = ParticleObservationCondition(null) {
                (it as FakeWorld).depth == 1 && it.informationState("p0").observation.step != "case-1:A"
            }, preResamplingReadout = { readout = it })
        assertEquals(listOf(0, 2, 3), readout.map { it.inputIndex })
        readout.zip(listOf(.7, .09, .01)).forEach { (row, prior) ->
            assertEquals(prior * .5, kotlin.math.exp(row.logUnnormalizedWeight), 1e-12)
            assertEquals(prior / .8, row.posteriorWeight, 1e-12)
        }
        assertEquals(1, result.diagnostics.resamplingCount)
        assertTrue(result.belief.weightedWorlds().all { it.weight == .25 })
        assertTrue(roots.all { it.depth == 0 })
        var called = false
        assertFailsWith<ParticleDepletionException> {
            ParticleBelief.from(input, BeliefMode.POLICY_CONDITIONED_V1).propagateAndReweight(
                "p0", fakeChoiceSignature("A"), UniformOpponentPolicy, 18L,
                observation = ParticleObservationCondition(null) { false },
                preResamplingReadout = { called = true })
        }
        assertTrue(!called, "A refused update has no admitted posterior")
    }

    @Test
    fun `new observation preserves a rare descendant before action resampling can remove it`() {
        val roots = List(8) { FakeWorld(variant = if (it == 7) "rare" else "common") }
        val input = batch(roots).copy(particles = roots.mapIndexed { i, w ->
            Weighted<SearchWorld>(w, if (i == 0) 1.0 else 1e-12)
        })
        val belief = ParticleBelief.from(input, BeliefMode.POLICY_CONDITIONED_V1)
        val condition = ParticleObservationCondition("observed-knowledge") { w ->
            check((w as FakeWorld).depth == 1)
            w.informationState("p1").observation.step == "rare:A"
        }
        val late = belief.propagateAndReweight("p0", fakeChoiceSignature("A"), updateSeed = 18L)
        assertTrue(late.belief.weightedWorlds().none { condition.matches(it.value) })
        val early = belief.propagateAndReweight("p0", fakeChoiceSignature("A"), updateSeed = 18L, observation = condition)
        assertEquals(8, early.belief.size)
        assertEquals(7, early.diagnostics.rejectedParticles)
        assertEquals(7, early.diagnostics.failures["observationMismatchParticles"])
        assertEquals(1, early.diagnostics.resamplingCount)
        assertEquals("observed-knowledge", early.diagnostics.knowledgeDigest)
        assertTrue(early.belief.weightedWorlds().all { condition.matches(it.value) })
        assertTrue(roots.all { it.depth == 0 })
    }

    @Test
    fun `compatible descendants retain action likelihood weights without needless resampling`() {
        val roots = List(4) { FakeWorld(variant = if (it < 2) "low" else "high") }
        val policy = object : OpponentPolicy {
            override val id = "observation-likelihood-test"
            override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.menu.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
                candidates: List<SemanticChoice>, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
                assertEquals("p0", opponentInformation.observation.viewerId)
                assertEquals(0, opponentInformation.observation.turnNumber)
                val p = if (opponentInformation.observation.step == "low") .25 else .75
                return ProbabilityDistribution.normalized(candidates.map { c ->
                    ProbabilityMass(c, if (c.display.label == "A") p else 1 - p)
                })
            }
        }
        val result = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1).propagateAndReweight(
            "p0", fakeChoiceSignature("A"), policy, 18L,
            observation = ParticleObservationCondition("new-knowledge") { (it as FakeWorld).depth == 1 })
        val expected = listOf(.125, .125, .375, .375)
        result.belief.weightedWorlds().zip(expected).forEach { (w, p) -> assertEquals(p, w.weight, 1e-12) }
        assertEquals(0, result.diagnostics.resamplingCount)
        assertEquals(0, result.diagnostics.rejectedParticles)
    }

    @Test
    fun `no observation compatible descendant is depletion before any rejuvenation`() {
        val roots = List(4) { FakeWorld() }
        val belief = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1)
        var refreshes = 0
        val refresh = ParticleRejuvenator { w, _, _ -> refreshes++; w.fork() }
        val condition = ParticleObservationCondition("impossible") { w ->
            assertEquals(1, (w as FakeWorld).depth)
            false
        }
        assertFailsWith<ParticleDepletionException> {
            belief.propagateAndReweight("p0", fakeChoiceSignature("A"), updateSeed = 18L, rejuvenator = refresh, observation = condition)
        }
        assertFailsWith<ParticleDepletionException> {
            belief.propagateThroughHiddenChoice("p0", UniformOpponentPolicy, 18L, refresh, condition)
        }
        assertEquals(0, refreshes)
        assertTrue(roots.all { it.depth == 0 })
    }

    @Test
    fun `private sampled choices filter only their newly observed consequences`() {
        val roots = List(8) { FakeWorld(variant = if (it < 4) "left" else "right") }
        val policy = object : OpponentPolicy {
            override val id = "private-observation-test"
            override val requiresArgentumAiChoiceOnMenu = false
            override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.menu.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
                candidates: List<SemanticChoice>, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
                assertEquals("p0", opponentInformation.observation.viewerId)
                assertEquals(0, opponentInformation.observation.turnNumber)
                val chosen = if (opponentInformation.observation.step == "left") "A" else "B"
                return ProbabilityDistribution.normalized(candidates.map { c ->
                    ProbabilityMass(c, if (c.display.label == chosen) 1.0 else 0.0)
                })
            }
        }
        val result = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1).propagateThroughHiddenChoice(
            "p0", policy, 31L, observation = ParticleObservationCondition("private-safe-observation") { w ->
                check((w as FakeWorld).depth == 1)
                w.informationState("p1").observation.step == "right:B"
            })
        assertEquals(4, result.diagnostics.rejectedParticles)
        assertEquals(4, result.diagnostics.failures["observationMismatchParticles"])
        assertEquals(8, result.diagnostics.opponentPolicyDecisions.decisions)
        assertTrue(result.belief.weightedWorlds().all { it.value.informationState("p1").observation.step == "right:B" })
        assertTrue(roots.all { it.depth == 0 })
    }

    @Test
    fun `rejuvenation cannot silently invalidate the assimilated observation`() {
        val roots = List(8) { FakeWorld(variant = if (it == 7) "rare" else "other") }
        val belief = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1)
        val condition = ParticleObservationCondition(null) { w ->
            w.informationState("p1").observation.step == "rare:A"
        }
        val bad = ParticleRejuvenator { _, _, _ -> FakeWorld(depth = 1) }
        assertFailsWith<IllegalStateException> {
            belief.propagateAndReweight("p0", fakeChoiceSignature("A"), updateSeed = 18L, rejuvenator = bad, observation = condition)
        }
        val good = belief.propagateAndReweight("p0", fakeChoiceSignature("A"), updateSeed = 18L, observation = condition)
        assertTrue(good.belief.weightedWorlds().all { condition.matches(it.value) })
    }

    @Test
    fun `exact zero mass reports the complete population without smoothing or mutating roots`() {
        val roots = List(4) { FakeWorld() }
        val belief = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1)
        val exact = ExactObservedAction { world ->
            ExactObservedActionResolution.Matched(fakeChoiceSignature("A"), 0.0, "test-member-v1",
                world.decisionContext()) { error("Zero mass must not execute") }
        }
        val failure = assertFailsWith<ExactObservationDepletionException> {
            belief.propagateAndReweight("p0", fakeChoiceSignature("A"), UniformOpponentPolicy, 19L, exactAction = exact)
        }
        assertEquals(ExactObservationFailureKind.EXACT_MEMBER_ZERO_MASS, failure.report.kind)
        assertEquals(ExactObservationFailureCounts(4, exactMemberZeroMass = 4), failure.report.counts)
        assertTrue(roots.all { it.depth == 0 })
        assertEquals(4, belief.weightedWorlds().size)
    }

    @Test
    fun `unavailable exact correspondence retains every classified particle including survivors`() {
        val roots = List(5) { FakeWorld(variant = "case-$it") }
        val belief = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1)
        val exact = ExactObservedAction { world ->
            when (world.informationState("p0").observation.step) {
                "case-0" -> ExactObservedActionResolution.Unsupported("MISSING_HANDLE")
                "case-2" -> ExactObservedActionResolution.NativeRejected("illegal declaration")
                else -> ExactObservedActionResolution.Matched(fakeChoiceSignature("A"),
                    if (world.informationState("p0").observation.step == "case-1") 0.0 else 1.0,
                    "test-member-v1", world.decisionContext()) { child ->
                    child.step(child.expandChoices().candidates.single { it.signature == fakeChoiceSignature("A") })
                }
            }
        }
        val failure = assertFailsWith<UnsupportedObservedActionException> {
            belief.propagateAndReweight("p0", fakeChoiceSignature("A"), UniformOpponentPolicy, 20L,
                observation = ParticleObservationCondition(null) {
                    it.informationState("p0").observation.step != "case-3:A"
                }, exactAction = exact)
        }
        val report = assertNotNull(failure.report)
        assertEquals(ExactObservationFailureKind.UNAVAILABLE_CORRESPONDENCE, report.kind)
        assertEquals(ExactObservationFailureCounts(5, unavailableCorrespondence = 1,
            exactMemberZeroMass = 1, nativeRejection = 1, incompatibleSuccessor = 1, surviving = 1), report.counts)
        assertEquals(mapOf("MISSING_HANDLE" to 1), report.correspondenceReasons)
        assertTrue(roots.all { it.depth == 0 })
        assertEquals(5, belief.weightedWorlds().size)
    }

    @Test
    fun `native rejection and incompatible exact successors have distinct exhaustion reasons`() {
        for (reject in listOf(true, false)) {
            val roots = List(3) { FakeWorld(rejectAtDepth = if (reject) 0 else null) }
            val exact = ExactObservedAction { world ->
                ExactObservedActionResolution.Matched(fakeChoiceSignature("A"), 1.0, "test-member-v1",
                    world.decisionContext()) { child ->
                    child.step(child.expandChoices().candidates.single { it.signature == fakeChoiceSignature("A") })
                }
            }
            val failure = assertFailsWith<ExactObservationDepletionException> {
                ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1).propagateAndReweight(
                    "p0", fakeChoiceSignature("A"), UniformOpponentPolicy, 21L,
                    observation = ParticleObservationCondition(null) { false }, exactAction = exact)
            }
            assertEquals(if (reject) ExactObservationFailureKind.NATIVE_REJECTION
                else ExactObservationFailureKind.INCOMPATIBLE_SUCCESSOR, failure.report.kind)
            assertEquals(if (reject) ExactObservationFailureCounts(3, nativeRejection = 3)
                else ExactObservationFailureCounts(3, incompatibleSuccessor = 3), failure.report.counts)
            assertTrue(roots.all { it.depth == 0 })
        }
    }

    private fun batch(worlds: List<SearchWorld>): ParticleSet<Weighted<SearchWorld>> = ParticleSet(
        particles = worlds.map { Weighted(it, 1.0 / worlds.size) },
        diagnostics = BeliefDiagnostics(
            mode = BeliefMode.CONSISTENCY_ONLY_V1,
            requestedParticles = worlds.size,
            acceptedParticles = worlds.size,
            rejectedParticles = 0,
            effectiveSampleSizeBefore = worlds.size.toDouble(),
            effectiveSampleSizeAfter = worlds.size.toDouble(),
            entropy = kotlin.math.ln(worlds.size.toDouble()),
            resamplingCount = 0,
        ),
    )
}

private fun testEvaluator(): InformationStateEvaluator =
    object : InformationStateEvaluator {
        override val id: String = "test-information-evaluator"
        override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double = 0.0
    }

private enum class QuiescenceBranch { FORCED_PASS, REAL_BRANCH, SINGLETON_MANA, ENDLESS_PASS, ALTERNATING_PASS }

private class QuiescenceProbe {
    val evaluatedStages = mutableListOf<Int>()
    val evaluatedCandidateCounts = mutableListOf<Int>()
}

private fun recordingEvaluator(
    probe: QuiescenceProbe,
    evaluatorId: String = "test-recording-evaluator",
) = object : InformationStateEvaluator {
    override val id: String = evaluatorId

    override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double {
        probe.evaluatedStages += information.observation.turnNumber
        probe.evaluatedCandidateCounts += information.candidates.size
        return 0.25
    }
}

private class QuiescenceWorld(
    private val probe: QuiescenceProbe,
    private val branch: QuiescenceBranch,
    private var stage: Int = 0,
    private var rootChoice: String? = null,
    private val rejectAtStage: Int? = null,
    private val terminalAtStage: Int? = null,
    private val volatileThroughStage: Int? = null,
    private val alternatingActors: Boolean = false,
    /** A lone pass also has an untapped mana source that the action-space profile omits. */
    private val profilePrunedPasses: Boolean = false,
    private val quiet: Boolean = false,
) : SearchWorld {
    override fun decisionContext(view: MenuRequest): DecisionContext = testDecisionContext(this, view)
    override fun actorToAct(): String = if (alternatingActors && stage % 2 == 1) "p1" else "p0"

    override fun informationState(viewer: String): InformationStateRepresentation {
        val menu = expandChoices()
        val volatile = !quiet && (stage == 1 || branch == QuiescenceBranch.ENDLESS_PASS && stage > 0 ||
            volatileThroughStage?.let { stage in 1..it } == true)
        val observation = PlayerObservationSnapshot(
            viewerId = viewer,
            turnNumber = stage,
            phase = if (volatile) "COMBAT" else "TEST",
            step = if (volatile) {
                "COMBAT_DAMAGE"
            } else {
                "QUIET"
            },
            activePlayerId = "p0",
            priorityPlayerId = "p0",
            players = listOf(
                PlayerView("p0", "Root", 20, 0, 0, 0, 0, ManaPoolView(), true, true, false),
                PlayerView("p1", "Opponent", 20, 0, 0, 0, 0, ManaPoolView(), false, false, false),
            ),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            observationDigest = CanonicalJson.sha256("quiescence:$viewer:$stage:$rootChoice:$branch") + ":test-value:0.5",
        )
        return InformationStateRepresentation(
            actingPlayerId = actorToAct(),
            observation = observation,
            informationStateDigest = CanonicalJson.sha256("quiescence-info:$viewer:$stage:$rootChoice:$branch"),
            historyCommitment = HistoryHashChain.empty(),
            history = emptyList(),
            candidates = menu.candidates,
            terminated = false,
        )
    }

    override fun expandChoices(): ActionMenu {
        val candidates = when {
            stage == 0 -> listOf(quiescenceChoice("A"), quiescenceChoice("B"))
            branch == QuiescenceBranch.ENDLESS_PASS ||
                branch == QuiescenceBranch.ALTERNATING_PASS && stage % 2 == 1 -> listOf(
                quiescenceChoice("Pass", SemanticOperationFamily.PASS_PRIORITY)
            )
            stage == 2 -> listOf(quiescenceChoice("A"), quiescenceChoice("B"))
            else -> when (branch) {
                QuiescenceBranch.FORCED_PASS -> listOf(
                    quiescenceChoice("Pass", SemanticOperationFamily.PASS_PRIORITY)
                )
                QuiescenceBranch.REAL_BRANCH,
                QuiescenceBranch.ALTERNATING_PASS -> listOf(quiescenceChoice("X"), quiescenceChoice("Y"))
                QuiescenceBranch.SINGLETON_MANA -> listOf(
                    quiescenceChoice("Float red", SemanticOperationFamily.MANA_ABILITY)
                )
                QuiescenceBranch.ENDLESS_PASS -> error("handled above")
            }
        }
        if (profilePrunedPasses && candidates.singleOrNull()?.operationFamily == SemanticOperationFamily.PASS_PRIORITY) {
            return ActionMenu(candidates, false, candidates.size.toLong(), "quiescence-v1", 1L,
                isProfileExhaustive = true,
                omissionReasons = setOf(ActionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA))
        }
        return ActionMenu(candidates, true, candidates.size.toLong(), "quiescence-v1", 1L)
    }

    override fun step(choice: SemanticChoice): SearchStepResult {
        if (choice.signature !in expandChoices().candidates.map { it.signature }) return SearchStepResult(false)
        if (stage == rejectAtStage) {
            return SearchStepResult(false, "simulated transition failure at stage $stage")
        }
        if (stage == 0) rootChoice = choice.display.label
        stage++
        return SearchStepResult(true)
    }

    override fun fork(): SearchWorld = QuiescenceWorld(
        probe, branch, stage, rootChoice, rejectAtStage, terminalAtStage, volatileThroughStage, alternatingActors,
        profilePrunedPasses, quiet,
    )

    override fun terminalPayoff(rootPlayer: String): Double? =
        if (terminalAtStage != null && stage >= terminalAtStage) 1.0 else null

}

private fun quiescenceChoice(
    label: String,
    family: SemanticOperationFamily = SemanticOperationFamily.OTHER,
) = SemanticChoice.create(
    kind = SemanticChoiceKind.ACTION,
    operationFamily = family,
    display = SemanticChoiceDisplay(label),
    canonicalPayload = buildJsonObject { put("choice", JsonPrimitive(label)) },
)

private class RecordingPolicy(override val id: String) : OpponentPolicy {
    val actors = mutableListOf<String?>()

    override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.menu.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> {
        actors += opponentInformation.actingPlayerId
        return ProbabilityDistribution.normalized(candidates.mapIndexed { index, candidate ->
            ProbabilityMass(candidate, if (index == 0) 1.0 else 0.0)
        })
    }
}

private class AuditedReplacementPolicy(
    private val disposition: OpponentPolicyReplacementEvidenceDisposition,
) : OpponentPolicy {
    override val id: String = "audited-replacement-policy"
    override val distributionIsSeedInvariant: Boolean = true

    override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> = distribution(context.information(), context.menu.candidates, policySeed)

    fun distribution(opponentInformation: InformationStateRepresentation,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> = ProbabilityDistribution.uniform(candidates)

    override fun decisionDiagnostic(context: DecisionContext, chosen: SemanticChoice, policySeed: Long, attributionSeed: Long): OpponentPolicyDecisionDiagnostic = decisionDiagnostic(context.information(), context.menu.candidates, chosen, policySeed, attributionSeed)

    fun decisionDiagnostic(opponentInformation: InformationStateRepresentation,
        candidates: List<SemanticChoice>,
        chosen: SemanticChoice,
        policySeed: Long,
        attributionSeed: Long,
    ): OpponentPolicyDecisionDiagnostic = OpponentPolicyDecisionDiagnostic(
        declaredPolicyId = id,
        selectedComponentId = "engine-component",
        effectivePolicyId = "typed-intent-replacement",
        replacement = OpponentPolicyReplacementDiagnostic(
            triggerId = "annotation-unavailable",
            replacementPolicyId = "typed-intent-replacement",
            evidenceDisposition = disposition,
        ),
    )
}

private class FakeWorld(
    private val candidateCount: Int = 2,
    private val variant: String = "default",
    var depth: Int = 0,
    private var firstChoice: String? = null,
    val requestedLimits: MutableList<Int> = mutableListOf(),
    private val hiddenVariant: String = "hidden-default",
    private val rejectAtDepth: Int? = null,
    private val valueForA: Double = 0.8,
    private val valueForB: Double = -0.2,
    private val terminalAtDepth: Int? = null,
) : ProgressiveSearchWorld {
    override fun decisionContext(view: MenuRequest): DecisionContext = testDecisionContext(this, view)
    override fun actorToAct(): String? = if (depth % 2 == 0) "p0" else "p1"

    override fun informationState(viewer: String): InformationStateRepresentation {
        val menu = expandChoices()
        // Keep the root observation identical across worlds with different later test scores.
        val visibleValue = when (firstChoice) {
            null -> 0.0
            "A" -> valueForA
            else -> valueForB
        }
        val observation = PlayerObservationSnapshot(
            viewerId = viewer,
            turnNumber = depth,
            phase = "TEST",
            step = rootChoiceStep(variant, firstChoice),
            activePlayerId = "p0",
            priorityPlayerId = actorToAct(),
            players = listOf(
                PlayerView("p0", "Root", 20, 7, 50, 0, 0, ManaPoolView(), true, actorToAct() == "p0", false),
                PlayerView("p1", "Opponent", 20, 7, 50, 0, 0, ManaPoolView(), false, actorToAct() == "p1", false),
            ),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            observationDigest = CanonicalJson.sha256("$viewer:$depth:$variant:$firstChoice") +
                ":test-value:$visibleValue",
        )
        return InformationStateRepresentation(
            actingPlayerId = actorToAct(),
            observation = observation,
            informationStateDigest = CanonicalJson.sha256("info:$viewer:$depth:$variant:$firstChoice:$visibleValue"),
            historyCommitment = HistoryHashChain.empty(),
            history = emptyList(),
            candidates = if (viewer == actorToAct()) menu.candidates else emptyList(),
            terminated = false,
        )
    }

    override fun expandChoices(): ActionMenu = expansion(minOf(64, candidateCount))

    override fun expandChoices(limit: Int): ActionMenu {
        requestedLimits += limit
        return expansion(minOf(limit, candidateCount))
    }

    private fun expansion(limit: Int): ActionMenu {
        val choices = if (candidateCount == 2) {
            listOf(quiescenceChoice("A"), quiescenceChoice("B"))
        } else {
            (0 until limit).map { quiescenceChoice("C$it") }
        }
        return ActionMenu(
            choices,
            isExhaustive = limit >= candidateCount,
            estimatedCandidateCount = candidateCount.toLong(),
            proposalVersion = "fake-v1",
            proposalSeed = 1L,
        )
    }

    override fun step(choice: SemanticChoice): SearchStepResult {
        if (depth == rejectAtDepth) {
            val diagnostic = if (depth == 0) {
                "simulated transition failure"
            } else {
                "simulated transition failure at depth $depth"
            }
            return SearchStepResult(false, diagnostic)
        }
        if (depth == 0) firstChoice = choice.display.label
        depth++
        return SearchStepResult(true)
    }

    override fun fork(): SearchWorld = FakeWorld(
        candidateCount,
        variant,
        depth,
        firstChoice,
        requestedLimits.toMutableList(),
        hiddenVariant,
        rejectAtDepth,
        valueForA,
        valueForB,
        terminalAtDepth,
    )

    override fun terminalPayoff(rootPlayer: String): Double? =
        1.0.takeIf { terminalAtDepth == depth }

}

private fun rootChoiceStep(variant: String, firstChoice: String?): String =
    firstChoice?.let { "$variant:$it" } ?: variant

private fun fakeChoiceSignature(label: String): String = SemanticChoice.computeSignature(
    SemanticOperationFamily.OTHER,
    buildJsonObject { put("choice", JsonPrimitive(label)) },
)

/** Every menu omits something: intentionally under the profile, or because enumeration was bounded. */
private class OmittingProgressiveWorld(
    private val world: FakeWorld,
    private val profileExhaustive: Boolean,
    private val limits: MutableList<Int>,
) : ProgressiveSearchWorld by world {
    override fun decisionContext(view: MenuRequest) = testDecisionContext(this, view)
    override fun fork(): SearchWorld = OmittingProgressiveWorld(world.fork() as FakeWorld, profileExhaustive, limits)
    override fun expandChoices(): ActionMenu = omitting(world.expandChoices())
    override fun expandChoices(limit: Int): ActionMenu {
        limits += limit
        return omitting(world.expandChoices(limit))
    }
    private fun omitting(menu: ActionMenu) = menu.copy(isExhaustive = false,
        isProfileExhaustive = profileExhaustive, omissionReasons = setOf(
            if (profileExhaustive) ActionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA
            else ActionOmissionReason.SOURCE_NON_EXHAUSTIVE))
}

private fun testDecisionContext(world: SearchWorld, view: MenuRequest): DecisionContext {
    val menu = when {
        world is PolicyAnnotatedSearchWorld && view.annotations ->
            view.limit?.let(world::expandChoicesWithPolicyAnnotations) ?: world.expandChoicesWithPolicyAnnotations()
        world is PolicyAnnotatedSearchWorld && view.admission == MenuSource.PRODUCTION ->
            view.limit?.let(world::expandChoicesForPolicyAdmission) ?: world.expandChoicesForPolicyAdmission()
        world is ProgressiveSearchWorld && view.limit != null -> world.expandChoices(requireNotNull(view.limit))
        else -> world.expandChoices()
    }
    val captured = world.fork()
    return DecisionContext.capture(requireNotNull(world.actorToAct()), menu,
        { captured.informationStateWithoutMenu(requireNotNull(captured.actorToAct())) }, view, menu.proposalVersion)
}

/** A test-only score carried by the player's synthetic observation. */
internal fun testInformationEvaluator() = object : InformationStateEvaluator {
    override val id = "synthetic-information-value-v1"
    override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double =
        information.observation.observationDigest.substringAfterLast(":test-value:", "0.0").toDouble()
}
