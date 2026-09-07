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
        // Bank admission validates only the recorded contiguous population, not a rewritten fixed campaign.
        assertFails { requireRetainedBankPairPopulation(read) }
        val pair = SearchBudgetFrontierPair(0, plan.pairSeed(0), emptyList(), false, listOf("synthetic"))
        val prefix = read.copy(comparisons = read.comparisons.map { it.copy(pairs = listOf(pair)) })
        requireRetainedBankPairPopulation(prefix)
        assertFails { requireRetainedBankPairPopulation(prefix.copy(plan = JsonObject(prefix.plan + ("pairCount" to JsonPrimitive(2))))) }
        for (pairs in listOf(listOf(pair, pair), listOf(pair.copy(pairIndex = 1)), listOf(pair.copy(seed = 999)))) {
            assertFails { requireRetainedBankPairPopulation(prefix.copy(comparisons = prefix.comparisons.map { it.copy(pairs = pairs) })) }
        }
        val opaqueBinding = RealGamePositionBankSourceBinding("/synthetic", identity, "report", "manifest",
            source, deck.deckHash(), deck.cardPoolHash(), rawPlan, rawReport.getValue("policies").jsonArray.map { it.jsonObject }, 0, 0, 0)
        val bindingText = evidenceJson.encodeToString(opaqueBinding)
        assertEquals(opaqueBinding, evidenceJson.decodeFromString<RealGamePositionBankSourceBinding>(bindingText))
        assertTrue(bindingText.contains(unknown))
        val ordinarySource = RealGamePositionBankSource("/synthetic", identity)
        assertFalse(evidenceJson.encodeToString(ordinarySource).contains("retainedReferenceOnly"))
        assertTrue(evidenceJson.encodeToString(ordinarySource.copy(retainedReferenceOnly = true)).contains("retainedReferenceOnly"))
        val scope = BehavioralCloningAdmissionScope.retainedCalibrationReference(deck, read)
        val game = CorpusGameSummary(gameId = "g", p0Policy = ArenaPolicyKind.SEARCH,
            p1Policy = ArenaPolicyKind.SEARCH, winner = "p0", terminal = true, decisions = 1,
            searchSeat = null, searchScore = null, illegalResponses = 0, fallbacks = 0, stepLimit = false)
        val entry = CorpusEntry(gameId = "g", publicTrajectory = "g.gz", publicSha256 = "a".repeat(64),
            publicSizeBytes = 1, policyEvidenceIdentity = read.teacher.binding.identity,
            behaviorSpecificationSha256 = read.teacher.binding.behaviorSpecificationSha256,
            replayVerified = true, game = game, teacherSeat = "p0")
        scope.requireSharedTreeTeacher(entry)
        assertFails { scope.requireSharedTreeTeacher(entry.copy(policyEvidenceIdentity = "wrong-teacher")) }
        assertFails { scope.requireSharedTreeTeacher(entry.copy(teacherSeat = null)) }
        assertFails { scope.requireSharedTreeTeacher(entry.copy(game = game.copy(searchPlanner = SearchPlannerKind.SHARED_TREE))) }
        assertFails { BehavioralCloningAdmissionScope.retainedCalibrationReference(deck,
            read.copy(teacher = read.teacher.copy(policy = read.teacher.policy.copy(searchPlanner = SearchPlannerKind.NO_SEARCH_HEURISTIC)))) }
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
    fun `generation limit exclusions reject every unrelated admission failure`() {
        val reason = "teacher expansion exhausted a response or generation limit"
        val file = CorpusValidationFile("g", "g.gz", 0, 0, 0, 1, false, listOf(reason))
        val report = CorpusValidationReport(generatedAtUtc = "synthetic", outerCommit = "source", argentumCommit = "source",
            sourceManifest = "population.json", sourceManifestHash = "a".repeat(64), profileHash = "b".repeat(64),
            games = 1, terminalGames = 0, searchDecisions = 0, events = 0,
            files = listOf(file), passed = false, failures = listOf("g: $reason"))
        assertEquals(mapOf("g" to reason), generationLimitedCloningExclusions(report))
        assertFails { generationLimitedCloningExclusions(report.copy(failures = report.failures + "wrong source")) }
        assertFails { generationLimitedCloningExclusions(report.copy(files = listOf(file.copy(failures = listOf("invalid action"))))) }
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
        Files.writeString(output.resolve("population-manifest.json"), "synthetic population")
        Files.writeString(output.resolve("population-validation.json"), "synthetic population validation")
        val identity = finalizeCalibrationCloningAdmission(output, lineage, evidenceJson.encodeToString(plan))
        assertEquals(8, ResearchRunArtifacts.loadAndVerify(output, identity).artifacts.size)
        Files.writeString(output.resolve("lineage.json"), evidenceJson.encodeToString(lineage.copy(
            wholePairGroupByGame = mapOf("game" to "another-pair"))))
        assertFails { ResearchRunArtifacts.loadAndVerify(output, identity) }
    }
}
