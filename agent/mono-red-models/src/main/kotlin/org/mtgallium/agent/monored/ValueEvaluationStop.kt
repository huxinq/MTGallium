package org.mtgallium.agent.monored

/** Redacts diagnostic details at the policy boundary; the original exception remains the cause. */
class ValueEvaluationStop(
    failure: ValueEvaluationException,
) : IllegalStateException("VALUE_EVALUATION:${failure.kind.name}", failure) {
    val failureKind: ValueInputError = failure.kind
}
