package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.ExactlyOneSubmissionResult
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumReplayStep
import org.mtgallium.agent.infoset.argentum.ArgentumRawTransition
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.monored.*
import kotlin.test.*

class LuckCorrectionTest {
    companion object { private val registry by lazy(::buildRegistry) }
    private fun world(hand: Int = 0, mulligans: Boolean = false): ArgentumSearchWorld {
        val deck = mapOf("Mountain" to 3, "Lightning Bolt" to 1)
        return createWorld(GameConfig(players = listOf(
            PlayerConfig("A", Deck.of("Mountain" to 3, "Lightning Bolt" to 1)),
            PlayerConfig("B", Deck.of("Mountain" to 3, "Lightning Bolt" to 1))),
            startingHandSize = hand, skipMulligans = !mulligans, useHandSmoother = false,
            startingPlayerIndex = 0, seed = 11), mapOf("p0" to deck, "p1" to deck), registry)
    }
    private fun pass(world: ArgentumSearchWorld) = world.expandChoices().candidates.first {
        it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
    }

    @Test fun `enumerating actual single draws gives nontrivial zero mean linear luck`() {
        val world = world()
        var found = false
        repeat(80) {
            if (found) return@repeat
            val before = world.fork() as ArgentumSearchWorld
            val choice = pass(world)
            val trace = world.stepWithReplayTrace(choice)
            val draws = trace.rawTransitions.flatMap { it.events }.filterIsInstance<CardsDrawnEvent>()
            if (draws.size != 1 || draws.single().cardIds.size != 1) return@repeat
            val owner = draws.single().playerId
            val seat = before.luckPlayerIdsForHost().entries.single { it.value == owner }.key
            val state = before.authoritativeStateForHost()
            val library = state.getLibrary(owner)
            val groups = library.groupBy { state.getEntity(it)!!.get<CardComponent>()!!.name }
            assertEquals(2, groups.size)
            val variants = groups.values.map { ids ->
                val order = library.toMutableList()
                val i = order.indexOf(ids.first())
                order[i] = order[0]; order[0] = ids.first()
                Triple(before.forkPermutingChanceForHost(seat, order), ids.size, choice)
            }
            val observations = variants.map { (pre, _, action) ->
                val post = pre.fork() as ArgentumSearchWorld
                assertTrue(post.step(action).accepted)
                ValueFeatures.compile(post.luckValueInformationForHost(seat), seat).values
            }
            val key = (observations[0].keys + observations[1].keys).first { key ->
                key.startsWith("card/") && observations[0][key] != observations[1][key]
            }
            val config = LuckCorrectionConfig(opening = false, models = listOf(
                LuckModelConfig("linear", LinearWeights(weights = mapOf(key to 0.5)))))
            var mean = 0.0
            val terms = variants.map { (pre, count, action) ->
                val correction = LuckCorrection(config, seat)
                val frozen = requireNotNull(correction.before(pre))
                val actualTrace = pre.stepWithReplayTrace(action)
                correction.after(frozen, action, pre, actualTrace)
                val event = correction.events.single()
                assertEquals("retained", event.status)
                assertEquals(2, event.branches)
                val term = event.weightedLuck.getValue("linear")
                val thinned = LuckCorrection(config.copy(rate = 0.25), seat)
                thinned.after(frozen, action, pre, actualTrace)
                assertEquals(term, thinned.events.single().weightedLuck.getValue("linear"))
                mean += term * count / library.size
                term
            }
            assertTrue(terms.any { kotlin.math.abs(it) > 1e-6 })
            assertEquals(0.0, mean, 1e-12)
            found = true
        }
        assertTrue(found, "No actual draw encountered")
    }

