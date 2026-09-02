package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.BOUNDED_POLICY_INPUT_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord
import org.junit.jupiter.api.Tag

@Tag("scenario-execution")
class BehavioralCloningCanonicalReplayTest {
    @Test
    fun `one current frozen Mono-Red arena game replays and admits its Search Teacher decisions`() {
        System.getenv("MTGALLIUM_BC_CANONICAL_FIXTURE_ROOT")?.let { existingRoot ->
            val admitted = verifyAndAdmitExistingFixture(Path.of(existingRoot))
            assertTrue(admitted.examples.isNotEmpty())
            return
        }
        val root = createTempDirectory("bc-canonical-replay")
        val gameId = "bc-current-canonical-replay"
        val manifest = loadDeckManifest()
        val profile = SearchTeacherArena.smokeProfile()
        val profileHash = sha256(evidenceJson.encodeToString(profile))
        val provenance = currentSourceProvenance()
        val publicRelative = "corpus/v5/public/$gameId.jsonl.gz"
        val replayRelative = "corpus/v5/privileged/$gameId.privileged.replay.jsonl.gz"
        val publicPath = root.resolve(publicRelative)
        val replayPath = root.resolve(replayRelative)
        Files.createDirectories(publicPath.parent)
        Files.createDirectories(replayPath.parent)

        val game = SearchTeacherArena(
            registry = buildRegistry(),
            manifest = manifest,
            profile = profile,
            baseSeed = 13L,
        ).play(
            gameId = gameId,
            gameSeed = 117L,
            p0Policy = ArenaPolicyKind.SEARCH,
            p1Policy = ArenaPolicyKind.HEURISTIC,
            evidence = GameEvidenceOptions(
                publicTrajectory = publicPath,
                outerCommit = provenance.outer.revision,
                argentumCommit = provenance.argentum.revision,
                profileHash = profileHash,
                sourceProvenance = provenance,
            ),
            replay = GameReplayOptions(
                finalPath = replayPath,
                referencePath = replayRelative,
                runIdentity = "bc-current-canonical-replay-v1",
                outerCommit = provenance.outer.revision,
                argentumCommit = provenance.argentum.revision,
            ),
        )

        assertTrue(game.terminal, game.toString())
        assertTrue(game.replayVerified, game.replayVerificationDiagnostic ?: game.toString())
        assertTrue(CanonicalTournamentReplayVerifier.verify(replayPath).verified)
        assertEquals(0, game.illegalResponses)
        assertEquals(0, game.fallbacks)
        assertTrue(game.informationLedgerComplete, game.unsupportedInformationEvents.toString())

        val header = trajectoryHeader(publicPath)
        val entry = CorpusEntry(
            gameId = gameId,
            publicTrajectory = publicRelative,
            publicSha256 = sha256File(publicPath),
            publicSizeBytes = Files.size(publicPath),
            policyEvidenceIdentity = header.behaviorBinding.identity,
            behaviorSpecificationSha256 = header.behaviorBinding.behaviorSpecificationSha256,
            replayVerified = game.replayVerified,
            game = game.toCorpusGameSummary(),
        )
        val datasetIdentity = CorpusManifest.computeDatasetIdentity(
            profileId = profile.id,
            profileHash = profileHash,
            sourceProvenance = provenance,
            requestedGames = 1,
            terminalGames = 1,
            replayVerifiedGames = 1,
            entries = listOf(entry),
            passed = true,
        )
        val corpus = CorpusManifest(
            generatedAtUtc = Instant.EPOCH.toString(),
            profileId = profile.id,
            profileHash = profileHash,
            outerCommit = provenance.outer.revision,
            argentumCommit = provenance.argentum.revision,
            sourceProvenance = provenance,
            requestedGames = 1,
            terminalGames = 1,
            replayVerifiedGames = 1,
            entries = listOf(entry),
            passed = true,
            datasetIdentity = datasetIdentity,
        )
        val corpusPath = root.resolve("corpus/v5/manifest.json")
        Files.writeString(corpusPath, evidenceJson.encodeToString(corpus))

        val admitted = verifyAndAdmitExistingFixture(root)
        assertTrue(admitted.examples.isNotEmpty())
        assertTrue(admitted.examples.all { it.actingPlayerId == game.searchSeat })
        assertEquals(game.seatDiagnostics.getValue("p0").searchDecisions, admitted.examples.size)
    }

    private fun verifyAndAdmitExistingFixture(root: Path): BehavioralCloningAdmissionResult {
        val corpusPath = root.resolve("corpus/v5/manifest.json")
        val corpus = evidenceJson.decodeFromString<CorpusManifest>(Files.readString(corpusPath))
        val entry = corpus.entries.single()
        val replayPath = root.resolve(
            "corpus/v5/privileged/${entry.gameId}.privileged.replay.jsonl.gz"
        )
        assertTrue(CanonicalTournamentReplayVerifier.verify(replayPath).verified)
        assertTrue(entry.replayVerified)
        assertEquals(sha256File(root.resolve(entry.publicTrajectory)), entry.publicSha256)

        val scope = BehavioralCloningAdmissionScope.frozenMonoRed(
            loadDeckManifest(),
            SearchTeacherArena.smokeProfile(),
        )
        return BehavioralCloningAdmission(root, scope).extract(corpusPath).also { admitted ->
            assertTrue(admitted.passed, admitted.failures.toString())
            assertEquals(admitted.validation.searchDecisions, admitted.examples.size)
            assertTrue(admitted.examples.all {
                it.policyInput.schemaVersion == BOUNDED_POLICY_INPUT_SCHEMA_CURRENT &&
                    it.actingPlayerId == entry.game.searchSeat
            })
        }
    }

    private fun trajectoryHeader(path: Path): PolicyTrajectoryHeader = GZIPInputStream(
        Files.newInputStream(path)
    ).bufferedReader().use { reader ->
        PolicyJson.format.decodeFromString(
            PolicyTrajectoryRecord.serializer(),
            requireNotNull(reader.readLine()),
        ) as PolicyTrajectoryHeader
    }
}
