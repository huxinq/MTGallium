package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.replay.CanonicalReplayTransition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.mtgallium.agent.infoset.argentum.ArgentumKnownDeckBeliefWorldSource
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.InformationSetSearch
import org.mtgallium.agent.infoset.core.InformationSetSearchDiagnostics
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SimulationWorldSchedule
import org.mtgallium.agent.infoset.core.Weighted
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyParameters
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

internal const val ISSUE_0013_FIXED_WORK_PROTOCOL = "issue-0013-fixed-work-v1"
internal const val ISSUE_0013_FIXED_SIMULATIONS = 64
internal const val ISSUE_0013_PRODUCTION_PARTICLES = 8

@Serializable
internal enum class FreshWorldArm { SEQUENTIAL_EIGHT_REUSED, FRESH_COMPLETE_PER_SIMULATION }

@Serializable
internal data class FreshWorldCandidateOutcome(
    val signature: String,
    val label: String,
    val visits: Int,
    val meanValue: Double,
)

@Serializable
internal data class FreshWorldWorkDiagnostics(
    val simulations: Int,
    val nodes: Int,
    val maximumDepth: Int,
    val searchWorldSteps: Int,
    val transitionCacheHits: Int,
    val transitionCacheMisses: Int,
    val rejectedTransitions: Int,
    val evaluatorCalls: Int,
    val quiescenceOverflows: Int,
    val quiescenceFallbacks: Int,
) {
    companion object {
        fun from(value: InformationSetSearchDiagnostics) = FreshWorldWorkDiagnostics(
            simulations = value.simulations,
            nodes = value.nodes,
            maximumDepth = value.maximumDepth,
            searchWorldSteps = value.searchWorldSteps,
            transitionCacheHits = value.transitionCacheHits,
            transitionCacheMisses = value.transitionCacheMisses,
            rejectedTransitions = value.rejectedTransitions,
            evaluatorCalls = value.evaluatorCalls,
            quiescenceOverflows = value.quiescenceOverflows,
            quiescenceFallbacks = value.quiescenceFallbacks,
        )
    }
}

@Serializable
internal data class FreshWorldArmOutcome(
    val arm: FreshWorldArm,
    val chosenSignature: String,
    val chosenLabel: String,
    val rootValue: Double,
    val candidates: List<FreshWorldCandidateOutcome>,
    val basePopulationSize: Int,
    val baseDistinctHiddenAssignments: Int,
    val scheduledDistinctHiddenAssignments: Int,
    val scheduledHiddenAssignmentDigests: List<String>,
    val proposalAttempts: Int,
    val proposalFailures: Map<String, Int>,
    val populationMaterializationMillis: Double,
    val scheduleForkMillis: Double,
    val searchMillis: Double,
    val totalColdMillis: Double,
    val futureChanceScheduleSha256: String,
    val work: FreshWorldWorkDiagnostics,
)

@Serializable
internal data class FreshWorldArmFailure(
    val arm: FreshWorldArm,
    val code: String,
    val message: String,
)

@Serializable
internal data class FreshWorldPairedTrial(
    val rootId: String,
    val panelIndex: Int,
    val regime: FrozenRootRegime,
    val repetition: Int,
    val searchSeed: Long,
    val futureChanceScheduleSha256: String,
    val futureChanceIdentities: Int,
    val pairedFutureChanceMismatches: Int,
    val reused: FreshWorldArmOutcome? = null,
    val fresh: FreshWorldArmOutcome? = null,
    val failures: List<FreshWorldArmFailure> = emptyList(),
) {
    val complete: Boolean get() = reused != null && fresh != null && failures.isEmpty()
}

@Serializable
internal data class FreshWorldArmSummary(
    val distinctSelectedActions: Int,
    val modalSignature: String?,
    val modalFraction: Double?,
    val selectionEntropyBits: Double?,
    val rootValueMean: Double?,
    val rootValueStandardDeviation: Double?,
    val meanScheduledDistinctHiddenAssignments: Double?,
    val candidateVariability: List<FreshWorldCandidateVariability>,
)

@Serializable
internal data class FreshWorldCandidateVariability(
    val signature: String,
    val label: String,
    val selectedRepetitions: Int,
    val visitsMean: Double,
    val visitsStandardDeviation: Double,
    val meanValueMean: Double,
    val meanValueStandardDeviation: Double,
)

