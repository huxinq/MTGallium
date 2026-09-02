package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyTrajectoryDecision
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryCompletion
import org.mtgallium.agent.infoset.core.PolicyTrajectoryOutcome
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord
import org.mtgallium.agent.infoset.core.SemanticChoice

/** Produces blinded review material and trusted replay cases from a frozen, verified corpus. */
internal class ReviewEvidenceGenerator(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val samplingSeed: Long,
) {
    fun generate(corpusManifestPath: Path, reviewItems: Int, surprisingCases: Int): ReviewEvidenceResult {
        require(reviewItems > 0)
        require(surprisingCases >= 20)
        val corpus = evidenceJson.decodeFromString<CorpusManifest>(Files.readString(corpusManifestPath))
        require(corpus.passed) { "Review evidence requires a corpus that passed replay verification" }
        val corpusHash = sha256File(corpusManifestPath)
        val sources = corpus.entries.flatMap(::loadSources)
        require(sources.size >= reviewItems) {
            "Corpus contains ${sources.size} search decisions; $reviewItems are required for blinded review"
        }
        require(sources.size >= surprisingCases) {
            "Corpus contains ${sources.size} search decisions; $surprisingCases replay cases are required"
        }

        val review = sources.sortedBy { source ->
            sha256("$samplingSeed:${source.header.gameId}:${source.decision.decisionIndex}:review")
        }.take(reviewItems).map(::reviewItem)
        val packet = ExpertReviewPacket(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            sourceCorpusHash = corpusHash,
            itemCount = review.size,
            items = review,
        )

        val selectedSurprises = sources.sortedWith(
            compareByDescending<DecisionSource> { it.surpriseScore }
                .thenBy { it.header.gameId }
                .thenBy { it.decision.decisionIndex }
        ).take(surprisingCases)
        val replayCases = selectedSurprises.map(::replayCase)
        val surprising = SurprisingLineReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            sourceCorpusHash = corpusHash,
            requestedCases = surprisingCases,
            verifiedCases = replayCases.count { it.replayVerified },
            cases = replayCases,
            passed = replayCases.size == surprisingCases && replayCases.all { it.replayVerified },
        )
        check(surprising.passed) {
            "Surprising-line replay verification failed: ${replayCases.filterNot { it.replayVerified }}"
        }
        return ReviewEvidenceResult(packet, surprising)
    }

    private fun loadSources(entry: CorpusEntry): List<DecisionSource> {
        require(entry.replayVerified) { "Unverified corpus entry ${entry.gameId}" }
        val publicPath = root.resolve(entry.publicTrajectory)
        val debugPath = privilegedDebugPath(publicPath)
        val records = readCompressed(publicPath).map { line ->
            PolicyJson.format.decodeFromString(PolicyTrajectoryRecord.serializer(), line)
        }
        val header = records.first() as? PolicyTrajectoryHeader ?: error("Missing header in $publicPath")
        val outcome = records.last() as? PolicyTrajectoryOutcome ?: error("Missing outcome in $publicPath")
        require(outcome.completion == PolicyTrajectoryCompletion.GAME_ENDED) {
            "Review evidence cannot use a game that stopped early: ${outcome.stopReason}"
        }
        val ledger = mutableListOf<org.mtgallium.agent.infoset.core.PolicyHistoryEvent>()
        val decisions = mutableListOf<DecisionSource>()
        records.forEach { record ->
            when (record) {
                is PolicyTrajectoryDecision -> {
                    require(record.historyCursor == ledger.size) {
                        "Decision ${record.decisionIndex} cursor ${record.historyCursor} does not match ledger ${ledger.size}"
                    }
                    decisions += DecisionSource(
                        entry,
                        publicPath,
                        debugPath,
                        header,
                        outcome,
                        record,
                        record.informationState(ledger),
                    )
                }
                is org.mtgallium.agent.infoset.core.PolicyTrajectoryForcedTransition -> ledger += record.events
                else -> Unit
            }
        }
        return decisions
    }

    private fun reviewItem(source: DecisionSource): ExpertReviewItem {
        val candidates = source.decision.candidates.map { sanitize(it.choice) }
        val information = sanitize(source.informationState, candidates)
        return ExpertReviewItem(
            reviewId = sha256(
                "$samplingSeed:${source.header.gameId}:${source.decision.decisionIndex}:review-id"
            ).take(16),
            informationState = information,
            candidates = candidates,
            selectedChoice = sanitize(source.decision.chosen),
        )
    }

    private fun replayCase(source: DecisionSource): SurprisingDecisionReplayCase {
        val debug = readCompressed(source.debugPath).map { PolicyJson.format.decodeFromString<PrivilegedDebugLine>(it) }
        val choices = debug.chunked(2).mapIndexed { index, pair ->
            require(pair.size == 2) { "Incomplete privileged pair at $index in ${source.debugPath}" }
            requireNotNull(pair[1].chosenChoice) { "Missing privileged choice at $index in ${source.debugPath}" }
        }
        val target = source.decision.decisionIndex
        val prefix = choices.take(target + 1)
        val verification = verifyPrefix(source, debug, prefix)
        val chosenStat = source.decision.candidates.single { it.choice.signature == source.decision.chosen.signature }
        val heuristicStat = source.decision.candidates.singleOrNull {
            it.choice.signature == source.decision.heuristicChoice.signature
        }
        return SurprisingDecisionReplayCase(
            caseId = sha256("${source.header.gameId}:$target:surprising").take(16),
            sourceTrajectory = root.relativize(source.publicPath).toString(),
            targetDecisionIndex = target,
            expectedInformationStateDigest = source.decision.informationStateDigest,
            chosenSignature = source.decision.chosen.signature,
            heuristicSignature = source.decision.heuristicChoice.signature,
            chosenMeanValue = chosenStat.meanValue,
            heuristicMeanValue = heuristicStat?.meanValue,
            estimatedAdvantage = heuristicStat?.let { chosenStat.meanValue - it.meanValue },
            privilegedDebugSource = root.relativize(source.debugPath).toString(),
            semanticPrefixDigest = PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }),
            replayVerified = verification.first,
            replayDiagnostic = verification.second,
        )
    }

    private fun verifyPrefix(
        source: DecisionSource,
        debug: List<PrivilegedDebugLine>,
        prefix: List<SemanticChoice>,
    ): Pair<Boolean, String?> = runCatching {
        val replaySeeds = privilegedReplaySeeds(debug)
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Player 0", manifest.deck()),
                    PlayerConfig("Player 1", manifest.deck()),
                ),
                skipMulligans = false,
                useHandSmoother = false,
                startingPlayerIndex = 0,
                seed = replaySeeds.gameSeed,
            )
        )
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = source.header.gameId,
            seedBase = replaySeeds.searchBaseSeed,
            expander = UnifiedSemanticExpander(actionSpaceProfile = source.header.actionSpaceProfile),
            cardRegistry = registry,
            knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
        )
        prefix.forEachIndexed { index, choice ->
            val before = debug[index * 2]
            val after = debug[index * 2 + 1]
            require(world.privilegedDebugSnapshot() == before.snapshot) { "pre-step mismatch at $index" }
            if (index == source.decision.decisionIndex) {
                val actor = requireNotNull(world.actorToAct())
                require(world.informationState(actor).informationStateDigest == source.decision.informationStateDigest) {
                    "information-state mismatch at target $index"
                }
                require(choice.signature == source.decision.chosen.signature) { "target choice mismatch at $index" }
            }
            val step = world.step(choice)
            require(step.accepted) { "choice rejected at $index: ${step.diagnostic}" }
            require(world.privilegedDebugSnapshot() == after.snapshot) { "post-step mismatch at $index" }
        }
        true to null
    }.getOrElse { false to "${it::class.simpleName}: ${it.message}" }

    private fun sanitize(choice: SemanticChoice): SemanticChoice = choice.copy(
        display = choice.display.copy(policyTags = emptySet()),
    )

    private fun sanitize(information: PolicyInformationState, candidates: List<SemanticChoice>): PolicyInformationState =
        information.copy(candidates = candidates)
}

internal data class ReviewEvidenceResult(
    val packet: ExpertReviewPacket,
    val surprisingLines: SurprisingLineReport,
)

private data class DecisionSource(
    val entry: CorpusEntry,
    val publicPath: Path,
    val debugPath: Path,
    val header: PolicyTrajectoryHeader,
    val outcome: PolicyTrajectoryOutcome,
    val decision: PolicyTrajectoryDecision,
    val informationState: PolicyInformationState,
) {
    val surpriseScore: Double
        get() {
            val chosen = decision.candidates.single { it.choice.signature == decision.chosen.signature }
            val heuristic = decision.candidates.singleOrNull { it.choice.signature == decision.heuristicChoice.signature }
            val divergence = if (decision.chosen.signature == decision.heuristicChoice.signature) 0.0 else 1.0
            val advantage = heuristic?.let { (chosen.meanValue - it.meanValue).coerceAtLeast(0.0) } ?: 0.0
            return divergence + advantage
        }
}

private fun readCompressed(path: Path): List<String> =
    GZIPInputStream(Files.newInputStream(path)).bufferedReader().use { reader -> reader.readLines() }
