package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy
import org.mtgallium.research.run.*

internal const val ROOT_COVERAGE_PROTOCOL = "decision-local-root-coverage-200-train-50-evaluation-v1"
internal const val ROOT_COVERAGE_ENGINE = "3eda577fdd10d08e0e62d66b4727ab53f1b41ff5"
internal const val ROOT_COVERAGE_PARENT = "research-run-v1-sha256:18c4d77ff57ccb5183ad900fa2c5c54416da9ec67d1e8ffdc68b1defc25e39c0"
internal const val ROOT_COVERAGE_BASE_SEED = 20260823L

@Serializable
internal data class CoverageAssignment(val pairIndex: Int, val split: DecisionLocalSplit, val rank: String) {
    val leg: Int get() = pairIndex % 2
    val seed: Long get() = ComponentSeeds.derive(ROOT_COVERAGE_BASE_SEED, pairIndex, "learned-leaf-pilot-library-orders")
}

/** Whole game lineages are assigned before games or outcomes; global indices never reuse 0..49. */
internal fun coverageAssignments(): List<CoverageAssignment> {
    val ranked = (50 until 300).associateWith { researchSha256("$ROOT_COVERAGE_PROTOCOL:whole-pair-split:$it") }
    val train = ranked.entries.sortedBy { it.value }.take(200).map { it.key }.toSet()
    return ranked.map { (i, rank) -> CoverageAssignment(i,
        if (i in train) DecisionLocalSplit.TRAIN else DecisionLocalSplit.VALIDATION, rank) }
}

internal fun fitExpandedCoverageModel(old: List<DecisionLocalRootEvidence>, fresh: List<DecisionLocalRootEvidence>): DecisionLocalModelCheckpoint {
    require(old.size == 34 && old.all { it.pairIndex in 0 until 50 && it.split == DecisionLocalSplit.TRAIN })
    val expected = coverageAssignments().filter { it.split == DecisionLocalSplit.TRAIN }.map { it.pairIndex }.toSet()
    require(fresh.size == 200 && fresh.map { it.pairIndex }.toSet() == expected && fresh.all { it.split == DecisionLocalSplit.TRAIN })
    require((old + fresh).map { it.pairIndex }.distinct().size == 234)
    return fitLearnabilityModel(old + fresh)
}

/** Constructor fallback only: both playing seats supply their own fully bound historical parameters. */
internal fun coverageArenaProfile(sourceCommit: String): FrozenSearchProfile {
    val control = LearnedLeafPilotRoster.parameters().first
    return FrozenSearchProfile(id = "fast-arena-v1", generatedAtUtc = "UNFROZEN-DIAGNOSTIC",
        outerCommit = sourceCommit, argentumCommit = ROOT_COVERAGE_ENGINE, host = "UNMEASURED",
        particles = control.particles, simulations = control.simulations, leaf = control.leaf,
        actionSpaceProfile = control.actionSpaceProfile, maxPolicyDecisions = control.maxPolicyDecisions,
        explorationConstant = control.explorationConstant, measuredP95Millis = 0.0, tacticalScore = 0.0,
        standardError = 0.0, calibrationReportHash = "UNFROZEN-DIAGNOSTIC-EXPLICIT-POLICIES")
}

@Serializable
internal data class CoveragePlan(
    val protocol: String = ROOT_COVERAGE_PROTOCOL,
    val bindings: ResearchRunBindings,
    val provenance: ResearchRunProvenance,
    val historicalPilot: LearnedLeafFixedRootPilotBinding,
    val generatedPolicyBinding: LearnedLeafFixedRootPilotBinding,
    val assignments: List<CoverageAssignment>,
    val workerThreads: Int,
    val originalModelId: String,
    val specification: List<String> = listOf(
        "One new control-versus-frozen-learned game per global index 50..299; original seed domain, alternating control seat, result-blind family/phase/band selector.",
        "Freeze all 250 reconstructed roots before drawing any new terminal labels; 200 TRAIN and 50 fresh evaluation lineages assigned by protocol SHA256 rank.",
        "Use original feature projection, finite belief/64-coordinate feature schedule, terminal offsets, and 32 matched terminal continuations per candidate.",
        "Fit unchanged ridge on the original 34 TRAIN roots plus 200 new TRAIN roots. Persist model before evaluating the 50 fresh roots.",
        "Evaluate original model, expanded model, cheap heuristic, and uniform baseline on the same fresh panel. Same-sample best regret remains descriptive.",
        "Old six validation and ten TEST roots do not enter expanded fitting or fresh evaluation. No tuning, adaptive replacement, or automatic promotion.",
        "Any failed or unsupported assigned lineage stops the run with retained failure evidence; failure is not a terminal outcome or a silently excluded root.",
    ),
)

