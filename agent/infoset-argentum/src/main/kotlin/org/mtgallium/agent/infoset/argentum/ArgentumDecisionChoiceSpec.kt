package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.BatchYesNoDecision
import com.wingedsheep.engine.core.BudgetModalDecision
import com.wingedsheep.engine.core.BudgetModeOption
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseReplacementDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.ConditionalSelectionMinimum
import com.wingedsheep.engine.core.DamageEdge
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.OptionMetadata
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.ResolutionAttacker
import com.wingedsheep.engine.core.ResolutionBlocker
import com.wingedsheep.engine.core.ResolutionDefender
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.WaterbendPermanentChoice
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MTGallium-owned description of the payload a policy may submit for one pending decision.
 *
 * Argentum owns the live [PendingDecision] and validates every response. This representation owns
 * only the chooser-safe information and proposal surface required by MTGallium; keeping it here
 * avoids making the generic Gym observation a policy/search authority.
 */
@Serializable
internal sealed interface DecisionChoiceSpec

@Serializable @SerialName("Targets")
internal data class TargetsChoiceSpec(
    val requirements: List<TargetRequirementInfo>,
    val legalTargets: Map<Int, List<EntityId>>,
    val canCancel: Boolean,
) : DecisionChoiceSpec

@Serializable @SerialName("Cards")
internal data class CardsChoiceSpec(
    val options: List<EntityId>,
    val minSelections: Int,
    val maxSelections: Int,
    val ordered: Boolean,
    val cardInfo: Map<EntityId, SearchCardInfo>? = null,
    val useTargetingUI: Boolean = false,
    val selectedLabel: String? = null,
    val remainderLabel: String? = null,
    val nonSelectableOptions: List<EntityId> = emptyList(),
    val onePerCardType: Boolean = false,
    val onePerColor: Boolean = false,
    val availableColors: List<String>? = null,
    val onePerCardName: Boolean = false,
    val onePerBasicLandType: Boolean = false,
    val onePerPower: Boolean = false,
    val maxTotalManaValue: Int? = null,
    val minTotalManaValue: Int? = null,
    val maxTotalPower: Int? = null,
    val conditionalMinimums: List<ConditionalSelectionMinimum> = emptyList(),
) : DecisionChoiceSpec

@Serializable @SerialName("YesNo")
internal data class YesNoChoiceSpec(val yesText: String, val noText: String, val hint: String? = null) : DecisionChoiceSpec

@Serializable @SerialName("BatchYesNo")
internal data class BatchYesNoChoiceSpec(val count: Int, val yesText: String, val noText: String) : DecisionChoiceSpec

@Serializable @SerialName("Modes")
internal data class ModesChoiceSpec(val modes: List<ModeOption>, val minModes: Int, val maxModes: Int) : DecisionChoiceSpec

@Serializable @SerialName("Colors")
internal data class ColorsChoiceSpec(val colors: List<Color>) : DecisionChoiceSpec

@Serializable @SerialName("Number")
internal data class NumberChoiceSpec(val minValue: Int, val maxValue: Int) : DecisionChoiceSpec

@Serializable @SerialName("Distribution")
internal data class DistributionChoiceSpec(
    val totalAmount: Int,
    val targets: List<EntityId>,
    val minPerTarget: Int,
    val maxPerTarget: Map<EntityId, Int>,
    val allowPartial: Boolean,
) : DecisionChoiceSpec

@Serializable @SerialName("Order")
internal data class OrderChoiceSpec(
    val objects: List<EntityId>,
    val cardInfo: Map<EntityId, SearchCardInfo>? = null,
) : DecisionChoiceSpec

@Serializable @SerialName("Piles")
internal data class PilesChoiceSpec(
    val cards: List<EntityId>,
    val numberOfPiles: Int,
    val pileLabels: List<String>,
    val cardInfo: Map<EntityId, SearchCardInfo>? = null,
) : DecisionChoiceSpec

@Serializable @SerialName("Options")
internal data class OptionsChoiceSpec(
    val options: List<String>,
    val defaultSearch: String? = null,
    val optionCardIds: Map<Int, List<EntityId>>? = null,
    val optionMetadata: List<OptionMetadata> = emptyList(),
    val canCancel: Boolean = false,
) : DecisionChoiceSpec

@Serializable @SerialName("Replacement")
internal data class ReplacementChoiceSpec(
    val fromOptions: List<String>,
    val toOptions: List<String>,
    val fromMetadata: List<OptionMetadata>,
    val toMetadata: List<OptionMetadata>,
    val allowedToByFrom: List<List<Int>>,
    val defaultFromIndex: Int? = null,
) : DecisionChoiceSpec

