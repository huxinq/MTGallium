package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.sdk.core.Zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.exactSingletonPassOrNull
import org.mtgallium.agent.infoset.core.policySingletonPassOrNull
import org.mtgallium.agent.infoset.core.PolicyExpansionOmissionReason

class UnifiedSemanticExpanderTest {
    private val registry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    private fun environment(skipMulligans: Boolean = true): GameEnvironment {
        return GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of("Mountain" to 12, "Raging Goblin" to 8)),
                        PlayerConfig("Bob", Deck.of("Mountain" to 12, "Raging Goblin" to 8)),
                    ),
                    seed = 17L,
                    skipMulligans = skipMulligans,
                    startingPlayerIndex = 0,
                )
            )
        }
    }

    @Test
    fun `expansion is deterministic duplicate-free and leaves parent and rng unchanged`() {
        val env = environment()
        val before = env.state
        val first = UnifiedSemanticExpander().expand(env, registry, proposalSeed = 99L)
        val second = UnifiedSemanticExpander().expand(env, registry, proposalSeed = 99L)

        assertEquals(first.policy, second.policy)
        assertEquals(before, env.state)
        assertEquals(before.rng, env.state.rng)
        assertEquals(
            first.policy.candidates.size,
            first.policy.candidates.map { it.signature }.distinct().size,
        )
        assertTrue(first.policy.candidates.isNotEmpty())
    }

    @Test
    fun `verbatim affordable engine actions admitted without a duplicate probe remain executable`() {
        val env = environment()
        var checked = 0
        repeat(160) {
            if (env.isTerminal) return@repeat
            val expansion = UnifiedSemanticExpander().expand(env, registry, proposalSeed = 100L + it)
            expansion.engineChoices.values.filterIsInstance<ArgentumEngineChoice.Action>()
                .filter { it.copiedFromLegalAction }
                .forEach { candidate ->
                    val child = env.fork()
                    child.step(candidate.value)
                    assertEquals(null, child.lastRejection, candidate.value.toString())
                    checked++
                }
            val next = env.legalActions().firstOrNull { it.affordable && it.action is PlayLand }
                ?: env.legalActions().firstOrNull { it.affordable && it.action is CastSpell }
                ?: env.legalActions().firstOrNull { it.affordable && it.action is PassPriority }
                ?: return@repeat
            env.step(next.action)
        }
        assertTrue(checked > 50, "Expected a substantial executable-action sample, got $checked")
    }

    @Test
    fun `mulligan keep and take are separate semantic choices`() {
        val env = environment(skipMulligans = false)
        val expansion = UnifiedSemanticExpander().expand(env, registry, proposalSeed = 3L)
        val names = expansion.engineChoices.values.mapNotNull { choice ->
            (choice as? ArgentumEngineChoice.Action)?.value?.let { it::class.simpleName }
        }.toSet()

        assertTrue("KeepHand" in names, "Expected KeepHand in $names")
        assertTrue("TakeMulligan" in names, "Expected TakeMulligan in $names")
        assertTrue(expansion.policy.candidates.all {
            it.operationFamily == SemanticOperationFamily.MULLIGAN
        })
        val actionIntents = expansion.engineChoices.mapNotNull { (signature, engineChoice) ->
            (engineChoice as? ArgentumEngineChoice.Action)?.value?.let { action ->
                action::class.simpleName to expansion.policy.candidates.single {
                    it.signature == signature
                }.actionIntent.kind
            }
        }.toMap()
        assertEquals(SemanticActionIntentKind.KEEP_HAND, actionIntents["KeepHand"])
        assertEquals(SemanticActionIntentKind.TAKE_MULLIGAN, actionIntents["TakeMulligan"])
    }

    @Test
    fun `operation families come from engine types and legal mana metadata`() {
        val env = environment()
        val initial = UnifiedSemanticExpander().expand(env, registry, proposalSeed = 31L)
        val bySignature = initial.policy.candidates.associateBy { it.signature }
        initial.engineChoices.forEach { (signature, engineChoice) ->
            val action = engineChoice as ArgentumEngineChoice.Action
            val expected = when (action.value) {
                is PassPriority -> SemanticOperationFamily.PASS_PRIORITY
                is PlayLand -> SemanticOperationFamily.PLAY_LAND
                is CastSpell -> SemanticOperationFamily.CAST_SPELL
                else -> if (action.isManaAbility) {
                    SemanticOperationFamily.MANA_ABILITY
                } else {
                    SemanticOperationFamily.OTHER
                }
            }
            assertEquals(expected, bySignature.getValue(signature).operationFamily)
        }

        var land: com.wingedsheep.engine.legalactions.LegalAction? = null
        for (ignored in 0 until 100) {
            land = env.legalActions().firstOrNull { it.affordable && it.action is PlayLand }
            if (land != null) break
            val pass = env.legalActions().firstOrNull { it.action is PassPriority }
                ?: error("Could not reach an ordinary land-play root")
            env.step(pass.action)
        }
        val legalLand = checkNotNull(land) { "Did not reach a legal land play" }
        env.step(legalLand.action)
        val afterLand = UnifiedSemanticExpander().expand(env, registry, proposalSeed = 32L)
        val manaSignatures = afterLand.engineChoices.filterValues {
            (it as? ArgentumEngineChoice.Action)?.isManaAbility == true
        }.keys
        assertTrue(manaSignatures.isNotEmpty())
        assertTrue(afterLand.policy.candidates.filter { it.signature in manaSignatures }.all {
            it.operationFamily == SemanticOperationFamily.MANA_ABILITY
        })
    }

    @Test
    fun `declare attackers creates a no-attack edge and attacking edges`() {
        val env = environment()
        var declarationFound = false
        for (ignored in 0 until 600) {
            val legal = env.legalActions()
            val declaration = legal.firstOrNull { action ->
                action.action is DeclareAttackers && action.validAttackers.orEmpty().isNotEmpty()
            }
            if (declaration != null) {
                val before = env.state
                val expansion = UnifiedSemanticExpander().expand(env, registry, proposalSeed = 7L)
                val attacks = expansion.engineChoices.mapNotNull { (signature, choice) ->
                    val attackers = ((choice as? ArgentumEngineChoice.Action)?.value as? DeclareAttackers)
                        ?.attackers ?: return@mapNotNull null
                    expansion.policy.candidates.single { it.signature == signature } to attackers
                }
                val noAttack = attacks.single { (_, attackers) -> attackers.isEmpty() }.first
                val attacking = attacks.filter { (_, attackers) -> attackers.isNotEmpty() }
                assertEquals("No attacks", noAttack.display.label)
                assertEquals(SemanticActionIntentKind.DECLINE_ATTACK, noAttack.actionIntent.kind)
                assertTrue(attacking.all { (choice, _) -> choice.display.label.startsWith("Attack with ") })
                assertTrue(attacking.all { (choice, _) -> "Raging Goblin" in choice.display.label })
                assertTrue(attacking.all { (choice, _) ->
                    choice.actionIntent.kind == SemanticActionIntentKind.DECLARE_ATTACKERS
                })
                assertTrue(attacks.map { it.second }.distinct().size > 1)
                assertEquals(before, env.state)
                declarationFound = true
                break
            }
            if (env.isTerminal) break

            val action = legal.firstOrNull { it.affordable && it.action is PlayLand }
                ?: legal.firstOrNull { candidate ->
                    if (!candidate.affordable || candidate.action !is CastSpell) return@firstOrNull false
                    val cardId = (candidate.action as CastSpell).cardId
                    env.state.getEntity(cardId)?.get<CardComponent>()?.name == "Raging Goblin"
                }
                ?: legal.firstOrNull { it.action is PassPriority }
                ?: legal.firstOrNull { it.affordable }
                ?: error("No action while driving to combat")
            env.step(action.action)
        }
        assertTrue(declarationFound, "Did not reach a combat root with a legal attacker")
    }

    @Test
    fun `choice payloads contain no per-step action ids`() {
        val expansion = UnifiedSemanticExpander().expand(environment(), registry, proposalSeed = 13L)
        assertFalse(expansion.policy.candidates.any { "actionId" in it.canonicalPayload })
    }

    @Test
    fun `interchangeable lands share one semantic action edge`() {
        val env = environment()
        val actor = env.playerIds[0]
        val mountains = env.state.getHand(actor).count { id ->
            env.state.getEntity(id)?.get<CardComponent>()?.name == "Mountain"
        }
        assertTrue(mountains > 1, "Fixture must contain interchangeable Mountains")

        repeat(100) {
            val rawLandActions = env.legalActions().count { it.action is PlayLand }
            if (rawLandActions > 1) {
                val expansion = UnifiedSemanticExpander().expand(env, registry, proposalSeed = 13L)
                val landEdges = expansion.engineChoices.values.filter {
                    (it as? ArgentumEngineChoice.Action)?.value is PlayLand
                }
                assertEquals(1, landEdges.size)
                return
            }
            val pass = env.legalActions().firstOrNull { it.action is PassPriority }
                ?: error("Could not reach an ordinary land-play root")
            env.step(pass.action)
        }
        error("Did not reach a land-play root")
    }

    @Test
    fun `semantic candidates ignore unordered visible-zone storage order`() {
        val original = environment()
        val actor = original.playerIds[0]
        val reordered = original.fork().also { env ->
            env.restore(
                original.state.copy(
                    zones = original.state.zones + (
                        ZoneKey(actor, Zone.HAND) to original.state.getHand(actor).reversed()
                    ),
                ),
                original.playerIds,
                original.stepCount,
            )
        }

        val left = UnifiedSemanticExpander().expand(original, registry, proposalSeed = 21L).policy
        val right = UnifiedSemanticExpander().expand(reordered, registry, proposalSeed = 21L).policy

        assertEquals(left, right)
    }

    @Test
    fun `Mono-Red fast profile suppresses standalone mana without claiming an exact forced pass`() {
        val registry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val env = GameEnvironment.create(registry).also { environment ->
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of("Mountain" to 60)),
                        PlayerConfig("Bob", Deck.of("Mountain" to 60)),
                    ),
                    seed = 117L,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                )
            )
        }
        var landPlayed = false
        for (ignored in 0 until 100) {
            val land = env.legalActions().firstOrNull { it.affordable && it.action is PlayLand }
            if (land != null) {
                env.step(land.action)
                landPlayed = true
                break
            }
            env.step(env.legalActions().single { it.action is PassPriority }.action)
        }
        assertTrue(landPlayed)

        val exact = UnifiedSemanticExpander().expand(env, registry, proposalSeed = 41L)
        val fast = UnifiedSemanticExpander(
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
        ).expand(env, registry, proposalSeed = 41L)
        val experimental = UnifiedSemanticExpander(
            actionSpaceProfile = SearchActionSpaceProfile.EXPERIMENTAL_STANDALONE_MANA_TIMING_V1,
        ).expand(env, registry, proposalSeed = 41L)

        assertTrue(exact.policy.candidates.any { it.operationFamily == SemanticOperationFamily.MANA_ABILITY })
        assertTrue(exact.policy.isExhaustive)
        assertEquals(exact.policy.candidates, experimental.policy.candidates)
        assertTrue(experimental.policy.isExhaustive)
        assertTrue(experimental.policy.isProfileExhaustive)
        assertTrue(experimental.policy.omissionReasons.isEmpty())
        assertFalse(SearchActionSpaceProfile.EXPERIMENTAL_STANDALONE_MANA_TIMING_V1.rulesEquivalent)
        assertTrue(experimental.policy.proposalVersion.endsWith("experimental-standalone-mana-timing-v1"))
        assertEquals(
            listOf(SemanticOperationFamily.PASS_PRIORITY),
            fast.policy.candidates.map { it.operationFamily },
        )
        assertFalse(fast.policy.isExhaustive)
        assertTrue(fast.policy.isProfileExhaustive)
        assertEquals(
            setOf(PolicyExpansionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA),
            fast.policy.omissionReasons,
        )
        assertEquals(null, fast.policy.exactSingletonPassOrNull())
        assertEquals(fast.policy.candidates.single(), fast.policy.policySingletonPassOrNull())
        assertTrue(fast.policy.proposalVersion.endsWith("mono-red-fast-mana-pruned-v1"))

        val manaAction = env.legalActions().single { it.isManaAbility }.action
        val world = ArgentumSearchWorld.create(
            environment = env,
            gameId = "fast-profile-observed-mana",
            seedBase = 41L,
            expander = UnifiedSemanticExpander(
                actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            ),
            cardRegistry = registry,
            effectiveSetupSeed = 117L,
        )
        val observed = world.applyObservedAction(manaAction)
        assertTrue(observed.result.accepted)
        assertEquals(SemanticOperationFamily.MANA_ABILITY, observed.choice.operationFamily)
        val information = world.informationState("p0")
        assertEquals(1, information.observation.players.single { it.playerId == "p0" }.mana.red)
        assertTrue(information.history.any {
            it.payload["operationFamily"]?.toString() == "\"MANA_ABILITY\""
        })
    }
}