@Serializable
internal data class CoverageSelectedGame(val assignment: CoverageAssignment, val game: GameRunResult)

@Serializable
internal data class CoveragePopulation(val researchRunIdentity: String, val roots: List<DecisionLocalRootAssignment>)

@Serializable
internal data class CoverageReport(
    val researchRunIdentity: String,
    val generatedAtUtc: String,
    val plan: CoveragePlan,
    val originalModel: DecisionLocalModelCheckpoint,
    val expandedModel: DecisionLocalModelCheckpoint,
    val originalTrainRoots: Int,
    val newTrainRoots: Int,
    val freshEvaluationRoots: Int,
    val generatedTerminalContinuations: Int,
    val expandedTrain: List<LearnabilityMethodSummary>,
    val freshOriginalModel: List<LearnabilityMethodSummary>,
    val freshExpandedModel: List<LearnabilityMethodSummary>,
    val originalModelRootResults: List<LearnabilityRootResult>,
    val expandedModelRootResults: List<LearnabilityRootResult>,
    val preparationMillis: Double,
    val rootGenerationMillis: Double,
    val trainLabelsMillis: Double,
    val fittingMillis: Double,
    val evaluationMillis: Double,
    val conclusion: String = "ROOT_COVERAGE_COMPLETE_NO_PROMOTION",
)

@Serializable
internal data class CoverageFailure(val researchRunIdentity: String, val phase: String,
    val failure: String, val assignedRoots: Int, val completedBoundRoots: Int, val completedLabelRoots: Int,
    val generatedAtUtc: String = Instant.now().toString(),
    val conclusion: String = "ROOT_COVERAGE_INCOMPLETE_NO_PROMOTION")

internal data class PreparedCoverage(val plan: CoveragePlan, val historical: HistoricalOutcomeValueDiagnosticCheckpoint,
    val oldPilot: LearnedLeafPilotReport, val oldManifest: DecisionLocalRootManifest,
    val oldTrain: List<DecisionLocalRootEvidence>, val originalModel: DecisionLocalModelCheckpoint,
    val arena: SearchTeacherArena, val control: ArenaPolicySpec, val learned: ArenaPolicySpec)