@Serializable @SerialName("LibrarySearch")
internal data class LibrarySearchChoiceSpec(
    val options: List<EntityId>,
    val minSelections: Int,
    val maxSelections: Int,
    val cards: Map<EntityId, SearchCardInfo>,
    val filterDescription: String,
) : DecisionChoiceSpec

@Serializable @SerialName("LibraryReorder")
internal data class LibraryReorderChoiceSpec(
    val cards: List<EntityId>,
    val cardInfo: Map<EntityId, SearchCardInfo>,
) : DecisionChoiceSpec

@Serializable @SerialName("DamageAssignment")
internal data class DamageAssignmentChoiceSpec(
    val attackerId: EntityId,
    val availablePower: Int,
    val orderedTargets: List<EntityId>,
    val defenderId: EntityId?,
    val minimumAssignments: Map<EntityId, Int>,
    val defaultAssignments: Map<EntityId, Int>,
    val hasTrample: Boolean,
    val hasDeathtouch: Boolean,
) : DecisionChoiceSpec

@Serializable @SerialName("CombatResolution")
internal data class CombatResolutionChoiceSpec(
    val firstStrike: Boolean,
    val attackers: List<ResolutionAttacker>,
    val blockers: List<ResolutionBlocker>,
    val defenders: List<ResolutionDefender>,
    val edges: List<DamageEdge>,
    val coChooserId: EntityId? = null,
) : DecisionChoiceSpec

@Serializable @SerialName("ManaSources")
internal data class ManaSourcesChoiceSpec(
    val availableSources: List<ManaSourceChoice>,
    val requiredCost: String,
    val autoPaySuggestion: List<EntityId>,
    val canDecline: Boolean,
    val waterbendPermanents: List<WaterbendPermanentChoice>,
) : DecisionChoiceSpec

@Serializable
internal data class ManaSourceChoice(
    val entityId: EntityId,
    val name: String,
    val producesColors: List<Color>,
    val producesColorless: Boolean,
    val requiresSacrifice: Boolean,
    val requiresTappingAnotherPermanent: Boolean,
)

@Serializable @SerialName("BudgetModes")
internal data class BudgetModesChoiceSpec(
    val budget: Int,
    val modes: List<BudgetModeOption>,
) : DecisionChoiceSpec

/** Exhaustive sealed-family projection: a new decision cannot silently lack a policy contract. */
internal fun PendingDecision.toDecisionChoiceSpec(): DecisionChoiceSpec = when (this) {
    is ChooseTargetsDecision -> TargetsChoiceSpec(targetRequirements, legalTargets, canCancel)
    is SelectCardsDecision -> CardsChoiceSpec(
        options, minSelections, maxSelections, ordered, cardInfo, useTargetingUI, selectedLabel,
        remainderLabel, nonSelectableOptions, onePerCardType, onePerColor, availableColors,
        onePerCardName, onePerBasicLandType, onePerPower, maxTotalManaValue, minTotalManaValue,
        maxTotalPower, conditionalMinimums,
    )
    is YesNoDecision -> YesNoChoiceSpec(yesText, noText, hint)
    is BatchYesNoDecision -> BatchYesNoChoiceSpec(count, yesText, noText)
    is ChooseModeDecision -> ModesChoiceSpec(modes, minModes, maxModes)
    is ChooseColorDecision -> ColorsChoiceSpec(availableColors.sortedBy { it.name })
    is ChooseNumberDecision -> NumberChoiceSpec(minValue, maxValue)
    is DistributeDecision -> DistributionChoiceSpec(totalAmount, targets, minPerTarget, maxPerTarget, allowPartial)
    is OrderObjectsDecision -> OrderChoiceSpec(objects, cardInfo)
    is SplitPilesDecision -> PilesChoiceSpec(cards, numberOfPiles, pileLabels, cardInfo)
    is ChooseOptionDecision -> OptionsChoiceSpec(options, defaultSearch, optionCardIds, optionMetadata, canCancel)
    is ChooseReplacementDecision -> ReplacementChoiceSpec(
        fromOptions, toOptions, fromMetadata, toMetadata, allowedToByFrom, defaultFromIndex,
    )
    is SearchLibraryDecision -> LibrarySearchChoiceSpec(
        options, minSelections, maxSelections, cards, filterDescription,
    )
    is ReorderLibraryDecision -> LibraryReorderChoiceSpec(cards, cardInfo)
    is AssignDamageDecision -> DamageAssignmentChoiceSpec(
        attackerId, availablePower, orderedTargets, defenderId, minimumAssignments,
        defaultAssignments, hasTrample, hasDeathtouch,
    )
    is CombatResolutionDecision -> CombatResolutionChoiceSpec(
        firstStrike, attackers, blockers, defenders, edges, coChooserId,
    )
    is SelectManaSourcesDecision -> ManaSourcesChoiceSpec(
        availableSources.map { source ->
            ManaSourceChoice(
                source.entityId,
                source.name,
                source.producesColors.sortedBy { it.name },
                source.producesColorless,
                source.requiresSacrifice,
                source.requiresTappingAnotherPermanent,
            )
        },
        requiredCost,
        autoPaySuggestion,
        canDecline,
        waterbendPermanents,
    )
    is BudgetModalDecision -> BudgetModesChoiceSpec(budget, modes)
}

