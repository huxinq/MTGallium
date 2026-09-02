package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyAudience
import org.mtgallium.agent.infoset.core.PolicyAudienceScope
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyHistoryEventKind
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyBehaviorBinding
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.infoset.core.PolicyTrajectoryForcedTransition
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryCompletion
import org.mtgallium.agent.infoset.core.PolicyTrajectoryOutcome
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord
import org.mtgallium.agent.infoset.core.PolicyTrajectoryStopReason
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceLocation
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

class PublicCorpusValidatorTest {
    @Test
    fun `streams a valid public-only trajectory`() {
        val fixture = fixture(listOf(header(), outcome()))
        val report = PublicCorpusValidator(fixture.root, emptyMap()).validate(fixture.manifest)

        assertTrue(report.passed, report.failures.toString())
        assertTrue(report.files.single().passed)
    }

    @Test
    fun `rejects a well-formed trajectory that stopped before the game ended`() {
        val stopped = outcome().copy(
            completion = PolicyTrajectoryCompletion.STOPPED_BEFORE_GAME_END,
            stopReason = PolicyTrajectoryStopReason.GAME_DECISION_LIMIT_REACHED,
            resultByPlayer = null,
        )
        val fixture = fixture(listOf(header(), stopped))

        val report = PublicCorpusValidator(fixture.root, emptyMap()).validate(fixture.manifest)

        assertFalse(report.passed)
        assertTrue(report.failures.any { "stopped before it ended" in it })
    }

    @Test
    fun `rejects a terminal corpus entry with an incomplete represented-information ledger`() {
        val fixture = fixture(listOf(header(), outcome()))
        val original = evidenceJson.decodeFromString<CorpusManifest>(Files.readString(fixture.manifest))
        val entries = original.entries.map { entry ->
            entry.copy(
                game = entry.game.copy(
                    informationLedgerComplete = false,
                    unsupportedInformationEvents = listOf("UNREPRESENTED:MysteryEvent"),
                ),
            )
        }
        val identity = CorpusManifest.computeDatasetIdentity(
            profileId = original.profileId,
            profileHash = original.profileHash,
            sourceProvenance = original.sourceProvenance,
            requestedGames = original.requestedGames,
            terminalGames = original.terminalGames,
            replayVerifiedGames = original.replayVerifiedGames,
            entries = entries,
            passed = original.passed,
        )
        Files.writeString(fixture.manifest, evidenceJson.encodeToString(original.copy(entries = entries, datasetIdentity = identity)))

        val report = PublicCorpusValidator(fixture.root, emptyMap()).validate(fixture.manifest)

        assertFalse(report.passed)
        assertTrue(report.failures.any { "incomplete represented-information ledger" in it })
    }

    @Test
    fun `rejects stale manifests tampered hashes path escapes and malformed streams`() {
        val valid = fixture(listOf(header(), outcome()))
        val staleJson = Files.readString(valid.manifest).replaceFirst("\"schemaVersion\": 5", "\"schemaVersion\": 4")
        Files.writeString(valid.manifest, staleJson)
        assertFailsWith<IllegalArgumentException> {
            PublicCorpusValidator(valid.root, emptyMap()).validate(valid.manifest)
        }

        val wrongHash = fixture(listOf(header(), outcome()), sha = "0".repeat(64))
        assertFalse(PublicCorpusValidator(wrongHash.root, emptyMap()).validate(wrongHash.manifest).passed)

        val escaped = fixture(listOf(header(), outcome()), trajectoryOverride = "../outside.jsonl.gz")
        assertFalse(PublicCorpusValidator(escaped.root, emptyMap()).validate(escaped.manifest).passed)

        val wrongGame = fixture(listOf(header(), outcome().copy(gameId = "other")))
        assertFalse(PublicCorpusValidator(wrongGame.root, emptyMap()).validate(wrongGame.manifest).passed)

        val gap = PolicyTrajectoryForcedTransition(
            gameId = "g1",
            afterDecisionIndex = 0,
            events = listOf(
                PolicyHistoryEvent(
                    eventId = 1,
                    audience = PolicyAudience(PolicyAudienceScope.PUBLIC),
                    actor = "p0",
                    kind = PolicyHistoryEventKind.ACTION,
                    payload = buildJsonObject { },
                )
            ),
        )
        val noncontiguous = fixture(listOf(header(), gap, outcome().copy(decisions = 1, semanticResponseSequence = listOf(null))))
        assertFalse(PublicCorpusValidator(noncontiguous.root, emptyMap()).validate(noncontiguous.manifest).passed)

        val trailing = fixture(listOf(header(), outcome(), outcome()))
        assertFalse(PublicCorpusValidator(trailing.root, emptyMap()).validate(trailing.manifest).passed)
    }

