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
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.sdk.model.EntityId
import java.io.BufferedWriter
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.argentum.ArgentumRawTransition

/**
 * Hosts a world's game as an Argentum browser game and records, for each seat, exactly the
 * messages Argentum sends that seat and the actions the seat sends back, in its own card names.
 * Every accepted engine action goes in through its actor's seat, forced ones included, and both
 * seats are sent their update, as a server does for two browser players. The host is set to the
 * world's state before each action, so it follows the world's game; with [verify] it also checks
 * that it reaches the world's state after each one.
 *
 * The truth log is privileged: before each action it holds both hands and the next cards of both
 * libraries, for training targets only. Seat logs never contain it.
 */
class SeatRecorder(
    registry: CardRegistry,
    initial: GameState,
    private val seatLogs: Map<String, BufferedWriter>,
    private val truthLog: BufferedWriter,
    private val verify: Boolean = false,
    private val libraryDepth: Int = 3,
) {
    private val session = GameSession(cardRegistry = registry)
    private val seats = initial.turnOrder
    private val aliases = HashMap<EntityId, String>()
    private val epochs = HashMap<EntityId, String>()
    private var step = 0
    private var decision = -1
    private var decisionActor: String? = null
    private var candidates = 0

    init {
        require(seatLogs.size == seats.size) { "One seat log per player" }
        session.injectStateForTesting(initial, emptyMap())
        sendAll(emptyList())
    }

    /** Called before each policy decision with the world's actor alias and menu size. */
    fun beforeDecision(index: Int, actor: String, menuSize: Int) {
        decision = index
        decisionActor = actor
        candidates = menuSize
    }

    fun accept(transition: ArgentumRawTransition) {
        if (!transition.accepted) return
        val before = transition.beforeState
        session.injectStateForTesting(before, emptyMap())
        val action = transition.action
        val actor = action.playerId
        val alias = aliases.getOrPut(actor) { requireNotNull(decisionActor) { "Action before any decision" } }
        check(alias == decisionActor) { "Decision $decision by $decisionActor submitted an action for $alias" }
        check(alias == "p${seats.indexOf(actor)}") { "World alias $alias is not turn-order seat ${seats.indexOf(actor)}" }
        step++
        truthLog.writeRecord(buildJsonObject {
            put("step", step)
            put("decision", decision)
            put("actor", alias)
            put("candidates", candidates)
            putJsonObject("players") {
                for (player in seats) put(aliasOf(player), buildJsonObject {
                    put("hand", JsonArray(before.getHand(player).map { JsonPrimitive(nameOf(before, it)) }.sortedBy { it.content }))
                    put("library", JsonArray(before.getLibrary(player).take(libraryDepth).map { JsonPrimitive(nameOf(before, it)) }))
                    put("librarySize", before.getLibrary(player).size)
                })
            }
        })
        val events: List<GameEvent> = when (action) {
            is KeepHand, is TakeMulligan -> {
                receive(actor, session.getMulliganDecision(actor))
                sent(actor, action)
                val result = if (action is KeepHand) session.keepHand(actor) else session.takeMulligan(actor)
                check(result !is GameSession.MulliganActionResult.Failure) { "Host refused $action: $result" }
                emptyList()
            }
            is BottomCards -> {
                session.getChooseBottomCardsMessage(actor)?.let { receive(actor, it) }
                val seatAction = session.toSeat(actor, action, GameAction.serializer()) as BottomCards
                sent(actor, seatAction)
                val result = session.chooseBottomCards(actor, seatAction.cardIds)
                check(result !is GameSession.MulliganActionResult.Failure) { "Host refused $action: $result" }
                emptyList()
            }
            else -> {
                val epoch = epochs.getValue(actor)
                var seatAction = session.toSeat(actor, action, GameAction.serializer())
                if (seatAction is SubmitDecision) seatAction = seatAction.copy(
                    response = seatAction.response.withDecisionId("$epoch:${seatAction.response.decisionId}"))
                sent(actor, seatAction)
                when (val result = session.executeClientAction(actor, seatAction, interactionEpoch = epoch)) {
                    is GameSession.ActionResult.Success -> result.events
                    is GameSession.ActionResult.PausedForDecision -> result.events
                    is GameSession.ActionResult.Failure -> error("Host refused $action: ${result.reason}")
                }
            }
        }
        sendAll(events)
        if (verify) {
            val hosted = session.getStateForTesting()
            // Resolution keys are random UUIDs, so they differ between any two runs of the same action.
            check(hosted == transition.afterState || withoutUuids(hosted) == withoutUuids(transition.afterState)) {
                "Host diverged from the world after $action"
            }
        }
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

    private fun receive(seat: EntityId, message: ServerMessage) = log(seat, buildJsonObject {
        put("step", step)
        put("received", wireJson.encodeToJsonElement(ServerMessage.serializer(), message))
    })

    private fun sent(seat: EntityId, action: GameAction) = log(seat, buildJsonObject {
        put("step", step)
        put("decision", decision)
        put("sent", wireJson.encodeToJsonElement(GameAction.serializer(), action))
    })

    private fun log(seat: EntityId, record: JsonObject) {
        val writer = seatLogs.getValue(aliasOf(seat))
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
