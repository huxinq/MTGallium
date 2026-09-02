package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.mechanics.layers.StaticAbilityHandler
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.battlefield.SummoningSicknessComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.player.LifeLostThisTurnComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.PlayerSpeedComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

internal const val TACTICAL_HORIZON_SUITE_VERSION = "tactical-horizon-v2"

@Serializable
enum class TacticalHorizon { IMMEDIATE, WITHIN_TURN, ROOT_TURNS_1_2, ROOT_TURNS_3_PLUS }

@Serializable
internal data class TacticalHorizonCase(
    val id: String,
    val horizon: TacticalHorizon,
    val category: TacticalCategory,
    val title: String,
    val description: String,
    val startingStateRationale: String,
    val rootSeed: Long,
    val setup: HorizonSetup,
    /** Kept out of the blinded packet; this is an authoring pre-admission assertion, not an oracle. */
    val expectedAction: TacticalHorizonExpectedAction,
    val terminalJustification: String,
)

@Serializable
internal data class TacticalHorizonExpectedAction(
    val operationFamily: SemanticOperationFamily,
    val sourceName: String? = null,
    val targetNames: Set<String>? = null,
    val assignmentField: String? = null,
    val assignmentCount: Int? = null,
    val requiredAssignments: Map<String, String> = emptyMap(),
    val usesAlternativeCost: Boolean? = null,
) {
    init {
        require((assignmentField == null) == (assignmentCount == null))
    }

    fun matches(choice: SemanticChoice, information: org.mtgallium.agent.infoset.core.PolicyInformationState): Boolean {
        if (choice.operationFamily != operationFamily) return false
        if (sourceName != null && choice.display.sourceName != sourceName) return false
        if (targetNames != null && choice.selectedTargetNames(information) != targetNames) return false
        if (assignmentField != null && choice.assignmentCount(assignmentField) != assignmentCount) return false
        if (requiredAssignments.isNotEmpty() && !choice.hasAssignments(information, requiredAssignments)) return false
        if (usesAlternativeCost != null) {
            val actual = choice.canonicalPayload["body"]?.jsonObject
                ?.get("useAlternativeCost")?.jsonPrimitive?.boolean ?: false
            if (actual != usesAlternativeCost) return false
        }
        return true
    }
}

@Serializable
internal enum class HorizonSetup {
    DRAW_STEP_SHOCK_LADDER,
    LETHAL_SHOCK_RESPONSE,
    HIRED_MAGEBANE_PASS,
    HIRED_COUNTER_LETHAL,
    BASHTRONAUT_PUMP_LETHAL,
    OJER_SHOCK_LETHAL,
    ROCKFACE_HASTE_LETHAL,
    HOWLSQUAD_CAST_BEFORE_COMBAT,
    WARP_NOVA_THIS_TURN,
    SUNSPINE_BASIC_LAND_ORDER,
    EXACT_TRIGGER_ATTACK,
    NOVA_CLEAR_EXACT_BLOCKER,
    CHUMP_PRESERVE_CRACKBACK,
    END_STEP_SHOCK_STANDOFF,
    TAKE_LYNX_PRESERVE_ATTACKER,
    END_STEP_HIRED_COUNTER,
    NOVA_KILL_MARKED_FLIER,
    END_STEP_SHOCK_RAZORKIN,
    LONG_HIRED_COUNTER_CLOCK,
    LONG_HOWLSQUAD_TOKEN_CLOCK,
    LONG_RAZORKIN_DRAW_CLOCK,
    LONG_SOULSTONE_CLOCK,
    LONG_PRESERVE_RAZORKIN_BLOCK,
    LONG_WARP_RECAST_CLOCK,
    FLYING_EXACT_BLOCKS,
    COLORLESS_LAND_RED_REQUIREMENT,
    SOULSTONE_FLOATING_BLOCK,
    FULL_CAST_NOT_WARP,
}

