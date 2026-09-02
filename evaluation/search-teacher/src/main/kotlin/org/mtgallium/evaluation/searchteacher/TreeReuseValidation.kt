package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.model.Deck
import java.time.Instant
import java.util.Random
import kotlin.math.tanh
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.InformationSetSearchConfig
import org.mtgallium.agent.infoset.core.InformationSetSearchReuseConfig
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.searchteacher.MonoRedInformationEvaluator
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyExpansionOmissionReason
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.PolicyObservation
import org.mtgallium.agent.infoset.core.PolicyPlayerView
import org.mtgallium.agent.infoset.core.ReusableSearchWorld
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SearchStepResult
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SearchWorldReuseKey
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import org.mtgallium.agent.infoset.core.Weighted
import org.mtgallium.agent.infoset.core.exactSingletonPassOrNull
import org.mtgallium.agent.infoset.core.policySingletonPassOrNull
import org.mtgallium.agent.searchteacher.PolicyCompressionConfig
import org.mtgallium.agent.searchteacher.SearchTeacherAutomaticSelection
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind

internal const val TREE_REUSE_VALIDATION_SCHEMA_VERSION = 3

@Serializable
internal data class PolicySingletonValidation(
    val rulesCandidateFamilies: List<SemanticOperationFamily>,
    val profileCandidateFamilies: List<SemanticOperationFamily>,
    val rulesExhaustive: Boolean,
    val profileExhaustive: Boolean,
    val omissionReasons: Set<PolicyExpansionOmissionReason>,
    val exactForced: Boolean,
    val selectionKind: SearchTeacherSelectionKind?,
    val simulationsAvoided: Int,
    val semanticChoicePreserved: Boolean,
    val passed: Boolean,
)

@Serializable
internal data class StrategicLandHoldValidation(
    val candidateFamilies: List<SemanticOperationFamily>,
    val automaticSelectionKind: SearchTeacherSelectionKind?,
    val firstLandNetValueAfterLeavingHand: Double,
    val sixthLandNetValueAfterLeavingHand: Double,
    val passRemainsSearchable: Boolean,
    val diminishingResourceValue: Boolean,
    val passed: Boolean,
)

@Serializable
internal data class TreeReuseFactorialCell(
    val id: String,
    val singletonCompression: Boolean,
    val treeReuse: Boolean,
    val transitionCacheEnabled: Boolean,
    val decisions: Int,
    val chosenLabels: List<String>,
    val decisionLatenciesMillis: List<Double>,
    val totalFreshSimulations: Int,
    val totalReusedSimulations: Int,
    val totalRefreshedSimulations: Int,
    val totalSearchWorldSteps: Int,
    val totalCompressedSingletonPasses: Int,
    val discardReasons: Map<String, Int>,
    val maximumRetainedSnapshots: Int,
    val regret: Double,
)

@Serializable
internal data class TreeReuseValidationGates(
    val singletonSemanticEquivalence: Boolean,
    val deterministicReplay: Boolean,
    val factorialSemanticEquivalence: Boolean,
    val reuseWorkRatio: Double,
    val reuseWorkPassed: Boolean,
    val latencyRatioUpper95: Double,
    val latencyPassed: Boolean,
    val maximumRegret: Double,
    val regretPassed: Boolean,
    val memoryPassed: Boolean,
)

@Serializable
internal data class TreeReuseLatencyTrial(
    val repetition: Int,
    val executionOrder: List<String>,
    val singletonOnlyMillis: List<Double>,
    val combinedMillis: List<Double>,
)

@Serializable
internal data class TreeReuseValidationReport(
    val schemaVersion: Int = TREE_REUSE_VALIDATION_SCHEMA_VERSION,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val outerDirty: Boolean,
    val argentumDirty: Boolean,
    val baseSeed: Long,
    val simulationsPerDecision: Int,
    val maxPolicyDecisions: Int,
    val singleton: PolicySingletonValidation,
    val strategicLandHold: StrategicLandHoldValidation,
    val factorial: List<TreeReuseFactorialCell>,
    val latencyTrials: List<TreeReuseLatencyTrial> = emptyList(),
    val gates: TreeReuseValidationGates,
    val passed: Boolean,
    val limitations: List<String>,
)

