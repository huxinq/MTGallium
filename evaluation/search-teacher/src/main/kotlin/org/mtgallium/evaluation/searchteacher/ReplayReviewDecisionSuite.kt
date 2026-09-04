package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.GameEnvironment
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

const val REPLAY_REVIEW_DECISION_SUITE_VERSION: String = "replay-review-decisions-v2"

@Serializable
enum class ReplayReviewDecisionGrade { PREFERRED, PLAUSIBLE, UNACCEPTABLE }

@Serializable
data class ReplayReviewDecisionSource(
    val gameId: String,
    val perspective: String,
    val frameIndex: Int,
    val decisionIndex: Int,
    val recordedOuterCommit: String,
    val recordedArgentumCommit: String,
    val safeInformationStateDigest: String,
    /** Relative to EvidenceLocation.WORK; kept relative so authority stays centralized in EvidenceStore. */
    val safeBundleRelativePath: String,
    val safeBundleSha256: String,
) {
    init {
        require(gameId.isNotBlank() && perspective in setOf("p0", "p1"))
        require(frameIndex >= 0 && decisionIndex >= 0)
        require(recordedOuterCommit.length == 40 && recordedArgentumCommit.length == 40)
        require(safeInformationStateDigest.length == 64 && safeBundleSha256.length == 64)
        require(safeBundleRelativePath.startsWith("inspection/") && !safeBundleRelativePath.contains(".."))
    }
}

data class ReplayReviewDecisionCase(
    val schemaVersion: Int = 1,
    val id: String,
    val source: ReplayReviewDecisionSource,
    val gameSeed: Long,
    val searchBaseSeed: Long,
    val startingPlayerIndex: Int,
    val semanticPrefix: List<SemanticActionIntentKind>,
    val expectedVisibleHand: Map<String, Int>,
    val gradesByCandidateCard: Map<String, ReplayReviewDecisionGrade>,
    val reviewerJudgment: String,
) {
    init {
        require(schemaVersion == 1)
        require(id.isNotBlank() && reviewerJudgment.isNotBlank())
        require(startingPlayerIndex in 0..1)
        require(semanticPrefix.isNotEmpty())
        require(semanticPrefix.none { it == SemanticActionIntentKind.BOTTOM_CARDS })
        require(expectedVisibleHand.isNotEmpty() && expectedVisibleHand.values.all { it > 0 })
        require(expectedVisibleHand.values.sum() == 7)
        require(gradesByCandidateCard.keys == expectedVisibleHand.keys) {
            "Every visible card name must have exactly one candidate grade"
        }
        require(ReplayReviewDecisionGrade.PREFERRED in gradesByCandidateCard.values)
        require(ReplayReviewDecisionGrade.UNACCEPTABLE in gradesByCandidateCard.values)
    }
}

internal interface ReplayReviewExecutablePosition {
    val caseId: String
    val world: ArgentumSearchWorld
    val actor: String
    val gameId: String
    val searchBaseSeed: Long
    val actionSpaceProfile: SearchActionSpaceProfile
    fun candidateLabel(choice: SemanticChoice): String
    fun grade(choice: SemanticChoice): ReplayReviewDecisionGrade
}

internal data class ReplayReviewDecisionPosition(
    val case: ReplayReviewDecisionCase,
    override val world: ArgentumSearchWorld,
    override val actor: String,
    val currentInformationStateDigest: String,
    val candidateCardsBySignature: Map<String, String>,
) : ReplayReviewExecutablePosition {
    override val caseId: String get() = case.id
    override val gameId: String get() = case.source.gameId
    override val searchBaseSeed: Long get() = case.searchBaseSeed
    override val actionSpaceProfile: SearchActionSpaceProfile
        get() = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1

    override fun candidateLabel(choice: SemanticChoice): String =
        requireNotNull(candidateCardsBySignature[choice.signature]) {
            "Choice ${choice.signature} is not a bottom candidate in ${case.id}"
        }

    override fun grade(choice: SemanticChoice): ReplayReviewDecisionGrade =
        case.gradesByCandidateCard.getValue(candidateLabel(choice))
}

