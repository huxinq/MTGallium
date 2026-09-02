package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.replay.CanonicalReplayHeader
import com.wingedsheep.engine.replay.CanonicalReplayTransition
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.PolicyInspectionBundle
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

@Serializable
data class ReplayReviewDraftSource(
    val runIdentity: String,
    val gameId: String,
    val perspectivePlayerId: String,
    val safeBundleSha256: String,
    val outerCommit: String,
    val argentumCommit: String,
    val frameIndex: Int,
    val decisionIndex: Int,
    val informationStateDigest: String,
    val historyCommitment: ReplayReviewHistoryCommitment,
    val candidateSchemaVersion: Int,
    val proposalVersion: String,
    val chosenSignature: String,
)

@Serializable
data class ReplayReviewHistoryCommitment(
    val algorithm: String,
    val cursor: Int,
    val digest: String,
)

@Serializable
data class ReplayReviewDraftCandidate(
    val signature: String,
    val label: String,
    val grade: ReplayReviewDecisionGrade?,
)

@Serializable
internal data class ReplayReviewDecisionDraft(
    val schemaVersion: Int,
    val documentKind: String,
    val source: ReplayReviewDraftSource,
    val reviewerId: String,
    val reviewerJudgment: String,
    val candidates: List<ReplayReviewDraftCandidate>,
    val createdAtUtc: String,
    val updatedAtUtc: String,
)

/**
 * Privileged, work-only case created by the trusted intake command. The browser never receives this
 * document: its seeds and exact semantic prefix exist solely to reconstruct the reviewed position.
 */
@Serializable
internal data class AuthenticatedReplayReviewDecisionCase(
    val schemaVersion: Int = 1,
    val documentKind: String = "authenticated-replay-review-decision-case-v1",
    val id: String,
    val source: ReplayReviewDraftSource,
    val safeBundleSha256: String,
    val canonicalReplaySha256: String,
    val gameSeed: Long,
    val searchBaseSeed: Long,
    val startingPlayerIndex: Int,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val semanticPrefix: List<SemanticChoice>,
    val candidates: List<ReplayReviewDraftCandidate>,
    val reviewerId: String,
    val reviewerJudgment: String,
    val authenticatedAtUtc: String,
    val intakeBindingSha256: String,
) {
    init {
        require(schemaVersion == 1 && documentKind == "authenticated-replay-review-decision-case-v1")
        require(id.isNotBlank() && reviewerId.isNotBlank() && reviewerJudgment.isNotBlank())
        require(startingPlayerIndex in 0..1)
        require(safeBundleSha256.matches(Regex("[0-9a-f]{64}")))
        require(canonicalReplaySha256.matches(Regex("[0-9a-f]{64}")))
        require(intakeBindingSha256.matches(Regex("[0-9a-f]{64}")))
        require(candidates.isNotEmpty() && candidates.all { it.grade != null })
        require(candidates.map { it.signature }.distinct().size == candidates.size)
        require(candidates.any { it.grade == ReplayReviewDecisionGrade.PREFERRED })
        require(candidates.any { it.grade == ReplayReviewDecisionGrade.UNACCEPTABLE })
    }

    fun requireIntakeBinding() {
        require(intakeBindingSha256 == expectedIntakeBinding()) {
            "Authenticated replay-review case changed after trusted intake"
        }
    }

    fun expectedIntakeBinding(): String = sha256(
        evidenceJson.encodeToString(
            AuthenticatedReplayReviewDecisionCase.serializer(),
            copy(intakeBindingSha256 = ZERO_SHA256),
        ),
    )

    private companion object {
        val ZERO_SHA256: String = "0".repeat(64)
    }
}

