package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

class FreshWorldReferenceOracleTest {
    @Test
    @Tag("scenario-execution")
    fun `fresh known-deck worlds follow the exact chance-only reference cases`() {
        val report = FreshWorldReferenceOracle(
            registry = buildRegistry(),
            outerCommit = "test-outer",
            argentumCommit = "test-argentum",
        ).run(trialsPerCase = 512)

        assertTrue(report.passed, report.failureReasons.joinToString())
        assertTrue(report.cases.all { it.supportViolations == 0 })
        assertTrue(report.cases.all { it.proposalRejections == 0 })
    }
}
