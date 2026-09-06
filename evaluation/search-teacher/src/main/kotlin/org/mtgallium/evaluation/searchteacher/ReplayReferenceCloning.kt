package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

internal const val REPLAY_REFERENCE_CLONING_PROTOCOL = "replay-derived-reference-cloning-v1"

/** Current safe projection and an authenticated historical action; not a historical trajectory. */
@Serializable
internal data class ReplayReferenceCloningExample(
    val gameId: String,
    val pairGroup: String,
    val actor: String,
    val decisionIndex: Int,
    val policyInput: BoundedPolicyInput,
    val teacherAction: SemanticChoice,
) {
    init {
        require(decisionIndex >= 0 && actor == policyInput.actingPlayerId)
        require(teacherAction in policyInput.candidates)
        policyInput.requireValidDigest()
    }
}

@Serializable
internal data class ReplayReferenceCloningGame(
    val gameId: String,
    val pairIndex: Int,
    val pairGroup: String,
    val teacherSeat: String,
    val replaySha256: String,
    val historicalSearchedDecisions: Int,
    val retainedExamples: Int,
    /** A current projection limit excludes the entire game, never just inconvenient labels. */
    val excludedForCurrentGenerationLimit: Boolean,
)

@Serializable
internal data class ReplayReferenceCloningReport(
    val protocol: String = REPLAY_REFERENCE_CLONING_PROTOCOL,
    val researchRunIdentity: String,
    val generatedAtUtc: String,
    val projectionProvenance: ResearchRunProvenance,
    val parentRunIdentity: String,
    val parentManifestSha256: String,
    val historicalSource: PolicySourceProvenance,
    val teacher: SearchTeacherCalibrationPolicyReport,
    val deckHash: String,
    val cardPoolHash: String,
    val selectedPairIndices: List<Int>,
    val games: List<ReplayReferenceCloningGame>,
    val exampleCount: Int,
)

/**
 * Authenticate the label, but do not promote its backed value or candidate probabilities into
 * targets. Completeness is checked on the current expansion; historical omission flags were not
 * retained in the arena report and are deliberately not reconstructed as historical facts.
 */
internal fun replayReferenceCloningExample(
    gameId: String,
    pairGroup: String,
    actor: String,
    information: PolicyInformationState,
    expansion: PolicyExpansion,
    diagnostic: ArenaSearchDecisionDiagnostic,
    acceptedChoice: SemanticChoice,
): ReplayReferenceCloningExample? {
    require(information.actingPlayerId == actor && information.observation.perspectivePlayerId == actor)
    require(!information.terminated && information.knowledge.epistemicallyComplete)
    require(information.observation.turnNumber == diagnostic.turnNumber &&
        information.observation.phase == diagnostic.phase && information.observation.step == diagnostic.step)
    require(information.candidates == expansion.candidates)
    val statistics = diagnostic.candidateStatistics
    require(statistics.isNotEmpty() && statistics.map { it.choice.signature }.distinct().size == statistics.size)
    require(statistics.all { it.visits >= 0 && it.meanValue.isFinite() && it.policyProbability.isFinite() })
    val visits = statistics.sumOf { it.visits.toLong() }
    require(visits > 0 && visits == diagnostic.searchDiagnostics.simulations.toLong())
    require(statistics.all { abs(it.policyProbability - it.visits.toDouble() / visits) <= 1e-12 })
    val chosen = requireNotNull(diagnostic.chosen)
    require(statistics.selectedSearchWinnerOrNull()?.choice == chosen && chosen == acceptedChoice)
    require(statistics.map { it.choice }.toSet() == expansion.candidates.toSet()) {
        "Historical search did not cover the exact current semantic menu"
    }
    require(chosen in expansion.candidates)
    require(diagnostic.searchDiagnostics.rejectedTransitions == 0)
    if (!expansion.isProfileExhaustive || expansion.omissionReasons.any { !it.intentionalProfileOmission }) return null
    require(expansion.exactSingletonPassOrNull() == null)
    return ReplayReferenceCloningExample(gameId, pairGroup, actor, diagnostic.decisionIndex,
        BoundedPolicyInputCompiler.compile(information), chosen)
}

/**
 * Reconstruct each selected terminal game once, collecting both reference seats across pair legs.
 * No planner is executed and no belief hypotheses are derived from authoritative replay state.
 * This is a separate derived dataset contract; strict historical public-corpus admission is unchanged.
 */