internal data class AuthenticatedReplayReviewDecisionPosition(
    val case: AuthenticatedReplayReviewDecisionCase,
    override val world: ArgentumSearchWorld,
    override val actor: String,
) : ReplayReviewExecutablePosition {
    override val caseId: String get() = case.id
    override val gameId: String get() = case.source.gameId
    override val searchBaseSeed: Long get() = case.searchBaseSeed
    override val actionSpaceProfile: SearchActionSpaceProfile get() = case.actionSpaceProfile
    private val candidatesBySignature = case.candidates.associateBy { it.signature }

    override fun candidateLabel(choice: SemanticChoice): String =
        requireNotNull(candidatesBySignature[choice.signature]) {
            "Choice ${choice.signature} is not authenticated in ${case.id}"
        }.label

    override fun grade(choice: SemanticChoice): ReplayReviewDecisionGrade =
        requireNotNull(candidatesBySignature.getValue(choice.signature).grade)
}

internal object ReplayReviewDecisionCatalog {
    val cases: List<ReplayReviewDecisionCase> = listOf(
        ReplayReviewDecisionCase(
            id = "mulligan-after-one-2251957f",
            source = ReplayReviewDecisionSource(
                gameId = "2251957f-c6dc-4b39-9cfd-4963cf99bb71",
                perspective = "p0",
                frameIndex = 4,
                decisionIndex = 4,
                recordedOuterCommit = "7aef93b5d9a45000e991faf8dfee820759d84a26",
                recordedArgentumCommit = "d16eaabd27f50ff4c366633e87515d2d4a408d39",
                safeInformationStateDigest =
                    "fc27dd2eedbe2eeb424b18f3bfcd2a76b9c1e70e7abc9dc5a4adfd0b11573d6a",
                safeBundleRelativePath =
                    "inspection/2251957f-c6dc-4b39-9cfd-4963cf99bb71/p0.inspection.json",
                safeBundleSha256 =
                    "9a627900e92f803f833d6cb21b0cccf05b2c6a35aeac2fb4f94cd0bd6089c3be",
            ),
            gameSeed = 20260828L,
            searchBaseSeed = 20260828L,
            startingPlayerIndex = 0,
            semanticPrefix = listOf(
                SemanticActionIntentKind.TAKE_MULLIGAN,
                SemanticActionIntentKind.KEEP_HAND,
                SemanticActionIntentKind.TAKE_MULLIGAN,
                SemanticActionIntentKind.KEEP_HAND,
            ),
            expectedVisibleHand = mapOf(
                "Nova Hellkite" to 1,
                "Hexing Squelcher" to 1,
                "Razorkin Needlehead" to 1,
                "Mountain" to 2,
                "Lightning Strike" to 1,
                "Burnout Bashtronaut" to 1,
            ),
            gradesByCandidateCard = mapOf(
                "Hexing Squelcher" to ReplayReviewDecisionGrade.PREFERRED,
                "Razorkin Needlehead" to ReplayReviewDecisionGrade.PREFERRED,
                "Nova Hellkite" to ReplayReviewDecisionGrade.PLAUSIBLE,
                "Lightning Strike" to ReplayReviewDecisionGrade.PLAUSIBLE,
                "Burnout Bashtronaut" to ReplayReviewDecisionGrade.UNACCEPTABLE,
                "Mountain" to ReplayReviewDecisionGrade.UNACCEPTABLE,
            ),
            reviewerJudgment =
                "Bottom a two-drop if possible; the one-drop and a Mountain are unacceptable; " +
                    "the remaining decisions are plausible.",
        ),
    )

