package org.mtgallium.agent.monored

import org.mtgallium.agent.value.ValueInputError

class ValueEvaluationException(
    val kind: ValueInputError,
    diagnostic: String,
    cause: Throwable? = null,
) : IllegalStateException("$kind: $diagnostic", cause)

/** Redacts diagnostic details at the policy boundary; the original exception remains the cause. */
class ValueEvaluationStop(
    failure: ValueEvaluationException,
) : IllegalStateException("VALUE_EVALUATION:${failure.kind.name}", failure) {
    val failureKind: ValueInputError = failure.kind
}
