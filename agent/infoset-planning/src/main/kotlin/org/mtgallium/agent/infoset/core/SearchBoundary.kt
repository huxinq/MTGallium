package org.mtgallium.agent.infoset.core

/**
 * Trusted hypothetical continuation state with both players' represented information histories.
 * This is the implementation's full epistemic-state hypothesis, not merely a current game state
 * or authoritative hidden truth. Its representation and consistency checks have declared limits.
 */
interface SearchWorld {
    /** Native safe projection is independent of any requested menu. */
    fun epistemicState(viewer: String): EpistemicState = EpistemicState.capture(informationState(viewer))
    /** Implementations capture one state revision for the requested admitted menu. */
    fun decisionContext(view: DecisionView = DecisionView()): DecisionSiteRequest
    fun actorToAct(): String?
    fun informationState(viewer: String): InformationStateRepresentation
    fun expandChoices(): PolicyExpansion
    fun step(choice: SemanticChoice): SearchStepResult
    fun fork(): SearchWorld
    fun terminalPayoff(rootPlayer: String): Double?
    fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double
}

/**
 * Optional trusted capability for refreshing an exact cached snapshot after its source world has
 * materialized lazy, state-derived data. Implementations must reject a source with a different
 * authoritative state or history. This transfers caches only; it must not advance either world.
 */
interface DerivedCacheTransferSearchWorld : SearchWorld {
    fun copyDerivedCachesFrom(source: SearchWorld): Boolean
}

/** Optional extension for deterministic progressive widening above the default 64 choices. */
interface ProgressiveSearchWorld : SearchWorld {
    fun expandChoices(limit: Int): PolicyExpansion
}

/**
 * Optional information-safe policy annotation. Search requests it only where a policy actually
 * consumes annotations; forced-pass and UCT expansion paths use the cheaper semantic family.
 */
interface PolicyAnnotatedSearchWorld : SearchWorld {
    /**
     * Candidate family supplied to a stochastic policy before optional annotations are attached.
     * Implementations may override this when candidate admission is coupled to
     * annotation production; membership must match the annotated path apart from annotation data.
     */
    fun expandChoicesForPolicyAdmission(): PolicyExpansion = expandChoices()
    fun expandChoicesForPolicyAdmission(limit: Int): PolicyExpansion =
        (this as? ProgressiveSearchWorld)?.expandChoices(limit) ?: expandChoices()

    fun expandChoicesWithPolicyAnnotations(): PolicyExpansion
    fun expandChoicesWithPolicyAnnotations(limit: Int): PolicyExpansion
}

data class SearchStepResult(
    val accepted: Boolean,
    val diagnostic: String? = null,
    val forcedTransitions: List<PolicyHistoryEvent> = emptyList(),
    /** True only when the response payload is entitled to its actor and must be masked from others. */
    val privateToActor: Boolean = false,
)

/**
 * Search selected a choice advertised by a sampled world, but that same world refused to apply it.
 * This aborts the simulation; it is not a loss, draw, or heuristic value for either player.
 */
class RejectedSearchTransitionException(
    val choiceSignature: String,
    val rejectionDiagnostic: String?,
) : IllegalStateException(
    "A sampled world rejected an expanded search choice $choiceSignature" +
        (rejectionDiagnostic?.let { ": $it" } ?: ""),
)

interface BeliefWorldSource {
    fun sample(
        rootInformation: InformationStateRepresentation,
        knownDecks: Map<String, Map<String, Int>>,
        beliefSeed: Long,
        count: Int,
    ): BeliefBatch<Weighted<SearchWorld>>
}

data class BeliefBatch<out T>(
    val particles: List<T>,
    val diagnostics: BeliefDiagnostics,
)

/**
 * Optional fixed-simulation experiment input.
 *
 * Search still draws the ordinary root-particle index for every simulation, but uses the world at
 * the corresponding schedule index. This lets a controlled experiment pair an independently
 * derived future-chance stream across treatments without changing tree policy, rollout policy, or
 * any production caller. Scheduled worlds are runtime-only complete hypotheses and must never be
 * serialized into policy-facing artifacts.
 */
class SimulationWorldSchedule(val worlds: List<SearchWorld>) {
    init {
        require(worlds.isNotEmpty()) { "A simulation-world schedule cannot be empty" }
    }
}

/** Type-safe source for a leaf value; only core can expose a complete sampled world to its route. */
sealed interface LeafValueSource {
    val invokedEvaluatorId: String
    val invokedEvaluatorConfigurationId: String

    data class Information(val evaluator: InformationStateEvaluator) : LeafValueSource {
        override val invokedEvaluatorId: String = evaluator.id
        override val invokedEvaluatorConfigurationId: String =
            (evaluator as? ConfiguredInformationStateEvaluator)?.configurationId ?: evaluator.id
    }

    data class SampledWorld(
        override val invokedEvaluatorId: String,
    ) : LeafValueSource {
        override val invokedEvaluatorConfigurationId: String = invokedEvaluatorId

        init {
            require(invokedEvaluatorId.isNotBlank())
        }
    }
}

enum class UnresolvedLeafHandling { EVALUATE, BACK_UP_NEUTRAL }