    @Test fun `Sokkas Haiku draw plus mill retains no luck correction`() {
        val deck = mapOf("Mountain" to 8, "Lightning Bolt" to 1, "Sokka's Haiku" to 1)
        val environment = GameEnvironment.create(registry)
        environment.reset(GameConfig(players = listOf(
            PlayerConfig("A", Deck.of(*deck.map { it.key to it.value }.toTypedArray())),
            PlayerConfig("B", Deck.of(*deck.map { it.key to it.value }.toTypedArray()))),
            startingHandSize = 0, skipMulligans = true, useHandSmoother = false,
            startingPlayerIndex = 0, seed = 11))
        val owner = environment.playerIds.first()
        var state = environment.state
        fun card(name: String) = state.getLibrary(owner).first {
            state.getEntity(it)?.get<CardComponent>()?.name == name
        }
        val bolt = card("Lightning Bolt")
        val haiku = card("Sokka's Haiku")
        val land = card("Mountain")
        state = state.removeFromZone(ZoneKey(owner, Zone.LIBRARY), land)
            .addToZone(ZoneKey(owner, Zone.BATTLEFIELD), land)
        state = state.removeFromZone(ZoneKey(owner, Zone.LIBRARY), bolt).pushToStack(bolt)
            .updateEntity(bolt) { it.with(SpellOnStackComponent(owner)) }
        state = state.removeFromZone(ZoneKey(owner, Zone.LIBRARY), haiku).pushToStack(haiku)
            .updateEntity(haiku) { it.with(SpellOnStackComponent(owner)).with(TargetsComponent(
                listOf(ChosenTarget.Spell(bolt), ChosenTarget.Permanent(land)))) }
        state = state.copy(activePlayerId = owner, priorityPlayerId = owner,
            phase = Phase.PRECOMBAT_MAIN, step = Step.PRECOMBAT_MAIN,
            priorityPassedBy = emptySet(), continuationStack = emptyList())
        environment.restore(state, environment.playerIds, environment.stepCount)
        fun snapshot() = ArgentumSearchWorld.create(environment.fork(), "haiku-luck-regression", 11, 11,
            knownDecks = mapOf("p0" to deck, "p1" to deck))
        val correction = LuckCorrection(LuckCorrectionConfig(opening = false), "p0")
        var sawDrawAndMill = false
        repeat(4) {
            if (sawDrawAndMill) return@repeat
            val before = requireNotNull(correction.before(snapshot()))
            val choice = pass(before)
            val nativeBefore = environment.state
            val action = PassPriority(requireNotNull(nativeBefore.priorityPlayerId))
            // Mill leaves selective-reveal metadata unsupported by the policy adapter. Exercise
            // the host rejection gate with the real native trace, without projecting that state
            // or stripping the metadata to make an unsupported world appear admissible.
            assertIs<ExactlyOneSubmissionResult.Applied>(environment.stepExactlyOne(action))
            val world = snapshot()
            val trace = ArgentumReplayStep(SearchStepResult(accepted = true), listOf(
                ArgentumRawTransition(action, nativeBefore, environment.state, environment.lastStepEvents, null)))
            val draws = trace.rawTransitions.flatMap { it.events }.filterIsInstance<CardsDrawnEvent>()
            if (draws.isNotEmpty()) {
                assertEquals(1, draws.single().cardIds.size)
                assertEquals(before.authoritativeStateForHost().getLibrary(owner).size - 4,
                    world.authoritativeStateForHost().getLibrary(owner).size)
                correction.after(before, choice, world, trace)
                assertEquals("skipped:NOT_PURE_LIBRARY_DRAW", correction.events.single().status)
                assertTrue(correction.events.none { it.status == "retained" })
                assertEquals(0.0, correction.result(null)["models"]!!.jsonObject["v2"]!!
                    .jsonObject["sum"]!!.jsonPrimitive.double)
                sawDrawAndMill = true
            }
        }
        assertTrue(sawDrawAndMill, "Sokka's Haiku did not resolve its draw and mill")
    }

    @Test fun `library order cannot enter snapshot linear features`() {
        val world = world(1)
        val state = world.authoritativeStateForHost()
        val owner = world.luckPlayerIdsForHost().getValue("p0")
        val child = world.forkPermutingChanceForHost("p0", state.getLibrary(owner).reversed())
        for (seat in listOf("p0", "p1")) {
            assertEquals(ValueFeatures.compile(world.luckValueInformationForHost(seat), seat).values,
                ValueFeatures.compile(child.luckValueInformationForHost(seat), seat).values)
        }
        assertEquals(state.rng, child.authoritativeStateForHost().rng)
        assertEquals(state.zones, child.authoritativeStateForHost().zones)
    }

    @Test fun `bridge captures opening and returns score metadata without changing raw result`() {
        val connection = PythonResearchConnection()
        val config = buildJsonObject { put("samples", 2); put("seed", 19) }
        val created = connection.request(buildJsonObject {
            put("command", "create")
            put("luckCorrection", config)
            put("plan", buildJsonObject {
                put("seed", 7); put("startingHandSize", 1); put("skipMulligans", true)
                put("decks", buildJsonArray { repeat(2) { add(buildJsonObject { put("Mountain", 3) }) } })
                put("policies", buildJsonArray { add("random"); add("random") })
            })
        }).jsonObject
        val error = assertFailsWith<IllegalArgumentException> {
            connection.request(buildJsonObject {
                put("command", "compare"); put("game", created.getValue("game"))
                put("candidateSeat", "p1"); put("incumbent", "random")
                put("maximumSeconds", 1.0); put("luckCorrection", config)
            })
        }
        assertTrue(error.message!!.contains("maximumSeconds"))
        // The failed request must not consume the opening hook or the fresh-game allowance.
        val comparison = connection.request(buildJsonObject {
            put("command", "compare"); put("game", created.getValue("game"))
            put("candidateSeat", "p1"); put("incumbent", "random"); put("maximumDecisions", 0)
            put("luckCorrection", config)
        }).jsonObject
        assertEquals(JsonNull, comparison["result"]!!.jsonObject["payoffs"])
        val luck = comparison["luck"]!!.jsonObject
        assertEquals("p1", luck["candidate"]!!.jsonPrimitive.content)
        val model = luck["models"]!!.jsonObject["v2"]!!.jsonObject
        assertEquals(64, model["sha256"]!!.jsonPrimitive.content.length)
        assertEquals(JsonNull, model["corrected"])
        assertEquals(2, model["events"]!!.jsonObject["opening"]!!.jsonObject["count"]!!.jsonPrimitive.int)
    }

