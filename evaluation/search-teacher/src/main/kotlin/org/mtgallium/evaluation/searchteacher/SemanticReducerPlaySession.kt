package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import java.io.BufferedReader
import java.io.PrintWriter
import kotlinx.serialization.encodeToString
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.searchteacher.SemanticHeuristicOpponentPolicy
import org.mtgallium.agent.infoset.core.exactSingletonPassOrNull

/**
 * Human-vs-computer terminal loop over the production semantic policy boundary.
 *
 * Both seats choose [SemanticChoice] values and advance through [ArgentumSearchWorld.step], so this
 * is an interactive reducer probe rather than a second game client with its own rules logic.
 */
internal class SemanticReducerPlaySession(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val seed: Long,
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val decisionLimit: Int = SearchTeacherArena.MAX_GAME_DECISIONS,
) {
    private val gameId = "interactive-$seed"
    private val human = "p0"
    private val computer = "p1"
    private val knownDecks = mapOf(human to manifest.mainDeck, computer to manifest.mainDeck)

    fun run(): SemanticPlayOutcome {
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("You", manifest.deck()),
                    PlayerConfig("Computer", manifest.deck()),
                ),
                skipMulligans = false,
                useHandSmoother = false,
                startingPlayerIndex = 0,
                seed = seed,
            )
        )
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = gameId,
            seedBase = seed,
            cardRegistry = registry,
            knownDecks = knownDecks,
        )

        output.println("MTGallium semantic reducer play")
        output.println("You are p0 against the deterministic heuristic computer (p1).")
        output.println("Both players use ${manifest.name}; game seed $seed.")
        output.println("Every move below is expanded, validated, stepped, and reduced through the research boundary.")
        output.println("Type 'help' for inspection commands or 'quit' to leave the game.")
        output.println()
        output.flush()

        var decisions = 0
        while (world.terminalPayoff(human) == null && decisions < decisionLimit) {
            val actor = requireNotNull(world.actorToAct()) { "Non-terminal game has no actor" }
            val before = world.informationState(human)
            val expansion = world.expandChoices()
            check(expansion.candidates.isNotEmpty()) { "No semantic choices at decision $decisions" }

            if (actor == computer) {
                val choice = chooseComputer(world, decisions)
                val step = world.step(choice)
                check(step.accepted) { "Computer semantic choice rejected: ${step.diagnostic}" }
                if (step.privateToActor) {
                    output.println("Computer completed a private decision.")
                } else {
                    output.println("Computer: ${formatChoice(choice, includeSignature = true)}")
                }
                printReducerDelta(before, world.informationState(human))
                decisions++
                continue
            }

            val forcedPass = expansion.exactSingletonPassOrNull()
            if (forcedPass != null) {
                val choice = forcedPass
                output.println("Forced: ${formatChoice(choice, includeSignature = true)}")
                val step = world.step(choice)
                check(step.accepted) { "Forced semantic choice rejected: ${step.diagnostic}" }
                printReducerDelta(before, world.informationState(human))
                decisions++
                continue
            }

            renderState(before, expansion.isExhaustive, expansion.estimatedCandidateCount)
            while (true) {
                output.print("semantic> ")
                output.flush()
                val line = input.readLine()?.trim() ?: return abandoned(decisions)
                if (line.isEmpty()) continue
                val words = line.split(Regex("\\s+"), limit = 2)
                when (words[0].lowercase()) {
                    "q", "quit", "exit" -> return abandoned(decisions)
                    "h", "help", "?" -> printHelp()
                    "s", "state" -> renderState(
                        world.informationState(human),
                        expansion.isExhaustive,
                        expansion.estimatedCandidateCount,
                    )
                    "history" -> printHistory(world.informationState(human))
                    "knowledge" -> printKnowledge(world.informationState(human))
                    "json" -> printInformationJson(world.informationState(human))
                    "payload" -> printPayload(words.getOrNull(1), expansion.candidates)
                    else -> {
                        val selected = words[0].toIntOrNull()
                        if (selected == null || selected !in 1..expansion.candidates.size) {
                            output.println("Choose 1-${expansion.candidates.size}, or type 'help'.")
                            continue
                        }
                        val choice = expansion.candidates[selected - 1]
                        output.println("You: ${formatChoice(choice, includeSignature = true)}")
                        val step = world.step(choice)
                        check(step.accepted) { "Your semantic choice was rejected: ${step.diagnostic}" }
                        printReducerDelta(before, world.informationState(human))
                        decisions++
                        break
                    }
                }
            }
        }

        if (world.terminalPayoff(human) != null) {
            val payoff = world.terminalPayoff(human)
            val result = when (payoff) {
                1.0 -> "You win."
                -1.0 -> "Computer wins."
                else -> "The game is a draw."
            }
            val information = world.informationState(human)
            output.println()
            output.println("Game over after $decisions semantic decisions. $result")
            output.println(
                "Final reducer: history=${information.historyCursor}, " +
                    "knowledge=${information.knowledge.knowledgeDigest.take(DIGEST_LENGTH)}, " +
                    "complete=${information.knowledge.epistemicallyComplete}"
            )
            output.flush()
            return SemanticPlayOutcome(completed = true, decisions = decisions, payoff = payoff)
        }

        output.println("Decision limit $decisionLimit reached; the game was stopped as a likely bug.")
        output.flush()
        return SemanticPlayOutcome(completed = false, decisions = decisions, payoff = null)
    }

    private fun chooseComputer(world: ArgentumSearchWorld, decisionIndex: Int): SemanticChoice {
        world.determinizedHeuristicChoiceOrNull()?.let { return it }
        val information = world.informationState(computer)
        val candidates = world.expandChoices().candidates
        val policySeed = ComponentSeeds.derive(gameId, decisionIndex, "interactive-computer")
        val distribution = SemanticHeuristicOpponentPolicy().distribution(
            information,
            candidates,
            policySeed,
        )
        return sample(distribution, policySeed)
    }

    private fun renderState(
        information: PolicyInformationState,
        expansionIsExhaustive: Boolean,
        estimatedCandidateCount: Long?,
    ) {
        val observation = information.observation
        output.println()
        val turnOwner = when (observation.activePlayerId) {
            human -> "YOUR TURN"
            computer -> "COMPUTER'S TURN"
            else -> "NO ACTIVE PLAYER"
        }
        output.println("=== Turn ${observation.turnNumber} · $turnOwner · ${observation.phase}/${observation.step} ===")
        output.println("ACTION REQUIRED: ${playerLabel(information.actingPlayerId, observation).uppercase()}")
        observation.players.forEach { player ->
            val marker = when (player.playerId) {
                human -> "You"
                computer -> "Computer"
                else -> player.name
            }
            val flags = buildList {
                if (player.active) add("active")
                if (player.priority) add("priority")
                if (player.lost) add("lost")
            }.joinToString(", ").let { if (it.isEmpty()) "" else " [$it]" }
            output.println(
                "$marker: ${player.life} life · hand ${player.handSize} · library ${player.librarySize} · " +
                    "graveyard ${player.graveyardSize} · exile ${player.exileSize} · mana ${formatMana(player.mana)}$flags"
            )
        }

        printZone(observation, computer, "BATTLEFIELD", "Computer battlefield")
        printZone(observation, human, "BATTLEFIELD", "Your battlefield")
        printZone(observation, human, "HAND", "Your hand", includeRulesText = true)
        printZone(observation, computer, "GRAVEYARD", "Computer graveyard")
        printZone(observation, human, "GRAVEYARD", "Your graveyard")
        printZone(observation, computer, "EXILE", "Computer exile")
        printZone(observation, human, "EXILE", "Your exile")

        if (observation.stack.isNotEmpty()) {
            output.println("Stack:")
            observation.stack.forEach { item ->
                val targets = item.targets.takeIf { it.isNotEmpty() }?.joinToString()?.let { " -> $it" }.orEmpty()
                output.println("  - ${item.name} (${item.kind})$targets")
            }
        }
        observation.combat?.takeIf { it.attackers.isNotEmpty() || it.blockers.isNotEmpty() }?.let { combat ->
            output.println("Combat: ${combat.attackers.size} attacker(s), ${combat.blockers.size} blocker(s)")
        }
        observation.pendingDecision?.let { pending ->
            output.println("Decision: ${pending.prompt} [${pending.decisionKind}]")
        }

        val candidates = information.candidates
        val count = if (expansionIsExhaustive) {
            "${candidates.size} exhaustive semantic choice(s)"
        } else {
            "${candidates.size} proposed semantic choice(s) of ${estimatedCandidateCount ?: "unknown"}"
        }
        output.println("Choices: $count")
        candidates.forEachIndexed { index, choice ->
            output.println("  ${index + 1}) ${formatChoice(choice, includeSignature = true)}")
        }
        output.println(
            "Reducer: history=${information.historyCursor}, knowledge=${information.knowledge.knowledgeDigest.take(DIGEST_LENGTH)}, " +
                "complete=${information.knowledge.epistemicallyComplete}"
        )
        output.flush()
    }

    private fun playerLabel(playerId: String?, observation: PolicyObservation): String = when (playerId) {
        human -> "You"
        computer -> "Computer"
        null -> "Nobody"
        else -> observation.players.firstOrNull { it.playerId == playerId }?.name ?: playerId
    }

    private fun printZone(
        observation: PolicyObservation,
        owner: String,
        zoneName: String,
        heading: String,
        includeRulesText: Boolean = false,
    ) {
        val zone = observation.zones.singleOrNull { it.ownerId == owner && it.zone == zoneName } ?: return
        if (zone.cards.isEmpty()) return
        output.println("$heading:")
        zone.cards.forEach { card ->
            output.println("  - ${formatCard(card)}")
            if (includeRulesText && card.oracleText.isNotBlank()) {
                output.println("      ${card.oracleText.replace('\n', ' ')}")
            }
        }
    }

    private fun printReducerDelta(before: PolicyInformationState, after: PolicyInformationState) {
        val newEvents = after.history.drop(before.historyCursor)
        output.println(
            "Reducer: history ${before.historyCursor}->${after.historyCursor} (+${newEvents.size}), " +
                "knowledge ${before.knowledge.knowledgeDigest.take(DIGEST_LENGTH)}->" +
                "${after.knowledge.knowledgeDigest.take(DIGEST_LENGTH)}, " +
                "complete=${after.knowledge.epistemicallyComplete}"
        )
        newEvents.take(EVENT_PREVIEW_LIMIT).forEach { output.println("  ${formatEvent(it)}") }
        if (newEvents.size > EVENT_PREVIEW_LIMIT) {
            output.println("  ... ${newEvents.size - EVENT_PREVIEW_LIMIT} more; type 'history' at your next prompt")
        }
        if (!after.knowledge.epistemicallyComplete) {
            after.knowledge.unsupportedReasons.forEach { output.println("  REDUCER INCOMPLETE: $it") }
        }
        output.flush()
    }

    private fun printHelp() {
        output.println("Commands:")
        output.println("  <number>    submit that exact semantic choice")
        output.println("  payload N   show choice N's canonical semantic payload")
        output.println("  state       redraw the safe board and choices")
        output.println("  history     show the last 20 perspective-safe reducer events")
        output.println("  knowledge   show the exact reducer output")
        output.println("  json        print the complete perspective-safe policy state for a bug report")
        output.println("  quit        leave without changing the current state")
        output.flush()
    }

    private fun printPayload(rawIndex: String?, candidates: List<SemanticChoice>) {
        val index = rawIndex?.toIntOrNull()
        if (index == null || index !in 1..candidates.size) {
            output.println("Usage: payload <1-${candidates.size}>")
            return
        }
        val choice = candidates[index - 1]
        output.println("${choice.signature} ${choice.kind} ${choice.display.label}")
        output.println(PolicyJson.canonical(choice.canonicalPayload))
        output.flush()
    }

    private fun printHistory(information: PolicyInformationState) {
        output.println("Perspective-safe history (${information.historyCursor} events):")
        information.history.takeLast(HISTORY_LIMIT).forEach { output.println("  ${formatEvent(it)}") }
        output.flush()
    }

    private fun printKnowledge(information: PolicyInformationState) {
        val knowledge = information.knowledge
        output.println(
            "Knowledge ${knowledge.knowledgeDigest} · complete=${knowledge.epistemicallyComplete} · " +
                "known objects=${knowledge.knownObjects.size}"
        )
        knowledge.zones.forEach { zone ->
            val cards = zone.knownCardCounts.entries.joinToString { (name, count) -> "$name x$count" }
                .ifEmpty { "no known identities" }
            output.println("  ${zone.ownerId} ${zone.zone}: ${zone.size} cards; $cards")
        }
        knowledge.knownLibraryOrders.filter { it.top.isNotEmpty() }.forEach { order ->
            output.println("  ${order.playerId} library top: ${order.top.joinToString { it ?: "?" }}")
        }
        knowledge.unsupportedReasons.forEach { output.println("  UNSUPPORTED: $it") }
        output.flush()
    }

    private fun printInformationJson(information: PolicyInformationState) {
        output.println(evidenceJson.encodeToString(PolicyInformationState.serializer(), information))
        output.flush()
    }

    private fun abandoned(decisions: Int): SemanticPlayOutcome {
        output.println("Game left after $decisions semantic decisions.")
        output.flush()
        return SemanticPlayOutcome(completed = false, decisions = decisions, payoff = null)
    }

    private fun formatCard(card: PolicyCardView): String {
        val stats = if (card.power != null && card.toughness != null) " ${card.power}/${card.toughness}" else ""
        val state = buildList {
            if (card.tapped) add("tapped")
            if (card.summoningSick) add("summoning sick")
            if (card.damageMarked > 0) add("${card.damageMarked} damage")
            card.counters.forEach { (counter, count) -> add("$counter x$count") }
        }.joinToString(", ").let { if (it.isEmpty()) "" else " [$it]" }
        val cost = card.manaCost.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return "${card.name}$cost$stats$state"
    }

    private fun formatChoice(choice: SemanticChoice, includeSignature: Boolean): String {
        val source = choice.display.sourceName?.takeUnless { it == choice.display.label }
            ?.let { " · source $it" }.orEmpty()
        val targets = choice.display.targetNames.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " · targets ").orEmpty()
        val signature = if (includeSignature) " [${choice.signature.take(DIGEST_LENGTH)}]" else ""
        return "${choice.display.label}$source$targets$signature"
    }

    private fun formatEvent(event: PolicyHistoryEvent): String {
        val detail = event.detail?.let {
            PolicyJson.format.encodeToString(PerspectiveEventDetail.serializer(), it)
        } ?: PolicyJson.canonical(event.payload)
        return "#${event.eventId} ${event.kind} $detail"
    }

    private fun formatMana(mana: PolicyManaPool): String = buildList {
        if (mana.white > 0) add("W${mana.white}")
        if (mana.blue > 0) add("U${mana.blue}")
        if (mana.black > 0) add("B${mana.black}")
        if (mana.red > 0) add("R${mana.red}")
        if (mana.green > 0) add("G${mana.green}")
        if (mana.colorless > 0) add("C${mana.colorless}")
    }.joinToString(" ").ifEmpty { "empty" }

    private companion object {
        const val DIGEST_LENGTH = 12
        const val EVENT_PREVIEW_LIMIT = 6
        const val HISTORY_LIMIT = 20
    }
}

internal data class SemanticPlayOutcome(
    val completed: Boolean,
    val decisions: Int,
    val payoff: Double?,
)
