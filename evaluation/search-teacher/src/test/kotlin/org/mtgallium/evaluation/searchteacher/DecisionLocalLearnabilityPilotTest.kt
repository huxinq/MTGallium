package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunCheckpoints

@org.junit.jupiter.api.Tag("public-source")
class DecisionLocalLearnabilityPilotTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var temporary: Path

    @Test
    fun `historical prefixed parent and unprefixed precision envelopes reach combination with lineage intact`() {
        val old = root("historical", DecisionLocalSplit.TRAIN, 8)
        val parent = temporary.resolve("parent")
        val precision = temporary.resolve("precision")
        val oldPath = "roots/train-historical.json"
        val freshPath = "roots/historical.json"
        val oldEnvelope = ResearchRunCheckpoints.persist(parent.resolve(oldPath), DECISION_LOCAL_PRECISION_PARENT,
            DECISION_LOCAL_ROOT_CHECKPOINT_SCHEMA, 1L, evidenceJson.encodeToString(old).encodeToByteArray())
        val fresh = fresh(old).copy(originalCheckpointPayloadSha256 = oldEnvelope.payloadSha256)
        val freshEnvelope = ResearchRunCheckpoints.persist(precision.resolve(freshPath), DECISION_LOCAL_LEARNABILITY_PRECISION,
            "decision-local-precision-root-v1", 1L, evidenceJson.encodeToString(fresh).encodeToByteArray())
        ResearchRunArtifacts(parent, DECISION_LOCAL_PRECISION_PARENT).also { it.register(oldPath); it.finalize() }
        ResearchRunArtifacts(precision, DECISION_LOCAL_LEARNABILITY_PRECISION).also { it.register(freshPath); it.finalize() }
        ResearchRunArtifacts.loadAndVerify(parent, DECISION_LOCAL_PRECISION_PARENT)
        ResearchRunArtifacts.loadAndVerify(precision, DECISION_LOCAL_LEARNABILITY_PRECISION)
        val (loadedOld, loadedFresh) = loadLearnabilityRootCheckpoints(parent, precision, old.rootId, old.split, old.pairIndex)
        assertEquals(oldEnvelope.payloadSha256, loadedOld.payloadSha256)
        assertEquals(freshEnvelope.payloadSha256, loadedFresh.payloadSha256)
        val decodedOld = evidenceJson.decodeFromString<DecisionLocalRootEvidence>(loadedOld.payload().decodeToString())
        val decodedFresh = evidenceJson.decodeFromString<PrecisionRootSamples>(loadedFresh.payload().decodeToString())
        assertEquals(loadedOld.payloadSha256, decodedFresh.originalCheckpointPayloadSha256)
        assertEquals(32, combineLearnabilityRoot(decodedOld, decodedFresh).candidates.first().primaryTerminalPayoffs.size)
        assertFailsWith<IllegalArgumentException> {
            loadLearnabilityRootCheckpoints(parent, precision, old.rootId, old.split, pairIndex = 2)
        }
    }

    @Test
    fun `offline cli needs only retained evidence and refuses challenges`() {
        val args = arrayOf("--suite", "decision-local-learnability-pilot", "--precision-parent", "old",
            "--precision-run", "fresh", "--output", "out")
        val options = SearchTeacherCli.parse(args)
        assertNull(options.deckManifest)
        assertEquals("decision-local-learnability-pilot", options.suite)
        assertFailsWith<IllegalArgumentException> { SearchTeacherCli.parse(args + arrayOf("--challenge-manifest", "test")) }
        assertFailsWith<IllegalArgumentException> { SearchTeacherCli.parse(args.take(4).toTypedArray()) }
    }

    @Test
    fun `merge appends fresh labels exactly once and retains old features`() {
        val old = root("old", DecisionLocalSplit.TRAIN, 8)
        val fresh = fresh(old)
        val merged = combineLearnabilityRoot(old, fresh)
        assertEquals(32, merged.primaryReplicates)
        assertEquals(0, merged.independentReplicates)
        assertEquals(old.candidates[0].featureMeans, merged.candidates[0].featureMeans)
        assertEquals(old.candidates[0].primaryTerminalPayoffs + List(24) { -1.0 }, merged.candidates[0].primaryTerminalPayoffs)
        assertTrue(merged.candidates.all { it.independentTerminalPayoffs.isEmpty() })
        assertFailsWith<IllegalArgumentException> { combineLearnabilityRoot(merged, fresh) }
        assertFailsWith<IllegalArgumentException> { combineLearnabilityRoot(old.copy(split = DecisionLocalSplit.TEST), fresh) }
    }

    @Test
    fun `missing duplicate reordered or failed fresh samples cannot become labels`() {
        val old = root("old", DecisionLocalSplit.TRAIN, 8)
        val fresh = fresh(old)
        val candidate = fresh.candidates.first()
        listOf(candidate.samples.dropLast(1), candidate.samples.reversed(),
            candidate.samples.dropLast(1) + candidate.samples.first()).forEach { bad ->
            assertFailsWith<IllegalArgumentException> {
                combineLearnabilityRoot(old, fresh.copy(candidates = listOf(candidate.copy(samples = bad)) + fresh.candidates.drop(1)))
            }
        }
        assertFailsWith<IllegalArgumentException> { combineLearnabilityRoot(old, fresh.copy(failure = "timeout")) }
        assertFailsWith<IllegalArgumentException> { combineLearnabilityRoot(old, fresh.copy(candidates = fresh.candidates.reversed())) }
    }

    @Test
    fun `validation targets and features never affect fitted model`() {
        val train = root("train", DecisionLocalSplit.TRAIN)
        val validation = root("validation", DecisionLocalSplit.VALIDATION)
        val fitted = fitLearnabilityModel(listOf(train, validation))
        val changedValidation = validation.copy(candidates = validation.candidates.map {
            it.copy(featureMeans = mapOf("validation-only" to 1e6), primaryTerminalPayoffs = it.primaryTerminalPayoffs.map { p -> -p })
        })
        assertEquals(fitted, fitLearnabilityModel(listOf(train, changedValidation)))
        assertTrue("validation-only" !in fitted.weights)
        val changedTrain = train.copy(candidates = train.candidates.map { it.copy(primaryTerminalPayoffs = it.primaryTerminalPayoffs.map { p -> -p }) })
        assertTrue(fitted.modelId != fitLearnabilityModel(listOf(changedTrain, validation)).modelId)
        val diagnostic = fitLearnabilityModel(listOf(train, validation), ridge = 1.0)
        assertEquals(1.0, diagnostic.regularization)
        assertEquals(fitDecisionLocalModel(listOf(train), ridge = 1.0), diagnostic)
        assertEquals(diagnostic, fitLearnabilityModel(listOf(train, changedValidation), ridge = 1.0))
    }

    @Test
    fun `observed ties stay in payoff summaries but supply no ordering evidence`() {
        val train = root("train", DecisionLocalSplit.TRAIN)
        val tied = root("tied", DecisionLocalSplit.VALIDATION).let { r ->
            r.copy(candidates = r.candidates.map { it.copy(primaryTerminalPayoffs = List(32) { 1.0 }) })
        }
        val result = evaluateLearnabilityRoot(tied, fitLearnabilityModel(listOf(train)))
        assertEquals(0, result.nonTiedObservedPairs)
        assertEquals(1, result.tiedObservedPairs)
        val summaries = summarizeLearnability(listOf(result))
        summaries.forEach {
            assertEquals(1, it.roots)
            assertEquals(0, it.rootsWithNonTiedPairs)
            assertNull(it.rootMeanNonTiedPairAccuracy)
            assertEquals(0.0, it.rootMeanObservedBestRegret)
        }
    }

    @Test
    fun `selection uses scores while paired payoff uses matched retained outcomes`() {
        val train = root("train", DecisionLocalSplit.TRAIN)
        val model = fitLearnabilityModel(listOf(train))
        val result = evaluateLearnabilityRoot(root("validation", DecisionLocalSplit.VALIDATION), model)
        val learned = result.methods.single { it.method == "decision-local-model" }
        val cheap = result.methods.single { it.method == "cheap-visible-heuristic" }
        assertEquals(listOf("a"), learned.selectedSignatures)
        assertEquals(listOf("b"), cheap.selectedSignatures)
        assertEquals(1.0, learned.nonTiedPairAccuracy)
        assertEquals(0.0, cheap.nonTiedPairAccuracy)
        assertEquals(2.0, result.modelMinusCheapPairedPayoff)
        assertEquals(0.0, result.modelMinusCheapPairedStandardError)
        assertEquals(0.5, result.methods.single { it.method == "uniform" }.nonTiedPairAccuracy)
    }

    private fun root(id: String, split: DecisionLocalSplit, count: Int = 32) = DecisionLocalRootEvidence(
        rootId = id, split = split, pairIndex = if (split == DecisionLocalSplit.TRAIN) 1 else 2,
        decisionFamily = "CAST_SPELL", phase = "PRECOMBAT_MAIN", turnNumber = 2, rootActor = "p0",
        representedKnowledgeCategory = "represented-history", candidateFamilyDigest = "family", productionScheduleDigest = "schedule",
        primaryReplicates = count, independentReplicates = 0,
        candidates = listOf(candidate("a", 1.0, count), candidate("b", -1.0, count)),
    )

    private fun candidate(signature: String, value: Double, count: Int) = DecisionLocalCandidateEvidence(
        signature = signature, featureWorlds = 64, nonterminalFeatureWorlds = 64, terminalFeatureWorlds = 0,
        terminalFeatureOffset = 0.0, featureMeans = mapOf("x" to value), featureScheduleDigest = "feature-$signature",
        cheapHeuristicScore = -value, failedGlobalModelScore = 0.0,
        primaryTerminalPayoffs = List(count) { value }, independentTerminalPayoffs = emptyList(),
        continuationPolicyDecisions = count, continuationRuntimeMillis = 1.0,
    )

    private fun fresh(old: DecisionLocalRootEvidence) = PrecisionRootSamples(old.rootId, "parent-hash",
        old.candidates.map { c -> PrecisionCandidateSamples(c.signature, decisionLocalPrecisionReplicates.map { i ->
            DecisionLocalTerminalSample(i, 0, 1L, 2L, -c.primaryMean, 1, 1.0)
        }) })
}
