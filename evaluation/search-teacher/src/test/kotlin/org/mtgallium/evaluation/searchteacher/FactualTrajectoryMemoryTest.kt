package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlin.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mtgallium.agent.infoset.argentum.ArgentumRawTransition
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.evaluation.searchteacher.replay.*

/** Public synthetic projection/validation/output load; it does not execute an engine policy or fit. */
@Tag("public-source")
class FactualTrajectoryMemoryTest {
    @Test
    fun `overlapping complete projections retain independent growing histories with bounded serialization`() {
        val decisions = integerProperty("decisions", 16)
        val objectsPerEvent = integerProperty("objects", 4)
        val workers = integerProperty("workers", 2)
        val batches = integerProperty("batches", 2)
        val minimumBytes = System.getProperty("mtgallium.factual.memory.minimumBytes", "1").toLong()
        require(decisions >= 4 && objectsPerEvent > 0 && workers in 1..8 && batches >= 2 && minimumBytes > 0)
        val root = Files.createTempDirectory("factual-memory-public-")
        val memory = ManagementFactory.getMemoryMXBean()
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        val initialGcMillis = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }
        val peak = AtomicLong(memory.heapMemoryUsage.used)
        val sampler = Executors.newSingleThreadScheduledExecutor()
        sampler.scheduleAtFixedRate({ peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf) }, 0, 10, TimeUnit.MILLISECONDS)
        val started = System.nanoTime()
        try {
            println("FACTUAL_MEMORY_RUNTIME java=${System.getProperty("java.version")} vm=${System.getProperty("java.vm.name")} args=${ManagementFactory.getRuntimeMXBean().inputArguments}")
            repeat(batches) { batch ->
                val overlap = CyclicBarrier(workers)
                val sizes = parallelMapOrdered(workers, workers) { worker ->
                    val task = batch * workers + worker
                    projectAndWrite(root.resolve("task-$task"), task, decisions, objectsPerEvent, overlap, peak).also {
                        assertTrue(it >= minimumBytes, "Synthetic trajectory is too small for the requested validation: $it < $minimumBytes")
                    }
                }
                println("FACTUAL_MEMORY_BATCH batch=$batch workers=$workers decisions=$decisions objects=$objectsPerEvent bytes=${sizes.joinToString(",")} usedHeapBytes=${memory.heapMemoryUsage.used} sampledPeakHeapBytes=${peak.get()}")
            }
            val elapsed = (System.nanoTime() - started) / 1e9
            val maximumHeap = memory.heapMemoryUsage.max
            println("FACTUAL_MEMORY_COMPLETE batches=$batches workers=$workers decisions=$decisions elapsedSeconds=$elapsed sampledPeakHeapBytes=${peak.get()} maximumHeapBytes=$maximumHeap gcMillis=${collectors.sumOf { it.collectionTime.coerceAtLeast(0) } - initialGcMillis}")
            // A measured engineering margin, not a guarantee for private engine replay or later search.
            val maximumFraction = System.getProperty("mtgallium.factual.memory.maximumHeapFraction", "0.85").toDouble()
            assertTrue(maximumFraction > 0 && maximumFraction < 1)
            assertTrue(peak.get() < maximumHeap * maximumFraction, "No requested heap headroom in the sampled public load")
        } finally {
            sampler.shutdownNow()
            sampler.awaitTermination(10, TimeUnit.SECONDS)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun projectAndWrite(directory: Path, task: Int, count: Int, objects: Int, overlap: CyclicBarrier, peak: AtomicLong): Long {
        Files.createDirectories(directory)
        val fixture = MemoryReplay(directory, task, count, objects)
        val replay = readVerifiedCanonicalSemanticReplay(fixture.path)
        val (rows, audit) = projectFactualIncumbentTrajectory(replay, "p0", fixture.world())
        assertEquals(count, rows.size)
        assertEquals((0 until count).toList(), rows.map { it.decisionIndex })
        assertEquals((0 until count).toList(), rows.map { it.information.history.size })
        assertEquals(1.0, rows.last().actualTerminalPayoff)
        // Equal remembered events are freshly allocated at each snapshot. Shared repeated objects
        // would make a misleadingly cheap heap fixture despite producing the same output bytes.
        val first = rows[1].information.history[0]
        val second = rows[2].information.history[0]
        assertEquals(first, second)
        assertNotSame(first, second)
        assertNotSame(first.payload, second.payload)
        assertNotSame(first.payload.getValue("objects"), second.payload.getValue("objects"))
        // Every worker retains its complete rows until all peers have reached this phase.
        // This is an explicit simultaneous-live-data witness, not just concurrent submission.
        overlap.await(180, TimeUnit.SECONDS)
        peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().heapMemoryUsage.used, ::maxOf)
        val output = directory.resolve("trajectory.json")
        // Same rows nesting and production streaming writer as the admitted report. The wrapper
        // excludes provenance setup; source/engine admission and policy execution are not benchmarked.
        writeEvidenceJsonStream(output, MemoryTrajectory(rows, audit), MemoryTrajectory.serializer())
        return Files.size(output)
    }

    private fun integerProperty(name: String, fallback: Int) =
        System.getProperty("mtgallium.factual.memory.$name", fallback.toString()).toInt()
}

@Serializable
private data class MemoryTrajectory(
    val rows: List<FactualIncumbentTrajectoryRow>,
    val replayAudit: OutcomeStateReplayCompatibilityAudit,
)

