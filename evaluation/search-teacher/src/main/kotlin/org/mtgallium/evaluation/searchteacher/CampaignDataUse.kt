package org.mtgallium.evaluation.searchteacher

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore
import org.mtgallium.research.run.*

@Serializable
internal enum class CampaignDataRole { TRAINING, MODEL_SELECTION, METHOD_SELECTION, INSPECTION, FINAL_EVALUATION }

@Serializable
internal enum class CampaignDataTiming { PROSPECTIVE_RESERVATION, RETROSPECTIVE_RECORD }

/** Bank-only populations record selected positions, not an assertion that their outcomes were read. */
@Serializable
internal data class CampaignPopulationInput(
    val bank: CloningComparisonInput,
    val rootIds: List<String>,
    val screen: CloningComparisonInput? = null,
) {
    init { require(rootIds.isNotEmpty() && rootIds == rootIds.distinct().sorted()) }
}

@Serializable
internal data class CampaignDataUsePlan(
    val schemaVersion: Int = 1,
    val campaignId: String,
    val studyIdentity: String,
    val role: CampaignDataRole,
    val timing: CampaignDataTiming,
    val populations: List<CampaignPopulationInput>,
    val purpose: String,
) {
    init {
        require(schemaVersion == 1 && campaignId.matches(Regex("[a-z][a-z0-9-]*")))
        require(studyIdentity.isNotBlank() && populations.isNotEmpty() && purpose.isNotBlank())
    }
}

@Serializable
internal data class CampaignDataUseRecord(
    val identity: String,
    val plan: CampaignDataUsePlan,
    val recordedAtUtc: String,
    val recordingSource: ResearchRunProvenance,
    val seedGroups: List<String>,
    val rootIds: List<String>,
    val inputManifestHashes: Map<String, String>,
)

@Serializable
internal data class CampaignGroupUse(val seedGroupId: String, val studies: List<String>, val roles: List<CampaignDataRole>, val records: List<String>)

@Serializable
internal data class CampaignDataUseSnapshot(
    val campaignId: String,
    val records: List<CampaignDataUseRecord>,
    val groups: List<CampaignGroupUse>,
    val interpretation: String = "This is the union of registered uses, including prospective reservations and retrospective declarations. It cannot observe unregistered analysis or prove pristine campaign confirmation. Absence of overlap means only no recorded overlap. Retrospective recording time is not historical access time. Library-seed groups, not positions, seats or sample repetitions, identify shared game randomness.",
)

internal data class CampaignVerifiedPopulation(val rootIds: List<String>, val seedGroups: List<String>, val inputManifestHashes: Map<String, String>)

/** Population facts come from existing source-owned loaders; callers cannot supply arbitrary group labels. */
internal fun verifyCampaignPopulation(inputs: List<CampaignPopulationInput>): CampaignVerifiedPopulation {
    val roots = linkedMapOf<String, String>(); val hashes = sortedMapOf<String, String>()
    fun bind(identity: String, hash: String) { require(hashes[identity] == null || hashes[identity] == hash) { "Conflicting manifests for $identity" }; hashes[identity] = hash }
    inputs.forEach { input ->
        val directory = Path.of(input.bank.directory)
        val bank = loadVerifiedRealGamePositionBank(directory, input.bank.researchRunIdentity)
        bind(input.bank.researchRunIdentity, researchSha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
        input.screen?.let { reference ->
            val path = Path.of(reference.directory)
            val manifest = ResearchRunArtifacts.loadAndVerify(path, reference.researchRunIdentity)
            require(manifest.artifacts.map { it.relativePath }.containsAll(listOf("report.json", "plan.json")))
            val selected = campaignScreenRoots(reference, bank)
            require(selected.map { it.rootId }.sorted() == input.rootIds) { "Registered screen population must include all planned roots, including refusals" }
            bind(reference.researchRunIdentity, researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)))
        }
        val byId = bank.roots.associateBy { it.rootId }
        input.rootIds.forEach { id ->
            val group = requireNotNull(byId[id]) { "Unrepresented campaign root: $id" }.seedGroupId
            require(roots[id] == null || roots[id] == group) { "Root group changed across inputs" }
            roots[id] = group
        }
    }
    return CampaignVerifiedPopulation(roots.keys.sorted(), roots.values.distinct().sorted(), hashes)
}

/** Authenticate bytes but decode only declared population metadata before recording target access. */
internal fun campaignScreenRoots(reference: CloningComparisonInput, bank: RealGamePositionBankReport): List<RealGamePositionBankRoot> {
    val path = Path.of(reference.directory)
    val manifest = ResearchRunArtifacts.loadAndVerify(path, reference.researchRunIdentity)
    require(manifest.artifacts.any { it.relativePath == "plan.json" })
    val plan = evidenceJson.decodeFromString<PositionBankScreenPlan>(Files.readString(path.resolve("plan.json")))
    require(plan.expectedBankIdentity == bank.bankIdentity)
    return selectPositionScreenRoots(plan, bank.roots.filter { it.partition.name == plan.partition.name }.sortedBy { it.rootId })
}

internal fun campaignUseBindings(plan: CampaignDataUsePlan, population: CampaignVerifiedPopulation, source: ResearchRunProvenance): ResearchRunBindings =
    ResearchRunBindings(protocol = "campaign-data-use-v1", material = mapOf(
        "plan" to sha256(evidenceJson.encodeToString(CampaignDataUsePlan.serializer(), plan)),
        "recording-source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
        "groups" to sha256(population.seedGroups.joinToString("\n")),
        "roots" to sha256(population.rootIds.joinToString("\n")),
        "input-manifests" to sha256(population.inputManifestHashes.entries.joinToString("\n") { "${it.key}=${it.value}" }),
    ))

