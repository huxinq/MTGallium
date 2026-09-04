package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.mechanics.layers.StaticAbilityHandler
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.SummoningSicknessComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

internal const val TACTICAL_PROOF_SUITE_VERSION = "tactical-proof-v1"

@Serializable
internal enum class TacticalProofCategory { STACK_RACE, URGENT_ATTACK, BLOCK, RESTRAINT }

@Serializable
internal enum class TacticalProofExpiry {
    TERMINAL,
    CURRENT_COMBAT_END,
    NEXT_OPPONENT_COMBAT_END,
    NEXT_ROOT_PRECOMBAT_MAIN,
}

@Serializable
internal data class TacticalProofAcceptedPattern(
    val operationFamilies: Set<SemanticOperationFamily>,
    val sourceName: String? = null,
    val targetNames: Set<String>? = null,
    val assignmentField: String? = null,
    val assignmentCount: Int? = null,
    val requiredAssignments: Map<String, String> = emptyMap(),
) {
    init {
        require(operationFamilies.isNotEmpty())
        require((assignmentField == null) == (assignmentCount == null))
    }

    fun matches(choice: SemanticChoice, informationState: PolicyInformationState): Boolean {
        if (choice.operationFamily !in operationFamilies) return false
        if (sourceName != null && choice.display.sourceName != sourceName) return false
        if (targetNames != null && choice.display.targetNames.toSet() != targetNames) return false
        if (assignmentField != null && choice.assignmentCount(assignmentField) != assignmentCount) return false
        if (requiredAssignments.isNotEmpty()) {
            val assignments = choice.canonicalPayload["body"]?.jsonObject?.get("blockers")?.jsonObject
                ?.flatMap { (blocker, attackers) ->
                    attackers.jsonArray.map { attacker ->
                        informationState.semanticObjectName(blocker) to
                            informationState.semanticObjectName(attacker.jsonPrimitive.content)
                    }
                }
                ?.toSet()
                ?: return false
            if (!requiredAssignments.all { (blocker, attacker) -> blocker to attacker in assignments }) return false
        }
        return true
    }
}

@Serializable
internal data class TacticalProofCase(
    val id: String,
    val category: TacticalProofCategory,
    val description: String,
    val acceptedPredicate: String,
    val proof: String,
    val opportunityExpiry: String,
    val rootSeed: Long,
    val rootPlayer: String,
    val expiry: TacticalProofExpiry,
    val acceptedPattern: TacticalProofAcceptedPattern,
    val criticalOpponentCards: Set<String> = emptySet(),
)