    fun reconstruct(
        case: ReplayReviewDecisionCase,
        registry: CardRegistry,
        manifest: DeckManifest,
    ): ReplayReviewDecisionPosition {
        val environment = GameEnvironment.create(registry).also { game ->
            game.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Player 0", manifest.deck()),
                        PlayerConfig("Player 1", manifest.deck()),
                    ),
                    skipMulligans = false,
                    useHandSmoother = false,
                    startingPlayerIndex = case.startingPlayerIndex,
                    seed = case.gameSeed,
                ),
            )
        }
        val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = case.source.gameId,
            seedBase = case.searchBaseSeed,
            effectiveSetupSeed = case.gameSeed,
            expander = UnifiedSemanticExpander(
                actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            ),
            cardRegistry = registry,
            knownDecks = knownDecks,
        )

        case.semanticPrefix.forEachIndexed { index, intent ->
            val choice = world.expandChoices().candidates.singleOrNull { it.actionIntent.kind == intent }
                ?: error("${case.id} prefix $index did not have exactly one $intent choice")
            check(world.step(choice).accepted) { "${case.id} prefix $index rejected $intent" }
        }

        val actor = requireNotNull(world.actorToAct()) { "${case.id} ended before its reviewed decision" }
        require(actor == case.source.perspective) {
            "${case.id} expected ${case.source.perspective} to act, found $actor"
        }
        val information = world.informationState(actor)
        val visibleHand = information.observation.zones
            .filter { it.ownerId == actor && it.zone == "HAND" && !it.hidden }
            .flatMap { it.cards }
            .groupingBy { it.name }
            .eachCount()
        require(visibleHand == case.expectedVisibleHand) {
            "${case.id} visible hand drifted: expected ${case.expectedVisibleHand}, found $visibleHand"
        }

        val candidates = world.expandChoices()
        require(candidates.isExhaustive) { "${case.id} bottom candidate expansion was not exhaustive" }
        require(candidates.candidates.all { it.actionIntent.kind == SemanticActionIntentKind.BOTTOM_CARDS })
        val namesBySignature = candidates.candidates.associate { choice ->
            choice.signature to bottomedCardName(world, choice)
        }
        require(namesBySignature.values.toSet() == case.gradesByCandidateCard.keys) {
            "${case.id} bottom candidates drifted: ${namesBySignature.values.toSet()}"
        }
        require(namesBySignature.size == case.gradesByCandidateCard.size) {
            "${case.id} no longer has one semantic candidate per graded card name"
        }
        return ReplayReviewDecisionPosition(
            case = case,
            world = world,
            actor = actor,
            currentInformationStateDigest = information.informationStateDigest,
            candidateCardsBySignature = namesBySignature,
        )
    }

    private fun bottomedCardName(world: ArgentumSearchWorld, choice: SemanticChoice): String {
        val action = (world.resolveChoice(choice) as? ArgentumResolvedChoice.Action)?.value as? BottomCards
            ?: error("Choice ${choice.signature} did not resolve to BottomCards")
        require(action.cardIds.size == 1) { "Replay-review v1 supports one-card bottoms" }
        return requireNotNull(
            world.authoritativeStateForHost().getEntity(action.cardIds.single())?.get<CardComponent>()?.name,
        ) { "Bottomed card was absent from the trusted reconstructed state" }
    }
}

@Serializable
data class ReplayReviewPolicySpecification(
    val id: String,
    val leaf: LeafEvaluationConfig,
)

@Serializable
data class ReplayReviewDecisionCaseSummary(
    val id: String,
    val source: ReplayReviewDecisionSource? = null,
    val authenticatedSource: ReplayReviewDraftSource? = null,
    val expectedVisibleHand: Map<String, Int> = emptyMap(),
    val gradesByCandidateCard: Map<String, ReplayReviewDecisionGrade> = emptyMap(),
    val gradesByCandidateSignature: Map<String, ReplayReviewDecisionGrade> = emptyMap(),
    val authentication: String = "RECORDED_FIXTURE",
    val safeBundleSha256: String? = null,
    val canonicalReplaySha256: String? = null,
    val intakeBindingSha256: String? = null,
    val reviewerJudgment: String,
)

@Serializable
data class ReplayReviewCandidateResult(
    val signature: String,
    val candidateLabel: String,
    val grade: ReplayReviewDecisionGrade,
    val visits: Int,
    val meanValue: Double,
)

@Serializable
data class ReplayReviewPolicyResult(
    val caseId: String,
    val policyId: String,
    val policyIdentity: String,
    val leaf: LeafEvaluationConfig,
    val evaluationLifecycle: String,
    val chosenCandidateSignature: String,
    val chosenCandidateLabel: String,
    val chosenGrade: ReplayReviewDecisionGrade,
    val selectionKind: SearchTeacherSelectionKind,
    val rootValue: Double,
    val actualSimulations: Int,
    val candidates: List<ReplayReviewCandidateResult>,
)

