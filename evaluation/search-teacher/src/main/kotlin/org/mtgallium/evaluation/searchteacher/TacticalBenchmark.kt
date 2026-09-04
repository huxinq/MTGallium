package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.sqrt
import org.mtgallium.agent.infoset.argentum.ArgentumKnownDeckBeliefWorldSource
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.ProgressiveSearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.searchteacher.SemanticHeuristicOpponentPolicy

internal object TacticalBenchmarkCatalog {
    val cases: List<TacticalCaseDefinition> = buildList {
        lethalDescriptions().forEachIndexed { index, description ->
            add(
                TacticalCaseDefinition(
                    id = "lethal-${(index + 1).toString().padStart(2, '0')}",
                    category = TacticalCategory.FORCED_LETHAL,
                    description = description,
                    rootSeed = 10_000L + index,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                )
            )
        }
        attackDescriptions().forEachIndexed { index, description ->
            add(
                TacticalCaseDefinition(
                    id = "attack-${(index + 1).toString().padStart(2, '0')}",
                    category = TacticalCategory.ATTACK,
                    description = description,
                    rootSeed = 20_000L + index,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                )
            )
        }
        addAll(
            listOf(
                TacticalCaseDefinition(
                    id = "block-01",
                    category = TacticalCategory.FORCED_SURVIVAL,
                    description = "At 3 life, classify every assignment of two 1/2 Hired Claws against two attacking 2/2 Hexing Squelchers. The mechanical objective is only to survive combat; rank the surviving single, pile, and split structures by expert judgment.",
                    rootSeed = 30_000L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-02",
                    category = TacticalCategory.FORCED_SURVIVAL,
                    description = "At 2 life, assign three 1/2 Hired Claws against an attacking 1/1 Burnout Bashtronaut with menace and a 2/2 Hexing Squelcher. Survive combat while respecting menace's two-blocker minimum; strategically rank all surviving assignments separately.",
                    rootSeed = 30_001L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-03",
                    category = TacticalCategory.FORCED_SURVIVAL,
                    description = "At 5 life, block an attacking 4/5 flying Nova Hellkite and 2/2 Razorkin Needlehead using a 4/5 flying Nova Hellkite, 1/4 Magebane Lizard, and 1/2 Hired Claw. The mechanical objective is survival; flying legality and Razorkin's first strike during its controller's turn must be honored.",
                    rootSeed = 30_002L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-04",
                    category = TacticalCategory.BLOCK,
                    description = "At 5 life, block an attacking 5/4 Sunspine Lynx with two 2/2 Hexing Squelchers. Compare taking lethal damage, chump-blocking with one creature, and double-blocking with four combined power to trade for the Lynx.",
                    rootSeed = 30_003L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-05",
                    category = TacticalCategory.BLOCK,
                    description = "At 5 life, choose which of an attacking 5/4 Sunspine Lynx and 2/2 Hexing Squelcher to block with one 1/4 Magebane Lizard. Find the assignment that survives combat.",
                    rootSeed = 30_004L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-06",
                    category = TacticalCategory.BLOCK,
                    description = "At 2 life, block an attacking 4/5 flying Nova Hellkite and 2/2 Hexing Squelcher using a 4/5 flying Nova Hellkite and 1/4 Magebane Lizard. Find the surviving assignment while respecting flying.",
                    rootSeed = 30_005L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-07",
                    category = TacticalCategory.BLOCK,
                    description = "At 5 life with one 1/2 Hired Claw, decide whether to block an attacking 5/4 Sunspine Lynx. Find the line that survives combat.",
                    rootSeed = 30_006L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-08",
                    category = TacticalCategory.BLOCK,
                    description = "At 4 life, choose which attacker to block with a 4/5 flying Nova Hellkite when a 4/5 flying Nova Hellkite and 2/2 Hexing Squelcher attack. Find the surviving line.",
                    rootSeed = 30_007L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-09",
                    category = TacticalCategory.BLOCK,
                    description = "At 1 life, block a 1/1 Burnout Bashtronaut with menace using two 1/2 Hired Claws. Menace makes a one-creature block illegal, so find the surviving declaration among the exhaustive choices.",
                    rootSeed = 30_008L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-10",
                    category = TacticalCategory.BLOCK,
                    description = "At 2 life, block an attacking 2/2 Razorkin Needlehead with a 2/2 Hexing Squelcher and 1/2 Hired Claw. Compare single and double blocks while accounting for Razorkin's first strike during its controller's turn.",
                    rootSeed = 30_009L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-11",
                    category = TacticalCategory.BLOCK,
                    description = "At 2 life, assign a 1/4 Magebane Lizard and 1/2 Hired Claw against attacking 5/4 Sunspine Lynx and 2/2 Hexing Squelcher. Find every surviving assignment, then compare the material that remains.",
                    rootSeed = 30_010L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
                TacticalCaseDefinition(
                    id = "block-12",
                    category = TacticalCategory.BLOCK,
                    description = "At 5 life, distribute two 1/2 Hired Claws against attacking 5/4 Sunspine Lynx and 2/2 Hexing Squelcher. Find the declarations that stop lethal damage, then compare single, pile, and split structures.",
                    rootSeed = 30_011L,
                    captureDecisionIndex = 0,
                    mechanicallyVerifiable = true,
                    startingStateRationale = LEGALLY_REACHED_BLOCK_STATE,
                ),
            )
        )
        repeat(6) { family ->
            repeat(2) { variant ->
                add(
                    TacticalCaseDefinition(
                        id = "hidden-${family + 1}-${variant + 1}",
                        category = TacticalCategory.HIDDEN_STATE,
                        description = hiddenLethalDescriptions()[family],
                        rootSeed = 50_000L + family,
                        captureDecisionIndex = 0,
                        mechanicallyVerifiable = true,
                        hiddenFamily = "hidden-family-${family + 1}",
                    )
                )
            }
        }
    }

    fun validate() {
        require(cases.size == 48)
        require(cases.map { it.id }.distinct().size == 48)
        require(cases.all { it.mechanicallyVerifiable })
        require(cases.count { it.id.startsWith("lethal-") } == 18)
        require(cases.count { it.id.startsWith("attack-") } == 6)
        require(cases.count { it.id.startsWith("block-") } == 12)
        require(cases.mapNotNull { it.hiddenFamily }.distinct().size == 6)
        require(cases.none { it.requiresMoreThan64Responses })
    }

    private fun lethalDescriptions() = listOf(
        "Find lethal against an opponent at 2 life with Shock and one untapped Mountain.",
        "Find lethal against an opponent at 2 life when Shock can target either the opponent or a Hired Claw.",
        "Find lethal against an opponent at 2 life when Shock has two plausible creature targets as distractions.",
        "Find lethal against an opponent at 3 life with Lightning Strike and exactly two untapped Mountains.",
        "Find lethal against an opponent at 3 life when Lightning Strike can target either the opponent or a Hexing Squelcher.",
        "Find lethal against an opponent at 3 life when Lightning Strike has two plausible creature-removal targets.",
        "Find lethal against an opponent at 2 life with an unkicked Burst Lightning.",
        "Find lethal against an opponent at 2 life when Burst Lightning can remove a Burnout Bashtronaut instead.",
        "Find lethal against an opponent at 2 life when Burst Lightning has two plausible creature targets.",
        "Find four damage of lethal with Burst Lightning and five untapped Mountains; compare its kicked and unkicked modes.",
        "Find four damage of lethal with Burst Lightning while an opposing Sunspine Lynx is also a legal target.",
        "At 3 opponent life with Shock and Lightning Strike in hand, identify the burn spell and target that are lethal.",
        "At 3 opponent life with Burst Lightning and Lightning Strike in hand, identify the burn spell and target that are lethal.",
        "At 2 opponent life with Shock and Burst Lightning in hand, identify every lethal face-burn choice while rejecting removal and passing.",
        "Find lethal at 4 opponent life by using Ojer Axonil's replacement effect with Shock.",
        "Find lethal at 4 opponent life by using Ojer Axonil's replacement effect with Burst Lightning.",
        "Find lethal at 4 opponent life by using Ojer Axonil's replacement effect with Lightning Strike.",
        "Find lethal by casting Sunspine Lynx against an opponent at 2 life who controls two nonbasic lands.",
    )

    private fun attackDescriptions() = listOf(
        "At 2 opponent life with one 2/2 Hexing Squelcher, find the lethal attack declaration.",
        "At 3 opponent life with a 2/2 Hexing Squelcher and 1/4 Magebane Lizard, find the lethal attacker set.",
        "At 6 opponent life with a 4/5 flying Nova Hellkite and 2/2 Hexing Squelcher, find the lethal attacker set.",
        "At 6 opponent life with a 5/4 Sunspine Lynx and 1/1 Burnout Bashtronaut, find the lethal attacker set.",
        "At 4 opponent life with two 2-power creatures and a 1-power creature, identify every lethal attacker set.",
        "At 3 opponent life with two Hired Claws and a Hexing Squelcher, find lethal while accounting for both Hired Claw attack triggers.",
    )

    private fun hiddenLethalDescriptions() = listOf(
        "Find Shock lethal at 2 life; the opponent's one-card hidden hand differs across a paired but observationally identical state.",
        "Find Lightning Strike lethal at 3 life; the opponent's one-card hidden hand differs across a paired but observationally identical state.",
        "Find Burst Lightning lethal at 2 life among creature targets; the opponent's hidden hand differs across the paired state.",
        "Choose Lightning Strike over Shock for lethal at 3 life; the opponent's hidden hand differs across the paired state.",
        "Use Ojer Axonil and Shock for lethal at 4 life; the opponent's hidden hand differs across the paired state.",
        "Find the Hired Claw trigger-assisted lethal attack at 3 life; the opponent's hidden hand differs across the paired state.",
    )

    private const val LEGALLY_REACHED_BLOCK_STATE =
        "The fixture is constructed at declare attackers with an empty stack and no skipped attack triggers. " +
            "The displayed state is then reached through the engine by legally declaring every listed attacker " +
            "and taking only pass-priority transitions until the defender receives the declare-blockers decision."
}

internal class TacticalScenarioFactory(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
) {
    private val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)

