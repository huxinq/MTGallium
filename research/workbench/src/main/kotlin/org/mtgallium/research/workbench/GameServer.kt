package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.sdk.model.Deck
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.infoset.planning.*
import org.mtgallium.agent.neural.InformationStateByteEncoder
import org.mtgallium.agent.neural.ByteTokenSchema
import org.mtgallium.agent.argentum.policy.*
import org.mtgallium.agent.value.MaterialEvaluator
import org.mtgallium.agent.value.ValueFeatures

/** Live world and native policy state. */
class PythonGame internal constructor(
    val plan: ResearchGameConfig,
    val world: ArgentumSearchWorld,
    private val gameId: String,
    private val sessions: MutableMap<Pair<String, String>, SearchPolicySession> = linkedMapOf(),
    private val policies: NativePolicies = NativePolicies.installed,
) {
    private var luckConfig: ChanceControlVariateConfig? = null
    private var luckOpening: ArgentumSearchWorld? = null
    private var luckUsed = false
    private val knownDecks = plan.decks.mapIndexed { index, deck -> "p$index" to deck }.toMap()
    private val actors = knownDecks.keys.toList()
    private val policyContext = NativePolicyContext(plan, world, gameId, knownDecks)

    fun status(): JsonObject = buildJsonObject {
        val terminal = world.terminalPayoff(actors.first()) != null
        put("terminal", terminal)
        put("index", world.acceptedDecisionCount)
        put("actor", world.actorToAct()?.let(::JsonPrimitive) ?: JsonNull)
        put("payoffs", if (terminal) researchJson.encodeToJsonElement(
            actors.associateWith { requireNotNull(world.terminalPayoff(it)) }) else JsonNull)
    }

    fun valueSnapshot(factualSchema: ByteTokenSchema? = null): JsonObject = buildJsonObject {
        actors.forEach { player ->
            val information = world.informationState(player)
            put(player, buildJsonObject {
                put("features", researchJson.encodeToJsonElement(ValueFeatures.compile(information, player).values))
                put("v2", MaterialEvaluator().evaluate(information, player))
                put("turn", information.observation.turnNumber)
                factualSchema?.let { put("view", researchJson.encodeToJsonElement(InformationStateByteEncoder(it).view(information))) }
            })
        }
    }

    private fun context(view: MenuRequest): DecisionContext {
        val context = world.decisionContext(view)
        check(context.site().epistemic.knowledge.isComplete) {
            "Player information is incomplete: ${context.site().epistemic.knowledge.unsupportedReasons}"
        }
        return context
    }

    fun decision(view: MenuRequest, kernel: Boolean = false, factual: Boolean = false,
        fromEvent: Int = 0, schema: ByteTokenSchema = ByteTokenSchema(),
        includeEvents: Boolean = true): JsonObject {
        if (world.terminalPayoff(actors.first()) != null) return status()
        val request = context(view)
        val site = request.site()
        return buildJsonObject {
            put("terminal", false)
            put("index", world.acceptedDecisionCount)
            put("actor", site.actor)
            put("view", encodeView(request.view))
            put("information", researchJson.encodeToJsonElement(site.information()))
            put("rulesExhaustive", site.menu.isExhaustive)
            put("profileExhaustive", site.menu.isProfileExhaustive)
            if (kernel) put("features", researchJson.encodeToJsonElement(kernelActionFeatures(site)))
            if (factual) {
                val projection = world.policyDecisionProjection(request.view)
                val encoder = InformationStateByteEncoder(schema)
                val eventsFrom = if (includeEvents) fromEvent else site.epistemic.history.size
                put("factual", buildJsonObject {
                    put("schema", researchJson.encodeToJsonElement(schema))
                    put("input", researchJson.encodeToJsonElement(encoder.decision(projection.site, projection.semanticReferenceGroups)))
                    put("events", researchJson.encodeToJsonElement(encoder.events(site.epistemic.history, site.actor, actors, eventsFrom)))
                    put("eventsFrom", eventsFrom)
                    put("eventPosition", site.epistemic.history.size)
                })
            }
        }
    }

    /** Search sessions live for the game; other native policies are constructed for each use. */
    private fun nativePolicy(name: String, actor: String): JvmPolicy =
        sessions[actor to name]?.let(JvmPolicy::SearchSession)
            ?: policies.create(name, policyContext, actor).also {
                if (it is JvmPolicy.SearchSession) sessions[actor to name] = it.session
            }

    private fun nativePlayer(policy: JvmPolicy): GameAgent = when (policy) {
        is JvmPolicy.Memoryless -> policy.player
        is JvmPolicy.SearchSession -> searchPlayer(world, policy.session)
    }

    private fun nativePlayer(name: String, actor: String): GameAgent = nativePlayer(nativePolicy(name, actor))

    /** The CLI and live connection share the same native policy construction and observers. */
    val players: Map<String, GameAgent> get() = actors.mapIndexed { i, actor ->
        actor to observedPlayer(actor, nativePlayer(plan.policies[i], actor))
    }.toMap()

    /** Check names and settings, and start search memory, including a shadow's, before the first move. */
    fun initializePolicies() {
        policies.checkSettings(plan)
        plan.policies.forEachIndexed { index, name -> nativePolicy(name, actors[index]) }
        plan.shadowPolicies.forEach { name -> actors.forEach { actor -> nativePolicy(name, actor) } }
    }

    /** Advance each existing native memory once, even when Python overrides the selected action. */
    private fun observedPlayer(actor: String, player: GameAgent): GameAgent = GameAgent(player.view,
        observe = { acting, choice, step, index ->
            sessions.filterKeys { it.first == actor }.values.forEach {
                it.observeAccepted(world, acting, choice, index, step.privateToActor)
            }
        }, choose = player.choose)

    fun select(name: String, seed: Long?): JsonObject {
        val actor = requireNotNull(world.actorToAct()) { "A terminal game has no decision" }
        val policy = nativePolicy(name, actor)
        val player = nativePlayer(policy)
        val request = context(player.view)
        val selectionSeed = seed ?: ComponentSeeds.derive(plan.seed, actor,
            world.acceptedDecisionCount.toString())
        val selection = (policy as? JvmPolicy.SearchSession)?.session?.select(world, actor, selectionSeed)
        val selected = selection?.choice ?: player.choose(request, selectionSeed)
        check(selected in request.menu.candidates) { "Native policy returned a non-admitted action" }
        return buildJsonObject {
            put("index", world.acceptedDecisionCount)
            put("view", encodeView(request.view))
            put("choice", researchJson.encodeToJsonElement(selected))
            put("search", (selection as? RootActionSelection.Searched)?.search?.let {
                researchJson.encodeToJsonElement(it)
            } ?: JsonNull)
        }
    }

    fun step(index: Int, view: MenuRequest, choice: SemanticChoice, record: Boolean = true): JsonObject {
        check(index == world.acceptedDecisionCount) { "Action belongs to an earlier decision; inspect the current game" }
        check(world.terminalPayoff(actors.first()) == null) { "Cannot step a terminal game" }
        val players = actors.associateWith { actor -> observedPlayer(actor, GameAgent(view) { _, _ -> choice }) }
        var decision: GameDecision? = null
        playGame(world, players, plan.seed, maximumDecisions = 1,
            record = if (record) ({ decision = it }) else null)
        return buildJsonObject {
            if (record) put("decision", researchJson.encodeToJsonElement(requireNotNull(decision)))
            put("status", status())
        }
    }

    fun play(names: List<String>, maximumDecisions: Int?, maximumSeconds: Double?): GameResult {
        require(names.size == actors.size)
        val players = actors.mapIndexed { i, actor -> actor to observedPlayer(actor, nativePlayer(names[i], actor)) }.toMap()
        return playGame(world, players, plan.seed, maximumDecisions, maximumSeconds)
    }

    /** Shadow choices consume the candidate's actual history but are never applied. */
    fun compare(candidateSeat: String, incumbent: String, maximumDecisions: Int?, maximumSeconds: Double?,
        luckCorrection: ChanceControlVariateConfig? = null, choiceSeed: Long? = null): JsonObject {
        require(luckCorrection == null || maximumSeconds == null) {
            "Luck correction does not support maximumSeconds; use a decision limit"
        }
        require(luckCorrection == luckConfig) { "Supply identical luckCorrection at create and compare" }
        val luck = luckCorrection?.let { config ->
            check(!luckUsed && world.acceptedDecisionCount == 0) { "Luck pilot requires one fresh full-game compare" }
            luckUsed = true
            ChanceControlVariate(config.copy(seed = ComponentSeeds.derive(plan.seed, "host-luck-pilot", config.seed.toString())), candidateSeat).also { it.opening(requireNotNull(luckOpening)) }
        }
        require(candidateSeat in actors)
        val baseline = nativePlayer(incumbent, candidateSeat)
        var decisions = 0
        var changed = 0
        val compared = players.toMutableMap()
        val candidate = compared.getValue(candidateSeat)
        compared[candidateSeat] = GameAgent(candidate.view, candidate.observe) { request, seed ->
            val expected = baseline.choose(context(baseline.view), seed)
            val selected = candidate.choose(request, seed)
            decisions++
            if (expected.signature != selected.signature) changed++
            selected
        }
        // Direct policy choices can use an independent stream without changing the deal.
        // Search sessions retain their own configured seeds; this is not a search-seed override.
        val result = playGame(world, compared, choiceSeed ?: plan.seed, maximumDecisions, maximumSeconds, luckCorrection = luck)
        return buildJsonObject {
            if (choiceSeed != null) put("choiceSeed", choiceSeed)
            put("result", researchJson.encodeToJsonElement(result))
            if (luck != null) put("luck", luck.result(result.payoffs))
            put("candidateDecisions", decisions)
            put("changedDecisions", changed)
        }
    }

    fun fork(): PythonGame {
        val child = world.fork() as ArgentumSearchWorld
        return PythonGame(plan, child, gameId,
            sessions.mapValues { (_, session) -> session.forkForFactualContinuation(child) }.toMutableMap(), policies)
    }

    companion object {
        fun create(plan: ResearchGameConfig, registry: CardRegistry, id: String, luckCorrection: ChanceControlVariateConfig? = null): PythonGame {
            require(luckCorrection == null || !plan.useHandSmoother) { "Uniform luck pilot does not support hand smoothing" }
            require(plan.decks.size == 2 && plan.policies.size == 2) { "The convenience setup takes two decks and two policy names" }
            val known = plan.decks.mapIndexed { i, cards -> "p$i" to cards }.toMap()
            val config = GameConfig(players = plan.decks.mapIndexed { i, cards ->
                PlayerConfig("Player $i", Deck.of(*cards.map { it.key to it.value }.toTypedArray()), plan.startingLife)
            }, startingHandSize = plan.startingHandSize, skipMulligans = plan.skipMulligans,
                useHandSmoother = plan.useHandSmoother, startingPlayerIndex = plan.startingPlayerIndex, seed = plan.seed)
            return PythonGame(plan, createWorld(config, known, registry, id, plan.seed, plan.actionProfile), id)
                .also {
                    it.luckConfig = luckCorrection
                    if (luckCorrection != null) it.luckOpening = it.world.fork() as ArgentumSearchWorld
                    it.initializePolicies()
                }
        }
    }
}

