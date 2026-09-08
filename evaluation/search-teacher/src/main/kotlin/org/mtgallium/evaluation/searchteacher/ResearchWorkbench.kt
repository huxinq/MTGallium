package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherSuites
import org.mtgallium.research.run.ResearchBuildReference
import org.mtgallium.research.run.ResearchPreflightReport
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunProvenance
import org.mtgallium.research.run.researchSha256
import org.mtgallium.research.run.researchSha256File
import org.mtgallium.research.run.verifyResearchBuild

/** Bounded machine interface over the same native authorities used by the research CLI. */
fun main(args: Array<String>) {
    runResearchWorkbench(Path.of("").toAbsolutePath().normalize(), args)?.let { println(it) }
}

private data class WorkbenchCapability(
    val kind: String, val suite: String, val launchGate: String, val serializer: KSerializer<*>,
)

private val workbenchCapabilities = listOf(
    WorkbenchCapability("calibration", "search-teacher-calibration", "bound-preflight", SearchTeacherCalibrationPlan.serializer()),
    WorkbenchCapability("sequential", "search-teacher-sequential", "bound-preflight", SearchTeacherSequentialPlan.serializer()),
    WorkbenchCapability("position-screen", "position-bank-screen", "bound-preflight", PositionBankScreenPlan.serializer()),
    WorkbenchCapability("position-features", "position-bank-screen", "authenticated-inputs", PositionBankScreenPlan.serializer()),
    WorkbenchCapability("position-bank", "real-game-position-bank", "authenticated-inputs", RealGamePositionBankPlan.serializer()),
    WorkbenchCapability("terminal-kernel-study", "terminal-kernel-study", "embedded-pilot", TerminalKernelStudyPlan.serializer()),
    WorkbenchCapability("terminal-target-sensitivity", "terminal-target-sensitivity", "embedded-pilot", TerminalTargetSensitivityPlan.serializer()),
    WorkbenchCapability("direct-attack-kernel-screen", "direct-attack-kernel-screen", "authenticated-inputs", DirectAttackKernelScreenPlan.serializer()),
    WorkbenchCapability("terminal-prediction-diagnostic", "terminal-prediction-diagnostic", "authenticated-inputs", TerminalPredictionDiagnosticPlan.serializer()),
    WorkbenchCapability("research-transfer-audit", "research-transfer-audit", "authenticated-inputs", ResearchTransferAuditPlan.serializer()),
    WorkbenchCapability("campaign-data-snapshot", "campaign-data-snapshot", "authenticated-inputs", CampaignSnapshotPlan.serializer()),
    WorkbenchCapability("campaign-data-use", "campaign-data-use", "native-cli-only", CampaignDataUsePlan.serializer()),
)

private fun workbenchCapability(kind: String): WorkbenchCapability =
    requireNotNull(workbenchCapabilities.singleOrNull { it.kind == kind }) { "Unsupported workbench plan kind: $kind" }

internal fun workbenchCatalog(): JsonElement = buildJsonObject {
    put("suites", buildJsonArray {
        SearchTeacherSuites.all().forEach { suite -> add(buildJsonObject {
            put("id", suite.id)
            workbenchCapabilities.firstOrNull { it.suite == suite.id }?.let {
                put("planKind", it.kind); put("launchGate", it.launchGate)
            }
        }) }
    })
    put("capabilities", buildJsonArray {
        workbenchCapabilities.forEach { capability -> add(buildJsonObject {
            put("kind", capability.kind); put("suite", capability.suite); put("launchGate", capability.launchGate)
        }) }
    })
    put("planKinds", buildJsonArray { workbenchCapabilities.forEach { add(JsonPrimitive(it.kind)) } })
}

/** Constructor validation only. No source, artifact, population, or scientific readiness is inferred. */
internal fun workbenchPlan(kind: String, bytes: String): JsonElement {
    val effective = effectiveWorkbenchPlan(kind, workbenchCapability(kind).serializer, bytes)
    return buildJsonObject {
        put("kind", kind); put("validation", "structural-only"); put("effectivePlan", effective)
    }
}