    fun create(case: TacticalCaseDefinition): ArgentumSearchWorld = when {
        case.id.startsWith("lethal-") -> lethal(case)
        case.id.startsWith("attack-") -> attack(case)
        case.id.startsWith("block-") -> block(case)
        case.id.startsWith("hidden-") -> hidden(case)
        else -> error("Unknown tactical scenario ${case.id}")
    }

    private fun lethal(case: TacticalCaseDefinition): ArgentumSearchWorld {
        val index = case.id.substringAfter('-').toInt() - 1
        val setup = LETHAL_SETUPS[index]
        return scenario(
            case,
            p0Hand = setup.hand,
            p0Battlefield = setup.battlefield,
            p1Battlefield = setup.opposingBattlefield,
            p1Life = setup.opposingLife,
            phase = Phase.PRECOMBAT_MAIN,
            step = Step.PRECOMBAT_MAIN,
        )
    }

    private fun attack(case: TacticalCaseDefinition): ArgentumSearchWorld {
        val index = case.id.substringAfter('-').toInt() - 1
        val setup = ATTACK_SETUPS[index]
        return scenario(
            case,
            p0Battlefield = setup.attackers,
            p1Life = setup.opposingLife,
            phase = Phase.COMBAT,
            step = Step.DECLARE_ATTACKERS,
        )
    }