/** Independent immutable records; no mutable scientific identity for the evolving campaign directory. */
internal class CampaignDataRegistry(private val repository: Path, private val directory: Path, private val campaignId: String) {
    init { EvidenceStore(repository).requireDiagnosticOutput(directory, "campaign population usage") }

    fun record(plan: CampaignDataUsePlan): CampaignDataUseRecord {
        require(plan.campaignId == campaignId)
        val p = verifyCampaignPopulation(plan.populations)
        val recordingSource = ResearchRunProvenance.capture(repository).also { it.requireReady(); require(!it.outerDirty && !it.engineDirty) }
        val bindings = campaignUseBindings(plan, p, recordingSource)
        Files.createDirectories(directory)
        FileChannel.open(directory.resolve(".record.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                // A partially written event must be repaired from retained artifacts, never silently ignored.
                snapshot()
                val destination = directory.resolve(bindings.identity.substringAfterLast(':'))
                if (Files.exists(destination)) return readRecord(destination)
                val record = CampaignDataUseRecord(bindings.identity, plan, Instant.now().toString(), recordingSource, p.seedGroups, p.rootIds, p.inputManifestHashes)
                writeJsonAtomically(destination.resolve("bindings.json"), bindings)
                writeJsonAtomically(destination.resolve("record.json"), record)
                ResearchRunArtifacts(destination, bindings.identity).also { a -> a.register("bindings.json"); a.register("record.json"); a.finalize() }
                return readRecord(destination)
            }
        }
    }

    private fun readRecord(path: Path): CampaignDataUseRecord {
        val manifest = ResearchRunArtifacts.loadAndVerify(path)
        require(manifest.artifacts.map { it.relativePath }.containsAll(listOf("bindings.json", "record.json")))
        val record = evidenceJson.decodeFromString<CampaignDataUseRecord>(Files.readString(path.resolve("record.json")))
        val bindings = evidenceJson.decodeFromString<ResearchRunBindings>(Files.readString(path.resolve("bindings.json")))
        require(record.plan.campaignId == campaignId && record.identity == manifest.researchRunIdentity && bindings.identity == record.identity)
        val population = CampaignVerifiedPopulation(record.rootIds, record.seedGroups, record.inputManifestHashes)
        require(record.rootIds.isNotEmpty() && record.rootIds == record.rootIds.distinct().sorted())
        require(record.seedGroups.isNotEmpty() && record.seedGroups == record.seedGroups.distinct().sorted())
        require(campaignUseBindings(record.plan, population, record.recordingSource) == bindings)
        // Source artifacts were authenticated at admission. The registry retains their exact hashes;
        // snapshotting does not repeatedly reread potentially large replay trees.
        return record
    }

    fun snapshot(): CampaignDataUseSnapshot {
        val records = if (!Files.exists(directory)) emptyList() else Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString() != ".record.lock" }.sorted().map { path ->
                require(Files.isDirectory(path) && path.fileName.toString().matches(Regex("[0-9a-f]{64}"))) { "Unexpected campaign entry: $path" }
                readRecord(path).also { require(path.fileName.toString() == it.identity.substringAfterLast(':')) }
            }.toList()
        }
        val groups = records.flatMap { r -> r.seedGroups.map { it to r } }.groupBy({ it.first }, { it.second }).toSortedMap().map { (group, uses) ->
            CampaignGroupUse(group, uses.map { it.plan.studyIdentity }.distinct().sorted(), uses.map { it.plan.role }.distinct().sortedBy { it.name }, uses.map { it.identity }.sorted())
        }
        return CampaignDataUseSnapshot(campaignId, records, groups)
    }
}

internal fun requireStudyGroupSeparation(training: Set<String>, validation: Set<String>) {
    require(training.isNotEmpty() && validation.isNotEmpty())
    require((training intersect validation).isEmpty()) { "Training and validation share library-seed groups" }
}

@Serializable
internal data class CampaignSnapshotPlan(val directory: String, val campaignId: String)

internal fun retainCampaignSnapshot(repository: Path, plan: CampaignSnapshotPlan, output: Path): CampaignDataUseSnapshot {
    val destination = EvidenceStore(repository).requireDiagnosticOutput(output, "campaign snapshot")
    require(!Files.exists(destination))
    val registryPath = Path.of(plan.directory).toAbsolutePath().normalize()
    require(!destination.startsWith(registryPath) && !registryPath.startsWith(destination))
    val snapshot = CampaignDataRegistry(repository, registryPath, plan.campaignId).snapshot()
    val source = ResearchRunProvenance.capture(repository).also { it.requireReady(); require(!it.outerDirty && !it.engineDirty) }
    val bindings = ResearchRunBindings(protocol = "campaign-data-use-snapshot-v1", material = mapOf(
        "campaign" to plan.campaignId,
        "analysis-source" to sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), source)),
        "records" to sha256(evidenceJson.encodeToString(CampaignDataUseSnapshot.serializer(), snapshot))))
    writeJsonAtomically(destination.resolve("bindings.json"), bindings)
    writeJsonAtomically(destination.resolve("snapshot.json"), snapshot)
    finalizeStudyArtifacts(destination, bindings.identity)
    return snapshot
}