internal object ReplayReviewDecisionIntake {
    fun authenticate(
        root: Path,
        draftPath: Path,
        safeBundlePath: Path,
        canonicalReplayPath: Path,
        outputPath: Path,
        registry: CardRegistry,
        manifest: DeckManifest,
    ): AuthenticatedReplayReviewDecisionCase {
        listOf(draftPath, safeBundlePath, canonicalReplayPath).forEach { path ->
            require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                "Replay-review intake input is not a regular non-link file: $path"
            }
        }
        val output = EvidenceStore(root).requireDiagnosticOutput(
            outputPath,
            "the authenticated replay-review decision case",
        )
        val draft = evidenceJson.decodeFromString<ReplayReviewDecisionDraft>(Files.readString(draftPath))
        require(draft.schemaVersion == 1 && draft.documentKind == "replay-review-decision-draft-v1")
        require(draft.reviewerId.isNotBlank() && draft.reviewerJudgment.isNotBlank())
        require(draft.candidates.isNotEmpty() && draft.candidates.all { it.grade != null })
        require(draft.candidates.map { it.signature }.distinct().size == draft.candidates.size)
        require(draft.candidates.any { it.grade == ReplayReviewDecisionGrade.PREFERRED })
        require(draft.candidates.any { it.grade == ReplayReviewDecisionGrade.UNACCEPTABLE })

        val safeSha = sha256File(safeBundlePath)
        require(safeSha == draft.source.safeBundleSha256) { "Safe inspection SHA-256 does not match the draft" }
        val safe = evidenceJson.decodeFromString<PolicyInspectionBundle>(Files.readString(safeBundlePath))
        require(safe.gameId == draft.source.gameId)
        require(safe.perspectivePlayerId == draft.source.perspectivePlayerId)
        require(safe.outerCommit == draft.source.outerCommit && safe.argentumCommit == draft.source.argentumCommit)
        val frame = safe.frames.getOrNull(draft.source.frameIndex)
            ?: error("Draft frame is absent from the safe inspection")
        val search = requireNotNull(frame.search) { "Draft frame has no recorded search decision" }
        require(frame.actingPlayerId == safe.perspectivePlayerId)
        require(frame.frameIndex == draft.source.decisionIndex && search.decisionIndex == draft.source.decisionIndex)
        require(frame.informationStateDigest == draft.source.informationStateDigest)
        require(frame.historyCommitment.algorithm == draft.source.historyCommitment.algorithm)
        require(frame.historyCommitment.cursor == draft.source.historyCommitment.cursor)
        require(frame.historyCommitment.digest == draft.source.historyCommitment.digest)
        require(frame.candidateSchemaVersion == draft.source.candidateSchemaVersion)
        require(search.expansion.isProfileExhaustive) {
            "Safe inspection candidate expansion is not exhaustive for its declared policy profile"
        }
        require(search.expansion.proposalVersion == draft.source.proposalVersion)
        require(search.chosen.signature == draft.source.chosenSignature)
        val safeCandidates = search.expansion.candidates.map { it.signature to it.display.label }
        require(safeCandidates == draft.candidates.map { it.signature to it.label }) {
            "Draft candidates differ from the safe inspection's exact ordered candidate set"
        }

        val verification = CanonicalTournamentReplayVerifier.verify(canonicalReplayPath)
        require(verification.verified) { "Canonical replay verification failed: ${verification.diagnostic}" }
        val records = readCanonicalReplay(canonicalReplayPath)
        val header = records.firstOrNull() as? CanonicalReplayHeader
            ?: error("Canonical replay does not begin with a header")
        require(header.gameId == draft.source.gameId)
        val runIdentity = header.extensionString("mtgallium.runIdentity")
        val outerCommit = header.extensionString("mtgallium.outerCommit")
        val argentumCommit = header.extensionString("mtgallium.argentumCommit")
        require(runIdentity == draft.source.runIdentity)
        require(outerCommit == draft.source.outerCommit && argentumCommit == draft.source.argentumCommit)
        require(header.extensionString("mtgallium.deckHash") == manifest.deckHash())
        require(header.extensionString("mtgallium.cardPoolHash") == manifest.cardPoolHash())
        val gameSeed = header.extensionLong("mtgallium.gameSeed")
        val baseSeed = header.extensionLong("mtgallium.baseSeed")
        val choices = records.filterIsInstance<CanonicalReplayTransition>().mapNotNull { transition ->
            val decision = (transition.extensions["mtgallium.decisionIndex"] as? JsonPrimitive)?.content?.toInt()
                ?: return@mapNotNull null
            val choice = requireNotNull(transition.extensions["mtgallium.semanticChoice"])
            decision to PolicyJson.format.decodeFromJsonElement(SemanticChoice.serializer(), choice)
        }
        require(choices.map { it.first } == choices.indices.toList()) { "Canonical replay decision indices are not contiguous" }
        require(draft.source.decisionIndex in choices.indices) { "Reviewed decision is absent from canonical replay" }