private fun <T> effectiveWorkbenchPlan(kind: String, serializer: KSerializer<T>, bytes: String): JsonElement {
    val plan = evidenceJson.decodeFromString(serializer, bytes)
    if (plan is PositionBankScreenPlan) {
        require((kind == "position-features") == (plan.mode == PositionBankScreenMode.FEATURES)) {
            "position-features requires FEATURES; other screen modes belong to position-screen"
        }
    }
    return evidenceJson.encodeToJsonElement(serializer, plan)
}

internal fun workbenchSchema(kind: String): JsonElement =
    workbenchDescriptorSchema(kind, workbenchCapability(kind).serializer.descriptor)

/** Descriptor identity preserves distinct generic element types, even when serial names coincide. */
@OptIn(ExperimentalSerializationApi::class)
internal fun workbenchDescriptorSchema(kind: String, descriptor: SerialDescriptor): JsonElement {
    val references = linkedMapOf<SerialDescriptor, String>()
    val definitions = linkedMapOf<String, JsonElement>()
    fun reference(current: SerialDescriptor): JsonObject {
        val existing = references[current]
        val id = existing ?: current.serialName.let { name ->
            if (name !in definitions) name else "$name#${references.size}"
        }
        if (existing == null) {
            // Register before walking children so recursive types point back to this definition.
            references[current] = id
            definitions[id] = JsonNull
            definitions[id] = buildJsonObject {
                put("serialName", current.serialName); put("kind", current.kind.toString()); put("nullable", current.isNullable)
                if (current.kind == SerialKind.ENUM) {
                    put("enumValues", buildJsonArray {
                        repeat(current.elementsCount) { add(JsonPrimitive(current.getElementName(it))) }
                    })
                } else if (current.elementsCount > 0) {
                    put("fields", buildJsonArray {
                        repeat(current.elementsCount) { index -> add(buildJsonObject {
                            put("name", current.getElementName(index)); put("optional", current.isElementOptional(index))
                            put("type", reference(current.getElementDescriptor(index)))
                        }) }
                    })
                }
            }
        }
        return buildJsonObject { put("ref", id); put("serialName", current.serialName); put("nullable", current.isNullable) }
    }
    val root = reference(descriptor)
    return buildJsonObject {
        put("kind", kind); put("format", "kotlin-serialization-descriptor-v1"); put("validation", "type-shape-only")
        put("interpretation", "Kotlin serialization descriptor guide, not a full JSON Schema or constructor validity proof. " +
            "Optional describes serializer omission handling; effective values and defaults come from typed plan decoding.")
        put("root", root); put("definitions", JsonObject(definitions))
    }
}

internal fun workbenchVerify(directory: Path, expectedIdentity: String? = null): JsonElement {
    val path = directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)
    val bytes = Files.readAllBytes(path)
    ResearchRunArtifacts.loadAndVerify(directory, expectedIdentity)
    val hash = researchSha256(bytes)
    require(researchSha256File(path) == hash) { "Artifact manifest changed during verification" }
    return buildJsonObject {
        put("manifest", evidenceJson.parseToJsonElement(bytes.decodeToString()))
        put("manifestSha256", hash)
    }
}

/** There is intentionally no independent plan, deck, output, or thread override for this path. */
internal fun workbenchLaunchArguments(plan: ResearchPreflightPlan): Array<String> = when (val work = plan.work) {
    is ResearchPreflightWork.Gameplay -> {
        val kind = if (work.sequential) "sequential" else "calibration"
        workbenchPlan(kind, Files.readString(Path.of(work.planPath)))
        workbenchArguments("search-teacher-$kind", Path.of(work.planPath), Path.of(plan.targetOutput),
            Path.of(work.deckManifest), work.threads)
    }
    is ResearchPreflightWork.PositionScreen -> {
        val screen = evidenceJson.decodeFromString<PositionBankScreenPlan>(Files.readString(Path.of(work.planPath)))
        require(screen.mode in setOf(PositionBankScreenMode.SEARCH, PositionBankScreenMode.ACTION_CONDITIONAL)) {
            "Bound preflight launch supports SEARCH and ACTION_CONDITIONAL position screens"
        }
        workbenchArguments("position-bank-screen", Path.of(work.planPath), Path.of(plan.targetOutput),
            Path.of(work.deckManifest), work.threads)
    }
    is ResearchPreflightWork.Learning -> error("Historical decision-local learning has no workbench launch adapter")
}

