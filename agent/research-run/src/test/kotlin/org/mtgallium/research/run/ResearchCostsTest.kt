package org.mtgallium.research.run

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.serialization.json.Json

class ResearchCostsTest {
    @Test fun `reused evidence charges verification time without counting historical scientific work`() {
        var nanos = 0L
        val output = createTempDirectory("cost-reuse").resolve("costs.json")
        val recorder = ResearchCostRecorder(output, { nanos }, { null })
        recorder.measure("retained-targets", reused = true, describe = { ResearchMeasuredWork("old-target-id") }) { nanos += 4_000_000; "verified" }
        val report = Json.decodeFromString<ResearchInvocationCosts>(Files.readString(output))
        assertEquals(4.0, report.wallMillis)
        assertEquals(ResearchStageDisposition.REUSED, report.stages.single().disposition)
        assertNull(report.processCpuMillis)
        assertTrue(report.stages.single().counts.isEmpty())
        assertFails { recorder.measure("wrong-reuse", reused = true, describe = { ResearchMeasuredWork(counts = mapOf("samples" to 80)) }) { Unit } }
    }

    @Test fun `failed stages retain earlier completed work and never acquire a result identity`() {
        var nanos = 0L; var cpu = 0L
        val output = createTempDirectory("cost-failure").resolve("costs.json")
        val recorder = ResearchCostRecorder(output, { nanos }, { cpu })
        recorder.measure("fit", describe = { ResearchMeasuredWork("fit-id", mapOf("fits" to 1)) }) { nanos += 2_000_000; cpu += 5_000_000 }
        assertFails { recorder.measure<Unit>("validation") { nanos += 3_000_000; cpu += 4_000_000; error("unsupported action") } }
        val report = Json.decodeFromString<ResearchInvocationCosts>(Files.readString(output))
        assertEquals(5.0, report.wallMillis); assertEquals(9.0, report.processCpuMillis)
        assertEquals(listOf(ResearchStageDisposition.COMPLETED, ResearchStageDisposition.FAILED), report.stages.map { it.disposition })
        assertEquals("fit-id", report.stages.first().evidenceIdentity)
        assertNull(report.stages.last().evidenceIdentity)
        assertTrue(report.stages.last().diagnostic!!.contains("unsupported action"))
        assertFails { recorder.measure("fit") { Unit } }
    }
    @Test fun `a returned failed child retains its identity and partial work when validation refuses it`() {
        val output = createTempDirectory("cost-partial-child").resolve("costs.json")
        val recorder = ResearchCostRecorder(output)
        val counts = mapOf("requested-continuations" to 8L, "terminal-samples" to 3L, "failed-attempts" to 1L, "unexecuted-continuations" to 4L)
        assertFails {
            recorder.measure("terminal-targets", describe = { ResearchMeasuredWork("finalized-child", counts, mapOf("selection" to 7.0)) },
                validate = { _: String -> error("Child contains a typed non-game failure") }) { "retained child report" }
        }
        val row = Json.decodeFromString<ResearchInvocationCosts>(Files.readString(output)).stages.single()
        assertEquals(ResearchStageDisposition.FAILED, row.disposition)
        assertEquals("finalized-child", row.evidenceIdentity)
        assertEquals(counts, row.counts)
        assertEquals(7.0, row.accumulatedComponentMillis.getValue("selection"))
    }

}
