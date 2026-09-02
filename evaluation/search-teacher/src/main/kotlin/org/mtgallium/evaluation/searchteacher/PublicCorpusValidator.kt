package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.ArrayDeque
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import org.mtgallium.agent.infoset.core.BOUNDED_POLICY_INPUT_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler
import org.mtgallium.agent.infoset.core.CANDIDATE_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.POLICY_HISTORY_COMMITMENT_ALGORITHM
import org.mtgallium.agent.infoset.core.POLICY_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.PERSPECTIVE_EVENT_SCHEMA_V3
import org.mtgallium.agent.infoset.core.PolicyBeliefSummary
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyInformationStateDigest
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyKnowledgeAccumulator
import org.mtgallium.agent.infoset.core.PolicyTrajectoryCompletion
import org.mtgallium.agent.infoset.core.PolicyTrajectoryDecision
import org.mtgallium.agent.infoset.core.PolicyTrajectoryForcedTransition
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryOutcome
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord
import org.mtgallium.agent.infoset.core.PlannerEvidenceSidecar
import org.mtgallium.agent.infoset.core.PerspectiveEventDetail
import org.mtgallium.agent.infoset.core.PublicArtifactPrivacy
import org.mtgallium.agent.infoset.core.TRAJECTORY_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.exactSingletonPassOrNull
import org.mtgallium.agent.infoset.core.selectedSearchWinnerOrNull

