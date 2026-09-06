package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunProvenance

@Tag("public-source")
class CalibrationBehavioralCloningTest {
    private val tree = PolicySourceTreeState("source", "a".repeat(64), "b".repeat(64), "c".repeat(64))
    private val source = PolicySourceProvenance(expectedArgentumRevision = "source", outer = tree, argentum = tree)
    private val control = SearchTeacherCalibrationPolicy("control", 8, 64, 32, 1.4, true, 1.0)
    private val candidate = control.copy(id = "candidate")
    private val plan = SearchTeacherCalibrationPlan(phase = SearchTeacherCalibrationPhase.DEVELOPMENT,
        baseSeed = 123, pairOffset = 0, pairCount = 1, control = control, candidates = listOf(candidate))

    @Test
    fun `opaque opponent configuration remains bound while teacher is strictly decoded`() {
        val deck = SearchTeacherDeckManifest("test", "test", "test", "2026-09-06", "synthetic",
            mapOf("Mountain" to 60), emptyMap())
        val arena = SearchTeacherArena(buildRegistry(), deck, calibrationPresentationProfile(source), plan.baseSeed)
        val policies = listOf(control, candidate).map { descriptor ->
            val policy = descriptor.policy(plan.baseSeed)
            SearchTeacherCalibrationPolicyReport(descriptor, describeTournamentPolicy(policy),
                descriptor.parameters(plan.baseSeed).searchConfig(), arena.evidenceBinding(policy, null, source),
                policy.effectiveRootRolloutPolicy().behaviorSpecification,
                policy.effectiveOpponentRolloutPolicy().behaviorSpecification)
        }
        val identities = policies.associate { it.descriptor.id to it.binding.identity }
        val ordinary = searchTeacherCalibrationBindings(plan, source, identities, deck.deckHash(), deck.cardPoolHash(), 1)
        assertEquals(ordinary.identity, retainedCalibrationBindings(evidenceJson.encodeToString(plan), source,
            identities, deck.deckHash(), deck.cardPoolHash(), 1, null).identity)
        val unknown = "historicalOpponentIntervention"
        fun intervene(element: JsonElement) = JsonObject(element.jsonObject + (unknown to JsonPrimitive("preserved")))
        val rawPlan = evidenceJson.encodeToJsonElement(plan).jsonObject.let {
            JsonObject(it + ("candidates" to JsonArray(listOf(intervene(it.getValue("candidates").jsonArray.single())))))
        }
        val identity = retainedCalibrationBindings(evidenceJson.encodeToString(rawPlan), source,
            identities, deck.deckHash(), deck.cardPoolHash(), 1, null).identity
        assertNotEquals(ordinary.identity, identity)
        val report = SearchTeacherCalibrationReport(runIdentity = identity, generatedAtUtc = "synthetic",
            sourceProvenance = source, deckHash = deck.deckHash(), cardPoolHash = deck.cardPoolHash(),
            plan = plan, workerThreads = 1, currentAttemptElapsedMillis = 0.0, policies = policies,
            comparisons = listOf(calibrationComparison(plan, candidate, emptyList())), valid = false)
        val rawReport = evidenceJson.encodeToJsonElement(report).jsonObject.let { obj ->
            JsonObject(obj + ("plan" to rawPlan) + ("policies" to JsonArray(obj.getValue("policies").jsonArray.mapIndexed { i, p ->
                if (i == 0) p else JsonObject(p.jsonObject + ("descriptor" to intervene(p.jsonObject.getValue("descriptor"))))
            })))
        }
        val read = readRetainedCalibrationCloningSource(evidenceJson.encodeToString(rawReport),
            evidenceJson.encodeToString(rawPlan), identity)
        assertEquals(control, read.teacher.descriptor)
        assertEquals(rawPlan, read.plan)
        assertEquals(plan.pairSeed(0), read.pairSeed(0))
        assertFails { read.pairSeed(1) }
        assertFails { readRetainedCalibrationCloningSource(evidenceJson.encodeToString(rawReport),
            evidenceJson.encodeToString(rawPlan), ordinary.identity) }
        val badTeacher = JsonObject(rawReport + ("policies" to JsonArray(rawReport.getValue("policies").jsonArray.mapIndexed { i, p ->
            if (i != 0) p else JsonObject(p.jsonObject + ("descriptor" to intervene(p.jsonObject.getValue("descriptor"))))
        })))
        assertFails { readRetainedCalibrationCloningSource(evidenceJson.encodeToString(badTeacher),
            evidenceJson.encodeToString(rawPlan), identity) }
    }

    @Test
    fun `derived corpus authenticates lineage and retained plan`() {
        val output = Files.createTempDirectory("cloning-lineage-")
        val provenance = ResearchRunProvenance("source", "source", "source", false, false, source)
        val lineage = CalibrationCloningLineage(provenance, "parent", "d".repeat(64), "dataset",
            "control", "teacher", mapOf("game" to "parent:pair-0"))
        Files.writeString(output.resolve("corpus-manifest.json"), "synthetic corpus")
        Files.writeString(output.resolve("validation.json"), "synthetic validation")
        Files.writeString(output.resolve("lineage.json"), evidenceJson.encodeToString(lineage))
        Files.writeString(output.resolve("examples.jsonl.gz"), "synthetic extracted examples")
        val identity = finalizeCalibrationCloningAdmission(output, lineage, evidenceJson.encodeToString(plan))
        assertEquals(6, ResearchRunArtifacts.loadAndVerify(output, identity).artifacts.size)
        Files.writeString(output.resolve("lineage.json"), evidenceJson.encodeToString(lineage.copy(
            wholePairGroupByGame = mapOf("game" to "another-pair"))))
        assertFails { ResearchRunArtifacts.loadAndVerify(output, identity) }
    }
}