internal class TreeReuseValidationRunner(
    private val registry: CardRegistry,
    private val seed: Long,
) {
    private val simulations = 64
    private val horizon = 8
    private val decisions = 8
    private val latencyRepetitions = 8

    fun run(provenance: org.mtgallium.evaluation.searchteacher.evidence.RunProvenance): TreeReuseValidationReport {
        val singleton = measureFastProfileSingleton()
        val strategicLandHold = measureStrategicLandHold()
        val cells = listOf(
            runCell("cold", compression = false, reuse = false),
            runCell("singleton-only", compression = true, reuse = false),
            runCell("reuse-only", compression = false, reuse = true),
            runCell("combined", compression = true, reuse = true),
        )
        val replay = listOf(
            runCell("cold-replay", compression = false, reuse = false),
            runCell("singleton-only-replay", compression = true, reuse = false),
            runCell("reuse-only-replay", compression = false, reuse = true),
            runCell("combined-replay", compression = true, reuse = true),
        )
        // Keep class loading and JIT compilation out of the latency comparison.
        runCell("latency-warmup-singleton", compression = true, reuse = false)
        runCell("latency-warmup-combined", compression = true, reuse = true)
        val latencyTrials = List(latencyRepetitions) { repetition ->
            val executionOrder = if (repetition % 2 == 0) {
                listOf("singleton-only", "combined")
            } else {
                listOf("combined", "singleton-only")
            }
            val measured = executionOrder.associateWith { cell ->
                runCell(
                    id = "latency-$repetition-$cell",
                    compression = true,
                    reuse = cell == "combined",
                )
            }
            val singletonLatency = measured.getValue("singleton-only")
            val combinedLatency = measured.getValue("combined")
            check(singletonLatency.chosenLabels == combinedLatency.chosenLabels)
            TreeReuseLatencyTrial(
                repetition = repetition,
                executionOrder = executionOrder,
                singletonOnlyMillis = singletonLatency.decisionLatenciesMillis,
                combinedMillis = combinedLatency.decisionLatenciesMillis,
            )
        }
        val byId = cells.associateBy(TreeReuseFactorialCell::id)
        val singletonOnly = byId.getValue("singleton-only")
        val combined = byId.getValue("combined")
        val workRatio = combined.totalSearchWorldSteps.toDouble() /
            singletonOnly.totalSearchWorldSteps.coerceAtLeast(1)
        val latencyUpper = pairedBootstrapRatioUpper95(
            baseline = latencyTrials.flatMap(TreeReuseLatencyTrial::singletonOnlyMillis),
            candidate = latencyTrials.flatMap(TreeReuseLatencyTrial::combinedMillis),
            seed = seed,
        )
        val deterministic = cells.zip(replay).all { (first, second) ->
            first.chosenLabels == second.chosenLabels &&
                first.totalFreshSimulations == second.totalFreshSimulations &&
                first.totalReusedSimulations == second.totalReusedSimulations &&
                first.totalRefreshedSimulations == second.totalRefreshedSimulations &&
                first.totalSearchWorldSteps == second.totalSearchWorldSteps &&
                first.transitionCacheEnabled == second.transitionCacheEnabled &&
                first.discardReasons == second.discardReasons
        }
        val semantic = cells.map(TreeReuseFactorialCell::chosenLabels).distinct().size == 1
        val maximumRegret = cells.maxOf(TreeReuseFactorialCell::regret)
        val memoryPassed = cells.all { it.maximumRetainedSnapshots <= simulations * horizon }
        val gates = TreeReuseValidationGates(
            singletonSemanticEquivalence = singleton.passed,
            deterministicReplay = deterministic,
            factorialSemanticEquivalence = semantic,
            reuseWorkRatio = workRatio,
            reuseWorkPassed = workRatio <= 0.80,
            latencyRatioUpper95 = latencyUpper,
            latencyPassed = latencyUpper < 1.0,
            maximumRegret = maximumRegret,
            regretPassed = maximumRegret <= 0.02,
            memoryPassed = memoryPassed,
        )
        val passed = gates.singletonSemanticEquivalence && strategicLandHold.passed && gates.deterministicReplay &&
            gates.factorialSemanticEquivalence && gates.reuseWorkPassed && gates.latencyPassed &&
            gates.regretPassed && gates.memoryPassed
        return TreeReuseValidationReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = provenance.outerCommit,
            argentumCommit = provenance.checkedOutArgentumCommit,
            outerDirty = provenance.outerDirty,
            argentumDirty = provenance.argentumDirty,
            baseSeed = seed,
            simulationsPerDecision = simulations,
            maxPolicyDecisions = horizon,
            singleton = singleton,
            strategicLandHold = strategicLandHold,
            factorial = cells,
            latencyTrials = latencyTrials,
            gates = gates,
            passed = passed,
            limitations = listOf(
                "The deterministic factorial is a controlled complete-world search microbenchmark, not a win-rate claim.",
                "Wall-clock latency uses eight alternating-order paired repetitions after warm-up; " +
                    "deterministic SearchWorld.step counts remain the primary work metric.",
                "The 2×2 factorial disables exact simulated-prefix caching so that its reuse effect is not " +
                    "masked by an orthogonal within-search optimization; the production profiler measures both.",
                "The full seat-swapped tournament remains blocked until a clean packet receives external sign-off.",
            ),
        )
    }

    private fun measureFastProfileSingleton(): PolicySingletonValidation {
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Singleton A", Deck.of("Mountain" to 60)),
                        PlayerConfig("Singleton B", Deck.of("Mountain" to 60)),
                    ),
                    seed = seed,
                    skipMulligans = true,
                    useHandSmoother = false,
                    startingPlayerIndex = 0,
                )
            )
        }
        var playedLand = false
        for (ignored in 0 until 200) {
            val land = environment.legalActions().firstOrNull { it.affordable && it.action is PlayLand }
            if (land != null) {
                environment.step(land.action)
                playedLand = true
                break
            }
            val pass = environment.legalActions().single { it.action is PassPriority }
            environment.step(pass.action)
        }
        check(playedLand) { "Could not reach the fast-profile singleton fixture" }
        val exact = UnifiedSemanticExpander().expand(environment, seed).policy
        val fast = UnifiedSemanticExpander(
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
        ).expand(environment, seed).policy
        val automatic = SearchTeacherAutomaticSelection.classify(fast)
        val semanticChoicePreserved = automatic == null && fast.candidates.singleOrNull() != null
        val passed = exact.candidates.any { it.operationFamily == SemanticOperationFamily.MANA_ABILITY } &&
            exact.candidates.any { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY } &&
            fast.exactSingletonPassOrNull() == null && fast.policySingletonPassOrNull() != null &&
            semanticChoicePreserved
        return PolicySingletonValidation(
            rulesCandidateFamilies = exact.candidates.map { it.operationFamily },
            profileCandidateFamilies = fast.candidates.map { it.operationFamily },
            rulesExhaustive = exact.isExhaustive,
            profileExhaustive = fast.isProfileExhaustive,
            omissionReasons = fast.omissionReasons,
            exactForced = fast.exactSingletonPassOrNull() != null,
            selectionKind = automatic?.kind,
            simulationsAvoided = 0,
            semanticChoicePreserved = semanticChoicePreserved,
            passed = passed,
        )
    }

    private fun measureStrategicLandHold(): StrategicLandHoldValidation {
        fun choice(label: String, family: SemanticOperationFamily) = SemanticChoice.create(
            kind = SemanticChoiceKind.ACTION,
            operationFamily = family,
            display = SemanticChoiceDisplay(label),
            canonicalPayload = buildJsonObject { put("choice", JsonPrimitive(label)) },
        )
        val expansion = PolicyExpansion(
            candidates = listOf(
                choice("Pass priority", SemanticOperationFamily.PASS_PRIORITY),
                choice("Play Mountain", SemanticOperationFamily.PLAY_LAND),
            ),
            isExhaustive = true,
            estimatedCandidateCount = 2,
            proposalVersion = "strategic-land-hold-validation-v1",
        )
        val automatic = SearchTeacherAutomaticSelection.classify(expansion)
        val handCardValue = 0.35
        val firstLandNet = MonoRedInformationEvaluator.developedManaValue(1) - handCardValue
        val sixthLandNet = MonoRedInformationEvaluator.developedManaValue(6) -
            MonoRedInformationEvaluator.developedManaValue(5) - handCardValue
        val passSearchable = automatic == null && expansion.candidates.any {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        }
        val diminishing = firstLandNet > 0.0 && sixthLandNet < 0.0
        return StrategicLandHoldValidation(
            candidateFamilies = expansion.candidates.map(SemanticChoice::operationFamily),
            automaticSelectionKind = automatic?.kind,
            firstLandNetValueAfterLeavingHand = firstLandNet,
            sixthLandNetValueAfterLeavingHand = sixthLandNet,
            passRemainsSearchable = passSearchable,
            diminishingResourceValue = diminishing,
            passed = passSearchable && diminishing,
        )
    }

    private fun runCell(id: String, compression: Boolean, reuse: Boolean): TreeReuseFactorialCell {
        var worlds = List(8) { hidden -> ValidationSearchWorld(hidden = "particle-$hidden") }
        val session = SearchTeacherSearchFactory.session(
            config = InformationSetSearchConfig(
                simulations = simulations,
                maxPolicyDecisions = horizon,
                leaf = LeafEvaluationConfig(
                    LeafStateSource.CURRENT_SAMPLED_WORLD,
                    LeafEvaluator.ARGENTUM_BOARD_V1,
                ),
                compressPolicySingletonPasses = compression,
                cacheSimulationTransitions = false,
            ),
            opponentPolicy = UniformOpponentPolicy,
            reuseConfig = InformationSetSearchReuseConfig(
                enabled = reuse,
                minimumFreshSimulations = 16,
                maximumReuseFraction = 0.75,
            ),
        )
        val labels = mutableListOf<String>()
        val latencies = mutableListOf<Double>()
        var fresh = 0
        var reused = 0
        var refreshed = 0
        var steps = 0
        var compressed = 0
        var maximumSnapshots = 0
        val discards = linkedMapOf<String, Int>()
        repeat(decisions) { decision ->
            val started = System.nanoTime()
            val result = session.search(
                rootPlayer = "p0",
                belief = validationBatch(worlds),
                searchSeed = org.mtgallium.agent.infoset.core.ComponentSeeds.derive(seed, decision, "factorial"),
                beliefContinuityEpoch = 0L,
            )
            latencies += (System.nanoTime() - started) / 1_000_000.0
            labels += result.chosen.display.label
            fresh += result.diagnostics.freshSimulations
            reused += result.diagnostics.reusedSimulations
            refreshed += result.diagnostics.refreshedSimulations
            steps += result.diagnostics.searchWorldSteps
            compressed += result.diagnostics.compressedPolicySingletonPasses
            maximumSnapshots = maxOf(maximumSnapshots, result.diagnostics.retainedSnapshotCount)
            result.diagnostics.reuseDiscardReasons.forEach { (reason, count) ->
                discards[reason] = (discards[reason] ?: 0) + count
            }
            worlds.forEach { world ->
                check(world.step(result.chosen).accepted)
                val pass = world.expandChoices().candidates.single()
                check(world.step(pass).accepted)
            }
        }
        return TreeReuseFactorialCell(
            id = id,
            singletonCompression = compression,
            treeReuse = reuse,
            transitionCacheEnabled = false,
            decisions = decisions,
            chosenLabels = labels,
            decisionLatenciesMillis = latencies,
            totalFreshSimulations = fresh,
            totalReusedSimulations = reused,
            totalRefreshedSimulations = refreshed,
            totalSearchWorldSteps = steps,
            totalCompressedSingletonPasses = compressed,
            discardReasons = discards.toSortedMap(),
            maximumRetainedSnapshots = maximumSnapshots,
            regret = labels.count { it != "A" }.toDouble() / labels.size,
        )
    }
}

