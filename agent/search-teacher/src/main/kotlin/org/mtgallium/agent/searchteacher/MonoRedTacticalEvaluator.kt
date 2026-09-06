package org.mtgallium.agent.searchteacher

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPlayerView

/** Disjoint weighted feature families. Priors inform reach and hand only. */
@Serializable
enum class TacticalFeatureFamily {
    NONLINEAR_LIFE,
    COMBAT_READINESS,
    REACH,
    HAND_VALUE,
    DURABLE_MANA,
    INITIATIVE,
}

@Serializable
data class MonoRedTacticalEvaluatorWeights(
    val life: Double = 1.20,
    val lethal: Double = 2.40,
    val body: Double = 0.90,
    val attack: Double = 1.00,
    val block: Double = 0.35,
    val reach: Double = 0.85,
    val hand: Double = 0.55,
    val mana: Double = 0.65,
    val initiative: Double = 0.25,
) {
    init {
        require(listOf(life, lethal, body, attack, block, reach, hand, mana, initiative).all(Double::isFinite))
    }

    val configurationId: String
        get() = listOf(life, lethal, body, attack, block, reach, hand, mana, initiative)
            .joinToString(",", transform = ::canonicalTacticalNumber)
}

@Serializable
data class MonoRedTacticalEvaluatorSettings(
    val schemaVersion: Int = 2,
    val outputTemperature: Double = 2.0,
    val startingLife: Int = 20,
    val annotationVersion: String = "mono-red-tactical-annotations-v2",
    val enabledFamilies: Set<TacticalFeatureFamily> = TacticalFeatureFamily.entries.toSet(),
    val weights: MonoRedTacticalEvaluatorWeights = MonoRedTacticalEvaluatorWeights(),
) {
    init {
        require(schemaVersion == 2)
        require(outputTemperature.isFinite() && outputTemperature > 0.0)
        require(startingLife > 0)
        require(annotationVersion.isNotBlank())
    }

    val configurationId: String
        get() = listOf(
            MonoRedTacticalEvaluator.EVALUATOR_ID,
            "schema-$schemaVersion",
            "temperature-${canonicalTacticalNumber(outputTemperature)}",
            "life-$startingLife",
            annotationVersion,
            enabledFamilies.sortedBy(TacticalFeatureFamily::name).joinToString("+") { it.name },
            "weights-${weights.configurationId}",
        ).joinToString(":")
}

private fun canonicalTacticalNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Serializable
data class TacticalEvaluationResult(
    val evaluatorId: String,
    val configurationId: String,
    val value: Double,
    val rawScore: Double,
    val components: Map<String, Double>,
    val flags: Set<String>,
)

/**
 * Projected-information tactical evaluator. Burn availability is an approximation, never a legal
 * spell claim. The tactical output is for nonterminal leaf evaluation only.
 */
