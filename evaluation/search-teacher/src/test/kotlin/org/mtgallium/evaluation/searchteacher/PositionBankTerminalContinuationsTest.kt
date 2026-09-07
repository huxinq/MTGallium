package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.InformationSetSearch
import org.mtgallium.agent.infoset.core.OpponentPolicyDecisionSummary
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.research.run.ResearchSourceTreeState

@Tag("public-source")
class PositionBankTerminalContinuationsTest {
    private val policy = PositionBankScreenPolicy(
        SearchTeacherCalibrationPolicy("rollout", 8, 64, 32, 0.35, true, 1.0), MonoRedVisibleEvaluatorConfig())

    private fun plan() = PositionBankTerminalContinuationPlan(bankDirectory = "/private/bank",
        expectedBankIdentity = "bank", partition = PositionBankScreenPartition.DEVELOPMENT,
        rootIds = listOf("root"), policy = policy, samplesPerRoot = 2,
        maxContinuationDecisions = 100, baseSeed = 7, maximumCandidates = 8)

    private fun terminal(signature: String, coordinate: PositionBankTerminalCoordinate, payoff: Double) =
        PositionBankTerminalSample(signature, coordinate, 1.0, payoff, 0,
            OpponentPolicyDecisionSummary(), OpponentPolicyDecisionSummary())

    @Test
    fun `plan requires development roots and finite positive bounds`() {
        assertFailsWith<IllegalArgumentException> { plan().copy(partition = PositionBankScreenPartition.VALIDATION) }
        assertFailsWith<IllegalArgumentException> { plan().copy(rootIds = listOf("root", "root")) }
        assertFailsWith<IllegalArgumentException> { plan().copy(rootIds = emptyList()) }
        assertFailsWith<IllegalArgumentException> { plan().copy(samplesPerRoot = 0) }
        assertFailsWith<IllegalArgumentException> { plan().copy(maxContinuationDecisions = 0) }
        assertFailsWith<IllegalArgumentException> { plan().copy(maximumCandidates = 0) }
    }

    @Test
    fun `paired coordinates use production weighted draws and separate seed domains`() {
        val weights = listOf(0.0, 1.0, 3.0)
        val coordinates = positionBankTerminalCoordinates(7, "root", weights, 128)
        assertEquals(coordinates, positionBankTerminalCoordinates(7, "root", weights, 128))
        assertEquals(InformationSetSearch.productionRootParticleIndices(weights, coordinates.first().particleSamplingSeed, 128),
            coordinates.map { it.particleIndex })
        assertTrue(coordinates.none { it.particleIndex == 0 })
        assertTrue(coordinates.any { it.particleIndex == 1 } && coordinates.any { it.particleIndex == 2 })
        assertEquals((0 until 128).toList(), coordinates.map { it.replicate })
        assertTrue(coordinates.all { it.futureSeed != it.continuationSeed })
        assertNotEquals(coordinates, positionBankTerminalCoordinates(7, "other-root", weights, 128))
    }

    @Test
    fun `complete rectangular population reports only paired descriptive gaps`() {
        val coordinates = positionBankTerminalCoordinates(7, "root", listOf(1.0), 2)
        val samples = listOf(terminal("a", coordinates[0], 1.0), terminal("a", coordinates[1], -1.0),
            terminal("b", coordinates[0], -1.0), terminal("b", coordinates[1], 0.0))
        val summary = summarizePositionBankTerminalSamples(listOf("a", "b"), 2, samples)
        assertTrue(summary.complete)
        assertEquals(4, summary.assignedSamples)
        assertEquals(4, summary.terminalSamples)
        assertEquals(0, summary.refusedSamples)
        assertEquals(0, summary.unattemptedSamples)
        assertEquals(mapOf("a" to 0.0, "b" to -0.5), summary.candidateMeanPayoffs)
        assertEquals(listOf(PositionBankTerminalGap("a", "b", 0.5)), summary.pairedGaps)
        assertEquals(summary, summarizePositionBankTerminalSamples(listOf("a", "b"), 2, samples.reversed()))
    }