    private fun block(case: TacticalCaseDefinition): ArgentumSearchWorld {
        val setup = when (case.id) {
            "block-01" -> BlockSetup(
                attackers = listOf("Hexing Squelcher", "Hexing Squelcher"),
                blockers = listOf("Hired Claw", "Hired Claw"),
                defendingLife = 3,
            )
            "block-02" -> BlockSetup(
                attackers = listOf("Burnout Bashtronaut", "Hexing Squelcher"),
                blockers = listOf("Hired Claw", "Hired Claw", "Hired Claw"),
                defendingLife = 2,
            )
            "block-03" -> BlockSetup(
                attackers = listOf("Nova Hellkite", "Razorkin Needlehead"),
                blockers = listOf("Nova Hellkite", "Magebane Lizard", "Hired Claw"),
                defendingLife = 5,
            )
            "block-04" -> BlockSetup(
                attackers = listOf("Sunspine Lynx"),
                blockers = listOf("Hexing Squelcher", "Hexing Squelcher"),
                defendingLife = 5,
            )
            "block-05" -> BlockSetup(
                attackers = listOf("Sunspine Lynx", "Hexing Squelcher"),
                blockers = listOf("Magebane Lizard"),
                defendingLife = 5,
            )
            "block-06" -> BlockSetup(
                attackers = listOf("Nova Hellkite", "Hexing Squelcher"),
                blockers = listOf("Nova Hellkite", "Magebane Lizard"),
                defendingLife = 2,
            )
            "block-07" -> BlockSetup(
                attackers = listOf("Sunspine Lynx"),
                blockers = listOf("Hired Claw"),
                defendingLife = 5,
            )
            "block-08" -> BlockSetup(
                attackers = listOf("Nova Hellkite", "Hexing Squelcher"),
                blockers = listOf("Nova Hellkite"),
                defendingLife = 4,
            )
            "block-09" -> BlockSetup(
                attackers = listOf("Burnout Bashtronaut"),
                blockers = listOf("Hired Claw", "Hired Claw"),
                defendingLife = 1,
            )
            "block-10" -> BlockSetup(
                attackers = listOf("Razorkin Needlehead"),
                blockers = listOf("Hexing Squelcher", "Hired Claw"),
                defendingLife = 2,
            )
            "block-11" -> BlockSetup(
                attackers = listOf("Sunspine Lynx", "Hexing Squelcher"),
                blockers = listOf("Magebane Lizard", "Hired Claw"),
                defendingLife = 2,
            )
            "block-12" -> BlockSetup(
                attackers = listOf("Sunspine Lynx", "Hexing Squelcher"),
                blockers = listOf("Hired Claw", "Hired Claw"),
                defendingLife = 5,
            )
            else -> error("Unknown blocking scenario ${case.id}")
        }
        val world = scenario(
            case,
            p0Battlefield = setup.attackers,
            p1Battlefield = setup.blockers,
            p1Life = setup.defendingLife,
            phase = Phase.COMBAT,
            step = Step.DECLARE_ATTACKERS,
        )
        val declaration = world.expandChoices(2_048).candidates.single { choice ->
            choice.actionType() == "DeclareAttackers" && choice.assignmentCount("attackers") == setup.attackers.size
        }
        require(world.step(declaration).accepted) { "Could not legally declare attackers for ${case.id}" }
        repeat(16) {
            val actor = requireNotNull(world.actorToAct()) { "${case.id} terminated before declare blockers" }
            val information = world.informationState(actor)
            if (actor == "p1" && information.observation.step == "DECLARE_BLOCKERS") return world
            val pass = world.expandChoices(2_048).candidates.singleOrNull { it.actionType() == "PassPriority" }
                ?: error("${case.id} encountered a non-pass decision before declare blockers")
            require(world.step(pass).accepted) { "Could not advance ${case.id} to declare blockers" }
        }
        error("${case.id} did not reach declare blockers within 16 policy decisions")
    }

