package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.model.Deck
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.neural.FactualPolicyEncoder
import org.mtgallium.agent.neural.FactualTensorSchema
import org.mtgallium.agent.argentum.policy.*
import org.mtgallium.agent.monored.LinearValueEvaluator
import org.mtgallium.agent.monored.MonoRedInformationEvaluator
import org.mtgallium.agent.monored.ValueFeatures

/** Live world and native policy state. */
internal class PythonGame(
    val plan: GamesPlan,
    val world: ArgentumSearchWorld,
    private val gameId: String,
    private val sessions: MutableMap<String, SearchPolicySession> = linkedMapOf(),
) {
    private val knownDecks = plan.decks.mapIndexed { index, deck -> "p$index" to deck }.toMap()
    private val actors = knownDecks.keys.toList()

    fun status(): JsonObject = buildJsonObject {
        val terminal = world.terminalPayoff(actors.first()) != null
        put("terminal", terminal)
        put("index", world.acceptedDecisionCountForHost)
        put("actor", world.actorToAct()?.let(::JsonPrimitive) ?: JsonNull)
        put("payoffs", if (terminal) researchJson.encodeToJsonElement(
            actors.associateWith { requireNotNull(world.terminalPayoff(it)) }) else JsonNull)
    }

    private fun context(view: DecisionView): DecisionSiteRequest {
        val context = world.decisionContext(view)
        check(context.site().epistemic.knowledge.epistemicallyComplete) {
            "Player information is incomplete: ${context.site().epistemic.knowledge.unsupportedReasons}"
        }
        return context
    }

    fun decision(view: DecisionView, kernel: Boolean = false, factual: Boolean = false,
        fromEvent: Int = 0, schema: FactualTensorSchema = FactualTensorSchema()): JsonObject {
        if (world.terminalPayoff(actors.first()) != null) return status()
        val request = context(view)
        val site = request.site()
        return buildJsonObject {
            put("terminal", false)
            put("index", world.acceptedDecisionCountForHost)
            put("actor", site.actor)
            put("view", encodeView(request.view))
            put("information", researchJson.encodeToJsonElement(site.information()))
            put("rulesExhaustive", site.expansion.isExhaustive)
            put("profileExhaustive", site.expansion.isProfileExhaustive)
            if (kernel) put("features", researchJson.encodeToJsonElement(rootActionKernelFeatures(site)))
            if (factual) {
                val projection = world.policyDecisionProjection(request.view)
                val encoder = FactualPolicyEncoder(schema)
                put("factual", buildJsonObject {
                    put("schema", researchJson.encodeToJsonElement(schema))
                    put("input", researchJson.encodeToJsonElement(encoder.decision(projection.site, projection.semanticReferenceGroups)))
                    put("events", researchJson.encodeToJsonElement(encoder.events(site.epistemic.history, site.actor, actors, fromEvent)))
                    put("eventsFrom", fromEvent)
                    put("eventPosition", site.epistemic.history.size)
                })
            }
        }
    }

    private fun searchSession(actor: String): SearchPolicySession = sessions.getOrPut(actor) {
        SearchPolicySession(world, actor, knownDecks,
            SearchPolicyConfig(plan.particles, plan.simulations, plan.searchDepth, plan.explorationConstant,
                plan.leaf, plan.actionProfile, baseSeed = plan.seed, rolloutTurnHorizon = plan.rolloutTurnHorizon),
            when (plan.opponentModel) {
                "mixture" -> defaultMonoRedOpponentPolicy()
                "heuristic" -> DeterminizedArgentumHeuristicOpponentPolicy()
                "random" -> UniformOpponentPolicy
                else -> error("Unknown opponent model '${plan.opponentModel}'")
            }, gameId,
            valueSource = LeafValueSource.Information(
                plan.valueWeights?.let(::LinearValueEvaluator) ?: MonoRedInformationEvaluator))
    }

    private fun nativePlayer(name: String, actor: String): Player = when (name) {
        "random" -> selectorPlayer(UniformOpponentPolicy)
        "heuristic" -> selectorPlayer(DeterminizedArgentumHeuristicOpponentPolicy())
        "search" -> searchPlayer(world, searchSession(actor))
        else -> error("Unknown native policy '$name'; Python policies supply their selected action directly")
    }

    /** The CLI and live connection share the same native policy construction and observers. */
    val players: Map<String, Player> get() = actors.mapIndexed { i, actor ->
        actor to observedPlayer(actor, nativePlayer(plan.policies[i], actor))
    }.toMap()

    fun initializePolicies() {
        plan.policies.forEachIndexed { index, name -> if (name == "search") nativePlayer(name, actors[index]) }
    }

    /** Advance each existing native memory once, even when Python overrides the selected action. */
    private fun observedPlayer(actor: String, player: Player): Player = Player(player.view,
        observe = { acting, choice, step, index ->
            sessions[actor]?.observeAccepted(world, acting, choice, index, step.privateToActor)
        }, choose = player.choose)

    fun select(name: String, seed: Long?): JsonObject {
        val actor = requireNotNull(world.actorToAct()) { "A terminal game has no decision" }
        val player = nativePlayer(name, actor)
        val request = context(player.view)
        val selectionSeed = seed ?: ComponentSeeds.derive(plan.seed, actor,
            world.acceptedDecisionCountForHost.toString())
        val selection = if (name == "search") searchSession(actor).select(world, actor, selectionSeed) else null
        val selected = selection?.choice ?: player.choose(request, selectionSeed)
        check(selected in request.expansion.candidates) { "Native policy returned a non-admitted action" }
        return buildJsonObject {
            put("index", world.acceptedDecisionCountForHost)
            put("view", encodeView(request.view))
            put("choice", researchJson.encodeToJsonElement(selected))
            put("search", (selection as? RootActionSelection.Searched)?.search?.let {
                researchJson.encodeToJsonElement(it)
            } ?: JsonNull)
        }
    }

    fun step(index: Int, view: DecisionView, choice: SemanticChoice): JsonObject {
        check(index == world.acceptedDecisionCountForHost) { "Action belongs to an earlier decision; inspect the current game" }
        check(world.terminalPayoff(actors.first()) == null) { "Cannot step a terminal game" }
        val players = actors.associateWith { actor -> observedPlayer(actor, Player(view) { _, _ -> choice }) }
        var record: GameDecision? = null
        playGame(world, players, plan.seed, maximumDecisions = 1, record = { record = it })
        return buildJsonObject {
            put("decision", researchJson.encodeToJsonElement(requireNotNull(record)))
            put("status", status())
        }
    }

    fun play(names: List<String>, maximumDecisions: Int?, maximumSeconds: Double?): GameResult {
        require(names.size == actors.size)
        val players = actors.mapIndexed { i, actor -> actor to observedPlayer(actor, nativePlayer(names[i], actor)) }.toMap()
        return playGame(world, players, plan.seed, maximumDecisions, maximumSeconds)
    }

    fun fork(): PythonGame {
        val child = world.fork() as ArgentumSearchWorld
        return PythonGame(plan, child, gameId,
            sessions.mapValues { (_, session) -> session.forkForFactualContinuation(child) }.toMutableMap())
    }

    companion object {
        fun create(plan: GamesPlan, registry: CardRegistry, id: String): PythonGame {
            require(plan.decks.size == 2 && plan.policies.size == 2) { "The convenience setup takes two decks and two policy names" }
            val known = plan.decks.mapIndexed { i, cards -> "p$i" to cards }.toMap()
            val config = GameConfig(players = plan.decks.mapIndexed { i, cards ->
                PlayerConfig("Player $i", Deck.of(*cards.map { it.key to it.value }.toTypedArray()), plan.startingLife)
            }, startingHandSize = plan.startingHandSize, skipMulligans = plan.skipMulligans,
                useHandSmoother = plan.useHandSmoother, startingPlayerIndex = plan.startingPlayerIndex, seed = plan.seed)
            return PythonGame(plan, createWorld(config, known, registry, id, plan.seed, plan.actionProfile), id)
                .also { it.initializePolicies() }
        }
    }
}