internal object TacticalProofCatalog {
    val cases: List<TacticalProofCase> = listOf(
        TacticalProofCase(
            "stack-race-01", TacticalProofCategory.STACK_RACE,
            "At 2 life, answer an opposing lethal Shock with Shock using one floating red mana.",
            "CAST_SPELL source=Shock target=opponent",
            "The reply resolves above the opposing Shock. Passing lets the opposing Shock resolve first; targeting self loses immediately.",
            "The opportunity expires when priority is passed to the opponent with their lethal Shock on top of the remaining stack.",
            61_001L, "p0", TacticalProofExpiry.TERMINAL,
            castAtOpponent("Shock"),
        ),
        TacticalProofCase(
            "stack-race-02", TacticalProofCategory.STACK_RACE,
            "At 2 life, answer an opposing lethal Shock with Lightning Strike using two floating red mana.",
            "CAST_SPELL source=Lightning Strike target=opponent",
            "Lightning Strike resolves first for three damage. Passing loses to the opposing Shock, and every non-opponent target fails to end the race.",
            "The opportunity expires on the next resolving object.",
            61_002L, "p0", TacticalProofExpiry.TERMINAL,
            castAtOpponent("Lightning Strike"),
        ),
        TacticalProofCase(
            "stack-race-03", TacticalProofCategory.STACK_RACE,
            "At 2 life, answer an opposing lethal Shock with Burst Lightning using one floating red mana.",
            "CAST_SPELL source=Burst Lightning target=opponent unkicked",
            "The unkicked Burst Lightning deals the required two damage above the opposing Shock. Passing or choosing a nonlethal target loses.",
            "The opportunity expires on the next resolving object.",
            61_003L, "p0", TacticalProofExpiry.TERMINAL,
            castAtOpponent("Burst Lightning"),
        ),
        TacticalProofCase(
            "stack-race-04", TacticalProofCategory.STACK_RACE,
            "At 2 life with Ojer Axonil, answer an opposing lethal Shock by sending Shock at an opponent on 4.",
            "CAST_SPELL source=Shock target=opponent with Ojer replacement",
            "Ojer replaces Shock's two damage with four, so it wins before the opposing Shock resolves. Passing loses the stack race.",
            "The opportunity expires on the next resolving object.",
            61_004L, "p0", TacticalProofExpiry.TERMINAL,
            castAtOpponent("Shock"),
        ),
        TacticalProofCase(
            "urgent-attack-01", TacticalProofCategory.URGENT_ATTACK,
            "Attack for two with Hexing Squelcher against an opponent on 2 before Razorkin makes the next draw lethal.",
            "DECLARE_ATTACKERS lethal attacker set={Hexing Squelcher}",
            "The tapped Razorkin cannot block. Every nonlethal declaration leaves it in play, and the opponent can pass to the next root draw for a forced one damage.",
            "The opportunity expires when the current attack fails to end the game.",
            62_001L, "p0", TacticalProofExpiry.NEXT_ROOT_PRECOMBAT_MAIN,
            assigned(SemanticOperationFamily.DECLARE_ATTACKERS, "attackers", 1, setOf("Player 1")),
        ),
        TacticalProofCase(
            "urgent-attack-02", TacticalProofCategory.URGENT_ATTACK,
            "Use Hired Claw's attack trigger plus Hexing Squelcher against an opponent on 3.",
            "DECLARE_ATTACKERS lethal set includes Hired Claw and Hexing Squelcher",
            "The attack trigger and combat damage are lethal through the tapped board. A nonlethal set leaves Razorkin to kill on the next root draw.",
            "The opportunity expires when the current attack fails to end the game.",
            62_002L, "p0", TacticalProofExpiry.NEXT_ROOT_PRECOMBAT_MAIN,
            assigned(SemanticOperationFamily.DECLARE_ATTACKERS, "attackers", 2, setOf("Player 1")),
        ),
        TacticalProofCase(
            "urgent-attack-03", TacticalProofCategory.URGENT_ATTACK,
            "Attack for six with Nova Hellkite and Hexing Squelcher before the next draw is lethal.",
            "DECLARE_ATTACKERS lethal set includes Nova Hellkite and Hexing Squelcher",
            "Six unblocked power is exact lethal. Any smaller declaration leaves Razorkin in play for the forced next-draw loss.",
            "The opportunity expires when the current attack fails to end the game.",
            62_003L, "p0", TacticalProofExpiry.NEXT_ROOT_PRECOMBAT_MAIN,
            assigned(SemanticOperationFamily.DECLARE_ATTACKERS, "attackers", 2, setOf("Player 1")),
        ),
        TacticalProofCase(
            "urgent-attack-04", TacticalProofCategory.URGENT_ATTACK,
            "Combine Hired Claw, Magebane Lizard, and Hexing Squelcher for five damage including the attack trigger.",
            "DECLARE_ATTACKERS lethal set includes all three creatures",
            "Four combat power plus Hired Claw's public trigger is lethal. Every smaller set leaves the next draw fatal to Razorkin.",
            "The opportunity expires when the current attack fails to end the game.",
            62_004L, "p0", TacticalProofExpiry.NEXT_ROOT_PRECOMBAT_MAIN,
            assigned(SemanticOperationFamily.DECLARE_ATTACKERS, "attackers", 3, setOf("Player 1")),
        ),
        TacticalProofCase(
            "proof-block-01", TacticalProofCategory.BLOCK,
            "Double-block an attacking Nova Hellkite with two Nova Hellkites.",
            "DECLARE_BLOCKERS both Novas assigned to attacking Nova Hellkite",
            "One Nova deals only four to five toughness and leaves the attacker alive; two deal eight and remove it while preserving the best surviving material.",
            "The proof score is fixed when this combat ends.",
            63_001L, "p1", TacticalProofExpiry.CURRENT_COMBAT_END,
            assigned(SemanticOperationFamily.DECLARE_BLOCKERS, "blockers", 2, setOf("Nova Hellkite")),
            setOf("Nova Hellkite"),
        ),
        TacticalProofCase(
            "proof-block-02", TacticalProofCategory.BLOCK,
            "Pile Hexing Squelcher and two Hired Claws onto Sunspine Lynx.",
            "DECLARE_BLOCKERS all three blockers assigned to Sunspine Lynx",
            "Five combined power kills the critical 5/4 attacker. Smaller piles leave it alive, while no block is immediately lethal.",
            "The proof score is fixed when this combat ends.",
            63_002L, "p1", TacticalProofExpiry.CURRENT_COMBAT_END,
            assigned(SemanticOperationFamily.DECLARE_BLOCKERS, "blockers", 3, setOf("Sunspine Lynx")),
            setOf("Sunspine Lynx"),
        ),
        TacticalProofCase(
            "proof-block-03", TacticalProofCategory.BLOCK,
            "Spread Nova Hellkite and Hexing Squelcher across Sunspine Lynx and Hexing Squelcher.",
            "DECLARE_BLOCKERS Nova->Sunspine and Hexing->Hexing; Hired remains",
            "The spread removes the critical Lynx, trades with the second attacker, and preserves Hired Claw. Piling blockers exposes lethal damage or preserves a critical attacker.",
            "The proof score is fixed when this combat ends.",
            63_003L, "p1", TacticalProofExpiry.CURRENT_COMBAT_END,
            TacticalProofAcceptedPattern(
                operationFamilies = setOf(SemanticOperationFamily.DECLARE_BLOCKERS),
                targetNames = setOf("Sunspine Lynx", "Hexing Squelcher"),
                requiredAssignments = mapOf(
                    "Nova Hellkite" to "Sunspine Lynx",
                    "Hexing Squelcher" to "Hexing Squelcher",
                ),
            ),
            setOf("Sunspine Lynx", "Hexing Squelcher"),
        ),
        TacticalProofCase(
            "proof-block-04", TacticalProofCategory.BLOCK,
            "Use two Hired Claws to satisfy menace and the third to cover Hexing Squelcher at 1 life.",
            "DECLARE_BLOCKERS two blockers->Burnout Bashtronaut and one->Hexing Squelcher",
            "Menace requires two blockers on Burnout. Leaving either attacker uncovered is lethal, so the only surviving topology is a two-one spread.",
            "The proof score is fixed when this combat ends.",
            63_004L, "p1", TacticalProofExpiry.CURRENT_COMBAT_END,
            assigned(
                SemanticOperationFamily.DECLARE_BLOCKERS,
                "blockers",
                3,
                setOf("Burnout Bashtronaut", "Hexing Squelcher"),
            ),
        ),
        TacticalProofCase(
            "restraint-01", TacticalProofCategory.RESTRAINT,
            "Pass with a lethal Hired Claw trigger on the stack instead of casting Shock into Magebane Lizard.",
            "PASS_PRIORITY",
            "Passing lets the lethal attack trigger resolve. Casting the noncreature spell creates Magebane's trigger above it and kills the caster first.",
            "The opportunity expires when another object is put above the lethal Hired Claw trigger.",
            64_001L, "p0", TacticalProofExpiry.TERMINAL,
            TacticalProofAcceptedPattern(setOf(SemanticOperationFamily.PASS_PRIORITY)),
        ),
        TacticalProofCase(
            "restraint-02", TacticalProofCategory.RESTRAINT,
            "Declare no attackers so Nova Hellkite remains available for the opponent's lethal crack-back.",
            "DECLARE_ATTACKERS empty",
            "Attacking taps the only flying blocker and permits a forcing lethal Nova attack next turn. Holding it allows the public block and survives the combat.",
            "The result is fixed at the end of the opponent's next combat.",
            64_002L, "p0", TacticalProofExpiry.NEXT_OPPONENT_COMBAT_END,
            assigned(SemanticOperationFamily.DECLARE_ATTACKERS, "attackers", 0, emptySet()),
        ),
        TacticalProofCase(
            "restraint-03", TacticalProofCategory.RESTRAINT,
            "Take five from tapped Sunspine Lynx to preserve Hexing Squelcher for lethal on the next turn.",
            "DECLARE_BLOCKERS empty",
            "No block leaves the player at one and preserves the two-power attacker against an opponent on two. Blocking loses the only attacker while the Lynx remains tapped through the counterattack.",
            "The result is fixed on reaching the root player's next precombat main phase.",
            64_003L, "p0", TacticalProofExpiry.NEXT_ROOT_PRECOMBAT_MAIN,
            assigned(SemanticOperationFamily.DECLARE_BLOCKERS, "blockers", 0, emptySet()),
        ),
        TacticalProofCase(
            "restraint-04", TacticalProofCategory.RESTRAINT,
            "Let the opponent's self-targeted Shock resolve; floating red mana before passing is equivalent.",
            "PASS_PRIORITY or MANA_ABILITY followed by PASS_PRIORITY",
            "The opponent's Shock is already lethal to its controller. Both immediate pass and float-then-pass preserve that result, proving the mana action is legal but the pass is not forced.",
            "The result is fixed when the self-targeted Shock resolves.",
            64_004L, "p0", TacticalProofExpiry.TERMINAL,
            TacticalProofAcceptedPattern(
                setOf(SemanticOperationFamily.PASS_PRIORITY, SemanticOperationFamily.MANA_ABILITY)
            ),
        ),
    )

