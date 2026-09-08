package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.research.run.*

internal const val FACTUAL_RESIDUAL_ROOT_ORDER = "factual-v2-residual-screen-root-v1"
internal const val FACTUAL_RESIDUAL_FRAME_RULE = "at-most32-equispaced-floor-including-endpoints-v1"
internal const val FACTUAL_RESIDUAL_WEIGHTING = "equal-seed-group-then-leg-then-selected-frame-v1"

/** A manifest binding is explicit even when a protocol identity alone does not bind result bytes. */
@Serializable
internal data class FactualResidualInput(val directory: String, val identity: String, val manifestSha256: String) {
    init {
        require(Path.of(directory).isAbsolute)
        require(identity.matches(Regex("[a-z][a-z0-9-]*-sha256:[0-9a-f]{64}")))
        require(manifestSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

internal fun verifyFactualResidualInput(input: FactualResidualInput, required: Set<String> = setOf("report.json")) {
    val path = Path.of(input.directory)
    require(researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == input.manifestSha256)
    val manifest = ResearchRunArtifacts.loadAndVerify(path, input.identity)
    require(manifest.artifacts.map { it.relativePath }.toSet().containsAll(required))
}

internal fun factualResidualReference(path: Path, identity: String): FactualResidualInput =
    FactualResidualInput(path.toAbsolutePath().normalize().toString(), identity,
        researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)))

/** Stage A only: one fit and fresh decisions. Eligibility never means the terminal or gameplay gate passed. */
@Serializable
internal data class FactualResidualStudyPlan(
    val schemaVersion: Int = 1,
    val inventory: FactualResidualInput,
    val incumbent: SearchTeacherCalibrationPolicy,
    val build: ResearchBuildReference,
    val workers: Int = 8,
    val maximumSeconds: Int = 1800,
    val trainingGroups: Int = 16,
    val screeningGroups: Int = 16,
    val maximumFittingFramesPerLeg: Int = 32,
    val searchRepetitions: Int = 2,
    val minimumChangedGroups: Int = 8,
    val maximumSelectionCostRatio: Double = 1.10,
    val searchSeedDomain: String = "factual-residual-fresh-search-v1",
) {
    init {
        require(schemaVersion == 1 && workers in 1..8 && maximumSeconds == 1800)
        require(trainingGroups == 16 && screeningGroups == 16 && maximumFittingFramesPerLeg == 32)
        require(searchRepetitions == 2 && minimumChangedGroups == 8 && maximumSelectionCostRatio == 1.10)
        require(searchSeedDomain == "factual-residual-fresh-search-v1")
        val casting = requireNotNull(incumbent.fastRootKernelRolloutFit)
        require(incumbent.fastOpponentKernelRolloutFit == casting)
        require(incumbent == SearchTeacherCalibrationPolicy(
            id = incumbent.id, particles = 8, simulations = 56, maxPolicyDecisions = 16,
            explorationConstant = 1.4, singletonSelection = true, rolloutHeuristicProbability = 1.0,
            evaluator = MonoRedVisibleEvaluatorConfig(), fastRootKernelRolloutFit = casting,
            fastOpponentKernelRolloutFit = casting,
        )) { "The factual residual comparator must be the declared unchanged fast16/V2/56sim/eight-particle incumbent" }
    }
}

@Serializable
internal enum class FactualResidualDataRole { TRAIN, SCREEN }

/** Selection sees only authenticated metadata, never outcomes, action values or learned scores. */
@Serializable
internal data class FactualResidualRootMetadata(
    val assignment: RealGamePositionBankAssignment,
    val candidates: List<SemanticChoice>,
) {
    init {
        require(candidates.size >= 2 && candidates.map { it.signature }.distinct().size == candidates.size)
    }
}

@Serializable
internal data class FactualResidualGameAllocation(
    val sourceRunIdentity: String,
    val gameId: String,
    val seedGroupId: String,
    val originalPartition: RealGamePositionPartition,
    val role: FactualResidualDataRole,
    val leg: Int,
    val viewer: String,
    val semanticDecisions: Int,
    val fittingFrames: List<Int>,
    val root: FactualResidualRootMetadata?,
) {
    init {
        require(leg in 0..1 && viewer in setOf("p0", "p1") && semanticDecisions > 0)
        require(fittingFrames == if (role == FactualResidualDataRole.TRAIN) factualResidualFrameIndices(semanticDecisions) else emptyList())
        require((role == FactualResidualDataRole.SCREEN) == (root != null))
        root?.assignment?.let {
            require(it.sourceRunIdentity == sourceRunIdentity && it.sourceGameId == gameId && it.seedGroupId == seedGroupId)
            require(it.actor == viewer && it.partition == originalPartition && it.decisionIndex in 0 until semanticDecisions)
        }
    }
}

@Serializable
internal data class FactualResidualAllocation(
    val bindings: ResearchRunBindings,
    val studyIdentity: String,
    val inventory: FactualResidualInput,
    val incumbent: SearchTeacherCalibrationPolicy,
    val games: List<FactualResidualGameAllocation>,
    val frameRule: String = FACTUAL_RESIDUAL_FRAME_RULE,
    val weighting: String = FACTUAL_RESIDUAL_WEIGHTING,
    val rootOrder: String = FACTUAL_RESIDUAL_ROOT_ORDER,
    val interpretation: String = "All groups are campaign-observed. SCREEN is held out from this coefficient fit only, not pristine confirmation. Original inventory partitions are retained separately.",
) {
    init {
        require(frameRule == FACTUAL_RESIDUAL_FRAME_RULE && weighting == FACTUAL_RESIDUAL_WEIGHTING && rootOrder == FACTUAL_RESIDUAL_ROOT_ORDER)
        requireFactualResidualAllocation(games)
        require(bindings == factualResidualAllocationBindings(studyIdentity, inventory, incumbent, games))
    }
}

internal fun factualResidualFrameIndices(frames: Int): List<Int> {
    require(frames > 0)
    val count = minOf(frames, 32)
    return if (count == 1) listOf(0) else List(count) { (it.toLong() * (frames - 1) / (count - 1)).toInt() }
}

internal fun factualResidualTrainingGroups(games: List<RealGamePositionBankGame>): Set<String> {
    require(games.size == 64 && games.all { it.validPair && it.exclusionReasons.isEmpty() })
    val groups = games.groupBy { it.seedGroupId }
    require(groups.size == 32)
    groups.values.forEach { legs ->
        require(legs.size == 2 && legs.map { it.leg }.sorted() == listOf(0, 1))
        require(legs.map { it.partition }.distinct().size == 1)
    }
    val development = groups.filterValues { it.first().partition == RealGamePositionPartition.DEVELOPMENT }.keys.sorted()
    require(development.size == 25 && groups.size - development.size == 7)
    return development.take(16).toSet()
}

internal fun selectFactualResidualRoot(candidates: List<FactualResidualRootMetadata>): FactualResidualRootMetadata {
    require(candidates.isNotEmpty()) { "The allocated screen leg has no technically eligible searched root" }
    require(candidates.map { it.assignment.rootId }.distinct().size == candidates.size)
    return candidates.minWith(compareBy<FactualResidualRootMetadata>(
        { sha256("$FACTUAL_RESIDUAL_ROOT_ORDER:${it.assignment.rootId}") }, { it.assignment.rootId }))
}

internal fun requireFactualResidualAllocation(games: List<FactualResidualGameAllocation>) {
    require(games.size == 64 && games.map { it.sourceRunIdentity to it.gameId }.distinct().size == games.size)
    require(games == games.sortedWith(compareBy({ it.seedGroupId }, { it.leg }, { it.sourceRunIdentity }, { it.gameId })))
    val groups = games.groupBy { it.seedGroupId }
    require(groups.size == 32)
    groups.values.forEach { pair ->
        require(pair.size == 2 && pair.map { it.leg }.sorted() == listOf(0, 1))
        require(pair.map { it.viewer }.sorted() == listOf("p0", "p1"))
        require(pair.map { it.role }.distinct().size == 1 && pair.map { it.originalPartition }.distinct().size == 1)
    }
    val development = groups.filterValues { it.first().originalPartition == RealGamePositionPartition.DEVELOPMENT }.keys.sorted()
    require(development.size == 25 && groups.size - development.size == 7)
    require(groups.filterValues { it.first().role == FactualResidualDataRole.TRAIN }.keys == development.take(16).toSet())
    require(games.count { it.root != null } == 32)
}

internal fun factualResidualAllocationBindings(study: String, inventory: FactualResidualInput,
    incumbent: SearchTeacherCalibrationPolicy, games: List<FactualResidualGameAllocation>) = ResearchRunBindings(
    protocol = "factual-residual-allocation-v1", material = mapOf(
        "study" to study, "inventory" to inventory.identity, "inventory-manifest" to inventory.manifestSha256,
        "incumbent" to sha256(evidenceJson.encodeToString(incumbent)),
        "games" to sha256(evidenceJson.encodeToString(games)), "frame-rule" to FACTUAL_RESIDUAL_FRAME_RULE,
        "weighting" to FACTUAL_RESIDUAL_WEIGHTING, "root-order" to FACTUAL_RESIDUAL_ROOT_ORDER,
    ),
)

internal data class LoadedFactualResidualInputs(
    val inventory: RealGamePositionBankReport,
    val parents: Map<String, SearchTeacherCalibrationReport>,
    val sources: Map<String, FactualResidualInput>,
)

/** Existing completed-calibration and bank authorities authenticate the original games and checkpoints. */
internal fun loadFactualResidualInputs(plan: FactualResidualStudyPlan, deck: DeckManifest): LoadedFactualResidualInputs {
    verifyFactualResidualInput(plan.inventory, setOf("report.json", "plan.json"))
    val bank = loadVerifiedRealGamePositionBank(Path.of(plan.inventory.directory), plan.inventory.identity)
    factualResidualTrainingGroups(bank.games)
    require(bank.plan.validationFraction == 0.25)
    require(bank.sources.map { it.runIdentity }.distinct().size == bank.sources.size)
    val sources = bank.sources.associate { source ->
        source.runIdentity to FactualResidualInput(source.runDirectory, source.runIdentity, source.manifestSha256)
    }
    val parents = sources.mapValues { (_, reference) ->
        verifyFactualResidualInput(reference, setOf("report.json", "plan.json"))
        loadCompletedCalibration(Path.of(reference.directory), reference.identity).also { parent ->
            val binding = bank.sources.single { it.runIdentity == parent.runIdentity }
            require(researchSha256File(Path.of(reference.directory).resolve("report.json")) == binding.reportSha256)
            require(parent.sourceProvenance == binding.sourceProvenance)
            require(parent.sourceProvenance.argentum.revision == FACTUAL_INCUMBENT_ARGENTUM_REVISION && parent.sourceProvenance.gitlinkMatchesCheckout)
            require(parent.deckHash == deck.deckHash() && parent.cardPoolHash == deck.cardPoolHash())
            require(parent.policies.size == 2 && parent.policies.map { it.descriptor.id }.distinct().size == 2)
            require(parent.policies.any { it.descriptor == plan.incumbent })
            require(parent.policies.all { it.descriptor.copy(id = plan.incumbent.id) == plan.incumbent }) {
                "Factual targets require material incumbent self-play, including both rollout configurations"
            }
        }
    }
    val actualGames = parents.flatMap { (run, parent) -> parent.comparisons.flatMap { c -> c.pairs.flatMap { pair ->
        require(pair.valid && pair.invalidationReasons.isEmpty())
        pair.games.mapIndexed { leg, game -> Triple(run, game.gameId, leg) }
    } } }.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    require(actualGames == bank.games.map { Triple(it.sourceRunIdentity, it.sourceGameId, it.leg) }
        .sortedWith(compareBy({ it.first }, { it.second }, { it.third })))
    return LoadedFactualResidualInputs(bank, parents, sources)
}

internal fun factualResidualSourceGame(inputs: LoadedFactualResidualInputs, selected: RealGamePositionBankGame): GameRunResult =
    inputs.parents.getValue(selected.sourceRunIdentity).comparisons.flatMap { it.pairs }.single { it.pairIndex == selected.pairIndex }
        .games.also { require(it.size == 2) }[selected.leg].also { require(it.gameId == selected.sourceGameId) }

/** Outcome-bearing source reports never enter the pure root-order function. */
internal fun allocateFactualResidualStudy(plan: FactualResidualStudyPlan, inputs: LoadedFactualResidualInputs,
    studyIdentity: String): FactualResidualAllocation {
    val train = factualResidualTrainingGroups(inputs.inventory.games)
    val assignments = inputs.inventory.assignments.groupBy { it.sourceRunIdentity to it.sourceGameId }
    val games = inputs.inventory.games.map { selected ->
        val source = inputs.parents.getValue(selected.sourceRunIdentity)
        val game = factualResidualSourceGame(inputs, selected)
        val viewer = when (plan.incumbent.id) {
            game.p0PolicyId -> "p0"
            game.p1PolicyId -> "p1"
            else -> error("Incumbent policy absent from allocated leg")
        }
        val role = if (selected.seedGroupId in train) FactualResidualDataRole.TRAIN else FactualResidualDataRole.SCREEN
        require(game.seed == source.plan.pairSeed(selected.pairIndex))
        require(realGamePositionSeedGroup(source.deckHash, source.cardPoolHash, game.seed) == selected.seedGroupId)
        val root = if (role == FactualResidualDataRole.TRAIN) null else {
            val decisions = requireNotNull(game.seatDiagnostics[viewer]).searchDecisionsDetail.associateBy { it.decisionIndex }
            val metadata = assignments.getValue(selected.sourceRunIdentity to selected.sourceGameId).mapNotNull { assignment ->
                if (assignment.actor != viewer || assignment.sourcePolicyId != plan.incumbent.id) return@mapNotNull null
                val diagnostic = requireNotNull(decisions[assignment.decisionIndex])
                val candidates = diagnostic.candidateStatistics.map { it.choice }
                if (candidates.size < 2 || diagnostic.chosen == null) return@mapNotNull null
                if (runCatching { requireValidScreenSearch(diagnostic.searchDiagnostics) }.isFailure) return@mapNotNull null
                if (diagnostic.searchDiagnostics.simulations != 56 || diagnostic.searchDiagnostics.freshSimulations != 56 ||
                    diagnostic.searchDiagnostics.reusedSimulations != 0 || diagnostic.searchDiagnostics.particles != 8) return@mapNotNull null
                require(assignment.seedGroupId == selected.seedGroupId && assignment.partition == selected.partition)
                FactualResidualRootMetadata(assignment, candidates)
            }
            selectFactualResidualRoot(metadata)
        }
        FactualResidualGameAllocation(selected.sourceRunIdentity, selected.sourceGameId, selected.seedGroupId,
            selected.partition, role, selected.leg, viewer, game.decisions,
            if (role == FactualResidualDataRole.TRAIN) factualResidualFrameIndices(game.decisions) else emptyList(), root)
    }.sortedWith(compareBy({ it.seedGroupId }, { it.leg }, { it.sourceRunIdentity }, { it.gameId }))
    return FactualResidualAllocation(factualResidualAllocationBindings(studyIdentity, plan.inventory, plan.incumbent, games),
        studyIdentity, plan.inventory, plan.incumbent, games)
}