    private fun SemanticChoice.actionType(): String? = canonicalPayload["body"]
        ?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull

    private fun SemanticChoice.assignmentCount(field: String): Int = canonicalPayload["body"]
        ?.jsonObject?.get(field)?.jsonObject?.size ?: -1

    private data class BlockSetup(
        val attackers: List<String>,
        val blockers: List<String>,
        val defendingLife: Int,
    )

    private fun hidden(case: TacticalCaseDefinition): ArgentumSearchWorld {
        val family = case.hiddenFamily!!.substringAfterLast('-').toInt()
        val variant = case.id.substringAfterLast('-').toInt()
        val hiddenCard = if (variant == 1) "Shock" else "Hired Claw"
        val setup = HIDDEN_SETUPS[family - 1]
        return scenario(
            case.copy(rootSeed = 50_000L + family),
            gameId = case.hiddenFamily,
            p0Hand = setup.hand,
            p1Hand = listOf(hiddenCard),
            p0Battlefield = setup.battlefield,
            p1Battlefield = setup.opposingBattlefield,
            p1Life = setup.opposingLife,
            phase = setup.phase,
            step = setup.step,
        )
    }

    private data class LethalSetup(
        val hand: List<String>,
        val battlefield: List<String>,
        val opposingBattlefield: List<String>,
        val opposingLife: Int,
    )

    private data class AttackSetup(val attackers: List<String>, val opposingLife: Int)

    private data class HiddenSetup(
        val hand: List<String>,
        val battlefield: List<String>,
        val opposingBattlefield: List<String>,
        val opposingLife: Int,
        val phase: Phase = Phase.PRECOMBAT_MAIN,
        val step: Step = Step.PRECOMBAT_MAIN,
    )