/** Synthetic complete canonical chain plus a deterministic public world; no private game inputs. */
private class MemoryReplay(directory: Path, private val task: Int, private val count: Int, private val objectCount: Int) {
    private val players = listOf(EntityId("e0"), EntityId("e1"))
    private val states = (0..count).map { index -> GameState(turnNumber = 1,
        activePlayerId = players[0], priorityPlayerId = if (index == count) null else players[index % 2],
        turnOrder = players, timestamp = index.toLong(), winnerId = players[0].takeIf { index == count },
        gameOver = index == count) }
    private val actions = (0 until count).map { PassPriority(players[it % 2]) }
    private val choices = (0 until count).map { index -> SemanticChoice.create(kind = SemanticChoiceKind.ACTION,
        operationFamily = SemanticOperationFamily.PASS_PRIORITY, display = SemanticChoiceDisplay("Pass priority"),
        canonicalPayload = buildJsonObject { put("type", "PassPriority"); put("playerId", "p${index % 2}") }) }
    val path = directory.resolve("synthetic.privileged.replay.jsonl.gz")
    init {
        val recorder = CanonicalReplayRecorder("public-memory-$task", "2026-01-01T00:00:00Z",
            FACTUAL_INCUMBENT_ARGENTUM_REVISION, "public synthetic memory fixture", listOf("p0", "p1"), states.first())
        GZIPOutputStream(Files.newOutputStream(path)).bufferedWriter().use { writer ->
            fun record(value: CanonicalReplayRecord) {
                writer.write(CanonicalReplayJson.encodeToString(CanonicalReplayRecord.serializer(), value))
                writer.newLine()
            }
            record(recorder.header)
            actions.indices.forEach { index ->
                record(recorder.appendAction(ReplayTransitionOrigin.POLICY, actions[index], true, states[index + 1],
                    extensions = buildJsonObject {
                        put("mtgallium.decisionIndex", index)
                        put("mtgallium.semanticChoice", PolicyJson.format.encodeToJsonElement(choices[index]))
                    }))
            }
            record(recorder.finish(ReplayCompletionStatus.COMPLETE, states.last(), winnerId = "p0"))
        }
    }

    fun world(): SemanticReplayWorld = object : SemanticReplayWorld {
        private var index = 0
        override fun actorToAct(): String? = "p${index % 2}".takeIf { index < count }
        override fun informationState(viewer: String): PolicyInformationState {
            val actor = actorToAct()
            // Reconstruct every event independently for each growing snapshot, including nested
            // maps, lists, event objects and identity strings. No padding string or repeated event cache.
            val history = List(index) { event -> publicHistoryEvent(event) }
            return PolicyInformationState(actingPlayerId = actor, observation = PolicyObservation(
                perspectivePlayerId = viewer, turnNumber = 1, phase = "BEGINNING", step = "UPKEEP",
                activePlayerId = "p0", priorityPlayerId = actor,
                players = listOf(
                    PolicyPlayerView("p0", "First", 20, 0, 53, 0, 0, PolicyManaPool(), viewer == "p0", actor == "p0", false),
                    PolicyPlayerView("p1", "Second", 10, 0, 53, 0, 0, PolicyManaPool(), viewer == "p1", actor == "p1", false)),
                zones = emptyList(), stack = emptyList(), currentTurnStateComplete = true, pendingDecision = null,
                observationDigest = sha256("observation:$task:$viewer:$index")),
                informationStateDigest = sha256("information:$task:$viewer:$index"),
                historyCommitment = PolicyHistoryCommitment.replay(history), history = history,
                knowledge = PolicyKnowledgeState(perspectivePlayerId = viewer,
                    knownLibraryOrders = listOf(PolicyKnownLibraryOrder(viewer, 0, top = listOf("Mountain"))),
                    knowledgeDigest = sha256("knowledge:$task:$viewer:$index")),
                candidates = if (actor == viewer) listOf(choices[index]) else emptyList(),
                terminated = index == count, winnerId = "p0".takeIf { index == count })
        }
        override fun expandChoices() = listOf(choices[index])
        override fun stepWithReplayTrace(choice: SemanticChoice): SemanticReplayStep {
            require(choice == choices[index])
            val current = index++
            return SemanticReplayStep(SearchStepResult(accepted = true, privateToActor = false), listOf(
                ArgentumRawTransition(actions[current], states[current], states[current + 1], emptyList(), null)))
        }
        override fun authoritativeState() = states[index]
        override fun terminalPayoff(rootPlayer: String): Double? =
            if (index != count) null else if (rootPlayer == "p0") 1.0 else -1.0
    }

    private fun publicHistoryEvent(event: Int) = PolicyHistoryEvent(event.toLong(),
        PolicyAudience(PolicyAudienceScope.PUBLIC), "p${event % 2}", PolicyHistoryEventKind.OBJECT_STATE,
        buildJsonObject {
            put("turn", event / 8 + 1)
            putJsonArray("objects") {
                repeat(objectCount) { card -> addJsonObject {
                    put("objectId", "public-object-$task-$event-$card")
                    put("name", if (card % 2 == 0) "Mountain" else "Goblin Token")
                    put("controller", "p${card % 2}")
                    put("zone", "BATTLEFIELD")
                    put("tapped", (event + card) % 2 == 0)
                    put("power", card % 4)
                    put("toughness", 1 + card % 4)
                    putJsonArray("types") { add(if (card % 2 == 0) "Land" else "Creature") }
                    putJsonObject("counters") { put("charge", event % 3) }
                } }
            }
        })
}
