package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.state.ObjectRef
import com.wingedsheep.sdk.model.EntityId
import org.mtgallium.agent.infoset.core.*

/** Private state/history identity for exact observed-action execution, not search reuse. */
internal class ArgentumWorldRevision(private val digest: String) {
    override fun equals(other: Any?): Boolean = other is ArgentumWorldRevision && digest == other.digest
    override fun hashCode(): Int = digest.hashCode()
    override fun toString(): String = "<private-world-revision>"
}

/** Trusted evidence, never a policy feature. Native incarnations qualify LOCAL continuity only. */
data class ArgentumObservedObjectBinding(
    val nativeId: EntityId,
    val incarnation: ObjectRef?,
    val actorReference: String?,
    val observerReference: String?,
    val actorHandle: String?,
    val observerHandle: String?,
    val observerIncarnationQualified: Boolean,
)

/** One private frozen revision, with explicitly separate acting and observing perspectives. */
class ArgentumObservedActionCapture internal constructor(
    val actingSite: DecisionSiteRequest,
    val observerInformation: EpistemicState,
    action: GameAction,
    searchGroup: SemanticChoice,
    bindings: List<ArgentumObservedObjectBinding>,
    val behaviorId: String,
    internal val decisionIndex: Int,
    internal val eventOrder: PerspectiveHistoryEventOrder,
    internal val objectReference: PerspectiveHistoryObjectReference,
    internal val view: DecisionView,
    internal val players: Map<EntityId, String>,
) {
    private val actionBytes = PolicyJson.format.encodeToString(GameAction.serializer(), action)
    private val groupBytes = PolicyJson.format.encodeToString(SemanticChoice.serializer(), searchGroup)
    /** Decode on access so a caller cannot mutate the retained declaration's collection fields. */
    val action: GameAction get() = PolicyJson.format.decodeFromString(GameAction.serializer(), actionBytes)
    val searchGroup: SemanticChoice get() = PolicyJson.format.decodeFromString(SemanticChoice.serializer(), groupBytes)
    private val retainedBindings = bindings.toList()
    val bindings: List<ArgentumObservedObjectBinding> get() = retainedBindings.toList()

    /**
     * Resolve the member and its likelihood menu in the particle using the conditioning model's
     * view. Unconditioned callers retain the host capture's view.
     */
    fun particleAction(conditioningView: DecisionView = view): ExactObservedAction = ExactObservedAction { world ->
        val adapter = world as? ArgentumSearchWorld
        if (adapter == null) ExactObservedActionResolution.Unsupported("WORLD_TYPE")
        else when (val result = adapter.correspondObservedActionForHost(this, conditioningView)) {
            is ArgentumActionCorrespondence.Unsupported -> if (result.reason == ArgentumCorrespondenceRefusal.NATIVE_REJECTED)
                ExactObservedActionResolution.NativeRejected(result.reason.name)
                else ExactObservedActionResolution.Unsupported(result.reason.name)
            is ArgentumActionCorrespondence.Matched -> {
                val revisionKey = adapter.exactRevision()
                ExactObservedActionResolution.Matched(
                    result.searchGroup.signature, result.memberProbability, result.memberSelectionBehaviorId,
                    adapter.decisionContext(conditioningView),
                ) { child ->
                    require(child is ArgentumSearchWorld)
                    require(child.exactRevision() == revisionKey) {
                        "Exact member execution requires a fork of the resolved revision"
                    }
                    child.applyObservedAction(result.action).result
                }
            }
        }
    }
}

enum class ArgentumCorrespondenceRefusal {
    DIFFERENT_BOUNDARY, DIFFERENT_OBSERVER_INFORMATION, UNSUPPORTED_ACTION_FAMILY,
    MISSING_BINDING, AMBIGUOUS_BINDING, INCARNATION_MISMATCH, UNAVAILABLE_GROUP, NATIVE_REJECTED,
}

sealed interface ArgentumActionCorrespondence {
    data class Matched(
        val action: GameAction,
        val searchGroup: SemanticChoice,
        /** Conditional mass given the search group; this is NOT the group's policy probability. */
        val memberProbability: Double,
        val memberSelectionBehaviorId: String = REPRESENTATIVE_ONLY,
    ) : ArgentumActionCorrespondence
    data class Unsupported(val reason: ArgentumCorrespondenceRefusal) : ArgentumActionCorrespondence

    companion object {
        const val REPRESENTATIVE_ONLY = "exact-member:retained-representative-only-v1"
        const val BEHAVIOR_ID = "observed-native:proposal-independent-origin-aware-propagation-v5"
    }
}

/** Reusable handle join. Neither native ID equality, names nor snapshot ordinals establish a match. */
internal object QualifiedObservedObjectCorrespondence {
    fun bind(
        source: List<ArgentumObservedObjectBinding>,
        target: List<ArgentumObservedObjectBinding>,
    ): Pair<Map<EntityId, EntityId>?, ArgentumCorrespondenceRefusal?> {
        val result = linkedMapOf<EntityId, EntityId>()
        for (binding in source) {
            val handle = binding.observerHandle
                ?: return null to ArgentumCorrespondenceRefusal.MISSING_BINDING
            if (!binding.observerIncarnationQualified || binding.incarnation == null)
                return null to ArgentumCorrespondenceRefusal.INCARNATION_MISMATCH
            val matches = target.filter { it.observerHandle == handle }
            if (matches.isEmpty()) return null to ArgentumCorrespondenceRefusal.MISSING_BINDING
            if (matches.size != 1) return null to ArgentumCorrespondenceRefusal.AMBIGUOUS_BINDING
            val match = matches.single()
            if (!match.observerIncarnationQualified || match.incarnation == null)
                return null to ArgentumCorrespondenceRefusal.INCARNATION_MISMATCH
            result[binding.nativeId] = match.nativeId
        }
        if (result.values.toSet().size != result.size)
            return null to ArgentumCorrespondenceRefusal.AMBIGUOUS_BINDING
        return result to null
    }
}

/** Typed transport deliberately refuses families whose exact correspondence is not yet supported. */
internal fun GameAction.transportObservedObjects(resolve: (EntityId) -> EntityId): GameAction? = when (this) {
    is PassPriority -> copy(playerId = resolve(playerId))
    is DeclareAttackers -> copy(playerId = resolve(playerId),
        attackers = attackers.entries.associate { resolve(it.key) to resolve(it.value) },
        bands = bands.map { band -> band.map(resolve).toSet() })
    is DeclareBlockers -> copy(playerId = resolve(playerId),
        blockers = blockers.entries.associate { resolve(it.key) to it.value.map(resolve) })
    is OrderBlockers -> copy(playerId = resolve(playerId), attackerId = resolve(attackerId),
        orderedBlockers = orderedBlockers.map(resolve))
    else -> null
}
