package org.mtgallium.quality.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureRulesTest {
    @Test
    fun `compliant fixture has no violations`() {
        val fixture = RepositoryFixture()

        assertEquals(emptyList(), fixture.evaluate())
    }

    @Test
    fun `module and direct edge drift have distinct actionable codes`() {
        val fixture = RepositoryFixture()
        fixture.writeSettings(
            ArchitecturePolicy.directDependencies.keys - ":evaluation:argentum" + ":experimental:orphan"
        )
        fixture.writeBuild(":agent:infoset-argentum", emptyList())
        fixture.writeBuild(
            ":agent:infoset-core",
            listOf(":agent:search-teacher", ":agent:search-teacher"),
        )

        val violations = fixture.evaluate()

        assertCodes(
            violations,
            "ARCH-PROJECT-MODULE-MISSING",
            "ARCH-PROJECT-MODULE-UNEXPECTED",
            "ARCH-PROJECT-EDGE-MISSING",
            "ARCH-PROJECT-EDGE-UNEXPECTED",
            "ARCH-PROJECT-EDGE-DUPLICATED",
            "ARCH-PROJECT-UPWARD-DEPENDENCY",
            "ARCH-PROJECT-CYCLE",
        )
        assertTrue(violations.all { "Remedy:" in it.toString() })
    }

    @Test
    fun `package ownership and engine independence are checked in new working tree files`() {
        val fixture = RepositoryFixture()
        fixture.write(
            "agent/infoset-core/src/test/kotlin/WrongOwner.kt",
            """
                package org.mtgallium.evaluation.borrowed

                import ${listOf("com", "wingedsheep", "engine", "core", "GameEvent").joinToString(".")}
            """.trimIndent(),
        )

        assertCodes(
            fixture.evaluate(),
            "ARCH-PACKAGE-OWNERSHIP",
            "ARCH-INFOSET-CORE-ENGINE-REFERENCE",
            "ARCH-RUNTIME-DEPENDS-ON-EVALUATION",
        )
    }

    @Test
    fun `evaluation and integration cannot import through one another`() {
        val fixture = RepositoryFixture()
        fixture.write(
            "integration/argentum-search-teacher/src/test/kotlin/EvaluationBypass.kt",
            "package org.mtgallium.integration.searchteacher\nimport " +
                listOf("org", "mtgallium", "evaluation", "searchteacher", "Arena").joinToString("."),
        )
        fixture.write(
            "evaluation/search-teacher/src/test/kotlin/DeploymentBypass.kt",
            "package org.mtgallium.evaluation.searchteacher\nimport " +
                listOf("org", "mtgallium", "integration", "searchteacher", "Application").joinToString("."),
        )

        assertCodes(
            fixture.evaluate(),
            "ARCH-RUNTIME-DEPENDS-ON-EVALUATION",
            "ARCH-EVALUATION-INTEGRATION-COUPLING",
        )
    }

    @Test
    fun `boundary and retirement checks ignore comments and literals`() {
        val fixture = RepositoryFixture()
        fixture.write(
            "agent/infoset-core/src/main/kotlin/org/mtgallium/agent/infoset/core/Notes.kt",
            """
                package org.mtgallium.agent.infoset.core

                /** com.wingedsheep and org.mtgallium.evaluation are architectural examples. */
                val examples = "com.wingedsheep org.mtgallium.evaluation TournamentReplayWriter"
            """.trimIndent(),
        )
        fixture.write(
            "agent/search-teacher/src/main/kotlin/org/mtgallium/agent/searchteacher/Notes.kt",
            """
                package org.mtgallium.agent.searchteacher

                val example = "org.mtgallium.evaluation"
                // EvaluatorReviewProtocolRunner was deliberately retired.
            """.trimIndent(),
        )
        fixture.write(
            "evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/Notes.kt",
            """
                package org.mtgallium.evaluation.searchteacher

                val example = "org.mtgallium.integration"
            """.trimIndent(),
        )

        val violations = fixture.evaluate()

        assertTrue(violations.none { it.code == "ARCH-INFOSET-CORE-ENGINE-REFERENCE" })
        assertTrue(violations.none { it.code == "ARCH-RUNTIME-DEPENDS-ON-EVALUATION" })
        assertTrue(violations.none { it.code == "ARCH-EVALUATION-INTEGRATION-COUPLING" })
        assertTrue(violations.none { it.code == "ARCH-RETIRED-SYMBOL" })
    }

    @Test
    fun `generic core cannot recover concrete evaluator behavior`() {
        val fixture = RepositoryFixture()
        fixture.write(
            "agent/infoset-core/src/main/kotlin/org/mtgallium/agent/infoset/core/ConcreteLeaf.kt",
            """
                package org.mtgallium.agent.infoset.core

                val leakedBehavior = LeafEvaluator.MTGALLIUM_VISIBLE_V2
            """.trimIndent(),
        )

        assertCodes(fixture.evaluate(), "ARCH-CORE-CONCRETE-EVALUATOR-BEHAVIOR")
    }

    @Test
    fun `persisted evaluator declarations are allowed but contract behavior is not`() {
        val fixture = RepositoryFixture()
        fixture.write(
            ArchitecturePolicy.leafContractPath,
            """
                package org.mtgallium.agent.infoset.core

                enum class LeafEvaluator(val id: String) {
                    MTGALLIUM_VISIBLE_V2("visible-v2"),
                    MTGALLIUM_TACTICAL_V3("tactical-v3"),
                    ARGENTUM_BOARD_V1("board-v1"),
                }
            """.trimIndent(),
        )
        assertTrue(fixture.evaluate().none { it.code == "ARCH-CORE-CONCRETE-EVALUATOR-BEHAVIOR" })

        fixture.write(
            ArchitecturePolicy.leafContractPath,
            """
                package org.mtgallium.agent.infoset.core

                enum class LeafEvaluator(val id: String) {
                    MTGALLIUM_VISIBLE_V2("visible-v2"),
                    MTGALLIUM_TACTICAL_V3("tactical-v3"),
                    ARGENTUM_BOARD_V1("board-v1"),
                    ;

                    val leakedBehavior: Boolean get() = this != ARGENTUM_BOARD_V1
                }
            """.trimIndent(),
        )
        assertCodes(fixture.evaluate(), "ARCH-CORE-CONCRETE-EVALUATOR-BEHAVIOR")
    }

    @Test
    fun `concrete evaluator guard derives future keys instead of relying on prefixes`() {
        val fixture = RepositoryFixture()
        fixture.write(
            ArchitecturePolicy.leafContractPath,
            """
                package org.mtgallium.agent.infoset.core

                enum class LeafEvaluator(val id: String) {
                    MTGALLIUM_VISIBLE_V2("visible-v2"),
                    NEURAL_STUDENT_V1("student-v1"),
                    ;

                    val leakedBehavior: Boolean get() = this == NEURAL_STUDENT_V1
                }
            """.trimIndent(),
        )

        assertCodes(fixture.evaluate(), "ARCH-CORE-CONCRETE-EVALUATOR-BEHAVIOR")
    }

    @Test
    fun `Search Teacher is the production search composition root while tests stay explicit`() {
        val fixture = RepositoryFixture()
        val constructor = listOf("InformationSet", "Search").joinToString("")
        fixture.write(
            "evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/DirectSearch.kt",
            "package org.mtgallium.evaluation.searchteacher\nval search = $constructor(",
        )
        fixture.write(
            "agent/search-teacher/src/main/kotlin/org/mtgallium/agent/searchteacher/DirectSearch.kt",
            "package org.mtgallium.agent.searchteacher\nval search = $constructor(",
        )
        fixture.write(
            ArchitecturePolicy.searchCompositionRootPath,
            "package org.mtgallium.agent.searchteacher\nval search = $constructor(",
        )
        fixture.write(
            "evaluation/search-teacher/src/test/kotlin/org/mtgallium/evaluation/searchteacher/ExplicitTestSearch.kt",
            "package org.mtgallium.evaluation.searchteacher\nval search = $constructor(",
        )
        fixture.write(
            "integration/argentum-search-teacher/src/main/kotlin/org/mtgallium/integration/searchteacher/Mentions.kt",
            "package org.mtgallium.integration.searchteacher\n// $constructor( is not code\nval text = \"$constructor(\"",
        )

        val violations = fixture.evaluate().filter { it.code == "ARCH-SEARCH-COMPOSITION-BYPASS" }

        assertEquals(2, violations.size)
        assertTrue(violations.all { "DirectSearch.kt:2" in it.subject })
    }

    @Test
    fun `artifact path and retired implementation checks have focused failures`() {
        val fixture = RepositoryFixture()
        fixture.write(
            "evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/Bypass.kt",
            "package org.mtgallium.evaluation.searchteacher\nval path = \"" +
                listOf("reports", "search-teacher", "latest").joinToString("/") + "\"",
        )
        fixture.write(
            "evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/Retired.kt",
            "package org.mtgallium.evaluation.searchteacher\nclass Canonical" +
                listOf("TournamentReplay", "Writer").joinToString("") + "\nclass " +
                listOf("TournamentReplay", "Writer").joinToString(""),
        )
        fixture.write("fixtures/duplicate/mono-red-standard-2026-07-30.json", "{}")

        assertCodes(
            fixture.evaluate(),
            "ARCH-EVIDENCE-PATH-BYPASS",
            "ARCH-RETIRED-SYMBOL",
            "ARCH-FROZEN-DECK-DUPLICATED",
        )
        assertEquals(1, fixture.evaluate().count { it.code == "ARCH-RETIRED-SYMBOL" })
    }

    @Test
    fun `generated directory check reads tracked paths rather than ignored output`() {
        val fixture = RepositoryFixture()
        val evidenceRoot = listOf("reports", "search-teacher").joinToString("/")
        fixture.track("$evidenceRoot/work/run/report.json")
        fixture.track("reports/argentum/raw/source.json")
        fixture.track("tools/replay-inspector/dist/index.js")
        fixture.track("agent/infoset-core/build/classes/Main.class")

        val violations = fixture.evaluate().filter { it.code == "ARCH-TRACKED-GENERATED-ARTIFACT" }

        assertEquals(4, violations.size)
    }

    @Test
    fun `parsers accept Gradle formatting without weakening directness`() {
        assertEquals(
            setOf(":agent:one", ":agent:two"),
            ArchitectureRules.parseIncludedProjects(
                """
                    include(":agent:one", ":agent:two")
                    includeBuild("third_party/engine")
                """.trimIndent()
            ),
        )
        assertEquals(
            listOf(":agent:one", ":agent:two"),
            ArchitectureRules.parseProjectDependencies(
                """
                    // implementation(project(":ignored:line"))
                    implementation(project(path = ":agent:one"))
                    /* api(project(":ignored:block")) */
                    testImplementation(project ( ':agent:two' ))
                """.trimIndent()
            ),
        )
    }

    private fun assertCodes(violations: List<ArchitectureViolation>, vararg expected: String) {
        val actual = violations.map(ArchitectureViolation::code).toSet()
        expected.forEach { code -> assertTrue(code in actual, "Missing $code in:\n${violations.joinToString("\n")}") }
    }

    private class RepositoryFixture {
        private val root = createTempDirectory("mtgallium-architecture-")
        private val tracked = linkedSetOf<Path>()

        init {
            writeSettings(ArchitecturePolicy.directDependencies.keys)
            ArchitecturePolicy.directDependencies.forEach { (project, dependencies) ->
                writeBuild(project, dependencies.toList())
            }
            ArchitecturePolicy.packageOwners.forEach { (project, ownedPackage) ->
                write(
                    "${projectPath(project)}/src/main/kotlin/Owned.kt",
                    "package $ownedPackage\nobject Owned",
                )
            }
            write(
                ArchitecturePolicy.artifactStorePath,
                "package org.mtgallium.evaluation.searchteacher.evidence\n" +
                    "val root = \"${listOf("reports", "search-teacher").joinToString("/")}\"",
            )
            write(
                ArchitecturePolicy.leafContractPath,
                """
                    package org.mtgallium.agent.infoset.core

                    enum class LeafEvaluator(val id: String) {
                        MTGALLIUM_VISIBLE_V2("visible-v2"),
                        MTGALLIUM_TACTICAL_V3("tactical-v3"),
                        ARGENTUM_BOARD_V1("board-v1"),
                    }
                """.trimIndent(),
            )
            write(ArchitecturePolicy.frozenDeckPath, "{}")
        }

        fun writeSettings(projects: Collection<String>) {
            write("settings.gradle.kts", projects.joinToString("\n") { "include(\"$it\")" })
        }

        fun writeBuild(project: String, dependencies: Collection<String>) {
            val declarations = dependencies.joinToString("\n") { "implementation(project(\"$it\"))" }
            write("${projectPath(project)}/build.gradle.kts", "dependencies {\n$declarations\n}")
        }

        fun write(relative: String, content: String) {
            val path = root.resolve(relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
            tracked.add(Path.of(relative))
        }

        fun track(relative: String) {
            tracked.add(Path.of(relative))
        }

        fun evaluate(): List<ArchitectureViolation> = ArchitectureRules(root, tracked.toList()).evaluate()

        private fun projectPath(project: String): String = project.removePrefix(":").replace(':', '/')
    }
}
