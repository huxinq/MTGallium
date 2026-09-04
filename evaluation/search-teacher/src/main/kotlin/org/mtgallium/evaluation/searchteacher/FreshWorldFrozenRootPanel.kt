package org.mtgallium.evaluation.searchteacher

import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayHeader
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayTransition
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.searchteacher.SearchTeacherAutomaticSelection
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val ISSUE_0013_FROZEN_PANEL_VERSION = "issue-0013-frozen-rv2-panel-v1"
internal const val ISSUE_0013_FROZEN_PANEL_SIZE = 32
internal const val ISSUE_0013_BASELINE_RUN_ID =
    "baseline-factorial-v1-sha256:9432649f71ad5f8ac7406e9f0c2ccc5fb7ffb4f3ae8e3e04fe9bb3f5cba55b09"
private const val ISSUE_0013_BASELINE_DIRECTORY =
    "baseline-factorial-v1-sha256-9432649f71ad5f8ac7406e9f0c2ccc5fb7ffb4f3ae8e3e04fe9bb3f5cba55b09"

@Serializable
internal enum class FrozenRootRegime {
    EARLY_HIGH_UNCERTAINTY,
    POST_MULLIGAN,
    ORDINARY_MIDGAME,
    LATER_REDUCED_UNCERTAINTY,
    LOW_UNCERTAINTY_CONTROL,
}

@Serializable
internal data class FreshWorldFrozenRootPanel(
    val schemaVersion: Int = 1,
    val panelVersion: String = ISSUE_0013_FROZEN_PANEL_VERSION,
    val sourceRunIdentity: String,
    val sourceOuterCommit: String,
    val sourceArgentumCommit: String,
    val currentOuterCommit: String,
    val currentArgentumCommit: String,
    val deckId: String,
    val deckHash: String,
    val cardPoolHash: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val sourcePolicyId: String,
    val selectionAlgorithm: String,
    val sourceScheduledGames: Int,
    val sourcePolicyGames: Int,
    val reconstructedSourcePolicyGames: Int,
    val candidateRoots: Int,
    val candidateRootsByRegime: Map<FrozenRootRegime, Int>,
    val selectedRootsByRegime: Map<FrozenRootRegime, Int>,
    val replayRefusals: List<FrozenRootReplayRefusal>,
    val roots: List<FreshWorldFrozenRoot>,
    val panelDigest: String,
) {
    init {
        require(schemaVersion == 1 && panelVersion == ISSUE_0013_FROZEN_PANEL_VERSION)
        require(sourceRunIdentity == ISSUE_0013_BASELINE_RUN_ID)
        require(roots.isNotEmpty() && roots.size <= ISSUE_0013_FROZEN_PANEL_SIZE)
        require(roots.map(FreshWorldFrozenRoot::gameId).distinct().size == roots.size)
        require(panelDigest == expectedPanelDigest(roots))
    }
}

@Serializable
internal data class FreshWorldFrozenRoot(
    val panelIndex: Int,
    val id: String,
    val gameId: String,
    val decisionIndex: Int,
    val perspectivePlayerId: String,
    val regime: FrozenRootRegime,
    val turnNumber: Int,
    val phase: String,
    val step: String,
    val opponentHandSize: Int,
    val opponentLibrarySize: Int,
    val actorKnownBottomCards: Int,
    val candidateCount: Int,
    val informationStateDigest: String,
    val semanticPrefixDigest: String,
    val sourceChosenSignature: String,
    val replayPath: String,
    val replaySha256: String,
    val gameSeed: Long,
    val searchBaseSeed: Long,
    val resultBlindSelectionKey: String,
)

@Serializable
internal data class FrozenRootReplayRefusal(
    val gameId: String,
    val decisionIndex: Int,
    val reasonCode: String,
)

private data class Candidate(
    val root: FreshWorldFrozenRoot,
    val selectionKey: String,
)