private fun encodeView(view: MenuRequest): JsonObject = buildJsonObject {
    put("admission", view.admission.name)
    put("annotations", view.annotations)
    put("limit", view.limit?.let(::JsonPrimitive) ?: JsonNull)
}

private fun decodeView(value: JsonObject?): MenuRequest = MenuRequest(
    limit = value?.get("limit")?.jsonPrimitive?.intOrNull,
    admission = value?.get("admission")?.jsonPrimitive?.content?.let(MenuSource::valueOf) ?: MenuSource.PRODUCTION,
    annotations = value?.get("annotations")?.jsonPrimitive?.booleanOrNull ?: false,
)

private fun replayViews(state: GameState, registry: CardRegistry): JsonObject {
    val transformer = ClientStateTransformer(registry)
    return buildJsonObject {
        for ((index, player) in state.turnOrder.withIndex()) {
            put("p$index", researchJson.encodeToJsonElement(transformer.transform(state, player)))
        }
    }
}

/** One private, synchronous connection; the protocol the Python session speaks. */
class GameServerConnection {
    private val registry by lazy(::buildRegistry)
    private val games = linkedMapOf<Int, PythonGame>()
    private var nextId = 0

    fun request(request: JsonObject): JsonElement {
        fun game() = games.getValue(request.getValue("game").jsonPrimitive.int)
        fun remember(game: PythonGame): JsonObject {
            val id = nextId++
            games[id] = game
            return buildJsonObject { put("game", id); put("status", game.status()) }
        }
        return when (request.getValue("command").jsonPrimitive.content) {
            "create" -> {
                val plan = decodeGamesPlan(request.getValue("plan").jsonObject)
                // Search RNG identity must not depend on which worker/session happened to run a setup.
                remember(PythonGame.create(plan, registry, "python-game-${plan.seed}",
                    request["luckCorrection"]?.takeUnless { it is JsonNull }?.let { researchJson.decodeFromJsonElement<ChanceControlVariateConfig>(it) }))
            }
            "fork" -> remember(game().fork())
            "close" -> { games.remove(request.getValue("game").jsonPrimitive.int); JsonNull }
            "status" -> game().status()
            "decision" -> game().decision(decodeView(request["view"]?.jsonObject),
                request["kernel"]?.jsonPrimitive?.booleanOrNull ?: false,
                request["factual"]?.jsonPrimitive?.booleanOrNull ?: false,
                request["fromEvent"]?.jsonPrimitive?.intOrNull ?: 0,
                request["schema"]?.let { researchJson.decodeFromJsonElement<ByteTokenSchema>(it) } ?: ByteTokenSchema(),
                request["includeEvents"]?.jsonPrimitive?.booleanOrNull ?: true)
            "select" -> game().select(request.getValue("policy").jsonPrimitive.content, request["seed"]?.jsonPrimitive?.longOrNull)
            "step" -> game().step(request.getValue("index").jsonPrimitive.int,
                decodeView(request.getValue("view").jsonObject), researchJson.decodeFromJsonElement(request.getValue("choice")),
                request["record"]?.jsonPrimitive?.boolean ?: true)
            "play" -> {
                val game = game()
                val result = game.play(researchJson.decodeFromJsonElement(request.getValue("policies")),
                    request["maximumDecisions"]?.jsonPrimitive?.intOrNull,
                    request["maximumSeconds"]?.jsonPrimitive?.doubleOrNull)
                // The resulting position saves a status request between Python-driven native moves.
                JsonObject(researchJson.encodeToJsonElement(result).jsonObject + ("position" to game.status()))
            }
            "compare" -> game().compare(request.getValue("candidateSeat").jsonPrimitive.content,
                request.getValue("incumbent").jsonPrimitive.content,
                request["maximumDecisions"]?.jsonPrimitive?.intOrNull,
                request["maximumSeconds"]?.jsonPrimitive?.doubleOrNull,
                request["luckCorrection"]?.takeUnless { it is JsonNull }?.let { researchJson.decodeFromJsonElement<ChanceControlVariateConfig>(it) },
                request["choiceSeed"]?.jsonPrimitive?.longOrNull)
            "information" -> researchJson.encodeToJsonElement(game().world.informationState(request.getValue("player").jsonPrimitive.content))
            "value-features" -> {
                val world = game().world
                val player = request["player"]?.jsonPrimitive?.content ?: requireNotNull(world.actorToAct())
                researchJson.encodeToJsonElement(ValueFeatures.compile(world.informationState(player), player).values)
            }
            "value-snapshot" -> game().valueSnapshot(request["factualSchema"]?.let {
                researchJson.decodeFromJsonElement<ByteTokenSchema>(it)
            })
            "state" -> researchJson.encodeToJsonElement(game().world.trueState())
            "replay-views" -> replayViews(game().world.trueState(), registry)
            "render-replay-state" -> replayViews(
                researchJson.decodeFromJsonElement(request.getValue("state")), registry)
            "fit" -> researchJson.encodeToJsonElement(fitKernelRidge(
                researchJson.decodeFromJsonElement(request.getValue("roots")),
                request["ridge"]?.jsonPrimitive?.double ?: 0.001,
                request["weights"]?.takeUnless { it is JsonNull }?.let { researchJson.decodeFromJsonElement(it) }))
            "predict" -> {
                val model = researchJson.decodeFromJsonElement<KernelRidgeActionModel>(request.getValue("model"))
                val menus = researchJson.decodeFromJsonElement<List<List<KernelActionFeatures>>>(request.getValue("menus"))
                researchJson.encodeToJsonElement(menus.map(model::scores))
            }
            else -> error("Unknown primitive: ${request["command"]}")
        }
    }
}

/** stdout is exclusively responses; ordinary native diagnostic output goes to stderr. */
object GameServer {
    @JvmStatic fun main(args: Array<String>) {
        val responses = System.out
        System.setOut(System.err)
        val connection = GameServerConnection()
        System.`in`.bufferedReader().useLines { lines ->
            for (line in lines) {
                val response = try {
                    val value = connection.request(researchJson.parseToJsonElement(line).jsonObject)
                    buildJsonObject { put("value", value) }
                } catch (failure: Exception) {
                    failure.printStackTrace(System.err)
                    buildJsonObject {
                        put("error", failure.javaClass.name)
                        put("message", failure.message ?: failure.javaClass.simpleName)
                    }
                }
                responses.println(response)
                responses.flush()
            }
        }
    }
}
