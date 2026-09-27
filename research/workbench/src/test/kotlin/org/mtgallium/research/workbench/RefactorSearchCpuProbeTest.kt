package org.mtgallium.research.workbench

import com.sun.management.OperatingSystemMXBean
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.model.Deck
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.mtgallium.agent.argentum.policy.SearchPolicyConfig
import org.mtgallium.agent.argentum.policy.SearchPolicySession
import org.mtgallium.agent.argentum.policy.defaultMonoRedOpponentPolicy
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.PerspectiveHistoryEventOrder
import org.mtgallium.agent.infoset.argentum.PerspectiveHistoryObjectReference
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.InformationSetSearchResult
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.RootActionSelection
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in measurement entrypoint, not a correctness test or a performance claim.
 *
 * Run only on an otherwise idle build host with MTG_RUN_REFACTOR_CPU_PROBE=1.
 * Normal suites skip this probe. Same seed/budget and alternating order pair
 * legacy/V2 modes. The fixture is a public deck after pass-only prefixes; this
 * measures that bounded root workload, not a representative game or policy.
 * Capture before and after a refactor on the same host/JDK. Compare distributions
 * externally; this probe never labels a noisy sample as meeting a +/-3% budget.
 */
class RefactorSearchCpuProbeTest {
    @Test
    fun `record paired default and V2 process CPU per search decision`() {
        assumeTrue(System.getenv("MTG_RUN_REFACTOR_CPU_PROBE") == "1",
            "Opt-in CPU probe; skipped is not performance verification")
        val sourcePath = Path.of(requireNotNull(System.getenv("MTG_SOURCE_JSON")) {
            "Run the opt-in probe through tools/remote for source provenance"
        })
        val provenance = researchJson.parseToJsonElement(Files.readString(sourcePath)).jsonObject
        check(provenance.getValue("diff").jsonPrimitive.content.isEmpty() &&
            provenance.getValue("status").jsonPrimitive.content.isEmpty()) {
            "CPU comparisons require a clean committed source snapshot"
        }
        val sourceSha = provenance.getValue("commit").jsonPrimitive.content
        val samples = System.getenv("MTG_REFACTOR_CPU_SAMPLES")?.toInt() ?: 7
        require(samples in 3..50)
        val cpu = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
            ?: error("Process CPU time is unavailable on this JVM")
        check(cpu.processCpuTime >= 0) { "Process CPU time is unsupported" }
        val cards = publicDeck()
        val registry = buildRegistry()
        val deck = Deck.of(*cards.entries.map { it.key to it.value }.toTypedArray())
        val known = mapOf("p0" to cards, "p1" to cards)
        val lines = mutableListOf<String>()

        fun sample(v2: Boolean, sampleIndex: Int, measured: Boolean) {
            val seed = 811L + sampleIndex
            val environment = GameEnvironment.create(registry).also { env ->
                env.reset(GameConfig(players = listOf(PlayerConfig("Alice", deck), PlayerConfig("Bob", deck)),
                    seed = seed, startingPlayerIndex = 0, startingHandSize = 7,
                    skipMulligans = true, useHandSmoother = false))
            }
            val world = ArgentumSearchWorld.create(environment, "refactor-cpu-$sampleIndex", seed, seed,
                expander = UnifiedSemanticExpander(actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1),
                knownDecks = known,
                historyEventOrder = if (v2) PerspectiveHistoryEventOrder.QUALIFIED_TURN_UNTAP_V2
                    else PerspectiveHistoryEventOrder.LEGACY_ENGINE_ORDER_V1,
                historyObjectReference = if (v2) PerspectiveHistoryObjectReference.QUALIFIED_OBSERVED_OBJECTS_V2
                    else PerspectiveHistoryObjectReference.LEGACY_SNAPSHOT_V1)
            // Build a bounded non-empty observation history before preparing the session.
            repeat(24) {
                val menu = world.expandChoices().candidates
                val choice = menu.firstOrNull { it.operationFamily == SemanticOperationFamily.PASS_PRIORITY }
                    ?: requireNotNull(menu.singleOrNull()) { "Pass-prefix fixture encountered an unplanned choice" }
                check(world.step(choice).accepted)
            }
            var forced = 0
            while (world.expandChoices().candidates.size == 1 && forced < 32) {
                check(world.step(world.expandChoices().candidates.single()).accepted)
                forced++
            }
            check(world.expandChoices().candidates.size > 1) { "Probe requires a searched multi-action root" }
            val actor = requireNotNull(world.actorToAct())
            check(world.informationState(actor).historyCursor > 0)
            val setupStart = cpu.processCpuTime
            val session = SearchPolicySession(world, actor, known,
                SearchPolicyConfig(particles = 8, simulations = 64, maxPolicyDecisions = 16,
                    explorationConstant = 1.4,
                    leaf = LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT),
                    actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
                    baseSeed = seed), defaultMonoRedOpponentPolicy(), "refactor-cpu-$sampleIndex")
            val searchStart = cpu.processCpuTime
            val wallStart = System.nanoTime()
            val selected = session.select(world, actor, seed + 10_000L)
            val wallNanos = System.nanoTime() - wallStart
            val searchCpuNanos = cpu.processCpuTime - searchStart
            val result = (selected as? RootActionSelection.Searched)?.search
                ?: error("Probe must perform search rather than singleton selection")
            assertEquals(64, result.candidates.sumOf { it.visits })
            assertTrue(searchCpuNanos > 0)
            if (measured) lines += buildJsonObject {
                val raw = PolicyJson.format.encodeToJsonElement(InformationSetSearchResult.serializer(), result).jsonObject
                put("searchBehavior", JsonObject(raw + ("diagnostics" to
                    JsonObject(raw.getValue("diagnostics").jsonObject - "evaluatorNanos"))))
                put("sourceSha", sourceSha)
                put("enginePin", provenance.getValue("engine_pin"))
                put("mode", if (v2) "v2" else "default")
                put("sample", sampleIndex)
                put("seed", seed)
                put("prefixAcceptedDecisions", 24 + forced)
                put("observerHistoryCursor", world.informationState(actor).historyCursor)
                put("particles", 8)
                put("simulations", 64)
                put("maxPolicyDecisions", 16)
                put("sessionPreparationCpuNanos", searchStart - setupStart)
                put("searchCpuNanos", searchCpuNanos)
                put("searchWallNanos", wallNanos)
                put("measurementScope", "session.select only; preparation separate; process CPU includes JVM background work")
                put("fixtureScope", "public deck, pass-only prefix; no representative-game claim")
                put("jdk", System.getProperty("java.version"))
                put("os", System.getProperty("os.name"))
                put("availableProcessors", Runtime.getRuntime().availableProcessors())
            }.toString()
        }

        // Warm both configurations before retaining any measured rows.
        repeat(2) { index -> sample(false, -index - 1, false); sample(true, -index - 1, false) }
        repeat(samples) { index ->
            val order = if (index % 2 == 0) listOf(false, true) else listOf(true, false)
            order.forEach { v2 -> sample(v2, index, true) }
        }
        val output = Path.of("build", "refactor-cpu", "samples.jsonl")
        Files.createDirectories(output.parent)
        Files.writeString(output, lines.joinToString("\n", postfix = "\n"))
        assertEquals(samples * 2, lines.size)
    }

    private fun publicDeck(): Map<String, Int> {
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null) {
            val path = directory.resolve("fixtures/decks/mono-red-standard-2026-07-30.json")
            if (Files.exists(path)) return researchJson.parseToJsonElement(Files.readString(path))
                .jsonObject.getValue("mainDeck").jsonObject.mapValues { it.value.jsonPrimitive.content.toInt() }
            directory = directory.parent
        }
        error("Public deck fixture not found")
    }
}
