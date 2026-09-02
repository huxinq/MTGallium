package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.mtgallium.agent.infoset.core.BOUNDED_POLICY_INPUT_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.POLICY_HISTORY_COMMITMENT_ALGORITHM
import org.mtgallium.agent.infoset.core.POLICY_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.TRAJECTORY_SCHEMA_CURRENT
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal class BaselineHardeningRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val deck: DeckManifest,
    private val profile: FrozenSearchProfile,
    private val baseSeed: Long,
) {
    fun run(corpusManifestPath: Path, games: Int, threads: Int): Path {
        val corpusHash = sha256File(corpusManifestPath)
        val profileHash = sha256(evidenceJson.encodeToString(profile))
        val outer = currentOuterCommit()
        val argentum = currentArgentumCommit()
        val runIdentity = sha256(
            listOf(
                outer, argentum, deck.deckHash(), deck.cardPoolHash(), profileHash, corpusHash,
                POLICY_SCHEMA_CURRENT, BOUNDED_POLICY_INPUT_SCHEMA_CURRENT, TRAJECTORY_SCHEMA_CURRENT,
                POLICY_HISTORY_COMMITMENT_ALGORITHM, games,
            ).joinToString("|"),
        ).take(24)
        val directory = EvidenceStore(root).diagnostic(
            "baseline-hardening/$runIdentity",
            "the corpus and policy-boundary remediation bundle",
        )
        Files.createDirectories(directory)
        val validation = PublicCorpusValidator(
            root,
            mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck),
        ).validate(corpusManifestPath)
        val boundary = PolicyBoundaryProfiler(root, registry, deck).run(corpusManifestPath)
        val conformance = InformationConformanceRunner(root, registry, deck, profile, baseSeed).run(games, threads)
        val reportValues = listOf(
            "corpus-validation" to validation,
            "policy-boundary" to boundary,
            "information-conformance" to conformance,
        )
        val artifacts = reportValues.map { (kind, value) ->
            val path = directory.resolve("$kind.json")
            val encoded = when (value) {
                is CorpusValidationReport -> evidenceJson.encodeToString(value)
                is PolicyBoundaryReport -> evidenceJson.encodeToString(value)
                is InformationConformanceReport -> evidenceJson.encodeToString(value)
                else -> error("Unknown hardening report")
            }
            writeTextAtomically(path, encoded + "\n")
            BaselineHardeningArtifact(kind, root.relativize(path).toString(), sha256File(path))
        }
        val passed = validation.passed && boundary.passed && conformance.passed &&
            validation.games >= 10 && validation.searchDecisions >= 100
        val manifest = BaselineHardeningManifest(
            generatedAtUtc = Instant.now().toString(),
            runIdentity = runIdentity,
            outerCommit = outer,
            argentumCommit = argentum,
            profileHash = profileHash,
            sourceCorpusHash = corpusHash,
            conformanceGames = games,
            artifacts = artifacts,
            passed = passed,
        )
        val path = directory.resolve("manifest.json")
        writeTextAtomically(path, evidenceJson.encodeToString(manifest) + "\n")
        return path
    }
}
