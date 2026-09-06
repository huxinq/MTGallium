package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicyBehaviorBinding
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.agent.infoset.core.PLANNER_EVIDENCE_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunCheckpoints
import org.mtgallium.research.run.ResearchRunFiles
import org.mtgallium.research.run.ResearchRunProvenance
import org.mtgallium.research.run.researchSha256File

/** Only the selected teacher is executable here; other policy descriptors remain opaque and hash-bound. */
internal data class RetainedCalibrationCloningSource(
    val runIdentity: String,
    val plan: JsonObject,
    val sourceProvenance: PolicySourceProvenance,
    val deckHash: String,
    val cardPoolHash: String,
    val teacher: SearchTeacherCalibrationPolicyReport,
    val comparisons: List<SearchTeacherCalibrationComparison>,
) {
    val profileHash get() = sha256(evidenceJson.encodeToString(plan))
    fun pairSeed(index: Int): Long {
        val offset = plan.getValue("pairOffset").jsonPrimitive.int
        val count = plan.getValue("pairCount").jsonPrimitive.int
        require(offset >= 0 && count > 0 && index.toLong() in offset.toLong() until offset.toLong() + count)
        return calibrationPairSeed(plan.getValue("baseSeed").jsonPrimitive.long, index)
    }
}

internal fun readRetainedCalibrationCloningSource(
    reportText: String, planText: String, expectedIdentity: String,
): RetainedCalibrationCloningSource {
    val report = evidenceJson.parseToJsonElement(reportText).jsonObject
    val plan = evidenceJson.parseToJsonElement(planText).jsonObject
    require(report.getValue("plan") == plan)
    require(report.getValue("schemaVersion").jsonPrimitive.int == 1)
    require(plan.getValue("schemaVersion").jsonPrimitive.int == 1)
    require(report.getValue("protocol").jsonPrimitive.content == SEARCH_TEACHER_CALIBRATION_PROTOCOL)
    require(report.getValue("runIdentity").jsonPrimitive.content == expectedIdentity)
    val source = evidenceJson.decodeFromJsonElement<PolicySourceProvenance>(report.getValue("sourceProvenance"))
    val control = evidenceJson.decodeFromJsonElement<SearchTeacherCalibrationPolicy>(plan.getValue("control"))
    val policies = report.getValue("policies").jsonArray.map { it.jsonObject }
    val ids = policies.map { it.getValue("descriptor").jsonObject.getValue("id").jsonPrimitive.content }
    require(ids.distinct().size == ids.size)
    val candidates = plan.getValue("candidates").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
    require((candidates + control.id).distinct().size == candidates.size + 1)
    require(ids.toSet() == (candidates + control.id).toSet())
    val teacher = evidenceJson.decodeFromJsonElement<SearchTeacherCalibrationPolicyReport>(policies.single {
        it.getValue("descriptor").jsonObject.getValue("id").jsonPrimitive.content == control.id
    })
    require(teacher.descriptor == control && teacher.binding.sourceProvenance == source)
    val bindings = policies.associate { policy ->
        policy.getValue("descriptor").jsonObject.getValue("id").jsonPrimitive.content to
            evidenceJson.decodeFromJsonElement<PolicyBehaviorBinding>(policy.getValue("binding")).identity
    }
    val deckHash = report.getValue("deckHash").jsonPrimitive.content
    val cardPoolHash = report.getValue("cardPoolHash").jsonPrimitive.content
    val sequential = report["sequentialRule"]?.takeUnless { it == JsonNull }
    require(retainedCalibrationBindings(evidenceJson.encodeToString(plan), source, bindings,
        deckHash, cardPoolHash, report.getValue("workerThreads").jsonPrimitive.int,
        sequential?.let { evidenceJson.encodeToString(it) }).identity == expectedIdentity)
    val comparisons = evidenceJson.decodeFromJsonElement<List<SearchTeacherCalibrationComparison>>(report.getValue("comparisons"))
    require(comparisons.map { it.candidateId }.sorted() == candidates.sorted())
    return RetainedCalibrationCloningSource(expectedIdentity, plan, source, deckHash, cardPoolHash, teacher, comparisons)
}

