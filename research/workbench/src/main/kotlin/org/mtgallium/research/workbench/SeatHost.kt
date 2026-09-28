package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.MulliganStateComponent
import com.wingedsheep.gameserver.protocol.ErrorCode
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.sdk.model.EntityId
import java.io.BufferedWriter
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.argentum.ArgentumRawTransition

/**
 * A player at an Argentum seat. It is sent, as JSON text, exactly the messages Argentum sends a
 * browser seat, and answers with the JSON action that seat would send. It sees nothing else.
 */
interface SeatAgent {
    fun receive(message: String)

    /** The action to send now; called when the seat must act, after the messages that led here. */
    fun act(): String

    /** Counters the agent reports with the game's results, such as its own CPU time. */
    fun stats(): Map<String, Double> = emptyMap()
}

/**
 * Hosts a world's game as an Argentum browser game. Every accepted engine action goes in through
 * its actor's seat, forced ones included, and both seats are sent their update, as a server does for
 * two browser players. The host is set to the world's state before each action, so it follows the
 * world's game; with [verify] it also checks that it reaches the world's state after each one.
 *
 * Seats played by a [SeatAgent] choose their own actions ([act]); the world then applies the same
 * action. Other seats' actions arrive from the world ([accept]).
 *
 * With logs, each seat's log holds exactly what that seat is sent and sends back, in its own card
 * names. The truth log is privileged: before each action it holds both hands and the next cards of
 * both libraries, for training targets only.
 */
