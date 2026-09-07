package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import jdk.jfr.Recording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherSuites
import org.mtgallium.research.run.ResearchRunArtifacts

@Tag("public-source")
class SearchProfileSummaryTest {
    @TempDir lateinit var temporary: Path
    private val search = "org.mtgallium.agent.infoset.core.InformationSetSearch.rollout"

    @Test
    fun `cost partition excludes incomplete stacks and does not double count digest projection`() {
        val summary = SearchProfileSummary()
        val projection = "org.mtgallium.agent.infoset.argentum.SafeObservationProjection.withPriority"
        summary.addExecution(SearchProfileStack(listOf("org.mtgallium.agent.infoset.core.PolicyJson.digest", projection, search)))
        summary.addExecution(SearchProfileStack(listOf(projection, search)))
        summary.addExecution(SearchProfileStack(listOf("com.wingedsheep.engine.legalactions.LegalActionEnumerator.enumerate", search)))
        summary.addExecution(SearchProfileStack(listOf("new.unclassified.Work.run", search)))
        summary.addExecution(SearchProfileStack(listOf("reconstruction.Loader.run")))
        summary.addExecution(SearchProfileStack(listOf(search), truncated = true))
        summary.addExecution(SearchProfileStack(emptyList()))
        assertEquals(7L, summary.executionSamples)
        assertEquals(4L, summary.searchSamples)
        assertEquals(1L, summary.otherSamples)
        assertEquals(1L, summary.truncatedStackSamples)
        assertEquals(1L, summary.missingStackSamples)
        assertEquals(4L, summary.exclusiveCosts.values.sum())
        assertEquals(1L, summary.exclusiveCosts["Canonical digest"])
        assertEquals(1L, summary.exclusiveCosts["Other observation projection"])
        assertEquals(1L, summary.exclusiveCosts["Other search"])
        assertEquals(2L, summary.inclusiveCosts["Priority-only safe projection"])
        assertEquals(1L, summary.digestCallers[projection])
        val rendered = renderSearchProfileSummary(summary, "synthetic")
        assertTrue("25.0%" in rendered)
        assertTrue("do not add them" in rendered)
    }

    @Test
    fun `sampled allocation is kept separate from execution counts`() {
        val summary = SearchProfileSummary()
        val stack = SearchProfileStack(listOf("java.util.HashMap.put", "org.mtgallium.example.Feature.add", search))
        summary.addAllocation(stack, 100)
        summary.addAllocation(stack, 200)
        summary.addAllocation(stack.copy(truncated = true), 900)
        assertEquals(2L, summary.allocationSamples)
        assertEquals(300L, summary.allocationWeights.values.sum())
        assertEquals(0L, summary.executionSamples)
        assertTrue("No complete search samples" in renderSearchProfileSummary(summary, "synthetic"))
        assertFalse("NaN" in renderSearchProfileSummary(summary, "synthetic"))
    }

    @Test
    fun `retained profile command verifies registered bytes without requiring a deck`() {
        val path = temporary.resolve("profile.jfr")
        Recording().use { recording -> recording.start(); recording.stop(); recording.dump(path) }
        val identity = "research-run-v1-sha256:${"a".repeat(64)}"
        ResearchRunArtifacts(temporary, identity).apply { register("profile.jfr"); finalize() }
        assertTrue(identity in summarizeRegisteredSearchProfile(path, identity))
        val options = SearchTeacherCli.parse(arrayOf("--suite", "search-profile-summary", "--profile", path.toString()))
        assertEquals("search-profile-summary", SearchTeacherSuites.require(options.suite).id)
        assertEquals(null, options.deckManifest)
        Files.writeString(path, "tampered")
        assertFails { summarizeRegisteredSearchProfile(path, identity) }
    }
}