@Serializable
internal data class CalibrationCloningLineage(
    val admissionProvenance: ResearchRunProvenance,
    val parentResearchRunIdentity: String,
    val parentManifestSha256: String,
    val datasetIdentity: String,
    val teacherPolicyId: String,
    val teacherPolicyEvidenceIdentity: String,
    /** Both legs from a source pair share this group; this corpus supplies only its p0 teacher leg. */
    val wholePairGroupByGame: Map<String, String>,
    val selection: String = "reference-p0-leg-from-every-reported-pair-no-outcome-based-filter-v1",
)

internal data class AdmittedCalibrationCloning(
    val manifest: CorpusManifest,
    val lineage: CalibrationCloningLineage,
    val admission: BehavioralCloningAdmissionResult,
    val researchRunIdentity: String,
)

/**
 * Reuses immutable safe inputs and accepted search labels. Canonical replay remains private;
 * its retained verification result and exact hash are checked, never converted into features.
 */
internal fun admitCalibrationReferenceCloning(
    repositoryRoot: Path,
    parent: Path,
    expectedParentIdentity: String,
    deck: DeckManifest,
    output: Path,
): AdmittedCalibrationCloning {
    val provenance = ResearchRunProvenance.capture(repositoryRoot, "third_party/argentum-engine").also { it.requireReady() }
    require(!provenance.outerDirty && !provenance.engineDirty) { "Commit admission source before retaining a derived corpus" }
    val directory = EvidenceStore(repositoryRoot).requireDiagnosticOutput(output, "calibration cloning admission")
    require(!Files.exists(directory)) { "Use a fresh derived corpus destination" }
    val artifacts = ResearchRunArtifacts.loadAndVerify(parent, expectedParentIdentity)
    val registered = artifacts.artifacts.associateBy { it.relativePath }
    fun input(relative: String): Path {
        val entry = requireNotNull(registered[relative]) { "Consumed input is absent from parent manifest: $relative" }
        return ResearchRunFiles.resolveBelow(parent, relative).also {
            require(researchSha256File(it) == entry.sha256) { "Consumed input changed after verification: $relative" }
        }
    }
    val report = readRetainedCalibrationCloningSource(Files.readString(input("report.json")),
        Files.readString(input("plan.json")), expectedParentIdentity)
    val scope = BehavioralCloningAdmissionScope.retainedCalibrationReference(deck, report)
    val teacher = report.teacher
    val groups = linkedMapOf<String, String>()
    val entries = report.comparisons.sortedBy { it.candidateId }.flatMap { comparison ->
        comparison.pairs.sortedBy { it.pairIndex }.map { pair ->
            val gameId = "${comparison.candidateId}-pair-${pair.pairIndex}-leg-0"
            val envelopePath = input("checkpoints/$gameId.json")
            require(ResearchRunCheckpoints.load(envelopePath).parentPayloadSha256 == null)
            val game = requireNotNull(loadSearchTeacherCalibrationCheckpoint(envelopePath, parent,
                expectedParentIdentity, pair.pairIndex, 0, report.pairSeed(pair.pairIndex),
                teacher.descriptor.id, comparison.candidateId, gameId))
            require(game == pair.games.single { it.gameId == gameId })
            require(searchBudgetFrontierInvalidationReasons(game).isEmpty()) {
                "A selected reference game is invalid; do not silently filter the training population: $gameId"
            }
            require(game.replayVerified && game.replayVerificationDiagnostic == null)
            val replay = input("replays/$gameId.privileged.replay.jsonl.gz")
            require(game.replaySha256 == researchSha256File(replay)) { "Replay verification is detached from the retained file" }
            val publicRelative = "public/$gameId.p0.jsonl.gz"
            val publicPath = input(publicRelative)
            val plannerRelative = "public/planner/$gameId.p0.planner.json.gz"
            val plannerPath = input(plannerRelative)
            val header = GZIPInputStream(Files.newInputStream(publicPath)).bufferedReader().use { reader ->
                PolicyJson.format.decodeFromString(PolicyTrajectoryRecord.serializer(), requireNotNull(reader.readLine()))
                    as? PolicyTrajectoryHeader ?: error("Public trajectory does not begin with a header")
            }
            require(header.behaviorBinding == teacher.binding && header.perspectivePlayerId == "p0")
            require(header.profileManifestHash == scope.profileHash)
            require(groups.put(gameId, "$expectedParentIdentity:pair-${pair.pairIndex}") == null)
            CorpusEntry(
                gameId = gameId, publicTrajectory = publicRelative,
                publicSha256 = registered.getValue(publicRelative).sha256, publicSizeBytes = Files.size(publicPath),
                policyEvidenceIdentity = teacher.binding.identity,
                behaviorSpecificationSha256 = teacher.binding.behaviorSpecificationSha256,
                plannerEvidence = PlannerEvidenceArtifact(plannerRelative,
                    registered.getValue(plannerRelative).sha256, Files.size(plannerPath), PLANNER_EVIDENCE_SCHEMA_CURRENT),
                replayVerified = game.replayVerified, game = game.toCorpusGameSummary(), teacherSeat = "p0",
            )
        }
    }
    require(entries.isNotEmpty())
    val identity = CorpusManifest.computeDatasetIdentity(scope.profileId, scope.profileHash,
        report.sourceProvenance, entries.size, entries.size, entries.size, entries, true)
    val manifest = CorpusManifest(generatedAtUtc = Instant.now().toString(), profileId = scope.profileId,
        profileHash = scope.profileHash, outerCommit = scope.expectedOuterRevision,
        argentumCommit = scope.expectedArgentumRevision, sourceProvenance = report.sourceProvenance,
        requestedGames = entries.size, terminalGames = entries.size, replayVerifiedGames = entries.size,
        entries = entries, passed = true, datasetIdentity = identity)
    val lineage = CalibrationCloningLineage(provenance, expectedParentIdentity,
        researchSha256File(parent.resolve(ResearchRunArtifacts.MANIFEST_FILE)), identity,
        teacher.descriptor.id, teacher.binding.identity, groups)
    Files.createDirectories(directory)
    val manifestPath = ResearchRunFiles.atomicWrite(directory.resolve("corpus-manifest.json"), evidenceJson.encodeToString(manifest))
    ResearchRunFiles.atomicWrite(directory.resolve("lineage.json"), evidenceJson.encodeToString(lineage))
    val admission = BehavioralCloningAdmission(parent, scope).extract(manifestPath)
    ResearchRunFiles.atomicWrite(directory.resolve("validation.json"), evidenceJson.encodeToString(admission.validation))
    require(admission.passed) { "Reference cloning admission failed: ${admission.failures.joinToString("; ")}" }
    val exampleJson = Json(evidenceJson) { prettyPrint = false }
    GZIPOutputStream(Files.newOutputStream(directory.resolve("examples.jsonl.gz"))).bufferedWriter().use { writer ->
        admission.examples.forEach { writer.appendLine(exampleJson.encodeToString(it)) }
    }
    val derivedIdentity = finalizeCalibrationCloningAdmission(directory, lineage,
        evidenceJson.encodeToString(report.plan))
    return AdmittedCalibrationCloning(manifest, lineage, admission, derivedIdentity)
}

