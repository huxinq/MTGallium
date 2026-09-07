package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.research.run.*

@Tag("public-source")
class CampaignDataUseTest {
    private val tree = ResearchSourceTreeState("synthetic", "a".repeat(64), "b".repeat(64), "c".repeat(64))
    private val source = ResearchRunProvenance("synthetic", "synthetic", "synthetic", false, false, ResearchSourceProvenance(expectedArgentumRevision = "synthetic", outer = tree, argentum = tree))
    private val population = CampaignVerifiedPopulation(listOf("root-a", "root-b"), listOf("same-library-group"), mapOf("bank" to "a".repeat(64)))
    private fun plan(role: CampaignDataRole) = CampaignDataUsePlan(campaignId = "synthetic-campaign", studyIdentity = "study", role = role,
        timing = CampaignDataTiming.RETROSPECTIVE_RECORD, populations = listOf(CampaignPopulationInput(CloningComparisonInput("/synthetic/bank", "bank"), population.rootIds)),
        purpose = "Synthetic authority and grouping witness")

    @Test fun `uses preserve role timing and population in immutable identities`() {
        val original = campaignUseBindings(plan(CampaignDataRole.TRAINING), population, source)
        assertNotEquals(original.identity, campaignUseBindings(plan(CampaignDataRole.INSPECTION), population, source).identity)
        assertNotEquals(original.identity, campaignUseBindings(plan(CampaignDataRole.TRAINING).copy(timing = CampaignDataTiming.PROSPECTIVE_RESERVATION), population, source).identity)
        assertNotEquals(original.identity, campaignUseBindings(plan(CampaignDataRole.TRAINING), population.copy(seedGroups = listOf("another-library")), source).identity)
        assertNotEquals(original.identity, campaignUseBindings(plan(CampaignDataRole.TRAINING), population.copy(inputManifestHashes = mapOf("bank" to "b".repeat(64))), source).identity)
    }

    @Test fun `registry retains both uses of one library group and refuses a corrupted event`() {
        val repository = createTempDirectory("campaign-repository")
        // Follow the configured public evidence root when present, rather than inventing private inputs.
        val registryPath = org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore(repository).work("synthetic-campaign-${System.nanoTime()}")
        Files.createDirectories(registryPath)
        for (role in listOf(CampaignDataRole.TRAINING, CampaignDataRole.METHOD_SELECTION)) {
            val p = plan(role); val b = campaignUseBindings(p, population, source); val dir = registryPath.resolve(b.identity.substringAfterLast(':'))
            writeJsonAtomically(dir.resolve("bindings.json"), b)
            writeJsonAtomically(dir.resolve("record.json"), CampaignDataUseRecord(b.identity, p, "2026-01-01T00:00:00Z", source, population.seedGroups, population.rootIds, population.inputManifestHashes))
            ResearchRunArtifacts(dir, b.identity).apply { register("bindings.json"); register("record.json"); finalize() }
        }
        val registry = CampaignDataRegistry(repository, registryPath, "synthetic-campaign")
        val snapshot = registry.snapshot()
        assertEquals(2, snapshot.records.size); assertEquals(1, snapshot.groups.size)
        assertEquals(setOf(CampaignDataRole.TRAINING, CampaignDataRole.METHOD_SELECTION), snapshot.groups.single().roles.toSet())
        assertTrue(snapshot.interpretation.contains("cannot"))
        val event = registryPath.resolve(snapshot.records.first().identity.substringAfterLast(':'))
        Files.writeString(event.resolve("record.json"), "changed population")
        assertFails { registry.snapshot() }
    }
}