private fun encodeView(view: DecisionView): JsonObject = buildJsonObject {
    put("admission", view.admission.name)
    put("annotations", view.annotations)
    put("limit", view.limit?.let(::JsonPrimitive) ?: JsonNull)
}

private fun decodeView(value: JsonObject?): DecisionView = DecisionView(
    limit = value?.get("limit")?.jsonPrimitive?.intOrNull,
    admission = value?.get("admission")?.jsonPrimitive?.content?.let(DecisionAdmission::valueOf) ?: DecisionAdmission.PRODUCTION,
    annotations = value?.get("annotations")?.jsonPrimitive?.booleanOrNull ?: false,
)

/** One private, synchronous connection. */
internal class PythonResearchConnection {
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
            "create" -> remember(PythonGame.create(researchJson.decodeFromJsonElement(request.getValue("plan")), registry, "python-game-$nextId"))
            "fork" -> remember(game().fork())
            "close" -> { games.remove(request.getValue("game").jsonPrimitive.int); JsonNull }
            "status" -> game().status()
            "decision" -> game().decision(decodeView(request["view"]?.jsonObject),
                request["kernel"]?.jsonPrimitive?.booleanOrNull ?: false,
                request["factual"]?.jsonPrimitive?.booleanOrNull ?: false,
                request["fromEvent"]?.jsonPrimitive?.intOrNull ?: 0,
                request["schema"]?.let { researchJson.decodeFromJsonElement<FactualTensorSchema>(it) } ?: FactualTensorSchema())
            "select" -> game().select(request.getValue("policy").jsonPrimitive.content, request["seed"]?.jsonPrimitive?.longOrNull)
            "step" -> game().step(request.getValue("index").jsonPrimitive.int,
                decodeView(request.getValue("view").jsonObject), researchJson.decodeFromJsonElement(request.getValue("choice")))
            "play" -> researchJson.encodeToJsonElement(game().play(
                researchJson.decodeFromJsonElement(request.getValue("policies")),
                request["maximumDecisions"]?.jsonPrimitive?.intOrNull,
                request["maximumSeconds"]?.jsonPrimitive?.doubleOrNull))
            "information" -> researchJson.encodeToJsonElement(game().world.informationState(request.getValue("player").jsonPrimitive.content))
            "value-features" -> {
                val world = game().world
                val player = request["player"]?.jsonPrimitive?.content ?: requireNotNull(world.actorToAct())
                researchJson.encodeToJsonElement(ValueFeatures.compile(world.informationState(player), player).values)
            }
            "state" -> researchJson.encodeToJsonElement(game().world.authoritativeStateForHost())
            "fit" -> researchJson.encodeToJsonElement(fitRootActionKernel(
                researchJson.decodeFromJsonElement(request.getValue("roots")),
                request["ridge"]?.jsonPrimitive?.double ?: 0.001,
                request["weights"]?.takeUnless { it is JsonNull }?.let { researchJson.decodeFromJsonElement(it) }))
            "predict" -> {
                val model = researchJson.decodeFromJsonElement<RootActionKernelModel>(request.getValue("model"))
                val menus = researchJson.decodeFromJsonElement<List<List<RootActionKernelFeatures>>>(request.getValue("menus"))
                researchJson.encodeToJsonElement(menus.map(model::scores))
            }
            else -> error("Unknown primitive: ${request["command"]}")
        }
    }
}

/** stdout is exclusively responses; ordinary native diagnostic output goes to stderr. */
object PythonResearch {
    @JvmStatic fun main(args: Array<String>) {
        val responses = System.out
        System.setOut(System.err)
        val connection = PythonResearchConnection()
        System.`in`.bufferedReader().useLines { lines ->
            for (line in lines) {
                val response = try {
                    val value = connection.request(researchJson.parseToJsonElement(line).jsonObject)
                    buildJsonObject { put("value", value) }
                } catch (failure: Exception) {
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
