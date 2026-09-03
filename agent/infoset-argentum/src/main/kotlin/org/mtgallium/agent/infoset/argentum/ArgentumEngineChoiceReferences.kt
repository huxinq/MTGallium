package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseManaColor
import com.wingedsheep.engine.core.Concede
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.ForetellCard
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.OrderBlockers
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlotCard
import com.wingedsheep.engine.core.SaddleMount
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.SuspendCardFromHand
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.core.TypedEntityReferences
import com.wingedsheep.engine.core.TypecycleCard
import com.wingedsheep.engine.core.UnlockRoomDoor
import com.wingedsheep.sdk.model.EntityId

/** Complete typed references from Argentum, or null when its serializer traversal failed closed. */
internal fun GameAction.completeEntityReferencesOrNull(): Set<EntityId>? =
    when (val projection = TypedEntityReferences.action(this)) {
        is TypedEntityReferences.Projection.Complete -> projection.entityIds
        is TypedEntityReferences.Projection.Incomplete -> null
    }

/** Complete typed references from Argentum, or null when its serializer traversal failed closed. */
internal fun DecisionResponse.completeEntityReferencesOrNull(): Set<EntityId>? =
    when (val projection = TypedEntityReferences.response(this)) {
        is TypedEntityReferences.Projection.Complete -> projection.entityIds
        is TypedEntityReferences.Projection.Incomplete -> null
    }

/** MTGallium's display/intent source, deliberately distinct from generic reference traversal. */
internal fun GameAction.policySourceEntityIdOrNull(): EntityId? = when (this) {
    is CastSpell -> cardId
    is ActivateAbility -> sourceId
    is CycleCard -> cardId
    is PlotCard -> cardId
    is ForetellCard -> cardId
    is SuspendCardFromHand -> cardId
    is TypecycleCard -> cardId
    is PlayLand -> cardId
    is OrderBlockers -> attackerId
    is CrewVehicle -> vehicleId
    is SaddleMount -> mountId
    is TurnFaceUp -> sourceId
    is UnlockRoomDoor -> roomId
    is PassPriority,
    is DeclareAttackers,
    is DeclareBlockers,
    is ChooseManaColor,
    is SubmitDecision,
    is TakeMulligan,
    is KeepHand,
    is BottomCards,
    is Concede -> null
}
