package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumBeliefSupport
import org.mtgallium.agent.infoset.argentum.ArgentumKnownDeckBeliefWorldSource
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile

internal const val ISSUE_0013_STAGE_A_VERSION = "issue-0013-stage-a-reference-oracle-v1"
private const val ISSUE_0013_STAGE_A_SEED = 6_230_013_001L
private const val DEFAULT_STAGE_A_TRIALS = 4_096
private const val STAGE_A_FAMILY_ERROR_BOUND = 1e-6

@Serializable
internal data class FreshWorldReferenceOracleReport(
    val schemaVersion: Int = 1,
    val experimentVersion: String = ISSUE_0013_STAGE_A_VERSION,
    val outerCommit: String,
    val argentumCommit: String,
    val deckScope: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val referenceLaw: String,
    val seed: Long,
    val trialsPerCase: Int,
    val familyErrorBound: Double,
    val cases: List<FreshWorldOracleCaseResult>,
    val issue0019AdmissionTests: List<String>,
    val passed: Boolean,
    val failureReasons: List<String>,
)

@Serializable
internal data class FreshWorldOracleCaseResult(
    val id: String,
    val proposition: String,
    val exactSupportSize: Int,
    val exactProbabilities: Map<String, Double>,
    val observedCounts: Map<String, Int>,
    val observedProbabilities: Map<String, Double>,
    val unexpectedOutcomes: Map<String, Int>,
    val missingOutcomes: List<String>,
    val maximumAbsoluteProbabilityError: Double,
    val simultaneousAbsoluteErrorBound: Double,
    val supportViolations: Int,
    val proposalAttempts: Int,
    val proposalRejections: Int,
    val materializationMillis: Double,
    val passed: Boolean,
    val failureReasons: List<String>,
)