/** Rebuilds historical semantic prefixes under the checked-out admitted engine before selection. */
internal class FreshWorldFrozenRootPanelBuilder(
    private val root: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val manifest: DeckManifest,
    private val currentOuterCommit: String,
    private val currentArgentumCommit: String,
) {
    fun build(): FreshWorldFrozenRootPanel {
        val sourceDirectory = EvidenceStore(root).work(
            "baseline-factorial-v1/$ISSUE_0013_BASELINE_DIRECTORY"
        )
        val sourceManifestPath = sourceDirectory.resolve("manifest.json")
        require(Files.isRegularFile(sourceManifestPath) && !Files.isSymbolicLink(sourceManifestPath)) {
            "Preserved baseline manifest is unavailable: $sourceManifestPath"
        }
        val source = evidenceJson.decodeFromString<BaselineFactorialRunManifest>(
            Files.readString(sourceManifestPath)
        )
        require(source.runIdentity == ISSUE_0013_BASELINE_RUN_ID)
        require(source.deckHash == manifest.deckHash() && source.cardPoolHash == manifest.cardPoolHash())
        val rv2Games = source.scheduledGames.filter { scheduled ->
            scheduled.p0PolicyId == BaselineFactorialRoster.ROLLOUT_V2_ID ||
                scheduled.p1PolicyId == BaselineFactorialRoster.ROLLOUT_V2_ID
        }.sortedBy { it.gameId }
        val candidates = mutableListOf<Candidate>()
        val refusals = mutableListOf<FrozenRootReplayRefusal>()
        var reconstructedGames = 0
        rv2Games.forEach { scheduled ->
            val replayPath = sourceDirectory.resolve("replays/${scheduled.gameId}.privileged.replay.jsonl.gz")
            if (!Files.isRegularFile(replayPath) || Files.isSymbolicLink(replayPath)) {
                refusals += FrozenRootReplayRefusal(scheduled.gameId, 0, "SOURCE_REPLAY_MISSING")
                return@forEach
            }
            val records = runCatching { readCanonicalReplay(replayPath) }.getOrElse { failure ->
                refusals += FrozenRootReplayRefusal(
                    scheduled.gameId,
                    0,
                    "REPLAY_DECODE_${failure::class.simpleName ?: "FAILURE"}",
                )
                return@forEach
            }
            val header = records.firstOrNull() as? CanonicalReplayHeader
            if (header == null) {
                refusals += FrozenRootReplayRefusal(scheduled.gameId, 0, "REPLAY_HEADER_MISSING")
                return@forEach
            }
            if (header.extensionString("mtgallium.runIdentity") != source.runIdentity ||
                header.extensionString("mtgallium.deckHash") != manifest.deckHash() ||
                header.extensionString("mtgallium.cardPoolHash") != manifest.cardPoolHash()
            ) {
                refusals += FrozenRootReplayRefusal(scheduled.gameId, 0, "REPLAY_SOURCE_BINDING_MISMATCH")
                return@forEach
            }
            val choices = records.filterIsInstance<CanonicalReplayTransition>().mapNotNull { transition ->
                val decision = (transition.extensions["mtgallium.decisionIndex"] as? JsonPrimitive)
                    ?.content?.toInt() ?: return@mapNotNull null
                val encoded = transition.extensions["mtgallium.semanticChoice"] ?: return@mapNotNull null
                decision to PolicyJson.format.decodeFromJsonElement(SemanticChoice.serializer(), encoded)
            }
            if (choices.map { it.first } != choices.indices.toList()) {
                refusals += FrozenRootReplayRefusal(scheduled.gameId, 0, "REPLAY_DECISION_SEQUENCE_INVALID")
                return@forEach
            }
            val gameSeed = header.extensionLong("mtgallium.gameSeed")
            val baseSeed = header.extensionLong("mtgallium.baseSeed")
            val world = runCatching {
                reconstructReplayReviewWorld(
                    registry = registry,
                    manifest = manifest,
                    gameId = scheduled.gameId,
                    gameSeed = gameSeed,
                    searchBaseSeed = baseSeed,
                    startingPlayerIndex = 0,
                    profile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
                    semanticPrefix = emptyList(),
                )
            }.getOrElse { failure ->
                refusals += FrozenRootReplayRefusal(
                    scheduled.gameId,
                    0,
                    "CURRENT_ROOT_${failure::class.simpleName ?: "FAILURE"}",
                )
                return@forEach
            }
            val rv2Seat = if (scheduled.p0PolicyId == BaselineFactorialRoster.ROLLOUT_V2_ID) "p0" else "p1"
            var refused = false
            choices.forEachIndexed { decisionIndex, (_, historicalChoice) ->
                if (refused) return@forEachIndexed
                val expansion = runCatching { world.expandChoices() }.getOrElse { failure ->
                    refusals += FrozenRootReplayRefusal(
                        scheduled.gameId,
                        decisionIndex,
                        "CURRENT_EXPANSION_${failure::class.simpleName ?: "FAILURE"}",
                    )
                    refused = true
                    return@forEachIndexed
                }
                val actor = world.actorToAct()
                val exact = expansion.candidates.singleOrNull { it.signature == historicalChoice.signature }
                if (actor == null || exact == null) {
                    refusals += FrozenRootReplayRefusal(
                        scheduled.gameId,
                        decisionIndex,
                        if (actor == null) "CURRENT_ACTOR_MISSING" else "HISTORICAL_CHOICE_NOT_CURRENTLY_LEGAL",
                    )
                    refused = true
                    return@forEachIndexed
                }
                if (actor == rv2Seat && SearchTeacherAutomaticSelection.classify(expansion) == null) {
                    val information = world.informationState(actor)
                    val observation = information.observation
                    val opponent = observation.players.single { it.playerId != actor }
                    val knownBottom = information.knowledge.knownLibraryOrders
                        .singleOrNull { it.playerId == actor }?.bottom.orEmpty().size
                    val regime = classifyRegime(
                        turnNumber = observation.turnNumber,
                        opponentHandSize = opponent.handSize,
                        actorKnownBottomCards = knownBottom,
                    )
                    val selectionKey = sha256(
                        "$ISSUE_0013_FROZEN_PANEL_VERSION:${scheduled.gameId}:$decisionIndex"
                    )
                    val rootId = sha256(
                        "$selectionKey:${information.informationStateDigest}:$rv2Seat"
                    ).take(20)
                    candidates += Candidate(
                        FreshWorldFrozenRoot(
                            panelIndex = -1,
                            id = rootId,
                            gameId = scheduled.gameId,
                            decisionIndex = decisionIndex,
                            perspectivePlayerId = rv2Seat,
                            regime = regime,
                            turnNumber = observation.turnNumber,
                            phase = observation.phase,
                            step = observation.step,
                            opponentHandSize = opponent.handSize,
                            opponentLibrarySize = opponent.librarySize,
                            actorKnownBottomCards = knownBottom,
                            candidateCount = expansion.candidates.size,
                            informationStateDigest = information.informationStateDigest,
                            semanticPrefixDigest = PolicyJson.sha256(
                                choices.take(decisionIndex).joinToString("\u001f") { it.second.signature }
                            ),
                            sourceChosenSignature = historicalChoice.signature,
                            replayPath = root.relativize(replayPath).toString(),
                            replaySha256 = sha256File(replayPath),
                            gameSeed = gameSeed,
                            searchBaseSeed = baseSeed,
                            resultBlindSelectionKey = selectionKey,
                        ),
                        selectionKey,
                    )
                }
                val step = runCatching { world.step(exact) }.getOrElse { failure ->
                    refusals += FrozenRootReplayRefusal(
                        scheduled.gameId,
                        decisionIndex,
                        "CURRENT_STEP_${failure::class.simpleName ?: "FAILURE"}",
                    )
                    refused = true
                    return@forEachIndexed
                }
                if (!step.accepted) {
                    refusals += FrozenRootReplayRefusal(
                        scheduled.gameId,
                        decisionIndex,
                        "CURRENT_STEP_REJECTED",
                    )
                    refused = true
                }
            }
            if (!refused) reconstructedGames++
        }
        val selected = select(candidates).mapIndexed { index, candidate ->
            candidate.root.copy(panelIndex = index)
        }
        require(selected.size == ISSUE_0013_FROZEN_PANEL_SIZE) {
            "Result-blind selection found only ${selected.size}/$ISSUE_0013_FROZEN_PANEL_SIZE roots"
        }
        return FreshWorldFrozenRootPanel(
            sourceRunIdentity = source.runIdentity,
            sourceOuterCommit = source.outerCommit,
            sourceArgentumCommit = source.argentumCommit,
            currentOuterCommit = currentOuterCommit,
            currentArgentumCommit = currentArgentumCommit,
            deckId = manifest.id,
            deckHash = manifest.deckHash(),
            cardPoolHash = manifest.cardPoolHash(),
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            sourcePolicyId = BaselineFactorialRoster.ROLLOUT_V2_ID,
            selectionAlgorithm = "fixed regime quotas 8 early, 6 post-mulligan, 8 midgame, 6 later, 4 low-control; at least two profile-admitted actions outside low-control; sha256 order; one root per game; deterministic deficit fill",
            sourceScheduledGames = source.scheduledGames.size,
            sourcePolicyGames = rv2Games.size,
            reconstructedSourcePolicyGames = reconstructedGames,
            candidateRoots = candidates.size,
            candidateRootsByRegime = FrozenRootRegime.entries.associateWith { regime ->
                candidates.count { it.root.regime == regime }
            },
            selectedRootsByRegime = FrozenRootRegime.entries.associateWith { regime ->
                selected.count { it.regime == regime }
            },
            replayRefusals = refusals,
            roots = selected,
            panelDigest = expectedPanelDigest(selected),
        )
    }

    private fun select(candidates: List<Candidate>): List<Candidate> {
        val quotas = linkedMapOf(
            FrozenRootRegime.LOW_UNCERTAINTY_CONTROL to 4,
            FrozenRootRegime.LATER_REDUCED_UNCERTAINTY to 6,
            FrozenRootRegime.POST_MULLIGAN to 6,
            FrozenRootRegime.ORDINARY_MIDGAME to 8,
            FrozenRootRegime.EARLY_HIGH_UNCERTAINTY to 8,
        )
        val selected = mutableListOf<Candidate>()
        val games = mutableSetOf<String>()
        quotas.forEach { (regime, count) ->
            candidates.asSequence().filter { it.root.regime == regime }
                .filter { regime == FrozenRootRegime.LOW_UNCERTAINTY_CONTROL || it.root.candidateCount >= 2 }
                .sortedBy(Candidate::selectionKey)
                .filter { games.add(it.root.gameId) }
                .take(count)
                .forEach(selected::add)
        }
        candidates.asSequence().filter { candidate ->
            candidate.root.regime == FrozenRootRegime.LOW_UNCERTAINTY_CONTROL ||
                candidate.root.candidateCount >= 2
        }.sortedBy(Candidate::selectionKey)
            .filter { games.add(it.root.gameId) }
            .take(ISSUE_0013_FROZEN_PANEL_SIZE - selected.size)
            .forEach(selected::add)
        return selected
    }
}

