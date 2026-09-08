package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.mtgallium.agent.searchteacher.MonoRedVisibleEvaluatorConfig
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherSuites
import org.mtgallium.research.run.*

@Tag("public-source")
class ResearchWorkbenchTest {
    @TempDir lateinit var directory: Path

    private val policy = SearchTeacherCalibrationPolicy("control", 8, 64, 32, 1.4, true, 1.0)
    private val calibration = SearchTeacherCalibrationPlan(phase = SearchTeacherCalibrationPhase.DEVELOPMENT,
        baseSeed = 17, pairOffset = 3, pairCount = 12, control = policy, candidates = listOf(policy.copy(id = "candidate")))
    private val sequential = SearchTeacherSequentialPlan(calibration, PairedSequentialRule(
        nullPointRate = .5, targetPointRate = .5, falsePositiveRate = .025, falseNegativeRate = .025, maximumPairs = 12))
    private val screen = PositionBankScreenPlan(bankDirectory = "/synthetic/bank", expectedBankIdentity = "bank",
        partition = PositionBankScreenPartition.DEVELOPMENT, mode = PositionBankScreenMode.SEARCH,
        rootLimit = 1, repetitions = 1, policies = listOf(PositionBankScreenPolicy(policy, MonoRedVisibleEvaluatorConfig())))
    private val build = ResearchBuildReference("/synthetic/build", "build", "c".repeat(64))
    private val fit = RootKernelFitReference("/synthetic/fit", "research-run-v1-sha256:" + "a".repeat(64), "b".repeat(64))