internal object TacticalHorizonCatalog {
    val cases: List<TacticalHorizonCase> = listOf(
        case("immediate-01", TacticalHorizon.IMMEDIATE, TacticalCategory.PRIORITY_STACK,
            "Draw-step Shock ladder",
            "Both players are at 2 life. You have just drawn your second Shock and have priority with two red mana floating, no untapped mana source, and two Shocks in hand. The opponent's one-card hand is publicly forced to be Shock.",
            "The two red were floated before the draw-step capture and will empty when the step ends. Public zone depletion accounts for every other deck card.",
            HorizonSetup.DRAW_STEP_SHOCK_LADDER, castAtOpponent("Shock"),
            "Casting Shock at the opponent starts the stack race while retaining the second Shock as the winning reply. Passing loses both floating mana; every other target fails to win."),
        case("immediate-02", TacticalHorizon.IMMEDIATE, TacticalCategory.PRIORITY_STACK,
            "Answer the lethal Shock",
            "An opposing Shock targeting you is on the stack. Both players are at 2 life; your only card is Shock and your only available mana is one floating red.",
            STACK_FIXTURE, HorizonSetup.LETHAL_SHOCK_RESPONSE, castAtOpponent("Shock"),
            "The opposing Shock is lethal. Shock at the opponent resolves first and wins; passing or choosing another target loses."),
        case("immediate-03", TacticalHorizon.IMMEDIATE, TacticalCategory.HOLD_OR_CAST,
            "Hired trigger versus Magebane",
            "A Hired Claw attack trigger is lethal on the stack. You have one floating red and Shock in hand while the opponent controls Magebane Lizard.",
            "Reached by legally declaring Hired Claw as an attacker. The only available mana was floated before the capture; there is no mana-prefix action.",
            HorizonSetup.HIRED_MAGEBANE_PASS, action(SemanticOperationFamily.PASS_PRIORITY),
            "Passing resolves the lethal Hired trigger. Casting any noncreature spell puts Magebane's lethal trigger above it."),
        case("immediate-04", TacticalHorizon.IMMEDIATE, TacticalCategory.SEQUENCING,
            "Counter for combat lethal",
            "Before attackers, the opponent has already lost life this turn. Two red mana are floating, the opponent is at 3, and an opposing tapped Nova Hellkite threatens lethal next turn.",
            COMBAT_PRIORITY_FIXTURE, HorizonSetup.HIRED_COUNTER_LETHAL,
            action(SemanticOperationFamily.ACTIVATE_ABILITY, "Hired Claw"),
            "The counter makes Hired Claw's trigger-plus-combat attack deal three for lethal. Passing into combat deals only two and loses to the known Nova crack-back."),
        case("immediate-05", TacticalHorizon.IMMEDIATE, TacticalCategory.SEQUENCING,
            "Pump for double-strike lethal",
            "Before attackers, max-speed Burnout Bashtronaut has two red floating. The opponent is at 3 and has a tapped Nova Hellkite that is lethal next turn.",
            COMBAT_PRIORITY_FIXTURE, HorizonSetup.BASHTRONAUT_PUMP_LETHAL,
            action(SemanticOperationFamily.ACTIVATE_ABILITY, "Burnout Bashtronaut"),
            "One pump makes double-strike damage lethal. Passing deals two and loses to the forced crack-back."),
        case("immediate-06", TacticalHorizon.IMMEDIATE, TacticalCategory.PRIORITY_STACK,
            "Obvious Ojer lethal",
            "In your postcombat main phase, Ojer Axonil and the opposing Nova Hellkite are tapped after attacking. Both players are at 4, Nova is lethal next turn, and your only available mana is one floating red for Shock.",
            FLOATING_FIXTURE, HorizonSetup.OJER_SHOCK_LETHAL, castAtOpponent("Shock"),
            "Ojer replaces Shock's two damage with four, so targeting the opponent wins immediately. Passing or targeting either creature or yourself lets the opponent untap Nova and attack for lethal."),
        case("immediate-07", TacticalHorizon.IMMEDIATE, TacticalCategory.BLOCK,
            "Flying fixes both blocks",
            "At 4 life, an opposing Nova Hellkite and Sunspine Lynx attack into your Nova Hellkite and Magebane Lizard. The opponent is at 4, and both finite libraries are public.",
            FINITE_COMBAT_FIXTURE, HorizonSetup.FLYING_EXACT_BLOCKS,
            blockAssigned(2, mapOf("Nova Hellkite" to "Nova Hellkite", "Magebane Lizard" to "Sunspine Lynx")),
            "Only your Nova can legally block the flying attacker. Magebane must therefore block Lynx: this prevents all damage and leaves your Nova to attack for lethal next turn. Every incomplete assignment takes lethal, and putting Nova on Lynx leaves the opposing flier unblocked for lethal."),

        case("within-turn-01", TacticalHorizon.WITHIN_TURN, TacticalCategory.SEQUENCING,
            "Rockface haste window",
            "Hired Claw has just resolved. One red remains floating; Rockface Village is untapped. The opponent is at 2 and a tapped Nova Hellkite threatens lethal next turn.",
            "The Hired Claw cast was executed through the engine. Rockface's mana modes and haste mode are all exposed as raw actions.",
            HorizonSetup.ROCKFACE_HASTE_LETHAL,
            action(SemanticOperationFamily.ACTIVATE_ABILITY, "Rockface Village", setOf("Hired Claw")),
            "Only the haste activation permits Hired Claw to attack for trigger-plus-combat lethal this turn. Tapping Rockface for mana or passing loses the window."),
        case("within-turn-02", TacticalHorizon.WITHIN_TURN, TacticalCategory.SEQUENCING,
            "Cast the hasty Goblin",
            "Howlsquad Heavy is in play before combat, Burnout Bashtronaut is in hand, and one red is floating. The opponent is at 4 with a tapped lethal crack-back.",
            FLOATING_FIXTURE, HorizonSetup.HOWLSQUAD_CAST_BEFORE_COMBAT,
            action(SemanticOperationFamily.CAST_SPELL, "Burnout Bashtronaut"),
            "Casting Bashtronaut supplies the fourth point because Howlsquad grants it haste. Passing yields only Heavy plus its token for three and loses next turn."),
        case("within-turn-03", TacticalHorizon.WITHIN_TURN, TacticalCategory.SEQUENCING,
            "Warp before the crack-back",
            "Nova Hellkite and Shock are in hand with exactly three floating red and no untapped mana source. The opponent is at 4 and has a tapped Nova Hellkite.",
            FLOATING_FIXTURE, HorizonSetup.WARP_NOVA_THIS_TURN,
            action(SemanticOperationFamily.CAST_SPELL, "Nova Hellkite"),
            "Warp Nova attacks for lethal this turn. Shock is nonlethal, and passing loses to the opposing Nova after it untaps."),
        case("within-turn-04", TacticalHorizon.WITHIN_TURN, TacticalCategory.SEQUENCING,
            "Basic land before Sunspine",
            "Three red are floating, three Mountains are tapped, and the hand is Mountain, Soulstone Sanctuary, Sunspine Lynx. You are at 1; the opponent is at 2 with two nonbasic lands and a tapped lethal attacker.",
            FLOATING_FIXTURE, HorizonSetup.SUNSPINE_BASIC_LAND_ORDER,
            action(SemanticOperationFamily.PLAY_LAND, "Mountain"),
            "Mountain provides the fourth mana without adding a nonbasic. Soulstone makes the Lynx trigger kill both players; passing loses the casting window and then the game."),
        case("within-turn-05", TacticalHorizon.WITHIN_TURN, TacticalCategory.ATTACK,
            "All attackers is exactly lethal",
            "At declare attackers, Hired Claw and Hexing Squelcher face an opponent at 4 with no untapped blocker. A tapped Nova Hellkite is lethal on the crack-back.",
            COMBAT_FIXTURE, HorizonSetup.EXACT_TRIGGER_ATTACK,
            assigned(SemanticOperationFamily.DECLARE_ATTACKERS, "attackers", 2, setOf("Player 1")),
            "Attacking with both deals three combat damage plus Hired Claw's trigger. Every smaller attack is nonlethal and loses next turn."),
        case("within-turn-06", TacticalHorizon.WITHIN_TURN, TacticalCategory.SEQUENCING,
            "Nova clears the exact blocker",
            "Choose Nova Hellkite's enters target. The opponent is at 8 with untapped Burnout Bashtronaut, tapped Magebane Lizard, and a tapped lethal Nova. Your creatures can deal exactly eight only if Burnout cannot block.",
            "Nova was cast and resolved through the engine; the capture is its public target decision before combat.",
            HorizonSetup.NOVA_CLEAR_EXACT_BLOCKER,
            action(SemanticOperationFamily.DECISION_RESPONSE, "Nova Hellkite", setOf("Burnout Bashtronaut")),
            "Targeting Burnout removes the only untapped blocker and permits exact lethal. Targeting the tapped Magebane leaves a chump blocker and loses to the crack-back."),
        case("within-turn-07", TacticalHorizon.WITHIN_TURN, TacticalCategory.SEQUENCING,
            "Colorless land cannot cast Lightning Strike",
            "In your precombat main phase, one colorless mana is floating and your hand is Mountain, Soulstone Sanctuary, and Lightning Strike. You control no lands. The opponent is at 3 with a tapped Nova Hellkite that is lethal next turn.",
            "The floating mana empties when the precombat main phase ends. The raw action is the land play itself; the following mana ability and spell cast remain separate rules-exact actions.",
            HorizonSetup.COLORLESS_LAND_RED_REQUIREMENT,
            action(SemanticOperationFamily.PLAY_LAND, "Mountain"),
            "Playing Mountain supplies {R}, which combines with the expiring {C} to cast Lightning Strike for lethal. Soulstone produces only colorless mana, and passing loses the floating mana before the postcombat main phase, leaving the spell uncastable before Nova's crack-back."),

        case("short-01", TacticalHorizon.ROOT_TURNS_1_2, TacticalCategory.BLOCK,
            "Chump and preserve the crack-back",
            "At 2 life, block attacking Sunspine Lynx and Hexing Squelcher with Hired Claw and Nova Hellkite. The opponent is at 4; your only library card is public.",
            FINITE_COMBAT_FIXTURE, HorizonSetup.CHUMP_PRESERVE_CRACKBACK,
            blockAssigned(2, mapOf("Hired Claw" to "Sunspine Lynx", "Nova Hellkite" to "Hexing Squelcher")),
            "Both attackers must be blocked. Hired chumps Lynx while Nova kills Squelcher and survives to attack for lethal next turn; the reverse assignment loses both attackers and then the library race."),
        case("short-02", TacticalHorizon.ROOT_TURNS_1_2, TacticalCategory.PRIORITY_STACK,
            "End-step Shock standoff",
            "Both players are at 2 with one publicly forced Shock in hand. Your next draw is the second Shock. The opponent has passed in their end step; your only current mana is one floating red and both Mountains are tapped.",
            "The opponent's pass was executed through the engine. Public zone depletion proves both hands and the next draw.",
            HorizonSetup.END_STEP_SHOCK_STANDOFF, action(SemanticOperationFamily.PASS_PRIORITY),
            "Passing reaches the draw and the two-Shock ladder. Casting now lets the opponent respond with the sole Shock and win first."),
        case("short-03", TacticalHorizon.ROOT_TURNS_1_2, TacticalCategory.BLOCK,
            "Take five; preserve lethal",
            "At 6 life, Sunspine Lynx attacks your Hexing Squelcher while the opponent is at 2. The Lynx is the opponent's only relevant permanent.",
            FINITE_COMBAT_FIXTURE, HorizonSetup.TAKE_LYNX_PRESERVE_ATTACKER,
            assigned(SemanticOperationFamily.DECLARE_BLOCKERS, "blockers", 0, emptySet()),
            "Taking five leaves you at one and preserves Hexing for lethal next turn while Lynx is tapped. Blocking loses Hexing and the finite library race."),
        case("short-04", TacticalHorizon.ROOT_TURNS_1_2, TacticalCategory.SEQUENCING,
            "Last Hired counter condition",
            "In the opponent's end step, they have lost life this turn. Two red are floating, Hired Claw is 1/2, and the opponent is at 3 with a tapped Nova Hellkite.",
            "Captured after the opponent passed priority. The mana source is gone, and the life-loss condition resets next turn.",
            HorizonSetup.END_STEP_HIRED_COUNTER,
            action(SemanticOperationFamily.ACTIVATE_ABILITY, "Hired Claw"),
            "Activating now makes next turn's trigger-plus-combat damage lethal. Passing loses the condition; the smaller attack is followed by Nova's lethal crack-back."),
        case("short-05", TacticalHorizon.ROOT_TURNS_1_2, TacticalCategory.SEQUENCING,
            "Kill the marked opposing flier",
            "Choose Nova Hellkite's enters target in your postcombat main phase. You are at 1; the opposing Nova Hellkite has four damage marked and Magebane Lizard is undamaged. Both finite libraries are public.",
            "Nova was cast and resolved through the engine in your postcombat main phase. The marked damage was dealt earlier this turn and is visible.",
            HorizonSetup.NOVA_KILL_MARKED_FLIER,
            action(SemanticOperationFamily.DECISION_RESPONSE, "Nova Hellkite", setOf("Nova Hellkite")),
            "Targeting the marked opposing Nova kills it. Your Nova can then block the lone Magebane Lizard and fly for lethal next turn. Targeting Magebane merely marks its four toughness; the opposing Nova and Magebane attack together, and one unblocked attacker is lethal."),
        case("short-06", TacticalHorizon.ROOT_TURNS_1_2, TacticalCategory.BURN_ALLOCATION,
            "Remove the draw-step trigger source",
            "In the opponent's end step you are at 1 with Shock and one floating red. The opponent is at 3 and controls Razorkin Needlehead; Hired Claw plus Hexing Squelcher are lethal next turn if you survive the draw.",
            "The opponent has passed priority. All remaining cards and the next draw are public; no untapped mana source creates a prefix alias.",
            HorizonSetup.END_STEP_SHOCK_RAZORKIN,
            action(SemanticOperationFamily.CAST_SPELL, "Shock", setOf("Razorkin Needlehead")),
            "Shock to Razorkin prevents the lethal draw trigger and unlocks the next-turn attack. Shock to the opponent is nonlethal and loses in the draw step."),
        case("short-07", TacticalHorizon.ROOT_TURNS_1_2, TacticalCategory.SEQUENCING,
            "Animate the land before mana empties",
            "The opponent has passed priority in beginning of combat. You are at 5 with an untapped Soulstone Sanctuary and four colorless mana floating; their Sunspine Lynx threatens lethal. The public libraries show that surviving this turn wins by opponent deck-out.",
            "The four mana came from a source no longer on the battlefield and empties when beginning of combat ends. Soulstone's mana ability and creature ability are distinct raw actions.",
            HorizonSetup.SOULSTONE_FLOATING_BLOCK,
            action(SemanticOperationFamily.ACTIVATE_ABILITY, "Soulstone Sanctuary"),
            "Animating Soulstone now makes a 3/3 blocker and guarantees survival until the opponent's empty-library draw. Passing loses the mana before blockers; tapping Soulstone for {C} leaves it tapped and unable to block."),

        case("long-01", TacticalHorizon.ROOT_TURNS_3_PLUS, TacticalCategory.SEQUENCING,
            "Invest the expiring Hired counter",
            "Before combat, Hired Claw faces an opponent at 12. Two red are floating from a source that has left the battlefield; both finite libraries are completely public.",
            FINITE_CLOCK_FIXTURE, HorizonSetup.LONG_HIRED_COUNTER_CLOCK,
            action(SemanticOperationFamily.ACTIVATE_ABILITY, "Hired Claw"),
            "The counter raises each trigger-plus-combat attack from two to three. Four attacks then deal exactly 12 before your library empties; declining cannot meet the clock."),
        case("long-02", TacticalHorizon.ROOT_TURNS_3_PLUS, TacticalCategory.HOLD_OR_CAST,
            "Start the Howlsquad token clock",
            "Howlsquad Heavy is in hand with exactly three floating red from a removed source. The opponent is at 16 and both finite libraries are public.",
            FINITE_CLOCK_FIXTURE, HorizonSetup.LONG_HOWLSQUAD_TOKEN_CLOCK,
            action(SemanticOperationFamily.CAST_SPELL, "Howlsquad Heavy"),
            "Casting now produces attacks of 1, 4, 5, and 6 over four combats for exactly 16. Passing loses the only mana and cannot win before deck-out."),
        case("long-03", TacticalHorizon.ROOT_TURNS_3_PLUS, TacticalCategory.SEQUENCING,
            "Install the Razorkin draw clock",
            "Razorkin Needlehead is in hand with two floating red from a removed source. The opponent is at 3; the complete finite libraries show three opponent draws before your deck-out.",
            FINITE_CLOCK_FIXTURE, HorizonSetup.LONG_RAZORKIN_DRAW_CLOCK,
            action(SemanticOperationFamily.CAST_SPELL, "Razorkin Needlehead"),
            "Casting Razorkin converts the next three opponent draws into exactly lethal damage. Passing loses the only casting window and decks out first."),
        case("long-04", TacticalHorizon.ROOT_TURNS_3_PLUS, TacticalCategory.SEQUENCING,
            "Animate Soulstone before the clock",
            "Before combat, Soulstone Sanctuary is untapped with four colorless mana floating. The opponent is at 9 and the complete libraries allow exactly three attacks before your deck-out.",
            FINITE_CLOCK_FIXTURE, HorizonSetup.LONG_SOULSTONE_CLOCK,
            action(SemanticOperationFamily.ACTIVATE_ABILITY, "Soulstone Sanctuary"),
            "Animating now gives three 3-power attacks for exactly nine. Tapping Soulstone for mana or passing misses the current attack and the deck-out deadline."),
        case("long-05", TacticalHorizon.ROOT_TURNS_3_PLUS, TacticalCategory.BLOCK,
            "Preserve the Razorkin engine",
            "At 6 life, block Hexing Squelcher with Magebane Lizard and Razorkin Needlehead available. The opponent kept Sunspine Lynx back, is at 3, and three future opposing draws and both libraries are public.",
            FINITE_COMBAT_FIXTURE, HorizonSetup.LONG_PRESERVE_RAZORKIN_BLOCK,
            blockAssigned(1, mapOf("Magebane Lizard" to "Hexing Squelcher")),
            "Magebane blocks Squelcher now while Razorkin remains available for the three-draw clock. With no block, the player falls to four; the opponent can then attack with only Lynx while retaining Squelcher, forcing Magebane to chump and preventing Razorkin from both penetrating the blocker and surviving to finish the clock. Blocking with Razorkin trades away the only engine."),
        case("long-06", TacticalHorizon.ROOT_TURNS_3_PLUS, TacticalCategory.SEQUENCING,
            "Warp now, recast later",
            "Nova Hellkite is in hand with three floating red and five tapped Mountains. The opponent is at 12; the complete libraries permit this combat and two more before your deck-out.",
            FINITE_CLOCK_FIXTURE, HorizonSetup.LONG_WARP_RECAST_CLOCK,
            action(SemanticOperationFamily.CAST_SPELL, "Nova Hellkite"),
            "Warp supplies the otherwise-missing first four-damage attack. Recasting from exile later completes three attacks for 12; waiting yields only two attacks before deck-out."),
        case("long-07", TacticalHorizon.ROOT_TURNS_3_PLUS, TacticalCategory.HOLD_OR_CAST,
            "Full cast, not Warp",
            "Nova Hellkite is in hand with five floating red from a removed source. The opponent has a tapped Nova Hellkite, is at 20, and the complete finite libraries make survival through three future root turns decisive.",
            FINITE_CLOCK_FIXTURE, HorizonSetup.FULL_CAST_NOT_WARP,
            TacticalHorizonExpectedAction(
                SemanticOperationFamily.CAST_SPELL,
                sourceName = "Nova Hellkite",
                usesAlternativeCost = false,
            ),
            "The normal cast remains on the battlefield to block the opposing Nova until that opponent decks out. A warped Nova is exiled in the current end step, after which the opposing flier attacks for lethal; passing loses the expiring mana and has the same result."),
    )

