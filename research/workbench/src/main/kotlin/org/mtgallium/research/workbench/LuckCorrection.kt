package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.state.components.identity.CardComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumReplayStep
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.monored.*
import java.security.MessageDigest
import kotlin.random.Random

/** Experimental host statistic. Separate seed stream; never consumes policy or engine RNG. */
@Serializable
data class LuckCorrectionConfig(
    val rate: Double = 1.0,
    val seed: Long = 0,
    val opening: Boolean = true,
    val samples: Int = 8,
    val models: List<LuckModelConfig> = listOf(LuckModelConfig()),
) {
    init {
        require(samples > 0 && samples <= 1024)
        require(rate.isFinite() && rate > 0 && rate <= 1)
        require(models.isNotEmpty() && models.all { it.name.isNotBlank() } && models.map { it.name }.distinct().size == models.size)
    }
}

@Serializable
data class LuckModelConfig(val name: String = "v2", val weights: LinearWeights? = null,
    val link: LinearValueLink = LinearValueLink.CLIP)

@Serializable
data class LuckEvent(val index: Int, val kind: String, val player: String?, val status: String,
    val actual: Map<String, Double> = emptyMap(), val expected: Map<String, Double> = emptyMap(),
    /** Historical bridge field name; thinning retains unweighted terms. */
    val weightedLuck: Map<String, Double> = emptyMap(), val branches: Int = 0)

/** V = 0.5 + (own-view candidate value - own-view opponent value)/4, in score units. */
class LuckCorrection(private val config: LuckCorrectionConfig, private val candidate: String) {
    val events = mutableListOf<LuckEvent>()
    var nanos: Long = 0; private set
    private val sums = config.models.associate { it.name to 0.0 }.toMutableMap()
    private var calls = 0
    private var selected = 0
    private val evaluators = config.models.associate { it.name to
        (it.weights?.let { weights -> LinearValueEvaluator(weights, it.link) } ?: MonoRedInformationEvaluator) }

    private inline fun <T> timed(block: () -> T): T {
        val start = System.nanoTime()
        try { return block() } finally { nanos += System.nanoTime() - start }
    }

    private fun sample(index: Int, kind: String): Boolean = Random(
        ComponentSeeds.derive(config.seed, "luck-pilot-v1", index.toString(), kind)).nextDouble() < config.rate

    fun before(world: ArgentumSearchWorld): ArgentumSearchWorld? = timed {
        calls++
        if (sample(world.acceptedDecisionCountForHost, "step")) {
            selected++
            world.fork() as ArgentumSearchWorld
        } else null
    }

    private fun values(world: ArgentumSearchWorld): Map<String, Double> {
        val seats = world.luckPlayerIdsForHost().keys.toList()
        require(seats.size == 2 && candidate in seats)
        val other = seats.single { it != candidate }
        val terminal = world.terminalPayoff(candidate)
        if (terminal != null) return config.models.associate { it.name to ((terminal + 1) / 2) }
        val own = world.luckValueInformationForHost(candidate)
        val opponent = world.luckValueInformationForHost(other)
        // This pilot excludes library-position inputs, including publicly revealed library cards.
        require(listOf(own, opponent).all { information ->
            information.knowledge.knownLibraryOrders.all { it.top.isEmpty() && it.bottom.isEmpty() } &&
                information.observation.zones.filter { it.zone == "LIBRARY" }.all { it.cards.isEmpty() }
        }) { "LIBRARY_INFORMATION" }
        return evaluators.mapValues { (_, evaluator) ->
            (0.5 + (evaluator.evaluate(own, candidate) - evaluator.evaluate(opponent, other)) / 4).also {
                require(it.isFinite())
            }
        }
    }

    private fun retain(index: Int, kind: String, player: String?, actual: Map<String, Double>,
        expected: Map<String, Double>, branches: Int) {
        val luck = actual.mapValues { (name, value) -> value - expected.getValue(name) }
        luck.forEach { (name, value) -> sums[name] = sums.getValue(name) + value }
        events += LuckEvent(index, kind, player, "retained", actual, expected, luck, branches)
    }

