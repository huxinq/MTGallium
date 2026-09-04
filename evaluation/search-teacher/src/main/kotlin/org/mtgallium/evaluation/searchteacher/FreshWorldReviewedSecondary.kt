package org.mtgallium.evaluation.searchteacher

import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayTransition
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

private const val REVIEWED_SECONDARY_DRAFT =
    "replay-review-decisions/drafts/" +
        "304f79e6bba473ee615439d597d9b0cf92ac976accda3d013f9ab7d67efe374b/frame-119.json"

@Serializable
internal data class ReviewedSecondaryArmDisposition(
    val preferred: Int,
    val unacceptable: Int,
    val ungradedOrChanged: Int,
    val modalSignature: String?,
    val modalGrade: ReplayReviewDecisionGrade?,
)

@Serializable
internal data class FreshWorldReviewedSecondaryReport(
    val schemaVersion: Int = 1,
    val sourceGameId: String,
    val sourceDecisionIndex: Int,
    val sourceHistoricalChosenSignature: String,
    val reviewerId: String,
    val reviewerJudgment: String,
    val currentInformationStateDigest: String,
    val currentCandidateGrades: Map<String, ReplayReviewDecisionGrade?>,
    val currentCandidatesMissingFromHistoricalReview: Int,
    val historicalReviewedCandidatesMissingFromCurrentRoot: Int,
    val experiment: FreshWorldFixedWorkReport,
    val reused: ReviewedSecondaryArmDisposition,
    val fresh: ReviewedSecondaryArmDisposition,
    val interpretation: String,
    val limitations: List<String>,
)