    @Test fun `catalog preserves the native suite inventory and distinguishes execution gates`() {
        val catalog = requireNotNull(runResearchWorkbench(directory, arrayOf("catalog"))).jsonObject
        assertEquals(SearchTeacherSuites.all().map { it.id }, catalog.getValue("suites").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val capabilities = catalog.getValue("capabilities").jsonArray.associate { it.jsonObject.getValue("kind").jsonPrimitive.content to it.jsonObject }
        assertEquals("bound-preflight", capabilities.getValue("position-screen").getValue("launchGate").jsonPrimitive.content)
        assertEquals("embedded-pilot", capabilities.getValue("terminal-kernel-study").getValue("launchGate").jsonPrimitive.content)
        assertEquals("authenticated-inputs", capabilities.getValue("factual-residual-study").getValue("launchGate").jsonPrimitive.content)
        assertEquals("native-cli-only", capabilities.getValue("campaign-data-use").getValue("launchGate").jsonPrimitive.content)
    }

    @Test fun `factual residual native route requires plan output and deck`() {
        assertFails { SearchTeacherCli.parse(arrayOf("--suite", "factual-residual-study")) }
        assertFails { SearchTeacherCli.parse(arrayOf("--suite", "factual-residual-study", "--profile", "plan.json", "--output", "output")) }
        val options = SearchTeacherCli.parse(arrayOf("--suite", "factual-residual-study", "--profile", "plan.json", "--output", "output", "--deck-manifest", "deck.json"))
        assertEquals("factual-residual-study", options.suite)
        assertEquals(Path.of("plan.json").toAbsolutePath().normalize(), options.profilePath)
        assertEquals(Path.of("deck.json").toAbsolutePath().normalize(), options.deckManifest)
        assertEquals(Path.of("output").toAbsolutePath().normalize(), options.outputPath)
    }

    @Test fun `typed plans use native constructors and serialization without reading synthetic evidence`() {
        val targets = screen.copy(mode = PositionBankScreenMode.TERMINAL_CONTINUATIONS, repetitions = 2,
            rootIds = listOf("d"), terminalContinuation = TerminalRootContinuationConfig(8, maximumTotalContinuations = 1000))
        val validation = targets.copy(partition = PositionBankScreenPartition.VALIDATION, rootIds = listOf("v"))
        val control = validation.copy(mode = PositionBankScreenMode.ROOT_ROLLOUT_SELECTION, repetitions = 1, terminalContinuation = null)
        val bank = CloningComparisonInput(screen.bankDirectory, screen.expectedBankIdentity)
        val retained = SavedRootPolicyInput("/synthetic/targets", "targets", policy.id)
        val study = TerminalKernelStudyPlan(campaignId = "synthetic", campaignDirectory = "/synthetic/campaign",
            build = build, baseline = fit, development = TerminalStudyData(targets), validation = TerminalStudyData(validation),
            control = control, gate = TerminalStudyGate(1))
        val sensitivity = TerminalTargetSensitivityPlan(build = build, campaignId = "synthetic", campaignDirectory = "/synthetic/campaign",
            bank = bank, baselineTargets = retained, productionControl = bank, baselineModel = fit, candidateModel = fit,
            rootIds = listOf("v"), samplePrefixes = listOf(8), variants = listOf(TerminalTargetVariant("declared", validation)), maximumNewContinuations = 1000)
        val attack = DirectAttackKernelScreenPlan(build, bank, (1..32).map { "root-%02d".format(it) }, fit,
            SearchTeacherCalibrationPolicy("attack-control", 8, 56, 16, 1.4, true, 1.0,
                evaluator = MonoRedVisibleEvaluatorConfig(), fastRootKernelRolloutFit = fit, fastOpponentKernelRolloutFit = fit),
            "selection", "targets")
        val plans = listOf(
            "calibration" to evidenceJson.encodeToJsonElement(calibration),
            "sequential" to evidenceJson.encodeToJsonElement(sequential),
            "position-screen" to evidenceJson.encodeToJsonElement(screen),
            "position-bank" to evidenceJson.encodeToJsonElement(RealGamePositionBankPlan(
                sources = listOf(RealGamePositionBankSource("/synthetic/gameplay", "gameplay")), rootLimit = 1,
                maxRootsPerGame = 1, validationFraction = .25, selectionSeed = 19)),
            "terminal-kernel-study" to evidenceJson.encodeToJsonElement(study),
            "terminal-target-sensitivity" to evidenceJson.encodeToJsonElement(sensitivity),
            "direct-attack-kernel-screen" to evidenceJson.encodeToJsonElement(attack),
            "terminal-prediction-diagnostic" to evidenceJson.encodeToJsonElement(TerminalPredictionDiagnosticPlan(bank, retained, fit)),
        )
        for ((kind, expected) in plans) {
            val result = workbenchPlan(kind, expected.toString()).jsonObject
            assertEquals("structural-only", result.getValue("validation").jsonPrimitive.content)
            assertEquals(expected, result.getValue("effectivePlan"), kind)
        }
        val withoutDefault = JsonObject(plans.first().second.jsonObject - "schemaVersion")
        assertEquals(plans.first().second, workbenchPlan("calibration", withoutDefault.toString()).jsonObject.getValue("effectivePlan"))
    }

    @Test fun `plan inspection refuses unknown fields invalid schedules and unknown kinds`() {
        val plan = evidenceJson.encodeToJsonElement(calibration).jsonObject
        assertFails { workbenchPlan("calibration", JsonObject(plan + ("pairCount" to JsonPrimitive(0))).toString()) }
        assertFails { workbenchPlan("calibration", JsonObject(plan + ("unknownField" to JsonPrimitive(true))).toString()) }
        val sequentialJson = evidenceJson.encodeToJsonElement(sequential).jsonObject
        val wrongRule = JsonObject(sequentialJson.getValue("rule").jsonObject + ("maximumPairs" to JsonPrimitive(11)))
        assertFails { workbenchPlan("sequential", JsonObject(sequentialJson + ("rule" to wrongRule)).toString()) }
        assertFails { workbenchPlan("historical-learning", "{}") }
        assertFails { workbenchPlan("position-features", evidenceJson.encodeToString(screen)) }
        assertFails { workbenchPlan("position-screen", evidenceJson.encodeToString(screen.copy(mode = PositionBankScreenMode.FEATURES))) }
    }

    @Test fun `schema describes required sequential fields and keeps optional nullable and enum distinctions`() {
        val schema = requireNotNull(runResearchWorkbench(directory, arrayOf("schema", "sequential"))).jsonObject
        assertEquals("type-shape-only", schema.getValue("validation").jsonPrimitive.content)
        val definitions = schema.getValue("definitions").jsonObject
        fun definition(reference: JsonElement) = definitions.getValue(reference.jsonObject.getValue("ref").jsonPrimitive.content).jsonObject
        fun fields(type: JsonObject) = type.getValue("fields").jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject }
        val sequentialFields = fields(definition(schema.getValue("root")))
        assertEquals(setOf("calibration", "rule"), sequentialFields.keys)
        assertFalse(sequentialFields.getValue("calibration").getValue("optional").jsonPrimitive.boolean)
        val calibrationFields = fields(definition(sequentialFields.getValue("calibration").getValue("type")))
        assertTrue(calibrationFields.getValue("schemaVersion").getValue("optional").jsonPrimitive.boolean)
        assertFalse(calibrationFields.getValue("schemaVersion").getValue("type").jsonObject.getValue("nullable").jsonPrimitive.boolean)
        val phase = definition(calibrationFields.getValue("phase").getValue("type"))
        assertEquals("ENUM", phase.getValue("kind").jsonPrimitive.content)
        assertEquals(SearchTeacherCalibrationPhase.entries.map { it.name }, phase.getValue("enumValues").jsonArray.map { it.jsonPrimitive.content })
        val evaluator = fields(definition(calibrationFields.getValue("control").getValue("type"))).getValue("evaluator")
        assertTrue(evaluator.getValue("optional").jsonPrimitive.boolean)
        assertTrue(evaluator.getValue("type").jsonObject.getValue("nullable").jsonPrimitive.boolean)
        assertFails { workbenchSchema("historical-learning") }
    }

