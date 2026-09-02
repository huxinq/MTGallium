package org.mtgallium.evaluation.argentum

import kotlin.test.Test
import kotlin.test.assertEquals

class DecisionTest {
    @Test
    fun `all passing gates adopt every component`() {
        val (decisions, overall) = decide(baselineProbes(), emptyList())

        assertEquals(List(decisions.size) { Verdict.ADOPT }, decisions.map { it.verdict })
        assertEquals(Verdict.ADOPT, overall)
    }

    @Test
    fun `determinism failure rejects direct gym and overall adoption`() {
        val (decisions, overall) = decide(
            probes = baselineProbes() + failure(
                id = "determinism.seeded_replay",
                component = "Direct Gym",
                severity = Severity.BLOCKER,
            ),
            corpora = emptyList(),
        )

        assertEquals(Verdict.REJECT, decisions.single { it.component == "Direct Gym" }.verdict)
        assertEquals(Verdict.REJECT, overall)
    }

    @Test
    fun `optional HTTP defects produce a conditional overall verdict`() {
        val (decisions, overall) = decide(
            probes = baselineProbes() + failure(
                id = "contract.http_seed",
                component = "HTTP Gym",
                severity = Severity.MAJOR,
            ),
            corpora = emptyList(),
        )

        assertEquals(Verdict.CONDITIONAL, decisions.single { it.component == "HTTP Gym" }.verdict)
        assertEquals(Verdict.CONDITIONAL, overall)
    }

    @Test
    fun `incomplete corpus rejects both rules core and direct gym`() {
        val corpus = CorpusResult(
            id = "probe",
            requestedGames = 10,
            completedGames = 9,
            rejectedGames = 0,
            truncatedGames = 1,
            exceptions = 0,
            totalSteps = 100,
            wallClockMillis = 10,
        )

        val (decisions, overall) = decide(baselineProbes(), listOf(corpus))

        assertEquals(Verdict.REJECT, decisions.single { it.component == "Rules core" }.verdict)
        assertEquals(Verdict.REJECT, decisions.single { it.component == "Direct Gym" }.verdict)
        assertEquals(Verdict.REJECT, overall)
    }

    private fun baselineProbes() = listOf(
        pass("cards.manifest_shape", "Rules core"),
        pass("cards.resolve", "Rules core"),
        pass("determinism.seeded_replay", "Direct Gym"),
        pass("information.hidden_zones", "Direct Gym"),
        pass("trainer.mcts_smoke", "Bundled trainer"),
        pass("trainer.structured_branching", "Bundled trainer"),
    )

    private fun pass(id: String, component: String) = ProbeResult(
        id = id,
        component = component,
        status = ProbeStatus.PASS,
        severity = Severity.INFO,
        summary = "pass",
    )

    private fun failure(id: String, component: String, severity: Severity) = ProbeResult(
        id = id,
        component = component,
        status = ProbeStatus.FAIL,
        severity = severity,
        summary = "failure",
    )
}