@Serializable
internal data class FreshWorldRootSummary(
    val rootId: String,
    val panelIndex: Int,
    val regime: FrozenRootRegime,
    val completePairs: Int,
    val actionMismatches: Int,
    val pairedActionMismatchRate: Double?,
    val empiricalActionTotalVariation: Double?,
    val modalActionChanged: Boolean,
    val materialByDeclaredScreen: Boolean,
    val meanPairedFreshMinusReusedRootValue: Double?,
    val reused: FreshWorldArmSummary,
    val fresh: FreshWorldArmSummary,
)

@Serializable
internal data class FreshWorldCostSummary(
    val arm: FreshWorldArm,
    val populationMaterializationMedianMillis: Double?,
    val populationMaterializationP95Millis: Double?,
    val searchMedianMillis: Double?,
    val searchP95Millis: Double?,
    val totalColdMedianMillis: Double?,
    val totalColdP95Millis: Double?,
)

@Serializable
internal data class FreshWorldFixedWorkReport(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0013_FIXED_WORK_PROTOCOL,
    val panelDigest: String,
    val outerCommit: String,
    val argentumCommit: String,
    val deckHash: String,
    val rootLimit: Int,
    val repetitions: Int,
    val workerThreads: Int,
    val simulationsPerArm: Int = ISSUE_0013_FIXED_SIMULATIONS,
    val productionParticleCount: Int = ISSUE_0013_PRODUCTION_PARTICLES,
    val leaf: LeafEvaluationConfig = LeafEvaluationConfig(
        LeafStateSource.BOUNDED_ROLLOUT,
        LeafEvaluator.MTGALLIUM_VISIBLE_V2,
    ),
    val actionSpaceProfile: SearchActionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
    val futureChanceControl: String = "same independently derived future-chance identity for each root/repetition/simulation index in both arms",
    val primaryMaterialityScreen: String = "root modal action differs and empirical action total variation is at least 0.25",
    val trials: List<FreshWorldPairedTrial>,
    val rootSummaries: List<FreshWorldRootSummary>,
    val scheduledPairs: Int,
    val completePairs: Int,
    val failedPairs: Int,
    val typedFailures: Map<String, Int>,
    val pairedActionMismatches: Int,
    val pairedActionMismatchRate: Double?,
    val rootsWithModalActionChange: Int,
    val rootsMeetingMaterialityScreen: Int,
    val meanRootActionTotalVariation: Double?,
    val pairedFutureChanceMismatches: Int,
    val reusedMeanScheduledDistinctHiddenAssignments: Double?,
    val freshMeanScheduledDistinctHiddenAssignments: Double?,
    val costs: List<FreshWorldCostSummary>,
    val completed: Boolean,
    val limitations: List<String>,
)

@Serializable
internal data class FreshWorldFixedWorkCheckpoint(
    val schemaVersion: Int = 1,
    val protocol: String = ISSUE_0013_FIXED_WORK_PROTOCOL,
    val panelDigest: String,
    val outerCommit: String,
    val argentumCommit: String,
    val deckHash: String,
    val rootLimit: Int,
    val repetitions: Int,
    val workerThreads: Int,
    val trials: List<FreshWorldPairedTrial>,
)