/** A separately bound diagnostic using a frozen historical opponent, never a new promotion capability. */
internal class DecisionLocalRootCoverage(private val repositoryRoot: Path, private val registry: CardRegistry,
    private val deck: DeckManifest) {
    internal fun prepare(parent: Path, pilotDirectory: Path, corpus: Path, gate: Path, workers: Int,
        executionEngine: String = ROOT_COVERAGE_ENGINE): PreparedCoverage {
        require(workers > 0)
        val source = ResearchRunProvenance.capture(repositoryRoot)
        source.requireReady()
        require(!source.outerDirty && !source.engineDirty && source.checkedOutEngineCommit == executionEngine)
        val arena = SearchTeacherArena(registry, deck, coverageArenaProfile(source.outerCommit), ROOT_COVERAGE_BASE_SEED)
        fun verify(directory: Path, identity: String): String {
            ResearchRunArtifacts.loadAndVerify(directory, identity)
            return researchSha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE))
        }
        val learnDirectory = parent.resolve("learnability-32-v1")
        val learnHash = verify(learnDirectory, ROOT_COVERAGE_PARENT)
        val learn = evidenceJson.decodeFromString<DecisionLocalLearnabilityReport>(Files.readString(learnDirectory.resolve("report.json")))
        require(learn.researchRunIdentity == ROOT_COVERAGE_PARENT && learn.plan.bindings.identity == ROOT_COVERAGE_PARENT)
        require(learn.completedRoots == 40 && learn.excludedRoots == 0 && learn.retainedTerminalLabels == 3744 && learn.generatedTerminalLabels == 0)
        require(learn.model == evidenceJson.decodeFromString<DecisionLocalModelCheckpoint>(Files.readString(learnDirectory.resolve("model.json"))))
        val oldDirectory = parent.resolve("experiment-v1")
        val freshDirectory = parent.resolve("precision-8-plus-24-v1")
        val oldHash = verify(oldDirectory, DECISION_LOCAL_PRECISION_PARENT)
        val freshHash = verify(freshDirectory, DECISION_LOCAL_LEARNABILITY_PRECISION)
        require(learn.plan.bindings.material.getValue("original-manifest") == oldHash)
        require(learn.plan.bindings.material.getValue("precision-manifest") == freshHash)
        val manifestDirectory = parent.resolve("root-population-v1")
        ResearchRunArtifacts.loadAndVerify(manifestDirectory)
        val oldManifest = loadDecisionLocalRootManifest(manifestDirectory.resolve("root-manifest.json"))
        require(oldManifest.manifestId == learn.plan.bindings.material.getValue("root-manifest"))
        require(oldManifest.argentumCommit == ROOT_COVERAGE_ENGINE)
        val oldTrain = oldManifest.assignments.filter { it.split == DecisionLocalSplit.TRAIN }.map { a ->
            val (oldEnvelope, freshEnvelope) = loadLearnabilityRootCheckpoints(oldDirectory, freshDirectory, a.root.id, a.split, a.root.pairIndex)
            require(oldEnvelope.payloadSha256 == learn.plan.originalPayloadHashes.getValue(a.root.id))
            require(freshEnvelope.payloadSha256 == learn.plan.precisionPayloadHashes.getValue(a.root.id))
            val old = evidenceJson.decodeFromString<DecisionLocalRootEvidence>(oldEnvelope.payload().decodeToString())
            val fresh = evidenceJson.decodeFromString<PrecisionRootSamples>(freshEnvelope.payload().decodeToString())
            require(old.rootId == a.root.id && old.split == a.split && old.pairIndex == a.root.pairIndex)
            require(fresh.originalCheckpointPayloadSha256 == oldEnvelope.payloadSha256)
            combineLearnabilityRoot(old, fresh)
        }
        require(oldTrain.size == 34 && oldTrain.sumOf { it.candidates.size } == 101)
        val pilotHash = verify(pilotDirectory, DECISION_LOCAL_SOURCE_PILOT_RUN)
        val oldPilot = evidenceJson.decodeFromString<LearnedLeafPilotReport>(Files.readString(pilotDirectory.resolve("report.json")))
        require(oldPilot.valid && oldPilot.runIdentity == DECISION_LOCAL_SOURCE_PILOT_RUN && oldPilot.argentumCommit == ROOT_COVERAGE_ENGINE)
        require(oldPilot.baseSeed == ROOT_COVERAGE_BASE_SEED && oldPilot.deckHash == deck.deckHash() && oldPilot.cardPoolHash == deck.cardPoolHash())
        val historical = HistoricalOutcomeValueDiagnosticCheckpoint.load(corpus, gate)
        require(historical.trainingRunIdentity == oldPilot.trainingRunIdentity && historical.checkpointPayloadSha256 == oldPilot.trainingEnvelopePayloadSha256)
        require(historical.corpusIdentity == oldPilot.corpusIdentity)
        val historicalBinding = learnedLeafFixedRootPilotBinding(oldPilot, historical)
        require(historicalBinding == oldManifest.pilot)
        val (controlParameters, learnedParameters) = LearnedLeafPilotRoster.parameters()
        val control = ArenaPolicySpec(LEARNED_LEAF_PILOT_CONTROL_ID, ArenaPolicyKind.SEARCH, parameters = controlParameters)
        val learned = ArenaPolicySpec(LEARNED_LEAF_PILOT_TREATMENT_ID, ArenaPolicyKind.SEARCH, parameters = learnedParameters,
            informationEvaluator = historical.diagnosticEvaluator())
        require(listOf(control, learned).map(::describeTournamentPolicy) == oldPilot.policies)
        val policyEvidence = listOf(control, learned).associate { it.id to arena.evidenceBinding(it, null, source.sourceProvenance).identity }
        val assignments = coverageAssignments()
        val bindings = ResearchRunBindings(protocol = ROOT_COVERAGE_PROTOCOL, material = mapOf(
            "source" to source.outerCommit, "source-provenance" to researchSha256(evidenceJson.encodeToString(source)),
            "argentum" to source.checkedOutEngineCommit, "deck" to deck.deckHash(), "card-pool" to deck.cardPoolHash(),
            "parent-learnability" to ROOT_COVERAGE_PARENT, "parent-manifest" to learnHash,
            "historical-pilot" to oldPilot.runIdentity, "historical-pilot-manifest" to pilotHash,
            "opponent-checkpoint" to historical.checkpointPayloadSha256,
            "historical-policy-binding" to researchSha256(evidenceJson.encodeToString(historicalBinding)),
            "generation-policies" to researchSha256(evidenceJson.encodeToString(policyEvidence)),
            "assignments" to researchSha256(evidenceJson.encodeToString(assignments)),
            "selection" to DECISION_LOCAL_SELECTION_RULE, "features" to DECISION_LOCAL_FEATURE_SCHEDULE,
            "continuation-seeds" to DECISION_LOCAL_CONTINUATION_SEED_RULE,
            "continuation-policies" to (SearchTeacherSearchFactory.rootRolloutPolicy().behaviorSpecification.toString() + "/" + SearchTeacherSearchFactory.opponentRolloutPolicy().behaviorSpecification),
            "labels" to "32-primary-per-candidate:4096-policy-decision-cap:terminal-only:reject-replacement",
            "fit" to "$DECISION_LOCAL_MODEL_OBJECTIVE:$DECISION_LOCAL_SOLVER:ridge=0.01:tol=1e-7:original34-plus-new200",
            "evaluation" to "frozen-fresh50:original-model-expanded-model-cheap-uniform:root-equal-descriptive-no-promotion",
            "workers" to workers.toString(),
        ))
        val generatedBinding = historicalBinding.copy(runIdentity = bindings.identity,
            control = historicalBinding.control.copy(evidenceIdentity = policyEvidence.getValue(control.id)),
            learned = historicalBinding.learned.copy(evidenceIdentity = policyEvidence.getValue(learned.id)))
        return PreparedCoverage(CoveragePlan(bindings = bindings, provenance = source, historicalPilot = historicalBinding,
            generatedPolicyBinding = generatedBinding, assignments = assignments, workerThreads = workers, originalModelId = learn.model.modelId),
            historical, oldPilot, oldManifest, oldTrain, learn.model, arena, control, learned)
    }

    fun preflight(parent: Path, pilot: Path, corpus: Path, gate: Path, workers: Int, output: Path) {
        require(!Files.exists(output))
        val start = System.nanoTime()
        val p = prepare(parent, pilot, corpus, gate, workers)
        val binding = ResearchRunBindings(protocol = "$ROOT_COVERAGE_PROTOCOL-preflight", material = mapOf(
            "coverage-plan" to p.plan.bindings.identity, "witness" to "retained-roots8-and25-bind:root8-two-old-terminal0:old-pair8-leg1-first-learned-search"))
        ResearchRunFiles.atomicWrite(output.resolve("plan.json"), evidenceJson.encodeToString(p.plan))
        ResearchRunFiles.atomicWrite(output.resolve("preflight-bindings.json"), evidenceJson.encodeToString(binding))
        val rebound = listOf(8, 25).map { index ->
            val oldAssignment = p.oldManifest.assignments.single { it.root.pairIndex == index }
            val oldPair = p.oldPilot.pairs.single { it.pairIndex == index }
            val leg = index % 2
            // The expansion keeps only this leg; odd indices must still retain leg b.
            val selectedPair = oldPair.copy(games = listOf(oldPair.games[leg]))
            val draft = DecisionLocalRootFreezer(repositoryRoot, registry, deck).selectRootDraft(pilot, selectedPair, p.control.id)
            LearnedLeafFixedRootProductionBinder(pilot, registry, deck, p.historical).bindRecordedRoot(
                FixedRootReplayContext(p.oldPilot.runIdentity, p.oldPilot.outerCommit, ROOT_COVERAGE_ENGINE, ROOT_COVERAGE_BASE_SEED, 20260825L),
                p.plan.historicalPilot, draft, selectedPair.games.single()).also { require(it == oldAssignment.root) }
        }.first()
        val reconstructed = reconstructDecisionLocalRoot(pilot, registry, deck, p.plan.historicalPilot, rebound)
        val search = SearchTeacherSearchFactory.create(p.control.effectiveParameters(20260825L).searchConfig(), defaultMonoRedOpponentPolicy())
        val old = p.oldTrain.single { it.pairIndex == 8 }
        old.candidates.take(2).forEach { c ->
            require(continueDecisionLocalCandidate(reconstructed, rebound, old.split, c.signature, 0, search).payoff == c.primaryTerminalPayoffs[0])
        }
        val descriptor = tournamentDescriptor(p.control, p.learned, 8, 1)
        val replay = replayOptions(output, binding.identity, descriptor, p.plan.provenance)
        val stopped = p.arena.playWithPolicies(descriptor.gameId, ComponentSeeds.derive(ROOT_COVERAGE_BASE_SEED, 8, "learned-leaf-pilot-library-orders"),
            p.learned, p.control, replay = replay, maxSearchDecisions = 1)
        require(!stopped.terminal && stopped.exception == null && stopped.replayVerified)
        val decisions = stopped.seatDiagnostics.values.flatMap { it.searchDecisionsDetail }
        require(decisions.size == 1 && decisions.single().searchDiagnostics.simulations == 64)
        require(decisions.single().searchDiagnostics.leaf == p.learned.effectiveParameters(ROOT_COVERAGE_BASE_SEED).leaf)
        ResearchRunFiles.atomicWrite(output.resolve("stopped-search-witness.json"), evidenceJson.encodeToString(stopped))
        ResearchRunFiles.atomicWrite(output.resolve("preflight.json"),
            """{"researchRunIdentity":"${binding.identity}","coveragePlanIdentity":"${p.plan.bindings.identity}","historicalRootBindingsReproduced":2,"historicalTerminalOutcomesReproduced":2,"freshAssignedOutcomesGenerated":0,"learnedSearchDecisions":1,"stoppedWitnessIsTerminal":false,"elapsedMillis":${(System.nanoTime()-start)/1e6}}""")
        finalizeArtifacts(output, binding.identity)
    }

    fun run(parent: Path, pilot: Path, corpus: Path, gate: Path, workers: Int, output: Path, progressPath: Path?): CoverageReport {
        val start = System.nanoTime()
        require(!Files.exists(output.resolve(ResearchRunArtifacts.MANIFEST_FILE))) { "Completed or failed coverage outputs are immutable" }
        val p = prepare(parent, pilot, corpus, gate, workers)
        val identity = p.plan.bindings.identity
        val planPath = output.resolve("plan.json")
        if (Files.exists(output)) require(evidenceJson.decodeFromString<CoveragePlan>(Files.readString(planPath)) == p.plan) {
            "Resume requires the exact committed source and coverage plan"
        } else ResearchRunFiles.atomicWrite(planPath, evidenceJson.encodeToString(p.plan))
        var phase = "root generation"
        val progressLock = Any()
        fun progress(completed: Int, total: Int, unit: String, detail: String) = synchronized(progressLock) {
            progressPath?.let { ResearchRunFiles.atomicWrite(it,
                """{"schemaVersion":1,"updatedAt":"${Instant.now()}","completed":$completed,"total":$total,"unit":"$unit","phase":"$phase","detail":"$detail"}""") }
        }
        try {
            val generationStart = System.nanoTime()
            val generated = AtomicInteger()
            progress(0, 250, "root games", "frozen independent indices 50 through 299")
            val roots = coverageParallel(250, workers) { offset ->
                val a = p.plan.assignments[offset]
                val descriptor = tournamentDescriptor(p.control, p.learned, a.pairIndex, a.leg)
                val gamePath = output.resolve("games/${a.pairIndex}.json")
                val selected = if (Files.exists(gamePath)) loadCoverageCheckpoint<CoverageSelectedGame>(gamePath, identity, "coverage-selected-game-v1", a.pairIndex) else {
                    val replay = replayOptions(output, identity, descriptor, p.plan.provenance)
                    require(!Files.exists(replay.finalPath)) { "Orphan completed replay requires explicit recovery: ${replay.finalPath}" }
                    val result = p.arena.playWithPolicies(descriptor.gameId, a.seed,
                        if (a.leg == 0) p.control else p.learned, if (a.leg == 0) p.learned else p.control,
                        replay = replay)
                    CoverageSelectedGame(a, result).also { persistCoverageCheckpoint(gamePath, identity, "coverage-selected-game-v1", a.pairIndex, it) }
                }
                require(selected.assignment == a)
                requireCoverageGame(selected.game, a, descriptor, output, identity, p)
                val rootPath = output.resolve("roots/${a.pairIndex}.json")
                val root = if (Files.exists(rootPath)) loadCoverageCheckpoint<DecisionLocalRootAssignment>(rootPath, identity, "coverage-bound-root-v1", a.pairIndex) else {
                    val pair = LearnedLeafPilotPair(a.pairIndex, a.seed, listOf(selected.game), true, emptyList())
                    val draft = DecisionLocalRootFreezer(repositoryRoot, registry, deck).selectRootDraft(output, pair, p.control.id)
                    val bound = LearnedLeafFixedRootProductionBinder(output, registry, deck, p.historical).bindRecordedRoot(
                        FixedRootReplayContext(identity, p.plan.provenance.outerCommit, ROOT_COVERAGE_ENGINE, ROOT_COVERAGE_BASE_SEED, 20260825L),
                        p.plan.generatedPolicyBinding, draft, selected.game)
                    DecisionLocalRootAssignment(bound, a.split, a.rank).also {
                        persistCoverageCheckpoint(rootPath, identity, "coverage-bound-root-v1", a.pairIndex, it)
                    }
                }
                require(root.split == a.split && root.splitRankSha256 == a.rank && root.root.pairIndex == a.pairIndex)
                require(root.root.sourceGameId == descriptor.gameId && root.root.replaySha256 == selected.game.replaySha256)
                require(root.root.rootActor == if (a.leg == 0) "p0" else "p1")
                progress(generated.incrementAndGet(), 250, "root games", "frozen root ${a.pairIndex}")
                root
            }.sortedBy { it.root.pairIndex }
            val population = CoveragePopulation(identity, roots)
            val populationPath = output.resolve("root-population.json")
            if (Files.exists(populationPath)) require(evidenceJson.decodeFromString<CoveragePopulation>(Files.readString(populationPath)) == population)
            else ResearchRunFiles.atomicWrite(populationPath, evidenceJson.encodeToString(population))
            val generationEnd = System.nanoTime()
            fun labels(assignments: List<DecisionLocalRootAssignment>): List<DecisionLocalRootEvidence> {
                val total = assignments.sumOf { it.root.candidateSignatures.size * 32 }
                val done = AtomicInteger()
                progress(0, total, "terminal continuations", "frozen root population")
                return coverageParallel(assignments.size, workers) { index ->
                    val a = assignments[index]
                    val path = output.resolve("labels/${a.root.pairIndex}.json")
                    val retained = if (Files.exists(path)) loadCoverageCheckpoint<DecisionLocalRootEvidence>(path, identity, "coverage-terminal-labels-v1", a.root.pairIndex).also {
                        done.addAndGet(it.candidates.sumOf { c -> c.primaryTerminalPayoffs.size })
                    } else DecisionLocalEvidenceMaterializer(output, registry, deck, p.plan.generatedPolicyBinding, p.historical) {
                        progress(done.incrementAndGet(), total, "terminal continuations", "root ${a.root.pairIndex}")
                    }.materialize(a, 32, 0).also { persistCoverageCheckpoint(path, identity, "coverage-terminal-labels-v1", a.root.pairIndex, it) }
                    requireCoverageLabels(a, retained)
                    progress(done.get(), total, "terminal continuations", "retained root ${a.root.pairIndex}")
                    retained
                }
            }
            phase = "TRAIN terminal labels"
            val train = labels(roots.filter { it.split == DecisionLocalSplit.TRAIN })
            val labelsEnd = System.nanoTime()
            phase = "fitting fixed ridge"
            val expandedTraining = p.oldTrain + train
            require(expandedTraining.size == 234)
            val modelPath = output.resolve("expanded-model.json")
            // The fit is cheap; recomputation also authenticates a retained model against these labels on resume.
            val expanded = fitExpandedCoverageModel(p.oldTrain, train)
            if (Files.exists(modelPath)) require(evidenceJson.decodeFromString<DecisionLocalModelCheckpoint>(Files.readString(modelPath)) == expanded)
            else ResearchRunFiles.atomicWrite(modelPath, evidenceJson.encodeToString(expanded))
            val modelEnd = System.nanoTime()
            phase = "fresh evaluation terminal labels"
            val evaluation = labels(roots.filter { it.split == DecisionLocalSplit.VALIDATION })
            require(evaluation.size == 50)
            val originalResults = evaluation.map { evaluateLearnabilityRoot(it, p.originalModel) }
            val expandedResults = evaluation.map { evaluateLearnabilityRoot(it, expanded) }
            val report = CoverageReport(identity, Instant.now().toString(), p.plan, p.originalModel, expanded,
                34, train.size, evaluation.size, (train + evaluation).sumOf { it.candidates.size * 32 },
                summarizeLearnability(expandedTraining.map { evaluateLearnabilityRoot(it, expanded) }),
                summarizeLearnability(originalResults), summarizeLearnability(expandedResults), originalResults, expandedResults,
                (generationStart-start)/1e6, (generationEnd-generationStart)/1e6, (labelsEnd-generationEnd)/1e6,
                (modelEnd-labelsEnd)/1e6, (System.nanoTime()-modelEnd)/1e6)
            ResearchRunFiles.atomicWrite(output.resolve("report.json"), evidenceJson.encodeToString(report))
            ResearchRunFiles.atomicWrite(output.resolve("report.md"), coverageMarkdown(report))
            finalizeArtifacts(output, identity)
            return report
        } catch (failure: Exception) {
            fun count(directory: String): Int = output.resolve(directory).let { path ->
                if (!Files.isDirectory(path)) 0 else Files.list(path).use { files -> files.filter { it.fileName.toString().endsWith(".json") }.count().toInt() }
            }
            ResearchRunFiles.atomicWrite(output.resolve("failure.json"), evidenceJson.encodeToString(
                CoverageFailure(identity, phase, "${failure::class.simpleName}:${failure.message}", 250, count("roots"), count("labels"))))
            finalizeArtifacts(output, identity)
            throw failure
        }
    }

    private fun replayOptions(output: Path, identity: String, descriptor: TournamentGameDescriptor, source: ResearchRunProvenance): GameReplayOptions {
        val path = output.resolve("replays/${descriptor.gameId}.privileged.replay.jsonl.gz")
        return GameReplayOptions(path, repositoryRoot.relativize(path).toString(), identity, source.outerCommit, ROOT_COVERAGE_ENGINE)
    }

    private fun requireCoverageGame(game: GameRunResult, a: CoverageAssignment, descriptor: TournamentGameDescriptor,
        output: Path, identity: String, p: PreparedCoverage) {
        require(game.gameId == descriptor.gameId && game.seed == a.seed && game.p0PolicyId == descriptor.p0PolicyId && game.p1PolicyId == descriptor.p1PolicyId)
        require(learnedLeafPilotGameValid(game)) { "Invalid assigned root game ${a.pairIndex}: ${learnedLeafPilotInvalidationReasons(game)}" }
        val replay = replayOptions(output, identity, descriptor, p.plan.provenance)
        require(game.replayPath == replay.referencePath && game.replaySha256 == researchSha256File(replay.finalPath))
        val canonical = readVerifiedCanonicalSemanticReplay(replay.finalPath)
        require(canonical.header.gameId == descriptor.gameId)
        require(canonical.header.requireExtensionString("mtgallium.runIdentity") == identity)
        require(canonical.header.requireExtensionString("mtgallium.outerCommit") == p.plan.provenance.outerCommit)
        require(canonical.header.requireExtensionString("mtgallium.argentumCommit") == ROOT_COVERAGE_ENGINE)
        require(canonical.header.requireExtensionLong("mtgallium.gameSeed") == a.seed && canonical.header.requireExtensionLong("mtgallium.baseSeed") == ROOT_COVERAGE_BASE_SEED)
        require(canonical.header.requireExtensionString("mtgallium.deckHash") == deck.deckHash() &&
            canonical.header.requireExtensionString("mtgallium.cardPoolHash") == deck.cardPoolHash())
        require(game.terminal && game.disposition == GameRunDisposition.GAME_ENDED &&
            game.winner == canonical.terminal.winnerId && game.decisions == canonical.decisions.size)
        val expectedPolicies = mapOf("p0" to game.p0PolicyId, "p1" to game.p1PolicyId)
        require(game.seatDiagnostics.keys == expectedPolicies.keys)
        game.seatDiagnostics.forEach { (actor, seat) ->
            require(seat.policyId == expectedPolicies.getValue(actor) && seat.searchDecisions == seat.searchDecisionsDetail.size &&
                seat.searchLatenciesMillis == seat.searchDecisionsDetail.map(ArenaSearchDecisionDiagnostic::latencyMillis))
            val policy = if (seat.policyId == p.control.id) p.control else {
                require(seat.policyId == p.learned.id)
                p.learned
            }
            val parameters = policy.effectiveParameters(ROOT_COVERAGE_BASE_SEED)
            seat.searchDecisionsDetail.forEach { d ->
                require(d.searchDiagnostics.particles == parameters.particles && d.searchDiagnostics.simulations == parameters.simulations &&
                    d.searchDiagnostics.leaf == parameters.leaf &&
                    d.settlementCountsAvailability == SettlementCountsAvailability.EXACT_SUCCESSFUL_BACKUPS_V1 &&
                    d.settlementCounts.successfulBackups == d.searchDiagnostics.simulations)
                requirePilotEvaluatorDiagnostic(d, parameters, policy)
            }
        }
    }
}