/** Exact expected laws plus deterministic sampling checks for chance-only, hard-conditioned roots. */
internal class FreshWorldReferenceOracle(
    private val registry: CardRegistry,
    private val outerCommit: String,
    private val argentumCommit: String,
) {
    fun run(trialsPerCase: Int = DEFAULT_STAGE_A_TRIALS): FreshWorldReferenceOracleReport {
        require(trialsPerCase >= 256)
        val cases = listOf(
            ordinaryLibraryOrder(trialsPerCase),
            coupledHandAndLibrary(trialsPerCase),
            pinnedCardAndOrdering(trialsPerCase),
            orderedLondonBottom(trialsPerCase),
        )
        val failures = cases.filterNot(FreshWorldOracleCaseResult::passed).map { result ->
            "${result.id}: ${result.failureReasons.joinToString()}"
        }
        return FreshWorldReferenceOracleReport(
            outerCommit = outerCommit,
            argentumCommit = argentumCommit,
            deckScope = "mono-red-standard-2026-07-30 card families; exact authored small pools",
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            referenceLaw = "uniform residual card-copy permutation across inaccessible hand/library slots after hard conditioning",
            seed = ISSUE_0013_STAGE_A_SEED,
            trialsPerCase = trialsPerCase,
            familyErrorBound = STAGE_A_FAMILY_ERROR_BOUND,
            cases = cases,
            issue0019AdmissionTests = listOf(
                "ReachableSemanticTrustTest.same warp-exiled Nova is castable only with permission across rules observation and belief worlds",
                "ReachableSemanticTrustTest.Rockface creature-only mana differs from ordinary red in policy input and legal casts",
                "ReachableSemanticTrustTest.London mulligan bottom order is private knowledge and a hard world-support constraint",
            ),
            passed = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    private fun ordinaryLibraryOrder(trials: Int): FreshWorldOracleCaseResult {
        val deck = linkedMapOf(
            "Mountain" to 7,
            "Shock" to 1,
            "Hired Claw" to 1,
            "Rockface Village" to 1,
        )
        val fixture = fixture("ordinary-library-order", deck, skipMulligans = true)
        val viewer = fixture.environment.playerIds[0]
        val all = fixture.environment.state.getHand(viewer) + fixture.environment.state.getLibrary(viewer)
        val library = listOf("Shock", "Hired Claw", "Rockface Village").map { wanted ->
            all.single { cardName(fixture.environment.state, it) == wanted }
        }
        val state = fixture.environment.state.copy(
            zones = fixture.environment.state.zones +
                (ZoneKey(viewer, Zone.HAND) to all.filterNot { it in library }) +
                (ZoneKey(viewer, Zone.LIBRARY) to library),
        )
        val world = fixture.world(state)
        val exact = permutations(library.map { cardName(state, it) })
            .associate { names -> names.joinToString(" > ") to 1.0 / 6.0 }
        return sampleCase(
            id = "ordinary-shuffle-library-order",
            proposition = "The three residual distinct cards occupy the viewer's unknown library order uniformly.",
            root = world,
            viewerAlias = "p0",
            knownDecks = fixture.knownDecks,
            trials = trials,
            exactProbabilities = exact,
        ) { sampled -> sampled.privilegedDebugSnapshot().libraries.getValue("p0").joinToString(" > ") }
    }

    private fun coupledHandAndLibrary(trials: Int): FreshWorldOracleCaseResult {
        val deck = linkedMapOf(
            "Mountain" to 4,
            "Shock" to 2,
            "Hired Claw" to 1,
            "Rockface Village" to 1,
        )
        val fixture = fixture("coupled-hand-library", deck, skipMulligans = true)
        val exact = deck.mapValues { (_, count) -> count.toDouble() / deck.values.sum() }
            .mapKeys { (libraryCard, _) -> coupledSignature(deck, libraryCard) }
        return sampleCase(
            id = "coupled-hidden-hand-library",
            proposition = "The opponent's one-card library marginal follows deck multiplicity and its seven-card hand is the exact complement.",
            root = fixture.world(),
            viewerAlias = "p0",
            knownDecks = fixture.knownDecks,
            trials = trials,
            exactProbabilities = exact,
        ) { sampled ->
            val snapshot = sampled.privilegedDebugSnapshot()
            val libraryCard = snapshot.libraries.getValue("p1").single()
            val signature = "library=$libraryCard|hand=${cardCounts(snapshot.hiddenHands.getValue("p1"))}"
            check(signature == coupledSignature(deck, libraryCard)) { "Hand/library complement was broken" }
            signature
        }
    }

    private fun pinnedCardAndOrdering(trials: Int): FreshWorldOracleCaseResult {
        val deck = linkedMapOf(
            "Mountain" to 5,
            "Shock" to 2,
            "Hired Claw" to 1,
            "Rockface Village" to 1,
            "Nova Hellkite" to 1,
        )
        val fixture = fixture("pinned-card-order", deck, skipMulligans = true)
        val opponent = fixture.environment.playerIds[1]
        val all = fixture.environment.state.getHand(opponent) + fixture.environment.state.getLibrary(opponent)
        val knownGraveyard = all.single { cardName(fixture.environment.state, it) == "Hired Claw" }
        val residualIds = all.filterNot { it == knownGraveyard }
        val library = residualIds.take(3)
        val hand = residualIds.drop(3)
        val state = fixture.environment.state.copy(
            zones = fixture.environment.state.zones +
                (ZoneKey(opponent, Zone.HAND) to hand) +
                (ZoneKey(opponent, Zone.LIBRARY) to library) +
                (ZoneKey(opponent, Zone.GRAVEYARD) to listOf(knownGraveyard)),
        )
        val residual = deck.toMutableMap().also { counts ->
            counts["Hired Claw"] = counts.getValue("Hired Claw") - 1
        }.filterValues { it > 0 }
        val denominator = residual.values.sum().toDouble()
        val exact = residual.mapValues { (_, count) -> count / denominator }
        val world = fixture.world(state)
        return sampleCase(
            id = "hard-known-public-card",
            proposition = "A publicly known graveyard card stays fixed and is subtracted exactly before the hidden hand/library top follows the residual multiset.",
            root = world,
            viewerAlias = "p0",
            knownDecks = fixture.knownDecks,
            trials = trials,
            exactProbabilities = exact,
        ) { sampled ->
            val snapshot = sampled.privilegedDebugSnapshot()
            val sampledLibrary = snapshot.libraries.getValue("p1")
            val graveyardNames = sampled.authoritativeStateForHost().getGraveyard(opponent)
                .map { cardName(sampled.authoritativeStateForHost(), it) }
            check(graveyardNames == listOf("Hired Claw")) { "Known public graveyard identity moved" }
            val hiddenCounts = snapshot.hiddenHands.getValue("p1") + sampledLibrary
            check(cardCounts(hiddenCounts) == cardCounts(residual.flatMap { (name, count) -> List(count) { name } })) {
                "Known-card depletion or hidden-zone coupling was broken"
            }
            sampledLibrary.first()
        }
    }

    private fun orderedLondonBottom(trials: Int): FreshWorldOracleCaseResult {
        val deck = linkedMapOf(
            "Mountain" to 4,
            "Hired Claw" to 4,
            "Rockface Village" to 4,
            "Nova Hellkite" to 4,
            "Shock" to 4,
        )
        val fixture = fixture("ordered-london-bottom", deck, skipMulligans = false)
        val world = fixture.world()
        var rootMulligans = 0
        var rootKept = false
        var decisions = 0
        while (!rootKept) {
            check(decisions++ < 8)
            val rootActs = world.actorToAct() == "p0"
            val takeRootMulligan = rootActs && rootMulligans < 2
            val choice = world.expandChoices().candidates.single { candidate ->
                val action = (world.resolveChoice(candidate) as? org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice.Action)?.value
                if (takeRootMulligan) action is TakeMulligan else action is KeepHand
            }
            check(world.step(choice).accepted)
            if (takeRootMulligan) rootMulligans++
            if (rootActs && !takeRootMulligan) rootKept = true
        }
        var expansion = world.expandChoices()
        while (expansion.candidates.none { candidate ->
                ((world.resolveChoice(candidate) as? org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice.Action)
                    ?.value is BottomCards)
            }
        ) {
            val keep = expansion.candidates.single { candidate ->
                ((world.resolveChoice(candidate) as? org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice.Action)
                    ?.value is KeepHand)
            }
            check(world.step(keep).accepted)
            expansion = world.expandChoices()
        }
        val bottom = expansion.candidates.first { candidate ->
            val action = (world.resolveChoice(candidate) as? org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice.Action)
                ?.value as? BottomCards
            action != null && action.cardIds.map { cardName(world.authoritativeStateForHost(), it) }.distinct().size == 2
        }
        check(world.step(bottom).accepted)
        val expectedBottom = world.informationState("p0").knowledge.knownLibraryOrders.single {
            it.playerId == "p0"
        }.bottom
        val actorLibrary = world.privilegedDebugSnapshot().libraries.getValue("p0")
        check(actorLibrary.takeLast(expectedBottom.size) == expectedBottom)
        val residualTopPool = actorLibrary.dropLast(expectedBottom.size)
        val exact = residualTopPool.groupingBy { it }.eachCount()
            .mapValues { (_, count) -> count.toDouble() / residualTopPool.size }
        return sampleCase(
            id = "actor-private-ordered-london-bottom",
            proposition = "The entitled actor's ordered two-card London-bottom suffix is fixed while the next unknown card follows the exact residual multiset.",
            root = world,
            viewerAlias = "p0",
            knownDecks = fixture.knownDecks,
            trials = trials,
            exactProbabilities = exact,
        ) { sampled ->
            val library = sampled.privilegedDebugSnapshot().libraries.getValue("p0")
            check(library.takeLast(expectedBottom.size) == expectedBottom) { "Ordered London-bottom suffix changed" }
            library.first()
        }
    }

    private fun sampleCase(
        id: String,
        proposition: String,
        root: ArgentumSearchWorld,
        viewerAlias: String,
        knownDecks: Map<String, Map<String, Int>>,
        trials: Int,
        exactProbabilities: Map<String, Double>,
        signature: (ArgentumSearchWorld) -> String,
    ): FreshWorldOracleCaseResult {
        require(exactProbabilities.isNotEmpty())
        require(kotlin.math.abs(exactProbabilities.values.sum() - 1.0) < 1e-12)
        val information = root.informationState(viewerAlias)
        val source = ArgentumKnownDeckBeliefWorldSource(root, registry)
        val counts = mutableMapOf<String, Int>()
        var supportViolations = 0
        var attempts = 0
        var rejections = 0
        val started = System.nanoTime()
        repeat(trials) { trial ->
            val batch = source.sample(
                rootInformation = information,
                knownDecks = knownDecks,
                beliefSeed = ComponentSeeds.derive(ISSUE_0013_STAGE_A_SEED, id, trial, "fresh-world-oracle"),
                count = 1,
            )
            attempts += batch.diagnostics.proposalAttempts
            rejections += batch.diagnostics.rejectedParticles
            val sampled = batch.particles.single().value as ArgentumSearchWorld
            if (ArgentumBeliefSupport.completeFailures(listOf(sampled), viewerAlias, information).isNotEmpty()) {
                supportViolations++
            }
            val outcome = runCatching { signature(sampled) }.getOrElse { failure ->
                supportViolations++
                "<constraint-failure:${failure::class.simpleName}>"
            }
            counts[outcome] = counts.getOrDefault(outcome, 0) + 1
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000.0
        val observed = counts.mapValues { (_, count) -> count.toDouble() / trials }.toSortedMap()
        val expected = exactProbabilities.toSortedMap()
        val unexpected = counts.filterKeys { it !in expected }.toSortedMap()
        val missing = expected.keys.filter { it !in counts }
        val maximumError = expected.maxOf { (outcome, probability) ->
            kotlin.math.abs((observed[outcome] ?: 0.0) - probability)
        }
        val simultaneousBound = sqrt(
            ln(2.0 * expected.size / STAGE_A_FAMILY_ERROR_BOUND) / (2.0 * trials)
        )
        val failures = buildList {
            if (supportViolations > 0) add("$supportViolations represented-support/constraint violations")
            if (unexpected.isNotEmpty()) add("unexpected support ${unexpected.keys}")
            if (missing.isNotEmpty()) add("missing exact outcomes $missing")
            if (maximumError > simultaneousBound) {
                add("maximum probability error $maximumError exceeds simultaneous bound $simultaneousBound")
            }
        }
        return FreshWorldOracleCaseResult(
            id = id,
            proposition = proposition,
            exactSupportSize = expected.size,
            exactProbabilities = expected,
            observedCounts = counts.toSortedMap(),
            observedProbabilities = observed,
            unexpectedOutcomes = unexpected,
            missingOutcomes = missing,
            maximumAbsoluteProbabilityError = maximumError,
            simultaneousAbsoluteErrorBound = simultaneousBound,
            supportViolations = supportViolations,
            proposalAttempts = attempts,
            proposalRejections = rejections,
            materializationMillis = elapsedMillis,
            passed = failures.isEmpty(),
            failureReasons = failures,
        )
    }

    private fun fixture(
        id: String,
        deck: Map<String, Int>,
        skipMulligans: Boolean,
    ): OracleFixture {
        val environment = GameEnvironment.create(registry).also { game ->
            game.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Player 0", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                        PlayerConfig("Player 1", Deck.of(*deck.entries.map { it.key to it.value }.toTypedArray())),
                    ),
                    skipMulligans = skipMulligans,
                    useHandSmoother = false,
                    startingPlayerIndex = 0,
                    seed = ComponentSeeds.derive(ISSUE_0013_STAGE_A_SEED, id, "fixture"),
                )
            )
        }
        return OracleFixture(id, deck, environment, registry)
    }
}

