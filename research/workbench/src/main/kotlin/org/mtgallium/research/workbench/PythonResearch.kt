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
import org.mtgallium.agent.monored.LinearValueLink
import org.mtgallium.agent.monored.LinearWeights

/** Live world and native policy state. */
class PythonGame internal constructor(
    val plan: GamesPlan,
    val world: ArgentumSearchWorld,
    private val gameId: String,
    private val sessions: MutableMap<Pair<String, String>, SearchPolicySession> = linkedMapOf(),
    private val policies: NativePolicies = NativePolicies.installed,
) {
    private val knownDecks = plan.decks.mapIndexed { index, deck -> "p$index" to deck }.toMap()
    private val actors = knownDecks.keys.toList()
    private val policyContext = NativePolicyContext(plan, world, gameId, knownDecks)

    fun status(): JsonObject = buildJsonObject {
        val terminal = world.terminalPayoff(actors.first()) != null
        put("terminal", terminal)
        put("index", world.acceptedDecisionCountForHost)
        put("actor", world.actorToAct()?.let(::JsonPrimitive) ?: JsonNull)
        put("payoffs", if (terminal) researchJson.encodeToJsonElement(
            actors.associateWith { requireNotNull(world.terminalPayoff(it)) }) else JsonNull)
    }

    fun valueSnapshot(): JsonObject = buildJsonObject {
        actors.forEach { player ->
            val information = world.informationState(player)
            put(player, buildJsonObject {
                put("features", researchJson.encodeToJsonElement(ValueFeatures.compile(information, player).values))
                put("v2", MonoRedInformationEvaluator.evaluate(information, player))
                put("turn", information.observation.turnNumber)
            })
        }
    }

    fun valueScore(player: String, weights: LinearWeights, link: LinearValueLink): JsonObject {
        require(player in actors) { "Unknown player '$player'" }
        val estimate = LinearValueEvaluator(weights, link).evaluateDetailed(world.informationState(player), player)
        return buildJsonObject {
            put("rawScore", estimate.rawScore)
            put("deployedValue", estimate.deployedValue)
        }
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

    /** Search sessions live for the game; other native policies are constructed for each use. */
    private fun nativePolicy(name: String, actor: String): NativePolicy =
        sessions[actor to name]?.let(NativePolicy::Search)
            ?: policies.create(name, policyContext, actor).also {
                if (it is NativePolicy.Search) sessions[actor to name] = it.session
            }

    private fun nativePlayer(policy: NativePolicy): Player = when (policy) {
        is NativePolicy.Direct -> policy.player
        is NativePolicy.Search -> searchPlayer(world, policy.session)
    }

    private fun nativePlayer(name: String, actor: String): Player = nativePlayer(nativePolicy(name, actor))

    /** The CLI and live connection share the same native policy construction and observers. */
    val players: Map<String, Player> get() = actors.mapIndexed { i, actor ->
        actor to observedPlayer(actor, nativePlayer(plan.policies[i], actor))
    }.toMap()

    /** Check names and settings, and start search memory, including a shadow's, before the first move. */
    fun initializePolicies() {
        policies.checkSettings(plan)
        plan.policies.forEachIndexed { index, name -> nativePolicy(name, actors[index]) }
        plan.shadowPolicies.forEach { name -> actors.forEach { actor -> nativePolicy(name, actor) } }
    }

    /** Advance each existing native memory once, even when Python overrides the selected action. */
    private fun observedPlayer(actor: String, player: Player): Player = Player(player.view,
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
            world.acceptedDecisionCountForHost.toString())
        val selection = (policy as? NativePolicy.Search)?.session?.select(world, actor, selectionSeed)
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

    fun step(index: Int, view: DecisionView, choice: SemanticChoice, record: Boolean = true): JsonObject {
        check(index == world.acceptedDecisionCountForHost) { "Action belongs to an earlier decision; inspect the current game" }
        check(world.terminalPayoff(actors.first()) == null) { "Cannot step a terminal game" }
        val players = actors.associateWith { actor -> observedPlayer(actor, Player(view) { _, _ -> choice }) }
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
    fun compare(candidateSeat: String, incumbent: String, maximumDecisions: Int?, maximumSeconds: Double?): JsonObject {
        require(candidateSeat in actors)
        val baseline = nativePlayer(incumbent, candidateSeat)
        var decisions = 0
        var changed = 0
        val compared = players.toMutableMap()
        val candidate = compared.getValue(candidateSeat)
        compared[candidateSeat] = Player(candidate.view, candidate.observe) { request, seed ->
            val expected = baseline.choose(context(baseline.view), seed)
            val selected = candidate.choose(request, seed)
            decisions++
            if (expected.signature != selected.signature) changed++
            selected
        }
        val result = playGame(world, compared, plan.seed, maximumDecisions, maximumSeconds)
        return buildJsonObject {
            put("result", researchJson.encodeToJsonElement(result))
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

/** One private, synchronous connection; the protocol the Python session speaks. */
class PythonResearchConnection {
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
                remember(PythonGame.create(plan, registry, "python-game-${plan.seed}"))
            }
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
                request["maximumSeconds"]?.jsonPrimitive?.doubleOrNull)
            "information" -> researchJson.encodeToJsonElement(game().world.informationState(request.getValue("player").jsonPrimitive.content))
            "value-features" -> {
                val world = game().world
                val player = request["player"]?.jsonPrimitive?.content ?: requireNotNull(world.actorToAct())
                researchJson.encodeToJsonElement(ValueFeatures.compile(world.informationState(player), player).values)
            }
            "value-snapshot" -> game().valueSnapshot()
            "value-score" -> game().valueScore(
                request.getValue("player").jsonPrimitive.content,
                researchJson.decodeFromJsonElement(request.getValue("weights")),
                request["link"]?.let { researchJson.decodeFromJsonElement(it) } ?: LinearValueLink.CLIP)
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