    @Test
    fun `dataset identity rejects a changed policy commitment before any trajectory is consumed`() {
        val valid = fixture(listOf(header(), outcome()))
        val encoded = Files.readString(valid.manifest)
        val manifest = evidenceJson.decodeFromString<CorpusManifest>(encoded)
        val identity = requireNotNull(manifest.entries.single().policyEvidenceIdentity)
        val replacement = identity.dropLast(1) + if (identity.last() == '0') "1" else "0"
        Files.writeString(valid.manifest, encoded.replace(identity, replacement))

        val failure = assertFailsWith<IllegalArgumentException> {
            PublicCorpusValidator(valid.root, emptyMap()).validate(valid.manifest)
        }
        assertTrue("dataset identity" in failure.message.orEmpty())
    }

    private fun fixture(
        records: List<PolicyTrajectoryRecord>,
        sha: String? = null,
        trajectoryOverride: String? = null,
    ): Fixture {
        val root = createTempDirectory("public-corpus-validator")
        val relative = EvidenceLocation.LATEST.relativePath("corpus/v5/public/g1.jsonl.gz")
        val trajectory = root.resolve(relative)
        Files.createDirectories(trajectory.parent)
        GZIPOutputStream(Files.newOutputStream(trajectory)).bufferedWriter().use { writer ->
            records.forEach { record ->
                writer.write(PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), record))
                writer.newLine()
            }
        }
        val trajectoryHeader = records.firstOrNull() as? PolicyTrajectoryHeader
        val sourceProvenance = header().behaviorBinding.sourceProvenance
        val entries = listOf(
            CorpusEntry(
                    gameId = "g1",
                    publicTrajectory = trajectoryOverride ?: relative,
                    publicSha256 = sha ?: sha256File(trajectory),
                    publicSizeBytes = Files.size(trajectory),
                    policyEvidenceIdentity = trajectoryHeader?.behaviorBinding?.identity,
                    behaviorSpecificationSha256 =
                        trajectoryHeader?.behaviorBinding?.behaviorSpecificationSha256,
                    replayVerified = true,
                    game = CorpusGameSummary(
                        gameId = "g1",
                        p0Policy = ArenaPolicyKind.SEARCH,
                        p1Policy = ArenaPolicyKind.HEURISTIC,
                        winner = null,
                        terminal = true,
                        disposition = GameRunDisposition.GAME_ENDED,
                        decisions = 0,
                        searchSeat = "p0",
                        searchScore = 0.5,
                        illegalResponses = 0,
                        fallbacks = 0,
                        stepLimit = false,
                    ),
                )
        )
        val datasetIdentity = CorpusManifest.computeDatasetIdentity(
            profileId = "deep-teacher-v1",
            profileHash = "profile",
            sourceProvenance = sourceProvenance,
            requestedGames = 1,
            terminalGames = 1,
            replayVerifiedGames = 1,
            entries = entries,
            passed = true,
        )
        val manifest = CorpusManifest(
            generatedAtUtc = "2026-08-24T00:00:00Z",
            profileId = "deep-teacher-v1",
            profileHash = "profile",
            outerCommit = "outer",
            argentumCommit = "argentum",
            sourceProvenance = sourceProvenance,
            requestedGames = 1,
            terminalGames = 1,
            replayVerifiedGames = 1,
            entries = entries,
            passed = true,
            datasetIdentity = datasetIdentity,
        )
        val manifestPath = EvidenceStore(root).latest("corpus/v5/manifest.json")
        Files.writeString(manifestPath, evidenceJson.encodeToString(manifest))
        return Fixture(root, manifestPath)
    }

    private fun header(): PolicyTrajectoryHeader {
        val empty = PolicyJson.sha256("")
        val binding = PolicyBehaviorBinding.create(
            behaviorIdentity = "policy",
            behaviorSpecification = buildJsonObject { put("implementation", "test") },
            sourceProvenance = PolicySourceProvenance(
                expectedArgentumRevision = "argentum",
                outer = PolicySourceTreeState("outer", empty, empty, empty),
                argentum = PolicySourceTreeState("argentum", empty, empty, empty),
            ),
        )
        return PolicyTrajectoryHeader(
            gameId = "g1",
            createdAtUtc = "2026-08-24T00:00:00Z",
            outerCommit = "outer",
            argentumCommit = "argentum",
            deckManifestHash = "deck",
            cardPoolHash = "pool",
            perspectivePlayerId = "p0",
            profileManifestHash = "profile",
            behaviorBinding = binding,
            policyVersion = binding.identity,
            evaluatorVersion = LeafEvaluator.MTGALLIUM_VISIBLE_V2.evaluatorId,
            leaf = LeafEvaluationConfig(
                LeafStateSource.CURRENT_INFORMATION_STATE,
                LeafEvaluator.MTGALLIUM_VISIBLE_V2,
            ),
            beliefVersion = "belief",
            opponentModelVersion = "opponent",
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

    private data class Fixture(val root: Path, val manifest: Path)
}