private fun classifyRegime(
    turnNumber: Int,
    opponentHandSize: Int,
    actorKnownBottomCards: Int,
): FrozenRootRegime = when {
    opponentHandSize <= 1 && turnNumber >= 3 -> FrozenRootRegime.LOW_UNCERTAINTY_CONTROL
    turnNumber >= 6 -> FrozenRootRegime.LATER_REDUCED_UNCERTAINTY
    actorKnownBottomCards > 0 && turnNumber <= 3 -> FrozenRootRegime.POST_MULLIGAN
    turnNumber <= 2 && opponentHandSize >= 4 -> FrozenRootRegime.EARLY_HIGH_UNCERTAINTY
    else -> FrozenRootRegime.ORDINARY_MIDGAME
}

internal fun expectedPanelDigest(roots: List<FreshWorldFrozenRoot>): String = sha256(
    roots.joinToString("\n") { root ->
        listOf(
            root.panelIndex,
            root.id,
            root.gameId,
            root.decisionIndex,
            root.perspectivePlayerId,
            root.regime,
            root.informationStateDigest,
            root.semanticPrefixDigest,
            root.replaySha256,
            root.resultBlindSelectionKey,
        ).joinToString(":")
    }
)

private fun CanonicalReplayHeader.extensionString(key: String): String =
    (extensions[key] as? JsonPrimitive)?.content ?: error("Canonical replay header lacks $key")