/** Finish in-flight work before finalizing failure evidence; queued tasks do not start after failure. */
internal fun <T> coverageParallel(count: Int, workers: Int, block: (Int) -> T): List<T> {
    val failure = AtomicReference<Exception?>()
    return try {
        parallelMapOrdered(count, workers) { index ->
            failure.get()?.let { throw IllegalStateException("Stopped queued coverage task $index after an earlier failure", it) }
            try { block(index) } catch (e: Exception) {
                val contextual = IllegalStateException("Coverage task $index failed: ${e.message}", e)
                failure.compareAndSet(null, contextual)
                throw contextual
            }
        }
    } catch (e: Exception) { throw (failure.get() ?: e) }
}

internal fun requireCoverageLabels(a: DecisionLocalRootAssignment, r: DecisionLocalRootEvidence) {
    require(r.rootId == a.root.id && r.pairIndex == a.root.pairIndex && r.split == a.split && r.rootActor == a.root.rootActor)
    require(r.candidateFamilyDigest == a.root.candidateFamilyDigest && r.productionScheduleDigest == a.root.schedule.scheduleDigest)
    require(r.primaryReplicates == 32 && r.independentReplicates == 0 && r.failures.isEmpty())
    require(r.candidates.map { it.signature } == a.root.candidateSignatures)
    require(r.candidates.all { it.primaryTerminalPayoffs.size == 32 && it.independentTerminalPayoffs.isEmpty() &&
        it.primaryTerminalPayoffs.all { p -> p in setOf(-1.0, 0.0, 1.0) } })
}

