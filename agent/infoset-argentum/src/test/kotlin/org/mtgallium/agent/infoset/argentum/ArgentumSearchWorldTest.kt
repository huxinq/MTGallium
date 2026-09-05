package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.sth.StrongholdSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.GameRng
import com.wingedsheep.sdk.core.Zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.InformationSetSearch
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluationStrategy
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.LeafValueSource
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import org.mtgallium.agent.infoset.core.BeliefArchitecture

class ArgentumSearchWorldTest {
    private val deck = mapOf("Mountain" to 12, "Raging Goblin" to 8)
    private val cardRegistry = registry()

    private fun sampledWorldLeafStrategy() = LeafEvaluationStrategy(
        configuredEvaluatorId = LeafEvaluator.ARGENTUM_BOARD_V1.evaluatorId,
        source = LeafValueSource.SampledWorld(LeafEvaluator.ARGENTUM_BOARD_V1.evaluatorId),
    )

    private fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    private fun environment(
        cardRegistry: CardRegistry = this.cardRegistry,
        skipMulligans: Boolean = true,
    ): GameEnvironment =
        GameEnvironment.create(cardRegistry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                        PlayerConfig("Bob", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                    ),
                    seed = 811L,
                    skipMulligans = skipMulligans,
                    startingPlayerIndex = 0,
                )
            )
        }

    @Test
    fun `pure priority transfer keeps typed history without redundant catch-all transition`() {
        val world = ArgentumSearchWorld.create(
            environment(), "pure-priority", 89L,
            effectiveSetupSeed = 811L,
        )
        val beforeP0 = world.informationState("p0").history.size
        val beforeP1 = world.informationState("p1").history.size
        val pass = world.expandChoices().candidates.single {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        }

        val result = world.step(pass)

        assertTrue(result.accepted)
        assertEquals(listOf(PolicyHistoryEventKind.TURN_STRUCTURE), result.forcedTransitions.map { it.kind })
        assertEquals(
            listOf(PolicyHistoryEventKind.PRIORITY_PASS, PolicyHistoryEventKind.TURN_STRUCTURE),
            world.informationState("p0").history.drop(beforeP0).map { it.kind },
        )
        assertEquals(
            listOf(PolicyHistoryEventKind.PRIORITY_PASS, PolicyHistoryEventKind.TURN_STRUCTURE),
            world.informationState("p1").history.drop(beforeP1).map { it.kind },
        )
    }

    @Test
    fun `search world exposes only safe ids and forks independently`() {
        val env = environment()
        val world = ArgentumSearchWorld.create(
            env, "game-a", 90L,
            effectiveSetupSeed = 811L,
        )
        val parentInfo = world.informationState("p0")
        val child = world.fork()
        val choice = child.expandChoices().candidates.first()

        assertTrue(child.step(choice).accepted)
        assertEquals(parentInfo, world.informationState("p0"))
        assertNotEquals(parentInfo.historyDigest, child.informationState("p0").historyDigest)
        assertTrue(parentInfo.observation.zones.flatMap { it.cards }.all { it.objectRef.startsWith("zone:") })
    }

    @Test
    fun `exact forks preserve cached expansion semantics and invalidate after a step`() {
        val world = ArgentumSearchWorld.create(
            environment(), "cached-fork", 91L,
            effectiveSetupSeed = 811L,
        )
        val parentExpansion = world.expandChoices()
        val child = world.fork() as ArgentumSearchWorld

        assertEquals(parentExpansion, child.expandChoices())
        assertTrue(child.step(parentExpansion.candidates.first()).accepted)

        val nextExpansion = child.expandChoices()
        assertNotEquals(parentExpansion.proposalSeed, nextExpansion.proposalSeed)
        assertEquals(parentExpansion, world.expandChoices())
    }

    @Test
    fun `live resolution does not step and an observed raw action advances exactly once`() {
        val env = environment()
        val world = ArgentumSearchWorld.create(
            env, "live-resolution", 92L,
            effectiveSetupSeed = 811L,
        )
        val before = world.authoritativeFingerprint()
        val choice = world.expandChoices().candidates.first()

        val resolved = world.resolveChoice(choice) as ArgentumResolvedChoice.Action
        assertEquals(before, world.authoritativeFingerprint())

        val observed = world.applyObservedAction(resolved.value)
        assertEquals(choice.signature, observed.choice.signature)
        assertTrue(observed.result.accepted)
        assertNotEquals(before, world.authoritativeFingerprint())
        assertEquals(1, env.stepCount)
    }

    @Test
    fun `casting leaves every response opportunity for a later search choice`() {
        val cardRegistry = registry().apply { register(StrongholdSet.cards) }
        val responseDeck = mapOf("Mountain" to 12, "Shock" to 8)
        val env = GameEnvironment.create(cardRegistry).also { environment ->
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of(*responseDeck.entries.map { it.key to it.value }.toTypedArray())),
                        PlayerConfig("Bob", Deck.of(*responseDeck.entries.map { it.key to it.value }.toTypedArray())),
                    ),
                    seed = 813L,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                )
            )
        }
        val player = env.playerIds[0]
        var preparedState = env.state
        listOf("Mountain", "Shock").forEachIndexed { handIndex, cardName ->
            val currentHand = preparedState.getHand(player)
            val source = (preparedState.getHand(player) + preparedState.getLibrary(player)).first { id ->
                preparedState.getEntity(id)?.get<CardComponent>()?.name == cardName
            }
            if (source !in currentHand) {
                val target = currentHand[handIndex]
                val library = preparedState.getLibrary(player)
                preparedState = preparedState.copy(
                    zones = preparedState.zones +
                        (ZoneKey(player, Zone.HAND) to currentHand.map { if (it == target) source else it }) +
                        (ZoneKey(player, Zone.LIBRARY) to library.map { if (it == source) target else it })
                )
            }
        }
        env.restore(preparedState, env.playerIds, env.stepCount)
        val knownDecks = mapOf("p0" to responseDeck, "p1" to responseDeck)
        val world = ArgentumSearchWorld.create(
            env,
            "response-window",
            94L,
            effectiveSetupSeed = 813L,
            knownDecks = knownDecks,
        )
        var beforeLand = world.expandChoices().candidates
        repeat(12) {
            if (beforeLand.any { it.operationFamily == SemanticOperationFamily.PLAY_LAND }) return@repeat
            val pass = beforeLand.single { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
            assertTrue(world.step(pass).accepted)
            beforeLand = world.expandChoices().candidates
        }
        val land = beforeLand.firstOrNull {
            it.operationFamily == SemanticOperationFamily.PLAY_LAND
        } ?: error("No land play in ${beforeLand.map { it.operationFamily to it.display.label }}")
        assertTrue(world.step(land).accepted)
        val beforeCast = world.expandChoices().candidates
        val cast = beforeCast.firstOrNull {
            it.operationFamily == SemanticOperationFamily.CAST_SPELL
        } ?: error("No spell cast in ${beforeCast.map { it.operationFamily to it.display.label }}")

        val traced = world.stepWithReplayTrace(cast)

        assertTrue(traced.result.accepted)
        assertEquals("p0", world.actorToAct())
        assertEquals(1, world.informationState("p0").observation.stack.size)
        assertEquals(1, traced.rawTransitions.size)
        assertTrue(traced.rawTransitions.single().accepted)
        val casterPass = world.expandChoices().candidates.single {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        }
        assertTrue(world.step(casterPass).accepted)
        assertEquals("p1", world.actorToAct())
        assertEquals(1, world.informationState("p1").observation.stack.size)
    }

    @Test
    fun `private reuse identity is equality only and always redacted`() {
        val world = ArgentumSearchWorld.create(
            environment(), "reuse-key", 93L,
            effectiveSetupSeed = 811L,
        )
        val fork = world.fork() as ArgentumSearchWorld
        val key = world.privateSearchReuseKey()

        assertEquals(key, fork.privateSearchReuseKey())
        assertEquals("<private-search-world-key>", key.toString())
        assertTrue(fork.step(fork.expandChoices().candidates.first()).accepted)
        assertNotEquals(key, fork.privateSearchReuseKey())
    }

    @Test
    fun `observed mulligan and bottom-card actions match the current semantic choice`() {
        val cardRegistry = registry()
        val env = GameEnvironment.create(cardRegistry).also { environment ->
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                        PlayerConfig("Bob", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                    ),
                    seed = 812L,
                    skipMulligans = false,
                    startingPlayerIndex = 0,
                )
            )
        }
        val world = ArgentumSearchWorld.create(
            env, "live-mulligan", 93L,
            effectiveSetupSeed = 812L,
        )
        var tookMulligan = false
        var sawBottom = false

        repeat(12) {
            if (sawBottom) return@repeat
            val expansion = world.expandChoices()
            val picked = if (!tookMulligan) {
                expansion.candidates.firstOrNull {
                    (world.resolveChoice(it) as? ArgentumResolvedChoice.Action)?.value is TakeMulligan
                }?.also { tookMulligan = true }
            } else null
            val choice = picked ?: expansion.candidates.firstOrNull {
                (world.resolveChoice(it) as? ArgentumResolvedChoice.Action)?.value is BottomCards
            } ?: expansion.candidates.first { it.display.label.contains("Keep", ignoreCase = true) }
            val resolved = world.resolveChoice(choice) as ArgentumResolvedChoice.Action
            val observed = world.applyObservedAction(resolved.value)
            assertEquals(choice.signature, observed.choice.signature)
            if (resolved.value is BottomCards) {
                sawBottom = true
                return@repeat
            }
        }

        assertTrue(tookMulligan)
        assertTrue(sawBottom)
    }

    @Test
    fun `known-deck particles are reproducible and invariant to hidden truth`() {
        val cardRegistry = registry()
        val originalEnv = environment(cardRegistry)
        val viewer = originalEnv.playerIds[0]
        val opponent = originalEnv.playerIds[1]
        val hidden = originalEnv.state.getHand(opponent) + originalEnv.state.getLibrary(opponent)
        val first = hidden.first()
        val last = hidden.last()
        val firstCard = originalEnv.state.getEntity(first)!!.require<CardComponent>()
        val lastCard = originalEnv.state.getEntity(last)!!.require<CardComponent>()
        val permutedState = originalEnv.state
            .updateEntity(first) { it.with(lastCard) }
            .updateEntity(last) { it.with(firstCard) }
        val permutedEnv = originalEnv.fork().also {
            it.restore(permutedState, originalEnv.playerIds, originalEnv.stepCount)
        }
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        val leftRoot = ArgentumSearchWorld.create(
            originalEnv, "same-game", 4L,
            effectiveSetupSeed = 811L,
            knownDecks = knownDecks,
        )
        val rightRoot = ArgentumSearchWorld.create(
            permutedEnv, "same-game", 4L,
            effectiveSetupSeed = 811L,
            knownDecks = knownDecks,
        )
        val rootInfo = leftRoot.informationState("p0")
        assertEquals(rootInfo, rightRoot.informationState("p0"))
        val beforeState = originalEnv.state

        val left = ArgentumKnownDeckBeliefWorldSource(leftRoot)
            .sample(rootInfo, knownDecks, 29L, 16)
        val right = ArgentumKnownDeckBeliefWorldSource(rightRoot)
            .sample(rootInfo, knownDecks, 29L, 16)

        val leftOpponentViews = left.particles.map { it.value.informationState("p1").observation }
        val rightOpponentViews = right.particles.map { it.value.informationState("p1").observation }
        assertEquals(leftOpponentViews, rightOpponentViews)
        assertEquals(left.diagnostics, right.diagnostics)
        assertEquals(beforeState, originalEnv.state)
        assertEquals(beforeState.rng, originalEnv.state.rng)
        assertEquals(16.0, left.diagnostics.effectiveSampleSizeBefore)
        assertTrue(left.particles.all { particle ->
            (particle.value as ArgentumSearchWorld).knowledgeSupportFailure("p0", rootInfo) == null
        })

        val leftHybrid = ArgentumHybridBeliefWorldSource(leftRoot)
            .sample(rootInfo, knownDecks, 41L, 8)
        val rightHybrid = ArgentumHybridBeliefWorldSource(rightRoot)
            .sample(rootInfo, knownDecks, 41L, 8)
        val search = InformationSetSearch(
            InformationSetSearchConfig(
                simulations = 32,
                maxPolicyDecisions = 6,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            rolloutPolicy = UniformOpponentPolicy,
            rolloutOpponentPolicy = UniformOpponentPolicy,
            leafEvaluationStrategy = sampledWorldLeafStrategy(),
        )
        val leftResult = search.search("p0", leftHybrid, searchSeed = 57L)
        val rightResult = search.search("p0", rightHybrid, searchSeed = 57L)
        assertEquals(
            leftResult.copy(diagnostics = leftResult.diagnostics.copy(evaluatorNanos = 0)),
            rightResult.copy(diagnostics = rightResult.diagnostics.copy(evaluatorNanos = 0)),
        )
    }

    @Test
    fun `sampled future shuffle does not inherit the referee chance outcome`() {
        val cardRegistry = registry()
        val env = environment(cardRegistry, skipMulligans = false)
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        val root = ArgentumSearchWorld.create(
            env,
            "future-chance-boundary",
            101L,
            effectiveSetupSeed = 811L,
            knownDecks = knownDecks,
        )
        val information = root.informationState("p0")
        val before = env.state
        val sampled = ArgentumKnownDeckBeliefWorldSource(root)
            .sample(information, knownDecks, beliefSeed = 313L, count = 1)
            .particles.single().value as ArgentumSearchWorld
        val refereeContinuation = ArgentumSearchWorld.create(
            env.fork(),
            "future-chance-referee",
            101L,
            effectiveSetupSeed = 811L,
        )

        val refereePermutation = takeMulliganShufflePermutation(refereeContinuation)
        val sampledPermutation = takeMulliganShufflePermutation(sampled)

        // Before issue 0017, both transitions applied the same shuffle permutation because the
        // determinized state retained the referee's exact next GameRng state.
        assertNotEquals(refereePermutation, sampledPermutation)
        assertEquals(before, env.state)
        assertEquals(before.rng, env.state.rng)
    }

    @Test
    fun `sampled future shuffle streams are reproducible and ignore referee rng state`() {
        val cardRegistry = registry()
        val env = environment(cardRegistry, skipMulligans = false)
        val before = env.state
        val alternateEnv = env.fork().also { fork ->
            fork.restore(
                before.copy(rng = GameRng.seeded(919_191L)),
                env.playerIds,
                env.stepCount,
            )
        }
        val knownDecks = mapOf("p0" to deck, "p1" to deck)

        fun futureShuffleOutcomes(source: GameEnvironment): List<List<Int>> {
            val root = ArgentumSearchWorld.create(
                source,
                "reproducible-future-chance",
                202L,
                effectiveSetupSeed = 811L,
                knownDecks = knownDecks,
            )
            val information = root.informationState("p0")
            return ArgentumKnownDeckBeliefWorldSource(root)
                .sample(information, knownDecks, beliefSeed = 414L, count = 4)
                .particles
                .map { weighted ->
                    takeMulliganShufflePermutation(weighted.value as ArgentumSearchWorld)
                }
        }

        val first = futureShuffleOutcomes(env.fork())
        val reconstructed = futureShuffleOutcomes(env.fork())
        val differentRefereeStream = futureShuffleOutcomes(alternateEnv)

        assertEquals(first, reconstructed)
        assertEquals(first, differentRefereeStream)
        assertTrue(first.distinct().size > 1)
        assertEquals(before, env.state)
        assertEquals(GameRng.seeded(919_191L), alternateEnv.state.rng)
    }

    @Test
    fun `sampled world evaluator is an allowlist`() {
        val world = ArgentumSearchWorld.create(
            environment(), "game-eval", 2L,
            effectiveSetupSeed = 811L,
        )
        world.sampledWorldLeafValue("p0", ArgentumSearchWorld.ARGENTUM_BOARD_EVALUATOR_V1)
        assertFailsWith<IllegalArgumentException> {
            world.sampledWorldLeafValue("p0", "peek-at-referee")
        }
    }

    @Test
    fun `unsupported visibility states fail before policy projection`() {
        val env = environment()
        val cardId = env.state.getHand(env.playerIds[0]).first()

        fun rejected(state: com.wingedsheep.engine.state.GameState, expectedCode: String) {
            val fork = env.fork().also { it.restore(state, env.playerIds, env.stepCount) }
            val failure = assertFailsWith<UnsupportedInformationStateException> {
                ArgentumSearchWorld.create(
                    fork, "visibility-audit", 9L,
                    effectiveSetupSeed = 811L,
                ).informationState("p0")
            }
            assertEquals(listOf(expectedCode), failure.reasonCodes)
            assertTrue(cardId.value !in failure.message.orEmpty())
            assertTrue("Mountain" !in failure.message.orEmpty())
        }

        rejected(env.state.updateEntity(cardId) { it.with(FaceDownComponent) }, "FACE_DOWN_OBJECT")
        rejected(
            env.state.updateEntity(cardId) { it.with(RevealedToComponent.to(env.playerIds[1])) },
            "SELECTIVE_REVEAL_STATE",
        )
    }

    @Test
    fun `shared-tree search is reproducible and does not mutate its authoritative root`() {
        val cardRegistry = registry()
        val env = environment(cardRegistry)
        val root = ArgentumSearchWorld.create(
            env, "search-game", 17L,
            effectiveSetupSeed = 811L,
        )
        val rootInformation = root.informationState("p0")
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        val belief = ArgentumKnownDeckBeliefWorldSource(root)
            .sample(rootInformation, knownDecks, beliefSeed = 81L, count = 8)
        val search = InformationSetSearch(
            config = InformationSetSearchConfig(
                simulations = 64,
                maxPolicyDecisions = 8,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
            ),
            opponentPolicy = UniformOpponentPolicy,
            rolloutPolicy = UniformOpponentPolicy,
            rolloutOpponentPolicy = UniformOpponentPolicy,
            leafEvaluationStrategy = sampledWorldLeafStrategy(),
        )
        val before = env.state

        val first = search.search("p0", belief, searchSeed = 93L)
        val second = search.search("p0", belief, searchSeed = 93L)

        assertEquals(
            first.copy(diagnostics = first.diagnostics.copy(evaluatorNanos = 0)),
            second.copy(diagnostics = second.diagnostics.copy(evaluatorNanos = 0)),
        )
        assertEquals(64, first.candidates.sumOf { it.visits })
        assertEquals(before, env.state)
        assertEquals(before.rng, env.state.rng)
    }

    @Test
    fun `duplicate particles can be conditionally reshuffled without changing root information`() {
        val cardRegistry = registry()
        val env = environment(cardRegistry)
        val root = ArgentumSearchWorld.create(
            env, "rejuvenate-game", 19L,
            effectiveSetupSeed = 811L,
        )
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        val before = env.state

        val rejuvenated = ArgentumConditionalRejuvenator(knownDecks, "p0")
            .rejuvenate(root, duplicateIndex = 1, seed = 123L)

        assertEquals(root.informationState("p0"), rejuvenated.informationState("p0"))
        assertEquals(before, env.state)
        assertEquals(before.rng, env.state.rng)
    }

    @Test
    fun `determinized heuristic tag is information-safe and unique`() {
        val cardRegistry = registry()
        val originalEnv = environment(cardRegistry)
        val before = originalEnv.state
        val opponent = originalEnv.playerIds[1]
        val hidden = originalEnv.state.getHand(opponent) + originalEnv.state.getLibrary(opponent)
        val first = hidden.first()
        val last = hidden.last { id ->
            before.getEntity(id)!!.require<CardComponent>().name !=
                before.getEntity(first)!!.require<CardComponent>().name
        }
        val permuted = originalEnv.state
            .updateEntity(first) { container ->
                container.with(originalEnv.state.getEntity(last)!!.require<CardComponent>())
            }
            .updateEntity(last) { container ->
                container.with(originalEnv.state.getEntity(first)!!.require<CardComponent>())
            }
        val permutedEnv = originalEnv.fork().also {
            it.restore(permuted, originalEnv.playerIds, originalEnv.stepCount)
        }
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        fun world(env: GameEnvironment) = ArgentumSearchWorld.create(
            env,
            gameId = "tag-game",
            seedBase = 44L,
            effectiveSetupSeed = 811L,
            knownDecks = knownDecks,
        )
        fun tagged(world: ArgentumSearchWorld): List<String> {
            val diagnosis = world.determinizedHeuristicChoiceDiagnosis()
            assertEquals(null, diagnosis.unavailableReason)
            assertTrue(diagnosis.choice != null)
            return world.expandChoicesWithPolicyAnnotations().candidates.filter {
                ARGENTUM_HEURISTIC_CHOICE_TAG_V1 in it.display.policyTags
            }.map { it.signature }
        }

        val root = world(originalEnv)
        val left = tagged(root)
        val right = tagged(world(permutedEnv))

        assertEquals(1, left.size)
        assertEquals(left, right)
        // These uncached hypothetical worlds retain the root's annotator and factory. Alternate
        // hidden assignments so earlier annotations cannot supply decision memory to later ones.
        repeat(3) { index ->
            val sampled = root.withSampledState(
                if (index % 2 == 0) permuted else before,
                futureChanceStreamIdentity = 100L + index,
            )
            assertEquals(root.informationState("p0"), sampled.informationState("p0"))
            assertEquals(left, tagged(sampled))
        }
        assertEquals(before, originalEnv.state)
        assertEquals(before.rng, originalEnv.state.rng)
    }

    @Test
    fun `trusted attack outside capped family becomes a validated safe anchor`() {
        val cardRegistry = registry()
        val env = environment(cardRegistry)
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        var anchored: ArgentumHeuristicChoiceDiagnosis? = null

        repeat(600) {
            if (env.isTerminal || anchored != null) return@repeat
            val legal = env.legalActions()
            val declaration = legal.firstOrNull { action ->
                action.action is DeclareAttackers && action.validAttackers.orEmpty().size >= 2
            }
            if (declaration != null) {
                val diagnosticWorld = ArgentumSearchWorld.create(
                    environment = env.fork(),
                    gameId = "attack-anchor",
                    seedBase = 101L,
                    expander = UnifiedSemanticExpander(
                        actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
                    ),
                    effectiveSetupSeed = 811L,
                    knownDecks = knownDecks,
                )
                val diagnosis = diagnosticWorld.determinizedHeuristicChoiceDiagnosis(maxCandidates = 1)
                if (diagnosis.resolution == ArgentumHeuristicResolution.VALIDATED_ATTACK_ANCHOR) {
                    val choice = assertNotNull(diagnosis.choice)
                    assertEquals(true, diagnosis.selectedAcceptedBySampledState)
                    assertEquals(true, diagnosis.selectedAcceptedByAuthoritativeState)
                    assertEquals(1, diagnosticWorld.expandChoicesWithPolicyAnnotations(1).candidates.count {
                        ARGENTUM_HEURISTIC_CHOICE_TAG_V1 in it.display.policyTags
                    })
                    assertTrue(diagnosticWorld.step(choice).accepted)
                    anchored = diagnosis
                    return@repeat
                }
            }

            val next = legal.firstOrNull { it.affordable && it.action is PlayLand }
                ?: legal.firstOrNull { it.affordable && it.action is CastSpell }
                ?: legal.firstOrNull { it.action is DeclareAttackers }
                ?: legal.firstOrNull { it.action is PassPriority }
                ?: error("No deterministic test action at ${env.state.phase}/${env.state.step}")
            env.step(next.action)
        }

        assertEquals(
            ArgentumHeuristicResolution.VALIDATED_ATTACK_ANCHOR,
            assertNotNull(anchored).resolution,
        )
    }

    @Test
    fun `hybrid source gives rare tactical strata their exact mass`() {
        val cardRegistry = CardRegistry().apply {
            register(PortalSet.basicLands)
            register(StrongholdSet.cards)
        }
        val shockDeck = mapOf("Mountain" to 18, "Shock" to 2)
        val env = GameEnvironment.create(cardRegistry).also { environment ->
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of(*shockDeck.entries.map { it.key to it.value }.toTypedArray())),
                        PlayerConfig("Bob", Deck.of(*shockDeck.entries.map { it.key to it.value }.toTypedArray())),
                    ),
                    seed = 901L,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                )
            )
        }
        val knownDecks = mapOf("p0" to shockDeck, "p1" to shockDeck)
        val root = ArgentumSearchWorld.create(
            env,
            "hybrid-strata",
            8L,
            effectiveSetupSeed = 901L,
            knownDecks = knownDecks,
        )
        val information = root.informationState("p0")

        val batch = ArgentumHybridBeliefWorldSource(root)
            .sample(information, knownDecks, beliefSeed = 77L, count = 8)

        assertEquals(BeliefArchitecture.HYBRID_C_V1, batch.diagnostics.architecture)
        assertEquals(information.knowledge.knowledgeDigest, batch.diagnostics.knowledgeDigest)
        assertEquals(2, batch.diagnostics.strata.size)
        val present = batch.diagnostics.strata.single { ":hand-contains:Shock" in it.id }
        val actualPresent = batch.particles.filter { weighted ->
            val sampled = weighted.value as ArgentumSearchWorld
            val opponent = sampled.rawPlayerIds().getValue("p1")
            sampled.authoritativeState().getHand(opponent).any { id ->
                sampled.authoritativeState().getEntity(id)?.get<CardComponent>()?.name == "Shock"
            }
        }
        assertEquals(present.particles, actualPresent.size)
        assertEquals(present.exactMass, actualPresent.sumOf { it.weight }, absoluteTolerance = 1e-12)
        assertEquals(1.0, batch.particles.sumOf { it.weight }, absoluteTolerance = 1e-12)
        assertTrue(batch.particles.all { particle ->
            (particle.value as ArgentumSearchWorld).knowledgeSupportFailure("p0", information) == null
        })
    }

    private fun takeMulliganShufflePermutation(world: ArgentumSearchWorld): List<Int> {
        val actorAlias = requireNotNull(world.actorToAct())
        val actor = world.rawPlayerIds().getValue(actorAlias)
        val before = world.authoritativeState()
        val inputOrder = before.getLibrary(actor) + before.getHand(actor)
        val inputIndices = inputOrder.withIndex().associate { (index, id) -> id to index }
        val takeMulligan = world.expandChoices().candidates.single { choice ->
            (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value is TakeMulligan
        }

        assertTrue(world.step(takeMulligan).accepted)

        val after = world.authoritativeState()
        val outputOrder = after.getHand(actor) + after.getLibrary(actor)
        assertEquals(inputOrder.size, outputOrder.size)
        return outputOrder.map(inputIndices::getValue)
    }
}