internal fun renderTreeReuseValidation(report: TreeReuseValidationReport): String = buildString {
    appendLine("# Whether retaining earlier simulations saves work without changing choices in the declared cases")
    appendLine()
    appendLine("## Behavior under test")
    appendLine()
    appendLine(
        "When a later decision has the same recorded public position and a previously simulated hidden-card " +
            "position remains possible, this experiment may retain some earlier simulated paths. It also skips " +
            "search when the configured candidate list contains only one action, while keeping pass and play-land " +
            "as separate choices when both are present."
    )
    appendLine()
    appendLine(
        "For example, a position with a playable land must still let search compare holding that land with playing " +
            "it; the one-candidate shortcut must not choose the land automatically."
    )
    appendLine()
    appendLine(
        "This report checks fixed decision roots containing eight weighted hidden-card positions. Its retention " +
            "rule tests whether a position remains possible, but it does not make the number of retained paths " +
            "proportional to that position's current probability. It therefore does not support production reuse " +
            "after probabilities change; production reuse remains disabled. The code calls the mechanisms tree " +
            "reuse and policy-singleton compression."
    )
    appendLine()
    appendLine("## Results for the declared checks")
    appendLine()
    appendLine(
        "${if (report.passed) "Every condition listed by this fixed-root procedure was satisfied" else "One or more conditions listed by this fixed-root procedure was not satisfied"}. " +
            "The fast-profile singleton avoided ${report.singleton.simulationsAvoided} simulations; " +
            "combined reuse used ${"%.1f".format(report.gates.reuseWorkRatio * 100)}% of the singleton-only step work; " +
            "land-available passing remained a searched branch."
    )
    appendLine()
    appendLine("## Checks for three separate behavior changes")
    appendLine()
    appendLine("| Cause | Evidence | Result |")
    appendLine("| --- | ---: | --- |")
    appendLine(
        "| Profile singleton | ${report.singleton.simulationsAvoided} simulations avoided | " +
            "${if (report.singleton.passed) "condition satisfied" else "condition not satisfied"} |"
    )
    appendLine(
        "| Discarded search state | work ratio ${"%.3f".format(report.gates.reuseWorkRatio)} | " +
            "${if (report.gates.reuseWorkPassed) "condition satisfied" else "condition not satisfied"} |"
    )
    appendLine(
        "| Land-zone value defect | first/sixth land net " +
            "${"%.3f".format(report.strategicLandHold.firstLandNetValueAfterLeavingHand)}/" +
            "${"%.3f".format(report.strategicLandHold.sixthLandNetValueAfterLeavingHand)}; pass searchable | " +
            "${if (report.strategicLandHold.passed) "condition satisfied" else "condition not satisfied"} |"
    )
    appendLine()
    appendLine("## Factorial evidence")
    appendLine()
    appendLine("| Cell | Fresh | Reused | Refreshed | Search steps | Mean latency (ms) | Regret |")
    appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    report.factorial.forEach { cell ->
        appendLine(
            "| ${cell.id} | ${cell.totalFreshSimulations} | ${cell.totalReusedSimulations} | " +
                "${cell.totalRefreshedSimulations} | ${cell.totalSearchWorldSteps} | " +
                "${"%.3f".format(cell.decisionLatenciesMillis.average())} | ${"%.3f".format(cell.regret)} |"
        )
    }
    appendLine()
    appendLine("## Scope, definitions, and method")
    appendLine()
    appendLine(
        "Each factorial cell uses the same ${report.factorial.firstOrNull()?.decisions ?: 0} decision roots, " +
            "eight weighted complete worlds, " +
            "${report.simulationsPerDecision} evidence slots per decision, and a root-relative horizon of " +
        "${report.maxPolicyDecisions}. Reused traces count as evidence slots; at least 16 simulations remain fresh. " +
            "The separate transition cache, which requires its declared simulated-prefix identity to match, is " +
            "disabled in all four cells so its work savings do not enter this comparison."
    )
    appendLine()
    appendLine(
        "Retention requires equality of every field in the declared public-root record and an opaque hidden-position " +
            "key that remains among the currently represented possibilities. This deliberately ignores no field in " +
            "that public-root record, but it does not correct retained path counts to current probability weights. " +
            "Paths cut off at the search horizon are simulated again before their later values are reused."
    )
    appendLine()
    appendLine(
        "Resource development is represented only in the visible leaf value, with diminishing land marginals. " +
            "Neither live selection nor inner search compresses a land drop: pass and play-land remain separate edges. " +
            "This permits bluffing and decks that convert held lands into other resources."
    )
    appendLine()
    appendLine("## Repetition, limitations, and decision")
    appendLine()
    appendLine("- Repeating the same declared inputs produced the same recorded choices: ${report.gates.deterministicReplay}")
    appendLine("- The four 2×2 cells produced the equality relation required by this fixture: ${report.gates.factorialSemanticEquivalence}")
    appendLine("- Land-available pass remains searchable: ${report.strategicLandHold.passRemainsSearchable}")
    appendLine(
        "- Paired-bootstrap latency ratio upper 95%: ${"%.3f".format(report.gates.latencyRatioUpper95)} " +
            "(${report.latencyTrials.size} alternating-order repetitions, " +
            "${report.latencyTrials.sumOf { it.singletonOnlyMillis.size }} paired decisions)"
    )
    appendLine("- The measured retained snapshots stayed below this procedure's configured memory bound: ${report.gates.memoryPassed}")
    report.limitations.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Recommended next step")
    appendLine()
    appendLine(
        "Keep production reuse disabled. If reuse is proposed again, first compare retained contribution with " +
            "current hidden-position probabilities and fresh-only search, then present the observed choice and " +
            "latency differences for a new owner decision. The downside is higher latency while only fresh " +
            "simulations are used."
    )
    appendLine()
    appendLine("## Further questions")
    appendLine()
    appendLine("- Does the full game-root posterior-match rate remain high after private opponent decisions?")
    appendLine("- Does the 48/16 reuse/fresh split remain optimal at larger simulation budgets?")
    appendLine()
    appendLine("Primary theory: Silver & Veness (2010), POMCP; Cowling et al. (2012), ISMCTS.")
}

