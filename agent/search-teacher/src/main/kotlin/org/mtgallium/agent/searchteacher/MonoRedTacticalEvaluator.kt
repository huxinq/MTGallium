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
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

@Serializable
enum class TacticalFeatureFamily {
    NONLINEAR_LIFE,
    COMBAT_READINESS,
    ROOT_KNOWN_REACH,
    SAFE_OPPONENT_PRIOR,
    MANA_FIT,
    KNOWN_CARD_VALUE,
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
    val landConversion: Double = 0.20,
    val initiative: Double = 0.25,
) {
    init {
        require(
            listOf(life, lethal, body, attack, block, reach, hand, mana, landConversion, initiative)
                .all(Double::isFinite)
        )
    }

    val configurationId: String
        get() = listOf(
            life,
            lethal,
            body,
            attack,
            block,
            reach,
            hand,
            mana,
            landConversion,
            initiative,
        ).joinToString(",", transform = ::canonicalTacticalNumber)
}

@Serializable
data class MonoRedTacticalEvaluatorSettings(
    val schemaVersion: Int = 1,
    val outputTemperature: Double = 2.0,
    val startingLife: Int = 20,
    val annotationVersion: String = "mono-red-tactical-annotations-v1",
    val enabledFamilies: Set<TacticalFeatureFamily> = TacticalFeatureFamily.entries.toSet(),
    val weights: MonoRedTacticalEvaluatorWeights = MonoRedTacticalEvaluatorWeights(),
) {
    init {
        require(schemaVersion == 1)
        require(outputTemperature.isFinite() && outputTemperature > 0.0)
        require(startingLife > 0)
        require(annotationVersion.isNotBlank())
    }

    val configurationId: String
        get() = listOf(
            MonoRedTacticalEvaluator.EVALUATOR_ID,
            "schema-$schemaVersion",
            "temperature-${canonicalNumber(outputTemperature)}",
            "life-$startingLife",
            annotationVersion,
            enabledFamilies.sortedBy(TacticalFeatureFamily::name).joinToString("+") { it.name },
            "weights-${weights.configurationId}",
        ).joinToString(":")

    private fun canonicalNumber(value: Double): String = canonicalTacticalNumber(value)
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
 * Information-safe, hand-designed tactical successor to [MonoRedInformationEvaluator].
 *
 * It deliberately consumes only the already projected [PolicyInformationState]. Root-private
 * identities are read only from the root's projected hand/knowledge; opponent hidden identities
 * and sampled-world fields are never inputs. The opponent term is a coarse hypergeometric prior
 * derived from the declared known deck and perspective-safe depletion ledger.
 */
class MonoRedTacticalEvaluator(
    val settings: MonoRedTacticalEvaluatorSettings = MonoRedTacticalEvaluatorSettings(),
) : ConfiguredInformationStateEvaluator {
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

        val publicBattlefield = observation.zones.asSequence()
            .filter { it.zone.equals("BATTLEFIELD", ignoreCase = true) && !it.hidden }
            .flatMap { it.cards.asSequence() }
            .filterNot(PolicyCardView::faceDown)
            .toList()
        val rootHand = observation.zones.asSequence()
            .filter { it.ownerId == rootPlayer && it.zone.equals("HAND", ignoreCase = true) }
            .flatMap { it.cards.asSequence() }
            .filterNot(PolicyCardView::faceDown)
            .toList()
        val knownOpponentHand = information.knowledge.zones.asSequence()
            .filter { it.ownerId == opponent.playerId && it.zone.equals("HAND", ignoreCase = true) }
            .flatMap { zone -> zone.knownCardCounts.asSequence().flatMap { (name, count) -> List(count) { name }.asSequence() } }
            .map(::descriptorForName)
            .toList()

        val components = linkedMapOf<String, Double>()

        val phiLife = if (TacticalFeatureFamily.NONLINEAR_LIFE in settings.enabledFamilies) {
            lifeUtility(root.life) - lifeUtility(opponent.life)
        } else 0.0
        components["phiLife"] = phiLife

        val rootBattlefield = publicBattlefield.filter { it.controllerId == rootPlayer }
        val opponentBattlefield = publicBattlefield.filter { it.controllerId == opponent.playerId }
        val rootCombat = combatFeatures(rootPlayer, opponent.playerId, rootBattlefield, opponentBattlefield, observation)
        val opponentCombat = combatFeatures(opponent.playerId, rootPlayer, opponentBattlefield, rootBattlefield, observation)
        if (rootCombat.approximationUsed || opponentCombat.approximationUsed) flags += "combat-approximation"
        val phiBody = if (TacticalFeatureFamily.COMBAT_READINESS in settings.enabledFamilies) {
            normalizedDifference(rootCombat.body, opponentCombat.body, 12.0)
        } else 0.0
        val phiAttack = if (TacticalFeatureFamily.COMBAT_READINESS in settings.enabledFamilies) {
            normalizedDifference(rootCombat.attackPressure, opponentCombat.attackPressure, 8.0)
        } else 0.0
        val phiBlock = if (TacticalFeatureFamily.COMBAT_READINESS in settings.enabledFamilies) {
            normalizedDifference(rootCombat.blockCapacity, opponentCombat.blockCapacity, 8.0)
        } else 0.0
        components["phiBody"] = phiBody
        components["phiAttack"] = phiAttack
        components["phiBlock"] = phiBlock

        val rootMana = usableMana(rootPlayer, root, rootBattlefield)
        val opponentMana = usableMana(opponent.playerId, opponent, opponentBattlefield)
        val rootDescriptors = rootHand.map(::descriptorForCard)
        if (rootDescriptors.any { it.annotationMissing }) flags += "root-annotation-missing"
        if (knownOpponentHand.any { it.annotationMissing }) flags += "opponent-annotation-missing"

        val rootBurnNow = bestBurn(rootDescriptors, rootMana)
        val rootNextMana = maxOf(rootMana, controlledLandCount(rootBattlefield) + 1).coerceAtMost(8)
        val rootReachReserve = bestBurn(rootDescriptors, rootMana + rootNextMana)
        val opponentPrior = opponentBurnPrior(information, opponent.playerId, opponent.handSize)
        if (opponentPrior.used) flags += "opponent-safe-prior"
        if (!opponentPrior.available) flags += "opponent-prior-missing"
        val knownOpponentBurn = knownOpponentHand.sumOf(CardTacticalDescriptor::faceDamage)
        val opponentReachReserve = knownOpponentBurn + opponentPrior.expectedDamage
        val opponentBurnNow = minOf(
            opponentReachReserve,
            opponentReachReserve * (opponentMana.toDouble() / maxOf(1.0, opponentPrior.expectedManaNeed)),
        )

        val rootDamageWindow = rootBurnNow + rootCombat.attackPressure
        val opponentDamageWindow = opponentBurnNow + opponentCombat.attackPressure
        val phiLethal = if (
            TacticalFeatureFamily.ROOT_KNOWN_REACH in settings.enabledFamilies ||
            TacticalFeatureFamily.SAFE_OPPONENT_PRIOR in settings.enabledFamilies
        ) {
            sigmoid((rootDamageWindow - opponent.life + 0.5) / 1.25) -
                sigmoid((opponentDamageWindow - root.life + 0.5) / 1.25)
        } else 0.0
        val phiReach = if (TacticalFeatureFamily.ROOT_KNOWN_REACH in settings.enabledFamilies) {
            normalizedDifference(rootReachReserve, opponentReachReserve, 6.0)
        } else 0.0
        components["rootBurnNow"] = rootBurnNow
        components["opponentExpectedBurnNow"] = opponentBurnNow
        components["phiLethal"] = phiLethal
        components["phiReach"] = phiReach

        val rootRetained = retainedNonReachValue(rootDescriptors)
        val opponentKnownRetained = retainedNonReachValue(knownOpponentHand)
        val phiHand = if (TacticalFeatureFamily.KNOWN_CARD_VALUE in settings.enabledFamilies) {
            normalizedDifference(rootRetained, opponentKnownRetained, 8.0)
        } else 0.0
        components["phiHand"] = phiHand

        val rootManaFit = manaFit(rootMana, rootDescriptors, information, rootPlayer)
        val opponentManaFit = coarseOpponentManaFit(opponentMana, opponent.handSize, opponentPrior)
        val phiMana = if (TacticalFeatureFamily.MANA_FIT in settings.enabledFamilies) {
            (rootManaFit - opponentManaFit).coerceIn(-1.0, 1.0)
        } else 0.0
        val rootLandConversion = marginalLandConversion(rootMana, rootDescriptors, information, rootPlayer)
        val phiLandConversion = if (TacticalFeatureFamily.MANA_FIT in settings.enabledFamilies) {
            rootLandConversion
        } else 0.0
        components["phiMana"] = phiMana
        components["phiLandConversion"] = phiLandConversion

        val phiInitiative = if (TacticalFeatureFamily.INITIATIVE in settings.enabledFamilies) {
            initiative(rootPlayer, observation) - initiative(opponent.playerId, observation)
        } else 0.0
        components["phiDurable"] = 0.0
        components["phiInitiative"] = phiInitiative

        val weights = settings.weights
        val rawScore = weights.life * phiLife +
            weights.lethal * phiLethal +
            weights.body * phiBody +
            weights.attack * phiAttack +
            weights.block * phiBlock +
            weights.reach * phiReach +
            weights.hand * phiHand +
            weights.mana * phiMana +
            weights.landConversion * phiLandConversion +
            weights.initiative * phiInitiative
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
        ln(1.0 + life.coerceIn(0, settings.startingLife * 2)) /
            ln(1.0 + settings.startingLife * 2.0)

    private fun combatFeatures(
        player: String,
        opponent: String,
        ownBattlefield: List<PolicyCardView>,
        opposingBattlefield: List<PolicyCardView>,
        observation: PolicyObservation,
    ): CombatFeatures {
        val creatures = ownBattlefield.filter { it.types.any { type -> type.equals("CREATURE", true) } }
        val blockers = opposingBattlefield.filter { card ->
            card.types.any { it.equals("CREATURE", true) } && !card.tapped && damageMargin(card) > 0
        }
        val body = creatures.sumOf { card ->
            0.45 * (card.power ?: 0).coerceIn(0, 8) +
                0.25 * damageMargin(card).coerceIn(0, 8) + 0.30
        }
        val blockCapacity = creatures.filter { !it.tapped && damageMargin(it) > 0 }
            .sumOf { damageMargin(it).coerceAtMost(4).toDouble() }
        val declared = observation.combat?.takeIf { it.attackingPlayerId == player }
        val attackPressure = if (declared != null && declared.attackers.isNotEmpty()) {
            val byRef = creatures.associateBy(PolicyCardView::objectRef)
            declared.attackers.filter { it.blockerObjectRefs.isEmpty() }
                .sumOf { attack -> (byRef[attack.attackerObjectRef]?.power ?: 0).coerceAtLeast(0).toDouble() }
        } else {
            val readyPowers = creatures.filter { card ->
                !card.tapped && (!card.summoningSick || card.keywords.any { it.equals("HASTE", true) })
            }.map { (it.power ?: 0).coerceAtLeast(0) }.sortedDescending()
            val open = readyPowers.drop(blockers.size).sum().toDouble()
            when {
                observation.activePlayerId == player && attackWindowRemains(observation) -> open
                observation.activePlayerId == opponent && attackWindowRemains(observation) -> 0.0
                else -> 0.60 * open
            }
        }
        val approximation = declared == null || creatures.any { card ->
            card.keywords.any { it.uppercase() !in SUPPORTED_COMBAT_KEYWORDS }
        }
        return CombatFeatures(body, attackPressure, blockCapacity, approximation)
    }

    private fun damageMargin(card: PolicyCardView): Int =
        ((card.toughness ?: 0) - card.damageMarked).coerceAtLeast(0)

    private fun attackWindowRemains(observation: PolicyObservation): Boolean {
        if (observation.phase.equals("PRECOMBAT_MAIN", true)) return true
        if (!observation.phase.equals("COMBAT", true)) return false
        return observation.step.uppercase() !in setOf("COMBAT_DAMAGE", "END_COMBAT")
    }

    private fun usableMana(
        player: String,
        playerView: PolicyPlayerView,
        battlefield: List<PolicyCardView>,
    ): Int = playerView.mana.total() + battlefield.count { card ->
        card.controllerId == player && !card.tapped && card.types.any { it.equals("LAND", true) }
    }

    private fun controlledLandCount(battlefield: List<PolicyCardView>): Int =
        battlefield.count { card -> card.types.any { it.equals("LAND", true) } }

    private fun bestBurn(cards: List<CardTacticalDescriptor>, mana: Int): Double {
        if (mana <= 0) return 0.0
        val dp = DoubleArray(mana + 1)
        cards.filter { it.faceDamage > 0.0 && it.manaCost in 1..mana }.forEach { card ->
            for (budget in mana downTo card.manaCost) {
                dp[budget] = maxOf(dp[budget], dp[budget - card.manaCost] + card.faceDamage)
            }
        }
        return dp.maxOrNull() ?: 0.0
    }

    private fun retainedNonReachValue(cards: List<CardTacticalDescriptor>): Double = cards.sumOf { card ->
        0.55 * card.removalSwing + card.bodyCreated + card.cardsGenerated +
            0.80 * card.repeatableNonReach + 0.60 * card.usefulManaCreated
    }

    private fun opponentBurnPrior(
        information: PolicyInformationState,
        opponent: String,
        handSize: Int,
    ): OpponentPrior {
        val unlocated = information.knowledge.unlocatedCardCounts[opponent].orEmpty()
        if (unlocated.isEmpty() || handSize <= 0) return OpponentPrior.NONE
        val knownInHand = information.knowledge.zones.firstOrNull {
            it.ownerId == opponent && it.zone.equals("HAND", true)
        }?.knownCardCounts.orEmpty()
        val unknownHand = (handSize - knownInHand.values.sum()).coerceAtLeast(0)
        val total = unlocated.values.sum().coerceAtLeast(1)
        val expectedDamagePerCard = unlocated.entries.sumOf { (name, count) ->
            descriptorForName(name).faceDamage * count
        } / total
        val burnCopies = unlocated.entries.sumOf { (name, count) ->
            if (descriptorForName(name).faceDamage > 0.0) count else 0
        }
        val expectedBurnCopies = unknownHand * burnCopies.toDouble() / total
        val expectedManaNeed = if (burnCopies == 0) 1.0 else {
            unlocated.entries.sumOf { (name, count) ->
                val descriptor = descriptorForName(name)
                if (descriptor.faceDamage > 0.0) descriptor.manaCost * count.toDouble() else 0.0
            } / burnCopies
        }
        return OpponentPrior(
            expectedDamage = unknownHand * expectedDamagePerCard,
            expectedBurnCopies = expectedBurnCopies,
            expectedManaNeed = expectedManaNeed.coerceAtLeast(1.0),
            used = true,
            available = true,
        )
    }

    private fun manaFit(
        mana: Int,
        cards: List<CardTacticalDescriptor>,
        information: PolicyInformationState,
        player: String,
    ): Double {
        if (cards.isEmpty()) return 0.0
        val total = cards.sumOf { it.totalRetainedUtility }.coerceAtLeast(1.0)
        val castable = cards.filter { it.manaCost <= mana }.sumOf { it.totalRetainedUtility } / total
        val usefulDemand = cards.count { it.manaCost >= 1 }.toDouble() / cards.size
        val development = (mana.toDouble() / maxOf(1, cards.maxOf { it.manaCost })).coerceIn(0.0, 1.0) *
            (0.25 + 0.75 * usefulDemand)
        val floating = information.observation.players.single { it.playerId == player }.mana.total()
        val hasLegalUse = information.actingPlayerId == player && information.candidates.any {
            it.operationFamily in setOf(SemanticOperationFamily.CAST_SPELL, SemanticOperationFamily.ACTIVATE_ABILITY)
        }
        val expiringWaste = if (floating > 0 && !hasLegalUse) {
            floating.toDouble() / maxOf(1, mana)
        } else 0.0
        return (development + 0.50 * castable - 0.70 * expiringWaste).coerceIn(0.0, 1.5) / 1.5
    }

    private fun coarseOpponentManaFit(
        mana: Int,
        handSize: Int,
        prior: OpponentPrior,
    ): Double {
        if (handSize <= 0) return 0.0
        val demand = if (prior.available) (0.25 + 0.75 * prior.expectedBurnCopies.coerceIn(0.0, 1.0)) else 0.5
        return (mana.toDouble() / maxOf(1.0, prior.expectedManaNeed + 1.0) * demand).coerceIn(0.0, 1.0)
    }

    private fun marginalLandConversion(
        mana: Int,
        cards: List<CardTacticalDescriptor>,
        information: PolicyInformationState,
        player: String,
    ): Double {
        val legalLandDrop = information.actingPlayerId == player && information.candidates.any {
            it.operationFamily == SemanticOperationFamily.PLAY_LAND
        }
        if (!legalLandDrop) return 0.0
        val before = cards.filter { it.manaCost <= mana }.sumOf { it.totalRetainedUtility }
        val after = cards.filter { it.manaCost <= mana + 1 }.sumOf { it.totalRetainedUtility }
        return ((after - before) / 3.0).coerceIn(0.0, 1.0)
    }

    private fun initiative(player: String, observation: PolicyObservation): Double {
        var value = 0.0
        if (observation.priorityPlayerId == player) value += 0.5
        if (observation.activePlayerId == player && attackWindowRemains(observation)) value += 0.5
        return value
    }

    private fun descriptorForCard(card: PolicyCardView): CardTacticalDescriptor {
        val known = descriptorForName(card.name)
        if (!known.annotationMissing) return known
        val createsBody = card.types.any { it.equals("CREATURE", true) }
        return known.copy(
            manaCost = card.manaValue.coerceAtLeast(0),
            bodyCreated = if (createsBody) 0.35 + 0.30 * card.manaValue.coerceIn(0, 6) else 0.0,
        )
    }

    private fun descriptorForName(name: String): CardTacticalDescriptor =
        CARD_DESCRIPTORS[name] ?: CardTacticalDescriptor(annotationMissing = true)

    private fun normalizedDifference(root: Double, opponent: Double, scale: Double): Double =
        ((root - opponent) / scale).coerceIn(-1.0, 1.0)

    private fun sigmoid(value: Double): Double = 1.0 / (1.0 + exp(-value))

    private data class CombatFeatures(
        val body: Double,
        val attackPressure: Double,
        val blockCapacity: Double,
        val approximationUsed: Boolean,
    )

    private data class OpponentPrior(
        val expectedDamage: Double,
        val expectedBurnCopies: Double,
        val expectedManaNeed: Double,
        val used: Boolean,
        val available: Boolean,
    ) {
        companion object {
            val NONE = OpponentPrior(0.0, 0.0, 1.0, used = false, available = false)
        }
    }

    private data class CardTacticalDescriptor(
        val manaCost: Int = 0,
        val faceDamage: Double = 0.0,
        val removalSwing: Double = 0.0,
        val bodyCreated: Double = 0.0,
        val cardsGenerated: Double = 0.0,
        val repeatableNonReach: Double = 0.0,
        val usefulManaCreated: Double = 0.0,
        val annotationMissing: Boolean = false,
    ) {
        val totalRetainedUtility: Double
            get() = faceDamage + 0.55 * removalSwing + bodyCreated + cardsGenerated +
                0.80 * repeatableNonReach + 0.60 * usefulManaCreated
    }

    companion object {
        const val EVALUATOR_ID = "mono-red-tactical-value-v3"
        private val SUPPORTED_COMBAT_KEYWORDS = setOf("HASTE", "FLYING", "MENACE", "FIRST_STRIKE")
        private val CARD_DESCRIPTORS = mapOf(
            "Mountain" to CardTacticalDescriptor(),
            "Rockface Village" to CardTacticalDescriptor(usefulManaCreated = 0.20),
            "Soulstone Sanctuary" to CardTacticalDescriptor(usefulManaCreated = 0.20),
            "Shock" to CardTacticalDescriptor(manaCost = 1, faceDamage = 2.0, removalSwing = 0.7),
            "Burst Lightning" to CardTacticalDescriptor(manaCost = 1, faceDamage = 2.0, removalSwing = 0.7),
            "Lightning Strike" to CardTacticalDescriptor(manaCost = 2, faceDamage = 3.0, removalSwing = 0.9),
            "Hired Claw" to CardTacticalDescriptor(manaCost = 1, bodyCreated = 0.65, repeatableNonReach = 0.20),
            "Burnout Bashtronaut" to CardTacticalDescriptor(manaCost = 1, bodyCreated = 0.75),
            "Hexing Squelcher" to CardTacticalDescriptor(manaCost = 2, bodyCreated = 1.05),
            "Razorkin Needlehead" to CardTacticalDescriptor(manaCost = 2, bodyCreated = 1.10, repeatableNonReach = 0.20),
            "Magebane Lizard" to CardTacticalDescriptor(manaCost = 3, bodyCreated = 1.25, repeatableNonReach = 0.20),
            "Nova Hellkite" to CardTacticalDescriptor(manaCost = 5, bodyCreated = 2.20),
            "Howlsquad Heavy" to CardTacticalDescriptor(manaCost = 3, bodyCreated = 1.40),
            "Sunspine Lynx" to CardTacticalDescriptor(manaCost = 4, bodyCreated = 1.80, repeatableNonReach = 0.30),
            "Ojer Axonil, Deepest Might" to CardTacticalDescriptor(manaCost = 4, bodyCreated = 1.75, repeatableNonReach = 0.45),
        )
    }
}

object MonoRedTacticalEvaluatorV3 : ConfiguredInformationStateEvaluator by MonoRedTacticalEvaluator()

private fun PolicyManaPool.total(): Int = white + blue + black + red + green + colorless
