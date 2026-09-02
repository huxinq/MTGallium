package org.mtgallium.evaluation.searchteacher

import org.junit.jupiter.api.Tag

internal const val SCENARIO_EXECUTION_TAG = "scenario-execution"

/** Marks tests that execute full games, multi-case engine diagnostics, finite proofs, or benchmarks. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Tag(SCENARIO_EXECUTION_TAG)
internal annotation class ScenarioExecutionTest