private data class OracleFixture(
    val id: String,
    val deck: Map<String, Int>,
    val environment: GameEnvironment,
    val registry: CardRegistry,
) {
    val knownDecks = mapOf("p0" to deck, "p1" to deck)

    fun world(state: GameState = environment.state): ArgentumSearchWorld {
        val fork = environment.fork().also { it.restore(state, environment.playerIds, environment.stepCount) }
        return ArgentumSearchWorld.create(
            environment = fork,
            gameId = "issue-0013-stage-a:$id",
            seedBase = ISSUE_0013_STAGE_A_SEED,
            expander = UnifiedSemanticExpander(
                actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            ),
            cardRegistry = registry,
            knownDecks = knownDecks,
        )
    }
}

private fun cardName(state: GameState, id: EntityId): String =
    requireNotNull(state.getEntity(id)?.get<CardComponent>()?.name)

private fun cardCounts(names: List<String>): String = names.groupingBy { it }.eachCount().toSortedMap()
    .entries.joinToString(",") { (name, count) -> "$name=$count" }

private fun coupledSignature(deck: Map<String, Int>, libraryCard: String): String {
    val hand = deck.toMutableMap()
    hand[libraryCard] = hand.getValue(libraryCard) - 1
    return "library=$libraryCard|hand=" + hand.filterValues { it > 0 }.toSortedMap().entries
        .joinToString(",") { (name, count) -> "$name=$count" }
}