private inline fun <reified T> loadCoverageCheckpoint(path: Path, identity: String, schema: String, index: Int): T {
    val envelope = ResearchRunCheckpoints.load(path)
    require(envelope.researchRunIdentity == identity && envelope.payloadSchema == schema && envelope.sequence == index.toLong() && envelope.parentPayloadSha256 == null)
    return evidenceJson.decodeFromString(envelope.payload().decodeToString())
}

private inline fun <reified T> persistCoverageCheckpoint(path: Path, identity: String, schema: String, index: Int, value: T) {
    ResearchRunCheckpoints.persist(path, identity, schema, index.toLong(), evidenceJson.encodeToString(value).encodeToByteArray())
}

private fun finalizeArtifacts(output: Path, identity: String) {
    ResearchRunArtifacts(output, identity).also { artifacts ->
        Files.walk(output).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() != ResearchRunArtifacts.MANIFEST_FILE }
            .sorted().forEach { artifacts.register(output.relativize(it).toString()) } }
        artifacts.finalize()
    }
    ResearchRunArtifacts.loadAndVerify(output, identity)
}

private fun coverageMarkdown(r: CoverageReport): String = buildString {
    append("# Broader decision-root coverage\n\n${r.conclusion}\n\nRun: `${r.researchRunIdentity}`\n\n")
    append("234 TRAIN roots (34 retained + 200 new), 50 separately frozen fresh evaluation roots.\n\n")
    append("| Model evaluated on fresh panel | Method | Non-tied roots / 50 | Root-mean ordering accuracy | Sample-best regret | Selected payoff |\n")
    append("| --- | --- | --- | --- | --- | --- |\n")
    listOf("Original 34-root" to r.freshOriginalModel, "Expanded 234-root" to r.freshExpandedModel).forEach { (label, methods) -> methods.forEach { m ->
        append("| $label | ${m.method} | ${m.rootsWithNonTiedPairs}/50 | ${m.rootMeanNonTiedPairAccuracy} | ${m.rootMeanObservedBestRegret} | ${m.rootMeanObservedSelectedPayoff} |\n")
    } }
    append("\nSame-sample best regret is descriptive. All roots remain in payoff/regret summaries; observed tied pairs supply no ordering evidence. Historical engine, frozen opponent and features retained. No old TEST evaluation, tuning, or automatic promotion.\n")
}