internal fun requireWorkbenchBoundProfile(profileBytes: ByteArray, report: ResearchPreflightReport) {
    require(report.bindings.material["profile"] == researchSha256(profileBytes)) {
        "Preflight verification did not bind the profile used to derive launch arguments"
    }
}

internal fun workbenchExecuteArguments(
    kind: String, plan: Path, output: Path, deck: Path, threads: Int,
): Array<String> {
    val capability = workbenchCapabilities.singleOrNull {
        it.kind == kind && it.launchGate in setOf("embedded-pilot", "authenticated-inputs")
    } ?: error("Unsupported workbench execute kind: $kind; rehearsal workloads require bound launch")
    val bytes = Files.readString(plan)
    workbenchPlan(kind, bytes)
    return workbenchArguments(capability.suite, plan, output, deck, threads)
}

private fun workbenchArguments(suite: String, plan: Path, output: Path, deck: Path, threads: Int): Array<String> {
    require(threads > 0)
    return arrayOf("--suite", suite, "--profile", plan.toString(), "--output", output.toString(),
        "--deck-manifest", deck.toString(), "--threads", threads.toString())
}

internal fun runResearchWorkbench(root: Path, args: Array<String>): JsonElement? {
    fun arity(size: Int, usage: String) = require(args.size == size) { "Expected $usage" }
    fun path(index: Int): Path = Path.of(args[index]).toAbsolutePath().normalize()
    fun verifyBuild(index: Int) = verifyResearchBuild(
        evidenceJson.decodeFromString<ResearchBuildReference>(Files.readString(path(index))),
        ResearchRunProvenance.capture(root),
    )
    return when (args.firstOrNull()) {
        "catalog" -> { arity(1, "catalog"); workbenchCatalog() }
        "schema" -> { arity(2, "schema KIND"); workbenchSchema(args[1]) }
        "plan" -> { arity(3, "plan KIND PATH"); workbenchPlan(args[1], Files.readString(path(2))) }
        "verify" -> {
            require(args.size in 2..3) { "Expected verify DIRECTORY [EXPECTED_ID]" }
            workbenchVerify(path(1), args.getOrNull(2))
        }
        "build-verify" -> { arity(2, "build-verify BUILD_REFERENCE_JSON"); evidenceJson.encodeToJsonElement(verifyBuild(1)) }
        "preflight" -> {
            arity(4, "preflight PROFILE DIRECTORY BUILD_REFERENCE_JSON")
            verifyBuild(3)
            evidenceJson.encodeToJsonElement(ResearchPreflightRunner(root).run(path(1), path(2)))
        }
        "launch" -> {
            arity(4, "launch PREFLIGHT_PROFILE PREFLIGHT_DIRECTORY BUILD_REFERENCE_JSON")
            val bytes = Files.readAllBytes(path(1))
            val plan = evidenceJson.decodeFromString<ResearchPreflightPlan>(bytes.decodeToString())
            val launchArgs = workbenchLaunchArguments(plan)
            verifyBuild(3)
            val report = ResearchPreflightRunner(root).run(path(1), path(2), verifyOnly = true)
            requireWorkbenchBoundProfile(bytes, report)
            runSearchTeacher(root, launchArgs)
            null
        }
        "execute" -> {
            arity(7, "execute KIND PLAN OUTPUT DECK THREADS BUILD_REFERENCE_JSON")
            val launchArgs = workbenchExecuteArguments(args[1], path(2), path(3), path(4), args[5].toInt())
            verifyBuild(6)
            runSearchTeacher(root, launchArgs)
            null
        }
        else -> error("Expected catalog, schema, plan, verify, build-verify, preflight, launch, or execute")
    }
}