@Serializable
data class ReplayReviewDecisionReport(
    val schemaVersion: Int = 2,
    val documentKind: String = "replay-review-decision-diagnostic-v2",
    val suiteVersion: String = REPLAY_REVIEW_DECISION_SUITE_VERSION,
    val generatedAtUtc: String,
    val currentOuterCommit: String,
    val currentArgentumCommit: String,
    val particles: Int,
    val simulations: Int,
    val cases: List<ReplayReviewDecisionCaseSummary>,
    val trustedIntakeCases: Int,
    val evaluationLifecycle: String,
    val results: List<ReplayReviewPolicyResult>,
    val unacceptableSelections: Int,
    val limitation: String,
)

internal class ReplayReviewDecisionRunner(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val outerCommit: String,
    private val argentumCommit: String,
) {
    fun run(
        particles: Int,
        simulations: Int,
        maxPolicyDecisions: Int,
        policies: List<ReplayReviewPolicySpecification> = defaultPolicies,
        authenticatedCases: List<AuthenticatedReplayReviewDecisionCase> = emptyList(),
    ): ReplayReviewDecisionReport {
        require(particles > 0 && simulations > 0 && maxPolicyDecisions > 0)
        require(policies.isNotEmpty() && policies.map { it.id }.distinct().size == policies.size)
        authenticatedCases.forEach(AuthenticatedReplayReviewDecisionCase::requireIntakeBinding)
        val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)
        val positionFactories: List<() -> ReplayReviewExecutablePosition> =
            ReplayReviewDecisionCatalog.cases.map { case ->
                { ReplayReviewDecisionCatalog.reconstruct(case, registry, manifest) }
            } + authenticatedCases.map { case ->
                { reconstructAuthenticated(case) }
            }
        val results = positionFactories.flatMap { positionFactory ->
            policies.map { policy ->
                val position = positionFactory()
                val parameters = SearchTeacherPolicyParameters(
                    particles = particles,
                    simulations = simulations,
                    maxPolicyDecisions = maxPolicyDecisions,
                    explorationConstant = 1.4,
                    leaf = policy.leaf,
                    actionSpaceProfile = position.actionSpaceProfile,
                    baseSeed = position.searchBaseSeed,
                    profileId = policy.id,
                )
                val session = SearchTeacherPolicySession(
                    root = position.world,
                    viewer = position.actor,
                    registry = registry,
                    knownDecks = knownDecks,
                    parameters = parameters,
                    opponentPolicy = defaultMonoRedOpponentPolicy(),
                    gameId = position.gameId,
                )
                val selection = session.select(
                    position.world,
                    position.actor,
                    ComponentSeeds.derive(REPLAY_REVIEW_DECISION_SUITE_VERSION, position.caseId, policy.id, "search"),
                )
                val search = requireNotNull(selection.search) { "Reviewed choices must remain searched" }
                ReplayReviewPolicyResult(
                    caseId = position.caseId,
                    policyId = policy.id,
                    policyIdentity = session.policyIdentity,
                    leaf = policy.leaf,
                    evaluationLifecycle = SNAPSHOT_EVALUATION_LIFECYCLE,
                    chosenCandidateSignature = selection.choice.signature,
                    chosenCandidateLabel = position.candidateLabel(selection.choice),
                    chosenGrade = position.grade(selection.choice),
                    selectionKind = selection.kind,
                    rootValue = search.rootValue,
                    actualSimulations = search.diagnostics.simulations,
                    candidates = search.candidates.map { candidate ->
                        ReplayReviewCandidateResult(
                            signature = candidate.choice.signature,
                            candidateLabel = position.candidateLabel(candidate.choice),
                            grade = position.grade(candidate.choice),
                            visits = candidate.visits,
                            meanValue = candidate.meanValue,
                        )
                    }.sortedBy { it.candidateLabel },
                )
            }
        }
        return ReplayReviewDecisionReport(
            generatedAtUtc = Instant.now().toString(),
            currentOuterCommit = outerCommit,
            currentArgentumCommit = argentumCommit,
            particles = particles,
            simulations = simulations,
            cases = ReplayReviewDecisionCatalog.cases.map { case ->
                ReplayReviewDecisionCaseSummary(
                    id = case.id,
                    source = case.source,
                    expectedVisibleHand = case.expectedVisibleHand,
                    gradesByCandidateCard = case.gradesByCandidateCard,
                    reviewerJudgment = case.reviewerJudgment,
                )
            } + authenticatedCases.map { case ->
                ReplayReviewDecisionCaseSummary(
                    id = case.id,
                    authenticatedSource = case.source,
                    gradesByCandidateSignature = case.candidates.associate {
                        it.signature to requireNotNull(it.grade)
                    },
                    authentication = "TRUSTED_CANONICAL_REPLAY_INTAKE",
                    safeBundleSha256 = case.safeBundleSha256,
                    canonicalReplaySha256 = case.canonicalReplaySha256,
                    intakeBindingSha256 = case.intakeBindingSha256,
                    reviewerJudgment = case.reviewerJudgment,
                )
            },
            trustedIntakeCases = authenticatedCases.size,
            evaluationLifecycle = SNAPSHOT_EVALUATION_LIFECYCLE,
            results = results,
            unacceptableSelections = results.count {
                it.chosenGrade == ReplayReviewDecisionGrade.UNACCEPTABLE
            },
            limitation =
                "These replay-derived cases grade the exact candidates admitted by their declared action-space " +
                "profiles; intentionally profile-suppressed standalone mana actions are not graded. They grade " +
                "only the named policies at the declared seeds and " +
                "budgets. Sessions start at the reviewed decision rather than replaying a lived belief " +
                "posterior; they do not establish general strategy, frequency, or overall playing strength.",
        )
    }

    private fun reconstructAuthenticated(
        case: AuthenticatedReplayReviewDecisionCase,
    ): AuthenticatedReplayReviewDecisionPosition {
        val world = reconstructReplayReviewWorld(
            registry = registry,
            manifest = manifest,
            gameId = case.source.gameId,
            gameSeed = case.gameSeed,
            searchBaseSeed = case.searchBaseSeed,
            startingPlayerIndex = case.startingPlayerIndex,
            profile = case.actionSpaceProfile,
            semanticPrefix = case.semanticPrefix,
        )
        val actor = requireNotNull(world.actorToAct()) { "${case.id} ended before its reviewed decision" }
        require(actor == case.source.perspectivePlayerId)
        require(world.informationState(actor).informationStateDigest == case.source.informationStateDigest)
        val expansion = world.expandChoices()
        require(expansion.isProfileExhaustive && expansion.proposalVersion == case.source.proposalVersion)
        require(expansion.candidates.map { it.signature } == case.candidates.map { it.signature })
        return AuthenticatedReplayReviewDecisionPosition(case, world, actor)
    }

    companion object {
        const val SNAPSHOT_EVALUATION_LIFECYCLE: String = "SNAPSHOT_COLD_START_AT_REVIEWED_DECISION"

        val defaultPolicies: List<ReplayReviewPolicySpecification> = listOf(
            ReplayReviewPolicySpecification(
                id = "production-visible-v2",
                leaf = LeafEvaluationConfig(
                    LeafStateSource.BOUNDED_ROLLOUT,
                    LeafEvaluator.MTGALLIUM_VISIBLE_V2,
                ),
            ),
            ReplayReviewPolicySpecification(
                id = "experimental-tactical-v3",
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_INFORMATION_STATE,
                    LeafEvaluator.MTGALLIUM_TACTICAL_V3,
                ),
            ),
        )
    }
}

internal fun renderReplayReviewDecisionReport(report: ReplayReviewDecisionReport): String = buildString {
    appendLine("# Replay Review Decision Suite")
    appendLine()
    appendLine(
        "The run evaluated ${report.results.size} policy/case combinations across " +
            "${report.cases.size} replay-derived case(s), including ${report.trustedIntakeCases} " +
            "authenticated by trusted canonical-replay intake. " +
            "${report.unacceptableSelections} selections were graded unacceptable.",
    )
    appendLine()
    report.results.forEach { result ->
        appendLine(
            "- `${result.policyId}` on `${result.caseId}` chose **${result.chosenCandidateLabel}** " +
                "(${result.chosenGrade}); ${result.actualSimulations} simulations.",
        )
    }
    appendLine()
    appendLine("Limit: ${report.limitation}")
}
