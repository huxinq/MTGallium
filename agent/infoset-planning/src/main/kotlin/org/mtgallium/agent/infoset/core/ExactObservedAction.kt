package org.mtgallium.agent.infoset.core

/** Trusted simulation capability; implementations retain native declarations behind the adapter. */
fun interface ExactObservedAction {
    fun resolve(world: SearchWorld): ExactObservedActionResolution
}

sealed interface ExactObservedActionResolution {
    class Matched(
        val groupSignature: String,
        val memberProbability: Double,
        val memberSelectionBehaviorId: String,
        /** The very menu whose retained native representative defines the member mass. */
        val context: DecisionSiteRequest,
        val apply: (SearchWorld) -> SearchStepResult,
    ) : ExactObservedActionResolution {
        init {
            require(memberProbability.isFinite() && memberProbability in 0.0..1.0)
            require(memberSelectionBehaviorId.isNotBlank())
        }
    }
    data class Unsupported(val reason: String) : ExactObservedActionResolution
    data class NativeRejected(val reason: String) : ExactObservedActionResolution
}

enum class ExactObservationFailureKind {
    UNAVAILABLE_CORRESPONDENCE, EXACT_MEMBER_ZERO_MASS, NATIVE_REJECTION,
    INCOMPATIBLE_SUCCESSOR, INVALID_WEIGHT, MIXED_EXHAUSTION,
}

data class ExactObservationFailureCounts(
    val population: Int,
    val unavailableCorrespondence: Int = 0,
    val exactMemberZeroMass: Int = 0,
    val nativeRejection: Int = 0,
    val incompatibleSuccessor: Int = 0,
    val invalidWeight: Int = 0,
    val surviving: Int = 0,
) {
    init {
        require(listOf(population, unavailableCorrespondence, exactMemberZeroMass, nativeRejection,
            incompatibleSuccessor, invalidWeight, surviving).all { it >= 0 })
        require(population == unavailableCorrespondence + exactMemberZeroMass + nativeRejection +
            incompatibleSuccessor + invalidWeight + surviving)
    }
}

data class ExactObservationFailureReport(
    val kind: ExactObservationFailureKind,
    val counts: ExactObservationFailureCounts,
    val correspondenceReasons: Map<String, Int> = emptyMap(),
)

/** Missing correspondence is not sampled zero support and must not trigger depletion recovery. */
class UnsupportedObservedActionException(val reason: String, val report: ExactObservationFailureReport? = null) :
    IllegalStateException("UNSUPPORTED_OBSERVED_ACTION:$reason")

/** A completed, classified exact update with no survivors. Counts remain available when all mass is zero. */
class ExactObservationDepletionException(val report: ExactObservationFailureReport) :
    ParticleDepletionException("EXACT_OBSERVATION_DEPLETION:${report.kind}:${report.counts}")
