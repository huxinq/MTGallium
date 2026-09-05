package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherSuites

@org.junit.jupiter.api.Tag("public-source")
class LearnedOutcomeValueGateTest {
    @Test
    fun `partial stage fails closed before corpus loading or source capture`() {
        val output = createTempDirectory("learned-outcome-gate-partial")
        Files.createDirectories(output.resolve("training"))
        val runner = LearnedOutcomeValueGateRunner(
            repositoryRoot = output,
        )

        val failure = assertFailsWith<IllegalStateException> {
            runner.run(output.resolve("corpus"), output)
        }

        assertTrue(failure.message.orEmpty().contains("partial output"))
    }

    @Test
    fun `cli registers the completed corpus gate and its explicit inputs`() {
        assertEquals("learned-outcome-value-gate", SearchTeacherSuites.require("learned-outcome-value-gate").id)
        val parsed = SearchTeacherCli.parse(
            arrayOf(
                "--suite", "learned-outcome-value-gate",
                "--outcome-corpus", "completed-corpus",
                "--output", "gate-output",
            )
        )

        assertTrue(parsed.outcomeCorpus?.isAbsolute == true)
        assertTrue(parsed.outputPath?.isAbsolute == true)
    }

    @Test
    fun `cli requires separately named evidence roots for a learned pilot`() {
        assertEquals("learned-leaf-pilot", SearchTeacherSuites.require("learned-leaf-pilot").id)
        val parsed = SearchTeacherCli.parse(
            arrayOf(
                "--suite", "learned-leaf-pilot",
                "--outcome-corpus", "completed-corpus",
                "--learned-gate", "completed-gate",
                "--learned-smoke", "completed-matching-smoke",
                "--output", "pilot-output",
                "--pairs", "50",
            )
        )

        assertTrue(parsed.outcomeCorpus?.isAbsolute == true)
        assertTrue(parsed.learnedGate?.isAbsolute == true)
        assertTrue(parsed.learnedSmoke?.isAbsolute == true)
        assertTrue(parsed.outputPath?.isAbsolute == true)
        assertEquals("learned-leaf-pilot-smoke", SearchTeacherSuites.require("learned-leaf-pilot-smoke").id)
    }

    @Test
    fun `pilot CLI cannot be invoked without a smoke artifact location`() {
        assertFailsWith<IllegalArgumentException> {
            SearchTeacherCli.parse(
                arrayOf(
                    "--suite", "learned-leaf-pilot",
                    "--outcome-corpus", "completed-corpus",
                    "--learned-gate", "completed-gate",
                    "--output", "pilot-output",
                    "--pairs", "50",
                ),
            )
        }
    }
}