    @Test fun `playGame rejects luck with time limits before touching the world`() {
        val world = world()
        val fingerprint = world.freshAuthoritativeFingerprintForHost()
        val luck = LuckCorrection(LuckCorrectionConfig(), "p0")
        val player = Player { _, _ -> error("Policy must not be called") }
        val error = assertFailsWith<IllegalArgumentException> {
            playGame(world, mapOf("p0" to player, "p1" to player), maximumSeconds = 1.0,
                luckCorrection = luck)
        }
        assertTrue(error.message!!.contains("maximumSeconds"))
        assertEquals(fingerprint, world.freshAuthoritativeFingerprintForHost())
        assertTrue(luck.events.isEmpty())
    }

    @Test fun `mulligan replays use configured independent sample count`() {
        val world = world(2, mulligans = true)
        val choice = world.expandChoices().candidates.first { it.actionIntent.kind == SemanticActionIntentKind.TAKE_MULLIGAN }
        val correction = LuckCorrection(LuckCorrectionConfig(samples = 3), "p0")
        val before = requireNotNull(correction.before(world))
        val trace = world.stepWithReplayTrace(choice)
        assertTrue(trace.result.accepted)
        correction.after(before, choice, world, trace)
        assertEquals("mulligan", correction.events.single().kind)
        assertEquals("retained", correction.events.single().status)
        assertEquals(3, correction.events.single().branches)
    }

    @Test fun `each opening expectation holds the other realized hand fixed`() {
        val world = world(2)
        val feature = ValueFeatures.compile(world.luckValueInformationForHost("p0"), "p0")
            .values.keys.first { it.startsWith("card/") }
        val weights = LinearWeights(weights = mapOf(feature to 0.7))
        val config = LuckCorrectionConfig(samples = 3, seed = 37,
            models = listOf(LuckModelConfig("linear", weights, LinearValueLink.TANH)))
        val correction = LuckCorrection(config, "p0")
        correction.opening(world)
        assertEquals(2, correction.events.size)
        val evaluator = LinearValueEvaluator(weights, LinearValueLink.TANH)
        val state = world.authoritativeStateForHost()
        for ((seat, owner) in world.luckPlayerIdsForHost()) {
            val pool = (state.getHand(owner) + state.getLibrary(owner)).sortedWith(
                compareBy({ state.getEntity(it)?.get<CardComponent>()?.name }, { it.toString() }))
            val other = world.luckPlayerIdsForHost().values.single { it != owner }
            val expected = (0 until config.samples).map { k ->
                val child = world.forkPermutingChanceForHost(seat, pool.shuffled(kotlin.random.Random(
                    ComponentSeeds.derive(config.seed, "luck-opening", k.toString(), seat))), true)
                for (id in state.getHand(other) + state.getLibrary(other)) {
                    assertEquals(state.getEntity(id), child.authoritativeStateForHost().getEntity(id))
                }
                0.5 + (evaluator.evaluate(child.luckValueInformationForHost("p0"), "p0") -
                    evaluator.evaluate(child.luckValueInformationForHost("p1"), "p1")) / 4
            }.average()
            val event = correction.events.single { it.player == seat }
            assertEquals("retained", event.status)
            assertEquals(expected, event.expected.getValue("linear"), 1e-12)
        }
    }

    @Test fun `opening materialization linear snapshot and hook preserve factual game`() {
        val world = world(2)
        val fingerprint = world.freshAuthoritativeFingerprintForHost()
        val correction = LuckCorrection(LuckCorrectionConfig(models = listOf(
            LuckModelConfig(), LuckModelConfig("linear", LinearWeights(bias = 0.2)))), "p0")
        correction.opening(world)
        assertEquals(setOf("p0", "p1"), correction.events.map { it.player }.toSet())
        assertEquals(2, correction.events.size)
        assertTrue(correction.events.all { it.status == "retained" && it.branches == 8 })
        assertEquals(fingerprint, world.freshAuthoritativeFingerprintForHost())
        val control = world.fork() as ArgentumSearchWorld
        val player = Player { context, _ -> context.expansion.candidates.first {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        } }
        val players = mapOf("p0" to player, "p1" to player)
        assertEquals(playGame(control, players, maximumDecisions = 20),
            playGame(world, players, maximumDecisions = 20, luckCorrection = correction))
        assertEquals(control.freshAuthoritativeFingerprintForHost(), world.freshAuthoritativeFingerprintForHost())
        val result = correction.result(mapOf("p0" to 1.0, "p1" to -1.0))
        assertEquals(1.0, result["models"]!!.jsonObject["v2"]!!.jsonObject["raw"]!!.jsonPrimitive.double)
    }
}
