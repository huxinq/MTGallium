package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mtgallium.agent.infoset.core.PolicyBehaviorBinding
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.junit.jupiter.api.Tag

@Tag("public-source")
class PolicyEvidenceIdentityTest {
    @Test
    fun `source provenance and policy identity distinguish every clean and dirty source state`() {
        val root = createTempDirectory("mtgallium-policy-source-")
        initializeRepositoryWithPinnedEngine(root)

        val clean = RunProvenance.capture(root)
        val cleanAgain = RunProvenance.capture(root)
        assertTrue(clean.consistent)
        assertFalse(clean.outerDirty)
        assertFalse(clean.argentumDirty)
        assertEquals(clean.sourceProvenance, cleanAgain.sourceProvenance)
        assertTrue(requireNotNull(clean.sourceProvenance).gitlinkMatchesCheckout)
        val cleanIdentity = binding(clean).identity

        Files.writeString(root.resolve("README.md"), "tracked outer behavior changed\n")
        val trackedOuter = RunProvenance.capture(root)
        assertTrue(trackedOuter.outerDirty)
        assertNotEquals(
            requireNotNull(clean.sourceProvenance).outer.trackedDiffSha256,
            requireNotNull(trackedOuter.sourceProvenance).outer.trackedDiffSha256,
        )
        assertNotEquals(cleanIdentity, binding(trackedOuter).identity)

        git(root, "add", "README.md")
        git(root, "commit", "--quiet", "-m", "new clean outer baseline")
        val secondClean = RunProvenance.capture(root)
        assertFalse(secondClean.outerDirty)
        val untrackedPath = root.resolve("new-policy-source.txt")
        Files.writeString(untrackedPath, "untracked behavior one\n")
        val untrackedOne = RunProvenance.capture(root)
        assertTrue(untrackedOne.outerDirty)
        assertEquals(
            requireNotNull(secondClean.sourceProvenance).outer.trackedDiffSha256,
            requireNotNull(untrackedOne.sourceProvenance).outer.trackedDiffSha256,
        )
        assertNotEquals(
            requireNotNull(secondClean.sourceProvenance).outer.untrackedContentSha256,
            requireNotNull(untrackedOne.sourceProvenance).outer.untrackedContentSha256,
        )
        assertNotEquals(binding(secondClean).identity, binding(untrackedOne).identity)

        Files.writeString(untrackedPath, "untracked behavior two\n")
        val untrackedTwo = RunProvenance.capture(root)
        assertEquals(
            requireNotNull(untrackedOne.sourceProvenance).outer.statusSha256,
            requireNotNull(untrackedTwo.sourceProvenance).outer.statusSha256,
        )
        assertNotEquals(
            requireNotNull(untrackedOne.sourceProvenance).outer.untrackedContentSha256,
            requireNotNull(untrackedTwo.sourceProvenance).outer.untrackedContentSha256,
        )
        assertNotEquals(binding(untrackedOne).identity, binding(untrackedTwo).identity)

        val engineFile = root.resolve("third_party/argentum-engine/engine.txt")
        Files.writeString(engineFile, "dirty engine behavior\n")
        val dirtyEngine = RunProvenance.capture(root)
        assertTrue(dirtyEngine.argentumDirty)
        assertNotEquals(
            requireNotNull(untrackedTwo.sourceProvenance).argentum.trackedDiffSha256,
            requireNotNull(dirtyEngine.sourceProvenance).argentum.trackedDiffSha256,
        )
        assertNotEquals(binding(untrackedTwo).identity, binding(dirtyEngine).identity)
    }

    @Test
    fun `full evidence identity changes even if a stale declared behavior id is reused`() {
        val root = createTempDirectory("mtgallium-policy-behavior-")
        initializeRepositoryWithPinnedEngine(root)
        val source = requireNotNull(RunProvenance.capture(root).sourceProvenance)
        val first = PolicyBehaviorBinding.create(
            behaviorIdentity = "stale-declared-id",
            behaviorSpecification = buildJsonObject { put("exploration", 0) },
            sourceProvenance = source,
        )
        val changed = PolicyBehaviorBinding.create(
            behaviorIdentity = "stale-declared-id",
            behaviorSpecification = buildJsonObject { put("exploration", 10) },
            sourceProvenance = source,
        )

        assertNotEquals(first.behaviorSpecificationSha256, changed.behaviorSpecificationSha256)
        assertNotEquals(first.identity, changed.identity)
    }

    private fun binding(provenance: RunProvenance): PolicyBehaviorBinding =
        PolicyBehaviorBinding.create(
            behaviorIdentity = "search-teacher-behavior-v1-sha256:${"a".repeat(64)}",
            behaviorSpecification = buildJsonObject {
                put("schemaVersion", 1)
                put("implementation", "test-policy")
            },
            sourceProvenance = requireNotNull(provenance.sourceProvenance),
        )

    private fun initializeRepositoryWithPinnedEngine(root: Path) {
        git(root, "init", "--quiet")
        git(root, "config", "user.name", "Policy Evidence Test")
        git(root, "config", "user.email", "policy-evidence@example.invalid")

        val engine = root.resolve("third_party/argentum-engine")
        Files.createDirectories(engine)
        git(engine, "init", "--quiet")
        git(engine, "config", "user.name", "Policy Evidence Test")
        git(engine, "config", "user.email", "policy-evidence@example.invalid")
        Files.writeString(engine.resolve("engine.txt"), "pinned engine\n")
        git(engine, "add", "engine.txt")
        git(engine, "commit", "--quiet", "-m", "pin engine")

        Files.writeString(root.resolve("README.md"), "test repository\n")
        git(root, "add", "README.md", "third_party/argentum-engine")
        git(root, "commit", "--quiet", "-m", "test baseline")
    }

    private fun git(root: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) {
            "git ${arguments.joinToString(" ")} failed: $output"
        }
    }
}