    fun validate() {
        require(cases.size == 28)
        require(cases.map { it.id }.distinct().size == 28)
        require(cases.groupingBy { it.horizon }.eachCount().values.all { it == 7 })
        require(cases.all {
            it.description.isNotBlank() && it.startingStateRationale.isNotBlank() &&
                it.terminalJustification.isNotBlank()
        })
    }

    private fun case(
        id: String,
        horizon: TacticalHorizon,
        category: TacticalCategory,
        title: String,
        description: String,
        rationale: String,
        setup: HorizonSetup,
        expectedAction: TacticalHorizonExpectedAction,
        terminalJustification: String,
    ) = TacticalHorizonCase(
        id, horizon, category, title, description, rationale, 72_000L + casesSeed++, setup,
        expectedAction, terminalJustification,
    )

    private fun action(
        family: SemanticOperationFamily,
        source: String? = null,
        targets: Set<String>? = null,
    ) = TacticalHorizonExpectedAction(family, source, targets)

    private fun castAtOpponent(source: String) = action(
        SemanticOperationFamily.CAST_SPELL, source, setOf("Player 1")
    )

    private fun assigned(
        family: SemanticOperationFamily,
        field: String,
        count: Int,
        targets: Set<String>,
    ) = TacticalHorizonExpectedAction(family, targetNames = targets, assignmentField = field, assignmentCount = count)