private fun pairedBootstrapRatioUpper95(
    baseline: List<Double>,
    candidate: List<Double>,
    seed: Long,
    samples: Int = 2_000,
): Double {
    require(baseline.size == candidate.size && baseline.isNotEmpty())
    val random = Random(seed)
    val ratios = DoubleArray(samples) {
        var baselineTotal = 0.0
        var candidateTotal = 0.0
        repeat(baseline.size) {
            val index = random.nextInt(baseline.size)
            baselineTotal += baseline[index]
            candidateTotal += candidate[index]
        }
        candidateTotal / baselineTotal.coerceAtLeast(1e-9)
    }.sortedArray()
    return ratios[(samples * 0.95).toInt().coerceAtMost(samples - 1)]
}

private fun validationBatch(worlds: List<ValidationSearchWorld>): BeliefBatch<Weighted<SearchWorld>> =
    BeliefBatch(
        particles = worlds.map { Weighted(it, 1.0 / worlds.size) },
        diagnostics = BeliefDiagnostics(
            mode = BeliefMode.CONSISTENCY_ONLY_V1,
            requestedParticles = worlds.size,
            acceptedParticles = worlds.size,
            rejectedParticles = 0,
            effectiveSampleSizeBefore = worlds.size.toDouble(),
            effectiveSampleSizeAfter = worlds.size.toDouble(),
            entropy = kotlin.math.ln(worlds.size.toDouble()),
            resamplingCount = 0,
        ),
    )

