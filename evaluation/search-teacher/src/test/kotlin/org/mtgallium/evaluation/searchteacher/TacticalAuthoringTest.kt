package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.PublicArtifactPrivacy
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

class TacticalAuthoringTest {
    @Test
    fun `authoring packet contains only blinded safe state and candidates`() {
        val root = createTempDirectory("mtgallium-tactical-authoring")
        val manifest = loadDeckManifest()
        val (packet, path) = TacticalAuthoringPacketGenerator(root, buildRegistry(), manifest).generate(caseLimit = 1)

        val scenario = packet.scenarios.single()
        assertEquals("lethal-01", scenario.caseId)
        assertEquals(
            scenario.informationState.observation.perspectivePlayerId,
            scenario.informationState.actingPlayerId,
        )
        assertTrue(scenario.candidateExpansion.candidates.isNotEmpty())
        assertTrue(scenario.informationState.candidates.all { candidate ->
            candidate.signature in scenario.candidateExpansion.candidates.map { it.signature }
        })
        assertTrue(
            (scenario.informationState.candidates + scenario.candidateExpansion.candidates)
                .all { it.display.policyTags.isEmpty() }
        )

        val encoded = Files.readString(path)
        PublicArtifactPrivacy.requireSafeJson(encoded, "test tactical authoring packet")
        assertFalse(encoded.contains("rootSeed"))
        assertFalse(encoded.contains("heuristicChoice"))
        assertFalse(encoded.contains("chosenSignature"))
        assertFalse(encoded.contains("acceptableSignatures"))
        assertFalse(encoded.contains("hiddenFamily"))
        assertTrue(path.startsWith(EvidenceStore(root).work("tactical-authoring")))
    }
}
