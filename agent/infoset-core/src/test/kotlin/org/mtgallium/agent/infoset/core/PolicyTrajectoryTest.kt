package org.mtgallium.agent.infoset.core

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mtgallium.research.run.ResearchRunArtifacts

class PolicyTrajectoryTest {
    @Test
    fun `public records round trip losslessly`() {
        val records: List<PolicyTrajectoryRecord> = listOf(
            header(),
            outcome(),
        )

        records.forEach { record ->
            val json = PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), record)
            assertEquals(record, PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(json))
        }
    }

    @Test
    fun `writer requires header and outcome and emits compressed jsonl`() {
        val bytes = ByteArrayOutputStream()
        val gzip = java.util.zip.GZIPOutputStream(bytes)
        PolicyTrajectoryWriter.from(gzip).use { writer ->
            writer.append(header())
            writer.append(outcome())
        }
        val text = GZIPInputStream(bytes.toByteArray().inputStream()).bufferedReader().readText()

        assertEquals(2, text.lineSequence().filter { it.isNotBlank() }.count())
        assertFailsWith<IllegalArgumentException> {
            PolicyTrajectoryWriter.from(ByteArrayOutputStream()).use { it.append(outcome()) }
        }
    }

    @Test
    fun `writer rejects cross-game records stale commitments and outcome count mismatch`() {
        assertFailsWith<IllegalArgumentException> {
            PolicyTrajectoryWriter.from(ByteArrayOutputStream()).use { writer ->
                writer.append(header())
                writer.append(outcome().copy(gameId = "other"))
            }
        }
        val event = PolicyHistoryEvent(
            eventId = 0,
            audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
            actor = "p0",
            kind = PolicyHistoryEventKind.ACTION,
            payload = buildJsonObject { },
        )
        assertFailsWith<IllegalArgumentException> {
            PolicyTrajectoryWriter.from(ByteArrayOutputStream()).use { writer ->
                writer.append(header())
                writer.append(PolicyTrajectoryForcedTransition(gameId = "g1", afterDecisionIndex = 0, events = listOf(event)))
                writer.append(decision())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyTrajectoryWriter.from(ByteArrayOutputStream()).use { writer ->
                writer.append(header())
                writer.append(outcome().copy(decisions = 1, semanticResponseSequence = listOf(null)))
            }
        }
    }

    @Test
    fun `writer rejects redundant runtime metadata that disagrees with the header or diagnostics`() {
        val tacticalLeaf = LeafEvaluationConfig(
            LeafStateSource.BOUNDED_ROLLOUT,
            LeafEvaluator.MTGALLIUM_TACTICAL_V3,
        )
        val tacticalDecision = decision().copy(
            evaluatorVersion = tacticalLeaf.evaluator.evaluatorId,
            leaf = tacticalLeaf,
            searchDiagnostics = decision().searchDiagnostics.copy(leaf = tacticalLeaf),
        )
        val mismatches = listOf(
            tacticalDecision,
            decision().copy(actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1),
            decision().copy(beliefVersion = "snapshot_a_v1:policy_conditioned_v1"),
            decision().copy(opponentModelVersion = "other-opponent"),
        )

        mismatches.forEach { mismatched ->
            val writer = PolicyTrajectoryWriter.from(ByteArrayOutputStream())
            writer.append(header())
            assertFailsWith<IllegalArgumentException> { writer.append(mismatched) }
        }
        val writer = PolicyTrajectoryWriter.from(ByteArrayOutputStream())
        assertFailsWith<IllegalArgumentException> {
            writer.append(header().copy(evaluatorVersion = LeafEvaluator.MTGALLIUM_TACTICAL_V3.evaluatorId))
        }
    }

    @Test
    fun `affected current-schema trajectory metadata remains decodable for historical interpretation`() {
        val actualLeaf = LeafEvaluationConfig(
            LeafStateSource.BOUNDED_ROLLOUT,
            LeafEvaluator.MTGALLIUM_TACTICAL_V3,
        )
        val staleHeader = header()
        val actualDecision = decision().copy(
            evaluatorVersion = actualLeaf.evaluator.evaluatorId,
            leaf = actualLeaf,
            searchDiagnostics = decision().searchDiagnostics.copy(leaf = actualLeaf),
        )

        val decodedHeader = PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(
            PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), staleHeader),
        ) as PolicyTrajectoryHeader
        val decodedDecision = PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(
            PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), actualDecision),
        ) as PolicyTrajectoryDecision

        assertEquals(TRAJECTORY_SCHEMA_CURRENT, decodedHeader.schemaVersion)
        assertEquals(LeafEvaluator.MTGALLIUM_VISIBLE_V2, decodedHeader.leaf.evaluator)
        assertEquals(LeafEvaluator.MTGALLIUM_TACTICAL_V3, decodedDecision.searchDiagnostics.leaf.evaluator)
    }

    @Test
    fun `unknown schemas fail closed`() {
        assertFailsWith<IllegalArgumentException> { header().copy(schemaVersion = 1) }
        assertFailsWith<IllegalArgumentException> { header().copy(schemaVersion = TRAJECTORY_SCHEMA_V9) }
        assertFailsWith<IllegalArgumentException> { outcome().copy(schemaVersion = 2) }
    }

    @Test
    fun `outcome masks a private response without serializing its identity`() {
        val privateChoice = SemanticChoice.create(
            kind = SemanticChoiceKind.DECISION,
            operationFamily = SemanticOperationFamily.DECISION_RESPONSE,
            display = SemanticChoiceDisplay("BottomCards"),
            canonicalPayload = buildJsonObject {
                put("choice", kotlinx.serialization.json.JsonPrimitive("private-card-identity"))
            },
        )
        val outcome = outcome().copy(
            decisions = 2,
            semanticResponseSequence = listOf(privateChoice, null),
        )
        val encoded = PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), outcome)
        val decoded = PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(encoded) as PolicyTrajectoryOutcome

        assertEquals(privateChoice, decoded.semanticResponseSequence[0])
        assertEquals(null, decoded.semanticResponseSequence[1])
        assertEquals(1, "private-card-identity".toRegex().findAll(encoded).count())
    }

    @Test
    fun `public current header contains no replay seed`() {
        val encoded = PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), header())

        assertFalse("gameSeed" in encoded)
        assertFalse("searchBaseSeed" in encoded)
        assertEquals(TRAJECTORY_SCHEMA_CURRENT, header().schemaVersion)
    }

    @Test
    fun `planner sidecar round trips binds the exact safe trajectory and rejects private references`() {
        val sidecar = PlannerEvidenceSidecar(
            binding = PlannerEvidenceBinding(
                gameId = "g1",
                safeTrajectoryReference = "public/g1.jsonl.gz",
                safeTrajectorySha256 = "a".repeat(64),
                trajectorySchemaVersion = TRAJECTORY_SCHEMA_CURRENT,
                candidateSchemaVersion = CANDIDATE_SCHEMA_CURRENT,
                behaviorBinding = header().behaviorBinding,
                actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
                researchRunIdentity = "research-run-v1-sha256:test",
            ),
            decisions = listOf(
                PlannerEvidenceDecision(
                    gameId = "g1",
                    decisionIndex = 0,
                    actingPlayerId = "p0",
                    informationStateDigest = "safe-information-digest",
                    selectedCandidateSignature = "choice-a",
                    candidates = listOf(
                        PlannerEvidenceCandidate(
                            candidateSignature = "choice-a",
                            rawVisits = 1,
                            backedMean = 0.5,
                            settlementCounts = SearchSettlementCounts(heuristicSettlementBackups = 1),
                        ),
                    ),
                    work = PlannerEvidenceWork(1.0, 1, 2, 0, 0, 0),
                ),
            ),
        )
        val path = createTempDirectory("planner-evidence").resolve("sidecar.json.gz")
        sidecar.writeCompressed(path)
        assertEquals(sidecar, PlannerEvidenceSidecar.readCompressed(path))
        ResearchRunArtifacts(path.parent, "research-run-v1-sha256:test").also { artifacts ->
            artifacts.register("sidecar.json.gz")
        }.finalize()
        assertEquals(
            listOf("sidecar.json.gz"),
            ResearchRunArtifacts.loadAndVerify(path.parent, "research-run-v1-sha256:test")
                .artifacts.map { it.relativePath },
        )

        assertFailsWith<IllegalArgumentException> {
            sidecar.copy(binding = sidecar.binding.copy(safeTrajectoryReference = "privileged/g1.jsonl.gz"))
        }
        assertFailsWith<IllegalArgumentException> {
            PlannerEvidenceCandidate(
                candidateSignature = "choice-a",
                rawVisits = 1,
                backedMean = 0.0,
                settlementCounts = SearchSettlementCounts(),
            )
        }
    }

    @Test
    fun `detached consumer verifies a supplied behavior specification without recovering it`() {
        val source = behaviorBinding("outer", "inner", "policy-v1").sourceProvenance
        val specification = buildJsonObject {
            put("implementation", "policy-v1")
            put("randomizationAuthority", "private-test-authority")
        }
        val binding = PolicyBehaviorBinding.create("policy-v1", specification, source)
        val encoded = PolicyJson.format.encodeToString(
            PolicyTrajectoryRecord.serializer(),
            header().copy(behaviorBinding = binding, policyVersion = binding.identity),
        )
        val decoded = PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(encoded)
            as PolicyTrajectoryHeader

        assertFalse("private-test-authority" in encoded)
        assertEquals(binding, decoded.behaviorBinding)
        assertEquals(
            decoded.behaviorBinding,
            PolicyBehaviorBinding.create("policy-v1", specification, source),
        )
        assertNotEquals(
            decoded.behaviorBinding,
            PolicyBehaviorBinding.create(
                "policy-v1",
                buildJsonObject {
                    put("implementation", "policy-v1")
                    put("randomizationAuthority", "different-private-test-authority")
                },
                source,
            ),
        )
    }

    @Test
    fun `a stopped trajectory states why it stopped and assigns no game result`() {
        val stopped = PolicyTrajectoryOutcome(
            gameId = "g1",
            decisions = 0,
            completion = PolicyTrajectoryCompletion.STOPPED_BEFORE_GAME_END,
            stopReason = PolicyTrajectoryStopReason.GAME_DECISION_LIMIT_REACHED,
            winnerId = null,
            resultByPlayer = null,
            semanticResponseSequence = emptyList(),
        )

        val encoded = PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), stopped)
        val decoded = PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(encoded) as PolicyTrajectoryOutcome

        assertEquals(PolicyTrajectoryCompletion.STOPPED_BEFORE_GAME_END, decoded.completion)
        assertEquals(PolicyTrajectoryStopReason.GAME_DECISION_LIMIT_REACHED, decoded.stopReason)
        assertEquals(null, decoded.resultByPlayer)
    }

    @Test
    fun `decision stores bounded input and a ledger cursor rather than a duplicated history`() {
        val decision = decision()
        val encoded = PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), decision)
        val decoded = PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(encoded) as PolicyTrajectoryDecision

        assertEquals(decision, decoded)
        assertEquals(0, decoded.historyCursor)
        assertTrue("\"policyInput\"" in encoded)
        assertFalse("\"history\":" in encoded)
        assertEquals(decision.policyInput.toInformationState(emptyList()), decoded.informationState(emptyList()))
    }

    @Test
    fun `runtime proposal seed is excluded from public decisions`() {
        val decision = decision().copy(expansion = decision().expansion.copy(proposalSeed = 117L))
        val encoded = PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), decision)
        val decoded = PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(encoded) as PolicyTrajectoryDecision

        assertFalse("seed" in encoded.lowercase())
        assertEquals(0L, decoded.expansion.proposalSeed)
    }

    @Test
    fun `public artifact privacy rejects seed keys and privileged paths`() {
        assertFailsWith<IllegalArgumentException> {
            PublicArtifactPrivacy.requireSafeJson("""{"searchSeed":117}""", "test")
        }
        assertFailsWith<IllegalArgumentException> {
            PublicArtifactPrivacy.requireSafeJson("""{"path":"review/privileged/truth.json"}""", "test")
        }
    }

    private fun header(): PolicyTrajectoryHeader {
        val binding = behaviorBinding("outer", "inner", "policy-v1")
        return PolicyTrajectoryHeader(
            gameId = "g1",
            createdAtUtc = "2026-08-22T00:00:00Z",
            outerCommit = "outer",
            argentumCommit = "inner",
            deckManifestHash = "deck",
            cardPoolHash = "cards",
            perspectivePlayerId = "p0",
            profileManifestHash = "profile",
            behaviorBinding = binding,
            policyVersion = binding.identity,
            evaluatorVersion = LeafEvaluator.MTGALLIUM_VISIBLE_V2.evaluatorId,
            leaf = LeafEvaluationConfig(
                LeafStateSource.CURRENT_INFORMATION_STATE,
                LeafEvaluator.MTGALLIUM_VISIBLE_V2,
            ),
            beliefVersion = "sequential_b_v1:consistency_only_v1",
            opponentModelVersion = "opponent-v1",
        )
    }

    private fun behaviorBinding(
        outerRevision: String,
        argentumRevision: String,
        behaviorIdentity: String,
    ): PolicyBehaviorBinding {
        val empty = PolicyJson.sha256("")
        return PolicyBehaviorBinding.create(
            behaviorIdentity = behaviorIdentity,
            behaviorSpecification = buildJsonObject { put("implementation", behaviorIdentity) },
            sourceProvenance = PolicySourceProvenance(
                expectedArgentumRevision = argentumRevision,
                outer = PolicySourceTreeState(outerRevision, empty, empty, empty),
                argentum = PolicySourceTreeState(argentumRevision, empty, empty, empty),
            ),
        )
    }

    private fun outcome() = PolicyTrajectoryOutcome(
        gameId = "g1",
        decisions = 0,
        completion = PolicyTrajectoryCompletion.GAME_ENDED,
        winnerId = null,
        resultByPlayer = mapOf("p0" to 0.0, "p1" to 0.0),
        semanticResponseSequence = emptyList(),
    )

    private fun decision(): PolicyTrajectoryDecision {
        val choice = SemanticChoice.create(
            kind = SemanticChoiceKind.ACTION,
            operationFamily = SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay("Pass"),
            canonicalPayload = buildJsonObject {
                put("choice", kotlinx.serialization.json.JsonPrimitive("pass"))
            },
        )
        val observation = PolicyObservation(
            perspectivePlayerId = "p0",
            turnNumber = 1,
            phase = "MAIN",
            step = "PRECOMBAT_MAIN",
            activePlayerId = "p0",
            priorityPlayerId = "p0",
            players = emptyList(),
            zones = emptyList(),
            stack = emptyList(),
            currentTurnStateComplete = true,
            pendingDecision = null,
            observationDigest = PolicyJson.sha256("observation"),
        )
        val historyCommitment = PolicyHistoryCommitment.empty()
        val information = PolicyInformationState(
            actingPlayerId = "p0",
            observation = observation,
            informationStateDigest = PolicyJson.sha256("information"),
            historyCommitment = historyCommitment,
            history = emptyList(),
            candidates = listOf(choice),
            terminated = false,
        )
        val belief = BeliefDiagnostics(
            mode = BeliefMode.CONSISTENCY_ONLY_V1,
            requestedParticles = 1,
            acceptedParticles = 1,
            rejectedParticles = 0,
            effectiveSampleSizeBefore = 1.0,
            effectiveSampleSizeAfter = 1.0,
            entropy = 0.0,
            resamplingCount = 0,
        )
        return PolicyTrajectoryDecision(
            gameId = "g1",
            decisionIndex = 0,
            actingPlayerId = "p0",
            policyVersion = header().policyVersion,
            evaluatorVersion = LeafEvaluator.MTGALLIUM_VISIBLE_V2.evaluatorId,
            leaf = LeafEvaluationConfig(
                LeafStateSource.CURRENT_INFORMATION_STATE,
                LeafEvaluator.MTGALLIUM_VISIBLE_V2,
            ),
            beliefVersion = "sequential_b_v1:consistency_only_v1",
            opponentModelVersion = "opponent-v1",
            policyInput = BoundedPolicyInputCompiler.compile(information),
            expansion = PolicyExpansion(listOf(choice), true, 1, "proposal-v1", 0),
            candidates = listOf(SearchCandidateStatistics(choice, 1, 0.0, 1.0)),
            chosen = choice,
            heuristicChoice = choice,
            rootValue = 0.0,
            beliefDiagnostics = belief,
            searchDiagnostics = InformationSetSearchDiagnostics(
                simulations = 1,
                particles = 1,
                nodes = 1,
                maximumDepth = 1,
                exhaustiveNodes = 1,
                nonExhaustiveNodes = 0,
                wideningEvents = 0,
                opponentModelId = "opponent-v1",
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
            ),
        )
    }
}