class SeatHost(
    registry: CardRegistry,
    initial: GameState,
    private val agents: Map<String, SeatAgent> = emptyMap(),
    private val seatLogs: Map<String, BufferedWriter> = emptyMap(),
    private val truthLog: BufferedWriter? = null,
    private val verify: Boolean = false,
    private val libraryDepth: Int = 3,
) {
    private val session = GameSession(cardRegistry = registry)
    private val seats = initial.turnOrder
    private val epochs = HashMap<EntityId, String>()
    private var step = 0
    private var decision = -1
    private var decisionActor: String? = null
    private var candidates = 0
    private var hosted: GameState? = null
    /** Actions an agent sent that the host refused, and fallbacks after repeated refusals. */
    var refused = 0
        private set
    var fallbacks = 0
        private set
    private var lastRefusal: String? = null

    init {
        require(seatLogs.isEmpty() || seatLogs.size == seats.size) { "One seat log per player" }
        session.injectStateForTesting(initial, emptyMap())
        sendAll(emptyList())
    }

    fun agentStats(): Map<String, Map<String, Double>> = agents.mapValues { it.value.stats() }

    /** Called before each policy decision with the world's actor alias and menu size. */
    fun beforeDecision(index: Int, actor: String, menuSize: Int) {
        decision = index
        decisionActor = actor
        candidates = menuSize
    }

    /** An action another player took in the world. */
    fun accept(transition: ArgentumRawTransition) {
        if (!transition.accepted) return
        val before = transition.beforeState
        session.injectStateForTesting(before, emptyMap())
        val action = transition.action
        val actor = action.playerId
        check(aliasOf(actor) == decisionActor) { "Decision $decision by $decisionActor submitted an action for ${aliasOf(actor)}" }
        writeTruth(before, actor)
        val events: List<GameEvent> = when (action) {
            is KeepHand, is TakeMulligan -> {
                receive(actor, session.getMulliganDecision(actor))
                sent(actor, action)
                mulligan(actor, action)
            }
            is BottomCards -> {
                session.getChooseBottomCardsMessage(actor)?.let { receive(actor, it) }
                val seatAction = session.toSeat(actor, action, GameAction.serializer()) as BottomCards
                sent(actor, seatAction)
                mulligan(actor, seatAction)
            }
            else -> {
                var seatAction = session.toSeat(actor, action, GameAction.serializer())
                if (seatAction is SubmitDecision) seatAction = seatAction.copy(
                    response = seatAction.response.withDecisionId("${epochs.getValue(actor)}:${seatAction.response.decisionId}"))
                sent(actor, seatAction)
                execute(actor, seatAction) ?: error("Host refused $action")
            }
        }
        sendAll(events)
        if (verify) checkFollowed(transition.afterState)
    }

    /**
     * Ask the agent at [alias]'s seat for its action at the world's [state], apply it for both seats,
     * and return it in engine names for the world to apply.
     */
    fun act(alias: String, state: GameState): GameAction {
        val agent = agents.getValue(alias)
        val actor = seats[alias.removePrefix("p").toInt()]
        check(alias == decisionActor) { "The world asks $decisionActor, not $alias" }
        session.injectStateForTesting(state, emptyMap())
        writeTruth(state, actor)
        val mulligan = state.getEntity(actor)?.get<MulliganStateComponent>()
        when {
            session.isAwaitingBottomCards(actor) -> receive(actor, requireNotNull(session.getChooseBottomCardsMessage(actor)))
            mulligan != null && !mulligan.hasKept -> receive(actor, session.getMulliganDecision(actor))
        }
        repeat(3) {
            val sent = agent.act()
            val seatAction = runCatching { SeatHost.wireJson.decodeFromString(GameAction.serializer(), sent) }
                .onFailure { lastRefusal = "undecodable: ${it.message}" }.getOrNull()
            val engineAction = seatAction?.takeIf { it.playerId == actor }?.let { toEngine(actor, it) }
            if (seatAction != null && engineAction != null) {
                val before = session.getStateForTesting()
                val events = when (seatAction) {
                    is KeepHand, is TakeMulligan, is BottomCards -> runCatching { mulligan(actor, seatAction) }
                        .onFailure { lastRefusal = it.message }.getOrNull()
                    else -> execute(actor, seatAction)
                }
                if (events != null) {
                    sent(actor, seatAction)
                    sendAll(events)
                    hosted = session.getStateForTesting()
                    return engineAction
                }
                session.injectStateForTesting(requireNotNull(before), emptyMap())
            }
            refused++
            if (engineAction == null && seatAction != null) lastRefusal = "not this seat's action or unknown names: $sent"
            receive(actor, ServerMessage.Error(ErrorCode.INVALID_ACTION, lastRefusal ?: "Action refused"))
        }
        // Repeatedly refused: pass priority when the seat may, otherwise stop the game.
        fallbacks++
        val pass = session.getStateForTesting()?.let { current ->
            com.wingedsheep.engine.core.PassPriority(actor).takeIf { current.pendingDecision == null && current.priorityPlayerId == actor }
        } ?: error("Seat agent $alias sent no acceptable action at decision $decision: $lastRefusal")
        sent(actor, pass)
        sendAll(execute(actor, pass) ?: error("Host refused a fallback pass at decision $decision after: $lastRefusal"))
        hosted = session.getStateForTesting()
        return pass
    }

    /** After a seat's own action, the world must reach the state the host reached. */
    fun checkFollowed(world: GameState) {
        val mine = hosted ?: session.getStateForTesting()
        hosted = null
        check(mine == world || withoutUuids(mine) == withoutUuids(world)) { "Host and world diverged after decision $decision" }
    }

    private fun toEngine(actor: EntityId, action: GameAction): GameAction? = when (action) {
        is KeepHand, is TakeMulligan -> action
        else -> session.fromSeat(actor, action, GameAction.serializer())
    }

    private fun mulligan(actor: EntityId, action: GameAction): List<GameEvent> {
        val result = when (action) {
            is KeepHand -> session.keepHand(actor)
            is TakeMulligan -> session.takeMulligan(actor)
            is BottomCards -> session.chooseBottomCards(actor, action.cardIds)
            else -> error("Not a mulligan action: $action")
        }
        check(result !is GameSession.MulliganActionResult.Failure) { "Host refused $action: $result" }
        return emptyList()
    }

    private fun execute(actor: EntityId, seatAction: GameAction): List<GameEvent>? =
        when (val result = session.executeClientAction(actor, seatAction, interactionEpoch = epochs.getValue(actor))) {
            is GameSession.ActionResult.Success -> result.events
            is GameSession.ActionResult.PausedForDecision -> result.events
            is GameSession.ActionResult.Failure -> null.also { lastRefusal = "$seatAction: ${result.reason}" }
        }

    private fun writeTruth(before: GameState, actor: EntityId) {
        step++
        truthLog?.writeRecord(buildJsonObject {
            put("step", step)
            put("decision", decision)
            put("actor", aliasOf(actor))
            put("candidates", candidates)
            putJsonObject("players") {
                for (player in seats) put(aliasOf(player), buildJsonObject {
                    put("hand", JsonArray(before.getHand(player).map { JsonPrimitive(nameOf(before, it)) }.sortedBy { it.content }))
                    put("library", JsonArray(before.getLibrary(player).take(libraryDepth).map { JsonPrimitive(nameOf(before, it)) }))
                    put("librarySize", before.getLibrary(player).size)
                })
            }
        })
    }

    private fun aliasOf(player: EntityId): String = "p${seats.indexOf(player)}"

    private fun nameOf(state: GameState, card: EntityId): String =
        requireNotNull(state.getEntity(card)?.get<CardComponent>()?.name) { "No card component for $card" }

    private fun sendAll(events: List<GameEvent>) = seats.forEach { seat ->
        val message = requireNotNull(session.createStateUpdate(seat, events, useEngineDecisionIds = false)) { "No update for $seat" }
        epochs[seat] = when (message) {
            is ServerMessage.StateUpdate -> message.interactionEpoch
            is ServerMessage.StateDeltaUpdate -> message.interactionEpoch
            else -> error("No state update for $seat: $message")
        } ?: error("Update has no interaction epoch")
        receive(seat, message)
    }

    private fun receive(seat: EntityId, message: ServerMessage) {
        val alias = aliasOf(seat)
        val agent = agents[alias]
        val log = seatLogs[alias]
        if (agent == null && log == null) return
        val encoded = wireJson.encodeToJsonElement(ServerMessage.serializer(), message)
        agent?.receive(wireJson.encodeToString(JsonElement.serializer(), encoded))
        log?.let { write(it, buildJsonObject { put("step", step); put("received", encoded) }) }
    }

    private fun sent(seat: EntityId, action: GameAction) {
        val log = seatLogs[aliasOf(seat)] ?: return
        write(log, buildJsonObject {
            put("step", step)
            put("decision", decision)
            put("sent", wireJson.encodeToJsonElement(GameAction.serializer(), action))
        })
    }

    private fun write(writer: BufferedWriter, record: JsonObject) {
        writer.write(wireJson.encodeToString(JsonObject.serializer(), record))
        writer.newLine()
    }

    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private fun withoutUuids(state: GameState?): String = uuid.replace(state?.entities.toString(), "") +
        uuid.replace(state?.copy(entities = emptyMap()).toString(), "")

    companion object {
        /** Argentum's WebSocket encoding (`MessageSender.json`). */
        val wireJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
            serializersModule = engineSerializersModule
        }
    }
}