private fun <T> permutations(values: List<T>): Set<List<T>> {
    if (values.isEmpty()) return setOf(emptyList())
    return values.indices.flatMapTo(linkedSetOf()) { index ->
        val remaining = values.toMutableList().also { it.removeAt(index) }
        permutations(remaining).map { listOf(values[index]) + it }
    }
}

internal fun renderFreshWorldReferenceOracle(report: FreshWorldReferenceOracleReport): String = buildString {
    appendLine("# Issue 0013 Stage A — fresh complete-world reference oracle")
    appendLine()
    appendLine(if (report.passed) "Result: **PASS**." else "Result: **FAIL**.")
    appendLine()
    appendLine(
        "The oracle compares the existing known-deck materializer with exact, enumerable " +
            "chance-only laws after hard conditioning. It does not claim a behavioral posterior."
    )
    appendLine()
    appendLine("| Case | Exact support | Max error | Simultaneous bound | Support violations | Result |")
    appendLine("| --- | ---: | ---: | ---: | ---: | --- |")
    report.cases.forEach { case ->
        appendLine(
            "| `${case.id}` | ${case.exactSupportSize} | " +
                "${"%.4f".format(case.maximumAbsoluteProbabilityError)} | " +
                "${"%.4f".format(case.simultaneousAbsoluteErrorBound)} | " +
                "${case.supportViolations} | ${if (case.passed) "PASS" else "FAIL"} |"
        )
    }
    appendLine()
    appendLine("- Trials per case: ${report.trialsPerCase}")
    appendLine("- Reference: ${report.referenceLaw}")
    appendLine("- Argentum: `${report.argentumCommit}`")
    appendLine("- Action profile: `${report.actionSpaceProfile.profileId}`")
    appendLine("- Proposal rejections: ${report.cases.sumOf { it.proposalRejections }}")
    appendLine()
    appendLine(
        "Issue-0019 admission coverage is retained by the named Warp-permission, restricted-mana, " +
            "and ordered-London-bottom focused tests; Stage A's ordered-bottom case also exercises " +
            "the actor-private suffix in the statistical oracle."
    )
    if (report.failureReasons.isNotEmpty()) {
        appendLine()
        appendLine("Failures:")
        report.failureReasons.forEach { appendLine("- $it") }
    }
}
