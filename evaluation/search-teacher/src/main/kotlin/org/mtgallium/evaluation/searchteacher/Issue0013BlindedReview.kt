package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayTransition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyInspectionBundle
import org.mtgallium.agent.infoset.core.PolicyInspectionFrame
import org.mtgallium.agent.infoset.core.PolicyInspectionOutcome
import org.mtgallium.agent.infoset.core.PolicyInspectionPresentation
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PublicArtifactPrivacy
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val ISSUE_0013_BLINDED_REVIEW_DOCUMENT_KIND =
    "issue-0013-blinded-action-review-v1"
private const val ISSUE_0013_PRIMARY_REPORT =
    "issue-0013-fresh-world/stage-b/fixed-work-r32-x16/report.json"
private const val ISSUE_0013_PANEL = "issue-0013-fresh-world/stage-b/panel.json"

@Serializable
internal data class Issue0013BlindedReviewPacket(
    val schemaVersion: Int = 1,
    val documentKind: String = ISSUE_0013_BLINDED_REVIEW_DOCUMENT_KIND,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val deckManifestHash: String,
    val cardPoolHash: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val gradingScale: List<String> = listOf("PREFERRED", "ACCEPTABLE", "DUBIOUS", "UNACCEPTABLE"),
    val presentation: PolicyInspectionPresentation,
    val cases: List<Issue0013BlindedReviewCase>,
) {
    init {
        require(schemaVersion == 1 && documentKind == ISSUE_0013_BLINDED_REVIEW_DOCUMENT_KIND)
        require(cases.size == 3)
        require(cases.map(Issue0013BlindedReviewCase::reviewCaseId).distinct().size == cases.size)
        require(cases.map(Issue0013BlindedReviewCase::neutralLabel).distinct().size == cases.size)
    }
}

@Serializable
internal data class Issue0013BlindedReviewCase(
    val reviewCaseId: String,
    val neutralLabel: String,
    val prompt: String,
    val informationState: PolicyInformationState,
    val candidateExpansion: PolicyExpansion,
    val blindLabelBySignature: Map<String, String>,
) {
    init {
        require(runCatching { UUID.fromString(reviewCaseId) }.isSuccess)
        require(neutralLabel.isNotBlank() && prompt.isNotBlank())
        require(informationState.actingPlayerId == informationState.observation.perspectivePlayerId)
        require(!informationState.terminated)
        require(candidateExpansion.isProfileExhaustive)
        val signatures = candidateExpansion.candidates.map(SemanticChoice::signature)
        require(signatures.toSet() == blindLabelBySignature.keys)
        require(blindLabelBySignature.values.distinct().size == blindLabelBySignature.size)
        require((informationState.candidates + candidateExpansion.candidates).all {
            it.display.policyTags.isEmpty()
        }) { "Blinded review candidates cannot carry policy annotations" }
    }
}