    private companion object {
        val LETHAL_SETUPS = listOf(
            LethalSetup(listOf("Shock"), listOf("Mountain"), emptyList(), 2),
            LethalSetup(listOf("Shock"), listOf("Mountain"), listOf("Hired Claw"), 2),
            LethalSetup(listOf("Shock"), listOf("Mountain"), listOf("Hired Claw", "Burnout Bashtronaut"), 2),
            LethalSetup(listOf("Lightning Strike"), listOf("Mountain", "Mountain"), emptyList(), 3),
            LethalSetup(listOf("Lightning Strike"), listOf("Mountain", "Mountain"), listOf("Hexing Squelcher"), 3),
            LethalSetup(listOf("Lightning Strike"), listOf("Mountain", "Mountain"), listOf("Hexing Squelcher", "Magebane Lizard"), 3),
            LethalSetup(listOf("Burst Lightning"), listOf("Mountain"), emptyList(), 2),
            LethalSetup(listOf("Burst Lightning"), listOf("Mountain"), listOf("Burnout Bashtronaut"), 2),
            LethalSetup(listOf("Burst Lightning"), listOf("Mountain"), listOf("Burnout Bashtronaut", "Hired Claw"), 2),
            LethalSetup(listOf("Burst Lightning"), List(5) { "Mountain" }, emptyList(), 4),
            LethalSetup(listOf("Burst Lightning"), List(5) { "Mountain" }, listOf("Sunspine Lynx"), 4),
            LethalSetup(listOf("Shock", "Lightning Strike"), listOf("Mountain", "Mountain"), listOf("Hired Claw"), 3),
            LethalSetup(listOf("Burst Lightning", "Lightning Strike"), listOf("Mountain", "Mountain"), listOf("Hexing Squelcher"), 3),
            LethalSetup(listOf("Shock", "Burst Lightning"), listOf("Mountain"), listOf("Hired Claw", "Burnout Bashtronaut"), 2),
            LethalSetup(listOf("Shock"), listOf("Mountain", "Ojer Axonil, Deepest Might"), listOf("Hired Claw"), 4),
            LethalSetup(listOf("Burst Lightning"), listOf("Mountain", "Ojer Axonil, Deepest Might"), listOf("Hexing Squelcher"), 4),
            LethalSetup(listOf("Lightning Strike"), listOf("Mountain", "Mountain", "Ojer Axonil, Deepest Might"), listOf("Magebane Lizard"), 4),
            LethalSetup(listOf("Sunspine Lynx"), List(4) { "Mountain" }, listOf("Rockface Village", "Soulstone Sanctuary"), 2),
        )

        val ATTACK_SETUPS = listOf(
            AttackSetup(listOf("Hexing Squelcher"), 2),
            AttackSetup(listOf("Hexing Squelcher", "Magebane Lizard"), 3),
            AttackSetup(listOf("Nova Hellkite", "Hexing Squelcher"), 6),
            AttackSetup(listOf("Sunspine Lynx", "Burnout Bashtronaut"), 6),
            AttackSetup(listOf("Razorkin Needlehead", "Hexing Squelcher", "Magebane Lizard"), 4),
            AttackSetup(listOf("Hired Claw", "Hired Claw", "Hexing Squelcher"), 3),
        )

        val HIDDEN_SETUPS = listOf(
            HiddenSetup(listOf("Shock"), listOf("Mountain"), listOf("Hired Claw"), 2),
            HiddenSetup(listOf("Lightning Strike"), listOf("Mountain", "Mountain"), listOf("Hexing Squelcher"), 3),
            HiddenSetup(listOf("Burst Lightning"), listOf("Mountain"), listOf("Burnout Bashtronaut", "Hired Claw"), 2),
            HiddenSetup(listOf("Shock", "Lightning Strike"), listOf("Mountain", "Mountain"), listOf("Hired Claw"), 3),
            HiddenSetup(listOf("Shock"), listOf("Mountain", "Ojer Axonil, Deepest Might"), listOf("Hexing Squelcher"), 4),
            HiddenSetup(
                hand = emptyList(),
                battlefield = listOf("Hired Claw", "Hired Claw", "Hexing Squelcher"),
                opposingBattlefield = emptyList(),
                opposingLife = 3,
                phase = Phase.COMBAT,
                step = Step.DECLARE_ATTACKERS,
            ),
        )
    }

    private fun scenario(
        case: TacticalCaseDefinition,
        gameId: String = case.id,
        p0Hand: List<String> = emptyList(),
        p1Hand: List<String> = emptyList(),
        p0Battlefield: List<String> = emptyList(),
        p1Battlefield: List<String> = emptyList(),
        p0Life: Int = 20,
        p1Life: Int = 20,
        phase: Phase = Phase.PRECOMBAT_MAIN,
        step: Step = Step.PRECOMBAT_MAIN,
    ): ArgentumSearchWorld {
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
        val playerIds = environment.playerIds
        var state = arrangePlayer(environment.state, playerIds[0], p0Hand, p0Battlefield, p0Life)
        state = arrangePlayer(state, playerIds[1], p1Hand, p1Battlefield, p1Life)
        state = state.copy(
            phase = phase,
            step = step,
            activePlayerId = playerIds[0],
            priorityPlayerId = if (step == Step.DECLARE_BLOCKERS) playerIds[1] else playerIds[0],
            priorityPassedBy = emptySet(),
            stack = emptyList(),
            pendingDecision = null,
            continuationStack = emptyList(),
            winnerId = null,
            gameOver = false,
        )
        environment.restore(
            state,
            playerIds,
        )
        return wrap(environment, gameId, case.rootSeed)
    }

    private fun arrangePlayer(
        original: GameState,
        playerId: EntityId,
        handNames: List<String>,
        battlefieldNames: List<String>,
        life: Int,
    ): GameState {
        val owned = Zone.entries.flatMap { original.getZone(playerId, it) }.distinct()
        val available = owned.groupBy { entityId ->
            requireNotNull(original.getEntity(entityId)?.get<CardComponent>()).name
        }.mapValues { (_, ids) -> ids.sortedBy(EntityId::value).toMutableList() }

        fun take(names: List<String>): List<EntityId> = names.map { name ->
            val candidates = requireNotNull(available[name]) { "No $name in $playerId deck" }
            require(candidates.isNotEmpty()) { "Scenario overuses $name for $playerId" }
            candidates.removeAt(0)
        }

        val hand = take(handNames)
        val battlefield = take(battlefieldNames)
        val library = available.toSortedMap().values.flatten()
        val clearedZones = original.zones + Zone.entries.associate { zone -> ZoneKey(playerId, zone) to emptyList() }
        var state = original.copy(
            zones = clearedZones + mapOf(
                ZoneKey(playerId, Zone.HAND) to hand,
                ZoneKey(playerId, Zone.BATTLEFIELD) to battlefield,
                ZoneKey(playerId, Zone.LIBRARY) to library,
            )
        )
        val staticHandler = StaticAbilityHandler(registry)
        battlefield.forEach { cardId ->
            val card = requireNotNull(state.getEntity(cardId)?.get<CardComponent>())
            val definition = registry.requireCard(card.name)
            state = state.updateEntity(cardId) { container ->
                staticHandler.addReplacementEffectComponent(
                    staticHandler.addContinuousEffectComponent(
                        container
                            .with(ControllerComponent(playerId))
                            .without<TappedComponent>()
                            .without<SummoningSicknessComponent>(),
                        definition,
                    ),
                    definition,
                )
            }
        }
        return state.updateEntity(playerId) { it.with(LifeTotalComponent(life)) }
    }

