package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherSuites

@Tag("public-source")
class SearchTeacherCommandDispatchTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `every established suite remains registered exactly once`() {
        val expected = """
            smoke research-preflight research-preflight-verify
            search-profile-summary gameplay-summary terminal-prediction-diagnostic
            terminal-kernel-study attack-kernel-learning direct-attack-kernel-screen
            attack-kernel-gameplay terminal-target-sensitivity research-transfer-audit
            campaign-data-use campaign-data-snapshot arena
            arena-shard arena-merge tactical
            tactical-authoring tactical-horizon-authoring tactical-horizon-check
            evaluator-comparison tactical-proof tactical-proof-benchmark
            replay-review-decisions replay-review-case-intake pilot-calibrate
            legacy-tactical-benchmark tournament tournament-v3-calibrated
            outcome-qualification-preflight outcome-qualification-pilot search-teacher-calibration
            search-teacher-sequential search-teacher-continuation search-teacher-continuation-preflight
            real-game-position-bank position-bank-screen position-bank-terminal-continuations
            search-budget-frontier-preflight search-budget-frontier-pilot search-budget-frontier-extension-preflight
            search-budget-frontier-extension outcome-state-corpus-preflight outcome-state-corpus
            learned-outcome-value-gate learned-outcome-value-global-signal learned-outcome-value-retained-parity-audit
            learned-leaf-pilot learned-leaf-pilot-smoke learned-leaf-fixed-root-bind
            learned-leaf-fixed-root-preflight learned-leaf-fixed-root-diagnostic decision-local-precision-preflight
            decision-local-learnability-pilot decision-local-root-coverage-preflight decision-local-root-coverage
            decision-local-performance-check decision-local-precision-followup decision-local-root-freeze
            decision-local-throughput-preflight decision-local-sibling-outcome decision-local-sibling-signal
            baseline-factorial-tournament baseline-factorial-smoke tree-reuse-validation
            calibrate corpus ablations
            belief opponent-models population
            review replay throughput
            latency-preflight tournament-performance tournament-remediation
            tournament-remediation-check tournament-remediation-probe tournament-fallback-diagnostic
            response-window-inventory player-choice-inventory tournament-amendment
            inspection play baseline-hardening
            issue-0013-stage-a issue-0013-stage-b-panel issue-0013-stage-b
            issue-0013-stage-b-reviewed-secondary issue-0013-blinded-review standalone-mana-timing-experiment
            root-search-evidence-repeatability neural-behavioral-cloning neural-capacity-diagnostic
            neural-memorization-diagnostic neural-saturation-trajectory-diagnostic neural-candidate-update-scale-diagnostic
            neural-population-scaling-diagnostic neural-stability-boundary-diagnostic neural-final-boundary-diagnostic
            neural-cohort-continuation-preflight neural-cohort-continuation-diagnostic neural-anchor-crossing-preflight
            neural-anchor-crossing-diagnostic neural-held-out-generalization-preflight neural-held-out-generalization-diagnostic
        """.trimIndent().split(Regex("\\s+"))
        val actual = SearchTeacherSuites.all().map { it.id }
        assertEquals(expected.sorted(), actual)
        actual.forEach { assertEquals(it, SearchTeacherSuites.require(it).id) }
    }

    @Test
    fun `historical inspection validates its inputs without current checkout provenance`() {
        // This directory is deliberately not a source checkout and has no engine or deck.
        val failure = assertFailsWith<IllegalArgumentException> {
            runSearchTeacher(directory, arrayOf("--suite", "search-profile-summary"))
        }
        assertEquals(
            "Pass a JFR registered in its parent directory's finalized manifest via --profile",
            failure.message,
        )
        val parity = assertFailsWith<IllegalArgumentException> {
            runSearchTeacher(directory, arrayOf("--suite", "learned-outcome-value-retained-parity-audit"))
        }
        assertEquals("The retained verified outcome-state corpus is required via --outcome-corpus", parity.message)
    }

    @Test
    fun `source-bound commands still capture provenance before their handler runs`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            runSearchTeacher(directory, arrayOf("--suite", "neural-capacity-diagnostic"))
        }
        assertTrue(failure.message.orEmpty().startsWith("Git rev-parse HEAD failed in "))
    }

    @Test
    fun `unknown commands are refused before accessing source or evidence`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            runSearchTeacher(directory, arrayOf("--suite", "unregistered-command"))
        }
        assertEquals("Unknown suite unregistered-command", failure.message)
    }
}