/** Builds the three screened roots without running either experimental search arm. */
internal class Issue0013BlindedReviewGenerator(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val outerCommit: String,
    private val argentumCommit: String,
) {
    private val evidence = EvidenceStore(root)

    fun generate(): Pair<Issue0013BlindedReviewPacket, Path> {
        val panelPath = evidence.work(ISSUE_0013_PANEL)
        val resultPath = evidence.work(ISSUE_0013_PRIMARY_REPORT)
        listOf(panelPath, resultPath).forEach { path ->
            require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                "Required preserved issue-0013 input is unavailable: $path"
            }
        }
        val panel = evidenceJson.decodeFromString<FreshWorldFrozenRootPanel>(Files.readString(panelPath))
        val result = evidenceJson.decodeFromString<FreshWorldFixedWorkReport>(Files.readString(resultPath))
        require(result.completed && result.panelDigest == panel.panelDigest)
        require(panel.currentArgentumCommit == argentumCommit && result.argentumCommit == argentumCommit) {
            "The blinded review must use the admitted issue-0013 Argentum revision"
        }
        require(panel.deckHash == manifest.deckHash() && panel.cardPoolHash == manifest.cardPoolHash())

        val screened = result.rootSummaries.filter(FreshWorldRootSummary::materialByDeclaredScreen)
        require(screened.size == 3)
        val frozenById = panel.roots.associateBy(FreshWorldFrozenRoot::id)
        val selected = screened.map { summary ->
            val frozen = requireNotNull(frozenById[summary.rootId])
            require(
                Triple(frozen.regime, frozen.decisionIndex, frozen.perspectivePlayerId) in EXPECTED_ROOTS
            ) { "Unexpected root passed the issue-0013 primary materiality screen" }
            frozen to summary
        }
        require(selected.map { (frozen) ->
            Triple(frozen.regime, frozen.decisionIndex, frozen.perspectivePlayerId)
        }.toSet() == EXPECTED_ROOTS)

        val reconstructed = selected.map { (frozen, summary) -> reconstruct(frozen, summary) }
            .sortedBy { (frozen, _) ->
                sha256("$ISSUE_0013_BLINDED_REVIEW_DOCUMENT_KIND:${frozen.informationStateDigest}:case-order")
            }
        val generatedAt = Instant.now().toString()
        val caseNames = listOf("Case Cedar", "Case Harbor", "Case Kestrel")
        val cases = reconstructed.mapIndexed { index, (frozen, reconstructedCase) ->
            Issue0013BlindedReviewCase(
                reviewCaseId = UUID.nameUUIDFromBytes(
                    "mtgallium:$ISSUE_0013_BLINDED_REVIEW_DOCUMENT_KIND:${frozen.informationStateDigest}"
                        .toByteArray()
                ).toString(),
                neutralLabel = caseNames[index],
                prompt = "Grade each currently admitted legal action using only the displayed safe position, " +
                    "legitimate history, and remembered information.",
                informationState = reconstructedCase.information,
                candidateExpansion = reconstructedCase.expansion,
                blindLabelBySignature = reconstructedCase.expansion.candidates.mapIndexed { candidateIndex, choice ->
                    choice.signature to "Option ${('A'.code + candidateIndex).toChar()}"
                }.toMap(),
            )
        }
        val resolver = InspectionCardPresentationResolver(
            registry,
            manifest.mainDeck.keys + manifest.sideboard.keys,
        )
        val presentation = mergeIssue0013Presentations(
            cases.map { resolver.safe(it.presentationBundle(generatedAt)) }
        )
        val packet = Issue0013BlindedReviewPacket(
            generatedAtUtc = generatedAt,
            outerCommit = outerCommit,
            argentumCommit = argentumCommit,
            deckManifestHash = manifest.deckHash(),
            cardPoolHash = manifest.cardPoolHash(),
            actionSpaceProfile = panel.actionSpaceProfile,
            presentation = presentation,
            cases = cases,
        )
        val encoded = evidenceJson.encodeToString(packet)
        PublicArtifactPrivacy.requireSafeJson(encoded, "issue-0013 blinded action-review packet")
        require(BLINDED_FORBIDDEN_TEXT.none { encoded.contains(it, ignoreCase = true) }) {
            "The issue-0013 owner packet contains an experimental diagnostic label"
        }
        val path = evidence.diagnostic(
            "issue-0013-fresh-world/blinded-review/issue-0013.blinded-action-review.json",
            "the issue-0013 blinded human-review packet",
        )
        writeJsonAtomically(path, packet)
        return packet to path
    }

    private fun reconstruct(
        frozen: FreshWorldFrozenRoot,
        summary: FreshWorldRootSummary,
    ): Pair<FreshWorldFrozenRoot, ReconstructedCase> {
        val replayPath = root.resolve(frozen.replayPath)
        require(Files.isRegularFile(replayPath) && !Files.isSymbolicLink(replayPath))
        require(sha256File(replayPath) == frozen.replaySha256)
        val choices = readCanonicalReplay(replayPath).filterIsInstance<CanonicalReplayTransition>().mapNotNull {
            val decision = (it.extensions["mtgallium.decisionIndex"] as? JsonPrimitive)
                ?.content?.toInt() ?: return@mapNotNull null
            val encoded = it.extensions["mtgallium.semanticChoice"] ?: return@mapNotNull null
            decision to PolicyJson.format.decodeFromJsonElement(SemanticChoice.serializer(), encoded)
        }
        require(choices.map { it.first } == choices.indices.toList())
        val prefix = choices.take(frozen.decisionIndex).map { it.second }
        require(PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }) == frozen.semanticPrefixDigest)
        val world = reconstructReplayReviewWorld(
            registry = registry,
            manifest = manifest,
            gameId = frozen.gameId,
            gameSeed = frozen.gameSeed,
            searchBaseSeed = frozen.searchBaseSeed,
            startingPlayerIndex = 0,
            profile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            semanticPrefix = prefix,
        )
        val actor = requireNotNull(world.actorToAct())
        require(actor == frozen.perspectivePlayerId)
        val information = world.informationState(actor).blindIssue0013PolicyAnnotations()
        require(information.informationStateDigest == frozen.informationStateDigest)
        val expansion = world.expandChoices().blindIssue0013PolicyAnnotations()
        require(expansion.candidates.size == frozen.candidateCount)
        require(expansion.candidates.map { it.signature } == information.candidates.map { it.signature })

        // This is an internal completeness check only. Arm names, occurrence, and mapping are not serialized.
        val experimentSelections = listOf(summary.reused, summary.fresh).flatMap { arm ->
            arm.candidateVariability.filter { it.selectedRepetitions > 0 }.map { it.signature }
        }.toSet()
        require(experimentSelections.isNotEmpty())
        require(experimentSelections.all { selected -> expansion.candidates.any { it.signature == selected } })
        require(summary.reused.modalSignature != null && summary.fresh.modalSignature != null)
        require(summary.reused.modalSignature != summary.fresh.modalSignature)

        val shuffled = expansion.candidates.sortedBy { choice ->
            sha256(
                "$ISSUE_0013_BLINDED_REVIEW_DOCUMENT_KIND:${information.informationStateDigest}:" +
                    "${choice.signature}:candidate-order"
            )
        }
        return frozen to ReconstructedCase(information, expansion.copy(candidates = shuffled))
    }

    private fun Issue0013BlindedReviewCase.presentationBundle(generatedAt: String): PolicyInspectionBundle =
        PolicyInspectionBundle(
            gameId = reviewCaseId,
            createdAtUtc = generatedAt,
            outerCommit = outerCommit,
            argentumCommit = argentumCommit,
            deckManifestHash = manifest.deckHash(),
            cardPoolHash = manifest.cardPoolHash(),
            profileManifestHash = ISSUE_0013_BLINDED_REVIEW_DOCUMENT_KIND,
            perspectivePlayerId = informationState.observation.perspectivePlayerId,
            policyVersion = "human-review",
            evaluatorVersion = "not-run",
            beliefVersion = "not-run",
            opponentModelVersion = "not-run",
            ledger = informationState.history,
            frames = listOf(
                PolicyInspectionFrame(
                    frameIndex = 0,
                    afterDecisionIndex = null,
                    actingPlayerId = informationState.actingPlayerId,
                    observation = informationState.observation,
                    knowledge = informationState.knowledge,
                    candidates = candidateExpansion.candidates,
                    candidateSchemaVersion = informationState.candidateSchemaVersion,
                    historyLength = informationState.history.size,
                    historyCommitment = informationState.historyCommitment,
                    informationStateDigest = informationState.informationStateDigest,
                    terminated = false,
                    winnerId = null,
                )
            ),
            outcome = PolicyInspectionOutcome(
                decisions = 0,
                terminated = false,
                truncated = true,
                winnerId = null,
                resultByPlayer = emptyMap(),
            ),
        )

    private data class ReconstructedCase(
        val information: PolicyInformationState,
        val expansion: PolicyExpansion,
    )

    companion object {
        private val EXPECTED_ROOTS = setOf(
            Triple(FrozenRootRegime.LATER_REDUCED_UNCERTAINTY, 194, "p0"),
            Triple(FrozenRootRegime.LATER_REDUCED_UNCERTAINTY, 197, "p0"),
            Triple(FrozenRootRegime.EARLY_HIGH_UNCERTAINTY, 4, "p1"),
        )
        private val BLINDED_FORBIDDEN_TEXT = listOf(
            "SEQUENTIAL_EIGHT_REUSED",
            "FRESH_COMPLETE_PER_SIMULATION",
            "selectedRepetitions",
            "rootValue",
            "scheduledHiddenAssignment",
            "proposalFailures",
        )
    }
}

private fun PolicyInformationState.blindIssue0013PolicyAnnotations(): PolicyInformationState = copy(
    candidates = candidates.map(SemanticChoice::blindIssue0013PolicyAnnotations),
)

private fun PolicyExpansion.blindIssue0013PolicyAnnotations(): PolicyExpansion = copy(
    candidates = candidates.map(SemanticChoice::blindIssue0013PolicyAnnotations),
)

private fun SemanticChoice.blindIssue0013PolicyAnnotations(): SemanticChoice = copy(
    display = display.copy(policyTags = emptySet()),
)

private fun mergeIssue0013Presentations(
    presentations: List<PolicyInspectionPresentation>,
): PolicyInspectionPresentation {
    val images = presentations.flatMap(PolicyInspectionPresentation::cardImages)
        .associateBy { it.key }.values.sortedBy { it.key }
    val resolvedNames = images.map { it.cardName }.toSet()
    val unresolved = presentations.flatMap(PolicyInspectionPresentation::unresolvedCardNames)
        .filterNot(resolvedNames::contains).distinct().sorted()
    return PolicyInspectionPresentation(images, unresolved)
}