    private fun wrap(environment: GameEnvironment, gameId: String, seed: Long) = ArgentumSearchWorld.create(
        environment = environment,
        gameId = gameId,
        seedBase = seed,
        effectiveSetupSeed = seed,
       expander = UnifiedSemanticExpander(actionSpaceProfile = actionSpaceProfile),
       knownDecks = knownDecks,
    )
}

internal class TacticalBenchmarkRunner(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val profile: FrozenSearchProfile,
) {
    private val factory = TacticalScenarioFactory(registry, manifest, profile.actionSpaceProfile)
    private val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)

    fun run(
        cases: List<TacticalCaseDefinition> = TacticalBenchmarkCatalog.cases,
        includeStrategicReference: Boolean = false,
    ): TacticalReport {
        TacticalBenchmarkCatalog.validate()
        require(cases.isNotEmpty())
        val results = cases.map { case ->
            println("Tactical case ${case.id}")
            runCase(case, includeStrategicReference)
        }
        val forced = cases.zip(results).filter { it.first.mechanicallyVerifiable }
        val hiddenFailures = results.filter { it.id.startsWith("hidden-") }
            .groupBy { it.id.substringBeforeLast('-') }
            .count { (_, pair) -> pair.map { it.chosenSignature }.distinct().size != 1 }
        val compared = results.filter { it.regret != null && it.heuristicRegret != null }
        val searchRegret = compared.map { requireNotNull(it.regret) }
        val heuristicRegret = compared.map { requireNotNull(it.heuristicRegret) }
        val improvements = searchRegret.zip(heuristicRegret) { search, heuristic -> heuristic - search }
        val interval = improvements.takeIf { it.isNotEmpty() }?.let {
            bootstrapInterval(it, seed = 20260823L, samples = 10_000)
        }
        val meanSearchRegret = searchRegret.takeIf { it.isNotEmpty() }?.average()
        val meanHeuristicRegret = heuristicRegret.takeIf { it.isNotEmpty() }?.average()
        val reduction = meanSearchRegret?.let { search ->
            meanHeuristicRegret?.takeIf { it > 0.0 }?.let { heuristic -> (heuristic - search) / heuristic }
        }
        val hasStrategicReferenceCases = cases.any { !it.mechanicallyVerifiable && it.hiddenFamily == null }
        val proposalFailures = results.count { (it.proposalRegret ?: 0.0) > MAX_PROPOSAL_REGRET ||
            it.diagnostic?.contains("proposal", true) == true }
        val failures = buildList {
            if (forced.count { it.second.solved == true } != forced.size) {
                add("not all mechanically forced cases were solved")
            }
            if (hiddenFailures > 0) add("deployable policy changed across hidden-state pairs")
            if (includeStrategicReference && hasStrategicReferenceCases && compared.isEmpty()) {
                add("no strategic case had a separated reference")
            }
            if (includeStrategicReference && hasStrategicReferenceCases && (reduction == null || reduction < 0.25)) {
                add("mean strategic regret was not at least 25% below the heuristic")
            }
            if (includeStrategicReference && hasStrategicReferenceCases && (interval == null || interval.first <= 0.0)) {
                add("paired regret-improvement confidence interval did not exclude zero")
            }
            if (proposalFailures > 0) add("proposal stress gate failed")
        }
        return TacticalReport(
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            profileId = profile.id,
            cases = results,
            mechanicallyForcedSolved = forced.count { it.second.solved == true },
            mechanicallyForcedTotal = forced.size,
            meanStrategicRegret = meanSearchRegret,
            heuristicMeanStrategicRegret = meanHeuristicRegret,
            strategicSeparatedCases = compared.size,
            regretReductionFraction = reduction,
            regretImprovementConfidenceLower = interval?.first,
            regretImprovementConfidenceUpper = interval?.second,
            maximumProposalRegret = results.mapNotNull { it.proposalRegret }.maxOrNull(),
            hiddenStateFailures = hiddenFailures,
            proposalStressFailures = proposalFailures,
            heuristicFallbacks = results.count(TacticalCaseResult::heuristicFallback),
            rootRolloutDecisions = results.sumOf(TacticalCaseResult::rootRolloutDecisions),
            opponentRolloutDecisions = results.sumOf(TacticalCaseResult::opponentRolloutDecisions),
            rootRolloutFallbacks = results.sumOf(TacticalCaseResult::rootRolloutFallbacks),
            opponentRolloutFallbacks = results.sumOf(TacticalCaseResult::opponentRolloutFallbacks),
            quiescenceForcedPasses = results.sumOf(TacticalCaseResult::quiescenceForcedPasses),
            quiescenceStrategicDecisions = results.sumOf(TacticalCaseResult::quiescenceStrategicDecisions),
            quiescenceFallbacks = results.sumOf(TacticalCaseResult::quiescenceFallbacks),
            passed = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    internal fun runCase(
        case: TacticalCaseDefinition,
        includeStrategicReference: Boolean = false,
    ): TacticalCaseResult {
        val world = factory.create(case)
        val actor = requireNotNull(world.actorToAct())
        val expansionStarted = System.nanoTime()
        val initial = world.expandChoices()
        val full = (world as ProgressiveSearchWorld).expandChoices(2_048)
        val expansionMillis = (System.nanoTime() - expansionStarted) / 1_000_000.0
        val acceptable = if (case.mechanicallyVerifiable) mechanicallyAcceptableChoices(world, actor, full.candidates, case) else emptySet()
        val heuristicFallbackSeed = ComponentSeeds.derive(case.rootSeed, "tactical-heuristic-fallback")
        val determinizedHeuristic = world.determinizedHeuristicChoiceOrNull()
        val heuristic = determinizedHeuristic ?: sample(
            SemanticHeuristicOpponentPolicy().distribution(
                world.informationState(actor),
                initial.candidates,
                heuristicFallbackSeed,
            ),
            heuristicFallbackSeed,
        )
        val beliefStarted = System.nanoTime()
        val belief = ArgentumKnownDeckBeliefWorldSource(world).sample(
            world.informationState(actor),
            knownDecks,
            ComponentSeeds.derive(case.rootSeed, "tactical-belief"),
            profile.particles,
        )
        val beliefMillis = (System.nanoTime() - beliefStarted) / 1_000_000.0
        val search = SearchTeacherSearchFactory.create(
            InformationSetSearchConfig(
                simulations = profile.simulations,
                explorationConstant = profile.explorationConstant,
                maxPolicyDecisions = profile.maxPolicyDecisions,
                leaf = profile.leaf,
            ),
            defaultMonoRedOpponentPolicy(),
        )
        val searchStarted = System.nanoTime()
        val result = search.search(actor, belief, ComponentSeeds.derive(case.rootSeed, "tactical-search"))
        val searchMillis = (System.nanoTime() - searchStarted) / 1_000_000.0
        val latency = expansionMillis + beliefMillis + searchMillis
        val proposalRegret = if (case.requiresMoreThan64Responses && acceptable.isNotEmpty()) {
            if (initial.candidates.any { it.signature in acceptable }) 0.0 else 1.0
        } else null
        val proposalFailure = if (case.requiresMoreThan64Responses && acceptable.isNotEmpty() &&
            acceptable.none { signature -> signature in result.candidates.map { it.choice.signature } }) {
            "proposal omitted every mechanically acceptable action"
        } else null
        val reference = when {
            case.referenceValues.isNotEmpty() -> compareWithReference(
                values = case.referenceValues,
                chosenSignature = result.chosen.signature,
                heuristicSignature = heuristic.signature,
            )
            includeStrategicReference && !case.mechanicallyVerifiable && case.hiddenFamily == null -> {
                val referenceBelief = ArgentumKnownDeckBeliefWorldSource(world).sample(
                    world.informationState(actor),
                    knownDecks,
                    ComponentSeeds.derive(case.rootSeed, "tactical-reference-belief"),
                    64,
                )
                val referenceSearch = SearchTeacherSearchFactory.create(
                    InformationSetSearchConfig(
                        simulations = 4_096,
                        explorationConstant = profile.explorationConstant,
                        maxPolicyDecisions = profile.maxPolicyDecisions,
                        leaf = LeafEvaluationConfig(
                            LeafStateSource.CURRENT_SAMPLED_WORLD,
                            LeafEvaluator.ARGENTUM_BOARD_V1,
                        ),
                    ),
                    defaultMonoRedOpponentPolicy(),
                ).search(actor, referenceBelief, ComponentSeeds.derive(case.rootSeed, "tactical-reference-search"))
                compareWithReference(
                    values = referenceSearch.candidates.filter { it.visits > 0 }
                        .associate { it.choice.signature to it.meanValue },
                    chosenSignature = result.chosen.signature,
                    heuristicSignature = heuristic.signature,
                )
            }
            else -> null
        }
        return TacticalCaseResult(
            id = case.id,
            solved = if (case.mechanicallyVerifiable) result.chosen.signature in acceptable else null,
            chosenSignature = result.chosen.signature,
            chosenLabel = result.chosen.display.label,
            heuristicSignature = heuristic.signature,
            heuristicLabel = heuristic.display.label,
            heuristicFallback = determinizedHeuristic == null,
            candidateCount = result.candidates.size,
            estimatedCandidateCount = full.estimatedCandidateCount,
            searchValue = result.rootValue,
            regret = reference?.searchRegret,
            heuristicRegret = reference?.heuristicRegret,
            referenceBestSignature = reference?.bestSignature,
            referenceBestValue = reference?.bestValue,
            referenceSeparation = reference?.separation,
            proposalRegret = proposalRegret,
            latencyMillis = latency,
            expansionMillis = expansionMillis,
            beliefMillis = beliefMillis,
            searchMillis = searchMillis,
            rootRolloutPolicyId = result.diagnostics.rootRolloutPolicyId,
            opponentRolloutPolicyId = result.diagnostics.opponentRolloutPolicyId,
            rootRolloutDecisions = result.diagnostics.rootRolloutDecisions,
            opponentRolloutDecisions = result.diagnostics.opponentRolloutDecisions,
            rootRolloutFallbacks = result.diagnostics.rootRolloutFallbacks,
            opponentRolloutFallbacks = result.diagnostics.opponentRolloutFallbacks,
            maximumDepth = result.diagnostics.maximumDepth,
            quiescenceForcedPasses = result.diagnostics.quiescenceForcedPasses,
            quiescenceStrategicDecisions = result.diagnostics.quiescenceStrategicDecisions,
            quiescenceFallbacks = result.diagnostics.quiescenceFallbacks,
            diagnostic = proposalFailure ?: if ((proposalRegret ?: 0.0) > MAX_PROPOSAL_REGRET) {
                "initial proposal normalized regret exceeds $MAX_PROPOSAL_REGRET"
            } else if (acceptable.isEmpty() && case.mechanicallyVerifiable) {
                "mechanical oracle found no acceptable action"
            } else null,
        )
    }

}

internal fun mechanicallyAcceptableChoices(
    world: ArgentumSearchWorld,
    actor: String,
    candidates: List<SemanticChoice>,
    case: TacticalCaseDefinition,
): Set<String> = candidates.filter { choice ->
    val child = world.fork()
    if (!child.step(choice).accepted) return@filter false
    repeat(48) {
        child.terminalPayoff(actor)?.let { payoff -> return@filter payoff == 1.0 }
        val actingPlayer = child.actorToAct() ?: return@filter false
        val info = child.informationState(actingPlayer)
        if (case.id.startsWith("block-") && info.observation.phase != "COMBAT") {
            return@filter info.observation.players.single { it.playerId == actor }.life > 0
        }
        val expansion = child.expandChoices()
        val pass = expansion.candidates.firstOrNull { it.display.label.contains("pass", true) }
            // Multi-block combat can require a damage-order/assignment response before it resolves.
            // These fixtures have no trample, so that mandatory choice cannot change player survival.
            ?: expansion.candidates.firstOrNull {
                case.id.startsWith("block-") && it.kind == SemanticChoiceKind.DECISION
            }
            ?: expansion.candidates.singleOrNull()
            ?: return@filter false
        if (!child.step(pass).accepted) return@filter false
    }
    false
}.map { it.signature }.toSet()

internal data class TacticalReferenceComparison(
    val bestSignature: String,
    val bestValue: Double,
    val separation: Double,
    val searchRegret: Double,
    val heuristicRegret: Double,
)

internal fun compareWithReference(
    values: Map<String, Double>,
    chosenSignature: String,
    heuristicSignature: String,
): TacticalReferenceComparison? {
    if (values.size < 2) return null
    val ranked = values.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
    val best = ranked.first()
    val separation = (best.value - ranked[1].value) / 2.0
    if (separation <= STRATEGIC_REFERENCE_SEPARATION) return null
    val chosen = values[chosenSignature] ?: return null
    val heuristic = values[heuristicSignature] ?: return null
    return TacticalReferenceComparison(
        bestSignature = best.key,
        bestValue = best.value,
        separation = separation,
        searchRegret = ((best.value - chosen) / 2.0).coerceIn(0.0, 1.0),
        heuristicRegret = ((best.value - heuristic) / 2.0).coerceIn(0.0, 1.0),
    )
}

private const val STRATEGIC_REFERENCE_SEPARATION = 0.02
private const val MAX_PROPOSAL_REGRET = 0.02
