package org.mtgallium.evaluation.searchteacher

import java.lang.management.ManagementFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.searchteacher.SearchTeacherRuntimeConfig
import org.mtgallium.agent.searchteacher.PolicySingletonSelectionConfig
import org.mtgallium.research.run.PrivateEvidencePaths
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunProvenance
import org.mtgallium.research.run.researchSha256File

/** Records the ordinary arena lifecycle, optionally comparing explicit singleton selection. */
internal object SearchCountDiagnostic {
    // Use the owning research hash implementation for the ordinary, non-overlaid runtime inputs.
    private fun runtimeFiles(): Map<String, String> = buildMap {
        System.getProperty("java.class.path").split(File.pathSeparator).forEach { entry ->
            val declared = Path.of(entry).toAbsolutePath().normalize()
            if (!Files.exists(declared)) {
                // Gradle includes output directories for source sets with no Java/resources.
                // Retain their absence so creating one during the run still fails the guard.
                put(declared.toString(), "ABSENT")
            } else if (Files.isDirectory(declared)) {
                val path = declared.toRealPath()
                Files.walk(path).use { files ->
                    files.filter(Files::isRegularFile).sorted().forEach { file ->
                        put(file.toString(), researchSha256File(file))
                    }
                }
            } else {
                val path = declared.toRealPath()
                put(path.toString(), researchSha256File(path))
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 4..5) {
            "repository-root output-relative-path base-seed limit [search|singleton]; " +
                "limit counts searches without a mode, accepted decisions with a mode"
        }
        val repository = Path.of(args[0]).toAbsolutePath().normalize()
        require(Path.of("").toRealPath() == repository.toRealPath()) { "Launch from the recorded repository" }
        val output = PrivateEvidencePaths.resolve(repository, args[1])
        require(!Files.exists(output)) { "Output already exists: $output" }
        val seed = args[2].toLong()
        val comparisonMode = args.getOrNull(4)
        require(comparisonMode == null || comparisonMode in setOf("search", "singleton"))
        val limit = args[3].toInt()
        require(limit in 1..if (comparisonMode == null) 64 else 256)
        val maximumSearchDecisions = limit.takeIf { comparisonMode == null }
        val maximumAcceptedDecisions = limit.takeIf { comparisonMode != null }
            ?: SearchTeacherArena.MAX_GAME_DECISIONS
        val protocol = if (comparisonMode == null) "mtgallium-search-count-shadow-v1"
            else "mtgallium-singleton-selection-comparison-v1"
        val thread = (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).also {
            require(it.isCurrentThreadCpuTimeSupported && it.isThreadAllocatedMemorySupported)
            it.isThreadCpuTimeEnabled = true
            it.isThreadAllocatedMemoryEnabled = true
        }
        val provenance = ResearchRunProvenance.capture(repository).also {
            it.requireReady()
            require(!it.outerDirty && !it.engineDirty) { "Commit source before recording diagnostic evidence" }
        }
        val source = currentSourceProvenance()
        require(source == provenance.sourceProvenance)
        Files.createDirectories(output)
        fun write(name: String, text: String) = Files.writeString(output.resolve(name), text + "\n", CREATE_NEW)
        write("provenance.json", evidenceJson.encodeToString(provenance))

        val cards = linkedMapOf("Mountain" to 24)
        listOf("Shock", "Lightning Strike", "Hired Claw", "Hexing Squelcher", "Burnout Bashtronaut",
            "Magebane Lizard", "Razorkin Needlehead", "Nova Hellkite", "Sunspine Lynx").forEach {
            cards[it] = 4
        }
        val deck = SearchTeacherDeckManifest(
            "search-count-synthetic-v1", "Search count fixture", "synthetic", "2026-09-05",
            "Public synthetic mono-red fixture; not a representative deck population", cards, emptyMap(),
        )
        write("deck.json", evidenceJson.encodeToString(deck))
        val config = SearchTeacherRuntimeConfig(
            baseSeed = seed,
            singletonSelection = PolicySingletonSelectionConfig(enabled = comparisonMode == "singleton"),
        )
        val parameters = config.policyParameters()
        // The arena requires a presentation profile even when the acting policy supplies its
        // complete current runtime parameters. These zeroes are not calibration measurements.
        val presentation = FrozenSearchProfile(
            id = "fast-arena-v1", generatedAtUtc = "UNFROZEN-DIAGNOSTIC",
            outerCommit = provenance.outerCommit, argentumCommit = provenance.checkedOutEngineCommit,
            host = ManagementFactory.getOperatingSystemMXBean().name,
            particles = config.particles, simulations = config.simulations, leaf = config.leaf,
            actionSpaceProfile = config.actionSpaceProfile, maxPolicyDecisions = config.maxPolicyDecisions,
            measuredP95Millis = 0.0, tacticalScore = 0.0, standardError = 0.0,
            calibrationReportHash = "UNFROZEN-DIAGNOSTIC",
        )
        val registry = buildRegistry()
        val arena = SearchTeacherArena(
            registry, deck, presentation, seed, gameDecisionLimit = maximumAcceptedDecisions,
        )
        val search = ArenaPolicySpec("search-count-current-runtime", ArenaPolicyKind.SEARCH, parameters = parameters)
        val opponent = ArenaPolicySpec("search-count-argentum-heuristic", ArenaPolicyKind.HEURISTIC)
        val searchBinding = arena.evidenceBinding(search, maximumSearchDecisions, source)
        val opponentBinding = arena.evidenceBinding(opponent, maximumSearchDecisions, source)
        write("search-behavior.json", PolicyJson.format.encodeToString(searchBinding))
        write("opponent-behavior.json", PolicyJson.format.encodeToString(opponentBinding))
        write("protocol.json", buildJsonObject {
            put("protocol", protocol)
            put("baseSeed", seed)
            put("gameCount", 2)
            maximumSearchDecisions?.let { put("maximumSearchDecisionsPerGame", it) }
            put("maximumAcceptedDecisionsPerGame", maximumAcceptedDecisions)
            comparisonMode?.let { put("selectionMode", it) }
            put("schedule", "p0 searches at baseSeed; p1 searches at baseSeed+1; other seat uses heuristic")
            put("warmupGames", 0)
            put("searchLatencyScope", "Arena policy selection after root expansion, including belief synchronization")
            put("gameElapsedScope", "Arena setup, both policies, comparator, evidence writing, transitions and belief updates")
            put("cpuAllocationScope", "Calling thread around arena invocation, including private parity probes in comparison mode")
            put("comparator", "Existing arena determinized Argentum heuristic with explicitly counted semantic fallback")
            put("interpretation", if (comparisonMode == null)
                "Action agreement and retrospective search cost only; no gate, strength or realized speedup"
            else "Compare matched accepted traces across modes; elapsed includes comparator and evidence costs; no strength result")
        }.toString())
        write("runtime.txt", System.getProperty("java.runtime.version") + "\n" +
            ManagementFactory.getRuntimeMXBean().inputArguments + "\n" +
            "classpath=" + System.getProperty("java.class.path") + "\n" +
            "systemLoadAverage=" + ManagementFactory.getOperatingSystemMXBean().systemLoadAverage)
        val runtimeBefore = runtimeFiles()
        write("runtime-files.json", evidenceJson.encodeToString(runtimeBefore))
        Files.copy(
            repository.resolve("evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/SearchCountDiagnostic.kt"),
            output.resolve("SearchCountDiagnostic.kt"),
        )
        val inputs = listOf("provenance.json", "deck.json", "search-behavior.json", "opponent-behavior.json",
            "protocol.json", "runtime.txt", "runtime-files.json", "SearchCountDiagnostic.kt")
        val bindings = ResearchRunBindings(
            protocol = protocol,
            material = inputs.associate { it.lowercase().replace('.', '-') to researchSha256File(output.resolve(it)) },
        )
        write("bindings.json", evidenceJson.encodeToString(bindings))
        val artifacts = ResearchRunArtifacts(output, bindings.identity)
        (inputs + "bindings.json").forEach(artifacts::register)
        repeat(2) { index ->
            val searchSeat = "p$index"
            val gameSeed = seed + index
            val gameId = UUID.nameUUIDFromBytes("search-count-shadow-v1:$gameSeed:$searchSeat".toByteArray()).toString()
            val trajectory = "$index.trajectory.jsonl.gz"
            val trace = mutableListOf<JsonObject>()
            fun snapshot(world: ArgentumSearchWorld, actor: String, decision: Int, stage: String) = buildJsonObject {
                put("stage", stage)
                put("decisionIndex", decision)
                put("actor", actor)
                put("authoritativeFingerprint", world.freshAuthoritativeFingerprintForHost())
                put("p0InformationDigest", world.informationState("p0").informationStateDigest)
                put("p1InformationDigest", world.informationState("p1").informationStateDigest)
            }
            val cpuBefore = thread.currentThreadCpuTime
            val bytesBefore = thread.getThreadAllocatedBytes(Thread.currentThread().threadId())
            val result = arena.playWithPolicies(
                gameId = gameId, gameSeed = gameSeed,
                p0Policy = if (index == 0) search else opponent,
                p1Policy = if (index == 1) search else opponent,
                maxSearchDecisions = maximumSearchDecisions,
                rootProbe = if (comparisonMode == null) null else { world, actor, decision ->
                    trace += snapshot(world, actor, decision, "before")
                },
                acceptedStepProbe = if (comparisonMode == null) null else { world, actor, decision, choice, step ->
                    trace += JsonObject(snapshot(world, actor, decision, "accepted") + buildJsonObject {
                        put("choice", PolicyJson.format.encodeToJsonElement(choice))
                        put("accepted", step.accepted)
                        put("privateToActor", step.privateToActor)
                        put("forcedTransitions", PolicyJson.format.encodeToJsonElement(step.forcedTransitions))
                    })
                },
                evidence = GameEvidenceOptions(
                    publicTrajectory = output.resolve(trajectory), publicTrajectoryPerspective = searchSeat,
                    outerCommit = provenance.outerCommit, argentumCommit = provenance.checkedOutEngineCommit,
                    profileHash = searchBinding.identity, sourceProvenance = source,
                ),
            )
            val cpuNanos = thread.currentThreadCpuTime - cpuBefore
            val allocatedBytes = thread.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytesBefore
            write("$index.game.json", evidenceJson.encodeToString(result))
            artifacts.register("$index.game.json")
            if (comparisonMode != null) {
                write("$index.timing.json", buildJsonObject {
                    put("arenaThreadCpuNanos", cpuNanos)
                    put("arenaThreadAllocatedBytes", allocatedBytes)
                }.toString())
                artifacts.register("$index.timing.json")
                write("$index.accepted-trace.json", evidenceJson.encodeToString(trace))
                artifacts.register("$index.accepted-trace.json")
            }
            // A typed failure before the writer opens is still a recorded attempt, not a missing game.
            if (Files.exists(output.resolve(trajectory))) artifacts.register(trajectory)
            println("game=$index disposition=${result.disposition} decisions=${result.decisions} " +
                "searches=${result.searchLatenciesMillis.size} elapsedMs=${result.elapsedMillis}")
        }
        check(ResearchRunProvenance.capture(repository) == provenance) { "Source changed during the diagnostic" }
        check(runtimeFiles() == runtimeBefore) { "Runtime inputs changed during the diagnostic" }
        artifacts.finalize()
        val verified = ResearchRunArtifacts.loadAndVerify(output, bindings.identity)
        println("Verified ${verified.researchRunIdentity} artifacts=${verified.artifacts.size}")
    }
}