private class ValidationSearchWorld(
    private val hidden: String,
    private var stage: Int = 0,
    private var score: Int = 0,
    private var path: String = "",
) : ReusableSearchWorld {
    override fun actorToAct(): String? = if (terminalPayoff("p0") != null) null else if (stage % 2 == 0) "p0" else "p1"

    override fun informationState(viewer: String): PolicyInformationState {
        val expansion = expandChoices()
        val actor = actorToAct()
        val observation = PolicyObservation(
            perspectivePlayerId = viewer,
            turnNumber = stage,
            phase = "VALIDATION",
            step = "STAGE_$stage",
            activePlayerId = "p0",
            priorityPlayerId = actor,
            players = listOf(
                PolicyPlayerView("p0", "Root", 20 + score, 0, 0, 0, 0, PolicyManaPool(), true, actor == "p0", false),
                PolicyPlayerView("p1", "Opponent", 20, 0, 0, 0, 0, PolicyManaPool(), false, actor == "p1", false),
            ),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            observationDigest = PolicyJson.sha256("validation:$viewer:$stage:$score:$path"),
        )
        return PolicyInformationState(
            actingPlayerId = actor,
            observation = observation,
            informationStateDigest = PolicyJson.sha256("validation-info:$viewer:$stage:$score:$path"),
            historyCommitment = PolicyHistoryCommitment.empty(),
            history = emptyList(),
            candidates = if (viewer == actor) expansion.candidates else emptyList(),
            terminated = actor == null,
        )
    }

    override fun expandChoices(): PolicyExpansion {
        if (actorToAct() == null) return PolicyExpansion(emptyList(), true, 0, "validation-v1")
        if (actorToAct() == "p1") {
            val pass = choice("Pass", SemanticOperationFamily.PASS_PRIORITY)
            return PolicyExpansion(
                candidates = listOf(pass),
                isExhaustive = false,
                estimatedCandidateCount = null,
                proposalVersion = "validation-fast-profile-v1",
                proposalSeed = stage.toLong(),
                isProfileExhaustive = true,
                omissionReasons = setOf(PolicyExpansionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA),
            )
        }
        val candidates = listOf(choice("A"), choice("B"))
        return PolicyExpansion(candidates, true, 2, "validation-v1", stage.toLong())
    }

    override fun step(choice: SemanticChoice): SearchStepResult {
        val canonical = expandChoices().candidates.singleOrNull { it.signature == choice.signature }
            ?: return SearchStepResult(false, "choice absent")
        if (actorToAct() == "p0") {
            if (canonical.display.label == "A") score++ else score--
            path += canonical.display.label
        } else {
            path += "P"
        }
        stage++
        return SearchStepResult(true)
    }

    override fun fork(): SearchWorld = ValidationSearchWorld(hidden, stage, score, path)

    override fun terminalPayoff(rootPlayer: String): Double? = if (stage >= 40) {
        val payoff = when {
            score > 0 -> 1.0
            score < 0 -> -1.0
            else -> 0.0
        }
        if (rootPlayer == "p0") payoff else -payoff
    } else {
        null
    }

    override fun sampledWorldLeafValue(rootPlayer: String, evaluatorId: String): Double =
        (if (rootPlayer == "p0") tanh(score / 2.0) else -tanh(score / 2.0))

    override fun privateSearchReuseKey(): SearchWorldReuseKey =
        SearchWorldReuseKey.fromTrustedDigest("$hidden:$stage:$score:$path")

    private fun choice(
        label: String,
        family: SemanticOperationFamily = SemanticOperationFamily.OTHER,
    ): SemanticChoice = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION,
        operationFamily = family,
        display = SemanticChoiceDisplay(label),
        canonicalPayload = buildJsonObject { put("choice", JsonPrimitive(label)) },
    )
}
