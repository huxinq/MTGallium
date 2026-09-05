package org.mtgallium.agent.infoset.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class InformationSetSearchTest {
    @Test
    fun `terminal continuation uses both fixed rollout seats and returns only actual payoff`() {
        val rootPolicy = RecordingPolicy("root-terminal-policy")
        val opponentPolicy = RecordingPolicy("opponent-terminal-policy")
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 1,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.BOUNDED_ROLLOUT,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            rolloutPolicy = rootPolicy,
            rolloutOpponentPolicy = opponentPolicy,
        )

        val continuation = search.continueFirstUnvisitedEdgeToTerminal(
            childWorld = FakeWorld(terminalAtDepth = 3),
            rootPlayer = "p0",
            searchSeed = 17L,
            simulationIndex = 2,
        )

        assertEquals(1.0, continuation.payoff)
        assertEquals(3, continuation.policyDecisions)
        assertEquals(2, continuation.rootPolicyDecisions.decisions)
        assertEquals(1, continuation.opponentPolicyDecisions.decisions)
        assertEquals(listOf<String?>("p0", "p0"), rootPolicy.actors)
        assertEquals(listOf<String?>("p1"), opponentPolicy.actors)
    }

    @Test
    fun `terminal continuation accepts payoff reached on the final permitted decision`() {
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 1,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.ARGENTUM_BOARD_V1),
            ),
            opponentPolicy = UniformOpponentPolicy,
        )
        val result = search.continueFirstUnvisitedEdgeToTerminal(
            childWorld = FakeWorld(terminalAtDepth = 1), rootPlayer = "p0",
            searchSeed = 18L, simulationIndex = 0, maximumContinuationPolicyDecisions = 1,
        )
        assertEquals(1.0, result.payoff)
        assertEquals(1, result.policyDecisions)
        assertEquals(1, result.rootPolicyDecisions.decisions)
    }

    @Test
    fun `terminal continuation exhaustion is a software failure rather than a value`() {
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 1,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.BOUNDED_ROLLOUT,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
        )

        val failure = assertFailsWith<IllegalStateException> {
            search.continueFirstUnvisitedEdgeToTerminal(
                childWorld = FakeWorld(), rootPlayer = "p0", searchSeed = 18L,
                simulationIndex = 0, maximumContinuationPolicyDecisions = 2,
            )
        }

        assertTrue(failure.message.orEmpty().contains("exhausted"))
    }

    @Test
    fun `first unvisited edge seam retains the production terminal bypass`() {
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 1,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.ARGENTUM_BOARD_V1),
            ),
            opponentPolicy = UniformOpponentPolicy,
        )

        val settlement = search.settleFirstUnvisitedEdge(
            childWorld = FakeWorld(terminalAtDepth = 0), rootPlayer = "p0", searchSeed = 17L, simulationIndex = 0,
        )

        assertEquals(SearchSettlement(1.0, SearchSettlementOrigin.TERMINAL_PAYOFF), settlement)
    }

    @Test
    fun `historical settlement counts retain no fabricated learned estimate`() {
        val historical = PolicyJson.format.decodeFromString<SearchSettlementCounts>(
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
            SearchCandidateStatistics(first, visits = 3, meanValue = -1.0, policyProbability = 0.3),
            SearchCandidateStatistics(second, visits = 2, meanValue = 1.0, policyProbability = 0.2),
        )
        assertEquals(first, byVisits.selectedSearchWinnerOrNull()?.choice)

        val byValue = byVisits.map { it.copy(visits = 3) }
        assertEquals(second, byValue.selectedSearchWinnerOrNull()?.choice)

        val tied = byValue.map { it.copy(meanValue = 0.5) }
        assertEquals(
            tied.minBy { it.choice.signature }.choice,
            tied.selectedSearchWinnerOrNull()?.choice,
        )
    }

    @Test
    fun `opponent replacement diagnostics preserve the exact sampled-decision denominator`() {
        fun run(disposition: OpponentPolicyReplacementEvidenceDisposition) = coreSearch(
            InformationSetSearchConfig(
                simulations = 32,
                maxPolicyDecisions = 2,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
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
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
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
    fun `candidate backup settlement counts classify terminal heuristic and unresolved leaves`() {
        fun search(
            world: SearchWorld,
            leaf: LeafEvaluationConfig,
            maxQuiescenceDecisions: Int = 32,
            maxQuiescenceForcedPasses: Int = 256,
        ) =
            coreSearch(
                InformationSetSearchConfig(
                    simulations = 4,
                    maxPolicyDecisions = 1,
                    maxQuiescenceDecisions = maxQuiescenceDecisions,
                    maxQuiescenceForcedPasses = maxQuiescenceForcedPasses,
                    leaf = leaf,
                ),
                UniformOpponentPolicy,
            ).search("p0", batch(listOf(world)), searchSeed = 701L)

        val terminal = search(
            FakeWorld(terminalAtDepth = 1),
            LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.ARGENTUM_BOARD_V1),
        )
        assertTrue(terminal.candidateSettlementCounts.values.all {
            it.terminalPayoffBackups == it.successfulBackups && it.heuristicSettlementBackups == 0
        })

        val heuristic = search(
            FakeWorld(),
            LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.ARGENTUM_BOARD_V1),
        )
        assertTrue(heuristic.candidateSettlementCounts.values.all {
            it.heuristicSettlementBackups == it.successfulBackups && it.terminalPayoffBackups == 0
        })

        val unresolved = search(
            QuiescenceWorld(QuiescenceProbe(), QuiescenceBranch.ENDLESS_PASS),
            LeafEvaluationConfig(LeafStateSource.CURRENT_INFORMATION_STATE, LeafEvaluator.MTGALLIUM_TACTICAL_V3),
            maxQuiescenceForcedPasses = 1,
        )
        assertTrue(unresolved.candidateSettlementCounts.values.all {
            it.neutralUnresolvedSettlementBackups == it.successfulBackups && it.heuristicSettlementBackups == 0
        })
    }

    @Test
    fun `settlement counts partition visits without changing deterministic search output`() {
        val config = InformationSetSearchConfig(
            simulations = 32,
            maxPolicyDecisions = 1,
            leaf = LeafEvaluationConfig(LeafStateSource.CURRENT_SAMPLED_WORLD, LeafEvaluator.ARGENTUM_BOARD_V1),
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
                LeafStateSource.CURRENT_SAMPLED_WORLD,
                LeafEvaluator.ARGENTUM_BOARD_V1,
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
        assertEquals(64, controlled.diagnostics.freshSimulations)
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
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
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
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
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
                    LeafStateSource.BOUNDED_ROLLOUT,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
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
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            informationEvaluator = recordingEvaluator(probe),
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
    fun `exact semantic prefix cache preserves the search result and removes repeated world steps`() {
        fun run(cache: Boolean) = coreSearch(
            InformationSetSearchConfig(
                simulations = 64,
                maxPolicyDecisions = 6,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
                cacheSimulationTransitions = cache,
            ),
            opponentPolicy = UniformOpponentPolicy,
        ).search("p0", batch(List(2) { FakeWorld() }), searchSeed = 19L)

        val uncached = run(cache = false)
        val cached = run(cache = true)

        assertEquals(uncached.chosen, cached.chosen)
        assertEquals(uncached.rootValue, cached.rootValue)
        assertEquals(uncached.candidates, cached.candidates)
        assertEquals(0, uncached.diagnostics.transitionCacheHits)
        assertEquals(0, uncached.diagnostics.transitionCacheSnapshots)
        assertTrue(cached.diagnostics.transitionCacheHits > 0)
        assertTrue(cached.diagnostics.searchWorldSteps < uncached.diagnostics.searchWorldSteps)
        assertTrue(cached.diagnostics.opponentDistributionCacheHits > 0)
    }

    @Test
    fun `session promotes posterior-compatible traces and refreshes horizon debt`() {
        val session = coreSession(
            config = InformationSetSearchConfig(
                simulations = 64,
                maxPolicyDecisions = 3,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            reuseConfig = InformationSetSearchReuseConfig(enabled = true),
        )
        val roots = List(8) { FakeWorld(hiddenVariant = "compatible") }
        session.search("p0", batch(roots), 101L, beliefContinuityEpoch = 0L)
        val promoted = List(8) {
            FakeWorld(hiddenVariant = "compatible").also { world ->
                repeat(2) { world.step(world.expandChoices().candidates.first()) }
            }
        }

        val result = session.search("p0", batch(promoted), 102L, beliefContinuityEpoch = 0L)

        assertTrue(result.diagnostics.reusedSimulations > 0)
        assertTrue(result.diagnostics.freshSimulations >= 16)
        assertEquals(
            64,
            result.diagnostics.freshSimulations + result.diagnostics.reusedSimulations,
        )
        assertTrue(result.diagnostics.refreshedSimulations > 0)
        assertEquals(64, result.candidates.sumOf { it.visits })
    }

    @Test
    fun `a rejected reuse frontier refresh aborts search instead of preserving a stale value`() {
        val session = coreSession(
            config = InformationSetSearchConfig(
                simulations = 64,
                maxPolicyDecisions = 3,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            reuseConfig = InformationSetSearchReuseConfig(enabled = true),
        )
        val roots = List(8) { FakeWorld(hiddenVariant = "compatible", rejectAtDepth = 3) }
        session.search("p0", batch(roots), 111L, beliefContinuityEpoch = 0L)
        val promoted = List(8) {
            FakeWorld(hiddenVariant = "compatible", rejectAtDepth = 3).also { world ->
                repeat(2) { world.step(world.expandChoices().candidates.first()) }
            }
        }

        val failure = assertFailsWith<RejectedSearchTransitionException> {
            session.search("p0", batch(promoted), 112L, beliefContinuityEpoch = 0L)
        }

        assertEquals("simulated transition failure at depth 3", failure.rejectionDiagnostic)
        assertTrue(failure.choiceSignature.isNotBlank())
    }

    @Test
    fun `session rejects matching public topology outside current posterior support`() {
        val session = reusableSession()
        val roots = List(8) { FakeWorld(hiddenVariant = "retained") }
        session.search("p0", batch(roots), 201L, beliefContinuityEpoch = 0L)
        val incompatible = List(8) {
            FakeWorld(hiddenVariant = "current").also { world ->
                repeat(2) { world.step(world.expandChoices().candidates.first()) }
            }
        }

        val result = session.search("p0", batch(incompatible), 202L, beliefContinuityEpoch = 0L)

        assertEquals(0, result.diagnostics.reusedSimulations)
        assertTrue((result.diagnostics.reuseDiscardReasons["POSTERIOR_SUPPORT_MISS"] ?: 0) > 0)
    }

    @Test
    fun `belief continuity epoch invalidates retained traces`() {
        val session = reusableSession()
        val roots = List(8) { FakeWorld(hiddenVariant = "stable") }
        val retained = session.search("p0", batch(roots), 301L, beliefContinuityEpoch = 0L)
        val promoted = List(8) {
            FakeWorld(hiddenVariant = "stable").also { world ->
                repeat(2) { world.step(world.expandChoices().candidates.first()) }
            }
        }

        val result = session.search("p0", batch(promoted), 302L, beliefContinuityEpoch = 1L)

        assertEquals(0, result.diagnostics.reusedSimulations)
        assertEquals(
            retained.diagnostics.retainedTraceCount,
            result.diagnostics.reuseDiscardReasons["BELIEF_CONTINUITY_CHANGED"],
        )
    }

    private fun reusableSession() = coreSession(
        config = InformationSetSearchConfig(
            simulations = 64,
            maxPolicyDecisions = 3,
            leaf = LeafEvaluationConfig(
                LeafStateSource.CURRENT_SAMPLED_WORLD,
                LeafEvaluator.ARGENTUM_BOARD_V1,
            ),
        ),
        opponentPolicy = UniformOpponentPolicy,
        reuseConfig = InformationSetSearchReuseConfig(enabled = true),
    )

    @Test
    fun `sampled-world source can deliberately project back to the visible evaluator`() {
        var visibleEvaluations = 0
        val visible = object : InformationStateEvaluator {
            override val id = LeafEvaluator.MTGALLIUM_VISIBLE_V2.evaluatorId
            override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double {
                visibleEvaluations++
                return if (information.observation.step.endsWith(":B")) 0.9 else -0.9
            }
        }
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 32,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            informationEvaluator = visible,
        )

        val result = search.search("p0", batch(List(2) { FakeWorld() }), 92L)

        assertEquals("B", result.chosen.display.label)
        assertTrue(visibleEvaluations > 0)
    }

    @Test
    fun `bounded rollout can score its nonterminal horizon with Argentum evaluator`() {
        val visible = object : InformationStateEvaluator {
            override val id = "must-not-run"
            override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double =
                error("Visible evaluator must not score the Argentum rollout cell")
        }
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 32,
                maxPolicyDecisions = 2,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.BOUNDED_ROLLOUT,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            rolloutPolicy = UniformOpponentPolicy,
            rolloutOpponentPolicy = UniformOpponentPolicy,
            informationEvaluator = visible,
        )

        val result = search.search("p0", batch(listOf(FakeWorld())), 93L)

        assertEquals("A", result.chosen.display.label)
        assertEquals(LeafEvaluator.ARGENTUM_BOARD_V1, result.diagnostics.leaf.evaluator)
    }

    @Test
    fun `non-exhaustive roots widen at the configured visit threshold`() {
        val root = FakeWorld(candidateCount = 140)
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 80,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
                wideningThresholds = listOf(64),
                wideningLimits = listOf(128),
            ),
            opponentPolicy = UniformOpponentPolicy,
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
                        LeafStateSource.CURRENT_INFORMATION_STATE,
                        LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                    ),
                    initialExpansionLimit = initialLimit,
                    wideningThresholds = listOf(4),
                    wideningLimits = listOf(256),
                ),
                opponentPolicy = UniformOpponentPolicy,
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
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
                initialExpansionLimit = 128,
                wideningThresholds = listOf(1),
                wideningLimits = listOf(256),
            ),
            opponentPolicy = UniformOpponentPolicy,
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
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
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
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
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
                    LeafStateSource.BOUNDED_ROLLOUT,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
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
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            informationEvaluator = recordingEvaluator(probe),
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
                        LeafStateSource.CURRENT_SAMPLED_WORLD,
                        LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                    ),
                ),
                opponentPolicy = UniformOpponentPolicy,
                informationEvaluator = recordingEvaluator(probe),
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
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            informationEvaluator = recordingEvaluator(probe),
        )

        val result = search.search("p0", batch(listOf(QuiescenceWorld(probe, QuiescenceBranch.ENDLESS_PASS))), 103L)

        assertEquals(2, result.diagnostics.quiescenceOverflows)
        assertEquals(2, result.diagnostics.quiescenceFallbacks)
        assertEquals(4, result.diagnostics.quiescenceForcedPasses)
    }

    @Test
    fun `v3 backs up neutral uncertainty instead of scoring an unresolved overflow`() {
        val probe = QuiescenceProbe()
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 2,
                maxPolicyDecisions = 1,
                maxQuiescenceForcedPasses = 2,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_TACTICAL_V3,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
        )

        val result = search.search(
            "p0",
            batch(listOf(QuiescenceWorld(probe, QuiescenceBranch.ENDLESS_PASS))),
            104L,
        )

        assertEquals(2, result.diagnostics.quiescenceOverflows)
        assertEquals(2, result.diagnostics.quiescenceUnresolvedBackups)
        assertEquals(0, result.diagnostics.evaluatorCalls)
        assertEquals(0.0, result.rootValue)
    }

    @Test
    fun `search fails closed when root particles disagree about visible information`() {
        val search = coreSearch(
            InformationSetSearchConfig(
                simulations = 4,
                maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
        )
        val belief = batch(listOf(FakeWorld(variant = "left"), FakeWorld(variant = "right")))

        assertFailsWith<InformationSetConformanceException> {
            search.search("p0", belief, 7L)
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
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
        )

        search.search("p0", batch(roots), 17L)

        assertTrue(roots.all { 64 in it.requestedLimits })
    }

    @Test
    fun `policy conditioning resamples deterministically after particle collapse`() {
        val roots = List(8) { index -> FakeWorld(variant = if (index == 0) "favored" else "unlikely") }
        val belief = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1)
        val policy = object : OpponentPolicy {
            override val id = "calibration-test"
            override fun distribution(
                opponentInformation: PolicyInformationState,
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

        val first = belief.observeAndStep("p0", fakeChoiceSignature("A"), policy, updateSeed = 18L)
        val second = belief.observeAndStep("p0", fakeChoiceSignature("A"), policy, updateSeed = 18L)

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

        val update = belief.advance(
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
            override fun distribution(
                opponentInformation: PolicyInformationState,
                candidates: List<SemanticChoice>,
                policySeed: Long,
            ) = ProbabilityDistribution.normalized(candidates.map { candidate ->
                ProbabilityMass(candidate, if (candidate.display.label == "B") 1.0 else 0.0)
            })
        }

        val first = belief.advanceUnobserved("p0", alwaysSecond, updateSeed = 31L)
        val second = belief.advanceUnobserved("p0", alwaysSecond, updateSeed = 31L)

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
            private fun expansion(annotated: Boolean): PolicyExpansion {
                val ordinary = listOf(quiescenceChoice("A"), quiescenceChoice("B"))
                val candidates = if (!annotated) ordinary else ordinary.map { choice ->
                    if (choice.display.label == "B") {
                        choice.copy(display = choice.display.copy(policyTags = setOf(annotationTag)))
                    } else {
                        choice
                    }
                }
                return PolicyExpansion(candidates, true, candidates.size.toLong(), "private-choice-v1", 1L)
            }

            override fun actorToAct(): String = "p0"

            override fun informationState(viewer: String): PolicyInformationState {
                val candidates = expansion(annotated = false).candidates
                val observation = PolicyObservation(
                    perspectivePlayerId = viewer,
                    turnNumber = depth,
                    phase = "TEST",
                    step = "PRIVATE_CHOICE",
                    activePlayerId = "p0",
                    priorityPlayerId = "p0",
                    players = listOf(
                        PolicyPlayerView("p0", "Actor", 20, 1, 1, 0, 0, PolicyManaPool(), true, true, false),
                        PolicyPlayerView("p1", "Viewer", 20, 1, 1, 0, 0, PolicyManaPool(), false, false, false),
                    ),
                    zones = emptyList(),
                    stack = emptyList(),
                    pendingDecision = null,
                    observationDigest = PolicyJson.sha256("private-choice:$viewer:$depth"),
                )
                return PolicyInformationState(
                    actingPlayerId = "p0",
                    observation = observation,
                    informationStateDigest = PolicyJson.sha256("private-choice-info:$viewer:$depth"),
                    historyCommitment = PolicyHistoryCommitment.empty(),
                    history = emptyList(),
                    candidates = candidates,
                    terminated = false,
                )
            }

            override fun expandChoices(): PolicyExpansion = expansion(annotated = false)

            override fun expandChoicesWithPolicyAnnotations(): PolicyExpansion {
                probe.calls++
                return expansion(annotated = true)
            }

            override fun expandChoicesWithPolicyAnnotations(limit: Int): PolicyExpansion =
                expandChoicesWithPolicyAnnotations()

            override fun step(choice: SemanticChoice): SearchStepResult {
                depth++
                return SearchStepResult(true)
            }

            override fun fork(): SearchWorld = AnnotatedPrivateChoiceWorld(probe, depth)

            override fun terminalPayoff(rootPlayer: String): Double? = null

            override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double = 0.0
        }
        val probes = List(4) { AnnotationProbe() }
        val roots = probes.map(::AnnotatedPrivateChoiceWorld)
        val belief = ParticleBelief.from(batch(roots), BeliefMode.POLICY_CONDITIONED_V1)
        val requiresAnnotation = object : OpponentPolicy {
            override val id: String = "requires-private-choice-annotation"

            override fun distribution(
                opponentInformation: PolicyInformationState,
                candidates: List<SemanticChoice>,
                policySeed: Long,
            ): ProbabilityDistribution<SemanticChoice> {
                val selected = candidates.single { annotationTag in it.display.policyTags }
                return ProbabilityDistribution.normalized(candidates.map { candidate ->
                    ProbabilityMass(candidate, if (candidate.signature == selected.signature) 1.0 else 0.0)
                })
            }
        }

        val update = belief.advanceUnobserved("p0", requiresAnnotation, updateSeed = 32L)

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

        val update = belief.advance("p0", fakeChoiceSignature("C100"), updateSeed = 44L)

        assertEquals(0, update.diagnostics.rejectedParticles)
        assertTrue(update.belief.weightedWorlds().all { (it.value as FakeWorld).depth == 1 })
    }

    private fun coreSearch(
        config: InformationSetSearchConfig,
        opponentPolicy: OpponentPolicy,
        rolloutPolicy: OpponentPolicy = UniformOpponentPolicy,
        rolloutOpponentPolicy: OpponentPolicy = UniformOpponentPolicy,
        informationEvaluator: InformationStateEvaluator = testEvaluator(config.leaf.evaluator),
        reuseConfig: InformationSetSearchReuseConfig = InformationSetSearchReuseConfig.DISABLED,
    ): InformationSetSearch = InformationSetSearch(
        config = config,
        opponentPolicy = opponentPolicy,
        rolloutPolicy = rolloutPolicy,
        rolloutOpponentPolicy = rolloutOpponentPolicy,
        leafEvaluationStrategy = testLeafEvaluationStrategy(config.leaf.evaluator, informationEvaluator),
        reuseConfig = reuseConfig,
    )

    private fun coreSession(
        config: InformationSetSearchConfig,
        opponentPolicy: OpponentPolicy,
        rolloutPolicy: OpponentPolicy = UniformOpponentPolicy,
        rolloutOpponentPolicy: OpponentPolicy = UniformOpponentPolicy,
        informationEvaluator: InformationStateEvaluator = testEvaluator(config.leaf.evaluator),
        reuseConfig: InformationSetSearchReuseConfig,
    ): InformationSetSearchSession = InformationSetSearchSession(
        config = config,
        opponentPolicy = opponentPolicy,
        rolloutPolicy = rolloutPolicy,
        rolloutOpponentPolicy = rolloutOpponentPolicy,
        leafEvaluationStrategy = testLeafEvaluationStrategy(config.leaf.evaluator, informationEvaluator),
        reuseConfig = reuseConfig,
    )

    private fun batch(worlds: List<SearchWorld>): BeliefBatch<Weighted<SearchWorld>> = BeliefBatch(
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

private fun testEvaluator(evaluator: LeafEvaluator): InformationStateEvaluator =
    object : InformationStateEvaluator {
        override val id: String = evaluator.evaluatorId
        override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double = 0.0
    }

private fun testLeafEvaluationStrategy(
    evaluator: LeafEvaluator,
    informationEvaluator: InformationStateEvaluator,
): LeafEvaluationStrategy = when (evaluator) {
    LeafEvaluator.MTGALLIUM_VISIBLE_V2 -> LeafEvaluationStrategy(
        evaluator.evaluatorId,
        LeafValueSource.Information(informationEvaluator),
    )
    LeafEvaluator.MTGALLIUM_TACTICAL_V3 -> LeafEvaluationStrategy(
        configuredEvaluatorId = evaluator.evaluatorId,
        source = LeafValueSource.Information(informationEvaluator),
        supportsTraceReuse = false,
        settleAtRolloutHorizon = true,
        unresolvedLeafHandling = UnresolvedLeafHandling.BACK_UP_NEUTRAL,
    )
    LeafEvaluator.MTGALLIUM_LEARNED_OUTCOME_V1 ->
        error("Core search fixtures do not construct Search Teacher checkpoint evaluators")
    LeafEvaluator.ARGENTUM_BOARD_V1 -> LeafEvaluationStrategy(
        evaluator.evaluatorId,
        LeafValueSource.SampledWorld(evaluator.evaluatorId),
    )
}

private enum class QuiescenceBranch { FORCED_PASS, REAL_BRANCH, SINGLETON_MANA, ENDLESS_PASS }

private class QuiescenceProbe {
    val evaluatedStages = mutableListOf<Int>()
}

private fun recordingEvaluator(probe: QuiescenceProbe) = object : InformationStateEvaluator {
    override val id: String = LeafEvaluator.MTGALLIUM_VISIBLE_V2.evaluatorId

    override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double {
        probe.evaluatedStages += information.observation.turnNumber
        return 0.25
    }
}

private class QuiescenceWorld(
    private val probe: QuiescenceProbe,
    private val branch: QuiescenceBranch,
    private var stage: Int = 0,
    private var rootChoice: String? = null,
    private val rejectAtStage: Int? = null,
) : SearchWorld {
    override fun actorToAct(): String = "p0"

    override fun informationState(viewer: String): PolicyInformationState {
        val expansion = expandChoices()
        val observation = PolicyObservation(
            perspectivePlayerId = viewer,
            turnNumber = stage,
            phase = if (stage == 1 || branch == QuiescenceBranch.ENDLESS_PASS && stage > 0) "COMBAT" else "TEST",
            step = if (stage == 1 || branch == QuiescenceBranch.ENDLESS_PASS && stage > 0) {
                "COMBAT_DAMAGE"
            } else {
                "QUIET"
            },
            activePlayerId = "p0",
            priorityPlayerId = "p0",
            players = listOf(
                PolicyPlayerView("p0", "Root", 20, 0, 0, 0, 0, PolicyManaPool(), true, true, false),
                PolicyPlayerView("p1", "Opponent", 20, 0, 0, 0, 0, PolicyManaPool(), false, false, false),
            ),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            observationDigest = PolicyJson.sha256("quiescence:$viewer:$stage:$rootChoice:$branch"),
        )
        return PolicyInformationState(
            actingPlayerId = "p0",
            observation = observation,
            informationStateDigest = PolicyJson.sha256("quiescence-info:$viewer:$stage:$rootChoice:$branch"),
            historyCommitment = PolicyHistoryCommitment.empty(),
            history = emptyList(),
            candidates = expansion.candidates,
            terminated = false,
        )
    }

    override fun expandChoices(): PolicyExpansion {
        val candidates = when {
            stage == 0 -> listOf(quiescenceChoice("A"), quiescenceChoice("B"))
            branch == QuiescenceBranch.ENDLESS_PASS -> listOf(
                quiescenceChoice("Pass", SemanticOperationFamily.PASS_PRIORITY)
            )
            stage == 2 -> listOf(quiescenceChoice("A"), quiescenceChoice("B"))
            else -> when (branch) {
                QuiescenceBranch.FORCED_PASS -> listOf(
                    quiescenceChoice("Pass", SemanticOperationFamily.PASS_PRIORITY)
                )
                QuiescenceBranch.REAL_BRANCH -> listOf(quiescenceChoice("X"), quiescenceChoice("Y"))
                QuiescenceBranch.SINGLETON_MANA -> listOf(
                    quiescenceChoice("Float red", SemanticOperationFamily.MANA_ABILITY)
                )
                QuiescenceBranch.ENDLESS_PASS -> error("handled above")
            }
        }
        return PolicyExpansion(candidates, true, candidates.size.toLong(), "quiescence-v1", 1L)
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

    override fun fork(): SearchWorld = QuiescenceWorld(probe, branch, stage, rootChoice, rejectAtStage)

    override fun terminalPayoff(rootPlayer: String): Double? = null

    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double = 0.5
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

    override fun distribution(
        opponentInformation: PolicyInformationState,
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

    override fun distribution(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> = ProbabilityDistribution.uniform(candidates)

    override fun decisionDiagnostic(
        opponentInformation: PolicyInformationState,
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
    var defaultExpansionCalls: Int = 0,
    private val hiddenVariant: String = "hidden-default",
    private val rejectAtDepth: Int? = null,
    private val valueForA: Double = 0.8,
    private val valueForB: Double = -0.2,
    private val terminalAtDepth: Int? = null,
) : ProgressiveSearchWorld, ReusableSearchWorld {
    override fun actorToAct(): String? = if (depth % 2 == 0) "p0" else "p1"

    override fun informationState(viewer: String): PolicyInformationState {
        val expansion = expandChoices()
        val observation = PolicyObservation(
            perspectivePlayerId = viewer,
            turnNumber = depth,
            phase = "TEST",
            step = rootChoiceStep(variant, firstChoice),
            activePlayerId = "p0",
            priorityPlayerId = actorToAct(),
            players = listOf(
                PolicyPlayerView("p0", "Root", 20, 7, 50, 0, 0, PolicyManaPool(), true, actorToAct() == "p0", false),
                PolicyPlayerView("p1", "Opponent", 20, 7, 50, 0, 0, PolicyManaPool(), false, actorToAct() == "p1", false),
            ),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            observationDigest = PolicyJson.sha256("$viewer:$depth:$variant:$firstChoice"),
        )
        return PolicyInformationState(
            actingPlayerId = actorToAct(),
            observation = observation,
            informationStateDigest = PolicyJson.sha256("info:$viewer:$depth:$variant:$firstChoice"),
            historyCommitment = PolicyHistoryCommitment.empty(),
            history = emptyList(),
            candidates = if (viewer == actorToAct()) expansion.candidates else emptyList(),
            terminated = false,
        )
    }

    override fun expandChoices(): PolicyExpansion {
        defaultExpansionCalls++
        return expansion(minOf(64, candidateCount))
    }

    override fun expandChoices(limit: Int): PolicyExpansion {
        requestedLimits += limit
        return expansion(minOf(limit, candidateCount))
    }

    private fun expansion(limit: Int): PolicyExpansion {
        val choices = if (candidateCount == 2) {
            listOf(choice("A"), choice("B"))
        } else {
            (0 until limit).map { choice("C$it") }
        }
        return PolicyExpansion(
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
        defaultExpansionCalls,
        hiddenVariant,
        rejectAtDepth,
        valueForA,
        valueForB,
        terminalAtDepth,
    )

    override fun privateSearchReuseKey(): SearchWorldReuseKey = SearchWorldReuseKey.fromTrustedDigest(
        "$hiddenVariant:$depth:$firstChoice",
    )

    override fun terminalPayoff(rootPlayer: String): Double? =
        1.0.takeIf { terminalAtDepth == depth }

    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double =
        if (firstChoice == "A") valueForA else valueForB

    private fun choice(signature: String) = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.OTHER,
        display = SemanticChoiceDisplay(signature),
        canonicalPayload = buildJsonObject { put("choice", JsonPrimitive(signature)) },
    )
}

private fun rootChoiceStep(variant: String, firstChoice: String?): String =
    firstChoice?.let { "$variant:$it" } ?: variant

private fun fakeChoiceSignature(label: String): String = SemanticChoice.computeSignature(
    SemanticOperationFamily.OTHER,
    buildJsonObject { put("choice", JsonPrimitive(label)) },
)
