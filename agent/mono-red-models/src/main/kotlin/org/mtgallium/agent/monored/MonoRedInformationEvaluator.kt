package org.mtgallium.agent.monored

import kotlin.math.tanh
import org.mtgallium.agent.infoset.core.InformationStateEvaluator
import org.mtgallium.agent.infoset.core.InformationStateRepresentation

internal const val MONO_RED_VISIBLE_V2_EVALUATOR_ID = "mono-red-visible-board-v2"

object MonoRedInformationEvaluator : InformationStateEvaluator {
    override val id: String = MONO_RED_VISIBLE_V2_EVALUATOR_ID

    override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double {
        val observation = information.observation
        val root = observation.players.single { it.playerId == rootPlayer }
        val opponent = observation.players.first { it.playerId != rootPlayer }
        val battlefield = observation.zones.filter { it.zone == "BATTLEFIELD" }.flatMap { it.cards }
        fun boardValue(player: String): Double {
            val permanents = battlefield.filter { it.controllerId == player }
            val lands = permanents.count { card -> card.types.any { it.equals("LAND", ignoreCase = true) } }
            val creatures = permanents.sumOf { card ->
                (card.power ?: 0) * 1.2 + (card.toughness ?: 0) * 0.4 +
                    if (card.keywords.any { it.equals("HASTE", ignoreCase = true) }) 0.3 else 0.0
            }
            return creatures + developedManaValue(lands)
        }
        fun handValue(size: Int): Double = size * 0.35
        val score = (root.life - opponent.life) * 0.12 +
            handValue(root.handSize) - handValue(opponent.handSize) +
            boardValue(rootPlayer) - boardValue(opponent.playerId)
        return tanh(score / 8.0)
    }

    /** Diminishing visible-state value for developed mana. */
    fun developedManaValue(lands: Int): Double {
        require(lands >= 0)
        val earlyMarginals = doubleArrayOf(1.00, 0.85, 0.70, 0.45, 0.25)
        return earlyMarginals.take(lands).sum() + (lands - earlyMarginals.size).coerceAtLeast(0) * 0.15
    }
}
