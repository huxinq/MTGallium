package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.model.Deck
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.mtgallium.agent.argentum.policy.LivePolicyConfig
import org.mtgallium.agent.argentum.policy.PolicyIdentity
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.PerspectiveHistoryEventOrder
import org.mtgallium.agent.infoset.argentum.PerspectiveHistoryObjectReference
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import org.mtgallium.agent.monored.ValueFeatures
import org.mtgallium.agent.neural.FactualPolicyEncoder
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Public-fixture byte baselines. Capture writes candidates and deliberately fails; only reviewed
 * resources can satisfy the ordinary test. Identity has its own baseline for the deliberate PR2 bump.
 */
class FrozenPublicByteGoldensTest {
    private val decks = mapOf("p0" to mapOf("Mountain" to 8), "p1" to mapOf("Mountain" to 8))

    @Test
    fun provenanceValidatorRejectsUncleanOrMisattributedSnapshots() {
        val stack = "a".repeat(40)
        val parent = ROOT_MAIN_SHA
        fun provenance(commit: String = stack, diff: String = "", status: String = "",
            engineHead: String = "c".repeat(40), enginePin: String = engineHead) = JsonObject(mapOf(
            "commit" to JsonPrimitive(commit), "diff" to JsonPrimitive(diff),
            "status" to JsonPrimitive(status), "engine_head" to JsonPrimitive(engineHead),
            "engine_pin" to JsonPrimitive(enginePin),
        ))
        validateProvenance(provenance(), stack, parent, "behavior")
        validateProvenance(provenance(), stack, parent, "identity")
        validateProvenance(provenance(), stack, POST_IDENTITY_PARENT_SHA, "identity")
        assertFailsWith<IllegalArgumentException> { validateProvenance(provenance(), "short", parent, "behavior") }
        assertFailsWith<IllegalArgumentException> { validateProvenance(provenance(), stack, stack, "behavior") }
        assertFailsWith<IllegalArgumentException> { validateProvenance(provenance(), stack, "b".repeat(40), "identity") }
        assertFailsWith<IllegalArgumentException> {
            validateProvenance(provenance(), stack, POST_IDENTITY_PARENT_SHA, "behavior")
        }
        assertFailsWith<IllegalArgumentException> { validateProvenance(provenance(), stack, parent, "unknown") }
        assertFailsWith<IllegalArgumentException> {
            validateProvenance(provenance(commit = "d".repeat(40)), stack, parent, "identity")
        }
        assertFailsWith<IllegalArgumentException> { validateProvenance(provenance(diff = "patch"), stack, parent, "identity") }
        assertFailsWith<IllegalArgumentException> { validateProvenance(provenance(status = " M file"), stack, parent, "identity") }
        assertFailsWith<IllegalArgumentException> {
            validateProvenance(provenance(enginePin = "d".repeat(40)), stack, parent, "identity")
        }
        assertFailsWith<IllegalArgumentException> {
            validateProvenance(provenance(engineHead = "short"), stack, parent, "identity")
        }
        val preSources = identitySourcesFor(parent, listOf(
            "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/BeliefTracker.kt",
            RETIRED_BELIEF_PREPARATION,
        ))
        val postSources = identitySourcesFor(POST_IDENTITY_PARENT_SHA, preSources)
        assertTrue(RETIRED_BELIEF_PREPARATION in preSources)
        assertTrue(postSources == listOf(preSources.first()))
        validateSourceDigest("a".repeat(64), "a".repeat(64))
        assertFailsWith<IllegalArgumentException> { validateSourceDigest("a".repeat(64), "b".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { validateSourceDigest("short", "a".repeat(64)) }
    }

    @Test
    fun identityBytes() {
        val base = LivePolicyConfig(particles = 4, simulations = 8, maxPolicyDecisions = 4).policyParameters()
        val identities = sortedMapOf(
            "default" to PolicyIdentity.identity(base, decks, UniformOpponentPolicy),
            "snapshot-a" to PolicyIdentity.identity(base.copy(beliefArchitecture = BeliefArchitecture.SNAPSHOT_A_V1),
                decks, UniformOpponentPolicy),
            "privileged-o" to PolicyIdentity.identity(base.copy(beliefArchitecture = BeliefArchitecture.PRIVILEGED_O_V1),
                decks, UniformOpponentPolicy),
            "conditioned" to PolicyIdentity.identity(base.copy(beliefMode = BeliefMode.POLICY_CONDITIONED_V1),
                decks, UniformOpponentPolicy),
        )
        verifyOrCapture("identity", identities.mapValues { it.value.toByteArray(UTF_8) }, listOf(
            "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/PolicyIdentity.kt",
            "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/SearchPolicy.kt",
            "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/BeliefTracker.kt",
            "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/BeliefPreparation.kt",
            "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/PolicyDefaults.kt",
            "agent/mono-red-models/src/main/kotlin/org/mtgallium/agent/monored/MonoRedInformationEvaluator.kt",
            "agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyContract.kt",
        ))
    }

    @Test
    fun publicBehaviorBytes() {
        val world = publicWorld(PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1,
            PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1)
        val site = world.decisionContext().site()
        val information = site.information()
        val tensors = FactualPolicyEncoder().decision(site)
        val keys = ValueFeatures.compile(information, "p0").values.keys.sorted()
        assertTrue(keys.isNotEmpty())
        val outputs = sortedMapOf<String, ByteArray>(
            "value/public-fixture-keys" to keys.joinToString("\n", postfix = "\n").toByteArray(UTF_8),
            "tensor/view-text" to untoken(tensors.view),
            "tensor/flags" to "${tensors.rulesExhaustive},${tensors.profileExhaustive}\n".toByteArray(UTF_8),
            // The public API returns hashed vectors; it does not expose pre-hash feature names.
            "kernel/public-fixture-vectors" to researchJson.encodeToString(rootActionKernelFeatures(site)).toByteArray(UTF_8),
        )
        tensors.actions.forEachIndexed { index, action ->
            outputs["tensor/action-text-%03d".format(index)] = untoken(action)
        }
        // A visible land move makes this an ordinary history corpus; it is not a qualified
        // simultaneous-untap witness. That mechanism retains its focused regression tests.
        for (order in listOf(PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1,
            PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2)) {
            for (references in listOf(PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1,
                PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2)) {
                val historyWorld = publicWorld(order, references)
                var landPlays = 0
                for (step in 0 until 48) {
                    if (historyWorld.actorToAct() == null) break
                    val candidates = historyWorld.expandChoices().candidates
                    val choice = candidates.firstOrNull {
                        it.operationFamily == SemanticOperationFamily.PLAY_LAND
                    } ?: candidates.firstOrNull {
                        it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
                    } ?: candidates.firstOrNull() ?: error("No choice in public history fixture at step $step")
                    check(historyWorld.step(choice).accepted) { "Rejected public history fixture choice $step" }
                    if (choice.operationFamily == SemanticOperationFamily.PLAY_LAND) landPlays++
                }
                check(landPlays > 0) { "Public history fixture never played a visible land" }
                for (viewer in listOf("p0", "p1")) {
                    val state = historyWorld.informationState(viewer)
                    check(state.history.isNotEmpty()) { "History fixture produced no events for $viewer" }
                    val prefix = "history/${order.name}/${references.name}/$viewer"
                    outputs["$prefix/events"] = PolicyJson.format.encodeToString(state.history).toByteArray(UTF_8)
                    outputs["$prefix/commitment"] = PolicyJson.format.encodeToString(state.historyCommitment).toByteArray(UTF_8)
                }
            }
        }
        verifyOrCapture("behavior", outputs, listOf(
            "agent/mono-red-models/src/main/kotlin/org/mtgallium/agent/monored/ValueFeatures.kt",
            "agent/neural-policy/src/main/kotlin/org/mtgallium/agent/neural/FactualPolicyTensors.kt",
            "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/SemanticFeatures.kt",
            "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/Kernel.kt",
            "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/Games.kt",
            "agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyContract.kt",
            "agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyHistoryCommitment.kt",
            "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumSearchWorld.kt",
            "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/PerspectiveHistoryEventOrder.kt",
            "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/PerspectiveHistoryObjectReference.kt",
        ))
    }

    private fun publicWorld(order: PerspectiveHistoryEventOrder,
        references: PerspectiveHistoryObjectReference): ArgentumSearchWorld {
        val registry = buildRegistry()
        val environment = GameEnvironment.create(registry)
        environment.reset(GameConfig(
            players = listOf(PlayerConfig("A", Deck.of("Mountain" to 8)),
                PlayerConfig("B", Deck.of("Mountain" to 8))),
            startingHandSize = 2, skipMulligans = true, startingPlayerIndex = 0, seed = 61L,
        ))
        return ArgentumSearchWorld.create(environment, "frozen-public-61", 61L, 61L,
            knownDecks = decks, historyEventOrder = order, historyObjectReference = references)
    }

    private fun untoken(tokens: List<Int>): ByteArray {
        require(tokens.all { it in 1..256 })
        return tokens.map { (it - 1).toByte() }.toByteArray()
    }

    private fun verifyOrCapture(group: String, entries: Map<String, ByteArray>, sources: List<String>) {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            ?: error("Cannot find public source root")
        val payload = JsonObject(entries.toSortedMap().mapValues { JsonPrimitive(Base64.getEncoder().encodeToString(it.value)) })
            .toString().toByteArray(UTF_8)
        if (System.getenv("MTG_CAPTURE_GOLDENS") == "1") {
            val expectedStackSha = requireNotNull(System.getenv("MTG_GOLDEN_BASELINE_SHA")) {
                "Capture requires the reviewed full PR1 test-stack SHA"
            }
            val productionParentSha = requireNotNull(System.getenv("MTG_GOLDEN_PRODUCTION_PARENT_SHA")) {
                "Capture requires the reviewed production-parent SHA for this group"
            }
            val provenancePath = requireNotNull(System.getenv("MTG_SOURCE_JSON")) {
                "Capture requires tools/remote snapshot provenance in MTG_SOURCE_JSON"
            }
            val provenance = Json.parseToJsonElement(Files.readString(Path.of(provenancePath))).jsonObject
            validateProvenance(provenance, expectedStackSha, productionParentSha, group)
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            val selectedSources = if (group == "identity") identitySourcesFor(productionParentSha, sources) else sources
            selectedSources.sorted().forEach { relative ->
                sourceDigest.update(relative.toByteArray(UTF_8))
                sourceDigest.update(0.toByte())
                sourceDigest.update(Files.readAllBytes(root.resolve(relative)))
            }
            val sourceDigestHex = sourceDigest.digest().hex()
            val reviewedSourceDigest = requireNotNull(System.getenv("MTG_GOLDEN_${group.uppercase(Locale.ROOT)}_SOURCE_DIGEST")) {
                "Capture requires the $group source digest calculated from the reviewed production parent"
            }
            validateSourceDigest(expectedSourceDigest(productionParentSha, group), reviewedSourceDigest)
            validateSourceDigest(reviewedSourceDigest, sourceDigestHex)
            val metadata = JsonObject(mapOf(
                "fixtureVersion" to JsonPrimitive("frozen-public-v1"),
                "sourceSha" to JsonPrimitive(expectedStackSha),
                "productionParentSha" to JsonPrimitive(productionParentSha),
                "rootMainSha" to JsonPrimitive(ROOT_MAIN_SHA),
                "sourceDigestSha256" to JsonPrimitive(sourceDigestHex),
                "payloadSha256" to JsonPrimitive(sha256(payload)),
                "entryCount" to JsonPrimitive(entries.size),
                "snapshotProvenance" to provenance,
            )).toString().toByteArray(UTF_8)
            val output = root.resolve("research/workbench/build/frozen-public-byte-goldens")
            Files.createDirectories(output)
            Files.write(output.resolve("$group.candidate.json"), payload)
            Files.write(output.resolve("$group.candidate.meta.json"), metadata)
            fail("Unreviewed $group candidate at $output; capture is never verification")
        }
        val baseline = javaClass.getResourceAsStream("/frozen-public-byte-goldens/$group.json")
            ?.use { it.readBytes() } ?: fail("Missing reviewed $group byte golden; capture on unretired source first")
        val metadataBytes = javaClass.getResourceAsStream("/frozen-public-byte-goldens/$group.meta.json")
            ?.use { it.readBytes() } ?: fail("Missing reviewed $group golden source/digest metadata")
        val metadata = Json.parseToJsonElement(metadataBytes.toString(UTF_8)).jsonObject
        require(metadata.getValue("fixtureVersion").jsonPrimitive.content == "frozen-public-v1")
        require(metadata.getValue("rootMainSha").jsonPrimitive.content == ROOT_MAIN_SHA)
        validateProvenance(metadata.getValue("snapshotProvenance").jsonObject,
            metadata.getValue("sourceSha").jsonPrimitive.content,
            metadata.getValue("productionParentSha").jsonPrimitive.content, group)
        val recordedSourceDigest = metadata.getValue("sourceDigestSha256").jsonPrimitive.content
        require(recordedSourceDigest == expectedSourceDigest(
            metadata.getValue("productionParentSha").jsonPrimitive.content, group)) {
            "Reviewed $group source digest differs from the accepted parent source bytes"
        }
        require(metadata.getValue("payloadSha256").jsonPrimitive.content == sha256(baseline)) {
            "Reviewed $group golden bytes do not match their digest metadata"
        }
        require(metadata.getValue("entryCount").jsonPrimitive.int == entries.size)
        if (!baseline.contentEquals(payload)) {
            val pinned = Json.parseToJsonElement(baseline.toString(UTF_8)).jsonObject
            val changed = (pinned.keys + entries.keys).sorted().firstOrNull {
                pinned[it]?.jsonPrimitive?.content != payload.decodeJsonEntry(it)
            }
            fail("$group byte golden changed at $changed; pinned=${sha256(baseline)} current=${sha256(payload)}")
        }
    }

    private fun ByteArray.decodeJsonEntry(key: String): String? =
        Json.parseToJsonElement(toString(UTF_8)).jsonObject[key]?.jsonPrimitive?.content

    private fun identitySourcesFor(productionParentSha: String, sources: List<String>): List<String> =
        if (productionParentSha == POST_IDENTITY_PARENT_SHA) {
            sources.filterNot { it == RETIRED_BELIEF_PREPARATION }
        } else sources

    private fun validateSourceDigest(reviewed: String, actual: String) {
        require(Regex("[0-9a-f]{64}").matches(reviewed) && reviewed == actual) {
            "Capture source differs from the reviewed production-parent sources"
        }
    }

    private fun expectedSourceDigest(productionParentSha: String, group: String): String =
        when (productionParentSha to group) {
            ROOT_MAIN_SHA to "identity" -> PRE_IDENTITY_SOURCE_DIGEST
            ROOT_MAIN_SHA to "behavior" -> PRE_BEHAVIOR_SOURCE_DIGEST
            POST_IDENTITY_PARENT_SHA to "identity" -> POST_IDENTITY_SOURCE_DIGEST
            else -> error("No accepted source digest for $group at $productionParentSha")
        }

    private fun validateProvenance(provenance: JsonObject, expectedStackSha: String,
        productionParentSha: String, group: String) {
        require(Regex("[0-9a-f]{40}").matches(expectedStackSha) &&
            (productionParentSha == ROOT_MAIN_SHA ||
                (group == "identity" && productionParentSha == POST_IDENTITY_PARENT_SHA)) &&
            group in setOf("identity", "behavior") && expectedStackSha != productionParentSha) {
            "Expected SHA and group must name a reviewed capture stack and its allowed production parent"
        }
        require(provenance.getValue("commit").jsonPrimitive.content == expectedStackSha) {
            "tools/remote snapshot commit differs from the reviewed full test-stack SHA"
        }
        require(provenance.getValue("diff").jsonPrimitive.content.isEmpty()) {
            "Capture refuses a snapshot with tracked changes"
        }
        require(provenance.getValue("status").jsonPrimitive.content.isEmpty()) {
            "Capture refuses a snapshot with staged or untracked changes"
        }
        val engineHead = provenance.getValue("engine_head").jsonPrimitive.content
        val enginePin = provenance.getValue("engine_pin").jsonPrimitive.content
        require(Regex("[0-9a-f]{40}").matches(engineHead) && engineHead == enginePin) {
            "Snapshot engine does not match its Argentum pin"
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val ROOT_MAIN_SHA = "2a5cfce11fa59815d849d2a1f8fabab2e7eba79f"
        private const val POST_IDENTITY_PARENT_SHA = "26f3af44bd6030f931ddefaff1f29d1d30c3fe90"
        private const val RETIRED_BELIEF_PREPARATION =
            "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/BeliefPreparation.kt"
        private const val PRE_IDENTITY_SOURCE_DIGEST = "d177ab0b6880ab0a26a346bf4ebd85655152a11ec843864abb875282557d0088"
        private const val PRE_BEHAVIOR_SOURCE_DIGEST = "9de941b63ff7627ae90c2e0612cdfc9a18bcd16d3392743509ecfbb878d01741"
        private const val POST_IDENTITY_SOURCE_DIGEST = "a0e32d45d139b2d42d9a76354ca023a8b5a69c9a3bb046b97752370bc1999439"
    }
}