        val profile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1
        val world = reconstructReplayReviewWorld(
            registry, manifest, draft.source.gameId, gameSeed, baseSeed, 0, profile,
            choices.take(draft.source.decisionIndex).map { it.second },
        )
        require(world.actorToAct() == draft.source.perspectivePlayerId)
        val information = world.informationState(draft.source.perspectivePlayerId)
        require(information.informationStateDigest == draft.source.informationStateDigest) {
            "Trusted replay reconstruction does not match the safe information-state digest"
        }
        val expansion = world.expandChoices()
        require(expansion.isProfileExhaustive && expansion.proposalVersion == draft.source.proposalVersion)
        require(expansion.candidates.map { it.signature } == draft.candidates.map { it.signature }) {
            "Trusted replay reconstruction candidates do not match the safe draft"
        }
        require(choices[draft.source.decisionIndex].second.signature == draft.source.chosenSignature)

        val unsigned = AuthenticatedReplayReviewDecisionCase(
            id = "${draft.source.gameId}-decision-${draft.source.decisionIndex}",
            source = draft.source,
            safeBundleSha256 = safeSha,
            canonicalReplaySha256 = sha256File(canonicalReplayPath),
            gameSeed = gameSeed,
            searchBaseSeed = baseSeed,
            startingPlayerIndex = 0,
            actionSpaceProfile = profile,
            semanticPrefix = choices.take(draft.source.decisionIndex).map { it.second },
            candidates = draft.candidates,
            reviewerId = draft.reviewerId,
            reviewerJudgment = draft.reviewerJudgment,
            authenticatedAtUtc = Instant.now().toString(),
            intakeBindingSha256 = "0".repeat(64),
        )
        val authenticated = unsigned.copy(intakeBindingSha256 = unsigned.expectedIntakeBinding())
        authenticated.requireIntakeBinding()
        writeJsonAtomically(output, authenticated)
        return authenticated
    }
}

internal fun reconstructReplayReviewWorld(
    registry: CardRegistry,
    manifest: DeckManifest,
    gameId: String,
    gameSeed: Long,
    searchBaseSeed: Long,
    startingPlayerIndex: Int,
    profile: SearchActionSpaceProfile,
    semanticPrefix: List<SemanticChoice>,
): ArgentumSearchWorld {
    val environment = GameEnvironment.create(registry).also { game ->
        game.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Player 0", manifest.deck()),
                    PlayerConfig("Player 1", manifest.deck()),
                ),
                skipMulligans = false,
                useHandSmoother = false,
                startingPlayerIndex = startingPlayerIndex,
                seed = gameSeed,
            ),
        )
    }
    return ArgentumSearchWorld.create(
        environment = environment,
        gameId = gameId,
        seedBase = searchBaseSeed,
        expander = UnifiedSemanticExpander(actionSpaceProfile = profile),
        cardRegistry = registry,
        knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
    ).also { world ->
        semanticPrefix.forEachIndexed { index, choice ->
            val exact = world.expandChoices().candidates.singleOrNull { it.signature == choice.signature }
                ?: error("Authenticated semantic prefix choice $index is no longer legal")
            require(world.step(exact).accepted) { "Authenticated semantic prefix choice $index was rejected" }
        }
    }
}

private fun CanonicalReplayHeader.extensionString(key: String): String =
    (extensions[key] as? JsonPrimitive)?.content ?: error("Canonical replay header lacks $key")

private fun CanonicalReplayHeader.extensionLong(key: String): Long = extensionString(key).toLong()