    private fun castAtOpponent(sourceName: String) = TacticalProofAcceptedPattern(
        operationFamilies = setOf(SemanticOperationFamily.CAST_SPELL),
        sourceName = sourceName,
        targetNames = setOf("Player 1"),
    )

    private fun assigned(
        family: SemanticOperationFamily,
        field: String,
        count: Int,
        targetNames: Set<String>,
    ) = TacticalProofAcceptedPattern(
        operationFamilies = setOf(family),
        targetNames = targetNames,
        assignmentField = field,
        assignmentCount = count,
    )

    fun validate() {
        require(cases.size == 16)
        require(cases.map { it.id }.distinct().size == 16)
        require(cases.groupingBy { it.category }.eachCount().values.all { it == 4 })
        require(cases.all { it.proof.isNotBlank() && it.opportunityExpiry.isNotBlank() })
    }
}

internal class TacticalProofScenarioFactory(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
) {
    private val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)

    fun create(case: TacticalProofCase, hiddenVariant: Int): ArgentumSearchWorld {
        require(hiddenVariant in 1..2)
        val exactScenario = when (case.category) {
            TacticalProofCategory.STACK_RACE -> stackRace(case, hiddenVariant)
            TacticalProofCategory.URGENT_ATTACK -> urgentAttack(case, hiddenVariant)
            TacticalProofCategory.BLOCK -> block(case, hiddenVariant)
            TacticalProofCategory.RESTRAINT -> restraint(case, hiddenVariant)
        }
        return exactScenario.withActionSpaceProfile(actionSpaceProfile)
    }

    /** Large public combat used for anytime proposal-prefix and latency stress, not as an oracle case. */
    fun createBlockProposalStress(hiddenVariant: Int = 1): ArgentumSearchWorld {
        val template = TacticalProofCatalog.cases.single { it.id == "proof-block-04" }
        val stressCase = template.copy(id = "structured-block-stress", rootSeed = 63_900L)
        val creatures = List(3) { "Hexing Squelcher" } + List(3) { "Razorkin Needlehead" }
        val world = base(
            case = stressCase,
            hiddenVariant = hiddenVariant,
            p0Battlefield = creatures,
            p1Battlefield = creatures,
            p1Life = 20,
            phase = Phase.COMBAT,
            step = Step.BEGIN_COMBAT,
        )
        advanceToFamily(world, "p0", SemanticOperationFamily.DECLARE_ATTACKERS)
        stepMatching(world, "declare all stress attackers") { choice ->
            choice.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS &&
                choice.assignmentCount("attackers") == creatures.size
        }
        advanceToFamily(world, "p1", SemanticOperationFamily.DECLARE_BLOCKERS)
        return world.withActionSpaceProfile(actionSpaceProfile)
    }

    private fun stackRace(case: TacticalProofCase, hiddenVariant: Int): ArgentumSearchWorld {
        val index = case.id.substringAfterLast('-').toInt()
        val reply = when (index) {
            1 -> "Shock"
            2 -> "Lightning Strike"
            3 -> "Burst Lightning"
            4 -> "Shock"
            else -> error("Unknown stack proof ${case.id}")
        }
        val mountains = if (reply == "Lightning Strike") 2 else 1
        val world = base(
            case = case,
            hiddenVariant = hiddenVariant,
            p0Hand = listOf(reply),
            p0Battlefield = List(mountains) { "Mountain" } + if (index == 4) {
                listOf("Ojer Axonil, Deepest Might")
            } else {
                emptyList()
            },
            p1RequiredHand = listOf("Shock"),
            p1Battlefield = listOf("Mountain"),
            p0Life = 2,
            p1Life = if (index == 2) 3 else if (index == 4) 4 else 2,
            phase = Phase.PRECOMBAT_MAIN,
            step = Step.PRECOMBAT_MAIN,
            activePlayer = "p0",
            priorityPlayer = "p1",
        )
        stepMatching(world, "opponent Shock targeting root", raw = true) {
            it.operationFamily == SemanticOperationFamily.CAST_SPELL &&
                it.display.sourceName == "Shock" && "Player 0" in it.display.targetNames
        }
        stepMatching(world, "opponent priority pass", raw = true) {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        }
        repeat(mountains) {
            stepMatching(world, "root mana activation ${it + 1}", raw = true) { choice ->
                choice.operationFamily == SemanticOperationFamily.MANA_ABILITY
            }
        }
        check(world.actorToAct() == "p0")
        return world
    }

    private fun urgentAttack(case: TacticalProofCase, hiddenVariant: Int): ArgentumSearchWorld {
        val index = case.id.substringAfterLast('-').toInt()
        val (attackers, opponentLife) = when (index) {
            1 -> listOf("Hexing Squelcher") to 2
            2 -> listOf("Hired Claw", "Hexing Squelcher") to 3
            3 -> listOf("Nova Hellkite", "Hexing Squelcher") to 6
            4 -> listOf("Hired Claw", "Magebane Lizard", "Hexing Squelcher") to 5
            else -> error("Unknown attack proof ${case.id}")
        }
        val world = base(
            case = case,
            hiddenVariant = hiddenVariant,
            p0Battlefield = attackers,
            p1Battlefield = listOf("Razorkin Needlehead"),
            p1Tapped = listOf("Razorkin Needlehead"),
            p0Life = 1,
            p1Life = opponentLife,
            phase = Phase.COMBAT,
            step = Step.BEGIN_COMBAT,
        )
        advanceToFamily(world, "p0", SemanticOperationFamily.DECLARE_ATTACKERS)
        return world
    }

    private fun block(case: TacticalProofCase, hiddenVariant: Int): ArgentumSearchWorld {
        val index = case.id.substringAfterLast('-').toInt()
        val setup = when (index) {
            1 -> ProofBlockSetup(listOf("Nova Hellkite"), listOf("Nova Hellkite", "Nova Hellkite"), 4)
            2 -> ProofBlockSetup(
                listOf("Sunspine Lynx"),
                listOf("Hexing Squelcher", "Hired Claw", "Hired Claw"),
                5,
            )
            3 -> ProofBlockSetup(
                listOf("Sunspine Lynx", "Hexing Squelcher"),
                listOf("Nova Hellkite", "Hexing Squelcher", "Hired Claw"),
                2,
            )
            4 -> ProofBlockSetup(
                listOf("Burnout Bashtronaut", "Hexing Squelcher"),
                listOf("Hired Claw", "Hired Claw", "Hired Claw"),
                1,
            )
            else -> error("Unknown block proof ${case.id}")
        }
        val world = base(
            case = case,
            hiddenVariant = hiddenVariant,
            hiddenOwner = "p0",
            p0Battlefield = setup.attackers,
            p1Battlefield = setup.blockers,
            p1Life = setup.defendingLife,
            phase = Phase.COMBAT,
            step = Step.BEGIN_COMBAT,
        )
        advanceToFamily(world, "p0", SemanticOperationFamily.DECLARE_ATTACKERS)
        stepMatching(world, "declare all attackers") { choice ->
            choice.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS &&
                choice.assignmentCount("attackers") == setup.attackers.size
        }
        advanceToFamily(world, "p1", SemanticOperationFamily.DECLARE_BLOCKERS)
        return world
    }

    private fun restraint(case: TacticalProofCase, hiddenVariant: Int): ArgentumSearchWorld {
        val index = case.id.substringAfterLast('-').toInt()
        return when (index) {
            1 -> {
                val world = base(
                    case = case,
                    hiddenVariant = hiddenVariant,
                    p0Hand = listOf("Shock"),
                    p0Battlefield = listOf("Hired Claw", "Mountain"),
                    p1Battlefield = listOf("Magebane Lizard"),
                    p0Life = 1,
                    p1Life = 1,
                    phase = Phase.COMBAT,
                    step = Step.BEGIN_COMBAT,
                )
                advanceToFamily(world, "p0", SemanticOperationFamily.DECLARE_ATTACKERS)
                stepMatching(world, "attack with Hired Claw", raw = true) { choice ->
                    choice.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS &&
                        choice.assignmentCount("attackers") == 1
                }
                stepMatching(world, "float red above Hired trigger", raw = true) {
                    it.operationFamily == SemanticOperationFamily.MANA_ABILITY
                }
                world
            }
            2 -> base(
                case = case,
                hiddenVariant = hiddenVariant,
                p0Battlefield = listOf("Nova Hellkite"),
                p1Battlefield = listOf("Nova Hellkite"),
                p0Life = 4,
                phase = Phase.COMBAT,
                step = Step.BEGIN_COMBAT,
            ).also { advanceToFamily(it, "p0", SemanticOperationFamily.DECLARE_ATTACKERS) }
            3 -> {
                val world = base(
                    case = case,
                    hiddenVariant = hiddenVariant,
                    p0Battlefield = listOf("Hexing Squelcher"),
                    p1Battlefield = listOf("Sunspine Lynx"),
                    p0Life = 6,
                    p1Life = 2,
                    phase = Phase.COMBAT,
                    step = Step.BEGIN_COMBAT,
                    activePlayer = "p1",
                    priorityPlayer = "p1",
                )
                advanceToFamily(world, "p1", SemanticOperationFamily.DECLARE_ATTACKERS)
                stepMatching(world, "attack with Sunspine Lynx") { choice ->
                    choice.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS &&
                        choice.assignmentCount("attackers") == 1
                }
                advanceToFamily(world, "p0", SemanticOperationFamily.DECLARE_BLOCKERS)
                world
            }
            4 -> {
                val world = base(
                    case = case,
                    hiddenVariant = hiddenVariant,
                    p0Battlefield = listOf("Mountain"),
                    p1RequiredHand = listOf("Shock"),
                    p1Battlefield = listOf("Mountain"),
                    p1Life = 2,
                    phase = Phase.PRECOMBAT_MAIN,
                    step = Step.PRECOMBAT_MAIN,
                    priorityPlayer = "p1",
                )
                stepMatching(world, "opponent Shock targeting self", raw = true) {
                    it.operationFamily == SemanticOperationFamily.CAST_SPELL &&
                        it.display.sourceName == "Shock" && "Player 1" in it.display.targetNames
                }
                stepMatching(world, "opponent priority pass", raw = true) {
                    it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
                }
                world
            }
            else -> error("Unknown restraint proof ${case.id}")
        }
    }

    private fun base(
        case: TacticalProofCase,
        hiddenVariant: Int,
        hiddenOwner: String = "p1",
        p0Hand: List<String> = emptyList(),
        p1RequiredHand: List<String> = emptyList(),
        p0Battlefield: List<String> = emptyList(),
        p1Battlefield: List<String> = emptyList(),
        p0Tapped: List<String> = emptyList(),
        p1Tapped: List<String> = emptyList(),
        p0Life: Int = 20,
        p1Life: Int = 20,
        phase: Phase,
        step: Step,
        activePlayer: String = "p0",
        priorityPlayer: String = activePlayer,
    ): ArgentumSearchWorld {
        val hiddenCard = if (hiddenVariant == 1) "Nova Hellkite" else "Sunspine Lynx"
        val p0FullHand = p0Hand + if (hiddenOwner == "p0") listOf(hiddenCard) else emptyList()
        val p1FullHand = p1RequiredHand + if (hiddenOwner == "p1") listOf(hiddenCard) else emptyList()
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Player 0", manifest.deck()),
                    PlayerConfig("Player 1", manifest.deck()),
                ),
                seed = case.rootSeed,
                startingPlayerIndex = 0,
                skipMulligans = true,
            )
        )
        val ids = environment.playerIds
        var state = arrangePlayer(environment.state, ids[0], p0FullHand, p0Battlefield, p0Tapped, p0Life)
        state = arrangePlayer(state, ids[1], p1FullHand, p1Battlefield, p1Tapped, p1Life)
        val activeId = if (activePlayer == "p0") ids[0] else ids[1]
        val priorityId = if (priorityPlayer == "p0") ids[0] else ids[1]
        state = state.copy(
            phase = phase,
            step = step,
            activePlayerId = activeId,
            priorityPlayerId = priorityId,
            priorityPassedBy = emptySet(),
            stack = emptyList(),
            pendingDecision = null,
            continuationStack = emptyList(),
            winnerId = null,
            gameOver = false,
        )
        environment.restore(state, ids)
        return ArgentumSearchWorld.create(
            environment = environment,
            gameId = "${case.id}-paired-hidden",
            seedBase = case.rootSeed,
            effectiveSetupSeed = case.rootSeed,
            cardRegistry = registry,
            knownDecks = knownDecks,
        )
    }

    private fun arrangePlayer(
        original: GameState,
        playerId: EntityId,
        handNames: List<String>,
        battlefieldNames: List<String>,
        tappedNames: List<String>,
        life: Int,
    ): GameState {
        val owned = Zone.entries.flatMap { original.getZone(playerId, it) }.distinct()
        val available = owned.groupBy { entityId ->
            requireNotNull(original.getEntity(entityId)?.get<CardComponent>()).name
        }.mapValues { (_, ids) -> ids.sortedBy(EntityId::value).toMutableList() }
        fun take(names: List<String>): List<EntityId> = names.map { name ->
            val candidates = requireNotNull(available[name]) { "No $name in $playerId deck" }
            require(candidates.isNotEmpty()) { "Proof scenario overuses $name for $playerId" }
            candidates.removeAt(0)
        }
        val hand = take(handNames)
        val battlefield = take(battlefieldNames)
        val tappedRemaining = tappedNames.groupingBy { it }.eachCount().toMutableMap()
        val library = available.entries
            .sortedWith(compareBy<Map.Entry<String, MutableList<EntityId>>> { it.key != "Mountain" }.thenBy { it.key })
            .flatMap { it.value }
        val cleared = original.zones + Zone.entries.associate { zone -> ZoneKey(playerId, zone) to emptyList() }
        var state = original.copy(
            zones = cleared + mapOf(
                ZoneKey(playerId, Zone.HAND) to hand,
                ZoneKey(playerId, Zone.BATTLEFIELD) to battlefield,
                ZoneKey(playerId, Zone.LIBRARY) to library,
            )
        )
        val staticHandler = StaticAbilityHandler(registry)
        battlefield.forEach { cardId ->
            val card = requireNotNull(state.getEntity(cardId)?.get<CardComponent>())
            val definition = registry.requireCard(card.name)
            val tap = tappedRemaining.getOrDefault(card.name, 0) > 0
            if (tap) tappedRemaining[card.name] = tappedRemaining.getValue(card.name) - 1
            state = state.updateEntity(cardId) { container ->
                var updated = staticHandler.addReplacementEffectComponent(
                    staticHandler.addContinuousEffectComponent(
                        container.with(ControllerComponent(playerId)).without<SummoningSicknessComponent>(),
                        definition,
                    ),
                    definition,
                )
                updated = if (tap) updated.with(TappedComponent) else updated.without<TappedComponent>()
                updated
            }
        }
        require(tappedRemaining.values.all { it == 0 }) { "Tapped proof permanents were not arranged" }
        return state.updateEntity(playerId) { it.with(LifeTotalComponent(life)) }
    }

    private fun advanceToFamily(
        world: ArgentumSearchWorld,
        actor: String,
        family: SemanticOperationFamily,
    ) {
        repeat(32) {
            val expansion = world.expandChoices(2_048)
            if (world.actorToAct() == actor && expansion.candidates.any { it.operationFamily == family }) return
            val pass = expansion.candidates.singleOrNull {
                it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
            } ?: error("${world.actorToAct()} encountered a branching decision before $actor/$family")
            require(world.step(pass).accepted)
        }
        error("Did not reach $actor/$family within 32 proof-history actions")
    }

    private fun stepMatching(
        world: ArgentumSearchWorld,
        description: String,
        raw: Boolean = false,
        predicate: (SemanticChoice) -> Boolean,
    ) {
        val expansion = world.expandChoices(2_048)
        require(expansion.isExhaustive) { "$description requires an exhaustive expansion" }
        val matches = expansion.candidates.filter(predicate)
        require(matches.size == 1) {
            val actor = world.actorToAct()
            val state = actor?.let(world::informationState)
            "$description matched ${matches.size} candidates for actor ${world.actorToAct()}; " +
                "families=${expansion.candidates.groupingBy { it.operationFamily }.eachCount()}; " +
                "terminal=${state?.terminated}; stack=${state?.observation?.stack?.map { it.name }}"
        }
        val choice = matches.single()
        val result = if (raw) {
            when (val resolved = world.resolveChoice(choice)) {
                is ArgentumResolvedChoice.Action -> world.applyObservedAction(resolved.value).result
                is ArgentumResolvedChoice.Decision -> error("$description unexpectedly resolved to a decision")
            }
        } else {
            world.step(choice)
        }
        require(result.accepted) { "Engine rejected $description: ${result.diagnostic}" }
    }

    private data class ProofBlockSetup(
        val attackers: List<String>,
        val blockers: List<String>,
        val defendingLife: Int,
    )
}

private fun SemanticChoice.assignmentCount(field: String): Int = canonicalPayload["body"]
    ?.jsonObject?.get(field)?.jsonObject?.size ?: -1

private fun PolicyInformationState.semanticObjectName(reference: String): String {
    val descriptorPrefix = reference.substringAfter("object:").split(':', limit = 3).getOrNull(2)
        ?.substringBefore('#')?.take(16)
        ?: error("Not a semantic object reference: $reference")
    return observation.zones.asSequence().flatMap { it.cards.asSequence() }
        .single { card -> card.objectRef.split(':').getOrNull(3) == descriptorPrefix }
        .name
}

internal fun PolicyInformationState.publicProofKey(): String = informationStateDigest