/** Validates only perspective-safe artifacts and never opens privileged replay companions. */
internal class PublicCorpusValidator(
    private val root: Path,
    private val knownDecks: Map<String, Map<String, Int>>,
) {
    fun validate(manifestPath: Path): CorpusValidationReport = inspect(manifestPath, null).report

    internal fun inspectForBehavioralCloning(
        manifestPath: Path,
        scope: BehavioralCloningAdmissionScope,
    ): BehavioralCloningInspection = inspect(manifestPath, scope)

    private fun inspect(
        manifestPath: Path,
        admissionScope: BehavioralCloningAdmissionScope?,
    ): BehavioralCloningInspection {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedManifest = manifestPath.toAbsolutePath().normalize()
        val failures = mutableListOf<String>()
        val manifestJson = Files.readString(normalizedManifest)
        PublicArtifactPrivacy.requireSafeJson(manifestJson, "public corpus manifest")
        val manifest = runCatching {
            evidenceJson.decodeFromString<CorpusManifest>(manifestJson)
        }.getOrElse { error ->
            throw IllegalArgumentException("Corpus manifest is not valid v5 JSON: ${error.message}", error)
        }
        if (!manifest.passed) failures += "manifest was not admitted by its producer"
        if (manifest.entries.isEmpty()) failures += "manifest contains no corpus entries"
        if (manifest.entries.map { it.gameId }.distinct().size != manifest.entries.size) {
            failures += "manifest contains duplicate game ids"
        }
        if (manifest.entries.map { it.publicTrajectory }.distinct().size != manifest.entries.size) {
            failures += "manifest contains duplicate public trajectory paths"
        }
        if (manifest.requestedGames != manifest.entries.size) {
            failures += "manifest requested-game count is inconsistent"
        }
        if (manifest.terminalGames != manifest.entries.count { it.game.terminal }) {
            failures += "manifest terminal-game count is inconsistent"
        }
        if (manifest.replayVerifiedGames != manifest.entries.count { it.replayVerified }) {
            failures += "manifest replay-verified count is inconsistent"
        }
        admissionScope?.let { scope ->
            if (manifest.outerCommit != scope.expectedOuterRevision) {
                failures += "manifest outer revision is outside the admitted frozen scope"
            }
            if (manifest.argentumCommit != scope.expectedArgentumRevision) {
                failures += "manifest Argentum revision is outside the admitted frozen scope"
            }
            if (manifest.profileId != scope.profileId) {
                failures += "manifest profile id is outside the admitted frozen scope"
            }
            if (manifest.profileHash != scope.profileHash) {
                failures += "manifest profile hash is outside the admitted frozen scope"
            }
        }

        val inspectedFiles = manifest.entries.map { entry ->
            val path = normalizedRoot.resolve(entry.publicTrajectory).normalize()
            val entryFailures = mutableListOf<String>()
            if (Path.of(entry.publicTrajectory).isAbsolute) entryFailures += "trajectory path must be relative"
            if (!path.startsWith(normalizedRoot)) entryFailures += "trajectory path escapes repository root"
            if (path.parent?.fileName?.toString() != "public") {
                entryFailures += "trajectory is not in a public directory"
            }
            if (!Files.isRegularFile(path)) entryFailures += "trajectory file is missing"
            if (Files.isRegularFile(path)) {
                if (entry.publicSha256 == null || sha256File(path) != entry.publicSha256) {
                    entryFailures += "SHA-256 mismatch"
                }
                if (Files.size(path) != entry.publicSizeBytes) entryFailures += "file-size mismatch"
            }
            if (entryFailures.isNotEmpty()) {
                TrajectoryInspection(
                    CorpusValidationFile(
                        entry.gameId,
                        entry.publicTrajectory,
                        0,
                        0,
                        0,
                        0,
                        false,
                        entryFailures,
                    ),
                    emptyList(),
                )
            } else {
                inspectTrajectory(entry, path, manifest, admissionScope)
            }
        }
        val files = inspectedFiles.map(TrajectoryInspection::file)
        failures += files.flatMap { file -> file.failures.map { "${file.gameId}: $it" } }
        val report = CorpusValidationReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = manifest.outerCommit,
            argentumCommit = manifest.argentumCommit,
            sourceManifest = normalizedRoot.relativize(normalizedManifest).toString(),
            sourceManifestHash = sha256File(normalizedManifest),
            profileHash = manifest.profileHash,
            games = files.size,
            terminalGames = files.count { it.passed },
            searchDecisions = files.sumOf { it.searchDecisions },
            events = files.sumOf { it.events },
            files = files,
            passed = failures.isEmpty() && files.isNotEmpty(),
            failures = failures,
        )
        return BehavioralCloningInspection(
            report = report,
            examples = inspectedFiles.flatMap(TrajectoryInspection::examples)
                .takeIf { report.passed && admissionScope != null }
                .orEmpty(),
        )
    }

    private fun inspectTrajectory(
        entry: CorpusEntry,
        path: Path,
        manifest: CorpusManifest,
        admissionScope: BehavioralCloningAdmissionScope?,
    ): TrajectoryInspection {
        val failures = mutableListOf<String>()
        val ledger = mutableListOf<PolicyHistoryEvent>()
        var commitment = PolicyHistoryCommitment.empty()
        val recent = ArrayDeque<PolicyHistoryEvent>()
        val knowledge = PolicyKnowledgeAccumulator()
        var header: PolicyTrajectoryHeader? = null
        var outcome: PolicyTrajectoryOutcome? = null
        var lineNumber = 0
        var wrapperDecisions = 0
        var lastDecisionIndex = -1
        val decisions = mutableListOf<PolicyTrajectoryDecision>()
        val acceptedTransitionChoices = mutableListOf<AcceptedTransitionChoice>()
        val examples = mutableListOf<BehavioralCloningExample>()
        runCatching {
            check(entry.game.gameId == entry.gameId) { "entry game-summary id mismatch" }
            check(entry.game.disposition == GameRunDisposition.GAME_ENDED) {
                "entry is a stopped run: ${entry.game.disposition}"
            }
            check(entry.game.terminal) { "entry game summary is not terminal" }
            check(entry.game.informationLedgerComplete && entry.game.unsupportedInformationEvents.isEmpty()) {
                "entry has an incomplete represented-information ledger"
            }
            check(entry.game.evidenceStop == null) { "entry carries an evidence-stop trigger" }
            check(entry.game.failureCategory == null) { "entry carries a software failure category" }
            check(entry.game.illegalResponses == 0) { "entry carries an illegal live response" }
            check(entry.game.fallbacks == 0) { "entry carries an evidence-invalidating fallback" }
            check(!entry.game.stepLimit) { "entry exhausted a game or decision limit" }
            check(entry.replayVerified) { "entry canonical replay is not verified" }
            check(entry.policyEvidenceIdentity != null && entry.behaviorSpecificationSha256 != null) {
                "entry has no complete teacher-policy identity"
            }
            val searchSeats = buildList {
                if (entry.game.p0Policy == ArenaPolicyKind.SEARCH) add("p0")
                if (entry.game.p1Policy == ArenaPolicyKind.SEARCH) add("p1")
            }
            check(searchSeats.size == 1 && entry.game.searchSeat == searchSeats.single()) {
                "entry does not bind exactly one actual Search Teacher seat"
            }
            admissionScope?.let {
                check(entry.game.searchPlanner == SearchPlannerKind.SHARED_TREE) {
                    "entry did not use the admitted shared-tree Search Teacher"
                }
            }

            GZIPInputStream(Files.newInputStream(path)).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    lineNumber++
                    check(outcome == null) { "record occurs after outcome at line $lineNumber" }
                    PublicArtifactPrivacy.requireSafeJson(line, "public trajectory line $lineNumber")
                    val record = PolicyJson.format.decodeFromString(PolicyTrajectoryRecord.serializer(), line)
                    check(record.schemaVersion == TRAJECTORY_SCHEMA_CURRENT) {
                        "non-current trajectory record at line $lineNumber"
                    }
                    check(record.gameId == entry.gameId) { "game id mismatch at line $lineNumber" }
                    when (record) {
                        is PolicyTrajectoryHeader -> {
                            check(lineNumber == 1 && header == null) { "header is not the unique first record" }
                            check(record.observationSchemaVersion == POLICY_SCHEMA_CURRENT) {
                                "trajectory uses an obsolete policy-observation schema"
                            }
                            check(record.boundedInputSchemaVersion == BOUNDED_POLICY_INPUT_SCHEMA_CURRENT) {
                                "trajectory uses an obsolete bounded-policy schema"
                            }
                            check(record.candidateSchemaVersion == CANDIDATE_SCHEMA_CURRENT) {
                                "trajectory uses an obsolete semantic-candidate schema"
                            }
                            check(record.historyCommitmentAlgorithm == POLICY_HISTORY_COMMITMENT_ALGORITHM) {
                                "trajectory uses an unknown history commitment"
                            }
                            check(record.outerCommit == manifest.outerCommit) { "outer source revision mismatch" }
                            check(record.argentumCommit == manifest.argentumCommit) {
                                "Argentum source revision mismatch"
                            }
                            check(record.profileManifestHash == manifest.profileHash) { "profile hash mismatch" }
                            check(record.behaviorBinding.sourceProvenance == manifest.sourceProvenance) {
                                "trajectory source provenance differs from corpus manifest"
                            }
                            check(record.behaviorBinding.sourceProvenance.gitlinkMatchesCheckout) {
                                "trajectory source provenance has a mismatched Argentum gitlink"
                            }
                            check(record.policyVersion == entry.policyEvidenceIdentity) {
                                "entry policy identity differs from trajectory header"
                            }
                            check(
                                record.behaviorBinding.behaviorSpecificationSha256 ==
                                    entry.behaviorSpecificationSha256
                            ) { "entry behavior-specification commitment differs from trajectory header" }
                            check(record.perspectivePlayerId == entry.game.searchSeat) {
                                "trajectory perspective differs from the Search Teacher seat"
                            }
                            admissionScope?.let { scope ->
                                check(record.deckManifestHash == scope.deckManifestHash) {
                                    "trajectory deck hash is outside the admitted frozen scope"
                                }
                                check(record.cardPoolHash == scope.cardPoolHash) {
                                    "trajectory card-pool hash is outside the admitted frozen scope"
                                }
                                check(record.actionSpaceProfile == scope.actionSpaceProfile) {
                                    "trajectory action profile is outside the admitted frozen scope"
                                }
                                check(record.leaf == scope.leaf &&
                                    record.evaluatorVersion == scope.leaf.evaluator.evaluatorId) {
                                    "trajectory evaluator is outside the admitted frozen profile"
                                }
                            }
                            header = record
                        }
                        is PolicyTrajectoryDecision -> {
                            val trajectoryHeader = requireNotNull(header) { "decision precedes header" }
                            check(record.decisionIndex == wrapperDecisions) {
                                "search decision is not bound to the current accepted decision"
                            }
                            check(record.decisionIndex > lastDecisionIndex) { "decision indices do not increase" }
                            lastDecisionIndex = record.decisionIndex
                            check(record.actingPlayerId == entry.game.searchSeat) {
                                "decision actor differs from the Search Teacher seat"
                            }
                            check(record.policyInput.observation.perspectivePlayerId == record.actingPlayerId) {
                                "bounded observation perspective differs from its actor"
                            }
                            check(record.policyInput.knowledge.perspectivePlayerId == record.actingPlayerId) {
                                "bounded knowledge perspective differs from its actor"
                            }
                            check(!record.policyInput.terminated && record.policyInput.winnerId == null) {
                                "terminal policy input is not a strategic Search Teacher decision"
                            }
                            check(record.policyInput.schemaVersion == BOUNDED_POLICY_INPUT_SCHEMA_CURRENT) {
                                "decision uses an obsolete bounded-policy schema"
                            }
                            check(record.policyInput.candidateSchemaVersion == CANDIDATE_SCHEMA_CURRENT) {
                                "decision uses an obsolete semantic-candidate schema"
                            }
                            check(record.policyVersion == trajectoryHeader.policyVersion) {
                                "decision policy identity differs from header"
                            }
                            check(record.evaluatorVersion == trajectoryHeader.evaluatorVersion &&
                                record.leaf == trajectoryHeader.leaf) {
                                "decision evaluator or leaf differs from header"
                            }
                            check(record.actionSpaceProfile == trajectoryHeader.actionSpaceProfile) {
                                "decision action-space profile differs from header"
                            }
                            check(record.beliefVersion == trajectoryHeader.beliefVersion) {
                                "decision belief identity differs from header"
                            }
                            check(record.opponentModelVersion == trajectoryHeader.opponentModelVersion) {
                                "decision opponent-model identity differs from header"
                            }
                            check(record.searchDiagnostics.opponentModelId == record.opponentModelVersion) {
                                "search diagnostics disagree with the opponent-model identity"
                            }
                            check(record.searchDiagnostics.configuredEvaluatorId == record.evaluatorVersion) {
                                "search diagnostics disagree with the evaluator identity"
                            }
                            check(record.searchDiagnostics.invokedEvaluatorId == record.evaluatorVersion) {
                                "search invoked a different evaluator than the declared teacher"
                            }
                            admissionScope?.let { scope ->
                                check(record.searchDiagnostics.particles == scope.particles &&
                                    record.searchDiagnostics.simulations == scope.simulations &&
                                    record.searchDiagnostics.freshSimulations == scope.simulations &&
                                    record.searchDiagnostics.reusedSimulations == 0 &&
                                    record.searchDiagnostics.wallClockBudgetMillis == null) {
                                    "teacher search did not complete the admitted fixed-work profile"
                                }
                                check(
                                    record.searchDiagnostics.invokedEvaluatorConfigurationId ==
                                        scope.invokedEvaluatorConfigurationId
                                ) {
                                    "search evaluator configuration differs from the admitted profile"
                                }
                            }
                            check(record.searchDiagnostics.rejectedTransitions == 0) {
                                "teacher search contains a rejected transition"
                            }
                            check(record.beliefDiagnostics.architecture != BeliefArchitecture.PRIVILEGED_O_V1 &&
                                record.policyInput.belief.architecture != BeliefArchitecture.PRIVILEGED_O_V1) {
                                "privileged belief representation is not admissible student evidence"
                            }
                            check(
                                record.beliefVersion ==
                                    "${record.beliefDiagnostics.architecture.name.lowercase()}:" +
                                    record.beliefDiagnostics.mode.name.lowercase()
                            ) { "decision belief identity disagrees with runtime diagnostics" }
                            val expectedBelief = PolicyBeliefSummary.from(
                                record.beliefDiagnostics,
                                record.policyInput.knowledge.knowledgeDigest,
                            )
                            check(record.policyInput.belief == expectedBelief) {
                                "bounded belief summary is not the declared safe diagnostic projection"
                            }
                            check(record.policyInput.historyCommitment == commitment) {
                                "decision commitment mismatch"
                            }
                            check(record.policyInput.recentEvents.size <= 64) { "recent-event limit exceeded" }
                            check(
                                record.policyInput.recentEventStartCursor ==
                                    commitment.cursor - record.policyInput.recentEvents.size
                            ) { "recent-event cursor mismatch" }
                            check(
                                recent.toList().takeLast(record.policyInput.recentEvents.size) ==
                                    record.policyInput.recentEvents
                            ) { "recent-event suffix mismatch" }
                            record.policyInput.requireValidDigest()
                            val snapshot = knowledge.snapshot(
                                perspectivePlayerId = record.policyInput.observation.perspectivePlayerId,
                                knownDecks = knownDecks,
                                currentObservation = record.policyInput.observation,
                            )
                            check(snapshot == record.policyInput.knowledge) { "incremental knowledge mismatch" }
                            check(snapshot.epistemicallyComplete) {
                                "trajectory contains an unrepresented player-visible fact"
                            }
                            val expectedInformationDigest = PolicyInformationStateDigest.compute(
                                record.policyInput.observation.observationDigest,
                                commitment,
                                snapshot.knowledgeDigest,
                                record.actingPlayerId,
                                record.expansion.candidates.map { it.signature },
                                record.expansion.proposalVersion,
                            )
                            check(expectedInformationDigest == record.informationStateDigest) {
                                "information-state digest mismatch"
                            }
                            check(record.policyInput.candidates == record.expansion.candidates) {
                                "input/expansion candidates differ"
                            }
                            check(record.expansion.isProfileExhaustive &&
                                record.expansion.omissionReasons.all { it.intentionalProfileOmission }) {
                                "teacher expansion exhausted a response or generation limit"
                            }
                            check(record.expansion.exactSingletonPassOrNull() == null) {
                                "rules-forced pass is not a searched strategic decision"
                            }
                            val expanded = record.expansion.candidates.map { it.signature }.toSet()
                            check(record.candidates.isNotEmpty()) {
                                "search statistics contain no strategic candidates"
                            }
                            check(record.candidates.map { it.choice.signature }.distinct().size ==
                                record.candidates.size) { "search statistics contain duplicate candidates" }
                            check(record.candidates.all { candidate ->
                                candidate.visits >= 0 && candidate.meanValue.isFinite() &&
                                    candidate.policyProbability.isFinite() &&
                                    candidate.policyProbability in 0.0..1.0
                            }) { "search statistics contain an invalid value" }
                            val totalVisits = record.candidates.sumOf { it.visits.toLong() }
                            check(totalVisits == record.searchDiagnostics.simulations.toLong()) {
                                "search candidate visits do not account for the completed simulations"
                            }
                            check(record.candidates.all { candidate ->
                                abs(candidate.policyProbability -
                                    candidate.visits.toDouble() / totalVisits.toDouble()) <= 1e-12
                            }) { "search candidate probabilities disagree with visit counts" }
                            check(record.candidates.map { it.choice.signature }.all { it in expanded }) {
                                "search statistics contain an unexpanded candidate"
                            }
                            check(record.candidates.all { it.choice in record.expansion.candidates }) {
                                "search statistics differ from the exact expanded semantic choices"
                            }
                            check(record.chosen.signature in expanded && record.heuristicChoice.signature in expanded) {
                                "chosen candidate is outside expansion"
                            }
                            check(record.chosen in record.expansion.candidates) {
                                "teacher choice differs from the exact expanded semantic choice"
                            }
                            check(record.candidates.selectedSearchWinnerOrNull()?.choice == record.chosen) {
                                "teacher label is not the deterministic winner of serialized search statistics"
                            }
                            val information = record.informationState(ledger)
                            check(
                                BoundedPolicyInputCompiler.compile(information, record.policyInput.belief) ==
                                    record.policyInput
                            ) { "bounded student input is not reproducible from current safe evidence" }
                            decisions += record
                        }
                        is PolicyTrajectoryForcedTransition -> {
                            check(header != null) { "transition precedes header" }
                            check(record.afterDecisionIndex == wrapperDecisions) {
                                "forced-transition indices are not contiguous"
                            }
                            val choiceEvents = record.events.mapNotNull { event ->
                                (event.detail as? PerspectiveEventDetail.Choice)?.let { event.actor to it }
                            }
                            check(choiceEvents.size == 1) {
                                "accepted transition does not contain exactly one perspective-safe choice event"
                            }
                            val (choiceActor, choiceDetail) = choiceEvents.single()
                            check(choiceDetail.schemaVersion == PERSPECTIVE_EVENT_SCHEMA_V3) {
                                "accepted transition uses an obsolete typed choice event"
                            }
                            acceptedTransitionChoices += AcceptedTransitionChoice(
                                actor = choiceActor,
                                semanticSignature = choiceDetail.semanticSignature,
                                choiceKind = choiceDetail.choiceKind,
                                operationFamily = choiceDetail.operationFamily,
                            )
                            record.events.forEach { event ->
                                check(event.eventId == ledger.size.toLong()) {
                                    "forced-transition event ids are not contiguous"
                                }
                                ledger += event
                                commitment = commitment.append(event)
                                knowledge.append(event)
                                recent.addLast(event)
                                if (recent.size > 64) recent.removeFirst()
                            }
                            wrapperDecisions++
                        }
                        is PolicyTrajectoryOutcome -> {
                            check(header != null) { "outcome precedes header" }
                            check(record.decisions == wrapperDecisions) { "outcome decision count mismatch" }
                            check(record.semanticResponseSequence.size == wrapperDecisions) {
                                "outcome sequence count mismatch"
                            }
                            check(record.sequenceDigest == semanticResponseSequenceDigest(record)) {
                                "outcome semantic-response digest mismatch"
                            }
                            check(entry.game.decisions == record.decisions) {
                                "entry game summary decision count mismatch"
                            }
                            check(entry.game.winner == record.winnerId) {
                                "entry game summary winner mismatch"
                            }
                            check(record.completion == PolicyTrajectoryCompletion.GAME_ENDED) {
                                "game stopped before it ended: ${record.stopReason}"
                            }
                            outcome = record
                        }
                    }
                }
            }
            val trajectoryHeader = requireNotNull(header) { "missing header" }
            val trajectoryOutcome = requireNotNull(outcome) { "missing outcome" }
            trajectoryOutcome.semanticResponseSequence.forEachIndexed { decisionIndex, accepted ->
                val transition = acceptedTransitionChoices.getOrNull(decisionIndex)
                check(
                    transition?.semanticSignature == accepted?.signature
                ) {
                    "accepted response disagrees with the perspective-safe transition at decision $decisionIndex"
                }
                if (accepted != null) {
                    val typedTransition = requireNotNull(transition)
                    check(
                        typedTransition.choiceKind == accepted.kind.name &&
                            typedTransition.operationFamily == accepted.operationFamily
                    ) {
                        "accepted response type disagrees with the perspective-safe transition at decision " +
                            decisionIndex
                    }
                }
            }
            entry.plannerEvidence?.let { artifact ->
                val repositoryRoot = root.toAbsolutePath().normalize()
                val plannerPath = repositoryRoot.resolve(artifact.reference).normalize()
                check(!Path.of(artifact.reference).isAbsolute && plannerPath.startsWith(repositoryRoot)) {
                    "planner sidecar path escapes repository root"
                }
                check(Files.isRegularFile(plannerPath)) { "planner sidecar is missing" }
                check(sha256File(plannerPath) == artifact.sha256 && Files.size(plannerPath) == artifact.sizeBytes) {
                    "planner sidecar checksum or size mismatch"
                }
                val sidecar = PlannerEvidenceSidecar.readCompressed(plannerPath)
                check(sidecar.binding.gameId == entry.gameId &&
                    sidecar.binding.safeTrajectoryReference == entry.publicTrajectory &&
                    sidecar.binding.safeTrajectorySha256 == entry.publicSha256 &&
                    sidecar.binding.trajectorySchemaVersion == trajectoryHeader.schemaVersion &&
                    sidecar.binding.candidateSchemaVersion == trajectoryHeader.candidateSchemaVersion &&
                    sidecar.binding.behaviorBinding == trajectoryHeader.behaviorBinding &&
                    sidecar.binding.actionSpaceProfile == trajectoryHeader.actionSpaceProfile
                ) { "planner sidecar binding differs from its safe trajectory" }
                check(sidecar.decisions.map { it.decisionIndex }.toSet() == decisions.map { it.decisionIndex }.toSet()) {
                    "planner sidecar does not cover exactly the safe searched decisions"
                }
                sidecar.decisions.forEach { plannerDecision ->
                    val safeDecision = decisions.single { it.decisionIndex == plannerDecision.decisionIndex }
                    check(plannerDecision.actingPlayerId == safeDecision.actingPlayerId &&
                        plannerDecision.informationStateDigest == safeDecision.informationStateDigest &&
                        plannerDecision.selectedCandidateSignature == safeDecision.chosen.signature
                    ) { "planner sidecar decision identity differs from safe trajectory" }
                    check(plannerDecision.candidates.map { it.candidateSignature }.toSet() ==
                        safeDecision.candidates.map { it.choice.signature }.toSet()
                    ) { "planner sidecar candidate family differs from safe trajectory" }
                    plannerDecision.candidates.forEach { plannerCandidate ->
                        val safeCandidate = safeDecision.candidates.single {
                            it.choice.signature == plannerCandidate.candidateSignature
                        }
                        check(plannerCandidate.rawVisits == safeCandidate.visits &&
                            plannerCandidate.backedMean == safeCandidate.meanValue &&
                            plannerCandidate.settlementCounts.successfulBackups == safeCandidate.visits
                        ) { "planner sidecar candidate accounting differs from safe trajectory" }
                    }
                }
            }
            decisions.forEach { decision ->
                val accepted = trajectoryOutcome.semanticResponseSequence.getOrNull(decision.decisionIndex)
                check(accepted != null) {
                    "accepted semantic response is unavailable for Search Teacher decision ${decision.decisionIndex}"
                }
                check(accepted == decision.chosen) {
                    "teacher label does not equal the accepted semantic response at decision " +
                        "${decision.decisionIndex}; chosen=${decision.chosen.signature}, accepted=${accepted.signature}"
                }
                check(acceptedTransitionChoices[decision.decisionIndex].actor == decision.actingPlayerId) {
                    "teacher actor disagrees with the accepted transition at decision ${decision.decisionIndex}"
                }
                admissionScope?.let {
                    val example = BehavioralCloningExample(
                        gameId = entry.gameId,
                        decisionIndex = decision.decisionIndex,
                        actingPlayerId = decision.actingPlayerId,
                        policyInput = decision.policyInput,
                        teacherAction = accepted,
                        evidence = BehavioralCloningEvidenceIdentity(
                            datasetIdentity = manifest.datasetIdentity,
                            publicTrajectorySha256 = requireNotNull(entry.publicSha256),
                            sourceProvenance = manifest.sourceProvenance,
                            deckManifestHash = trajectoryHeader.deckManifestHash,
                            cardPoolHash = trajectoryHeader.cardPoolHash,
                            profileId = manifest.profileId,
                            profileHash = manifest.profileHash,
                            trajectorySchemaVersion = trajectoryHeader.schemaVersion,
                            observationSchemaVersion = trajectoryHeader.observationSchemaVersion,
                            boundedInputSchemaVersion = trajectoryHeader.boundedInputSchemaVersion,
                            candidateSchemaVersion = trajectoryHeader.candidateSchemaVersion,
                            historyCommitmentAlgorithm = trajectoryHeader.historyCommitmentAlgorithm,
                            actionSpaceProfile = trajectoryHeader.actionSpaceProfile,
                            searchPlanner = requireNotNull(entry.game.searchPlanner),
                            policyEvidenceIdentity = trajectoryHeader.policyVersion,
                            behaviorIdentity = trajectoryHeader.behaviorBinding.behaviorIdentity,
                            behaviorSpecificationSha256 =
                                trajectoryHeader.behaviorBinding.behaviorSpecificationSha256,
                            evaluatorVersion = trajectoryHeader.evaluatorVersion,
                            invokedEvaluatorConfigurationId =
                                decision.searchDiagnostics.invokedEvaluatorConfigurationId,
                            leaf = trajectoryHeader.leaf,
                            particles = decision.searchDiagnostics.particles,
                            simulations = decision.searchDiagnostics.simulations,
                            beliefVersion = trajectoryHeader.beliefVersion,
                            opponentModelVersion = trajectoryHeader.opponentModelVersion,
                        ),
                    )
                    PublicArtifactPrivacy.requireSafeJson(
                        PolicyJson.format.encodeToString(BehavioralCloningExample.serializer(), example),
                        "behavioral-cloning example",
                    )
                    examples += example
                }
            }
        }.onFailure { failures += (it.message ?: it::class.simpleName.orEmpty()) }
        return TrajectoryInspection(
            file = CorpusValidationFile(
                gameId = entry.gameId,
                trajectory = entry.publicTrajectory,
                events = commitment.cursor,
                searchDecisions = decisions.size,
                wrapperDecisions = wrapperDecisions,
                bytes = Files.size(path),
                passed = failures.isEmpty(),
                failures = failures,
            ),
            examples = examples.takeIf { failures.isEmpty() }.orEmpty(),
        )
    }
}

internal data class BehavioralCloningInspection(
    val report: CorpusValidationReport,
    val examples: List<BehavioralCloningExample>,
)

private data class TrajectoryInspection(
    val file: CorpusValidationFile,
    val examples: List<BehavioralCloningExample>,
)

private data class AcceptedTransitionChoice(
    val actor: String?,
    val semanticSignature: String?,
    val choiceKind: String,
    val operationFamily: org.mtgallium.agent.infoset.core.SemanticOperationFamily?,
)

private fun semanticResponseSequenceDigest(outcome: PolicyTrajectoryOutcome): String = PolicyJson.sha256(
    outcome.semanticResponseSequence.joinToString("\u001f") { it?.signature ?: "<private>" },
)