internal class FreshWorldReviewedSecondaryRunner(
    private val root: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val manifest: DeckManifest,
    private val primaryPanel: FreshWorldFrozenRootPanel,
    private val outerCommit: String,
    private val argentumCommit: String,
) {
    fun run(
        repetitions: Int,
        workerThreads: Int,
        progress: (String) -> Unit = {},
    ): FreshWorldReviewedSecondaryReport {
        val draftPath = EvidenceStore(root).work(REVIEWED_SECONDARY_DRAFT)
        require(Files.isRegularFile(draftPath) && !Files.isSymbolicLink(draftPath))
        val draft = evidenceJson.decodeFromString<ReplayReviewDecisionDraft>(Files.readString(draftPath))
        require(draft.reviewerJudgment.isNotBlank())
        require(draft.candidates.any { it.grade == ReplayReviewDecisionGrade.PREFERRED })
        require(draft.candidates.any { it.grade == ReplayReviewDecisionGrade.UNACCEPTABLE })
        val source = primaryPanel.roots.single { it.gameId == draft.source.gameId }
        val replayPath = root.resolve(source.replayPath)
        require(sha256File(replayPath) == source.replaySha256)
        val choices = readCanonicalReplay(replayPath).filterIsInstance<CanonicalReplayTransition>().mapNotNull { transition ->
            val decisionIndex = (transition.extensions["mtgallium.decisionIndex"] as? JsonPrimitive)
                ?.content?.toInt() ?: return@mapNotNull null
            val encoded = transition.extensions["mtgallium.semanticChoice"] ?: return@mapNotNull null
            decisionIndex to PolicyJson.format.decodeFromJsonElement(SemanticChoice.serializer(), encoded)
        }
        require(choices.map { it.first } == choices.indices.toList())
        val prefix = choices.take(draft.source.decisionIndex).map { it.second }
        val world = reconstructReplayReviewWorld(
            registry = registry,
            manifest = manifest,
            gameId = source.gameId,
            gameSeed = source.gameSeed,
            searchBaseSeed = source.searchBaseSeed,
            startingPlayerIndex = 0,
            profile = primaryPanel.actionSpaceProfile,
            semanticPrefix = prefix,
        )
        val actor = requireNotNull(world.actorToAct())
        require(actor == draft.source.perspectivePlayerId)
        val information = world.informationState(actor)
        val observation = information.observation
        val opponent = observation.players.single { it.playerId != actor }
        val knownBottom = information.knowledge.knownLibraryOrders
            .singleOrNull { it.playerId == actor }?.bottom.orEmpty().size
        val expansion = world.expandChoices()
        val reviewedGrades = draft.candidates.associate { it.signature to it.grade }
        val currentGrades = expansion.candidates.associate { it.signature to reviewedGrades[it.signature] }
        val frozen = FreshWorldFrozenRoot(
            panelIndex = 0,
            id = sha256("reviewed-secondary:${source.gameId}:${draft.source.decisionIndex}:$argentumCommit").take(20),
            gameId = source.gameId,
            decisionIndex = draft.source.decisionIndex,
            perspectivePlayerId = actor,
            regime = FrozenRootRegime.ORDINARY_MIDGAME,
            turnNumber = observation.turnNumber,
            phase = observation.phase,
            step = observation.step,
            opponentHandSize = opponent.handSize,
            opponentLibrarySize = opponent.librarySize,
            actorKnownBottomCards = knownBottom,
            candidateCount = expansion.candidates.size,
            informationStateDigest = information.informationStateDigest,
            semanticPrefixDigest = PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }),
            sourceChosenSignature = choices[draft.source.decisionIndex].second.signature,
            replayPath = source.replayPath,
            replaySha256 = source.replaySha256,
            gameSeed = source.gameSeed,
            searchBaseSeed = source.searchBaseSeed,
            resultBlindSelectionKey = "SECONDARY_EXISTING_HUMAN_REVIEW",
        )
        val secondaryPanel = primaryPanel.copy(
            panelVersion = ISSUE_0013_FROZEN_PANEL_VERSION,
            selectionAlgorithm = "single pre-existing human-reviewed secondary root; excluded from primary panel selection",
            candidateRoots = 1,
            candidateRootsByRegime = mapOf(FrozenRootRegime.ORDINARY_MIDGAME to 1),
            selectedRootsByRegime = mapOf(FrozenRootRegime.ORDINARY_MIDGAME to 1),
            replayRefusals = emptyList(),
            roots = listOf(frozen),
            panelDigest = expectedPanelDigest(listOf(frozen)),
        )
        val experiment = FreshWorldFixedWorkExperiment(
            root = root,
            registry = registry,
            manifest = manifest,
            panel = secondaryPanel,
            outerCommit = outerCommit,
            argentumCommit = argentumCommit,
        ).run(
            rootLimit = 1,
            repetitions = repetitions,
            workerThreads = workerThreads,
            progress = progress,
        )
        val complete = experiment.trials.filter(FreshWorldPairedTrial::complete)
        val reused = disposition(complete.map { it.reused!! }, currentGrades)
        val fresh = disposition(complete.map { it.fresh!! }, currentGrades)
        val interpretation = when {
            reused.modalGrade == ReplayReviewDecisionGrade.PREFERRED &&
                fresh.modalGrade == ReplayReviewDecisionGrade.PREFERRED ->
                "Both arms' modal current-revision choice agrees with the reviewed preferred action."
            reused.modalGrade != ReplayReviewDecisionGrade.PREFERRED &&
                fresh.modalGrade == ReplayReviewDecisionGrade.PREFERRED ->
                "Fresh worlds move the modal choice toward the reviewed preferred action."
            reused.modalGrade == ReplayReviewDecisionGrade.PREFERRED &&
                fresh.modalGrade != ReplayReviewDecisionGrade.PREFERRED ->
                "Fresh worlds move the modal choice away from the reviewed preferred action."
            else -> "Neither arm's modal choice establishes movement toward the reviewed preferred action."
        }
        return FreshWorldReviewedSecondaryReport(
            sourceGameId = source.gameId,
            sourceDecisionIndex = draft.source.decisionIndex,
            sourceHistoricalChosenSignature = draft.source.chosenSignature,
            reviewerId = draft.reviewerId.ifBlank { "UNRECORDED_IN_DRAFT" },
            reviewerJudgment = draft.reviewerJudgment,
            currentInformationStateDigest = information.informationStateDigest,
            currentCandidateGrades = currentGrades,
            currentCandidatesMissingFromHistoricalReview = currentGrades.count { it.value == null },
            historicalReviewedCandidatesMissingFromCurrentRoot = reviewedGrades.keys.count { it !in currentGrades },
            experiment = experiment,
            reused = reused,
            fresh = fresh,
            interpretation = interpretation,
            limitations = listOf(
                "This is one secondary root and cannot establish strategic correctness or a population rate.",
                "The draft contains a clear judgment and candidate grades but has an empty reviewer-id field.",
                "The position was reconstructed under current Argentum; its current information digest is intentionally not the historical engine digest.",
            ),
        )
    }

    private fun disposition(
        outcomes: List<FreshWorldArmOutcome>,
        grades: Map<String, ReplayReviewDecisionGrade?>,
    ): ReviewedSecondaryArmDisposition {
        val selected = outcomes.map { it.chosenSignature }
        val modal = selected.groupingBy { it }.eachCount().entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }
        )?.key
        return ReviewedSecondaryArmDisposition(
            preferred = selected.count { grades[it] == ReplayReviewDecisionGrade.PREFERRED },
            unacceptable = selected.count { grades[it] == ReplayReviewDecisionGrade.UNACCEPTABLE },
            ungradedOrChanged = selected.count { grades[it] == null },
            modalSignature = modal,
            modalGrade = modal?.let(grades::get),
        )
    }
}

internal fun renderFreshWorldReviewedSecondary(report: FreshWorldReviewedSecondaryReport): String = buildString {
    appendLine("# Issue 0013 Stage B — reviewed secondary root")
    appendLine()
    appendLine(
        "Current-revision reconstruction of `${report.sourceGameId}` decision " +
            "${report.sourceDecisionIndex}; ${report.experiment.completePairs}/${report.experiment.scheduledPairs} " +
            "paired repetitions completed."
    )
    appendLine()
    appendLine("- Reused: preferred ${report.reused.preferred}, unacceptable ${report.reused.unacceptable}, ungraded/changed ${report.reused.ungradedOrChanged}.")
    appendLine("- Fresh: preferred ${report.fresh.preferred}, unacceptable ${report.fresh.unacceptable}, ungraded/changed ${report.fresh.ungradedOrChanged}.")
    appendLine("- ${report.interpretation}")
    appendLine("- Current candidates absent from historical review: ${report.currentCandidatesMissingFromHistoricalReview}; reviewed candidates absent now: ${report.historicalReviewedCandidatesMissingFromCurrentRoot}.")
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
}