    private fun blockAssigned(count: Int, assignments: Map<String, String>) = TacticalHorizonExpectedAction(
        SemanticOperationFamily.DECLARE_BLOCKERS,
        assignmentField = "blockers",
        assignmentCount = count,
        requiredAssignments = assignments,
    )

    private var casesSeed = 0
    private const val FLOATING_FIXTURE =
        "The rules-exact state records already-floating mana and no untapped source, eliminating mana-prefix aliases."
    private const val STACK_FIXTURE =
        "The spell and priority passes that produced the stack were executed through the engine; only the response window is captured."
    private const val COMBAT_FIXTURE =
        "The listed attackers are declared through the engine from beginning of combat; the packet is captured at the exhaustive rules-exact combat decision."
    private const val COMBAT_PRIORITY_FIXTURE =
        "The packet is captured at a rules-exact priority window in combat; expiring mana and the turn's life-loss marker are explicit engine state."
    private const val FINITE_COMBAT_FIXTURE =
        "The combat history was executed through the engine. All remaining library cards are public, so the stated terminal clock is part of the position."
    private const val FINITE_CLOCK_FIXTURE =
        "Every remaining library card is public and all other deck cards are in public zones. The finite deck-out deadline is therefore rules-derived, not heuristic."
}

internal class TacticalHorizonScenarioFactory(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
) {
    private val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)

    fun create(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = when (case.setup) {
            HorizonSetup.DRAW_STEP_SHOCK_LADDER -> shockDraw(case)
            HorizonSetup.LETHAL_SHOCK_RESPONSE -> lethalShockResponse(case)
            HorizonSetup.HIRED_MAGEBANE_PASS -> hiredMagebanePass(case)
            HorizonSetup.HIRED_COUNTER_LETHAL -> hiredCounterLethal(case)
            HorizonSetup.BASHTRONAUT_PUMP_LETHAL -> bashtronautPumpLethal(case)
            HorizonSetup.OJER_SHOCK_LETHAL -> base(
                case,
                p0Hand = listOf("Shock"),
                p0Battlefield = listOf("Ojer Axonil, Deepest Might"),
                p1Battlefield = listOf("Nova Hellkite"),
                p0Tapped = listOf("Ojer Axonil, Deepest Might"),
                p1Tapped = listOf("Nova Hellkite"),
                p0Life = 4,
                p1Life = 4,
                floatingRed = 1,
                phase = Phase.POSTCOMBAT_MAIN,
                step = Step.POSTCOMBAT_MAIN,
            )
            HorizonSetup.ROCKFACE_HASTE_LETHAL -> rockfaceHasteLethal(case)
            HorizonSetup.HOWLSQUAD_CAST_BEFORE_COMBAT -> simpleMain(case,
                hand = listOf("Burnout Bashtronaut"), battlefield = listOf("Howlsquad Heavy"),
                p1Battlefield = listOf("Nova Hellkite"), p1Tapped = listOf("Nova Hellkite"),
                p0Life = 4, p1Life = 4, floatingRed = 1)
            HorizonSetup.WARP_NOVA_THIS_TURN -> simpleMain(case,
                hand = listOf("Nova Hellkite", "Shock"),
                p1Battlefield = listOf("Burnout Bashtronaut", "Nova Hellkite"),
                p1Tapped = listOf("Nova Hellkite"), p0Life = 4, p1Life = 4, floatingRed = 3)
            HorizonSetup.SUNSPINE_BASIC_LAND_ORDER -> simpleMain(case,
                hand = listOf("Mountain", "Soulstone Sanctuary", "Sunspine Lynx"),
                battlefield = List(3) { "Mountain" }, p0Tapped = List(3) { "Mountain" },
                p1Battlefield = listOf("Rockface Village", "Soulstone Sanctuary", "Nova Hellkite"),
                p1Tapped = listOf("Nova Hellkite"), p0Life = 1, p1Life = 2, floatingRed = 3)
            HorizonSetup.EXACT_TRIGGER_ATTACK -> attackState(case,
                attackers = listOf("Hired Claw", "Hexing Squelcher"), p0Life = 4, p1Life = 4,
                p1Battlefield = listOf("Nova Hellkite"), p1Tapped = listOf("Nova Hellkite"))
            HorizonSetup.NOVA_CLEAR_EXACT_BLOCKER -> novaClearExactBlocker(case)
            HorizonSetup.CHUMP_PRESERVE_CRACKBACK -> blockState(case,
                attackers = listOf("Sunspine Lynx", "Hexing Squelcher"),
                blockers = listOf("Hired Claw", "Nova Hellkite"), p0Life = 2, p1Life = 4,
                p0LibraryTop = listOf("Mountain"), p1LibraryTop = listOf("Mountain", "Mountain"),
                publiclyDepleteDecks = true)
            HorizonSetup.END_STEP_SHOCK_STANDOFF -> shockEndStep(case)
            HorizonSetup.TAKE_LYNX_PRESERVE_ATTACKER -> blockState(case,
                attackers = listOf("Sunspine Lynx"), blockers = listOf("Hexing Squelcher"),
                p0Life = 6, p1Life = 2, p0LibraryTop = listOf("Mountain"),
                p1LibraryTop = listOf("Mountain", "Mountain"), publiclyDepleteDecks = true)
            HorizonSetup.END_STEP_HIRED_COUNTER -> endStepHiredCounter(case)
            HorizonSetup.NOVA_KILL_MARKED_FLIER -> novaKillMarkedFlier(case)
            HorizonSetup.END_STEP_SHOCK_RAZORKIN -> endStepShockRazorkin(case)
            HorizonSetup.LONG_HIRED_COUNTER_CLOCK -> simpleMain(case,
                battlefield = listOf("Hired Claw"), p0Life = 10, p1Life = 12, floatingRed = 2,
                opponentLostLife = true, p0LibraryTop = List(3) { "Mountain" },
                p1LibraryTop = List(4) { "Mountain" }, publiclyDepleteDecks = true)
            HorizonSetup.LONG_HOWLSQUAD_TOKEN_CLOCK -> simpleMain(case,
                hand = listOf("Howlsquad Heavy"), p0Life = 10, p1Life = 16, floatingRed = 3,
                p0LibraryTop = List(3) { "Mountain" }, p1LibraryTop = List(4) { "Mountain" },
                publiclyDepleteDecks = true)
            HorizonSetup.LONG_RAZORKIN_DRAW_CLOCK -> simpleMain(case,
                hand = listOf("Razorkin Needlehead"), p0Life = 10, p1Life = 3, floatingRed = 2,
                p0LibraryTop = List(2) { "Mountain" }, p1LibraryTop = List(3) { "Mountain" },
                publiclyDepleteDecks = true)
            HorizonSetup.LONG_SOULSTONE_CLOCK -> simpleMain(case,
                battlefield = listOf("Soulstone Sanctuary"), p0Life = 10, p1Life = 9,
                floatingColorless = 4, p0LibraryTop = List(2) { "Mountain" },
                p1LibraryTop = List(3) { "Mountain" }, publiclyDepleteDecks = true)
            HorizonSetup.LONG_PRESERVE_RAZORKIN_BLOCK -> blockState(case,
                attackers = listOf("Hexing Squelcher"),
                blockers = listOf("Magebane Lizard", "Razorkin Needlehead"),
                p0Life = 6, p1Life = 3, p1BattlefieldExtra = listOf("Sunspine Lynx"),
                p0LibraryTop = List(3) { "Mountain" },
                p1LibraryTop = List(4) { "Mountain" }, publiclyDepleteDecks = true)
            HorizonSetup.LONG_WARP_RECAST_CLOCK -> simpleMain(case,
                hand = listOf("Nova Hellkite"), battlefield = List(5) { "Mountain" },
                p0Tapped = List(5) { "Mountain" }, p1Battlefield = listOf("Burnout Bashtronaut"),
                p0Life = 10, p1Life = 12, floatingRed = 3,
                p0LibraryTop = List(2) { "Mountain" }, p1LibraryTop = List(3) { "Mountain" },
                publiclyDepleteDecks = true)
            HorizonSetup.FLYING_EXACT_BLOCKS -> blockState(case,
                attackers = listOf("Nova Hellkite", "Sunspine Lynx"),
                blockers = listOf("Nova Hellkite", "Magebane Lizard"),
                p0Life = 4, p1Life = 4, p0LibraryTop = listOf("Mountain"),
                p1LibraryTop = listOf("Mountain", "Mountain"), publiclyDepleteDecks = true)
            HorizonSetup.COLORLESS_LAND_RED_REQUIREMENT -> simpleMain(case,
                hand = listOf("Mountain", "Soulstone Sanctuary", "Lightning Strike"),
                p1Battlefield = listOf("Nova Hellkite"), p1Tapped = listOf("Nova Hellkite"),
                p0Life = 4, p1Life = 3, floatingColorless = 1)
            HorizonSetup.SOULSTONE_FLOATING_BLOCK -> soulstoneFloatingBlock(case)
            HorizonSetup.FULL_CAST_NOT_WARP -> simpleMain(case,
                hand = listOf("Nova Hellkite"), p1Battlefield = listOf("Nova Hellkite"),
                p1Tapped = listOf("Nova Hellkite"), p0Life = 4, p1Life = 20,
                floatingRed = 5, p0LibraryTop = List(4) { "Mountain" },
                p1LibraryTop = List(3) { "Mountain" }, publiclyDepleteDecks = true)
        }
        return world.withActionSpaceProfile(actionSpaceProfile)
    }

    private fun simpleMain(
        case: TacticalHorizonCase,
        hand: List<String> = emptyList(),
        battlefield: List<String> = emptyList(),
        p0Tapped: List<String> = emptyList(),
        p1Battlefield: List<String> = emptyList(),
        p1Tapped: List<String> = emptyList(),
        p0Life: Int,
        p1Life: Int,
        floatingRed: Int = 0,
        floatingColorless: Int = 0,
        opponentLostLife: Boolean = false,
        p0LibraryTop: List<String> = emptyList(),
        p1LibraryTop: List<String> = emptyList(),
        publiclyDepleteDecks: Boolean = false,
    ) = base(
        case, p0Hand = hand, p0LibraryTop = p0LibraryTop, p1LibraryTop = p1LibraryTop,
        p0Battlefield = battlefield, p1Battlefield = p1Battlefield,
        p0Tapped = p0Tapped, p1Tapped = p1Tapped, p0Life = p0Life, p1Life = p1Life,
        floatingRed = floatingRed, floatingColorless = floatingColorless,
        p1LostLifeThisTurn = opponentLostLife,
        phase = Phase.PRECOMBAT_MAIN, step = Step.PRECOMBAT_MAIN,
        publiclyDepleteDecks = publiclyDepleteDecks,
    )

    private fun shockDraw(case: TacticalHorizonCase): ArgentumSearchWorld = base(
        case, p0Hand = listOf("Shock", "Shock"), p1Hand = listOf("Shock"),
        p0Battlefield = listOf("Mountain", "Mountain"), p1Battlefield = listOf("Mountain", "Mountain"),
        p0Tapped = listOf("Mountain", "Mountain"), p0Life = 2, p1Life = 2, floatingRed = 2,
        phase = Phase.BEGINNING, step = Step.DRAW, publiclyDepleteDecks = true,
    )

    private fun lethalShockResponse(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(case, p0Hand = listOf("Shock"), p1Hand = listOf("Shock"),
            p1Battlefield = listOf("Mountain"), p0Life = 2, p1Life = 2, floatingRed = 1,
            phase = Phase.PRECOMBAT_MAIN, step = Step.PRECOMBAT_MAIN, priorityPlayer = "p1")
        stepMatching(world, "opponent floats red", raw = true) {
            it.operationFamily == SemanticOperationFamily.MANA_ABILITY
        }
        stepMatching(world, "opponent casts lethal Shock", raw = true) {
            it.operationFamily == SemanticOperationFamily.CAST_SPELL &&
                it.display.sourceName == "Shock" && "Player 0" in it.display.targetNames
        }
        stepMatching(world, "opponent passes above Shock", raw = true) {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        }
        return world
    }

    private fun shockEndStep(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(
            case, p0Hand = listOf("Shock"), p1Hand = listOf("Shock"),
            p0LibraryTop = listOf("Shock"), p0Battlefield = listOf("Mountain", "Mountain"),
            p1Battlefield = listOf("Mountain", "Mountain"), p0Life = 2, p1Life = 2,
            p0Tapped = listOf("Mountain", "Mountain"), floatingRed = 1,
            phase = Phase.ENDING, step = Step.END, activePlayer = "p1", priorityPlayer = "p1",
            publiclyDepleteDecks = true,
        )
        stepMatching(world, "opponent end-step pass") { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
        return world
    }

    private fun hiredMagebanePass(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(case, p0Hand = listOf("Shock"),
            p0Battlefield = listOf("Hired Claw", "Mountain"),
            p1Battlefield = listOf("Magebane Lizard"), p0Life = 1, p1Life = 1,
            phase = Phase.COMBAT, step = Step.BEGIN_COMBAT)
        advanceToFamily(world, "p0", SemanticOperationFamily.DECLARE_ATTACKERS)
        stepMatching(world, "attack with Hired Claw", raw = true) {
            it.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS && it.display.targetNames.isNotEmpty()
        }
        stepMatching(world, "float red above Hired trigger", raw = true) {
            it.operationFamily == SemanticOperationFamily.MANA_ABILITY
        }
        return world
    }

    private fun hiredCounterLethal(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(case, p0Battlefield = listOf("Hired Claw"),
            p1Battlefield = listOf("Nova Hellkite"), p1Tapped = listOf("Nova Hellkite"),
            p0Life = 4, p1Life = 3, floatingRed = 2, p1LostLifeThisTurn = true,
            phase = Phase.COMBAT, step = Step.BEGIN_COMBAT)
        advanceToFamily(world, "p0", SemanticOperationFamily.ACTIVATE_ABILITY)
        return world
    }

    private fun bashtronautPumpLethal(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(case, p0Battlefield = listOf("Burnout Bashtronaut"),
            p1Battlefield = listOf("Nova Hellkite"), p1Tapped = listOf("Nova Hellkite"),
            p0Life = 4, p1Life = 3, floatingRed = 2, p0Speed = 4,
            phase = Phase.COMBAT, step = Step.BEGIN_COMBAT)
        advanceToFamily(world, "p0", SemanticOperationFamily.ACTIVATE_ABILITY)
        return world
    }

    private fun rockfaceHasteLethal(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(case, p0Hand = listOf("Hired Claw"),
            p0Battlefield = listOf("Rockface Village"), p1Battlefield = listOf("Nova Hellkite"),
            p1Tapped = listOf("Nova Hellkite"), p0Life = 4, p1Life = 2, floatingRed = 2,
            phase = Phase.PRECOMBAT_MAIN, step = Step.PRECOMBAT_MAIN)
        stepMatching(world, "cast Hired Claw") {
            it.operationFamily == SemanticOperationFamily.CAST_SPELL && it.display.sourceName == "Hired Claw"
        }
        advanceToFamily(world, "p0", SemanticOperationFamily.ACTIVATE_ABILITY)
        return world
    }

    private fun novaClearExactBlocker(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(case, p0Hand = listOf("Nova Hellkite"),
            p0Battlefield = listOf("Hired Claw", "Hexing Squelcher"),
            p1Battlefield = listOf("Burnout Bashtronaut", "Magebane Lizard", "Nova Hellkite"),
            p1Tapped = listOf("Magebane Lizard", "Nova Hellkite"),
            p0Life = 4, p1Life = 8, floatingRed = 5,
            phase = Phase.PRECOMBAT_MAIN, step = Step.PRECOMBAT_MAIN)
        stepMatching(world, "cast Nova Hellkite") {
            it.operationFamily == SemanticOperationFamily.CAST_SPELL &&
                it.display.sourceName == "Nova Hellkite" &&
                !it.canonicalPayload.toString().contains("Warp", ignoreCase = true)
        }
        advanceToFamily(world, "p0", SemanticOperationFamily.DECISION_RESPONSE)
        return world
    }

    private fun endStepHiredCounter(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(case, p0Battlefield = listOf("Hired Claw"),
            p1Battlefield = listOf("Nova Hellkite"), p1Tapped = listOf("Nova Hellkite"),
            p0Life = 4, p1Life = 3, floatingRed = 2, p1LostLifeThisTurn = true,
            phase = Phase.ENDING, step = Step.END, activePlayer = "p1", priorityPlayer = "p1")
        stepMatching(world, "opponent end-step pass") { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
        return world
    }

    private fun novaKillMarkedFlier(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(case, p0Hand = listOf("Nova Hellkite"),
            p0LibraryTop = listOf("Mountain"), p1LibraryTop = listOf("Mountain", "Mountain"),
            p1Battlefield = listOf("Nova Hellkite", "Magebane Lizard"),
            p1MarkedDamage = listOf("Nova Hellkite" to 4), p0Life = 1, p1Life = 4,
            floatingRed = 5, phase = Phase.POSTCOMBAT_MAIN, step = Step.POSTCOMBAT_MAIN,
            publiclyDepleteDecks = true)
        stepMatching(world, "cast Nova Hellkite") {
            it.operationFamily == SemanticOperationFamily.CAST_SPELL &&
                it.display.sourceName == "Nova Hellkite" &&
                !it.canonicalPayload.toString().contains("Warp", ignoreCase = true)
        }
        advanceToFamily(world, "p0", SemanticOperationFamily.DECISION_RESPONSE)
        return world
    }

    private fun endStepShockRazorkin(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(case, p0Hand = listOf("Shock"),
            p0Battlefield = listOf("Hired Claw", "Hexing Squelcher"),
            p1Battlefield = listOf("Razorkin Needlehead"), p0Life = 1, p1Life = 3,
            floatingRed = 1, p0LibraryTop = listOf("Mountain"), p1LibraryTop = listOf("Mountain", "Mountain"),
            phase = Phase.ENDING, step = Step.END, activePlayer = "p1", priorityPlayer = "p1",
            publiclyDepleteDecks = true)
        stepMatching(world, "opponent end-step pass") { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
        return world
    }

    private fun soulstoneFloatingBlock(case: TacticalHorizonCase): ArgentumSearchWorld {
        val world = base(
            case,
            p0Battlefield = listOf("Soulstone Sanctuary"),
            p1Battlefield = listOf("Sunspine Lynx"),
            p0Life = 5,
            p1Life = 20,
            floatingColorless = 4,
            p0LibraryTop = listOf("Mountain"),
            p1LibraryTop = emptyList(),
            phase = Phase.COMBAT,
            step = Step.BEGIN_COMBAT,
            activePlayer = "p1",
            priorityPlayer = "p1",
            publiclyDepleteDecks = true,
        )
        stepMatching(world, "opponent beginning-combat pass") {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        }
        return world
    }

    private fun blockState(
        case: TacticalHorizonCase,
        attackers: List<String>,
        blockers: List<String>,
        p0Life: Int,
        p1Life: Int,
        p1BattlefieldExtra: List<String> = emptyList(),
        p1ExtraTapped: List<String> = emptyList(),
        p0LibraryTop: List<String> = emptyList(),
        p1LibraryTop: List<String> = emptyList(),
        publiclyDepleteDecks: Boolean = false,
    ): ArgentumSearchWorld {
        val world = base(case, p0Battlefield = blockers,
            p0LibraryTop = p0LibraryTop, p1LibraryTop = p1LibraryTop,
            p1Battlefield = attackers + p1BattlefieldExtra, p0Life = p0Life, p1Life = p1Life,
            p1Tapped = p1ExtraTapped,
            phase = Phase.COMBAT, step = Step.BEGIN_COMBAT, activePlayer = "p1", priorityPlayer = "p1",
            publiclyDepleteDecks = publiclyDepleteDecks)
        advanceToFamily(world, "p1", SemanticOperationFamily.DECLARE_ATTACKERS)
        val attackerInformation = world.informationState("p1")
        stepMatching(world, "declare listed attackers") { choice ->
            choice.operationFamily == SemanticOperationFamily.DECLARE_ATTACKERS &&
                choice.assignmentObjectNames("attackers", attackerInformation).groupingBy { it }.eachCount() ==
                    attackers.groupingBy { it }.eachCount()
        }
        advanceToFamily(world, "p0", SemanticOperationFamily.DECLARE_BLOCKERS)
        return world
    }

    private fun attackState(
        case: TacticalHorizonCase,
        attackers: List<String>,
        p0Life: Int,
        p1Life: Int,
        p1Battlefield: List<String>,
        p1Tapped: List<String> = emptyList(),
    ): ArgentumSearchWorld {
        val world = base(case, p0Battlefield = attackers, p1Battlefield = p1Battlefield,
            p1Tapped = p1Tapped, p0Life = p0Life, p1Life = p1Life,
            phase = Phase.COMBAT, step = Step.BEGIN_COMBAT)
        advanceToFamily(world, "p0", SemanticOperationFamily.DECLARE_ATTACKERS)
        return world
    }

    private fun base(
        case: TacticalHorizonCase,
        p0Hand: List<String> = emptyList(),
        p1Hand: List<String> = emptyList(),
        p0LibraryTop: List<String> = emptyList(),
        p1LibraryTop: List<String> = emptyList(),
        p0Battlefield: List<String> = emptyList(),
        p1Battlefield: List<String> = emptyList(),
        p0Tapped: List<String> = emptyList(),
        p1Tapped: List<String> = emptyList(),
        p0MarkedDamage: List<Pair<String, Int>> = emptyList(),
        p1MarkedDamage: List<Pair<String, Int>> = emptyList(),
        p0Life: Int,
        p1Life: Int,
        floatingRed: Int = 0,
        floatingColorless: Int = 0,
        p0Speed: Int = 0,
        p1LostLifeThisTurn: Boolean = false,
        phase: Phase,
        step: Step,
        activePlayer: String = "p0",
        priorityPlayer: String = activePlayer,
        publiclyDepleteDecks: Boolean = false,
    ): ArgentumSearchWorld {
        val environment = GameEnvironment.create(registry)
        environment.reset(GameConfig(
            players = listOf(PlayerConfig("Player 0", manifest.deck()), PlayerConfig("Player 1", manifest.deck())),
            seed = case.rootSeed, startingPlayerIndex = 0, skipMulligans = true,
        ))
        val ids = environment.playerIds
        var state = arrangePlayer(
            environment.state, ids[0], p0Hand, p0LibraryTop, p0Battlefield, p0Tapped,
            p0MarkedDamage, p0Life, publiclyDepleteDecks,
        )
        state = arrangePlayer(
            state, ids[1], p1Hand, p1LibraryTop, p1Battlefield, p1Tapped,
            p1MarkedDamage, p1Life, publiclyDepleteDecks,
        )
        state = state.updateEntity(ids[0]) { container ->
            var updated = if (floatingRed > 0 || floatingColorless > 0) {
                container.with(ManaPoolComponent(red = floatingRed, colorless = floatingColorless))
            } else {
                container
            }
            if (p0Speed > 0) updated = updated.with(PlayerSpeedComponent(p0Speed))
            updated
        }
        if (p1LostLifeThisTurn) state = state.updateEntity(ids[1]) { it.with(LifeLostThisTurnComponent) }
        val activeId = if (activePlayer == "p0") ids[0] else ids[1]
        val priorityId = if (priorityPlayer == "p0") ids[0] else ids[1]
        state = state.copy(
            initialSeed = case.rootSeed, phase = phase, step = step, activePlayerId = activeId,
            priorityPlayerId = priorityId, priorityPassedBy = emptySet(), stack = emptyList(),
            pendingDecision = null, continuationStack = emptyList(), winnerId = null, gameOver = false,
        )
        environment.restore(state, ids)
        return ArgentumSearchWorld.create(environment, case.id, case.rootSeed, cardRegistry = registry, knownDecks = knownDecks)
    }

    private fun arrangePlayer(
        original: GameState,
        playerId: EntityId,
        handNames: List<String>,
        libraryTopNames: List<String>,
        battlefieldNames: List<String>,
        tappedNames: List<String>,
        markedDamage: List<Pair<String, Int>>,
        life: Int,
        publiclyDeplete: Boolean,
    ): GameState {
        val owned = Zone.entries.flatMap { original.getZone(playerId, it) }.distinct()
        val available = owned.groupBy { entityId -> requireNotNull(original.getEntity(entityId)?.get<CardComponent>()).name }
            .mapValues { (_, ids) -> ids.sortedBy(EntityId::value).toMutableList() }
        fun take(names: List<String>): List<EntityId> = names.map { name ->
            val values = requireNotNull(available[name]) { "No $name in frozen deck" }
            require(values.isNotEmpty()) { "Scenario overuses $name" }
            values.removeAt(0)
        }
        val hand = take(handNames)
        val top = take(libraryTopNames)
        val battlefield = take(battlefieldNames)
        val remaining = available.toSortedMap().values.flatten()
        val library = if (publiclyDeplete) top else top + remaining
        val graveyard = if (publiclyDeplete) remaining else emptyList()
        val zones = original.zones + Zone.entries.associate { zone -> ZoneKey(playerId, zone) to emptyList() }
        var state = original.copy(zones = zones + mapOf(
            ZoneKey(playerId, Zone.HAND) to hand,
            ZoneKey(playerId, Zone.BATTLEFIELD) to battlefield,
            ZoneKey(playerId, Zone.LIBRARY) to library,
            ZoneKey(playerId, Zone.GRAVEYARD) to graveyard,
        ))
        val tapped = tappedNames.groupingBy { it }.eachCount().toMutableMap()
        val marked = markedDamage.groupBy({ it.first }, { it.second })
            .mapValues { (_, damage) -> damage.toMutableList() }
            .toMutableMap()
        val staticHandler = StaticAbilityHandler(registry)
        battlefield.forEach { id ->
            val card = requireNotNull(state.getEntity(id)?.get<CardComponent>())
            val definition = registry.requireCard(card.name)
            val shouldTap = tapped.getOrDefault(card.name, 0) > 0
            if (shouldTap) tapped[card.name] = tapped.getValue(card.name) - 1
            val damageMarked = marked[card.name]?.removeFirstOrNull() ?: 0
            state = state.updateEntity(id) { container ->
                var updated = staticHandler.addReplacementEffectComponent(
                    staticHandler.addContinuousEffectComponent(
                        container.with(ControllerComponent(playerId)).without<SummoningSicknessComponent>(), definition,
                    ), definition,
                )
                updated = if (shouldTap) updated.with(TappedComponent) else updated.without<TappedComponent>()
                updated = if (damageMarked > 0) updated.with(DamageComponent(damageMarked)) else updated.without<DamageComponent>()
                updated
            }
        }
        require(tapped.values.all { it == 0 })
        require(marked.values.all { it.isEmpty() })
        return state.updateEntity(playerId) { it.with(LifeTotalComponent(life)) }
    }

    private fun advanceToFamily(world: ArgentumSearchWorld, actor: String, family: SemanticOperationFamily) {
        repeat(64) {
            val expansion = world.expandChoices(2_048)
            if (world.actorToAct() == actor && expansion.candidates.any { it.operationFamily == family }) return
            val pass = expansion.candidates.singleOrNull { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
                ?: error(
                    "${world.actorToAct()} branched before $actor/$family: " +
                        expansion.candidates.groupingBy { it.operationFamily }.eachCount()
                )
            require(world.step(pass).accepted)
        }
        error("Did not reach $actor/$family")
    }

    private fun stepMatching(
        world: ArgentumSearchWorld,
        description: String,
        raw: Boolean = false,
        predicate: (SemanticChoice) -> Boolean,
    ) {
        val expansion = world.expandChoices(2_048)
        require(expansion.isExhaustive) { "$description requires exhaustive actions" }
        val matches = expansion.candidates.filter(predicate)
        require(matches.size == 1) {
            "$description matched ${matches.size}; actor=${world.actorToAct()} " +
                "families=${expansion.candidates.groupingBy { it.operationFamily }.eachCount()} " +
                "labels=${matches.take(8).map { it.display.label }}"
        }
        val result = if (raw) {
            when (val resolved = world.resolveChoice(matches.single())) {
                is ArgentumResolvedChoice.Action -> world.applyObservedAction(resolved.value).result
                is ArgentumResolvedChoice.Decision -> error("$description unexpectedly resolved to a decision")
            }
        } else world.step(matches.single())
        require(result.accepted) { "Engine rejected $description" }
    }

}

private fun SemanticChoice.assignmentCount(field: String): Int = canonicalPayload["body"]
    ?.jsonObject?.get(field)?.jsonObject?.size ?: -1

private fun SemanticChoice.assignmentObjectNames(
    field: String,
    information: org.mtgallium.agent.infoset.core.PolicyInformationState,
): List<String> = canonicalPayload["body"]?.jsonObject?.get(field)?.jsonObject
    ?.keys?.map(information::semanticName)
    ?: emptyList()

private fun SemanticChoice.selectedTargetNames(
    information: org.mtgallium.agent.infoset.core.PolicyInformationState,
): Set<String> {
    if (display.targetNames.isNotEmpty()) return display.targetNames.toSet()
    val selected = canonicalPayload["body"]?.jsonObject?.get("selectedTargets")?.jsonObject
        ?.values?.flatMap { group -> group.jsonArray.map { it.jsonPrimitive.content } }
        ?: return emptySet()
    return selected.map(information::semanticName).toSet()
}

private fun SemanticChoice.hasAssignments(
    information: org.mtgallium.agent.infoset.core.PolicyInformationState,
    required: Map<String, String>,
): Boolean {
    val actual = canonicalPayload["body"]?.jsonObject?.get("blockers")?.jsonObject
        ?.flatMap { (blocker, attackers) ->
            attackers.jsonArray.map { attacker ->
                information.semanticName(blocker) to information.semanticName(attacker.jsonPrimitive.content)
            }
        }
        ?.toSet()
        ?: return false
    return required.all { (blocker, attacker) -> blocker to attacker in actual }
}

private fun org.mtgallium.agent.infoset.core.PolicyInformationState.semanticName(reference: String): String {
    if (reference.startsWith("player:")) {
        val playerId = reference.substringAfter("player:").substringBefore(':')
        return observation.players.single { it.playerId == playerId }.name
    }
    val descriptorPrefix = reference.split(':').getOrNull(3)?.take(16)
        ?: error("Not a semantic object reference: $reference")
    return observation.zones.asSequence().flatMap { it.cards.asSequence() }
        .single { it.objectRef.split(':').getOrNull(3) == descriptorPrefix }
        .name
}