    @Serializable
    private data class RecursiveSchemaNode(
        val child: RecursiveSchemaNode? = null,
        val labels: List<String> = emptyList(),
        val counts: List<Int> = emptyList(),
    )

    @Test fun `schema terminates recursive references and distinguishes generic element types`() {
        val schema = workbenchDescriptorSchema("synthetic", RecursiveSchemaNode.serializer().descriptor).jsonObject
        val definitions = schema.getValue("definitions").jsonObject
        fun reference(field: JsonObject) = field.getValue("type").jsonObject.getValue("ref").jsonPrimitive.content
        fun fields(id: String) = definitions.getValue(id).jsonObject.getValue("fields").jsonArray
            .associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject }
        val rootFields = fields(schema.getValue("root").jsonObject.getValue("ref").jsonPrimitive.content)
        val child = reference(rootFields.getValue("child"))
        assertEquals(child, reference(fields(child).getValue("child")))
        assertNotEquals(reference(rootFields.getValue("labels")), reference(rootFields.getValue("counts")))
        assertTrue(definitions.size < 12)
        assertTrue(definitions.values.none { it == JsonNull })
    }

    @Test fun `launch derives the exact gameplay inputs and refuses argument overrides`() {
        for (isSequential in listOf(false, true)) {
            val path = write("gameplay.json", if (isSequential) evidenceJson.encodeToString(sequential) else evidenceJson.encodeToString(calibration))
            val work = ResearchPreflightWork.Gameplay(path.toString(), isSequential, directory.resolve("deck.json").toString(), 7, 23)
            val profile = ResearchPreflightPlan(targetOutput = directory.resolve("primary").toString(), work = work)
            val actual = SearchTeacherCli.parse(workbenchLaunchArguments(profile))
            assertEquals(if (isSequential) "search-teacher-sequential" else "search-teacher-calibration", actual.suite)
            assertEquals(path, actual.profilePath)
            assertEquals(Path.of(work.deckManifest), actual.deckManifest)
            assertEquals(Path.of(profile.targetOutput), actual.outputPath)
            assertEquals(work.threads, actual.threads)
        }
        assertFails { runResearchWorkbench(directory, arrayOf("launch", "profile", "preflight", "build", "--threads", "99")) }
        val learning = ResearchPreflightPlan(targetOutput = directory.resolve("learning").toString(),
            work = ResearchPreflightWork.Learning("/synthetic/parent", "/synthetic/precision", PreflightLearner.LINEAR))
        assertFails { workbenchLaunchArguments(learning) }
    }

    @Test fun `position launch permits only the rehearsed search modes and binds every supplied path`() {
        val path = directory.resolve("screen.json")
        val work = ResearchPreflightWork.PositionScreen(path.toString(), directory.resolve("deck.json").toString(), 3)
        val profile = ResearchPreflightPlan(targetOutput = directory.resolve("screen-output").toString(), work = work)
        for (mode in listOf(PositionBankScreenMode.SEARCH, PositionBankScreenMode.ACTION_CONDITIONAL)) {
            Files.writeString(path, evidenceJson.encodeToString(screen.copy(mode = mode)))
            val actual = SearchTeacherCli.parse(workbenchLaunchArguments(profile))
            assertEquals("position-bank-screen", actual.suite)
            assertEquals(path, actual.profilePath)
            assertEquals(Path.of(work.deckManifest), actual.deckManifest)
            assertEquals(Path.of(profile.targetOutput), actual.outputPath)
            assertEquals(work.threads, actual.threads)
        }
        for (mode in listOf(PositionBankScreenMode.FEATURES, PositionBankScreenMode.ROOT_ROLLOUT_SELECTION)) {
            Files.writeString(path, evidenceJson.encodeToString(screen.copy(mode = mode)))
            assertFails { workbenchLaunchArguments(profile) }
        }
    }

    @Test fun `execute cannot route a search plan through features or invoke a historical suite`() {
        val path = write("features.json", evidenceJson.encodeToString(screen.copy(mode = PositionBankScreenMode.FEATURES)))
        fun arguments(kind: String) = workbenchExecuteArguments(kind, path, directory.resolve("output"), directory.resolve("deck.json"), 2)
        assertEquals("position-bank-screen", SearchTeacherCli.parse(arguments("position-features")).suite)
        Files.writeString(path, evidenceJson.encodeToString(screen))
        assertFails { arguments("position-features") }
        assertFails { arguments("position-screen") }
        assertFails { arguments("search-teacher-calibration") }
        assertFails { arguments("decision-local-learnability-pilot") }
        assertFails { arguments("campaign-data-use") }
    }

    @Test fun `a changed profile cannot borrow a pass verified for different launch arguments`() {
        val bytes = "synthetic original profile".toByteArray()
        val report = ResearchPreflightReport(bindings = ResearchRunBindings(protocol = "research-preflight-v1",
            material = mapOf("profile" to researchSha256(bytes))),
            checks = listOf(ResearchPreflightCheck("synthetic", true, "passed")), workload = emptyMap())
        requireWorkbenchBoundProfile(bytes, report)
        assertFails { requireWorkbenchBoundProfile("changed target or threads".toByteArray(), report) }
    }

    @Test fun `artifact inspection preserves historical identity and refuses corruption`() {
        val path = write("report.json", "synthetic historical report")
        ResearchRunArtifacts(directory, "historical-source-identity").apply { register("report.json"); finalize() }
        val result = workbenchVerify(directory, "historical-source-identity").jsonObject
        assertEquals("historical-source-identity", result.getValue("manifest").jsonObject.getValue("researchRunIdentity").jsonPrimitive.content)
        assertEquals(researchSha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)), result.getValue("manifestSha256").jsonPrimitive.content)
        assertFails { workbenchVerify(directory, "current-source-identity") }
        Files.writeString(path, "altered historical report")
        assertFails { workbenchVerify(directory) }
        Files.writeString(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE), "corrupt manifest")
        assertFails { workbenchVerify(directory) }
    }

    @Test fun `artifact inspection transports the exact retained fields when defaults were omitted`() {
        write("report.json", "synthetic report")
        ResearchRunArtifacts(directory, "retained-identity").apply { register("report.json"); finalize() }
        val manifest = directory.resolve(ResearchRunArtifacts.MANIFEST_FILE)
        val original = JsonObject(evidenceJson.parseToJsonElement(Files.readString(manifest)).jsonObject - "schemaVersion" - "state")
        Files.writeString(manifest, original.toString())
        val inspected = workbenchVerify(directory).jsonObject
        assertEquals(original, inspected.getValue("manifest"))
        assertEquals(researchSha256File(manifest), inspected.getValue("manifestSha256").jsonPrimitive.content)
    }

    private fun write(name: String, bytes: String): Path = directory.resolve(name).also { Files.writeString(it, bytes) }
}