internal fun deriveReplayReferenceCloning(
    repositoryRoot: Path,
    parent: Path,
    expectedParentIdentity: String,
    registry: CardRegistry,
    deck: DeckManifest,
    selectedPairIndices: List<Int>,
    output: Path,
): ReplayReferenceCloningReport {
    require(selectedPairIndices.isNotEmpty() && selectedPairIndices == selectedPairIndices.distinct().sorted())
    val provenance = ResearchRunProvenance.capture(repositoryRoot, "third_party/argentum-engine").also { it.requireReady() }
    require(!provenance.outerDirty && !provenance.engineDirty) { "Commit projection source before retaining derived examples" }
    val directory = EvidenceStore(repositoryRoot).requireDiagnosticOutput(output, "replay reference cloning")
    require(!Files.exists(directory)) { "Use a fresh derived dataset destination" }
    val registered = ResearchRunArtifacts.loadAndVerify(parent, expectedParentIdentity).artifacts.associateBy { it.relativePath }
    fun input(relative: String): Path = ResearchRunFiles.resolveBelow(parent, relative).also {
        require(researchSha256File(it) == requireNotNull(registered[relative]).sha256)
    }
    val report = readRetainedCalibrationCloningSource(Files.readString(input("report.json")),
        Files.readString(input("plan.json")), expectedParentIdentity)
    require(report.sourceProvenance.argentum.revision == provenance.checkedOutEngineCommit)
    require(report.deckHash == deck.deckHash() && report.cardPoolHash == deck.cardPoolHash())
    val teacher = report.teacher
    val baseSeed = report.plan.getValue("baseSeed").jsonPrimitive.long
    val arena = SearchTeacherArena(registry, deck, calibrationPresentationProfile(report.sourceProvenance), baseSeed)
    val policy = teacher.descriptor.policy(baseSeed)
    require(arena.evidenceBinding(policy, null, report.sourceProvenance) == teacher.binding)
    require(describeTournamentPolicy(policy) == teacher.policy && policy.effectiveParameters(baseSeed).searchConfig() == teacher.search)
    val profile = teacher.descriptor.parameters(baseSeed).actionSpaceProfile
    val groups = mutableListOf<ReplayReferenceCloningGame>()
    val examples = mutableListOf<ReplayReferenceCloningExample>()
    report.comparisons.forEach { comparison ->
        require(comparison.pairs.map { it.pairIndex }.containsAll(selectedPairIndices))
        comparison.pairs.filter { it.pairIndex in selectedPairIndices }.sortedBy { it.pairIndex }.forEach { pair ->
            require(pair.valid && pair.games.size == 2)
            var previousPayload: String? = null
            for (leg in 0..1) {
                val gameId = "${comparison.candidateId}-pair-${pair.pairIndex}-leg-$leg"
                val seat = if (leg == 0) "p0" else "p1"
                val p0 = if (leg == 0) teacher.descriptor.id else comparison.candidateId
                val p1 = if (leg == 1) teacher.descriptor.id else comparison.candidateId
                val checkpoint = input("checkpoints/$gameId.json")
                val envelope = ResearchRunCheckpoints.load(checkpoint)
                require(envelope.parentPayloadSha256 == previousPayload)
                previousPayload = envelope.payloadSha256
                val game = requireNotNull(loadSearchTeacherCalibrationCheckpoint(checkpoint, parent, expectedParentIdentity,
                    pair.pairIndex, leg, report.pairSeed(pair.pairIndex), p0, p1, gameId))
                require(game == pair.games.single { it.gameId == gameId })
                require(searchBudgetFrontierInvalidationReasons(game).isEmpty() && game.replayVerified)
                val replayPath = input("replays/$gameId.privileged.replay.jsonl.gz")
                require(game.replaySha256 == researchSha256File(replayPath))
                val replay = readVerifiedCanonicalSemanticReplay(replayPath)
                val header = replay.header
                require(header.gameId == gameId && replay.terminal.gameId == gameId && header.players == listOf("p0", "p1"))
                require(header.producer == "mtgallium-search-teacher" && header.engineVersion == provenance.checkedOutEngineCommit)
                require(header.requireExtensionString("mtgallium.runIdentity") == expectedParentIdentity)
                require(header.requireExtensionString("mtgallium.outerCommit") == report.sourceProvenance.outer.revision)
                require(header.requireExtensionString("mtgallium.argentumCommit") == report.sourceProvenance.argentum.revision)
                require(header.requireExtensionString("mtgallium.deckHash") == report.deckHash)
                require(header.requireExtensionString("mtgallium.cardPoolHash") == report.cardPoolHash)
                require(header.requireExtensionLong("mtgallium.gameSeed") == game.seed &&
                    header.requireExtensionLong("mtgallium.baseSeed") == baseSeed)
                require(replay.decisions.size == game.decisions && replay.terminal.winnerId == game.winner)
                val diagnostics = game.seatDiagnostics.getValue(seat)
                require(diagnostics.policyId == teacher.descriptor.id)
                require(diagnostics.searchDecisions == diagnostics.searchDecisionsDetail.size &&
                    diagnostics.selectionCounts.getOrDefault(SearchTeacherSelectionKind.SEARCHED, 0) == diagnostics.searchDecisions)
                val byIndex = diagnostics.searchDecisionsDetail.associateBy { it.decisionIndex }
                require(byIndex.size == diagnostics.searchDecisions && byIndex.keys.all { it in replay.decisions.indices })
                val actual = createSemanticReplayWorld(registry, deck, gameId, game.seed, baseSeed, 0, profile)
                val group = "$expectedParentIdentity:pair-${pair.pairIndex}"
                val collected = mutableListOf<ReplayReferenceCloningExample>()
                var limited = false
                replayFixedRootPrefix(replay.decisions.size, replay, actual, null) { index ->
                    byIndex[index]?.let { detail ->
                        require(actual.actorToAct() == seat)
                        require(detail.searchDiagnostics.particles == teacher.descriptor.particles &&
                            detail.searchDiagnostics.simulations == teacher.search.simulations && detail.searchDiagnostics.leaf == teacher.search.leaf)
                        val row = replayReferenceCloningExample(gameId, group, seat, actual.informationState(seat),
                            actual.expandChoices(), detail, replay.decisions[index].choice)
                        if (row == null) limited = true else collected += row
                    }
                }
                val expectedPayoff = if (game.winner == null) 0.0 else if (game.winner == seat) 1.0 else -1.0
                require(actual.terminalPayoff(seat) == expectedPayoff)
                // All raw transitions and the terminal state have been checked before any examples escape.
                if (!limited) {
                    require(collected.size == diagnostics.searchDecisions)
                    examples += collected
                }
                groups += ReplayReferenceCloningGame(gameId, pair.pairIndex, group, seat, requireNotNull(game.replaySha256),
                    diagnostics.searchDecisions, if (limited) 0 else collected.size, limited)
            }
        }
    }
    require(groups.map { it.gameId }.distinct().size == groups.size && examples.isNotEmpty())
    val parentHash = researchSha256File(parent.resolve(ResearchRunArtifacts.MANIFEST_FILE))
    val binding = ResearchRunBindings(protocol = REPLAY_REFERENCE_CLONING_PROTOCOL, material = mapOf(
        "projection-source" to sha256(evidenceJson.encodeToString(provenance)), "parent-run" to expectedParentIdentity,
        "parent-manifest" to parentHash, "teacher" to teacher.binding.identity,
        "selected-pairs" to selectedPairIndices.joinToString(","),
        "input-config" to evidenceJson.encodeToString(BoundedPolicyInputConfig()),
        "action-profile" to profile.name,
    ))
    val result = ReplayReferenceCloningReport(researchRunIdentity = binding.identity, generatedAtUtc = Instant.now().toString(),
        projectionProvenance = provenance, parentRunIdentity = expectedParentIdentity, parentManifestSha256 = parentHash,
        historicalSource = report.sourceProvenance, teacher = teacher, deckHash = report.deckHash, cardPoolHash = report.cardPoolHash,
        selectedPairIndices = selectedPairIndices, games = groups, exampleCount = examples.size)
    Files.createDirectories(directory)
    ResearchRunFiles.atomicWrite(directory.resolve("report.json"), evidenceJson.encodeToString(result))
    ResearchRunFiles.atomicWrite(directory.resolve("bindings.json"), evidenceJson.encodeToString(binding))
    val json = kotlinx.serialization.json.Json(evidenceJson) { prettyPrint = false }
    GZIPOutputStream(Files.newOutputStream(directory.resolve("examples.jsonl.gz"))).bufferedWriter().use { writer ->
        examples.forEach { writer.appendLine(json.encodeToString(it)) }
    }
    ResearchRunArtifacts(directory, binding.identity).also {
        listOf("report.json", "bindings.json", "examples.jsonl.gz").forEach(it::register)
        it.finalize()
    }
    return result
}

/** Reusable projection parity check; both callers' source datasets must already be authenticated. */
internal fun requireReplayCloningEncodingParity(expected: EncodedBcDecision, actual: EncodedBcDecision) {
    fun same(a: SparseFeatureVector, b: SparseFeatureVector) =
        a.indices.contentEquals(b.indices) && a.values.contentEquals(b.values)
    require(expected.gameId == actual.gameId && expected.decisionIndex == actual.decisionIndex)
    require(expected.labelIndex == actual.labelIndex && expected.decisionFamily == actual.decisionFamily)
    require(expected.candidateFamilies == actual.candidateFamilies && expected.candidateIntents == actual.candidateIntents)
    require(same(expected.state, actual.state) && expected.candidateCount == actual.candidateCount)
    require(expected.candidates.zip(actual.candidates).all { (a, b) -> same(a, b) }) {
        "Current projected features differ from the admitted historical example"
    }
}