internal class FreshWorldFixedWorkExperiment(
    private val root: Path,
    private val registry: com.wingedsheep.engine.registry.CardRegistry,
    private val manifest: DeckManifest,
    private val panel: FreshWorldFrozenRootPanel,
    private val outerCommit: String,
    private val argentumCommit: String,
) {
    private val knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck)
    private val leaf = LeafEvaluationConfig(
        LeafStateSource.BOUNDED_ROLLOUT,
        LeafEvaluator.MTGALLIUM_VISIBLE_V2,
    )

    init {
        require(panel.currentArgentumCommit == argentumCommit) {
            "Frozen panel uses ${panel.currentArgentumCommit}, not checked-out Argentum $argentumCommit"
        }
        require(panel.deckHash == manifest.deckHash())
        require(panel.actionSpaceProfile == SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1)
    }

    fun run(
        rootLimit: Int,
        repetitions: Int,
        workerThreads: Int,
        existing: FreshWorldFixedWorkCheckpoint? = null,
        checkpoint: (FreshWorldFixedWorkCheckpoint) -> Unit = {},
        progress: (String) -> Unit = {},
    ): FreshWorldFixedWorkReport {
        require(rootLimit in 1..panel.roots.size && repetitions > 0 && workerThreads > 0)
        existing?.requireCompatible(rootLimit, repetitions, workerThreads)
        val trials = existing?.trials.orEmpty().toMutableList()
        val completedKeys = trials.mapTo(mutableSetOf()) { it.rootId to it.repetition }
        val remaining = panel.roots.take(rootLimit).flatMap { frozen ->
            (0 until repetitions).mapNotNull { repetition ->
                TrialSpecification(frozen, repetition).takeIf {
                    completedKeys.add(frozen.id to repetition)
                }
            }
        }
        remaining.chunked(workerThreads).forEach { batch ->
            val completed = parallelMapOrdered(batch.size, minOf(workerThreads, batch.size)) { index ->
                batch[index].let { runTrial(it.root, it.repetition) }
            }
            trials += completed
            checkpoint(checkpoint(rootLimit, repetitions, workerThreads, trials))
            completed.forEach { trial ->
                val frozen = panel.roots[trial.panelIndex]
                progress(
                    "root ${frozen.panelIndex + 1}/$rootLimit repetition ${trial.repetition + 1}/$repetitions " +
                        if (trial.complete) {
                            "complete mismatch=${trial.reused!!.chosenSignature != trial.fresh!!.chosenSignature}"
                        } else {
                            "failed=${trial.failures.joinToString { it.code }}"
                        }
                )
            }
        }
        return summarize(rootLimit, repetitions, workerThreads, trials.sortedWith(
            compareBy<FreshWorldPairedTrial> { it.panelIndex }.thenBy { it.repetition }
        ))
    }

    private fun runTrial(frozen: FreshWorldFrozenRoot, repetition: Int): FreshWorldPairedTrial {
        val prefix = semanticPrefix(frozen)
        val currentRoot = reconstruct(frozen, prefix)
        val actor = requireNotNull(currentRoot.actorToAct())
        require(actor == frozen.perspectivePlayerId)
        require(currentRoot.informationState(actor).informationStateDigest == frozen.informationStateDigest)
        val searchSeed = ComponentSeeds.derive(ISSUE_0013_FIXED_WORK_PROTOCOL, frozen.id, repetition, "search")
        val futureChance = List(ISSUE_0013_FIXED_SIMULATIONS) { simulationIndex ->
            ComponentSeeds.derive(
                ISSUE_0013_FIXED_WORK_PROTOCOL,
                frozen.id,
                repetition,
                simulationIndex,
                "paired-future-chance",
            )
        }
        val futureDigest = sha256(futureChance.joinToString(":"))
        var reusedPreparation: PreparedArm? = null
        var freshPreparation: PreparedArm? = null
        val failures = mutableListOf<FreshWorldArmFailure>()
        runCatching {
            prepareReused(frozen, prefix, repetition, searchSeed, futureChance)
        }.onSuccess { reusedPreparation = it }.onFailure {
            failures += failure(FreshWorldArm.SEQUENTIAL_EIGHT_REUSED, it)
        }
        runCatching {
            prepareFresh(currentRoot, actor, frozen, repetition, futureChance)
        }.onSuccess { freshPreparation = it }.onFailure {
            failures += failure(FreshWorldArm.FRESH_COMPLETE_PER_SIMULATION, it)
        }
        var reused: FreshWorldArmOutcome? = null
        var fresh: FreshWorldArmOutcome? = null
        val order = if (repetition % 2 == 0) {
            listOf(reusedPreparation, freshPreparation)
        } else {
            listOf(freshPreparation, reusedPreparation)
        }
        order.filterNotNull().forEach { prepared ->
            runCatching { executeSearch(prepared, actor, searchSeed, futureDigest) }
                .onSuccess { outcome ->
                    if (outcome.arm == FreshWorldArm.SEQUENTIAL_EIGHT_REUSED) reused = outcome else fresh = outcome
                }.onFailure { failures += failure(prepared.arm, it) }
        }
        return FreshWorldPairedTrial(
            rootId = frozen.id,
            panelIndex = frozen.panelIndex,
            regime = frozen.regime,
            repetition = repetition,
            searchSeed = searchSeed,
            futureChanceScheduleSha256 = futureDigest,
            futureChanceIdentities = futureChance.distinct().size,
            pairedFutureChanceMismatches = 0,
            reused = reused,
            fresh = fresh,
            failures = failures,
        )
    }

    private fun prepareReused(
        frozen: FreshWorldFrozenRoot,
        prefix: List<SemanticChoice>,
        repetition: Int,
        searchSeed: Long,
        futureChance: List<Long>,
    ): PreparedArm {
        val started = System.nanoTime()
        val actual = reconstruct(frozen, emptyList())
        val parameters = parameters(frozen.searchBaseSeed)
        val session = SearchTeacherPolicySession(
            root = actual,
            viewer = frozen.perspectivePlayerId,
            registry = registry,
            knownDecks = knownDecks,
            parameters = parameters,
            opponentPolicy = defaultMonoRedOpponentPolicy(),
            gameId = "${frozen.gameId}:$ISSUE_0013_FIXED_WORK_PROTOCOL:$repetition",
        )
        prefix.forEachIndexed { decisionIndex, historical ->
            val actor = requireNotNull(actual.actorToAct())
            val exact = actual.expandChoices().candidates.singleOrNull { it.signature == historical.signature }
                ?: error("Frozen prefix choice $decisionIndex is not currently legal")
            val step = actual.step(exact)
            require(step.accepted) { "Frozen prefix choice $decisionIndex was rejected" }
            session.observeAccepted(actual, actor, exact, decisionIndex, step.privateToActor)
        }
        require(actual.informationState(frozen.perspectivePlayerId).informationStateDigest == frozen.informationStateDigest)
        val belief = session.beliefBatch(actual)
        require(belief.particles.size == ISSUE_0013_PRODUCTION_PARTICLES)
        val materializationMillis = elapsedMillis(started)
        val indices = InformationSetSearch.productionRootParticleIndices(
            belief.particles.map { it.weight }, searchSeed, ISSUE_0013_FIXED_SIMULATIONS,
        )
        val forkStarted = System.nanoTime()
        val scheduled = indices.mapIndexed { simulationIndex, particleIndex ->
            (belief.particles[particleIndex].value as ArgentumSearchWorld)
                .forkForHypotheticalSearch(futureChance[simulationIndex])
        }
        return PreparedArm(
            arm = FreshWorldArm.SEQUENTIAL_EIGHT_REUSED,
            belief = belief,
            schedule = scheduled,
            baseDistinct = distinctHidden(belief.particles.map { it.value }),
            diagnostics = belief.diagnostics,
            populationMaterializationMillis = materializationMillis,
            scheduleForkMillis = elapsedMillis(forkStarted),
        )
    }

    private fun prepareFresh(
        currentRoot: ArgentumSearchWorld,
        actor: String,
        frozen: FreshWorldFrozenRoot,
        repetition: Int,
        futureChance: List<Long>,
    ): PreparedArm {
        val started = System.nanoTime()
        val fresh = ArgentumKnownDeckBeliefWorldSource(
            root = currentRoot,
            cardRegistry = registry,
            proposalContext = "$ISSUE_0013_FIXED_WORK_PROTOCOL:${frozen.id}:$repetition:fresh",
        ).sample(
            rootInformation = currentRoot.informationState(actor),
            knownDecks = knownDecks,
            beliefSeed = ComponentSeeds.derive(
                ISSUE_0013_FIXED_WORK_PROTOCOL, frozen.id, repetition, "fresh-complete-worlds"
            ),
            count = ISSUE_0013_FIXED_SIMULATIONS,
        )
        val materializationMillis = elapsedMillis(started)
        val forkStarted = System.nanoTime()
        val scheduled = fresh.particles.mapIndexed { simulationIndex, particle ->
            (particle.value as ArgentumSearchWorld).forkForHypotheticalSearch(futureChance[simulationIndex])
        }
        return PreparedArm(
            arm = FreshWorldArm.FRESH_COMPLETE_PER_SIMULATION,
            // Holding the information-equivalent representative batch constant is unnecessary:
            // scheduled worlds are the only simulation roots, and reuse is disabled.
            belief = fresh,
            schedule = scheduled,
            baseDistinct = distinctHidden(fresh.particles.map { it.value }),
            diagnostics = fresh.diagnostics,
            populationMaterializationMillis = materializationMillis,
            scheduleForkMillis = elapsedMillis(forkStarted),
        )
    }

    private fun executeSearch(
        prepared: PreparedArm,
        actor: String,
        searchSeed: Long,
        futureDigest: String,
    ): FreshWorldArmOutcome {
        val started = System.nanoTime()
        val result = SearchTeacherSearchFactory.create(
            config = parameters(0L).searchConfig(),
            opponentPolicy = defaultMonoRedOpponentPolicy(),
        ).search(
            rootPlayer = actor,
            belief = prepared.belief,
            searchSeed = searchSeed,
            simulationWorldSchedule = SimulationWorldSchedule(prepared.schedule),
        )
        val searchMillis = elapsedMillis(started)
        val hidden = prepared.schedule.map(::hiddenAssignmentDigest)
        return FreshWorldArmOutcome(
            arm = prepared.arm,
            chosenSignature = result.chosen.signature,
            chosenLabel = result.chosen.display.label,
            rootValue = result.rootValue,
            candidates = result.candidates.map { candidate ->
                FreshWorldCandidateOutcome(
                    candidate.choice.signature,
                    candidate.choice.display.label,
                    candidate.visits,
                    candidate.meanValue,
                )
            },
            basePopulationSize = prepared.belief.particles.size,
            baseDistinctHiddenAssignments = prepared.baseDistinct,
            scheduledDistinctHiddenAssignments = hidden.distinct().size,
            scheduledHiddenAssignmentDigests = hidden,
            proposalAttempts = prepared.diagnostics.proposalAttempts,
            proposalFailures = prepared.diagnostics.failures,
            populationMaterializationMillis = prepared.populationMaterializationMillis,
            scheduleForkMillis = prepared.scheduleForkMillis,
            searchMillis = searchMillis,
            totalColdMillis = prepared.populationMaterializationMillis + prepared.scheduleForkMillis + searchMillis,
            futureChanceScheduleSha256 = futureDigest,
            work = FreshWorldWorkDiagnostics.from(result.diagnostics),
        )
    }

    private fun reconstruct(frozen: FreshWorldFrozenRoot, prefix: List<SemanticChoice>): ArgentumSearchWorld =
        reconstructReplayReviewWorld(
            registry = registry,
            manifest = manifest,
            gameId = frozen.gameId,
            gameSeed = frozen.gameSeed,
            searchBaseSeed = frozen.searchBaseSeed,
            startingPlayerIndex = 0,
            profile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            semanticPrefix = prefix,
        )

    private fun semanticPrefix(frozen: FreshWorldFrozenRoot): List<SemanticChoice> {
        val path = root.resolve(frozen.replayPath)
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path))
        require(sha256File(path) == frozen.replaySha256)
        val choices = readCanonicalReplay(path).filterIsInstance<CanonicalReplayTransition>().mapNotNull { transition ->
            val decision = (transition.extensions["mtgallium.decisionIndex"] as? JsonPrimitive)
                ?.content?.toInt() ?: return@mapNotNull null
            val encoded = transition.extensions["mtgallium.semanticChoice"] ?: return@mapNotNull null
            decision to PolicyJson.format.decodeFromJsonElement(SemanticChoice.serializer(), encoded)
        }
        require(choices.map { it.first } == choices.indices.toList())
        val prefix = choices.take(frozen.decisionIndex).map { it.second }
        require(
            PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }) == frozen.semanticPrefixDigest
        )
        return prefix
    }

    private fun parameters(baseSeed: Long) = SearchTeacherPolicyParameters(
        particles = ISSUE_0013_PRODUCTION_PARTICLES,
        simulations = ISSUE_0013_FIXED_SIMULATIONS,
        maxPolicyDecisions = 32,
        explorationConstant = 1.4,
        leaf = leaf,
        actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
        beliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
        beliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
        baseSeed = baseSeed,
        profileId = BaselineFactorialRoster.ROLLOUT_V2_ID,
    )

    private fun checkpoint(
        rootLimit: Int,
        repetitions: Int,
        workerThreads: Int,
        trials: List<FreshWorldPairedTrial>,
    ) = FreshWorldFixedWorkCheckpoint(
        panelDigest = panel.panelDigest,
        outerCommit = outerCommit,
        argentumCommit = argentumCommit,
        deckHash = manifest.deckHash(),
        rootLimit = rootLimit,
        repetitions = repetitions,
        workerThreads = workerThreads,
        trials = trials.toList(),
    )

    private fun FreshWorldFixedWorkCheckpoint.requireCompatible(
        rootLimit: Int,
        repetitions: Int,
        workerThreads: Int,
    ) {
        require(protocol == ISSUE_0013_FIXED_WORK_PROTOCOL)
        require(panelDigest == panel.panelDigest)
        require(outerCommit == this@FreshWorldFixedWorkExperiment.outerCommit)
        require(argentumCommit == this@FreshWorldFixedWorkExperiment.argentumCommit)
        require(deckHash == manifest.deckHash())
        require(this.rootLimit == rootLimit && this.repetitions == repetitions)
        require(this.workerThreads == workerThreads)
    }

    private fun summarize(
        rootLimit: Int,
        repetitions: Int,
        workerThreads: Int,
        trials: List<FreshWorldPairedTrial>,
    ): FreshWorldFixedWorkReport {
        val roots = panel.roots.take(rootLimit).map { frozen ->
            summarizeRoot(frozen, trials.filter { it.rootId == frozen.id && it.complete })
        }
        val complete = trials.filter(FreshWorldPairedTrial::complete)
        val outcomes = complete.flatMap { listOf(it.reused!!, it.fresh!!) }
        val mismatches = complete.count { it.reused!!.chosenSignature != it.fresh!!.chosenSignature }
        return FreshWorldFixedWorkReport(
            panelDigest = panel.panelDigest,
            outerCommit = outerCommit,
            argentumCommit = argentumCommit,
            deckHash = manifest.deckHash(),
            rootLimit = rootLimit,
            repetitions = repetitions,
            workerThreads = workerThreads,
            trials = trials,
            rootSummaries = roots,
            scheduledPairs = rootLimit * repetitions,
            completePairs = complete.size,
            failedPairs = trials.count { !it.complete },
            typedFailures = trials.flatMap { it.failures }.groupingBy { "${it.arm}:${it.code}" }.eachCount().toSortedMap(),
            pairedActionMismatches = mismatches,
            pairedActionMismatchRate = ratio(mismatches, complete.size),
            rootsWithModalActionChange = roots.count(FreshWorldRootSummary::modalActionChanged),
            rootsMeetingMaterialityScreen = roots.count(FreshWorldRootSummary::materialByDeclaredScreen),
            meanRootActionTotalVariation = roots.mapNotNull { it.empiricalActionTotalVariation }.averageOrNull(),
            pairedFutureChanceMismatches = trials.sumOf { it.pairedFutureChanceMismatches },
            reusedMeanScheduledDistinctHiddenAssignments = outcomes
                .filter { it.arm == FreshWorldArm.SEQUENTIAL_EIGHT_REUSED }
                .map { it.scheduledDistinctHiddenAssignments.toDouble() }.averageOrNull(),
            freshMeanScheduledDistinctHiddenAssignments = outcomes
                .filter { it.arm == FreshWorldArm.FRESH_COMPLETE_PER_SIMULATION }
                .map { it.scheduledDistinctHiddenAssignments.toDouble() }.averageOrNull(),
            costs = FreshWorldArm.entries.map { arm -> costSummary(arm, outcomes.filter { it.arm == arm }) },
            completed = trials.size == rootLimit * repetitions,
            limitations = listOf(
                "Action stability is not strategic correctness.",
                "Cold reconstruction cost for the sequential arm includes replaying belief history and is not live amortized decision latency; all timings were observed under $workerThreads-worker experiment contention.",
                "The primary panel was selected without human labels; any reviewed-root overlap is secondary evidence only.",
                "This fixed-work report does not perform matched-time games, paired games, factored belief, or production changes.",
            ),
        )
    }

    private fun summarizeRoot(
        frozen: FreshWorldFrozenRoot,
        complete: List<FreshWorldPairedTrial>,
    ): FreshWorldRootSummary {
        val reused = complete.map { it.reused!! }
        val fresh = complete.map { it.fresh!! }
        val reusedDistribution = actionDistribution(reused)
        val freshDistribution = actionDistribution(fresh)
        val all = reusedDistribution.keys + freshDistribution.keys
        val tv = if (complete.isEmpty()) null else {
            0.5 * all.sumOf { signature ->
                kotlin.math.abs(reusedDistribution.getOrDefault(signature, 0.0) - freshDistribution.getOrDefault(signature, 0.0))
            }
        }
        val reusedSummary = armSummary(reused)
        val freshSummary = armSummary(fresh)
        val modalChanged = reusedSummary.modalSignature != null && freshSummary.modalSignature != null &&
            reusedSummary.modalSignature != freshSummary.modalSignature
        val mismatch = complete.count { it.reused!!.chosenSignature != it.fresh!!.chosenSignature }
        return FreshWorldRootSummary(
            rootId = frozen.id,
            panelIndex = frozen.panelIndex,
            regime = frozen.regime,
            completePairs = complete.size,
            actionMismatches = mismatch,
            pairedActionMismatchRate = ratio(mismatch, complete.size),
            empiricalActionTotalVariation = tv,
            modalActionChanged = modalChanged,
            materialByDeclaredScreen = modalChanged && (tv ?: 0.0) >= 0.25,
            meanPairedFreshMinusReusedRootValue = complete
                .map { it.fresh!!.rootValue - it.reused!!.rootValue }.averageOrNull(),
            reused = reusedSummary,
            fresh = freshSummary,
        )
    }

    private fun armSummary(outcomes: List<FreshWorldArmOutcome>): FreshWorldArmSummary {
        val counts = outcomes.groupingBy { it.chosenSignature }.eachCount()
        val modal = counts.entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }
        )
        val values = outcomes.map { it.rootValue }
        return FreshWorldArmSummary(
            distinctSelectedActions = counts.size,
            modalSignature = modal?.key,
            modalFraction = modal?.value?.toDouble()?.div(outcomes.size),
            selectionEntropyBits = if (outcomes.isEmpty()) null else counts.values.sumOf { count ->
                val p = count.toDouble() / outcomes.size
                -p * ln(p) / ln(2.0)
            },
            rootValueMean = values.averageOrNull(),
            rootValueStandardDeviation = values.sampleStandardDeviation(),
            meanScheduledDistinctHiddenAssignments = outcomes
                .map { it.scheduledDistinctHiddenAssignments.toDouble() }.averageOrNull(),
            candidateVariability = candidateVariability(outcomes),
        )
    }

    private fun candidateVariability(
        outcomes: List<FreshWorldArmOutcome>,
    ): List<FreshWorldCandidateVariability> {
        val candidates = outcomes.flatMap { it.candidates }.associateBy { it.signature }
        return candidates.values.sortedBy { it.signature }.map { candidate ->
            val perRepetition = outcomes.map { outcome ->
                outcome.candidates.singleOrNull { it.signature == candidate.signature }
                    ?: FreshWorldCandidateOutcome(candidate.signature, candidate.label, 0, 0.0)
            }
            val visits = perRepetition.map { it.visits.toDouble() }
            val values = perRepetition.map { it.meanValue }
            FreshWorldCandidateVariability(
                signature = candidate.signature,
                label = candidate.label,
                selectedRepetitions = outcomes.count { it.chosenSignature == candidate.signature },
                visitsMean = visits.average(),
                visitsStandardDeviation = visits.sampleStandardDeviation() ?: 0.0,
                meanValueMean = values.average(),
                meanValueStandardDeviation = values.sampleStandardDeviation() ?: 0.0,
            )
        }
    }

    private fun actionDistribution(outcomes: List<FreshWorldArmOutcome>): Map<String, Double> =
        if (outcomes.isEmpty()) emptyMap() else outcomes.groupingBy { it.chosenSignature }.eachCount()
            .mapValues { it.value.toDouble() / outcomes.size }

    private fun costSummary(
        arm: FreshWorldArm,
        outcomes: List<FreshWorldArmOutcome>,
    ) = FreshWorldCostSummary(
        arm = arm,
        populationMaterializationMedianMillis = outcomes.map { it.populationMaterializationMillis }.medianOrNull(),
        populationMaterializationP95Millis = outcomes.map { it.populationMaterializationMillis }.p95OrNull(),
        searchMedianMillis = outcomes.map { it.searchMillis }.medianOrNull(),
        searchP95Millis = outcomes.map { it.searchMillis }.p95OrNull(),
        totalColdMedianMillis = outcomes.map { it.totalColdMillis }.medianOrNull(),
        totalColdP95Millis = outcomes.map { it.totalColdMillis }.p95OrNull(),
    )

    private data class PreparedArm(
        val arm: FreshWorldArm,
        val belief: BeliefBatch<Weighted<SearchWorld>>,
        val schedule: List<ArgentumSearchWorld>,
        val baseDistinct: Int,
        val diagnostics: BeliefDiagnostics,
        val populationMaterializationMillis: Double,
        val scheduleForkMillis: Double,
    )

    private data class TrialSpecification(
        val root: FreshWorldFrozenRoot,
        val repetition: Int,
    )
}