    fun opening(world: ArgentumSearchWorld) = timed {
        if (!config.opening) return@timed
        require(world.acceptedDecisionCountForHost == 0) { "Opening correction requires a fresh game" }
        for ((seat, owner) in world.luckPlayerIdsForHost()) {
            // Select each seat independently, without consulting either realized opening hand.
            if (!sample(-1, "opening:$seat")) continue
            attempt(-1, "opening", seat) {
                val actual = values(world)
                val mean = sums.mapValues { 0.0 }.toMutableMap()
                val state = world.authoritativeStateForHost()
                // Canonical input makes MC permutations independent of this seat's realized order.
                val pool = (state.getHand(owner) + state.getLibrary(owner)).sortedWith(
                    compareBy({ state.getEntity(it)?.get<CardComponent>()?.name }, { it.toString() }))
                repeat(config.samples) { k ->
                    // Always start from the factual world: the other seat stays conditioned on its actual hand.
                    val child = world.forkPermutingChanceForHost(seat, pool.shuffled(Random(
                        ComponentSeeds.derive(config.seed, "luck-opening", k.toString(), seat))), true)
                    values(child).forEach { (name, value) -> mean[name] = mean.getValue(name) + value / config.samples }
                }
                retain(-1, "opening", seat, actual, mean, config.samples)
            }
        }
    }

    private inline fun attempt(index: Int, kind: String, player: String?, block: () -> Unit) {
        try { block() } catch (error: Exception) {
            if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
            events += LuckEvent(index, kind, player, "skipped:" + (error.message ?: error.javaClass.simpleName).take(160))
        }
    }

    fun after(before: ArgentumSearchWorld, choice: SemanticChoice, actual: ArgentumSearchWorld,
        trace: ArgentumReplayStep) = timed {
        val index = before.acceptedDecisionCountForHost
        val mulligan = choice.actionIntent.kind == SemanticActionIntentKind.TAKE_MULLIGAN
        val draws = trace.rawTransitions.flatMap { it.events }.filterIsInstance<CardsDrawnEvent>()
        if (mulligan) {
            val actor = requireNotNull(before.actorToAct())
            attempt(index, "mulligan", actor) {
                val owner = before.luckPlayerIdsForHost().getValue(actor)
                val state = before.authoritativeStateForHost()
                val pool = (state.getHand(owner) + state.getLibrary(owner)).sortedWith(compareBy({ state.getEntity(it)?.get<CardComponent>()?.name }, { it.toString() }))
                val mean = sums.mapValues { 0.0 }.toMutableMap()
                repeat(config.samples) { k ->
                    val child = before.forkPermutingChanceForHost(actor, pool.shuffled(Random(
                        ComponentSeeds.derive(config.seed, "luck-mulligan", index.toString(), k.toString()))), true)
                    val replay = child.stepWithReplayTrace(choice)
                    require(replay.result.accepted) { "REPLAY_REJECTED" }
                    require(drawShape(replay) == drawShape(trace)) { "DIFFERENT_DRAWS" }
                    values(child).forEach { (name, value) -> mean[name] = mean.getValue(name) + value / config.samples }
                }
                retain(index, "mulligan", actor, values(actual), mean, config.samples)
            }
            return@timed
        }
        if (draws.isEmpty()) return@timed
        attempt(index, "draw", null) {
            require(draws.size == 1 && draws.single().cardIds.size == 1) { "NOT_SINGLE_DRAW" }
            val draw = draws.single()
            val seat = before.luckPlayerIdsForHost().entries.single { it.value == draw.playerId }.key
            val state = before.authoritativeStateForHost()
            val library = state.getLibrary(draw.playerId)
            require(library.isNotEmpty() && draw.cardIds.single() == library.first()) { "NOT_PRESTEP_TOP_DRAW" }
            // Known top/bottom conditioning needs its own chance kernel; never assume it uniform.
            require(before.luckPlayerIdsForHost().keys.all { viewer ->
                before.informationState(viewer).knowledge.knownLibraryOrders.all {
                    it.playerId != seat || (it.top.isEmpty() && it.bottom.isEmpty())
                }
            }) { "KNOWN_LIBRARY_ORDER" }
            val groups = library.groupBy { requireNotNull(state.getEntity(it)?.get<CardComponent>()).name }
            val mean = sums.mapValues { 0.0 }.toMutableMap()
            for ((_, ids) in groups) {
                val order = library.toMutableList()
                val position = order.indexOf(ids.first())
                order[position] = order[0]; order[0] = ids.first()
                val child = before.forkPermutingChanceForHost(seat, order)
                val replay = child.stepWithReplayTrace(choice)
                require(replay.result.accepted) { "REPLAY_REJECTED" }
                require(drawShape(replay) == drawShape(trace)) { "DIFFERENT_DRAWS" }
                val alternate = replay.rawTransitions.flatMap { it.events }.filterIsInstance<CardsDrawnEvent>().single()
                require(alternate.cardIds.single() == library.first()) { "DIFFERENT_DRAW_SOURCE" }
                values(child).forEach { (name, value) ->
                    mean[name] = mean.getValue(name) + value * ids.size / library.size
                }
            }
            retain(index, "draw", seat, values(actual), mean, groups.size)
        }
    }