class MonoRedTacticalEvaluator(
    val settings: MonoRedTacticalEvaluatorSettings = MonoRedTacticalEvaluatorSettings(),
) : ConfiguredInformationStateEvaluator {
    private val enabledFamilies = settings.enabledFamilies.toSet()

    override val id: String = EVALUATOR_ID
    override val configurationId: String = settings.configurationId

    override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double =
        evaluateDetailed(information, rootPlayer).value

    fun evaluateDetailed(
        information: PolicyInformationState,
        rootPlayer: String,
    ): TacticalEvaluationResult {
        require(!information.terminated) {
            "$id scores nonterminal leaves only; terminal utility must take precedence"
        }
        val observation = information.observation
        require(observation.perspectivePlayerId == rootPlayer) {
            "Evaluator perspective ${observation.perspectivePlayerId} does not match root $rootPlayer"
        }
        require(information.knowledge.perspectivePlayerId == rootPlayer) {
            "Knowledge perspective ${information.knowledge.perspectivePlayerId} does not match root $rootPlayer"
        }

        val root = observation.players.single { it.playerId == rootPlayer }
        val opponent = observation.players.single { it.playerId != rootPlayer }
        val flags = sortedSetOf<String>()
        if (!information.knowledge.epistemicallyComplete) flags += "knowledge-incomplete"

        val battlefield = observation.zones
            .filter { it.zone.equals("BATTLEFIELD", ignoreCase = true) && !it.hidden }
            .flatMap { it.cards }
            .filterNot(PolicyCardView::faceDown)
        val rootBattlefield = battlefield.filter { it.controllerId == rootPlayer }
        val opponentBattlefield = battlefield.filter { it.controllerId == opponent.playerId }
        if (root.mana.restricted.isNotEmpty() || opponent.mana.restricted.isNotEmpty()) {
            flags += "restricted-mana-omitted"
        }
        if (battlefield.any(::isUnrecognizedLand)) flags += "unrecognized-mana-source-omitted"

        val rootHand = observation.zones
            .filter { it.ownerId == rootPlayer && it.zone.equals("HAND", ignoreCase = true) }
            .flatMap { it.cards }
            .filterNot(PolicyCardView::faceDown)
            .map(::descriptorForCard)
        val knownOpponentHand = information.knowledge.zones
            .filter { it.ownerId == opponent.playerId && it.zone.equals("HAND", ignoreCase = true) }
            .flatMap { zone -> zone.knownCardCounts.flatMap { (name, count) -> List(count) { descriptorForName(name) } } }
        if ((rootHand + knownOpponentHand).any { it.annotationMissing }) flags += "annotation-missing"

        val rootCombat = combatFeatures(rootPlayer, rootBattlefield, observation)
        val opponentCombat = combatFeatures(opponent.playerId, opponentBattlefield, observation)
        if (rootCombat.approximationUsed || opponentCombat.approximationUsed) flags += "combat-approximation"

        val rootMana = currentMana(root, rootBattlefield)
        val opponentMana = currentMana(opponent, opponentBattlefield)
        val rootBurnNow = bestBurn(rootHand, rootMana)
        val rootBurnReserve = bestBurnAcrossWindows(rootHand, rootMana, nextUntapMana(rootBattlefield))
        val opponentPrior = opponentPrior(
            information = information,
            opponent = opponent.playerId,
            handSize = opponent.handSize,
            known = knownOpponentHand,
            mana = opponentMana,
            nextMana = nextUntapMana(opponentBattlefield),
            rootLife = root.life,
        )
        if (opponentPrior.used) flags += "opponent-safe-prior"
        if (!opponentPrior.available) flags += "opponent-prior-missing"

        fun enabled(family: TacticalFeatureFamily, value: Double): Double =
            if (family in enabledFamilies) value else 0.0

        val components = linkedMapOf<String, Double>()
        components["phiLife"] = enabled(
            TacticalFeatureFamily.NONLINEAR_LIFE,
            lifeUtility(root.life) - lifeUtility(opponent.life),
        )
        components["phiBody"] = enabled(
            TacticalFeatureFamily.COMBAT_READINESS,
            normalizedDifference(rootCombat.body, opponentCombat.body, 12.0),
        )
        // This is potential attacker power, never inferred unblocked face damage.
        components["phiAttackCapacity"] = enabled(
            TacticalFeatureFamily.COMBAT_READINESS,
            normalizedDifference(rootCombat.attackCapacity, opponentCombat.attackCapacity, 8.0),
        )
        components["phiBlock"] = enabled(
            TacticalFeatureFamily.COMBAT_READINESS,
            normalizedDifference(rootCombat.blockCapacity, opponentCombat.blockCapacity, 8.0),
        )
        components["rootBurnNow"] = rootBurnNow
        components["opponentExpectedBurnNow"] = opponentPrior.now
        components["phiLethal"] = enabled(
            TacticalFeatureFamily.REACH,
            sigmoid((rootBurnNow - opponent.life + 0.5) / 1.25) - opponentPrior.lethalAgainstRoot,
        )
        components["phiReach"] = enabled(
            TacticalFeatureFamily.REACH,
            lifeLossUtility(opponent.life, rootBurnReserve) - opponentPrior.reserveLifeLoss,
        )
        components["phiHand"] = enabled(
            TacticalFeatureFamily.HAND_VALUE,
            normalizedDifference(
                retainedNonReachValue(rootHand),
                retainedNonReachValue(knownOpponentHand) + opponentPrior.expectedNonReach,
                8.0,
            ),
        )
        components["phiDurableMana"] = enabled(
            TacticalFeatureFamily.DURABLE_MANA,
            normalizedDifference(
                MonoRedInformationEvaluator.developedManaValue(controlledLands(rootBattlefield)),
                MonoRedInformationEvaluator.developedManaValue(controlledLands(opponentBattlefield)),
                8.0,
            ),
        )
        components["phiInitiative"] = enabled(
            TacticalFeatureFamily.INITIATIVE,
            initiative(rootPlayer, observation) - initiative(opponent.playerId, observation),
        )

        val weights = settings.weights
        val rawScore = weights.life * components.getValue("phiLife") +
            weights.lethal * components.getValue("phiLethal") +
            weights.body * components.getValue("phiBody") +
            weights.attack * components.getValue("phiAttackCapacity") +
            weights.block * components.getValue("phiBlock") +
            weights.reach * components.getValue("phiReach") +
            weights.hand * components.getValue("phiHand") +
            weights.mana * components.getValue("phiDurableMana") +
            weights.initiative * components.getValue("phiInitiative")
        val value = 0.95 * tanh(rawScore.coerceIn(-6.0, 6.0) / settings.outputTemperature)
        require(value.isFinite() && value > -0.95 && value < 0.95)

        return TacticalEvaluationResult(
            evaluatorId = id,
            configurationId = configurationId,
            value = value,
            rawScore = rawScore,
            components = components.toSortedMap(),
            flags = flags,
        )
    }

    private fun lifeUtility(life: Int): Double =
        ln(1.0 + life.coerceIn(0, settings.startingLife * 2)) / ln(1.0 + settings.startingLife * 2.0)

    private fun lifeLossUtility(life: Int, damage: Double): Double =
        lifeUtility(life) - lifeUtility((life - damage).coerceAtLeast(0.0).toInt())

    private fun combatFeatures(
        player: String,
        own: List<PolicyCardView>,
        observation: PolicyObservation,
    ): CombatFeatures {
        val creatures = own.filter { it.types.any { type -> type.equals("CREATURE", ignoreCase = true) } }
        val declared = observation.combat?.takeIf { it.attackingPlayerId == player }
        val body = creatures.sumOf { card ->
            0.45 * (card.power ?: 0).coerceIn(0, 8) +
                0.25 * damageMargin(card).coerceIn(0, 8) +
                0.30
        }
        val block = creatures
            .filter { !it.tapped && damageMargin(it) > 0 }
            .sumOf { damageMargin(it).coerceAtMost(4).toDouble() }
        val attack = if (observation.activePlayerId == player && attackWindowRemains(observation)) {
            val attackers = if (declared == null) {
                creatures.filter { card ->
                    !card.tapped && (!card.summoningSick || card.hasKeyword("HASTE"))
                }
            } else {
                val committedRefs = declared.attackers.mapTo(sortedSetOf()) { it.attackerObjectRef }
                creatures.filter { it.objectRef in committedRefs }
            }
            attackers.sumOf { (it.power ?: 0).coerceAtLeast(0).toDouble() }
        } else {
            0.0
        }
        return CombatFeatures(
            body = body,
            attackCapacity = attack,
            blockCapacity = block,
            approximationUsed = declared == null || creatures.any(::hasUnsupportedCombatKeyword),
        )
    }

    private fun damageMargin(card: PolicyCardView): Int =
        ((card.toughness ?: 0) - card.damageMarked).coerceAtLeast(0)

    private fun attackWindowRemains(observation: PolicyObservation): Boolean =
        observation.phase.equals("PRECOMBAT_MAIN", ignoreCase = true) ||
            (observation.phase.equals("COMBAT", ignoreCase = true) &&
                observation.step.uppercase() !in setOf("FIRST_STRIKE_COMBAT_DAMAGE", "COMBAT_DAMAGE", "END_COMBAT"))

    private fun controlledLands(battlefield: List<PolicyCardView>): Int =
        battlefield.count { it.types.any { type -> type.equals("LAND", ignoreCase = true) } }

    private fun currentMana(player: PolicyPlayerView, battlefield: List<PolicyCardView>): Mana {
        val availableLands = battlefield.filter { card ->
            !card.tapped && isManaSource(card) && (!card.isCreatureLand() || !card.summoningSick || card.hasKeyword("HASTE"))
        }
        return Mana(
            total = player.mana.total() + availableLands.size,
            red = player.mana.red + availableLands.count(::redSource),
        )
    }

    // Current lands only: no projected land drop and no floating mana next turn.
    private fun nextUntapMana(battlefield: List<PolicyCardView>): Mana = Mana(
        total = battlefield.count(::isManaSource),
        red = battlefield.count(::redSource),
    )

    private fun isManaSource(card: PolicyCardView): Boolean =
        card.types.any { it.equals("LAND", ignoreCase = true) } && card.name in MANA_SOURCES

    private fun isUnrecognizedLand(card: PolicyCardView): Boolean =
        card.types.any { it.equals("LAND", ignoreCase = true) } && !isManaSource(card)

    private fun redSource(card: PolicyCardView): Boolean = isManaSource(card) && card.name in RED_SOURCES

    /** Exact 0/1 knapsack over projected burn cards and one mana window. */
    private fun bestBurn(cards: List<CardTacticalDescriptor>, mana: Mana): Double {
        var states = mapOf(ManaSpent() to 0.0)
        cards.filter { it.faceDamage > 0.0 }.forEach { card ->
            val next = states.toMutableMap()
            states.forEach { (spent, damage) ->
                val nextSpent = spent + card.manaCost.toManaSpent(card.requiresRed)
                if (nextSpent.fits(mana)) {
                    next[nextSpent] = maxOf(next[nextSpent] ?: 0.0, damage + card.faceDamage)
                }
            }
            states = next
        }
        return states.values.maxOrNull() ?: 0.0
    }

    /**
     * Exact two-window allocation. A card is omitted, paid in the current window, or paid after
     * the next untap; it cannot spend mana pooled across windows or be cast twice.
     */
    private fun bestBurnAcrossWindows(
        cards: List<CardTacticalDescriptor>,
        currentMana: Mana,
        nextMana: Mana,
    ): Double {
        var states = mapOf(TwoWindowSpent() to 0.0)
        cards.filter { it.faceDamage > 0.0 }.forEach { card ->
            val next = states.toMutableMap()
            states.forEach { (spent, damage) ->
                val cost = card.manaCost.toManaSpent(card.requiresRed)
                val current = spent.copy(current = spent.current + cost)
                if (current.current.fits(currentMana)) {
                    next[current] = maxOf(next[current] ?: 0.0, damage + card.faceDamage)
                }
                val future = spent.copy(next = spent.next + cost)
                if (future.next.fits(nextMana)) {
                    next[future] = maxOf(next[future] ?: 0.0, damage + card.faceDamage)
                }
            }
            states = next
        }
        return states.values.maxOrNull() ?: 0.0
    }

    /**
     * Mixes over the public depletion ledger. Nonlinear terms are calculated for each possible
     * composition before weighted averaging, never from an expected damage total.
     */
    private fun opponentPrior(
        information: PolicyInformationState,
        opponent: String,
        handSize: Int,
        known: List<CardTacticalDescriptor>,
        mana: Mana,
        nextMana: Mana,
        rootLife: Int,
    ): OpponentPrior {
        val unlocated = information.knowledge.unlocatedCardCounts[opponent].orEmpty()
        val unknown = (handSize - known.size).coerceAtLeast(0)
        val knownNow = bestBurn(known, mana)
        val knownReserve = bestBurnAcrossWindows(known, mana, nextMana)
        if (unknown == 0) return knownPrior(knownNow, knownReserve, rootLife, available = true)
        if (unknown > unlocated.values.sum() || unlocated.isEmpty()) {
            return knownPrior(knownNow, knownReserve, rootLife, available = false)
        }

        val shockCount = unlocated.entries.sumOf { (name, count) ->
            if (descriptorForName(name).burnGroup == 1) count else 0
        }
        val strikeCount = unlocated.entries.sumOf { (name, count) ->
            if (descriptorForName(name).burnGroup == 2) count else 0
        }
        val total = unlocated.values.sum()
        var now = 0.0
        var reserve = 0.0
        var lethalAgainstRoot = 0.0
        var reserveLifeLoss = 0.0
        for (shocks in 0..minOf(shockCount, unknown)) {
            for (strikes in 0..minOf(strikeCount, unknown - shocks)) {
                val other = unknown - shocks - strikes
                if (other > total - shockCount - strikeCount) continue
                val probability = choose(shockCount, shocks) * choose(strikeCount, strikes) *
                    choose(total - shockCount - strikeCount, other) / choose(total, unknown)
                if (probability == 0.0) continue
                val cards = known + List(shocks) { SHOCK } + List(strikes) { STRIKE }
                val current = bestBurn(cards, mana)
                val future = bestBurnAcrossWindows(cards, mana, nextMana)
                now += probability * current
                reserve += probability * future
                lethalAgainstRoot += probability * sigmoid((current - rootLife + 0.5) / 1.25)
                reserveLifeLoss += probability * lifeLossUtility(rootLife, future)
            }
        }
        val expectedNonReach = unknown * unlocated.entries.sumOf { (name, count) ->
            descriptorForName(name).nonReachValue * count
        } / total
        return OpponentPrior(now, reserve, expectedNonReach, lethalAgainstRoot, reserveLifeLoss, used = true, available = true)
    }

    private fun knownPrior(now: Double, reserve: Double, rootLife: Int, available: Boolean): OpponentPrior =
        OpponentPrior(
            now = now,
            reserve = reserve,
            expectedNonReach = 0.0,
            lethalAgainstRoot = sigmoid((now - rootLife + 0.5) / 1.25),
            reserveLifeLoss = lifeLossUtility(rootLife, reserve),
            used = false,
            available = available,
        )

    private fun choose(n: Int, k: Int): Double {
        if (k < 0 || k > n) return 0.0
        var result = 1.0
        for (index in 1..k) result = result * (n - k + index) / index
        return result
    }

    private fun retainedNonReachValue(cards: List<CardTacticalDescriptor>): Double = cards.sumOf { it.nonReachValue }

    private fun initiative(player: String, observation: PolicyObservation): Double =
        (if (observation.priorityPlayerId == player) 0.5 else 0.0) +
            (if (observation.activePlayerId == player && attackWindowRemains(observation)) 0.5 else 0.0)

    private fun descriptorForCard(card: PolicyCardView): CardTacticalDescriptor {
        val known = descriptorForName(card.name)
        return if (!known.annotationMissing) {
            known
        } else {
            known.copy(
                manaCost = card.manaValue.coerceAtLeast(0),
                bodyCreated = if (card.types.any { it.equals("CREATURE", ignoreCase = true) }) {
                    0.35 + 0.30 * card.manaValue.coerceIn(0, 6)
                } else {
                    0.0
                },
            )
        }
    }

    private fun descriptorForName(name: String): CardTacticalDescriptor =
        CARD_DESCRIPTORS[name] ?: CardTacticalDescriptor(annotationMissing = true)

    private fun normalizedDifference(left: Double, right: Double, scale: Double): Double =
        ((left - right) / scale).coerceIn(-1.0, 1.0)

    private fun sigmoid(value: Double): Double = 1.0 / (1.0 + exp(-value))

    private fun PolicyCardView.hasKeyword(keyword: String): Boolean =
        keywords.any { it.equals(keyword, ignoreCase = true) }

    private fun PolicyCardView.isCreatureLand(): Boolean =
        types.any { it.equals("CREATURE", ignoreCase = true) }

    private fun hasUnsupportedCombatKeyword(card: PolicyCardView): Boolean =
        card.keywords.any { it.uppercase() !in SUPPORTED_COMBAT_KEYWORDS }

    private data class CombatFeatures(
        val body: Double,
        val attackCapacity: Double,
        val blockCapacity: Double,
        val approximationUsed: Boolean,
    )

    private data class Mana(
        val total: Int,
        val red: Int,
    )

    private data class ManaSpent(
        val total: Int = 0,
        val red: Int = 0,
    ) {
        operator fun plus(other: ManaSpent): ManaSpent = ManaSpent(total + other.total, red + other.red)
        fun fits(mana: Mana): Boolean = total <= mana.total && red <= mana.red
    }

    private data class TwoWindowSpent(
        val current: ManaSpent = ManaSpent(),
        val next: ManaSpent = ManaSpent(),
    )

    private fun Int.toManaSpent(requiresRed: Boolean): ManaSpent =
        ManaSpent(total = this, red = if (requiresRed) 1 else 0)

    private data class OpponentPrior(
        val now: Double,
        val reserve: Double,
        val expectedNonReach: Double,
        val lethalAgainstRoot: Double,
        val reserveLifeLoss: Double,
        val used: Boolean,
        val available: Boolean,
    )

    private data class CardTacticalDescriptor(
        val manaCost: Int = 0,
        val faceDamage: Double = 0.0,
        val requiresRed: Boolean = false,
        val removalSwing: Double = 0.0,
        val bodyCreated: Double = 0.0,
        val cardsGenerated: Double = 0.0,
        val repeatableNonReach: Double = 0.0,
        val usefulManaCreated: Double = 0.0,
        val annotationMissing: Boolean = false,
        val burnGroup: Int = 0,
    ) {
        val nonReachValue: Double
            get() = 0.55 * removalSwing + bodyCreated + cardsGenerated +
                0.80 * repeatableNonReach + 0.60 * usefulManaCreated
    }

    companion object {
        const val EVALUATOR_ID = "mono-red-tactical-value-v3"

        private val SUPPORTED_COMBAT_KEYWORDS = setOf("HASTE", "FLYING", "MENACE", "FIRST_STRIKE")
        private val MANA_SOURCES = setOf("Mountain", "Temple of Power", "Soulstone Sanctuary", "Rockface Village")
        private val RED_SOURCES = setOf("Mountain", "Temple of Power")
        private val SHOCK = CardTacticalDescriptor(1, 2.0, requiresRed = true, removalSwing = 0.7, burnGroup = 1)
        private val STRIKE = CardTacticalDescriptor(2, 3.0, requiresRed = true, removalSwing = 0.9, burnGroup = 2)
        private val CARD_DESCRIPTORS = mapOf(
            "Mountain" to CardTacticalDescriptor(),
            "Temple of Power" to CardTacticalDescriptor(),
            "Rockface Village" to CardTacticalDescriptor(usefulManaCreated = 0.20),
            "Soulstone Sanctuary" to CardTacticalDescriptor(usefulManaCreated = 0.20),
            "Shock" to SHOCK,
            "Burst Lightning" to SHOCK,
            "Lightning Strike" to STRIKE,
            "Hired Claw" to CardTacticalDescriptor(1, bodyCreated = 0.65, repeatableNonReach = 0.20),
            "Burnout Bashtronaut" to CardTacticalDescriptor(1, bodyCreated = 0.75),
            "Hexing Squelcher" to CardTacticalDescriptor(2, bodyCreated = 1.05),
            "Razorkin Needlehead" to CardTacticalDescriptor(2, bodyCreated = 1.10, repeatableNonReach = 0.20),
            "Magebane Lizard" to CardTacticalDescriptor(2, bodyCreated = 1.25, repeatableNonReach = 0.20),
            "Nova Hellkite" to CardTacticalDescriptor(5, bodyCreated = 2.20),
            "Howlsquad Heavy" to CardTacticalDescriptor(3, bodyCreated = 1.40),
            "Sunspine Lynx" to CardTacticalDescriptor(4, bodyCreated = 1.80, repeatableNonReach = 0.30),
            "Ojer Axonil, Deepest Might" to CardTacticalDescriptor(4, bodyCreated = 1.75, repeatableNonReach = 0.45),
        )
    }
}

object MonoRedTacticalEvaluatorV3 : ConfiguredInformationStateEvaluator by MonoRedTacticalEvaluator()

private fun PolicyManaPool.total(): Int = white + blue + black + red + green + colorless