private fun CanonicalReplayHeader.extensionLong(key: String): Long = extensionString(key).toLong()

internal fun renderFreshWorldFrozenRootPanel(panel: FreshWorldFrozenRootPanel): String = buildString {
    appendLine("# Issue 0013 Stage B — frozen rollout-visible-v2 root panel")
    appendLine()
    appendLine(
        "Selected ${panel.roots.size} roots from ${panel.candidateRoots} current-revision-reconstructed " +
            "rollout-visible-v2 candidates. Selection used only source identity and perspective-safe " +
            "regime fields; no fresh-world result existed when this panel was written."
    )
    appendLine()
    appendLine("| Regime | Candidates | Selected |")
    appendLine("| --- | ---: | ---: |")
    FrozenRootRegime.entries.forEach { regime ->
        appendLine(
            "| `${regime.name}` | ${panel.candidateRootsByRegime.getValue(regime)} | " +
                "${panel.selectedRootsByRegime.getValue(regime)} |"
        )
    }
    appendLine()
    appendLine("- Distinct source games: ${panel.roots.map { it.gameId }.distinct().size}")
    appendLine("- Current Argentum: `${panel.currentArgentumCommit}`")
    appendLine("- Historical source Argentum: `${panel.sourceArgentumCommit}`")
    appendLine("- Current-revision replay refusals: ${panel.replayRefusals.size}")
    appendLine("- Panel digest: `${panel.panelDigest}`")
}
