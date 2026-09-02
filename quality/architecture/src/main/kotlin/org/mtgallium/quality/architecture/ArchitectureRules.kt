package org.mtgallium.quality.architecture

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

data class ArchitectureViolation(
    val code: String,
    val subject: String,
    val problem: String,
    val remedy: String,
) {
    override fun toString(): String = "[$code] $subject: $problem Remedy: $remedy"
}

/**
 * Repository-owned architecture policy.
 *
 * The checks deliberately use the working tree for source conformance and the Git
 * index only for the generated-artifact rule. This means a newly created source
 * file is checked before it is staged, while ignored run output does not look like
 * a second normative implementation.
 */
class ArchitectureRules(
    private val root: Path,
    private val trackedRelativeFiles: List<Path>? = null,
) {
    // Focused fixtures model the private policy regardless of the process
    // environment; only an outer repository check may opt into public mode.
    private val publicSourceMode =
        trackedRelativeFiles == null && System.getenv("MTGALLIUM_PUBLIC_SOURCE") == "1"

    fun evaluate(): List<ArchitectureViolation> = buildList {
        addAll(projectLayoutViolations())
        addAll(projectDependencyViolations())
        addAll(packageOwnershipViolations())
        addAll(sourceBoundaryViolations())
        addAll(coreConcreteEvaluatorViolations())
        addAll(searchCompositionViolations())
        // The canonical deck is deliberately absent from a public-source
        // candidate until its provenance is reviewed. Private checkouts retain
        // the stronger exact-fixture invariant.
        if (!publicSourceMode) addAll(frozenDeckViolations())
        addAll(trackedArtifactViolations())
        addAll(artifactPathViolations())
        addAll(retiredSymbolViolations())
    }.sortedWith(compareBy(ArchitectureViolation::code, ArchitectureViolation::subject, ArchitectureViolation::problem))

    private fun projectLayoutViolations(): List<ArchitectureViolation> {
        val settings = root.resolve("settings.gradle.kts")
        if (!settings.isRegularFile()) {
            return listOf(
                violation(
                    "ARCH-PROJECT-SETTINGS-MISSING",
                    display(settings),
                    "the outer build has no settings.gradle.kts",
                    "run the verifier from the MTGallium outer repository root and restore the build settings",
                )
            )
        }

        val actual = parseIncludedProjects(settings.readText())
        val expected = ArchitecturePolicy.directDependencies.keys
        return buildList {
            (expected - actual).sorted().forEach { project ->
                add(
                    violation(
                        "ARCH-PROJECT-MODULE-MISSING",
                        project,
                        "the capability is absent from settings.gradle.kts",
                        "restore include(\"$project\") or update ArchitecturePolicy with the architectural decision",
                    )
                )
            }
            (actual - expected).sorted().forEach { project ->
                add(
                    violation(
                        "ARCH-PROJECT-MODULE-UNEXPECTED",
                        project,
                        "an outer Gradle module has no declared architectural ownership",
                        "place the capability in an existing module, or document the boundary and add it to ArchitecturePolicy",
                    )
                )
            }
        }
    }

    private fun projectDependencyViolations(): List<ArchitectureViolation> {
        val buildFileViolations = mutableListOf<ArchitectureViolation>()
        val edgeCounts = linkedMapOf<Pair<String, String>, Int>()
        val graph = linkedMapOf<String, Set<String>>()
        val modules = parseIncludedProjects(
            root.resolve("settings.gradle.kts").takeIf(Files::isRegularFile)?.readText().orEmpty()
        ) + ArchitecturePolicy.directDependencies.keys

        modules.sorted().forEach { project ->
            val buildFile = root.resolve(project.removePrefix(":").replace(':', '/')).resolve("build.gradle.kts")
            if (!buildFile.isRegularFile()) {
                buildFileViolations += violation(
                    "ARCH-PROJECT-BUILD-MISSING",
                    project,
                    "${display(buildFile)} is missing",
                    "restore the module build file or remove the module through an explicit architecture change",
                )
                graph[project] = emptySet()
            } else {
                val dependencies = parseProjectDependencies(buildFile.readText())
                graph[project] = dependencies.toSet()
                dependencies.forEach { dependency ->
                    val edge = project to dependency
                    edgeCounts[edge] = edgeCounts.getOrDefault(edge, 0) + 1
                }
            }
        }

        return buildFileViolations + dependencyGraphViolations(graph, edgeCounts)
    }

    private fun packageOwnershipViolations(): List<ArchitectureViolation> = buildList {
        ArchitecturePolicy.packageOwners.forEach { (project, owner) ->
            val sourceRoot = root.resolve(project.removePrefix(":").replace(':', '/')).resolve("src")
            kotlinFiles(sourceRoot).forEach { source ->
                val declaredPackage = PACKAGE_DIRECTIVE.find(maskKotlinNonCode(source.readText()))
                    ?.groupValues
                    ?.get(1)
                when {
                    declaredPackage == null -> add(
                        violation(
                            "ARCH-PACKAGE-MISSING",
                            display(source),
                            "the Kotlin source has no package declaration",
                            "declare a package beneath $owner, the namespace owned by $project",
                        )
                    )

                    declaredPackage != owner && !declaredPackage.startsWith("$owner.") -> add(
                        violation(
                            "ARCH-PACKAGE-OWNERSHIP",
                            display(source),
                            "package $declaredPackage is outside the namespace owned by $project",
                            "move the source to its owning module or declare it beneath $owner",
                        )
                    )
                }
            }
        }
    }

    private fun sourceBoundaryViolations(): List<ArchitectureViolation> = buildList {
        val coreRoot = root.resolve("agent/infoset-core")
        val coreFiles = kotlinFiles(coreRoot.resolve("src")) + listOf(coreRoot.resolve("build.gradle.kts"))
            .filter(Files::isRegularFile)
        coreFiles.forEach { source ->
            val text = source.readText()
            val code = if (source.extension == "kt") maskKotlinNonCode(text) else stripGradleComments(text)
            val reference = code.indexOf(ENGINE_PACKAGE)
            if (reference >= 0) {
                add(
                    violation(
                        "ARCH-INFOSET-CORE-ENGINE-REFERENCE",
                        location(source, text, reference),
                        "engine-owned package $ENGINE_PACKAGE appears in engine-independent infoset core",
                        "move the adapter or engine type usage to :agent:infoset-argentum",
                    )
                )
            }
        }

        listOf(":agent:infoset-core", ":agent:infoset-argentum", ":agent:search-teacher", ":integration:argentum-search-teacher")
            .flatMap(::projectKotlinFiles)
            .forEach { source ->
                val text = source.readText()
                val code = maskKotlinNonCode(text)
                val reference = code.indexOf(EVALUATION_PACKAGE)
                if (reference >= 0) {
                    add(
                        violation(
                            "ARCH-RUNTIME-DEPENDS-ON-EVALUATION",
                            location(source, text, reference),
                            "runtime or agent code references $EVALUATION_PACKAGE",
                            "invert the dependency: evaluation may call stable agent interfaces, never the reverse",
                        )
                    )
                }
            }

        projectKotlinFiles(":evaluation:argentum")
            .plus(projectKotlinFiles(":evaluation:search-teacher"))
            .forEach { source ->
                val text = source.readText()
                val code = maskKotlinNonCode(text)
                val reference = code.indexOf(INTEGRATION_PACKAGE)
                if (reference >= 0) {
                    add(
                        violation(
                            "ARCH-EVALUATION-INTEGRATION-COUPLING",
                            location(source, text, reference),
                            "evaluation code references $INTEGRATION_PACKAGE",
                            "exercise the agent contract directly; deployment wiring belongs only to integration",
                        )
                    )
                }
            }
    }

    private fun searchCompositionViolations(): List<ArchitectureViolation> = buildList {
        ArchitecturePolicy.searchCompositionConsumers
            .flatMap(::projectMainKotlinFiles)
            .filterNot { source ->
                root.relativize(source.normalize()) == Path.of(ArchitecturePolicy.searchCompositionRootPath)
            }
            .forEach { source ->
                val text = source.readText()
                val code = maskKotlinNonCode(text)
                SEARCH_CONSTRUCTOR.findAll(code).forEach { match ->
                    add(
                        violation(
                            "ARCH-SEARCH-COMPOSITION-BYPASS",
                            location(source, text, match.range.first),
                            "${match.groupValues[1]} is constructed outside the Search Teacher composition root",
                            "request it through SearchTeacherSearchFactory in :agent:search-teacher; tests may construct core explicitly",
                        )
                    )
                }
            }
    }

    private fun coreConcreteEvaluatorViolations(): List<ArchitectureViolation> = buildList {
        val contract = root.resolve(ArchitecturePolicy.leafContractPath)
        if (!contract.isRegularFile()) {
            add(
                violation(
                    "ARCH-CORE-EVALUATOR-CONTRACT-MISSING",
                    ArchitecturePolicy.leafContractPath,
                    "the persisted LeafEvaluator contract is missing",
                    "restore the serialized evaluator-key contract before changing search behavior",
                )
            )
            return@buildList
        }
        val declarations = parseLeafEvaluatorEntries(contract.readText())
        if (declarations.isEmpty()) {
            add(
                violation(
                    "ARCH-CORE-EVALUATOR-CONTRACT-UNREADABLE",
                    ArchitecturePolicy.leafContractPath,
                    "no persisted LeafEvaluator entries could be derived",
                    "keep LeafEvaluator as an explicit enum whose entries precede its member declarations",
                )
            )
            return@buildList
        }
        val keyPattern = declarations.keys.joinToString("|") { Regex.escape(it) }
        val concreteKey = Regex("""\b(?:$keyPattern)\b""")

        projectMainKotlinFiles(":agent:infoset-core").forEach { source ->
            val text = source.readText()
            val code = maskKotlinNonCode(text)
            val declarationRanges = if (source.normalize() == contract.normalize()) {
                declarations.values.toSet()
            } else {
                emptySet()
            }
            val match = concreteKey.findAll(code).firstOrNull { it.range !in declarationRanges }
                ?: return@forEach
            add(
                violation(
                    "ARCH-CORE-CONCRETE-EVALUATOR-BEHAVIOR",
                    location(source, text, match.range.first),
                    "generic search names concrete persisted evaluator key ${match.value}",
                    "inject routing and safety through LeafEvaluationStrategy; only the persisted contract declares keys",
                )
            )
        }
    }

    private fun frozenDeckViolations(): List<ArchitectureViolation> {
        val expected = root.resolve(ArchitecturePolicy.frozenDeckPath).normalize()
        val copies = normativeRepositoryFiles()
            .filter { it.fileName.toString() == expected.fileName.toString() }
            .map(Path::normalize)
        return when {
            copies.isEmpty() -> listOf(
                violation(
                    "ARCH-FROZEN-DECK-MISSING",
                    ArchitecturePolicy.frozenDeckPath,
                    "the canonical frozen deck fixture is missing",
                    "restore the preserved fixture at ${ArchitecturePolicy.frozenDeckPath}",
                )
            )

            copies.size == 1 && copies.single() == expected -> emptyList()
            else -> listOf(
                violation(
                    "ARCH-FROZEN-DECK-DUPLICATED",
                    copies.joinToString { display(it) },
                    "the frozen deck has ${copies.size} normative copies; exactly ${ArchitecturePolicy.frozenDeckPath} is allowed",
                    "make every consumer load the root fixture and remove only redundant copies, not historical evidence",
                )
            )
        }
    }

    private fun trackedArtifactViolations(): List<ArchitectureViolation> {
        val tracked = trackedRelativeFiles?.let { TrackedFiles(it, null) } ?: readTrackedFiles()
        tracked.error?.let { error ->
            return listOf(
                violation(
                    "ARCH-TRACKED-FILES-UNAVAILABLE",
                    display(root),
                    error,
                    "run from a Git checkout with git available; this rule must inspect the index",
                )
            )
        }

        return tracked.paths.mapNotNull { relative ->
            val generatedSegment = relative
                .map(Path::toString)
                .firstOrNull { it.lowercase() in ArchitecturePolicy.generatedDirectoryNames }
                ?: return@mapNotNull null
            violation(
                "ARCH-TRACKED-GENERATED-ARTIFACT",
                slash(relative),
                "a Git-tracked path is beneath generated directory '$generatedSegment'",
                "remove generated output from the index and keep only intentional source or fixture artifacts",
            )
        }
    }

    private fun artifactPathViolations(): List<ArchitectureViolation> = buildList {
        ownedKotlinFiles().forEach { source ->
            val text = source.readText()
            if (root.relativize(source.normalize()) != Path.of(ArchitecturePolicy.artifactStorePath) &&
                EVIDENCE_PATH_LITERAL in text
            ) {
                add(
                    violation(
                        "ARCH-EVIDENCE-PATH-BYPASS",
                        location(source, text, EVIDENCE_PATH_LITERAL),
                        "the repository artifact root is repeated outside ${ArchitecturePolicy.artifactStorePath}",
                        "resolve artifact paths through EvidenceStore and EvidenceLocation so path safety stays centralized",
                    )
                )
            }
        }
    }

    private fun retiredSymbolViolations(): List<ArchitectureViolation> = buildList {
        ownedKotlinFiles().forEach { source ->
            val text = source.readText()
            val code = maskKotlinNonCode(text)
            ArchitecturePolicy.retiredSymbols.forEach { symbol ->
                val match = Regex("""\b${Regex.escape(symbol)}\b""").find(code)
                if (match != null) {
                    add(
                        violation(
                            "ARCH-RETIRED-SYMBOL",
                            location(source, text, match.range.first),
                            "retired implementation symbol $symbol remains",
                            "use the consolidated implementation path and remove the obsolete symbol and callers",
                        )
                    )
                }
            }
        }
    }

    private fun dependencyGraphViolations(
        graph: Map<String, Set<String>>,
        edgeCounts: Map<Pair<String, String>, Int>,
    ): List<ArchitectureViolation> = buildList {
        ArchitecturePolicy.directDependencies.forEach { (project, expected) ->
            val actual = graph[project].orEmpty()
            (expected - actual).sorted().forEach { dependency ->
                add(
                    violation(
                        "ARCH-PROJECT-EDGE-MISSING",
                        "$project -> $dependency",
                        "the required direct dependency is absent",
                        "declare project(\"$dependency\") directly; do not rely on an unrelated module's transitive export",
                    )
                )
            }
            (actual - expected).sorted().forEach { dependency ->
                add(
                    violation(
                        "ARCH-PROJECT-EDGE-UNEXPECTED",
                        "$project -> $dependency",
                        "the direct dependency is outside the allowed capability graph",
                        "remove the edge or record and encode an intentional boundary change in ArchitecturePolicy",
                    )
                )
            }
        }

        edgeCounts.filterValues { it > 1 }.forEach { (edge, count) ->
            add(
                violation(
                    "ARCH-PROJECT-EDGE-DUPLICATED",
                    "${edge.first} -> ${edge.second}",
                    "the same direct project dependency is declared $count times",
                    "retain one declaration in the configuration that represents the real API exposure",
                )
            )
        }

        graph.forEach { (project, dependencies) ->
            val projectLevel = ArchitecturePolicy.dependencyLevel[project] ?: return@forEach
            dependencies.forEach dependencyLoop@{ dependency ->
                val dependencyLevel = ArchitecturePolicy.dependencyLevel[dependency] ?: return@dependencyLoop
                if (dependencyLevel > projectLevel) {
                    add(
                        violation(
                            "ARCH-PROJECT-UPWARD-DEPENDENCY",
                            "$project -> $dependency",
                            "dependency level $projectLevel points upward to level $dependencyLevel",
                            "move the shared contract downward or invert the call through the lower-level capability",
                        )
                    )
                }
            }
        }

        findCycles(graph).forEach { cycle ->
            add(
                violation(
                    "ARCH-PROJECT-CYCLE",
                    cycle.joinToString(" -> "),
                    "the outer project dependency graph is cyclic",
                    "move the shared concept to its lower-level owner and remove one direction of the cycle",
                )
            )
        }
    }

    private fun findCycles(graph: Map<String, Set<String>>): List<List<String>> {
        val complete = mutableSetOf<String>()
        val active = mutableListOf<String>()
        val cycles = linkedMapOf<String, List<String>>()

        fun visit(node: String) {
            val activeIndex = active.indexOf(node)
            if (activeIndex >= 0) {
                val cycle = active.subList(activeIndex, active.size).toList() + node
                val ring = cycle.dropLast(1)
                val start = ring.indices.minByOrNull { ring[it] } ?: 0
                val canonicalRing = ring.drop(start) + ring.take(start)
                val canonical = canonicalRing + canonicalRing.first()
                cycles.putIfAbsent(canonical.joinToString("|"), canonical)
                return
            }
            if (node in complete) return

            active += node
            graph[node].orEmpty().sorted().forEach(::visit)
            active.removeAt(active.lastIndex)
            complete += node
        }

        graph.keys.sorted().forEach(::visit)
        return cycles.values.toList()
    }

    private fun readTrackedFiles(): TrackedFiles = runCatching {
        val process = ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            return TrackedFiles(emptyList(), "git ls-files failed ($exitCode): ${output.toString(StandardCharsets.UTF_8).trim()}")
        }
        val paths = output.toString(StandardCharsets.UTF_8)
            .split('\u0000')
            .filter(String::isNotBlank)
            .map(Path::of)
        TrackedFiles(paths, null)
    }.getOrElse { failure ->
        TrackedFiles(emptyList(), "git ls-files could not run: ${failure.message ?: failure::class.simpleName}")
    }

    private fun ownedKotlinFiles(): List<Path> = ArchitecturePolicy.packageOwners.keys.flatMap(::projectKotlinFiles)

    private fun projectKotlinFiles(project: String): List<Path> = kotlinFiles(
        root.resolve(project.removePrefix(":").replace(':', '/')).resolve("src")
    )

    private fun projectMainKotlinFiles(project: String): List<Path> = kotlinFiles(
        root.resolve(project.removePrefix(":").replace(':', '/')).resolve("src/main")
    )

    private fun kotlinFiles(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.walk(directory).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "kt" }.toList()
        }
    }

    private fun normativeRepositoryFiles(): List<Path> = Files.walk(root).use { paths ->
        paths.filter { path ->
            path.isRegularFile() && root.relativize(path).none { segment ->
                segment.toString().lowercase() in ArchitecturePolicy.nonNormativeDirectoryNames
            }
        }.toList()
    }

    private fun display(path: Path): String = runCatching { slash(root.relativize(path.normalize())) }
        .getOrElse { slash(path) }

    private fun location(path: Path, text: String, token: String): String {
        val index = text.indexOf(token)
        return location(path, text, index)
    }

    private fun location(path: Path, text: String, index: Int): String {
        val line = if (index < 0) 1 else text.take(index).count { it == '\n' } + 1
        return "${display(path)}:$line"
    }

    private fun slash(path: Path): String = path.toString().replace('\\', '/')

    private fun violation(code: String, subject: String, problem: String, remedy: String) =
        ArchitectureViolation(code, subject, problem, remedy)

    private data class TrackedFiles(val paths: List<Path>, val error: String?)

    companion object {
        private val PACKAGE_DIRECTIVE = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
        private val PROJECT_DEPENDENCY = Regex(
            """project\s*\(\s*(?:path\s*=\s*)?[\"'](:[A-Za-z0-9:_-]+)[\"']\s*\)"""
        )
        private val SEARCH_CONSTRUCTOR = Regex("""\b(InformationSetSearch(?:Session)?)\s*\(""")
        private val INCLUDED_PROJECT = Regex("""[\"'](:[A-Za-z0-9:_-]+)[\"']""")
        private const val ENGINE_PACKAGE = "com.wingedsheep"
        private const val EVALUATION_PACKAGE = "org.mtgallium.evaluation"
        private const val INTEGRATION_PACKAGE = "org.mtgallium.integration"
        private val EVIDENCE_PATH_LITERAL = listOf("reports", "search-teacher").joinToString("/")

        fun locateRepositoryRoot(start: Path): Path {
            var candidate = start.toAbsolutePath().normalize()
            while (true) {
                if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                    Files.isDirectory(candidate.resolve("agent"))
                ) {
                    return candidate
                }
                candidate = candidate.parent
                    ?: error("Cannot locate MTGallium repository root from ${start.toAbsolutePath()}")
            }
        }

        internal fun parseIncludedProjects(settings: String): Set<String> = stripGradleComments(settings).lineSequence()
            .filter { it.trimStart().startsWith("include(") }
            .flatMap { line -> INCLUDED_PROJECT.findAll(line).map { it.groupValues[1] } }
            .toSet()

        internal fun parseProjectDependencies(buildFile: String): List<String> =
            PROJECT_DEPENDENCY.findAll(stripGradleComments(buildFile)).map { it.groupValues[1] }.toList()

        internal fun parseLeafEvaluatorEntries(source: String): Map<String, IntRange> {
            val code = maskKotlinNonCode(source)
            val header = Regex("""\benum\s+class\s+LeafEvaluator\b[^\{]*\{""").find(code)
                ?: return emptyMap()
            val openBrace = code.indexOf('{', header.range.first)
            var depth = 0
            var closeBrace = -1
            for (index in openBrace until code.length) {
                when (code[index]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            closeBrace = index
                            break
                        }
                    }
                }
            }
            if (closeBrace < 0) return emptyMap()
            val semicolon = code.indexOf(';', openBrace + 1)
                .takeIf { it in (openBrace + 1)..<closeBrace }
            val entriesEnd = semicolon ?: closeBrace
            val entries = code.substring(openBrace + 1, entriesEnd)
            val entry = Regex("""(?m)^[ \t]*([A-Z][A-Z0-9_]*)[ \t]*\(""")
            return entry.findAll(entries).associate { match ->
                val range = match.groups[1]!!.range
                match.groupValues[1] to IntRange(
                    openBrace + 1 + range.first,
                    openBrace + 1 + range.last,
                )
            }
        }

        /** Keeps quoted text intact while making commented-out Gradle declarations invisible to the policy parser. */
        internal fun stripGradleComments(source: String): String {
            val result = StringBuilder(source.length)
            var index = 0
            var lineComment = false
            var blockCommentDepth = 0
            var quote: Char? = null
            var tripleQuoted = false
            var escaped = false

            while (index < source.length) {
                val current = source[index]
                val next = source.getOrNull(index + 1)

                if (lineComment) {
                    if (current == '\n') {
                        result.append(current)
                        lineComment = false
                    }
                    index++
                    continue
                }

                if (blockCommentDepth > 0) {
                    when {
                        current == '/' && next == '*' -> {
                            blockCommentDepth++
                            index += 2
                        }
                        current == '*' && next == '/' -> {
                            blockCommentDepth--
                            index += 2
                        }
                        else -> {
                            if (current == '\n') result.append(current)
                            index++
                        }
                    }
                    continue
                }

                if (quote != null) {
                    if (tripleQuoted && source.startsWith("\"\"\"", index)) {
                        result.append("\"\"\"")
                        index += 3
                        quote = null
                        tripleQuoted = false
                    } else {
                        result.append(current)
                        when {
                            escaped -> escaped = false
                            current == '\\' && !tripleQuoted -> escaped = true
                            current == quote && !tripleQuoted -> quote = null
                        }
                        index++
                    }
                    continue
                }

                when {
                    current == '/' && next == '/' -> {
                        lineComment = true
                        index += 2
                    }
                    current == '/' && next == '*' -> {
                        blockCommentDepth = 1
                        index += 2
                    }
                    source.startsWith("\"\"\"", index) -> {
                        result.append("\"\"\"")
                        quote = '"'
                        tripleQuoted = true
                        index += 3
                    }
                    current == '"' || current == '\'' -> {
                        result.append(current)
                        quote = current
                        index++
                    }
                    else -> {
                        result.append(current)
                        index++
                    }
                }
            }
            return result.toString()
        }

        /** Masks Kotlin comments and string/character literals while preserving offsets and line breaks. */
        internal fun maskKotlinNonCode(source: String): String {
            val result = source.toCharArray()
            var index = 0
            var lineComment = false
            var blockCommentDepth = 0
            var quote: Char? = null
            var tripleQuoted = false
            var escaped = false

            fun mask(position: Int) {
                if (result[position] != '\n' && result[position] != '\r') result[position] = ' '
            }

            while (index < source.length) {
                val current = source[index]
                val next = source.getOrNull(index + 1)

                if (lineComment) {
                    mask(index)
                    if (current == '\n') lineComment = false
                    index++
                    continue
                }

                if (blockCommentDepth > 0) {
                    when {
                        current == '/' && next == '*' -> {
                            mask(index)
                            mask(index + 1)
                            blockCommentDepth++
                            index += 2
                        }
                        current == '*' && next == '/' -> {
                            mask(index)
                            mask(index + 1)
                            blockCommentDepth--
                            index += 2
                        }
                        else -> {
                            mask(index)
                            index++
                        }
                    }
                    continue
                }

                if (quote != null) {
                    if (tripleQuoted && source.startsWith("\"\"\"", index)) {
                        repeat(3) { mask(index + it) }
                        index += 3
                        quote = null
                        tripleQuoted = false
                    } else {
                        mask(index)
                        when {
                            escaped -> escaped = false
                            current == '\\' && !tripleQuoted -> escaped = true
                            current == quote && !tripleQuoted -> quote = null
                        }
                        index++
                    }
                    continue
                }

                when {
                    current == '/' && next == '/' -> {
                        mask(index)
                        mask(index + 1)
                        lineComment = true
                        index += 2
                    }
                    current == '/' && next == '*' -> {
                        mask(index)
                        mask(index + 1)
                        blockCommentDepth = 1
                        index += 2
                    }
                    source.startsWith("\"\"\"", index) -> {
                        repeat(3) { mask(index + it) }
                        quote = '"'
                        tripleQuoted = true
                        index += 3
                    }
                    current == '"' || current == '\'' -> {
                        mask(index)
                        quote = current
                        index++
                    }
                    else -> index++
                }
            }
            return result.concatToString()
        }
    }
}