    private fun drawShape(trace: ArgentumReplayStep) = trace.rawTransitions.flatMap { it.events }
        .filterIsInstance<CardsDrawnEvent>().map { it.playerId to it.cardIds.size }

    fun result(payoffs: Map<String, Double>?) = buildJsonObject {
        put("version", JsonPrimitive("host-luck-pilot-v1"))
        put("valueInput", JsonPrimitive("own-perspective-snapshot-no-history-or-library-order-v1"))
        put("beta", JsonPrimitive(1.0))
        put("samples", JsonPrimitive(config.samples))
        put("rate", JsonPrimitive(config.rate))
        put("seed", JsonPrimitive(config.seed))
        put("thinning", JsonPrimitive("preselected-unweighted"))
        put("opening", JsonPrimitive(config.opening))
        put("candidate", JsonPrimitive(candidate))
        put("nanos", JsonPrimitive(nanos))
        put("steps", JsonPrimitive(calls))
        put("selectedSteps", JsonPrimitive(selected))
        put("events", researchJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(LuckEvent.serializer()), events))
        put("models", buildJsonObject {
            config.models.forEach { model -> put(model.name, buildJsonObject {
                val bytes = (model.weights?.toJson() ?: MonoRedVisibleEvaluatorConfig().configurationId) + ":" + model.link.name
                put("hashFormat", JsonPrimitive("sha256-canonical-weights-json-or-v2-config-colon-link-utf8"))
                put("sha256", JsonPrimitive(MessageDigest.getInstance("SHA-256")
                    .digest(bytes.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }))
                put("link", JsonPrimitive(model.link.name))
                put("kind", JsonPrimitive(if (model.weights == null) "v2" else "linear"))
                put("events", buildJsonObject {
                    for (kind in listOf("opening", "mulligan", "draw")) {
                        val rows = events.filter { it.kind == kind }
                        put(kind, buildJsonObject {
                            put("count", rows.count { it.status == "retained" })
                            put("sum", rows.sumOf { it.weightedLuck[model.name] ?: 0.0 })
                            put("skipped", rows.count { it.status.startsWith("skipped:") })
                            put("skipReasons", buildJsonObject {
                                rows.filter { it.status != "retained" }.groupingBy { it.status }.eachCount()
                                    .forEach { (reason, count) -> put(reason, count) }
                            })
                        })
                    }
                })
                put("sum", JsonPrimitive(sums.getValue(model.name)))
                put("retained", JsonPrimitive(events.count { it.status == "retained" }))
                put("skipped", JsonPrimitive(events.count { it.status.startsWith("skipped:") }))
                put("raw", payoffs?.get(candidate)?.let { JsonPrimitive((it + 1) / 2) } ?: JsonNull)
                put("corrected", payoffs?.get(candidate)?.let { JsonPrimitive((it + 1) / 2 - sums.getValue(model.name)) }
                    ?: JsonNull)
            }) }
        })
    }
}