internal fun PendingDecision.policyKindName(): String = when (this) {
    is ChooseTargetsDecision -> "CHOOSE_TARGETS"
    is SelectCardsDecision -> "SELECT_CARDS"
    is YesNoDecision -> "YES_NO"
    is BatchYesNoDecision -> "BATCH_YES_NO"
    is ChooseModeDecision -> "CHOOSE_MODE"
    is ChooseColorDecision -> "CHOOSE_COLOR"
    is ChooseNumberDecision -> "CHOOSE_NUMBER"
    is DistributeDecision -> "DISTRIBUTE"
    is OrderObjectsDecision -> "ORDER_OBJECTS"
    is SplitPilesDecision -> "SPLIT_PILES"
    is ChooseOptionDecision -> "CHOOSE_OPTION"
    is ChooseReplacementDecision -> "CHOOSE_REPLACEMENT"
    is SearchLibraryDecision -> "SEARCH_LIBRARY"
    is ReorderLibraryDecision -> "REORDER_LIBRARY"
    is AssignDamageDecision -> "ASSIGN_DAMAGE"
    is CombatResolutionDecision -> "COMBAT_RESOLUTION"
    is SelectManaSourcesDecision -> "SELECT_MANA_SOURCES"
    is BudgetModalDecision -> "BUDGET_MODAL"
}

/** Every entity reference the chooser was explicitly shown by this local choice contract. */
internal fun DecisionChoiceSpec.entityReferences(): List<EntityId> = when (this) {
    is TargetsChoiceSpec -> legalTargets.toSortedMap().values.flatten()
    is CardsChoiceSpec -> buildList {
        addAll(options)
        addAll(nonSelectableOptions)
        addAll(cardInfo.orEmpty().keys)
        conditionalMinimums.forEach { addAll(it.matchingOptions) }
    }
    is DistributionChoiceSpec -> targets + maxPerTarget.keys
    is OrderChoiceSpec -> objects + cardInfo.orEmpty().keys
    is PilesChoiceSpec -> cards + cardInfo.orEmpty().keys
    is OptionsChoiceSpec -> optionCardIds.orEmpty().toSortedMap().values.flatten()
    is LibrarySearchChoiceSpec -> options + cards.keys
    is LibraryReorderChoiceSpec -> cards + cardInfo.keys
    is DamageAssignmentChoiceSpec -> buildList {
        add(attackerId)
        addAll(orderedTargets)
        defenderId?.let(::add)
        addAll(minimumAssignments.keys)
        addAll(defaultAssignments.keys)
    }
    is CombatResolutionChoiceSpec -> buildList {
        attackers.forEach { attacker ->
            add(attacker.id)
            add(attacker.attackedDefenderId)
            addAll(attacker.blockedByIds)
        }
        blockers.forEach { blocker ->
            add(blocker.id)
            addAll(blocker.blockedAttackerIds)
            addAll(blocker.orderedAttackers)
        }
        defenders.forEach { add(it.id) }
        edges.forEach { edge ->
            add(edge.sourceId)
            add(edge.targetId)
            add(edge.editableBy)
        }
        coChooserId?.let(::add)
    }
    is ManaSourcesChoiceSpec -> buildList {
        availableSources.forEach { add(it.entityId) }
        addAll(autoPaySuggestion)
        waterbendPermanents.forEach { add(it.entityId) }
    }
    is YesNoChoiceSpec,
    is BatchYesNoChoiceSpec,
    is ModesChoiceSpec,
    is ColorsChoiceSpec,
    is NumberChoiceSpec,
    is ReplacementChoiceSpec,
    is BudgetModesChoiceSpec -> emptyList()
}