/** Source-owned derived-run binding includes lineage, which the historical corpus schema does not. */
internal fun finalizeCalibrationCloningAdmission(
    directory: Path, lineage: CalibrationCloningLineage, planJson: String,
): String {
    val bindings = ResearchRunBindings(protocol = "calibration-reference-cloning-admission-v1", material = mapOf(
        "admission-provenance" to sha256(evidenceJson.encodeToString(lineage.admissionProvenance)),
        "parent-run" to lineage.parentResearchRunIdentity,
        "parent-manifest" to lineage.parentManifestSha256,
        "teacher" to lineage.teacherPolicyEvidenceIdentity,
        "dataset" to lineage.datasetIdentity,
        "lineage" to sha256(evidenceJson.encodeToString(lineage)),
        "plan" to sha256(planJson),
    ))
    ResearchRunFiles.atomicWrite(directory.resolve("retained-plan.json"), planJson)
    ResearchRunFiles.atomicWrite(directory.resolve("bindings.json"), evidenceJson.encodeToString(bindings))
    ResearchRunArtifacts(directory, bindings.identity).also { artifacts ->
        listOf("corpus-manifest.json", "lineage.json", "validation.json", "retained-plan.json", "bindings.json", "examples.jsonl.gz")
            .forEach { artifacts.register(it) }
        artifacts.finalize()
    }
    return bindings.identity
}