    @Test
    fun `one refused candidate sample suppresses every root mean and gap`() {
        val coordinates = positionBankTerminalCoordinates(7, "root", listOf(1.0), 2)
        val refused = PositionBankTerminalSample("b", coordinates[1], 10.0,
            refusal = PositionBankTerminalRefusal.CONTINUATION_LIMIT, diagnostic = "cap")
        val samples = listOf(terminal("a", coordinates[0], 1.0), terminal("a", coordinates[1], 1.0),
            terminal("b", coordinates[0], -1.0), refused)
        val summary = summarizePositionBankTerminalSamples(listOf("a", "b"), 2, samples)
        assertFalse(summary.complete)
        assertEquals(4, summary.attemptedSamples)
        assertEquals(3, summary.terminalSamples)
        assertEquals(1, summary.refusedSamples)
        assertTrue(summary.candidateMeanPayoffs.isEmpty() && summary.pairedGaps.isEmpty())
        assertNull(refused.policyDecisions)
        assertNull(refused.rootPolicyDecisions)
        assertNull(refused.opponentPolicyDecisions)
        assertFailsWith<IllegalArgumentException> { refused.copy(policyDecisions = 0) }
    }

    @Test
    fun `missing samples and refused roots retain assigned denominators without values`() {
        val coordinate = positionBankTerminalCoordinates(7, "root", listOf(1.0), 2).first()
        val summary = summarizePositionBankTerminalSamples(listOf("a", "b"), 2, listOf(terminal("a", coordinate, 1.0)))
        assertFalse(summary.complete)
        assertEquals(4, summary.assignedSamples)
        assertEquals(3, summary.unattemptedSamples)
        assertTrue(summary.candidateMeanPayoffs.isEmpty() && summary.pairedGaps.isEmpty())
        val capped = summarizePositionBankTerminalSamples(listOf("a", "b", "c"), 2, emptyList(), rootRefused = true)
        assertEquals(6, capped.assignedSamples)
        assertEquals(6, capped.unattemptedSamples)
        assertEquals(0, capped.attemptedSamples)
        assertFalse(capped.complete)
    }

    @Test
    fun `duplicate or unpaired samples cannot masquerade as a complete matrix`() {
        val coordinate = positionBankTerminalCoordinates(7, "root", listOf(1.0), 1).single()
        val a = terminal("a", coordinate, 1.0)
        assertFailsWith<IllegalArgumentException> { summarizePositionBankTerminalSamples(listOf("a", "b"), 1, listOf(a, a)) }
        assertFailsWith<IllegalArgumentException> {
            summarizePositionBankTerminalSamples(listOf("a", "b"), 1,
                listOf(a, terminal("b", coordinate.copy(futureSeed = coordinate.futureSeed + 1), -1.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            summarizePositionBankTerminalSamples(listOf("a", "b"), 1, listOf(terminal("outside", coordinate, 1.0)))
        }
    }

    @Test
    fun `report complete field requires all selected roots and every assigned terminal sample`() {
        val choice = SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
            operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay("Pass"),
            canonicalPayload = JsonObject(emptyMap()))
        val samples = positionBankTerminalCoordinates(7, "root", listOf(1.0), 2).map { terminal(choice.signature, it, 1.0) }
        val summary = summarizePositionBankTerminalSamples(listOf(choice.signature), 2, samples)
        val row = PositionBankTerminalRoot("root", "p0", 7, listOf(choice), listOf(choice), "test", true,
            summary, samples, elapsedMillis = 2.0)
        val tree = ResearchSourceTreeState("test-revision", "0".repeat(64), "0".repeat(64), "0".repeat(64))
        val report = PositionBankTerminalContinuationReport(researchRunIdentity = "test-run",
            sourceProvenance = PolicySourceProvenance(expectedArgentumRevision = tree.revision, outer = tree, argentum = tree),
            generatedAtUtc = "2026-09-06T00:00:00Z", plan = plan(), workerThreads = 1, roots = listOf(row),
            assignedSamples = 2, attemptedSamples = 2, terminalSamples = 2, refusedSamples = 0,
            unattemptedSamples = 0, completeRoots = 1, invalidRoots = 0, complete = true)
        assertTrue(report.complete)
        assertEquals(report, evidenceJson.decodeFromString<PositionBankTerminalContinuationReport>(evidenceJson.encodeToString(report)))
        assertFailsWith<IllegalArgumentException> { report.copy(complete = false) }
        val missingSummary = summarizePositionBankTerminalSamples(listOf(choice.signature), 2, samples.take(1))
        val partial = report.copy(roots = listOf(row.copy(summary = missingSummary, samples = samples.take(1))),
            attemptedSamples = 1, terminalSamples = 1, unattemptedSamples = 1, completeRoots = 0, invalidRoots = 1, complete = false)
        assertFalse(partial.complete)
        assertFailsWith<IllegalArgumentException> { partial.copy(complete = true) }
    }
}