internal object ArchitecturePolicy {
    val directDependencies: Map<String, Set<String>> = linkedMapOf(
        ":agent:research-run" to emptySet(),
        ":agent:infoset-core" to setOf(":agent:research-run"),
        ":agent:infoset-argentum" to setOf(":agent:infoset-core"),
        ":agent:search-teacher" to setOf(":agent:infoset-core", ":agent:infoset-argentum"),
        ":evaluation:argentum" to setOf(":agent:research-run"),
        ":evaluation:search-teacher" to setOf(
            ":agent:research-run",
            ":agent:infoset-core",
            ":agent:infoset-argentum",
            ":agent:search-teacher",
        ),
        ":integration:argentum-search-teacher" to setOf(
            ":agent:infoset-argentum",
            ":agent:search-teacher",
        ),
        ":quality:architecture" to emptySet(),
    )

    val dependencyLevel: Map<String, Int> = mapOf(
        ":agent:research-run" to 0,
        ":agent:infoset-core" to 1,
        ":quality:architecture" to 0,
        ":agent:infoset-argentum" to 2,
        ":agent:search-teacher" to 3,
        ":evaluation:argentum" to 3,
        ":evaluation:search-teacher" to 3,
        ":integration:argentum-search-teacher" to 3,
    )

    val packageOwners: Map<String, String> = linkedMapOf(
        ":agent:research-run" to "org.mtgallium.research.run",
        ":agent:infoset-core" to "org.mtgallium.agent.infoset.core",
        ":agent:infoset-argentum" to "org.mtgallium.agent.infoset.argentum",
        ":agent:search-teacher" to "org.mtgallium.agent.searchteacher",
        ":evaluation:argentum" to "org.mtgallium.evaluation.argentum",
        ":evaluation:search-teacher" to "org.mtgallium.evaluation.searchteacher",
        ":integration:argentum-search-teacher" to "org.mtgallium.integration.searchteacher",
        ":quality:architecture" to "org.mtgallium.quality.architecture",
    )

    val searchCompositionConsumers = setOf(
        ":agent:infoset-argentum",
        ":agent:search-teacher",
        ":evaluation:argentum",
        ":evaluation:search-teacher",
        ":integration:argentum-search-teacher",
    )

    const val searchCompositionRootPath =
        "agent/search-teacher/src/main/kotlin/org/mtgallium/agent/searchteacher/SearchTeacherSearchFactory.kt"
    const val leafContractPath =
        "agent/infoset-core/src/main/kotlin/org/mtgallium/agent/infoset/core/InformationSetSearchContract.kt"

    const val frozenDeckPath = "fixtures/decks/mono-red-standard-2026-07-30.json"
    const val artifactStorePath =
        "evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/evidence/EvidenceStore.kt"
    val generatedDirectoryNames = setOf("work", "raw", "build", "dist")
    val nonNormativeDirectoryNames = generatedDirectoryNames + setOf(".git", ".gradle", "node_modules")
    val retiredSymbols = setOf(
        listOf("TournamentReplay", "Writer").joinToString(""),
        listOf("EvaluatorReviewProtocol", "Runner").joinToString(""),
    )
}