private fun failure(arm: FreshWorldArm, value: Throwable) = FreshWorldArmFailure(
    arm = arm,
    code = value::class.simpleName ?: "Throwable",
    message = value.message?.take(500) ?: "no message",
)

private fun hiddenAssignmentDigest(world: SearchWorld): String {
    val snapshot = (world as ArgentumSearchWorld).privilegedDebugSnapshot()
    return sha256(buildString {
        snapshot.hiddenHands.toSortedMap().forEach { (player, cards) ->
            append("hand:").append(player).append(':').append(cards.joinToString("\u001f")).append('\n')
        }
        snapshot.libraries.toSortedMap().forEach { (player, cards) ->
            append("library:").append(player).append(':').append(cards.joinToString("\u001f")).append('\n')
        }
    })
}

private fun distinctHidden(worlds: List<SearchWorld>): Int = worlds.map(::hiddenAssignmentDigest).distinct().size

private fun elapsedMillis(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0

private fun ratio(numerator: Int, denominator: Int): Double? =
    if (denominator == 0) null else numerator.toDouble() / denominator

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun List<Double>.sampleStandardDeviation(): Double? = when (size) {
    0 -> null
    1 -> 0.0
    else -> {
        val mean = average()
        sqrt(sumOf { (it - mean) * (it - mean) } / (size - 1))
    }
}

private fun List<Double>.medianOrNull(): Double? =
    if (isEmpty()) null else sorted().let { values ->
        val middle = values.size / 2
        if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
    }

private fun List<Double>.p95OrNull(): Double? = if (isEmpty()) null else percentile(this, 0.95)

internal fun renderFreshWorldFixedWorkReport(report: FreshWorldFixedWorkReport): String = buildString {
    appendLine("# Issue 0013 Stage B — fixed-work fresh hidden worlds")
    appendLine()
    appendLine(
        "Completed ${report.completePairs}/${report.scheduledPairs} paired 64-simulation repetitions " +
            "on ${report.rootLimit} frozen roots with ${report.workerThreads} worker(s). Future-chance pairing mismatches: " +
            "${report.pairedFutureChanceMismatches}."
    )
    appendLine()
    appendLine("- Paired selected-action mismatches: ${report.pairedActionMismatches}/${report.completePairs} (${report.pairedActionMismatchRate?.format3() ?: "n/a"})")
    appendLine("- Roots with different modal actions: ${report.rootsWithModalActionChange}/${report.rootLimit}")
    appendLine("- Roots meeting the declared modal-change + TV>=0.25 screen: ${report.rootsMeetingMaterialityScreen}/${report.rootLimit}")
    appendLine("- Mean root empirical action TV distance: ${report.meanRootActionTotalVariation?.format3() ?: "n/a"}")
    appendLine("- Mean distinct scheduled hidden assignments, reused: ${report.reusedMeanScheduledDistinctHiddenAssignments?.format2() ?: "n/a"}/64")
    appendLine("- Mean distinct scheduled hidden assignments, fresh: ${report.freshMeanScheduledDistinctHiddenAssignments?.format2() ?: "n/a"}/64")
    appendLine("- Typed failed pairs: ${report.failedPairs}; ${report.typedFailures}")
    appendLine()
    appendLine("| Root | Regime | Mismatch | TV | Modal changed | Screen |")
    appendLine("| --- | --- | ---: | ---: | --- | --- |")
    report.rootSummaries.forEach { root ->
        appendLine(
            "| `${root.rootId}` | `${root.regime}` | ${root.actionMismatches}/${root.completePairs} | " +
                "${root.empiricalActionTotalVariation?.format3() ?: "n/a"} | ${root.modalActionChanged} | " +
                "${root.materialByDeclaredScreen} |"
        )
    }
    appendLine()
    appendLine("Costs are cold diagnostic costs, not matched-time evidence:")
    report.costs.forEach { cost ->
        appendLine(
            "- `${cost.arm}`: materialization median/p95 " +
                "${cost.populationMaterializationMedianMillis?.format2()}/${cost.populationMaterializationP95Millis?.format2()} ms; " +
                "search ${cost.searchMedianMillis?.format2()}/${cost.searchP95Millis?.format2()} ms; " +
                "cold total ${cost.totalColdMedianMillis?.format2()}/${cost.totalColdP95Millis?.format2()} ms."
        )
    }
    appendLine()
    report.limitations.forEach { appendLine("- $it") }
}

private fun Double.format2(): String = "%.2f".format(java.util.Locale.ROOT, this)
private fun Double.format3(): String = "%.3f".format(java.util.Locale.ROOT, this)
